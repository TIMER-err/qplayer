package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link Analysis#tempoFromBeats} 的测试——把<b>模型给出的拍点</b>变成节奏结论。
 *
 * <h2>为什么这是一个独立的测试类</h2>
 *
 * <p>这个函数<b>本仓库新增</b>：上游 {@code sonalis-model/src/beat.rs} 的
 * {@code BeatModel::beats} 只返回拍点秒数，到 {@code Tempo} 的换算是空白的
 * （它那边模型路线还没接进 automix）。所以这里没有上游测试可对齐，
 * 断言全部按 {@code tempoFromBeats} 自己的文档写：
 * <b>周期取间隔中位数、相位取第一个拍点、置信度按间隔的中位绝对偏差</b>。
 *
 * <p>测试用的拍点数组是手工造的「秒」，不经过模型——与 {@code PeaksTest}
 * 同一条理由：算法里的十几行应该能脱离上千个模型参数被测到。
 */
public class TempoFromBeatsTest {

    private static final int RATE = 48_000;

    /** 造一条从 {@code first} 开始、周期 {@code period} 秒、共 {@code n} 个的拍点。 */
    private static float[] grid(float first, float period, int n) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = first + (float) i * period;
        }
        return out;
    }

    /** 规整的栅格必须直接给出它的 BPM。 */
    @Test
    public void aRegularGridBecomesItsBpm() {
        Analysis.Tempo t = Analysis.tempoFromBeats(grid(0.0f, 0.5f, 25), RATE);
        assertNotNull(t);
        assertEquals(120.0f, t.bpm, 1e-3f);
        assertEquals(24_000.0f, t.periodFrames, 1e-1f);
        assertEquals(0.0f, t.phaseFrames, 1e-1f);
    }

    /** 相位是第一个拍点，取模到周期之内。 */
    @Test
    public void theFirstBeatSetsThePhase() {
        // 首拍 0.31 秒（AnalysisTest 里硬算那条也用的是这个数）
        Analysis.Tempo t = Analysis.tempoFromBeats(grid(0.31f, 0.5f, 25), RATE);
        assertNotNull(t);
        assertEquals(120.0f, t.bpm, 1e-3f);
        assertEquals(0.31f * RATE, t.phaseFrames, 0.5f);

        // 首拍落在一个周期之后时，相位要折回 [0, period)
        Analysis.Tempo t2 = Analysis.tempoFromBeats(grid(1.31f, 0.5f, 25), RATE);
        assertNotNull(t2);
        assertEquals(120.0f, t2.bpm, 1e-3f);
        assertEquals(0.31f * RATE, t2.phaseFrames, 1.0f);
    }

    /**
     * 缺一拍不该把速度带偏——这正是「取中位数而不是取平均」的理由。
     *
     * <p>模型在弱拍上偶尔会漏一个峰，漏掉的那一拍会让某一个间隔翻倍。
     * 平均值会被它拉低一大截；中位数岿然不动。
     */
    @Test
    public void aDroppedBeatDoesNotShiftThePeriod() {
        float[] beats = {0.0f, 0.5f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 4.5f};
        Analysis.Tempo t = Analysis.tempoFromBeats(beats, RATE);
        assertNotNull(t);
        assertEquals("漏了一拍时中位数仍应是 0.5 秒", 120.0f, t.bpm, 1e-3f);
    }

    /** 少于两个拍点就没有周期可言——<b>不表态</b>（上游 {@code None} 的对应物）。 */
    @Test
    public void fewerThanTwoBeatsIsNotGuessed() {
        assertNull(Analysis.tempoFromBeats(null, RATE));
        assertNull(Analysis.tempoFromBeats(new float[0], RATE));
        assertNull(Analysis.tempoFromBeats(new float[]{0.5f}, RATE));
    }

    /** 全零秒差（模型重复给了同一帧）也不算周期。 */
    @Test
    public void duplicateTimestampsAreNotAPeriod() {
        assertNull(Analysis.tempoFromBeats(new float[]{0.5f, 0.5f, 0.5f}, RATE));
    }

    /** 采样率为 0 时不表态。 */
    @Test
    public void zeroSampleRateIsNotGuessed() {
        assertNull(Analysis.tempoFromBeats(grid(0.0f, 0.5f, 10), 0));
        assertNull(Analysis.tempoFromBeats(grid(0.0f, 0.5f, 10), -1));
    }

    /**
     * 置信度：规整栅格接近 1，抖动越大越低。
     *
     * <p>它与硬算路的 {@code confidence} <b>语义不同</b>（那边是自相关主峰相对
     * 邻域的能量占比），所以这里只钉「同一条路内部可比」这件事——
     * 没有任何判定读它，它是给人看的。
     */
    @Test
    public void regularGridsScoreHigherConfidenceThanJitteredOnes() {
        Analysis.Tempo regular = Analysis.tempoFromBeats(grid(0.0f, 0.5f, 25), RATE);
        assertNotNull(regular);
        assertEquals(1.0f, regular.confidence, 1e-3f);

        float[] wobbly = new float[25];
        for (int i = 0; i < wobbly.length; i++) {
            wobbly[i] = (float) i * 0.5f + (i % 2 == 0 ? 0.0f : 0.08f);
        }
        Analysis.Tempo jittered = Analysis.tempoFromBeats(wobbly, RATE);
        assertNotNull(jittered);
        assertTrue("抖动的栅格置信度应当更低：" + jittered.confidence + " vs " + regular.confidence,
                jittered.confidence < regular.confidence);
        assertTrue(jittered.confidence >= 0.0f && jittered.confidence <= 1.0f);
    }

    /**
     * 周期换算成帧用的是<b>传进来的</b>采样率，不是模型前端的 22050。
     *
     * <p>拍点是秒，秒与采样率无关；采样率只决定「一秒算多少帧」。
     * 这条盯住那个换算：用错会让拍点栅格整体缩放，
     * 而症状是「切点总差一点」，几乎查不出来。
     */
    @Test
    public void framesAreExpressedInTheCallersRate() {
        Analysis.Tempo at22050 = Analysis.tempoFromBeats(grid(0.0f, 0.5f, 9), 22_050);
        Analysis.Tempo at48000 = Analysis.tempoFromBeats(grid(0.0f, 0.5f, 9), 48_000);
        assertNotNull(at22050);
        assertNotNull(at48000);
        assertEquals(at22050.bpm, at48000.bpm, 1e-6f);
        assertEquals(11_025.0f, at22050.periodFrames, 1e-1f);
        assertEquals(24_000.0f, at48000.periodFrames, 1e-1f);
    }

    // ------------------------------------------------------------------
    // 帧以下分辨率的细化（本条是「最小二乘细化」存在的理由）
    // ------------------------------------------------------------------

    /**
     * 造一条「落在帧栅格上」的拍点序列：模型给出的时间戳就是帧号换算来的，
     * 所以真实拍点会被吸附到最近的格点上。
     *
     * <p>{@code hop = 441} @ {@code rate}——与模型前端一致（22050 下 20 ms 一格）。
     */
    private static float[] frameQuantisedGrid(float bpm, float first, int count, int rate) {
        int hop = 441;
        float periodFrames = 60.0f / bpm * (float) rate;
        float firstFrames = first * (float) rate;
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            // 「窗口中心」约定：帧号换算回秒时把 n_fft/2 加回去，这里沿用同一套
            long frame = Math.round((firstFrames + (float) i * periodFrames) / (float) hop);
            out[i] = (frame * (float) hop) / (float) rate;
        }
        return out;
    }

    /**
     * <b>落在帧栅格上的拍点必须能估出帧以下的周期。</b>
     *
     * <p>128 BPM 的周期是 0.46875 s，而 22050 Hz 下帧格是 20 ms：相邻两个拍点
     * 一相减只能得到 0.46 s（= <b>130.43 BPM</b>）。把整条序列对「第几拍」做一次
     * 最小二乘，量化误差会互相抵消，周期回到 0.4688 s 附近。
     *
     * <p>140 BPM 同理：周期 0.42857 s 会被量化成 0.42 s（= 142.86 BPM）。
     */
    @Test
    public void aFrameQuantisedGridStillResolvesTheTrueTempo() {
        float[] beats = frameQuantisedGrid(128.0f, 0.02f, 26, 22_050);
        Analysis.Tempo t = Analysis.tempoFromBeats(beats, RATE);
        assertNotNull(t);
        assertTrue("128 BPM 的拍点应当被细化成 128 附近，实际 " + t.bpm + "（" + t.describe() + "）",
                Math.abs(t.bpm - 128.0f) < 0.5f);

        float[] beats140 = frameQuantisedGrid(140.0f, 0.02f, 30, 22_050);
        Analysis.Tempo t140 = Analysis.tempoFromBeats(beats140, RATE);
        assertNotNull(t140);
        assertTrue("140 BPM 的拍点应当被细化成 140 附近，实际 " + t140.bpm,
                Math.abs(t140.bpm - 140.0f) < 0.5f);
    }

    /**
     * 细化不能把「本来就落在格点上」的结论改坏——100 BPM 的周期 0.6 s
     * 恰好是 30 个帧格，量化误差为 0，细化前后都该是 100.00。
     */
    @Test
    public void refiningDoesNotDisturbGridAlignedTempos() {
        float[] beats = frameQuantisedGrid(100.0f, 0.0f, 21, 22_050);
        Analysis.Tempo t = Analysis.tempoFromBeats(beats, RATE);
        assertNotNull(t);
        assertEquals(100.0f, t.bpm, 0.05f);
    }

    /** 漏一拍之后细化仍要给出真实周期（异常点剔掉，不能把斜率带偏）。 */
    @Test
    public void refiningSurvivesAMissingBeat() {
        float[] beats = frameQuantisedGrid(128.0f, 0.02f, 26, 22_050);
        // 挖掉第 9 个拍点：与它相邻的两个间隔会翻倍
        float[] holed = new float[beats.length - 1];
        int at = 0;
        for (int i = 0; i < beats.length; i++) {
            if (i != 9) {
                holed[at++] = beats[i];
            }
        }
        Analysis.Tempo t = Analysis.tempoFromBeats(holed, RATE);
        assertNotNull(t);
        assertTrue("漏一拍不该把周期带偏，实际 " + t.bpm, Math.abs(t.bpm - 128.0f) < 1.0f);
    }

    /** 拍点太少（少于四个）就不做细化，但仍要给出中位数结论。 */
    @Test
    public void tooFewBeatsFallsBackToTheMedian() {
        Analysis.Tempo t = Analysis.tempoFromBeats(new float[]{0.0f, 0.5f, 1.0f}, RATE);
        assertNotNull(t);
        assertEquals(120.0f, t.bpm, 1e-3f);
    }
}
