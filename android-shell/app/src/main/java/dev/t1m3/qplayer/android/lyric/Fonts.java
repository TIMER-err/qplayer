package dev.t1m3.qplayer.android.lyric;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontEdging;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Typeface;

import java.util.HashMap;
import java.util.Map;

// Font cache for the lyric renderer. drawString uses a single typeface with no
// automatic fallback, so the lyric face must itself cover the glyphs we draw —
// the bundled PingFang SC covers Latin + CJK across four weights. Fonts are CPU
// objects, safe to cache by (weight, size) across frames.
public final class Fonts {

    private Fonts() {
    }

    public enum Weight { THIN, LIGHT, REGULAR, MEDIUM }

    private static final Typeface[] faces = new Typeface[Weight.values().length];
    private static Typeface icon;
    private static final Map<Long, Font> cache = new HashMap<>();
    private static final Map<Long, Font> iconCache = new HashMap<>();

    /** Load the four bundled PingFang weights (any may be null → falls back to Regular). */
    public static void init(byte[] thin, byte[] light, byte[] regular, byte[] medium) {
        FontMgr mgr = FontMgr.getDefault();
        if (mgr == null) return;
        faces[Weight.THIN.ordinal()] = make(mgr, thin);
        faces[Weight.LIGHT.ordinal()] = make(mgr, light);
        faces[Weight.REGULAR.ordinal()] = make(mgr, regular);
        faces[Weight.MEDIUM.ordinal()] = make(mgr, medium);
    }

    private static Typeface make(FontMgr mgr, byte[] bytes) {
        if (bytes == null) return null;
        try {
            return mgr.makeFromData(Data.makeFromBytes(bytes));
        } catch (Throwable t) {
            return null;
        }
    }

    // Material Symbols face for icon glyphs. Drawn via a shaped TextLine (the
    // font's GSUB turns the ligature name into the glyph), so no codepoint table.
    public static void initIcon(byte[] iconTtf) {
        FontMgr mgr = FontMgr.getDefault();
        if (mgr == null || iconTtf == null) return;
        icon = make(mgr, iconTtf);
    }

    public static Font getIcon(float size) {
        long key = Float.floatToIntBits(size);
        Font f = iconCache.get(key);
        if (f == null) {
            f = icon != null ? new Font(icon, size) : new Font().setSize(size);
            f.setSubpixel(true);
            f.setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS);
            iconCache.put(key, f);
        }
        return f;
    }

    public static Font get(Weight w, float size) {
        long key = ((long) Float.floatToIntBits(size) << 2) | w.ordinal();
        Font f = cache.get(key);
        if (f == null) {
            Typeface tf = faces[w.ordinal()];
            if (tf == null) tf = faces[Weight.REGULAR.ordinal()];
            f = tf != null ? new Font(tf, size) : new Font().setSize(size);
            f.setSubpixel(true);
            f.setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS);
            cache.put(key, f);
        }
        return f;
    }
}
