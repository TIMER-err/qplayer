package dev.t1m3.qplayer.audio.mix;

/**
 * <b>出场曲尾奏的保音高降速斜坡（beatmatch ramp）</b>——纯推导、纯数据，没有任何 I/O。
 *
 * <p>为什么要有它（2026-10-06，取证报告
 * {@code tools/automix-forensics/APPLE_AUTOMIX_FORENSIC_REPORT.md}）：Apple 在
 * skydive → 春めく 上对<b>出场曲</b>做了保音高的<b>渐进</b>降速（相对线性外推的偏差线性累积，
 * 分数半音扫描 +0.00 ⇒ 不是变调重采样），而我们当时是"完全不变速"。
 * 仓库里早就有保音高变速（{@link TempoBridge}，纯 WSOLA），但只做成<b>恒定</b>比例
 * （参考对上 0.9373×）——恒定比例在窗口起点是一个<b>速率阶跃</b>：素材内容接得上，
 * 速率却是一下子跳过去的。这个类把"阶跃"换成"斜坡"，并顺手回答窗口该多长。
 *
 * <h3>它算三个数</h3>
 *
 * <ol>
 *   <li><b>速率比</b> {@code ratio = 进场 BPM / 出场 BPM}（{@code <1} = 把出场曲放慢）；</li>
 *   <li><b>窗口上界</b> {@link #windowSecs}：完成一次 beatmatch 所需的最短 ramp 长度，
 *       见下面「窗口怎么定」；</li>
 *   <li><b>终点速率</b> {@link #endSpeedFor}：从 1.000 单调降到哪儿为止，见下面「降多少」。</li>
 * </ol>
 *
 * <h3>降多少：只降到"听不出还差"为止（不是降到完全对齐）</h3>
 *
 * <p>把出场曲一直降到 {@code ratio}（完全对齐）看起来最"正确"，但那是<b>最大干预</b>：
 * 参考对上是 6.27% 的降速，而人耳对"拍长（tempo）差"的听阈（JND）在
 * {@value #TEMPO_JND} 量级——<b>只要残留的拍长差落进听阈以内，再降就是白改素材</b>。
 * 所以终点速率取
 * {@code ratio × (1 + TEMPO_JND)}（= 残留差正好一个 JND 的那个速率），
 * 这也是本包一贯的"最小干预"纪律（{@link #MIN_DEVIATION} 那条"比值离 1 太近就不动"
 * 是它在门槛处的特例）。
 *
 * <p><b>这条规则是拿参考对的实测校准过的，不是拍脑袋</b>：Apple 实测坡道末端
 * 瞬时速率 {@code 0.963} ⇒ 它的出场曲末端是 123.3 BPM，与进场曲 120.0 还差 <b>2.7%</b>
 * ——正落在听阈以内。同一条规则套在参考对上给 {@code 0.93727 × 1.027 = 0.9626}
 * （末端 123.2 BPM，残留 2.63%），与 Apple 实测的 0.963 差 <b>0.04%</b>。
 *
 * <h3>窗口怎么定：完成一次 beatmatch 的最短 ramp 长度（只减不增）</h3>
 *
 * <p>{@code W = |1 − ratio| / }{@value #SLEW_MAX}：把全部 BPM 差吸收掉所需的最短时长，
 * 而"最短"由<b>听不出来的最快斜率</b>封顶——{@value #SLEW_MAX} 是<b>每秒</b>允许的速率变化量
 * （2.5%/s；在参考对那个速度上约合每拍 1.2%，低于上面那个听阈，所以没有哪一拍听起来是
 * "被硬拖了一下"）。参考对：{@code 0.06273 / 0.025 = 2.51 s}。
 *
 * <p>它为什么是窗口的<b>上界</b>（而不是下界）：融合窗口本身没有独立的意义，
 * 它的用处就是<b>把两首歌的拍子在并行期里拉近</b>；ramp 需要多久，窗口就该多长——
 * 比它更长的时间是白留的，而长窗口会让两边素材在别处（低频、人声）打架。
 * 这与尾奏循环那条「<b>只延长不缩短</b>」（时间不够时买时间，见
 * {@code AutoCueTransition.prepareLoopOutgoing} 的两道长度闸）是<b>对称</b>的一对：
 * 那条是买时间，这条是省时间。用户/风格设的值在这条规则面前只是上界。
 * 参考对上它给出 <b>2.5 s</b>，与 Apple 实测的单侧淡化 2.4~2.8 s 同档。
 *
 * <h3>不 active 的四种情况（都退回原来的行为，一个字节都不碰）</h3>
 *
 * <ul>
 *   <li>两侧拍栅格置信度不足（拿着不准的 BPM 去变速 = 按一个错的拍长改素材）；</li>
 *   <li>比值落在 {@link AutoCueTransition#STRETCH_MIN_SPEED}~{@link AutoCueTransition#STRETCH_MAX_SPEED}
 *       之外（那不是"微调"了）；</li>
 *   <li>比值离 1 太近（{@link AutoCueTransition#STRETCH_MIN_DEVIATION} 以内）；</li>
 *   <li><b>拍长差本来就落在听阈以内</b>（{@code ratio × (1 + TEMPO_JND) ≥ 1}）——
 *       两首歌本来就"够齐"，不动素材，窗口也照旧不动。</li>
 * </ul>
 *
 * <p>前三条的实现只有一处（{@link AutoCueTransition#stretchSpeedFor}），这个类不复制它们：
 * 拿到的一定是一个已经过闸门、真正要动手的比值。
 */
