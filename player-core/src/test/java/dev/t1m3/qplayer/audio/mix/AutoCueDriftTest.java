package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>拍漂移必须真的参与打分</b>（P1）。
 *
 * <p>用户报「两首歌有时候皱在一起很糊」。节奏上的成因是**拍栅格相对滑动**：
 * 两边速度不一样（例如 140 vs 145 BPM），窗口里攒出来的错位量 = {@code W × |1/T_A − 1/T_B|}，
 * 4 秒窗口约 140 ms、12 秒窗口约 420 ms —— 那是听得出来的「双踩」。
 *
 * <p>而 2026-10-01 那一版把这一项的死区放大到 **1.5 拍**（≈0.6 s）✗，理由是「不该为了一个
 * 听不出来的漂移把窗口砍短」。问题是 0.6 s 的漂移**恰恰听得出来**：这个死区让长窗口
 * 完全免疫于漂移，于是「窗口越长分越高」（平均副产物）就没被挡住。
 * 这一版把它收回到 0.25 拍。
 */
public class AutoCueDriftTest {

    /** 漂移的算法本身：与手算一致（140/145、4 s ≈ 140 ms）。 */
    @Test
    public void theDriftFormulaMatchesHandCalculation() {
        double four = AutoCue.driftSecs(140.0, 145.0, 4.0);
        double twelve = AutoCue.driftSecs(140.0, 145.0, 12.0);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[drift] 140/145：4 s → %.0f ms · 12 s → %.0f ms", Double.valueOf(four * 1000.0),
                Double.valueOf(twelve * 1000.0)));
        assertEquals("4 秒窗口应当是 ≈140 ms", 0.140, four, 0.01);
        assertEquals("12 秒窗口应当是 ≈420 ms", 0.420, twelve, 0.02);
        assertEquals("同一速度没有漂移", 0.0, AutoCue.driftSecs(140.0, 140.0, 8.0), 1e-9);
    }

    /**
     * <b>漂移要扣分</b>：140/145 配 12 秒窗口（漂移 ≈0.42 s ≈1 拍）不该拿满分。
     *
     * <p>改之前这一项有 1.5 拍死区 → 恒为 1.0（长窗口对漂移免疫）。
     */
    @Test
    public void aDriftyLongWindowIsPenalised() {
        AutoCue.Score score = AutoCue.evaluate(groove(140.0, 40.0), groove(145.0, 40.0),
                10.0, 10.0, 12.0);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[drift] 12 s 窗口 · 140/145 的「拍漂移」项 = %.3f（%s）",
                Double.valueOf(score.beat), score.beatNote));
        assertTrue("12 秒窗口里漂了约 1 拍，这一项不该还是满分：" + score.beat, score.beat < 0.9);
    }

    /** 速度一致时不许被罚（只有真漂移才扣分）。 */
    @Test
    public void aMatchedPairKeepsFullMarks() {
        AutoCue.Score score = AutoCue.evaluate(groove(140.0, 40.0), groove(140.0, 40.0),
                10.0, 10.0, 12.0);
        assertEquals("同速度没有漂移，这一项必须满分", 1.0, score.beat, 1e-9);
    }

    /** 窗口越短漂移越小（这是「短窗更清晰」的定量依据）。 */
    @Test
    public void shorterWindowsAlwaysDriftLess() {
        double prev = Double.MAX_VALUE;
        for (double w : new double[] {16.0, 12.0, 8.0, 4.0, 2.0}) {
            double d = AutoCue.driftSecs(140.0, 145.0, w);
            assertTrue("窗口 " + w + " s 的漂移应当小于更长窗口", d < prev);
            prev = d;
        }
    }

    // ------------------------------------------------------------------
    // 夹具：带拍栅格的平包络（速度可控）
    // ------------------------------------------------------------------

    private static AutoCue.Envelope groove(double bpm, double secs) {
        int hops = (int) Math.round(secs / AutoCue.HOP_SECS);
        float[] rms = new float[hops];
        float[] onset = new float[hops];
        float[][] band = new float[hops][];
        Arrays.fill(rms, 0.7f);
        double periodHops = 60.0 / bpm / AutoCue.HOP_SECS;
        for (int i = 0; i < hops; i++) {
            band[i] = new float[] {0.6f, 0.4f, 0.4f};
            if (periodHops >= 1.0 && Math.abs(i % periodHops) < 1.0) {
                onset[i] = 0.5f;      // 每拍一记起音，好让拍栅格可信
            }
        }
        double periodFrames = 60.0 / bpm * AutoCue.TARGET_ONSET_RATE;
        Analysis.Tempo tempo = new Analysis.Tempo((float) bpm, (float) periodFrames, 0f, 0.95f);
        return new AutoCue.Envelope(rms, onset, secs, tempo, AutoCue.TARGET_ONSET_RATE, band, null, 0);
    }
}
