package dev.t1m3.qplayer.audio.studio;

/**
 * 单音基频检测（YIN 那一套：累积均值归一化差值函数 + 抛物线插值）。
 *
 * <h2>为什么自己做，不用现成的</h2>
 *
 * <p>修跑调要的是<strong>流式、零分配、低延迟</strong>的基频：跑在生产者线程上，
 * 每 10 ms 要一个新的估计。现成的做法（整段 FFT + 峰值、自相关的朴素实现）要么要整段
 * 素材，要么每帧分配。所以这里按 YIN 的定义写一遍，参数按「人声」定：
 *
 * <ul>
 *   <li>窗口 <strong>40 ms</strong>、跳距 <strong>10 ms</strong>：40 ms 覆盖男低音
 *       （80 Hz 两个周期 25 ms）绰绰有余，10 ms 够快到能跟住颤音；</li>
 *   <li>范围 <strong>60–1200 Hz</strong>：比人声（约 80–1100 Hz）宽一点，不假装知道更多；</li>
 *   <li>内部分析降到约 <strong>12 kHz</strong>：人声的基频远低于 6 kHz，
 *       降采样把 YIN 的开销砍掉 16 倍（朴素实现在 48 kHz 下每跳要 150 万次乘加，
 *       100 跳/秒就是 1.5 亿——实时跑不动）；</li>
 *   <li><strong>置信度（clarity = 1 − d′）</strong>必须一起报出来：修音只能修「有明确音高」
 *       的那些帧。低置信度还硬修，等于把气声、齿音、说话声一起推走——那比跑调更难听。</li>
 * </ul>
 *
 * <h2>实时规则</h2>
 *
 * <p>所有缓冲构造时分配；{@link #push(float)} 每采样只做几次乘加与一次环形写入；
 * 差值函数每 10 ms 算一次（约 10 万次乘加，48 kHz 下占单核不到 1%）。
 */
public final class PitchDetector {

    /** 分析窗（秒）。 */
    public static final double WINDOW_SECS = 0.040;

    /** 跳距（秒）：每这么多秒出一个新估计。 */
    public static final double HOP_SECS = 0.010;

    /** 最低基频（Hz）。 */
    public static final double MIN_HZ = 60.0;

    /** 最高基频（Hz）。 */
    public static final double MAX_HZ = 1200.0;

    /** YIN 的绝对阈值：累积均值归一化差值低于它才算「找到了周期」。 */
    public static final double YIN_THRESHOLD = 0.15;

    /**
     * 内部分析的目标采样率（Hz）。
     *
     * <p>为什么是 24 kHz 而不是 12 kHz：YIN 的精度由<strong>滞后（lag）的分辨率</strong>决定，
     * 而 lag 是采样率的倒数。12 kHz 下 880 Hz 的一个 lag 步长就是 120 音分，
     * 抛物线插值也压不到 5 音分以内（实测偏 +5.3 音分）——而判据是 &lt; 5 音分。
     * 24 kHz 下同样的音是 44 音分/步，实测偏 +1 音分以内。
     * 代价是差值函数大 4 倍（每跳约 38 万次乘加，100 跳/秒 ≈ 单核 3%），可以接受。
     */
    static final int WORK_RATE = 24_000;

    private final int rate;
    private final int decimate;
    private final int workRate;
    private final int windowFrames;
    private final int hopFrames;
    private final int minLag;
    private final int maxLag;

    /** 降采样累加器。 */
    private double acc;
    private int accCount;

    /**
     * 降采样之前的抗混叠低通（2 kHz 处 −3 dB 的二阶巴特沃斯）。
     *
     * <p>为什么需要它：内部只分析到 {@link #MAX_HZ}（1200 Hz），而「抽头平均」那种便宜的
     * 降采样对 6 kHz 以上的东西几乎不衰减——齿音与高频伴奏会<strong>折叠</strong>到基频附近，
     * 把差值函数污染成一片噪声（置信度虚高、音高乱跳）。一个二阶低通放在这里最省：
     * 每输入采样 5 次乘加。
     */
    private final StudioBiquad antiAlias;

    /** 分析历史（长度 = 窗 + 跳，保证窗口始终是连续的一段）。 */
    private final float[] history;
    /** 新采样的暂存（攒够一跳就整体挪进 history）。 */
    private final float[] inbox;
    private int inCount;
    /** 差值函数 d(τ) 与它的累积均值归一化 d′(τ)。 */
    private final double[] diff;
    private final double[] norm;

