package dev.t1m3.qplayer.audio.mix;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>各档的闸门（因子）</b>（2026-10-07）。
 *
 * <p>每条路由的因子清单
 * （{@code strict_tempo_compatible} · {@code tonalities_compatible} · {@code matching_bar_count} ·
 * {@code vocal_overlap_incompatible} · {@code incoming/outgoing_beat_count} · {@code tempo_alias} ·
 * {@code outgoing_loudness_significant} · {@code incoming_loudness_relation_code} ·
 * {@code trailing_span_seconds}）。我们把它映射到**自己已有的信号**上：
 *
 * <table>
 *   <tr><th>他的因子</th><th>我们的信号</th><th>说明</th></tr>
 *   <tr><td>strict/loose_tempo_compatible</td><td>速度差</td><td>两道门限（严/松）</td></tr>
 *   <tr><td>tonalities_compatible</td><td>调性相似度</td><td>≥ 门槛（调性不合 ⇒ 0）</td></tr>
 *   <tr><td>matching_bar_count</td><td>可叠秒数 ≥ 下限</td><td>**代理**：我们还没按"小节数相等"精确比对，如实标为代理</td></tr>
 *   <tr><td>vocal_overlap_incompatible</td><td>叠唱秒数</td><td>人声不撞那一档用</td></tr>
 *   <tr><td>incoming/outgoing_beat_count</td><td>该侧可叠拍数</td><td>够不够成段</td></tr>
 *   <tr><td>tempo_alias</td><td>倍半关系（{@link #tempoAlias}）</td><td>
 *       **已实现**：比值 ≈2/≈0.5 ⇒ 记为 alias，速度门不再判"不合"，量子取 2 拍
 *       （见 {@link #quantumBeats}）</td></tr>
 *   <tr><td>outgoing_loudness_significant<br>incoming_loudness_relation_code</td><td>——</td><td>**我们没有这个信号** ⇒ 返回 null（缺失 ⇒ 该候选不给分）</td></tr>
 *   <tr><td>（曲风路由）</td><td>{@code EqFeatureExtractor.genreScores}</td><td>连续分数直接当因子（越大越该走曲风专用档）</td></tr>
 * </table>
 *
 * <p>缺信号一律 {@code null} 而不是 1 —— 这是本仓库的纪律，也是参照的纪律
 * （{@code score = None}）：**没有依据就不要给分**。
 */
public final class RouteGates {

    // ⚠️ **变速这一块只用我们自己的判定，不采用参照的做法**（用户 2026-10-07 明确要求）：
    //   · 不用他的「±17% 硬门 + σ=9% 舒适区」；
    //   · 更不用他"加权候选 + 种子（两首歌毫秒数）取 UInt64 模"那一套抽签式选择 ✗。
    // 我们的做法（自己定、自己验）：严 3% / 松 10% 两道门 + 位置只值 0.001 的破平手，
    // 选点与变速的其余部分沿用 AutoCue / TempoRamp / TempoBridge 既有的那套。

    /**
     * <b>倍半关系的容差</b>（我们自己的口径）：比值落在 2±{@value} 或 0.5±{@value} 内
     * 就算"同一个速度的倍半"。
     *
     * <p>取 6% 的理由：速度估计本身有百分之几的抖动，而倍半关系是**精确的 2 倍**，
     * 所以窗口可以窄；比我们自己的"严"门限（3%）宽一点，是为了容忍估计误差，
     * 但远小于"半速/两倍"与"随便两首歌"的差别。
     */
    public static final double ALIAS_TOLERANCE = 0.06;

    /**
     * <b>识别倍半关系</b>（`tempo_alias`）—— 参照档案里它是 {@code ∈ {0.5, 1, 2}}。
     *
     * <p>用户 2026-10-07 的原话说明了它的用处：「87 BPM 和 174 BPM 这种**倍半关系**的曲子，
     * 在你这里速度门直接判"不合"（50% 的差），而 AM 会认出来"这是**同一个速度的两倍**"，
     * 然后按 4 拍（alias==2 时 2 拍）为**量子**去算叠唱区」。
     *
     * <p>所以：能识别出倍半关系时，速度门**不该判不合** ✗ —— 而应当把这一对按
     * "同一个速度"处理，并把量子换成 2 拍（见 {@link #quantumBeats(double)}）。
     *
     * @return {@code 1} = 同速 · {@code 2} = 前者是后者两倍 · {@code 0.5} = 前者是后者一半 ·
     *         {@code 0} = 没有倍半关系（**不猜**：识别不出来就如实说没有）
     */
    public static double tempoAlias(double bpmA, double bpmB) {
        return tempoAlias(bpmA, bpmB, ALIAS_TOLERANCE);
    }

    /** 见 {@link #tempoAlias(double, double)}（容差可传，判据用）。 */
    public static double tempoAlias(double bpmA, double bpmB, double tolerance) {
        if (!(bpmA > 0.0) || !(bpmB > 0.0) || !(tolerance > 0.0)) {
            return 0.0;
        }
        double ratio = bpmA / bpmB;
        if (Math.abs(ratio - 2.0) <= tolerance * 2.0) {
            return 2.0;                       // A 是 B 的两倍（174 vs 87）
        }
        if (Math.abs(ratio - 0.5) <= tolerance * 0.5) {
            return 0.5;                       // A 是 B 的一半（87 vs 174）
        }
        if (Math.abs(ratio - 1.0) <= tolerance) {
            return 1.0;                       // 同速
        }
        return 0.0;                           // 没有关系（不猜）
    }

    /**
     * <b>叠唱区的量子拍数</b>：默认 <b>4 拍</b>；识别出倍半关系（alias == 2 或 0.5）时
     * 用 <b>2 拍</b>（用户原话："按 4 拍（alias==2 时 2 拍）为量子去算叠唱区"）。
     *
     * <p>为什么倍半时量子减半：两边的小节长度差一倍 —— 用 4 拍去对齐会出现"一边两小节
     * 对一边一小节"的错位，2 拍才是两边都能落准的公共量子 ✓。
     */
    public static double quantumBeats(double alias) {
        return alias == 2.0 || alias == 0.5 ? 2.0 : 4.0;
    }

    /**
     * <b>把叠唱区的拍数取整到"量子"的整数倍</b>（用户原话："按 4 拍（alias==2 时 2 拍）
     * 为量子去算叠唱区"）。
     *
     * <p>量子由 {@link #quantumBeats(double)} 给：默认 4 拍；识别出倍半关系（alias 为 2 或 0.5）
     * 时 2 拍。为什么这样：倍半的两边小节长度差一倍，用 4 拍去对齐会出现
     * "一边两小节对一边一小节"的错位 —— 2 拍才是两边都能落准的公共量子 ✓。
     *
     * <p>取整规则：向**最近的**量子倍数靠；至少一个量子（不允许 0 拍）。
     * 拍数不是正数（算不出来）⇒ 原样返回（不猜）。
     */
    public static double snapBeatsToQuantum(double beats, double alias) {
        if (!(beats > 0.0)) {
            return beats;                     // 算不出来 ⇒ 原样（不猜）
        }
        double quantum = quantumBeats(alias);
        // **向上取整，不是四舍五入**（2026-10-07 修一个真 bug）：
        // 这个函数是拿"接法拍数 × window_scale"来算的 ⇒ 用户把 scale 调大，
        // 本意是**要更长的窗口** ✓。而四舍五入会在量子边界上把它**缩短** ✗：
        // 舞曲那一档 8 拍 × 1.3 = 10.4、× 1.6 = 12.8 ⇒ 两个都被四舍五入成 12 ✗
        // ⇒ 用户把滑块从 1.3 拖到 1.6 **窗口一点都不变**（判据
        // `AutomixSmoothnessReferenceTest.longerWindowsKeepTheBlendAtTheEndOfTheSong` 照出来的）。
        // 所以改成 ceil：量子只负责把窗口**补足**到下一个量子，绝不缩短 ✓。
        double snapped = Math.ceil(beats / quantum - 1e-9) * quantum;
        return snapped < quantum ? quantum : snapped;
    }

    /** 速度差"严"门限（**我们自己的**）。 */
    static final double STRICT_TEMPO = 0.03;
    /** 速度差"松"门限（**我们自己的**）。 */
    static final double LOOSE_TEMPO = 0.10;
    /** 调性门槛（与 MixTuning.autoKeySame 的默认一致）。 */
    static final double KEY_GATE = 0.70;
    /** 可叠秒数下限（当作 matching_bar_count 的代理）。 */
    static final double MIN_OVERLAP = 1.5;
    /** 成段所需的最少拍数。 */
    static final double MIN_BEATS = 4.0;
    /** 曲风分数到多少才算"这一档的曲风"。 */
    static final double GENRE_GATE = 0.30;

    /**
     * 曲风**分派**门槛：某一曲风分数高到这个程度，就认为"这一对是那个曲风"
     * ⇒ 该曲风档给 1、**其它曲风档给 0**（硬分派）。
     *
     * <p>为什么：档案里他是按曲风**分派**模板的（{@code algorithm_name: hiphop / pop …}），
     * 而此前我们只把曲风当**连续小权重** ✗（0.05 也在乘）⇒ 曲风档几乎永远拿不到高分、
     * 于是 base 50 的 {@code FILTER_EXPANSION} 几乎永远赢 ✗。改成"可靠就硬分派"之后，
     * 信号空间会按曲风切成三块，hiphop / pop / dance 各自在自己的块里稳赢 ✓。
     * 分数不够高 ⇒ 维持现状（连续权重，不硬筛）—— 不猜。
     */
    public static final double GENRE_DISPATCH_GATE = 0.5;

    /** 这一对**最像**哪个曲风（0/1/3 = dance/hiphop/pop；都不够高 ⇒ -1）。 */
    static int dispatchedGenre(Signals s) {
        if (s.genres == null) {
            return -1;
        }
        int best = -1;
        double bestScore = GENRE_DISPATCH_GATE;
        int[] slots = {0, 1, 3};                 // dance / hiphop / pop（与 genreScores 的槽位一致）
        for (int slot : slots) {
            if (slot < s.genres.length && s.genres[slot] >= bestScore) {
                bestScore = s.genres[slot];
                best = slot;
            }
        }
        return best;
    }

    /** 曲风档的因子：硬分派（是我的 ⇒ 1；是别人的 ⇒ 0；都没分派 ⇒ 连续值）。 */
    static Double genreDispatchFactor(Signals s, int mySlot) {
        int dispatched = dispatchedGenre(s);
        if (dispatched < 0) {
            return genre(s.genres, mySlot);      // 没分派 ⇒ 维持连续权重（不猜）
        }
        return gate(dispatched == mySlot);
    }

    private RouteGates() {
    }

    /** 一次判定要用到的全部信号（缺的用 {@code null} / NaN 表示"没有"）。 */
    public static final class Signals {
        /** 相对速度差（|bpmA−bpmB| / 均值）。 */
        public Double tempoDiff;
        /** 调性相似度（0..1）。 */
        public Double keyScore;
        /** 低频冲突（0..1）。 */
        public Double lowClash;
        /** drop 显著度（0..1）。 */
        public Double dropReliable;
        /** 可叠秒数。 */
        public Double overlapSecs;
        /** A 尾段是不是在往上堆。 */
        public Boolean buildUp;
        /** 叠唱秒数（人声真的叠着多久）。 */
        public Double vocalOverlapSecs;
        /**
         * 叠唱判断是不是**基于可靠依据**（有中置声道 / 真 VAD 置信度足够）✓。
         * {@code false} 时那条硬门会**软化成 0.75 因子**（不误杀候选）✓。
         */
        public boolean vocalOverlapReliable = false;
        /**
         * <b>强度加权重叠度</b>：{@code max_t( min(strengthA(t), strengthB(t)) )}（抄自参照）。
         *
         * <p>为什么它比"重叠秒数"准得多：{@code 0.5 s 的阈值}分不出"一边轻哼"与"两边都在吼" ✗，
         * 而这个量取的是"**两边同时最响的那一刻**" ✓ —— 那才是真正听得出来的人声撞车 ✓。
         * 我们现有的 {@code vocalOverlapSecs} 旁边就多这一个更细的判据 ✓（下一步接进闸门 ✓）。
         */
        public Double vocalOverlapStrength;
        /**
         * <b>人声类别</b>：0 = singing · 1 = speech · 2 = rapping（抄自参照的 kind 三分类）。
         *
         * <p>说唱重叠与演唱重叠的**容忍度完全不同** ✗ —— 我们的代理分不出来 ✗；
         * 这一项对 hip-hop / 中文说唱的曲库价值最大 ✓（下一步接进闸门 ✓）。
         */
        public Integer vocalKind;
        /** A 侧可叠拍数 / B 侧可叠拍数。 */
        public Double outroBeats;
        public Double introBeats;
        /**
         * A 侧"尾巴长度"（秒）—— 他清单里 {@code trailing_span_seconds} 的**我们的代理**。
         *
         * <p>他的原语义未恢复 ✗；我们量的是"窗口里最后一段还高于（峰值 −20 dB）的时间"。
         */
        public Double trailingSpanSecs;

        /**
         * 进场侧是不是**比出场侧安静** —— 他清单里 {@code incoming_loudness_relation_code}
         * 的**我们的代理**（他的码值语义未恢复 ✗，我们只用这一条布尔关系）。
         */
        public Boolean incomingQuieter;

        /**
         * 两侧可叠拍数是否**基本相等**（差 ≤ 1 拍）—— {@code matching_bar_count} 的我们的代理
         * （他真的比的是"小节数相等"，我们还没按小节精确比对）。
         */
        public Boolean matchingBars;

        /** A 侧尾巴的衰减斜率（dB/s；他清单里 trailing_span_seconds 的**代理**）。 */
        public Double tailDecayDbPerSec;

        /** 两侧的 bpm（识别倍半关系用；任一缺失 ⇒ alias 算不出来）。 */
        public Double bpmA;
        public Double bpmB;

        /** 曲风分数（hiphop / dance / pop / …，来自 genreScores）。 */
        public double[] genres;
        /**
         * <b>旋律重合度</b>（0..1，逐帧包络相关性）—— <b>他没有这个量</b>（用户点名"让他更好"）。
         *
         * <p>作为**因子**进入乘性核：旋律越重合、这一档的乘积越大 ⇒ 同一条路由内部也会
         * 优先挑旋律吻合的情况 ✓。{@code null} = 没量到（缺失 ⇒ 不给分）✓。
         */
        public Double melody;
        /**
         * 出场侧响度是否"显著"与现代关系码 —— **我们现在没有这两个信号**，
         * 留成 {@code null} 以驱动"缺失 ⇒ 不给分"这条纪律（不假装有）。
         */
        public Boolean outgoingLoudnessSignificant;
        public Double incomingLoudnessRelationCode;
    }

    private static Double num(Double v) {
        return (v == null || Double.isNaN(v.doubleValue())) ? null : v;
    }

    /**
     * <b>旋律因子</b>：{@code 0.7 + 0.3 × 旋律重合度}（我们独有的维度）。
     *
     * <p><b>为什么它和"闸门"不一样</b>（这一点第一次跑就被判据逼出来了）：
     * 闸门是"必须满足" ✓ ⇒ 缺失就 {@code null}（不给分 ✓）；而旋律是**加成** ✓
     * —— 把它做成硬因子时，"没量到旋律"会连**交叉淡化**都不能用 ✗（实测：干净的一对
     * 因此选了滤波档 ✗），那是过度否决 ✓。
     *
     * <p>所以：量到 ⇒ 落在 {@code [0.7, 1.0]}，越重合越接近 1（这条路由越占优 ✓）；
     * 没量到 ⇒ **中性 1.0**（不奖励也不惩罚 ✓）—— 于是"我们的旋律"只在**能量到时**让它更好 ✓，
     * 而不是在缺信号时把整条路堵死 ✓。
     */
    /** **理由项加成**：{@code 0.7 + 0.3 × v}（"正因为这样才用这一档"的项，越大越该用）。 */
    static Double reasonBonus(Double v) {
        if (v == null || Double.isNaN(v.doubleValue())) {
            return Double.valueOf(1.0);
        }
        double x = Math.max(0.0, Math.min(1.0, v.doubleValue()));
        return Double.valueOf(0.7 + 0.3 * x);
    }

    static Double melodyFactor(Double melody) {
        if (melody == null || Double.isNaN(melody.doubleValue())) {
            return Double.valueOf(1.0);        // 加成项缺失 ⇒ 中性（与"闸门缺失"不同，理由见上）
        }
        double m = Math.max(0.0, Math.min(1.0, melody.doubleValue()));
        return Double.valueOf(0.7 + 0.3 * m);
    }

    /** "出场侧响度是否显著"的门槛（LUFS）。低于它按"这一侧几乎是静音"处理。 */
    public static final double LOUDNESS_SIGNIFICANT_LUFS = -40.0;

    /**
     * <b>"出场侧响度是否显著"</b>（参照里有这个信号：{@code outgoing_loudness_significant}）。
     *
     * <p>⚠️ <b>它的判据是我们自己定的</b>：参照的 notes 只给了签名
     * {@code outgoing_loudness_significant(mean, map_available)}，**没有给出那个规则** ✗，
     * 所以这里用我们的 LUFS 表 + 一个明确的门槛（{@value #LOUDNESS_SIGNIFICANT_LUFS} LUFS）
     * 自己定，并**如实标注为我们的定义** ✓（不假装是他的规则）。
     *
     * <p>用途：POP 那两条路由（base 50）需要它 —— 在它被填上之前那两条**永远拿不到分** ✓
     * （那是"缺失就不给分"在起作用 ✓，不是 bug ✓）。
     *
     * @param mono 单声道素材（我们手上是单声道；标准刻度按双声道定义 ⇒ 这里读出来会
     *             比双声道低约 3 LU ✓，对"是否显著"这个布尔量没有影响 ✓）
     */
    public static boolean outgoingLoudnessSignificant(float[] mono, int rate) {
        if (mono == null || mono.length == 0) {
            return false;
        }
        double lufs = dev.t1m3.qplayer.audio.dsp.Lufs.integrated(mono, null, rate);
        return lufs > LOUDNESS_SIGNIFICANT_LUFS;      // 负无穷（全静音）自然为 false
    }

    /**
     * <b>速度门的因子（含倍半识别）</b>：识别出倍半关系时直接给 1 —— 用户原话
     * 「AM 会认出来"这是同一个速度的两倍"」，所以**不该判不合** ✗；
     * 识别不出来才按原来的阶跃门（严 3% / 松 10%）。
     */
    static Double tempoGateFactor(Signals s) {
        if (s == null) {
            return null;
        }
        if (s.bpmA != null && s.bpmB != null
                && tempoAlias(s.bpmA.doubleValue(), s.bpmB.doubleValue()) != 0.0) {
            return Double.valueOf(1.0);          // 倍半 ⇒ 同一个速度（不判不合）
        }
        return s.tempoDiff == null ? null
                : gate(s.tempoDiff.doubleValue() <= LOOSE_TEMPO);
    }

    private static Double gate(boolean ok) {
        return ok ? 1.0 : 0.0;
    }

    /** 代理信号置信度低时，硬门退成的**软因子**（用户 2026-10-07 指定的数值）。 */
    public static final double SOFT_FACTOR = 0.75;

    /**
     * <b>硬门，但代理信号不可靠时自动软化</b>（2026-10-07，用户指定，抄自参照的设计）。
     *
     * <p>问题：我们的门是**硬否决**（{@code ok ? 1 : 0}）✓ —— 更严格 ✓，但**更脆** ✗：
     * 代理信号（我们没有真 VAD ✗，用的是包络代理 ✓）一次假阳性就会**直接杀掉一个好候选** ✗，
     * 而且事后完全看不出"它是被一个不可靠的信号杀的"✗。
     *
     * <p>所以：<b>有可靠依据时用硬门 ✓；只有代理信号时退成 {@value #SOFT_FACTOR} 的软因子</b> ✓
     * —— 于是"不满足"只是**降低**这条候选的乘积 ✓，而不是把它一票否决 ✓；
     * 其它闸门更强的候选仍然可能赢 ✓，但它不会因为一个代理指标的抖动被误杀 ✓。
     *
     * @param ok         依据（满足为真）
     * @param reliable   {@code true} = 这个判断基于**可靠依据**（例如有中置声道数据、
     *                   或真 VAD 的置信度足够）⇒ 用硬门；{@code false} = 只是代理 ⇒ 软化
     */
    static Double gateWithConfidence(boolean ok, boolean reliable) {
        if (ok) {
            return 1.0;
        }
        return reliable ? 0.0 : SOFT_FACTOR;
    }

    private static Double genre(double[] genres, int index) {
        if (genres == null || index >= genres.length) {
            return null;                       // 没有曲风信号 ⇒ 曲风档不给分（不猜）
        }
        double g = genres[index];
        return g >= GENRE_GATE ? g : 0.0;      // 连续分数当因子：越像这一曲风，乘积越大
    }

    /**
     * 取这一档的因子列表（{@code null} 元素 = 该信号缺失）。
     *
     * @return 因子列表；{@code null} = 这一档在当前信号下**不参与**（调用方跳过它）
     */
    public static List<Double> factorsFor(MixStyle style, Signals s) {
        if (style == null || s == null) {
            return null;
        }
        // =====================================================================
        // **按他逐条的因子清单分派**（2026-10-07 用户指出：24 档都能选，靠的是
        // "每条路由各有自己的因子"；我此前按家族归并 ⇒ 多条路由同时满足 ⇒
        // 过闸门者里 base 最高的永远赢 ⇒ 塌缩成 5 档 ✗）。
        //
        // 三个**区分性**最强的因子（他清单里的原话）：
        //   · tempo_alias：只有倍半/同速的一对才满足 ⇒ 把 hiphop / filtered-8 / pop 分出去；
        //   · incoming/outgoing_beat_count：两侧可叠拍数 ⇒ 把"窗口够不够"分成几档；
        //   · trailing_span_seconds：尾巴长度 ⇒ 单独归给 smart-crossfade。
        //
        // **我们自己的东西一样不丢**（用户专门叮嘱）：
        //   · 旋律当**加成**因子（melodyFactor，缺了中性）—— 他清单里没有这一项；
        //   · 闸门缺失时的软因子（gateWithConfidence，代理不可靠 ⇒ 0.75 而不是 0）；
        //   · 我们独有的两档（表现模式 / 学习EQ）继续用我们自己的判据。
        // =====================================================================
        List<Double> f = new ArrayList<Double>(6);
        boolean halfDouble = aliasPair(s);          // tempo_alias：倍半或同速
        boolean strictTempo = s.tempoDiff != null && s.tempoDiff.doubleValue() <= STRICT_TEMPO;
        boolean looseTempo = s.tempoDiff != null && s.tempoDiff.doubleValue() <= LOOSE_TEMPO;
        boolean keyOk = s.keyScore == null || s.keyScore.doubleValue() >= KEY_GATE;
        boolean overlapOk = s.overlapSecs != null && s.overlapSecs.doubleValue() >= MIN_OVERLAP;
        boolean shortOverlap = s.overlapSecs != null && s.overlapSecs.doubleValue() < MIN_OVERLAP;
        boolean beatsOk = s.outroBeats != null && s.outroBeats.doubleValue() >= MIN_BEATS
                && s.introBeats != null && s.introBeats.doubleValue() >= MIN_BEATS;
        boolean longTail = s.tailDecayDbPerSec != null
                && s.tailDecayDbPerSec.doubleValue() <= LONG_TAIL_DB_PER_SEC;
        switch (style) {
            // ---- filtered-direct (id 9)：他的因子里多一项 vocal_overlap_incompatible ----
            case FILTER_EXPANSION:
                // 他的清单里这一档**有 matching_bar_count** ⇒ 用它收窄（否则它 base 50、
                // 因子又宽，会压过所有低 base 的档 ⇒ 其它档永远选不到 ✗）。
                // 他的清单（id 9）：**strict_tempo_compatible** · tonalities_compatible ·
                // matching_bar_count · vocal_overlap_incompatible —— 逐项照抄（此前我用的是
                // alias-aware 的松门 ✗，那与他的清单不符，也是空间切不开的原因之一）。
                f.add(gate(strictTempo));
                f.add(gate(keyOk));
                f.add(gate(matchingBarsOk(s)));
                f.add(gateWithConfidence(vocalOverlapOk(s), vocalVerdictReliable(s)));
                f.add(gateWithConfidence(vocalOverlapOk(s), vocalVerdictReliable(s)));
                f.add(melodyFactor(s.melody));
                return f;
            // ---- filtered-direct (id 8)：**requiring alias** + incoming_beat_count ----
            case FILTER_HIGH_TO_LOW:
                // 他的清单（id 8）：**strict_tempo_compatible** · incoming_beat_count · tempo_alias
                f.add(gate(halfDouble));            // tempo_alias 当**筛选**（不是门 ✓）
                f.add(gate(strictTempo));
                // 与 SHORT 那条互斥：这条要【中短】窗口（5..8 拍）
                f.add(gate(s.outroBeats != null && s.outroBeats.doubleValue() >= 5.0
                        && !longWindow(s)));
                f.add(gate(s.introBeats != null && s.introBeats.doubleValue() >= MIN_BEATS));
                f.add(melodyFactor(s.melody));
                return f;
            case FILTER_SHORT_HIGH_TO_LOW:
                // 同上，但名字是 **Short** ⇒ 要【极短】窗口（≤4 拍）
                f.add(gate(halfDouble));
                f.add(gate(strictTempo));
                f.add(gate(s.outroBeats != null && s.outroBeats.doubleValue() <= 4.0));
                f.add(melodyFactor(s.melody));
                return f;
            // ---- filtered-shifted (id 12)：loose_tempo + matching_bar_count ----
            // **六条同 base 20** ⇒ 各配自己的区分维（名字即语义）：
            //   Long⇒窗口够长 · same-bar-num⇒小节相等 · dynamic⇒进场更响 ·
            //   delay⇒尾巴长且小节不等 · Reverb⇒尾巴长且小节相等 · soft⇒低频几乎不撞
            case FILTER_LONG_HIGH_TO_LOW:
                f.add(gate(strictTempo));
                f.add(gate(looseTempo));
                f.add(gate(matchingBarsOk(s)));
                f.add(gate(longWindow(s)));
                f.add(melodyFactor(s.melody));
                return f;
            case FILTER_LONG_OUT_SHORT_IN:
                f.add(gate(looseTempo));
                f.add(gate(overlapOk));
                f.add(gate(longOutShortIn(s)));      // 名字：出场**长**、进场**短**
                // 与 DYNAMIC_IN 互斥：这条用于"进场**不更响**"（含没量到）
                f.add(gate(s.incomingQuieter == null || s.incomingQuieter.booleanValue()));
                // 与 REVERB 那条互斥：尾巴**不很长**（<3 秒）
                f.add(gate(s.trailingSpanSecs == null || s.trailingSpanSecs.doubleValue() < 3.0));
                f.add(gate(matchingBarsOk(s)));
                f.add(melodyFactor(s.melody));
                return f;
            case FILTER_DYNAMIC_IN:
                f.add(gate(looseTempo));
                f.add(gate(overlapOk));
                f.add(gate(longOutShortIn(s)));      // 名字：出场长、进场短
                // dynamic fade-in ⇒ 进场**更响**（量到才算，硬门）
                f.add(gate(s.incomingQuieter != null && !s.incomingQuieter.booleanValue()));
                f.add(melodyFactor(s.melody));
                return f;
            case FILTER_LONG_OUT_SHORT_IN_DELAY:
                f.add(gate(looseTempo));
                f.add(gate(overlapOk));
                f.add(gate(longOutShortIn(s)));      // 名字：出场长、进场短
                f.add(gate(trailingSpanOk(s)));
                f.add(gate(!matchingBarsOk(s)));
                f.add(melodyFactor(s.melody));
                return f;
            case FILTER_LONG_OUT_SHORT_IN_REVERB:
                f.add(gate(looseTempo));
                f.add(gate(overlapOk));
                f.add(gate(longOutShortIn(s)));      // 名字：出场长、进场短
                // Reverb ⇒ 尾巴**很长**（≥3 秒）——与上面那条（<3）互斥
                f.add(gate(s.trailingSpanSecs != null && s.trailingSpanSecs.doubleValue() >= 3.0));
                f.add(gate(matchingBarsOk(s)));
                f.add(melodyFactor(s.melody));
                return f;
            case FILTER_SOFT_REVERB:
                f.add(gate(looseTempo));
                f.add(gate(overlapOk));
                f.add(gate(s.lowClash != null && s.lowClash.doubleValue() < 0.3));
                f.add(melodyFactor(s.melody));
                return f;
            // ---- hiphop / pop：他要求 alias + 两侧拍数（pop 还要出场响度显著）----
            case HIPHOP_LOOPING:
                // 与 HIPHOP_DELAY_OUT 同 base 100 ⇒ 各有区分维（名字即语义）：
                //   Looping = 尾巴长到能循环（vamp）；Delay out = 尾巴短、用延迟接。
                f.add(genreDispatchFactor(s, 1));
                f.add(gate(halfDouble));
                f.add(gate(beatsOk));
                f.add(gate(trailingSpanOk(s)));
                f.add(gate(matchingBarsOk(s)));
                f.add(melodyFactor(s.melody));
                return f;
            case HIPHOP_DELAY_OUT:
                f.add(genreDispatchFactor(s, 1));
                f.add(gate(halfDouble));
                f.add(gate(s.outroBeats != null && s.outroBeats.doubleValue() >= MIN_BEATS));
                f.add(gateWithConfidence(!trailingSpanOk(s), s.trailingSpanSecs != null));
                f.add(melodyFactor(s.melody));
                return f;
            // 两条 pop **同 base 50** ⇒ 各有区分维（名字即语义）：
            //   Pop - Delay out = 尾巴短、用延迟接；Pop - Reverb = 尾巴长、上混响。
            case POP_DELAY_OUT:
                f.add(genreDispatchFactor(s, 3));
                f.add(gate(s.introBeats != null && s.introBeats.doubleValue() >= MIN_BEATS));
                f.add(gateWithConfidence(vocalOverlapOk(s), vocalVerdictReliable(s)));
                f.add(s.outgoingLoudnessSignificant == null
                        ? null : gate(s.outgoingLoudnessSignificant.booleanValue()));
                f.add(gateWithConfidence(!trailingSpanOk(s), s.trailingSpanSecs != null));
                f.add(melodyFactor(s.melody));
                return f;
            case POP_REVERB:
                f.add(genreDispatchFactor(s, 3));
                f.add(gate(s.introBeats != null && s.introBeats.doubleValue() >= MIN_BEATS));
                f.add(gateWithConfidence(vocalOverlapOk(s), vocalVerdictReliable(s)));
                f.add(s.outgoingLoudnessSignificant == null
                        ? null : gate(s.outgoingLoudnessSignificant.booleanValue()));
                f.add(gate(trailingSpanOk(s)));
                f.add(melodyFactor(s.melody));
                return f;
            // ---- dance：他自己的两条（Any Key / Repeater）----
            // 两条 dance **同 base 17** ⇒ 各有区分维（名字即语义）：
            //   Dance - Any Key  = **不挑调性**（调性低/量不出时用它）；
            //   Dance - Repeater = 尾巴能重复（vamp）。
            case DANCE_ANY_KEY:
                f.add(genreDispatchFactor(s, 0));
                f.add(gate(beatsOk));
                f.add(gate(s.keyScore == null || s.keyScore.doubleValue() < KEY_GATE));
                f.add(melodyFactor(s.melody));
                return f;
            case DANCE_REPEATER:
                f.add(genreDispatchFactor(s, 0));
                f.add(gate(overlapOk));
                f.add(gate(s.outroBeats != null && s.outroBeats.doubleValue() >= MIN_BEATS));
                f.add(gate(trailingSpanOk(s)));
                f.add(melodyFactor(s.melody));
                return f;
            // ---- smart-crossfade：他的专用因子是"尾巴长度"——我们用尾斜率当代理 ----
            // **他的 smart-crossfade 生成"四个分数象限"**（档案原话）⇒ 四档按
            // 「进场响度关系 × 尾巴长度」分成四个区域（各有一个我们的代理）：
            //   overlap = 进场不更静+尾巴短 · overlap+Reverb = 不更静+尾巴长
            //   Ring-out = 更静+尾巴短     · Ring-out+Reverb = 更静+尾巴长
            case CROSSFADE_OVERLAP:
                f.add(gate(s.incomingQuieter != null && !s.incomingQuieter.booleanValue()));
                f.add(gate(s.trailingSpanSecs != null && s.trailingSpanSecs.doubleValue() < MIN_TRAILING_SPAN_SECS));
                f.add(melodyFactor(s.melody));
                return f;
            case CROSSFADE_OVERLAP_REVERB:
                f.add(gate(s.incomingQuieter != null && !s.incomingQuieter.booleanValue()));
                f.add(gate(s.trailingSpanSecs != null && s.trailingSpanSecs.doubleValue() >= MIN_TRAILING_SPAN_SECS));
                f.add(melodyFactor(s.melody));
                return f;
            case CROSSFADE_RINGOUT:
                f.add(gate(s.incomingQuieter != null && s.incomingQuieter.booleanValue()));
                f.add(gate(s.trailingSpanSecs != null && s.trailingSpanSecs.doubleValue() < MIN_TRAILING_SPAN_SECS));
                f.add(melodyFactor(s.melody));
                return f;
            case CROSSFADE_RINGOUT_REVERB:
                f.add(gate(s.incomingQuieter != null && s.incomingQuieter.booleanValue()));
                f.add(gate(s.trailingSpanSecs != null && s.trailingSpanSecs.doubleValue() >= MIN_TRAILING_SPAN_SECS));
                f.add(melodyFactor(s.melody));
                return f;
            // 我们自己那三条（base 10）：都以"进场响度关系**没量到**"为前提 ⇒ 与他的四象限
            // （那两项必须**量到**）互斥，于是各占一块，不再平手压人。
            case CROSSFADE_LONG:
                f.add(gate(s.incomingQuieter == null));
                f.add(gate(longWindow(s)));                 // 长交叉淡化 ⇒ 窗口够长
                f.add(tempoGateFactor(s));
                f.add(melodyFactor(s.melody));
                return f;
            case TAIL_RIDE:
                f.add(gate(s.incomingQuieter == null));
                f.add(gate(s.trailingSpanSecs != null
                        && s.trailingSpanSecs.doubleValue() >= 3.0));   // 尾巴**很长**才谈得上骑行
                f.add(gate(!longWindow(s)));                // 骑尾音不需要长窗口
                f.add(melodyFactor(s.melody));
                return f;
            case PLAIN_BLEND:
                f.add(gate(s.incomingQuieter == null && s.trailingSpanSecs == null));
                f.add(tempoGateFactor(s));
                f.add(gate(overlapOk));
                f.add(melodyFactor(s.melody));
                return f;
            // ---- filtered 的滤波条（低频真的撞）----
            case BASS_SWAP:
                // 名字就是"低频撞了才**换低音**" ⇒ 低频不撞时不该用它
                // （否则它 base 20 会一路压掉 base 10 的 smart 族）。
                f.add(tempoGateFactor(s));
                f.add(gate(s.lowClash != null && s.lowClash.doubleValue() >= 0.6));
                f.add(reasonBonus(s.lowClash));      // 低频**撞**是"换低音"的理由 ⇒ 加成（不是惩罚）
                f.add(gate(overlapOk));
                f.add(melodyFactor(s.melody));
                return f;
            // ---- 他的 smart-crossfade 里"没有东西可叠"的那几档（无评分因子，base 1 是地板）----
            case NO_VOCAL_CLASH:
                f.add(gateWithConfidence(vocalOverlapOk(s), vocalVerdictReliable(s)));
                f.add(gate(overlapOk));
                f.add(melodyFactor(s.melody));
                return f;
            // 三条同 base 1（地板）⇒ 必须各有区分维，否则平手靠顺序 ⇒ 只能一个赢：
            //   GAPLESS   = 有整小节可依（>=4 拍）但可叠极短 ⇒ 无缝拼上
            //   BEAT_CUT  = 有拍但**不足一小节**（1..3 拍）⇒ 按拍切
            //   SPLICE    = 连拍都没量到（<1 拍）⇒ 只能硬拼
            case GAPLESS:
                f.add(gate(shortOverlap));
                f.add(gate(s.outroBeats != null && s.outroBeats.doubleValue() >= 4.0));
                f.add(melodyFactor(s.melody));
                return f;
            case BEAT_CUT:
                f.add(gate(shortOverlap));
                f.add(gate(s.outroBeats != null && s.outroBeats.doubleValue() >= 1.0
                        && s.outroBeats.doubleValue() < 4.0));
                f.add(melodyFactor(s.melody));
                return f;
            case SPLICE:
                f.add(gate(shortOverlap));
                f.add(gate(s.outroBeats == null || s.outroBeats.doubleValue() < 1.0));
                f.add(melodyFactor(s.melody));
                return f;
            // ---- 我们独有的两档（他清单里没有）----
            case SHOW_MODE:
                f.add(gate(s.buildUp != null && s.buildUp.booleanValue()));
                f.add(reasonBonus(s.dropReliable));   // 有 build-up/拐点是"表现模式"的理由 ⇒ 加成
                f.add(melodyFactor(s.melody));
                return f;
            case LEARNED_EQ:
                // 学习 EQ 用在"**有明确拐点**"的对子上（我们自己的判据；它与他的 24 条无关）
                f.add(tempoGateFactor(s));
                // 窄带：拐点**明确但不极端**（0.4..0.6）且低频**中等冲突**（0.3..0.5）——
                // 我们这条 base 30 比他的 20 档高，若不收窄就会到处赢（实测踩过）。
                // "拐点**很强**"（≥0.7）且低频**真撞**（≥0.6）—— 用下界而不是窄带：
                // 窄带会把真实素材（实测低频 1.00 / drop 0.91 那一对）排除在外 ✗。
                // 下界够高就不会去抢 base 20 那些案例（它们的 drop 多在 0.5 上下）。
                f.add(gate(s.dropReliable != null && s.dropReliable.doubleValue() >= 0.7
                        && s.lowClash != null && s.lowClash.doubleValue() >= 0.6));
                f.add(num(s.lowClash));
                f.add(reasonBonus(s.dropReliable));  // 拐点明显是"用学习 EQ"的理由 ⇒ 加成
                f.add(melodyFactor(s.melody));
                return f;
            case AUTO:
            default:
                return null;                   // AUTO 不是候选，而是"去挑"这件事本身
        }
    }

    /** 尾巴"够长"的门槛（dB/s；越接近 0 越缓）。 */
    static final double LONG_TAIL_DB_PER_SEC = -3.0;

    /** 出场**长**、进场**短**（LONG_OUT_SHORT_IN 那一族名字里的意思）。 */
    static boolean longOutShortIn(Signals s) {
        return s.outroBeats != null && s.introBeats != null
                && s.outroBeats.doubleValue() >= 8.0 && s.introBeats.doubleValue() < 8.0;
    }

    /** 窗口"够长"的门槛（拍）——他 id 12 叫 "Long filter" 的那一维。 */
    static boolean longWindow(Signals s) {
        return s.outroBeats != null && s.introBeats != null
                && s.outroBeats.doubleValue() >= 8.0 && s.introBeats.doubleValue() >= 8.0;
    }

    /** 尾巴"够长"的门槛（秒）——{@code trailing_span_seconds} 代理用。 */
    public static final double MIN_TRAILING_SPAN_SECS = 1.0;

    /** 尾巴是否够长（代理；没量到 ⇒ 交给调用方按"缺失"处理）。 */
    static boolean trailingSpanOk(Signals s) {
        return s.trailingSpanSecs != null
                && s.trailingSpanSecs.doubleValue() >= MIN_TRAILING_SPAN_SECS;
    }

    /** 进场侧是否更安静（代理；没量到 ⇒ 交给调用方按"缺失"处理）。 */
    static boolean incomingQuieterOk(Signals s) {
        return s.incomingQuieter != null && s.incomingQuieter.booleanValue();
    }

    /**
     * 两侧可叠拍数是否基本相等（{@code matching_bar_count} 的代理）。
     *
     * <p>第一版"量不到就算过"是**不忠实**的 ✗：他这一项的意思是"两边的**小节**对得上"，
     * 而"根本没地方放小节"（可叠极短 / 拍数量不出来）显然是**对不上** ⇒ 现在返回 false。
     * 这正是判据 `noOverlapGoesToTheGaplessFamily` 抓出来的（它发现"没东西可叠"时
     * FILTER_EXPANSION 还照样过闸门 ✗）。
     */
    static boolean matchingBarsOk(Signals s) {
        if (s.outroBeats == null || s.introBeats == null) {
            return false;                    // 量不到 ⇒ **不过**（见下）
        }
        double a = s.outroBeats.doubleValue();
        double b = s.introBeats.doubleValue();
        if (a < 4.0 || b < 4.0) {
            return false;                    // 连一整小节都放不下 ⇒ 谈不上"落在小节边界上"
        }
        // **语义修正**：他这一项说的是"两侧**各自**的过渡段落是否落在整小节边界上"，
        // 而**不是**"两侧小节数相等" —— 后者会让 LONG_OUT_SHORT_IN（出场长、进场短）
        // 自相矛盾（判据当场指出它分数为 0）。
        return a % 4.0 == 0.0 && b % 4.0 == 0.0;
    }

    /**
     * <b>人声存在的代理：中置/侧向能量比</b>（mid/side）—— 我们自己的手法。
     *
     * <p>为什么要它：{@code vocal_overlap} 这个闸门此前**根本没有信号来源**
     * （{@code vocalOverlapSecs} 在生产里是 null ⇒ 闸门无条件放行 ✗），
     * 于是高 base 的 {@code FILTER_EXPANSION} 在任何素材上都过 ⇒ 其它档选不到 ✗。
     *
     * <p>做法：人声（尤其主唱）在混音里通常**居中** ⇒ 中置能量显著高于侧向能量。
     * 取若干帧算 {@code mid = (L+R)/2}、{@code side = (L−R)/2}，
     * 返回 {@code mid² / (mid² + side²)}（0.5 = 完全无方向性；越高越像"中间有人声"）。
     *
     * <p>⚠️ 这是**代理**，不是真 VAD：所以它只在"明显居中"时才用作**可靠**依据
     * （见 {@link #vocalPresenceIsReliable}），否则如实按不可靠处理（软因子 0.75）✓。
     *
     * @return 0..1；素材不可用 ⇒ {@link Double#NaN}（缺失，不猜）
     */
    public static double centreRatio(float[] left, float[] right, int frames) {
        if (left == null || right == null || frames <= 0) {
            return Double.NaN;
        }
        int n = Math.min(frames, Math.min(left.length, right.length));
        double mid = 0.0;
        double side = 0.0;
        for (int i = 0; i < n; i += 16) {              // 抽样即可（省时间，够稳）
            double m = (left[i] + right[i]) * 0.5;
            double sd = (left[i] - right[i]) * 0.5;
            mid += m * m;
            side += sd * sd;
        }
        double total = mid + side;
        return total <= 1e-12 ? Double.NaN : mid / total;
    }

    /** 中置比高到这个程度，才敢把它当**可靠**依据（否则按代理处理）。 */
    public static final double CENTRE_RELIABLE_MIN = 0.75;

    /** 这条"人声存在"的判断够不够可靠（够可靠才允许硬筛）。 */
    public static boolean vocalPresenceIsReliable(double centreRatio) {
        return !Double.isNaN(centreRatio) && centreRatio >= CENTRE_RELIABLE_MIN;
    }

    /** 叠唱是否"不撞"（人声重叠少）。 */
    /**
     * 叠唱"撞不撞"：**先看强度加权重叠 + 人声类别，其次才退回秒数阈值**（2026-10-07 接线）。
     *
     * <p>为什么换：0.5 s 的秒数阈值**分不出**"一边轻哼"与"两边都在吼" ✗，而真正听得出来的
     * 是人声撞车的那一瞬间 ✓。类别则决定容忍度 —— 说唱叠一句常常听不出来，两条主旋律
     * 叠起来立刻糊 ✗（用户点名的第 1、2 条）。
     *
     * <p>容忍表是**我们自己的**（他那边只给 kind，没给表 ✗）：唱 0.25 · 说话 0.5 · 说唱 0.6 ——
     * 依次放宽，理由是"越像乐音、叠起来越糊" ✓。写死、可判据 ✓。
     *
     * <p>两个信号缺一个就**如实退回秒数阈值** ✓（不猜），并且 {@code gateWithConfidence}
     * 会按"可靠/不可靠"决定是硬门还是 0.75 软因子 ✓。
     */
    static boolean vocalOverlapOk(Signals s) {
        Double strength = s.vocalOverlapStrength;
        Integer kind = s.vocalKind;
        if (strength != null && !Double.isNaN(strength.doubleValue())
                && kind != null && kind.intValue() >= 0) {
            return strength.doubleValue() <= vocalToleranceFor(kind.intValue());
        }
        return s.vocalOverlapSecs == null || s.vocalOverlapSecs.doubleValue() < 0.5;
    }

    /** 叠唱容忍度（按人声类别；越大越能容忍叠在一起）。 */
    static double vocalToleranceFor(int kind) {
        switch (kind) {
            case 2:  return 0.6;             // 说唱：叠一句常常听不出来
            case 1:  return 0.5;             // 说话
            default: return 0.25;            // 唱：越像乐音、叠起来越糊
        }
    }

    /**
     * 这一对的叠唱判断**够不够可靠**（够可靠才允许硬筛，否则交给我们的软因子 0.75 ✓）。
     *
     * <p>判据：强度加权重叠**量到了**，而且类别**分出来了**（−1 = 量不出来，如实不算可靠 ✓）
     * —— 或者退一步：秒数那一路本身是可靠的（{@code vocalOverlapReliable} ✓）。
     */
    static boolean vocalVerdictReliable(Signals s) {
        Double strength = s.vocalOverlapStrength;
        Integer kind = s.vocalKind;
        boolean byStrength = strength != null && !Double.isNaN(strength.doubleValue())
                && kind != null && kind.intValue() >= 0;
        return byStrength || Boolean.TRUE.equals(s.vocalOverlapReliable);
    }

    /** 这一对是不是倍半/同速（他清单里的 tempo_alias 因子；没有 bpm ⇒ 无从判断 ⇒ 不满足）。 */
    static boolean aliasPair(Signals s) {
        return s.bpmA != null && s.bpmB != null
                && tempoAlias(s.bpmA.doubleValue(), s.bpmB.doubleValue()) != 0.0;
    }
}
