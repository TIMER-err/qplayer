package dev.t1m3.qplayer.lyric.skia;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontEdging;
import io.github.humbleui.skija.FontHinting;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.FontVariation;
import io.github.humbleui.skija.FontVariationAxis;
import io.github.humbleui.skija.Typeface;

import java.util.HashMap;
import java.util.Map;

// Font cache for the lyric renderer. drawString uses a single typeface with no
// automatic fallback, so the lyric face must itself cover the glyphs we draw —
// the bundled PingFang SC covers Latin + CJK across four weights. Scripts PingFang
// lacks (notably Hangul) are served by a system fallback face resolved on demand
// via {@link #korean(float)}. Fonts are CPU objects, safe to cache across frames.
//
// The face in use comes from one setting, {@link #setSelection}: bundled, the OS
// default, or a named family. All three go through the same per-weight resolution,
// so the lyric weight setting keeps working whichever is picked.
public final class Fonts {

    private Fonts() {
    }

    public enum Weight { THIN, LIGHT, REGULAR, MEDIUM, BOLD }

    /** {@link #setSelection} sentinel for "whatever the OS reports as its default
     *  UI font". Anything else non-empty is a literal family name; empty/null is
     *  the bundled PingFang SC. A font family can't be called this (FontMgr family
     *  names are display names like "Microsoft YaHei"), so one string covers all
     *  three sources without a parallel flag to keep in sync. */
    public static final String SYSTEM = "system";

    /** OpenType weight class for each bundled weight, used to ask FontMgr for the
     *  matching face of a system/custom family (and to set a variable font's
     *  {@code wght} axis). PingFang's own four files are Thin/Light/Regular/Medium. */
    private static final int[] WEIGHT_VALUES = {100, 300, 400, 500, 700};

    private static final Typeface[] faces = new Typeface[Weight.values().length];
    // Bundled faces are separate from the active table so switching to a system
    // font and back doesn't require keeping the original 52 MiB of OTF byte arrays
    // alive. Current hosts install a lazy loader: only the lyric weight actually
    // selected by the user is parsed, instead of all four PingFang files at startup.
    private static final Typeface[] bundledFaces = new Typeface[Weight.values().length];
    private static BundledFontLoader bundledLoader;
    // Null/empty = bundled PingFang SC, SYSTEM = the OS default UI font, anything
    // else = that installed family. One value instead of the old
    // useSystemFont + customFamily pair, whose precedence ("custom wins unless it
    // fails to resolve, then system, then bundled") was implicit in two settings
    // that could disagree.
    private static String selection;
    // Family name the current selection actually resolved to, null while on the
    // bundled faces. Published for hosts that need the matching font FILE rather
    // than a Typeface — see activeFamilyName().
    private static String activeFamily;
    private static Typeface icon;
    private static final Map<Long, Font> cache = new HashMap<>();
    private static final Map<Long, Font> iconCache = new HashMap<>();
    private static final Map<Long, Font> hanFonts = new HashMap<>();

    @FunctionalInterface
    public interface BundledFontLoader {
        byte[] load(Weight weight) throws Exception;
    }

    // OpenType weight each face in `faces` was resolved for, so a fallback face can
    // be matched to the same weight as the lyric face that needs it. Identity-keyed:
    // these are the exact Typeface instances handed out by get(), and they're
    // replaced wholesale on every reapply().
    private static final java.util.IdentityHashMap<Typeface, Integer> faceWeights =
            new java.util.IdentityHashMap<>();

    private static final String[] KOREAN_CANDIDATES = {
        "Noto Sans CJK KR", "Noto Sans KR", "NotoSansCJK",
        "Source Han Sans KR", "Apple SD Gothic Neo", "Malgun Gothic", "Droid Sans Fallback"
    };

    private static final String[] THAI_CANDIDATES = {
        "Noto Sans Thai", "Leelawadee UI", "Tahoma"
    };

    private static final String[] JAPANESE_CANDIDATES = {
        "Noto Sans CJK JP", "Noto Sans JP", "Hiragino Sans", "Hiragino Kaku Gothic ProN",
        "Yu Gothic UI", "Meiryo UI", "Meiryo", "MS Gothic", "Droid Sans Fallback"
    };

