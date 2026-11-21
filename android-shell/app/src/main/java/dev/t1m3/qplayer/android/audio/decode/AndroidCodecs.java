package dev.t1m3.qplayer.android.audio.decode;

import java.util.Locale;

/**
 * 安卓解码层的「编码名 → 事实」映射表（<strong>纯逻辑，不引用 {@code android.*}</strong>）。
 *
 * <p>包里对应 {@code MediaFormat} 的几个键，但取值由调用方从 {@code MediaFormat} 里读出来传进来，
 * 这样这张表可以脱离设备被测试（{@code AndroidCodecsTest}）。这里的每一条规则都是
 * 「下游会照着它显示/判定的」，错一条就会一路传到界面上。
 *
 * <h3>无损判定只看编码名（上游踩过的坑）</h3>
 *
 * <p>上游代码里专门写明了这一点：<strong>绝不能拿位深去佐证无损</strong>。
 * 有损解码器同样会报 16/24/32 位输出，把「解出来的位深」当成「源是否无损」的依据，
 * 会把 AAC / MP3 标成无损，并且这个错误会一路传到 Hi-Res 标签上。
 * 所以 {@link #isLosslessMime(String)} 是一张<strong>只看 MIME 的白名单</strong>，
 * 不接受任何别的参数。
 */
public final class AndroidCodecs {

    /**
     * 时长上限：超过这个值的容器时长按「未知」处理。
     *
     * <p>理由：网络流的 {@code KEY_DURATION} 偶尔是垃圾值（0、或几十万秒）。
     * 它只用来算总帧数（时长显示 + seek 上界 + 尾部裁剪边界），一旦是垃圾就会让
     * 进度条完全失真，而「未知」至少是诚实的。音乐文件不该超过 12 小时。
     */
    public static final long MAX_DURATION_US = 12L * 60L * 60L * 1_000_000L;

    private AndroidCodecs() {
    }

    /**
     * 这个 MIME 是不是无损编码。<strong>只看编码名</strong>——理由见类注释。
     *
     * <p>白名单里的每一项都对应一个真无损的编码：
     *
     * <ul>
     *   <li>{@code audio/flac}（{@code MediaFormat.MIMETYPE_AUDIO_FLAC}）</li>
     *   <li>{@code audio/raw}（WAV / 未压缩 PCM；{@code MIMETYPE_AUDIO_RAW}）</li>
     *   <li>{@code audio/alac}（苹果无损；AOSP 的 m4a 提取器用它，能不能真解取决于设备，
     *       但「是不是无损编码」这件事与设备无关）</li>
     * </ul>
     *
     * <p>其余一律按有损处理（包括认不出来的 MIME）：<strong>保守方向必须是「有损」</strong>，
     * 因为报错成无损会让界面把一首 AAC 标成 Hi-Res。
     */
    public static boolean isLosslessMime(String mime) {
        if (mime == null) {
            return false;
        }
        String m = mime.toLowerCase(Locale.US);
        return "audio/flac".equals(m)
                || "audio/x-flac".equals(m)
                || "audio/raw".equals(m)
                || "audio/alac".equals(m)
                || "audio/x-alac".equals(m);
    }

    /**
     * 编码名（给 {@code TrackSpec.codec} 用）。
     *
     * <p>与桌面端命名保持一致（{@code "flac"} / {@code "mp3"} / {@code "pcm"}…），
     * 因为这个名字会经 {@code SourceFormat} 走到路径判定与界面标注上，两端不该有差异。
     * 认不出的 MIME 去掉 {@code audio/} 前缀原样返回。
     *
     * <p>输入是 <strong>media3 的 {@code Format.sampleMimeType}</strong>（{@code MimeTypes} 里的值），
     * 不是平台 {@code MediaFormat} 那个键——两者对常见格式取同样的字符串，所以两端与旧版都一致。
     */
    public static String codecName(String mime) {
        if (mime == null) {
            return "unknown";
        }
        String m = mime.toLowerCase(Locale.US);
        if ("audio/flac".equals(m) || "audio/x-flac".equals(m)) {
            return "flac";
        }
        if ("audio/raw".equals(m) || "audio/x-wav".equals(m)) {
            return "pcm";
        }
        if ("audio/alac".equals(m) || "audio/x-alac".equals(m)) {
            return "alac";
        }
        if ("audio/mpeg".equals(m) || "audio/mp3".equals(m) || "audio/x-mp3".equals(m)) {
            return "mp3";
        }
        if ("audio/mp4a-latm".equals(m) || "audio/aac".equals(m) || "audio/aac-adts".equals(m)) {
            return "aac";
        }
        if ("audio/vorbis".equals(m) || "audio/x-vorbis".equals(m)) {
            return "vorbis";
        }
        if ("audio/opus".equals(m)) {
            return "opus";
        }
        if ("audio/amr-wb".equals(m) || "audio/amr".equals(m) || "audio/3gpp".equals(m)) {
            return "amr";
        }
        return m.startsWith("audio/") ? m.substring("audio/".length()) : m;
    }

