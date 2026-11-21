package dev.t1m3.qplayer.audio.mix;

import java.util.ArrayList;

/**
 * 调参旋钮。
 *
 * <h3>为什么要有这个类</h3>
 *
 * <p>ADR-002 把「Automix 听感调参」列为<strong>唯一替不掉的环节</strong>：
 * 「folia 的音频路径经过九轮盲听才定稿。<strong>我听不了，这一环必须你参与。</strong>」
 *
 * <p>而在这之前，每个旋钮都是一个 {@code const}。「换个数字再听一遍」意味着
 * 改代码 → 重编译 → 重新打包 → 再听。一轮盲听的成本高到没人愿意做第二遍，
 * 于是调参这件事事实上做不起来。
 *
 * <p>所以把旋钮收进一个类：<strong>默认值与原来逐个 {@code const} 完全一致</strong>
 * （改这个文件不该改变任何现有听感），但可以从命令行覆盖。
 * 一串「键=值」即可，例如把低音交接的分频点挪到 250 Hz、
 * 表现模式的高通终点挪到 6 kHz：{@code bassswap.crossover=250,showmode.hp_to=6000}。
 *
 * <p>调参的人只需要听，不需要碰代码。听到满意的组合之后，把
 * {@link #toLine()} 打印出来的那一行原样贴回去即可复现。
 *
 * <h3>哪些旋钮值得有</h3>
 *
 * <p>只放<strong>会改变听感</strong>的量。纯粹的工程常数（分析窗长度、解码缓冲）不放进来——
 * 那些不该由耳朵决定，改坏了也听不出来。
 *
 * <h3>环境变量</h3>
 *
 * <p>上游在<strong>宿主</strong>里读 {@code SONALIS_MIX_TUNING} 再交给 {@code parse}
 * （{@code tuning.rs} 自己不碰环境变量），这里照做：本类只提供
 * {@link #parse(String)} / {@link #check()} / {@link #toLine()}，
 * 由宿主决定从哪里取值。QPlayer 侧的宿主接入点等 automix 移植时再接。
 *
 * <h3>与上游的类型差异（按 docs/audio-port.md 记录）</h3>
 *
 * <ul>
 *   <li>{@code Result<Self, TuningError>} → 抛 {@link TuningException}。
 *       {@code MixTuning::default()} → {@link #MixTuning()} 构造器 + {@link #defaults()}：
 *       Java 没有 {@code Default} trait，也不能把「默认值」做成共享单例——
 *       这个类是<strong>可变的</strong>（上游的字段是 {@code pub}，{@code parse} 就是逐个赋值），
 *       共享一份会被谁改一下都不知道。</li>
 *   <li>{@code #[derive(Clone, Copy)]} → {@link #MixTuning(MixTuning)} 复制构造。
 *       这里有个<strong>语义差别要记住</strong>：Rust 的 {@code Copy} 让「把 tuning 传给别人」
 *       天然是复制，而 Java 传的是引用。<strong>可变的共享配置对象</strong>是最容易被踩的那种坑
 *       （甲改了旋钮，乙那边跟着变），所以需要独立副本时显式调复制构造。</li>
 *   <li>{@code f32}/{@code f64} → {@code float}/{@code double}，字面量带 {@code f}；
 *       {@code max_window_fraction} 在上游就是 {@code f64}，这里保持 {@code double}，
 *       并且解析时<strong>先按 f32 解析再拓宽</strong>（上游是 {@code f()? as f64}）。</li>
 *   <li>浮点解析<strong>不</strong>直接用 {@code Float.parseFloat}/{@code Integer.parseInt}：
 *       它们接受的语言比 Rust 的 {@code FromStr} 宽（见 {@link #parseF32}），
 *       宽出来的部分正是「用户以为改了、实际没改」的入口。</li>
 *   <li>{@code to_line()} 的浮点格式按<strong>上游 {@code Display} 的样子</strong>输出
 *       （Rust 对 {@code 1.0f32} 打印 {@code 1}），所以这里把 Java
 *       {@code Float.toString} 那个多余的 {@code .0} 去掉（见 {@link #displayF32}）；
 *       {@link #toString()} 则镜像 {@code #[derive(Debug)]}（Rust 的 Debug 会打印 {@code 1.0}），
 *       两者不要混用。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::tuning::MixTuning}。
 */
public final class MixTuning {

    // ------------------------------------------------------------------
    // 上游 automix.rs 的 pub const —— 本类默认值的来源
    //
    // 上游把「听感常数」都写成 pub 是有理由的（automix.rs 那段注释）：
    // 「这些『听感常数』全都是 pub 的，因为它们是 crate::tuning::MixTuning 的默认值——
    // 调参要能在不重编译的前提下改，而默认值必须仍然只有这一份。」
    //
    // Sonalis 侧 automix 还没移植到 QPlayer，所以这些常量暂时寄放在本类里。
    // **Automix 移植时必须引用这里的常量**（而不是另抄一份字面量），
    // 否则「默认值只有一份」这条就断了，而它正是 defaults_match_the_shipped_constants
    // 那条测试要锁住的东西。
    // ------------------------------------------------------------------

    /**
     * {@code bassSwap} 的分频点（Hz）。
     *
     * <p>200 Hz 是 DJ 混音的常规取法：低于它的是「能量」，高于它的是「辨识度」。
     */
    public static final float BASS_CROSSOVER_HZ = 200.0f;

    /** {@code bassSwap} 里高频换完的进度点，低频从这一点开始换（只在分段形状下用）。 */
    public static final float BASS_SWAP_SPLIT = 0.5f;

    /**
     * {@code bassSwap} 中段「平台」占整段的比例。
     *
     * <p>0.5 = 平台占一半：两曲等响地叠着，低频在这段时间里换手。
     * <strong>这是听感上的默认形状</strong>，「像混音」而不是「像淡化」就靠它。
     */
    public static final float BASS_SWAP_TOGETHER = 0.5f;

    /**
     * {@code beatCut} 的消咔哒斜坡（秒）。
     *
     * <p>硬切在采样级不连续处会产生咔哒。30 ms 短到听不出是淡化，又足够消除跳变。
     */
    public static final float CUT_FADE_SECS = 0.03f;

    /**
     * 过渡窗口最多能占出场曲长度的多少。
     *
     * <p>见上游 {@code AiMix::prepare} 里那段注释：这个上界是实测踩出来的。
     * 定成一半是个保守但稳妥的界——过渡本来就该是几秒的事。
     *
     * <p>上游是 {@code f64}（不是 f32），这里跟着用 {@code double}。
     */
    /**
     * 窗口占剩余时长的比例上限。**2026-10-06 第一阶段工作五：0.5 → 0.7**（用户：
     * 「只保留技术上不可能的限制，去掉审美上保守的限制」）。
     *
     * <p>为什么写成 {@code (double) 0.7f} 而不是 {@code 0.7}：调参台的导出是 **float32**，
     * 而 0.5 在二进制里精确、0.7 不是——直接写 double 的 {@code 0.7}（= 0.69999999999999996）
     * 会让"toLine → parse 往返必须相等"这条判据红（parse 出来是 0.699999988079071）。
     * 写成 f32 再抬宽就与导出、解析三方一致。**这不是迁就判据，而是让"导出精度"这件事
     * 只有一个定义**：调参台能存的数就是 f32。
     */
    public static final double MAX_WINDOW_FRACTION = (double) 0.7f;

    /**
     * <b>滤波扫</b>（名字里带 FILTER 的那几档）：出场侧低通的起点/终点截止频率（Hz）。
     *
     * <p>"**长高到低**"这个说法就是它：截止频率从高往低走 ⇒ 出场曲一路变暗 ✓
     * （这是接法名字的意思 ✓；在那之前我们这一族跑的其实是固定分频的 `bassSwap` ✗ ——
     * 名字说滤波、听感是分带交接 ✓，用户 2026-10-07 报了"怎么感觉不对" ✓）。
     *
     * <p>**设为 0（或起点 ≤ 终点）⇒ 这一族与加它之前逐位相同** ✓（可以精确回退 ✓）。
     * 对数插值 ✓；系数每块更新一次（20 ms 一步，听不出台阶 ✓）；滤波状态跨块保留 ✓
     * （新建滤波器会把状态清零 ⇒ 每块开头一个瞬态 ✗）。
     */
    public static final float FILTER_SWEEP_FROM_HZ = 18000f;
    /** 见 {@link #FILTER_SWEEP_FROM_HZ}。 */
    public static final float FILTER_SWEEP_TO_HZ = 400f;

    /** 滤波扫的 Q（0.707 = 不抬峰 ✓）。 */
    public static final float FILTER_SWEEP_Q = 0.707f;

