package dev.t1m3.qplayer.audio.dsp;

/**
 * 两路互补分频：一路低频、一路高频，且<strong>两路相加近似等于原信号</strong>。
 *
 * <p>用「低通 + (原信号 − 低通)」而不是「低通 + 高通」来保证互补性。两个独立设计的滤波器
 * 在截止点附近相位不同，直接相加会在分频点留下一个幅度凹陷——听感上是分频处「薄」了一块。
 * 做差就没有这个问题。automix 的「低音交接」就靠这条性质：同一条信号的低频段与高频段
 * 分别按不同时序交叉，合起来才不会在分频点掉能量。
 *
 * <p>对应 Sonalis 的 {@code sonalis_dsp::biquad::SplitLowHigh}。与上游的差异：
 *
 * <ul>
 *   <li>上游 {@code process} 返回元组 {@code (f32, f32)}；Java 没有元组，用不可变值类
 *       {@link Split} 代替（字段 {@code low}/{@code high} 对应 {@code .0}/{@code .1}）。
 *       每次调用都会新建一个 {@code Split}，逐样本渲染里嫌它分配就用
 *       {@link #processInto(float, float[])} 写进调用方自己的数组</li>
 *   <li>上游 {@code &mut self} 方法在这里是可变方法；一个实例只能被一个线程使用，
 *       多声道要各自持有实例（上游 automix 是每声道一个）</li>
 *   <li>上游派生 {@code PartialEq}，这里<b>不</b>移植成 {@code equals}：
 *       f32 的 {@code ==} 对 NaN 为 false，会违反 {@code Object.equals} 的自反契约
 *       （理由与 {@link Biquad} 一样）</li>
 *   <li>上游 {@code Copy}/{@code Clone} → {@link #copy()}（连内部低通的状态一起快照；
 *       想从零状态的另一个滤波器开始，就重新 {@code new SplitLowHigh(...)}）</li>
 * </ul>
 */
public final class SplitLowHigh {

    /** 内部低通。上游是 {@code lp: Biquad}。 */
    private final Biquad lp;

    /**
     * 以分频点构造。
     *
     * <p>Q 取 0.5 让截止点附近更平缓：分解比锐利的分离更重要。
     */
    public SplitLowHigh(int rate, float crossoverHz) {
        this.lp = Biquad.lowPass(rate, crossoverHz, 0.5f);
    }

    /** 清空内部状态（跳转后必须调用，否则旧余振会漏进新位置）。 */
    public void reset() {
        lp.reset();
    }

    /** 复制内部状态，对应上游的 {@code #[derive(Clone, Copy)]}。 */
    public SplitLowHigh copy() {
        // 上游的 Clone 会把 lp 一起复制（含延迟链），这里借 Biquad.copy 保持同样语义。
        return new SplitLowHigh(this);
    }

    /** 复制构造：不重新设计系数，正好对应上游 {@code Clone} 的语义。 */
    private SplitLowHigh(SplitLowHigh other) {
        this.lp = other.lp.copy();
    }

    /**
     * 把一个样本拆成（低频, 高频）。两者之和等于输入。
     *
     * <p>注意：{@code low} 是低通的输出，{@code high} 是<strong>做差</strong>得来的，
     * 不是独立设计的高通。合计误差只有一个浮点舍入（|x| ≤ 1 时 ≤ 1 ulp），
     * 上游 {@code split_recombines_exactly} 锁着这条性质（容差 1e-6）。
     */
    public Split process(float x) {
        float low = lp.process(x);
        return new Split(low, x - low);
    }

    /**
     * 同上，但把结果写进调用方给的长度 ≥ 2 的数组：{@code out[0] = 低频}、
     * {@code out[1] = 高频}，返回 {@code out} 方便链式使用。
     *
     * <p>逐样本的渲染路径（automix 的交接、分频）应当用它：音频回调里不允许分配，
     * 而 {@link #process(float)} 每个样本都会新建一个 {@link Split}。
     * 两条路径的算法完全一样，结果逐位相同（有测试锁着）。
     */
    public float[] processInto(float x, float[] out) {
        if (out == null || out.length < 2) {
            throw new IllegalArgumentException("分频结果需要长度 >= 2 的数组，实际 "
                    + (out == null ? "null" : String.valueOf(out.length)));
        }
        float low = lp.process(x);
        out[0] = low;
        out[1] = x - low;
        return out;
    }

    /**
     * 一次分频的结果：低频与高频两路。
     *
     * <p>对应上游 {@code process} 返回的元组 {@code (f32, f32)}：Java 没有元组，
     * 用不可变值类代替，字段名 {@code low}/{@code high} 即元组的 {@code .0}/{@code .1}
     * （按移植约定，带数据类型的字段公开且 {@code final}）。
     */
    public static final class Split {

        /** 低频路 = {@code lowpass(x)}。 */
        public final float low;

        /** 高频路 = {@code x - lowpass(x)}（做差，不是独立设计的高通）。 */
        public final float high;

        private Split(float low, float high) {
            this.low = low;
            this.high = high;
        }

        /**
         * 对应上游元组的 {@code Debug}：{@code (lo, hi)}。
         *
         * <p>这条路径不是转场用的，保持简短（不做 {@code equals}，理由同外层类注释）。
         */
        @Override
        public String toString() {
            return "(" + low + ", " + high + ")";
        }
    }

    /** 对应上游 {@code #[derive(Debug)]}：上游会打成 {@code SplitLowHigh { lp: ... }}。 */
    @Override
    public String toString() {
        return "SplitLowHigh{lp=" + lp + '}';
    }
}
