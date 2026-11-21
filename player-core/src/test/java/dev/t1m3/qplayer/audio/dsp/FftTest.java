package dev.t1m3.qplayer.audio.dsp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * FFT 的对齐测试：对应 Sonalis {@code sonalis-dsp/src/fft.rs} 的 {@code mod tests}（9 条）。
 *
 * <p>方法名是上游 snake_case 名的驼峰化，Javadoc 里标出上游原名，方便逐条核对覆盖度。
 * 断言强度、容差数值一律照抄上游，没有放宽。
 *
 * <p>补测（{@code 补测：} 开头的方法）是上游没有测到、但移植时最容易写错的分支：
 * 上游 {@code assert!} / {@code assert_eq!} 的前置条件、{@code magnitude} 的尾部清零、
 * {@code autocorrelate} 的 {@code lag >= n} 分支与"尾部不写"语义、{@code hann} 的
 * 0/1 长度分支。这些行为上游代码里有，但没被锁住。
 *
 * <p>浮点镜像：所有 {@code Math.*} 结果都<strong>立即</strong>转 {@code float}，与实现里的写法
 * 一致（Rust 那边是 {@code f32::sin}）。容差全部照抄上游，<strong>一条都没有放宽</strong>，
 * 依据是实际对照过：
 *
 * <ul>
 *   <li>旋转因子表（n = 64/128/256）逐位对照上游 {@code Fft::new}：<strong>完全一致</strong>
 *       （{@code core::f32::consts::PI} 的 f32 值 == {@code (float) Math.PI}，相位链全程 f32）</li>
 *   <li>冲激 / 直流的谱、{@code magnitude} 结果、Parseval 的频域数组、Hann 窗表、
 *       自相关输出：逐位对照上游<strong>完全一致</strong>（含噪声底部的 bin）</li>
 *   <li>唯一有位差的是 {@code roundtrip}（n = 128、5 Hz）：它的输入来自本文件里的
 *       {@code sine()} 辅助函数，而 {@code 2π·f·i/rate} 这几个相位点上 Rust 的
 *       {@code f32::sin}（系统 {@code sinf}）与 {@code (float) Math.sin} 差 1 ulp——
 *       同样是 (11, 256) 与 (8, 64) 的输入则逐位一致。这点输入位差传到输出只有
 *       2.4e-7（上游容差 1e-4，余量 400 倍），且偏差落在几乎为零的 bin 上。</li>
 * </ul>
 *
 * <p>结论：上游容差本身就是按 f32 抖动留的，Java 侧不需要动。上表也说明"差异来源是
 * 超越函数实现，不是算法"——变换内核在输入逐位相同时输出逐位相同。
 */
public class FftTest {

    /** 与 {@code Fft.PI} 同源的 f32 镜像，测试里的相位必须按 f32 算，否则喂进去的信号就已经不一样了。 */
    private static final float PI = (float) Math.PI;

