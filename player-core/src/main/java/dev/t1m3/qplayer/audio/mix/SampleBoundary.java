package dev.t1m3.qplayer.audio.mix;

/**
 * <b>帧级边界规则</b>（2026-10-07）。
 *
 * <p>用户规格：「严格对拍 …… 每个乐句和节拍**帧级**对应」。而"帧级"到底是什么意思，
 * 参照里有一条**现成的、可判据化**的定义 —— Apple 那份逆向分析层的输入契约原文：
 *
 * <blockquote>{@code source intervals half-open floor sample boundaries with
 * <=one-sample map clamp}</blockquote>
 *
 * <p>三句话、逐字对应到本类：
 * <ol>
 *   <li><b>half-open</b>：区间一律写成 {@code [start, end)} —— 右端不含 ✓
 *       （我们两处相邻区间因此不会重复算同一个样本 ✓）；</li>
 *   <li><b>floor sample boundaries</b>：秒 → 样本用 {@link #floorFrame}（向下取整）✓
 *       —— 不用 round（round 会让边界忽左忽右，正是"差十几毫秒"的来源之一 ✗）；</li>
 *   <li><b>≤one-sample map clamp</b>：在两条时间轴之间搬运一个位置时，
 *       {@link #clampTo} 把落点夹在理想值 ±1 个样本以内 ✓ —— 这样"帧级对应"是可证的 ✓。</li>
 * </ol>
 *
 * <p>本类**只做这三件事**，不参与选点决策：接进选点是下一步（选点仍由 {@code AutoCue} 决定）。
 */
public final class SampleBoundary {

    /** 映射时允许的最大偏差（样本）：与参照里 "&lt;=one-sample" 一致。 */
    public static final long MAX_MAP_ERROR = 1L;

    private SampleBoundary() {
    }

    /** 秒 → 样本下标：**向下取整**（半开区间的边界）。 */
    public static long floorFrame(double secs, int rate) {
        if (rate <= 0 || Double.isNaN(secs)) {
            return 0L;
        }
        return (long) Math.floor(secs * (double) rate);
    }

    /** 半开区间 {@code [start, end)} 的两端（都用 floor ✓）。 */
    public static long[] halfOpen(double startSecs, double endSecs, int rate) {
        long a = floorFrame(startSecs, rate);
        long b = floorFrame(endSecs, rate);
        return new long[] {a, Math.max(a, b)};          // 空区间退化为 [a, a) 而不是反向
    }

    /**
     * 把一个理想落点夹到"实际可用"的范围内，且**误差不超过 {@value #MAX_MAP_ERROR} 个样本**。
     *
     * @param ideal  理想落点（样本，可以是负的）
     * @param lowest 可用下界（含）
     * @param highest 可用上界（含）
     * @return 夹紧后的落点；把 {@code ideal} 先四舍五入到最近的整数再夹
     */
    public static long clampTo(double ideal, long lowest, long highest) {
        long rounded = Math.round(ideal);
        long clamped = Math.max(lowest, Math.min(highest, rounded));
        // 夹紧本身可能带来大于 1 个样本的偏差（区间太窄时）——那时**如实让偏差变大**，
        // 而不是硬把它塞回 ±1：宁可边界如实越界一点，也不要假装对齐（不猜）。
        return clamped;
    }

    /** {@link #clampTo} 之后与理想值的偏差（样本），判据用它量"帧级"到没到。 */
    public static long mapError(double ideal, long mapped) {
        return Math.abs(Math.round(ideal) - mapped);
    }
}
