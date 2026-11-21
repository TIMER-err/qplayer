package dev.t1m3.qplayer.audio.mix;

/**
 * <b>Apple `REMIX_FX` 的调度层</b>（2026-10-08 ✓，照搬 goal 第 37 轮 ✓）。
 *
 * <p>材料里档 17 `Dance - Repeater`（**参考对所属档** ✓）的 `REMIX_FX` 不是"简单循环" ✗，
 * 而是一条按时刻推进的效果链 ✓（逐字读出来的 ✓）：
 * <pre>
 * repeater_on      0 -&gt; 1   @ 0.4444        （开）
 * repeater_on      1 -&gt; 0   @ 0.8889        （关）
 * repeater_rate    1 @ 0.4444 → 2 @ 0.6667 → 3 @ 0.7778 → 4 @ 0.8333 → 5 @ 0.8611   （逐级细分）
 * repeater_mix     1
 * filter_a_on      0 -&gt; 1   @ 0.45   · a_cutoff   20 -&gt; 1000   [0.45, 0.8889]   （低通打开）
 * filter_b_on      0 -&gt; 1   @ 0.5556 · b_cutoff   20000 -&gt; 2111 -&gt; 20           （低通收拢）
 * reverb_on        0 -&gt; 1   @ 0.6389 · reverb_send 0 -&gt; 0.56   [0.6667, 0.7778]
 * flanger_on       0 -&gt; 1   @ 0.6667 · flanger_mix 0 -&gt; 0.45   [0.6722, 0.8333]
 * </pre>
 *
 * <p>本类只做**调度**（按时刻求值 ✓，零分配 ✓，不碰音频 ✗）：DSP 那一层读这里的值 ✓。
 * 取值全部来自生成表 {@link AppleTransitionSpec} ✓（没有第二份数据 ✓）；某一档没有这些参数 ⇒
 * 返回"全关" ✓（不会凭空加效果 ✗）。
 */
public final class AppleRemixFx {

    /** 一次过渡在某个进度上的 FX 状态 ✓。 */
    public static final class State {
        public boolean repeaterOn;
        public float repeaterRate = 1f;
        public float repeaterMix = 1f;
        public boolean filterAOn;
        /** `remixfx_filter_a_type` ✓：1 = **高通** ✓、0 = 低通 ✓（他档 17 的 a 是 **1** ✓）。 */
        public boolean filterAHigh;
        public float filterACutoff = 20000f;
        public boolean filterBOn;
        /** `remixfx_filter_b_type` ✓：他档 17 的 b 是 **0 = 低通** ✓。 */
        public boolean filterBHigh;
        public float filterBCutoff = 20000f;
        public boolean reverbOn;
        public float reverbSend;
        public boolean flangerOn;
        public float flangerMix;

        void reset() {
            repeaterOn = false;
            repeaterRate = 1f;
            repeaterMix = 1f;
            filterAOn = false;
            filterACutoff = 20000f;
            filterBOn = false;
            filterBCutoff = 20000f;
            reverbOn = false;
            reverbSend = 0f;
            flangerOn = false;
            flangerMix = 0f;
        }
    }

    private AppleRemixFx() {
    }

    /** 档 17 的整条链 ✓（逐字见类注释 ✓）。 */
    private static final int DANCE_REPEATER_ID = 17;

    private static AppleTransitionSpec.Style repeater() {
        return AppleStyleMap.byAppleId(DANCE_REPEATER_ID);
    }

