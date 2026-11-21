package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.BitDepth;

/**
 * 打开输出时的诉求。
 *
 * <p>对应 Sonalis 的 {@code sonalis_output::OutputRequest}。
 *
 * <h3>为什么 {@code sampleRate} 是必填的（上游设计决定 ①）</h3>
 *
 * 刻意把它设为必填：调用方几乎总是传源文件的采样率，而<strong>「跟随源采样率」
 * 正是位完美的前提</strong>。想用设备默认配置请走单独的无参入口
 * （上游是 {@code OutputHandle::open_default}，这里是 {@link OutputHandle#openDefault(int)}）。
 */
public final class OutputRequest {

    private final int sampleRate;
    private final BitDepth bitDepth;
    private final Integer channels;
    private final int ringFrames;
    private final Integer fixedBufferFrames;

    public OutputRequest(int sampleRate, BitDepth bitDepth, Integer channels,
                         int ringFrames, Integer fixedBufferFrames) {
        // Rust 的 u32 表达不了 0 与负数，Java 的 int 可以，所以这里补一道前置条件。
        // 不补的话 rate=0 会一路走到「设备不支持 0 Hz 的输出」，报出来的错驴唇不对马嘴。
        if (sampleRate < 1) {
            throw AudioException.config("目标采样率必须 >= 1：" + sampleRate);
        }
        if (ringFrames < 1) {
            throw AudioException.config("环形缓冲帧数必须 >= 1：" + ringFrames);
        }
        this.sampleRate = sampleRate;
        this.bitDepth = bitDepth;
        this.channels = channels;
        this.ringFrames = ringFrames;
        this.fixedBufferFrames = fixedBufferFrames;
    }

    /**
     * 以某个采样率构造一个最简请求。
     *
     * <p><strong>位深留空</strong>（{@code null} = 优先 F32，内部格式直通无转换开销）。
     * 要让设备跟随源文件的位深，用 {@link #withBitDepth(BitDepth)}。
     */
    public static OutputRequest atRate(int sampleRate, int ringFrames) {
        return new OutputRequest(sampleRate, null, null, ringFrames, null);
    }

    /**
     * 指定目标位深。
     *
     * <h3>为什么这个 setter 必须存在（上游缺陷 A1）</h3>
     *
     * {@code bitDepth} 字段和 {@link OutputSampleFormat#I24}（「HiFi 主力规格」）
     * 都实现了、也都有测试，但上游<strong>没有任何调用方设过它</strong>：
     * 三个开流点一律走 {@code at_rate()}，位深永远是 {@code None}。
     * 于是 I24 是一条从任何界面都走不到的死路，24 bit 的 FLAC 永远按 F32 开流。
     * 「字段实现了」和「字段被接上了」是两件事。
     *
     * <p>选不到请求的位深时<strong>不报错</strong>：{@link OutputChooser#choose} 会退回设备上
     * 最接近的格式，而实际协商结果由 {@code PathVerdict.classify} 如实说出来。
     * 位深不值得像采样率那样直接失败——采样率不对会真的重采样，位深不对只是换个容器
     * 装同样的样本。
     */
    public OutputRequest withBitDepth(BitDepth depth) {
        return new OutputRequest(sampleRate, depth, channels, ringFrames, fixedBufferFrames);
    }

    /** 指定目标声道数（{@code null} = 不做筛选）。 */
    public OutputRequest withChannels(Integer want) {
        return new OutputRequest(sampleRate, bitDepth, want, ringFrames, fixedBufferFrames);
    }

    /** 目标采样率。必填。 */
    public int sampleRate() {
        return sampleRate;
    }

    /** 目标位深。{@code null} = 由设备选最优（优先 F32，无转换开销）。 */
    public BitDepth bitDepth() {
        return bitDepth;
    }

    /** 目标声道数。{@code null} = 不做筛选。 */
    public Integer channels() {
        return channels;
    }

    /** 环形缓冲帧数。 */
    public int ringFrames() {
        return ringFrames;
    }

    /**
     * 要求固定的设备缓冲帧数。{@code null} = 交给设备决定。
     *
     * <p>显式指定能压低延迟，但给得太小会在负载波动时频繁欠载。
     */
    public Integer fixedBufferFrames() {
        return fixedBufferFrames;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OutputRequest)) {
            return false;
        }
        OutputRequest other = (OutputRequest) o;
        return sampleRate == other.sampleRate
                && ringFrames == other.ringFrames
                && bitDepth == other.bitDepth
                && eq(channels, other.channels)
                && eq(fixedBufferFrames, other.fixedBufferFrames);
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public int hashCode() {
        int h = sampleRate;
        h = 31 * h + (bitDepth == null ? 0 : bitDepth.hashCode());
        h = 31 * h + (channels == null ? 0 : channels.hashCode());
        h = 31 * h + ringFrames;
        h = 31 * h + (fixedBufferFrames == null ? 0 : fixedBufferFrames.hashCode());
        return h;
    }

    @Override
    public String toString() {
        return "OutputRequest{" + sampleRate + " Hz, depth=" + (bitDepth == null ? "auto" : bitDepth)
                + ", channels=" + (channels == null ? "any" : channels.toString())
                + ", ring " + ringFrames + " frames"
                + ", fixedBuffer=" + (fixedBufferFrames == null ? "device" : fixedBufferFrames.toString()) + '}';
    }
}
