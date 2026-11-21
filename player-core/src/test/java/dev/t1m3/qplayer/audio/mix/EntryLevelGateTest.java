package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * **进场点电平闸门的两把尺子**（2026-10-09 ✓ 用户「自主测试自主修复」✓）。
 *
 * <p>桌面实测（两条口径 ✓）：B 的引子**平均够响但疏密不均**（逐块 −7…−28 dB，供料满格 4096 ✓）
 * ⇒ 只听平均会放行 ⇒ 融合期"响度一会大一会小" ✓。所以闸门现在要两把尺子：
 * ① 平均 ≥ A − 6 dB ✓；② **每 0.5 s 小块 ≥ A − 12 dB** ✓。
 */
public class EntryLevelGateTest {
    /** 包络栅格 = 20 ms ⇒ 每秒 50 格 ✓。 */
    private static final int HOP_PER_SEC = 50;

    private static float[] sparse() {
        // 前 3 秒：每 0.5 s 一小块，响/轻交替（模拟 B 的稀疏引子 ✓）
        float[] r = new float[HOP_PER_SEC * 6];
        for (int i = 0; i < r.length; i++) {
            boolean loud = ((i / 25) % 2) == 0;      // 25 格 = 0.5 s ✓
            r[i] = loud ? 0.30f : 0.02f;             // 响块 −10 dB · 轻块 −34 dB ✓
        }
        return r;
    }

    private static float[] steady() {
        float[] r = new float[HOP_PER_SEC * 6];
        java.util.Arrays.fill(r, 0.30f);
        return r;
    }

    @Test
    public void averagingAloneWouldPassTheSparseIntro() {
        double avg = AutoCueTransition.windowRmsOf(sparse(), 0.0, 4.0);
        assertTrue("稀疏引子的**平均**能量应当够响（这正是它骗过第一版闸门的原因 ✓）",
                avg > 0.15f);
    }

    @Test
    public void theContinuityRulerRejectsAQuietTail() {
        // 实测教训（2026-10-09 ✓）：整窗最小值**两处候选都被否**（9.6s 与 72s 各有一块 −24/−26 dB ✗）
        // ⇒ 闸门改成只看**最后 2.5 s**（那一刀发生在结尾 ✓）。这里就测"尾部安静 ⇒ 被否" ✓。
        float[] tailQuiet = new float[HOP_PER_SEC * 6];
        for (int i = 0; i < tailQuiet.length; i++) {
            tailQuiet[i] = i < tailQuiet.length - HOP_PER_SEC * 2 ? 0.30f : 0.02f;   // 最后 2 s 很轻 ✓
        }
        double worstTail = AutoCueTransition.windowMinBlockRms(tailQuiet, 3.5, 2.5);
        assertTrue("尾部很轻 ⇒ 连续性尺子必须报出来（< A−12 dB 的量级 ✓）", worstTail < 0.05f);
        double steadyTail = AutoCueTransition.windowMinBlockRms(steady(), 3.5, 2.5);
        assertTrue("尾部稳定 ⇒ 不被误杀 ✓", steadyTail > 0.25f);
    }

    @Test
    public void aSteadySectionPassesBothRulers() {
        double avg = AutoCueTransition.windowRmsOf(steady(), 0.0, 4.0);
        double worst = AutoCueTransition.windowMinBlockRms(steady(), 0.0, 4.0);
        assertTrue("稳定段落：平均够响 ✓", avg > 0.25f);
        assertTrue("稳定段落：最轻的小块也够响 ✓（不会被误杀 ✓）", worst > 0.25f);
    }
}
