package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.core.SourceFormat;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.audio.dsp.BinauralRenderer;
import dev.t1m3.qplayer.audio.dsp.Gain;
import dev.t1m3.qplayer.audio.dsp.PitchShifter;
import dev.t1m3.qplayer.audio.dsp.Resampler;

/**
 * 单个 deck：一路独立的音频源。
 *
 * <p>一条 deck 做五件事：<strong>解码 → 重采样到输出采样率 →（可选）变调 →
 * 声道映射 → 增益</strong>。
 *
 * <p>它<strong>不知道「过渡」的存在</strong>——那是引擎的事。这条边界让 deck 可以独立测试，
 * 也让后续的过渡实现不必关心解码细节。
 *
 * <h2>位完美是怎么落在这里的</h2>
 *
 * <p>注意这条链路<strong>没有任何分支</strong>去「绕过处理」。位完美段与过渡段的差别，
 * 只是各处理单元是否处于<strong>单位状态</strong>：
 *
 * <ul>
 *   <li>设备采样率 == 源采样率时，重采样器逐样本精确透传（相位 0 的核 = delta）</li>
 *   <li>{@link Gain} 在 unity 时不触碰缓冲</li>
 *   <li>变调器<strong>不在链上</strong>时整段跳过；在链上时按比例 1 空转只是近似透明，
 *       所以「挂上过变调器」的 deck 会如实报告自己不再位完美</li>
 * </ul>
 *
 * <p>这比「在过渡点重新接线」安全得多——后者几乎必然产生爆音。
 *
 * <h2>线程纪律（两条，都是架构级的）</h2>
 *
 * <ol>
 *   <li><strong>混音运行在生产者线程，不在音频回调里。</strong> 音频回调只做
 *       {@code pop_or_silence} 这一件事。所以这一层可以分配内存、可以用间接调用；
 *       但它仍然<strong>刻意避免每块分配</strong>——所有缓冲在 {@link #open} 时一次性
 *       分配好，之后 {@link #render} 只移动游标。理由不只是性能：频繁分配会让内存布局
 *       变散，而这个项目的目标之一是<strong>小占用</strong>。</li>
 *   <li><strong>过渡开始那一刻，生产者线程不做 IO。</strong> 上游有一条测试专门据此断言
 *       （{@code cfg(test)} 下的重活探针记录「哪条线程做了开文件 / 定位 / 造变调器」）。
 *       Java 没有等价的 {@code cfg(test)} 探针机制，但这条<strong>行为</strong>保住了：
 *       {@link #idlePitchShifter} 把「造变调器」这半件事挪到准备线程上，
 *       {@link #engagePitchWith} 在过渡开始那一刻只交出所有权。理由见
 *       {@link #idlePitchShifter} 的注释：造它要分配环形缓冲、铺粒窗、算粒长，
 *       而这曾经落在<strong>开始过渡的那一瞬间</strong>、在生产者线程上——那正是用户
 *       听到的「开始过渡时卡一下」的其中一份。
 *       <br>对应的 Java 侧验证方式在 {@code DeckTest} 里：解码器替身会
 *       记下开了几次音源、定位了几次、分别在哪条线程上，安装路径必须一个都不新增。</li>
 * </ol>
 *
 * <h2>与上游的类型差异（按 docs/audio-port.md 记录）</h2>
 *
 * <ul>
 *   <li>音源句柄是 {@link String}（本地路径或 http(s) URL），不是上游的 {@code &Path}：
 *       QPlayer 的音源可以是网络直链且需要请求头，{@code Decoder} / {@code Transition}
 *       用的是同一种句柄。字段名与访问器因此叫 {@code source}（上游是 {@code path}）。</li>
 *   <li>上游模块级私有函数 {@code copy_frames_into} / {@code copy_from_offset} →
 *       本类的 {@code static} 包内可见方法。上游的 {@code mod tests} 是子模块、看得见
 *       私有函数；Java 的测试同包可见，包内可见是它的等价物（比照 {@code PitchShifter}
 *       的做法，不把这些内部搬运函数放进公开 API）。</li>
 *   <li>上游 {@code pub const RENDER_FRAMES} → {@link #RENDER_FRAMES}；
 *       {@code DECODE_BUF_FRAMES} / {@code PITCH_CHUNK_FACTOR} 没有 {@code pub}，
 *       所以是包内可见的常量（测试要拿它们算参数关系）。</li>
 *   <li>上游 {@code u64 out_frames} → {@code long}；计数用的 {@code usize} → {@code int}。</li>
 *   <li>上游 {@code Result<Self>} / {@code Result<usize>} → 抛 {@link AudioException}
 *       （解码器打不开音源时由 {@code Decoder.open} 抛出，这里如实透传）；
 *       {@code Option<u64>} → {@code Long}（未知时 {@code null}）。</li>
 *   <li>上游的 {@code debug_assert_eq!} 只在 debug 构建生效；Java 没有 debug/release 之分，
 *       这里<strong>总是</strong>检查（更严，不会更松，比照 {@code Resampler} 的先例）。
 *       其中「变调器没能吃下整块」那条尤其不能松：真走到了就是参数被改坏，
 *       那会<strong>静默丢帧</strong>——正是本项目最不能接受的一类缺陷。</li>
 *   <li>上游 {@code impl fmt::Debug} → {@link #toString()}，但只报关键状态：
 *       derive 会把几万个采样刷进日志（比照 {@link AudioBuf} 的先例）。</li>
 *   <li>上游靠 {@code Drop} 隐式关闭解码器；Java 没有析构，所以补一个 {@link #close()}
 *       （见它的注释）。</li>
 *   <li>变调器的构造点：上游走 {@code crate::automix::new_pitch_shifter}（那个函数是
 *       一行包装，写在那里只是为了「调用方只该知道挂一个变调器，不该知道粒长怎么选」）。
 *       这里直接调 {@code new PitchShifter(channels, rate, semitones, chunk)}，
 *       语义相同，不另立包装函数——{@code Automix} 有自己的移植者，两边不撞。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::deck::Deck}。
 */
