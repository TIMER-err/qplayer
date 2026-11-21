package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>第九项：人声重叠</b>（1 = 窗口里两边的人声没有长时间叠唱，0 = 一直在叠）。
 *
 * <p>人声判定是**代理 VAD**：中频（200 Hz–2 kHz，人声共振峰）既要够响，又要在
 * 0.4 秒窗里**按音节起伏**。所以判据里必须有一条是「持续长音 / 和声垫不算人声」——
 * 那是这个代理立不立得住的关键，也是最容易被写坏的一处。
 *
 * <p>判分口径照用户提的来：窗口内人声重叠 ≤ 0.5 秒不罚，之后线性加重。
 */
public class AutoCueVocalTest {

    private static final int HOPS = 500;          // 10 秒窗口

    // ------------------------------------------------------------------
    // 代理 VAD 本身
    // ------------------------------------------------------------------

    /** 按音节起伏的中频 = 有人在唱：两侧都唱 → 低分；只有一侧唱 → 满分。 */
    @Test
    public void twoSingersOverlapAndOneSingerDoesNot() {
        AutoCue.Envelope a = singing(0.5f);
        AutoCue.Envelope b = singing(0.5f);
        AutoCue.Envelope quiet = steady(0.5f);       // 中频有能量但完全不动

        double both = AutoCue.vocalOverlap(a, b, 0, 0, HOPS);
        double one = AutoCue.vocalOverlap(a, quiet, 0, 0, HOPS);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[vad] 两边都在唱 %.3f · 只有一边唱 %.3f", Double.valueOf(both), Double.valueOf(one)));
        assertTrue("两边整窗都在唱，这一项必须给低分，实际 " + both, both < 0.3);
        assertEquals("另一边没有起伏（不是人声）→ 不该罚", 1.0, one, 1e-9);
    }

    /**
     * <b>持续长音/和声垫不算人声。</b>
     *
     * <p>这是这个代理与「中频够响就算人声」的分界线：垫音在时间上几乎是平的，
     * 按音节起伏才是人声。把它算成人声，就会把大量「两边都有合成器垫」的候选误杀。
     */
    @Test
    public void aSteadyMidBandIsNotAVoice() {
        AutoCue.Envelope pad = steady(0.9f);         // 很响但完全不动
        assertEquals("没有起伏的中频不该判成人声", 1.0, AutoCue.vocalOverlap(pad, pad, 0, 0, HOPS), 1e-9);
    }