    /** 对应上游的测试辅助函数 {@code sine(freq, rate, n)}。 */
    private static float[] sine(float freq, float rate, int n) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (float) Math.sin(2.0f * PI * freq * i / rate);
        }
        return out;
    }

    /**
     * 对应上游的 {@code max_by(partial_cmp)} + {@code unwrap}：并列时 Rust 的 {@code max_by} 取
     * <strong>最后一个</strong>最大值，所以这里用 {@code >=}。
     *
     * <p>{@code from} 对应上游的 {@code skip(...)}（自相关那条跳过 lag 0）。
     */
    private static int argMaxLast(float[] v, int from) {
        int best = from;
        for (int i = from + 1; i < v.length; i++) {
            if (v[i] >= v[best]) {
                best = i;
            }
        }
        return best;
    }

    /**
     * 对应上游 {@code impulse_has_flat_spectrum}：冲激的频谱必须平坦——这是 FFT 最直接的正确性判据。
     */
    @Test
    public void impulseHasFlatSpectrum() {
        Fft fft = new Fft(64);
        float[] re = new float[64];
        float[] im = new float[64];
        re[0] = 1.0f;
        fft.forward(re, im);

        for (int k = 0; k < 64; k++) {
            float m = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            assertTrue("bin " + k + " 幅度应为 1，实际 " + m, Math.abs(m - 1.0f) < 1e-4);
        }
    }

    /**
     * 对应上游 {@code dc_lands_in_bin_zero}：直流输入只在 bin 0 有能量。
     */
    @Test
    public void dcLandsInBinZero() {
        Fft fft = new Fft(64);
        float[] re = new float[64];
        float[] im = new float[64];
        for (int i = 0; i < 64; i++) {
            re[i] = 2.0f;
        }
        fft.forward(re, im);

        assertTrue("直流应等于 2×64", Math.abs(re[0] - 128.0f) < 1e-3);
        for (int k = 1; k < 64; k++) {
            float m = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            assertTrue("bin " + k + " 不该有能量，实际 " + m, m < 1e-3);
        }
    }

    /**
     * 对应上游 {@code sine_lands_in_its_bin}：单频正弦的能量必须集中在它自己的 bin 上。
     *
     * <p>64 点、采样率 64 Hz、频率 8 Hz → 恰好落在 bin 8。
     */
    @Test
    public void sineLandsInItsBin() {
        Fft fft = new Fft(64);
        float[] x = sine(8.0f, 64.0f, 64);
        float[] re = x.clone();
        float[] im = new float[64];
        fft.forward(re, im);

        float[] mag = new float[33];
        fft.magnitude(re, im, mag);

        int peak = argMaxLast(mag, 0);
        assertEquals("峰值应落在 bin 8", 8, peak);

        // 单边幅度谱的峰值应约等于时域幅度 1.0
        assertTrue("峰值幅度应约等于 1，实际 " + mag[8], Math.abs(mag[8] - 1.0f) < 0.05f);
        // 邻近 bin 必须很低（整数周期下几乎没有泄漏）
        assertTrue("不该有明显泄漏", mag[7] < 0.05f && mag[9] < 0.05f);
    }

    /**
     * 对应上游 {@code roundtrip_restores_signal}：逆变换必须还原原信号。
     */
    @Test
    public void roundtripRestoresSignal() {
        Fft fft = new Fft(128);
        float[] x = sine(5.0f, 128.0f, 128);
        float[] re = x.clone();
        float[] im = new float[128];
        fft.forward(re, im);
        fft.inverse(re, im);

        for (int i = 0; i < 128; i++) {
            assertTrue("第 " + i + " 点还原失败：" + re[i] + " vs " + x[i],
                    Math.abs(re[i] - x[i]) < 1e-4);
            assertTrue("虚部应为 0，实际 " + im[i], Math.abs(im[i]) < 1e-4);
        }
    }

    /**
     * 对应上游 {@code parseval_holds}：Parseval 时域能量 == 频域能量 / n。
     *
     * <p>求和顺序也照上游来（顺序累加，不做 Kahan / 两两求和），否则这条的误差量级就对不上了。
     */
    @Test
    public void parsevalHolds() {
        Fft fft = new Fft(256);
        float[] x = sine(11.0f, 256.0f, 256);
        float timeEnergy = 0.0f;
        for (int i = 0; i < x.length; i++) {
            timeEnergy += x[i] * x[i];
        }

        float[] re = x.clone();
        float[] im = new float[256];
        fft.forward(re, im);
        float freqEnergy = 0.0f;
        for (int i = 0; i < re.length; i++) {
            freqEnergy += re[i] * re[i] + im[i] * im[i];
        }
        freqEnergy /= 256.0f;

        float rel = Math.abs(timeEnergy - freqEnergy) / Math.max(timeEnergy, 1e-9f);
        assertTrue("能量不守恒，相对误差 " + rel, rel < 1e-4f);
    }

    /**
     * 对应上游 {@code hann_window_shape}：Hann 窗必须两端为零、中点为 1，且<strong>周期性</strong>
     * （首尾不等于 0 的对称窗会在重叠相加时留下幅度起伏）。
     */
    @Test
    public void hannWindowShape() {
        float[] w = new float[8];
        Fft.hann(w);
        assertTrue("起点应为 0", Math.abs(w[0]) < 1e-6f);
        assertTrue("中点应为 1，实际 " + w[4], Math.abs(w[4] - 1.0f) < 1e-6f);
        // 周期性：w[n-1] 应与 w[-1] 接续，即略大于 0 且小于其后继
        assertTrue("终点应在 0 与 1 之间", w[7] > 0.0f && w[7] < w[4]);
    }

    /**
     * 对应上游 {@code hann_satisfies_cola_at_half_hop}：相邻两帧各加半窗后，重叠相加应为常数
     * （COLA 条件）。
     */
    @Test
    public void hannSatisfiesColaAtHalfHop() {
        int n = 64;
        float[] w = new float[n];
        Fft.hann(w);
        // 50% 重叠：相邻两帧的窗值之和应恒为 1
        for (int i = 0; i < n / 2; i++) {
            float s = w[i] + w[i + n / 2];
            assertTrue("第 " + i + " 点窗和为 " + s + "，应为 1", Math.abs(s - 1.0f) < 1e-5f);
        }
    }

    /**
     * 对应上游 {@code autocorrelation_peaks_at_period}：自相关在周期处必须出现峰——节奏估计的地基。
     *
     * <p>注意断言写的是「峰落在周期的<strong>整数倍</strong>上」而不是「恰好等于 10」：
     * 自相关对周期天然是多值的（lag=10/20/30 一样高），这是它的性质不是缺陷。
     * 真正把候选收敛到单一 BPM，靠的是 {@code analysis.rs} 里的速度先验。
     */
    @Test
    public void autocorrelationPeaksAtPeriod() {
        // 每 10 个样本一个脉冲
        float[] x = new float[200];
        for (int i = 0; i < 200; i += 10) {
            x[i] = 1.0f;
        }
        float[] ac = new float[40];
        Fft.autocorrelate(x, 40, ac);

        int peak = argMaxLast(ac, 1);
        assertEquals("自相关峰 " + peak + " 不是周期的整数倍", 0, peak % 10);
        // 周期处的相关必须显著高于非周期处
        assertTrue("lag=10 (" + ac[10] + ") 应显著高于非周期的 lag=5 (" + ac[5] + ")",
                ac[10] > ac[5] * 2.0f);
        // 周期处的相关应当与零延迟同量级
        assertTrue("lag=10 (" + ac[10] + ") 应接近零延迟 (" + ac[0] + ")",
                ac[10] > ac[0] * 0.5f);
    }

    /**
     * 对应上游 {@code non_power_of_two_panics}（{@code #[should_panic(expected = "2 的幂")]}）：
     * 长度非 2 的幂必须报错而不是静默算错。
     */
    @Test(expected = IllegalArgumentException.class)
    public void nonPowerOfTwoPanics() {
        new Fft(100);
    }

    /**
     * 补测：上游 {@code should_panic(expected = "2 的幂")} 里的期望子串在 Java 侧
     * {@code @Test(expected = ...)} 表达不了，单独补一条把消息文本也钉住。
     */
    @Test
    public void nonPowerOfTwoMessageNamesTheConstraint() {
        try {
            new Fft(100);
            fail("非 2 的幂应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue("消息里应说明是 2 的幂的问题，实际：" + e.getMessage(),
                    e.getMessage() != null && e.getMessage().contains("2 的幂"));
        }
        // 上游的 n.is_power_of_two() 对 0 也是 false，构造函数必须同样拒绝 0
        try {
            new Fft(0);
            fail("长度 0 应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue("消息里应说明是 2 的幂的问题，实际：" + e.getMessage(),
                    e.getMessage() != null && e.getMessage().contains("2 的幂"));
        }
    }

    /**
     * 补测：上游 {@code transform} 里的 {@code assert_eq!(re.len(), self.n)} / {@code assert_eq!(im.len(), self.n)}
     * 是前置条件（Rust panic），这里对应 {@link IllegalArgumentException}。上游没测，但很容易漏。
     */
    @Test
    public void transformRejectsWrongLength() {
        Fft fft = new Fft(64);
        try {
            fft.forward(new float[64], new float[32]);
            fail("虚部长度不匹配应当抛异常");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            fft.inverse(new float[63], new float[64]);
            fail("实部长度不匹配应当抛异常");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    /**
     * 补测：{@code magnitude} 在 {@code out} 比 {@code n/2+1} 长时，把多出来的尾部清零
     * （上游 {@code for o in out.iter_mut().skip(take)}）。调用方复用缓冲时靠这一点保证
     * 旧数据不会混进拍点判据里。
     */
    @Test
    public void magnitudeZeroesEverythingPastNyquist() {
        Fft fft = new Fft(64);
        float[] re = new float[64];
        float[] im = new float[64];
        re[0] = 1.0f;
        fft.forward(re, im);

        float[] mag = new float[40];
        for (int i = 0; i < mag.length; i++) {
            mag[i] = 9.0f; // 哨兵：没被写到的位置会留下 9
        }
        fft.magnitude(re, im, mag);

        // 0..32 是 bin 0..32（n/2+1 = 33 个），33..39 必须被清零
        assertTrue("bin 0 应被写入", mag[0] != 9.0f);
        for (int k = 33; k < mag.length; k++) {
            assertEquals("超出奈奎斯特的尾部应清零（下标 " + k + "）", 0.0f, mag[k], 0.0f);
        }
    }

    /**
     * 补测：{@code hann} 的 {@code n == 0} / {@code n == 1} 两个早返回分支。上游代码里有，
     * 但没有测试锁住——0 长度时若走进循环体里的除法就会得到 NaN。
     */
    @Test
    public void hannHandlesEmptyAndSingleSample() {
        float[] empty = new float[0];
        Fft.hann(empty);
        assertEquals(0, empty.length);

        float[] one = new float[1];
        Fft.hann(one);
        assertEquals("长度为 1 时窗值取 1", 1.0f, one[0], 0.0f);
    }

    /**
     * 补测：{@code autocorrelate} 在 {@code lag >= n} 时写 0（上游的 {@code continue} 分支）——
     * 此时没有任何重叠样本可乘。上游没测。
     */
    @Test
    public void autocorrelateZerosLagsPastInputLength() {
        float[] x = new float[10];
        x[0] = 1.0f;
        float[] ac = new float[20];
        Fft.autocorrelate(x, 20, ac);

        assertEquals("lag=0 应等于 1/10", 0.1f, ac[0], 1e-7f);
        for (int lag = 10; lag < 20; lag++) {
            assertEquals("lag " + lag + " 超出输入长度，应为 0", 0.0f, ac[lag], 0.0f);
        }
    }

    /**
     * 补测：{@code autocorrelate} 只写 {@code min(out.length, max_lag)} 个位置，
     * 之后的尾部<strong>保持原样</strong>——这与 {@code magnitude} 的清零行为相反，
     * 是移植时最容易"顺手统一"掉的地方，所以单独钉一条。
     */
    @Test
    public void autocorrelateLeavesOutputPastTakeUntouched() {
        float[] x = new float[200];
        for (int i = 0; i < 200; i += 10) {
            x[i] = 1.0f;
        }
        float[] ac = new float[64];
        for (int i = 0; i < ac.length; i++) {
            ac[i] = 7.0f; // 哨兵
        }
        Fft.autocorrelate(x, 40, ac);

        assertTrue("take 之内应被写入", ac[0] != 7.0f && ac[39] != 7.0f);
        for (int lag = 40; lag < ac.length; lag++) {
            assertEquals("超过 max_lag 的尾部不应被改写（下标 " + lag + "）", 7.0f, ac[lag], 0.0f);
        }
    }
}
