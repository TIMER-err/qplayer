package dev.t1m3.qplayer.audio.mashup;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link TimeMap} 的不变式（设计文档 §6.2 的四条）。
 *
 * <p>这一层是纯算术，所以判据全部写死到<b>精确相等</b>：锚点处差 1 个采样，
 * 3 分钟里就可能被听成"鼓点晚了一点点"。允许误差的地方只有段内插值
 * （那里本来就该是小数）。
 */
public class TimeMapTest {

    @Test
    public void identityIsExactAtEveryAnchor() {
        TimeMap map = TimeMap.identity(96_000);
        assertEquals(2, map.anchors());
        assertEquals(0L, map.outStart());
        assertEquals(96_000L, map.outEnd());
        assertEquals(1.0, map.speed(0), 0.0);
        // 锚点处精确（不变式 3）
        assertEquals(0.0, map.srcAt(0), 0.0);
        assertEquals(96_000.0, map.srcAt(96_000), 0.0);
        // 段内：恒速 1 ⇒ 处处等于自己
        for (long o = 0; o <= 96_000; o += 1_237) {
            assertEquals((double) o, map.srcAt(o), 0.0);
        }
    }

    @Test
    public void constantOffsetKeepsSpeedOne() {
        // A 从第 4000 帧开始对到输出第 0 帧（"把 A 的人声往前挪"）
        TimeMap map = TimeMap.of(new long[]{0, 96_000}, new long[]{4_000, 100_000});
        assertEquals(1.0, map.speed(0), 0.0);
        assertEquals(4_000.0, map.srcAt(0), 0.0);
        assertEquals(5_000.0, map.srcAt(1_000), 0.0);
        assertEquals(100_000.0, map.srcAt(96_000), 0.0);
    }

    @Test
    public void piecewiseLinearInterpolatesInsideSegmentsAndHitsAnchors() {
        // 第一段 0.9 倍速、第二段 1.2 倍速（不合规的速度，这里只验算术）
        TimeMap map = TimeMap.of(new long[]{0, 100, 200}, new long[]{0, 90, 210});
        assertEquals(0.9, map.speed(0), 1e-12);
        assertEquals(1.2, map.speed(1), 1e-12);
        assertEquals(0.9, map.minSpeed(), 1e-12);
        assertEquals(1.2, map.maxSpeed(), 1e-12);

        assertEquals(0.0, map.srcAt(0), 0.0);
        assertEquals(90.0, map.srcAt(100), 0.0);
        assertEquals(210.0, map.srcAt(200), 0.0);

        assertEquals(45.0, map.srcAt(50), 1e-12);
        assertEquals(150.0, map.srcAt(150), 1e-12);

        assertEquals(0.9, map.speedAt(0), 1e-12);
        assertEquals(0.9, map.speedAt(99), 1e-12);
        assertEquals(1.2, map.speedAt(100), 1e-12);
        assertEquals(1.2, map.speedAt(200), 1e-12);
    }

    @Test
    public void extrapolatesWithTheNearestSegmentSpeed() {
        TimeMap map = TimeMap.of(new long[]{100, 200}, new long[]{1_000, 1_100});
        // 起点之前：按首段速度往回外推（速度 1）
        assertEquals(900.0, map.srcAt(0), 0.0);
        // 终点之后：按末段速度往后外推
        assertEquals(1_200.0, map.srcAt(300), 0.0);
        assertEquals(1.0, map.speedAt(-500), 0.0);
        assertEquals(1.0, map.speedAt(10_000), 0.0);
    }

    @Test
    public void reportsTheFirstSegmentOutsideARange() {
        TimeMap map = TimeMap.of(new long[]{0, 100, 200}, new long[]{0, 90, 210});
        assertEquals(0, map.firstSpeedOutside(0.92, 1.08));
        assertEquals(-1, map.firstSpeedOutside(0.5, 1.5));
    }

    @Test
    public void rejectsNonIncreasingAnchors() {
        assertRejects(new long[]{0, 100, 100}, new long[]{0, 10, 20}, "输出帧锚点");
        assertRejects(new long[]{0, 100, 200}, new long[]{0, 20, 20}, "源帧锚点");
        assertRejects(new long[]{0, 100, 50}, new long[]{0, 10, 20}, "输出帧锚点");
    }

    @Test
    public void rejectsTooFewOrMismatchedAnchors() {
        try {
            TimeMap.of(new long[]{0}, new long[]{0});
            fail("只有一个锚点应当被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("两个锚点"));
        }
        try {
            TimeMap.of(new long[]{0, 10}, new long[]{0, 10, 20});
            fail("锚点数不等应当被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("等长"));
        }
        try {
            TimeMap.of(null, new long[]{0, 10});
            fail("空锚点应当被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("不能为空"));
        }
        try {
            TimeMap.identity(0);
            fail("长度为 0 的恒等映射应当被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("≥ 1"));
        }
    }

    @Test
    public void copiesAnchorsSoCallersCannotMutateTheMap() {
        long[] out = {0, 100};
        long[] src = {0, 100};
        TimeMap map = TimeMap.of(out, src);
        out[1] = 999;
        src[1] = 999;
        assertEquals(100L, map.outEnd());
        assertEquals(100L, map.srcEnd());
    }

    private static void assertRejects(long[] out, long[] src, String expectedFragment) {
        try {
            TimeMap.of(out, src);
            fail("应当被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue("异常信息里应当有「" + expectedFragment + "」，实际是："
                            + expected.getMessage(),
                    expected.getMessage().contains(expectedFragment));
        }
    }
}
