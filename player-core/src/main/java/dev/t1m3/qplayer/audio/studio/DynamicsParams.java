package dev.t1m3.qplayer.audio.studio;

/**
 * 动态处理的参数（不可变）：<strong>门 → 压缩 → 限制</strong>三台各自独立开关。
 *
 * <h2>为什么三台都要（而且顺序不能换）</h2>
 *
 * <ol>
 *   <li><b>门 / 扩展器</b>在最前：先把底噪与房间噪声关门（阈值以下按比率衰减），
 *       否则压缩器会把噪声一起抬上来——这是「工作站级」与「一个压缩器」的区别；</li>
 *   <li><b>压缩器</b>在中间：把动态范围收窄（阈值以上按比率压），软的拐点让它不显形；</li>
 *   <li><b>限制器</b>在最后：它是<strong>安全网</strong>（天花板），保证输出不超 0 dBFS。
 *       它带前视（2 ms），所以是这条链上第二个引入延迟的模块——现在如实上报
 *       （见 {@link DynamicsModule#latencyFrames()} 与 docs/audio-studio.md §4）。</li>
 * </ol>
 *
 * <h2>参数按「用户旋钮」给，不按「实现细节」给</h2>
 *
 * <p>阈值用 dBFS、比率用 {@code n:1}、时间用毫秒、补偿增益用 dB——与硬件面板同一个口径。
 * 内部换算（线性阈值、时间常数 α）只发生在换参数那一刻，泵线程只读算好的值。
 */
public final class DynamicsParams {

    /** 压缩比上限。 */
    public static final double MAX_RATIO = 20.0;

    /** 时间常数范围（毫秒）。 */
    public static final double MIN_TIME_MS = 0.1;
    public static final double MAX_TIME_MS = 2000.0;

    public final boolean gateOn;
    /** 门限（dBFS，−80..0）：低于它就衰减。 */
    public final double gateThresholdDb;
    /** 门限以下的衰减上限（dB，0..−80）：0 = 不衰减（等于关），−80 = 近似静音。 */
    public final double gateRangeDb;
    /** 门的保持时间（毫秒）：避免一有停顿就把尾巴切掉。 */
    public final double gateHoldMs;

    public final boolean compOn;
    /** 压缩阈值（dBFS）。 */
    public final double compThresholdDb;
    /** 压缩比（1..20）。 */
    public final double compRatio;
    /** 软拐点宽度（dB，0 = 硬拐点）。 */
    public final double compKneeDb;
    /** 启动（毫秒）。 */
    public final double compAttackMs;
    /** 释放（毫秒）。 */
    public final double compReleaseMs;
    /** 补偿增益（dB）。 */
    public final double compMakeupDb;
    /** 用 RMS 检波（false = 峰值检波）。 */
    public final boolean compRms;
    /** RMS 检波的时间常数（毫秒）。 */
    public final double compRmsWindowMs;

    public final boolean limiterOn;
    /** 输出天花板（dBFS，−12..0）。 */
    public final double limiterCeilingDb;
    /** 前视（毫秒，0..5）：它不是「更聪明」，而是让限制器提前开始压、少一点失真。 */
    public final double limiterLookaheadMs;
    /** 限制器的释放（毫秒）。 */
    public final double limiterReleaseMs;

    private DynamicsParams(boolean gateOn, double gateThresholdDb, double gateRangeDb,
                           double gateHoldMs, boolean compOn, double compThresholdDb,
                           double compRatio, double compKneeDb, double compAttackMs,
                           double compReleaseMs, double compMakeupDb, boolean compRms,
                           double compRmsWindowMs, boolean limiterOn, double limiterCeilingDb,
                           double limiterLookaheadMs, double limiterReleaseMs) {
        this.gateOn = gateOn;
        this.gateThresholdDb = clamp(gateThresholdDb, -80.0, 0.0);
        this.gateRangeDb = clamp(gateRangeDb, -80.0, 0.0);
        this.gateHoldMs = clamp(gateHoldMs, 0.0, MAX_TIME_MS);
        this.compOn = compOn;
        this.compThresholdDb = clamp(compThresholdDb, -60.0, 0.0);
        this.compRatio = clamp(compRatio, 1.0, MAX_RATIO);
        this.compKneeDb = clamp(compKneeDb, 0.0, 24.0);
        this.compAttackMs = clamp(compAttackMs, MIN_TIME_MS, MAX_TIME_MS);
        this.compReleaseMs = clamp(compReleaseMs, MIN_TIME_MS, MAX_TIME_MS);
        this.compMakeupDb = clamp(compMakeupDb, 0.0, 24.0);
        this.compRms = compRms;
        this.compRmsWindowMs = clamp(compRmsWindowMs, 1.0, 200.0);
        this.limiterOn = limiterOn;
        this.limiterCeilingDb = clamp(limiterCeilingDb, -12.0, 0.0);
        this.limiterLookaheadMs = clamp(limiterLookaheadMs, 0.0, 5.0);
        this.limiterReleaseMs = clamp(limiterReleaseMs, MIN_TIME_MS, MAX_TIME_MS);
    }

