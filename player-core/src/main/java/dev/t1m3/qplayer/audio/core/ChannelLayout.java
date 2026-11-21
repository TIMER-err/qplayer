package dev.t1m3.qplayer.audio.core;

/**
 * 声道布局。
 *
 * <p>环绕声相关的判定全部收敛到这里：设备能力探测、设置项置灰、多声道直通与
 * HRTF 折叠的分支，都只看这一个类型。
 *
 * <p>对应 Sonalis 的 {@code sonalis_core::channel::ChannelLayout}。
 */
public final class ChannelLayout {

    /** 单声道。 */
    public static final ChannelLayout MONO = new ChannelLayout(1, "mono");
    /** 立体声。 */
    public static final ChannelLayout STEREO = new ChannelLayout(2, "stereo");
    /** 5.1 环绕（FL、FR、C、LFE、SL、SR）。 */
    public static final ChannelLayout SURROUND_5_1 = new ChannelLayout(6, "5.1");
    /** 7.1 环绕。 */
    public static final ChannelLayout SURROUND_7_1 = new ChannelLayout(8, "7.1");

    /** 声道数上限：与上游的 {@code u16} 承载范围一致。 */
    private static final int MAX_CHANNELS = 65_535;

    private final int count;
    private final String label;

    private ChannelLayout(int count, String label) {
        this.count = count;
        this.label = label;
    }

    /**
     * 由声道数反推布局。
     *
     * <p>超过上限的声道数被<strong>夹到上界</strong>而不是静默回绕——回绕会让
     * {@code of(65536)} 变成 0，下游 {@link AudioBuf} 的声道数断言直接抛异常；
     * 夹取至少保住「一个荒谬但自洽」的值。
     */
    public static ChannelLayout of(int channels) {
        switch (channels) {
            case 1: return MONO;
            case 2: return STEREO;
            case 6: return SURROUND_5_1;
            case 8: return SURROUND_7_1;
            default:
                return new ChannelLayout(channels > MAX_CHANNELS ? MAX_CHANNELS : channels,
                        (channels > MAX_CHANNELS ? MAX_CHANNELS : channels) + "ch");
        }
    }

    /** 声道数。 */
    public int count() {
        return count;
    }

    /**
     * 是否多于立体声。
     *
     * <p>这是「要不要走环绕路径」的唯一判据。设备不支持时就该置灰设置项，而不是
     * 静默降级成双声道——静默降级会让人以为功能坏了。
     */
    public boolean isSurround() {
        return count > 2;
    }

    /** 是否可以不经处理直接互通（用于快路径判断）。 */
    public boolean sameGeometry(ChannelLayout other) {
        return other != null && count == other.count;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChannelLayout)) return false;
        return count == ((ChannelLayout) o).count;
    }

    @Override
    public int hashCode() {
        return count;
    }

    @Override
    public String toString() {
        return label;
    }
}
