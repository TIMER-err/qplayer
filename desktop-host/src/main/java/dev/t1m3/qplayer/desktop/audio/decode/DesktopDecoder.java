package dev.t1m3.qplayer.desktop.audio.decode;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.desktop.audio.SeekableByteSource;

/**
 * 桌面端解码层：Sonalis 的 {@code sonalis-decode} 在宿主侧的实现，底座换成
 * jflac / jlayer / stb_vorbis / 自家 RIFF 读取（每个格式一个 {@link CodecAdapter}）。
 *
 * <p>结构与语义<strong>照搬上游</strong>，注释里保留上游踩坑换来的理由：
 *
 * <ol>
 *   <li><strong>输出是平面 f32</strong>：{@link #decodeInto} 把交错暂存拆进
 *       {@link AudioBuf} 的各个声道（用 {@code channelWriter(c)}），最后 {@code setFrames(n)}。
 *       返回写入帧数，0 表示到结尾；返回前一直解码到填满容量或流结束，调用方不必自己循环。</li>
 *   <li><strong>暂存（pending）语义</strong>：一包数据一旦取出就无法退回，而它可能放不进
 *       调用方给的缓冲（包长与缓冲大小无关）。没有暂存，多出的部分只能丢弃——那正是
 *       「3 秒的文件只放出 2.67 秒」的根因。交付顺序因此是硬约束：
 *       <strong>先清空 pending，再解码新包</strong>（见 {@link #fillPending()}）。</li>
 *   <li><strong>seek 必须作废 pending</strong>：定位之后所有暂存与游标全部作废，否则跳转后
 *       会先放出一小段旧内容，听起来像「跳过去又弹回来」。seek 返回<em>实际</em>落到的位置。</li>
 *   <li><strong>坏包跳过而不是中断</strong>：抓轨文件常有零星坏帧，为此停掉整首歌不可接受。
 *       各适配器按 {@link CodecAdapter#decodeNext()} 的约定返回 0 表示「跳过这一包」。</li>
 *   <li><strong>无缝播放默认开启</strong>：MP3 的编码器延迟与填充在
 *       {@link Mp3CodecAdapter} + {@link Mp3XingTag} 里补偿；没有可选开关，因为关掉它
 *       过渡引擎算出来的位置就是错的。</li>
 * </ol>
 *
 * <p>与上游的差异（都写在对应位置的注释里）：音源句柄是 {@code String}（本地路径或
 * http(s) URL，可带请求头）而不是 {@code Path}；错误是异常而不是 {@code Result}；
 * 「取包 + 解码」拆给了适配器。
 */
public final class DesktopDecoder implements Decoder {

    /**
     * 暂存预分配容量（帧）。
     *
     * <p>容量必须覆盖<strong>任何单包</strong>：上游在这里只给 16384，而当时
     * {@code decode_into} 在一包装不下时会把多出的部分丢弃（注释还写着「不影响听感」），
     * 实测后果是 11% 的音频凭空消失——3 秒的文件只放出 2.67 秒。WAV/PCM 的包可能远大于
     * 常见编码，所以给到 65536 帧（双声道 f32 约 512 KB，一次性分配）。
     */
    private static final int SCRATCH_FRAMES = 65536;

    /**
     * 连续空包上限。
     *
     * <p>上游的循环对「解出 0 帧」的包是 {@code continue}，没有次数上限；万一某个适配器
     * 卡在「永远返回 0」的状态，音频线程就会死循环。这里是 Java 侧额外加的一道保险
     * （属于刻意的差异）：连续这么多次都没产出，就报解码错误，让上层换源重试。
     */
    private static final int MAX_CONSECUTIVE_EMPTY_PACKETS = 4096;

    private final CodecAdapter adapter;
    private final TrackSpec spec;

    /** 已解码但尚未交付的交错采样，容量 = {@link #pendingCapacityFrames} × 声道数。 */
    private float[] pending;
    /** pending 的容量（帧）。自己记账，扩容一次就够。 */
    private int pendingCapacityFrames;
    /** pending 里已交付的帧数。 */
    private int pendingOffset;
    /** pending 里有效的帧数。 */
    private int pendingFrames;

    /** 是否已经读到结尾。 */
    private boolean drained;
    /** 已解出的总帧数，用于报告进度。 */
    private long framesDecoded;
    private boolean closed;

    /**
     * 用适配器构造。
     *
     * <p>包内可见：格式适配器与测试（同包）都走这个入口；宿主只该用
     * {@link #open(String, Map)} 或安装好的 {@link Decoder#open}。
     */
    DesktopDecoder(CodecAdapter adapter) {
        this.adapter = adapter;
        this.spec = adapter.spec();
        int channels = spec.layout.count();
        this.pendingCapacityFrames = SCRATCH_FRAMES;
        this.pending = new float[SCRATCH_FRAMES * channels];
    }

