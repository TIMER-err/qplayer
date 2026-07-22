package dev.t1m3.qplayer.lyric.skia;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontEdging;
import io.github.humbleui.skija.FontHinting;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.Typeface;

import java.util.HashMap;
import java.util.Map;

// Font cache for the lyric renderer. drawString uses a single typeface with no
// automatic fallback, so the lyric face must itself cover the glyphs we draw —
// the bundled PingFang SC covers Latin + CJK across four weights. Scripts PingFang
// lacks (notably Hangul) are served by a system fallback face resolved on demand
// via {@link #korean(float)}. Fonts are CPU objects, safe to cache across frames.
public final class Fonts {

    private Fonts() {
    }

    public enum Weight { THIN, LIGHT, REGULAR, MEDIUM }

    private static final Typeface[] faces = new Typeface[Weight.values().length];
    // The four bundled OTF weights, kept around (not just consumed once by init())
    // so setUseSystemFont(false) can restore them without re-reading resources.
    private static final byte[][] bundledBytes = new byte[Weight.values().length][];
    private static boolean systemFontActive = false;
    private static Typeface icon;
    private static final Map<Long, Font> cache = new HashMap<>();
    private static final Map<Long, Font> iconCache = new HashMap<>();

    // Hangul fallback (PingFang has no Korean glyphs). Resolved once from the system
    // font manager and cached by size; null when the platform ships no Korean face.
    private static Typeface korean;
    private static boolean koreanResolved;
    private static final Map<Long, Font> koreanCache = new HashMap<>();

    private static final String[] KOREAN_CANDIDATES = {
        "Noto Sans CJK KR", "Noto Sans KR", "NotoSansCJK",
        "Source Han Sans KR", "Apple SD Gothic Neo", "Malgun Gothic", "Droid Sans Fallback"
    };

    // Thai fallback (PingFang has no Thai glyphs either). Same lazy-resolve-and-
    // cache shape as the Korean fallback above.
    private static Typeface thai;
    private static boolean thaiResolved;
    private static final Map<Long, Font> thaiCache = new HashMap<>();

    private static final String[] THAI_CANDIDATES = {
        "Noto Sans Thai", "Leelawadee UI", "Tahoma"
    };

    /** Load the four bundled PingFang weights (any may be null → falls back to Regular). */
    public static void init(byte[] thin, byte[] light, byte[] regular, byte[] medium) {
        bundledBytes[Weight.THIN.ordinal()] = thin;
        bundledBytes[Weight.LIGHT.ordinal()] = light;
        bundledBytes[Weight.REGULAR.ordinal()] = regular;
        bundledBytes[Weight.MEDIUM.ordinal()] = medium;
        if (!systemFontActive) applyBundledFaces();
    }

    /** Settings-driven toggle (issue #15's "内置字体 / 系统默认字体" option): switch the
     *  lyric-page text between the bundled PingFang SC faces and whatever the OS
     *  reports as its default UI font (via Skija's FontMgr — no file path guessing,
     *  works the same on every platform). Safe to call before or after {@link #init}.
     *  Live: the next {@link #get} call (and thus the next lyric repaint) picks up
     *  the change — no cache invalidation needed elsewhere since callers don't hold
     *  onto {@link Font} instances across a toggle. */
    public static void setUseSystemFont(boolean useSystem) {
        if (systemFontActive == useSystem) return;
        systemFontActive = useSystem;
        if (useSystem) {
            FontMgr mgr = FontMgr.getDefault();
            // null family name asks Skia for the platform's default face, same
            // convention already used by matchFamilyStyleCharacter below.
            Typeface sys = mgr != null ? mgr.matchFamilyStyle(null, FontStyle.NORMAL) : null;
            if (sys != null) {
                for (int i = 0; i < faces.length; i++) faces[i] = sys;
            } else {
                systemFontActive = false; // no system match — stay on bundled rather than blank
            }
        } else {
            applyBundledFaces();
        }
        cache.clear();
    }

