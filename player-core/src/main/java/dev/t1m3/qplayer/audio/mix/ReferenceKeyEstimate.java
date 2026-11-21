package dev.t1m3.qplayer.audio.mix;

/**
 * <b>从 12 维 chroma 估绝对调性</b>（参考包把绝对调性当输入，我们需要自己算出来）。
 *
 * <p>做法是经典的 Krumhansl-Kessler 调性轮廓相关法：
 * 把一段 chroma 均值与 24 个（12 主音 × 大小调）轮廓各做一次相关，取最高分；
 * 置信度用<strong>第一名与第二名的相对间隔</strong>（间隔越大越确定）——
 * 参考包的 {@code key_confidence ≥ 0.3} 那条闸门吃的就是这个量。
 *
 * <p>约定：chroma 下标 0 = C（与 {@code Harmonic.PITCH_CLASSES = 12} 的对齐方式一致）。
 */
public final class ReferenceKeyEstimate {

    private ReferenceKeyEstimate() {
    }

    /** Krumhansl-Kessler 大调轮廓。 */
    static final double[] MAJOR_PROFILE = {
            6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    /** Krumhansl-Kessler 小调轮廓。 */
    static final double[] MINOR_PROFILE = {
            6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

    /** 估出来的调性 + 置信度。 */
    public static final class Estimate {
        public final int tonic;
        public final String mode;
        public final double confidence;
        public final double best;
        public final double runnerUp;

        Estimate(int tonic, String mode, double confidence, double best, double runnerUp) {
            this.tonic = tonic;
            this.mode = mode;
            this.confidence = confidence;
            this.best = best;
            this.runnerUp = runnerUp;
        }
    }

    /** 把逐帧 chroma 平均成一个 12 维向量（帧长 = 12，按 region 截取）。 */
    public static double[] meanChroma(float[] chroma, int frames, int fromFrame, int toFrame) {
        double[] out = new double[12];
        if (chroma == null || frames <= 0) {
            return out;
        }
        int from = Math.max(0, Math.min(frames - 1, fromFrame));
        int to = Math.max(from + 1, Math.min(frames, toFrame));
        for (int f = from; f < to; f++) {
            int base = f * 12;
            if (base + 12 > chroma.length) {
                break;
            }
            for (int i = 0; i < 12; i++) {
                out[i] += chroma[base + i];
            }
        }
        double sum = 0.0;
        for (int i = 0; i < 12; i++) {
            sum += out[i];
        }
        if (sum > 1e-9) {
            for (int i = 0; i < 12; i++) {
                out[i] /= sum;
            }
        }
        return out;
    }

    /** 对 12 维 chroma 估调性。 */
    public static Estimate estimate(double[] chroma) {
        double mean = 0.0;
        for (int i = 0; i < 12; i++) {
            mean += chroma[i];
        }
        mean /= 12.0;
        double best = Double.NEGATIVE_INFINITY;
        double second = Double.NEGATIVE_INFINITY;
        int bestTonic = 0;
        String bestMode = "major";
        for (int tonic = 0; tonic < 12; tonic++) {
            double major = correlate(chroma, mean, tonic, MAJOR_PROFILE);
            double minor = correlate(chroma, mean, tonic, MINOR_PROFILE);
            if (major > best) {
                second = best;
                best = major;
                bestTonic = tonic;
                bestMode = "major";
            } else if (major > second) {
                second = major;
            }
            if (minor > best) {
                second = best;
                best = minor;
                bestTonic = tonic;
                bestMode = "minor";
            } else if (minor > second) {
                second = minor;
            }
        }
        double confidence = best > 1e-9 ? Math.max(0.0, Math.min(1.0, (best - Math.max(0.0, second)) / best)) : 0.0;
        return new Estimate(bestTonic, bestMode, confidence, best, second);
    }

    /** Pearson 相关（轮廓 vs 旋转后的 chroma）。 */
    private static double correlate(double[] chroma, double chromaMean, int tonic, double[] profile) {
        double profileMean = 0.0;
        for (int i = 0; i < 12; i++) {
            profileMean += profile[i];
        }
        profileMean /= 12.0;
        double num = 0.0;
        double denA = 0.0;
        double denB = 0.0;
        for (int i = 0; i < 12; i++) {
            double a = chroma[(tonic + i) % 12] - chromaMean;
            double b = profile[i] - profileMean;
            num += a * b;
            denA += a * a;
            denB += b * b;
        }
        if (denA <= 1e-12 || denB <= 1e-12) {
            return 0.0;
        }
        return num / Math.sqrt(denA * denB);
    }
}
