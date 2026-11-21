package dev.t1m3.qplayer.audio.mix;

/**
 * <b>Apple automix 的自动化段表（照搬依据）</b> —— 由
 * {@code /home/strokeshading/Documents/automix-c/assets/TransitionStyles.json} 生成 ✓。
 *
 * <p>用户 2026-10-08：「完全照搬他的写法」✓。他每档 = 命名操作串 + 参数自动化 ✓；
 * 自动化是**分段**的（起止值 ✓、起止时间 ✓、插值 ✓），而且：
 *  · 时间既可能是 {@code relative}（0..1 的相对进度 ✓），也可能是
 *    {@code offsetInSeconds}（**绝对秒** ✓，例如 id 33/44 的 send_gain 是 [0, +2 s] ✓、
 *    id 10 的 delay_dry_wet 是 [0, +1 s] ✓）—— 两者都必须支持 ✓（第 23 轮修 ✓）；
 *  · 值也可能是**符号**（例如 {@code fx_delay_delay_time = beat_length} ✓）⇒ 不当数值用 ✗，
 *    另存 {@link Seg#symbol} ✓（第 23 轮修 ✓）。
 *
 * <p>零分配求值 ✓（{@link #valueAt(Auto, float, float)} ✓：绝对秒按窗口时长归一 ✓）。
 */
public final class AppleTransitionSpec {

    private AppleTransitionSpec() {
    }

    /** 插值：0 = linear ✓、1 = ease-out-N ✓、2 = ease-in-N ✓。 */
    public static final class Seg {
        public final float t0;
        public final float t1;
        public final float v0;
        public final float v1;
        /** 绝对秒起点（−1 = 用 {@link #t0} 的相对进度 ✓）✓。 */
        public final float absT0;
        /** 绝对秒终点（−1 = 用 {@link #t1} ✓）✓。 */
        public final float absT1;
        public final int easeKind;
        public final float easeN;
        /** 符号值（如 "beat_length" ✓）；null = 普通数值 ✓。 */
        public final String symbol;

        Seg(float t0, float t1, float v0, float v1, float absT0, float absT1,
                int easeKind, float easeN, String symbol) {
            this.t0 = t0; this.t1 = t1; this.v0 = v0; this.v1 = v1;
            this.absT0 = absT0; this.absT1 = absT1;
            this.easeKind = easeKind; this.easeN = easeN; this.symbol = symbol;
        }
    }

    /** 由外部的"同名合并"构造一条车道 ✓（照搬 goal 第 37 轮 ✓）。 */
    public static Auto merged(String key, Seg[] segs) {
        return new Auto(key, segs);
    }

    /** 一条参数自动化 ✓。 */
    public static final class Auto {
        public final String key;
        public final Seg[] segs;
        Auto(String key, Seg[] segs) { this.key = key; this.segs = segs; }
    }

    /** 一档 ✓。 */
    public static final class Style {
        public final int id;
        public final String name;
        public final float durationSecs;
        public final String[] outgoingOps;
        public final String[] incomingOps;
        public final Auto[] outgoing;
        public final Auto[] incoming;
        Style(int id, String name, float durationSecs, String[] outgoingOps,
                String[] incomingOps, Auto[] outgoing, Auto[] incoming) {
            this.id = id; this.name = name; this.durationSecs = durationSecs;
            this.outgoingOps = outgoingOps; this.incomingOps = incomingOps;
            this.outgoing = outgoing; this.incoming = incoming;
        }
    }

    /** 相对进度求值（绝对秒条目按"窗口 0 秒"处理 ⇒ 保持起点值 ✓；精确求值用三参版 ✓）。 */
    /** **瞬时条目的最短淡出秒数**（用户 2026-10-09 选 A ✓；0 = 关 ⇒ 逐字照搬他的硬切 ✓）。 */
    private static volatile double instantFadeSecs = 0.25;

    public static void setInstantFadeSecs(double secs) {
        instantFadeSecs = Math.max(0.0, secs);
    }

    public static double instantFadeSecs() {
        return instantFadeSecs;
    }

    public static float valueAt(Auto auto, float p) {
        return valueAt(auto, p, 0f);
    }

