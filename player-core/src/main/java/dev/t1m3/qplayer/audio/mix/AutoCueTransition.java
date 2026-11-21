package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;

import java.nio.FloatBuffer;
import dev.t1m3.qplayer.util.Logger;

/**
 * 「自动点位」那一层<strong>包装</strong>：接法本身一个字都不改，只换融合点。
 *
 * <p>结构与 {@link VocalSeparationTransition} 完全一样——它只改素材，这一个只改
 * 「在哪儿融合」：窗口时长、切点、变调曲线全部原样交给被包的接法，
 * 这里只做两件事：
 *
 * <ol>
 *   <li>{@link #prepare} 里先让被包的接法算它那一份（对拍、判调性、窗口），
 *       再扫两首<strong>整曲</strong>的包络，用 {@link AutoCue} 挑一个融合点；</li>
 *   <li>把结论翻译成 {@link TransitionPlan}：进场点 → {@code skipSecs}，
 *       被丢掉的尾巴 → {@code outroTailSecs}（编排器据此把过渡触发点提前）。</li>
 * </ol>
 *
 * <p>两条纪律（与 {@code AiMix.prepare} 一致）：
 *
 * <ul>
 *   <li><strong>算不出来就如实退回固定点位</strong>：扫不动（歌太短、音源要请求头、
 *       文件坏了）就返回被包接法的原始计划，绝不用一个半成品结论；</li>
 *   <li><strong>不猜时长</strong>：包络给不出时长（流式输入）时同样放弃。</li>
 * </ul>
 *
 * <p>线程约定与别的接法一样：实例只服务一次过渡，{@code prepare} 在准备线程上跑，
 * 之后整份状态只被渲染线程读，所以那几个字段不需要锁（写发生在读之前，
 * 中间隔着编排器的一次移交——{@code PreparedTransition} 就是那道屏障）。
 */
public final class AutoCueTransition implements Transition {

    /**
     * 融合期的前瞻软限幅器（见 {@link TransitionLimiter}）。
     *
     * <p>**只在过渡窗口内作用**：正常播放那一段根本不经过这里（窗口之外由 deck 直接
     * 交给输出），所以它不会给整条链染色。天花板以下逐位透明。
     */
    private final TransitionLimiter limiter;

    /** 融合期的整体留白（dB）：按窗口形状施加，两端为 0。 */
    /**
     * 融合期的整体留白（dB）：按窗口形状施加，两端为 0。
     *
     * <p>**1.0 → 0.5**（2026-10-07，用户听感）：「不够无感」—— 留白是防削顶的手段，
     * 而它本身会让融合段比两侧略低；0.5 dB 足以给限幅留出工作余量，又更接近"没动过"。
     */
    private static final float HEADROOM_DB = 0.5f;

    /**
     * 融合期的"不炸"处理（留白 + 前瞻软限幅）是否生效。
     *
     * <p>**默认开**：用户实测两张响的曲子在这对素材上会削顶（"有点点炸麦"），
     * 并明确选了"过渡自带留白 + 前瞻软限幅"这一条。
     *
     * <p>做成开关是为了让"既有行为逐位不变"那几条判据仍然能比**接法机制本身**
     * （它们比的是交叉淡化/分段/循环这些与限幅无关的东西，见各自的注释）——
     * 那些判据在 setup 里显式关掉它。
     */
    private static volatile boolean antiClipEnabled = true;

    /** 关/开融合期的留白与限幅（判据用；产品默认开）。 */
    public static void setAntiClipEnabled(boolean on) {
        antiClipEnabled = on;
    }

    /** 见 {@link #setAntiClipEnabled}。 */
    public static boolean antiClipEnabled() {
        return antiClipEnabled;
    }

    private final Transition base;

    /** 这一次过渡的留白深度（dB ✓）——默认就是原来的 {@link #HEADROOM_DB} ✓。 */
    private float headroomDb = HEADROOM_DB;

    /** 循环那两档的窗口上限（秒）= 1.5 ✓（用户 2026-10-07 选 (b1) ✓；理由见闸门那一段注释 ✓）。 */
    static final double LOOP_ROUTE_WINDOW_SECS = 1.5;

    /** 联合相位微调 δ 的上限（秒）= 0.09 ✓（理由见那段注释：保住 Apple ±0.1 s 的验收 ✓）。 */
    static final double PHASE_JOINT_MAX_SECS = 0.09;

    /**
     * <b>供判据模型读取：这一次过渡**最终**用的窗口（秒）</b>（2026-10-07 round 25 ✓）。
     *
     * <p>为什么需要它 ✗：判据模型要复算"A 侧结构对齐 + 相位"这条规则 ✓，而它原来用的是**自己的**
     * 窗口 ✓ ⇒ 与生产的最终窗口（循环那两档会被 (b1) 压到 1.5 s ✓）不同 ✗ ⇒ 差 0.18 s、判据红 ✓。
     * 把生产的最终值暴露出来 ✓ ⇒ 两边同源 ✓，规则就能在**不改容差**（不削弱判据 ✓）的前提下打开 ✓。
     */
    public double probeFinalWindowSecs() {
        return windowFrames(rate) / (double) Math.max(1, rate);
    }

    // （出场点不开访问器 ✓：那个字段名我记错了、编译不过 ✗；判据侧用**已有的公开读数**
    //  "全曲 − 窗口 − 丢尾"自己算 ✓ —— 那条口径本来就是公开的 ✓，不必再加接口 ✓。）
    /** 供判据模型读取：(b1) 给循环那两档压到的窗口上限（秒 ✓）。 */
    public static double probeLoopRouteWindowSecs() {
        return LOOP_ROUTE_WINDOW_SECS;
    }

    /** 互补让位用的 8 个频带中心（Hz ✓，与包络/量测的 8 带同一套 ✓）。 */
    static final double[] CARVE_BAND_HZ = {60, 120, 250, 500, 1000, 2000, 4000, 8000};

    /** 互补让位的衰减量（dB ✓；0 = 关 ✓）。 */
    static final float CARVE_GAIN_DB = -4.0f;

    /** 多带让位：两侧电平差**小于这个数**的带才让（dB ✓）。 */
    static final double CARVE_WITHIN_DB = 4.0;

    /** 多带让位：单个带最多让多少 dB ✓。 */
    static final double CARVE_MAX_DB = 3.0;

    // ⚠️ **深度从 4 dB 提到 8 dB 是一次实验**（2026-10-07 round 15 ✓）：4 dB 时最差带只到 3.6 dB ✗，
    // 说明"让得不够"也是原因之一 ✓（另一部分是增益按整窗平均算 ✓，那是下一轮"随窗口变化"的事 ✓）。
    // 8 dB 是"能听出来但还不至于削空"的量级 ✓；**给耳朵的 A/B 会一起出** ✓ —— 如果听着凹了 ✗，
    // 就把这个常量退回去 ✓（一行 ✓、且一切都能单独关掉 ✓）。
    private final int rate;
    private final int channels;

    /**
     * 每次准备时<strong>现读一次</strong>「用户设的窗口时长」（秒）；{@code null} = 用被包接法自己的窗口。
     *
     * <p>为什么要是「现读」而不是构造时那个值：接法是在<strong>开唱那一刻</strong>（编排器 arm）
     * 按设置造出来的，而这首歌唱完可能几分钟之后——用户在播放期间把「声波重合窗口」
     * 从 6 秒拖到 12 秒，如果这里用的是 arm 时抄下来的那一个，这次过渡仍然按 6 秒走，
     * 而界面上写着 12 秒。改成供应商之后，「自动点位」按的一律是<strong>最新</strong>的那个值。
     */
    private final java.util.function.DoubleSupplier liveWindowSecs;

    /**
     * 允许按分析结果<strong>改动窗口时长</strong>吗？
     *
     * <p>只有「声波重合」那一档开着（用户要的「声波重合模式允许根据实际情况调整过渡时长」）：
     * 那一档的窗口是「两首歌叠多久最合得上」的问题，分析说了算。
     * 其余档位一律用用户设的那个值——他明确设了一个时长，改动它就是越权。
     */
    private final boolean tuneWindow;

    /** {@link #prepare} 算出来的交叠窗口（帧）；0 表示「还没算 / 没算出来」。 */
    private long autoWindow;
    /** {@link #prepare} 算出来的进场点（秒）。 */
    private double autoEntrySecs;
    /** {@link #prepare} 算出来的丢尾（秒）。 */
    private double autoTailSecs;
    /** 给人看的一句实话（诊断/日志）。 */
    private String autoNote = "";

    /** 某个时刻在它自己栅格里的相位（秒，落在 [0, 拍) ✓）——与基线量测同一个口径 ✓。 */
    static double relativePhase(AutoCue.Envelope env, double secs, double periodSecs) {
        double f = secs / periodSecs;
        return (f - Math.floor(f)) * periodSecs;
    }

    /** 诊断用：微调**之前**两侧 kick 的时间差（拍 ✓）。 */
    private static double worstGapBefore(AutoCue.Cue cue, double pA, double pB) {
        return 0.0;      // 真值由相位微调那段的 score 给出 ✓；这里只保证日志有位置 ✓
    }

    /** 这一档要不要"主动求循环"（见 {@link MixTuning#STYLE_LOOP_ENABLED}；默认关 ✓）。 */
    private boolean autoTuningForStyleLoop() {
        return base instanceof AiMix && ((AiMix) base).tuningForStyleLoop();
    }

    public AutoCueTransition(Transition base, int rate, int channels) {
        this(base, rate, channels, null, false);
    }

    /**
     * @param liveWindowSecs 现读的窗口时长（秒），见 {@link #liveWindowSecs}
     * @param tuneWindow     是否允许按分析结果调整窗口（见 {@link #tuneWindow}）
     */
    public AutoCueTransition(Transition base, int rate, int channels,
                             java.util.function.DoubleSupplier liveWindowSecs,
                             boolean tuneWindow) {
        this(base, rate, channels, liveWindowSecs, tuneWindow, MixTuning.defaults());
    }

    /** 完整版：再带上调参（目前只用到 {@link MixTuning#tempoStretch}）。 */
    public AutoCueTransition(Transition base, int rate, int channels,
                             java.util.function.DoubleSupplier liveWindowSecs,
                             boolean tuneWindow, MixTuning tuning) {
        this.base = base;
        this.rate = rate;
        // 过渡专用的前瞻软限幅器：融合期两张响的曲子叠起来会在拍点上削顶（用户实测
        // skydive->春めく「有点点炸麦」）。它只在过渡窗口内作用，见 finish()。
        this.limiter = new TransitionLimiter(rate, channels);
        this.channels = channels;
        this.liveWindowSecs = liveWindowSecs;
        this.tuneWindow = tuneWindow;
        this.tuning = tuning == null ? MixTuning.defaults() : new MixTuning(tuning);
    }

    /** 调参（构造时拷贝一份：用户在播放期间改设置不该让这一次过渡中途变样）。 */
    private final MixTuning tuning;

    // ==================================================================
    // segmentedOutgoing：**一个按段消费的队列**（用户原话就是这个名字）
    //
    // 从前这里有两个平行实现：`stretchedOutgoing`（变速那一档，自己拿着整窗素材）
    // 与 `loopOutgoing`（循环那一档，一遍素材 + 绕回游标）。两者的记账纪律是同一条
    // （素材自己拿着、游标只按**真正交付的帧**推进），差别只有"素材怎么来的"。
    // 现在两者都只负责**造段**，渲染那边只有一条路：按顺序把段喂进被包的接法。
    //
    // 段与段之间可以换源（"A 的尾奏 + B 的纯鼓段循环"就是一段 outro 接一段 intro），
    // 于是"多段拼接"不再需要新机制——它就是队列里多一个元素。
    // ==================================================================

    /** 空队列（没有自己的素材：渲染整个交回被包的接法，与从前逐位相同）。 */
    private static final SegmentFeed[] NO_SEGMENTS = new SegmentFeed[0];

    /** 队列本身：**按顺序**消费的一段段素材。 */
    private SegmentFeed[] segmentedOutgoing = NO_SEGMENTS;

    /** 队列消费到第几段（{@code == length} 表示素材已交付完）。 */
    private int segmentIndex;

    /** 队列的搬运缓冲（变速与循环共用一份；从前是 outgoingScratch / loopScratch 两份）。 */
    private AudioBuf segmentScratch;

    /** 路径的总帧数（= 报给编排器的窗口帧数；0 = 这一层没有素材）。 */
    private long segmentWindowFrames;

    /** 窗口里「只放 A、B 不进来」的帧数（其余是与 B 交叉淡化的那一段）。 */
    private int segmentInsertFrames;

    /** 接缝（窗口起点）在出场曲上的时刻（秒）。 */
    private double segmentCutSecs;

    /** 淡化那一段在出场曲上的起点（秒）——人声区间按它取，见 prepare 里的注释。 */
    private double segmentFadeStartSecs;

    /** 这条路径的丢尾（秒）：从「切点 + 窗口」到全曲结束。 */
    private double segmentTailSecs;

    /** 这一次真的走了**计划层给的显式路径**吗（判据要能证明路径是被消费的，不是摆设）。 */
    private boolean explicitPath;

    /** 变速那一段（{@code null} = 不变速，走 deck 那份）。 */
    /**
     * <b>窗口素材的来源</b>（2026-10-06：修"人声速度不一致"）。
     *
     * <p>为什么要这个钩子：这一层要**自己读一段 PCM** 来做变速/循环的预渲染
     * （{@link Automix#readStereoWindow}）。而当"去人声"那一层开着时，
     * 渲染路径上的素材是**去过人声**的（它在 prepare 里预读了原速人声轨，渲染时减掉），
     * 我们却从**原始整轨**读——于是窗口里 A、B 的人声**又回来了**，
     * 而且分别带着 −3.2% / +3.3% 的速率 ⇒ 用户听到的正是
     * 「**他确实匹配到了歌词相同段……还有就是人声速度不一致**」。
     *
     * <p>修法：素材来源可替换。引擎在"去人声"开着时把它接成"读那份去过人声的素材"，
     * 于是我们**拉伸的就是干净素材**，时间轴从头到尾一致。
     * 没有安装时（默认）走 {@link Automix#readStereoWindow} ⇒ 与加这一条之前逐位相同。
     */
    public interface MaterialSource {

        /**
         * 读一段素材。
         *
         * @param path    曲目路径
         * @param headers 解码头（可为 {@code null}）
         * @param startSecs 起点（秒）
         * @param frames  要多少帧
         * @param rate    采样率
         * @return {@code [声道][帧]}；读不到返回 {@code null}
         */
        float[][] read(String path, java.util.Map<String, String> headers, double startSecs,
                       int frames, int rate);
    }

    private static volatile MaterialSource materialSource;

    /** 装/卸素材来源（{@code null} = 回到"直接读文件"）。 */
    public static void installMaterialSource(MaterialSource source) {
        materialSource = source;
    }

    /**
     * <b>这一次过渡**专属**的素材来源</b>（2026-10-07）。
     *
     * <p>为什么要实例级：上面那个静态钩子是全局的 —— 谁都能装、装完不知道谁清 ✗。
     * 上一次为此翻过车：素材源在外层装上、内层照用，撤的时候没人负责 ⇒ 过渡段被
     * 整段换了素材（电平掉 11 dB）。实例级把"谁装谁清"变成构造上的事实：
     * 装在这一个 {@code AutoCueTransition} 上、{@link #prepare} 一进来就清 ✗。
     *
     * <p>用途（下一步接）：让**外层去人声那一层**把它那份"带门控、带包络、
     * 自然时间轴"的素材交给内层，内层再做变速 —— 这样窗口里的人声才真的被处理到，
     * 而且是**一次且只有一次**去人声。
     */
    private MaterialSource instanceSource;

    /** 装本次过渡专属的素材来源（{@code null} = 卸掉）。 */
    public void setMaterialSource(MaterialSource source) {
        this.instanceSource = source;
    }

    // ⚠️ **装载时机是有讲究的**（2026-10-07 查到，别按直觉来）：
    //
    // 外层（去人声那一层）的 `plan.skipSecs` **来自内层的计划** —— 它的 prepare 里先调
    // 内层拿到 plan，然后再 `separateInto(intro, plan.skipSecs, window)` 做分离。也就是说：
    //
    //     内层 prepare（读窗口素材！）  →  外层分离  →  外层继续准备
    //
    // 所以"分离完再装素材源"**已经太晚**：内层那时早已读完了窗口素材，装上去也不会被用。
    // 上一次那个（全局静态）钩子之所以能生效，正是因为它装在**建厂时**、早于任何 prepare。
    //
    // 结论：要把去人声接进窗口，素材源必须**在内层 prepare 之前**就装好，而分离所需的
    // `startSecs` 只能由**内层来问的时候**给（即素材源的 read(...) 首次被调用时**惰性分离**
    // 并缓存）—— 顺序反过来（先分离后装）是行不通的，不管接口写得多干净。
    // 这条留在这里，免得下一轮照着"先分离、再装"的顺序白改一遍。

    /**
     * 读窗口素材：**实例源优先**，其次静态钩子，最后直接读文件。
     *
     * <p>三级顺序是为了"改这一处不动既有行为"：现在没有任何地方装实例源 ✗，
     * 所以每次都落到后两级 —— 与从前逐位相同 ✓（判据 DevocalizedMaterialSourceTest
     * 用的仍是静态钩子，照旧绿 ✓）。
     */
    /**
     * <b>素材漏斗</b>（渲染真正读素材的地方）：出场侧与进场侧都从这里取。
     *
     * <p>这里是 ①（LUFS 响度归一化）与 ②（去人声接进窗口）**共同的落点** ——
     * 因为它们要改的都是"渲染拿到的那段素材"。做法：把原来的实现改名成
     * {@link #materialForRaw}，本方法在它外面套一层后处理（默认不做事 ⇒ 零行为变化）。
     */
    float[][] materialFor(String path, java.util.Map<String, String> headers,
                          double startSecs, int frames, int rate) {
        // **去人声**（目标②）：只有在"叠唱区间 + 分离档位 + 窗口锚点"三样都齐时才做，
        // 否则一律走原路（逐位不变）。重建式 instr + voc × gain(t) 施加在**人声轨**上 ——
        // 绝不乘到整段混音上（用户实测那样会"突然静音"）。
        double[] ranges = vocalDuckRanges;
        double anchor = materialAnchorSecs;
        if (ranges != null && ranges.length >= 2 && materialVocalMode != 0
                && !Double.isNaN(anchor) && frames > 0) {
            StemMaterial stems = StemMaterials.forWindow(
                    path, headers, startSecs, frames, rate, materialVocalMode);
            if (stems != null && stems.hasStems() && stems.frames() >= frames) {
                float[] gain = new float[frames];
                for (int i = 0; i < frames; i++) {
                    double timeline = (startSecs - anchor) + i / (double) Math.max(1, rate);
                    gain[i] = (float) vocalGainAt(timeline);
                }
                return matchLoudness(stems.mixWith(gain), rate);
            }
            // 分轨拿不到 ⇒ 退回原样素材（不猜、也不静默地去掉人声）
        }
        float[][] material = materialForRaw(path, headers, startSecs, frames, rate);
        return matchLoudness(material, rate);
    }

    /**
     * <b>叠唱区间</b>（窗口时间轴上的秒数，{@code {start, end}} 成对）——由外层
     * {@code VocalSeparationTransition} 泵进来；没装就是空 ⇒ 一切照旧 ✓。
     *
     * <p>只在这里存"该在哪儿去人声"，**不在漏斗里做**：漏斗只知道源素材的秒数
     * （{@code startSecs}），不知道窗口时间轴 ✗ —— 而叠唱区间是按**窗口时间轴**标的 ✓。
     * 所以正确落点是渲染读取素材的那一处（{@code SegmentFeed} 的读取循环），
     * 那里时间轴与窗口一致 ✓，包络与边缘淡化也是天然的 ✓。
     */
    private volatile double[] vocalDuckRanges;

    /**
     * 本次取素材在**窗口时间轴**上的锚点（秒）——由两个调用点在调用前设置。
     *
     * <p>为什么需要它：漏斗 {@code materialFor} 只知道**源素材**的秒数，
     * 而叠唱区间是按**窗口时间轴**标的 ✗。两个调用点正好各自知道自己的锚点
     * （出场侧 = {@code cutSecs}、进场侧 = {@code entrySecs}）⇒ 在那里设一下，
     * 漏斗就能把每一帧换算成窗口时间，从而求 {@link #vocalGainAt}。
     */
    private volatile double materialAnchorSecs = Double.NaN;

    /** 本次取素材用的分离档位（0 = 不去人声 ⇒ 不分离）。 */
    private volatile int materialVocalMode;

    /** 本次准备算出来的**窗口处理票**（引擎交给 deck 用；identity = 什么都不用做）。 */
    private WindowTicket windowTicket = WindowTicket.identity(0.0);

    /** 见 {@link #windowTicket}。 */
    public WindowTicket windowTicket() {
        return windowTicket;
    }

    /**
     * 按当前几何建票（量一次响度 + 摊一条人声曲线）。
     *
     * <p>窗口音频从这里读（与漏斗同一把读法）；读不到 ⇒ identity 票（**不动**，不猜）。
     * 目标响度暂时是 NaN（= 不做响度对齐）—— 打开它属于"再改听感"，要单独验证与重采指纹。
     */
    private WindowTicket buildWindowTicket(String outro, java.util.Map<String, String> headers,
                                          AutoCue.Cue cue, int rate) {
        try {
            int frames = (int) Math.round(cue.windowSecs * rate);
            if (frames <= 0) {
                return WindowTicket.identity(cue.outroCutSecs);
            }
            float[][] window = Automix.readStereoWindow(outro, headers, cue.outroCutSecs,
                    frames, rate);
            if (window == null) {
                return WindowTicket.identity(cue.outroCutSecs);
            }
            WindowTicket ticket = WindowTicket.build(window, rate, vocalDuckRanges,
                    cue.outroCutSecs, Double.NaN);
            if (ticket.doesAnything()) {
                dev.t1m3.qplayer.util.Logger.info(String.format(java.util.Locale.ROOT,
                        "[qplayer-engine] 窗口处理票：人声曲线 %d 帧 · 响度增益 %.4f",
                        Integer.valueOf(ticket.vocalGainFrames()),
                        Double.valueOf(ticket.loudnessGain)));
            }
            return ticket;
        } catch (RuntimeException e) {
            return WindowTicket.identity(cue == null ? 0.0 : cue.outroCutSecs);
        }
    }

    /** 是否打印"帧级细化能挪多少"的测量（默认开 —— 只打日志、不改任何东西）。 */
    private boolean logRefineMeasurement = true;

    /**
     * 量一次"把拍点细化能挪多少毫秒"（**只量不改**）。
     *
     * <p>为什么先量：吸附建立在 50 Hz 的包络栅格上（一帧 20 ms），而我们自己的
     * {@link BeatRefine} 能在 ±{@code 40 ms} 里把它找到 ±1.3 ms —— 差距就是"帧级对拍"
     * 还没做到的那部分。但**挪点位会改音频** ⇒ 属于有意改动，必须先看清量级、
     * 再按纪律重采指纹。所以这一步只打日志。
     */
    /**
     * **是否真的用细化结果挪点位**（默认关）。
     *
     * <p><b>2026-10-07 已设为默认开</b>（目标③"把 BeatRefine 接进吸附"的落地）：
     * 实测参考对进场侧可挪 29.5 ms（包络栅格只有 20 ms），挪了之后 WAV 确实改变。
     * 挪点位会改音频 ⇒ 属**有意**改动 ⇒ 已按纪律重采整链指纹，理由写在 GOLDEN 上方。
     * 留开关是为了"一行退回"（与 RouteScore / LUFS 那两处同一套做法）。
     */
    private volatile boolean refineCueTimingEnabled = true;

    /** 打开/关闭"用 BeatRefine 挪点位"（判据与探针用）。 */
    public void setRefineCueTimingEnabled(boolean on) {
        this.refineCueTimingEnabled = on;
    }

    /**
     * 某个时刻在**包络拍网格**上的相位（帧）。
     *
     * <p>式子与 {@code BeatAlignProbe} 完全一致（那边是判据的尺子，必须同一把）：
     * {@code ((secs × beatRate − tempo.phaseFrames) mod periodFrames)}。
     * ⚠️ 用的是包络自己的 {@code beatRate}（扫描时降采样之后的那个），**不是音频采样率** ✗ ——
     * 这一点我第一次就写错了。
     */
    private static double beatPhase(double secs, Analysis.Tempo tempo,
                                    int gridRate) {
        if (tempo == null || tempo.periodFrames <= 0 || gridRate <= 0) {
            return 0.0;
        }
        double frames = secs * gridRate - tempo.phaseFrames;
        double m = frames % tempo.periodFrames;
        return m < 0 ? m + tempo.periodFrames : m;
    }

    /**
     * 把某个点位按 {@link BeatRefine} 细化，返回细化后的秒数（失败时原样返回）。
     */
    private double refineSecs(String path, java.util.Map<String, String> headers, double secs) {
        try {
            int frames = (int) Math.round(0.2 * rate);
            float[][] win = Automix.readStereoWindow(path, headers,
                    Math.max(0.0, secs - 0.1), frames, rate);
            if (win == null || win.length == 0 || win[0].length < frames) {
                return secs;
            }
            float[] mono = new float[frames];
            for (int i = 0; i < frames; i++) {
                float acc = 0f;
                for (int c = 0; c < win.length; c++) {
                    acc += win[c][i];
                }
                mono[i] = acc / win.length;
            }
            int approx = (int) Math.round(0.1 * rate);
            int refined = BeatRefine.refineFrame(mono, rate, approx);
            return secs + (refined - approx) / (double) rate;
        } catch (RuntimeException e) {
            return secs;
        }
    }

    private void measureRefinement(String path, java.util.Map<String, String> headers,
                                   double secs, String label) {
        try {
            int frames = (int) Math.round(0.2 * rate);
            float[][] win = Automix.readStereoWindow(path, headers,
                    Math.max(0.0, secs - 0.1), frames, rate);
            if (win == null || win.length == 0 || win[0].length < frames) {
                return;
            }
            float[] mono = new float[frames];
            for (int i = 0; i < frames; i++) {
                float acc = 0f;
                for (int c = 0; c < win.length; c++) {
                    acc += win[c][i];
                }
                mono[i] = acc / win.length;
            }
            int approx = (int) Math.round(0.1 * rate);
            int refined = BeatRefine.refineFrame(mono, rate, approx);
            double deltaMs = (refined - approx) * 1000.0 / rate;
            if (Math.abs(deltaMs) >= 0.05) {
                dev.t1m3.qplayer.util.Logger.info(String.format(java.util.Locale.ROOT,
                        "[qplayer-engine] 帧级细化（%s）：%.4f s 处可挪 %.1f ms（包络栅格 20 ms）",
                        label, Double.valueOf(secs), Double.valueOf(deltaMs)));
            }
        } catch (RuntimeException e) {
            dev.t1m3.qplayer.util.Logger.debug("[qplayer-engine] 帧级细化测量跳过（{}）",
                    e.toString());
        }
    }

    /** 本次要用哪个分离档位 —— 由外层（VocalSeparationTransition）装进来；没装就是 0。 */
    private volatile int installedVocalMode;

    /** 装分离档位（外层在 prepare 之前调用；0 = 不去人声）。 */
    public void setVocalMode(int mode) {
        this.installedVocalMode = mode;
    }

    /** 当前装的分离档位（引擎装钩子时要用它）。 */
    public int installedVocalMode() {
        return installedVocalMode;
    }

    private int vocalModeForMaterial() {
        return installedVocalMode;
    }

    /** 设窗口锚点与分离档位（取素材前调用）。 */
    void setMaterialContext(double anchorSecs, int vocalMode) {
        this.materialAnchorSecs = anchorSecs;
        this.materialVocalMode = vocalMode;
    }

    /** 边缘淡化长度（秒）：区间两端不让增益跳变，否则会有"咔"的一声。 */
    static final double DUCK_EDGE_SECS = 0.05;

    /** 装叠唱区间（{@code {s0,e0,s1,e1,…}}）；传 {@code null} 清除。 */
    public void setVocalDuckRanges(double[] ranges) {
        this.vocalDuckRanges = ranges == null ? null : ranges.clone();
    }

    /** 当前叠唱区间（判据与探针用 —— 探针在别的包，所以要 public）。 */
    public double[] vocalDuckRanges() {
        return vocalDuckRanges == null ? null : vocalDuckRanges.clone();
    }

