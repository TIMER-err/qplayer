package dev.t1m3.qplayer.audio.mix;

/**
 * <b>三频段 EQ 曲线</b>（第二阶段：规则教师蒸馏出来的 MLP 输出 → 逐帧线性增益）。
 *
 * <p>职责边界（规格锁死）：
 * <ul>
 *   <li>输入是 <b>12 个控制点</b>（三频段各 4 个，p 固定为 0 / 0.33 / 0.66 / 1.0）；</li>
 *   <li>用<b>自然三次样条</b>插值成 T×3 的逐帧曲线（两端二阶导为 0，必须可微）；
 *       Python 侧 {@code spline.py} 是同一套算法，固定测试向量下两边误差 &lt; 1e-4；</li>
 *   <li>分频段 dB 映射：低频 0→−30 dB、中频 0→−18 dB、高频 0→−18 dB，<b>maxDb 一律 0（只切不抬）</b>；</li>
 *   <li>提升侧软限幅（防御性代码，当前设计下永不触发）：{@code g &gt; 1} 时
 *       {@code g = 1 + tanh(g − 1) × 0.5}，衰减侧不动；</li>
 *   <li><b>prepare 阶段就把线性增益算好</b>（那里可以用一次 {@code Math.pow}），
 *       render 阶段只做查表 + 线性插值，<b>不做 Math.pow</b>。</li>
 * </ul>
 *
 * <p>为什么样条要自己写：这一段是 Python 训练与 Java 推理之间唯一"数值上必须一致"的接缝，
 * 用现成库没法保证两边同一个算法（非均匀节点的三对角解法有两个等价写法，舍入不同）。
 */
public final class EqCurve {

    /** 控制点的 p 值（规格写死的四个点，**非等距**：0 / 0.33 / 0.66 / 1.0）。 */
    static final double[] CONTROL_P = {0.0, 0.33, 0.66, 1.0};

    /** 三频段的 dB 下限（0 → 这个值；1 → 0 dB）。低频切得更深（DJ 混音里 90% 是衰减）。 */
    static final double[] MIN_DB = {-30.0, -18.0, -18.0};

    /** 只切不抬。 */
    static final double MAX_DB = 0.0;

    /** 每个频段几个控制点。 */
    static final int PER_BAND = 4;

    /** 频段数。 */
    public static final int BANDS = 3;

    /** 总控制点数。 */
    public static final int CONTROLS = PER_BAND * BANDS;

    private final int frames;

    /** 逐帧线性增益：{@code [band][frame]}（查表用，render 阶段只读它）。 */
    private final float[][] gains = new float[BANDS][];

    private EqCurve(int frames) {
        this.frames = frames;
    }

    /** 帧数。 */
    public int frames() {
        return frames;
    }

    /**
     * 从 12 个控制点算出逐帧线性增益（prepare 阶段调一次）。
     *
     * @param controls 12 个控制点（0..1）：前 4 低频、中 4 中频、后 4 高频
     * @param frames   这一次融合有多少帧
     */
    public static EqCurve of(double[] controls, int frames) {
        EqCurve curve = new EqCurve(Math.max(0, frames));
        if (controls == null || controls.length < CONTROLS || frames <= 0) {
            for (int b = 0; b < BANDS; b++) {
                curve.gains[b] = new float[Math.max(0, frames)];
                java.util.Arrays.fill(curve.gains[b], 1.0f);        // 没有曲线 = 不动它
            }
            return curve;
        }
        for (int b = 0; b < BANDS; b++) {
            double[] c = new double[PER_BAND];
            for (int i = 0; i < PER_BAND; i++) {
                c[i] = clamp(controls[b * PER_BAND + i], 0.0, 1.0);
            }
            double[] m = secondDerivatives(c);
            float[] band = new float[frames];
            for (int f = 0; f < frames; f++) {
                double p = frames == 1 ? 0.0 : f / (double) (frames - 1);
                double v = sampleSpline(c, m, p);
                double db = MIN_DB[b] + clamp(v, 0.0, 1.0) * (MAX_DB - MIN_DB[b]);
                double linear = Math.pow(10.0, db / 20.0);           // 只在 prepare 里出现一次
                band[f] = (float) softLimit(linear);
            }
            curve.gains[b] = band;
        }
        return curve;
    }

