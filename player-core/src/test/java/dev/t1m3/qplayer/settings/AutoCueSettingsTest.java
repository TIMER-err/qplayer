package dev.t1m3.qplayer.settings;

import dev.t1m3.qplayer.i18n.I18n;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 「自动点位」这一条设置的形状与它到引擎的接线。
 *
 * <p>要钉住三件事：
 *
 * <ol>
 *   <li>设置里那一行：开关、默认<strong>关</strong>、落在自动混音组、依赖总开关、两端都能设；</li>
 *   <li>文案齐全（中英都要有 title 与 desc）——缺 key 的表现在是「界面上一片空白」；</li>
 *   <li>引擎接线：打开时接法外面<strong>多套一层自动点位</strong>；关着时一字不改；
 *       无缝拼接（窗口 0）即使开着也不套——没有可融合的片段，套上去只会白扫两首整曲。</li>
 * </ol>
 */
public class AutoCueSettingsTest {

    @Before
    public void useChinese() {
        I18n.instance().setLanguage("zh_CN");
    }

    @After
    public void restoreDefaultLanguage() {
        I18n.instance().setLanguage(I18n.FALLBACK);
    }

    @Test
    public void theRowIsAnOffByDefaultToggleThatDependsOnTheMasterSwitch() {
        SettingSpec spec = row();
        assertEquals(SettingSpec.SWITCH, spec.type);
        assertEquals("默认必须是关：它要扫两首整曲",
                Boolean.FALSE, spec.def);
        assertEquals("automix", spec.group);
        assertEquals(SettingsCatalog.AUDIO, spec.category);
        assertEquals("总开关关着时它不该显示", SettingsCatalog.AUTOMIX_ENABLED_KEY, spec.dependsOn);
        assertTrue("两端都能设（安卓也跑同一套引擎）",
                spec.appliesTo(SettingsCatalog.DESKTOP));
        assertTrue(spec.appliesTo(SettingsCatalog.ANDROID));
    }

    @Test
    public void bothLanguagesHaveBothStrings() {
        for (String lang : new String[]{"zh_CN", "en_US"}) {
            I18n.instance().setLanguage(lang);
            SettingSpec spec = row();
            assertFalse(lang + " 缺标题", spec.title.equals(I18n.tr(spec.title)));
            assertFalse(lang + " 缺说明", spec.desc.equals(I18n.tr(spec.desc)));
        }
    }

    @Test
    public void theStatusLineSaysWhenTheCuePointIsScanned() {
        // 这一行是用户判断「现在到底会不会做过渡」的唯一入口：打开自动点位之后
        // 那句「窗口 6 s」只说了交叠多长，不说「从哪儿开始」——必须补一句。
        String plain = SettingsCore.automixStatusNotice(true,
                SettingsCatalog.AUTOMIX_MODE_CROSSFADE, "交叉淡化", 6, false);
        String scanned = SettingsCore.automixStatusNotice(true,
                SettingsCatalog.AUTOMIX_MODE_CROSSFADE, "交叉淡化", 6, true);
        assertTrue("不开时说原话就行：" + plain, plain.contains("窗口 6"));
        assertTrue("开了要说清融合点是扫出来的：" + scanned,
                scanned.contains("自动点位"));
        // 无缝拼接没有可融合的片段：引擎那一层不套包装，这里也不该提自动点位
        String gapless = SettingsCore.automixStatusNotice(true,
                SettingsCatalog.AUTOMIX_MODE_GAPLESS, "无缝拼接", 6, true);
        assertFalse("无缝拼接下不该提自动点位：" + gapless, gapless.contains("自动点位"));
        // 总开关关着时同样不提（什么都不做）
        String off = SettingsCore.automixStatusNotice(false,
                SettingsCatalog.AUTOMIX_MODE_CROSSFADE, "交叉淡化", 6, true);
        assertFalse("关着时不该提自动点位：" + off, off.contains("自动点位"));
    }

    private static SettingSpec row() {
        for (SettingSpec candidate : SettingsCatalog.specs()) {
            if (SettingsCatalog.AUTOMIX_AUTO_CUE_KEY.equals(candidate.key)) {
                return candidate;
            }
        }
        throw new AssertionError("设置目录里没有「自动点位」这一行");
    }
}
