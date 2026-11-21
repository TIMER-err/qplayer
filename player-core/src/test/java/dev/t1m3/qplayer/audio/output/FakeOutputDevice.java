package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.RingBuffer;

import java.util.Collections;
import java.util.List;

/**
 * 测试用的假输出设备：<strong>完全不碰真实声卡</strong>。
 *
 * <p>为什么需要它：上游那些「开流」路径（{@code open} / {@code open_on} / {@code build}）
 * 在 Rust 里也测不到（要 {@code &cpal::Device}），所以上游干脆没写。移植时把设备抽成
 * {@link OutputDevice} 接口之后，这些路径可以先在假设备上走一遍：
 * 「选不到就报错，不静默降级」「实际协商结果如实回报」「取走写入端只能一次」
 * 这三条都能在没有声卡的机器上被锁住。
 */
final class FakeOutputDevice implements OutputDevice {

    /** 假流：只记录被调用过什么。 */
    static final class Stream implements OutputStreamControl {

        boolean played;
        boolean closed;
        boolean running;

        @Override
        public void play() {
            played = true;
            running = true;
        }

        @Override
        public void close() {
            closed = true;
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    private final String name;
    private final DeviceCapabilities capabilities;
    private final OutputDevice.BufferRange bufferRange;

    /** 最后一次开流的实参，供断言检查「请求有没有原样传下去」。 */
    OutputConfig openedConfig;
    RingBuffer.Consumer openedConsumer;
    Integer openedFixedBufferFrames;
    Stream lastStream;
    int openCount;

    FakeOutputDevice(String name, DeviceCapabilities capabilities, OutputDevice.BufferRange bufferRange) {
        this.name = name;
        this.capabilities = capabilities;
        this.bufferRange = bufferRange;
    }

    /** 一台「F32/I24/I16 立体声，44.1k~192k」的常见设备。 */
    static FakeOutputDevice versatile() {
        return new FakeOutputDevice("fake-versatile", new DeviceCapabilities(java.util.Arrays.asList(
                new DeviceCapability(OutputSampleFormat.F32, 2, 44_100, 192_000),
                new DeviceCapability(OutputSampleFormat.I24, 2, 44_100, 192_000),
                new DeviceCapability(OutputSampleFormat.I16, 2, 44_100, 96_000)), null), null);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public DeviceCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public OutputConfig defaultConfig(int ringFrames) {
        return OutputChooser.chooseDefault(capabilities).toConfig(ringFrames);
    }

    @Override
    public OutputStreamControl openStream(OutputConfig config, RingBuffer.Consumer consumer,
                                          Integer fixedBufferFrames) {
        openCount++;
        openedConfig = config;
        openedConsumer = consumer;
        openedFixedBufferFrames = fixedBufferFrames;
        lastStream = new Stream();
        return lastStream;
    }

    @Override
    public OutputDevice.BufferRange bufferSizeRange() {
        return bufferRange;
    }

    /** 一个只有名字的假设备列表，给 {@link OutputDevices} 的测试用。 */
    static List<OutputDevice> single(OutputDevice device) {
        return Collections.singletonList(device);
    }
}
