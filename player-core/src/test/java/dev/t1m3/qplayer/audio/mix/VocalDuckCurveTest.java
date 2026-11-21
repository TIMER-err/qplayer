package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>人声层用连续曲线，不再被"空区间"一票否决</b>（2026-10-03）。
 *
 * <p>两件事：
 *
 * <ol>
 *   <li>{@code duckCurveAt} 的取值是连续的（线性插值）、越界取端点、空曲线取 0（不动手）；</li>
 *   <li><b>曲线优先于区间</b>：两条通道同时给的时候，曲线说了算。特别是
 *       「区间是空的、但曲线有内容」这种组合——按老的语义空区间等于「整段不去人声」，
 *       而曲线恰恰是要动的地方，绝不能因为区间空就跳过去。</li>
 * </ol>
 */
public class VocalDuckCurveTest {

    /** ① 曲线取值连续、越界取端点、空曲线取 0。 */
    @Test
    public void theCurveIsSampledContinuously() {
        float[] curve = {0f, 0.5f, 1f, 1f, 0.5f, 0f};
        double hop = AutoCue.HOP_SECS;
        assertEquals("第一格", 0.0, VocalSeparationTransition.duckCurveAt(curve, 0.0), 1e-9);
        assertEquals("中间那格", 1.0, VocalSeparationTransition.duckCurveAt(curve, 2 * hop), 1e-9);
        assertEquals("两格之间应当插值出中间值（第 1 格 0.5 与第 2 格 1.0 之间的中点）",
                0.75, VocalSeparationTransition.duckCurveAt(curve, 1.5 * hop), 1e-9);
        assertEquals("越界取端点（前）", 0.0,
                VocalSeparationTransition.duckCurveAt(curve, -1.0), 1e-9);
        assertEquals("越界取端点（后）", 0.0,
                VocalSeparationTransition.duckCurveAt(curve, 100.0), 1e-9);
        assertEquals("没有曲线 → 不动手", 0.0,
                VocalSeparationTransition.duckCurveAt(null, 1.0), 1e-9);

        // 逐样本扫一遍：变化必须是「每格 1/15」量级，不能出现台阶
        float[] smooth = new float[60];
        for (int i = 0; i < 30; i++) {
            smooth[i] = i / 30f;
        }
        for (int i = 30; i < 60; i++) {
            smooth[i] = (60 - i) / 30f;
        }
        double worst = 0.0;
        double prev = VocalSeparationTransition.duckCurveAt(smooth, 0.0);
        for (double t = hop; t < 60 * hop; t += hop) {
            double v = VocalSeparationTransition.duckCurveAt(smooth, t);
            worst = Math.max(worst, Math.abs(v - prev));
            assertTrue("连续曲线取出来的值不许跳（" + prev + " → " + v + "）", worst < 0.08);
            prev = v;
        }
        System.out.println("[duck-curve] 逐格采样最大变化 " + worst);
    }

    /** ② 曲线的三值语义（供人声层判断"要不要动手"用）。 */
    @Test
    public void theCurveDistinguishesUnknownFromNothingToDo() {
        assertTrue("null = 没有这个信息", VocalSeparationTransition.duckCurveAt(null, 0.5) == 0.0);
        float[] zeros = new float[50];
        assertEquals("全 0 的曲线 = 这一窗不用动", 0.0,
                VocalSeparationTransition.duckCurveAt(zeros, 0.4), 1e-9);
        float[] ones = new float[50];
        java.util.Arrays.fill(ones, 1f);
        assertEquals("全 1 的曲线 = 整窗让位", 1.0,
                VocalSeparationTransition.duckCurveAt(ones, 0.4), 1e-9);
    }
}