public final class Deck {

    /**
     * 解码暂存容量。
     *
     * <p><strong>必须覆盖任何单包</strong>：{@code Decoder.decodeInto} 在一包装不下时会
     * <strong>报错</strong>（而不是静默丢弃——那曾经造成 11% 的音频凭空消失）。
     * WAV / PCM 的包可能远大于常见编码，所以给到 65536 帧。
     */
    static final int DECODE_BUF_FRAMES = 65_536;

    /**
     * 变调器环形缓冲的余量倍数（相对 {@link #RENDER_FRAMES}）。
     *
     * <p>{@code pushFrom} 是部分接收语义，而这个系数决定了「一次 render 的产出能否
     * 全部喂进去」。取 4 倍留足余量，于是 {@link #render} 里那段喂入循环的
     * {@code break} 分支<strong>永远不会被走到</strong>——这一点由上游的 {@code debug_assert}
     * 钉住，Java 侧由 {@link #render} 里的显式检查钉住。
     */
    static final int PITCH_CHUNK_FACTOR = 4;

    /**
     * 单次 {@link #render} 的<strong>固定</strong>产出帧数。
     *
     * <p>这个值同时是内部暂存的容量，不能随便调——原因见 {@link #open} 与
     * {@link #render} 里的注释：暂存给富余会导致<strong>静默丢帧</strong>。
     *
     * <p>调用方给的缓冲必须 ≥ 这个值，否则 {@code render} 会<strong>报错</strong>
     * 而不是悄悄截断。
     */
    public static final int RENDER_FRAMES = 4096;

    /** {@code f32::EPSILON}：单位增益快路径的判定阈值（与 {@code Mixer} / {@code Gain} 同一份值）。 */
    private static final float F32_EPSILON = 1.1920929E-7f;

    /**
     * 源文件句柄。
     *
     * <p>引擎层需要它来回答「现在放的是哪一首」——界面要显示曲名，
     * 出问题时日志也要能定位到具体文件。
     */
    private final String source;

    private final Decoder decoder;

    /**
     * <b>素材钩子</b>（2026-10-07，目标①②路径 A）：交付给渲染之前把素材过一遍
     * （分轨 + 只在叠唱区间压人声 + 整段一次响度对齐）。默认 {@code null} ⇒ 一切照旧。
     *
     * <p>谁来装：上层（{@code VocalSeparationTransition.prepare}）——那时过渡几何
     * （cut/entry、叠唱区间、分离档位）全部已知；deck 只负责"问一句"，
     * 不知道"过渡""叠唱区间"是什么（策略留上层、取素材留下层）。
     *
     * <p><b>下一步要接的那一行</b>（还没接，接法与判据都写在
     * {@code docs/apple-automix-templates.md} 的"路径 A"一节）：
     * <pre>
     *   // Deck.java:785 附近，decoder.decodeInto(decBuf) 之后：
     *   if (materialHook != null) {
     *       materialHook.onDecoded(decBuf, got, sourceTimeSecs());   // 就地处理前 got 帧
     *   }
     * </pre>
     * 契约按"**处理已解码的这一块**"定义（不重读文件）—— 这样 deck 不需要把
     * source/headers 透下来，也不涉及第二次解码。
     */
    private volatile MaterialHook materialHook;

    /** 装/卸素材钩子（{@code null} = 卸载 ⇒ 逐位回到今天的行为）。 */
    public void setMaterialHook(MaterialHook hook) {
        this.materialHook = hook;
    }

    /** 当前素材钩子（判据/上层用）。 */
    public MaterialHook materialHook() {
        return materialHook;
    }
    private final Resampler resampler;
    private final Gain gain;

    /**
     * 可选的变调器。
     *
     * <p>{@code null} 表示<strong>一个采样都不碰</strong>，这是位完美的必要条件之一。
     * 一旦因为「声波重合」挂上过，就会留到下次跳转才摘下。
     */
    private PitchShifter pitch;

    /**
     * 可选的双耳渲染器（空间音频）。
     *
     * <p>与变调器同一套哲学：<strong>惰性挂载、挂上后常驻</strong>。挂上即改写采样值
     * （延迟线 + 头影滤波），所以 {@link #isPassthrough} 必须如实报告。
     *
     * <p>只对<strong>双声道输出</strong>有意义：接到 5.1/7.1 功放时应当直通而不是先折叠，
     * 所以非立体声输出时这个字段永远保持 {@code null}（{@link #setSpatial} 是 no-op）。
     */
    private BinauralRenderer spatial;

    /**
     * 用户要的空间音频开关。与 {@link #spatial} 分开存：设备不是立体声时
     * 开关可以是开的，但渲染器不存在——两条事实都必须如实可查。
     */
    private boolean spatialEnabled;

    /** 源声道数（解码输出）。 */
    private final int srcChannels;

    /** 目标声道数（设备要求）。 */
    private final int dstChannels;

    /** 解码暂存，按源声道布局。 */
    private final AudioBuf decBuf;

    /** 重采样输出暂存，仍是<strong>源</strong>声道布局（映射在之后）。 */
    private final AudioBuf rsBuf;

    /** 声道映射后的暂存，按目标声道布局。 */
    private final AudioBuf mapped;

