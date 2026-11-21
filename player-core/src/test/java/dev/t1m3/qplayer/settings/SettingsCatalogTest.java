package dev.t1m3.qplayer.settings;

import dev.t1m3.qplayer.i18n.I18n;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingsCatalogTest {

    @Test
    public void systemTitleBarIsDesktopOnlyAndDefaultsOff() {
        SettingSpec spec = setting("windowDecorated");

        assertEquals(Boolean.FALSE, spec.def);
        assertTrue(spec.appliesTo(SettingsCatalog.DESKTOP));
        assertFalse(spec.appliesTo(SettingsCatalog.ANDROID));
    }

    @Test
    public void lyricSizingUsesDottedFixedStepSliders() {
        SettingSpec fontSize = setting("lyricFontSize");
        SettingSpec lineSpacing = setting("lyricLineSpacing");

        assertEquals(SettingSpec.SLIDER, fontSize.type);
        assertTrue(fontSize.dots);
        assertEquals(1, fontSize.step);

        assertEquals(SettingSpec.SLIDER, lineSpacing.type);
        assertTrue(lineSpacing.dots);
        assertEquals(5, lineSpacing.step);
    }

    /** 逐行动效风格：飘带 / 放大，二选一，默认必须是飘带（0）。 */
    @Test
    public void lyricLiftStyleOffersRibbonAndZoomWithRibbonAsDefault() {
        SettingSpec spec = setting("lyricLiftStyle");
        assertEquals(SettingSpec.SEGMENTED, spec.type);
        assertEquals(0, ((Number) spec.def).intValue());
        assertEquals(2, spec.options.size());
        assertEquals("settings.lyricLiftStyle.ribbon", spec.options.get(0));
        assertEquals("settings.lyricLiftStyle.zoom", spec.options.get(1));
    }

    @Test
    public void maximumCacheUsesPlainSlider() {
        SettingSpec cache = setting("maxCacheSizeMB");

        assertEquals(SettingSpec.SLIDER, cache.type);
        assertFalse(cache.dots);
        assertEquals(50, cache.min);
        assertEquals(1024, cache.max);
    }

    @Test
    public void pageTransitionDefaultsToZoomAndOffersAccessibleFallback() {
        SettingSpec transition = setting(SettingsCatalog.PAGE_TRANSITION_KEY);

        assertEquals(SettingSpec.DROPDOWN, transition.type);
        assertEquals(SettingsCatalog.PAGE_TRANSITION_ZOOM, transition.def);
        // Options are language keys; the catalog resolves them at render time.
        assertEquals("settings.pageTransition.zoom", transition.options.get(0));
        assertTrue(transition.options.contains("settings.pageTransition.none"));
        assertEquals("Zoom In / Out", I18n.tr(transition.options.get(0)));
        assertTrue(transition.appliesTo(SettingsCatalog.DESKTOP));
        assertTrue(transition.appliesTo(SettingsCatalog.ANDROID));
    }

    @Test
    public void temperaUsesTheExistingLyricTabAndDoesNotAddMobileTabs() {
        // Spelled out rather than counted: a diff here says which tab appeared.
        // 桌面歌词 is a real tab but a desktop-only one — SettingsCore.categories()
        // drops it on a host with no rows for it.
        // 音频 (HiFi + 自动混音) is a deliberate addition: automix is the one thing a
        // phone needs the same way a desktop does, so it is NOT a desktop-only tab.
        assertEquals(java.util.Arrays.asList(
                        SettingsCatalog.APPEARANCE, SettingsCatalog.PLAYBACK,
                        SettingsCatalog.AUDIO, SettingsCatalog.LYRIC,
                        SettingsCatalog.DESKTOP_LYRIC, SettingsCatalog.LOCAL,
                        SettingsCatalog.PLUGINS, SettingsCatalog.ABOUT),
                SettingsCatalog.CATEGORIES);
        assertTrue("自动混音必须在两端都能设——安卓也要无缝过渡",
                setting(SettingsCatalog.AUTOMIX_MODE_KEY).appliesTo(SettingsCatalog.ANDROID));
        assertTrue(setting(SettingsCatalog.AUTOMIX_STYLE_KEY).appliesTo(SettingsCatalog.ANDROID));
        assertFalse(SettingsCatalog.specs().stream().anyMatch(spec -> "temperaEnabled".equals(spec.key)));
        for (String key : new String[]{"temperaWholeLine",
                "temperaGlyphSettleStretch", "temperaImages"}) {
            SettingSpec spec = setting(key);
            assertEquals(SettingsCatalog.LYRIC, spec.category);
            assertTrue(spec.appliesTo(SettingsCatalog.DESKTOP));
            assertTrue(spec.appliesTo(SettingsCatalog.ANDROID));
        }
    }

    /**
     * 融合期人声那一行是<b>同一个键、两种平台形状</b>：桌面三选一（含 AI），
     * 安卓两选一（AI 那一档在手机上不装推理实现，选了也跑不起来）。
     *
     * <p>这里只钉「目录的形状」（两行、各自只对一端生效、同一个键、安卓没有 AI 选项）；
     * 两行的取值/文案细节在 {@code VocalModeSettingsTest} 里展开。
     * 之所以要有两行而不是两个键，见 {@code SettingsCatalog} 里那两行的注释：
     * 一个是「值只能有一个」，另一个是「options 是固定列表、没法按平台少给一项」。
     */
    @Test
    public void theVocalModeRowIsSplitByPlatformAndOnlyDesktopSeesAi() {
        java.util.List<SettingSpec> rows = SettingsCatalog.specs().stream()
                .filter(spec -> SettingsCatalog.AUTOMIX_VOCAL_MODE_KEY.equals(spec.key))
                .collect(java.util.stream.Collectors.toList());
        assertEquals("这个键应当恰好两行（桌面 + 安卓）", 2, rows.size());

        SettingSpec desktop = null;
        SettingSpec android = null;
        for (SettingSpec spec : rows) {
            if (spec.appliesTo(SettingsCatalog.DESKTOP)) desktop = spec;
            if (spec.appliesTo(SettingsCatalog.ANDROID)) android = spec;
        }
        assertTrue("桌面那一行必须在", desktop != null && !desktop.appliesTo(SettingsCatalog.ANDROID));
        assertTrue("安卓那一行必须在", android != null && !android.appliesTo(SettingsCatalog.DESKTOP));
        assertEquals("AI 只能出现在桌面那一行", 3, desktop.options.size());
        assertEquals("安卓只给「不分离 / DSP」", 2, android.options.size());
        assertFalse("安卓那一行不许有 AI 选项",
                android.options.contains("settings.automixVocalMode.ai"));
        // 两行都必须落在自动混音那一组（这一条决定它跟谁同一张卡）
        assertEquals("automix", desktop.group);
        assertEquals("automix", android.group);
    }

    /**
     * 目录里出现的<strong>每一个 i18n 键都必须在语言文件里查得到</strong>。
     *
     * <p>为什么值得单钉一条：这些键是界面拿来查表的，**查不到就把键名原样画出来**。
     * 2026-09-19 用户在音频页看到的就是两个生键——{@code settings.group.hifi} 与
     * {@code settings.group.automix}（分组标题按 {@code "settings.group." + group} 查，
     * 而这两句文案压根没写）。这种漏文案**没有任何运行时报错**，编译、单测、启动全都正常，
     * 只有人眼能发现；所以只能靠一条把「目录里引用了什么」与「语言文件里有什么」
     * 对起来的测试拦住它。
     *
     * <p>只查当前语言即可：两份语言文件的键集一致由 {@code I18nTest} 守着，
     * 这里再查一遍两种语言就是把同一条规矩写两处。
     *
     * <p>{@code provider} / {@code action} / {@code icon} / {@code unit} 这几项**不是**
     * i18n 键（provider 是 {@code SettingsCore} 里注册的活文案名字，action 是注册的动作名，
     * unit 是 {@code " MB"} 这种直接显示的串），所以不在检查范围内。
     */
    @Test
    public void everyI18nKeyUsedByTheCatalogResolves() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        java.util.Set<String> checkedGroups = new java.util.LinkedHashSet<>();

        for (SettingSpec spec : SettingsCatalog.specs()) {
            if (spec.group != null && !spec.group.isEmpty()
                    && checkedGroups.add(spec.group)) {
                String key = "settings.group." + spec.group;
                if (key.equals(I18n.tr(key))) missing.add(key);
            }
            requireTranslated(missing, spec.title);
            requireTranslated(missing, spec.desc);
            requireTranslated(missing, spec.hint);
            requireTranslated(missing, spec.button);
            if (spec.options != null) {
                for (String option : spec.options) requireTranslated(missing, option);
            }
        }

        assertTrue("这些键在语言文件里查不到，界面上会把键名原样显示出来：\n  "
                + String.join("\n  ", missing), missing.isEmpty());
    }

    /**
     * 判据：**带点的字符串才当键**。
     *
     * <p>目录里除了一处例外全都是 {@code settings.*} 的 i18n 键；那一处例外是
     * 关于页里的 {@code SettingSpec.action("openRepo", ABOUT, "QPlayer", "")}——标题就是
     * **产品名本身**，按约定不翻译（也正因为它是字面量，不能拿去查表）。所以这里用
     * "有没有点"把两种东西分开，而不是维护一张例外名单：名单会随代码漂移，而
     * "产品名不带点"是这个仓库里稳定的事实。
     */
    private static void requireTranslated(java.util.List<String> missing, String key) {
        if (key == null || key.isEmpty() || key.indexOf('.') < 0) return;
        if (key.equals(I18n.tr(key))) missing.add(key);
    }

    /**
     * <b>「播放设备」那一行：有设备才出现，而且第一项必须是「系统默认」。</b>
     *
     * <p>设备名是动态的（每次构建目录时现取），没有设备时（安卓、或枚举失败）
     * 整行不出现——给一个只有一项的下拉框比不给更让人困惑。
     */
    @Test
    public void theOutputDeviceRowAppearsOnlyWhenThereAreDevices() {
        // 没有来源（安卓那一路）：整行不许出现
        dev.t1m3.qplayer.audio.output.OutputDevices.install(null);
        assertTrue("没有设备时不该有这一行",
                SettingsCatalog.specs().stream().noneMatch(
                        spec -> SettingsCatalog.OUTPUT_DEVICE_KEY.equals(spec.key)));

        // 装一个假来源，两台设备
        dev.t1m3.qplayer.audio.output.OutputDevices.install(new dev.t1m3.qplayer.audio.output.OutputDevices.Provider() {
            @Override
            public java.util.List<dev.t1m3.qplayer.audio.output.OutputDevice> list() {
                return java.util.Arrays.asList(
                        device("扬声器"), device("USB 声卡"));
            }

            @Override
            public dev.t1m3.qplayer.audio.output.OutputDevice defaultDevice() {
                return list().get(0);
            }
        });
        try {
            SettingSpec row = SettingsCatalog.specs().stream()
                    .filter(spec -> SettingsCatalog.OUTPUT_DEVICE_KEY.equals(spec.key))
                    .findFirst().orElse(null);
            assertTrue("有设备时必须有这一行", row != null);
            assertEquals(SettingSpec.DROPDOWN, row.type);
            assertEquals(SettingsCatalog.PLAYBACK, row.category);
            assertTrue("这一行只在桌面端有意义", row.appliesTo(SettingsCatalog.DESKTOP));
            assertFalse(row.appliesTo(SettingsCatalog.ANDROID));
            assertEquals("第一项是跟随系统默认，后面按设备顺序", 3, row.options.size());
            assertEquals(I18n.tr("settings.outputDevice.systemDefault"), row.options.get(0));
            assertEquals("扬声器", row.options.get(1));
            assertEquals("USB 声卡", row.options.get(2));
            assertEquals("默认选中「系统默认」那一项", 0, ((Number) row.def).intValue());
            // 隐藏项：设备**名字**才是事实来源（下标会因枚举顺序变化而指错）
            assertTrue("名字那个隐藏项也必须在",
                    SettingsCatalog.specs().stream().anyMatch(spec ->
                            SettingsCatalog.OUTPUT_DEVICE_NAME_KEY.equals(spec.key) && spec.hidden));
        } finally {
            dev.t1m3.qplayer.audio.output.OutputDevices.install(null);
        }
    }

    private static dev.t1m3.qplayer.audio.output.OutputDevice device(String name) {
        return (dev.t1m3.qplayer.audio.output.OutputDevice) java.lang.reflect.Proxy.newProxyInstance(
                dev.t1m3.qplayer.audio.output.OutputDevice.class.getClassLoader(),
                new Class<?>[]{dev.t1m3.qplayer.audio.output.OutputDevice.class},
                (proxy, method, args) -> {
                    if ("name".equals(method.getName())) return name;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
    }

    private static SettingSpec setting(String key) {
        return SettingsCatalog.specs().stream()
                .filter(candidate -> key.equals(candidate.key))
                .findFirst()
                .orElseThrow(() -> new AssertionError(key + " setting missing"));
    }
}