    /**
     * <b>同名合并成一条"车道"</b>（2026-10-08 ✓，第 37 轮由判据照出来 ✓）。
     *
     * <p>他的 `repeater_on` 是**两条**条目（0.4444 置 1 ✓、0.8889 置 0 ✓）、
     * `repeater_rate` 是**五条**（1/2/3/4/5 ✓）—— 它们是**同一条车道上的多个时刻** ✓，
     * 而不是五条独立曲线 ✗。我第一版按"最后一条覆盖"取 ✗ ⇒ 只看到最后一个值 ✗
     * （实测：0.4 处误判为"开" ✓、速率恒为 5 ✗）。⇒ 按表序**拼接段**成一条 ✓，再按车道语义求值 ✓。
     */
    private static final java.util.Map<String, AppleTransitionSpec.Auto> LANES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static AppleTransitionSpec.Auto lane(String key) {
        return lane(true, key);
    }

    /**
     * **按侧别取车道**（2026-10-09 ✓，goal「让过渡像一首歌」✓）：他档 17 的**进场侧**另有
     * 一整套 `remixfx_filter_a/b_*`（a: 1250→950→200→20 ✓；b: 70→1600→20000 ✓）✓ —— 我们原来
     * 只建出场侧 ✗ ⇒ 实测 `REMIX_FX` 开/关时 **B 独奏逐切片 +0.0 dB** ✗（B 完全没吃到这条链 ✓）。
     */
    private static AppleTransitionSpec.Auto lane(boolean outgoing, String key) {
        String ck = (outgoing ? "o:" : "i:") + key;
        AppleTransitionSpec.Auto cached = LANES.get(ck);
        if (cached != null) {
            return cached;
        }
        AppleTransitionSpec.Style s = repeater();
        if (s == null) {
            return null;
        }
        AppleTransitionSpec.Auto[] ops = outgoing ? s.outgoing : s.incoming;
        int n = 0;
        for (AppleTransitionSpec.Auto x : ops) {
            if (key.equals(x.key)) {
                n += x.segs.length;
            }
        }
        if (n == 0) {
            return null;
        }
        AppleTransitionSpec.Seg[] segs = new AppleTransitionSpec.Seg[n];
        int at = 0;
        for (AppleTransitionSpec.Auto x : ops) {
            if (key.equals(x.key)) {
                for (AppleTransitionSpec.Seg seg : x.segs) {
                    segs[at++] = seg;
                }
            }
        }
        AppleTransitionSpec.Auto merged = newMerged(key, segs);
        LANES.put(ck, merged);
        return merged;
    }

    private static AppleTransitionSpec.Auto newMerged(String key, AppleTransitionSpec.Seg[] segs) {
        return AppleTransitionSpec.merged(key, segs);
    }

    private static float vSide(boolean outgoing, String key, float p, float fallback) {
        AppleTransitionSpec.Auto a = lane(outgoing, key);
        return a == null ? fallback : AppleTransitionSpec.valueAt(a, p, 0f);
    }

    private static float v(AppleTransitionSpec.Style s, String key, float p, float fallback) {
        AppleTransitionSpec.Auto a = lane(key);
        return a == null ? fallback : AppleTransitionSpec.valueAt(a, p, 0f);
    }

    /** 他的 `remixfx_reverb_time`（秒 ✓，档 17 是 6 ✓）。 */
    public static float reverbTimeSecs() {
        AppleTransitionSpec.Style s = repeater();
        return v(s, "remixfx_reverb_time", 1f, 6f);
    }

    /** 他的 `remixfx_flanger_rate`（档 17 是 2 ✓；单位材料没给 ⇒ 按 Hz 解释 ✓）。 */
    public static float flangerRateHz() {
        return v(repeater(), "remixfx_flanger_rate", 1f, 2f);
    }

    /** 他的 `remixfx_flanger_depth`（档 17 是 0.32 ✓）。 */
    public static float flangerDepth() {
        return v(repeater(), "remixfx_flanger_depth", 1f, 0.32f);
    }

    /** 他的 `remixfx_flanger_center`（档 17 是 0.51 ✓）。 */
    public static float flangerCenter() {
        return v(repeater(), "remixfx_flanger_center", 1f, 0.51f);
    }

