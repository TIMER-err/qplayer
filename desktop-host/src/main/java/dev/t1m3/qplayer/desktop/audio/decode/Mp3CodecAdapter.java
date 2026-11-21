package dev.t1m3.qplayer.desktop.audio.decode;

import java.io.IOException;
import java.io.InputStream;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.decoder.SampleBuffer;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.desktop.audio.SeekableByteSource;
import dev.t1m3.qplayer.util.Logger;

/**
 * MP3 适配器，底座是 jlayer（纯 Java）。
 *
 * <h2>无缝播放（gapless）</h2>
 *
 * <p>上游把 gapless 定为<strong>默认开启</strong>：关掉它，MP3 开头会带上编码器延迟产生的
 * 静音，而过渡引擎是按「文件里的采样位置」算交叉点的，位置整体偏移就没有一处对得上。
 *
 * <p>jlayer 本身做不到这件事：它只解析 Xing/VBRI 的帧数与字节数，完全不看紧随其后的
 * LAME 扩展标签，因此不知道编码器延迟与填充。这里的做法是自己解析 {@link Mp3XingTag}
 * 拿到 delay/padding，然后：
 *
 * <ul>
 *   <li><strong>头部</strong>：丢掉 {@code delay + 528 + 1} 个采样（含 LAME 的 528 基准）</li>
 *   <li><strong>尾部</strong>：在原始采样 {@code 帧数 × 每帧采样数 − (padding − 528 − 1)} 处收尾</li>
 * </ul>
 *
 * <h2>两根基线：本类的游标建在「音频帧轴」上（这是 2026-10-06 修掉的那条轴错）</h2>
 *
 * <p>同一批采样有两套编号，差<strong>整整一个 MPEG 帧</strong>，混用就会让整条时间轴早/晚
 * 24 ms（48 kHz 下一个 MPEG1 帧 = 1152 采样）：
 *
 * <ol>
 *   <li><strong>音频帧轴</strong>（{@link Mp3XingTag} 与 LAME 标签用的那根）：
 *       第一个<em>音频</em>帧的采样 0 = 0。{@code headTrim = 1105}、
 *       {@code endRaw = 帧数 × 1152 − 尾部裁剪} 全在这根轴上，交付长度
 *       {@code endRaw − headTrim} 与 ffmpeg 解出来的采样数逐位相等（实测：
 *       {@code 7931 × 1152 − 1105 − 407 = 9135000}）。</li>
 *   <li><strong>jlayer 产出采样轴</strong>：jlayer 会把开头的 Info/Xing 帧<em>当成一帧真解码</em>
 *       （实测：帧 0 解出 1152 个采样，是静音），所以它的产出采样 0 在音频帧轴的 −1152 处。</li>
 * </ol>
 *
 * <p>本类的 {@link #cursor}、{@link #headTrim}、{@link #endRaw} 因此<strong>全在音频帧轴上</strong>
 * （游标从 {@code −samplesPerFrame} 起算，Info 帧落在负半轴、被裁剪窗口天然丢掉），
 * 而 {@code headTrim} 保持 {@link Mp3XingTag#headTrimFrames()} 的原值 1105 不动。
 * 这样「1105」与「1152」各自只出现在自己的轴上；把它们相加/混用就是这个类之前
 * 整条早 24 ms、定位读整条晚 24 ms 的根因。
 *
 * <p>没有 Xing/Info 标签的老式 CBR（或者标签里 delay/padding 全零）不做任何裁剪：
 * 拿不到数字就别猜，猜错会切掉真实的开头。这条取舍在 {@link Mp3XingTag} 里有详细注释。
 *
 * <h2>定位</h2>
 *
 * <p>jlayer 的流不能回退，所以定位只能从头推进：主体用<em>扫帧头</em>（{@code readFrame} +
 * {@code closeFrame} 不做子带合成，比「解码再丢弃」便宜两个数量级，而且按真实帧数累计
 * 采样，VBR 也准），只在目标帧前面留 {@link #RESERVOIR_WARMUP_FRAMES} 帧改用真<em>解码</em>
 * 推进——原因是比特池：只扫帧头时解码器没有上下文，离开扫描点后的头两帧会解出
 * <strong>0 个采样</strong>（实测），于是后面每一帧的内容都整体晚一帧落到游标位置上，
 * 定位读就比真值晚 24 ms（这就是「定位没预热」那条）。用真解码走过最后几帧把比特池
 * 喂满，目标帧才是对的；被预热的这几帧本来就整帧落在目标位置之前，采样照旧丢弃。
 *
 * <p>扫到「再读一帧就越过目标」为止，然后用 {@code skipInFrame} 丢掉最后一帧开头
 * 若干个采样，于是落点<em>精确</em>等于请求位置（在裁剪窗口允许的范围内）。
 */
