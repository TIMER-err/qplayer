package dev.t1m3.qplayer.audio.dsp;

import java.nio.FloatBuffer;

/**
 * 双二阶（biquad）滤波器。
 *
 * <p>三种用途（对应上游 {@code sonalis-dsp/src/biquad.rs} 的模块说明）：
 *
 * <ol>
 *   <li><b>分频</b> ——「低音交接」（bass swap，automix 的一种接法）要把信号切成低频与
 *       高频两条，各自用不同时序交接。这里提供它需要的低通，两路的互补性见
 *       {@link SplitLowHigh}</li>
 *   <li><b>扫频</b> —— 表现模式（build-up）用高通/低通在过渡窗口内连续移动截止频率，
 *       靠 {@link #updateLowPass} / {@link #updateHighPass} 换系数而<b>不清状态</b></li>
 *   <li><b>头影</b> —— 空间音频里远端耳的低通近似（那边用的是一极点 {@link OnePole}，
 *       双二阶留给需要更陡滚降的场合）</li>
 * </ol>
 *
 * <p><b>系数来源</b>：采用 RBJ Audio EQ Cookbook 的标准双二阶公式（公开的解析设计法），
 * 直接按截止频率与 Q 值算出系数，不做数值优化。上游把设计式写成关联函数
 * （{@code Biquad::low_pass} 等），不是模块级自由函数，所以这里就是 {@code public static}
 * 方法，不做位置调整。
 *
 * <p><b>实现约定</b>：用<b>直接 II 型转置</b>结构。它只有一个延迟链、状态是两个数，
 * 数值特性比直接 I 型好，且改动系数时不需要重置状态——这一点对扫频很关键：
 * 每块重算系数的瞬间不该产生咔哒声。
 *
 * <p>对应 Sonalis 的 {@code sonalis_dsp::biquad::Biquad}。与上游的差异（都写在这里，
 * 免得将来 diff 时误判成漏移植）：
 *
 * <ul>
 *   <li>上游 {@code fn process(&mut self, ...)} 在这里就是可变方法。Java 的类型系统表达不了
 *       「独占借用」：<b>一个实例只能被一个线程使用</b>，多声道并行就各自持有实例</li>
 *   <li>上游派生 {@code PartialEq}，这里<b>不</b>移植成 {@code equals}：f32 的 {@code ==}
 *       对 NaN 为 false，而 {@code Object.equals} 的契约要求自反（{@code x.equals(x)} 必须为
 *       true）；滤波器一旦进入 NaN 状态（{@link #prewarp} 允许 NaN 频率原样穿过 clamp）
 *       契约就废了。要比较状态请用 {@link #toString()} 或逐字段比对</li>
 *   <li>上游 {@code Copy}/{@code Clone} → {@link #copy()}（连延迟链一起快照）</li>
 *   <li>上游 {@code impl Default for Biquad} 返回的是直通。Java 没有 Default trait，这里也
 *       不提供无参构造：Java 的隐式默认是<b>全零系数</b>（= 静音），正是最不该出现的默认值，
 *       拿直通滤波器必须显式写 {@link #unity()}</li>
 *   <li>{@code &mut [f32]} → {@link FloatBuffer}（移植约定的类型映射）；
 *       {@link #processBuf} 处理整个视图 {@code [0, limit)}</li>
 * </ul>
 *
 * <p>所有运算都用 {@code float} 镜像上游的 {@code f32}。{@code Math.sin}/{@code Math.cos}/
 * {@code Math.pow}/{@code Math.sqrt}/{@code Math.exp} 只接受 double，因此结果<b>立即</b>
 * {@code (float)} 转回来——若让 double 走完全程再转，系数就会与上游不一致，
 * 「两路相加等于原信号」这类被测试锁住的性质会跟着破。这些超越函数与 Rust 的 libm
 * 只差 1 ulp 级（两个方向都是正确舍入范围内的差异），测试容差见 {@code BiquadTest}。
 */
public final class Biquad {

    /**
     * 上游用的 {@code core::f32::consts::PI}，即 f32 能表示的最近 π（0x1.921FB6p+1）。
     *
     * <p>必须在这里先转成 float：直接用 {@code Math.PI}（double）参与运算会把整条系数
     * 计算拖进 double，与上游的 f32 逐位不一致。
     */
    private static final float PI = (float) Math.PI;

    private float b0;
    private float b1;
    private float b2;
    private float a1;
    private float a2;
    /** 直接 II 型转置的延迟链。 */
    private float z1;
    private float z2;