    /**
     * 求值 ✓：相对条目按 {@code p} ✓；绝对秒条目用 {@code windowSecs} 归一 ✓
     *（{@code p * windowSecs} 与 {@code absT0/absT1} 比较 ✓ —— 他那些 `offsetInSeconds` 是绝对值 ✓）。
     */
    public static float valueAt(Auto auto, float p, float windowSecs) {
        if (auto == null || auto.segs.length == 0) {
            return 0f;
        }
        float x = p < 0f ? 0f : (p > 1f ? 1f : p);
        // ⚠️ **自动化是"车道"语义**（第 37 轮由判据照出来 ✓）：他的很多条目是
        // `startTime == endTime`（**瞬时置值** ✓，例如 repeater_on 在 0.4444 处直接 0→1 ✓）
        // ⇒ 值必须**保持到下一段** ✓，而不是只在 [t0,t1] 内有效 ✗。
        // 所以取"**最后一个 t0 ≤ x 的段**" ✓：落在那段区间内就插值 ✓、过了它的 t1 就保持终值 ✓。
        Seg best = null;
        for (Seg s : auto.segs) {
            float a0 = s.t0 + (windowSecs > 0f ? s.absT0 / windowSecs : 0f);
            float a1 = s.t1 + (windowSecs > 0f ? s.absT1 / windowSecs : 0f);
            if (windowSecs <= 0f && (s.absT0 != 0f || s.absT1 != 0f)) {
                // 没有窗口时长就无从归一 ✓ ⇒ 只认相对时间 ✓（不猜 ✗）
                a0 = s.t0;
                a1 = s.t1;
            }
            if (x >= a0) {
                if (best == null || a0 >= bestStart(best, windowSecs)) {
                    best = s;
                }
            }
        }
        if (best == null) {
            Seg first = auto.segs[0];
            return first.symbol == null ? first.v0 : first.v0;
        }
        float b0 = best.t0 + (windowSecs > 0f ? best.absT0 / windowSecs : 0f);
        float b1 = best.t1 + (windowSecs > 0f ? best.absT1 / windowSecs : 0f);
        if (windowSecs <= 0f && (best.absT0 != 0f || best.absT1 != 0f)) {
            b0 = best.t0;
            b1 = best.t1;
        }
        if (b1 <= b0) {
            // **瞬时条目（起止同刻）⇒ 摊成"最短淡出"**（2026-10-09 ✓，用户选 A ✓）：
            // 他表里有这类条目 ✓（档 16 `Pop - Delay out`：player_gain 在 **relative 0.25**
            // 处 1 → 0 ✓、起止同一时刻 ✓）⇒ 照原样实现就是**一步切到 0** ✗ ⇒ 听感"A 在融合期
            // 突然消失" ✓。这里把它摊成 **MIN_INSTANT_FADE_SECS** 的线性淡出 ✓（默认开 ✓，
            // 可用 setInstantFadeSecs(0) 关掉 ⇒ 回到逐字照搬 ✓）；**线性条目完全不受影响** ✗。
            float span = windowSecs > 0f
                    ? (float) (instantFadeSecs / windowSecs) : 0f;
            boolean gainLane = auto.key != null && auto.key.endsWith("_gain");
            if (gainLane && span > 1e-6f && Math.abs(best.v1 - best.v0) > 1e-6f) {
                float k = (x - b0) / span;
                if (k <= 0f) {
                    return best.v0;
                }
                if (k >= 1f) {
                    return best.v1;
                }
                return best.v0 + k * (best.v1 - best.v0);
            }
            return x >= b1 ? best.v1 : best.v0;   // 没有窗口时长或关掉 ⇒ 与从前逐位相同 ✓
        }
        if (x >= b1) {
            return best.v1;                       // 过了这一段 ⇒ 保持终值 ✓
        }
        float k = (x - b0) / (b1 - b0);
        if (best.easeKind == 1) {
            k = (float) (1.0 - Math.pow(1.0 - k, best.easeN));
        } else if (best.easeKind == 2) {
            k = (float) Math.pow(k, best.easeN);
        }
        return best.v0 + k * (best.v1 - best.v0);
    }

    private static float bestStart(Seg s, float windowSecs) {
        return s.t0 + (windowSecs > 0f ? s.absT0 / windowSecs : 0f);
    }