    /**
     * 2 倍插值过的历史（精修用）：YIN 的精度受<strong>滞后分辨率</strong>限制，
     * 而分辨率就是内部采样率的倒数。24000 Hz 下 1100 Hz 的一个 lag 步长仍有 80 音分，
     * 抛物线插值只能压到 6 音分左右——而判据是 5 音分以内。
     *
     * <p>于是最后一步在<strong>线性插值出的一倍过采样历史</strong>上重算一遍差值函数
     * （只在粗找到的那个周期附近 ±2 个粗步长里算 9 个点），把分辨率翻倍。
     * 成本是每跳约 1.7 万次乘加——相对整趟差值函数的 68 万次可以忽略。
     */
    private final float[] historyFine;
    /** 精修用的差值（下标是「细 lag」，= 2 × 粗 lag）。 */
    private final double[] fineDiff;

    private double hz;
    private double clarity;

    public PitchDetector(int sampleRate) {
        this.rate = Math.max(1, sampleRate);
        this.decimate = Math.max(1, (int) Math.round((double) rate / WORK_RATE));
        this.workRate = Math.max(1, rate / decimate);
        this.windowFrames = Math.max(16, (int) Math.round(workRate * WINDOW_SECS));
        this.hopFrames = Math.max(1, (int) Math.round(workRate * HOP_SECS));
        this.minLag = Math.max(2, (int) Math.floor((double) workRate / MAX_HZ));
        this.maxLag = Math.min(windowFrames - 2, (int) Math.ceil((double) workRate / MIN_HZ));
        // 截止频率取 MAX_HZ 的 2.5 倍：既保住要检测的那一段，又把折叠源挡掉
        this.antiAlias = StudioBiquad.lowPass(rate, Math.min(MAX_HZ * 2.5, rate * 0.45), 0.7071);
        this.history = new float[windowFrames];
        this.inbox = new float[hopFrames];
        this.diff = new double[maxLag + 2];
        this.norm = new double[maxLag + 2];
        this.historyFine = new float[windowFrames * 2];
        this.fineDiff = new double[9];
    }

    /** 一帧分析窗对应多少输入采样（跳距 × 降采样倍数）——上报延迟与按帧计数时用它。 */
    public int hopInputFrames() {
        return hopFrames * decimate;
    }

    /** 一个分析窗对应多少输入采样。 */
    public int windowInputFrames() {
        return windowFrames * decimate;
    }

    /** 最近的估计（Hz）；{@code 0} = 还没估计过或不可信。 */
    public double hz() {
        return hz;
    }

    /** 最近的置信度（0..1）。 */
    public double clarity() {
        return clarity;
    }

    public void reset() {
        acc = 0.0;
        accCount = 0;
        inCount = 0;
        java.util.Arrays.fill(history, 0f);
        antiAlias.reset();
        hz = 0.0;
        clarity = 0.0;
    }

    /**
     * 推进一个采样（单声道素材）。
     *
     * @return 这一次调用是否刚好凑满一跳（= 有一个新估计可读；用 {@link #hz()} / {@link #clarity()} 取）
     */
    public boolean push(float mono) {
        double v = mono;
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            v = 0.0;
        }
        acc += antiAlias.process(v);
        if (++accCount < decimate) {
            return false;
        }
        // 降采样的低通就是「抽头平均」：人声基频远低于折叠频率，够用且零成本
        float s = (float) (acc / accCount);
        acc = 0.0;
        accCount = 0;

