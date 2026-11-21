package dev.t1m3.qplayer.audio.studio;


import java.util.List;

/**
 * 均衡链的<strong>解析频响</strong>：把各段的传递函数在 z 平面上乘起来。
 *
 * <p>两处用它，而且都用同一份实现：
 *
 * <ul>
 *   <li>界面画曲线（用户拖的是参数，看到的是这条曲线）；</li>
 *   <li>测试与解析式对比（{@code EqResponseTest}：处理器实测的频响必须与这里算出来的
 *       逐点吻合，±0.1 dB 以内）。</li>
 * </ul>
 *
 * <p>都是离线路径（每次调用都会分配），<strong>不要</strong>放进音频线程。
 */
public final class EqResponse {

    private EqResponse() {
    }

    /**
     * 各段的合成增益（dB）。
     *
     * @param bands 段表（顺序无关：线性系统的乘积可交换）
     * @param rate  采样率
     * @param freqs 要算的频率（Hz）
     * @param extraDb 额外叠加的常数增益（例如前级），可为 0
     */
    public static double[] db(List<EqBand> bands, int rate, double[] freqs, double extraDb) {
        double[] out = new double[freqs.length];
        for (int i = 0; i < freqs.length; i++) {
            double w = 2.0 * Math.PI * freqs[i] / Math.max(rate, 1);
            double re = 1.0;
            double im = 0.0;
            for (EqBand band : bands == null ? java.util.Collections.<EqBand>emptyList() : bands) {
                if (band == null || band.isTransparent()) {
                    continue;
                }
                double[] qs = qsFor(band, band.stages());
                for (double q : qs) {
                    double[] c = design(rate, band, q).coeffs();
                    double[] h = transfer(c, w);
                    double nre = re * h[0] - im * h[1];
                    double nim = re * h[1] + im * h[0];
                    re = nre;
                    im = nim;
                }
            }
            double mag = Math.sqrt(re * re + im * im);
            out[i] = 20.0 * Math.log10(Math.max(mag, 1e-12)) + extraDb;
        }
        return out;
    }

    /** 单点版本。 */
    public static double dbAt(List<EqBand> bands, int rate, double freqHz) {
        return db(bands, rate, new double[]{freqHz}, 0.0)[0];
    }

    /**
     * 从 20 Hz 到 20 kHz 的对数等分频率点（画曲线与测试都用它）。
     *
     * @param points 点数（含首尾）
     */
    public static double[] logGrid(int points) {
        int n = Math.max(points, 2);
        double lo = Math.log10(EqBand.MIN_FREQ);
        double hi = Math.log10(EqBand.MAX_FREQ);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = Math.pow(10.0, lo + (hi - lo) * i / (n - 1));
        }
        return out;
    }

    /** {@code H(e^{jw})}：{@code {实部, 虚部}}。 */
    private static double[] transfer(double[] c, double w) {
        double b0 = c[0];
        double b1 = c[1];
        double b2 = c[2];
        double a1 = c[3];
        double a2 = c[4];
        double cw = Math.cos(w);
        double sw = Math.sin(w);
        double c2 = Math.cos(2 * w);
        double s2 = Math.sin(2 * w);
        double nre = b0 + b1 * cw + b2 * c2;
        double nim = -(b1 * sw + b2 * s2);
        double dre = 1.0 + a1 * cw + a2 * c2;
        double dim = -(a1 * sw + a2 * s2);
        double den = dre * dre + dim * dim;
        return new double[]{(nre * dre + nim * dim) / den, (nim * dre - nre * dim) / den};
    }

    /** 与处理器同一条约定：只有高/低通用级联的固定 Q，其余用段自己的 Q。 */
    private static double[] qsFor(EqBand band, int stages) {
        if (!band.type.usesSlope()) {
            return new double[]{band.q};
        }
        if (stages >= 4) {
            return new double[]{0.509795579104159, 0.601344886935045,
                    0.899976223136416, 2.562915447741505};
        }
        return stages == 2
                ? new double[]{0.541196100146197, 1.306562964876377}
                : new double[]{0.7071067811865476};
    }

    private static StudioBiquad design(int rate, EqBand band, double q) {
        double f = band.freqHz;
        double qq = q;
        double g = band.gainDb;
        switch (band.type) {
            case LOW_SHELF:
                return StudioBiquad.lowShelf(rate, f, qq, g);
            case HIGH_SHELF:
                return StudioBiquad.highShelf(rate, f, qq, g);
            case HIGH_PASS:
                return StudioBiquad.highPass(rate, f, qq);
            case LOW_PASS:
                return StudioBiquad.lowPass(rate, f, qq);
            case BAND_PASS:
                return StudioBiquad.bandPass(rate, f, qq);
            case NOTCH:
                return StudioBiquad.notch(rate, f, qq);
            case ALL_PASS:
                return StudioBiquad.allPass(rate, f, qq);
            case PEAK:
            default:
                return StudioBiquad.peaking(rate, f, qq, g);
        }
    }
}
