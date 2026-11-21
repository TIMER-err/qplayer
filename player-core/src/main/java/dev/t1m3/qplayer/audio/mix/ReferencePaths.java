package dev.t1m3.qplayer.audio.mix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>candidate_paths.py 的端口</b>：路径级候选评分（{@code evaluate_candidate}）。
 *
 * <p>这是参考包"每条路径各算各的分"的那一层，输入是**已经算好的 signal 布尔/数值**，
 * 所以可以逐位对照（不依赖音频）。要点：
 * <ul>
 *   <li>base 取自 {@code PATH_SPECS}（filtered-direct 按 style 分 20 / 50）；</li>
 *   <li>每条路径有各自的 <b>required 集合</b>：缺（键不存在，或数值为 null）→
 *       {@code missing_upstream_signals}、score = null；</li>
 *   <li>显式 null 的布尔字段**不算缺**，而是按 false 参与因子（参考包就是这样判的）；</li>
 *   <li>因子连乘后 {@code score = product + 0.001·(outgoing_end − incoming_end)}
 *       （仅 product &gt; 0 才加位置项）。</li>
 * </ul>
 */
public final class ReferencePaths {

    private ReferencePaths() {
    }

    /** 一条路径的规格（PATH_SPECS）。 */
    static final class Spec {
        final int[] styleIds;
        final double base;
        final Map<Integer, Double> bases;

        Spec(int[] styleIds, double base, Map<Integer, Double> bases) {
            this.styleIds = styleIds;
            this.base = base;
            this.bases = bases;
        }
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<String, Spec>();

    static {
        SPECS.put("dance-original", new Spec(new int[] {19}, 17.0, null));
        SPECS.put("dance-shifted", new Spec(new int[] {17}, 17.0, null));
        Map<Integer, Double> filtered = new LinkedHashMap<Integer, Double>();
        filtered.put(Integer.valueOf(8), Double.valueOf(20.0));
        filtered.put(Integer.valueOf(9), Double.valueOf(50.0));
        SPECS.put("filtered-direct", new Spec(new int[] {8, 9}, 0.0, filtered));
        SPECS.put("filtered-shifted", new Spec(new int[] {12}, 20.0, null));
        SPECS.put("hiphop-direct", new Spec(new int[] {18}, 100.0, null));
        SPECS.put("hiphop-shifted", new Spec(new int[] {15}, 100.0, null));
        SPECS.put("pop-scaled-shifted", new Spec(new int[] {16, 20}, 50.0, null));
        SPECS.put("smart-crossfade", new Spec(new int[] {3, 33, 4, 44}, 10.0, null));
        SPECS.put("dead-air", new Spec(new int[] {0}, 1.0, null));
        SPECS.put("fallback-crossfade", new Spec(new int[] {1}, 1.0, null));
        SPECS.put("soft-skip", new Spec(new int[] {5}, 10.0, null));
    }

    /** 参考包 evaluate_candidate 的 signals（带"提供了没有"的痕迹）。 */
    public static final class Signals {
        final Set<String> provided = new LinkedHashSet<String>();
        Boolean strictTempoCompatible;
        Boolean looseTempoCompatible;
        Boolean tonalitiesCompatible;
        Integer matchingBarCount;
        Integer outgoingBeatCount;
        Integer incomingBeatCount;
        Double tempoAlias;
        Integer targetBarCount;
        /** 可以是 String（high/veryHigh）或 Integer（3/4）。 */
        Object incomingLeadingStrength;
        Double incomingRegionLoudness;
        Double incomingTrailingLoudness;
        Boolean vocalOverlapIncompatible;
        Integer incomingLoudnessRelationCode;
        Double trailingSpanSeconds;
        Boolean outgoingLoudnessSignificant;

        public Signals strictTempo(Boolean v) {
            strictTempoCompatible = v;
            provided.add("strict_tempo_compatible");
            return this;
        }

        public Signals looseTempo(Boolean v) {
            looseTempoCompatible = v;
            provided.add("loose_tempo_compatible");
            return this;
        }

        public Signals tonalities(Boolean v) {
            tonalitiesCompatible = v;
            provided.add("tonalities_compatible");
            return this;
        }

