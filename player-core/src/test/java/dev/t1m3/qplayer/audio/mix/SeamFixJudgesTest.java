package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.*;
import org.junit.Test;

/** ②③a 的判据（2026-10-11 ✓ 根因修复的可测部分 ✓）。 */
public class SeamFixJudgesTest {

    // ② 素材末尾尾淡：不能阶跃 ✗
    @Test public void theStemWeightKeepsFullValueAwayFromTheEnd() {
        assertEquals(1.0, VocalSeparationTransition.tailFadeWeight(0, 44100, 44100), 1e-9);
        assertEquals(1.0, VocalSeparationTransition.tailFadeWeight(35000, 44100, 44100), 1e-9);
    }

    @Test public void theStemWeightFadesLinearlyOverTheLastTwoHundredMillis() {
        final int rate = 44100, tail = Math.round(0.2f * rate);      // 8820 帧
        final int covered = 44100;
        double prev = 1.0;
        for (int idx = covered - tail; idx <= covered; idx++) {
            double w = VocalSeparationTransition.tailFadeWeight(idx, covered, rate);
            assertTrue("权重必须单调不增（idx=" + idx + "）", w <= prev + 1e-12);
            assertTrue("相邻帧差不得超过每帧步长（idx=" + idx + "）",
                    Math.abs(w - prev) <= 1.0 / tail + 1e-12);
            prev = w;
        }
        assertEquals("素材尽头必须是 0（不再硬切 ✓）", 0.0,
                VocalSeparationTransition.tailFadeWeight(covered, covered, rate), 1e-9);
    }

    // ③b 拍速差过大 ⇒ 降级
    @Test public void aTempoGapOfTwentyOnePercentIsDowngraded() {
        assertTrue("124 vs 150.6 实测差 21% ⇒ 必须降级",
                AutoCue.shouldDowngradeForTempo(124.0, 150.6, 0.06));
        assertFalse("124 vs 126（1.6%）⇒ 不降级",
                AutoCue.shouldDowngradeForTempo(124.0, 126.0, 0.06));
        assertFalse("拍速未知 ⇒ 不降级（不误杀）",
                AutoCue.shouldDowngradeForTempo(0, 150.6, 0.06));
        assertFalse("两边都未知 ⇒ 不降级", AutoCue.shouldDowngradeForTempo(0, 0, 0.06));
    }

    // ③a 窗口下限：2 s，但不越过用户上界
    @Test public void theWindowFloorIsTwoSecondsUnlessTheUserAskedForLess() {
        assertEquals(2.0, AutoCueTransition.windowFloorSecs(6.0), 1e-9);
        assertEquals(2.0, AutoCueTransition.windowFloorSecs(2.0), 1e-9);
        assertEquals(1.0, AutoCueTransition.windowFloorSecs(1.0), 1e-9);
        assertEquals(0.0, AutoCueTransition.windowFloorSecs(0.0), 1e-9);
    }
}