    /**
     * <b>混响</b>（名字里带 REVERB 的那 5 档）：送音量 / 湿声量 / RT60 / 高频衰减。
     *
     * <p>为什么要有它：那 5 档以前听着跟朴素淡化一样 ✗（仓库里**一个混响实现都没有** ✗）——
     * 用户 2026-10-07：「全部让他们名副其实」✓。这里送的是**出场侧原始样本** ✓
     * （A 淡出的同时，它的尾巴在混响里继续响 ✓）。
     *
     * <p>**{@code reverbSend <= 0 或 reverbWet <= 0} ⇒ 那 5 档与加它之前逐位相同** ✓（可精确回退 ✓）。
     */
    public static final float REVERB_SEND = 0.35f;
    /** 见 {@link #REVERB_SEND}。 */
    public static final float REVERB_WET = 0.5f;
    /** 见 {@link #REVERB_SEND}。 */
    public static final float REVERB_DECAY_SECS = 1.8f;
    /** 见 {@link #REVERB_SEND}（越大人声尾巴越闷 ✓）。 */
    public static final float REVERB_DAMPING = 0.35f;

    /**
     * <b>跟拍延迟</b>（名字里带 DELAY 的那 3 档）：送音量 / 湿声量 / **延时几拍** / 反馈量。
     *
     * <p>那 3 档以前跑的是 `tailRide` / `learnedEq` ✗（仓库里**一个延迟实现都没有** ✗）。
     * 延时长度 = {@code delayBeats × 拍长} ✓ —— 拍长从**出场曲的 tempo** 现算 ✓（跟拍 ✓）。
     * {@code delayBeats = 0.75} 是附点八分音符 ✓（流行/嘻哈里最常见的那一档 ✓）。
     *
     * <p>**{@code delaySend <= 0 或 delayWet <= 0} ⇒ 那 3 档与加它之前逐位相同** ✓。
     */
    public static final float DELAY_SEND = 0.4f;
    /** 见 {@link #DELAY_SEND}。 */
    public static final float DELAY_WET = 0.5f;
    /** 见 {@link #DELAY_SEND}。 */
    public static final float DELAY_BEATS = 0.75f;
    /** 见 {@link #DELAY_SEND}。 */
    public static final float DELAY_FEEDBACK = 0.45f;

    /**
     * <b>长出短进</b>（名字里带 LONG_OUT_SHORT_IN 的那几档，以及 FILTER_DYNAMIC_IN）：
     * <b>进场侧淡入在窗口的前百分之几就完成</b>（出场侧仍走满整窗 ✓）。
     *
     * <p>名字说的就是这件事 ✓：A 慢慢地走 ✓、B 麻利地进来 ✓。两条曲线各自仍是**等功率形状**
     * （cos / sin ✓），只是时间常数不同 ✓。
     *
     * <p>**1.0（或 ≥1）⇒ 与对称淡化逐位相同** ✓ —— 也就是"关掉"这一条的写法 ✓。
     */
    public static final float SHORT_IN_FRACTION = 0.5f;

    /**
     * <b>"窗口很短就做确认重复"</b>（用户 2026-10-07 的口径 ✓）—— <b>默认开</b> ✓。
     *
     * <p>用户原话：「循环是给融合窗口很短很短的确认重复后真有用才用的，不能有人声，重复要不违和」✓
     * ⇒ 触发条件是<b>窗口很短</b>（不足 1 小节 ✓），<b>对任何档都成立</b> ✓（音乐上的需要 ✓），
     * 闸门一个不放松 ✓（无人声 / 小节对齐 / 自相似 / 拍稳 ✓）。
     *
     * <p>⚠️⚠️ <b>仍然默认关</b> ✗（2026-10-07 实测）：门槛虽然换成了"窗口 &lt; 1 小节" ✓，
     * 但代码里的 {@code barSecs} **不是出场曲那一把尺子** ✓ ——
     * 参考对的窗口 2.5128 s 照样进了这一档 ✗ ⇒ 产物指纹从 {@code d3bff00c…} 变成 {@code f2895a8f…} ✗、
     * 用户**已经验收过的落点**（A 181.385 / B 14.936 ✓）被挪走 ✓。
     *
     * <p>⇒ 这是一个**需要用户拍板的取舍** ✗（"窗口短就重复一遍坐实" ✓ 与 "落点已经验收、不许动" ✓
     * 直接冲突 ✓），我不替他决定 ✗ ⇒ 默认关 ✓，机制与门槛都留着 ✓，他点头就打开 ✓。
     */
    public static final boolean STYLE_LOOP_ENABLED = true;

    /**
     * <b>"直接过渡效果不好"的门（选点代价）</b>：这一次选出来的方案代价高于它 ⇒ 才算"效果不好" ✓
     * （用户 2026-10-07：「融合窗口低于 2 s **且**直接过渡效果不好才算真缺时间」✓）。
     *
     * <p>代价是 {@code Cue.cost = 1 − 接缝总评} ✓（越高越差 ✓，量纲与既有的选点一致 ✓）。
     * 两条**同时**成立才做确认重复 ✓：窗口 &lt; 2 s ✓ **且** cost &gt; 这个门 ✓。
     * 设为 1.0 ⇒ 等于关掉这一条（任何方案都算"效果还行" ✓）。
     *
     * <p>⚠️ 这个阈值是**初值** ✓（按"总评 &lt; 0.5 就算勉强"取的中庸值 ✓）——
     * 真实素材上的手感要靠听感校准 ✓，所以做成旋钮 ✓（你可直接拧 ✓）。
     */
    public static final float LOOP_CONFIRM_MIN_COST = 0.5f;

    // ⚠️ **2026-10-07 试过加"交叠时长绝对上限 8 s"** ✗ —— 已撤：
    //   ① 它会夹掉**用户明确设的 12 s** ✗（"用户设的值永远是上界"是仓库铁律 ✓，
    //      `AutoCuePlanTest` 钉着它 ✓）；
    //   ② 它违反"基准窗口 = 拍数 × 实际 BPM"✓（`AiMixBaselineWindowTest` ✓）
    //      与"对齐只该拉长窗口"✓（`AiMixTest` ✓）。
    // ⇒ 那种"一刀切"的上限是错的刀口 ✗；窗口本来就该由**拍数**说话 ✓。
    //   38.4 s 的病根**不在这一层** ✓（实测内层窗口是 8.18 s ✓）：真正把长窗口写回去的是
    //   {@code AutoCueTransition} 里**路径/循环**那两支的 `autoWindow = windowTotal`
    //   （见 `prepareFromPath` 与 `prepareLoopOutgoing`）✗ ⇒ 下一轮从那里查 ✓。

    /**
     * <b>逐帧响度匹配的默认强度</b>（P4）：默认<b>开</b>（1 = 全匹配）。
     *
     * <p>与 P2 的空间展宽（默认 0 = 关）方向相反，这是有意的、也是有理由的：
     * 空间展宽<b>改变作品的空间形象</b>（是一种观点，得先听过再决定要不要），
     * 而响度匹配只是把两个母带<b>档位对齐</b>（是纠正）——「一首把另一首埋了」本来就是
     * 用户报的那个「糊」。两者都可逆：设 0 就与没有这个功能逐位相同，而且现在都能在
     * 宿主设置项 {@code automixMixTuning} 里调。
     */
    public static final float DEFAULT_LEVEL_MATCH = 1.0f;

    /** <b>过渡期变速的默认强度</b>：全开（见 {@link #tempoStretch}）。 */
    public static final float DEFAULT_TEMPO_STRETCH = 1.0f;

    /**
     * <b>低频让位的默认强度</b>（P5）：全开。
     *
     * <p>为什么不给个「一半」的保守值：这个效果本身<b>有界</b>（最多 −6 dB，而且只在两侧
     * 都有低频时才起作用），方向也正是用户报的那个「糊」；不像空间展宽那样会明显改变
     * 作品的空间形象。要减弱在代码里调 {@code lowDuck}。
     */
    public static final float DEFAULT_LOW_DUCK = 1.0f;

    /**
     * <b>空间让位的默认宽度</b>（P2）：<b>一半</b>（0.5）。用户 2026-10-02 听过之后定的。
     *
     * <p>为什么不给满宽（1.0）：那是中间 −40% / 两侧 +40%，相关性一路掉到 −0.691，
     * 听感上「出场曲被扯到两边」很明显、人声也会往后退——用户试过之后挑的是 0.5。
     * 为什么不是 0（关）：这一手治的正是用户报的那个「糊」，而事实证明它开一半最好听；
     * 计划里写「默认关」的前提是「旋钮摸不到、不知道好不好听」，现在两个前提都没了
     * （能听、能调）。
     *
     * <p>想改就改：宿主设置项 {@code automixMixTuning}
     * （{@link dev.t1m3.qplayer.settings.SettingsCatalog#AUTOMIX_MIX_TUNING_KEY}），
     * {@code width=1} 拉满、{@code width=0} 完全关掉（那时与没有这个功能逐位相同），
     * 也可以和别的旋钮一起调，例如 {@code width=0.3,low_duck=0}。
     */
    public static final float DEFAULT_SPACE_WIDTH = 0.5f;

    /** {@code tailRide} 的出场侧衰减曲线幂：{@code gf = (1 − p^power) ^ 0.5}。越大衰减越慢。 */
    public static final float TAIL_RIDE_OUTRO_POWER = 2.0f;