    /** 三台全关（直通）。 */
    public static DynamicsParams off() {
        return new DynamicsParams(false, -40.0, -40.0, 50.0, false, -18.0, 3.0, 6.0, 10.0,
                120.0, 0.0, false, 20.0, false, -1.0, 2.0, 80.0);
    }

    public static Builder builder() {
        DynamicsParams base = off();
        return new Builder(base);
    }

    /** 从当前参数出发改几项（不可变 + builder，与 {@link EqBand} 同一套风格）。 */
    public static final class Builder {

        private boolean gateOn;
        private double gateThresholdDb;
        private double gateRangeDb;
        private double gateHoldMs;
        private boolean compOn;
        private double compThresholdDb;
        private double compRatio;
        private double compKneeDb;
        private double compAttackMs;
        private double compReleaseMs;
        private double compMakeupDb;
        private boolean compRms;
        private double compRmsWindowMs;
        private boolean limiterOn;
        private double limiterCeilingDb;
        private double limiterLookaheadMs;
        private double limiterReleaseMs;

        private Builder(DynamicsParams base) {
            gateOn = base.gateOn;
            gateThresholdDb = base.gateThresholdDb;
            gateRangeDb = base.gateRangeDb;
            gateHoldMs = base.gateHoldMs;
            compOn = base.compOn;
            compThresholdDb = base.compThresholdDb;
            compRatio = base.compRatio;
            compKneeDb = base.compKneeDb;
            compAttackMs = base.compAttackMs;
            compReleaseMs = base.compReleaseMs;
            compMakeupDb = base.compMakeupDb;
            compRms = base.compRms;
            compRmsWindowMs = base.compRmsWindowMs;
            limiterOn = base.limiterOn;
            limiterCeilingDb = base.limiterCeilingDb;
            limiterLookaheadMs = base.limiterLookaheadMs;
            limiterReleaseMs = base.limiterReleaseMs;
        }

        public Builder gate(boolean on, double thresholdDb, double rangeDb, double holdMs) {
            this.gateOn = on;
            this.gateThresholdDb = thresholdDb;
            this.gateRangeDb = rangeDb;
            this.gateHoldMs = holdMs;
            return this;
        }

        public Builder compressor(boolean on, double thresholdDb, double ratio, double kneeDb,
                                  double attackMs, double releaseMs, double makeupDb) {
            this.compOn = on;
            this.compThresholdDb = thresholdDb;
            this.compRatio = ratio;
            this.compKneeDb = kneeDb;
            this.compAttackMs = attackMs;
            this.compReleaseMs = releaseMs;
            this.compMakeupDb = makeupDb;
            return this;
        }

        public Builder rmsDetector(boolean on, double windowMs) {
            this.compRms = on;
            this.compRmsWindowMs = windowMs;
            return this;
        }

        public Builder limiter(boolean on, double ceilingDb, double lookaheadMs, double releaseMs) {
            this.limiterOn = on;
            this.limiterCeilingDb = ceilingDb;
            this.limiterLookaheadMs = lookaheadMs;
            this.limiterReleaseMs = releaseMs;
            return this;
        }

        public DynamicsParams build() {
            return new DynamicsParams(gateOn, gateThresholdDb, gateRangeDb, gateHoldMs, compOn,
                    compThresholdDb, compRatio, compKneeDb, compAttackMs, compReleaseMs,
                    compMakeupDb, compRms, compRmsWindowMs, limiterOn, limiterCeilingDb,
                    limiterLookaheadMs, limiterReleaseMs);
        }
    }

    /** 有没有任何一台在工作（闸门用）。 */
    public boolean active() {
        return gateOn && gateRangeDb < -1e-6 || compOn || limiterOn;
    }

    /** 限制器的前视（采样数 @ 给定采样率）。 */
    public int lookaheadFrames(int rate) {
        return limiterOn ? (int) Math.round(limiterLookaheadMs / 1000.0 * Math.max(1, rate)) : 0;
    }

    private static double clamp(double v, double min, double max) {
        if (Double.isNaN(v)) {
            return min;
        }
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "DynamicsParams{门 %s %.0f dB/%.0f dB；压缩 %s %.1f dB %.1f:1 拐点 %.1f dB "
                        + "%.0f/%.0f ms 补偿 %.1f dB %s；限制 %s %.1f dB 前视 %.1f ms}",
                gateOn ? "开" : "关", Double.valueOf(gateThresholdDb), Double.valueOf(gateRangeDb),
                compOn ? "开" : "关", Double.valueOf(compThresholdDb), Double.valueOf(compRatio),
                Double.valueOf(compKneeDb), Double.valueOf(compAttackMs),
                Double.valueOf(compReleaseMs), Double.valueOf(compMakeupDb),
                compRms ? "RMS" : "峰值", limiterOn ? "开" : "关",
                Double.valueOf(limiterCeilingDb), Double.valueOf(limiterLookaheadMs));
    }
}
