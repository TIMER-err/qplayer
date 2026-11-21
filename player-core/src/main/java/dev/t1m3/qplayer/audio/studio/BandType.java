package dev.t1m3.qplayer.audio.studio;

/**
 * 均衡段类型。
 *
 * <p>挑了 RBJ 手册里音乐播放器真正用得上的那几种：<b>峰值</b>（调音色）、<b>低架/高架</b>
 * （整体走向）、<b>低通/高通</b>（切掉无用的频段，斜率可选级联阶数）、<b>陷波</b>（去某一根
 * 交流声或啸叫）、<b>全通</b>（只动相位，做相位对齐用）。
 *
 * <p>类型的 JSON 名固定，<strong>不要改</strong>——预设是用户存下来的，改名等于把他们的
 * 预设弄丢。新增类型只能往后加。
 */
public enum BandType {

    /** 峰值（钟形）：频率处抬/降 gainDb，Q 决定宽窄。 */
    PEAK("peak"),
    /** 低架：freq 以下整体抬/降。 */
    LOW_SHELF("low_shelf"),
    /** 高架：freq 以上整体抬/降。 */
    HIGH_SHELF("high_shelf"),
    /** 高通：freq 以下按斜率衰减。 */
    HIGH_PASS("high_pass"),
    /** 低通：freq 以上按斜率衰减。 */
    LOW_PASS("low_pass"),
    /** 带通：只留 freq 附近一段。 */
    BAND_PASS("band_pass"),
    /** 陷波：只压掉 freq 附近很窄的一段。 */
    NOTCH("notch"),
    /** 全通：幅度不变，只改相位。 */
    ALL_PASS("all_pass");

    private final String json;

    BandType(String json) {
        this.json = json;
    }

    /** 预设里用的名字。 */
    public String json() {
        return json;
    }

    /** 界面上的名字（下拉与列表用）。 */
    public String label() {
        switch (this) {
            case PEAK: return "峰值";
            case LOW_SHELF: return "低架";
            case HIGH_SHELF: return "高架";
            case HIGH_PASS: return "高通";
            case LOW_PASS: return "低通";
            case BAND_PASS: return "带通";
            case NOTCH: return "陷波";
            default: return "全通";
        }
    }

    /** 全部类型的界面名字（下拉的选项，顺序与 {@code values()} 一致）。 */
    public static String[] labels() {
        BandType[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            out[i] = all[i].label();
        }
        return out;
    }

    /** 认不出来就退回 {@link #PEAK}（不认识的名字不该让整份预设读不出来）。 */
    public static BandType fromJson(String value) {
        if (value != null) {
            for (BandType t : values()) {
                if (t.json.equalsIgnoreCase(value) || t.name().equalsIgnoreCase(value)) {
                    return t;
                }
            }
        }
        return PEAK;
    }

    /** 这种类型用不用得上「增益」与「斜率」两个参数（界面据此灰掉无关的控件）。 */
    public boolean usesGain() {
        return this == PEAK || this == LOW_SHELF || this == HIGH_SHELF;
    }

    /** 斜率只对低通/高通有意义；其余类型按 12 dB/oct（单级）处理。 */
    public boolean usesSlope() {
        return this == HIGH_PASS || this == LOW_PASS;
    }
}
