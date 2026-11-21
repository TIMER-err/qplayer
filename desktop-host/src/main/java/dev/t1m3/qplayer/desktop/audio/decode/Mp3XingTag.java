package dev.t1m3.qplayer.desktop.audio.decode;

/**
 * MP3 首帧里的 Xing/Info（VBR 头）与 LAME 扩展标签：<strong>无缝播放所需的编码器延迟与填充</strong>
 * 全在这里。
 *
 * <h2>为什么自己解析，而不是让 jlayer 干</h2>
 *
 * <p>jlayer 的 {@code Header.parseVBR} 只读 Xing/VBRI 的<em>帧数/字节数/TOC</em>，
 * <strong>完全不看</strong>紧随其后的 LAME 扩展标签——也就是说编码器延迟（delay）与填充
 * （padding）它压根不知道。上游把 gapless 定为默认开启的硬约定（关掉它，过渡引擎算出来的
 * 位置整体偏移，MP3 开头会带上编码器延迟产生的静音），所以这一段只能自己补：
 * 直接按首帧的字节布局取 {@code delay}/{@code padding} 两个 12 位字段。
 *
 * <h2>布局（这份解析器的规格来源）</h2>
 *
 * <pre>
 * 帧首 4 字节 → 版本/层/比特率/采样率/声道模式
 * Xing 魔数偏移 = 4 + 边信息长度
 *                 MPEG1  : 单声道 17 → 偏移 21；立体声 32 → 偏移 36
 *                 MPEG2/2.5: 单声道  9 → 偏移 13；立体声 17 → 偏移 21
 * Xing 头 = 魔数(4) + flags(4) + [帧数(4)] + [字节数(4)] + [TOC(100)] + [scale(4)]
 * LAME 标签 = 紧跟 Xing 头，前 9 字节是编码器字符串（"LAME…" / "Lavf…"），
 *             第 21..23 字节是 12 位 delay + 12 位 padding
 * </pre>
 *
 * <h2>裁剪量（这是上游「默认开启 gapless」在 MP3 上的落点）</h2>
 *
 * <p>LAME 存的 delay/padding 是相对 528 采样基准的偏移：真正的编码器延迟是
 * {@code delay + 528 + 1}，真正的填充是 {@code padding - 528 - 1}（那个 +1 是 libmp3lame
 * 自己的定义）。两边共有的 528 抵消后，有效采样数就是通用的
 * {@code 帧数 × 每帧采样数 − delay − padding}。
 *
 * <h2>单位与轴：1105 与 1152 是两个轴上的数，别混</h2>
 *
 * <p><strong>实测结论（2026-10-06，skydive.mp3 / 春めく.mp3，ffprobe 数包 + 逐帧走文件）</strong>：
 * Xing 里的 {@code frames} <strong>不含</strong> Info/Xing 帧自己——两个文件分别读出 7931 / 7731 包，
 * 而 {@code 7931 × 1152 − 1105 − 407 = 9135000}、{@code 7731 × 1152 − 1105 − 1007 = 8904000}
 * 正好等于 ffmpeg（以及我们）交付的采样数。所以本类所有数字都在
 * <strong>音频帧轴</strong>上：第一个<em>音频</em>帧的采样 0 就是 0，Info 帧不在数里。
 * （早先这里写着「假定包含 Xing 帧自身、头部裁剪不受影响」的猜测，已被上面这组实测推翻，
 * 当时下游的 {@link Mp3CodecAdapter} 也因此把 1105 用错了轴。）
 *
 * <p>而 {@code headTrimFrames() = delay + 528 + 1 = 1105} 是<strong>音频帧轴</strong>上的头部
 * 裁剪量，不是「解码器吐出来的前 1105 个采样」。jlayer 会把 Info/Xing 帧<em>当成一帧真解码</em>
 * （实测帧 0 解出 1152 个采样），也就是它多吐了整整一个 MPEG 帧，所以在
 * <strong>jlayer 产出采样轴</strong>上同一个裁剪量是 {@code 1105 + samplesPerFrame}
 * （MPEG1 Layer III：1105 + 1152 = <b>2257</b>）。把 1105 直接当成 jlayer 那条轴上的
 * 裁剪量，整条时间轴就会早 24 ms（1152 采样 @48 kHz），过渡窗口的接缝因此差两个帧。
 *
 * <p>{@link Mp3CodecAdapter} 的做法是把内部游标记在<strong>音频帧轴</strong>上（Info 帧占负半轴
 * 那一格、被裁剪窗口丢掉），于是本类的 1105 与 {@code 帧数 × 每帧采样数} 都不用改；
 * 关系与实测都写在那个类的类注释里。
 *
 * <p>类内可见：只服务于 {@link Mp3CodecAdapter}，测试与本类同包。
 */