    /**
     * <strong>上一块没装下的余量</strong>（按目标声道布局）。
     *
     * <p>为什么必须留着：{@link Resampler#pull} 一次能产出多少，取决于重采样器历史里
     * 攒了多少，而历史一次只吃得下一个解码包。于是块长会随解码包边界起伏——
     * 实测 48 kHz 源、44.1 kHz 输出时，pull 时不时只给出 2867 帧而不是 4096
     * （见 {@link #render} 与 {@code renderAlwaysDeliversAFullBlock}）。
     *
     * <p>单条 deck 出短块无所谓，过渡窗口里却是<strong>听得到的洞</strong>：
     * {@link Mixer#mixTwo} 把短的那一路的尾巴当作「这一路已经结束」补零，于是每短一次，
     * 融合里就少掉 4096−2867 = 1229 帧（约 28 ms）的声音。
     *
     * <p>攒满一块就得把多出来的那部分放到别处，而 {@code mapped} 正是被下一次
     * {@code pull} 结果覆盖的那一块，装不下——所以单独留一个缓冲。
     * 容量与 {@code want} 相同就够：只有 {@code take ≥ 1} 时才可能有余量，
     * 而那时余量必然小于一块。
     */
    private final AudioBuf carry;

    /** {@link #carry} 里的有效帧数。交付时<strong>整份搬走</strong>，所以不需要游标，也不需要前移。 */
    private int carryFrames;

    /** 已产出帧数（输出采样率下）。上游是 {@code u64}。 */
    private long outFrames;

    /** 解码器是否已耗尽。 */
    private boolean drained;

    /**
     * 重采样器的流末尾是否已经收尾过（{@link Resampler#drain} 只调用一次）。
     *
     * <p><strong>上游没有这个字段，也没有这一步</strong>——这是与上游的已记录偏差：
     * {@code sonalis-mix/src/deck.rs} 只有 {@code drained} 标志，解码器耗尽后只是
     * 「再转一圈把重采样器里残留的尾巴拉干净」，而 {@link Resampler#pull} 在流末尾
     * <strong>永远拉不出最后那 {@code TAPS_PER_SIDE} 帧</strong>（它算每个输出点都要看到
     * 后面 16 帧「未来输入」）。结果是每首曲子丢掉最后 16 帧：24000 帧的曲子只产出
     * 23984 帧、{@link #remainingFrames()} 永远停在 16（进度卡在 499 ms），gapless
     * 接缝整体早 0.33 ms。原因、影响与上游为何没发现都写在
     * {@link Resampler#drain} 的 javadoc 与 {@code docs/audio-port.md} 的差异表里。
     *
     * <p>{@link #seek} 会把它复位：跳转之后又是一段新的流末尾。
     */
    private boolean tailDrained;

    /** 本包在 {@link #decBuf} 里的总帧数。 */
    private int pktFrames;

    /**
     * 本包已经喂进重采样器的帧数。
     *
     * <p>这两个字段存在的理由：{@code decodeInto} 会一次填满整个解码缓冲
     * （可能上万帧），而重采样器的历史容量只有几千帧，必须分批喂。
     * <strong>喂不下时不能放弃</strong> —— 见 {@link #render} 里那段注释。
     */
    private int pktFed;

    /**
     * 打开一个音源作为 deck，把它的采样率与声道数对齐到设备要求。
     *
     * <p>所有暂存缓冲在这里一次性分配好，之后 {@link #render} 只移动游标，不做任何分配。
     *
     * @param source      本地路径或 http(s) URL
     * @param outRate     输出采样率
     * @param outChannels 输出声道数
     * @return 打开好的 deck
     * @throws AudioException 音源打不开或解不了（由宿主提供的 {@link Decoder} 抛出）
     */
    public static Deck open(String source, int outRate, int outChannels) {
        return open(source, java.util.Collections.<String, String>emptyMap(), outRate, outChannels);
    }

    /**
     * 同 {@link #open(String, int, int)}，但带上音源需要的请求头。
     *
     * <p>自动混音的进场曲可能是「需要请求头的插件直链」：没有这条通路，那种音源
     * 只能等它落到本地缓存之后才接得上（而缓存往往要等它上过一次）。
     */
    public static Deck open(String source, java.util.Map<String, String> headers,
                            int outRate, int outChannels) {
        // 先把句柄留下来：上游的 Decoder::open 会把 path 消费掉，Java 这边也是同一个道理
        // （句柄是引擎层要用的那一条事实，不能只活在解码器里）。
        String sourceHandle = source;
        Decoder decoder = headers == null || headers.isEmpty()
                ? Decoder.open(sourceHandle)
                : Decoder.open(sourceHandle, headers);
        TrackSpec spec = decoder.spec();
        int srcChannels = spec.layout.count();

        Resampler resampler = new Resampler(spec.sampleRate, outRate, srcChannels);
        Gain gain = new Gain(outRate);

        // 暂存容量**必须正好等于单次 render 的产出上限**，不能给富余。
        //
        // Resampler::pull 一次能产出的帧数由它自己的历史决定（升采样时会多于请求量），
        // 而 render 只拷贝得下 RENDER_FRAMES —— 多出来的部分会被**静默丢弃**。
        //
        // 这曾经造成约 8% 的音频丢失：44.1k → 48k 时 pull 一次能出 4458 帧，
        // 而每轮只交付 4096 帧，差额 362 帧凭空消失、且没有任何报错。
        int cap = RENDER_FRAMES;
        ChannelLayout dstLayout = ChannelLayout.of(outChannels);

        return new Deck(
                sourceHandle,
                decoder,
                resampler,
                gain,
                srcChannels,
                outChannels,
                new AudioBuf(spec.layout, DECODE_BUF_FRAMES),
                new AudioBuf(spec.layout, cap),
                new AudioBuf(dstLayout, cap),
                new AudioBuf(dstLayout, cap));
    }

