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
    // A user-picked specific family (Windows font-picker UI), takes precedence over
    // systemFontActive when set; null/empty falls through to it. Kept separate from
    // systemFontActive rather than folded into one enum so a family match failing
    // (uninstalled/renamed font) has an obvious, already-tested fallback path to
    // reuse (the same one systemFontActive itself falls back to).
    private static String customFamilyName;
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

    // Japanese fallback: PingFang SC is a Simplified-Chinese CJK face and covers a
    // lot of shared Han, but has no Hiragana/Katakana glyphs at all — those render
    // as tofu boxes without this. Same lazy-resolve-and-cache shape as Korean/Thai.
    private static Typeface japanese;
    private static boolean japaneseResolved;
    private static final Map<Long, Font> japaneseCache = new HashMap<>();

    private static final String[] JAPANESE_CANDIDATES = {
        "Noto Sans CJK JP", "Noto Sans JP", "Hiragino Sans", "Hiragino Kaku Gothic ProN",
        "Yu Gothic UI", "Meiryo UI", "Meiryo", "MS Gothic", "Droid Sans Fallback"
    };

    /** Load the four bundled PingFang weights (any may be null → falls back to Regular). */
    public static void init(byte[] thin, byte[] light, byte[] regular, byte[] medium) {
        bundledBytes[Weight.THIN.ordinal()] = thin;
        bundledBytes[Weight.LIGHT.ordinal()] = light;
        bundledBytes[Weight.REGULAR.ordinal()] = regular;
        bundledBytes[Weight.MEDIUM.ordinal()] = medium;
        reapply();
    }

    /** Settings-driven toggle (issue #15's "内置字体 / 系统默认字体" option): switch the
     *  lyric-page text between the bundled PingFang SC faces and whatever the OS
     *  reports as its default UI font (via Skija's FontMgr — no file path guessing,
     *  works the same on every platform). Safe to call before or after {@link #init}.
     *  Live: the next {@link #get} call (and thus the next lyric repaint) picks up
     *  the change — no cache invalidation needed elsewhere since callers don't hold
     *  onto {@link Font} instances across a toggle. */
    public static void setUseSystemFont(boolean useSystem) {
        systemFontActive = useSystem;
        reapply();
    }

    /** Windows font-picker UI: pin the lyric page to one specific installed family
     *  (from {@link #listFamilies()}), overriding {@link #setUseSystemFont}. Pass
     *  null/empty to clear the override and fall back to whatever setUseSystemFont
     *  last set. Safe to call before {@link #init}; a family that fails to resolve
     *  (uninstalled, renamed) just falls through to the system-default/bundled path
     *  instead of leaving the lyric page blank. */
    public static void setCustomFamily(String family) {
        customFamilyName = (family != null && !family.isEmpty()) ? family : null;
        reapply();
    }

    /** Every family name the platform's font manager knows about, for the picker UI
     *  to list. Cheap to call repeatedly (Skija just walks its own index), so no
     *  caching here — the caller (Settings) reads it once at startup. */
    public static String[] listFamilies() {
        FontMgr mgr = FontMgr.getDefault();
        if (mgr == null) return new String[0];
        int n = mgr.getFamiliesCount();
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = mgr.getFamilyName(i);
        return out;
    }

    private static void reapply() {
        if (customFamilyName != null) {
            FontMgr mgr = FontMgr.getDefault();
            Typeface t = mgr != null ? mgr.matchFamilyStyle(customFamilyName, FontStyle.NORMAL) : null;
            if (t != null) {
                for (int i = 0; i < faces.length; i++) faces[i] = t;
                cache.clear();
                return;
            }
            // Match failed — fall through to the system-default/bundled path below
            // rather than leaving faces on a stale typeface.
        }
        if (systemFontActive) {
            FontMgr mgr = FontMgr.getDefault();
            // Desktop's Skija backends (DirectWrite/CoreText/Fontconfig) treat a
            // null family name as "give me the platform's default UI face" — but
            // Android's SkFontMgr_android binding needs an actual family name (a
            // bare null lookup returns null there), the same reason korean()/
            // thai()/japanese() below never risk a null-family query either. Try
            // the null-family shortcut first, then fall back to naming known
            // system families explicitly.
            //
            // Bug fixed 2026-07-23: neither step originally checked the resolved
            // face actually HAS CJK glyphs before accepting it — unlike korean()/
            // thai()/japanese() below, which all verify via covers(). A platform's
            // "default UI font" is very often Latin-only (Android's null-family/
            // "sans-serif" both land on Roboto; Windows null-family lands on Segoe
            // UI — neither carries CJK glyphs, Windows' own font-linking that
            // normally papers over this for real apps doesn't apply to a raw
            // Skija Typeface), so this silently produced a face that rendered
            // English fine and every CJK character as tofu. Every candidate below
            // (including the null-family shortcut) now has to pass the same
            // covers() check real per-script fallback candidates do.
            Typeface sys = null;
            if (mgr != null) {
                Typeface nullFamily = mgr.matchFamilyStyle(null, FontStyle.NORMAL);
                if (nullFamily != null && covers(nullFamily, '中')) sys = nullFamily;
            }
            if (sys == null && mgr != null) {
                for (String name : SYSTEM_DEFAULT_CANDIDATES) {
                    Typeface t = mgr.matchFamilyStyle(name, FontStyle.NORMAL);
                    if (t != null && covers(t, '中')) { sys = t; break; }
                }
            }
            if (sys != null) {
                for (int i = 0; i < faces.length; i++) faces[i] = sys;
                cache.clear();
                return;
            }
            // Nothing on this platform covers CJK under the "system default" label
            // at all — fall through to the bundled PingFang SC rather than ship a
            // face that would tofu every CJK character.
        }
        applyBundledFaces();
        cache.clear();
    }

    // Named system-family fallbacks, tried only if the null-family "give me the
    // platform default" lookup above didn't resolve to something with real CJK
    // glyphs. Covers the three desktop platforms' actual CJK UI fonts plus
    // Android's AOSP fonts.xml families — every entry still has to pass the
    // covers() check, so a Latin-only match (e.g. Android's "sans-serif"/Roboto,
    // Windows' Arial) is skipped rather than silently accepted.
    private static final String[] SYSTEM_DEFAULT_CANDIDATES = {
        // Windows
        "Microsoft YaHei UI", "Microsoft YaHei", "Microsoft JhengHei UI", "Microsoft JhengHei",
        "SimSun", "SimHei",
        // macOS
        "PingFang SC", "PingFang TC", "Heiti SC",
        // Linux
        "Noto Sans CJK SC", "Noto Sans SC", "WenQuanYi Zen Hei", "WenQuanYi Micro Hei",
        // Android (AOSP fonts.xml)
        "Noto Sans CJK SC", "Droid Sans Fallback",
    };

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

    /**
     * A Hiragana/Katakana-capable system Font at {@code size}, mirroring
     * {@link #korean(float)} — PingFang SC has no Japanese kana glyphs (they'd
     * otherwise render as tofu boxes), so lines containing kana fall back to
     * whatever the OS reports for Japanese. Shared Han characters don't need this
     * (PingFang already covers them); only the kana-detection call site cares.
     */
    public static Font japanese(float size) {
        Typeface tf = japaneseFace();
        if (tf == null) return null;
        long key = Float.floatToIntBits(size);
        Font f = japaneseCache.get(key);
        if (f == null) {
            f = new Font(tf, size);
            f.setBaselineSnapped(false);
            f.setSubpixel(true);
            f.setHinting(FontHinting.NONE);
            f.setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS);
            japaneseCache.put(key, f);
        }
        return f;
    }

    private static Typeface japaneseFace() {
        if (japaneseResolved) return japanese;
        japaneseResolved = true;
        FontMgr mgr = FontMgr.getDefault();
        if (mgr == null) return null;
        for (String name : JAPANESE_CANDIDATES) {
            // Hiragana 'あ' (U+3042) — matchFamilyStyle returns the closest face even
            // for an unknown family, so confirm it actually carries the glyph first.
            Typeface t = mgr.matchFamilyStyle(name, FontStyle.NORMAL);
            if (t != null && covers(t, 'あ')) { japanese = t; return japanese; }
        }
        try {
            japanese = mgr.matchFamilyStyleCharacter(
                null, FontStyle.NORMAL, new String[]{"ja", "ja-JP"}, 0x3042);
        } catch (Throwable ignored) {
            japanese = null;
        }
        return japanese;
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
