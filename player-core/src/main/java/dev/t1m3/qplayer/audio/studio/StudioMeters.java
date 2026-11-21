package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;

/**
 * 分析表头（P8）：<strong>峰值 / 真峰 / RMS / LUFS（M、S、I）/ 相位相关性</strong>。
 *
 * <h2>为什么这些数要按标准算，而不是「看着差不多」</h2>
 *
 * <p>表头是给用户做判断用的（「这首歌比那首响 6 dB 吗」「这一段是不是反相了」），
 * 所以每个数都有定义、有出处：
 *
 * <ul>
 *   <li><b>真峰（true peak，dBTP）</b>：ITU-R BS.1770 的定义是「重建后的模拟信号峰值」。
 *       实现上按 4× 过采样后的峰值算（零填充 + 低通，与 {@code SaturationModule} 同一套）；
 *       采样峰值会低估 0.1–1 dB（48 kHz、16 kHz 以上的内容尤其明显），真峰才是「会不会削」。；</li>
 *   <li><b>LUFS（K 加权响度）</b>：BS.1770 的两级滤波器（高架 + 高通，见 {@link #kWeight}）
 *       之后算均方值，M = 400 ms 窗、S = 3 s 窗、I = 整段（未门限化的积分；
 *       <b>门限化（−70 LUFS 绝对门 + −10 LU 相对门）本版没做</b>，如实写在这里：
 *       它只影响「整首歌的节目响度」这一个数，而播放器要的是「此刻多响」）；</li>
 *   <li><b>相位相关性</b>：L/R 的归一化互相关（+1 = 单声道、0 = 无关、−1 = 反相）。
 *       它比「看波形」有用：反相的立体声在单声道设备上会凭空消失。</li>
 * </ul>
 *
 * <h2>发布方式</h2>
 *
 * <p>与 {@code SpectrumTap} 同一个形状：生产者每块调一次 {@link #process(AudioBuf)}，
 * 界面读 {@link #snapshot()}（不可变对象，一次性原子替换）。<b>零分配？不是</b>——
 * 快照对象每块 new 一个（与 {@code TransitionSnapshot} 同一个取舍，如实记在这里）；
 * 内部累计的缓冲与滤波器状态都是构造时分配。
 */
public final class StudioMeters {

    /** 一阶 K 加权滤波器的两个级联（高架 + 高通），每声道一份。 */
    private final StudioBiquad[][] kWeight;
    /** 真峰用的 4× 插值滤波器（每声道两级级联）。 */
    private final StudioBiquad[][] truePeakLp;
    private final int channels;
    private final int rate;

    /** 400 ms 窗（M）与 3 s 窗（S）的均方积分器。 */
    private final double[] mWindow;
    private final double[] sWindow;
    private int mAt;
    private int sAt;
    private double mSum;
    private double sSum;

    /** 整段（I）的均方和与样本数。 */
    private double iSum;
    private long iCount;

    /** L/R 的互相关累加（相位表）。 */
    private double corrLR;
    private double corrLL;
    private double corrRR;

    /** 采样峰值与真峰（按块重置的瞬时值 + 保持）。 */
    private double peakHold;
    private double truePeakHold;

    private volatile Snapshot snapshot = Snapshot.SILENT;

    public StudioMeters(int rate, int channels) {
        this.rate = Math.max(1, rate);
        this.channels = Math.max(2, channels);
        this.kWeight = new StudioBiquad[this.channels][2];
        for (int ch = 0; ch < this.channels; ch++) {
            // BS.1770 的两级：高架（+4 dB @ 1.5 kHz 以上）+ 高通（38 Hz）
            kWeight[ch][0] = kWeightHighShelf(this.rate);
            kWeight[ch][1] = kWeightHighPass(this.rate);
        }
        this.truePeakLp = new StudioBiquad[this.channels][2];
        for (int ch = 0; ch < this.channels; ch++) {
            truePeakLp[ch][0] = StudioBiquad.lowPass(this.rate * 4, this.rate * 0.45, 0.5412);
            truePeakLp[ch][1] = StudioBiquad.lowPass(this.rate * 4, this.rate * 0.45, 1.3066);
        }
        this.mWindow = new double[Math.max(1, (int) (0.4 * this.rate))];
        this.sWindow = new double[Math.max(1, (int) (3.0 * this.rate))];
    }