    /** 只允许通过设计式工厂构造，避免出现「系数全零」这种静音滤波器。 */
    private Biquad(float b0, float b1, float b2, float a1, float a2) {
        this.b0 = b0;
        this.b1 = b1;
        this.b2 = b2;
        this.a1 = a1;
        this.a2 = a2;
        this.z1 = 0f;
        this.z2 = 0f;
    }

    /**
     * 直通（系数为恒等）。同时也是上游 {@code impl Default} 的行为。
     */
    public static Biquad unity() {
        return new Biquad(1f, 0f, 0f, 0f, 0f);
    }

    /**
     * 从归一化系数构造（{@code a0} 已除掉）。对应上游的 {@code Biquad::from_coeffs}
     * （上游是 {@code const fn}，Java 没有 const 构造，工厂方法即可）。
     *
     * <p>状态（{@code z1}/{@code z2}）从零开始：换了系数就要从头起振，
     * 想让已有的运行状态接上新系数请用 {@link #updateLowPass} / {@link #updateHighPass}。
     */
    public static Biquad fromCoeffs(float b0, float b1, float b2, float a1, float a2) {
        return new Biquad(b0, b1, b2, a1, a2);
    }

    /**
     * 复制系数<strong>与</strong>延迟链，对应上游的 {@code #[derive(Clone, Copy)]}。
     *
     * <p>不是「同参数的另一个滤波器」：上游 {@code vec![SplitLowHigh::new(..); n]} 那种写法的
     * 语义是把同一个状态快照铺成 n 份，需要各自从零起振时应该重新调用设计式。
     */
    public Biquad copy() {
        Biquad c = new Biquad(b0, b1, b2, a1, a2);
        c.z1 = z1;
        c.z2 = z2;
        return c;
    }

    /**
     * 清空内部状态。
     *
     * <p><strong>跳转（seek）后必须调用</strong>：否则跳转前那一段的滤波器余振会漏进新位置，
     * 听感上是「跳过去之后先响了一下旧内容」。
     */
    public void reset() {
        this.z1 = 0f;
        this.z2 = 0f;
    }

    /**
     * 低通。{@code q} 取 {@code FRAC_1_SQRT_2}（≈0.7071）即最平坦（Butterworth）响应。
     */
    /**
     * <b>原地换系数（不动滤波状态）</b> —— 扫频/自动化必须走这一条 ✓。
     *
     * <p>为什么不能每块新建一个 {@link #lowPass}：渲染路径**零分配** ✓（那是硬约束，
     * 见 {@code AiMix} 类注释），而且新建会把 {@code z1/z2} 清零 ⇒ 每块开头一个瞬态 ✗
     * （听感就是"每 20 毫秒咔一下" ✗）。这里只换系数、保留状态 ✓。
     */
    public void setLowPass(int rate, float freq, float q) {
        float[] pw = prewarp(rate, freq, q);
        float cosW0 = pw[0];
        float alpha = pw[1];
        float a0 = 1f + alpha;
        float b = (1f - cosW0) / 2f;
        setCoeffs(b / a0, (1f - cosW0) / a0, b / a0, (-2f * cosW0) / a0, (1f - alpha) / a0);
    }

    /** 原地换系数（见 {@link #setLowPass}）。 */
    /** 原地换系数成高通（扫频/自动化用 ✓，保留滤波状态 ✓）。 */
    public void setHighPass(int rate, float freq, float q) {
        float[] pw = prewarp(rate, freq, q);
        float cosW0 = pw[0];
        float alpha = pw[1];
        float a0 = 1f + alpha;
        float b = (1f + cosW0) / 2f;
        setCoeffs(b / a0, -(1f + cosW0) / a0, b / a0, (-2f * cosW0) / a0, (1f - alpha) / a0);
    }

    public void setCoeffs(float nb0, float nb1, float nb2, float na1, float na2) {
        this.b0 = nb0;
        this.b1 = nb1;
        this.b2 = nb2;
        this.a1 = na1;
        this.a2 = na2;
    }

    /** 当前截止频率对应的低通系数（诊断/判据用：不新建对象 ✓）。 */
    public void copyLowPassInto(int rate, float freq, float q, float[] dst) {
        float[] pw = prewarp(rate, freq, q);
        float cosW0 = pw[0];
        float alpha = pw[1];
        float a0 = 1f + alpha;
        float b = (1f - cosW0) / 2f;
        dst[0] = b / a0;
        dst[1] = (1f - cosW0) / a0;
        dst[2] = b / a0;
        dst[3] = (-2f * cosW0) / a0;
        dst[4] = (1f - alpha) / a0;
    }