    /**
     * {@code tailRide} 的进场侧上升曲线幂：{@code gt = p ^ power}。
     *
     * <p><strong>{@code 1.0} 比等功率（{@code p^0.5}）更晚进来</strong>——这是当前实现的实际听感，
     * 而文档一度写成「进场侧提前进来」，两者相反。想提前就调到 0.5 以下。
     */
    public static final float TAIL_RIDE_INTRO_POWER = 1.0f;

    /** {@code showMode} 出场曲高通扫频的起止（Hz）：从几乎全通扫到只剩高频。 */
    public static final float SHOW_HP_FROM = 20.0f;

    public static final float SHOW_HP_TO = 4000.0f;

    /** {@code showMode} 进场曲低通扫频的起止（Hz）：从闷到全开。 */
    public static final float SHOW_LP_FROM = 300.0f;

    public static final float SHOW_LP_TO = 18000.0f;

    /** {@code showMode} 两侧的增益曲线幂：{@code gf = (1 − p) ^ power}、{@code gt = p ^ power}。 */
    public static final float SHOW_OUTRO_GAIN_POWER = 0.5f;

    public static final float SHOW_INTRO_GAIN_POWER = 0.6f;

    /**
     * 上游 {@code spec.split([',', ';', ' ', '\n', '\t'])} 的分隔符集合。
     *
     * <p><strong>{@code '\r'} 故意不在里面</strong>（上游也没有）：Windows 风格的行尾靠
     * 后面那一步 {@code v.trim()} 消化掉，加进来就与上游分叉了。
     */
    private static final String SEPARATORS = "[,; \n\t]";

    // ------------------------------------------------------------------
    // 字段（上游的 pub 字段，可写——parse 就是逐个赋值）
    // ------------------------------------------------------------------

    // ---- 全局 ----

    /**
     * 各接法窗口按「拍数」的缩放。{@code 1.0} 是规格值。
     *
     * <p>规格里各接法的拍数（8/16/24/32）是<strong>音乐上的</strong>长度，
     * 但「多长才自然」跟曲风有关：Techno 的 32 拍和民谣的 32 拍不是一回事。
     */
    public float windowScale;

    /**
     * 窗口上限：最多占出场曲长度的多少。
     *
     * <p>定成一半是实测踩出来的界（见 {@code AiMix::prepare} 那段注释），
     * 一般不该动；留着是因为短曲子上它才是真正的约束。
     */
    public double maxWindowFraction;

    // ---- 低音交接（bassSwap）----

    /** 分频点（Hz）。低于它是「能量」，高于它是「辨识度」。 */
    public float crossoverHz;

    /**
     * 高频换完的进度点（0..1）。低频<strong>从这一点开始</strong>换。
     *
     * <p>只在 {@code bass_swap_together = 0}（分段形状）时起作用。
     */
    public float bassSwapSplit;

    /**
     * 低音交接中段「平台」占整段的比例。
     *
     * <p>平台期两曲各停在 {@code cos(π/4)}（等响）不动，<strong>低频就在这一段换手</strong>。
     * 这是「像混音」而不是「像淡化」的关键——把过渡拉长换不来这个听感，
     * 只有形状能。{@code 0} 退回最早那种「高频前半段、低频后半段」的分段形状。
     *
     * <p>取舍：平台越长，两首歌同时可听的时间越久，对调性/节奏差异也越敏感。
     */
    public float bassSwapTogether;

    // ---- 拍点剪切（beatCut）----

    /** 消咔哒斜坡时长（秒）。 */
    public float cutFadeSecs;

    /**
     * <b>空间让位宽度</b>（P2，0..1，默认 {@value #DEFAULT_SPACE_WIDTH}）：重叠期把出场曲（A）
     * 推向两侧，进场曲留在中间——两首歌在**空间**上分开，这是「不糊」最有效的一手。
     *
     * <p>0 = 完全不碰（逐位不变）；1 = 峰值处中间 −40%、两侧 +40%。
     * 曲线沿窗口取 {@code width × sin(π·progress)}：两端为 0（与淡化同步淡入淡出，
     * 不会有台阶），中段最强。
     *
     * <p><b>默认 {@value #DEFAULT_SPACE_WIDTH}（一半，用户听过之后定的）</b>，可调：
     * 宿主设置项 {@link dev.t1m3.qplayer.settings.SettingsCatalog#AUTOMIX_MIX_TUNING_KEY}
     * 里 {@code width=1} 拉满、{@code width=0} 关掉。设 0 时与没有这个功能<b>逐位相同</b>。
     */
    public float spaceWidth;

    /**
     * <b>逐帧响度匹配强度</b>（P4，0..1，默认 1 = 全匹配）：融合期让进场的 B 在响度上
     * 贴着出场的 A 走，A 与 B 谁的母带响就按谁的档位对齐——「一首把另一首埋了」是
     * 「皱在一起很糊」里最直白的一半。
     *
     * <p>0 = 完全不碰（逐位不变），1 = 匹配到 ±6 dB 上限为止。时间常数取慢
     * （响度 0.4 s、增益 0.25 s），所以它<b>不是</b> AGC：B 自己的强弱起伏会保留下来，
     * 匹配的只是它的平均档位，也因此不会有泵感与台阶。见 {@link LevelMatcher}。
     */
    public float levelMatch;

    /**
     * <b>低频让位强度</b>（P5，0..1，默认 1 = 全让）：融合期把两侧低频的<b>总量</b>压到
     * 「不超过其中较响的一首」，中高频原样相加。
     *
     * <p>两首歌的底鼓/贝斯同相叠起来实测是单首的 <b>2 倍（+6 dB）</b>——「皱在一起很糊」
     * 里最硬的一半就是它。0 = 完全不碰（逐位不变）；1 = 两侧一样响时各让一半
     * （低频增益 0.5，实测叠唱时的低频从 2.0 倍压到 1.17 倍）。
     *
     * <p>默认开（与 {@link #spaceWidth} 的默认关不同：低频让位是<b>纠正</b>——两首歌的
     * 低频叠起来本来就不该是 +6 dB，而且效果有界）。宿主设置项
     * {@link dev.t1m3.qplayer.settings.SettingsCatalog#AUTOMIX_MIX_TUNING_KEY}
     * 里可以关掉或调弱。见 {@link LowDucker}。
     */
    public float lowDuck;

    /**
     * <b>过渡期变速强度</b>（2026-10-03，0..1，默认 {@value #DEFAULT_TEMPO_STRETCH}）。
     *
     * <p>用有界离线 WSOLA（{@link TempoBridge}）把<b>出场曲的窗口素材</b>在保调的前提下变速，
     * 让重叠期两首歌的鼓点不再越滑越远。
     *
     * <p><b>2026-10-06 起是「时间渐变的斜坡」而不是恒定比例</b>：速率在整窗里从 1.0000
     * 单调降到 {@link TempoRamp#endSpeedFor} 给的目标（参考对上 {@code 1.0000 → 0.9626×}、
     * 平均 0.9813×），走 {@link TempoBridge#stretchRamp}（一次 OLA，段与段之间没有缝）。
     * 依据是取证实测（{@code tools/automix-forensics/}）：Apple 在同一对曲子上的出场曲是
     * <b>渐进</b>降速、保音高（分数半音扫描 +0.00），而恒定比例在窗口起点是一个速率阶跃。
     * 同一条规则还给出<b>窗口的上界</b>（完成一次 beatmatch 的最短 ramp 长度，只减不增）。
     *
     * <p>什么时候<b>不</b>降速（一律退回原样，与从前逐位相同）：两侧 BPM 置信度不足 0.8、
     * 速度比不在 0.92~1.08 内、比值几乎等于 1（没必要动）、<b>两侧拍长差本来就落在听阈
     * （{@link TempoRamp#TEMPO_JND}）以内</b>、素材不够、或这个旋钮为 0。
     * <b>旋钮为 0 时窗口也不许动</b>（"关掉的功能要与没有它一致"）。
     *
     * <p>为什么默认开：用户要的是「无感、听不出换歌」，而鼓点错位是这件事最直接的破绽；
     * 而项目原先「不做时间拉伸」的方针，其前提是「窗口短到一两拍」，用户听过之后选的是长窗——
     * 前提已经不成立（见 {@code docs/automix-overview.md} §6.4）。设 0 就完全回到从前。
     */
    public float tempoStretch;

    /** 切点相对窗口末尾的提前量（秒）。0 = 正好切在窗口末尾。 */
    public float cutLeadSecs;

    // ---- 尾音骑行（tailRide）----

    /** 出场侧衰减曲线：{@code gf = (1 − p^power) ^ 0.5}。越大衰减越慢。 */
    public float tailRideOutroPower;

    /**
     * 进场侧上升曲线：{@code gt = p ^ power}。
     *
     * <p>{@code 1.0} <strong>比等功率更晚</strong>进来（等功率是 {@code p^0.5}），这是当前实现；
     * 想要「进场侧提前进来」就调到 {@code 0.5} 以下。
     */
    public float tailRideIntroPower;

    // ---- 表现模式（showMode）----

