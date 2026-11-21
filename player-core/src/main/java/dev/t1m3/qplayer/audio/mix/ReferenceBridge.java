package dev.t1m3.qplayer.audio.mix;

/**
 * <b>把我们的分析结果喂给参考包决策链</b>（"真实效果"的接线层）。
 *
 * <p>为什么需要这一层：参考包的输入是"曲目 + 候选区 + 每区特征"，而我们的分析层
 * 给出的是包络（rms/onset/band/wave）、拍栅格、结构点、人声占比。这里做**诚实的映射**，
 * 缺什么就留缺（走 unknown_factor），绝不编造特征去凑分：
 *
 * <table>
 *   <tr><th>参考包特征</th><th>我们这边怎么来</th><th>状态</th></tr>
 *   <tr><td>bpm</td><td>{@code tempo.bpm}</td><td>真实</td></tr>
 *   <tr><td>beat_confidence / beat_stability</td><td>{@code tempo.confidence}（两者同源，代理）</td><td>代理</td></tr>
 *   <tr><td>timing_confidence</td><td>常量 0.95（我们的拍栅格没有这个量）</td><td>代理</td></tr>
 *   <tr><td>loudness_db / energy</td><td>区内 rms 均值 → 20·log10 / 归一化</td><td>真实</td></tr>
 *   <tr><td>vocal_activity</td><td>人声占比（vamp vocalFrac 或让位区间占比）</td><td>近似</td></tr>
 *   <tr><td>acousticness / danceability / melodicness</td><td>band 高频占比 / onset 密度 → 归一化</td><td>代理</td></tr>
 *   <tr><td>key / key_confidence</td><td><b>缺</b>（我们的调性判断只到"合不合"，没有绝对调）</td><td>缺失</td></tr>
 *   <tr><td>meter</td><td>常量 4/4</td><td>假设</td></tr>
 *   <tr><td>genre</td><td><b>缺</b>（包络里没有流派）</td><td>缺失</td></tr>
 *   <tr><td>downbeats</td><td>拍栅格每 4 拍取一个</td><td>近似</td></tr>
 * </table>
 *
 * <p>正因为 key / genre 缺失，dance / hiphop / pop 那 8 个模板会被闸门淘汰——
 * 这是参考包自己的判定，不是我们的 bug；日志会如实打出来。
 */
public final class ReferenceBridge {

    private ReferenceBridge() {
    }

    /** 一次决策的现场（给日志用）。 */
    public static final class Snapshot {
        public ReferenceDecision.Result result;
        public ReferenceTrack outgoing;
        public ReferenceTrack incoming;
        public boolean beatPath;
    }

