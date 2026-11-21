package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>AI 自动档的「点位 × 接法」联合选择不许偏袒任何一侧</b>（路线图 W4 的验证那一半）。
 *
 * <p>W4 要的是「AutoCue 给点、AiMix 对每个点评最佳接法、再全局选」。查下来这套结构
 * <b>已经在</b> {@code AutoCue.comparePlans} 里：两侧各自去挑<strong>自己最好的点位与窗口</strong>，
 * 再拿同一把尺子（九项总分）全局比一次。文档里明确否决过的只有一件事——
 * 「让某一侧用它<strong>不会执行</strong>的窗口去打分」（那是拿它做不到的事给它加分）。
 *
 * <p><b>实测发现（2026-10-02，写这条判据时量出来的）</b>：这套打分<strong>没有任何一项
 * 内在地偏好短窗口</strong>——长窗口同样可以自由选进场点去避开起伏，于是「凹陷」「周期脉冲」
 * 这类夹具下 4 s 与 8 s 会打出**完全相同**的分数（本判据打印的那两行就是证据）。
 * 窗口之所以曾经总是挑十几秒，原因在这里，而不在偏袒某一侧。
 * 所以下面第一条判据在**当前夹具上是守卫性的**（同分时按「先出现的赢」取基准侧），
 * 它真正钉的是「一旦两侧分数分开，结论必须跟着分数走，不许偏向某种接法」。
 *
 * <p>还钉住另外两条性质：
 *
 * <ol>
 *   <li>结论必须**跟着分数走**：哪一侧分高就选哪一侧（判据里造一个「短窗能躲开凹陷」的素材，
 *       声波重合那一侧因此更高 → 必须选它）；</li>
 *   <li>调性本来就合（{@code shiftWorthwhile == false}）时**不给声波重合候选**——
 *       它的全部优势就是那次移调。</li>
 * </ol>
 */
public class AutoCueJointTest {