    private Deck(String source, Decoder decoder, Resampler resampler, Gain gain,
                 int srcChannels, int dstChannels,
                 AudioBuf decBuf, AudioBuf rsBuf, AudioBuf mapped, AudioBuf carry) {
        this.source = source;
        this.decoder = decoder;
        this.resampler = resampler;
        this.gain = gain;
        this.srcChannels = srcChannels;
        this.dstChannels = dstChannels;
        this.decBuf = decBuf;
        this.rsBuf = rsBuf;
        this.mapped = mapped;
        this.carry = carry;
        this.carryFrames = 0;
        this.outFrames = 0L;
        this.drained = false;
        this.tailDrained = false;
        this.pktFrames = 0;
        this.pktFed = 0;
        this.pitch = null;
        this.spatial = null;
        this.spatialEnabled = false;
    }

    /** 音源句柄（本地路径或 http(s) URL）。上游是 {@code path()}。 */
    public String source() {
        return source;
    }

    /** 曲目事实。 */
    public TrackSpec spec() {
        return decoder.spec();
    }

    /** 源格式指纹。位完美判定要拿它和输出配置比。 */
    public SourceFormat sourceFormat() {
        return decoder.spec().sourceFormat();
    }

    /** 源采样率。 */
    public int inRate() {
        return resampler.inRate();
    }

    /** 输出采样率。 */
    public int outRate() {
        return resampler.outRate();
    }

    /**
     * 是否处于「透传」状态。
     *
     * <p>三个条件都必须成立：重采样器不改采样值、<strong>链上没有变调器</strong>、
     * <strong>双耳渲染器没有在干活</strong>。渲染器被旁路时（开关关掉）不碰采样值，
     * 可以继续如实报告透传——这与变调器「挂上就不能再声称位完美」不同，
     * 因为变调器即使比例是 1 也只是近似透明，而旁路的渲染器是
     * <strong>一个采样都不经过</strong>。
     */
    public boolean isPassthrough() {
        return resampler.isPassthrough() && pitch == null && !spatialIsActive();
    }

    /** 双耳渲染器是否在干活（开关开着且已挂载）。 */
    public boolean spatialIsActive() {
        return spatialEnabled && spatial != null;
    }

    /** 已产出帧数（输出采样率下）。 */
    public long outFrames() {
        return outFrames;
    }

    /** 是否已耗尽。 */
    public boolean isDrained() {
        return drained;
    }

    /**
     * 当前变调量（半音）。没挂变调器时为 0。
     *
     * <p>上游是 {@code Option::map(...).unwrap_or(0.0)}：没挂变调器时返回 0。
     */
    public float pitchSemitones() {
        PitchShifter ps = pitch;
        return ps == null ? 0.0f : PitchShifter.ratioToSemitones(ps.ratio());
    }

    /** 设置增益（分贝）。 */
    public void setGainDb(float db) {
        gain.setTargetDb(db);
    }

    /**
     * 预先挂上变调器（若尚未挂载），初始比例为 1。
     *
     * <p>用于「这个 deck 稍后会需要变调」的场合：先以单位比例挂上，
     * 变调量随后由 {@link #setPitch} 连续调整。
     *
     * <p><strong>必须提前挂。</strong> 中途挂载等于丢掉变调器最初那几十毫秒的前视，
     * 而那时它已经在输出音频了——听感是一声断裂。
     */
    public void engagePitch() {
        if (pitch != null) {
            return;
        }
        pitch = idlePitchShifter(outRate(), dstChannels);
    }

    /**
     * 造一个「以单位比例挂着」的变调器，<strong>但不挂上去</strong>。
     *
     * <p>参数与 {@link #engagePitch} 会挂的那个完全一致，两者可以互换。
     * 把「造」与「挂」拆开，是为了让造的<strong>那一半挪到准备线程上</strong>：
     * 造它要分配环形缓冲、铺粒窗、算粒长，而这曾经发生在<strong>开始过渡的那一瞬间</strong>、
     * 在生产者线程上——那正是用户听到的「开始过渡时卡一下」的其中一份。
     * 现在由准备线程提前造好，安装时只交出所有权（{@link #engagePitchWith}）。
     *
     * <p><strong>为什么直接构造、不经过 {@code automix::new_pitch_shifter}：</strong>
     * 上游那个函数是一行包装，写在那里只是为了让调用方「只该知道挂一个变调器，
     * 不该知道粒长怎么选」。这里直接调 {@link PitchShifter} 的构造器，语义相同。
     *
     * @param outRate     输出采样率
     * @param outChannels 输出声道数（变调器吃的是声道映射之后的缓冲，所以用目标声道数）
     * @return 以单位比例挂着的变调器（调用方负责交给 {@link #engagePitchWith}）
     */
    public static PitchShifter idlePitchShifter(int outRate, int outChannels) {
        int chunk = RENDER_FRAMES * PITCH_CHUNK_FACTOR;
        return new PitchShifter(outChannels, outRate, 0.0f, chunk);
    }

    /**
     * 挂上<strong>已经造好</strong>的变调器（比例 1）。
     *
     * <p>与 {@link #engagePitch} 的区别只有「谁造的」，见 {@link #idlePitchShifter} 的注释。
     *
     * <p>链上已经有变调器时<strong>保持不变</strong>（传进来的那个被丢掉）：与
     * {@code engagePitch} 一致——已经在攒前视的那个不能被换掉，换了就是丢掉最初几十毫秒，
     * 听感是一声断裂。
     *
     * @param shifter 已造好的变调器。上游按值接收、类型上不可能是空，Java 只能显式挡住
     */
    public void engagePitchWith(PitchShifter shifter) {
        if (shifter == null) {
            // Rust 的签名要求实参存在；Java 允许 null，所以把这条前置条件显式化
            throw new IllegalArgumentException("变调器不能为 null");
        }
        if (pitch != null) {
            return;
        }
        pitch = shifter;
    }

