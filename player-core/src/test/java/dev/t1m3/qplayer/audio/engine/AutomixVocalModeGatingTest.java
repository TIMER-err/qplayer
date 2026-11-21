package dev.t1m3.qplayer.audio.engine;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.mix.AiMix;
import dev.t1m3.qplayer.audio.mix.Crossfade;
import dev.t1m3.qplayer.audio.mix.Gapless;
import dev.t1m3.qplayer.audio.mix.HarmonicBlend;
import dev.t1m3.qplayer.audio.mix.MixStyle;
import dev.t1m3.qplayer.audio.mix.PitchPlan;
import dev.t1m3.qplayer.audio.mix.Transition;
import dev.t1m3.qplayer.audio.mix.TransitionFactory;
import dev.t1m3.qplayer.audio.mix.VocalAwarePitchTransition;
import dev.t1m3.qplayer.audio.mix.VocalSeparationTransition;
import dev.t1m3.qplayer.audio.stem.StemSeparators;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * 设置 → 接法那条链上的<b>人声档门控</b>：三档里只有 DSP / AI 会把接法包上一层
 * {@link VocalSeparationTransition}，<b>「不分离」一个包装都不套</b>——流水线与
 * 没有这个功能时完全一样。
 *
 * <p><b>2026-09-29 起多了一条同源的纪律</b>（用户实测「声波重合时 A 的人声变调了」）：
 * 移调改到<b>进场侧</b>（见 {@link #theOutgoingTrackIsNeverAskedToShift}），
 * 于是这一档不再需要专用的人声包装——「只转伴奏」那条路既保不住 A（分离拿不干净 +
 * 这首歌自己跟自己差 s 个半音），整体移进场曲又压根不需要分轨。
 * 三种模式现在都走通用那一层。
 *
 * <h2>为什么单独一个类、而且只测到这一层</h2>
 *
 * <p>{@code AutomixSettings} 要读的是一个真的 {@code SettingsCore}，而它在这台机器的
 * player-core 单测里<b>跑不起来</b>：{@code load(...)} 会走到 {@code Fonts.listFamilies()}，
 * 而 Skija 的原生库只在各宿主的运行时里（player-core 的测试 classpath 上只有
 * {@code skija-shared}），实测直接 {@code UnsatisfiedLinkError}。
 * 所以这里钉的是<b>工厂那一层</b>（模式 + 人声档 → 接法），读设置那一层是一行
 * {@code settings.intOf(KEY)}，由 {@link #theVocalModeDefaultsToOffWithoutSettings}
 * 钉住「读不到设置时不承诺」这一半。
 */
public class AutomixVocalModeGatingTest {

    private static final int RATE = 48_000;

    private static final int CHANNELS = 2;

    // ==================================================================
    // 不分离：一个包装都不套
    // ==================================================================

    /**
     * <b>不分离档下，只有「能挑到声波重合」的两条路会被包一层</b>，其余接法拿到的都是它自己。
     *
     * <p>那一层是「只移伴奏」的人声保护（{@link VocalAwarePitchTransition}）：
     * 声波重合要移两条伴奏才能凑调，而用户没开分离时它<b>自动用 DSP</b>
     * （毫秒级、不依赖外部文件），这件事在包装上留了记号（{@code autoSeparated}）。
     */
    @Test
    public void theOffModeWrapsOnlyThePathsThatCanPickTheHarmonicBlend() {
        assertTrue(create(SettingsCatalog.AUTOMIX_MODE_CROSSFADE, StemSeparators.OFF)
                instanceof Crossfade);
        assertEquals(Gapless.INSTANCE,
                create(SettingsCatalog.AUTOMIX_MODE_GAPLESS, StemSeparators.OFF));

        Transition harmonic = create(SettingsCatalog.AUTOMIX_MODE_HARMONIC, StemSeparators.OFF);
        assertTrue("声波重合必须被「只移伴奏」那一层包住，实际 " + harmonic.getClass().getName(),
                harmonic instanceof VocalAwarePitchTransition);
        VocalAwarePitchTransition h = (VocalAwarePitchTransition) harmonic;
        assertEquals("里面还是声波重合", HarmonicBlend.class, h.inner().getClass());
        assertEquals("用户没开分离 → 引擎自动用 DSP", StemSeparators.DSP, h.vocalMode());
        assertTrue("自动补的档必须留记号（note 与日志要写出来）", h.autoSeparated());

        Transition ai = create(SettingsCatalog.AUTOMIX_MODE_AI, StemSeparators.OFF);
        assertTrue("AI 自动档也要被包住（这条路上原先没有任何人声保护），实际 "
                        + ai.getClass().getName(), ai instanceof VocalAwarePitchTransition);
        assertEquals("里面还是 AI 那一套", AiMix.class,
                ((VocalAwarePitchTransition) ai).inner().getClass());

        Transition plain = AutomixSettings.factoryFor(SettingsCatalog.AUTOMIX_MODE_AI,
                MixStyle.PLAIN_BLEND.uiIndex(), 6f, 6f, StemSeparators.OFF,
                RATE, CHANNELS).create();
        assertEquals("固定接法挑不到声波重合，拿到的就是它自己", AiMix.class, plain.getClass());
    }

    /** 越界的人声档夹回「不分离」（设置文件是用户能手改的 JSON）——不会静默用上某一档。 */
    @Test
    public void anOutOfRangeVocalModeFallsBackToOff() {
        assertTrue(create(SettingsCatalog.AUTOMIX_MODE_CROSSFADE, 99) instanceof Crossfade);
        assertTrue(create(SettingsCatalog.AUTOMIX_MODE_CROSSFADE, -3) instanceof Crossfade);
        // 夹回「不分离」之后，声波重合仍然是「自动 DSP + 只移伴奏」。
        VocalAwarePitchTransition t = (VocalAwarePitchTransition)
                create(SettingsCatalog.AUTOMIX_MODE_HARMONIC, 99);
        assertEquals(StemSeparators.DSP, t.vocalMode());
        assertTrue(t.autoSeparated());
    }

    // ==================================================================
    // DSP / AI：套上包装，档位带进去
    // ==================================================================

    /**
     * DSP / AI 两档都把三种模式包上，而且里面还是原来那个接法。
     *
     * <p>两条路的包法不同，各有理由：
     *
     * <ul>
     *   <li><b>交叉淡化 / AI 固定接法</b>：一层通用的人声包装
     *       （{@link VocalSeparationTransition}）；</li>
     *   <li><b>声波重合</b>：只有「只移伴奏」那一层（{@link VocalAwarePitchTransition}）——
     *       它自己就在准备线程里分轨，再套一层通用包装等于白跑一次分离；</li>
     *   <li><b>AI 自动档</b>：外面「只移伴奏」+ 里面通用那一层。里面那层要留着，
     *       因为 AI 挑的若是别的接法，靠它做去人声与人声后期；外面那层负责
     *       「万一挑中声波重合，绝对不许动到人声」。</li>
     * </ul>
     */
    @Test
    public void theSeparatingModesWrapEveryTransitionAroundItsUsualTransition() {
        for (int mode : new int[]{StemSeparators.DSP, StemSeparators.AI}) {
            assertInner(Crossfade.class, mode, create(SettingsCatalog.AUTOMIX_MODE_CROSSFADE, mode));
            assertInner(AiMix.class, mode, create(SettingsCatalog.AUTOMIX_MODE_AI, mode));

            Transition harmonic = create(SettingsCatalog.AUTOMIX_MODE_HARMONIC, mode);
            assertTrue("声波重合只有「只移伴奏」那一层（它自己分轨，不必再套通用层），实际 "
                            + harmonic.getClass().getName(),
                    harmonic instanceof VocalAwarePitchTransition);
            assertEquals("里面还是声波重合", HarmonicBlend.class,
                    ((VocalAwarePitchTransition) harmonic).inner().getClass());
        }
    }

    /**
     * 声波重合尊重用户选的那一档，而且用户自己开了就不该标成自动补的。
     */
    @Test
    public void theHarmonicStyleHonoursTheChosenVocalMode() {
        for (int mode : new int[]{StemSeparators.DSP, StemSeparators.AI}) {
            Transition t = create(SettingsCatalog.AUTOMIX_MODE_HARMONIC, mode);
            assertTrue("声波重合应当被「只移伴奏」那一层包住，实际 " + t.getClass().getName(),
                    t instanceof VocalAwarePitchTransition);
            VocalAwarePitchTransition wrap = (VocalAwarePitchTransition) t;
            assertEquals("里面还是声波重合", HarmonicBlend.class, wrap.inner().getClass());
            assertEquals("档位要原样带进去", mode, wrap.vocalMode());
            assertFalse("用户自己开了就不该标成自动补的", wrap.autoSeparated());
        }
    }

    /**
     * <b>任何模式 × 风格 × 人声档，deck 都不许被要求移调（两侧都为零）。</b>
     *
     * <p>这一条同时守住用户报过的两件事：
     *
     * <ol>
     *   <li><b>人声不许变调</b>：deck 的移调加在<b>整个混音</b>上，一旦非零，
     *       A 与 B 的人声都跟着走（用户实测「声波重合的时候 A 的人声变调了」，
     *       以及「开了 DSP 也照样」——AI 自动档那条链原先没有保护）；</li>
     *   <li><b>位完美不许因为过渡消失</b>：编排器是按 {@code plan.pitch} 决定要不要
     *       给 deck 挂变调器的，而变调器<b>一旦挂上就不再摘</b>
     *       （{@code Deck.pitch = null} 只发生在 {@code reset()}/{@code seek()}）——
     *       于是整首歌都要过一遍颗粒式变调器：{@code isPassthrough()} 永远为 false、
     *       听感就是失真。用户报的「融合后位完美消失、声音失真」正是这个。
     *       {@code setPitch(0)} 救不了（那只是 ratio=1，变调器还挂着），
     *       所以唯一的解法是<b>一开始就不挂</b>——也就是这一条判据。</li>
     * </ol>
     *
     * <p>为什么扫满整张表：出过一次「换个配置就漏」的事。
     */
    @Test
    public void noConfigurationEverLetsTheDeckShiftPitch() {
        int checked = 0;
        for (int mode : new int[]{SettingsCatalog.AUTOMIX_MODE_GAPLESS,
                SettingsCatalog.AUTOMIX_MODE_CROSSFADE, SettingsCatalog.AUTOMIX_MODE_HARMONIC,
                SettingsCatalog.AUTOMIX_MODE_AI}) {
            for (int vocalMode : new int[]{StemSeparators.OFF, StemSeparators.DSP,
                    StemSeparators.AI}) {
                for (int style = 0; style < 8; style++) {
                    Transition t = AutomixSettings.factoryFor(
                            mode, style, 6f, 6f, vocalMode, RATE, CHANNELS).create();
                    for (float p : new float[]{0.0f, 0.1f, 0.25f, 0.5f, 0.75f, 0.9f, 1.0f}) {
                        PitchPlan plan = t.pitchAt(p);
                        assertEquals("模式 " + mode + " / 风格 " + style + " / 人声档 " + vocalMode
                                        + " 在进度 " + p + " 处要求出场侧移调：" + plan
                                        + "（" + t.getClass().getSimpleName() + "）",
                                0.0f, plan.outro, 1e-9f);
                        assertEquals("模式 " + mode + " / 风格 " + style + " / 人声档 " + vocalMode
                                        + " 在进度 " + p + " 处要求进场侧移调：" + plan
                                        + "（" + t.getClass().getSimpleName() + "）",
                                0.0f, plan.intro, 1e-9f);
                        checked++;
                    }
                }
            }
        }
        assertTrue("这张表应当扫出几百个点，实际只有 " + checked, checked > 500);
        System.out.println("[automix-pitch] 扫了 " + checked + " 个（模式 × 风格 × 人声档 × 进度）"
                + "组合 × 两侧：deck 全程零移调（⇒ 不挂变调器 ⇒ 人声不动、位完美不丢）；"
                + "移调只发生在「只移伴奏」那一层的离线素材上");
    }

    /** 档位必须原样带进包装层（它不认识实现，只按档位去 {@link StemSeparators} 拿）。 */
    @Test
    public void theWrapperRemembersWhichModeItWasBuiltFor() {
        assertEquals(StemSeparators.DSP, ((VocalSeparationTransition)
                create(SettingsCatalog.AUTOMIX_MODE_CROSSFADE, StemSeparators.DSP)).vocalMode());
        assertEquals(StemSeparators.AI, ((VocalSeparationTransition)
                create(SettingsCatalog.AUTOMIX_MODE_CROSSFADE, StemSeparators.AI)).vocalMode());
    }

    /**
     * 无缝拼接<b>永不</b>被套：它的窗口是 0（什么都不渲染），套上去除了多读一次文件、
     * 多跑一次分离之外什么都不会发生。
     */
    @Test
    public void theGaplessJoinIsNeverWrapped() {
        for (int mode : new int[]{StemSeparators.OFF, StemSeparators.DSP, StemSeparators.AI}) {
            assertEquals(Gapless.INSTANCE, create(SettingsCatalog.AUTOMIX_MODE_GAPLESS, mode));
        }
    }

    /** 越界的模式落回无缝拼接，于是同样不会被套上（夹紧只发生在工厂这一层）。 */
    @Test
    public void anOutOfRangeModeFallsBackToGaplessAndIsNotWrapped() {
        assertEquals(Gapless.INSTANCE, create(99, StemSeparators.DSP));
    }

    /**
     * 包装<b>不改窗口</b>——编排器就是拿 {@code windowFrames} 判断触发点的，
     * 改写它等于把切歌点挪走。
     */
    @Test
    public void theWrapperDoesNotMoveTheTransitionWindow() {
        Transition plain = create(SettingsCatalog.AUTOMIX_MODE_CROSSFADE, StemSeparators.OFF);
        Transition wrapped = create(SettingsCatalog.AUTOMIX_MODE_CROSSFADE, StemSeparators.DSP);
        assertEquals(plain.windowFrames(RATE), wrapped.windowFrames(RATE));
        assertEquals(plain.rewritesSamples(), wrapped.rewritesSamples());
        assertTrue("名字要让人看出去了人声：" + wrapped.name(), wrapped.name().contains("去人声"));
    }

    /**
     * 工厂每次都要造<b>新实例</b>（上游的生命周期约定：实例只服务一次过渡，
     * 准备线程上造新的，渲染路径上因此不加锁）。
     */
    @Test
    public void everyCreateReturnsAFreshInstance() {
        TransitionFactory factory = AutomixSettings.factoryFor(
                SettingsCatalog.AUTOMIX_MODE_CROSSFADE, 0, 6f, 6f, StemSeparators.DSP, RATE, CHANNELS);
        Transition a = factory.create();
        Transition b = factory.create();
        assertNotSame(a, b);
        assertNotSame(((VocalSeparationTransition) a).inner(), ((VocalSeparationTransition) b).inner());
    }

    // ==================================================================
    // 读不到设置时
    // ==================================================================

    /** 读不到设置时是「不分离」——不承诺任何需要额外东西的能力。 */
    @Test
    public void theVocalModeDefaultsToOffWithoutSettings() {
        assertEquals(StemSeparators.OFF, AutomixSettings.defaults().automixVocalMode());
        assertEquals(StemSeparators.OFF, new AutomixSettings(null).automixVocalMode());
    }

    /** 只实现取值方法、没实现新方法的宿主实现拿到默认档（向后兼容）。 */
    @Test
    public void aMinimalEngineSettingsGetsNoWrapper() {
        EngineSettings minimal = new EngineSettings() {
            @Override
            public int automixMode() {
                return SettingsCatalog.AUTOMIX_MODE_CROSSFADE;
            }

            @Override
            public int automixStyle() {
                return 0;
            }

            @Override
            public float crossfadeSecs() {
                return 6f;
            }

            @Override
            public float harmonicSecs() {
                return 6f;
            }

            @Override
            public boolean bitPerfect() {
                return false;
            }

            @Override
            public boolean rateFollow() {
                return false;
            }
        };
        assertEquals(StemSeparators.OFF, minimal.automixVocalMode());
        assertFalse("没实现新方法的宿主不该被包上",
                minimal.transitionFactory(RATE, CHANNELS).create()
                        instanceof VocalSeparationTransition);
    }

    /** 三档的常数就是设置里那个下拉的下标（数字只有一个真源）。 */
    @Test
    public void theCatalogConstantsAndTheSeamAgreeOnTheNumbers() {
        assertEquals(StemSeparators.OFF, SettingsCatalog.AUTOMIX_VOCAL_MODE_OFF);
        assertEquals(StemSeparators.DSP, SettingsCatalog.AUTOMIX_VOCAL_MODE_DSP);
        assertEquals(StemSeparators.AI, SettingsCatalog.AUTOMIX_VOCAL_MODE_AI);
        assertEquals(0, StemSeparators.OFF);
        assertEquals(1, StemSeparators.DSP);
        assertEquals(2, StemSeparators.AI);
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 三个模式现在都由<strong>同一层</strong>通用包装包住（人声分离 + 人声后期），
     * 里面还是原来那个接法。
     *
     * <p>曾经的 {@code VocalAwarePitchTransition}（只对伴奏移调）已经不接线了：
     * 声波重合不再转调，它没有要保护的对象。它的判据留在 {@code HarmonicBlendTest}
     * 与 {@code VocalSeparationDspEndToEndTest} 里，将来若要改转进场曲还用得上。
     */
    private static void assertInner(Class<?> expected, int vocalMode, Transition wrapped) {
        Transition outer = wrapped;
        // 能挑到声波重合的那两条路会多一层「只移伴奏」的保护（见上一条测试），剥掉再看。
        if (outer instanceof VocalAwarePitchTransition) {
            outer = ((VocalAwarePitchTransition) outer).inner();
        }
        if (!(outer instanceof VocalSeparationTransition)) {
            throw new AssertionError("档 " + vocalMode + " 应当被通用的人声包装包住，实际 "
                    + wrapped.getClass().getName());
        }
        Transition inner = ((VocalSeparationTransition) outer).inner();
        assertEquals("里面还是原来那个接法（档 " + vocalMode + "）", expected, inner.getClass());
    }

    private static Transition create(int mode, int vocalMode) {
        return AutomixSettings.factoryFor(mode, 0, 6f, 6f, vocalMode, RATE, CHANNELS).create();
    }

    private static AudioBuf filled(int channels, int frames, float value) {
        AudioBuf b = new AudioBuf(ChannelLayout.of(channels), frames);
        b.setFrames(frames);
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < frames; i++) {
                b.channelWriter(c).put(i, value);
            }
        }
        return b;
    }
}
