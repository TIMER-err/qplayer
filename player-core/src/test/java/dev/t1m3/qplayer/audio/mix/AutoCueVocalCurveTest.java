package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>「人声让多少」必须是连续权重，不是开关</b>（2026-10-03，用户报「有点乱」）。
 *
 * <p>症状与根因：去人声的区间是<b>开关式</b>的（区间内 1、区间外 0、两端各 30 ms 坡），
 * 而代理 VAD 给的叠唱区间是**互不相连的好几段**——实测参考对上就是三段
 * （0.88~1.26 / 2.68~2.76 / 4.74~5.50 秒），于是一次融合里人声被「拿掉、放回」三轮。
 * 用户的原话是「有点乱」：不是糊，是砧板式的拼贴。
 *
 * <p>判据钉的是**连续性**（这是「乱」的机械原因）：
 *
 * <ol>
 *   <li>曲线必须存在（拿不到分频包络才允许为 null）；</li>
 *   <li>逐格变化必须有界——最陡的变化要摊在零点几秒里，而不是一格跳到 1；</li>
 *   <li>形状要对：没人叠唱的地方是 0（一个采样都不动），长叠唱的地方接近 1；</li>
 *   <li>顺带把「起落几次」打印出来（用户可能会想再合并成一段，那是另一个旋钮）。</li>
 * </ol>
 */
public class AutoCueVocalCurveTest {

    private static final double HOP = AutoCue.HOP_SECS;
    private static final int HOPS_PER_SEC = (int) Math.round(1.0 / HOP);

    private static AutoCue.Envelope envelope(double seconds, int[][] singRanges) {
        int hops = (int) Math.round(seconds * HOPS_PER_SEC);
        float[] rms = new float[hops];
        float[] onset = new float[hops];
        float[][] band = new float[hops][];
        for (int i = 0; i < hops; i++) {
            rms[i] = 0.5f;
            float mid = 0.02f;
            for (int[] r : singRanges) {
                if (i >= r[0] && i < r[1]) {
                    mid = (i / 5) % 2 == 0 ? 0.5f : 0.2f;
                }
            }
            band[i] = new float[] {0.05f, mid, 0.05f};
        }
        return new AutoCue.Envelope(rms, onset, seconds, null, 0, band, null, 0);
    }

    private static int[][] sing(double... fromTo) {
        int[][] out = new int[fromTo.length / 2][2];
        for (int i = 0; i + 1 < fromTo.length; i += 2) {
            out[i / 2] = new int[] {(int) Math.round(fromTo[i] * HOPS_PER_SEC),
                    (int) Math.round(fromTo[i + 1] * HOPS_PER_SEC)};
        }
        return out;
    }

    /** 三段互不相连的叠唱（就是实测参考对上那个形状）。 */
    private static final int[][] THREE_BURSTS = sing(0.9, 1.3, 2.7, 2.8, 4.7, 5.5);

    private static float[] curveOf(int[][] ranges, double windowSecs) {
        int hops = (int) Math.round(windowSecs * HOPS_PER_SEC);
        return AutoCue.vocalDuckCurve(envelope(windowSecs, ranges),
                envelope(windowSecs, ranges), 0, 0, hops);
    }

    /** ① 曲线必须存在，而且是连续的：逐格变化不许超过 1/8（0.36 秒的平滑窗量级）。 */
    @Test
    public void theCurveIsContinuousInsteadOfASwitch() {
        float[] curve = curveOf(THREE_BURSTS, 7.5);
        assertNotNull("拿得到分频包络时必须有曲线（null 就退回开关式的区间了）", curve);
        float worst = 0.0f;
        for (int i = 1; i < curve.length; i++) {
            worst = Math.max(worst, Math.abs(curve[i] - curve[i - 1]));
        }
        System.out.println("[duck-curve] 逐格最大变化 " + worst + "（上限 0.125）");
        assertTrue("逐格变化 " + worst + " 太大——那还是开关，不是连续权重", worst <= 0.125f);
    }

    /** ② 形状：没人叠唱的地方必须是 0（一个采样都不动），长叠唱的地方要接近 1。 */
    @Test
    public void theCurveIsZeroWhereNobodyOverlapsAndHighWhereBothSing() {
        float[] curve = curveOf(THREE_BURSTS, 7.5);
        assertNotNull(curve);
        assertTrue("开头没人叠唱 → 0（实际 " + curve[0] + "）", curve[0] <= 0.02f);
        int peak = (int) Math.round(5.1 / HOP);
        // **有意更新（2026-10-09 ✓，功能变更 ✓）**：让位曲线现在乘一个**强度上限**
        // `AutoCue.vocalDuckCap`（默认 0.75 ✓）—— 因为两边整窗都有唱时原曲线全程 = 1.0 ✗，
        // 听感是"A 的人声从第 0 秒被整段拿走"= A 瞬间消失 ✓（用户 2026-10-09 实测日志 ✓）。
        // 上限只压低"让多少" ✓，形状（哪里有叠唱）不变 ✓，所以这里改成贴着上限断言 ✓，容差不变 ✓。
        assertTrue("第三段（4.7~5.5 s）是长叠唱 → 应当到达让位上限 " + AutoCue.vocalDuckCap()
                        + "（实际 " + curve[peak] + "）",
                curve[peak] >= AutoCue.vocalDuckCap() - 0.05f);
        assertTrue("末尾没人叠唱 → 0（实际 " + curve[curve.length - 1] + "）",
                curve[curve.length - 1] <= 0.02f);
    }

    /** ③ 全程都在叠唱 → 全程接近 1；全程都没人叠唱 → 全程 0。 */
    @Test
    public void theCurveTracksHowMuchOfTheWindowOverlaps() {
        float[] all = curveOf(sing(0.0, 7.5), 7.5);
        assertNotNull(all);
        float min = Float.MAX_VALUE;
        for (float v : all) {
            min = Math.min(min, v);
        }
        // 同上：全程叠唱 ⇒ 曲线最低处也应当到达**让位上限** ✓（原来要求 ≥ 0.9 = 上限 1.0 时代 ✓）。
        assertTrue("全程叠唱 → 曲线最低处也该到达让位上限 " + AutoCue.vocalDuckCap()
                        + "（实际 " + min + "）",
                min >= AutoCue.vocalDuckCap() - 0.05f);

        float[] none = curveOf(sing(), 7.5);
        assertNotNull(none);
        float max = 0.0f;
        for (float v : none) {
            max = Math.max(max, v);
        }
        assertTrue("全程没人叠唱 → 曲线必须全 0（实际最大 " + max + "）", max <= 0.02f);
    }

    /** ④ 拿不到分频包络 → null（人声层按「不确定」处理，照旧整窗分离）。 */
    @Test
    public void withoutBandDataThereIsNoCurve() {
        int hops = 100;
        float[] rms = new float[hops];
        Arrays.fill(rms, 0.5f);
        AutoCue.Envelope bare = new AutoCue.Envelope(rms, new float[hops], 2.0, null, 0);
        assertTrue("没有分频包络时应当是 null（= 不确定 → 整窗去人声）",
                AutoCue.vocalDuckCurve(bare, bare, 0, 0, hops) == null);
    }
}