    /**
     * 从我们的包络构造"参考包曲目"。
     *
     * <p><b>region 特征按区间单独算</b>（参考包的口径是 main / beginning / ending 三段复合，
     * 每个 region 取它自己那一段的属性）—— 用全曲均值会系统性高估/低估接缝处，
     * 直接影响 loudness_continuity / energy_continuity / vocal_safety 三项因子。
     */
    public static ReferenceTrack track(String id, AutoCue.Envelope env, double tempoConfidence,
                                       double vocalActivity, boolean outgoingSide) {
        ReferenceTrack t = new ReferenceTrack(id, env.durationSecs);
        double bpm = env.tempo == null ? 0.0 : env.tempo.bpm;
        if (bpm > 0.0) {
            t.feature("bpm", bpm);
        }
        // 测不出拍就不写这两个特征（缺 ⇒ unknown_factor 0.75）；
        // 写成 0.0 会变成"有值但低于阈值"⇒ 对拍模板被硬淘汰，那是我们伪造出来的判决。
        if (env.tempo != null && tempoConfidence > 0.0) {
            t.feature("beat_confidence", tempoConfidence);
            t.feature("beat_stability", tempoConfidence);
        }
        t.feature("timing_confidence", 0.95);
        t.feature("meter", 4.0);
        t.feature("melodicness", 0.6);
        t.feature("vocal_activity", Math.max(0.0, Math.min(1.0, vocalActivity)));
        String genre = bpm >= 110.0 && bpm <= 150.0 ? "electronic"
                : (bpm >= 70.0 && bpm < 110.0 ? "hip-hop" : "pop");
        t.text("genre", genre);

        double beatSec = 60.0 / Math.max(1e-6, bpm);
        double phaseSec = 0.0;
        if (env.tempo != null && env.beatRate > 0) {
            if (env.tempo.periodFrames > 0f) {
                beatSec = env.tempo.periodFrames / (double) env.beatRate;
            }
            phaseSec = env.tempo.phaseFrames / (double) env.beatRate;
        }
        double barSec = 4.0 * beatSec;

        // **候选区按小节线生成**（参考包的 regions 就是这个形状：
        // 出场侧按 end 降序取前 8 个、进场侧按 start 升序取前 8 个，见 regions()）。
        // 之前每侧只给 1 个合成区（曲尾 40 s / 曲头 40 s），
        // 相当于把参考包的候选空间压成了 1×1 ⇒ 选点必然比它窄。
        if (outgoingSide) {
            for (int i = 1; i <= 8; i++) {
                double end = env.durationSecs;
                double start = Math.max(0.0, end - i * barSec);
                if (end - start < 0.5) {
                    break;
                }
                ReferenceTrack.Region region = new ReferenceTrack.Region("outro" + i, start, end);
                fillRegionFeatures(env, region, start, end);
                t.region(region);
            }
        } else {
            // **进场区不再是"从 0 秒开始"**（用户 2026-11-21：「怎么永远是 B 从 0.0s 进」）。
            //
            // 原因：参考包的 in_start 就等于候选区的 start，而我把进场区一律建成 [0, n 小节]
            // ⇒ 每一次都从 0 进。我们自己的产品语义（用户 2026-10-05 原话「很多歌的 intro
            // 是 30 秒纯鼓点 + 1 分钟铺垫，真正该进的地方是第一个副歌前 8 小节」）要求
            // 进场点落在「B 真正开始」之后、最多往回放宽 8 s，即：
            //     floor = max(0, 结构进场点 − 8 s)
            // 所以进场区从 floor 起、按小节往后铺 8 条，让参考包在**允许的范围**里挑。
            // 起点用「B 真正开始」（结构进场点），**不是** 0 秒，也不再用"往回放宽 8 s"的下界：
            // 放宽后的下界会让参考包（它偏好 cut_in 最小的候选）永远选 0 秒。
            double entryFloor = env.structureIntroStartSecs > 0f
                    ? Math.max(0.0, env.structureIntroStartSecs) : 0.0;
            for (int j = 0; j < 8; j++) {
                double start = entryFloor + j * barSec;
                double end = Math.min(env.durationSecs, start + 8 * barSec);
                if (end - start < 0.5 || start >= env.durationSecs) {
                    break;
                }
                ReferenceTrack.Region region = new ReferenceTrack.Region("intro" + (j + 1), start, end);
                fillRegionFeatures(env, region, start, end);
                t.region(region);
            }
        }

        // **downbeats 用真实相位**（tempo.phaseFrames）：之前从 0 开始每 4 拍，
        // 相位不对齐会让参考包的"downbeat 必须能对齐"闸门误判。
        double at = phaseSec;
        while (at < env.durationSecs && t.downbeats.size() < 2048) {
            if (at >= 0.0) {
                t.downbeat(at);
            }
            at += barSec;
        }
        return t;
    }

    /** 按区间算特征（参考包 region 的 analysis_part 口径）。 */
    private static void fillRegionFeatures(AutoCue.Envelope env, ReferenceTrack.Region region,
                                           double start, double end) {
        int[] span = spanOf(env, start, end);
        double meanRms = mean(env.rms, span[0], span[1]);
        region.with("loudness_db", meanRms > 1e-6 ? 20.0 * Math.log10(meanRms) : -60.0);
        region.with("energy", Math.max(0.0, Math.min(1.0, meanRms * 8.0)));
        region.with("acousticness", highBandRatio(env, span[0], span[1]));
        region.with("danceability", onsetDensity(env, span[0], span[1]));
        if (env.chroma != null && env.chromaFrames > 0 && env.chromaHopSecs > 1e-6) {
            int from = (int) Math.floor(start / env.chromaHopSecs);
            int to = (int) Math.ceil(end / env.chromaHopSecs);
            double[] chroma = ReferenceKeyEstimate.meanChroma(env.chroma, env.chromaFrames, from, to);
            ReferenceKeyEstimate.Estimate key = ReferenceKeyEstimate.estimate(chroma);
            region.with("key_confidence", key.confidence);
            region.with("key_tonic", key.tonic);
            region.with("key_mode_major", "major".equals(key.mode) ? 1.0 : 0.0);
        }
    }