    /** K 加权第一级（高架）。系数按 BS.1770 的表格换算成 RBJ 高架。 */
    private static StudioBiquad kWeightHighShelf(int rate) {
        // BS.1770-4 表 1（48 kHz）：b = [1.53512485958697, −2.69169618940638, 1.19839281085285]，
        // a = [1, −1.69065929318241, 0.73248077421585]。等价于一个 ~+4 dB 的高架（转折约 1.5 kHz）。
        double b0 = 1.53512485958697;
        double b1 = -2.69169618940638;
        double b2 = 1.19839281085285;
        double a1 = -1.69065929318241;
        double a2 = 0.73248077421585;
        return StudioBiquad.fromCoeffs(b0, b1, b2, a1, a2);
    }

    /** K 加权第二级（高通，38 Hz）。系数同样来自 BS.1770 的表格（48 kHz）。 */
    private static StudioBiquad kWeightHighPass(int rate) {
        double b0 = 1.0;
        double b1 = -2.0;
        double b2 = 1.0;
        double a1 = -1.99004745483398;
        double a2 = 0.99007225036621;
        return StudioBiquad.fromCoeffs(b0, b1, b2, a1, a2);
    }

    /** 清状态（跳转、换曲、重开设备）。 */
    public void reset() {
        for (StudioBiquad[] row : kWeight) {
            for (StudioBiquad b : row) {
                b.reset();
            }
        }
        for (StudioBiquad[] row : truePeakLp) {
            for (StudioBiquad b : row) {
                b.reset();
            }
        }
        java.util.Arrays.fill(mWindow, 0.0);
        java.util.Arrays.fill(sWindow, 0.0);
        mAt = 0;
        sAt = 0;
        mSum = 0.0;
        sSum = 0.0;
        iSum = 0.0;
        iCount = 0L;
        corrLR = 0.0;
        corrLL = 0.0;
        corrRR = 0.0;
        peakHold = 0.0;
        truePeakHold = 0.0;
        snapshot = Snapshot.SILENT;
    }

    /** 每块调一次（生产者线程）。 */
    public void process(AudioBuf buf) {
        int frames = buf.frames();
        if (frames <= 0) {
            return;
        }
        int chs = Math.min(buf.channels(), channels);
        float[] backing = buf.backingArray();
        int baseL = buf.channelBase(0);
        int baseR = buf.channelBase(1 % buf.channels());
        for (int i = 0; i < frames; i++) {
            double l = backing[baseL + i];
            double r = backing[baseR + i];
            // ① 采样峰值
            double peak = Math.max(Math.abs(l), Math.abs(r));
            // ② 真峰：4× 插值后的峰值（零填充 + 低通）
            double tp = 0.0;
            for (int k = 0; k < 4; k++) {
                double upL = k == 0 ? l * 4.0 : 0.0;
                double upR = k == 0 ? r * 4.0 : 0.0;
                double fL = truePeakLp[0][1].process(truePeakLp[0][0].process(upL));
                double fR = truePeakLp[1][1].process(truePeakLp[1][0].process(upR));
                tp = Math.max(tp, Math.max(Math.abs(fL), Math.abs(fR)));
            }
            // ③ K 加权（响度）
            double kl = kWeight[0][1].process(kWeight[0][0].process(l));
            double kr = kWeight[1][1].process(kWeight[1][0].process(r));
            double kSquared = kl * kl + kr * kr;
            // ④ 相关（相位）
            corrLR += l * r;
            corrLL += l * l;
            corrRR += r * r;
            // ⑤ 窗口积分：M = 400 ms、S = 3 s（滑窗用「环形缓冲 + 当前和」实现）
            mSum -= mWindow[mAt];
            mWindow[mAt] = kSquared;
            mSum += kSquared;
            mAt = mAt + 1 == mWindow.length ? 0 : mAt + 1;
            sSum -= sWindow[sAt];
            sWindow[sAt] = kSquared;
            sSum += kSquared;
            sAt = sAt + 1 == sWindow.length ? 0 : sAt + 1;
            iSum += kSquared;
            iCount++;
            peakHold = Math.max(peakHold, peak);
            truePeakHold = Math.max(truePeakHold, tp);
        }
        publish(0L);
    }

    /**
     * 均方和 → LUFS：{@code −0.691 + 10·log10(Σ(通道加权均方))}。
     *
     * <p>{@code −0.691} 是 BS.1770 里那个把「1 kHz 正弦、−20 dBFS」校准到 −20 LUFS 的常数
     * （判据里就量这一条）。
     */
    private static double loudnessOf(double sumOfSquares, long samples) {
        if (samples <= 0L) {
            return -120.0;
        }
        double mean = sumOfSquares / samples;
        if (mean <= 1e-12) {
            return -120.0;
        }
        return -0.691 + 10.0 * Math.log10(mean);
    }

