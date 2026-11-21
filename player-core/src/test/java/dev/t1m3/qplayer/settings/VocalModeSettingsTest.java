package dev.t1m3.qplayer.settings;

import dev.t1m3.qplayer.audio.model.StemModels;
import dev.t1m3.qplayer.audio.stem.StemSeparators;
import dev.t1m3.qplayer.i18n.I18n;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 「融合期人声」这一条设置：<b>两端形状不同但共用一个键</b>的那两行、以及那一行
 * <b>活文案</b>的诚实性。
 *
 * <h2>为什么桌面三选一、移动端两选一</h2>
 *
 * <p>AI 那一档需要宿主提供推理实现（htdemucs 走 ONNX Runtime），而<b>安卓不安装那个实现</b>
 * （{@code StemModels.Opener} 在移动端不安装，于是 {@code StemModels.available()} 恒为 false）。
 * 所以那一档根本不该在手机的设置里出现——给一个选了必然无效的选项，比不给更糟。
 *
 * <p>而 {@code SettingSpec} 的 options 是一个固定列表，没法按平台少给一项，所以「少一档」
 * 只能靠两行（一行 {@code onlyOn(DESKTOP)}、一行 {@code onlyOn(ANDROID)}）；
 * 但<b>键只有一个</b>：引擎那边刻意不知道自己在哪个平台，两个键会逼出
 * 「按平台挑键读」这种漏进音频层的判断，而且另一个键在对面宿主上是一份永远生效不了的死值。
 *
 * <h2>为什么测的是静态方法而不是 {@code SettingsCore.info()}</h2>
 *
 * <p>{@code SettingsCore.load(...)} 在 player-core 的单测里跑不起来（它要
 * {@code Fonts.listFamilies()}，而 Skija 的原生库只在宿主运行时里，实测
 * {@code UnsatisfiedLinkError}）。所以拼文案那一段被抽成了纯函数
 * （{@link SettingsCore#vocalModeNotice}，理由写在它的 javadoc 里），
 * 这里断言的就是它；读设置那一层只有一行 {@code intOf(KEY)}。
 */
public class VocalModeSettingsTest {

    @Before
    public void useChinese() {
        I18n.instance().setLanguage("zh_CN");
    }

    @After
    public void restoreDefaultLanguage() {
        I18n.instance().setLanguage(I18n.FALLBACK);
    }

    // ==================================================================
    // 两个平台的形状
    // ==================================================================

    /**
     * 桌面那一行：三选一（含 AI）、落在「自动混音」那一组、默认不分离、带活文案 provider。
     */
    @Test
    public void theDesktopRowOffersAllThreeModes() {
        SettingSpec spec = rowFor(SettingsCatalog.DESKTOP);

        assertEquals(SettingSpec.DROPDOWN, spec.type);
        assertEquals(SettingsCatalog.AUDIO, spec.category);
        assertEquals("automix", spec.group);
        assertEquals("默认必须是「不分离」", SettingsCatalog.AUTOMIX_VOCAL_MODE_OFF, spec.def);
        assertEquals("活文案的 provider id", "automixVocalNotice", spec.provider);
        assertTrue(spec.appliesTo(SettingsCatalog.DESKTOP));
        assertFalse("桌面那一行不该出现在手机上", spec.appliesTo(SettingsCatalog.ANDROID));
        assertEquals(3, spec.options.size());
    }

    /**
     * 移动端那一行：<b>只给「不分离 / DSP」</b>——AI 那一档在手机上选了也跑不起来。
     */
    @Test
    public void theMobileRowHasNoAiOption() {
        SettingSpec spec = rowFor(SettingsCatalog.ANDROID);

        assertEquals(SettingSpec.SEGMENTED, spec.type);
        assertEquals(SettingsCatalog.AUDIO, spec.category);
        assertEquals("automix", spec.group);
        assertEquals(SettingsCatalog.AUTOMIX_VOCAL_MODE_OFF, spec.def);
        assertTrue(spec.appliesTo(SettingsCatalog.ANDROID));
        assertFalse("移动端那一行不该出现在桌面上", spec.appliesTo(SettingsCatalog.DESKTOP));
        assertEquals("移动端只有两档", 2, spec.options.size());
        assertFalse("AI 那一档不许出现在移动端：" + spec.options,
                spec.options.contains("settings.automixVocalMode.ai"));
    }

    /**
     * <b>两行共用一个键</b>：设置里只有这一个值，读它的地方只有一处。
     *
     * <p>这条同时挡住「顺手改成两个键」这种看起来更省事的写法——那会让音频层不得不
     * 按平台挑键读（见类注释）。
     */
    @Test
    public void bothPlatformsShareOneKeyAndNoSecondKeyExists() {
        assertEquals("这个键总共就该有两行（桌面一行、移动端一行）", 2, rows().size());
        for (SettingSpec spec : rows()) {
            assertEquals(SettingsCatalog.AUTOMIX_VOCAL_MODE_KEY, spec.key);
        }
        // 两端各自生效的那一行必须恰好一行（SettingsCore.load 就是按 appliesTo 过滤的，
        // 所以「同一端两行都生效」会让 specsByKey 里后一行悄悄顶掉前一行）
        assertEquals(1, specsFor(SettingsCatalog.DESKTOP).size());
        assertEquals(1, specsFor(SettingsCatalog.ANDROID).size());
    }

    /**
     * 两行的<b>选项下标就是取值</b>：第 i 个选项 = 取值 i。
     *
     * <p>这条要钉住，因为「选项与取值错位」的表现是「选了 DSP 实际用模型」——
     * 界面上看着一切正常，只有听感不对。也顺带钉住「移动端存不进 2」：
     * 取值的上界由选项表决定（{@code SettingsCore.normalize} 夹到 {@code options.size()-1}）。
     */
    @Test
    public void eachRowsOptionsLineUpWithTheModeValues() {
        SettingSpec desktop = rowFor(SettingsCatalog.DESKTOP);
        assertEquals("settings.automixVocalMode.off",
                desktop.options.get(SettingsCatalog.AUTOMIX_VOCAL_MODE_OFF));
        assertEquals("settings.automixVocalMode.dsp",
                desktop.options.get(SettingsCatalog.AUTOMIX_VOCAL_MODE_DSP));
        assertEquals("settings.automixVocalMode.ai",
                desktop.options.get(SettingsCatalog.AUTOMIX_VOCAL_MODE_AI));

        SettingSpec mobile = rowFor(SettingsCatalog.ANDROID);
        assertEquals("settings.automixVocalMode.off",
                mobile.options.get(SettingsCatalog.AUTOMIX_VOCAL_MODE_OFF));
        assertEquals("settings.automixVocalMode.dsp",
                mobile.options.get(SettingsCatalog.AUTOMIX_VOCAL_MODE_DSP));

        // 而这三个取值就是接缝上的三个档（数字只有一个真源）
        assertEquals(StemSeparators.OFF, SettingsCatalog.AUTOMIX_VOCAL_MODE_OFF);
        assertEquals(StemSeparators.DSP, SettingsCatalog.AUTOMIX_VOCAL_MODE_DSP);
        assertEquals(StemSeparators.AI, SettingsCatalog.AUTOMIX_VOCAL_MODE_AI);
    }

    /** 标题、两段说明与三个选项的文案（两份语言各一遍；缺了会直接把 key 显示给用户）。 */
    @Test
    public void everyStringOfBothRowsExistsInBothLanguages() {
        for (String language : new String[]{"zh_CN", "en_US"}) {
            I18n.instance().setLanguage(language);
            for (SettingSpec spec : rows()) {
                assertFalse(language + " 缺标题", spec.title.equals(I18n.tr(spec.title)));
                assertFalse(language + " 缺说明：" + spec.desc, spec.desc.equals(I18n.tr(spec.desc)));
                for (String option : spec.options) {
                    assertFalse(language + " 缺选项文案：" + option, option.equals(I18n.tr(option)));
                }
            }
        }
    }

    // ==================================================================
    // 文案里的代价与门控
    // ==================================================================

    /**
     * 桌面那段说明必须把 AI 的<b>实测代价</b>说出来（用户要在选之前知道预期）。
     *
     * <p><b>这些数字变了就要一起改这一行</b>——这条测试的作用是「不让这段代价被悄悄删掉」，
     * 而不是把某一次实测钉成永久事实。
     *
     * <p>当前实测（Java 进程内、焊死 3.9 s 窗口的那份模型）：峰值约 1.2 GB；
     * 3.9 s 窗口约 5.0 s、8 秒窗口约 9.1 s（15 秒预算内）、16 秒窗口 15.7 s + 建会话 1.6 s
     * ≈ 17.3 s（<b>超预算 → 那一次退回不去人声</b>）。
     */
    @Test
    public void theDesktopDescriptionStatesTheMeasuredCost() {
        String base = I18n.tr(rowFor(SettingsCatalog.DESKTOP).desc);

        assertTrue("要点出峰值内存（约 1.2 GB）：" + base, base.contains("1.2 GB"));
        assertTrue("要点出超预算的下场：" + base, base.contains("不去人声"));
        assertTrue("要点出哪一档最容易撞上这条（表现模式）：" + base, base.contains("表现模式"));
        assertTrue("要说清 DSP 不需要下载：" + base, base.contains("不需要下载"));
        assertTrue("要点名 AI 用哪个模型：" + base, base.contains(StemModels.MODEL_NAME));
    }

    /** 移动端那段说明只讲 DSP，并且说清 AI 那一档不在这一端。 */
    @Test
    public void theMobileDescriptionTalksAboutDspAndSaysAiIsDesktopOnly() {
        String base = I18n.tr(rowFor(SettingsCatalog.ANDROID).desc);

        assertTrue("要点出 DSP 不需要下载：" + base, base.contains("不需要下载"));
        assertTrue("要说清 AI 那一档只在桌面：" + base, base.contains("桌面"));
        assertFalse("移动端不该提那个模型文件（它在这一端根本用不上）：" + base,
                base.contains(StemModels.MODEL_NAME));
        assertFalse("移动端不该出现「已就绪」这种承诺", base.contains("已就绪"));
    }

    /**
     * <b>选了 AI 而模型不在磁盘上时，那一行不许说「已启用」。</b>
     *
     * <p>它要说的是：需要下载、现在会正常过渡但不去人声（而且不会改用 DSP）、
     * 模型叫什么名字。断言分成两半：<b>附加上去的那一句</b>必须自己就把话说全，
     * 而不是靠基础说明里恰好也有那几个字。
     */
    @Test
    public void pickingAiWithoutTheModelSaysItWillNotDropVocals() {
        String base = I18n.tr(rowFor(SettingsCatalog.DESKTOP).desc);

        String notice = SettingsCore.vocalModeNotice(base, SettingsCatalog.AUTOMIX_VOCAL_MODE_AI, false);
        String suffix = notice.substring(base.length());

        assertTrue("必须点名是哪个模型：" + suffix, suffix.contains(StemModels.MODEL_NAME));
        assertTrue("必须明说需要下载：" + suffix, suffix.contains("下载"));
        assertTrue("必须明说现在不去人声：" + suffix, suffix.contains("不去人声"));
        assertFalse("模型不在磁盘上却说「已就绪」——这正是要挡住的那句话：" + notice,
                notice.contains("已就绪"));
        assertTrue("基础说明不能被丢掉", notice.startsWith(base));
    }

    /** 模型在磁盘上时，选了 AI 才可以说「已就绪」。 */
    @Test
    public void pickingAiWithTheModelSaysItIsReady() {
        String base = I18n.tr(rowFor(SettingsCatalog.DESKTOP).desc);

        String notice = SettingsCore.vocalModeNotice(base, SettingsCatalog.AUTOMIX_VOCAL_MODE_AI, true);
        String suffix = notice.substring(base.length());

        assertTrue(suffix.contains("已就绪"));
        assertTrue("也要点名是哪个模型：" + suffix, suffix.contains(StemModels.MODEL_NAME));
        assertFalse("模型在的时候不该再提「不去人声」：" + suffix, suffix.contains("不去人声"));
    }

    /**
     * DSP 与「不分离」两档<b>不加任何话</b>：它们没有「缺东西」这回事，
     * 多一句只会让人以为要去下载点什么。移动端跑的就是这一支（它的键只可能是 0 或 1）。
     */
    @Test
    public void theDspAndOffModesGetNoExtraLine() {
        String base = I18n.tr(rowFor(SettingsCatalog.DESKTOP).desc);

        assertEquals(base, SettingsCore.vocalModeNotice(base, SettingsCatalog.AUTOMIX_VOCAL_MODE_DSP, false));
        assertEquals(base, SettingsCore.vocalModeNotice(base, SettingsCatalog.AUTOMIX_VOCAL_MODE_DSP, true));
        assertEquals(base, SettingsCore.vocalModeNotice(base, SettingsCatalog.AUTOMIX_VOCAL_MODE_OFF, false));
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /** 这个键的所有行（桌面 + 移动端各一行）。 */
    private static List<SettingSpec> rows() {
        List<SettingSpec> out = new ArrayList<SettingSpec>();
        for (SettingSpec spec : SettingsCatalog.specs()) {
            if (SettingsCatalog.AUTOMIX_VOCAL_MODE_KEY.equals(spec.key)) {
                out.add(spec);
            }
        }
        if (out.isEmpty()) {
            throw new AssertionError(SettingsCatalog.AUTOMIX_VOCAL_MODE_KEY + " setting missing");
        }
        return out;
    }

    private static List<SettingSpec> specsFor(String platform) {
        List<SettingSpec> out = new ArrayList<SettingSpec>();
        for (SettingSpec spec : rows()) {
            if (spec.appliesTo(platform)) {
                out.add(spec);
            }
        }
        return out;
    }

    /** 某一端实际生效的那一行（{@code SettingsCore.load} 就是按这个过滤的）。 */
    private static SettingSpec rowFor(String platform) {
        List<SettingSpec> active = specsFor(platform);
        assertEquals("这一端应当恰好有一行生效", 1, active.size());
        return active.get(0);
    }
}