    // Scripts the bundled PingFang SC has no glyphs for at all: Hangul, Thai, and
    // Japanese kana (PingFang SC is a Simplified-Chinese face — it covers a lot of
    // shared Han, but has no hiragana/katakana, which would otherwise draw as tofu).
    private static final Fallback KOREAN = new Fallback('가', KOREAN_CANDIDATES, new String[]{"ko", "ko-KR"});
    private static final Fallback THAI = new Fallback('ก', THAI_CANDIDATES, new String[]{"th", "th-TH"});
    private static final Fallback JAPANESE = new Fallback('あ', JAPANESE_CANDIDATES, new String[]{"ja", "ja-JP"});

    /**
     * One script's fallback face, resolved from the system font manager on demand.
     *
     * <p>Only used when the face actually in use has no glyph for the script: a
     * system/custom selection that covers kana (any pan-CJK JP face, a Japanese
     * system default) keeps drawing its own kana, instead of every kana silently
     * jumping to a different family — which is what made kana ignore the font
     * setting entirely while the surrounding Han followed it.
     *
     * <p>Resolved per weight rather than once, so the weight setting reaches
     * fallback text too (same three tiers as {@link #applyFamilyFaces}: a real
     * static face, else the variable {@code wght} axis, else the closest match).
     */
    private static final class Fallback {
        private final char probe;
        private final String[] families;
        private final String[] bcp47;
        // Resolved face per requested weight; a null VALUE means "this platform has
        // no face for this script", cached so the candidate walk runs at most once.
        private final Map<Integer, Typeface> byWeight = new HashMap<>();
        private final Map<Long, Font> fonts = new HashMap<>();
        // Whether a lyric face already covers this script — identity-keyed on the
        // Typeface, cleared with the rest whenever the selection changes.
        private final java.util.IdentityHashMap<Typeface, Boolean> baseCoverage =
                new java.util.IdentityHashMap<>();

        Fallback(char probe, String[] families, String[] bcp47) {
            this.probe = probe;
            this.families = families;
            this.bcp47 = bcp47;
        }

        /** A face for this script matching {@code base}'s size and weight, or null
         *  when {@code base} already covers the script (use it as-is) or the platform
         *  ships nothing for it. */
        Font fontFor(Font base) {
            if (base == null) return null;
            Typeface bt = base.getTypeface();
            if (bt != null && baseCovers(bt)) return null;
            int weight = weightOf(bt);
            Typeface tf = face(weight);
            if (tf == null) return null;
            long key = ((long) Float.floatToIntBits(base.getSize()) << 16) | (weight & 0xFFFFL);
            Font f = fonts.get(key);
            if (f == null) {
                f = new Font(tf, base.getSize());
                f.setBaselineSnapped(false);
                f.setSubpixel(true);
                f.setHinting(FontHinting.NONE);
                f.setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS);
                fonts.put(key, f);
            }
            return f;
        }

        private boolean baseCovers(Typeface t) {
            Boolean known = baseCoverage.get(t);
            if (known != null) return known;
            boolean ok = covers(t, probe);
            baseCoverage.put(t, ok);
            return ok;
        }

        private Typeface face(int weight) {
            if (byWeight.containsKey(weight)) return byWeight.get(weight);
            Typeface t = resolve(weight);
            byWeight.put(weight, t);
            return t;
        }

        private Typeface resolve(int weight) {
            FontMgr mgr = FontMgr.getDefault();
            if (mgr == null) return null;
            FontStyle style = FontStyle.NORMAL.withWeight(weight);
            for (String name : families) {
                // matchFamilyStyle returns the closest face even for an unknown
                // family, so confirm the result actually carries the glyph.
                Typeface t = mgr.matchFamilyStyle(name, style);
                if (t != null && covers(t, probe)) return atWeight(t, weight);
            }
            // Ask for ANY installed font covering the character instead of guessing
            // family names — some OEM skins ship their CJK face under a name no
            // candidate list would have.
            try {
                Typeface t = mgr.matchFamilyStyleCharacter(null, style, bcp47, probe);
                if (t != null && covers(t, probe)) return atWeight(t, weight);
            } catch (Throwable ignored) {
                // fall through
            }
            return null;
        }