    /**
     * 安装桌面端的解码器工厂。
     *
     * <p>宿主的启动路径调用一次（与音频后端初始化挨着写）；player-core 只认
     * {@link Decoder#install}，不认任何平台解码库。
     */
    public static void install() {
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, Map<String, String> headers) {
                return DesktopDecoder.open(source, headers);
            }
        });
    }

    /**
     * 打开一个音源（本地路径或 http(s) URL，可带提供商要求的请求头）。
     *
     * <p>容器识别按魔数走（URL 常常没有可用的扩展名）。上游只用扩展名做 hint，
     * 因为 symphonia 还有自己的探测；这里的底座没有探测能力，所以按字节判断：
     * 认不出的就按 MP3 试——它是网易云直链最常见的载荷，也是唯一没有固定魔数的格式。
     */
    public static DesktopDecoder open(String source, Map<String, String> headers) {
        Map<String, String> h = headers == null ? Collections.<String, String>emptyMap() : headers;
        SeekableByteSource bytes;
        try {
            bytes = SeekableByteSource.open(source, h);
        } catch (IOException e) {
            throw AudioException.io("打开音源失败：" + redact(source), e);
        }
        CodecAdapter adapter;
        try {
            adapter = createAdapter(sniff(bytes), bytes);
        } catch (RuntimeException e) {
            // 构造失败的适配器还没接管字节源（见 CodecAdapter 的所有权约定），这里收尾。
            bytes.close();
            throw e;
        }
        return new DesktopDecoder(adapter);
    }

    /** 打开一个音源（不带请求头）。 */
    public static DesktopDecoder open(String source) {
        return open(source, Collections.<String, String>emptyMap());
    }

    private static CodecAdapter createAdapter(String kind, SeekableByteSource bytes) {
        if ("flac".equals(kind)) {
            return new FlacCodecAdapter(bytes);
        }
        if ("ogg".equals(kind)) {
            return new OggCodecAdapter(bytes);
        }
        if ("wav".equals(kind)) {
            return new WavCodecAdapter(bytes);
        }
        return new Mp3CodecAdapter(bytes);
    }

    /** 读文件开头 12 字节判容器，读完回到 0（与旧 {@code PcmSource.sniff} 同一套判断）。 */
    private static String sniff(SeekableByteSource bytes) {
        byte[] head = new byte[12];
        try {
            bytes.seek(0);
            int got = 0;
            while (got < head.length) {
                int n = bytes.read(head, got, head.length - got);
                if (n < 0) {
                    break;
                }
                got += n;
            }
            bytes.seek(0);
        } catch (IOException e) {
            throw AudioException.io("读取音源头部失败", e);
        }
        if (head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') {
            return "ogg";
        }
        if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
            return "flac";
        }
        if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F') {
            return "wav";
        }
        return "mp3";
    }

    /** 网络直链常带 token，错误信息里不回显 query 部分。 */
    private static String redact(String source) {
        if (source == null) {
            return "null";
        }
        int q = source.indexOf('?');
        return q < 0 ? source : source.substring(0, q) + "?…";
    }

    @Override
    public TrackSpec spec() {
        return spec;
    }

    @Override
    public long framesDecoded() {
        return framesDecoded;
    }

    @Override
    public boolean isDrained() {
        return drained;
    }

    /**
     * 跳到指定位置（秒）。
     *
     * <p>返回<strong>实际</strong>跳到的位置：容器往往只能停在关键帧或包边界上，实际位置
     * 常常略早于请求位置——如实回报，界面进度条才不会在松手后自己跳回去。
     *
     * <p>定位之后所有暂存与游标全部作废。<strong>这一步绝不能省</strong>：pending 里装的是
     * 定位<em>之前</em>解出的数据，不丢掉的话，跳转后会先放出一小段旧内容——听起来像
     * 「跳过去又弹回来」。
     */
    @Override
    public double seek(double secs) {
        if (closed) {
            throw new IllegalStateException("解码器已关闭，不能再定位");
        }
        if (Double.isNaN(secs) || Double.isInfinite(secs) || secs < 0.0) {
            // 上游：`!secs.is_finite() || secs < 0.0` → SonalisError::Decode
            throw AudioException.decode("无效的定位目标：" + secs);
        }
        double rate = Math.max(1, spec.sampleRate);
        long total = spec.totalFrames == null ? Long.MAX_VALUE : spec.totalFrames;
        long targetFrame = Math.min((long) (secs * rate), total);

        long actual = adapter.seekToFrame(targetFrame);

        // 定位之后所有暂存与游标全部作废，见方法注释。
        pendingFrames = 0;
        pendingOffset = 0;
        drained = false;
        framesDecoded = actual;
        return actual / rate;
    }

    /**
     * 解码填满 {@code out}，返回写入的帧数。返回 0 表示已到结尾。
     *
     * <p>会一直解码到填满容量或流结束，所以调用方不必自己循环。
     */
    @Override
    public int decodeInto(AudioBuf out) {
        if (closed || drained) {
            return 0;
        }
        int channels = spec.layout.count();
        if (out.channels() != channels) {
            // 上游用 `out.channel_mut(c)` 直接索引，声道数不匹配会 panic；
            // 按约定映射成 IllegalArgumentException。
            throw new IllegalArgumentException(
                    "缓冲声道数与音轨不符：" + out.channels() + " ≠ " + channels);
        }
        int capacity = out.capacityFrames();
        if (capacity <= 0) {
            // 上游在这里会因为 written == 0 落进「没解出数据」的兜底分支、把流标记成
            // drained。容量为 0 是调用方的问题，不该污染流状态，所以显式返回 0。
            return 0;
        }

        int written = 0;
        while (true) {
            // 1) 先把上次没交付完的部分交付掉：**先清 pending，再取新包**，
            //    这个顺序是正确性的关键（把两件事揉在一个循环里正是上一版的错误来源）。
            if (pendingOffset < pendingFrames) {
                int avail = pendingFrames - pendingOffset;
                int n = Math.min(avail, capacity - written);
                for (int c = 0; c < channels; c++) {
                    // 相对写入（put(float) 依赖 position）：带下标的绝对批量读写是
                    // Java 13+ API，Android（release 8）会构建失败。
                    FloatBuffer dst = out.channelWriter(c);
                    dst.position(written);
                    for (int i = 0; i < n; i++) {
                        dst.put(pending[(pendingOffset + i) * channels + c]);
                    }
                }
                pendingOffset += n;
                written += n;
                framesDecoded += n;
                if (written == capacity) {
                    break;
                }
                continue;
            }

            // 2) pending 空了，去解码新的一包
            if (!fillPending()) {
                break; // 流结束
            }
        }

        out.setFrames(written);
        if (written == 0 && !drained) {
            // 容量非零却没解出任何数据，且流也没结束——不该发生。
            drained = true;
        }
        return written;
    }

    /**
     * 解码一包进 {@code pending}，并重置交付游标。返回 {@code false} 表示流已结束。
     *
     * <p>拆成独立方法是为了让 {@link #decodeInto} 的交付循环保持简单：
     * 「<strong>先清 pending，再取新包</strong>」这个顺序是正确性的关键，
     * 把两件事揉在一个循环里正是上一个版本的错误来源。
     */
    private boolean fillPending() {
        int channels = spec.layout.count();
        int emptyPackets = 0;
        while (true) {
            int frames = adapter.decodeNext();
            if (frames < 0) {
                drained = true;
                return false;
            }
            if (frames == 0) {
                // 坏包/空包被适配器跳过了：继续取下一包，绝不当成流结束。
                if (++emptyPackets > MAX_CONSECUTIVE_EMPTY_PACKETS) {
                    throw AudioException.decode("连续 " + MAX_CONSECUTIVE_EMPTY_PACKETS
                            + " 个包都没有解出数据，放弃这一路音源");
                }
                continue;
            }
            emptyPackets = 0;

            // 暂存不够就扩容一次（正常不会走到这里，因为初值给得很大）。
            if (frames > pendingCapacityFrames) {
                pendingCapacityFrames = frames * 2;
                pending = new float[pendingCapacityFrames * channels];
            }
            System.arraycopy(adapter.scratch(), 0, pending, 0, frames * channels);
            pendingFrames = frames;
            pendingOffset = 0;
            return true;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return; // 幂等：上游没有 close，Java 的 AutoCloseable 惯例是重复调用无害
        }
        closed = true;
        adapter.close();
    }

    // ---- 包内可见的诊断/测试钩子（不改状态） ----

    /** pending 里还没交付的帧数。测试用它验证「先清 pending 再取新包」这条硬约束。 */
    int pendingFrames() {
        return pendingFrames - pendingOffset;
    }

    /** pending 的容量（帧），测试用来验证超大包触发的扩容分支。 */
    int pendingCapacityFrames() {
        return pendingCapacityFrames;
    }

    /**
     * 只暴露真正有用的状态：适配器里的库对象没什么可打印的，而 AudioBuf 的教训是
     * 别把几万个采样刷进日志（上游手写 {@code Debug} 也是同一个理由）。
     */
    @Override
    public String toString() {
        return "DesktopDecoder{spec=" + spec + ", framesDecoded=" + framesDecoded
                + ", drained=" + drained + '}';
    }
}