    /** 他的 `remixfx_flanger_feedback`（档 17 是 0.52 ✓）。 */
    public static float flangerFeedback() {
        return v(repeater(), "remixfx_flanger_feedback", 1f, 0.52f);
    }

    /** 他的 `remixfx_reverb_wet`（档 17 是 0.7 ✓）。 */
    public static float reverbWet() {
        AppleTransitionSpec.Style s = repeater();
        return v(s, "remixfx_reverb_wet", 1f, 0.7f);
    }

    /**
     * **进场侧的状态**（2026-10-09 ✓）：键与出场侧相同 ✓，但取 `s.incoming` ✓。
     * 实测依据：他档 17 的进场侧有 filter a（1250→950→200→20 ✓）与 filter b（70→1600→20000 ✓）✓。
     */
    public static void fillIncoming(State out, float progress) {
        out.reset();
        AppleTransitionSpec.Style s = repeater();
        if (s == null) {
            return;
        }
        float p = progress < 0f ? 0f : (progress > 1f ? 1f : progress);
        out.repeaterOn = vSide(false, "remixfx_repeater_on", p, 0f) > 0.5f;
        out.repeaterRate = vSide(false, "remixfx_repeater_rate", p, 1f);
        out.repeaterMix = vSide(false, "remixfx_repeater_mix", p, 1f);
        out.filterAOn = vSide(false, "remixfx_filter_a_on", p, 0f) > 0.5f;
        out.filterACutoff = vSide(false, "remixfx_filter_a_cutoff", p, 20000f);
        out.filterAHigh = vSide(false, "remixfx_filter_a_type", p, 0f) >= 0.5f;
        out.filterBOn = vSide(false, "remixfx_filter_b_on", p, 0f) > 0.5f;
        out.filterBCutoff = vSide(false, "remixfx_filter_b_cutoff", p, 20000f);
        out.filterBHigh = vSide(false, "remixfx_filter_b_type", p, 0f) >= 0.5f;
        out.reverbOn = vSide(false, "remixfx_reverb_on", p, 0f) > 0.5f;
        out.reverbSend = vSide(false, "remixfx_reverb_send", p, 0f);
        out.flangerOn = vSide(false, "remixfx_flanger_on", p, 0f) > 0.5f;
        out.flangerMix = vSide(false, "remixfx_flanger_mix", p, 0f);
    }

    /** 按进度填一次状态 ✓（复用同一个对象 ⇒ 零分配 ✓）。 */
    public static void fill(State out, float progress) {
        out.reset();
        AppleTransitionSpec.Style s = repeater();
        if (s == null) {
            return;
        }
        float p = progress < 0f ? 0f : (progress > 1f ? 1f : progress);
        out.repeaterOn = v(s, "remixfx_repeater_on", p, 0f) > 0.5f;
        out.repeaterRate = v(s, "remixfx_repeater_rate", p, 1f);
        out.repeaterMix = v(s, "remixfx_repeater_mix", p, 1f);
        out.filterAOn = v(s, "remixfx_filter_a_on", p, 0f) > 0.5f;
        out.filterACutoff = v(s, "remixfx_filter_a_cutoff", p, 20000f);
        out.filterAHigh = v(s, "remixfx_filter_a_type", p, 0f) >= 0.5f;
        out.filterBOn = v(s, "remixfx_filter_b_on", p, 0f) > 0.5f;
        out.filterBCutoff = v(s, "remixfx_filter_b_cutoff", p, 20000f);
        out.filterBHigh = v(s, "remixfx_filter_b_type", p, 0f) >= 0.5f;
        out.reverbOn = v(s, "remixfx_reverb_on", p, 0f) > 0.5f;
        out.reverbSend = v(s, "remixfx_reverb_send", p, 0f);
        out.flangerOn = v(s, "remixfx_flanger_on", p, 0f) > 0.5f;
        out.flangerMix = v(s, "remixfx_flanger_mix", p, 0f);
    }
}
