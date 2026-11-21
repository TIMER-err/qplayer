package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.audio.dsp.Resampler;
import dev.t1m3.qplayer.audio.model.InferException;
import dev.t1m3.qplayer.audio.model.StemSeparator;
import dev.t1m3.qplayer.audio.studio.AutotuneParams;
import dev.t1m3.qplayer.audio.studio.DereverbParams;
import dev.t1m3.qplayer.audio.studio.VocalPolisher;
import dev.t1m3.qplayer.audio.stem.StemSeparators;
import dev.t1m3.qplayer.util.Logger;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Locale;

/**
 * <strong>融合阶段只合并背景声、过渡结尾把人声渐恢复</strong>——包在任意接法外面的
 * 一层「去人声」。
 *
 * <p>用户要的听感：过渡的那几秒里两首歌的<b>人声都别叠在一起</b>（叠在一起最糊），
 * 只把两边的<b>背景声</b>融合；等过渡走完、进场曲正式接手时，人声已经渐变回满音量。
 * 所以它做的事只有一件：把进场曲那一路的 {@code to} 换成
 * {@code 伴奏 + 人声 × 包络(progress)}，包络在整个窗口里从 0 走到 1。
 *
 * <h2>为什么是「包装」而不是再写一个接法</h2>
 *
 * <p>{@link Transition} 的语义被拆成两半：
 *
 * <ul>
 *   <li><b>怎么接</b>（窗口多长、什么时候切、转不转调）——由内部接法决定；
 *       三种模式与 AI 模式的五种接法都在这一半里，而且 {@link MixStyle#AUTO}
 *       还会在 {@code prepare} 里换一个接法。</li>
 *   <li><b>接的时候两路用什么素材</b>——就是这一层要改的东西。</li>
 * </ul>
 *
 * <p>所以 {@link #windowFrames(int)} / {@link #name()} / {@link #rewritesSamples()} /
 * {@link #pitchAt(float)} <b>全部原样委托</b>：包装不该改变「在哪一帧开始过渡」这件事
 * （编排器就是拿 {@code windowFrames} 判断触发点的，改写它等于把切歌点挪走），
 * 也不该改变位完美标记与变调曲线。于是这一层自动支持全部三种模式与五种接法，
 * 而且将来往 {@code MixStyle} 里加接法也不用动这里——<b>重写一份就做不到这一点</b>：
 * 那是把「怎么接」抄第二遍，两份迟早分叉，而分叉的表现是「AI 模式的某个接法
 * 悄悄退化成朴素融合」。
 *
 * <h2>三档人声处理，而这一层对「用哪个分离器」一无所知</h2>
 *
 * <p>设置里那一项是三选一（{@code automixVocalMode}）：<b>不分离</b> / <b>DSP</b> /
 * <b>AI（模型）</b>。第一档<b>根本不套这一层</b>（工厂那边就不套，见
 * {@code AutomixSettings.factoryFor}），所以这一层只面对后两档；
 * 而这两档拿分离器的方式完全不同（DSP 是 player-core 自己那份纯 Java 实现，
 * AI 是宿主提供的 ONNX 推理）——<b>差别全部收在 {@link StemSeparators} 里</b>：
 * 这一层只问它三件事（能不能用 / 为什么不能用 / 打开一个），
 * 于是两条实现可以并行演进，而这一层也不会因为「多了第四档」而悄悄用错算法。
 *
 * <p><b>绝不替用户换档</b>：AI 不可用时照常过渡但不去人声，<b>不</b>改用 DSP 顶替——
 * 用户明确选了 AI，悄悄换一种算法就是「换了个东西还不说」，
 * 而这两种算法的听感差别很大（DSP 是卡拉OK级的中心声道抑制，AI 是真分轨）。
 *
 * <h2>包络曲线：smoothstep（3 次 Hermite）</h2>
 *
 * <p>人声增益取 {@code g(p) = t²(3 − 2t)}（{@code t = clamp(p)}，与
 * {@link Automix#smoothstep} 同一条曲线）。选它而不是线性斜坡，有三条理由：
 *
 * <ol>
 *   <li><b>两端是精确的 0 与 1，不是「约等于」。</b> {@code t=0} 时多项式逐位给 0，
 *       {@code t=1} 时给 1。这是硬要求：窗口结束时进场曲要接着以原始音量放下去，
 *       人声增益差一点点（例如线性斜坡在最后一块上的量化残差）就是接缝上的一下台阶。</li>
 *   <li><b>两端的导数也是 0（C¹）</b>，所以人声电平在窗口开头与结尾是「贴着 0/1 起步」的，
 *       没有折角。线性斜坡虽然电平连续，斜率却有折角——听感上是过渡刚开始/刚结束时
 *       人声「一下子开始回来」，正是用户说的那下「啪」。</li>
 *   <li><b>单调不减</b>（{@code g' = 6t(1−t) ≥ 0}），所以人声不会中途又缩回去——
 *       这条是可断言的，见 {@code VocalSeparationTransitionTest}。</li>
 * </ol>
 *
 * <p>形状上它还合一个实际的听感诉求：{@code g(0.25) ≈ 0.16}、{@code g(0.5) = 0.5}，
 * 也就是窗口<b>前半段人声基本不在</b>（用户说的「融合阶段只要背景声」），
 * 后半段才逐步把进场曲的人声让回来。
 *
 * <p>包络是<b>逐样本</b>算的（{@code p + i/window}），不是每块一个值：编排器一块是
 * {@code Deck.RENDER_FRAMES} = 4096 帧（48 kHz 下 85 ms），整块套同一个增益等于把人声
 * 电平做成 85 ms 一级的阶梯（拉链噪声）。同一条理由见 {@code AiMix.renderBeatCut}。
 *
 * <h2>为什么分离只能在 prepare 里做（绝不能碰渲染路径）</h2>
 *
 * <p>一次分轨推理是<b>秒级</b>的活，而且模型上百兆、峰值内存按上游实测可达数百 MB
 * （见 {@link StemSeparator} 的类注释）。渲染路径跑在生产者线程上、环形缓冲只有
 * 200 ms：在那里做一次推理，用户听到的就是「卡住几秒然后再出来」——
 * 与 {@link Transition#prepare} 文档里那条「同步调会把缓冲写空」是同一件事。
 *
 * <p>这条纪律与「用哪一档」无关：DSP 那一档是 {@code O(帧数 · log N)} 的频域改写，
 * 本机实测 44.1 kHz 立体声 10 秒素材 215 ms（<b>只对窗口那几秒做</b>，别拿它整首整首地跑），
 * 但它照样<b>只允许在 {@code prepare} 里跑</b>——渲染路径的预算是一块 4096 帧
 * （48 kHz 下 85 ms），几十毫秒的 STFT 落在那儿同样是欠载；AI 那一档更是秒级 + 上百兆内存。
 * 而且渲染路径上不能出现「这次算不出来」这种事，分不分得成必须在过渡开始<b>之前</b>
 * 就有结论（否则包络就无从谈起）。
 *
 * <p>所以：{@link #prepare} 里读素材 + 分离一次，把两条轨缓存进<b>本实例的字段</b>；
 * {@link #render} 只做「逐样本乘加」，不读盘、不推理、不分配。
 * 实例的生命周期约定（{@link Transition} 的类注释）正好配合这一点：
 * <b>每个实例只服务一次过渡</b>，所以分离结果只在这一次过渡里用，
 * 既不需要全局缓存，也不需要跨过渡的失效逻辑。
 *
 * <h2>失败必须说出来（这条比功能本身更要紧）</h2>
 *
 * <p>AI 那一档的模型没下载 / 会话建不起来 / 推理失败 / 耗时超预算，DSP 那一档的实现
 * 算不出来——这些都会发生，而且都会<b>不阻断过渡</b>：内部接法照常渲染，
 * 一次正常的过渡照做。但「这次没去人声」这件事必须留在 {@code plan.note}
 * （界面与日志都读它）与日志里。
 *
 * <p><b>绝不许在没有分离结果时装作做了</b>——拿原始音频当「伴奏」、或拿静音当「人声」，
 * 都会让「没去人声」看起来像「去了人声」，而用户唯一的判据就是听感：
 * 他会得到「这个功能开了但好像没用」的结论，然后永远不知道该去下载模型。
 * 这条是可执行地钉住的：降级时输出的采样值必须与「没套这一层」<b>逐位相同</b>
 * （见 {@code VocalSeparationTransitionTest}）。
 *
 * <h2>已知代价与不确定性（照实记）</h2>
 *
 * <ul>
 *   <li><b>分离素材是「另一次解码」，不是 {@code to} 那一份。</b> 这一层按时间位置
 *       自己打开进场曲读一段（{@code plan.skipSecs} 起、窗口那么长），再重采样到输出
 *       采样率；而 {@code to} 是 deck 解码 + 重采样出来的。两者在解码器 seek 精度
 *       级别上可能差几帧（FLAC 落在帧首、MP3 要扫帧头）。这几帧只影响「伴奏与人声
 *       之间」的对齐（几十微秒），不影响包络与接缝；对拍定位失败时偏差会更大
 *       （那时 deck 不在 {@code plan.skipSecs} 上）。</li>
 *   <li><b>窗口结束时是「近似原音」。</b> 分离本身不是无损的（{@code 伴奏 + 人声}
 *       与原音有误差：模型是近似，DSP 那档更是「卡拉OK级」的频域近似），所以窗口末尾
 *       与进场曲单独播放之间可能有一点音色台阶。这是预渲染分轨这条路的固有代价，
 *       不是实现漏洞。<b>两档的差距也在这里</b>：DSP 免费、毫秒级、但只在中心声道上
 *       做频带抑制，去掉的人声不如模型干净；AI 慢、要下载，但那是真分轨。</li>
 *   <li><b>输出多于两个声道时只处理前两个。</b> 两个分离器都只吃立体声，而我们的源
 *       几乎都是立体声——{@link Mixer#mapChannels} 对 2 → N 的映射只填前两个声道，
 *       其余声道本来就是零，所以不碰它们不等于漏掉人声。</li>
 * </ul>
 *
 * <p>与文档的分工见 {@code docs/audio-engine.md} §4（模式 → 接法那张表）。
 */