        public Signals matchingBars(Integer v) {
            matchingBarCount = v;
            provided.add("matching_bar_count");
            return this;
        }

        public Signals outgoingBeats(Integer v) {
            outgoingBeatCount = v;
            provided.add("outgoing_beat_count");
            return this;
        }

        public Signals incomingBeats(Integer v) {
            incomingBeatCount = v;
            provided.add("incoming_beat_count");
            return this;
        }

        public Signals tempoAlias(Double v) {
            tempoAlias = v;
            provided.add("tempo_alias");
            return this;
        }

        public Signals targetBars(Integer v) {
            targetBarCount = v;
            provided.add("target_bar_count");
            return this;
        }

        public Signals leadingStrength(Object v) {
            incomingLeadingStrength = v;
            provided.add("incoming_leading_strength");
            return this;
        }

        public Signals incomingLoudness(Double v) {
            incomingRegionLoudness = v;
            provided.add("incoming_region_loudness");
            return this;
        }

        public Signals incomingTrailingLoudness(Double v) {
            incomingTrailingLoudness = v;
            provided.add("incoming_trailing_loudness");
            return this;
        }

        public Signals vocalOverlap(Boolean v) {
            vocalOverlapIncompatible = v;
            provided.add("vocal_overlap_incompatible");
            return this;
        }

        public Signals loudnessRelation(Integer v) {
            incomingLoudnessRelationCode = v;
            provided.add("incoming_loudness_relation_code");
            return this;
        }

        public Signals trailingSpan(Double v) {
            trailingSpanSeconds = v;
            provided.add("trailing_span_seconds");
            return this;
        }

        public Signals outgoingLoudnessSignificant(Boolean v) {
            outgoingLoudnessSignificant = v;
            provided.add("outgoing_loudness_significant");
            return this;
        }
    }

    /** 评分结果。 */
    public static final class Result {
        public String status;
        public Double score;
        public final Map<String, Double> factors = new LinkedHashMap<String, Double>();
        public final List<String> missingInputs = new ArrayList<String>();
        public final List<String> invalidInputs = new ArrayList<String>();
        public double product;
        public double boundaryDelta;
        public double base;
        public String reason;
    }

    /** 参考包 {@code leading_vocal_factor}：high/veryHigh（或 3/4）⇒ 0.75，其余 1。 */
    public static double leadingVocalFactor(Object strength) {
        if (strength instanceof String) {
            String s = (String) strength;
            return ("high".equals(s) || "veryHigh".equals(s)) ? 0.75 : 1.0;
        }
        if (strength instanceof Number) {
            double v = ((Number) strength).doubleValue();
            return (v == 3.0 || v == 4.0) ? 0.75 : 1.0;
        }
        return 1.0;
    }

    /** 参考包 {@code trailing_loudness_ratio}：两者都必须为负 dB；否则 None（调用方当 1）。 */
    public static Double trailingLoudnessRatio(Double regionMean, Double trailingMean) {
        if (regionMean == null || trailingMean == null) {
            return null;
        }
        if (regionMean.doubleValue() >= 0.0 || trailingMean.doubleValue() >= 0.0) {
            return null;
        }
        return Double.valueOf(regionMean.doubleValue() / trailingMean.doubleValue());
    }

    /** 参考包 {@code normalized_incoming_beats}。 */
    public static int normalizedIncomingBeats(int beatCount, double alias) {
        if (alias == 0.5) {
            return beatCount / 2;
        }
        return alias == 1.0 ? beatCount : beatCount * 2;
    }

    /** 参考包 {@code shift_bars_for}。 */
    static int[] shiftBarsFor(String path, int styleId) {
        if ("dance-shifted".equals(path) && styleId == 17) {
            return new int[] {2, 2};
        }
        if ("filtered-shifted".equals(path) && styleId == 12) {
            return new int[] {0, 4};
        }
        if ("hiphop-shifted".equals(path) && styleId == 15) {
            return new int[] {1, 1};
        }
        if ("pop-scaled-shifted".equals(path)) {
            if (styleId == 16) {
                return new int[] {0, 2};
            }
            if (styleId == 20) {
                return new int[] {2, 1};
            }
        }
        return new int[] {0, 0};
    }