    /** 出场曲高通扫频的起点与终点（Hz）。 */
    /** 见 {@link #FILTER_SWEEP_FROM_HZ}（0 = 这一族与加它之前逐位相同 ✓）。 */
    public float filterSweepFromHz;
    /** 见 {@link #FILTER_SWEEP_FROM_HZ}。 */
    public float filterSweepToHz;
    /** 滤波扫的 Q（见 {@link #FILTER_SWEEP_Q}）。 */
    public float filterSweepQ;

    /** 见 {@link #REVERB_SEND}。 */
    /** 见 {@link #DELAY_SEND}。 */
    /** 见 {@link #SHORT_IN_FRACTION}。 */
    public float shortInFraction;

    /** 见 {@link #LOOP_CONFIRM_MIN_COST}。 */
    public float loopConfirmMinCost;

    /**
     * 见 {@link #STYLE_LOOP_ENABLED}。
     *
     * <p>⚠️ **这一个暂时不进 {@code toLine/parse/equals}** ✓（别的旋钮都进 ✓，判据
     * `MixTuningCompletenessTest` 只遍历 float/double/int ⇒ 不会照到它 ✗，所以在这里显式说明 ✓）：
     * 它是一个**默认关的临时开关** ✓，等"循环嵌进既选窗口"做完就会被那个正解取代 ✓ ——
     * 现在把它写进用户设置，等于把一个要删的键留给用户 ✗。
     */
    public boolean styleLoopEnabled;

    /** 见 {@link #DELAY_SEND}。 */
    public float delaySend;
    /** 见 {@link #DELAY_SEND}。 */
    public float delayWet;
    /** 见 {@link #DELAY_SEND}。 */
    public float delayBeats;
    /** 见 {@link #DELAY_SEND}。 */
    public float delayFeedback;

    /** 见 {@link #REVERB_SEND}。 */
    public float reverbSend;
    /** 见 {@link #REVERB_SEND}。 */
    public float reverbWet;
    /** 见 {@link #REVERB_SEND}。 */
    public float reverbDecaySecs;
    /** 见 {@link #REVERB_SEND}。 */
    public float reverbDamping;

    public float showHpFrom;

    public float showHpTo;

    /** 进场曲低通扫频的起点与终点（Hz）。 */
    public float showLpFrom;

    public float showLpTo;

    /** 出场侧增益曲线：{@code gf = (1 − p) ^ power}。 */
    public float showOutroGainPower;

    /** 进场侧增益曲线：{@code gt = p ^ power}。 */
    public float showIntroGainPower;

    // ---- 声波重合（harmonic）----

    /**
     * 一帧被采纳所需的最低和声清晰度。
     *
     * <p><strong>这条最影响「到底会不会转调」</strong>：调高会拒掉更多真实素材
     * （混响、鼓多的段落清晰度天然低），于是多数时候不表态。
     */
    public float harmonyClarityMin;

    /** 转调后至少要达到的相似度。 */
    public float harmonyMatchMinScore;

    /** 转调相对不转调至少要提升多少才值得动。 */
    public float harmonyMinImprovement;

    /** 允许搜索的半音范围（±）。 */
    public int harmonyMaxShift;

    // ---- 自动选接法（MixStyle.AUTO）----

    /**
     * 速度差达到多少（相对比例）就算「节奏对不上」。
     *
     * <p>用相对值而不是绝对 BPM：120 与 124 差 3.3%，60 与 62 也差 3.3%，
     * 两者在听感上是同一件事（拍与拍之间漂移的比例相同）。
     */
    public float autoTempoConflict;

    /**
     * 调性相似度达到多少就算「两边本来就合」。
     *
     * <p>到了这一档就<strong>不该做任何滤波</strong>——没有东西需要遮，滤波只会白掉能量。
     */
    public float autoKeySame;

    // ---- 自动挑接法的素材门槛（2026-10-07 从 Chooser 常量搬来；**只挪位置、不动判定**）----
    /** 低频冲突（lowA×lowB 归一化重叠）到多少才算"真的撞上了"。 */
    public float lowClashGate;
    /** 能量峰显著度到多少才算"拐点站得住"（学习EQ 的曲线绑在 drop 上）。 */
    public float dropGate;
    /** 可叠秒数低于它 ⇒ 判"没东西可叠"（拍点剪切那一档）。 */
    public float minOverlapSecs;
    /** 学习EQ 要求的最短窗口（太短的话曲线还没铺开就结束了）。 */
    public float learnedEqMinWindowSecs;
    /** A 尾段衰减慢于这个斜率（dB/s）才算"长尾"（尾音骑行那一档）。 */
    public float tailLongDbPerSec;
    /** B 头段起跳低于它才算"留白进"（尾音骑行那一档）。 */
    public float headCalmGate;

    /**
     * 一次过渡的全部可调量，取<strong>当前已实现的听感</strong>。
     *
     * <p>这就是上游的 {@code impl Default for MixTuning}——所有字段的默认值都等于
     * 原先写死在 {@code automix} / {@code harmonic} 里的那些常量。
     * 赋值顺序与上游的 {@code Self { .. }} 逐行一致，方便对照。
     */
    public MixTuning() {
        // **保持 1.0**（2026-10-07）：想按用户听感"窗口长一点"时**不能改这里** ——
        // 判据 MixTuningTest.defaultsMatchTheShippedConstants 明确守着
        // 「缩放默认必须是 1，否则等于悄悄改了规格」。窗口时长是**规格常量**，
        // 用户的偏好应当走**设置项的默认值 / 界面滑块**（那条路不碰规格）。
        this.windowScale = 1.0f;
        this.maxWindowFraction = MAX_WINDOW_FRACTION;

        this.crossoverHz = BASS_CROSSOVER_HZ;
        this.bassSwapSplit = BASS_SWAP_SPLIT;
        this.bassSwapTogether = BASS_SWAP_TOGETHER;

        this.cutFadeSecs = CUT_FADE_SECS;
        this.cutLeadSecs = 0.0f;

        this.tailRideOutroPower = TAIL_RIDE_OUTRO_POWER;
        this.tailRideIntroPower = TAIL_RIDE_INTRO_POWER;

        this.shortInFraction = SHORT_IN_FRACTION;
        this.loopConfirmMinCost = LOOP_CONFIRM_MIN_COST;
        this.styleLoopEnabled = STYLE_LOOP_ENABLED;
        this.delaySend = DELAY_SEND;
        this.delayWet = DELAY_WET;
        this.delayBeats = DELAY_BEATS;
        this.delayFeedback = DELAY_FEEDBACK;
        this.reverbSend = REVERB_SEND;
        this.reverbWet = REVERB_WET;
        this.reverbDecaySecs = REVERB_DECAY_SECS;
        this.reverbDamping = REVERB_DAMPING;
        this.filterSweepFromHz = FILTER_SWEEP_FROM_HZ;
        this.filterSweepToHz = FILTER_SWEEP_TO_HZ;
        this.filterSweepQ = FILTER_SWEEP_Q;
        this.showHpFrom = SHOW_HP_FROM;
        this.showHpTo = SHOW_HP_TO;
        this.showLpFrom = SHOW_LP_FROM;
        this.showLpTo = SHOW_LP_TO;
        this.showOutroGainPower = SHOW_OUTRO_GAIN_POWER;
        this.showIntroGainPower = SHOW_INTRO_GAIN_POWER;

        // 这三条的常量在 harmonic 模块里，也同样不能另抄一份字面量。
        this.harmonyClarityMin = Harmonic.CLARITY_MIN;
        this.harmonyMatchMinScore = Harmonic.MATCH_MIN_SCORE;
        this.harmonyMinImprovement = Harmonic.MIN_IMPROVEMENT;
        this.harmonyMaxShift = Harmonic.MAX_SHIFT;

        // **3% → 8%**（2026-10-07，依据：用户的 Apple 推理）。用户仔细听过
        // Apple Music 在 skydive→春めく（A 128.0 / B 119.97，差 **6.27%**）上用的是
        // **交叉淡化** —— 也就是说：中等速度差**不构成**"需要额外处理"的冲突，
        // 因为变速那一层本来就能把 8% 以内接住（见 AutoCue 里"BPM 差在 3%~8%"那段）。
        // 旧门槛 3% 会把这一对直接推给低音交接/学习EQ，与用户听到的 Apple 做法相反。
        this.autoTempoConflict = 0.10f;
        // **0.85 → 0.75**（2026-10-07）：参考对量出来调性 0.796，而 Apple Music 在它上面用的是
        // **交叉淡化** ⇒ 0.796 不该构成"要额外处理"的理由。调性相近的曲子本来就该走朴素淡化，
        // 只有明显不合（< 0.75）才值得动素材。
        this.autoKeySame = 0.70f;
        // **0.35 → 0.5**（同上依据）：参考对量出来是 0.35（恰好压线），而 Apple 在它上面
        // 走的是交叉淡化 ⇒ "低频有一点叠"不该触发 EQ 处理。0.5 意味着"两边低频都占
        // 一半以上"才算真的撞上。
        this.lowClashGate = 0.5f;
        this.dropGate = 0.25f;
        this.minOverlapSecs = 1.5f;
        this.learnedEqMinWindowSecs = 2.0f;
        this.tailLongDbPerSec = -3.0f;
        this.headCalmGate = 0.25f;

        // 下面这些是 Java 侧新增的（上游没有对应字段），所以放在赋值序列末尾，
        // 上面那一串「与上游逐行一致」的对照关系不会被搅乱。
        this.spaceWidth = DEFAULT_SPACE_WIDTH;
        this.levelMatch = DEFAULT_LEVEL_MATCH;
        this.lowDuck = DEFAULT_LOW_DUCK;
        this.tempoStretch = DEFAULT_TEMPO_STRETCH;
    }

