package dev.t1m3.qplayer.audio.engine;

import dev.t1m3.qplayer.audio.mix.Crossfade;
import dev.t1m3.qplayer.audio.mix.Gapless;
import dev.t1m3.qplayer.audio.mix.Transition;
import dev.t1m3.qplayer.audio.mix.TransitionFactory;
import dev.t1m3.qplayer.audio.mix.VocalSeparationTransition;
import dev.t1m3.qplayer.audio.stem.StemSeparators;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 自动混音<strong>总开关</strong>：关掉时「一次过渡都不做」这件事落在哪一层、
 * 打开时又是不是照旧读那一档接法。
 *
 * <h2>为什么单独一个类</h2>
 *
 * <p>与 {@code AutomixVocalModeGatingTest} 同一条理由：{@code AutomixSettings} 要读一个
 * 真的 {@code SettingsCore}，而它在这台机器的 player-core 单测里跑不起来（{@code load}
 * 会走到 Skija 的原生字体枚举）。所以这里钉的是<strong>纯的那一半</strong>——
 * {@link AutomixSettings#factoryFor(boolean, int, int, float, float, int, int, int)}
 * 那张表，加上「读不到设置时是什么」（{@link AutomixSettings#defaults()}）。
 *
 * <p><b>总开关的判据是窗口为 0</b>：{@code Director.shouldPrepare()} 与
 * {@code shouldBeginTransition()} 的第一句都是看窗口，所以「关掉 = 不做任何分析、
 * 换曲是硬接」是由工厂这一处保证的，不靠调用方自觉。
 */
public class AutomixEnabledGatingTest {

    private static final int RATE = 48_000;

    private static final int CHANNELS = 2;

    // ==================================================================
    // 关掉：一律无缝拼接
    // ==================================================================

    /** 总开关关着时，<b>任何</b>接法都落成窗口 0 的无缝拼接。 */
    @Test
    public void theMasterSwitchOffTurnsEveryTechniqueIntoAGapless() {
        for (int mode : new int[]{SettingsCatalog.AUTOMIX_MODE_GAPLESS,
                SettingsCatalog.AUTOMIX_MODE_CROSSFADE, SettingsCatalog.AUTOMIX_MODE_HARMONIC,
                SettingsCatalog.AUTOMIX_MODE_AI}) {
            Transition t = AutomixSettings.factoryFor(false, mode, 0, 6f, 6f,
                    StemSeparators.OFF, RATE, CHANNELS).create();
            assertEquals("模式 " + mode + " 在总开关关着时必须是无缝拼接", Gapless.INSTANCE, t);
            assertEquals("窗口必须真的是 0（这是「不分析」的唯一判据）", 0L, t.windowFrames(RATE));
        }
    }

    /** 关掉时连人声那层包装都不套：选了 DSP/AI 也不该多读一次文件、多跑一次分离。 */
    @Test
    public void theMasterSwitchOffDoesNotEvenWrapVocals() {
        for (int vocal : new int[]{StemSeparators.DSP, StemSeparators.AI}) {
            Transition t = AutomixSettings.factoryFor(false, SettingsCatalog.AUTOMIX_MODE_CROSSFADE, 0,
                    6f, 6f, vocal, RATE, CHANNELS).create();
            assertFalse("总开关关着时不该套人声包装",
                    t instanceof VocalSeparationTransition);
            assertEquals(Gapless.INSTANCE, t);
        }
    }

    // ==================================================================
    // 打开：照旧按那一档接法
    // ==================================================================

    /** 打开时接法就是用户选的那一档，窗口也照旧（总开关不许顺手改掉别的行为）。 */
    @Test
    public void theMasterSwitchOnKeepsTheSelectedTechnique() {
        Transition crossfade = AutomixSettings.factoryFor(true,
                SettingsCatalog.AUTOMIX_MODE_CROSSFADE, 0, 6f, 6f, StemSeparators.OFF,
                RATE, CHANNELS).create();
        assertTrue("打开时必须是交叉淡化，实际 " + crossfade.getClass().getName(),
                crossfade instanceof Crossfade);
        assertTrue("窗口要真的非 0：" + crossfade.windowFrames(RATE),
                crossfade.windowFrames(RATE) > 0L);

        // 声波重合外面固定套一层「只移伴奏」的人声保护（用户 2026-09-29 的要求：
        // A 与 B 的人声音调都不变），里面照旧是 HarmonicBlend。
        // 「deck 全程零移调（⇒ 位完美不丢、人声不动）」那条由
        // AutomixVocalModeGatingTest 全表扫。
        Transition harmonic = AutomixSettings.factoryFor(true, SettingsCatalog.AUTOMIX_MODE_HARMONIC,
                0, 6f, 6f, StemSeparators.OFF, RATE, CHANNELS).create();
        assertTrue("声波重合应当被「只移伴奏」那一层包住，实际 " + harmonic.getClass().getName(),
                harmonic instanceof dev.t1m3.qplayer.audio.mix.VocalAwarePitchTransition);
        assertTrue("里面照旧是声波重合",
                ((dev.t1m3.qplayer.audio.mix.VocalAwarePitchTransition) harmonic).inner()
                        instanceof dev.t1m3.qplayer.audio.mix.HarmonicBlend);
    }

    /** 过渡工厂的注释里记过的那条纪律：每次 {@code create()} 都要造新实例。 */
    @Test
    public void everyCreateStillReturnsAFreshInstanceWithTheSwitchOn() {
        TransitionFactory factory = AutomixSettings.factoryFor(true,
                SettingsCatalog.AUTOMIX_MODE_CROSSFADE, 0, 6f, 6f, StemSeparators.OFF,
                RATE, CHANNELS);
        assertFalse(factory.create() == factory.create());
    }

    // ==================================================================
    // 「读不到设置」与后加接口的默认值
    // ==================================================================

    /**
     * 读不到设置时是<b>关</b>：不承诺任何会改写采样值、会跑秒级分析的能力。
     * 这与「读不到设置时是『不分离』」是同一条纪律。
     */
    @Test
    public void withoutSettingsTheSwitchIsOff() {
        assertFalse(AutomixSettings.defaults().automixEnabled());
        assertFalse(new AutomixSettings(null).automixEnabled());
        assertEquals("读不到设置时的接法仍是窗口 0 的那一档", Gapless.INSTANCE,
                new AutomixSettings(null).transitionFactory(RATE, CHANNELS).create());
    }

    /**
     * {@code automixEnabled()} 是<b>后加</b>的接口方法，默认 {@code true}：
     * 只实现了取值方法的老实现类（测试夹具、将来别的宿主）不该因为少写一个方法
     * 就静默失去它原来会做的过渡——那正是 {@code EngineSettings.transitionFactory}
     * 的注释里记过的教训。
     */
    @Test
    public void theSeamDefaultsToEnabledForImplementationsThatPredateIt() {
        EngineSettings minimal = new EngineSettings() {
            @Override public int automixMode() { return SettingsCatalog.AUTOMIX_MODE_CROSSFADE; }
            @Override public int automixStyle() { return 0; }
            @Override public float crossfadeSecs() { return 6f; }
            @Override public float harmonicSecs() { return 6f; }
            @Override public boolean bitPerfect() { return false; }
            @Override public boolean rateFollow() { return false; }
        };
        assertTrue("没实现新方法的老实现必须照旧过渡", minimal.automixEnabled());
        assertTrue(minimal.transitionFactory(RATE, CHANNELS).create() instanceof Crossfade);
    }

    /** 关掉时，即使老实现报了交叉淡化，引擎拿到的也是窗口 0 的无缝拼接。 */
    @Test
    public void theSwitchGatesTheDefaultSeamImplementationToo() {
        EngineSettings off = new EngineSettings() {
            @Override public int automixMode() { return SettingsCatalog.AUTOMIX_MODE_CROSSFADE; }
            @Override public int automixStyle() { return 0; }
            @Override public float crossfadeSecs() { return 6f; }
            @Override public float harmonicSecs() { return 6f; }
            @Override public boolean bitPerfect() { return false; }
            @Override public boolean rateFollow() { return false; }
            @Override public boolean automixEnabled() { return false; }
        };
        assertEquals(Gapless.INSTANCE, off.transitionFactory(RATE, CHANNELS).create());
    }
}