    private static String[] requiredFor(String path, int styleId) {
        if ("dance-original".equals(path)) {
            return new String[] {"strict_tempo_compatible", "matching_bar_count"};
        }
        if ("dance-shifted".equals(path)) {
            return new String[] {"strict_tempo_compatible", "matching_bar_count",
                    "tonalities_compatible"};
        }
        if ("filtered-shifted".equals(path)) {
            return new String[] {"strict_tempo_compatible", "loose_tempo_compatible",
                    "matching_bar_count"};
        }
        if ("filtered-direct".equals(path)) {
            return styleId == 9
                    ? new String[] {"strict_tempo_compatible", "tonalities_compatible",
                    "matching_bar_count", "vocal_overlap_incompatible"}
                    : new String[] {"strict_tempo_compatible", "incoming_beat_count",
                    "tempo_alias"};
        }
        if ("hiphop-direct".equals(path) || "hiphop-shifted".equals(path)) {
            return new String[] {"outgoing_beat_count", "incoming_beat_count", "tempo_alias"};
        }
        if ("pop-scaled-shifted".equals(path)) {
            return new String[] {"outgoing_beat_count", "incoming_beat_count", "tempo_alias",
                    "outgoing_loudness_significant"};
        }
        if ("smart-crossfade".equals(path)) {
            return new String[] {"incoming_loudness_relation_code", "trailing_span_seconds"};
        }
        return new String[] {};
    }

    private static boolean isNumeric(String name) {
        return "outgoing_beat_count".equals(name) || "incoming_beat_count".equals(name)
                || "tempo_alias".equals(name) || "incoming_loudness_relation_code".equals(name)
                || "trailing_span_seconds".equals(name);
    }

