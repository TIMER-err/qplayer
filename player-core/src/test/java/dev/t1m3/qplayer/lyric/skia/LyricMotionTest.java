package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricTimeline;
import dev.t1m3.qplayer.lyric.Syllable;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LyricMotionTest {

    private static LyricTimeline.Group group(long startMs, long endMs) {
        LyricLine line = new LyricLine();
        line.syllables.add(new Syllable("line", startMs, endMs - startMs));
        return LyricTimeline.prepare(Collections.singletonList(line), false).groups.get(0);
    }

    @Test
    public void activeCurveFadesInHoldsAndFadesOut() {
        LyricTimeline.Group group = group(1_000L, 3_000L);

        assertEquals(0f, LyricMotion.active(500L, group), 0f);
        assertTrue(LyricMotion.active(800L, group) > 0f);
        assertEquals(1f, LyricMotion.active(1_200L, group), 0f);
        assertEquals(1f, LyricMotion.active(3_050L, group), 0f);
        assertTrue(LyricMotion.active(3_250L, group) < 1f);
        assertEquals(0f, LyricMotion.active(3_450L, group), 0f);
    }

    @Test
    public void backgroundScaleUsesDelayedPopAndReturnsToZero() {
        LyricTimeline.Group group = group(1_000L, 3_000L);

        assertEquals(0f, LyricMotion.backgroundScale(1_100L, group), 0f);
        assertTrue(LyricMotion.backgroundScale(1_300L, group) > 0f);
        assertEquals(1f, LyricMotion.backgroundScale(2_000L, group), 0f);
        assertEquals(0f, LyricMotion.backgroundScale(3_280L, group), 0f);
    }

    /**
     * 逐字放大的两个公式（照搬 Melodify emphasizeAmount / emphasizeK）：
     * 幅度按时长取幂曲线、末词 ×1.6、上限 1.2；每字脉冲按 AMLL 错开、从左往右扫。
     */
    @Test
    public void emphasisSwellMatchesMelodifysPerLetterPulse() {
        // du=2000 → amount=1 → ^3=1 → ×0.6 = 0.6；末词 ×1.6 = 0.96
        assertEquals(0.6f, LyricMotion.emphasisAmount(2000L, 0, 3), 0.001f);
        assertEquals(0.96f, LyricMotion.emphasisAmount(2000L, 2, 3), 0.001f);
        // du 很大时走 sqrt 分支并被 1.2 夹住：du=8000 → 4 → sqrt=2 → ×0.6=1.2
        assertEquals(1.2f, LyricMotion.emphasisAmount(8000L, 0, 3), 0.001f);
        // 时长下限 1000ms：du=100 被抬到 1000 → 0.5³=0.125 → ×0.6 = 0.075
        assertEquals(0.075f, LyricMotion.emphasisAmount(100L, 0, 3), 0.0001f);
        assertEquals("下限与 2000ms 等价", LyricMotion.emphasisAmount(1000L, 0, 3),
                LyricMotion.emphasisAmount(100L, 0, 3), 0.0001f);

        // 脉冲：起点前为 0，词中点附近达到峰值，词末回落到 0
        assertEquals(0f, LyricMotion.emphasisSwell(999L, 1000L, 2000L, 0, 4), 0.001f);
        assertTrue("词中应当有鼓包", LyricMotion.emphasisSwell(2000L, 1000L, 2000L, 0, 4) > 0.5f);
        assertEquals(0f, LyricMotion.emphasisSwell(3001L, 1000L, 2000L, 0, 4), 0.001f);
        // 错开：同一时刻，靠后的字比靠前的字更晚起来
        assertTrue(LyricMotion.emphasisSwell(1400L, 1000L, 2000L, 0, 4)
                > LyricMotion.emphasisSwell(1400L, 1000L, 2000L, 3, 4));
    }

    @Test
    public void interludeSlotRampsAtBothEdges() {
        assertEquals(0f, LyricMotion.interludeSlot(999L, 1_000L, 2_000L, 40f), 0f);
        assertEquals(0f, LyricMotion.interludeSlot(1_000L, 1_000L, 2_000L, 40f), 0f);
        assertEquals(40f, LyricMotion.interludeSlot(1_500L, 1_000L, 2_000L, 40f), 0f);
        assertTrue(LyricMotion.interludeSlot(1_950L, 1_000L, 2_000L, 40f) < 40f);
        assertEquals(0f, LyricMotion.interludeSlot(2_001L, 1_000L, 2_000L, 40f), 0f);
    }
}
