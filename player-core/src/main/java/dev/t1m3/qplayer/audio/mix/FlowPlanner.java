package dev.t1m3.qplayer.audio.mix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一键排列：把一串 {@link FlowFeatures} 排成「相邻两首接得上」的顺序。
 *
 * <h2>它优化的目标函数</h2>
 *
 * <p>整个歌单的<strong>总代价</strong>：
 *
 * <pre>
 * 总代价 = Σ_k  1.5 × |该位曲目的贴合分 − 兴趣曲线在第 k 位的理想分|
 *        + Σ_k  衔接代价(第 k−1 首, 第 k 首)          （k = 0 时若给了锚点就用锚点）
 *
 * 衔接代价(前, 后) = 速度项 + 响度项
 *   速度项 = min(|r − 1|, |r − 2|) × 100      其中 r = max(前尾, 后头) / min(前尾, 后头) ≥ 1
 *   响度项 = |后头响度 − 前尾响度| × 50 × 0.5
 * </pre>
 *
 * <p>三块的相对权重（{@code 1.5} / {@code 1.0} / {@code 0.5}）就是 LiveSort 公开说明里
 * 那条「情绪贴合度<strong>最高</strong>权重、BPM 衔接次之、能量平滑度再次」。
 * 量级上它们并不相等，这是有意的：一次情绪错位（最长可到 ~135）本来就应该盖过
 * 一次速度小差（110% vs 100% ≈ 10）；把三块拉平会让「曲线」这件事失效。
 *
 * <h3>速度项为什么要写成 min(|r−1|, |r−2|)</h3>
 *
 * <p>{@code r} 是两首歌速度的<strong>倍数</strong>，不是差值。BPM 的偏差感是比例性的：
 * 90→100 与 180→200 是同一种「快了」，差值都是 10，倍数都是 1.11。
 * 而 {@code |r−2|} 这一项是混音里最实用的一条经验：<strong>60 BPM 接 120 BPM、
 * 或 128 接 64，听感上是顺的</strong>（半速/倍速关系，鼓点仍然对得上），
 * 所以倍数 1 和倍数 2 都拿满分，倍数 1.5 最差。不写这一条，所有慢歌都会被
 * 排得离快歌很远，而实际上它们本来可以互为倍速接上。
 *
 * <h2>用什么算法</h2>
 *
 * <p><strong>确定性的贪心最近邻</strong>，没有 2-opt、没有模拟退火、没有随机数：
 *
 * <ol>
 *   <li>先按曲目条数生成理想兴趣曲线（见 {@link #idealCurve}）。</li>
 *   <li><strong>第一首</strong>：在还没排的曲目里取代价最小的（代价 = 情绪贴合 + 锚点衔接）。</li>
 *   <li><strong>之后每一位</strong>：对每个还没排的候选算
 *       {@code 1.5×|贴合 − 理想| + 衔接代价(上一首, 候选)}，取最小的那个填进去。</li>
 * </ol>
 *
 * <p>复杂度 O(n²) 次代价计算，n = 100 也就一万次浮点运算——相对解码一首歌
 * 几十毫秒的分析成本可以忽略，所以不需要更聪明的搜索。
 *
 * <h3>可复现</h3>
 *
 * <p>比较一律用严格小于（{@code <}），所以<strong>并列时取原顺序里靠前的那一首</strong>。
 * 同样的输入（同样的特征、同样的顺序）永远给同样的输出，同一首歌重复点两次
 * 「一键排列」也不会来回换序——用户看到的是一个稳定的结果，而不是抽奖。
 *
 * <h2>两条 QPlayer 自己的决定（LiveSort 没有对应物）</h2>
 *
 * <ol>
 *   <li><strong>锚点</strong>（{@code anchor}）：正在播的那一首。LiveSort 排的是
 *       「还没开始听」的歌单，第一首只看情绪曲线；QPlayer 排的是<strong>正在播的队列</strong>，
 *       最需要顺的那一处接缝恰恰是「正在播的这首 → 排在第一的那首」，所以锚点参与
 *       第一首的选择。不给锚点（{@code null}）就退化成 LiveSort 的行为。</li>
 *   <li><strong>固定位</strong>（{@code slots} 里的 {@code null}）：没有特征的曲目
 *       <strong>原地不动</strong>，只把有特征的曲目在它们<strong>原本占的那些位置</strong>
 *       之间重新分配。既不把没分析出来的歌随便塞进顺序里，也不把它们挪到别处——
 *       见 {@link #plan} 的说明。</li>
 * </ol>
 *
 * <h2>洁净室说明</h2>
 *
 * <p>见 {@link FlowFeatures} 的类注释：依据 LiveSort 的公开算法说明独立编写，
 * 未复制其源码/命名/结构/测试数据。
 */
public final class FlowPlanner {

    /**
     * 兴趣曲线的控制点位置（归一化到 0..1 的播放进度）。
     *
     * <p>这条曲线是 LiveSort 公开说明里那张「优秀兴趣曲线」的取样点：
     * 引子 → 铺垫 → 谷底 → 递进 → 高潮 → 收尾。位置与高度这两组数取自
     * 该说明给出的曲线形状（<strong>算法的规格参数</strong>，不是代码抄录）。
     */
    public static final float[] CURVE_AT = {
            0.00f, 0.15f, 0.22f, 0.32f, 0.40f, 0.55f, 0.65f, 0.80f, 1.00f
    };

    /** 控制点高度（0..1 的相对情绪强度），与 {@link #CURVE_AT} 一一对应。 */
    public static final float[] CURVE_HEIGHT = {
            0.10f, 0.60f, 0.25f, 0.45f, 0.35f, 0.75f, 0.40f, 1.00f, 0.05f
    };

    /**
     * 曲线映射到分数区间时用的下界与跨度。
     *
     * <p>曲线高度是 0..1，而贴合分是 0..100（见 {@link #fitScores}），
     * 所以要把曲线拉到 10..90 才能直接相减。上界不取 100：曲线要<strong>留出余量</strong>，
     * 让「比理想更极端」的曲子仍然能被区分开，而不是一起撞在天花板上。
     */
    static final float CURVE_FLOOR = 10.0f;

    /** 见 {@link #CURVE_FLOOR}。 */
    static final float CURVE_SPAN = 80.0f;

    /** 情绪贴合项权重（三位里最高）。 */
    static final float W_FIT = 1.5f;

    /** 速度衔接项权重。 */
    static final float W_TEMPO = 1.0f;

    /** 响度平滑项权重。 */
    static final float W_LEVEL = 0.5f;

    /** 速度倍数差的换算系数：倍数差 1.0（也就是 2 倍速）算 100 分。 */
    static final float TEMPO_SCALE = 100.0f;

    /** 响度差的换算系数。 */
    static final float LEVEL_SCALE = 50.0f;

    /**
     * 贴合分里三项的权重：速度、响度、音色。
     *
     * <p>同样出自 LiveSort 公开说明的「BPM / 能量 / 亮度」三特征，
     * 前两项等权、亮度减半——亮度是辅助判断「风格跳不跳」的，
     * 单靠它定情绪会把「干净的女声」和「嘈杂的摇滚」判反。
     */
    static final float FIT_W_TEMPO = 0.4f;

    /** 见 {@link #FIT_W_TEMPO}。 */
    static final float FIT_W_LEVEL = 0.4f;

    /** 见 {@link #FIT_W_TEMPO}。 */
    static final float FIT_W_TONE = 0.2f;

    private FlowPlanner() {
    }

    /**
     * 排列结果。
     *
     * <p>{@link #order} 是「<strong>哪位的内容搬到哪位</strong>」的置换：
     * {@code 新列表[k] = 旧列表[order[k]]}。没有特征的位（传 {@code null} 的那些）
     * 恒有 {@code order[i] == i}。
     */
    public static final class Plan {

        /** 置换：{@code order[k]} 是「放在第 k 位的曲目原来在哪一位」。 */
        public final int[] order;

        /** 参与排列的曲目数（有特征的那些）。 */
        public final int movable;

        /** 理想兴趣曲线，长度 = {@link #movable}。播放界面可以直接画出来对照。 */
        public final float[] idealCurve;

        /** 实际贴合分，长度 = {@link #movable}，与 {@link #idealCurve} 同下标对齐。 */
        public final float[] actualCurve;

        /** 总代价（越小越顺）。只用于日志与测试，不展示给用户。 */
        public final double cost;

        Plan(int[] order, int movable, float[] idealCurve, float[] actualCurve, double cost) {
            this.order = order;
            this.movable = movable;
            this.idealCurve = idealCurve;
            this.actualCurve = actualCurve;
            this.cost = cost;
        }

        /** 这个排列是不是什么都没动。 */
        public boolean isIdentity() {
            for (int i = 0; i < order.length; i++) {
                if (order[i] != i) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "Plan { 可排 %d 首, 代价 %.2f, 顺序 %s }", movable, cost,
                    java.util.Arrays.toString(order));
        }
    }

    /**
     * 排列一串「槽位」。
     *
     * <h3>槽位里放 {@code null} 是什么意思</h3>
     *
     * <p>{@code null} = 「这一位的东西<strong>没有可分析特征</strong>」（解码失败、
     * 没有音源句柄、要求先解析直链而我们没有）。这些位<strong>原地不动</strong>：
     * 有特征的曲目只在它们<strong>自己原本占的那些位置</strong>之间重新分配。
     *
     * <p>这是刻意的，也是本模块对「不许假装排好了」那条纪律的落点。另外两种做法都更差：
     *
     * <ul>
     *   <li><strong>把没特征的歌一起塞进顺序里</strong>——它们没有代价可算，
     *       等于按「当前这个位置顺眼」摆，用户看到的却是一个「全都排好了」的结果。</li>
     *   <li><strong>把它们统一挪到末尾</strong>——听上去诚实，实际上把用户
     *       自己摆的位置也动了，而理由（我们没分析出来）跟它们的音乐毫无关系。</li>
     * </ul>
     *
     * <p>代价是：夹在两首有特征曲目之间的没特征曲目，会让那两首<strong>听起来</strong>
     * 确实相邻，但代价函数里并不相邻。这一条如实写在
     * {@code docs} 与界面提示里，不藏着。
     *
     * @param slots  曲目特征，可含 {@code null}（含义见上）
     * @param anchor 正在播的那一首的特征；没有（队列没在播、或在播的那首自己也没特征）
     *               时传 {@code null}
     */
    public static Plan plan(List<FlowFeatures> slots, FlowFeatures anchor) {
        int n = slots == null ? 0 : slots.size();
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        if (n == 0) {
            return new Plan(order, 0, new float[0], new float[0], 0.0);
        }

        List<Integer> movable = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (slots.get(i) != null) {
                movable.add(i);
            }
        }
        int m = movable.size();
        if (m < 2) {
            // 一首（或零首）有特征的曲目：没有「相邻」可言，什么都不动。
            // 不返回 null、不报错——「排不动」是一个正常结论，不是失败。
            return new Plan(order, m, new float[m], new float[m], 0.0);
        }

        float[] fit = fitScores(slots, movable);
        float[] ideal = idealCurve(m);
        boolean[] used = new boolean[m];
        int[] pick = new int[m];

        // 第一首：情绪贴合 + 锚点衔接（见类注释「两条 QPlayer 自己的决定」）。
        int first = -1;
        double firstCost = Double.MAX_VALUE;
        for (int j = 0; j < m; j++) {
            double c = W_FIT * Math.abs(fit[j] - ideal[0]) + transitionCost(anchor, slots.get(movable.get(j)));
            if (c < firstCost) {
                firstCost = c;
                first = j;
            }
        }
        pick[0] = first;
        used[first] = true;

        double total = firstCost;
        FlowFeatures prev = slots.get(movable.get(first));
        for (int pos = 1; pos < m; pos++) {
            int best = -1;
            double bestCost = Double.MAX_VALUE;
            for (int j = 0; j < m; j++) {
                if (used[j]) {
                    continue;
                }
                FlowFeatures candidate = slots.get(movable.get(j));
                // 严格小于：并列时保留原顺序里靠前的那一首，于是结果可复现（见类注释）。
                double c = W_FIT * Math.abs(fit[j] - ideal[pos]) + transitionCost(prev, candidate);
                if (c < bestCost) {
                    bestCost = c;
                    best = j;
                }
            }
            pick[pos] = best;
            used[best] = true;
            total += bestCost;
            prev = slots.get(movable.get(best));
        }

        // 写回：有特征的位按 pick 重排，没特征的位保持 order[i] == i。
        float[] actual = new float[m];
        for (int pos = 0; pos < m; pos++) {
            order[movable.get(pos)] = movable.get(pick[pos]);
            actual[pos] = fit[pick[pos]];
        }
        return new Plan(order, m, ideal, actual, total);
    }

    /**
     * 一条<strong>已经排好</strong>的顺序的总代价（{@code ordered.get(0)} 是第一位）。
     *
     * <p>与 {@link #plan} 用的是同一个 {@link #transitionCost} 与同一条兴趣曲线，
     * 所以「排完之后到底变好了没有」这件事是<strong>可验证</strong>的：
     * {@code FlowPlannerTest} 拿它比较排列结果与原顺序，日志里也能打出改善量。
     *
     * <p>这里<strong>不做</strong>「{@code null} 槽位跳过」那套：
     * 传进来的列表被当作一条<strong>连续的播放顺序</strong>，{@code null}
     * 表示「这一位不知道是什么」，于是它两侧的衔接代价按 0 计（见
     * {@link #transitionCost(FlowFeatures, FlowFeatures)}）。{@link #plan}
     * 内部的槽位语义（{@code null} 原地不动）是另一回事，别混用。
     */
    public static double costOf(List<FlowFeatures> ordered, FlowFeatures anchor) {
        if (ordered == null || ordered.isEmpty()) {
            return 0.0;
        }
        int n = ordered.size();
        float[] ideal = idealCurve(n);
        List<Integer> all = new ArrayList<>(n);
        List<FlowFeatures> filled = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            FlowFeatures f = ordered.get(i);
            // 曲线是给「整条顺序」用的，所以缺席的位也要占一个位置：用一个中性的
            // 零特征顶上去，只为了让 fitScores 好算；它自己的贴合项随后被跳过。
            filled.add(f == null ? ZERO : f);
            all.add(i);
        }
        float[] fit = fitScores(filled, all);

        double total = 0.0;
        FlowFeatures prev = anchor;
        for (int i = 0; i < n; i++) {
            FlowFeatures f = ordered.get(i);
            if (f != null) {
                total += W_FIT * Math.abs(fit[i] - ideal[i]);
            }
            total += transitionCost(prev, f);
            prev = f;
        }
        return total;
    }

    /** {@link #costOf} 用来顶替缺席槽位的全零特征（没有任何一项能带来分数）。 */
    private static final FlowFeatures ZERO =
            new FlowFeatures(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0);

    /**
     * 理想兴趣曲线：{@code n} 首曲子，第 {@code k} 位应当有多「燃」。
     *
     * <p>做法是把 {@link #CURVE_AT} / {@link #CURVE_HEIGHT} 这组控制点
     * <strong>按播放进度线性插值</strong>，再取样到 {@code n} 个等距位置上，
     * 最后拉到 0..100 量纲（见 {@link #CURVE_FLOOR}）。
     *
     * <p>单曲歌单取曲线中点：{@code n == 1} 时 {@code i/(n-1)} 会除以 0，
     * 而「唯一一首歌应当有多燃」本来就没有位置可言，取中点是最少假设的答案。
     */
    public static float[] idealCurve(int n) {
        float[] out = new float[Math.max(n, 0)];
        for (int i = 0; i < out.length; i++) {
            float x = (n <= 1) ? 0.5f : (float) i / (float) (n - 1);
            out[i] = curveAt(x) * CURVE_SPAN + CURVE_FLOOR;
        }
        return out;
    }

    /** 在控制点之间线性插值。{@code x} 超出 0..1 时按端点取值。 */
    static float curveAt(float x) {
        if (x <= CURVE_AT[0]) {
            return CURVE_HEIGHT[0];
        }
        int last = CURVE_AT.length - 1;
        if (x >= CURVE_AT[last]) {
            return CURVE_HEIGHT[last];
        }
        for (int i = 0; i < last; i++) {
            float left = CURVE_AT[i];
            float right = CURVE_AT[i + 1];
            if (x >= left && x <= right) {
                float t = (x - left) / Math.max(1e-9f, right - left);
                return CURVE_HEIGHT[i] * (1.0f - t) + CURVE_HEIGHT[i + 1] * t;
            }
        }
        return CURVE_HEIGHT[last];
    }

    /**
     * 每首曲目的「情绪贴合分」（0..100）。
     *
     * <p>三项（速度、响度、音色）各自<strong>除以这一批里的最大值</strong>再按
     * {@link #FIT_W_TEMPO} 加权求和。用「这一批里的相对值」而不是绝对刻度，
     * 是因为绝对刻度无法同时容纳古典（质心低、响度小）与电子（质心高、响度大）：
     * 一批安静的歌里，相对更快更亮的那些才应该是这一批的高潮点。
     *
     * <p>分母取 {@code max(最大值, 地板)}，所以全是 0 时（整批都测不出速度）
     * 归一化值是 0 而不是 NaN。{@link FlowFeatures#tempo} 为 0 的曲目在速度项上
     * 拿 0 分——<strong>这不是「它很慢」，而是「我们不知道」</strong>；把它当慢歌
     * 处理会一路把它推到曲线起点（引子），那是个编出来的结论。
     *
     * <p>包私有而非 {@code private}：给同包的 {@code FlowPlannerTest} 直接对照检验。
     */
    static float[] fitScores(List<FlowFeatures> slots, List<Integer> movable) {
        float maxTempo = 1.0f;
        float maxLevel = 1e-9f;
        float maxTone = 1e-9f;
        for (int j = 0; j < movable.size(); j++) {
            FlowFeatures f = slots.get(movable.get(j));
            maxTempo = Math.max(maxTempo, f.tempo);
            maxLevel = Math.max(maxLevel, f.level);
            maxTone = Math.max(maxTone, f.tone);
        }
        float[] out = new float[movable.size()];
        for (int j = 0; j < out.length; j++) {
            FlowFeatures f = slots.get(movable.get(j));
            float score = FIT_W_TEMPO * (f.tempo / maxTempo)
                    + FIT_W_LEVEL * (f.level / maxLevel)
                    + FIT_W_TONE * (f.tone / maxTone);
            out[j] = clamp(score * 100.0f, 0.0f, 100.0f);
        }
        return out;
    }

    /**
     * 两首相邻曲目的衔接代价。
     *
     * <p>{@code prev == null}（前面没有歌 / 没给锚点）返回 0。
     * <strong>任一侧速度测不出</strong>时速度项记 0：不猜。代价函数里 0 是「中性」，
     * 不是「完美」——这一点在总代价的绝对值上看不出来，所以总量不拿来做任何判定。
     *
     * <p>响度项在 {@code prev} 的结尾段整个没读到时（{@link FlowFeatures#tailLevel}
     * 是 NaN）同样记 0，理由相同。
     *
     * <p>包私有而非 {@code private}：给同包的 {@code FlowPlannerTest} 直接用
     * 具体数值钉住公式（含 1:1 与 1:2 两处免费档）。
     */
    static double transitionCost(FlowFeatures prev, FlowFeatures next) {
        if (prev == null || next == null) {
            return 0.0;
        }
        double cost = 0.0;
        float a = prev.outgoingTempo();
        float b = next.incomingTempo();
        if (a > 0.0f && b > 0.0f) {
            float hi = Math.max(a, b);
            float lo = Math.max(Math.min(a, b), 1e-3f);
            float ratio = hi / lo;
            cost += W_TEMPO * Math.min(Math.abs(ratio - 1.0f), Math.abs(ratio - 2.0f)) * TEMPO_SCALE;
        }
        float outLevel = prev.outgoingLevel();
        if (!Float.isNaN(outLevel)) {
            cost += W_LEVEL * Math.abs(next.incomingLevel() - outLevel) * LEVEL_SCALE;
        }
        return cost;
    }

    /** 上游 {@code f32::clamp} 的镜像：NaN 原样穿过去（与本包其它类的处理一致）。 */
    private static float clamp(float v, float lo, float hi) {
        if (v < lo) {
            return lo;
        }
        if (v > hi) {
            return hi;
        }
        return v;
    }
}
