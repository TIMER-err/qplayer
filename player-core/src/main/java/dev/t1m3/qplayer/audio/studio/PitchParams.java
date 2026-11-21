package dev.t1m3.qplayer.audio.studio;

/**
 * 音高模块的参数（不可变）。
 *
 * <p>两个旋钮分开是刻意的：<b>半音</b>用来改调（「这首降两个半音唱」），
 * <b>音分</b>用来做精细对齐（分轨之间差 8 音分这种事，只有音分看得见）。
 * 两者相加才是最终音高比：{@code 2^((semitone + cents/100)/12)}，
 * 所以范围是 ±12 半音 ±100 音分 = **±13 半音**，别按 ±12 去夹。
 */
public final class PitchParams {

    /** 半音上限（不含音分那部分）。 */
    public static final int MAX_SEMITONE = 12;

    /** 音分上限。 */
    public static final double MAX_CENTS = 100.0;

    public final boolean enabled;
    public final int semitone;
    public final double cents;
    /** 干湿：0 = 原样（逐位直通），1 = 全湿。 */
    public final double mix;

    private PitchParams(boolean enabled, int semitone, double cents, double mix) {
        this.enabled = enabled;
        this.semitone = (int) Math.max(-MAX_SEMITONE, Math.min(MAX_SEMITONE,
                Double.isNaN(semitone) ? 0 : semitone));
        double c = Double.isNaN(cents) ? 0.0 : cents;
        this.cents = Math.max(-MAX_CENTS, Math.min(MAX_CENTS, c));
        double m = Double.isNaN(mix) ? 1.0 : mix;
        this.mix = Math.max(0.0, Math.min(1.0, m));
    }

    /** 关。 */
    public static PitchParams off() {
        return new PitchParams(false, 0, 0.0, 1.0);
    }

    public static PitchParams of(int semitone, double cents, double mix) {
        return new PitchParams(true, semitone, cents, mix);
    }

    /** 常用：只给半音。 */
    public static PitchParams semitones(int semitone) {
        return new PitchParams(true, semitone, 0.0, 1.0);
    }

    public PitchParams withEnabled(boolean value) {
        return new PitchParams(value, semitone, cents, mix);
    }

    public PitchParams withSemitone(int value) {
        return new PitchParams(enabled, value, cents, mix);
    }

    public PitchParams withCents(double value) {
        return new PitchParams(enabled, semitone, value, mix);
    }

    public PitchParams withMix(double value) {
        return new PitchParams(enabled, semitone, cents, value);
    }

    /** 最终音高（半音，含音分）。 */
    public double totalSemitones() {
        return semitone + cents / 100.0;
    }

    /** 这个参数会不会改采样（关掉、或全干、或音高正好是 0 → 不会）。 */
    public boolean active() {
        if (!enabled || mix <= 1e-9) {
            return false;
        }
        return Math.abs(totalSemitones()) > 1e-9;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "PitchParams{%s %+d 半音 %+.1f 音分, 干湿 %.2f}",
                enabled ? "开" : "关", Integer.valueOf(semitone), Double.valueOf(cents),
                Double.valueOf(mix));
    }
}
