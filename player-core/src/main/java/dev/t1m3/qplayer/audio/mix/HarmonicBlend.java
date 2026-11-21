package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;

/**
 * 声波重合（上游 {@code automix.rs} 的 {@code HarmonicBlend}）。
 *
 * <p>先判两首歌的调性差，算出一个移调量；<b>但移调只加在出场曲的伴奏上，
 * A 与 B 的人声一个采样都不动</b>。这份意图由 {@code VocalAwarePitchTransition}
 * 在准备线程里消费（分轨 → 只移伴奏 → 四条轨等功率交叉）；deck 拿不到它。
 * 模块级的说明（三个模式、共用骨架、对拍怎么算、算不出来就不猜、洁净室声明）
 * 见 {@link Automix}。
 *
 * <h2>为什么只移伴奏（用户 2026-09-29 定的规矩）</h2>
 *
 * <p>用户的要求：「<b>效果就是 A 和 B 人声音调都不变</b>」，并且明确
 * 「没人注意伴奏好不好，而且变调的时候在融合期更不会察觉」。所以：
 * 人声那两条是耳朵唯一盯着的东西，一个采样都不许动；伴奏可以按调性差移。
 *
 * <p>违反过这条规矩的两种形态（都修了，判据都在）：
 *
 * <ol>
 *   <li><b>deck 整段移调</b>（上游原设计）：移调交给 {@code Deck.setPitch}，它作用在
 *       <b>整个混音</b>上，人声跟着走——用户实测「声波重合的时候 A 的人声变调了」；</li>
 *   <li><b>只保护了「声波重合」那一档</b>：AI 自动档挑中声波重合时走的是另一条链
 *       （{@code AiMix.pitchAt} 委托给它自己的 {@code HarmonicBlend}），那条链上
 *       没有任何人声保护——**用户开着 DSP 也照样听到 A 的人声变调**。</li>
 * </ol>
 *
 * <h2>移调只加在出场曲的伴奏上（2026-09-30 修）</h2>
 *
 * <p>{@code shift} 的语义是「把<b>出场曲</b>转到进场曲的调上」（见 {@code Harmonic.match}），
 * 所以只有 A 的伴奏按它移：A 的伴奏于是<b>真的</b>落进 B 的调里，而 B 整条（伴奏 + 人声）
 * 一个采样都不动——它要接着播下去，动它就是给无缝交接留一个跳变。
 *
 * <p>原先两条伴奏移的是<b>同一个量</b>（A、B 都移）：那既完不成「凑调」（A、B 之间的音程
 * 一点没变），又让 B 的伴奏与 B 自己的人声差 {@code shift} 个半音、窗口结束时再弹回原调——
 * 用户 2026-09-30 报的「（声波重合时）加速了」就是那个跳变（docs §12.1① 早就点过名）。
 *
 * <h2>剩下的取舍（用户明确接受）</h2>
 *
 * <p>出场曲自己身上还剩一处不协和：A 的伴奏被移了、A 的人声没移，所以窗口内 A 的伴奏与
 * A 自己的人声差 {@code shift} 个半音。要连这个也消掉，只能<b>整体移进场曲</b>
 * （人声一起移，用户不接受）。权衡的结果是：<b>宁可 A 自己差一点，也不动人声</b>。
 *
 * <h2>变调不能用重采样</h2>
 *
 * <p>升 3 个半音如果靠「读快一点」实现，速度会一起快 19%——那是变速，不是变调。
 * 必须用<b>时长不变的颗粒式重叠相加</b>（{@link dev.t1m3.qplayer.audio.dsp.PitchShifter}），
 * 而且 {@code ratio == 1} 时要<b>整段旁路</b>而不是 1:1 空转：空转会把位完美白丢掉。
 *
 * <h2>渲染路径上不做变调</h2>
 *
 * <p>变调由 deck 那一侧施加（见 {@code Deck.setPitch}），这里只做等功率融合。
 * 把它放在 deck 而不是这里，是因为变调器需要<b>跨块连续的状态</b>——
 * 每块重新初始化的变调器会在块边界留下碎响。
 *
 * <h2>与上游的类型差异（按 docs/audio-port.md 记录）</h2>
 *
 * <ul>
 *   <li>字段 {@code shift} / {@code score} 是<b>包私有</b>而不是 {@code private}：
 *       上游的私有字段对子模块 {@code mod tests} 可见（测试会直接写
 *       {@code t.shift = 3.0} 模拟「上一首判出来要转 3 个半音」），Java 的对应可见性
 *       就是包私有——只给同包的 {@code HarmonicBlendTest} 用，不作为对外 API。</li>
 *   <li>{@code Result} → 异常：{@code read_mono} 失败时抛 {@link AudioException}，
 *       {@link #prepare} 接住它并如实写进 {@code plan.note}（分析失败不阻断过渡）。</li>
 *   <li>{@code &Path} → {@code String}（本地路径或 http(s) URL）。</li>
 *   <li>{@code u32}（采样率）→ {@code int}，{@code usize}（声道数）→ {@code int}，
 *       {@code u64}（帧）→ {@code long}。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::automix::HarmonicBlend}。
 */
