package dev.t1m3.qplayer.audio.studio;

/**
 * 一段均衡作用在<strong>哪个声道</strong>上。
 *
 * <p>工作站级 EQ 的常规能力，这里都保留了：单声道独立修（{@code LEFT}/{@code RIGHT}）、
 * 以及在<b>中/侧</b>域上修（{@code MID}/{@code SIDE}）——后者是修「人声居中、伴奏在两侧」
 * 这类问题的正路：想抬人声就抬 MID，想扩声场就抬 SIDE。
 *
 * <p>{@code MID}/{@code SIDE} 的实现是逐样本做 M/S 变换、只过滤其中一路、再变回来
 * （见 {@link EqProcessor}）。它<strong>不是</strong>「把左右加起来过滤再摊回去」——
 * 那样两侧会被强行抹成一样，是另一种（通常不是你要的）处理。
 */
public enum ChannelTarget {

    /** 左右都过（常规）。 */
    STEREO("stereo"),
    /** 只改左声道。 */
    LEFT("left"),
    /** 只改右声道。 */
    RIGHT("right"),
    /** 只改中置（(L+R)/2）。 */
    MID("mid"),
    /** 只改两侧（(L−R)/2）。 */
    SIDE("side");

    private final String json;

    ChannelTarget(String json) {
        this.json = json;
    }

    public String json() {
        return json;
    }

    /** 界面上的名字。 */
    public String label() {
        switch (this) {
            case LEFT: return "左";
            case RIGHT: return "右";
            case MID: return "中";
            case SIDE: return "侧";
            default: return "立体声";
        }
    }

    /** 全部声道的界面名字（顺序与 {@code values()} 一致）。 */
    public static String[] labels() {
        ChannelTarget[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            out[i] = all[i].label();
        }
        return out;
    }

    /** 认不出来就退回 {@link #STEREO}。 */
    public static ChannelTarget fromJson(String value) {
        if (value != null) {
            for (ChannelTarget t : values()) {
                if (t.json.equalsIgnoreCase(value) || t.name().equalsIgnoreCase(value)) {
                    return t;
                }
            }
        }
        return STEREO;
    }
}
