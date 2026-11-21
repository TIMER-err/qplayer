package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>帧级边界规则</b>（2026-10-07）。规则出处：参照里那条输入契约 ——
 * {@code source intervals half-open floor sample boundaries with <=one-sample map clamp}。
 *
 * <p>这条判据把"帧级"变成三个可证伪的断言：半开区间不重叠 ✓、边界一律向下取整 ✓、
 * 映射误差 ≤ 1 个样本 ✓。
 */
public class SampleBoundaryTest {

    private static final int RATE = 48000;

    @Test
    public void boundariesAreFlooredAndIntervalsDoNotOverlap() {
        // 边界落在两个样本之间时，必须**向下**取整（round 会忽左忽右）
        assertEquals("落在样本之间时向下取整", 4799L,
                SampleBoundary.floorFrame(4799.999 / (double) RATE, RATE));
        long[] first = SampleBoundary.halfOpen(1.0, 2.0, RATE);
        assertEquals("左端 floor", 48000L, first[0]);
        assertEquals("右端 floor", 96000L, first[1]);
        long[] second = SampleBoundary.halfOpen(2.0, 3.0, RATE);
        assertEquals("半开区间：后一段的左端 == 前一段的右端（不重叠、不留缝）", first[1], second[0]);
    }

    @Test
    public void degenerateIntervalsCollapseInsteadOfInverting() {
        long[] empty = SampleBoundary.halfOpen(5.0, 4.0, RATE);
        assertEquals("给反了的区间应当退化成空区间，而不是反向", empty[0], empty[1]);
    }

    @Test
    public void mappingStaysWithinOneSampleWhenTheRangeAllowsIt() {
        double ideal = 12345.4;
        long mapped = SampleBoundary.clampTo(ideal, 0L, 1_000_000L);
        assertEquals("区间够宽时按四舍五入", 12345L, mapped);
        assertTrue("映射误差必须 ≤ 1 个样本（这就是「帧级」的可证形式）",
                SampleBoundary.mapError(ideal, mapped) <= SampleBoundary.MAX_MAP_ERROR);
    }

    @Test
    public void clampingNeverPretendsToBeAligned() {
        // 区间太窄、容不下理想落点时：如实夹到边界，并让误差**如实**变大（不假装对齐）
        long mapped = SampleBoundary.clampTo(100.0, 90L, 92L);
        assertEquals("夹到上界", 92L, mapped);
        assertTrue("这时误差就该大于 1 —— 判据量的是事实，不是愿望",
                SampleBoundary.mapError(100.0, mapped) > SampleBoundary.MAX_MAP_ERROR);
    }
}
