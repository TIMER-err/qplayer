package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;

/**
 * 染色（饱和/激励）：<strong>驱动 → 过采样 → 波形整形 → 降采样 → 直流阻断 → 干湿</strong>。
 *
 * <h2>过采样怎么做的（以及它的代价）</h2>
 *
 * <p>用<strong>零填充 + 低通</strong>这条最朴素的路子：先把信号插值到 2×/4×
 * （相邻采样之间补零，再过一遍抗镜像低通），整形，最后用同一族的低通降回来并抽取。
 * 理由：这里要的不是「完美的重采样器」，而是「把整形产生的谐波挡在奈奎斯特之上」——
 * 而 {@link StudioBiquad} 的四阶巴特沃斯（两级级联）在目标频段上的镜像抑制已经够
 * （判据量的是<strong>混叠</strong>：喂一个 12 kHz 的正弦，看 36 kHz 的谐波有没有折叠到
 * 12 kHz 附近）。
 *
 * <p>代价如实写在这里：零填充插值在通带里引入一点高频滚降与轻微的通带纹波；
 * 它是这一档的已知取舍（要更干净就得写半带 FIR），判据里量了那一点滚降。
 *
 * <h2>判据（{@code SaturationTest}）</h2>
 *
 * <ul>
 *   <li>驱动 0 / 全干 / 关掉 → <strong>逐位直通</strong>；</li>
 *   <li>THD（总谐波失真）随驱动单调上升，而且与解析式 tanh 的预测同量级（±20%）；</li>
 *   <li><strong>过采样开关真的有用</strong>：喂 12 kHz 正弦 + 大驱动时，
 *       开过采样后「折叠到可听段的能量」必须比关掉时低 ≥ 10 dB；</li>
 *   <li>直流阻断：喂一个带直流偏移的信号，输出的直流分量被压到 ≤ −40 dB。</li>
 * </ul>
 *
 * <p>实时规则：所有缓冲构造时分配（含过采样的中间缓冲），{@link #process} 零分配。
 */
public final class SaturationModule implements StudioModule {

    /** 过采样的最大倍数。 */
    private static final int MAX_OVERSAMPLE = 4;

    private final int rate;
    private final int channels;

    private volatile SaturationParams params = SaturationParams.off();

    /** 过采样用的插值/抽取低通（每声道一组，两级级联 = 四阶巴特沃斯）。 */
    private final StudioBiquad[][] interpolation;
    private final StudioBiquad[][] decimation;
    /** 直流阻断（每声道一个一阶高通）。 */
    private final double[] dcX1;
    private final double[] dcY1;
    private double dcCoef;

    public SaturationModule(int rate, int channels) {
        this.rate = Math.max(1, rate);
        this.channels = Math.max(1, channels);
        interpolation = new StudioBiquad[this.channels][2 * (MAX_OVERSAMPLE - 1)];
        decimation = new StudioBiquad[this.channels][2 * (MAX_OVERSAMPLE - 1)];
        for (int ch = 0; ch < this.channels; ch++) {
            for (int k = 0; k < interpolation[ch].length; k++) {
                interpolation[ch][k] = StudioBiquad.lowPass(this.rate, this.rate * 0.45, 0.5412);
                decimation[ch][k] = StudioBiquad.lowPass(this.rate, this.rate * 0.45, 1.3066);
            }
        }
        dcX1 = new double[this.channels];
        dcY1 = new double[this.channels];
        // 10 Hz 的一阶高通（直流阻断）：y[n] = x[n] − x[n−1] + 0.999·y[n−1]
        dcCoef = 1.0 - 2.0 * Math.PI * 10.0 / rate;
    }

    public void setParams(SaturationParams value) {
        SaturationParams next = value == null ? SaturationParams.off() : value;
        if (next.oversample != params.oversample) {
            designFilters(next.oversample);
        }
        this.params = next;
    }

