package dev.t1m3.qplayer.audio.engine;

import dev.t1m3.qplayer.audio.mix.AiMix;
import dev.t1m3.qplayer.audio.mix.AutoCueTransition;
import dev.t1m3.qplayer.audio.mix.Crossfade;
import dev.t1m3.qplayer.audio.mix.Gapless;
import dev.t1m3.qplayer.audio.mix.HarmonicBlend;
import dev.t1m3.qplayer.audio.mix.MixStyle;
import dev.t1m3.qplayer.audio.mix.Transition;
import dev.t1m3.qplayer.audio.mix.TransitionFactory;
import dev.t1m3.qplayer.audio.mix.VocalAwarePitchTransition;
import dev.t1m3.qplayer.audio.mix.VocalSeparationTransition;
import dev.t1m3.qplayer.audio.stem.StemSeparators;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import dev.t1m3.qplayer.settings.SettingsCore;
import dev.t1m3.qplayer.util.Logger;

/**
 * 设置 → 过渡引擎的桥（docs/audio-engine.md §4）。
 *
 * <p>它做两件事：
 *
 * <ol>
 *   <li>把 {@code SettingsCore} 里那几个键读成 {@link EngineSettings}
 *       （含越界夹紧：设置文件是用户能手改的 JSON，引擎不能假定它合法）；</li>
 *   <li>按模式造 {@link TransitionFactory}——<strong>每次过渡都要工厂造新实例</strong>，
 *       这是上游的生命周期约定（实例只服务一次过渡，准备线程上造新的，
 *       渲染路径因此不需要锁，见 {@code Director} 的类注释）。</li>
 * </ol>
 *
 * <table border="1">
 *   <caption>模式对照（与 docs/audio-engine.md §4 一致）</caption>
 *   <tr><th>设置值</th><th>模式</th><th>工厂产出</th></tr>
 *   <tr><td>0</td><td>无缝拼接</td><td>{@link Gapless#INSTANCE}（窗口 0、不改写采样值，
 *       <strong>唯一与位完美兼容</strong>的换曲方式）</td></tr>
 *   <tr><td>1</td><td>交叉淡化</td><td>{@code new Crossfade(automixCrossfadeSecs)}</td></tr>
 *   <tr><td>2</td><td>声波重合</td><td>{@code new HarmonicBlend(rate, channels, automixHarmonicSecs)}</td></tr>
 *   <tr><td>3</td><td>AI 模式</td><td>{@code new AiMix(MixStyle.fromUiIndex(automixStyle), rate, channels)}</td></tr>
 * </table>
 *
 * <p>这张表之外还有一层<strong>可选的人声处理</strong>：{@code automixVocalMode} 选
 * <strong>DSP</strong> 或 <strong>AI（模型）</strong>时，上面每一个接法都会被
 * {@link VocalSeparationTransition} 包住——它只把进场曲那一路的素材换成
 * 「伴奏 + 人声 × 包络」，<strong>接法本身（窗口、切点、变调）一字不改</strong>，
 * 所以三种模式与 AI 模式的五种接法自动都支持。两个例外：<strong>不分离</strong>
 * （默认档，压根不套这一层）与<strong>无缝拼接</strong>（窗口 0，没有可融合的片段，
 * 套上去只是白读一次文件、白跑一次分离）。
 *
 * <p><strong>设置的改变在下一次造编排器时生效</strong>（每个 Director 都是重新按当前
 * 设置造工厂）：也就是下一首开始时，或本曲中途因为预告了下一首而重建时。
 * 这与上游「选择要等下一次 {@code /api/play} 才生效」是同一个取舍——
 * 在过渡进行中换掉引擎会把已经准备好的那一份结论作废，得不偿失。
 */
public final class AutomixSettings implements EngineSettings {

    /**
     * **AI 档是否套「只移伴奏」那一层**（默认 **false** ✓ 2026-10-09 对照实验 ✓）：
     * 0-参构造走的是同一份设置 ✓；这一项只影响 **AI 档 + 接法=自动** 的组合 ✓（HARMONIC 档不受影响 ✓）。
     */
    private static volatile boolean harmonicInAiMode = true;   // 默认 = 与从前逐位相同（内核 1464/1 ✓）

    public static void setHarmonicInAiMode(boolean on) {
        harmonicInAiMode = on;
    }

    public static boolean harmonicInAiMode() {
        return harmonicInAiMode;
    }

    /** 窗口时长的合法上限（秒）。设置里的滑块是 0..20，这里留点余量但不许无限大。 */
    private static final float MAX_WINDOW_SECS = 60.0f;

    /** {@code null} = 用缺省值（无缝拼接 / 不位完美），宿主还没接设置时就是这种状态。 */
    private final SettingsCore settings;

    /**
     * @param settings 设置中枢；{@code null} 表示「读不到设置」，一律用最保守的缺省值
     *                 （无缝拼接 = 不改写采样值）
     */
    public AutomixSettings(SettingsCore settings) {
        this.settings = settings;
    }

    /** 读不到设置时的桥（全部走缺省值）。 */
    public static AutomixSettings defaults() {
        return new AutomixSettings(null);
    }

    @Override
    public int automixMode() {
        if (settings == null) {
            return SettingsCatalog.AUTOMIX_MODE_AI;
        }
        return clamp(settings.intOf(SettingsCatalog.AUTOMIX_MODE_KEY),
                SettingsCatalog.AUTOMIX_MODE_GAPLESS, SettingsCatalog.AUTOMIX_MODE_AI);
    }

    /**
     * 总开关（{@code SettingsCatalog.AUTOMIX_ENABLED_KEY}）。
     *
     * <p>读不到设置时是 <strong>false</strong>：不承诺任何会改写采样值、会跑秒级分析
     * 的能力——与「读不到设置时是『不分离』」同一条纪律。
     *
     * <p>注意它<strong>不改</strong> {@link #automixMode()} 的读数：用户存的那一档如实
     * 报出来（界面要显示它），只是不生效。关掉开关就说「接法是无缝拼接」是另一种撒谎。
     */
    @Override
    public boolean automixEnabled() {
        return settings != null && settings.bool(SettingsCatalog.AUTOMIX_ENABLED_KEY);
    }

    @Override
    public int automixStyle() {
        if (settings == null) {
            return 5;
        }
        // 存的确实是 UI_ORDER 的下标（不是 ordinal），越界由 fromUiIndex 退化成「自动」；
        // 这里再把结果归一成下标，让「读到的值」与「实际用的接法」永远一致。
        return MixStyle.fromUiIndex(settings.intOf(SettingsCatalog.AUTOMIX_STYLE_KEY)).uiIndex();
    }

    @Override
    public float crossfadeSecs() {
        return windowSecs(SettingsCatalog.AUTOMIX_CROSSFADE_SECS_KEY);
    }

    @Override
    public float harmonicSecs() {
        return windowSecs(SettingsCatalog.AUTOMIX_HARMONIC_SECS_KEY);
    }

    @Override
    public boolean bitPerfect() {
        return settings != null && settings.bool(SettingsCatalog.HIFI_BIT_PERFECT_KEY);
    }

    @Override
    public boolean rateFollow() {
        return settings != null && settings.bool(SettingsCatalog.HIFI_RATE_FOLLOW_KEY);
    }

