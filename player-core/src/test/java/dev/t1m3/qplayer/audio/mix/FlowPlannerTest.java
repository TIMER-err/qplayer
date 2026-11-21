package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link FlowPlanner} 的测试。
 *
 * <p>本模块是<strong>纯逻辑</strong>（进一串特征、出一串下标），所以这里的断言可以
 * 钉到具体数值上，不必去解码任何音频。要钉的是四件事：
 *
 * <ol>
 *   <li><strong>衔接代价的公式</strong>：1:1 与 1:2 两个「免费档」、倍数差怎么换算、
 *       响度项的量级，以及两侧任一项测不出时那一项记 0（不猜）。</li>
 *   <li><strong>兴趣曲线的形状</strong>：开头中低、尾段高潮、最后收下来。</li>
 *   <li><strong>没特征的位原地不动</strong>，有特征的位只在它们原本占的位置之间重排。</li>
 *   <li><strong>可复现</strong>：同样输入两次得到同样输出；并列时取原顺序靠前的那个。</li>
 * </ol>
 */
public class FlowPlannerTest {

    /** 造一个只有速度与响度、没有音色信息的特征（音色项 0 分，权重 0.2 不参与区分）。 */
    private static FlowFeatures feat(float tempo, float level) {
        return new FlowFeatures(tempo, tempo, tempo, level, level, level, 0.0f, 180.0);
    }

    /** 头尾可以不同的特征（用来钉「接进来」与「交出去」用的是不同的那一端）。 */
    private static FlowFeatures edges(float headTempo, float tailTempo,
                                      float headLevel, float tailLevel) {
        return new FlowFeatures(headTempo, headTempo, tailTempo,
                headLevel, headLevel, tailLevel, 0.0f, 180.0);
    }

    private static List<FlowFeatures> list(FlowFeatures... items) {
        List<FlowFeatures> out = new ArrayList<>(items.length);
        Collections.addAll(out, items);
        return out;
    }

    // ---- 衔接代价 ---------------------------------------------------------

    /**
     * 1:1 与 1:2 都拿满分（代价 0）。
     *
     * <p>这是整个排序算法里最容易被「顺手优化」掉的一条：只看
     * {@code |r − 1|} 的话，60 BPM 的慢歌在 120 BPM 的快歌旁边会被判成
     * 「差了整整一倍」，而它们在混音里其实可以互为倍速直接叠上。
     */
    @Test
    public void transitionCostIsFreeAtOneToOneAndTwoToOne() {
        FlowFeatures slow = feat(60.0f, 0.10f);
        FlowFeatures fast = feat(120.0f, 0.10f);
        FlowFeatures same = feat(120.0f, 0.10f);

        assertEquals("同速应当完全免费", 0.0, FlowPlanner.transitionCost(same, fast), 1e-9);
        assertEquals("60 接 120（1:2）应当完全免费", 0.0, FlowPlanner.transitionCost(slow, fast), 1e-9);
        assertEquals("120 接 60（2:1）应当完全免费", 0.0, FlowPlanner.transitionCost(fast, slow), 1e-9);
    }

    /** 1.5 倍是最差的倍数关系：两个「免费档」各差 0.5，代价 50。 */
    @Test
    public void transitionCostPeaksAtOneAndAHalfTimes() {
        FlowFeatures a = feat(100.0f, 0.10f);
        FlowFeatures b = feat(150.0f, 0.10f);
        // min(|1.5−1|, |1.5−2|) = 0.5 → 0.5 × 100 = 50，响度相同所以响度项为 0
        assertEquals(50.0, FlowPlanner.transitionCost(a, b), 1e-4);
    }

