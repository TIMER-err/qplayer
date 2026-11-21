package dev.t1m3.qplayer.audio.dsp;

/**
 * 三段电平表：低 / 中 / 高（0..1），给歌词动态背景这类「跟着音乐动」的画面用。
 *
 * <p>上游 Sonalis 没有这个东西——它是 QPlayer 的界面需要。所以这里不追求「和某个上游一致」，
 * 只追求<strong>便宜、稳定、看起来对</strong>：
 *
 * <ul>
 *   <li><strong>不用 FFT</strong>：两个一阶低通（200 Hz / 2 kHz）做互补分解就够了，
 *       每个样本两次一阶滤波 + 三次乘加。FFT 要按窗算、还要为它留缓冲，
 *       而这里只要一个「大概的频段能量」；</li>
 *   <li><strong>互补分解</strong>（与 {@link SplitLowHigh} 同一个理由）：两路独立设计的滤波器
 *       相加会在分频点掉能量，做差不会。低 = lp200、中 = lp2k − lp200、高 = 原信号 − lp2k，
 *       三段相加正好是原信号；</li>
 *   <li><strong>按块给值</strong>：一块算一次 RMS，再映射成 0..1（−60 dB → 0，0 dB → 1），
 *       最后做过攻击/慢释放的平滑。快攻击是为了让鼓点「跳」起来，慢释放是为了不让画面抖。</li>
 * </ul>
 *
 * <p>本类<strong>不分配内存</strong>（结果写进调用方给的数组），可以安全地放在音频生产线程上跑。
 * 实例不可跨线程共享（内部有滤波器状态）。
 */
public final class BandLevels {

    /** 频段数：低 / 中 / 高。 */
    public static final int BANDS = 3;

    /** 低频段上界（Hz）。 */
    private static final float LOW_CROSSOVER_HZ = 200f;
    /** 中频段上界（Hz）。 */
    private static final float MID_CROSSOVER_HZ = 2_000f;
    /** 电平映射的下限：低于 −60 dBFS 一律算 0。 */
    private static final float FLOOR_DB = -60f;
    /** 平滑系数（每块一次）：快攻击、慢释放。 */
    private static final float ATTACK = 0.55f;
    private static final float RELEASE = 0.12f;

    private final OnePole lowPass;
    private final OnePole midPass;
    private final float[] smoothed = new float[BANDS];

    /**
     * @param rate 输出采样率（Hz）。用设备实际生效的那个，不是源采样率——
     *             这里的样本是即将送去声卡的交错数据。
     */
    public BandLevels(int rate) {
        this.lowPass = OnePole.lowPass(rate, LOW_CROSSOVER_HZ);
        this.midPass = OnePole.lowPass(rate, MID_CROSSOVER_HZ);
    }

    /** 清空滤波器状态与平滑值（开新会话/跳转后用，免得旧余振与新位置的电平混在一起）。 */
    public void reset() {
        lowPass.reset();
        midPass.reset();
        smoothed[0] = 0f;
        smoothed[1] = 0f;
        smoothed[2] = 0f;
    }

    /**
     * 吃一块<strong>交错</strong>样本，把三个频段的电平（0..1）写进 {@code out[offset..offset+2]}。
     *
     * @param samples  交错样本（长度 &gt;= {@code length}）
     * @param length   有效样本数（不是帧数）
     * @param channels 声道数；多声道先平均成单声道再分频
     */
    public void process(float[] samples, int length, int channels, float[] out, int offset) {
        if (channels < 1) {
            throw new IllegalArgumentException("声道数必须 >= 1：" + channels);
        }
        double energyLow = 0.0;
        double energyMid = 0.0;
        double energyHigh = 0.0;
        int frames = 0;
        for (int i = 0; i + channels <= length; i += channels) {
            float mono = 0f;
            for (int c = 0; c < channels; c++) {
                mono += samples[i + c];
            }
            mono /= channels;
            float low = lowPass.process(mono);
            float upToMid = midPass.process(mono);
            float mid = upToMid - low;
            float high = mono - upToMid;
            energyLow += (double) low * low;
            energyMid += (double) mid * mid;
            energyHigh += (double) high * high;
            frames++;
        }
        if (frames == 0) {
            return;
        }
        smooth(0, toLevel(energyLow / frames));
        smooth(1, toLevel(energyMid / frames));
        smooth(2, toLevel(energyHigh / frames));
        for (int i = 0; i < BANDS; i++) {
            out[offset + i] = smoothed[i];
        }
    }

    /** RMS（功率量）→ 0..1：−60 dBFS 以下算 0，0 dBFS 算 1。 */
    private static float toLevel(double meanSquare) {
        double rms = Math.sqrt(meanSquare);
        if (rms <= 1e-7) {
            return 0f;
        }
        double db = 20.0 * Math.log10(rms);
        double normalized = (db - FLOOR_DB) / -FLOOR_DB;
        if (normalized <= 0.0) {
            return 0f;
        }
        return normalized >= 1.0 ? 1f : (float) normalized;
    }

    private void smooth(int band, float value) {
        float previous = smoothed[band];
        float coefficient = value > previous ? ATTACK : RELEASE;
        smoothed[band] = previous + (value - previous) * coefficient;
    }
}