    /**
     * 上游 {@code MixTuning::default()}。
     *
     * <p>与 {@link #MixTuning()} 是同一件事，写成静态工厂只是为了让
     * 「取一份默认旋钮」这个调用点在 Java 里读起来更像上游。
     */
    public static MixTuning defaults() {
        return new MixTuning();
    }

    /**
     * 复制一份（对应上游的 {@code #[derive(Clone, Copy)]}）。
     *
     * <p>字段全是值类型，逐字段拷贝即为深拷贝。理由见类注释里那条
     * 「Rust 是 Copy、Java 是引用」的差别说明。
     */
    public MixTuning(MixTuning other) {
        this.windowScale = other.windowScale;
        this.maxWindowFraction = other.maxWindowFraction;

        this.crossoverHz = other.crossoverHz;
        this.bassSwapSplit = other.bassSwapSplit;
        this.bassSwapTogether = other.bassSwapTogether;

        this.cutFadeSecs = other.cutFadeSecs;
        this.cutLeadSecs = other.cutLeadSecs;
        // 新字段必须跟着拷贝：`AiMix` 的构造器是 `new MixTuning(tuning)`，
        // 漏了这一行旋钮就静默失效（P2 的接线判据当场照出来了：开了旋钮输出逐位没变）。
        this.spaceWidth = other.spaceWidth;
        this.levelMatch = other.levelMatch;
        this.lowDuck = other.lowDuck;
        this.tempoStretch = other.tempoStretch;

        this.tailRideOutroPower = other.tailRideOutroPower;
        this.tailRideIntroPower = other.tailRideIntroPower;

        this.shortInFraction = other.shortInFraction;
        this.loopConfirmMinCost = other.loopConfirmMinCost;
        this.styleLoopEnabled = other.styleLoopEnabled;
        this.delaySend = other.delaySend;
        this.delayWet = other.delayWet;
        this.delayBeats = other.delayBeats;
        this.delayFeedback = other.delayFeedback;
        this.reverbSend = other.reverbSend;
        this.reverbWet = other.reverbWet;
        this.reverbDecaySecs = other.reverbDecaySecs;
        this.reverbDamping = other.reverbDamping;
        this.filterSweepFromHz = other.filterSweepFromHz;
        this.filterSweepToHz = other.filterSweepToHz;
        this.filterSweepQ = other.filterSweepQ;
        this.showHpFrom = other.showHpFrom;
        this.showHpTo = other.showHpTo;
        this.showLpFrom = other.showLpFrom;
        this.showLpTo = other.showLpTo;
        this.showOutroGainPower = other.showOutroGainPower;
        this.showIntroGainPower = other.showIntroGainPower;

        this.harmonyClarityMin = other.harmonyClarityMin;
        this.harmonyMatchMinScore = other.harmonyMatchMinScore;
        this.harmonyMinImprovement = other.harmonyMinImprovement;
        this.harmonyMaxShift = other.harmonyMaxShift;

        this.autoTempoConflict = other.autoTempoConflict;
        this.autoKeySame = other.autoKeySame;
        this.lowClashGate = other.lowClashGate;
        this.dropGate = other.dropGate;
        this.minOverlapSecs = other.minOverlapSecs;
        this.learnedEqMinWindowSecs = other.learnedEqMinWindowSecs;
        this.tailLongDbPerSec = other.tailLongDbPerSec;
        this.headCalmGate = other.headCalmGate;
    }

    /**
     * 从 {@code 键=值} 串解析，未提到的项保持默认。
     *
     * <p>分隔符是逗号、分号或空白，键可以用接法名做前缀（{@code bassswap.crossover}），
     * 也可以直接用不带前缀的别名（{@code crossover}）。
     *
     * <p><strong>认不出的键一律报错，不静默忽略。</strong>
     * 静默忽略是这类配置最常见的坑：用户以为把某个值改了，实际没改，
     * 于是整个盲听循环都在一个假的假设上跑，而且看不出任何异常。
     *
     * @throws TuningException 键不认识、值不是数、或者值越界（对应上游的 {@code Err}）
     */
    public static MixTuning parse(String spec) {
        MixTuning t = new MixTuning();
        // 上游：spec.split([',', ';', ' ', '\n', '\t']).filter(|s| !s.is_empty())
        for (String raw : spec.split(SEPARATORS)) {
            if (raw.isEmpty()) {
                continue;
            }
            int eq = raw.indexOf('=');
            if (eq < 0) {
                // 上游报的是**整段原文**（此时还没 trim 过），保持一致。
                throw new TuningException(raw, "应当是 键=值");
            }
            String k = raw.substring(0, eq);
            String v = raw.substring(eq + 1).trim();

            // 上游：k.trim().to_ascii_lowercase().replace('-', "_")，再取最后一个 '.' 之后的部分。
            String key = lastSegment(asciiLower(k.trim()).replace('-', '_'));

            switch (key) {
                case "window_scale":
                case "scale":
                    t.windowScale = parseF32(v, key);
                    break;
                case "max_window_fraction":
                case "max_window":
                    // 上游：f()? as f64——**先按 f32 解析再拓宽**。别写成直接解析 double：
                    // 那样 "max_window=0.1" 会得到 0.1，而上游得到 0.10000000149011612。
                    t.maxWindowFraction = (double) parseF32(v, key);
                    break;
                case "crossover":
                case "crossover_hz":
                    t.crossoverHz = parseF32(v, key);
                    break;
                case "split":
                case "bass_swap_split":
                    t.bassSwapSplit = parseF32(v, key);
                    break;
                case "together":
                case "bass_swap_together":
                    t.bassSwapTogether = parseF32(v, key);
                    break;
                case "cut_fade":
                case "cut_fade_secs":
                    t.cutFadeSecs = parseF32(v, key);
                    break;
                case "width":
                    t.spaceWidth = Math.max(0.0f, Math.min(1.0f, parseF32(v, key)));
                    break;
                case "level_match":
                case "levelMatch":
                    t.levelMatch = Math.max(0.0f, Math.min(1.0f, parseF32(v, key)));
                    break;
                case "low_duck":
                case "lowDuck":
                    t.lowDuck = Math.max(0.0f, Math.min(1.0f, parseF32(v, key)));
                    break;
                case "tempo_stretch":
                case "tempoStretch":
                    t.tempoStretch = Math.max(0.0f, Math.min(1.0f, parseF32(v, key)));
                    break;
                case "cut_lead":
                case "cut_lead_secs":
                    t.cutLeadSecs = parseF32(v, key);
                    break;
                case "tail_outro":
                case "tail_ride_outro_power":
                    t.tailRideOutroPower = parseF32(v, key);
                    break;
                case "tail_intro":
                case "tail_ride_intro_power":
                    t.tailRideIntroPower = parseF32(v, key);
                    break;
                case "loop_cost":
                case "loop_confirm_min_cost":
                    t.loopConfirmMinCost = parseF32(v, key);
                    break;
                case "short_in":
                case "short_in_fraction":
                    t.shortInFraction = parseF32(v, key);
                    break;
                case "delay_send":
                case "delay_wet":
                case "delay_beats":
                case "delay_feedback":
                    if ("delay_send".equals(key)) {
                        t.delaySend = parseF32(v, key);
                    } else if ("delay_wet".equals(key)) {
                        t.delayWet = parseF32(v, key);
                    } else if ("delay_beats".equals(key)) {
                        t.delayBeats = parseF32(v, key);
                    } else {
                        t.delayFeedback = parseF32(v, key);
                    }
                    break;
                case "reverb_send":
                case "reverb_wet":
                case "reverb_decay":
                case "reverb_damping":
                    if ("reverb_send".equals(key) || "reverb_wet".equals(key)) {
                        float rv = parseF32(v, key);
                        if ("reverb_send".equals(key)) {
                            t.reverbSend = rv;
                        } else {
                            t.reverbWet = rv;
                        }
                    } else if ("reverb_decay".equals(key)) {
                        t.reverbDecaySecs = parseF32(v, key);
                    } else {
                        t.reverbDamping = parseF32(v, key);
                    }
                    break;
                case "sweep_from":
                case "filter_sweep_from_hz":
                    t.filterSweepFromHz = parseF32(v, key);
                    break;
                case "sweep_to":
                case "filter_sweep_to_hz":
                    t.filterSweepToHz = parseF32(v, key);
                    break;
                case "sweep_q":
                case "filter_sweep_q":
                    t.filterSweepQ = parseF32(v, key);
                    break;
                case "hp_from":
                case "show_hp_from":
                    t.showHpFrom = parseF32(v, key);
                    break;
                case "hp_to":
                case "show_hp_to":
                    t.showHpTo = parseF32(v, key);
                    break;
                case "lp_from":
                case "show_lp_from":
                    t.showLpFrom = parseF32(v, key);
                    break;
                case "lp_to":
                case "show_lp_to":
                    t.showLpTo = parseF32(v, key);
                    break;
                case "outro_gain":
                case "show_outro_gain_power":
                    t.showOutroGainPower = parseF32(v, key);
                    break;
                case "intro_gain":
                case "show_intro_gain_power":
                    t.showIntroGainPower = parseF32(v, key);
                    break;
                case "clarity_min":
                case "harmony_clarity_min":
                    t.harmonyClarityMin = parseF32(v, key);
                    break;
                case "match_min_score":
                case "harmony_match_min_score":
                    t.harmonyMatchMinScore = parseF32(v, key);
                    break;
                case "min_improvement":
                case "harmony_min_improvement":
                    t.harmonyMinImprovement = parseF32(v, key);
                    break;
                case "auto_tempo_conflict":
                case "tempo_conflict":
                    t.autoTempoConflict = parseF32(v, key);
                    break;
                case "auto_key_same":
                case "key_same":
                    t.autoKeySame = parseF32(v, key);
                    break;
                // 自动挑接法的素材门槛（2026-10-07 从 Chooser 常量搬来）
                case "low_clash_gate":
                    t.lowClashGate = parseF32(v, key);
                    break;
                case "drop_gate":
                    t.dropGate = parseF32(v, key);
                    break;
                case "min_overlap_secs":
                    t.minOverlapSecs = parseF32(v, key);
                    break;
                case "learned_eq_min_window_secs":
                    t.learnedEqMinWindowSecs = parseF32(v, key);
                    break;
                case "tail_long_db_per_sec":
                    t.tailLongDbPerSec = parseF32(v, key);
                    break;
                case "head_calm_gate":
                    t.headCalmGate = parseF32(v, key);
                    break;
                case "max_shift":
                case "harmony_max_shift":
                    // 整数字段的错误文案与浮点不同（上游单独写了一处 parse::<i32>）。
                    t.harmonyMaxShift = parseI32(v, key);
                    break;
                default:
                    // 上游：other => Err(TuningError { key: other, why: "没有这个调参项" })
                    // 报的是**归一化后的短键**（去掉前缀、小写、'-'→'_'），例如 bass_swap_splot。
                    throw new TuningException(key, "没有这个调参项");
            }
        }
        t.check();
        return t;
    }

