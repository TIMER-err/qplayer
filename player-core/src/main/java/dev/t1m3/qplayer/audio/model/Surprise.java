package dev.t1m3.qplayer.audio.model;

/**
 * 惊喜度（2026-10-11 ✓ automix 标准 **L1.6 加分项** ✓ 用户提出：「熵与预测误差评估惊喜度」✓）。
 *
 * <p><b>定位（不可动摇 ✓）：加分项</b> —— 不参与否决 ✗、不设门槛 ✗、不影响降级 ✗。
 * 它的唯一用途：在**同一小节网格内**的若干候选里给一个先后顺序 ✓ 且加成**限幅** ✓。
 *
 * <p>三路成分（全部 O(n) ✓ 不需要 FFT ✓）：
 * <ul>
 *   <li>{@link #onsetEntropy} —— 起音强度直方图的归一化香农熵 ✓
 *       低 = 律动稳定可预期 ✓ 高 = 花活多/密集 ✗</li>
 *   <li>{@link #beatPredictionError} —— 1/8 拍网格上"有/无起音"的**二阶马尔可夫** ✓
 *       在 t 处算 −log P(观测 | 前两格) ✓ 低 = 该响的响了、该停的停了 ✓</li>
 *   <li>{@link #novelty} —— 近 4 小节 vs 前 4 小节的能量分带**余弦距离** ✓
 *       高 = 结构在变（进副歌/转段）✓</li>
 * </ul>
 */
public final class Surprise {

    private Surprise() {}

    /** 归一化香农熵 ∈ [0,1]（除以 log(桶数) ✓）。 */
    public static double onsetEntropy(float[] onset, int from, int to) {
        if (onset == null || onset.length == 0) return 0;
        from = Math.max(0, from);
        to = Math.min(onset.length, Math.max(to, from + 1));
        final int buckets = 8;
        final double[] hist = new double[buckets];
        double max = 1e-9;
        for (int i = from; i < to; i++) max = Math.max(max, onset[i]);
        double total = 0;
        for (int i = from; i < to; i++) {
            final int b = Math.min(buckets - 1, (int) (onset[i] / max * buckets));
            hist[b] += 1;
            total += 1;
        }
        double h = 0;
        for (double v : hist) {
            if (v <= 0) continue;
            final double p = v / total;
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h / (Math.log(buckets) / Math.log(2));
    }

    /**
     * 二阶马尔可夫的节拍预测误差 ∈ [0,1] ✓。
     *
     * <p>把区间按 {@code periodFrames} 的 1/8 切格 ✓ 每格"有无起音"当二值事件 ✓
     * 用区间内统计出的二阶转移概率 ✓ 在每个格子上算 −log2 P(观测 | 前两格) ✓ 取平均并归一化 ✓。
     */
    public static double beatPredictionError(float[] onset, int from, int to, int periodFrames) {
        if (onset == null || periodFrames < 8) return 0;
        from = Math.max(0, from);
        to = Math.min(onset.length, Math.max(to, from + periodFrames));
        final int step = Math.max(1, periodFrames / 8);
        final int n = (to - from) / step;
        if (n < 16) return 0;
        final boolean[] on = new boolean[n];
        double mean = 0;
        for (int i = 0; i < n; i++) mean += onset[from + i * step];
        mean /= n;
        final double threshold = Math.max(mean * 1.2, 1e-9);
        for (int i = 0; i < n; i++) on[i] = onset[from + i * step] > threshold;
        // 二阶转移计数：state = (prev2, prev1) ⇒ 4 种 ✓
        final double[][] count = new double[4][2];
        for (int i = 2; i < n; i++) {
            count[state(on[i - 2], on[i - 1])][on[i] ? 1 : 0] += 1;
        }
        double sum = 0; int used = 0;
        for (int i = 2; i < n; i++) {
            final int s = state(on[i - 2], on[i - 1]);
            final double a = count[s][0] + 0.5, b = count[s][1] + 0.5;   // 拉普拉斯 ✓
            final double p = on[i] ? b / (a + b) : a / (a + b);
            sum += -Math.log(p) / Math.log(2);
            used++;
        }
        if (used == 0) return 0;
        return Math.min(1, sum / used);      // 1 bit+ ⇒ 视为满 ✓
    }

    private static int state(boolean a, boolean b) { return (a ? 2 : 0) + (b ? 1 : 0); }

    /** 段间新颖度 ∈ [0,1] ✓（近 4 小节 vs 前 4 小节的能量分带余弦距离 ✓）。 */
    public static double novelty(float[] onset, int from, int to, int periodFrames) {
        if (onset == null || periodFrames < 8) return 0;
        from = Math.max(0, from);
        to = Math.min(onset.length, Math.max(to, from + 2 * periodFrames));
        final int bar = periodFrames * 4;
        if (to - from < 2 * bar) return 0;
        // **先去均值**再算余弦 ✓：不去均值的话"整体变响"得到的两个带向量**平行** ✓
        // ⇒ 余弦距离恒为 0 ✗ 从结构变化里看不出响度/密度变化 ✓（判据
        //   `changingStructureRaisesNovelty` 就是这么抓到的 ✓）
        final double[] recent = centered(bands(onset, to - bar, to));
        final double[] older = centered(bands(onset, to - 2 * bar, to - bar));
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < recent.length; i++) {
            dot += recent[i] * older[i];
            na += recent[i] * recent[i];
            nb += older[i] * older[i];
        }
        if (na <= 0 || nb <= 0) return 0;
        final double cos = dot / Math.sqrt(na * nb);
        return Math.max(0, Math.min(1, (1 - cos)));
    }

    private static double[] centered(double[] v) {
        double mean = 0;
        for (double x : v) mean += x;
        mean /= Math.max(1, v.length);
        final double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] - mean;
        return out;
    }

    private static double[] bands(float[] onset, int from, int to) {
        final int buckets = 8;
        final double[] out = new double[buckets];
        from = Math.max(0, from);
        to = Math.min(onset.length, Math.max(to, from + 1));
        final int span = Math.max(1, (to - from) / buckets);
        for (int b = 0; b < buckets; b++) {
            double sum = 0;
            for (int i = from + b * span; i < Math.min(to, from + (b + 1) * span); i++) sum += onset[i];
            out[b] = sum;
        }
        return out;
    }

    /**
     * 综合加分（0~1 ✓）。默认权重 1/3 各 ✓；
     * <b>调用方必须限幅</b>——见标准文档 L1.6 的三条约束 ✓。
     */
    public static double score(float[] onset, int from, int to, int periodFrames) {
        final double h = onsetEntropy(onset, from, to);
        final double pe = beatPredictionError(onset, from, to, periodFrames);
        final double nv = novelty(onset, from, to, periodFrames);
        return (h + pe + nv) / 3.0;
    }
}