final class Mp3XingTag {

    /** MPEG1 的采样率表（下标 = 头里的采样率索引）。 */
    private static final int[] RATES_MPEG1 = {44100, 48000, 32000};
    /** MPEG2 的采样率表。 */
    private static final int[] RATES_MPEG2 = {22050, 24000, 16000};
    /** MPEG2.5 的采样率表（非标准但到处都在用）。 */
    private static final int[] RATES_MPEG25 = {11025, 12000, 8000};

    /** LAME 扩展标签里 delay/padding 相对 528 采样基准的偏移，见类注释。 */
    private static final int LAME_BASE_DELAY = 528;

    /** 版本：1 = MPEG1，2 = MPEG2，25 = MPEG2.5。 */
    final int version;
    /** 层：1 = Layer I，2 = Layer II，3 = Layer III。 */
    final int layer;
    /** 采样率。 */
    final int sampleRate;
    /** 声道数（1 或 2）。 */
    final int channels;
    /** 每帧采样数（MPEG1 Layer III = 1152，MPEG2/2.5 Layer III = 576）。 */
    final int samplesPerFrame;
    /** Xing/Info 里的帧数；-1 = 没有（CBR 且没写 Info 标签）。 */
    final int frames;
    /** 是不是 VBR（"Xing"）；"Info" 是 CBR 也写的同一种头。 */
    final boolean vbr;
    /**
     * <strong>文件里的第一帧是不是「编码器信息帧」而不是音频</strong>（Xing/Info，或 Fraunhofer 的
     * VBRI——它们都占一个完整的 MPEG 帧）。
     *
     * <p>这一位是「解码轴」记账的关键：jlayer 会把这一帧<em>当成一帧真解码</em>并吐出整整
     * {@code samplesPerFrame} 个采样（实测：帧 0 解出 1152 个，是静音），所以它不是音频、
     * 不该占音频帧轴上的位置。{@link Mp3CodecAdapter} 靠它决定游标从 0 还是从
     * {@code −samplesPerFrame} 起算。
     *
     * <p>不要用 {@code frames > 0} 代替这一位：Xing 头完全可能不带帧数
     * （flags 里没那一位时 {@code frames == -1}），但那一帧照样在文件里。
     */
    final boolean headerFrame;
    /** LAME 标签里的 12 位编码器延迟；-1 = 没有 LAME 标签。 */
    final int encoderDelay;
    /** LAME 标签里的 12 位填充；-1 = 没有 LAME 标签。 */
    final int encoderPadding;

    private Mp3XingTag(int version, int layer, int sampleRate, int channels, int samplesPerFrame,
                       int frames, boolean vbr, boolean headerFrame,
                       int encoderDelay, int encoderPadding) {
        this.version = version;
        this.layer = layer;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.samplesPerFrame = samplesPerFrame;
        this.frames = frames;
        this.vbr = vbr;
        this.headerFrame = headerFrame;
        this.encoderDelay = encoderDelay;
        this.encoderPadding = encoderPadding;
    }

