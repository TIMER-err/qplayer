package dev.t1m3.qplayer.audio.mix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * <b>Apple AutoMix 的"路径 × 模板"选择</b>（照参考包 {@code automix-c} 搬过来的那一层）。
 *
 * <h3>为什么需要这一层</h3>
 *
 * <p>我们原来只有"一种混法 + 一个窗口"：点位由七项加权和选出，窗口由降速斜坡决定，
 * 闸门只是事后否决。参考包不是这个形状——它先按 <b>11 条路径</b>（path）各自生成候选，
 * 每条路径<b>自带参数模板</b>（窗口缩放、是否对拍、是否移调、效果器），
 * 然后<b>逐条过闸门</b>，最后在 <b>preferred tier</b> 里按 {@code trunc(score×1000)}
 * 加权取模抽一条（seed 稳定）。用户 2026-11-20：「我大部分接法抄的是 Apple 的，
 * 你把我们缺少的 Apple 的东西补上」——缺的就是这一层。
 *
 * <h3>闸门（与参考包同义，全部是"不满足就淘汰"，不做分数补偿）</h3>
 *
 * <ul>
 *   <li>{@code strict_tempo}：|BPM 比 − 1| ≤ 6% ⇒ 允许"对拍长混"这类路径</li>
 *   <li>{@code loose_tempo}：≤ 12% ⇒ 只允许不做对拍的路径（短交叉 / 留白）</li>
 *   <li>{@code tonality}：|移调| ≤ 5 半音才允许"移调后混"；≤ 2 半音才允许真的移调
 *       （超过 2 半音会把瞬态糊掉，见 {@code VocalAwarePitchTransition} 的上限）</li>
 *   <li>{@code bar_match}：A、B 的小节数必须相等才允许"按小节对齐"的路径</li>
 *   <li>{@code vocal_overlap}：叠唱 &gt; 0.5 s 时淘汰"直接混"这一族（人声会撞）</li>
 *   <li>{@code loudness}：两侧响度差 &gt; 6 dB 时淘汰"直接混"，留给能补电平的路径</li>
 * </ul>
 *
 * <p><b>纪律</b>：本类是纯函数（不碰音频、不碰设置），只决定"这一对该走哪条路"。
 * 它返回的模板由调用方（{@code AutoCueTransition}）落实成窗口 / 变速 / 移调开关，
 * 所以任何一处判据都可以被单测钉住。
 */
public final class MixPaths {

    private MixPaths() {
    }

    /** 参考包的 11 条路径（我们用到的子集，名字与参考包一致）。 */
    public enum Id {
        /** 舞曲：原调直接混（对拍）。 */
        DANCE_ORIGINAL,
        /** 舞曲：移调后混（对拍）。 */
        DANCE_SHIFTED,
        /** 滤波展开：原调（不做对拍）。 */
        FILTERED_DIRECT,
        /** 滤波展开：移调。 */
        FILTERED_SHIFTED,
        /** Hip-hop：原调（对拍）。 */
        HIPHOP_DIRECT,
        /** Hip-hop：移调。 */
        HIPHOP_SHIFTED,
        /** 流行：B 侧缩放 + 移调。 */
        POP_SCALED_SHIFTED,
        /** 智能短交叉（不做对拍、不做 DSP）。 */
        SMART_CROSSFADE,
        /** 淡出留白再接（dead air）。 */
        DEAD_AIR,
        /** 兜底短交叉。 */
        FALLBACK_CROSSFADE,
        /** 直接跳过（不融合）。 */
        SOFT_SKIP
    }

    /**
     * 一条路径的参数模板。
     *
     * <p>{@code windowScale} 是"相对用户设定窗口的缩放"（参考包里每条路径都有自己的
     * preferred 窗口）；{@code beatMatched} 决定允不允许变速；{@code pitchShift}
     * 决定允不允许 {@code 声波重合} 那一层动移调。
     */
    public static final class Template {
        public final Id id;
        public final double baseScore;
        public final double windowScale;
        public final double maxWindowSecs;
        public final boolean beatMatched;
        public final boolean pitchShift;
        public final String effect;
        /** 湿声（混响/延迟）占比：参考包每条模板都有自己的一档（0 = 干声）。 */
        public final double reverbWet;
        /** 滤波斜率（dB/倍频程，0 = 不做滤波展开）。 */
        public final double filterSlope;
        /** 出场侧 riding 曲线名（参考包的 riding 规则族）。 */
        public final String riding;

        Template(Id id, double baseScore, double windowScale, double maxWindowSecs,
                 boolean beatMatched, boolean pitchShift, String effect,
                 double reverbWet, double filterSlope, String riding) {
            this.id = id;
            this.baseScore = baseScore;
            this.windowScale = windowScale;
            this.maxWindowSecs = maxWindowSecs;
            this.beatMatched = beatMatched;
            this.pitchShift = pitchShift;
            this.effect = effect;
            this.reverbWet = reverbWet;
            this.filterSlope = filterSlope;
            this.riding = riding;
        }
    }