    public static Biquad lowPass(int rate, float freq, float q) {
        float[] pw = prewarp(rate, freq, q);
        float cosW0 = pw[0];
        float alpha = pw[1];
        float a0 = 1f + alpha;
        float b = (1f - cosW0) / 2f;
        return fromCoeffs(
                b / a0,
                (1f - cosW0) / a0,
                b / a0,
                (-2f * cosW0) / a0,
                (1f - alpha) / a0);
    }

    /** 高通。 */
    public static Biquad highPass(int rate, float freq, float q) {
        float[] pw = prewarp(rate, freq, q);
        float cosW0 = pw[0];
        float alpha = pw[1];
        float a0 = 1f + alpha;
        float b = (1f + cosW0) / 2f;
        return fromCoeffs(
                b / a0,
                (-(1f + cosW0)) / a0,
                b / a0,
                (-2f * cosW0) / a0,
                (1f - alpha) / a0);
    }

    /** 带通（峰值增益 0 dB）。 */
    public static Biquad bandPass(int rate, float freq, float q) {
        float[] pw = prewarp(rate, freq, q);
        float cosW0 = pw[0];
        float alpha = pw[1];
        float a0 = 1f + alpha;
        return fromCoeffs(
                alpha / a0,
                0f,
                -alpha / a0,
                (-2f * cosW0) / a0,
                (1f - alpha) / a0);
    }

    /** 低架（低频段的整体抬升/衰减）。{@code gainDb} 为 0 时即恒等。 */
    public static Biquad lowShelf(int rate, float freq, float q, float gainDb) {
        float[] pw = prewarp(rate, freq, q);
        float cosW0 = pw[0];
        float alpha = pw[1];
        // 上游是 f32 的 `10.0f32.powf(gain_db / 40.0)`：这里在 double 里算 pow 再立即转回
        // float。它和 libm 的 powf 最多差 1 ulp（实测 0/3/6/-12 dB 这几组与上游逐位相同，
        // 见 BiquadTest 类注释里的交叉验证）；gain_db = 0 时两边都精确得到 1，
        // 这正是 low_shelf_with_zero_gain_is_transparent 依赖的那一点。
        float a = (float) Math.pow(10.0, gainDb / 40f);
        float sqrtA = (float) Math.sqrt(a);
        float a0 = (a + 1f) + (a - 1f) * cosW0 + 2f * sqrtA * alpha;
        return fromCoeffs(
                (a * ((a + 1f) - (a - 1f) * cosW0 + 2f * sqrtA * alpha)) / a0,
                (2f * a * ((a - 1f) - (a + 1f) * cosW0)) / a0,
                (a * ((a + 1f) - (a - 1f) * cosW0 - 2f * sqrtA * alpha)) / a0,
                (-2f * ((a - 1f) + (a + 1f) * cosW0)) / a0,
                ((a + 1f) + (a - 1f) * cosW0 - 2f * sqrtA * alpha) / a0);
    }

    /**
     * 峰值（钟形）均衡：工作室 EQ 的主力段。
     *
     * <p>RBJ 手册的那一组式子，与 {@link #lowShelf} 同一套预畸变口径
     * （{@code alpha = sin(w0)/(2Q)}），所以界面上「Q 越大越窄」与低架/高架一致。
     * {@code gainDb = 0} 时系数<b>精确</b>退化为恒等（{@code A = 1} 让分子分母相同）。
     */
    public static Biquad peaking(int rate, float freq, float q, float gainDb) {
        float[] pw = prewarp(rate, freq, q);
        float cosW0 = pw[0];
        float alpha = pw[1];
        float a = (float) Math.pow(10.0, gainDb / 40f);
        float a0 = 1f + alpha / a;
        return fromCoeffs(
                (1f + alpha * a) / a0,
                (-2f * cosW0) / a0,
                (1f - alpha * a) / a0,
                (-2f * cosW0) / a0,
                (1f - alpha / a) / a0);
    }

