package dev.t1m3.qplayer.audio.mix;

/**
 * 过渡开始<strong>之前</strong>得出的安排。
 *
 * <p>它把一个现实问题摆到台面上：要对拍、要判调性，就必须在过渡开始<strong>之前</strong>
 * 知道两首歌长什么样。而 {@link Transition#render} 拿到的只是窗口内的两小段，
 * 那时候再算已经来不及了。
 *
 * <p>所以 {@link Transition#prepare} 拿到的是<strong>两个文件路径</strong>，实现可以自己
 * 开一份解码器去读尾部与首部。这条设计让分析不依赖播放线程的推进节奏，
 * 也避免为了「看未来」而在 Deck 里塞一个环形预读缓冲。
 *
 * <p><strong>注意耗时</strong>：上游实测 {@code prepare} 最坏 4.6 秒（debug、auto 接法），
 * 所以编排器必须在<strong>另一条线程</strong>上调它，并且提前（剩余 ≤ 窗口 + 30 秒）
 * 就开始。同步调会把环形缓冲写空，听感是「卡几秒然后才出来」。
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::transition::TransitionPlan}。
 */
public final class TransitionPlan {

    /** 什么都没安排。 */
    public static final TransitionPlan NONE = new TransitionPlan(0.0, PitchPlan.ZERO, 0f, "");

    /**
     * 出场曲<strong>被丢掉的尾巴</strong>（秒）：「自动点位」选中的融合点可能不在曲子末尾，
     * 那么从「出场点 + 交叠时长」到全曲结束的这一段就<strong>不会播</strong>。
     *
     * <p>编排器靠它把过渡的触发点提前：出场曲还剩 {@code 窗口 + 这个尾巴} 时就得开始交叠
     * （否则等到只剩一个窗口时，出场曲已经被它自己的尾巴占满了，融合点也就落回曲尾）。
     * 0 表示「在曲尾融合」，也就是固定点位的老行为。
     */
    public final double outroTailSecs;

    /**
     * 进场曲要跳过的时长（秒）。
     *
     * <p>用途是把进场曲的<strong>第一拍对齐到出场曲的拍上</strong>。曲子的第一个强拍
     * 往往不在文件第 0 帧，所以这个值通常非零。
     */
    public final double skipSecs;

    /** 变调安排。 */
    public final PitchPlan pitch;

    /**
     * 进场曲从窗口的<strong>哪个进度</strong>开始参与（0.0 → 1.0）。
     *
     * <p>只有拍点剪切需要它：那种接法在窗口的大部分时间里只放出场曲，进场曲
     * <strong>要等到切点才开始解码</strong>。若让它在窗口一开始就跟着跑，它的开头会被
     * 白白吃掉——切点处实际听到的已经是它几百毫秒之后的内容。
     */
    public final float introStartAt;

    /** 可读说明，供界面与日志。例如「128.0 BPM · 转调 +2」。 */
    public final String note;

    /**
     * 窗口里「两边人声同时在场」的时间段（秒，相对窗口起点，平成对给），
     * 供<b>人声层</b>决定「哪几秒需要去人声」（见 {@code AutoCue.vocalOverlapRanges}）。
     *
     * <p>三种取值分得很清楚，人声层照这三种各走一条路：
     * <ul>
     *   <li><b>{@code null}</b>（默认）= 这次没有这个信息（自动点位没开、接法不是 AI 那几档、
     *       或者分析拿不到分频包络）→ 人声层**照旧按整窗去人声**，与没有这个字段时逐位相同；</li>
     *   <li><b>空数组</b> = 明确结论「窗口里两边没有叠唱」→ 人声层**整段不去人声**；</li>
     *   <li><b>非空</b> = 只在这些区间里替换素材，区间之间**原样保留** deck 的素材。</li>
     * </ul>
     */
    public final double[] vocalDuckRanges;