    /**
     * 设置变调量（半音）。0 表示不变调。
     *
     * <p>语义上有两处刻意的设计：
     *
     * <ol>
     *   <li><strong>从零变到非零时挂上变调器；反过来不会拆掉它。</strong>
     *       拆掉等于把缓冲里那几十毫秒的音频丢掉，听感是一声咔哒。
     *       粒窗比例等于 1 时是近似透明的，所以让它留在链上以单位状态空转，
     *       与 ADR-003「处理单元常驻、靠单位状态区分段落」的做法一致。</li>
     *   <li>代价是<strong>挂上之后这个 deck 就不再位完美</strong>，{@link #isPassthrough}
     *       会如实报告。这是可接受的：调用方只在用户主动选了「声波重合」时才会挂它。</li>
     * </ol>
     */
    public void setPitch(float semitones) {
        PitchShifter ps = pitch;
        if (ps != null) {
            ps.setSemitones(semitones);
            return;
        }
        // 没有变调器又不需要变调 → 什么都不做，保持位完美
        if (Math.abs(semitones) < 1e-6f) {
            return;
        }
        int rate = outRate();
        int channels = dstChannels;
        int chunk = RENDER_FRAMES * PITCH_CHUNK_FACTOR;
        // 与 idlePitchShifter 同一个构造点（上游这里也走 automix::new_pitch_shifter）
        pitch = new PitchShifter(channels, rate, semitones, chunk);
    }

    /**
     * 设置空间音频（双耳渲染）开关。
     *
     * <p>语义与 {@link #setPitch} 一致：<strong>首次开启时挂载，之后常驻</strong>。
     * 关掉只是旁路——渲染器留在链上但不碰采样值，再开时状态还在，
     * 不需要重新积累延迟线的历史。
     *
     * <p>只有立体声输出才会真正挂载（见 {@link BinauralRenderer} 的类注释：
     * 接功放应当直通）。非立体声时这是个记录开关状态的 no-op，
     * {@link #spatialIsActive} 会如实报告「没有在干活」。
     */
    public void setSpatial(boolean enabled) {
        spatialEnabled = enabled;
        if (!enabled || spatial != null) {
            return;
        }
        if (dstChannels != 2) {
            // 双耳渲染折叠成两耳；输出本来就不是两声道时没有意义
            return;
        }
        // 渲染器的声源表按**源布局**建：它接在重采样之后、声道映射之前，
        // 看到的是解码出来的原始声道（5.1 → 双耳），不是设备声道。
        ChannelLayout srcLayout = decoder.spec().layout;
        spatial = new BinauralRenderer(srcLayout, outRate());
    }

    /**
     * 跳到指定位置（秒），返回<strong>实际</strong>落点。
     *
     * <p>会把整条链路的状态清干净：解码器的待交付数据、重采样器的历史、
     * 正在进行的增益斜坡、以及变调器。<strong>少清一样，跳转后就会听到一小段
     * 错位的声音</strong>。
     *
     * <p>变调器在这里被<strong>摘掉</strong>而不是重置：跳转是一次明确的定位动作，
     * 转调的意义在跳转后就没了，而重新挂上要重新积累几十毫秒的前视。
     *
     * <h3><strong>已知边界（2026-10-06 实测）：定位落点与「自然播到的位置」不是同一根轴</strong></h3>
     *
     * <p>同一个文件、同一个秒数，{@link #seek} 之后读到的样本与<strong>从 0 自然播</strong>
     * 到那个位置读到的样本实测差 <strong>2304 帧（48.000 ms @48k）</strong>；
     * 而 {@code Automix.readStereoWindow}（离线读过渡素材）与 {@code seek} <strong>逐位相同</strong>。
     * 换成无损 WAV 素材时这个差是 0，所以根因在解码器层（桌面端 MP3 适配器：jlayer 把
     * Info/Xing 帧当一帧真解、而头部只丢了 1105 采样；以及定位后比特池没有预热），
     * <strong>不在本类</strong>——这里两边走的是同一条 {@code Decoder.seek}。
     * 判据见 {@code WindowReadAlignmentProbe}。绝不要在这里加「凭猜测的补偿」：
     * {@link #render} 的块记账很敏感，而补偿量是解码器的内部状态。
     *
     * @param secs 目标位置（秒）
     * @return 实际落点（秒）
     * @throws AudioException 定位失败（由解码器抛出）
     */
    public double seek(double secs) {
        // 上游在这里调 cfg(test) 的重活探针 crate::probe::record_seek(&self.path)：
        // 「对拍定位也该发生在准备线程上」。Java 侧没有等价机制，行为由
        // DeckTest 里那个「记线程名的解码器工厂」守着。
        double actual = decoder.seek(secs);

        // 重采样器的历史装的是跳转**之前**的声音，必须丢掉
        resampler.reset();
        // 增益斜坡也不该跨过跳转继续跑
        gain.snapTo(gain.target());
        // 变调器的粒缓冲同样是旧位置的声音
        pitch = null;
        // 双耳渲染器的延迟线里也是旧位置的声音——留着会让跳转后
        // 听到一小段「从旧位置飘过来」的余振
        if (spatial != null) {
            spatial.reset();
        }
        // 手上没喂完的那一包同样作废
        pktFrames = 0;
        pktFed = 0;
        // **攒块余量也必须清**。它装的是跳转**之前**那一块没装下的声音
        // （实测 44.1k→48k 时每块能余 300 帧上下，最多近一千帧 ≈ 20 ms），
        // 留着它 = 跳转后先响一下旧位置的声音——正是这个类一直警告的那类缺陷。
        carryFrames = 0;
        drained = false;
        // 跳转之后又是一段新的流末尾，收尾标记必须跟着复位
        tailDrained = false;

        // 进度按**实际落点**记账，不是请求值 ——
        // 否则界面上的进度条会在松手后跳回去。
        // Math.max(..., 0.0) 是上游 `as u64` 的饱和转换语义（负数与 NaN 都落到 0），
        // Java 的 (long) 转换会给出负值。
        outFrames = (long) Math.max(actual * (double) outRate(), 0.0);

        return actual;
    }