    /** 24 档全表 ✓。 */
    public static final Style[] STYLES = {
        new Style(0, "Gapless", 0.0f,
                new String[] {"RECEIVE_MIXER"}, new String[] {"RECEIVE_MIXER"},
                new Auto[] {new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 2, 0.5f, null)})},
                new Auto[] {new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1, 0.5f, null)})}),
        new Style(1, "constant-power cross-fade", 0.0f,
                new String[] {"RECEIVE_MIXER"}, new String[] {"RECEIVE_MIXER"},
                new Auto[] {new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 2, 0.5f, null)})},
                new Auto[] {new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 0.5f, null)})}),
        new Style(2, "constant-power long fade-out short fade-in", 0.0f,
                new String[] {"RECEIVE_MIXER"}, new String[] {"RECEIVE_MIXER"},
                new Auto[] {new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 2, 0.5f, null)})},
                new Auto[] {new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 0.5f, null)})}),
        new Style(22, "splice", 0.0f,
                new String[] {}, new String[] {},
                new Auto[] {},
                new Auto[] {}),
        new Style(3, "overlap", 0.0f,
                new String[] {}, new String[] {},
                new Auto[] {},
                new Auto[] {}),
        new Style(33, "overlap + Reverb", 0.0f,
                new String[] {"AUX_REVERB", "SEND_MIXER", "RECEIVE_MIXER"}, new String[] {},
                new Auto[] {new Auto("fx_reverb_high_frequency_decay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.001f, 6.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_low_frequency_decay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.001f, 5.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_min_delay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.008f, 0.9f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_dry_wet", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 100.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("send_mixer_gain", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.75f, 0.0f, 2.0f, 0, 1.0f, null)}), new Auto("fx_mixer_wet", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)})},
                new Auto[] {}),
        new Style(4, "Ease-in Ring-out", 0.0f,
                new String[] {}, new String[] {"RECEIVE_MIXER"},
                new Auto[] {},
                new Auto[] {new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 0.5f, null)})}),
        new Style(44, "Ease-in Ring-out + Reverb", 0.0f,
                new String[] {"AUX_REVERB", "SEND_MIXER"}, new String[] {"RECEIVE_MIXER"},
                new Auto[] {new Auto("fx_reverb_high_frequency_decay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.001f, 6.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_low_frequency_decay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.001f, 5.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_min_delay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.008f, 0.9f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_dry_wet", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 100.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("send_mixer_gain", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.75f, 0.0f, 2.0f, 0, 1.0f, null)}), new Auto("fx_mixer_wet", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)})},
                new Auto[] {new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 0.5f, null)})}),
        new Style(5, "BM - Short filter high to low", 2.0f,
                new String[] {"TIME_STRETCHING", "LOW_PASS", "RECEIVE_MIXER"}, new String[] {"TIME_STRETCHING", "HI_PASS", "LOW_PASS", "RECEIVE_MIXER"},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 2.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.0f, 1.0f, 20000.0f, 200.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0, 1.0f, null)})},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 2.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.0f, 1.0f, 20000.0f, 10.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.0f, 1.0f, 1200.0f, 20000.0f, 0.0f, 0.0f, 2, 4.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)})}),
        new Style(6, "Beat-matched long fade-out short fade-in - Same Bar num", 0.0f,
                new String[] {"TIME_STRETCHING", "RECEIVE_MIXER"}, new String[] {"TIME_STRETCHING", "RECEIVE_MIXER"},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 2, 0.5f, null)})},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 0.5f, null)})}),
        new Style(7, "Beat-matched long fade-out dynamic fade-in", 0.0f,
                new String[] {"TIME_STRETCHING", "RECEIVE_MIXER"}, new String[] {"TIME_STRETCHING", "RECEIVE_MIXER"},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 2.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 2, 0.5f, null)})},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 2.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 0.5f, null)})}),
        new Style(8, "BM - Filter high to low", 8.0f,
                new String[] {"TIME_STRETCHING", "LOW_PASS", "RECEIVE_MIXER"}, new String[] {"TIME_STRETCHING", "HI_PASS", "LOW_PASS", "RECEIVE_MIXER"},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 2.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.0f, 1.0f, 20000.0f, 200.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0, 1.0f, null)})},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 2.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.0f, 1.0f, 20000.0f, 10.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.0f, 1.0f, 1200.0f, 20000.0f, 0.0f, 0.0f, 2, 4.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)})}),
        new Style(9, "BM - Filter expansion", 8.0f,
                new String[] {"TIME_STRETCHING", "LOW_PASS", "HI_PASS", "RECEIVE_MIXER"}, new String[] {"TIME_STRETCHING", "LOW_PASS", "HI_PASS", "RECEIVE_MIXER"},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 2.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.5f, 1.0f, 20000.0f, 20.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.5f, 0.75f, 20.0f, 200.0f, 0.0f, 0.0f, 1, 0.5f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0, 1.0f, null)})},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 2.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.0f, 0.75f, 1000.0f, 20000.0f, 0.0f, 0.0f, 1, 0.5f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.5f, 0.75f, 1000.0f, 20.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)})}),
        new Style(10, "BM - Filter long out short in + delay", 0.0f,
                new String[] {"TIME_STRETCHING", "LOW_PASS", "AUX_DELAY", "SEND_MIXER", "RECEIVE_MIXER", "RECEIVE_MIXER"}, new String[] {"TIME_STRETCHING", "HI_PASS", "RECEIVE_MIXER"},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 2.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.0f, 1.0f, 20000.0f, 200.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("fx_delay_dry_wet", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 100.0f, 0.0f, 1.0f, 1, 2.0f, null)}), new Auto("fx_delay_delay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, "beat_length")}), new Auto("send_mixer_gain", new Seg[] {new Seg(0.0f, 1.0f, 0.5f, 0.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_mixer_wet", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0, 1.0f, null)})},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 2.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.0f, 1.0f, 20000.0f, 200.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)})}),
        new Style(11, "BM - Filter long out short in + Reverb", 0.0f,
                new String[] {"TIME_STRETCHING", "LOW_PASS", "HI_PASS", "AUX_REVERB", "SEND_MIXER"}, new String[] {"TIME_STRETCHING", "LOW_PASS", "HI_PASS", "RECEIVE_MIXER"},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 2.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.0f, 1.0f, 20000.0f, 80.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.0f, 1.0f, 10.0f, 200.0f, 0.0f, 0.0f, 2, 4.0f, null)}), new Auto("fx_reverb_high_frequency_decay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.001f, 4.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_low_frequency_decay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.001f, 4.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_min_delay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.008f, 0.052f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_max_delay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.106f, 0.106f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_randomize_reflections", new Seg[] {new Seg(0.0f, 0.0f, 333.0f, 333.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_dry_wet", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 100.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("send_mixer_gain", new Seg[] {new Seg(0.0f, 0.25f, 0.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)})},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 2.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.0f, 1.0f, 60.0f, 21000.0f, 0.0f, 0.0f, 2, 4.0f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.0f, 1.0f, 3000.0f, 10.0f, 0.0f, 0.0f, 1, 4.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.5f, 0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)})}),
        new Style(12, "BM - Long filter high to low", 16.0f,
                new String[] {"TIME_STRETCHING", "LOW_PASS", "RECEIVE_MIXER"}, new String[] {"TIME_STRETCHING", "HI_PASS", "LOW_PASS", "RECEIVE_MIXER"},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 2.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.0f, 1.0f, 20000.0f, 200.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0, 1.0f, null)})},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 2.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.0f, 1.0f, 20000.0f, 10.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.0f, 1.0f, 1200.0f, 20000.0f, 0.0f, 0.0f, 2, 4.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)})}),
        new Style(13, "BM - Filter soft + Reverb", 10.0f,
                new String[] {"TIME_STRETCHING", "LOW_PASS", "HI_PASS", "RECEIVE_MIXER", "AUX_REVERB", "SEND_MIXER", "RECEIVE_MIXER", "INPUT_GAIN"}, new String[] {"TIME_STRETCHING", "LOW_PASS", "HI_PASS", "RECEIVE_MIXER"},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 2.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.4f, 0.8f, 20000.0f, 760.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.0f, 0.8f, 10.0f, 760.0f, 0.0f, 0.0f, 1, 0.5f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.4f, 0.8f, 1.0f, 0.75f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_high_frequency_decay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.001f, 4.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_low_frequency_decay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.001f, 4.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_min_delay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.008f, 0.052f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_max_delay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.106f, 0.106f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_randomize_reflections", new Seg[] {new Seg(0.0f, 0.0f, 333.0f, 333.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_dry_wet", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 100.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("send_mixer_gain", new Seg[] {new Seg(0.0f, 0.8f, 0.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_mixer_wet", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 2, 2.0f, null)}), new Auto("fx_mixer_dry", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 2, 2.0f, null)}), new Auto("player_gain", new Seg[] {new Seg(0.8f, 0.8f, 1.0f, 0.0f, -0.5f, 0.0f, 0, 1.0f, null)})},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 2.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.0f, 0.0f, 250.0f, 250.0f, 0.0f, 0.0f, 1, 0.5f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.6f, 0.8f, 500.0f, 20000.0f, 0.0f, 0.0f, 2, 2.0f, null)}), new Auto("lp_reso", new Seg[] {new Seg(0.0f, 0.0f, -5.0f, -5.0f, 0.0f, 0.0f, 2, 2.0f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.0f, 0.0f, 150.0f, 150.0f, 0.0f, 0.0f, 2, 2.0f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.7f, 0.8f, 150.0f, 20.0f, 0.0f, 0.0f, 2, 2.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 0.1f, 0.0f, 0.75f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.65f, 0.7f, 0.75f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)})}),
        new Style(14, "No vocal clash one", 10.0f,
                new String[] {"TIME_STRETCHING", "LOW_PASS", "HI_PASS", "AUX_REVERB", "SEND_MIXER", "RECEIVE_MIXER", "INPUT_GAIN"}, new String[] {"TIME_STRETCHING", "LOW_PASS", "HI_PASS", "RECEIVE_MIXER"},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 2.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.0f, 1.0f, 20000.0f, 20.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.0f, 0.8f, 10.0f, 300.0f, 0.0f, 0.0f, 1, 0.5f, null)}), new Auto("fx_reverb_high_frequency_decay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.001f, 0.5f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_low_frequency_decay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.001f, 1.5f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_min_delay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.008f, 0.01f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_max_delay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.106f, 0.03f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_reverb_dry_wet", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 100.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("send_mixer_gain", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_mixer_wet", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 2, 2.0f, null)}), new Auto("fx_mixer_dry", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 2, 2.0f, null)}), new Auto("player_gain", new Seg[] {new Seg(0.8f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0, 1.0f, null)})},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 2.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.0f, 0.0f, 250.0f, 250.0f, 0.0f, 0.0f, 1, 0.5f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.6f, 0.8f, 500.0f, 20000.0f, 0.0f, 0.0f, 2, 2.0f, null)}), new Auto("lp_reso", new Seg[] {new Seg(0.0f, 0.0f, -5.0f, -5.0f, 0.0f, 0.0f, 2, 2.0f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.0f, 0.0f, 150.0f, 150.0f, 0.0f, 0.0f, 2, 2.0f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.7f, 0.8f, 150.0f, 20.0f, 0.0f, 0.0f, 2, 2.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 0.1f, 0.0f, 0.75f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.65f, 0.7f, 0.75f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)})}),
        new Style(15, "Hiphop - Delay out", 4.0f,
                new String[] {"TIME_STRETCHING", "AUX_DELAY", "SEND_MIXER", "HI_PASS", "RECEIVE_MIXER", "RECEIVE_MIXER"}, new String[] {"TIME_STRETCHING", "LOW_PASS", "HI_PASS", "RECEIVE_MIXER"},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 0.75f, 1.0f, 2.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_delay_dry_wet", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 100.0f, 0.0f, 1.0f, 1, 2.0f, null)}), new Auto("fx_delay_delay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, "dynamic_beat_length")}), new Auto("fx_delay_lp_cutoff_frequency", new Seg[] {new Seg(0.0f, 0.0f, 3000.0f, 3000.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_delay_feedback", new Seg[] {new Seg(0.0f, 0.0f, 35.0f, 35.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("send_mixer_gain", new Seg[] {new Seg(0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.1f, 0, 1.0f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.25f, 0.5f, 20.0f, 200.0f, 0.0f, 0.0f, 1, 0.5f, null)}), new Auto("fx_mixer_wet", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 2, 2.0f, null)}), new Auto("fx_mixer_dry", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 2, 2.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0, 1.0f, null)})},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 0.75f, 2.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.25f, 0.75f, 200.0f, 20000.0f, 0.0f, 0.0f, 2, 4.0f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.25f, 0.75f, 300.0f, 20.0f, 0.0f, 0.0f, 1, 0.5f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)})}),
        new Style(16, "Pop - Delay out", 4.0f,
                new String[] {"TIME_STRETCHING", "LOW_PASS", "AUX_DELAY", "SEND_MIXER", "RECEIVE_MIXER", "RECEIVE_MIXER", "INPUT_GAIN"}, new String[] {"TIME_STRETCHING", "HI_PASS", "RECEIVE_MIXER"},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 0.5f, 1.0f, 2.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("lp_cutoff_freq", new Seg[] {new Seg(0.125f, 0.25f, 20000.0f, 3500.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("fx_delay_dry_wet", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 100.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("fx_delay_delay_time", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, "beat_length")}), new Auto("fx_delay_lp_cutoff_frequency", new Seg[] {new Seg(0.0f, 0.0f, 3500.0f, 3500.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_delay_feedback", new Seg[] {new Seg(0.0f, 0.0f, 60.0f, 60.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("send_mixer_gain", new Seg[] {new Seg(0.0f, 0.25f, 0.0f, 0.5f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("fx_mixer_wet", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 2, 2.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.375f, 0.5f, 1.0f, 0.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("player_gain", new Seg[] {new Seg(0.25f, 0.25f, 1.0f, 0.0f, 0.0f, 0.0f, 0, 1.0f, null)})},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 0.5f, 2.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("hp_cutoff_freq", new Seg[] {new Seg(0.1875f, 0.4375f, 5200.0f, 20.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)})}),
        new Style(17, "Dance - Repeater", 18.0f,
                new String[] {"TIME_STRETCHING", "REMIX_FX"}, new String[] {"TIME_STRETCHING", "REMIX_FX", "RECEIVE_MIXER"},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 2.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("remixfx_beats_per_minute", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, "bpm")}), new Auto("remixfx_filter_a_on", new Seg[] {new Seg(0.45f, 0.45f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_cutoff", new Seg[] {new Seg(0.45f, 0.8889f, 20.0f, 1000.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_resonance", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_type", new Seg[] {new Seg(0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_on", new Seg[] {new Seg(0.5556f, 0.5556f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_cutoff", new Seg[] {new Seg(0.8333f, 0.8611f, 20000.0f, 2111.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_cutoff", new Seg[] {new Seg(0.8611f, 0.8889f, 2111.0f, 20.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_resonance", new Seg[] {new Seg(0.8611f, 0.8611f, 0.3f, 0.3f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_type", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_repeater_on", new Seg[] {new Seg(0.4444f, 0.4444f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_repeater_on", new Seg[] {new Seg(0.8889f, 0.8889f, 1.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_repeater_rate", new Seg[] {new Seg(0.4444f, 0.4444f, 1.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_repeater_rate", new Seg[] {new Seg(0.6667f, 0.6667f, 2.0f, 2.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_repeater_rate", new Seg[] {new Seg(0.7778f, 0.7778f, 3.0f, 3.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_repeater_rate", new Seg[] {new Seg(0.8333f, 0.8333f, 4.0f, 4.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_repeater_rate", new Seg[] {new Seg(0.8611f, 0.8611f, 5.0f, 5.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_repeater_mix", new Seg[] {new Seg(0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_reverb_on", new Seg[] {new Seg(0.6389f, 0.6389f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_reverb_time", new Seg[] {new Seg(0.0f, 0.0f, 6.0f, 6.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_reverb_send", new Seg[] {new Seg(0.6667f, 0.7778f, 0.0f, 0.56f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_reverb_dry", new Seg[] {new Seg(0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_reverb_wet", new Seg[] {new Seg(0.0f, 0.0f, 0.7f, 0.7f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_flanger_on", new Seg[] {new Seg(0.6667f, 0.6667f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_flanger_rate", new Seg[] {new Seg(0.0f, 0.0f, 2.0f, 2.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_flanger_mix", new Seg[] {new Seg(0.6722f, 0.8333f, 0.0f, 0.45f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_flanger_feedback", new Seg[] {new Seg(0.0f, 0.0f, 0.52f, 0.52f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_flanger_depth", new Seg[] {new Seg(0.0f, 0.0f, 0.32f, 0.32f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_flanger_center", new Seg[] {new Seg(0.0f, 0.0f, 0.51f, 0.51f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_lfo_phase_sync", new Seg[] {new Seg(0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)})},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 2.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("remixfx_beats_per_minute", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, "bpm")}), new Auto("remixfx_filter_a_on", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_cutoff", new Seg[] {new Seg(0.0f, 0.2222f, 1250.0f, 950.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_cutoff", new Seg[] {new Seg(0.2222f, 0.3889f, 950.0f, 200.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_cutoff", new Seg[] {new Seg(0.3889f, 0.5556f, 200.0f, 20.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_resonance", new Seg[] {new Seg(0.0f, 0.0f, 0.3f, 0.3f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_type", new Seg[] {new Seg(0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_on", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_cutoff", new Seg[] {new Seg(0.0f, 0.4444f, 70.0f, 1600.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_cutoff", new Seg[] {new Seg(0.4444f, 0.8889f, 1600.0f, 20000.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_resonance", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_type", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 0.4444f, 0.0f, 0.4467f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.4444f, 0.7778f, 0.4467f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)})}),
        new Style(18, "Hiphop - Looping", 4.0f,
                new String[] {"TIME_STRETCHING", "REMIX_FX"}, new String[] {"TIME_STRETCHING", "REMIX_FX", "RECEIVE_MIXER"},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 2.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("remixfx_lfo_phase_sync", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_repeater_on", new Seg[] {new Seg(0.5f, 0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_repeater_rate", new Seg[] {new Seg(0.0f, 0.0f, 2.0f, 2.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_repeater_mix", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_beats_per_minute", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, "bpm")}), new Auto("remixfx_filter_a_on", new Seg[] {new Seg(0.525f, 0.525f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_cutoff", new Seg[] {new Seg(0.525f, 1.0f, 20000.0f, 20.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_type", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)})},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 2.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("remixfx_lfo_phase_sync", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_beats_per_minute", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, "bpm")}), new Auto("remixfx_filter_a_on", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_cutoff", new Seg[] {new Seg(0.0f, 0.75f, 2000.0f, 20.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_type", new Seg[] {new Seg(0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 0.75f, 0.3162f, 1.0f, 0.0f, 0.0f, 2, 0.5f, null)})}),
        new Style(19, "Dance - Any Key", 8.0f,
                new String[] {"TIME_STRETCHING", "REMIX_FX"}, new String[] {"TIME_STRETCHING", "REMIX_FX", "RECEIVE_MIXER"},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 2.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("remixfx_beats_per_minute", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, "bpm")}), new Auto("remixfx_filter_a_on", new Seg[] {new Seg(0.2813f, 0.2813f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_cutoff", new Seg[] {new Seg(0.25f, 0.625f, 20.0f, 1031.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_resonance", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_type", new Seg[] {new Seg(0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_on", new Seg[] {new Seg(0.2656f, 0.2656f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_cutoff", new Seg[] {new Seg(0.2656f, 0.375f, 20000.0f, 2375.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_cutoff", new Seg[] {new Seg(0.375f, 0.875f, 2375.0f, 20.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("remixfx_filter_b_resonance", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_type", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_repeater_on", new Seg[] {new Seg(0.25f, 0.25f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_repeater_rate", new Seg[] {new Seg(0.0f, 0.0f, 2.0f, 2.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_repeater_mix", new Seg[] {new Seg(0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_reverb_on", new Seg[] {new Seg(0.5f, 0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_reverb_time", new Seg[] {new Seg(0.0f, 0.0f, 18.0f, 18.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_reverb_send", new Seg[] {new Seg(0.5f, 0.75f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_reverb_dry", new Seg[] {new Seg(0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_reverb_wet", new Seg[] {new Seg(0.0f, 0.0f, 0.815f, 0.815f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_delay_on", new Seg[] {new Seg(0.375f, 0.375f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_delay_rate", new Seg[] {new Seg(0.0f, 0.0f, 7.0f, 7.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_delay_feedback", new Seg[] {new Seg(0.0f, 0.0f, 0.5f, 0.5f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_delay_level", new Seg[] {new Seg(0.625f, 0.875f, 0.0f, 0.5f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_gater_on", new Seg[] {new Seg(0.375f, 0.375f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_gater_rate", new Seg[] {new Seg(0.0f, 0.0f, 7.0f, 7.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_gater_depth", new Seg[] {new Seg(0.25f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_gater_pw", new Seg[] {new Seg(0.0f, 0.0f, 0.5f, 0.5f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_gater_noise_level", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_gater_noise_cutoff", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_flanger_on", new Seg[] {new Seg(0.5f, 0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_flanger_rate", new Seg[] {new Seg(0.0f, 0.0f, 2.0f, 2.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_flanger_mix", new Seg[] {new Seg(0.5f, 1.0f, 0.0f, 0.63f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_flanger_feedback", new Seg[] {new Seg(0.0f, 0.0f, 0.5f, 0.5f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_flanger_depth", new Seg[] {new Seg(0.0f, 0.0f, 0.3f, 0.3f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_flanger_center", new Seg[] {new Seg(0.0f, 0.0f, 0.5f, 0.5f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_lfo_phase_sync", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)})},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 2.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("remixfx_beats_per_minute", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, "bpm")}), new Auto("remixfx_filter_a_on", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_cutoff", new Seg[] {new Seg(0.0f, 0.5f, 1730.0f, 20.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_resonance", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_type", new Seg[] {new Seg(0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_on", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_cutoff", new Seg[] {new Seg(0.0f, 0.5f, 20.0f, 20000.0f, 0.0f, 0.0f, 2, 4.0f, null)}), new Auto("remixfx_filter_b_resonance", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_type", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.0f, 0.5f, 0.2239f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)})}),
        new Style(20, "Pop - Reverb", 4.0f,
                new String[] {"TIME_STRETCHING", "REMIX_FX", "RECEIVE_MIXER"}, new String[] {"TIME_STRETCHING", "REMIX_FX", "RECEIVE_MIXER"},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 1.0f, 2.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("remixfx_beats_per_minute", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, "bpm")}), new Auto("remixfx_filter_a_on", new Seg[] {new Seg(0.25f, 0.25f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_cutoff", new Seg[] {new Seg(0.25f, 0.75f, 20000.0f, 20.0f, 0.0f, 0.0f, 1, 4.0f, null)}), new Auto("remixfx_filter_a_resonance", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_type", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_on", new Seg[] {new Seg(0.2813f, 0.2813f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_cutoff", new Seg[] {new Seg(0.2813f, 0.5f, 20.0f, 600.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("remixfx_filter_b_cutoff", new Seg[] {new Seg(0.5f, 0.75f, 600.0f, 927.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("remixfx_filter_b_resonance", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_type", new Seg[] {new Seg(0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_reverb_on", new Seg[] {new Seg(0.3125f, 0.3125f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_reverb_time", new Seg[] {new Seg(0.0f, 0.0f, 6.0f, 6.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_reverb_send", new Seg[] {new Seg(0.3125f, 0.525f, 0.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("remixfx_reverb_dry", new Seg[] {new Seg(0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_reverb_wet", new Seg[] {new Seg(0.0f, 0.0f, 0.8f, 0.8f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_lfo_phase_sync", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.375f, 0.75f, 1.0f, 0.4217f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.75f, 1.0f, 0.4217f, 0.0f, 0.0f, 0.0f, 0, 1.0f, null)})},
                new Auto[] {new Auto("ts_rate", new Seg[] {new Seg(0.0f, 1.0f, 2.0f, 1.0f, 0.0f, 0.0f, 0, 1.0f, null)}), new Auto("remixfx_beats_per_minute", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, "bpm")}), new Auto("remixfx_filter_a_on", new Seg[] {new Seg(0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_cutoff", new Seg[] {new Seg(0.25f, 0.75f, 3300.0f, 20.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_resonance", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_a_type", new Seg[] {new Seg(0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_on", new Seg[] {new Seg(0.25f, 0.25f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_cutoff", new Seg[] {new Seg(0.25f, 0.75f, 20.0f, 20000.0f, 0.0f, 0.0f, 2, 4.0f, null)}), new Auto("remixfx_filter_b_resonance", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("remixfx_filter_b_type", new Seg[] {new Seg(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1, 2.0f, null)}), new Auto("out_gain", new Seg[] {new Seg(0.25f, 0.75f, 0.0f, 1.0f, 0.0f, 0.0f, 1, 2.0f, null)})}),
    };
}
