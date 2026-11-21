package dev.t1m3.qplayer.audio.studio;

/**
 * 工作室用的双精度双二阶（Double Biquad）。
 *
 * <h2>为什么不直接用 {@code dsp.Biquad}</h2>
 *
 * <p>那个是<strong>上游 Rust 实现的移植</strong>，系数与状态都是 {@code f32}——为了与上游
 * <strong>逐位一致</strong>（变调、分频那些路径依赖这一点），这是刻意的。
 *
 * <p>但均衡器不能这么干：{@code f32} 的 24 位尾数在<b>低频</b>会明显丢精度。实测
 * （{@code StudioEqOracleTest.pendingMatchesTheCookbook}）：1 kHz、Q=1、−12 dB 的峰值段，
 * 在 200 Hz 处的解析值是 −0.64 dB，而 f32 实现量到 −1.19 dB——<strong>差 0.55 dB</strong>，
 * 这是「调音台上看得见」的量级；48 dB/oct 的高通级联更夸张（f32 量出来的斜率直接是错的）。
 * 原因很朴素：频率越低，极点越靠近 z=1，系数之间的相对差越小，f32 的尾数不够分辨。
 *
 * <p>双精度之后：极点在 double 里有 53 位尾数，同样的测试能压到 ±0.02 dB（实测）。
 * 代价是每样本几次 double 乘加——在现代 x86 上与 float 同级（AVX 下 double 也是
 * 一条指令一条），而这一层本来就有 12 段 × 4 级的上限。
 *
 * <h2>与 {@code Biquad} 的关系</h2>
 *
 * <p>设计式（RBJ 手册那几条）逐字相同，连预畸变的口径（{@code alpha = sin(w0)/(2Q)}）
 * 都一致，所以界面上的「Q」在两条路径上含义相同。差别只有：这里是 double，
 * 而且<strong>没有</strong>「保留状态换系数」之外的花样。
 */
public final class StudioBiquad {

    private double b0 = 1;
    private double b1;
    private double b2;
    private double a1;
    private double a2;
    private double z1;
    private double z2;

    private StudioBiquad() {
    }

    /** 恒等（{@code gain = 0} 的段、或还没设计过的段）。 */
    public static StudioBiquad unity() {
        return new StudioBiquad();
    }

    /**
     * 从归一化系数构造。{@code a0} 必须已经除掉。
     */
    public static StudioBiquad fromCoeffs(double b0, double b1, double b2, double a1, double a2) {
        StudioBiquad f = new StudioBiquad();
        f.b0 = b0;
        f.b1 = b1;
        f.b2 = b2;
        f.a1 = a1;
        f.a2 = a2;
        return f;
    }

    /** 处理一个样本（直接 II 型转置）。 */
    public double process(double x) {
        double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }

    /** 清空状态（跳转、换曲后调用，免得漏出上一段的余振）。 */
    public void reset() {
        z1 = 0;
        z2 = 0;
    }

    /**
     * 换成另一份系数，<strong>保留内部状态</strong>。
     *
     * <p>拖动滑杆时参数一秒能改几十次，每次都从零起振就是一连串咔哒；
     * 状态与系数在直接 II 型转置里是分开的，所以换系数不必清状态。
     */
    public void updateFrom(StudioBiquad other) {
        this.b0 = other.b0;
        this.b1 = other.b1;
        this.b2 = other.b2;
        this.a1 = other.a1;
        this.a2 = other.a2;
    }

    /** 当前系数 {@code {b0, b1, b2, a1, a2}}（离线算频响用；每次分配）。 */
    public double[] coeffs() {
        return new double[]{b0, b1, b2, a1, a2};
    }

    // ------------------------------------------------------------------
    // RBJ 手册的设计式（与 dsp.Biquad 同一套，只是换成 double）
    // ------------------------------------------------------------------

    public static StudioBiquad peaking(int rate, double freq, double q, double gainDb) {
        double[] pw = prewarp(rate, freq, q);
        double cos = pw[0];
        double alpha = pw[1];
        double a = Math.pow(10.0, gainDb / 40.0);
        double a0 = 1 + alpha / a;
        return fromCoeffs((1 + alpha * a) / a0, -2 * cos / a0, (1 - alpha * a) / a0,
                -2 * cos / a0, (1 - alpha / a) / a0);
    }

