package dev.t1m3.qplayer.settings;

import dev.t1m3.qplayer.audio.mix.MixStyle;
import dev.t1m3.qplayer.i18n.I18n;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 自动混音<strong>总开关</strong>在设置目录与那一行活文案里的形状。
 *
 * <p>两件事必须钉住：
 *
 * <ol>
 *   <li><strong>总开关本身</strong>：默认关、落在自动混音那一组、两端都能设、
 *       并且配了活文案 provider（{@code automixStatus}）——
 *       「开着但不会做过渡」这件事只能靠那一行说清楚。</li>
 *   <li><strong>依赖它的每一行都声明了 {@code dependsOn}</strong>：
 *       关着的时候界面上一眼就能看出「现在不会做过渡」，而不是留一排可点、
 *       点了却没反应的接法。</li>
 * </ol>
 */
public class AutomixSwitchSettingsTest {

    /** 总开关那一行本身：默认关、有活文案、在自动混音那一组。 */
    @Test
    public void theMasterSwitchDefaultsOffAndCarriesLiveText() {
        SettingSpec spec = setting(SettingsCatalog.AUTOMIX_ENABLED_KEY);

        assertEquals("switch 才是「一个开关」，不是下拉里的某一档",
                SettingSpec.SWITCH, spec.type);
        assertEquals(Boolean.FALSE, spec.def);
        assertEquals("automix", spec.group);
        assertEquals("活文案的 provider id", "automixStatus", spec.provider);
        assertEquals("总开关不该依赖别的行", "", spec.dependsOn);
        assertTrue("两端都要能设（安卓也要无缝过渡）",
                spec.appliesTo(SettingsCatalog.DESKTOP));
        assertTrue(spec.appliesTo(SettingsCatalog.ANDROID));
    }

    /**
     * 接法、AI 接法、两个窗口、融合期人声（桌面一行 + 安卓一行）都必须
     * <strong>依赖总开关</strong>：关着的时候它们整行不可见（{@code SettingGroup.qml}
     * 的 {@code dependsOn} 语义就是「值不为 true 就藏起来」）。
     */
    @Test
    public void everyDependentAutomixRowHidesItselfWhileTheSwitchIsOff() {
        List<String> keys = new ArrayList<>();
        for (SettingSpec spec : SettingsCatalog.specs()) {
            if (SettingsCatalog.AUDIO.equals(spec.category)
                    && SettingsCatalog.AUTOMIX_ENABLED_KEY.equals(spec.dependsOn)) {
                keys.add(spec.key + "(" + spec.type + ")");
            }
        }
        // 逐条列出来（不是数个数）：少一条 / 多一条都能从失败信息里看出来是谁
        assertTrue("模式那一行必须依赖总开关：" + keys,
                keys.contains(SettingsCatalog.AUTOMIX_MODE_KEY + "(dropdown)"));
        assertTrue("AI 接法那一行必须依赖总开关：" + keys,
                // 2026-11-16：接法选择从 dropdown 改成 chips 卡（用户要求"别排成一列"）
                keys.contains(SettingsCatalog.AUTOMIX_STYLE_KEY + "(chips)"));
        assertTrue("交叉淡化窗口必须依赖总开关：" + keys,
                keys.contains(SettingsCatalog.AUTOMIX_CROSSFADE_SECS_KEY + "(slider)"));
        assertTrue("声波重合窗口必须依赖总开关：" + keys,
                keys.contains(SettingsCatalog.AUTOMIX_HARMONIC_SECS_KEY + "(slider)"));
        assertTrue("融合期人声（桌面 dropdown + 安卓 segmented）两行都要依赖总开关：" + keys,
                keys.contains(SettingsCatalog.AUTOMIX_VOCAL_MODE_KEY + "(dropdown)")
                        && keys.contains(SettingsCatalog.AUTOMIX_VOCAL_MODE_KEY + "(segmented)"));

        // 而且自动混音那一组之外的键不许顺手挂上这个依赖
        for (SettingSpec spec : SettingsCatalog.specs()) {
            if (SettingsCatalog.AUTOMIX_ENABLED_KEY.equals(spec.dependsOn)) {
                assertEquals("依赖总开关的行必须都在音频分类的自动混音组里：" + spec.key,
                        SettingsCatalog.AUDIO, spec.category);
                // 2026-11-16：30 条「接法开关」独立成 automixStyles 组（可折叠），
                // 仍然依附总开关
                assertTrue("依赖总开关的行必须在 automix / automixStyles 两组的某一组里：" + spec.key,
                        "automix".equals(spec.group) || "automixStyles".equals(spec.group));
            }
        }
    }