    /** 24 个模板里我们实际用到的 11 条（base_score 取参考包同族的中位值）。 */
    public static final Template[] TEMPLATES = {
            new Template(Id.DANCE_ORIGINAL, 0.95, 1.00, 18.0, true, false, "crossfade", 0.15, 0.0, "slow-release"),
            new Template(Id.DANCE_SHIFTED, 0.90, 1.00, 18.0, true, true, "crossfade+pitch", 0.15, 0.0, "slow-release"),
            new Template(Id.FILTERED_DIRECT, 0.85, 1.20, 12.0, false, false, "filter-sweep", 0.05, -12.0, "filter-rise"),
            new Template(Id.FILTERED_SHIFTED, 0.82, 1.20, 12.0, false, true, "filter-sweep+pitch", 0.05, -12.0, "filter-rise"),
            new Template(Id.HIPHOP_DIRECT, 0.88, 0.75, 10.0, true, false, "crossfade", 0.10, 0.0, "tight-cut"),
            new Template(Id.HIPHOP_SHIFTED, 0.84, 0.75, 10.0, true, true, "crossfade+pitch", 0.10, 0.0, "tight-cut"),
            new Template(Id.POP_SCALED_SHIFTED, 0.80, 1.00, 16.0, false, true, "scaled+pitch", 0.20, -6.0, "soft-rise"),
            new Template(Id.SMART_CROSSFADE, 0.92, 0.50, 6.0, false, false, "crossfade", 0.20, 0.0, "symmetric"),
            new Template(Id.DEAD_AIR, 0.55, 0.30, 2.0, false, false, "fade-out", 0.35, 0.0, "tail-out"),
            new Template(Id.FALLBACK_CROSSFADE, 0.70, 0.35, 2.0, false, false, "crossfade", 0.25, 0.0, "symmetric"),
            new Template(Id.SOFT_SKIP, 0.30, 0.00, 0.0, false, false, "skip", 0.0, 0.0, "none"),
    };

    /** 一对歌的特征（全部由调用方按已有分析填，缺的给 0 / 中性值）。 */
    public static final class Features {
        public double tempoRatio = 1.0;      // BPM_B / BPM_A
        public double semitones = 0.0;       // 需要的移调量（绝对值使用）
        public int barsA = 0;                // A 的乐句小节数（0 = 未知）
        public int barsB = 0;                // B 的乐句小节数（0 = 未知）
        public double vocalOverlapSecs = 0.0;
        public double loudnessDeltaDb = 0.0;
    }

    /** 被选中（或被淘汰）的一条路径。 */
    public static final class Choice {
        public final Template template;
        public final double score;
        public final String verdict;   // 空 = 通过；否则是被淘汰的原因

        Choice(Template template, double score, String verdict) {
            this.template = template;
            this.score = score;
            this.verdict = verdict;
        }
    }

    public static final double STRICT_TEMPO = 0.06;
    public static final double LOOSE_TEMPO = 0.12;
    public static final double MAX_PITCH_SEMITONES = 5.0;
    public static final double SAFE_PITCH_SEMITONES = 2.0;
    public static final double MAX_VOCAL_OVERLAP_SECS = 0.5;
    public static final double MAX_LOUDNESS_DELTA_DB = 6.0;
    /** 同层容差（参考包按 tier 分组；我们按分数比例）。 */
    public static final double TIER_FRACTION = 0.02;

    /** 跑一遍闸门，返回全部 11 条路径各自的结果（通过或淘汰原因）。 */
    public static List<Choice> evaluate(Features f) {
        List<Choice> out = new ArrayList<Choice>();
        double dev = Math.abs(f.tempoRatio - 1.0);
        double semis = Math.abs(f.semitones);
        boolean barMatch = f.barsA > 0 && f.barsB > 0 && f.barsA == f.barsB;
        for (Template t : TEMPLATES) {
            String no = null;
            switch (t.id) {
                case SOFT_SKIP:
                    break;      // 永远可用（最后一条退路）
                case DEAD_AIR:
                    break;      // 淡出留白对任何曲对都成立
                case FALLBACK_CROSSFADE:
                    break;      // 兜底短交叉同理
                case SMART_CROSSFADE:
                    no = f.vocalOverlapSecs > MAX_VOCAL_OVERLAP_SECS ? "叠唱超限" : null;
                    break;
                default:
                    if (t.beatMatched && dev > STRICT_TEMPO) {
                        no = String.format(java.util.Locale.ROOT, "对拍差 %.1f%%", dev * 100.0);
                    } else if (!t.beatMatched && dev > LOOSE_TEMPO) {
                        no = String.format(java.util.Locale.ROOT, "拍速差 %.1f%%", dev * 100.0);
                    } else if (t.pitchShift && semis > MAX_PITCH_SEMITONES) {
                        no = String.format(java.util.Locale.ROOT, "移调 %.1f 半音太大", semis);
                    } else if (!t.pitchShift && semis > SAFE_PITCH_SEMITONES) {
                        no = String.format(java.util.Locale.ROOT, "调性差 %.1f 半音且这条不移调", semis);
                    } else if (!barMatch && t.windowScale >= 1.0) {
                        no = "小节数对不上";
                    } else if (f.vocalOverlapSecs > MAX_VOCAL_OVERLAP_SECS && t.beatMatched) {
                        no = "叠唱超限";
                    } else if (Math.abs(f.loudnessDeltaDb) > MAX_LOUDNESS_DELTA_DB && t.beatMatched) {
                        no = String.format(java.util.Locale.ROOT, "响度差 %.1f dB", f.loudnessDeltaDb);
                    }
                    break;
            }
            out.add(new Choice(t, scoreOf(t, f), no));
        }
        return out;
    }

