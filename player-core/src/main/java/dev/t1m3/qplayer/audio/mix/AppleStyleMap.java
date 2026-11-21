package dev.t1m3.qplayer.audio.mix;

/**
 * <b>我们的档位 ↔ Apple 的档位</b>（2026-10-08 ✓，照搬 goal 第 4 轮 ✓）。
 *
 * <p>用户：「完全照搬他的写法」✓。要照搬就必须先能"按档取他的参数" ✓ —— 本类就是那张对照表 ✓。
 *
 * <p>对应关系来自**名字**：我们的档名当初就是照他的档名抄的 ✓（例如
 * 我们的 {@code FILTER_SHORT_HIGH_TO_LOW} ↔ 他的 {@code 5 · "BM - Short filter high to low"} ✓），
 * 所以这份映射是**逐条对名**得到的 ✓、不是猜的 ✗。他 24 档我们都有对应 ✓；
 * 反过来，我们有几档是**自己的东西**（他没有 ✓）⇒ 映射返回 {@link #NONE} ✓，
 * 那些档**不照搬** ✓（照搬只管"我们抄了他的"那些 ✓）。
 */
public final class AppleStyleMap {

    /** 表示"这一档不是抄他的" ✓（我们自己的设计 ✓，照搬不适用 ✓）。 */
    public static final int NONE = -1;

    private AppleStyleMap() {
    }

    /**
     * 我们的档 → 他的 id（{@link AppleTransitionSpec#STYLES} 里的 {@code id} ✓）。
     *
     * @return 他的档 id ✓；{@link #NONE} 表示我们自己的档 ✓
     */
    public static int appleId(MixStyle style) {
        if (style == null) {
            return NONE;
        }
        switch (style) {
            case GAPLESS:                       return 0;   // "Gapless"
            case PLAIN_BLEND:                   return 1;   // "constant-power cross-fade"
            case CROSSFADE_LONG:                return 2;   // "constant-power long fade-out short fade-in"
            case CROSSFADE_OVERLAP:             return 3;   // "overlap"
            case CROSSFADE_RINGOUT:             return 4;   // "Ease-in Ring-out"
            case FILTER_SHORT_HIGH_TO_LOW:      return 5;   // "BM - Short filter high to low"
            case FILTER_LONG_OUT_SHORT_IN:      return 6;   // "Beat-matched long fade-out short fade-in - Same Bar num"
            case FILTER_DYNAMIC_IN:             return 7;   // "Beat-matched long fade-out dynamic fade-in"
            case FILTER_HIGH_TO_LOW:            return 8;   // "BM - Filter high to low"
            case FILTER_EXPANSION:              return 9;   // "BM - Filter expansion"
            case FILTER_LONG_OUT_SHORT_IN_DELAY:    return 10;  // "... + delay"
            case FILTER_LONG_OUT_SHORT_IN_REVERB:   return 11;  // "... + Reverb"
            case FILTER_LONG_HIGH_TO_LOW:       return 12;  // "BM - Long filter high to low"
            case FILTER_SOFT_REVERB:            return 13;  // "BM - Filter soft + Reverb"
            case NO_VOCAL_CLASH:                return 14;  // "No vocal clash one"
            case HIPHOP_DELAY_OUT:              return 15;  // "Hiphop - Delay out"
            case POP_DELAY_OUT:                 return 16;  // "Pop - Delay out"
            case DANCE_REPEATER:                return 17;  // "Dance - Repeater"
            case HIPHOP_LOOPING:                return 18;  // "Hiphop - Looping"
            case DANCE_ANY_KEY:                 return 19;  // "Dance - Any Key"
            case POP_REVERB:                    return 20;  // "Pop - Reverb"
            case SPLICE:                        return 22;  // "splice"
            case CROSSFADE_OVERLAP_REVERB:      return 33;  // "overlap + Reverb"
            case CROSSFADE_RINGOUT_REVERB:      return 44;  // "Ease-in Ring-out + Reverb"
            default:                            return NONE; // 我们自己的（BEAT_CUT / BASS_SWAP /
                                                             // TAIL_RIDE / SHOW_MODE / LEARNED_EQ / AUTO）
        }
    }

    /**
     * <b>按"车道"取参数</b>（2026-10-08 ✓，照搬 goal 第 38 轮 ✓）：同一个键在同一侧可能出现
     * **多条**条目（实测：档 17/13/14/20 的 `out_gain` ✓、档 13/14 的 `lp/hp_cutoff_freq` ✓）
     * —— 它们是**同一条车道上的多个时刻** ✓，不是独立曲线 ✗。
     *
     * <p>⚠️ 早前几项照搬我用的是"取第一条" ✗ ⇒ 后面的段被丢掉 ✗（参考对所属档 17 的
     * **incoming `out_gain`** 就是两条 ✓ ⇒ 那一侧的增益曲线只搬了一半 ✗）。这里按表序**拼接** ✓。
     */
    public static AppleTransitionSpec.Auto lane(AppleTransitionSpec.Style spec, boolean outgoing,
                                                String key) {
        if (spec == null) {
            return null;
        }
        AppleTransitionSpec.Auto[] list = outgoing ? spec.outgoing : spec.incoming;
        int n = 0;
        for (AppleTransitionSpec.Auto x : list) {
            if (key.equals(x.key)) {
                n += x.segs.length;
            }
        }
        if (n == 0) {
            return null;
        }
        AppleTransitionSpec.Seg[] segs = new AppleTransitionSpec.Seg[n];
        int at = 0;
        for (AppleTransitionSpec.Auto x : list) {
            if (key.equals(x.key)) {
                for (AppleTransitionSpec.Seg seg : x.segs) {
                    segs[at++] = seg;
                }
            }
        }
        return AppleTransitionSpec.merged(key, segs);
    }

    /** 他的 id → 他的那一档（找不到 ⇒ null ✓）。 */
    public static AppleTransitionSpec.Style byAppleId(int id) {
        for (AppleTransitionSpec.Style s : AppleTransitionSpec.STYLES) {
            if (s.id == id) {
                return s;
            }
        }
        return null;
    }

    /** 我们的档 → 他的那一档（{@link #NONE} 或找不到 ⇒ null ✓）。 */
    public static AppleTransitionSpec.Style specFor(MixStyle style) {
        int id = appleId(style);
        return id == NONE ? null : byAppleId(id);
    }
}