public final class HarmonicBlend implements Transition {

    private final int rate;

    private final int channels;

    private final long window;

    /**
     * 计算出来的转调量（半音）。0 表示不转。
     *
     * <p>包私有：上游的私有字段对子模块测试可见，理由见类注释。
     */
    float shift;

    /** 匹配质量，供界面显示。包私有：理由同 {@link #shift}。 */
    float score;

    /**
     * 构造。{@code seconds} 是窗口时长。
     *
     * <p>{@code f32::round} 是「半数远离零」，Java 的 {@code Math.round(float)} 是
     * {@code floor(x+0.5)}：两者只在负值上分叉，而这里的乘积恒为非负
     * （{@code seconds.max(0.0)} 已经把负值夹掉了），所以直接用 {@code Math.round}。
     */
    public HarmonicBlend(int rate, int channels, float seconds) {
        int r = Math.max(rate, 1);
        this.rate = r;
        this.channels = Math.max(channels, 1);
        this.window = Math.round(Math.max(seconds, 0.0f) * (float) r);
        this.shift = 0.0f;
        this.score = 0.0f;
    }

    /** 当前转调量（半音）。 */
    public float shift() {
        return shift;
    }

    /**
     * 变调滑入段的<strong>绝对</strong>时长上限（秒）。
     *
     * <p><strong>这是与上游的已记录偏差</strong>（见 {@code docs/audio-port.md}）。
     * 上游把滑入段写成窗口的 {@link Automix#OUTRO_PITCH_GLIDE_END 前 25%}，
     * 在它自己的默认窗口下没暴露问题；但窗口是用户可调的（0–20 s），
     * 于是 10 s 的窗口意味着<strong>出场曲连续滑音 2.5 秒</strong>：
     *
     * <ul>
     *   <li>正在放的那首歌当场变调并一路弯过去——听感就是「诡异音高」；</li>
     *   <li>滑音期间两首歌始终<strong>不在一调上</strong>（最多差一个全音以上），
     *       而进场曲到滑音结束时已经响了 38%（{@code cos/sin} 等功率曲线），
     *       于是那两秒半里两路一直在打架——听感就是「糊」。</li>
     * </ul>
     *
     * <p>滑入段本来只有一个用处：把「忽然变调」这一下藏起来。200 ms 足够藏住它，
     * 而且那时进场曲才刚出声（10 s 窗口下约 −30 dB），不存在「两路不在一调上」
     * 的窗口。窗口很短时（&lt; 0.8 s）仍按上游的 25% 走——那本来就更短。
     */
    public static final float PITCH_GLIDE_SECS = 0.20f;

