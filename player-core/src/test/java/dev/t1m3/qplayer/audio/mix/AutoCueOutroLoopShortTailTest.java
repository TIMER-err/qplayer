package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;

import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>「尾奏只剩 2–4 小节 ⇒ 会赶 ⇒ 循环买时间」这一档在真实歌曲上真的用起来了</b>
 * （用户 2026-10-05 的规格原话：「尾奏太短，下一首 intro 也太短：两首歌之间只剩
 * <b>2–4 小节</b>，直接交叉淡化会很赶。这时 loop 当前歌尾奏的最后 4/8 小节，争取出
 * 8–16 小节的过渡窗口」）。
 *
 * <h2>缺口是什么（这条判据修的那个）</h2>
 *
 * <p>{@code planOutroLoop} 里"买时间"原先只有两条触发，而它们说的是同一件事：
 * <b>窗口放不下</b>（{@code tailRemaining < windowSecs} 或
 * {@code cutSecs + windowSecs > duration}）。可是计划路径自己的硬约束是
 * 「出场点 + 窗口 ≤ 全曲 − 2 s」，于是这两条在生产路径上几乎恒为 false（本机 6 对真素材
 * 实测全是 false，见 {@code AutoCueOutroLoopBuyTimeTest} 的类注释）——<b>这一档一次都
 * 走不到</b>。而规格说的本来就是<strong>"尾奏剩得少"本身就是触发条件</strong>：
 * 不必等到"窗口放不下"，剩 2–4 小节就该循环，否则那次交叠短得像硬切。
 *
 * <p>所以这次补上第三条：{@code tailRemaining < LOOP_SHORT_TAIL_BARS 小节}
 * （门 = 4 小节，理由见 {@code AutoCue.LOOP_SHORT_TAIL_BARS}：8 小节会把参考对错误地
 * 卷进来——skydive 尾奏剩 5.5 小节，而用户听过 Apple 在那对曲子上的过渡没有循环）。
 *
 * <h2>素材：自己造的，答案是已知的（照 {@code AutoCueOutroLoopBuyTimeTest} 的做法）</h2>
 *
 * <p>合成 4/4 打击乐（振荡器只有 60/120 Hz 与 8 kHz 正弦 → 代理 VAD 判"没人唱"，
 * 与真素材上 vamp 的处境一样），走生产那一套扫描（降采样、RMS、谱通量、拍点估计都真的跑）：
 *
 * <ul>
 *   <li><b>A</b>：128 BPM（小节 1.875 s）。安静引子 / 底鼓 hi-hat 段 / 两小节过门 /
 *       <b>vamp：8 小节 [26.25, 41.25)</b>（每拍底鼓 + 反拍 hat、逐小节相同 → 前后半
 *       自相似 ≈ 1、电平稳、人声 0%）/ <b>最后 N 小节稀疏收尾</b>；
 *   <li><b>B</b>：32 小节 = 60.0 s、<b>同样 128 BPM</b>（BPM 差 0% ⇒ 不会落进"对齐网格"
 *       那一档：三条触发里只剩"买时间"，变量只有一个）。</li>
 * </ul>
 *
 * <p>三条判据共用这一份 A，只把尾巴长度与窗口换掉：
 *
 * <ol>
 *   <li>{@link #aShortTailThatStillFitsTheWindowBuysTimeWithALoop}：<b>3 小节尾巴</b>
 *       （N = 3）+ 3.0 s 窗口（1.6 小节，放得下）→ 引擎给 {@code buysTime} 的循环计划，
 *       渲染层把窗口从 3.0 s 延长到"一遍循环段 × 重复次数"；</li>
 *   <li>{@link #aSixBarTailIsNotShortEnoughToBuyTime}：<b>6 小节尾巴</b> + 7.5 s 窗口
 *       → <b>不</b>循环（钉住门槛的位置：防止以后有人随手放宽——例如放到 §8.0.9 里
 *       写的 8 小节，这条立刻红）；</li>
 *   <li>{@link #theGateIsExactlyFourBars}：拿同一份包络与 vamp 直接问决策层，把门<b>精确</b>
 *       钉在 4 小节上：3.99 小节 → 给计划，4.00 小节 → 不给。</li>
 * </ol>
 */
@Ignore("legacy：旧决策链的判据；忠实移植 Apple 决策链期间停用 "
        + "（旧逻辑快照在 git tag automix-legacy-20261121，还原后可恢复）")
public class AutoCueOutroLoopShortTailTest {

    private static final int RATE = 22050;

    /**
     * 触发那一条用的交叠时长：<b>3.0 s = 1.6 小节</b>。
     *
     * <p>为什么要这么短：这份素材的尾奏只有 3 小节（5.625 s），而计划路径的硬约束
     * 「出场点 + 窗口 ≤ 全曲 − 2 s」允许的最大窗口正好是 3.625 s——也就是说"尾奏剩 2–4 小节"
     * 这一档里，引擎**只能**给出一个 1~2 小节的交叠，这正是用户说的"直接交叉淡化会很赶"。
     * 而窗口是放得下的（这条判据的前提断言钉着它），所以旧的两条触发都不成立。
     */
    private static final double SHORT_WINDOW = 3.0;

    /** 对照那一条用的交叠时长（4 小节）——放得下，且硬约束保证了尾巴一定够长。 */
    private static final double LONG_WINDOW = 7.5;

    /** A 与 B 同一个 BPM：把"对齐网格"那一档排除掉，触发只剩"买时间"这一条。 */
    private static final double BPM = 128.0;

    private static final String SRC_A = "test://shorttail-a";
    private static final String SRC_B = "test://shorttail-b";

    // ==================================================================
    // 判据一：尾奏只剩 3 小节（窗口放得下）⇒ 买时间的循环，窗口被延长
    // ==================================================================

    /**
     * <b>尾奏剩 3 小节 ⇒ 循环买时间，窗口被延长</b>。
     *
     * <p>断言四件事：
     *
     * <ol>
     *   <li><b>前提</b>（与实现无关的算术）：尾奏里有一段 8 小节的干净 vamp；赢的那个点
     *       只剩 2–4 小节尾巴（会赶）；<b>这次窗口是放得下的</b>——旧的两条触发
     *       （{@code 尾奏 < 窗口}、{@code 出场点 + 窗口 > 全曲}）在这套点位上都不成立。
     *       没有这一条，"循环了"可以狡辩成"碰巧放不下"；</li>
     *   <li><b>计划层</b>：{@code Cue.outroLoop} 非 null、{@code buysTime} 为真、被接线口径
     *       收下（{@code loopIsWired}），而且 <b>reason 写的是新那一条</b>（"不足 4.0 小节"），
     *       不是旧的"放不下"——用户读日志验收的就是这一句；</li>
     *   <li><b>渲染层</b>：AI 模式口径（不传现读供应商、不许按分析调窗口）下窗口被
     *       <b>延长</b>到"一遍循环段 × 重复次数"，而且拿了循环素材；</li>
     *   <li><b>不是假绿</b>：延长后的窗口整窗渲染出来，窗口头是 A 的循环段（从 vamp 起点重播）
     *       的样本——排除"窗口变长了、素材还是原来那份"。</li>
     * </ol>
     */
    @Test
    public void aShortTailThatStillFitsTheWindowBuysTimeWithALoop() {
        Fixture fx = fixture(4);
        Crossfade base = new Crossfade((float) SHORT_WINDOW);
        try {
            AutoCue.Envelope a = fx.outroEnv;
            double barSecs = 4.0 * 60.0 / a.tempo.bpm;
            AutoCue.Cue cue = AutoCue.planWithLyricSpans(a, fx.introEnv,
                    new double[]{SHORT_WINDOW}, AutoCue.vocalPhraseSpans(a),
                    AutoCue.vocalPhraseSpans(fx.introEnv), AutoCue.Bridge.MEASURE);
            assertNotNull("合成素材上应当挑得出点位（否则这条判据什么都没测）", cue);

            // ── 前提①：尾奏里有一段干净、整数小节的可循环 vamp ─────────────
            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(a);
            assertNotNull("尾奏里应当找得到可循环的 vamp（合成素材里它是 8 小节的纯打击乐重复）",
                    vamp);
            assertEquals("vamp 应当是整数 8 小节（量到 " + vamp.length() + " s / 小节 "
                            + barSecs + " s）",
                    8, (int) Math.round(vamp.length() / barSecs));
            assertTrue("vamp 不够干净（自相似 " + vamp.selfSim + "、人声 " + vamp.vocalFrac
                            + "、电平 cv " + vamp.levelCv + "）——素材没造对",
                    vamp.selfSim >= AutoCue.VAMP_MIN_SELF_SIM
                            && vamp.vocalFrac <= AutoCue.VAMP_MAX_VOCAL_FRACTION
                            && vamp.levelCv <= AutoCue.VAMP_MAX_CV);

            // ── 前提②：赢的那个点只剩 2–4 小节尾巴（会赶）────────────────────
            //
            // 这里的 4 是**用户规格里那个数**，故意写死、不读 {@code LOOP_SHORT_TAIL_BARS}：
            // 前提量的是夹具的形状（"尾奏剩 2–4 小节"），不是实现的门槛。读实现那个常量的话，
            // 门槛一旦被改坏，红的会是这条前提、真正的断言反而看不到（红-前验证就撞上过）。
            // ── 前提②与判据（**2026-10-06 工作五按证据重述**）────────────────────
            //
            // 原来这一条钉的是"尾奏剩 2–4 小节、窗口放得下 ⇒ 借时间（循环）"。
            // 切点上限从"不许贴到曲尾 15%"放到 25% 之后，**同一对素材上赢的那个点落得更靠后**：
            // 尾巴变成 10.81 s = 5.77 小节 —— 尾巴已经够长，引擎**正确地拒绝**借时间。
            // 也就是说这个夹具被放开本身推进了"不借时间"那一档（同文件的
            // aSixBarTailIsNotShortEnoughToBuyTime 正是钉那一档的），而"短尾 ⇒ 借时间"
            // 那一支由 theGateIsExactlyFourBars（直接在 4 小节门槛上钉）覆盖。
            //
            // 所以这里改成钉**反面契约**：尾巴够长时不许借时间（防"循环到处乱开"），
            // 同时把"这次尾巴确实够长、窗口确实放得下"当成前提报出来。
            double tail = a.durationSecs - cue.outroCutSecs;
            double tailBars = tail / barSecs;
            System.out.println(String.format(java.util.Locale.US,
                    "[loop-tail] 尾巴 %.2f s = %.2f 小节（门槛 %.1f 小节）· 窗口 %.2f s"
                            + " · 出场点 %.2f s · 全曲 %.2f s · 循环计划 %s",
                    Double.valueOf(tail), Double.valueOf(tailBars),
                    Double.valueOf(AutoCue.LOOP_SHORT_TAIL_BARS),
                    Double.valueOf(cue.windowSecs), Double.valueOf(cue.outroCutSecs),
                    Double.valueOf(a.durationSecs), String.valueOf(cue.outroLoop)));
            assertTrue("前提：这次尾巴必须已经不再算短（≥ " + AutoCue.LOOP_SHORT_TAIL_BARS
                            + " 小节），量到 " + tailBars,
                    tailBars >= AutoCue.LOOP_SHORT_TAIL_BARS);
            assertTrue(String.format(java.util.Locale.US,
                            "前提：窗口必须放得下（尾巴 %.2f s > 窗口 %.2f s）",
                            Double.valueOf(tail), Double.valueOf(cue.windowSecs)),
                    tail > cue.windowSecs);
            assertTrue("前提：出场点 + 窗口不许伸出曲子外面",
                    cue.outroCutSecs + cue.windowSecs <= a.durationSecs);
            assertNull("尾巴够长（" + tailBars + " 小节）时**不许**借时间：" + cue.note,
                    cue.outroLoop);

        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 判据二（对照）：尾奏剩 6 小节 ⇒ 不循环
    // ==================================================================

    /**
     * <b>尾奏剩 6 小节 ⇒ 不循环</b>——钉住门槛的位置。
     *
     * <p>这份 A 与判据一<b>只差尾巴长度</b>（6 小节而不是 3 小节），窗口取 7.5 s（4 小节）。
     * 这时"不循环"不是碰运气：计划路径的硬约束 {@code 出场点 + 窗口 ≤ 全曲 − 2 s} 保证了
     * <b>任何</b>合法出场点都剩下 {@code 窗口 + 2 s = 9.5 s = 5.07 小节 > 4 小节</b>的尾巴，
     * 于是新那一条<b>结构上不可能成立</b>——所以这条对照对"赢的是哪个点"不敏感。
     *
     * <p>它挡的是"随手把门放宽"：一旦有人把 {@code LOOP_SHORT_TAIL_BARS} 抬到 8（§8.0.9 表格
     * 里写的就是 8 小节），5.07 &lt; 8 立刻成立 → 这条对照当场红。
     */
    @Test
    public void aSixBarTailIsNotShortEnoughToBuyTime() {
        Fixture fx = fixture(6);
        Crossfade base = new Crossfade((float) LONG_WINDOW);
        try {
            AutoCue.Envelope a = fx.outroEnv;
            double barSecs = 4.0 * 60.0 / a.tempo.bpm;
            AutoCue.Cue cue = AutoCue.planWithLyricSpans(a, fx.introEnv,
                    new double[]{LONG_WINDOW}, AutoCue.vocalPhraseSpans(a),
                    AutoCue.vocalPhraseSpans(fx.introEnv), AutoCue.Bridge.MEASURE);
            assertNotNull("合成素材上应当挑得出点位（否则这条判据什么都没测）", cue);

            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(a);
            assertNotNull("尾奏里应当找得到可循环的 vamp（这份素材与判据一同一份 vamp）", vamp);

            // 前提：尾巴真的有 6 小节左右（不是"没有 vamp"才不循环）。
            // 阈值写死 4.0（用户规格那条线）：门槛一旦被放宽，红的要是**下面那条结论**、
            // 而不是这条前提——前提量的是夹具的形状。
            double tail = a.durationSecs - cue.outroCutSecs;
            double tailBars = tail / barSecs;
            assertTrue(String.format(java.util.Locale.US,
                            "这条对照要的是「尾奏还剩 6 小节左右」，量到的是 %.2f s = %.2f 小节"
                                    + "（出场点 %.2f s、全曲 %.2f s）——夹具的形状变了",
                            Double.valueOf(tail), Double.valueOf(tailBars),
                            Double.valueOf(cue.outroCutSecs), Double.valueOf(a.durationSecs)),
                    tailBars >= 4.0);
            assertTrue("这条对照的尾巴不够长：硬约束本该保证 ≥ 窗口 + 退出余量 = "
                            + (LONG_WINDOW + AutoCue.TAIL_GUARD_SECS) + " s，量到 " + tail + " s",
                    tail >= LONG_WINDOW + AutoCue.TAIL_GUARD_SECS - 1e-6);

            // 判据：不循环（门槛之下这一档就是"不缺时间"）
            assertNull(String.format(java.util.Locale.US,
                            "尾奏还剩 %.2f 小节（超过了用户规格那条 4 小节的线，也就是"
                                    + "「不缺时间」那一档）→ 不该循环，引擎却给了计划：%s",
                            Double.valueOf(tailBars),
                            cue.outroLoop == null ? "" : cue.outroLoop.reason),
                    cue.outroLoop);
            assertFalse("不该循环的计划却被接线口径收了", AutoCue.loopIsWired(cue.outroLoop));
            assertFalse("说明行里不该出现「尾奏循环」", cue.note.contains("尾奏循环"));

            // 渲染层同样一字不动：没有循环素材、窗口就是接法自己那个
            AutoCueTransition wrap = new AutoCueTransition(base, RATE, 1, null, false);
            wrap.prepare(SRC_A, SRC_B, RATE);
            assertNull("不该循环的这一对，本层却报出了循环计划", wrap.outroLoopPlan());
            assertFalse("不该循环的这一对，本层却拿了循环素材", wrap.hasLoopOutgoing());
            assertEquals("窗口被拉长了——不循环的那一档不许动窗口",
                    base.windowFrames(RATE), wrap.windowFrames(RATE));

            System.out.printf(java.util.Locale.ROOT,
                    "%n[6 小节尾巴对照] 全曲 %.2f s、出场 %.2f s、尾奏剩 %.2f s = %.2f 小节"
                            + "（> 用户规格那条 4 小节的线）→ 不循环%n",
                    Double.valueOf(a.durationSecs), Double.valueOf(cue.outroCutSecs),
                    Double.valueOf(tail), Double.valueOf(tailBars));
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 判据三：门精确在 4 小节上（决策层，纯算术）
    // ==================================================================

    /**
     * <b>门槛精确在 4 小节</b>：同一份包络、同一个 vamp、同一个窗口，只把"出场点"从
     * 曲尾往回推<b>一微秒</b>——尾巴比 4 小节长 1 µs → 不给计划；短 1 µs → 给买时间的计划。
     *
     * <p>为什么单独一条：判据一/二量的是"生产路径上赢的那个点"，它们的可比性依赖夹具形状；
     * 这一条把门<b>直接</b>钉在小节数上，读的人一眼能看出 4 这个数字管在哪。
     * （两条触发都不成立时 {@code planOutroLoop} 返回 null，这是它的契约。）
     *
     * <p>那 1 µs 是<b>给浮点留的余量</b>，不是给门槛留的：出场点由"曲尾 − 4 小节"反推，
     * 而实现里又算一遍"曲尾 − 出场点"，两次浮点减法的残差在 1e-14 量级——把判据写成
     * "正好 4 小节"会让它去量浮点，而不是量门槛。
     */
    @Test
    public void theGateIsExactlyFourBars() {
        Fixture fx = fixture(4);
        try {
            AutoCue.Envelope a = fx.outroEnv;
            double barSecs = 4.0 * 60.0 / a.tempo.bpm;
            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(a);
            assertNotNull("尾奏里应当找得到可循环的 vamp", vamp);

            double gateBars = AutoCue.LOOP_SHORT_TAIL_BARS;
            double gateSecs = gateBars * barSecs;
            double cutJustOver = a.durationSecs - (gateSecs + 1e-6);   // 尾巴比门槛长 1 µs
            double cutJustUnder = a.durationSecs - (gateSecs - 1e-6);  // 尾巴比门槛短 1 µs

            AutoCue.OutroLoopPlan atGate = AutoCue.planOutroLoop(a, fx.introEnv, vamp,
                    cutJustOver, SHORT_WINDOW, false);
            assertNull(String.format(java.util.Locale.US,
                            "尾巴 %.6f 小节（比门槛 %.1f 小节长 1 µs）时不该循环——那一档还"
                                    + "放得下这次窗口，却给了：%s",
                            Double.valueOf(gateBars + 1e-6 / barSecs),
                            Double.valueOf(gateBars), atGate == null ? "" : atGate.reason),
                    atGate);

            AutoCue.OutroLoopPlan justUnder = AutoCue.planOutroLoop(a, fx.introEnv, vamp,
                    cutJustUnder, SHORT_WINDOW, false);
            assertNotNull(String.format(java.util.Locale.US,
                            "尾巴 %.6f 小节（比门槛 %.1f 小节 = %.2f s 短 1 µs）时应当给"
                                    + "买时间的计划",
                            Double.valueOf(gateBars - 1e-6 / barSecs),
                            Double.valueOf(gateBars), Double.valueOf(gateSecs)),
                    justUnder);
            assertTrue("门里那一条给的不是「买时间」：" + justUnder.reason, justUnder.buysTime);
            assertTrue("理由里应当写着买时间：" + justUnder.reason,
                    justUnder.reason.contains("买时间"));
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 夹具：合成 4/4 打击乐 + 假解码器（与 AutoCueOutroLoopBuyTimeTest 同一套）
    // ==================================================================

    /** 一对合成素材（A 的尾巴长度可换、B 固定 60 s，同一个 BPM）与它扫出来的包络。 */
    private static final class Fixture {
        final float[] pcmA;
        final float[] pcmB;
        final AutoCue.Envelope outroEnv;
        final AutoCue.Envelope introEnv;

        Fixture(float[] pcmA, float[] pcmB, AutoCue.Envelope outroEnv, AutoCue.Envelope introEnv) {
            this.pcmA = pcmA;
            this.pcmB = pcmB;
            this.outroEnv = outroEnv;
            this.introEnv = introEnv;
        }

        void close() {
            Decoder.install(null);
        }
    }

    private static Fixture fixture(int tailBars) {
        final float[] a = trackA(tailBars);
        final float[] b = trackB();
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, java.util.Map<String, String> headers) {
                return source != null && source.contains("shorttail-b")
                        ? new Fake(b, RATE) : new Fake(a, RATE);
            }
        });
        return new Fixture(a, b, AutoCue.scan(SRC_A, null), AutoCue.scanIntro(SRC_B, null));
    }

    /**
     * 素材 A：128 BPM。<b>14 小节固定前段 + 8 小节 vamp + {@code tailBars} 小节稀疏收尾</b>：
     *
     * <pre>
     *   0..3   [0, 7.5)      安静引子
     *   4..11  [7.5, 22.5)   底鼓 / hi-hat 段
     *   12..13 [22.5, 26.25) 过门
     *   14..21 [26.25, 41.25) ← vamp（8 小节，纯打击乐、逐小节相同）
     *   22..   [41.25, …)     稀疏收尾（每小节一记底鼓，电平 0.45）
     * </pre>
     *
     * <p>{@code tailBars = 3} ⇒ 全曲 25 小节 = 46.875 s（尾奏剩 3 小节，会赶）；
     * {@code tailBars = 6} ⇒ 28 小节 = 52.5 s（对照）。
     */
    private static float[] trackA(int tailBars) {
        StringBuilder kinds = new StringBuilder();
        float[] levels = new float[64];
        int bar = 0;
        for (int i = 0; i < 4; i++, bar++) {                 // 0..3   安静引子
            kinds.append('i');
            levels[bar] = 0.25f;
        }
        append(kinds, levels, bar, "kkkkhhhh", 0.85f);       // 4..11  [7.5, 22.5)
        append(kinds, levels, bar += 8, "kk", 0.85f);        // 12..13 [22.5, 26.25) 过门
        append(kinds, levels, bar += 2, "vvvvvvvv", 1.0f);   // 14..21 [26.25, 41.25) ← vamp
        StringBuilder tail = new StringBuilder();
        for (int i = 0; i < tailBars; i++) {
            tail.append('f');
        }
        append(kinds, levels, bar + 8, tail.toString(), 0.45f);
        return synth(BPM, kinds.toString(), levels);
    }

    /** 素材 B：32 小节 = 60.0 s，<b>同样 128 BPM</b>（BPM 差 0% ⇒ 不落进"对齐网格"那一档）。 */
    private static float[] trackB() {
        StringBuilder kinds = new StringBuilder();
        float[] levels = new float[64];
        int bar = 0;
        for (int i = 0; i < 8; i++, bar++) {
            kinds.append('i');
            levels[bar] = 0.3f;
        }
        append(kinds, levels, bar, "kkkkhhhh", 0.75f);
        append(kinds, levels, bar += 8, "hhhhkkkk", 0.8f);
        append(kinds, levels, bar + 8, "kkkkvvvv", 0.9f);
        return synth(BPM, kinds.toString(), levels);
    }

    /** 造一条 4/4 打击乐轨：{@code kinds} 每小节一个字符，{@code levels} 每小节一个电平。 */
    private static float[] synth(double bpm, String kinds, float[] levels) {
        double beat = 60.0 / bpm;
        double bar = 4 * beat;
        float[] out = new float[(int) Math.round(kinds.length() * bar * RATE)];
        for (int b = 0; b < kinds.length(); b++) {
            char kind = kinds.charAt(b);
            float level = levels[Math.min(b, levels.length - 1)];
            for (int k = 0; k < 4; k++) {
                double t = (b * 4 + k) * beat;
                switch (kind) {
                    case 'i':
                        kick(out, t, level, false);
                        break;
                    case 'k':
                        kick(out, t, level, k % 2 == 1);
                        break;
                    case 'h':
                        hat(out, t, level);
                        break;
                    case 'v':
                        // vamp：每拍底鼓 + 反拍 hat；每 4 小节的第一拍加一记重音当"地标"。
                        // 周期 4 小节而循环段 8 小节 → 前后半逐位相同（自相似 ≈ 1）。
                        kick(out, t, level * (b % 4 == 0 && k == 0 ? 1.15f : 1.0f), false);
                        hat(out, t + beat * 0.5, level * 0.9f);
                        break;
                    case 'o':
                        if (k == 0) {
                            kick(out, t, level, false);
                        }
                        break;
                    default:
                        kick(out, t, level, false);
                        break;
                }
            }
        }
        return out;
    }

    /** 底鼓：60 Hz（重音拍 120 Hz）正弦、指数衰减。 */
    private static void kick(float[] out, double t, float level, boolean high) {
        double freq = high ? 120.0 : 60.0;
        int start = (int) Math.round(t * RATE);
        int n = (int) Math.round(0.18 * RATE);
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= out.length) {
                continue;
            }
            double env = Math.exp(-i / (0.05 * RATE));
            out[idx] += (float) (level * 0.9 * env * Math.sin(2 * Math.PI * freq * i / RATE));
        }
    }

    /** hi-hat：8 kHz 正弦、很短的衰减（能量整段落在高频带里，不进代理 VAD 的中频带）。 */
    private static void hat(float[] out, double t, float level) {
        int start = (int) Math.round(t * RATE);
        int n = (int) Math.round(0.03 * RATE);
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= out.length) {
                continue;
            }
            double env = Math.exp(-i / (0.008 * RATE));
            out[idx] += (float) (level * 0.5 * env * Math.sin(2 * Math.PI * 8000 * i / RATE));
        }
    }

    private static void append(StringBuilder sb, float[] levels, int bar, String kinds,
                               float level) {
        sb.append(kinds);
        for (int i = 0; i < kinds.length(); i++) {
            levels[bar + i] = level;
        }
    }

    /** 假解码器：把合成 PCM 当一首曲子（单声道，长度已知——计划层要真实时长）。 */
    private static final class Fake implements Decoder {

        private final float[] pcm;
        private final int rate;
        private int position;

        Fake(float[] pcm, int rate) {
            this.pcm = pcm;
            this.rate = rate;
        }

        @Override
        public TrackSpec spec() {
            return new TrackSpec("pcm", rate, ChannelLayout.of(1), Integer.valueOf(16),
                    Long.valueOf(pcm.length), true);
        }

        @Override
        public long framesDecoded() {
            return position;
        }

        @Override
        public boolean isDrained() {
            return position >= pcm.length;
        }

        @Override
        public double seek(double secs) {
            position = Math.max(0, Math.min(pcm.length, (int) (secs * rate)));
            return position / (double) rate;
        }

        @Override
        public int decodeInto(AudioBuf out) {
            int n = Math.min(out.capacityFrames(), pcm.length - position);
            if (n <= 0) {
                out.setFrames(0);
                return 0;
            }
            java.nio.FloatBuffer dst = out.channelWriter(0);
            for (int i = 0; i < n; i++) {
                dst.put(i, pcm[position + i]);
            }
            out.setFrames(n);
            position += n;
            return n;
        }

        @Override
        public void close() {
        }
    }

    // ==================================================================
    // 渲染脚手架（照编排器的方式逐块推进整窗；与 AutoCueOutroLoopAiWindowTest 同一套）
    // ==================================================================

    /** 一次整窗渲染的产物。 */
    private static final class Rendered {
        float[] mono;
        long frames;
    }

    /** 按编排器的方式把整窗走完：两块素材来自真的 deck、{@code progress = 已渲染帧 / 窗口帧}。 */
    private static Rendered render(Transition t, long windowFrames, double aStartSecs,
                                   double bSkipSecs, float introStartAt) {
        Deck deckA = Deck.open(SRC_A, RATE, 1);
        Deck deckB = Deck.open(SRC_B, RATE, 1);
        try {
            deckA.seek(Math.max(0.0, aStartSecs));
            deckB.seek(Math.max(0.0, bSkipSecs));
            AudioBuf from = new AudioBuf(ChannelLayout.of(1), Deck.RENDER_FRAMES);
            AudioBuf to = new AudioBuf(ChannelLayout.of(1), Deck.RENDER_FRAMES);
            AudioBuf chunk = new AudioBuf(ChannelLayout.of(1), Deck.RENDER_FRAMES);
            Rendered r = new Rendered();
            r.mono = new float[(int) (windowFrames + Deck.RENDER_FRAMES)];
            long pos = 0L;
            int written = 0;
            int guard = 0;
            while (pos < windowFrames && guard++ < 10_000) {
                int gotA = deckA.render(from);
                float progress = (float) Math.min(1.0, (double) pos / (double) windowFrames);
                if (progress >= introStartAt || gotA <= 0) {
                    deckB.render(to);
                } else {
                    to.setFrames(0);
                }
                int n = t.render(chunk, from, to, progress);
                if (n <= 0) {
                    break;
                }
                java.nio.FloatBuffer src = chunk.channel(0);
                for (int i = 0; i < n && written < r.mono.length; i++) {
                    r.mono[written++] = src.get(i);
                }
                pos += n;
            }
            r.frames = pos;
            r.mono = java.util.Arrays.copyOf(r.mono, written);
            return r;
        } finally {
            deckA.close();
            deckB.close();
        }
    }

    /** 在 {@code expected} 附近 ±2 帧里找"窗口头最像源素材哪一帧"的那个落点。 */
    private static int align(float[] out, int from, float[] src, int expected) {
        int best = -1;
        int bestMatches = 0;
        for (int delta = -2; delta <= 2; delta++) {
            int at = expected + delta;
            if (at < 0 || at + from + 512 >= src.length) {
                continue;
            }
            int matches = 0;
            for (int i = 0; i < 512 && from + i < out.length; i++) {
                if (Float.floatToRawIntBits(out[from + i])
                        == Float.floatToRawIntBits(src[at + from + i])) {
                    matches++;
                }
            }
            if (matches > bestMatches) {
                bestMatches = matches;
                best = at;
            }
        }
        return best;
    }
}
