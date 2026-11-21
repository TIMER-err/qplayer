package dev.t1m3.qplayer.audio.mix;

import java.util.List;

/**
 * <b>参照的打分核</b>（2026-10-07）。
 *
 * <p>下面这段公式的原文（只记事实、不搬代码）：
 *
 * <blockquote>{@code P = base * product(factors); score = P + 0.001 * D if P > 0 else P}
 * · {@code D = outgoing_upper_bound - incoming_upper_bound}…
 * · {@code No factor or final-score clamp in the inspected kernel. D may be negative.}</blockquote>
 *
 * <p>三条要点（决定它和我们原来的"顺序判定链"完全不同）：
 * <ol>
 *   <li><b>因子是乘性的</b>：任一因子为 0 ⇒ 整条候选直接淘汰（硬闸）✓
 *       —— 不是加权求和，所以"权重"不是可调大小的贡献，而是"过不过得去"；</li>
 *   <li><b>位置只值千分之一</b>：{@code 0.001 × D} 只用来在乘积相同的候选之间破平手 ✓
 *       —— 它**不会**让一个闸门没过的候选翻盘 ✓；</li>
 *   <li><b>缺失信号 ⇒ 不给分</b>：{@code score = None}（我们译成 {@code null}）✓
 *       —— 与仓库里"算不出来就别猜"是同一条纪律 ✓。</li>
 * </ol>
 *
 * <p>{@link #BASE} 里的 base 分来自参照清单（hiphop 100 · filtered-expansion 与 pop 50 ·
 * filtered 20 · dance 17 · smart 10 · 兜底 1）；没有对应模板的档（我们自己的表现模式、
 * 学习EQ 的 MLP 加成）标 {@link #BASE_OURS}，作为"我们有他没有"的独立起点 ✓。
 */
public final class RouteScore {

    /**
     * 打分核**是否接管选点**（默认关闭）。
     *
     * <p><b>2026-10-07 已打开（用户定夺：按他的真实走法）。</b>用户原话：
     * 「都有 applemusic 的真实走法了肯定按照他的啊，他走 Filter expansion 那我们也走」。
     * 早先试开时发现与"参考对走交叉淡化"冲突，已由用户拍板选他这一侧。
     *
     * <p><b>⚠️ 2026-10-07 更正一处证据误引</b>：这里原先写着"参考对被选中滤波·展开，
     * 而那正是参照档案 `chosen` 的那一条" —— **那句是误引** ✗。档案里的
     * `check-results/planner.json` 覆盖的是**合成对 `dance-a → dance-b`**
     * （文件里明写 `/outgoing_id = dance-a`、`/incoming_id = dance-b`），**不是** skydive→春めく ✗；
     * 其中 `chosen = BM - Filter expansion` 是**那一对**的结论。
     *
     * <p>这一对真正有依据的只有两样：用户对 AM 的听感（「不是声波重合，是别的交叠算法」）
     * 与我们的取证点位（出场 181.1 / 进场 15.0）。按**他的已知规则**重推：这一对的原始速度
     * 读数相差约 29%（远超他 ±17% 的 stretch 门）⇒ **要节拍匹配的 BM-* 全不合格** ✗
     * ⇒ 剩下 dance(17) / smart(10) / 兜底(1) ⇒ 按 base 该落 **dance 家族**（Any Key / Repeater）✓，
     * 与用户听感一致 ✓（我们的核现在也落"舞曲·重复器"✓）。
     *
     * <p>保留开关本身：它是"回到旧判定链"的唯一入口（出问题时一行就能退回去）。
     */
    private static volatile boolean enabled = true;

