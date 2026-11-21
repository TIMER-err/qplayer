package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.BitDepth;

/**
 * 已协商好的输出配置。
 *
 * <p>对应 Sonalis 的 {@code sonalis_output::OutputConfig}（Rust 是公有字段的 struct，
 * 这里按其不可变值对象的惯例改为私有字段 + 访问器）。
 *
 * <p><strong>这里的 {@link #sampleRate()} 是设备实际生效的采样率，未必是你请求的那个。</strong>
 * 拿它和源格式一起喂给 {@code PathVerdict.classify} 才能得到位完美判定。
 */
public final class OutputConfig {

    private final int sampleRate;
    private final int channels;
    private final OutputSampleFormat sampleFormat;
    private final int ringFrames;

    public OutputConfig(int sampleRate, int channels, OutputSampleFormat sampleFormat, int ringFrames) {
        if (sampleRate < 1) {
            throw AudioException.config("输出采样率必须 >= 1：" + sampleRate);
        }
        if (channels < 1) {
            throw AudioException.config("输出声道数必须 >= 1：" + channels);
        }
        if (ringFrames < 1) {
            throw AudioException.config("环形缓冲帧数必须 >= 1：" + ringFrames);
        }
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.sampleFormat = sampleFormat;
        this.ringFrames = ringFrames;
    }

    /** 设备采样率（实际生效的）。 */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * 设备声道数。
     *
     * <p>推入环形缓冲的数据<strong>必须</strong>是这个声道数，交错度也是它。
     */
    public int channels() {
        return channels;
    }

    /** 设备采样格式。 */
    public OutputSampleFormat sampleFormat() {
        return sampleFormat;
    }

    /**
     * 环形缓冲容量（帧）。
     *
     * <p>这是<strong>请求值</strong>：{@code RingBuffer.create} 会把它向上取整到 2 的幂
     * （与上游 {@code sonalis_core::ring_buffer} 一致），实际可用容量见
     * {@code Producer.capacity()}。
     */
    public int ringFrames() {
        return ringFrames;
    }

    /** 环形缓冲容量折算成采样数。 */
    public int ringSamples() {
        return ringFrames * channels;
    }

    /** 缓冲时长（毫秒）。 */
    public float ringMs() {
        return (float) ringFrames * 1000.0f / (float) Math.max(sampleRate, 1);
    }

    /** 位深。 */
    public BitDepth bitDepth() {
        return sampleFormat.bitDepth();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OutputConfig)) {
            return false;
        }
        OutputConfig other = (OutputConfig) o;
        return sampleRate == other.sampleRate
                && channels == other.channels
                && ringFrames == other.ringFrames
                && sampleFormat == other.sampleFormat;
    }

    @Override
    public int hashCode() {
        int h = sampleRate;
        h = 31 * h + channels;
        h = 31 * h + sampleFormat.hashCode();
        h = 31 * h + ringFrames;
        return h;
    }

    @Override
    public String toString() {
        return "OutputConfig{" + sampleRate + " Hz, " + channels + "ch, " + sampleFormat
                + ", ring " + ringFrames + " frames (" + ringSamples() + " samples)}";
    }
}
