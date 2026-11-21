package dev.t1m3.qplayer.audio.engine;

import dev.t1m3.qplayer.audio.mix.AutoCueTransition;
import dev.t1m3.qplayer.audio.mix.Crossfade;
import dev.t1m3.qplayer.audio.mix.MixStyle;
import dev.t1m3.qplayer.audio.mix.VocalSeparationTransition;
import dev.t1m3.qplayer.audio.mix.Transition;
import dev.t1m3.qplayer.audio.mix.TransitionFactory;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 「自动点位」在接法工厂里的门控：什么时候套这一层、什么时候不套。
 *
 * <p>这一层只改<strong>融合点</strong>（窗口从哪儿开始算、进场曲从哪儿进），
 * 所以它可以套在任何一个接法外面；三个例外必须钉住：
 *
 * <ol>
 *   <li>总开关关着——一切照旧是无缝拼接，自动点位不该凭空发生；</li>
 *   <li>接法是「无缝拼接」——窗口 0，没有可融合的片段，套上去只会白扫两首整曲；</li>
 *   <li>开关没打开——一字不改（默认关，因为整曲扫描要多花时间）。</li>
 * </ol>
 */
public class AutomixAutoCueGatingTest {

    private static final int RATE = 48_000;
    private static final int CHANNELS = 2;

    @Test
    public void disabledByDefaultChangesNothing() {
        Transition plain = create(SettingsCatalog.AUTOMIX_MODE_CROSSFADE, false, true);
        assertFalse("默认不该套自动点位：" + plain.name(), plain.name().contains("自动点位"));
        assertEquals(Crossfade.class, plain.getClass());
    }

    @Test
    public void enabledWrapsTheSameBaseTransition() {
        Transition wrapped = create(SettingsCatalog.AUTOMIX_MODE_CROSSFADE, true, true);
        assertTrue("应当被自动点位包住，实际 " + wrapped.getClass().getName(),
                wrapped instanceof AutoCueTransition);
        assertEquals("里面还是原来那个接法", Crossfade.class,
                ((AutoCueTransition) wrapped).inner().getClass());
        // 包装不碰窗口与位完美判定：那是被包接法的事
        Transition plain = create(SettingsCatalog.AUTOMIX_MODE_CROSSFADE, false, true);
        assertEquals(plain.windowFrames(RATE), wrapped.windowFrames(RATE));
        assertEquals(plain.rewritesSamples(), wrapped.rewritesSamples());
    }

    @Test
    public void gaplessIsNeverWrapped() {
        Transition gapless = create(SettingsCatalog.AUTOMIX_MODE_GAPLESS, true, true);
        assertEquals("窗口 0 就是硬接，不该被包装", 0L, gapless.windowFrames(RATE));
        assertFalse(gapless.name().contains("自动点位"));
    }

    @Test
    public void masterSwitchOffWins() {
        Transition off = create(SettingsCatalog.AUTOMIX_MODE_CROSSFADE, true, false);
        assertEquals("总开关关着 = 无缝拼接", 0L, off.windowFrames(RATE));
        assertFalse(off.name().contains("自动点位"));
    }

    @Test
    public void vocalSeparationSitsOutsideAutoCue() {
        // 两层包装同时在时的嵌套顺序：**自动点位在里面（它定点），去人声在外面（它换素材）**。
        //
        // 这条断言以前是反的，理由是「自动点位算出的进场点会被去人声重新包一次」——
        // 那个理由站不住，而反着来的顺序有一个听得见的后果：
        // 去人声这一层要**预先读好**进场曲的一段素材（渲染路径上一次盘都不许读），
        // 读的位置正是内层计划给的 `skipSecs`；自动点位恰恰会把这个值挪到曲中另一个拍上。
        // 自动点位套在外面时，去人声读到的是**旧的**进场点，于是融合期听到的 B 与 deck
        // 报出来的位置整整差一个进场点——实测进场点 2.8 s 时差 2.87 s，
        // 而过渡一结束 deck 上位，进度就往前跳那么多（用户报的就是这一条）。
        // `DirectorTest#autoCueIntroAudioStaysAlignedWithItsReportedPosition` 从音频那一侧
        // 把这条不变量钉住了：改回旧顺序它会当场失败（实测过，报的就是那 2.877 s）。
        TransitionFactory factory = AutomixSettings.factoryFor(
                true, SettingsCatalog.AUTOMIX_MODE_CROSSFADE, 0, 6f, 6f,
                SettingsCatalog.AUTOMIX_VOCAL_MODE_DSP, true, RATE, CHANNELS);
        Transition outer = factory.create();
        assertTrue("外层应当是去人声（它要预先读素材，必须在内层定完点之后再读），实际 "
                        + outer.getClass().getName(),
                outer instanceof VocalSeparationTransition);
        Transition inner = ((VocalSeparationTransition) outer).inner();
        assertTrue("内层应当是自动点位（负责定点），实际 " + inner.getClass().getName(),
                inner instanceof AutoCueTransition);
        assertTrue("再里面才是真正的接法",
                ((AutoCueTransition) inner).inner() instanceof Crossfade);
    }

    // ==================================================================
    // 准备余量：自动点位比别的档贵一个量级（2026-09-26 手机端实测）
    // ==================================================================

