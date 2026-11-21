package dev.t1m3.qplayer.audio.mix;

/**
 * 当前时段的信号路径状态。
 *
 * <p><strong>这是界面上「直通 / 过渡中」那行字的唯一来源</strong>，UI 不得自行判断。
 * 判定依据是实际状态（deck 是否透传、增益是否 unity、是否在过渡窗口内），
 * 不是什么「模式开关」——判定逻辑只允许有一份（见 {@link Director#pathState()}）。
 *
 * <p>位完美是<strong>逐段</strong>生效的：只有过渡窗口那几秒让步，歌曲主体照常直通。
 * 一个过渡会不会改写采样值由 {@link Transition#rewritesSamples()} 回答（上层用它标注
 * 信号路径），而「现在这一块是不是落在那个窗口里」由
 * {@link Director#pathState()} 判——后者是本类唯一的产出点，UI 只读不算。
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::director::PathState}（上游是简单 enum，
 * 这里同样是 Java {@code enum}，变体名按约定用 {@code UPPER_SNAKE}）。
 * 上游 {@code impl fmt::Display} 写的是 {@code label()}，所以 {@link #toString()}
 * 也返回同一行字。
 */
public enum PathState {

    /** 位完美：设备与源采样率一致、增益单位状态、不在过渡窗口内。 */
    BIT_PERFECT("直通"),

    /** 非位完美，但原因不是过渡——通常是采样率不匹配，或挂了变调器。 */
    PROCESSED("已处理"),

    /** 正在过渡窗口内：采样值被混合改写。 */
    TRANSITIONING("过渡中");

    private final String label;

    PathState(String label) {
        this.label = label;
    }

    /** 是否位完美。 */
    public boolean isBitPerfect() {
        return this == BIT_PERFECT;
    }

    /** 给界面用的一行说明。 */
    public String label() {
        return label;
    }

    /** 上游的 {@code impl fmt::Display}：打的就是 {@link #label()}。 */
    @Override
    public String toString() {
        return label;
    }
}