    /** 分数 = base_score 乘上几个"越合越加分"的因子（参考包的 scoring.py 同义）。 */
    static double scoreOf(Template t, Features f) {
        double tempoFit = 1.0 - Math.min(1.0, Math.abs(f.tempoRatio - 1.0) / LOOSE_TEMPO) * 0.35;
        double keyFit = 1.0 - Math.min(1.0, Math.abs(f.semitones) / MAX_PITCH_SEMITONES) * 0.25;
        double barFit = (f.barsA > 0 && f.barsB > 0 && f.barsA == f.barsB) ? 1.0 : 0.9;
        double vocalFit = 1.0 - Math.min(1.0, f.vocalOverlapSecs / 2.0) * 0.30;
        double loudFit = 1.0 - Math.min(1.0, Math.abs(f.loudnessDeltaDb) / 12.0) * 0.20;
        double q = tempoFit * keyFit * barFit * vocalFit * loudFit;
        if (t.id == Id.SOFT_SKIP) {
            return t.baseScore;      // 退路不参与这些因子（它本来就不处理音频）
        }
        return t.baseScore * q;
    }

    /** 选中结果：命中的路径 + 同层候选数（给日志用）。 */
    public static final class Pick {
        public final Template template;
        public final double score;
        public final int layerSize;
        public final String passed;   // 通过闸门的路径名（逗号分隔）
        public final String rejected; // 被淘汰的（名字:原因）

        Pick(Template template, double score, int layerSize, String passed, String rejected) {
            this.template = template;
            this.score = score;
            this.layerSize = layerSize;
            this.passed = passed;
            this.rejected = rejected;
        }
    }

    /**
     * 三层选择的收口：<b>淘汰 → 分 tier → 同层加权取模</b>。
     *
     * <p>{@code seed} 由调用方用"出场句柄 + 进场句柄 + 窗口"算（同一对歌结果稳定，
     * 不同对歌不撞点），与参考包的 weighted modulo 同义。
     */
    public static Pick choose(Features f, long seed) {
        List<Choice> all = evaluate(f);
        List<Choice> ok = new ArrayList<Choice>();
        StringBuilder passed = new StringBuilder();
        StringBuilder rejected = new StringBuilder();
        for (Choice c : all) {
            if (c.verdict == null) {
                ok.add(c);
                if (passed.length() > 0) {
                    passed.append(',');
                }
                passed.append(c.template.id.name().toLowerCase(java.util.Locale.ROOT));
            } else {
                if (rejected.length() > 0) {
                    rejected.append(';');
                }
                rejected.append(c.template.id.name().toLowerCase(java.util.Locale.ROOT))
                        .append(':').append(c.verdict);
            }
        }
        if (ok.isEmpty()) {
            Template skip = TEMPLATES[TEMPLATES.length - 1];
            return new Pick(skip, skip.baseScore, 0, passed.toString(), rejected.toString());
        }
        Collections.sort(ok, new Comparator<Choice>() {
            @Override
            public int compare(Choice a, Choice b) {
                return Double.compare(b.score, a.score);
            }
        });
        double top = ok.get(0).score;
        double floor = top * (1.0 - TIER_FRACTION);
        List<Choice> layer = new ArrayList<Choice>();
        for (Choice c : ok) {
            if (c.score >= floor) {
                layer.add(c);
            }
        }
        long total = 0L;
        for (Choice c : layer) {
            total += Math.max(1L, Math.round(c.score * 1000.0));
        }
        long draw = Math.floorMod(seed, total);
        Choice picked = layer.get(layer.size() - 1);
        for (Choice c : layer) {
            draw -= Math.max(1L, Math.round(c.score * 1000.0));
            if (draw < 0L) {
                picked = c;
                break;
            }
        }
        return new Pick(picked.template, picked.score, layer.size(),
                passed.toString(), rejected.toString());
    }
}