    /** 高架（高频段的整体抬升/衰减）。{@link #lowShelf} 的镜像写法。 */
    public static Biquad highShelf(int rate, float freq, float q, float gainDb) {
        float[] pw = prewarp(rate, freq, q);
        float cosW0 = pw[0];
        float alpha = pw[1];
        float a = (float) Math.pow(10.0, gainDb / 40f);
        float sqrtA = (float) Math.sqrt(a);
        float a0 = (a + 1f) - (a - 1f) * cosW0 + 2f * sqrtA * alpha;
        return fromCoeffs(
                (a * ((a + 1f) + (a - 1f) * cosW0 + 2f * sqrtA * alpha)) / a0,
                (-2f * a * ((a - 1f) + (a + 1f) * cosW0)) / a0,
                (a * ((a + 1f) + (a - 1f) * cosW0 - 2f * sqrtA * alpha)) / a0,
                (2f * ((a - 1f) - (a + 1f) * cosW0)) / a0,
                ((a + 1f) - (a - 1f) * cosW0 - 2f * sqrtA * alpha) / a0);
    }

    /** 陷波（把很窄的一段压掉，例如去交流声/啸叫）。 */
    public static Biquad notch(int rate, float freq, float q) {
        float[] pw = prewarp(rate, freq, q);
        float cosW0 = pw[0];
        float alpha = pw[1];
        float a0 = 1f + alpha;
        return fromCoeffs(
                1f / a0,
                (-2f * cosW0) / a0,
                1f / a0,
                (-2f * cosW0) / a0,
                (1f - alpha) / a0);
    }

    /** 全通（只改相位，不改幅度）：做相位对齐 / 分频器修相位时用。 */
    public static Biquad allPass(int rate, float freq, float q) {
        float[] pw = prewarp(rate, freq, q);
        float cosW0 = pw[0];
        float alpha = pw[1];
        float a0 = 1f + alpha;
        return fromCoeffs(
                (1f - alpha) / a0,
                (-2f * cosW0) / a0,
                (1f + alpha) / a0,
                (-2f * cosW0) / a0,
                (1f - alpha) / a0);
    }

    /**
     * 当前系数，顺序 {@code {b0, b1, b2, a1, a2}}（{@code a0} 已归一）。
     *
     * <p>给「算频响曲线」这类离线用途（界面画曲线、测试与解析式对比）。
     * <strong>别放进逐样本路径</strong>：它每次分配一个数组。
     */
    public float[] coeffs() {
        return new float[]{b0, b1, b2, a1, a2};
    }

    /** 重算峰值系数，保留内部状态。见 {@link #updateLowPass}。 */
    public void updatePeaking(int rate, float freq, float q, float gainDb) {
        copyCoeffs(peaking(rate, freq, q, gainDb));
    }

    /** 重算高架系数，保留内部状态。 */
    public void updateHighShelf(int rate, float freq, float q, float gainDb) {
        copyCoeffs(highShelf(rate, freq, q, gainDb));
    }

    /** 重算陷波系数，保留内部状态。 */
    public void updateNotch(int rate, float freq, float q) {
        copyCoeffs(notch(rate, freq, q));
    }

    /** 重算低架系数，保留内部状态。 */
    public void updateLowShelf(int rate, float freq, float q, float gainDb) {
        copyCoeffs(lowShelf(rate, freq, q, gainDb));
    }

    /** 重算全通系数，保留内部状态。 */
    public void updateAllPass(int rate, float freq, float q) {
        copyCoeffs(allPass(rate, freq, q));
    }

    /**
     * 换成另一个滤波器的系数，<strong>保留自己的内部状态</strong>。
     *
     * <p>工作室 EQ 拖动滑杆时用的就是这一条：参数一改就重新设计系数，但状态连续接上，
     * 所以听不到咔哒。与 {@link #copy()} 的区别：那个连状态一起复制（等价于「同一时刻的
     * 另一个拷贝」），这里只换系数。
     */
    public void updateFrom(Biquad other) {
        copyCoeffs(other);
    }

    private void copyCoeffs(Biquad c) {
        this.b0 = c.b0;
        this.b1 = c.b1;
        this.b2 = c.b2;
        this.a1 = c.a1;
        this.a2 = c.a2;
    }

    /**
     * 重算低通系数，<strong>保留内部状态</strong>。
     *
     * <p>扫频要在运行中不断改截止频率，而改的瞬间不该产生咔哒声。直接 II 型转置正好满足
     * 这一点：状态（{@code z1}/{@code z2}）与系数分离，换系数既不用清状态、也不会跳变。
     */
    public void updateLowPass(int rate, float freq, float q) {
        Biquad c = lowPass(rate, freq, q);
        this.b0 = c.b0;
        this.b1 = c.b1;
        this.b2 = c.b2;
        this.a1 = c.a1;
        this.a2 = c.a2;
    }

