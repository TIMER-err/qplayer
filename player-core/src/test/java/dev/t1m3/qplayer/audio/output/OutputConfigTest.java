package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.BitDepth;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 输出配置的对齐测试：对应 Sonalis {@code crates/sonalis-output/src/lib.rs} 的 {@code mod tests}。
 *
 * <p>上游 1 条 → 本地 1 条对齐（另有 2 条「额外补充」）：
 * <pre>
 * ring_size_reported_in_ms    → ringSizeReportedInMs
 * </pre>
 */
public class OutputConfigTest {

    /** 对应上游 {@code ring_size_reported_in_ms}。 */
    @Test
    public void ringSizeReportedInMs() {
        OutputConfig cfg = new OutputConfig(48_000, 2, OutputSampleFormat.F32, 9_600);
        assertEquals(19_200, cfg.ringSamples());
        assertTrue("容差 1e-3 与上游一致", Math.abs(cfg.ringMs() - 200.0f) < 1e-3);
        assertEquals(BitDepth.FLOAT32, cfg.bitDepth());
    }

    // ------------------------------------------------------------------
    // 以下为移植时补充的测试（上游没有对应条目）
    // ------------------------------------------------------------------

    /**
     * 采样率为 0 时 {@code ring_ms} 不能除零（上游是 {@code sample_rate.max(1)}）。
     *
     * <p>Java 的 {@code OutputConfig} 直接拒绝 0 采样率，所以这里改从
     * 「合法但极端」的一端验证：1 Hz + 1 帧 = 1000 ms。
     */
    @Test
    public void ringMsHandlesExtremeRatios() {
        assertEquals(1_000.0f, new OutputConfig(1, 1, OutputSampleFormat.F32, 1).ringMs(), 1e-3f);
        // 768 kHz 下 9600 帧只有 12.5 ms
        assertEquals(12.5f, new OutputConfig(768_000, 2, OutputSampleFormat.F32, 9_600).ringMs(), 1e-3f);
        // 多声道不影响时长（ring_frames 是帧数，不是采样数）
        assertEquals(200.0f, new OutputConfig(48_000, 8, OutputSampleFormat.F32, 9_600).ringMs(), 1e-3f);
    }

    /** 值对象的相等性与字段一一对应。 */
    @Test
    public void configEquality() {
        OutputConfig a = new OutputConfig(48_000, 2, OutputSampleFormat.I24, 9_600);
        OutputConfig b = new OutputConfig(48_000, 2, OutputSampleFormat.I24, 9_600);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(new OutputConfig(44_100, 2, OutputSampleFormat.I24, 9_600)));
        assertFalse("同 32 位宽但格式不同就不相等", a.equals(new OutputConfig(48_000, 2, OutputSampleFormat.I32, 9_600)));
        // I24 是「4 字节存储」，环形缓冲按帧数算，不受存储宽度影响
        assertEquals(2, a.channels());
    }
}