public final class TempoRamp {

    /**
     * <b>每秒允许的速率变化量</b>（0.025 = 2.5%/s）：斜坡的斜率上限。
     *
     * <p>取值理由有两条，都指向同一个量级：① 在参考对那个速度上（一小节 1.875 s）
     * 一小节最多变 4.7%、一拍最多变 1.2%，落在"听不出被拖"的听阈（{@value #TEMPO_JND}）以内；
     * ② 它让"吸收参考对那 6.27% 的差"给出 <b>2.5 s</b> 的窗口，
     * 与 Apple 实测的单侧淡化（2.4~2.8 s）同档——这是它唯一的"外部校准点"。
     */
    public static final double SLEW_MAX = 0.025;

    /**
     * <b>拍长差的听阈</b>（0.027 = 2.7%）：残留差落进这里就不再降速。
     *
     * <p>它同时是「要不要出手」的门槛（{@code ratio × (1 + TEMPO_JND) ≥ 1} 就不出手）
     * 与「要降到哪儿」的目标。取值有两个来源，互相不冲突：
     * <ol>
     *   <li>音乐心理学里 tempo 差的可辨阈大致是 2~4%，2.7% 落在中段；</li>
     *   <li><b>参考对实测的残留就是这个数</b>：Apple 的坡道末端速率 0.963 ⇒ 出场曲末端
     *       123.3 BPM，与进场曲 120.0 还差 2.7%。也就是说 Apple 自己停在了"听不出还差"
     *       的那条线上——这条规则用同一个数，于是两边的**末端残留**是可比的
     *       （我们 2.63%，Apple 2.7%）。</li>
     * </ol>
     */
    public static final double TEMPO_JND = 0.027;

    /** 窗口的下限（秒）：比这更短就不是融合而是硬切了（与 {@code AutoCueTransition} 同口径）。 */
    public static final double MIN_WINDOW_SECS = 1.0;

    /** 这一条规则出手了吗。 */
    public final boolean active;

    /** 速率比 {@code 进场 BPM / 出场 BPM}（{@code <1} = 出场曲要被放慢）；不 active 时是 1。 */
    public final double ratio;

    /** 用户/风格给的窗口（秒）——这条规则面前它只是<b>上界</b>。 */
    public final double userWindowSecs;

    /** 这条规则给的窗口（秒）：{@code min(用户值, 完成一次 beatmatch 的最短 ramp)}。 */
    public final double windowSecs;

    /** 窗口<b>起点</b>的速率（恒为 1.0 = 与自然播放同速，接缝因此没有速率阶跃）。 */
    public final double startSpeed;

    /** 可读说明（日志与判据）。 */
    public final String note;

    /**
     * 这一次是不是<b>双方各承担一半</b>那一档（{@link #deriveHalved}）。
     *
     * <p>为什么要有这个位：两条路的"降到哪儿"不一样——老路（{@link #derive}）把整份差都压在
     * A 身上，终点是 {@code ratio×(1+听阈)}；对半路只走一半，终点是 {@code sqrt(ratio)}。
     * 早先我直接把 {@code endSpeedFor} 改成 sqrt，结果**老路也被改了**（判据当场红）——
     * 模式必须显式，否则同一条公式会被两套语义轮流解释。
     */
    public final boolean halved;

    private TempoRamp(boolean active, double ratio, double userWindowSecs, double windowSecs,
                      double startSpeed, String note) {
        this(active, ratio, userWindowSecs, windowSecs, startSpeed, note, false);
    }

