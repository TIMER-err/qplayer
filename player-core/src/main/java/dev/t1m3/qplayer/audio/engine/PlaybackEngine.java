package dev.t1m3.qplayer.audio.engine;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.dsp.BandLevels;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.BitDepth;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.core.PathVerdict;
import dev.t1m3.qplayer.audio.core.RingBuffer;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.audio.dsp.Gain;
import dev.t1m3.qplayer.audio.mix.Deck;
import dev.t1m3.qplayer.audio.mix.Director;
import dev.t1m3.qplayer.audio.mix.PathState;
import dev.t1m3.qplayer.audio.output.OutputConfig;
import dev.t1m3.qplayer.audio.output.OutputDevice;
import dev.t1m3.qplayer.audio.output.OutputDevices;
import dev.t1m3.qplayer.audio.output.OutputHandle;
import dev.t1m3.qplayer.audio.output.OutputRequest;
import dev.t1m3.qplayer.util.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 桌面与安卓共用的播放引擎：把 {@link Director} + {@link Deck} 接到设备输出上。
 *
 * <p>这是 docs/audio-engine.md 的落点。分层与职责边界：
 *
 * <pre>
 * Director（编排：何时开始过渡、何时换 deck、位完美状态）   ┐
 * Deck ×2（解码 → 重采样 → 可选变调 → 声道映射 → 增益）    │ player-core，两端共用
 * OutputFiller（回调体：f32 → 设备格式、欠载补静音）        ┘
 * ---------------------------------------------------------- 接缝
 * Decoder（宿主：桌面 jflac/jlayer/stb + RIFF；安卓 MediaCodec）
 * OutputDevice / OutputStreamControl（宿主：桌面 SourceDataLine；安卓 AudioTrack）
 * </pre>
 *
 * <h2>线程模型：两条线程，各自碰各自的东西</h2>
 *
 * <ul>
 *   <li><strong>生产者线程</strong>（{@value #PRODUCER_THREAD_NAME}）：独占
 *       {@link Director}、环形缓冲的 {@link RingBuffer.Producer}、以及所有
 *       {@link Deck} 状态。它做三件事：{@code Director.pump(块)} →
 *       {@code AudioBuf.interleaveInto} → {@code Producer.push}。
 *       <strong>环满就阻塞等待，绝不丢弃</strong>——丢弃会让位置记账与听感错位
 *       （记账说「已经放到第 30 秒」，耳朵听到的是第 29.8 秒，越积越多）。</li>
 *   <li><strong>宿主流的写线程</strong>（回调语境）：只做
 *       {@code OutputFiller.fill(consumer, scratch, format, out)}。
 *       这是<strong>唯一</strong>允许在「音频回调语境」里跑的东西：不分配、不加锁、
 *       不日志、不系统调用。引擎在别处<strong>一个字节都不碰</strong>那个 Consumer
 *       （它是单生产者单消费者里的消费者端，只能归写线程）。</li>
 * </ul>
 *
 * <p>控制方法（{@link #play} / {@link #pause} / {@link #seek} …）都跑在控制器/UI 线程上，
 * 它们<strong>只往命令队列里放一条命令 + 写几个 volatile</strong>，绝不直接碰
 * {@code Director} 或任何 deck——那些对象的线程纪律是「归生产者线程独占」。
 * 位置、时长、路径状态这些给界面读的量由生产者线程每块发布一次快照（volatile）。
 *
 * <h2>暂停：不关流</h2>
 *
 * <p>暂停<strong>不碰输出流</strong>——关掉就丢了「一条永不中断的输出流」这个无缝前提
 * （ADR-003 §2）。做法是停止喂数据：环里剩下的会自然放完（≤ 一个环，默认 200 ms），
 * 之后由 {@link dev.t1m3.qplayer.audio.output.OutputFiller} 按欠载吐静音，
 * 设备时钟因此不乱。恢复时接着喂，位置连续（deck 在暂停期间根本没有推进）。
 *
 * <h2>跳转：必须清空环形缓冲</h2>
 *
 * <p>跳转后环里还压着近一个满环的<strong>旧位置</strong>音频，不清掉就会先把它放出去
 * 再跳到新位置，听感是「跳过去又弹回来」。生产者线程<strong>不能</strong>自己改环的
 * 读游标（那是消费者独占的），所以走 {@link RingBuffer.Producer#flush()}：
 * 生产者发一个世代号，消费者在自己的线程上把残留丢掉。
 *
 * <h2>曲终：不要自作判断</h2>
 *
 * <p>{@code pump} 返回 0 <strong>不代表</strong>队列播完：过渡窗口里可能出现「这一块
 * 两路都没有内容」，而 {@code Director} 已经按上游那样内部收尾重试过一次。
 * 所以只有在 <strong>{@code pump} 返回 0 且 {@code queued() == 0}</strong> 时才认为
 * 整场播完，并把环里剩下的尾巴放完（{@link #drainRing}）再触发
 * {@link #setOnComplete}——否则控制器一推进队列就会把最后 200 ms 切掉。
 *
 * <h2>音量与位完美</h2>
 *
 * <p>主音量走 {@link Deck} 的增益（分贝）。<strong>增益不是单位值时就不再位完美</strong>，
 * 这是上游 {@code PathState} 的判据（{@code gainIsUnity()}），不是特判——
 * 也绝不允许为了「看起来位完美」而在别处偷偷改采样值。
 *
 * <p>HiFi「位完美直通」开启时：输出流按<strong>源采样率</strong>请求（并带上源位深），
 * 主增益<strong>保持单位值</strong>（ADR-003：HiFi 路径强制旁路一切电平处理，
 * 音量交给系统/硬件；设置页那行说明写的也是「全程不做增益」）。
 * 此时过渡窗口那几秒照样会放弃位完美——{@link #pathState()} 会如实报
 * {@link PathState#TRANSITIONING}，<strong>不假装</strong>。
 *
 * <p>位完美还有第二层判据：链路上全单位状态（重采样器透传 + 增益单位）<strong>不等于</strong>
 * 位完美——32 bit 整数源经过内部 f32 缓冲会丢掉低 8 位，而这一路上没有任何处理单元在动。
 * 所以 {@link #pathState()} 在链路判定之外再叠一层
 * {@link PathVerdict#classify}（判据是 {@code losesInformation()}，
 * 不是 {@code !isBitPerfect()}：24 bit 的 FLAC 在只声明 F32 的设备上不是直通，
 * 但一个比特都没丢）。
 *
 * <h2>与帧完美相关的分寸（本版如实记下的两处缺口）</h2>
 *
 * <ul>
 *   <li><strong>请求头传不进去</strong>：{@link AudioBackend#play(String, Map, long)} 带的
 *       {@code headers}（插件直链需要）在混音层没有通道——{@code Deck.open} 只收句柄，
 *       {@code Director} 也只会 {@code Deck.open(source,…)}。本任务不许改 mix 层，
 *       所以引擎只记下它们（诊断用）并打一条警告，实际打开时用不到。
 *       要真正支持，需要在 mix 层加一条 headers 通路。</li>
 *   <li><strong>{@code Director} 没有 {@code close()}</strong>：换曲时引擎会丢掉上一个
 *       编排器，它手上正在出声的那个 deck（重活是解码器的文件句柄/网络连接）
 *       关不掉——上游靠 Rust 的 {@code Drop}，Java 没有析构。引擎能做的只是把
 *       「已就绪但没安装」的那份准备结果还回去（见 {@link #retireDirector}），
 *       正在出声的那个只能等 GC。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_engine::session::Session}（那个 crate 的
 * {@code Engine} 还兼着命令通道与状态快照，QPlayer 这边控制器已经承担了
 * 「队列推进」与「状态发布」，所以这里只做「接设备输出」这一件事）。
 */
public final class PlaybackEngine implements AudioBackend {

    /** 生产者线程名。与桌面端既有的 {@code qplayer-audio} 区分开，日志里一眼能认出是谁。 */
    public static final String PRODUCER_THREAD_NAME = "qplayer-audio-engine";

    /** 没有会话（或暂停）时的空转间隔。命令到达后最坏晚这么久被处理。 */
    private static final long IDLE_SLEEP_MS = 4L;

    /** 环满时的等待粒度。 */
    private static final long STALL_SLEEP_MS = 2L;

    /**
     * 环满之后最多等多久就认定「设备已经不在消费了」。
     *
     * <p>上游 {@code session.rs} 用 2000 ms：USB DAC 被拔掉时消费者就死了，
     * {@code push} 会永远返回 0。没有这个出口，生产者会永远卡在等环里，
     * 停止命令得不到处理、退出时 join 卡死——正是「程序不退出也不报错」那场事故的入口。
     */
    private static final long STALL_LIMIT_MS = 2_000L;

    /** 曲终之后等环放完的上限（正常 ≤ 一个环的量，200 ms 上下）。 */
    private static final long DRAIN_LIMIT_MS = 3_000L;

    /** {@link #release()} 里 join 生产者线程的上限：退出路径绝不允许无限等。 */
    private static final long RELEASE_JOIN_MS = 500L;

    /** 采样数 → 秒的哨兵：某些 API 要秒，命令里传的是毫秒。 */
    private static final double MS_PER_SEC = 1000.0;

    /**
     * 宿主注入线程优先级的地方。
     *
     * <p>线程优先级是平台的事：安卓要
     * {@code Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)}，
     * 桌面上通常什么都不用做。player-core <strong>不许引任何 Android API</strong>
     * （引擎两端共用），所以这个动作由宿主通过本接口注入。
     */
    public interface ThreadTuner {

        /** 在生产线程启动前调它（实现里可以设优先级、设名字、绑核……）。 */
        void tune(Thread producerThread);
    }

    // ==================================================================
    // 命令
    // ==================================================================

    private static final int CMD_PLAY = 0;
    private static final int CMD_SEEK = 1;
    private static final int CMD_ARM_NEXT = 2;
    private static final int CMD_CLEAR_NEXT = 3;
    private static final int CMD_CONFIRM_HANDOFF = 4;
    private static final int CMD_REPREPARE = 5;

    /**
     * 一条控制命令。
     *
     * <p>为什么要排队而不是「直接改几个 volatile 字段」：{@code play} 与
     * {@code setNextSource} 是<strong>一对有顺序要求</strong>的命令（引擎要靠后者把
     * 「下一首」带上，才能一次性把 {@code Director} 的当前曲与队首安排对），
     * 而 {@code seek} 必须在生产者线程上按顺序作用于 deck。排队是唯一能同时满足
     * 「有参数」「有顺序」「不碰别人线程的对象」的做法。
     */
    private static final class Command {

        final int kind;
        final String source;
        final Map<String, String> headers;
        final long ms;

        /**
         * 这条 PLAY 是不是已经为「等预告的下一首」让过一次路。
         *
         * <p>理由见 {@link #drainCommands}：控制器是 {@code play()} 之后紧接着
         * {@code setNextSource()}，两条命令一般会一起到；先让后面的 ARM 落地，
         * 开编排器时就能一次把「当前曲 + 队首」安排对，省掉一次开文件与一次定位。
         * 只让一次，所以不会无限推迟。
         */
        boolean armWaited;

        Command(int kind, String source, Map<String, String> headers, long ms) {
            this.kind = kind;
            this.source = source;
            this.headers = headers;
            this.ms = ms;
        }
    }

    private final Object commandLock = new Object();

    /** 待处理命令。归 {@link #commandLock} 保护。 */
    private final ArrayDeque<Command> commands = new ArrayDeque<Command>();

    /**
     * 结构性命令的世代号（{@link #play} / {@link #pause} / {@link #resume} /
     * {@link #seek} / 预告下一首 / {@link #release} 各加一）。
     *
     * <p>它的唯一用途是<strong>把阻塞在「等环腾地方」里的生产者叫回来</strong>：
     * 环满时的等待循环每 2 ms 看一次这个号，变了就先回去处理命令（未推完的那一块
     * 留在手上，不丢），处理完接着推。没有它，暂停/跳转最坏要等一个满环（200 ms）
     * 才生效。音量变化<strong>不</strong>算结构性命令——它一秒钟可能来六十次。
     */
    private volatile long commandEpoch;

    // ==================================================================
    // 宿主注入
    // ==================================================================

    /** 输出设备。{@code null} = 开流时用 {@link OutputDevices#defaultDevice()}（懒解析）。 */
    private volatile OutputDevice device;

    /** 设置桥。默认是「读不到设置」的保守值（无缝拼接、不位完美）。 */
    private volatile EngineSettings settings = AutomixSettings.defaults();

    private volatile ThreadTuner threadTuner;

    /** 环形缓冲目标时长（毫秒）。 */
    private volatile int ringMs = EngineFrames.DEFAULT_RING_MS;

    // ==================================================================
    // 回调（UI 线程写，生产者线程读）
    // ==================================================================

    private volatile Runnable onComplete;
    private volatile Runnable onStarted;
    private volatile Runnable onPaused;
    private volatile Runnable onResumed;
    private volatile Runnable onError;

    /**
     * 过渡**开始**那一刻的回调（参数 = 进场曲的音源句柄）。
     *
     * <p>与 {@link #onComplete} 的分工：{@code onComplete} 是「交接完成」（入场曲上位），
     * 而这个回调是「窗口刚开、进场曲已经在出声」。控制器要在后者推进队列，
     * 否则界面会滞后整整一个窗口（见 {@link #reportTransitionEntry}）。
     */
    private volatile java.util.function.Consumer<String> onTransitionStart;

    // ==================================================================
    // 意图与发布（UI 线程读）
    // ==================================================================

    /** 用户要的音量（线性 0..1）。走 deck 的增益，不走任何「偷偷改采样值」的路子。 */
    private volatile float volume = 1.0f;

    /** 用户要暂停。 */
    private volatile boolean pausedIntent;

    /** 用户点过播放（含恢复）。自然播完后由生产者清掉。 */
    private volatile boolean playRequested;

    /** 当前有会话在出声（生产者线程维护，供 {@link #isPlaying()} 用）。 */
    private volatile boolean sessionActive;

    /** 已释放。 */
    private volatile boolean released;

    /** 位置（毫秒，曲内时间）。生产者线程每块发布一次；seek 时会先发布目标值。 */
    private volatile long positionMs;

    /**
     * **"耳朵听到的位置"的唯一公式**（2026-10-09 ✓ 用户：
     * 「我不要面向结果式……说不定在其他歌曲又出现了呢，你这样设计应该直接修复」✓）。
     *
     * <p>为什么要有它：引擎里"帧 → 毫秒"曾经有**两条**口径 ✗ —— 一条扣了输出环（`ringMs` ✓），
     * 另一条（`publishTrackReadings`）没扣 ✗ ⇒ 两条交替写同一个 `positionMs` ✓
     * ⇒ 界面位置在两种口径之间来回 ✓ = 用户看到的「歌词始终比音频快一拍」✓，且换歌会再犯 ✗。
     *
     * <p>用法：**界面侧**（歌词/进度/过渡快照）一律调这个方法 ✓，
     * 契约字段 `positionMs` 与 MPRIS 保持原样（判据钉着它 ✓ 不动 ✗）。
     */
    public long audiblePositionMs(long renderedFrames) {
        return Math.max(0L, EngineFrames.msOfFrames(renderedFrames, rate()) - ringMs);
    }

    /** 时长（毫秒，未知为 0）。未知长度**如实报 0，不猜**。 */
    private volatile long durationMs;

    /** 当前曲目的音源句柄（曲终后仍是最后那一首）。 */
    private volatile String currentSource = "";

    /** 当前曲目的格式判定说明（{@link PathVerdict#summary()}）。 */
    private volatile String formatSummary = "";

    /** 如实计算的信号路径状态（唯一来源，界面直接显示它，不要另算一套）。 */
    private volatile PathState pathState = PathState.PROCESSED;

    private volatile String transitionName = "";

    private volatile String transitionNote = "";

    /**
     * 控制室要的那一份快照（P4 的「看」）。
     *
     * <p>生产者每块发布一次，界面读它——与 {@code SpectrumTap} 同一个理由：
     * 过渡的读数都住在生产者线程独占的对象里，界面直接去问就是数据竞争。
     */
    private volatile TransitionSnapshot transitionSnapshot = TransitionSnapshot.IDLE;

    /**
     * 分析表头（P8）：主总线那一块的读数（峰值/真峰/LUFS/相位）。
     *
     * <p>它在开流时按输出配置建（采样率与声道数要一致），每块处理一次；界面读它发布的快照。
     * 座位在**工作室主总线之后**：表头看到的必须就是听得到的（与电平表同一个口径）。
     */
    private volatile dev.t1m3.qplayer.audio.studio.StudioMeters studioMeters;

    /**
     * 实时频谱分析器（工作站的频谱显示）。与 {@link #bandLevels} 同一处发布：
     * 都在主总线之后，所以画面看到的就是听到的。
     */
    private volatile SpectrumBands.Analyzer spectrumAnalyzer;

    /**
     * 界面现在有没有人在看工作室页（{@code PlayerController.setStudioPageVisible} 传下来）。
     *
     * <p>表头与频谱都是<strong>为了看</strong>才算的数据：没人看的时候一帧都不该花
     * （4× 过采样的真峰 + 每块一次 FFT 在手机上不是零成本）。
     */
    private volatile boolean studioMonitoring;

    /** 频谱/电平发布失败只报一次（见 {@code publishSpectrum} 的 catch）。 */
    private boolean spectrumErrorLogged;

    /**
     * 编排器「自己」换过的那一首（自动混音的一次过渡收尾）。
     *
     * <p>它是一次性令牌：控制器随后为了推进自己的队列会 {@code play(同一首)}，
     * 那一次<strong>不能重启</strong>——重启会把已经过渡进来的那几秒（窗口长度）
     * 重放一遍。见 {@link #absorbHandedOverPlay}。
     */
    private volatile String handedOverSource;

    /** 实际协商到的输出配置（{@code null} = 还没开流）。 */
    private volatile OutputConfig outputConfig;

    /** 已协商采样率/声道数下，源格式判定是否丢信息（每首歌重算一次，见 {@link #publishFormat}）。 */
    private volatile boolean formatLosesInformation;

    /** HiFi 的设置值快照（诊断用；每块发布）。 */
    private volatile boolean hifiBitPerfect;

    // ==================================================================
    // 生产者线程独占的字段（发布只靠上面那些 volatile）
    // ==================================================================

    private volatile Thread producerThread;

    /** 当前会话。生产者线程独占。 */
    private Session session;

    private OutputHandle handle;
    private RingBuffer.Producer producer;
    private AudioBuf blockBuf;

    /** 交错暂存。开流时按声道数一次性分配，之后不再分配。 */
    private float[] interleaved;

    /**
     * 三段电平表：歌词动态背景这类「跟着音乐动」的画面读它。
     *
     * <p>在开流时按设备实际采样率建；每块算一次（两个一阶低通 + 三次乘加/样本，
     * 相对这一块的混音/重采样开销可以忽略），结果发布到 {@link SpectrumTap}。
     */
    private BandLevels bandLevels;

    /**
     * 工作室处理链（**主总线**那一台；每 deck 两台在 {@code Director} 里）。
     *
     * <p>主总线挂在这里：{@code pump} 出来之后、交错写环之前——也就是「你听到的最终结果」，
     * 于是电平表（{@link SpectrumTap}）看到的与听到的一致。
     */
    private dev.t1m3.qplayer.audio.studio.StudioProcessor studio;

    /** 已经生效的那一份参数（引用来比：设置实现缓存解析结果，没换就是同一个对象）。 */
    private dev.t1m3.qplayer.audio.studio.StudioRacks appliedStudioRacks;
    private final float[] bandScratch = new float[BandLevels.BANDS];

    /** 没推完的那一块：起点与长度（采样）。**绝不丢弃**，下一轮先把它推完。 */
    private int pendingOffset;
    private int pendingSamples;

    /** 已经下达给 deck 的音量（线性），避免每块都重设增益。 */
    private float appliedGain = -1.0f;

    /** 已经处理过的命令世代号（生产者线程用）。 */
    private long seenCommandEpoch;

    /** 会话内的一条曲。 */
    private static final class Session {

        final Director director;

        /** 建会话时的音源句柄（诊断用）。 */
        final String source;

        /** 上一次看到的当前曲（用来发现「编排器自己换曲了」）。 */
        String lastPath;

        /** 上一次看到的已产出帧数（换 deck 后它会回退，也是重新施加增益的判据）。 */
        long lastCurrentFrames;

        /** 上一块读到的**进场 deck**帧数（−1 = 还没读过）。用来判断"B 到底开始放了没有"。 */
        long lastNextFrames = -1L;

        /**
         * 进场曲**现在是不是真的在出声**（帧数在往前走）。
         *
         * <p>2026-10-07 用户拍板改的口径：过渡窗口里凡是"只有 A 在响"的那一段
         * （尾奏循环的「买时间」档有十几秒这种），界面必须**仍然显示 A** ✓ ——
         * 通知、位置、时长三样一起等 B 出声再切 ✓，免得出现"标题已经是 B、
         * 进度条却是另一个读数"的不一致 ✗（用户实测）。
         */
        boolean introSounding;

        /** 已经触发过 onStarted 了吗（每次 play 一次）。 */
        boolean startedFired;

        /** 已经自然播完（或失败）了。 */
        boolean finished;

        /**
         * 当前是不是在过渡窗口里。
         *
         * <p>只用来<strong>进出各记一条日志</strong>（用户能照着日志判断「刚才到底混了没有」）：
         * {@code pathState()} 每块都在算，但日志不能每块都写。
         */
        boolean inTransition;

        /** 进入过渡那一刻的已产出帧数——与收尾时的差值就是这次实际交叠的时长。 */
        long transitionEnterFrames;

        /** 已经报过几条 {@code skipped}（打不开的文件/分析失败），只报新增的那些。 */
        int skippedReported;

        /**
         * 造这个编排器时总开关是什么状态。
         *
         * <p>用来发现「用户中途拨了开关」：窗口是造编排器时定下的，不改就一直是旧的
         * （关了还在跑 / 开了不生效）。见 {@code handleArmNext}。
         */
        final boolean automixEnabledAtOpen;

        Session(Director director, String source, boolean automixEnabledAtOpen) {
            this.director = director;
            this.source = source;
            this.lastPath = source;
            this.lastCurrentFrames = director.currentFrames();
            this.automixEnabledAtOpen = automixEnabledAtOpen;
        }
    }

    // ==================================================================
    // 构造与宿主注入
    // ==================================================================

    /** 用系统默认输出设备（开流那一刻才去解析，所以 {@code OutputDevices.install} 之后调用即可）。 */
    public PlaybackEngine() {
    }

    /**
     * 用指定设备（测试与安卓宿主用）。
     *
     * @param device 输出设备，不能为 {@code null}
     */
    public PlaybackEngine(OutputDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("输出设备不能为 null");
        }
        this.device = device;
    }

    /** 指定输出设备。必须在第一次 {@link #play} 之前调用。 */
    public void setDevice(OutputDevice device) {
        this.device = device;
    }

    /**
     * 接上设置。必须在第一次 {@link #play} 之前调用（之后调用只会影响下一次造编排器）。
     *
     * <p>{@code null} 会被当成「读不到设置」的保守值。
     */
    public void setSettings(EngineSettings settings) {
        this.settings = settings == null ? AutomixSettings.defaults() : settings;
    }

    /** 环形缓冲目标时长（毫秒）。0 或负数按 {@link EngineFrames#DEFAULT_RING_MS}。 */
    public void setRingMs(int ms) {
        this.ringMs = ms > 0 ? ms : EngineFrames.DEFAULT_RING_MS;
    }

    /** 注入线程优先级（安卓用；桌面可以不管）。 */
    public void setThreadTuner(ThreadTuner tuner) {
        this.threadTuner = tuner;
    }

    // ==================================================================
    // AudioBackend
    // ==================================================================

    @Override
    public void play(String source, long startMs) {
        play(source, Collections.<String, String>emptyMap(), startMs);
    }

    @Override
    public void play(String source, Map<String, String> headers, long startMs) {
        if (source == null || source.isEmpty() || released) {
            return;
        }
        long target = Math.max(0L, startMs);
        // 立刻发布基线：界面在会话真正起来之前读到的是「新曲的位置」，
        // 而不是上一首的末尾位置（否则进度条会先显示旧值再跳）。
        positionMs = target;
        durationMs = 0L;
        currentSource = source;
        rememberHeaders(source, headers);
        playRequested = true;
        pausedIntent = false;
        enqueue(new Command(CMD_PLAY, source,
                headers == null ? Collections.<String, String>emptyMap() : headers, target));
    }

    @Override
    public void pause() {
        if (released) {
            return;
        }
        pausedIntent = true;
        // 暂停后引擎不再产块，电平会停在最后一个值上（画面继续跟着一段已经不存在的音乐抖）；
        // 直接归零，画面随即平静下来。环里剩下的那点声音照放，不影响听感。
        SpectrumTap.reset();
        resetStudioReadings();
        commandEpoch++;
    }

    /**
     * 控制器对一次自动交接的<strong>确认</strong>：引擎只认账，<strong>绝不重启播放</strong>。
     *
     * <h3>为什么必须有一条专门的命令（这是一个真 bug 的修法）</h3>
     *
     * <p>从前控制器在这里发的是 {@code play(进场曲, 0)}，靠 {@link #handedOverSource}
     * 那枚一次性令牌把它认掉（见 {@code absorbHandedOverPlay}）。而令牌是按<strong>句柄
     * 字符串逐字</strong>比对的，同一首歌的「直链」与「落地后的缓存文件」是两个字符串——
     * 对不上的那一次，引擎就把这句话当成一次**真正的换曲**：{@code endSession} + 重新开流，
     * 于是刚过渡进来的那首歌<strong>从 0 重放</strong>。用户实测原话：「过渡完成后歌曲进度
     * 会被重置回 0 从头播放」。
     *
     * <p>语义上本来也不该是 {@code play}：控制器这一刻<strong>不想播任何东西</strong>，
     * 它只是想告诉引擎「你报的那次交接我收到了、队列我已经推进过了」。所以这里给一条
     * 只做认账的命令，它没有「重开」这条路径——不管句柄对不对得上。
     *
     * <p>命令走与 {@code play} 同一条队列（生产者线程落地）：下面那些字段都是生产者线程
     * 独占的，在调用线程上直接改就是数据竞争。
     *
     * @param source 控制器认下来的那一首的音源句柄（只用于日志对账，不参与任何「开流」决策）
     */
    /**
     * 「重做这次过渡」（P4 控制室的第三个动作）：把已经跑完的准备结论作废、重新准备一次。
     *
     * <p>走命令通道：{@code Director.reprepare()} 要读改的是生产者线程独占的字段。
     * 它<strong>不打断正在进行的过渡</strong>（引擎那边会拒绝），所以这个调用是安全的。
     */
    public void reprepareTransition() {
        if (released) {
            return;
        }
        enqueue(new Command(CMD_REPREPARE, null, Collections.<String, String>emptyMap(), 0L));
    }

    @Override
    public void confirmHandoff(String source) {
        if (released) {
            return;
        }
        enqueue(new Command(CMD_CONFIRM_HANDOFF, source,
                Collections.<String, String>emptyMap(), 0L));
    }

    @Override
    public void resume() {
        if (released) {
            return;
        }
        // 「什么都没装」时是 no-op（接口契约）：没有会话、也没点过播放就什么都不做。
        if (!playRequested) {
            return;
        }
        pausedIntent = false;
        commandEpoch++;
    }

    @Override
    public boolean isPlaying() {
        return playRequested && !pausedIntent && sessionActive && !released;
    }

    @Override
    public void seek(long ms) {
        if (released) {
            return;
        }
        long target = Math.max(0L, ms);
        // 先发布目标值：生产者线程最坏要过一块（几十毫秒）才把它应用上去，
        // 中间这段时间界面若读到旧位置，进度条会在松手之后弹回去（看起来像 bug）。
        // 落点随后由生产者按**实际**位置覆盖（容器只能停在关键帧/包边界上）。
        positionMs = target;
        enqueue(new Command(CMD_SEEK, null, null, target));
    }

    @Override
    public long position() {
        return Math.max(0L, positionMs);
    }

    /**
     * **界面该用的"耳朵听到的位置"**（2026-10-09 ✓ 用户：「我不要面向结果式……应该直接修复」✓）。
     *
     * <p>为什么不改 {@link #position()}：它是**契约值**（已渲染位置 ✓
     * `PlaybackEngineTest` 钉着"seek 后立刻返回目标值"与"通知/位置/时长一起切"✗
     * 往它里面扣环会让判据红 ✓ 已试过两次 ✗）。所以这里**另开一条**给界面用的 API ✓：
     * 界面/歌词/进度读它 ⇒ 位置与耳朵对齐 ✓（差一个输出环 `ringMs` = 200 ms ✓），
     * 而 `positionMs` 与 MPRIS 保持原样 ✓ ⇒ 契约不动 ✓ 判据不红 ✓。
     */
    public long audiblePosition() {
        return Math.max(0L, position() - ringMs);
    }

    @Override
    public long duration() {
        return Math.max(0L, durationMs);
    }

    @Override
    public void setVolume(float v) {
        float clamped = Math.max(0f, Math.min(1f, v));
        volume = clamped;
    }

    @Override
    public void setOnComplete(Runnable callback) {
        this.onComplete = callback;
    }

    @Override
    public void setOnStarted(Runnable callback) {
        this.onStarted = callback;
    }

    @Override
    public void setOnPaused(Runnable callback) {
        this.onPaused = callback;
    }

    @Override
    public void setOnResumed(Runnable callback) {
        this.onResumed = callback;
    }

    @Override
    public void setOnError(Runnable callback) {
        this.onError = callback;
    }

    @Override
    public void setOnTransitionStart(java.util.function.Consumer<String> callback) {
        this.onTransitionStart = callback;
    }

    /**
     * 「音源句柄 → 它需要的请求头」。
     *
     * <p>为什么要在这里记：自动混音的分析（对拍、判调性、自动点位扫整曲）与进场 deck 的开流
     * 都是<strong>混音层自己去开解码器</strong>，而插件直链常常必须带 token/Referer。
     * 控制器在 {@code play}/{@code setNextSource} 时把那份头交给我们，这里存下来、
     * 由 {@code Director} 按句柄查回去。
     *
     * <p>有界：只留最近 {@value #MAX_REMEMBERED_HEADER_SETS} 条（队列与历史都是小窗口，
     * 留着整张表没有意义，还会跟着曲库长大）。
     */
    private final LinkedHashMap<String, Map<String, String>> headersBySource =
            new LinkedHashMap<String, Map<String, String>>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> eldest) {
                    return size() > MAX_REMEMBERED_HEADER_SETS;
                }
            };

    private static final int MAX_REMEMBERED_HEADER_SETS = 8;

    /** 记下一个句柄的请求头（空的不记：空表是默认值，记了只是浪费）。 */
    private void rememberHeaders(String source, Map<String, String> headers) {
        if (source == null || headers == null || headers.isEmpty()) {
            return;
        }
        headersBySource.put(source, Collections.unmodifiableMap(new HashMap<String, String>(headers)));
    }

    @Override
    public void setNextSource(String source, long startMs) {
        setNextSource(source, Collections.<String, String>emptyMap(), startMs);
    }

    @Override
    public void setNextSource(String source, Map<String, String> headers, long startMs) {
        if (source == null || source.isEmpty() || released) {
            clearNextSource();
            return;
        }
        rememberHeaders(source, headers);
        enqueue(new Command(CMD_ARM_NEXT, source,
                headers == null ? Collections.<String, String>emptyMap() : headers,
                Math.max(0L, startMs)));
    }

    @Override
    public void clearNextSource() {
        if (released) {
            return;
        }
        enqueue(new Command(CMD_CLEAR_NEXT, null, null, 0L));
    }

    @Override
    public void release() {
        if (released) {
            return;
        }
        released = true;
        playRequested = false;
        pausedIntent = true;
        commandEpoch++;
        Thread t = producerThread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(RELEASE_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 走到这里生产者线程要么已经退出，要么卡在一次不可打断的 IO 里（最多再跑一块）。
        // 两种情况都收尾：先把流关掉（不关就是持续出声），再把编排器丢掉。
        Session last = session;
        retireDirector(last == null ? null : last.director);
        session = null;
        sessionActive = false;
        OutputHandle h = handle;
        handle = null;
        producer = null;
        outputConfig = null;
        if (h != null) {
            h.close();
        }
    }

    // ==================================================================
    // 引擎附加的只读诊断
    // ==================================================================

    /** 实际协商到的输出配置；还没开流时为 {@code null}。 */
    public OutputConfig outputConfig() {
        return outputConfig;
    }

    /**
     * 当前时段的信号路径状态——<strong>界面那行字的唯一来源</strong>。
     *
     * <p>它已经是「链路判定 + 格式判定」两层合起来的结果（见类注释），
     * 界面不要另算一套。
     */
    public PathState pathState() {
        return pathState;
    }

    /** 格式判定的说明（例如「32 bit/48 kHz FLAC → 24 bit/48 kHz，位完美」）。 */
    public String formatSummary() {
        return formatSummary;
    }

    /** 当前（或最后）一首曲的音源句柄（接口方法，见 {@link AudioBackend#currentSource()}）。 */
    @Override
    public String currentSource() {
        return currentSource;
    }

    /** 正在用的过渡接法名。 */
    public String transitionName() {
        return transitionName;
    }

    /** 这次过渡的分析结论（界面显示用）。 */
    public String transitionNote() {
        return transitionNote;
    }

    /** 被跳过的音源及其原因（打不开的文件不该毁掉整张专辑，但要说清为什么）。 */
    public List<Director.Skipped> skipped() {
        Session s = session;
        if (s == null) {
            return Collections.emptyList();
        }
        return s.director.skipped();
    }

    /** HiFi「位完美直通」当前是否开着（诊断用）。 */
    public boolean hifiBitPerfect() {
        return hifiBitPerfect;
    }

    // ==================================================================
    // 控制侧：入队
    // ==================================================================

    private void enqueue(Command c) {
        synchronized (commandLock) {
            commands.addLast(c);
        }
        commandEpoch++;
        // 生产者线程是**懒起**的：没有命令时一个线程都不占。这里是唯一的入队口，
        // 所以启动点放在这里——漏掉它的话，命令永远躺在队列里没人抽，
        // 表现为「play() 之后什么都没发生」：不开流、不出声、也没有报错。
        ensureProducerThread();
    }

    private Command pollCommand() {
        synchronized (commandLock) {
            return commands.pollFirst();
        }
    }

    private void ensureProducerThread() {
        Thread t = producerThread;
        if (t != null && t.isAlive()) {
            return;
        }
        Thread fresh = new Thread(new Runnable() {
            @Override
            public void run() {
                producerLoop();
            }
        }, PRODUCER_THREAD_NAME);
        fresh.setDaemon(true);
        ThreadTuner tuner = threadTuner;
        if (tuner != null) {
            try {
                tuner.tune(fresh);
            } catch (Throwable e) {
                // 宿主注入的优先级设置失败不该毁掉播放：它是优化，不是前提
                Logger.warn("[qplayer-engine] 线程优先级注入失败：{}", String.valueOf(e));
            }
        }
        producerThread = fresh;
        fresh.start();
    }

    // ==================================================================
    // 生产者线程
    // ==================================================================

    private void producerLoop() {
        while (!released) {
            try {
                drainCommands();
                Session s = session;
                if (s == null || s.finished || pausedIntent || !playRequested) {
                    sleepQuietly(IDLE_SLEEP_MS);
                    continue;
                }
                produceBlock(s);
            } catch (Throwable e) {
                // 生产者线程绝不能因为一次异常就悄悄死掉：那会让播放永远停住、
                // 而且没有任何声音上的迹象。如实报出去，并让会话结束。
                //
                // **必须带栈**：只打 `e`（toString）的话日志里只有异常类名，
                // 事后完全没法定位（2026-09-26 收到过一条「生产者线程异常：
                // java.lang.ArrayIndexOutOfBoundsException」，没有栈就只能靠猜）。
                Logger.error("[qplayer-engine] 生产者线程异常：" + stackOf(e));
                failSession("引擎内部错误：" + e);
            }
        }
        // 退出前把手上没推完的余量丢掉（会话已经结束/被释放，没人再听它）
        pendingSamples = 0;
        pendingOffset = 0;
    }

    /**
     * 把命令队列抽干。<strong>所有会碰 {@code Director} 的动作都在这里发生。</strong>
     *
     * <p>一条 PLAY 会让一次路（{@link Command#armWaited}）：控制器是 {@code play()}
     * 之后紧接着 {@code setNextSource()}，而后者正是自动混音的前提。让后面的
     * ARM 先落地，这一趟造编排器时就能把「当前曲 + 队首」一起交出去——
     * 否则每首开头都要白白多开一次文件、多定位一次（重建，见
     * {@link #rebuildSession}）。只让一次，所以不会把播放无限推迟。
     */
    private void drainCommands() {
        Command c;
        Command deferredPlay = null;
        while ((c = pollCommand()) != null) {
            if (c.kind == CMD_PLAY && !c.armWaited && hasPendingCommands()) {
                c.armWaited = true;
                deferredPlay = c;
                continue;
            }
            if (deferredPlay != null) {
                // 队列里已经有更新的一条 PLAY：先把让路的那条按序做掉，再来新的
                applyCommand(deferredPlay);
                deferredPlay = null;
            }
            applyCommand(c);
        }
        if (deferredPlay != null) {
            applyCommand(deferredPlay);
        }
        seenCommandEpoch = commandEpoch;
        // 音量与设置每轮对一次账（setVolume 一秒钟可能来六十次，所以它不走命令队列）
        applyGain(false);
    }

    private boolean hasPendingCommands() {
        synchronized (commandLock) {
            return !commands.isEmpty();
        }
    }

    // ==================================================================
    // 命令落地探针（Director.heavyProbe 的先例）
    // ==================================================================

    /**
     * 命令落地探针：生产者线程每应用完一条结构性命令就往这里追加一个名字。
     *
     * <p>{@code null} = 关闭（生产路径上只剩一次判空）。为什么需要它：
     * 环形缓冲的丢弃是<strong>消费者侧</strong>做的（见
     * {@link RingBuffer.Producer#flush()}），所以「seek 之后环里还有没有旧内容」
     * 这件事只有在「seek 已经落到生产者线程上」之后才可判定；而
     * {@link #seek(long)} 一进函数就把位置乐观地发布出去了，
     * {@link #position()} 因此区分不出「命令已应用」与「命令还在队列里」。
     * 测试没有第二条路能拿到这个确定信号，所以按 {@code Director.heavyProbe}
     * 的同一理由补一处<strong>包私有</strong>钩子：上游的 {@code crate::probe}
     * 是 {@code cfg(test)} 专用的，Java 没有那个机制，只能靠可见性把它挡在公开 API 之外。
     */
    static List<String> commandProbe;

    /** 记一笔「这条命令已经落到生产者线程上」。{@code commandProbe == null} 时什么都不做。 */
    private static void recordApplied(String kind) {
        List<String> probe = commandProbe;
        if (probe == null) {
            return;
        }
        synchronized (probe) {
            probe.add(kind);
        }
    }

    private void applyCommand(Command c) {
        try {
            switch (c.kind) {
                case CMD_PLAY:
                    handlePlay(c);
                    recordApplied("play");
                    break;
                case CMD_SEEK:
                    handleSeek(c.ms);
                    recordApplied("seek");
                    break;
                case CMD_ARM_NEXT:
                    handleArmNext(c.source, c.headers, c.ms);
                    recordApplied("arm");
                    break;
                case CMD_CONFIRM_HANDOFF:
                    handleConfirmHandoff(c.source);
                    recordApplied("confirmHandoff");
                    break;
                case CMD_REPREPARE:
                    handleReprepare();
                    recordApplied("reprepare");
                    break;
                default:
                    handleClearNext();
                    recordApplied("clear");
                    break;
            }
        } catch (Throwable e) {
            Logger.error("[qplayer-engine] 命令处理失败：" + e);
            failSession(String.valueOf(e.getMessage()));
        }
    }

    /**
     * 开一次播放。
     *
     * <p>先看这是不是「控制器对自动交接的追认」（见 {@link #absorbHandedOverPlay}）：
     * 是的话<strong>什么都不重启</strong>。否则就是一次真正的换曲：清掉环里上一首的
     * 尾巴，重开一个编排器。
     */
    private void handlePlay(Command c) {
        if (absorbHandedOverPlay(c.source)) {
            return;
        }
        // 控制器**直接切过来**的一次换曲（手动点下一首/点播/换队列）：上一首还在放，
        // 而这一次 play 会重建整个会话——过渡来不及做（准备要在曲终前 30 秒以上动手，
        // 见 Director.PREPARE_MARGIN_SECS），于是这一次一定是硬接/交叉淡化那种
        // 「当场接上去」的走法。如实记一条：用户最容易把「手动切歌没有过渡」
        // 当成「自动混音坏了」，而这是设计如此。
        Session previous = session;
        if (previous != null && !previous.finished && settings.automixEnabled()) {
            Logger.info("[qplayer-engine] 自动混音：这次换曲是控制器直接切过来的"
                            + "（手动切歌/点播）→ 不经过渡。这是设计如此、不是失败："
                            + "过渡要在曲终前 {} s 以上就开始准备（对拍/判调性），"
                            + "手动切歌没有那段时间 · {} → {}",
                    Long.valueOf(Director.PREPARE_MARGIN_SECS),
                    previous.director.currentPath(), c.source);
        }
        // 换曲必须清环：环里压着上一首的尾巴，漏进新曲的开头就是
        // 「切过去了又弹回来」（同 seek 的理由）。
        endSession(true);
        pausedIntent = false;
        playRequested = true;
        hifiBitPerfect = settings.bitPerfect();
        try {
            ensureOutput(c.source, c.headers);
        } catch (AudioException e) {
            failSession("打开输出失败：" + e.getMessage());
            return;
        }
        // 「预告的下一首」是控制器在 play() 之后紧接着给的（同一条调用栈）。
        // 生产者一次把命令队列抽干，所以这里通常已经拿到了——这才有「一次就把
        // 当前曲 + 队首安排对」的可能，不必先开一个只有当前曲的编排器再重建。
        String next = armedSource;
        long nextStart = armedStartMs;
        if (!openDirector(c.source, EngineFrames.framesOfMs(c.ms, rate()), next, nextStart, false)) {
            return;
        }
        publishSnapshot(session);
    }

    /**
     * 跳转。
     *
     * <p>三件事缺一不可：{@code Director.seek}（deck 与解码器一起定位）、
     * <strong>清空环形缓冲</strong>（见类注释）、以及丢掉手上没推完的那一块
     * （它是跳转<strong>之前</strong>产出的音频）。
     */
    private void handleSeek(long ms) {
        Session s = session;
        if (s == null || s.finished) {
            positionMs = ms;
            return;
        }
        double actual;
        try {
            actual = s.director.seek((double) ms / MS_PER_SEC);
        } catch (AudioException e) {
            failSession("跳转失败：" + e.getMessage());
            return;
        }
        // 没推完的余量作废：它是旧位置的音频
        pendingSamples = 0;
        pendingOffset = 0;
        flushRing();
        // 定位可能顺带结束了过渡（用户拖了进度条就不该继续混音），
        // 所以这里同步一次「当前曲」，免得把收尾当成一次自动交接。
        s.lastPath = s.director.currentPath();
        s.lastCurrentFrames = s.director.currentFrames();
        currentSource = s.lastPath;
        publishFormat(s);
        // 进度按**实际落点**记账，不是请求值——否则界面上的进度条会在松手后跳回去
        // **扣掉输出环形缓冲**（2026-10-09 ✓ 用户：「过渡时界面跳到 B、歌词和实际进度不一致
        // 这个就是为什么结束后会 seek」✓）：`lastCurrentFrames` 是**已渲染**位置 ✓，
        // 而耳朵听到的要再晚一个环（`ringMs`=200 ms ✓ 日志 `ring 9600 frames` ✓）。
        // 不扣的话：界面/歌词比听到的 B 超前 200–400 ms ✗，交接时这点超前被抹掉 ⇒ 显示回跳 ✓
        // ⇒ 只在这一层扣（不重复扣 ✗），过渡中与交接后用同一个式子 ✓。
        positionMs = audiblePositionMs(s.lastCurrentFrames);
    }

    /**
     * 预告下一首（{@link AudioBackend#setNextSource}）。
     *
     * <p>两条路：
     *
     * <ul>
     *   <li>还没会话（或队首已经是这一首）：只记下来，下一次 {@link #handlePlay}
     *       开编排器时带上——<strong>这是最理想的一条</strong>，
     *       一次开文件就把「当前曲 + 队首」安排对。</li>
     *   <li>会话已经在跑：{@code Director} 的队列在构造时就定下了，而 mix 层
     *       不允许改（没有 enqueue），所以只能<strong>重建</strong>一个编排器
     *       （见 {@link #rebuildSession}）。重建<strong>不清环</strong>——
     *       环里那段正好接在新 deck 的起点之前，是连续的。</li>
     * </ul>
     *
     * <p>预告的就是<strong>当前</strong>这一首时也会重建：那是「单曲循环」，
     * 编排器会把同一首当作进场曲，于是句尾能无缝接回句首。
     *
     * <p>只接受 {@code startMs == 0}：进场 deck 的起点由对拍结论决定
     * （{@code TransitionPlan.skipSecs}），引擎这里没法另行指定。
     */
    private void handleArmNext(String source, Map<String, String> headers, long startMs) {
        armedSource = source;
        armedStartMs = startMs;
        rememberHeaders(source, headers);
        Session s = session;
        if (s == null || s.finished || source == null) {
            Logger.debug("[qplayer-engine] 自动混音：收到预告，下一首 {}（还没有会话，等下一次 play 一起交给编排器）",
                    source);
            return;
        }
        // **开关改过就必须重建**：过渡窗口是造编排器时按当时的设置定下的，
        // 不重建的话，用户拨下总开关之后当前这一曲照样会交叠（「关了还在跑」）。
        // 这一条也覆盖「从关到开」：打开之后当前这一曲的结尾就能开始准备过渡了。
        boolean switchChanged = s.automixEnabledAtOpen != settings.automixEnabled();
        if (source.equals(s.director.queuedNextPath()) && !switchChanged) {
            // 队首已经是它了：什么都不用做（重复预告是常态）
            Logger.debug("[qplayer-engine] 自动混音：收到预告，下一首 {}（队首已经是它）", source);
            return;
        }
        if (switchChanged) {
            Logger.info("[qplayer-engine] 自动混音：总开关{} → 重建编排器，让新的接法立即生效",
                    settings.automixEnabled() ? "打开" : "关闭");
            rebuildSession(source, startMs);
            return;
        }
        // **首选：只换队首，不重建**。重建要「重新打开当前曲并定位」——本地文件一两毫秒，
        // 网络流上几百毫秒到几秒；这期间一个样本都产不出来，环被抽干、补静音，用户听到的
        // 就是「正常播放期间时不时卡一下」，歌词进度也跟着顿（2026-09-20 实测）。
        // 而重新预告要换的只是「接下来接哪一首」，根本不必碰当前曲。
        if (s.director.replaceQueued(source)) {
            Logger.debug("[qplayer-engine] 自动混音：收到预告，下一首 {}（只换队首，不重建）", source);
            return;
        }
        // 换不了：过渡正在进行，那一刻队首正在被交接（见 Director.replaceQueued）。
        // **此刻绝不能重建**——重建会以「当前曲」为准重新开一个编排器，而那还是出场曲，
        // 于是这次过渡连同进场曲一起被丢掉。记下来，等交接完再换。
        pendingArmSource = source;
        pendingArmStartMs = startMs;
        Logger.debug("[qplayer-engine] 自动混音：过渡正在进行 → 预告先记下（交接完再换队首）：{}", source);
    }

    /** 过渡期间记下的预告（交接完成后落到队首上，见 {@link #applyPendingArmAfterHandoff}）。 */
    private String pendingArmSource;

    private long pendingArmStartMs;

    /** 交接完成之后，把过渡期间记下的预告落到队首上（生产者线程）。 */
    private void applyPendingArmAfterHandoff(Session s) {
        String pending = pendingArmSource;
        if (pending == null) {
            return;
        }
        pendingArmSource = null;
        if (s.director.replaceQueued(pending)) {
            Logger.debug("[qplayer-engine] 自动混音：交接完成，把过渡期间记下的预告落到队首：{}", pending);
        } else {
            Logger.warn("[qplayer-engine] 自动混音：交接之后仍然换不了队首（{}）→ "
                    + "这一轮没有预告，曲终由控制器换曲", pending);
        }
    }

    /** 「重做这次过渡」的落地（生产者线程）。过渡进行中时如实拒绝并记一条。 */
    private void handleReprepare() {
        Session s = session;
        if (s == null || s.finished) {
            return;
        }
        if (s.director.reprepare()) {
            Logger.info("[qplayer-engine] 控制室：已作废这一轮的准备结论，接下来重新准备一次");
        } else {
            Logger.debug("[qplayer-engine] 控制室：过渡正在进行（或没有准备过）→ 这次不重做"
                    + "（撕开正在混的那几秒比点位晚一拍糟得多）");
        }
    }

    private void handleClearNext() {
        if (armedSource != null) {
            Logger.debug("[qplayer-engine] 自动混音：预告被撤销（控制器说没有可用的下一首）"
                    + " → 曲终由控制器换曲，接缝上会有一小段空隙");
        }
        armedSource = null;
        armedStartMs = 0L;
    }

    /** 已预告的下一首（生产者线程独占）。 */
    private String armedSource;

    private long armedStartMs;

    /**
     * 确认「控制器这一次 play 只是在追认自动交接」。
     *
     * <p>自动混音的一次过渡收尾时，编排器已经把进场曲提上位、它<strong>正在出声</strong>
     * （过渡窗口里混进来的那几秒也算）。引擎随即触发 {@code onComplete} 让控制器推进
     * 它的队列，而控制器推进的方式正是 {@code play(下一首, 0)}——若照字面重开，
     * 那几秒会被重放一遍，接缝上凭空多出一次重复。所以这一次认掉。
     *
     * <p><strong>令牌窗口覆盖「过渡开始 → 交接完成」这整段</strong>：过渡一开始
     * （{@link #reportTransitionEntry}）令牌就落在进场曲上，而下面除了「已经上位」
     * 还认「正在进场」（{@code s.inTransition} 且 token == {@code director.nextPath()}）。
     * 为什么必须这么宽：窗口最长 16 s（表现模式），而「控制器在窗口开头就推进队列」
     * 是这次交付之后真实存在的走法（{@code onTransitionStart}）。那一次 play 若不认，
     * 就会 {@code endSession} 重开会话——把已经混进来的几秒重放一遍、把交叉淡化从中间
     * 撕开，比界面滞后糟得多。
     *
     * <p>令牌是<strong>一次性</strong>的：任何一次 {@code play} 都会把它消费掉，
     * 所以「用户再点一次同一首歌要重头放」最多只可能被吃掉一次，而且只在过渡窗口里
     * 或自动交接的下一瞬间——这是有意的取舍：在窗口里把进场曲重头放一遍是听得出来的
     * 破损，而一次点击被吃掉只是「没反应」。
     */
    private boolean absorbHandedOverPlay(String source) {
        String token = handedOverSource;
        handedOverSource = null;
        Session s = session;
        if (token == null || s == null || s.finished || !token.equals(source)) {
            return false;
        }
        if (!token.equals(s.director.currentPath()) && !isEntering(s, token)) {
            return false;
        }
        pausedIntent = false;
        playRequested = true;
        sessionActive = true;
        applyGain(true);
        publishSnapshot(s);
        fire(onStarted);
        return true;
    }

    /**
     * {@link #confirmHandoff(String)} 命令的落地（生产者线程）。
     *
     * <p>与 {@link #absorbHandedOverPlay} 的分工：那个回答的是「控制器这一次
     * {@code play} 要不要重启」，所以要逐字核对句柄，对不上就照字面重开；
     * 这个回答的是「控制器已经认下这次交接了」，<strong>只认账</strong>——
     * 句柄对不对得上都不重启，因为控制器压根没要求播任何东西。
     *
     * <p>令牌无论对不对得上都消费掉：一次交接只会被确认一次，留着它只会让用户
     * 下一次点这一首被吃掉（那是个静默的、让人以为「点了没反应」的缺陷）。
     * 对不上就如实记一条——那是「同一首歌的另一个句柄」这类差异的唯一痕迹。
     */
    private void handleConfirmHandoff(String source) {
        String token = handedOverSource;
        handedOverSource = null;
        Session s = session;
        if (s == null || s.finished) {
            // 会话已经结束（用户在交接的下一瞬间按了停止/换了曲）：什么都不做，
            // 更不许为了「确认」去开一次流。
            return;
        }
        if (token == null || !token.equals(source)) {
            Logger.info("[qplayer-engine] 自动混音：交接确认的句柄与令牌不同（{} ≠ {}）→ "
                            + "只认账，不重开放（这正是「过渡完成后进度被重置回 0」的成因）",
                    String.valueOf(source), String.valueOf(token));
        }
        pausedIntent = false;
        playRequested = true;
        sessionActive = true;
        applyGain(true);
        publishSnapshot(s);
        fire(onStarted);
    }

    /** 这一次 play 的目标是不是「过渡里正在进场的这一首」（还没上位）。 */
    private static boolean isEntering(Session s, String source) {
        return s.inTransition && source.equals(s.director.nextPath());
    }

    // ==================================================================
    // 会话生命周期
    // ==================================================================

    /**
     * 开流。整场播放<strong>只开一次</strong>（ADR-003 §2：一条永不中断的输出流是无缝的前提），
     * 之后换曲、过渡、跳转都不关它。
     *
     * <p>采样率的选择：
     *
     * <ul>
     *   <li>HiFi「位完美直通」开着 → 按<strong>源采样率</strong>请求（并带上源位深）。
     *       要知道源采样率只能先把解码器打开探一次（探完就关）——上游
     *       {@code Session::start} 也是这么做的。选不到就报错，<strong>不静默降级</strong>：
     *       悄悄退回设备默认配置，用户以为开着 HiFi 而实际没有，是最坏的结果。</li>
     *   <li>没开 → 用设备默认配置（上游 {@code OutputHandle::open_default}）。
     *       之后每一首各自的采样率由 deck 的重采样器对齐到这条流的采样率，
     *       补不上来的那点差异由 {@link #pathState()} 如实报出来。</li>
     * </ul>
     *
     * <p>HiFi L3「随曲切换采样率」<strong>本版没实现</strong>：默认是无缝优先
     * （见 docs/audio-engine.md §6），跨采样率的另一首走我们自己的重采样器。
     */
    private void ensureOutput(String source, Map<String, String> headers) {
        if (handle != null) {
            return;
        }
        OutputDevice dev = device;
        if (dev == null) {
            dev = OutputDevices.defaultDevice();
        }
        if (dev == null) {
            throw AudioException.output("没有可用的输出设备");
        }

        boolean hifi = settings.bitPerfect();
        hifiBitPerfect = hifi;
        if (!headers.isEmpty()) {
            // 请求头在混音层没有通道（Deck.open / Director 都只收句柄），如实记一条警告，
            // 而不是假装传下去了——插件直链缺请求头会打不开，日志里必须能看出原因。
            Logger.warn("[qplayer-engine] 音源请求头无法传递到解码层（{} 项）："
                    + "Deck/Director 没有 headers 通路，网络直链可能打不开", headers.size());
        }

        OutputHandle opened;
        if (hifi) {
            int[] probed = probeSourceRate(source, headers);
            int srcRate = probed[0];
            Integer srcBits = probed[1] < 0 ? null : Integer.valueOf(probed[1]);
            OutputRequest req = OutputRequest.atRate(srcRate,
                    EngineFrames.ringFramesFor(srcRate, ringMs));
            if (srcBits != null) {
                req = req.withBitDepth(BitDepth.fromBits(srcBits.intValue()));
            }
            opened = dev.openWithRequest(req);
        } else {
            opened = dev.open(EngineFrames.ringFramesFor(48_000, ringMs));
        }

        RingBuffer.Producer p = opened.takeProducer();
        if (p == null) {
            opened.close();
            throw AudioException.output("输出流的写入端已经被取走");
        }
        OutputConfig config = opened.config();
        handle = opened;
        producer = p;
        outputConfig = config;
        blockBuf = new AudioBuf(ChannelLayout.of(config.channels()), EngineFrames.BLOCK_FRAMES);
        interleaved = new float[EngineFrames.BLOCK_FRAMES * config.channels()];
        bandLevels = new BandLevels(config.sampleRate());
        studio = new dev.t1m3.qplayer.audio.studio.StudioProcessor(config.sampleRate(),
                config.channels());
        // 表头与频谱分析器**必须在这里建**（按输出配置的采样率与声道数）：
        // 它们以前只是两个声明出来、从来没被赋值的字段——于是「表头」永远显示 -120、
        // 「实时频谱」永远空着，而页面看起来一切正常（探针用了假后端才没暴露）。
        // 判据：PlaybackEngineTest.theStudioMetersAndSpectrumPublishWhatYouHear
        studioMeters = new dev.t1m3.qplayer.audio.studio.StudioMeters(config.sampleRate(),
                config.channels());
        spectrumAnalyzer = new SpectrumBands.Analyzer(config.sampleRate());
        SpectrumBands.reset();
        spectrumErrorLogged = false;
        appliedStudioRacks = null;
        Logger.info("[qplayer-engine] 输出已开：{}（HiFi={}，环 {} 帧）",
                config, Boolean.valueOf(hifi), Integer.valueOf(config.ringFrames()));
    }

    /** {@link #probeSourceRate} 的产出：{@code [0]} = 采样率，{@code [1]} = 位深（-1 = 未知）。 */
    private static int[] probeSourceRate(String source, Map<String, String> headers) {
        Decoder probe = null;
        try {
            probe = Decoder.open(source, headers);
            TrackSpec spec = probe.spec();
            int bits = spec.bitsPerSample == null ? -1 : spec.bitsPerSample.intValue();
            return new int[]{spec.sampleRate, bits};
        } finally {
            if (probe != null) {
                try {
                    probe.close();
                } catch (RuntimeException ignored) {
                    // 探测用的句柄关不掉不该影响开流
                }
            }
        }
    }

    /**
     * 开流/重建会话失败时的收尾。
     *
     * <p><strong>重建失败绝不能杀掉正在播的会话。</strong> 那件事的后果是灾难性的：
     * 用户正在听的这一首会被判死，控制器于是重试或跳到下一首——听感就是
     * 「刚切过去放了几秒又跳了」（2026-09-20 安卓实机报告）。而重建只是想给「下一首」
     * 补一份预告：补不上顶多是接缝上多一小段空隙（曲终由控制器换曲），
     * 没有任何理由把正在放的东西一起干掉。
     *
     * <p>所以重建那条路只记一条日志然后返回 {@code false}：老会话照旧在放
     * （它的 {@code Director} 一个字都没被碰），只是这一轮没有预告的下一首。
     */
    private boolean failOpen(boolean rebuilding, String why) {
        if (rebuilding) {
            Logger.warn("[qplayer-engine] 重建会话失败（继续放当前这一首，只是这一轮没有预告下一首）：{}"
                    + " → 曲终由控制器换曲，接缝上会有一小段空隙", why);
            return false;
        }
        failSession(why);
        return false;
    }

    /**
     * 造一个编排器（一次会话）。
     *
     * @param source      当前曲
     * @param atSecs      起始位置（秒）；0 = 从头
     * @param nextSource  已预告的下一首（可为 {@code null}）
     * @param rebuilding  {@code true} = 这是「重新预告下一首」触发的<strong>重建</strong>，
     *                    失败时<strong>绝不能杀掉正在播的会话</strong>（见下）
     * @return 成功与否；失败时若是首次开流则已经触发过 {@code onError}
     */
    private boolean openDirector(String source, double atSecs, String nextSource, long nextStartMs,
                                 boolean rebuilding) {
        OutputConfig config = outputConfig;
        if (config == null || producer == null) {
            return failOpen(rebuilding, "输出还没开");
        }
        int rate = config.sampleRate();
        int channels = config.channels();

        List<String> tracks = new ArrayList<String>(2);
        tracks.add(source);
        if (nextSource != null && !nextSource.isEmpty() && nextStartMs == 0L) {
            // 一次就把「当前曲 + 队首」交给编排器：它才会按「剩余 ≤ 窗口 + 30 秒」
            // 自己去异步 prepare（对拍、判调性）。**这正是预告下一首的全部意义**。
            tracks.add(nextSource);
        }

        Director d;
        try {
            d = new Director(tracks, rate, channels, settings.transitionFactory(rate, channels));
            d.setStudio(studio);
            // 准备阶段要扫整曲的档（自动点位 / AI 自动档）需要更长的提前量，
            // 见 EngineSettings#prepareMarginSecs
            d.setPrepareMarginSecs(settings.prepareMarginSecs());
            // 混音层要按句柄查请求头（它自己开解码器做分析/开进场流）：见 headersBySource
            d.setHeadersLookup(new Director.HeadersLookup() {
                @Override
                public Map<String, String> headersFor(String source) {
                    Map<String, String> h = headersBySource.get(source);
                    return h == null ? Collections.<String, String>emptyMap() : h;
                }
            });
        } catch (AudioException e) {
            return failOpen(rebuilding, "打开音源失败：" + e.getMessage());
        }
        if (atSecs > 0.0) {
            try {
                d.seek(atSecs);
            } catch (AudioException e) {
                // 定位失败不该让整首歌播不出来：从头开始，并如实记一条
                Logger.warn("[qplayer-engine] 起始定位失败，改从头播放：{}", String.valueOf(e.getMessage()));
            }
        }
        session = new Session(d, source, settings.automixEnabled());
        sessionActive = true;
        appliedGain = -1.0f;   // 新 deck 的增益是单位值，必须重新施加
        applyGain(true);
        publishFormat(session);
        reportAutomixPlan(session, source, nextSource);
        return true;
    }

    /**
     * 开一次会话之后，把「这一次换曲会不会做过渡」如实说清。
     *
     * <p>为什么这条日志是必须的（而不是「调试用的」）：整条自动混音的失败方式都是
     * <strong>静默</strong>的——没有下一首、窗口为 0、总开关关着，三种情况在那几秒里
     * 都是「什么都没有发生」。用户能拿到的唯一证据是听感，而听感只能得出
     * 「自动混音没效果」，看不出是哪一环没成。所以会话一开就把结论写进日志：
     * 会不会过渡、用什么接法、窗口多长。
     */
    private void reportAutomixPlan(Session s, String source, String nextSource) {
        double window = s.director.windowSecs();
        if (!settings.automixEnabled()) {
            Logger.info("[qplayer-engine] 自动混音：总开关关闭 → 本次会话不做过渡"
                            + "（窗口 0，一次 prepare 都不跑）· 换曲点仍由引擎无缝拼接"
                            + "（同一路输出流，不断流、不交叠）· 当前曲 {}",
                    source);
            return;
        }
        if (window <= 0.0) {
            Logger.info("[qplayer-engine] 自动混音：接法「{}」的窗口是 0 → 不做过渡"
                            + "（无缝拼接就是硬接，没有任何交叠）· 当前曲 {}",
                    s.director.transitionName(), source);
            return;
        }
        if (nextSource == null || nextSource.isEmpty()) {
            Logger.info("[qplayer-engine] 自动混音：这一曲没有一个已经拿到句柄的下一首"
                            + "（窗口 {} s 的「{}」备而不用）→ 曲终只能硬接。"
                            + "下一首要到控制器把它预告过来（见 AudioBackend#setNextSource）才会生效",
                    fmtSecs(window), s.director.transitionName());
            return;
        }
        // **分析时机与预算都要报准**（2026-10-02 改）。
        //
        // 原先这一行写的是「将在剩余 ≤ 窗口 + N s 时后台分析」——那时准备确实是按那条
        // 时间闸门起跑的。现在改成**一收到预告就开工**（用户要求：「不要留到最后再开始」，
        // 见 {@code Director.shouldPrepare}），所以这一行改成报「现在就开始」+
        // **这一档的预算**（最坏要跑多久，手机上进场曲是直链时就是它）。
        Logger.debug("[qplayer-engine] 自动混音：编排器已带上下一首 {}，接法「{}」，窗口 {} s，"
                        + "**收到预告就开始**后台分析（这一档预算 {} s{}）",
                nextSource, s.director.transitionName(), fmtSecs(window),
                Long.valueOf(s.director.prepareMarginSecs()),
                Director.isRemoteSource(nextSource)
                        ? "，下一首还在远端 → 预算抬到 " + Director.REMOTE_PREPARE_MARGIN_SECS
                                + " s 这一档" : "");
    }

    /** 一位小数的秒（日志用；{@code String.format} 每次都会分配，只在日志路径上用它）。 */
    private static String fmtSecs(double secs) {
        return String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(secs));
    }

    /**
     * 重建编排器：把队首换成新预告的那一首。
     *
     * <p>为什么要重建：{@code Director} 的队列在构造时就定下了，而 mix 层这次不许改
     * （没有 enqueue）。重建的代价必须说清——它<strong>重新打开当前曲并定位</strong>，
     * 所以：
     *
     * <ul>
     *   <li><strong>不清环</strong>。环里压着的是「已经产出、还没放出去」的那一段，
     *       它正好接在新 deck 的起点之前；清了就是凭空一个 200 ms 的洞。</li>
     *   <li>位置按<strong>已产出帧数</strong>对齐（不是「已经放出去的帧数」）——
     *       环里那段还要放，新 deck 必须从它之后接着产。</li>
     *   <li>手上没推完的那一块<strong>留着</strong>：它比环里的内容更靠后，
     *       下一轮先推它，顺序才对。</li>
     * </ul>
     *
     * <p>解码器的定位粒度（FLAC 落在帧首、MP3 要扫帧头）会让落点略早于请求值，
     * 于是接缝上可能出现几毫秒的重复——与一次普通跳转的代价同级，如实记在注释里。
     */
    private void rebuildSession(String nextSource, long nextStartMs) {
        Session old = session;
        if (old == null || old.finished) {
            return;
        }
        // 用**当前正在放的那一首**，不是会话起始的那一首：自动交接之后
        // 编排器已经把进场曲提上来了（session.source 是开会话时的那一首），
        // 照它重建会把上一首从当前这一首的位置上再接一遍。
        String current = old.director.currentPath();
        double resumeSecs = EngineFrames.secsOfFrames(old.lastCurrentFrames, rate());
        Director previous = old.director;
        if (!openDirector(current, resumeSecs, nextSource, nextStartMs, true)) {
            return;
        }
        retireDirector(previous);
        Logger.info("[qplayer-engine] 已按预告重建编排器：下一首 {}，接缝位置 {} ms",
                nextSource, Long.valueOf(EngineFrames.msOfFrames(old.lastCurrentFrames, rate())));
    }

    /** 结束当前会话。{@code flush} = 环里的残留要不要丢掉（换曲/释放要，曲终不要）。 */
    private void endSession(boolean flush) {
        Session s = session;
        session = null;
        sessionActive = false;
        pendingSamples = 0;
        pendingOffset = 0;
        if (s != null) {
            retireDirector(s.director);
        }
        if (flush) {
            flushRing();
        }
    }

    /**
     * 丢掉一个编排器前的收尾。
     *
     * <p>{@code Director} 没有 {@code close()}（mix 层不在本次改动范围内），而它手上
     * 「已就绪、还没安装」的那份准备结果里有一个<strong>已经打开好</strong>的 deck
     * （可能是一路网络连接）。上游靠 Rust 的 {@code Drop} 连它一起丢掉，
     * Java 只能绕一下：{@code seek()} 会走 {@code resetPreparation()}，
     * 把那份结果显式还回去（见 {@code Director.resetPreparation} 的注释）。
     *
     * <p><strong>正在出声的那个 deck 关不掉</strong>：那是 {@code Director} 缺
     * {@code close()} 的直接后果，已在交付报告里说明。能做的只有不在换曲时
     * 白白多留一份。
     */
    private static void retireDirector(Director d) {
        if (d == null) {
            return;
        }
        try {
            d.seek(0.0);
        } catch (RuntimeException e) {
            // 收尾都失败不该把换曲搞失败：这里只求尽力
            Logger.warn("[qplayer-engine] 编排器收尾失败：{}", String.valueOf(e.getMessage()));
        }
    }

    /** 让环形缓冲丢掉「跳转/换曲之前」产出的残留（生产者的唯一安全做法见 RingBuffer.Producer#flush）。 */
    private void flushRing() {
        RingBuffer.Producer p = producer;
        if (p != null) {
            p.flush();
        }
    }

    // ==================================================================
    // 生产
    // ==================================================================

    private void produceBlock(Session s) {
        // 0) 先把上一轮没推完的余量推完：**一帧都不许丢**（丢弃会让位置记账与听感错位）。
        if (!pushPending()) {
            return;
        }
        // 1) 渲染一块
        int n;
        try {
            n = s.director.pump(blockBuf);
        } catch (AudioException e) {
            failSession("渲染失败：" + e.getMessage());
            return;
        }
        if (n == 0) {
            // **pump 返回 0 不代表队列播完。**
            // 过渡窗口里可能出现「这一块两路都交付不出帧」（拍点剪切的切点落在出场曲结束之后、
            // 或窗口比曲子还长），而 Director 内部已经按上游收尾并重试过一次。
            // 只有「队列也空了」才是真的播完。
            if (s.director.queued() == 0) {
                finishSession(s);
            } else {
                // 还在等准备结果 / 编排器推迟了这次过渡：曲子其实没完，下一轮再问
                sleepQuietly(STALL_SLEEP_MS);
            }
            return;
        }
        // 1.5) **主总线**：工作室的最后一道（前级/EQ/平衡/响度补偿）。
        //      放在电平表之前，所以表头看到的就是听到的。
        reconcileStudio();
        if (studio != null) {
            studio.processMaster(blockBuf);
        }
        // 1.6) **分析表头（P8）**：座位在工作室主总线之后——表头看到的必须就是听得到的。
        //      它只读、不改采样（真峰那一份过采样滤波器是它自己的内部状态）。
        if (studioMonitoring) {
            dev.t1m3.qplayer.audio.studio.StudioMeters meters = studioMeters;
            if (meters != null) {
                meters.process(blockBuf);
            }
        }
        // 2) 交错 → 推入环（环满就等，绝不丢）
        int samples = blockBuf.interleaveInto(interleaved);
        publishSpectrum(samples);
        pendingSamples = samples;
        pendingOffset = 0;
        pushPending();
        // 3) 发布快照（位置/时长/状态/自动交接）
        publishSnapshot(s);
    }

    /**
     * 把这一块的三段电平发布给界面（{@link SpectrumTap}）。
     *
     * <p>这是<strong>画面用</strong>的数据：算错了最多是背景不动，绝不能影响出声，
     * 所以整段包在 try/catch 里——这一块失败，下一块还会再算。
     */
    private void publishSpectrum(int samples) {
        BandLevels levels = bandLevels;
        OutputConfig config = outputConfig;
        if (levels == null || config == null || samples <= 0) {
            return;
        }
        try {
            levels.process(interleaved, samples, config.channels(), bandScratch, 0);
            SpectrumTap.publish(bandScratch[0], bandScratch[1], bandScratch[2]);
            // 工作站的频谱（64 条对数频带）：同一个出口、同一段 try/catch 纪律
            SpectrumBands.Analyzer analyzer = studioMonitoring ? spectrumAnalyzer : null;
            if (analyzer != null) {
                analyzer.process(interleaved, samples, config.channels());
            }
        } catch (Throwable e) {
            // 画面数据出错不影响出声——但**不能一声不吭**：这条 try/catch 曾经把
            // 一个真错误吞了整整一轮（工作站的频谱永远是空的，日志里一个字都没有）。
            // 每条流只报一次，带栈。
            if (!spectrumErrorLogged) {
                spectrumErrorLogged = true;
                Logger.warn("[qplayer-engine] 频谱/电平发布失败（不影响出声）：" + stackOf(e));
            }
        }
    }

    /**
     * 把手上的余量推进环里。<strong>环满就阻塞等待，绝不丢弃。</strong>
     *
     * <p>为什么不能丢：这个块的帧数已经被 {@code Deck} 记进「已产出」，位置、过渡触发点、
     * 剩余时长全都按它算。丢掉一部分，记账就会跑在听感前面，而且差距会一直累积
     * （不是「偶尔少几毫秒」，是越差越多）。
     *
     * <p>等待循环里每 2 ms 看一次命令世代号：暂停/跳转/停止来了就先回去处理命令，
     * 没推完的余量留在手上（不清），处理完接着推。
     *
     * @return 这一块是否已经全部推完
     */
    private boolean pushPending() {
        long stalledMs = 0L;
        while (pendingOffset < pendingSamples) {
            if (released) {
                return false;
            }
            if (seenCommandEpoch != commandEpoch) {
                // 有命令在等：先回去处理（余量留着，绝不丢）
                return false;
            }
            RingBuffer.Producer p = producer;
            if (p == null) {
                return false;
            }
            int wrote = p.push(interleaved, pendingOffset, pendingSamples - pendingOffset);
            if (wrote > 0) {
                pendingOffset += wrote;
                stalledMs = 0L;
                continue;
            }
            // 环满：等消费者追上来
            sleepQuietly(STALL_SLEEP_MS);
            stalledMs += STALL_SLEEP_MS;
            if (stalledMs >= STALL_LIMIT_MS) {
                // 消费者不在了（设备拔掉/流挂了）。上游同样在这里报错而不是永远等：
                // 无限等会让停止命令得不到处理、退出时 join 卡死。
                //
                // **顺手把这条输出流丢掉**（{@link #abandonOutput}）：从前这里只报错、
                // 句柄留着，于是往后**每一首**都继续往同一条已经不出声的流里推——等 2 秒、
                // 报错、控制器重解析、再推、再等 2 秒……一首都放不出来。
                // 2026-09-19 安卓真机实测就是这么一串重复的「输出停滞超过 2000 毫秒」。
                // 丢掉之后下一次 play 会重新开流，给设备一次自愈的机会；真开不回来时
                // 会在 ensureOutput 那一步如实报错，而不是无声地反复卡住。
                Logger.warn("[qplayer-engine] 输出停滞 {} ms：环形缓冲 {}/{} 帧没被消费 → "
                                + "丢掉这条输出流，下一次播放会重新开流",
                        Long.valueOf(STALL_LIMIT_MS), Integer.valueOf(p.capacity() - p.free()),
                        Integer.valueOf(p.capacity()));
                failSession("输出停滞超过 " + STALL_LIMIT_MS + " 毫秒——音频设备可能已断开");
                abandonOutput();
                return false;
            }
        }
        pendingSamples = 0;
        pendingOffset = 0;
        return true;
    }

    /**
     * 曲终。
     *
     * <p>顺序是<strong>先排空再报</strong>：环里还压着这首歌的最后一段（≤ 一个环），
     * 直接 {@code onComplete} 会让控制器立刻开下一首并把这段切掉——用户听到的是
     * 「最后 200 ms 没了」。上游 {@code Session::run} 结尾那段排空就是为这个。
     */
    private void finishSession(Session s) {
        SpectrumTap.reset();
        resetStudioReadings();
        reportNoTransition(s);
        s.finished = true;
        drainRing();
        sessionActive = false;
        playRequested = false;
        // 位置/时长留在末尾如实报（界面显示曲终位置），曲名也留着，
        // 直到下一次 play 覆盖——清了的话界面上「刚放完的那首」会瞬间变空。
        fire(onComplete);
    }

    /**
     * 这一曲自然放完了，而<strong>没有做过过渡</strong>：把原因写清楚。
     *
     * <p>用户能看到的只有「换歌的时候什么都没发生」，而这条日志回答的是「为什么」：
     * 总开关关着 / 接法是窗口 0 的硬接 / 没有下一首预告 / 分析没赶上。
     * 判据全部来自编排器自己的状态（窗口、队列、是否在过渡里），不是猜的。
     */
    private void reportNoTransition(Session s) {
        if (!settings.automixEnabled() || s.inTransition) {
            return;
        }
        Director d = s.director;
        String why;
        if (d.windowFrames() == 0) {
            why = "接法「" + d.transitionName() + "」的窗口是 0（无缝拼接就是硬接）";
        } else if (d.queued() == 0) {
            why = "这一曲没有下一首预告（队列为空）";
        } else {
            // 队列里还有下一首、窗口也不是 0：那就是准备没在曲终之前落地。
            // prepare 至今最坏实测 4.6 秒，而余量是 PREPARE_MARGIN_SECS——
            // 走到这里通常意味着预告来得太晚（这一首很短）或分析线程还没收工。
            why = "分析没能在曲终之前就绪（prepare 需要提前 "
                    + Director.PREPARE_MARGIN_SECS + " 秒以上动手）";
        }
        Logger.info("[qplayer-engine] 自动混音：本曲自然放完，这一次没有做过渡（{}）· {}",
                why, d.currentPath());
    }

    /** 等环被消费者读空（上限 {@link #DRAIN_LIMIT_MS}）。 */
    private void drainRing() {
        RingBuffer.Producer p = producer;
        if (p == null) {
            return;
        }
        long waited = 0L;
        while (!released && p.free() < p.capacity() && waited < DRAIN_LIMIT_MS) {
            sleepQuietly(STALL_SLEEP_MS);
            waited += STALL_SLEEP_MS;
        }
    }

    /** 异常 + 完整栈（只有类名的日志等于没有日志，见生产者线程那一处）。 */
    private static String stackOf(Throwable e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private void failSession(String why) {
        Logger.error("[qplayer-engine] " + why);
        sessionActive = false;
        playRequested = false;
        pendingSamples = 0;
        pendingOffset = 0;
        Session s = session;
        session = null;
        if (s != null) {
            s.finished = true;
            retireDirector(s.director);
        }
        flushRing();
        fire(onError);
    }

    /**
     * 丢掉当前这条输出流（<strong>只在生产者线程上调</strong>）。
     *
     * <p>为什么需要它：{@link #ensureOutput} 见到 {@code handle != null} 就直接复用，
     * 这对「偶尔的会话失败」是对的（重开一次流要几百毫秒，能不动就不动）。但**输出本身**
     * 坏掉时（设备断开、AudioTrack 不再被消费）复用就变成了灾难：往后每一首歌都往同一条
     * 死流里推，等满 {@link #STALL_LIMIT_MS}、报错、跳下一首，一首都放不出来。
     *
     * <p>所以那条路上要把句柄一并丢掉，让下一次 {@code play} 重新开流。开不回来时
     * {@code ensureOutput} 会如实报「打开输出失败／没有可用的输出设备」——那是可见的失败，
     * 比"每首歌都卡 2 秒然后跳过"好得多。
     */
    private void abandonOutput() {
        OutputHandle h = handle;
        handle = null;
        producer = null;
        outputConfig = null;
        if (h == null) {
            return;
        }
        try {
            h.close();
        } catch (RuntimeException e) {
            // 关一条已经坏掉的流失败没有补救动作，也不该盖掉真正的失败原因
            Logger.warn("[qplayer-engine] 关掉停滞的输出流时出错：{}", String.valueOf(e));
        }
    }

    // ==================================================================
    // 发布
    // ==================================================================

    /**
     * 每块发布一次快照（位置、时长、路径状态……），并顺手发现「编排器自己换曲了」。
     *
     * <p>{@code Director} 的一切只归生产者线程读，所以界面读到的是这里的快照，
     * 而不是去碰那些对象——那会是一次数据竞争（{@code Deck} 的游标不是 volatile）。
     */
    private void publishSnapshot(Session s) {
        Director d = s.director;
        String path = d.currentPath();
        if (!path.equals(s.lastPath)) {
            // **编排器把进场曲提上来了**：自动混音的一次过渡收尾了。
            // 这一刻起「正在放的是哪一首」变了，而控制器还不知道，所以：
            //  1) 留下一次性令牌，控制器随后 play(这一首) 时不要重启它（见 absorbHandedOverPlay）；
            //  2) 重新施加增益——新 deck 的增益是单位值，不重设音量会在接缝上跳回去；
            //  3) 触发 onComplete，让控制器推进它自己的队列（否则界面停在上上首）。
            if (s.inTransition) {
                // 实际交叠了多久：**用编排器自己数的那个值**。
                //
                // 原先这里拿「快照里曲目路径变了」那一刻的帧数差去估，而那是按块边界量的
                // ——日志上就出现「窗口 4000 ms、实际交叠 3900 ms」这种看着像少了 100 ms
                // 的数（实测差 142 ms，比一整块还多）。真正渲染了多少帧编排器最清楚
                // （它就是拿这个计数判断过渡什么时候结束），所以直接问它要。
                long renderedFrames = s.director.lastTransitionFrames();
                long plannedFrames = s.director.windowFrames();
                Logger.info("[qplayer-engine] 自动混音：过渡结束 · 实际交叠 {} ms（计划窗口 {} ms）"
                                + " · 现在放 {}",
                        Long.valueOf(EngineFrames.msOfFrames(renderedFrames, rate())),
                        Long.valueOf(EngineFrames.msOfFrames(plannedFrames, rate())),
                        path);
                s.inTransition = false;
            } else {
                // 没有过渡的那种换曲（总开关关着 / 接法是无缝拼接）：接缝上不留空隙，
                // 但也没有交叠。写一条是为了让「刚才换曲了没有空隙」也能从日志看出来。
                // **「为什么没有过渡」在这里收口**（2026-10-01，用户报「有的歌压根没 automix」
                // 而手机上日志刷得太快、复制不出来）：不管原因是哪一条（拿不到下一首句柄、
                // 准备没赶上触发点、准备失败、进场曲打不开…），换曲这一刻都打**一条**
                // WARN，一眼能看完、能念出来。
                String why = s.director.skipReason();
                Logger.warn("[qplayer-engine] 自动混音：这次换曲没有交叠（原因：{}）· 现在放 {}",
                        why == null ? "总开关关着 / 接法是无缝拼接" : why, path);
            }
            s.lastPath = path;
            handedOverSource = path;
            // 过渡期间记下的预告在这里落地（见 handleArmNext）——交接刚完，队首可以安全换了
            applyPendingArmAfterHandoff(s);
            applyGain(true);
            publishFormat(s);
            fire(onComplete);
            fire(onStarted);
        }
        s.lastCurrentFrames = d.currentFrames();
        // ---- 位置/时长报「界面现在说的那一首」 ----
        //
        // 过渡窗口一开，界面就切到进场曲了（控制器在 onTransitionStart 里推进队列，
        // 那一刻进场曲已经在出声）。所以这里必须跟着切：报出场曲的剩余会让进度条
        // 在标题已经写着 B 的时候贴在 100% 不动——那比滞后更难解释。
        //
        // **过渡窗口里两份音频都在出声，「当前位置」本来就没有唯一解。** 挑进场 deck
        // 的两个理由：①界面此刻的题目就是它；②它取的是 Deck.outFrames()，与交接之后
        // 立刻上报的那一组数字是同一个 deck 的同一份记账，于是进度条在交接那一刻连续、
        // 不会往回跳。代价如实写在这里：窗口内这个值<b>不等于</b>「已经听了多久」——
        // 那几秒里还有出场曲的尾巴在响。
        publishTrackReadings(s, d);
        // 与上面两个**故意不同**：currentSource 报的仍是出场曲的句柄，直到交接把它提上位。
        // 它是「编排器手上那个 current deck 是谁」这一事实（交接判据 s.lastPath 也建在它上面），
        // 而位置/时长报的是「界面此刻说哪一首」。窗口里两首都在出声，把两个问题合成一个答案
        // 只会让其中一边撒谎——所以宁可让这两行在窗口内指向不同的曲目，并在这里写清。
        currentSource = path;
        transitionName = d.transitionName();
        transitionNote = d.transitionNote();
        pathState = combine(d);
        publishTransitionSnapshot(s, d);
        reportTransitionEntry(s, d);
        reportSkipped(s, d);
        if (!s.startedFired) {
            s.startedFired = true;
            fire(onStarted);
        }
    }

    /**
     * 发布控制室快照（每块一次，零分配上的取舍见下）。
     *
     * <p>它<strong>每块 new 一个对象</strong>（十几个字段）：这是这条路径上唯一的每块分配，
     * 而有意的——快照必须是不可变的，否则界面读到一半生产者在改，看到的是一份撕裂的状态。
     * 代价实测很小（每块约 100 字节，200 ms 环里只有几十个对象），换来的是「读数与引擎一致」
     * 这条判据可以真的成立。要彻底零分配就得做成双缓冲 + 版本号，那是 P10 页面时的事。
     */
    private void publishTransitionSnapshot(Session s, Director d) {
        // **这里的每一个「可能是 null」都要挡住**：`Director` 的 `Option<T>` 一律映射成
        // `null`（见它的类说明），而这一段落跑在命令落地之后的同一条栈上——抛一次就是
        // `failSession`（整场播放结束），症状是「测试里环一直是空的」。
        // 这不是假想：第一版就是这么写的，`PlaybackEngineTest` 21 条里红了 20 条，日志里
        // 一行 NPE 指到 `queuedNextPath()` 上。
        String rack = studio == null ? "" : studio.describe();
        boolean inWindow = d.inTransition();
        Float progress = d.transitionProgress();
        String note = safe(d.transitionNote());
        String next = safe(d.nextPath());
        String queued = safe(d.queuedNextPath());
        String summary;
        if (inWindow) {
            summary = "过渡中 · " + safe(d.transitionName()) + " · 窗口 "
                    + fmtSecs(d.windowSecs()) + " s · 进度 "
                    + String.format(java.util.Locale.ROOT, "%.0f%%",
                            Float.valueOf((progress == null ? 0f : progress.floatValue()) * 100f))
                    + (note.isEmpty() ? "" : " · " + note);
        } else if (!queued.isEmpty()) {
            summary = "待机 · 下一首已就位 · 接法 " + safe(d.transitionName());
        } else {
            summary = "待机 · 没有下一首";
        }
        // 离过渡开始还有几秒（NaN = 没有已装载的过渡）：界面的「过渡提示」靠它提前亮，
        // 见 `TransitionSnapshot.leadInSecs`。
        //
        // **只放进这个数值字段，不拼进 `summary`**：`summary` 每块都会被界面读去显示，
        // 里面放一个每块都在变的数字，等于逼控制室每块重设一次字符串属性（那正是
        // `PlayerController.pump()` 里「日志文本每块重建导致整树重排」的同一个坑）。
        Double lead = d.transitionLeadInSecs();
        transitionSnapshot = new TransitionSnapshot(inWindow, safe(d.transitionName()), note,
                d.windowSecs(), progress == null ? 0f : progress.floatValue(),
                pathState, safe(d.currentPath()), next, d.currentFrames(),
                Math.max(0L, d.nextFrames()), rack, summary,
                lead == null ? Double.NaN : lead.doubleValue());
    }

    /** `null` 当空串（`Director` 的 `Option<T>` 映射成 `null`，见上面的注释）。 */
    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 分析表头（P8）的读数：峰值 / 真峰 / LUFS（M、S、I）/ 相位相关性。
     *
     * <p>没有开流时返回「静音」那一份（不返回 null，界面不必到处判空）。
     */
    @Override
    public dev.t1m3.qplayer.audio.studio.StudioMeters.Snapshot studioMeters() {
        dev.t1m3.qplayer.audio.studio.StudioMeters meters = studioMeters;
        return meters == null ? dev.t1m3.qplayer.audio.studio.StudioMeters.Snapshot.SILENT
                : meters.snapshot();
    }

    /**
     * 控制室读的那一份快照（P4）。界面直接显示 {@code summary} 即可，不必自己拼。
     */
    @Override
    public TransitionSnapshot transitionSnapshot() {
        return transitionSnapshot;
    }

    /**
     * 进入过渡的那一刻记一条，并<strong>通知控制器</strong>（都只做一次，不是每块都做）。
     *
     * <p>内容按用户能自己核对的顺序给：接法名、窗口长度、这一档的结论
     * （{@code plan.note}，与设置页/AI 模式那套措辞同源）、以及位完美状态——
     * 「过渡那几秒不是位完美」是设计（ADR-003 §2.2），但必须能在日志里看到
     * 它<strong>只是那几秒</strong>。
     *
     * <p><b>为什么在这里发 {@code onTransitionStart}</b>：这一刻进场曲已经开始出声
     * （{@code Director} 把它渲染进混合里了），而交接（{@code onComplete}）要等整个窗口
     * 走完。控制器推进队列/界面如果等交接，就会在整个窗口里停在出场曲上——用户实测的
     * 「B 都放上了、界面还赖在 A、进度条走完才跳」正是这么来的。
     *
     * <p><b>恰好一次</b>由上面那道边沿判断保证（{@code now == s.inTransition} 直接返回），
     * 一次过渡只会走到 {@code if (now)} 里面一次；交接时换的是 {@code s.lastPath}，
     * 与这里的状态无关，所以那个分支不会把这条再发一遍。
     */
    private void reportTransitionEntry(Session s, Director d) {
        // **通知的边沿 = "B 真的开始出声"**（2026-10-07 用户拍板改契约 ✓）。
        //
        //   · 正常交叉淡化：B 从窗口第一块起就在往前走 ⇒ 通知时机与从前只差一块
        //     （≈20 ms，无感 ✓），现有判据「通知恰好一次 · 状态是 TRANSITIONING ·
        //     报的是进场曲的」照旧成立 ✓；
        //   · 尾奏循环的「买时间」档：窗口前半段只有 A 在响、B 停在进场上限不动
        //     ⇒ 通知**顺延到 B 出声那一刻** ✓ —— 在此之前界面一直是 A
        //     （标题/进度/时长**一起**是 A ✓），不会再出现"标题已经是 B、
        //     进度条却是另一个读数"的不一致 ✗（用户原话：「进度条还在走 …
        //     我看到的进度不一致啥的也是这来的」）。
        long introFrames = d.inTransition() ? d.nextFrames() : -1L;
        // **只有真存在「只有 A 在响」的前导时才等**（买时间那一档 ✓）：
        // 其余情况（普通交叉淡化、以及引擎夹具里那些替身过渡）一律照旧 = 窗口一开就通知 ✓。
        // 拿"帧数在走"当**唯一**信号是不行的 ✗ —— 实测：引擎夹具里 `nextFrames()` 根本不前进，
        // 于是通知一次都没发出去 ✗（`PlaybackEngineTest` 当场 6 条红）。
        boolean hasLeadIn = d.outroLoopLeadInSecs() > 0.05;
        boolean introAdvanced = introFrames >= 0L && s.lastNextFrames >= 0L
                && introFrames > s.lastNextFrames;
        boolean sounding = pathState == PathState.TRANSITIONING
                && (!hasLeadIn || introAdvanced);
        s.lastNextFrames = introFrames;
        s.introSounding = sounding;
        boolean now = sounding;
        if (now == s.inTransition) {
            return;
        }
        if (now) {
            s.inTransition = true;
            s.transitionEnterFrames = s.lastCurrentFrames;
            // **先把位置/时长算好再通知**（见 publishTrackReadings 的注释）：回调里控制器会
            // 立刻读它们摆界面 ⇒ 不能让它读到"上一块"的读数 ✗。
            publishTrackReadings(s, d);
            String note = d.transitionNote();
            // **一段 note 不等于一行日志**（2026-10-03）：这一条原先把 `transitionNote()`
            // 整个贴上去，而那是九项打分 + 次优候选分析的完整段落（几百字），
            // 于是「进入过渡」这一行在日志里占掉半屏。用户的原话是「现在太繁琐了」。
            // 默认只留「接法」（真正要一眼知道的），那一整段降级到 debug——设置里
            // 「过渡诊断日志」一开就原样回来。
            Logger.info("[qplayer-engine] 自动混音：进入过渡 · 接法「{}」", d.transitionName());
            Logger.debug("[qplayer-engine] 自动混音：进入过渡详情 · 窗口 {} s · 位完美={}{}",
                    fmtSecs(d.windowSecs()), Boolean.valueOf(pathState.isBitPerfect()),
                    note == null || note.isEmpty() ? "" : " · " + note);
            // 通知放在日志之后：日志是「发生了什么」的凭据，回调是「界面现在该跟着走」，
            // 而回调那一侧（控制器）可能会读这里刚发布的位置/时长快照。
            String intro = d.nextPath();
            if (intro != null && !intro.isEmpty()) {
                // 令牌**提前**落在进场曲上：过渡窗口这一整段里，任何一次「play 进场曲」
                // 都是「追认这次交接」而不是「重头放一遍」（见 absorbHandedOverPlay）。
                handedOverSource = intro;
                fireTransitionStart(intro);
            }
        } else {
            // 过渡在**没有换曲**的情况下结束了（拖了进度条、或窗口走完但编排器还没提上来）：
            // 一样要收掉状态，否则下一条「过渡结束」会拿一个过期的起点算时长。
            // 还在过渡里、但这一块被上面的换曲分支收掉的情况，由那里负责。
            s.inTransition = false;
        }
    }

    /**
     * 把「打不开的下一首 / 分析失败」如实报出来（只报新增的那些）。
     *
     * <p>{@code Director} 把这些记在 {@code skipped()} 里（谁、为什么），引擎以前只在
     * 界的另一个出口（{@code skipped()} 的读取方）留着，日志里一条都没有：
     * 结果是「这次过渡没发生」在日志上完全静默。失败原因必须能对上——
     * 用户看到的应当是「下一首那个文件打不开」，而不是「自动混音没用」。
     */
    private void reportSkipped(Session s, Director d) {
        java.util.List<Director.Skipped> all = d.skipped();
        if (all.size() <= s.skippedReported) {
            return;
        }
        for (int i = s.skippedReported; i < all.size(); i++) {
            Director.Skipped skipped = all.get(i);
            Logger.warn("[qplayer-engine] 自动混音：跳过了 {}（{}）→ 这一次过渡用不上它",
                    skipped.source, skipped.why);
        }
        s.skippedReported = all.size();
    }

    /** 每首歌（或输出配置）变一次：把格式判定算出来缓存，别每块都算。 */
    private void publishFormat(Session s) {
        OutputConfig config = outputConfig;
        TrackSpec spec = s.director.currentSpec();
        if (config == null || spec == null) {
            formatSummary = "";
            formatLosesInformation = false;
            return;
        }
        PathVerdict verdict = PathVerdict.classify(spec.sourceFormat(),
                new int[]{config.sampleRate()}, new BitDepth[]{config.bitDepth()});
        formatSummary = verdict.summary();
        formatLosesInformation = verdict.losesInformation();
    }

    /**
     * 把两层判定合起来——这是「界面上那行字」的唯一来源。
     *
     * <p>第一层是链路（{@link Director#pathState()}）：重采样器是否透传、增益是否单位值、
     * 是否在过渡窗口里。第二层是格式（{@link PathVerdict}）：采样率/位深这一路到底有没有
     * 丢信息。
     *
     * <p>判据用 {@code losesInformation()} 而<strong>不是</strong>
     * {@code !isBitPerfect()}：24 bit 的 FLAC 在只声明 F32 的设备上不是「直通」，
     * 但一个比特都没丢（ADR-003 §3.2）。用后者会把这种正常情况报成降级。
     */
    private PathState combine(Director d) {
        PathState link = d.pathState();
        if (link.isBitPerfect() && formatLosesInformation) {
            return PathState.PROCESSED;
        }
        if (link.isBitPerfect() && studio != null && studio.active()) {
            // 主总线那一台在动采样（deck 那两台由 Director 自己报）
            return PathState.PROCESSED;
        }
        return link;
    }

    /**
     * 参数对账：设置里的工作室链变了才重建滤波器，顺便把当前音量交给响度补偿。
     *
     * <p>判据是<strong>对象引用</strong>：设置实现缓存解析结果，没改就是同一个对象
     * （见 {@code EngineSettings.audioStudio} 的说明）。这样每块只做一次引用比较，
     * 而重建（要算 pow/sin/cos）只发生在用户真的动了参数那一刻。
     */
    private void reconcileStudio() {
        if (studio == null) {
            return;
        }
        dev.t1m3.qplayer.audio.studio.StudioRacks want = settings.audioStudio();
        if (want == null) {
            want = dev.t1m3.qplayer.audio.studio.StudioRacks.EMPTY;
        }
        if (want != appliedStudioRacks) {
            studio.setRacks(want);
            appliedStudioRacks = want;
            Logger.info("[qplayer-engine] {}", studio.describe());
        }
        studio.setVolume(volume);
    }

    /**
     * 把音量施加到 deck 的增益上。
     *
     * <p>两处刻意的设计：
     *
     * <ol>
     *   <li><strong>音量走 deck 的增益</strong>，不在这里改采样值——增益不是单位值时
     *       {@code Deck.gainIsUnity()} 就是 false，{@link #pathState()} 会如实报
     *       {@code PROCESSED}。位完美是<strong>算出来的</strong>，不许为了好看绕过去。</li>
     *   <li><strong>HiFi 位完美直通开着时保持单位值</strong>：ADR-003 要求 HiFi 路径
     *       旁路一切电平处理，设置页那行说明也是「全程不做增益」。所以此时不施加
     *       用户音量，音量交给系统/硬件——这是有意的取舍，不是漏做。</li>
     * </ol>
     */
    private void applyGain(boolean force) {
        Session s = session;
        if (s == null || s.finished) {
            return;
        }
        float want = settings.bitPerfect() ? 1.0f : volume;
        if (!force && want == appliedGain) {
            return;
        }
        appliedGain = want;
        s.director.setGainDb(Gain.linearToDb(want));
    }

    private int rate() {
        OutputConfig config = outputConfig;
        return config == null ? 48_000 : config.sampleRate();
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private void fire(Runnable callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.run();
        } catch (Throwable e) {
            // 控制器那边抛异常绝不能弄死生产者线程：它的死法是「播放悄悄停住」，
            // 没有任何声音上的迹象，最难查。
            //
            // **必须带栈**：2026-09-28 真机日志里出现过一条
            // 「回调抛异常：java.util.ConcurrentModificationException」——只有类名，
            // 事后完全不知道该去看哪一处并发（与生产者线程那一条同一个教训）。
            Logger.error("[qplayer-engine] 回调抛异常：" + stackOf(e));
        }
    }

    /** {@link #fire(Runnable)} 的带参版本（过渡开始那一条，参数是进场曲句柄）。 */
    private void fireTransitionStart(String intro) {
        java.util.function.Consumer<String> callback = onTransitionStart;
        if (callback == null) {
            return;
        }
        try {
            callback.accept(intro);
        } catch (Throwable e) {
            // 与上面同一条纪律：控制器的异常不许落到生产者线程上（同样带上栈）。
            Logger.error("[qplayer-engine] 过渡开始回调抛异常：" + stackOf(e));
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // 释放路径用 interrupt 叫醒等待中的生产者；标志由调用方（循环条件）处理。
            Thread.currentThread().interrupt();
        }
    }


    /**
     * 发布「界面现在说的那一首」的位置与时长（抽出来的，2026-10-07）。
     *
     * <p><b>为什么要抽</b>：过渡通知的回调里，控制器会**立刻**读 {@code position()}/{@code duration()}
     * 去摆界面 ✓ —— 而那两个字段是在主循环里算的 ⇒ 通知若发生在"算完之前"，回调拿到的还是
     * **上一块**的读数（实测：进场曲已经上位了，回调读到的还是出场曲的 500 ms ✗）。
     * 抽出来之后，通知那一步可以**先重算一次再发** ✓（见 {@code reportTransitionEntry}）。
     */
    private void publishTrackReadings(Session s, Director d) {
    long publishedFrames = s.lastCurrentFrames;
    TrackSpec spec = d.currentSpec();
    // **只在 B 真的出声之后才报 B**（2026-10-07 用户拍板改契约 ✓，判定见
    // `reportTransitionEntry` 里的 `introSounding`）。那之前报的还是 **A** ——
    // 界面此刻听到的也是 A ✓，于是标题/进度/时长三样一致 ✓。
    //
    // ⚠️ 曾经试过这一步但**契约没改**（`PlaybackEngineTest` 当时钉着"通知那一刻报的时长
    // 必须已经是进场曲的"）⇒ 当场红 ✗，于是退回。现在通知本身也顺延到 B 出声那一刻了 ✓，
    // 两者口径一致：**通知、位置、时长一起切** ✓。
    if (d.inTransition() && s.introSounding) {
        long introFrames = d.nextFrames();
        TrackSpec introSpec = d.nextSpec();
        if (introFrames >= 0L && introSpec != null) {
            publishedFrames = introFrames;
            spec = introSpec;
        }
    }
    // **夹在真实时长内**：循环是用素材源喂的，deck 位置会一路走进"延长段" ✗ ——
    // 那会让进度条越过 100%（另一种"不一致"）⇒ 按当前曲目的真实时长夹住 ✓。
    Double durSecs = spec == null ? null : spec.durationSecs();
    if (durSecs != null && durSecs.doubleValue() > 0.0) {
        long maxFrames = EngineFrames.framesOfMs(
                Math.round(durSecs.doubleValue() * 1000.0), rate());
        if (maxFrames > 0L && publishedFrames > maxFrames) {
            publishedFrames = maxFrames;          // 夹到"正好结尾"= 100%，不再少一帧 ✗
        }
    }
    s.lastNextFrames = d.nextFrames();
    positionMs = EngineFrames.msOfFrames(publishedFrames, rate());
    Double secs = spec == null ? null : spec.durationSecs();
    // 未知长度**如实报 0**，不猜一个出来（进场曲长度未知时也一样，不拿出场曲的顶上）
    durationMs = secs == null ? 0L : (long) (secs.doubleValue() * MS_PER_SEC);
    }

    /** 工作室现在的增益衰减（dB，正数 = 压了多少）。 */
    @Override
    public float studioGainReductionDb() {
        dev.t1m3.qplayer.audio.studio.StudioProcessor s = studio;
        return s == null ? 0f : s.gainReductionDb();
    }

    /** 表头与频谱归零（暂停/曲终：画面不该停在上一首的读数上）。 */
    private void resetStudioReadings() {
        dev.t1m3.qplayer.audio.studio.StudioMeters meters = studioMeters;
        if (meters != null) {
            meters.reset();
        }
        SpectrumBands.Analyzer analyzer = spectrumAnalyzer;
        if (analyzer != null) {
            analyzer.reset();
        } else {
            SpectrumBands.reset();
        }
    }

    /**
     * 界面在不在看工作室页（表头与频谱只在有人看的时候才算）。
     *
     * <p>关掉时<strong>不再累积</strong>：读数与频谱停在上一次的值，页面再打开时
     * 控制器会重新问一次（表头那几条在 {@code StudioBridge.refresh()} 里重读快照）。
     */
    @Override
    public void setStudioMonitoring(boolean on) {
        studioMonitoring = on;
    }

    /** 实时频谱：拷进调用方给的数组（见 {@code AudioBackend.studioSpectrum}）。 */
    @Override
    public int studioSpectrum(float[] dst) {
        return SpectrumBands.copyInto(dst);
    }

    @Override
    public String toString() {
        OutputConfig config = outputConfig;
        return "PlaybackEngine{" + (config == null ? "未开流" : config.toString())
                + ", playing=" + isPlaying()
                + ", pos=" + positionMs + "ms"
                + ", src=" + currentSource
                + ", path=" + pathState
                + ", transition=\"" + transitionName + "\""
                + ", hifi=" + hifiBitPerfect + '}';
    }
}