final class Mp3CodecAdapter implements CodecAdapter {

    /** 首帧 + Xing 头 + LAME 标签，2 KB 绰绰有余。 */
    private static final int PREFIX_BYTES = 2048;

    /**
     * 连续坏帧上限。抓轨文件零星坏帧要跳过（上游明文要求），但整段乱码时不能无限重试——
     * 那会把音频线程锁死。超限就当流结束（文件本身已开不出来时，open 阶段就报不支持了）。
     */
    private static final int MAX_CONSECUTIVE_BAD_FRAMES = 1024;

    /**
     * 定位时用「真解码」推进的尾部帧数（喂满 Layer III 的比特池，见类注释「定位」）。
     *
     * <p><strong>不是靠感觉调的</strong>，是量出来的（{@code Automix.readStereoWindow} 读窗口
     * 与 ffmpeg 真值逐位对齐求偏移；素材 = 本机 skydive.mp3 / 春めく.mp3 / LAME 重编码对照件，
     * 位置 5 / 30 / 60 / 120 / 180 s）：
     *
     * <pre>
     *   预热帧数        结果
     *   0              偏 +2304（skydive）/ +1152（重编码对照）；
     *                  春めく 5 s 处 +1152、30 s 之后 +2304
     *                  —— 残留偏差文件相关且位置相关，正对应「只扫帧头时头两帧解出 0 个采样」
     *   1              skydive 仍偏 +1152，其余 0
     *   2              全部素材、全部位置 0 帧
     *   3 / 4 / 6 / 8  全部 0 帧（本设计里预热的这几帧整帧落在目标之前、采样本来就要丢，
     *                  所以多解<em>不会</em>过头，只是白花时间）
     * </pre>
     *
     * <p>取 3 而不是刚好够的 2：实测扫描点之后第 1、2 帧产出 0 个采样，第 3 帧落点虽对
     * 但 ncc 只有 0.41（比特池只喂了一帧），第 4 帧起才 ncc = 1.0000。多留一帧让
     * <em>目标帧自己</em>稳稳落在喂满之后，代价是每次定位多解 1152 个采样（≈0.1 ms）。
     */
    private static final int RESERVOIR_WARMUP_FRAMES = 3;

    private static final float INV_INT16 = 1f / 32768f;

    private final SeekableByteSource src;
    private final TrackSpec spec;
    private final int channels;
    /** 每帧采样数（MPEG1 Layer III = 1152，MPEG2/2.5 Layer III = 576）。 */
    private final int samplesPerFrame;
    private final float[] scratch;
    /** 首帧是 Info/Xing（或 VBRI）信息帧而不是音频；见 {@link Mp3XingTag#headerFrame}。 */
    private final boolean headerFrame;

    /** 头部要丢掉的采样数（编码器延迟）；<strong>音频帧轴</strong>，没有 gapless 信息时为 0。 */
    private final long headTrim;
    /**
     * 交付右边界（<strong>音频帧轴</strong>采样下标，= 帧数 × 每帧采样数 − 尾部填充）；
     * {@code Long.MAX_VALUE} = 未知（此时不裁尾部填充）。
     */
    private final long endRaw;
    /** 有效采样总数（= endRaw − headTrim）；未知时 -1，用于 TrackSpec。 */
    private final long validFrames;

    private Bitstream bitstream;
    private javazoom.jl.decoder.Decoder decoder;
    /**
     * 下一帧 {@code samples[0]} 对应的<strong>音频帧轴</strong>采样下标；起点是
     * {@code −samplesPerFrame}（Info 帧占负半轴那一格），见类注释「两根基线」。
     */
    private long cursor;
    /** 下一帧开头还要丢掉的采样数（精确 seek 用，正常为 0）。 */
    private int skipInFrame;
    private int badFrames;
    private boolean eos;