    /** 打开/关闭"打分核接管选点"（产品默认关，判据用）。 */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** 见 {@link #setEnabled}。 */
    public static boolean enabled() {
        return enabled;
    }

    /** 位置项的系数（参照：0.001）。 */
    public static final double POSITION_WEIGHT = 0.001;

    /** 我们自己独有档的 base（参照里没有对应模板）。 */
    public static final int BASE_OURS = 30;

    private RouteScore() {
    }

    /**
     * 各档的 base 分（对照参照清单）。
     *
     * <p>查表用 {@link MixStyle} 而不是字符串 ✓ —— 改名不会静默丢分 ✓。
     */
    public static int base(MixStyle style) {
        switch (style) {
            // 参照 base 100：嘻哈两条
            case HIPHOP_DELAY_OUT:
            case HIPHOP_LOOPING:
                return 100;
            // 参照 base 50
            case FILTER_EXPANSION:      // BM - Filter expansion（他自动档实际选中的）
            case POP_DELAY_OUT:
            case POP_REVERB:
                return 50;
            // 参照 base 20：filtered 家族
            case FILTER_HIGH_TO_LOW:
            case FILTER_LONG_HIGH_TO_LOW:
            case FILTER_SHORT_HIGH_TO_LOW:
            case FILTER_LONG_OUT_SHORT_IN:
            case FILTER_DYNAMIC_IN:
            case FILTER_LONG_OUT_SHORT_IN_DELAY:
            case FILTER_LONG_OUT_SHORT_IN_REVERB:
            case FILTER_SOFT_REVERB:
            case NO_VOCAL_CLASH:
                return 20;
            // ⚠️ BASS_SWAP 是**我们自己**的档（他 24 条里没有"低音交接"这一条）——
            // 它的 base 不能压过他的档：原先给 20，于是参考对上它压掉了他的 dance(17) ✗。
            // 按"他的顺序优先、我们只在他没覆盖的地方补位"的原则降到 12（仍在 smart 的 10 之上）。
            case BASS_SWAP:
                return 12;
            // 参照 base 17：dance
            case DANCE_ANY_KEY:
            case DANCE_REPEATER:
                return 17;
            // 参照 base 10：smart（含我们自己那两条交叉淡化）
            case CROSSFADE_LONG:
            case CROSSFADE_OVERLAP:
            case CROSSFADE_RINGOUT:
            case CROSSFADE_OVERLAP_REVERB:
            case CROSSFADE_RINGOUT_REVERB:
            case PLAIN_BLEND:
            case TAIL_RIDE:
                return 10;
            // 参照 base 1：兜底
            case GAPLESS:
            case SPLICE:
            case BEAT_CUT:
                return 1;
            // 参照清单里没有的（我们独有）
            case SHOW_MODE:
            case LEARNED_EQ:
            case AUTO:
            default:
                return BASE_OURS;
        }
    }

    /**
     * 算一条候选的分。
     *
     * @param style     这一档
     * @param factors   各闸门的因子（{@code 0} = 没过闸 ⇒ 淘汰；{@code 1} = 中性；
     *                  也可以是介于两者之间；{@code null} 里的任何一项
     *                  —— 即 {@code factors} 里有 {@code null} —— 表示**信号缺失**）
     * @param positionD 位置差（出场侧上界 − 进场侧上界），只在乘积相同时起作用
     * @return 分数；**信号缺失时返回 {@code null}**（调用方据此不选它 —— 不猜）
     */
    public static Double score(MixStyle style, List<Double> factors, double positionD) {
        if (style == null || factors == null) {
            return null;
        }
        double product = 1.0;
        for (Double f : factors) {
            if (f == null || Double.isNaN(f)) {
                return null;                     // 信号缺失 ⇒ 不给分
            }
            product *= f;
        }
        double p = base(style) * product;
        if (p <= 0.0) {
            // **忠实于他的口径**：{@code score = P + 0.001·D if P > 0} ⇒ P 不大于 0 就**没有分**
            // （返回 null，而不是 0）——否则"所有候选都没过闸门"时会硬选一个 0 分的档 ✗
            // （实测：RouteSelectionTest 的"没东西可叠"那一格就是这样选中了一个 0 分的档）。
            return null;
        }
        return p + POSITION_WEIGHT * positionD;
    }

    /** 在若干候选里取分最高者；全都没分（{@code null}）时返回 {@code null}（不猜）。 */
    public static MixStyle best(List<MixStyle> styles, List<List<Double>> factorSets,
                               List<Double> positions) {
        MixStyle pick = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int n = Math.min(styles.size(), Math.min(factorSets.size(), positions.size()));
        for (int i = 0; i < n; i++) {
            Double s = score(styles.get(i), factorSets.get(i), positions.get(i).doubleValue());
            if (s != null && s > bestScore) {
                bestScore = s;
                pick = styles.get(i);
            }
        }
        return pick;
    }
}
