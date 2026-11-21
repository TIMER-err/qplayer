package dev.t1m3.qplayer.audio.dsp;

/**
 * <b>BS.1770 积分响度（LUFS）</b>（2026-10-07，用户要求"用 LUFS 做响度归一化更精准"）。
 *
 * <p>做法照标准三件：<b>K 加权</b>（高架 + 高通两级 biquad）→ 400 ms 块（75% 重叠，
 * 双声道按 BS.1770 的通道和相加）→ <b>两级门限</b>（绝对 −70 LUFS + 相对 −10 LU）。
 *
 * <p>刻度（可直接验算，判据就是照它写的）：振幅 A 的正弦均方为 A²/2，于是
 * <pre>
 *   双声道各 −20 dBFS（A = 0.1）⇒ Σz = 2 × 0.005 = 0.01
 *   L = −0.691 + 10·log10(0.01) = −20.69 LUFS
 * </pre>
 *
 * <p>与 {@code tools/automix-ml/objective_metrics.py} 里那份的关系：那边是**未加门限**的
 * 版本（当时只用于三档互比），这里是**加门限**的正式版 —— 门限决定"静音段不算数"，
 * 所以两者在同一素材上可以差很多（这是正常的，判据要按同一把尺子量）。
 */
public final class Lufs {

    /** 绝对门限（LUFS）：块响度低于它直接丢弃。 */
    public static final double ABSOLUTE_GATE = -70.0;

    /** 相对门限（LU）：相对"过绝对门限那些块的均值"再降这么多。 */
    public static final double RELATIVE_GATE = -10.0;

    /** 块长（秒）与重叠（BS.1770：400 ms、75% 重叠 ⇒ 步长 100 ms）。 */
    static final double BLOCK_SECS = 0.4;
    static final double HOP_SECS = 0.1;

    private Lufs() {
    }