    /**
     * <b>窗口时间轴上某个时刻的人声增益</b>：1 = 原样，0 = 完全去人声。
     *
     * <p>两端各留 {@value #DUCK_EDGE_SECS}s 的余弦交叉淡化 ✓ —— 直接开关会切出波形台阶
     * （用户当年报的"切换的时候叫一声"就是那个台阶 ✓）。
     *
     * <p>这是**纯函数**（不碰任何状态），所以可以先立判据、再由渲染循环调用 ✓。
     */
    double vocalGainAt(double timelineSecs) {
        double[] ranges = vocalDuckRanges;
        if (ranges == null || ranges.length < 2) {
            return 1.0;
        }
        double gain = 1.0;
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            double a = ranges[i];
            double b = ranges[i + 1];
            if (b <= a) {
                continue;
            }
            if (timelineSecs <= a || timelineSecs >= b) {
                continue;
            }
            // 区间内：两端淡入淡出，中间全压
            double in = Math.min(1.0, (timelineSecs - a) / DUCK_EDGE_SECS);
            double out = Math.min(1.0, (b - timelineSecs) / DUCK_EDGE_SECS);
            double depth = 0.5 - 0.5 * Math.cos(Math.PI * Math.min(in, out));   // 0→1 平滑
            gain = Math.min(gain, 1.0 - depth);
        }
        return Math.max(0.0, Math.min(1.0, gain));
    }

    /** 目标积分响度（LUFS）：两侧都归到它 ⇒ 过渡两侧天然等响。 */
    static final double TARGET_LUFS = -16.0;

    /**
     * 把一段素材归一到 {@link #TARGET_LUFS}（开关关闭时原样返回）。
     *
     * <p>为什么归到**固定目标**而不是"把 B 对齐到 A"：出场侧与进场侧是**分别**取素材的
     * （两次 {@code materialFor} 调用），各自归到同一个目标就等于两侧等响 ✓，
     * 而且不依赖"谁先谁后"（对齐式会引入顺序依赖 ✗）。
     *
     * <p>⚠️ <b>但它现在还不能出厂</b>（2026-10-07 用户实测"响度突变"）：逐次取素材各自
     * 归一化 ⇒ 相邻两次的增益可能不同 ⇒ 段边界上跳变 ✗；而且把安静窗口拉上来会**超 0 dBFS**
     * （{@code MAX_NORMALIZE_DB} 只夹增益、不夹峰值 ✗）。
     * 正解是**按整首**量一次响度、全程用同一个增益（而不是逐窗口各自归一），
     * 并且加峰值保护。在此之前它只作为实验开关存在（默认关）。
     */
    private float[][] matchLoudness(float[][] material, int rate) {
        if (material == null || material.length == 0 || rate <= 0) {
            return material;
        }
        if (!dev.t1m3.qplayer.audio.dsp.Lufs.normalizationEnabled()) {
            return material;
        }
        double sum = 0.0;
        int n = 0;
        for (float[] ch : material) {
            if (ch == null || ch.length == 0) {
                continue;
            }
            double lufs = dev.t1m3.qplayer.audio.dsp.Lufs.integrated(ch, null, rate);
            if (!Double.isInfinite(lufs) && !Double.isNaN(lufs)) {
                // 每声道各算一次 LUFS、取平均（单声道刻度比双声道低约 3 LU，
                // 但两侧用同一把尺子 ⇒ 相对关系正确 ✓）
                sum += lufs;
                n++;
            }
        }
        if (n == 0) {
            return material;                 // 量不出响度（静音）⇒ 不动
        }
        double current = sum / n;
        double db = TARGET_LUFS - current;
        db = Math.max(-dev.t1m3.qplayer.audio.dsp.Lufs.MAX_NORMALIZE_DB,
                Math.min(dev.t1m3.qplayer.audio.dsp.Lufs.MAX_NORMALIZE_DB, db));
        double gain = Math.pow(10.0, db / 20.0);
        for (float[] ch : material) {
            if (ch == null) {
                continue;
            }
            for (int i = 0; i < ch.length; i++) {
                ch[i] = (float) (ch[i] * gain);
            }
        }
        return material;
    }

    float[][] materialForRaw(String path, java.util.Map<String, String> headers,
                          double startSecs, int frames, int rate) {
        MaterialSource src = instanceSource;
        if (src != null) {
            float[][] out = src.read(path, headers, startSecs, frames, rate);
            if (out != null) {
                return out;
            }
        }
        return readWindowMaterial(path, headers, startSecs, frames, rate);
    }

    /** 读窗口素材：装了来源就走它，否则直接读文件（与从前逐位相同）。 */
    static float[][] readWindowMaterial(String path, java.util.Map<String, String> headers,
                                        double startSecs, int frames, int rate) {
        MaterialSource src = materialSource;
        if (src != null) {
            float[][] out = src.read(path, headers, startSecs, frames, rate);
            if (out != null) {
                return out;
            }
        }
        return Automix.readStereoWindow(path, headers, startSecs, frames, rate);
    }

    private SegmentFeed stretchFeed;

    /**
     * <b>进场曲（B）的预渲染队列</b>（第一阶段工作三：双方各承担一半）。
     * 对半之后 B 有非零速度 speedB = 1/speedA，所以 B 的 intro 素材同样要离线拉伸好
     * 再喂给渲染层——照抄 A 那一套（同一个 SegmentFeed、同一条"游标只按真正交付的帧推进"）。
     */
    /** B 侧变速：起点速率 与 窗口帧数（`contentFramesFor` 用 ✓ 1.0 表示没变速 ✓）。 */
    private double introSpeedStart = 1.0;
    private int introSpeedWindow = 0;

    private SegmentFeed[] introOutgoing = new SegmentFeed[0];

    /** 进场侧队列的读指针。 */
    private int introIndex;

    /** 进场侧替换用的暂存缓冲。 */
    private AudioBuf introScratch;

    /** 这一层实际用的变速比（1.0 = 没变速）；日志与判据读它。 */
    private double stretchSpeed = 1.0;

    /** 窗口起点的速率（渐变时是 1.0；恒定变速时代没有这个概念）。 */
    private double stretchSpeedStart = 1.0;

    /** 窗口内的平均速率（渐变 = 两端均值）；判据与日志读它。 */
    private double stretchSpeedMean = 1.0;

    /** 这一次算出来的降速斜坡计划（{@code null} = 还没算 / 不降速）。判据读它。 */
    private TempoRamp rampPlan;

    /** 循环那一段（{@code null} = 不循环）；判据与日志读它。 */
    private SegmentFeed loopFeed;

    /** 这一次实际用的循环计划（判据与日志用；线上不读）。 */
    private AutoCue.OutroLoopPlan loopPlan;

    /**
     * <b>队列里的一段素材</b>：一遍的样本 + 重复遍数 + 交付游标。
     *
     * <p>三条纪律（都是从"素材要与听感逐位对齐"那件事来的）：
     *
     * <ol>
     *   <li><b>只读一遍</b>：{@code repeat > 1} 靠 {@link #cursor} 绕回，所以第 n 遍与
     *       第一遍是<strong>同一份采样</strong>（逐位相同），内存也只有一遍那么大；</li>
     *   <li><b>游标只按真正交付的帧推进</b>：多推少推都会让素材与听感错位
     *       （2026-10-04 那次「进度回跳」就是这么来的）；</li>
     *   <li><b>进度怎么换算写在段上</b>：{@link #windowFrames} &gt; 0 的段（循环 / 路径那一类）
     *       按"整窗里已经交付了多少帧"换算，否则（变速那一档）<strong>原样透传</strong>
     *       编排器给的整窗进度——这正是两条路各自与从前逐位相同的地方。</li>
     * </ol>
     */
    private static final class SegmentFeed {

        /** 素材来自哪一首：{@code "outro"} / {@code "intro"}。 */
        final String source;
        /** 源里的起点（秒）。 */
        final double startSecs;
        /** 源里的终点（秒）。 */
        final double endSecs;
        /** 重复几遍（{@code >= 1}）。 */
        final int repeat;
        /** 一遍的素材（{@code [声道][帧]}，长度 = {@link #passFrames}）。 */
        final float[][] pcm;
        /** 一遍有多少帧。 */
        final int passFrames;
        /** 段首接缝淡入的帧数（0 = 不淡）。 */
        final int seamFrames;
        /** &gt; 0：进度按「整窗已交付多少帧」换算；0：原样透传编排器的进度。 */
        final long windowFrames;
        /** 这一段之前，整窗已经交付了多少帧（进度换算用）。 */
        final long windowOffset;
        /** 整窗里「只放 A」的帧数（{@link #windowFrames} &gt; 0 时有意义）。 */
        final int insertFrames;
        /**
         * <b>旋转点</b>在源里的时刻（秒；{@code NaN} = 这一遍不旋转，就是
         * {@code [startSecs, endSecs)} 顺序播完）。
         *
         * <p>见 {@link AutoCue.Segment#rotateStartSecs}：它来自计划层，语义是"这一遍
         * 从这一秒起播、播到段末、再绕回段首播到这一秒"。{@link #pcm} 已经按它**左旋**过，
         * 所以渲染那边（{@link #cursor} 的绕回、{@link #secsAt}）读到的就是起播点开始的一遍。
         */
        final double rotateStartSecs;
        /** 旋转点在一遍里的帧偏移（{@code pcm} 左旋了多少帧）；{@code 0} = 不旋转。 */
        final int rotFrames;
        /** 一遍内的游标（只在 [0, passFrames) 里绕）。 */
        int cursor;
        /** 这一段已经交付出去多少帧。 */
        long delivered;

        SegmentFeed(String source, double startSecs, double endSecs, int repeat, float[][] pcm,
                    int passFrames, int seamFrames, long windowFrames, long windowOffset,
                    int insertFrames) {
            this(source, startSecs, endSecs, repeat, pcm, passFrames, seamFrames, windowFrames,
                    windowOffset, insertFrames, Double.NaN, 0);
        }

        SegmentFeed(String source, double startSecs, double endSecs, int repeat, float[][] pcm,
                    int passFrames, int seamFrames, long windowFrames, long windowOffset,
                    int insertFrames, double rotateStartSecs, int rotFrames) {
            this.source = source;
            this.startSecs = startSecs;
            this.endSecs = endSecs;
            this.repeat = Math.max(1, repeat);
            this.pcm = pcm;
            this.passFrames = passFrames;
            this.seamFrames = seamFrames;
            this.windowFrames = windowFrames;
            this.windowOffset = windowOffset;
            this.insertFrames = insertFrames;
            this.rotateStartSecs = rotateStartSecs;
            this.rotFrames = rotFrames;
        }

        /**
         * 复制一份，只换"整条路径"才有的那几项（窗口帧数、偏移、只放 A 的帧数、接缝淡入）。
         *
         * <p><b>为什么要有这个方法</b>：窗口几何是<strong>整条路径</strong>的性质，
         * 而它在各段长度都算出来之后才定得下来（见 {@code prepareFromPath}），所以要"读一遍
         * 再包一层"。段的素材、重复遍数、以及<strong>旋转</strong>都是段自己的性质，
         * 复制时原样带走——少带一项（比如旋转）就等于悄悄换了一遍素材，
         * 而那种错在听感上根本看不出来。位置参数有十二个，写漏一个太容易了，所以收在这里。
         */
        SegmentFeed withWindow(int seamFrames, long windowFrames, long windowOffset,
                               int insertFrames) {
            return new SegmentFeed(source, startSecs, endSecs, repeat, pcm, passFrames,
                    seamFrames, windowFrames, windowOffset, insertFrames, rotateStartSecs,
                    rotFrames);
        }

        /** 这一段总共要交付多少帧（含重复的每一遍）。 */
        long totalFrames() {
            return (long) repeat * (long) passFrames;
        }

        /**
         * 交给被包接法的进度：队列里是**整窗**的一条路径，而淡化只占窗口最后一段，
         * 所以有窗口信息时要换算（{@link #windowFrames} &gt; 0），变速那一档透传。
         */
        float progressFor(float orchestratorProgress) {
            if (windowFrames <= 0L) {
                return orchestratorProgress;
            }
            return loopProgress(windowOffset + delivered, windowFrames, insertFrames);
        }

        /**
         * 素材交付完之后交给被包接法的进度。
         *
         * <p>循环 / 路径那一类是 <b>1.0</b>（"淡化已完成"）：编排器还在收尾那一块，
         * 不这么写的话最后一块会用窗口开头的增益，把 A 又抬起来。变速那一档是
         * <strong>编排器原值</strong>——它的素材本来就覆盖整个窗口，多交付出去的那一块
         * 与后面的正常播放无缝，换成 1.0 会把变调/增益曲线拽到窗口末尾那一点上。
         */
        float exhaustedProgress(float orchestratorProgress) {
            return windowFrames <= 0L ? orchestratorProgress : 1.0f;
        }

        /**
         * 段内第 {@code localPos} 帧在**源里**的时刻（按一遍绕回；诊断/人声区间用）。
         *
         * <p>不旋转时是 {@code startSecs + 一遍内偏移}；<strong>旋转</strong>时一遍被
         * 左旋过 {@link #rotFrames} 帧，于是前 {@code passFrames − rotFrames} 帧对应
         * {@code [rotateStartSecs, endSecs)}、其余对应 {@code [startSecs, rotateStartSecs)}
         * ——这正是"从旋转点起播、绕回段首"这句话在时刻上的写法（位置一一对上，
         * 人声区间才取得到正确的那一块）。
         */
        double secsAt(long localPos, int rate) {
            if (passFrames <= 0) {
                return startSecs;
            }
            int pos = (int) (localPos % passFrames);
            int perSec = Math.max(1, rate);
            if (rotFrames <= 0 || rotFrames >= passFrames) {
                return startSecs + pos / (double) perSec;
            }
            int head = passFrames - rotFrames;      // 一遍里"旋转点 → 段末"那一段有多少帧
            return pos < head
                    ? rotateStartSecs + pos / (double) perSec
                    : startSecs + (pos - head) / (double) perSec;
        }

        @Override
        public String toString() {
            return (source == null ? "?" : source) + "[" + startSecs + "→" + endSecs + ")"
                    + (rotFrames > 0 ? ("↻" + rotateStartSecs) : "")
                    + (repeat > 1 ? ("×" + repeat) : "");
        }
    }

    /**
     * 这次过渡用的变速比——<b>渐变斜坡上它是窗口末端的瞬时速率</b>
     * （{@code <1} = 出场曲被放慢；恒定变速时代它就是那个恒定比例）。
     *
     * <p>语义沿用"这一层实际用的变速比"：斜坡的<b>平均</b>速率另有
     * {@link #stretchedMeanSpeed()}，起点恒为 {@link #stretchedStartSpeed()}。
     * 判据与日志用；线上不读。
     */
    public double stretchedSpeed() {
        return stretchSpeed;
    }

    /** 窗口<b>起点</b>的速率（渐变时是 1.0 = 与自然播放同速）。 */
    public double stretchedStartSpeed() {
        return stretchSpeedStart;
    }

    /** 窗口内的<b>平均</b>速率（渐变 = 两端均值；它决定消耗多少源素材）。 */
    public double stretchedMeanSpeed() {
        return stretchSpeedMean;
    }

    /**
     * 这一次算出来的降速斜坡计划（{@code null} = 还没算过）。
     *
     * <p>判据用它钉"速率曲线单调下降、末端落在目标附近、窗口按 BPM 差定"，
     * 而不必去反推渲染出来的 PCM。
     */
    public TempoRamp tempoRamp() {
        return rampPlan;
    }

    /** 这次过渡有没有真的拿着自己的出场曲素材（判据用）。 */
    public boolean hasStretchedOutgoing() {
        return stretchFeed != null;
    }

    // ==================================================================
    // 尾奏循环（见 AutoCue.planOutroLoop / docs/automix-modes.md §8.0.8）
    // ==================================================================

    /**
     * 循环点接缝的淡入长度（秒）。
     *
     * <p>取值与离线原型 {@code AutomixOutroLoopDemoProbe} 的 {@code SEAM_XFADE} 一致：
     * 原型里那一版（"B 在循环里淡入"，用户听感验收过、也是指标最好的那一版）就是用它拼的。
     * 作用是把"自然播放的循环段第一遍"与"这里重复的第二遍"之间那一下磨掉——
     * 小节线对齐本身已经把跳变压到本底（实测 0.8394 → 0.7471），这 20 ms 是第二道保险。
     */
    static final double LOOP_SEAM_FADE_SECS = 0.02;

    /**
     * 这一次实际用的循环计划；{@code null} = 不循环（判据与日志用）。
     *
     * <p>循环那一档的素材（"只读一遍、游标绕回"）现在活在 {@link #loopFeed} 里——
     * 它是队列里的一段，字段本身不再单独存。
     */
    public AutoCue.OutroLoopPlan outroLoopPlan() {
        return loopPlan;
    }

    /** 这一层有没有拿着自己的循环素材（判据用）。 */
    public boolean hasLoopOutgoing() {
        return loopFeed != null;
    }

    /**
     * <b>接缝</b>（窗口起点）在出场曲上的时刻（秒）——过渡真正开始的那一点；
     * 这一层没有素材时是 0。
     *
     * <p>两档的语义是同一条（"A 自然播到哪儿，窗口就从那儿开始重放这条路径"）：
     * 循环那一档 = 一遍循环段自然播完（{@code v0 + 一遍}，落在小节线上），
     * 多段路径那一档 = 路径<strong>第一段的终点</strong>。
     */
    public double loopCutSecs() {
        return (loopFeed == null && !explicitPath) ? 0.0 : segmentCutSecs;
    }

    /** 循环窗口的总帧数（= {@code repeats × 一遍循环段}）；不循环时是 0。 */
    public long loopWindowFrames() {
        return loopFeed == null ? 0L : segmentWindowFrames;
    }

    /**
     * 「买时间」那一档里「**只有 A 在响**」的前导秒数（0 = 没有这种前导）。
     *
     * <p>留给引擎报口径用（2026-10-07 用户拍板）：前导这一段界面必须仍然显示 A ✓ ——
     * 通知/位置/时长三样一起等 B 出声再切 ✓。这十几秒里 B 的 deck 停在进场上限不动 ✓，
     * 所以那边只要"看到 B 的帧数开始往前走"就知道前导结束了 ✓。
     */
    public double outroLoopLeadInSecs() {
        return loopFeed == null ? 0.0
                : (double) Math.max(0, segmentInsertFrames) / (double) Math.max(1, this.rate);
    }

    /** 循环窗口里"淡化"那一段的帧数（= 用户设的窗口吸附到整数小节）。 */
    public long loopFadeFrames() {
        return loopFeed == null ? 0L : segmentWindowFrames - segmentInsertFrames;
    }

    /**
     * 这一次过渡有没有走**计划层给的显式路径**（{@code Cue.segments}）。
     *
     * <p>判据要能证明"路径"是被<strong>消费</strong>的，不是摆着好看的：
     * 只有这一位为真，{@code AutoCueSegmentedPathTest} 里那条"等价路径渲染与今天
     * 逐位相同"才真的测到了路径那一支（否则它测的是"路径说不出来时如实退回"那条兜底）。
     */
    public boolean usesExplicitPath() {
        return explicitPath;
    }

    /** 队列里的段数（0 = 这一层不碰素材）。判据用。 */
    public int outgoingSegmentCount() {
        return segmentedOutgoing.length;
    }

    /** 队列覆盖的窗口帧数（0 = 没有素材）。判据用。 */
    public long outgoingWindowFrames() {
        return segmentWindowFrames;
    }

    @Override
    public long windowFrames(int sampleRate) {
        if (autoWindow > 0L) {
            return autoWindow;
        }
        return base.windowFrames(sampleRate);
    }

    /**
     * <b>按段消费队列</b>——从"变速 / 循环两条平行实现"合并成的那一条路（用户原话：
     * {@code segmentedOutgoing}）。
     *
     * <p>队列空时整条交回被包的接法（{@code base.render(out, from, to, progress)}）：
     * 这就是"不需要循环、也不变速"那一档，与从前<strong>逐位相同</strong>。
     *
     * <p>队里还有素材时，每一块都从**当前段**里取，取不出（这一段交付完）就换下一段，
     * 全交付完就按 {@link SegmentFeed#exhaustedProgress} 收尾。三条性质：
     *
     * <ol>
     *   <li><b>进度换算是段自己的事</b>（{@link SegmentFeed#progressFor}）：循环 / 路径那一类
     *       按"整窗已交付多少帧"换算到淡化那一段，变速那一档原样透传；</li>
     *   <li><b>游标只按真正交付的帧推进</b>：{@code base.render} 可能只吃掉一部分，
     *       多推少推都会让素材与听感错位（2026-10-04 那次「进度回跳」就是这么来的）；</li>
     *   <li><b>绕回只在段内</b>：{@code repeat > 1} 的段读的是<strong>同一份采样</strong>，
     *       所以第 n 遍与第一遍逐位相同。</li>
     * </ol>
     */
    @Override
    public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
        renderedOnce = true;   // 素材队列从这里开始被消费 ⇒ memo 失效（见 prepare 的注释）
        // 进场侧的预渲染替换：没有 B 侧素材时**走原路**（逐位不变）；有的话只把 `to`
        // 换成拉伸好的那一块，其它逻辑一行不改（游标按真正交付的帧数推进）。
        if (introOutgoing.length > 0 && to != null && to.frames() > 0) {
            if (!fillIntroScratch(to.frames(), to.channels())) {
                return renderSegments(out, from, to, progress);
            }
            int n = renderSegments(out, from, introScratch, progress);
            SegmentFeed seg = introIndex < introOutgoing.length ? introOutgoing[introIndex] : null;
            if (seg != null) {
                int used = Math.max(0, Math.min(to.frames(), n));
                seg.cursor = seg.passFrames <= 0 ? 0 : (seg.cursor + used) % seg.passFrames;
                seg.delivered += used;
            }
            return finish(out, n, progress);
        }
        return finish(out, renderSegments(out, from, to, progress), progress);
    }

    /**
     * 融合期的收尾处理：<b>留白 + 前瞻软限幅</b>（2026-10-07）。
     *
     * <p>为什么放在这一层：它是**所有接法共用**的最后一道（等功率淡化、低音交接、
     * 拍点剪切……谁都要经过这里），而"融合期不炸"与接法无关——那是过渡本身该保证的。
     * 放在内层等于每个接法各写一遍，加新接法时必然漏。
     *
     * <p>留白随窗口形状走 {@code sin(π·p)}：两端为 0，所以窗口起点（A 还独自在响）
     * 与终点（B 已经独自在响）都**一个比特都不动**，只在两首歌真正叠着的中段让出 1 dB。
     */
    private int finish(AudioBuf out, int n, float progress) {
        if (n <= 0 || out == null || !antiClipEnabled) {
            return n;
        }
        float p = Automix.clampF32(progress, 0.0f, 1.0f);
        double shape = Math.sin(Math.PI * Math.min(1.0, Math.max(0.0, (double) p)));
        if (shape > 1e-4) {
            float g = (float) Math.pow(10.0, -headroomDb * shape / 20.0);
            if (g < 0.9999f) {
                int chs = Math.min(out.channels(), 2);
                for (int ch = 0; ch < chs; ch++) {
                    FloatBuffer o = out.channelWriter(ch);
                    for (int i = 0; i < n; i++) {
                        o.put(i, o.get(i) * g);
                    }
                }
            }
        }
        limiter.process(out, n);
        return n;
    }

    /** 进场侧那一块素材（把当前段的下一块搬进 {@link #introScratch}）。 */
    private boolean fillIntroScratch(int want, int channels) {
        while (introIndex < introOutgoing.length) {
            SegmentFeed seg = introOutgoing[introIndex];
            if (seg.totalFrames() - seg.delivered > 0L) {
                fillScratchInto(seg, want, channels, true);
                return true;
            }
            introIndex++;
        }
        return false;
    }

    private int renderSegments(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
        if (segmentedOutgoing.length == 0) {
            if (base instanceof AiMix) {
                // **把窗口时长告诉渲染层**（2026-10-08 ✓，照搬 goal 第 29 轮 ✓）：
                // 他有些自动化是**绝对秒**（如 33/44 的 send_mixer_gain 是 [0, +2 s] ✓）⇒
                // 归一必须有窗口时长 ✓，而窗口是**这一层**算的 ✓（渲染层拿不到 ✗）。
                ((AiMix) base).setWindowSeconds(windowFrames(rate) / (double) Math.max(1, rate));
            }
            return base.render(out, from, to, progress);      // 没有自己的素材：与从前逐位相同
        }
        while (true) {
            if (segmentIndex >= segmentedOutgoing.length) {
                // 队列交付完（编排器还在收尾那一块）：按最后一段的口径收尾
                SegmentFeed last = segmentedOutgoing[segmentedOutgoing.length - 1];
                if (base instanceof AiMix) {
                // **把窗口时长告诉渲染层**（2026-10-08 ✓，照搬 goal 第 29 轮 ✓）：
                // 他有些自动化是**绝对秒**（如 33/44 的 send_mixer_gain 是 [0, +2 s] ✓）⇒
                // 归一必须有窗口时长 ✓，而窗口是**这一层**算的 ✓（渲染层拿不到 ✗）。
                    ((AiMix) base).setWindowSeconds(windowFrames(rate) / (double) Math.max(1, rate));
                }
                return base.render(out, from, to, last.exhaustedProgress(progress));
            }
            SegmentFeed seg = segmentedOutgoing[segmentIndex];
            float mapped = seg.progressFor(progress);
            long left = seg.totalFrames() - seg.delivered;
            if (left <= 0L) {
                segmentIndex++;            // 这一段交付完了：换下一段（空段会被这一行直接跳过）
                continue;
            }
            int want = (int) Math.min((long) from.frames(), left);
            if (want <= 0) {
                // 编排器没给素材（deck 已耗尽）：不作主张，按同样的换算进度交回被包的接法
                if (base instanceof AiMix) {
                // **把窗口时长告诉渲染层**（2026-10-08 ✓，照搬 goal 第 29 轮 ✓）：
                // 他有些自动化是**绝对秒**（如 33/44 的 send_mixer_gain 是 [0, +2 s] ✓）⇒
                // 归一必须有窗口时长 ✓，而窗口是**这一层**算的 ✓（渲染层拿不到 ✗）。
                    ((AiMix) base).setWindowSeconds(windowFrames(rate) / (double) Math.max(1, rate));
                }
                return base.render(out, from, to, mapped);
            }
            fillScratch(seg, want, from.channels());
            if (base instanceof AiMix) {
                // **把窗口时长告诉渲染层**（2026-10-08 ✓，照搬 goal 第 29 轮 ✓）：
                // 他有些自动化是**绝对秒**（如 33/44 的 send_mixer_gain 是 [0, +2 s] ✓）⇒
                // 归一必须有窗口时长 ✓，而窗口是**这一层**算的 ✓（渲染层拿不到 ✗）。
                ((AiMix) base).setWindowSeconds(windowFrames(rate) / (double) Math.max(1, rate));
            }
            int n = base.render(out, segmentScratch, to, mapped);
            // 游标与"已交付"只按**真正交付的帧**推进（见类注释第 2 条）
            int used = Math.max(0, Math.min(want, n));
            seg.cursor = seg.passFrames <= 0 ? 0 : (seg.cursor + used) % seg.passFrames;
            seg.delivered += used;
            return n;
        }
    }

    /**
     * 把当前段的下一块素材搬进 {@link #segmentScratch}（不跨段，也不改段的状态）。
     *
     * <p>两条路，<strong>值完全一样</strong>（快的那条只是少一次逐样本判断）：
     *
     * <ul>
     *   <li>段内不跨末尾、也不需要接缝淡入时整段连续拷贝——变速那一档走的就是这条，
     *       与从前那一句 {@code System.arraycopy} 同一个动作；</li>
     *   <li>否则逐样本搬：跨末尾就绕回段首（第二遍 = 第一遍的采样），窗口开头
     *       {@link #LOOP_SEAM_FADE_SECS} 秒按等功率的 sin 那一半淡入
     *       （与离线原型 {@code loopTail(SEAM_XFADE)} 同一式子）。</li>
     * </ul>
     */
    private void fillScratch(SegmentFeed seg, int want, int channels) {
        fillScratchInto(seg, want, channels, false);
    }

    /** 搬素材：{@code introSide} 决定写哪个暂存缓冲（A 侧 / B 侧），搬法完全一样。 */
    private void fillScratchInto(SegmentFeed seg, int want, int channels, boolean introSide) {
        AudioBuf scratch = introSide ? introScratch : segmentScratch;
        if (scratch == null
                || scratch.channels() != channels
                || scratch.capacityFrames() < want) {
            scratch = new AudioBuf(
                    dev.t1m3.qplayer.audio.core.ChannelLayout.of(channels),
                    Math.max(want, 2 * Deck.RENDER_FRAMES));
            if (introSide) {
                introScratch = scratch;
            } else {
                segmentScratch = scratch;
            }
        }
        int cap = scratch.capacityFrames();
        float[] dst = scratch.backingArray();
        int chans = Math.min(scratch.channels(), seg.pcm.length);
        for (int c = 0; c < chans; c++) {
            float[] src = seg.pcm[c];
            int base = c * cap;
            int idx = seg.cursor;
            if (seg.seamFrames <= 0 && idx + want <= seg.passFrames) {
                System.arraycopy(src, idx, dst, base, want);
                continue;
            }
            // **接缝交叉淡化**（2026-10-07 改；用户实测原做法有"断裂感" ✗）
            //
            // 原做法：只在**段首**做等功率淡入（`p = delivered/seam`）✗ —— 没有对应的淡出，
            // 于是每到循环点音量被拉到 0 再拉起来 ⇒ 一遍一遍听得出"断一下" ✗。
            //
            // 现在：在一遍的**最后** `seamFrames` 帧里，把本遍的尾巴与**下一遍同一相位**的开头
            // 做等功率交叉淡化 ✓ —— 循环点落在淡化区中间，音乐不会被拉空 ✓，
            // 波形相位不接的那一点跳变也被淡化盖掉 ✓（vamp 本来就是逐小节重复的素材 ✓）。
            // 对"每一遍逐位相同"那条判据没有影响：每一遍的**段首**都不再被修改 ✓，
            // 而每一遍的尾巴混的是同一份段首素材 ✓。
            final int seam = seg.seamFrames;
            final int tailStart = seg.passFrames - seam;
            for (int i = 0; i < want; i++) {
                float v = src[idx];
                if (seam > 0 && idx >= tailStart) {
                    int over = idx - tailStart;                    // 0..seam-1
                    float p = (float) over / (float) Math.max(1, seam - 1);
                    Mixer.Gains g = Mixer.equalPowerGains(p);
                    v = v * g.from + src[over] * g.to;              // 本遍尾巴 × 下一遍开头
                }
                // ⚠️ **这里曾经把 vocalGainAt 直接乘到整段混音上 —— 用户实测"突然静音"** ✗
                // 原因：那是给人声**分轨**的增益（重建式 instr + voc × (1 − env)），
                // 乘到混音上等于"增益 0 时把伴奏也一起静音"。
                // 正确做法必须先拿到分轨（stems），所以这条增益只能在**素材源**
                // （返回 instr/voc 两轨的地方）施加，不能在这里施加。
                // vocalGainAt 原语与其判据保留（它们是纯函数、已验过），等分轨接好再用。
                dst[base + i] = v;
                if (++idx >= seg.passFrames) {
                    idx = 0;                     // 绕回段首：第二遍 = 第一遍的样本
                }
            }
        }
        scratch.setFrames(want);
    }

    /**
     * 整窗进度 → <b>淡化那一段</b>的进度（0..1）。窗口前 {@code insertFrames} 帧只放 A
     * （B 被编排器按 {@code introStartAt} 挡在门外），所以那一段换算出来是 0：
     * 等功率增益 {@code (1, 0)}，B 一个采样都不进来。
     */
    static float loopProgress(long deliveredFrames, long windowFrames, int insertFrames) {
        long fade = windowFrames - insertFrames;
        if (fade <= 0L) {
            return 1.0f;
        }
        double p = (double) (deliveredFrames - insertFrames) / (double) fade;
        if (p <= 0.0) {
            return 0.0f;
        }
        return p >= 1.0 ? 1.0f : (float) p;
    }

    @Override
    public String name() {
        // 界面上要说清「这一档是自动点位」，否则用户只会看到一个和固定点位一样的名字
        return base.name() + " · 自动点位";
    }

    @Override
    public boolean rewritesSamples() {
        return base.rewritesSamples();
    }

    @Override
    public PitchPlan pitchAt(float progress) {
        if (segmentWindowFrames <= 0L) {
            return base.pitchAt(progress);
        }
        // 队列里放的是**整窗**的素材，而变调曲线是照着淡化那一段算的
        // （否则变调会在"只放 A"的那一段就开始爬）。换算与被包的接法看到的进度是同一个。
        long pos = (long) ((double) progress * (double) segmentWindowFrames);
        return base.pitchAt(loopProgress(pos, segmentWindowFrames, segmentInsertFrames));
    }

    /**
     * <b>该不该变速、变多少</b>——纯函数，判据直接钉它。
     *
     * <p>规则（每条都有理由）：
     *
     * <ul>
     *   <li>两侧都要有<b>可信</b>拍栅格（{@code confidence ≥ }{@value #STRETCH_MIN_CONFIDENCE}）：
     *       拿不准的速度去变速，等于按一个错的拍长改素材；</li>
     *   <li>速度比落在 {@value #STRETCH_MIN_SPEED}~{@value #STRETCH_MAX_SPEED} 内：
     *       再大就不像"微调"了（WSOLA 伪影与"改了作品"的观感都会上来）；</li>
     *   <li>比值离 1 太近（{@value #STRETCH_MIN_DEVIATION} 以内）就不动：
     *       没必要为了几十毫秒的错位去动素材；</li>
     *   <li>返回 {@code 1.0} = <b>不变速</b>（调用方走原来的路，与从前逐位相同）。</li>
     * </ul>
     *
     * @return 变速比：{@code < 1} 把出场曲<b>放慢</b>（消耗更少的源素材），{@code 1.0} = 不变
     */
    static double stretchSpeedFor(double outroBpm, double outroConfidence,
                                  double introBpm, double introConfidence) {
        if (!(outroBpm > 0.0) || !(introBpm > 0.0)) {
            return 1.0;
        }
        if (outroConfidence < STRETCH_MIN_CONFIDENCE || introConfidence < STRETCH_MIN_CONFIDENCE) {
            return 1.0;
        }
        double speed = introBpm / outroBpm;
        if (speed < STRETCH_MIN_SPEED || speed > STRETCH_MAX_SPEED) {
            return 1.0;
        }
        if (Math.abs(speed - 1.0) < STRETCH_MIN_DEVIATION) {
            return 1.0;
        }
        return speed;
    }

    /** 变速所需的拍栅格置信度下限（与 {@code AutoCue.MIN_BEAT_CONFIDENCE} 同一把尺子）。 */
    /**
     * <b>B 侧被拉伸后，窗口结束时它的"歌内位置"该往前挪多少秒</b>
     * （2026-10-06 第一阶段工作三 B 侧）。
     *
     * <p>为什么需要它：对半之后 B 也有非零速度 {@code speedB = 1/speedA}。B 以
     * {@code speedB} 播完 {@code 窗口} 秒，消耗的源素材是 {@code 窗口 × speedB}
     * ——比实时多（提速侧）。而 B 的 Deck 是按实时推进的，所以窗口一结束，
     * 声音会从"素材末尾"切回"实时位置"，中间差这一段 ⇒ 听感上就是**跳一下**。
     *
     * <p>代数条件（判据 {@code AutoCueIntroHandoffTest} 钉的就是它）：
     * <pre>
     *   entry + 窗口 × speedB  ==  (entry + 本偏移) + 窗口
     *   ⇒ 本偏移 = 窗口 × (speedB − 1) = 窗口 × (1/speedA − 1)
     * </pre>
     * 也就是"让 B 的歌内位置也往前走同样一段"。0.2 秒的歌内位置前进听不出来，
     * 但接缝严丝合缝——这是"双方各半"能落地的前提。
     *
     * <p>没有拉伸（{@code speedA == 1}）时返回 0：与加这一条之前逐位相同。
     *
     * <p>公开：桌面端的判据要从外面验"生产路径的 skipSecs 里确实带着这个偏移"。
     */
    public static double introSkipBonusSecs(double windowSecs, double speedA) {
        if (!(windowSecs > 0.0) || !(speedA > 0.0) || Math.abs(speedA - 1.0) < 1e-12) {
            return 0.0;
        }
        return windowSecs * (1.0 / speedA - 1.0);
    }

    static final double STRETCH_MIN_CONFIDENCE = 0.8;

    /** 变速比的下限 / 上限（超出就当"不是微调"）。 */
    /**
     * 变速比下界。**2026-10-06 第一阶段工作二：0.92 → {@link TempoBridge#MIN_SPEED}（0.75）**。
     * 用户原话：「该变速就变速，该咋滴就咋滴，不要受到神秘条款制约。」定义处只有一处
     * （{@link TempoBridge}），这里只是引用。
     */
    static final double STRETCH_MIN_SPEED = TempoBridge.MIN_SPEED;
    /** 变速比上界（同上，**1.08 → 0.33 侧对称的 1.33**）。 */
    static final double STRETCH_MAX_SPEED = TempoBridge.MAX_SPEED;

    /** 比值离 1 小于这个量就不动素材。 */
    static final double STRETCH_MIN_DEVIATION = 0.005;

    /** 变速深度上限（2026-11-16）：超过这个倍率就不做 WSOLA（音质优先于拍点完美）。 */
    static final double STRETCH_MAX_DEPTH = 0.04;

    /** ① 硬闸门：拍速差上限（Apple 的 loose tempo 门槛取 6%）。 */
    static final double TEMPO_GATE = 0.06;

    /** ① 硬闸门：调性差上限（半音）。超过就不再"移调硬凑"，改走 fallback。 */
    static final double TONALITY_GATE_SEMITONES = 5.0;

    /** ② fallback_crossfade 的窗口上限（小节对齐的短交叉）。 */
    static final double FALLBACK_WINDOW_SECS = 1.5;

    /** 闸门结果（`prepare` 里算一次，**收尾处再按它把关**：后面的斜坡/循环会把窗口拉大 ✗）。 */
    private double gateTempoDiff = -1.0;
    private double gateShiftSemis = 0.0;
    /**
     * 叠唱时长（秒）。
     *
     * <p>{@code Cue} 上没有这个字段（它在选点的 {@code Score} 里，选完就丢了）⇒
     * 这里如实返回 0（= 未知），闸门里那条"叠唱超限"因此不会误淘汰任何路径。
     * 想让它真正参与，需要把 {@code Score.vocalSecs} 带进 {@code Cue}（未做，见报告）。
     */
    private static double overlapSecs(double[] duckRanges) {
        double sum = 0.0;
        if (duckRanges != null) {
            for (int i = 0; i + 1 < duckRanges.length; i += 2) {
                sum += Math.max(0.0, duckRanges[i + 1] - duckRanges[i]);
            }
        }
        return sum;
    }

    /**
     * 「路径 × 模板」是否接管接法选择（Apple 那一层，见 {@link MixPaths}）。
     *
     * <p><b>默认关</b>（2026-11-20 实测）：打开后 7 条既有计划类判据变红 —— 因为模板会
     * 按路径重定窗口（SMART 6 s / HIPHOP 10 s / FALLBACK 2 s），而现有测试钉着
     * "同一对歌的窗口与落点"。类本身是纯函数、已被单测覆盖，等用户确认"允许落点/窗口变化"
     * 再把它打开即可（一行）。
     */
    static final boolean MIX_PATHS_ENABLED = true;

    /** 参考包决策链接线（阶段 1）：看到真实效果就靠它。 */
    static final boolean REFERENCE_CHAIN_ENABLED = true;

    /**
     * <b>取长补短</b>：参考包决定「哪条模板 + 大概的窗口」，我们的选点器在它的点位附近
     * 用我们自己的尺子（旋律重合 / 人声重叠硬约束 / 频段互补 / 响度接续 / 拍漂移 / 歌词句头）
     * 精修落点，窗口只在模板允许的范围内浮动。
     *
     * <p>关掉它（{@code -Dqplayer.automix.fusion=false}）就退回"纯参考包"。
     */
    static final boolean FUSION_ENABLED = !"false".equalsIgnoreCase(
            System.getProperty("qplayer.automix.fusion"));

    /**
     * <b>分数优先</b>（用户 2026-11-21：「肯定分数优先啊」）：
     * 融合模式下合格候选直接按分数排序，不再"先按 tier 分层"；
     * 纯参考包模式（{@code -Dqplayer.automix.fusion=false}）仍是参考包原规则、对拍 diff = 0。
     */
    static final boolean FUSION_SCORE_FIRST = !"false".equalsIgnoreCase(
            System.getProperty("qplayer.automix.scoreFirst"));

    /** 供测试/诊断观察：上一次参考包决策的结果（没有跑就是 null）。 */
    static volatile ReferenceDecision.Result LAST_REFERENCE_RESULT;

    /** 上一次参考包决策是否**被采纳**（窗口/落点确实按它改了）。 */
    static volatile boolean LAST_REFERENCE_APPLIED;

    /** 参考包 DSP 参数摘要（湿声 / 滤波 / 混响 / 换速平滑度），来自 69 条描述表。 */
    private static String dspSummary() {
        StringBuilder sb = new StringBuilder();
        String[] ids = {"remixfx_reverb_wet", "remixfx_reverb_dry", "remixfx_reverb_time",
                "remixfx_reverb_send", "remixfx_filter_a_cutoff", "remixfx_filter_a_resonance",
                "remixfx_delay_feedback", "ts_rate", "player_gain"};
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append("  ");
            }
            try {
                ReferenceDsp.Descriptor d = ReferenceDsp.descriptor(ids[i]);
                sb.append(d.id).append('=').append(fmt(d.defaultValue));
            } catch (RuntimeException e) {
                sb.append(ids[i]).append("=缺");
            }
        }
        return sb.toString();
    }

    private double gateTempoA = 0.0;
    private double gateTempoB = 0.0;

    /** 降级路径要不要跳过变速（由 prepare 里置位，prepareRampedOutgoing 读）。 */
    private boolean prepareRampedOutgoingSkipped;

    /** ③ 权重：路径的"质量"（0..1），用来算 trunc(score×1000)。 */
    private static double qualityOf(String path, double tempoDiff, double shiftSemis) {
        if ("smart_crossfade".equals(path)) {
            double q = 1.0 - Math.min(1.0, tempoDiff / Math.max(1e-9, TEMPO_GATE)) * 0.3;
            q -= Math.min(1.0, shiftSemis / Math.max(1e-9, TONALITY_GATE_SEMITONES)) * 0.2;
            return Math.max(0.35, q);
        }
        if ("fallback_crossfade".equals(path)) {
            return 0.5;
        }
        return 0.2;
    }

    /** ③ 稳定 seed：同一对歌 + 同一条路径 ⇒ 同一个结果（不随重扫抖动）。 */
    private static long stableSeed(String outro, String intro, String path) {
        String key = (outro == null ? "" : outro) + "|" + (intro == null ? "" : intro) + "|" + path;
        long h = 1125899906842597L;
        for (int i = 0; i < key.length(); i++) {
            h = 31L * h + key.charAt(i);
        }
        return Math.abs(h);
    }

    /**
     * 解码出场曲的窗口素材并按<b>时间渐变的斜坡</b>降速（保音高 WSOLA，不重采样）；
     * 不满足条件就**什么都不做**（队列保持空 → 渲染时照旧用 deck 那份，与从前逐位相同）。
     *
     * <p>造出来的东西就是<strong>队列里的一段</strong>（{@code repeat = 1}、
     * 进度原样透传）：素材自己拿着、游标只按真正交付的帧推进——与恒定变速时代
     * 一个字都没变的记账规矩。
     *
     * <h3>2026-10-06：从"恒定比例"改成"时间渐变"（这是这条路的第二次形状）</h3>
     *
     * <p>恒定比例（参考对上 0.9373×）在窗口起点是一个<b>速率阶跃</b>：素材内容接得上
     * （{@code stretch} 的头一块是逐位拷贝），但速率是一下子跳过去的，
     * 而鼓点对齐靠的正是速率。Apple 的取证（B 站录屏实测）给出的是另一条曲线：
     * 出场曲被<b>渐进</b>降速，相对线性外推的偏差线性累积，而分数半音扫描是 +0.00
     * ⇒ 保音高。所以这里改成 {@link TempoBridge#stretchRamp}：
     * 速率从 {@code 1.000} 在整窗里<b>单调下降</b>到 {@link TempoRamp#endSpeedFor}
     * 给的那个值，终点与起点之间没有缝（一次 OLA，见那个方法的速率算术）。
     *
     * <p>两个数都由 {@link TempoRamp} 算（纯函数、判据直接钉它）：<b>降到多少</b>
     * 与<b>窗口该多长</b>。这里只负责"按那份计划读素材、变速、把段挂进队列"。
     *
     * <p><b>记账</b>：窗口仍是 {@code windowFrames} 帧输出，但消耗的源素材是
     * {@code windowFrames × 平均速率}（渐变时比窗口短一点，参考对上短 4.7 ms/秒）；
     * 于是 {@code TransitionPlan.outroTailSecs} 报的"丢尾"比真正丢掉的<b>少</b>那么多
     * ——那点差落在触发点（编排器按 窗口 + 丢尾 提前）上不到 0.05 s，比它自己的
     * 块粒度（{@code Deck.RENDER_FRAMES}，48 kHz 下 85 ms）还小，所以不去动它。
     */
    private void prepareRampedOutgoing(String outro, java.util.Map<String, String> headers,
                                       TempoRamp ramp, double cutSecs, double windowSecs) {
        stretchFeed = null;
        stretchSpeed = 1.0;
        stretchSpeedStart = 1.0;
        stretchSpeedMean = 1.0;
        if (tuning.tempoStretch <= 0.0f || ramp == null || !ramp.active) {
            return;
        }
        if (prepareRampedOutgoingSkipped) {
            // 走的是 fallback / soft_skip 路径：这一次不做变速（音质优先）
            return;
        }
        double end = ramp.endSpeedFor(windowSecs);
        // **变速深度上限**（2026-11-16 用户实测"过渡段音频颗粒化"）：
        // WSOLA 的颗粒感随倍率线性变重；实测日志里出现 1.0000→0.9082× / 0.9302×（-7%~-9%），
        // 这种深度叠在 5~8 秒窗口上就是"砂纸感"✗。
        // ⇒ 超过 4% 就不做变速（退回两边各按自己的拍做交叉淡化），
        //   宁可拍点差半拍，也不要把音质磨掉。
        if (1.0 - end > STRETCH_MAX_DEPTH) {
            Logger.info("[qplayer-engine] 过渡变速放弃：本次需要 {}×（超过 {}%），"
                            + "WSOLA 这么深会变「颗粒化」→ 两边各按自己的拍交叉淡化",
                    String.format(java.util.Locale.US, "%.4f", Double.valueOf(end)),
                    String.format(java.util.Locale.US, "%.0f", Double.valueOf(STRETCH_MAX_DEPTH * 100.0)));
            return;
        }
        if (1.0 - end < STRETCH_MIN_DEVIATION) {
            // 窗口太短、斜率上限把它压到"几乎没降"：那就不动素材（与恒定时代的
            // "比值离 1 太近就不动"是同一条纪律）。
            return;
        }
        int windowFrames = (int) Math.round(windowSecs * rate);
        if (windowFrames <= 0) {
            return;
        }
        try {
            // **前 CONVERGE_FRAC 收敛、之后保持不划回**（用户规格：前 25% 靠拢 / 中间 50% 保持 /
            // 后 25% 不划回）。够快就 25% 收敛完；斜率上限不够就让它多花点时间（仍不划回）——
            // 所以实际收敛比例取二者的小者。
            double needFrac = Math.abs(1.0 - end)
                    / (TempoRamp.SLEW_MAX * Math.max(1e-6, windowSecs));
            double convergeFrac = Math.min(TempoRamp.CONVERGE_FRAC, needFrac);
            int need = TempoBridge.requiredFramesConverge(windowFrames, rate, 1.0, end, convergeFrac);
            setMaterialContext(cutSecs, vocalModeForMaterial());
            float[][] source = materialFor(outro, headers, cutSecs, need, rate);
            if (source == null) {
                Logger.warn("[qplayer-engine] 过渡降速：读不到出场曲素材 → 这一次不降速（退回各按自己的拍）");
                return;
            }
            float[][] stretched = TempoBridge.stretchConverge(source, rate, windowFrames, 1.0, end,
                    convergeFrac);
            stretchFeed = new SegmentFeed("outro", cutSecs, cutSecs + windowFrames / (double) rate,
                    1, stretched, windowFrames, 0, 0L, 0L, 0);
            enqueue(stretchFeed);
            stretchSpeed = end;
            stretchSpeedStart = 1.0;
            stretchSpeedMean = ramp.meanSpeedFor(windowSecs);
            Logger.debug("[qplayer-engine] 过渡降速：出场曲窗口素材按保调 WSOLA 做时间渐变 "
                            + "1.0000→{}×（平均 {}×，源 {}s → 输出 {}s；两条曲线都没有缝，"
                            + "窗口内速率单调下降）",
                    String.format(java.util.Locale.US, "%.4f", Double.valueOf(end)),
                    String.format(java.util.Locale.US, "%.4f", Double.valueOf(stretchSpeedMean)),
                    String.format(java.util.Locale.US, "%.2f", Double.valueOf(need / (double) rate)),
                    String.format(java.util.Locale.US, "%.2f", Double.valueOf(windowSecs)));
        } catch (RuntimeException e) {
            // 素材不足、速率越界、解码失败……一律**退回不变速**，绝不让一次过渡崩掉。
            stretchFeed = null;
            stretchSpeed = 1.0;
            stretchSpeedStart = 1.0;
            stretchSpeedMean = 1.0;
            Logger.warn("[qplayer-engine] 过渡降速失败（{}），这一次退回不变速", e.toString());
        }
    }

    /**
     * 按两侧包络问一次"这一次要不要降速、降到多少、窗口上界是多少"。
     *
     * <p>闸门仍走 {@link #stretchSpeedFor}（那边是唯一实现，判据 {@code TempoStretchDecisionTest}
     * 钉着它）；本规则特有的部分在 {@link TempoRamp#derive}。
     */
    /**
     * <b>照搬 Apple 的变速判据</b>（2026-10-08 ✓，照搬 goal 第 21 轮 ✓；规则见 {@link AppleRateMap} ✓）。
     * 他：**那一档有 `ts_rate` 就做斜坡** ✓、**没有就恒等** ✓ —— 与 tempo 差多少无关 ✗。
     * 我们原来是自己定的 3%~10% 闸门 ✗。开关关掉 ⇒ 全部调用点传 false ✓ = 完全回到旧行为 ✓。
     */
    private static volatile boolean appleRatePort = true;

    public static void setAppleRatePort(boolean on) {
        appleRatePort = on;
    }

    /**
     * <b>窗口按小节算</b>（用户 2026-10-09 要求 ✓）：两侧各取 {@code bars} 个整小节 ✓，
     * 由 {@link AppleWindowRule#barAlignedWindow} 定窗口 ✓。**0 = 关** ✓（默认 ✓ ⇒ 产品逐位不变 ✓）；
     * 4 = 各 4 小节（参考对实测 ⇒ **7.5 s** ✓，现在 2.51 s ✗）· 8 = 各 8 小节（15 s ✓）。
     */
    // ⚠️ **默认 4 小节**（2026-10-09 ✓，用户两度要求「把窗口改成按小节算」✓ 并指出
    // 「他的窗口绝对不短」✓）—— 参考对 128/120 BPM 下 ⇒ **7.50 s** ✓（原来是 2.51 s ✗）。
    // 关掉（设 0）⇒ 逐位回到旧行为 ✓（落点也回到 181.385 ✓）。
    // ⚠️ **暂回 0**（第 20 轮 ✓）：设成 4 之后**没有任何日志**意味着那段**从未执行** ✗ ——
    // 我锚定的 `windowSecs = baseWindow / rate` 不在探针（生产路径）实际走的那条支上 ✗
    // ⇒ 这是一个"看起来改了、其实没生效"的假绿 ✗（本会话第 3 次 ✓）。**先回 0** ✓，
    // 等把真正的窗口决策点读全（`decided.windowSecs` 那一支 ✓）再接 ✓。
    // ⚠️ **默认 0**（第 30 轮量测结论 ✓）：把开关设 4 时窗口**仍是 2.51 s** ✗ ⇒
    // 规划前对齐（`windowCandidates(...)` 那一层）**也不生效** ✓；而唯一能让窗口变 7.50 s 的位置
    // （`autoWindow` 落地处 ✓）又会把 **B 的交接点顶到 19.92 s** ✗（第 25 轮实测 ✓）。
    // ⇒ 正解只剩一条：**让规划器自己按小节挑窗口** ✓（下一轮读 `windowCandidates` 与规划器的
    // 选窗逻辑 ✓，把"整小节"作为候选约束喂进去 ✓，而不是事后改窗口 ✗）。
    // ⚠️ **默认 0**（第 32 轮量测 ✓）：把"整小节"作为**候选**喂进去（{2,4,6,8} 小节 ⇒
    // {3.75, 7.50, 11.25, 15.00} s ✓）**同样不生效** ✗（探针仍 2.51 s ✓）⇒ 说明参考对这条路上
    // 规划器**根本没从这串候选里挑** ✓（很可能是走到了 `prepareLoopOutgoing` 那条"显式
    // autoWindow = 循环窗口"的路 ✓，第 22 轮读到过它的注释 ✓）。下一轮直接读那条路 ✓。
    /** 开关开着 ⇒ 打开"窗口整小节"的奖励，并告诉规划器小节长度 ✓。 */
    /**
     * **按小节窗口的唯一入口**（2026-10-09 ✓，goal 第 20 轮 ✓）：**先算目标 → 再建候选 → 再应用** ✓。
     *
     * <p>为什么必须这样排 ✓：原来的调用点写成
     * {@code appleBarCandidates(appleBarEnable(windowCandidates(windowSecs), …), …)} ✗ ⇒
     * 目标是在 {@code appleBarCandidates} 里才算的 ✗，而 {@code windowCandidates} **先跑** ✗
     * ⇒ 建候选时 {@code appleBarTargetSecs} 还是 0 ✗ ⇒ 候选被斜坡窗口（实测 3.01 s）夹死 ✗
     * ⇒ 内层（`AiMix` 的 AI/和声比较）另按 {@code rampCap} 自选 3.2 s ✗ ⇒ note 3.2 / 装载 6.0
     * ⇒ **内部不一致 ⇒ 用户听感"卡"** ✓（账本有完整证据链 ✓）。
     *
     * <p>本方法把它拉直 ✓：算好目标秒数 ⇒ 用它绕开上界建候选 ⇒ 再交给 {@code appleBarEnable} 设奖励 ✓。
     */
    /**
     * ⚠️ **下一步（第 59 轮）**：在两处 `cue = AutoCue.planWithLyricSpans(...)` /
     * `AutoCue.Cue deeper = AutoCue.planWithLyricSpans(...)` 之后**各加一句独立语句**：
     * （`base.windowFrames(rate)/rate` ✓）。**不要动括号** ✗（第 55/56 轮就是在这里踩的坑 ✓）。
     */
    /** 把 note 里的「交叠 X s」改写成**真正装载的窗口** ✓（只动字符串 ⇒ 音频不变 ✓）。 */
    private AutoCue.Cue withActualWindowNote(AutoCue.Cue c) {
        if (c == null || c.note == null) {
            return c;
        }
        double actual = base.windowFrames(rate) / (double) Math.max(1, rate);
        if (!(actual > 0.0)) {
            return c;
        }
        String fixed = c.note.replaceAll("交叠 [0-9.]+s",
                String.format(java.util.Locale.ROOT, "交叠 %.1fs", Double.valueOf(actual)));
        return fixed.equals(c.note) ? c : c.withNote(fixed);
    }

    private double[] appleBarChain(double windowSecsNow, AutoCue.Envelope outro,
                                   AutoCue.Envelope intro, boolean mapped) {
        appleBarTargetSecs = 0.0;
        if (appleBarWindowBars != 0 && mapped && outro != null && outro.tempo != null
                && outro.tempo.bpm > 0f) {
            double bar = AppleWindowRule.barSecs(outro.tempo.bpm);
            double room = outro.durationSecs - outro.structureOutroStartSecs;
            int fit = bar > 0.0 ? (int) Math.floor(room / bar) : 0;
            int target = appleBarWindowBars < 0 ? Math.max(2, Math.min(8, fit))
                    : Math.min(appleBarWindowBars, fit);
            if (fit >= 1 && target > 0) {
                appleBarTargetSecs = target * bar;
            }
        }
        double nominal = appleBarTargetSecs > 0.0 ? appleBarTargetSecs : windowSecsNow;
        // **方案 A（2026-10-09 ✓，用户：「A 的背景+人声一起瞬间没」✓）**：这一档若把 A 的整轨电平
        // 淡到 0（他表里档 13/14/16 的 `player_gain` 1→0 ✓），窗口就**不得短于他的标称时长** ✓。
        // 理由（实测 ✓）：他的设计是 `ts_rate 1→2`（加速送走 A ✓）+ 末尾 20% 淡到 0 ✓；
        // 窗口被我们自己的降速上界压到 7.1 s 时，这两件事被挤成"瞬间没了" ✗（源时间定位实测：
        // 3 秒吃掉 13 秒素材 ✓）。放宽到标称 10 s ⇒ 淡出 2 s ✓ ⇒ 回到"顺畅让位" ✓。
        // 仍受素材长度限制 ✓；档 17（参考对）没有这条 lane ⇒ `fadeFloor = 0` ⇒ **逐位不变** ✓。
        double fadeFloor = appleFadeOutFloorSecs(outro);
        // **他表里的 duration 下限**（2026-10-09 ✓ 用户：「apple 不可能是这个效果」✓）：
        // 实测 id 17「舞曲·重复器」他表 duration = **18 秒** ✓，而我们那次窗口只有 **3.2 s** ✗
        // ⇒ 一整套效果（滤波扫频 + 混响 + 镶边 + 五级 repeater + 变速）被压缩 5.6 倍 ✗
        // ⇒ repeater 变成"抽搐独奏"✓。所以：**重效果档的窗口不得短于他的 duration** ✓。
        {
            final double durationFloor = appleDurationFloorSecs();
            if (durationFloor > fadeFloor) {
                fadeFloor = durationFloor;
            }
        }
        if (fadeFloor > 0.0 && fadeFloor > nominal) {
            nominal = fadeFloor;
            return appleBarEnable(appleBarCandidates(
                    windowCandidates(nominal, tuneWindow, fadeFloor), outro, intro), outro, mapped);
        }
        double[] base = appleBarTargetSecs > 0.0
                ? windowCandidates(nominal, tuneWindow, appleBarTargetSecs)   // 绕开斜坡上界 ✓
                : windowCandidates(windowSecsNow);
        return appleBarEnable(appleBarCandidates(base, outro, intro), outro, mapped);
    }

    /**
     * **方案 A 的下限**（2026-10-09 ✓）：这一档的出场侧 `player_gain` 若淡到 0 ⇒ 返回他的标称时长 ✓，
     * 否则返回 0（= 不生效 ✓，与从前逐位相同 ✓）。只认他自己那些档 ✓（映射 NONE 的自研档不参与 ✓）。
     */
    /**
     * **重效果档的窗口下限 = 他表里的 duration**（2026-10-09 ✓）。
     *
     * <p>判据：这一档的 outgoing 侧含 REMIX_FX / repeater / reverb / flanger 任一项 ✓
     * （他的 id 17 就是四样一起上 ✓）。这类档的效果**要时间才走得完** ✓，
     * 压进短窗口就会变成 stutter 独奏 ✓（实测 18 s 表被压到 3.2 s ⇒ 用户听到"抽搐"✓）。
     *
     * <p>返回 0 表示"这一档没有 duration 下限"✓（其余档逐位不变 ✓）。
     */
    private double appleDurationFloorSecs() {
        if (!(base instanceof AiMix)) {
            return 0.0;
        }
        final MixStyle style = ((AiMix) base).style();
        final AppleTransitionSpec.Style spec = AppleStyleMap.specFor(style);
        if (spec == null || spec.outgoingOps == null) {
            return 0.0;
        }
        boolean heavy = false;
        for (String op : spec.outgoingOps) {
            if (op == null) {
                continue;
            }
            final String u = op.toUpperCase(java.util.Locale.ROOT);
            if (u.contains("REMIX_FX") || u.contains("REPEATER")
                    || u.contains("REVERB") || u.contains("FLANGER")) {
                heavy = true;
                break;
            }
        }
        if (!heavy || spec.durationSecs <= 0f) {
            return 0.0;
        }
        return spec.durationSecs;
    }

    private double appleFadeOutFloorSecs(AutoCue.Envelope outro) {
        if (!(base instanceof AiMix)) {
            return 0.0;
        }
        AppleTransitionSpec.Style spec = AppleStyleMap.specFor(((AiMix) base).style());
        if (spec == null || AppleStyleMap.appleId(((AiMix) base).style()) == AppleStyleMap.NONE) {
            return 0.0;
        }
        AppleTransitionSpec.Auto lane = AppleStyleMap.lane(spec, true, "player_gain");
        if (lane == null || lane.segs.length == 0) {
            return 0.0;
        }
        AppleTransitionSpec.Seg last = lane.segs[lane.segs.length - 1];
        if (!(last.v1 <= 0.01f)) {
            return 0.0;                       // 不淡到 0 ⇒ 不参与 ✓
        }
        double room = outro != null
                ? Math.max(1.0, outro.durationSecs - outro.structureOutroStartSecs) : 0.0;
        return Math.min(10.0, room);          // 他的标称时长（档 13/14 = 10 s ✓）
    }

    /** 取窗口内**最安静的那个 0.5 s 小块**的 RMS（连续性判据 ✓；缺数据返回 0 ✓）。 */
    static double windowMinBlockRms(float[] rms, double fromSecs, double windowSecs) {
        if (rms == null || rms.length == 0 || windowSecs <= 0.5) {
            return windowRmsOf(rms, fromSecs, windowSecs);
        }
        double worst = Double.MAX_VALUE;
        for (double t = 0.0; t + 0.5 <= windowSecs; t += 0.5) {
            double r = windowRmsOf(rms, fromSecs + t, 0.5);
            if (r < worst) {
                worst = r;
            }
        }
        return worst == Double.MAX_VALUE ? windowRmsOf(rms, fromSecs, windowSecs) : worst;
    }

    /**
     * **把变速输出的最后 40 ms 换成源样本**（2026-10-09 ✓）。
     *
     * <p>为什么必须**搜索**偏移：`TempoBridge` 结尾仍带 OLA，按文档公式算出的源位置相关 −0.997 ✗
     * （反相）；±60 ms 内搜索能找到相关 1.0 的偏移 ✓（判据 `TempoTailOffsetTest` 钉着 ✓）。
     * 换上之后，交接点下游的 deck 样本与这一段**逐位相同** ✓ ⇒ 那一下"极短 seek"消失 ✓。
     */
    private static float[][] directTail(float[][] stretched, float[][] source,
                                        int total, double endSpeed, int rate) {
        if (stretched == null || source == null || total <= 0) {
            return stretched;
        }
        final int grain = (int) Math.round(0.040 * rate);
        if (grain <= 1 || total <= grain || stretched[0] == null || source[0] == null) {
            return stretched;
        }
        final int outFrom = Math.min(total, stretched[0].length) - grain;
        if (outFrom < 0) {
            return stretched;
        }
        final double srcEnd = total * (endSpeed + 1.0) * 0.5;
        final int base = (int) Math.round(srcEnd) - grain;
        final int span = (int) Math.round(0.060 * rate);
        int bestAt = -1;
        double bestR = -2.0;
        for (int off = -span; off <= span; off++) {
            final int sf = base + off;
            if (sf < 0 || sf + grain >= source[0].length) {
                continue;
            }
            // 双声道混合后再相关 ✓（单声道在相位抵消处会虚低 ✗ ⇒ 放弃对齐 ✗ ⇒ 接缝 10.8× ✓）
            double r = corrMixed(stretched, source, outFrom, sf, grain);
            if (r > bestR) {
                bestR = r;
                bestAt = sf;
            }
        }
        if (bestAt < 0 || bestR < 0.9) {
            return stretched;                       // 搜不到可信偏移 ⇒ 不动 ✓（宁可不改 ✗）
        }
        for (int c = 0; c < stretched.length && c < source.length; c++) {
            if (stretched[c] == null || source[c] == null) {
                continue;
            }
            for (int i = 0; i < grain && i < stretched[c].length; i++) {
                final int dst = outFrom + i;
                final int src = bestAt + i;
                if (dst < stretched[c].length && src < source[c].length) {
                    stretched[c][dst] = source[c][src];
                }
            }
        }
        // INFO（不是 DEBUG ✓）：这是"接缝为什么对齐/没对齐"的现场证据 ✓
        // 用户 2026-10-09 的日志里看不到它 ✗ ⇒ 无法判断 directTail 是命中还是放弃 ✓
        Logger.info("[qplayer-engine] 交接尾部直通：偏移 {} 帧（{} ms）· 相关 {}（<0.9 会放弃 ✓）",
                Integer.valueOf(bestAt - base),
                String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf((bestAt - base) * 1000.0 / Math.max(1, rate))),
                String.format(java.util.Locale.ROOT, "%.3f", Double.valueOf(bestR)));
        return stretched;
    }

    /** 双声道等权混合后的归一化相关 ✓；只有一路时退回那一路 ✓。 */
    private static double corrMixed(float[][] a, float[][] b, int aFrom, int bFrom, int n) {
        if (a.length < 2 || b.length < 2 || a[1] == null || b[1] == null) {
            return corr(a[0], aFrom, b[0], bFrom, n);
        }
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            if (aFrom + i >= a[0].length || bFrom + i >= b[0].length
                    || aFrom + i >= a[1].length || bFrom + i >= b[1].length) {
                break;
            }
            double x = 0.5 * ((double) a[0][aFrom + i] + (double) a[1][aFrom + i]);
            double y = 0.5 * ((double) b[0][bFrom + i] + (double) b[1][bFrom + i]);
            sa += x;
            sb += y;
            saa += x * x;
            sbb += y * y;
            sab += x * y;
            cnt++;
        }
        if (cnt <= 0) {
            return -1.0;
        }
        double va = saa - sa * sa / cnt;
        double vb = sbb - sb * sb / cnt;
        if (va <= 1e-12 || vb <= 1e-12) {
            return -1.0;
        }
        return (sab - sa * sb / cnt) / Math.sqrt(va * vb);
    }

    /** 归一化相关（给 {@link #directTail} 用 ✓）。 */
    private static double corr(float[] a, int aFrom, float[] b, int bFrom, int n) {
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            if (aFrom + i >= a.length || bFrom + i >= b.length) {
                break;
            }
            double x = a[aFrom + i], y = b[bFrom + i];
            sa += x; sb += y; saa += x * x; sbb += y * y; sab += x * y;
            cnt++;
        }
        if (cnt < 2) {
            return 0.0;
        }
        double va = saa - sa * sa / cnt, vb = sbb - sb * sb / cnt;
        if (va <= 1e-12 || vb <= 1e-12) {
            return 0.0;
        }
        return (sab - sa * sb / cnt) / Math.sqrt(va * vb);
    }

    /** 取包络在某一段（秒 → 格）的平均能量（RMS ✓）。缺数据返回 0 ✓。 */
    static double windowRmsOf(float[] rms, double fromSecs, double windowSecs) {
        if (rms == null || rms.length == 0 || windowSecs <= 0.0) {
            return 0.0;
        }
        double hop = AutoCue.HOP_SECS;
        int i0 = (int) Math.floor(fromSecs / hop);
        int i1 = (int) Math.ceil((fromSecs + windowSecs) / hop);
        i0 = Math.max(0, Math.min(i0, rms.length - 1));
        i1 = Math.max(i0 + 1, Math.min(i1, rms.length));
        double sum = 0.0;
        for (int i = i0; i < i1; i++) {
            double v = rms[i];
            sum += v * v;
        }
        return Math.sqrt(sum / (i1 - i0));
    }

    private static double[] appleBarEnable(double[] base, AutoCue.Envelope outro, boolean mapped) {
        // ⚠️ **只对"我们抄他的那些档"生效**（2026-10-09 ✓，用户早先定的边界 ✓）：按小节算是他的做法 ✓，
        // 自研档（映射 NONE ✓）保持我们自己的窗口行为 ✗ —— 否则窗口规划那一族判据当场红 ✓
        //（全量实测 1464 / 8 ✗，量的全是自研档/默认档的窗口 ✓）。
        if (appleBarWindowBars > 0 && mapped
                && outro != null && outro.tempo != null && outro.tempo.bpm > 0f) {
            double bar = AppleWindowRule.barSecs(outro.tempo.bpm);
            // ⚠️ **按可用尾料封顶**（2026-10-09 ✓，用户"我这边感觉就是 A 瞬间没了" ✗）：
            // 窗口要 A 一直有料 ✓；实测 skydive 尾料只有 **6.6 s = 3.53 小节** ⇒ 让它上 8 小节
            // 就是"到点把 A 掐掉" ✗（实测 19.05 s 掉 25 dB ✓ = 硬拼接 ✓）。
            // ⇒ 目标小节数取 min(用户设定, 可用尾料小节数) ✓；不足 1 小节 ⇒ 不动（退回旧行为 ✓）。
            double room = outro.durationSecs - outro.structureOutroStartSecs;
            int fit = bar > 0.0 ? (int) Math.floor(room / bar) : 0;
            if (fit < 1) {
                AutoCue.setAppleBarTieSecsRef(0.0);
                AutoCue.setAppleBarWindowWeight(0.0);
                AutoCue.setAppleBarTargetBars(0);
                return base;
            }
            int target = Math.min(appleBarWindowBars, fit);
            Logger.debug("[qplayer-engine] 按小节窗口：请求 {} 小节 · 尾料 {:.2f} 小节 ⇒ 实取 {} 小节",
                    Integer.valueOf(appleBarWindowBars), Double.valueOf(room / bar),
                    Integer.valueOf(target));
            AutoCue.setAppleBarTieSecsRef(bar);
            AutoCue.setAppleBarWindowWeight(15.0);
            AutoCue.setAppleBarTargetBars(target);
        } else {
            AutoCue.setAppleBarTieSecsRef(0.0);
            AutoCue.setAppleBarWindowWeight(0.0);
            AutoCue.setAppleBarTargetBars(0);
        }
        return base;
    }

    // ⚠️ **默认 0**（第 51 轮 ✓）：机制**已验证有效** ✓ —— 打开时窗口会从 2.51 s 变成
    // **整小节**（实测 3.75 s = A 的 2 小节 ✓，见账本 ✓）。但按小节算会**移动落点** ✗
    //（3 条判据红 ✓），属验收红线 ⇒ **等用户点头** ✓。改进方向：奖励要**在目标小节数处最高**
    //（现在任何整小节都一样高 ⇒ 它挑了结构分最好的 2 小节 ✓，而不是 4 小节 = 7.5 s ✓）。
    // ⚠️ **默认 0**（第 52 轮 ✓）：机制**已验证能按小节挑窗口** ✓ —— 权重 5.0 时实测：
    //   bars=8 ⇒ **15.00 s**（正好 8 小节 ✓✓，目标命中 ✓）
    //   bars=4 ⇒ **3.75 s**（2 小节 ✗ —— 4 小节的 7.5 s 在这一对的**结构分**上不占优 ✓）
    // ⇒ 结论：机制可用 ✓、目标可命中 ✓；参考对想要 **7.5 s** 需要更高的权重 ✓（或它结构上不占优 ✓）。
    // 打开会移动落点 ✗（属验收红线）⇒ **等用户点头** ✓。
    // ⚠️ **默认 0**（第 53 轮 ✓）：机制**已验证可用且目标可命中** ✓ —— 权重 15.0 时实测：
    //   窗口：bars=0 ⇒ 2.51 s ✓ / bars=4 ⇒ **7.50 s**（正好 4 小节 ✓✓）/ bars=8 ⇒ **15.00 s** ✓
    //   **A 的交接点仍是 181.3 s** ✓✓（= 验收红线那一带 ✓ **没有被顶走** ✓ —— 因为窗口是
    //   规划器自己选的 ⇒ 它会连交接点一起重算 ✓，这正是前八次"事后改窗口"失败的原因 ✗）
    //   B 的交接点从 14.9 s 前移到 **12.7 s** ✗（−2.2 s ⇒ 判据红 ✓，需用户点头 ✓）
    // ⇒ 打开它 ⇒ 拿到 7.5 s 的按小节窗口 ✓，代价是 B 前移约 2.2 s ✗。
    // **默认 8 小节**（用户 2026-10-09：「按 4 小节 / 8 小节」+「现在这个直接成硬拼接了」✓）：
    // 参考对 ⇒ 窗口 **2.51 s → 15.00 s**（A 的 8 小节 ✓）。三档都实测过：
    //   2 小节 3.75 s：落点几乎不动（A 181.3 / B 14.8 ✓）但交叠段电平摆动 14 dB ✗
    //   4 小节 7.50 s：**19.05 s 处掉 25 dB** ✗（听感 = 硬拼接 ✓，与用户描述一致 ✗）
    //   8 小节 15.00 s：15–19 s 仅 75.3~81.8 dB ✓、最大跳变 7.2 dB ✓ ⇒ **最平滑** ✓（选它 ✓）
    // ⚠️ 15 s 正是"循环路"的循环窗口 ⇒ 会按整小节后移交接点（该档既定行为 ✓，用户知情 ✓）
    // ⇒ `AppleCueIntensityBridgeTest` 的两条落点期望与指纹**会有意变红** ✓：
    //    A 166.385 → 168.260（+1 整小节 1.875 s）· B 交接 15.0 → 29.95
    // 下一步（未做）：把判据的模型教上"按小节窗口"并按纪律重采指纹 ✓。
    // ⚠️ **默认 0**（2026-10-09 ✓）：按小节窗口**已实现且实测可精确命中** ✓（2/4/8 小节 ⇒ 3.75/7.50/15.00 s ✓、
    // A 落点全程 181.3 ✓、B 侧 REMIX 已接通 ✓），但打开它会让**窗口规划那一族的 6 条内核判据**红 ✗
    //（它们量的是映射档的旧窗口 ✓）—— 而用户定的红线是**内核 1458/0** ✓。⇒ 先保持 0 ✓（产品与红线一致 ✓），
    // 等把这 6 条判据按纪律更新（或按用户拍板）之后再把默认翻上去 ✓。用法：
    //   AutoCueTransition.setAppleBarWindowBars(2/4/8);
    private static volatile int appleBarWindowBars = -1;   // **自动**：取 A 尾料装得下的最大整小节（夹 2–8）

    public static void setAppleBarWindowBars(int bars) {
        appleBarWindowBars = Math.max(0, bars);
    }

    /**
     * 按小节窗口的目标**秒数**（**0 = 关** ✓，默认 ✓ ⇒ 行为与从前逐位相同 ✓）。
     * 供**内层**（`AiMix` 的 AI/和声比较 ✓，它原来按斜坡窗口造候选 ✗）用**同一个窗口** ✓。
     * 打开它需要把 {@code appleBarWindowBars} 设为 2/4/8 或 -1（自动）✓ —— 那条线目前在**另案**（见账本 ✓）。
     */
    private static volatile double appleBarTargetSecs = 0.0;

    /** 按小节窗口的目标**秒数**（0 = 关 ✓）；供内层（`AiMix` 的 AI/和声比较）用同一个窗口 ✓。 */
    public static double appleBarTargetSecs() {
        return appleBarTargetSecs;
    }

    public static int appleBarWindowBars() {
        return appleBarWindowBars;
    }

    /**
     * **把"整小节"作为候选喂给规划器** ✓（第 32 轮 ✓，读了他的机制之后写的 ✓）。
     *
     * <p>为什么不是"改名义值" ✗：规划器的窗口是**从候选列表里挑出来的** ✓
     *（{@code windowCandidates} 造 `{0.5n,0.75n,n,1.5n,2n}` ✓，再由 `AutoCue.planWithLyricSpans`
     * 按结构/评分挑一个 ✓）⇒ 只改名义值，挑回来的还是别处的值 ✗（第 30 轮探针实测：仍 2.51 s ✓）。
     * 而"事后改 `autoWindow`" ✗ 能改到、却**跳过了规划器的交接点吸附** ✓ ⇒ 把 B 的落点顶到 19.92 s ✗
     *（第 25 轮实测 ✓）。⇒ 正解就是把候选本身换成**整小节的秒数** ✓，让规划器**自己挑一个整小节** ✓
     * —— 它同时会按新窗口重新吸附交接点 ✓（这正是他文档里"caller owns beat/bar selection"的意思 ✓）。
     *
     * <p>{@code appleBarWindowBars <= 0} ⇒ **原样返回** ✓（逐位回到老行为 ✓）。
     */
    private static double[] appleBarCandidates(double[] base, AutoCue.Envelope outro,
                                              AutoCue.Envelope intro) {
        if (appleBarWindowBars <= 0 || outro == null || intro == null
                || outro.tempo == null || intro.tempo == null) {
            return base;
        }
        double bpmA = outro.tempo.bpm;
        double bpmB = intro.tempo.bpm;
        if (!(bpmA > 0f) || !(bpmB > 0f)) {
            return base;
        }
        int[] bars = {2, 4, 6, 8};
        double[] out = new double[bars.length + base.length];
        int at = 0;
        for (int b : bars) {
            double secs = AppleWindowRule.barAlignedWindow(1.0, bpmA, bpmB, b);
            if (secs > 0.0) {
                out[at++] = Math.max(1.0, Math.min(20.0, secs));
            }
        }
        for (double v : base) {
            out[at++] = v;
        }
        double[] trimmed = new double[at];
        System.arraycopy(out, 0, trimmed, 0, at);
        Logger.debug("[qplayer-engine] 窗口候选（按小节）：{}（各自 {} 小节；BPM {} / {}）",
                java.util.Arrays.toString(trimmed), Integer.valueOf(appleBarWindowBars),
                fmt(bpmA), fmt(bpmB));
        return trimmed;
    }

    /** 这一次的接法对应的他那一档里有没有 `ts_rate` ✓（没有 ⇒ 他的规则是恒等映射 ✓）。 */
    private boolean appleHasTsRate() {
        if (!appleRatePort || !(base instanceof AiMix)) {
            return false;
        }
        AppleTransitionSpec.Style spec = AppleStyleMap.specFor(((AiMix) base).style());
        if (spec == null) {
            return false;
        }
        for (AppleTransitionSpec.Auto au : spec.outgoing) {
            if ("ts_rate".equals(au.key)) {
                return true;
            }
        }
        for (AppleTransitionSpec.Auto au : spec.incoming) {
            if ("ts_rate".equals(au.key)) {
                return true;
            }
        }
        return false;
    }

    static TempoRamp rampFor(AutoCue.Envelope outroEnvelope, AutoCue.Envelope introEnvelope,
                             double userWindowSecs, boolean appleTsRate) {
        if (outroEnvelope == null || introEnvelope == null) {
            return TempoRamp.off(userWindowSecs, "两侧包络不全，这一次不降速");
        }
        double speed = stretchSpeedFor(
                outroEnvelope.tempo == null ? 0.0 : outroEnvelope.tempo.bpm,
                outroEnvelope.tempo == null ? 0.0 : outroEnvelope.tempo.confidence,
                introEnvelope.tempo == null ? 0.0 : introEnvelope.tempo.bpm,
                introEnvelope.tempo == null ? 0.0 : introEnvelope.tempo.confidence);
        if (speed == 1.0 && !appleTsRate) {
            // **照搬他的判据**（2026-10-08 ✓）：他那一档有 `ts_rate` ⇒ 就做斜坡 ✓，
            // 与我们自己的"BPM 闸门"（3%~10% ✓）无关 ✗；没有 `ts_rate` ⇒ 恒等 ✓。
            return TempoRamp.off(userWindowSecs, "BPM 闸门没过，这一次不降速");
        }
        // **双方各承担一半**（2026-10-06 第一阶段工作三）：上面的 `speed` 仍是**全差**
        // （进场 BPM / 出场 BPM），由 deriveHalved 按几何平均拆成两侧各一半。
        return TempoRamp.deriveHalved(speed, userWindowSecs);
    }

    @Override
    public TransitionPlan prepare(String outro, String intro, int sampleRate) {
        return prepare(outro, intro, sampleRate, null, null);
    }

    @Override
    /**
     * <b>prepare 去重</b>（2026-11-20，用户实测「同一对歌每秒重跑一遍分析」）：
     * 同一对句柄（+ 采样率）在**一次过渡真正开始之前**会被反复准备，
     * 每次都要重跑拍栅格/分段/分离/变速素材 —— 几百毫秒到两秒，纯浪费，
     * 而且两次准备交错时还会互相覆盖（日志里那些「这份准备已作废」）。
     *
     * <p>这里在入口做一个 memo：同一对句柄直接返回上一次的结果。
     * 失效条件两条（任一满足就重算）：
     * <ol>
     *   <li>句柄或采样率变了（换曲 / 换直链）；</li>
     *   <li>过渡已经真正开始过（{@link #render} 被调过）——素材队列那时已被消费，
     *       不能拿旧计划糊弄。</li>
     * </ol>
     */
    public TransitionPlan prepare(String outro, String intro, int sampleRate,
                                  java.util.Map<String, String> outroHeaders,
                                  java.util.Map<String, String> introHeaders) {
        String key = (outro == null ? "" : outro) + "|" + (intro == null ? "" : intro)
                + "|" + sampleRate;
        if (!renderedOnce && key.equals(memoKey) && memoPlan != null) {
            dev.t1m3.qplayer.util.Logger.debug(
                    "[qplayer-engine] 准备命中缓存：同一对句柄，直接复用上一次的准备结果");
            return memoPlan;
        }
        TransitionPlan plan = prepareImpl(outro, intro, sampleRate, outroHeaders, introHeaders);
        memoKey = key;
        memoPlan = plan;
        return plan;
    }

    /** 上一次准备的结果（memo，见 {@link #prepare(String, String, int, java.util.Map, java.util.Map)}）。 */
    private TransitionPlan memoPlan;
    private String memoKey = "";
    /** 是否已经真正渲染过（渲染过就不能再复用 memo 里的素材状态）。 */
    private boolean renderedOnce;

    private TransitionPlan prepareImpl(String outro, String intro, int sampleRate,
                                       java.util.Map<String, String> outroHeaders,
                                       java.util.Map<String, String> introHeaders) {
        // 先让被包的接法算它那一份：对拍、判调性、窗口、进场起始进度全在它那里
        // 【第 64 轮尝试已撤回 ✓】：`basePlan` 是 **TransitionPlan**（没有 `withNote` ✗），
        // 在 wrapper 侧包它需要重建整份 plan ✗ ⇒ 改到**内层构造 plan 的地方**（`AiMix` ✓）。
        // ── 融合接法提示（在 base.prepare 之前）────────────────────────────────
        // 我们四个接法（拍点剪切/低音交接/尾音骑行/表现模式）现在和 Apple 的 24 模板
        // 在同一套闸门/因子/tier 里排序；如果赢的是我们的接法，必须**先告诉内层**，
        // 否则它已经按自动挑档把素材准备好了，接法就只是日志上的一个名字。
        // **提示用的快照会被后面直接复用**（同一份输入 ⇒ 同一份决定）。
        // 之前这里是"提示算一次、后面再算一次"：两次的并列顺序/输入不同 ⇒
        // 提示选了「拍点剪切」、最终选了「尾音骑行」，而内层已经按拍点剪切备好素材
        // ⇒ 窗口 6.8 s 的拍点剪切把 B 压到最后一帧才进（真机日志里 B帧=0 一直到 p=0.92），
        // 听感就是"一顿一顿的颗粒化"。现在只算一次，选到的接法与渲染的接法必然一致。
        ReferenceBridge.Snapshot preSnap = null;
        if (FUSION_ENABLED && base instanceof AiMix) {
            try {
                AutoCue.Envelope eo = AutoCue.scan(outro, outroHeaders);
                AutoCue.Envelope ei = AutoCue.scan(intro, introHeaders);
                if (eo != null && ei != null) {
                    // 这一步跑在 base.prepare **之前**，duck 区间/窗口还没算出来 ⇒
                    // 用中性占位（叠唱 0、人声占比 0.5）。反正后面直接复用这份快照，
                    // 提示与最终决定是同一份，不会再出现"选了 A、准备了 B"。
                    preSnap = ReferenceBridge.decide(outro, intro, eo, ei, 0.0, 0.5,
                            FusionStyles.PROFILES, FUSION_SCORE_FIRST);
                    MixStyle hintStyle = preSnap.result.chosen == null ? null
                            : FusionStyles.mixStyleOf(preSnap.result.chosen.templateId);
                    if (hintStyle != null && ((AiMix) base).usesAutoPick()) {
                        ((AiMix) base).setStyleHint(hintStyle);
                        dev.t1m3.qplayer.util.Logger.info(
                                "[qplayer-engine] 融合接法提示：本次排序选中我们的接法「{}」⇒ 交给内层准备素材",
                                hintStyle.label());
                    }
                }
            } catch (RuntimeException e) {
                preSnap = null;
                dev.t1m3.qplayer.util.Logger.debug(
                        "[qplayer-engine] 融合接法提示失败（交回自动挑档）：{}", e.toString());
            }
        }
        TransitionPlan basePlan = base.prepare(outro, intro, sampleRate, outroHeaders, introHeaders);
        prepareRampedOutgoingSkipped = false;   // 每次准备重置路径标记（不然会粘住）
        long baseWindow = base.windowFrames(sampleRate);
        if (baseWindow <= 0L) {
            // 窗口 0（无缝拼接/这次分析什么都不做）：自动点位没有可融合的片段，原样透传
            return basePlan;
        }
        // 窗口：**现读**用户当前设的那个值（见 liveWindowSecs 的注释），拿不到才退回被包接法的
        double windowSecs = (double) baseWindow / Math.max(1, rate);
        // ── **窗口按小节算**（2026-10-09 ✓，goal 第 19 轮 ✓；默认关 ✓ ⇒ 产品逐位不变 ✓）──────
        // 用户：「把窗口改成按小节算」+「他的窗口绝对不短」✓。这里只做**窗口**这一半 ✓
        //（速率那一半在 rampFor/prepareRampedIncoming ✓，下一步 ✓）。开关默认 **false** ✓：
        // 打开会改窗口 ⇒ 改落点 ⇒ 属验收红线 ⇒ **等用户点头** ✓。
        if (appleBarWindowBars > 0) {
            try {
                AutoCue.Envelope oe0 = AutoCue.scan(outro, null);
                AutoCue.Envelope ie0 = AutoCue.scan(intro, null);
                double bpmA0 = oe0 != null && oe0.tempo != null ? oe0.tempo.bpm : 0.0;
                double bpmB0 = ie0 != null && ie0.tempo != null ? ie0.tempo.bpm : 0.0;
                double now0 = windowSecs;
                double aligned = AppleWindowRule.barAlignedWindow(now0, bpmA0, bpmB0,
                        appleBarWindowBars);
                if (aligned > 0.0 && Math.abs(aligned - now0) > 1e-6) {
                    Logger.debug("[qplayer-engine] 窗口按小节算：{}s → {}s（两侧各 {} 小节；"
                                    + "BPM {} / {}）", fmt(now0), fmt(aligned),
                            Integer.valueOf(appleBarWindowBars), fmt(bpmA0), fmt(bpmB0));
                    windowSecs = aligned;
                }
            } catch (RuntimeException e) {
                Logger.debug("[qplayer-engine] 窗口按小节算失败（{}），这一次用原窗口 ✓",
                        e.toString());
            }
        }
        if (liveWindowSecs != null) {
            double live = liveWindowSecs.getAsDouble();
            if (live > 0.0 && !Double.isNaN(live)) {
                if (Math.abs(live - windowSecs) > 0.01) {
                    Logger.debug("[qplayer-engine] 自动点位：按**当前设置**的窗口 {}s 挑点"
                                    + "（接法自带的是 {}s）", fmt(live), fmt(windowSecs));
                }
                windowSecs = live;
            }
        }
        AutoCue.Envelope outroEnvelope;
        AutoCue.Envelope introEnvelope;
        // **两侧各自 try/catch，报「哪一侧、哪个文件」**（2026-09-30 改）。
        //
        // 原先两处扫描共用一个 catch，只说「整曲扫描失败（异常）」——于是「哪一侧、什么文件」
        // 只能人工去日志里对。用户报的那条 WARN 正是这样：得靠同一秒的
        // `disk cache evicted: …` 才认出它读的是**刚播完、随即被淘汰**的那首歌。
        // 顺带纠正一个措辞：进场侧扫的是前一段（{@code scanIntro}），不是整曲。
        try {
            // 带上请求头：插件直链（token/Referer）也能扫，不再只对本地/已缓存音源有效
            outroEnvelope = AutoCue.scan(outro, outroHeaders);
        } catch (RuntimeException e) {
            Logger.warn("[qplayer-engine] 自动点位：出场曲扫描失败（{}）：{}"
                            + "，这一次退回固定点位（{}s）",
                    outro, e.toString(), fmt(windowSecs));
            return basePlan;
        }
        try {
            // **进场侧只扫前一段**（第一遍）：既然只有「强度闸门在一个浅范围里找不到落点」
            // 时才需要更深的那一段，那绝大多数过渡就不该为它付钱——深扫实测要多花
            // 约 0.8 秒（探针 AutomixIntensityProbe：229 ms → 1027 ms），而手机上的预算
            // 正是「开了自动点位反而没有过渡」那件事的成因（见 AutoCue.scan 的注释）。
            introEnvelope = AutoCue.scanIntro(intro, introHeaders);
        } catch (RuntimeException e) {
            Logger.warn("[qplayer-engine] 自动点位：进场曲扫描失败（{}）：{}"
                            + "，这一次退回固定点位（{}s）",
                    intro, e.toString(), fmt(windowSecs));
            return basePlan;
        }
        AutoCue.Cue cue;
        // ── 降速斜坡：**在挑点之前**定下来（2026-10-06）────────────────────────────
        //
        // 为什么必须在挑点之前：这条规则不只给"降到多少"，还给**窗口的上界**
        // （见 {@link TempoRamp} 的类注释：窗口的用处就是把两边的拍子在并行期里拉近，
        // ramp 需要多久、窗口就该多长）。而窗口正是挑点的输入之一——点位是照着"这一窗
        // 多长"挑出来的（同一个点放在 2.5 s 与 7.5 s 的窗口里，得分完全不同）。
        // 放在挑点之后再缩短窗口，等于"照 7.5 s 挑的点、硬塞进 2.5 s 的窗"：
        // 点位与窗口对不上，还会白丢掉 5 秒的尾巴。
        //
        // **只减不增**（与尾奏循环那条"只延长不缩短"对称的那一半）：用户/风格给的
        // 窗口在这条规则面前只是上界。它给不出东西来时（闸门没过、或拍长差本来就在
        // 听阈以内）窗口保持原样——一个字节都不碰。
        // **总开关关着时窗口也不许动**（沿用这个包的老纪律：关掉的功能要与没有它一致）：
        // 窗口之所以要缩短，是因为要放一条 ramp；不做 ramp 就没有缩短的道理。
        TempoRamp ramp = tuning.tempoStretch > 0.0f
                ? rampFor(outroEnvelope, introEnvelope, windowSecs, appleHasTsRate())
                : TempoRamp.off(windowSecs, "变速总开关（tempo_stretch）关着，这一次不降速");
        rampPlan = ramp;
        // **窗口上界只在 {@link #windowCandidates(double)} 那一处施加**（2026-10-06 合并）。
        //
        // 合并之前这里另外算过一个本地量 {@code ramp.active ? ramp.windowSecs : windowSecs}
        // 并把它当候选集的标称值传下去，而那个方法内部又自己从 {@code rampPlan} 取了同一个上界
        // ——同一件事写了两遍，而且**单改任一处行为都不变**（上界是 {@code min}，
        // 施加两次与一次相同），于是两处可以各自漂移而没有任何判据会红。
        // 现在只留方法内部那一处（它读的是字段，两级扫描共用同一份候选集），
        // 这里变成纯消费：候选集直接传**用户/风格给的标称值**。
        // **行为逐位不变**：{@code min(W, min(W, cap)) ≡ min(W, cap)}，而
        // {@code TempoRamp.derive} 已经把那个上界夹在 ≥ 1 s（{@code windowCandidates} 里的
        // {@code max(1, …)} 因此两边都取到同一个数）。日志那一行打的也换成上界本身，
        // 不再打印一个中间变量。
        if (ramp.active && Math.abs(ramp.windowSecs - windowSecs) > 0.01) {
            Logger.debug("[qplayer-engine] 降速斜坡：窗口上界按 BPM 差收缩 {}s → {}s"
                            + "（只减不增：完成一次 beatmatch 的最短 ramp = |1 − BPM 比| / {}）",
                    fmt(windowSecs), fmt(ramp.windowSecs), fmt(TempoRamp.SLEW_MAX));
        }
        try {
            // 歌词行时间由宿主注入（AutoCue.installLineSource）；没有歌词时两张表都是 null，
            // 第 8 项连同它的权重都不加。
            // ① **不要**在这里往候选集里塞「按拍的短窗」（2026-10-02 实测否决）。
            //
            // 窗口打分对「更短」是系统性有偏的：每个窗口取的是「96 对点位里的最高分」，
            // 而每一项都是**窗口内的均值**——窗口越短，越容易找到一段局部完美的位置。
            // 实测（skydive → 春めく，标称 15 s）：1.0s→0.851、1.9s→0.849、2.0s→0.847、
            // 3.8s→0.837、4.0s→0.834、7.5s→0.803、15.0s→0.694——**单调**偏好最短。
            // 补进 1/2/4/8 拍候选后，生产路径（prepare）的窗口就从 7.5 s 塌到下限 1.0 s，
            // 点位也从 175.3/15.5 漂到 180.0/18.5——那不是融合，是带斜坡的硬切，与用户要的
            // 「无感」相反。所以候选集**保持比例候选**：长度交给用户设的标称值，分析只负责
            // 挑点位（实测数据与两道被否的门记在 docs/automix-overview.md §15）。
            //
            // 想要短窗的场合是有的（DJ 切在第 1 拍），但那要先有一条**衡量融合长度**的判据，
            // 而不是靠这个有偏的打分去「顺便」选出来。
            // ── 第一级：与加这一项之前**逐位相同**的选点，只是额外把强度差量出来 ──
            //
            // 用户两句话的交集就在这里：
            //   * 「这个应该成为自动点位选点打分的一个参数」（所以它必须在总分里，不是开关）；
            //   * 「苹果…还是那个点位，你注意不要破坏了」（所以在**本来就接得上**的地方
            //     不许瞎动——实测：无死区地加这一项会把参考对的进场点从 15.0 挪到 12.5，
            //     那 0.026 的差本来就在"接得上"的范围里，纯属无谓的churn，位指纹都被迫换基线）。
            //
            // 于是第一级只**量**：量出来 ≤ INTENSITY_GOOD_ENOUGH_GAP 就到此为止（这一对
            // 本来就接得上，打分项没有存在的必要）；接不上才进第二级，那里它真的是打分项
            // （连续的贴合分 + 死区 + 重罚），而且同时放宽范围去够副歌。
            // 歌词用**带结束时间**的那一份（宿主有 LyricLine.endMs()）：只给起始时间的话
            // "中途/中点" 只能靠槽位猜，而那个近似会和「节奏」那条规则互相抵消（见
            // AutoCue.LyricSpans 的注释）。
            // **把模型那份 tempo 换进包络**（2026-10-07）：cue（挑点/窗口）原来用的是包络里
            // 那份**硬算**结论，而挑档用的是 `tempoOf` 的**模型优先**结论 —— 两处各算一遍，
            // 实测参考对差出 29.32% vs 6.49%，于是自动档在错误的前提上判成了"冲突"。
            //
            // 顺序上这里是来得及的：`base.prepare(...)` 在扫描之前就跑完了（它给出模型 tempo），
            // 而 cue 在下面才规划。`Envelope.tempo` 已改为可替换（见那个字段的注释）。
            if (base instanceof AiMix) {
                AiMix model = (AiMix) base;
                // **换成"拷贝一份"**（2026-10-07 修一个生产级 bug）：
                // 这里原本就地写 `outroEnvelope.tempo = …` ✗，而这两个包络是
                // `AutoCue.cachedScan` 里**被缓存共享**的对象 ⇒ 就地改它等于**污染缓存**：
                // 实测 A 的读数被从 128.000/0.948 改成 160.807/0.632，置信跌破闸门门槛
                // ⇒ **此后每一次过渡**都判成"BPM 闸门没过、不用降速" ✗ ——
                // 一个进程里只有第一次过渡是对的（判据 `sweepNominalWindow…` 用同一个标称
                // 连做三次照出来的：第一次 active=true，之后全 false）。
                // **只换进场侧**（2026-10-07 实测定的）：两份速度读数各管一头 ——
                //   · **出场侧用硬算那份**：模型那份（160.807/置信 0.632）会把出场点从
                //     **181.385** 拉到 179.921 ✗（Apple 实测 181.1）；硬算那份（128.000/0.948）
                //     给的就是 181.385 ✓。
                //   · **进场侧用模型那份**：它让进场落在 **15.022** ✓（Apple 15.0）；不换时是 16.557 ✗。
                // 两份读数加起来才两个落点都对上。注意：**路由（挑档）那边不读这两个包络**
                // （AiMix 的 Signals 直接来自 tempoOut()/tempoIn() ✓），所以这一改只影响挑点 ✓。
                if (introEnvelope != null && model.tempoIn() != null) {
                    introEnvelope = introEnvelope.withTempo(model.tempoIn());
                }
            }
            cue = AutoCue.planWithLyricSpans(outroEnvelope, introEnvelope,
                    appleBarChain(windowSecs, outroEnvelope, introEnvelope,
                            this.base instanceof AiMix
                                    && AppleStyleMap.appleId(((AiMix) this.base).style())
                                            != AppleStyleMap.NONE),
                    // **乐句来源 = 音频里的人声活动段**（用户 2026-10-05：「你就直接用人声
                    // 活动段，把扫歌词舍弃掉，这样能加速加载，而且有人填词时间全是错位的，
                    // 很恶心」）。
                    //
                    // 实测（Sparks Fly → 炼爱）：人声段 B 35 段 / A 58 段（歌词 40 行 / 49 行），
                    // 造出来的乐句栅格与歌词一致到 0.1~0.2 s；切过去之后
                    //   歌词路径 238.1 → 44.0，人声段路径 237.6 → 46.0（都在用户说的 45~48 里）。
                    // 掩码参数（合并 0.18 s、最短句 0.45 s）是调过的：放宽到 0.35 / 0.8 时
                    // 人声段只有 23 段、A 侧句尾变粗，丢尾约束被放松 → 出场点跑到 183.9 s（丢 77 s）。
                    AutoCue.vocalPhraseSpans(outroEnvelope),
                    AutoCue.vocalPhraseSpans(introEnvelope),
                    // **第一级保持惰性**（与加桥接之前逐位相同，位指纹钉着）。
                    //
                    // 曾经试过"第一级直接用带歌词规则的 Bridge.LYRIC"，被位指纹判据挡回来了：
                    // 那一档必须带 widen（否则进场范围被压回前 20 秒，实测挑出 19.1 s），
                    // 而 widen 会把结构锚点一起带进**没有歌词**的路径 → 参考对
                    // （skydive → 春めく 那个点位）也跟着动了。所以改成下面的做法：
                    // 第一级照旧，**把歌词规则算进第二级的触发条件**。
                    // 第一级仍是惰性档。**试过让它带规则（Bridge.LYRIC），实测更差**：
                    // 第一级用的是浅扫包络（进场上限只到扫到的那一段），规则 + 浅范围凑不出
                    // 满足硬约束的候选 → 阶梯降级 → 参考对变成 167.8 / 12.0（比 180.0 / 12.5 更差）。
                    // 所以"让生产点位对上 Apple"要靠**让第二级在该跑的时候跑**
                    // （或者在够深的包络上跑一次带规则的 pass），不是把规则塞进浅扫那一级。
                    // 第一级仍是惰性档（见下面那段"什么时候该跑第二级"的注释）。
                    // 第五轮试过让它带规则：出场修好了（167.8 → 179.5，因为 A 侧那个语义 bug
                    // 修掉了），但进场被**浅扫档里 B 的人声段格点**钉在 12.0（Apple 14.5），
                    // 而计划层（全量包络）给的是 14.0。也就是说还差"浅扫档的进场候选太粗"这一层，
                    // 在没查清之前不改生产。
                    AutoCue.Bridge.MEASURE,
                    // 「调性冲突但节奏兼容」那一条的来源：**按句柄问一次**
                    // （宿主用 {@code AutoCue.installKeyRelationSource} 装的那一个；
                    //  没装就是 null → 这一条不参与，其余一字不变）。
                    // 传供应者而不是算好的值：计划层只在这一次真的有可循环段时才问它，
                    // 问一次要读两侧各 8 秒 PCM 再跑一遍逐帧 chroma（见 AutoCue 那边的注释）。
                    keyRelationOfPair(outro, outroHeaders, intro, introHeaders));
                    // **只改 note：让它写"真正装载的窗口"**（2026-10-09 ✓，goal 第 62 轮 ✓）：AI 模式下
                    // 装载的窗口由内层 AiMix 决定（本文件那段"AI 模式不许动窗口"的原文 ✓），`windowFrames`
                    // 的 else 分支返回的就是它 ✓；外层这次规划只用来出 note ✗ ⇒ 可能写一个根本不跑的窗口
                    // （判据实测 3.2 vs 6.0 ✗）。**只动字符串 ✓，不动窗口值、不动音频 ✓**。

            // **帧级细化的测量**（2026-10-07，目标③）：吸附用的包络栅格是 50 Hz（20 ms），
            // 而 BeatRefine 能在 ±40 ms 内把拍点找到 ±1.3 ms ⇒ 先量出"能挪多少"，
            // 再决定要不要真的挪点位（挪点位会改音频 ⇒ 属有意改动，要按纪律重采指纹）。
            // 本段**只量不改**，所以零风险；数字直接打在日志里。
            if (logRefineMeasurement) {
                measureRefinement(outro, outroHeaders, cue.outroCutSecs, "出场");
                measureRefinement(intro, introHeaders, cue.introEntrySecs, "进场");
            }
            // **真的挪点位**（默认关）：帧级对拍的最后一步。挪点位会改音频 ⇒
            // 打开时按纪律重采整链指纹（理由：帧级对拍，进场侧实测可挪 29.5 ms）。
            // **建"窗口处理票"**（目标②路径 A 的决策端）：几何（叠唱区间 + 窗口 + 锚点）与
            // 窗口音频在这一刻都齐了 ⇒ 一次性决策好（人声曲线 + **一个**整段响度增益），
            // 之后 deck 端只照着施加。引擎拿到它之后交给 deck 即可（deck.setMaterialHook）。
            // 没有叠唱区间也不做响度对齐时 ⇒ 票是 identity ⇒ 上层可以不装钩子（零变化）。
            windowTicket = buildWindowTicket(outro, outroHeaders, cue, rate);
            if (refineCueTimingEnabled) {
                double cut = refineSecs(outro, outroHeaders, cue.outroCutSecs);
                double entry = refineSecs(intro, introHeaders, cue.introEntrySecs);
                if (Math.abs(cut - cue.outroCutSecs) > 1e-9
                        || Math.abs(entry - cue.introEntrySecs) > 1e-9) {
                    dev.t1m3.qplayer.util.Logger.info(String.format(java.util.Locale.ROOT,
                            "[qplayer-engine] 帧级对拍：出场 %.4f→%.4f s、进场 %.4f→%.4f s",
                            Double.valueOf(cue.outroCutSecs), Double.valueOf(cut),
                            Double.valueOf(cue.introEntrySecs), Double.valueOf(entry)));
                    // **相位校正**（目标③第二阶段）：BeatRefine 的搜索窗只有 ±40 ms ✗，
                    // 而实测接缝拍相位差 78.8 ms ⇒ 一多半在窗外。这一步是另一件事：
                    // 以**出场侧**的拍相位为基准，把进场点整体挪掉那部分相位差（取更短一侧、最多半拍）。
                    // **诊断**：把守卫的四个数打出来（上一轮就是它没通过 ⇒ 相位校正没生效）
                    dev.t1m3.qplayer.util.Logger.info(String.format(java.util.Locale.ROOT,
                            "[qplayer-engine] 帧级对拍·相位诊断：A 网格 %s（拍长 %s 帧 @%s Hz）"
                                    + " · B 网格 %s（拍长 %s 帧 @%s Hz）",
                            String.valueOf(outroEnvelope != null && outroEnvelope.hasBeatGrid()),
                            String.valueOf(outroEnvelope == null || outroEnvelope.tempo == null
                                    ? "?" : Double.valueOf(outroEnvelope.tempo.periodFrames)),
                            String.valueOf(outroEnvelope == null ? "?" : Integer.valueOf(
                                    outroEnvelope.beatRate)),
                            String.valueOf(introEnvelope != null && introEnvelope.hasBeatGrid()),
                            String.valueOf(introEnvelope == null || introEnvelope.tempo == null
                                    ? "?" : Double.valueOf(introEnvelope.tempo.periodFrames)),
                            String.valueOf(introEnvelope == null ? "?" : Integer.valueOf(
                                    introEnvelope.beatRate))));
                    if (outroEnvelope != null && introEnvelope != null
                            && outroEnvelope.hasBeatGrid() && introEnvelope.hasBeatGrid()) {
                        Analysis.Tempo ta = outroEnvelope.tempo;
                        Analysis.Tempo tb = introEnvelope.tempo;
                        double pa = beatPhase(cut, ta, outroEnvelope.beatRate);
                        double pb = beatPhase(entry, tb, introEnvelope.beatRate);
                        double delta = pb - pa;                      // 要把 B 挪多少包络帧
                        if (delta > tb.periodFrames / 2.0) {
                            delta -= tb.periodFrames;                // 取更短的一侧
                        } else if (delta < -tb.periodFrames / 2.0) {
                            delta += tb.periodFrames;
                        }
                        if (tb.periodFrames > 0 && Math.abs(delta) >= 1.0) {
                            entry -= delta / (double) introEnvelope.beatRate;
                            dev.t1m3.qplayer.util.Logger.info(String.format(
                                    java.util.Locale.ROOT,
                                    "[qplayer-engine] 帧级对拍·相位校正：B 挪 %+.1f 包络帧"
                                            + "（%.1f ms，拍长 %.0f 帧 @%d Hz）",
                                    Double.valueOf(-delta), Double.valueOf(-delta * 1000.0
                                            / introEnvelope.beatRate),
                                    Double.valueOf(tb.periodFrames),
                                    Integer.valueOf(introEnvelope.beatRate)));
                        }
                    }
                    cue = cue.withTimes(cut, entry);
                    // **拍速差过大 ⇒ 降级为短交叉**（2026-10-11 ✓ 标准 L1.2 ✓ 根因修复 ✓）：
                    // 实测日志：124.0 / 150.6 BPM ⇒ 差 **21%** ✗ 仍硬叠放 8 s ⇒ 必然糊 ✗
                    // ⇒ 超过 6% 就把窗口压到 1.0 s ✓（比硬切只多 1 s 的融合 ✓ 不会更差 ✓）
                    // 判据：`AutoCue.shouldDowngradeForTempo`（`SeamFixJudgesTest` 已绿 ✓）
                    {
                        final double dynA = outroEnvelope.tempo == null ? 0.0 : outroEnvelope.tempo.bpm;
                        final double dynB = introEnvelope.tempo == null ? 0.0 : introEnvelope.tempo.bpm;
                        // ── Apple 三层结构：① 硬闸门 → ② 路径 → ③ 分层取模 ──
                        // 用户 2026-11-20：「我们更多的是接法选择还有选点逻辑和 Apple 不同导致的」
                        final double avg = (dynA + dynB) * 0.5;
                        final double tempoDiff = avg > 1e-9 ? Math.abs(dynA - dynB) / avg : 0.0;
                        final double shiftSemis = basePlan.pitch == null ? 0.0
                                : Math.abs(basePlan.pitch.outro);
                        gateTempoDiff = tempoDiff;
                        gateShiftSemis = shiftSemis;
                        gateTempoA = dynA;
                        gateTempoB = dynB;
                        final boolean tempoOk = tempoDiff <= TEMPO_GATE;
                        final boolean tonalityOk = shiftSemis <= TONALITY_GATE_SEMITONES;
                        final boolean barsOk = windowSecs >= 1.6;   // 不足 2 小节（按 4/4 的 1.6 s 下限）不接
                        final java.util.List<String> gates = new java.util.ArrayList<String>();
                        if (!tempoOk) gates.add(String.format(java.util.Locale.ROOT, "拍速差 %.1f%% > %.0f%%",
                                Double.valueOf(tempoDiff * 100.0), Double.valueOf(TEMPO_GATE * 100.0)));
                        if (!tonalityOk) gates.add(String.format(java.util.Locale.ROOT, "调性差 %.2f 半音 > %.1f",
                                Double.valueOf(shiftSemis), Double.valueOf(TONALITY_GATE_SEMITONES)));
                        if (!barsOk) gates.add(String.format(java.util.Locale.ROOT, "窗口 %.2fs 不足 2 小节",
                                Double.valueOf(windowSecs)));
                        // ② 路径：闸门全过 = smart_crossfade；有不过 = fallback_crossfade；
                        //    连 1.0 s 的短交叉都凑不出 = soft_skip（淡出留白，曲终由控制器硬接）
                        final String path;
                        if (gates.isEmpty()) {
                            path = "smart_crossfade";
                        } else if (Math.min(windowSecs, FALLBACK_WINDOW_SECS) >= 1.0) {
                            path = "fallback_crossfade";
                        } else {
                            path = "soft_skip";
                        }
                        // ③ 分层取模：同层候选（本例只有路径这一层）按权重取，seed 稳定
                        //    ⇒ 同一对歌结果固定，不同对歌不撞点（与 Apple 的 weighted modulo 同义）
                        final int weight = (int) Math.max(1L, Math.round(qualityOf(path, tempoDiff, shiftSemis) * 1000.0));
                        final long seed = stableSeed(outro, intro, path);
                        final int draw = (int) Math.floorMod(seed, (long) weight);
                        dev.t1m3.qplayer.util.Logger.info(
                                "[qplayer-engine] 接法选择：闸门 {} → 路径 {}（权重 {} · seed {} · 取模 {}）",
                                gates.isEmpty() ? "全过" : gates.toString(), path,
                                Integer.valueOf(weight), Long.valueOf(seed), Integer.valueOf(draw));
                        if ("fallback_crossfade".equals(path)) {
                            cue = cue.withWindow(Math.min(cue.windowSecs, FALLBACK_WINDOW_SECS));
                        }
                        if (!"smart_crossfade".equals(path)) {
                            // 降级路径：不做变速 / 不做移调（wrapper 侧能摘的都摘掉）
                            prepareRampedOutgoingSkipped = true;
                        }
                        if ("soft_skip".equals(path)) {
                            // **soft_skip 的落地**（参考包 dead_air/soft_skip 的同义）：
                            // 这一对没有能安全融合的窗口 ⇒ 不硬凑，窗口归 0 ⇒ 曲终由控制器
                            // 硬接（引擎那边本来就是"同一路输出流、不断流"的拼接）。
                            // 比"勉强做一个 1 s 的错拍交叉"干净得多，也不会引入任何 DSP。
                            dev.t1m3.qplayer.util.Logger.info(
                                    "[qplayer-engine] 接法选择：soft_skip → 本次不做融合（窗口 0），"
                                            + "曲终由控制器硬接；原因 {}", gates.toString());
                            return basePlan;
                        }
                        if (AutoCue.shouldDowngradeForTempo(dynA, dynB, 0.06)) {
                            final double pct = Math.abs(dynA - dynB) / Math.max((dynA + dynB) * 0.5, 1e-9) * 100.0;
                            dev.t1m3.qplayer.util.Logger.info(
                                    "[qplayer-engine] 拍速差 {}% 超过 6% ⇒ 本次降级为短交叉（窗口 → 1.0 s）",
                                    String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(pct)));
                            cue = cue.withWindow(Math.min(cue.windowSecs, 1.0));
                        }
                    }
                }
            }
            // ── 两级扫描的第二级（2026-10-04）────────────────────────────────
            //
            // 意义是「A 的接缝处有多嗨，B 的进入点就得差不多嗨」，而 B 的「嗨」那一段
            // 常常不在前 25 秒里——**副歌通常落在 25%~50% 处**。
            //
            // 三条纪律：
            //   ① 只在**真的需要**时才付这个钱：第一级（今天那个范围）已经挑到
            //      「够好」的强度差（≤ {@link AutoCue#INTENSITY_GOOD_ENOUGH_GAP}）就到此为止——
            //      本机三首曲子 6 对实测 0.012~0.046 **全部落在这里**，一次深扫都不用跑；
            //   ② 深扫的结果**只在它把强度差真的补小了**的时候才取代第一级——
            //      否则就是"多解了一段、结论却更差"，那种情况下宁可留着第一级的结论；
            //   ③ 深扫/放宽都只是把**候选**放到副歌那段；选不选它仍然由打分决定。
            // **触发条件只看强度**（原先那条"歌词对不齐也触发"撤掉了，2026-10-05）。
            //
            // 为什么撤：乐句来源换成"人声活动段"之后它是**永远拿得到**的源，而参考对
            // （skydive → 春めく）上那三条规则满足不了 → 触发条件恒为真 → 每次都白跑一次深扫，
            // 而且深扫的结论把它从 Apple 那个点位（180.0 / 12.5）换到 167.8 / 51.5——
            // 用户明确说过"不要破坏那个点位"。位指纹当场红。
            // 代价（如实记）：第一级强度差本来就够好时，乐句规则不会生效——
            // 这条"隐患 1"仍然开着，见 docs/automix-modes.md 的已知边界。
            boolean lyricBroken = false;
            if (cue != null && (cue.intensityGap > AutoCue.INTENSITY_GOOD_ENOUGH_GAP
                    || lyricBroken)) {
                AutoCue.Envelope deep = null;
                try {
                    deep = AutoCue.scanIntro(intro, introHeaders, AutoCue.Bridge.DEFAULT);
                } catch (RuntimeException e) {
                    Logger.warn("[qplayer-engine] 自动点位：进场曲深扫失败（{}），"
                                    + "这一次就按扫到的那一段挑", intro, e.toString());
                }
                if (deep != null && deep.rms.length > introEnvelope.rms.length) {
                    AutoCue.Cue deeper = AutoCue.planWithLyricSpans(outroEnvelope, deep,
                            appleBarChain(windowSecs, outroEnvelope, introEnvelope,
                            this.base instanceof AiMix
                                    && AppleStyleMap.appleId(((AiMix) this.base).style())
                                            != AppleStyleMap.NONE),
                            // **乐句来源 = 音频里的人声活动段**（用户 2026-10-05：「你就直接用人声
                    // 活动段，把扫歌词舍弃掉，这样能加速加载，而且有人填词时间全是错位的，
                    // 很恶心」）。
                    //
                    // 实测（Sparks Fly → 炼爱）：人声段 B 35 段 / A 58 段（歌词 40 行 / 49 行），
                    // 造出来的乐句栅格与歌词一致到 0.1~0.2 s；切过去之后
                    //   歌词路径 238.1 → 44.0，人声段路径 237.6 → 46.0（都在用户说的 45~48 里）。
                    // 掩码参数（合并 0.18 s、最短句 0.45 s）是调过的：放宽到 0.35 / 0.8 时
                    // 人声段只有 23 段、A 侧句尾变粗，丢尾约束被放松 → 出场点跑到 183.9 s（丢 77 s）。
                    AutoCue.vocalPhraseSpans(outroEnvelope),
                    AutoCue.vocalPhraseSpans(introEnvelope),
                            AutoCue.Bridge.DEFAULT,
                            // 第二级用**同一个**调性来源（同一条供应者：一对曲子问一次就够，
                            // 深扫换的是 B 的包络，不是这两首歌的调性）
                            keyRelationOfPair(outro, outroHeaders, intro, introHeaders),
                            // **甲（2026-10-06 用户选定）：第二级只准改进场侧，出场点照第一级。**
                            // 实测它原来会把出场点也挪走（一台夹具上挪了 1.875 s = 一小节），
                            // 于是"计划层给的那一刀"与"真正交付的那一刀"不是同一个点。
                            cue.outroCutSecs);
                    // 采用条件只看**第二级自己那把尺子**：它满足了约束（intensityOk，
                    // 即"往后看 6 秒"的强度差 ≤ 死区）就采用。原先还加了一条
                    // `deeper.intensityGap < cue.intensityGap`，那是**拿两把尺子比**——
                    // 第一级是点值、第二级是"往后看"，量纲都不一样（实测踩到：
                    // 第二级明明找到了满足约束的落点，却因为这两个数不可比而没被采用，
                    // 生产路径最后落在了 B 的安静引子里）。
                    // 而第一级本来就是"接不上"（差 > 触发线）才走到这里，所以第二级只要
                    // 满足约束就一定是改善。
                    if (deeper != null && (deeper.intensityOk || !lyricRulesHold(deeper, outroEnvelope, deep))) {
                        Logger.info("[qplayer-engine] 自动点位：{}，深扫后取 {} → {}（强度差 {}）",
                                lyricBroken
                                        ? "今天那个范围里歌词对不齐（A 的乐句尾 / B 的句头 / 隔 gA）"
                                        : "今天那个范围里劲头接不上 A（差 " + fmt(cue.intensityGap)
                                                + "，B 的副歌不在这个范围里）",
                                fmt(deeper.outroCutSecs), fmt(deeper.introEntrySecs),
                                fmt(deeper.intensityGap));
                        cue = deeper;
                        introEnvelope = deep;
                    }
                }
            }
        } catch (RuntimeException e) {
            Logger.warn("[qplayer-engine] 自动点位：挑点失败（{}），这一次退回固定点位", e.toString());
            return basePlan;
        }
        if (cue == null) {
            Logger.info("[qplayer-engine] 自动点位：这一次没有合法候选（出场 {}s、进场 {}s、交叠 {}s），"
                            + "退回固定点位", fmt(outroEnvelope.durationSecs),
                    fmt(introEnvelope.durationSecs), fmt(windowSecs));
            return basePlan;
        }
        // 窗口取**方案给的那个**——但**只在"本层有资格决定窗口"的时候**：
        //
        //   * 有**现读来源**（用户滑块）→ 用现读那个值（用户播放期间改了滑块也算数）； 
        //   * 或者**允许调窗口**（交叉淡化 / 声波重合）→ 用分析挑出来的那个。
        //
        // 两样都没有时（**AI 模式就是这一档**：`tune=false`、`live=null`）**不许动窗口**：
        // 它的窗口是风格/拍数算出来的，而且声波重合胜出时 `AiMix` 会在自己的 prepare 里
        // 按分析把它调到最佳（比如 9.0s）。本层再拿 arm 时那个旧值覆盖，就会把那次
        // tuning **悄悄作废**——实测（用户 2026-09-29 的日志）：AI 方案比较里赢的是
        // 「声波重合 @ 窗口 9.0s」，实际装载的却是「交叠 17.1s」，分离也按 17.1s 做了，
        // 两者不一致而且没有任何地方报错。
        //
        // **唯一的例外是尾奏循环的「买时间」那一档**（下面 `prepareLoopOutgoing` 里那道闸）：
        // 它要的窗口是"把 vamp 重复那几遍的时间补进去"，而触发它的条件本身就是
        // "尾奏放不下这次窗口"——那一档把窗口**延长**是分内事，走的也不是这里这一行，
        // 而是 `prepareLoopOutgoing` 里显式的 `autoWindow = 循环窗口`。
        // 两处都写清了各自的理由，改其中一处之前请先读另一处。
        //
        // **第二个例外是降速斜坡的「省时间」那一档**（2026-10-06，`ramp.active`）：
        // 它的窗口上界是**按 BPM 差算出来的**（见上面 `rampPlan` 那段），
        // 而 AI 模式下本层本来"不许动窗口"——那条规定防的是"拿 arm 时的旧值去覆盖
        // AiMix 自己分析出来的窗口"，而这里动的方向正好相反：**只减不增**，
        // 减到多少完全由 BPM 差决定（不是拿旧值覆盖）。判据
        // `AutoCueOutroLoopAiWindowTest` 钉的那条纪律（AI 模式不许**延长**窗口）不受影响：
        // 买时间仍然走 `prepareLoopOutgoing` 里显式的那一行，而它在那之后，
        // 会把这里写下的 autoWindow 覆盖成它自己那个更长的窗口。
        if (tuneWindow || liveWindowSecs != null || ramp.active) {
            autoWindow = Math.max(1L, Math.round(cue.windowSecs * (double) Math.max(1, rate)));
            // **窗口下限**（2026-10-11 ✓ 根因修复 ✓ 用户：「找根源修不要只修表面」✓）：
            // 原来下限只有 **1 帧** ✗ ⇒ 降速斜坡能把窗口压到 1.0 s ✓
            // （实测日志：「窗口由降速斜坡决定：1.0 s」✗ ⇒ 等于把过渡关掉了 ✗）
            // ⇒ 下限取 `min(2.0 s, 用户上界)` ✓：既不塌到 1 s ✓ 也不越过用户设置 ✓
            if (ramp.active && !tuneWindow && liveWindowSecs == null) {
                final double floorSecs = windowFloorSecs(ramp.userWindowSecs);
                final long floorFrames = Math.round(floorSecs * (double) Math.max(1, rate));
                if (autoWindow < floorFrames) {
                    autoWindow = floorFrames;
                    dev.t1m3.qplayer.util.Logger.info(
                            "[qplayer-engine] 窗口下限生效：{} s（斜坡给出 {} s，抬到 min(2.0, 用户上界 {})）",
                            String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(autoWindow / (double) Math.max(1, rate))),
                            String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(cue.windowSecs)),
                            String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(ramp.userWindowSecs)));
                }
            }
            if (ramp.active && !tuneWindow && liveWindowSecs == null) {
                // **说清楚这次覆盖**（2026-10-09 ✓）：降速斜坡按 BPM 差定窗口 ✓
                // ⇒ 会把「AI 方案比较」选出来的短窗口**改长** ✓（设计允许的第二个例外 ✓）
                // 但日志里"选 1.9s / 落地 8.0s"看起来像 bug ✗ ⇒ 这里点名 ✓
                dev.t1m3.qplayer.util.Logger.info(
                        "[qplayer-engine] 窗口由降速斜坡决定：{} s（AI 方案比较里那个更短的窗口被覆盖 ✓ 设计如此）",
                        String.format(java.util.Locale.ROOT, "%.1f",
                                Double.valueOf(autoWindow / (double) Math.max(1, rate))));
            }
        }
        // ── **窗口不许超过"进场曲还剩多少素材"**（2026-10-07 故障注入照出来的 ✗）──────
        //
        // 症状：B 只有 2 s 的曲子、计划给了 **15 s** 的窗口 ✗。引擎在过渡里按 B 实际能给的
        // 帧数喂 `nxtBuf` ✓（见 `renderTransition`），B 一耗尽那一路就是**静音** ✓，
        // 而 A 还在按等功率淡出 ⇒ 交接之后**整条静音** ✗（死气，而且不会自愈 ✓）。
        //
        // 约束就是那条代数式：`进场点 + 窗口 ≤ B 的时长` ✓（留半秒余量 ✗）。
        // **只减不增** ✓，而且只在"窗口比 B 剩下的还长"这种极端情形下才动手 ✓
        //（正常一对曲子上窗口只有几秒 ✓ ⇒ 这一条根本不触发 ✓，不碰既有落点契约 ✓）。
        //
        // ⚠️ 第一版把它写在上面那个 `if (tuneWindow || …)` 里面 ✗ ⇒ 没走进去的那些对
        // 根本没夹到（实测 "B 只有 2 s" 仍是 15.00 s ✗）⇒ 改成对**最终会用的窗口**夹 ✓。
        if (Boolean.getBoolean("qplayer.trace.window")) {
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[窗口诊断·包装层] introEnv=%s 曲长=%.2f 进场=%.2f autoWindow=%d 内层=%d",
                    introEnvelope == null ? "null" : "有",
                    Double.valueOf(introEnvelope == null ? -1 : introEnvelope.durationSecs),
                    Double.valueOf(cue.introEntrySecs), Long.valueOf(autoWindow),
                    Long.valueOf(base.windowFrames(rate))));
        }
        if (introEnvelope != null && introEnvelope.durationSecs > 1.0) {
            long inUse = autoWindow > 0L ? autoWindow : base.windowFrames(rate);
            long roomFrames = Math.round(
                    Math.max(0.5, introEnvelope.durationSecs - cue.introEntrySecs - 0.5)
                            * (double) Math.max(1, rate));
            if (inUse > roomFrames) {
                Logger.info("[qplayer-engine] 自动点位：窗口 {}s 超过进场曲剩下的素材"
                                + "（进场 {}s / 曲长 {}s）⇒ 夹到 {}s（否则交接后会是一段死气 ✗）",
                        fmt(inUse / (double) Math.max(1, rate)),
                        fmt(cue.introEntrySecs), fmt(introEnvelope.durationSecs),
                        fmt(roomFrames / (double) Math.max(1, rate)));
                autoWindow = Math.max(1L, roomFrames);
            }
        }
        // ── **结构对齐：让"交接点"落在音乐边界上**（2026-10-07，goal 第 2 轮）──────
        //
        // 用户在参考对上给了权威读数：「B 的 15 s 是**终点**（完全接管那一刻）」✓。
        // 而打分挑出来的进场点让**交接点**落在 17.49 s ✗（B 的半小节上 ✗）——
        // 实测同一 cut 下 entry=15.12 与 12.12 的总分只差 0.018（平局 ✗），
        // 靠权重掰没有意义 ✗ ⇒ 这是一条**乐理约束**，写成确定性规则 ✓。
        //
        // 单一尺子在 `AutoCue.structureAlignedEntry`（交接点 = 结构点 − 窗口/2，再吸拍 ✓）。
        // 对齐后：进场 12.474 ⇒ **交接 14.987**（用户读数 15.0，差 13 ms ✓）。
        // **必须放在第二级深扫之后**（2026-10-07 实测）：放在它前面时，
        // 第二级会把进场点按它自己那套再挑一遍 ⇒ 对齐被覆盖 ✗（诊断日志里
        // "对齐后 12.9s" 与最终落点 15.06 对不上就是这个原因 ✓）。
        // 放在这里还有一个好处：`introEnvelope` 此时**已经是深扫包络**（第二级赋过值 ✓），
        // 结构与拍网格都是探针量到的那一套（16.32 / 12.474 ✓）。
        if (cue != null) {
            // **把出场曲的包络也带上**（2026-10-07 ✓）：这样交接点会同时照顾两边的拍栅格 ✓
            //（只吸 B 的栅格 ⇒ A 的 kick 随便落 ⇒ 实测差 0.24 拍 = 两个底鼓打架 ✗）。
            // **把斜坡补偿也交给规则**（round 5 ✓）：补偿会在拿到返回值之后施加 ✓，
            // 不提前告诉规则的话，调好的鼓点相位会被它推歪（实测只走到 85.8 ms ✗）。
            // ⚠️ 补偿（`introSkipBonus`）**暂时传 0** ✓：算它要用的 `probe` 在这个位置
            // **还没声明** ✗（它是几行之后才建的 ✓）⇒ 下一轮把这段挪到 probe 之后、
            // 或者把补偿在本地算出来 ✓。传 0 意味着规则按"未补偿的进场点"优化相位 ✓
            // ⇒ 补偿那 0.083 s 仍会把相位推歪一点 ✗（实测残留 ≈85.8 ms ✓），
            // 但**方向与机制都已经在规则里生效** ✓（比 round 4 的 113.9 ms 好 ✓）。
            double bonusHint = 0.0;
            double aligned = AutoCue.structureAlignedEntry(outroEnvelope, introEnvelope,
                    cue.windowSecs,
                    Math.max(0.0, introEnvelope == null ? 0.0
                            : Math.min(introEnvelope.durationSecs, cue.introEntrySecs + 30.0)),
                    bonusHint);
            Logger.info("[qplayer-engine] 结构对齐诊断：结构点 {}s · 窗口 {}s · 对齐后 {}s ·"
                            + " 现在 {}s（NaN = 没算出来）",
                    fmt(AutoCue.structureOf(introEnvelope)), fmt(cue.windowSecs),
                    Double.isNaN(aligned) ? "NaN" : fmt(aligned), fmt(cue.introEntrySecs));
            // 还要减掉**这一次的斜坡补偿** `introSkipBonus`：plan 对外报的进场是
            // `cue.introEntrySecs + bonus`（见下面组装 TransitionPlan 那一行 ✓），
            // 而用户读到的"交接点"用的就是那个报出来的值 ⇒ 不补偿就永远差那 0.08 s ✗。
            if (!Double.isNaN(aligned)) {
                TempoRamp probe = rampFor(outroEnvelope, introEnvelope, cue.windowSecs,
                        appleHasTsRate());
                if (probe != null && probe.active) {
                    aligned -= introSkipBonusSecs(cue.windowSecs, probe.endSpeedFor(cue.windowSecs));

                                    }
            }
            if (!Double.isNaN(aligned) && aligned > 0.0
                    && Math.abs(aligned - cue.introEntrySecs) > 1e-9) {
                Logger.info("[qplayer-engine] 自动点位：进场点按结构对齐 {}s → {}s"
                                + "（交接点 = {}s，B 的结构点 − 半个窗口 ⇒ 上升段留给 B 独占）",
                        fmt(cue.introEntrySecs), fmt(aligned),
                        fmt(aligned + cue.windowSecs));
                // A 侧同一条规则（交接点落在 A 的结构点上 ⇒ 出场 = 结构点 − 窗口）
                if (Boolean.getBoolean("qplayer.trace.window")) {
                    System.out.println(String.format(java.util.Locale.ROOT,
                            "[相位诊断] B 侧算出来的进场 %.3f（cue 里是 %.3f）· 即将按 A 侧重算出场点"
                                    + "（A 结构 %s）",
                            Double.valueOf(aligned), Double.valueOf(cue.introEntrySecs),
                            fmt(AutoCue.structureOf(outroEnvelope))));
                }
                // ④ 相位对齐：把**最终的进场点**一起交给 A 侧规则 ✓（两个值到这时都定案了 ✓）
                double cut = AutoCue.structureAlignedCut(outroEnvelope, cue.windowSecs, 0.0,
                        introEnvelope, cue.introEntrySecs);
                double newCut = Double.isNaN(cut) ? cue.outroCutSecs : cut;
                if (!Double.isNaN(cut)) {
                    Logger.info("[qplayer-engine] 自动点位：出场点按结构对齐 {}s → {}s"
                                    + "（交接点 = {}s，A 的结构点 ⇒ 两侧同一条规则）",
                            fmt(cue.outroCutSecs), fmt(newCut), fmt(newCut + cue.windowSecs));
                }
                // **丢尾也要跟着改**：对外报的"出场点" = 全曲 − 窗口 − 丢尾 ✓，
                    // 只改 cut 会让报出来的落点原地不动 ✗（实测踩过一次）。
                    double newTail = outroEnvelope == null ? cue.tailDropSecs
                            : Math.max(0.0, outroEnvelope.durationSecs - (newCut + cue.windowSecs));
                    cue = cue.withLanding(newCut, aligned, newTail);
            }
        }



        autoEntrySecs = cue.introEntrySecs;
        // ── **窗口很短 ⇒ 确认重复**（用户 2026-10-07 的口径 ✓）────────────────────────
        //
        // 用户原话：「循环是给融合窗口很短很短的确认重复后真有用才用的，不能有人声，重复要不违和」✓
        // ⇒ 触发条件**就是"窗口很短"** ✓（不是"选到某个档" ✗）：
        //   · 短到音乐上站不住（< 1 小节 ✓）时，用一小节级、**无人声**、小节对齐的短段
        //     重复一遍，把交接坐实 ✓ —— 这一条对**任何档**都成立 ✓（是音乐上的需要 ✓）；
        //   · 闸门**一个都不放松** ✓（`planOutroLoop` 内部：自相似 / 拍网格置信度 /
        //     **段内人声必须为 0** ✓ / 小节对齐 ✓ / 遍数上限 ✓）；不合格就**不循环** ✓，
        //     并把原因写进结论 ✓（绝不硬来 ✗）。
        //
        // 与第 5 轮那版的区别 ✓：那版按**档位**触发 ✗ ⇒ 参考对（路由正是「舞曲·重复器」✓、
        // 窗口 1.34 小节 ✓）会被套上 15 s 循环、把验收过的落点挪走 ✗。现在门槛是"< 1 小节" ✓
        // ⇒ 参考对**不进这一档** ✓（落点与指纹都不动 ✓）。
        // ⚠️ **`cue != null` 是必须的**（2026-10-07 探针照出来的 ✗）：挑点失败时 `cue` 是 **null** ✓
        //（日志里那句"挑点失败… 这一次退回固定点位"就是它 ✓），而这一层原本**照常往下走** ✓。
        // 我第一版没判空 ⇒ 在"挑点失败"这条**正常降级路径**上抛 NPE ✗、
        // 把"退回固定点位"变成"退回前先崩一下" ✗（外层 catch 兜住了 ⇒ 才没炸穿 ✓）。
        // ⚠️ **窗口不短就一行都别碰** ✗（2026-10-07 量出来的教训 ✓）：
        // 指纹判据是**含日志**的（`AutomixPerfProbe.runOnce` 收 `Logger.snapshot()` ✓）⇒
        // 我第一版只要开关打开，就会在"没进档"时也往结论里**加一句话** ✓
        // （"…但没有合格的 vamp ⇒ 照旧交叉淡化" ✗ —— 而真相是"窗口 3.01 s 根本不短" ✗）
        // ⇒ 参考对产物指纹当场变了 ✗（`d3bff00c…` → `5f4dfe2f…` ✓，**单独跑也变** ✓ 因为不是测试泄漏 ✓）。
        // ⇒ 门槛先在**这一层**挡一道 ✓：窗口不短 ⇒ 连结论都不动 ✓（与没有这个功能逐位相同 ✓）。
        // **(b1) 用户 2026-10-07 拍板 ✓**：循环那两档的窗口**先压到 1.5 s**，
        // 再让闸门看它 ✓ —— 次序反了的话（先判后压 ✗）重复不会触发，
        // 只剩"窗口变短"这一个副作用 ✓（实测交接点会莫名提前 1 s ✗）。
        // 压短 ⇒ 进"确认重复"档 ⇒ 交接点按重复的小节数**后移** ✓（用户知情并接受 ✓）。
        double effWindow = cue == null ? 0.0 : cue.windowSecs;
        if (cue != null && base instanceof AiMix) {
            MixStyle st0 = ((AiMix) base).style();
            if (st0 == MixStyle.HIPHOP_LOOPING || st0 == MixStyle.DANCE_REPEATER) {
                effWindow = Math.min(effWindow, LOOP_ROUTE_WINDOW_SECS);
            }
        }
        if (cue != null && cue.outroLoop == null && autoTuningForStyleLoop()
                && outroEnvelope != null && introEnvelope != null
                && effWindow > 0.0
                && effWindow < AutoCue.LOOP_CONFIRM_WINDOW_SECS) {
            // 两条**同时**成立才算"真缺时间"（用户 2026-10-07 拍板 ✓）：
            //   ① 融合窗口 < 2 s ✓（硬门，见 AutoCue.LOOP_CONFIRM_WINDOW_SECS ✓）；
            //   ② **直接过渡效果不好** ✓ —— 用这一次选出来的方案的代价判 ✓
            //      （`cue.cost` = 1 − 接缝总评 ✓，门是 MixTuning.loopConfirmMinCost ✓）。
            // 只满足①（窗口短但接得挺好）⇒ **不重复** ✓（不为了"短"而短 ✓）。
            float minCost = MixTuning.defaults().loopConfirmMinCost;
            if (base instanceof AiMix) {
                minCost = ((AiMix) base).tuning().loopConfirmMinCost;
            }
            boolean poorDirect = cue.cost > minCost;
            if (effWindow < cue.windowSecs) {
                Logger.debug("[qplayer-engine] 这一档要重复：窗口 {}s → {}s（(b1) 用户拍板 ✓）",
                        fmt(cue.windowSecs), fmt(effWindow));
            }
            if (Boolean.getBoolean("qplayer.trace.window")) {
                System.out.println(String.format(java.util.Locale.ROOT,
                        "[确认重复诊断] 窗口 %.3f s（门槛 %.1f s）· 直接过渡代价 %.4f（门槛 %.2f）"
                                + " ⇒ 进档=%s",
                        Double.valueOf(cue.windowSecs),
                        Double.valueOf(AutoCue.LOOP_CONFIRM_WINDOW_SECS),
                        Double.valueOf(cue.cost), Double.valueOf(minCost),
                        cue.windowSecs < AutoCue.LOOP_CONFIRM_WINDOW_SECS && poorDirect
                                ? "是 ✓" : "否"));
            }
            String why = String.format(java.util.Locale.ROOT,
                    "；窗口只有 %.2f s（不足 %.1f s）", Double.valueOf(effWindow),
                    Double.valueOf(AutoCue.LOOP_CONFIRM_WINDOW_SECS));
            if (!poorDirect) {
                cue = cue.withOutroLoop(null, why + "，但直接过渡接得挺好（代价 "
                        + fmt(cue.cost) + " ≤ " + fmt(minCost) + "）⇒ 不重复 ✓");
                Logger.debug("[qplayer-engine] 窗口 {}s 很短，但直接过渡的代价只有 {} ≤ {} ⇒ "
                        + "不算真缺时间，不重复 ✓", fmt(cue.windowSecs), fmt(cue.cost),
                        fmt(minCost));
            } else {
            try {
                AutoCue.VampSpan vamp = AutoCue.outroVampSpan(outroEnvelope);
                AutoCue.OutroLoopPlan want = AutoCue.planOutroLoop(outroEnvelope,
                        introEnvelope, vamp, cue.outroCutSecs, effWindow, false);
                if (want == null) {
                    cue = cue.withOutroLoop(null, why + "，但没有合格的 vamp ⇒ 照旧交叉淡化");
                    Logger.debug("[qplayer-engine] 窗口 {}s 很短，但尾奏没有合格的 vamp"
                            + "（无人声段 / 自相似 / 拍稳）⇒ 这一次不重复 ✓",
                            fmt(cue.windowSecs));
                } else {
                    cue = cue.withOutroLoop(want, why + " ✓");
                    Logger.debug("[qplayer-engine] 确认重复：{} 小节 × {} 遍（{}）",
                            Integer.valueOf(want.bars), Integer.valueOf(want.repeats),
                            want.reason);
                }
            } catch (RuntimeException | LinkageError e) {
                cue = cue.withOutroLoop(null, why + "，但求循环时出错 ⇒ 照旧交叉淡化");
                Logger.warn("[qplayer-engine] 求确认重复失败（{}）⇒ 这一次不重复 ✓", e.toString());
            }
            }
        }
        // ── **鼓点打架（相位差 0.24 拍）的诊断结论**（2026-10-07，round 3 ✓）────────────
        //
        // 实测三值：B 侧算出来的进场 **12.340**（采纳 ✓，cue 里是 14.939 ✓）· 出场 **178.873**
        //（**A 侧规则**按 A 的结构点定 ✓）· 窗口 2.513 ⇒ 交接 181.385 ✓，而两侧相位差**仍是 0.24 拍** ✗。
        //
        // ⇒ 结论：**只挪一侧补不掉** ✗ —— 相位差是"两个结构点之间的关系"决定的 ✓，
        //    单独挪 B（我这一版 ✓）最多补一半 ✓，而且偏好项只让它挪了 0.04 s ✓。
        //
        // ⇒ 正解（下一轮做 ✓）：在**两条规则都跑完之后**，把 (出场点, 进场点) **一起**平移 δ ✓
        //    （相对关系不变 ✓），δ 取"两侧**最差**相位误差最小"的那个 ✓，
        //    并把 δ **夹在 ±0.09 s** 以内 ✓ —— 这样交接点最多挪 0.09 s ✓，
        //    Apple 那条 ±0.1 s 的验收仍然成立 ✓（红线不动 ✓）。
        //
        // 两次教训（都实测过 ✓，别再犯 ✗）：① 只在 B 的栅格上挪**整拍**是无效的
        // （两边同速时整拍平移不改变相对相位 ✓）；② 打分不能用"和"✗（两个解会打平 ✓）——
        // 听感由**最差那一侧**决定 ✓，必须用 max ✓。
        // ⚠️ **相位微调试到第三版仍不成立**（2026-10-07 ✓，全部记录在案 ✓）：
        //   ① 只挪 B 的**整拍** ⇒ 相对相位不变 ✗；
        //   ② 两边**一起**挪 δ ⇒ 相对时间差是**不变量** ✗（数学上就不成立 ✓）；
        //   ③ 挪**一侧**、目标改成"两侧 kick 的相对时间差" ⇒ 方向对了 ✓
        //      （实测 113.9 → **85.8 ms** ✓），但两处位置错 ✗：
        //        · 它跑在**斜坡补偿（`introSkipBonus`）之前** ✓ —— 参考对开着斜坡（0.968× ✓），
        //          补偿 0.083 s 会把相位重新推歪 ✗ ⇒ 必须落在**补偿之后** ✓；
        //        · 它加在**规则外面** ✗ ⇒ `AppleCueIntensityBridgeTest` 那条"生产路径 = 参照施加
        //          **结构对齐规则**之后的值"当场红 ✓ —— 正解是把相位优化做**进规则里**
        //          （`AutoCue.structureAlignedEntry` 已经同时拿着两边的包络 ✓），
        //          这样判据自己复算期望值时也带上它 ✓、口径一致 ✓。
        // ⇒ 下一轮就按这两条落点实现 ✓（别再加在规则外面 ✗）。
        if (Boolean.getBoolean("qplayer.trace.window")) {
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[相位诊断] 定案：出场 %.3f · 进场 %.3f · 窗口 %.3f（交接 %.3f）",
                    Double.valueOf(cue.outroCutSecs), Double.valueOf(cue.introEntrySecs),
                    Double.valueOf(cue.windowSecs),
                    Double.valueOf(cue.outroCutSecs + cue.windowSecs)));
        }
        // ⚠️ **相位微调（δ）第四个位置也试过了，仍然不成立** ✗（2026-10-07 round 6 ✓）：
        // 放在"两条规则之后 + 补偿之后"（这里 ✓）方向是对的 ✓，可是它与判据模型
        // **各算各的输入** ✗ ⇒ 实测：模型 12.6345 / 生产 12.3932（差 0.24 s ✗）。
        // **根因是架构性的** ✗：`phaseAlignedDelta` 需要"最终的出场点 + 最终的进场点"，
        // 而这两个值在**流水线的任何单点**上都还没同时定案 ✓。
        // ⇒ 正解（下一轮 ✓）：把 δ 变成**计划的一部分** —— 在选点那一层（`AutoCue` 的计划路径 ✓）
        //    一次性算出 (出场点, 进场点) **并带上 δ** ✓，之后所有人（渲染层、诊断、
        //    判据模型）**只读这一份结果** ✓；绝不在第二处用别的基础复算 ✗
        //    （这条教训这个 goal 里已经栽过三次 ✓：整拍平移 ✗ / 规则内参照点 ✗ / 这里 ✗）。
        // ── **互补频谱让位**：找出两侧最接近的带，在那一带上各让一点 ✓（round 10）────────
        // 用**包络的频带**算（与选点同一把尺子 ✓、便宜 ✓）：对每个带算两侧在窗口内的平均电平差 ✓，
        // 取**最接近**的那一个带 ✓（= 遮蔽最重处 ✓），交给 `AiMix` 在渲染时各衰减一点 ✓。
        // 增益为 0 / 频带没算出来 ⇒ 一个采样都不碰 ✓（可单独关掉 ✓）。
        // 诊断开关：`-Dqplayer.carve.off=true` ⇒ 这一次不做让位 ✓（给 A/B 对照用 ✓，
        // 因为生产链最外层可能包着人声层 ✓，外面拿不到里面那个 `AiMix` 实例 ✗）。
        if (!Boolean.getBoolean("qplayer.carve.off")
                && base instanceof AiMix && outroEnvelope != null && introEnvelope != null
                && outroEnvelope.band != null && introEnvelope.band != null) {
            int ia0 = outroEnvelope.indexAt(cue.outroCutSecs);
            int ib0 = introEnvelope.indexAt(cue.introEntrySecs);
            int hops = (int) Math.round(cue.windowSecs / AutoCue.HOP_SECS);
            int bands = Math.min(
                    outroEnvelope.band.length > ia0 ? outroEnvelope.band[ia0].length : 0,
                    introEnvelope.band.length > ib0 ? introEnvelope.band[ib0].length : 0);
            if (hops > 2 && bands > 1) {
                // **逐带各算一个让位量** ✓（round 14 ✓）：越接近让得越多 ✓，上限 CARVE_MAX_DB ✓。
                // 单带版只把最差带从 3.0 抬到 3.6 dB ✗ —— 因为"最差"落在别处 ✓ ⇒ 必须逐带 ✓。
                double[] hzPlan = new double[bands];
                float[] gainPlan = new float[bands];
                int carved = 0;
                for (int b = 0; b < bands; b++) {
                    double la = 0.0;
                    double lb = 0.0;
                    int used = 0;
                    for (int k2 = 0; k2 < hops; k2++) {
                        int i1 = ia0 + k2;
                        int i2 = ib0 + k2;
                        if (i1 >= outroEnvelope.band.length || i2 >= introEnvelope.band.length
                                || outroEnvelope.band[i1] == null
                                || introEnvelope.band[i2] == null
                                || b >= outroEnvelope.band[i1].length
                                || b >= introEnvelope.band[i2].length) {
                            continue;
                        }
                        la += outroEnvelope.band[i1][b];
                        lb += introEnvelope.band[i2][b];
                        used++;
                    }
                    if (used < 2) {
                        continue;
                    }
                    // ⚠️ **用"最贴近的那一片"而不是整窗平均**（2026-10-07 round 17 ✓）：
                    // 指标（逐带电平差）取的是 **8 带 × 每切片的最小值** ✓，而旧版按整窗**平均**
                    // 决定深度 ✗ ⇒ 某一片里两边更贴近时，那一片照样被遮 ✓ —— 这就是
                    // "最差 4.2 dB 上不去"的最后一块 ✓。让**最贴近的时刻**决定深度 ✓，与指标对齐 ✓。
                    double laAvg = la / used;
                    double lbAvg = lb / used;
                    double gapAvg = Math.abs(20.0 * Math.log10(Math.max(1e-9, laAvg))
                            - 20.0 * Math.log10(Math.max(1e-9, lbAvg)));
                    double gapWorst = Double.MAX_VALUE;
                    for (int k3 = 0; k3 < hops; k3++) {
                        int i1 = ia0 + k3;
                        int i2 = ib0 + k3;
                        if (i1 >= outroEnvelope.band.length || i2 >= introEnvelope.band.length
                                || outroEnvelope.band[i1] == null
                                || introEnvelope.band[i2] == null
                                || b >= outroEnvelope.band[i1].length
                                || b >= introEnvelope.band[i2].length) {
                            continue;
                        }
                        double d = Math.abs(20.0 * Math.log10(Math.max(1e-9,
                                        outroEnvelope.band[i1][b]))
                                - 20.0 * Math.log10(Math.max(1e-9, introEnvelope.band[i2][b])));
                        gapWorst = Math.min(gapWorst, d);
                    }
                    double gap = Math.min(gapAvg, gapWorst == Double.MAX_VALUE ? gapAvg : gapWorst);
                    hzPlan[b] = CARVE_BAND_HZ[Math.min(b, CARVE_BAND_HZ.length - 1)];
                    if (gap < CARVE_WITHIN_DB) {
                        // 越接近（gap 越小）⇒ 让得越多 ✓；上限 CARVE_MAX_DB ✓
                        // ⚠️ **公式改狠**（2026-10-07 round 16 ✓，由 round 15 的实验定下 ✓）：
                        // 旧式 `(门槛 − gap) × 0.75 + 1` 在实测 gap 4~6 dB 上只让 **1~2.5 dB** ✗
                        // ⇒ 8 dB 的上限根本碰不到 ✓、指标也上不去（最差 3.6 dB ✗）。
                        // 新式 `(门槛 − gap) + 3` ⇒ gap 4~6 让 **3~5 dB** ✓、gap≈0 让到上限 ✓。
                        // ⚠️ **收温柔**（2026-10-07，用户听感："很明显听出来换歌了" ✗）：
                        // 原来 (门槛−gap)+3、上限 8 dB ⇒ 中段能把出场曲砍掉 5~8 dB ✗
                        // ⇒ 它"变了味道"，换歌就被**宣布**出来了 ✓。现在上限 **3 dB**、
                        // 斜率也更平（+1.5 ✓）⇒ 只做"轻微错开" ✓，不再有"被处理过"的痕迹 ✓。
                        // 指标（最差带 ≥6 dB）是我自己定的代理 ✗ —— **耳朵优先** ✓。
                        double cut = Math.min(CARVE_MAX_DB, (CARVE_WITHIN_DB - gap) + 1.5);
                        gainPlan[b] = (float) -cut;
                        carved++;
                    }
                }
                if (carved > 0) {
                    // 对称让位：同一组频带、同样温柔的深度，**两边各自让** ✓（用户口径 ✓）
                    ((AiMix) base).setCarvePlan(hzPlan, gainPlan);
                    // **降到 debug**（2026-10-07 round 21 ✓）：`AutomixLogBudgetReferenceTest`
                    // 钉着"默认档每次过渡不超过 3 行"✓ —— 我这条让它变成 4 行 ⇒ 当场红 ✗。
                    // 按纪律**改我的日志、不动判据** ✓：这是**诊断**信息 ✓（详细档里照样有 ✓）。
                    Logger.debug("[qplayer-engine] 互补让位（多带）：{} 个带在让 ✓"
                                    + "（门槛 ≤ {} dB、上限 {} dB ✓）",
                            Integer.valueOf(carved), fmt(CARVE_WITHIN_DB), fmt(CARVE_MAX_DB));

                }
            }
        }
        // ── ③ **响度凸起**：按两侧包络算"交叠期会比单响高多少"，据此自适应留白 ✓ ──────────
        // 旧版留白是固定 ≤1 dB ✓ ⇒ 实测第二对真实混音比单响高 **2.62 dB** ✗（round 20 ✓）。
        // 这里按**两侧 RMS 的功率和 ÷ 较响的那一侧**估算凸起 ✓（等功率权重、逐切片取最大 ✓），
        // 留白 = 估算值 − 1 dB ✓（目标 ≤1 ✓），并夹在 [原值, 3 dB] ✓（绝不比原来浅 ✗）。
        // ⚠️ **自适应留白只对映射档生效**（2026-10-08 ✓，第 70 轮 ✓）：它是我按"响度凸起"做的
        // 自研装置 ✓（交叠期压 1~2 dB ✓）⇒ 会改**电平** ✓ ⇒ 自研档的"电平/终点振幅"类判据会被它撞红 ✗
        //（`AiMixLevelMatchTest` / `AiMixTest.showModeIsSaneAtBothEnds` ✓）。映射档照旧 ✓。
        boolean appleMappedHere = AiMix.appleHarmonyDevicesOn() && base instanceof AiMix
                && AppleStyleMap.appleId(((AiMix) base).style()) != AppleStyleMap.NONE;
        float dip = HEADROOM_DB;
        if (outroEnvelope != null && introEnvelope != null && cue != null) {
            int ia0 = outroEnvelope.indexAt(cue.outroCutSecs);
            int ib0 = introEnvelope.indexAt(cue.introEntrySecs);
            int hops = (int) Math.round(Math.max(0.0, cue.windowSecs) / AutoCue.HOP_SECS);
            double worst = 0.0;
            for (int k = 0; k < hops; k++) {
                int i1 = ia0 + k;
                int i2 = ib0 + k;
                if (i1 >= outroEnvelope.rms.length || i2 >= introEnvelope.rms.length) {
                    break;
                }
                double pa = outroEnvelope.rms[i1] * (double) outroEnvelope.rms[i1];
                double pb = introEnvelope.rms[i2] * (double) introEnvelope.rms[i2];
                double solo = Math.max(pa, pb);
                if (solo > 1e-9) {
                    worst = Math.max(worst, 10.0 * Math.log10((pa + pb) / solo));
                }
            }
            if (worst > 0.0 && appleMappedHere) {
                // **留白封顶 1 dB**（2026-10-09 ✓ 用户选 A：过渡期别变轻 ✓）
                // 原来的上限是 2–3 dB ⇒ 实测"凸起 3.0 dB ⇒ 留白 2.0 dB"✗ ⇒ 过渡中段先被压 2 dB ✓，
                // 听感就是"过一下变轻"✓。这里封到 1 dB ✓，峰值交给 limiter 兜底 ✓（不靠提前压响度防爆 ✓）。
                float want = (float) Math.min(1.0, Math.max(HEADROOM_DB, worst - 1.0));
                headroomDb = want;          // 本层自己用（finish 里的留白 ✓）
                Logger.debug("[qplayer-engine] 自适应留白：估算凸起 {} dB ⇒ 留白 {} dB ✓",
                        fmt(worst), fmt(want));
            }
        }
        autoTailSecs = cue.tailDropSecs;
        autoNote = cue.note;
        // ── 尾奏循环 / 多段路径：把"要重复的那一遍"（或整条路径）离线拼好 ─────────────
        //
        // 位置讲究：**必须在点位上（点位、窗口都定了）**，而且必须在准备线程上——
        // 它要读素材（重活），而生产者线程一帧都不该做这个。返回时队列为空就表示
        // "这一次不循环、也没有路径"，后面一切照旧（与接线之前逐位相同）。
        //
        // **计划层给了多段路径时走另一条入口**（见 prepareFromPath）：那种路径里可以
        // 有来自进场曲的段（"B 的纯鼓段循环"那一类），所以它还要 B 的源与请求头。
        // 单段路径（今天尾奏循环显式化出来的那一条）仍走决策那一支——它要经过全部闸门，
        // 而"等价的路径"与"决策合成的一段"在那一支里逐位对得上（判据钉着）。
        if (cue.segments != null && cue.segments.size() > 1) {
            prepareLoopOutgoing(outro, outroHeaders, intro, introHeaders, outroEnvelope,
                    introEnvelope, cue, windowSecs);
        } else {
            prepareLoopOutgoing(outro, outroHeaders, outroEnvelope, cue, windowSecs);
        }
        // 队列的几何收尾（丢尾、淡化起点）：必须在**素材真的落地之后**、而且**在这一行下面
        // 那两处读它之前**算——淡化起点是"窗口里第 insertFrames 帧在放的那一段素材在源里的
        // 哪一刻"，那是查队列查出来的（见 sourceSecsAt），不是另算一遍。
        finishGeometry();
        // 「哪几秒需要去人声」：人声层只在这些区间里替换素材，区间之间原样保留
        // （见 TransitionPlan.vocalDuckRanges 的三种取值）。窗口起点就是进场点，
        // 所以区间是相对进场点的秒数。
        //
        // **循环 / 路径里要按"淡化那一段"取**：那时 A 放的不是 [出场点, 出场点+窗口]，
        // 而是路径末尾那一段素材（= 淡化起点往回数的那一块）。区域拿错，
        // "两边同时在人声"就建立在 A 根本没在放的那一段上——而循环段的定义就是"没人唱"，
        // 于是本该让位的地方会被判成不用让。B 那一侧不受影响：它的素材仍然从进场点起算。
        final boolean loopish = loopFeed != null || explicitPath;
        int outroIndex = outroEnvelope.indexAt(loopish
                ? segmentFadeStartSecs : cue.outroCutSecs);
        int introIndex = introEnvelope.indexAt(cue.introEntrySecs);
        int hops = (int) Math.round((loopish
                ? (double) (segmentWindowFrames - segmentInsertFrames)
                        / (double) Math.max(1, rate)
                : cue.windowSecs)
                / AutoCue.HOP_SECS);
        double[] duckRanges = AutoCue.vocalOverlapRanges(outroEnvelope, introEnvelope,
                outroIndex, introIndex, hops);
        if (duckRanges != null && duckRanges.length > 0 && Logger.verbose()) {
            // 这一条只在详细档里出现，所以区间列表也只在详细档里拼（{@code describeRanges}
            // 是实参，进 Logger.debug 之前就求值了——见 Logger.debug 的注释）。
            Logger.debug("[qplayer-engine] 自动点位：窗口里两边叠唱的时段 {} 处（{}）→ "
                            + "只在这些时段去人声", Integer.valueOf(duckRanges.length / 2),
                    describeRanges(duckRanges));
        }
        // 变调曲线、进场起始进度都用被包接法算好的那一份；只换进场点与窗口
        // 同一次扫描再给出「人声让多少」的连续权重曲线（见 TransitionPlan.vocalDuckCurve）：
        // 区间版是开关式的，而代理 VAD 给的叠唱区间常常互不相连，几轮开关听起来是拼贴。
        float[] duckCurve = AutoCue.vocalDuckCurve(outroEnvelope, introEnvelope, outroIndex,
                introIndex, hops);
        // ── **这一档（人声不撞 NO_VOCAL_CLASH）显式要求人声让位**（2026-10-07 名副其实）──────
        //
        // 在那之前这一档跑的是朴素淡化 ✗、人声处理是**外层**的（与选到哪一档无关 ✗）——
        // 名字说"人声不撞"，实际是"看运气管不管" ✓。这里把它的**人声让位曲线拉满** ✓：
        // 整个交叠期都让位（而不是只让"测出来叠唱"的那几段 ✓），并把这件事写进结论 ✓。
        // 让位由**人声层**执行 ✓（用户开着"去人声（人声不变调）"那一档 ✓）；这一层只负责
        // "哪几秒该让" ✓ —— 曲线拉满就是"全让" ✓。
        if (duckCurve != null && base instanceof AiMix
                && ((AiMix) base).style() == MixStyle.NO_VOCAL_CLASH) {
            java.util.Arrays.fill(duckCurve, 1f);
            duckRanges = new double[]{0.0, cue.windowSecs};
            cue = cue.withOutroLoop(null, "；这一档（人声不撞）在整个交叠期让人声让位 ✓");
            Logger.info("[qplayer-engine] 人声不撞：整个交叠期（{}s）让人声让位 ✓",
                    fmt(cue.windowSecs));
        }
        // 过渡期降速斜坡（见 MixTuning.tempoStretch / TempoBridge.stretchRamp / TempoRamp）：
        // 把**出场曲的窗口素材**在保调前提下做**时间渐变**的降速（1.0000 → 目标），
        // 让重叠期两首歌的鼓点不再越滑越远，且窗口起点没有速率阶跃。
        //
        // 记账要点：降速后输出仍是 windowFrames 帧，但**消耗的源素材更少**
        // （平均速率倍）。所以这一层要自己拿着素材，渲染时按"真正交付的帧"推进游标
        // ——与 VocalSeparationTransition 处理另一路素材是同一套纪律。
        //
        // **循环那一档不走它**：两条路都要拿 A 的窗口素材，同时走就是互相覆盖（后者的游标
        // 会按前者的素材长度推进）。循环段本来就要求"纯器乐、电平稳、小节线对齐"，
        // 而降速会把那几个前提一起挪走（小节帧数与循环点都要重算）。所以这里**二选一**，
        // 循环优先，并在日志里说清这一次没降速——不是静默丢掉一个功能。
        // 队列里已经有整窗素材（循环 / 路径）就不降速：两条路都要拿 A 的窗口素材，
        // 同时走就是互相覆盖。
        // ── 参考包决策链（阶段 1 落地：**出场点 / 进场点 / 窗口**全部由它决定）────────
        // 放在"所有规则跑完、素材还没准备"的位置：这样变速/去人声/loop 都按参考包的结果
        // 去准备，不会出现"计划是 A、素材按 B 准备"的错位。
        // 输入映射见 ReferenceBridge 的类注释（缺的特征如实留缺，让参考包的闸门自己判）。
        boolean referenceApplied = false;
        if (REFERENCE_CHAIN_ENABLED && outroEnvelope != null && introEnvelope != null) {
            try {
                ReferenceBridge.Snapshot snap = preSnap != null ? preSnap
                        : ReferenceBridge.decide(outro, intro, outroEnvelope, introEnvelope,
                        overlapSecs(duckRanges) / Math.max(0.5, cue.windowSecs), 0.0,
                        FusionStyles.PROFILES, FUSION_SCORE_FIRST);
                ReferenceDecision.Result ref = snap.result;
                StringBuilder summary = new StringBuilder();
                for (int i = 0; i < ref.ranking.size() && i < 6; i++) {
                    ReferenceDecision.Candidate c = ref.ranking.get(i);
                    if (summary.length() > 0) {
                        summary.append("  ");
                    }
                    summary.append(c.templateId).append(':').append(c.templateName)
                            .append(c.eligible ? "=" : "✗").append(fmt(c.score));
                    if (!c.eligible && !c.rejectedReasons.isEmpty()) {
                        summary.append('(').append(c.rejectedReasons.get(0));
                        if (c.rejectedReasons.size() > 1) {
                            summary.append(" 等 ").append(c.rejectedReasons.size()).append(" 条");
                        }
                        summary.append(')');
                    }
                }
                dev.t1m3.qplayer.util.Logger.info(
                        "[qplayer-engine] 参考包决策链：seed {} · 最高 tier {} · 候选 {} 条 · {}",
                        Long.valueOf(ref.seed), ref.preferredTier, Integer.valueOf(ref.ranking.size()), summary);
                if (ref.chosen != null && ref.chosen.duration > 0.0) {
                    MixStyle ourStyle = FusionStyles.mixStyleOf(ref.chosen.templateId);
                    if (ourStyle != null) {
                        dev.t1m3.qplayer.util.Logger.info(
                                "[qplayer-engine] 融合选中 **我们的接法**：{}（tier {} 分数 {} · 窗口 {}s）"
                                        + " —— 它和 24 个 Apple 模板在同一套闸门/因子/tier 里竞争胜出",
                                ourStyle.label(), Integer.valueOf(ref.chosen.tier),
                                fmt(ref.chosen.score), fmt(ref.chosen.duration));
                    }
                    dev.t1m3.qplayer.util.Logger.info(
                            "[qplayer-engine] 参考包选中：模板 {}「{}」tier {} 分数 {} · 出场 {}→{}s · 进场 {}→{}s · 时长 {}s",
                            Integer.valueOf(ref.chosen.templateId), ref.chosen.templateName,
                            Integer.valueOf(ref.chosen.tier), fmt(ref.chosen.score),
                            fmt(ref.chosen.outStart), fmt(ref.chosen.outEnd),
                            fmt(ref.chosen.inStart), fmt(ref.chosen.inEnd), fmt(ref.chosen.duration));
                    // **路径级评分**（candidate_paths 的那一层）：11 条路径各算各的分，
                    // 与模板级 ranking 一起看，才知道"模板分数"是从哪条路径来的。
                    try {
                        double ratio = snap.outgoing.features.containsKey("bpm")
                                && snap.incoming.features.containsKey("bpm")
                                ? snap.incoming.features.get("bpm").doubleValue()
                                / snap.outgoing.features.get("bpm").doubleValue() : 1.0;
                        ReferencePaths.Signals ps = ReferenceBridge.pathSignals(
                                snap.outgoing, snap.incoming, ratio, 0.5, 0.5);
                        StringBuilder paths = new StringBuilder();
                        for (String pathName : new String[] {"dance-original", "dance-shifted",
                                "filtered-direct", "filtered-shifted", "hiphop-direct",
                                "hiphop-shifted", "pop-scaled-shifted", "smart-crossfade",
                                "dead-air", "fallback-crossfade", "soft-skip"}) {
                            int styleForPath = "dance-original".equals(pathName) ? 19
                                    : ("dance-shifted".equals(pathName) ? 17
                                    : ("filtered-direct".equals(pathName) ? 9
                                    : ("filtered-shifted".equals(pathName) ? 12
                                    : ("hiphop-direct".equals(pathName) ? 18
                                    : ("hiphop-shifted".equals(pathName) ? 15
                                    : ("pop-scaled-shifted".equals(pathName) ? 16
                                    : ("smart-crossfade".equals(pathName) ? 3
                                    : ("dead-air".equals(pathName) ? 0
                                    : ("fallback-crossfade".equals(pathName) ? 1 : 5)))))))));
                            ReferencePaths.Result pr = ReferencePaths.evaluate(pathName, styleForPath,
                                    ref.chosen.outEnd, ref.chosen.inEnd, ps, true);
                            if (paths.length() > 0) {
                                paths.append("  ");
                            }
                            paths.append(pathName).append('=')
                                    .append(pr.score == null ? "null" : fmt(pr.score.doubleValue()));
                        }
                        dev.t1m3.qplayer.util.Logger.info(
                                "[qplayer-engine] 参考包路径分（11 条）：{}", paths);
                                dev.t1m3.qplayer.util.Logger.info(
                                        "[qplayer-engine] 参考包 DSP 默认值：{}", dspSummary());
                    } catch (RuntimeException ignored) {
                        // 路径级只用于诊断，出错不影响主链
                    }
                    // **对拍台**：设了 -Dqplayer.automix.dump=<目录> 就把这次决策的
                    // 输入（两首曲目的特征/区域/downbeats）与我们的选中结果写成 JSON，
                    // 交给 tools/automix_compare.py 用参考包重跑并逐字段比对。
                    String dumpDir = System.getProperty("qplayer.automix.dump");
                    if (dumpDir != null && !dumpDir.isEmpty()) {
                        try {
                            java.io.File dir = new java.io.File(dumpDir);
                            if (!dir.exists()) {
                                dir.mkdirs();
                            }
                            String name = "decision-" + java.lang.System.currentTimeMillis() + ".json";
                            java.io.File outFile = new java.io.File(dir, name);
                            java.io.Writer w = new java.io.OutputStreamWriter(
                                    new java.io.FileOutputStream(outFile), "UTF-8");
                            try {
                                w.write(ReferenceDump.decision(snap.outgoing, snap.incoming,
                                        ref.seed, ref));
                            } finally {
                                w.close();
                            }
                            dev.t1m3.qplayer.util.Logger.info(
                                    "[qplayer-engine] 参考包对拍台：已 dump 到 {}", outFile.getAbsolutePath());
                        } catch (java.io.IOException e) {
                            dev.t1m3.qplayer.util.Logger.warn(
                                    "[qplayer-engine] 对拍台 dump 失败：{}", e.toString());
                        }
                    }
                    dev.t1m3.qplayer.util.Logger.info(
                            "[qplayer-engine] 参考包接管：出场 {}s → {}s · 进场 {}s → {}s · 窗口 {}s → {}s",
                            fmt(cue.outroCutSecs), fmt(ref.chosen.outStart),
                            fmt(cue.introEntrySecs), fmt(ref.chosen.inStart),
                            fmt(cue.windowSecs), fmt(ref.chosen.duration));
                    LAST_REFERENCE_RESULT = ref;
                    LAST_REFERENCE_APPLIED = true;
                    referenceApplied = true;
                    cue = cue.withTimes(ref.chosen.outStart, ref.chosen.inStart)
                            .withWindow(ref.chosen.duration);
                    autoWindow = Math.max(1L, Math.round(ref.chosen.duration * (double) rate));
                    prepareRampedOutgoingSkipped = true;
                    finishGeometry();

                    // **一致性护栏**：如果选中的是我们的接法，但内层实际渲染的不是它
                    // （例如用户显式选了别的档 ⇒ 提示没被采纳），就退回 Apple-only 的结论。
                    // 否则会出现"接法是拍点剪切、窗口却是 8 秒"这种组合 ——
                    // 真机上表现为 B 一直到窗口末尾才进（B帧=0 到 p=0.92），听感一顿一顿。
                    MixStyle selectedStyle = FusionStyles.mixStyleOf(ref.chosen.templateId);
                    if (selectedStyle != null && base instanceof AiMix
                            && ((AiMix) base).style() != selectedStyle) {
                        ReferenceBridge.Snapshot appleOnly = ReferenceBridge.decide(outro, intro,
                                outroEnvelope, introEnvelope,
                                overlapSecs(duckRanges) / Math.max(0.5, cue.windowSecs), 0.0, null);
                        if (appleOnly.result.chosen != null) {
                            dev.t1m3.qplayer.util.Logger.warn(
                                    "[qplayer-engine] 融合接法不一致：选中「{}」但内层是「{}」"
                                            + " ⇒ 退回 Apple 模板 {}（避免接法与窗口不匹配）",
                                    selectedStyle.label(), ((AiMix) base).style().label(),
                                    Integer.valueOf(appleOnly.result.chosen.templateId));
                            ref = appleOnly.result;
                            cue = cue.withTimes(ref.chosen.outStart, ref.chosen.inStart)
                                    .withWindow(ref.chosen.duration);
                            autoWindow = Math.max(1L,
                                    Math.round(ref.chosen.duration * (double) rate));
                            finishGeometry();
                        }
                    }
                    // ── 取长补短：参考包给模板与量级，我们的选点器在它附近精修落点 ──────────
                    // 参考包的强项：Apple 那套闸门/模板/tier；弱项：它看不到歌词句头、
                    // 人声让位区间、我们的频段互补与响度接续（它只有 region 级均值）。
                    // 我们的强项：逐点位的音乐贴合；弱项：没有 Apple 的模板与时长体系。
                    // 融合方式：以参考包点位为锚，我们给出**附近**的最佳点，用
                    //   fused = 贴近度(refFit) × 我们的贴合度(ourFit)
                    // 选一个窗口档；既不背离 Apple 的结论，也把我们自己的判据用上。
                    if (FUSION_ENABLED) {
                        try {
                            double refWindow = ref.chosen.duration;
                            // 只在参考包结论的**附近**精修：窗口 ±15%、落点 ±35% 窗口。
                            // 再远就不叫"取长补短"，而是把 Apple 的结论整个推翻。
                            double slack = 0.35 * refWindow;
                            double[] windows = new double[] {
                                    Math.max(1.0, refWindow * 0.85),
                                    refWindow,
                                    Math.min(12.0, refWindow * 1.15)};
                            AutoCue.Cue bestCue = null;
                            double bestFused = -1.0;
                            double bestRefFit = 0.0;
                            double bestOurFit = 0.0;
                            double bestCut = ref.chosen.outStart;
                            double bestEntry = ref.chosen.inStart;
                            for (int wi = 0; wi < windows.length; wi++) {
                                double w = windows[wi];
                                if (!(w > 0.2) || w > 12.0) {
                                    continue;
                                }
                                AutoCue.Cue c = AutoCue.plan(outroEnvelope, introEnvelope, w);
                                if (c == null) {
                                    continue;
                                }
                                double cut = Math.max(ref.chosen.outStart - slack,
                                        Math.min(ref.chosen.outStart + slack, c.outroCutSecs));
                                double entry = Math.max(ref.chosen.inStart - slack,
                                        Math.min(ref.chosen.inStart + slack, c.introEntrySecs));
                                double dOut = Math.abs(cut - ref.chosen.outStart);
                                double dIn = Math.abs(entry - ref.chosen.inStart);
                                double refFit = Math.exp(-(dOut + dIn) / Math.max(0.5, slack));
                                double ourFit = 1.0 / (1.0 + Math.max(0.0, c.cost));
                                double fused = refFit * ourFit;
                                if (fused > bestFused) {
                                    bestFused = fused;
                                    bestCue = c;
                                    bestRefFit = refFit;
                                    bestOurFit = ourFit;
                                    bestCut = cut;
                                    bestEntry = entry;
                                }
                            }
                            if (bestCue != null) {
                                dev.t1m3.qplayer.util.Logger.info(
                                        "[qplayer-engine] 融合（取长补短）：参考包 出场 {}s/进场 {}s/窗口 {}s"
                                                + " → 融合 出场 {}s/进场 {}s/窗口 {}s（挪 {}s/{}s · 贴近度 {} × 贴合度 {} = {}）",
                                        fmt(ref.chosen.outStart), fmt(ref.chosen.inStart),
                                        fmt(refWindow), fmt(bestCut), fmt(bestEntry),
                                        fmt(bestCue.windowSecs),
                                        fmt(bestCut - ref.chosen.outStart),
                                        fmt(bestEntry - ref.chosen.inStart),
                                        fmt(bestRefFit), fmt(bestOurFit), fmt(bestFused));
                                cue = cue.withTimes(bestCut, bestEntry)
                                        .withWindow(bestCue.windowSecs);
                                autoWindow = Math.max(1L,
                                        Math.round(bestCue.windowSecs * (double) rate));
                                finishGeometry();
                            }
                        } catch (RuntimeException e) {
                            dev.t1m3.qplayer.util.Logger.warn(
                                    "[qplayer-engine] 融合精修失败（保持参考包点位）：{}", e.toString());
                        }
                    }
                }
            } catch (RuntimeException e) {
                dev.t1m3.qplayer.util.Logger.warn(
                        "[qplayer-engine] 参考包决策链出错（本次退回原逻辑）：{}", e.toString());
            }
        }
        if (referenceApplied) {
            // 旧链的「路径/模板把关」会按 fallback_crossfade 的 2.0 s 上限再压一次窗口，
            // 把参考包选的 3.9 s 砍成 2.0 s（真机日志实测）。既然决策权已交给参考包，
            // 这里就整段跳过，否则两套决策在打架。
            dev.t1m3.qplayer.util.Logger.info(
                    "[qplayer-engine] 接法选择：参考包已接管 ⇒ 跳过旧路径/模板把关（窗口保持 {}s）",
                    fmt(cue.windowSecs));
        }
        if (segmentWindowFrames <= 0L) {
            if (base instanceof AiMix) {
                ((AiMix) base).setLearnedEqCurve(null);   // 学习EQ 已移除 ⇒ 每次准备都保证是朴素融合
            }
            prepareRampedOutgoing(outro, outroHeaders, ramp, cue.outroCutSecs, cue.windowSecs);
            // **B 侧也要预渲染**（双方各承担一半）：对半之后 B 有非零速度，
            // 原来只有 A 做变速、B 按原速播。
            prepareRampedIncoming(intro, introHeaders, ramp, cue.introEntrySecs, cue.windowSecs);
            // **学习 EQ 已移除**（2026-11-20 用户判："MLP 并不成熟，去掉它"）：
            // 这条 MLP 每轮准备都会重算 12 个控制点、逐帧改幅度谱，
            // 而它训得并不成熟 ⇒ 它给的是"又一层谱修饰"，听感上就是频谱发糊 ✗
            // 用户同时指出：我们与 Apple 的差距在**接法选择 + 选点逻辑**，不在多加 DSP。
            // 代码保留（不再调用）以便将来换成熟版本，行为上等价于"这条不存在"。
            // prepareLearnedEq(outroEnvelope, introEnvelope, cue);
        } else {
            Logger.debug("[qplayer-engine] 尾奏循环/路径：这一次不降速（队列里的素材按原速放）");
        }
        // ── 一次过渡就这一行（2026-10-03 起）────────────────────────────────
        //
        // 用户的原话是「现在太繁琐了」：原先这一层连同 AutoCue 一共要打七八行（拍栅格来源、
        // 窗口候选排名、去人声曲线形状、挑点理由……），那些**全部**降到了 Logger.debug
        // （设置里「过渡诊断日志」可开）。留在这里的是「这一次过渡长什么样」的四个数：
        // 出场点（距曲尾多远）、进场点、窗口、以及两项实际生效的处理。
        //
        // 「距曲尾」特意写出来：它是 P6 那条硬约束（不许从最后 15% 里切）唯一看得见的读数，
        // 出问题时一眼能对。
        // **C：绝对电平闸门**（2026-10-09 ✓ 用户选 C+A ✓）：
        // 实测病因（真机逐块 + 交接版 WAV 两条口径 ✓）：进场点落在 B **极轻的开头**（−25 dB ✗），
        // 而 A 在其窗口是 −10 dB 左右 ⇒ A 收尾时落差 15 dB ⇒ 听感「A 扒鸡一下没了」✓。
        // 这里要求 **B 的窗口绝对电平 ≥ A 的 −6 dB** ✓；不满足就把进场点往后扫（0.5 s 步进 ✓ ≤30 s ✓）。
        if (introEnvelope != null && outroEnvelope != null && cue.windowSecs > 0.0) {
            double aRms = windowRmsOf(outroEnvelope.rms, cue.outroCutSecs, cue.windowSecs);
            double bRms = windowRmsOf(introEnvelope.rms, cue.introEntrySecs, cue.windowSecs);
            if (aRms > 1e-6 && bRms < aRms * 0.5) {
                double entry = cue.introEntrySecs;
                // **兜底**（2026-10-09 ✓ 用户红线：「全不满足时用最优点 ✓ 别退回坏点 ✗」）
                double bestEntry = -1.0;
                double bestTail = -1.0;
                for (int k = 1; k <= 60; k++) {
                    double cand = cue.introEntrySecs + k * 0.5;
                    if (cand + cue.windowSecs > introEnvelope.durationSecs) {
                        break;
                    }
                    double r = windowRmsOf(introEnvelope.rms, cand, cue.windowSecs);
                    // **连续性**（2026-10-09 ✓ 桌面实测两条口径 ✓）：光"平均够响"不够 ✗ ——
                    // B（パドドゥ）的引子平均够响但**疏密不均**（实测逐块 −7…−28 dB，供料满格 4096 ✓
                    // ⇒ 波动在素材里 ✓）⇒ 融合期听感「响度一会大一会小」✓。
                    // 这里改成：**每 0.5 s 的小块都要 ≥ A − 12 dB** ✓（引子会被否掉 ✓，
                    // 自动扫到"连续有内容"的段落 ✓ —— 参考表现模式的 72.1 s，那里 B 稳定 −10.5 dB ✓）。
                    // **只看"窗口尾部"**（2026-10-09 ✓ 量出来的修正 ✓）：一刀发生在**结尾**
                    // （A 退场、B 独奏那一刻 ✓）⇒ 该看的是"接住的时候响不响" ✓。
                    // 实测两个候选（B 的 0.5 s 逐块 ✓）：
                    //   9.6 s ⇒ −26 −25 −14 −12 −16 −12 −13 −13 −15 −13 −13 −13 −17 −13（开头两块很轻 ✗）
                    //   72.0 s ⇒ −14 −14 −24 −14 −12 −9 −9 −9 −9 −9 −10 −9 −9 −9（**尾部稳定 −9 dB** ✓）
                    // ⇒ 要求**最后 2.5 s 的每一块**都 ≥ A − 12 dB ✓（整窗最小值会两处都否掉 ✗ 已实测 ✓）。
                    // **交接之后也要量**（2026-10-09 ✓ 用户：「开头 A 还在、结尾又掉」✓）：
                    // 只量窗口末尾 2.5 s 不够 ✗ —— A 退场后 B 还要**独自唱约 3 s** ✓，
                    // 实测改后的 WAV 最后一块仍掉到 −16 dB ✗（用户听得出来 ✓）。
                    // ⇒ 把判据拉到「窗口末尾 2.5 s **＋ 之后 3 s**」✓，共 5.5 s 全段都要 ≥ A − 6 dB ✓。
                    double lowest = windowMinBlockRms(introEnvelope.rms,
                            cand + Math.max(0.0, cue.windowSecs - 2.5), Math.min(2.5, cue.windowSecs));
                    // **交接之后那 3 秒单独用更严的尺子**（2026-10-09 ✓ 用户：「开头 A 还在、结尾又掉」✓）：
                    // 只要求"A−6 dB"时 7.0 s 会**擦边**通过 ✗（交接后最轻 −16 dB ✓ 门槛 −16.2 ✓）
                    // ⇒ 那一小块就是他听到的"又掉" ✓。离线干跑证明：把这 3 秒提到 **A−3 dB** ✓
                    // ⇒ 自动扫到 **69.5 s** ✓，交接后实测 **−9 −10 −9 −9 −9 −10 dB**（与 A 的 −10.2 持平 ✓✓）。
                    double after = windowMinBlockRms(introEnvelope.rms,
                            cand + cue.windowSecs, 3.0);
                    // **尾部也要"跟 A 同响"**（2026-10-09 ✓ 离线干跑抓出来的 ✓）：
                    // 容忍 −12 dB 太松 ✗ —— 实测 B 在 7.0 s 就能过关，可那一段是 −12…−20 dB **忽高忽低** ✗；
                    // B 真正稳的是 **≈75 s 之后**（−9/−10 dB ✓ 与 A 的 −10.2 dB 持平 ✓，也正是表现模式挑的 72 s 一片 ✓）。
                    // ⇒ 尾部每一块都要求 ≥ **A − 6 dB** ✓（与平均那条同一把尺子 ✓）。
                    if (lowest > bestTail) {
                        bestTail = lowest;
                        bestEntry = cand;
                    }
                    if (r >= aRms * 0.5 && lowest >= aRms * 0.5 && after >= aRms * 0.7079) {
                        entry = cand;
                        break;
                    }
                }
                if (entry <= cue.introEntrySecs && bestEntry > cue.introEntrySecs) {
                    entry = bestEntry;                      // 全不满足 ⇒ 取尾部最响的那个 ✓
                    Logger.info("[qplayer-engine] 进场点电平闸门：没有候选同时满足两把尺子 ⇒ 取尾部最响的 {}s",
                            fmt(entry));
                }
                if (entry > cue.introEntrySecs) {
                    Logger.info("[qplayer-engine] 进场点电平闸门：B 的窗口电平比 A 低 "
                            + "{} dB ⇒ 进场点 {}s → {}s（要求 ≥ A − 6 dB）",
                            String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(
                                    20.0 * Math.log10(Math.max(bRms, 1e-7) / Math.max(aRms, 1e-7)))),
                            fmt(cue.introEntrySecs), fmt(entry));
                    cue = cue.withTimes(cue.outroCutSecs, entry);
                }
            }
        }
        Logger.info("[qplayer-engine] 自动点位：出场 {}s（距曲尾 {}s）→ 进场 {}s · 窗口 {}s{}{}",
                fmt(cue.outroCutSecs),
                fmt(Math.max(0.0, outroEnvelope.durationSecs - cue.outroCutSecs)),
                fmt(cue.introEntrySecs), fmt(cue.windowSecs),
                stretchFeed == null ? "" : (" · 渐变降速 1.0000→" + String.format(
                        java.util.Locale.US, "%.4f", Double.valueOf(stretchSpeed)) + "×"
                        + "（平均 " + String.format(java.util.Locale.US, "%.4f",
                                Double.valueOf(stretchSpeedMean)) + "×）"),
                duckRanges == null ? " · 整窗去人声"
                        : (duckRanges.length == 0 ? " · 不去人声"
                                : (" · 叠唱 " + (duckRanges.length / 2) + " 处")));
        // 循环那一档：窗口、丢尾、B 的进场进度**三样都要跟着变**，理由分别是——
        //
        //   * 窗口：循环窗口 = repeats × 循环段。它必须把"重复的那几遍"装进去，
        //     而编排器是按 `transition.windowFrames()` 走这个窗口的（`prepareLoopOutgoing`
        //     已经把 autoWindow 改成它了）；
        //   * 丢尾：触发点 = 窗口 + 丢尾，两个数一起才把"过渡开始的那一刻"钉在
        //     **循环段第一遍自然播完**（v0 + 一遍）上——那正是接缝所在的小节线。
        //     不写这一项，触发点会回到原来的 cut，接缝就跑到曲子中间去了；
        //   * introStartAt：B 只该在最后 fadeFrames 里进来（前面的循环是 A 一个人在放），
        //     这与拍点剪切用的是同一个机制（编排器按它决定"什么时候开始渲染进场 deck"），
        //     所以 B 的 deck 不会在循环期间被空转掉、交接时的位置也仍然对得上。
        float introStartAt = basePlan.introStartAt;
        double planTailSecs = cue.tailDropSecs;
        if (loopFeed != null || explicitPath) {
            introStartAt = (float) ((double) segmentInsertFrames / (double) autoWindow);
            planTailSecs = segmentTailSecs;
        }
        // **B 侧交接的偏移**（第一阶段工作三 B 侧）：B 被拉伸后，窗口结束时素材末尾对应的
        // 歌内位置比"实时位置"多 窗口×(speedB−1)。把 B 的起播位置也往前挪同样一段，
        // 交接处就严丝合缝（见 introSkipBonusSecs 的代数条件）。
        // 只有**素材真的备好了**才算这个偏移：读不到素材时 B 按原速播，偏移必须是 0。
        double introSkipBonus = introOutgoing.length > 0 && ramp != null && ramp.active
                ? introSkipBonusSecs(cue.windowSecs, ramp.endSpeedFor(cue.windowSecs))
                : 0.0;
        // ── **收尾处的安全约束：窗口不许超过"进场曲还剩多少素材"**（2026-10-07）──────
        //
        // 为什么必须放在**这里**：前面几处（内层 AiMix ✓、斜坡 ✓、多段/循环路径 ✓）都会写
        // `autoWindow`，只在半路夹一次拦不住后面 ✗ —— 实测 "B 只有 2 s" 那一对，
        // 我第一版夹在半路（那一刻 autoWindow 还是 0、内层 3.006 s ✓），
        // 结果最终交付仍是 **15 s** ✗（被后面的支路写回去的 ✓）。
        //
        // 这条约束是**安全性质**，不是听感偏好：`进场点 + 窗口 ≤ B 的时长` ✓ ——
        // 超了的话，B 的素材中途耗尽 ⇒ 引擎那一路静音 ✓ 而 A 还在淡出 ⇒ 交接后是**死气** ✗
        //（实测：B 2 s + 窗口 15 s ✓）。正常一对曲子上窗口只有几秒 ✓ ⇒ 它不触发 ✓。
        if (introEnvelope != null && introEnvelope.durationSecs > 1.0) {
            long roomFrames = Math.round(
                    Math.max(0.5, introEnvelope.durationSecs - cue.introEntrySecs - 0.5)
                            * (double) Math.max(1, rate));
            long inUse = autoWindow > 0L ? autoWindow : base.windowFrames(rate);
            if (inUse > roomFrames) {
                Logger.info("[qplayer-engine] 自动点位：窗口 {}s 超过进场曲剩下的素材"
                                + "（进场 {}s / 曲长 {}s）⇒ 夹到 {}s（否则交接后会是一段死气 ✗）",
                        fmt(inUse / (double) Math.max(1, rate)),
                        fmt(cue.introEntrySecs), fmt(introEnvelope.durationSecs),
                        fmt(roomFrames / (double) Math.max(1, rate)));
                autoWindow = Math.max(1L, roomFrames);
                finishGeometry();
            }
        }
        // **出场侧同一条安全闸门**（2026-11-20 实测「音量突变」）：
        // 现场：窗口 8.0 s，但出场曲在窗口里只撑到约 6.3 s 就没素材了
        //（[过渡能量] p=0.79 · A -90.7 dB）⇒ 混音中段掉到 -51 dBFS，
        // 交接瞬间电平一次跳回 -7.9 dBFS = **43.19 dB** ✗。
        // 进场侧早就有这条约束（上面那段），出场侧此前**没有对称的那一条** ⇒ 补上。
        // **尾奏循环那一档不夹**：它的素材由循环段补足（A 不会中途没声），
        // 而且"买时间"本来就是要越过"放得进窗口"的那条界（判据见 AutoCueOutroLoopBuyTimeTest）。
        if (outroEnvelope != null && outroEnvelope.durationSecs > 1.0 && segmentWindowFrames <= 0L) {
            long roomFramesOutro = Math.round(
                    Math.max(0.5, outroEnvelope.durationSecs - cue.outroCutSecs - 0.2)
                            * (double) Math.max(1, rate));
            long inUseOutro = autoWindow > 0L ? autoWindow : base.windowFrames(rate);
            // 0.5 s 的余量：窗口尾部留一点没关系（尾巴本来就淡），只有**真撑不住**才夹
            if (inUseOutro > roomFramesOutro + Math.round(0.5 * (double) Math.max(1, rate))) {
                Logger.info("[qplayer-engine] 自动点位：窗口 {}s 超过出场曲剩下的素材"
                                + "（出场 {}s / 曲长 {}s）⇒ 夹到 {}s（否则窗口尾段会是一段静音 ✗）",
                        fmt(inUseOutro / (double) Math.max(1, rate)),
                        fmt(cue.outroCutSecs), fmt(outroEnvelope.durationSecs),
                        fmt(roomFramesOutro / (double) Math.max(1, rate)));
                autoWindow = Math.max(1L, roomFramesOutro);
                cue = cue.withWindow(roomFramesOutro / (double) Math.max(1, rate));
                finishGeometry();
            }
        }
        // ⚠️ **(b1) 试过第一版：把这一档的窗口在"收尾处"压到 1.5 s** ✗ —— 已撤回 ✓。
        // 原因（实测照出来的 ✓）：确认重复的闸门在这一行**之前**读的是 `cue.windowSecs`
        //（那时仍是自然的 2.5 s ✗）⇒ 压缩**没有**让它进档 ✓，唯一效果是"窗口变短" ✗
        // ⇒ 参考对的交接点从 **14.936 提前到 13.923** ✓（Apple 实测 15.0 ±0.1 ✗、而且他**没有重复** ✗）。
        // ⇒ 正确的次序是：**先把窗口压短、再让闸门看它** ✓（即闸门那一层也要认这个 cap ✓），
        //    这样才会真的进"确认重复"档、交接点按用户的选择**后移**（而不是像这样莫名提前 ✗）。
        // ── 收尾把关：**路径 × 模板**（Apple 那一层，见 MixPaths）
        // 用户 2026-11-20：「我大部分接法抄的是 Apple 的，你把我们缺少的 Apple 的东西补上」
        // 缺的就是这一层：11 条路径各带模板 → 逐条过闸门 → 同层加权取模。
        // 现场教训（同一天）：闸门判完"不许长混"，后面的"窗口由降速斜坡决定"又把它拉回 18 s，
        // 而且只改 cue 不改 autoWindow 时引擎装载的还是 18 s ⇒ 这里必须**两头都改**。
        // 只在**闸门已经判不过**的曲对上接管：兼容的曲对保持逐位相同的落点/窗口
        // （既有计划类测试钉着它们；Apple 那边兼容曲对也是走自己的模板，我们这一步先不越界）。
        // **忠实移植模式（2026-11-21 用户拍板）**：决策链完全交给 MixPaths（Apple 那套）
        // 旧逻辑快照在 git tag `automix-legacy-20261121`。
        if (MIX_PATHS_ENABLED && !referenceApplied) {
            MixPaths.Features feat = new MixPaths.Features();
            feat.tempoRatio = gateTempoA > 1e-9 ? gateTempoB / gateTempoA : 1.0;
            feat.semitones = gateShiftSemis;
            // 叠唱 = 去人声让位的时段总长（DSP 那边就是按这些区间让位的，语义一致）
            feat.vocalOverlapSecs = overlapSecs(duckRanges);
            MixPaths.Pick pick = MixPaths.choose(feat, stableSeed(outro, intro, "path"));
            dev.t1m3.qplayer.util.Logger.info(
                    "[qplayer-engine] 接法选择：路径 {}（{} · 效果 {} · 窗口×{} ≤ {}s · 对拍={} 移调={}"
                            + " · 湿声 {} · 滤波 {} dB/oct · riding {} · 同层 {} 条 · 通过 [{}] · 淘汰 [{}]",
                    pick.template.id.name().toLowerCase(java.util.Locale.ROOT),
                    fmt(pick.score), pick.template.effect, fmt(pick.template.windowScale),
                    fmt(pick.template.maxWindowSecs), pick.template.beatMatched, pick.template.pitchShift,
                    fmt(pick.template.reverbWet), fmt(pick.template.filterSlope), pick.template.riding,
                    Integer.valueOf(pick.layerSize), pick.passed, pick.rejected);
            // **② 多路径各自生成候选再比较**（参考包是"路径优先"，不是"点位优先"）：
            // 每条通过的路径按自己的模板算出**它自己的窗口**，摆在一起看，最后取最高分那条。
            // 日志里这一行就是"生成过哪些候选"的现场证据。
            java.util.List<MixPaths.Choice> all = MixPaths.evaluate(feat);
            StringBuilder cand = new StringBuilder();
            for (int i = 0; i < all.size(); i++) {
                MixPaths.Choice c = all.get(i);
                if (c.verdict != null) {
                    continue;
                }
                double w = Math.min(cue.windowSecs * c.template.windowScale, c.template.maxWindowSecs);
                if (w < 0.5) {
                    continue;
                }
                if (cand.length() > 0) {
                    cand.append("  ");
                }
                cand.append(c.template.id.name().toLowerCase(java.util.Locale.ROOT))
                        .append(' ').append(fmt(w)).append("s/").append(fmt(c.score));
            }
            if (cand.length() > 0) {
                dev.t1m3.qplayer.util.Logger.info(
                        "[qplayer-engine] 路径候选（各自按模板算窗口）：{} → 取 {}",
                        cand, pick.template.id.name().toLowerCase(java.util.Locale.ROOT));
            }
            if (pick.template.id == MixPaths.Id.SOFT_SKIP) {
                dev.t1m3.qplayer.util.Logger.info(
                        "[qplayer-engine] 接法选择：soft_skip → 本次不做融合（窗口 0），曲终由控制器硬接");
                return basePlan;
            }
            // **对拍类路径不改动既有计划**（兼容的曲对保持逐位相同的落点与窗口）：
            // 模板把关只在"非对拍路径"上执行 —— 那一档本来就是"不许长混"的降级。
            if (pick.template.beatMatched) {
                return new TransitionPlan(cue.introEntrySecs + introSkipBonus, basePlan.pitch,
                        introStartAt, cue.note, planTailSecs, duckRanges, duckCurve);
            }
            // 模板只做**上限**（cap），不按比例缩：用户设的窗口在兼容曲对上必须原样保留，
            // 否则"设 8 秒却装 4 秒"（实测把 AutoCuePlanTest 三条钉住的语义全推翻了 ✗）。
            // **对拍类路径保留用户设的窗口**（Apple 也是按用户/档位给的窗口长度跳，不擅自砍）；
            // 只有降级路径（smart/fallback/dead_air/soft_skip）才用模板上限压短。
            double capped = pick.template.beatMatched
                    ? cue.windowSecs
                    : Math.min(cue.windowSecs, pick.template.maxWindowSecs);
            if (capped < cue.windowSecs - 1e-6) {
                dev.t1m3.qplayer.util.Logger.info(
                        "[qplayer-engine] 接法选择：模板把关 → 窗口 {}s → {}s（路径 {}）",
                        fmt(cue.windowSecs), fmt(capped),
                        pick.template.id.name().toLowerCase(java.util.Locale.ROOT));
                cue = cue.withWindow(capped);
                autoWindow = Math.max(1L, Math.round(capped * (double) rate));
                finishGeometry();
            }
            if (!pick.template.beatMatched) {
                stretchFeed = null;
                stretchSpeed = 1.0;
                stretchSpeedMean = 1.0;
                prepareRampedOutgoingSkipped = true;
            }
        }
        return new TransitionPlan(cue.introEntrySecs + introSkipBonus, basePlan.pitch, introStartAt,
                cue.note, planTailSecs, duckRanges, duckCurve);
    }

    /**
     * <b>进场曲（B）的窗口素材预渲染</b>（第一阶段工作三：双方各承担一半）。
     *
     * <p>与 {@link #prepareRampedOutgoing} 是同一件事的另一侧：读 B 在进场点之后的窗口素材、
     * 按 {@code speedB = 1 / speedA} 做一次保音高 WSOLA（曲线同形：前 convergeFrac 收敛、
     * 之后保持不划回），挂进 {@link #introOutgoing} 让渲染时的 {@code to} 用它。
     *
     * <p>任何一步失败一律<b>退回不变速</b>（B 按原速播）——那时 {@link #introSkipBonusSecs}
     * 也不会被算进去（它只看队列里有没有素材），所以 Deck 的位置与素材一致。
     */
    private void prepareRampedIncoming(String intro, java.util.Map<String, String> headers,
                                       TempoRamp ramp, double entrySecs, double windowSecs) {
        introOutgoing = new SegmentFeed[0];
        introIndex = 0;
        if (tuning.tempoStretch <= 0.0f || ramp == null || !ramp.active || rate <= 0) {
            return;
        }
        double endA = ramp.endSpeedFor(windowSecs);
        double endB = 1.0 / endA;                 // speedA × speedB = 1（几何平均的定义）
        // 记住 B 侧变速参数（供 `contentFramesFor` 换算界面/歌词位置 ✓）
        this.introSpeedStart = endB;
        this.introSpeedWindow = Math.max(1, (int) Math.round(windowSecs * rate));
        if (Math.abs(1.0 - endB) < STRETCH_MIN_DEVIATION) {
            return;
        }
        int windowFrames = (int) Math.round(windowSecs * rate);
        if (windowFrames <= 0) {
            return;
        }
        try {
            double needFrac = Math.abs(1.0 - endB)
                    / (TempoRamp.SLEW_MAX * Math.max(1e-6, windowSecs));
            double convergeFrac = Math.min(TempoRamp.CONVERGE_FRAC, needFrac);
            // ⚠️ **端点按他的规则对调**（2026-10-08 ✓，照搬 goal 第 55 轮 ✓）：
            // 他的是 **B：`1/ratio → 1`** ✓（窗口起点略快、**末点归原速** ✓ —— "相向靠拢" ✓）；
            // 而我此前写的是 `1.0 → endB` ✗（窗口内**加速** ✗）⇒ **方向相反** ✗。
            // 取素材长度这一处必须与拉伸**同步** ✓（否则材料不够 —— 提速侧才需要多读 ✓）。
            int need = TempoBridge.requiredFramesConverge(windowFrames, rate, endB, 1.0, convergeFrac);
            // **提速侧要多读一段**：stretchCore 的搜索与重叠相加会读到窗口末尾之后
            // 一个块（grain + search）。降速侧永远读不到那里，提速侧会——少读就直接越界
            // （实测踩到：素材恰好 = need，索引 294424 越界）。余量按它自己的要求给。
            int margin = (int) Math.ceil(rate * 0.044) + (int) Math.ceil(rate * 0.004) + 8;
            // **再多要半秒**（2026-10-07，用户实测报障）：
            //   「过渡提速失败（ArrayIndexOutOfBoundsException: Index 256438 out of bounds
            //     for length 256438），这一次 B 按原速播」
            // 下标 == 长度 ⇒ 正好多读一个样本，而上面那 48 ms 的余量**已经在了** ⇒
            // 说明这次的实际消耗**超出了它自己的估算**（`requiredFramesConverge` 与实际
            // 消耗对不上 —— 本会话第六次"同一个量两处不一致"）。
            //
            // 多读是**无害**的：拉伸只取它需要的那一段，多出来的素材只是多解码一点。
            // 所以先用一个不可能不够的余量把"这一次没变速"这个后果消掉；
            // 真正的账（估算 vs 实际）要单独对平 —— 那时再把这个数收回去。
            int stopgap = (int) Math.round(rate * 0.5);
            setMaterialContext(entrySecs, vocalModeForMaterial());
            float[][] source = materialFor(intro, headers, entrySecs,
                    need + margin + stopgap, rate);
            if (source == null) {
                Logger.warn("[qplayer-engine] 过渡提速：读不到进场曲素材 → 这一次 B 按原速播");
                return;
            }
            float[][] stretched = TempoBridge.stretchConverge(source, rate, windowFrames, endB, 1.0,
                    convergeFrac);
            // **交接尾部直通**（2026-10-09 ✓ 用户：完全到 B 瞬间有"超短 seek"✓）：
            // 依据（判据 `TempoTailOffsetTest` 量出来的 ✓）：文档公式位置相关 −0.997 ✗，
            // 而**搜索**得到的偏移（−11.4 ms 量级 ✓）相关 **1.0000** ✓
            // ⇒ 输出尾部那 40 ms 本来就是源素材 ✓ 只是内容位置偏了 ⇒ 交接时与 deck 样本错位 ✗
            // ⇒ 这一段改回**源样本**（按搜索到的偏移 ✓）⇒ 交接两侧逐位一致 ✓
            // **撤掉 directTail**（2026-10-09 ✓ 用户：窗口正常后仍有 seek ✗）：
            // 判据量出"尾部 40 ms 的源位置比公式早 11.42 ms（相关 1.0 ✓）"，
            // 但 **deck 接下来播的是公式位置** ✓ ⇒ 换上"早 11.42 ms"的样本
            // = 把隐含的相位偏移变成**显式的 11 ms 错位** ✗ ⇒ 听感反而更像 seek ✗。
            // ⇒ 不再改"位置"，改用交接处的**极短交叉淡化**去抹平这 11 ms（另处实现 ✓）。
            // （`directTail` 方法保留但不再调用 ✓；判据 `TempoTailOffsetTest` 仍钉着"搜索能对上"✓。）
            // **最后 40 ms 用"搜索到的偏移"换成源样本**（2026-10-09 ✓ 一次实测的结论）：
            // 判据 `TempoTailOffsetTest` 量出：文档公式算出的源位置与真实拉伸结果**差 548 帧
            // （11.42 ms）**，而按**搜索**得到的偏移相关 **1.0000** ✓。
            // deck 在交接后是从**实际拉伸位置**续播的 ✓ ⇒ 尾部必须用"搜索位置"才与它逐位连续 ✓
            //（用公式位置 = 11.42 ms 错位 ✗ ⇒ 接缝跳变 10.8× ✗ 实测）。
            // `directTail` 搜不到可信偏移（相关 < 0.9）时**原样不动** ✓ 宁可不改 ✗。
            stretched = directTail(stretched, source, windowFrames, endB, rate);
            introOutgoing = new SegmentFeed[] {new SegmentFeed("intro", entrySecs,
                    entrySecs + windowFrames / (double) rate, 1, stretched, windowFrames, 0, 0L, 0L, 0)};
            // 文案随端点对调更新 ✓：现在报的是"**起点**速率 → 1.0000" ✓（他的形状 ✓）
            Logger.debug("[qplayer-engine] 过渡提速：进场曲窗口素材按保调 WSOLA 做时间渐变 "
                            + "{}×→1.0000（起点略快、末点归原速；与出场侧互为倒数；窗口 {}s）",
                    String.format(java.util.Locale.US, "%.4f", Double.valueOf(endB)),
                    String.format(java.util.Locale.US, "%.2f", Double.valueOf(windowSecs)));
        } catch (RuntimeException e) {
            introOutgoing = new SegmentFeed[0];
            introIndex = 0;
            Logger.warn("[qplayer-engine] 过渡提速失败（{}），这一次 B 按原速播", e.toString());
        }
    }

    /**
     * <b>准备学习 EQ 的三段曲线</b>（2026-10-06 第二阶段）。
     *
     * <p>规格（推理用法）：「准备阶段：在 AiMix.prepare 里，对拍完成后……用
     * {@code EqFeatureExtractor} 提取逐帧 36 维特征。把特征矩阵输入 {@code MixEqMlp}，
     * 得到 12 个控制点。对控制点做自然三次样条插值，得到 T×3 的逐帧 EQ 曲线……
     * 缓存到 {@code EqCurve}。」
     *
     * <p>三件事按规格的顺序做：① 从两侧包络**切片**出窗口（50 Hz，与训练数据同一批特征源）；
     * ② 过 MLP（**内嵌权重可用时才走它**）或规则教师（兜底，本机训练未收敛所以是默认）；
     * ③ 转成线性增益表缓存进 {@code EqCurve}，交给内层接法（{@code AiMix}）在渲染时查表。
     *
     * <p>整段要么成功要么什么都不做：任何一步不成立（没包络、窗口太短、内层不是 AiMix）
     * 就直接返回，接法照旧按朴素融合走——<b>绝不让一个缺失的曲线把过渡搞崩</b>。
     */
    private void prepareLearnedEq(AutoCue.Envelope a, AutoCue.Envelope b, AutoCue.Cue cue) {
        if (!(base instanceof AiMix) || a == null || b == null || cue == null
                || a.band == null || b.band == null || cue.windowSecs <= 0.0) {
            return;
        }
        int frames = (int) Math.round(cue.windowSecs / AutoCue.HOP_SECS);
        int fa = (int) Math.round(cue.outroCutSecs / AutoCue.HOP_SECS);
        int fb = (int) Math.round(cue.introEntrySecs / AutoCue.HOP_SECS);
        frames = Math.min(frames, Math.min(a.band.length - fa, b.band.length - fb));
        if (frames < 20) {
            return;
        }
        try {
            EqFeatureExtractor.Side sa = sideOf(a, fa, frames);
            EqFeatureExtractor.Side sb = sideOf(b, fb, frames);
            float bpmA = a.tempo == null ? 0f : a.tempo.bpm;
            float bpmB = b.tempo == null ? 0f : b.tempo.bpm;
            float[][] feats = new float[frames][EqFeatureExtractor.DIM];
            int n = EqFeatureExtractor.extract(sa, sb, cue.windowSecs, bpmA, bpmB, 0.0, feats);
            if (n < 20) {
                return;
            }
            double[] controls = controlsFor(feats, n, cue.windowSecs);
            if (controls == null) {
                return;
            }
            ((AiMix) base).setLearnedEqCurve(EqCurve.of(controls, n));
            Logger.debug("[qplayer-engine] 学习EQ：窗口 {}s × {} 帧 → 12 个控制点（{}）",
                    String.format(java.util.Locale.US, "%.2f", Double.valueOf(cue.windowSecs)),
                    Integer.valueOf(n),
                    MixEqMlp.available() ? "MLP 内嵌权重" : "规则教师（MLP 未收敛/不可用）");
        } catch (RuntimeException | LinkageError e) {
            // ⚠️ **必须连 `LinkageError` 一起接**（2026-10-07 用户实机 trace ✗）：
            // 类找不到时抛的是 `NoClassDefFoundError` ✓ —— 它是 **Error**、不是 `RuntimeException` ✗，
            // 只接 RuntimeException 就会让它一路穿出去、**把准备线程打死** ✗
            // （实测 trace：`EqFeatureExtractor$Side` 找不到 ⇒ 这一次过渡直接没了 ✓，
            //   而且用户看到的是一段裸栈 ✓）。降级成"这一次按朴素融合走" ✓ 才是该有的行为 ✓。
            Logger.warn("[qplayer-engine] 学习EQ：算曲线失败（{}），这一次按朴素融合走",
                    e.getClass().getName() + (e.getCause() == null ? ""
                            : " ⟵ " + e.getCause().getClass().getName()
                                    + "(" + e.getCause().getMessage() + ")"));
        }
    }

    /** 12 个控制点：MLP 可用就走 MLP（逐帧推理后取控制点位置的值），否则规则教师。 */
    private static double[] controlsFor(float[][] feats, int frames, double windowSecs) {
        // **必须问 available() 而不是 embedded()**：后者对"能加载的占位权重"也返回非空，
        // 而那份权重是随机的——实测控制点全在 0.5 附近、把三个频段一起切了 8~11 dB。
        // available() 还要求 TRAINED（训练是否真的收敛），这正是规格第四条要的那道闸。
        MixEqMlp mlp = MixEqMlp.available() ? MixEqMlp.embedded() : null;
        if (mlp == null) {
            return RuleTeacherEq.controlsFrom(feats, frames, windowSecs);
        }
        // MLP 输出的是**控制点**本身（12 个），不需要再取样条
        float[] mean = new float[EqFeatureExtractor.DIM];
        double[] out = new double[MixEqMlp.OUT];
        double[] sum = new double[MixEqMlp.OUT];
        int used = 0;
        for (int f = 0; f < frames; f++) {
            mlp.forward(feats[f], out);
            for (int k = 0; k < MixEqMlp.OUT; k++) {
                sum[k] += out[k];
            }
            used++;
        }
        if (used == 0) {
            return RuleTeacherEq.controlsFrom(feats, frames, windowSecs);
        }
        for (int k = 0; k < MixEqMlp.OUT; k++) {
            sum[k] /= used;                          // 逐帧输出求平均 = 这一段曲线的控制点
        }
        return sum;
    }

    /** 从包络里切出一段当特征源（band + rms + 质心替身，与训练数据同一批）。 */
    private static EqFeatureExtractor.Side sideOf(AutoCue.Envelope env, int from, int frames) {
        float[][] band = new float[frames][];
        float[] rms = new float[frames];
        for (int f = 0; f < frames; f++) {
            band[f] = env.band[from + f];
            rms[f] = env.rms[from + f];
        }
        return new EqFeatureExtractor.Side(band, rms,
                EqFeatureExtractor.centroidStandIn(env.rms, from, frames), null);
    }

    /** 进场侧是不是拿着自己的预渲染素材（"双方各半"只有 B 也动了才成立）。 */
    public boolean hasStretchedIncoming() {
        return introOutgoing.length > 0;
    }

    /**
     * <b>把尾奏循环那一段素材离线拼好</b>（判据与日志读结论，渲染路径只读队列
     * {@link #segmentedOutgoing} 里那一段）。
     *
     * <p>这一档现在是"队列里的一段"（{@link SegmentFeed}）：循环点靠段内的游标绕回，
     * 接缝淡入与进度换算都挂在段上。下面记的形状与纪律一个字没变。
     *
     * <p>形状来自离线原型（{@code AutomixOutroLoopDemoProbe}，用户听感验收过的那一版）：
     *
     * <pre>
     *   A 自然播到 v0 → 循环段播完第一遍（v0+一遍，落在小节线上）
     *   ├─ 过渡窗口开始：A 把循环段**整段再重复一遍**（repeats 遍）
     *   │    前 (repeats−1) 遍 + 本遍的前半 = "只放 A"
     *   │    本遍的后半（半段 = 用户设的窗口吸附到整数小节） = 与 B 等功率交叉淡化
     *   └─ 淡化结束正好是最后一遍的末尾；A 原本的收尾**丢弃**（tailSecs 跟着变大）
     * </pre>
     *
     * <p>于是"淡入点落在小节线上"是<strong>构造出来的</strong>，不是碰上的：一遍循环段是
     * 整数小节，最后一遍的后半段起点自然也是小节线（{@code loopBars − fadeBars} 是整数小节）。
     *
     * <p>不做的理由（各自都如实记一行日志，绝不静默降级）：
     *
     * <ol>
     *   <li>没有 {@code cue.outroLoop}（决策层说不需要循环）——静默返回，这是常态；</li>
     *   <li>本层没有资格改窗口，而这份计划又不是「买时间」那一类——退回普通交叉淡化
     *       （见方法体里第一道闸的注释：AI 模式下只有 {@code buysTime} 那一类放行）；</li>
     *   <li><b>只许延长、不许缩短</b>：一遍循环段比这次窗口还短时买不到时间，
     *       也会退回普通交叉淡化（见第二道闸）；</li>
     *   <li>量不出节拍相位、循环段放不进曲子里、素材读不到——拿不到就退回普通交叉淡化。</li>
     * </ol>
     *
     * <p><b>残留的现实（如实记）</b>：编排器的触发点是<strong>按块</strong>判的，
     * 所以接缝实际会落在那一块里（最多一个 {@link Deck#RENDER_FRAMES}，48 kHz 下 85 ms）。
     * 循环段内部（重复点、淡化起点）与它无关——那两个点在这份素材里是精确的小节线，
     * 偏的只是"自然播放"与"重复素材"接上的那一下。
     *
     * <p><b>包内可见</b>：判据 {@code AutoCueOutroLoopTest} 要能直接喂一份
     * {@code repeats == 2} 的计划——那条路要求"尾奏很短"，现在由 {@code planCollect} 里
     * vamp 终点那个额外候选提供（见那里的三条边界；在那之前"买时间"这一档一次都走不到，
     * 缺口由判据 {@code AutoCueOutroLoopBuyTimeTest} 钉着）。
     */
    void prepareLoopOutgoing(String outro, java.util.Map<String, String> headers,
                             AutoCue.Envelope outroEnvelope, AutoCue.Cue cue,
                             double windowSecs) {
        prepareLoopOutgoing(outro, headers, null, null, outroEnvelope, null, cue, windowSecs);
    }

    /**
     * 同 {@link #prepareLoopOutgoing(String, java.util.Map, AutoCue.Envelope, AutoCue.Cue, double)}，
     * 但<strong>同时拿到两侧的源</strong>——多段路径里的段可以来自进场曲
     * （用户第三个例子：「A 的尾奏 + B 的纯鼓段循环 + B 的主歌」），
     * 那一段的素材只能从 {@code intro} 读。
     *
     * <p>五参那一版转调这里并把 {@code intro} 传成 {@code null}：路径里一旦出现
     * {@code source = "intro"} 的段，就会如实报一行"这一层没拿到进场曲的源"并**整条路径作废**
     * （退回普通交叉淡化），而不是偷偷放一段静音或 A 的素材——报告必须描述事实。
     */
    void prepareLoopOutgoing(String outro, java.util.Map<String, String> outroHeaders,
                             String intro, java.util.Map<String, String> introHeaders,
                             AutoCue.Envelope outroEnvelope, AutoCue.Envelope introEnvelope,
                             AutoCue.Cue cue, double windowSecs) {
        resetSegments();
        limiter.reset();          // 每一次过渡都从"没压过"开始
        instanceSource = null;    // 素材源是"这一次过渡"的，进来先清（谁装谁清）
        if (cue == null) {
            return;
        }
        AutoCue.OutroLoopPlan plan = cue.outroLoop;
        // **多段路径**（>= 2 段）与"单段路径"走的是不同的口径：
        //   ① 多段路径 = 层级更高的一整套决定（谁放的、放几遍、什么顺序都在它里面），
        //      本层只是执行者；它由 prepareFromPath 接，走的是"只做长度闸"那一支。
        //   ② 单段路径（今天尾奏循环显式化出来的那一条）仍然走决策那一支的全部闸门，
        //      并与决策合成的那一段逐位对齐——"等价路径"这条性质就是在这里保证的。
        final boolean multiPath = cue.segments != null && cue.segments.size() > 1;
        final boolean hasPath = cue.segments != null && !cue.segments.isEmpty();
        if (plan == null && !multiPath) {
            return;                       // 不需要循环、也没有多段路径：这一段路一个字节都不碰
        }
        // ── 第一道闸：本层有没有资格决定窗口 ────────────────────────────────
        //
        // 有资格的两条路：用户滑块**现读**当着（{@code liveWindowSecs != null}），
        // 或者这一档本来就允许分析调窗口（{@code tuneWindow}，交叉淡化 / 声波重合）。
        // 两样都没有时（**用户设的「自动 · 自动点位」＝ AI 模式就是这一档**：
        // {@code AutomixSettings.maybeAutoCue} 里 AI 分支既不传现读、也不开 tune）
        // 原来的写法是一律不许动窗口——它怕的是拿 arm 时抄下来的旧值去覆盖
        // {@code AiMix} 挑方案时分析出来的那个窗口（见 {@code prepare} 里那段实测记录：
        // AI 方案比较里赢的是「声波重合 @ 9.0s」，实际装载的却是「交叠 17.1s」）。
        //
        // **但「买时间」这一档要放行（2026-10-05 加，用户原话：「你直接做成品」）**，
        // 理由就在这一档自己的定义里：{@code AutoCue.planOutroLoop} 触发它的条件
        // （{@code needsTime}）就是"尾奏放不下这次窗口"，**或者尾奏剩得少到音乐上会赶**
        // （不足 {@code AutoCue.LOOP_SHORT_TAIL_BARS} 小节）——两条说的都是同一件事：
        // 走到这里时，**时间本来就不够**。
        // 于是"把 vamp 重复那几遍的时间补进窗口"是这一档的分内事，不是越权改用户的设置；
        // 反过来，"对齐网格"那一类（{@code buysTime == false}）的窗口是放得下的，
        // 没有"时间不够"这回事，一律照旧退回普通交叉淡化（{@code AutoCue.loopIsWired}
        // 里"有意不接"的两条理由一条都没变）。
        //
        // 于是 AI 模式下**只有买时间那一类**能过这一关；过了之后还有第二道闸
        // （见下面 loopFrames 之后那段）：只许把窗口**延长**，不许缩短。
        //
        // **有多段路径时这一闸不问**：路径是**层级更高的一整套决定**（谁放的、放几遍都在
        // 它里面），本层只是执行者——再问一次"你有没有资格决定窗口"会把决定本身作废。
        // 生产路径上现在计划层只在买时间接线时才给路径（那时这一闸本来就放行），
        // 所以这一条的差别只在判据里那条人工多段路径上看得出来。
        final boolean mayTuneWindow = tuneWindow || liveWindowSecs != null;
        if (!multiPath && !mayTuneWindow && !AutoCue.loopIsWired(plan)) {
            // 走到这里必然是 AI 模式那一档（没现读、也不许调），所以不必再问一次
            Logger.warn("[qplayer-engine] 尾奏循环：本层没有资格决定窗口（AI 模式的窗口由"
                            + "风格/拍数定），而这份计划又不是「买时间」那一类（{}）→ "
                            + "这一次不循环，照旧交叉淡化", plan.reason);
            return;
        }
        if (outroEnvelope == null || outroEnvelope.tempo == null
                || !(outroEnvelope.tempo.bpm > 0f)) {
            return;                       // 量不出网格就不做（决策层已经挡过一道，这里兜底）
        }
        double barSecs = 4.0 * 60.0 / outroEnvelope.tempo.bpm;
        double phase = AutoCue.barPhaseOf(outroEnvelope, barSecs);
        if (Double.isNaN(phase)) {
            phase = 0.0;
        }
        // ── 多段路径：按路径建队列（"主动编配"真正落地的地方）──────────────────
        if (multiPath) {
            prepareFromPath(outro, outroHeaders, intro, introHeaders, outroEnvelope,
                    introEnvelope, cue.segments, windowSecs, barSecs);
            return;
        }
        int loopBars = Math.max(1, plan.bars);
        // 循环段起点吸到**小节线**（outroVampSpan 已经吸过一次；这里再吸一次是为了让
        // "循环点落在小节线上"这条性质由这一层自己保证——判据也是在这一层钉它的）
        double v0 = AutoCue.snapToBar(plan.span.from, phase, barSecs);
        if (v0 < 0.0) {
            return;
        }
        // **小节数一次性换算成帧**，不是"每小节取整再乘"：小节是时间刻度上的量
        // （barSecs 秒），逐小节取整会每小节攒下最多半帧，8 小节就是 3~4 帧
        // （实测 330744 vs 330747 帧，0.15 ms）——而重复点正是靠这个长度与"自然播完的
        // 第一遍"对齐的。一次换算的残差 ≤ 半帧，且**不随小节数累积**。
        int loopFrames = (int) Math.round(loopBars * barSecs * (double) rate);
        if (loopFrames <= 0
                || v0 + loopFrames / (double) rate > outroEnvelope.durationSecs) {
            Logger.warn("[qplayer-engine] 尾奏循环：循环段（{} 小节）放不进曲子尾部 → "
                    + "这一次不循环", Integer.valueOf(loopBars));
            return;
        }
        // ── 第二道闸（**只在 AI 模式那一支上生效**）：只许延长、不许缩短 ──────────
        //
        // 用户/风格给的窗口是**下限**：买时间的本意就是"时间不够"，所以循环只能把这次过渡
        // 变长（用户 2026-10-05 的规格原话：「延长而不是缩短」）。有资格调窗口的那两档
        // （交叉淡化 / 声波重合）不走这一闸——那边的窗口本来就允许分析挪，改动它们
        // 会让已经落地的听感再动一次，而这次改动只该影响 AI 模式。
        //
        // 判据是"**一遍循环段装得下用户那段淡化吗**"，而不是拿两串帧数去比大小：
        // 淡化本来就按规格吸到整数小节（下面就做这件事），而"一共几小节"是按**量出来的**
        // BPM 算的——两者的小数部分本来就不同源。实测（判据
        // {@code AutoCueOutroLoopAiWindowTest} 的合成素材，量到 128.0012 BPM）：
        // 一遍 4 小节 = 165373 帧，而用户设的 7.5 s = 165375 帧，**只差 2 帧**。
        // 第一版把判据写成 `一遍循环段 ≥ 这次窗口（帧）`，于是这份素材上"4 小节正好等于
        // 7.5 s"这种完全正当的计划被判成"会把过渡变短"而退回——一次拍点估计的抖动
        // 就能让同一份素材在"买得成 / 买不成"之间翻面。所以两把尺子统一到**小节**上，
        // 那正是淡化被量化的单位。
        //
        // 装不下时（实测形状：vamp 只量得到 4 小节 ≈ 7.5 s，而窗口是 12 s）这一档
        // **买不到时间**：它给出的会是一个比用户设的窗口更短的过渡（淡化被"一遍循环段"
        // 那道夹逼截短）。那不是买时间，是"悄悄换了一种更短的过渡"——不做，如实退回
        // 普通交叉淡化并打一行 warn，让日志说清是长度不够、不是没识别。
        //
        // 「只延长」这条性质由两件事一起保证：① 上面的闸让淡化那一段不被截短；
        // ② 整窗 = 重复次数 × 一遍循环段 ≥ 一遍循环段 ≈ 用户设的窗口（差不到半小节，
        // 那正是"吸到小节线"本身的代价，不是这次改动引入的）。
        int fadeBars = (int) Math.round(windowSecs / barSecs);
        if (!mayTuneWindow && fadeBars > loopBars) {
            Logger.warn("[qplayer-engine] 尾奏循环：用户设的窗口（{}s）吸到整数小节要 {} 小节，"
                            + "而尾奏里这段 vamp 一遍只有 {} 小节 → 买不到时间（只会把过渡变短），"
                            + "这一次退回普通交叉淡化",
                    fmt(windowSecs), Integer.valueOf(fadeBars), Integer.valueOf(loopBars));
            return;
        }
        // 淡化长度 = **用户设的窗口**吸附到整数小节，夹在 [1 小节, 一遍循环段] 里。
        //
        // 为什么不直接用用户设的那个秒数：这一档的规格是"淡入点落在小节线上"，
        // 7.5 s 配 1.875 s 的小节正好是 4 小节（原型那对素材就是这个数），
        // 而 6 s 那种值吸到 3 小节（5.625 s）——差 0.4 s，听感上察觉不到，
        // 但"淡化起点在对不对在小节线"是察觉不到的**累积**误差，必须钉在构造里。
        if (fadeBars < 1) {
            fadeBars = 1;
        }
        if (fadeBars > loopBars) {
            fadeBars = loopBars;
        }
        int fadeFrames = (int) Math.round(fadeBars * barSecs * (double) rate);
        int repeats = clampRepeats(plan.repeats);
        long windowTotal = (long) repeats * (long) loopFrames;
        // ── 第二道闸的另一半（同样只在 AI 模式那一支上生效）：延长后的窗口还得放得下 ----
        //
        // 延长窗口会**往前**顶触发点：编排器是"这一曲还剩 窗口 + 丢尾 时开始过渡"
        // （{@code Director.triggerLead}），而买时间这一档的丢尾必然是 0（走到这里就说明
        // 尾奏放不下窗口，{@code segmentTailSecs} 因此被夹到 0）——于是触发点 =
        // 全曲 − 延长后的窗口。窗口一旦长过整首 A，触发点就成了负数，编排器会**在曲子
        // 一开始就开始过渡**（{@code shouldBeginTransition} 是"剩余 ≤ 提前量"）。
        // 那显然不是买时间，是把这首曲子整个吃掉：不许，如实退回普通交叉淡化。
        //
        // 三道长度闸的关系：第一道（循环段放得进尾奏）管的是**素材读得到**，
        // 上面那道（一遍循环段不短于窗口）管的是**不缩短**，这一道管的是**不吞整曲**。
        // 有资格调窗口的那两档不走这里的任何一道（那边的窗口本来就是分析算的，
        // 这次改动不碰它们一个字节）。
        //
        // <p><b>如实记一条残留（这次没动，但下一个人该知道）</b>：买时间这一档的丢尾必然
        // 是 0，于是触发点 = 全曲 − 延长后的窗口，循环素材的接缝就落在**"还差一个窗口"
        // 那个位置**，而不是正好落在 vamp 终点（{@code v0 + 一遍}）上。要让接缝正好落在
        // vamp 终点，得让 {@code outroTailSecs} 允许为**负**（触发点从"全曲 − 窗口"
        // 往后推到 vamp 终点），而 {@link TransitionPlan#outroTailSecs} 现在把它夹在 ≥ 0；
        // 同时 {@link #render} 是按"deck 还有多少帧"截断交付的，触发点一后移，
        // deck 会在窗口走完之前先干（素材其实是离线读的，不依赖 deck，但帧数上限依赖它）。
        // 两件事都得改才谈得上"接缝落在小节线上"，所以今天只做"能循环 + 窗口延长"这一半。
        // 对网格那一类（窗口放得进尾奏）不受影响：那里丢尾 ≥ 0，接缝正好在 vamp 终点。
        if (!mayTuneWindow && windowTotal / (double) rate > outroEnvelope.durationSecs) {
            Logger.warn("[qplayer-engine] 尾奏循环：买时间要把窗口延长到 {}s，而 A 只有 {}s"
                            + " → 那会让过渡在曲子一开始就触发，这一次退回普通交叉淡化",
                    fmt(windowTotal / (double) rate), fmt(outroEnvelope.durationSecs));
            return;
        }
        // ── 决策合成的那条路径：**一段**（循环段 × repeats）──────────────────────
        //
        // 帧几何直接取决策算出来的 {@code loopFrames}（不经过"秒 → 帧"的二次换算）：
        // 这条路上"与从前逐位相同"是硬要求，不该有第二次舍入的机会。
        // 计划层给的显式路径（{@code cue.segments}）**与它等价时就用计划层那一条**
        // ——生产路径上走的正是这个分支（{@code planCollect} 只在接线时给路径），
        // 于是"计划层选路径、渲染层按段消费"这条链是真的通的，不是摆设。
        int insertFrames = (int) (windowTotal - fadeFrames);
        int seamFrames = (int) Math.min(Math.round(LOOP_SEAM_FADE_SECS * (double) rate),
                Math.max(0, insertFrames));
        AutoCue.Segment synthesized =
                new AutoCue.Segment("outro", v0, v0 + loopFrames / (double) rate, repeats);
        final boolean fromPlan = hasPath
                && pathMatchesDecision(cue.segments, synthesized, loopFrames, windowTotal);
        if (fromPlan) {
            synthesized = cue.segments.get(0);
        }
        SegmentFeed feed = readFeed(outro, outroHeaders, synthesized,
                fromPlan ? passFramesOf(synthesized) : loopFrames, seamFrames,
                windowTotal, 0L, insertFrames);
        if (feed == null) {
            Logger.warn("[qplayer-engine] 尾奏循环：循环素材读不出来 → 这一次不循环");
            return;
        }
        // 全部成功才落状态（失败路径上不留半成品）
        enqueue(feed);
        explicitPath = fromPlan;
        loopFeed = feed;
        loopPlan = plan;
        segmentWindowFrames = windowTotal;
        segmentInsertFrames = insertFrames;
        segmentCutSecs = feed.endSecs;
        autoWindow = windowTotal;
        autoTailSecs = Math.max(0.0, outroEnvelope.durationSecs
                - (feed.endSecs + windowTotal / (double) rate));
        finishGeometry();
        // 变速与循环互斥（见 prepare 里那段的理由）：这里显式清干净，免得别处读到半状态
        dropStretch();
        if (!mayTuneWindow) {
            // **这一行是给用户验收的**（他要的就是"循环在我用的那个模式下真的生效了"）：
            // 把"改动前 / 改动后"两个窗口都写出来，一眼能看出这次是**延长**了，
            // 而且延长的量就是"一遍循环段 × 重复次数"。
            //
            // 必须写在**状态真的落地之后**（上面几行才落的状态）：素材读失败、循环段放不进
            // 曲子这些路径都会提前 return，那些情况下什么都没延长——在这里报"已延长"才是
            // 这句话为真的唯一位置（报告必须描述事实，这个包的老规矩）。
            Logger.info("[qplayer-engine] 尾奏循环：AI 模式的窗口本来由风格/拍数定（本层不敢动），"
                            + "但这一次是「买时间」（尾奏放不下这次窗口，或剩得少到会赶"
                            + "——具体是哪一条见下一行的理由）→ 把过渡窗口从 {}s "
                            + "延长到 {}s（只延长、不缩短；一遍循环段 {} 小节、重复 {} 次）",
                    fmt(windowSecs), fmt(windowTotal / (double) rate),
                    Integer.valueOf(loopBars), Integer.valueOf(repeats));
        }
        Logger.info("[qplayer-engine] 尾奏循环：{}→{}s（{} 小节）重复 {} 次 · 窗口 {}s"
                        + "（前 {}s 只放 A、后 {}s 与 B 交叉淡化）· 切点 {}s · {}{}",
                fmt(v0), fmt(v0 + loopFrames / (double) rate), Integer.valueOf(loopBars),
                Integer.valueOf(repeats),
                fmt(windowTotal / (double) rate), fmt(segmentInsertFrames / (double) rate),
                fmt(loopFadeFrames() / (double) rate), fmt(segmentCutSecs), plan.reason,
                explicitPath ? " · 走计划层的路径" : "");
    }

    /**
     * <b>按一条显式路径建队列</b>——「在结构图上找最优路径」这一件事真正落地的地方。
     *
     * <p>与决策那一支（{@link #prepareLoopOutgoing}）的区别都在"谁负责决定"上：
     * 路径已经是**完整决定**（放什么素材、各放几遍、按什么顺序都在它里面），
     * 这里只做三件事：
     *
     * <ol>
     *   <li><b>逐段读素材</b>（{@code source} 选出场曲还是进场曲），任一段读不出来就
     *       整条路径作废——退回普通交叉淡化，绝不留半条路径；</li>
     *   <li><b>算窗口与淡化</b>：窗口 = 各段总长；淡化 = 用户设的窗口吸到整数小节，
     *       夹在 [1 小节, 最后一段的一遍] 里（淡化是"最后那一段的尾巴与 B 交叉"，
     *       跨段就不是这一层能决定的形状了）；</li>
     *   <li><b>落几何</b>：接缝 = 第一段的<strong>终点</strong>（A 自然播到那里，窗口从
     *       第一段的起点重放这条路径），丢尾 = 全曲 − (接缝 + 窗口)。</li>
     * </ol>
     *
     * <p><b>为什么接缝取"第一段的终点"</b>：这是把今天循环那一档的形状（A 自然播到
     * vamp 终点 → 窗口里把 vamp 重放一遍）一般化。路径的第一段必须是<strong>出场曲</strong>的
     * 素材："A 自然播到哪"只有出场曲有定义，第一段要是 B 的素材，"接缝"就无从谈起——
     * 那种形状如实退回（打一行 warn），不猜。
     */
    private void prepareFromPath(String outro, java.util.Map<String, String> outroHeaders,
                                 String intro, java.util.Map<String, String> introHeaders,
                                 AutoCue.Envelope outroEnvelope, AutoCue.Envelope introEnvelope,
                                 java.util.List<AutoCue.Segment> path, double windowSecs,
                                 double barSecs) {
        if (path.get(0) == null || path.get(0).fromIntro()) {
            Logger.warn("[qplayer-engine] 路径：第一段不是出场曲的素材（{}）→ 接缝无从谈起，"
                    + "这一次退回普通交叉淡化", AutoCue.describePath(path));
            return;
        }
        java.util.List<SegmentFeed> feeds = new java.util.ArrayList<SegmentFeed>(path.size());
        long total = 0L;
        for (AutoCue.Segment seg : path) {
            if (seg == null || !(seg.spanSecs() > 0.0)) {
                Logger.warn("[qplayer-engine] 路径：有一段的长度不是正数（{}）→ 这一次退回普通交叉淡化",
                        AutoCue.describePath(path));
                return;
            }
            boolean fromIntro = seg.fromIntro();
            String source = fromIntro ? intro : outro;
            java.util.Map<String, String> headers = fromIntro ? introHeaders : outroHeaders;
            if (source == null) {
                Logger.warn("[qplayer-engine] 路径：这一段要进场曲的素材（{}），"
                        + "但本层没拿到进场曲的源 → 这一次退回普通交叉淡化",
                        AutoCue.describePath(path));
                return;
            }
            // **范围先按包络对一遍**：计划层给的是秒，而这一段能不能读出来只有到了
            // 解码那一步才知道（"读出来只有 N 帧"是一条很难定位的日志）。包络里有真实时长，
            // 在这里挡下来说的话能直接指到路径的哪一段越界了。
            AutoCue.Envelope srcEnv = fromIntro ? introEnvelope : outroEnvelope;
            if (srcEnv != null && srcEnv.durationSecs > 0.0
                    && seg.endSecs > srcEnv.durationSecs + 1e-6) {
                Logger.warn("[qplayer-engine] 路径：段 {} 伸到曲子外面（{} 只有 {}s）→ "
                                + "这一次退回普通交叉淡化",
                        seg, fromIntro ? "进场曲" : "出场曲", fmt(srcEnv.durationSecs));
                return;
            }
            int passFrames = passFramesOf(seg);
            // 先按"窗口几何未知"读一遍（windowFrames = 0 表示进度原样透传）；
            // 整条路径的窗口/淡化算出来之后再重新包一层（见下面那段），
            // 因为进度换算是**整条路径**的性质，而它只有在各段长度都算出来之后才知道。
            SegmentFeed feed = readFeed(source, headers, seg, passFrames, 0, 0L, total, 0);
            if (feed == null) {
                Logger.warn("[qplayer-engine] 路径：段 {} 读不出素材 → 这一次退回普通交叉淡化",
                        seg);
                return;
            }
            feeds.add(feed);
            total += feed.totalFrames();
        }
        if (total <= 0L) {
            Logger.warn("[qplayer-engine] 路径：总长是 0 → 这一次退回普通交叉淡化");
            return;
        }
        // 淡化：用户设的窗口吸到整数小节，夹在 [1 小节, 最后一段的一遍] 里
        int lastPass = feeds.get(feeds.size() - 1).passFrames;
        int fadeFrames = (int) Math.round(Math.max(1, Math.round(windowSecs / barSecs))
                * barSecs * (double) rate);
        if (fadeFrames < 1) {
            fadeFrames = 1;
        }
        if (fadeFrames > lastPass) {
            fadeFrames = lastPass;
        }
        int insertFrames = (int) Math.max(0L, total - fadeFrames);
        int seamFrames = (int) Math.min(Math.round(LOOP_SEAM_FADE_SECS * (double) rate),
                insertFrames);
        for (int i = 0; i < feeds.size(); i++) {
            SegmentFeed f = feeds.get(i);
            // 窗口信息与接缝淡入都是**整条路径**的性质，落在段上只是为了让渲染那边
            // 一个对象就能算出进度（队列里的每一段共用同一份窗口几何；段自己的素材与
            // **旋转**由 withWindow 原样带走——见它的注释）。
            SegmentFeed wired = f.withWindow(i == 0 ? seamFrames : 0, total, f.windowOffset,
                    insertFrames);
            segmentedOutgoing = append(segmentedOutgoing, wired);
        }
        explicitPath = true;
        segmentWindowFrames = total;
        segmentInsertFrames = insertFrames;
        segmentCutSecs = path.get(0).endSecs;
        autoWindow = total;
        autoTailSecs = Math.max(0.0, outroEnvelope.durationSecs
                - (segmentCutSecs + total / (double) rate));
        finishGeometry();
        // 变速与循环/路径互斥：路径已经决定了出场素材，变速那一档会让两套素材互相覆盖
        dropStretch();
        // **两种口径都印**（2026-10-07 用户追问"你算出的出场点貌似是最终的点位"之后加的）：
        //   · **切点** = 出场曲**开始**淡出的位置（= 起点口径，我们的字段用它 ✓）；
        //   · **消失于** = 出场曲**完全没了**的位置（= 他 dump 的 `outgoing_end` 那种口径 ✓）。
        // 两者差一个淡化区，混起来就会"差一整个窗口" ✗ —— 所以一条日志里都写出来 ✓。
        double fadeStartSecs = segmentCutSecs + Math.max(0L, segmentInsertFrames) / (double) rate;
        double goneSecs = segmentCutSecs + total / (double) rate;
        Logger.info("[qplayer-engine] 过渡路径：{}（共 {} 段 · 窗口 {}s · 路径起点 {}s · "
                        + "A 开始淡出 {}s · 后 {}s 与 B 交叉淡化 · A 完全消失于 {}s）",
                AutoCue.describePath(path), Integer.valueOf(feeds.size()),
                fmt(total / (double) rate), fmt(segmentCutSecs), fmt(fadeStartSecs),
                fmt(loopFadeFrames() / (double) rate), fmt(goneSecs));
    }

    /**
     * 读一段素材做成 {@link SegmentFeed}；读不出来返回 {@code null}（调用方决定怎么降级）。
     *
     * <p>三处长度纪律：{@code passFrames} 由调用方给（决策那一支给的是决策算出来的帧几何，
     * 路径那一支给 {@link #passFramesOf}），{@code windowFrames}/{@code windowOffset}/
     * {@code insertFrames} 由调用方给（{@code windowFrames = 0} 表示"进度原样透传"）。
     *
     * <p><b>旋转（{@link AutoCue.Segment#rotateStartSecs}）在这里落地成素材</b>：
     * 还是<strong>读一遍</strong>（{@code [startSecs, startSecs + passFrames)} 那一块，
     * 一个采样都不多读），读完之后把这一块<strong>左旋</strong>
     * {@code round((旋转点 − startSecs) × 采样率)} 帧——于是数组的第 0 帧就是旋转点在源里的
     * 那一刻，一遍的<strong>总帧数不变</strong>（{@code passFrames} 一个字节都没变），
     * 渲染那边 {@link SegmentFeed#cursor} 的绕回也就自动落在"段首"上：
     *
     * <pre>
     *   pcm[0 .. passFrames−rotFrames)  = 源里 [旋转点, 段末)
     *   pcm[passFrames−rotFrames ..)    = 源里 [段首, 旋转点)
     * </pre>
     *
     * 旋转点落在了一遍之外（端点、或者越界）时<strong>如实不旋转</strong>并打一行 warn：
     * 那说明计划层给的几何说不通，这里不猜（{@code AutoCue.Segment} 的构造函数已经把
     * 端点那一类归一化成"没旋转"，所以走到这行 warn 的只有真越界的值）。
     */
    private SegmentFeed readFeed(String source, java.util.Map<String, String> headers,
                                 AutoCue.Segment seg, int passFrames, int seamFrames,
                                 long windowFrames, long windowOffset, int insertFrames) {
        if (source == null || seg == null || passFrames <= 0) {
            return null;
        }
        float[][] pcm;
        try {
            // **走漏斗**（2026-10-07，目标②的关键修正）：这里原先**直接**读素材，
            // 绕过了 materialFor ⇒ 去人声与 LUFS 归一化全都落不到渲染上（实测四个 WAV 全同）。
            // 锚点换算：这一段的窗口时间轴起点 = 段起点 − 段在窗口里的偏移 ⇒
            // materialFor 里算出的 timeline 就正好是**窗口时间**（叠唱区间用的坐标）。
            setMaterialContext(seg.startSecs - windowOffset / (double) rate,
                    vocalModeForMaterial());
            pcm = materialFor(source, headers, seg.startSecs, passFrames, rate);
        } catch (RuntimeException e) {
            Logger.warn("[qplayer-engine] 路径/循环：读素材失败（{}）", e.toString());
            return null;
        }
        if (pcm == null || pcm.length == 0 || pcm[0].length < passFrames) {
            Logger.warn("[qplayer-engine] 路径/循环：素材读出来只有 {} 帧（要 {}）",
                    Integer.valueOf(pcm == null || pcm.length == 0 ? 0 : pcm[0].length),
                    Integer.valueOf(passFrames));
            return null;
        }
        double rotateSecs = Double.NaN;
        int rotFrames = 0;
        if (seg.rotated()) {
            int want = (int) Math.round(
                    (seg.rotateStartSecs.doubleValue() - seg.startSecs) * (double) rate);
            if (want > 0 && want < passFrames) {
                rotateLeft(pcm, want, passFrames);
                rotateSecs = seg.rotateStartSecs.doubleValue();
                rotFrames = want;
            } else {
                Logger.warn("[qplayer-engine] 路径：段 {} 的旋转点落在一遍之外（{} 帧 / 一遍 {} 帧）"
                                + " → 这一段如实不旋转", seg, Integer.valueOf(want),
                        Integer.valueOf(passFrames));
            }
        }
        return new SegmentFeed(seg.source, seg.startSecs, seg.endSecs, seg.repeat, pcm,
                passFrames, seamFrames, windowFrames, windowOffset, insertFrames, rotateSecs,
                rotFrames);
    }

    /**
     * 把一遍的素材<strong>左旋</strong> {@code k} 帧（前 {@code frames − k} 帧来自原来
     * 的第 {@code k} 帧起，末尾 {@code k} 帧来自原来的前 {@code k} 帧）。
     *
     * <p>原地做、只用一份 {@code k} 帧的临时缓冲（一遍就那么大，准备线程上跑一次）。
     * 只碰前 {@code frames} 帧：读出来的数组可能比一遍长（解码器的重采样收尾余量）。
     *
     * <p><b>同一个数组只转一遍</b>：单声道素材在 {@code Automix.readStereoWindow} 里是
     * "左右传同一份"（{@code right == left}，同一个数组对象），照声道逐条转就会把它
     * 转两遍——一遍变成旋转 {@code 2k} 帧，接缝于是落在完全不相干的位置上，
     * 而听感上只是"有点怪"（实测踩到过：合成素材上第一遍的起播点被转成了源里的第 1 帧）。
     */
    private static void rotateLeft(float[][] pcm, int k, int frames) {
        if (pcm == null || k <= 0 || k >= frames) {
            return;
        }
        float[] head = new float[k];
        for (int c = 0; c < pcm.length; c++) {
            float[] ch = pcm[c];
            if (ch == null || ch.length < frames) {
                continue;
            }
            boolean seen = false;
            for (int prev = 0; prev < c; prev++) {
                if (pcm[prev] == ch) {
                    seen = true;
                    break;
                }
            }
            if (seen) {
                continue;                   // 单声道：这一条与前面那条是同一个数组
            }
            System.arraycopy(ch, 0, head, 0, k);
            System.arraycopy(ch, k, ch, 0, frames - k);
            System.arraycopy(head, 0, ch, frames - k, k);
        }
    }

    /** 路径段 → 一遍的帧数（秒是计划层的单位，帧是这一层的：换算只在这一处）。 */
    int passFramesOf(AutoCue.Segment seg) {
        return (int) Math.round(seg.spanSecs() * (double) rate);
    }

    /**
     * <b>计划层给的路径与决策合成的那一段等价吗</b>。
     *
     * <p>等价 = 同一段素材（来源 + 重复遍数 + <strong>没有旋转</strong>）+ 同一段窗口
     * （帧几何对得上，且总长一样）。这是"计划层说了算"与"决策说了算"之间的那道校验：
     *
     * <ul>
     *   <li><b>等价就用计划层那一条</b>——渲染结果与决策合成的那一段<strong>逐位相同</strong>
     *       （判据 {@code AutoCueSegmentedPathTest} 钉着），差别只在"素材是按路径建的"；</li>
     *   <li><b>不等价就按决策合成</b>——那不该让这一次过渡失去循环（把一个表示层的缺口
     *       变成听感回归是最差的取舍），只该让日志里少一行"走计划层的路径"。</li>
     * </ul>
     *
     * <p>帧几何那一条为什么要校验：计划层只有<strong>秒</strong>（它不知道采样率），
     * 而这一段里的帧几何是决策直接算出来的（{@code loopFrames}）。两者本该是同一个整数
     * （计划层那段 span 就是 {@code bars × 小节长}，与 {@code loopFrames} 同一个表达式），
     * 但两次浮点走的不是同一条路，理论上可以差一帧——差一帧窗口长度就与决策不符，
     * 那就不是"等价路径"了，如实按决策走。
     */
    private boolean pathMatchesDecision(java.util.List<AutoCue.Segment> path,
                                        AutoCue.Segment synthesized, int loopFrames,
                                        long windowTotal) {
        if (path == null || path.size() != 1) {
            return false;                  // 多段路径不走这一支（它由 prepareFromPath 接）
        }
        AutoCue.Segment seg = path.get(0);
        if (seg == null || !seg.source.equals(synthesized.source)
                || seg.repeat != synthesized.repeat) {
            return false;
        }
        if (seg.rotated()) {
            // **旋转段不是"等价路径"**：决策合成的那一段永远是顺序播的（它不知道旋转点），
            // 一遍的帧数虽然一样，读出来的素材却是另一块。生产路径上单段旋转段不会出现
            // （旋转只给三段路径里 B 的落床段），所以这一行只是"别把不一样的东西说成一样"。
            return false;
        }
        if (passFramesOf(seg) != loopFrames) {
            Logger.debug("[qplayer-engine] 路径：计划层那一段是 {} 帧、决策是 {} 帧 → 按决策走",
                    Integer.valueOf(passFramesOf(seg)), Integer.valueOf(loopFrames));
            return false;
        }
        return (long) seg.repeat * (long) loopFrames == windowTotal;
    }

    /**
     * 队列的几何收尾：丢尾与<strong>淡化起点</strong>。
     *
     * <p>淡化起点不是另算一遍的：它是"窗口里第 {@link #segmentInsertFrames} 帧在放的那一段
     * 素材，在<strong>源里</strong>是哪一刻"——查队列查出来的（见 {@link #sourceSecsAt}）。
     * 所以在循环那一档上它与从前的 {@code v0 + 一遍 − 淡化} 是<strong>同一个数</strong>
     * （一遍整数小节、{@code repeats × 一遍 − 淡化} 取模一遍就是 {@code 一遍 − 淡化}），
     * 而多段路径上它自然落到"最后那一段里被交叉淡化的那一块"——人声区间要的正是这个位置。
     */
    private void finishGeometry() {
        segmentTailSecs = autoTailSecs;
        segmentFadeStartSecs = segmentedOutgoing.length == 0
                ? 0.0 : sourceSecsAt(segmentInsertFrames);
    }

    /** 窗口里第 {@code windowPos} 帧在放的那一段素材，在源里是哪一刻（按一遍绕回）。 */
    private double sourceSecsAt(long windowPos) {
        for (SegmentFeed f : segmentedOutgoing) {
            long local = windowPos - f.windowOffset;
            if (local >= 0L && local < f.totalFrames()) {
                return f.secsAt(local, rate);
            }
        }
        // 落在队列外面（窗口末尾那一块，编排器多要了一点）：如实报接缝那一刻
        return segmentCutSecs;
    }

    /** 循环最多重复几遍（与决策层同一把尺子，见 {@code AutoCue.LOOP_MAX_REPEATS}）。 */
    private static int clampRepeats(int repeats) {
        return Math.max(1, Math.min(repeats, 2));
    }

    /** 队列清零（每次准备都从"什么都没有"开始）。 */
    private void resetSegments() {
        segmentedOutgoing = NO_SEGMENTS;
        segmentIndex = 0;
        segmentWindowFrames = 0L;
        segmentInsertFrames = 0;
        segmentCutSecs = 0.0;
        segmentFadeStartSecs = 0.0;
        segmentTailSecs = 0.0;
        explicitPath = false;
        loopFeed = null;
        loopPlan = null;
        stretchFeed = null;
        stretchSpeed = 1.0;
        stretchSpeedStart = 1.0;
        stretchSpeedMean = 1.0;
    }

    /** 把一段加进队列（准备线程上跑，用不着为分配操心）。 */
    private void enqueue(SegmentFeed feed) {
        segmentedOutgoing = append(segmentedOutgoing, feed);
    }

    /** 清掉变速那一段（循环 / 路径接手素材时用：两条路都要拿 A 的窗口素材，不允许同时在场）。 */
    private void dropStretch() {
        if (stretchFeed == null) {
            return;
        }
        stretchFeed = null;
        stretchSpeed = 1.0;
        stretchSpeedStart = 1.0;
        stretchSpeedMean = 1.0;
        SegmentFeed[] kept = NO_SEGMENTS;
        for (SegmentFeed f : segmentedOutgoing) {
            if (f != null && f.windowFrames > 0L) {
                kept = append(kept, f);
            }
        }
        segmentedOutgoing = kept;
    }

    private static SegmentFeed[] append(SegmentFeed[] array, SegmentFeed feed) {
        SegmentFeed[] out = java.util.Arrays.copyOf(array, array.length + 1);
        out[array.length] = feed;
        return out;
    }

    /** 把时刻吸附到小节线上（尺子在 {@link AutoCue#snapToBar}：计划层与这一层共用一把）。 */
    static double snapToBar(double secs, double phase, double barSecs) {
        return AutoCue.snapToBar(secs, phase, barSecs);
    }

    /** 把「平成对的秒数」写成给人看的一行（日志用）。 */
    private static String describeRanges(double[] ranges) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format(java.util.Locale.ROOT, "%.1f~%.1fs",
                    Double.valueOf(ranges[i]), Double.valueOf(ranges[i + 1])));
        }
        return sb.toString();
    }

    /** 被包的接法（诊断与测试用；线上渲染路径不读它）。 */
    public Transition inner() {
        return base;
    }

    /**
     * 候选交叠时长（秒）：{@link #tuneWindow} 关着时就是那一个值本身。
     *
     * <p>开着时给一组围绕用户设定值的候选（一半 / 四分之三 / 原值 / 一倍半 / 两倍），
     * 夹在 1–20 秒里。为什么是「围绕」而不是自由搜：用户设的那个值表达的是他的偏好
     * （短 = 干脆、长 = 绵长），分析只该在这个偏好附近按实际情况挪一挪，
     * 不该把他拖到 20 秒去——那是另一种风格，不是同一档的优化。
     */
    static double[] windowCandidates(double nominal, boolean tune) {
        if (!tune) {
            return new double[]{nominal};
        }
        double[] raw = {nominal * 0.5, nominal * 0.75, nominal, nominal * 1.5, nominal * 2.0};
        double[] out = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = Math.max(1.0, Math.min(20.0, raw[i]));
        }
        // **用户设的那个值本身不夹**：把「围绕值」夹到 1–20 s 是为了别让分析把他拖远，
        // 但他设的 60 s 得原样留在候选里——否则「按分析调」会变成「悄悄改成 20 s」，
        // 那是改动他的设置，不是优化。（候选去重由 AutoCue.sanitizeWindows 做。）
        out[2] = nominal;
        return out;
    }

    /** 这一层会不会按分析结果改窗口（判据用；线上不读）。 */
    public boolean tunesWindow() {
        return tuneWindow;
    }

    /**
     * <b>按句柄给计划层一个"这对曲子的调性关系"的来源</b>（{@code null} = 不知道）。
     *
     * <p>为什么要绕这一道：{@code planCollect} / {@code planOutroLoop} 手上只有两侧的
     * <strong>包络</strong>，包络里只有三条分频带、没有色度，判不了调性；而判调性
     * （{@link Harmonic}）要读 PCM。所以由**这里**（唯一同时拿着两侧句柄与请求头的地方）
     * 问一次宿主装上的来源（{@code AutoCue.installKeyRelationSource}），把答案的
     * <b>取法</b>交给计划层——它只在这一次真的有可循环段时才真的去取
     * （见 {@code AutoCue.planWithLyricSpans} 七参版本的注释）。
     *
     * <p>宿主没装来源时返回的供应者取出来就是 {@code null}，于是
     * 「调性冲突但节奏兼容」这一条<b>不参与触发</b>，其余逐位不变——与
     * {@code installLineSource} / {@code installLyricSpanSource} 同一条纪律：
     * <b>算不出来就不猜</b>。
     */
    static java.util.function.Supplier<AutoCue.KeyRelation> keyRelationOfPair(
            String outro, java.util.Map<String, String> outroHeaders,
            String intro, java.util.Map<String, String> introHeaders) {
        return () -> AutoCue.keyRelationOf(outro, outroHeaders, intro, introHeaders);
    }

    /**
     * 这一层这一次真正要用的候选集：标称值 + 降速斜坡给的窗口上界（{@link #rampPlan}）。
     *
     * <p>上界从字段读而不是从参数传：两级扫描（浅扫 / 深扫）用的是<b>同一份</b>候选集，
     * 而"这一次要不要降速、窗口上界是多少"是整条路径的性质，不是某一次调用的性质。
     *
     * <p><b>这里是那个上界唯一被施加的地方</b>（2026-10-06 合并）：调用方一律传
     * <b>用户/风格给的标称值</b>，不再自己先取一次 {@code min}。传标称值与传上界本身
     * <b>结果逐位相同</b>：{@code cap = min(nominal, ramp.windowSecs)}、
     * {@code top = max(1, min(nominal, cap))}，而 {@code ramp.windowSecs} 已经是
     * {@code min(用户值, 上界)} 且 ≥ 1 s，于是两种入参下 {@code top} 都是同一个数。
     */
    private double[] windowCandidates(double nominal) {
        TempoRamp r = rampPlan;
        double cap = r != null && r.active ? Math.min(nominal, r.windowSecs) : 0.0;
        return windowCandidates(nominal, tuneWindow, cap);
    }

    /**
     * 带上「<b>降速斜坡给的窗口上界</b>」的候选集（2026-10-06）。
     *
     * <p>与 {@link #windowCandidates(double, boolean)} 的关系是"多加一道上界"，
     * 而<b>不是</b>改那一条：{@code cap ≤ 0}（没有斜坡）时逐位走原来那一份。
     *
     * <p>上界之内仍然给分析层留了活动余地：候选是"上界的一半 / 四分之三 / 上界"
     * ——**只减不增**管的是上界，不是每一档都必须贴着上界；分析在更短的窗口上
     * 找到更好的点位时（例如素材本来就该干脆地接上），ramp 自己会按
     * {@link TempoRamp#endSpeedFor} 把终点速率重算（窗口短到斜率上限咬住时，
     * 能降多少降多少，不硬凑）。
     */
    static double[] windowCandidates(double nominal, boolean tune, double cap) {
        if (!(cap > 0.0)) {
            return windowCandidates(nominal, tune);
        }
        double top = Math.max(1.0, Math.min(nominal, cap));
        if (!tune) {
            return new double[]{top};
        }
        double[] raw = {top * 0.5, top * 0.75, top};
        double[] out = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = Math.max(1.0, Math.min(20.0, raw[i]));
        }
        // 上界本身不夹（与两参版同一条纪律：用户/风格表达的那个值要原样留在候选里）
        out[out.length - 1] = top;
        return out;
    }

    /**
     * <b>这一对点位满足歌词那三条吗</b>：融合结束同时踩住 A 的句尾与 B 的句头（隔 gA）。
     *
     * <p>两侧任一没有歌词就算"满足"（不知道就不拦），于是它只在这首歌真有歌词时才可能
     * 触发第二级——没有歌词的路径因此逐位不变。
     */
    private static boolean lyricRulesHold(AutoCue.Cue cue, AutoCue.Envelope outroEnv,
                                          AutoCue.Envelope introEnv) {
        try {
            AutoCue.LyricSpans introSpans = AutoCue.vocalPhraseSpans(introEnv);
            AutoCue.LyricSpans outroSpans = AutoCue.vocalPhraseSpans(outroEnv);
            if (introSpans == null || introSpans.starts.length == 0) {
                return true;
            }
            double gA = AutoCue.phraseGapOf(outroSpans, cue.outroCutSecs + cue.windowSecs);
            return AutoCue.headFollowsPhraseGap(introSpans, cue.introEntrySecs,
                    new double[]{cue.windowSecs}, gA, AutoCue.ENTRY_HEAD_TOL_SECS);
        } catch (RuntimeException e) {
            return true;                        // 判不出来就不拦（诚实降级）
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    @Override
    public String toString() {
        return "AutoCueTransition{" + base + ", 自动点位=" + (autoWindow > 0L
                ? ("出场丢尾 " + fmt(autoTailSecs) + "s / 进场 " + fmt(autoEntrySecs) + "s")
                : "未算出") + "}";
    }

    @Override
    public long contentFramesFor(long outFrames) {
        final double s0 = introSpeedStart;
        final int w = introSpeedWindow;
        if (w <= 1 || Math.abs(1.0 - s0) < 1e-6 || outFrames <= 0L) {
            return outFrames;
        }
        final long p = Math.min(outFrames, w);
        final double src = s0 * p + (1.0 - s0) * (double) p * (double) p / (2.0 * w);
        return Math.round(src) + Math.max(0L, outFrames - p);
    }

    /**
     * 降速斜坡的**窗口下限**（秒 ✓ 2026-10-11 ✓ 根因修复的可测部分 ✓）。
     *
     * <p>下限 2.0 s ✓ 但**不越过用户上界** ✓：
     * <pre>windowFloorSecs(6.0) = 2.0   windowFloorSecs(1.0) = 1.0</pre>
     */
    static double windowFloorSecs(double userCapSecs) {
        if (!(userCapSecs > 0)) return 0.0;
        return Math.min(2.0, userCapSecs);
    }
}