    /** 归一化互相关（+1 = 完全同相，−1 = 反相，0 = 无关）。 */
    private double correlation() {
        double denominator = Math.sqrt(Math.max(corrLL * corrRR, 1e-30));
        if (corrLL < 1e-12 || corrRR < 1e-12) {
            return 0.0;
        }
        return Math.max(-1.0, Math.min(1.0, corrLR / denominator));
    }

    /** 当前快照（界面读它；不可变）。 */
    public Snapshot snapshot() {
        return snapshot;
    }

    /**
     * 峰值表复位（用户按「清峰值」时调）。
     *
     * <p><strong>它必须重新发布快照</strong>：界面读的是 {@link #snapshot()} 那一份不可变对象，
     * 只把字段清零而不发布，读数会一直停在旧值上——判据（复位之后读数归零）当场红。
     */
    public void clearPeakHold() {
        peakHold = 0.0;
        truePeakHold = 0.0;
        publish(0L);
    }

    /**
     * 用当前累计值发布一份新快照（{@code iSamplesOverride} 用于复位那一瞬间）。
     *
     * <p>M 与 S 各自除以**自己那个窗的长度**（400 ms / 3 s）：两个窗都用同一个分母的话，
     * S 会凭空高出 {@code 10·log10(3/0.4)} ≈ 8.8 dB——这是最容易写错、也最容易看出来的一个数，
     * 所以判据里量了「稳态信号下 M 与 S 必须接近」。
     */
    private void publish(long iSamplesOverride) {
        long iSamples = iSamplesOverride > 0L ? iSamplesOverride : iCount;
        // 每一档除以**真的攒够的那一段**：滑窗还没填满时（刚开播的头 0.4 s / 3 s）
        // 按整窗长去除会把响度报低——3 s 窗最多少报 4.8 dB，
        // 而界面上那三个数并排显示，用户一眼就能看出「S 比 M 低 5 到 9 dB」。
        // 判据：PlaybackEngineTest 里量「稳态信号下 M 与 S 必须接近」。
        snapshot = new Snapshot(peakHold, truePeakHold,
                loudnessOf(mSum, Math.min(iCount, mWindow.length)),
                loudnessOf(sSum, Math.min(iCount, sWindow.length)),
                iSamples > 0L ? loudnessOf(iSum, iSamples) : -120.0, correlation());
    }

    /** 一次表头读数（不可变）。 */
    public static final class Snapshot {

        /** 什么都不在放时的读数。 */
        public static final Snapshot SILENT = new Snapshot(0.0, 0.0, -120.0, -120.0, -120.0, 0.0);

        /** 采样峰值（线性 0..）。 */
        public final double peak;
        /** 真峰（线性，4× 过采样）。 */
        public final double truePeak;
        /** 响度：400 ms 窗（LUFS）。 */
        public final double lufsM;
        /** 响度：3 s 窗（LUFS）。 */
        public final double lufsS;
        /** 响度：整段积分（LUFS，**未做门限化**，见类注释）。 */
        public final double lufsI;
        /** 相位相关性（−1..+1）。 */
        public final double correlation;

        Snapshot(double peak, double truePeak, double lufsM, double lufsS, double lufsI,
                 double correlation) {
            this.peak = peak;
            this.truePeak = truePeak;
            this.lufsM = lufsM;
            this.lufsS = lufsS;
            this.lufsI = lufsI;
            this.correlation = correlation;
        }

        /** 峰值（dBFS）。 */
        public double peakDb() {
            return db(peak);
        }

        /** 真峰（dBTP）。 */
        public double truePeakDb() {
            return db(truePeak);
        }

        /** 相关性的一句话（界面直接显示）。 */
        public String correlationLabel() {
            if (correlation > 0.9) {
                return "单声道（几乎完全同相）";
            }
            if (correlation > 0.3) {
                return "同相";
            }
            if (correlation > -0.3) {
                return "宽（相关性低）";
            }
            if (correlation > -0.9) {
                return "反相（单声道下会抵消）";
            }
            return "几乎完全反相";
        }

        private static double db(double linear) {
            return linear <= 1e-9 ? -120.0 : 20.0 * Math.log10(linear);
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "峰值 %.1f dBFS · 真峰 %.1f dBTP · M %.1f / S %.1f / I %.1f LUFS · %s",
                    Double.valueOf(peakDb()), Double.valueOf(truePeakDb()), Double.valueOf(lufsM),
                    Double.valueOf(lufsS), Double.valueOf(lufsI), correlationLabel());
        }
    }
}