    /**
     * <b>render 阶段</b>：按当前进度查表插值（只用加乘，不做 Math.pow）。
     *
     * @param progress 0..1
     * @param out      长度 ≥ 3 的输出（低/中/高）
     */
    public void gainsAt(double progress, float[] out) {
        double p = clamp(progress, 0.0, 1.0);
        double x = p * Math.max(0, frames - 1);
        int i = (int) Math.floor(x);
        int j = Math.min(frames - 1, i + 1);
        float t = (float) (x - i);
        for (int b = 0; b < BANDS; b++) {
            float[] g = gains[b];
            if (g.length == 0) {
                out[b] = 1.0f;
            } else {
                out[b] = g[i] + (g[j] - g[i]) * t;
            }
        }
    }

    /** 某频段某帧的线性增益（判据用）。 */
    float gain(int band, int frame) {
        float[] g = gains[band];
        if (g.length == 0) {
            return 1.0f;
        }
        return g[Math.max(0, Math.min(g.length - 1, frame))];
    }

    // ── 下面两个方法是 Python spline.py 的逐行对应（同一套 Thomas 解法）──────────

    /** 自然三次样条的 M（二阶导）。**节点非均匀**（0/0.33/0.66/1.0，最后一段是 0.34）。 */
    static double[] secondDerivatives(double[] y) {
        int n = y.length;
        double[] m = new double[n];
        if (n < 3) {
            return m;
        }
        double[] h = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            h[i] = CONTROL_P[i + 1] - CONTROL_P[i];
        }
        double[] a = new double[n];
        double[] b = new double[n];
        double[] c = new double[n];
        double[] d = new double[n];
        for (int i = 1; i < n - 1; i++) {
            a[i] = h[i - 1];
            b[i] = 2.0 * (h[i - 1] + h[i]);
            c[i] = h[i];
            d[i] = 6.0 * ((y[i + 1] - y[i]) / h[i] - (y[i] - y[i - 1]) / h[i - 1]);
        }
        double[] cp = new double[n];
        double[] dp = new double[n];
        cp[1] = c[1] / b[1];
        dp[1] = d[1] / b[1];
        for (int i = 2; i < n - 1; i++) {
            double den = b[i] - a[i] * cp[i - 1];
            cp[i] = c[i] / den;
            dp[i] = (d[i] - a[i] * dp[i - 1]) / den;
        }
        for (int i = n - 2; i >= 1; i--) {
            m[i] = dp[i] - cp[i] * m[i + 1];
        }
        return m;
    }

    /** 在 p 处取样条值（三次 Hermite 形式，控制点处精确等于控制值）。 */
    static double sampleSpline(double[] y, double[] m, double p) {
        double x = clamp(p, 0.0, 1.0);
        int i = 0;
        for (int j = 0; j < y.length - 1; j++) {
            if (x >= CONTROL_P[j]) {
                i = j;
            }
        }
        double x0 = CONTROL_P[i];
        double x1 = CONTROL_P[i + 1];
        double h = x1 - x0;
        double t = h <= 0.0 ? 0.0 : (x - x0) / h;
        double t2 = t * t;
        double t3 = t2 * t;
        double h00 = 2 * t3 - 3 * t2 + 1;
        double h10 = t3 - 2 * t2 + t;
        double h01 = -2 * t3 + 3 * t2;
        double h11 = t3 - t2;
        return h00 * y[i] + h10 * h * m[i] + h01 * y[i + 1] + h11 * h * m[i + 1];
    }

    /** 提升侧软限幅：**只在 g &gt; 1 时**动，衰减侧原样。 */
    static double softLimit(double g) {
        return g > 1.0 ? 1.0 + Math.tanh(g - 1.0) * 0.5 : g;
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private EqCurve() {
        this(0);
    }
}
