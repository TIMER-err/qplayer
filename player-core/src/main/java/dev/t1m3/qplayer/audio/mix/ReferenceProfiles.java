package dev.t1m3.qplayer.audio.mix;

/**
 * <b>参考包 profiles.json 的 Java 常量版</b>（脚本生成，不手抄、不改数值）。
 *
 * <p>数值与 automix-c/profiles.json 逐位相同；缺失值按字段类型记为 NaN / "" / false。
 * 4 个结构化字段（curves_observed / native_path_names / parameters_observed /
 * processors_observed）是描述性清单，选点不需要，故未生成。
 */
public final class ReferenceProfiles {
    private ReferenceProfiles() {
    }

    /** 一个模板。 */
    public static final class Profile {
        public final double automation_ramps_observed;
        public final double base_score;
        public final String base_score_provenance;
        public final boolean beat_matched;
        public final String duration_provenance;
        public final String effect;
        public final String family;
        public final double id;
        public final String key_policy;
        public final String name;
        public final String policy_provenance;
        public final double preferred_beats;
        public final double raw_duration_observed;
        public final double required_complexity;
        public final double tier;
        public final double vocal_suppression;

        Profile(double automation_ramps_observed, double base_score, String base_score_provenance, boolean beat_matched, String duration_provenance, String effect, String family, double id, String key_policy, String name, String policy_provenance, double preferred_beats, double raw_duration_observed, double required_complexity, double tier, double vocal_suppression) {
            this.automation_ramps_observed = automation_ramps_observed;
            this.base_score = base_score;
            this.base_score_provenance = base_score_provenance;
            this.beat_matched = beat_matched;
            this.duration_provenance = duration_provenance;
            this.effect = effect;
            this.family = family;
            this.id = id;
            this.key_policy = key_policy;
            this.name = name;
            this.policy_provenance = policy_provenance;
            this.preferred_beats = preferred_beats;
            this.raw_duration_observed = raw_duration_observed;
            this.required_complexity = required_complexity;
            this.tier = tier;
            this.vocal_suppression = vocal_suppression;
        }
    }