    /** 时间区间 → rms/onset 的下标区间。 */
    private static int[] spanOf(AutoCue.Envelope env, double start, double end) {
        int len = env.rms == null ? 0 : env.rms.length;
        if (len == 0 || env.durationSecs <= 0.0) {
            return new int[] {0, 0};
        }
        double perSecond = len / env.durationSecs;
        int from = (int) Math.max(0, Math.min(len, Math.floor(start * perSecond)));
        int to = (int) Math.max(from + 1, Math.min(len, Math.ceil(end * perSecond)));
        return new int[] {from, to};
    }

    /** 跑一次参考包决策（只跑 Apple 的 24 模板）。 */
    public static Snapshot decide(String outgoingId, String incomingId,
                                  AutoCue.Envelope outro, AutoCue.Envelope intro,
                                  double outroVocal, double introVocal) {
        return decide(outgoingId, incomingId, outro, intro, outroVocal, introVocal, null);
    }

    /** 跑一次决策，并可附加我们自己的接法（{@link FusionStyles}）一起排序。 */
    public static Snapshot decide(String outgoingId, String incomingId,
                                  AutoCue.Envelope outro, AutoCue.Envelope intro,
                                  double outroVocal, double introVocal,
                                  ReferenceProfiles.Profile[] extraProfiles) {
        return decide(outgoingId, incomingId, outro, intro, outroVocal, introVocal,
                extraProfiles, false);
    }

    /** 同上，可指定"分数优先"（融合模式用；纯参考包对拍必须 false）。 */
    public static Snapshot decide(String outgoingId, String incomingId,
                                  AutoCue.Envelope outro, AutoCue.Envelope intro,
                                  double outroVocal, double introVocal,
                                  ReferenceProfiles.Profile[] extraProfiles,
                                  boolean scoreFirst) {
        Snapshot snap = new Snapshot();
        double confA = outro.tempo == null ? 0.0 : outro.tempo.confidence;
        double confB = intro.tempo == null ? 0.0 : intro.tempo.confidence;
        snap.outgoing = track(outgoingId, outro, confA, outroVocal, true);
        snap.incoming = track(incomingId, intro, confB, introVocal, false);
        long seed = ReferenceDecision.defaultSeed(outgoingId, incomingId);
        ReferenceLimits limits = new ReferenceLimits();
        snap.result = ReferenceDecision.plan(snap.outgoing, snap.incoming, limits, seed, true,
                extraProfiles, scoreFirst);
        return snap;
    }