    /** 响度项：差值 × 50 × 0.5，速度相同所以速度项为 0。 */
    @Test
    public void transitionCostMeasuresTheLevelJump() {
        FlowFeatures quiet = feat(120.0f, 0.10f);
        FlowFeatures loud = feat(120.0f, 0.30f);
        // |0.30 − 0.10| × 50 × 0.5 = 5
        assertEquals(5.0, FlowPlanner.transitionCost(quiet, loud), 1e-4);
        assertEquals(5.0, FlowPlanner.transitionCost(loud, quiet), 1e-4);
    }

    /** 前面没有歌（列表第一位 / 没给锚点）不产生代价。 */
    @Test
    public void transitionCostIsZeroWithoutAPredecessor() {
        assertEquals(0.0, FlowPlanner.transitionCost(null, feat(120.0f, 0.5f)), 1e-9);
    }

    /**
     * 任一侧速度<strong>测不出</strong>时，速度项记 0，响度项照算。
     *
     * <p>这是「不许猜」那条纪律在代价函数里的落点：拿 0 当「很慢」会稳定地把静音/自由速度
     * 的曲子推向曲线低点，那是个编出来的结论。
     */
    @Test
    public void transitionCostKeepsTheLevelTermWhenTempoIsUnknown() {
        FlowFeatures unknown = feat(0.0f, 0.10f);
        FlowFeatures known = feat(140.0f, 0.30f);
        // 速度项 0（不知道），只剩响度项 |0.30 − 0.10| × 50 × 0.5 = 5
        assertEquals(5.0, FlowPlanner.transitionCost(unknown, known), 1e-4);
        assertEquals(5.0, FlowPlanner.transitionCost(known, unknown), 1e-4);
    }

    /**
     * 前面那首的<strong>结尾段整个没读到</strong>（{@link FlowAnalyzer} 里尾部读取失败）
     * 时，出去的这一侧两项都不知道，代价记 0。
     *
     * <p>这是可达的真实状态：没有总长头部的 CBR MP3 定位不到尾部，
     * 这一首的「它怎么结束的」就是彻底未知。此时对它的下一首
     * <strong>不加任何衔接惩罚</strong>——因为我们没有任何依据说它接得不好。
     * 编一个惩罚（比如「未知算最差」）会让所有这类曲目被推到队列两头，
     * 而原因跟它们的音乐毫无关系。
     */
    @Test
    public void transitionCostIsZeroWhenTheOutgoingSideIsEntirelyUnknown() {
        FlowFeatures noTail = new FlowFeatures(120.0f, 120.0f, 0.0f,
                0.10f, 0.10f, Float.NaN, 0.0f, 180.0);
        FlowFeatures next = feat(100.0f, 0.40f);
        assertEquals(0.0, FlowPlanner.transitionCost(noTail, next), 1e-9);
    }

    /**
     * 结尾段读到了、但<strong>速度测不出</strong>（自由速度/渐弱）：速度项记 0，
     * 响度项照算。
     *
     * <p>这两个「不知道」是独立的：响度一定测得出（有 PCM 就有 RMS），
     * 速度不一定。把它们绑在一起（「速度没有就整项跳过」）会白白丢掉一个
     * 本来有效的判断依据。
     */
    @Test
    public void transitionCostKeepsTheLevelTermWhenTheOutgoingTempoIsUnmeasurable() {
        FlowFeatures noTempoTail = new FlowFeatures(120.0f, 120.0f, 0.0f,
                0.10f, 0.10f, 0.30f, 0.0f, 180.0);
        FlowFeatures next = feat(100.0f, 0.40f);
        // 速度项 0（结尾速度测不出），响度项 |0.40 − 0.30| × 50 × 0.5 = 2.5
        assertEquals(2.5, FlowPlanner.transitionCost(noTempoTail, next), 1e-4);
    }

