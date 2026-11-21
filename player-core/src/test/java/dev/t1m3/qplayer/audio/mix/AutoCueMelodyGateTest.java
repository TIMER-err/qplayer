package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * <b>三道闸</b>（2026-10-06 第一阶段工作六）。
 *
 * <p>用户原话：「放开限制后，在少数极端情况下过渡会变得很长、变速会很猛、切点会很早。
 * 为了只在旋律匹配足够高时才全放开，保留三道闸：旋律匹配大于 0.9，全放开；
 * 0.7 到 0.9，半放开（原限制和全放开限制的**线性插值**，系数 = (旋律 − 0.7)/0.2）；
 * 小于 0.7，保持原限制。」
 *
 * <p>这条判据把三个区间与两个端点都钉死——尤其是"0.8 恰好放开一半"这个中点，
 * 它是"连续信任度"与"开关"的分水岭。
 */
public class AutoCueMelodyGateTest {

    /** 拿一对好认的数：原限制 0.5、放开后 0.7。 */
    private static final double ORIGINAL = 0.5;
    private static final double OPENED = 0.7;

    @Test
    public void belowTheLowGateKeepsTheOriginalLimit() {
        assertEquals("0.0 保持原限制", ORIGINAL,
                AutoCue.gatedLimit(ORIGINAL, OPENED, 0.0), 1e-12);
        assertEquals("0.69 保持原限制", ORIGINAL,
                AutoCue.gatedLimit(ORIGINAL, OPENED, 0.69), 1e-12);
        assertEquals("刚好 0.7 = 原限制（插值系数 0）", ORIGINAL,
                AutoCue.gatedLimit(ORIGINAL, OPENED, AutoCue.GATE_MELODY_LOW), 1e-12);
    }

    @Test
    public void atOrAboveTheHighGateOpensEverything() {
        assertEquals("刚好 0.9 = 全放开", OPENED,
                AutoCue.gatedLimit(ORIGINAL, OPENED, AutoCue.GATE_MELODY_HIGH), 1e-12);
        assertEquals("1.0 全放开", OPENED,
                AutoCue.gatedLimit(ORIGINAL, OPENED, 1.0), 1e-12);
    }

    @Test
    public void inBetweenIsLinear() {
        assertEquals("0.8（正中）恰好放开一半", (ORIGINAL + OPENED) / 2.0,
                AutoCue.gatedLimit(ORIGINAL, OPENED, 0.8), 1e-12);
        assertEquals("0.75 放开 1/4", ORIGINAL + (OPENED - ORIGINAL) * 0.25,
                AutoCue.gatedLimit(ORIGINAL, OPENED, 0.75), 1e-12);
        assertEquals("0.85 放开 3/4", ORIGINAL + (OPENED - ORIGINAL) * 0.75,
                AutoCue.gatedLimit(ORIGINAL, OPENED, 0.85), 1e-12);
        // 单调：相似度越高，放开得越多（不许有台阶或回头）
        double previous = Double.NEGATIVE_INFINITY;
        for (double m = AutoCue.GATE_MELODY_LOW; m <= AutoCue.GATE_MELODY_HIGH; m += 0.01) {
            double v = AutoCue.gatedLimit(ORIGINAL, OPENED, m);
            assertEquals("必须单调不减（m=" + m + "）", Math.max(previous, v), v, 1e-12);
            previous = v;
        }
    }

    @Test
    public void unmeasuredMelodyKeepsTheOriginalLimit() {
        assertEquals("量不到（NaN）时按原限制——不假装相似", ORIGINAL,
                AutoCue.gatedLimit(ORIGINAL, OPENED, Double.NaN), 1e-12);
    }
}