/**
 * <b>人声处理：我们用自己的这一套，不用参照的</b>（用户 2026-10-07 明确要求）。
 *
 * <p>参照档案里那套（{@code aligned_vocal_windows} 对齐人声窗 + {@code vocal_overlap_incompatible}
 * 当闸门）**我们不采用** ✗。我们自己的做法是：
 * <ol>
 *   <li><b>只在叠唱区间替换</b>（{@code duckRanges}）—— 没叠唱的那几秒一个采样都不动 ✓；</li>
 *   <li><b>带包络</b>（{@code mixInto} 里的 {@code envelope = smoothstep(0,1,p)}）✓
 *       —— 人声是渐回、不是硬切 ✓；</li>
 *   <li><b>边缘交叉淡化</b>（{@code DUCK_EDGE_RAMP_SECS}）✓ —— 消掉区间边界上的波形台阶 ✓
 *       （用户当年报的"切换的时候叫一声"就是那个台阶 ✓）；</li>
 *   <li><b>一次且只有一次</b> ✓ —— 素材源与渲染侧不许各减一遍（那会让整段电平掉下去，
 *       实测掉过 11 dB ✗）；接进变速窗口时也必须守这一条（见 {@code AutoCueTransition}
 *       的 {@code setMaterialSource} 注释：提前装 + 惰性分离）。</li>
 * </ol>
 *
 * <p>所以本类**不去对齐他的人声窗**：我们的时间轴由我们自己的变速与
 * {@code SampleBoundary}（半开区间 + floor + ≤1 样本夹紧）保证 ✓。
 */
public final class VocalSeparationTransition implements Transition {

    /**
     * 分轨这一步的预算（秒）。
     *
     * <p>编排器的准备余量是 {@code Director.PREPARE_MARGIN_SECS} = 30 秒，而这次
     * {@code prepare} 里还要做对拍（{@code BeatModels.BUDGET_SECS} = 10 秒）与开 deck、
     * 定位；15 秒是留给分轨的那一份，三项相加仍小于 30。
     *
     * <p>超预算的处理见 {@link #prepare}：<b>这次就不去人声</b>，并如实写出来。
     */
    public static final float DEFAULT_BUDGET_SECS = 15.0f;

    /**
     * 分离素材允许比窗口短多少（帧）。超了就降级不用。
     *
     * <p>给一点余量是因为重采样器收尾可能少几个采样（几毫秒），那是正常的；
     * 而用户实机那一轮少了 <b>0.904 s</b>（725698 帧 vs 769104 帧），那是插件直链那条流
     * 解码提前结束——那种情况必须降级：窗口尾部会混回未分离的素材，听感上就是
     * 「过渡段和标准段时间线对不上」。
     */
    static final long VOCAL_COVER_TOLERANCE_FRAMES = 2_400L;

    /**
     * 解码分块长度。<b>必须小于 {@code Resampler} 的历史容量（8192 帧）</b>，
     * 否则超出的部分会被 {@code push} 静默丢掉、输出时长凭空变短。
     */
    private static final int DECODE_CHUNK_FRAMES = 4096;

    /**
     * 读素材时多要的源帧数：重采样器每算一个输出点要看它前后各 16 帧输入，
     * 末尾那几帧得有人给。多要一点点比少要好——少了窗口尾部就没人声数据了。
     */
    private static final int RESAMPLE_MARGIN_FRAMES = 128;

    /** 分离做成时 {@code plan.note} 里那句话的开头。 */
    private static final String NOTE_DONE = "已去人声";

    /** 没能分离时 {@code plan.note} 里那句话的开头。 */
    private static final String NOTE_SKIPPED = "未去人声";

    /** 被包住的接法（窗口、切点、变调、名称全部以它为准）。 */
    private final Transition inner;

    /** 输出采样率（素材要重采样到它上面）。 */
    private final int rate;

    /** 输出声道数。 */
    private final int channels;

    /** Private working copy: Director retains the unconsumed part of its input buffer. */
    private AudioBuf vocalScratch;

    /**
     * 人声怎么处理（{@link StemSeparators} 的两档之一）。
     *
     * <p>这里存的是<b>档位</b>而不是某个实现对象：拿分离器一律走
     * {@link StemSeparators}，所以这一层对「DSP 还是模型」一无所知。
     */
    private final int vocalMode;

    /** 分轨预算（秒）。 */
    private final float budgetSecs;

    /**
     * 人声后期（修跑调 / 去混响）的参数。
     *
     * <p>为什么挂在这一层：<strong>这里是全工程唯一真正拿得到「人声轨」的地方</strong>
     * ——分轨就在同一个方法里发生（{@code separateInto}）。修跑调与去混响都只对
     * 单音素材有意义（理由见 {@code AutotuneParams} 的类注释），所以它们必须作用在
     * 分离出来的 {@code vocals} 上，而不是整条混音上。挂在渲染链上是做不到这一点的：
     * 那条路上只有已经混好的两首歌。
     *
     * <p>它在<strong>准备线程</strong>上跑（离线，允许几十毫秒），
     * 于是渲染路径一行都不用改——这是「最贵的处理放在准备上」那条纪律的又一次应用。
     */
    private final AutotuneParams autotune;

    private final DereverbParams dereverb;

    // ---- 本次过渡的分离结果（只在 prepare 的那条线程写，渲染线程读） ----
    //
    // 四条轨都按**输出声道**排好：ch0 是输出声道 0 要用的，ch1 是输出声道 1 要用的。
    // 单声道输出时只填 ch0（口径与 Mixer.mapChannels 的下混一致），ch1 留 null。