    /**
     * 结尾<strong>响度</strong>是 NaN（这一段的响度不存在）时，响度项记 0，
     * 速度项照算。
     *
     * <p>NaN 与 0 在这里是两件不同的事，而代价函数必须分清：响度真的可能为 0
     * （静音结尾），拿 0 当「没有」会让所有「结尾很安静」的曲子被当成
     * 「结尾未知」——那会把一个真实的特征当成缺失，白丢掉一个判断依据。
     *
     * <p>{@link FlowAnalyzer} 不会单独产出「结尾速度有、结尾响度是 NaN」这一组合
     * （两者一起来自同一次尾部读取），这里钉的是<strong>公式</strong>对这两个维度的
     * 独立处理，所以用值类型手工构造。
     */
    @Test
    public void transitionCostKeepsTheTempoTermWhenTheOutgoingLevelIsUnknown() {
        FlowFeatures noLevelTail = new FlowFeatures(120.0f, 120.0f, 110.0f,
                0.10f, 0.10f, Float.NaN, 0.0f, 180.0);
        FlowFeatures next = feat(100.0f, 0.40f);
        // 速度项：110 vs 100 → 倍数 1.1 → min(0.1, 0.9) × 100 = 10
        // 响度项：0（结尾响度不存在）
        assertEquals(10.0, FlowPlanner.transitionCost(noLevelTail, next), 1e-4);
    }

    /**
     * 接缝用的是「前一首的结尾」与「后一首的开头」，不是「整首歌的平均」。
     *
     * <p>一首从 90 渐快到 140 的歌：交给下一首的是 <strong>140</strong>，
     * 拿它自己的平均速度去算，任何真正的接缝都会被算错。
     */
    @Test
    public void transitionCostUsesTheSeamEndsNotTheWholeTrackAverage() {
        FlowFeatures outgoing = edges(90.0f, 140.0f, 0.10f, 0.10f);
        FlowFeatures incoming = edges(140.0f, 40.0f, 0.10f, 0.10f);
        assertEquals("140 接 140 应当免费", 0.0, FlowPlanner.transitionCost(outgoing, incoming), 1e-9);

        FlowFeatures wrongEnd = edges(40.0f, 40.0f, 0.10f, 0.10f);
        assertTrue("接进来的是 140，上一首交出 40 就不该免费",
                FlowPlanner.transitionCost(wrongEnd, incoming) > 0.0);
    }

    // ---- 兴趣曲线 ---------------------------------------------------------

    /** 曲线两端都低（引子要抓耳但克制、收尾要落下来），最高点在后段。 */
    @Test
    public void idealCurveRisesToAClimaxNearTheEndThenDrops() {
        int n = 11;
        float[] curve = FlowPlanner.idealCurve(n);
        assertEquals(n, curve.length);

        int peak = 0;
        for (int i = 1; i < n; i++) {
            if (curve[i] > curve[peak]) {
                peak = i;
            }
        }
        assertEquals("第 80% 那一档应当是全场最高（i/(n-1) = 0.8）", 8, peak);
        // 控制点 (0.80, 1.00) → 1.00 × 80 + 10 = 90
        assertEquals(90.0f, curve[peak], 1e-3f);
        // 第一位：(0.00, 0.10) → 0.10 × 80 + 10 = 18
        assertEquals(18.0f, curve[0], 1e-3f);
        // 最后一位：(1.00, 0.05) → 14，比开场还低 —— 「迅速而温暖地结束」
        assertEquals(14.0f, curve[n - 1], 1e-3f);
        assertTrue("收尾应当低于开场", curve[n - 1] < curve[0]);
    }

    /** 单曲歌单取曲线中点，而不是除以 0 得到 NaN。 */
    @Test
    public void idealCurveOfASingleSongIsTheMidpoint() {
        float[] one = FlowPlanner.idealCurve(1);
        assertEquals(1, one.length);
        assertEquals(FlowPlanner.curveAt(0.5f) * 80.0f + 10.0f, one[0], 1e-4f);
        assertFalse(Float.isNaN(one[0]));
    }

    /** 空歌单给空曲线，不抛异常。 */
    @Test
    public void idealCurveOfNothingIsEmpty() {
        assertEquals(0, FlowPlanner.idealCurve(0).length);
        assertEquals(0, FlowPlanner.idealCurve(-3).length);
    }

