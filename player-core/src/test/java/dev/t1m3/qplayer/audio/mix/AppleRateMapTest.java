package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 照搬 goal 第 15 轮：把他的 `ts_rate` 规则钉成契约 ✓（出处见 {@link AppleRateMap} ✓）。 */
public class AppleRateMapTest {

    @Test
    public void equalSourceLengthsMeanUnityMapping() {
        AppleRateMap.Endpoints e = AppleRateMap.endpoints(10.0, 10.0);
        assertEquals(1f, e.ratio, 1e-6f);
        assertEquals(1f, e.outStart, 1e-6f);
        assertEquals(1f, e.outEnd, 1e-6f);
        assertEquals(1f, e.inStart, 1e-6f);
        assertEquals(1f, e.inEnd, 1e-6f);
    }

    @Test
    public void theTwoSidesAreMirrored() {
        // 实测那一对：A 的速率 1.000 -> 0.966 ⇒ ratio ≈ 0.966 ✓（源长度之比同值 ✓）
        AppleRateMap.Endpoints e = AppleRateMap.endpoints(0.966, 1.0);
        assertEquals(0.966f, e.outEnd, 1e-4f);      // A：1 -> ratio ✓
        assertEquals(1f, e.outStart, 1e-6f);
        assertEquals(1f / 0.966f, e.inStart, 1e-4f); // B：1/ratio -> 1 ✓（相向而行 ✓）
        assertEquals(1f, e.inEnd, 1e-6f);
    }

    @Test
    public void missingOrNonPositiveLengthsFallBackToUnity() {
        assertEquals(1f, AppleRateMap.endpoints(0.0, 5.0).ratio, 1e-6f);
        assertEquals(1f, AppleRateMap.endpoints(5.0, -1.0).ratio, 1e-6f);
    }

    @Test
    public void constantRateWalksAtThatRate() {
        assertEquals(2.0, AppleRateMap.elapsed(1.0, 2.0, 2.0, 4.0), 1e-9);
    }

    @Test
    public void rampedRateWalksMonotonicallyAndStartsAtZero() {
        // ⚠️ **我原来在这里断言错了**（照搬 goal 第 15 轮 ✓，如实记下 ✓）：
        // 我原以为"全程积分 = 时长 × 平均速率"（0.9→1.1、4 s ⇒ 4.0 ✓）——**不成立** ✗。
        // 把他的式子按 t = length 展开：slope·length = r1 − r0 ⇒
        //   (exp(ln r0 + r1 − r0) − r0) / slope = r0·(e^(r1−r0) − 1)/slope
        //   = 0.9·(e^0.2 − 1)/0.05 ≈ **3.985** ✗ ≠ 4.0 ✓
        // ⇒ 说明他的**速率不是随时间线性变化**、而是**指数型**的映射 ✓（我的直觉错了 ✗）。
        // 所以判据只钉"确定成立"的性质 ✓：起点为 0 ✓、单调 ✓、常速率时等于 r·t ✓。
        assertEquals(0.0, AppleRateMap.elapsed(0.0, 0.9, 1.1, 4.0), 1e-9);
        assertTrue("走过的源时间必须单调增 ✓",
                AppleRateMap.elapsed(2.0, 0.9, 1.1, 4.0)
                        < AppleRateMap.elapsed(3.0, 0.9, 1.1, 4.0));
        assertTrue("全程走过的源时间必须大于 0 ✓",
                AppleRateMap.elapsed(4.0, 0.9, 1.1, 4.0) > 0.0);
    }
}