    Mp3CodecAdapter(SeekableByteSource src) {
        this.src = src;
        Mp3XingTag tag = Mp3XingTag.parse(readPrefix(src));
        if (tag == null) {
            throw AudioException.unsupportedFormat("文件里没有可解码的音轨（找不到 MPEG 帧头）");
        }
        long raw = tag.rawFrames();
        this.headTrim = tag.headTrimFrames();
        this.endRaw = raw > 0 ? raw - tag.tailTrimFrames() : Long.MAX_VALUE;
        long valid = tag.validFrames();
        this.validFrames = valid;
        this.channels = tag.channels;
        this.samplesPerFrame = tag.samplesPerFrame;
        this.headerFrame = tag.headerFrame;
        this.scratch = new float[Math.max(samplesPerFrame, 1152) * channels];
        this.spec = new TrackSpec("mp3", tag.sampleRate, ChannelLayout.of(tag.channels),
                null, valid > 0 ? Long.valueOf(valid) : null,
                CodecNames.isLossless("mp3"));

        Logger.info("MP3 解码：{} Hz {}ch，{}；gapless {}{}", tag.sampleRate, tag.channels,
                tag.frames > 0 ? tag.frames + " 帧" : "帧数未知",
                tag.hasGaplessInfo() ? "已开启（丢弃头部 " + headTrim + " 采样 / 尾部 "
                        + tag.tailTrimFrames() + " 采样）" : "未开启",
                tag.hasGaplessInfo() ? "" : "（没有 Xing/LAME 标签，拿不到编码器延迟与填充）");

        // 从头开始：游标回到音频帧轴的 0（Info 帧在负半轴、被裁剪窗口丢掉），
        // skipInFrame=0，之后交给 decodeNext 的求交。
        reposition(0L);
    }

    @Override
    public TrackSpec spec() {
        return spec;
    }

    @Override
    public float[] scratch() {
        return scratch;
    }

    /** 诊断开关：{@code -Dqplayer.trace.mp3=true} 时打印前若干次解码的内部状态。 */
    private static final boolean TRACE_MP3 = Boolean.getBoolean("qplayer.trace.mp3");
    private int traceCount;