    /**
     * 范围检查。
     *
     * <p>一个越界的旋钮（负的时长、0 的分频点）会产出听起来像「坏了」的东西，
     * 而坏掉的声音与「参数不好听」在盲听里很难分辨——
     * 那会把一整轮听力浪费在追一个数值错误上。所以宁可当场报错。
     *
     * <p>检查顺序与上游逐条一致：同时有几项越界时，报出来的是<strong>靠前</strong>的那一项，
     * 而这是可见行为（错误文案就是调参的人看到的东西），所以顺序照抄、不重排。
     * <strong>注意 {@code harmony_match_min_score} 与 {@code harmony_min_improvement}
     * 上游没有检查</strong>，这里也不补——补上就与上游分叉了。
     *
     * @throws TuningException 有一项越界
     */
    public void check() {
        if (!(windowScale > 0.0f && windowScale <= 8.0f)) {
            throw bad("window_scale", "应当在 (0, 8] 之间");
        }
        if (!(maxWindowFraction > 0.0 && maxWindowFraction <= 1.0)) {
            throw bad("max_window_fraction", "应当在 (0, 1] 之间");
        }
        if (!(crossoverHz > 0.0f && crossoverHz < 20000.0f)) {
            throw bad("crossover", "应当在 (0, 20000) Hz 之间");
        }
        if (!(bassSwapSplit >= 0.0f && bassSwapSplit < 1.0f)) {
            throw bad("split", "应当在 [0, 1) 之间");
        }
        if (!(bassSwapTogether >= 0.0f && bassSwapTogether <= 0.9f)) {
            // 上界 0.9 而不是 1：平台占满整段就不是过渡了，是两台同时放
            throw bad("together", "应当在 [0, 0.9] 之间");
        }
        if (!(cutFadeSecs > 0.0f && cutFadeSecs <= 1.0f)) {
            throw bad("cut_fade", "应当在 (0, 1] 秒之间");
        }
        if (!(cutLeadSecs >= 0.0f && cutLeadSecs <= 1.0f)) {
            throw bad("cut_lead", "应当在 [0, 1] 秒之间");
        }
        if (!(tailRideOutroPower > 0.0f) || !(tailRideIntroPower > 0.0f)) {
            throw bad("tail_outro/tail_intro", "幂必须为正");
        }
        if (!(showHpFrom > 0.0f && showHpTo > showHpFrom)) {
            throw bad("hp_from/hp_to", "终点必须高于起点，且都为正");
        }
        if (!(showLpFrom > 0.0f && showLpTo > showLpFrom)) {
            throw bad("lp_from/lp_to", "终点必须高于起点，且都为正");
        }
        if (!(showOutroGainPower > 0.0f) || !(showIntroGainPower > 0.0f)) {
            throw bad("outro_gain/intro_gain", "幂必须为正");
        }
        if (!(harmonyClarityMin >= 0.0f && harmonyClarityMin <= 1.0f)) {
            throw bad("clarity_min", "应当在 [0, 1] 之间");
        }
        if (!(harmonyMaxShift >= 0 && harmonyMaxShift <= 12)) {
            throw bad("max_shift", "应当在 0..=12 个半音之间");
        }
        if (!(autoTempoConflict > 0.0f && autoTempoConflict <= 1.0f)) {
            throw bad("auto_tempo_conflict", "应当在 (0, 1] 之间");
        }
        if (!(autoKeySame >= -1.0f && autoKeySame <= 1.0f)) {
            throw bad("auto_key_same", "应当在 [-1, 1] 之间（它是余弦相似度）");
        }
    }