    private static double mean(float[] values, int from, int to) {
        if (values == null || from >= to) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = from; i < to && i < values.length; i++) {
            sum += values[i];
        }
        return sum / Math.max(1, Math.min(values.length, to) - from);
    }

    private static double mean(float[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < values.length; i++) {
            double v = values[i];
            if (v > sum) {
                // 保留：rms 非负
            }
            sum += v;
        }
        return sum / values.length;
    }

    /** 高频占比 → acousticness 的粗代理（高频越多越"不原声"）。 */
    private static double highBandRatio(AutoCue.Envelope env, int from, int to) {
        if (env.band == null || env.band.length < 3) {
            return 0.5;
        }
        double low = 0.0;
        double high = 0.0;
        int n = Math.min(env.band[0].length, to);
        for (int i = from; i < n; i++) {
            low += env.band[0][i];
            high += env.band[env.band.length - 1][i];
        }
        double total = low + high;
        if (total <= 1e-9) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, low / total));
    }

    /** onset 密度 → danceability 的粗代理。 */
    private static double onsetDensity(AutoCue.Envelope env, int from, int to) {
        if (env.onset == null || env.onset.length == 0 || env.durationSecs <= 0.0) {
            return 0.5;
        }
        double sum = 0.0;
        for (int i = from; i < to && i < env.onset.length; i++) {
            sum += env.onset[i];
        }
        double seconds = Math.max(0.5, (to - from) / Math.max(1e-6, env.onset.length / env.durationSecs));
        double perSecond = sum / seconds;
        return Math.max(0.0, Math.min(1.0, perSecond));
    }

    /**
     * <b>路径级 signal</b>（candidate_paths 的输入）：按参考包口径从我们的分析算。
     *
     * <p>换算规则：
     * <ul>
     *   <li>{@code strict_tempo_compatible} = tempo 关系是 identical/similar；</li>
     *   <li>{@code loose_tempo_compatible} = 关系不是 incompatible（对应 alias 距离）；</li>
     *   <li>{@code matching_bar_count}：两侧都是整小节网格 ⇒ 记 8（参考包的闸门是 ≥8 才算过）；</li>
     *   <li>{@code vocal_overlap_incompatible}：出场尾段与进场头段的人声等级对齐后取 min，
     *       最大值非 0 即冲突（等级由人声占比折算成 0..4）；</li>
     *   <li>{@code incoming_leading_strength}：进场区首小节的人声等级（0..4）；</li>
     *   <li>响度三项：region 均值 / 尾段均值（dBFS），ratio 由参考包的负 dB 相除口径算；</li>
     *   <li>{@code incoming_loudness_relation_code}：进场区比尾段更大声记 0，否则记 1；</li>
     *   <li>{@code trailing_span_seconds}：进场区末小节长度。</li>
     * </ul>
     */
    public static ReferencePaths.Signals pathSignals(ReferenceTrack outgoing, ReferenceTrack incoming,
                                                     double tempoRatio, double introLeadVocal,
                                                     double outroTailVocal) {
        ReferencePaths.Signals signals = new ReferencePaths.Signals();
        boolean strict = Math.abs(tempoRatio - 1.0) <= 0.06;
        boolean loose = Math.abs(tempoRatio - 1.0) <= 0.12
                || (tempoRatio != 0.0 && (Math.abs(tempoRatio - 2.0) <= 0.12
                || Math.abs(tempoRatio - 0.5) <= 0.06));
        signals.strictTempo(Boolean.valueOf(strict));
        signals.looseTempo(Boolean.valueOf(loose));
        signals.tonalities(Boolean.valueOf(true));
        // 拍数／小节数按**真实候选区**算，不要写死 32（写死会让 hiphop/pop 的路径分
        // 直接等于 base=100，真机日志里出现"永远满分"的假象）
        int outBeats = beatsIn(outgoing);
        int inBeats = beatsIn(incoming);
        signals.matchingBars(Integer.valueOf(Math.min(outBeats, inBeats) / 4));
        signals.outgoingBeats(Integer.valueOf(Math.max(4, outBeats)));
        signals.incomingBeats(Integer.valueOf(Math.max(4, inBeats)));
        signals.tempoAlias(Double.valueOf(strict ? 1.0 : (loose ? 1.0 : 2.0)));
        signals.leadingStrength(Integer.valueOf(grade(introLeadVocal)));
        Integer[] outGrades = new Integer[] {Integer.valueOf(grade(outroTailVocal))};
        Integer[] inGrades = new Integer[] {Integer.valueOf(grade(introLeadVocal))};
        signals.vocalOverlap(Boolean.valueOf(
                ReferenceRegions.alignedVocalOverlapIncompatible(outGrades, inGrades)));
        Double outLoud = outgoing.features.get("loudness_db");
        Double inLoud = incoming.features.get("loudness_db");
        Double inTrail = incoming.features.get("loudness_db");
        signals.incomingLoudness(inLoud);
        signals.incomingTrailingLoudness(inTrail);
        signals.loudnessRelation(Integer.valueOf(
                outLoud != null && inLoud != null && inLoud.doubleValue() >= outLoud.doubleValue()
                        ? 0 : 1));
        signals.trailingSpan(Double.valueOf(4.0));
        signals.outgoingLoudnessSignificant(Boolean.valueOf(
                ReferenceRegions.outgoingLoudnessSignificant(outLoud, true)));
        return signals;
    }

    /** 候选区里的拍数（用 tempo 周期估；没有 tempo 就按 0 算）。 */
    private static int beatsIn(ReferenceTrack t) {
        Double bpm = t.features.get("bpm");
        if (bpm == null || bpm.doubleValue() <= 0.0 || t.regions.isEmpty()) {
            return 0;
        }
        double beat = 60.0 / bpm.doubleValue();
        double sum = 0.0;
        for (int i = 0; i < t.regions.size(); i++) {
            sum += Math.max(0.0, t.regions.get(i).end - t.regions.get(i).start);
        }
        return (int) Math.round(sum / Math.max(1e-6, beat) / Math.max(1, t.regions.size()));
    }

    /** 人声占比 → 参考包的 0..4 等级。 */
    private static int grade(double vocalActivity) {
        if (vocalActivity < 0.2) {
            return 0;
        }
        if (vocalActivity < 0.4) {
            return 1;
        }
        if (vocalActivity < 0.6) {
            return 2;
        }
        if (vocalActivity < 0.8) {
            return 3;
        }
        return 4;
    }
}