    // ---- 排列 -------------------------------------------------------------

    /** 结果永远是一个置换，不会丢歌也不会重复。 */
    @Test
    public void planAlwaysReturnsAPermutation() {
        List<FlowFeatures> slots = list(
                feat(120.0f, 0.10f), feat(90.0f, 0.30f), feat(140.0f, 0.05f),
                feat(70.0f, 0.25f), feat(160.0f, 0.40f));
        FlowPlanner.Plan plan = FlowPlanner.plan(slots, null);

        assertEquals(slots.size(), plan.order.length);
        assertEquals(slots.size(), plan.movable);
        boolean[] seen = new boolean[slots.size()];
        for (int i = 0; i < plan.order.length; i++) {
            int src = plan.order[i];
            assertTrue("下标越界：" + src, src >= 0 && src < slots.size());
            assertFalse("同一首先后被放了两次：" + src, seen[src]);
            seen[src] = true;
        }
    }

    /**
     * 可复现：同一份输入排两次得到同一个顺序。
     *
     * <p>这条不是形式主义——排列里处处是「取最小」，一旦写成 {@code <=}
     * （并列取最后一个）或者引入随机数，用户就会发现同一份歌单点两次
     * 「一键排列」出来两个顺序，而且没有一次能说得清为什么。
     */
    @Test
    public void planIsDeterministic() {
        List<FlowFeatures> slots = list(
                feat(120.0f, 0.10f), feat(90.0f, 0.30f), feat(140.0f, 0.05f),
                feat(70.0f, 0.25f), feat(160.0f, 0.40f), feat(100.0f, 0.18f));
        FlowPlanner.Plan first = FlowPlanner.plan(slots, null);
        FlowPlanner.Plan second = FlowPlanner.plan(slots, null);
        assertSameOrder(first.order, second.order);
        assertEquals(first.cost, second.cost, 1e-9);
    }

    /** 完全相同的特征（处处并列）时取原顺序：不做无意义的乱动。 */
    @Test
    public void planKeepsTheOriginalOrderWhenEveryCandidateTies() {
        List<FlowFeatures> slots = list(
                feat(120.0f, 0.20f), feat(120.0f, 0.20f),
                feat(120.0f, 0.20f), feat(120.0f, 0.20f));
        FlowPlanner.Plan plan = FlowPlanner.plan(slots, null);
        assertTrue("处处并列时不该动任何东西：" + Arrays.toString(plan.order), plan.isIdentity());
    }

    /**
     * 排列确实让总代价变小了。
     *
     * <p>这条把「排完到底有没有变好」变成可验证的：{@link FlowPlanner#costOf}
     * 与原顺序用的是同一条曲线、同一个衔接代价，所以两个数可以直接比。
     */
    @Test
    public void planCostsLessThanTheOriginalOrder() {
        List<FlowFeatures> original = list(
                feat(160.0f, 0.40f), feat(70.0f, 0.04f), feat(155.0f, 0.38f),
                feat(72.0f, 0.05f), feat(150.0f, 0.36f), feat(75.0f, 0.06f));
        FlowPlanner.Plan plan = FlowPlanner.plan(original, null);

        List<FlowFeatures> reordered = new ArrayList<>(original.size());
        for (int i = 0; i < plan.order.length; i++) {
            reordered.add(original.get(plan.order[i]));
        }
        double before = FlowPlanner.costOf(original, null);
        double after = FlowPlanner.costOf(reordered, null);
        assertEquals("plan 自己报的代价应当与重算的一致", plan.cost, after, 1e-6);
        assertTrue("排完应当更顺：原 " + before + " → 现 " + after, after < before);
    }