    private TempoRamp(boolean active, double ratio, double userWindowSecs, double windowSecs,
                      double startSpeed, String note, boolean halved) {
        this.active = active;
        this.ratio = ratio;
        this.userWindowSecs = userWindowSecs;
        this.windowSecs = windowSecs;
        this.startSpeed = startSpeed;
        this.note = note;
        this.halved = halved;
    }

    /** 这一次<b>不降速</b>（窗口保持用户/风格给的那个值）。 */
    public static TempoRamp off(double userWindowSecs, String why) {
        return new TempoRamp(false, 1.0, userWindowSecs, userWindowSecs, 1.0, why);
    }

    /**
     * 按<b>已经过闸门</b>的 BPM 比推出这一次的斜坡（纯函数，判据直接钉它）。
     *
     * <p>闸门（置信度、{@value #MIN_RATIO}~{@value #MAX_RATIO}、"离 1 太近就不动"）
     * 仍然只有一处实现：{@link AutoCueTransition#stretchSpeedFor}——它返回 1.0 就是"不降速"，
     * 于是这里的入参一定是一个真正要动手的比值。这里只补上<b>本规则特有</b>的那道门：
     * 拍长差本来就落在听阈以内时也不动手。
     *
     * @param ratio          进场 BPM / 出场 BPM（{@code <1} = 出场曲要被放慢）
     * @param userWindowSecs 用户/风格给的窗口（秒）——只作为上界，见类注释
     */
    public static TempoRamp derive(double ratio, double userWindowSecs) {
        double window = userWindowSecs > 0.0 ? userWindowSecs : MIN_WINDOW_SECS;
        if (!(ratio > 0.0)) {
            return off(window, "BPM 算不出来，这一次不降速");
        }
        // 只降到"残留拍长差 = 一个听阈"为止：不需要再往 ratio 走，所以这里就能判
        // "本来就不用出手"——两首歌的拍长差本来就落在听阈以内。
        double stop = ratio * (1.0 + TEMPO_JND);
        if (stop >= 1.0) {
            return off(window, "两侧拍长差 " + pct(1.0 - ratio) + " 已在听阈内，这一次不降速");
        }
        // 窗口上界 = 完成一次 beatmatch 的最短 ramp 长度（见类注释）
        double cap = Math.abs(1.0 - ratio) / SLEW_MAX;
        double derived = Math.max(MIN_WINDOW_SECS, Math.min(window, cap));
        TempoRamp probe = new TempoRamp(true, ratio, window, derived, 1.0, "");
        return new TempoRamp(true, ratio, window, derived, 1.0,
                "渐变降速 1.0000→" + f4(probe.endSpeedFor(derived)) + "×"
                        + "（窗口 " + f2(derived) + "s / 平均 " + f4(probe.meanSpeedFor(derived))
                        + "×；只减不增：BPM 比 " + f4(ratio) + "、用户值 " + f2(window) + "s）");
    }