    /**
     * 融合阶段的人声怎么处理（{@code SettingsCatalog.AUTOMIX_VOCAL_MODE_KEY}）。
     *
     * <p>三档：不分离 / DSP / AI（模型）。取值按<strong>不可信</strong>处理（设置文件
     * 是用户能手改的 JSON），越界一律夹到「不分离」——那是唯一不需要任何额外东西的档，
     * 也是唯一不会「悄悄用了另一种算法」的档。
     *
     * <p><strong>AI 那一档只在桌面设置里出现</strong>（移动端不安装
     * {@code StemModels.Opener}，那份推理在手机上跑不起来），但这个键在两端是同一个，
     * 而且这里<b>不按平台夹取</b>：万一手上真有一个 2（手改过的存储、跨端同步的配置文件），
     * 它的处理与「模型没下载」完全一样——{@code StemSeparators.available} 为假，
     * 于是<b>如实降级</b>（照常过渡 + note 标注），<b>绝不</b>当成 DSP 用。
     * 悄悄换一档比「这一档用不了」糟得多。
     */
    @Override
    public int automixVocalMode() {
        if (settings == null) {
            return SettingsCatalog.AUTOMIX_VOCAL_MODE_OFF;
        }
        return clamp(settings.intOf(SettingsCatalog.AUTOMIX_VOCAL_MODE_KEY),
                SettingsCatalog.AUTOMIX_VOCAL_MODE_OFF, SettingsCatalog.AUTOMIX_VOCAL_MODE_AI);
    }

    /**
     * 按当前设置造一个过渡工厂。
     *
     * <p>采样率与声道数是<strong>协商到的输出配置</strong>（不是源文件的）：过渡在输出
     * 采样率上做，声道数也按输出布局（AI 模式要按声道数分配分频器与扫频滤波器，
     * 声波重合要按采样率预留变调器的环形缓冲，这些都不能等到渲染时再分配）。
     *
     * <p>返回的工厂每次 {@link TransitionFactory#create()} 都造<strong>新实例</strong>；
     * 模式与窗口在造工厂这一刻就定下来了（后续改设置不影响这一份）。
     */
    /**
     * 融合期那几层处理的旋钮：来自设置项
     * {@link SettingsCatalog#AUTOMIX_MIX_TUNING_KEY}（空串 = 全默认）。
     *
     * <p>没有设置来源时（{@link #defaults()}）一律用 {@link MixTuning#defaults()}——
     * 与加这个键之前逐位相同。
     */
    String tuningSpec() {
        if (settings == null) {
            return null;
        }
        return composeSpec(settings.str(SettingsCatalog.AUTOMIX_MIX_TUNING_KEY),
                settings.intOf(SettingsCatalog.AUTOMIX_BLEND_WIDTH_KEY),
                settings.bool(SettingsCatalog.AUTOMIX_TEMPO_STRETCH_KEY),
                settings.intOf(SettingsCatalog.AUTOMIX_WINDOW_SCALE_KEY));
    }

    /**
     * 把「文本调参串」与「三个正经控件」拼成一条串（控件排在后面 → 同名键以控件为准）。
     *
     * <p>为什么控件优先：用户 2026-10-02 的原话是「旋钮在哪我都找不到」——一个只认文本的
     * 键对听众等于不存在。既然把 {@code width / tempo_stretch / window_scale} 搬成了
     * 滑杆和开关，那么界面上<b>看得见的那一个</b>必须是生效的那一个，否则拖了没反应＝坏了。
     *
     * <p><b>但不在文本里写了却被忽略是不行的</b>（这个仓库的纪律：不许静默降级）。
     * 所以一旦文本串里出现这三个键（含 {@code scale} / {@code tempoStretch} 这类别名），
     * 就报一行 WARN 说明「以控件为准」，且同一串只报一次（每次过渡都会走这里，
     * 不缓存就会刷屏；串只在用户改设置时才变，所以按串去重足够）。
     */
    static String composeSpec(String text, int widthPct, boolean stretchOn, int scalePct) {
        final String base = text == null ? "" : text.trim();
        warnControlOwnedKeysWrittenByHand(base);
        final String controls = "width=" + (clamp(widthPct, 0, 100) / 100.0f)
                + ",tempo_stretch=" + (stretchOn ? 1 : 0)
                + ",window_scale=" + (clamp(scalePct, 50, 200) / 100.0f);
        return base.isEmpty() ? controls : base + "," + controls;
    }

    /** 串里写了控件正主的键：以控件为准，但要说一声。 */
    private static void warnControlOwnedKeysWrittenByHand(String spec) {
        if (spec.isEmpty()) {
            return;
        }
        final String hit = controlOwnedKeyIn(spec);
        if (hit == null || spec.equals(lastControlOverrideWarned)) {
            return;
        }
        // 按**整条串**去重（不是按键名）：按键名去重的话，用户删掉再写回来就不再报了——
        // 而「第二次写了却不吭声」正好是这条纪律要防的样子。
        lastControlOverrideWarned = spec;
        dev.t1m3.qplayer.util.Logger.warn(
                "[qplayer-engine] autoMix 调参串里写了 {}，但它现在由界面上的控件控制："
                        + "以控件为准（要按文本生效请先清空这一行或删掉该键）：{}",
                hit, spec);
    }

    /** 只报一次的那把锁：记整条串（串变了就再报一次，同一条不重复报）。 */
    private static volatile String lastControlOverrideWarned;

