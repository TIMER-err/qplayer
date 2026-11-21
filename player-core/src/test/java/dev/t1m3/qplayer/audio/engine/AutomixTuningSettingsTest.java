package dev.t1m3.qplayer.audio.engine;

import dev.t1m3.qplayer.audio.mix.MixTuning;
import dev.t1m3.qplayer.settings.SettingsCatalog;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>融合期那几个旋钮真的接进了宿主</b>（P2/P4/P5）。
 *
 * <p>为什么单独一个类：{@code AutomixSettings} 要读的是一个真的 {@code SettingsCore}，
 * 而它在这台机器的 player-core 单测里跑不起来（{@code load(...)} 会走到
 * {@code Fonts.listFamilies()}，Skija 的原生库只在宿主运行时里，实测直接
 * {@code UnsatisfiedLinkError}）——这一条与 {@code AutomixVocalModeGatingTest} 的
 * 类注释里写的是同一件事。所以这里钉的是<b>「读设置那一行」的两端</b>：
 * 空串 → 全默认、合法串 → 真的落到字段上、写错 → 退回默认且不炸。
 *
 * <p>这条判据存在的原因值得记一笔：在这一项设置键出现之前，{@code MixTuning} 的 24 个旋钮
 * <b>只能改代码</b>（{@code AutomixSettings} 直接写死 {@code MixTuning.defaults()}），
 * 于是「默认关」在成品里等于「这个功能不存在」——P2 的默认值因此一度被临时改成 0.5。
 * 有了这个键，「默认关 + 用户可开」才是真的。
 */
public class AutomixTuningSettingsTest {

    /** 没有设置来源（{@code AutomixSettings.defaults()}）→ 没有设置串，工厂一律用全默认。 */
    @Test
    public void withoutSettingsTheSpecIsAbsent() {
        assertEquals("没有设置来源时不该有设置串", null,
                AutomixSettings.defaults().tuningSpec());
        assertEquals("没有串就是全默认",
                MixTuning.defaults().toLine(), AutomixSettings.parseTuning(null).toLine());
    }

    /** 空串 / 只有空白 → 全默认（设置项留空是默认状态，不该被当成「写错了」）。 */
    @Test
    public void anEmptySpecMeansAllDefaults() {
        assertEquals(MixTuning.defaults().toLine(), AutomixSettings.parseTuning("").toLine());
        assertEquals(MixTuning.defaults().toLine(), AutomixSettings.parseTuning("   ").toLine());
        assertEquals(MixTuning.defaults().toLine(), AutomixSettings.parseTuning(null).toLine());
    }

    /** 合法串真的落到字段上（三个与「糊」直接相关的旋钮都要能调）。 */
    @Test
    public void aValidSpecReachesTheKnobs() {
        MixTuning t = AutomixSettings.parseTuning("width=0.5,low_duck=0,level_match=0");
        assertEquals("空间展宽", 0.5f, t.spaceWidth, 1e-6f);
        assertEquals("低频让位", 0.0f, t.lowDuck, 1e-6f);
        assertEquals("响度匹配", 0.0f, t.levelMatch, 1e-6f);
        System.out.println("[tuning] width=0.5,low_duck=0,level_match=0 → "
                + t.toLine());
    }

    /**
     * 默认值：空间展宽<b>一半</b>（用户 2026-10-02 听过之后定的），响度匹配与低频让位开。
     *
     * <p>这一条曾经钉的是「默认关」（那时计划这么写，因为旋钮在宿主里没有接入点，
     * 默认关等于功能不存在）。现在两件事都变了：有了 {@code automixMixTuning} 可调，
     * 而且用户实际听过、挑的是 0.5。判据跟着改，注释留着说明为什么变。
     */
    @Test
    public void theShippedDefaultsMatchWhatWasDecided() {
        MixTuning d = MixTuning.defaults();
        assertEquals("空间展宽默认一半（用户定的）", 0.5f, d.spaceWidth, 1e-6f);
        assertEquals("响度匹配默认开", 1.0f, d.levelMatch, 1e-6f);
        assertEquals("低频让位默认开", 1.0f, d.lowDuck, 1e-6f);
        assertTrue("设置键要真的在目录里（否则界面上改不到）",
                SettingsCatalog.specs().stream().anyMatch(
                        s -> SettingsCatalog.AUTOMIX_MIX_TUNING_KEY.equals(s.key)));
    }

