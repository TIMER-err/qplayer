package dev.t1m3.qplayer.audio.mix;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>参考包选点决策的完整端口</b>（scoring.py 的 {@code _candidate} + {@code plan_transition}）。
 *
 * <p>逐条对齐的东西（顺序也一致，因为因子是**按插入顺序连乘**进 product 的）：
 * <ol>
 *   <li>闸门：gapless / 专辑无缝 / 可重叠 / 复杂度 / splice / smart 响度 /
 *       拍可信度 / 拍稳定度 / timing 置信度 / 原声度 / 舞曲度 / 拍号相等 /
 *       拍速 log 距离 / 调性 / 调性置信度 / 风格词 / 时长 / downbeat 对齐 /
 *       丢尾 30 s / 剪头 24 s / 准备余量 / 人声冲突 / 最低分</li>
 *   <li>因子：stretch_comfort · beat_reliability · beat_stability · tonality · key_reliability ·
 *       vocal_safety · loudness_continuity · energy_continuity · phrase_fit · placement ·
 *       effect_reserve · duration_fit · eligibility</li>
 *   <li>分数：{@code score_core(base, factors.values(), boundary_delta, 0.001)}</li>
 *   <li>选择：每模板取代表候选 → 原生默认 16 风格过滤 → 排序 → 最高 tier →
 *       trunc(score×1000) 权重 → {@link ReferenceRandom#randrange}</li>
 * </ol>
 */
public final class ReferenceDecision {

    private ReferenceDecision() {
    }

    /** 一次候选（参考包的候选 dict 的 Java 版）。 */
    public static final class Candidate {
        public int templateId;
        public String templateName;
        public String family;
        public int tier;
        public double baseScore;
        public double score;
        public double boundaryDelta;
        public boolean eligible = true;
        public final List<String> rejectedReasons = new ArrayList<String>();
        public final Map<String, Double> factors = new LinkedHashMap<String, Double>();
        public final Set<String> unknownFeatures = new LinkedHashSet<String>();
        public double duration;
        public double outStart;
        public double outEnd;
        public double inStart;
        public double inEnd;
        public double gainDb;
        public ReferenceTempo.Relationship tempo;
        public String outRegionId;
        public String inRegionId;
    }

    /** 选择结果（参考包 plan_transition 返回值的 Java 版）。 */
    public static final class Result {
        public final List<Candidate> ranking = new ArrayList<Candidate>();
        public Integer preferredTier;
        public final List<Candidate> preferred = new ArrayList<Candidate>();
        public final List<Integer> weights = new ArrayList<Integer>();
        public long seed;
        public Candidate chosen;
    }

    private interface Transform {
        double apply(double a, double b);
    }

    /** {@code _candidate}：算一个 (模板, 出场区, 进场区) 候选。 */
    public static Candidate candidate(ReferenceTrack outgoing, ReferenceTrack incoming,
                                      ReferenceTrack.Region outRegion, ReferenceTrack.Region inRegion,
                                      ReferenceProfiles.Profile profile, ReferenceLimits limits) {
        Map<String, Double> outF = merged(outgoing, outRegion);
        Map<String, Double> inF = merged(incoming, inRegion);
        Candidate c = new Candidate();
        c.templateId = (int) profile.id;
        c.templateName = profile.name;
        c.family = profile.family;
        c.tier = (int) profile.tier;
        c.baseScore = profile.base_score;
        c.outRegionId = outRegion.id;
        c.inRegionId = inRegion.id;

        boolean beat = profile.beat_matched;
        String kind = profile.family;
        boolean gapless = "gapless".equals(kind);
        boolean splice = "splice".equals(kind);

        final List<String> rejected = c.rejectedReasons;
        final Set<String> unknown = c.unknownFeatures;

        if (gapless) {
            gate(rejected, limits.preserveAlbumGapless || !limits.supportsOverlap
                            || Math.min(outgoing.duration, incoming.duration) < 1.0
                            || outgoing.duration - limits.currentOutgoingTime <= limits.setupMargin + 0.25,
                    "gapless reserved for continuity, short tracks or late preparation");
        }
        gate(rejected, gapless || !limits.preserveAlbumGapless, "album requires gapless continuity");
        gate(rejected, gapless || splice || limits.supportsOverlap, "player cannot overlap tracks");
        gate(rejected, (int) profile.required_complexity <= limits.maxComplexity,
                "effect complexity exceeds capability");
        if (splice) {
            gate(rejected, outgoing.trailingSilence + incoming.leadingSilence >= 0.25,
                    "splice requires detected silence");
            gate(rejected, limits.supportsOverlap, "gapless continuity required when overlap capability is absent");
        }
        if ("smart".equals(kind)) {
            gate(rejected, outF.get("loudness_db") != null && inF.get("loudness_db") != null,
                    "smart fade needs both region loudness values");
            gate(rejected, (outF.get("loudness_db") == null || outF.get("loudness_db").doubleValue() > -55.0)
                            && (inF.get("loudness_db") == null || inF.get("loudness_db").doubleValue() > -55.0),
                    "smart fade needs audible loudness");
        }

        ReferenceTempo.Relationship tempo = null;
        if (beat) {
            gate(rejected, limits.supportsSpeedRamp, "player cannot apply speed ramps");
            gate(rejected, !outgoing.downbeats.isEmpty() && !incoming.downbeats.isEmpty(),
                    "downbeat maps are required");
            ReferenceTrack[] sides = {outgoing, incoming};
            Map<String, Double>[] sideF = new Map[] {outF, inF};
            for (int i = 0; i < 2; i++) {
                Map<String, Double> f = sideF[i];
                gate(rejected, f.get("bpm") != null, "tempo unavailable");
                gate(rejected, f.get("beat_confidence") != null
                                && f.get("beat_confidence").doubleValue() >= ReferenceProfiles.MIN_BEAT_CONFIDENCE,
                        "beat confidence below threshold");
                gate(rejected, f.get("beat_stability") != null
                                && f.get("beat_stability").doubleValue() >= ReferenceProfiles.MIN_BEAT_STABILITY,
                        "beat stability below threshold");
                Double timing = f.get("timing_confidence");
                gate(rejected, timing == null || timing.doubleValue() >= 0.5, "asset timing unverified");
                gate(rejected, f.get("acousticness") == null
                                || f.get("acousticness").doubleValue() <= ReferenceProfiles.MAX_ACOUSTICNESS,
                        "acousticness too high");
                gate(rejected, f.get("danceability") == null
                                || f.get("danceability").doubleValue() >= ReferenceProfiles.MIN_DANCEABILITY,
                        "danceability too low");
            }
            Double outMeter = outF.get("meter");
            Double inMeter = inF.get("meter");
            gate(rejected, outMeter != null && inMeter != null
                            && outMeter.doubleValue() == inMeter.doubleValue(),
                    "time signature unavailable or mismatched");
            Double outBpm = outF.get("bpm");
            Double inBpm = inF.get("bpm");
            if (outBpm != null && inBpm != null && outBpm.doubleValue() != 0.0 && inBpm.doubleValue() != 0.0) {
                ReferenceLogic.TempoRelation rel =
                        ReferenceLogic.tempoRelationship(outBpm.doubleValue(), inBpm.doubleValue());
                tempo = ReferenceTempo.harmonicTempo(outBpm.doubleValue(), inBpm.doubleValue(),
                        rel.alias, rel.logDistance);
                gate(rejected, rel.logDistance <= ReferenceProfiles.MAX_TEMPO_LOG_DISTANCE,
                        "tempo log distance exceeds configured range");
                c.factors.put("stretch_comfort",
                        Double.valueOf(ReferenceTempo.stretchComfort(tempo, ReferenceProfiles.STRETCH_SIGMA)));
            }
            c.factors.put("beat_reliability", Double.valueOf(measured(outF, inF, unknown, "beat_confidence", MIN)));
            c.factors.put("beat_stability", Double.valueOf(measured(outF, inF, unknown, "beat_stability", MIN)));

            // 参考包在这里还要过一次 key_relation：relative / neighbouring 都归为 'compatible'
            // （漏了这一步 ⇒ dance/hiphop 这些 key_policy=compatible 的模板会被误淘汰 ✗）
            String rawRelation = ReferenceLogic.tonalityRelationship(keyFrom(outF, outgoing),
                    keyFrom(inF, incoming));
            String relation = ("relative".equals(rawRelation) || "neighbouring".equals(rawRelation))
                    ? "compatible" : rawRelation;
            Double melodicA = outF.get("melodicness");
            Double melodicB = inF.get("melodicness");
            boolean weakMelody = (melodicA != null && melodicA.doubleValue() < ReferenceProfiles.LOW_MELODICNESS)
                    || (melodicB != null && melodicB.doubleValue() < ReferenceProfiles.LOW_MELODICNESS);
            boolean keyOk = "any".equals(profile.key_policy)
                    || (!"unknown".equals(relation) && ("identical".equals(relation)
                    || ("compatible".equals(profile.key_policy) && "compatible".equals(relation)) || weakMelody));
            gate(rejected, keyOk, "tonality incompatible or unknown for this profile");
            c.factors.put("tonality", Double.valueOf("identical".equals(relation) ? 1.0
                    : ("compatible".equals(relation) ? 0.95 : 0.75)));
            if ("unknown".equals(relation)) {
                unknown.add("key");
            }
            if (!"any".equals(profile.key_policy) && !weakMelody) {
                c.factors.put("key_reliability",
                        Double.valueOf(measured(outF, inF, unknown, "key_confidence", MIN)));
                Double kcA = outF.get("key_confidence");
                Double kcB = inF.get("key_confidence");
                gate(rejected, kcA != null && kcA.doubleValue() >= ReferenceProfiles.MIN_KEY_CONFIDENCE,
                        "key confidence below threshold");
                gate(rejected, kcB != null && kcB.doubleValue() >= ReferenceProfiles.MIN_KEY_CONFIDENCE,
                        "key confidence below threshold");
            }
            if (GENRE_WORDS.containsKey(kind)) {
                String[] words = GENRE_WORDS.get(kind);
                gate(rejected, genreMatches(outgoing.textFeatures.get("genre"), words)
                                && genreMatches(incoming.textFeatures.get("genre"), words),
                        "genre incompatible with strategy");
            }
        }

        double duration = 0.0;
        double outRate = tempo == null ? 1.0 : tempo.outgoingRate;
        double inRate = tempo == null ? 1.0 : tempo.incomingRate;
        if (!gapless && !splice) {
            // **参考包原文**（scoring.py:251）：
            //   wanted = preferred_beats*60/target_bpm        （有拍有速度）
            //          = policy.fallback_seconds              （family == 'fallback'）
            //          = min(8, preferred_beats)              （其余：preferred_beats 当**秒**用）
            // 我们之前一律用 fallback_seconds(4) ⇒ 非对拍模板（如 smart 的 preferred_beats=6）
            // 时长少 2 s ⇒ boundary_delta 差 2 ⇒ 分数差 0.002（被对拍台逐模板比对抓到 ✗）。
            double wanted;
            if (beat && tempo != null) {
                wanted = profile.preferred_beats * 60.0 / tempo.targetBpm;
            } else if ("fallback".equals(kind)) {
                wanted = ReferenceProfiles.FALLBACK_SECONDS;
            } else {
                wanted = Math.min(8.0, profile.preferred_beats);
            }
            duration = Math.min(wanted, ReferenceProfiles.MAX_OVERLAP_SECONDS);
            duration = Math.min(duration, (outRegion.end - outRegion.start) / outRate);
            duration = Math.min(duration, (inRegion.end - inRegion.start) / inRate);
            if (beat && tempo != null) {
                double beatSeconds = 60.0 / tempo.targetBpm;
                duration = Math.floor((duration + 1e-9) / beatSeconds) * beatSeconds;
                gate(rejected, duration >= 2.0 * beatSeconds, "insufficient room for a two-beat overlap");
            } else {
                gate(rejected, duration >= 0.25, "insufficient room for overlap");
            }
        }
        double outStart = outRegion.end - duration * outRate;
        double inStart = inRegion.start;
        if (gapless) {
            outStart = outgoing.duration;
            inStart = 0.0;
        }
        if (beat) {
            List<Double> outOptions = new ArrayList<Double>();
            for (int i = 0; i < outgoing.downbeats.size(); i++) {
                double v = outgoing.downbeats.get(i).doubleValue();
                if (v >= outRegion.start && v <= outStart + 1e-7) {
                    outOptions.add(Double.valueOf(v));
                }
            }
            List<Double> inOptions = new ArrayList<Double>();
            double inLimit = inRegion.end - duration * inRate;
            for (int i = 0; i < incoming.downbeats.size(); i++) {
                double v = incoming.downbeats.get(i).doubleValue();
                if (v >= inStart && v <= inLimit) {
                    inOptions.add(Double.valueOf(v));
                }
            }
            gate(rejected, !outOptions.isEmpty() && !inOptions.isEmpty(),
                    "no aligned downbeats fit candidate region");
            if (!outOptions.isEmpty()) {
                outStart = outOptions.get(outOptions.size() - 1).doubleValue();
            }
            if (!inOptions.isEmpty()) {
                inStart = inOptions.get(0).doubleValue();
            }
        }
        double outEnd = outStart + duration * outRate;
        double inEnd = inStart + duration * inRate;
        double skipped = Math.max(0.0, outgoing.duration - outgoing.trailingSilence - outEnd);
        double cutIn = Math.max(0.0, inStart - incoming.leadingSilence);
        gate(rejected, skipped <= ReferenceProfiles.MAX_OUTGOING_SKIP || gapless,
                "outgoing truncation exceeds constraint");
        gate(rejected, cutIn <= ReferenceProfiles.MAX_INCOMING_OFFSET || gapless,
                "incoming skip exceeds constraint");
        gate(rejected, outStart >= limits.currentOutgoingTime + limits.setupMargin || gapless,
                "not enough time to prepare");

        if (!gapless && !splice) {
            Double vocalA = outF.get("vocal_activity");
            Double vocalB = inF.get("vocal_activity");
            if (vocalA == null || vocalB == null) {
                unknown.add("vocal_activity");
                c.factors.put("vocal_safety", Double.valueOf(ReferenceProfiles.UNKNOWN_FACTOR));
            } else {
                double clash = vocalA.doubleValue() * vocalB.doubleValue();
                double suppression = profile.vocal_suppression;
                gate(rejected, !beat || clash <= 0.8 || suppression >= 0.4,
                        "strong simultaneous vocals; profile cannot suppress them");
                c.factors.put("vocal_safety",
                        Double.valueOf(Math.max(0.05, 1.0 - 0.85 * clash * (1.0 - suppression))));
            }
            c.factors.put("loudness_continuity", Double.valueOf(measured(outF, inF, unknown, "loudness_db",
                    new Transform() {
                        @Override
                        public double apply(double a, double b) {
                            double z = (a - b) / ReferenceProfiles.LOUDNESS_SIGMA_DB;
                            return Math.exp(-0.5 * z * z);
                        }
                    })));
            c.factors.put("energy_continuity", Double.valueOf(measured(outF, inF, unknown, "energy",
                    new Transform() {
                        @Override
                        public double apply(double a, double b) {
                            double z = (a - b) / ReferenceProfiles.ENERGY_SIGMA;
                            return Math.exp(-0.5 * z * z);
                        }
                    })));
            c.factors.put("phrase_fit", Double.valueOf(measured(outF, inF, unknown, "phrase_confidence", MIN)));
            c.factors.put("placement", Double.valueOf(Math.exp(-skipped / 12.0 - cutIn / 24.0)));
            c.factors.put("effect_reserve", Double.valueOf(1.0 - 0.04 * profile.required_complexity));
            if (beat) {
                double wantedSeconds = tempo == null ? 1.0 : profile.preferred_beats * 60.0 / tempo.targetBpm;
                c.factors.put("duration_fit", Double.valueOf(Math.min(1.0, duration / wantedSeconds)));
            }
        }
        c.factors.put("eligibility", Double.valueOf(rejected.isEmpty() ? 1.0 : 0.0));
        c.duration = duration;
        c.outStart = outStart;
        c.outEnd = outEnd;
        c.inStart = inStart;
        c.inEnd = inEnd;
        c.boundaryDelta = outEnd - inEnd;
        double[] factors = new double[c.factors.size()];
        int k = 0;
        for (Double v : c.factors.values()) {
            factors[k++] = v.doubleValue();
        }
        c.score = ReferenceScoring.scoreCore(profile.base_score, factors, c.boundaryDelta);
        if (c.score < ReferenceProfiles.MIN_SCORE && rejected.isEmpty()) {
            rejected.add("score below minimum");
            c.factors.put("eligibility", Double.valueOf(0.0));
            c.score = 0.0;
        }
        Double loudA = outF.get("loudness_db");
        Double loudB = inF.get("loudness_db");
        if (loudA != null && loudB != null) {
            double g = loudA.doubleValue() - loudB.doubleValue();
            c.gainDb = Math.max(-ReferenceProfiles.MAX_LOUDNESS_GAIN_DB,
                    Math.min(ReferenceProfiles.MAX_LOUDNESS_GAIN_DB, g));
        }
        c.tempo = tempo;
        c.eligible = rejected.isEmpty();
        Collections.sort(rejected);
        // 参考包对 rejected_reasons 做 sorted(set(...))；判据里只用到"条数"，故去重也照做
        dedupe(rejected);
        return c;
    }

    /** {@code plan_transition}：跑全部模板 → 代表候选 → 过滤 → 排序 → 选一个。 */
    public static Result plan(ReferenceTrack outgoing, ReferenceTrack incoming, ReferenceLimits limits,
                              long seed, boolean weighted) {
        return plan(outgoing, incoming, limits, seed, weighted, null);
    }

    /**
     * 同上，但可以附加**我们自己的接法**（{@link FusionStyles}）一起竞争。
     *
     * <p>关键差别：附加模板不受 {@code native_default_styles_only} 的限制
     * （那是 Apple 目录的约束，我们的接法本来就不在它的目录里），其余闸门/因子/tier/
     * 加权取模全部走同一套。
     */
    public static Result plan(ReferenceTrack outgoing, ReferenceTrack incoming, ReferenceLimits limits,
                              long seed, boolean weighted, ReferenceProfiles.Profile[] extraProfiles) {
        return plan(outgoing, incoming, limits, seed, weighted, extraProfiles, false);
    }

    /**
     * @param scoreFirst {@code true} = **分数优先**（用户 2026-11-21：「肯定分数优先啊」）：
     *                   合格候选里直接按分数排序、不分 tier；
     *                   {@code false} = 参考包原规则（先 tier、同层再比分数）。
     *                   纯参考包模式（对拍）必须传 false，才能保持 diff = 0。
     */
    public static Result plan(ReferenceTrack outgoing, ReferenceTrack incoming, ReferenceLimits limits,
                              long seed, boolean weighted, ReferenceProfiles.Profile[] extraProfiles,
                              boolean scoreFirst) {
        Result r = new Result();
        r.seed = seed;
        List<ReferenceTrack.Region> outRegions = outgoing.regionsFor("outgoing");
        List<ReferenceTrack.Region> inRegions = incoming.regionsFor("incoming");
        java.util.List<ReferenceProfiles.Profile> all =
                new ArrayList<ReferenceProfiles.Profile>();
        for (int i = 0; i < ReferenceProfiles.PROFILES.length; i++) {
            all.add(ReferenceProfiles.PROFILES[i]);
        }
        if (extraProfiles != null) {
            for (int i = 0; i < extraProfiles.length; i++) {
                all.add(extraProfiles[i]);
            }
        }
        for (int i = 0; i < all.size(); i++) {
            ReferenceProfiles.Profile profile = all.get(i);
            Candidate representative = null;
            for (int a = 0; a < outRegions.size(); a++) {
                for (int b = 0; b < inRegions.size(); b++) {
                    Candidate c = candidate(outgoing, incoming, outRegions.get(a), inRegions.get(b),
                            profile, limits);
                    if (representative == null || better(c, representative)) {
                        representative = c;
                    }
                }
            }
            if (representative != null) {
                boolean ourStyle = representative.templateId >= 900;
                if (limits.nativeDefaultStylesOnly && !ourStyle
                        && !ReferenceRouter.defaultGenerated(representative.templateId)) {
                    representative.eligible = false;
                    representative.rejectedReasons.add("Template absent from recovered native default generators");
                    Collections.sort(representative.rejectedReasons);
                    dedupe(representative.rejectedReasons);
                }
                r.ranking.add(representative);
            }
        }
        final boolean byScore = scoreFirst;
        Collections.sort(r.ranking, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                if (a.eligible != b.eligible) {
                    return a.eligible ? -1 : 1;
                }
                if (a.eligible && b.eligible) {
                    if (byScore) {
                        int byScoreOrder = Double.compare(b.score, a.score);
                        if (byScoreOrder != 0) {
                            return byScoreOrder;
                        }
                    } else {
                        int ka = -a.tier;
                        int kb = -b.tier;
                        if (ka != kb) {
                            return ka < kb ? -1 : 1;
                        }
                        int byScoreOrder = Double.compare(b.score, a.score);
                        if (byScoreOrder != 0) {
                            return byScoreOrder;
                        }
                    }
                }
                return a.templateId < b.templateId ? -1 : (a.templateId > b.templateId ? 1 : 0);
            }
        });
        if (scoreFirst) {
            // 分数优先：合格候选全部进入加权池（不再只取最高 tier 那层）
            for (int i = 0; i < r.ranking.size(); i++) {
                Candidate c = r.ranking.get(i);
                if (c.eligible) {
                    r.preferred.add(c);
                    r.weights.add(Integer.valueOf((int) Math.floor(c.score * 1000.0)));
                    r.preferredTier = Integer.valueOf(Math.max(
                            r.preferredTier == null ? c.tier : r.preferredTier.intValue(), c.tier));
                }
            }
            r.preferredTier = null;
            return finish(r, weighted, seed);
        }
        int tier = Integer.MIN_VALUE;
        for (int i = 0; i < r.ranking.size(); i++) {
            Candidate c = r.ranking.get(i);
            if (c.eligible) {
                tier = Math.max(tier == Integer.MIN_VALUE ? c.tier : tier, c.tier);
            }
        }
        if (tier != Integer.MIN_VALUE) {
            r.preferredTier = Integer.valueOf(tier);
            for (int i = 0; i < r.ranking.size(); i++) {
                Candidate c = r.ranking.get(i);
                if (c.eligible && c.tier == tier) {
                    r.preferred.add(c);
                    r.weights.add(Integer.valueOf((int) Math.floor(c.score * 1000.0)));
                }
            }
        }
        return finish(r, weighted, seed);
    }

    /** 根据 preferred + 权重做加权取模（参考包 random.Random(seed).randrange）。 */
    private static Result finish(Result r, boolean weighted, long seed) {
        if (r.preferred.isEmpty()) {
            return r;
        }
        Candidate chosen = r.preferred.get(0);
        long sum = 0L;
        for (int i = 0; i < r.weights.size(); i++) {
            sum += r.weights.get(i).longValue();
        }
        if (weighted && sum > 0L) {
            ReferenceRandom random = new ReferenceRandom(seed);
            long value = random.randrange((int) Math.min(Integer.MAX_VALUE, sum));
            for (int i = 0; i < r.preferred.size(); i++) {
                value -= r.weights.get(i).longValue();
                if (value < 0) {
                    chosen = r.preferred.get(i);
                    break;
                }
            }
        }
        r.chosen = chosen;
        return r;
    }

    /** 参考包的默认 seed：sha256(json([out_id, in_id])) 前 8 字节按大端。 */
    public static long defaultSeed(String outgoingId, String incomingId) {
        String material = "[" + json(outgoingId) + "," + json(incomingId) + "]";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            long v = 0L;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (digest[i] & 0xffL);
            }
            return v;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') {
                sb.append('\\').append(ch);
            } else {
                sb.append(ch);
            }
        }
        return "\"" + sb + "\"";
    }

    private static boolean better(Candidate a, Candidate b) {
        if (a.eligible != b.eligible) {
            return a.eligible;
        }
        if (a.score != b.score) {
            return a.score > b.score;
        }
        return -a.rejectedReasons.size() > -b.rejectedReasons.size();
    }

    private static void gate(List<String> rejected, boolean condition, String reason) {
        if (!condition) {
            rejected.add(reason);
        }
    }

    private static Map<String, Double> merged(ReferenceTrack track, ReferenceTrack.Region region) {
        Map<String, Double> m = new LinkedHashMap<String, Double>(track.features);
        m.putAll(region.features);
        return m;
    }

    /** 先看 region 里的 key（参考包的 region 可以带自己的调性），没有再退回曲目级。 */
    private static ReferenceLogic.Key keyFrom(Map<String, Double> features, ReferenceTrack track) {
        Double tonic = features.get("key_tonic");
        Double major = features.get("key_mode_major");
        if (tonic != null && major != null) {
            return new ReferenceLogic.Key((int) Math.round(tonic.doubleValue()),
                    major.doubleValue() >= 0.5 ? "major" : "minor");
        }
        return keyOf(track);
    }

    private static ReferenceLogic.Key keyOf(ReferenceTrack track) {
        if (track.keyTonic == null || track.keyMode == null) {
            return null;
        }
        return new ReferenceLogic.Key(track.keyTonic.intValue(), track.keyMode);
    }

    private static final Transform MIN = new Transform() {
        @Override
        public double apply(double a, double b) {
            return Math.min(a, b);
        }
    };

    private static double measured(Map<String, Double> outF, Map<String, Double> inF,
                                  Set<String> unknown, String key, Transform transform) {
        Double a = outF.get(key);
        Double b = inF.get(key);
        if (a == null || b == null) {
            unknown.add(key);
            return ReferenceProfiles.UNKNOWN_FACTOR;
        }
        return transform.apply(a.doubleValue(), b.doubleValue());
    }

    private static final Map<String, String[]> GENRE_WORDS = new LinkedHashMap<String, String[]>();

    static {
        GENRE_WORDS.put("dance", new String[] {"dance", "electronic", "house", "techno"});
        GENRE_WORDS.put("hiphop", new String[] {"hiphop", "hip-hop", "rap"});
        GENRE_WORDS.put("pop", new String[] {"pop"});
    }

    private static boolean genreMatches(String genre, String[] words) {
        if (genre == null) {
            return false;
        }
        String text = genre.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < words.length; i++) {
            if (text.contains(words[i])) {
                return true;
            }
        }
        return false;
    }

    private static void dedupe(List<String> list) {
        Set<String> seen = new LinkedHashSet<String>(list);
        list.clear();
        list.addAll(seen);
    }
}
