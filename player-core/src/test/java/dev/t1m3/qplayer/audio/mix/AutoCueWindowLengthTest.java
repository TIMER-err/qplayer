package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>窗口长度要有「最优点」，不能是「越短越好」。</b>
 *
 * <p>为什么必须单独一条判据：打分里那九项全都是<b>窗口内的均值</b>，而每个窗口取的是
 * 「96 对点位里的最高分」——窗口越短越容易找到一段局部完美的位置，于是<b>分数单调偏向更短</b>。
 * 实测（P3 那轮，真曲目、标称 15 s）：1.0s→0.851、2.0s→0.847、4.0s→0.834、7.5s→0.803、
 * 15.0s→0.694，<b>单调</b>。后果是两头都不对：
 *
 * <ul>
 *   <li>窗口塌到 1~3 秒 → 那不是融合而是带斜坡的硬切，用户「一换歌就听得出来」；</li>
 *   <li>窗口拖到 15 秒 → 两首歌完整叠在一起，糊。</li>
 * </ul>
 *
 * <p>参考量级来自用户自己的听感基准：Apple Music 在 skydive → 春めく 上用的是
 * <b>6~7 秒</b>，而我们在同一对曲子上曾经选 4.0 s、也曾经塌到 1.0 s。
 *
 * <p>判据钉三件事：① 中性素材上不能选最短的那个；② 选中的窗口要落在最优点附近；
 * ③ 加成曲线的形状（峰值、两端归零、两侧单调）与它声称的一致。
 */
public class AutoCueWindowLengthTest {

    private static final double HOPS_PER_SEC = 50.0;

    /** 完全平的中性素材（九项对窗口长度不敏感，胜负只能由长度判据决定）。 */
    private static AutoCue.Envelope flat(double seconds) {
        int hops = (int) Math.round(seconds * HOPS_PER_SEC);
        float[] rms = new float[hops];
        float[] onset = new float[hops];
        float[][] band = new float[hops][];
        Arrays.fill(rms, 0.6f);
        for (int i = 0; i < hops; i++) {
            band[i] = new float[] {0.5f, 0.8f, 0.5f};
        }
        return new AutoCue.Envelope(rms, onset, seconds, null, 0, band, null, 0);
    }

    /** ① 中性素材 + 常用的比例候选：不许选最短的那个。 */
    @Test
    public void theShortestCandidateIsNotAutomaticallyTheWinner() {
        AutoCue.Cue cue = AutoCue.plan(flat(120.0), flat(60.0),
                new double[] {3.0, 4.5, 6.0, 9.0, 12.0});
        assertNotNull(cue);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[window] 中性素材 + 候选 {3, 4.5, 6, 9, 12} → 选中 %.1f s（最优点 %.1f s）",
                Double.valueOf(cue.windowSecs), Double.valueOf(AutoCue.WINDOW_OPTIMUM_SECS)));
        assertTrue("不该选最短的那一个（选中 " + cue.windowSecs + " s）", cue.windowSecs > 3.0);
        assertTrue("应当落在最优点附近（选中 " + cue.windowSecs + " s）",
                Math.abs(cue.windowSecs - AutoCue.WINDOW_OPTIMUM_SECS) <= 2.0);
    }

    /** ② 只有「极短」与「极长」两个候选时，选离最优点更近的那个（这里是最短的）。 */
    @Test
    public void betweenTwoExtremesTheCloserToOneIsChosen() {
        AutoCue.Cue cue = AutoCue.plan(flat(120.0), flat(60.0), new double[] {20.0, 1.0});
        assertNotNull(cue);
        System.out.println("[window] 候选 {20s, 1s} → 选中 " + cue.windowSecs + " s");
        assertTrue("1 s 比 20 s 更接近最优点，应当选它（实际 " + cue.windowSecs + " s）",
                cue.windowSecs <= 2.0);
    }

    /** ③ 加成曲线的形状与它声称的一致：峰值在最优点、两端归零、两侧单调。 */
    @Test
    public void theBonusCurveIsWhatItSays() {
        double peak = AutoCue.windowLengthBonus(AutoCue.WINDOW_OPTIMUM_SECS);
        assertEquals("峰值就在最优点上", AutoCue.WINDOW_LENGTH_BONUS, peak, 1e-9);
        // 短端**不是** 0，而是很小：曲线是从最优点向两侧线性递减，0.5 s 处还留着约 1/5 峰值。
        // 这一点如实钉住（判据原来想当然写成「两端都归零」，被自己的实现挡了回来）。
        assertTrue("极短窗口的加成必须很小（0 s → " + AutoCue.windowLengthBonus(0.0) + "）",
                AutoCue.windowLengthBonus(0.0) < AutoCue.WINDOW_LENGTH_BONUS / 4.0);
        assertEquals("超出衰减距离之后归零", 0.0, AutoCue.windowLengthBonus(30.0), 1e-9);
        assertEquals("衰减距离上正好归零", 0.0,
                AutoCue.windowLengthBonus(AutoCue.WINDOW_OPTIMUM_SECS
                        + AutoCue.WINDOW_LENGTH_FALLOFF_SECS), 1e-9);
        double prev = -1.0;
        for (double w = 0.5; w <= AutoCue.WINDOW_OPTIMUM_SECS; w += 0.5) {
            double v = AutoCue.windowLengthBonus(w);
            assertTrue("最优点左侧必须单调上升（" + w + " s → " + v + "）", v > prev);
            prev = v;
        }
        // 右侧在衰减距离以内严格下降，之外是平的（0）——所以是「非增」，不是「严格下降」。
        // 判据原来写成严格下降，被自己的实现（超出衰减距离返回 0）挡了回来。
        prev = Double.MAX_VALUE;
        double end = AutoCue.WINDOW_OPTIMUM_SECS + AutoCue.WINDOW_LENGTH_FALLOFF_SECS;
        for (double w = AutoCue.WINDOW_OPTIMUM_SECS; w <= 20.0; w += 0.5) {
            double v = AutoCue.windowLengthBonus(w);
            assertTrue("最优点右侧不许上升（" + w + " s → " + v + "）", v <= prev + 1e-12);
            if (w <= end - 1e-9) {
                assertTrue("衰减距离以内必须严格下降（" + w + " s → " + v + "）", v < prev);
            }
            prev = v;
        }
        System.out.println("[window] 加成曲线：1s→" + String.format("%.3f", AutoCue.windowLengthBonus(1.0))
                + "、3s→" + String.format("%.3f", AutoCue.windowLengthBonus(3.0))
                + "、" + AutoCue.WINDOW_OPTIMUM_SECS + "s→" + String.format("%.3f", peak)
                + "、12s→" + String.format("%.3f", AutoCue.windowLengthBonus(12.0))
                + "、20s→" + String.format("%.3f", AutoCue.windowLengthBonus(20.0)));
    }
}