    /**
     * 串里是否写了「控件正主」的三个键之一；命中就返回命中的写法（用于日志），否则 null。
     *
     * <p>认的是 {@code MixTuning.parse} 自己认的那些别名，否则「写了 scale=1.4 却被忽略」
     * 会漏报——别名漏一个，这条纪律就破了。
     */
    static String controlOwnedKeyIn(String spec) {
        String[] pairs = spec.split(",");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            String key = (eq < 0 ? pairs[i] : pairs[i].substring(0, eq)).trim().toLowerCase();
            if (key.equals("width")
                    || key.equals("tempo_stretch") || key.equals("tempostretch")
                    || key.equals("window_scale") || key.equals("scale")
                    || key.equals("windowscale")) {
                return key;
            }
        }
        return null;
    }

    /**
     * 解析 {@code 键=值,键=值} 形式的调参串；<b>空串 = 全默认，写错则退回全默认并报一行</b>。
     *
     * <p>为什么这里要吞掉异常：{@code MixTuning.parse} 的纪律是「认不出的键一律报错，
     * 不静默忽略」（那是对的，调参时写错键必须立刻知道），但宿主这一层是**每次过渡**都会
     * 走一遍的地方——一个手滑的字符不该让自动混音整段失效。所以这一层把异常转成
     * 一条 WARN + 全默认，用户下次看日志就知道自己写错了。
     */
    static dev.t1m3.qplayer.audio.mix.MixTuning parseTuning(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            return dev.t1m3.qplayer.audio.mix.MixTuning.defaults();
        }
        try {
            return dev.t1m3.qplayer.audio.mix.MixTuning.parse(spec);
        } catch (RuntimeException e) {
            dev.t1m3.qplayer.util.Logger.warn(
                    "[qplayer-engine] automixMixTuning 解析失败（{}），这一次退回全部默认旋钮：{}",
                    spec, e.toString());
            return dev.t1m3.qplayer.audio.mix.MixTuning.defaults();
        }
    }

    @Override
    public TransitionFactory transitionFactory(final int rate, final int channels) {
        // **人声后期从这里带进去**：它是「作用在人声轨上」的那一套参数，
        // 而拿得到人声轨的地方只有分轨那一层（见 VocalSeparationTransition）。
        return factoryFor(automixEnabled(), automixMode(), automixStyle(), crossfadeSecs(),
                harmonicSecs(), automixVocalMode(), automixAutoCue(), rate, channels,
                vocalAutotune(), vocalDereverb(), manualCueEnabled(), manualOutroSecs(),
                manualIntroSecs(),
                // 「现读」的窗口：自动点位与 AI 方案比较每次准备时读一次，
                // 用户在播放期间改滑块也能立刻用上（见 AutoCueTransition.liveWindowSecs）
                this::crossfadeSecs, this::harmonicSecs, tuningSpec());
    }

    /**
     * 要扫整曲的那几档，提前量必须比默认的 30 秒大得多（生成式曾是最贵的一档，
     * 2026-10-01 整套删除）。
     *
     * <p>取 150 秒：桌面 CPU 上跑一次 8 步的 DiT + VAE 解码通常远小于这个数，
     * 但慢机器、第一次加载权重（读一两个 GB）、以及被系统调度挤开都还在里面。
     * 给大没有代价——准备一直是异步的，提前量只决定什么时候起跑；
     * 给小的代价则是这次过渡干脆用不上生成段。
     *
     * <p>其余档一律返回默认值：它们只需要几秒的分析，30 秒的余量本来就是为那个留的。
     */
    /**
     * 工作室处理链：把设置里的那几行（曲线 / 作用机架 / 前级增益 / 等响度 / 自定义 JSON）
     * 组合成三台机架的参数。
     *
     * <h3>优先级（界面上那几行的语义）</h3>
     *
     * <ol>
     *   <li><b>自定义 JSON</b> 是底座：填了就以它为准（可以只写要改的那一台）；</li>
     *   <li><b>曲线</b> 覆盖「作用在哪一台」选中的那台的<strong>段表</strong>
     *       （选「关」时不覆盖，纯用 JSON）；</li>
     *   <li><b>前级增益</b>与<b>等响度</b>始终叠加在那一台上——它们各自就是一行，
     *       用户动它们就是要立刻生效。</li>
     * </ol>
     *
     * <p><b>缓存解析结果</b>：引擎每块都要问一次，它靠对象引用判断参数换没换
     * （每次都 new 会让它每块重建滤波器）。失效只认那五个值一起变了。
     */
    @Override
    public dev.t1m3.qplayer.audio.studio.StudioRacks audioStudio() {
        if (settings == null) {
            return dev.t1m3.qplayer.audio.studio.StudioRacks.EMPTY;
        }
        int preset = settings.intOf(dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_PRESET_KEY);
        int target = settings.intOf(dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_RACK_KEY);
        int gain = settings.intOf(dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_GAIN_KEY);
        boolean loudness = settings.bool(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_LOUDNESS_KEY);
        String custom = settings.str(dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_CUSTOM_KEY);
        boolean pitchOn = settings.bool(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_PITCH_KEY);
        int semitone = settings.intOf(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_PITCH_SEMITONE_KEY);
        int cents = settings.intOf(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_PITCH_CENTS_KEY);
        int pitchMix = settings.intOf(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_PITCH_MIX_KEY);
        boolean gateOn = settings.bool(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_GATE_KEY);
        int gateThreshold = settings.intOf(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_GATE_THRESHOLD_KEY);
        boolean compOn = settings.bool(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_COMP_KEY);
        int compThreshold = settings.intOf(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_COMP_THRESHOLD_KEY);
        int compRatio = settings.intOf(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_COMP_RATIO_KEY);
        int compAttack = settings.intOf(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_COMP_ATTACK_KEY);
        int compRelease = settings.intOf(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_COMP_RELEASE_KEY);
        int compMakeup = settings.intOf(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_COMP_MAKEUP_KEY);
        boolean limiterOn = settings.bool(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_LIMITER_KEY);
        int limiterCeiling = settings.intOf(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_LIMITER_CEILING_KEY);
        boolean saturationOn = settings.bool(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_SATURATION_KEY);
        int saturationDrive = settings.intOf(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_SATURATION_DRIVE_KEY);
        int saturationCurve = settings.intOf(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_SATURATION_CURVE_KEY);
        int saturationOversample = settings.intOf(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_SATURATION_OVERSAMPLE_KEY);
        boolean stereoOn = settings.bool(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_STEREO_KEY);
        int stereoWidth = settings.intOf(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_STEREO_WIDTH_KEY);
        int bassMono = settings.intOf(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_STEREO_BASS_MONO_KEY);
        int crossfeed = settings.intOf(
                dev.t1m3.qplayer.settings.SettingsCatalog.STUDIO_STEREO_CROSSFEED_KEY);
        String key = preset + "|" + target + "|" + gain + "|" + loudness + "|" + custom
                + "|" + pitchOn + "|" + semitone + "|" + cents + "|" + pitchMix
                + "|" + gateOn + "|" + gateThreshold + "|" + compOn + "|" + compThreshold
                + "|" + compRatio + "|" + compAttack + "|" + compRelease + "|" + compMakeup
                + "|" + limiterOn + "|" + limiterCeiling
                + "|" + saturationOn + "|" + saturationDrive + "|" + saturationCurve
                + "|" + saturationOversample
                + "|" + stereoOn + "|" + stereoWidth + "|" + bassMono + "|" + crossfeed;
        if (key.equals(cachedStudioKey)) {
            return cachedStudioRacks;
        }
        cachedStudioKey = key;
        cachedStudioRacks = composeStudio(preset, target, gain, loudness, custom,
                pitchOn, semitone, cents, pitchMix,
                gateOn, gateThreshold, compOn, compThreshold, compRatio, compAttack, compRelease,
                compMakeup, limiterOn, limiterCeiling,
                saturationOn, saturationDrive, saturationCurve, saturationOversample,
                stereoOn, stereoWidth, bassMono, crossfeed);
        if (cachedStudioRacks.active()) {
            Logger.info("[qplayer-engine] 工作室参数更新：{}", cachedStudioRacks);
        }
        return cachedStudioRacks;
    }

    /** 组合逻辑单独拆出来：它是纯函数，测试直接喂参数就能核（不用造设置宿主）。 */
    static dev.t1m3.qplayer.audio.studio.StudioRacks composeStudio(
            int presetIndex, int target, int gainDb, boolean loudness, String customJson) {
        return composeStudio(presetIndex, target, gainDb, loudness, customJson,
                false, 0, 0, 100);
    }

    /**
     * 同 {@link #composeStudio(int, int, int, boolean, String)}，再叠加<strong>音高</strong>。
     *
     * <p>音高与曲线/增益同一条优先级：总是落在「作用机架」选中的那一台上，
     * 以及<strong>自定义 JSON 里已经写好的音高之上</strong>——所以「关」的时候不是清零，
     * 而是原样保留 JSON 里那一份（与曲线选「关」时不覆盖 JSON 同一条纪律）。
     *
     * @param pitchOn  音高开关
     * @param semitone 半音（越界由 {@code PitchParams} 夹）
     * @param cents    音分
     * @param pitchMix 干湿（百分比 0..100）
     */
    static dev.t1m3.qplayer.audio.studio.StudioRacks composeStudio(
            int presetIndex, int target, int gainDb, boolean loudness, String customJson,
            boolean pitchOn, int semitone, int cents, int pitchMix) {
        return composeStudio(presetIndex, target, gainDb, loudness, customJson, pitchOn,
                semitone, cents, pitchMix, false, -45, false, -18, 3, 10, 120, 0, false, -1,
                false, 6, 0, 1, false, 100, 0, 0);
    }

    /** 动态参数的纯函数（与 {@code composeStudio} 同一个理由：测试直接喂参数核）。 */
    public static dev.t1m3.qplayer.audio.studio.DynamicsParams composeDynamics(
            boolean gateOn, double gateThresholdDb, boolean compOn, double compThresholdDb,
            double compRatio, double compAttackMs, double compReleaseMs, double compMakeupDb,
            boolean limiterOn, double limiterCeilingDb) {
        return dev.t1m3.qplayer.audio.studio.DynamicsParams.builder()
                .gate(gateOn, gateThresholdDb, -60.0, 50.0)
                .compressor(compOn, compThresholdDb, compRatio, 6.0, compAttackMs,
                        compReleaseMs, compMakeupDb)
                .limiter(limiterOn, limiterCeilingDb, 2.0, 80.0)
                .build();
    }

    /** 染色参数的纯函数。 */
    public static dev.t1m3.qplayer.audio.studio.SaturationParams composeSaturation(
            boolean on, double driveDb, int curveIndex, int oversample) {
        return dev.t1m3.qplayer.audio.studio.SaturationParams.of(
                dev.t1m3.qplayer.audio.studio.SaturationParams.Curve.fromIndex(curveIndex),
                driveDb, -driveDb, 1.0, oversample).withEnabled(on);
    }

    /** 立体声参数的纯函数。 */
    public static dev.t1m3.qplayer.audio.studio.StereoParams composeStereo(
            boolean on, int widthPercent, double bassMonoHz, int crossfeedPercent) {
        return new dev.t1m3.qplayer.audio.studio.StereoParams(on,
                Math.max(0, Math.min(200, widthPercent)) / 100.0, bassMonoHz, 0.0, false,
                Math.max(0, Math.min(100, crossfeedPercent)) / 100.0);
    }

    /** 完整版（带 P5–P7 那三组）。 */
    static dev.t1m3.qplayer.audio.studio.StudioRacks composeStudio(
            int presetIndex, int target, int gainDb, boolean loudness, String customJson,
            boolean pitchOn, int semitone, int cents, int pitchMix,
            boolean gateOn, double gateThresholdDb, boolean compOn, double compThresholdDb,
            double compRatio, double compAttackMs, double compReleaseMs, double compMakeupDb,
            boolean limiterOn, double limiterCeilingDb, boolean saturationOn,
            double saturationDriveDb, int saturationCurve, int saturationOversample,
            boolean stereoOn, int stereoWidthPercent, double bassMonoHz, int crossfeedPercent) {
        dev.t1m3.qplayer.audio.studio.StudioRacks base = customJson == null
                || customJson.trim().isEmpty()
                ? dev.t1m3.qplayer.audio.studio.StudioRacks.EMPTY
                : dev.t1m3.qplayer.audio.studio.StudioJson.fromJson(customJson);
        boolean presetOff = presetIndex <= dev.t1m3.qplayer.audio.studio.StudioPresets.OFF;
        dev.t1m3.qplayer.audio.studio.StudioRack presetRack =
                dev.t1m3.qplayer.audio.studio.StudioPresets.at(presetIndex).rack;
        int[] targets = target == 3 ? new int[]{0, 1, 2} : new int[]{target < 0 ? 0 : target};
        // **「下面那几行」本身就是「我要这台机架工作」的意思**：曲线选「关」时底座是
        // 空机架（`enabled == false`，直通），照原样叠加增益/响度/音高等于把它们默默丢掉
        // ——用户把前级推到 +6 dB 却一点变化都没有，那是这个功能最难查的一种 bug。
        // 所以只要这三行里有任何一项不是「什么都没做」，就把目标机架打开。
        boolean rowsAskForWork = Math.abs(gainDb) > 0 || loudness
                || (pitchOn && (semitone != 0 || cents != 0) && pitchMix > 0);
        for (int t : targets) {
            dev.t1m3.qplayer.audio.studio.StudioRack rack = presetOff
                    ? rackOf(base, t) : presetRack;
            rack = rack.withPreamp(gainDb);
            rack = rack.withLoudness(loudness, Math.max(rack.loudnessAmount, 0.8));
            if (pitchOn) {
                rack = rack.withPitch(dev.t1m3.qplayer.audio.studio.PitchParams.of(
                        semitone, cents, Math.max(0.0, Math.min(100, pitchMix)) / 100.0));
            }
            // 动态 / 立体声 / 染色（P5–P7）：各自一个纯函数组合，落点与上面那几行一致
            rack = rack.withDynamics(composeDynamics(gateOn, gateThresholdDb, compOn,
                    compThresholdDb, compRatio, compAttackMs, compReleaseMs, compMakeupDb,
                    limiterOn, limiterCeilingDb));
            rack = rack.withSaturation(composeSaturation(saturationOn, saturationDriveDb,
                    saturationCurve, saturationOversample));
            rack = rack.withStereo(composeStereo(stereoOn, stereoWidthPercent, bassMonoHz,
                    crossfeedPercent));
            if (rowsAskForWork || gateOn || compOn || limiterOn || saturationOn
                    || (stereoOn && stereoWidthPercent != 100)) {
                // 这三组开着时也要把机架打开（否则它们同样会被「机架总闸关着」静默吃掉）
                rack = rack.withEnabled(true);
            }
            base = withRack(base, t, rack);
        }
        return base;
    }

    private static dev.t1m3.qplayer.audio.studio.StudioRack rackOf(
            dev.t1m3.qplayer.audio.studio.StudioRacks racks, int target) {
        if (target == 1) {
            return racks.deckA;
        }
        if (target == 2) {
            return racks.deckB;
        }
        return racks.master;
    }

    private static dev.t1m3.qplayer.audio.studio.StudioRacks withRack(
            dev.t1m3.qplayer.audio.studio.StudioRacks racks, int target,
            dev.t1m3.qplayer.audio.studio.StudioRack rack) {
        if (target == 1) {
            return racks.withDeckA(rack);
        }
        if (target == 2) {
            return racks.withDeckB(rack);
        }
        return racks.withMaster(rack);
    }

    private String cachedStudioKey = "\u0000";

    private dev.t1m3.qplayer.audio.studio.StudioRacks cachedStudioRacks =
            dev.t1m3.qplayer.audio.studio.StudioRacks.EMPTY;

    /**
     * 人声后期（修跑调 / 去混响）的参数。
     *
     * <p>它是纯函数 + 缓存（与 {@code audioStudio()} 同一套写法）：每次造接法都会问一次，
     * 每次都 new 一个对象没有意义，而这个对象只在下一次造编排器时被读一次。
     */
    public dev.t1m3.qplayer.audio.studio.AutotuneParams vocalAutotune() {
        if (settings == null) {
            return dev.t1m3.qplayer.audio.studio.AutotuneParams.off();
        }
        if (!settings.bool(dev.t1m3.qplayer.settings.SettingsCatalog.VOCAL_AUTOTUNE_KEY)) {
            return dev.t1m3.qplayer.audio.studio.AutotuneParams.off();
        }
        return composeAutotune(
                settings.intOf(dev.t1m3.qplayer.settings.SettingsCatalog.VOCAL_AUTOTUNE_SCALE_KEY),
                settings.intOf(
                        dev.t1m3.qplayer.settings.SettingsCatalog.VOCAL_AUTOTUNE_STRENGTH_KEY),
                settings.intOf(dev.t1m3.qplayer.settings.SettingsCatalog.VOCAL_AUTOTUNE_RETUNE_KEY),
                settings.bool(
                        dev.t1m3.qplayer.settings.SettingsCatalog.VOCAL_AUTOTUNE_VIBRATO_KEY));
    }

    /** 修跑调参数的纯函数（测试直接喂参数核，见 {@code VocalPolishSettingsTest}）。 */
    static dev.t1m3.qplayer.audio.studio.AutotuneParams composeAutotune(
            int scaleIndex, int strengthPercent, int retuneMs, boolean preserveVibrato) {
        return dev.t1m3.qplayer.audio.studio.AutotuneParams.of(
                dev.t1m3.qplayer.audio.studio.ScaleQuantizer.Scale.fromIndex(scaleIndex),
                0, dev.t1m3.qplayer.audio.studio.ScaleQuantizer.DEFAULT_A4,
                Math.max(0, Math.min(100, strengthPercent)) / 100.0,
                Math.max(0, retuneMs), preserveVibrato, 1.0);
    }

    /** 去混响的参数（纯函数）。 */
    public dev.t1m3.qplayer.audio.studio.DereverbParams vocalDereverb() {
        if (settings == null) {
            return dev.t1m3.qplayer.audio.studio.DereverbParams.off();
        }
        return composeDereverb(
                settings.intOf(dev.t1m3.qplayer.settings.SettingsCatalog.VOCAL_DEREVERB_KEY),
                settings.intOf(
                        dev.t1m3.qplayer.settings.SettingsCatalog.VOCAL_DEREVERB_FLOOR_KEY));
    }

    /** 去混响参数的纯函数。 */
    static dev.t1m3.qplayer.audio.studio.DereverbParams composeDereverb(int amountPercent,
                                                                        int floorDb) {
        if (amountPercent <= 0) {
            return dev.t1m3.qplayer.audio.studio.DereverbParams.off();
        }
        return dev.t1m3.qplayer.audio.studio.DereverbParams.of(
                Math.min(100, amountPercent) / 100.0, floorDb, 1.0);
    }

    /** 手动点位开关。 */
    public boolean manualCueEnabled() {
        return settings != null && settings.bool(
                dev.t1m3.qplayer.settings.SettingsCatalog.AUTOMIX_MANUAL_CUE_KEY);
    }

    /** 手动融合点（秒；0 = 不覆盖这一侧）。 */
    public double manualOutroSecs() {
        if (settings == null) {
            return 0.0;
        }
        return settings.intOf(dev.t1m3.qplayer.settings.SettingsCatalog
                .AUTOMIX_MANUAL_OUTRO_SECS_KEY);
    }

    /** 手动切入点（秒；0 = 不覆盖这一侧）。 */
    public double manualIntroSecs() {
        if (settings == null) {
            return 0.0;
        }
        return settings.intOf(dev.t1m3.qplayer.settings.SettingsCatalog
                .AUTOMIX_MANUAL_INTRO_SECS_KEY);
    }

    /** 「自动点位」那一档的准备余量（秒）：要额外扫两首整曲，比默认那 30 秒高一个量级。 */
    /**
     * 自动点位的准备余量（秒）。**2026-10-06 第一阶段工作五：90 → 360**。
     *
     * <p>为什么跟着抬：用户规格把 {@code Director.PREPARE_MARGIN_SECS} 从 30 s 调到 180 s，
     * 而这一档的**意图**是"开了自动点位要扫两首整曲，余量必须明显更大"
     * （判据钉的是 {@code autoCue >= plain × 2}）。90 在新基数下反而比普通档还小——
     * 那不是"更大"，是把关系弄反了。按 2 倍关系取 360。
     */
    static final long AUTOCUE_PREPARE_MARGIN_SECS = 360L;

    @Override
    public long prepareMarginSecs() {
        return prepareMarginSecsFor(automixEnabled(), automixMode(), automixStyle(), automixAutoCue());
    }

    /**
     * 准备余量的<strong>纯函数</strong>（{@code prepareMarginSecs()} 只是它的一层读法）。
     *
     * <p>抽出来是为了让「这张表」能被直接喂参数核对（与 {@code composeStudio} 同一个理由）：
     * 这一项写错的表现不是「慢一点」，而是<strong>这一次过渡完全不发生</strong>——
     * {@code Director} 在触发点没有准备结果时刻意不开始过渡（见 {@code beginTransition}），
     * 用户听到的是「曲终直接硬接」。而它太隐蔽：日志里只有一条「跳过了…」或者什么都没有。
     *
     * <p>它回答的是「这一档的准备要跑多久」，所以每一档按<strong>最贵的那条路</strong>算：
     *
     * <ul>
     *   <li>{@link #AUTOCUE_PREPARE_MARGIN_SECS}：自动点位，以及 <b>AI 模式的自动档</b>
     *       （它挑接法时要把声波重合当候选方案比一次，用的是同一套整曲包络）——
     *       两者都要把两首歌<strong>各再解一遍</strong>扫成包络。手机上进场曲常常还是那条
     *       带签名的直链（预取的音频没落盘），这一次扫描等于把整首歌再下一次；</li>
     *   <li>{@code Director.PREPARE_MARGIN_SECS}（30 s）：其余各档——只读两首的尾段/头段。</li>
     * </ul>
     *
     * @param enabled 自动混音总开关
     * @param mode    {@code SettingsCatalog.AUTOMIX_MODE_*} 的数值
     * @param style   {@code MixStyle.UI_ORDER} 的下标
     * @param autoCue 自动点位开关
     */
    static long prepareMarginSecsFor(boolean enabled, int mode, int style, boolean autoCue) {
        if (!enabled) {
            // 开关关着：一次分析都不跑，余量取默认值（它此时只影响「什么时候问一次」）
            return dev.t1m3.qplayer.audio.mix.Director.PREPARE_MARGIN_SECS;
        }
        // 这里原先还有一档 150 s：生成式（AI 模式 + 生成式风格）要跑模型推理，
        // 实测 60~70 s 一段桥接。**2026-10-01 生成式整套删除**，这一档随之去掉。
        //
        // AI 模式的**自动**档也会扫两首整曲：它在挑接法时要把「声波重合」也当候选方案
        // 比一次，而那一比用的是与自动点位同一套整曲包络（见 AiMix.prepare 的
        // 「AI 方案比较」）。漏了这一条的表现与自动点位那条一模一样——
        // 准备没赶上触发点，这一次过渡根本不发生。
        boolean aiAuto = mode == SettingsCatalog.AUTOMIX_MODE_AI
                && MixStyle.fromUiIndex(style) == MixStyle.AUTO;
        if (autoCue || aiAuto) {
            return AUTOCUE_PREPARE_MARGIN_SECS;
        }
        return dev.t1m3.qplayer.audio.mix.Director.PREPARE_MARGIN_SECS;
    }

    /**
     * 「自动点位」（{@code SettingsCatalog.AUTOMIX_AUTO_CUE_KEY}）。
     *
     * <p>读不到设置时是 <strong>false</strong>：它要扫两首整曲（多花几秒 CPU），
     * 属于「用户没说要就不做」的那一类——与总开关、以及「不分离」同一条纪律。
     */
    @Override
    public boolean automixAutoCue() {
        return settings != null && settings.bool(SettingsCatalog.AUTOMIX_AUTO_CUE_KEY);
    }

    /**
     * 「模式 → 接法」那张表（docs/audio-engine.md §4）的<strong>唯一落点</strong>。
     *
     * <p>做成 {@code static} 是为了让 {@link EngineSettings#transitionFactory(int, int)}
     * 的默认实现与这里共用同一张表，而不是各写一份（两份迟早对不上，
     * 而症状是「用 AutomixSettings 时对、用别的实现时静默退化」）。
     *
     * <p>取值全部按<strong>不可信</strong>处理：模式越界、风格越界、窗口是负数或大得离谱，
     * 都在这里夹到一个安全的接法上。窗口上限与 {@link #windowSecs} 一致——
     * 默认实现那条路上没有 {@code SettingsCore} 帮忙夹，所以夹紧必须落在这一层。
     *
     * <p><strong>总开关在这一层落地</strong>：{@code enabled == false} 时无论模式选了
     * 哪一档都造无缝拼接（窗口 0）。窗口为 0 是引擎唯一的「什么都不做」信号——
     * {@code Director.shouldPrepare()} 与 {@code shouldBeginTransition()} 第一句就是
     * 看它，所以「关掉总开关 = 一次分析都不跑、换曲是硬接」是由这一处保证的，
     * 不靠调用方自觉。
     *
     * @param enabled        自动混音总开关；false 时忽略后面所有接法参数
     * @param mode           0 无缝 / 1 交叉淡化 / 2 声波重合 / 3 AI（越界按无缝）
     * @param style          {@code MixStyle.UI_ORDER} 的下标（越界由 {@code fromUiIndex} 退化成自动）
     * @param crossfadeSecs  交叉淡化窗口（秒，夹到 [0, {@value #MAX_WINDOW_SECS}]）
     * @param harmonicSecs   声波重合窗口（秒，同上）
     * @param vocalMode      融合阶段的人声怎么处理：{@link StemSeparators#OFF 不分离} /
     *                       {@link StemSeparators#DSP DSP} / {@link StemSeparators#AI AI（模型）}
     * @param rate           输出采样率
     * @param channels       输出声道数
     */
    static TransitionFactory factoryFor(final boolean enabled, final int mode, final int style,
                                        float crossfadeSecs, float harmonicSecs, final int vocalMode,
                                        final int rate, final int channels) {
        return factoryFor(enabled, mode, style, crossfadeSecs, harmonicSecs, vocalMode,
                /* autoCue= */ false, rate, channels);
    }

    static TransitionFactory factoryFor(final boolean enabled, final int mode, final int style,
                                        float crossfadeSecs, float harmonicSecs, final int vocalMode,
                                        final boolean autoCue, final int rate, final int channels) {
        return factoryFor(enabled, mode, style, crossfadeSecs, harmonicSecs, vocalMode, autoCue,
                rate, channels, dev.t1m3.qplayer.audio.studio.AutotuneParams.off(),
                dev.t1m3.qplayer.audio.studio.DereverbParams.off());
    }

    /**
     * 完整版：再带上「人声后期」（修跑调 / 去混响）那一套。
     *
     * <p>{@code vocalAutotune}/{@code vocalDereverb} 只被用来造
     * {@link VocalSeparationTransition}——那一层是唯一真正拿得到人声轨的地方。
     */
    static TransitionFactory factoryFor(final boolean enabled, final int mode, final int style,
                                        float crossfadeSecs, float harmonicSecs, final int vocalMode,
                                        final boolean autoCue, final int rate, final int channels,
                                        final dev.t1m3.qplayer.audio.studio.AutotuneParams
                                                vocalAutotune,
                                        final dev.t1m3.qplayer.audio.studio.DereverbParams
                                                vocalDereverb) {
        return factoryFor(enabled, mode, style, crossfadeSecs, harmonicSecs, vocalMode, autoCue,
                rate, channels, vocalAutotune, vocalDereverb, false, 0.0, 0.0);
    }

    /** 完整版：再带上「手动点位」（P4）。 */
    static TransitionFactory factoryFor(final boolean enabled, final int mode, final int style,
                                        float crossfadeSecs, float harmonicSecs, final int vocalMode,
                                        final boolean autoCue, final int rate, final int channels,
                                        final dev.t1m3.qplayer.audio.studio.AutotuneParams
                                                vocalAutotune,
                                        final dev.t1m3.qplayer.audio.studio.DereverbParams
                                                vocalDereverb,
                                        final boolean manualCue, final double manualOutroSecs,
                                        final double manualIntroSecs) {
        return factoryFor(enabled, mode, style, crossfadeSecs, harmonicSecs, vocalMode, autoCue,
                rate, channels, vocalAutotune, vocalDereverb, manualCue, manualOutroSecs,
                manualIntroSecs, null, null, null);
    }

    /**
     * 完整版 + <strong>窗口的「现读」供应商</strong>。
     *
     * <p>窗口时长（交叉淡化 / 声波重合）在造工厂这一刻会被抄成一个数，而「自动点位」
     * 要到这次过渡<strong>准备</strong>时才挑点——中间隔着整首歌。用户在播放期间把滑块改了，
     * 那些抄下来的数就是旧的。两个供应商让那一层每次都能读到<strong>当前</strong>的值
     * （见 {@code AutoCueTransition.liveWindowSecs}）。
     *
     * <p>只有真正拿得到 {@code SettingsCore} 的那条路会传非 {@code null}
     * （{@code AutomixSettings#factory()}）；测试与「只关心模式→接法那张表」的调用方传
     * {@code null}，那时行为与从前完全一样。
     */
    static TransitionFactory factoryFor(final boolean enabled, final int mode, final int style,
                                        float crossfadeSecs, float harmonicSecs, final int vocalMode,
                                        final boolean autoCue, final int rate, final int channels,
                                        final dev.t1m3.qplayer.audio.studio.AutotuneParams
                                                vocalAutotune,
                                        final dev.t1m3.qplayer.audio.studio.DereverbParams
                                                vocalDereverb,
                                        final boolean manualCue, final double manualOutroSecs,
                                        final double manualIntroSecs,
                                        final java.util.function.DoubleSupplier liveCrossfade,
                                        final java.util.function.DoubleSupplier liveHarmonic,
                                        final String tuningSpec) {
        // 总开关关着：一律无缝拼接。这一句必须在最前面——它就是「关掉时不做任何分析」
        // 的落点（窗口 0 是 Director 唯一的「什么都不做」信号）。
        final int effectiveMode = enabled ? mode : SettingsCatalog.AUTOMIX_MODE_GAPLESS;
        final MixStyle mixStyle = MixStyle.fromUiIndex(style);
        // P2/P4/P5 的旋钮：设置串在这一刻解析一次（空/写错 → 全默认），
        // 与上面两个窗口供应商同一套「拿不到设置就退回从前的行为」的写法。
        final dev.t1m3.qplayer.audio.mix.MixTuning tuning = parseTuning(tuningSpec);
        final float crossfade = windowSecs(crossfadeSecs);
        final float harmonic = windowSecs(harmonicSecs);
        // 人声档同样按**不可信**处理：越界的值一律当「不分离」——那是唯一不需要任何
        // 额外东西的档，也是唯一不会「悄悄用了另一种算法」的档。
        // 总开关关着时也不套：无缝拼接压根没有可融合的片段（下面那条判断会兜住，
        // 但把话写在前面更清楚：关掉 = 这条流水线在渲染路径上一个采样都不碰）。
        final int vocal = enabled && StemSeparators.isKnownMode(vocalMode)
                ? vocalMode : StemSeparators.OFF;
        return new TransitionFactory() {
            @Override
            public Transition create() {
                Transition base;
                switch (effectiveMode) {
                    case SettingsCatalog.AUTOMIX_MODE_CROSSFADE:
                        base = new Crossfade(crossfade);
                        break;
                    case SettingsCatalog.AUTOMIX_MODE_HARMONIC:
                        base = new HarmonicBlend(rate, channels, harmonic);
                        break;
                    case SettingsCatalog.AUTOMIX_MODE_AI:
                        // AI 自动档会把「声波重合」也当候选方案，所以这里把**现读**的
                        // 声波重合窗口交给它（用户播放期间改滑块也要读到新的那一个）
                        //
                        // **剔除「人声不撞」**（2026-10-09 ✓ 用户：「改成剔除」✓）：
                        // 真机实测（同一对 ✓）：这一档让 B 的进场落在极轻处（B=−32.9 dB ✗，A=−7.5 ✗）
                        // ⇒ 融合末尾落差 15 dB ⇒ 听感「A 啪一下没了」✓；表现模式（B 从 72.1s 进、
                        // B=−10.5 ✓ 与 A 同响）完全正常 ✓。
                        // ⇒ AI 档若在设置里拿到这一档，就**交给打分器在其余档里挑** ✓（= 剔除 ✓），
                        //   而不是硬降级到朴素交叠 ✗。**手动选这一档照旧可用** ✓。
                        base = new AiMix(mixStyle == MixStyle.NO_VOCAL_CLASH ? MixStyle.AUTO : mixStyle,
                                rate, channels, tuning, liveHarmonic);
                        break;
                    default:
                        // 无缝拼接：窗口 0，一个采样都不改写——唯一与位完美兼容的那一档
                        base = Gapless.INSTANCE;
                        break;
                }
                // 「去人声」是一层**包装**（它只改素材，接法还是上面那个），所以三种模式与
                // AI 模式的五种接法自动都支持，将来加接法也不用动这里。
                //
                // 两个不套的情况：
                // ① vocalMode == OFF（不分离）——**这一档完全不套**，接法与改造前一模一样
                //    （「不分离」不是一个打不开的分离器，是压根没有这一层）；
                // ② 无缝拼接——它的窗口是 0（什么都不渲染），套上去除了多读一次文件、
                //    多跑一次分离之外什么都不会发生，而那是这条路上最贵的一步。
                // ---- 谁能挑到「声波重合」，谁就必须被「只移伴奏」那一层包住 ----
                //
                // 移调意图由 {@code HarmonicBlend} 算出来（{@code plan.pitch}），
                // 而它**绝不能落到 deck 上**：deck 的移调加在整个混音上，人声会跟着走
                // （用户实测两次，机理见 HarmonicBlend 的类注释）。
                // 两条路都能挑到它：① 顶层模式 2；② AI 自动档（{@code AiMix.pitchAt}
                // 委托给它自己的 {@code HarmonicBlend}）——②原先没有任何人声保护，
                // 于是「用户开着 DSP 也照样听到 A 的人声变调」。
                //
                // 用户没开人声分离时这一层**自动用 DSP**（毫秒级、不依赖外部文件）：
                // 人声保护优先于「不许悄悄换算法」——那就别悄悄，{@code autoSeparated}
                // 留记号、{@code plan.note} 与日志里写明。
                final Transition nested = maybeManualCue(maybeAutoCue(base));
                final boolean autoSeparate = vocal == StemSeparators.OFF;
                final int protectVocal = autoSeparate ? StemSeparators.DSP : vocal;
                // **AI 档默认不套「只移伴奏」那一层**（2026-10-09 ✓ 用户实测决定性线索：「我只把 AI 自动模式
                // 换成表现模式、剩下都没动（自动点位也开着）⇒ 就没问题」✓）⇒ 差别只在这层 ✓：
                // AI 档会额外套 `VocalAwarePitchTransition`（靠**推测**人声在哪来决定移谁 ✗）
                // ⇒ 判错就把 A 的人声整段拿走 ✓（听感正是「融合期结尾人声突然没了 / A 瞬间消失」✓）。
                // 表现模式不套这层 ⇒ 正常 ✓。默认**关** ✓（= 与表现模式同构 ✓）；`setHarmonicInAiMode(true)`
                // 恢复从前 ✓。HARMONIC（声波重合）档**照旧** ✓。
                final boolean canPickHarmonic =
                        effectiveMode == SettingsCatalog.AUTOMIX_MODE_HARMONIC
                                || (harmonicInAiMode
                                    && effectiveMode == SettingsCatalog.AUTOMIX_MODE_AI
                                    && mixStyle == MixStyle.AUTO);
                // **素材源接线**（2026-10-06 修"人声速度不一致"）：自动点位那一层要自己读一段
                // PCM 做变速/循环的预渲染，而渲染路径上的素材在这一档是**去过人声**的。
                // 让它读"去过人声的那一份"，两边的时间轴才一致；不装的话它读原始整轨 ⇒
                // 窗口里 A、B 的人声又回来（还各带 −3.2%/+3.3% 的速率）。
                // 不分离（autoSeparate）时显式卸掉 ⇒ 回到"直接读文件"，与从前逐位相同。
                final Transition separated = autoSeparate
                        ? nested
                        : new VocalSeparationTransition(nested, rate, channels, vocal,
                                VocalSeparationTransition.DEFAULT_BUDGET_SECS,
                                vocalAutotune, vocalDereverb);
                // **这里曾经接过"预先去掉人声"的素材源，已撤掉**（2026-10-07 用户实测：
                // 「我选择任何模式（自动 表现模式 学习EQ）在过渡 skydive->春めく 全炸了……
                //   现在过渡段音频被降很低」）。
                //
                // 原因是**双重去人声**：素材源把窗口素材预先减掉人声，而外面这层
                // {@link VocalSeparationTransition} 在**渲染时**又按原速人声轨减一次
                // ——两次相减在人声频段上是**相位抵消**，于是窗口里电平被压下去。
                // 它作用在**窗口素材**上、与接法无关，所以三种接法一起坏（症状正是"全炸了"）。
                //
                // 钩子（{@code AutoCueTransition.installMaterialSource}）与那个工具函数都留着，
                // 但要接的话必须先解决"谁负责减"这件事——见下面那条注释里的方向。
                dev.t1m3.qplayer.audio.mix.AutoCueTransition.installMaterialSource(null);

                if (canPickHarmonic && base != Gapless.INSTANCE) {
                    if (effectiveMode == SettingsCatalog.AUTOMIX_MODE_HARMONIC) {
                        // 声波重合：**只要**这一层（它自己分轨、只移伴奏）；
                        // 再套通用那一层等于白跑一遍分离。
                        return new VocalAwarePitchTransition(nested, rate, channels,
                                protectVocal, autoSeparate);
                    }
                    // AI 自动档：里面那层留着（AI 挑别的接法时靠它做去人声与人声后期），
                    // 外面再包一层负责「只移伴奏」。代价是 AI 真挑中声波重合时两次分离
                    // （DSP 毫秒级；选了 AI 模型分轨的配置本来就是秒级，如实记在这里），
                    // 换来的是这条路再也不会动到人声。
                    return new VocalAwarePitchTransition(separated, rate, channels,
                            protectVocal, autoSeparate);
                }

                if (autoSeparate || base == Gapless.INSTANCE) {
                    // 「自动点位」只改融合点，不改素材：它套在接法外面（与去人声同一层），
                    // 窗口 0 的无缝拼接没有可融合的片段，不套。
                    return nested;
                }
                // 用哪一档由 vocal 带进去，具体实现由 StemSeparators 决定
                // ——包装层不认识任何具体实现（见 VocalSeparationTransition 的类注释）。
                //
                // **嵌套顺序不能反：自动点位在里面、去人声在外面。**
                //
                // 去人声这一层要**预先读好**进场曲那一段素材（`separateInto(intro,
                // plan.skipSecs, window)`，渲染路径上一次盘都不许读），而它读的位置就是
                // 内层计划给的 `skipSecs`。自动点位恰恰会把这个值改掉：它把进场点挪到曲中
                // 某个拍上。反过来套（自动点位在外）的话，去人声读到的是**旧**的进场点，
                // 于是融合期间听到的 B 与 deck 报的位置差着整整一个进场点——
                // 实测：进场点 2.8 s 时，听到的 B 比报的位置晚 2.87 s，
                // 而过渡一结束进度就会往前跳那么多（用户报的正是这个）。
                //
                // 谁改素材谁就该在最外层：它在里面那层**定完点**之后再读，读到的才对得上。
                //
                // **人声后期挂在这一层**：这里是全工程唯一真正拿得到「人声轨」的地方
                // （分轨就在它的 prepare 里发生）。修跑调/去混响只对单音素材有意义，
                // 所以只能作用在分离出来的那条人声上，不能作用在整条混音上。
                return separated;
            }

            /**
             * 自动点位那一层包装。窗口 0（无缝拼接）不套：没有可融合的片段，
             * 套上去只会白扫两首整曲。
             */
            /**
             * 手动点位那一层（P4 控制室的「调」）。
             *
             * <p>套在自动点位**外面**：两者都开时手动说了算——那是用户明确的意图，
             * 而自动点位只是「替他挑一个」。两者都是「只换点位」的包装，所以顺序在
             * 语义上都成立；挑这个顺序是因为「明确的意图压过推断」。
             */
            private Transition maybeManualCue(Transition base) {
                if (!manualCue || base == Gapless.INSTANCE) {
                    return base;
                }
                return new dev.t1m3.qplayer.audio.mix.ManualCue(base, manualOutroSecs,
                        manualIntroSecs);
            }

            private Transition maybeAutoCue(Transition base) {
                if (!autoCue || base == Gapless.INSTANCE) {
                    return base;
                }
                // 「现读」的那份窗口：按**当前模式**决定读哪个滑块。造接法是在开唱那一刻
                // （arm），而这一层要到这次过渡**准备**时才挑点——中间隔着整首歌，
                // 用户完全可能把滑块改了。用供应商就把这个时间差消掉
                // （见 AutoCueTransition.liveWindowSecs 的注释）。
                java.util.function.DoubleSupplier live = null;
                boolean tune = false;
                if (effectiveMode == SettingsCatalog.AUTOMIX_MODE_CROSSFADE) {
                    live = liveCrossfade;
                    // 交叉淡化同样允许分析按实际情况调整时长（2026-09-28 用户要的：
                    // 「让交叉淡化也按照分析调」）。语义仍是「用户设的那个值是**中心与默认**」：
                    // 候选是围绕它的一组（见 AutoCueTransition.windowCandidates），
                    // 而他设的那个值本身永远是候选之一——分析只是在他的偏好附近按素材挑一个。
                    tune = true;
                } else if (effectiveMode == SettingsCatalog.AUTOMIX_MODE_HARMONIC) {
                    live = liveHarmonic;
                    // 声波重合那一档同样（用户更早要的）：叠多久最合得上，
                    // 是分析能回答的问题，而不是只能靠他猜一个秒数
                    tune = true;
                }
                return new AutoCueTransition(base, rate, channels, live, tune, tuning);
            }
        };
    }

    /** 把一个窗口时长（秒）夹到 [0, {@value #MAX_WINDOW_SECS}]（{@link #windowSecs(String)} 的纯值版本）。 */
    static float windowSecs(float secs) {
        if (!(secs > 0f)) {
            // 负数、0 与 NaN 都当「硬接」：NaN 用 > 比较会落到这里，正是我们要的
            return 0.0f;
        }
        return secs > MAX_WINDOW_SECS ? MAX_WINDOW_SECS : secs;
    }

    /**
     * 总开关<strong>打开</strong>时的 {@link #factoryFor(boolean, int, int, float, float, int, int, int)}
     * ——给「只关心模式 → 接法那张表」的调用方（测试、别的宿主）用。
     *
     * <p>它<b>不是</b>「默认打开」：默认值只有一处（{@link EngineSettings#automixEnabled()}，
     * {@link AutomixSettings} 读设置文件里的键）。这里写成显式的 {@code true}
     * 是为了让「这份调用刻意绕开总开关」在调用点上看得见。
     */
    static TransitionFactory factoryFor(final int mode, final int style, float crossfadeSecs,
                                        float harmonicSecs, final int vocalMode,
                                        final int rate, final int channels) {
        return factoryFor(true, mode, style, crossfadeSecs, harmonicSecs, vocalMode, rate, channels);
    }

    /**
     * 当前设置的一行说明（日志与诊断用）。
     *
     * <p>总开关关着时<strong>先说这一句</strong>：那种状态下模式选的是哪一档都无所谓，
     * 换曲一定是硬接，把「交叉淡化 6 s」放在前面就是在撒谎。
     */
    public String describe() {
        if (!automixEnabled()) {
            return "已关闭（换曲为硬接）· 存着的接法：" + modeLabel();
        }
        return modeLabel();
    }

    /** 当前模式的一行说明（不看总开关；{@link #describe()} 的那一半纯函数部分）。 */
    private String modeLabel() {
        switch (automixMode()) {
            case SettingsCatalog.AUTOMIX_MODE_CROSSFADE:
                return "交叉淡化 " + crossfadeSecs() + " s";
            case SettingsCatalog.AUTOMIX_MODE_HARMONIC:
                return "声波重合 " + harmonicSecs() + " s";
            case SettingsCatalog.AUTOMIX_MODE_AI:
                return "AI 模式 · " + MixStyle.fromUiIndex(automixStyle()).label();
            default:
                return "无缝拼接";
        }
    }

    /** 读一个滑块键并夹到 [0, MAX_WINDOW_SECS]。 */
    private float windowSecs(String key) {
        if (settings == null) {
            return 0.0f;
        }
        return windowSecs((float) settings.intOf(key));
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) {
            return lo;
        }
        return v > hi ? hi : v;
    }

    @Override
    public String toString() {
        return "AutomixSettings{" + describe()
                + ", bitPerfect=" + bitPerfect()
                + ", rateFollow=" + rateFollow() + '}';
    }
}
