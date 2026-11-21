package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.audio.dsp.PitchShifter;
import dev.t1m3.qplayer.util.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 播放编排器。
 *
 * <p>它回答四个问题：<strong>现在放什么、接下来放什么、什么时候开始准备、
 * 什么时候开始过渡</strong>，并把这些翻译成对两个 deck 与一个过渡引擎的调用。
 *
 * <h2>两阶段：准备 → 过渡</h2>
 *
 * <p>过渡要能「踩在拍上」「转调到相合的调」，就必须在开始<strong>之前</strong>知道两首歌
 * 长什么样。所以流程分两步：
 *
 * <ol>
 *   <li><strong>准备</strong>（<b>一收到预告就开工</b>，2026-10-02 起；原先要等到
 *       {@code remaining <= 窗口 + 余量} 才动，见 {@code shouldPrepare} 里记的那两条理由）：
 *       在<strong>另一条线程</strong>（{@code sonalis-prepare}）上把两个音源句柄交给
 *       {@link Transition#prepare}，拿回一份 {@link TransitionPlan} 与一个
 *       <strong>准备好的实例</strong>；同一条线程接着把<strong>下一首 deck 打开、
 *       对拍定位好、变调器造好</strong>——交回来的是一份「随时能开播」的结果，
 *       而不是一份纸面计划。</li>
 *   <li><strong>过渡</strong>（{@code remaining <= 窗口} 时触发）：把准备好的东西
 *       <strong>装上去</strong>（换实例、弹队列、deck 放上 {@code next}），进入窗口渲染。
 *       这一步<strong>不做任何 IO、不造任何变调器</strong>。</li>
 * </ol>
 *
 * <p>准备之所以要<strong>提前</strong>，是因为对拍会把窗口<strong>拉长</strong>（最多一拍），
 * 若等到触发点才分析，拿到的新窗口已经比剩余量大了——过渡会开始得偏晚，
 * 拍点也就对不上了。「一收到预告就开工」把这个提前量做到了极限：预告通常是
 * <strong>比唱早几分钟</strong>送进来的，于是分析永远在从容的时候做完，
 * 而 {@link #PREPARE_MARGIN_SECS} 那一档现在只表示「**这一档最坏要跑多久**」（预算）。
 *
 * <h2>准备<strong>必须</strong>异步：绝不能在生产者线程上等它</h2>
 *
 * <p>曾经 {@link #pump} 是同步调用 {@link Transition#prepare} 的。后果不是「慢一点」，
 * 而是<strong>整个音频卡住</strong>：{@code pump()} 跑在生产者线程上，
 * 环形缓冲默认只有 200 ms，而一次准备要打开两个文件、解码十几秒音频、
 * 做频谱通量与调性分析——上游实测（debug 构建、40 秒素材）中位耗时 1.6~4.6 秒。
 * 缓冲被写空，听感就是「卡 1.6~4.6 秒，然后加载完了再出来」。
 *
 * <p>改法是<strong>让每个接法实例只服务一次过渡</strong>：
 *
 * <ul>
 *   <li>{@code Director} 持有<strong>一个工厂</strong>（{@link TransitionFactory}）与
 *       <strong>一个当前用于渲染的实例</strong>；</li>
 *   <li>到该准备时另起一条线程，在那边用工厂造一个<strong>新实例</strong>、跑
 *       {@code prepare}、把下一首 deck 开好定位好，再把这一整份结果发回来；
 *       {@link #pump} 里只做非阻塞的 {@code poll()}，<strong>一个字节都不等</strong>；</li>
 *   <li>过渡真正开始时把准备好的实例<strong>换上来</strong>。此刻旧实例已经不再渲染，
 *       所以这次替换不需要任何锁。</li>
 * </ul>
 *
 * <p>最后这条正是选「工厂 + 换实例」而不是 {@code Arc<Mutex<dyn Transition>>} 的
 * 全部理由：<strong>那把锁会在整整 4.6 秒的准备期间被持有</strong>，等于把同一个卡顿
 * 原样复现一遍。
 *
 * <h2>准备的不只是计划，还有<strong>一个能马上开播的 deck</strong></h2>
 *
 * <p>只把分析搬走是不够的。{@link #beginTransition} 里原本还剩三件重活，
 * 它们同样落在<strong>过渡刚起来的那一瞬间</strong>（环形缓冲只有 200 ms）：
 *
 * <table border="1">
 *   <caption>重活的搬迁</caption>
 *   <tr><th>活</th><th>原来在哪</th><th>现在在哪</th></tr>
 *   <tr><td>{@code Deck.open}（开文件、探容器、分配缓冲、建重采样核表）</td>
 *       <td>{@code begin_transition}</td><td>准备线程</td></tr>
 *   <tr><td>{@code Deck.seek}（对拍定位）</td><td>{@code begin_transition}</td><td>准备线程</td></tr>
 *   <tr><td>造变调器（{@code engage_pitch}）</td><td>{@code begin_transition}</td><td>准备线程</td></tr>
 * </table>
 *
 * <p>症状与上一轮完全一样：<strong>开始过渡时卡一下</strong>。搬走之后
 * {@link #beginTransition} 只做安装——把三样东西摆好，置过渡位置为 0。
 *
 * <h2>没准备好时怎么办：<strong>推迟</strong>，不是硬上</h2>
 *
 * <p>从前的降级是「用没 {@code prepare} 过的实例 + 默认计划，照常开始过渡」。
 * 现在不能这么退了：那需要<strong>当场打开进场曲</strong>（就是 {@code begin_transition} 里
 * 原来那句 {@code open_next}），等于把刚搬走的 IO 拿回来再付一次。
 *
 * <p>所以降级改成<strong>推迟</strong>：没有准备好的 deck 就这一轮不开始过渡，
 * 让当前曲接着放。{@link #pump} 每一轮都会再问一次，结果一到就立刻开始过渡——
 * 只是窗口比原计划短一点，而 {@link Mixer#mixTwo} 会把缺失的那一路当零处理。
 * 一直等不到（线程起不来、{@code prepare} 抛异常、队列里的文件一个都打不开、
 * 机器慢到准备跑不完），当前曲就自然结束，由 {@link #renderCurrent} 里那条硬接路径接手。
 * 那条路径本来就要打开文件（手动切歌、队列播完走的都是它），不是新引入的行为。
 *
 * <p>两条路都<strong>不可能阻塞生产者线程</strong>，这是这次修复的底线：
 * 宁可听一次硬接，也不能为了等准备把环形缓冲写空。
 *
 * <p>代价要说清：<strong>泵得比实时快得多的调用方</strong>（离屏渲染、测试）会撞上这条推迟。
 * 实时播放不会——设备节奏把生产者线程限着，30 秒的余量对几秒的准备绰绰有余。
 * 非实时调用方靠 {@link #collectPreparation()} 自己等一等，见那个方法的文档。
 *
 * <p>准备结果还要能<strong>作废</strong>：用户切歌/跳转/上一首时，还在路上的结论是给
 * 上一个局面算的，用上它就是把 A 的结论施加到 B 上。作废只看世代号
 * （见 {@link #generation}）；线程本身<strong>打断不了</strong>——{@code prepare}
 * 是一整段不可中断的分析，它会把那段算完再退出。
 *
 * <p>作废掉的是<strong>整份结果</strong>，包括那个已经打开好的 deck（它会被直接丢掉，
 * 文件句柄跟着释放）。这一点比只丢计划更要紧：deck 上已经被施加过
 * {@code seek(skip_secs)}，把这样一个 deck 装上去就是把 A 的定位施加到 B 上。
 *
 * <h2>过渡窗口是怎么触发的</h2>
 *
 * <p>编排器持续盯着当前 deck 的剩余帧数。当 {@code 剩余 <= 过渡窗口长度} 时开始过渡——
 * 这样窗口走完时出场曲<strong>正好</strong>结束，而不是被硬切。
 *
 * <p>如果素材比预期短（窗口还没走完 A 就没了），{@link Mixer#mixTwo} 会把缺的那一路
 * 当零处理：不会出错，只是淡出会显得提前。
 *
 * <h2>长度未知的流不做过渡</h2>
 *
 * <p>{@link Deck#remainingFrames()} 返回 {@code null} 时（流式输入没有总帧数）
 * 编排器<strong>不做过渡</strong>，退化为硬接。理由：算不出触发点就不该猜——
 * 猜错的结果是过渡提前发生，听感比硬接更差。
 *
 * <h2>位完美状态怎么算出来的</h2>
 *
 * <p>{@link #pathState()} 是界面上「直通 / 过渡中」那行字的<strong>唯一</strong>来源，
 * 判定只看<strong>实际状态</strong>：
 *
 * <ul>
 *   <li>在过渡窗口内 → {@link PathState#TRANSITIONING}（采样值被混合改写）；</li>
 *   <li>否则看当前 deck：{@code isPassthrough()} <strong>且</strong> {@code gainIsUnity()}
 *       → {@link PathState#BIT_PERFECT}；</li>
 *   <li>其余 → {@link PathState#PROCESSED}（采样率不匹配、挂了变调器、
 *       增益不是 0 dB……都是同一条判定逻辑的结果，不是特判）。</li>
 * </ul>
 *
 * <p>{@code isPassthrough()} 内部再往下问的是重采样器、变调器、双耳渲染器
 * 各自的单位状态（见 {@link Deck#isPassthrough()}）。也就是说：<strong>判定逻辑只允许
 * 有一份</strong>——位完美是逐段生效的，歌曲主体保持、过渡窗口放弃；
 * UI 与别处都不得另算一套。
 *
 * <p>与 {@link Transition#rewritesSamples()} 的关系要说清（它俩是同一件事的两面）：
 * 那是<strong>接法自己</strong>对「这个过渡会不会改写采样值」的回答，上层（CLI / 引擎）
 * 用它标注信号路径；而这里判的是<strong>当前这一块是不是正落在那个窗口里</strong>——
 * 让步的那一段就是过渡窗口。现有的每个接法上两者给同一个答案（会改写采样值的接法
 * 窗口都非 0；{@link Gapless} 的窗口是 0，压根进不了过渡分支）。
 *
 * <h2>线程纪律</h2>
 *
 * <p><strong>混音运行在生产者线程，不在音频回调里。</strong> 音频回调只做
 * {@code pop_or_silence} 这一件事（见 {@code RingBuffer} / {@code OutputHandle}），
 * 所以 {@link #pump} 这一层可以分配内存、可以走间接调用（{@link Transition} 是接口）。
 * 但它仍然<strong>刻意避免每块分配</strong>：两个 deck 的暂存、队列、历史都在构造时
 * 一次性就位，渲染路径上只移动游标。{@code Option<u64>} 拆成两个字段也是为了这个
 * （见下面的差异表）。唯一会在渲染路径上分配的是准备结果的交付，
 * 而它一块最多走一次。
 *
 * <h2>与上游的类型差异（按 docs/audio-port.md 记录）</h2>
 *
 * <ul>
 *   <li>{@code PathBuf} → {@link String}（本地路径或 http(s) URL）：QPlayer 的音源可以是
 *       网络直链且需要请求头，同 {@link Deck} / {@link Transition#prepare}。</li>
 *   <li>{@code Option<T>} → {@code null}（{@link #nextPath()}、{@link #queuedNextPath()}、
 *       {@link #nextSpec()}、{@link #transitionProgress()} 的 {@code Float}、
 *       {@link #currentSpec()} 之外那些）。</li>
 *   <li>{@code Result<T>} → 抛 {@link AudioException}（构造时队列里一首能播的都没有、
 *       {@code Deck.open} / {@code Deck.seek} 失败都如实透传）。</li>
 *   <li>{@code VecDeque<PathBuf>} → {@link ArrayDeque}：队列归<strong>生产者线程独占</strong>，
 *       准备线程只拿一份副本（上游同样如此：{@code worker_queue} 是快照）。
 *       容器本身不做同步，因为上游也不是靠锁，而是靠「只有一条线程碰它」。</li>
 *   <li>{@code mpsc::channel} + {@code try_recv} → {@link ArrayBlockingQueue}
 *       （容量 1，一个任务只会送一份结果）+ {@link BlockingQueue#poll()}。
 *       <strong>为什么不阻塞</strong>：{@code poll()} 在空队列上<strong>立刻</strong>返回
 *       {@code null}，与 {@code try_recv} 的非阻塞语义一一对应；会阻塞的是
 *       {@code take()}，本类一处都不用。容量 1 是够的——上游一个任务也只会
 *       {@code send} 一次。它由 {@code java.util.concurrent} 提供 happens-before：
 *       准备线程造好的 deck 在生产者线程上看得见（这是上游 {@code Send} 的 Java 替身）。</li>
 *   <li>{@code Arc<AtomicBool>} → {@link AtomicBoolean}（volatile 语义，比上游的
 *       Acquire/Release 更强；不用 {@code getAcquire/setRelease}，那要 Android API 33+）。
 *       线程退出时清掉「还在跑」标记靠 {@code try/finally}——上游是 RAII（{@code PrepBusyGuard}），
 *       两者要解决的问题一样：<strong>抛异常/panic 时标记也必须被清掉</strong>，
 *       否则槽位被永久占住，之后再也不会做分析，而且没人会知道。</li>
 *   <li>{@code Option<u64> transition_pos} → {@code boolean transitioning} + {@code long transitionPos}：
 *       上游的 {@code Option<u64>} 是个无分配的值，Java 的 {@code Long} 每块渲染都要装箱一次，
 *       与「渲染路径上刻意避免每块分配」冲突，所以拆成两个字段（{@code transitioning} 为
 *       {@code false} 时 {@code transitionPos} 无意义，与 {@code None} 等价）。</li>
 *   <li>{@code Arc<dyn Fn() -> Box<dyn Transition> + Send + Sync>} → {@link TransitionFactory}
 *       接口（见它的类注释）。</li>
 *   <li>{@code impl fmt::Debug} → {@link #toString()}，与 {@link AudioBuf} / {@link Deck}
 *       同一处理：只报关键状态，不把队列内容刷进日志。</li>
 *   <li>上游靠 {@code Drop} 释放 deck（解码器握着文件句柄/网络连接）；Java 没有析构，
 *       所以<strong>每一处「上游会丢弃 deck」的地方都显式 {@link Deck#close()}</strong>
 *       （见 {@link #retire(Deck)} 与 {@code Deck.close()} 的注释）。</li>
 *   <li>上游 {@code crate::probe} 是 {@code cfg(test)} 专用探针（记录「哪条线程做了
 *       开文件 / 定位 / 造变调器」）。Java 没有「只在测试编译期存在」的机制，
 *       这里补一处<strong>包私有</strong>的钩子 {@link #heavyProbe}（默认 {@code null}，
 *       生产路径上只剩一次判空），记录点就在本类自己调用 {@code Deck.open} /
 *       {@code Deck.seek} / {@code Deck.idlePitchShifter} 的那三处——正是上游探针
 *       守着的那三件重活。{@link Automix} 的类注释里留过这条交接
 *       （「接 director 移植时应当按同样的理由补一处包私有的探针钩子，否则那条回归
 *       无据可依」），这里兑现。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::director::Director}（{@code director.rs}）。
 */
public final class Director {

    /**
     * 「上一首」能回溯多远。
     *
     * <p>随机播放几小时的话，不设上限会让这里成为唯一还在增长的内存。
     */
    private static final int HISTORY_LIMIT = 64;

    /**
     * 过渡前的准备提前量（秒）。
     *
     * <p>它要同时覆盖两件事：
     *
     * <ol>
     *   <li>对拍会把窗口<strong>拉长</strong>最多一拍，而常见速度里最长的一拍
     *       （60 BPM）正好 1 秒；</li>
     *   <li><strong>分析本身的耗时</strong>——准备挪到另一条线程上之后，这一条才是主要项。
     *       提前量成了「结果必须在触发点之前回来」的最后一道保障：实测最坏
     *       4.6 秒（debug 构建、auto 接法），取 30 秒留了一个数量级的余量，
     *       慢机器、大文件、准备线程被系统调度挤开都还在里面。</li>
     * </ol>
     *
     * <p><strong>4 是这次修复之前的旧值</strong>（那时分析是同步做的）。余量再大也救不了
     * 同步：结果是阻塞着等出来的，代价直接落在音频上。反过来，因为现在是异步的，
     * 余量给足才真正换到「触发点之前一定已经拿到结论」。
     */
    public static final long PREPARE_MARGIN_SECS = 180L;   // 工作五：30 s → 180 s

    /**
     * 本编排器的准备**预算**（秒）。默认 {@link #PREPARE_MARGIN_SECS}。
     *
     * <p><strong>2026-10-02 起它不再是「开工闸门」</strong>：准备改成「一收到预告就开工」
     * （见 {@code shouldPrepare}），因为用户明确要求「不要留到最后再开始」。
     * 它现在的语义是「**这一档最坏要跑多久**」——按当前接法给一个保守值，
     * 用于日志与「准备没赶上触发点」那条诊断（回答「这一档的预算是多少、实际花了多少」）。
     *
     * <p>数值仍是引擎按设置算出来的（30 / 90 s，见 {@code AutomixSettings.prepareMarginSecsFor}）：
     * 要扫整曲的那几档（自动点位、AI 自动档）留 90 s，其余留 30 s。
     */
    private volatile long prepareMarginSecs = PREPARE_MARGIN_SECS;

    /**
     * 过渡开始那一刻，进场 deck 的剩余帧数（{@code null} = 长度未知）。
     *
     * <p>只为一个诊断：**交接连续性**（用户 2026-10-01 报的「融合段跑快、回到 B 时进度回跳」）。
     * 融合期里出声的是接法自己的素材（声波重合那一层是预渲染的），而 deck 也在按块推进——
     * 两者只要不一致，交接瞬间就会听到跳变。这里的读数 + {@link #transitionPos} 就能算出
     * 差了多少毫秒，不必再靠听感猜。
     */
    private Long introRemainingAtTransitionStart;

    /**
     * 上一次交接时「进场 deck 多渲染、但没被混音用掉」的帧数（0 = 没有这种事）。
     *
     * <p>它是 {@code renderTransition} 最后一块的余量：{@link Deck#render} 固定产出
     * {@code RENDER_FRAMES} 帧，窗口在块中间结束时，尾巴那截从没被听到——交接前会被
     * 退回（见那里的注释）。判据 {@code DirectorTest} 直接断言它是 0。
     */
    long lastHandoverUnusedFrames;

    /** 本次过渡里进场 deck 累计被渲染了多少帧（用来算交接时要退回多少）。 */
    private long transitionRenderedIntro;

    /**
     * 上一次交接时「进场 deck 前进的帧数 − 过渡真正产出的帧数」（0 = 完全一致）。
     *
     * <p>这就是日志里那句「B 前进 X s（素材推进 Y s，差 ±N ms）」的 N。用户 2026-10-01
     * 报的「进度回跳」量到的就是它，修法见 {@code renderTransition} 末尾的交接回退。
     * 判据 {@code DirectorTest} 断言它的绝对值 ≤ 2 帧（{@code seek} 落点有 1 帧粒度的可能）。
     */
    long lastHandoverDeltaFrames;

    /**
     * 上一次交接时「界面读到的位置」在交接前后的差（帧，0 = 不跳）。
     *
     * <p>用户 2026-10-01 报的进度回跳量到的就是它：过渡窗口里引擎读 {@link #nextFrames()}、
     * 交接之后读 {@link #currentFrames()}，本该逐帧相等。判据 {@code DirectorTest} 直接断言它是 0。
     */
    long lastHandoverPositionDeltaFrames;

    /** 同上，出场 deck 的（用来核「A 结束时应当只剩丢尾那么多」）。 */
    private Long outroRemainingAtTransitionStart;

    /** 上一次预告的队首是不是远端（只为了让「抬余量 / 回到原值」各记一行，见 replaceQueued）。 */
    private boolean sawRemoteNext;

    /**
     * 队首是<strong>远端音源</strong>（http/https）时的准备余量下限（秒）。
     *
     * <h3>为什么必须有这一条（用户 2026-09-30 报的「安卓上有的歌曲完全没有过渡」）</h3>
     *
     * <p>余量表是按**设置**定的（30 / 90 / 150 s，见 {@code AutomixSettings.prepareMarginSecsFor}），
     * 而它**看不见「下一首在哪儿」**：手机上的下一首经常还是那条带签名的直链（预取的音频
     * 还没落盘），此时准备阶段的每一次读——读尾段/头段、扫包络、开 deck——都是**网络读**，
     * 而网络读的耗时没法预估。默认那 30 s（自动点位关着的声波重合/交叉淡化就是这一档）
     * 在这种素材上必然不够：结果是「到了触发点却没有准备结果」→ 这一次过渡**根本不发生**。
     *
     * <p>取值与自动点位那一档一致（{@code AUTOCUE_PREPARE_MARGIN_SECS}）：那一档的理由
     * 也正是「手机上进场曲常常还是直链，这一次扫描等于把整首歌再下一次」。
     *
     * <p>只抬<strong>不降</strong>：远端→本地（预取落盘、句柄升级）之后回到设置给的那份值。
     *
     * <p><strong>2026-10-02 起它不再决定「什么时候开工」</strong>（开工时机改成「收到预告就开工」，
     * 见 {@code shouldPrepare}）：它现在只影响报出来的**预算**，以及日志里那句
     * 「这一档最坏要跑多久」。这条「远端读没法预估」的理由本身仍然成立。
     */
    public static final long REMOTE_PREPARE_MARGIN_SECS = 90L;

    /** 这个句柄是不是远端音源。判字符串就够了：解码层只认 http/https 前缀。 */
    public static boolean isRemoteSource(String source) {
        if (source == null) {
            return false;
        }
        String s = source.trim().toLowerCase(java.util.Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /** 调整准备预算（秒）。给「准备阶段要跑模型」的接法用，见 {@link #prepareMarginSecs}。 */
    public void setPrepareMarginSecs(long secs) {
        this.prepareMarginSecs = secs > 0L ? secs : PREPARE_MARGIN_SECS;
    }

    /** 当前准备预算（秒）：远端队首至少 {@link #REMOTE_PREPARE_MARGIN_SECS}。 */
    public long prepareMarginSecs() {
        long base = prepareMarginSecs;
        return isRemoteSource(queuedNextPath())
                ? Math.max(base, REMOTE_PREPARE_MARGIN_SECS) : base;
    }

    // ==================================================================
    // 重活探针（上游 crate::probe 的 Java 替身，见类注释）
    // ==================================================================

    /** {@code Deck.open}：开文件、探容器、分配缓冲、建重采样核表。上游 {@code Heavy::Open}。 */
    static final String HEAVY_OPEN = "Open";

    /** {@code Deck.seek}：解码器定位。上游 {@code Heavy::Seek}。 */
    static final String HEAVY_SEEK = "Seek";

    /** 造一个变调器。上游 {@code Heavy::PitchShifter}。 */
    static final String HEAVY_PITCH_SHIFTER = "PitchShifter";

    /**
     * 一条重活记录。字段与上游 {@code probe::Record} 一一对应。
     *
     * <p>{@code source} 与文件无关的重活（造变调器）记空串，同上游。
     */
    static final class HeavyRecord {

        /** 哪件活（{@link #HEAVY_OPEN} / {@link #HEAVY_SEEK} / {@link #HEAVY_PITCH_SHIFTER}）。 */
        final String kind;

        /** 做这件事的线程名。上游是 {@code std::thread::current().name()}，Java 的名字不会是 null。 */
        final String thread;

        /** 针对哪个音源句柄（造变调器时是空串）。 */
        final String source;

        HeavyRecord(String kind, String thread, String source) {
            this.kind = kind;
            this.thread = thread;
            this.source = source;
        }

        @Override
        public String toString() {
            return "HeavyRecord{kind=" + kind + ", thread=" + thread + ", source=" + source + '}';
        }
    }

    /**
     * 重活记录表。<strong>{@code null} = 关闭</strong>（生产路径上的默认值）。
     *
     * <p>包私有，只给同包的 {@code DirectorTest} 用——理由与 {@code AiMix.window} /
     * {@code Automix.togetherAngle} 的包私有一样：上游的探针是 {@code cfg(test)} 的，
     * Java 没有那个机制，只能靠可见性把它挡在公开 API 之外。
     *
     * <p>测试自己建一个列表（要用 {@code Collections.synchronizedList}：准备线程会往里写），
     * 断言前 {@code synchronized} 着拷一份出来。
     */
    static List<HeavyRecord> heavyProbe;

    /** 记一笔重活。{@code heavyProbe == null} 时什么都不做（生产路径只剩一次判空）。 */
    private static void recordHeavy(String kind, String source) {
        List<HeavyRecord> log = heavyProbe;
        if (log == null) {
            return;
        }
        synchronized (log) {
            log.add(new HeavyRecord(kind, Thread.currentThread().getName(), source));
        }
    }

    /** 开一个 deck，并记一笔探针（上游的探针在 {@code Deck::open} 里，含失败的那次）。 */
    private static Deck openDeckWithHeaders(String source, int outRate, int outChannels,
                                            HeadersLookup lookup) {
        // 这笔探针是 DirectorTest 判「开流是否落在准备线程上」的唯一凭据：
        // 换成带请求头的 open 时漏掉过它一次，两条测试立刻红（2026-09-24）。
        recordHeavy(HEAVY_OPEN, source);
        return Deck.open(source, headersOf(lookup, source), outRate, outChannels);
    }

    /**
     * <b>把"窗口处理票"装到 deck 上</b>（2026-10-07，目标②路径 A 的最后一环）。
     *
     * <p>为什么在这里：引擎只有这里同时握着 **deck** 与 **transition** ——
     * deck 负责交付素材、transition 知道几何（叠唱区间 / 窗口 / 分离档位）。
     * 上层（{@code AutoCueTransition.prepare}）已经把决策做成票，这里只负责**递过去**。
     *
     * <p>没有票 / 票是 identity / 结构对不上 ⇒ **什么都不装**（deck 的钩子保持 null ⇒ 零变化）。
     */
    private static void installMaterialHook(Deck deck, Transition engine, int rate) {
        if (deck == null || engine == null || rate <= 0) {
            return;
        }
        try {
            Transition inner = engine;
            if (inner instanceof VocalSeparationTransition) {
                Transition wrapped = ((VocalSeparationTransition) inner).inner();
                if (wrapped != null) {
                    inner = wrapped;
                }
            }
            if (!(inner instanceof AutoCueTransition)) {
                return;
            }
            AutoCueTransition cue = (AutoCueTransition) inner;
            WindowTicket ticket = cue.windowTicket();
            if (ticket == null || !ticket.doesAnything()) {
                return;                      // 没事可做 ⇒ 不装（一个采样都不动）
            }
            deck.setMaterialHook(new WindowMaterialHook(ticket, cue.installedVocalMode(), rate));
        } catch (RuntimeException e) {
            // 装不上就如实不装（不猜、也不让准备过程因此失败）
        }
    }

    private static Deck openDeck(String source, int outRate, int outChannels) {
        return openDeckWithHeaders(source, outRate, outChannels, null);
    }

    /** 造一个「以单位比例挂着」的变调器，并记一笔探针（上游在 {@code new_pitch_shifter} 里记）。 */
    private static PitchShifter idlePitchShifter(int outRate, int outChannels) {
        recordHeavy(HEAVY_PITCH_SHIFTER, "");
        return Deck.idlePitchShifter(outRate, outChannels);
    }

    // ==================================================================
    // 字段
    // ==================================================================

    private final int outRate;

    private final int outChannels;

    /**
     * 过渡窗口长度（帧）。0 表示硬接。
     *
     * <p><strong>准备结果到手时会被重读一次</strong>——对拍会把窗口拉长到整数拍。
     */
    private long window;

    /**
     * 过渡的<strong>触发提前量</strong>（帧）：出场曲还剩这么多时就要开始交叠。
     *
     * <p>平时等于 {@link #window}（在曲尾融合）。「自动点位」选中的融合点可能不在曲尾——
     * 那时出场曲有一段尾巴会被丢掉（{@code TransitionPlan.outroTailSecs}），
     * 触发点必须把这段尾巴算进去，否则等到只剩一个窗口时，融合点早就被曲尾占满了。
     */
    private long triggerLead = -1L;

    /** 上一次过渡真正渲染的帧数（诊断用：调用方拿它报「实际交叠多久」）。 */
    private long lastTransitionFrames;

    /** 「开始交叠」那一行是否已经记过（触发条件每块都成立，不能每块都打印）。 */
    private boolean triggerReported;

    /**
     * 「到了触发点却没有可用的准备结果」这条日志是不是已经报过（这一次准备里只报一次）。
     *
     * <p>为什么需要它：那条路径从前是<strong>完全静默</strong>的——{@code pump} 每块都会问
     * 「该不该开始过渡」，没有准备成果时 {@code beginTransition} 直接返回（这是有意的，
     * 见那里的注释），于是「这一次过渡根本没发生」在日志里连一行都没有：用户看到的只有
     * 曲终那次硬接，而分析慢了多少、准备线程跑完没有，一概看不出来。实测里
     * 「开了自动点位反而没过渡」（手机端）与「最贵那一档准备好了但没过渡」（桌面端）
     * 都是这么被发现的——两条都得靠日志里别的行去反推。
     */
    private boolean triggerMissReported;

    /**
     * 这一对已经重来过几次准备（失败重试用，见 {@link #pollPreparation}）。
     *
     * <p>为什么要限次：`shouldPrepare()` 的条件「剩余 ≤ 触发提前量 + 余量」一旦成立就**一直成立**
     * （剩余只会变小），所以一次失败之后若不限次，就会「失败 → 重开 → 再失败 → 再重开」，
     * 在触发点之前把 CPU 白烧掉。给一次机会就够——真正的价值是「差一次重试」与「整首硬接」
     * 的差别，而不是无限重试。换队首（{@code replaceQueued}）时清零。
     */
    private int prepareRetries = 0;

    /** 这一对最多重来几次准备。 */
    private static final int MAX_PREPARE_RETRIES = 1;

    /**
     * <strong>渲染用的实例。</strong> 只在渲染路径上用，绝不在它上面跑 {@code prepare}——
     * 那正是这次要修的卡顿（见类注释）。
     */
    private Transition transition;

    /** 造准备用实例的工厂。 */
    private final TransitionFactory factory;

    /** 当前在放的 deck。 */
    private Deck current;

    /** 已就位的下一首（过渡开始后才存在）。 */
    private Deck next;

    /** 待播队列。<strong>归生产者线程独占</strong>（上游同样是 {@code VecDeque} + 快照）。 */
    private final ArrayDeque<String> queue;

    /** 打不开、被跳过的音源及其原因。 */
    private final List<Skipped> skipped;

    /** 刚播过的曲目，供「上一首」回溯。最近的排在末尾。 */
    private final List<String> history;

    /** 过渡已进行的帧数；只在 {@link #transitioning} 为真时有意义。 */
    private long transitionPos;

    /**
     * 是否正在过渡窗口内。
     *
     * <p>上游是 {@code transition_pos.is_some()}。拆成两个字段的理由见类注释
     * （{@code Option<u64>} 在 Java 里要装箱，而这是每块渲染都会写的字段）。
     */
    private boolean transitioning;

    /**
     * 本次「这一段」是否已经<strong>发起过</strong>准备。
     *
     * <p>标的是「发起」而不是「完成」：准备是异步的，没有它每一块音频都会再起
     * 一条线程。它还顺带挡住「过渡进行中还去做下一次准备」——那时 {@code current}
     * 还是出场曲、队首却已经是进场曲之后的那一首，分析出来的是一份张冠李戴的结论。
     */
    private boolean prepared;

    /** 已就绪、还没换上来的准备结果。 */
    private PreparedTransition ready;

    /** 在跑（或已被作废）的准备任务。{@code null} 表示没有准备线程在跑。 */
    private PrepJob prep;

    /**
     * 准备世代号。<strong>每次作废准备时自增。</strong>
     *
     * <p>线程带着出生时的世代号把结果发回来，对不上就丢掉。
     * 没有它就会出现「拿 A 的结论处理 B」：用户切歌/跳转/上一首之后，
     * 上一次为队首 A 算出来的 {@code skip_secs} 与变调会被施加到 B 上——
     * 跳到 B 里一个凭空的时刻、给它转一个凭空的调。上游为同类问题修过一次
     * （见 {@link PreparedTransition#preparedIntro} 那段注释），这里是那次
     * 的异步版：结论还在路上时，用户就已经切走了。
     */
    private long generation;

    /**
     * <strong>正在进行的这次过渡</strong>实际生效的安排，供界面显示。
     *
     * <p>只在过渡窗口内有意义，过渡一结束就清空（见 {@link #finishTransition}）：
     * {@link #transitionNote()} 的读法是「这里非空就用它，否则用已就绪的那一份」，
     * 留着一份旧结论会把「下一次怎么接」永久顶掉。
     */
    private TransitionPlan activePlan;

    /**
     * 空间音频开关。<strong>新建的 deck 必须继承这个值</strong>——否则切换曲目后
     * 空间音频悄悄失效，用户以为开着其实没开。
     */
    private boolean spatialEnabled;

    /**
     * 工作室处理链（每 deck 一台机架）。
     *
     * <p>角色的含义与界面上一致：**出场曲机架 = 正在放的那一首**（过渡窗口里就是淡出的
     * 那一首），**进场曲机架 = 即将进来的那一首**（只有过渡窗口里才会响）。
     * 所以「当前曲」的 EQ 平时就在生效，而「下一曲」的只在交接那几秒生效——
     * 这也正是自动混音需要的：A 低音轰、B 高频刺时各自修各自的。
     */
    private dev.t1m3.qplayer.audio.studio.StudioProcessor studio;

    /** 过渡期间两个 deck 的暂存（构造时一次性分配，之后不再分配）。 */
    private final AudioBuf curBuf;

    private final AudioBuf nxtBuf;

    /** 渲染 deck 用的暂存（一块）：先渲染到这里，再按需接到 {@link #curBuf}/{@link #nxtBuf} 后面。 */
    private final AudioBuf renderA;

    private final AudioBuf renderB;

    /**
     * 交接余量：进场 deck 已经渲染、但还没交付给输出的那些帧。
     *
     * <p>为什么需要它：{@code Deck.render} 一次固定产出一整块（4096 帧），而过渡的收尾块
     * 常常只用得到一部分；多出来的那些帧如果丢掉，就得用 {@code seek} 把 deck 退回来
     * （2026-10-01 第一版就是这么做的，结果是**界面位置在交接处往回退**——用户报的进度回跳）。
     * 现在改成把它**留到下一块放出来**：一帧不丢，也不需要动位置。
     */
    /** 【接缝交叉 ✓】过渡最后 5 ms 的输出（每声道 ✓ 最多 8 声道）。 */
    private final float[] seamTail = new float[8 * 512];
    private int seamTailFrames;
    /** 「没耗尽却一直出 0 帧」只记一次日志（2026-10-11 ✓ 播一半消失那个 bug ✓）。 */
    private boolean stallLogged;
    /** 过渡响度补偿的平滑状态（2026-10-11 ✓ 每块最多走 0.5 dB ✓ 不用瞬时比值 ✗）。 */
    private double loudnessCompState = 1.0;
    /** 【电平接缝 ✓】过渡最后**一整块**的 RMS（4096 帧 ≈ 85 ms ✓ 比 5 ms 稳得多 ✓）。 */
    private float seamBlockRms;

    /** 【接缝测量 ✓】交接后第一块到达时，与"过渡最后交付的样本"比一次。 */
    private boolean seamPending;

    /** 【窗口诊断 ✓】只打前 3 次。 */
    private static int windowDiag;

    /** 过渡开始时 B deck 的帧位置（"画布原点" ✓ 参照 am_render.c：整段预先拉伸后匀速播 ✓）。 */
    /** 过渡能量日志的分块计数（每 25 块 ≈ 100 ms ✓）。 */
    private int energyBlockCount;

    /** 响度补偿日志的节流计数 ✓。 */
    private int loudnessLogCount;

    /**
     * **当前曲的持续增益**（响度对齐 ✓ 用户 2026-10-09：「响度一会大一会小」✓）。
     *
     * <p>为什么需要它：每首歌的母带电平本来就不同 ✗（实测 A -11.2 dB / B **-30.6 dB**，
     * 差 **19 dB** ✓）。过渡窗口内补偿只治重叠段 ✓，交接后 B 用原始电平播 ✗
     * ⇒ 掉 10.6 dB ⇒ 听感"跳一下"✓。
     * <p>做法：交接那一刻按「A 的实际电平 / B 的实际电平」定一个增益 ✓，
     * **一直跟到这首播完** ✓（下次交接再重算 ✓）⇒ 换歌不再有响度台阶 ✓。
     * 默认 1.0 ✓（单曲播放、判据里都不受影响 ✓）。
     */
    private float currentDeckGain = 1f;

    /** 交接时实测的 A / B 电平（响度对齐用 ✓）。 */
    private float lastRmsA;
    private double rmsSumA;
    private double rmsSumB;
    private int rmsCount;
    private static final float levelSamples = 0f;
    private float lastRmsB;

    /** 释放进度（帧 ✓）：B 的补偿在 {@link #DECK_GAIN_RELEASE_SECS} 内线性回到 1.0 ✓。 */
    private long deckGainReleaseFrames;

    /** 补偿释放时长（用户 2026-10-09 选 B ✓）：30 s 内缓慢回到原电平 ✓ 不是硬台阶 ✓。 */
    private static final double DECK_GAIN_RELEASE_SECS = 30.0;

    /** 一段缓冲的 RMS（多声道平均 ✓；不动缓冲 ✓）。 */
    private static double rmsOf(AudioBuf buf, int frames) {
        if (buf == null || frames <= 0) {
            return 0.0;
        }
        double acc = 0.0;
        int cnt = 0;
        for (int c = 0; c < Math.min(buf.channels(), 2); c++) {
            int base = buf.channelBase(c);
            for (int i = 0; i < frames; i++) {
                double v = buf.backingArray()[base + i];
                acc += v * v;
                cnt++;
            }
        }
        return cnt > 0 ? Math.sqrt(acc / cnt) : 0.0;
    }

    /** 原地缩放一段缓冲（响度补偿用 ✓ 调用方负责还原 ✓）。 */
    /** 把当前曲的持续增益乘到输出上（只影响这一块 ✓）。 */
    private void applyDeckGain(AudioBuf out, int frames) {
        float[] arr = out.backingArray();
        int limit = Math.min(frames, out.frames());
        final long total = Math.max(1L, Math.round(DECK_GAIN_RELEASE_SECS * (double) Math.max(1, outRate)));
        for (int i = 0; i < limit; i++) {
            // 逐样本释放（块之间连续 ✓）：30 s 线性回到 1.0 ✓
            double t = Math.min(1.0, deckGainReleaseFrames / (double) total);
            float g = (float) (currentDeckGain + (1.0 - currentDeckGain) * t);
            for (int c = 0; c < out.channels(); c++) {
                arr[out.channelBase(c) + i] *= g;
            }
            deckGainReleaseFrames++;
        }
    }

    private static void scaleBuf(AudioBuf buf, float gain) {
        if (buf == null || gain == 1f) {
            return;
        }
        int frames = buf.frames();
        float[] arr = buf.backingArray();
        for (int c = 0; c < buf.channels(); c++) {
            int base = buf.channelBase(c);
            for (int i = 0; i < frames; i++) {
                arr[base + i] *= gain;
            }
        }
    }

    private static double db(double rms) {
        return 20.0 * Math.log10(Math.max(rms, 1e-9));
    }

    private long introDeckStartFrames = -1L;

    private final AudioBuf carryBuf;

    /** 【接缝诊断 ✓ 2026-10-09】过渡最后一次交付的尾样本（每声道 ✓）与它的「一阶差分基线」✓。 */
    private final float[] lastDelivered = new float[8];
    private float lastDeliveredDelta;
    private long lastDeliveredAt = -1;

    // ==================================================================
    // 嵌套类型
    // ==================================================================

    /**
     * 一条被跳过的音源及原因。
     *
     * <p>上游是 {@code Vec<(PathBuf, String)>}；Java 没有元组，这里做成一个不可变的
     * 值类（与 {@link Mixer.Gains} 同一处理），字段名取自上游那段注释里的说法。
     */
    public static final class Skipped {

        /** 打不开（或定位失败）的音源句柄。 */
        public final String source;

        /** 为什么。上游那条注释：界面靠它告诉用户「这个文件为什么没放」。 */
        public final String why;

        Skipped(String source, String why) {
            this.source = source;
            this.why = why;
        }

        @Override
        public String toString() {
            return "Skipped{source='" + source + "', why='" + why + "'}";
        }
    }

    /**
     * 在跑（或已被作废）的一次准备。
     */
    private static final class PrepJob {

        /**
         * 结果通道。<strong>只 {@code poll()}，绝不 {@code take()}。</strong>
         *
         * <p>容量 1：一个任务只会送一份结果（上游一个 {@code mpsc} 同样只 {@code send} 一次）。
         */
        final BlockingQueue<PreparedTransition> rx;

        /** 出生时的世代号。回来时对不上就丢弃。 */
        final long generation;

        /** 线程还在跑。<strong>编排器只靠它</strong>判断槽位什么时候能再用。 */
        final AtomicBoolean busy;

        PrepJob(BlockingQueue<PreparedTransition> rx, long generation, AtomicBoolean busy) {
            this.rx = rx;
            this.generation = generation;
            this.busy = busy;
        }
    }

    /**
     * 准备线程交回来的、<strong>随时能开播</strong>的下一首。
     *
     * <p>它是一整份结果，七样东西<strong>同进同出</strong>：引擎、计划、已经打开并定位好的
     * deck、队列消耗的账、以及预先造好的变调器。分成两次送就会出现「计划换了、deck 没换」
     * 这类错位，而那正是「拿 A 的结论处理 B」的形状。
     */
    private static final class PreparedTransition {

        /** 准备线程造出来的新实例。过渡开始时用它<strong>换掉</strong>渲染实例。 */
        final Transition engine;

        /** 分析结论。{@code Deck.seek} 已经按它做完了，安装时只用来记账与显示。 */
        final TransitionPlan plan;

        /**
         * 这份结论是<strong>针对哪一首进场曲</strong>做的。
         *
         * <p>分析看的是队首，而打开 deck 时会跳过打不开的文件——两者可能不是
         * 同一个文件。记下来才能发现这种错位。
         */
        final String preparedIntro;

        /**
         * <strong>已经在准备线程打开、定位好的进场 deck。</strong>
         *
         * <p>它才是这次修复的核心：{@code Deck.open}（开文件、探容器、分配缓冲、
         * 建重采样核表）与对拍定位原本发生在开始过渡的那一瞬间、在生产者线程上。
         * {@code null} 表示快照里剩下的文件一个都打不开。
         */
        final Deck deck;

        /**
         * 准备线程从队列快照里<strong>取走</strong>的音源，按顺序，最后一个就是它打开的那个。
         *
         * <p>安装时按这个前缀弹 {@link #queue}——{@code openNext} 的语义，
         * 只是执行它的时刻从「开始过渡」挪到了「安装」。
         */
        final List<String> took;

        /**
         * 要并进 {@link #skipped} 的记录：打不开的文件、以及定位失败的进场曲。
         *
         * <p><strong>不能省。</strong> {@code openNext} 会把打不开的文件连原因一起记下来
         * 给界面看，搬走之后这条账只有准备线程记得住，不并回来就等于把
         * 「为什么跳过它」丢了。
         */
        final List<Skipped> recorded;

        /**
         * 给<strong>出场 deck</strong> 预先造好的变调器（初始比例 1）。{@code null} = 这次不需要变调。
         *
         * <p>造它要分配环形缓冲并铺粒窗，而这以前也落在开始过渡的那一瞬间。
         * 安装时只把它交给 {@link Deck#engagePitchWith(PitchShifter)}，那是<strong>一次字段赋值</strong>。
         */
        final PitchShifter outroPitch;

        PreparedTransition(Transition engine, TransitionPlan plan, String preparedIntro, Deck deck,
                           List<String> took, List<Skipped> recorded, PitchShifter outroPitch) {
            this.engine = engine;
            this.plan = plan;
            this.preparedIntro = preparedIntro;
            this.deck = deck;
            this.took = took;
            this.recorded = recorded;
            this.outroPitch = outroPitch;
        }
    }

    /**
     * 「音源句柄 → 它需要的请求头」的查表接缝。
     *
     * <p>为什么需要它：这一层要自己开解码器读文件做分析（对拍、判调性、自动点位扫整曲），
     * 而插件直链常常必须带 token/Referer。宿主（{@code PlaybackEngine}）在
     * {@code play}/{@code setNextSource} 时就已经拿到了那份头，这里按句柄问回来即可。
     *
     * <p>默认（没装）返回空表：只实现老 API 的宿主、以及所有测试夹具的行为一字不变。
     */
    public interface HeadersLookup {
        java.util.Map<String, String> headersFor(String source);
    }

    private HeadersLookup headersLookup;

    /** 装上请求头查表（宿主在造好编排器之后立刻调用一次）。 */
    public void setHeadersLookup(HeadersLookup lookup) {
        this.headersLookup = lookup;
    }

    /** 某个句柄需要的请求头；没装查表或查不到时返回空表（绝不返回 null）。 */
    private java.util.Map<String, String> headersFor(String source) {
        return headersOf(headersLookup, source);
    }

    /** 表本身可能为 {@code null}（宿主没装）：静态形式，供准备线程与静态开 deck 用。 */
    private static java.util.Map<String, String> headersOf(HeadersLookup lookup, String source) {
        if (lookup == null || source == null) {
            return java.util.Collections.emptyMap();
        }
        java.util.Map<String, String> h = lookup.headersFor(source);
        return h == null ? java.util.Collections.<String, String>emptyMap() : h;
    }

    /** {@link #openPreparedDeck} 的产出。上游是 {@code PreparedDeck}。 */
    private static final class PreparedDeck {

        /** 开好的 deck。{@code null} = 快照里的文件一个都打不开。 */
        final Deck deck;

        /** 从快照里取走的音源，按顺序。安装时按这个前缀弹真队列。 */
        final List<String> took;

        /** 要并进 {@link #skipped} 的记录（打不开的文件及原因）。 */
        final List<Skipped> recorded;

        PreparedDeck(Deck deck, List<String> took, List<Skipped> recorded) {
            this.deck = deck;
            this.took = took;
            this.recorded = recorded;
        }
    }

    // ==================================================================
    // 构造与访问器
    // ==================================================================

    /**
     * 构造编排器。{@code tracks} 的第一个能打开的元素作为当前曲，其余进队列。
     *
     * <p>{@code factory} 必须能<strong>反复造出等价的新实例</strong>（同一个接法、同样的
     * 采样率与声道数）。渲染用的那个实例由它在这里造一次，准备用的实例由准备线程再造。
     *
     * <p><strong>收工厂而不是收一个现成的引擎</strong>是有意的：分别传「一个实例」和
     * 「一个工厂」的话，两者可以不一致（名字、窗口、接法都对不上），
     * 而这种漂移没人能一眼看出来。模式只允许有一处真源。
     *
     * <p>打不开的文件会被<strong>跳过并记录</strong>，而不是让整个播放失败——
     * 一个坏文件不该毁掉整张专辑的播放。队列里一个能打开的都没有时才抛
     * {@link AudioException}（上游 {@code SonalisError::Config}）。
     *
     * <p>上游这里是泛型 {@code new<F>(..) where F: Fn() -> Box<dyn Transition>}；
     * Java 对应 {@link TransitionFactory}（见它的类注释）。
     *
     * @param tracks      音源句柄列表，第一个能打开的作为当前曲
     * @param outRate     输出采样率
     * @param outChannels 输出声道数
     * @param factory     过渡引擎工厂，不能为 {@code null}
     */
    public Director(List<String> tracks, int outRate, int outChannels, TransitionFactory factory) {
        if (factory == null) {
            // 上游的类型系统不允许传 null；Java 允许，所以把这条前置条件显式化
            // （与 Deck.engagePitchWith 同一处理）。
            throw new IllegalArgumentException("过渡工厂不能为 null：模式只允许有一处真源");
        }
        this.outRate = outRate;
        this.outChannels = outChannels;
        // 渲染用的实例在这里造一次：工厂是**唯一**的真源
        Transition engine = factory.create();
        this.transition = engine;
        this.factory = factory;
        this.window = engine.windowFrames(outRate);
        this.triggerLead = this.window;
        // 必须与 `Deck` 的单次产出量一致：编排器给两个 deck 的缓冲就是
        // 它们的产出目标，给小了 `Deck.render` 会直接报错。
        int cap = Deck.RENDER_FRAMES;
        ChannelLayout layout = ChannelLayout.of(outChannels);

        Iterator<String> it = tracks.iterator();
        List<Skipped> skipLog = new ArrayList<Skipped>();

        // 找第一个能打开的作为当前曲
        Deck opened = null;
        while (it.hasNext()) {
            String p = it.next();
            try {
                opened = openDeckWithHeaders(p, outRate, outChannels, headersLookup);
                break;
            } catch (AudioException e) {
                // 上游是 `e.to_string()`（Display）；Java 的对应物是 getMessage()
                // （toString() 会多带异常类名，比照 AiMix 的先例）。
                skipLog.add(new Skipped(p, e.getMessage()));
            }
        }
        if (opened == null) {
            throw AudioException.config("队列里没有任何可播放的文件");
        }

        this.current = opened;
        this.next = null;
        this.queue = new ArrayDeque<String>();
        while (it.hasNext()) {
            this.queue.addLast(it.next());
        }
        this.skipped = skipLog;
        this.history = new ArrayList<String>();
        this.transitionPos = 0L;
        this.transitioning = false;
        this.prepared = false;
        this.ready = null;
        this.prep = null;
        this.generation = 0L;
        this.activePlan = TransitionPlan.NONE;
        this.spatialEnabled = false;
        // **容量是两块**：deck 一次渲染一块（{@code Deck.RENDER_FRAMES}），而上块没被混音
        // 用掉的余量要留在缓冲里等下一块（见 renderTransition 的「按需渲染 + 消费」）——
        // 装不下就会丢帧，丢帧就得靠 seek 把 deck 退回来，那正是用户报的「进度回跳」。
        this.curBuf = new AudioBuf(layout, Math.max(cap, 2 * Deck.RENDER_FRAMES));
        this.nxtBuf = new AudioBuf(layout, Math.max(cap, 2 * Deck.RENDER_FRAMES));
        this.renderA = new AudioBuf(layout, Deck.RENDER_FRAMES);
        this.renderB = new AudioBuf(layout, Deck.RENDER_FRAMES);
        this.carryBuf = new AudioBuf(layout, 2 * Deck.RENDER_FRAMES);
    }

    /** 输出采样率。 */
    public int outRate() {
        return outRate;
    }

    /** 过渡窗口长度（帧）。 */
    /**
     * 上一次过渡真正渲染的帧数。
     *
     * <p>比「按块边界估的差值」准：那个估法天生少一到两块（实测少 142 ms），
     * 而这一个就是判据本身用的计数。过渡还没发生过时是 0。
     */
    public long lastTransitionFrames() {
        return lastTransitionFrames;
    }

    public long windowFrames() {
        return window;
    }

    /**
     * 过渡的<strong>触发提前量</strong>（帧）：当前曲还剩这么多时就开始交叠。
     *
     * <p>平时等于 {@link #windowFrames()}。「自动点位」把融合点选在曲中时，
     * 计划里会带上「被丢掉的尾巴」（{@code TransitionPlan.outroTailSecs}），
     * 这里就跟着变大——它同时决定了「什么时候开始准备」与「什么时候进入过渡」。
     * 诊断与测试用它；渲染路径不读。
     */
    public long triggerLeadFrames() {
        return triggerLead();
    }

    /** 过渡窗口长度（秒）。 */
    public float windowSecs() {
        return (float) window / (float) Math.max(outRate, 1);
    }

    /** 当前曲目事实。 */
    public TrackSpec currentSpec() {
        return current.spec();
    }

    /**
     * 当前曲目的音源句柄。
     *
     * <p><strong>从 deck 取，不另存一份</strong>——这样不可能出现「记录的路径与正在解码的
     * 文件不是同一个」。界面显示曲名、日志定位问题都靠它。
     */
    public String currentPath() {
        return current.source();
    }

    /** 已就位的下一首的句柄（只在过渡开始后存在）。 */
    public String nextPath() {
        Deck d = next;
        return d == null ? null : d.source();
    }

    /** 已就位的下一首的事实。 */
    public TrackSpec nextSpec() {
        Deck d = next;
        return d == null ? null : d.spec();
    }

    /** 队首的句柄（还没打开，但这是「接下来要放的那一首」）。 */
    public String queuedNextPath() {
        return queue.peekFirst();
    }

    /**
     * 换掉「队首」——还没打开、也还没开始准备的那一首。
     *
     * <h3>为什么需要它（而不是让引擎重建整个编排器）</h3>
     *
     * <p>引擎每收到一次新的「下一首预告」都要把它交给编排器，而编排器的队列在构造时就定下了，
     * 于是原先的做法是<strong>重建一整个编排器</strong>——那要<strong>重新打开当前曲并定位</strong>。
     * 本地文件上那是一两毫秒，在安卓的网络流上是几百毫秒到几秒：这期间引擎一个样本都产不出来，
     * 环形缓冲（约 200 ms）被抽干、由 {@code OutputFiller} 补静音——用户听到的就是
     * 「放得好好的莫名其妙卡一下」，歌词时间线跟着顿一下（2026-09-20 实测报告）。
     *
     * <p>而「重新预告」这件事<strong>根本不需要碰当前曲</strong>：要换的只是「接下来接哪一首」。
     * 这个方法就只做那一件事：换掉队列里的那一个、把手上可能已经备好的（属于<strong>旧队首</strong>的）
     * 准备结果作废。当前 deck、渲染状态、位置账目一律不动，所以它没有可听代价。
     *
     * <p><strong>过渡进行中拒绝</strong>（{@code transitioning}）：那一刻队首正在被交接，
     * 换掉它等于让交接去接一首没人预告过的曲子。返回 {@code false}，由调用方决定怎么办
     * （引擎那边会记下来、等这次交接完再换）。
     *
     * @return 换成功（或本来就已经是它）返回 {@code true}；此刻不能换返回 {@code false}
     */
    public boolean replaceQueued(String source) {
        if (source == null || source.isEmpty() || transitioning) {
            return false;
        }
        if (source.equals(queue.peekFirst())) {
            return true;
        }
        queue.clear();
        queue.addLast(source);
        // **远端队首要留够时间**（见 REMOTE_PREPARE_MARGIN_SECS）：换句柄是唯一会改变
        // 「准备余量够不够」的地方（本地文件 → 直链、直链 → 落盘的缓存），所以在这里记一行，
        // 让「这一次为什么提前开始分析 / 为什么余量变了」在日志里查得到。
        if (isRemoteSource(source) && prepareMarginSecs < REMOTE_PREPARE_MARGIN_SECS) {
            Logger.debug("[qplayer-engine] 自动混音：下一首是远端音源（还没落盘），准备余量抬到 {} s"
                    + "（设置里那一档是 {} s）——远端读取的耗时没法预估", Long.valueOf(
                    REMOTE_PREPARE_MARGIN_SECS), Long.valueOf(prepareMarginSecs));
        } else if (!isRemoteSource(source) && sawRemoteNext) {
            Logger.debug("[qplayer-engine] 自动混音：下一首已经落到本地（{}），准备余量回到设置里那一档 {} s",
                    source, Long.valueOf(prepareMarginSecs));
        }
        sawRemoteNext = isRemoteSource(source);
        // 新的一对：重试次数归零（见 prepareRetries）。
        prepareRetries = 0;
        // 旧队首可能已经有一份备好的结果（对拍、判调性都算过了）：那是**另一首**的，
        // 不作废就会拿着 A 的准备去接 B——比不换更糟。
        resetPreparation();
        return true;
    }

    /**
     * 把准备好的下一首收上来（<strong>不产音频</strong>），返回它是不是已经就位。
     *
     * <h3>它有副作用</h3>
     *
     * <p>名字是动词性的：它真的会改状态——做的事与 {@link #pump} 开头那一步一样
     * （收准备线程的结果、重读窗口），只是<strong>不产音频</strong>。
     *
     * <h3>谁需要它</h3>
     *
     * <p><strong>泵得比实时快得多的调用方</strong>：离屏渲染、调参台、测试。编排器在没有
     * 准备好的 deck 时会把过渡<strong>推迟</strong>（见类注释），而它们几毫秒就能泵完
     * 一秒音频，准备线程根本追不上——不等就会一路泵到曲子结束，
     * 渲出来「一次过渡都没有」。有了它就能「泵到该开始过渡的位置 →
     * 用这个方法等结果 → 继续泵」。
     *
     * <p><strong>实时播放不需要它</strong>：设备节奏把生产者线程限着，30 秒的余量对几秒的
     * 准备绰绰有余，去等反而是把卡顿请回来。
     *
     * <p>注意它<strong>不会</strong>发起准备（那是 {@link #pump} 里按剩余量判断的），
     * 所以调用方至少得先泵一块，否则会白等。
     */
    public boolean collectPreparation() {
        pollPreparation();
        PreparedTransition p = ready;
        return p != null && p.deck != null;
    }

    /**
     * 这次过渡在**进场曲里的落点**（秒）—— B 从这一秒开始放（诊断/对照点位用）。
     *
     * <p>为什么要暴露它：用户拿我们的点位与 Apple Music 的实测点位对比
     * （参考对 skydive→春めく 的实测是出场 181.1 s / **进场 15.0 s**），
     * 而在此之前从外面只能看到"窗口多大"，看不到**进场落点** ✗。
     */
    public double transitionEntrySecs() {
        return activePlan.skipSecs;
    }

    /**
     * 这次过渡**丢掉的出场尾巴**（秒）：出场侧落点 = A 时长 − 窗口 − 这个值。
     *
     * <p>与 {@link #transitionEntrySecs()} 配套，两个读数加起来就是"我们把融合点放在哪儿"。
     */
    public double transitionDroppedTailSecs() {
        return activePlan.outroTailSecs;
    }

    /** 过渡引擎的名字（界面显示用）。 */
    public String transitionName() {
        return transition.name();
    }

    /**
     * 这次过渡的分析结论，供界面显示。
     *
     * <p>例如「低音交接（128.0 → 124.5 BPM，窗口 8.0 s）」或
     * 「声波重合 · 转调 +2 半音（相似度 0.86 ← 0.55）」。
     */
    public String transitionNote() {
        if (activePlan.note.isEmpty()) {
            // 还没进过渡：先说已经算好的那一份（没有就算空）
            PreparedTransition p = ready;
            return p == null ? "" : p.plan.note;
        }
        return activePlan.note;
    }

    /** 当前曲已产出帧数（输出采样率下）。 */
    public long currentFrames() {
        // **上报"可听位置"**（2026-10-09 ✓ 用户：交接后 B 的进度从 25 瞬跳到 27 ✓）：
        // `outFrames()` 是**已渲染**位置 ✓ 比"已经放出来的"超前一段（渲染缓冲 ✓）
        // ⇒ 过渡期间与交接之后若用不同口径报，就会看到 2 秒级的跳 ✗（与声音无关 ✓）。
        // 这里统一扣掉"还没交付的余量"✓ ⇒ 前后一致、不会跳 ✓。
        long played = current.outFrames() - carryBuf.frames();
        return Math.max(0L, played);
    }

    /**
     * 这一次过渡有没有「只有 A 在响」的前导（秒；0 = 没有）。
     *
     * <p>见 {@code AutoCueTransition.outroLoopLeadInSecs()}：界面的口径要按它**延后切 B** ✓
     * （前导这十几秒里 B 的 deck 停在进场上限不动 ✓，引擎据此把"通知/位置/时长"一起顺延 ✓）。
     */
    public double outroLoopLeadInSecs() {
        Transition t = transition;
        return t == null ? 0.0 : t.outroLoopLeadInSecs();
    }

    /**
     * 进场 deck 的播放位置（输出采样率下的帧数，含对拍定位的起点）。
     *
     * <p><strong>只在过渡窗口内有意义</strong>：没有进场 deck 时返回 {@code -1}。
     * {@code -1} 而不是 0，是因为 0 是一个合法的位置（进场曲正好从第 0 帧开始）。
     *
     * <p>为什么要暴露它：过渡窗口里界面已经切到进场曲了（见
     * {@code AudioBackend#setOnTransitionStart}），引擎因此要按进场 deck 报位置与时长，
     * 否则进度条会在标题已经是 B 的时候还贴着 A 的结尾。它取的是 {@link Deck#outFrames()}
     * ——与交接之后 {@link #currentFrames()} 报的是<strong>同一个 deck 的同一份记账</strong>，
     * 于是交接那一刻位置连续、不回头。
     */
    public long nextFrames() {
        Deck d = next;
        if (d == null) {
            return -1L;
        }
        // 同一个"可听位置"口径 ✓（扣掉还没交付的缓冲 ✓）⇒ 交接前后连续 ✓
        long played = d.outFrames() - nxtBuf.frames() - carryBuf.frames();
        // **画布位置**（2026-10-09 ✓ 照参考实现 am_render.c：窗口素材先整段拉伸成"画布" ✓
        // 之后匀速播放 ✓）⇒ 窗口期 B 的位置 = **画布原点 + 已交付帧数** ✓
        // 与耳朵听到的内容**天然同域** ✓ ⇒ 界面/歌词不再跑到"下一句"✗，
        // 而交接后 `transitionPos` 停住、`currentFrames()` 接手 ✓ 两者在交接点相等 ✓ 不跳 ✓。
        if (transition != null && introDeckStartFrames >= 0L) {
            return Math.max(0L, introDeckStartFrames + transitionPos);
        }
        return Math.max(0L, played);
    }

    /** 队列里还剩多少首。 */
    public int queued() {
        return queue.size();
    }

    /**
     * 被跳过的文件及其原因。
     *
     * <p>上游返回的是借用切片（外部改不了），所以这里给一个不可修改视图——
     * 界面读得、写不得（与 {@link #skipped} 这份账只由编排器自己记）。
     */
    public List<Skipped> skipped() {
        return Collections.unmodifiableList(skipped);
    }

    /** 是否正在过渡。 */
    public boolean inTransition() {
        return transitioning;
    }

    /**
     * <b>离这次过渡开始还有多少秒</b>（负数 = 已经开始了多久）。
     *
     * <p>界面用它把「过渡提示」（经典歌词页进度条的发光）提前到开始之前——
     * 用户 2026-09-30 的要求：「让进度条发光在 automix 开始前三秒就有」。
     * 原先界面只能看 {@code inTransition}，而那一位恰好是<b>过渡开始那一刻</b>才翻的，
     * 于是提示与「界面已经跳到下一首」同时发生，等于没提示。
     *
     * <p>三种取值分得很清楚：
     *
     * <ul>
     *   <li><b>正数</b>：已经装载了过渡计划（{@link #ready}），离触发点还有这么多秒。
     *       提前量用的是 {@link #triggerLead()}——<b>它包含自动点位丢掉的尾巴</b>，
     *       所以「融合点被选在曲中」时提示同样准（那里离曲终不止一个窗口）；</li>
     *   <li><b>0 或负数</b>：正在过渡里（报负的剩余窗口时间），提示要一直亮着；</li>
     *   <li><b>{@code null}</b>：没有已装载的过渡（还没准备完 / 已经用掉 / 长度未知）——
     *       界面据此<b>不预告</b>。注意这与「曲终硬接」是两回事：没有计划就没有倒计时，
     *       绝不拿「离曲终还有多久」去冒充过渡提前量。</li>
     * </ul>
     */
    public Double transitionLeadInSecs() {
        int rate = Math.max(outRate, 1);
        if (transitioning) {
            long left = Math.max(0L, Math.max(window, 1L) - transitionPos);
            return Double.valueOf(-(double) left / (double) rate);
        }
        if (ready == null) {
            return null;
        }
        Long rem = current.remainingFrames();
        if (rem == null) {
            return null;
        }
        return Double.valueOf((double) (rem.longValue() - triggerLead()) / (double) rate);
    }

    /** 当前过渡进度（0.0 → 1.0）。不在过渡中时返回 {@code null}（上游 {@code None}）。 */
    public Float transitionProgress() {
        if (!transitioning) {
            return null;
        }
        long w = Math.max(window, 1L);
        float p = (float) transitionPos / (float) w;
        if (p < 0f) {
            p = 0f;
        } else if (p > 1f) {
            p = 1f;
        }
        return Float.valueOf(p);
    }

    /**
     * 当前时段的信号路径状态——<strong>界面上「直通 / 过渡中」那行字的唯一来源</strong>。
     *
     * <p>判定只看<strong>实际状态</strong>，不看「模式开关」——位完美段与过渡段的差别
     * 就是各处理单元是否处于单位状态（见 ADR-003 §2.2）。位完美因此是逐段生效的：
     * 只有过渡窗口那几秒让步，歌曲主体照常直通。
     *
     * <p>这也是「判定逻辑只允许有一份」的落点：UI 不得另算一套。三个分支与上游逐条对应，
     * 中间那一条借 {@link Deck#isPassthrough()} 与 {@link Deck#gainIsUnity()}，
     * 而那两个方法本身就是「各处理单元是否处于单位状态」的合成。
     *
     * <p>与 {@link Transition#rewritesSamples()} 的分工见类注释：那个是接法自己对
     * 「会不会改写采样值」的回答（上层用它标注信号路径），这里判的是「当前这一块
     * 是不是正落在那个窗口里」——两者在现有接法上给同一个答案。
     */
    public PathState pathState() {
        // **过渡必须排在工作室前面判**，顺序不能反。
        //
        // 这不是措辞问题：`PlaybackEngine` 用 `pathState() == TRANSITIONING` 当
        // 「过渡窗口开了」的**唯一判据**（见 `reportTransitionEntry`）。工作室在动采样时
        // 先返回 `PROCESSED` 的话，引擎就永远看不到「过渡」，于是：
        //   ① 过渡开始那条日志与 `onTransitionStart` 一次都不发——界面整窗停在出场曲上；
        //   ② `handedOverSource` 那枚一次性令牌从不落下，交接时控制器那次 `play(进场曲)`
        //      不被认，引擎**真的把这首歌从 0 重开一遍**（用户实测：「过渡完成后歌曲进度
        //      被重置回 0 从头播放」）；
        //   ③ `s.inTransition` 一直是 false，交接那条日志会说成「无缝换曲（本次没有过渡）」。
        // 「在过渡窗口里」是比「链上有处理」更具体、更该被报出来的事实；两件事同时为真时
        // 报过渡。窗口之外再报 `PROCESSED`（工作室确实在动采样，位完美如实熄灭）。
        if (transitioning) {
            return PathState.TRANSITIONING;
        }
        if (studio != null && studio.active()) {
            // 工作室在动采样：这条链**不是**位完美了。如实报，不许为了标记好看绕过去
            // （与音量走 deck 增益那一条同一条纪律）。
            return PathState.PROCESSED;
        }
        // 透传 + 增益单位 → 采样值原样通过
        if (current.isPassthrough() && current.gainIsUnity()) {
            return PathState.BIT_PERFECT;
        }
        return PathState.PROCESSED;
    }

    /**
     * 设置当前 deck 的增益（分贝）。
     *
     * <p>注意：<strong>非 0 dB 会让 {@link #pathState()} 变成 {@link PathState#PROCESSED}</strong>，
     * 因为增益是对采样值的乘加。这不是特判，是同一条判定逻辑的结果。
     */
    public void setGainDb(float db) {
        current.setGainDb(db);
    }

    /**
     * 设置空间音频（双耳渲染）开关。
     *
     * <p>同时作用于当前 deck 与已就位的下一首（过渡中它也在出声）。
     * 之后再打开的 deck 会在 {@link #openNext()} / {@link #skipPrev()} 里继承这个开关——
     * 漏掉任何一处，切歌后空间音频就会悄悄失效。
     */
    public void setSpatial(boolean enabled) {
        this.spatialEnabled = enabled;
        this.current.setSpatial(enabled);
        Deck n = next;
        if (n != null) {
            n.setSpatial(enabled);
        }
    }

    /** 空间音频开关的当前值。 */
    /**
     * 挂上工作室处理链（宿主在开流时挂一次，参数由它自己换）。
     *
     * <p>同一个对象：引擎换参数就是用这个对象换（{@code StudioProcessor.setRacks}），
     * 所以这里不需要转发参数，只要拿着引用就行。
     */
    public void setStudio(dev.t1m3.qplayer.audio.studio.StudioProcessor value) {
        this.studio = value;
    }

    /** 工作室链（测试与诊断用）。 */
    public dev.t1m3.qplayer.audio.studio.StudioProcessor studio() {
        return studio;
    }

    public boolean spatialEnabled() {
        return spatialEnabled;
    }

    // ==================================================================
    // 手动控制
    // ==================================================================

    /**
     * 跳到当前曲目的指定位置（秒），返回实际落点。
     *
     * <p>过渡进行中时<strong>先结束过渡</strong>：用户拖了进度条就不该继续混音，
     * 那会让他听到的与看到的对不上。
     *
     * <p>跳转同样要<strong>作废正在跑的准备</strong>：那份结论是为「还差 X 秒就到末尾」
     * 这个局面算的，跳转之后这个局面已经不存在了。作废的代价只是重算一次
     * （余量有 30 秒，来得及），而留着一份对不上的结论，早晚会演变成
     * 「拿 A 的结论处理 B」。
     */
    public double seek(double secs) {
        seamPending = false;   // 改位置后不许交叉到"新位置首块"✓
        if (transitioning) {
            finishTransition();
        }
        resetPreparation();
        clearPlaybackBuffers();
        return current.seek(secs);
    }

    /** 立刻切到下一首。返回 {@code false} 表示队列里没有能播的了。 */
    public boolean skipNext() {
        seamPending = false;   // 改位置后不许交叉到"新位置首块"✓
        // 过渡中按下一首 = 认可这次过渡的结果，直接完成它
        if (transitioning) {
            finishTransition();
            return true;
        }

        rememberCurrent();
        Deck deck = openNext();
        if (deck == null) {
            return false;
        }
        Deck old = current;
        current = deck;
        retire(old);
        clearPlaybackBuffers();
        resetPreparation();
        return true;
    }

    /**
     * 回到上一首。返回 {@code false} 表示没有历史可回。
     *
     * <p>当前曲会<strong>塞回队列最前面</strong>，所以再按「下一首」能原路返回 ——
     * 不然用户按两下就发现自己回不去了。
     */
    public boolean skipPrev() {
        seamPending = false;   // 改位置后不许交叉到"新位置首块"✓
        // 正在过渡时，出场曲就是「上一首」。
        // 先结束过渡把状态理清 —— 结束后它正好落在 history 末尾，
        // 于是下面那段回溯逻辑可以原样复用，不必为过渡单写一套。
        if (transitioning) {
            finishTransition();
        }

        if (history.isEmpty()) {
            return false;
        }
        String prev = history.remove(history.size() - 1);

        String cur = currentPath();
        queue.addFirst(cur);

        try {
            Deck deck = openDeckWithHeaders(prev, outRate, outChannels, headersLookup);
            deck.setSpatial(spatialEnabled);
            Deck old = current;
            current = deck;
            retire(old);
            clearPlaybackBuffers();
            resetPreparation();
            return true;
        } catch (AudioException e) {
            skipped.add(new Skipped(prev, e.getMessage()));
            return false;
        }
    }

    /**
     * 记下当前曲，供「上一首」使用。
     *
     * <p>历史只留最近 {@link #HISTORY_LIMIT} 首：一口气随机播放几小时，
     * 不设上限会让这里成为唯一还在增长的内存。
     */
    private void rememberCurrent() {
        String p = currentPath();
        // 连续同一首（过渡完成后再记一次）不该占两条
        if (history.isEmpty() || !history.get(history.size() - 1).equals(p)) {
            history.add(p);
            if (history.size() > HISTORY_LIMIT) {
                history.remove(0);
            }
        }
    }

    /**
     * 「重做这次过渡」：把已经跑完的准备结论作废、让下一块重新准备（P4 控制室的第三个动作）。
     *
     * <p>什么时候用得上：用户听了这一次过渡、觉得点位不对，改了手动点位，
     * 想让<b>这一曲的结尾</b>按新点位再来一次——不重做的话新的参数要等下一首才生效
     * （工厂是造编排器时定的，见 {@code AutomixSettings} 的类注释）。
     *
     * <p>它<strong>不打断正在进行的过渡</strong>（那会把已经混进来的几秒撕开）：
     * 过渡进行中时什么都不做，由调用方在窗口结束后再调。
     *
     * @return 真的作废了没有（过渡进行中、或本来就没准备过时返回 false）
     */
    public boolean reprepare() {
        if (transitioning) {
            return false;
        }
        if (!prepared && prep == null) {
            return false;
        }
        resetPreparation();
        return true;
    }

    /**
     * 清掉准备状态。换曲、跳转之后必然要重新准备。
     *
     * <p><strong>在跑的那次准备也一起作废</strong>（世代号自增）：它的结论是给换曲之前
     * 那个局面算的，用在换曲之后就是拿 A 的结论处理 B。
     *
     * <p>作废只<strong>丢结果</strong>，不打断线程——{@code prepare} 是一整段不可中断的分析
     * （读两个文件 + 解码 + 频谱通量 + 调性），没有能安全插进去的中断点。
     * 线程会自己跑完、把结果发进已经没人在意的通道然后退出；槽位由
     * {@link #pollPreparation()} 腾出来。
     */
    private void resetPreparation() {
        prepared = false;
        PreparedTransition p = ready;
        if (p != null) {
            // 上游在这里丢掉 ready —— Rust 的 Drop 会连那个已经打开好的 deck 一起丢掉，
            // 「文件句柄跟着释放」。Java 没有析构，所以显式关（见 retire）。
            retire(p.deck);
            ready = null;
        }
        generation = generation + 1L;
    }

    /** Only discard decoded audio when the playback position or track actually changes. */
    private void clearPlaybackBuffers() {
        curBuf.clear();
        nxtBuf.clear();
        carryBuf.clear();
    }

    // ==================================================================
    // 泵：生产者线程的入口
    // ==================================================================

    /**
     * 产出下一块音频。返回写入 {@code out} 的帧数，<strong>0 表示整个队列播完</strong>。
     *
     * <p><strong>这个方法绝不做分析。</strong> 它跑在生产者线程上，环形缓冲默认只有 200 ms，
     * 任何一次秒级的同步分析都会把缓冲写空——用户听到的就是「卡住，
     * 然后加载完了再出来」。分析全在另一条线程上跑，这里只非阻塞地 {@code poll()}，
     * 连「有没有准备好」都不问个究竟，见类注释。
     */
    public int pump(AudioBuf out) {
        int capacity = out.capacityFrames();
        if (capacity == 0) {
            out.setFrames(0);
            return 0;
        }

        // 0) 收上一次准备的成果。**只 poll，一个字节都不等。**
        pollPreparation();

        // 1) 该不该提前分析
        if (shouldPrepare()) {
            startPreparation();
        }

        // 2) 该不该进入过渡。
        //
        // 注意 `beginTransition` 可能**什么都不做**：没有准备好的 deck 时它
        // 把这次过渡推迟（见它的文档），下面照常渲染当前曲。这是有意的——
        // 为了不等准备而自己去打开文件，就是把用户听到的那一下卡顿请回来。
        if (!transitioning && shouldBeginTransition()) {
            if (!triggerReported) {
                // **每次触发只记一行。** 触发条件一旦成立就**每一块都成立**
                // （A 的剩余时间在单调减少），原先在这里每块都打印，一秒钟能刷出几十条
                // ——实测把整段日志淹掉了。记一次、说清，就够定位。
                triggerReported = true;
                Long left = current.remainingFrames();
                logBeginOverlap(left, ready);
            }
            beginTransition();
        } else if (!transitioning) {
            triggerReported = false;
        }

        // 3) 按当前状态渲染
        int n = transitioning ? renderTransition(out, transitionPos) : renderCurrent(out);
        if (n > 0) {
            return n;
        }

        // 4) 过渡里出现了「一帧都交付不出」的情况。
        //
        // 这**不是**「队列播完了」，所以不能就这样返回 0——调用方（CLI / 引擎）
        // 看到 0 会认为整场结束。两种情况会走到这里：
        //
        // - 拍点剪切的切点落在出场曲结束**之后**，那一小段窗口里两路都还没内容
        // - 窗口比曲子还长，两路先后耗尽
        //
        // 正确处理是把这次过渡收尾、让进场曲上位，然后重试一次。
        //
        // 这一条是 ADR-005 §5③ 那个「用 bug 换来的教训」的可执行版本。
        //
        // **上游那 13 条单测里没有一条直接钉住它**：把上面这几行去掉、
        // 用 `cargo test -p sonalis-mix --lib director::tests -- --test-threads=1`
        // 跑一遍，13 条依然全绿（实测过）。原因是上游的假接法都把「出场曲已经放完」
        // 那一块混成非零帧，唯一会看到 0 的是「泵到没有音频为止」的调用方
        // （CLI 的 queue、引擎的 session），那一层的粒度抓不住这种状态。
        // 所以 Java 侧另补了一条本地测试直接钉住它：
        // `DirectorTest.pumpZeroFramesDuringTransitionFinishesInsteadOfEndingPlayback`。
        if (transitioning) {
            finishTransition();
            return renderCurrent(out);
        }
        return 0;
    }

    /**
     * 是否到了该做分析的时点。
     */
    private boolean shouldPrepare() {
        // 硬接没有过渡可准备
        if (window == 0 || queue.isEmpty() || prepared) {
            return false;
        }
        // 上一条准备线程还没收工（可能已经被作废）：**这一轮先不开工**。
        //
        // 作废的那些线程打断不了，`prepare` 会满负荷跑到底；密集跳转时不等它们
        // 收工就一路新开，会堆起一堆同时跑 FFT 的线程，CPU 被打满，音频反倒开始
        // underrun——修卡顿的动作把卡顿换个来源带回来了。
        // 注意这只是「这一轮不开工」，**不是等它**：下面几步照常走完，
        // 音频照常产出，下一轮再看槽位空没空。
        //
        // 万一某条准备线程真的挂死了（不该发生），代价也只是此后每次过渡都被
        // 推迟到曲子自然结束（见类注释「没准备好时怎么办」）——
        // 它**绝不会**把播放一起拖住，这正是这里宁可放弃分析也不等的理由。
        if (prep != null) {
            return false;
        }
        // **收到预告就开工**（2026-10-02，用户要求：「automix 预处理一收到预告就开始做，
        // 不要留到最后再开始」）。
        //
        // 这里原先还有一道时间闸门：`剩余 ≤ 触发点 + 余量`（余量 = 设置里那一档 30/90 s）。
        // 那道闸门有两个毛病：
        //
        //   ① **来不及的场合它救不了**：队首那首本来就短、用户中途换歌、或第一首刚播
        //      就被预告下一首时，「还有 30/90 秒」这个前提根本不成立——于是这一次过渡
        //      彻底不发生（用户长测里「有的歌压根没 automix」有这一份）。
        //   ② **它把 4 秒的分析挤到触发点前**：AI 自动 + 自动点位实测冷启动 ≈4.0 s
        //      （其中扫出场曲整曲 2.5 s，见 docs/automix-modes.md §8.0.1-补），
        //      和播放抢 CPU 的时机最差。
        //
        // 现在只要「下一首是谁」已经知道就开工：控制器是在**比唱早几分钟**把预告送进来的
        // （`AudioBackend#setNextSource`），于是分析永远在从容的时候做完，
        // 触发点、窗口、丢尾也全都提前定死。代价是那个 deck 的句柄会早几分钟打开——
        // 预取落盘后它是本地缓存文件（`DiskCache.pinAudio` 钉住），所以没有额外风险。
        //
        // `prepareMarginSecs` 仍然留着、仍然进日志：它现在的语义是「**这一档最坏要跑多久**」
        // （给「为什么没赶上」那条诊断用的预算），**不再是开工的闸门**。
        return currentPath() != null;
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        // 上游是 u64 的 saturating_add：溢出时落在最大值，而不是回绕成负数
        return sum < 0L ? Long.MAX_VALUE : sum;
    }

    /**
     * 起一条准备线程。<strong>生产者线程只负责启动：不分析、不打开文件、不定位、不分配变调器。</strong>
     *
     * <p>这条线程要做完三件事，前两件以前在 {@code beginTransition} 里、在生产者线程上：
     *
     * <ol>
     *   <li>分析（{@code engine.prepare}）——上一轮已经搬过来的那部分</li>
     *   <li>打开进场 deck 并对拍定位（{@code Deck.open} + {@code Deck.seek}）</li>
     *   <li>造两侧要用的变调器</li>
     * </ol>
     */
    private void startPreparation() {
        prepared = true;

        String outro = currentPath();
        // 只看队首。若它恰好打不开，打开这一步会换下一个，而这次的分析结果
        // 就用不上了——那属于异常路径，不值得为它提前分析整个队列。
        String intro = queue.peekFirst();
        if (intro == null) {
            return;
        }

        // 新实例、新线程：这一个实例只服务这一次过渡，用完就随过渡一起退役。
        // 于是 `prepare` 拿得到独占的实例，不必和渲染路径共用同一个对象——
        // 这正是渲染路径上不需要锁的原因。
        TransitionFactory f = factory;
        int rate = outRate;
        int channels = outChannels;
        // 空间音频开关按**发起准备那一刻**的值打开 deck。用户在这之后改开关的话，
        // 安装时会再套一次（见 `beginTransition`）——不套就会切歌后悄悄失效。
        boolean spatial = spatialEnabled;
        long gen = generation;

        // 队列的**快照**。准备线程碰不到 `queue` 本身（它归生产者线程），
        // 于是拿一份副本去走「逐个尝试、跳过打不开的」那条路，
        // 把走过的文件名按顺序带回来，安装时照着弹真队列。
        List<String> workerQueue = new ArrayList<String>(queue);

        AtomicBoolean busy = new AtomicBoolean(true);
        BlockingQueue<PreparedTransition> rx = new ArrayBlockingQueue<PreparedTransition>(1);
        // 线程要一份句柄：分析（`prepare`）与随后打开 deck 的交叉核对都用它，
        // 最后它还跟着结果回到 `PreparedTransition.preparedIntro`——
        // 「分析的是不是真的就是打开的那一首」全靠它。
        PrepareWorker worker = new PrepareWorker(f, outro, intro, rate, channels, spatial,
                workerQueue, busy, rx, headersLookup);

        Thread t = new Thread(worker, "sonalis-prepare");
        try {
            t.start();
            prep = new PrepJob(rx, gen, busy);
        } catch (Throwable e) {
            // 线程起不来（系统资源耗尽这类）：**这次没有准备结果**。
            // 于是这次过渡会被推迟（见类注释「没准备好时怎么办」），
            // 而不是退化成一次朴素淡化——那需要当场打开进场曲。
            // 这里绝不能改成本线程直接 `prepare`——那正是这次要修的卡顿。
            // 线程句柄也不需要留：它自己会退出，没人等它。
            //
            // 上游匹配的是 `spawn` 的 Result；Java 的对应物是 `start()` 抛出的
            // OutOfMemoryError（"unable to create native thread"）与
            // IllegalThreadStateException，所以这里接 Throwable——与上游「起不来就
            // 当这次没有结果」的语义一致，绝不吞掉别的失败。
            prep = null;
        }
    }

    /** 把一条异常的**因果链**拼成一行（类加载失败时，根因往往是链尾那一个 ✓）。 */
    private static String chainOf(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        int guard = 0;
        while (t != null && guard++ < 8) {
            sb.append(t.getClass().getName());
            if (t.getMessage() != null) {
                sb.append('(').append(t.getMessage()).append(')');
            }
            t = t.getCause();
            if (t != null) {
                sb.append(" ⟵ ");
            }
        }
        // 类加载失败多半是**缺类** ✓ ⇒ 顺带把类路径上能否找到它写出来（能找 ⇒ 是链接问题 ✓）
        if (e instanceof LinkageError && e.getMessage() != null) {
            String name = e.getMessage().replace('/', '.');
            try {
                Class.forName(name, false, Director.class.getClassLoader());
                sb.append("；⚠️ 这个类**在**类路径上 ⇒ 缺的是它依赖的另一个类 ✗");
            } catch (Throwable ignored) {
                sb.append("；这个类**不在**类路径上 ⇒ 产物确实缺它 ✗");
            }
        }
        return sb.toString();
    }

    /**
     * 准备线程的活。
     *
     * <p>做成 {@code static} 嵌套类而不是匿名内部类：上游那边是 {@code move} 闭包，
     * 线程<strong>碰不到编排器的状态</strong>（队列、deck、世代号都靠参数带进来）。
     * 匿名内部类会隐式持有外层的引用，这里用 static 把这条约束写进类型里。
     */
    private static final class PrepareWorker implements Runnable {

        private final TransitionFactory factory;
        private final String outro;
        private final String intro;
        private final int rate;
        private final int channels;
        private final boolean spatial;
        private final List<String> workerQueue;
        private final AtomicBoolean busy;
        private final BlockingQueue<PreparedTransition> rx;
        private final HeadersLookup headersLookup;

        PrepareWorker(TransitionFactory factory, String outro, String intro, int rate, int channels,
                      boolean spatial, List<String> workerQueue, AtomicBoolean busy,
                      BlockingQueue<PreparedTransition> rx, HeadersLookup headersLookup) {
            this.headersLookup = headersLookup;
            this.factory = factory;
            this.outro = outro;
            this.intro = intro;
            this.rate = rate;
            this.channels = channels;
            this.spatial = spatial;
            this.workerQueue = workerQueue;
            this.busy = busy;
            this.rx = rx;
        }

        /**
         * 线程退出时清掉「还在跑」标记，<strong>抛异常也一样</strong>。
         *
         * <p>上游用 RAII（{@code PrepBusyGuard}）而不是在闭包末尾写一句
         * {@code store(false)}：{@code prepare} 里 panic 时闭包末尾那行根本不会执行，
         * 槽位就被永久占住——之后再也不会做分析，而且是<strong>静默</strong>的，
         * 没人会知道。Java 的对应物就是这里这个 {@code try/finally}。
         */
        @Override
        public void run() {
            try {
                work();
            } catch (LinkageError e) {
                // **兜底**（2026-10-07 用户实机 trace ✗）：类加载失败（`NoClassDefFoundError` 那一类 ✓）
                // 抛的是 **Error** ✗，任何只接 `RuntimeException` 的地方都拦不住它 ✓
                // ⇒ 它会把准备线程整个打死 ✓、这一次过渡直接没了 ✓（用户看到一段裸栈 ✓）。
                // 这里把它降级成"**这一次没有准备结果**" ✓：编排器照旧放音频 ✓、
                // 下一次过渡会重新准备 ✓；并且**留一行能看懂的日志** ✓（而不是只有裸栈 ✓）。
                // ⚠️ **必须把 cause 链打出来**（2026-10-07 用户实机 trace ✗）：
                // `NoClassDefFoundError: X` 有两种成因 —— ① X 真的不在 ✓；
                // ② **X 在、但它依赖的 Y 不在** ⇒ 链接失败，报的却是 X 的名字 ✗。
                // 只打 `e.toString()` 就**看不到 Y** ✓（用户第一次贴来的日志正是这样 ✓）
                // ⇒ 这里把整条因果链打出来，一行就能定位 ✓。
                Logger.warn("[qplayer-engine] 准备线程：类加载失败 ⇒ 这一次没有准备结果，"
                        + "照常播放（下一次过渡会重新尝试）。诊断链：{}", chainOf(e));
            } finally {
                busy.set(false);
                // 收工就清掉阶段记号（成功/失败/被作废都算）——它只在「这一次为什么没赶上」
                // 那条日志里有意义，留着会让下一次读到上一次的。
                PrepareStage.clear();
            }
        }

        private void work() {
            PrepareStage.mark("建接法实例");
            Transition engine = factory.create();
            PrepareStage.mark("分析（读素材 / 测速 / 判调性 / 扫包络 / 分轨）");
            TransitionPlan plan = engine.prepare(outro, intro, rate,
                    headersOf(headersLookup, outro), headersOf(headersLookup, intro));
            PrepareStage.mark("打开进场曲的 deck");

            // ---- 打开进场 deck：这次搬过来的活之一 ----
            PreparedDeck pd = openPreparedDeck(workerQueue, rate, channels, spatial, headersLookup);
            Deck deck = pd.deck;
            List<Skipped> recorded = pd.recorded;

            // ---- 交叉核对：**必须排在定位前面** ----
            //
            // 分析看的是队首 A；A 打不开时这里打开的是队列里后面的 B。
            // 跳过量是按 A 的拍点算的，施加到 B 上就是把它跳到一个凭空的
            // 时刻——「拿 A 的结论处理 B」在搬动之后的新落点。
            // **定位不可逆**：等到安装时才发现错位，那个 deck 已经站在
            // 错误的位置上了，只能整个丢掉。所以这里先核对、再定位。
            if (deck != null && !deck.source().equals(intro)) {
                plan = noCuePlan();
            }

            // ---- 对拍定位：把进场曲的第一拍对齐到出场曲的拍上 ----
            //
            // **失败不致命。** 大不了这次不对拍，绝不能因为一次定位失败
            // 就放弃整个过渡——那会让本想顺滑的连接变成一次硬切。
            if (deck != null && plan.skipSecs > 0.0) {
                recordHeavy(HEAVY_SEEK, deck.source());
                try {
                    deck.seek(plan.skipSecs);
                } catch (AudioException e) {
                    recorded.add(new Skipped(deck.source(), "对拍定位失败：" + e.getMessage()));
                }
            }

            // ---- 变调器：出场侧 ----
            //
            // 以**单位比例**造好，具体量由每块的 `pitchAt` 连续调整
            // （`renderTransition` 会调 `setPitch`，那时它已经在链上了）。
            PitchShifter outroPitch = Math.abs(plan.pitch.outro) > 1e-6f
                    ? idlePitchShifter(rate, channels) : null;

            // ---- 变调器：进场侧 ----
            //
            // 首块就要求变调的话也在这里挂上：`renderTransition` 在过渡位置为 0 时
            // 问的就是 `pitchAt(0.0)`，不在此时挂，那一次分配就落进过渡的第一块里。
            if (deck != null && Math.abs(engine.pitchAt(0.0f).intro) > 1e-6f) {
                deck.engagePitch();
            }

            // 送不出去只可能是编排器本身已经析构（连通道一起丢了）。
            // 作废**不会**丢通道——那样这条线程就得自己去碰编排器的状态，
            // 而它没有、也不该有那条路；它只管把结果放回通道然后退出。
            //
            // Java 的 `offer` 在队列满时返回 false（容量 1，一个任务只送一次，
            // 正常永远送得进去）；上游同样忽略 send 的失败。
            // **路径 A 的最后一环**：deck 与 transition 在这里相遇 ⇒ 把票装到 deck 上。
            // 上层（AutoCueTransition.prepare）已经把决策做成票；没有票 / identity ⇒ 不装 ⇒ 零变化。
            installMaterialHook(deck, engine, deck.inRate());   // 解码缓冲用的是输入采样率
            rx.offer(new PreparedTransition(engine, plan, intro, deck, pd.took, recorded,
                    outroPitch));
        }
    }

    /**
     * 收一次准备结果。<strong>只 {@code poll()}，一个字节都不等。</strong>
     */
    private void pollPreparation() {
        // 过渡进行中**不收结果**：窗口是这次过渡的判据（进度怎么推进、
        // 什么时候收尾），中途换一个窗口会把进度算歪、把交接点挪走。
        // 这次过渡的结论到这时才来，说明它本来就没赶上——那就按退化的
        // 朴素过渡走完，下一首再重新准备。
        if (transitioning) {
            return;
        }

        PrepJob job = prep;
        if (job == null) {
            return;
        }
        // 线程还在跑：这一轮什么都不做。**不是等它**——下面照常产音频。
        if (job.busy.get()) {
            return;
        }

        // 线程收工了：槽位腾出来，再看这份结果还要不要
        prep = null;
        if (job.generation != generation) {
            // 已作废（换曲/跳转/上一首）：丢掉。用上它就是拿 A 的结论处理 B。
            //
            // 丢掉的是**整份结果**，包括那个已经打开好的 deck —— 上游靠 Drop
            // 释放它的文件句柄，Java 这边显式关（见 retire）。
            PreparedTransition stale = job.rx.poll();
            if (stale != null) {
                retire(stale.deck);
            }
            // **作废也要留一行**（2026-09-30）。这件事原先完全静默，而那份准备干活时
            // 留下的告警还挂在日志里没人解释——用户就是这么读到一段看不懂的 WARN 的：
            // 那段「自动点位：整曲扫描失败（打开音源失败：…cache）」是给**已经换掉的
            // 那一对**算的（它的出场曲源文件刚被磁盘缓存淘汰，见 DiskCache.pinAudio
            // 只钉「当前曲 + 已预告的下一首」），结果本来就要丢。一进一出两条线对上，
            // 日志才读得通。
            String cur = currentPath();
            String next = queuedNextPath();
            // **这一条留在 info**（2026-10-03 复查时特意留下）：它解释的是「那份准备
            // 自己留下的 WARN 为什么没有下文」，而那条 WARN 默认就在日志里
            // （扫描失败之类）——把它降级就等于让用户再读到一段没头没尾的 WARN，
            // 那正是这条日志当初被加上的原因（判据见 DirectorTest）。
            // 但只留一行：具体是给哪一对算的降级到 debug。
            Logger.info("[qplayer-engine] 自动混音：这份准备已作废（换过曲/跳过），结果丢弃"
                            + " · 当前曲 {} · 队首 {}",
                    cur == null ? "（无）" : cur, next == null ? "（无）" : next);
            Logger.debug("[qplayer-engine] 自动混音：作废的那份准备{}",
                    stale == null ? "（线程没留下结果）"
                            : "（那份计划是给 " + stale.preparedIntro + " 算的）");
            return;
        }

        PreparedTransition p = job.rx.poll();
        if (p != null) {
            // **对拍会把窗口拉长，所以必须重读一次。**
            // 忘了这一步，过渡就会用旧窗口去判断触发点，结果开始得偏晚、
            // 拍点全错。
            //
            // 读的时机只能是这里（结果到手时），不能等到换上实例那一刻：
            // `shouldBeginTransition` 就是用 `window` 判断触发点的。
            long w = p.engine.windowFrames(outRate);
            if (w > 0) {
                window = w;
            }
            // 自动点位会把「丢尾」写进计划里：触发点跟着提前，好让融合点真的落在曲中
            long tailFrames = (long) Math.round(p.plan.outroTailSecs * outRate);
            triggerLead = tailFrames > 0L ? saturatingAdd(window, tailFrames) : window;
            // **触发点要能查。** 用户报「没按预期跳跃和混音」时，日志里原先只有
            // 「计划算出来了」和「准备做完了」，中间缺一句「什么时候才会开始交叠」——
            // 于是分不清是「还没到点」还是「到了点没动」。这一行把它答掉：
            // 自动点位把融合点选在曲中时，触发点会提前到「A 还剩 窗口 + 丢尾」。
            Logger.info("[qplayer-engine] 过渡已装载：{} → {}；交叠 {}s，A 还剩 {}s 时开始，B 从 {}s 进",
                    current.source(), p.deck == null ? "（打开失败）" : p.deck.source(),
                    secs(window), secs(triggerLead), secs(p.plan.skipSecs));
            if (p.deck == null) {
                // 快照里剩下的文件**一个都打不开**：这份结果没有可安装的对象。
                //
                // 这里既不弹队列也不记 `skipped`——那是 `openNext` 的地盘，
                // 曲终的硬接路径会照跑一遍并把原因记全；两边都记，
                // 界面上同一个文件就会出现两条一模一样的理由。
                // 这次过渡于是被推迟到曲子自然结束（见类注释）。
                //
                // **但要让下一次准备还能开工**（2026-10-01）：不清 `prepared` 的话
                // `shouldPrepare()` 永远返回 false——那一次失败之后，这一曲**再也不会**
                // 试第二次（用户长测里「有的歌压根没过渡」有这一份）。清掉之后只要还剩
                // 余量，下一块就会重开一条准备线程；余量不够自然不会再开工，不会打转。
                allowPrepareRetry("这份准备没有可安装的进场曲（deck 打不开）");
                return;
            }
            ready = p;
            return;
        }
        // **失败也要能重来**（同一条理由）：线程收工却没留下结果（prepare 抛了、
        // 或者开流就失败了），原先 `prepared` 一直是 true，于是这一曲再也不会重新准备。
        // 清掉它，让 `shouldPrepare()` 在余量还够时重开一条线程——「差一次重试」与
        // 「整首硬接」的差别就是一次过渡。
        allowPrepareRetry("这次准备没留下结果（prepare 抛了 / 开流失败）");
        // 结果为空，对应上游的两个分支，处理完全一样：
        //
        // - `Err(TryRecvError::Empty)`：线程说它收工了、却没留下结果——理论上不会发生。
        // - `Err(TryRecvError::Disconnected)`：准备线程 **panic 了**（`prepare` 里的断言、
        //   越界都算）。Java 的对应物是 worker 里抛出的未捕获异常
        //   （`PrepareWorker.run` 的 finally 照样清 busy，结果则永远不会进来）。
        //
        // 两者都按「这次没有准备结果」处理——过渡被推迟（见类注释），别的一概不变。
    }

    /**
     * 是否满足进入过渡的条件。
     */
    private boolean shouldBeginTransition() {
        // 窗口为 0 就是硬接，不走过渡分支
        if (window == 0 || queue.isEmpty()) {
            return false;
        }
        // 长度未知（流式）时不猜触发点，退化为硬接
        Long rem = current.remainingFrames();
        if (rem == null) {
            return false;
        }
        return rem.longValue() <= triggerLead();
    }

    /** 帧数 → 「12.3」这样的秒数字符串（只给日志用）。 */
    private String secs(long frames) {
        return String.format(java.util.Locale.ROOT, "%.1f", (double) frames / (double) Math.max(outRate, 1));
    }

    /** 上面那个重载：秒（double）版本。 */
    private String secs(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.1f", seconds);
    }

    /** 触发提前量；还没收到计划（{@code triggerLead < 0}）时就是窗口本身。 */
    private long triggerLead() {
        return triggerLead >= 0L ? triggerLead : window;
    }

    /**
     * 这一次「没有交叠」的原因（给引擎在换曲那一刻打**一条**短而全的 WARN 用）。
     *
     * <p>用户 2026-10-01 长测报「有的歌压根没 automix」，而手机上日志刷得太快、复制不出来。
     * 于是把所有「没过渡 / 降级过渡」的路径都往这里写一句话，换曲那一刻由引擎统一打出来：
     * **一眼能看完、能念出来**（`自动混音：这次换曲没有交叠（原因：…）`）。
     */
    private volatile String skipReason;

    /** 记下「这一次没有交叠」的原因（各条路径调用）；有交叠时清成 {@code null}。 */
    private void noteSkip(String reason) {
        skipReason = reason;
    }

    /** 这一次没有交叠的原因；一切正常时为 {@code null}。 */
    public String skipReason() {
        return skipReason;
    }

    /**
     * 允许重来一次准备（失败路径用）——**限次**，理由见 {@link #prepareRetries}。
     *
     * <p>用户 2026-10-01 长测：「有一些歌曲压根没 automix」。原先准备线程一旦失败
     * （`prepare` 抛了 / 进场曲打不开），`prepared` 一直是 true，于是这一曲**再也不会**
     * 重新准备——差的往往就是一次重试。

     * @return 是否真的放开了重试
     */
    private boolean allowPrepareRetry(String why) {
        noteSkip(why);
        if (prepareRetries >= MAX_PREPARE_RETRIES) {
            Logger.info("[qplayer-engine] 自动混音：{} → 已经重来过 {} 次，这一次不再重来",
                    why, Integer.valueOf(prepareRetries));
            return false;
        }
        prepareRetries++;
        prepared = false;
        Logger.info("[qplayer-engine] 自动混音：{} → 作废并允许重来一次（余量 {} s）",
                why, Long.valueOf(prepareMarginSecs));
        return true;
    }

    /**
     * 到了触发点、手上却没有可用的准备结果——<strong>这一次过渡不会发生</strong>。
     *
     * <p>这是那条「完全没有过渡」路径的唯一证据，所以要把三种情况分清楚（它们要修的地方
     * 完全不同）：准备线程还在跑（分析太慢 / 扫整曲 / 生成太慢）、已经收工但没有结果
     * （打开进场曲全部失败、或者 {@code prepare} 抛了）、以及压根没开始准备。
     *
     * <p>只报一次（{@code pump} 每块都会问到这条路上来）。
     */
    private void reportTriggerMiss() {
        if (triggerMissReported) {
            return;
        }
        triggerMissReported = true;
        String why;
        if (prep != null) {
            // **带上「卡在哪一步」**（2026-09-30）：手机上取不到 log 时，那一句
            // 「准备线程还在跑」说不清是整曲扫描、模型推理还是分轨之后的移调——
            // 三者的修法完全不同。记号由准备链上的各阶段写（见 PrepareStage）。
            why = "准备线程还在跑（分析/开流没完成，卡在：" + PrepareStage.current()
                    + "）→ 结果没赶上触发点";
        } else if (ready == null && prepared) {
            why = "准备线程已收工但没有可安装的结果（进场曲全部打不开，或 prepare 抛了）";
        } else {
            why = "一次准备都没开始过";
        }
        Logger.warn("[qplayer-engine] 自动混音：到了触发点却没有可用的准备结果 → "
                        + "这一次不开始过渡（{}）· 当前曲 {} · 队首 {} · 余量 {} s · 触发提前量 {} s",
                why, currentPath(), String.valueOf(queuedNextPath()),
                Long.valueOf(prepareMarginSecs), fmtSecs((double) triggerLead() / Math.max(1, outRate)));
        noteSkip(why);
    }

    /** 一位小数的秒（日志用）。 */
    private static String fmtSecs(double secs) {
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(secs));
    }

    /**
     * 开始过渡：<strong>只做安装，不做 IO</strong>。
     *
     * <p>打开进场曲、对拍定位、造变调器都已经在准备线程上做完了
     * （见 {@link PreparedTransition}）。这里只把准备好的东西摆好：
     * 换引擎、弹队列、deck 放上 {@link #next}、置过渡位置为 0。
     *
     * <h3>没准备好时<strong>不开始</strong>（这次修复改掉的地方）</h3>
     *
     * <p>从前这里会在没有准备结果时照常开始过渡——那要当场 {@code open_next}，
     * 也就是把刚搬走的 IO 拿回来再付一次。现在改成<strong>推迟</strong>：这一轮不动，
     * 让当前曲接着放；{@link #pump} 每轮都会再问一次，结果一到就开始（窗口短一点），
     * 一直等不到就让当前曲自然结束、由硬接路径接手。理由见类注释。
     */
    private void beginTransition() {
        // 没有准备成果：这一轮不开始过渡。
        // **这里绝不能退回 `openNext()`**——那句话正是用户听到的那一下卡顿。
        PreparedTransition preparedResult = ready;
        if (preparedResult == null) {
            reportTriggerMiss();
            return;
        }
        ready = null;
        triggerMissReported = false;

        Transition engine = preparedResult.engine;
        TransitionPlan plan = preparedResult.plan;
        String preparedIntro = preparedResult.preparedIntro;
        Deck deck = preparedResult.deck;
        List<String> took = preparedResult.took;
        List<Skipped> recorded = preparedResult.recorded;
        PitchShifter outroPitch = preparedResult.outroPitch;

        // `pollPreparation` 不收没有 deck 的结果，所以这里必然有；
        // 留个防御分支是为了万一那条规则被改坏时不至于抛空指针。
        if (deck == null) {
            return;
        }

        // ---- 队列核对：准备时看到的和现在的是不是同一串 ----
        //
        // 准备线程拿的是队列**快照**，它按快照跳过了打不开的文件、打开了第一个
        // 能开的。安装时必须把这一串从真队列里弹掉，**但只弹一模一样的**：
        // 弹错就是静默吃掉一首歌。正常情况下对得上（队列一变，
        // `resetPreparation` 就把这份结果作废了，走不到这里），
        // 这是对那道机制的兜底。
        if (!queueStartsWith(took)) {
            // 上游在这里把整份结果丢掉（连带那个 deck）——Java 显式关。
            retire(deck);
            return;
        }
        for (int i = 0; i < took.size(); i++) {
            queue.pollFirst();
        }
        // 打不开的文件与定位失败的记录并回公开账上——`openNext` 的语义。
        skipped.addAll(recorded);

        // ---- 把准备好的实例换上来 ----
        //
        // 此刻旧实例**已经不在渲染了**（过渡还没开始，上一次过渡早已
        // 结束），所以这次替换不需要任何锁——这正是「工厂 + 换实例」
        // 比 `Arc<Mutex<…>>` 好的地方：锁会在整整 4.6 秒的准备期间被
        // 持有，等于把这个卡顿原样复现一遍。
        transition = engine;

        // 空间音频开关：准备线程是按**它发起时**的开关值打开 deck 的，
        // 用户可能在这之后改了。开着且渲染器已经挂上时这是个 no-op，
        // 只有「准备的这几十秒里用户恰好把开关打开」那一次才会真的分配
        // 延迟线——而那本来就是这个开关的代价。
        deck.setSpatial(spatialEnabled);

        // **分析的是队首，打开的可能是另一首。**
        //
        // 准备线程在**定位之前**已经核对过一次（那次才是关键的，
        // 因为定位不可逆：见 `openPreparedDeck` 与准备线程里的注释）。
        // 这里再对一次是兜底：将来若有人把跳过量挪回安装路径上，
        // 这一条能拦住「拿 A 的结论处理 B」。
        if (!preparedIntro.equals(deck.source())) {
            plan = noCuePlan();
        }

        // 出场侧要变调时，把准备线程**造好的**变调器挂上。
        // 这里只剩一次字段赋值——造它（分配环形缓冲、铺粒窗）那一步
        // 已经在准备线程上做完了。中途挂载会丢掉变调器最初几十毫秒的前视，
        // 听感是一声断裂，所以它必须赶在过渡开始前挂上。
        if (outroPitch != null) {
            current.engagePitchWith(outroPitch);
        }

        activePlan = plan;
        next = deck;
        transitionPos = 0L;
        transitioning = true;
        lastHandoverUnusedFrames = 0L;
        transitionRenderedIntro = 0L;
        // 交接连续性的起点读数（见字段注释）。
        introDeckStartFrames = deck == null ? -1L : deck.outFrames();
        introRemainingAtTransitionStart = deck == null ? null : deck.remainingFrames();
        outroRemainingAtTransitionStart = current == null ? null : current.remainingFrames();
        noteSkip(null);      // 这次真的有交叠了，把上一次的原因清掉
    }

    /**
     * <b>「开始交叠」那一行</b>：正常情况降级成 debug，只有「备好=否」留在 info。
     *
     * <p>为什么按这一条分：{@code ready == null} 意味着<b>这次过渡被推迟到曲终硬接</b>
     * ——那是实实在在的降级（用户会听到一次硬切），必须在默认日志里看得见。
     * 而正常那一支说的「A 还剩多少秒时开始」，装载那一行已经给了触发点，重复一遍只是噪音
     * （用户 2026-10-03：「现在太繁琐了」）。
     */
    private void logBeginOverlap(Long left, PreparedTransition ready) {
        Object[] args = {left == null ? "?" : secs(left.longValue()), secs(triggerLead()),
                ready == null ? "?" : secs(ready.plan.skipSecs),
                ready == null ? "否（这次过渡被推迟到曲终硬接）" : "是"};
        String msg = "[qplayer-engine] 开始交叠：A 还剩 {}s（触发点 {}s），B 从 {}s 进，备好={}";
        if (ready == null) {
            Logger.info(msg, args);
        } else {
            Logger.debug(msg, args);
        }
    }

    /**
     * <b>交接位置</b>：交接前后「界面读到的那份位置」必须逐帧相等。
     *
     * <p>过渡窗口里引擎报的是进场 deck 的位置（{@link #nextFrames()}），交接之后报的是
     * 同一个 deck 的 {@link #currentFrames()}——同一份记账，本该连续。
     * 用户 2026-10-01 报「表现模式之类的也会加速、进度回跳」，而连续性那条账是 +0 ms，
     * 说明要查的是**报出去的位置**这一面：回退（{@link #rewindUnusedIntroFrames}）
     * 会让 deck 的位置往回退，交接瞬间显示就可能跟着退；这一行把两个数并排打出来，
     * 差多少一目了然，不用再靠猜。
     */
    private void logHandoverPosition(Long framesBeforeHandover, boolean afterTransition) {
        if (framesBeforeHandover == null) {
            return;
        }
        long before = framesBeforeHandover.longValue();
        // **交接时定下 B 的响度补偿**（用户 2026-10-09 选 B ✓）：
        // 按刚才实测的 A/B 电平算增益 ✓，之后 30 s 线性释放回 1.0 ✓（不永久改电平 ✗）。
        rmsSumA = 0.0;
        rmsSumB = 0.0;
        rmsCount = 0;
        if (lastRmsA > 1e-5f && lastRmsB > 1e-5f) {
            double want = Math.min(2.0, Math.max(0.5, lastRmsA / (double) lastRmsB));
            // 过渡刚结束 ⇒ 电平已经在过渡里对齐过 ⇒ 不要再叠一次阶跃（那会再"卡一下"）
            currentDeckGain = afterTransition ? 1f : (float) want;
            deckGainReleaseFrames = 0L;
            dev.t1m3.qplayer.util.Logger.info(
                    "[qplayer-engine] 交接响度对齐：A {} dB / B {} dB ⇒ 新曲增益 {} dB（{} s 内回到 0 dB）",
                    String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(20.0 * Math.log10(lastRmsA))),
                    String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(20.0 * Math.log10(lastRmsB))),
                    String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(20.0 * Math.log10(want))),
                    String.format(java.util.Locale.ROOT, "%.0f", Double.valueOf(DECK_GAIN_RELEASE_SECS)));
        }
        long after = currentFrames();
        long delta = after - before;
        lastHandoverPositionDeltaFrames = delta;
        if (delta == 0L && !Logger.verbose()) {
            // 正常交接（差 0 帧）在默认档里不出现，那一行也就不必拼——见 Logger.debug 的注释。
            return;
        }
        String line = String.format(java.util.Locale.ROOT,
                "交接位置：交接前 %d 帧（%.3f s）· 交接后 %d 帧（%.3f s）· 差 %+d 帧（%+d ms）",
                Long.valueOf(before), Double.valueOf(before / (double) Math.max(1, outRate)),
                Long.valueOf(after), Double.valueOf(after / (double) Math.max(1, outRate)),
                Long.valueOf(delta), Long.valueOf(delta * 1000L / Math.max(1, outRate)));
        // **画布位置自检**（2026-10-09 ✓）：接手时 deck 的位置应当等于
        // 「窗口开启时的 deck 位置 + 窗口里已交付的帧数」✓（这正是我给 `nextFrames` 用的画布口径 ✓）。
        // 不等就说明"界面看到的 B 位置"与"deck 真正的续播位置"差了一截 ✗
        // —— 那正是接缝相位错位（0.22 绝对跳变 ✓ 电平却连续 ✓）的来源 ✓。
        if (introDeckStartFrames >= 0L) {
            long predicted = introDeckStartFrames + transitionPos;
            long gap = after - predicted;
            dev.t1m3.qplayer.util.Logger.info(
                    "[qplayer-engine] 画布位置自检：原点 {} + 已交付 {} = {} · deck 实际 {} · 差 {} 帧（{} ms）",
                    Long.valueOf(introDeckStartFrames), Long.valueOf(transitionPos),
                    Long.valueOf(predicted), Long.valueOf(after), Long.valueOf(gap),
                    Double.valueOf(gap * 1000.0 / (double) Math.max(1, outRate)));
        }
        if (delta != 0L) {
            Logger.warn("[qplayer-engine] {} —— 位置在交接处退了/进了，界面会跳；请连同这一行一起上报", line);
        } else {
            Logger.debug("[qplayer-engine] {}", line);
        }
    }

    /**
     * <b>交接连续性</b>：融合期里「接法的素材推进了多少」与「deck 实际前进多少」必须一致。
     *
     * <p>用户 2026-10-01 报：「声波重合会让歌曲加速我能接受，但是本身的 deck 也得跟着加速啊——
     * 现在是融合部分加速了但本体时间没加速，导致回归到 B 的时候出现听感进度回跳」。
     * 融合期里出声的可能是接法**自己预渲染的素材**（声波重合那一层就是），而 deck 同时在按块
     * 推进；两者一旦不一致，交接瞬间就会跳变。这一行把两个数并排打出来：
     *
     * <ul>
     *   <li>窗口推进：{@code transitionPos}（这次过渡真正渲染的帧数，判据就是它）；</li>
     *   <li>B 前进：进场 deck 剩余帧数的变化量；</li>
     *   <li>差额：正数 = deck 跑在素材**前面**（交接时会往回跳），负数 = 落在后面（会重一遍）；</li>
     *   <li>再顺带核 A：它结束时应当只剩「丢尾」那么多。</li>
     * </ul>
     *
     * <p>长度未知的流（{@code remainingFrames() == null}）打不出来，那一档如实写「未知」。
     */
    private void logHandoverContinuity(Deck nextDeck) {
        int rate = Math.max(1, outRate);
        long produced = transitionPos;
        String introPart;
        Long start = introRemainingAtTransitionStart;
        Long end = nextDeck == null ? null : nextDeck.remainingFrames();
        if (start == null || end == null) {
            introPart = "B 前进 未知（这一侧长度未知）";
        } else {
            long advanced = start.longValue() - end.longValue();
            // 缓冲里还没交付的余量（carry）也算在这一段的「前进」里：它不是跳，
            // 是下一块立刻就要放出来的东西。不减掉它，这条诊断会天天误报。
            long pending = carryBuf.frames();
            lastHandoverUnusedFrames = pending;
            lastHandoverDeltaFrames = advanced - pending - produced;
            long deltaMs = lastHandoverDeltaFrames * 1000L / rate;
            introPart = String.format(java.util.Locale.ROOT,
                    "B 前进 %.3f s（素材推进 %.3f s%s，差 %+d ms%s）",
                    Double.valueOf((double) advanced / rate),
                    Double.valueOf((double) produced / rate),
                    pending > 0 ? String.format(java.util.Locale.ROOT,
                            "，其中 %d 帧留在缓冲里下一块放", Long.valueOf(pending)) : "",
                    Long.valueOf(deltaMs),
                    deltaMs == 0L ? "" : " ← 这一对不一致，交接处会跳");
        }
        String outroPart = "";
        Long oStart = outroRemainingAtTransitionStart;
        if (oStart != null) {
            long tailFrames = (long) Math.round(activePlan == null ? 0.0
                    : activePlan.outroTailSecs * rate);
            long left = oStart.longValue() - produced;
            outroPart = String.format(java.util.Locale.ROOT,
                    " · A 结束时剩 %.3f s（丢尾 %.3f s）",
                    Double.valueOf((double) left / rate),
                    Double.valueOf((double) tailFrames / rate));
        }
        // 不一致就喊 WARN，不藏在 INFO 里：用户已经抱怨过「看不到卡在哪」，
        // 而这正是「听感上跳了一下」的那条线索。
        if (introRemainingAtTransitionStart != null && end != null
                && Math.abs(lastHandoverDeltaFrames) > 2L) {
            Logger.warn("[qplayer-engine] 交接连续性：{} · 窗口推进 {} 帧{} —— "
                            + "进场 deck 与素材推进不一致，交接处会跳；请连同这一行一起上报",
                    introPart, Long.valueOf(produced), outroPart);
        } else {
            Logger.debug("[qplayer-engine] 交接连续性：{} · 窗口推进 {} 帧{}",
                    introPart, Long.valueOf(produced), outroPart);
        }
    }

    /**
     * 真队列的前 {@code prefix.size()} 项是不是与 {@code prefix} 逐项相同。
     *
     * <p>对应上游的 {@code self.queue.iter().take(took.len()).eq(took.iter())}：
     * <strong>只弹一模一样的</strong>——弹错就是静默吃掉一首歌。
     */
    private boolean queueStartsWith(List<String> prefix) {
        if (prefix.size() > queue.size()) {
            return false;
        }
        int i = 0;
        for (String p : queue) {
            if (i >= prefix.size()) {
                break;
            }
            if (!prefix.get(i).equals(p)) {
                return false;
            }
            i++;
        }
        return i == prefix.size();
    }

    /**
     * 渲染非过渡时段：直接输出当前 deck。
     */
    private int renderCurrent(AudioBuf out) {
        // 刚交接过的那一块：进场 deck 缓冲里可能还有「已渲染、没交付」的余量，
        // 先把它放出来（普通播放路径不受影响，carryBuf 平时是空的）。
        int n = carryBuf.frames() > 0 ? renderCarryThenDeck(out) : renderCurrentDeck(out);
        if (n > 0) {
            return n;
        }
        // 走到这里：要么真的耗尽，要么（防御）转了 256 圈仍无帧。
        if (!current.isDrained()) {
            skipped.add(new Skipped(current.source(),
                    "渲染反复返回空帧但解码器未耗尽，按结束处理"));
        }
        rememberCurrent();
        resetPreparation();
        Deck deck = openNext();
        if (deck == null) {
            return 0;
        }
        Deck old = current;
        current = deck;
        retire(old);
        clearPlaybackBuffers();
        return renderCurrentDeck(out);
    }

    /** Render and process fresh samples once; queued carry already passed its rack. */
    private int renderCurrentDeck(AudioBuf out) {
        int n = current.render(out);
        // 响度对齐（默认 1.0 ✓ 单曲播放完全不受影响 ✓）：
        // 交接时按 A/B 实测电平定下 ✓，一直跟到这首播完 ✓。
        if (n > 0 && currentDeckGain != 1f) {
            applyDeckGain(out, n);
        }
        if (seamPending && n > 1 && out.channels() <= lastDelivered.length) {
            float[] ob = out.backingArray();
            // **交接处 5 ms 等功率交叉**（2026-10-09 ✓）：变速输出与 deck 之间约 11 ms 相位差 ✓
            // ⇒ 与"过渡最后 5 ms 的输出"等功率混合 ✓ ⇒ "跳一下"被摊开 ✓。
            // 只作用于真正的交接后首块 ✓；任何改位置入口（seek / skipNext / skipPrev ✓）都会清掉它 ✓
            // —— `DirectorBufferOwnershipTest` 的 `seekAfterHandoff…` 判据两次都钉在这里 ✓。
            int len5 = Math.min(Math.min(seamTailFrames, n), 512);
            for (int c0 = 0; c0 < out.channels() && c0 < 8 && len5 > 1; c0++) {
                int base = out.channelBase(c0);
                for (int i = 0; i < len5; i++) {
                    double u = (double) i / (double) (len5 - 1);
                    // **从"上一个交付的样本"开始淡入**（2026-10-11 ✓ 根因修复 ✓）：
                    // 原来用 `seamTail[i]` ✗ —— 那是「本块最后 keep 帧」里的**第 i 帧** ✓
                    // 而 i=0 对应的是 `n-keep`（220 帧前）✗ 不是交接点 ✓
                    // ⇒ 混合的起点比真正的接缝**早 220 帧** ✗ ⇒ ob[0] 跳 0.19 ✓ ⇒
                    //   `接缝跳变 比值 24` 就是这么来的 ✓（三次运行都是 24~25 ✓ 与电平无关 ✓）
                    // 修法：起点用 `lastDelivered`（上一个交付样本 ✓）⇒ i=0 时 ob[0] 正好等于它 ✓
                    // ⇒ `seam = |ob[0] − lastDelivered|` ≈ 0 ✓ 台阶消失 ✓
                    ob[base + i] = (float) (lastDelivered[c0] * Math.cos(u * Math.PI * 0.5)
                            + ob[base + i] * Math.sin(u * Math.PI * 0.5));
                }
            }
            float seam = 0f;
            for (int c0 = 0; c0 < out.channels(); c0++) {
                seam = Math.max(seam, Math.abs(ob[out.channelBase(c0)] - lastDelivered[c0]));
            }
            // **电平接缝**（2026-10-09 ✓）：交接前 5 ms 与交接后首块前 5 ms 的 RMS 差 ✓
            // 若这里出现 dB 级台阶 ⇒ 那一下是**电平**而不是波形 ✓（判据：接缝跳变比值只有 1.5 ✓ 波形连续 ✓）。
            float rmsOld = 0f;
            float rmsNew = 0f;
            int len0 = Math.min(Math.min(seamTailFrames, n), 512);
            if (len0 > 1) {
                for (int c0 = 0; c0 < out.channels() && c0 < 8; c0++) {
                    int base = out.channelBase(c0);
                    double accOld = 0.0;
                    double accNew = 0.0;
                    for (int i = 0; i < len0; i++) {
                        double o = seamTail[c0 * 512 + i];
                        double w = ob[base + i];
                        accOld += o * o;
                        accNew += w * w;
                    }
                    rmsOld = (float) Math.max(rmsOld, Math.sqrt(accOld / len0));
                    rmsNew = (float) Math.max(rmsNew, Math.sqrt(accNew / len0));
                }
                double acc2 = 0.0;
                int cnt2 = 0;
                for (int c0 = 0; c0 < out.channels() && c0 < 8; c0++) {
                    int base2 = out.channelBase(c0);
                    for (int i = 0; i < n; i++) {
                        double v = ob[base2 + i];
                        acc2 += v * v;
                        cnt2++;
                    }
                }
                float rmsBlockNew = cnt2 > 0 ? (float) Math.sqrt(acc2 / cnt2) : 0f;
                Logger.info("[qplayer-engine] 电平接缝（整块 85 ms）：前 {} dBFS · 后 {} dBFS · 差 {} dB",
                        String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(20.0 * Math.log10(Math.max(1e-9, seamBlockRms)))),
                        String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(20.0 * Math.log10(Math.max(1e-9, rmsBlockNew)))),
                        String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(
                                20.0 * Math.log10(Math.max(1e-9, rmsBlockNew))
                                        - 20.0 * Math.log10(Math.max(1e-9, seamBlockRms)))));
                Logger.info("[qplayer-engine] 电平接缝：前 {} dBFS · 后 {} dBFS · 差 {} dB",
                        String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(20.0 * Math.log10(Math.max(1e-9, rmsOld)))),
                        String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(20.0 * Math.log10(Math.max(1e-9, rmsNew)))),
                        String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(
                                20.0 * Math.log10(Math.max(1e-9, rmsNew)) - 20.0 * Math.log10(Math.max(1e-9, rmsOld)))));
            }
            Logger.info("[qplayer-engine] 接缝跳变：{} · 基线 {} · 比值 {}",
                    String.format(java.util.Locale.ROOT, "%.6f", Float.valueOf(seam)),
                    String.format(java.util.Locale.ROOT, "%.6f", Float.valueOf(lastDeliveredDelta)),
                    String.format(java.util.Locale.ROOT, "%.1f", Float.valueOf(
                            seam / Math.max(1e-7f, lastDeliveredDelta))));
            seamPending = false;
        }
        // **非过渡时段也要过出场曲那一台机架**：用户设的 EQ/音高/EQ 曲线在「正常播放」的
        // 百分之九十时间里都该生效，只在过渡窗口里生效等于没有。
        // （这条判据当场抓到过：我第一版只插在 renderTransition 那一支里，
        //   NormalPlayback 的能量与不挂机架时一模一样。）
        // **返回 0 不一定是「本曲结束」。**
        //
        // 挂着变调器的 deck 在攒前视时也返回 0（`Deck.render` 的文档写明）。
        // 曾经这里把 0 一律当成结束：BeatCut 的进场 deck 在切点第一次被调、
        // 变调器还在攒前视 → 过渡收尾把它提为当前曲 → 首块返回 0 →
        // **一首歌刚开头就被当成播完跳走**。
        //
        // 判据用解码器的真实状态：没耗尽就再喂一轮。每次 render 都会推进
        // 变调器的输入，攒够前视后自然出帧，所以循环必然收敛。
        int tries = 0;
        while (n == 0 && !current.isDrained() && tries < 256) {
            n = current.render(out);
            if (n > 0 && currentDeckGain != 1f) {
                applyDeckGain(out, n);
            }
            tries++;
        }
        // **播到一半「停住 → 消失 → 过一会跳下一首」的保护**（2026-10-11 ✓ 手机端实测 ✓）
        //
        // 远端源（插件直链）偶发短暂取不到数据：解码器没耗尽，但这一段 render 一直给 0 ✓
        // 上面的重试跑满 256 次后仍然 0 ✗ ⇒ 调用方会把这一块当成"没有音频" ✓
        // 再叠上 automix 那一次"准备作废 ⇒ 硬切"的收尾 ⇒ 听感正是"歌没了，然后跳到下一首" ✓
        //
        // 处理：**没耗尽就不许表现为枯竭** ✓ 这一块直接补静音（位置只前进一块 93 ms ✓
        // 数据回来以后接着播 ✓），并且每种情况只记一次日志 ✓。
        if (n == 0 && !current.isDrained()) {
            if (!stallLogged) {
                stallLogged = true;
                dev.t1m3.qplayer.util.Logger.warn(
                        "[qplayer-engine] 当前曲连续 256 次没渲染出帧但**没耗尽**"
                            + "（远端源短暂取不到数据？）⇒ 补一块静音继续，不当作曲终");
            }
            final int fill = Math.min(out.capacityFrames(), 4096);
            out.setFrames(fill);
            out.zero();
            n = fill;
            if (studio != null) {
                studio.processDeckA(out);
            }
            return n;
        }
        if (n > 0) {
            stallLogged = false;
        }
        if (n > 0 && studio != null) {
            studio.processDeckA(out);
        }
        return n;
    }

    /**
     * 保证缓冲里至少有 {@code want} 帧：不够就渲染一块 deck **接到后面**（而不是覆盖）。
     *
     * <p>「接在后面」是这套记账的关键：{@code Deck.render} 一次固定产出一整块，而过渡的收尾块
     * 常常只用得到一部分；接在后面，余量才能留到下一块继续用（见 {@link #dropFront} 与
     * {@link #carryBuf} 的注释）。
     *
     * <p>{@code deckA} 决定这一块过哪一台机架——**必须在 append 之前处理**，
     * 因为 append 之后改的是缓冲里的那份拷贝，scratch 上的处理就白做了。
     */
    private void ensureFrames(Deck deck, AudioBuf buf, AudioBuf scratch, int want, boolean deckA) {
        int guard = 0;
        while (buf.frames() < want && guard++ < 4) {
            int got = deck.render(scratch);
            if (got <= 0) {
                break;
            }
            if (studio != null) {
                if (deckA) {
                    studio.processDeckA(scratch);
                } else {
                    studio.processDeckB(scratch);
                }
            }
            if (buf.append(scratch) <= 0) {
                break;
            }
        }
    }

    /**
     * 交接后的头一两块：先把 {@link #carryBuf} 里的余量交付出去，不够一整块再渲染 deck 补满。
     *
     * <p>deck 的新块装不下时，装不下的那部分**留在 carryBuf 里**（不是丢掉）——
     * 与过渡期间同一套记账：渲染过的帧要么已经交付、要么还在缓冲里等着。
     * 余量已经处理过；只对新渲染的帧使用当前曲机架，避免重复 EQ 和滤波状态倒退。
     */
    private int renderCarryThenDeck(AudioBuf out) {
        out.clear();
        int moved = out.append(carryBuf);
        dropFront(carryBuf, moved);
        if (out.frames() >= out.capacityFrames()) {
            return out.frames();
        }
        int got = renderCurrentDeck(renderA);
        if (got <= 0) {
            return out.frames();
        }
        int room = out.capacityFrames() - out.frames();
        if (got <= room) {
            out.append(renderA);
        } else {
            renderA.setFrames(room);
            out.append(renderA);
            renderA.setFrames(got);
            dropFront(renderA, room);
            carryBuf.clear();
            carryBuf.append(renderA);
        }
        return out.frames();
    }

    /** 从缓冲**头部**丢掉 {@code frames} 帧（把余量往前挪）。消费记账就靠它。 */
    private static void dropFront(AudioBuf buf, int frames) {
        int drop = Math.min(Math.max(0, frames), buf.frames());
        if (drop <= 0) {
            return;
        }
        int keep = buf.frames() - drop;
        if (keep > 0) {
            float[] data = buf.backingArray();
            int stride = buf.capacityFrames();
            for (int c = 0; c < buf.channels(); c++) {
                System.arraycopy(data, c * stride + drop, data, c * stride, keep);
            }
        }
        buf.setFrames(keep);
    }

    /**
     * 渲染过渡时段。
     */
    /** 【供料诊断 ✓】只打前 200 块 ✓。 */
    private static int feedLogged;

    private int renderTransition(AudioBuf out, long pos) {
        long windowLocal = Math.max(window, 1L);
        // **窗口长度诊断**（2026-10-09 ✓ 用户：计划 18s 只跑 15.445s ✗）：
        // 只测量 ✓ 不改行为 ✓ —— 把循环实际用的窗口、引擎报的窗口并排打出来 ✓。
        windowDiag++;
        if (windowDiag <= 3) {
            Logger.info("[qplayer-engine] 窗口诊断：windowLocal {} 帧（{} s）· window {} 帧（{} s）· 引擎报 {} 帧",
                    Long.valueOf(windowLocal),
                    String.format(java.util.Locale.ROOT, "%.3f", Double.valueOf(windowLocal / (double) Math.max(1, outRate))),
                    Long.valueOf(window),
                    String.format(java.util.Locale.ROOT, "%.3f", Double.valueOf(window / (double) Math.max(1, outRate))),
                    Long.valueOf(transition == null ? -1L : transition.windowFrames(outRate)));
        }
        float progress = (float) pos / (float) windowLocal;
        if (progress < 0f) {
            progress = 0f;
        } else if (progress > 1f) {
            progress = 1f;
        }

        // 变调曲线每块更新一次（它是连续的，所以块与块之间不会有台阶）。
        // 对不需要变调的过渡这两个调用会立刻返回，不产生任何开销。
        PitchPlan pitch = transition.pitchAt(progress);
        current.setPitch(pitch.outro);
        Deck nextDeck = next;
        if (nextDeck != null) {
            nextDeck.setPitch(pitch.intro);
        }

        // 两个 deck 各渲染一份（可能少于容量，mixTwo 会处理长度差异）
        ensureFrames(current, curBuf, renderA, out.capacityFrames(), true);

        // 有的接法（拍点剪切）要求进场曲**等到切点才开始跑**，
        // 否则它开头那一段会被白白吃掉。
        //
        // **但出场曲已经放完时不能继续等。** 编排器是按块推进的，出场曲在
        // 触发时可能只剩下不到一个块；窗口又可能比一个块还短（拍点剪切就是），
        // 于是切点永远到不了：进场曲一直卡在闸门后面，两路都没内容 →
        // `render` 返回 0 → 走兜底硬接。结果是**消咔哒斜坡一次都没跑**，
        // 接缝上留下一个实打实的台阶（实测最大跳变 0.49，其余接法 0.09）。
        boolean outroGone = curBuf.frames() == 0;
        float introStartAt = activePlan.introStartAt;
        int renderedIntro = 0;
        if (progress >= introStartAt || outroGone) {
            if (nextDeck != null) {
                ensureFrames(nextDeck, nxtBuf, renderB, out.capacityFrames(), false);
                renderedIntro = nxtBuf.frames();
                transitionRenderedIntro += renderedIntro;
            }
        } else {
            nxtBuf.setFrames(0);
        }

        // 【供料诊断 ✓ 2026-10-09】每块实际供了多少帧（空块 = B 断断续续的嫌疑 ✓）
        feedLogged++;
        if (feedLogged <= 200) {
            Logger.debug("[供料] p={} A帧={} B帧={} 容量={}", 
                    String.format(java.util.Locale.ROOT, "%.2f", Float.valueOf(progress)),
                    Integer.valueOf(curBuf.frames()), Integer.valueOf(nxtBuf.frames()),
                    Integer.valueOf(out.capacityFrames()));
        }
        // **响度补偿**（2026-10-09 ✓ 用户：「这个能明显感觉歌曲跳变」✓）：
        // 实测日志 p=0.34 A -12.4 dB / **B -30.6 dB** ⇒ B 比 A 轻 18 dB ✗
        // ⇒ 交叉淡化走到 100% B 时，整体音量**掉一大截** ✓（电平接缝只差 0.59 dB 也照样难听 ✓）
        // ⇒ 窗口内把 B 抬到与 A 相当（夹 ±12 dB ✓），**渲完立刻还原**（不改任何持久状态 ✓）。
        // **响度补偿（平滑版 ✓ 2026-10-11 ✓ 用户：「automix 经常出现响度抽搐和突变」✗）**
        //
        // 原来每块（≈93 ms）**从零重算**一次 `A/B` 的 RMS 比 ✗ ⇒ 实测日志里同一个窗口内：
        //   p=0.00 抬 7.9 dB → p=0.30 抬 1.8 dB → p=0.89 抬 5.3 dB
        //   ⇒ 2~3 秒里来回荡 **5~6 dB** ✗ = 听感上的"抽搐/呼吸"✓
        // 而且结尾时 A 已经放干（日志：p=0.88 · A **−78.6 dB**）✗
        //   ⇒ 拿"几乎为 0 的 RMS"当基准 ⇒ 比值变成噪声 ✗ = "突变"✓
        //
        // 修法（三条 ✓ 都只影响本窗口 ✓ 渲完照样还原 ✓）：
        //   ① **不拿放干的 A 当基准**（A < −40 dBFS ⇒ 保持上次的值 ✓ 不改 ✗）
        //   ② 一阶平滑（α≈0.06 ⇒ 时间常数 ≈ 1.5 s ✓ 覆盖整个窗口 ✓ 只跟"曲目整体电平差"✓
        //      不跟音乐自身的起伏 ✓）
        //   ③ **每块变化夹在 0.5 dB 内** ✓ ⇒ 任何情况下都不会"突然窜一下"✓
        if (progress < 0.02f) loudnessCompState = 1.0;
        float loudnessComp = 1f;
        {
            int probe = Math.min(Math.max(1, nxtBuf.frames()), Math.max(1, curBuf.frames()));
            double ra = rmsOf(curBuf, probe);
            double rb = rmsOf(nxtBuf, probe);
            // ① A 放干（< −40 dBFS ≈ 0.01）或 B 还没进来 ⇒ 保持上次状态 ✓
            if (ra > 0.01 && rb > 1e-6) {
                double want = Math.pow(10.0, (db(ra) - db(rb)) / 20.0);
                want = Math.min(Math.pow(10.0, 12.0 / 20.0), Math.max(1.0 / Math.pow(10.0, 12.0 / 20.0), want));
                // ② 一阶平滑 ✓
                // **更慢**（2026-11-15 用户实测"音量忽大忽小 / 一顿一顿"✗）：
                // α 0.06 → 0.03（时间常数 ≈ 3 s，几乎只跟"整首的电平差"）
                loudnessCompState += (want - loudnessCompState) * 0.03;
            }
            // ③ 每块最多走 0.5 dB ✓（换算成线性比的夹紧）
            // 每块上限 0.5 dB → **0.15 dB**（≈1.6 dB/s）⇒ 不会有可听的"抽搐" ✓
            final double maxStep = Math.pow(10.0, 0.15 / 20.0) - 1.0;
            final double lo = Math.max(1.0 - maxStep, loudnessCompState - maxStep);
            final double hi = Math.min(1.0 + maxStep, loudnessCompState + maxStep);
            loudnessComp = (float) Math.max(lo, Math.min(hi, loudnessCompState));
        }
        // **收尾必须回到 1.0**（2026-11-16 用户实测"瞬间卡了一下换了下一首"）：
        // 过渡末尾 A 已经放干 ⇒ 上面那条"不拿放干的 A 当基准"会**保持**补偿值 ✗
        // ⇒ 混音的最后一块比 B 的自然电平低（实测接缝 前 -27.5 dBFS → 后 -6.9 dBFS = 20.6 dB 台阶）✗
        // 交接瞬间听起来就是"卡一下"✓。⇒ 最后 15% 把补偿平滑拉回 1.0，接缝就连续了。
        if (progress > 0.85f) {
            double t = Math.min(1.0, (progress - 0.85) / 0.15);
            t = t * t * (3.0 - 2.0 * t);   // smoothstep，导数两端为 0
            loudnessComp = (float) (loudnessComp + (1.0 - loudnessComp) * t);
        }
        final boolean compensated = Math.abs(loudnessComp - 1f) > 1e-3f;
        if (compensated && (++loudnessLogCount % 25) == 1) {
            dev.t1m3.qplayer.util.Logger.info(
                    "[过渡响度补偿] p={} · B 抬 {} dB（与 A 对齐 ✓ 渲完还原 ✓）",
                    String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(progress)),
                    String.format(java.util.Locale.ROOT, "%.1f",
                            Double.valueOf(20.0 * Math.log10(loudnessComp))));
        }
        if (compensated) {
            scaleBuf(nxtBuf, loudnessComp);
        }
        int n = transition.render(out, curBuf, nxtBuf, progress);
        // **总增益不塌陷**（2026-10-11 ✓ 根因修复 ✓ 用户：「找根源修不要只修表面」✓）：
        // 机制：等功率 `out = cos(p·π/2)·A + sin(p·π/2)·B` ✓
        //   ⇒ 若 A 在 p 还很小的时候就**放干**（`outroGone` ✓）⇒ cos 项=0 ✓ 而 sin(p) 也很小 ✗
        //   ⇒ 输出 ≈ 静音 ✓ ⇒ 交接后 B 满增益 ⇒ **25 dB 台阶** ✓（日志：前 -38 / 后 -13 ✓）
        // 修法：A 没素材了这一块**本来就只该有 B** ⇒ 把 B 按 `max(p, sin(p·π/2))` 直接补上 ✓
        //   （`p` 比 `sin` 大 ⇒ 淡入更早、更满 ✓ 不会再出现"掉到没声音"✗）
        if (n > 0 && outroGone && progress < 1f && nxtBuf.frames() > 0) {
            liftWhenOutgoingDrained(out, nxtBuf, progress, n);
        }
        // **只在窗口前段累计**（2026-10-09 ✓ 修我自己的 bug ✗）：
        // 窗口末尾 A 已经被淡出到 -78.9 dB ✗，拿它当"A 的电平"会把 B 压低 6 dB ✓
        // ⇒ 交接后响度突变下降 ✓（用户实测 ✓）。前 35% 里两侧都还在正常播放 ✓ 才是真电平 ✓。
        if (n > 0 && progress <= 0.35f) {
            float ra = (float) rmsOf(curBuf, n);
            float rb = (float) rmsOf(nxtBuf, n);
            if (ra > levelSamples ? false : true) {
                rmsSumA += ra;
                rmsSumB += rb;
                rmsCount++;
                lastRmsA = (float) (rmsSumA / Math.max(1, rmsCount));
                lastRmsB = (float) (rmsSumB / Math.max(1, rmsCount));
            }
        }
        if (compensated) {
            scaleBuf(nxtBuf, 1f / loudnessComp);
        }
        // **三段能量曲线**（2026-10-09 ✓ 用户：「过渡方案效果不行 基本都是 am 的炸」✓）：
        // 每 ~100 ms 打一块 **A 的 RMS / B 的 RMS / 混音输出 RMS** ✓
        // ⇒ 一眼看出是谁在跳（A 掉太狠 / B 冲太高 / 总和过载 ✓），而不是靠猜 ✗。
        if ((++energyBlockCount % 25) == 0 && n > 0) {
            double rmsA = rmsOf(curBuf, n);
            double rmsB = rmsOf(nxtBuf, n);
            double rmsO = rmsOf(out, n);
            dev.t1m3.qplayer.util.Logger.info(
                    "[过渡能量] p={} · A {} dB · B {} dB · 输出 {} dB",
                    String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(progress)),
                    String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(db(rmsA))),
                    String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(db(rmsB))),
                    String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(db(rmsO))));
        }
        if (n > 1) {
            int keep = Math.min(Math.min((int) Math.round(0.005 * Math.max(1, outRate)), n), 512);
            int chs = Math.min(out.channels(), 8);
            float[] ob0 = out.backingArray();
            for (int c0 = 0; c0 < chs; c0++) {
                int base = out.channelBase(c0);
                for (int i = 0; i < keep; i++) {
                    seamTail[c0 * 512 + i] = ob0[base + n - keep + i];
                }
            }
            seamTailFrames = keep;
            {
                double acc = 0.0;
                int cntB = 0;
                for (int c0 = 0; c0 < Math.min(out.channels(), 8); c0++) {
                    int baseB = out.channelBase(c0);
                    for (int i = 0; i < n; i++) {
                        double v = ob0[baseB + i];
                        acc += v * v;
                        cntB++;
                    }
                }
                seamBlockRms = cntB > 0 ? (float) Math.sqrt(acc / cntB) : 0f;
            }
        }
        // 消费掉这一块真正用掉的前 n 帧：剩下的余量留在缓冲里，下一块接着用（不丢、不退）。
        if (n > 1) {
            // 记下**交付输出的**最后一个样本 + 它前一个样本的差（作为接缝对比的基线 ✓）
            float[] ob = out.backingArray();
            int chs = Math.min(out.channels(), lastDelivered.length);
            for (int c = 0; c < chs; c++) {
                int base = out.channelBase(c);
                lastDelivered[c] = ob[base + n - 1];
            }
            lastDeliveredDelta = Math.abs(ob[out.channelBase(0) + n - 1] - ob[out.channelBase(0) + n - 2]);
            lastDeliveredAt = transitionPos;
        }
        dropFront(curBuf, n);
        dropFront(nxtBuf, n);

        // 进度按**实际产出**推进，而不是按请求量——
        // 否则窗口会比实际音频跑得快，过渡提前结束。
        long newPos = pos + (long) n;
        // **先把这一块的产出记进去再收尾**：`finishTransition` 要用 `transitionPos`
        // 报「这次实际交叠了多少帧」，而收尾时会把它归零。原先只在没结束的那一支里赋值，
        // 于是报出来的数永远少最后一块（实测 300 ms 的窗口报 256 ms）。
        transitionPos = newPos;
        if (newPos >= windowLocal) {
            finishTransition();
        }
        return n;
    }

    /**
     * 过渡结束：出场 deck 退役，进场 deck 上位。
     */
    private void finishTransition() {
        // 出场曲进历史，这样「上一首」能回到它
        rememberCurrent();

        Deck nextDeck = next;
        // 「界面读到的位置」在交接前后各取一次：过渡窗口里引擎读 nextFrames()（进场 deck），
        // 交接之后读 currentFrames()（同一个 deck）。两者本该**逐帧相等**——不等就是
        // 用户报的「过渡进度与实际进度不统一 / 进度回跳」。这一行是为此加的：
        // 之前只有「素材推进 vs deck 前进」那条账，看不到**报出去的位置**这一面。
        Long framesBeforeHandover = nextDeck == null ? null : Long.valueOf(nextDeck.outFrames());
        // The retired outgoing deck's unconsumed samples cannot belong to the next transition.
        curBuf.clear();
        // 进场 deck 缓冲里可能还剩「已渲染、没交付」的余量：搬进 carryBuf，
        // 交接之后由 renderCurrent 先把它放出来（见 carryBuf 的注释）。
        carryBuf.clear();
        if (nxtBuf.frames() > 0) {
            carryBuf.append(nxtBuf);
            nxtBuf.clear();
        }
        // **交接余量测量**（2026-10-09 ✓ 用户：过渡结束瞬间有"超短 seek"✓）：
        // 交接时"已渲染、未交付"的 B 帧被搬进 carryBuf 接着放 ✓；
        // 若它与 deck 继续的位置**重叠或缺失** ✗ ⇒ 听感就是一个极短的 seek ✓。只测量 ✓ 不改行为 ✓。
        if (nextDeck != null) {
            float seam = 0f;
            if (lastDeliveredAt >= 0 && carryBuf.frames() > 0
                    && carryBuf.channels() <= lastDelivered.length) {
                float[] cb = carryBuf.backingArray();
                for (int c = 0; c < carryBuf.channels(); c++) {
                    seam = Math.max(seam, Math.abs(cb[carryBuf.channelBase(c)] - lastDelivered[c]));
                }
                Logger.info("[qplayer-engine] 接缝检查：接缝跳变 {} · 基线 {} · 比值 {}",
                        String.format(java.util.Locale.ROOT, "%.6f", Float.valueOf(seam)),
                        String.format(java.util.Locale.ROOT, "%.6f", Float.valueOf(lastDeliveredDelta)),
                        String.format(java.util.Locale.ROOT, "%.1f", Float.valueOf(
                                seam / Math.max(1e-7f, lastDeliveredDelta))));
            }
            if (lastDeliveredAt >= 0) {
                seamPending = true;      // 交接后第一块到达时测接缝 ✓（carryBuf=0 也测 ✓）
            }
            // **可听位置**（2026-10-09 ✓ 用户：交接后进度 25→27 ✓）：上报位置已改成
            // `outFrames() - 未交付余量` ✓，这里把交接瞬间的三个数并排打出来 ✓
            // ⇒ 若「可听位置」与「交接前」连续 ✓ 则界面不会再跳 ✓（且 dex 里有 ASCII/中文特征可验 ✓）。
            long before = framesBeforeHandover == null ? -1L : framesBeforeHandover.longValue();
            long after = carryBuf.frames();
            if (before >= 0L) {
                Logger.info("[qplayer-engine] 可听位置：交接前 {} 帧（{} s）· 未交付余量 {} 帧（{} ms）",
                        Long.valueOf(before),
                        String.format(java.util.Locale.ROOT, "%.3f", Double.valueOf(before / (double) Math.max(1, outRate))),
                        Long.valueOf(after),
                        String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(after * 1000.0 / Math.max(1, outRate))));
            }
            Logger.info("[qplayer-engine] 交接余量：carryBuf {} 帧（{} ms）· deck 位置 {} 帧（{} s）",
                    Integer.valueOf(carryBuf.frames()),
                    String.format(java.util.Locale.ROOT, "%.1f",
                            Double.valueOf(carryBuf.frames() * 1000.0 / Math.max(1, outRate))),
                    Long.valueOf(nextDeck.outFrames()),
                    String.format(java.util.Locale.ROOT, "%.3f",
                            Double.valueOf(nextDeck.outFrames() / (double) Math.max(1, outRate))));
        }
        next = null;
        if (nextDeck != null) {
            Deck old = current;
            current = nextDeck;
            // 上游在这里丢掉出场 deck（Drop 关掉它的解码器）
            retire(old);
        }
        final boolean wasTransitioning = transitioning;
        transitioning = false;
        logHandoverContinuity(nextDeck);
        logHandoverPosition(framesBeforeHandover, wasTransitioning);
        // 刚结束的这次过渡**真正渲染了多少帧**，先记下来再归零。
        //
        // 为什么要记：调用方（引擎）原先只能拿「快照里曲目路径变了」那一刻的帧数差
        // 去估算交叠时长，而那是按块边界量的——日志上就出现「窗口 4000 ms、实际交叠
        // 3900 ms」这种看着像少了 100 ms 的数（实测差 142 ms，比一块还多）。
        // 真正渲染了多少帧这里最清楚（`transitionPos` 就是判据本身），如实交出去。
        lastTransitionFrames = transitionPos;
        // 与上游的 `transition_pos = None` 对应：`transitioning` 关掉之后这个值
        // 不再有意义，归零免得它被别处误读。
        transitionPos = 0L;
        // 这次过渡的结论**跟着过渡一起结束**。
        //
        // 不清的话，`transitionNote()` 里「active 非空就用它」那一支会一直命中：
        // 过渡走完之后它再也不看已经就绪的那一份，于是界面上「接下来这次怎么接」
        // 永远显示的是**上一首**的窗口与跳过量（CLI 的「分析：」那行直接读它）。
        // 消费这个字段的地方都在过渡窗口内读（CLI 在进入过渡那一刻读一次），
        // 所以清在这里是安全的。
        activePlan = TransitionPlan.NONE;
        // 触发提前量回到窗口本身：自动点位的「丢尾」只属于刚刚结束的那一次过渡
        triggerLead = window;
        // 下一首的过渡要重新分析——窗口也可能因为新的素材而变
        resetPreparation();
    }

    /**
     * 从队列里取出下一个能打开的文件（<strong>过渡不再走它</strong>，见 {@code openPreparedDeck}）。
     */
    private Deck openNext() {
        String p;
        while ((p = queue.pollFirst()) != null) {
            try {
                Deck d = openDeckWithHeaders(p, outRate, outChannels, headersLookup);
                d.setSpatial(spatialEnabled);
                return d;
            } catch (AudioException e) {
                skipped.add(new Skipped(p, e.getMessage()));
            }
        }
        return null;
    }

    /**
     * 关掉一个已经退役的 deck。
     *
     * <p>上游靠 {@code Drop}：deck 被丢弃时它拥有的解码器（文件句柄 / 网络连接）
     * 跟着释放。Java 没有析构，所以<strong>每一处「上游会丢弃 deck」的地方都要显式关</strong>——
     * 漏一处就是文件句柄一直挂着（见 {@link Deck#close()} 的注释）。
     *
     * <p>关的是<strong>已经不再被渲染</strong>的 deck：被换下来的出场 deck、
     * 被作废的准备结果里那个 deck。正在出声的 {@link #current} 与 {@link #next}
     * 一个都不会走到这里。
     */
    private static void retire(Deck d) {
        if (d != null) {
            d.close();
        }
    }

    /**
     * 交叉核对失败时的安排：<strong>不做对拍</strong>。
     *
     * <p>上游在两处写的是同一段「{@code TransitionPlan::default()} 再把 note 改成
     * 『队首曲目打不开，本次不做对拍』」（准备线程定位之前一次、安装时兜底一次）。
     * 这里收成一个工厂，好让那行文案只有一份——它是界面会读的字符串。
     */
    private static TransitionPlan noCuePlan() {
        return new TransitionPlan(0.0, PitchPlan.ZERO, 0.0f, "队首曲目打不开，本次不做对拍");
    }

    /**
     * 在<strong>准备线程</strong>上把「下一首」开好。
     *
     * <p>语义与 {@link #openNext()} 逐条对齐：从队首起逐个尝试，打不开的跳过，
     * 并把原因带回去（安装时并进 {@link #skipped}，界面上那行「跳过：xxx —— 为什么」
     * 就是它）。<strong>这条语义不能丢</strong>：一个坏文件不该毁掉整张专辑的播放。
     *
     * <p>队列用<strong>快照</strong>（{@code List<String>}）而不是真队列：真队列归生产者线程，
     * 准备线程只能读一份副本；它把 {@code took} 带回去，安装时照着弹真队列。
     */
    private static PreparedDeck openPreparedDeck(List<String> queue, int outRate, int outChannels,
                                                 boolean spatial, HeadersLookup lookup) {
        List<String> took = new ArrayList<String>();
        List<Skipped> recorded = new ArrayList<Skipped>();
        for (int i = 0; i < queue.size(); i++) {
            String p = queue.get(i);
            took.add(p);
            try {
                Deck d = openDeckWithHeaders(p, outRate, outChannels, lookup);
                // 空间音频开关必须继承，否则切歌后它悄悄失效
                d.setSpatial(spatial);
                return new PreparedDeck(d, took, recorded);
            } catch (AudioException e) {
                recorded.add(new Skipped(p, e.getMessage()));
            }
        }
        return new PreparedDeck(null, took, recorded);
    }

    /**
     * 上游 {@code impl fmt::Debug for Director}。
     *
     * <p>上游用 {@code finish_non_exhaustive()}，所以末尾有个 {@code ..}；
     * {@code transition} 打的是 {@code name()}，{@code ready} / {@code prep_in_flight} /
     * {@code in_transition} 打的是 {@code is_some()}。
     * 与 {@link AudioBuf} / {@link Deck} 同一处理：队列内容、历史、缓冲都不打。
     */
    @Override
    public String toString() {
        return "Director { out_rate: " + outRate
                + ", out_channels: " + outChannels
                + ", window: " + window
                + ", transition: \"" + transition.name() + "\""
                + ", current: " + current
                + ", queue_len: " + queue.size()
                + ", prepared: " + prepared
                + ", ready: " + (ready != null)
                + ", prep_in_flight: " + (prep != null)
                + ", in_transition: " + transitioning
                + ", .. }";
    }

    /**
     * **总增益不塌陷**（2026-10-11 ✓ 根因修复 ✓ 可单测 ✓）。
     *
     * <p>等功率交叉是 `out = cos(p·π/2)·A + sin(p·π/2)·B` ✓；
     * 一旦 A **已经放干**，cos 项为 0 ✓ 而 sin(p) 在 p 小的时候很小 ✗
     * ⇒ 输出≈静音 ✓ ⇒ 交接后 B 满增益 ⇒ **25 dB 台阶** ✓（实测日志：前 -38 / 后 -13 dBFS ✗）。
     *
     * <p>这里把 B 的**未加增益原始素材**按 `max(p, sin(p·π/2))` 直接写进 out ✓
     * ⇒ A 没素材的那一段**只输出 B**，且淡入更早更满 ✓ 再也不会掉到没声音 ✗。
     *
     * @param out      已混合好的输出块（会被就地改写 ✓）
     * @param incoming B 的原始素材（**未加增益** ✓ 与 `Crossfade.render` 的入参一致 ✓）
     * @param progress 本块在窗口内的进度 p ∈ [0,1)
     * @param n        本块实际帧数
     */
    static void liftWhenOutgoingDrained(AudioBuf out, AudioBuf incoming, float progress, int n) {
        if (out == null || incoming == null || n <= 0) return;
        final int chs = Math.min(out.channels(), 8);
        final int m = Math.min(n, incoming.frames());
        if (m <= 0) return;
        final float[] ob = out.backingArray();
        final float[] bb = incoming.backingArray();
        final float g = Math.max(progress, (float) Math.sin(progress * Math.PI * 0.5));
        for (int c = 0; c < chs; c++) {
            final int dst = out.channelBase(c);
            final int src = incoming.channelBase(c);
            // **5 ms 交叉**（2026-10-11 ✓ 咔哒修复 ✓）：
            // 实测日志：电平接缝已经好了（-3.4 dB ✓）但 `接缝跳变 比值 24.7` ✗
            // ⇒ 覆盖率那一下是**硬切** ✓ 第一帧从混音直接跳 B ✗ = "咔" ✓
            // ⇒ 前 5 ms 从"原来的混音"平滑切到"B 的满增益" ✓ 电平与波形同时连续 ✓
            final int ramp = Math.max(1, Math.round(0.005f * 44100f));
            for (int i = 0; i < m; i++) {
                final float w = i >= ramp ? 1f : i / (float) ramp;
                final float target = bb[src + i] * g;
                ob[dst + i] = ob[dst + i] * (1f - w) + target * w;
            }
        }
    }
}