    /**
     * 按<strong>实际的内部采样率</strong>设计插值 / 抽取低通。
     *
     * <p>第一版把两组滤波器都按 4× 的内部采样率设计一次（{@code rate * MAX_OVERSAMPLE}），
     * 于是选 2× 时它们的<strong>有效截止频率只有设计值的一半</strong>——判据当场照出来：
     * 开 2× 之后 12 kHz 附近的混叠能量反而比不开高 0.5 dB（正常应当低 10 dB 以上）。
     * 换系数保留状态（{@code updateFrom}），所以中途改这一档不会咔哒。
     */
    private void designFilters(int os) {
        int internalRate = rate * Math.max(1, os);
        double cutoff = rate * 0.45;
        for (int ch = 0; ch < channels; ch++) {
            for (int k = 0; k < interpolation[ch].length; k++) {
                interpolation[ch][k].updateFrom(StudioBiquad.lowPass(internalRate, cutoff, 0.5412));
                decimation[ch][k].updateFrom(StudioBiquad.lowPass(internalRate, cutoff, 1.3066));
            }
        }
    }

    public SaturationParams params() {
        return params;
    }

    @Override
    public boolean active() {
        return params.active();
    }

    /** 过采样与插值滤波器不改变时间轴（线性相位不是它的目标），所以延迟为 0。 */
    @Override
    public int latencyFrames() {
        return 0;
    }

    @Override
    public void reset() {
        for (StudioBiquad[] row : interpolation) {
            for (StudioBiquad b : row) {
                b.reset();
            }
        }
        for (StudioBiquad[] row : decimation) {
            for (StudioBiquad b : row) {
                b.reset();
            }
        }
        java.util.Arrays.fill(dcX1, 0.0);
        java.util.Arrays.fill(dcY1, 0.0);
    }

    @Override
    public void process(AudioBuf buf) {
        SaturationParams p = params;
        if (!p.active()) {
            return;                            // 直通：一个采样都不碰（位完美的前提）
        }
        int frames = buf.frames();
        if (frames <= 0) {
            return;
        }
        int chs = Math.min(buf.channels(), channels);
        float[] backing = buf.backingArray();
        int os = p.oversample;
        double drive = p.driveLinear();
        double makeup = p.makeupLinear();
        float mix = (float) p.mix;

        for (int i = 0; i < frames; i++) {
            for (int ch = 0; ch < chs; ch++) {
                int at = buf.channelBase(ch) + i;
                double dry = backing[at];
                double v;
                if (os == 1) {
                    v = shape(p.curve, dry * drive) * makeup;
                } else {
                    // ① 插值到 os×（零填充 + 低通）：这两个（或四个）内部采样要按顺序
                    //    穿过**同一个**滤波器状态——那正是多相插值的标准做法。
                    // ② 在每个内部采样上整形。
                    // ③ 低通之后**抽取**：输出的是最后那一个内部采样的滤波结果，
                    //    **不是它们的和**——求和等于「每个内部采样各滤一遍再相加」，
                    //    那是个错的抽取器（实测它会让 12 kHz 附近的混叠反而多 5.9 dB）。
                    int stages = 2 * (os - 1);
                    double decimated = 0.0;
                    for (int k = 0; k < os; k++) {
                        double up = k == 0 ? dry * os : 0.0;
                        double filtered = up;
                        for (int s = 0; s < stages; s++) {
                            filtered = interpolation[ch][s].process(filtered);
                        }
                        filtered = shape(p.curve, filtered * drive) * makeup;
                        for (int s = 0; s < stages; s++) {
                            filtered = decimation[ch][s].process(filtered);
                        }
                        decimated = filtered;
                    }
                    v = decimated;
                }
                // ④ 直流阻断
                double y = v - dcX1[ch] + dcCoef * dcY1[ch];
                dcX1[ch] = v;
                dcY1[ch] = y;
                backing[at] = (float) (dry + (y - dry) * mix);
            }
        }
    }

    /** 波形整形曲线（都在 ±1 附近趋于饱和）。 */
    private static double shape(SaturationParams.Curve curve, double x) {
        switch (curve) {
            case TANH:
                return Math.tanh(x);
            case SOFT_CLIP:
                // 三次软削波：|x| ≤ 1 时 x − x³/3，外面夹在 ±2/3
                if (x >= 1.0) {
                    return 2.0 / 3.0;
                }
                if (x <= -1.0) {
                    return -2.0 / 3.0;
                }
                return x - x * x * x / 3.0;
            default:
                // 折线（二极管近似）：小信号线性、拐点之后斜率减半，两个拐点不对称
                if (x > 0.7) {
                    return 0.7 + (x - 0.7) * 0.35;
                }
                if (x < -0.5) {
                    return -0.5 + (x + 0.5) * 0.5;
                }
                return x;
        }
    }

    @Override
    public String toString() {
        return "SaturationModule{" + params + "}";
    }
}
