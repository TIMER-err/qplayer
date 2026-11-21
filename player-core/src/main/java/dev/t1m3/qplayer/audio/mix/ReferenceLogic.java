package dev.t1m3.qplayer.audio.mix;

/**
 * <b>recovered_logic.py 的端口</b>：tempo 关系与调性关系（逐字对齐）。
 *
 * <p>这两条是参考包选点里最先决定"能不能搭"的两把尺子：
 * <ul>
 *   <li>{@code tempo_relationship}：在 ln(BPM) 上找最近的 **半/一/两倍** alias，
 *       距离阈值 0.16；alias 平手时按 (0.5, 1.0, 2.0) 的顺序取第一个；</li>
 *   <li>{@code tonality_relationship}：identical / relative / neighbouring / incompatible，
 *       走五度圈（大调与小调各一张表）；neutral 只有同根才算 identical。</li>
 * </ul>
 */
public final class ReferenceLogic {

    private ReferenceLogic() {
    }

    /** 小调五度圈（recovered_logic.MINOR_CIRCLE）。 */
    static final int[] MINOR_CIRCLE = {9, 4, 11, 6, 1, 8, 3, 10, 5, 0, 7, 2};
    /** 大调五度圈（recovered_logic.MAJOR_CIRCLE）。 */
    static final int[] MAJOR_CIRCLE = {0, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10, 5};

    /** tempo 关系的结果。 */
    public static final class TempoRelation {
        public final double alias;
        public final double effectiveIncomingBpm;
        public final double logDistance;
        public final String relationship;   // identical / similar / incompatible
        public final boolean compatible;

        TempoRelation(double alias, double effectiveIncomingBpm, double logDistance,
                      String relationship, boolean compatible) {
            this.alias = alias;
            this.effectiveIncomingBpm = effectiveIncomingBpm;
            this.logDistance = logDistance;
            this.relationship = relationship;
            this.compatible = compatible;
        }
    }

    /**
     * {@code tempo_relationship(outgoing, incoming, threshold=0.16)}。
     *
     * <p>alias 从 (0.5, 1.0, 2.0) 里按"ln 距离最小"挑，**平手保留靠前的那个**
     * （参考包用 {@code min(key=...)} 的语义）。
     */
    public static TempoRelation tempoRelationship(double outgoing, double incoming) {
        return tempoRelationship(outgoing, incoming, 0.16);
    }

    public static TempoRelation tempoRelationship(double outgoing, double incoming, double threshold) {
        if (!(outgoing > 0.0) || !(incoming > 0.0)) {
            throw new IllegalArgumentException("Positive local BPM values are required");
        }
        double[] candidates = {0.5, 1.0, 2.0};
        double bestAlias = candidates[0];
        double bestKey = Math.abs(Math.log(incoming * candidates[0]) - Math.log(outgoing));
        for (int i = 1; i < candidates.length; i++) {
            double key = Math.abs(Math.log(incoming * candidates[i]) - Math.log(outgoing));
            if (key < bestKey) {
                bestKey = key;
                bestAlias = candidates[i];
            }
        }
        double adjusted = incoming * bestAlias;
        double distance = Math.abs(Math.log(adjusted) - Math.log(outgoing));
        String relation = adjusted == outgoing ? "identical"
                : (distance <= threshold ? "similar" : "incompatible");
        return new TempoRelation(bestAlias, adjusted, distance, relation,
                !"incompatible".equals(relation));
    }

    /** 调性（tonic 0..11，C=0；mode major/minor/neutral）。 */
    public static final class Key {
        public final int tonic;
        public final String mode;

        public Key(int tonic, String mode) {
            this.tonic = tonic;
            this.mode = mode;
        }
    }

    /** {@code tonality_relationship(a, b)}：返回 identical / relative / neighbouring / incompatible / unknown。 */
    public static String tonalityRelationship(Key outgoing, Key incoming) {
        if (outgoing == null || incoming == null) {
            return "unknown";
        }
        if (outgoing.tonic == incoming.tonic && outgoing.mode.equals(incoming.mode)) {
            return "identical";
        }
        boolean majorA = "major".equals(outgoing.mode);
        boolean minorA = "minor".equals(outgoing.mode);
        boolean majorB = "major".equals(incoming.mode);
        boolean minorB = "minor".equals(incoming.mode);
        if (!(majorA || minorA) || !(majorB || minorB)) {
            return "incompatible";
        }
        int[] circleA = majorA ? MAJOR_CIRCLE : MINOR_CIRCLE;
        int[] circleB = majorB ? MAJOR_CIRCLE : MINOR_CIRCLE;
        int a = indexOf(circleA, outgoing.tonic);
        int b = indexOf(circleB, incoming.tonic);
        if (!majorA == !majorB) {
            // 同一个 mode（同大或同小）
            int diff = ((a - b) % 12 + 12) % 12;
            return (diff == 1 || diff == 11) ? "neighbouring" : "incompatible";
        }
        return a == b ? "relative" : "incompatible";
    }

    private static int indexOf(int[] arr, int value) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == value) {
                return i;
            }
        }
        throw new IllegalArgumentException("tonic out of range: " + value);
    }
}
