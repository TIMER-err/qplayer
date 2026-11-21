package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>小节相位</b>（4/4 启发式，无模型）。
 *
 * <p>拍栅格只回答「哪里是拍」，而接歌讲的是<strong>小节</strong>：在「第 1 拍」上接、
 * 在 8/16 小节线上接，听感与「随便挑一拍」完全不同。上一轮把「窗口压进 6–8 秒」判成
 * 不可达时，缺的正是这一层（纯频谱里 174 s 处的新颖度是整段最低的，它不是谱边界而是
 * 小节/乐句地标）。
 *
 * <p>测法不需要模型：<b>底鼓在小节里的分布不均匀</b>——4/4 的流行/电子里第 1 拍
 * 落鼓的概率明显更高。于是对 4 个相位各算一次「落在该相位上的拍，低频起音的平均强度」，
 * 取最大者；对比度低于 {@code BAR_CONFIDENCE} 就如实返回「没测出来」。
 */
public class AutoCueBarTest {

    private static final int RATE = 48_000;
    private static final double BPM = 120.0;                     // 拍长 0.5 s

    /** 底鼓只在第 1 拍：相位必须测成 0。 */
    @Test
    public void kicksOnTheFirstBeatGivePhaseZero() {
        AutoCue.Envelope e = clickTrack(new int[] {0});
        assertEquals("底鼓落在第 1 拍（序号 0 mod 4）上，相位应当是 0", 0, AutoCue.barPhase(e));
    }

    /** 底鼓只在第 3 拍：相位必须测成 2（不是 0）。 */
    @Test
    public void kicksOnTheThirdBeatGivePhaseTwo() {
        AutoCue.Envelope e = clickTrack(new int[] {2});
        assertEquals("底鼓落在第 3 拍（序号 2 mod 4）上，相位应当是 2", 2, AutoCue.barPhase(e));
    }

    /** 每拍一样响（四踩）：测不出相位 → 如实返回 -1，不猜。 */
    @Test
    public void evenKicksAreReportedAsUnknown() {
        AutoCue.Envelope e = clickTrack(new int[] {0, 1, 2, 3});
        assertEquals("四踩看不出小节相位，必须如实说「没测出来」", -1, AutoCue.barPhase(e));
    }

    /** 没有拍栅格 → 相位未知，小节线加分一分不给。 */
    @Test
    public void withoutABeatGridThereIsNoBarBonus() {
        AutoCue.Envelope bare = new AutoCue.Envelope(new float[100], new float[100], 20.0);
        assertEquals("没有拍栅格就不该有相位", -1, AutoCue.barPhase(bare));
        assertEquals("相位未知时加分必须是 0", 0.0, AutoCue.barTerm(bare, 5.0), 1e-9);
        assertEquals("两侧都测不出 → 这一项整项不加", null,
                AutoCue.barBonus(bare, bare, 5.0, 5.0));
    }

    /** 小节线判定：第 1 拍上给 1，第 2/3/4 拍上给 0。 */
    @Test
    public void onlyTheFirstBeatOfTheBarCountsAsABarLine() {
        AutoCue.Envelope e = clickTrack(new int[] {0});
        assertEquals("第 1 拍 = 小节线", 1.0, AutoCue.barTerm(e, 4.0), 1e-9);
        assertEquals("第 2 拍不是小节线", 0.0, AutoCue.barTerm(e, 4.5), 1e-9);
        assertEquals("第 3 拍不是小节线", 0.0, AutoCue.barTerm(e, 5.0), 1e-9);
        assertEquals("第 4 拍不是小节线", 0.0, AutoCue.barTerm(e, 5.5), 1e-9);
        assertEquals("下一小节第 1 拍又是", 1.0, AutoCue.barTerm(e, 6.0), 1e-9);
    }