    /** 24 个模板（顺序与 profiles.json 一致）。 */
    public static final Profile[] PROFILES = {
            new Profile(2.0, 1.0, "representative base from a confirmed direct candidate path; automatic region generation and factor policy are independent", false, "independent beats/seconds policy; not interpreting raw Apple duration", "none", "gapless", 0.0, "none", "Gapless", "independent prototype; not a recovered Apple rule", 0.0, Double.NaN, 0.0, 0.0, 0.0),
            new Profile(2.0, 1.0, "representative base from a confirmed direct candidate path; automatic region generation and factor policy are independent", false, "independent beats/seconds policy; not interpreting raw Apple duration", "none", "fallback", 1.0, "none", "constant-power cross-fade", "independent prototype; not a recovered Apple rule", 4.0, Double.NaN, 0.0, 0.0, 0.0),
            new Profile(2.0, 10.0, "family_transfer_or_independent_assignment; full-catalog prototype assignment, not a path-specific native score; see recovered_candidate_paths", false, "independent beats/seconds policy; not interpreting raw Apple duration", "none", "smart", 2.0, "soft", "constant-power long fade-out short fade-in", "independent prototype; not a recovered Apple rule", 6.0, Double.NaN, 0.0, 1.0, 0.0),
            new Profile(0.0, 1.0, "family_transfer_or_independent_assignment; full-catalog prototype assignment, not a path-specific native score; see recovered_candidate_paths", false, "independent beats/seconds policy; not interpreting raw Apple duration", "none", "splice", 22.0, "none", "splice", "independent prototype; not a recovered Apple rule", 0.0, Double.NaN, 0.0, 1.0, 0.0),
            new Profile(0.0, 10.0, "representative base from a confirmed direct candidate path; automatic region generation and factor policy are independent", false, "independent beats/seconds policy; not interpreting raw Apple duration", "none", "smart", 3.0, "soft", "overlap", "independent prototype; not a recovered Apple rule", 4.0, Double.NaN, 0.0, 1.0, 0.0),
            new Profile(6.0, 10.0, "representative base from a confirmed direct candidate path; automatic region generation and factor policy are independent", false, "independent beats/seconds policy; not interpreting raw Apple duration", "reverb", "smart", 33.0, "soft", "overlap + Reverb", "independent prototype; not a recovered Apple rule", 4.0, Double.NaN, 1.0, 1.0, 0.0),
            new Profile(1.0, 10.0, "representative base from a confirmed direct candidate path; automatic region generation and factor policy are independent", false, "independent beats/seconds policy; not interpreting raw Apple duration", "none", "smart", 4.0, "soft", "Ease-in Ring-out", "independent prototype; not a recovered Apple rule", 4.0, Double.NaN, 0.0, 1.0, 0.0),
            new Profile(7.0, 10.0, "representative base from a confirmed direct candidate path; automatic region generation and factor policy are independent", false, "independent beats/seconds policy; not interpreting raw Apple duration", "reverb", "smart", 44.0, "soft", "Ease-in Ring-out + Reverb", "independent prototype; not a recovered Apple rule", 4.0, Double.NaN, 1.0, 1.0, 0.0),
            new Profile(7.0, 10.0, "representative base from a confirmed direct candidate path; automatic region generation and factor policy are independent", true, "independent beats/seconds policy; not interpreting raw Apple duration", "filter", "filtered", 5.0, "compatible", "BM - Short filter high to low", "independent prototype; not a recovered Apple rule", 4.0, 2.0, 1.0, 2.0, 0.15),
            new Profile(4.0, 20.0, "family_transfer_or_independent_assignment; full-catalog prototype assignment, not a path-specific native score; see recovered_candidate_paths", true, "independent beats/seconds policy; not interpreting raw Apple duration", "none", "filtered", 6.0, "compatible", "Beat-matched long fade-out short fade-in - Same Bar num", "independent prototype; not a recovered Apple rule", 8.0, Double.NaN, 1.0, 2.0, 0.0),
            new Profile(4.0, 20.0, "family_transfer_or_independent_assignment; full-catalog prototype assignment, not a path-specific native score; see recovered_candidate_paths", true, "independent beats/seconds policy; not interpreting raw Apple duration", "none", "filtered", 7.0, "compatible", "Beat-matched long fade-out dynamic fade-in", "independent prototype; not a recovered Apple rule", 8.0, Double.NaN, 1.0, 2.0, 0.0),
            new Profile(7.0, 20.0, "representative base from a confirmed direct candidate path; automatic region generation and factor policy are independent", true, "independent beats/seconds policy; not interpreting raw Apple duration", "filter", "filtered", 8.0, "compatible", "BM - Filter high to low", "independent prototype; not a recovered Apple rule", 8.0, 8.0, 1.0, 2.0, 0.15),
            new Profile(8.0, 50.0, "representative base from a confirmed direct candidate path; automatic region generation and factor policy are independent", true, "independent beats/seconds policy; not interpreting raw Apple duration", "filter", "filtered", 9.0, "compatible", "BM - Filter expansion", "independent prototype; not a recovered Apple rule", 8.0, 8.0, 1.0, 2.0, 0.15),
            new Profile(10.0, 20.0, "family_transfer_or_independent_assignment; full-catalog prototype assignment, not a path-specific native score; see recovered_candidate_paths", true, "independent beats/seconds policy; not interpreting raw Apple duration", "delay", "filtered", 10.0, "compatible", "BM - Filter long out short in + delay", "independent prototype; not a recovered Apple rule", 8.0, Double.NaN, 2.0, 2.0, 0.0),
            new Profile(14.0, 20.0, "family_transfer_or_independent_assignment; full-catalog prototype assignment, not a path-specific native score; see recovered_candidate_paths", true, "independent beats/seconds policy; not interpreting raw Apple duration", "reverb", "filtered", 11.0, "compatible", "BM - Filter long out short in + Reverb", "independent prototype; not a recovered Apple rule", 8.0, Double.NaN, 2.0, 2.0, 0.0),
            new Profile(7.0, 20.0, "representative base from a confirmed direct candidate path; automatic region generation and factor policy are independent", true, "independent beats/seconds policy; not interpreting raw Apple duration", "filter", "filtered", 12.0, "compatible", "BM - Long filter high to low", "independent prototype; not a recovered Apple rule", 16.0, 16.0, 2.0, 2.0, 0.15),
            new Profile(22.0, 20.0, "family_transfer_or_independent_assignment; full-catalog prototype assignment, not a path-specific native score; see recovered_candidate_paths", true, "independent beats/seconds policy; not interpreting raw Apple duration", "reverb", "filtered", 13.0, "compatible", "BM - Filter soft + Reverb", "independent prototype; not a recovered Apple rule", 8.0, 10.0, 2.0, 2.0, 0.0),
            new Profile(20.0, 20.0, "family_transfer_or_independent_assignment; full-catalog prototype assignment, not a path-specific native score; see recovered_candidate_paths", true, "independent beats/seconds policy; not interpreting raw Apple duration", "vocal_filter", "filtered", 14.0, "compatible", "No vocal clash one", "independent prototype; not a recovered Apple rule", 8.0, 10.0, 1.0, 2.0, 0.45),
            new Profile(14.0, 100.0, "representative base from a confirmed direct candidate path; automatic region generation and factor policy are independent", true, "independent beats/seconds policy; not interpreting raw Apple duration", "delay", "hiphop", 15.0, "compatible", "Hiphop - Delay out", "independent prototype; not a recovered Apple rule", 4.0, 4.0, 2.0, 2.0, 0.0),
            new Profile(13.0, 50.0, "representative base from a confirmed direct candidate path; automatic region generation and factor policy are independent", true, "independent beats/seconds policy; not interpreting raw Apple duration", "delay", "pop", 16.0, "compatible", "Pop - Delay out", "independent prototype; not a recovered Apple rule", 4.0, 4.0, 2.0, 2.0, 0.0),
            new Profile(46.0, 17.0, "representative base from a confirmed direct candidate path; automatic region generation and factor policy are independent", true, "independent beats/seconds policy; not interpreting raw Apple duration", "repeater", "dance", 17.0, "identical", "Dance - Repeater", "independent prototype; not a recovered Apple rule", 16.0, 18.0, 3.0, 2.0, 0.0),
            new Profile(16.0, 100.0, "representative base from a confirmed direct candidate path; automatic region generation and factor policy are independent", true, "independent beats/seconds policy; not interpreting raw Apple duration", "loop", "hiphop", 18.0, "compatible", "Hiphop - Looping", "independent prototype; not a recovered Apple rule", 4.0, 4.0, 3.0, 2.0, 0.0),
            new Profile(47.0, 17.0, "representative base from a confirmed direct candidate path; automatic region generation and factor policy are independent", true, "independent beats/seconds policy; not interpreting raw Apple duration", "filter", "dance", 19.0, "any", "Dance - Any Key", "independent prototype; not a recovered Apple rule", 8.0, 8.0, 2.0, 2.0, 0.15),
            new Profile(30.0, 50.0, "representative base from a confirmed direct candidate path; automatic region generation and factor policy are independent", true, "independent beats/seconds policy; not interpreting raw Apple duration", "reverb", "pop", 20.0, "compatible", "Pop - Reverb", "independent prototype; not a recovered Apple rule", 4.0, 4.0, 2.0, 2.0, 0.0),
    };

    /** policy 的 17 项（原样数值）。 */
    public static final double MIN_BEAT_CONFIDENCE = 0.55;
    public static final double MIN_BEAT_STABILITY = 0.55;
    public static final double MAX_ACOUSTICNESS = 0.65;
    public static final double MIN_DANCEABILITY = 0.4;
    public static final double LOW_MELODICNESS = 0.25;
    public static final double MIN_KEY_CONFIDENCE = 0.3;
    public static final double UNKNOWN_FACTOR = 0.75;
    public static final int MAX_OVERLAP_SECONDS = 12;
    public static final int FALLBACK_SECONDS = 4;
    public static final int MAX_INCOMING_OFFSET = 24;
    public static final int MAX_OUTGOING_SKIP = 30;
    public static final int MAX_LOUDNESS_GAIN_DB = 6;
    public static final double MIN_SCORE = 0.001;
    public static final double STRETCH_SIGMA = 0.09;
    public static final int LOUDNESS_SIGMA_DB = 8;
    public static final double ENERGY_SIGMA = 0.4;
    public static final double MAX_TEMPO_LOG_DISTANCE = 0.16;
}
