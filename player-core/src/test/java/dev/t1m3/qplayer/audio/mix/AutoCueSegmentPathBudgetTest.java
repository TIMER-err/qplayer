package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>「三段路径的 16 小节上限只封 A 的贡献」的判据</b>（2026-10-06；用户原话，他列表里的第 ④ 项：
 *
 * <blockquote>
 * 三段路径的 16 小节上限语义（上限只封 A 的贡献；B 的床单算）
 * </blockquote>
 * ）
 *
 * <h2>要钉的那一句话</h2>
 *
 * <p>{@link AutoCue#INTRO_BED_PATH_MAX_BARS} 那 16 小节量的是<b>这条路径为 A 争取出来的
 * 过渡窗口</b>，也就是 <b>A 的尾奏段 + A 的循环段 ×repeats</b> 那两段（{@code tailBars +
 * loopBars × loopRepeats}，按 A 的小节尺子换算成秒）。<b>B 的落床段是另一件事</b>
 * （"B 的床"），它由 B 侧自己的规矩管（{@link AutoCue#INTRO_BED_HEAD_SECS} 的扫描范围
 * 45 s + {@link AutoCue#INTRO_BED_LEAD_BARS} 的 8 小节前伸上限，见
 * {@link AutoCue#introBedSpan}），<strong>不从那 16 小节里扣</strong>。
 *
 * <p>改之前那条闸量的是<b>总窗</b>（{@code A 侧 + B 的落床段 ×n}）：于是"B 的床单"会被
 * 算进 A 的 16 小节里，A 侧明明只有 16 小节、却因为床也要落两遍而被整条否掉。
 *
 * <h2>红-前（每一条都真的先跑过）</h2>
 *
 * <p>这一档的素材是<strong>直接用 {@code AutoCue.envelopeOf} 造出来的小节栅格包络</strong>
 * （每小节头上一个起音 ⇒ {@link AutoCue#barPhaseOf} 给相位 0），因为这里要钉的是
 * <b>闸的边界算术</b>：段的秒数必须正好落在"16 小节 vs 16 小节 + 半小节"上，
 * 而"尾奏段/循环段各几小节"在闸眼里就是 {@code windowSecs} 与那份循环决策本身——
 * 直接从这两个数出发，边界才是<strong>构造出来的</strong>，不是碰上的。
 * 三段路径那三段的<b>渲染</b>（按顺序播、重复段逐位相同、旋转）是
 * {@code AutoCueSegmentedPathTest} / {@code AutoCueSegmentRotationTest} 钉的，这里一条都不重复。
 *
 * <ul>
 *   <li>① {@link #aSixteenBarASidePlusAnEightBarBedIsStillAThreeSegmentPath}：
 *       <b>A 侧正好 16 小节 + B 的床 8 小节 ×2 ⇒ 必须成立</b>（改之前会被总闸挡掉 ⇒ 红）；</li>
 *   <li>② {@link #aSeventeenBarASideIsStillRefusedEvenThoughTheBedIsCountedSeparately}：
 *       <b>A 侧 17 小节 ⇒ 必须不成立</b>（"上限还是上限"那一半；这条<b>改动前后都是绿</b>，
 *       它防的是把闸整个拆掉——见下）；</li>
 *   <li>③ {@link #aWindowLongerThanTrackAIsStillRefusedByThePhysicalConstraint}：
 *       {@code 整窗 ≤ A 的全曲时长}那条<b>物理约束被保留</b>（A 侧的素材得真的存在）；</li>
 *   <li>④ {@link #theDebugLineReportsTheNewBudgetSemantics}：
 *       <b>日志里的数按新口径说话</b>（否则读日志的人会把"总窗"当成被上限挡住的那个量）。</li>
 * </ul>
 */
public class AutoCueSegmentPathBudgetTest {

    /** 合成素材的 BPM：与这个包里别的合成夹具同一把尺子（一小节 4 拍 = 1.875 s）。 */
    private static final double BPM = 128.0;

    /** 一小节的秒数（A 与 B 同 BPM，所以两侧的小节尺子在这条判据里是同一把）。 */
    private static final double BAR_SECS = 4.0 * 60.0 / BPM;

    /** 上限换算成秒：16 × 1.875 = 30.0 s。 */
    private static final double BUDGET_SECS = AutoCue.INTRO_BED_PATH_MAX_BARS * BAR_SECS;

    // ==================================================================
    // 判据一：A 侧正好 16 小节 + B 的床 8 小节 ×2 ⇒ 成立
    // ==================================================================

    /**
     * <b>A 侧正好 16 小节（= 上限）+ B 的落床段 8 小节 ×2 ⇒ 这条三段路径必须成立</b>。
     *
     * <p>算术（全部按 A 的小节尺子 1.875 s）：
     *
     * <pre>
     *   窗口        15.0 s → 尾奏段 round(15.0 / 1.875) = 8 小节
     *   循环决策    4 小节 × 2 遍            =  8 小节
     *   ─────────────────────────────────────────────
     *   A 侧        8 + 8                    = 16 小节 = 30.00 s   ← 正好等于上限
     *   B 的落床段  8 小节 × 2 遍            = 30.00 s              ← 不计入那 16 小节
     *   整窗        30.00 + 30.00            = 60.00 s
     * </pre>
     *
     * <p>改之前那条闸量的是整窗：{@code 60.00 > 30.00} ⇒ 两遍、一遍都不成立 ⇒ 返回
     * {@code null}（判据这里红在"路径必须成立"上）。改之后上限只封 A 侧那 30.00 s
     * （正好压线，{@code >} 不是 {@code ≥}），B 的床照落 2 遍。
     *
     * <p>整窗 60 s 仍然要过"不许比 A 的全曲长"那条物理约束——素材 A 因此给了 90.0 s。
     */
    @Test
    public void aSixteenBarASidePlusAnEightBarBedIsStillAThreeSegmentPath() {
        // A 侧 = (8 + 4×2) = 16 小节 = 30.00 s；B 的床 = 8 小节 = 15.00 s ×2 = 30.00 s
        List<AutoCue.Segment> path = bedPath(/*loopBars=*/4, /*loopRepeats=*/2,
                /*windowSecs=*/15.0, /*vampFrom=*/75.0, /*aSecs=*/90.0, /*bSecs=*/90.0,
                /*bedBars=*/8.0);

        assertNotNull("A 侧正好 16 小节（= 上限 30.00 s）+ B 的床 8 小节 ×2（30.00 s）"
                + "这条三段路径必须成立——上限只封 A 的贡献，B 的床单算", path);
        assertEquals("路径应当是三段（A 的尾奏段 + A 的循环段 ×repeats + B 的落床段 ×n）",
                3, path.size());

        AutoCue.Segment tail = path.get(0);
        AutoCue.Segment loop = path.get(1);
        AutoCue.Segment bed = path.get(2);
        double v0 = AutoCue.snapToBar(75.0, phaseOf(BPM, 90.0), BAR_SECS);
        assertEquals("第一段该是 A 的尾奏段（8 小节）", 8.0, tail.spanSecs() / BAR_SECS, 1e-9);
        assertEquals("第二段该是 A 的循环段（4 小节）", 4.0, loop.spanSecs() / BAR_SECS, 1e-9);
        assertEquals("第二段的重复遍数该是那份循环决策的 2 遍", 2, loop.repeat);
        assertEquals("第一段该正好压在循环段的起点上", v0, tail.endSecs, 1e-9);
        assertEquals("第三段该是 B 的落床段（8 小节）", 8.0, bed.spanSecs() / BAR_SECS, 1e-9);
        assertTrue("第三段该是进场曲的素材", bed.fromIntro());
        assertEquals("B 的床该落满 2 遍（它的长度不从那 16 小节里扣，所以两遍都落得下）",
                AutoCue.LOOP_MAX_REPEATS, bed.repeat);
        assertFalse("这一段不该带旋转（判据没给旋转点）", bed.rotated());

        // ── 两个口径的算术都摆在这里（红-前那一次就是第一句的对照）────────────
        double aSide = (tail.spanSecs() + loop.repeat * loop.spanSecs());
        double window = aSide + bed.repeat * bed.spanSecs();
        assertEquals("A 侧该正好是 16 小节 = 30.00 s", 30.0, aSide, 1e-9);
        assertEquals("A 侧该正好压在上限上（16 × 1.875 = 30.00 s）", BUDGET_SECS, aSide, 1e-9);
        assertFalse("A 侧不该超过上限（正好压线要放行）", aSide > BUDGET_SECS);
        assertEquals("整窗该是 A 侧 + B 的床两遍 = 60.00 s", 60.0, window, 1e-9);
        assertTrue("整窗 60.00 s 是上限 30.00 s 的两倍——老口径（量整窗）下这一对必然被拒，"
                        + "这正是这条判据改之前红的原因",
                window > BUDGET_SECS);
    }

    // ==================================================================
    // 判据二：A 侧 17 小节 ⇒ 仍然不成立（上限还是上限）
    // ==================================================================

    /**
     * <b>A 侧 17 小节 ⇒ 仍然不成立</b>——这是"上限还是上限"那一半，也是真素材
     * {@code qplayer-demo-A → qplayer-demo-B} 那个形状（实测：窗口 2.5 s ⇒ 尾奏段 1 小节、
     * 循环段 8 小节 × 2 遍）。
     *
     * <pre>
     *   窗口        2.5 s → 尾奏段 round(2.5 / 1.875) = 1 小节
     *   循环决策    8 小节 × 2 遍            = 16 小节
     *   ─────────────────────────────────────────────
     *   A 侧        1 + 16                   = 17 小节 = 31.875 s  ← 超过上限 30.00 s
     *   B 的落床段  8 小节                   = 15.00 s
     * </pre>
     *
     * <p><b>这条判据改动前后都是绿</b>（如实记：它<b>没有</b>红过，也不该红）——老口径量整窗
     * （{@code 31.875 + 15.00 = 46.875 > 30.00} ⇒ 拒），新口径量 A 侧
     * （{@code 31.875 > 30.00} ⇒ 拒），两次都由 A 侧自己顶破上限。它在这里的作用是
     * <strong>防"把闸整个拆掉"</strong>：只留判据一，把这条闸删干净也能全绿。
     *
     * <p>同一条闸下的对照（第二个断言块）：把循环决策降到 <b>1 遍</b>（A 侧 9 小节）
     * 这条路径立刻成立——于是"拒"这件事确实出在 A 侧小节数上，
     * 而不是这套夹具自己有毛病。
     */
    @Test
    public void aSeventeenBarASideIsStillRefusedEvenThoughTheBedIsCountedSeparately() {
        // A 侧 = 1 + 8×2 = 17 小节 = 31.875 s > 30.00 s
        List<AutoCue.Segment> path = bedPath(/*loopBars=*/8, /*loopRepeats=*/2,
                /*windowSecs=*/2.5, /*vampFrom=*/75.0, /*aSecs=*/90.0, /*bSecs=*/90.0,
                /*bedBars=*/8.0);
        assertNull("A 侧 17 小节（31.875 s）超过上限 16 小节（30.00 s）——上限还是上限，"
                + "B 的床不计入这件事救不了它", path);

        double aSide = (1 + 8 * 2) * BAR_SECS;
        assertEquals("A 侧该是 31.875 s", 31.875, aSide, 1e-9);
        assertTrue("A 侧 31.875 s 该超过上限 30.00 s", aSide > BUDGET_SECS);

        // ── 对照：同一条闸、同一份几何，只把 A 侧降到 9 小节 ⇒ 成立 ──────────────
        // （循环段 8 小节 × 1 遍 = 8 小节，尾奏段仍是 1 小节 ⇒ 9 小节 = 16.875 s；
        //   v0 往前挪到 64.0 s 是为了让循环段的终点仍在 A 的全曲之内。
        //   **床取 2 小节**是有意的：这样"整窗 ≤ 30 s"与"A 侧 ≤ 30 s"两个口径都放行
        //   （16.875 + 2×3.75 = 24.375 s），于是这条对照改动前后**都是绿**——
        //   它证的是"拒"出在 A 侧小节数上，而不是也靠改口径才成立。）
        List<AutoCue.Segment> ok = bedPath(/*loopBars=*/8, /*loopRepeats=*/1,
                /*windowSecs=*/2.5, /*vampFrom=*/64.0, /*aSecs=*/90.0, /*bSecs=*/90.0,
                /*bedBars=*/2.0);
        assertNotNull("同一条闸下把 A 侧降到 9 小节就成立——说明上面那条 null 是 A 侧小节数"
                + "顶破上限，不是这套夹具有毛病", ok);
        assertEquals("对照那一条 A 侧该是 9 小节 = 16.875 s",
                16.875, ok.get(0).spanSecs() + ok.get(1).repeat * ok.get(1).spanSecs(), 1e-9);
        assertEquals("对照那一条 B 的床仍该落满 2 遍（整窗 24.375 s ≤ A 全曲 90 s）",
                AutoCue.LOOP_MAX_REPEATS, ok.get(2).repeat);
    }

    // ==================================================================
    // 判据三：整窗不许比 A 的全曲长（物理约束被保留）
    // ==================================================================

    /**
     * <b>{@code 整窗 ≤ A 的全曲时长}那条物理约束没有被这次改口径抹掉</b>。
     *
     * <p>素材给成"A 的全曲正好就是 A 侧那 16 小节"（30.0 s），于是：
     *
     * <pre>
     *   A 侧        8 + 4×2 = 16 小节 = 30.00 s  ≤ 上限 30.00 s   ← 闸①放行（压线）
     *   整窗 2 遍   30.00 + 2×15.00 = 60.00 s    > A 全曲 30.00 s  ← 闸②拒
     *   整窗 1 遍   30.00 + 1×15.00 = 45.00 s    > A 全曲 30.00 s  ← 闸②拒（退到 1 遍也不行）
     * </pre>
     *
     * <p>于是返回 {@code null}：<b>A 侧的素材得真的存在</b>——渲染层自己也按这条退
     * （{@code AutoCueTransition.prepareLoopOutgoing} 里 {@code windowTotal > duration}
     * 就退回普通交叉淡化），计划层先按同一句话挡住，免得产出一条渲染层会拒绝的路径。
     *
     * <p><b>这条判据改动前后都是绿</b>（老口径下两遍与一遍也都被挡）。
     */
    @Test
    public void aWindowLongerThanTrackAIsStillRefusedByThePhysicalConstraint() {
        List<AutoCue.Segment> path = bedPath(/*loopBars=*/4, /*loopRepeats=*/2,
                /*windowSecs=*/15.0, /*vampFrom=*/15.0, /*aSecs=*/30.0, /*bSecs=*/90.0,
                /*bedBars=*/8.0);
        assertNull("整窗 45.00 s（最少一遍）比 A 的全曲 30.00 s 还长——这条物理约束必须保留",
                path);

        // 把上面那条算术摆出来：A 侧自己是放行的（压线），被拒的是整窗
        double aSide = (8 + 4 * 2) * BAR_SECS;
        double onePass = aSide + 8 * BAR_SECS;
        assertEquals("A 侧该正好压在上限上（16 小节 = 30.00 s）", BUDGET_SECS, aSide, 1e-9);
        assertFalse("A 侧自己不该超过上限——被拒的必须只是整窗那条物理约束",
                aSide > BUDGET_SECS);
        assertEquals("最少一遍的整窗该是 45.00 s", 45.0, onePass, 1e-9);
        assertTrue("整窗 45.00 s 该超过 A 的全曲 30.00 s", onePass > 30.0);
    }

    // ==================================================================
    // 判据四：日志按新口径说话
    // ==================================================================

    /**
     * <b>被这条闸挡下时，日志里的数必须按新口径说话</b>——否则读日志的人会把"整窗"
     * 当成被上限挡住的那个量，算出来的结论正好相反。
     *
     * <p>改之前那行是「三段路径：总窗放不下——A 侧 17 小节（尾奏段 1 + 循环段 8×2）
     * + B 的落床段 8.00 小节，最少也要 46.88s，而上限是 16 小节（30.0s）」：
     * 把 B 的床也加进去的那 46.88 s 拿去与"上限 16 小节"比，这**正是**用户列表里第 ④ 项
     * 要改掉的那件事（读日志的人会以为"是床把预算顶破的"，而事实是 A 侧自己就 17 小节）。
     *
     * <p>所以这里钉两句：A 侧超上限那一支必须报<b>A 侧的小节数</b>与<b>上限</b>，
     * 并说清 B 的床<b>不计入</b>；整窗超 A 全曲那一支必须报<b>A 的全曲</b>，
     * 一个字都不许再提那 16 小节。
     *
     * <p><b>红-前：这条是红的</b>（改之前那一支的字面是"总窗放不下…而上限是 16 小节"，
     * 没有"不计入"这三个字，而在整窗那一支它也在报"上限是 16 小节"）。
     */
    @Test
    public void theDebugLineReportsTheNewBudgetSemantics() {
        // ── A 侧超上限那一支（判据二的 17 小节形状）────────────────────────────
        List<String> lines = bedPathLogLines(/*loopBars=*/8, /*loopRepeats=*/2,
                /*windowSecs=*/2.5, /*vampFrom=*/75.0, /*aSecs=*/90.0, /*bSecs=*/90.0,
                /*bedBars=*/8.0);
        assertEquals("A 侧超上限这一段应当留下一行日志（日志是这一档唯一可观测的产物）",
                1, lines.size());
        String aSideLine = lines.get(0);
        assertTrue("日志该说清 B 的落床段**不计入**这条上限（否则读日志的人会把床也算进预算）："
                + aSideLine, aSideLine.contains("不计入"));
        assertFalse("日志不该再说「总窗放不下」——被这条闸挡住的不是总窗：" + aSideLine,
                aSideLine.contains("总窗放不下"));
        assertTrue("日志该报 A 侧的小节数（这是新口径被量的那个量）：" + aSideLine,
                aSideLine.contains("A 侧 17 小节"));
        assertTrue("日志该报上限是 16 小节：" + aSideLine, aSideLine.contains("上限 16.0 小节"));

        // ── 整窗超 A 全曲那一支（判据三的形状）────────────────────────────────
        List<String> phys = bedPathLogLines(/*loopBars=*/4, /*loopRepeats=*/2,
                /*windowSecs=*/15.0, /*vampFrom=*/15.0, /*aSecs=*/30.0, /*bSecs=*/90.0,
                /*bedBars=*/8.0);
        assertEquals("整窗超 A 全曲这一段应当留下一行日志", 1, phys.size());
        String physLine = phys.get(0);
        assertTrue("日志该报 A 的全曲时长（这一支挡的是它，不是那 16 小节）：" + physLine,
                physLine.contains("A 的全曲"));
        assertFalse("这一支一个字都不该提那 16 小节的上限（那会让人以为是被预算挡的）："
                + physLine, physLine.contains("上限 16"));
    }

    // ==================================================================
    // 夹具：小节栅格包络（{@code envelopeOf} 直接造，不合成 PCM）
    // ==================================================================

    /**
     * 走那条闸（{@link AutoCue#introBedPath}），把三段路径取出来。
     *
     * <p>为什么要直接问这条闸，而不是走 {@code planWithLyricSpans}：这一档要钉的是
     * <b>闸的边界算术</b>——"尾奏段几小节 / 循环段几小节×几遍"在闸眼里就是
     * {@code windowSecs} 与那份循环决策（{@link AutoCue.OutroLoopPlan}）本身，
     * 直接喂这两个数，边界（16 小节 vs 17 小节 vs 16 小节 + 半小节）才是构造出来的。
     * 走打分那条路就只能挑素材形状去"碰上"边界，而"碰不上"的表现是判据变得什么都没测到。
     * 三段路径在<b>生产</b>路径上真的会被选中这件事由 {@code AutoCueSegmentRotationTest} 钉着
     * （那边是合成 PCM + 假解码器 + 消费 {@code Cue.segments} 的全链）。
     *
     * @return 三段路径；闸不收就 {@code null}
     */
    private static List<AutoCue.Segment> bedPath(int loopBars, int loopRepeats, double windowSecs,
                                                 double vampFrom, double aSecs, double bSecs,
                                                 double bedBars) {
        AutoCue.Envelope a = grid(BPM, aSecs);
        AutoCue.Envelope b = grid(BPM, bSecs);
        double barA = 4.0 * 60.0 / BPM;
        double barB = 4.0 * 60.0 / BPM;
        double phaseA = AutoCue.barPhaseOf(a, barA);
        double phaseB = AutoCue.barPhaseOf(b, barB);
        assertEquals("这套夹具的小节相位该是 0（每个小节头上一个起音）", 0.0, phaseA, 1e-9);
        assertEquals("B 那一侧同理", 0.0, phaseB, 1e-9);
        // 尾奏 vamp 与落床段都给成"自己那一侧的小节线"上的整数小节（与 introBedSpan /
        // outroVampSpan 的产物同一形状）
        double v0 = AutoCue.snapToBar(vampFrom, phaseA, barA);
        AutoCue.VampSpan vamp = new AutoCue.VampSpan(v0, v0 + loopBars * barA, 1.0, 0.0, 0.0);
        AutoCue.OutroLoopPlan plan = new AutoCue.OutroLoopPlan(vamp, loopBars, loopRepeats,
                true, "判据：尾奏放不下这次窗口 ⇒ 买时间");
        AutoCue.VampSpan bed = new AutoCue.VampSpan(phaseB, phaseB + bedBars * barB,
                1.0, 0.0, 0.0);
        return AutoCue.introBedPath(a, b, vamp, plan, bed, windowSecs, Double.NaN);
    }

    /** 同上，但把诊断档里带「三段路径」的那几行捞出来（{@code AutoCueOutroLoopKeyClashTest} 同一套做法）。 */
    private static List<String> bedPathLogLines(int loopBars, int loopRepeats, double windowSecs,
                                                double vampFrom, double aSecs, double bSecs,
                                                double bedBars) {
        List<String> hits = new java.util.ArrayList<String>();
        boolean wasVerbose = dev.t1m3.qplayer.util.Logger.verbose();
        dev.t1m3.qplayer.util.Logger.setVerbose(true);
        dev.t1m3.qplayer.util.Logger.clear();
        try {
            bedPath(loopBars, loopRepeats, windowSecs, vampFrom, aSecs, bSecs, bedBars);
            for (String line : dev.t1m3.qplayer.util.Logger.snapshot()) {
                if (line.contains("三段路径")) {
                    hits.add(line);
                }
            }
        } finally {
            dev.t1m3.qplayer.util.Logger.clear();
            dev.t1m3.qplayer.util.Logger.setVerbose(wasVerbose);
        }
        return hits;
    }

    /** 这套夹具的小节相位（{@code AutoCue.barPhaseOf} 是那把唯一的尺子）。 */
    private static double phaseOf(double bpm, double durationSecs) {
        return AutoCue.barPhaseOf(grid(bpm, durationSecs), 4.0 * 60.0 / bpm);
    }

    /**
     * <b>一条带小节栅格的包络</b>：{@code durationSecs} 长、每小节头上有一个起音的合成 RMS。
     *
     * <p>形状：每小节的头 {@value #HIGH_HOPS} 个 hop（0.12 s）抬到 1.0、其余 0.2。
     * 为什么不是"一个 hop 的尖峰"：{@code envelopeOf} 按 <b>95 分位</b>归一化，
     * 尖峰只占 1% 的 hop 时参考值会落在低电平上、整条包络被削平（起音一个都不剩）。
     * 6 个 hop 占一小节的 6.4%（&gt; 5%）⇒ 参考值就是那个高电平，于是每小节线上恰好留下
     * 一个正向跳变（{@code onset}），{@link AutoCue#barPhaseOf} 给相位 0
     * （判据里直接断言这一点，不是假设）。
     *
     * <p>用 {@code envelopeOf} 而不是合成 PCM：这条判据要的是<b>小节栅格与时长可控</b>，
     * 不是音频样本（"渲染层按路径读素材"那件事在别的判据里逐位比）。
     */
    private static AutoCue.Envelope grid(double bpm, double durationSecs) {
        double bar = 4.0 * 60.0 / bpm;
        int hops = (int) Math.round(durationSecs / AutoCue.HOP_SECS);
        float[] rms = new float[hops];
        for (int h = 0; h < hops; h++) {
            double inBar = h * AutoCue.HOP_SECS - Math.floor(h * AutoCue.HOP_SECS / bar) * bar;
            rms[h] = inBar < HIGH_HOPS * AutoCue.HOP_SECS ? 1f : 0.2f;
        }
        return AutoCue.envelopeOf(rms, durationSecs,
                new Analysis.Tempo((float) bpm, 1f, 0f, 0.9f), 4000);
    }

    /** 每小节头上的高电平宽度（hop）：0.12 s。见 {@link #grid} 里的 95 分位那条理由。 */
    private static final int HIGH_HOPS = 6;
}