    /** {@code evaluate_candidate}。 */
    public static Result evaluate(String path, int styleId, double outgoingEnd, double incomingEnd,
                                  Signals signals, boolean positionalEnabled) {
        Spec spec = SPECS.get(path);
        if (spec == null) {
            throw new IllegalArgumentException("unknown path: " + path);
        }
        boolean styleOk = false;
        for (int i = 0; i < spec.styleIds.length; i++) {
            if (spec.styleIds[i] == styleId) {
                styleOk = true;
                break;
            }
        }
        if (!styleOk) {
            throw new IllegalArgumentException("style/path pair has no confirmed direct entry");
        }
        Result r = new Result();
        r.base = spec.bases != null && spec.bases.containsKey(Integer.valueOf(styleId))
                ? spec.bases.get(Integer.valueOf(styleId)).doubleValue() : spec.base;
        String[] required = requiredFor(path, styleId);
        for (int i = 0; i < required.length; i++) {
            String name = required[i];
            boolean missing = !signals.provided.contains(name)
                    || (isNumeric(name) && numericValue(name, signals) == null);
            if (missing) {
                r.missingInputs.add(name);
            }
        }
        double target = signals.targetBarCount != null ? signals.targetBarCount.doubleValue() : 8.0;
        if (!(target > 0.0) || Double.isNaN(target) || Double.isInfinite(target)) {
            throw new IllegalArgumentException("Positive finite template target bar count required");
        }
        if (!r.missingInputs.isEmpty()) {
            r.status = "missing_upstream_signals";
            r.score = null;
            return r;
        }
        List<String> invalid = r.invalidInputs;
        for (int i = 0; i < required.length; i++) {
            String name = required[i];
            if ("outgoing_beat_count".equals(name) || "incoming_beat_count".equals(name)) {
                Object v = "outgoing_beat_count".equals(name)
                        ? Integer.valueOf(signals.outgoingBeatCount) : Integer.valueOf(signals.incomingBeatCount);
                if (v == null) {
                    invalid.add(name);
                }
            }
        }
        if (contains(required, "tempo_alias")) {
            Double alias = signals.tempoAlias;
            if (alias == null || !(alias.doubleValue() == 0.5 || alias.doubleValue() == 1.0
                    || alias.doubleValue() == 2.0)) {
                invalid.add("tempo_alias");
            }
        }
        if (contains(required, "trailing_span_seconds")) {
            Double d = signals.trailingSpanSeconds;
            if (d == null || d.doubleValue() < 0.0) {
                invalid.add("trailing_span_seconds");
            }
        }
        if (contains(required, "matching_bar_count") && signals.matchingBarCount != null
                && signals.matchingBarCount.intValue() < 0) {
            invalid.add("matching_bar_count");
        }
        if (signals.incomingRegionLoudness != null
                && (Double.isNaN(signals.incomingRegionLoudness.doubleValue())
                || Double.isInfinite(signals.incomingRegionLoudness.doubleValue()))) {
            invalid.add("incoming_region_loudness");
        }
        if (signals.incomingTrailingLoudness != null
                && (Double.isNaN(signals.incomingTrailingLoudness.doubleValue())
                || Double.isInfinite(signals.incomingTrailingLoudness.doubleValue()))) {
            invalid.add("incoming_trailing_loudness");
        }
        if (Double.isNaN(outgoingEnd) || Double.isInfinite(outgoingEnd)) {
            invalid.add("outgoing_end");
        }
        if (Double.isNaN(incomingEnd) || Double.isInfinite(incomingEnd)) {
            invalid.add("incoming_end");
        }
        if (!invalid.isEmpty()) {
            r.status = "invalid_upstream_signals";
            r.score = null;
            return r;
        }
        double strict = signals.strictTempoCompatible != null && signals.strictTempoCompatible.booleanValue()
                ? 1.0 : 0.0;
        double barsGate = (signals.matchingBarCount != null && signals.matchingBarCount.intValue() >= 8)
                ? 1.0 : 0.0;
        double vocal = leadingVocalFactor(signals.incomingLeadingStrength);
        Double ratio = trailingLoudnessRatio(signals.incomingRegionLoudness,
                signals.incomingTrailingLoudness);
        double loudness = ratio == null ? 1.0 : ratio.doubleValue();
        double lengthGate = 0.0;
        double coverage = 0.0;
        int outBars = 0;
        if ("hiphop-direct".equals(path) || "hiphop-shifted".equals(path)
                || "pop-scaled-shifted".equals(path)) {
            outBars = signals.outgoingBeatCount.intValue() / 4;
            if (outBars <= 0) {
                r.status = "invalid_upstream_region";
                r.reason = "Native caller assumes a positive outgoing bar count";
                r.score = null;
                return r;
            }
            int incBars = normalizedIncomingBeats(signals.incomingBeatCount.intValue(),
                    signals.tempoAlias.doubleValue()) / 4;
            lengthGate = (incBars / (double) outBars >= 0.75) ? 1.0 : 0.0;
            coverage = incBars / target;
        }
        Map<String, Double> f = r.factors;
        if ("dance-original".equals(path)) {
            f.put("strict_tempo", Double.valueOf(strict));
            f.put("matching_bars", Double.valueOf(barsGate));
            f.put("leading_vocal", Double.valueOf(vocal));
        } else if ("dance-shifted".equals(path)) {
            f.put("strict_tempo", Double.valueOf(strict));
            f.put("key", Double.valueOf(signals.tonalitiesCompatible != null
                    && signals.tonalitiesCompatible.booleanValue() ? 1.0 : 0.0));
            f.put("matching_bars", Double.valueOf(barsGate));
            f.put("trailing_loudness", Double.valueOf(loudness));
        } else if ("filtered-direct".equals(path) && styleId == 9) {
            f.put("strict_tempo", Double.valueOf(strict));
            f.put("key", Double.valueOf(signals.tonalitiesCompatible != null
                    && signals.tonalitiesCompatible.booleanValue() ? 1.0 : 0.0));
            f.put("matching_bars", Double.valueOf(barsGate));
            f.put("no_vocal_overlap", Double.valueOf(signals.vocalOverlapIncompatible != null
                    && !signals.vocalOverlapIncompatible.booleanValue() ? 1.0 : 0.0));
            f.put("leading_vocal", Double.valueOf(vocal));
            f.put("trailing_loudness", Double.valueOf(loudness));
        } else if ("filtered-direct".equals(path)) {
            int incBars = normalizedIncomingBeats(signals.incomingBeatCount.intValue(),
                    signals.tempoAlias.doubleValue()) / 4;
            f.put("strict_tempo", Double.valueOf(strict));
            f.put("leading_vocal", Double.valueOf(vocal));
            f.put("incoming_bar_coverage", Double.valueOf(incBars / target));
            f.put("trailing_loudness", Double.valueOf(loudness));
        } else if ("filtered-shifted".equals(path)) {
            double tempoRescue = (signals.strictTempoCompatible != null
                    && !signals.strictTempoCompatible.booleanValue()
                    && signals.looseTempoCompatible != null
                    && signals.looseTempoCompatible.booleanValue()) ? 1.0 : 0.0;
            f.put("tempo_rescue", Double.valueOf(tempoRescue));
            f.put("matching_bars", Double.valueOf(barsGate));
            f.put("leading_vocal", Double.valueOf(vocal));
            f.put("trailing_loudness", Double.valueOf(loudness));
        } else if ("hiphop-direct".equals(path) || "hiphop-shifted".equals(path)) {
            f.put("leading_vocal", Double.valueOf(vocal));
            f.put("relative_bar_length", Double.valueOf(lengthGate));
            f.put("incoming_bar_coverage", Double.valueOf(coverage));
            f.put("trailing_loudness", Double.valueOf(loudness));
        } else if ("pop-scaled-shifted".equals(path)) {
            f.put("outgoing_bars_equal_target",
                    Double.valueOf(outBars == (int) target ? 1.0 : 0.0));
            f.put("relative_bar_length", Double.valueOf(lengthGate));
            f.put("leading_vocal", Double.valueOf(vocal));
            f.put("outgoing_loudness", Double.valueOf(signals.outgoingLoudnessSignificant != null
                    && signals.outgoingLoudnessSignificant.booleanValue() ? 1.0 : 0.0));
            f.put("trailing_loudness", Double.valueOf(loudness));
        } else if ("smart-crossfade".equals(path)) {
            Integer code = signals.incomingLoudnessRelationCode;
            if (code == null || code.intValue() < 0 || code.intValue() > 63) {
                r.status = "invalid_upstream_relation";
                r.score = null;
                return r;
            }
            boolean lowCodes = styleId == 3 || styleId == 33;
            double relationGate = (lowCodes ? code.intValue() == 0 : code.intValue() != 0) ? 1.0 : 0.0;
            double duration = signals.trailingSpanSeconds.doubleValue();
            boolean shortStyles = styleId == 3 || styleId == 4;
            double durationGate = (shortStyles ? duration >= 5.0 : duration < 5.0) ? 1.0 : 0.0;
            f.put("incoming_loudness_relation", Double.valueOf(relationGate));
            f.put("trailing_duration", Double.valueOf(durationGate));
        }
        double product = r.base;
        for (Double factor : f.values()) {
            product *= factor.doubleValue();
        }
        double delta = positionalEnabled ? outgoingEnd - incomingEnd : 0.0;
        r.product = product;
        r.boundaryDelta = delta;
        r.score = Double.valueOf(product > 0.0 ? product + 0.001 * delta : product);
        r.status = "scored";
        return r;
    }

    private static boolean contains(String[] arr, String value) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static Double numericValue(String name, Signals s) {
        if ("outgoing_beat_count".equals(name)) {
            return s.outgoingBeatCount == null ? null
                    : Double.valueOf(s.outgoingBeatCount.doubleValue());
        }
        if ("incoming_beat_count".equals(name)) {
            return s.incomingBeatCount == null ? null
                    : Double.valueOf(s.incomingBeatCount.doubleValue());
        }
        if ("tempo_alias".equals(name)) {
            return s.tempoAlias;
        }
        if ("incoming_loudness_relation_code".equals(name)) {
            return s.incomingLoudnessRelationCode == null ? null
                    : Double.valueOf(s.incomingLoudnessRelationCode.doubleValue());
        }
        return s.trailingSpanSeconds;
    }
}
