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

    public static final String APPEARANCE = "外观";
    public static final String PLAYBACK = "播放";
    public static final String LYRIC = "歌词";
    public static final String LOCAL = "本地";
    public static final String ABOUT = "关于";

    public static final List<String> CATEGORIES = Collections.unmodifiableList(
            Arrays.asList(APPEARANCE, PLAYBACK, LYRIC, LOCAL, ABOUT));

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

    private SettingsCatalog() {}

    public static List<SettingSpec> specs() {
        List<SettingSpec> out = new ArrayList<>();

        // ---- 外观 -----------------------------------------------------------
        out.add(SettingSpec.segmented("darkMode", APPEARANCE, "深色模式", MODE_SYSTEM,
                        "跟随系统", "浅色", "深色")
                .build());
        out.add(SettingSpec.dropdown(PAGE_TRANSITION_KEY, APPEARANCE, "页面切换动画",
                        PAGE_TRANSITION_ZOOM,
                        "Zoom In / Out", "淡入淡出", "水平滑动", "垂直滑动", "无动画")
                .desc("应用于导航页面，修改后立即生效；歌词页使用独立展开动画")
                .build());
        out.add(SettingSpec.toggle("monet", APPEARANCE, "莫奈取色", true)
                .desc("随封面动态生成主题配色")
                .accessory("swatch")
                .build());
        out.add(SettingSpec.action("pickFont", APPEARANCE, "字体", "选择")
                .provider("fontName")
                .desc("歌词页立即生效；其余界面文字需要重启软件")
                .build());
        out.add(SettingSpec.radio("graphicsBackend", APPEARANCE, "图形后端", 0,
                        "OpenGL", "Vulkan")
                .desc("切换后重启软件生效；若 Vulkan 初始化失败，将自动切回 OpenGL")
                .onlyOn(DESKTOP)
                .build());
        out.add(SettingSpec.toggle("windowDecorated", APPEARANCE, "使用系统标题栏", false)
                .desc("仅 Windows 生效；关闭时使用 QPlayer 标题栏，切换后重启软件生效")
                .onlyOn(DESKTOP)
                .build());
        out.add(SettingSpec.toggle("showLocalTab", APPEARANCE, "显示本地标签", true)
                .desc("关闭后隐藏底部导航栏和侧栏中的“本地”入口")
                .build());

        // ---- 播放 -----------------------------------------------------------
        out.add(SettingSpec.toggle("unblock", PLAYBACK, "音源解锁", true)
                .desc("灰色/VIP/试听歌曲自动尝试其他音源")
                .build());
        // Default follows the UI locale: the mirror only helps from mainland China.
        out.add(SettingSpec.toggle("mirror", PLAYBACK, "下载加速镜像", isSimplifiedChinese())
                .desc("通过 gh-proxy 镜像下载应用更新")
                .build());
        out.add(SettingSpec.toggle("fade", PLAYBACK, "淡入淡出", false)
                .desc("切歌/播放结束时音量渐变，而不是直接切断")
                .build());
        out.add(SettingSpec.toggle("highQuality", PLAYBACK, "高音质播放", true)
                .desc("关闭后使用低音质播放以节省流量")
                .build());

        // Custom API source: one card, the switch plus the field block it gates.
        out.add(SettingSpec.toggle("customApiEnabled", PLAYBACK, "启用自定义 API 源", false)
                .desc("使用第三方接口搜索/播放，独立于内置网易云音源")
                .group("customApi")
                .build());
        addCustomApiFields(out);

        // ---- 歌词 -----------------------------------------------------------
        // One card per control, like every other tab: no group() here, so each
        // row is its own card and the wide-window grid can pair them up.
        out.add(SettingSpec.slider("lyricFontSize", LYRIC, "字号", 28, 14, 64, 1)
                .unit(" px").dots()
                .build());
        out.add(SettingSpec.segmented("lyricFontWeight", LYRIC, "字重", 2,
                        "极细", "细", "常规", "中等")
                .build());
        out.add(SettingSpec.slider("lyricLineSpacing", LYRIC, "行间距", 200, 100, 250, 5)
                .scale(100).unit("×").dots()
                .build());
        out.add(SettingSpec.slider("lyricEmphasisScale", LYRIC, "歌词放大", 114, 100, 130, 1)
                .scale(100).unit("×").desc("当前行放大的倍率（默认 1.14）")
                .build());
        out.add(SettingSpec.toggle("lyricAutoLineSpacing", LYRIC, "自动行间距", false)
                .desc("使用 Melodify 行间距公式（开启后行间距滑块无效）")
                .build());
        out.add(SettingSpec.toggle("lyricSpring", LYRIC, "弹簧动效", true)
                .desc("滚动与逐字上抬使用弹簧物理")
                .build());
        out.add(SettingSpec.toggle("lyricMelodifyMotion", LYRIC, "Apple Music 动效", true)
                .desc("念完即切 + 提前跳转 + 距离级联 + 缩放同步（关闭 = 原版动效）")
                .build());
        out.add(SettingSpec.toggle("lyricScale", LYRIC, "放大缩放", true)
                .desc("当前行放大、其余行略缩")
                .build());
        out.add(SettingSpec.toggle("lyricGlow", LYRIC, "单词发光", true)
                .desc("仅逐字歌词：唱到的单词显示白色辉光和飘带上浮(较耗电)。"
                        + "歌词阴影开启时仅持续1.5秒以上的单词发光，关闭时所有单词都发光")
                .build());
        out.add(SettingSpec.toggle("lyricShadow", LYRIC, "歌词阴影", true)
                .desc("为歌词、背景声部和翻译添加柔和投影")
                .build());
        out.add(SettingSpec.toggle("lyricLinearAnim", LYRIC, "非逐字歌词线性动画", false)
                .desc("关闭时整行一起点亮")
                .build());
        out.add(SettingSpec.toggle("lyricEdgeBlur", LYRIC, "边缘模糊", false)
                .desc("未聚焦歌词按远近渐进高斯模糊(较耗电)")
                .build());
        out.add(SettingSpec.toggle("desktopLyricEnabled", LYRIC, "桌面歌词", false)
                .desc("使用独立渲染线程显示置顶歌词浮窗")
                .onlyOn(DESKTOP)
                .build());
        out.add(SettingSpec.segmented("lyricProgressStyle", LYRIC, "进度条样式", 1,
                        "波浪", "直线")
                .build());
        out.add(SettingSpec.radio(BG_MODE_KEY, LYRIC, "背景动效", 0, "动态", "静态")
                .desc("动态流动 / 静态(渲染一次,更省电)")
                .build());
        out.add(SettingSpec.dropdown(BG_STYLE_KEY, LYRIC, "流体样式", BG_STYLE_PIXI_RENDERER,
                        "Pixi Renderer", "Mesh Gradient", "Classic")
                .desc("切换歌词页的流体背景算法")
                .build());
        // ---- 本地 -----------------------------------------------------------
        out.add(SettingSpec.slider("maxCacheSizeMB", LOCAL, "最大缓存", 200, 50, 1024, 1)
                .unit(" MB").group("cache")
                .build());
        out.add(SettingSpec.action("clearCache", LOCAL, "当前占用", "清除缓存")
                .provider("cacheUsage").inlineProvider().buttonType("outlined")
                .group("cache")
                .build());
        out.add(SettingSpec.path("cacheFolder", LOCAL, "缓存目录", "")
                .desc("本地音乐库封面/歌词缓存与网易云缓存都存在这里；修改后不会自动搬运旧文件，会重新扫描并在新目录下重建缓存")
                .hint("目录路径")
                .group("cache")
                .onlyOn(DESKTOP)
                .build());
        out.add(SettingSpec.path("musicFolder", LOCAL, "本地音乐目录", "")
                .desc("修改后将自动重新扫描该目录中的音乐文件")
                .hint("目录路径")
                .onlyOn(DESKTOP)
                .build());

        // ---- 关于 -----------------------------------------------------------
        out.add(SettingSpec.action("openRepo", ABOUT, "QPlayer", "")
                .icon("link")
                .provider("version").inlineProvider()
                // Hard-coded breaks: qml4j's auto-wrap mis-measures this width.
                .desc("网易云音乐第三方客户端\nMaterial You 风格 · Apple Music 风逐字歌词\n"
                        + "由自研 qml4j 引擎强力驱动 · Skia 渲染后端")
                .build());
        out.add(SettingSpec.action("checkUpdate", ABOUT, "检查更新", "")
                .icon("system_update")
                .build());

        return out;
    }

    /** The custom-API adapter's field block — all in its card, all gated on the
     *  switch above them. */
    private static void addCustomApiFields(List<SettingSpec> out) {
        addApiField(out, "customApiSearchUrl", "搜索接口 URL 模板", "https://host/search?key={keyword}");
        addApiField(out, "customApiSearchListPath", "搜索结果列表路径", "如 data.list");
        addApiField(out, "customApiIdPath", "id 字段路径", "如 id");
        addApiField(out, "customApiNamePath", "歌名字段路径", "如 name");
        addApiField(out, "customApiArtistPath", "歌手字段路径（可选）", "如 artists[].name");
        addApiField(out, "customApiAlbumPath", "专辑字段路径（可选）", "如 album.name");
        addApiField(out, "customApiCoverPath", "封面字段路径（可选）", "如 pic");
        addApiField(out, "customApiDurationPath", "时长字段路径（可选，单位：秒）", "如 duration");
        addApiField(out, "customApiUrlUrl", "播放地址 URL 模板", "https://host/url?id={id}");
        addApiField(out, "customApiUrlResultPath", "播放地址结果路径", "如 data.url");
        addApiField(out, "customApiLyricUrl", "歌词接口 URL 模板（可选）", "https://host/lyric?id={id}");
        addApiField(out, "customApiLyricResultPath", "歌词结果路径（可选，纯 LRC 文本）", "如 data.lyric");
        addApiField(out, "customApiHeaders", "请求头（可选，多个用 ; 分隔）",
                "如 Authorization: Bearer xxx; X-Custom: 1");
    }

    private static void addApiField(List<SettingSpec> out, String key, String title, String hint) {
        out.add(SettingSpec.text(key, PLAYBACK, title, "")
                .hint(hint)
                .dependsOn("customApiEnabled")
                .group("customApi")
                .build());
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
