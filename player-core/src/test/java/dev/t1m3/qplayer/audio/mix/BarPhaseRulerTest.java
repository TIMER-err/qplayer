package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>小节相位该用哪把尺子</b>（2026-10-07，goal 第 1 步："选点差点意思"的根子之一）。
 *
 * <p>背景（实测）：参考对上 {@code barPhaseOf}（低频起音那套）与**人声乐句起点**给出的相位
 * 差 1.5~1.8 拍 ✗ —— 而乐句本该从小节头起 ✓。网格错了，"出场点贴在小节线上"就只是贴在
 * 错的小节线上 ✗ ⇒ 这一条钉住"什么时候该改用乐句那把尺子"。
 */
public class BarPhaseRulerTest {

    private static final double BAR = 2.0;

    /** 手搭一条包络：只需要能过 {@code barPhaseResolved} 的入口检查（没有拍栅格 ⇒ 低频那把给 NaN）。 */
    private static AutoCue.Envelope flatEnvelope() {
        int frames = 600;                       // 12 秒
        float[] rms = new float[frames];
        float[] onset = new float[frames];
        float[][] band = {new float[frames], new float[frames], new float[frames]};
        return new AutoCue.Envelope(rms, onset, frames * AutoCue.HOP_SECS, null, 48_000,
                band, new float[frames], 48_000, -1f, -1f, null);
    }

    private static AutoCue.LyricSpans spansAt(double phase, double bar, int count) {
        double[] starts = new double[count];
        double[] ends = new double[count];
        for (int i = 0; i < count; i++) {
            starts[i] = phase + i * bar;
            ends[i] = starts[i] + 1.0;
        }
        return new AutoCue.LyricSpans(starts, ends);
    }

    @Test
    public void phrasesOnAKnownGridWinOverTheLowBandRuler() {
        AutoCue.BarPhase r = AutoCue.barPhaseResolved(flatEnvelope(),
                spansAt(0.55, BAR, 10), BAR);
        assertTrue("乐句整整齐齐落在 0.55 + k×2.0 上 ⇒ 必须采纳**乐句**这把尺子", r.fromPhrases);
        assertEquals("相位必须命中乐句那条网格", 0.55, r.phase, 0.02);
    }

    @Test
    public void tooFewPhrasesFallBackInsteadOfGuessing() {
        AutoCue.BarPhase few = AutoCue.barPhaseResolved(flatEnvelope(),
                spansAt(0.55, BAR, 4), BAR);          // 只有 4 句 < 6
        assertFalse("乐句太少 ⇒ 不许拿它定相位（不猜）", few.fromPhrases);
        AutoCue.BarPhase none = AutoCue.barPhaseResolved(flatEnvelope(), null, BAR);
        assertFalse("没有乐句 ⇒ 同样退回", none.fromPhrases);
    }

    @Test
    public void scatteredPhrasesDoNotCountAsAGrid() {
        // 句子撒得到处都是（不成簇）⇒ 距离最近网格的平均值会很大 ⇒ 不许采纳
        double[] starts = {0.11, 0.87, 1.42, 0.33, 1.91, 1.05, 0.62, 1.77};
        double[] ends = new double[starts.length];
        for (int i = 0; i < starts.length; i++) {
            ends[i] = starts[i] + 0.5;
        }
        AutoCue.BarPhase r = AutoCue.barPhaseResolved(flatEnvelope(),
                new AutoCue.LyricSpans(starts, ends), BAR);
        assertFalse("不成簇 ⇒ 退回低频那把（哪怕乐句数够）", r.fromPhrases);
    }
}