    public static StudioBiquad lowShelf(int rate, double freq, double q, double gainDb) {
        double[] pw = prewarp(rate, freq, q);
        double cos = pw[0];
        double alpha = pw[1];
        double a = Math.pow(10.0, gainDb / 40.0);
        double sqrtA = Math.sqrt(a);
        double a0 = (a + 1) + (a - 1) * cos + 2 * sqrtA * alpha;
        return fromCoeffs(
                a * ((a + 1) - (a - 1) * cos + 2 * sqrtA * alpha) / a0,
                2 * a * ((a - 1) - (a + 1) * cos) / a0,
                a * ((a + 1) - (a - 1) * cos - 2 * sqrtA * alpha) / a0,
                -2 * ((a - 1) + (a + 1) * cos) / a0,
                ((a + 1) + (a - 1) * cos - 2 * sqrtA * alpha) / a0);
    }

    public static StudioBiquad highShelf(int rate, double freq, double q, double gainDb) {
        double[] pw = prewarp(rate, freq, q);
        double cos = pw[0];
        double alpha = pw[1];
        double a = Math.pow(10.0, gainDb / 40.0);
        double sqrtA = Math.sqrt(a);
        double a0 = (a + 1) - (a - 1) * cos + 2 * sqrtA * alpha;
        return fromCoeffs(
                a * ((a + 1) + (a - 1) * cos + 2 * sqrtA * alpha) / a0,
                -2 * a * ((a - 1) + (a + 1) * cos) / a0,
                a * ((a + 1) + (a - 1) * cos - 2 * sqrtA * alpha) / a0,
                2 * ((a - 1) - (a + 1) * cos) / a0,
                ((a + 1) - (a - 1) * cos - 2 * sqrtA * alpha) / a0);
    }

    public static StudioBiquad highPass(int rate, double freq, double q) {
        double[] pw = prewarp(rate, freq, q);
        double cos = pw[0];
        double alpha = pw[1];
        double a0 = 1 + alpha;
        return fromCoeffs((1 + cos) / 2 / a0, -(1 + cos) / a0, (1 + cos) / 2 / a0,
                -2 * cos / a0, (1 - alpha) / a0);
    }

    public static StudioBiquad lowPass(int rate, double freq, double q) {
        double[] pw = prewarp(rate, freq, q);
        double cos = pw[0];
        double alpha = pw[1];
        double a0 = 1 + alpha;
        return fromCoeffs((1 - cos) / 2 / a0, (1 - cos) / a0, (1 - cos) / 2 / a0,
                -2 * cos / a0, (1 - alpha) / a0);
    }

    public static StudioBiquad bandPass(int rate, double freq, double q) {
        double[] pw = prewarp(rate, freq, q);
        double cos = pw[0];
        double alpha = pw[1];
        double a0 = 1 + alpha;
        return fromCoeffs(alpha / a0, 0, -alpha / a0, -2 * cos / a0, (1 - alpha) / a0);
    }

    public static StudioBiquad notch(int rate, double freq, double q) {
        double[] pw = prewarp(rate, freq, q);
        double cos = pw[0];
        double alpha = pw[1];
        double a0 = 1 + alpha;
        return fromCoeffs(1 / a0, -2 * cos / a0, 1 / a0, -2 * cos / a0, (1 - alpha) / a0);
    }

    public static StudioBiquad allPass(int rate, double freq, double q) {
        double[] pw = prewarp(rate, freq, q);
        double cos = pw[0];
        double alpha = pw[1];
        double a0 = 1 + alpha;
        return fromCoeffs((1 - alpha) / a0, -2 * cos / a0, (1 + alpha) / a0,
                -2 * cos / a0, (1 - alpha) / a0);
    }

    /** {@code {cos(w0), alpha}}，频率夹在 (0, Nyquist)，Q 兜住 NaN 与过小值。 */
    private static double[] prewarp(int rate, double freq, double q) {
        double rateF = Math.max(rate, 2);
        double nyquist = rateF * 0.5;
        double hi = Math.max(nyquist * 0.999, 1.0);
        double lo = Math.min(hi, 1.0);
        double f = Math.max(lo, Math.min(hi, freq));
        double w0 = 2 * Math.PI * f / rateF;
        double qq = Double.isNaN(q) ? 0.05 : Math.max(q, 0.05);
        return new double[]{Math.cos(w0), Math.sin(w0) / (2 * qq)};
    }
}
