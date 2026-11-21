package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.BitDepth;
import dev.t1m3.qplayer.audio.core.RingBuffer;

import java.util.List;

/**
 * 一个输出设备。
 *
 * <p>对应 Sonalis 里的 {@code cpal::Device}：上游的自由函数
 * {@code capabilities_of(device)} / {@code supported_rates(device)} /
 * {@code supported_bit_depths(device)} / {@code probe_dsd_support()} 等等，
 * 在 Java 里落到这台设备的实例方法上（探测默认设备的那几个见 {@link OutputDevices}）。
 *
 * <p><strong>为什么这是接口而不是直接调 {@code javax.sound.sampled}</strong>：
 * 那个包在安卓上不存在，所以设备 I/O 不能放在 player-core。player-core 只认这个接口，
 * 桌面宿主实现它（{@code JavaSoundOutputDevice}），安卓宿主以后实现成 {@code AudioTrack} 版本。
 *
 * <p>实现约定：
 *
 * <ul>
 *   <li>{@link #capabilities()} <strong>绝不抛异常</strong>：没有可用声卡时返回
 *       {@link DeviceCapabilities#empty()}。探测是界面初始化时必走的一步，
 *       它抛异常等于整个设置页打不开</li>
 *   <li>开流的接口（{@link #openStream}）才允许抛 {@code AudioException}</li>
 * </ul>
 */
public interface OutputDevice {

    /** 设备的缓冲帧数范围。对应 cpal 的 {@code SupportedBufferSize}。 */
    final class BufferRange {

        public final int min;
        public final int max;

        public BufferRange(int min, int max) {
            this.min = min;
            this.max = max;
        }

        @Override
        public String toString() {
            return min + ".." + max + " frames";
        }
    }

    /** 设备名（给界面下拉框用）。 */
    String name();

    /** 这台设备的能力矩阵。绝不抛异常，探不到就返回空矩阵。 */
    DeviceCapabilities capabilities();

    /**
     * 设备的默认配置（套上调用方给的环形缓冲帧数）。
     *
     * <p>对应上游 {@code device.default_output_config()}：只有明确要「用设备默认」的
     * 那条入口（{@link OutputHandle#openDefault(int)}）才该走这里。
     */
    OutputConfig defaultConfig(int ringFrames);

    /**
     * 按已协商好的配置开一条流（<strong>不启动</strong>，启动由 {@link OutputHandle} 统一做）。
     *
     * <p>对应上游的 {@code build_stream::<T>(device, stream_config, consumer, scratch)}：
     * 消费端由上层（环形缓冲）交进来，写入路径只做
     * {@code pop_or_silence} + 采样格式转换。
     *
     * @param config            已协商好的配置（采样率/声道/格式/环形帧数）
     * @param consumer          环形缓冲的消费者端，音频线程独占
     * @param fixedBufferFrames 要求固定的设备缓冲帧数，{@code null} = 交给设备决定
     */
    OutputStreamControl openStream(OutputConfig config, RingBuffer.Consumer consumer,
                                   Integer fixedBufferFrames);

    /** 设备声明支持的采样率（去重、升序）。对应上游 {@code supported_rates(device)}。 */
    default int[] supportedRates() {
        return capabilities().supportedRates();
    }

    /** 设备声明支持的位深（去重、升序）。对应上游 {@code supported_bit_depths(device)}。 */
    default List<BitDepth> supportedBitDepths() {
        return capabilities().supportedBitDepths();
    }

    /**
     * DSD 原生支持情况。默认看设备声明过的原始格式清单。
     *
     * <p>注意<strong>不能</strong>拿 {@link #capabilities()} 的可开流矩阵去判 DSD：
     * 矩阵里永远不会有 DSD（{@link OutputSampleFormat} 刻意不接收），那样会恒为
     * {@link DsdSupport#NONE} 而我们还以为探到了。
     */
    default DsdSupport dsdSupport() {
        return capabilities().dsdSupport();
    }

    /**
     * 设备支持的声道数上限。
     *
     * <p>上游取的是<strong>默认输出配置</strong>的声道数，不是矩阵里的最大值：
     * 「能开 8 声道」和「默认就是 8 声道」是两件事，置灰判定要的是后者。
     */
    default int maxChannels() {
        return capabilities().maxChannels();
    }

    /**
     * 设备支持的缓冲帧数范围。
     *
     * <p>{@code null} 表示平台不提供这项信息——对应上游的
     * {@code SupportedBufferSize::Unknown} 分支，<strong>不能当成 0 处理</strong>。
     * 注意这是<strong>范围</strong>而不是默认值；要拿开流后的实际缓冲大小得问流本身。
     */
    default BufferRange bufferSizeRange() {
        return null;
    }

    /** 用本设备的默认配置开流。对应上游 {@code OutputHandle::open(device, ring_frames)}。 */
    default OutputHandle open(int ringFrames) {
        return OutputHandle.open(this, ringFrames);
    }

    /** 在本设备上按请求开流。对应上游 {@code OutputHandle::open_on(device, req)}。 */
    default OutputHandle openWithRequest(OutputRequest req) {
        return OutputHandle.openOn(this, req);
    }
}