    /** 短窗能躲开凹陷 → 声波重合那一侧分数更高 → 必须选它。 */
    @Test
    public void theChoiceFollowsTheScoreNotTheStyle() {
        AutoCue.PlanChoice decided = AutoCue.comparePlans(steadyOutro(), dipIntro(),
                8.0, new double[] {4.0}, 0.0, true);
        assertNotNull("两侧都算得出方案，这一对素材不该整条放弃", decided);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[joint] 基准 %.3f（窗口 %.1f）· 声波重合 %.3f（窗口 %.1f）→ 选 %s",
                Double.valueOf(decided.baselineScore), Double.valueOf(8.0),
                Double.valueOf(decided.harmonicScore), Double.valueOf(decided.windowSecs),
                decided.harmonic ? "声波重合" : "现有接法"));
        assertTrue("声波重合那一侧分数更高（" + decided.harmonicScore + " > "
                        + decided.baselineScore + "），结论却选了现有接法",
                decided.harmonic == (decided.harmonicScore > decided.baselineScore));
    }

    /** 调性本来就合 → 不该给声波重合候选（它的全部优势就是那次移调）。 */
    @Test
    public void aWorthlessShiftMeansNoHarmonicCandidateAtAll() {
        AutoCue.PlanChoice decided = AutoCue.comparePlans(steadyOutro(), dipIntro(),
                8.0, new double[] {4.0}, 0.0, false);
        assertNotNull(decided);
        assertTrue("调性差小到不值得移调时不该再给声波重合候选", !decided.harmonic);
        assertTrue("选了基准侧就该给出它的窗口与分数", decided.baselineScore > 0.0);
    }

    /**
     * <b>打平时取「离最优点更近」的窗口</b>（2026-10-02 改）。
     *
     * <p>夹具的九项分数在 4 s / 8 s / 12 s 上完全相同（正是这套打分的真实性质，见类注释），
     * 于是「选哪个窗口」原先完全取决于枚举顺序。
     *
     * <p><b>原来这里钉的是「取更短的」</b>——那是 P3 那一轮的权宜之计：当时唯一的杠杆就是
     * 「打平偏向更短」，用来把窗口从十几秒拉到 Apple 的量级。但它同时把窗口往<b>下</b>推
     * （真曲目上曾经塌到 1.0 s），而 1~3 秒不是融合、是带斜坡的硬切，用户的原话是
     * 「我可以很明了地听出来换歌曲了」。现在改由 {@code AutoCue.windowLengthBonus}
     * 决定：<b>有最优点（6.5 s）、两侧递减</b>——同一个意图（窗口落在 Apple 那个量级，
     * 那边用的是 6~7 s）做得更准：这一组候选里最接近 6.5 s 的是 8 s，就选 8 s。
     */
    @Test
    public void whenTheScoresTieTheWindowClosestToTheOptimumWins() {
        // 素材**完全平**（没有脉冲、没有起伏）：这是「九项对窗口长度不敏感」的极端形状，
        // 各档总分因此严格打平，胜负只能由窗口偏好决定。
        AutoCue.Cue cue = AutoCue.plan(flat(60.0), flat(80.0),
                new double[] {12.0, 8.0, 4.0});
        assertNotNull(cue);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[joint] 跨档取最高 → 窗口 %.1f s、出场 %.1f、进场 %.1f",
                Double.valueOf(cue.windowSecs), Double.valueOf(cue.outroCutSecs),
                Double.valueOf(cue.introEntrySecs)));
        assertTrue("分数打平时应当取离最优点（"
                        + AutoCue.WINDOW_OPTIMUM_SECS + " s）最近的那个窗口，实际 "
                        + cue.windowSecs + " s",
                cue.windowSecs == 8.0);
    }

    /**
     * <b>窗口死区的量级必须小于点位之间的真实分差</b>——这是那一刀唯一的风险点。
     *
     * <p>量出来的两个数：① 完全平素材上 4→12 s 的抬升是 **0.007**（纯属平均的副产物，
     * 死区要盖住它）；② 点位之间的真实分差通常 **0.02 上下**（`docs/automix-modes.md`
     * 里那句「候选之间分差只有 0.02 上下」）。死区落在两者之间才成立：
     * 小了盖不住长度偏置（窗口又回到十几秒），大了就会让「窗口短」压过「点位真的更好」。
     *
     * <p>这条判据是**哨兵**：谁把这个常数改出区间，它当场红，并带着上面这两个数解释为什么。
     */
    @Test
    public void theWindowDeadZoneStaysBetweenTheTwoMeasuredNumbers() {
        double artifact = 0.007;      // 4→12 s 的平均副产物（实测）
        double pointSpread = 0.02;    // 点位之间的真实分差（实测）
        System.out.println(String.format(java.util.Locale.ROOT,
                "[joint] 窗口死区 %.3f（平均副产物 %.3f · 点位分差 %.3f）",
                Double.valueOf(AutoCue.WINDOW_TIE_EPS), Double.valueOf(artifact),
                Double.valueOf(pointSpread)));
        assertTrue("死区 " + AutoCue.WINDOW_TIE_EPS + " 太小，盖不住窗口长度的平均偏置（"
                        + artifact + "）——窗口会又回到十几秒",
                AutoCue.WINDOW_TIE_EPS >= artifact);
        assertTrue("死区 " + AutoCue.WINDOW_TIE_EPS + " 太大，会让「窗口更短」压过「点位真的更好」（点位差只有 "
                        + pointSpread + " 量级）",
                AutoCue.WINDOW_TIE_EPS < pointSpread);
    }

    // ------------------------------------------------------------------
    // top-k（W4 后半的第一半）
    // ------------------------------------------------------------------

    /**
     * <b>top-k 的第 0 个必须与 {@code plan} 的结论完全一致</b>——两个入口走同一遍搜索，
     * 没有第二份实现；否则「准备后重选」就成了一次结果不确定的抽奖。
     */
    @Test
    public void theFirstTopCueIsExactlyWhatPlanWouldPick() {
        AutoCue.Cue single = AutoCue.plan(steadyOutro(), dipIntro(), new double[] {12.0, 4.0});
        AutoCue.Cue[] top = AutoCue.topCues(steadyOutro(), dipIntro(), new double[] {12.0, 4.0}, 4);
        assertNotNull(single);
        assertTrue("top-k 不该为空", top.length >= 1);
        assertEquals("第 0 个必须与 plan 一致（出场点）", single.outroCutSecs, top[0].outroCutSecs, 1e-9);
        assertEquals("第 0 个必须与 plan 一致（进场点）", single.introEntrySecs, top[0].introEntrySecs, 1e-9);
        assertEquals("第 0 个必须与 plan 一致（窗口）", single.windowSecs, top[0].windowSecs, 1e-9);
        assertEquals("第 0 个必须与 plan 一致（代价）", single.cost, top[0].cost, 1e-9);
    }

    /** 榜单里的候选必须是**互不相同**的点位（相邻 20 ms 的候选是同一个点，不许占名额）。 */
    @Test
    public void theTopCuesAreDistinctPoints() {
        AutoCue.Cue[] top = AutoCue.topCues(steadyOutro(), dipIntro(), new double[] {12.0, 8.0, 4.0}, 4);
        assertTrue("至少要有一个候选", top.length >= 1);
        for (int i = 0; i < top.length; i++) {
            for (int j = i + 1; j < top.length; j++) {
                boolean apart = Math.abs(top[i].outroCutSecs - top[j].outroCutSecs) >= 0.25
                        || Math.abs(top[i].introEntrySecs - top[j].introEntrySecs) >= 0.25;
                assertTrue("第 " + i + " 与第 " + j + " 个候选是同一个点（"
                        + top[i].outroCutSecs + "/" + top[i].introEntrySecs + " vs "
                        + top[j].outroCutSecs + "/" + top[j].introEntrySecs + "）",
                        apart);
            }
        }
        System.out.println("[joint] top-k：" + top.length + " 个候选，第 0 个窗口 "
                + top[0].windowSecs + " s");
    }

    /** 榜单按分数从好到差；k 超过可用候选数时如实少给（不补空位）。 */
    @Test
    public void theRankingIsOrderedAndBounded() {
        AutoCue.Cue[] top = AutoCue.topCues(steadyOutro(), dipIntro(), new double[] {12.0, 4.0}, 2);
        assertTrue("最多给 k 个", top.length <= 2);
        for (int i = 0; i < top.length; i++) {
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[joint]   候选 %d：%s/%.1fs 窗口 %.1fs 代价 %.4f",
                    Integer.valueOf(i + 1), Double.valueOf(top[i].outroCutSecs),
                    Double.valueOf(top[i].introEntrySecs), Double.valueOf(top[i].windowSecs),
                    Double.valueOf(top[i].cost)));
        }
        // 容差就是**窗口死区**：在死区之内「谁更好」本来就不由代价决定
        // （`better()` 说它们等价——例如两个候选只差 0.0003 的代价、窗口又一样长），
        // 这时榜单里的先后只保证**稳定**，不保证按代价单调。实测就是这么撞出来的：
        // 候选 1 代价 0.3491、候选 2 代价 0.3488，而 plan 选了前者（死区内先到先得）。
        for (int i = 1; i < top.length; i++) {
            assertTrue("第 " + i + " 个比第 " + (i - 1) + " 个好出死区（"
                            + (top[i - 1].cost - top[i].cost) + " > " + AutoCue.WINDOW_TIE_EPS
                            + "）——榜单顺序错了",
                    top[i].cost >= top[i - 1].cost - AutoCue.WINDOW_TIE_EPS);
        }
        assertEquals("没有任何合法候选时给空数组（不是 null）", 0,
                AutoCue.topCues(new AutoCue.Envelope(new float[100], new float[100], 2.0),
                        new AutoCue.Envelope(new float[100], new float[100], 2.0),
                        new double[] {4.0}, 4).length);
    }

    /** 两侧都没有合法候选 → 返回 null，交给调用方退回固定点位（不许假装选了一个）。 */
    @Test
    public void withoutAnyLegalCandidateTheComparisonSaysSo() {
        AutoCue.Envelope tiny = new AutoCue.Envelope(new float[100], new float[100], 2.0);
        assertTrue("素材短到没有任何合法候选时必须返回 null",
                AutoCue.comparePlans(tiny, tiny, 8.0, new double[] {4.0}, 0.0, true) == null);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 完全平的包络（响度处处相同）——用来让各档窗口的总分严格打平。 */
    private static AutoCue.Envelope flat(double secs) {
        int hops = (int) Math.round(secs / AutoCue.HOP_SECS);
        float[] rms = new float[hops];
        float[][] band = new float[hops][];
        Arrays.fill(rms, 0.7f);
        for (int i = 0; i < hops; i++) {
            band[i] = new float[] {0.6f, 0.4f, 0.4f};
        }
        return new AutoCue.Envelope(rms, new float[hops], secs, null, 0, band, null, 0);
    }

    /** 60 秒的平稳出场曲（够过 MIN_OUTRO_SECS 20 s 与 20% 两条硬边界）。 */
    private static AutoCue.Envelope steadyOutro() {
        return envelope(60.0, 0.7f, -1);
    }

    /**
     * 80 秒的引子：每 6 秒一个 0.5 秒的脉冲（响度 0.7 → 1.0）。
     *
     * <p>为什么用周期脉冲而不是凹陷：凹陷能被**任何**窗口的进场点绕开（两者的合法进场
     * 区间都是 [0,20] s，8 秒窗口停在凹陷之前就行），所以第一版夹具两侧同分、判据空转。
     * 脉冲不一样——窗口越长，**包含的脉冲越多**，叠出来的总电平起伏越大（`ripple` 项越低），
     * 于是「4 秒窗有它自己的道理」这件事才是可测的。
     */
    private static AutoCue.Envelope dipIntro() {
        int hops = (int) Math.round(80.0 / AutoCue.HOP_SECS);
        float[] rms = new float[hops];
        float[][] band = new float[hops][];
        Arrays.fill(rms, 0.7f);
        for (int i = 0; i < hops; i++) {
            if ((i / 25) % 6 == 0 && i % 25 < 6) {      // 每 6 秒一次、每次 0.5 秒
                rms[i] = 1.0f;
            }
            band[i] = new float[] {0.6f, 0.4f, 0.4f};
        }
        float[] onset = new float[hops];
        for (int i = 1; i < hops; i++) {
            onset[i] = Math.max(0f, rms[i] - rms[i - 1]);
        }
        return new AutoCue.Envelope(rms, onset, 80.0, null, 0, band, null, 0);
    }

    private static AutoCue.Envelope envelope(double secs, float level, int dipFromHop) {
        int hops = (int) Math.round(secs / AutoCue.HOP_SECS);
        float[] rms = new float[hops];
        float[][] band = new float[hops][];
        Arrays.fill(rms, level);
        if (dipFromHop >= 0) {
            for (int i = dipFromHop; i < Math.min(hops, dipFromHop + 100); i++) {
                rms[i] = 0.2f;             // 2 秒的凹陷
            }
        }
        for (int i = 0; i < hops; i++) {
            band[i] = new float[] {0.6f, 0.4f, 0.4f};
        }
        float[] onset = new float[hops];
        for (int i = 1; i < hops; i++) {
            onset[i] = Math.max(0f, rms[i] - rms[i - 1]);
        }
        return new AutoCue.Envelope(rms, onset, secs, null, 0, band, null, 0);
    }
}
