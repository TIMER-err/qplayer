package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.BitDepth;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 设备能力的对齐测试：对应 Sonalis {@code crates/sonalis-output/src/lib.rs} 的 {@code mod tests}。
 *
 * <p>上游 1 条 → 本地 1 条对齐（另有 5 条「额外补充」）：
 * <pre>
 * capability_coverage    → capabilityCoverage
 * </pre>
 *
 * <p>上游的 {@code supported_rates} / {@code supported_bit_depths} 是接收
 * {@code &Device} 的自由函数，没有单测（要真设备）；这里它们落在
 * {@link DeviceCapabilities} 上、只吃能力矩阵，所以补了测试——
 * 「常见档位采样、去重升序」这条规则本来就该被测到。
 */
public class DeviceCapabilitiesTest {

    private static DeviceCapabilities capsOf(DeviceCapability... entries) {
        return new DeviceCapabilities(Arrays.asList(entries), null);
    }

    /** 对应上游 {@code capability_coverage}。 */
    @Test
    public void capabilityCoverage() {
        DeviceCapability c = new DeviceCapability(OutputSampleFormat.I24, 2, 44_100, 192_000);
        assertTrue(c.coversRate(44_100));
        assertTrue(c.coversRate(96_000));
        assertTrue(c.coversRate(192_000));
        assertFalse(c.coversRate(384_000));
        assertTrue(c.isHiresCapable());

        // 上游用 `..c` 只改一个字段
        DeviceCapability cd = new DeviceCapability(OutputSampleFormat.I24, 2, 44_100, 48_000);
        assertFalse("只能到 48k 不算 Hi-Res 能力", cd.isHiresCapable());
        assertTrue("边界：上界恰好 44100 时覆盖 44100", cd.coversRate(44_100));
    }

    // ------------------------------------------------------------------
    // 以下为移植时补充的测试（上游没有对应条目）
    // ------------------------------------------------------------------

    /** 采样率清单：按常见档位采样、去重、升序（对应上游 {@code supported_rates}）。 */
    @Test
    public void supportedRatesAreDedupedAndSorted() {
        DeviceCapabilities caps = capsOf(
                new DeviceCapability(OutputSampleFormat.I16, 2, 44_100, 48_000),
                new DeviceCapability(OutputSampleFormat.I24, 2, 44_100, 96_000),
                new DeviceCapability(OutputSampleFormat.I24, 1, 8_000, 192_000));
        // 44.1k/48k 在两档里都出现 → 只报一次；1 声道的档位把上界推到 192k
        assertArrayEquals(new int[]{8_000, 11_025, 16_000, 22_050, 32_000, 44_100, 48_000, 88_200, 96_000, 176_400, 192_000},
                caps.supportedRates());
        // 区间里的「非档位」值不会被展开进来（避免上万个值）
        assertEquals(11, caps.supportedRates().length);
    }

    /** 位深清单：去重、按枚举声明顺序升序（对应上游 {@code supported_bit_depths}）。 */
    @Test
    public void supportedBitDepthsAreDedupedAndSorted() {
        DeviceCapabilities caps = capsOf(
                new DeviceCapability(OutputSampleFormat.I32, 2, 44_100, 192_000),
                new DeviceCapability(OutputSampleFormat.I16, 2, 44_100, 192_000),
                new DeviceCapability(OutputSampleFormat.I16, 1, 44_100, 192_000),
                new DeviceCapability(OutputSampleFormat.I24, 2, 44_100, 192_000));
        assertEquals(Arrays.asList(BitDepth.INT16, BitDepth.INT24, BitDepth.INT32), caps.supportedBitDepths());
        assertTrue(caps.isHiresCapable());
        assertEquals(2, caps.maxChannels());
    }

    /** 空矩阵（没声卡）不该抛异常，各项导出值都要有确定的答案。 */
    @Test
    public void emptyMatrixIsUsable() {
        DeviceCapabilities empty = DeviceCapabilities.empty();
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.supportedRates().length);
        assertTrue(empty.supportedBitDepths().isEmpty());
        assertEquals(0, empty.maxChannels());
        assertFalse(empty.isHiresCapable());
        assertEquals(DsdSupport.NONE, empty.dsdSupport());
        assertEquals("空矩阵不能有 0 声道的假档位", 0, empty.entries().size());
    }

    /**
     * DSD 支持度看的是设备<strong>声明过</strong>的原始格式清单，不是可开流矩阵。
     *
     * <p>这是移植时特意分开的一层：过滤后的矩阵里永远不会有 DSD
     * （{@link OutputSampleFormat} 不接收 DSD），拿它判定会恒为 NONE 而我们还以为探到了。
     */
    @Test
    public void dsdSupportComesFromDeclaredFormats() {
        List<DeviceSampleFormat> withDsd = Arrays.asList(
                DeviceSampleFormat.F32, DeviceSampleFormat.DSD_U16, DeviceSampleFormat.I16);
        DeviceCapabilities caps = new DeviceCapabilities(
                Arrays.asList(new DeviceCapability(OutputSampleFormat.F32, 2, 44_100, 192_000)), withDsd);
        assertEquals(DsdSupport.AVAILABLE, caps.dsdSupport());

        // 只有 PCM 格式（哪怕有 F64）时不算 DSD 支持
        DeviceCapabilities noDsd = new DeviceCapabilities(
                Arrays.asList(new DeviceCapability(OutputSampleFormat.F32, 2, 44_100, 192_000)),
                Arrays.asList(DeviceSampleFormat.F32, DeviceSampleFormat.F64));
        assertEquals(DsdSupport.NONE, noDsd.dsdSupport());
        assertEquals(DsdSupport.NONE, DsdSupport.of(null));
        assertEquals(DsdSupport.NONE, DsdSupport.of(new ArrayList<DeviceSampleFormat>()));
    }

    /** 矩阵是不可变的：外部拿到的列表改不动它。 */
    @Test
    public void matrixIsImmutable() {
        List<DeviceCapability> entries = new ArrayList<DeviceCapability>();
        entries.add(new DeviceCapability(OutputSampleFormat.I16, 2, 44_100, 48_000));
        DeviceCapabilities caps = new DeviceCapabilities(entries, null);
        entries.clear();
        assertEquals("构造后外部改动不该影响矩阵", 1, caps.entries().size());
        try {
            caps.entries().add(new DeviceCapability(OutputSampleFormat.I16, 2, 44_100, 48_000));
            org.junit.Assert.fail("矩阵应当不可修改");
        } catch (UnsupportedOperationException expected) {
            // 期望如此
        }
    }
}