    /**
     * 从（已跳掉 ID3v2 的）字节前缀里解析首帧与其 Xing/LAME 标签。
     *
     * @return 找不到合法帧头时返回 {@code null}（调用方据此报「不支持」）；
     *         找到帧但没有 Xing/LAME 标签时返回的对象里 {@code frames == -1}、
     *         两个 delay/padding 都是 -1（{@code headerFrame} 只在真的看到
     *         Xing/Info/VBRI 魔数时才为 true）。
     */
    static Mp3XingTag parse(byte[] prefix) {
        if (prefix == null || prefix.length < 4) {
            return null;
        }
        int offset = findFrameHeader(prefix);
        if (offset < 0 || offset + 4 > prefix.length) {
            return null;
        }
        int b1 = prefix[offset + 1] & 0xff;
        int b2 = prefix[offset + 2] & 0xff;
        int b3 = prefix[offset + 3] & 0xff;

        int versionBits = (b1 >> 3) & 0x03;
        int layerBits = (b1 >> 1) & 0x03;
        int rateIndex = (b2 >> 2) & 0x03;
        int mode = (b3 >> 6) & 0x03;

        int version;
        int[] rates;
        if (versionBits == 3) {
            version = 1;
            rates = RATES_MPEG1;
        } else if (versionBits == 2) {
            version = 2;
            rates = RATES_MPEG2;
        } else {
            version = 25;
            rates = RATES_MPEG25;
        }
        // layerBits: 3 = Layer I，2 = Layer II，1 = Layer III
        int layer = 4 - layerBits;
        int sampleRate = rates[rateIndex];
        int channels = mode == 3 ? 1 : 2;
        int samplesPerFrame = samplesPerFrame(version, layer);

        int xingOffset = offset + 4 + sideInfoBytes(version, mode);
        int frames = -1;
        boolean vbr = false;
        int encoderDelay = -1;
        int encoderPadding = -1;

        // 首帧是不是「信息帧」：Xing/Info 的魔数在 Xing 位置；Fraunhofer 的 VBRI 魔数固定在
        // 帧首之后第 36 字节（与声道模式无关，所以单独算一个下标）。两者都不含音频。
        String magic = readAscii(prefix, xingOffset, 4);
        boolean headerFrame = "Xing".equals(magic) || "Info".equals(magic)
                || "VBRI".equals(readAscii(prefix, offset + 36, 4));
        if ("Xing".equals(magic) || "Info".equals(magic)) {
            vbr = "Xing".equals(magic);
            int flags = readInt(prefix, xingOffset + 4);
            int q = xingOffset + 8;
            if ((flags & 0x01) != 0) {
                frames = readInt(prefix, q);
                q += 4;
                // 帧数是 32 位无符号，正常文件不可能到这个量级；越界就当标签是坏的。
                if (frames <= 0 || frames > (1 << 28)) {
                    frames = -1;
                }
            }
            if ((flags & 0x02) != 0) {
                q += 4; // 字节数：本层用不到（时长按帧数算，VBR 的字节数没有直接用途）
            }
            if ((flags & 0x04) != 0) {
                q += 100; // TOC
            }
            if ((flags & 0x08) != 0) {
                q += 4; // scale
            }
            // 编码器字符串：LAME 写 "LAME3.xxx"，ffmpeg 写 "Lavf"/"Lavc"，老 LAME 写 "L3.9"，
            // GoGo 是 "GOGO"。都按 LAME 标签布局处理。
            String encoder = readAscii(prefix, q, 4);
            if ("LAME".equals(encoder) || "Lavf".equals(encoder)
                    || "Lavc".equals(encoder) || "GOGO".equals(encoder)
                    || "L3.9".equals(encoder)) {
                // 第 21..23 字节：12 位 delay + 12 位 padding。
                int d0 = readByte(prefix, q + 21);
                int d1 = readByte(prefix, q + 22);
                int d2 = readByte(prefix, q + 23);
                if (d0 >= 0 && d1 >= 0 && d2 >= 0) {
                    encoderDelay = (d0 << 4) | (d1 >> 4);
                    encoderPadding = ((d1 & 0x0F) << 8) | d2;
                }
            }
        } else {
            // 没有 Xing/Info：老式 CBR。没有标签就没有延迟/填充信息（下面 hasGaplessInfo()
            // 会为 false），此时不做任何裁剪——比瞎猜一个延迟值安全。
            // （VBRI 那种「有信息帧但没有 LAME 标签」的情况同理：不裁剪，但首帧照样不算音频。）
            encoderDelay = -1;
            encoderPadding = -1;
        }

        return new Mp3XingTag(version, layer, sampleRate, channels, samplesPerFrame,
                frames, vbr, headerFrame, encoderDelay, encoderPadding);
    }

