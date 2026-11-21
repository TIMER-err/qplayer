package dev.t1m3.qplayer.audio.dsp;

import java.nio.FloatBuffer;

/**
 * 一阶一极点低通。
 *
 * <p>比双二阶更平滑、更便宜，用于头影近似这类「只要一个大致的滚降」的场合。
 *
 * <p>上游 {@code sonalis-dsp/src/spatial.rs}（双耳渲染的头影滤波）复用了这个类型：
 * 每个（声源，耳）一份 {@code OnePole}，按方位角算出的截止频率构造，渲染时
 * {@link #process} 逐步吃远端耳的信号，{@link #reset} 在重建渲染器时清零。
 * 所以这个类的公开 API（{@link #lowPass}、{@link #unity}、{@link #reset}、
 * {@link #process}、{@link #processBuf}）是给那个模块用的，不要删。
 *
 * <p>对应 Sonalis 的 {@code sonalis_dsp::biquad::OnePole}。与上游的差异：
 *
 * <ul>
 *   <li>上游 {@code &mut self} 方法在这里是可变方法；实例不可跨线程共享（Java 表达不了独占借用）</li>
 *   <li>上游派生 {@code PartialEq}，这里<b>不</b>移植成 {@code equals}：f32 的 {@code ==} 对 NaN
 *       为 false，会违反 {@code Object.equals} 的自反契约（理由与 {@link Biquad} 一样）</li>
 *   <li>上游 {@code Copy}/{@code Clone} → {@link #copy()}（连滤波器状态一起快照）</li>
 * </ul>
 *
 * <p>运算一律 {@code float} 镜像 {@code f32}：{@code Math.exp} 只接受 double，
 * 结果立即 {@code (float)} 转回来（见 {@link Biquad} 类注释里同一条约定）。
 */
public final class OnePole {

    /**
     * 上游的 {@code core::f32::consts::PI}（0x1.921FB6p+1）。
     *
     * <p>必须先转成 float，否则 {@code Math.PI} 的 double 精度会把截止频率算歪。
     */
    private static final float PI = (float) Math.PI;

    /** 一极点系数 {@code a}：{@code state += a * (x - state)}。 */
    private float a;
    /** 滤波器状态（上一次输出）。 */
    private float state;

    /**
     * 只允许通过工厂构造：Java 的隐式默认是 {@code a = 0}（永远输出 0，即静音），
     * 而直通应当是 {@link #unity()}。
     */
    private OnePole(float a) {
        this.a = a;
        this.state = 0f;
    }

    /**
     * 以截止频率构造。
     *
     * <p>与 {@link Biquad} 的预畸变同样的坑：{@code rate} 极小时奈奎斯特会低于 1 Hz，
     * 而 Rust 的 {@code clamp} 在 {@code min > max} 时会 panic，所以上下界要一起算
     * （此处把那个 panic 映射成 {@link IllegalArgumentException}，但上界由下界推出来，
     * 不会触发）。
     */
    public static OnePole lowPass(int rate, float freq) {
        float rateF = (float) Math.max(rate, 2);
        float hi = Math.max(rateF * 0.499f, 1f);
        float f = clamp(freq, Math.min(hi, 1f), hi);
        float a = 1f - (float) Math.exp((-2f * PI * f) / rateF);
        return new OnePole(clamp(a, 0f, 1f));
    }

    /** 直通。 */
    public static OnePole unity() {
        return new OnePole(1f);
    }

    /** 复制系数与状态，对应上游的 {@code #[derive(Clone, Copy)]}。 */
    public OnePole copy() {
        OnePole p = new OnePole(a);
        p.state = state;
        return p;
    }

    /** 清空状态。跳转/重建渲染器后要调用，否则旧余振会漏进新位置。 */
    public void reset() {
        this.state = 0f;
    }

    /** 处理单个样本。 */
    public float process(float x) {
        state += a * (x - state);
        return state;
    }

    /**
     * 就地处理整段（对应上游的 {@code &mut [f32]}）。
     *
     * <p>长度按 {@link FloatBuffer#limit()} 取，绝对下标读写、不动 {@code position}。
     */
    public void processBuf(FloatBuffer buf) {
        int n = buf.limit();
        for (int i = 0; i < n; i++) {
            buf.put(i, process(buf.get(i)));
        }
    }

    /**
     * Rust {@code f32::clamp} 的镜像，理由见 {@link Biquad} 里同名方法：
     * 上游 {@code clamp} 的 {@code assert!(min <= max)} 在这里是
     * {@link IllegalArgumentException}；NaN 输入两边都原样返回 NaN。
     */
    private static float clamp(float v, float min, float max) {
        if (min > max) {
            throw new IllegalArgumentException("clamp 的上下界反了：min=" + min + " > max=" + max);
        }
        return Math.max(min, Math.min(max, v));
    }

    /** 对应上游 {@code #[derive(Debug)]}。 */
    @Override
    public String toString() {
        return "OnePole{a=" + a + ", state=" + state + '}';
    }
}