    /**
     * 增益是否处于单位状态（位完美段的必要条件之一）。
     *
     * <p>阈值是 {@code f32::EPSILON}：与 {@link Gain} 内部的快路径判定用同一个值，
     * 否则会出现「{@code gainIsUnity()} 说单位、{@code process} 却还在乘」的错位。
     */
    public boolean gainIsUnity() {
        return Math.abs(gain.target() - 1.0f) < F32_EPSILON && !gain.isRamping();
    }

    /**
     * 估算剩余帧数（输出采样率下）。总帧数未知时返回 {@code null}。
     *
     * <p><strong>必须用 {@link #outFrames}（已产出）来算，不能用解码器的进度。</strong>
     *
     * <p>解码器报告的 {@code framesDecoded} 是「已交付到解码缓冲」的帧数——
     * 由于 {@code decodeInto} 一次会填满整个缓冲（可能上万帧），它<strong>远大于</strong>
     * 已播放的帧数。曾经用错这个值，结果过渡判断认为「快结束了」，
     * 在一首歌刚播到一半时就提前触发过渡（实测 1.56 s 处触发，而正确位置是 2.5 s）。
     *
     * @return 剩余帧数；总帧数未知时为 {@code null}（上游是 {@code Option<u64>}）
     */
    public Long remainingFrames() {
        Long totalSrc = decoder.spec().totalFrames;
        if (totalSrc == null) {
            return null;
        }
        double ratio = (double) outRate() / (double) Math.max(inRate(), 1);
        long totalOut = (long) ((double) totalSrc.longValue() * ratio);
        // 上游的 saturating_sub：已产出超过总长时落在 0，而不是回绕成天文数字
        return Long.valueOf(Math.max(totalOut - outFrames, 0L));
    }

