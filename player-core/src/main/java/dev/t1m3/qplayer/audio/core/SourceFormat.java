package dev.t1m3.qplayer.audio.core;

/**
 * 源文件的格式指纹。
 *
 * <p>只装「判定路径所必需的」事实，不含播放列表元数据——那些属于资料库层，
 * 不该混进音频决策。
 *
 * <p>对应 Sonalis 的 {@code sonalis_core::format::SourceFormat}。
 */
public final class SourceFormat {

    private final int sampleRate;
    private final int channels;
    private final BitDepth bitDepth;
    private final boolean lossless;
    private final String codec;

    public SourceFormat(int sampleRate, int channels, BitDepth bitDepth,
                        boolean lossless, String codec) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitDepth = bitDepth;
        this.lossless = lossless;
        this.codec = codec == null ? "" : codec;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    public BitDepth bitDepth() {
        return bitDepth;
    }

    public boolean lossless() {
        return lossless;
    }

    /** 编码短名，如 {@code flac} / {@code mp3} / {@code alac}。 */
    public String codec() {
        return codec;
    }

    /** 采样率家族。 */
    public RateFamily family() {
        return RateFamily.of(sampleRate);
    }

    /**
     * 是否属于 Hi-Res 范畴（&gt;48 kHz 或 &gt;16 bit 的无损）。
     *
     * <p>只用来做界面标记，<strong>不参与任何解码或输出决策</strong>。
     */
    public boolean isHires() {
        return lossless && (sampleRate > 48_000 || bitDepth.bits() > 16);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SourceFormat)) return false;
        SourceFormat other = (SourceFormat) o;
        return sampleRate == other.sampleRate
                && channels == other.channels
                && bitDepth == other.bitDepth
                && lossless == other.lossless
                && codec.equals(other.codec);
    }

    @Override
    public int hashCode() {
        int h = sampleRate;
        h = 31 * h + channels;
        h = 31 * h + bitDepth.hashCode();
        h = 31 * h + (lossless ? 1 : 0);
        h = 31 * h + codec.hashCode();
        return h;
    }

    @Override
    public String toString() {
        return "SourceFormat{" + sampleRate + " Hz, " + channels + "ch, " + bitDepth
                + (lossless ? ", lossless" : ", lossy") + ", " + codec + '}';
    }
}