    /**
     * 积分响度。
     *
     * @param left  左声道（可 null ⇒ 按单声道处理）
     * @param right 右声道（可 null）
     * @return LUFS；没有一块过门限时返回 {@link Double#NEGATIVE_INFINITY}
     */
    public static double integrated(float[] left, float[] right, int rate) {
        if (rate <= 0 || left == null || left.length == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        int frames = Math.min(left.length, right == null ? left.length : right.length);
        float[] l = kWeight(left, rate, frames);
        float[] r = right == null ? null : kWeight(right, rate, frames);
        int block = Math.max(1, (int) Math.round(BLOCK_SECS * rate));
        int hop = Math.max(1, (int) Math.round(HOP_SECS * rate));
        if (frames < block) {
            return Double.NEGATIVE_INFINITY;
        }
        // 第一遍：算每块的响度，收集过绝对门限的块
        java.util.List<Double> powers = new java.util.ArrayList<Double>();
        java.util.List<Double> loudness = new java.util.ArrayList<Double>();
        for (int at = 0; at + block <= frames; at += hop) {
            double sum = 0.0;
            for (int i = at; i < at + block; i++) {
                sum += (double) l[i] * l[i];
                if (r != null) {
                    sum += (double) r[i] * r[i];
                }
            }
            double z = sum / block;
            double level = -0.691 + 10.0 * Math.log10(Math.max(z, 1e-12));
            if (level > ABSOLUTE_GATE) {
                powers.add(Double.valueOf(z));
                loudness.add(Double.valueOf(level));
            }
        }
        if (powers.isEmpty()) {
            return Double.NEGATIVE_INFINITY;
        }
        // 第二遍：相对门限（相对"过绝对门限那些块"的均值）
        double mean = 0.0;
        for (Double p : powers) {
            mean += p.doubleValue();
        }
        mean /= powers.size();
        double relative = -0.691 + 10.0 * Math.log10(Math.max(mean, 1e-12)) + RELATIVE_GATE;
        double kept = 0.0;
        int n = 0;
        for (int i = 0; i < powers.size(); i++) {
            if (loudness.get(i).doubleValue() > relative) {
                kept += powers.get(i).doubleValue();
                n++;
            }
        }
        if (n == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        return -0.691 + 10.0 * Math.log10(Math.max(kept / n, 1e-12));
    }

    /**
     * 归一化**是否用于实际混音路径**（默认关闭）。
     *
     * <p>与 {@code RouteScore.setEnabled} 同一套做法：新的听感改动要先能被单独验证。
     * 关着的时候，过渡段两侧的响度仍由原来的逐帧匹配负责 ✓（零行为变化）；
     * 打开之后，两侧素材会先按**积分响度**对齐（更接近"等响"✓），再走原来的匹配。
     */
    private static volatile boolean normalizationEnabled = false;

    /** 打开/关闭"用 LUFS 对齐过渡两侧的响度"（产品默认关，判据用）。 */
    public static void setNormalizationEnabled(boolean on) {
        normalizationEnabled = on;
    }

    /** 见 {@link #setNormalizationEnabled}。 */
    public static boolean normalizationEnabled() {
        return normalizationEnabled;
    }

    /** 归一化时允许的最大增益（dB）：超出这个范围说明素材差别太大，宁可不动。 */
    public static final double MAX_NORMALIZE_DB = 12.0;

    /**
     * <b>把 source 归一到 target 的响度所需的线性增益</b>（LUFS 第 2 步的核心原语）。
     *
     * <p>做法就是"量—补—再量"里的第一步：两边各算一次积分响度，差多少补多少。
     * 三处保护，都是踩过坑才加的：
     * <ul>
     *   <li>任一侧量不出响度（全静音 ⇒ 负无穷）⇒ 返回 1（**不动**，别把静音放大成噪声）；</li>
     *   <li>增益限制在 ±{@value #MAX_NORMALIZE_DB} dB 以内（差太多说明这两段不是同一类素材，
     *       硬拉会把安静的段落抬成嘶声）；</li>
     *   <li>返回值恒为正、有限（调用方可以直接相乘）。</li>
     * </ul>
     *
     * @param source 要被调整的素材（单声道；双声道时传各自的一路）
     * @param target 参照素材
     */
    public static double gainToMatch(float[] source, float[] target, int rate) {
        double a = integrated(source, null, rate);
        double b = integrated(target, null, rate);
        if (Double.isInfinite(a) || Double.isInfinite(b) || Double.isNaN(a) || Double.isNaN(b)) {
            return 1.0;
        }
        double db = b - a;
        db = Math.max(-MAX_NORMALIZE_DB, Math.min(MAX_NORMALIZE_DB, db));
        return Math.pow(10.0, db / 20.0);
    }

    /** 按 {@link #gainToMatch} 就地施加增益（返回是否真的动过）。 */
    public static boolean normalizeTo(float[] source, float[] target, int rate) {
        double g = gainToMatch(source, target, rate);
        if (Math.abs(g - 1.0) < 1e-9) {
            return false;
        }
        for (int i = 0; i < source.length; i++) {
            source[i] = (float) (source[i] * g);
        }
        return true;
    }

    /** K 加权（高架 + 高通），系数按采样率现算。 */
    static float[] kWeight(float[] pcm, int rate, int frames) {
        float[] out = shelf(pcm, rate, frames);
        return highPass(out, rate, frames);
    }

    /** 第一级：高架（+4 dB @ ~1.68 kHz）。 */
    private static float[] shelf(float[] pcm, int rate, int frames) {
        double f0 = 1681.974450955533;
        double gainDb = 3.999843853973347;
        double q = 0.7071752369554196;
        double k = Math.tan(Math.PI * f0 / rate);
        double vh = Math.pow(10.0, gainDb / 20.0);
        double vb = Math.pow(vh, 0.4996667741545416);
        double a0 = 1.0 + k / q + k * k;
        double b0 = (vh + vb * k / q + k * k) / a0;
        double b1 = 2.0 * (k * k - vh) / a0;
        double b2 = (vh - vb * k / q + k * k) / a0;
        double a1 = 2.0 * (k * k - 1.0) / a0;
        double a2 = (1.0 - k / q + k * k) / a0;
        return biquad(pcm, frames, b0, b1, b2, a1, a2);
    }

    /** 第二级：高通（38 Hz）。 */
    private static float[] highPass(float[] pcm, int rate, int frames) {
        double f0 = 38.13547087602444;
        double q = 0.5003270373238773;
        double k = Math.tan(Math.PI * f0 / rate);
        double a0 = 1.0 + k / q + k * k;
        double b0 = 1.0 / a0;
        double b1 = -2.0 / a0;
        double b2 = 1.0 / a0;
        double a1 = 2.0 * (k * k - 1.0) / a0;
        double a2 = (1.0 - k / q + k * k) / a0;
        return biquad(pcm, frames, b0, b1, b2, a1, a2);
    }

    /** 直二式 biquad（分子已含 a0 归一）。 */
    private static float[] biquad(float[] pcm, int frames, double b0, double b1, double b2,
                                  double a1, double a2) {
        float[] out = new float[frames];
        double x1 = 0.0;
        double x2 = 0.0;
        double y1 = 0.0;
        double y2 = 0.0;
        for (int i = 0; i < frames; i++) {
            double x = pcm[i];
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            out[i] = (float) y;
        }
        return out;
    }
}