    /**
     * 这一套旋钮的 {@code 键=值} 单行形式。
     *
     * <p>渲染出来的素材要能<strong>复现</strong>：听到满意的组合时，把这一行原样贴回去即可。
     * 所以它是不带前缀的完整别名形式，且与 {@link #parse(String)} 互为逆运算。
     *
     * <p>顺序与上游的 {@code vec![...]} 一致，浮点格式见 {@link #displayF32}。
     */
    public String toLine() {
        ArrayList<String> parts = new ArrayList<String>();
        parts.add("window_scale=" + displayF32(windowScale));
        parts.add("max_window_fraction=" + displayF64(maxWindowFraction));
        parts.add("crossover=" + displayF32(crossoverHz));
        parts.add("split=" + displayF32(bassSwapSplit));
        parts.add("together=" + displayF32(bassSwapTogether));
        parts.add("cut_fade=" + displayF32(cutFadeSecs));
        parts.add("cut_lead=" + displayF32(cutLeadSecs));
        parts.add("tail_outro=" + displayF32(tailRideOutroPower));
        parts.add("tail_intro=" + displayF32(tailRideIntroPower));
        parts.add("loop_cost=" + displayF32(loopConfirmMinCost));
        parts.add("short_in=" + displayF32(shortInFraction));
        parts.add("delay_send=" + displayF32(delaySend));
        parts.add("delay_wet=" + displayF32(delayWet));
        parts.add("delay_beats=" + displayF32(delayBeats));
        parts.add("delay_feedback=" + displayF32(delayFeedback));
        parts.add("reverb_send=" + displayF32(reverbSend));
        parts.add("reverb_wet=" + displayF32(reverbWet));
        parts.add("reverb_decay=" + displayF32(reverbDecaySecs));
        parts.add("reverb_damping=" + displayF32(reverbDamping));
        parts.add("sweep_from=" + displayF32(filterSweepFromHz));
        parts.add("sweep_to=" + displayF32(filterSweepToHz));
        parts.add("sweep_q=" + displayF32(filterSweepQ));
        parts.add("hp_from=" + displayF32(showHpFrom));
        parts.add("hp_to=" + displayF32(showHpTo));
        parts.add("lp_from=" + displayF32(showLpFrom));
        parts.add("lp_to=" + displayF32(showLpTo));
        parts.add("outro_gain=" + displayF32(showOutroGainPower));
        parts.add("intro_gain=" + displayF32(showIntroGainPower));
        parts.add("clarity_min=" + displayF32(harmonyClarityMin));
        parts.add("match_min_score=" + displayF32(harmonyMatchMinScore));
        parts.add("min_improvement=" + displayF32(harmonyMinImprovement));
        parts.add("max_shift=" + harmonyMaxShift);
        parts.add("auto_tempo_conflict=" + displayF32(autoTempoConflict));
        parts.add("auto_key_same=" + displayF32(autoKeySame));
        // 自动挑接法的素材门槛（2026-10-07）：**必须进 toLine**，否则设置存不下来、
        // 完备性判据的往返会红（实测就是这么红的）。
        parts.add("low_clash_gate=" + displayF32(lowClashGate));
        parts.add("drop_gate=" + displayF32(dropGate));
        parts.add("min_overlap_secs=" + displayF32(minOverlapSecs));
        parts.add("learned_eq_min_window_secs=" + displayF32(learnedEqMinWindowSecs));
        parts.add("tail_long_db_per_sec=" + displayF32(tailLongDbPerSec));
        parts.add("head_calm_gate=" + displayF32(headCalmGate));
        // P2/P4/P5 新增的三个（漏了这三个的后果不是「少一行日志」：`toLine()` 的承诺是
        // 「贴回去就能复现」，漏掉就等于把旋钮悄悄扔掉。判据
        // `MixTuningCompletenessTest` 用反射遍历所有可调字段来防这一族 bug。）
        parts.add("width=" + displayF32(spaceWidth));
        parts.add("level_match=" + displayF32(levelMatch));
        parts.add("low_duck=" + displayF32(lowDuck));
        parts.add("tempo_stretch=" + displayF32(tempoStretch));

        // 上游的 parts.dedup()：它只合并**相邻**的重复项。24 个键互不相同，
        // 今天等价于空操作；保留是为了让以后真出现同键项时的行为与上游完全一致
        // （dedup 不是去重，别把它当 set 用）。
        ArrayList<String> uniq = new ArrayList<String>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i);
            if (i == 0 || !p.equals(parts.get(i - 1))) {
                uniq.add(p);
            }
        }
        return String.join(",", uniq);
    }

    /** 某接法的窗口拍数（应用了缩放）。对应上游 {@code beats_for}。 */
    public float beatsFor(MixStyle style) {
        return style.beats() * windowScale;
    }

    /**
     * 对应上游 derive 的 {@code PartialEq}（21 个字段全比，顺序与字段声明一致）。
     *
     * <p>与 {@code Harmonic.Match} 同一个取舍：上游对 {@code f32} 用 {@code ==}
     * （于是 {@code NaN != NaN}、{@code -0.0 == 0.0}），这里用 {@link Float#compare}
     * 保证 {@code equals} 的自反性。正常路径上两者一致；唯一的边角是
     * {@code cut_lead=-0} / {@code split=-0} 这类负零（{@code check} 允许
     * {@code -0.0} 通过 {@code >= 0.0}），上游会认为它等于默认值、这里不会。
     * 负零在听感与数值上都没有区别，不值得为它放弃自反性。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MixTuning)) {
            return false;
        }
        MixTuning m = (MixTuning) o;
        return Float.compare(m.windowScale, windowScale) == 0
                && Double.compare(m.maxWindowFraction, maxWindowFraction) == 0
                && Float.compare(m.crossoverHz, crossoverHz) == 0
                && Float.compare(m.bassSwapSplit, bassSwapSplit) == 0
                && Float.compare(m.bassSwapTogether, bassSwapTogether) == 0
                && Float.compare(m.cutFadeSecs, cutFadeSecs) == 0
                && Float.compare(m.cutLeadSecs, cutLeadSecs) == 0
                && Float.compare(m.tailRideOutroPower, tailRideOutroPower) == 0
                && Float.compare(m.tailRideIntroPower, tailRideIntroPower) == 0
                && Float.compare(m.loopConfirmMinCost, loopConfirmMinCost) == 0
                && Float.compare(m.shortInFraction, shortInFraction) == 0
                && Float.compare(m.delaySend, delaySend) == 0
                && Float.compare(m.delayWet, delayWet) == 0
                && Float.compare(m.delayBeats, delayBeats) == 0
                && Float.compare(m.delayFeedback, delayFeedback) == 0
                && Float.compare(m.reverbSend, reverbSend) == 0
                && Float.compare(m.reverbWet, reverbWet) == 0
                && Float.compare(m.reverbDecaySecs, reverbDecaySecs) == 0
                && Float.compare(m.reverbDamping, reverbDamping) == 0
                && Float.compare(m.filterSweepFromHz, filterSweepFromHz) == 0
                && Float.compare(m.filterSweepToHz, filterSweepToHz) == 0
                && Float.compare(m.filterSweepQ, filterSweepQ) == 0
                && Float.compare(m.showHpFrom, showHpFrom) == 0
                && Float.compare(m.showHpTo, showHpTo) == 0
                && Float.compare(m.showLpFrom, showLpFrom) == 0
                && Float.compare(m.showLpTo, showLpTo) == 0
                && Float.compare(m.showOutroGainPower, showOutroGainPower) == 0
                && Float.compare(m.showIntroGainPower, showIntroGainPower) == 0
                && Float.compare(m.harmonyClarityMin, harmonyClarityMin) == 0
                && Float.compare(m.harmonyMatchMinScore, harmonyMatchMinScore) == 0
                && Float.compare(m.harmonyMinImprovement, harmonyMinImprovement) == 0
                && m.harmonyMaxShift == harmonyMaxShift
                && Float.compare(m.autoTempoConflict, autoTempoConflict) == 0
                && Float.compare(m.autoKeySame, autoKeySame) == 0
                && Float.compare(m.lowClashGate, lowClashGate) == 0
                && Float.compare(m.dropGate, dropGate) == 0
                && Float.compare(m.minOverlapSecs, minOverlapSecs) == 0
                && Float.compare(m.learnedEqMinWindowSecs, learnedEqMinWindowSecs) == 0
                && Float.compare(m.tailLongDbPerSec, tailLongDbPerSec) == 0
                && Float.compare(m.headCalmGate, headCalmGate) == 0
                && Float.compare(m.spaceWidth, spaceWidth) == 0
                && Float.compare(m.levelMatch, levelMatch) == 0
                && Float.compare(m.lowDuck, lowDuck) == 0
                && Float.compare(m.tempoStretch, tempoStretch) == 0;
    }

    @Override
    public int hashCode() {
        int h = 1;
        h = 31 * h + Float.floatToIntBits(windowScale);
        h = 31 * h + (int) (Double.doubleToLongBits(maxWindowFraction) ^ (Double.doubleToLongBits(maxWindowFraction) >>> 32));
        h = 31 * h + Float.floatToIntBits(crossoverHz);
        h = 31 * h + Float.floatToIntBits(bassSwapSplit);
        h = 31 * h + Float.floatToIntBits(bassSwapTogether);
        h = 31 * h + Float.floatToIntBits(cutFadeSecs);
        h = 31 * h + Float.floatToIntBits(cutLeadSecs);
        h = 31 * h + Float.floatToIntBits(tailRideOutroPower);
        h = 31 * h + Float.floatToIntBits(tailRideIntroPower);
        h = 31 * h + Float.floatToIntBits(loopConfirmMinCost);
        h = 31 * h + Float.floatToIntBits(shortInFraction);
        h = 31 * h + Float.floatToIntBits(delaySend);
        h = 31 * h + Float.floatToIntBits(delayWet);
        h = 31 * h + Float.floatToIntBits(delayBeats);
        h = 31 * h + Float.floatToIntBits(delayFeedback);
        h = 31 * h + Float.floatToIntBits(reverbSend);
        h = 31 * h + Float.floatToIntBits(reverbWet);
        h = 31 * h + Float.floatToIntBits(reverbDecaySecs);
        h = 31 * h + Float.floatToIntBits(reverbDamping);
        h = 31 * h + Float.floatToIntBits(filterSweepFromHz);
        h = 31 * h + Float.floatToIntBits(filterSweepToHz);
        h = 31 * h + Float.floatToIntBits(filterSweepQ);
        h = 31 * h + Float.floatToIntBits(showHpFrom);
        h = 31 * h + Float.floatToIntBits(showHpTo);
        h = 31 * h + Float.floatToIntBits(showLpFrom);
        h = 31 * h + Float.floatToIntBits(showLpTo);
        h = 31 * h + Float.floatToIntBits(showOutroGainPower);
        h = 31 * h + Float.floatToIntBits(showIntroGainPower);
        h = 31 * h + Float.floatToIntBits(harmonyClarityMin);
        h = 31 * h + Float.floatToIntBits(harmonyMatchMinScore);
        h = 31 * h + Float.floatToIntBits(harmonyMinImprovement);
        h = 31 * h + harmonyMaxShift;
        h = 31 * h + Float.floatToIntBits(autoTempoConflict);
        h = 31 * h + Float.floatToIntBits(autoKeySame);
        h = 31 * h + Float.floatToIntBits(lowClashGate);
        h = 31 * h + Float.floatToIntBits(dropGate);
        h = 31 * h + Float.floatToIntBits(minOverlapSecs);
        h = 31 * h + Float.floatToIntBits(learnedEqMinWindowSecs);
        h = 31 * h + Float.floatToIntBits(tailLongDbPerSec);
        h = 31 * h + Float.floatToIntBits(headCalmGate);
        h = 31 * h + Float.floatToIntBits(spaceWidth);
        h = 31 * h + Float.floatToIntBits(levelMatch);
        h = 31 * h + Float.floatToIntBits(lowDuck);
        h = 31 * h + Float.floatToIntBits(tempoStretch);
        return h;
    }

    /**
     * 镜像上游 {@code #[derive(Debug)]} 的输出（字段名保持上游的下划线写法，
     * 方便与上游日志对照）。
     *
     * <p>这里用 Java 自己的浮点 {@code toString}，因为 Rust 的 {@code Debug}
     * <strong>恰好</strong>也打印 {@code 1.0}（而 {@code Display} 打印 {@code 1}）——
     * 与 {@link #toLine()} 那条路径不要混用。
     */
    @Override
    public String toString() {
        return "MixTuning { window_scale: " + windowScale
                + ", max_window_fraction: " + maxWindowFraction
                + ", crossover_hz: " + crossoverHz
                + ", bass_swap_split: " + bassSwapSplit
                + ", bass_swap_together: " + bassSwapTogether
                + ", cut_fade_secs: " + cutFadeSecs
                + ", cut_lead_secs: " + cutLeadSecs
                + ", tail_ride_outro_power: " + tailRideOutroPower
                + ", tail_ride_intro_power: " + tailRideIntroPower
                + ", loop_cost: " + loopConfirmMinCost
                + ", short_in: " + shortInFraction
                + ", delay_send: " + delaySend
                + ", delay_wet: " + delayWet
                + ", delay_beats: " + delayBeats
                + ", delay_feedback: " + delayFeedback
                + ", reverb_send: " + reverbSend
                + ", reverb_wet: " + reverbWet
                + ", reverb_decay: " + reverbDecaySecs
                + ", reverb_damping: " + reverbDamping
                + ", sweep_from: " + filterSweepFromHz
                + ", sweep_to: " + filterSweepToHz
                + ", sweep_q: " + filterSweepQ
                + ", show_hp_from: " + showHpFrom
                + ", show_hp_to: " + showHpTo
                + ", show_lp_from: " + showLpFrom
                + ", show_lp_to: " + showLpTo
                + ", show_outro_gain_power: " + showOutroGainPower
                + ", show_intro_gain_power: " + showIntroGainPower
                + ", harmony_clarity_min: " + harmonyClarityMin
                + ", harmony_match_min_score: " + harmonyMatchMinScore
                + ", harmony_min_improvement: " + harmonyMinImprovement
                + ", harmony_max_shift: " + harmonyMaxShift
                + ", auto_tempo_conflict: " + autoTempoConflict
                + ", auto_key_same: " + autoKeySame
                + ", lowClashGate: " + lowClashGate
                + ", dropGate: " + dropGate
                + ", minOverlapSecs: " + minOverlapSecs
                + ", learnedEqMinWindowSecs: " + learnedEqMinWindowSecs
                + ", tailLongDbPerSec: " + tailLongDbPerSec
                + ", headCalmGate: " + headCalmGate + " }";
    }

    /**
     * 上游 {@code TuningError} 的构造点（上游在 {@code check} 里是个闭包 {@code bad}）。
     */
    private static TuningException bad(String key, String why) {
        return new TuningException(key, why);
    }

    /** 上游 {@code key.rsplit('.').next()}：取最后一个 {@code '.'} 之后的部分（没有就是本身）。 */
    private static String lastSegment(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? key : key.substring(dot + 1);
    }

    /**
     * 上游 {@code str::to_ascii_lowercase()}：<strong>只</strong>折叠 ASCII 大写字母。
     *
     * <p>不用 {@code String.toLowerCase(Locale.ROOT)}：那会连非 ASCII 一起折叠
     * （例如 {@code 'İ'}），而这里的键全是 ASCII——行为要一一对应，不要靠「反正用不到」。
     */
    private static String asciiLower(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return b.toString();
    }

    /**
     * 按 Rust {@code f32::from_str} 的语法解析，而不是直接用 {@link Float#parseFloat}。
     *
     * <p>两者接受的语言<strong>不一样</strong>，而多出来的那部分正是「用户以为改了、
     * 实际没改」的入口（{@code parse} 的整个立论就是不静默接受没听懂的东西）：
     *
     * <ul>
     *   <li>Java 接受尾缀 {@code 1f} / {@code 0.5D}，Rust 拒绝</li>
     *   <li>Java 接受十六进制浮点 {@code 0x1p3}，Rust 拒绝</li>
     *   <li>Java 只认 {@code "Infinity"} / {@code "NaN"} 这两个确切写法，
     *       Rust 认 {@code inf}/{@code infinity}/{@code nan} 的任意大小写</li>
     * </ul>
     *
     * <p>这里接受的语言与 Rust 相同：可选符号，然后
     * {@code inf} / {@code infinity} / {@code nan}（大小写不敏感），
     * 或者十进制数字（可含一个小数点，两侧至少共有一位数字）加可选的
     * {@code e}/{@code E} 指数。<strong>下划线不是数字的一部分</strong>（Rust 的
     * {@code from_str} 也不认，{@code 1_000} 只在源码字面量里合法）。
     *
     * <p>溢出成 {@code ±inf} 不是错误（Rust 与 Java 在这里一致），
     * 而且越界值反正会被 {@link #check()} 拦下。
     */
    private static float parseF32(String v, String key) {
        int n = v.length();
        int i = 0;
        boolean neg = false;
        if (i < n && (v.charAt(i) == '+' || v.charAt(i) == '-')) {
            neg = v.charAt(i) == '-';
            i++;
        }
        if (i >= n) {
            throw new TuningException(key, "期望一个数，拿到 " + v);
        }

        String lower = asciiLower(v.substring(i));
        if (lower.equals("inf") || lower.equals("infinity")) {
            return neg ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
        }
        if (lower.equals("nan")) {
            return Float.NaN;
        }

        int j = i;
        int digits = 0;
        while (j < n && isAsciiDigit(v.charAt(j))) {
            j++;
            digits++;
        }
        if (j < n && v.charAt(j) == '.') {
            j++;
            while (j < n && isAsciiDigit(v.charAt(j))) {
                j++;
                digits++;
            }
        }
        if (digits == 0) {
            throw new TuningException(key, "期望一个数，拿到 " + v);
        }
        if (j < n && (v.charAt(j) == 'e' || v.charAt(j) == 'E')) {
            j++;
            if (j < n && (v.charAt(j) == '+' || v.charAt(j) == '-')) {
                j++;
            }
            int expDigits = 0;
            while (j < n && isAsciiDigit(v.charAt(j))) {
                j++;
                expDigits++;
            }
            if (expDigits == 0) {
                throw new TuningException(key, "期望一个数，拿到 " + v);
            }
        }
        if (j != n) {
            // 走到这里说明有多余字符：'f' 尾缀、十六进制、下划线、非 ASCII 全都在这一支。
            throw new TuningException(key, "期望一个数，拿到 " + v);
        }
        return Float.parseFloat(v);
    }

    /**
     * 按 Rust {@code i32::from_str} 的语法解析（可选符号 + 纯 ASCII 十进制数字）。
     *
     * <p>不用 {@link Integer#parseInt} 直接判：它经 {@code Character.digit} 接受
     * 其它 Unicode 数字（例如阿拉伯-印度数字 {@code ٥}），Rust 不认。
     * 溢出仍然交给 {@code parseInt} 抛，错误文案与上游一致（上游 {@code parse::<i32>}
     * 失败时也是同一个分支）。
     */
    private static int parseI32(String v, String key) {
        int n = v.length();
        int i = 0;
        if (i < n && (v.charAt(i) == '+' || v.charAt(i) == '-')) {
            i++;
        }
        if (i >= n) {
            throw new TuningException(key, "期望整数个半音，拿到 " + v);
        }
        for (int j = i; j < n; j++) {
            if (!isAsciiDigit(v.charAt(j))) {
                throw new TuningException(key, "期望整数个半音，拿到 " + v);
            }
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new TuningException(key, "期望整数个半音，拿到 " + v);
        }
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Rust {@code Display} 对 {@code f32} 的样子：<strong>能唯一还原的最短十进制，
     * 且不带多余的 {@code .0}</strong>（{@code 1.0f32} 打印 {@code 1}、{@code 4000.0f32}
     * 打印 {@code 4000}）——{@code to_line} 的输出要能贴回上游复现，格式就得一样。
     *
     * <p>Java 的 {@link Float#toString} 从 JDK 19 起也是「最短且能唯一还原」，
     * 只是强制保留一位小数（{@code 1.0}），所以这里只去掉那个 {@code .0}。
     * 残留差异只有一处：{@code |x| ≥ 10^7} 或 {@code < 10^-3} 时 Java 会用指数记法
     * （{@code 1.0E-5}），Rust 的 {@code Display} 永远展开——在 {@link #check()} 允许的
     * 取值范围内只有「极小的正数」（如 {@code crossover=1e-5}）会碰到，且它照样能被
     * {@link #parse(String)} 解析回去，往返性质不受影响。
     */
    private static String displayF32(float v) {
        String s = Float.toString(v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** {@link #displayF32} 的 {@code f64} 版本（上游 {@code max_window_fraction} 是 f64）。 */
    private static String displayF64(double v) {
        String s = Double.toString(v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
