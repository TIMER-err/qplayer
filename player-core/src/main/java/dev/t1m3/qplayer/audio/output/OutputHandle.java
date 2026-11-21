package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.RingBuffer;

/**
 * 一个已打开的音频输出。
 *
 * <p>对应 Sonalis 的 {@code sonalis_output::OutputHandle}：持有 {@code Producer} 供引擎写入，
 * 底层流在这个句柄被关闭时停止。
 *
 * <h3>与上游的两点差异</h3>
 *
 * <ul>
 *   <li>上游靠 {@code Drop} 自动停流，Java 没有析构，所以多了显式的 {@link #close()}。
 *       <strong>不关就是持续出声</strong>，调用方要自己负责</li>
 *   <li>{@link #takeProducer()} 的「只能取一次」语义保留（上游的理由：{@code Producer} 内部
 *       持有环形缓冲的 {@code Arc}，允许外面拿到两份就会双重释放；Java 这边对应的是
 *       「两个线程各自以为自己是生产者」），取走后 {@link #freeSamples()} 返回 0</li>
 * </ul>
 */
public final class OutputHandle {

    private final OutputConfig config;
    private final OutputStreamControl stream;
    /** 写入端。只能被取走一次。 */
    private RingBuffer.Producer producer;
    private boolean closed;

    /**
     * 供 {@link OutputDevice} 实现调用。
     *
     * <p>上游的 {@code build} 是私有的、由 {@code OutputHandle} 自己完成；
     * Java 这边开流必须落到宿主上，所以构造入口是公开的，但
     * <strong>调用方应该用 {@link #openDefault(int)} / {@link #openWithRequest(OutputRequest)}</strong>。
     */
    public OutputHandle(OutputConfig config, RingBuffer.Producer producer, OutputStreamControl stream) {
        if (config == null || producer == null || stream == null) {
            throw AudioException.config("OutputHandle 需要 config / producer / stream 三者齐全");
        }
        this.config = config;
        this.producer = producer;
        this.stream = stream;
    }

    /**
     * 用默认输出设备打开一路输出，采用设备<strong>默认</strong>配置。
     *
     * <p>想让设备跟随源采样率、或指定位深，请用 {@link #openWithRequest(OutputRequest)}。
     *
     * <p>{@code ringFrames} 是环形缓冲容量（帧）。太小会频繁欠载，太大会让暂停/定位的响应
     * 变钝。200 ms 上下是常见折中（48 kHz 立体声约 9600 帧）。
     *
     * @throws AudioException 没有可用的输出设备，或开流失败
     */
    public static OutputHandle openDefault(int ringFrames) {
        OutputDevice device = OutputDevices.defaultDevice();
        if (device == null) {
            throw AudioException.output("没有可用的输出设备");
        }
        return open(device, ringFrames);
    }

    /** 用指定设备打开一路输出（设备默认配置）。 */
    public static OutputHandle open(OutputDevice device, int ringFrames) {
        return build(device, device.defaultConfig(ringFrames), null);
    }

    /**
     * 按请求打开一路输出。
     *
     * <p>会在设备的全部可用档位里挑一个最接近请求的：优先精确匹配采样率，位深按
     * 「请求的优先，否则 F32」打分。<strong>选不到就报错，不静默降级</strong>——
     * 降级与否该由上层看着源格式决定，输出层说了不算。
     *
     * <p>成功时 {@link #config()} 给出的是<strong>实际生效值</strong>，
     * 拿它和源格式一起喂给 {@code PathVerdict.classify} 就能得到位完美判定。
     *
     * @throws AudioException 没有可用的输出设备、设备不支持该采样率，或开流失败
     */
    public static OutputHandle openWithRequest(OutputRequest req) {
        OutputDevice device = OutputDevices.defaultDevice();
        if (device == null) {
            throw AudioException.output("没有可用的输出设备");
        }
        return openOn(device, req);
    }

    /** 在指定设备上按请求打开。 */
    public static OutputHandle openOn(OutputDevice device, OutputRequest req) {
        OutputChooser.Chosen chosen = OutputChooser.choose(device.capabilities(), req);
        return build(device, chosen.toConfig(req.ringFrames()), req.fixedBufferFrames());
    }

    /** 从已协商好的配置开流。对应上游的 {@code OutputHandle::build}。 */
    private static OutputHandle build(OutputDevice device, OutputConfig config, Integer fixedBufferFrames) {
        // 环形缓冲容量按「采样数」申请（帧 × 声道），与上游一致
        RingBuffer.Ring ring = RingBuffer.create(config.ringSamples());
        OutputStreamControl stream = device.openStream(config, ring.consumer, fixedBufferFrames);
        // 上游在 build 里就对 play() 的失败报错，不吞——开不响的流比没有流更误导人
        stream.play();
        return new OutputHandle(config, ring.producer, stream);
    }

    /** <strong>实际</strong>协商结果。位完美判定要拿它和源格式比。 */
    public OutputConfig config() {
        return config;
    }

    /**
     * 取走写入端。只能成功一次（第二次返回 {@code null}）。
     *
     * <p>之所以不是「借用」：{@code Producer} 代表唯一的生产者身份，允许外面拿到两份就
     * 等于允许两个线程同时写环，游标会互相踩。
     */
    public RingBuffer.Producer takeProducer() {
        RingBuffer.Producer p = producer;
        producer = null;
        return p;
    }

    /** 还能写入多少采样。写入端已被取走时返回 0。 */
    public int freeSamples() {
        RingBuffer.Producer p = producer;
        return p == null ? 0 : p.free();
    }

    /** 底层流是否正在播放。 */
    public boolean isRunning() {
        return stream.isRunning();
    }

    /** 停止并释放底层流。可重复调用。 */
    public void close() {
        if (!closed) {
            closed = true;
            stream.close();
        }
    }

    @Override
    public String toString() {
        // 上游手写 Debug 的理由：cpal::Stream 没有实现 Debug，派生会直接编译失败（E0277）。
        // 这里同样不打印底层流，只打印协商结果与写入端是否已被取走。
        return "OutputHandle{config=" + config + ", producerTaken=" + (producer == null) + '}';
    }
}