    private float[] instrumental0;

    private float[] vocals0;

    private float[] instrumental1;

    private float[] vocals1;

    /** 素材覆盖的帧数（= 上面那几条数组的长度）。 */
    /** **素材健全性闸门**（2026-10-09 ✓）：默认**关** ✓（= 与从前逐位相同 ✓）；开 = 分离素材比原声低 12 dB 以上时保留原声 ✓。 */
    private static volatile boolean vocalSanityGate;

    public static void setVocalSanityGate(boolean on) {
        vocalSanityGate = on;
    }

    public static boolean vocalSanityGate() {
        return vocalSanityGate;
    }

    private int covered;

    /**
     * 这次过渡到底有没有分离结果。
     *
     * <p>{@link #render} 只看它——<b>没有结果就一个采样都不碰</b>（这条是硬要求：
     * 拿原始音频当伴奏、拿静音当人声都等于假装做了）。
     */
    private boolean separated;

    /**
     * 「哪几秒需要去人声」（秒，相对窗口起点；{@code null} = 没这个信息 → 整窗去人声）。
     *
     * <p>来源是 {@code AutoCue.vocalOverlapRanges}（经 {@code TransitionPlan.vocalDuckRanges}
     * 传进来）：用户要的是**只在两边人声真的叠着的时候**动素材——没有叠唱的那几秒
     * 原样保留 deck 的素材，既省一次分离（AI 档秒级），也不白白改音色。
     */
    private double[] duckRanges;

    /** 「人声让多少」的连续权重曲线（{@code null} = 没有这个信息，退回区间版）。 */
    private float[] duckCurve;

    /** 空数组 = 明确「窗口里没有叠唱」→ 整段不去人声。 */
    private boolean duckNothing;

    /**
     * 上一次 {@link #prepare} 得出的那句说明（{@code null} = 还没准备过）。
     *
     * <p>它同时是「每个实例只做一次分离」那个闩：非空就说明这次过渡已经有了结论
     * （成功或如实失败），第二次 {@code prepare} 不重跑推理、也不改口径。
     */
    private String preparedNote;

    /**
     * 进场音的请求头（插件直链需要 token/Referer）。
     *
     * <p>实例只服务一次过渡（{@link Transition} 的生命周期约定），所以一个字段足够；
     * 它只在准备线程上被写、被读。
     */
    private java.util.Map<String, String> introHeaders;

    /**
     * 进场 deck 已经<b>交付出去</b>的帧数，也就是「当前这一块的第一个采样对应素材里的哪个下标」。
     *
     * <p>2026-10-02 更正：它推进的是<b>交付量</b> {@code min(to.frames(), written)}，
     * 不是 {@code to.frames()}。原先那句注释（「deck 一次 render 交出多少帧，下一次就是从
     * 那里接着交的」）只在校验器把缓冲整块消费掉时成立；一旦接法少产出（{@code AiMix}
     * 在切点前不出声、{@code out} 装不下时截短），缓冲里就会留下余量，两者不再相等——
     * 按缓冲长度推进会让素材读指针跑到音频前面。判据见
     * {@code VocalSeparationTransitionTest#theMaterialCursorFollowsWhatWasDeliveredNotWhatTheBufferHeld}。
     */
    private int cursor;

    /** 「素材在窗口中间就用尽了」那一行是否已经记过（只记一次）。 */
    private boolean reportedExhausted;

    /** 用默认预算包住一个接法。 */
    public VocalSeparationTransition(Transition inner, int rate, int channels, int vocalMode) {
        this(inner, rate, channels, vocalMode, DEFAULT_BUDGET_SECS);
    }

    /** 同下面那个构造器，但不做人声后期（修跑调/去混响都关）。 */
    public VocalSeparationTransition(Transition inner, int rate, int channels, int vocalMode,
                                     float budgetSecs) {
        this(inner, rate, channels, vocalMode, budgetSecs,
                AutotuneParams.off(), DereverbParams.off());
    }

    /**
     * 用指定预算包住一个接法。
     *
     * @param inner      被包住的接法
     * @param rate       输出采样率
     * @param channels   输出声道数
     * @param vocalMode  人声怎么处理：{@code StemSeparators.DSP} / {@code StemSeparators.AI}。
     *                   <b>这一层不认识任何一个具体实现</b>——分离器一律从
     *                   {@link StemSeparators#forMode(int)} 拿，所以 DSP 与 AI 两条实现
     *                   可以并行演进，而这里也不会因为「多了第四档」而悄悄用错算法。
     *                   （{@code StemSeparators.OFF} = 不分离，<b>调用方在套这一层之前就该挡掉</b>。）
     * @param budgetSecs 分轨这一步的时间预算（秒）。{@code <= 0} 表示「一点都不许花」，
     *                   于是必然超支、必然降级——这是测试用来钉住「超预算」那条路的
     *                   口子，也是一个宿主可以拿它关掉这一层的把手
     */
    /**
     * 完整版：再带上「人声后期」那两个模块的参数。
     *
     * @param autotune 修跑调（只作用在分离出来的人声轨上；{@code off()} = 不做）
     * @param dereverb 去混响（同上）
     */
    public VocalSeparationTransition(Transition inner, int rate, int channels, int vocalMode,
                                     float budgetSecs, AutotuneParams autotune,
                                     DereverbParams dereverb) {
        if (inner == null) {
            throw new IllegalArgumentException("被包住的接法不能为 null：这一层只改素材，接法由它决定");
        }
        this.inner = inner;
        this.rate = Math.max(rate, 1);
        this.channels = Math.max(channels, 1);
        this.vocalScratch = new AudioBuf(ChannelLayout.of(this.channels), 2 * Deck.RENDER_FRAMES);
        this.vocalMode = vocalMode;
        this.budgetSecs = budgetSecs;
        this.autotune = autotune == null ? AutotuneParams.off() : autotune;
        this.dereverb = dereverb == null ? DereverbParams.off() : dereverb;
    }

    /** 人声后期有没有在动（诊断与判据用）。 */
    public boolean polishesVocals() {
        return autotune.active() || dereverb.active();
    }

    /** 被包住的接法（诊断用）。 */
    public Transition inner() {
        return inner;
    }

    /** 这一层用的是哪一档人声处理（诊断用）。 */
    public int vocalMode() {
        return vocalMode;
    }

    /** 这次过渡有没有去人声（只在 {@link #prepare} 之后有意义）。 */
    public boolean separatedVocals() {
        return separated;
    }

    /**
     * 分离素材覆盖了多少帧（= 窗口，或进场曲更短时的实际长度）。
     *
     * <p>包私有：与 {@code AiMix.window} / {@code Automix.togetherAngle} 同一处理——
     * 上游的私有字段对子模块测试可见，Java 的对应可见性就是包私有，
     * 只给同包的 {@code VocalSeparationTransitionTest} 用，不作为对外 API。
     * 「窗口必须在 prepare 之后读」那条纪律就靠它钉住。
     */
    int coveredFrames() {
        return covered;
    }

    // ==================================================================
    // 委托给内部接法的那四个（见类注释：包装不该改变切歌点判定）
    // ==================================================================

    @Override
    public long windowFrames(int sampleRate) {
        return inner.windowFrames(sampleRate);
    }

    @Override
    public String name() {
        return inner.name() + " + 去人声";
    }

    @Override
    public boolean rewritesSamples() {
        return inner.rewritesSamples();
    }

    @Override
    public PitchPlan pitchAt(float progress) {
        return inner.pitchAt(progress);
    }

    // ==================================================================
    // 准备：全部的重活都在这里（异步线程上）
    // ==================================================================

