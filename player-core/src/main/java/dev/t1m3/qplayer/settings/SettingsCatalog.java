package dev.t1m3.qplayer.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Every setting the app has, declared once. The settings page is generated from
 * this list at runtime — adding a row here is the whole change, on both
 * platforms at once.
 *
 * <p>Rows render in declaration order under the category they name, and
 * consecutive rows sharing a {@code group} share one card. Related preferences share a Miuix section; the UI always renders sections
 * in one column, with platform-specific rows filtered before layout.
 *
 * <p>A row limited to one host (the desktop-only folder paths) carries
 * {@link SettingSpec.Builder#onlyOn}; the other host never sees it, which
 * replaces the {@code typeof settings.x !== "undefined"} guards the QML used to
 * carry.
 */
public final class SettingsCatalog {

    public static final String DESKTOP = "desktop";
    public static final String ANDROID = "android";

    // Stable ids, not labels: the page titles a tab with
    // settings.category.<id> from the language catalog.
    public static final String APPEARANCE = "appearance";
    public static final String PLAYBACK = "playback";
    public static final String LYRIC = "lyric";
    /** Desktop lyrics gets its own tab rather than sitting under 歌词: it is a
     *  separate surface with its own typography and colours, and every row in it
     *  is desktop-only. A category with no rows on the running host is dropped —
     *  see {@link SettingsCore#categories()} — so Android never shows this. */
    public static final String DESKTOP_LYRIC = "desktopLyric";
    public static final String LOCAL = "local";
    public static final String PLUGINS = "plugins";
    public static final String ABOUT = "about";

    public static final List<String> CATEGORIES = Collections.unmodifiableList(
            Arrays.asList(APPEARANCE, PLAYBACK, LYRIC, DESKTOP_LYRIC, LOCAL, PLUGINS, ABOUT));

    /** Fluid-background mode, 0 dynamic / 1 static. Stored under a new key
     *  because the same setting used to be a boolean ("lyricBgStatic") and a
     *  store can't reinterpret a persisted bool as an int — see the one-time
     *  migration in SettingsCore.load. */
    public static final String BG_MODE_KEY = "lyricBgMode";

    /** Fluid backdrop renderer, kept separate from dynamic/static so every style
     *  can still use the existing battery-saving static cache. */
    public static final String BG_STYLE_KEY = "lyricBgStyle";
    public static final int BG_STYLE_PIXI_RENDERER = 0;
    public static final int BG_STYLE_MESH_GRADIENT = 1;
    public static final int BG_STYLE_CLASSIC = 2;

    /** Saturation percent applied to the fluid backdrop's final composite, every
     *  style — 100 is the unmodified original, 0 is greyscale. Independent of the
     *  cover-derived contrast/brightness baked into the mesh/classic textures at
     *  decode time; this is a live multiplier over whichever style is active. */
    public static final String BG_SATURATION_KEY = "lyricBgSaturation";

    // Dark-mode row values (the segmented control's indices).
    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    /** Shared full-page transition presets. QML and the host-drawn lyric page
     *  both read this index so navigation never changes motion language when it
     *  crosses the QML/Skia rendering boundary. */
    public static final String PAGE_TRANSITION_KEY = "pageTransitionPreset";
    public static final int PAGE_TRANSITION_ZOOM = 0;
    public static final int PAGE_TRANSITION_FADE = 1;
    public static final int PAGE_TRANSITION_SLIDE_HORIZONTAL = 2;
    public static final int PAGE_TRANSITION_SLIDE_VERTICAL = 3;
    public static final int PAGE_TRANSITION_NONE = 4;

    /** UI language: 0 follows the platform, 1 zh_CN, 2 en_US. */
    public static final String LANGUAGE_KEY = "language";
    public static final int LANGUAGE_SYSTEM = 0;
    public static final int LANGUAGE_ZH_CN = 1;
    public static final int LANGUAGE_EN_US = 2;

    /** Desktop-lyric click-through. Shared with the floating window's own lock
     *  button, which writes the same key — the two stay in sync in both directions. */
    public static final String DESKTOP_LYRIC_LOCKED_KEY = "desktopLyricMousePassthrough";
    /** Explicit desktop-lyric colours, "#rrggbb" or empty. Empty keeps the Monet
     *  role the window has always used (primary for sung, onSurfaceVariant/secondary
     *  for the rest), so the default look is unchanged. */
    public static final String DESKTOP_LYRIC_SUNG_COLOR_KEY = "desktopLyricSungColor";
    public static final String DESKTOP_LYRIC_UNSUNG_COLOR_KEY = "desktopLyricUnsungColor";
    /** Percent of the window's 900x180 base size (aspect ratio fixed); the
     *  same value a corner-drag on the floating window itself writes. */
    public static final String DESKTOP_LYRIC_SCALE_KEY = "desktopLyricScale";
    /** Background opacity percent while the pointer is away from the window —
     *  the state it is in almost all the time during normal playback. */
    public static final String DESKTOP_LYRIC_IDLE_OPACITY_KEY = "desktopLyricIdleOpacity";
    /** Which Monet scheme (light/dark) the floating window's text extracts its
     *  colours from — independent of the app's own darkMode, since the window
     *  floats over arbitrary desktop content, not the app's own backdrop.
     *  MODE_SYSTEM here means "whatever the app resolved", not literally the
     *  OS state; reuses MODE_SYSTEM/MODE_LIGHT/MODE_DARK from darkMode above. */
    public static final String DESKTOP_LYRIC_COLOR_SCHEME_KEY = "desktopLyricColorScheme";
    /** Fourth option, meaningful only here (not part of the shared darkMode
     *  trio above): pick light/dark text by periodically sampling the real
     *  screen content behind the window instead of any fixed scheme. Desktop
     *  (Windows) only — see ScreenContrastSampler. */
    public static final int MODE_CONTRAST = 3;

    /** Daily picks above the recommendation grid instead of below it. */
    public static final String HOME_DAILY_FIRST_KEY = "homeDailyFirst";
    /** How many plain recommended playlists the home page asks a source for. */
    public static final String HOME_PLAYLIST_LIMIT_KEY = "homePlaylistLimit";
    /** Playlist card width on 我的/本地歌单's grid, in dp; only affects the grid —
     *  list mode (below) always uses a fixed row height instead. */
    public static final String LIBRARY_CARD_SIZE_KEY = "libraryCardSize";
    /** Grid of cover cards (false, default) vs. a compact row list. */
    public static final String LIBRARY_LIST_VIEW_KEY = "libraryListView";

    private SettingsCatalog() {}

    public static List<SettingSpec> specs() {
        List<SettingSpec> out = new ArrayList<>();

        // ---- 外观 -----------------------------------------------------------
        out.add(SettingSpec.dropdown(LANGUAGE_KEY, APPEARANCE, "settings.language.title",
                        LANGUAGE_SYSTEM, "settings.language.system",
                        "settings.language.zhCN", "settings.language.enUS")
                .group("interface")
                .build());
        out.add(SettingSpec.segmented("darkMode", APPEARANCE, "settings.darkMode.title", MODE_SYSTEM,
                        "settings.darkMode.system", "settings.darkMode.light",
                        "settings.darkMode.dark")
                .group("interface")
                .build());
        out.add(SettingSpec.dropdown(PAGE_TRANSITION_KEY, APPEARANCE,
                        "settings.pageTransition.title", PAGE_TRANSITION_ZOOM,
                        "settings.pageTransition.zoom", "settings.pageTransition.fade",
                        "settings.pageTransition.slideH", "settings.pageTransition.slideV",
                        "settings.pageTransition.none")
                .desc("settings.pageTransition.desc")
                .group("interface")
                .build());
        out.add(SettingSpec.toggle("monet", APPEARANCE, "settings.monet.title", true)
                .desc("settings.monet.desc")
                .accessory("swatch")
                .group("interface")
                .build());
        out.add(SettingSpec.action("pickFont", APPEARANCE, "settings.font.title",
                        "settings.font.button")
                .provider("fontName")
                .desc("settings.font.desc")
                .group("interface")
                .build());
        out.add(SettingSpec.radio("graphicsBackend", APPEARANCE, "settings.graphicsBackend.title", 0,
                        "settings.graphicsBackend.opengl", "settings.graphicsBackend.vulkan")
                .desc("settings.graphicsBackend.desc")
                .onlyOn(DESKTOP)
                .group("window")
                .build());
        out.add(SettingSpec.toggle("windowDecorated", APPEARANCE,
                        "settings.windowDecorated.title", false)
                .desc("settings.windowDecorated.desc")
                .onlyOn(DESKTOP)
                .group("window")
                .build());
        out.add(SettingSpec.toggle("showLocalTab", APPEARANCE, "settings.showLocalTab.title", true)
                .desc("settings.showLocalTab.desc")
                .group("home")
                .build());
        out.add(SettingSpec.toggle(HOME_DAILY_FIRST_KEY, APPEARANCE,
                        "settings.homeDailyFirst.title", true)
                .desc("settings.homeDailyFirst.desc")
                .group("home")
                .build());
        out.add(SettingSpec.stepper(HOME_PLAYLIST_LIMIT_KEY, APPEARANCE,
                        "settings.homePlaylistLimit.title", 12, 4, 50, 2)
                .desc("settings.homePlaylistLimit.desc")
                .group("home")
                .build());
        out.add(SettingSpec.toggle(LIBRARY_LIST_VIEW_KEY, APPEARANCE,
                        "settings.libraryListView.title", false)
                .desc("settings.libraryListView.desc")
                .group("library")
                .build());
        out.add(SettingSpec.slider(LIBRARY_CARD_SIZE_KEY, APPEARANCE,
                        "settings.libraryCardSize.title", 200, 100, 260, 10)
                .desc("settings.libraryCardSize.desc")
                .unit("dp")
                .group("library")
                .build());

        // ---- 播放 -----------------------------------------------------------
        // Default follows the UI locale: the mirror only helps from mainland China.
        out.add(SettingSpec.toggle("mirror", PLAYBACK, "settings.mirror.title",
                        isSimplifiedChinese())
                .desc("settings.mirror.desc")
                .group("audio")
                .build());
        out.add(SettingSpec.toggle("fade", PLAYBACK, "settings.fade.title", false)
                .desc("settings.fade.desc")
                .group("audio")
                .build());
        out.add(SettingSpec.toggle("highQuality", PLAYBACK, "settings.highQuality.title", true)
                .desc("settings.highQuality.desc")
                .group("audio")
                .build());

        // ---- 歌词 -----------------------------------------------------------
        // Group typography, motion, display and background controls separately.
        out.add(SettingSpec.slider("lyricFontSize", LYRIC, "settings.lyricFontSize.title",
                        28, 14, 40, 1)
                .unit(" px").dots()
                .group("lyricText")
                .build());
        out.add(SettingSpec.segmented("lyricFontWeight", LYRIC, "settings.lyricFontWeight.title", 2,
                        "settings.lyricFontWeight.thin", "settings.lyricFontWeight.light",
                        "settings.lyricFontWeight.regular", "settings.lyricFontWeight.medium")
                .group("lyricText")
                .build());
        out.add(SettingSpec.slider("lyricLineSpacing", LYRIC, "settings.lyricLineSpacing.title",
                        200, 100, 250, 5)
                .scale(100).unit("×").dots()
                .group("lyricText")
                .build());
        out.add(SettingSpec.toggle("lyricSpring", LYRIC, "settings.lyricSpring.title", true)
                .desc("settings.lyricSpring.desc")
                .group("lyricMotion")
                .build());
        out.add(SettingSpec.toggle("lyricScale", LYRIC, "settings.lyricScale.title", true)
                .desc("settings.lyricScale.desc")
                .group("lyricMotion")
                .build());
        out.add(SettingSpec.toggle("lyricGlow", LYRIC, "settings.lyricGlow.title", true)
                .desc("settings.lyricGlow.desc")
                .group("lyricMotion")
                .build());
        out.add(SettingSpec.toggle("lyricShadow", LYRIC, "settings.lyricShadow.title", true)
                .desc("settings.lyricShadow.desc")
                .group("lyricMotion")
                .build());
        out.add(SettingSpec.toggle("lyricLinearAnim", LYRIC, "settings.lyricLinearAnim.title", false)
                .desc("settings.lyricLinearAnim.desc")
                .group("lyricMotion")
                .build());
        out.add(SettingSpec.toggle("lyricEdgeBlur", LYRIC, "settings.lyricEdgeBlur.title", false)
                .desc("settings.lyricEdgeBlur.desc")
                .group("lyricMotion")
                .build());
        out.add(SettingSpec.segmented("lyricProgressStyle", LYRIC,
                        "settings.lyricProgressStyle.title", 1,
                        "settings.lyricProgressStyle.wave",
                        "settings.lyricProgressStyle.line")
                .group("lyricDisplay")
                .build());
        // Wide-window layout: no cover, lyrics across the whole page, transport
        // and progress along the bottom. Desktop only — it needs a window much
        // wider than it is tall to read well, which a phone never is.
        //
        // Stored like any setting but with no row of its own: it is a way of
        // LOOKING at the lyric page, so it is toggled from the page itself,
        // next to the cover/lyrics switch, rather than from a list two screens
        // away. Same reasoning as the lyric timing offset.
        out.add(SettingSpec.hidden("lyricFullWidth", SettingSpec.SWITCH, false)
                .onlyOn(DESKTOP)
                .build());

        out.add(SettingSpec.radio(BG_MODE_KEY, LYRIC, "settings.lyricBgMode.title", 0,
                        "settings.lyricBgMode.dynamic", "settings.lyricBgMode.static")
                .desc("settings.lyricBgMode.desc")
                .group("background")
                .build());
        out.add(SettingSpec.dropdown(BG_STYLE_KEY, LYRIC, "settings.lyricBgStyle.title",
                        BG_STYLE_PIXI_RENDERER,
                        "settings.lyricBgStyle.pixi", "settings.lyricBgStyle.mesh",
                        "settings.lyricBgStyle.classic")
                .desc("settings.lyricBgStyle.desc")
                .group("background")
                .build());
        out.add(SettingSpec.slider(BG_SATURATION_KEY, LYRIC, "settings.lyricBgSaturation.title",
                        100, 0, 200, 10)
                .desc("settings.lyricBgSaturation.desc")
                .unit("%").dots()
                .group("background")
                .build());
        out.add(SettingSpec.toggle("temperaWholeLine", LYRIC, "settings.temperaWholeLine.title", false)
                .desc("settings.temperaWholeLine.desc")
                .group("tempera")
                .build());
        out.add(SettingSpec.slider("temperaGlyphSettleStretch", LYRIC, "settings.temperaGlyphSettleStretch.title", 50, 0, 100, 5)
                .unit(" %")
                .desc("settings.temperaGlyphSettleStretch.desc")
                .group("tempera")
                .build());
        out.add(SettingSpec.toggle("temperaImages", LYRIC, "settings.temperaImages.title", false)
                .desc("settings.temperaImages.desc")
                .group("tempera")
                .build());
        // ---- 桌面歌词 (its own tab, desktop only) -----------------------------
        // Everything below the master switch is deliberately independent of the
        // lyric page's own typography: the floating window sits on the wallpaper,
        // not on a controlled backdrop, so it needs its own size/weight/colours.
        // dependsOn hides the whole block while the feature is off.
        out.add(SettingSpec.toggle("desktopLyricEnabled", DESKTOP_LYRIC,
                        "settings.desktopLyric.title", false)
                .desc("settings.desktopLyric.desc")
                .onlyOn(DESKTOP)
                .group("desktopLyricGeneral")
                .build());
        out.add(SettingSpec.toggle(DESKTOP_LYRIC_LOCKED_KEY, DESKTOP_LYRIC,
                        "settings.desktopLyricLocked.title", false)
                .desc("settings.desktopLyricLocked.desc")
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricGeneral")
                .build());
        // The window itself can also be dragged from its bottom-right corner —
        // this slider and that drag write the same value, so either one moves
        // the other. Percent of the 900x180 base size, aspect ratio fixed: an
        // arbitrarily stretched pill never looked intentional in practice.
        out.add(SettingSpec.slider(DESKTOP_LYRIC_SCALE_KEY, DESKTOP_LYRIC,
                        "settings.desktopLyricScale.title", 100, 70, 160, 5)
                .unit("%").dots()
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricGeneral")
                .build());
        // Default (45) is deliberately higher than the old hidden-state 28% it
        // replaces: at 28% the backdrop was close enough to invisible that
        // legibility depended entirely on the outline/shadow settings below,
        // against whatever happened to be on screen underneath.
        out.add(SettingSpec.slider(DESKTOP_LYRIC_IDLE_OPACITY_KEY, DESKTOP_LYRIC,
                        "settings.desktopLyricIdleOpacity.title", 45, 15, 90, 5)
                .desc("settings.desktopLyricIdleOpacity.desc")
                .unit("%").dots()
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricGeneral")
                .build());
        out.add(SettingSpec.action("pickDesktopLyricFont", DESKTOP_LYRIC,
                        "settings.desktopLyricFont.title", "")
                .provider("desktopLyricFontName")
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricText")
                .build());
        out.add(SettingSpec.slider("desktopLyricFontSize", DESKTOP_LYRIC,
                        "settings.desktopLyricFontSize.title", 26, 18, 38, 1)
                .unit(" px").dots()
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricText")
                .build());
        out.add(SettingSpec.segmented("desktopLyricFontWeight", DESKTOP_LYRIC,
                        "settings.desktopLyricFontWeight.title", 2,
                        "settings.lyricFontWeight.thin", "settings.lyricFontWeight.light",
                        "settings.lyricFontWeight.regular", "settings.lyricFontWeight.medium")
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricText")
                .build());
        // The window floats over whatever is on screen, not the app's own
        // backdrop, so a text colour picked for the app's current theme can be
        // the wrong one for what happens to be underneath. This decouples the
        // two; the two colour rows below still win over either when set.
        out.add(SettingSpec.segmented(DESKTOP_LYRIC_COLOR_SCHEME_KEY, DESKTOP_LYRIC,
                        "settings.desktopLyricColorScheme.title", MODE_SYSTEM,
                        "settings.desktopLyricColorScheme.app", "settings.desktopLyricColorScheme.light",
                        "settings.desktopLyricColorScheme.dark", "settings.desktopLyricColorScheme.contrast")
                .desc("settings.desktopLyricColorScheme.desc")
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricInk")
                .build());
        out.add(SettingSpec.color(DESKTOP_LYRIC_SUNG_COLOR_KEY, DESKTOP_LYRIC,
                        "settings.desktopLyricSungColor.title", "")
                .desc("settings.desktopLyricSungColor.desc")
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricInk")
                .build());
        out.add(SettingSpec.color(DESKTOP_LYRIC_UNSUNG_COLOR_KEY, DESKTOP_LYRIC,
                        "settings.desktopLyricUnsungColor.title", "")
                .desc("settings.desktopLyricUnsungColor.desc")
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricInk")
                .build());
        out.add(SettingSpec.toggle("desktopLyricOutline", DESKTOP_LYRIC,
                        "settings.desktopLyricOutline.title", true)
                .desc("settings.desktopLyricOutline.desc")
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricInk")
                .build());
        out.add(SettingSpec.toggle("desktopLyricShadow", DESKTOP_LYRIC,
                        "settings.desktopLyricShadow.title", true)
                .desc("settings.desktopLyricShadow.desc")
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricInk")
                .build());
        // ---- 本地 -----------------------------------------------------------
        out.add(SettingSpec.slider("maxCacheSizeMB", LOCAL, "settings.maxCacheSize.title",
                        200, 50, 1024, 1)
                .unit(" MB").group("cache")
                .build());
        out.add(SettingSpec.action("clearCache", LOCAL, "settings.cacheUsage.title",
                        "settings.cacheUsage.button")
                .provider("cacheUsage").inlineProvider().buttonType("outlined")
                .group("cache")
                .build());
        out.add(SettingSpec.path("cacheFolder", LOCAL, "settings.cacheFolder.title", "")
                .desc("settings.cacheFolder.desc")
                .hint("settings.folder.hint")
                .group("cache")
                .onlyOn(DESKTOP)
                .build());
        out.add(SettingSpec.path("musicFolder", LOCAL, "settings.musicFolder.title", "")
                .desc("settings.musicFolder.desc")
                .hint("settings.folder.hint")
                .onlyOn(DESKTOP)
                .group("folders")
                .build());

        // ---- 关于 -----------------------------------------------------------
        out.add(SettingSpec.action("openRepo", ABOUT, "QPlayer", "")
                .provider("version").inlineProvider()
                // Hard-coded breaks: qml4j's auto-wrap mis-measures this width.
                .desc("settings.about.desc")
                .group("app")
                .build());
        out.add(SettingSpec.action("checkUpdate", ABOUT, "settings.checkUpdate.title", "")
                .icon("system_update")
                .group("app")
                .build());

        return out;
    }

    /** Mainland-Chinese UI locale (zh, not Traditional, not TW/HK/MO) — the one
     *  place that test lives now; both platform Settings classes used to carry
     *  their own copy of it. */
    public static boolean isSimplifiedChinese() {
        java.util.Locale l = java.util.Locale.getDefault();
        if (!"zh".equalsIgnoreCase(l.getLanguage())) return false;
        if ("Hant".equalsIgnoreCase(l.getScript())) return false;
        String country = l.getCountry();
        return !("TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country)
                || "MO".equalsIgnoreCase(country));
    }
}