    private static void applyBundledFaces() {
        FontMgr mgr = FontMgr.getDefault();
        if (mgr == null) return;
        faces[Weight.THIN.ordinal()] = make(mgr, bundledBytes[Weight.THIN.ordinal()]);
        faces[Weight.LIGHT.ordinal()] = make(mgr, bundledBytes[Weight.LIGHT.ordinal()]);
        faces[Weight.REGULAR.ordinal()] = make(mgr, bundledBytes[Weight.REGULAR.ordinal()]);
        faces[Weight.MEDIUM.ordinal()] = make(mgr, bundledBytes[Weight.MEDIUM.ordinal()]);
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

    /**
     * A Hangul-capable system Font at {@code size}, animation-configured to match the
     * lyric faces, or null when the platform has no Korean font. One weight only — the
     * fallback is about legibility, not matching PingFang's weight curve. The Korean
     * candidates are pan-CJK (Noto Sans CJK / Droid Sans Fallback), so a Korean line
     * mixed with Han/Latin stays in one coherent face.
     */
    public static Font korean(float size) {
        Typeface tf = koreanFace();
        if (tf == null) return null;
        long key = Float.floatToIntBits(size);
        Font f = koreanCache.get(key);
        if (f == null) {
            f = new Font(tf, size);
            f.setBaselineSnapped(false);
            f.setSubpixel(true);
            f.setHinting(FontHinting.NONE);
            f.setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS);
            koreanCache.put(key, f);
        }
        return f;
    }

    private static Typeface koreanFace() {
        if (koreanResolved) return korean;
        koreanResolved = true;
        FontMgr mgr = FontMgr.getDefault();
        if (mgr == null) return null;
        for (String name : KOREAN_CANDIDATES) {
            // matchFamilyStyle returns the closest face even for an unknown family,
            // so confirm the result actually carries a Hangul glyph before taking it.
            Typeface t = mgr.matchFamilyStyle(name, FontStyle.NORMAL);
            if (t != null && covers(t, '가')) { korean = t; return korean; }
        }
        try {
            korean = mgr.matchFamilyStyleCharacter(
                null, FontStyle.NORMAL, new String[]{"ko", "ko-KR"}, 0xAC00);
        } catch (Throwable ignored) {
            korean = null;
        }
        return korean;
    }

    /**
     * A Thai-capable system Font at {@code size}, mirroring {@link #korean(float)} —
     * PingFang SC has no Thai glyphs, so lines containing Thai script fall back to
     * whatever the OS reports for it. One weight only, same rationale as Korean.
     */
    public static Font thai(float size) {
        Typeface tf = thaiFace();
        if (tf == null) return null;
        long key = Float.floatToIntBits(size);
        Font f = thaiCache.get(key);
        if (f == null) {
            f = new Font(tf, size);
            f.setBaselineSnapped(false);
            f.setSubpixel(true);
            f.setHinting(FontHinting.NONE);
            f.setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS);
            thaiCache.put(key, f);
        }
        return f;
    }

    private static Typeface thaiFace() {
        if (thaiResolved) return thai;
        thaiResolved = true;
        FontMgr mgr = FontMgr.getDefault();
        if (mgr == null) return null;
        for (String name : THAI_CANDIDATES) {
            Typeface t = mgr.matchFamilyStyle(name, FontStyle.NORMAL);
            if (t != null && covers(t, 'ก')) { thai = t; return thai; }
        }
        try {
            thai = mgr.matchFamilyStyleCharacter(
                null, FontStyle.NORMAL, new String[]{"th", "th-TH"}, 0x0E01);
        } catch (Throwable ignored) {
            thai = null;
        }
        return thai;
    }

    private static boolean covers(Typeface t, char c) {
        try {
            short[] g = t.getStringGlyphs(String.valueOf(c));
            return g.length > 0 && g[0] != 0;
        } catch (Throwable e) {
            return false;
        }
    }
}