    /**
     * <b>写错了不许炸</b>：认不出的键、解析不了的值，都要退回<b>全默认</b>。
     *
     * <p>{@code MixTuning.parse} 自己的纪律是「认不出就抛」（调参时写错必须立刻知道），
     * 但宿主这一层每次过渡都会走一遍——一个手滑的字符不该让自动混音整段失效。
     */
    @Test
    public void aBrokenSpecFallsBackInsteadOfThrowing() {
        String defaults = MixTuning.defaults().toLine();
        assertEquals("认不出的键", defaults, AutomixSettings.parseTuning("nonsense=1").toLine());
        assertEquals("解析不了的值", defaults, AutomixSettings.parseTuning("width=abc").toLine());
        assertEquals("两个都对不上的写法", defaults,
                AutomixSettings.parseTuning("width=0.5;nonsense=1").toLine());
        System.out.println("[tuning] 三种写错都退回了全默认（日志里各有一行 WARN）");
    }
    // ---- 三个正经控件（用户 2026-10-02：「旋钮在哪我都找不到」）------------------------

    /**
     * <b>控件真的落到字段上</b>：滑杆 0%/100%、开关关、窗口 2 倍，各钉一遍。
     *
     * <p>为什么这三项搬成了控件：{@code width} 是用户唯一亲手挑过默认值的旋钮（听过之后
     * 定了 0.5），{@code tempo_stretch} 是 1002j 里唯一有听感风险的改动（A/B 必须能一键切），
     * {@code window_scale} 是「铺得开 / 听得出」之间的那根轴。只认文本的键对听众等于不存在。
     */
    @Test
    public void theControlsReachTheKnobs() {
        MixTuning t = AutomixSettings.parseTuning(AutomixSettings.composeSpec("", 0, false, 200));
        assertEquals("空间展宽 0% = 关", 0.0f, t.spaceWidth, 1e-6f);
        assertEquals("变速开关关掉", 0.0f, t.tempoStretch, 1e-6f);
        assertEquals("窗口 200%", 2.0f, t.windowScale, 1e-6f);
        MixTuning t2 = AutomixSettings.parseTuning(AutomixSettings.composeSpec("", 100, true, 50));
        assertEquals("空间展宽 100% = 全推", 1.0f, t2.spaceWidth, 1e-6f);
        assertEquals("变速开关打开", 1.0f, t2.tempoStretch, 1e-6f);
        assertEquals("窗口 50%", 0.5f, t2.windowScale, 1e-6f);
        System.out.println("[tuning] 控件读数 → " + t.toLine());
    }

    /**
     * <b>控件的默认读数 = 全默认旋钮</b>（升级用户不该因为多了三个控件而听到别的混音）。
     *
     * <p>顺带钉住一件容易写错的事：{@code SettingsCore.intOf/bool} 对<b>盘上没有的键</b>
     * 返回 0/false，而 {@code read()} 用 spec 的默认值兜底——所以老设置文件（没有这三个键）
     * 读出来是 50/开/100，不是 0/关/0。这一条要是反了，用户升上来会发现展宽关了、
     * 变速也没了，而且毫无提示。
     */
    @Test
    public void theDefaultControlReadingsAreTheDefaultTuning() {
        String spec = AutomixSettings.composeSpec(null,
                SettingsCatalog.DEFAULT_BLEND_WIDTH_PCT, true,
                SettingsCatalog.DEFAULT_WINDOW_SCALE_PCT);
        assertEquals("控件走默认 → 逐位等于全默认",
                MixTuning.defaults().toLine(), AutomixSettings.parseTuning(spec).toLine());
        assertEquals("空串与纯空白都只由控件决定",
                AutomixSettings.composeSpec("", 50, true, 100),
                AutomixSettings.composeSpec("   ", 50, true, 100));
    }

