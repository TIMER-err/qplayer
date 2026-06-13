package dev.t1m3.qplayer.android.lyric;

// Lyric rendering config (subset of Haedus's MusicPlayerConfig the renderer
// reads). Defaults are fine; values are wrapped so the renderer's
// `cfg.x.getValue()` calls port unchanged.
public final class LyricConfig {

    public static final LyricConfig instance = new LyricConfig();

    public enum FontWeight { REGULAR, MEDIUM }

    public static final class Val<T> {
        private final T value;
        Val(T value) { this.value = value; }
        public T getValue() { return value; }
    }

    public final Val<Integer> lyricFontSize = new Val<>(22);
    public final Val<Integer> subFontSize = new Val<>(13);
    public final Val<FontWeight> fontWeight = new Val<>(FontWeight.MEDIUM);
    public final Val<Boolean> showRomaji = new Val<>(Boolean.TRUE);
    public final Val<Boolean> showTranslation = new Val<>(Boolean.TRUE);
}
