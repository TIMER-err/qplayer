package dev.t1m3.qplayer.settings;

import dev.t1m3.qplayer.audio.stem.StemSeparators;

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
 * consecutive rows sharing a {@code group} share one card. Related preferences share a Miuix section; the UI always renders sections
 * in one column, with platform-specific rows filtered before layout.
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
    /** 音频：HiFi（位完美 / 采样率跟随）与自动混音。 */
    public static final String AUDIO = "audio";
    public static final String LYRIC = "lyric";
    /** Desktop lyrics gets its own tab rather than sitting under 歌词: it is a
     *  separate surface with its own typography and colours, and every row in it
     *  is desktop-only. A category with no rows on the running host is dropped —
     *  see {@link SettingsCore#categories()} — so Android never shows this. */
    public static final String DESKTOP_LYRIC = "desktopLyric";
    public static final String LOCAL = "local";
    public static final String PLUGINS = "plugins";
    public static final String ABOUT = "about";

    public static final List<String> CATEGORIES = Collections.unmodifiableList(
            Arrays.asList(APPEARANCE, PLAYBACK, AUDIO, LYRIC, DESKTOP_LYRIC, LOCAL, PLUGINS, ABOUT));

    // ---- 音频：HiFi 与自动混音 ------------------------------------------------

    /** HiFi L1：位完美直通。开启后旁路重采样与一切电平处理，设备跟随源采样率。
     *  注意位完美是<strong>逐段</strong>生效的——只有切歌过渡那几秒让步，
     *  歌曲主体照常直通（ADR-003 §2.2）。 */
    public static final String HIFI_BIT_PERFECT_KEY = "hifiBitPerfect";

    /** HiFi L3：换曲时把输出流切到新源的采样率。默认关是「无缝优先」的选择——
     *  跨采样率切设备有静默风险，只有实测能证明切换耗时被淡化完全掩盖时才该打开。 */
    public static final String HIFI_RATE_FOLLOW_KEY = "hifiRateFollow";

    /**
     * 自动混音的<strong>总开关</strong>。默认<strong>关</strong>。
     *
     * <p>为什么必须有一个总开关，而不是「模式默认选无缝拼接就等于关」：
     * 那是两件不同的事，混在一起必然撒谎——用户看到「自动混音 = 交叉淡化」
     * 会以为换曲时一定在交叠，而实际能不能交叠还取决于「有没有提前拿到下一首」。
     * 有了总开关，界面上的话可以只说一件确定的事：
     * <strong>关 = 不做过渡（不交叠、一次分析都不做）；开 = 才读下面那一档接法</strong>。
     *
     * <p>关闭时引擎那边的窗口是 0（{@link dev.t1m3.qplayer.audio.engine.AutomixSettings}
     * 会把模式一律落成无缝拼接），于是 {@code Director} 既不
     * {@code prepare}（不烧 CPU）也不进过渡。换曲点本身仍由引擎在<strong>同一路输出流里</strong>
     * 拼上去（无缝，见 ADR-003 §2）——那是播放器的地基，不该被这个开关顺手拿走。
     */
    /** ③ 分层 + 加权取模选择（Apple weighted modulo）：默认关，开了落点会变。 */
    public static final String AUTOMIX_TIERED_SELECTION_KEY = "automixTieredSelection";

    public static final String AUTOMIX_ENABLED_KEY = "automixEnabled";

    /** 自动混音的模式。它<strong>不是一个「播放模式」</strong>，而是每次切歌时那一次过渡
     *  怎么做：无缝拼接（什么都不做）/ 交叉淡化 / 声波重合 / AI 模式。
     *  只有在 {@link #AUTOMIX_ENABLED_KEY} 打开时才会被读。 */
    public static final String AUTOMIX_MODE_KEY = "automixMode";
    /** 无缝拼接：只决定在哪一帧拼接，不动采样值，是唯一与位完美兼容的换曲方式。 */
    public static final int AUTOMIX_MODE_GAPLESS = 0;
    public static final int AUTOMIX_MODE_CROSSFADE = 1;
    public static final int AUTOMIX_MODE_HARMONIC = 2;
    public static final int AUTOMIX_MODE_AI = 3;

    /** AI 模式里的接法。存的是 {@link dev.t1m3.qplayer.audio.mix.MixStyle#UI_ORDER}
     *  的下标（0 = 自动），<strong>不是</strong>枚举的 ordinal。 */
    public static final String AUTOMIX_STYLE_KEY = "automixStyle";

    /** 交叉淡化模式的窗口时长（秒）。0 等价于硬接。 */
    /**
     * 自动点位：融合点由「重扫两首整曲」决定，而不是固定在当前曲的最后 N 秒。
     *
     * <p>它解开的限制是：固定窗口把换曲时机锁死在曲尾那一段里，而一首歌最接得上的地方
     * 往往不在曲尾。打开后由 {@code AutoCue} 在全曲里挑点（有硬边界：
     * 不早于 20 秒/20%，不晚于「时长 − 交叠 − 2 秒」，进场不超过 20 秒/25%），
     * 挑不出来就<strong>如实退回固定点位</strong>。
     */
    public static final String AUTOMIX_AUTO_CUE_KEY = "automixAutoCue";

    // ---- P4：控制室（手动点位覆盖）----
    /** 手动点位开关：打开之后下面两个数覆盖自动点位/固定点位算出来的融合点与切入点。 */
    public static final String AUTOMIX_MANUAL_CUE_KEY = "automixManualCue";
    /** 出场曲从第几秒开始交叠（秒；0 = 不覆盖这一侧）。 */
    public static final String AUTOMIX_MANUAL_OUTRO_SECS_KEY = "automixManualOutroSecs";
    /** 进场曲从第几秒进（秒；0 = 不覆盖这一侧）。 */
    public static final String AUTOMIX_MANUAL_INTRO_SECS_KEY = "automixManualIntroSecs";

    /**
     * 音频工作室的处理链（三台机架的参数）。
     *
     * <p>存的是**一个 JSON 字符串**而不是几十个设置键：一条链有「三台机架 × 十几段 ×
     * 每段五个参数」那个量级，摊成键的话每加一个段类型都要迁一次本文件，而且泵线程会看到
     * 半套参数。JSON 的读写与容错都在 {@code audio.studio.StudioJson} 里，
     * 界面那边只认这一个键。
     *
     * <p>空串 = 全关（直通）。这一条是<strong>桌面临时先用</strong>的入口（预设文本），
     * 完整的曲线编辑器在「音频工作室」页（见 docs/audio-studio.md 的 P7）。
     */
    public static final String AUDIO_STUDIO_CHAIN_KEY = "audioStudioChain";

    /** 「播放设备」下拉框：存的是列表下标（0 = 跟随系统默认）。 */
    public static final String OUTPUT_DEVICE_KEY = "outputDevice";

    /**
     * 选中的设备**名字**（隐藏项）。
     *
     * <p>为什么下标之外还要存名字：设备枚举顺序会变（插拔、驱动重排），
     * 只存下标的话下一次启动可能指着另一台设备。名字是这张表的事实来源，
     * 下标只是给下拉框显示用的。
     */
    public static final String OUTPUT_DEVICE_NAME_KEY = "outputDeviceName";

    /** P1 的控制面：内置曲线、作用机架、前级增益、等响度补偿、自定义 JSON。 */
    // 桌面歌词浮窗（2.1.1 的安卓壳读这些键；这里先给常量，值由宿主写入 ✓）
    public static final String OVERLAY_LYRIC_KEY = "overlayLyric";
    public static final String OVERLAY_LYRIC_FONT_SIZE_KEY = "overlayLyricFontSize";
    public static final String OVERLAY_LYRIC_HEIGHT_KEY = "overlayLyricHeight";
    public static final String OVERLAY_LYRIC_WIDTH_KEY = "overlayLyricWidth";
    public static final String OVERLAY_LYRIC_LINE_SPACING_KEY = "overlayLyricLineSpacing";
    public static final String OVERLAY_LYRIC_LOCKED_KEY = "overlayLyricLocked";
    public static final String OVERLAY_LYRIC_OUTLINE_KEY = "overlayLyricOutline";
    public static final String OVERLAY_LYRIC_OUTLINE_COLOR_KEY = "overlayLyricOutlineColor";
    public static final String OVERLAY_LYRIC_SUNG_COLOR_KEY = "overlayLyricSungColor";
    public static final String OVERLAY_LYRIC_UNSUNG_COLOR_KEY = "overlayLyricUnsungColor";
    public static final String OVERLAY_LYRIC_BG_OPACITY_KEY = "overlayLyricBgOpacity";

    /** 界面缩放（2.1.1 新增；桌面宿主按它调 scale）。 */
    /** 文字对比度的"跟随屏幕"档（桌面 Windows 专有；2.1.1 宿主会引用该常量）。 */
    public static final int MODE_CONTRAST = 3;

    /** 歌词页封面样式（2.1.1 的键名就是 lyricCoverStyle ✓ 之前我写成 coverStyle ✗） */
    public static final String COVER_STYLE_KEY = "lyricCoverStyle";
    public static final int COVER_STYLE_ORIGINAL = 0;
    public static final int COVER_STYLE_VINYL = 1;

    public static final String UI_SCALE_KEY = "uiScale";

    public static final int UI_SCALE_MIN = 70;
    public static final int UI_SCALE_MAX = 160;
    public static final int UI_SCALE_DEFAULT = 100;

    public static final String STUDIO_PRESET_KEY = "studioPreset";

    /** 0 = 主总线，1 = 出场曲，2 = 进场曲，3 = 三台都上。 */
    public static final String STUDIO_RACK_KEY = "studioRackTarget";

    public static final String STUDIO_GAIN_KEY = "studioGainDb";

    public static final String STUDIO_LOUDNESS_KEY = "studioLoudness";

    public static final String STUDIO_CUSTOM_KEY = "studioCustomChain";

    // ---- P2：音高（改调）----
    /** 音高开关：关着时那三个滑杆不读（也就没有「开着但全是 0」这种中间态）。 */
    public static final String STUDIO_PITCH_KEY = "studioPitchOn";
    /** 移调：半音（±12）。与音分相加才是最终音高，所以范围是 ±13 半音。 */
    public static final String STUDIO_PITCH_SEMITONE_KEY = "studioPitchSemitone";
    /** 音分微调（±100）：分轨之间差 8 音分这种事只有音分看得见。 */
    public static final String STUDIO_PITCH_CENTS_KEY = "studioPitchCents";
    /** 干湿（0..100%）：0 = 原样，100 = 全湿。 */
    public static final String STUDIO_PITCH_MIX_KEY = "studioPitchMix";

    // ---- P2b/P3：人声后期（修跑调 / 去混响）----
    //
    // **它只作用在分离出来的人声轨上**（融合期的分轨，见 VocalSeparationTransition）。
    // 整条混音上做这两件事在数学上没有意义：修跑调要「只有一个音高」，去混响要
    // 「尾巴只来自人声」——复音素材两条都不成立。所以这一组在界面上说清「只在融合期
    // 的人声轨上生效」，而不是假装它能修整首歌。
    // ---- P5/P6/P7：动态 / 立体声 / 染色 ----
    /** 压缩器开关。 */
    public static final String STUDIO_COMP_KEY = "studioCompOn";
    /** 压缩阈值（dBFS，−60..0）。 */
    public static final String STUDIO_COMP_THRESHOLD_KEY = "studioCompThresholdDb";
    /** 压缩比（1..20）。 */
    public static final String STUDIO_COMP_RATIO_KEY = "studioCompRatio";
    /** 启动（毫秒）。 */
    public static final String STUDIO_COMP_ATTACK_KEY = "studioCompAttackMs";
    /** 释放（毫秒）。 */
    public static final String STUDIO_COMP_RELEASE_KEY = "studioCompReleaseMs";
    /** 增益衰减上限（dB，0..24）。 */
    public static final String STUDIO_COMP_MAKEUP_KEY = "studioCompMakeupDb";
    /** 限制器开关。 */
    public static final String STUDIO_LIMITER_KEY = "studioLimiterOn";
    /** 限制器天花板（dBFS，−12..0）。 */
    public static final String STUDIO_LIMITER_CEILING_KEY = "studioLimiterCeilingDb";
    /** 门开关。 */
    public static final String STUDIO_GATE_KEY = "studioGateOn";
    /** 门限（dBFS）。 */
    public static final String STUDIO_GATE_THRESHOLD_KEY = "studioGateThresholdDb";
    /** 立体声开关。 */
    public static final String STUDIO_STEREO_KEY = "studioStereoOn";
    /** 宽度（0..200%）。 */
    public static final String STUDIO_STEREO_WIDTH_KEY = "studioStereoWidth";
    /** 低音单声道分频点（0..500 Hz，0 = 关）。 */
    public static final String STUDIO_STEREO_BASS_MONO_KEY = "studioStereoBassMonoHz";
    /** 耳机交叉馈送（0..100%）。 */
    public static final String STUDIO_STEREO_CROSSFEED_KEY = "studioStereoCrossfeed";
    /** 染色开关。 */
    public static final String STUDIO_SATURATION_KEY = "studioSaturationOn";
    /** 驱动（dB）。 */
    public static final String STUDIO_SATURATION_DRIVE_KEY = "studioSaturationDriveDb";
    /** 曲线（{@code SaturationParams.Curve} 的下标）。 */
    public static final String STUDIO_SATURATION_CURVE_KEY = "studioSaturationCurve";
    /** 过采样（1/2/4）。 */
    public static final String STUDIO_SATURATION_OVERSAMPLE_KEY = "studioSaturationOversample";

    /** 动作行：打开「音频工作室」页面（工作站排版：频谱 + 拖拽调曲线）。 */
    public static final String OPEN_STUDIO_ACTION = "openAudioStudio";

    /** 修跑调开关。 */
    public static final String VOCAL_AUTOTUNE_KEY = "vocalAutotune";
    /** 音阶（{@code ScaleQuantizer.Scale} 的下标）。 */
    public static final String VOCAL_AUTOTUNE_SCALE_KEY = "vocalAutotuneScale";
    /** 强度（0..100%）。 */
    public static final String VOCAL_AUTOTUNE_STRENGTH_KEY = "vocalAutotuneStrength";
    /** 回正速度（0..200 ms）。 */
    public static final String VOCAL_AUTOTUNE_RETUNE_KEY = "vocalAutotuneRetuneMs";
    /** 保留颤音。 */
    public static final String VOCAL_AUTOTUNE_VIBRATO_KEY = "vocalAutotuneVibrato";
    /** 去混响强度（0..100%）。 */
    public static final String VOCAL_DEREVERB_KEY = "vocalDereverb";
    /** 去混响的噪声底（−30..−6 dB）。 */
    public static final String VOCAL_DEREVERB_FLOOR_KEY = "vocalDereverbFloorDb";

    public static final String AUTOMIX_CROSSFADE_SECS_KEY = "automixCrossfadeSecs";

    /** 声波重合模式的窗口时长（秒）。 */
    public static final String AUTOMIX_HARMONIC_SECS_KEY = "automixHarmonicSecs";

    /**
     * 融合阶段的人声怎么处理。<strong>三选一，但 AI 那一档只在桌面出现</strong>
     * （移动端不安装 {@code StemModels.Opener}，那份推理在手机上根本跑不起来）。
     *
     * <p>取值直接引用 {@link StemSeparators}（不另抄一份数字），所以「界面上选中的下标」
     * 与「实际用的档」不可能分叉。这个键在<b>两端各有一行、形状不同</b>
     * （桌面三选一、移动端两选一），见 {@code specs()} 里那两行的注释。
     */
    /**
     * <b>融合期那几层处理的调参串</b>（P2/P4/P5，2026-10-02）。
     *
     * <p>格式就是 {@link dev.t1m3.qplayer.audio.mix.MixTuning#parse(String)} 的那一套：
     * {@code 键=值,键=值}，例如 {@code width=0.5,low_duck=0,level_match=0}。
     * 一个键覆盖全部 24 个听感旋钮（将来加旋钮也不用再动设置面），空串 = 全默认。
     *
     * <p>哪几项与「糊」直接相关：
     *
     * <ul>
     *   <li>{@code level_match}（P4 逐帧响度匹配，默认 1 = 全开）：进场曲的响度贴着出场曲，
     *       消掉「一首把另一首埋了」；</li>
     *   <li>{@code low_duck}（P5 低频让位，默认 1 = 全开）：两侧低频加起来不超过较响的一首，
     *       消掉两首贝斯同相叠加的 +6 dB。</li>
     * </ul>
     *
     * <p><b>三个最常改的键现在有正经控件</b>（{@link #AUTOMIX_BLEND_WIDTH_KEY}、
     * {@link #AUTOMIX_TEMPO_STRETCH_KEY}、{@link #AUTOMIX_WINDOW_SCALE_KEY}）。
     * 用户 2026-10-02 的反馈是「旋钮在哪我都找不到」——一个只认文本的键对听众来说等于
     * 不存在，所以最常动的三项搬成了滑杆/开关。<b>控件是这三个键的正主</b>：
     * 调参串里再写它们不生效，并在日志里报一行（不静默忽略，见
     * {@code AutomixSettings.composeSpec}）。
     *
     * <p>写错的键或越界的值**会退回全默认并在日志里报一行**（{@code MixTuning.parse}
     * 本身是「认不出就报错」的，宿主这一层不能因此崩掉一次过渡）。
     */
    public static final String AUTOMIX_MIX_TUNING_KEY = "automixMixTuning";

    /**
     * 空间展宽（P2）：重叠期把出场曲推向两侧，让两首歌不在同一个正中间打架
     * （{@code MixTuning.spaceWidth}）。存的是<b>百分比整数</b>，默认 {@value #DEFAULT_BLEND_WIDTH_PCT}%。
     *
     * <p>为什么是滑杆而不是文本键：这是用户唯一亲手挑过默认值的旋钮（听过之后定了 0.5），
     * 「两首歌同时响会不会糊」的第一嫌疑人，必须能一边听一边拖。
     */
    public static final String AUTOMIX_BLEND_WIDTH_KEY = "automixBlendWidth";

    public static final int DEFAULT_BLEND_WIDTH_PCT = 50;

    /**
     * 过渡期变速（{@code MixTuning.tempoStretch}，默认开）：把出场曲那段窗口的素材
     * 变速到进场曲的拍长，鼓点不滑（WSOLA，只在这几秒里做，不改变原曲）。
     *
     * <p>关掉它就回到「两首歌各按自己的拍走」，用户要 A/B 这一项时得有个开关——
     * 这是本项改动（1002j）里唯一听感风险点，所以默认在界面上可见。
     */
    public static final String AUTOMIX_TEMPO_STRETCH_KEY = "automixTempoStretch";

    /**
     * 重叠窗口长度倍数（{@code MixTuning.windowScale}，默认 100% = 约 6.5 秒那一档）。
     *
     * <p>窗口越长越有地方铺开过渡、也越容易听出「两首歌叠在一起」；这一项在
     * {@code AutomixSmoothnessReferenceTest} 里扫过，用户要自己听就给他滑杆。
     */
    public static final String AUTOMIX_WINDOW_SCALE_KEY = "automixWindowScale";

    /**
     * 窗口缩放的**界面默认值**（百分比）。
     *
     * <p>**保持 100**（2026-10-07）：用户的听感是"窗口可以长一点"，但**默认值不能动** ——
     * 两条判据从两个方向守着这件事：
     * <ul>
     *   <li>{@code MixTuningTest.defaultsMatchTheShippedConstants}：缩放默认必须是 1；</li>
     *   <li>{@code AutomixTuningSettingsTest.theDefaultControlReadingsAreTheDefaultTuning}：
     *       控件走默认时必须**逐位等于**全默认。</li>
     * </ul>
     * 也就是说：想更长就**把这个滑块拉大**（50~200%，随设置持久化），
     * 而不是改这里 —— 改这里等于"悄悄改规格"，两条判据都不答应。
     */
    public static final int DEFAULT_WINDOW_SCALE_PCT = 100;

    /**
     * <b>过渡诊断日志</b>（排查用，默认关）：打开后 {@link dev.t1m3.qplayer.util.Logger#debug}
     * 那一路过程细节（拍栅格来源、窗口候选排名、去人声曲线形状、准备余量调整…）会原样输出。
     *
     * <p>为什么默认关：用户 2026-10-03 的原话是「现在太繁琐了」——一次换曲原本十几行，
     * 正常听歌时全是噪音。关掉不等于丢掉证据，只是<b>不在默认路径上花这笔钱</b>：
     * 真正的问题（扫描失败、分离失败、交接不连续）仍然是 WARN，一条都不会少。
     */
    public static final String AUTOMIX_DIAGNOSTIC_LOG_KEY = "automixDiagnosticLog";

    public static final String AUTOMIX_VOCAL_MODE_KEY = "automixVocalMode";

    /** 不分离（默认）：<strong>不套包装接法</strong>，照常过渡——与没有这个功能时一模一样。 */
    public static final int AUTOMIX_VOCAL_MODE_OFF = StemSeparators.OFF;

    /** DSP：player-core 自己那份纯 Java 中心声道（人声）抑制——不需要下载、不占常驻内存。 */
    public static final int AUTOMIX_VOCAL_MODE_DSP = StemSeparators.DSP;

    /** AI（模型）：htdemucs 分轨，权重按需下载。<strong>仅限桌面</strong>。
     *  模型不在磁盘上时过渡照常但<b>不去人声</b>，<b>不会</b>拿 DSP 顶替（用户选的是这一档）。 */
    public static final int AUTOMIX_VOCAL_MODE_AI = StemSeparators.AI;

    /** 模型目录：下载来的权重放在哪里。空 = {@code <数据目录>/models}。
     *  <strong>不是缓存</strong>（见 AppDirs.modelsDir 的注释）：几百 MB 的用户数据，
     *  放在会被清理的目录里意味着一次清理就要重下 108MB。可以指到别的盘上。 */
    public static final String MODELS_DIR_KEY = "modelsFolder";

    /** 「管理模型」入口的行 id。模型不随成品分发，由用户按需下载，
     *  所以设置里必须有一个看得见、点得开的入口，而不是一个「你应该自己去下载」的说明。 */
    public static final String MANAGE_MODELS_ACTION = "manageModels";

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
    /** 动态背景（网格形变）：网易云那套「三层旋转封面 + 网格形变」，见 FluidBackground.STYLE_DYNAMIC_MESH。 */
    public static final int BG_STYLE_DYNAMIC_MESH = 3;

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

    /** Desktop-lyric click-through. Shared with the floating window's own lock
     *  button, which writes the same key — the two stay in sync in both directions. */
    public static final String DESKTOP_LYRIC_LOCKED_KEY = "desktopLyricMousePassthrough";
    /** Explicit desktop-lyric colours, "#rrggbb" or empty. Empty keeps the Monet
     *  role the window has always used (primary for sung, onSurfaceVariant/secondary
     *  for the rest), so the default look is unchanged. */
    public static final String DESKTOP_LYRIC_SUNG_COLOR_KEY = "desktopLyricSungColor";
    public static final String DESKTOP_LYRIC_UNSUNG_COLOR_KEY = "desktopLyricUnsungColor";

    /** Daily picks above the recommendation grid instead of below it. */
    public static final String HOME_DAILY_FIRST_KEY = "homeDailyFirst";
    /** How many plain recommended playlists the home page asks a source for. */
    public static final String HOME_PLAYLIST_LIMIT_KEY = "homePlaylistLimit";

    /** Android: hold the screen awake while the lyric (now-playing) page is
     *  open, the one surface people leave up for a whole song. */
    public static final String KEEP_SCREEN_ON_KEY = "keepScreenOnPlayerPage";

    private SettingsCatalog() {}

    public static List<SettingSpec> specs() {
        List<SettingSpec> out = new ArrayList<>();

        // ---- 外观 -----------------------------------------------------------
        out.add(SettingSpec.dropdown(LANGUAGE_KEY, APPEARANCE, "settings.language.title",
                        LANGUAGE_SYSTEM, "settings.language.system",
                        "settings.language.zhCN", "settings.language.enUS")
                .group("interface")
                .build());

        out.add(SettingSpec.segmented("darkMode", APPEARANCE, "settings.darkMode.title", MODE_SYSTEM,
                        "settings.darkMode.system", "settings.darkMode.light",
                        "settings.darkMode.dark")
                .group("interface")
                .build());
        out.add(SettingSpec.dropdown(PAGE_TRANSITION_KEY, APPEARANCE,
                        "settings.pageTransition.title", PAGE_TRANSITION_ZOOM,
                        "settings.pageTransition.zoom", "settings.pageTransition.fade",
                        "settings.pageTransition.slideH", "settings.pageTransition.slideV",
                        "settings.pageTransition.none")
                .desc("settings.pageTransition.desc")
                .group("interface")
                .build());
        out.add(SettingSpec.toggle("reduceTransparency", APPEARANCE,
                        "settings.reduceTransparency.title", false)
                .desc("settings.reduceTransparency.desc")
                .group("accessibility")
                .build());
        out.add(SettingSpec.toggle("increaseContrast", APPEARANCE,
                        "settings.increaseContrast.title", false)
                .desc("settings.increaseContrast.desc")
                .group("accessibility")
                .build());
        out.add(SettingSpec.toggle("monet", APPEARANCE, "settings.monet.title", true)
                .desc("settings.monet.desc")
                .accessory("swatch")
                .group("interface")
                .build());
        out.add(SettingSpec.action("pickFont", APPEARANCE, "settings.font.title",
                        "settings.font.button")
                .provider("fontName")
                .desc("settings.font.desc")
                .group("interface")
                .build());
        out.add(SettingSpec.radio("graphicsBackend", APPEARANCE, "settings.graphicsBackend.title", 0,
                        "settings.graphicsBackend.opengl", "settings.graphicsBackend.vulkan")
                .desc("settings.graphicsBackend.desc")
                .onlyOn(DESKTOP)
                .group("window")
                .build());
        out.add(SettingSpec.toggle("windowDecorated", APPEARANCE,
                        "settings.windowDecorated.title", false)
                .desc("settings.windowDecorated.desc")
                .onlyOn(DESKTOP)
                .group("window")
                .build());
        out.add(SettingSpec.toggle("showLocalTab", APPEARANCE, "settings.showLocalTab.title", true)
                .desc("settings.showLocalTab.desc")
                .group("home")
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
                .group("audio")
                .build());
        // 「淡入淡出」那一行（{@code fade}）已经删掉：它原本是"切歌/曲终时把音量渐变过去，
        // 而不是直接切断"，那是引擎还没有接缝处理时（自动混音/无缝换曲都不存在）在**客户端**
        // 拿音量去补接缝的做法。现在接缝由引擎在输出流里做（自动混音那次交叉淡化、以及
        // 曲终的无缝换曲），而客户端那条音量渐变会把**整条混音**（含正在进场的下一首）
        // 一起拉到 0——与过渡直接冲突。它的默认值本来就是 {@code false}，所以删掉不改行为。
        out.add(SettingSpec.toggle("highQuality", PLAYBACK, "settings.highQuality.title", true)
                .desc("settings.highQuality.desc")
                .group("audio")
                .build());
        // 播放页常亮: the value says how the PAGE behaves while a track plays, and
        // the lyric page toggles it from its own top row (LyricOverlay's
        // keepAwakeBtn) -- but it also keeps a row here, because people look for
        // it under 设置 → 播放 first. Android only: a desktop window has no
        // display timeout to defeat, so settings.has() is false there and the
        // page button never appears.
        out.add(SettingSpec.toggle(KEEP_SCREEN_ON_KEY, PLAYBACK,
                        "settings.keepScreenOn.title", true)
                .desc("settings.keepScreenOn.desc")
                .onlyOn(ANDROID)
                .group("screen")
                .build());

        // ---- 播放设备（只有桌面端有得选）-------------------------------------
        // 设备名是**动态**的，所以这张表每次构建时现取；没有设备（安卓、或枚举失败）
        // 就整行不出现——没有可选的东西，比给一个只有一项的下拉框诚实。
        java.util.List<String> outputNames =
                dev.t1m3.qplayer.audio.output.OutputDevices.listOutputDeviceNames();
        if (!outputNames.isEmpty()) {
            java.util.List<String> labels = new java.util.ArrayList<String>();
            labels.add(dev.t1m3.qplayer.i18n.I18n.tr("settings.outputDevice.systemDefault"));
            labels.addAll(outputNames);
            String chosen = dev.t1m3.qplayer.audio.output.OutputDevices.selectedName();
            int def = 0;
            if (chosen != null) {
                int at = outputNames.indexOf(chosen);
                if (at >= 0) {
                    def = at + 1;
                }
            }
            out.add(SettingSpec.dropdown(OUTPUT_DEVICE_KEY, PLAYBACK,
                            "settings.outputDevice.title", def,
                            labels.toArray(new String[0]))
                    .desc("settings.outputDevice.desc")
                    .group("audio")
                    .onlyOn(DESKTOP)
                    .build());
            out.add(SettingSpec.hidden(OUTPUT_DEVICE_NAME_KEY, SettingSpec.TEXT, "")
                    .onlyOn(DESKTOP)
                    .build());
        }

        // ---- 音频：HiFi ------------------------------------------------------
        // HiFi 拆成几层<strong>互不依赖</strong>的能力（ADR-003 §2）：用户不该因为一层
        // 做不到就连带失去另一层。这里落 L1（位完美路径）与 L3（随曲切采样率）；
        // L2 的设备能力矩阵由输出层探测，只读不设——设备不支持时相关项在界面上置灰。
        out.add(SettingSpec.toggle(HIFI_BIT_PERFECT_KEY, AUDIO,
                        "settings.hifiBitPerfect.title", false)
                .desc("settings.hifiBitPerfect.desc")
                .onlyOn(DESKTOP)
                .group("hifi")
                .build());
        out.add(SettingSpec.toggle(HIFI_RATE_FOLLOW_KEY, AUDIO,
                        "settings.hifiRateFollow.title", false)
                .desc("settings.hifiRateFollow.desc")
                .onlyOn(DESKTOP)
                .group("hifi")
                .build());

        // ---- 音频：自动混音 ---------------------------------------------------
        // 自动混音不是「播放模式」，而是每次切歌时那一次过渡怎么做。
        // 所以它管的是接缝，与歌曲主体是否位完美是两件事（ADR-003 §2.2）。
        //
        // 先总开关、再接法：总开关关着的时候，下面每一行都藏着
        // （dependsOn 的语义见 SettingGroup.qml：值不为 true 就整行不可见）。
        // 藏起来而不是留着可点，是为了让「现在到底会不会做过渡」在界面上一眼可判——
        // 留着可点的接法行会让人以为「选好了就在跑」，而实际一次过渡都不会发生。
        out.add(SettingSpec.toggle(AUTOMIX_ENABLED_KEY, AUDIO,
                        "settings.automixEnabled.title", false)
                // 活文案：关着说「硬接、不分析」，开着把当前那一档接法原样说出来
                // （选的是「无缝拼接」时它也是硬接，这一句必须说出口，
                //  见 SettingsCore.automixStatusNotice）。
                .provider("automixStatus")
                .desc("settings.automixEnabled.desc")
                .group("automix")
                .build());
        out.add(SettingSpec.dropdown(AUTOMIX_MODE_KEY, AUDIO, "settings.automixMode.title",
                        AUTOMIX_MODE_AI,
                        "settings.automixMode.gapless", "settings.automixMode.crossfade",
                        "settings.automixMode.harmonic", "settings.automixMode.ai")
                // 说明文字是<strong>活的</strong>：选了 AI 模式而拍点模型不在磁盘上时，
                // 这一行必须如实说「需要下载、现在退回硬算」，而不是让人以为在用模型。
                // （见 SettingsCore.registerModelProviders 的 automixModelNotice。）
                .provider("automixModelNotice")
                .dependsOn(AUTOMIX_ENABLED_KEY)
                .group("automix")
                .build());
        out.add(SettingSpec.radio(COVER_STYLE_KEY, LYRIC, "settings.lyricCoverStyle.title",
                        COVER_STYLE_ORIGINAL,
                        "settings.lyricCoverStyle.original",
                        "settings.lyricCoverStyle.vinyl")
                .desc("settings.lyricCoverStyle.desc")
                .group("lyricDisplay")
                .build());

        out.add(SettingSpec.segmented("lyricProgressStyle", LYRIC,
                        "settings.lyricProgressStyle.title", 1,
                        "settings.lyricProgressStyle.wave",
                        "settings.lyricProgressStyle.line")
                .group("lyricDisplay")
                .build());

        out.add(SettingSpec.slider(AUTOMIX_HARMONIC_SECS_KEY, AUDIO,
                        "settings.automixHarmonic.title", 6, 0, 20, 1)
                .unit(" s").desc("settings.automixHarmonic.desc")
                .dependsOn(AUTOMIX_ENABLED_KEY)
                .group("automix")
                .build());
        out.add(SettingSpec.dropdown(AUTOMIX_VOCAL_MODE_KEY, AUDIO, "settings.automixVocalMode.title",
                        AUTOMIX_VOCAL_MODE_OFF,
                        "settings.automixVocalMode.off", "settings.automixVocalMode.dsp",
                        "settings.automixVocalMode.ai")
                .provider("automixVocalNotice")
                .desc("settings.automixVocalMode.desc")
                .onlyOn(DESKTOP)
                .dependsOn(AUTOMIX_ENABLED_KEY)
                .group("automix")
                .build());
        out.add(SettingSpec.chips(AUTOMIX_STYLE_KEY, AUDIO, "settings.automixStyle.title",
                        6,                                   // 下标：见 MixStyle.UI_ORDER
                        "settings.automixStyle.auto", "settings.automixStyle.plainBlend",
                        "settings.automixStyle.beatCut", "settings.automixStyle.bassSwap",
                        "settings.automixStyle.tailRide", "settings.automixStyle.showMode",
                        "settings.automixStyle.learnedEq",
                        // 参照那 23 条（目标④）：键名 = settings.automixStyle.<specName>，
                        // 顺序必须与 MixStyle.UI_ORDER 完全一致（存的就是这个下标）。
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
                        "settings.automixStyle.splice")
                .desc("settings.automixStyle.desc")
                .dependsOn(AUTOMIX_ENABLED_KEY)
                .group("automix")
                .build());

        // ③ 分层取模开关（默认关：开了同一对歌的落点可能变，见 AutoCue.TIER_FRACTION 注释）
        out.add(SettingSpec.toggle(AUTOMIX_TIERED_SELECTION_KEY, AUDIO,
                        "settings.automixTieredSelection.title", false)
                .group("automix")
                .dependsOn(AUTOMIX_ENABLED_KEY)
                .desc("settings.automixTieredSelection.desc")
                .build());

        out.add(SettingSpec.slider(AUTOMIX_BLEND_WIDTH_KEY, AUDIO,
                        "settings.automixBlendWidth.title", DEFAULT_BLEND_WIDTH_PCT, 0, 100, 5)
                .unit(" %")
                .desc("settings.automixBlendWidth.desc")
                .dependsOn(AUTOMIX_ENABLED_KEY)
                .group("automix")
                .build());
        out.add(SettingSpec.toggle(AUTOMIX_TEMPO_STRETCH_KEY, AUDIO,
                        "settings.automixTempoStretch.title", true)
                .desc("settings.automixTempoStretch.desc")
                .dependsOn(AUTOMIX_ENABLED_KEY)
                .group("automix")
                .build());
        out.add(SettingSpec.slider(AUTOMIX_WINDOW_SCALE_KEY, AUDIO,
                        "settings.automixWindowScale.title", DEFAULT_WINDOW_SCALE_PCT, 50, 200, 10)
                .unit(" %")
                .desc("settings.automixWindowScale.desc")
                .dependsOn(AUTOMIX_ENABLED_KEY)
                .group("automix")
                .build());
        // 排查开关放最后（它不属于「调音」，是「看日志」）。
        out.add(SettingSpec.toggle(AUTOMIX_DIAGNOSTIC_LOG_KEY, AUDIO,
                        "settings.automixDiagnosticLog.title", false)
                .desc("settings.automixDiagnosticLog.desc")
                .dependsOn(AUTOMIX_ENABLED_KEY)
                .group("automix")
                .build());

        // 其余二十来个键（以及将来新加的）仍然走这一行——控件只挑走了最常动的三个。
        out.add(SettingSpec.text(AUTOMIX_MIX_TUNING_KEY, AUDIO,
                        "settings.automixMixTuning.title", "")
                .desc("settings.automixMixTuning.desc")
                .dependsOn(AUTOMIX_ENABLED_KEY)
                .group("automix")
                .build());
        out.add(SettingSpec.toggle(AUTOMIX_AUTO_CUE_KEY, AUDIO,
                        "settings.automixAutoCue.title", false)
                .desc("settings.automixAutoCue.desc")
                .dependsOn(AUTOMIX_ENABLED_KEY)
                .group("automix")
                .build());
        // 手动点位（P4 控制室的「调」）：覆盖自动点位/固定点位算出来的那两个点。
        // 放在自动点位**之后**：两者都开时手动说了算（后者是明确的用户意图）。
        out.add(SettingSpec.toggle(AUTOMIX_MANUAL_CUE_KEY, AUDIO,
                        "settings.automixManualCue.title", false)
                .desc("settings.automixManualCue.desc")
                .dependsOn(AUTOMIX_ENABLED_KEY)
                .group("automix")
                .build());
        out.add(SettingSpec.slider(AUTOMIX_MANUAL_OUTRO_SECS_KEY, AUDIO,
                        "settings.automixManualOutro.title", 0, 0, 600, 5)
                .unit(" s").desc("settings.automixManualOutro.desc")
                .dependsOn(AUTOMIX_MANUAL_CUE_KEY)
                .group("automix")
                .build());
        out.add(SettingSpec.slider(AUTOMIX_MANUAL_INTRO_SECS_KEY, AUDIO,
                        "settings.automixManualIntro.title", 0, 0, 600, 1)
                .unit(" s").desc("settings.automixManualIntro.desc")
                .dependsOn(AUTOMIX_MANUAL_CUE_KEY)
                .group("automix")
                .build());
        out.add(SettingSpec.slider(AUTOMIX_CROSSFADE_SECS_KEY, AUDIO,
                        "settings.automixCrossfade.title", 6, 0, 20, 1)
                .unit(" s").desc("settings.automixCrossfade.desc")
                .dependsOn(AUTOMIX_ENABLED_KEY)
                .group("automix")
                .build());

        // 2026-11-15 用户反馈：23 条开关平铺在"自动混音"里完全没法看 ✗
        // ⇒ ① 标题用**人类可读**的接法名（settings.automixStyle.<specName>，不是枚举名）
        //    ② 单独成组 "automixStyles"，由 QML 那边做成**可折叠**的一节（默认收起）
        for (dev.t1m3.qplayer.audio.mix.MixStyle m
                : dev.t1m3.qplayer.audio.mix.MixStyle.values()) {
            // 仍然跟着总开关走：总开关关着时这 30 条也要藏起来（AutomixSwitchSettingsTest 钉的就是这条）
            out.add(SettingSpec.toggle("automix.allow." + m.name(), AUDIO,
                            "settings.automixStyle." + m.specName(), true)
                    .group("automixStyles")
                    .dependsOn(AUTOMIX_ENABLED_KEY)
                    .build());
        }
        // 自动点位：融合点交给整曲扫描决定。
        // 放在两个窗口滑块**之前**：它决定「窗口从哪儿开始算」，读完它再看时长才顺。
        // 只依赖总开关（不是接法）：无缝拼接时它也确实不生效，但那由引擎那一层
        // 如实不套包装保证（见 AutomixSettings.factoryFor），界面上不必再多一层隐藏——
        // 藏起来反而让用户以为「这个开关没了」。
        // ---- 音频工作室（P1 的控制面）----
        //
        // 为什么先是一组设置行而不是一个「工作室页面」：这一版要回答的是「这条链有没有用」。
        // 一个下拉 + 一个增益滑杆 + 一个响度开关就够把内置曲线一条条听过去；
        // 能拖曲线的编辑器是 P7（见 docs/audio-studio.md），那时这些行会变成它的入口。
        // 工作站页面的入口：设置页只留一行「打开音频工作室」，真正的调节都在那个页面里
        out.add(SettingSpec.action(OPEN_STUDIO_ACTION, AUDIO, "settings.studio.open.title",
                        "settings.studio.open.button")
                .icon("graphic_eq")
                .desc("settings.studio.open.desc")
                .group("studio")
                .build());
        out.add(SettingSpec.dropdown(STUDIO_PRESET_KEY, AUDIO, "settings.studio.preset.title",
                        dev.t1m3.qplayer.audio.studio.StudioPresets.OFF,
                        dev.t1m3.qplayer.audio.studio.StudioPresets.labels())
                .desc("settings.studio.preset.desc")
                .group("studio")
                .build());
        out.add(SettingSpec.dropdown(STUDIO_RACK_KEY, AUDIO, "settings.studio.rack.title",
                        0, "主总线", "出场曲（正在放的）", "进场曲（即将进来的）", "三台都上")
                .desc("settings.studio.rack.desc")
                .group("studio")
                .dependsOn(STUDIO_PRESET_KEY)
                .build());
        out.add(SettingSpec.slider(STUDIO_GAIN_KEY, AUDIO,
                        "settings.studio.gain.title", 0, -12, 12, 1)
                .unit(" dB")
                .desc("settings.studio.gain.desc")
                .group("studio")
                .dependsOn(STUDIO_PRESET_KEY)
                .build());
        out.add(SettingSpec.toggle(STUDIO_LOUDNESS_KEY, AUDIO,
                        "settings.studio.loudness.title", false)
                .desc("settings.studio.loudness.desc")
                .group("studio")
                .dependsOn(STUDIO_PRESET_KEY)
                .build());
        // ---- 音高（P2）----
        //
        // 四行而不是一行：开关 + 两个旋钮 + 干湿。为什么半音与音分拆开：见 PitchParams
        // 的类注释（改调用半音、对分轨用音分）。作用在哪一台沿用上面那一行「作用机架」——
        // 与曲线、前级增益同一个落点，用户不必再学一套。
        out.add(SettingSpec.toggle(STUDIO_PITCH_KEY, AUDIO,
                        "settings.studio.pitch.title", false)
                .desc("settings.studio.pitch.desc")
                .group("studio")
                .build());
        out.add(SettingSpec.slider(STUDIO_PITCH_SEMITONE_KEY, AUDIO,
                        "settings.studio.pitchSemitone.title", 0, -12, 12, 1)
                .unit(" 半音")
                .desc("settings.studio.pitchSemitone.desc")
                .group("studio")
                .dependsOn(STUDIO_PITCH_KEY)
                .build());
        out.add(SettingSpec.slider(STUDIO_PITCH_CENTS_KEY, AUDIO,
                        "settings.studio.pitchCents.title", 0, -100, 100, 1)
                .unit(" 音分")
                .desc("settings.studio.pitchCents.desc")
                .group("studio")
                .dependsOn(STUDIO_PITCH_KEY)
                .build());
        out.add(SettingSpec.slider(STUDIO_PITCH_MIX_KEY, AUDIO,
                        "settings.studio.pitchMix.title", 100, 0, 100, 5)
                .unit(" %")
                .desc("settings.studio.pitchMix.desc")
                .group("studio")
                .dependsOn(STUDIO_PITCH_KEY)
                .build());
        // ---- 人声后期（P2b/P3）----
        //
        // 独立一组（不是挂在「音频工作室」里）：它的作用域与那三台机架不同——
        // 机架作用在整条链上，这一组**只作用在融合期分离出来的人声轨上**。
        // 混在一起会让人以为「修跑调能修整首歌」，而那件事需要整曲分轨（见 docs 的待办）。
        out.add(SettingSpec.toggle(VOCAL_AUTOTUNE_KEY, AUDIO,
                        "settings.vocal.autotune.title", false)
                .desc("settings.vocal.autotune.desc")
                .group("vocal")
                .build());
        out.add(SettingSpec.dropdown(VOCAL_AUTOTUNE_SCALE_KEY, AUDIO,
                        "settings.vocal.scale.title", 0,
                        dev.t1m3.qplayer.audio.studio.ScaleQuantizer.Scale.labels())
                .desc("settings.vocal.scale.desc")
                .group("vocal")
                .dependsOn(VOCAL_AUTOTUNE_KEY)
                .build());
        out.add(SettingSpec.slider(VOCAL_AUTOTUNE_STRENGTH_KEY, AUDIO,
                        "settings.vocal.strength.title", 80, 0, 100, 5)
                .unit(" %")
                .desc("settings.vocal.strength.desc")
                .group("vocal")
                .dependsOn(VOCAL_AUTOTUNE_KEY)
                .build());
        out.add(SettingSpec.slider(VOCAL_AUTOTUNE_RETUNE_KEY, AUDIO,
                        "settings.vocal.retune.title", 40, 0, 200, 10)
                .unit(" ms")
                .desc("settings.vocal.retune.desc")
                .group("vocal")
                .dependsOn(VOCAL_AUTOTUNE_KEY)
                .build());
        out.add(SettingSpec.toggle(VOCAL_AUTOTUNE_VIBRATO_KEY, AUDIO,
                        "settings.vocal.vibrato.title", true)
                .desc("settings.vocal.vibrato.desc")
                .group("vocal")
                .dependsOn(VOCAL_AUTOTUNE_KEY)
                .build());
        out.add(SettingSpec.slider(VOCAL_DEREVERB_KEY, AUDIO,
                        "settings.vocal.dereverb.title", 0, 0, 100, 10)
                .unit(" %")
                .desc("settings.vocal.dereverb.desc")
                .group("vocal")
                .build());
        out.add(SettingSpec.slider(VOCAL_DEREVERB_FLOOR_KEY, AUDIO,
                        "settings.vocal.dereverbFloor.title", -18, -30, -6, 1)
                .unit(" dB")
                .desc("settings.vocal.dereverbFloor.desc")
                .group("vocal")
                .dependsOn(VOCAL_DEREVERB_KEY)
                .build());
        // ---- 动态（P5）----
        out.add(SettingSpec.toggle(STUDIO_GATE_KEY, AUDIO,
                        "settings.studio.gate.title", false)
                .desc("settings.studio.gate.desc").group("studio").build());
        out.add(SettingSpec.slider(STUDIO_GATE_THRESHOLD_KEY, AUDIO,
                        "settings.studio.gateThreshold.title", -45, -80, -10, 1)
                .unit(" dBFS").desc("settings.studio.gateThreshold.desc")
                .group("studio").dependsOn(STUDIO_GATE_KEY).build());
        out.add(SettingSpec.toggle(STUDIO_COMP_KEY, AUDIO,
                        "settings.studio.comp.title", false)
                .desc("settings.studio.comp.desc").group("studio").build());
        out.add(SettingSpec.slider(STUDIO_COMP_THRESHOLD_KEY, AUDIO,
                        "settings.studio.compThreshold.title", -18, -60, 0, 1)
                .unit(" dBFS").desc("settings.studio.compThreshold.desc")
                .group("studio").dependsOn(STUDIO_COMP_KEY).build());
        out.add(SettingSpec.slider(STUDIO_COMP_RATIO_KEY, AUDIO,
                        "settings.studio.compRatio.title", 3, 1, 20, 1)
                .unit(":1").desc("settings.studio.compRatio.desc")
                .group("studio").dependsOn(STUDIO_COMP_KEY).build());
        out.add(SettingSpec.slider(STUDIO_COMP_ATTACK_KEY, AUDIO,
                        "settings.studio.compAttack.title", 10, 1, 200, 1)
                .unit(" ms").desc("settings.studio.compAttack.desc")
                .group("studio").dependsOn(STUDIO_COMP_KEY).build());
        out.add(SettingSpec.slider(STUDIO_COMP_RELEASE_KEY, AUDIO,
                        "settings.studio.compRelease.title", 120, 10, 2000, 10)
                .unit(" ms").desc("settings.studio.compRelease.desc")
                .group("studio").dependsOn(STUDIO_COMP_KEY).build());
        out.add(SettingSpec.slider(STUDIO_COMP_MAKEUP_KEY, AUDIO,
                        "settings.studio.compMakeup.title", 0, 0, 24, 1)
                .unit(" dB").desc("settings.studio.compMakeup.desc")
                .group("studio").dependsOn(STUDIO_COMP_KEY).build());
        out.add(SettingSpec.toggle(STUDIO_LIMITER_KEY, AUDIO,
                        "settings.studio.limiter.title", false)
                .desc("settings.studio.limiter.desc").group("studio").build());
        out.add(SettingSpec.slider(STUDIO_LIMITER_CEILING_KEY, AUDIO,
                        "settings.studio.limiterCeiling.title", -1, -12, 0, 1)
                .unit(" dBFS").desc("settings.studio.limiterCeiling.desc")
                .group("studio").dependsOn(STUDIO_LIMITER_KEY).build());
        // ---- 立体声（P6）----
        out.add(SettingSpec.toggle(STUDIO_STEREO_KEY, AUDIO,
                        "settings.studio.stereo.title", false)
                .desc("settings.studio.stereo.desc").group("studio").build());
        out.add(SettingSpec.slider(STUDIO_STEREO_WIDTH_KEY, AUDIO,
                        "settings.studio.stereoWidth.title", 100, 0, 200, 5)
                .unit(" %").desc("settings.studio.stereoWidth.desc")
                .group("studio").dependsOn(STUDIO_STEREO_KEY).build());
        out.add(SettingSpec.slider(STUDIO_STEREO_BASS_MONO_KEY, AUDIO,
                        "settings.studio.bassMono.title", 0, 0, 500, 10)
                .unit(" Hz").desc("settings.studio.bassMono.desc")
                .group("studio").dependsOn(STUDIO_STEREO_KEY).build());
        out.add(SettingSpec.slider(STUDIO_STEREO_CROSSFEED_KEY, AUDIO,
                        "settings.studio.crossfeed.title", 0, 0, 100, 5)
                .unit(" %").desc("settings.studio.crossfeed.desc")
                .group("studio").dependsOn(STUDIO_STEREO_KEY).build());
        // ---- 染色（P7）----
        out.add(SettingSpec.toggle(STUDIO_SATURATION_KEY, AUDIO,
                        "settings.studio.saturation.title", false)
                .desc("settings.studio.saturation.desc").group("studio").build());
        out.add(SettingSpec.slider(STUDIO_SATURATION_DRIVE_KEY, AUDIO,
                        "settings.studio.saturationDrive.title", 6, 0, 24, 1)
                .unit(" dB").desc("settings.studio.saturationDrive.desc")
                .group("studio").dependsOn(STUDIO_SATURATION_KEY).build());
        out.add(SettingSpec.dropdown(STUDIO_SATURATION_CURVE_KEY, AUDIO,
                        "settings.studio.saturationCurve.title", 0,
                        dev.t1m3.qplayer.audio.studio.SaturationParams.Curve.labels())
                .desc("settings.studio.saturationCurve.desc")
                .group("studio").dependsOn(STUDIO_SATURATION_KEY).build());
        out.add(SettingSpec.dropdown(STUDIO_SATURATION_OVERSAMPLE_KEY, AUDIO,
                        "settings.studio.saturationOversample.title", 1,
                        "关（1×，有混叠）", "2×", "4×")
                .desc("settings.studio.saturationOversample.desc")
                .group("studio").dependsOn(STUDIO_SATURATION_KEY).build());
        out.add(SettingSpec.text(STUDIO_CUSTOM_KEY, AUDIO,
                        "settings.studio.custom.title", "")
                .desc("settings.studio.custom.desc")
                .group("studio")
                .build());

        // 三个最常改的旋钮（用户 2026-10-02：「旋钮在哪我都找不到」）：正经控件，
        // 不用打字。顺序 = 从「最容易听出来」到「最不容易听出来」。
        // 融合期的人声处理。**三选一，但只有桌面有第三档**：
        // DSP 是纯 Java（不需要下载、不该被任何「请先下载模型」挡住）；
        // AI 要 htdemucs 权重 + 宿主提供的推理实现 —— **安卓不装那个实现**
        // （StemModels.Opener 在移动端不安装，所以 StemModels.available() 恒为 false），
        // 所以 AI 那一档只在桌面出现，移动端只给「不分离 / DSP」。
        //
        // **为什么是同一个键、两行不同的形状，而不是两个键**：
        // SettingSpec 的 options 是一个固定列表，没法按平台少给一项，所以「少一档」只能靠
        // 两行。但**值**必须是同一个：引擎那边（AutomixSettings）刻意不知道自己在哪个平台
        // 上跑，两个键就会逼出 `settings.has(桌面键) ? 桌面键 : 移动键` 这种平台判断漏进音频层，
        // 而且另一个键在对面宿主上是一份永远生效不了的死值。同一个键 + 两行形状，
        // 读设置的地方仍然只有一处（{@link #AUTOMIX_VOCAL_MODE_KEY}），
        // 而「移动端选不到 AI」由选项表本身保证——SettingsCore.normalize 会把
        // 下标夹到 options.size()-1，所以移动端那行连存进 2 都做不到。
        // （万一别处真塞进来一个 2，音频层的规矩不变：available 为假就**如实降级**，
        //  绝不当成 DSP 用。）
        //
        // 静态 desc 自己就把代价与依赖写全：AI 那一档的实测代价（峰值内存、耗时、超预算时
        // 会退回不去人声）必须让用户在选之前看得到，而不能只挂在活文案上
        // （dropdown 行不渲染 provider，只有 action 行渲染）。
        out.add(SettingSpec.segmented(AUTOMIX_VOCAL_MODE_KEY, AUDIO, "settings.automixVocalMode.title",
                        AUTOMIX_VOCAL_MODE_OFF,
                        "settings.automixVocalMode.off", "settings.automixVocalMode.dsp")
                .desc("settings.automixVocalMode.desc.mobile")
                .onlyOn(ANDROID)
                .dependsOn(AUTOMIX_ENABLED_KEY)
                .group("automix")
                .build());

        // ---- 音频：模型（按需下载的权重） --------------------------------------
        // 权重绝不打包进成品（有测试钉着这条），所以设置里必须有：状态（未下载 /
        // 下载中 xx% / 已就绪 / 校验失败）、体积、许可证与来源、下载按钮，
        // 以及镜像全挂时的手动兜底入口。入口是一个 action 行 + 一个对话框，
        // 与「字体」那行同一个形状（都是「值不在行里，点开一个对话框选」）。
        out.add(SettingSpec.action(MANAGE_MODELS_ACTION, AUDIO, "settings.models.title",
                        "settings.models.manage")
                .provider("modelStatus")
                .desc("settings.models.desc")
                .icon("memory")
                .group("models")
                .build());
        out.add(SettingSpec.path(MODELS_DIR_KEY, AUDIO, "settings.modelsFolder.title", "")
                .desc("settings.modelsFolder.desc")
                .hint("settings.folder.hint")
                .onlyOn(DESKTOP)
                .group("models")
                .build());

        // ---- 歌词 -----------------------------------------------------------
        // Group typography, motion, display and background controls separately.
        out.add(SettingSpec.slider("lyricFontSize", LYRIC, "settings.lyricFontSize.title",
                        35, 14, 64, 1)
                .unit(" px").dots()
                .group("lyricText")
                .build());
        out.add(SettingSpec.segmented("lyricFontWeight", LYRIC, "settings.lyricFontWeight.title", 4,
                        "settings.lyricFontWeight.thin", "settings.lyricFontWeight.light",
                        "settings.lyricFontWeight.regular", "settings.lyricFontWeight.medium",
                        "settings.lyricFontWeight.bold", "settings.lyricFontWeight.heavy")
                .desc("settings.lyricFontWeight.desc")
                .group("lyricText")
                .build());
        out.add(SettingSpec.slider("lyricLineSpacing", LYRIC, "settings.lyricLineSpacing.title",
                        200, 100, 250, 5)
                .scale(100).unit("×").dots()
                .group("lyricText")
                .build());
        out.add(SettingSpec.slider("lyricEmphasisScale", LYRIC, "settings.lyricEmphasisScale.title",
                        114, 100, 130, 1)
                .scale(100).unit("×").dots()
                .desc("settings.lyricEmphasisScale.desc")
                .group("lyricText")
                .build());
        out.add(SettingSpec.toggle("lyricAutoLineSpacing", LYRIC,
                        "settings.lyricAutoLineSpacing.title", false)
                .desc("settings.lyricAutoLineSpacing.desc")
                .group("lyricText")
                .build());
        out.add(SettingSpec.toggle("lyricSpring", LYRIC, "settings.lyricSpring.title", true)
                .desc("settings.lyricSpring.desc")
                .group("lyricMotion")
                .build());
        out.add(SettingSpec.toggle("lyricMelodifyMotion", LYRIC,
                        "settings.lyricMelodifyMotion.title", true)
                .desc("settings.lyricMelodifyMotion.desc")
                .group("lyricMotion")
                .build());
        out.add(SettingSpec.toggle("lyricScale", LYRIC, "settings.lyricScale.title", true)
                .desc("settings.lyricScale.desc")
                .group("lyricMotion")
                .build());
        // 逐行动效**风格**：两条路互斥，默认沿用原来的"飘带"。
        //   · 飘带 = 跟发光一起出现的那条逐词抬升（LyricRowRenderer 的 WORD_RIBBON_LIFT_PX）
        //   · 放大 = Melodify（/home/strokeshading/Desktop/Melodify/melodify1201）的每行强调
        //     缩放：当前行 1.14、其它行 0.97，逐行弹簧驱动，锚点是行左缘 + 行垂直中心
        out.add(SettingSpec.segmented("lyricLiftStyle", LYRIC,
                        "settings.lyricLiftStyle.title", 0,
                        "settings.lyricLiftStyle.ribbon", "settings.lyricLiftStyle.zoom")
                .desc("settings.lyricLiftStyle.desc")
                .group("lyricMotion")
                .build());
        out.add(SettingSpec.toggle("lyricGlow", LYRIC, "settings.lyricGlow.title", true)
                .desc("settings.lyricGlow.desc")
                .group("lyricMotion")
                .build());
        out.add(SettingSpec.toggle("lyricShadow", LYRIC, "settings.lyricShadow.title", false)
                .desc("settings.lyricShadow.desc")
                .group("lyricMotion")
                .build());
        out.add(SettingSpec.toggle("lyricLinearAnim", LYRIC, "settings.lyricLinearAnim.title", false)
                .desc("settings.lyricLinearAnim.desc")
                .group("lyricMotion")
                .build());
        out.add(SettingSpec.toggle("lyricEdgeBlur", LYRIC, "settings.lyricEdgeBlur.title", true)
                .desc("settings.lyricEdgeBlur.desc")
                .group("lyricMotion")
                .build());
        out.add(SettingSpec.segmented("lyricProgressStyle", LYRIC,
                        "settings.lyricProgressStyle.title", 1,
                        "settings.lyricProgressStyle.wave",
                        "settings.lyricProgressStyle.line")
                .group("lyricDisplay")
                .build());

        out.add(SettingSpec.radio(BG_MODE_KEY, LYRIC, "settings.lyricBgMode.title", 0,
                        "settings.lyricBgMode.dynamic", "settings.lyricBgMode.static")
                .desc("settings.lyricBgMode.desc")
                .group("background")
                .build());
        out.add(SettingSpec.dropdown(BG_STYLE_KEY, LYRIC, "settings.lyricBgStyle.title",
                        BG_STYLE_DYNAMIC_MESH,
                        "settings.lyricBgStyle.pixi", "settings.lyricBgStyle.mesh",
                        "settings.lyricBgStyle.classic", "settings.lyricBgStyle.dynamicMesh")
                .desc("settings.lyricBgStyle.desc")
                .group("background")
                .build());
        out.add(SettingSpec.toggle("temperaWholeLine", LYRIC, "settings.temperaWholeLine.title", false)
                .desc("settings.temperaWholeLine.desc")
                .group("tempera")
                .build());
        out.add(SettingSpec.slider("temperaGlyphSettleStretch", LYRIC, "settings.temperaGlyphSettleStretch.title", 50, 0, 100, 5)
                .unit(" %")
                .desc("settings.temperaGlyphSettleStretch.desc")
                .group("tempera")
                .build());
        out.add(SettingSpec.toggle("temperaImages", LYRIC, "settings.temperaImages.title", false)
                .desc("settings.temperaImages.desc")
                .group("tempera")
                .build());
        // ---- 桌面歌词 (its own tab, desktop only) -----------------------------
        // Everything below the master switch is deliberately independent of the
        // lyric page's own typography: the floating window sits on the wallpaper,
        // not on a controlled backdrop, so it needs its own size/weight/colours.
        // dependsOn hides the whole block while the feature is off.
        out.add(SettingSpec.toggle("desktopLyricEnabled", DESKTOP_LYRIC,
                        "settings.desktopLyric.title", false)
                .desc("settings.desktopLyric.desc")
                .onlyOn(DESKTOP)
                .group("desktopLyricGeneral")
                .build());
        out.add(SettingSpec.toggle(DESKTOP_LYRIC_LOCKED_KEY, DESKTOP_LYRIC,
                        "settings.desktopLyricLocked.title", false)
                .desc("settings.desktopLyricLocked.desc")
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricGeneral")
                .build());
        out.add(SettingSpec.action("pickDesktopLyricFont", DESKTOP_LYRIC,
                        "settings.desktopLyricFont.title", "")
                .provider("desktopLyricFontName")
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricText")
                .build());
        out.add(SettingSpec.slider("desktopLyricFontSize", DESKTOP_LYRIC,
                        "settings.desktopLyricFontSize.title", 26, 18, 38, 1)
                .unit(" px").dots()
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricText")
                .build());
        out.add(SettingSpec.segmented("desktopLyricFontWeight", DESKTOP_LYRIC,
                        "settings.desktopLyricFontWeight.title", 2,
                        "settings.lyricFontWeight.thin", "settings.lyricFontWeight.light",
                        "settings.lyricFontWeight.regular", "settings.lyricFontWeight.medium")
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricText")
                .build());
        out.add(SettingSpec.color(DESKTOP_LYRIC_SUNG_COLOR_KEY, DESKTOP_LYRIC,
                        "settings.desktopLyricSungColor.title", "")
                .desc("settings.desktopLyricSungColor.desc")
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricInk")
                .build());
        out.add(SettingSpec.color(DESKTOP_LYRIC_UNSUNG_COLOR_KEY, DESKTOP_LYRIC,
                        "settings.desktopLyricUnsungColor.title", "")
                .desc("settings.desktopLyricUnsungColor.desc")
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricInk")
                .build());
        out.add(SettingSpec.toggle("desktopLyricOutline", DESKTOP_LYRIC,
                        "settings.desktopLyricOutline.title", true)
                .desc("settings.desktopLyricOutline.desc")
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricInk")
                .build());
        out.add(SettingSpec.toggle("desktopLyricShadow", DESKTOP_LYRIC,
                        "settings.desktopLyricShadow.title", true)
                .desc("settings.desktopLyricShadow.desc")
                .onlyOn(DESKTOP)
                .dependsOn("desktopLyricEnabled")
                .group("desktopLyricInk")
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
                .group("folders")
                .build());

        // ---- 关于 -----------------------------------------------------------
        out.add(SettingSpec.action("openRepo", ABOUT, "QPlayer", "")
                .provider("version").inlineProvider()
                // Hard-coded breaks: qml4j's auto-wrap mis-measures this width.
                .desc("settings.about.desc")
                .group("app")
                .build());
        out.add(SettingSpec.action("checkUpdate", ABOUT, "settings.checkUpdate.title", "")
                .icon("system_update")
                .group("app")
                .build());

        // **自动接法允许的风格**（用户 2026-10-09 ✓）：每档一条开关 ✓
        // 分类 AUDIO ✓；group 与 desc 都必须用语言文件里**已有**的键（不写 desc ⇒ 不校验 ✓）。
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