    /**
     * 没特征的位<strong>原地不动</strong>，有特征的位只在它们原本占的位置之间重排。
     *
     * <p>这是本模块对「不许假装排好了」那条纪律的落点：既没把没分析出来的歌
     * 塞进顺序里，也没把它们挪到别处（挪走等于顺手改了用户自己的摆放）。
     */
    @Test
    public void planLeavesSlotsWithoutFeaturesExactlyWhereTheyAre() {
        List<FlowFeatures> slots = list(
                feat(160.0f, 0.40f),   // 0
                null,                  // 1 ← 没分析出来
                feat(70.0f, 0.04f),    // 2
                null,                  // 3 ← 没分析出来
                feat(150.0f, 0.36f),   // 4
                null,                  // 5 ← 没分析出来
                feat(75.0f, 0.06f));   // 6
        FlowPlanner.Plan plan = FlowPlanner.plan(slots, null);

        assertEquals("只有 4 首参与排列", 4, plan.movable);
        assertEquals(1, plan.order[1]);
        assertEquals(3, plan.order[3]);
        assertEquals(5, plan.order[5]);

        // 有特征的曲目只在 {0, 2, 4, 6} 之间重排
        int[] movableSlots = {0, 2, 4, 6};
        boolean[] seen = new boolean[slots.size()];
        for (int slot : movableSlots) {
            int src = plan.order[slot];
            assertTrue("位 " + slot + " 收到了一个没特征的位：" + src,
                    slots.get(src) != null);
            assertFalse("同一首先后被放了两次", seen[src]);
            seen[src] = true;
        }
    }

    /** 一首（或零首）有特征的曲目：排不动是一个正常结论，不是失败。 */
    @Test
    public void planWithFewerThanTwoFeaturesDoesNothing() {
        FlowPlanner.Plan one = FlowPlanner.plan(list(null, feat(120.0f, 0.2f), null), null);
        assertEquals(1, one.movable);
        assertTrue(one.isIdentity());

        FlowPlanner.Plan none = FlowPlanner.plan(list(null, null), null);
        assertEquals(0, none.movable);
        assertTrue(none.isIdentity());

        FlowPlanner.Plan empty = FlowPlanner.plan(new ArrayList<FlowFeatures>(), null);
        assertEquals(0, empty.movable);
        assertEquals(0, empty.order.length);
    }

    /** 曲线与实测曲线同长、同下标对齐，界面可以直接并排画出来。 */
    @Test
    public void planExposesBothCurvesAlignedWithTheMovableSlotOrder() {
        List<FlowFeatures> slots = list(
                feat(160.0f, 0.40f), feat(70.0f, 0.04f), feat(150.0f, 0.36f),
                feat(75.0f, 0.06f), feat(120.0f, 0.20f));
        FlowPlanner.Plan plan = FlowPlanner.plan(slots, null);
        assertEquals(plan.movable, plan.idealCurve.length);
        assertEquals(plan.movable, plan.actualCurve.length);
        for (float v : plan.actualCurve) {
            assertTrue("贴合分应当落在 0..100：" + v, v >= 0.0f && v <= 100.0f);
        }
    }

    // ---- 锚点（正在播的那一首） -------------------------------------------