    /**
     * 读素材 + 分离 + 缓存，一次做完；任何一步不成，就<b>如实降级</b>。
     *
     * <p>顺序是有讲究的：<b>先委托、再读窗口</b>。对拍会把窗口拉长最多一拍
     * （见 {@link Transition#windowFrames} 里那条纪律），读早了就会按旧窗口截素材，
     * 窗口尾巴那一段没有分离结果——而过渡是照新窗口走的。
     */
    @Override
    public TransitionPlan prepare(String outro, String intro, int rate) {
        return prepare(outro, intro, rate, null, null);
    }

    @Override
    public TransitionPlan prepare(String outro, String intro, int rate,
                                  java.util.Map<String, String> outroHeaders,
                                  java.util.Map<String, String> introHeaders) {
        // ① 先让内部接法把「怎么接」算完（窗口、跳过量、变调、introStartAt 都是它的结论）
        this.introHeaders = introHeaders;
        TransitionPlan plan = inner.prepare(outro, intro, rate, outroHeaders, introHeaders);
        // 「哪几秒要去人声」由内层计划带进来（自动点位算的）。三种取值各走一条路：
        //   null（没这个信息）→ 照旧整窗去人声；空数组 → 整段不去人声；非空 → 只在这些区间里替换。
        duckRanges = plan == null ? null : plan.vocalDuckRanges;
        // **把叠唱区间泵进内层**（2026-10-07，目标②的接通一步）——
        // 读代码确认过：外层自己那套分离的结果（instrumental0/1）**没有传给任何人**
        // （本文件第 729 行原话），所以 -none 与 -dsp 一直逐位相同。
        // 真正生效的是内层那条**已经验证过**的路：AutoCueTransition 在 fillScratchInto 里
        // 按窗口时间轴（windowOffset + delivered + i）逐帧施加人声增益。
        // 互斥天然成立：外层那条路本来就没接到渲染 ⇒ 不存在"各减一遍"。
        if (inner instanceof AutoCueTransition) {
            // 分离档位：让漏斗知道"要不要分轨"（0 = 不去人声，**默认就是 0** ⇒ 逐位不变）
            ((AutoCueTransition) inner).setVocalMode(vocalMode);
            // 叠唱区间：渲染侧据此在**人声轨**上施加增益（区间外恒为 1 ⇒ 逐位不变）
            ((AutoCueTransition) inner).setVocalDuckRanges(duckRanges);
        }
        duckCurve = plan == null ? null : plan.vocalDuckCurve;
        // 曲线**优先于**区间：区间是开关式的清单，曲线是连续的权重。
        // 注意 duckNothing 的判定：有曲线时**不能**因为「区间是空的」就整段不动手——
        // 曲线本身就是「让多少」的答案，它可能处处接近 0（那就是真的不用动），
        // 也可能在某几段高起来（那正是要动的地方）。两条通道同时给的情况由这里定优先级。
        duckNothing = duckCurve == null && duckRanges != null && duckRanges.length == 0;
        // **每一次过渡都要把结论说出来**（2026-10-02 加；2026-10-03 收敛成一行）。
        //
        // 为什么值得单独一行日志：用户报过「开了 DSP 还是听到两首歌的人声叠在一起」，
        // 而这一层当时是**静默**的——它判断「这段没有叠唱」就什么都不做，用户只能听到结果、
        // 看不到结论。所以「真的动了手」的三种取值各说一句。
        //
        // 「结论是没有叠唱 → 整段不去人声」这一支降到了 debug：它<b>什么也没做</b>，
        // 而同一件事在 AutoCueTransition 的摘要行里已经写成「· 不去人声」了
        // （用户 2026-10-03：「现在太繁琐了」——结论要看得见，重复的结论不必两行）。
        if (duckCurve != null) {
            int peakHop = 0;
            for (int i = 1; i < duckCurve.length; i++) {
                if (duckCurve[i] > duckCurve[peakHop]) {
                    peakHop = i;
                }
            }
            dev.t1m3.qplayer.util.Logger.info(
                    "[qplayer-engine] 去人声：按连续权重曲线让位（峰值 {}/1 在第 {}s，"
                            + "最陡的变化摊在 0.3 秒里）",
                    String.format(java.util.Locale.US, "%.2f", Float.valueOf(duckCurve[peakHop])),
                    String.format(java.util.Locale.US, "%.2f",
                            Double.valueOf(peakHop * AutoCue.HOP_SECS)));
        } else if (duckRanges == null) {
            dev.t1m3.qplayer.util.Logger.info(
                    "[qplayer-engine] 去人声：没有叠唱信息（自动点位没开或拿不到分频包络）→ 整窗去人声");
        } else if (duckRanges.length == 0) {
            dev.t1m3.qplayer.util.Logger.debug(
                    "[qplayer-engine] 去人声：结论是这段没有叠唱 → 整段不去人声"
                            + "（如果这时候你仍然听到两个人声，就是这条结论判错了）");
        } else {
            double total = 0.0;
            for (int i = 0; i + 1 < duckRanges.length; i += 2) {
                total += duckRanges[i + 1] - duckRanges[i];
            }
            dev.t1m3.qplayer.util.Logger.info(
                    "[qplayer-engine] 去人声：只在 {} 处区间里替换素材（合计 {}s）",
                    Integer.valueOf(duckRanges.length / 2), String.format(
                            java.util.Locale.US, "%.2f", Double.valueOf(total)));
        }
        if (duckNothing) {
            return degrade(plan, "自动点位说这个窗口里两边没有叠唱 → 本次不去人声");
        }

        if (preparedNote != null) {
            // **每个实例只做一次分离**（{@link Transition} 的生命周期约定：实例只服务一次
            // 过渡，准备线程上造新的）。编排器不会调第二次；万一有人调了，这里**不重跑**
            // 那次秒级推理，沿用上一次的结论——连同它那句如实说明。
            return withNote(plan, preparedNote);
        }

        // ② **prepare 之后**再读窗口
        long window = inner.windowFrames(rate);

        if (window <= 0) {
            // 窗口为 0 = 硬接，没有可融合的片段。套上这一层本来就毫无意义
            // （工厂那边也不会套），这里如实说明而不是假装去过人声。
            return degrade(plan, "窗口为 0（硬接），没有可分离的片段");
        }
        // **内层要移进场曲时，这一层整段不做事。**
        //
        // 它渲染用的是**自己在准备线程读好的**进场曲分轨，而不是 deck 那条流；
        // 而变调是 deck 对整条流施加的（`Deck.setPitch`）——两者叠在一起，
        // 结果是「分离结果把移调那条通路整个截断」：计划里写着要移，听感上没移，
        // 而且没有任何报错。所以只要内层计划要求了进场侧移调，这一次就让内层自己
        // 按 deck 那条路渲染（声波重合就是这一档：它整条进场曲一起移，本来也不需要分轨）。
        if (Math.abs(plan.pitch.intro) > 1e-6f) {
            return degrade(plan, "内层要移进场曲（" + plan.pitch.intro
                    + " 半音）→ 本次不去人声（去人声会截断 deck 那条移调通路）");
        }
        if (!StemSeparators.available(vocalMode)) {
            // 「能不能用」逐档现问，而且**每次 prepare 现问**：AI 那一档是「磁盘上有没有
            // 那份权重」（下载落地后下一次过渡就该生效），DSP 那一档是「实现装没装上」。
            //
            // **这里绝不改去打开别的档**：用户选的是哪一档就用哪一档，
            // AI 不可用时拿 DSP 顶替就是「换了个东西还不说」——两种算法的听感差别很大。
            return degrade(plan, StemSeparators.unavailableReason(vocalMode));
        }

        // ③ 真的做。计时把 open（要加载上百兆的会话）+ 读素材 + 推理 + close 全算进去：
        //    失败的那次「打开模型」同样可能慢，不记就等于预算形同虚设
        //    （与 AiMix.tempoOf 的记账同一条理由）。
        long t0 = System.nanoTime();
        String failure = separateInto(intro, plan.skipSecs, (int) Math.min(window, Integer.MAX_VALUE - 8L));
        double spentSecs = (double) (System.nanoTime() - t0) / 1e9;

        if (failure == null && spentSecs > (double) budgetSecs) {
            // 超预算就**不用**这份结果，而不是「算都算了就装上」：
            // 预算的唯一作用是保证「结果得在触发点之前到位」，而一次分离花掉 15 秒以上时，
            // 剩下的准备时间（开 deck、定位）已经不够，装上一份迟到的东西只会让
            // 这次过渡被推迟到曲子自然结束。宁可这次老老实实不去人声。
            clearMaterial();
            failure = String.format(Locale.ROOT, "分离耗时 %.1f s，超出预算 %.1f s",
                    Double.valueOf(spentSecs), Float.valueOf(budgetSecs));
        }

        if (failure != null) {
            return degrade(plan, failure);
        }

        // **素材没覆盖满窗口：如实报警，但行为不变**（2026-10-01，用户实机 23:14 的日志）。
        //
        // 两种情况要分清：
        //  ① **进场曲本身就比窗口短**（歌到这儿就结束了）——这是仓库刻意支持的场景，
        //     见判据 {@code VocalSeparationTransitionTest#anIntroShorterThanTheWindowLeavesTheTailUntouched}：
        //     尾部保持原样（未分离），而且那时 deck 也交不出那么多帧，两边一致；
        //  ② **素材够、但那一次读少了**——用户 23:14 就是这种：deck 交足 16.043 s，
        //     而分离素材只有 725698 帧 = 15.119 s（进场曲是插件直链，那条流解码提前结束）。
        //     这一层分辨不出是①还是②（它看不到 deck 交了多少），所以**不改行为**，
        //     只把数字打出来——配合编排器的「交接连续性」「交接位置」两行，
        //     是不是同一份账一眼就能看出来。
        if (window - covered > VOCAL_COVER_TOLERANCE_FRAMES) {
            Logger.warn("融合期去人声：{} 只覆盖 {} 帧（{}.{} s），窗口要 {} 帧（差 {} 帧 / {} ms）——"
                            + "尾部那些会保持未分离（若进场曲本身够长，说明这一次读少了）",
                    StemSeparators.label(vocalMode), Integer.valueOf(covered),
                    Integer.valueOf(covered / rate),
                    String.format(java.util.Locale.ROOT, "%03d",
                            Integer.valueOf((int) Math.round(covered % rate * 1000.0 / rate))),
                    Long.valueOf(window), Long.valueOf(window - covered),
                    Long.valueOf((window - covered) * 1000L / Math.max(1, rate)));
        }
        Logger.debug("融合期去人声：{} 已分离进场曲窗口（{} 帧，耗时 {} s）",
                StemSeparators.label(vocalMode), Integer.valueOf(covered),
                String.format(Locale.ROOT, "%.1f", Double.valueOf(spentSecs)));
        String ok = NOTE_DONE + "（" + StemSeparators.label(vocalMode) + "，窗口 "
                + String.format(Locale.ROOT, "%.1f", Double.valueOf((double) covered / (double) rate))
                + " s）";
        preparedNote = ok;
        return withNote(plan, ok);
    }

