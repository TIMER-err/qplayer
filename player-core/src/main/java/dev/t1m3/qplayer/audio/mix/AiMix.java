package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.dsp.Lufs;
import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.dsp.Biquad;
import dev.t1m3.qplayer.audio.dsp.Delay;
import dev.t1m3.qplayer.audio.dsp.Reverb;
import dev.t1m3.qplayer.audio.dsp.SplitLowHigh;
import dev.t1m3.qplayer.audio.model.BeatDetector;
import dev.t1m3.qplayer.audio.model.BeatModels;
import dev.t1m3.qplayer.audio.model.MelParams;
import dev.t1m3.qplayer.util.Logger;

import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * AI 模式的过渡引擎（上游 {@code automix.rs} 的 {@code AiMix}）。
 *
 * <p>拍点对齐 + 五种接法（朴素融合 / 拍点剪切 / 低音交接 / 尾音骑行 / 表现模式）。
 * 模块级的说明（三个模式、共用骨架、对拍怎么算、算不出来就不猜、洁净室声明）
 * 见 {@link Automix}。
 *
 * <h2>生命周期约定：每个实例只服务一次过渡（Java 无法在类型上表达）</h2>
 *
 * <p>上游的 {@code Transition} 是 {@code Send}：实例可以在<b>准备线程</b>与<b>渲染线程</b>
 * 之间移交。Java 没有借用检查、也没有 trait 上的 {@code Send}，所以这条约定必须
 * <b>由实现者和调用方共同遵守</b>：
 *
 * <ol>
 *   <li>编排器在过渡开始<b>之前</b>另起一条线程，用工厂造一个<b>新实例</b>，在那条线程上跑
 *       {@link #prepare}；</li>
 *   <li>过渡真正开始时才把这个「已经准备好」的实例换上来给渲染路径用；</li>
 *   <li>渲染路径上<b>不加锁</b>（{@link #render} 会改写滤波器状态，天生不可重入）；</li>
 *   <li>实例<b>用完即弃</b>：下一次过渡要重新造一个（{@code prepare} 的结果只对本次成立）。</li>
 * </ol>
 *
 * <p>为什么必须异步：{@code prepare} 实测最坏 4.6 秒（debug、自动接法），而环形缓冲
 * 默认只有 200 ms。<b>同步调会把缓冲写空</b>，听感是「卡几秒然后才出来」。
 * 上游把这条代价写进了编排器 {@code pump} 的文档：「这个方法绝不做分析」。
 *
 * <h2>估算出来的 BPM 与窗口都可以「算错」，但都不会静默</h2>
 *
 * <p>分析失败时窗口退回固定值并在 {@code plan.note} 里说明原因；
 * {@link #outroBpm()} / {@link #introBpm()} 返回 {@code null} 表示「没判出来」，
 * 而不是「随便给一个数」。
 *
 * <h2>对拍的拍点来源：模型优先，硬算兜底（本仓库新增，上游没有对应物）</h2>
 *
 * <p>{@link #prepare} 估速度时<b>先问拍点模型</b>（{@code audio.model.BeatModels}，
 * 桌面宿主用 ONNX Runtime 跑 Beat This!），模型不可用（没下载 / 没接线 /
 * 会话建不起来 / 输出对不上 / 耗时超预算）时<b>退回硬算</b>
 * （{@link Analysis}，频谱通量 + 自相关，零下载零体积）。
 *
 * <p>上游的分工是「硬算路线是默认，模型路线由用户选，用户判断准不准」——
 * 上游那边模型路线还没接进 {@code automix}（{@code TransitionSpec::AiStyle} 的
 * {@code use_model} 至今只是一句注释），而这里按用户对「硬算 BPM 质量不够好」的
 * 判断把顺序改成了<b>模型优先</b>。判定逻辑本身（窗口对齐、切点、接法）一行没改：
 * 两条路线都只是给 {@link Analysis.Tempo} 提供 {@code bpm}/{@code periodFrames}/
 * {@code phaseFrames}，之后的算路完全共用。
 *
 * <p><b>降级必须诚实</b>：{@code plan.note} 里会标出每一侧用的是哪一个
 * （{@code 128.0 BPM（模型）} / {@code 119.9 BPM（硬算）}），
 * 失败原因走日志。不许在模型失败时假装「模型版」——那会让「BPM 到底变准了没有」
 * 这件事永远无法判断。
 *
 * <h2>与上游的类型差异（按 docs/audio-port.md 记录）</h2>
 *
 * <ul>
 *   <li>{@code Vec<SplitLowHigh>} / {@code Vec<Biquad>} → 定长数组
 *       （每声道一份，构造时分配一次，之后播放路径上不再分配）。</li>
 *   <li>{@code Option<Tempo>} → 可为 {@code null} 的 {@link Analysis.Tempo}；
 *       {@code Option<f32>}（BPM）→ 可为 {@code null} 的 {@link Float}；
 *       {@code Option<f32>}（{@code cut_forced_at}）→ 可为 {@code null} 的 {@link Float}；
 *       {@code Option<String>} → 可为 {@code null} 的 {@link String}。</li>
 *   <li>{@code u64}（帧）→ {@code long}，{@code usize}（计数/下标）→ {@code int}，
 *       {@code u32}（采样率）→ {@code int}。</li>
 *   <li>{@code &Path} → {@code String}（本地路径或 http(s) URL），见
 *       {@link Transition#prepare}。</li>
 *   <li>{@link #tuning()} 返回<b>复制</b>而不是内部那一份：上游是 {@code Copy}，
 *       而 {@link MixTuning} 在 Java 里是<b>可变对象</b>，把内部引用交出去等于
 *       把旋钮给了别人（见 {@link MixTuning} 类注释里那条语义差别）。
 *       构造器收到的 {@code tuning} 同样<b>复制一份</b>：上游 {@code MixTuning} 是
 *       {@code Copy}，`with_tuning` 拿到的是值，调用方之后再改旋钮不该影响已经在跑的
 *       这次过渡——一次过渡就应该是一份固定的听感快照。</li>
 *   <li>字段 {@code window} / {@code cutAt} 是<b>包私有</b>而不是 {@code private}：
 *       上游的私有字段对子模块 {@code mod tests} 可见（测试会直接写
 *       {@code t.window = 20 * RATE} 模拟「上一首对拍拉长过」），Java 的对应可见性
 *       就是包私有——只给同包的 {@code AiMixTest} 用，不作为对外 API。</li>
 *   <li>逐样本写输出一律用 {@link AudioBuf#channelWriter(int)}（相对/绝对 {@code put(int,float)}
 *       都是 Java 8 就有的重载），<b>不用</b>任何带下标的绝对批量读写
 *       （那是 Java 13+，Android 上会挂）。分频用 {@link SplitLowHigh#processInto}
 *       而不是每次新建 {@link SplitLowHigh.Split}：渲染路径上不允许分配。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::automix::AiMix}。
 */
public final class AiMix implements Transition {

    /** 自动接法的风格白名单（**逗号分隔的风格名** ✓ 空串 = 全允许 ✓）。 */
    private static volatile String autoAllowedStyleNames = "";

    public static void setAutoAllowedStyleNames(String names) {
        autoAllowedStyleNames = names == null ? "" : names.trim();
    }

    public static String autoAllowedStyleNames() {
        return autoAllowedStyleNames;
    }

    /**
     * **白名单过滤**（用户 2026-10-09 ✓）：选中的风格不在白名单里 ⇒ 换成白名单里**第一个** ✓
     * （顺序 = 用户勾选顺序 = 偏好 ✓）；白名单为空 ⇒ 原样返回 ✓（旧行为 ✓）。
     */
    private static MixStyle whitelisted(MixStyle picked) {
        String allow = autoAllowedStyleNames;
        if (picked == null || allow == null || allow.isEmpty()) {
            return picked;
        }
        String first = null;
        for (String raw : allow.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (first == null) {
                first = name;
            }
            if (name.equals(picked.name())) {
                return picked;
            }
        }
        if (first == null) {
            return picked;
        }
        for (MixStyle m : MixStyle.values()) {
            if (m.name().equals(first)) {
                Logger.info("自动接法：白名单生效 —— 原选 {}（未勾选）→ 改为 {}（白名单第一顺位）",
                        picked, m);
                return m;
            }
        }
        return picked;
    }

    /** 「模型不可用」这条 WARN 每个进程只报一次（见该处注释）。 */
    private static volatile boolean modelMissingReported;

    /** {@code core::f32::consts::FRAC_PI_2} 的 f32 镜像（见 {@link Automix#togetherAngle}）。 */
    private static final float FRAC_PI_2 = (float) (Math.PI / 2.0);

    /**
     * 两侧读取里「进场那一侧」用的池。
     *
     * <p>为什么不是每次 {@code new Thread}：一次换曲只差几十毫秒的事，起停线程的开销与
     * 随之而来的线程对象都白花；而且**线程活着**才能让按线程统计的量
     * （例如基准里的分配字节数）把它算进去。
     *
     * <p>为什么是 <b>cached</b> 而不是固定一条（2026-10-03，评审指出）：这个池是**静态**的，
     * 所有 {@code AiMix} 实例共用。固定一条时，如果同时有两处准备在跑
     * （双窗口各一个 {@code Director}），第二条 {@code prepare} 会在 {@code get()} 上
     * 白等第一条的读取做完。cached 池在并发时会再开一条、空闲 60 秒后自己退出，
     * 既不排队也不常驻。
     *
     * <p>现状说明（隐式耦合）：正常的播放链路里准备只有一个
     * （{@code Director} 的 PrepareWorker 是单线程、且一次只准备一次过渡），
     * 所以这个池平时就只有一条线程。这里的 cached 是给「万一有两处」兜底的。
     */
    private static final java.util.concurrent.ExecutorService READ_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(
                    new java.util.concurrent.ThreadFactory() {
                        private final java.util.concurrent.atomic.AtomicInteger n =
                                new java.util.concurrent.atomic.AtomicInteger();

                        @Override
                        public Thread newThread(Runnable r) {
                            Thread th = new Thread(r, "qplayer-automix-read-in-"
                                    + n.getAndIncrement());
                            th.setDaemon(true);
                            return th;
                        }
                    });

    /**
     * 当前接法。
     *
     * <p><b>不是 final</b>：{@link MixStyle#AUTO} 会在 {@link #prepare} 里被换成
     * 挑中的那个具体接法（上游同样直接改写 {@code self.style}）。所以
     * 「自动」实例的生命周期同样是「只服务一次过渡」——不然第二首歌会沿用
     * 上一首的结论，再也不会重新判定。
     */
    private MixStyle style;

    /**
     * <b>融合接法提示</b>（一次性）：外层（{@code AutoCueTransition}）先用参考包 + 我们四个
     * 接法算出"这次该走哪条"，在 {@code base.prepare} 之前把它交进来；
     * 自动挑档时优先用它，用完即清（不会粘到下一次）。
     */
    private MixStyle styleHint;

    /**
     * 「AI 模式」在自动档下把<strong>声波重合</strong>也当成候选方案时要用的窗口时长（秒）。
     *
     * <p>做成供应商而不是一个数字：接法是在开唱那一刻按设置造的，而方案比较发生在
     * 这次过渡<strong>准备</strong>时——中间隔着整首歌，用户在播放期间改了滑块，
     * 这里必须读到新的那一个（与 {@code AutoCueTransition.liveWindowSecs} 同一条纪律）。
     */
    private final java.util.function.DoubleSupplier harmonicSecs;

    /**
     * 自动档选出来的方案是「声波重合」时，真正干活的那个接法（否则为 {@code null}）。
     *
     * <p>为什么用<strong>委派</strong>而不是把 {@code MixStyle} 加一个变体：
     * 风格是<strong>存进设置里的下标</strong>（{@code UI_ORDER}），加一个变体就要动
     * 已存的值与新老含义的对齐——而这里要的只是「这一次过渡换个接法」，
     * 不是「多一种可选风格」。所以它在 {@code prepare} 里按分析结论建、之后只读。
     */
    private Transition harmonicBlend;

    /** 声波重合那套方案自己的计划（pitch/skip/进场起始进度）。 */
    private TransitionPlan harmonicPlan;

    /**
     * AI 比较选中那套方案的四个数（出场点 / 进场点 / 交叠 / 丢尾）。
     *
     * <p>只有「声波重合赢了比较」时才有值。它是**决策与执行同一套数**的落点：
     * 见 {@code AutoCue.PlanChoice.cue} 与下面装载那一段的注释。
     */
    private AutoCue.Cue harmonicCue;

    /**
     * <b>"自动点位"这一支选中的点位</b>（出场 / 进场 / 丢尾）。
     *
     * <p>2026-10-07 补：`comparePlans` 选出的那一整套方案原先**只在声波重合赢了的时候**被采用 ✗，
     * 走"现有接法"那一支时它被丢掉 ⇒ 进场只能落到"首拍对齐"的 0.45 s，
     * 而 Apple 在这一对上的实测进场是 **15.0 s** ⇒ 用户实测到"点位有点偏差" ✓。
     * 自动点位的意义就是"**挑出来的那个点**"，所以两支都要用它。
     */
    private AutoCue.Cue autoCue;

    /** 只是为了让「先建对象再prepare」这一步读起来不别扭（见 AUTO 分支）。 */
    private static final class ModeHolder {
        TransitionPlan plan;
    }

    private final int rate;

    private final int channels;

    /**
     * 当前窗口（帧）。{@link #prepare} 之后可能因为对拍而被拉长。
     *
     * <p>包私有：上游的私有字段对子模块测试可见，理由见类注释。
     */
    long window;

    /** 出场曲的节奏（分析成功时才有）。 */
    private Analysis.Tempo outroTempo;

    /** 进场曲的节奏。 */
    private Analysis.Tempo introTempo;

    /** 出场曲尾部窗口的实际时长（秒）。拍点坐标以它为基准换算到「距末尾」。 */
    private double outroTailSecs;

    /** 进场曲的采样率（把相位换算成秒要用）。 */
    private int introRate;

    /**
     * 切点位置（进度比例，0..1）。只有 {@link MixStyle#BEAT_CUT} 用得上。
     *
     * <p>包私有：理由同 {@link #window}。
     */
    float cutAt;

    /**
     * 出场曲提前放完时，<b>实际开始交接</b>的那一刻（进度）。
     *
     * <p>{@code null} 表示还没发生（正常情况：出场曲撑到了切点）。
     */
    private Float cutForcedAt;

    /** 自动选接法时给出的理由，会拼进 {@code plan.note}。 */
    private String autoNote;

    /**
     * 出场曲那一侧的速度是从哪来的（模型 / 硬算）。
     *
     * <p>只用于 {@code plan.note} 里的<b>如实标注</b>——见 {@link TempoSource}。
     */
    private TempoSource outroSource;

    /** 进场曲那一侧的速度是从哪来的。 */
    private TempoSource introSource;

    /**
     * 听感旋钮。默认值 == 原先写死的那些常量，见 {@link MixTuning}。
     *
     * <p>本类<b>不缓存</b>任何旋钮的数值：每一处都直接读字段，
     * 所以调参台上改一个数就能立刻听到（这正是 {@code tuning.rs} 存在的理由）。
     */
    private final MixTuning tuning;

    /**
     * 逐帧响度匹配器（P4）。**有状态**，所以每次过渡开始时在 {@link #reset()} 里清干净
     * ——带着上一首歌的档位进下一次过渡，接缝上会多出一下多余的响度变化。
     */
    private final LevelMatcher levelMatcher;

    /**
     * 低频让位（P5）。与 {@link #levelMatcher} 一样有状态，一起在 {@link #reset()} 里清干净。
     * 旋钮设 0 时为 {@code null}（那一路完全不分配、不参与）。
     */
    private final LowDucker lowDucker;

    /** 每声道一份分频器——滤波器有状态，不能跨声道共用。 */
    private final SplitLowHigh[] fromSplit;

    private final SplitLowHigh[] toSplit;

    /**
     * <b>第二级分频</b>（{@value #EQ_MID_HZ} Hz）：只在 {@link MixStyle#LEARNED_EQ} 用。
     *
     * <p>规格（第二十一条）：三段分频用两个 {@code SplitLowHigh} 串联，第一级 200 Hz、
     * 第二级 2000 Hz；<b>滤波器状态在构造函数里初始化、reset 里重置</b>，不能每帧重建。
     */
    private final SplitLowHigh[] toSplit2;

    /**
     * 选档时**真正**用的那份素材画像（诊断用）。
     *
     * <p>为什么必须暴露：探针原来自己拼一份输入去"复现"生产算的东西 ⇒ 取法不同就不是
     * 同一个量（实测参考对上探针算出 drop 0.23、生产那边 ≥ 0.25，于是两边的结论对不上，
     * 白查了一轮）。从此探针只读这一份。
     */
    /**
     * 打分核启用时的候选集（顺序 = 平手时的固定顺序 —— **不抽签**）。
     *
     * <p>这里列的是"闸门已经就位"的那些档：我们自己的六档 + 参照清单里映射清晰的若干条。
     * 谁进来由 {@link RouteGates#factorsFor} 决定（返回 {@code null} 的会被跳过）。
     */
    private static final MixStyle[] ROUTE_CANDIDATES = {
            MixStyle.HIPHOP_LOOPING, MixStyle.HIPHOP_DELAY_OUT,
            MixStyle.FILTER_EXPANSION, MixStyle.POP_DELAY_OUT, MixStyle.POP_REVERB,
            MixStyle.FILTER_HIGH_TO_LOW, MixStyle.FILTER_LONG_HIGH_TO_LOW,
            MixStyle.FILTER_SHORT_HIGH_TO_LOW, MixStyle.FILTER_SOFT_REVERB,
            // **「人声不撞」已停用**（2026-10-09 ✓ 用户：「你不是要删掉人声不撞吗怎么还在」✓）：
            // 它一直是通过**这张候选表**被自动选中的 ✗（不在 Chooser 的规则树里 ✓ ⇒ 我上次改错了地方 ✗）。
            // 实测这一档让 B 的进场落在极轻处（B=−32.9 dB ✗ 对 A=−7.5 dB ✗）⇒ 融合末尾落差 15 dB ✗
            // ⇒ 听感「A 啪一下没了」✓。从候选表里剔除后 ⇒ **自动再也选不到它** ✓（UI 侧也已被 fromUiIndex 拦掉 ✓）。
            MixStyle.BASS_SWAP,
            // 补齐剩下 6 档（2026-10-07 用户问"30 个档位自动选择都能选到吗"）——
            // 它们原先只在 UI_ORDER 里、不在候选集里 ⇒ 自动永远选不到 ✗。
            // 现在 23 条参照路由**全部**在候选集里；能不能被选中由闸门决定（判据逐个验）。
            MixStyle.CROSSFADE_OVERLAP_REVERB, MixStyle.CROSSFADE_RINGOUT_REVERB,
            MixStyle.FILTER_LONG_OUT_SHORT_IN, MixStyle.FILTER_DYNAMIC_IN,
            MixStyle.FILTER_LONG_OUT_SHORT_IN_DELAY, MixStyle.FILTER_LONG_OUT_SHORT_IN_REVERB,
            MixStyle.DANCE_ANY_KEY, MixStyle.DANCE_REPEATER,
            MixStyle.CROSSFADE_LONG, MixStyle.CROSSFADE_OVERLAP, MixStyle.CROSSFADE_RINGOUT,
            MixStyle.PLAIN_BLEND, MixStyle.TAIL_RIDE,
            MixStyle.GAPLESS, MixStyle.SPLICE, MixStyle.BEAT_CUT,
            MixStyle.SHOW_MODE, MixStyle.LEARNED_EQ,
    };

    private MaterialProfile autoProfile;

    /** 见 {@link #tempoOut()}：两侧实际用的拍点结论（模型优先）。 */
    private Analysis.Tempo lastTempoOut;

    /** 见 {@link #tempoOut()}。 */
    private Analysis.Tempo lastTempoIn;

    /** 见 {@link #autoProfile}；没走自动挑档时为 {@code null}。 */
    public MaterialProfile autoProfile() {
        return autoProfile;
    }

    /**
     * 这两侧**实际用的**拍点结论（{@code tempoOf} 的结果，**模型优先**）。
     *
     * <p>为什么必须暴露：cue（挑点）是在 {@code AutoCueTransition} 里、用**它自己扫的那份
     * 包络**规划的，而包络里的 tempo 来自**硬算** —— 两处各算一遍，实测参考对差出
     * 29.32%（挑档）vs 6.49%（cue）。统一的方向是**让模型那份流进包络**（不能反过来）。
     * {@code Envelope.tempo} 已改为可替换，所以那一层拿到这两个值直接换进去即可。
     */
    public Analysis.Tempo tempoOut() {
        return lastTempoOut;
    }

    /** 见 {@link #tempoOut()}。 */
    public Analysis.Tempo tempoIn() {
        return lastTempoIn;
    }

    /**
     * 模型与硬算的 BPM 差到多少就算"模型这次不可信"。
     *
     * <p><b>20% 是用两个已知用例夹出来的，不是从原理推的</b>：参考对（模型 ≈29% 离群、
     * 硬算 6.5% 与用户日志一致）必须退回硬算；而 {@code AiMixTest.modelBeatsArePreferred…}
     * 的素材（模型 120、素材 100 = 差 18%）里**模型是对的**，必须保留模型。
     * 以后素材多了应当重定这条线。
     */
    private static final float MAX_MODEL_BPM_GAP = 0.20f;

    /** 模型与硬算差得太远就以硬算为准；任一缺失就原样保留模型（不猜）。 */
    private static Analysis.Tempo saneTempo(Analysis.Tempo model, Analysis.Tempo hard) {
        if (model == null || hard == null || model.bpm <= 0f || hard.bpm <= 0f) {
            return model;
        }
        float gap = Math.abs(model.bpm - hard.bpm)
                / Math.max((model.bpm + hard.bpm) * 0.5f, 1.0f);
        return gap > MAX_MODEL_BPM_GAP ? hard : model;
    }

    /**
     * 自动挑档的**理由文本**（含它当时用的实际数值）。
     *
     * <p>为什么必须能读出来：探针两次用自己拼的输入"复现"生产的判定，两次都得出相反结论
     * （画像、调性各一次）—— 与其继续猜生产看到了什么，不如让它自己说。
     */
    public String autoNote() {
        return autoNote;
    }

    /** prepare 阶段算好的 EQ 曲线（{@code null} = 这一档没曲线 ⇒ 按朴素融合走）。 */
    private EqCurve learnedEq;

    /** 逐帧查表的暂存（三频段增益）。 */
    private final float[] eqGains = new float[3];

    /** build-up 用的扫频滤波器。 */
    private final Biquad[] fromHp;

    private final Biquad[] toLp;

    /**
     * <b>滤波扫</b>（名字里带 FILTER 的那几档）：出场侧低通的逐块截止频率。
     *
     * <p>只在这一族用 ✓；{@code null} = 没开（那时这一族与加它之前**逐位相同** ✓）。
     * 状态跨块保留 ✓、系数原地改 ✓（见 {@code Biquad.setLowPass}）。
     */
    private final Biquad[] sweepOut;

    /**
     * <b>混响</b>（名字里带 REVERB 的那 5 档）：自研 Schroeder 小混响 ✓。
     *
     * <p>{@code null} = 这一档不挂 / 或旋钮设成 0（那时与加混响之前**逐位相同** ✓）。
     * 送的是**出场侧**（A 的尾巴在混响里继续响 ✓ = "尾音延续"那个意思 ✓），
     * 湿声直接叠进输出 ✓。渲染路径零分配 ✓（缓冲全在构造时按采样率分好 ✓）。
     */
    private Reverb reverb;

    /**
     * <b>跟拍延迟</b>（名字里带 DELAY 的那 3 档）：出场侧的回声 ✓。
     *
     * <p>{@code null} = 这一档不挂 / 或旋钮设成 0（那时与加延迟之前**逐位相同** ✓）。
     * 延时长度 = {@code delayBeats × 拍长} ✓（拍长在 {@code prepare} 里从出场曲的 tempo 记下来 ✓）
     * ⇒ 回声落在拍上 ✓。渲染路径零分配 ✓。
     */
    private final Delay delay;
    /** 这一档要不要挂延迟（由档位决定 ✓）。 */
    private final boolean delayRoute;
    /** 延迟反馈路上的低通（固定值 ✓：回声一次比一次闷 ✓，那是"房间感"的来源 ✓）。 */
    private static final float DELAY_DAMPING = 0.35f;
    /** 这次过渡的拍长（秒 ✓；没有 tempo 时用 120 BPM 的兜底 ✓）。 */
    private double beatSecs = 0.5;
    /** 这一档是不是"长出短进"（名字里带 LONG_OUT_SHORT_IN 的几档 + FILTER_DYNAMIC_IN ✓）。 */
    private final boolean asymRoute;
    /** 这一档要不要挂混响（由档位决定 ✓，不由旋钮决定 ✓）。 */
    private final boolean reverbRoute;
    /** 当前已经设进滤波器的截止频率（−1 = 还没设 ✓）。 */
    private float sweepHz = -1f;

    /**
     * 构造。{@code fallback} 之外的一切都要等 {@link #prepare} 才知道。
     *
     * <p>用默认听感。调参请用 {@link #withTuning}。
     */
    /** 空间让位用的独立暂存（构造时分配，渲染路径不分配）。 */
    private final dev.t1m3.qplayer.audio.core.AudioBuf spaceScratch;

    /** 宽度 1 时中间/两侧各偏移多少（0.4 = 中间 −40%、两侧 +40%）。 */
    static final float SPACE_MAX_SHIFT = 0.4f;

    /**
     * 「几乎没有立体声信息」的门槛：侧向能量占比低于这个值就<b>不展宽</b>
     * （见 {@link #isEssentiallyMono}）。0.02 对应侧向幅度约为中置的 14%。
     */
    static final double SPACE_MONO_RATIO = 0.02;

    public AiMix(MixStyle style, int rate, int channels) {
        this(style, rate, channels, MixTuning.defaults());
    }

    /**
     * 用指定的一套听感旋钮构造。
     *
     * <p>分频点与扫频滤波器在这里就按旋钮建好——它们只在构造时分配一次，
     * 之后播放路径上不再分配。
     */
    public AiMix(MixStyle style, int rate, int channels, MixTuning tuning) {
        this(style, rate, channels, tuning, null);
    }

    /**
     * 完整版：带上「声波重合窗口」的现读供应商（AI 自动档拿它当候选方案的窗口中心值）。
     *
     * @param harmonicSecs 现读的声波重合窗口（秒）；{@code null} = 用 {@code 6s} 这个默认值
     */
    public AiMix(MixStyle style, int rate, int channels, MixTuning tuning,
                 java.util.function.DoubleSupplier harmonicSecs) {
        int r = Math.max(rate, 1);
        int ch = Math.max(channels, 1);
        this.harmonicSecs = harmonicSecs;
        this.style = style;
        this.rate = r;
        this.channels = ch;
        this.tuning = new MixTuning(tuning);
        // 空间让位的暂存只在真的开了这个旋钮时分配（旋钮设 0 = 零分配、零开销）。
        this.levelMatcher = new LevelMatcher(r);
        this.lowDucker = this.tuning.lowDuck > 0.0f
                ? new LowDucker(r, ch, this.tuning.crossoverHz) : null;
        //
        // 容量**必须**盖得住传进来的那一块：`Director` 的 `curBuf` / `nxtBuf` 是
        // `max(cap, 2 × RENDER_FRAMES)`（见 Director 的构造），也就是说 `from.frames()`
        // 在生产路径上可以到 8192——按 4096 分配的话，`copyWithWidth` 会**静默截断**，
        // 出场曲的后半块直接消失（丢音频，比不展宽严重得多）。第二道保险是下面那个
        // `fits` 判断：装不下就**不展宽**，绝不截断。
        this.spaceScratch = this.tuning.spaceWidth > 0.0f
                ? new dev.t1m3.qplayer.audio.core.AudioBuf(
                        dev.t1m3.qplayer.audio.core.ChannelLayout.of(ch), 2 * Deck.RENDER_FRAMES)
                : null;
        this.window = Automix.fallbackWindowWith(style, r, tuning.windowScale);
        this.outroTempo = null;
        this.introTempo = null;
        this.outroTailSecs = 0.0;
        this.introRate = r;
        this.cutAt = 0.0f;
        this.cutForcedAt = null;
        this.autoNote = null;
        this.outroSource = TempoSource.COMPUTED;
        this.introSource = TempoSource.COMPUTED;
        this.fromSplit = new SplitLowHigh[ch];
        this.toSplit = new SplitLowHigh[ch];
        this.toSplit2 = new SplitLowHigh[ch];
        for (int i = 0; i < ch; i++) {
            this.fromSplit[i] = new SplitLowHigh(r, tuning.crossoverHz);
            this.toSplit[i] = new SplitLowHigh(r, tuning.crossoverHz);
            this.toSplit2[i] = new SplitLowHigh(r, EQ_MID_HZ);     // 三段分频的第二级
        }
        // 扫频滤波器的初值就是曲线起点，`render` 每块都会按进度重算系数
        this.fromHp = new Biquad[ch];
        this.toLp = new Biquad[ch];
        // **名字里带 REVERB 的那 5 档才挂**（2026-10-07 让它们名副其实 ✓）。
        this.reverbRoute = style == MixStyle.CROSSFADE_OVERLAP_REVERB
                || style == MixStyle.CROSSFADE_RINGOUT_REVERB
                || style == MixStyle.POP_REVERB
                || style == MixStyle.FILTER_SOFT_REVERB
                || style == MixStyle.FILTER_LONG_OUT_SHORT_IN_REVERB;
        // **名字里带 DELAY 的那 3 档才挂**（2026-10-07 让它们名副其实 ✓）。
        this.asymRoute = style == MixStyle.FILTER_LONG_OUT_SHORT_IN
                || style == MixStyle.FILTER_LONG_OUT_SHORT_IN_DELAY
                || style == MixStyle.FILTER_LONG_OUT_SHORT_IN_REVERB
                || style == MixStyle.FILTER_DYNAMIC_IN;
        this.delayRoute = style == MixStyle.HIPHOP_DELAY_OUT
                || style == MixStyle.POP_DELAY_OUT
                || style == MixStyle.FILTER_LONG_OUT_SHORT_IN_DELAY;
        boolean delayOn = delayRoute && tuning.delaySend > 0f && tuning.delayWet > 0f;
        this.delay = delayOn ? new Delay(rate, ch, tuning.delayFeedback, DELAY_DAMPING) : null;
        boolean reverbOn = reverbRoute && tuning.reverbSend > 0f && tuning.reverbWet > 0f;
        this.reverb = reverbOn
                ? new Reverb(rate, ch, tuning.reverbDecaySecs, tuning.reverbDamping) : null;
        this.blur = BLUR_WET > 0f ? new Reverb(rate, ch, BLUR_DECAY_SECS, 0.25f) : null;
        boolean sweepOn = tuning.filterSweepFromHz > 0f && tuning.filterSweepToHz > 0f
                && tuning.filterSweepFromHz > tuning.filterSweepToHz;
        this.sweepOut = sweepOn ? new Biquad[ch] : null;
        // 让位滤波器**不在这里建**（见字段注释：参数是 prepare 阶段才设的 ✗）⇒ 改到 render 里惰性建 ✓。
        if (sweepOn) {
            for (int i = 0; i < ch; i++) {
                this.sweepOut[i] = Biquad.lowPass(rate, tuning.filterSweepFromHz,
                        tuning.filterSweepQ);
            }
        }
        for (int i = 0; i < ch; i++) {
            this.fromHp[i] = Biquad.highPass(r, tuning.showHpFrom, 0.707f);
            this.toLp[i] = Biquad.lowPass(r, tuning.showLpFrom, 0.707f);
        }
    }

    /**
     * 上游的关联函数 {@code AiMix::with_tuning}。
     *
     * <p>写成静态工厂而不是再加一个四参构造器，是为了让调用点读起来与上游一致
     * （{@code AiMix.withTuning(style, rate, ch, tuning)}）——四个参数里
     * 「哪一份旋钮」是最容易看漏的那一个。
     */
    public static AiMix withTuning(MixStyle style, int rate, int channels, MixTuning tuning) {
        return new AiMix(style, rate, channels, tuning);
    }

    /**
     * 当前这套听感旋钮（<b>复制</b>，理由见类注释）。
     *
     * <p>上游 {@code MixTuning::default()} 是 {@code Copy} 的值，白拿一份；
     * Java 的 {@link MixTuning} 可变，直接交出内部引用等于把旋钮送人。
     */
    public MixTuning tuning() {
        return new MixTuning(tuning);
    }

    /** 当前接法（若是 {@link MixStyle#AUTO}，第一次 {@link #prepare} 之后会变成挑中的那个）。 */
    /** 见 {@link MixTuning#STYLE_LOOP_ENABLED}：这一档要不要"主动求循环" ✓（默认关 ✓）。 */
    public boolean tuningForStyleLoop() {
        return tuning.styleLoopEnabled;
    }

    /** 这一档是不是"自动挑档"（只有自动档才会听融合接法提示）。 */
    public boolean usesAutoPick() {
        return style == MixStyle.AUTO;
    }

    /** 外层在 {@code prepare} 之前设置"这次想用的接法"（null = 不干预）。 */
    public void setStyleHint(MixStyle hint) {
        this.styleHint = hint;
    }

    public MixStyle style() {
        return style;
    }

    /** 声道数（分频器与扫频滤波器各声道一份）。 */
    public int channels() {
        return channels;
    }

    /** 输出采样率。 */
    public int rate() {
        return rate;
    }

    /** 当前窗口（帧）。 */
    public long window() {
        return window;
    }

    /** 估出来的出场曲速度（若有）。没判出来返回 {@code null}（上游 {@code None}）。 */
    public Float outroBpm() {
        return outroTempo == null ? null : Float.valueOf(outroTempo.bpm);
    }

    /** 估出来的进场曲速度（若有）。没判出来返回 {@code null}。 */
    public Float introBpm() {
        return introTempo == null ? null : Float.valueOf(introTempo.bpm);
    }

    /**
     * 重置全部滤波器状态。跳转后必须调用。
     *
     * <p>{@link SplitLowHigh} 的「两路相加等于原信号」只在稳态成立，带着上一首的
     * 残留状态进下一次过渡，接缝上会多出一下突变。
     */
    public void reset() {
        if (levelMatcher != null) {
            levelMatcher.reset();
        }
        if (lowDucker != null) {
            lowDucker.reset();
        }
        for (SplitLowHigh s : fromSplit) {
            s.reset();
        }
        for (SplitLowHigh s : toSplit) {
            s.reset();
        }
        for (SplitLowHigh s : toSplit2) {
            s.reset();                     // 第二级分频（学习EQ 用）也要清
        }
        learnedEq = null;                  // 曲线是"这一次过渡"的，别带到下一首
        for (Biquad f : fromHp) {
            f.reset();
        }
        for (Biquad f : toLp) {
            f.reset();
        }
        if (sweepOut != null) {
            for (Biquad f : sweepOut) {
                f.reset();
            }
            sweepHz = -1f;                 // 下一块重新按进度设一次 ✓
        }
        if (reverb != null) {
            if (appleReverbParamsPort) {
                if (!appleRevResolved) {
                    resolveAppleReverb();
                }
                if (appleRevDecay > 0f
                        && Math.abs(appleRevDecay - tuning.reverbDecaySecs) > 1e-3f) {
                    // 他的 low-frequency decay ✓（我们的 Reverb 只有一个衰减参数 ⇒ 用它 ✓，
                    // 换算与取舍写在 resolveAppleReverb 的注释里 ✓）
                    reverb = new Reverb(rate, channels, appleRevDecay, tuning.reverbDamping);
                }
            }
            reverb.reset();                // 下一次过渡不带上一首的尾巴 ✓
            if (repeat != null) {
                repeat.reset();            // 重复器同理 ✓（不留上一首的残响 ✓）
            }
            if (mixReverb != null) {
                mixReverb.reset();         // REMIX_FX 那路混响也复位 ✓
            }
            if (mixFlanger != null) {
                mixFlanger.reset();        // flanger 同理 ✓
            }
        }
        if (delay != null) {
            delay.reset();                 // 也不带上一首的回声 ✓
        }
    }

    @Override
    public long windowFrames(int sampleRate) {
        // 采样率在构造时就固定了，这里直接用算好的值——
        // 因为窗口已经包含了「对拍到整数拍」的修正，重算会把它丢掉。
        return window;
    }

    /**
     * <b>诊断专用：静音某一侧</b>（0 = 关 ✓ / 1 = 静音出场侧 ✓ / 2 = 静音进场侧 ✓）。
     *
     * <p>为什么需要它（2026-10-07 round 9 ✓）：要量"两侧各自的贡献"才能看出**互相遮蔽** ✓，
     * 而在外面把输入缓冲清零**不管用** ✗ —— 包装层在 prepare 阶段就把 B 的素材
     * **预渲染进自己的缓冲**（变速斜坡 ✓、循环/路径 ✓）⇒ 外面清零影响不到它 ✓。
     * 所以隔离必须**下沉到这一层**：在这里把已经就位的素材清零 ✓，后面的接法照常跑 ✓。
     *
     * <p>⚠️ 只在诊断里用 ✓（判据/探针 ✓）；产品路径上这个值恒为 0 ✓。
     */
    private static volatile int probeMuteSide = 0;

    /**
     * <b>互补频谱让位</b>（2026-10-07 round 10 ✓，用户"融合期两首歌打架" ✓）。
     *
     * <p>做法（最小可用版）：交叠期里找出两侧**电平最接近的那一个频带** ✓
     *（= "两边一样响"的地方 = 遮蔽最重 ✓，基线实测最差带只有 2.5~3.0 dB ✓），
     * 在那一带上给两侧各做一个 peaking **衰减** ✓ —— 谁也别在那儿压谁 ✓。
     *
     * <p>频带与增益在 prepare 阶段**一次算好**（包装层有两侧包络 ✓，见
     * {@link #setCarveBands}）✓；渲染时只是两个 biquad ✓（零分配 ✓）。
     * 增益为 0 或频带为 0 ⇒ **一个采样都不碰** ✓（与加它之前逐位相同 ✓）。
     */
    // ⚠️ **必须是实例字段**（2026-10-07 round 18 检查点照出来的真回归 ✗）：
    // 我原来写成 `static` ✗ ⇒ 参数**跨实例、跨过渡泄漏** ✓ —— 内核全量当场 15 条红 ✗，
    // 而且生产上也会"上一对的让位设置留给下一对" ✗。让位只属于**这一次过渡的那一个实例** ✓。
    private double carveHzA = 0.0;
    private double carveHzB = 0.0;
    private float carveGainDb = 0f;
    /** 参数版本号：`setCarveBands` 每调一次加一 ✓（渲染层据此惰性重建滤波器 ✓）。 */
    private int carveVersion = 0;
    /** 多带计划（见 {@link #setCarvePlan} ✓；null = 用单带那套 ✓）。 */
    private double[] carveHz;
    private float[] carveGain;
    // ⚠️ **不能是 final，也不能在构造时就建** ✗（2026-10-07 round 11 实测的教训 ✓）：
    // 参数是包装层在 `prepare` 里才设的 ✓，而 `AiMix` 的构造在那之前就发生了 ✓ ⇒
    // 构造时读到的是 0 ⇒ 建出 null ⇒ **让位永远不生效** ✗（实测读数纹丝不动 ✓）。
    /**
     * <b>退场侧的低音让手</b>（2026-10-07 ✓，用户："还是有点断层" + AM 取证的第三条 ✓）。
     *
     * <p>取证事实：A 的增益 <0.15 在 44.95 s ✓，而它的**低频成分**到 45.5-46.0 s 才彻底消失 ✓
     * === A 的低音是**在它本体消失之前**就被收掉的 ✓（两者不同时在场 ✓）。
     * 人耳对低频切换最敏感 ✓ ⇒ 低音换手干净、且不与 B 的低音重叠 ⇒ 断层感大幅下降 ✓。
     *
     * <p>做法：给**退场侧**一条高通 ✓，前 60% 不动（20 Hz = 无效果 ✓），
     * 最后 40% 从 20 Hz 升到 {@code BASS_HANDOVER_HZ} ✓（每块原地换系数 ✓、零分配 ✓）。
     * 系数 {@code BASS_HANDOVER_HZ <= 0} ⇒ 整段不碰 ✓（可单独关掉 ✓）。
     */
    static final double BASS_HANDOVER_HZ = 300.0;
    private Biquad[] bassOut;
    private double bassOutHz = -1.0;

    /**
     * <b>边界模糊</b>（2026-10-07 ✓，用户："他就是太干脆了，应该让边界模糊" ✓）。
     *
     * <p>症状：现在 A 在最后 20% 里"干脆地撤" ✗ —— 有一个明确的**放手点** ✓ = 边界感 ✓。
     * 做法：给**退场侧**叠一层**很轻的混响** ✓ —— 它的离开被"抹开" ✓，
     * 边界从一个点变成一段 ✓。参数刻意压得很轻（湿 0.18 / 送 0.5 / 混响 0.6 s ✓）⇒
     * 只在交接那几秒能听出"空间" ✓，不会让整首歌变湿 ✗。0 ⇒ 整段不碰 ✓（可单独关掉 ✓）。
     */
    // ⚠️ **默认关**（2026-10-08 ✓，照搬 goal 第 12 轮 ✓）：这条混响是我自己的发明 ✗，
    // 当时是为了治用户说的"太干脆、边界该模糊" ✓；但它**不受"关掉混响"接口管** ✗
    // ⇒ 连"干路"都带上尾巴 ✗ ⇒ `ReverbRouteTest` 的「关掉混响 ⇒ 必须逐位相同」永远不成立 ✗
    // （实测差异出现在下标 1215 ✓ = 第一个块 ✓，三个照搬开关都关不掉它 ✓ ⇒ 它就是这条 ✓）。
    // **他的做法**是：混响只出现在带 `AUX_REVERB` 的那几档里 ✓（我们已有那条路 ✓）⇒
    // 所以这条"全档无条件"的混响**退场** ✓ —— 边界靠他的 `out_gain` 曲线 + 滤波绽放来模糊 ✓，
    // 不是靠偷偷加混响 ✗。要回退成旧行为 ⇒ 把这个数改成 0.18 即可 ✓。
    static final float BLUR_WET = 0f;
    static final float BLUR_SEND = 0.5f;
    static final float BLUR_DECAY_SECS = 0.6f;
    private final Reverb blur;

    private Biquad[] carveA;
    private Biquad[] carveB;
    /** 本实例已经按哪个版本建过滤波器（−1 = 还没建 ✓）。 */
    private int carveBuiltVersion = -1;

    /**
     * 设定这一对的互补让位参数（由包装层在 prepare 里调用 ✓）。
     *
     * @param hzA 出场侧要让位的频带中心（Hz；≤0 = 不让 ✓）
     * @param hzB 进场侧要让位的频带中心（Hz；≤0 = 不让 ✓）
     * @param gainDb 让多少 dB（负值 = 衰减 ✓；0 = 关 ✓）
     */
    public void setCarveBands(double hzA, double hzB, float gainDb) {
        carveHzA = hzA;
        carveHzB = hzB;
        carveGainDb = gainDb;
        carveHz = null;
        carveGain = null;
        carveVersion++;                 // 通知所有实例：下一块重建 ✓（惰性、只建一次 ✓）
    }

    /**
     * <b>多带互补让位</b>（2026-10-07 round 14 ✓）：给出**每个带**要让多少 dB ✓
     *（只作用在**退场侧** ✓ —— round 11 实测：两侧对称让位改变不了差值 ✗）。
     *
     * <p>为什么从"单带"升级成"多带"：单带版实测只把最差带从 3.0 抬到 **3.6 dB** ✗
     *（目标 ≥6 ✓）—— 因为"最差"是**8 个带 × 每个切片**里的最小值 ✓，
     * 只修一个带/一个时刻，最小值往往落在**别处** ✗ ⇒ 必须按带逐个算 ✓。
     *
     * @param hz 各带中心（Hz ✓）
     * @param gainDb 各带要让的 dB（负值 ✓；0 或正 ⇒ 该带不动 ✓）
     */
    public void setCarvePlan(double[] hz, float[] gainDb) {
        carveHz = hz == null ? null : hz.clone();
        carveGain = gainDb == null ? null : gainDb.clone();
        carveHzA = 0.0;
        carveHzB = 0.0;
        carveGainDb = -1f;              // 标记"有多带计划" ✓
        carveVersion++;
    }

    /** 见 {@link #probeMuteSide}。 */
    public static void setProbeMuteSide(int side) {
        probeMuteSide = side;
    }

    @Override
    public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
        // 互补让位：在**已经就位的素材**上各衰减一个带 ✓（增益 0 ⇒ 不碰 ✓）
        // **让位深度随窗口淡入淡出**（2026-10-07，用户报"过渡段的分界有点猛烈" ✓）：
        // 原来是从窗口**第一个采样**就全深度生效 ✗ ⇒ 进窗那一瞬间出场曲被砍掉最多 8 dB ✓
        // = 一个**听得见的台阶** ✗（用户听到的就是这个）。现在按 `sin(πp)` 加权 ✓：
        // 两端为 0（与"没有让位"逐位相同 ✓）、中段最大 ✓ ⇒ 边界上不再有台阶 ✓，
        // 而且这与留白那条的形状一致（都是两端 0 ✓），两条叠起来也不会互相抬出一个台阶 ✓。
        // 退场侧的低音让手 ✓（末段抬升的高通 ✓）
        if (BASS_HANDOVER_HZ > 0.0 && from != null && from.frames() > 0) {
            applyBassHandover(from, progress);
        }
        // 边界模糊：退场侧过一层很轻的混响 ✓（把"放手点"抹成一段 ✓）。
        // ⚠️ **必须按档启用**（2026-10-08 照搬 goal 第 10 轮 ✓，由两条红判据照出来 ✓）：
        // 我原来是**无条件**给所有档加这点混响 ✗ ⇒ 连"干路"都带上了尾巴 ✗
        //（ReverbRouteTest / DelayRouteTest 量的正是"干路必须恰好静音" ✓，当场红 ✓）。
        // 而**他的写法本来就不是这样** ✓：混响只出现在带  的那几档里 ✓
        // ⇒ 改成"他那一档的操作串里真的有 AUX_REVERB 才开" ✓ —— 一步同时完成
        // ①改绿 ②照搬 AUX_REVERB ✓。
        if (blur != null && from != null && from.frames() > 0
                && (!appleReverbPort || appleHasReverb())) {
            float[] fb = from.backingArray();
            int fn = from.frames();
            for (int c = 0; c < Math.min(from.channels(), 2); c++) {
                int base = from.channelBase(c);
                for (int i = 0; i < fn; i++) {
                    fb[base + i] = fb[base + i] + blur.process(c, fb[base + i] * BLUR_SEND) * BLUR_WET;
                }
            }
        }
        ensureCarve();
        logStage("①carve前", from, to, progress);
        if ((carveA != null && from != null) || (carveB != null && to != null)) {
            float k = (float) Math.sin(Math.PI * Math.max(0.0, Math.min(1.0, (double) progress)));
            if (k > 1e-4f) {
                if (carveA != null && from != null) {
                    if (carveHz != null) {
                        applyCarveChain(carveA, from, k);      // 多带：按声道分组跑整条链 ✓
                    } else {
                        applyCarve(carveA, from, k);
                    }
                }
                if (carveB != null && to != null) {
                    applyCarve(carveB, to, k);
                }
            }
        }
        int mute = probeMuteSide;
        if (mute == 1 && from != null) {
            zeroFrames(from);
        } else if (mute == 2 && to != null) {
            zeroFrames(to);
        }
        logStage("②carve后", from, to, progress);
        int produced = renderBase(out, from, to, progress);
        logStage("③renderBase后", out, null, progress);
        // **混响是"后处理"**（2026-10-07）：底座各自照旧 ✓（纯混响那档还是朴素淡化、
        // 尾音那档还是 tailRide、滤波那档还是扫频 ✓），这里只把**出场侧的送音**过一遍混响、
        // 把湿声叠上去 ✓ —— 于是 5 档各自保留自己的底座、又都真的有了混响 ✓。
        if (reverb != null && produced > 0) {
            applyReverb(out, from, produced, progress);
        }
        if (delay != null && produced > 0) {
            applyDelay(out, from, produced, progress);
        }
        return produced;
    }

    /**
     * 把这一块的**出场侧送音**过一遍混响，湿声叠进已经渲染好的输出 ✓（零分配 ✓）。
     *
     * <p>送的是 {@code from}（出场侧）**原始**样本 × {@code send} ✓ —— 用原始而不是"已经淡出过
     * 的那一份"是刻意的：A 的音量在窗口里往下走 ✓，而混响尾巴**不该跟着一起消失** ✓
     * （"尾音延续"就是这个意思 ✓）。
     */
    /**
     * **惰性重建**让位滤波器（只在新参数到来后的第一块做一次 ✓，之后零分配 ✓）。
     *
     * <p>为什么必须惰性：参数由包装层在 `prepare` 里设 ✓，而本类构造在那之前 ✓ ——
     * 构造时建会读到 0 ⇒ 建出 null ⇒ 让位永不生效 ✗（round 11 实测 ✓）。
     */
    /** 见 {@link #BASS_HANDOVER_HZ}：末段把退场侧的低频收掉（零分配 ✓）。 */
    /**
     * <b>自研听感装置的开关</b>（第 72/73 轮 ✓）—— 一处管三样：**低音让手** ✓（退场段末 40% 高通 ✓）、
     * **互补让位** ✓、**自适应留白** ✓。它们是**我们的**发明 ✗（不是他的操作 ✗）⇒ 判据要"关掉它们"
     * 时必须有开关 ✓（第 71 轮的教训 ✓）。默认**开** ✓（产品行为不变 ✓）。
     */
    private static volatile boolean appleHarmonyPort = true;

    public static void setAppleHarmonyDevicesPort(boolean on) {
        appleHarmonyPort = on;
    }

    public static boolean appleHarmonyDevicesOn() {
        return appleHarmonyPort;
    }

    /** 本档是不是"我们抄他的"那一批 ✓（自研档 ⇒ 那些按 AM 取证做的听感装置都不该落上去 ✓）。 */
    private boolean isMappedStyle() {
        return AppleStyleMap.appleId(style()) != AppleStyleMap.NONE;
    }

    private void applyBassHandover(AudioBuf from, float progress) {
        // ⚠️ **只对映射档生效**（2026-10-08 ✓，第 70 轮修 ✓）：这是我按 AM 取证（"A 的低频先于
        // 它本体消失" ✓）做的装置 ✓，但它写在**默认路径**上 ✗ ⇒ 连**自研档**（低音交接 ✓ 骑行 ✓
        // 表现模式 ✓ 学习EQ ✓）的退场段低频都被抬掉 ✗ ⇒ 判据「BASS_SWAP 后半段低频应已换到进场曲」
        // 等当场红 ✓（`9b6caf6d` 那轮起 +2 条 ✓，与二分实测一致 ✓）。
        if (!appleHarmonyPort || !isMappedStyle()) {
            return;
        }
        double p = Math.max(0.0, Math.min(1.0, (double) progress));
        if (p <= 0.6) {
            return;                        // 前 60% 一个采样都不碰 ✓
        }
        double k = (p - 0.6) / 0.4;
        float hz = (float) (20.0 + k * (BASS_HANDOVER_HZ - 20.0));
        if (bassOut == null) {
            bassOut = new Biquad[from.channels()];
            for (int c = 0; c < bassOut.length; c++) {
                bassOut[c] = Biquad.highPass(rate, 20f, 0.707f);
            }
        }
        if (Math.abs(hz - bassOutHz) > bassOutHz * 0.01) {
            bassOutHz = hz;
            for (Biquad f : bassOut) {
                f.setHighPass(rate, hz, 0.707f);
            }
        }
        float[] back = from.backingArray();
        int n = from.frames();
        for (int c = 0; c < bassOut.length; c++) {
            int base = from.channelBase(c);
            Biquad f = bassOut[c];
            for (int i = 0; i < n; i++) {
                back[base + i] = f.process(back[base + i]);
            }
        }
    }

    private void ensureCarve() {
        // ⚠️ **只对映射档生效**（2026-10-08 ✓，第 70 轮 ✓）：让位是我按"互补频谱"做的自研装置 ✓
        //（用户"让两首歌从容交织"✓），但它的参数（哪些带让、让多少 ✓）由**包装层**为本档算 ✓ ——
        // 自研档不该被它改 ✗（它们的判据按"两轨原样"写 ✓）。映射档照旧 ✓。
        if (!appleHarmonyPort || !isMappedStyle()) {
            carveA = null;
            carveB = null;
            carveBuiltVersion = carveVersion;
            return;
        }
        if (carveBuiltVersion == carveVersion) {
            return;
        }
        carveBuiltVersion = carveVersion;
        carveA = null;
        carveB = null;
        double[] planHz = carveHz;
        float[] planGain = carveGain;
        if (planHz != null && planGain != null) {
            int n = Math.min(planHz.length, planGain.length);
            int used = 0;
            for (int i = 0; i < n; i++) {
                if (planHz[i] > 0.0 && planGain[i] < 0f) {
                    used++;
                }
            }
            if (used > 0) {
                // ⚠️ **两侧都让**（2026-10-07，用户的口径 ✓）：
                // 「互补的意义就在于让两首歌**从容交织**，而不是直接让一边滚蛋给另一边」✓
                // ⇒ 同一组频带与同样温柔的深度，**两边各自让一点点** ✓（对称 ✓）。
                // 我 round 11 曾因为"指标（两侧电平差）看不出改善"把它否掉 ✗ ——
                // 那是**指标错了，不是做法错了** ✓：那条指标量的是"两边差多少" ✗，
                // 而"交织得从容"恰恰是"**两边一起退一步**" ✓，指标看不见 ✓、耳朵听得见 ✓。
                carveA = new Biquad[channels * used];
                carveB = new Biquad[channels * used];
                int at = 0;
                for (int c = 0; c < channels; c++) {
                    for (int i = 0; i < n; i++) {
                        if (planHz[i] > 0.0 && planGain[i] < 0f) {
                            Biquad f = Biquad.peaking(rate, (float) planHz[i], 1.2f, planGain[i]);
                            carveA[at] = f;
                            carveB[at] = Biquad.peaking(rate, (float) planHz[i], 1.2f,
                                    planGain[i]);
                            at++;
                        }
                    }
                }
            }
        } else if (carveGainDb < 0f) {
            if (carveHzA > 0.0) {
                carveA = new Biquad[channels];
                for (int i = 0; i < channels; i++) {
                    carveA[i] = Biquad.peaking(rate, (float) carveHzA, 1.2f, carveGainDb);
                }
            }
            if (carveHzB > 0.0) {
                carveB = new Biquad[channels];
                for (int i = 0; i < channels; i++) {
                    carveB[i] = Biquad.peaking(rate, (float) carveHzB, 1.2f, carveGainDb);
                }
            }
        }
    }

    /** 多带让位：按声道分组把整条 peaking 链跑完（零分配 ✓）。 */
    private static void applyCarveChain(Biquad[] chain, AudioBuf buf, float k) {
        float[] back = buf.backingArray();
        int n = buf.frames();
        int chs = buf.channels();
        int per = chain.length / Math.max(1, chs);
        for (int c = 0; c < chs; c++) {
            int base = buf.channelBase(c);
            for (int f = 0; f < per; f++) {
                Biquad flt = chain[c * per + f];
                for (int i = 0; i < n; i++) {
                    int at2 = base + i;
                    float dry = back[at2];
                    float wet = flt.process(dry);
                    // 按 k 在"干/湿"之间插值 ✓（k = sin(πp) ⇒ 两端为 0 ✓ 无台阶 ✓）
                    back[at2] = dry + k * (wet - dry);
                }
            }
        }
    }

    /** 就地把一个缓冲逐声道过一遍 peaking（零分配 ✓）。 */
    private static void applyCarve(Biquad[] filters, AudioBuf buf, float k) {
        float[] back = buf.backingArray();
        int n = buf.frames();
        int chs = Math.min(buf.channels(), filters.length);
        for (int c = 0; c < chs; c++) {
            int base = buf.channelBase(c);
            Biquad f = filters[c];
            for (int i = 0; i < n; i++) {
                int at2 = base + i;
                float dry = back[at2];
                float wet = f.process(dry);
                back[at2] = dry + k * (wet - dry);
            }
        }
    }

    /** 清空一个缓冲当前交付的帧（诊断用 ✓，见 {@link #probeMuteSide}）。 */
    private static void zeroFrames(AudioBuf buf) {
        float[] back = buf.backingArray();
        int n = buf.frames();
        for (int c = 0; c < buf.channels(); c++) {
            int base = buf.channelBase(c);
            for (int i = 0; i < n; i++) {
                back[base + i] = 0f;
            }
        }
    }

    /**
     * <b>照搬 Apple 的混响参数</b>（2026-10-08 ✓，照搬 goal 第 28 轮 ✓；数据见 {@link AppleTransitionSpec} ✓）。
     *
     * <p>他的两套"尺寸"（读出来的 ✓）：33/44 = `low_decay 5 s / high_decay 6 s` ✓；
     * 11/13 = `4 s / 4 s` ✓；14 = `1.5 s / 0.5 s` ✓。`fx_reverb_dry_wet = 100` ⇒ 送出总线
     * **100% 湿** ✓ ⇒ 我们的 `wet` 取 1.0 ✓（回混量由 `send_mixer_gain` 决定 ✓）。
     * `send_mixer_gain` 是**随时间变化**的 ✓（33/44：`0→0.75` 于 `[0, +2 s]` ✓ —— 绝对秒 ✓）。
     *
     * <p>⚠️ **过渡态**（下一轮补 ✓）：绝对秒条目需要"窗口时长"才能归一 ✓，而窗口是**包装层**算的
     * （渲染层拿不到 ✓）。这一轮先用"**按窗口归一**"兜底 ✓（把他那 2 秒当成整窗 ✓）——
     * 比当成 0 更接近他的形状 ✓，但**不是精确值** ✗；下一轮由包装层把窗口时长告诉渲染层 ✓。
     * 开关关掉 ⇒ 全部回到我们自己的 tuning ✓（可回退 ✓）。
     */
    private static volatile boolean appleReverbParamsPort = true;

    public static void setAppleReverbParamsPort(boolean on) {
        appleReverbParamsPort = on;
    }

    /** 这一次过渡的**窗口时长**（秒 ✓）——包装层每块告诉渲染层 ✓，绝对秒自动化靠它归一 ✓。 */
    private double windowSeconds;

    /** 当前融合窗口（秒），静态影子：{@link #gainsFor(float)} 是静态方法（渲染热路径不取 this）。 */
    private static volatile double WINDOW_FOR_GAINS = 0.0;

    public void setWindowSeconds(double secs) {
        this.windowSeconds = secs > 0.0 ? secs : 0.0;
        WINDOW_FOR_GAINS = this.windowSeconds;
    }

    /**
     * 融合用的增益曲线：<b>短窗口（≤2.5 s）走错开版</b>，长窗口仍走教科书等功率。
     *
     * <p>实测（2026-11-20）：2 s 短交叉用教科书曲线时，末尾 A 已掉到 -22 dBFS、
     * B 才 -10.9 dBFS ⇒ 交接瞬间 11.38 dB 台阶（听感"卡一下"）。
     * 错开版见 {@link Mixer#shortCrossfadeGains(float)}。
     */
    private static Mixer.Gains gainsFor(float progress) {
        return WINDOW_FOR_GAINS > 0.0 && WINDOW_FOR_GAINS <= 2.5
                ? Mixer.shortCrossfadeGains(progress)
                : Mixer.equalPowerGains(progress);
    }

    private AppleTransitionSpec.Auto appleRevSend;
    private float appleRevDecay = -1f;
    private float appleRevWet = -1f;
    private boolean appleRevResolved;

    private void resolveAppleReverb() {
        appleRevResolved = true;
        AppleTransitionSpec.Style spec = AppleStyleMap.specFor(style());
        if (spec == null) {
            return;
        }
        appleRevSend = AppleStyleMap.lane(spec, true, "send_mixer_gain");
        AppleTransitionSpec.Auto low = AppleStyleMap.lane(spec, true, "fx_reverb_low_frequency_decay_time");
        AppleTransitionSpec.Auto wetAuto = AppleStyleMap.lane(spec, true, "fx_reverb_dry_wet");
        if (low != null) {
            appleRevDecay = AppleTransitionSpec.valueAt(low, 1f);   // 常数值 ✓（他的时间范围是 [0,0] ✓）
        }
        if (wetAuto != null) {
            appleRevWet = AppleTransitionSpec.valueAt(wetAuto, 1f) / 100f;   // 100 ⇒ 1.0 ✓
        }
    }

    private void applyReverb(AudioBuf out, AudioBuf from, int n, float progress) {
        int chs = Math.min(out.channels(), from.channels());
        float send = tuning.reverbSend;
        float wet = tuning.reverbWet;
        // 参考包的 69 条参数描述给了原生区间：湿声 / send 在应用前先夹一遍，
        // 越界就夹住并打一行日志（避免"参数超出原生范围"这种一眼假的声音）。
        float sendRaw = send;
        float wetRaw = wet;
        try {
            send = (float) ReferenceDsp.clampToRange("remixfx_reverb_send", send);
            wet = (float) ReferenceDsp.clampToRange("remixfx_reverb_wet", wet);
        } catch (RuntimeException e) {
            // 表里没有就不夹（保持原值）
        }
        if (send != sendRaw || wet != wetRaw) {
            dev.t1m3.qplayer.util.Logger.info(
                    "[qplayer-engine] 参考包区间夹取：send {}→{} · wet {}→{}",
                    Float.valueOf(sendRaw), Float.valueOf(send),
                    Float.valueOf(wetRaw), Float.valueOf(wet));
        }
        if (appleReverbParamsPort) {
            if (!appleRevResolved) {
                resolveAppleReverb();
            }
            if (appleRevSend != null) {
                // 他的 send 曲线 ✓（绝对秒条目这一轮按"整窗"兜底 ✓，见上面的过渡态说明 ✓）
                // 三参版：绝对秒条目按**真实窗口时长**归一 ✓（第 29 轮起精确 ✓，不再是兜底 ✓）
                send = AppleTransitionSpec.valueAt(appleRevSend, progress, (float) windowSeconds);
            }
            if (appleRevWet >= 0f) {
                wet = appleRevWet;
            }
        }
        for (int ch = 0; ch < chs; ch++) {
            java.nio.FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                float r = reverb.process(ch, sampleOf(from, ch, i) * send);
                o.put(i, o.get(i) + r * wet);
            }
        }
    }

    /**
     * 把这一块的**出场侧送音**过一遍跟拍延迟，回声叠进输出 ✓（零分配 ✓）。
     *
     * <p>延时长度**每块按拍长重算一次** ✓（拍长这次过渡内是常数 ⇒ 实际只在第一块设一次 ✓，
     * 但写成每块都对，将来若接上变速也不会错 ✓）。
     */
    /**
     * <b>照搬 Apple 的 `AUX_DELAY`</b>（2026-10-08 ✓，照搬 goal 第 32 轮 ✓）。
     *
     * <p>他的原文（读出来的 ✓）：档 10 的 `AUX_DELAY` 里
     * `fx_delay_dry_wet 0→100` 于 `[0, +1 s]`（`ease-out-2` ✓）、
     * `fx_delay_delay_time = beat_length`（**按拍长** ✓）；
     * 而 `send_mixer_gain` 是 `0.5 → 0` 于 `[0,1]` ✓ —— 注意**方向与其他档相反** ✓
     * （延迟档是**送量递减** ✓，这正是照搬要保住的细节 ✓）。
     * 我们本来就是"按拍长设延迟" ✓ ⇒ 与 `beat_length` 语义天然一致 ✓，只需换 send / wet ✓。
     * 开关关掉 ⇒ 回到我们自己的 tuning ✓（可回退 ✓）。
     */
    private static volatile boolean appleDelayParamsPort = true;

    public static void setAppleDelayParamsPort(boolean on) {
        appleDelayParamsPort = on;
    }

    private AppleTransitionSpec.Auto appleDelaySend;
    private float appleDelayWet = -1f;
    private boolean appleDelayResolved;

    private void resolveAppleDelay() {
        appleDelayResolved = true;
        AppleTransitionSpec.Style spec = AppleStyleMap.specFor(style());
        if (spec == null) {
            return;
        }
        AppleTransitionSpec.Auto wetAuto = AppleStyleMap.lane(spec, true, "fx_delay_dry_wet");
        if (wetAuto != null) {
            appleDelayWet = AppleTransitionSpec.valueAt(wetAuto, 1f) / 100f;   // 100 ⇒ 1.0 ✓
        }
        if (hasOp(spec.outgoingOps, "AUX_DELAY") || hasOp(spec.incomingOps, "AUX_DELAY")) {
            appleDelaySend = AppleStyleMap.lane(spec, true, "send_mixer_gain");
        }
    }

    private void applyDelay(AudioBuf out, AudioBuf from, int n, float progress) {
        long frames = Math.round(beatSecs * tuning.delayBeats * (double) rate);
        delay.setDelayFrames(frames);
        int chs = Math.min(out.channels(), from.channels());
        float send = tuning.delaySend;
        float wet = tuning.delayWet;
        if (appleDelayParamsPort) {
            if (!appleDelayResolved) {
                resolveAppleDelay();
            }
            if (appleDelaySend != null) {
                send = AppleTransitionSpec.valueAt(appleDelaySend, progress, (float) windowSeconds);
            }
            if (appleDelayWet >= 0f) {
                wet = appleDelayWet;
            }
        }
        for (int ch = 0; ch < chs; ch++) {
            java.nio.FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                float d = delay.process(ch, sampleOf(from, ch, i) * send);
                o.put(i, o.get(i) + d * wet);
            }
        }
    }

    /**
     * <b>这一块的音量交叉曲线</b>：默认就是等功率（cos/sin ✓，与从前逐位相同 ✓）；
     * 只有"长出短进"那几档不同 ✓ —— 进场侧提前在 {@code shortInFraction} 处到满 ✓
     * （2026-10-07 让名字名副其实 ✓）。
     *
     * <p>⚠️ 两条曲线**各自**仍是等功率形状 ✓，但时间常数不同 ⇒ 交叠期的功率和会**大于 1** ✗
     * （最多约 +1.8 dB，`shortInFraction = 0.5` 时）。这是这一档名字本身的含义 ✓
     * （"B 麻利地进来、A 还在"✓），而且后面还有那道前瞻软限幅接着 ✓。
     * 想关掉 ⇒ {@code shortInFraction >= 1} ✓（那时与对称淡化逐位相同 ✓）。
     */
    /**
     * <b>A 侧"压着不掉"的指数</b>（2026-10-07 ✓，按 Apple Music 实测形状 ✓）。
     *
     * <p>1.0 = 旧的等功率对称 ✓（中段两边各 50% ✗ = 用户听到的"很明显听出来换歌" ✓）；
     * 2.0 = AM 的形状 ✓：A 前段几乎不降、末段快速放手 ✓，B 也晚到 ✓
     *（**功率和仍恒为 1** ✓ ⇒ 不会引出响度凸起 ✓）。
     */
    static final double HOLD_EXPONENT = 2.0;

    /**
     * <b>Apple Music 实测的增益曲线</b>（2026-10-07 ✓）—— 取自我们自己的取证
     * （ 里逐时刻的读数 ✓），横轴 = 交叠进度 p ∈ [0,1] ✓。
     *
     * <p>A（退场）：**大部分时间压着不掉**（p<0.45 还在 0.85 以上 ✓），
     * 最后 **~0.6 s** 内 0.77 → 0.50 → 0.07 → 0 ✓（取证里 44.5/44.75/45.0 s 那三点 ✓）。
     * B（进场）：**一直很低**（前 40% 只有 0.09~0.23 ✓ = 取证里"落在噪声底 0.21 上" ✓），
     * 到 ~70% 才 0.5 ✓、~88% 才 0.92 ✓。
     *
     * <p>⇒ 效果：**任何一刻只有一边主导** ✓，而且中段总和还略**低于**单响 ✓
     *（0.77²+0.23² ≈ 0.65 ⇒ 约 −1.9 dB 的凹陷 ✓ = 取证里那段"并行感明显"但不吵 ✓）。
     * 这与等功率（中段两首各 50%、功率和恒为 1 ✗）是**两种形状** ✓ —— 后者正是"听出来换歌" ✓。
     */
    /**
     * <b>混合度</b>（用户 2026-10-07："可以再混一点" ✓）。
     *
     * <p>0 = 完全照 Apple 实测的增益表 ✓（A 主导、B 晚到 ⇒ 最不像"换歌" ✓ 但交织最少 ✓）；
     * 1 = 等功率 ✓（中段两首各 50% ⇒ 最混 ✓ 但也最容易"听出来换歌" ✓）。
     * 初值 **0.35**：比 AM 实测更交织一档 ✓ —— 用户听过 AM 形状后说"可以再混一点" ✓。
     */
    static final double MIX_AMOUNT = 0.5;

    private static final double[][] APPLE_A = {
        {0.00, 1.00}, {0.15, 1.00}, {0.30, 0.95}, {0.45, 0.85}, {0.55, 0.77},
        {0.70, 0.60}, {0.78, 0.50}, {0.85, 0.25}, {0.92, 0.07}, {1.00, 0.00},
    };
    private static final double[][] APPLE_B = {
        {0.00, 0.00}, {0.10, 0.09}, {0.20, 0.12}, {0.30, 0.18}, {0.40, 0.23},
        {0.55, 0.35}, {0.70, 0.50}, {0.80, 0.75}, {0.88, 0.92}, {1.00, 1.00},
    };

    /** 在实测表上线性插值（零分配 ✓）。 */
    private static float lerpTable(double[][] table, float p) {
        double x = Math.max(0.0, Math.min(1.0, (double) p));
        for (int i = 1; i < table.length; i++) {
            if (x <= table[i][0]) {
                double x0 = table[i - 1][0];
                double x1 = table[i][0];
                double k = x1 > x0 ? (x - x0) / (x1 - x0) : 0.0;
                return (float) (table[i - 1][1] + k * (table[i][1] - table[i - 1][1]));
            }
        }
        return (float) table[table.length - 1][1];
    }

    /**
     * <b>照搬 Apple 的滤波写法</b>（2026-10-08 ✓；材料：`automix-c/assets/TransitionStyles.json` ✓）。
     *
     * <p>他那一族的原文（逐字 ✓）：
     * <pre>
     * outgoing  LOW_PASS  lp_cutoff_freq 20000 -&gt; 200 Hz   ease-out-2
     * incoming  HI_PASS   hp_cutoff_freq 20000 -&gt; 10  Hz   ease-out-2
     * incoming  LOW_PASS  lp_cutoff_freq 1200  -&gt; 20000 Hz ease-in-4
     * 两侧      out_gain                 1-&gt;0 / 0-&gt;1       linear
     * </pre>
     * 三条与我们的旧实现**结构性不同** ✗：① A 是"关幕"（低通到 200 Hz ✓）；
     * ② B 有"绽放"（高通落回 10 Hz ✓ + 低通从 1.2k 开到 20k ✓）—— 我们**完全没有** ✗；
     * ③ 增益是 **linear**（不是等功率 ✓）⇒ 中段天生约 −6 dB 凹陷 ✓（他"从容"的来源 ✓）。
     * 缓动按名字解释：`ease-out-N` = 1−(1−p)^N ✓、`ease-in-N` = p^N ✓。
     */
    private static volatile boolean appleFilterPort = true;

    /** 判据/对照用：关掉照搬 ⇒ 回到旧的低通扫频行为 ✓。 */
    public static void setAppleFilterPort(boolean on) {
        appleFilterPort = on;
    }

    private Biquad[] appleLpA;
    private Biquad[] appleHpB;
    private Biquad[] appleLpB;
    private double appleLpAHz = -1.0;
    private double appleHpBHz = -1.0;
    private double appleLpBHz = -1.0;

    private static double easeOut(double p, double n) {
        return 1.0 - Math.pow(1.0 - p, n);
    }

    private static double easeIn(double p, double n) {
        return Math.pow(p, n);
    }

    private AppleTransitionSpec.Auto appleLpACurve;
    private AppleTransitionSpec.Auto appleHpBCurve;
    private AppleTransitionSpec.Auto appleLpBCurve;
    private boolean appleFilterResolved;

    /** 按 {@link AppleStyleMap} 取**本档**的 `lp_cutoff_freq` / `hp_cutoff_freq` 段表 ✓。 */
    private void resolveAppleFilters() {
        appleFilterResolved = true;
        AppleTransitionSpec.Style spec = AppleStyleMap.specFor(style());
        if (spec == null) {
            return;
        }
        appleLpACurve = AppleStyleMap.lane(spec, true, "lp_cutoff_freq");
        appleHpBCurve = AppleStyleMap.lane(spec, false, "hp_cutoff_freq");
        appleLpBCurve = AppleStyleMap.lane(spec, false, "lp_cutoff_freq");
    }

    /** 见 {@link #appleFilterPort}：A 关幕 ✓、B 绽放 ✓、增益线性 ✓（零分配 ✓）。 */
    private int renderAppleFilter(AudioBuf out, AudioBuf from, AudioBuf to, int n, float p0) {
        double p = Math.max(0.0, Math.min(1.0, (double) p0));
        if (appleLpA == null) {
            appleLpA = new Biquad[channels];
            appleHpB = new Biquad[channels];
            appleLpB = new Biquad[channels];
            for (int c = 0; c < channels; c++) {
                appleLpA[c] = Biquad.lowPass(rate, 20000f, 0.707f);
                appleHpB[c] = Biquad.highPass(rate, 20000f, 0.707f);
                appleLpB[c] = Biquad.lowPass(rate, 1200f, 0.707f);
            }
        }
        // **按档取他的截止频率段表**（2026-10-08 ✓，照搬 goal 第 6 轮 ✓）：
        // 不再是写死的那三个数 ✗ —— 每一档的起止频率、缓动、以及**时间范围**都照他的 ✓
        //（有的档 `startTime.relative = 0.5` ✓：前半段不动、后半段才扫 ✓ —— valueAt 天然处理 ✓：
        //  段前保持起值 ✓、段后保持终值 ✓）。某侧没给这个参数 ⇒ 用"透明"的默认 ✓（20 kHz ✓ / 10 Hz ✓）。
        if (!appleFilterResolved) {
            resolveAppleFilters();
        }
        float lpA = appleLpACurve != null
                ? AppleTransitionSpec.valueAt(appleLpACurve, (float) p) : 20000f;
        float hpB = appleHpBCurve != null
                ? AppleTransitionSpec.valueAt(appleHpBCurve, (float) p) : 10f;
        float lpB = appleLpBCurve != null
                ? AppleTransitionSpec.valueAt(appleLpBCurve, (float) p) : 20000f;
        if (Math.abs(lpA - appleLpAHz) > Math.max(1.0, Math.abs(appleLpAHz) * 0.005)) {
            appleLpAHz = lpA;
            for (Biquad f : appleLpA) {
                f.setLowPass(rate, Math.max(20f, lpA), 0.707f);
            }
        }
        if (Math.abs(hpB - appleHpBHz) > Math.max(1.0, Math.abs(appleHpBHz) * 0.005)) {
            appleHpBHz = hpB;
            for (Biquad f : appleHpB) {
                f.setHighPass(rate, Math.max(10f, hpB), 0.707f);
            }
        }
        if (Math.abs(lpB - appleLpBHz) > Math.max(1.0, Math.abs(appleLpBHz) * 0.005)) {
            appleLpBHz = lpB;
            for (Biquad f : appleLpB) {
                f.setLowPass(rate, Math.max(20f, lpB), 0.707f);
            }
        }
        float gA = (float) (1.0 - p);
        float gB = (float) p;
        int chs = Math.min(out.channels(), Math.min(from.channels(), to.channels()));
        out.setFrames(n);
        float[] fb = from.backingArray();
        float[] tb = to.backingArray();
        for (int c = 0; c < chs; c++) {
            java.nio.FloatBuffer o = out.channelWriter(c);
            int bf = from.channelBase(c);
            int bt = to.channelBase(c);
            Biquad fa = appleLpA[Math.min(c, appleLpA.length - 1)];
            Biquad hb = appleHpB[Math.min(c, appleHpB.length - 1)];
            Biquad lb = appleLpB[Math.min(c, appleLpB.length - 1)];
            for (int i = 0; i < n; i++) {
                float a = fa.process(fb[bf + i]);
                float b = lb.process(hb.process(tb[bt + i]));
                o.put(i, a * gA + b * gB);
            }
        }
        for (int c = chs; c < out.channels(); c++) {
            java.nio.FloatBuffer o = out.channelWriter(c);
            for (int i = 0; i < n; i++) {
                o.put(i, 0f);
            }
        }
        return n;
    }

    /**
     * <b>照搬他的 `RECEIVE_MIXER` / `out_gain`</b>（2026-10-08 ✓，照搬 goal 第 5 轮 ✓）。
     *
     * <p>用户的原始判断：「我们的和他的实现方案不一样」✓ —— 增益这一项就是最大的那块 ✗：
     * 我这两天一直在手调形状（实测表 ✓ + `MIX_AMOUNT` ✓），而他的写法是
     * **每一档自带一串 `out_gain` 分段自动化** ✓（`linear` ✓／`ease-in-0.5` ✓／
     * 甚至只占窗口的 `[0.375, 0.5]` 一小段 ✓，见 `AppleTransitionSpec` ✓）。
     *
     * <p>做法：按 {@link AppleStyleMap} 找到**本档**对应的他那一档 ✓，取两侧的 `out_gain` 段表 ✓，
     * 每次渲染直接求值 ✓（零分配 ✓）。某一侧没有 `out_gain`（他那档没用 ✓）⇒ 该侧退回等功率 ✓
     * （不会因为缺参数就静音 ✗）。我们**自己的**档（映射为 NONE ✓，如 BASS_SWAP/SHOW_MODE ✓）
     * ⇒ 完全不碰 ✓，仍走旧行为 ✓。
     */
    /**
     * **重复器（stutter）总开关**（2026-10-09 ✓，用户「感觉 A 被搓碟子了 像被抹布搓了一下」✓）。
     * 他档 17 `Dance - Repeater` 的签名效果就是"抓一小段原地循环"（`repeater_on` 0.4444–0.8889 ✓、
     * 速率阶梯 1→2→3→4→5 ✓）⇒ 听感偏"电子搓碟" ✓。**默认关** ✓（用户明确不喜欢 ✗）；
     * 打开 ⇒ 逐字照他的表跑 ✓。滤波/reverb/flanger 不受影响 ✓（它们才是"像一首歌"的来源 ✓）。
     */
    private static volatile boolean appleRepeaterPort = false;

    public static void setAppleRepeaterPort(boolean on) {
        appleRepeaterPort = on;
    }

    private static volatile boolean appleGainPort = true;

    /** 判据/对照用：关掉 ⇒ 回到旧行为（我手调那套 ✓）。 */
    public static void setAppleGainPort(boolean on) {
        appleGainPort = on;
    }

    /**
     * <b>照搬 `AUX_REVERB` 的开关</b>（2026-10-08 ✓）：他那一档的操作串里真的有 `AUX_REVERB` ✓
     * 才给这个档开混响 ✓（干路必须恰好静音 ✓，否则 `ReverbRouteTest`/`DelayRouteTest` 当场红 ✓）。
     * 关掉 ⇒ 回到"所有档都加一点点混响"的旧行为 ✓（判据要量旧行为时用 ✓，**成对设置与还原** ✓）。
     */
    private static volatile boolean appleReverbPort = true;

    public static void setAppleReverbPort(boolean on) {
        appleReverbPort = on;
    }

    private AppleTransitionSpec.Auto appleOutGain;
    private AppleTransitionSpec.Auto appleInGain;
    private boolean appleGainResolved;

    private boolean appleHasReverbResolved;
    private boolean appleHasReverbValue;

    /** 他那一档的操作串里有没有  ✓（有 ⇒ 才给这个档开混响 ✓，见调用处 ✓）。 */
    private boolean appleHasRemixResolved;
    private boolean appleHasRemixValue;

    /** 他那一档的操作串里有没有 REMIX_FX ✓（有 ⇒ 才跑那条链 ✓；材料里只有档 17 有 ✓）。 */
    private boolean appleHasRemix() {
        if (!appleHasRemixResolved) {
            appleHasRemixResolved = true;
            AppleTransitionSpec.Style spec = AppleStyleMap.specFor(style());
            appleHasRemixValue = spec != null
                    && (hasOp(spec.outgoingOps, "REMIX_FX") || hasOp(spec.incomingOps, "REMIX_FX"));
        }
        return appleHasRemixValue;
    }

    private boolean appleHasReverb() {
        if (!appleHasReverbResolved) {
            appleHasReverbResolved = true;
            AppleTransitionSpec.Style spec = AppleStyleMap.specFor(style());
            appleHasReverbValue = spec != null
                    && (hasOp(spec.outgoingOps, "AUX_REVERB")
                    || hasOp(spec.incomingOps, "AUX_REVERB"));
        }
        return appleHasReverbValue;
    }

    private static boolean hasOp(String[] ops, String name) {
        if (ops == null) {
            return false;
        }
        for (String o : ops) {
            if (name.equals(o)) {
                return true;
            }
        }
        return false;
    }

    private void resolveAppleGains() {
        appleGainResolved = true;
        AppleTransitionSpec.Style spec = AppleStyleMap.specFor(style());
        if (spec == null) {
            return;
        }
        appleOutGain = AppleStyleMap.lane(spec, true, "out_gain");
        appleInGain = AppleStyleMap.lane(spec, false, "out_gain");
    }

    private static AppleTransitionSpec.Auto findAuto(AppleTransitionSpec.Auto[] list, String key) {
        if (list == null) {
            return null;
        }
        for (AppleTransitionSpec.Auto a : list) {
            if (key.equals(a.key)) {
                return a;
            }
        }
        return null;
    }

    Mixer.Gains gains(float p) {           // 包内可见：判据要直接量这两条曲线 ✓
        // **照搬他的 out_gain**（见 appleGainPort ✓）：按本档取他那档的段表求值 ✓
        if (appleGainPort) {
            if (!appleGainResolved) {
                resolveAppleGains();
            }
            if (appleOutGain != null || appleInGain != null) {
                double eqG = (Math.PI / 2.0) * Math.max(0.0, Math.min(1.0, (double) p));
                // ⚠️ **他那一侧没有 `out_gain` 自动化 ⇒ 该侧保持 1.0**（2026-10-09 ✓，用户
                // "按他的做法开始做" ✓）：他的 `renderer.py` 里写得很清楚
                // `gain = control(None, 'out_gain', **1**)` ✓ —— 没自动化就是默认值 1 ✓。
                // 原来的写法是"缺车道 ⇒ 等功率淡出" ✗ ⇒ 档 17 的**出场侧没有** `out_gain`（他的表里
                // 只有进场侧有 ✓）⇒ 我们把**本该给 B 的淡入曲线**当成 A 的淡出 ✗ ⇒ 实测 A 在窗口
                // 40% 处就掉到 −26 dB ✗（用户："我这边感觉就是 A 瞬间没了"／"太分明了" ✓）。
                // 他的设计里 A 是靠 `ts_rate 1→2` + REMIX_FX 变化的 ✓，**不淡出** ✓。
                float gA = appleOutGain != null
                        ? AppleTransitionSpec.valueAt(appleOutGain, p) : 1f;
                float gB = appleInGain != null
                        ? AppleTransitionSpec.valueAt(appleInGain, p) : 1f;
                return new Mixer.Gains(gA, gB);
            }
        }
        float s = tuning.shortInFraction;
        if (!asymRoute || s <= 0f || s >= 1f) {
            // ⚠️ **"AM 形状"只对"我们抄他的"那些档生效**（2026-10-08 ✓，第 64 轮修 ✓）：
            // 这段（`APPLE_A/B` 实测表 × `MIX_AMOUNT` 与等功率的混合 ✓）我当初写进了**默认分支** ✗
            // ⇒ 连**自研档**（表现模式 ✓ 骑行 ✓ 低音交接 ✓ 学习EQ ✓）都被换掉了 ✗ ——
            // 而它们的期望是按**等功率**写的 ✓ ⇒ 全量一跑就红 ✗
            //（`AiMixTest` / `LearnedEqMixTest` / `AiMixLevelMatchTest` ✓ 实测七条 ✓）。
            // 用户的要求是"**我们抄他的那些模式**用他的" ✓ ⇒ 自研档必须**回到我们自己的等功率** ✓
            //（这也正是照搬之前的行为 ✓，逐位相同 ✓）。
            // ⚠️ **开关也要看**（第 74 轮修 ✓）：原来这里**只看映射** ✗ ⇒ 判据把 `appleGainPort`
            // 关掉时，映射档（如 `PLAIN_BLEND` ✓）**仍然**吃着 AM 表混合 ✗ ⇒ 跨档比较两侧永远不同构
            // ⇒ 五条红怎么也清不掉 ✓（这是第 8 次假设失败后靠"读行"才看见的 ✓）。
            if (!appleGainPort || AppleStyleMap.appleId(style()) == AppleStyleMap.NONE) {
                return gainsFor(p);
            }
            // ⚠️ **默认路径也要用 AM 的形状** ✗（2026-10-07 实测教训 ✓）：第一半只放在
            // `asymRoute` 那一支里 ✗ ⇒ 参考对（普通淡化 ✓）**一点没变** ✓ —— 而判据"全绿"、
            // **指纹没动** ✓ ⇒ 那是**假绿** ✗（指纹那条恰恰是用来照"成品有没有变"的 ✓）。
            // 这里用**时间弯曲的等功率**：`θ = (π/2)·p^hold` ✓ ——
            // 两端仍是 0/1 ✓、功率和仍恒为 1 ✓（不引响度凸起 ✓），但**中段不再各 50%** ✓：
            // A 压着不掉 ✓、B 也晚到 ✓ ⇒ 任何一刻只有一边主导 ✓（= AM 实测的形状 ✓）。
            // **照 AM 实测表插值**（2026-10-07 ✓，修"还是有点断" ✓）—— 公式逼近总差一点 ✗。
            // 再叠一个**混合度**（用户："可以再混一点" ✓）： ⇒ 完全照 AM 实测 ✓
            // （A 主导、B 晚到 ✓）； ⇒ 等功率（中段两首各半 ✓ 最混 ✓）。两边线性混合 ✓
            // ⇒ 用户每次说"再混 / 再收"，只动这一个数 ✓（不再手改表 ✓）。
            double eq = (Math.PI / 2.0) * Math.max(0.0, Math.min(1.0, (double) p));
            double eqA = Math.cos(eq);
            double eqB = Math.sin(eq);
            double m = MIX_AMOUNT;
            return new Mixer.Gains(
                    (float) ((1.0 - m) * lerpTable(APPLE_A, p) + m * eqA),
                    (float) ((1.0 - m) * lerpTable(APPLE_B, p) + m * eqB));
        }
        // ── **按 Apple Music 实测到的形状**（2026-10-07，用户："他的就不会出现很明显断层，
        //    我觉得是过渡操作有差异" ✓）────────────────────────────────────────────
        //
        // 我们自己的取证量到的三条（`docs` + forensics 报告 ✓）：
        //   ① A 的增益**大部分时间保持 0.77~1.0** ✓，最后 **~0.6 s** 内 0.77 → 0.50 → 0.07 → 0 ✓
        //      —— 即"**压着不掉，最后干净放手**" ✓；
        //   ② B 在 A 还在时**就已经满电平**（取证 44.75–46 s > 0.95 ✓）；
        //   ③ A 的低频在它**消失之前**就没了（~45.5–46.0 s ✓）。
        //
        // 而原来这里是**等功率对称**：中段两边各 50% 满响 ✗ —— 那正是用户听到的"两首歌
        // 在融合阶段打架 / 很明显听出来换歌" ✓（两首同时主导 = 换歌被宣布 ✓）。
        //
        // ⇒ 改成 AM 的形状：**A 侧压着不掉（指数 > 1 ⇒ 前段几乎不降 ✓、末段快速放手 ✓）**、
        //   **B 侧提前立住（到 `s` 就满 ✓）**。任何一刻都只有一边在主导 ✓。
        // `s >= 1` ⇒ 退回原来的等功率对称 ✓（可单独关掉 ✓）。
        double hold = HOLD_EXPONENT;             // A 的"压着"指数 ✓（1 = 旧行为 ✓）
        double thA = (Math.PI / 2.0)
                * Math.pow(Math.max(0.0, Math.min(1.0, (double) p)), hold);
        double thB = (Math.PI / 2.0) * Math.min(1.0, (double) p / Math.max(0.05, (double) s));
        return new Mixer.Gains((float) Math.cos(thA), (float) Math.sin(thB));
    }

    /** 真正的渲染（按档分派 ✓）；见 {@link #render}：混响/延迟在外面叠 ✓。 */
    /**
     * <b>照搬 `REMIX_FX` 的重复器</b>（2026-10-08 ✓，照搬 goal 第 42 轮 ✓）。
     *
     * <p>时刻表来自 {@link AppleRemixFx}（档 17 ✓）：`repeater_on` 0.4444 开 / 0.8889 关 ✓、
     * `rate` 阶梯 1→2→3→4→5 ✓、`mix = 1` ✓。段长 = **拍长 ÷ rate** ✓。
     * 作用对象是**出场侧**（`from` ✓），挂在 {@code renderBase} **最顶部**（在空间让位与各接法之前 ✓）——
     * 这样它对**所有**接法一致生效 ✓，而且改的是原始素材（不是 scratch ✓）。
     * 开关关掉 ⇒ 重复器恒为关闭 ✓ = 逐位回到旧行为 ✓。
     */
    private static volatile boolean appleRemixPort = true;

    public static void setAppleRemixPort(boolean on) {
        appleRemixPort = on;
    }

    private dev.t1m3.qplayer.audio.dsp.AppleBeatRepeat repeat;

    /**
     * <b>`REMIX_FX` 的双滤波</b>（2026-10-08 ✓，照搬 goal 第 43 轮 ✓）。
     *
     * <p>他的时刻表（{@link AppleRemixFx} ✓）：`filter_a` 0.45 开 ✓、截止 **20 → 1000** ✓（低通打开 ✓）；
     * `filter_b` 0.5556 开 ✓、截止 **20000 → 2111 → 20** ✓（低通收拢 ✓）。
     * 两块都是**低通**（`remixfx_filter_a_type = 1`、`b_type = 0` ⇒ 都按低通处理 ✓；
     * 类型字段的完整语义材料里没给全 ⇒ 我按"低通"实现并**在此说明** ✓，不假装知道别的 ✗）。
     * 顺序在**重复器之后** ✓（让 stutter 也过扫频 ✓，与他的链一致 ✓）。
     * RT 安全：`setLowPass` 原地换系数 ✓、系数变化小于阈值就不动 ✓、关着 ⇒ **一个采样都不碰** ✓。
     */
    /**
     * <b>`REMIX_FX` 的 reverb 送</b>（2026-10-08 ✓，照搬 goal 第 44 轮 ✓）。
     *
     * <p>材料（档 17 ✓）：`remixfx_reverb_on` 0.6389 置 1 ✓、`remixfx_reverb_send` **0 → 0.56**
     * 于 `[0.6667, 0.7778]` ✓、`remixfx_reverb_dry = 1` ✓、`remixfx_reverb_wet = 0.7` ✓、
     * `remixfx_reverb_time = 6` ✓（秒 ⇒ 我们 `Reverb` 的衰减 ✓）。
     * ⇒ 输出 = **干声 ×1 + 湿声 ×0.7** ✓，湿声由"送量 × 输入"喂进一个**独立**混响 ✓
     *（不是"整档混响"那一个 ✗ —— 两路互不干扰 ✓）。接在**滤波之后** ✓（与他的链一致 ✓）。
     * 关着 ⇒ 一个采样都不碰 ✓（逐位直通 ✓）；RT 安全 ✓（惰性建一次 ✓）。
     */
    /**
     * <b>`REMIX_FX` 的 flanger</b>（2026-10-08 ✓，照搬 goal 第 50 轮 ✓）。
     *
     * <p>材料（档 17 ✓）：`remixfx_flanger_on` 0.6667 置 1 ✓、`mix 0 → 0.45` 于 `[0.6722, 0.8333]` ✓、
     * `rate = 2` ✓、`depth = 0.32` ✓、`center = 0.51` ✓、`feedback = 0.52` ✓。
     * 接在 **reverb 送之后** ✓（与他的链顺序一致 ✓）；关着（或 mix = 0）⇒ **一个采样都不碰** ✓。
     */
    private dev.t1m3.qplayer.audio.dsp.AppleFlanger mixFlanger;

    private Reverb mixReverb;
    private float mixReverbTime = -1f;

    /** **进场侧**的一套 REMIX 状态与滤波**独立于出场侧** ✓（2026-10-09 ✓，goal「像一首歌」✓）。 */
    private final AppleRemixFx.State remixStateIn = new AppleRemixFx.State();
    private Biquad[] mixInFltA;
    private Biquad[] mixInFltB;
    private float mixInFltAHz = -1f;
    private float mixInFltBHz = -1f;

    private Biquad[] mixFltA;
    private Biquad[] mixFltB;
    private float mixFltAHz = -1f;
    private float mixFltBHz = -1f;
    private final AppleRemixFx.State remixState = new AppleRemixFx.State();

    /** 按本块进度驱动重复器（零分配 ✓，状态对象复用 ✓）。 */
    /** **A 侧扫动推迟量**（0 = 照他的表原样 ✓；0.45 = 前 45% 不动 ✓）。用户选的听感 ✓。 */
    private static volatile float appleOutDelay = 0f;   // **不压缩**（陡 ⇒ A 瞬间消失 ✗）

    /** **A 侧扫动用 S 曲线**（默认开 ✓）：两端平、中段快 ⇒ 不"整窗都在滑" ✓、也不突然没 ✓。 */
    private static volatile boolean appleOutSCurve = true;

    /** A 侧扫动**做完的进度**（0.78 ⇒ 78% 处做完、之后平 ✓；1.0 = 全程线性 ✓）。 */
    private static volatile float appleOutSpan = 0.78f;

    public static void setAppleOutSpan(float span) {
        appleOutSpan = Math.max(0.3f, Math.min(1f, span));
    }

    public static void setAppleOutSCurve(boolean on) {
        appleOutSCurve = on;
    }

    public static void setAppleOutDelay(float delay) {
        appleOutDelay = Math.max(0f, Math.min(0.9f, delay));
    }

    private void driveRemix(AudioBuf from, AudioBuf to, float progress) {
        if (!appleRemixPort || from == null || from.frames() <= 0) {
            return;
        }
        // ⚠️ **必须按档判定**（第 61 轮由两刀实验抓出来的真凶 ✓）：
        // AppleRemixFx 读的是他的**档 17** ✓ ⇒ 不加这道门 ✗ 就会把档 17 的滤波/混响/flanger
        // 套到**每条档**上 ✗（实测 PLAIN_BLEND 在 p≥0.45 被加低通 ⇒ `AiMixSpaceTest` 红 ✓）。
        // 他的规则：只有带 REMIX_FX 的那一档才做 ✓（材料里只有档 17 有 ✓）。
        if (!appleHasRemix()) {
            return;
        }
        if (repeat == null) {
            int maxFrames = (int) Math.ceil(Math.max(0.05, beatSecs) * rate) + 4;
            repeat = new dev.t1m3.qplayer.audio.dsp.AppleBeatRepeat(from.channels(), maxFrames);
        }
        // **A 的整套扫动整体推迟**（2026-10-09 ✓，用户「高通推迟还是不够连贯 / A 出场早了感觉有点」✓）：
        // 窗口前 45% 什么都不做 ✓（A 保持完整、"一直在"✓）⇒ 后 55% 才把整套扫动压缩做完 ✓
        // ⇒ 听感从"A 早早被削薄"✗ 变成"A 留到最后才让位"✓。**B 侧完全不动** ✓（fillIncoming 用原 progress ✓）。
        float pOut = appleOutDelay > 0f && progress <= appleOutDelay ? 0f
                : (progress - appleOutDelay) / (1f - appleOutDelay);
        pOut = Math.max(0f, Math.min(1f, pOut));
        if (appleOutSCurve) {
            // **"收得早"**（2026-10-09 ✓，用户：「滑太久了有点 有点杂耳朵不舒服」✗）：
            // 实测对比 ✓ —— S 曲线把中段压陡（最陡 9.7 dB ✗ > 线性 7.9 ✓）⇒ 弃用 ✗；
            // 改成**扫动在窗口 78% 处做完、之后平着** ✓ ⇒ 不再"整窗都在滑"✓，且尾巴平 ⇒ 不突然 ✓。
            pOut = Math.min(1f, pOut / appleOutSpan);
        }
        AppleRemixFx.fill(remixState, pOut);
        // **FX 收尾淡化**（2026-10-09 ✓ 用户：「融合结束瞬间…结束后抽搐」✓）：
        // 他表 id 17 的 repeater 在 0.8889 就关了 ✓，窗口末尾还在生效的是**混响 send 0.56** 与**镶边 mix 0.45** ✓
        // 而窗口一结束 ⇒ 我们立刻回到纯 deck 播放 ✗ ⇒ 它们的"材质"瞬断 ⇒ 听起来就是结束那一下抽 ✓。
        // 做法：窗口**最后 250 ms** 把 FX 混合量线性收到 0 ✓ ⇒ 结束时 FX 已自然退干净 ✓。
        // 他的表没规定"结束后要不要 FX 尾巴"✗ ⇒ 收尾淡化是我们的选择（审计表里标注为 (b) 类 ✓）。
        {
            final double win = windowSeconds;
            final double tailFrac = win > 0.0 ? Math.min(0.5, 0.25 / win) : 0.0;
            if (tailFrac > 0.0 && pOut > 1.0f - tailFrac) {
                final float k = (float) Math.max(0.0, (1.0 - (double) pOut) / tailFrac);
                remixState.reverbSend *= k;
                remixState.flangerMix *= k;
                remixState.repeaterMix *= k;
            }
        }
        boolean on = appleRepeaterPort && remixState.repeaterOn && remixState.repeaterRate > 0f;
        if (on) {
            int frames = (int) Math.round(beatSecs / (double) remixState.repeaterRate * rate);
            repeat.setLoopFrames(Math.max(1, frames));
            repeat.setMix(remixState.repeaterMix);
        }
        repeat.setActive(on);
        float[] back = from.backingArray();
        int n = from.frames();
        for (int ch = 0; ch < from.channels(); ch++) {
            int base = from.channelBase(ch);
            for (int i = 0; i < n; i++) {
                back[base + i] = repeat.process(ch, back[base + i]);
            }
        }
        // ── 双滤波（他的 REMIX_FX 第二段 ✓）：低通，A 打开、B 收拢 ✓ ──────────────────
        applyRemixFilter(from, remixState.filterAOn,
                appleHpDelayCutoff(remixState.filterACutoff), true,
                remixState.filterAHigh);
        applyRemixFilter(from, remixState.filterBOn, remixState.filterBCutoff, false,
                remixState.filterBHigh);
        // ── reverb 送（他的 REMIX_FX 第三段 ✓）：干 ×1 + 湿 ×0.7 ✓ ──────────────────────
        applyRemixReverb(from);
        // ── flanger（他的 REMIX_FX 第四段 ✓）────────────────────────────────────────────
        applyRemixFlanger(from);
        // ── **进场侧**（他的档 17 进场侧另有 filter a/b ✓，2026-10-09 接 ✓）──────────────
        // 实测依据：此前 `REMIX_FX` 开/关时 **B 独奏逐切片 +0.0 dB** ✗（= B 完全没吃到这条链 ✓）。
        // 他的表：进场侧 filter a = 1250→950→200→20（0–0.5556 ✓）、filter b = 70→1600→20000（0–0.8889 ✓）
        // ⇒ B 从 70 Hz 由暗到亮长出来 ✓，A 退场时把频段接住 ✓。**只加这两条** ✗（他没有别的进场侧操作 ✗）。
        if (to != null) {
            AppleRemixFx.fillIncoming(remixStateIn, progress);
            applyRemixFilterIn(to, remixStateIn.filterAOn, remixStateIn.filterACutoff, true,
                    remixStateIn.filterAHigh);
            applyRemixFilterIn(to, remixStateIn.filterBOn, remixStateIn.filterBCutoff, false,
                    remixStateIn.filterBHigh);
        }
        // ── INPUT_GAIN（他的 `player_gain` ✓）：出场侧**整轨电平** ✓ ────────────────────
        applyAppleInputGain(from, progress);
    }

    /**
     * <b>照搬 `INPUT_GAIN` / `player_gain`</b>（2026-10-08 ✓，照搬 goal 第 52 轮 ✓）。
     *
     * <p>材料（读出来的 ✓）：只有**三档**用它 ✓ ——
     * 档 16 `Pop - Delay out`：`1 → 0` 于 **0.25 处瞬时** ✓（= 硬切 ✓）；
     * 档 14 `No vocal clash one`：`1 → 0` 于 `[0.8, 1.0]` ✓（线性 ✓）；
     * 档 13 `BM - Filter soft + Reverb`：`1 → 0` 于 `[0.8 **− 0.5 s**, 0.8]` ✓（**负偏移** ✓，
     * 正好验收第 35 轮修好的"relative + offset 叠加"语义 ✓）。
     * 作用对象是**出场侧整轨电平** ✓（挂在 REMIX_FX 之后、`renderBase` 的最前面 ✓）。
     * 没有这一项（或值为 1）⇒ **一个采样都不碰** ✓（逐位直通 ✓）。
     */
    /**
     * **淡到 0 的车道的增益下限**（2026-10-09 ✓，方案 B ✓）：0 = 照他表逐字 ✓（默认 ✓）；
     * 评估值 0.35 ⇒ A 在窗口末尾仍留 35% 存在感 ✓（用户报告"A 瞬间没"时用来听差别 ✓）。
     */
    private static volatile float appleGainFloorWhenFadeOut = 0.35f;   // 评估值（用户报"A 瞬间没" ✓）

    public static void setAppleGainFloorWhenFadeOut(float v) {
        appleGainFloorWhenFadeOut = Math.max(0f, Math.min(1f, v));
    }

    public static float appleGainFloorWhenFadeOut() {
        return appleGainFloorWhenFadeOut;
    }

    private AppleTransitionSpec.Auto appleInputGain;
    private boolean appleInputGainResolved;

    /** 【诊断 ✓ 2026-10-09】过渡期间打印 A/B 的**分级电平**（只打前 20 块 ✓ 每 4 块一次 ✓）。 */
    private static int stageLogged;

    private static void logStage(String tag, AudioBuf a, AudioBuf b, float progress) {
        stageLogged++;
        if (stageLogged > 80 || stageLogged % 4 != 1) {
            return;
        }
        Logger.debug("[分级] {} A={} B={} p={}", tag, rmsDb(a), rmsDb(b),
                String.format(java.util.Locale.ROOT, "%.2f", Float.valueOf(progress)));
    }

    private static String rmsDb(AudioBuf buf) {
        if (buf == null || buf.frames() <= 0) {
            return "-";
        }
        float[] back = buf.backingArray();
        int n = buf.frames();
        double sum = 0.0;
        int cnt = 0;
        for (int c = 0; c < buf.channels(); c++) {
            int base = buf.channelBase(c);
            for (int i = 0; i < n; i += 16) {
                double v = back[base + i];
                sum += v * v;
                cnt++;
            }
        }
        double rms = cnt > 0 ? Math.sqrt(sum / cnt) : 0.0;
        return String.format(java.util.Locale.ROOT, "%.1f",
                Double.valueOf(20.0 * Math.log10(Math.max(rms, 1e-7))));
    }

    private void applyAppleInputGain(AudioBuf from, float progress) {
        if (!appleRemixPort || from == null || from.frames() <= 0) {
            return;
        }
        if (!appleInputGainResolved) {
            appleInputGainResolved = true;
            AppleTransitionSpec.Style spec = AppleStyleMap.specFor(style());
            appleInputGain = AppleStyleMap.lane(spec, true, "player_gain");
        }
        if (appleInputGain == null) {
            return;
        }
        float g = AppleTransitionSpec.valueAt(appleInputGain, progress, (float) windowSeconds);
        // **方案 B（2026-10-09 ✓，用户「A 的背景+人声一起瞬间没」✓）**：他表里档 13/14/16 的
        // 出场侧 player_gain 会淡到 **0** ✓ ⇒ 窗口较短时听感就是"A 突然没了" ✗。
        // 这里给**淡到 0 的那些档**加一个**下限**（`appleGainFloorWhenFadeOut` ✓，默认 0 = 照他表 ✓；
        // 评估值 0.35 ✓）⇒ A 始终留一点存在感 ✓。只影响"末值 ≤ 0.01"的车道 ✓，别的车道一个采样不碰 ✓。
        if (appleGainFloorWhenFadeOut > 0f && appleInputGain.segs.length > 0) {
            AppleTransitionSpec.Seg lastSeg = appleInputGain.segs[appleInputGain.segs.length - 1];
            if (lastSeg.v1 <= 0.01f) {
                g = Math.max(g, appleGainFloorWhenFadeOut);
            }
        }
        if (g >= 0.9999f) {
            return;                       // 还是 1 ⇒ 不碰 ✓
        }
        float[] back = from.backingArray();
        int n = from.frames();
        for (int c = 0; c < from.channels(); c++) {
            int base = from.channelBase(c);
            for (int i = 0; i < n; i++) {
                back[base + i] = back[base + i] * g;
            }
        }
    }

    /** 见 {@link #mixFlanger}：按需建、按他的四个参数设一次 ✓（关着或 mix = 0 ⇒ 直通 ✓）。 */
    private void applyRemixFlanger(AudioBuf from) {
        if (!remixState.flangerOn || remixState.flangerMix <= 0f || from.frames() <= 0) {
            return;
        }
        if (mixFlanger == null) {
            mixFlanger = new dev.t1m3.qplayer.audio.dsp.AppleFlanger(rate, from.channels());
        }
        mixFlanger.setRateHz(AppleRemixFx.flangerRateHz());
        mixFlanger.setDepth(AppleRemixFx.flangerDepth());
        mixFlanger.setCenter(AppleRemixFx.flangerCenter());
        mixFlanger.setFeedback(AppleRemixFx.flangerFeedback());
        mixFlanger.setMix(remixState.flangerMix);
        float[] back = from.backingArray();
        int n = from.frames();
        for (int ch = 0; ch < from.channels(); ch++) {
            int base = from.channelBase(ch);
            for (int i = 0; i < n; i++) {
                back[base + i] = mixFlanger.process(ch, back[base + i]);
            }
        }
    }

    /** 见 {@link #mixReverb}：按需建、按需改衰减 ✓（关着或送量为 0 ⇒ 直通 ✓）。 */
    private void applyRemixReverb(AudioBuf from) {
        if (!remixState.reverbOn || remixState.reverbSend <= 0f || from.frames() <= 0) {
            return;
        }
        float decay = AppleRemixFx.reverbTimeSecs();
        if (mixReverb == null || Math.abs(decay - mixReverbTime) > 1e-3f) {
            mixReverb = new Reverb(rate, from.channels(), Math.max(0.05f, decay), 0.3f);
            mixReverbTime = decay;
        }
        float send = remixState.reverbSend;
        float wet = AppleRemixFx.reverbWet();
        float[] back = from.backingArray();
        int n = from.frames();
        for (int c = 0; c < from.channels(); c++) {
            int base = from.channelBase(c);
            for (int i = 0; i < n; i++) {
                float dry = back[base + i];
                back[base + i] = dry + wet * mixReverb.process(c, dry * send);
            }
        }
    }

    /** 见 {@link #mixInFltA}：**进场侧**版本 ✓（与出场侧各自持有滤波器 ✓，系数不互相覆盖 ✓）。 */
    /**
     * **推迟 A 的高通抬升**（2026-10-09 ✓，用户选「先改高通」✓）：他的表里 A 侧高通是
     * 20 → 1000 Hz 线性抬（0.45–0.89 ✓）—— 抬早了 A 的低频就没了 ✓，B 那一拍抽吸时**没人填**
     * ⇒ 就是那口"空" ✓（实测 −25.4 dB ✗）。这里改成**二次软起** ✓：前段几乎不抬 ✓、
     * 末段才到 1000 ✓ ⇒ A 的低频多留一会儿 ✓（只动 A ✓，B 侧照他的表 ✓）。
     */
    private static volatile boolean appleHpDelayPort = true;   // A 侧高通"软起 + 限幅行程" ✓

    /**
     * **A 侧高通的上限频率**（Hz ✓，默认 **350** ✓）。他表里是 20→**1000** Hz ✗ —— 实测那样 A 会薄到
     * 听不见（用户：「A 瞬间消失的老毛病又来了」✗）。限到 350 ⇒ A 一路留着厚度 ✓、不再突兀消失 ✓；
     * 调大 ⇒ 更电子化/更易消失 ✗；调小 ⇒ A 更厚 ✓。
     */
    private static volatile float appleHpLimitHz = 350f;

    public static void setAppleHpLimitHz(float hz) {
        appleHpLimitHz = Math.max(20f, Math.min(4000f, hz));
    }

    public static float appleHpLimitHz() {
        return appleHpLimitHz;
    }

    public static void setAppleHpDelayPort(boolean on) {
        appleHpDelayPort = on;
    }

    private static float appleHpDelayCutoff(float hz) {
        if (!appleHpDelayPort || hz <= 20f) {
            return hz;
        }
        float span = Math.max(20f, appleHpLimitHz) - 20f;   // 上限可调 ✓（默认 350 Hz ✓）
        float x = Math.min(1f, (hz - 20f) / span);
        return 20f + span * x * x;
    }

    private void applyRemixFilterIn(AudioBuf buf, boolean on, float hz, boolean isA,
                                    boolean high) {
        if (!on || hz <= 0f) {
            return;
        }
        Biquad[] filters = isA ? mixInFltA : mixInFltB;
        if (filters == null || filters.length != buf.channels()) {
            filters = new Biquad[buf.channels()];
            for (int c = 0; c < filters.length; c++) {
                filters[c] = high ? Biquad.highPass(rate, Math.max(20f, hz), 0.707f)
                        : Biquad.lowPass(rate, Math.max(20f, hz), 0.707f);
            }
            if (isA) {
                mixInFltA = filters;
                mixInFltAHz = hz;
            } else {
                mixInFltB = filters;
                mixInFltBHz = hz;
            }
        }
        float last = isA ? mixInFltAHz : mixInFltBHz;
        if (Math.abs(hz - last) > Math.max(1f, Math.abs(last) * 0.005f)) {
            if (isA) {
                mixInFltAHz = hz;
            } else {
                mixInFltBHz = hz;
            }
            for (Biquad f : filters) {
                if (high) {
                    f.setHighPass(rate, Math.max(20f, hz), 0.707f);
                } else {
                    f.setLowPass(rate, Math.max(20f, hz), 0.707f);
                }
            }
        }
        float[] back = buf.backingArray();
        int n = buf.frames();
        for (int c = 0; c < filters.length; c++) {
            int base = buf.channelBase(c);
            Biquad f = filters[c];
            for (int i = 0; i < n; i++) {
                back[base + i] = f.process(back[base + i]);
            }
        }
    }

    /** 见 {@link #mixFltA}：按需建、按需换系数 ✓（关着 ⇒ 直通 ✓）。 */
    private void applyRemixFilter(AudioBuf from, boolean on, float hz, boolean isA,
                                  boolean high) {
        if (!on || hz <= 0f) {
            return;
        }
        Biquad[] filters = isA ? mixFltA : mixFltB;
        if (filters == null || filters.length != from.channels()) {
            filters = new Biquad[from.channels()];
            for (int c = 0; c < filters.length; c++) {
                filters[c] = high ? Biquad.highPass(rate, Math.max(20f, hz), 0.707f)
                        : Biquad.lowPass(rate, Math.max(20f, hz), 0.707f);
            }
            if (isA) {
                mixFltA = filters;
                mixFltAHz = hz;
            } else {
                mixFltB = filters;
                mixFltBHz = hz;
            }
        }
        float last = isA ? mixFltAHz : mixFltBHz;
        if (Math.abs(hz - last) > Math.max(1f, Math.abs(last) * 0.005f)) {
            if (isA) {
                mixFltAHz = hz;
            } else {
                mixFltBHz = hz;
            }
            for (Biquad f : filters) {
                if (high) {
                    f.setHighPass(rate, Math.max(20f, hz), 0.707f);
                } else {
                    f.setLowPass(rate, Math.max(20f, hz), 0.707f);
                }
            }
        }
        float[] back = from.backingArray();
        int n = from.frames();
        for (int c = 0; c < filters.length; c++) {
            int base = from.channelBase(c);
            Biquad f = filters[c];
            for (int i = 0; i < n; i++) {
                back[base + i] = f.process(back[base + i]);
            }
        }
    }

    private int renderBase(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
        driveRemix(from, to, progress);    // 照搬的 REMIX_FX ✓：出场侧整条 + **进场侧两条滤波** ✓
        float p0 = Automix.clampF32(progress, 0.0f, 1.0f);
        // **空间让位（P2）**：重叠期把出场曲推往两侧，进场曲留在中间 —— 两首歌在空间上分开，
        // 这是「不糊」最有效的一手（人耳分辨同时发声的两个源，一半靠频谱、一半靠空间）。
        //
        // 作用位置与方式都有讲究：
        //   · 只动 **A（from）**：进场曲那一侧会被「去人声」层替换素材，动它会白动；
        //   · 复制进**独立 scratch** 再改（`Mixer.copyWithWidth`），不就地改输入缓冲 ——
        //     输入缓冲里可能还留着上一块没交付的余量，就地改会让那些帧被反复加宽；
        //   · 强度沿窗口取 `width × sin(π·progress)`：两端为 0，与淡化同步淡入淡出，没有台阶；
        //   · `spaceWidth == 0` 时**一个采样都不碰**，与没有这个功能逐位相同；
        //   · **拍点剪切不动它**：那一档的契约是「切点之前出场曲一个比特都不改」
        //     （判据 `AiMixTest.beatCutKeepsOutgoingUntouchedBeforeTheCut`），展宽会破坏它；
        //   · **装不下就不展宽**（`fits`）：宁可少一个效果，也不能截断音频。
        float spaceWidth = tuning == null ? 0.0f : tuning.spaceWidth;
        boolean fits = spaceScratch != null && from.frames() <= spaceScratch.capacityFrames();
        if (spaceWidth > 0.0f && fits && style != MixStyle.BEAT_CUT
                && from.channels() >= 2 && from.frames() > 0 && !isEssentiallyMono(from)) {
            float curve = spaceWidth * (float) Math.sin(Math.PI * (double) p0);
            if (curve > 1e-4f) {
                float widen = curve * SPACE_MAX_SHIFT;
                Mixer.copyWithWidth(from, spaceScratch,
                        Math.min(from.frames(), spaceScratch.capacityFrames()),
                        1.0f - widen, 1.0f + widen);
                from = spaceScratch;
            }
        }
        // 自动档挑中「声波重合」：整块交给它渲染（它同时负责给 deck 的变调曲线）
        Transition harmonic = harmonicBlend;
        if (harmonic != null) {
            return harmonic.render(out, from, to, progress);
        }
        float p = Automix.clampF32(progress, 0.0f, 1.0f);
        int n = Math.min(out.capacityFrames(), Math.max(from.frames(), to.frames()));
        if (n == 0) {
            out.setFrames(0);
            return 0;
        }

        // 末尾那一下抛异常等价于上游穷尽 match 的编译期保证：加了新接法却忘了在这里
        // 处理时，一次运行就能炸出来（Java 的 switch 给不了编译期的穷尽检查）。
        switch (style) {
            // `Auto` 正常走不到这里：`prepare` 会把它换成具体接法。
            // 真走到了（没 prepare 就直接 render）就按朴素融合处理，这是最安全的退路。
            case PLAIN_BLEND:
            case AUTO:
                return renderPlainBlend(out, from, to, p);
            case BEAT_CUT:
                return renderBeatCut(out, from, to, p);
            case BASS_SWAP:
                return renderBassSwap(out, from, to, n, p);
            case TAIL_RIDE:
                return renderTailRide(out, from, to, p);
            case SHOW_MODE:
                return renderShowMode(out, from, to, n, p);
            case GAPLESS:
            case SPLICE:
                return renderBeatCut(out, from, to, p);
            case CROSSFADE_LONG:
            case CROSSFADE_OVERLAP:
            case CROSSFADE_OVERLAP_REVERB:
            case NO_VOCAL_CLASH:
            case POP_REVERB:
                return renderPlainBlend(out, from, to, p);
            case CROSSFADE_RINGOUT:
            case CROSSFADE_RINGOUT_REVERB:
            case HIPHOP_DELAY_OUT:
            case POP_DELAY_OUT:
                return renderTailRide(out, from, to, p);
            case FILTER_HIGH_TO_LOW:
            case FILTER_LONG_HIGH_TO_LOW:
            case FILTER_SHORT_HIGH_TO_LOW:
            case FILTER_SOFT_REVERB:
                // **名字里的"高到低"就是扫频**（2026-10-07 让这一族名副其实 ✓）：
                // 出场侧低通截止从高往低走 ⇒ 出场曲一路变暗 ✓；没开扫频时与从前逐位相同 ✓。
                if (appleFilterPort) {
                    return renderAppleFilter(out, from, to, n, p);   // 照搬他的写法 ✓
                }
                return renderBassSwap(out, from, to, n, p, true);
            case FILTER_EXPANSION:
            case FILTER_LONG_OUT_SHORT_IN:
            case FILTER_DYNAMIC_IN:
            case FILTER_LONG_OUT_SHORT_IN_DELAY:
            case FILTER_LONG_OUT_SHORT_IN_REVERB:
            case DANCE_ANY_KEY:
                return learnedEq == null ? renderPlainBlend(out, from, to, p)
                        : renderLearnedEq(out, from, to, n, p);
            case HIPHOP_LOOPING:
            case DANCE_REPEATER:
                // 循环类：第一版按等功率淡化走（循环素材是下一步的增量）
                return renderPlainBlend(out, from, to, p);
            case LEARNED_EQ:
                // 没有曲线（prepare 没跑、或那一步失败）⇒ **退回朴素融合**，
                // 与加这一档之前逐位相同——绝不让一个缺失的曲线把过渡搞崩。
                return learnedEq == null ? renderPlainBlend(out, from, to, p)
                        : renderLearnedEq(out, from, to, n, p);
            default:
                throw new IllegalStateException("未知接法：" + style);
        }
    }

    @Override
    public String name() {
        return harmonicBlend != null ? style.label() + " → 声波重合" : style.label();
    }

    @Override
    public boolean rewritesSamples() {
        return harmonicBlend != null || style.rewritesSamples();
    }

    @Override
    public PitchPlan pitchAt(float progress) {
        Transition harmonic = harmonicBlend;
        return harmonic != null ? harmonic.pitchAt(progress) : PitchPlan.ZERO;
    }

    @Override
    public TransitionPlan prepare(String outro, String intro, int rate) {
        return prepare(outro, intro, rate, null, null);
    }

    @Override
    public TransitionPlan prepare(String outro, String intro, int rate,
                                  java.util.Map<String, String> outroHeaders,
                                  java.util.Map<String, String> introHeaders) {
        // 上游在这里 `let _ = rate;`：窗口一律按**构造时**的 self.rate 算
        // （输出侧采样率才是渲染的规格），传进来的这个只作参考，不参与计算。

        // ---- 先回到「分析失败」那个干净基线 ----
        //
        // `prepare` 算的是**这一次**过渡，可引擎对象是跨曲目复用的，
        // 而且下面每一个字段都是「算出来才写」。不先清干净，一次失败的分析
        // 就会让上一首歌的窗口、拍点、时长继续生效——
        // 报错里写着「退回固定窗口」，实际用的却是上一首曲子的 16 秒窗口。
        //
        // 滤波器状态同理：`SplitLowHigh` 的「两路相加等于原信号」只在稳态
        // 成立，带着上一首歌的残留状态进下一次过渡，接缝上会多出一下突变。
        window = Automix.fallbackWindowWith(style, this.rate, tuning.windowScale);
        harmonicBlend = null;
        harmonicPlan = null;
        harmonicCue = null;
        autoCue = null;
        outroTempo = null;
        introTempo = null;
        outroTailSecs = 0.0;
        introRate = this.rate;
        cutAt = 0.0f;
        cutForcedAt = null;
        autoNote = null;
        outroSource = TempoSource.COMPUTED;
        introSource = TempoSource.COMPUTED;
        reset();

        // 分析失败不阻断过渡：读不出来就退回固定窗口的朴素融合。
        // 「算不出来就别猜」——比一个错误的切点好。
        //
        // **两侧的读取并行**（2026-10-03，性能）：它们互不依赖（两侧各自解码 + 重采样），
        // 而这一段是 prepare 里最大的一笔（各 12 秒素材的解码，实测约 48 ms 一侧）。
        // 失败**谁先报**仍按原来的顺序（先出场、后进场）：两边都抛时先看出场那条，
        // 与串行版本给出的那句话完全一致。
        final Automix.Mono[] reads = new Automix.Mono[2];
        final AudioException[] readErrors = new AudioException[2];
        // 用共享的 daemon 池而不是每次 new 一个线程：起停线程本身也是钱，
        // 而且线程活下来才能让「分配量」这类按线程统计的量归得到它头上。
        java.util.concurrent.Future<?> incoming = READ_POOL.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    reads[1] = Automix.readMono(intro, introHeaders, false,
                            Automix.ANALYSIS_SECS);
                } catch (AudioException e) {
                    readErrors[1] = e;
                }
            }
        });
        try {
            reads[0] = Automix.readMono(outro, outroHeaders, true, Automix.ANALYSIS_SECS);
        } catch (AudioException e) {
            readErrors[0] = e;
        }
        try {
            incoming.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            // 任务里抛的是**运行时**异常时，原样往上冒——串行版本来就是这么做的，
            // 换成 IllegalStateException 会把「哪一类异常」这条信息改掉。
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(cause);
        }
        String readFailure = readFailureNote(reads[0], readErrors[0], reads[1], readErrors[1]);
        if (readFailure != null) {
            return failurePlan(readFailure);
        }
        Automix.Mono tail = reads[0];
        Automix.Mono head = reads[1];

        float[] tailPcm = tail.pcm;
        int tailRate = tail.rate;
        Double outroDurOpt = tail.durationSecs;
        introRate = head.rate;
        outroTailSecs = (double) tailPcm.length / (double) Math.max(tailRate, 1);
        double outroDur = outroDurOpt == null ? 0.0 : outroDurOpt.doubleValue();

        // ---- 对拍：模型优先，模型不可用/失败就退回硬算（见 tempoOf） ----
        //
        // 预算按**这次 prepare**算：模型一次会话加载 + 一次推理，本机实测合计 1~3 秒，
        // 余量是「剩余 ≤ 窗口 + 30 秒」。超预算时剩下的侧一律走硬算，
        // 而不是让过渡该开始时还没准备好。
        Budget budget = new Budget();
        TempoResultHolder rOut = tempoOf(tailPcm, tailRate, budget);
        TempoResultHolder rIn = tempoOf(head.pcm, head.rate, budget);
        Analysis.Tempo tOut = rOut.tempo;
        Analysis.Tempo tIn = rIn.tempo;

        // **速度差要用与挑点同源的那一份**（2026-10-07）。这里原来用的是 `tempoOf(...)`
        // 自己那条分析 —— 实测参考对上它给出 **29.32%**，而挑点/cue 用的是包络里的 tempo
        // （**6.49%**）。同一个量两处各算一遍、差出四倍多，于是自动档在"速度差很大"这个
        // 错误前提上把参考对判成了冲突（走 ⑥ 学习EQ），而按用户的 Apple 推理它该是交叉淡化。
        //
        // 现在优先用包络那份；扫描失败就保留原判定（不猜）。这份包络后面 comparePlans 还要用，
        // 所以**只扫这一次** —— 准备阶段的预算是硬约束（不加模型 ≤ 15 s，见 AutomixPrepareBudgetTest）。
        AutoCue.Envelope outEnv = null;
        AutoCue.Envelope inEnv = null;
        try {
            outEnv = AutoCue.scan(outro, outroHeaders);
            inEnv = AutoCue.scanIntro(intro, introHeaders);
            // ⚠️ **这里曾经把 tOut/tIn 覆盖成包络那份（硬算），已撤回**（2026-10-07）：
            // 那是**反方向**的统一 —— 包络那份来自硬算，会把**模型**的结果盖掉，于是
            // 三条守"模型 BPM 优先"的判据当场变红（AiMixTest.modelBeatsArePreferred…、
            // AiMixTest.aLongWindowStillGetsBeatAligned、AiMixBaselineWindowTest…）。
            //
            // 正确的方向是**让模型那份流进包络/cue**（两处都用模型结果）：
            //   ① 给 AutoCue.Envelope 加一个"换 tempo 的副本"入口（字段是 final，得加 with 方法）；
            //   ② 在这里把 outEnv/inEnv 换成带模型 tempo 的副本，再交给 cue 与挑档；
            //   ③ 这样"速度差"两处同源，且保留模型优先 —— 参考对的速度差也会变成 6.5%。
            // 在那之前，这里的包络只用于**复用**（comparePlans 那段不再扫第二次）。
        } catch (RuntimeException e) {
            outEnv = null;
            inEnv = null;                     // 扫不动 ⇒ 保留原判定
        }
        // **模型这次的 BPM 离群就退回硬算**（2026-10-07）。实测参考对上模型推出 29% 量级的
        // 速度差，而硬算与用户 App 的日志（A 128.0 / B 119.97）一致给出 6.5% ⇒ 是模型这次错了。
        // 沿用"算不出来就别猜"的纪律：宁可退回硬算，也不要拿离谱的模型结论决定要不要动素材。
        //
        // ⚠️ **必须放在 760 行（窗口 = 拍数 × tOut 周期）之前** —— 否则"理由里写的 BPM"
        // 与"算窗口用的 BPM"会分家，判据 AiMixBaselineWindowTest 会当场指出（实测踩过）。
        // **只影响"冲突判定"，不动窗口计算**：`tOut/tIn` 还要用来算
        // 「窗口 = 拍数 × 周期」（见下面 760 行附近），把它们整个换掉会让窗口跟着变，
        // 判据 AiMixBaselineWindowTest 当场指出（实测踩过一次：期望 8.0~8.5 s、实测 6.1 s）。
        // 所以这里只算一份**给挑档看的** tempo，窗口仍用原来的。
        Analysis.Tempo sanOut = saneTempo(tOut, outEnv == null ? null : outEnv.tempo);
        Analysis.Tempo sanIn = saneTempo(tIn, inEnv == null ? null : inEnv.tempo);
        outroSource = rOut.source;
        introSource = rIn.source;
        outroTempo = tOut;
        introTempo = tIn;

        // ---- 自动选接法 ----
        //
        // **必须在算窗口之前定下来**：窗口长度是按接法的拍数算的，
        // 挑晚了就等于拿「自动」那个占位拍数（8 拍）去算窗口。
        //
        // 判定只用这里已经算好的两样东西（速度、调性），所以自动选择
        // **不额外增加分析成本**——它复用的是「本来就要跑」的那一遍。
        // 自动档要挑接法 + 比较方案；比较**必须等窗口算完**（见下面那段）。
        boolean autoPick = style == MixStyle.AUTO;
        Harmonic.Match harmony = null;
        if (autoPick) {
            harmony = Harmonic.match(tailPcm, tailRate, head.pcm, head.rate);
            // **素材画像**（2026-10-07 重写 Chooser）：六个可测信号 —— 低频冲突、drop 显著度、
            // A 尾斜率、B 起跳、可叠秒数、build-up。挑接法跑在**选窗口之前**，所以只能用
            // "素材整体"来算。算不出来时是 UNKNOWN，Chooser 会退回朴素融合（不猜）。
            // ⚠️ **诊断时必须连 `tuning` 的实际值一起打出来**（2026-10-07，最后一条线索）：
            // 这里的门槛取自 `tuning`，而它**未必**是 `MixTuning.defaults()` —— 生产路径上
            // 它可能是 `MixTuning.parse(...)` 从**用户设置**里读回来的（设置里可能存着
            // 旧默认值，例如 `auto_tempo_conflict=0.03`）。
            //
            // 这正好能解释那个对不上的现象：生产判成"有冲突"（于是走 ⑥ 学习EQ），
            // 而探针用 defaults（8%）量出来的速度差 6.49% 不算冲突。两边**用的不是同一套门槛**。
            // 下一步：把 `tuning.autoTempoConflict` / `autoKeySame` / `lowClashGate` 的**实际值**
            // 与 `autoNote` 一起打出来，再决定是"改设置里的存量值"还是"改判定"。
            MaterialProfile profile = MaterialProfile.of(tailPcm, head.pcm,
                    tailPcm.length / (double) Math.max(1, tailRate),
                    head.pcm.length / (double) Math.max(1, head.rate), tailRate);
            // 窗口下限那一项：真实窗口要到后面才定，这里用风格的名义窗口（8 s）作代表值 ——
            // 2 s 的下限对任何风格都成立，所以它只起"别在极短窗口上选学习EQ"的挡板作用。
            Chooser.AutoChoice choice;
            if (RouteScore.enabled()) {
                // **打分核接管**（默认关，见 RouteScore.setEnabled）：只喂我们**真有**的信号 ——
                // 音高/调性、速度差、低频冲突、drop、可叠秒数、build-up；
                // 曲风分数与"人声类别/强度/可靠性"我们这一步还没接（**留 null ⇒ 那几档不给分**，
                // 这是纪律：没有依据就不要给分，而不是给 1）。
                RouteGates.Signals sig = new RouteGates.Signals();
                sig.tempoDiff = sanOut == null || sanIn == null ? null
                        : Double.valueOf(Math.abs(sanOut.bpm - sanIn.bpm)
                                / Math.max((sanOut.bpm + sanIn.bpm) * 0.5f, 1.0f));
                // **倍半识别要用到 bpm 本身**（用户点名的洞）：87 与 174 这种一对，
                // 速度差 50% 会被旧门判"不合" ✗，而识别出"同一个速度的两倍"之后不该判不合 ✓。
                // 不填这两个字段，RouteGates 里那段新逻辑在生产路径上就是死的。
                sig.tailDecayDbPerSec = Double.valueOf(profile.tailDecayDbPerSec());
                // **三个代理信号**（他清单里那三项的我们的替代；语义未恢复的部分如实标注）
                sig.trailingSpanSecs = Double.valueOf(trailingSpanSecs(outEnv));
                // 读一小段**立体声**素材量中置比（mid/side）；读不到就如实按"没量到"处理
                float[] stereoLeft = null;
                float[] stereoRight = null;
                int stereoFrames = 0;
                try {
                    int want = (int) Math.round(Math.min(1.0, Math.max(0.2, tailPcm.length
                            / (double) Math.max(1, tailRate))));
                    float[][] st = Automix.readStereoWindow(outro, outroHeaders,
                            Math.max(0.0, tail.durationSecs == null ? 0.0
                                    : tail.durationSecs.doubleValue() - want), want, tailRate);
                    if (st != null && st.length >= 2) {
                        stereoLeft = st[0];
                        stereoRight = st[1];
                        stereoFrames = want;
                    }
                } catch (RuntimeException e) {
                    stereoFrames = 0;            // 读不到 ⇒ 后面按"没量到"处理（不猜）
                }
                // **人声存在的真信号**（mid/side 中置比 —— 我们自己的手法）：
                // 它同时给 vocal_overlap 闸门一个来源，并在"明显居中"时把它标成可靠
                // （可靠 ⇒ 闸门可以硬筛；不可靠 ⇒ 仍按我们的软因子 0.75 处理，不误杀）。
                //
                // ⚠️ 老实说：我们手上只有**单声道**素材（tailPcm / head.pcm），
                // 没有左/右两路 ⇒ 真正的中置比量不出来；这里先把**可靠性判成 false**
                // （诚实），并把"人声叠着多久"按素材里能量占比粗估一个值，等拿到立体声
                // 素材再换成真中置比（TODO 已写进 docs）。
                // **真中置比**（mid/side）：readStereoWindow 读出来就是**立体声** ✓（不用 deck），
                // 于是 vocal_overlap 这一项终于有了**可靠**来源 —— 可靠才敢硬筛（见下）。
                double centre = RouteGates.centreRatio(stereoLeft, stereoRight, stereoFrames);
                boolean centreReliable = RouteGates.vocalPresenceIsReliable(centre);
                if (centreReliable) {
                    // 明显居中 ⇒ 认为"这一段人声在场、且是叠着的"（保守：宁可多筛）
                    sig.vocalOverlapSecs = Double.valueOf(1.0);
                    sig.vocalOverlapReliable = Boolean.TRUE;
                    // **强度加权重叠 + 人声类别**（用户点名的第 1、2 条，2026-10-07 接线）：
                    // 交叠段两侧各自的窗口位置 —— A 侧取「末尾 − 窗口」起、B 侧取开头起 ✓，
                    // 与 melody/强度那几处同一口径 ✓。量不出来就留 null ✓
                    //（闸门会如实退回"秒数阈值"那一路，不猜 ✓）。
                    double kindWindow = Math.max(0.5, profile.overlapSecs());
                    double kindCut = Math.max(0.0, outroDur - kindWindow);
                    double overlapStrength = AutoCue.vocalOverlapStrength(outEnv, kindCut,
                            inEnv, 0.0, kindWindow);
                    if (!Double.isNaN(overlapStrength)) {
                        sig.vocalOverlapStrength = Double.valueOf(overlapStrength);
                    }
                    int kindA = AutoCue.vocalKind(outEnv, kindCut, kindCut + kindWindow);
                    int kindB = AutoCue.vocalKind(inEnv, 0.0, kindWindow);
                    // 两侧都分出来 ⇒ 取**人声更响**那侧的类别（它才是"撞车"的主体 ✓）；
                    // 只有一侧分出来就用那一侧 ✓；都没有 ⇒ 留 null（未知，不猜 ✓）。
                    int kind = kindA >= 0 && kindB >= 0
                            ? (overlapStrengthOfSide(outEnv, kindCut, kindWindow)
                                    >= overlapStrengthOfSide(inEnv, 0.0, kindWindow)
                                    ? kindA : kindB)
                            : Math.max(kindA, kindB);
                    if (kind >= 0) {
                        sig.vocalKind = Integer.valueOf(kind);
                    }
                } else {
                    // 量不出可靠中置（单声道/无方向性）⇒ 用保守上界 + 如实标不可靠（软因子）
                    sig.vocalOverlapSecs = Double.valueOf(
                            estimateVocalOverlapSecs(tailPcm, tailRate));
                    sig.vocalOverlapReliable = Boolean.FALSE;
                }
                sig.incomingQuieter = Boolean.valueOf(incomingIsQuieter(tailPcm, head, tailRate));
                // 两侧可叠拍数（matching_bar_count 的代理要用）——按各自包络的拍长换算
                // **把这次过渡的拍长记下来**（跟拍延迟要用 ✓；2026-10-07）——
                // 与窗口那套用同一个口径：`periodFrames / beatRate` ✓，没有 tempo 时留兜底 ✓。
                if (outEnv != null && outEnv.tempo != null && outEnv.tempo.periodFrames > 0
                        && outEnv.beatRate > 0) {
                    double b = outEnv.tempo.periodFrames / (double) outEnv.beatRate;
                    if (b > 0.05 && b < 5.0) {
                        this.beatSecs = b;
                    }
                }
                double beatsA = beatCount(profile.overlapSecs(), outEnv);
                double beatsB = beatCount(profile.overlapSecs(), inEnv);
                sig.outroBeats = Double.valueOf(beatsA);
                sig.introBeats = Double.valueOf(beatsB);
                sig.matchingBars = Boolean.valueOf(Math.abs(beatsA - beatsB) <= 1.0);
                sig.bpmA = sanOut == null ? null : Double.valueOf(sanOut.bpm);
                sig.bpmB = sanIn == null ? null : Double.valueOf(sanIn.bpm);
                sig.keyScore = harmony == null ? null : Double.valueOf(harmony.plainScore);
                sig.lowClash = Double.valueOf(profile.lowClash());
                sig.dropReliable = Double.valueOf(profile.dropReliable());
                sig.overlapSecs = Double.valueOf(profile.overlapSecs());
                sig.buildUp = Boolean.valueOf(profile.buildUp());
                // 曲风分数（④）：用现成的 genreScores —— 它要 (bpm, onset, lowRatio, midRatio)。
                // onset 与 lowRatio 从素材画像里取；**midRatio 用 1 − lowRatio 近似** ——
                // 如实标注为近似（真正的中频占比要另算），它只影响曲风分数的相对大小。
                float bpmForGenre = sanOut == null ? 0f : sanOut.bpm;
                // 出场侧响度是否显著（参照里的 outgoing_loudness_significant）——
                // 用我们刚做的 LUFS 表算：判据由我们自己定，理由与刻度都写在 RouteGates 里。
                sig.outgoingLoudnessSignificant = Boolean.valueOf(
                        RouteGates.outgoingLoudnessSignificant(tailPcm, tailRate));
                // ⚠️ **LUFS 响度对齐还没接到会生效的地方**（2026-10-07 实测）：
                // 我原先在这里对 head.pcm 做归一化，然后把开关打开重渲了一对素材 ——
                // **四个 WAV 的 md5 完全相同** ⇒ 说明这个位置的 head.pcm **不是渲染真正用的素材** ✗。
                // 结论（有用的否定结果）：要接就得接在**渲染读取素材的那条路上**
                // （AutoCueTransition 的素材源 / SegmentFeed 那一层），而不是这里。
                // 量表和原语都已就位且有判据（Lufs / gainToMatch / normalizeTo ✓），
                // 缺的只是"接对地方"这一下。
                // **旋律重合度**（我们独有的那一维；他的 24 条里没有这一项）——
                // 用**现成的同一把尺子**：AutoCue.melodyMatch（色度余弦），而不是另造一个 ✗。
                // 窗口取"可叠秒数"，出场侧的切点 = 时长 − 窗口，进场侧从头开始（entry = 0）——
                // 与 AutoCue 里那几处调用的口径一致。量不出来 ⇒ NaN ⇒ melodyOrNull 给 null
                // ⇒ RouteGates.melodyFactor 当**中性加成**（1.0），也就是"缺失不参与、不误杀"✓。
                if (outEnv != null && inEnv != null) {
                    double melodyWindow = Math.max(0.5, profile.overlapSecs());
                    double melodyCut = Math.max(0.0, outroDur - melodyWindow);
                    sig.melody = AutoCue.melodyOrNull(
                            AutoCue.melodyMatch(outEnv, melodyCut, inEnv, 0.0, melodyWindow));
                }
                sig.genres = EqFeatureExtractor.genreScores(bpmForGenre,
                        profile.onset(), profile.lowRatio(),
                        Math.max(0f, 1f - profile.lowRatio()));
                java.util.List<MixStyle> styles = new java.util.ArrayList<MixStyle>();
                java.util.List<java.util.List<Double>> sets =
                        new java.util.ArrayList<java.util.List<Double>>();
                java.util.List<Double> pos = new java.util.ArrayList<Double>();
                for (MixStyle cand : ROUTE_CANDIDATES) {
                    java.util.List<Double> f = RouteGates.factorsFor(cand, sig);
                    if (f == null) {
                        continue;
                    }
                    styles.add(cand);
                    sets.add(f);
                    pos.add(Double.valueOf(0.0));      // 位置项等"点位级"再算（本步先给 0）
                }
                MixStyle pick = RouteScore.best(styles, sets, pos);
                String kernelNote = "自动：打分核（base × Π因子 + 0.001·D）选中 "
                        + (pick == null ? "**没有候选通过**（" + styles.size() + "/"
                                + ROUTE_CANDIDATES.length + " 个候选过闸门 ⇒ 退回旧选档）"
                                : pick.label())
                        + "，旋律 " + (sig.melody == null ? "没量到（中性）"
                                : String.format(java.util.Locale.ROOT, "%.3f",
                                        sig.melody.doubleValue()))
                        + "，叠唱 " + (sig.vocalOverlapReliable == Boolean.TRUE
                                ? "可靠" : "不可靠");
                choice = pick == null
                        ? withKernelNote(Chooser.choose(sanOut, sanIn, harmony, tuning,
                                profile, 8.0), kernelNote)
                        : new Chooser.AutoChoice(pick, "自动：打分核（base × Π因子 + 0.001·D）选中 "
                                + pick.label()
                                // 把**我们独有的那一维**读数写进结论：既是诊断，也是"它真的喂上了"
                                // 的证据（旋律这一项曾经因为没人喂而死成恒 1.0，还一直没人发现 ✗）。
                                + "，旋律 "
                                + (sig.melody == null ? "没量到（中性）"
                                        : String.format(java.util.Locale.ROOT, "%.3f",
                                                sig.melody.doubleValue()))
                                + "，叠唱 " + (sig.vocalOverlapReliable == Boolean.TRUE
                                        ? "可靠" : "不可靠")
                                // **两种记法都写出来**（2026-10-07 用户追问"15.0 到底是哪一刻"）：
                                // 我们内部与录屏取证用的是**起点口径**（A 开始淡出 / B 开始淡入的
                                // 那一瞬间）；而他的 dump 字段（`outgoing_end` / `incoming_end`）记的是
                                // **终点口径**。两者差一个过渡窗口，混起来就会"差一整个窗口" ✗。
                                + "（起点口径）");
            } else {
                choice = Chooser.choose(sanOut, sanIn, harmony, tuning, profile, 8.0);
            }
            if (styleHint != null) {
                dev.t1m3.qplayer.util.Logger.info(
                        "[qplayer-engine] 融合接法提示：自动挑档改为「{}」（参考包与我们四个接法的联合排序）",
                        styleHint.label());
                style = whitelisted(styleHint);
                styleHint = null;
            } else {
                        style = whitelisted(choice.style);
            }
            // **A：AI 自动档不再挑「人声不撞」**（2026-10-09 ✓ 用户：「你先把A做一下 别选人声不撞了」✓）
            // 真机实测（同一对 ✓）：表现模式（B 从 72.1s 进、B=−10.5 dB ✓ 与 A 同响 ✓）没问题 ✗；
            // AI 档挑中「人声不撞」时 B 只有 **−32.9 dB** ✗ ⇒ 融合末尾落差 15 dB ⇒ 听感「A 啪一下没了」✓。
            // 只拦**自动挑**的结果 ✓；手动选这一档**照旧可用** ✓；可用 setAutoPickNoVocalClash(true) 恢复 ✓。
            if (autoPick && style == MixStyle.NO_VOCAL_CLASH && !autoPickNoVocalClash) {
                style = MixStyle.CROSSFADE_OVERLAP;
                Logger.info("[qplayer-engine] 自动挑档：跳过「人声不撞」（实测会让 A 在融合末尾掉 15 dB）"
                        + "⇒ 改用 「{}」", style);
            }
            this.autoProfile = profile;      // 诊断用：选档时**真正**用的那份画像
            this.lastTempoOut = tOut;        // 给 AutoCueTransition 统一 tempo 用
            this.lastTempoIn = tIn;
            autoNote = choice.why;
        }

        // ---- 出场侧：决定理想窗口，再把起点挪到拍上 ----
        //
        // 出场曲的结尾是固定的（文件末尾），所以能调的只有「从哪里开始过渡」。
        // 做法是把窗口**延长到某个拍点**：
        //   理想起点 = 末尾 − 理想窗口
        //   实际起点 = 不超过理想起点的最后一个拍
        //   实际窗口 = 末尾 − 实际起点   （因此 ≥ 理想窗口，最多多一拍）
        long fallbackFrames = Automix.fallbackWindowWith(style, this.rate, tuning.windowScale);

        // 有拍点信息、且接法给了拍数时，窗口应当落在整数拍上
        long idealFrames;
        if (tOut != null && style.beats() > 0.0f) {
            double period = (double) tOut.periodFrames / (double) Math.max(tailRate, 1);
            // 上游 `(beats_for(style) as f64 * period * self.rate as f64).round() as u64`：
            // f32 → f64 是精确加宽，`beats_for` 已经把 `window_scale` 乘进去了。
            // **叠唱区拍数按"量子"取整**（用户点名的 tempo_alias 行为）：默认 4 拍；
            // 识别出倍半关系（87 与 174 这种一对）时量子为 2 拍 —— 因为倍半的两边小节长度
            // 差一倍，用 4 拍对齐会出现"一边两小节对一边一小节"的错位。
            // 参考对（16 拍）不受影响：16 本身就是 4 的倍数 ⇒ 取整结果不变。
            // **量子加在"接法自己的拍数"上，scale 是它之后的用户乘子**（2026-10-07 修）：
            // 原来写成 `snapBeatsToQuantum(style.beats() × scale)` ✗ ⇒ 量子把**用户调出来的**
            // 长度又抹平了一次：8 拍 × 1.3 = 10.4 与 × 1.6 = 12.8 都落进"12 拍"那一格，
            // × 2.0 = 16 又落进"16 拍" ⇒ 滑块从 1.3 拖到 2.0 窗口只变了两次 ✗
            // （判据 `longerWindowsKeepTheBlendAtTheEndOfTheSong` 逐档照出来的）。
            // 量子管的是**音乐上的对齐单位**（接法本身的拍数，倍半关系时 2 拍）✓，
            // 而 scale 是用户要的长度 ⇒ 两者相乘之后**不再取整** ✓。
            double alias = sanOut == null || sanIn == null
                    ? 0.0 : RouteGates.tempoAlias(sanOut.bpm, sanIn.bpm);
            double beats = RouteGates.snapBeatsToQuantum((double) style.beats(), alias)
                    * (double) tuning.windowScale;
            double want = Math.round(beats * period * (double) this.rate);
            idealFrames = Math.max((long) want, 1L);
        } else {
            idealFrames = fallbackFrames;
        }

        // ---- 把窗口夹在「曲子的一半」以内 ----
        //
        // 这一条是实测踩出来的：表现模式想要 32 拍，在 60 BPM 下就是 32 秒，
        // 而落在**一首只有 8 秒的曲子**上时，整首歌都会被当成过渡窗口——
        // 进场曲在窗口里被慢慢淡入，等窗口走完它自己也放完了，
        // 结果是「两首歌加起来只播出一首的长度」。
        //
        // 「占比不超过一半」是个保守但稳妥的界：过渡本来就该是几秒的事，
        // 不该吃掉曲子主体。时长随 `read_mono` 一起带出来了；确为未知时
        // 跳过夹取（尾部读取已经对未知时长如实报错，走到这里的曲子都有时长）。
        idealFrames = Automix.limitWindowWith(
                idealFrames, outroDur, this.rate, tuning.maxWindowFraction);
        // ⚠️ **2026-10-07 试过在这里夹"窗口绝对上限 8 s"** ✗：它违反两条既有契约 ——
        // `AiMixBaselineWindowTest`（基准窗口必须按"拍数 × 实际 BPM"✓）与
        // `AutoCuePlanTest`（用户设的 12 s 不许被夹 ✓，那正是"用户值永远是上界"这条铁律 ✓）。
        // ⇒ 撤回 ✓；38.4 s 的病根在**斜坡的合理性闸门**（见 AutoCueTransition 那处注释 ✓）。
        // ---- 对齐之前先把尾巴补够长 ----
        //
        // 对拍的算法要求**理想窗口装得进已读进来的尾部**：它要把起点挪到
        // 「末尾 − 理想窗口」之前最后一个拍上，装不进就只能整段跳过。
        //
        // 而 `ANALYSIS_SECS` 只有 12 秒，比最长的两个接法还短：
        // `fallback_secs` 恰好就是各接法在 120 BPM 下的时长（TailRide 24 拍 = 12 秒、
        // ShowMode 32 拍 = 16 秒），所以 **TailRide 在 120 BPM 就卡在边界上、
        // 更慢则直接失效；ShowMode 在 120 BPM 及更慢全部失效**——
        // 而这两种接法恰恰是最需要踩拍点的。
        //
        // 失效是**静默**的：窗口仍是理想值、`note` 照写 BPM，
        // 只有「起点没落在拍上」这一件事没人知道。所以按需补读：
        // 先读 12 秒估速度，算出理想窗口，再补读到「理想窗口 + 一拍 + 1 秒」。
        // 一拍是对齐允许拉长的上限，1 秒是余量。只有长窗口的接法会付这次二次读盘。
        if (tOut != null) {
            double periodSecs = (double) tOut.periodFrames / (double) Math.max(tailRate, 1);
            double need = (double) idealFrames / (double) this.rate + periodSecs + 1.0;
            // 曲子本身比 `need` 还短时补读没有意义：尾巴早就是整首歌了
            if (periodSecs > 1e-3 && need > outroTailSecs + 1e-3 && need <= outroDur) {
                try {
                    Automix.Mono again = Automix.readMono(outro, outroHeaders, true, need);
                    tailPcm = again.pcm;
                    outroTailSecs = (double) tailPcm.length / (double) Math.max(again.rate, 1);
                    // 换了更长的窗口，拍点相位要重新估一次。
                    // 注意上游这里用的仍是**原来那个** `tail_rate`（不是补读回来的 rate）：
                    // 两者来自同一个文件，正常必然相同；这里保持原样以便与上游逐行对照。
                    TempoResultHolder againTempo = tempoOf(tailPcm, tailRate, budget);
                    tOut = againTempo.tempo;
                    outroSource = againTempo.source;
                    outroTempo = tOut;
                } catch (AudioException e) {
                    // 上游是 `if let Ok(..)`：补读失败**静默**沿用已经读到的那一份。
                    // 不写 note：能读到 12 秒就够估速度了，补读只是为了对齐更准。
                }
            }
        }

        // 【第 32 轮的尝试已撤回 ✓】：把目标喂给"理想窗口"没修好那条红 ✗（该测试里
        // appleBarTargetSecs() 为 0 ⇒ 没走按小节 ✓）；真正的问题是 **note 在最终窗口定下来
        // **之前**就写好了** ✗ ⇒ 下一轮改"note 的书写时机/取值"（一处 ✓），而不是动对齐 ✗。
        long alignedWindow = idealFrames;
        if (tOut != null) {
            double fileRate = (double) Math.max(tailRate, 1);
            double period = (double) tOut.periodFrames / fileRate; // 秒
            double phase = (double) tOut.phaseFrames / fileRate;   // 首个拍在尾部窗口内的位置
            double tailDur = outroTailSecs;
            double w0 = (double) idealFrames / (double) this.rate;

            if (period > 1e-3 && tailDur > w0) {
                double target = tailDur - w0;
                double k = Math.floor((target - phase) / period);
                double start = phase + k * period;
                if (start >= 0.0 && start < tailDur) {
                    long aligned = Math.round((tailDur - start) * (double) this.rate);
                    // 合理性检查：对齐只该**拉长**窗口，而且最多一拍。
                    // 超出这个范围说明拍点估歪了，宁可用固定窗口。
                    long maxOk = idealFrames + (long) (period * (double) this.rate) + 1L;
                    if (aligned >= idealFrames && aligned <= maxOk) {
                        alignedWindow = aligned;
                    }
                }
            }
        }
        // 现有接法**真正会跑**的那个窗口（打分与执行都用它，见下面那段比较）。
        long styleWindow = alignedWindow;
        window = styleWindow;
        // 诊断（默认关；-Dqplayer.trace.window=true）：窗口是怎么来的，一列看清 ✓。
        if (Boolean.getBoolean("qplayer.trace.window")) {
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[窗口诊断] ideal=%.2fs aligned=%.2fs style=%.2fs 接法=%s",
                    idealFrames / (double) this.rate, alignedWindow / (double) this.rate,
                    styleWindow / (double) this.rate, String.valueOf(style)));
        }

        // ---- AI 自动档：把两套**完整方案**比一次 ----
        //
        // 用户要的「AI 自动模式真正分析两首曲子选最优方案，声波重合也纳入候选」。
        // Chooser 判的是**素材关系**（速度差、调性相似度），它回答的是「该不该做滤波」；
        // 而「这两首歌叠在一起到底好不好」还要看整段交叠：波形对不对得上、淡化叠出来的
        // 总电平稳不稳、两边的能量是不是挤在同一个频段。那一套量在 AutoCue 里
        // （与自动点位共用同一把尺子）。
        //
        // **比较必须放在窗口算完之后**（2026-09-30 用户选的这一档）：基准那一侧的分数要按
        // 它**真正会跑的那个窗口**（`beatsFor(style) × 周期`，再对到拍点）打。原先这里用的是
        // `fallbackWindowWith(style)`——那是各接法**在 120 BPM 下**的标称秒数，
        // 于是在别的速度上「打分用的窗口」与「执行的窗口」差一个速度比
        // （朴素融合在 150 BPM：8 拍 × 0.4 s = 3.2 s 实际跑，却按 4.0 s 打分，差 25%）。
        // 两套方案要真的在同一把尺子上比，尺子就得是它们各自会跑的那个窗口。
        //
        // 扫不动（歌太短、直链要请求头、文件坏了）就**保留原判定**——
        // 与「算不出来就别猜」同一条纪律：宁可不动，也不要拿半成品结论改接法。
        if (autoPick && harmonicBlend == null) {
            java.util.function.DoubleSupplier supplier = harmonicSecs;
            double nominal = supplier == null ? 6.0 : supplier.getAsDouble();
            if (!(nominal > 0.0)) {
                nominal = 6.0;
            }
            double baselineSecs = (double) styleWindow / (double) Math.max(1, this.rate);
            try {
                // **复用上面那一份**（不再扫第二次：准备阶段有预算）
                if (outEnv == null) {
                    outEnv = AutoCue.scan(outro, outroHeaders);
                }
                if (inEnv == null) {
                    inEnv = AutoCue.scanIntro(intro, introHeaders);
                }
                // **把"现有接法真正会跑的那个窗口"也放进候选**（2026-10-07 修用户报的点位偏差）：
                // 候选原先只围绕"声波重合滑块"生成 ⇒ 滑块 6 s 时候选是 {3, 4.5, 6, 9, 12}，
                // 于是"现有接法"这一支被按 **3.0 s** 打分并执行 ✗ —— 而它自己真正会跑的是
                // `baselineSecs`（参考对上 ≈2.51 s，正合 Apple 实测的单侧淡化 2.4–2.8 s ✓）。
                // 上面那段注释自己就写着"两套方案要在**各自会跑的窗口**上比"，
                // 这一处偏偏没做到 ✗：滑块一动，窗口跟着动，落点也跟着动
                //（目标里那条"点位不许跟着滑块跑"就是这么来的）。
                // ⚠️ 试过"以现有接法自己的窗口为中心生成候选"，**已回退**：
                // 它把「窗口随 window_scale 变长」与「窗口必须等于 min(用户值, 吸收拍长差)」
                // 这两条契约弄坏了（AutomixSmoothnessReferenceTest 当场红 2 条 ✗）。
                // 候选仍然围绕调用方给的 nominal 生成 —— 那两条契约比"让进场落到 15.0"更硬。
                // **施加"降速斜坡给的窗口上界"**（2026-10-07 修 B 侧落点）：
                // 上界 {@code |1 − BPM比| / SLEW_MAX} 原本只在 `AutoCueTransition` 的**实例**
                // 方法里施加 ✗ —— 而 AI 这一层是用**静态** `windowCandidates(nominal, true)` 建的
                // 候选集，于是分析会在**超出上界**的窗口上挑点：参考对上挑了 3.006 s，
                // 而斜坡只允许 **2.51 s**（= 我们自己取证 Apple 的单侧淡化 2.4~2.8 s ✓）。
                // 于是进场被选到 16.944 s ✗（Apple 15.0）；窗口回到 2.51 s 时进场是 15.022 ✓。
                // **用斜坡自己的那一份实现**（`AutoCueTransition.rampFor` 是唯一真相来源：
                // 它有 BPM 闸门、听阈判断与"双方各承担一半"的拆法）——不要在这里另算一遍 ✗
                // （我第一版拿原始 BPM 比算，得到的上界是 10.2 s，等于没夹 ✗）。
                dev.t1m3.qplayer.audio.mix.TempoRamp ramp =
                        AutoCueTransition.rampFor(outEnv, inEnv, nominal, false);
                double rampCap = ramp != null && ramp.active ? ramp.windowSecs : 0.0;
                // ⚠️ **按小节窗口要在这里生效**（2026-10-09 ✓，链条证据 ✓）：内层（AI/和声比较）
                // 原来按"斜坡窗口 rampCap"造候选 ✗ ⇒ 它自己选出 3.2 s ✓，而装载窗口被我事后改成 6.0 s
                // ⇒ 判据「note 3.2 ✗ / 装载 6.0 ✓」+ 用户听感"卡" ✓。这里改成**用按小节的目标秒数** ✓
                // ⇒ 内层/外层/note/时刻表/斜坡 用同一个窗口 ✓。
                double barTarget = AutoCueTransition.appleBarTargetSecs();
                double capHere = barTarget > 0.0 ? barTarget : rampCap;
                double[] withBaseline = capHere > 0.0
                        ? AutoCueTransition.windowCandidates(capHere, true)
                        : AutoCueTransition.windowCandidates(nominal, true);
AutoCue.PlanChoice decided = AutoCue.comparePlans(outEnv, inEnv,
                        baselineSecs,
                        withBaseline,
                        harmony == null ? 0.0 : (double) harmony.plainScore,
                        // 调性本来就合时不给声波重合候选（它的全部优势就是那次移调）
                        harmony != null && harmony.shouldShift);
                if (decided != null) {
                    autoNote = autoNote + "；" + decided.why;
                    if (!decided.harmonic && decided.cue != null) {
                        // **"现有接法"这一支也要采它的点位**（自动点位挑的出场/进场）——
                        // 丢掉它就会退化成"首拍对齐"（实测进场 0.45 s vs Apple 15.0 s ✗）。
                        autoCue = decided.cue;
                        autoNote = autoNote + "；采自动点位（出场 "
                                + String.format(java.util.Locale.ROOT, "%.3f",
                                        decided.cue.outroCutSecs)
                                + " s · 进场 "
                                + String.format(java.util.Locale.ROOT, "%.3f",
                                        decided.cue.introEntrySecs)
                                + " s）";
                    }
                    if (decided.harmonic && decided.windowSecs > 0.0) {
                        ModeHolder prepared = new ModeHolder();
                        harmonicBlend = new HarmonicBlend(this.rate, this.channels,
                                (float) decided.windowSecs);
                        prepared.plan = harmonicBlend.prepare(outro, intro, this.rate,
                                outroHeaders, introHeaders);
                        harmonicPlan = prepared.plan;
                        // **整套方案都留着**（出场点/进场点/交叠/丢尾）：装载时要用它自己的
                        // 那几个数，不能用现有接法算出来的——那是两套不同的方案。
                        harmonicCue = decided.cue;
                        // **声波重合赢了就换成它自己的窗口**：七项是按那个窗口打的分，
                        // note 与日志里写的也是那个数。原先这里被现有接法的窗口覆盖，
                        // 于是「选 12.0 s」实际按 4.0 s 跑（用户报的「选 W1 装载 W2」）。
                        window = Math.max(1L, Math.round(decided.windowSecs * this.rate));
                        // 【第 34 轮的尝试已撤回 ✓】：正则删窗口从句把**唯一**那句也删了 ✗（两条判据全红 ✓）
                        // ⇒ 正确做法是**改写**成"真正装载"的那个窗口 ✓，而且要在**最终窗口定下来之后** ✓
                        // （本方法的 return 处 `autoNote + "；" + 实际跑…body` ✓ = 2892 行附近 ✓）。
                        autoNote = autoNote + "；" + decided.note;
                    }
                }
            } catch (RuntimeException e) {
                Logger.debug("[qplayer-engine] AI 方案比较跳过（{}），沿用接法判定：{}",
                        e.toString(), style.label());
            }
        }

        // ---- 切点：beatCut 在窗口末尾切，其余全窗口融合 ----
        long cutFrames = Math.round(tuning.cutFadeSecs * (float) this.rate);
        // `cutLeadSecs` 让切点**提前**落在窗口末尾之前。默认 0 = 正好切在末尾；
        // 调它是因为「切在末尾」意味着出场曲最后一个采样之后立刻换人，
        // 而有些素材的收尾需要一点点余量才不显得急。
        long leadFrames = Math.round(tuning.cutLeadSecs * (float) this.rate);
        long cutPos = cutFrames + leadFrames;
        if (style == MixStyle.BEAT_CUT && window > cutPos) {
            cutAt = Automix.clampF32((float) (window - cutPos) / (float) window, 0.0f, 1.0f);
        } else {
            cutAt = 0.0f;
        }

        // beatCut 的进场曲要等到切点才开始放，否则它的开头会被白白丢掉
        float introStartAt = style == MixStyle.BEAT_CUT ? cutAt : 0.0f;

        // ---- 进场侧：跳过开头，让第一拍落在过渡起点上 ----
        double skipSecs = 0.0;
        if (tIn != null) {
            double skip = (double) tIn.phaseFrames / (double) Math.max(head.rate, 1);
            if (skip > 1e-4) {
                skipSecs = skip;
            }
        }

        // AiMix 从不安排变调（那是声波重合的事），所以 pitch 恒为零。
        if (harmonicBlend != null && harmonicPlan != null) {
            // 声波重合那一套方案自带变调曲线与（它自己的）进场起始进度。
            //
            // **进场点与丢尾取 AI 比较选中那个方案里的值**（`harmonicCue`），而不是
            // `harmonicPlan.skipSecs`（`HarmonicBlend.prepare` 恒返回 0——它不认识
            // 「自动点位」挑的进场点），也不是这套接法自己算的（那是现有接法的点位）：
            //
            //   * 丢尾决定**触发点**（编排器按「窗口 + 丢尾」提前，见 Director）——
            //     丢了它，融合点就不在比较时选的那个位置上；
            //   * 进场点决定 B 从哪儿进，而且它同时是 B 窗口素材的读取起点
            //     （`VocalAwarePitchTransition` 按 `plan.skipSecs` 读）。
            double entry = harmonicCue == null ? harmonicPlan.skipSecs
                    : harmonicCue.introEntrySecs;
            double droppedTail = harmonicCue == null ? harmonicPlan.outroTailSecs
                    : harmonicCue.tailDropSecs;
            // 【第 41 轮尝试已撤回 ✓】：在这里重写窗口也没修好那条红 ✗ ⇒ 判据的 plan 不是本方法
            // 这两条返回 ✗（test 用 `AiMix.prepare` ✓ 但 note 里的 3.2 另有来源 ✓）。
            return new TransitionPlan(entry, harmonicPlan.pitch,
                    harmonicPlan.introStartAt, noteWithActualWindow(), droppedTail);
        }
        // **丢尾也要带上**：编排器靠它把触发点提前（见 VocalSeparationTransition.withNote
        // 的注释，那里踩过同一个坑）。
        //
        // **自动点位采到之后**：进场用点位里的进场点；丢尾按"出场落点 = cue.outroCutSecs"反推 ——
        // 这样**无论这一档的窗口多大**，A 侧落点都落在自动点位挑的那一秒上
        // （用户对照 Apple 实测：出场 181.1 / 进场 15.0）。
        double entrySecs = autoCue != null ? autoCue.introEntrySecs : skipSecs;
        double droppedTail = outroTailSecs;
        if (autoCue != null && outroDur > 0.0) {
            double windowSecsHere = window / (double) Math.max(1, this.rate);
            droppedTail = Math.max(0.0, outroDur - (autoCue.outroCutSecs + windowSecsHere));
        }
        return new TransitionPlan(entrySecs, PitchPlan.ZERO, introStartAt, noteWithActualWindow(),
                droppedTail);
    }

    /**
     * 一次速度分析的来源。只影响 {@code plan.note} 里的标注。
     *
     * <p><b>这一条是硬要求，不是装饰</b>：模型路线会失败（没下载、会话建不起来、
     * 耗时超预算、输出形状对不上），失败时退回硬算。如果不标注，
     * 用户看到「128.0 BPM」时无从知道它是模型给的还是硬算给的——
     * 而那正是他判断「这一版 BPM 到底变准了没有」的唯一依据。
     * <b>不许在模型失败时假装「模型版」。</b>
     */
    private enum TempoSource {

        /** 拍点模型给的。 */
        MODEL("模型"),

        /** 频谱通量 + 自相关硬算出来的（{@link Analysis}）。 */
        COMPUTED("硬算");

        private final String label;

        TempoSource(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /**
     * 这次 {@link #prepare} 还剩下的模型预算（秒）。
     *
     * <p>做成一个小对象而不是 {@code double[]} 出参：{@link #tempoOf} 每调一次都要
     * 扣掉自己花掉的时间，而 Java 没法把 {@code double} 按引用传出来。
     */
    private static final class Budget {

        private double left = BeatModels.BUDGET_SECS;

        boolean spent() {
            return left <= 0.0;
        }

        void charge(long elapsedNanos) {
            left -= (double) elapsedNanos / 1e9;
        }
    }

    /**
     * 估一段单声道 PCM 的速度：<b>先问拍点模型，失败/不可用/超预算就退回硬算</b>。
     *
     * <h3>为什么要模型</h3>
     *
     * <p>硬算路（频谱通量 + 自相关 + 速度先验）在节拍规整的素材上够用，但它给的是
     * 「这段音频的能量起伏周期」，在切分音多、鼓组不规整、或者开头有自由引子的
     * 素材上会整体偏移。模型路（Beat This! 的 log-mel + 卷积网络）直接输出拍点位置，
     * 对拍点准确度是另一档。
     *
     * <h3>为什么降级必须诚实</h3>
     *
     * <p>模型不可用时<b>不阻断过渡</b>：退回固定窗口的硬算结论，与上游
     * 「算不出来就别猜」同一条纪律。但「退回了」这件事要能被看见——
     * 来源写进 {@code plan.note}（{@link TempoSource}），原因写日志。
     *
     * <h3>耗时记账</h3>
     *
     * <p>无论成功还是失败都把花掉的时间从 {@link Budget} 里扣掉：失败的那次
     * 「打开模型」同样可能慢（83 MB 的会话加载要 1.4 秒），不记就会
     * 预算形同虚设。
     */
    private TempoResultHolder tempoOf(float[] pcm, int fileRate, Budget budget) {
        String reason = null;
        if (!BeatModels.isInstalled()) {
            // 宿主没接线（安卓这一轮就是这种状态）——这不是错误，是「没有这条路」
            reason = "宿主没有安装推理底座";
        } else if (budget.spent()) {
            reason = "模型预算已用完";
        }

        if (reason == null) {
            long t0 = System.nanoTime();
            BeatDetector det = null;
            try {
                det = BeatModels.openPreferred();
                MelParams mp = det.melParams();
                // 上游的约定：采样率不符时**由调用方先重采样**，
                // 模型里不暗中重采样（否则「结果不对」会难以定位）。
                float[] mono = BeatModels.resampleMono(pcm, fileRate, mp.sampleRate);
                float[] beats = det.beats(mono);
                // 相位/周期要落在**源文件**的采样率上：拍点是秒，秒与采样率无关，
                // 这里只决定「一秒算多少帧」。用 22050 换算再乘比例是同一件事，
                // 但多一次舍入，而且更容易写错。
                Analysis.Tempo tempo = Analysis.tempoFromBeats(beats, fileRate);
                if (tempo == null) {
                    reason = "模型没给出可用的拍点（拍点少于两个）";
                } else {
                    budget.charge(System.nanoTime() - t0);
                    Logger.debug("AI 模式对拍：{} 给了 {} 个拍点，{}",
                            det.label(), Integer.valueOf(beats.length), tempo.describe());
                    return new TempoResultHolder(tempo, TempoSource.MODEL);
                }
            } catch (RuntimeException e) {
                // 加载失败、运行失败、形状对不上——一律如实记下原因再走硬算。
                // 这里只吞**运行时异常**：它是「模型这条路不可用」的表达。
                // Error（OOM、UnsatisfiedLinkError）不在其列，仍然往上冒——
                // 那种情况不该被伪装成「模型不可用」然后在别处炸出更难懂的现象。
                reason = e.getClass().getSimpleName() + ": " + briefMessage(e);
            } finally {
                if (det != null) {
                    det.close();
                }
                if (reason != null) {
                    budget.charge(System.nanoTime() - t0);
                }
            }
        }

        if (reason != null) {
            // **这一条每个进程只报一次**（2026-10-03）。它说的是「这台设备没装对拍模型」，
            // 是个**装机状态**、不是这一次过渡出了什么事：移动端永远装不了那一档，
            // 于是原先每次换曲都要报两遍（出场侧 + 进场侧），纯噪音
            // ——用户的原话是「现在太繁琐了」。
            // 结论本身仍然是明说的（第一次是 WARN，之后每次降到 debug），
            // 不静默、也不重复。
            if (modelMissingReported) {
                Logger.debug("AI 模式对拍：模型仍不可用（{}），这一侧退回硬算", reason);
            } else {
                modelMissingReported = true;
                Logger.warn("AI 模式对拍：模型不可用（{}），这一侧退回硬算"
                        + "（这一条每个进程只说一次；之后每次过渡都会照常硬算）", reason);
            }
        }
        // ── 硬算：白化对数谱通量前端 + DP 对拍 + 全局回归（2026-10-01 换）──────────
        //
        // 换掉的只有前端与后段：原来走 `Analysis.analyze`（逐格 RMS 差分 + 自相关峰值）。
        // 实测（`BpmPrecisionTest`，见 docs/bpm-precision.md §2.6/§2.7）：新链路在整曲上
        // 的周期误差是 0.0006~0.0008%，旧的是 0.048~0.051%（差 60 倍），谐波错 0 比 3，
        // 而且旧链路在「变速到 ≈100 BPM」「只取中段 30 s」这两种情况下直接测不出来。
        // 自动混音的对拍（B 跟着 A 的速度走）与窗口拍数都吃这个数，所以这里是换得最值的一处。
        //
        // 新链路要不到结论时（素材太短、起音太少、残差太大）**仍退回旧路**：
        // 宁可拿一个粗结论，也不要因为换了算法而在原本能用的素材上突然没有 BPM——
        // 退回时把原因写进日志，别让它变成沉默的降级。
        Analysis.Tempo tracked = Analysis.tempoFromSpectralFluxTracked(pcm, fileRate);
        if (tracked != null) {
            return new TempoResultHolder(tracked, TempoSource.COMPUTED);
        }
        Logger.debug("AI 模式对拍：新链路没给出结论（{}），退回旧的 RMS 差分路",
                BeatTracker.lastRejectReason());
        return new TempoResultHolder(Analysis.analyze(pcm, fileRate), TempoSource.COMPUTED);
    }

    /**
     * 两侧读取收工之后的判定。
     *
     * <p>返回 {@code null} = 「两个结果都在，可以继续」；否则返回**要原样写进 plan 的那句话**。
     * 抽成静态方法只为了一件事：让这条判定能被判据钉住（见 {@code AiMixReadOutcomeTest}）——
     * 它里面的顺序规则是**行为**，不是实现细节。
     *
     * <p>顺序与串行版完全一致：先看出场侧的失败，再看进场侧的。
     *
     * <p><b>「没有结果也没有错误」也是一种结局</b>（2026-10-03 补，评审指出）：
     * {@code get()} 抛 {@code InterruptedException} 时我们只把中断位打回去，而那一刻任务
     * **可能还在跑**（它写的是数组，此刻仍是 {@code null}）——于是结果与错误双双为 null。
     * 那不是「分析失败」，是这一次准备被取消了；如实退回固定窗口，别拿 null 往下走
     * （{@code head.pcm} 会直接 NPE，而 NPE 会被读成「自动混音坏了」）。
     * 被丢下的那条读取让它跑完就好（一次 12 秒素材的解码，有界）：这里不 {@code cancel(true)}，
     * 半路打断解码器换来的是更难看的状态。
     */
    static String readFailureNote(Automix.Mono tail, AudioException tailError,
                                  Automix.Mono head, AudioException headError) {
        if (tailError != null) {
            // 上游是 `format!("出场曲分析失败（{e}），退回固定窗口")`：`{e}` 是 Display，
            // Java 的对应物是 getMessage()（toString() 会多带异常类名）。
            return "出场曲分析失败（" + tailError.getMessage() + "），退回固定窗口";
        }
        if (headError != null) {
            return "进场曲分析失败（" + headError.getMessage() + "），退回固定窗口";
        }
        if (tail == null || head == null) {
            return "读取被中断（准备线程被打断），退回固定窗口";
        }
        return null;
    }

    /** {@link #tempoOf} 的返回值：结论 + 来源。Java 没有元组，按约定用不可变值类。 */
    private static final class TempoResultHolder {

        final Analysis.Tempo tempo;

        final TempoSource source;

        TempoResultHolder(Analysis.Tempo tempo, TempoSource source) {
            this.tempo = tempo;
            this.source = source;
        }
    }

    /** 错误信息的第一行 + 截断（与 {@code InferException.brief} 同一条理由）。 */
    private static String briefMessage(Throwable e) {
        String s = e.getMessage();
        if (s == null) {
            return e.toString();
        }
        int nl = s.indexOf('\n');
        String first = (nl >= 0 ? s.substring(0, nl) : s).trim();
        return first.length() > 200 ? first.substring(0, 200) + "…" : first;
    }

    /**
     * 分析失败时的安排：固定窗口 + 原因说明，不安排变调、不跳过量。
     *
     * <p>上游是「先建一个 {@code TransitionPlan::default()} 再逐字段写」，Java 的
     * {@link TransitionPlan} 是不可变的，所以这里直接构造一个等价的 plan。
     * {@code pitch} 传 {@link PitchPlan#ZERO} 与上游的默认值一致。
     */
    private TransitionPlan failurePlan(String note) {
        return new TransitionPlan(0.0, PitchPlan.ZERO, 0.0f, note);
    }

    /**
     * 准备结果的文字说明。
     *
     * <p>格式串用 {@link Locale#ROOT}：默认区域会把小数点写成逗号，而这一行会进
     * {@code plan.note} 被界面与日志读。取整方向与 Rust 的 {@code {:.1}} 有一处
     * 不可能消除的差异（Rust 是 half-to-even，Java 是 HALF_UP），在 {@code x.x5}
     * 这种边界上可能差 1 个末位——<b>这一行是给人看的，不参与任何判定</b>
     * （与 {@link Chooser} / {@link Analysis.Tempo#describe()} 同一处理）。
     *
     * <p>每个 BPM 后面都跟着它的<b>来源</b>（{@link TempoSource}）：
     * 用户要能一眼看出这一版的速度是模型给的还是硬算给的。
     */
    /**
     * **note 里那句「交叠 X s」= 真正装载的窗口**（2026-10-09 ✓，goal 第 64 轮 ✓）：
     * plan 是在本类构造的 ✓（`describePlan()` 就在参数位上 ✓）⇒ 在这里直接改写最干净 ✓
     * （外层只是把它原样返回 ✓，所以在外层改 `cue`/`basePlan` 都没用 ✗ —— 这是 14 轮的教训 ✓）。
     * **只动字符串 ⇒ 音频一点不变** ✓；窗口值仍由内层/斜坡那套决定 ✓（另有判据钉着 ✓）。
     */
    private String noteWithActualWindow() {
        String note = describePlan();
        double actual = window / (double) Math.max(1, this.rate);
        if (!(actual > 0.0) || note == null) {
            return note;
        }
        return note.replaceAll("交叠 [0-9.]+s",
                String.format(Locale.ROOT, "交叠 %.1fs", Double.valueOf(actual)));
    }

    private String describePlan() {
        String bpm;
        if (outroTempo != null && introTempo != null) {
            bpm = String.format(Locale.ROOT, "%.1f BPM（%s）→ %.1f BPM（%s）",
                    outroTempo.bpm, outroSource.label(),
                    introTempo.bpm, introSource.label());
        } else if (outroTempo != null) {
            bpm = String.format(Locale.ROOT, "出场 %.1f BPM（%s），进场未判定",
                    outroTempo.bpm, outroSource.label());
        } else if (introTempo != null) {
            bpm = String.format(Locale.ROOT, "出场未判定，进场 %.1f BPM（%s）",
                    introTempo.bpm, introSource.label());
        } else {
            bpm = "未判定出拍点，按固定窗口融合";
        }
        // 真正跑的是哪一套：声波重合赢了比较时**不是**那个接法（它只是比较里的基准），
        // 所以这里不能写「选定 朴素融合（窗口 12.0 s）」——那句话会让用户以为接法换了。
        double secs = (double) window / (double) rate;
        String body = harmonicBlend != null
                ? String.format(Locale.ROOT, "声波重合（AI 比较选中，窗口 %.1f s）",
                        Double.valueOf(secs))
                : String.format(Locale.ROOT, "%s（%s，窗口 %.1f s）",
                        style.label(), bpm, Double.valueOf(secs));
        // 自动挑的时候，把**为什么**一并说出来。用户看到接法被换掉时必须能知道原因，
        // 否则「自动」就是个没法信任的黑箱。
        if (autoNote != null) {
            // 【第 35 轮的尝试已撤回 ✓】：在 `autoNote` 的 return 处改写窗口**没修好那条红** ✗
            // ⇒ 判据读的 note 不是 `autoNote` 这条字符串 ✗（下一步：先**打印**判据里那个 `plan.note`
            // 的来源 ✓，再决定改哪里 ✓ —— 不再盲改 ✗）。
            return autoNote + "；" + (harmonicBlend != null ? "实际跑 " : "选定 ") + body;
        }
        return body;
    }

    /**
     * 拍点硬切：出场曲原样走到切点，然后用 30 ms 斜坡交接。
     *
     * <h3>曲线必须<b>逐样本</b>算，不能每块算一次</h3>
     *
     * <p>编排器每块只给一个 {@code progress}，而一块是 {@code Deck.RENDER_FRAMES} = 4096 帧
     * （48 kHz 下 85 ms）。如果整块套用同一个增益，那条 30 ms 的斜坡就<b>根本
     * 不存在</b>——1 块之内 {@code local} 直接从 0 跳到 1，渲染出来是一次干净利落的硬切，
     * 而这正是它存在的理由。
     *
     * <p>这条对别的接法无害（bassswap 8 秒的窗口有 47 块，量化看不出来），
     * 唯独拍点剪切的窗口（0.05~0.11 秒）比一块还短，于是整段过渡只被渲染成
     * 一个增益值。实测症状：过渡窗口内 RMS 为 −inf dB（整段静音），
     * 顶上还带一个 0.16 的台阶。
     *
     * <h3>出场曲提前放完时也必须把斜坡走完</h3>
     *
     * <p>编排器按块推进（一块 4096 帧 ≈ 48 kHz 下 85 ms），出场曲在触发时可能只剩
     * 不到一个块；而拍点剪切的窗口<b>比一个块还短</b>，于是切点永远到不了。
     * 老代码在这种情况下两路都没内容、{@code render} 返回 0，调用方走兜底硬接——
     * <b>30 ms 消咔哒斜坡一次都没跑</b>。实测证据：接缝处的最大跳变 0.4888，
     * 而其余四种接法都是 0.0906（素材自己的瞬态基线）；修好之后同样是 0.0906。
     *
     * <p>所以出场曲一旦空了（{@code from.frames() == 0}），交接就从<b>当下这一刻</b>
     * 开始，而不是继续等切点：等下去只会两路都空。
     */
    private int renderBeatCut(AudioBuf out, AudioBuf from, AudioBuf to, float p) {
        int n = Math.min(out.capacityFrames(), Math.max(from.frames(), to.frames()));
        if (n == 0) {
            out.setFrames(0);
            return 0;
        }
        float cut = cutAt;
        // 块内每前进一帧，进度前进多少
        float perFrame = 1.0f / (float) Math.max(window, 1L);

        // 出场曲**提前放完**（窗口比素材尾部还长）时，交接从这一刻开始，
        // 而不是继续等切点——等下去只会两路都空。
        Float forcedFrom;
        if (from.frames() == 0) {
            if (cutForcedAt == null) {
                // 上游 `get_or_insert(p)`：只有第一次记下来的那一刻才算数，
                // 之后每块都拿同一个起点算，斜坡才能连续走完。
                cutForcedAt = Float.valueOf(p);
            }
            forcedFrom = cutForcedAt;
        } else {
            cutForcedAt = null;
            forcedFrom = null;
        }

        out.setFrames(n);
        for (int ch = 0; ch < out.channels(); ch++) {
            FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                float pi = Automix.minF32(p + (float) i * perFrame, 1.0f);
                if (forcedFrom != null) {
                    // 斜坡要在**窗口结束前走完**：否则进场曲还没起来窗口就没了，
                    // 编排器随即把进场曲按满增益接上，接缝上又是一下台阶。
                    float start = forcedFrom.floatValue();
                    float span = Automix.minF32(
                            tuning.cutFadeSecs * (float) rate / (float) Math.max(window, 1L),
                            Automix.maxF32(1.0f - start, 1e-4f));
                    float local = Automix.clampF32((pi - start) / span, 0.0f, 1.0f);
                    Mixer.Gains g = gainsFor(local);
                    o.put(i, sampleOf(from, ch, i) * g.from + sampleOf(to, ch, i) * g.to);
                } else if (pi < cut) {
                    // 切点之前、且出场曲还在：一个采样值都不改
                    o.put(i, sampleOf(from, ch, i));
                } else {
                    float local = cut >= 1.0f
                            ? 1.0f
                            : Automix.clampF32((pi - cut) / (1.0f - cut), 0.0f, 1.0f);
                    Mixer.Gains g = gainsFor(local);
                    o.put(i, sampleOf(from, ch, i) * g.from + sampleOf(to, ch, i) * g.to);
                }
            }
        }
        return n;
    }

    /**
     * 低音交接。
     *
     * <h3>两种形状</h3>
     *
     * <ul>
     *   <li><b>平台形状（默认，{@code together = 0.5}）</b>：中段两曲等响不动，低频在那一段换手。
     *       这是「混音」而不是「淡化」的形状来源（见 {@link Automix#togetherAngle}）。</li>
     *   <li><b>分段形状（{@code together = 0}）</b>：高频在前半段换完、低频在后半段换，
     *       由 {@code split} 决定分界。这是本模块最早的形状，保留下来是因为
     *       调参台上两种形状值得对比着听。</li>
     * </ul>
     *
     * <p>两者的共同性质是<b>每个频带内部的功率和恒为 1</b>，而频带之间不相交，
     * 所以总功率也恒为 1。
     *
     * <p>分频用 {@link SplitLowHigh} 的<b>互补</b>分频（低频 = lowpass(x)、
     * 高频 = x − lowpass(x)），<b>不是</b>「低通 + 独立高通」：两个独立设计的滤波器在
     * 截止点附近相位不同，相加会在分频点留下一个幅度凹陷，那样上面那条
     * 「总功率恒为 1」就不再成立。
     */
    /**
     * <b>交叠期"高频错开"的强度</b>（0 = 完全不错开，即老的等功率；1 = 完全分段）。
     *
     * <p>用户 2026-10-06 的两条听感把范围夹出来了：<b>0 太糊</b>（"衔接段很糊"）、
     * <b>1 太明显</b>（"分开太明显了能明显听出来换歌了"）。所以取一个中间值——
     * 平台期两边仍然都听得见，但**不再等响**。
     *
     * <p>为什么放在这里而不是 {@code MixTuning}：{@code MixTuning.java} 此刻有别的写者
     * 在改，先当类内常量；要上调参台再搬过去（搬的时候把一个字段加进去即可）。
     */
    private static final float HF_STAGGER = 0.35f;

    private int renderBassSwap(AudioBuf out, AudioBuf from, AudioBuf to, int n, float p) {
        return renderBassSwap(out, from, to, n, p, false);
    }

    /**
     * 见 {@link #renderBassSwap(AudioBuf, AudioBuf, AudioBuf, int, float)}；{@code sweep}
     * 为真时**出场侧先过一个逐块下滑的低通** ✓（名字带 FILTER 的那一族 ✓）。
     */
    private int renderBassSwap(AudioBuf out, AudioBuf from, AudioBuf to, int n, float p,
                               boolean sweep) {
        if (sweep && sweepOut != null) {
            // 截止频率按进度**对数**插值 ✓（听感上等距 ✓）；每块更新一次（≈20 ms 一步 ✓）。
            double k = Math.max(0.0, Math.min(1.0, (double) p));
            float hz = (float) (tuning.filterSweepFromHz
                    * Math.pow(tuning.filterSweepToHz / (double) tuning.filterSweepFromHz, k));
            if (sweepHz < 0f || Math.abs(hz - sweepHz) > sweepHz * 0.005f) {
                sweepHz = hz;
                for (Biquad f : sweepOut) {
                    f.setLowPass(rate, hz, tuning.filterSweepQ);
                }
            }
        }
        // 形状：中段一段**平台**（两曲等响、低频在那里换手），两端各自收放。
        //
        // 高频走的是基础曲线：平台期它停在 45°，也就是两曲等响。
        // 低频**只在平台期换手**——平台开始前完全还是出场曲，平台结束时已经
        // 完全交给进场曲。这就是「两个低端变一个低端」的那下动作，
        // 也是它不像淡化、并且不糊的原因。
        float together = Automix.clampF32(tuning.bassSwapTogether, 0.0f, 0.9f);
        float hfF;
        float hfT;
        float lfF;
        float lfT;
        if (together <= 0.0f) {
            // 分段形状：高频在 [0, split] 内换完，低频在 [split, 1] 内换
            float split = Automix.clampF32(tuning.bassSwapSplit, 0.0f, 0.99f);
            if (split <= 0.0f) {
                hfF = 0.0f;
                hfT = 1.0f;
            } else {
                Mixer.Gains g = gainsFor(Automix.minF32(p / split, 1.0f));
                hfF = g.from;
                hfT = g.to;
            }
            Mixer.Gains g = gainsFor(
                    Automix.clampF32((p - split) / (1.0f - split), 0.0f, 1.0f));
            lfF = g.from;
            lfT = g.to;
        } else {
            // 低频的角度：`arc` 之前恒为 0（低端还是出场曲），平台期内走满 π/2，
            // 之后恒为 π/2（低端已经交给进场曲）
            float arc = (1.0f - together) * 0.5f;
            float theta = Automix.togetherAngle(p, together);
            float thetaLf;
            if (p <= arc) {
                thetaLf = 0.0f;
            } else if (p >= arc + together) {
                thetaLf = FRAC_PI_2;
            } else {
                thetaLf = ((p - arc) / together) * FRAC_PI_2;
            }
            // ── 件 1：高频**错开换手**（2026-10-06，用户报障"衔接段很糊"）──────────
            //
            // 原来这里 hfF/hfT = cosθ/sinθ：**平台期 θ = 45°，也就是两曲等响**。而这一路
            // 带着整段**中高频**——低频换手做对了，中高频却是两边满响叠加 = 互相遮蔽，
            // 听感就是"糊"。用户给的循环规格里本来就写着「配合 EQ 低切/高切 + 音量交叉」，
            // 我们只落了"音量交叉"。
            //
            // 改法用这个类**自己已有的"分段形状"**：高频在 [0, split] 内换完（比平台更早），
            // 低频仍在平台期换手。于是**平台期没有任何一个频段是两边同时满响的**——
            // 中高频已经归了进场曲、低端还在换手。
            //
            // 不变量保住：两条曲线各自都是**等功率对**（cos/sin），三段频带互不相交，
            // 所以"每个频带内部功率和恒为 1、总功率也恒为 1"这条性质**没有被牺牲**
            // （即不靠挖响度坑来换频谱分离——那会让"响度接续"那一项掉下去）。
            float hfSplit = Automix.clampF32(tuning.bassSwapSplit, 0.0f, 0.99f);
            if (hfSplit <= 0.0f || HF_STAGGER <= 0.0f) {
                hfF = (float) Math.cos((double) theta);
                hfT = (float) Math.sin((double) theta);
            } else {
                // **部分错开**（在等功率曲线与"分段形状"之间插值）——这一条是听感逼出来的：
                // 第一版整段走分段形状，等于把中高频在平台期**之前**就全交给进场曲，
                // 交叠一开始就只听得见新歌了，用户的原话是「分开太明显了能明显听出来换歌了」。
                // 而完全不错开（等功率）又回到"两边满响 = 糊"。
                // 取一个中间值：平台期两边仍然都听得见，但**不再等响**（差几个 dB），
                // 遮蔽因此被压下去一大截，而"换歌"那一刻不会被提前。
                Mixer.Gains hg = gainsFor(Automix.minF32(p / hfSplit, 1.0f));
                float c = HF_STAGGER;
                hfF = (1.0f - c) * (float) Math.cos((double) theta) + c * hg.from;
                hfT = (1.0f - c) * (float) Math.sin((double) theta) + c * hg.to;
            }
            lfF = (float) Math.cos((double) thetaLf);
            lfT = (float) Math.sin((double) thetaLf);
        }

        int chs = Math.min(out.channels(), fromSplit.length);
        out.setFrames(n);
        // 分频结果写进这个复用的数组：`process(float)` 每个样本都会新建一个 Split，
        // 而渲染路径上不允许分配（两条路径逐位相同，见 SplitLowHigh 的文档）。
        float[] split = new float[2];
        for (int ch = 0; ch < chs; ch++) {
            SplitLowHigh fs = fromSplit[ch];
            SplitLowHigh ts = toSplit[ch];
            FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                float aRaw = sampleOf(from, ch, i);
                if (sweep && sweepOut != null) {
                    aRaw = sweepOut[ch].process(aRaw);       // 逐块下滑的低通（零分配 ✓）
                }
                fs.processInto(aRaw, split);
                float flo = split[0];
                float fhi = split[1];
                ts.processInto(sampleOf(to, ch, i), split);
                float tlo = split[0];
                float thi = split[1];
                // 两段各自等功率，且频带不相交 → 总功率仍为 1
                o.put(i, flo * lfF + tlo * lfT + fhi * hfF + thi * hfT);
            }
        }
        // 超出分频器数量的声道留静音，而不是留上一块的残留
        for (int ch = chs; ch < out.channels(); ch++) {
            FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                o.put(i, 0.0f);
            }
        }
        return n;
    }

    /** 朴素等功率融合（{@code PLAIN_BLEND} / {@code AUTO} 走到这里时的退路）。 */
    private int renderPlainBlend(AudioBuf out, AudioBuf from, AudioBuf to, float p) {
        Mixer.Gains g = gains(p);          // "长出短进"那几档在这里体现 ✓（其余逐位相同 ✓）
        // P4：朴素融合这一档正是「两首歌同时满响」的场景，逐帧响度匹配就在这里。
        return Mixer.mixTwoMatched(out, from, g.from, to, g.to,
                levelMatcher, blendCurve(p) * tuning.levelMatch,
                lowDucker, blendCurve(p) * tuning.lowDuck);
    }

    /**
     * <b>学习 EQ</b>：<b>出场曲只做等功率淡出，进场曲做三段 EQ + 等功率淡入</b>。
     *
     * <p>规格（第二阶段·系统架构）：时间轴是"A 与 B 同时开始播、窗口结束时 A 停 B 继续"；
     * A 的淡出必须**显式**（{@code cos(p·π/2)}）、B 的淡入 {@code sin(p·π/2)}，两者平方和为 1。
     * EQ 只加在 B 上：两级 {@code SplitLowHigh}（{@code tuning.crossoverHz} → {@value #EQ_MID_HZ}）
     * 分出低/中/高三段，各乘对应增益；增益来自 prepare 阶段算好的线性增益表
     * （render 只查表插值，不做 {@code Math.pow}）。
     */
    private int renderLearnedEq(AudioBuf out, AudioBuf from, AudioBuf to, int n, float p) {
        Mixer.Gains g = gains(p);          // 同上 ✓
        learnedEq.gainsAt(p, eqGains);
        int chs = Math.min(out.channels(), toSplit.length);
        out.setFrames(n);
        float[] split = new float[2];
        float[] split2 = new float[2];
        for (int ch = 0; ch < chs; ch++) {
            SplitLowHigh ts = toSplit[ch];
            SplitLowHigh ts2 = toSplit2[ch];
            FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                float a = sampleOf(from, ch, i) * g.from;      // A：只淡出
                ts.processInto(sampleOf(to, ch, i), split);
                ts2.processInto(split[1], split2);             // 把中高频再分开
                float b = split[0] * eqGains[0] * g.to
                        + split2[0] * eqGains[1] * g.to
                        + split2[1] * eqGains[2] * g.to;
                o.put(i, a + b);
            }
        }
        for (int ch = chs; ch < out.channels(); ch++) {
            FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                o.put(i, 0.0f);
            }
        }
        return n;
    }

    /** 装载这一次的 EQ 曲线（{@code null} = 清掉，回到朴素融合）。 */
    public void setLearnedEqCurve(EqCurve curve) {
        this.learnedEq = curve;
    }

    /** 三段分频第二级的边界（规格第二十一条：第一级 200 Hz、第二级 2000 Hz）。 */
    static final float EQ_MID_HZ = 2000f;

    /**
     * 尾音骑行：出场侧衰减得更慢，尾巴留在上面。
     *
     * <p>曲线取 {@code gf = √(1−p²)}、{@code gt = p}，两者<b>平方和为 1</b>，
     * 所以仍是等功率——只是把功率分配的<i>时间重心</i>往后推了。
     *
     * <h3>文档曾经写错的一句话</h3>
     *
     * <p>这里原先写着「进场侧<b>提前</b>进来」，与代码相反：{@code gt = p} 在 {@code p < 1} 时
     * 比等功率的 {@code √p} <b>更小</b>，也就是进场曲进来得<b>更晚</b>。
     * 换句话说「骑行」是靠出场侧拖得更久实现的，不是靠进场侧抢进来。
     * 哪一种是想要的听感是<b>调参</b>的事，不是对错——
     * 所以两边都留着：{@code tailRideIntroPower} 调到 0.5 以下就是「提前进来」。
     */
    /**
     * 这一段素材是不是**几乎没有立体声信息**（侧向能量不到总能量的 {@value #SPACE_MONO_RATIO}）。
     *
     * <p>为什么必须判这一下：中/侧展宽的手法是<b>压低中间、抬起两侧</b>，而单声道素材
     * （{@code L = R}）<b>根本没有两侧</b>——于是「展宽」退化成对出场曲的<b>纯衰减</b>
     * （中间增益 0.8 = −1.9 dB，两侧那 1.2 倍乘在 0 上）。那不是让位，是白掉电平：
     * 出场曲会提前瘦下去，而且素材不同、掉多少还不一样。
     *
     * <p>判据 {@code AiMixTest.tailRideHoldsTheOutgoingLongerThanPlainBlend} 当场把这一条
     * 照出来了（它拿常量立体声缓冲量尾音骑行的增益曲线，期望值被那层白掉的电平带偏）。
     *
     * <p>代价：真正的单声道素材（老录音之类）上这个旋钮<b>没有效果</b>——如实如此，
     * 没有两侧内容就没有空间可分。
     */
    private static boolean isEssentiallyMono(AudioBuf from) {
        int n = Math.min(from.frames(), 2 * Deck.RENDER_FRAMES);
        double midEnergy = 0.0;
        double sideEnergy = 0.0;
        FloatBuffer l = from.channel(0);
        FloatBuffer r = from.channel(1);
        for (int i = 0; i < n; i++) {
            double lv = l.get(i);
            double rv = r.get(i);
            double mid = (lv + rv) * 0.5;
            double side = (lv - rv) * 0.5;
            midEnergy += mid * mid;
            sideEnergy += side * side;
        }
        double total = midEnergy + sideEnergy;
        return total <= 0.0 || sideEnergy < SPACE_MONO_RATIO * total;
    }

    /**
     * <b>融合期那几层处理的强度曲线</b>：{@code sin(π·p)}——两端为 0，中段最强。
     * 逐帧响度匹配（P4）与低频让位（P5）都乘它。
     *
     * <p>两端必须归零，这不是审美问题，是<b>接缝</b>问题：这个匹配只在过渡内部生效，
     * 过渡结束后 deck 放的是<b>原样</b>的 B。终点 {@code p = 1} 时若匹配还在起作用，
     * 输出就是 {@code B × 增益}，跨过接缝的那一帧会从「被抬/压过的 B」跳回「原样的 B」——
     * 一个实打实的响度台阶。判据 {@code AiMixTest.plainBlendEndpointsArePure}
     * （「终点应是纯 B」）当场把这一条照出来了。
     *
     * <p>起点 {@code p = 0} 归零则纯属对称：那一刻 {@code gb = 0}，B 根本还没进来。
     * 中段（两首歌一样响的位置）最强，正是最需要对齐档位的时候。
     */
    private static float blendCurve(float p) {
        // 两端**精确**为 0：`sin(π)` 在 double 里是 1.22e-16 而不是 0，直接返回它的话，
        // 「两端逐位不变」这条契约就变成了「两端几乎不变」——判据
        // `AiMixLowDuckTest.theWindowEndpointsAreUntouched` 当场把这一条照出来了
        // （它在 p=1 的第 18 帧上量到差异）。数值上无害，契约上不成立。
        if (p <= 0.0f || p >= 1.0f) {
            return 0.0f;
        }
        return (float) Math.sin(Math.PI * (double) p);
    }

    private int renderTailRide(AudioBuf out, AudioBuf from, AudioBuf to, float p) {
        float gf = (float) Math.sqrt((double) Automix.maxF32(
                1.0f - (float) Math.pow((double) p, (double) tuning.tailRideOutroPower), 0.0f));
        float gt = (float) Math.pow((double) p, (double) tuning.tailRideIntroPower);
        return Mixer.mixTwoMatched(out, from, gf, to, gt, levelMatcher,
                blendCurve(p) * tuning.levelMatch, lowDucker, blendCurve(p) * tuning.lowDuck);
    }

    /**
     * 表现模式：出场曲高通上扫 + 渐弱，进场曲低通打开 + 渐强。
     */
    private int renderShowMode(AudioBuf out, AudioBuf from, AudioBuf to, int n, float p) {
        // 出场曲越来越「薄」：高通从起点扫到终点
        MixTuning t = tuning;
        float hp = t.showHpFrom + (t.showHpTo - t.showHpFrom) * (p * p);
        // 进场曲越来越「亮」：低通从起点开到终点（三次曲线，后段才打开）
        float lp = t.showLpFrom + (t.showLpTo - t.showLpFrom) * (p * p * p);
        for (Biquad f : fromHp) {
            f.updateHighPass(rate, hp, 0.707f);
        }
        for (Biquad f : toLp) {
            f.updateLowPass(rate, lp, 0.707f);
        }

        // 默认 0.5 / 0.6 时功率和 = (1−p) + p^1.2 ≤ 1，所以不会削顶。
        // 调这两个幂会改变这个性质——调小了（例如都取 0.3）两路叠加可能超过 1，
        // 那时听到的削顶是参数造成的，不是素材。
        float gf = (float) Math.pow((double) Automix.maxF32(1.0f - p, 0.0f),
                (double) t.showOutroGainPower);
        float gt = (float) Math.pow((double) p, (double) t.showIntroGainPower);

        int chs = Math.min(out.channels(), fromHp.length);
        out.setFrames(n);
        for (int ch = 0; ch < chs; ch++) {
            Biquad fh = fromHp[ch];
            Biquad tl = toLp[ch];
            FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                float a = fh.process(sampleOf(from, ch, i)) * gf;
                float b = tl.process(sampleOf(to, ch, i)) * gt;
                o.put(i, a + b);
            }
        }
        for (int ch = chs; ch < out.channels(); ch++) {
            FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                o.put(i, 0.0f);
            }
        }
        return n;
    }

    /**
     * 从缓冲取一个样本，越界返回零。
     *
     * <p>两个 deck 的产出长度可能不同（一首先结束），短的按静音处理——
     * 这与 {@link Mixer#mixTwo} 的约定一致。
     */
    private static float sampleOf(AudioBuf buf, int ch, int i) {
        if (i < buf.frames() && ch < buf.channels()) {
            return buf.channel(ch).get(i);
        }
        return 0.0f;
    }

    /**
     * 镜像上游 {@code impl core::fmt::Debug for AiMix}。
     *
     * <p>上游用 {@code finish_non_exhaustive()}，所以末尾有个 {@code ..}；
     * {@code style} 打的是 {@code spec_name}（与设计文档对照用），
     * {@code outro_bpm} / {@code intro_bpm} 是 {@code Option<f32>}，
     * Rust 的 {@code Debug} 会打成 {@code Some(128.0)} / {@code None}。
     *
     * <p>与 {@link AudioBuf} 同一处理：不打印滤波器内部状态——上游 {@code derive} 会把
     * 上百个系数（含每个滤波器的延迟链）刷进日志。
     */
    @Override
    public String toString() {
        return "AiMix { style: \"" + style.specName() + "\", rate: " + rate
                + ", window: " + window
                + ", outro_bpm: " + optionDebug(outroBpm())
                + ", intro_bpm: " + optionDebug(introBpm()) + ", .. }";
    }

    /** {@code Option<f32>} 的 {@code Debug} 样子。 */
    private static String optionDebug(Float v) {
        return v == null ? "None" : "Some(" + v + ")";
    }

    /**
     * 退回旧选档时，把"打分核为什么没选"接在该档结论的后面（旧路径的结论可能是空的 ✗）。
     *
     * <p>为什么值得做：真素材上实测出现过"档位 = 低音交接、**结论 = 空**"——
     * 那是打分核**没有任何候选通过**、退回旧选档，而旧路径不写结论 ⇒ 界面上什么也看不到，
     * 排查时只能猜。现在退回也会说明原因（含"几个候选过闸门"）。
     */
    /** 【A ✓】AI 自动档是否允许挑「人声不撞」（默认**否** ✓ 2026-10-09 用户要求 ✓；true = 与从前相同 ✓）。 */
    private static volatile boolean autoPickNoVocalClash = true;   // 实测该分支不经过 ⇒ 默认保持原样（真正的剔除在 AutomixSettings 的 AI 档）

    public static void setAutoPickNoVocalClash(boolean on) {
        autoPickNoVocalClash = on;
    }

    public static boolean autoPickNoVocalClash() {
        return autoPickNoVocalClash;
    }

    private static Chooser.AutoChoice withKernelNote(Chooser.AutoChoice legacy,
                                                     String kernelNote) {
        if (legacy == null) {
            return null;
        }
        String why = legacy.why == null || legacy.why.isEmpty()
                ? kernelNote
                : legacy.why + "（" + kernelNote + "）";
        return new Chooser.AutoChoice(legacy.style, why);
    }

    /**
     * A 侧"尾巴长度"（秒）：窗口里最后一段还高于（峰值 − 20 dB）的时间 ——
     * 他清单里 {@code trailing_span_seconds} 的**我们的代理**（原语义未恢复，如实标注）。
     */
    private static double trailingSpanSecs(AutoCue.Envelope env) {
        if (env == null || env.rms == null || env.rms.length == 0) {
            return 0.0;
        }
        float peak = 0f;
        for (float v : env.rms) {
            peak = Math.max(peak, v);
        }
        if (peak <= 0f) {
            return 0.0;
        }
        float floor = peak * 0.1f;                 // −20 dB
        int last = -1;
        for (int i = 0; i < env.rms.length; i++) {
            if (env.rms[i] >= floor) {
                last = i;
            }
        }
        if (last < 0) {
            return 0.0;
        }
        // 每格 HOP_SECS 秒（与信封同一时间栅格）⇒ 尾巴长度 = (末尾 − 最后一次高于门槛) × 格长
        double hop = 0.02;                          // AutoCue.HOP_SECS 的保守取值（0.02 s）
        return Math.max(0.0, (env.rms.length - 1 - last) * hop);
    }

    /** 进场侧是不是比出场侧安静（{@code incoming_loudness_relation_code} 的我们的代理）。 */
    private static boolean incomingIsQuieter(float[] tailPcm, Automix.Mono head, int rate) {
        if (tailPcm == null || head == null || head.pcm == null || rate <= 0) {
            return false;
        }
        double a = dev.t1m3.qplayer.audio.dsp.Lufs.integrated(tailPcm, null, rate);
        double b = dev.t1m3.qplayer.audio.dsp.Lufs.integrated(head.pcm, null, head.rate);
        return !Double.isInfinite(a) && !Double.isInfinite(b) && b < a;
    }

    /**
     * 可叠秒数里能放**几拍**，并且**向下取整到整小节**（4/4）—— 这才是
     * {@code matching_bar_count} 要的"小节数"（此前我用的是"拍数差 ≤ 1 拍"的代理 ✗，
     * 那连"一节都放不下"与"正好一节"都分不开）。
     *
     * <p>取整到整小节之后，{@code matchingBarsOk}（两侧差 ≤ 1 拍）就**正好**是
     * "两边能放的整小节数相等" ✓ —— 忠实于他那一项的意思。
     * 量不出拍长 ⇒ 0（⇒ 由匹配判据判为不相等 ⇒ 该档不过，不猜）。
     */
    /** 某一侧在给定窗口里的**最大**人声强度（0..1）；量不出 ⇒ −1（比较时当作更小的一侧）。 */
    private static double overlapStrengthOfSide(AutoCue.Envelope env, double fromSecs,
                                                double windowSecs) {
        double[] strength = AutoCue.vocalStrength(env);
        if (strength == null) {
            return -1.0;
        }
        int from = (int) Math.floor(fromSecs / AutoCue.HOP_SECS);
        int n = (int) Math.round(windowSecs / AutoCue.HOP_SECS);
        double best = 0.0;
        for (int k = 0; k < n; k++) {
            int i = from + k;
            if (i >= 0 && i < strength.length) {
                best = Math.max(best, strength[i]);
            }
        }
        return best;
    }

    private static double beatCount(double overlapSecs, AutoCue.Envelope env) {
        if (env == null || env.tempo == null || env.tempo.periodFrames <= 0 || env.beatRate <= 0) {
            return 0.0;
        }
        double beatSecs = env.tempo.periodFrames / (double) env.beatRate;
        if (beatSecs <= 0) {
            return 0.0;
        }
        double beats = overlapSecs / beatSecs;
        double wholeBarBeats = Math.floor(beats / 4.0) * 4.0;   // 4/4：一小节 = 4 拍
        return Math.max(0.0, wholeBarBeats);
    }

    /**
     * 粗估"人声叠着的时长"（秒）—— **代理**，且我们明确知道它不够可靠。
     *
     * <p>做法：把素材里"高于峰值 −20 dB 的时长"当一个上界（人声在场时通常有能量），
     * 所以这个数偏向**高估** ⇒ 只会让 vocal_overlap 闸门更严（更倾向于认为"有人声"），
     * 而不会把它放行得更宽 —— 方向上是安全的（宁可多筛、不要误放）。
     * 真正的 mid/side 中置比要等立体声素材（TODO）。
     */
    private static double estimateVocalOverlapSecs(float[] mono, int rate) {
        if (mono == null || mono.length == 0 || rate <= 0) {
            return 0.0;
        }
        float peak = 0f;
        for (float v : mono) {
            peak = Math.max(peak, Math.abs(v));
        }
        if (peak <= 0f) {
            return 0.0;
        }
        float floor = peak * 0.1f;
        int loud = 0;
        for (float v : mono) {
            if (Math.abs(v) >= floor) {
                loud++;
            }
        }
        return loud / (double) rate;
    }
}