    /** 重算高通系数，保留内部状态。见 {@link #updateLowPass}。 */
    public void updateHighPass(int rate, float freq, float q) {
        Biquad c = highPass(rate, freq, q);
        this.b0 = c.b0;
        this.b1 = c.b1;
        this.b2 = c.b2;
        this.a1 = c.a1;
        this.a2 = c.a2;
    }

    /**
     * 预畸变：把频率、Q 折算成 {@code cos(w0)} 与 {@code alpha}。
     *
     * <p>对应上游返回元组 {@code (f32, f32)} 的 {@code Biquad::prewarp}；Java 没有元组，
     * 这里返回 {@code {cosW0, alpha}}。每次设计系数分配一个长度 2 的数组——这是「每块一次」
     * 的路径（换系数），不是逐样本路径，可以接受；别把它放进逐样本内循环。
     *
     * <p>频率会被夹在 {@code (0, rate/2)} 开区间内。夹紧而不是断言，是因为扫频会在运行中
     * 掠过边界，<strong>那里不该抛异常</strong>。
     *
     * <p>注意 {@code rate} 极小（0 或 1）时奈奎斯特会低于 1 Hz。Rust 的 {@code f32::clamp}
     * 在 {@code min > max} 时会 panic，所以下界也要跟着抬——这里最容易踩的是
     * 「以为 clamp 是安全的」。本移植把那个 panic 映射成 {@link IllegalArgumentException}
     * （见 {@link #clamp}）；下面 {@code lo} 由 {@code hi} 推出来，恒有 {@code lo <= hi}，
     * 所以 {@code rate = 0} 也绝不会触发它。
     */
    private static float[] prewarp(int rate, float freq, float q) {
        float rateF = (float) Math.max(rate, 2);
        float nyquist = rateF * 0.5f;
        float hi = Math.max(nyquist * 0.999f, 1f);
        float lo = Math.min(hi, 1f);
        float f = clamp(freq, lo, hi);
        float w0 = 2f * PI * f / rateF;
        // 上游是 `q.max(0.05)`：Rust 的 f32::max 在一边是 NaN 时返回另一边，所以 q 为 NaN
        // 会退化成 0.05；而 Math.max 会返回 NaN，把整条系数链污染成 NaN。这里保留上游语义。
        float qq = Float.isNaN(q) ? 0.05f : Math.max(q, 0.05f);
        return new float[] {(float) Math.cos(w0), (float) Math.sin(w0) / (2f * qq)};
    }

    /**
     * Rust {@code f32::clamp} 的镜像。
     *
     * <p>上游的 {@code clamp} 带 {@code assert!(min <= max)}，按移植约定把前置条件映射成
     * {@link IllegalArgumentException}（而不是让结果静默错掉）。
     *
     * <p>NaN 的行为两边一致：Rust 的两个比较都不成立、原样返回 NaN；这里的
     * {@code Math.max(min, Math.min(max, v))} 同样返回 NaN。调用方要自己决定 NaN 输入该怎么办。
     */
    private static float clamp(float v, float min, float max) {
        if (min > max) {
            throw new IllegalArgumentException("clamp 的上下界反了：min=" + min + " > max=" + max);
        }
        return Math.max(min, Math.min(max, v));
    }

    /**
     * 处理单个样本。
     *
     * <p>直接 II 型转置（与上游逐行对应，乘加顺序都不能动：浮点加法不满足结合律，
     * 换个顺序结果就不再逐位相同）。
     */
    public float process(float x) {
        float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }

    /**
     * 就地处理整段（对应上游的 {@code &mut [f32]}）。
     *
     * <p>长度按 {@link FloatBuffer#limit()} 取，从头到尾整个视图都是 Rust 那边的切片；
     * 用绝对下标读写，不动 {@code position}，所以调用方给进来的视图不会被消费掉。
     */
    public void processBuf(FloatBuffer buf) {
        int n = buf.limit();
        for (int i = 0; i < n; i++) {
            buf.put(i, process(buf.get(i)));
        }
    }

    /** 对应上游 {@code #[derive(Debug)]}：上游会把 7 个字段全打出来，这里保持一致。 */
    @Override
    public String toString() {
        return "Biquad{b0=" + b0 + ", b1=" + b1 + ", b2=" + b2
                + ", a1=" + a1 + ", a2=" + a2 + ", z1=" + z1 + ", z2=" + z2 + '}';
    }
}