    /**
     * 读进场曲窗口那一段并分离它。
     *
     * @return {@code null} = 成功（结果已经写进字段）；否则是一句<b>给人看的失败原因</b>
     */
    /**
     * <b>读一段"去过人声"的窗口素材</b>（2026-10-06：修"人声速度不一致"）。
     *
     * <p>给 {@code AutoCueTransition.installMaterialSource} 用：那一层要自己读一段 PCM 做
     * 变速/循环的预渲染，而渲染路径上的素材本来是**去过人声**的（本层在 prepare 里预读了
     * 原速人声轨、渲染时减掉）。它却从**原始整轨**读 ⇒ 窗口里 A、B 的人声又回来了，
     * 而且分别带着 −3.2% / +3.3% 的速率——正是用户听到的
     * 「他确实匹配到了歌词相同段……还有就是人声速度不一致」。
     *
     * <p>所以这里把"读 + 去人声"打包成一份素材源：那一层读到的就是干净素材，时间轴一致。
     * <b>长度不符宁可返回 {@code null}</b>（调用方会回退读原轨）——拿一份对齐错了的伴奏
     * 去做变速，比不去人声更糟（同 {@code separateInto} 的纪律）。
     *
     * @param mode {@code StemSeparators} 的那一档；没有可用分离器时返回 {@code null}
     */
    public static float[][] accompanimentWindow(String path, java.util.Map<String, String> headers,
                                                double startSecs, int frames, int rate, int mode) {
        float[][] material = readWindow(path, headers, startSecs, frames, rate);
        if (material == null || material.length < 2 || material[0].length == 0) {
            return null;
        }
        StemSeparator sep = StemSeparators.forMode(mode);
        if (sep == null) {
            return null;
        }
        StemSeparator.StemSet set = sep.separate(material[0], material[1], rate);
        if (set == null || set.frames() != material[0].length) {
            return null;
        }
        return new float[][] {set.instrumentalLeft, set.instrumentalRight};
    }

    /**
     * 读一段窗口素材，<b>把两轨都返回</b>：{@code {伴奏L, 伴奏R, 人声L, 人声R}}。
     *
     * <p>为什么需要它（而不是用 {@link #accompanimentWindow}）：那一版返回的是**整段伴奏**，
     * 用在变速窗口上等于把整个窗口都换成伴奏 —— 那正是上次翻车的原因（去人声没有门控、
     * 整段电平掉 11 dB，用户报"过渡段音频被降很低"）。正确做法是把两轨都拿回来，
     * 由调用方**只在叠唱区间**按包络重建（与渲染侧 {@code mixInto} 同一套权重曲线），
     * 于是"一次且只有一次"去人声成立。
     *
     * <p>{@code mode} 传不支持的档位（例如 OFF）时返回 {@code null}：`forMode` 对 OFF 是**抛异常**
     * 而不是返回 null（本仓库的既有行为），所以这里按"算不出来就别猜"吞掉并返回 null，
     * 调用方据此退回直接读文件。
     */
    public static float[][] stemsWindow(String path, java.util.Map<String, String> headers,
                                        double startSecs, int frames, int rate, int mode) {
        try {
            float[][] material = readWindow(path, headers, startSecs, frames, rate);
            if (material == null || material.length < 2 || material[0].length == 0) {
                return null;
            }
            StemSeparator sep = StemSeparators.forMode(mode);
            if (sep == null) {
                return null;
            }
            StemSeparator.StemSet set = sep.separate(material[0], material[1], rate);
            if (set == null || set.frames() != material[0].length) {
                return null;
            }
            return new float[][] {set.instrumentalLeft, set.instrumentalRight,
                    set.vocalsLeft, set.vocalsRight};
        } catch (RuntimeException e) {
            return null;                     // 档位不支持/分离失败 ⇒ 交回直接读文件
        }
    }

    /** 读一段窗口素材（静态版：不依赖实例状态，供上面的素材源用）。 */
    static float[][] readWindow(String path, java.util.Map<String, String> headers,
                                double startSecs, int frames, int rate) {
        return Automix.readStereoWindow(path, headers, startSecs, frames, rate);
    }