        private void invalidateBaseCoverage() {
            baseCoverage.clear();
        }
    }

    /**
     * Install a lazy bundled-font source. No font file is read until the lyric
     * renderer asks for that exact weight, and the temporary byte array is released
     * as soon as Skija has created its native Typeface.
     */
    public static void init(BundledFontLoader loader) {
        bundledLoader = loader;
        java.util.Arrays.fill(bundledFaces, null);
        reapply();
    }

    /** Load already supplied bundled weights without retaining their byte arrays.
     *  Kept for embedders using the original API; QPlayer's hosts use the lazy form. */
    public static void init(byte[] thin, byte[] light, byte[] regular, byte[] medium) {
        bundledLoader = null;
        FontMgr mgr = FontMgr.getDefault();
        if (mgr != null) {
            bundledFaces[Weight.THIN.ordinal()] = make(mgr, thin);
            bundledFaces[Weight.LIGHT.ordinal()] = make(mgr, light);
            bundledFaces[Weight.REGULAR.ordinal()] = make(mgr, regular);
            bundledFaces[Weight.MEDIUM.ordinal()] = make(mgr, medium);
        }
        reapply();
    }

    /** Settings-driven lyric font source: null/empty for the bundled PingFang SC,
     *  {@link #SYSTEM} for the OS default UI font, or any family name from
     *  {@link #listFamilies()}. Resolved through Skija's FontMgr — no file-path
     *  guessing, same code on every platform. Safe to call before or after
     *  {@link #init}; anything that fails to resolve (uninstalled/renamed family,
     *  a "system default" with no CJK coverage) falls back to the bundled faces
     *  rather than leaving the lyric page blank or full of tofu. Live: the next
     *  {@link #get} call (and thus the next lyric repaint) picks up the change. */
    public static void setSelection(String sel) {
        selection = (sel != null && !sel.isEmpty()) ? sel : null;
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

    /** The family the lyric page is actually rendering with, or null when it's on
     *  the bundled faces — including when a selection failed to resolve and fell
     *  back. Hosts that can only load fonts from a FILE (qml4j's uiTypefaces takes
     *  raw bytes, not a Typeface) use this to look up the same family Skija picked,
     *  rather than repeating the resolution against a different index. */
    public static String activeFamilyName() {
        return activeFamily;
    }

    private static void reapply() {
        FontMgr mgr = FontMgr.getDefault();
        if (selection != null && mgr != null) {
            Typeface base = SYSTEM.equals(selection) ? systemDefaultFace(mgr)
                    : mgr.matchFamilyStyle(selection, FontStyle.NORMAL);
            // A system default with no CJK coverage never becomes `base` (see
            // systemDefaultFace); a custom family that fails to resolve at all
            // (uninstalled, renamed) leaves it null. Either way fall through to
            // the bundled faces rather than leaving faces on a stale typeface.
            String family = base != null ? familyNameOf(base, selection) : null;
            if (base != null && applyFamilyFaces(mgr, family, base)) {
                activeFamily = family;
                onFacesChanged();
                return;
            }
        }
        activeFamily = null;
        applyBundledFaces();
        onFacesChanged();
    }

    /** Drop everything keyed on the previous faces and re-index the new ones. The
     *  per-script fallbacks cache "does this face already cover the script" by
     *  Typeface identity, so those answers have to go with the faces they described. */
    private static void onFacesChanged() {
        cache.clear();
        faceWeights.clear();
        for (Weight w : Weight.values()) {
            Typeface t = faces[w.ordinal()];
            if (t != null) faceWeights.put(t, WEIGHT_VALUES[w.ordinal()]);
        }
        KOREAN.invalidateBaseCoverage();
        THAI.invalidateBaseCoverage();
        JAPANESE.invalidateBaseCoverage();
    }

    /** Resolve the four lyric weights within one family, so the weight setting keeps
     *  working for a system/custom font instead of pinning every weight to the same
     *  face (which is what happened while system/custom fonts were a single
     *  Typeface copied into all four slots). Three tiers per weight:
     *  a real static face of that weight if the family ships one, else the family's
     *  variable {@code wght} axis retargeted to it, else {@code base}. Returns false
     *  only when nothing at all resolved, so the caller can fall back to bundled. */
    private static boolean applyFamilyFaces(FontMgr mgr, String family, Typeface base) {
        Typeface[] resolved = new Typeface[Weight.values().length];
        boolean any = false;
        for (Weight w : Weight.values()) {
            int target = WEIGHT_VALUES[w.ordinal()];
            Typeface t = family != null ? mgr.matchFamilyStyle(family, FontStyle.NORMAL.withWeight(target)) : null;
            // matchFamilyStyle never fails outright — it returns the CLOSEST face in
            // the family, so a family with only one weight hands back that same face
            // for all four. Retarget through the variation axis in that case; fonts
            // with real per-weight files report the weight we asked for and are used
            // as-is.
            if (t == null || t.getFontStyle().getWeight() != target) {
                Typeface variable = cloneAtWeight(t != null ? t : base, target);
                if (variable != null) t = variable;
            }
            if (t == null) t = base;
            if (t != null) any = true;
            resolved[w.ordinal()] = t;
        }
        if (!any) return false;
        System.arraycopy(resolved, 0, faces, 0, faces.length);
        return true;
    }

    /** Retarget a variable font to {@code weight} via its {@code wght} axis (clamped
     *  to the axis' own range), or null when the face isn't variable / has no such
     *  axis — the common case for the per-weight-file families this falls back from. */
    private static Typeface cloneAtWeight(Typeface t, int weight) {
        if (t == null) return null;
        try {
            FontVariationAxis[] axes = t.getVariationAxes();
            if (axes == null) return null;
            for (FontVariationAxis axis : axes) {
                if (!"wght".equals(axis.getTag())) continue;
                float v = Math.max(axis.getMinValue(), Math.min(axis.getMaxValue(), weight));
                return t.makeClone(new FontVariation("wght", v));
            }
        } catch (Throwable ignored) {
            // Not variable, or the platform's Typeface can't be cloned — caller
            // falls back to the closest static face.
        }
        return null;
    }

    /** The family name to re-query per weight. Prefer the resolved face's own name
     *  (the SYSTEM path has no user-supplied name to start from, and a face found by
     *  {@code matchFamilyStyleCharacter} can be called something completely different
     *  from any candidate we tried), falling back to what the caller asked for. */
    private static String familyNameOf(Typeface t, String requested) {
        try {
            String name = t.getFamilyName();
            if (name != null && !name.isEmpty()) return name;
        } catch (Throwable ignored) {
            // fall through to the requested name
        }
        return SYSTEM.equals(requested) ? null : requested;
    }

    /** Whatever the OS reports as its default UI font, or null when nothing under
     *  that label covers CJK. */
    private static Typeface systemDefaultFace(FontMgr mgr) {
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
        // (including the null-family shortcut) has to pass the same covers()
        // check real per-script fallback candidates do.
        Typeface nullFamily = mgr.matchFamilyStyle(null, FontStyle.NORMAL);
        if (nullFamily != null && covers(nullFamily, '中')) return nullFamily;
        for (String name : SYSTEM_DEFAULT_CANDIDATES) {
            Typeface t = mgr.matchFamilyStyle(name, FontStyle.NORMAL);
            if (t != null && covers(t, '中')) return t;
        }
        // Last resort, same as korean()/thai()/japanese() below: ask the font
        // manager for ANY installed font that covers this codepoint, instead of
        // guessing family names. Some OEM Android skins (e.g. HarmonyOS) don't
        // expose their CJK system font under any of the names above, so the
        // named-candidate loop never matches even though the device clearly has
        // a working CJK font — matchFamilyStyleCharacter finds it regardless of
        // what it's actually called.
        try {
            Typeface t = mgr.matchFamilyStyleCharacter(
                null, FontStyle.NORMAL, new String[]{"zh", "zh-CN"}, '中');
            if (t != null && covers(t, '中')) return t;
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
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
        System.arraycopy(bundledFaces, 0, faces, 0, faces.length);
    }

    private static synchronized Typeface bundledFace(Weight weight) {
        int index = weight.ordinal();
        Typeface face = bundledFaces[index];
        if (face != null || bundledLoader == null) return face;
        FontMgr mgr = FontMgr.getDefault();
        if (mgr == null) return null;
        try {
            byte[] bytes = bundledLoader.load(weight);
            face = make(mgr, bytes);
            if (weight == Weight.BOLD) {
                dev.t1m3.qplayer.util.Logger.info(
                        "lyric BOLD font: {} bytes, face={}", bytes == null ? 0 : bytes.length,
                        face != null ? "ok" : "FAILED(maybe fallback to Regular)");
            }
        } catch (Throwable t) {
            face = null;
            if (weight == Weight.BOLD) {
                dev.t1m3.qplayer.util.Logger.warn("lyric BOLD font load error: {}", String.valueOf(t));
            }
        }
        bundledFaces[index] = face;
        return face;
    }

    private static Typeface make(FontMgr mgr, byte[] bytes) {
        if (bytes == null) return null;
        try (Data data = Data.makeFromBytes(bytes)) {
            return mgr.makeFromData(data);
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
            if (tf == null && activeFamily == null) {
                tf = bundledFace(w);
                faces[w.ordinal()] = tf;
                if (tf != null) faceWeights.put(tf, WEIGHT_VALUES[w.ordinal()]);
            }
            if (tf == null) {
                tf = faces[Weight.REGULAR.ordinal()];
                if (tf == null && activeFamily == null) {
                    tf = bundledFace(Weight.REGULAR);
                    faces[Weight.REGULAR.ordinal()] = tf;
                    if (tf != null) faceWeights.put(tf, WEIGHT_VALUES[Weight.REGULAR.ordinal()]);
                }
            }
            f = tf != null ? new Font(tf, size) : new Font().setSize(size);
            f.setSubpixel(true);
            f.setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS);
            cache.put(key, f);
        }
        return f;
    }

    /** The Hangul face for {@code base}'s size/weight, or null when {@code base}
     *  already covers Hangul (draw with it) or the platform ships no Korean font.
     *  The Korean candidates are pan-CJK (Noto Sans CJK / Droid Sans Fallback), so a
     *  Korean line mixed with Han/Latin stays in one coherent face. */
    public static Font korean(Font base) {
        return KOREAN.fontFor(base);
    }

    /** The Thai face for {@code base}'s size/weight, mirroring {@link #korean(Font)}. */
    public static Font thai(Font base) {
        return THAI.fontFor(base);
    }

    /**
     * Han fallback for faces without CJK glyphs (e.g. the SF Pro Bold lyric face):
     * returns a size-matched bundled PingFang Medium when the base face lacks Han,
     * null when the base already covers it (use it as-is).
     */
    public static Font han(Font base) {
        if (base == null) return null;
        Typeface bt = base.getTypeface();
        if (bt != null && covers(bt, '汉')) return null;
        Typeface tf = bundledFace(Weight.MEDIUM);
        if (tf == null) return null;
        long key = ((long) Float.floatToIntBits(base.getSize()) << 16);
        Font f = hanFonts.get(key);
        if (f == null) {
            f = new Font(tf, base.getSize());
            f.setBaselineSnapped(false);
            f.setSubpixel(true);
            f.setHinting(FontHinting.NONE);
            f.setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS);
            hanFonts.put(key, f);
        }
        return f;
    }

    /** The kana face for {@code base}'s size/weight, mirroring {@link #korean(Font)}.
     *  Shared Han doesn't need this (PingFang already covers it); only kana does. */
    public static Font japanese(Font base) {
        return JAPANESE.fontFor(base);
    }

    /** Retarget a face to {@code weight}, preferring the family's own static face and
     *  falling back to its variable {@code wght} axis. */
    private static Typeface atWeight(Typeface t, int weight) {
        if (t.getFontStyle().getWeight() == weight) return t;
        Typeface variable = cloneAtWeight(t, weight);
        return variable != null ? variable : t;
    }

    /** The weight a lyric face was resolved for — the value get() built it with, or
     *  the face's own OS/2 weight for anything not from the lyric face table. */
    private static int weightOf(Typeface t) {
        if (t == null) return WEIGHT_VALUES[Weight.REGULAR.ordinal()];
        Integer known = faceWeights.get(t);
        if (known != null) return known;
        try {
            return t.getFontStyle().getWeight();
        } catch (Throwable e) {
            return WEIGHT_VALUES[Weight.REGULAR.ordinal()];
        }
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
