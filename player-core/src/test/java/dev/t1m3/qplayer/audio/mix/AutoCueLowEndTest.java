package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>第八项：低频冲突</b>（1 = 从不打架，0 = 全程一起轰）。
 *
 * <p>为什么要它：{@code bandComplement} 把三个频段平均在一起，低频只占三分之一——
 * 两个底鼓从头对到尾时，中高频随便互补一下就能把平均分拉起来。而「低频同时全开」是
 * 接缝最糊的一种，所以这一项单独盯 {@code band[0]}（&lt; 200 Hz，装着底鼓与贝斯），
 * 并且额外看一件包络看不见的事：<b>两侧低频反相</b>（互相抵消 → 低频一鼓一鼓地塌）。
 *
 * <p>判据全是「构造两种极端，问哪一边分高」——没有一处靠听感。
 */
public class AutoCueLowEndTest {

    /** 窗口的 hop 数：500 × 20 ms = 10 秒。 */
    private static final int HOPS = 500;

    // ------------------------------------------------------------------
    // 成分一：低频重叠（两个底鼓一起轰 = 糊）
    // ------------------------------------------------------------------

    /** 两边低频全程都在轰 → 必须给低分；只有一边在轰 → 不罚。 */
    @Test
    public void twoHotLowsClashAndAQuietLowDoesNot() {
        double clash = AutoCue.lowEndClash(env(0.8f, 0.9f), env(0.8f, 0.9f), 0, 0, HOPS);
        double fine = AutoCue.lowEndClash(env(0.8f, 0.9f), env(0.8f, 0.05f), 0, 0, HOPS);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[low-end] 两边都轰 %.3f · 一边安静 %.3f",
                Double.valueOf(clash), Double.valueOf(fine)));
        assertTrue("两边低频全程都在轰，这一项必须给低分（糊），实际 " + clash, clash < 0.3);
        assertTrue("另一边低频安静时不该罚，实际 " + fine, fine > 0.9);
    }

    /** 两边低频都静音 → 谈不上打架，给满分（不是 0）。 */
    @Test
    public void silenceInBothLowsIsNotAConflict() {
        assertEquals("都没低频就不算冲突", 1.0,
                AutoCue.lowEndClash(env(0.5f, 0.0f), env(0.5f, 0.0f), 0, 0, HOPS), 1e-9);
    }

    /** 完全没有频段数据（手工包络）→ 中性 0.5，不猜。 */
    @Test
    public void missingBandDataIsNeutral() {
        AutoCue.Envelope bare = new AutoCue.Envelope(filled(HOPS), new float[HOPS], 10.0);
        assertEquals("没有频段数据时按中性算", 0.5,
                AutoCue.lowEndClash(bare, bare, 0, 0, HOPS), 1e-9);
    }

    // ------------------------------------------------------------------
    // 成分二：低频反相（互相抵消 = 低频塌）
    // ------------------------------------------------------------------

    /** 同一条 60 Hz 正弦：反相那一侧必须被罚，同相不罚。 */
    @Test
    public void opposingLowsArePenalisedAndInPhaseLowsAreNot() {
        float[] sine = lowSine();
        float[] inverted = new float[sine.length];
        for (int i = 0; i < sine.length; i++) {
            inverted[i] = -sine[i];
        }
        // 两侧低频带**故意不对称**（0.9 vs 0.2）：这样「重叠」那一半本身是高分，
        // 反相带来的下降只可能来自相位成分——否则测的是重叠、不是相位。
        double same = AutoCue.lowEndClash(envWithWave(sine, 0.9f), envWithWave(sine, 0.2f), 0, 0, HOPS);
        double opposite = AutoCue.lowEndClash(envWithWave(sine, 0.9f), envWithWave(inverted, 0.2f),
                0, 0, HOPS);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[low-end] 同相 %.3f · 反相 %.3f",
                Double.valueOf(same), Double.valueOf(opposite)));
        assertTrue("反相的低频必须明显低于同相，实际 " + opposite + " vs " + same,
                opposite < same - 0.15);
        assertTrue("同相是加强，不该被罚，实际 " + same, same > 0.8);
    }

    // ------------------------------------------------------------------
    // 与打分表接线
    // ------------------------------------------------------------------

    /**
     * 两个候选点只有低频一个「轰」一个「安静」→ 打分里的 {@code lowEnd} 必须跟着分开。
     *
     * <p>这一条钉的是「新项真的接进 {@code evaluate} 了」：只测 {@code lowEndClash} 本身的话，
     * 忘了接线也照样绿。
     */
    @Test
    public void theScoreCarriesTheLowEndTerm() {
        AutoCue.Envelope outro = env(0.7f, 0.8f);
        AutoCue.Envelope intro = twoRegionIntro();
        AutoCue.Score hot = AutoCue.evaluate(outro, intro, 0.0, 1.0, 5.0);
        AutoCue.Score quiet = AutoCue.evaluate(outro, intro, 0.0, 14.0, 5.0);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[low-end] 打分里：轰处 %.3f · 安静处 %.3f（总分 %.3f vs %.3f）",
                Double.valueOf(hot.lowEnd), Double.valueOf(quiet.lowEnd),
                Double.valueOf(hot.total), Double.valueOf(quiet.total)));
        assertTrue("轰处这一项应当明显低，实际 " + hot.lowEnd, hot.lowEnd < 0.4);
        assertTrue("安静处这一项应当明显高，实际 " + quiet.lowEnd, quiet.lowEnd > 0.8);
    }

    /** 端到端：引子前 10 秒低频在轰、之后安静 → 选点必须落在安静那一段。 */
    @Test
    public void planningAvoidsTheMuddyLowEnd() {
        AutoCue.Cue cue = AutoCue.plan(longOutro(), twoRegionIntro(), 5.0);
        assertNotNull("没有找到任何候选", cue);
        System.out.println("[low-end] 选中的进场点 = " + cue.introEntrySecs + " s");
        assertTrue("前 10 秒低频在轰、之后安静，选点却落在 " + cue.introEntrySecs
                + " s（应当落在 9 s 之后）", cue.introEntrySecs >= 9.0);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 平包络：低频 {@code low}、中高频 {@code rest}（无波形）。 */
    private static AutoCue.Envelope env(float rest, float low) {
        float[] rms = filled(HOPS);
        float[][] band = new float[HOPS][];
        Arrays.fill(rms, rest);
        for (int i = 0; i < HOPS; i++) {
            band[i] = new float[] {low, rest, rest};
        }
        return new AutoCue.Envelope(rms, new float[HOPS], 10.0, null, 0, band, null, 0);
    }

    /**
     * 80 秒的引子：前 10 秒低频在轰、之后安静；其余各项完全对称。
     *
     * <p>80 秒是这么来的：候选点的硬边界里有 {@code MAX_INTRO_SECS = 20 s} 与
     * {@code MAX_INTRO_FRACTION = 0.25}——引子太短的话 20 秒那一档根本不可达，
     * 「轰」与「安静」两段就有一段进不了候选集。
     */
    private static AutoCue.Envelope twoRegionIntro() {
        int hops = 80 * 50;                     // 80 秒 × 每 20 ms 一格
        int hotHops = 10 * 50;                  // 前 10 秒
        float[] rms = new float[hops];
        float[][] band = new float[hops][];
        Arrays.fill(rms, 0.7f);
        for (int i = 0; i < hops; i++) {
            band[i] = new float[] {i < hotHops ? 0.85f : 0.04f, 0.4f, 0.4f};
        }
        return new AutoCue.Envelope(rms, new float[hops], 80.0, null, 0, band, null, 0);
    }

    /** 60 秒的出场曲包络：够过 MIN_OUTRO_SECS（20 s）与 MIN_OUTRO_FRACTION（20%）两条硬边界。 */
    private static AutoCue.Envelope longOutro() {
        int hops = 60 * 50;
        float[] rms = new float[hops];
        float[][] band = new float[hops][];
        Arrays.fill(rms, 0.7f);
        for (int i = 0; i < hops; i++) {
            band[i] = new float[] {0.8f, 0.4f, 0.4f};
        }
        return new AutoCue.Envelope(rms, new float[hops], 60.0, null, 0, band, null, 0);
    }

    private static AutoCue.Envelope envWithWave(float[] wave, float low) {
        float[] rms = filled(HOPS);
        float[][] band = new float[HOPS][];
        for (int i = 0; i < HOPS; i++) {
            band[i] = new float[] {low, 0.4f, 0.4f};
        }
        return new AutoCue.Envelope(rms, new float[HOPS], 10.0, null, 0, band, wave,
                AutoCue.WAVE_RATE);
    }

    private static float[] filled(int n) {
        float[] a = new float[n];
        Arrays.fill(a, 0.7f);
        return a;
    }

    /** 60 Hz 正弦，1 kHz 采样，12 秒（比窗口长，够反相判定用）。 */
    private static float[] lowSine() {
        int n = 12 * AutoCue.WAVE_RATE;
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            w[i] = (float) Math.sin(2.0 * Math.PI * 60.0 * i / AutoCue.WAVE_RATE);
        }
        return w;
    }
}