    /** 文本串里<b>其余</b>键照常生效：控件只挑走了三个，不是把这一行废掉。 */
    @Test
    public void theTextBoxStillOwnsEveryOtherKey() {
        MixTuning t = AutomixSettings.parseTuning(
                AutomixSettings.composeSpec("low_duck=0,crossover=180", 50, true, 100));
        assertEquals("低频让位", 0.0f, t.lowDuck, 1e-6f);
        assertEquals("分频点", 180.0f, t.crossoverHz, 1e-6f);
        assertEquals("控件仍生效", 0.5f, t.spaceWidth, 1e-6f);
    }

    /**
     * <b>三个键的正主是控件</b>：文本里再写一遍<b>不生效</b>，而不是「看谁先谁后」。
     *
     * <p>选这一边是因为界面：用户拖滑杆时看得见的是滑杆，拖了没反应会被当成坏了。
     * 反过来「文本赢」在拖滑杆时同样是静默的——两种都不好，所以选<b>可说的那一种</b>：
     * 控件赢，并且写重复键时日志里有一行 WARN（{@code warnControlOwnedKeysWrittenByHand}）。
     */
    @Test
    public void theControlsWinOverTheTextBox() {
        MixTuning t = AutomixSettings.parseTuning(
                AutomixSettings.composeSpec("width=0,scale=2,tempo_stretch=0", 50, true, 100));
        assertEquals("文本里的 width 不该盖掉控件", 0.5f, t.spaceWidth, 1e-6f);
        assertEquals("文本里的 tempo_stretch 不该盖掉开关", 1.0f, t.tempoStretch, 1e-6f);
        assertEquals("文本里的 scale 不该盖掉滑杆", 1.0f, t.windowScale, 1e-6f);
    }

    /**
     * <b>别名也算「写了」</b>：{@code MixTuning.parse} 认的写法这里都得认，漏一个那条
     * WARN 就漏一次——「写了却被忽略且不吭声」正是要防的事。
     */
    @Test
    public void aliasesCountAsWritten() {
        assertNotNull("window_scale", AutomixSettings.controlOwnedKeyIn("window_scale=1.4"));
        assertNotNull("scale 别名", AutomixSettings.controlOwnedKeyIn("scale=1.4"));
        assertNotNull("tempoStretch 别名 + 空格", AutomixSettings.controlOwnedKeyIn(" tempoStretch = 0"));
        assertNotNull("夹在别的键中间", AutomixSettings.controlOwnedKeyIn("low_duck=0,windowScale=2"));
        assertNotNull("width", AutomixSettings.controlOwnedKeyIn("width=0.9"));
        assertNull("其余键不算", AutomixSettings.controlOwnedKeyIn("low_duck=0,crossover=180"));
        assertNull("空串不算", AutomixSettings.controlOwnedKeyIn(""));
    }

    /** 三个控件本身要在目录里、且要在「自动混音」那一组里（否则界面上还是找不到）。 */
    @Test
    public void theThreeControlsAreInTheCatalog() {
        String[] keys = {SettingsCatalog.AUTOMIX_BLEND_WIDTH_KEY,
                SettingsCatalog.AUTOMIX_TEMPO_STRETCH_KEY,
                SettingsCatalog.AUTOMIX_WINDOW_SCALE_KEY};
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            dev.t1m3.qplayer.settings.SettingSpec spec = SettingsCatalog.specs().stream()
                    .filter(s -> key.equals(s.key)).findFirst().orElse(null);
            assertNotNull("设置键要在目录里：" + key, spec);
            assertEquals("要在自动混音那一组：" + key, "automix", spec.group);
        }
    }
}