    /**
     * <b>双方各承担一半</b>（2026-10-06 第一阶段工作三）。
     *
     * <p>用户原话：「现有的变速方案是 B 不变，A 独自承担全部变化量。BPM 差 8% 时，
     * A 拉伸 8%，失真明显。改成**双方各承担一半**。目标 BPM 用**几何平均**，不是算术平均。
     * 因为 BPM 感知是对数的。」
     *
     * <pre>
     *   targetBPM = sqrt(bpmA × bpmB)      // 几何平均
     *   speedA    = targetBPM / bpmA       // = sqrt(ratio)
     *   speedB    = targetBPM / bpmB       // = 1 / speedA      ⇒ speedA × speedB = 1
     * </pre>
     *
     * <p>于是 A 加速多少、B 就减速多少，<b>单方最大变化量减半</b>（WSOLA 伪影随之减半）。
     *
     * <p><b>曲线按用户规格</b>：前 {@value #CONVERGE_FRAC} 收敛（双方同时靠拢到上面那对速度）、
     * 之后<b>一直保持、不划回</b>——因为 B 的 Deck 本来就是原速，过渡结束后 B 按原速播，
     * 不需要划回。所以这里的窗口只需容下"收敛那一段"：
     * <pre>
     *   窗口 = min(用户值, |1 − speedA| / (SLEW_MAX × CONVERGE_FRAC))
     * </pre>
     * 与旧版（整窗线性降到 ratio 为止）相比：总差要吸收的量没变，但
     * ① 每侧只走一半、② 收敛放在前 25% 且之后不动 ⇒ <b>同样窗口下斜率更低、更听不出来</b>。
     *
     * @param ratio 进场 BPM / 出场 BPM（{@code <1} = 出场曲更快）
     */
    public static TempoRamp deriveHalved(double ratio, double userWindowSecs) {
        double window = userWindowSecs > 0.0 ? userWindowSecs : MIN_WINDOW_SECS;
        if (!(ratio > 0.0)) {
            return off(window, "BPM 算不出来，这一次不变速");
        }
        // 「本来就不用出手」判据看的是**配对残差**：两侧都不动时两首歌的拍长差就是 |1−ratio|，
        // 它落在听阈以内才真的不用动（旧版按"每侧要走的量"判，对半之后会把 3% 的差误判成
        // 1.5% 而放过——实测踩到，这条注释就是那次修正的账）。
        if (Math.abs(1.0 - ratio) <= TEMPO_JND) {
            return off(window, "两侧拍长差 " + pct(1.0 - ratio) + " 已在听阈内，这一次不变速");
        }
        double speedA = Math.sqrt(ratio);
        // **窗口回到"吸收这一份拍长差所需的时长"**（2026-10-06 按 Apple 取证修正）。
        //
        // 教训：工作三我一度把窗口改成"用户值"（本机 5.99 s），理由是规格的"前 25% 收敛"
        // 需要窗口够长。但**我们自己量过 Apple**（tools/automix-forensics/
        // APPLE_AUTOMIX_FORENSIC_REPORT.md）：A 淡出 **2.8 s**、B 淡入 **2.4 s**（总交叠 ~3.4 s），
        // 而变速照样发生（保音高 1.000 → ~0.966、末端 0.963±0.01）。
        // 也就是说 **淡化与变速是两件事**：Apple 用很短的淡化换来"换歌感"最小，
        // 而把变速摊在那一小段里（斜率 3.2%/2.5 s ≈ 1.3%/s，远在听阈之内）。
        // 绑成一个 5.99 s 的窗口，等于把淡化拉长 2 倍多——用户听到的"分开太明显"与"糊"
        // 都与此有关。
        //
        // 所以：窗口 = 吸收**整份**拍长差所需的时长（|1−ratio| / SLEW_MAX，参考对 2.51 s，
        // 正落在 Apple 的 2.4~2.8 s 带里）；对半之后每侧只走一半 ⇒ 收敛占比由斜率上限
        // 自适应（见 AutoCueTransition.prepareRampedOutgoing 的 convergeFrac），
        // 规格里那个 25% 是"够快时"的理想值，不够快就多花点时间、仍然不划回。
        double cap = Math.abs(1.0 - ratio) / SLEW_MAX;
        double derived = Math.max(MIN_WINDOW_SECS, Math.min(window, cap));
        TempoRamp probe = new TempoRamp(true, ratio, window, derived, 1.0, "", true);
        double endA = probe.endSpeedFor(derived);
        return new TempoRamp(true, ratio, window, derived, 1.0,
                "双方各半 1.0000→" + f4(endA) + "×（对方 →" + f4(1.0 / endA) + "×）"
                        + "（窗口 " + f2(derived) + "s；BPM 比 " + f4(ratio)
                        + "、用户值 " + f2(window) + "s、" + pct(CONVERGE_FRAC)
                        + " 内收敛后保持不划回）", true);
    }

    /**
     * 收敛段占整窗的比例（2026-10-06 第一阶段工作三的规格：<b>前 25% 靠拢</b>，
     * 中间 50% 保持，后 25% <b>不划回</b>）。
     */
    public static final double CONVERGE_FRAC = 0.25;

    /**
     * <b>在实际窗口上把终点速率定下来</b>——这才是渲染真正用的那个数。
     *
     * <p>为什么要按窗口重算：{@link #windowSecs} 只是候选集的上界，分析层有权在它
     * <b>以下</b>再挑（"只减不增"只管上界）。窗口一旦比推导值短，斜率上限
     * {@value #SLEW_MAX} 就可能咬住：这时能降到多少就降多少（<b>部分吸收</b>），
     * 而不是硬凑——硬凑就是把一个听得出被拖的阶跃塞进短窗里。
     *
     * <pre>
     *   end = min(1.0, max( ratio × (1 + TEMPO_JND),      // 目标：残留差 = 一个听阈
     *                       1.0 − SLEW_MAX × 窗口 ))      // 下限：斜率上限允许降到哪儿
     * </pre>
     *
     * @param windowSecs 这一次实际的窗口（秒）
     */
    public double endSpeedFor(double windowSecs) {
        if (!active) {
            return 1.0;
        }
        double w = Math.max(0.0, windowSecs);
        // **A 的目标是几何平均那一半**（sqrt(ratio)），收敛只发生在前 CONVERGE_FRAC 的窗口里，
        // 所以斜率上限按那一段算：装不下就**部分吸收**（能走多少走多少），不硬凑。
        double stop = halved ? Math.sqrt(ratio) : ratio * (1.0 + TEMPO_JND);
        // 斜率上限按**整窗**算：{@link #CONVERGE_FRAC} 说的是"够快时收敛放在前 25%"，
        // 而不是"只准用前 25%"——不够快就让收敛多花点时间（仍不划回），
        // 而不是欠收敛。实测欠收敛的代价很具体：A 的末端速率从 Apple 实测带里的
        // 0.968 被抬到 **0.984**，而 Apple 那只曲子听感上的"对味"正是靠那 3~4%。
        double reachable = 1.0 - SLEW_MAX * w;
        return Math.min(1.0, Math.max(stop, reachable));
    }