    /**
     * 帧头扫描：跳过 ID3（已经由调用方跳掉）之后可能有零星垃圾字节，
     * 所以按 MPEG 同步字找第一个合法帧。
     *
     * @return 帧首在 {@code prefix} 里的下标；找不到返回 -1
     */
    static int findFrameHeader(byte[] prefix) {
        for (int i = 0; i + 4 <= prefix.length; i++) {
            if ((prefix[i] & 0xff) != 0xFF) {
                continue;
            }
            int b1 = prefix[i + 1] & 0xff;
            if ((b1 & 0xE0) != 0xE0) {
                continue;
            }
            int versionBits = (b1 >> 3) & 0x03;
            int layerBits = (b1 >> 1) & 0x03;
            int b2 = prefix[i + 2] & 0xff;
            int bitrateIndex = (b2 >> 4) & 0x0F;
            int rateIndex = (b2 >> 2) & 0x03;
            if (versionBits == 1 || layerBits == 0 || rateIndex == 3) {
                continue; // 保留值：不是帧头
            }
            // 比特率索引 0（free format）与 15（非法）都排除：jlayer 自己也不支持
            // free format，认下来只会让后面解码炸掉。
            if (bitrateIndex == 0 || bitrateIndex == 15) {
                continue;
            }
            return i;
        }
        return -1;
    }

    /** 每帧采样数：Layer I 384、Layer II 1152、Layer III（MPEG1 1152 / MPEG2·2.5 576）。 */
    static int samplesPerFrame(int version, int layer) {
        if (layer == 1) {
            return 384;
        }
        if (layer == 2) {
            return 1152;
        }
        return version == 1 ? 1152 : 576;
    }

    /** 帧头之后、Xing 魔数之前那段边信息的长度（4 字节帧头之后开始算）。 */
    private static int sideInfoBytes(int version, int mode) {
        boolean mono = mode == 3;
        if (version == 1) {
            return mono ? 17 : 32;
        }
        return mono ? 9 : 17;
    }

    /**
     * 有没有可用的延迟/填充信息。
     *
     * <p>{@code delay == 0 && padding == 0} 视为「没有信息」：有些编码器会写一个全零的
     * LAME 标签占位，这种时候按 0 裁剪反而会切掉开头 529 个真实采样，不如不裁。
     */
    boolean hasGaplessInfo() {
        return encoderDelay >= 0 && encoderPadding >= 0
                && (encoderDelay > 0 || encoderPadding > 0);
    }

    /**
     * 头部要丢掉的采样数（编码器延迟）；没有 gapless 信息时为 0。
     *
     * <p><strong>音频帧轴</strong>的量（第一个音频帧的采样 0 起算），不要直接拿去当
     * jlayer 产出采样轴上的下标——那条轴上要再加一个 {@code samplesPerFrame}，
     * 见类注释「单位与轴」。
     */
    long headTrimFrames() {
        return hasGaplessInfo() ? encoderDelay + LAME_BASE_DELAY + 1L : 0L;
    }

    /** 尾部要丢掉的采样数（填充）；同为音频帧轴，没有 gapless 信息时为 0。 */
    long tailTrimFrames() {
        if (!hasGaplessInfo()) {
            return 0L;
        }
        return Math.max(0L, encoderPadding - LAME_BASE_DELAY - 1L);
    }

    /**
     * 未裁剪的原始采样数（音频帧轴：{@code 帧数 × 每帧采样数}，<em>不含</em> Info 帧）；未知时 -1。
     */
    long rawFrames() {
        return frames > 0 ? (long) frames * samplesPerFrame : -1L;
    }

    /** 裁剪之后的有效采样数（音频帧轴）；未知时 -1。与 ffmpeg 交付的采样数逐位相等。 */
    long validFrames() {
        long raw = rawFrames();
        if (raw < 0) {
            return -1L;
        }
        long valid = raw - tailTrimFrames() - headTrimFrames();
        return valid > 0 ? valid : -1L;
    }

    // ---- 越界安全的读法：坏标签/截断前缀一律当成「读不到」，绝不让解析器抛异常 ----

    private static int readByte(byte[] b, int at) {
        return at >= 0 && at < b.length ? (b[at] & 0xff) : -1;
    }

    private static int readInt(byte[] b, int at) {
        if (at < 0 || at + 4 > b.length) {
            return 0;
        }
        return ((b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16)
                | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
    }

    private static String readAscii(byte[] b, int at, int len) {
        if (at < 0 || at + len > b.length) {
            return "";
        }
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) (b[at + i] & 0xff));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Mp3XingTag{MPEG" + version + " L" + layer + ", " + sampleRate + " Hz, "
                + channels + "ch, spf=" + samplesPerFrame + ", frames=" + frames
                + (vbr ? ", vbr" : ", cbr") + ", delay=" + encoderDelay
                + ", padding=" + encoderPadding + '}';
    }
}
