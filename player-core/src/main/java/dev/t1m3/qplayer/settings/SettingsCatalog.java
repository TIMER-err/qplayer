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
 * consecutive rows sharing a {@code group} share one card. The grouping,
 * wording and widget choice below reproduce the hand-written page this replaced
 * one for one.
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
    public static final String LOCAL = "local";
    public static final String PLUGINS = "plugins";
    public static final String ABOUT = "about";

    public static final List<String> CATEGORIES = Collections.unmodifiableList(
            Arrays.asList(APPEARANCE, PLAYBACK, LYRIC, LOCAL, PLUGINS, ABOUT));

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

    /** Daily picks above the recommendation grid instead of below it. */
    public static final String HOME_DAILY_FIRST_KEY = "homeDailyFirst";
    /** How many plain recommended playlists the home page asks a source for. */
    public static final String HOME_PLAYLIST_LIMIT_KEY = "homePlaylistLimit";

    private SettingsCatalog() {}

    public static List<SettingSpec> specs() {
        List<SettingSpec> out = new ArrayList<>();

        // ---- 外观 -----------------------------------------------------------
        out.add(SettingSpec.dropdown(LANGUAGE_KEY, APPEARANCE, "settings.language.title",
                        LANGUAGE_SYSTEM, "settings.language.system",
                        "settings.language.zhCN", "settings.language.enUS")
                .build());
        out.add(SettingSpec.segmented("darkMode", APPEARANCE, "settings.darkMode.title", MODE_SYSTEM,
                        "settings.darkMode.system", "settings.darkMode.light",
                        "settings.darkMode.dark")
                .build());
        out.add(SettingSpec.dropdown(PAGE_TRANSITION_KEY, APPEARANCE,
                        "settings.pageTransition.title", PAGE_TRANSITION_ZOOM,
                        "settings.pageTransition.zoom", "settings.pageTransition.fade",
                        "settings.pageTransition.slideH", "settings.pageTransition.slideV",
                        "settings.pageTransition.none")
                .desc("settings.pageTransition.desc")
                .build());
        out.add(SettingSpec.toggle("monet", APPEARANCE, "settings.monet.title", true)
                .desc("settings.monet.desc")
                .accessory("swatch")
                .build());
        out.add(SettingSpec.action("pickFont", APPEARANCE, "settings.font.title",
                        "settings.font.button")
                .provider("fontName")
                .desc("settings.font.desc")
                .build());
        out.add(SettingSpec.radio("graphicsBackend", APPEARANCE, "settings.graphicsBackend.title", 0,
                        "settings.graphicsBackend.opengl", "settings.graphicsBackend.vulkan")
                .desc("settings.graphicsBackend.desc")
                .onlyOn(DESKTOP)
                .build());
        out.add(SettingSpec.toggle("windowDecorated", APPEARANCE,
                        "settings.windowDecorated.title", false)
                .desc("settings.windowDecorated.desc")
                .onlyOn(DESKTOP)
                .build());
        out.add(SettingSpec.toggle("showLocalTab", APPEARANCE, "settings.showLocalTab.title", true)
                .desc("settings.showLocalTab.desc")
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

        // ---- 播放 -----------------------------------------------------------
        // Default follows the UI locale: the mirror only helps from mainland China.
        out.add(SettingSpec.toggle("mirror", PLAYBACK, "settings.mirror.title",
                        isSimplifiedChinese())
                .desc("settings.mirror.desc")
                .build());
        out.add(SettingSpec.toggle("fade", PLAYBACK, "settings.fade.title", false)
                .desc("settings.fade.desc")
                .build());
        out.add(SettingSpec.toggle("highQuality", PLAYBACK, "settings.highQuality.title", true)
                .desc("settings.highQuality.desc")
                .build());

        // ---- 歌词 -----------------------------------------------------------
        // One card per control, like every other tab: no group() here, so each
        // row is its own card and the wide-window grid can pair them up.
        out.add(SettingSpec.slider("lyricFontSize", LYRIC, "settings.lyricFontSize.title",
                        28, 14, 40, 1)
                .unit(" px").dots()
                .build());
        out.add(SettingSpec.segmented("lyricFontWeight", LYRIC, "settings.lyricFontWeight.title", 2,
                        "settings.lyricFontWeight.thin", "settings.lyricFontWeight.light",
                        "settings.lyricFontWeight.regular", "settings.lyricFontWeight.medium")
                .build());
        out.add(SettingSpec.slider("lyricLineSpacing", LYRIC, "settings.lyricLineSpacing.title",
                        200, 100, 250, 5)
                .scale(100).unit("×").dots()
                .build());
        out.add(SettingSpec.toggle("lyricSpring", LYRIC, "settings.lyricSpring.title", true)
                .desc("settings.lyricSpring.desc")
                .build());
        out.add(SettingSpec.toggle("lyricScale", LYRIC, "settings.lyricScale.title", true)
                .desc("settings.lyricScale.desc")
                .build());
        out.add(SettingSpec.toggle("lyricGlow", LYRIC, "settings.lyricGlow.title", true)
                .desc("settings.lyricGlow.desc")
                .build());
        out.add(SettingSpec.toggle("lyricShadow", LYRIC, "settings.lyricShadow.title", true)
                .desc("settings.lyricShadow.desc")
                .build());
        out.add(SettingSpec.toggle("lyricLinearAnim", LYRIC, "settings.lyricLinearAnim.title", false)
                .desc("settings.lyricLinearAnim.desc")
                .build());
        out.add(SettingSpec.toggle("lyricEdgeBlur", LYRIC, "settings.lyricEdgeBlur.title", false)
                .desc("settings.lyricEdgeBlur.desc")
                .build());
        out.add(SettingSpec.toggle("desktopLyricEnabled", LYRIC, "settings.desktopLyric.title", false)
                .desc("settings.desktopLyric.desc")
                .onlyOn(DESKTOP)
                .build());
        out.add(SettingSpec.segmented("lyricProgressStyle", LYRIC,
                        "settings.lyricProgressStyle.title", 1,
                        "settings.lyricProgressStyle.wave",
                        "settings.lyricProgressStyle.line")
                .build());
        out.add(SettingSpec.radio(BG_MODE_KEY, LYRIC, "settings.lyricBgMode.title", 0,
                        "settings.lyricBgMode.dynamic", "settings.lyricBgMode.static")
                .desc("settings.lyricBgMode.desc")
                .build());
        out.add(SettingSpec.dropdown(BG_STYLE_KEY, LYRIC, "settings.lyricBgStyle.title",
                        BG_STYLE_PIXI_RENDERER,
                        "settings.lyricBgStyle.pixi", "settings.lyricBgStyle.mesh",
                        "settings.lyricBgStyle.classic")
                .desc("settings.lyricBgStyle.desc")
                .build());
        out.add(SettingSpec.toggle("temperaEnabled", LYRIC, "settings.temperaEnabled.title", true)
                .desc("settings.temperaEnabled.desc")
                .onlyOn(DESKTOP)
                .build());
        out.add(SettingSpec.toggle("temperaWholeLine", LYRIC, "settings.temperaWholeLine.title", false)
                .desc("settings.temperaWholeLine.desc")
                .onlyOn(DESKTOP)
                .build());
        out.add(SettingSpec.slider("temperaGlyphSettleStretch", LYRIC, "settings.temperaGlyphSettleStretch.title", 50, 0, 100, 5)
                .unit(" %")
                .desc("settings.temperaGlyphSettleStretch.desc")
                .onlyOn(DESKTOP)
                .build());
        out.add(SettingSpec.toggle("temperaImages", LYRIC, "settings.temperaImages.title", false)
                .desc("settings.temperaImages.desc")
                .onlyOn(DESKTOP)
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
                .build());

        // ---- 关于 -----------------------------------------------------------
        out.add(SettingSpec.action("openRepo", ABOUT, "QPlayer", "")
                .icon("link")
                .provider("version").inlineProvider()
                // Hard-coded breaks: qml4j's auto-wrap mis-measures this width.
                .desc("settings.about.desc")
                .build());
        out.add(SettingSpec.action("checkUpdate", ABOUT, "settings.checkUpdate.title", "")
                .icon("system_update")
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
