package dev.t1m3.qplayer.lyric.skia;

// Lyric rendering config (subset of Haedus's MusicPlayerConfig the renderer
// reads). Values are mutable and wrapped so the renderer's `cfg.x.getValue()`
// calls port unchanged; the host pushes user settings into them.
public final class LyricConfig {

    public static final LyricConfig instance = new LyricConfig();

    /** Lyric font weight, mapped to a bundled PingFang face. */
    public enum FontWeight { THIN, LIGHT, REGULAR, MEDIUM }

    public static final class Val<T> {
        private volatile T value;
        Val(T value) { this.value = value; }
        public T getValue() { return value; }
        public void setValue(T value) { this.value = value; }
    }

    public final Val<Integer> lyricFontSize = new Val<>(28);
    public final Val<Integer> subFontSize = new Val<>(13);
    public final Val<FontWeight> fontWeight = new Val<>(FontWeight.REGULAR);
    /** Line-height multiplier applied to the lyric font size. */
    public final Val<Float> lineSpacing = new Val<>(2.00f);
    public final Val<Boolean> showRomaji = new Val<>(Boolean.TRUE);
    public final Val<Boolean> showTranslation = new Val<>(Boolean.TRUE);
    /** Apple-style spring physics for scroll + per-syllable lift. When off, the
     *  scroll uses a stiffer near-critically-damped spring and the lift a fixed
     *  cubic ease (the pre-0.4 tuning). */
    public final Val<Boolean> springPhysics = new Val<>(Boolean.TRUE);
    /** Active-line depth scaling (1.14× emphasis / 0.98× deselected). Off = no
     *  scaling, full-width wrap, no layout reflow. */
    public final Val<Boolean> scaleEmphasis = new Val<>(Boolean.TRUE);
    /** White glow behind sung syllables on the active line. */
    public final Val<Boolean> glow = new Val<>(Boolean.TRUE);
    /** Apple-Music depth of field: blur lyric lines progressively toward the edges
     *  (the focused line stays sharp). Off by default — it adds a per-line blur layer. */
    public final Val<Boolean> edgeBlur = new Val<>(Boolean.FALSE);
}
