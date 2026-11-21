package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 时间线模型的数学（对照 Apple 参考实现 {@code am_ramp} / {@code am_processor} ✓）。
 * 钉住：端点取端点值 ✓、线性中点=平均 ✓、余弦中点=平均且关于中点对称 ✓、
 * 零长度瞬间跳变 ✓、未声明参数返回 fallback ✓（等价 {@code am_control} 兜底 ✓）。
 */
public class ProcessorRampTest {

    private static final double EPS = 1e-9;

    @Test
    public void linearRampHitsEndpointsAndMidpoint() {
        Ramp r = new Ramp("gain", 2.0, 6.0, -1.5, 0.5, Ramp.Curve.LINEAR);
        assertEquals(-1.5, r.valueAt(0.0), EPS);
        assertEquals(-1.5, r.valueAt(2.0), EPS);
        assertEquals(-0.5, r.valueAt(4.0), EPS);
        assertEquals(0.5, r.valueAt(6.0), EPS);
        assertEquals(0.5, r.valueAt(99.0), EPS);
    }

    @Test
    public void cosineRampIsSymmetricAroundTheMidpoint() {
        Ramp r = new Ramp("hp", 0.0, 8.0, 0.0, 1.0, Ramp.Curve.COSINE);
        assertEquals(0.0, r.valueAt(0.0), EPS);
        assertEquals(0.5, r.valueAt(4.0), EPS);
        assertEquals(1.0, r.valueAt(8.0), EPS);
        for (double t = 0.5; t < 8.0; t += 0.5) {
            assertEquals(1.0, r.valueAt(t) + r.valueAt(8.0 - t), 1e-6);
        }
    }

    @Test
    public void zeroLengthRampJumpsToLastAtItsInstant() {
        Ramp r = new Ramp("x", 3.0, 3.0, 7.0, 9.0, Ramp.Curve.LINEAR);
        assertEquals(7.0, r.valueAt(2.99), EPS);
        assertEquals(9.0, r.valueAt(3.0), EPS);        // 到点 ⇒ 终点值 ✓
    }

    @Test
    public void processorQueriesByNameAndFallsBack() {
        Processor eq = new Processor("eq", 0.0, 8.0)
                .ramp("hp_cutoff_freq", 0.0, 8.0, 80.0, 40.0, Ramp.Curve.COSINE)
                .ramp("out_gain", 0.0, 8.0, -1.5, 0.0, Ramp.Curve.LINEAR);
        assertEquals(80.0, eq.control("hp_cutoff_freq", 0.0, -1.0), EPS);
        assertEquals(40.0, eq.control("hp_cutoff_freq", 8.0, -1.0), EPS);
        assertEquals(-1.5, eq.control("out_gain", 0.0, 0.0), EPS);
        assertEquals(123.0, eq.control("reverb_send", 4.0, 123.0), EPS);
        assertEquals(2, eq.ramps().size());
    }

    @Test
    public void processorIsActiveOnlyInsideItsPlacement() {
        Processor p = new Processor("reverb", 2.0, 5.0);
        assertFalse(p.activeAt(1.999));
        assertTrue(p.activeAt(2.0));
        assertTrue(p.activeAt(4.999));
        assertFalse(p.activeAt(5.0));
    }
}