    /**
     * 产出最多 {@code out.capacity_frames()} 帧，返回实际帧数。
     *
     * <p><strong>返回 0 表示本曲已结束</strong>（挂变调器后，开头几十毫秒也可能返回 0——
     * 那是变调器在攒前视，不是结束）。
     *
     * <p>内部会一直推进到填满或耗尽，所以调用方不必自己循环。
     *
     * <h3>块长<strong>必须</strong>是常量，这是过渡能听的前提</h3>
     *
     * <p>「填满」不是可选的优化，而是契约的一半：过渡窗口里有<strong>两条</strong> deck 在
     * 混音，而混音器（{@link Mixer#mixTwo}）只认长度——短的那一路的尾巴
     * 被当作「这一路已经结束」补零。两条 deck 各自独立地按自己的解码节奏
     * 产出，块长一旦不齐，短的那一路就会在融合里<strong>凭空少掉一段声音</strong>。
     *
     * <p>实测（48 kHz 源、44.1 kHz 输出、16 秒的 {@code ai} 窗口）：块长时不时从 4096
     * 掉到 2867，于是每掉一次就少 1229 帧 ≈ 28 ms —— 听感正是
     * 「融合阶段时不时顿一下」。而单条 deck 播放时同样的短块完全听不出来，
     * 这就是它一直没被发现的原因。
     *
     * <p>所以这里改成<strong>攒满一块再交付</strong>：一轮拉不满就再喂、再拉，多出来的部分
     * 存进 {@link #carry}。绝不做的是「截断」——那会静默丢帧，
     * 是本项目最不能接受的一类缺陷。
     *
     * @param out 输出缓冲，容量必须 ≥ {@link #RENDER_FRAMES}
     * @return 写入的帧数；0 表示本曲已结束
     * @throws IllegalArgumentException 输出缓冲的声道数或容量不满足契约
     * @throws AudioException           解码失败
     */
    public int render(AudioBuf out) {
        // 上游是 debug_assert_eq!；Java 没有 debug/release 之分，这里总是检查（更严）
        if (out.channels() != dstChannels) {
            throw new IllegalArgumentException(
                    "输出声道数与 deck 打开时约定的不一致：" + out.channels() + " ≠ " + dstChannels);
        }

        // 产出量固定为 RENDER_FRAMES，与内部暂存容量严格一致。
        // 调用方给更小的缓冲会直接报错——**宁可失败也不要静默截断**。
        if (out.capacityFrames() < RENDER_FRAMES) {
            throw AudioException.config("输出的缓冲至少要能放 " + RENDER_FRAMES
                    + " 帧，当前只有 " + out.capacityFrames());
        }
        int want = RENDER_FRAMES;

        // 0) 先把上一块没装下的余量交付出去（见 carry 字段的注释）。
        //    余量必然小于一块，所以整份搬走、不需要前移。
        int written = 0;
        if (carryFrames > 0) {
            int n = carryFrames;
            copyFramesInto(carry, out, 0, n);
            carryFrames = 0;
            written = n;
            outFrames += (long) n;
            // **不能就此返回**：余量必然小于一整块，直接交出去就是交了一块短的，
            // 而过渡窗口里短块 = 补零 = 听得见的静音洞。继续往下走，
            // 从第 written 帧起把这一块补满（余量比接下来产出的内容更早，
            // 所以顺序仍然是对的）。
            //
            // 变调器在链上时曾经必须在这里返回——因为它的 pull 只会从第 0 帧开始写，
            // 会把刚交付的余量盖掉。那条限制已经由 `PitchShifter#pull(out, offset)` 解掉。
        }

        while (true) {
            // 1) 有解码好但还没喂完的数据时，先喂一点进重采样器。
            //
            // 这一步每轮**只喂一次、不循环**：喂不进（返回 0）说明历史缓冲满了，
            // 必须靠下面的 pull 触发 reclaim 才会腾出空间。
            // 写成「while fed < got 循环喂、took == 0 就 break」会在一包上万帧时
            // 只喂进几千帧、其余全部丢弃。
            if (pktFed < pktFrames) {
                int took = resampler.pushFrom(decBuf, pktFed);
                pktFed += took;
            }

            // 2) 拉一次。
            //
            // **不做二次截断。** 这个循环与历史上那个丢帧的版本长得像
            // （都是「攒满一块再返回」），差别只在**装不下的那部分去哪**：
            // 那个版本按 want - written 把 produced 截断，多出来的直接丢弃，
            // 每块丢几十到上千帧、累积成 1%~3% 的时长缺口；
            // 现在它进 carry 等下一块，一帧都不丢。
            int produced = resampler.pull(rsBuf);

            // 2b) 流末尾收尾：解码器已确认结束、手上没有没喂完的数据、而 pull 又拉不出
            //     东西时，剩下的不是「等更多输入」，而是**永远等不到的未来输入**。
            //
            //     pull 每算一个输出点都要看到它前后各 TAPS_PER_SIDE 帧输入，而流末尾缺的
            //     正是后面那 16 帧。不补这一段，一首 24000 帧的曲子只会产出 23984 帧，
            //     remainingFrames() 永远停在 16（进度卡在 499 ms）、gapless 接缝整体早
            //     0.33 ms。**上游没有这一步**（见 tailDrained 字段的注释），所以这是与
            //     上游的已记录偏差。
            //
            //     交给 Resampler#drain 按静音补齐缺失的未来输入：同采样率下补出来的
            //     尾帧逐位精确，跨采样率时是一段约 0.33 ms 的有意淡出。
            //
            //     只做一次：收尾之后 pos 已经越过真实输入，再 drain 必然是 0——
            //     tailDrained 只是把这个事实显式记下来，免得每轮 render 都白调一次。
            if (produced == 0 && drained && !tailDrained && pktFed >= pktFrames) {
                tailDrained = true;
                produced = resampler.drain(rsBuf);
            }

            if (produced > 0) {
                // 声道映射与双耳渲染是**同一个位置的两种选择**：
                // 渲染器开着时由它把源布局折叠成立体声（替换映射），
                // 否则走普通的映射。两者都产出「设备声道布局」的缓冲。
                if (spatialIsActive()) {
                    // null 安全：active 蕴含 spatial 已挂载
                    spatial.render(rsBuf, mapped);
                } else {
                    Mixer.mapChannels(rsBuf, mapped);
                }
                // 增益在 unity 时会直接返回，不触碰缓冲——位完美段就靠这个
                gain.process(mapped);

                // 3) 变调。**挂上了才走这一步**；没挂载时一个采样都不碰。
                //
                // 注意这里必须把整块喂进去：pushFrom 是部分接收语义。
                // 环形缓冲的容量按 PITCH_CHUNK_FACTOR 倍 RENDER_FRAMES 预留，
                // 而变调器自身占用的前视不超过 (grain−1)·MAX_RATIO，
                // 两者相加远小于容量，所以下面这个 break **永远不会被走到**。
                // 上游用 debug_assert 把它钉住：真走到了就是参数被改坏，
                // 那会静默丢帧——正是本项目最不能接受的一类缺陷。这里把它变成
                // 真实检查（更严）：宁可当场失败，也不要悄悄丢掉一段音频。
                //
                // **它同样必须攒满一整块再交付**，理由与上面那段 carry 的注释
                // 完全一样：`pull` 一次能产出多少由手上有多少输入决定，
                // 变调器又总扣着 (grain−1)·ratio 帧前视不放，于是块长会随解码包
                // 起伏。实测 10 秒窗口里 8 块偏短、混音端被补零 10752 帧
                // （244 ms 静音）——听感就是「过渡的时候又糊又断续」。
                // 所以这里不满一块就再转一圈：解码 → 重采样 → 喂变调器，直到满块
                // 或源真的到底。变调器**不会丢内容**：没吐出来的部分是留在
                // 它自己的环形缓冲里的，下一轮接着吐。
                PitchShifter ps = pitch;
                if (ps != null) {
                    int count = mapped.frames();
                    int fed = 0;
                    while (fed < count) {
                        int took = ps.pushFrom(mapped, fed);
                        if (took == 0) {
                            break;
                        }
                        fed += took;
                    }
                    if (fed != count) {
                        throw new IllegalStateException("变调器没能吃下整块（" + fed + "/" + count
                                + "）——环形缓冲偏小，正在静默丢帧");
                    }
                    int got = ps.pull(out, written);
                    written += got;
                    outFrames += (long) got;
                    if (written >= want) {
                        break;
                    }
                    // 还没满：再转一圈去喂、去拉（变调器的前视正是靠这一轮补上的）
                    continue;
                }

                // 4) 装进这一块；装不下的留给下一块。
                int room = want - written;
                int take = Math.min(mapped.frames(), room);
                copyFramesInto(mapped, out, written, take);
                int extra = mapped.frames() - take;
                if (extra > 0) {
                    copyFromOffset(mapped, take, carry, extra);
                    carryFrames = extra;
                }
                written += take;
                outFrames += (long) take;
                if (written >= want) {
                    break;
                }
                // 还没满：再转一圈去喂、去拉（历史刚被这次 pull 腾出空间）
                continue;
            }

            // 5) 拉不出来，而手上还有没喂完的数据：
            //    历史满 + 拉不出，理论上不该同时发生（pushFrom 里的 reclaim
            //    会按 pos 腾出空间）。真发生了就跳出，避免死循环。
            if (pktFed < pktFrames) {
                break;
            }

            if (drained) {
                // 解码器结束 + pull 拉不出 + 上面的 2b 已经收过尾（或收尾也产不出东西）
                // —— 这一曲真的到头了
                break;
            }

            // 6) 解码新的一包
            int got = decoder.decodeInto(decBuf);
            // **素材钩子**（路径 A）：交付渲染之前把这一块过一遍（分轨 + 只在叠唱区间压人声
            // + 整段一次响度对齐）。默认没装 ⇒ 一个采样都不动（与今天逐位相同）。
            // 就地处理，所以不涉及第二次解码；改不动时实现方原样留着。
            MaterialHook hook = materialHook;
            if (hook != null && got > 0) {
                hook.onDecoded(decBuf, got, Double.NaN);
            }
            if (got == 0) {
                drained = true;
                // 再转一圈：下一轮的 pull 会把重采样器里能拉的部分先交付，
                // 剩下的那几帧由 2b 的 drain 收尾
                continue;
            }
            pktFrames = got;
            pktFed = 0;
        }

        // 7) 变调器的流末尾收尾。
        //
        // 它一直扣着 (grain−1)·ratio 帧前视不放，而那些内容已经喂进去了、
        // 却永远等不到「未来的输入」。不把它们吐出来就是**静默丢尾**
        // （实测 48 kHz 源 → 44.1 kHz 输出、ratio=1.19 时少 1228 帧 ≈ 28 ms，
        // 与重采样器少最后 16 帧是同一类问题）。这里把缺失的未来输入按静音补齐。
        //
        // 两个位置上的讲究，都是踩过才写下来的：
        //   - **放在统一的出口**，而不是上面 `if (drained)` 那一支里：
        //     「解码器已结束、最后一包还剩几帧没喂完」会走上面那个防御性 break
        //     （出口不同），写进任一支都会漏掉另一支。
        //   - **不记「收过尾了」**：一次 {@code drain} 受这一块的剩余容量限制，
        //     可能只吐一半（实测 offset=3072 时只吐 1024 帧）。记了标记，
        //     另一半就永远出不来。{@link PitchShifter#drain} 自己可续做，
        //     吐干净之后再调立刻返回 0，所以这里每轮调都是安全的。
        //
        // 条件是「源已到底」＋「这一块没喂满」：满块时不收尾（后面还有内容要吐）。
        PitchShifter tail = pitch;
        if (tail != null && drained && written < want) {
            int got = tail.drain(out, written);
            written += got;
            outFrames += (long) got;
        }

        out.setFrames(written);
        return written;
    }

