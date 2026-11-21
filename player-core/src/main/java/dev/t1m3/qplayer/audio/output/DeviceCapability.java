package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.AudioException;

/**
 * 设备支持的一个配置档位。
 *
 * <p>对应 Sonalis 的 {@code sonalis_output::DeviceCapability}。
 *
 * <p>这是「Hi-Res 能不能开」的唯一依据——<strong>由硬件决定，不由我们猜</strong>。
 * 与「环绕声没硬件就置灰」是同一条原则。
 */
public final class DeviceCapability {

    /** Hi-Res 门槛：采样率上界要能超过 48 kHz。 */
    private static final int HIRES_THRESHOLD = 48_000;

    private final OutputSampleFormat sampleFormat;
    private final int channels;
    private final int minSampleRate;
    private final int maxSampleRate;

    public DeviceCapability(OutputSampleFormat sampleFormat, int channels,
                            int minSampleRate, int maxSampleRate) {
        if (sampleFormat == null) {
            throw AudioException.config("设备档位缺少采样格式");
        }
        if (channels < 1) {
            throw AudioException.config("设备档位声道数必须 >= 1：" + channels);
        }
        if (minSampleRate < 1 || maxSampleRate < minSampleRate) {
            throw AudioException.config(
                    "设备档位采样率区间不合法：" + minSampleRate + ".." + maxSampleRate);
        }
        this.sampleFormat = sampleFormat;
        this.channels = channels;
        this.minSampleRate = minSampleRate;
        this.maxSampleRate = maxSampleRate;
    }

    /** 该档位的采样格式（决定位深）。 */
    public OutputSampleFormat sampleFormat() {
        return sampleFormat;
    }

    /** 该档位的声道数。 */
    public int channels() {
        return channels;
    }

    /** 支持的采样率下界。 */
    public int minSampleRate() {
        return minSampleRate;
    }

    /** 支持的采样率上界。 */
    public int maxSampleRate() {
        return maxSampleRate;
    }

    /** 是否覆盖某个采样率。 */
    public boolean coversRate(int rate) {
        return minSampleRate <= rate && rate <= maxSampleRate;
    }

    /** 是否达到 Hi-Res 门槛：采样率能上到 96k 以上（上游判定是 {@code > 48_000}）。 */
    public boolean isHiresCapable() {
        return maxSampleRate > HIRES_THRESHOLD;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DeviceCapability)) {
            return false;
        }
        DeviceCapability other = (DeviceCapability) o;
        return sampleFormat == other.sampleFormat
                && channels == other.channels
                && minSampleRate == other.minSampleRate
                && maxSampleRate == other.maxSampleRate;
    }

    @Override
    public int hashCode() {
        int h = sampleFormat.hashCode();
        h = 31 * h + channels;
        h = 31 * h + minSampleRate;
        h = 31 * h + maxSampleRate;
        return h;
    }

    @Override
    public String toString() {
        return sampleFormat + " " + channels + "ch "
                + minSampleRate + ".." + maxSampleRate + " Hz";
    }
}