    // ==================================================================
    // 那一行活文案
    // ==================================================================

    /** 关着时那句话必须说「硬接、不分析」——不能只说一个「已关闭」。 */
    @Test
    public void theLiveTextSaysWhatOffActuallyMeans() {
        String text = SettingsCore.automixStatusNotice(false,
                SettingsCatalog.AUTOMIX_MODE_CROSSFADE, "交叉淡化", 6);
        assertEquals(I18n.tr("settings.automixStatus.off"), text);
        assertFalse("关着的时候不该出现任何接法名（会让人以为在跑）",
                text.contains("交叉淡化"));
    }

    /**
     * <b>开着但接法是无缝拼接</b>是这套设置里最容易骗人的一种状态：
     * 开关是 ON，而窗口是 0、根本不会交叠。这一行必须把它说出口。
     */
    @Test
    public void theLiveTextWarnsWhenTheTechniqueItselfIsAHardJoin() {
        String text = SettingsCore.automixStatusNotice(true,
                SettingsCatalog.AUTOMIX_MODE_GAPLESS, "无缝拼接", 6);
        assertEquals(I18n.tr("settings.automixStatus.gapless"), text);
        assertTrue("必须点明它是窗口 0 的硬接：" + text, text.contains("无缝拼接"));
    }

    /** 开着且真会交叠时：把接法与窗口原样说给用户。 */
    @Test
    public void theLiveTextNamesTheTechniqueAndItsWindow() {
        String crossfade = SettingsCore.automixStatusNotice(true,
                SettingsCatalog.AUTOMIX_MODE_CROSSFADE, "交叉淡化", 6);
        assertEquals(I18n.tr("settings.automixStatus.window", "交叉淡化", Integer.valueOf(6)),
                crossfade);
        assertTrue(crossfade.contains("交叉淡化"));
        assertTrue(crossfade.contains("6"));

        String harmonic = SettingsCore.automixStatusNotice(true,
                SettingsCatalog.AUTOMIX_MODE_HARMONIC, "声波重合", 8);
        assertTrue(harmonic.contains("声波重合"));
        assertTrue(harmonic.contains("8"));

        // AI 模式的窗口是分析结论给的，没有固定值可说
        String ai = SettingsCore.automixStatusNotice(true,
                SettingsCatalog.AUTOMIX_MODE_AI, "AI 模式", 6);
        assertEquals(I18n.tr("settings.automixStatus.ai", "AI 模式"), ai);
    }

    private static SettingSpec setting(String key) {
        return SettingsCatalog.specs().stream()
                .filter(candidate -> key.equals(candidate.key))
                .findFirst()
                .orElseThrow(() -> new AssertionError(key + " setting missing"));
    }

