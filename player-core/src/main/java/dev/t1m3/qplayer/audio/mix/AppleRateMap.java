package dev.t1m3.qplayer.audio.mix;

/**
 * <b>Apple 的 `ts_rate` 规则</b>（2026-10-08 ✓，照搬 goal 第 15 轮 ✓）。
 *
 * <p>规则不是猜的 ✗ —— 逐字来自他的参考实现 ✓：
 * <pre>
 * reference-python/scheduling.py · structured_rate_endpoints()
 *   outgoing_length = a1 - a0        // A 的**源时间范围长度**（秒）
 *   incoming_length = b1 - b0
 *   ratio = outgoing_length / incoming_length      // 任一侧 <= 0 ⇒ 1.0
 *   → outgoing: (1.0, ratio)         // A：1 → ratio
 *   → incoming: (1.0 / ratio, 1.0)   // B：1/ratio → 1
 * </pre>
 * 配套约束（同材料 ✓）：曲线必须 linear ✓；范围可以是 placement 的子集 ✓；
 * **没有 `ts_rate` ⇒ 恒等映射** ✓（不是每档都变速 ✓）；
 * 速率**线性变化**时，时间映射不是线性插值 ✗ —— 是它的积分 ✓
 * （`native_stretch_elapsed()` ✓：`(exp(ln r0 + slope·t) − r0) / slope` ✓）。
 *
 * <p>与我们实测的 AM 行为一致 ✓：模板里的 `1→2` 被替换成 `1→ratio` ✓，
 * 而实测 A 的速率是 1.000 → **0.966** ✓ ⇒ ratio ≈ 0.966 ✓ —— 两边对上 ✓。
 */
public final class AppleRateMap {

    private AppleRateMap() {
    }

    /** 两侧速率端点（A：start→end ✓，B：start→end ✓）。 */
    public static final class Endpoints {
        public final float outStart;
        public final float outEnd;
        public final float inStart;
        public final float inEnd;
        public final float ratio;

        Endpoints(float outStart, float outEnd, float inStart, float inEnd, float ratio) {
            this.outStart = outStart;
            this.outEnd = outEnd;
            this.inStart = inStart;
            this.inEnd = inEnd;
            this.ratio = ratio;
        }
    }

    /** 见类注释：除的是**源时间范围长度** ✓（不是 BPM ✗）；任一侧非正 ⇒ ratio = 1 ✓。 */
    public static Endpoints endpoints(double outgoingLen, double incomingLen) {
        double ratio = (outgoingLen > 0.0 && incomingLen > 0.0)
                ? outgoingLen / incomingLen : 1.0;
        return new Endpoints(1f, (float) ratio, (float) (1.0 / ratio), 1f, (float) ratio);
    }

    /**
     * 速率线性变化时，**已走过的源时间**（他的 {@code native_stretch_elapsed} ✓）。
     *
     * @param t 已经过的时间（秒 ✓，同一时间轴上 ✓）
     * @param r0 起始速率 ✓
     * @param r1 结束速率 ✓
     * @param length 这一段的总时长（秒 ✓）
     */
    public static double elapsed(double t, double r0, double r1, double length) {
        if (length <= 0.0) {
            return t * ((r0 + r1) * 0.5);
        }
        double slope = (r1 - r0) / length;
        if (Math.abs(slope) < NEAR_CONSTANT_RATE_SLOPE) {
            return t * ((r0 + r1) * 0.5);
        }
        return (Math.exp(Math.log(r0) + slope * t) - r0) / slope;
    }

    /**
     * 他用来判定"速率几乎不变 ⇒ 走线性平均"的阈值 ✓。
     *
     * <p>⚠️ 他的常数**具体数值我没有读到** ✗（材料里只见名字与用法 ✓）⇒ 这里取一个足够小的值 ✓，
     * 并**如实标注**：它只影响"几乎不变"这一支的数值稳定性 ✓（斜率 <1e-6 时两支的差可忽略 ✓），
     * 不影响规则本身 ✓。
     */
    static final double NEAR_CONSTANT_RATE_SLOPE = 1e-6;
}
