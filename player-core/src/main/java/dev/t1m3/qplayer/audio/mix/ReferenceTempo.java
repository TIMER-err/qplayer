package dev.t1m3.qplayer.audio.mix;

/**
 * <b>参考包 tempo 关系的 Java 端口</b>（scoring.py 的 {@code harmonic_tempo} 与
 * {@code _candidate} 里 {@code stretch_comfort} 的那一行，逐字对齐）。
 *
 * <p>要点：half/double-time 只改**拍的解释**，不按 2 倍速播放 ⇒ 先取 alias 比例，
 * 再在 alias 之后的 BPM 上取**几何平均**作为 target，两侧速率互为倒数。
 */
public final class ReferenceTempo {

    private ReferenceTempo() {
    }

    /** {@code harmonic_tempo} 的结果。 */
    public static final class Relationship {
        public final double beatAlias;
        public final double logDistance;
        public final double targetBpm;
        public final double outgoingRate;
        public final double incomingRate;

        Relationship(double beatAlias, double logDistance, double targetBpm,
                     double outgoingRate, double incomingRate) {
            this.beatAlias = beatAlias;
            this.logDistance = logDistance;
            this.targetBpm = targetBpm;
            this.outgoingRate = outgoingRate;
            this.incomingRate = incomingRate;
        }
    }

    /** 与参考包 {@code harmonic_tempo} 同一算式（alias 由调用方给，见 {@link #harmonicTempo}）。 */
    public static Relationship harmonicTempo(double outBpm, double inBpm, double alias,
                                            double logDistance) {
        double effectiveIn = inBpm * alias;
        double target = Math.sqrt(outBpm * effectiveIn);
        return new Relationship(alias, logDistance, target, target / outBpm, target / effectiveIn);
    }

    /**
     * {@code factors['stretch_comfort'] = exp(-0.5 * (max(|ln out_rate|, |ln in_rate|) / σ)²)}。
     *
     * <p>σ 取 {@link ReferenceProfiles#STRETCH_SIGMA}（0.09）。
     */
    public static double stretchComfort(Relationship tempo, double sigma) {
        double worst = Math.max(Math.abs(Math.log(tempo.outgoingRate)),
                Math.abs(Math.log(tempo.incomingRate)));
        double z = worst / sigma;
        return Math.exp(-0.5 * z * z);
    }
}