    private String separateInto(String intro, double startSecs, int windowFrames) {
        StemSeparator sep = null;
        try {
            // 分离器**只从这个入口拿**（见类注释：这一层不认识任何具体实现）
            sep = StemSeparators.forMode(vocalMode);
            float[][] material = readIntroWindow(intro, introHeaders, startSecs, windowFrames);
            float[] left = material[0];
            float[] right = material[1];
            if (left.length == 0) {
                return "进场曲在这个位置之后没有内容";
            }

            StemSeparator.StemSet set = sep.separate(left, right, rate);
            if (set == null) {
                return "分轨实现返回了 null";
            }
            // 接缝写明「输入与输出的长度必须一致」（见 StemSeparator.separate）：
            // 不一致就说明对齐已经错了，拿它去乘包络会让伴奏与人声错位——
            // 那比不去人声更糟，所以宁可整段不用。
            if (set.frames() != left.length) {
                return "分轨结果长度与输入不符（接缝要求必须一致）："
                        + set.frames() + " ≠ " + left.length;
            }

            assemble(set);
            // **这一段本来就没有人声 → 如实跳过**（2026-10-02，路线图 W3 的后半）。
            //
            // 判据是**相对量**：人声轨的平均绝对幅度不到伴奏轨的 {@value #VOCAL_ABSENT_RATIO}，
            // 就说明这段素材里压根没有人声可去（纯器乐引子、纯伴奏段落、纯环境音）。
            // 为什么值得专门判一次：
            //   · **省一次分离**——DSP 档是几百毫秒，AI 档（htdemucs）是**秒级**；
            //   · **不动素材**——没有东西要去，就不该让素材多过一遍分离器（那会改音色），
            //     这也是「关掉的功能与没有它逐位相同」那条纪律的自然延伸。
            // 返回一句原因字符串 = 走既有的**诚实降级**那条路（`did not separate` +
            // 日志里写清原因），不新开一条静默分支。
            if (vocalLevelIsNegligible()) {
                clearMaterial();
                separated = false;
                return "进场曲这一段本来没有人声（人声轨幅度不到伴奏的 "
                        + String.format(Locale.ROOT, "%.0f%%", Double.valueOf(VOCAL_ABSENT_RATIO * 100.0))
                        + "）→ 本次不去人声（省下这次分离）";
            }
            // 人声后期：**在 assemble 之后**跑，因为它只改 vocals0/vocals1；
            // 伴奏那两条数组连传都不传进去（「只修人声」的结构性保证）。
            polishVocals();
            covered = left.length;
            separated = true;
            return null;
        } catch (InferException e) {
            // 模型缺失、会话建不起来、推理失败、形状对不上——一律如实记下原因再降级
            return "推理失败（" + InferException.brief(e) + "）";
        } catch (AudioException e) {
            return "读进场曲失败（" + e.getMessage() + "）";
        } catch (RuntimeException e) {
            // 宿主实现可能抛别的运行时异常（分轨实现在宿主里）。这里只吞**运行时异常**：
            // Error（OOM、UnsatisfiedLinkError）不在其列，仍然往上冒——那种情况不该被
            // 伪装成「模型不可用」然后在别处炸出更难懂的现象（与 AiMix.tempoOf 同一条）。
            return e.getClass().getSimpleName() + "：" + InferException.brief(e);
        } finally {
            closeQuietly(sep);
        }
    }

    /**
     * 把分离结果按<b>输出声道</b>装配好。
     *
     * <p>单声道输出取两声道平均，口径与 {@link Mixer#mapChannels} 的下混一致——
     * deck 那条路就是这么把立体声源变成单声道的，两边口径不同的话
     * 「伴奏 + 人声」就对不上原来的单声道信号了。
     */
    /**
     * 把分离结果装进来，并<strong>就地</strong>对人声那两条跑一遍后期。
     *
     * <p>「只修人声」这条纪律在这里是<strong>结构上</strong>成立的：伴奏那两条数组
     * （{@code instrumental0/1}）根本没有传给任何人。判据见
     * {@code VocalPolisherTest#theInstrumentalStaysBitIdentical}。
     */
    private void assemble(StemSeparator.StemSet set) {
        if (channels < 2) {
            instrumental0 = average(set.instrumentalLeft, set.instrumentalRight);
            vocals0 = average(set.vocalsLeft, set.vocalsRight);
            instrumental1 = null;
            vocals1 = null;
            return;
        }
        instrumental0 = set.instrumentalLeft;
        vocals0 = set.vocalsLeft;
        instrumental1 = set.instrumentalRight;
        vocals1 = set.vocalsRight;
    }

    /** 人声后期：只碰 vocals0/vocals1（伴奏连传都不传进来）。 */
    private void polishVocals() {
        if (!polishesVocals() || vocals0 == null || vocals1 == null) {
            return;
        }
        dev.t1m3.qplayer.audio.studio.VocalPolisher polisher =
                new dev.t1m3.qplayer.audio.studio.VocalPolisher(rate, autotune, dereverb);
        int frames = polisher.polish(vocals0, vocals1);
        if (frames > 0) {
            Logger.debug("融合期人声后期：{}（{} 帧，只作用在人声轨上）", polisher, Integer.valueOf(frames));
        }
    }