    @Override
    public int decodeNext() {
        if (eos) {
            return -1;
        }
        if (TRACE_MP3 && traceCount < 8) {
            traceCount++;
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[MP3 追踪] #%d cursor=%d endRaw=%d headTrim=%d eos=%s bad=%d",
                    Integer.valueOf(traceCount), Long.valueOf(cursor), Long.valueOf(endRaw),
                    Long.valueOf(headTrim), Boolean.valueOf(eos), Integer.valueOf(badFrames)));
        }
        while (true) {
            if (cursor >= endRaw) {
                if (TRACE_MP3) {
                    System.out.println("[MP3 追踪] 结束：cursor >= endRaw（cursor=" + cursor
                            + " endRaw=" + endRaw + "）");
                }
                // 裁剪窗口的右边界到了（尾部填充）：流其实还有数据，但对听感来说已经结束。
                eos = true;
                return -1;
            }
            Header header;
            try {
                header = bitstream.readFrame();
            } catch (BitstreamException e) {
                if (TRACE_MP3 && badFrames < 4) {
                    System.out.println("[MP3 追踪] readFrame 抛 BitstreamException："
                            + e.toString() + "（cursor=" + cursor + "）");
                }
                // 坏包跳过而不是中断：jlayer 的 readFrame 内部已经尝试跳过一帧，
                // 走到这里说明连跳过都失败了。继续试，连续太多次才收尾。
                if (++badFrames > MAX_CONSECUTIVE_BAD_FRAMES) {
                    eos = true;
                    return -1;
                }
                continue;
            }
            if (header == null) {
                if (TRACE_MP3) {
                    System.out.println("[MP3 追踪] 结束：readFrame 返回 null（cursor=" + cursor
                            + "）");
                }
                eos = true;
                return -1;
            }

            int frames;
            short[] samples;
            try {
                Object decoded = decoder.decodeFrame(header, bitstream);
                bitstream.closeFrame();
                badFrames = 0;
                if (!(decoded instanceof SampleBuffer)) {
                    continue;
                }
                SampleBuffer buffer = (SampleBuffer) decoded;
                samples = buffer.getBuffer();
                frames = buffer.getBufferLength() / channels;
            } catch (JavaLayerException e) {
                if (TRACE_MP3 && badFrames < 4) {
                    System.out.println("[MP3 追踪] decodeFrame 抛 JavaLayerException："
                            + e.toString() + "（cursor=" + cursor + "）");
                }
                try {
                    bitstream.closeFrame();
                } catch (RuntimeException ignored) {
                    // closeFrame 失败无所谓，下一轮 readFrame 会重新同步。
                }
                if (++badFrames > MAX_CONSECUTIVE_BAD_FRAMES) {
                    eos = true;
                    return -1;
                }
                continue;
            }

            long frameStart = cursor;
            int skip = skipInFrame;
            if (skip >= frames) {
                // 整帧都在定位落点之前：丢掉它继续（理论上只在 spf 变化时发生）。
                skipInFrame = skip - frames;
                cursor = frameStart + frames;
                continue;
            }
            skipInFrame = 0;
            cursor = frameStart + frames;

            long keptStart = frameStart + skip;
            // 裁剪窗口全部在音频帧轴上：开头丢掉编码器延迟（≥ 1105）、尾部停在填充之前。
            // Info/Xing 帧占的是负半轴那一格（cursor 从 −samplesPerFrame 起），必然整帧出局。
            long from = Math.max(keptStart, headTrim);
            long to = Math.min(cursor, endRaw);
            if (to <= from) {
                // 整帧都落在裁剪窗口外（Info 帧、开头的编码器延迟、尾部的填充）或解出 0 个采样
                // （坏帧、截断文件）——这一包没有产出，上层会继续取下一包，绝不会当成流结束。
                return 0;
            }
            int outFrames = (int) (to - from);
            int srcIndex = (int) (from - frameStart) * channels;
            for (int i = 0; i < outFrames * channels; i++) {
                scratch[i] = samples[srcIndex + i] * INV_INT16;
            }
            return outFrames;
        }
    }

    @Override
    public long seekToFrame(long targetFrame) {
        long rawTarget = targetFrame + headTrim;
        if (endRaw != Long.MAX_VALUE && rawTarget > endRaw) {
            rawTarget = endRaw;
        }
        long landedRaw = reposition(rawTarget);
        long actual = landedRaw - headTrim;
        if (actual < 0) {
            actual = 0;
        }
        if (validFrames >= 0 && actual > validFrames) {
            actual = validFrames;
        }
        return actual;
    }

    /**
     * 从文件开头重建解码器，把游标推进到<strong>音频帧轴</strong>的采样位置 {@code rawTarget}。
     *
     * <p>为什么必须重建：jlayer 的 {@code Bitstream} 只进不退，而且 Layer III 有跨帧的
     * 比特池，换位置就得换新的 {@code Decoder}。
     *
     * <p>推进分两段（理由见类注释「定位」，实测数据也在那里）：
     *
     * <ol>
     *   <li><b>快扫</b>：只读帧头（{@code readFrame} + {@code closeFrame}，不做子带合成），
     *       扫到目标帧往前 {@link #RESERVOIR_WARMUP_FRAMES} 帧为止；</li>
     *   <li><b>预热</b>：最后那几帧改用 {@code decodeFrame} 真解码、采样直接丢弃——
     *       它们整帧都在目标位置之前（本来就要丢），但解码动作把比特池喂满，
     *       目标帧才不会解出 0 个采样、内容才不会整体晚一帧。</li>
     * </ol>
     *
     * @return 实际落到的采样位置（流比目标短时更早）
     */
    private long reposition(long rawTarget) {
        try {
            src.seek(0);
        } catch (IOException e) {
            throw AudioException.io("MP3 定位失败", e);
        }
        bitstream = new Bitstream(new SrcStream());
        decoder = new javazoom.jl.decoder.Decoder();
        badFrames = 0;
        eos = false;

        // 游标在音频帧轴上。首帧是 Info/Xing（或 VBRI）信息帧时不占音频帧轴的位置
        // ——jlayer 会把它真解出 1152 个采样，所以它在游标上占负半轴那一格
        // （[−samplesPerFrame, 0)），裁剪窗口天然把它整帧丢掉。没有信息帧的文件（老式 CBR）
        // 随便一帧都是音频，游标就从 0 起。
        long scanned = headerFrame ? -samplesPerFrame : 0L;
        boolean hitEof = false;

        // 第一段：快扫到「目标帧往前 RESERVOIR_WARMUP_FRAMES 帧」。
        // 下界取 scanned 的起点：信息帧落在预热窗口里（只可能发生在文件最开头）时，
        // 交给第二段真解码——既喂了比特池，又省得它只被读个帧头。
        long scanLimit = Math.max(scanned,
                rawTarget - (long) RESERVOIR_WARMUP_FRAMES * samplesPerFrame);
        while (scanned + samplesPerFrame <= scanLimit) {
            Header header = readHeaderQuietly();
            if (header == null) {
                hitEof = true;
                break;
            }
            scanned += samplesPerFrame;
            bitstream.closeFrame();
        }

        // 第二段：真解码推进剩下的（至多 RESERVOIR_WARMUP_FRAMES 帧），采样丢弃。
        // 目标是「再解一帧就越过 rawTarget」，于是下一帧正好是包含 rawTarget 的那一帧。
        while (!hitEof && scanned + samplesPerFrame <= rawTarget) {
            Header header = readHeaderQuietly();
            if (header == null) {
                hitEof = true;
                break;
            }
            try {
                decoder.decodeFrame(header, bitstream);
            } catch (JavaLayerException ignored) {
                // 坏帧：采样本就不要了，编码器状态照样往下走（解码失败不该让定位失败）。
            } catch (RuntimeException ignored) {
                // jlayer 对截断文件偶尔抛运行时异常；同上，交给下面的 readFrame 重新同步。
            }
            bitstream.closeFrame();
            scanned += samplesPerFrame;
        }

        cursor = scanned;
        skipInFrame = hitEof ? 0 : (int) (rawTarget - scanned);
        return hitEof ? scanned : rawTarget;
    }

    /** 读帧头；解析不出下一帧时返回 {@code null}（真正的 I/O 错误仍向上抛）。 */
    private Header readHeaderQuietly() {
        try {
            return bitstream.readFrame();
        } catch (BitstreamException e) {
            return null;
        }
    }

    @Override
    public void close() {
        if (bitstream != null) {
            try {
                bitstream.close();
            } catch (BitstreamException ignored) {
                // 关流失败没有补救动作。
            }
            bitstream = null;
        }
        src.close();
    }

    /** 读文件开头（跳过 ID3v2）的一段字节，用于解析首帧与 Xing/LAME 标签。 */
    private static byte[] readPrefix(SeekableByteSource src) {
        byte[] start = readAt(src, 0L, 10);
        long offset = 0L;
        if (start.length >= 10 && start[0] == 'I' && start[1] == 'D' && start[2] == '3') {
            // ID3v2 头：10 字节，第 6..9 字节是 syncsafe 的标签长度（每字节只用 7 位）。
            // 带封面的标签可能几十 KB，所以先算偏移再去读帧头，而不是盲读一大段。
            long size = ((start[6] & 0x7fL) << 21) | ((start[7] & 0x7fL) << 14)
                    | ((start[8] & 0x7fL) << 7) | (start[9] & 0x7fL);
            offset = 10L + size;
        }
        return readAt(src, offset, PREFIX_BYTES);
    }

    private static byte[] readAt(SeekableByteSource src, long pos, int len) {
        try {
            src.seek(pos);
        } catch (IOException e) {
            throw AudioException.io("MP3 头部定位失败", e);
        }
        byte[] b = new byte[len];
        int got = 0;
        while (got < len) {
            int n;
            try {
                n = src.read(b, got, len - got);
            } catch (IOException e) {
                throw AudioException.io("MP3 头部读取失败", e);
            }
            if (n < 0) {
                break;
            }
            got += n;
        }
        if (got == len) {
            return b;
        }
        byte[] trimmed = new byte[got];
        System.arraycopy(b, 0, trimmed, 0, got);
        return trimmed;
    }

    /**
     * 把字节源适配成 jlayer 要的 {@link InputStream}。
     *
     * <p>刻意<strong>不</strong>重写 {@code close()}：jlayer 的 {@code Bitstream.close()}
     * 会关掉这个源，而定位时我们要在同一份字节源上重建 {@code Bitstream}。
     * {@code InputStream.close()} 默认是空实现，正好。
     */
    private final class SrcStream extends InputStream {

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : (b[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return src.read(b, off, len);
        }

        /**
         * <b>必须实现 {@code available()}</b>（2026-10-07 修一个"某首歌直接播不了"的真 bug ✗）。
         *
         * <p>实测证据（`SrcStreamProbe`，可复跑）：同一个文件、同一个 jlayer，
         * 只是把这个薄包装的 {@code available()} 从"默认 0"换成"剩余字节数"，
         * 解码就从 **1 帧就停** ✗ 变成 **正常出帧** ✓ —— jlayer 在跳 ID3 / 推进帧的时候
         * **会问 available()**，拿到 0 就当成流结束 ✗。
         *
         * <p>与上游的差异（刻意）：上游用带缓冲的容器读取器，没这个问题；
         * 我们这里是 `RandomAccessFile` 上的薄包装 ⇒ 不补这一行，凡是走到"要跳大标签"
         * 那条路的文件（实测 `愛とU（爱与你） - 虎纹章鱼.mp3`，ID3v2.3 标签 2.15 MB）
         * 就会静默地一帧都解不出来，报"扫不出能量包络（音源是空的？）"✗。
         *
         * <p>返回 {@code min(剩余, Integer.MAX_VALUE)}：接口只能给 int，
         * 而 2 GB 以上的文件在本地库里也存在（返回 MAX_VALUE 的语义是"至少还有这么多"）。
         */
        @Override
        public int available() throws IOException {
            long rest = src.size() - src.position();
            return rest <= 0L ? 0 : (int) Math.min(rest, (long) Integer.MAX_VALUE);
        }
    }
}
