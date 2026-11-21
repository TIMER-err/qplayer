package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.AudioException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 宿主设备来源与顶层探测入口的测试（上游没有对应条目）。
 *
 * <p>上游这几个函数都直接 {@code cpal::default_host()}，没有注入点，也就没法测。
 * Java 这边 player-core 不能依赖 {@code javax.sound.sampled}，设备来源必须由宿主注入，
 * 于是「没装来源 / 设备列表为空 / 探测抛异常」这几条降级路径都可以被测到——
 * 而它们正是「设置页在有声卡没声卡的机器上都要能打开」所依赖的。
 */
public class OutputDevicesTest {

    private OutputDevices.Provider saved;

    @Before
    public void rememberProvider() {
        saved = OutputDevices.provider();
    }

    @After
    public void restoreProvider() {
        OutputDevices.install(saved);
    }

    /** 没装设备来源：查询类接口优雅返回空，不抛异常。 */
    @Test
    public void withoutProviderQueriesAreGraceful() {
        OutputDevices.install(null);
        assertFalse(OutputDevices.isInstalled());
        assertNull(OutputDevices.defaultDevice());
        assertTrue(OutputDevices.list().isEmpty());
        assertTrue(OutputDevices.listOutputDeviceNames().isEmpty());
        assertNull("平台不提供缓冲范围信息时返回 null（对应上游 Unknown 分支）",
                OutputDevices.probeBufferSizeRange());

        // 需要「默认设备」的探测才抛错，消息与上游一致
        AudioException e = assertThrows(AudioException.class, () -> OutputDevices.probeCapabilities());
        assertEquals(AudioException.Kind.OUTPUT, e.kind());
        assertTrue(e.getMessage(), e.getMessage().contains("没有可用的输出设备"));
        assertThrows(AudioException.class, () -> OutputDevices.supportedRates());
        assertThrows(AudioException.class, () -> OutputDevices.supportedBitDepths());
        assertThrows(AudioException.class, () -> OutputDevices.probeDsdSupport());
        assertThrows(AudioException.class, () -> OutputDevices.probeMaxChannels());
    }

    /**
     * 设备存在但一个可开流档位都没有（没声卡的机器上很可能如此）：
     * 探测返回<strong>空矩阵</strong>而不是抛异常。
     */
    @Test
    public void emptyCapabilityMatrixIsReportedNotThrown() {
        final FakeOutputDevice device = new FakeOutputDevice("no-caps", DeviceCapabilities.empty(), null);
        OutputDevices.install(new OutputDevices.Provider() {
            @Override
            public List<OutputDevice> list() {
                return FakeOutputDevice.single(device);
            }

            @Override
            public OutputDevice defaultDevice() {
                return device;
            }
        });

        DeviceCapabilities caps = OutputDevices.probeCapabilities();
        assertTrue(caps.isEmpty());
        assertEquals(0, OutputDevices.supportedRates().length);
        assertTrue(OutputDevices.supportedBitDepths().isEmpty());
        assertEquals(DsdSupport.NONE, OutputDevices.probeDsdSupport());
        assertEquals(0, OutputDevices.probeMaxChannels());
        assertEquals("no-caps", OutputDevices.list().get(0).name());
    }

    /** 宿主枚举抛异常时，探测路径当「没有设备」处理，不把异常抛给界面。 */
    @Test
    public void providerFailuresAreSwallowed() {
        OutputDevices.install(new OutputDevices.Provider() {
            @Override
            public List<OutputDevice> list() {
                throw new IllegalStateException("混音器枚举炸了");
            }

            @Override
            public OutputDevice defaultDevice() {
                throw new IllegalStateException("默认设备查询炸了");
            }
        });

        assertTrue(OutputDevices.list().isEmpty());
        assertNull(OutputDevices.defaultDevice());
        assertNull(OutputDevices.probeBufferSizeRange());
        assertThrows(AudioException.class, () -> OutputDevices.probeCapabilities());
    }

    /** 探测结果要真的来自默认设备，而不是随便哪一台。 */
    @Test
    public void probesUseTheDefaultDevice() {
        final FakeOutputDevice dsdCapable = new FakeOutputDevice("dsd-dac", new DeviceCapabilities(
                java.util.Arrays.asList(new DeviceCapability(OutputSampleFormat.I24, 2, 44_100, 384_000)),
                java.util.Arrays.asList(DeviceSampleFormat.I24, DeviceSampleFormat.DSD_U16)),
                new OutputDevice.BufferRange(64, 4_096));
        final FakeOutputDevice cdOnly = new FakeOutputDevice("cd", new DeviceCapabilities(
                java.util.Arrays.asList(new DeviceCapability(OutputSampleFormat.I16, 2, 44_100, 48_000)),
                null), null);

        OutputDevices.install(new OutputDevices.Provider() {
            @Override
            public List<OutputDevice> list() {
                List<OutputDevice> all = new ArrayList<OutputDevice>();
                all.add(cdOnly);
                all.add(dsdCapable);
                return all;
            }

            @Override
            public OutputDevice defaultDevice() {
                return dsdCapable;
            }
        });

        assertEquals(2, OutputDevices.list().size());
        assertEquals("设备名的顺序要与 list() 一致",
                java.util.Arrays.asList("cd", "dsd-dac"), OutputDevices.listOutputDeviceNames());

        DeviceCapabilities caps = OutputDevices.probeCapabilities();
        assertTrue("Hi-Res 判定由默认设备决定", caps.isHiresCapable());
        assertEquals(DsdSupport.AVAILABLE, OutputDevices.probeDsdSupport());
        assertEquals(2, OutputDevices.probeMaxChannels());
        assertEquals(384_000, OutputDevices.supportedRates()[OutputDevices.supportedRates().length - 1]);
        assertEquals(1, OutputDevices.supportedBitDepths().size());

        OutputDevice.BufferRange range = OutputDevices.probeBufferSizeRange();
        assertEquals(64, range.min);
        assertEquals(4_096, range.max);
    }
}