    /**
     * 这一档是否把算出来的调性差算成一份移调<b>意图</b>（{@code plan.pitch}）。
     *
     * <p><b>是</b>——但这份意图只表达「想移多少」，谁施加有严格分工：
     * {@code VocalAwarePitchTransition} 在准备线程里把它消费掉（只加在伴奏上），
     * 而 {@link #pitchAt} 恒返回零，deck 永远拿不到「整段混音移调」这种指令。
     *
     * <p>改成 {@code false} 就是这一档彻底不转调（伴奏也不动）——那是我在
     * 2026-09-29 一度采取的做法，但用户的要求是「伴奏可以移、人声别动」，故留 true。
     */
    public static final boolean TRANSPOSITION_ENABLED = true;

    /** 匹配相似度。 */
    public float score() {
        return score;
    }

    /** 声道数（仅用于调试输出）。 */
    public int channels() {
        return channels;
    }

    /** 采样率。 */
    public int rate() {
        return rate;
    }

    @Override
    public long windowFrames(int sampleRate) {
        return window;
    }

    @Override
    public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
        // 变调由 deck 那一侧施加（见 `Deck::set_pitch`），这里只做等功率融合。
        // 把它放在 deck 而不是这里，是因为变调器需要**跨块连续的状态**——
        // 每块重新初始化的变调器会在块边界留下碎响。
        Mixer.Gains g = Mixer.equalPowerGains(Automix.clampF32(progress, 0.0f, 1.0f));
        return Mixer.mixTwo(out, from, g.from, to, g.to);
    }

    @Override
    public String name() {
        return "声波重合";
    }

    @Override
    public boolean rewritesSamples() {
        return true;
    }

    @Override
    public TransitionPlan prepare(String outro, String intro, int rate) {
        return prepare(outro, intro, rate, null, null);
    }

    @Override
    public TransitionPlan prepare(String outro, String intro, int rate,
                                  java.util.Map<String, String> outroHeaders,
                                  java.util.Map<String, String> introHeaders) {
        // 上游在这里 `let _ = rate;`：分析用的是**两个文件各自的**采样率
        // （见 Harmonic.match 的文档：共用一个是错的），传进来的输出采样率不参与。
        //
        // ---- 先回到「不转调」这个干净基线 ----
        //
        // **不能只在下结论时赋值。** 引擎对象跨曲目复用，而 `pitchAt()` 读的正是
        // `self.shift`：上一次的转调量留在里面，一首本来不需要转调（或根本没判出
        // 调性）的歌就会被按上一个歌的半音数处理。
        // 更糟的是 `plan.pitch` 此时是零，编排器不会提前挂变调器，
        // 于是变调器在过渡中途才被挂上——丢掉最初几十毫秒的前视，
        // 听感是一声断裂，正是 `Deck::engage_pitch` 文档里警告的那一下。
        shift = 0.0f;
        score = 0.0f;

        Automix.Mono tail;
        try {
            tail = Automix.readMono(outro, outroHeaders, true, Harmonic.WINDOW_SECS);
        } catch (AudioException e) {
            return failurePlan("出场曲分析失败（" + e.getMessage() + "），本次不转调");
        }
        Automix.Mono head;
        try {
            head = Automix.readMono(intro, introHeaders, false, Harmonic.WINDOW_SECS);
        } catch (AudioException e) {
            return failurePlan("进场曲分析失败（" + e.getMessage() + "），本次不转调");
        }

        Harmonic.Match m = Harmonic.match(tail.pcm, tail.rate, head.pcm, head.rate);
        PitchPlan pitch = PitchPlan.ZERO;
        String note;
        if (m != null) {
            // 上游 `m.best_score.max(m.plain_score)`（f32::max：一边是 NaN 时取另一边）。
            score = Automix.maxF32(m.bestScore, m.plainScore);
            if (m.shouldShift && TRANSPOSITION_ENABLED) {
                shift = (float) m.semitones;
                // 意图：**出场曲的伴奏**按这个量移（进场曲不动）。它由「只移伴奏」那一层
                // 在准备线程里消费
                // （`VocalAwarePitchTransition` 读的正是 `plan.pitch.outro`），
                // deck 拿不到它——那一层的 pitchAt 恒为零。
                pitch = new PitchPlan(shift, 0.0f);
            }
            note = intervalNote(m);
        } else {
            // 没有调性信息（噪声、静音、太短）→ 不表态，老老实实淡化
            note = "声波重合 · 素材无调性信息，按朴素融合处理";
        }
        return new TransitionPlan(0.0, pitch, 0.0f, note);
    }

    /**
     * <b>双方都变调</b>（2026-10-06 第一阶段工作四）。
     *
     * <p>用户原话：「现有的变调方案是 A 移调，B 不变。C 大调接 D 大调，A 移调加 2 半音，
     * B 不变，**A 全程跑调**。改成双方都变，但用一条特殊曲线：前 25% 双方靠拢到中间调，
     * 中间 50% 保持，后 25% 各自划回原调。」
     *
     * <p>中间调就是"各走一半"：{@code keyMid = keyA + d/2}（{@code d > 6} 时走环形另一侧，
     * 即 {@code keyMid = keyA − (12−d)/2}）。落到两个数上就是
     * {@code shiftA = +d/2}、{@code shiftB = −d/2}——正好是把原来那一份单方移调劈成两半，
     * 于是**单方最大移调量减半**（人声跑调的幅度也跟着减半）。
     *
     * <p>曲线（{@link #pitchCurve}）：前 25% 线性靠拢 → 保持到 <b>0.70</b> → 之后线性划回 0。
     * <b>从 0.70 开始而不是 0.75 是有意的</b>（用户明确说"不要改成 0.75"）：0.75 时 A 的增益
     * 已经很小，让 B 提前回到原调，避免两首歌在收尾处又分开。
     *
     * <p>两个数分别落到 {@link PitchPlan#outro}（A）与 {@link PitchPlan#intro}（B）——
     * 编排层 {@code Director.renderTransition} 早就各自 {@code setPitch} 了，
     * 以前 {@code intro} 恒零所以 B 没效果，现在自动生效。
     */
    @Override
    public PitchPlan pitchAt(float progress) {
        if (Math.abs(shift) < 1e-6f) {
            return PitchPlan.ZERO;
        }
        float half = shift / 2.0f;
        if (Math.abs(half) > MAX_SIDE_SHIFT) {
            // 防御：单侧要走的量超过上限（3 个半音）就退回**单方变**（B 不动）。
            // 最短距离 ≤ 6，所以 d/2 ≤ 3 恒成立——这一支是防线，不是常规路径。
            float g = Automix.smoothstep(0.0f, glideEnd(), progress);
            return new PitchPlan(shift * g, 0.0f);
        }
        float f = pitchCurve(progress);
        return new PitchPlan(half * f, -half * f);
    }

    /** 单侧移调量的上限（半音）：超过它就退回单方变。 */
    static final float MAX_SIDE_SHIFT = 3.0f;

    /** 靠拢段占窗口的比例（用户规格：前 25%）。 */
    static final float PITCH_CONVERGE_END = 0.25f;

    /** 开始划回的位置（用户规格：**0.70，不是 0.75**）。 */
    static final float PITCH_RETURN_START = 0.70f;

    /**
     * 变调曲线（0..1 → 0..1）：前 {@value #PITCH_CONVERGE_END} 线性靠拢、保持到
     * {@value #PITCH_RETURN_START}、之后线性划回 0。
     *
     * <p>与变速那条曲线是**两条独立的曲线**（用户要求「变速和变调必须用两条独立曲线，
     * 不要为了代码复用把两者合并」）：变速<b>不划回</b>（B 的 Deck 本来就是原速），
     * 变调<b>划回</b>（过渡结束后两首歌各自回到自己的原调，谁都不跑调）。
     */
    static float pitchCurve(float progress) {
        float p = Math.max(0.0f, Math.min(1.0f, progress));
        if (p < PITCH_CONVERGE_END) {
            return smooth(p / PITCH_CONVERGE_END);
        }
        if (p <= PITCH_RETURN_START) {
            return 1.0f;
        }
        return smooth((1.0f - p) / (1.0f - PITCH_RETURN_START));
    }

    /**
     * <b>缓和曲线</b>（3x² − 2x³）：两端斜率为 0。
     *
     * <p><b>为什么必须是它而不是线性</b>（2026-10-06 用户提醒：「注意融合窗口还有融合点滑音
     * 的问题哈」）：线性移调 = **匀速滑音**，而窗口 5.99 s 的 25% 是 1.5 s——
     * 半音走 1.5 秒是**明显听得出来的弯音**。缓和曲线把两端的**变化率**压到 0，
     * 于是没有"开始滑"那一下的边界感，听感上更接近"没动"。
     * 仓库里原来那条单方曲线用的就是 {@code Automix.smoothstep}，这里保持同一把尺子。
     *
     * <p>另一条同源的纪律：变速那条曲线**不用**缓和（它靠"前 25% 收敛后保持"来避免阶跃，
     * 且变速的听感边界是拍点而不是音高）——两条曲线是独立的，别合并。
     */
    private static float smooth(float x) {
        float v = Math.max(0.0f, Math.min(1.0f, x));
        return v * v * (3.0f - 2.0f * v);
    }

    /**
     * 滑入段占窗口的比例。取「上游的 25%」与「{@link #PITCH_GLIDE_SECS} 对应的比例」
     * 里更小的那个——窗口很短时前者更小（也就更短），窗口长时后者兜住。
     */
    float glideEnd() {
        if (window <= 0L || rate <= 0) {
            return Automix.OUTRO_PITCH_GLIDE_END;
        }
        float capped = (PITCH_GLIDE_SECS * (float) rate) / (float) window;
        return Math.min(Automix.OUTRO_PITCH_GLIDE_END, capped);
    }

    /**
     * 算出一个调性差时，写进 {@code plan.note} 的那句话。
     *
     * <p><b>不转调时也必须把量到的东西报出来</b>：用户看日志时该知道
     * 「分析判出差 2 个半音，是纪律上没动它」，而不是以为分析压根没跑。
     *
     * <p>包私有：让 {@code HarmonicPitchPolicyTest} 能直接钉住措辞与数字——
     * 为了造一份真实存在调性差的音频文件去驱动 {@code prepare} 不值当，
     * 而这句话恰恰是「决策依据」唯一对外的出口。
     */
    static String intervalNote(Harmonic.Match m) {
        if (!TRANSPOSITION_ENABLED) {
            return "声波重合 · " + m.describe() + "；这一档已关闭移调";
        }
        if (!m.shouldShift) {
            return "声波重合 · " + m.describe();
        }
        return "声波重合 · " + m.describe() + "；移调只加在两条伴奏上（A、B 的人声原样）";
    }

    /**
     * 分析失败时的安排：不转调 + 原因说明。
     *
     * <p>上游是「先建一个 {@code TransitionPlan::default()} 再写 {@code note}」，
     * Java 的 {@link TransitionPlan} 是不可变的，所以直接构造一个等价的 plan。
     */
    private TransitionPlan failurePlan(String note) {
        return new TransitionPlan(0.0, PitchPlan.ZERO, 0.0f, note);
    }

    /**
     * 镜像上游 {@code #[derive(Debug)]} 的输出（字段名保持上游的下划线写法，
     * 方便与上游日志对照）。
     */
    @Override
    public String toString() {
        return "HarmonicBlend { rate: " + rate
                + ", channels: " + channels
                + ", window: " + window
                + ", shift: " + shift
                + ", score: " + score + " }";
    }
}