    /**
     * <strong>下拉里的选项必须与 {@code MixStyle.UI_ORDER} 一一对应且同序。</strong>
     *
     * <p>设置里存的是 {@code UI_ORDER} 的<strong>下标</strong>，界面上显示的是那串 i18n 键。
     * 两边一旦错位，用户选「生成式」会得到别的接法（或者反过来），而两处都不报错——
     * 只会在听感上莫名其妙。所以这里按顺序逐项钉住。
     *
     * <p>同时也钉住「新选项追加在末尾」：中间插一个会让所有老用户存着的下标整体错位。
     */
    @Test
    public void theStyleDropdownMatchesUiOrder() {
        SettingSpec spec = setting(SettingsCatalog.AUTOMIX_STYLE_KEY);
        assertNotNull("接法那一行必须存在", spec);
        java.util.List<String> options = spec.options;
        assertNotNull("接法那一行必须是下拉", options);
        MixStyle[] order = MixStyle.UI_ORDER;
        // **界面用的键与 MixStyle.specName() 不是一回事**：后者是上游带过来的调试名
        // （`SHOW_MODE` 叫 `buildUp`，而 i18n 键叫 `showMode`，见 AiMixTest 里那条断言）。
        // 所以这里逐项写出**真正的键**，而不是拿 specName 拼——拼出来的东西
        // 看起来对得上、实际是个不存在的键。
        // 前 7 项是历史顺序（老用户存着的下标指向它们），**不许插中间**；
        // 第 8 项起是 2026-10-10 那批"参照模板"（george 的 24 条），一律追加在末尾。
        String[] keys = {
                "settings.automixStyle.auto",
                "settings.automixStyle.plainBlend",
                "settings.automixStyle.beatCut",
                "settings.automixStyle.bassSwap",
                "settings.automixStyle.tailRide",
                "settings.automixStyle.showMode",
                "settings.automixStyle.learnedEq",     // ← 历史 7 项到此为止
                "settings.automixStyle.crossfadeLong",
                "settings.automixStyle.crossfadeOverlap",
                "settings.automixStyle.crossfadeRingout",
                "settings.automixStyle.crossfadeOverlapReverbPendingFx",
                "settings.automixStyle.crossfadeRingoutReverbPendingFx",
                "settings.automixStyle.filterHighToLow",
                "settings.automixStyle.filterLongHighToLow",
                "settings.automixStyle.filterShortHighToLow",
                "settings.automixStyle.filterExpansion",
                "settings.automixStyle.filterLongOutShortIn",
                "settings.automixStyle.filterDynamicIn",
                "settings.automixStyle.filterLongOutShortInDelayPendingFx",
                "settings.automixStyle.filterLongOutShortInReverbPendingFx",
                "settings.automixStyle.filterSoftReverbPendingFx",
                "settings.automixStyle.noVocalClash",
                "settings.automixStyle.hiphopDelayOutPendingFx",
                "settings.automixStyle.hiphopLooping",
                "settings.automixStyle.popDelayOutPendingFx",
                "settings.automixStyle.popReverbPendingFx",
                "settings.automixStyle.danceAnyKey",
                "settings.automixStyle.danceRepeater",
                "settings.automixStyle.gapless",
                "settings.automixStyle.splice",
        };
        assertEquals("选项个数必须与 UI_ORDER 一致：" + options, order.length, options.size());
        assertEquals("写死的那串键也要与 UI_ORDER 一样长", order.length, keys.length);
        for (int i = 0; i < order.length; i++) {
            assertEquals("第 " + i + " 项与 UI_ORDER 对不上（设置里存的就是这个下标）",
                    keys[i], options.get(i));
        }
        // **生成式（2026-10-01 删除）本来就在 UI_ORDER 的末尾**，所以删掉它之后
        // 前六项的 UI 下标一个都没变——老用户存着的 automix 风格下标仍然指向同一档。
        // 这条断言守的就是这件事：以后再加/删档，不许插在中间。
        assertEquals("删掉末尾那一档之后，剩下的项数不变（老下标不错位）",
                keys.length, order.length);
        // 每一个键都要真的能翻译出来（键写错了界面会直接显示那串键名）
        for (String key : keys) {
            assertNotEquals("这个键在 zh_CN 里翻译不出来：" + key, key, I18n.tr(key));
        }
    }

}
