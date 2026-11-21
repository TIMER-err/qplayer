package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.BitDepth;
import dev.t1m3.qplayer.audio.core.RingBuffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 开流路径的测试（上游没有对应条目）。
 *
 * <p>上游的 {@code OutputHandle::open} / {@code open_on} / {@code build} 都要
 * {@code &cpal::Device}，没法单测，所以上游一个都没写。移植后设备是接口，
 * 这些路径可以在假设备上完整走一遍——<strong>不碰真实声卡</strong>。
 */
public class OutputHandleTest {

    /** 协商结果如实回报：请求 88.2k / 24 bit 时，只有 I24 档位覆盖，就该开 I24。 */
    @Test
    public void openOnReportsActualNegotiatedConfig() {
        FakeOutputDevice device = new FakeOutputDevice("fake", new DeviceCapabilities(java.util.Arrays.asList(
                new DeviceCapability(OutputSampleFormat.F32, 2, 44_100, 48_000),
                new DeviceCapability(OutputSampleFormat.I24, 2, 44_100, 192_000)), null), null);

        OutputRequest req = OutputRequest.atRate(88_200, 4_800).withBitDepth(BitDepth.INT24);
        OutputHandle handle = OutputHandle.openOn(device, req);

        assertEquals(88_200, handle.config().sampleRate());
        assertEquals(OutputSampleFormat.I24, handle.config().sampleFormat());
        assertEquals(BitDepth.INT24, handle.config().bitDepth());
        assertEquals(2, handle.config().channels());
        assertEquals(4_800, handle.config().ringFrames());

        assertEquals("只该开一次流", 1, device.openCount);
        assertSame("协商好的配置要原样交给宿主", handle.config(), device.openedConfig);
        assertNotNull(device.openedConsumer);
        assertTrue("开流后要启动", device.lastStream.played);
        assertTrue(handle.isRunning());

        // 环形缓冲按「采样数」申请（帧 × 声道），并向上取整到 2 的幂
        assertTrue("容量至少要有请求的采样数",
                device.openedConsumer.capacity() >= 4_800 * 2);
        assertEquals("写入端初始应可写满整个环",
                device.openedConsumer.capacity(), handle.freeSamples());
    }

    /** 采样率选不到 → 抛错，且<strong>根本不该去开流</strong>。 */
    @Test
    public void unsupportedRateFailsBeforeOpeningStream() {
        FakeOutputDevice device = FakeOutputDevice.versatile();
        AudioException e = assertThrows(AudioException.class,
                () -> OutputHandle.openOn(device, OutputRequest.atRate(384_000, 4_800)));
        assertEquals(AudioException.Kind.OUTPUT, e.kind());
        assertEquals("选不到就不该开流", 0, device.openCount);
    }

    /**
     * 位深请求选不到时如实回报实际协商结果，而不是假装成功。
     *
     * <p>这是输出层的核心契约：<strong>假装位完美是这类功能最糟的失败模式</strong>。
     * 上层拿 {@link OutputHandle#config()} 和源格式一起喂给
     * {@code PathVerdict.classify} 就能看出这里其实降到了 16 bit。
     */
    @Test
    public void bitDepthFallbackIsReportedNotFaked() {
        FakeOutputDevice device = new FakeOutputDevice("only16", new DeviceCapabilities(java.util.Arrays.asList(
                new DeviceCapability(OutputSampleFormat.I16, 2, 44_100, 48_000)), null), null);

        OutputRequest req = OutputRequest.atRate(44_100, 4_800).withBitDepth(BitDepth.INT24);
        OutputHandle handle = OutputHandle.openOn(device, req);

        assertEquals("请求 24 bit 但设备只有 16 bit：实际生效是 16 bit",
                BitDepth.INT16, handle.config().bitDepth());
        assertEquals(OutputSampleFormat.I16, handle.config().sampleFormat());
    }

    /** 固定缓冲帧数要原样传到宿主（不给就是 null = 交给设备决定）。 */
    @Test
    public void fixedBufferFramesArePassedThrough() {
        FakeOutputDevice device = FakeOutputDevice.versatile();
        OutputRequest req = new OutputRequest(48_000, null, null, 9_600, Integer.valueOf(512));
        OutputHandle handle = OutputHandle.openOn(device, req);
        assertEquals(Integer.valueOf(512), device.openedFixedBufferFrames);
        assertEquals(9_600, handle.config().ringFrames());

        FakeOutputDevice device2 = FakeOutputDevice.versatile();
        OutputHandle.openOn(device2, OutputRequest.atRate(48_000, 9_600));
        assertNull("不指定就让设备决定", device2.openedFixedBufferFrames);
    }

    /** 取走写入端只能成功一次——允许外面拿到两份就等于允许两个生产者同时写环。 */
    @Test
    public void takeProducerIsOneShot() {
        FakeOutputDevice device = FakeOutputDevice.versatile();
        OutputHandle handle = OutputHandle.openOn(device, OutputRequest.atRate(48_000, 9_600));

        RingBuffer.Producer producer = handle.takeProducer();
        assertNotNull(producer);
        assertNull("第二次取必须是 null", handle.takeProducer());
        assertEquals("写入端被取走后不该再报可写", 0, handle.freeSamples());
        assertTrue(producer.capacity() > 0);
    }

    /** 关闭要真的把流停掉（Java 没有 Drop，不关就是持续出声）。 */
    @Test
    public void closeStopsTheStream() {
        FakeOutputDevice device = FakeOutputDevice.versatile();
        OutputHandle handle = OutputHandle.openOn(device, OutputRequest.atRate(48_000, 9_600));
        assertTrue(handle.isRunning());

        handle.close();
        assertFalse(handle.isRunning());
        assertTrue(device.lastStream.closed);

        // 可重复调用，不会把宿主叫醒第二次
        handle.close();
        assertTrue(device.lastStream.closed);
    }

    /** 走「设备默认配置」的那条入口：48 kHz / 立体声 / F32 直通。 */
    @Test
    public void openUsesDeviceDefaultConfig() {
        FakeOutputDevice device = FakeOutputDevice.versatile();
        OutputHandle handle = OutputHandle.open(device, 9_600);
        assertEquals(48_000, handle.config().sampleRate());
        assertEquals(OutputSampleFormat.F32, handle.config().sampleFormat());
        assertEquals(2, handle.config().channels());
        assertEquals("默认配置这条路上没有位深诉求", 1, device.openCount);
        handle.close();
    }
}