    private static float[] average(float[] a, float[] b) {
        float[] out = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (a[i] + b[i]) * 0.5f;
        }
        return out;
    }

    /** 丢掉这一次的分离结果（超预算时用）。 */
    /** 人声轨的平均绝对幅度不到伴奏轨这个比例，就认为「这段本来没有人声」。 */
    static final double VOCAL_ABSENT_RATIO = 0.05;

    /**
     * 分离出来的两条轨里，人声是不是**可以忽略**（相对伴奏而言）。
     *
     * <p>用相对量而不是绝对阈值：素材响度差很多个量级（-30 dBFS 的钢琴独奏与贴着 0 dB
     * 的电子乐），绝对阈值会把安静那首一律判成「没有人声」。
     * 拿不到任一条轨（分离失败/被清理）时返回 false——**照旧去人声**，
     * 宁可多做一次，也不拿「读不到」当「没有」。
     */
    private boolean vocalLevelIsNegligible() {
        return vocalAbsent(vocals0, vocals1, instrumental0, instrumental1);
    }

    /**
     * 「这段素材本来就没有人声」的纯函数判据（包私有，单测直接喂数组）。
     *
     * <p>读不到任一条轨（分离失败 / 被清理）时返回 <strong>false</strong>——照旧去人声，
     * 宁可多做一次，也不拿「读不到」当「没有」。
     */
    static boolean vocalAbsent(float[] vocals0, float[] vocals1,
                               float[] instrumental0, float[] instrumental1) {
        double vocals = meanAbs(vocals0) + meanAbs(vocals1);
        double instrumental = meanAbs(instrumental0) + meanAbs(instrumental1);
        if (vocals < 0.0 || instrumental <= 1e-9) {
            return false;
        }
        return vocals < instrumental * VOCAL_ABSENT_RATIO;
    }

    /**
     * 某一时刻「该替换多少」的权重：区间内 1、区间外 0，**边缘 {@value #DUCK_EDGE_RAMP_SECS} 秒走坡**。
     *
     * <p>{@code ranges == null} → 恒为 1（没有这个信息 = 照旧整窗去人声，与加这个字段之前
     * 逐位相同）；空数组 → 恒为 0；非空 → 落在任一段的平台上给 1，坡上给 (0,1)。
     *
     * <p>为什么必须有坡（2026-10-02 实测的缺陷）：第一版是硬开关，区间边界上素材从
     * 「deck 原样」瞬间跳到「分离后」——两者差着人声那一路与分离器的音色差，
     * 那是一个波形台阶，用户听到的是「切换的时候叫一声」。
     * 坡长取 {@value #DUCK_EDGE_RAMP_SECS} 秒：与噼哒修正（{@code beatCut} 的 30 ms 斜坡）
     * 同一个量级——短到听不出来，长到足以让台阶消失。
     */
    /**
     * 从连续权重曲线按秒取值（线性插值；越界取端点，空曲线取 0 = 不动手）。
     *
     * <p>抽成纯函数是为了让判据直接钉「连续性」——曲线版取代开关版之后，</br>
     * 这一层最陡的变化应当摊在 {@code AutoCue.DUCK_CURVE_SMOOTH_HOPS} 格（0.3 秒）里。
     */
    static double duckCurveAt(float[] curve, double secs) {
        if (curve == null || curve.length == 0) {
            return 0.0;
        }
        double pos = secs / AutoCue.HOP_SECS;
        if (pos <= 0.0) {
            return curve[0];
        }
        if (pos >= curve.length - 1) {
            return curve[curve.length - 1];
        }
        int i = (int) Math.floor(pos);
        double frac = pos - i;
        return curve[i] * (1.0 - frac) + curve[i + 1] * frac;
    }

    static double duckWeight(double[] ranges, double secs) {
        if (ranges == null) {
            return 1.0;
        }
        double best = 0.0;
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            double from = ranges[i];
            double to = ranges[i + 1];
            double w;
            if (secs < from - DUCK_EDGE_RAMP_SECS || secs > to + DUCK_EDGE_RAMP_SECS) {
                continue;
            }
            if (secs < from) {
                w = (secs - (from - DUCK_EDGE_RAMP_SECS)) / DUCK_EDGE_RAMP_SECS;
            } else if (secs <= to) {
                w = 1.0;
            } else {
                w = ((to + DUCK_EDGE_RAMP_SECS) - secs) / DUCK_EDGE_RAMP_SECS;
            }
            best = Math.max(best, Math.max(0.0, Math.min(1.0, w)));
        }
        return best;
    }

    /** 去人声区间边缘的坡长（秒）：消掉硬开关留下的那个台阶。 */
    static final double DUCK_EDGE_RAMP_SECS = 0.03;

    /** 一条轨的平均绝对幅度；数组为 {@code null} 时返回 −1（= 读不到，不是 0）。 */
    private static double meanAbs(float[] track) {
        if (track == null || track.length == 0) {
            return -1.0;
        }
        double sum = 0.0;
        for (int i = 0; i < track.length; i++) {
            sum += Math.abs(track[i]);
        }
        return sum / track.length;
    }

    private void clearMaterial() {
        instrumental0 = null;
        vocals0 = null;
        instrumental1 = null;
        vocals1 = null;
        covered = 0;
        separated = false;
    }

    /**
     * 降级：照常过渡，但<b>一个采样都不碰</b>，并把原因写进 note 与日志。
     *
     * <p>为什么必须说出来见类注释；这里只强调「说出来」的落点有两处：
     * {@code plan.note}（界面与日志都会读）与一行 warn。
     */
    private TransitionPlan degrade(TransitionPlan plan, String reason) {
        clearMaterial();
        Logger.warn("融合期去人声：{}，这次过渡照常进行但不去人声", reason);
        String suffix = NOTE_SKIPPED + "（" + reason + "）";
        preparedNote = suffix;
        return withNote(plan, suffix);
    }

    /**
     * 把说明拼到内部接法的 note 后面（note 是给人看的，别把内部接法的结论盖掉）。
     *
     * <p><b>每一个重建计划的地方都必须把 {@code outroTailSecs} 带过去。</b>
     * 4 参数的那个构造器把它默认成 0，而它是「被丢掉的尾巴」——编排器靠它把触发点提前
     * （{@code triggerLead = 窗口 + 丢尾}）。丢掉它，自动点位选在曲中的融合点就永远到不了：
     * 触发点塌成「窗口」，过渡被推到曲尾才尝试。实测（用户日志）：
     * 自动点位给出「丢尾 48.5s」，装到编排器手里变成「窗口 4.0s + 丢尾 0.0s」，
     * 触发点从 A 还剩 52.5 秒塌成 4.0 秒 —— 表现就是「没有按预期跳跃和混音」。
     */
    private static TransitionPlan withNote(TransitionPlan plan, String suffix) {
        String note = plan.note == null || plan.note.isEmpty() ? suffix : plan.note + " · " + suffix;
        return new TransitionPlan(plan.skipSecs, plan.pitch, plan.introStartAt, note,
                plan.outroTailSecs);
    }

    // ==================================================================
    // 渲染：只做复制和逐样本乘加，不读盘、不推理；常规 Director 块使用预分配空间
    // ==================================================================

    /**
     * 在独立暂存里混入分轨，再交给内部接法渲染。
     *
     * <p>Director 会保留未消费的输入。改写原缓冲会让区间边缘反复淡化，
     * 还会把去人声后的余量带进正常播放，因此必须保持原输入不变。
     */
    @Override
    public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
        int n = to.frames();
        AudioBuf material = to;
        if (separated && n > 0) {
            if (n > vocalScratch.capacityFrames() || to.channels() != vocalScratch.channels()) {
                // Non-Director callers may supply larger buffers; grow once instead of truncating.
                vocalScratch = new AudioBuf(ChannelLayout.of(to.channels()), Math.max(n, vocalScratch.capacityFrames()));
            }
            vocalScratch.clear();
            vocalScratch.append(to);
            mixInto(vocalScratch, n, progress);
            material = vocalScratch;
        }
        int written = inner.render(out, from, material, progress);
        // 游标 = 这份素材里的下标，而**素材的下标就是「已经听到/交付了多少帧」**——
        // 所以它只能按真正交付出去的帧数推进，绝不能按 `to.frames()`（缓冲里有几帧）。
        //
        // 2026-10-02 修：原来是 `cursor += n`，而 `n = to.frames()`。两者在真实路径上
        // 并不相等，实测抓到的形状（编排器的块内探针）：
        //
        //   to.frames()=6400 ≠ 产出 0      ← 缓冲里 6400 帧，这一块一帧都没交付
        //   to.frames()=4096 ≠ 产出 2560
        //   to.frames()=4096 ≠ 产出 1792
        //
        // 缓冲比产出长，一是因为 `ensureFrames` 按「至少一块」补（余量会攒下来），
        // 二是因为接法自己会少产出（`AiMix` 在切点前不出声、`out` 装不下时截短）。
        // 按缓冲长度推进的后果是**素材的读指针跑到音频前面**：窗口里听到的 B 是
        // 「更靠后」的那一段，而 deck 与界面还在原来的位置；素材被读空之后又掉回
        // deck 那条流——听感就是用户报的「融合部分跑快了，到交接处往回跳一下」。
        //
        // 交付量取 `min(to.frames(), written)`：`to` 短（远端流没跟上）时交付量就等于
        // 它有多少，`to` 长（有余量）时交付量等于真的写出去了多少。
        //
        // 未消费的原始余量仍由 Director 持有，下次从相同素材位置重新生成工作副本。
        cursor += Math.max(0, Math.min(n, written));
        // 素材用尽之后再往后就没有「去人声」了（{@code mixInto} 会把越界那一段原样留着）。
        // 如实记一次：这一行是「窗口里听到的素材位置对不对」这件事的现场证据——
        // 正常情况下它应当在窗口<b>结束</b>时才出现（素材覆盖满窗口），
        // 早于窗口结束就说明这一份读少了（远端直链提前结束之类），值得看一眼。
        if (separated && !reportedExhausted && cursor >= covered && progress < 1.0f) {
            reportedExhausted = true;
            Logger.info("融合期去人声：分离素材在窗口 {}% 处用尽（素材 {} 帧 / 窗口 {} 帧），"
                            + "之后这一段保持未分离",
                    String.format(Locale.ROOT, "%.0f", Float.valueOf(progress * 100.0f)),
                    Integer.valueOf(covered), Long.valueOf(inner.windowFrames(rate)));
        }
        return written;
    }

    /**
     * {@code to[ch][i] = 伴奏[cursor+i] + 人声[cursor+i] × g(progress + i/window)}。
     *
     * <p>越界（素材比窗口短，进场曲不够长之类）就<b>原样留着</b>：那一段本来就没有
     * 分离结果，把它当静音等于「悄悄把人声也删了」，而如实留着顶多是这一小段
     * 恢复成没去人声的样子。越界只可能发生在窗口尾部——那时进场 deck 也已经交不出
     * 那么多帧了，`to.frames()` 自己就短下来了。
     */
    private void mixInto(AudioBuf to, int n, float progress) {
        // 窗口以内部接法为准（它对拍之后可能被拉长过）——这里不能用 progress 反推。
        float perFrame = 1.0f / (float) Math.max(inner.windowFrames(rate), 1L);
        int chs = Math.min(to.channels(), 2);
        for (int ch = 0; ch < chs; ch++) {
            float[] instrumental = ch == 0 ? instrumental0 : instrumental1;
            float[] vocals = ch == 0 ? vocals0 : vocals1;
            if (instrumental == null || vocals == null) {
                continue;
            }
            FloatBuffer o = to.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                int idx = cursor + i;
                if (idx < 0 || idx >= covered) {
                    continue;
                }
                // **只在叠唱时段里替换素材**，而且边缘**交叉淡化**（2026-10-02 修正）。
                //
                // 第一版是硬开关（区间内替换、区间外原样）：那在区间边界上留下一个
                // **波形台阶**——两边差着「人声那一路 ×（1−包络）」+ 分离器的音色差，
                // 用户听到的就是「切换的时候叫一声」（只在自动点位真的产出区间时才发生，
                // 所以是「有概率」）。现在按 DUCK_EDGE_RAMP_SECS 做上下游坡，
                // 权重 0 → 1 平滑过渡：**没有台阶就没有那一声**。
                double secs = (double) idx / (double) Math.max(rate, 1);
                double duck = duckCurve != null ? duckCurveAt(duckCurve, secs)
                        : duckWeight(duckRanges, secs);
                // **素材末尾 200 ms 尾淡**（2026-10-11 ✓ 根因修复 ✓ 用户：「找根源修」✓）：
                // 原来素材一用完（`idx >= covered` ✓）就直接 `continue` ✗
                // ⇒ 有效权重从 1 **阶跃到 0** ✗ ⇒ 人声在窗口最后几十毫秒**整块回弹** ✓
                //   （实测日志：「融合期去人声：分离素材在窗口 93% 处用尽」✗）
                // ⇒ 现在在末尾 200 ms 内把权重线性收到 0 ✓ 没有阶跃 ✓
                if (duck > 0.0 && covered > 0) {
                    duck *= tailFadeWeight(idx, covered, rate);
                }
                if (duck <= 0.0) {
                    continue;      // 区间外（含坡外/素材末尾）：原样保留 deck 的素材，一个字都不动
                }
                // 与 AiMix.renderBeatCut 同一处理：块内逐样本推进进度，
                // 并用 minF32 把上界钉在 1.0（它的 NaN 语义与 Rust 的 f32::min 一致）。
                float p = Automix.minF32(progress + (float) i * perFrame, 1.0f);
                float envelope = Automix.smoothstep(0.0f, 1.0f, p);
                // **素材健全性闸门**（2026-10-09 ✓，用户报"A 的背景+人声一起瞬间没"✓）：
                // 若"分离后的伴奏"比**原声**低 6 dB 以上 ⇒ 这份素材不可信（兜底分轨退化 / 取件错位 /
                // 相位抵消 ✗）⇒ **放弃这一块的替换，保留原声** ✓ —— 宁可不做去人声，也绝不让 A 消失 ✓。
                // 正常素材（实测重建=原声 ✓）永远不触发 ✓，所以这条路逐位不变 ✓。
                int blk = Math.min(256, Math.min(n - i, covered - idx));
                double so = 0.0;
                double ss = 0.0;
                for (int j = 0; j < Math.max(0, blk); j++) {
                    double v0 = o.get(i + j);
                    double v1 = instrumental[idx + j] + vocals[idx + j] * envelope;
                    so += v0 * v0;
                    ss += v1 * v1;
                }
                if (vocalSanityGate && ss * 16.0 < so) {
                    // −12 dB：正常素材（重建=原声 ✓）永不触发 ✓；只有**退化到几乎没声**的素材才被挡 ✓
                    continue;
                }

                float separated = instrumental[idx] + vocals[idx] * envelope;
                if (duck >= 1.0) {
                    o.put(i, separated);      // 平台内：整段替换（与第一版逐位相同）
                } else {
                    // 边缘坡道：按权重把「deck 原样」与「分离后」混起来，消掉台阶
                    o.put(i, (float) (o.get(i) * (1.0 - duck) + separated * duck));
                }
            }
        }
    }

    // ==================================================================
    // 读素材：进场曲从 plan.skipSecs 起、够窗口用的那一段（输出侧规格）
    // ==================================================================

    /**
     * 读进场曲的一段，重采样到输出采样率，返回 {@code {左, 右}}。
     *
     * <p>读的是 {@code startSecs}（= 内部接法给的跳过量，也就是 deck 被定位到的地方）
     * 之后的 {@code windowFrames} 帧。返回的数组可能比 {@code windowFrames} 短
     * （曲子不够长）——调用方按实际长度处理，不做补齐（补齐等于凭空造静音，
     * 那会让「没数据」看起来像「静音的人声」，见类注释）。
     */
    private float[][] readIntroWindow(String intro, double startSecs, int windowFrames) {
        return readIntroWindow(intro, null, startSecs, windowFrames);
    }

    /**
     * @param headers 进场音的请求头（插件直链常常必须带 token/Referer）；
     *                为 {@code null} / 空时与不带请求头的老路完全一样
     */
    private float[][] readIntroWindow(String intro, java.util.Map<String, String> headers,
                                      double startSecs, int windowFrames) {
        // 立体声读取与重采样已挪到 Automix.readStereoWindow（原先生成式那一层也要用同一份，
        // 去人声是**按中心/两侧**做的，把素材折成单声道再去人声等于把整段都当人声减掉）
        return Automix.readStereoWindow(intro, headers, startSecs, windowFrames, rate);
    }

    /** {@code pull}/{@code drain} 的产物搬进裸数组，返回实际搬了多少帧。 */
    private static int copyOut(int produced, float[] dst, int at, int limit, AudioBuf src) {
        int n = Math.min(Math.min(produced, src.frames()), limit - at);
        if (n <= 0) {
            return 0;
        }
        FloatBuffer r = src.channel(0);
        for (int i = 0; i < n; i++) {
            dst[at + i] = r.get(i);
        }
        return n;
    }

    private static void closeQuietly(StemSeparator sep) {
        if (sep == null) {
            return;
        }
        try {
            sep.close();
        } catch (RuntimeException e) {
            // 关不掉一个已经用完的会话不影响这次过渡（宿主那边释放的是原生内存，
            // 最坏的结果是它多留一会儿），但也不该把真正的失败盖掉。
            Logger.warn("融合期去人声：释放分轨会话失败（不影响过渡）：{}", InferException.brief(e));
        }
    }

    @Override
    public String toString() {
        return "VocalSeparationTransition{" + name()
                + ", separated=" + separated
                + ", covered=" + covered
                + ", budget=" + budgetSecs + "s}";
    }

    /**
     * 素材末尾的**尾淡系数**（2026-10-11 ✓ 根因修复的可测部分 ✓）。
     *
     * <p>素材剩 `remain` 帧时返回 `remain / (0.2·rate)` ✓（线性 ✓ 夹到 [0,1] ✓）
     * ⇒ 权重从 1 平滑收到 0 ✓ 不再阶跃 ✗ ⇒ 人声不会在窗口末尾整块回弹 ✓。
     */
    static double tailFadeWeight(int index, int covered, int rate) {
        final int tailFrames = Math.max(1, Math.round(0.2f * (float) Math.max(rate, 1)));
        final int remain = covered - index;
        if (remain >= tailFrames) return 1.0;
        return Math.max(0.0, remain) / (double) tailFrames;
    }
}
