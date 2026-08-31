package dev.t1m3.qplayer.lyric.skia;

// Lyric rendering config (subset of Haedus's MusicPlayerConfig the renderer
// reads). Values are mutable and wrapped so the renderer's `cfg.x.getValue()`
// calls port unchanged; the host pushes user settings into them.
public final class LyricConfig {

    public static final LyricConfig instance = new LyricConfig();

    /** Lyric font weight, mapped to a bundled PingFang face. */
    public enum FontWeight { THIN, LIGHT, REGULAR, MEDIUM, BOLD, HEAVY }

    public static final class Val<T> {
        private volatile T value;
        Val(T value) { this.value = value; }
        public T getValue() { return value; }
        public void setValue(T value) { this.value = value; }
    }

    public final Val<Integer> lyricFontSize = new Val<>(28);
    public final Val<FontWeight> fontWeight = new Val<>(FontWeight.REGULAR);
    /** Line-height multiplier applied to the lyric font size. */
    public final Val<Float> lineSpacing = new Val<>(2.00f);
    /** 歌词放大倍率:当前行缩放目标(默认 1.14,Apple Music 值)。 */
    public final Val<Float> emphasisScale = new Val<>(1.14f);
    /** 自动行间距:开启后用 Melodify 公式(字高 + 10U + 45U + 25)计算行高,
     *  忽略 {@link #lineSpacing} 滑块。 */
    public final Val<Boolean> autoLineSpacing = new Val<>(Boolean.FALSE);
    /** 歌词动效引擎：true = Apple Music 动效（念完即切 + 提前跳转 + gap 驱动弹簧 +
     *  距离级联 + 缩放同步 + 已过行秒亮，默认）；false = 原版动效（fade-in 提前锚点 +
     *  k=65 弹簧 + 方向衰减级联 + 中线渐进放大）。 */
    public final Val<Boolean> melodifyMotion = new Val<>(Boolean.TRUE);
    public final Val<Boolean> showRomaji = new Val<>(Boolean.TRUE);
    public final Val<Boolean> showTranslation = new Val<>(Boolean.TRUE);
    /** Apple-style spring physics for scroll + per-syllable lift. When off, the
     *  scroll uses a stiffer near-critically-damped spring and the lift a fixed
     *  cubic ease (the pre-0.4 tuning). */
    public final Val<Boolean> springPhysics = new Val<>(Boolean.TRUE);
    /** Active-line depth scaling (1.14× emphasis / 0.98× deselected). Off = no
     *  scaling, full-width wrap, no layout reflow. */
    public final Val<Boolean> scaleEmphasis = new Val<>(Boolean.TRUE);
    /** White glow behind every display word. Real per-syllable sources only;
     * synthetic plain-LRC timing never enables it. While {@link #dropShadow} is
     * on, only words held at least 1.5s glow; with it off, every word does,
     * regardless of duration. */
    public final Val<Boolean> glow = new Val<>(Boolean.TRUE);
    /** Soft drop shadow behind lyric, background-vocal and sub-line glyphs. Also
     * gates {@link #glow}'s 1.5s minimum sustain duration -- see its doc. */
    public final Val<Boolean> dropShadow = new Val<>(Boolean.TRUE);
    /** Apple-Music depth of field: blur lyric lines progressively toward the edges
     *  (the focused line stays sharp). Off by default — it adds a per-line blur layer. */
    public final Val<Boolean> edgeBlur = new Val<>(Boolean.FALSE);
    /** Manual lyric-timing offset in ms, subtracted from the playback position before
     *  it's compared against each line's timestamp: a larger value makes lyrics appear
     *  later (slower), a smaller/negative value makes them appear earlier (faster).
     *  Compensates for LRC files whose timestamps don't quite match the audio. */
    public final Val<Integer> offsetMs = new Val<>(0);
    /** Plain LRC (no real per-syllable timing) lines: true synthesizes an evenly-
     *  spaced per-character timing (spread across the line's real start/duration)
     *  so the same sweep/lift a real per-syllable source gets runs on it too,
     *  giving a linear front-to-back reveal. False (default) lights the whole
     *  line up together as one block instead (still gets scaleEmphasis,
     *  just no per-character sweep motion) — the synthetic timing is only an
     *  even split of the line's duration, so it never matches how the line is
     *  actually sung. No effect on lines that already have real per-syllable
     *  timing (YRC/LYS/TTML/QRC). */
    public final Val<Boolean> linearAnimForPlainLrc = new Val<>(Boolean.FALSE);
}