    /** 很短的重叠（≤ 0.5 秒）不罚：接缝处一个字尾压上另一个字头是正常的。 */
    @Test
    public void ashortOverlapIsFreeButALongOneIsNot() {
        AutoCue.Envelope a = burst(new int[] {0, 30});        // 只在开头唱 0.6 秒
        AutoCue.Envelope b = burst(new int[] {0, 30});
        AutoCue.Envelope c = burst(new int[] {200, 400});     // 唱 4 秒
        double shortOverlap = AutoCue.vocalOverlap(a, b, 0, 0, HOPS);
        double longOverlap = AutoCue.vocalOverlap(c, c, 0, 0, HOPS);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[vad] 短重叠 %.3f · 长重叠 %.3f",
                Double.valueOf(shortOverlap), Double.valueOf(longOverlap)));
        assertTrue("≤0.5 秒的重叠应当基本不罚，实际 " + shortOverlap, shortOverlap > 0.9);
        assertTrue("4 秒的重叠必须归零，实际 " + longOverlap, longOverlap < 0.1);
    }

    /** 没有分频数据 → 中性 0.5，不猜。 */
    @Test
    public void missingBandDataIsNeutralForVoicesToo() {
        AutoCue.Envelope bare = new AutoCue.Envelope(new float[HOPS], new float[HOPS], 10.0);
        assertEquals("没有分频数据时按中性算", 0.5, AutoCue.vocalOverlap(bare, bare, 0, 0, HOPS), 1e-9);
    }

    // ------------------------------------------------------------------
    // 接线与端到端
    // ------------------------------------------------------------------

    /** 打分真的带上了这一项（只测 {@code vocalOverlap} 的话，忘了接线也照样绿）。 */
    @Test
    public void theScoreCarriesTheVocalTerm() {
        AutoCue.Envelope outro = singing(0.7f);
        AutoCue.Envelope intro = new AutoCue.Envelope(singing(0.7f).rms, new float[8 * 50 * 2], 80.0,
                null, 0, twoRegionMid(), null, 0);
        AutoCue.Score singingBoth = AutoCue.evaluate(outro, intro, 0.0, 0.0, 5.0);
        AutoCue.Score onlyOne = AutoCue.evaluate(outro, intro, 0.0, 11.0, 5.0);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[vad] 打分里：两边唱 %.3f · 只有一边唱 %.3f（总分 %.3f vs %.3f）",
                Double.valueOf(singingBoth.vocal), Double.valueOf(onlyOne.vocal),
                Double.valueOf(singingBoth.total), Double.valueOf(onlyOne.total)));
        assertTrue("两边都在唱那一段应当低，实际 " + singingBoth.vocal, singingBoth.vocal < 0.3);
        assertTrue("只有一边唱那一段应当满分，实际 " + onlyOne.vocal, onlyOne.vocal > 0.99);
    }

    /** 端到端：引子前 10 秒两边都在唱、之后只有一边 → 选点必须落在后面。 */
    @Test
    public void planningAvoidsTwoVoicesSingingTogether() {
        AutoCue.Cue cue = AutoCue.plan(singing(0.7f), vocalTwoRegionIntro(), 5.0);
        assertNotNull("没有找到任何候选", cue);
        System.out.println("[vad] 选中的进场点 = " + cue.introEntrySecs + " s");
        assertTrue("前 10 秒两边都在唱、之后只有一边唱，选点却落在 " + cue.introEntrySecs
                + " s（应当落在 9 s 之后）", cue.introEntrySecs >= 9.0);
    }

    // ------------------------------------------------------------------
    // 重叠时段（W3 后半：只在重叠处去人声的输入）
    // ------------------------------------------------------------------

    /** 两边都只在第 2~4 秒唱 → 区间必须正好是那一段。 */
    @Test
    public void theOverlapRangeIsExactlyWhereBothSing() {
        AutoCue.Envelope a = burst(new int[] {100, 200});     // 2~4 秒
        AutoCue.Envelope b = burst(new int[] {100, 200});
        double[] ranges = AutoCue.vocalOverlapRanges(a, b, 0, 0, HOPS);
        assertNotNull("两侧都有频段数据，不该返回 null", ranges);
        assertEquals("应当只有一段", 2, ranges.length);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[vad] 重叠时段 %.2f~%.2f s", Double.valueOf(ranges[0]), Double.valueOf(ranges[1])));
        assertTrue("起点应当 ≈2.0 s，实际 " + ranges[0], Math.abs(ranges[0] - 2.0) < 0.1);
        assertTrue("终点应当 ≈4.0 s，实际 " + ranges[1], Math.abs(ranges[1] - 4.0) < 0.1);
    }

    /** 两边唱的时候完全错开 → 没有重叠，返回**空数组**（明确的结论，不是 null）。 */
    @Test
    public void singingAtDifferentTimesMeansNoRanges() {
        double[] ranges = AutoCue.vocalOverlapRanges(burst(new int[] {0, 100}),
                burst(new int[] {200, 300}), 0, 0, HOPS);
        assertNotNull("这是「算得出、结论是没有重叠」，不该返回 null", ranges);
        assertEquals("错开唱就没有重叠时段", 0, ranges.length);
    }

    /** 拿不到分频包络 → null（人声层据此走「不确定，照旧整窗去人声」）。 */
    @Test
    public void withoutBandDataTheRangesAreUnknownNotEmpty() {
        AutoCue.Envelope bare = new AutoCue.Envelope(new float[HOPS], new float[HOPS], 10.0);
        assertEquals("没有数据时必须是 null（不等于「没有重叠」）", null,
                AutoCue.vocalOverlapRanges(bare, bare, 0, 0, HOPS));
    }

    /** 计划的三值契约：null = 照旧整窗、空 = 整段不去、非空 = 只在区间里去。 */
    @Test
    public void thePlanCarriesThreeDistinctStates() {
        dev.t1m3.qplayer.audio.mix.TransitionPlan none = new dev.t1m3.qplayer.audio.mix.TransitionPlan(
                0.0, PitchPlan.ZERO, 0f, "x");
        assertEquals("不传区间 = 没有这个信息", null, none.vocalDuckRanges);
        dev.t1m3.qplayer.audio.mix.TransitionPlan empty = new dev.t1m3.qplayer.audio.mix.TransitionPlan(
                0.0, PitchPlan.ZERO, 0f, "x", 0.0, new double[0]);
        assertEquals("空数组 = 明确「没有重叠」", 0, empty.vocalDuckRanges.length);
        dev.t1m3.qplayer.audio.mix.TransitionPlan some = new dev.t1m3.qplayer.audio.mix.TransitionPlan(
                0.0, PitchPlan.ZERO, 0f, "x", 0.0, new double[] {2.0, 4.0});
        assertEquals("非空 = 只在这些区间里去人声", 2, some.vocalDuckRanges.length);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 中频按音节起伏（2 Hz 方波状起伏）、够响、低频与高频都很小。 */
    private static AutoCue.Envelope singing(float level) {
        int hops = 80 * 50;
        float[] rms = new float[hops];
        float[][] band = new float[hops][];
        Arrays.fill(rms, level);
        for (int i = 0; i < hops; i++) {
            // 每 5 格（0.1 秒）在 level 与 level×0.35 之间来回：两个电平都在
            // VOICE_FLOOR 之上（否则一半的格会被判成「没人声」，重叠时长只剩一半），
            // 起伏比例 0.35 也够过 VOICE_MODULATION（0.25）。
            float mid = (i / 5) % 2 == 0 ? level : level * 0.35f;
            band[i] = new float[] {0.05f, mid, 0.05f};
        }
        return new AutoCue.Envelope(rms, new float[hops], 80.0, null, 0, band, null, 0);
    }

    /** 中频有能量但完全平（和声垫/长音）。 */
    private static AutoCue.Envelope steady(float level) {
        int hops = 80 * 50;
        float[] rms = new float[hops];
        float[][] band = new float[hops][];
        Arrays.fill(rms, level);
        for (int i = 0; i < hops; i++) {
            band[i] = new float[] {0.05f, level, 0.05f};
        }
        return new AutoCue.Envelope(rms, new float[hops], 80.0, null, 0, band, null, 0);
    }

    /** 只在 {@code [from, to)} 格唱（其余安静）：用来验「≤0.5 秒不罚 / 长了就罚」。 */
    private static AutoCue.Envelope burst(int[] range) {
        int hops = 80 * 50;
        float[] rms = new float[hops];
        float[][] band = new float[hops][];
        Arrays.fill(rms, 0.5f);
        for (int i = 0; i < hops; i++) {
            boolean on = i >= range[0] && i < range[1];
            float mid = on ? ((i / 5) % 2 == 0 ? 0.5f : 0.2f) : 0.02f;
            band[i] = new float[] {0.05f, mid, 0.05f};
        }
        return new AutoCue.Envelope(rms, new float[hops], 80.0, null, 0, band, null, 0);
    }

    /** 80 秒引子：前 10 秒两边都在唱（中频起伏），之后只有一边。 */
    private static AutoCue.Envelope vocalTwoRegionIntro() {
        int hops = 80 * 50;
        int hot = 10 * 50;
        float[] rms = new float[hops];
        float[][] band = new float[hops][];
        Arrays.fill(rms, 0.7f);
        for (int i = 0; i < hops; i++) {
            float mid = i < hot
                    ? ((i / 5) % 2 == 0 ? 0.7f : 0.3f)      // 在唱（两个电平都在门槛之上）
                    : 0.02f;                                // 不唱
            band[i] = new float[] {0.4f, mid, 0.4f};
        }
        return new AutoCue.Envelope(rms, new float[hops], 80.0, null, 0, band, null, 0);
    }

    /** {@code theScoreCarriesTheVocalTerm} 用的引子频段：前 10 秒唱、之后不唱。 */
    private static float[][] twoRegionMid() {
        int hops = 80 * 50;
        int hot = 10 * 50;
        float[][] band = new float[hops][];
        for (int i = 0; i < hops; i++) {
            float mid = i < hot ? ((i / 5) % 2 == 0 ? 0.7f : 0.3f) : 0.02f;
            band[i] = new float[] {0.4f, mid, 0.4f};
        }
        return band;
    }
}