    /**
     * 关闭解码器。
     *
     * <p>上游没有这个方法：Rust 的 {@code Drop} 会在 deck 被丢弃时释放。Java 没有析构，
     * 而宿主的解码器握着文件句柄或网络连接（不释放会一直挂着），所以显式补一个。
     * 语义与 {@code Drop} 一致：只关解码器，不碰任何缓冲，也不做别的收尾。
     */
    public void close() {
        decoder.close();
    }

    /**
     * 把 {@code src} 的前 {@code n} 帧写到 {@code dst} 的 {@code offset} 起的位置。
     *
     * <p>不改变 {@code dst} 的有效帧数——长度由调用方最后统一 {@code setFrames}。
     *
     * <p>上游是模块级私有函数 {@code copy_frames_into}，按约定挂成本类的
     * {@code static} 方法；包内可见（同包测试要直接调它，对应上游
     * {@code mod tests} 里那两条搬运测试），刻意不进公开 API。
     *
     * <p>实现用 {@link AudioBuf#backingArray()} + {@link AudioBuf#channelBase(int)} 做整段
     * {@code System.arraycopy}：平面布局下「第 c 声道第 i 帧」就是
     * {@code channelBase(c) + i}，与上游的 {@code copy_from_slice} 逐段一一对应
     * （这是 {@link AudioBuf} 明确留给热循环的那条路，同 {@code Gain.process} /
     * {@code BinauralRenderer.render} 的做法）。{@code src} 与 {@code dst} 永远是两块不同的
     * 缓冲，所以没有重叠区间的问题。
     */
    static void copyFramesInto(AudioBuf src, AudioBuf dst, int offset, int n) {
        if (n == 0) {
            return;
        }
        // 上游的 debug_assert!(offset + n <= dst.capacity_frames())：越界时
        // System.arraycopy 自己会抛 IndexOutOfBoundsException，不用额外挡
        float[] s = src.backingArray();
        float[] d = dst.backingArray();
        for (int c = 0; c < dst.channels(); c++) {
            System.arraycopy(s, src.channelBase(c), d, dst.channelBase(c) + offset, n);
        }
    }

    /**
     * 把 {@code src[from..from+n]} 拷到 {@code dst} 的第 0 帧起，并把 {@code dst} 的有效帧数
     * 设为 {@code n}。
     *
     * <p>与 {@link #copyFramesInto} 的差别只有「源从哪一帧开始」：攒块时多出来的那一段
     * 不在源的开头，而在已经交付掉的那一段之后。攒块要<strong>一帧都不丢</strong>，
     * 所以这个偏移不能省。
     */
    static void copyFromOffset(AudioBuf src, int from, AudioBuf dst, int n) {
        if (n == 0) {
            dst.setFrames(0);
            return;
        }
        float[] s = src.backingArray();
        float[] d = dst.backingArray();
        for (int c = 0; c < dst.channels(); c++) {
            System.arraycopy(s, src.channelBase(c) + from, d, dst.channelBase(c), n);
        }
        dst.setFrames(n);
    }

    /**
     * 上游 {@code impl fmt::Debug}。照 {@link AudioBuf} 的先例只报关键状态：
     * 派生出来的 Debug 会把几万个采样刷进日志。
     */
    @Override
    public String toString() {
        PitchShifter ps = pitch;
        return "Deck{codec=" + decoder.spec().codec
                + ", inRate=" + resampler.inRate()
                + ", outRate=" + resampler.outRate()
                + ", channels=(" + srcChannels + ", " + dstChannels + ")"
                + ", pitchSemitones=" + (ps == null ? "null" : Float.toString(pitchSemitones()))
                + ", spatial=" + spatialIsActive()
                + ", outFrames=" + outFrames
                + ", drained=" + drained + '}';
    }
}