    /**
     * 源位深。拿不到就返回 {@code null}（不猜）。
     *
     * <p>三条规则：
     *
     * <ol>
     *   <li><strong>有损一律 {@code null}</strong>。有损编码没有「源位深」这个概念；
     *       解码器报的输出位深是它自己的内部格式，拿它当源位深正是上面记下的那个错误。</li>
     *   <li>无损：先看容器亲口说的 {@code bits-per-sample}（参数 {@code formatBits}）。</li>
     *   <li>无损且没有那个键时，退而看 {@code pcmEncoding}：8 / 16 / 24 / 32 位整数能反推出源位深；
     *       {@link dev.t1m3.qplayer.android.audio.AndroidAudioFormats#ENCODING_PCM_FLOAT}
     *       说明<strong>不了</strong>源位深（解码器被要求输出 float 时就会报它），所以返回 {@code null}。
     *       <strong>安卓这一档才是主路径</strong>：media3 的 FLAC / WAV 提取器把
     *       <em>源</em>位深放进 {@code Format.pcmEncoding}（{@code Util.getPcmEncoding(bitsPerSample)}），
     *       平台的 {@code bits-per-sample} 键（连常量都没公开）能不用就不用。</li>
     * </ol>
     *
     * <p>返回 {@code null} 的后果是保守的：{@code TrackSpec.sourceFormat()} 会按 16 位估算，
     * 于是 Hi-Res 判定最多说「不是 Hi-Res」，<strong>绝不会凭空把有损音乐标成 Hi-Res</strong>。
     *
     * @param lossless        {@link #isLosslessMime} 的结果
     * @param formatBits      容器 {@code bits-per-sample}，没有则 {@code null}
     * @param pcmEncoding     源 PCM 编码（media3 的 {@code Format.pcmEncoding}），没有则 {@code null}
     */
    public static Integer bitsPerSample(boolean lossless, Integer formatBits, Integer pcmEncoding) {
        if (!lossless) {
            return null;
        }
        if (formatBits != null && formatBits.intValue() > 0) {
            return formatBits;
        }
        if (pcmEncoding != null) {
            switch (pcmEncoding.intValue()) {
                case 3:
                    // 8 位无符号 PCM（8 位 WAV）：它确实是源位深本身
                    return Integer.valueOf(8);
                case 2:
                    return Integer.valueOf(16);
                case 21:
                    return Integer.valueOf(24);
                case 22:
                    return Integer.valueOf(32);
                default:
                    // 包括 float：说明不了源位深，返回 null 而不是编一个
                    return null;
            }
        }
        return null;
    }

    /**
     * 容器时长（微秒）→ 总帧数。时长不可用（0、负数、超出 {@link #MAX_DURATION_US} 的垃圾值）时返回
     * {@code null}，对应 {@code TrackSpec.totalFrames} 的「未知」。
     *
     * <p>用整数运算（先乘后除）而不是浮点：这是一条会被拿去和「已交付帧数」比较的边界值，
     * 浮点的舍入会让末尾差一两帧，而末尾正是尾部裁剪（padding）所在的位置。
     *
     * <p>这个帧数是<strong>容器时间轴</strong>上的帧数（含编码器延迟与填充），
     * 真正可播放的帧数由 {@link AndroidGapless#playableFrames()} 给出。
     */
    public static Long framesFromDurationMicros(long durationUs, int sampleRate) {
        if (durationUs <= 0L || durationUs > MAX_DURATION_US || sampleRate < 1) {
            return null;
        }
        return Long.valueOf(durationUs * sampleRate / 1_000_000L);
    }