    /**
     * 锚点参与第一首的选择，而且能盖过一次情绪贴合上的小劣势。
     *
     * <p>这是 LiveSort 没有的一步（它排的是还没开始听的歌单），
     * 而对 QPlayer 恰恰是最要紧的一处接缝：正在播的那一首 → 排在第一的那一首。
     *
     * <p>构造上刻意让两件事对着干：
     *
     * <ul>
     *   <li>{@code fit} 上「接不上的」反而更合适——它的情绪贴合分比「接得上的」低 10 分，
     *       按权重折成 15 分的差距。</li>
     *   <li>但「接不上的」要从锚点的 120 BPM 接到 90 BPM（1.33 倍），衔接代价 33.3 分。</li>
     * </ul>
     *
     * <p>33.3 &gt; 15，所以锚点赢。不给锚点时走的仍是纯情绪贴合（LiveSort 的规则），
     * 于是选中另一个——两条规则的差别因此是<strong>可观察</strong>的，不是注释里的一句话。
     */
    @Test
    public void anchorDecidesBetweenEquallyWellFittingOpeners() {
        // 锚点：结尾 120 BPM、响度 0.20
        FlowFeatures anchor = edges(120.0f, 120.0f, 0.20f, 0.20f);
        // 接得上的：与锚点同速，衔接代价 0
        FlowFeatures smooth = edges(120.0f, 120.0f, 0.20f, 0.20f);
        // 接不上的：90 BPM，与 120 是 1.33 倍
        FlowFeatures jumpy = edges(90.0f, 90.0f, 0.20f, 0.20f);

        // 无锚点：纯情绪贴合 → 90 BPM 那首（贴合分更低，反而更接近曲线起点 18）
        FlowPlanner.Plan noAnchor = FlowPlanner.plan(list(jumpy, smooth), null);
        assertEquals("不给锚点时，起点由情绪贴合决定", 0, noAnchor.order[0]);

        // 有锚点：jumpy 要付 33.3 分的衔接代价，盖过它那 15 分的贴合优势
        FlowPlanner.Plan anchored = FlowPlanner.plan(list(jumpy, smooth), anchor);
        assertEquals("接得上锚点的那一首应当排第一", 1, anchored.order[0]);
    }

    /** 不给锚点就退化成 LiveSort 的行为（第一首纯看情绪贴合）。 */
    @Test
    public void withoutAnAnchorTheOpenerIsChosenByEmotionFitAlone() {
        List<FlowFeatures> slots = list(
                feat(160.0f, 0.40f), feat(70.0f, 0.04f), feat(120.0f, 0.20f));
        FlowPlanner.Plan plan = FlowPlanner.plan(slots, null);
        float[] fit = FlowPlanner.fitScores(slots, Arrays.asList(0, 1, 2));
        float[] ideal = FlowPlanner.idealCurve(3);
        int expected = 0;
        for (int i = 1; i < 3; i++) {
            if (Math.abs(fit[i] - ideal[0]) < Math.abs(fit[expected] - ideal[0])) {
                expected = i;
            }
        }
        assertEquals("起点应当是贴合分最接近曲线第一位的那一首", expected, plan.order[0]);
    }

    // ---- 贴合分 -----------------------------------------------------------

    /**
     * 贴合分是<strong>这一批里的相对值</strong>：三项各自除以本批最大值再加权。
     *
     * <p>绝对刻度无法同时容纳古典（质心低、响度小）与电子（质心高、响度大）——
     * 一批安静的歌里，相对更快更亮的那些才该是这一批的高潮点。
     */
    @Test
    public void fitScoresAreRelativeToTheBatchMaximum() {
        List<FlowFeatures> slots = list(
                new FlowFeatures(120.0f, 120.0f, 120.0f, 0.20f, 0.20f, 0.20f, 4000.0f, 180.0),
                new FlowFeatures(60.0f, 60.0f, 60.0f, 0.10f, 0.10f, 0.10f, 2000.0f, 180.0));
        float[] fit = FlowPlanner.fitScores(slots, Arrays.asList(0, 1));

        // 第一首三项都是本批最大 → 0.4 + 0.4 + 0.2 = 1.0 → 100
        assertEquals(100.0f, fit[0], 1e-3f);
        // 第二首三项都是本批一半 → 0.5 → 50
        assertEquals(50.0f, fit[1], 1e-3f);
    }

