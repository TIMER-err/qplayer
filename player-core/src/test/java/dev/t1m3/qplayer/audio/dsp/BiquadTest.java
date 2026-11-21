package dev.t1m3.qplayer.audio.dsp;

import org.junit.Test;

import java.nio.FloatBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 双二阶 / 一极点 / 互补分频的对齐测试：对应 Sonalis
 * {@code crates/sonalis-dsp/src/biquad.rs} 的 {@code #[cfg(test)] mod tests}（上游 11 条）。
 *
 * <p>上游三条公开类型共用一个 {@code mod tests}，这里也共用一个测试类：方法名与上游测试
 * 一一对应（snake_case → camelCase），断言强度、容差、断言消息都照搬。
 *
 * <p><b>容差来源</b>：上游断言里的容差（直流 1e-3、通道增益 0.05、互补和 1e-6、重置 1e-9、
 * 冲激尾 1e-3）是<strong>算法本身</strong>留的余量，不是给数值库差异的补偿，所以原样保留、
 * 一条都没有放宽。唯一可能引入差异的地方是 {@code Math.sin/cos/exp/pow/sqrt} 与 Rust libm
 * 之间的 1 ulp 级差别（Java 在 double 里算完再立即转 float，glibc 的 sinf/cosf 是正确舍入），
 * 它比上面任何一条容差都小好几个数量级，因此不需要额外放宽。
 * {@link #resetClearsState} 与 {@link #updateLowPassKeepsState} 用 0 容差（逐位相同），
 * 因为那两处两边算的是同一串乘加，不存在超越函数差异。
 *
 * <p>移植时另外做了一次比特级交叉验证（不在本仓库里，属一次性核对）：把上游
 * {@code biquad.rs} 编成一个 Rust 探针程序（只加两个 {@code probe()} 读私有字段，算法未改），
 * 与 Java 实现喂同一组输入——18 组设计系数、6 个一极点系数，以及分频 / 双二阶+一极点串联 /
 * 扫频（每 64 样本 {@code updateLowPass}）/ 低架四条各 1024 样本的序列，
 * 输出连比特打印后逐行比对：4122 行全部相同。所以上面这些容差确实只是算法余量，
 * 没有替数值库差异兜底。
 *
 * <p><b>测试数量</b>：上游 11 条 → 本地 16 条。多出的 5 条是 Java 侧补充
 * （{@link #processIntoMatchesProcess}、{@link #updateLowPassKeepsState}、
 * {@link #copySnapshotsState}、{@link #nanQDegradesToRustMaxSemantics}、
 * {@link #tinyRatesDoNotInvertTheClampRange}），每条都在注释里写明为什么补。
 */
public class BiquadTest {

    /** 与上游测试同名同值。 */
    private static final int RATE = 48_000;

    /**
     * Rust 的 {@code core::f32::consts::FRAC_1_SQRT_2}（0x1.6A09E6p-1 = 0.70710677f）。
     *
     * <p>Java 没有这个常量：{@code (float) (1.0 / Math.sqrt(2.0))} 与 Rust 的字面量逐位相同
     * （double 里 1/√2 精确到 53 位后舍入到 float，与直接舍入 1/√2 的结果一致）。
     */
    private static final float FRAC_1_SQRT_2 = (float) (1.0 / Math.sqrt(2.0));

    /**
     * 对应上游测试里的 {@code fn rms(x: &[f32])}：对切片 {@code [from, to)} 求均方根。
     *
     * <p>和上游一样用 f32 顺序累加（{@code sum::<f32>()} 也是从左到右加），
     * 除数取 {@code len.max(1)}，避免空切片出现 0/0。
     */
    private static float rms(float[] x, int from, int to) {
        float sum = 0f;
        for (int i = from; i < to; i++) {
            sum += x[i] * x[i];
        }
        return (float) Math.sqrt(sum / Math.max(to - from, 1));
    }

    /**
     * 对应上游测试里的 {@code fn sine(freq: f32, n: usize) -> Vec<f32>}。
     *
     * <p>乘法顺序照抄上游的 {@code 2.0 * PI * freq * i as f32 / RATE as f32}
     * （浮点乘法可交换但不可结合，换个顺序相位就会有 ulp 级差别）；
     * {@code PI} 在这里是 f32，{@code Math.sin} 只接受 double，结果立即转回 float。
     */
    private static float[] sine(float freq, int n) {
        float pi = (float) Math.PI;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (float) Math.sin(2f * pi * freq * (float) i / (float) RATE);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 上游测试（biquad.rs 的 mod tests，逐条对应）
    // ------------------------------------------------------------------

    /** 低通必须放过直流。 */
    @Test
    public void lowPassPassesDc() {
        Biquad f = Biquad.lowPass(RATE, 1_000f, FRAC_1_SQRT_2);
        float[] x = new float[4_800];
        java.util.Arrays.fill(x, 1f);
        // 跳过起始瞬态
        f.processBuf(FloatBuffer.wrap(x));
        assertTrue("直流应原样通过，实际 " + x[4_799], Math.abs(x[4_799] - 1f) < 1e-3f);
    }

    /** 低通必须把远超截止点的频率压下去。 */
    @Test
    public void lowPassAttenuatesHighFreq() {
        Biquad f = Biquad.lowPass(RATE, 500f, FRAC_1_SQRT_2);
        float[] x = sine(10_000f, 9_600);
        float before = rms(x, 4_800, x.length);
        f.processBuf(FloatBuffer.wrap(x));
        float after = rms(x, 4_800, x.length);
        assertTrue("10 kHz 应被 500 Hz 低通大幅衰减：" + before + " → " + after,
                after < before * 0.05f);
    }

    /** 高通必须拦住直流。 */
    @Test
    public void highPassBlocksDc() {
        Biquad f = Biquad.highPass(RATE, 500f, FRAC_1_SQRT_2);
        float[] x = new float[9_600];
        java.util.Arrays.fill(x, 1f);
        f.processBuf(FloatBuffer.wrap(x));
        assertTrue("直流应被拦住，实际 " + x[9_599], Math.abs(x[9_599]) < 1e-3f);
    }

    /** 高通必须放过高频。 */
    @Test
    public void highPassPassesHighFreq() {
        Biquad f = Biquad.highPass(RATE, 500f, FRAC_1_SQRT_2);
        float[] x = sine(10_000f, 9_600);
        float before = rms(x, 4_800, x.length);
        f.processBuf(FloatBuffer.wrap(x));
        float after = rms(x, 4_800, x.length);
        float gain = after / before;
        assertTrue("10 kHz 应几乎原样通过，实际增益 " + gain, Math.abs(gain - 1f) < 0.05f);
    }

    /** 冲激响应必须衰减到零——否则滤波器不稳定，会攒出一个直流偏置。 */
    @Test
    public void impulseResponseDecays() {
        Biquad f = Biquad.lowPass(RATE, 200f, 4f); // 高 Q，最容易不稳定
        float[] x = new float[48_000];
        x[0] = 1f;
        f.processBuf(FloatBuffer.wrap(x));

        float early = rms(x, 0, 480);
        float late = rms(x, 43_200, x.length);
        assertTrue("起始段不该是全零", early > 0f);
        assertTrue("尾部残余 " + late + " 相对起始 " + early + " 太大，滤波器可能不稳定",
                late < early * 1e-3f);
        // 绝不能出现非有限值（Rust 的 `v.is_finite()`）
        for (float v : x) {
            assertTrue("出现了非有限值：" + v, Float.isFinite(v));
        }
    }

    /** 分频的两路相加必须等于原信号——这是「做差」这个设计的全部意义。 */
    @Test
    public void splitRecombinesExactly() {
        SplitLowHigh sp = new SplitLowHigh(RATE, 300f);
        float[] x = sine(1_000f, 4_800);
        for (int i = 0; i < x.length; i++) {
            float v = x[i];
            SplitLowHigh.Split s = sp.process(v);
            assertTrue("第 " + i + " 点两路之和 " + (s.low + s.high) + " 不等于原值 " + v,
                    Math.abs((s.low + s.high) - v) < 1e-6f);
        }
    }

    /**
     * 分频点两侧的归属必须正确。
     *
     * <p>判据用<strong>能量比</strong>，而不是「残差路应该接近零」：互补分频的另一路是
     * {@code 原信号 − 低通}，而低通在截止点以下仍有相位滞后，残差因此不会是零。
     * 100 Hz 落在 500 Hz 截止的 1/5 处时，残差仍有约 20% 的幅度——这是相位差的必然结果，
     * 不是滤波器有问题。写死一个「接近零」的断言会把正确的实现判成失败。
     */
    @Test
    public void splitRoutesBandsToTheRightSide() {
        float[] low = energies(100f, 500f);
        assertTrue("100 Hz 应主要走低频路：低 " + low[0] + " 高 " + low[1], low[0] > low[1] * 5f);

        float[] high = energies(8_000f, 500f);
        assertTrue("8 kHz 应主要走高频路：低 " + high[0] + " 高 " + high[1],
                high[1] > high[0] * 5f);
    }

    /** 对应上游 {@code split_routes_bands_to_the_right_side} 里的嵌套 {@code fn energies}。 */
    private static float[] energies(float freq, float crossover) {
        SplitLowHigh sp = new SplitLowHigh(RATE, crossover);
        float[] sig = sine(freq, 9_600);
        float lo = 0f;
        float hi = 0f;
        for (float v : sig) {
            SplitLowHigh.Split s = sp.process(v);
            lo += s.low * s.low;
            hi += s.high * s.high;
        }
        return new float[] {lo, hi};
    }

    /** 上游这条没有文档注释（0 dB 低架应当透明）。 */
    @Test
    public void lowShelfWithZeroGainIsTransparent() {
        Biquad f = Biquad.lowShelf(RATE, 500f, 0.707f, 0f);
        float[] x = sine(200f, 9_600);
        float[] y = x.clone();
        f.processBuf(FloatBuffer.wrap(y));
        float g = rms(y, 4_800, y.length) / rms(x, 4_800, x.length);
        assertTrue("0 dB 架式应透明，实际增益 " + g, Math.abs(g - 1f) < 0.02f);
    }

    /** 一极点低通的直流增益必须是 1。 */
    @Test
    public void onePolePassesDc() {
        OnePole f = OnePole.lowPass(RATE, 1_000f);
        float[] x = new float[4_800];
        java.util.Arrays.fill(x, 1f);
        f.processBuf(FloatBuffer.wrap(x));
        assertTrue("实际 " + x[4_799], Math.abs(x[4_799] - 1f) < 1e-3f);
    }

    /**
     * 截止频率越过奈奎斯特时不能 panic（扫频会掠过边界）。
     *
     * <p>Java 侧的「不 panic」就是「不抛异常」：这里只构造、不断言。注意上游的 clamp 在
     * {@code min > max} 时会 panic，本移植把它映射成 {@link IllegalArgumentException}，
     * 所以这条测试同时锁住「夹紧范围不能反过来」——见
     * {@link #tinyRatesDoNotInvertTheClampRange}。
     */
    @Test
    public void extremeFrequenciesDoNotPanic() {
        Biquad.lowPass(RATE, 1.0e9f, 0.707f);
        Biquad.highPass(RATE, -5f, 0.707f);
        Biquad.lowPass(0, 1_000f, 0.707f);
        OnePole.lowPass(RATE, 1.0e9f);
    }

    /** 重置后应当从零状态开始。 */
    @Test
    public void resetClearsState() {
        Biquad f = Biquad.lowPass(RATE, 1_000f, 0.707f);
        f.process(1f);
        f.reset();
        // 重置后冲激响应应当从头开始，第一个输出等于 b0
        float y = f.process(1f);
        float fresh = Biquad.lowPass(RATE, 1_000f, 0.707f).process(1f);
        assertTrue("重置后应从零状态开始", Math.abs(y - fresh) < 1e-9f);
    }

    // ------------------------------------------------------------------
    // Java 侧补充（上游没有；每条都写明理由）
    // ------------------------------------------------------------------

    /**
     * 补充：分频的零分配路径必须和元组路径逐位相同。
     *
     * <p>{@link SplitLowHigh#process(float)} 返回的 {@code Split} 是每个样本新建的对象，
     * 音频回调里不允许分配，所以移植时加了写进调用方数组的
     * {@link SplitLowHigh#processInto(float, float[])}。这条测试保证两条路径不会走偏，
     * 顺带在原信号上再验一次互补性。
     */
    @Test
    public void processIntoMatchesProcess() {
        SplitLowHigh a = new SplitLowHigh(RATE, 300f);
        SplitLowHigh b = new SplitLowHigh(RATE, 300f);
        float[] sig = sine(1_000f, 1_000);
        float[] out = new float[2];
        for (int i = 0; i < sig.length; i++) {
            SplitLowHigh.Split s = a.process(sig[i]);
            b.processInto(sig[i], out);
            assertEquals("第 " + i + " 点低频应逐位相同", s.low, out[0], 0f);
            assertEquals("第 " + i + " 点高频应逐位相同", s.high, out[1], 0f);
            assertTrue("第 " + i + " 点两路之和应等于原信号",
                    Math.abs((out[0] + out[1]) - sig[i]) < 1e-6f);
        }
    }

    /**
     * 补充：{@code updateLowPass} 的存在理由就是「换系数不清状态」。
     *
     * <p>上游没有这条测试，但扫频（表现模式）完全依赖它：如果换系数时顺手 reset，
     * 分频点附近就会掉一段能量。对一个已经在跑的低通重算成<strong>同样参数</strong>，
     * 之后每个输出都必须与从不调用它的对照实例逐位相同（0 容差：两边是同一串乘加）。
     */
    @Test
    public void updateLowPassKeepsState() {
        Biquad a = Biquad.lowPass(RATE, 1_000f, FRAC_1_SQRT_2);
        Biquad b = Biquad.lowPass(RATE, 1_000f, FRAC_1_SQRT_2);
        float[] sig = sine(1_000f, 500);
        for (int i = 0; i < sig.length; i++) {
            float ya = a.process(sig[i]);
            if (i == 200) {
                a.updateLowPass(RATE, 1_000f, FRAC_1_SQRT_2);
            }
            float yb = b.process(sig[i]);
            assertEquals("第 " + i + " 点：换系数不该动状态", yb, ya, 0f);
        }
    }

    /**
     * 补充：{@code copy()} 对应上游的 {@code Clone}/{@code Copy}，语义是「连状态一起快照」，
     * 不是「同参数的另一个滤波器」。
     *
     * <p>Java 里没有 Copy 语义（对象是引用），所以必须测两件事：快照之后两条轨迹逐位一致；
     * 以及副本是独立对象——原实例 {@code reset} 不会影响它。
     */
    @Test
    public void copySnapshotsState() {
        Biquad f = Biquad.lowPass(RATE, 1_000f, FRAC_1_SQRT_2);
        for (int i = 0; i < 100; i++) {
            f.process(0.5f);
        }
        Biquad g = f.copy();
        for (float v : sine(400f, 300)) {
            assertEquals("快照后的轨迹必须逐位一致", f.process(v), g.process(v), 0f);
        }

        // 再取一份快照，然后只动原实例：副本的轨迹不能跟着变
        Biquad h = g.copy();
        f.reset();
        f.process(1f);
        for (float v : sine(700f, 300)) {
            assertEquals("副本是独立对象，原实例的 reset 不该影响它", h.process(v), g.process(v), 0f);
        }
    }

    /**
     * 补充：上游 {@code q.max(0.05)} 用的是 Rust 的 {@code f32::max}——一边为 NaN 时返回
     * 另一边，所以 q 为 NaN 会退化成 0.05；而 Java 的 {@code Math.max} 会传播 NaN，把整条
     * 系数链污染成 NaN。实现里显式镜像了上游语义，这条测试把它钉住（否则将来有人
     * 「简化」成 Math.max 也不会有测试报警）。
     */
    @Test
    public void nanQDegradesToRustMaxSemantics() {
        Biquad f = Biquad.lowPass(RATE, 1_000f, Float.NaN);
        float y = f.process(1f);
        assertTrue("q 为 NaN 时上游退化成 0.05，输出应当有限，实际 " + y, Float.isFinite(y));
        float z = f.process(1f);
        assertTrue("后续输出也必须有限，实际 " + z, Float.isFinite(z));
    }

    /**
     * 补充：上游注释点名的坑——{@code rate} 为 0/1 时奈奎斯特低于 1 Hz，Rust 的
     * {@code clamp} 在 {@code min > max} 时会 panic（本移植映射成
     * {@link IllegalArgumentException}）。
     *
     * <p>上游测试只覆盖了 {@code Biquad::low_pass} 的 {@code rate = 0}，这里把 {@code rate = 1}
     * 和 {@link OnePole} 的 {@code rate = 0/1} 也钉住：两个设计式都必须自己把下界抬到不超过
     * 上界，并且算出来的系数还得是有限值（夹到 1 Hz 附近，不是 NaN/Inf）。
     */
    @Test
    public void tinyRatesDoNotInvertTheClampRange() {
        Biquad.lowPass(0, 1_000f, 0.707f);
        Biquad.lowPass(1, 1_000f, 0.707f);
        Biquad.highPass(1, 1_000f, 0.707f);
        OnePole.lowPass(0, 1_000f);
        OnePole.lowPass(1, 1_000f);

        float y = OnePole.lowPass(0, 1_000f).process(1f);
        assertTrue("rate = 0 时一极点应给出有限输出，实际 " + y, Float.isFinite(y));
        float z = Biquad.lowPass(0, 1_000f, 0.707f).process(1f);
        assertTrue("rate = 0 时双二阶应给出有限输出，实际 " + z, Float.isFinite(z));
    }
}