    /**
     * 容器报的「编码器延迟/填充」可不可信；不可信时返回一句人话，可信返回 {@code null}。
     *
     * <p>为什么需要这道闸门（2026-09-23 安卓实机）：media3 的 {@code Format.encoderDelay/Padding}
     * 是<strong>真会去解析</strong> LAME/Xing 与 iTunSMPB 的（旧实现读的
     * {@code MediaFormat.KEY_ENCODER_DELAY} 基本没人设，所以以前从来不裁、也从来不会因此提前收尾）。
     * 这两个值一旦被写坏（字段损坏、单位不是帧、或时长本身已经把延迟排除掉了造成双扣），
     * 「可播放帧数 = 容器帧数 − 延迟 − 填充」就会<strong>小得离谱</strong>，
     * 解码器于是会在歌还没放完时宣告结束——听感正是「刚换过来没放几秒就跳到下一首」。
     *
     * <p>判据（宁可少裁也不要多裁）：
     *
     * <ol>
     *   <li>没有容器总帧数 → 不可信（没有分母，算不出可播放帧数，也就没法裁）；</li>
     *   <li>单个值超过 {@code MAX_TRUSTED_TRIM_SECS} → 不可信；</li>
     *   <li>两个值之和超过容器时长的 {@code MAX_TRUSTED_TRIM_FRACTION} → 不可信。</li>
     * </ol>
     *
     * @param rate            源采样率
     * @param delayFrames     延迟（帧），可为 {@code null}
     * @param paddingFrames   填充（帧），可为 {@code null}
     * @param containerFrames 容器总帧数，可为 {@code null}
     * @return 不可信的原因（写进日志）；可信则 {@code null}
     */
    public static String trimVerdict(int rate, Integer delayFrames, Integer paddingFrames,
                                     Long containerFrames) {
        int delay = delayFrames == null ? 0 : Math.max(0, delayFrames.intValue());
        int padding = paddingFrames == null ? 0 : Math.max(0, paddingFrames.intValue());
        if (delay == 0 && padding == 0) {
            return null;    // 没给（或都是 0）：没什么可裁的，也就没有截断的风险
        }
        if (containerFrames == null || containerFrames.longValue() <= 0L) {
            return "容器没给总帧数，算不出可播放帧数";
        }
        long maxTrusted = (long) Math.max(1, rate) * MAX_TRUSTED_TRIM_SECS;
        if (delay > maxTrusted || padding > maxTrusted) {
            return "单个值超过 " + MAX_TRUSTED_TRIM_SECS + " 秒（延迟 " + delay
                    + " 帧 / 填充 " + padding + " 帧）";
        }
        double sum = (double) delay + (double) padding;
        if (sum > containerFrames.longValue() * MAX_TRUSTED_TRIM_FRACTION) {
            return "两者之和占容器时长 " + String.format(java.util.Locale.US, "%.1f%%",
                    sum * 100.0 / containerFrames.longValue()) + "（超过 "
                    + (int) (MAX_TRUSTED_TRIM_FRACTION * 100) + "%）";
        }
        return null;
    }

    /** 单个裁剪值的可信上限（秒），与 {@link #trimVerdict} 同一处真源。 */
    static final long MAX_TRUSTED_TRIM_SECS = 1L;

    /** 裁剪总量相对容器时长的可信上限（比例）。 */
    static final double MAX_TRUSTED_TRIM_FRACTION = 0.05;

    /**
     * 解码器实际报的声道数/采样率与容器声明的是否一致。
     *
     * <p>不一致时必须报错而不是硬着头皮放：{@code AudioBuf} 的声道数在解码器构造时就定了，
     * 解码器要是真换了布局，我们按原声道数搬数据就会把左右声道搅在一起——
     * 那比直接报错难查得多。
     */
    public static boolean layoutMatches(int declaredChannels, int declaredRate,
                                       int decodedChannels, int decodedRate) {
        if (decodedChannels > 0 && decodedChannels != declaredChannels) {
            return false;
        }
        return !(decodedRate > 0 && decodedRate != declaredRate);
    }
}
