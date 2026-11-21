package dev.t1m3.qplayer.audio.mashup;

import dev.t1m3.qplayer.audio.mix.Harmonic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>两首歌能不能做成一首 mashup</b>（mashup 第二阶段）：先在纸上淘汰，再花几分钟渲染。
 *
 * <h2>闸门用的是仓库已有的数字</h2>
 *
 * <p>速度 0.12（{@code MixPaths.LOOSE_TEMPO}）、调性相似度 0.72（{@code Harmonic.MATCH_MIN_SCORE}）、
 * 移调上限 3 半音（{@code Harmonic.MAX_SHIFT}）、"舒服"的移调 2 半音
 * （{@code MixPaths.SAFE_PITCH_SEMITONES}）——<b>不另立一套</b>。
 * 唯一的自造数是最小可用时长 75 s 与 0.08 的"可整曲对拍"线（原因见设计文档 §7.2/§7.4：
 * 整曲长时间变速超过 ±8% 会被听成拖沓）。
 *
 * <h2>速度"对半"为什么在闸门里做</h2>
 *
 * <p>87 BPM 与 174 BPM 的两首歌，直接按 {@code bpm_b/bpm_a=2} 判断会当场被淘汰；
 * 但音乐上它们是同一速度（half-time）。所以先试三种锚法——逐拍、B 用每两拍、A 用每两拍
 * ——取最接近 1 的那个。{@link Result#aStride}/{@link Result#bStride} 把结论带出去，
 * 规划层照着建锚点，不需要再猜一次。
 *
 * <h2>置信度只降权、不做硬闸门</h2>
 *
 * <p>{@code Analysis.Tempo.confidence} 是<b>四把不同的尺子共用一个字段名</b>
 * （设计文档 G8：旧路 主峰/均值/8、新路 |ac|/能量、{@code BeatTracker} 1−残差/0.25、
 * 模型路 1−MAD/周期）。在拿不到"这是哪把尺子"的前提下，用它做硬闸门会误杀，
 * 所以它只乘一个 0.7~1.0 的系数。
 */
public final class MashupMatcher {

    /** 速度差超过这个值直接拒绝（与 {@code MixPaths.LOOSE_TEMPO} 同值）。 */
    public static final double TEMPO_REJECT = 0.12;

    /** 整曲对拍的速度上限：超过它只允许 ≤90 s 的短输出（设计文档 §7.4）。 */
    public static final double TEMPO_RENDERABLE = 0.08;

    /** 调性相似度下限（与 {@code Harmonic.MATCH_MIN_SCORE} 同值）。 */
    public static final double MIN_COSINE = 0.72;

    /** 允许移调的最大半音数（与 {@code Harmonic.MAX_SHIFT} 同值）。 */
    public static final int MAX_SHIFT = 3;

    /** "听不出共振峰偏移"的移调上限（与 {@code MixPaths.SAFE_PITCH_SEMITONES} 同值）。 */
    public static final int SAFE_SHIFT = 2;

    /** 最小可用时长（秒）：太短的歌拼不出成品。 */
    public static final double MIN_DURATION_SECS = 75.0;

    private MashupMatcher() {
    }

    /**
     * 配对结论。
     *
     * <p>{@link #verdict} 为空表示通过；否则是<b>一句话原因</b>（和 {@code PrepareStage}
     * 一样的口径：用户念出来就能定位）。
     */
    public static final class Result {

        /** 能不能做。 */
        public final boolean accepted;

        /** 不通过的原因（通过时为空串）。 */
        public final String verdict;

        /** 总分（0..1，只用于同几对之间排序）。 */
        public final double score;

        /** A 人声要播的速度（1.0 = 原速）。 */
        public final double tempoRatio;

        /** A 的拍锚点用"每 aStride 拍"（1 或 2，half-time 时为 2）。 */
        public final int aStride;

        /** B 的拍锚点用"每 bStride 拍"（1 或 2）。 */
        public final int bStride;

        /** 建议给 A 的人声移调（半音，正 = 升高）。 */
        public final int semitones;

        /** 四个分项（0..1）。 */
        public final double tempoFit;
        public final double keyFit;
        public final double vocalFit;
        public final double structureFit;

        /** A 与 B 的响度差（dB；任一未知时为 NaN）。 */
        public final double loudnessDeltaDb;

        /** 需要让用户知道的事（降权原因、未知项…），空列表 = 没有。 */
        public final List<String> notes;

        Result(boolean accepted, String verdict, double score, double tempoRatio,
               int aStride, int bStride, int semitones, double tempoFit, double keyFit,
               double vocalFit, double structureFit, double loudnessDeltaDb,
               List<String> notes) {
            this.accepted = accepted;
            this.verdict = verdict;
            this.score = score;
            this.tempoRatio = tempoRatio;
            this.aStride = aStride;
            this.bStride = bStride;
            this.semitones = semitones;
            this.tempoFit = tempoFit;
            this.keyFit = keyFit;
            this.vocalFit = vocalFit;
            this.structureFit = structureFit;
            this.loudnessDeltaDb = loudnessDeltaDb;
            this.notes = notes;
        }

        /** 速度差是否小到可以整曲对拍（否则只能出短版本）。 */
        public boolean renderableWholeTrack() {
            return accepted && Math.abs(tempoRatio - 1.0) <= TEMPO_RENDERABLE;
        }

        @Override
        public String toString() {
            if (!accepted) {
                return "MashupMatch{拒绝：" + verdict + "}";
            }
            return String.format(Locale.ROOT,
                    "MashupMatch{通过 score=%.3f, 速度 %.4f×（步长 %d/%d）, 移调 %+d, "
                            + "分项 速度%.2f/调性%.2f/人声%.2f/结构%.2f}",
                    score, tempoRatio, aStride, bStride, semitones,
                    tempoFit, keyFit, vocalFit, structureFit);
        }
    }

    /**
     * 配对：{@code a} 出人声、{@code b} 出伴奏。
     *
     * <p>方向是有意义的：人声轨要变速/移调（A），伴奏轨保持原样（B）。
     */
    public static Result match(TrackProfile a, TrackProfile b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("两首歌的档案都不能为空");
        }
        List<String> notes = new ArrayList<>();

        // ---- 闸门 1：时长 -------------------------------------------------
        double shorter = Math.min(a.durationSecs, b.durationSecs);
        if (shorter < MIN_DURATION_SECS) {
            return reject(String.format(Locale.ROOT,
                    "太短：min(%.0f, %.0f) = %.0f s < %.0f s",
                    a.durationSecs, b.durationSecs, shorter, MIN_DURATION_SECS));
        }

        // ---- 闸门 2：速度（含 half-time） ---------------------------------
        if (!a.hasTempo()) {
            return reject("A 没有可用的拍点结论");
        }
        if (!b.hasTempo()) {
            return reject("B 没有可用的拍点结论");
        }
        double raw = b.bpm / a.bpm;
        double ratio = raw;
        int aStride = 1;
        int bStride = 1;
        if (Math.abs(raw / 2.0 - 1.0) < Math.abs(ratio - 1.0)) {
            ratio = raw / 2.0;
            bStride = 2;
        }
        if (Math.abs(raw * 2.0 - 1.0) < Math.abs(ratio - 1.0)) {
            ratio = raw * 2.0;
            aStride = 2;
        }
        double tempoDeviation = Math.abs(ratio - 1.0);
        if (tempoDeviation > TEMPO_REJECT) {
            return reject(String.format(Locale.ROOT,
                    "速度差 %.1f%% 超过 %.0f%%（A %.1f BPM，B %.1f BPM）",
                    tempoDeviation * 100.0, TEMPO_REJECT * 100.0, a.bpm, b.bpm));
        }
        if (aStride != 1 || bStride != 1) {
            notes.add(String.format(Locale.ROOT,
                    "按 half-time 对拍（A 用每 %d 拍、B 用每 %d 拍）", aStride, bStride));
        }
        double tempoFit = 1.0 - Math.min(1.0, tempoDeviation / TEMPO_REJECT);

        // ---- 闸门 3：调性 -------------------------------------------------
        int semitones = 0;
        double keyFit;
        if (!a.hasKey() || !b.hasKey()) {
            keyFit = 0.5;
            notes.add("调性未知，不做移调（相似度按中性 0.5 记）");
            if (a.keyTonic >= 0 && b.keyTonic >= 0) {
                int shift = relativeSemitones(a.keyTonic, b.keyTonic);
                if (Math.abs(shift) > MAX_SHIFT) {
                    return reject(String.format(Locale.ROOT,
                            "调性差距 %+d 半音，超过 ±%d", shift, MAX_SHIFT));
                }
                semitones = shift;
            }
        } else {
            double best = -1;
            int bestShift = 0;
            for (int s = -MAX_SHIFT; s <= MAX_SHIFT; s++) {
                double c = cosine(Harmonic.rotate(a.meanChroma, s), b.meanChroma);
                if (c > best) {
                    best = c;
                    bestShift = s;
                }
            }
            if (a.keyTonic >= 0 && b.keyTonic >= 0) {
                int tonalShift = relativeSemitones(a.keyTonic, b.keyTonic);
                if (Math.abs(tonalShift) <= MAX_SHIFT) {
                    double c = cosine(Harmonic.rotate(a.meanChroma, tonalShift), b.meanChroma);
                    // 主音给的结论解释性更强；相似度只要不明显更差就用它
                    if (c >= best - 0.05) {
                        best = c;
                        bestShift = tonalShift;
                    }
                }
            }
            if (best < MIN_COSINE) {
                return reject(String.format(Locale.ROOT,
                        "调性不兼容（最佳相似度 %.2f < %.2f）", best, MIN_COSINE));
            }
            semitones = bestShift;
            keyFit = best;
            if (Math.abs(bestShift) > SAFE_SHIFT) {
                keyFit *= 0.85;
                notes.add("要移调 " + Math.abs(bestShift) + " 半音，超过「舒适」的 "
                        + SAFE_SHIFT + " 半音");
            }
            if (!a.keyMode.isEmpty() && !b.keyMode.isEmpty()
                    && !a.keyMode.equals(b.keyMode)) {
                keyFit *= 0.9;
                notes.add("调式不同（" + a.keyMode + " vs " + b.keyMode + "）");
            }
        }

        // ---- 分项 4：B 的人声残量 -----------------------------------------
        double vocalFit = 1.0 - clamp01(b.vocalShare);
        if (b.vocalShare > 0.5) {
            notes.add(String.format(Locale.ROOT,
                    "B 有 %.0f%% 的时长在唱，分轨后可能残留人声", b.vocalShare * 100.0));
        }

        // ---- 分项 5：结构点 -----------------------------------------------
        boolean hasStructure = a.structureOutroSecs >= 0 || b.structureIntroSecs >= 0;
        double structureFit = hasStructure ? 1.0 : 0.6;
        if (!hasStructure) {
            notes.add("两侧都没有结构点，选段只能靠能量代理");
        }

        // ---- 响度（只降权） -----------------------------------------------
        double loudnessDelta = Double.NaN;
        double loudnessPenalty = 1.0;
        if (Double.isFinite(a.lufs) && Double.isFinite(b.lufs)) {
            loudnessDelta = a.lufs - b.lufs;
            if (Math.abs(loudnessDelta) > 6.0) {
                loudnessPenalty = 0.9;
                notes.add(String.format(Locale.ROOT, "响度差 %.1f dB 偏大（> 6 dB）",
                        loudnessDelta));
            }
        } else {
            notes.add("响度未知（近似 LUFS 没算成）");
        }

        // ---- 置信度（只降权） ---------------------------------------------
        double confidence = Math.min(a.beatConfidence, b.beatConfidence);
        double confidenceFactor = 0.7 + 0.3 * clamp01(confidence / 0.5);
        if (confidence < 0.2) {
            notes.add(String.format(Locale.ROOT,
                    "拍点置信度低（%.2f），对拍结果可能不稳", confidence));
        }

        double score = confidenceFactor * loudnessPenalty
                * (0.45 * tempoFit + 0.35 * keyFit + 0.10 * vocalFit + 0.10 * structureFit);
        return new Result(true, "", score, ratio, aStride, bStride, semitones,
                tempoFit, keyFit, vocalFit, structureFit, loudnessDelta, notes);
    }

    /**
     * 两个主音之间的相对半音（结果落在 {@code [-6, 5]}：升 5 与降 7 是同一个键，
     * 取绝对值更小的那个方向）。
     */
    static int relativeSemitones(int aTonic, int bTonic) {
        int raw = Math.floorMod(bTonic - aTonic, 12);
        return raw > 6 ? raw - 12 : raw;
    }

    private static Result reject(String verdict) {
        return new Result(false, verdict, 0.0, 1.0, 1, 1, 0,
                0.0, 0.0, 0.0, 0.0, Double.NaN, new ArrayList<>());
    }

    private static double cosine(float[] x, float[] y) {
        if (x == null || y == null || x.length != y.length) {
            return 0.0;
        }
        double dot = 0;
        double nx = 0;
        double ny = 0;
        for (int i = 0; i < x.length; i++) {
            dot += (double) x[i] * y[i];
            nx += (double) x[i] * x[i];
            ny += (double) y[i] * y[i];
        }
        if (!(nx > 0) || !(ny > 0)) {
            return 0.0;
        }
        return dot / (Math.sqrt(nx) * Math.sqrt(ny));
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
