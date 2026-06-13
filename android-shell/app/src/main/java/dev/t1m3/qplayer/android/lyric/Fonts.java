package dev.t1m3.qplayer.android.lyric;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontEdging;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.Typeface;

import java.util.HashMap;
import java.util.Map;

// Font cache for the lyric renderer. drawString uses a single typeface with no
// automatic fallback, so the lyric face must itself cover the glyphs we draw:
// CJK lyrics need a CJK-capable system face (Noto Sans CJK contains Latin too).
// Roboto (bundled, injected via init) is only a fallback when no CJK face is
// found. Fonts are CPU objects, safe to cache by (weight, size) across frames.
public final class Fonts {

    private Fonts() {
    }

    private static Typeface regular;
    private static Typeface medium;
    private static boolean mediumIsFake;   // no real Medium face -> embolden at draw
    private static final Map<Long, Font> cache = new HashMap<>();

    public static void init(byte[] regularTtf, byte[] mediumTtf) {
        FontMgr mgr = FontMgr.getDefault();
        if (mgr == null) return;

        Typeface cjk = matchCjk(mgr, FontStyle.NORMAL);
        if (cjk != null) {
            regular = cjk;
            Typeface cjkMed = matchCjk(mgr, new FontStyle(500, 5, FontStyle.NORMAL.getSlant()));
            if (cjkMed != null && !cjkMed.equals(cjk)) {
                medium = cjkMed;
            } else {
                medium = cjk;
                mediumIsFake = true;
            }
            return;
        }
        // No CJK face: fall back to bundled Roboto (Latin-only lyrics still work).
        if (regularTtf != null) regular = mgr.makeFromData(Data.makeFromBytes(regularTtf));
        if (mediumTtf != null) medium = mgr.makeFromData(Data.makeFromBytes(mediumTtf));
        if (medium == null) medium = regular;
    }

    private static Typeface matchCjk(FontMgr mgr, FontStyle style) {
        try {
            return mgr.matchFamilyStyleCharacter(
                    null, style, new String[]{"zh-Hans", "zh-CN", "ja"}, 0x4E2D);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Font getRegular(float size) {
        return font(false, size);
    }

    public static Font getMedium(float size) {
        return font(true, size);
    }

    private static Font font(boolean med, float size) {
        long key = ((long) Float.floatToIntBits(size) << 1) | (med ? 1 : 0);
        Font f = cache.get(key);
        if (f == null) {
            Typeface tf = med ? medium : regular;
            f = tf != null ? new Font(tf, size) : new Font().setSize(size);
            if (med && mediumIsFake) f.setEmboldened(true);
            f.setSubpixel(true);
            f.setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS);
            cache.put(key, f);
        }
        return f;
    }
}
