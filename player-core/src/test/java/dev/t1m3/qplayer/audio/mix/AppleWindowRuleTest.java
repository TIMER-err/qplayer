package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 照搬 goal：把他 {@code alignment.py} 的三条公式钉成契约 ✓（值是我按原文手算的 ✓）。 */
public class AppleWindowRuleTest {

    @Test
    public void equalLengthsAndBarsGiveUnity() {
        AppleWindowRule.Result r = AppleWindowRule.compute(10, 10, 8, 8, 1, 100, 110);
        assertEquals(1.0, r.m, 1e-9);
        assertEquals(10.0, r.scaledIncomingLen, 1e-9);
        assertEquals(1.0, r.outgoingEndRate, 1e-9);
        assertEquals(1.0, r.incomingStartRate, 1e-9);   // 起点 = 斜坡起点 ⇒ Li'/Lo = 1 ✓
    }

    @Test
    public void shorterIncomingMakesTheOutgoingSpeedUpAndTheIncomingStartBelowOne() {
        // Lo=10, Li=8, bars 相同, alias=1 ⇒ M=1 ⇒ Li'=8 ⇒ A 终点 = 10/8 = 1.25 ✓
        AppleWindowRule.Result r = AppleWindowRule.compute(10, 8, 8, 8, 1, 100, 108);
        assertEquals(1.0, r.m, 1e-9);
        assertEquals(8.0, r.scaledIncomingLen, 1e-9);
        assertEquals(1.25, r.outgoingEndRate, 1e-9);
        // 斜坡 = [108-8, 108] = [100,108] ✓ ⇒ 起点 100 落在**斜坡起点** ⇒ Li'/Lo = 0.8 ✓
        assertEquals(0.8, r.incomingStartRate, 1e-9);
        assertEquals(100.0, r.virtualRampStart, 1e-9);
    }

    @Test
    public void theIncomingStartIsSampledInsideTheVirtualRamp() {
        // 起点落在斜坡**中点**（104 ✓）⇒ 0.8 + 0.5 × 0.2 = 0.9 ✓（这正是"不总是倒数"的来源 ✓）
        AppleWindowRule.Result r = AppleWindowRule.compute(10, 8, 8, 8, 1, 104, 108);
        assertEquals(0.9, r.incomingStartRate, 1e-9);
        // 起点在斜坡终点之后 ⇒ 1.0 ✓
        AppleWindowRule.Result r2 = AppleWindowRule.compute(10, 8, 8, 8, 1, 109, 108);
        assertEquals(1.0, r2.incomingStartRate, 1e-9);
    }

    @Test
    public void aliasTwoDoublesTheScale() {
        AppleWindowRule.Result r = AppleWindowRule.compute(10, 8, 8, 8, 2, 100, 108);
        assertEquals(2.0, r.m, 1e-9);              // alias=2 ✓
        assertEquals(4.0, r.scaledIncomingLen, 1e-9);
        assertEquals(2.5, r.outgoingEndRate, 1e-9); // 10 / 4 ✓
    }

    @Test
    public void zeroLengthsFallBackToUnity() {
        AppleWindowRule.Result r = AppleWindowRule.compute(0, 0, 8, 8, 1, 0, 0);
        assertEquals(1.0, r.outgoingEndRate, 1e-9);
        assertEquals(1.0, r.incomingStartRate, 1e-9);
    }

    @Test
    public void theWindowIsAlignedToWholeBarsOnTheOutgoingSide() {
        // 参考对实测：A 128 BPM ⇒ 小节 1.875 s；B 120 BPM ⇒ 2.000 s ✓
        assertEquals(1.875, AppleWindowRule.barSecs(128.0), 1e-9);
        assertEquals(2.0, AppleWindowRule.barSecs(120.0), 1e-9);
        // 各 4 小节 ⇒ 取小者 7.5 s ✓，对齐到 A 的整小节 = 4 × 1.875 = 7.5 s ✓
        assertEquals(7.5, AppleWindowRule.barAlignedWindow(2.51, 128.0, 120.0, 4), 1e-9);
        // 各 8 小节 ⇒ 15.0 s ✓
        assertEquals(15.0, AppleWindowRule.barAlignedWindow(2.51, 128.0, 120.0, 8), 1e-9);
        // 关掉（targetBars = 0）⇒ 原样返回 ✓（可回退 ✓）
        assertEquals(2.51, AppleWindowRule.barAlignedWindow(2.51, 128.0, 120.0, 0), 1e-9);
        // BPM 取不到 ⇒ 不动 ✓
        assertEquals(2.51, AppleWindowRule.barAlignedWindow(2.51, 0.0, 120.0, 4), 1e-9);
        // 两侧小节数 ✓
        assertEquals(4, AppleWindowRule.barsFor(7.5, 128.0));
        assertEquals(4, AppleWindowRule.barsFor(7.5, 120.0));
    }
}