    /** 端到端：其余全同，只有「落在小节线上」不同 → 选点必须选小节线那个。 */
    @Test
    public void planningPrefersTheCandidateOnABarLine() {
        AutoCue.Envelope outro = flat(60.0, 0.7f, 0.8f, clickBands(60.0, new int[] {0}));
        AutoCue.Envelope intro = flat(80.0, 0.7f, 0.4f, clickBands(80.0, new int[] {0}));
        AutoCue.Cue cue = AutoCue.plan(outro, intro, 5.0);
        assertNotNull("没有找到任何候选", cue);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[bar] 出场 %.2f s（%.2f 拍）· 进场 %.2f s（%.2f 拍）",
                Double.valueOf(cue.outroCutSecs), Double.valueOf(cue.outroCutSecs / 0.5),
                Double.valueOf(cue.introEntrySecs), Double.valueOf(cue.introEntrySecs / 0.5)));
        assertTrue("进场点应当落在小节线上（拍序 mod 4 == 0，即整秒上），实际 "
                        + cue.introEntrySecs,
                Math.abs(cue.introEntrySecs - Math.round(cue.introEntrySecs)) < 0.03);
    }

    /** 结构项/歌词项那一套加分的机制没被破坏：两项都算上时仍然只往上加。 */
    @Test
    public void theBarBonusIsCappedAndAdditive() {
        AutoCue.Score base = AutoCue.evaluate(flat(60.0, 0.7f, 0.8f, null),
                flat(80.0, 0.7f, 0.4f, null), 10.0, 10.0, 5.0);
        AutoCue.Score withBar = base.withBar(Double.valueOf(1.0));
        assertTrue("加上小节线奖励之后总分必须不降",
                withBar.total >= base.total - 1e-12);
        assertTrue("单项加分必须有上限（" + AutoCue.BAR_WEIGHT + "）",
                withBar.total - base.total <= AutoCue.BAR_WEIGHT + 1e-9);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 一段 30 秒的点击轨：{@code phases} 里列出的拍序上落「鼓」，其余拍几乎没有能量。 */
    private static AutoCue.Envelope clickTrack(int[] phases) {
        return envelope(30.0, 0.7f, 0.4f, clickBands(30.0, phases));
    }

    private static float[][] clickBands(double secs, int[] phases) {
        int hops = (int) Math.round(secs / AutoCue.HOP_SECS);
        double periodSecs = 60.0 / BPM;
        int periodHops = (int) Math.round(periodSecs / AutoCue.HOP_SECS);
        float[][] band = new float[hops][];
        for (int i = 0; i < hops; i++) {
            int beat = (int) Math.round(i / (double) periodHops);
            boolean hit = false;
            for (int p = 0; p < phases.length; p++) {
                if (Math.floorMod(beat, 4) == phases[p]) {
                    hit = true;
                    break;
                }
            }
            // 「鼓」= 低频那一条带上的一记起音；其余拍留一点点底噪
            float low = hit ? 0.9f : 0.05f;
            band[i] = new float[] {low, 0.4f, 0.4f};
        }
        return band;
    }

    private static AutoCue.Envelope flat(double secs, float rest, float low, float[][] band) {
        return envelope(secs, rest, low, band);
    }

    private static AutoCue.Envelope envelope(double secs, float rest, float low, float[][] band) {
        int hops = (int) Math.round(secs / AutoCue.HOP_SECS);
        float[] rms = new float[hops];
        float[] onset = new float[hops];
        float[][] bands = band;
        Arrays.fill(rms, rest);
        if (bands == null) {
            bands = new float[hops][];
            for (int i = 0; i < hops; i++) {
                bands[i] = new float[] {low, rest, rest};
            }
        } else {
            // 包络的 onset 要跟着低频走，相位判定读的就是它（真实扫描里也是这个关系）
            for (int i = 1; i < hops; i++) {
                onset[i] = Math.max(0f, bands[i][0] - bands[i - 1][0]);
            }
        }
        return new AutoCue.Envelope(rms, onset, secs, beatGrid(), AutoCue.TARGET_ONSET_RATE,
                bands, null, 0);
    }

    /** 120 BPM 的合成拍栅格（置信度拉满，好让 {@code hasBeatGrid()} 成立）。 */
    private static Analysis.Tempo beatGrid() {
        double periodFrames = 60.0 / BPM * AutoCue.TARGET_ONSET_RATE;
        return new Analysis.Tempo((float) BPM, (float) periodFrames, 0.0f, 0.95f);
    }
}