    /** 窗口内的<b>平均</b>速率（线性斜坡 = 两端均值）；判据与日志读它。 */
    public double meanSpeedFor(double windowSecs) {
        return active ? (startSpeed + endSpeedFor(windowSecs)) / 2.0 : 1.0;
    }

    /**
     * <b>速率曲线本身</b>：窗口内第 {@code u} 秒处的瞬时速率（线性斜坡，{@code u} 夹进
     * {@code [0, 窗口]}）。这是"渐变"两个字的唯一出处：{@code speedAt(0) = 1.000}、
     * {@code speedAt(窗口) = }{@link #endSpeedFor}，中间严格单调。
     *
     * <p>{@link TempoBridge#stretchRamp} 的源位置就是它的<b>积分</b>（那个方法的注释里写了
     * 同一式子）——两处必须是同一条曲线，判据 {@code AutoCueTempoRampTest} 两边都钉。
     */
    public double speedAt(double windowSecs, double uSecs) {
        if (!active) {
            return 1.0;
        }
        double w = Math.max(1e-9, windowSecs);
        double u = Math.min(Math.max(0.0, uSecs), w);
        return startSpeed + (endSpeedFor(windowSecs) - startSpeed) * (u / w);
    }

    /** 窗口内某一点对应的<b>源素材位置</b>（速率的积分，秒）——与渲染那条式子是同一个。 */
    public double sourceSecsAt(double windowSecs, double uSecs) {
        if (!active) {
            return uSecs;
        }
        double w = Math.max(1e-9, windowSecs);
        double u = Math.min(Math.max(0.0, uSecs), w);
        double k = (endSpeedFor(windowSecs) - startSpeed) / w;
        return startSpeed * u + 0.5 * k * u * u;
    }

    /**
     * 这一次实际<b>吸收掉</b>的 BPM 差占全部差的比例（0..1）。
     *
     * <p>1 = 完全对齐到 {@code ratio}；{@value #TEMPO_JND} 那一档规则给的一般是 0.5 上下。
     * <b>参考对上的实测值是 60%</b>（{@code AutoCueTempoRampTest} 打出来的原文：
     * 「窗口 2.5093 s（用户值 7.49 s）· 1.0000 → 0.9626×（平均 0.9813×，吸收 60% 的差）」，
     * 也就是吸收掉的绝对量 1 − 0.9626 = <b>3.74%</b>、占全部差 6.27% 的 59.7%）。
     * 它只用来写日志与报告，不参与任何判断。
     *
     * <p><b>2026-10-06 修正</b>：这里原来写着「参考对：吸收 3.3% / 6.27% = 53%」——
     * 两个数都是笔误（3.3% 与 53%），与实测不符。上面的数是照
     * {@code AutoCueTempoRampTest} 的打印逐字抄的。
     */
    public double absorbedFraction(double windowSecs) {
        if (!active) {
            return 0.0;
        }
        return (1.0 - endSpeedFor(windowSecs)) / Math.max(1e-9, 1.0 - ratio);
    }

    private static String f2(double v) {
        return String.format(java.util.Locale.US, "%.2f", Double.valueOf(v));
    }

    private static String f4(double v) {
        return String.format(java.util.Locale.US, "%.4f", Double.valueOf(v));
    }

    private static String pct(double v) {
        return String.format(java.util.Locale.US, "%.2f%%", Double.valueOf(v * 100.0));
    }

    @Override
    public String toString() {
        return "TempoRamp{" + (active
                ? ("1.0000→" + f4(endSpeedFor(windowSecs)) + "× · 窗口 " + f2(windowSecs) + "s")
                : "off") + (note.isEmpty() ? "" : " · " + note) + "}";
    }
}
