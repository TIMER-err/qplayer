package dev.t1m3.qplayer.audio.decode;

import dev.t1m3.qplayer.audio.core.BitDepth;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.core.SourceFormat;

/**
 * 对一首曲目「在解码之前就能知道」的事实。
 *
 * <p>对应 Sonalis 的 {@code sonalis_decode::TrackSpec}。
 */
public final class TrackSpec {

    /** 编码名称，如 {@code flac} / {@code mp3}。 */
    public final String codec;
    /** 采样率。 */
    public final int sampleRate;
    /** 声道布局。 */
    public final ChannelLayout layout;
    /** 位深。有损格式通常为 {@code null}。 */
    public final Integer bitsPerSample;
    /** 总帧数。流式输入可能未知。 */
    public final Long totalFrames;
    /** 是否为无损编码。 */
    public final boolean lossless;

    public TrackSpec(String codec, int sampleRate, ChannelLayout layout,
                     Integer bitsPerSample, Long totalFrames, boolean lossless) {
        this.codec = codec == null ? "unknown" : codec;
        this.sampleRate = sampleRate;
        this.layout = layout;
        this.bitsPerSample = bitsPerSample;
        this.totalFrames = totalFrames;
        this.lossless = lossless;
    }

    /** 时长（秒）。总帧数未知时返回 {@code null}（上游是 {@code None}）。 */
    public Double durationSecs() {
        if (totalFrames == null) {
            return null;
        }
        return totalFrames.doubleValue() / Math.max(1, sampleRate);
    }

    /** 每秒的采样数（所有声道合计）。 */
    public int samplesPerSecond() {
        return sampleRate * layout.count();
    }

    /**
     * 转成通用格式指纹，供 HiFi 路径判定使用。
     *
     * <p>位深缺失时按 16 位估算。有损编码本来就没有「位深」这个概念，但路径判定
     * 需要一个值；取 16 位是保守选择——不会凭空把有损音乐标成 Hi-Res。
     */
    public SourceFormat sourceFormat() {
        BitDepth depth = bitsPerSample == null ? null : BitDepth.fromBits(bitsPerSample);
        return new SourceFormat(sampleRate, layout.count(),
                depth == null ? BitDepth.INT16 : depth, lossless, codec);
    }

    @Override
    public String toString() {
        return "TrackSpec{" + codec + " " + sampleRate + " Hz, " + layout
                + ", bits=" + bitsPerSample + ", frames=" + totalFrames
                + (lossless ? ", lossless" : ", lossy") + '}';
    }
}