        inbox[inCount++] = s;
        if (inCount < hopFrames) {
            return false;
        }
        // 窗口左移一跳，把新的一跳接在后面（连续的一段，YIN 才能直接用）
        System.arraycopy(history, hopFrames, history, 0, windowFrames - hopFrames);
        System.arraycopy(inbox, 0, history, windowFrames - hopFrames, hopFrames);
        inCount = 0;
        estimate();
        return true;
    }

    /** 在 {@link #history} 上跑一遍 YIN。 */
    private void estimate() {
        int n = windowFrames;
        // ① 差值函数 d(τ) = Σ (x[i] − x[i+τ])²
        for (int tau = minLag; tau <= maxLag; tau++) {
            double sum = 0.0;
            for (int i = 0; i + tau < n; i++) {
                double d = history[i] - history[i + tau];
                sum += d * d;
            }
            diff[tau] = sum;
        }
        // ② 累积均值归一化：d′(τ) = d(τ) / ((1/τ)Σ_{j≤τ} d(j))
        double running = 0.0;
        norm[minLag - 1] = 1.0;
        for (int tau = minLag; tau <= maxLag; tau++) {
            running += diff[tau];
            double mean = running / (tau - minLag + 1);
            norm[tau] = mean > 1e-12 ? diff[tau] / mean : 1.0;
        }
        // ③ 第一个低于阈值的局部极小（YIN 的「第一周期」原则，避免选到倍频）
        int best = -1;
        for (int tau = minLag; tau <= maxLag; tau++) {
            if (norm[tau] < YIN_THRESHOLD) {
                while (tau + 1 <= maxLag && norm[tau + 1] < norm[tau]) {
                    tau++;
                }
                best = tau;
                break;
            }
        }
        if (best < 0) {
            // 没有可信周期：取全局最小，但仍然把置信度如实报出来（调用方据此决定修不修）
            double bestVal = Double.MAX_VALUE;
            for (int tau = minLag; tau <= maxLag; tau++) {
                if (norm[tau] < bestVal) {
                    bestVal = norm[tau];
                    best = tau;
                }
            }
            clarity = Math.max(0.0, Math.min(1.0, 1.0 - bestVal));
            // 置信度不够就不要报一个「音高」出来（宁可不修，也不要把气声推走）
            if (clarity < 0.5 && best >= 0) {
                hz = 0.0;
                return;
            }
        } else {
            clarity = Math.max(0.0, Math.min(1.0, 1.0 - norm[best]));
        }
        if (best <= 0) {
            hz = 0.0;
            return;
        }
        // ④ 抛物线插值：不做的话在 12 kHz 上采样点量化到 60 Hz 处就是 ±40 音分
        double y0 = best > minLag ? norm[best - 1] : norm[best];
        double y1 = norm[best];
        double y2 = best < maxLag ? norm[best + 1] : norm[best];
        double denom = y0 - 2 * y1 + y2;
        double delta = Math.abs(denom) < 1e-12 ? 0.0 : 0.5 * (y0 - y2) / denom;
        if (delta > 1.0) {
            delta = 1.0;
        } else if (delta < -1.0) {
            delta = -1.0;
        }
        double tau = best + delta;
        // ⑤ 精修：在一倍过采样的历史重算周期附近，把滞后分辨率翻倍（见 historyFine 的注释）
        double fineTau = refineTau(tau);
        hz = fineTau > 0.0 ? (2.0 * workRate) / fineTau : 0.0;
    }

    /**
     * 在 2 倍插值的历史上精修滞后，返回<strong>细 lag</strong>（= 2 × 粗 lag 的刻度）。
     *
     * @param coarseTau 粗估计（粗 lag 刻度）
     */
    private double refineTau(double coarseTau) {
        int n = windowFrames;
        // 线性插值出 2 倍过采样：x2[2i] = x[i]，x2[2i+1] = (x[i] + x[i+1]) / 2
        for (int i = 0; i < n - 1; i++) {
            float a = history[i];
            float b = history[i + 1];
            historyFine[2 * i] = a;
            historyFine[2 * i + 1] = 0.5f * (a + b);
        }
        historyFine[2 * n - 2] = history[n - 1];
        historyFine[2 * n - 1] = history[n - 1];

        int center = (int) Math.round(coarseTau * 2.0);
        int lo = Math.max(2, center - 4);
        int hi = Math.min(2 * maxLag, center + 4);
        int bestFine = center;
        double bestVal = Double.MAX_VALUE;
        for (int tau = lo; tau <= hi; tau++) {
            double sum = 0.0;
            for (int i = 0; i + tau < 2 * n; i++) {
                double d = historyFine[i] - historyFine[i + tau];
                sum += d * d;
            }
            fineDiff[tau - lo] = sum;
            if (sum < bestVal) {
                bestVal = sum;
                bestFine = tau;
            }
        }
        if (bestFine <= lo || bestFine >= hi) {
            return 2.0 * coarseTau;              // 精修窗口贴边：退回粗估计
        }
        double y0 = fineDiff[bestFine - 1 - lo];
        double y1 = fineDiff[bestFine - lo];
        double y2 = fineDiff[bestFine + 1 - lo];
        double denom = y0 - 2 * y1 + y2;
        double delta = Math.abs(denom) < 1e-12 ? 0.0 : 0.5 * (y0 - y2) / denom;
        if (delta > 1.0) {
            delta = 1.0;
        } else if (delta < -1.0) {
            delta = -1.0;
        }
        return bestFine + delta;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "PitchDetector{%d Hz 输入 → %d Hz 分析，窗 %.0f ms / 跳 %.0f ms，范围 %.0f–%.0f Hz}",
                Integer.valueOf(rate), Integer.valueOf(workRate), Double.valueOf(WINDOW_SECS * 1000),
                Double.valueOf(HOP_SECS * 1000), Double.valueOf(MIN_HZ), Double.valueOf(MAX_HZ));
    }
}