    /**
     * <b>开了自动点位就必须给它足够的准备时间。</b>
     *
     * <p>自动点位要把<strong>两首歌各再解一遍</strong>扫成包络，而默认那 30 秒余量是照
     * 「读两首的尾段/头段」定的。不够的后果不是「点位差一点」，而是<strong>这一次过渡
     * 完全不发生</strong>：{@code Director} 在触发点没有准备结果时<strong>刻意不开始过渡</strong>
     * （它宁可让当前曲自然结束、由硬接路径接手，也不当场去开文件），于是用户听到的是
     * 「开了自动点位反而没有过渡」——手机端真机报告就是这个（进场曲还是带签名的直链时，
     * 那次整曲扫描等于把整首歌再下一遍）。
     */
    @Test
    public void autoCueGetsAMuchBiggerPreparationMargin() {
        long plain = AutomixSettings.prepareMarginSecsFor(
                true, SettingsCatalog.AUTOMIX_MODE_CROSSFADE, 0, false);
        long autoCue = AutomixSettings.prepareMarginSecsFor(
                true, SettingsCatalog.AUTOMIX_MODE_CROSSFADE, 0, true);
        assertEquals("不扫整曲时是默认余量（两首的尾段/头段）",
                dev.t1m3.qplayer.audio.mix.Director.PREPARE_MARGIN_SECS, plain);
        assertEquals("开了自动点位就要按「两首整曲」给余量",
                AutomixSettings.AUTOCUE_PREPARE_MARGIN_SECS, autoCue);
        assertTrue("余量必须真的变大（否则等于没改）：" + plain + " → " + autoCue,
                autoCue >= plain * 2);

        // AI 模式的**自动**档同样要扫整曲（它要把声波重合当候选比一次），
        // 所以余量必须与自动点位同一档——否则准备赶不上触发点，这一次过渡根本不发生
        long aiAuto = AutomixSettings.prepareMarginSecsFor(
                true, SettingsCatalog.AUTOMIX_MODE_AI, MixStyle.AUTO.uiIndex(), false);
        assertEquals("AI 自动档也要按「两首整曲」给余量",
                AutomixSettings.AUTOCUE_PREPARE_MARGIN_SECS, aiAuto);
        // 明确选了某个接法（不是自动）的 AI 档不扫整曲，仍取默认余量
        assertEquals("AI 档明确指定接法时不用扫整曲",
                dev.t1m3.qplayer.audio.mix.Director.PREPARE_MARGIN_SECS,
                AutomixSettings.prepareMarginSecsFor(
                        true, SettingsCatalog.AUTOMIX_MODE_AI,
                        MixStyle.PLAIN_BLEND.uiIndex(), false));

        // 总开关关着：一次分析都不跑，取默认值
        assertEquals("关掉总开关就不该按「要扫整曲」给余量",
                dev.t1m3.qplayer.audio.mix.Director.PREPARE_MARGIN_SECS,
                AutomixSettings.prepareMarginSecsFor(
                        false, SettingsCatalog.AUTOMIX_MODE_CROSSFADE, 0, true));
    }

    /**
     * <b>交叉淡化那一档也允许分析调窗口</b>（用户 2026-09-28 明确要的），
     * 而关掉自动点位时它必须原样用用户设的值。
     */
    /**
     * 拿到里面那层自动点位包装。
     *
     * <p>声波重合（以及 AI 自动档）外面固定套着一层「只移伴奏」的人声保护
     * （见 {@code AutomixVocalModeGatingTest}），窗口调优在里面那层，所以先剥壳。
     */
    private static dev.t1m3.qplayer.audio.mix.AutoCueTransition autoCueOf(Transition t) {
        Transition inner = t;
        if (inner instanceof dev.t1m3.qplayer.audio.mix.VocalAwarePitchTransition) {
            inner = ((dev.t1m3.qplayer.audio.mix.VocalAwarePitchTransition) inner).inner();
        }
        return (dev.t1m3.qplayer.audio.mix.AutoCueTransition) inner;
    }

    @Test
    public void theCrossfadeModeAlsoLetsTheAnalysisTuneItsWindow() {
        dev.t1m3.qplayer.audio.mix.AutoCueTransition crossfade = autoCueOf(
                AutomixSettings.factoryFor(
                        true, SettingsCatalog.AUTOMIX_MODE_CROSSFADE, 0, 6f, 6f,
                        SettingsCatalog.AUTOMIX_VOCAL_MODE_OFF, /* autoCue= */ true,
                        RATE, CHANNELS).create());
        assertTrue("交叉淡化 + 自动点位：分析可以按实际情况调窗口",
                crossfade.tunesWindow());

        dev.t1m3.qplayer.audio.mix.AutoCueTransition harmonic = autoCueOf(
                AutomixSettings.factoryFor(
                        true, SettingsCatalog.AUTOMIX_MODE_HARMONIC, 0, 6f, 6f,
                        SettingsCatalog.AUTOMIX_VOCAL_MODE_OFF, true, RATE, CHANNELS).create());
        assertTrue("声波重合 + 自动点位：同样可以", harmonic.tunesWindow());

        Transition plain = AutomixSettings.factoryFor(
                true, SettingsCatalog.AUTOMIX_MODE_CROSSFADE, 0, 6f, 6f,
                SettingsCatalog.AUTOMIX_VOCAL_MODE_OFF, /* autoCue= */ false,
                RATE, CHANNELS).create();
        assertFalse("关掉自动点位时不套这一层，窗口就是用户设的那个",
                plain instanceof dev.t1m3.qplayer.audio.mix.AutoCueTransition);
    }

    private static Transition create(int mode, boolean autoCue, boolean enabled) {
        return AutomixSettings.factoryFor(enabled, mode, 0, 6f, 6f,
                SettingsCatalog.AUTOMIX_VOCAL_MODE_OFF, autoCue, RATE, CHANNELS).create();
    }
}
