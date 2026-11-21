package dev.t1m3.qplayer.audio.mix;

/**
 * <b>参考包的分数核心</b>（scoring.py 的 {@code score_core}，逐字对齐）。
 *
 * <pre>
 *   product = base_score × Π(factors)
 *   score   = product + 0.001 × boundary_delta   （仅当 product &gt; 0 才加位置项）
 * </pre>
 *
 * <p>注意这不是加权和：任何一项因子为 0，整段分数就是 0（参考包就是这样"一票否决"的）。
 * {@code boundary_delta} = 出场区上界 − 进场区上界（按歌曲时间，可为负）；
 * 系数默认 0.001（{@code score_core} 的默认实参）。
 */
public final class ReferenceScoring {

    private ReferenceScoring() {
    }

    public static final double BOUNDARY_COEFFICIENT = 0.001;

    public static double scoreCore(double base, double[] factors, double boundaryDelta) {
        return scoreCore(base, factors, boundaryDelta, BOUNDARY_COEFFICIENT);
    }

    public static double scoreCore(double base, double[] factors, double boundaryDelta,
                                   double coefficient) {
        if (!(base >= 0.0)) {
            throw new IllegalArgumentException("base must be nonnegative");
        }
        if (!(coefficient >= 0.0)) {
            throw new IllegalArgumentException("coefficient must be nonnegative");
        }
        double product = base;
        if (factors != null) {
            for (int i = 0; i < factors.length; i++) {
                double v = factors[i];
                if (!(v >= 0.0)) {
                    throw new IllegalArgumentException("factor must be nonnegative");
                }
                product *= v;
            }
        }
        return product > 0.0 ? product + coefficient * boundaryDelta : product;
    }
}
