package dev.t1m3.qplayer.audio.studio;

/**
 * 一段均衡的参数（不可变）。
 *
 * <p>为什么不可变：泵线程（生产者）随时在读它，界面/命令线程随时在建新的。
 * 不可变 + 整体替换（{@code volatile} 引用）是这里唯一不需要锁的写法
 * （与 {@code SpectrumTap} 的发布/读取模型同一条理由）。
 *
 * <p>取值一律<strong>在构造时就夹住</strong>（见 settle）：界面滑杆、预设文件、命令行
 * 三条路都能把参数送进来，任何一条路送出 0 Hz 或 Q = 1e9 都不该让滤波器炸掉或静音。
 */
public final class EqBand {

    /** 频率上下限（Hz）：20 Hz–20 kHz 之外对听感没意义，而且容易把次声/超声灌进扬声器。 */
    public static final double MIN_FREQ = 20.0;
    public static final double MAX_FREQ = 20000.0;

    /** 增益上下限（dB）。±24 是工作站 EQ 的常见范围。 */
    public static final double MIN_GAIN = -24.0;
    public static final double MAX_GAIN = 24.0;

    /** Q 上下限：1/18 很宽（一个八度多），18 很窄（去一根啸叫）。 */
    public static final double MIN_Q = 0.1;
    public static final double MAX_Q = 18.0;

    /** 允许的斜率（dB/oct），用级联实现。 */
    public static final int[] SLOPES = {12, 24, 48};

    public final BandType type;
    public final double freqHz;
    public final double gainDb;
    public final double q;
    /** 高/低通的斜率（dB/oct）；其余类型忽略。 */
    public final int slopeDbPerOct;
    public final ChannelTarget channel;
    public final boolean enabled;

    private EqBand(BandType type, double freqHz, double gainDb, double q, int slopeDbPerOct,
                   ChannelTarget channel, boolean enabled) {
        this.type = type == null ? BandType.PEAK : type;
        this.freqHz = settle(freqHz, MIN_FREQ, MAX_FREQ, 1000.0);
        this.gainDb = settle(gainDb, MIN_GAIN, MAX_GAIN, 0.0);
        this.q = settle(q, MIN_Q, MAX_Q, 0.7071);
        this.slopeDbPerOct = settleSlope(slopeDbPerOct);
        this.channel = channel == null ? ChannelTarget.STEREO : channel;
        this.enabled = enabled;
    }

    /** 峰值段（最常用）。 */
    public static EqBand peak(double freqHz, double gainDb, double q) {
        return new EqBand(BandType.PEAK, freqHz, gainDb, q, 12, ChannelTarget.STEREO, true);
    }

    public static EqBand of(BandType type, double freqHz, double gainDb, double q) {
        return new EqBand(type, freqHz, gainDb, q, 12, ChannelTarget.STEREO, true);
    }

    public static EqBand of(BandType type, double freqHz, double gainDb, double q,
                            int slopeDbPerOct, ChannelTarget channel, boolean enabled) {
        return new EqBand(type, freqHz, gainDb, q, slopeDbPerOct, channel, enabled);
    }

    /** 关掉这一段（界面上是那一行的小开关；关掉时处理器里不占任何算力）。 */
    public EqBand withEnabled(boolean value) {
        return new EqBand(type, freqHz, gainDb, q, slopeDbPerOct, channel, value);
    }

    public EqBand withGain(double value) {
        return new EqBand(type, freqHz, value, q, slopeDbPerOct, channel, enabled);
    }

    public EqBand withFreq(double value) {
        return new EqBand(type, value, gainDb, q, slopeDbPerOct, channel, enabled);
    }

    public EqBand withQ(double value) {
        return new EqBand(type, freqHz, gainDb, value, slopeDbPerOct, channel, enabled);
    }

    public EqBand withType(BandType value) {
        return new EqBand(value, freqHz, gainDb, q, slopeDbPerOct, channel, enabled);
    }

    public EqBand withChannel(ChannelTarget value) {
        return new EqBand(type, freqHz, gainDb, q, slopeDbPerOct, value, enabled);
    }

    public EqBand withSlope(int value) {
        return new EqBand(type, freqHz, gainDb, q, value, channel, enabled);
    }

    /** 这一段的系数是不是恒等（不必参与处理）。 */
    public boolean isTransparent() {
        if (!enabled) {
            return true;
        }
        if (type.usesGain() && Math.abs(gainDb) < 1e-6) {
            return true;
        }
        return false;
    }

    /** 级联几级（只对高/低通有意义）。 */
    public int stages() {
        if (!type.usesSlope()) {
            return 1;
        }
        return Math.max(1, slopeDbPerOct / 12);
    }

    private static double settle(double value, double min, double max, double fallback) {
        if (Double.isNaN(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static int settleSlope(int value) {
        for (int s : SLOPES) {
            if (s == value) {
                return s;
            }
        }
        return 12;
    }

    @Override
    public String toString() {
        return type.json() + " " + round(freqHz) + "Hz " + round(gainDb) + "dB Q" + round(q)
                + " " + channel.json() + (enabled ? "" : " (关)");
    }

    private static String round(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(v));
    }
}
