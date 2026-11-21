package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>硬约束的降级不许静默</b>——被放松了哪一条，必须写在说明里。
 *
 * <p>为什么单独立一条判据：这一轮的降级阶梯是「分支式」写的（两个都满足 → 只满足出场点 →
 * 只满足叠唱 → 都不满足），而**分支的排列组合是有缝的**。实测漏掉的那一种是：
 * <b>最终选中的候选满足出场点、但没有任何候选满足叠唱</b>——这时
 * {@code bestGated == null}、{@code bestNearEnd == best}、{@code bestClean == null}，
 * 四个分支一个都不命中，于是 {@code gateNote} 是空的：<b>结论被放松了，日志里却一个字都没有</b>。
 *
 * <p>更糟的一种在真曲目上量到过（{@code windowScale=2.6}，19.4 s 的窗口）：
 * 出场点跳回半路（70.3 s、丢尾 100.6 s），说明里同样什么都没写——用户只看到结论变了，
 * 不知道为什么。这与本项目的纪律相反（「每一种『没有交叠』都有日志和处置」）。
 *
 * <p>判据钉三件事：该说话的时候必须说话（两种组合各一条）、不该说话的时候不许乱说。
 */
public class AutoCueConstraintLadderTest {

    private static final double HOP = 0.02;
    private static final int HOPS_PER_SEC = 50;

    /** 中性素材：完全平（九项对窗口不敏感），可选「全程都在唱」。 */
    private static AutoCue.Envelope flat(double seconds, boolean singing) {
        int hops = (int) Math.round(seconds * HOPS_PER_SEC);
        float[] rms = new float[hops];
        float[] onset = new float[hops];
        float[][] band = new float[hops][];
        Arrays.fill(rms, 0.6f);
        for (int i = 0; i < hops; i++) {
            float mid;
            if (singing) {
                mid = (i / 5) % 2 == 0 ? 0.6f : 0.2f;      // 全程按音节起伏 → 全程判为「在唱」
            } else {
                mid = 0.6f;                                // 平的中频 → 不算人声
            }
            band[i] = new float[] {0.05f, mid, 0.05f};
        }
        return new AutoCue.Envelope(rms, onset, seconds, null, 0, band, null, 0);
    }

    /**
     * ① <b>出场点满足、但没有任何候选满足叠唱</b> → 说明里必须交代「叠唱这条放松了」。
     *
     * <p>这就是四个分支都命不中的那一种（原来 {@code gateNote} 为空）。
     */
    @Test
    public void relaxingTheVocalConstraintMustBeSaidOutLoud() {
        // 注意：叠唱要求**两边同时**在唱，所以两侧都得全程在唱（只让一边唱是构成不了这条的）。
        AutoCue.Cue cue = AutoCue.plan(flat(190.0, true), flat(60.0, true), 5.0);
        assertNotNull("应当能挑出方案", cue);
        System.out.println("[ladder] 叠唱不可能满足时的说明：" + cue.note);
        assertTrue("出场点应当在曲尾附近（这一条是满足的）：" + cue.note,
                cue.outroCutSecs >= 190.0 * 0.85);
        assertTrue("放松了叠唱就必须说明（说明里一个字都没有就是静默降级）：" + cue.note,
                cue.note.contains("硬约束"));
    }

    /** ② 两个约束都能满足时，不许乱报「放松了」。 */
    @Test
    public void aFullyCompliantChoiceMustNotClaimARelaxation() {
        AutoCue.Cue cue = AutoCue.plan(flat(190.0, false), flat(60.0, false), 5.0);
        assertNotNull(cue);
        System.out.println("[ladder] 两边都不唱时的说明：" + cue.note);
        assertTrue("没有放松任何约束时不该提「放松」二字：" + cue.note,
                !cue.note.contains("放松"));
    }

    /**
     * ③ 窗口长到「最后 15% 里放不下」时（真曲目上 {@code windowScale=2.6} 那种），
     * 也要说明为什么最终的点位不在曲尾。
     */
    @Test
    public void anImpossibleTailConstraintIsAlsoExplained() {
        // 60 秒的歌 + 20 秒窗口：留给「最后 15%（9 s）」的余地是负的
        AutoCue.Cue cue = AutoCue.plan(flat(60.0, false), flat(40.0, false),
                new double[] {20.0, 6.0});
        assertNotNull(cue);
        System.out.println("[ladder] 长窗口装不下时的说明：" + cue.note);
        assertTrue("窗口是 20 s、歌只有 60 s，出场点不可能落在最后 15%——必须说明：" + cue.note,
                cue.windowSecs <= 6.0 + 1e-6
                        || (cue.note.contains("硬约束") || cue.note.contains("放松")));
    }
}