    /**
     * 速度整批都测不出时，速度项对谁都拿 0 分，而不是除出 NaN。
     *
     * <p>同分不是一个好结果，但它至少是<strong>可解释</strong>的：
     * 这一批里没有速度信息，区分只能靠响度与音色。除成 NaN 会让所有比较
     * 都是 false，于是「取最小」永远停在第一个候选上——顺序看起来正常，
     * 实际上排序根本没生效，而且没有任何迹象。
     */
    @Test
    public void fitScoresStayFiniteWhenNoTempoWasMeasured() {
        List<FlowFeatures> slots = list(
                new FlowFeatures(0.0f, 0.0f, 0.0f, 0.20f, 0.20f, 0.20f, 0.0f, 180.0),
                new FlowFeatures(0.0f, 0.0f, 0.0f, 0.10f, 0.10f, 0.10f, 0.0f, 180.0));
        float[] fit = FlowPlanner.fitScores(slots, Arrays.asList(0, 1));
        for (float v : fit) {
            assertFalse("不该出现 NaN", Float.isNaN(v));
            assertTrue(v >= 0.0f && v <= 100.0f);
        }
        assertTrue("这一批里响度大的应当分更高", fit[0] > fit[1]);
    }

    /**
     * 一首歌整体「更燃」（更快、更响、更亮）就该排在更靠后的位置。
     *
     * <p>这是从用户视角看得见的那条性质：点完「一键排列」，安静的在前、
     * 高潮在后。用 11 首拉开梯度，断言最安静的那首落在前三分之一、
     * 最燃的那首落在后半段。
     */
    @Test
    public void planPushesTheBiggestTrackTowardsTheClimax() {
        // 11 首：速度/响度/音色同步递增，正好对上 11 个曲线取样点
        List<FlowFeatures> slots = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            float t = 60.0f + (float) i * 8.0f;
            float l = 0.02f + (float) i * 0.03f;
            float tone = 500.0f + (float) i * 400.0f;
            slots.add(new FlowFeatures(t, t, t, l, l, l, tone, 180.0));
        }
        FlowPlanner.Plan plan = FlowPlanner.plan(slots, null);

        int quietestAt = indexOf(plan.order, 0);
        int loudestAt = indexOf(plan.order, 10);
        assertTrue("最安静的那首应当落在前三分之一，实际第 " + quietestAt + " 位",
                quietestAt <= 3);
        assertTrue("最燃的那首应当落在后半段，实际第 " + loudestAt + " 位（共 11 位）",
                loudestAt >= 6);
    }

    /** 曲线形状本身：{@link FlowPlanner#curveAt} 在两端夹住，中间线性插值。 */
    @Test
    public void curveAtClampsOutsideTheControlRangeAndInterpolatesInside() {
        assertEquals(FlowPlanner.CURVE_HEIGHT[0], FlowPlanner.curveAt(-1.0f), 1e-6f);
        assertEquals(FlowPlanner.CURVE_HEIGHT[FlowPlanner.CURVE_HEIGHT.length - 1],
                FlowPlanner.curveAt(2.0f), 1e-6f);
        // 0.15 与 0.22 是相邻控制点（0.60 / 0.25），中点 0.185 应当是平均值
        assertEquals(0.425f, FlowPlanner.curveAt(0.185f), 1e-4f);
    }

    /** {@code costOf} 对空输入与全空槽位给 0，不抛异常。 */
    @Test
    public void costOfHandlesEmptyAndUnknownEntries() {
        assertEquals(0.0, FlowPlanner.costOf(new ArrayList<FlowFeatures>(), null), 1e-9);
        assertEquals(0.0, FlowPlanner.costOf(list(null, null), null), 1e-9);
        assertNotNull(FlowPlanner.plan(list(feat(120.0f, 0.1f), null), null).idealCurve);
    }

    private static int indexOf(int[] order, int value) {
        for (int i = 0; i < order.length; i++) {
            if (order[i] == value) {
                return i;
            }
        }
        return -1;
    }

    /** 不叫 assertArrayEquals：JUnit 自己有一个同签名的重载，重名会让人以为在比数组内容。 */
    private static void assertSameOrder(int[] a, int[] b) {
        assertEquals("长度不同", a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals("第 " + i + " 位不同（" + Arrays.toString(a) + " vs "
                    + Arrays.toString(b) + "）", a[i], b[i]);
        }
    }
}