    /**
     * <b>「人声让多少」的连续权重曲线</b>（每个元素一格、每 {@code AutoCue.HOP_SECS} 秒一格，
     * 取值 0..1；{@code null} = 没有这个信息）。
     *
     * <p>为什么要有它（2026-10-03 用户报「现在这版 skydive→春めく 有点乱」）：
     * {@link #vocalDuckRanges} 是<b>开关式</b>的——区间内权重 1、区间外 0，两端各 30 ms 坡。
     * 而代理 VAD 给出的叠唱区间往往是<b>互不相连的好几段</b>，实测参考对上就是三段
     * （0.88~1.26 / 2.68~2.76 / 4.74~5.50 秒）→ 于是一次融合里人声被「拿掉、放回」<b>三轮</b>：
     * 听起来不是糊，是<b>砧板式的拼贴</b>。
     *
     * <p>曲线版把这件事变成<b>连续的</b>：权重跟着「两边同时在唱」的强度平滑地起落，
     * 没有开关、没有碎段，最陡的变化也摊在零点几秒里。
     *
     * <p><b>优先级</b>：曲线非 {@code null} 时<b>优先于</b> {@link #vocalDuckRanges} 使用
     * （区间那份仍然带着，只用于日志与向后兼容）。两者都为 {@code null} 时行为与加这个字段
     * 之前<b>逐位相同</b>（整窗去人声）。
     */
    public final float[] vocalDuckCurve;

    public TransitionPlan(double skipSecs, PitchPlan pitch, float introStartAt, String note) {
        this(skipSecs, pitch, introStartAt, note, 0.0);
    }

    /**
     * @param outroTailSecs 出场曲被丢掉的尾巴（秒），见 {@link #outroTailSecs}；
     *                      负数按 0 处理（设置/分析都是不可信输入）
     */
    /**
     * 带上「哪几秒要去人声」的那一份（2026-10-02 加）。
     *
     * <p>做成**新增重载**而不是改既有构造器：{@code new TransitionPlan(...)} 在仓库里有
     * 十来处，逐个改既吵又容易漏；不传就是 {@code null} = 「没有这个信息」，
     * 行为与加这个字段之前**逐位相同**（那条纪律：关掉的功能要与没有它一致）。
     */
    public TransitionPlan(double skipSecs, PitchPlan pitch, float introStartAt, String note,
                          double outroTailSecs, double[] vocalDuckRanges) {
        this(skipSecs, pitch, introStartAt, note, outroTailSecs, vocalDuckRanges, null);
    }

    /** 完整版：再带上「人声让多少」的连续权重曲线（见 {@link #vocalDuckCurve}）。 */
    public TransitionPlan(double skipSecs, PitchPlan pitch, float introStartAt, String note,
                          double outroTailSecs, double[] vocalDuckRanges,
                          float[] vocalDuckCurve) {
        this.skipSecs = skipSecs;
        this.pitch = pitch == null ? PitchPlan.ZERO : pitch;
        this.introStartAt = introStartAt;
        this.note = note == null ? "" : note;
        this.outroTailSecs = outroTailSecs > 0.0 ? outroTailSecs : 0.0;
        this.vocalDuckRanges = vocalDuckRanges;
        this.vocalDuckCurve = vocalDuckCurve;
    }

    public TransitionPlan(double skipSecs, PitchPlan pitch, float introStartAt, String note,
                          double outroTailSecs) {
        this(skipSecs, pitch, introStartAt, note, outroTailSecs, null);
    }



    /** 是否什么都没安排。 */
    public boolean isNoop() {
        return skipSecs <= 0.0 && pitch.isZero() && introStartAt <= 0f && note.isEmpty()
                && outroTailSecs <= 0.0 && vocalDuckRanges == null;
    }

    @Override
    public String toString() {
        return "TransitionPlan{skipSecs=" + skipSecs + ", pitch=" + pitch
                + ", introStartAt=" + introStartAt + ", outroTailSecs=" + outroTailSecs
                + ", note='" + note + "'}";
    }
}
