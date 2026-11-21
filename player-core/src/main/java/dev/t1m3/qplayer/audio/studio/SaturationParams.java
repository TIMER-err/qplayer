package dev.t1m3.qplayer.audio.studio;

/**
 * 染色（饱和/激励）的参数：<strong>波形整形 + 驱动 + 干湿 + 2×/4× 过采样 + 直流阻断</strong>。
 *
 * <h2>为什么必须有过采样</h2>
 *
 * <p>波形整形（tanh、软削波）是<strong>非线性</strong>的：它会产生输入里没有的高次谐波。
 * 数字域里超过奈奎斯特的那些谐波不会消失，而是<strong>折叠</strong>回可听频段
 * （混叠）——听感是「脏、发沙」，而且折叠出来的东西与音乐毫无关系。
 * 过采样把「整形」放在 2×/4× 的域里做，谐波落在更高的奈奎斯特之下，再低通降回来时
 * 被滤掉。代价是算力与一点复杂度，这里如实做成三档（关 / 2× / 4×）。
 *
 * <h2>为什么必须直流阻断</h2>
 *
 * <p>非对称的整形（例如只削上半周）会产生直流偏移，跟着信号一路下去会吃掉动态余量、
 * 在扬声器上还不该有。阻断器是一个一阶高通（10 Hz 以下）。
 */
public final class SaturationParams {

    /** 整形曲线。 */
    public enum Curve {
        /** 双曲正切：最经典的「磁带/电子管」味，奇次谐波为主。 */
        TANH("tanh 软饱和"),
        /** 软削波（三次多项式，拐点处斜率连续）：更「硬」一点，谐波更多。 */
        SOFT_CLIP("软削波"),
        /** 折线（模拟二极管削波）：奇偶次都有，最脏。 */
        DIODE("折线（二极管）");

        public final String label;

        Curve(String label) {
            this.label = label;
        }

        public static Curve fromIndex(int index) {
            Curve[] all = values();
            return index >= 0 && index < all.length ? all[index] : TANH;
        }

        public static String[] labels() {
            Curve[] all = values();
            String[] out = new String[all.length];
            for (int i = 0; i < all.length; i++) {
                out[i] = all[i].label;
            }
            return out;
        }
    }

    public final boolean enabled;
    public final Curve curve;
    /** 驱动（dB，0..24）：进整形器之前的增益。 */
    public final double driveDb;
    /** 输出补偿（dB，−24..0）：整形之后拉回来，避免「一开就变大」。 */
    public final double makeupDb;
    /** 干湿（0..1）。 */
    public final double mix;
    /** 过采样倍数：1（关）/ 2 / 4。 */
    public final int oversample;

    private SaturationParams(boolean enabled, Curve curve, double driveDb, double makeupDb,
                             double mix, int oversample) {
        this.enabled = enabled;
        this.curve = curve == null ? Curve.TANH : curve;
        this.driveDb = clamp(driveDb, 0.0, 24.0);
        this.makeupDb = clamp(makeupDb, -24.0, 0.0);
        this.mix = clamp(mix, 0.0, 1.0);
        this.oversample = oversample >= 4 ? 4 : (oversample >= 2 ? 2 : 1);
    }

    public static SaturationParams off() {
        return new SaturationParams(false, Curve.TANH, 6.0, -6.0, 1.0, 2);
    }

    public static SaturationParams of(Curve curve, double driveDb, double makeupDb, double mix,
                                      int oversample) {
        return new SaturationParams(true, curve, driveDb, makeupDb, mix, oversample);
    }

    public SaturationParams withEnabled(boolean value) {
        return new SaturationParams(value, curve, driveDb, makeupDb, mix, oversample);
    }

    public SaturationParams withCurve(Curve value) {
        return new SaturationParams(enabled, value, driveDb, makeupDb, mix, oversample);
    }

    public SaturationParams withDriveDb(double value) {
        return new SaturationParams(enabled, curve, value, makeupDb, mix, oversample);
    }

    public SaturationParams withMakeupDb(double value) {
        return new SaturationParams(enabled, curve, driveDb, value, mix, oversample);
    }

    public SaturationParams withMix(double value) {
        return new SaturationParams(enabled, curve, driveDb, makeupDb, value, oversample);
    }

    public SaturationParams withOversample(int value) {
        return new SaturationParams(enabled, curve, driveDb, makeupDb, mix, value);
    }

    /** 会不会改采样（关掉或全干 → 不会）。 */
    public boolean active() {
        return enabled && mix > 1e-9 && driveDb > 1e-9;
    }

    /** 驱动（线性）。 */
    public double driveLinear() {
        return Math.pow(10.0, driveDb / 20.0);
    }

    /** 输出补偿（线性）。 */
    public double makeupLinear() {
        return Math.pow(10.0, makeupDb / 20.0);
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
                "SaturationParams{%s %s、驱动 %.1f dB、补偿 %.1f dB、干湿 %.2f、过采样 %d×}",
                enabled ? "开" : "关", curve.label, Double.valueOf(driveDb),
                Double.valueOf(makeupDb), Double.valueOf(mix), Integer.valueOf(oversample));
    }
}
