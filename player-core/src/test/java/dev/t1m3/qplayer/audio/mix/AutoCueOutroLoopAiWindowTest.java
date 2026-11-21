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
 * <b>AI 模式（用户设的「自动 · 自动点位」）下，「买时间」那一档的尾奏循环真的生效了</b>
 * （2026-10-05；用户原话：「你直接做成品」「我要最完美的效果」）。
 *
 * <h2>它修的是什么</h2>
 *
 * <p>尾奏循环的渲染那一半早就写好了（{@code AutoCueTransition.prepareLoopOutgoing} /
 * {@code renderLooped}），"买时间"这一档在计划层也真的能触发
 * （判据 {@code AutoCueOutroLoopBuyTimeTest}）。可它在<b>用户实际用的那个模式</b>下一次都
 * 走不到：AI 模式的窗口由风格/拍数（{@code AiMix} 挑出来的方案）定，
 * 而渲染那一层原来对"没有资格决定窗口"的情况一律退回普通交叉淡化
 * （{@code AutomixSettings.maybeAutoCue} 的 AI 分支既不传现读供应商、也不开
 * {@code tuneWindow}）——于是<strong>用户看到的循环等于不存在</strong>。
 *
 * <p>修法是给"买时间"这一档单开一道门：这一档的触发条件本身就是
 * {@code needsTime = 尾奏剩余 < 这次窗口}（见 {@code AutoCue.planOutroLoop}），
 * 也就是"时间本来就不够"，于是把窗口<strong>延长</strong>成
 * <b>一遍循环段 × 重复次数</b>是它的分内事。判据分两条，正好把这道门的两个方向都钉住：
 *
 * <ol>
 *   <li>{@link #aiModeBuysTimeLoopExtendsTheWindowAndUsesTheLoopMaterial}：
 *       <b>走生产那条路</b>（{@code AutoCueTransition.prepare} 自己挑点、自己决定循环），
 *       断言窗口真的被延长成"一遍循环段 × 重复次数"、循环素材真的被用上（窗口里的 A
 *       与循环段起点处的源 PCM 逐位相同）、第二遍是第一遍的重复，而且与"同样窗口的普通
 *       交叉淡化"渲染结果不同（循环路径确实被走上了，不是只把窗口拉长）；</li>
 *   <li>{@link #aiModeOnlyBuysTimeAndNeverShortens}：<b>这道门只对「买时间」开、
 *       而且只许拉长</b>——同样一份素材、同样一份计划形状，只把
 *       {@code buysTime} 这一个标志位翻过来（"对齐网格"那一类），循环就不许生效；
 *       窗口比一遍循环段还长的"买时间"（那会把过渡变短）也不许生效。</li>
 * </ol>
 *
 * <h2>素材：自己造的，答案是已知的</h2>
 *
 * <p>与 {@code AutoCueOutroLoopBuyTimeTest} 同一份合成素材（4/4 打击乐，振荡器只有
 * 60/120 Hz 与 8 kHz 正弦 → 代理 VAD 判"没人唱"）：<b>A = 128 BPM、24 小节 = 45.0 s</b>，
 * 可循环的 8 小节 vamp 落在 [26.25, 41.25)，它之后只有 2 小节稀疏收尾
 * （<b>尾奏只剩 3.75 s，放不下 7.5 s 窗口</b>）；<b>B = 32 小节 = 60.0 s、同样 128 BPM</b>
 * （BPM 差 0% ⇒ "对齐网格"那一条不成立，触发只剩"买时间"）。于是
 * {@code AutoCue.planOutroLoop} 在 vamp 终点那个点位上给出
 * {@code buysTime = true} 的计划，{@code Cue.outroLoop} 跟着落地。
 *
 * <p>合成方法的实现是 private（各判据各抄一份，这个包的惯例），所以这里也抄了一份。
 */
@Ignore("legacy：旧决策链的判据；忠实移植 Apple 决策链期间停用 "
        + "（旧逻辑快照在 git tag automix-legacy-20261121，还原后可恢复）")
public class AutoCueOutroLoopAiWindowTest {

    private static final int RATE = 22050;

    /** 用户设的交叠时长（秒）：在这份素材上正好 4 小节（1.875 s × 4）。 */
    private static final double NOMINAL_WINDOW = 7.5;

    /** A 与 B 同一个 BPM：把"对齐网格"那一档排除掉，触发只剩"买时间"这一条。 */
    private static final double BPM = 128.0;

    private static final String SRC_A = "test://ailoop-a";
    private static final String SRC_B = "test://ailoop-b";

    // ==================================================================
    // 判据一：AI 模式下，买时间的循环**延长窗口、用上循环素材**
    // ==================================================================

    /**
     * <b>AI 模式口径 + 买时间 ⇒ 窗口被延长、循环素材被用上</b>。
     *
     * <p>口径就是 {@code AutomixSettings.maybeAutoCue} 里 AI 分支造出来的那两个值：
     * <b>现读供应商 {@code null}、{@code tuneWindow = false}</b>
     * （{@code new AutoCueTransition(base, RATE, 1, null, false)}）。
     * 素材是"尾奏放不下窗口、里面有一段 8 小节干净 vamp"的那一份，所以计划层给的
     * 就是 {@code buysTime = true} 的循环计划——<b>整条路都是生产的</b>：
     * 挑点、决策、循环、渲染都由 {@code prepare} 与 {@code render} 自己走，
     * 判据只读结论。
     *
     * <p>断言五件事：
     *
     * <ol>
     *   <li><b>前提</b>：计划层在这一对素材上给的确实是「买时间」那一类
     *       （否则这条判据什么都没测）；</li>
     *   <li><b>接住了</b>：AI 模式下 {@code outroLoopPlan() != null}、
     *       {@code hasLoopOutgoing()} 为真（拿了自己的循环素材）；</li>
     *   <li><b>窗口被延长</b>：报给编排器的窗口 &gt; 被包接法的窗口，而且延长的量
     *       正好是「一遍循环段（整数小节）× 重复次数」；同时与 B 真正交叠的那一段
     *       仍然是"用户设的窗口吸到整数小节"（<b>只延长、不缩短</b>的读数）；</li>
     *   <li><b>素材真的换了</b>：整窗渲染出来的窗口头与源 PCM 在<b>循环段起点</b>处
     *       逐位相同（A 被倒回 vamp 重播），第二遍是第一遍的逐位重复；</li>
     *   <li><b>不是普通淡化</b>：同样窗口长度、同样起点跑一遍"只包交叉淡化"的渲染，
     *       两份 PCM 必须<b>不同</b>——这一条排除"窗口被拉长、但素材还是原来那份"
     *       那种假绿。</li>
     * </ol>
     */
    @Test
    public void aiModeBuysTimeLoopExtendsTheWindowAndUsesTheLoopMaterial() {
        AiLoopFixture fx = fixture();
        Crossfade base = new Crossfade((float) NOMINAL_WINDOW);
        try {
            // ── 前提：计划层给的正是「买时间」那一类 ────────────────────────
            AutoCue.Cue cue = AutoCue.planWithLyricSpans(fx.outroEnv, fx.introEnv,
                    new double[]{NOMINAL_WINDOW}, AutoCue.vocalPhraseSpans(fx.outroEnv),
                    AutoCue.vocalPhraseSpans(fx.introEnv), AutoCue.Bridge.MEASURE);
            assertNotNull("合成素材上应当挑得出点位（否则这条判据什么都没测）", cue);
            assertNotNull("这一对素材上计划层应当给出循环计划（尾奏放不下窗口、里面有一段"
                    + "可循环 vamp）", cue.outroLoop);
            assertTrue("计划层给的不是「买时间」那一类 → 这道门本来就不该开："
                    + cue.outroLoop.reason, AutoCue.loopIsWired(cue.outroLoop));
            assertTrue("买时间的理由里应当写着买时间：" + cue.outroLoop.reason,
                    cue.outroLoop.reason.contains("买时间"));

            // ── AI 模式口径：不传现读供应商、也不许调窗口 ────────────────────
            AutoCueTransition wrap = new AutoCueTransition(base, RATE, 1, null, false);
            assertFalse("这一条判据要的是 AI 模式口径（不许按分析调窗口）", wrap.tunesWindow());
            TransitionPlan plan = wrap.prepare(SRC_A, SRC_B, RATE);

            // ── 接住了 ────────────────────────────────────────────────────
            assertNotNull("AI 模式下「买时间」的循环计划没有被接住（renderLooped 走不到）",
                    wrap.outroLoopPlan());
            assertTrue("AI 模式下没有拿到自己的循环素材（窗口里的 A 还是原来那份）",
                    wrap.hasLoopOutgoing());

            // ── 窗口被延长：延长量 = 一遍循环段 × 重复次数 ──────────────────
            AutoCue.Envelope a = fx.outroEnv;
            double barSecs = 4.0 * 60.0 / a.tempo.bpm;
            long plainWindow = base.windowFrames(RATE);
            long window = wrap.windowFrames(RATE);
            long pass = Math.round(cue.outroLoop.bars * barSecs * RATE);
            assertTrue("窗口没有被延长（报给编排器的是 " + window + " 帧，风格/接法给的是 "
                            + plainWindow + " 帧）——AI 模式下买时间的循环没有生效",
                    window > plainWindow);
            assertEquals("延长的量不是「一遍循环段 × 重复次数」",
                    cue.outroLoop.repeats * pass, window);
            assertEquals("报给编排器的窗口不是循环窗口", wrap.loopWindowFrames(), window);
            assertTrue("循环窗口不是整数小节（" + window + " 帧 / 一遍 " + pass + " 帧）",
                    Math.abs((double) window / (double) pass
                            - Math.round((double) window / (double) pass)) < 1e-9);
            // 「只延长、不缩短」的读数：与 B 真正交叠的那一段仍是用户设的窗口吸到整数小节。
            // 容差半小节——"吸到小节线"这件事本身的代价：实测这份素材量到 128.0012 BPM，
            // 一遍 8 小节 = 165373 帧，而 7.5 s = 165375 帧，交叠因此少 2 帧（不是被截短，
            // 见实现里那道闸的注释）。
            assertEquals("与 B 交叠的那一段不是「用户设的窗口吸到整数小节」"
                            + "（窗口被延长了，但交叠被人改动过）",
                    Math.round(NOMINAL_WINDOW / barSecs) * barSecs * RATE,
                    (double) wrap.loopFadeFrames(), 1.0);
            assertTrue("交叠那一段比用户设的窗口短了半小节以上（交叠 " + wrap.loopFadeFrames()
                            + " 帧 vs 窗口 " + plainWindow + " 帧，一小节 "
                            + (barSecs * RATE) + " 帧）——那就不是「只延长」",
                    Math.abs(wrap.loopFadeFrames() - plainWindow) < 0.51 * barSecs * RATE);

            // ── 编排器那边接得上：触发点 = 全曲 − 窗口 − 丢尾 ────────────────
            double windowSecs = window / (double) RATE;
            double aStart = a.durationSecs - windowSecs - plan.outroTailSecs;
            assertTrue("延长后的触发点跑到曲子外面去了（" + aStart + " s）——这条判据构造的"
                    + "形状要求它在曲子里面", aStart >= 0.0);

            // ── 素材真的换了：窗口头 = 循环段（从 v0 重播） ──────────────────
            Rendered wired = render(wrap, window, aStart, plan.skipSecs, plan.introStartAt);
            assertTrue("整窗渲染几乎是空的（" + wired.frames + " / " + window + " 帧）",
                    wired.frames > window - Deck.RENDER_FRAMES);
            double phase = AutoCue.barPhaseOf(a, barSecs);
            double v0 = AutoCueTransition.snapToBar(cue.outroLoop.span.from, phase, barSecs);
            int seamFrames = (int) Math.round(AutoCueTransition.LOOP_SEAM_FADE_SECS * RATE);
            int insertFrames = (int) (window - wrap.loopFadeFrames());
            int srcV0 = (int) Math.round(v0 * RATE);
            int alignV0 = align(wired.mono, seamFrames + 64, fx.pcmA, srcV0);
            assertTrue("窗口头对不上循环段起点（落在 " + alignV0 + "，期望 " + srcV0
                            + "）——A 没有倒回 vamp 重播，循环素材没被用上",
                    alignV0 >= 0 && Math.abs(alignV0 - srcV0) <= 2);
            // 第一遍（整段都在"只放 A"那一段里）逐位比
            int firstMismatch = -1;
            int checked = 0;
            for (int i = seamFrames + 1; i < pass; i++) {
                checked++;
                if (Float.floatToRawIntBits(wired.mono[i])
                        != Float.floatToRawIntBits(fx.pcmA[alignV0 + i])) {
                    firstMismatch = i;
                    break;
                }
            }
            assertEquals("窗口里的 A 素材与循环段的样本不一致（第一个不同在下标 "
                            + firstMismatch + "）——重播的不是同一份素材", -1, firstMismatch);
            assertTrue("比对的采样太少（" + checked + "），这条判据没测到东西",
                    checked > pass / 2);
            // 第二遍（还在"只放 A"那一段里，绕回 v0 重来）逐位比 → 重复是真重复
            int repeatMismatch = -1;
            int repeatChecked = 0;
            for (int i = (int) pass + seamFrames + 1; i < insertFrames; i++) {
                repeatChecked++;
                if (Float.floatToRawIntBits(wired.mono[i])
                        != Float.floatToRawIntBits(fx.pcmA[alignV0 + (i - (int) pass)])) {
                    repeatMismatch = i;
                    break;
                }
            }
            assertEquals("第二遍与第一遍不是同一份样本（第一个不同在下标 " + repeatMismatch
                    + "）——重复没有按「只读一遍、游标绕回」的方式做", -1, repeatMismatch);
            assertTrue("第二遍几乎没比（" + repeatChecked + "），这条判据没测到东西",
                    repeatChecked > (insertFrames - pass) / 2);

            // ── 不是普通淡化：同样窗口、同样起点，两份 PCM 必须不同 ──────────
            Rendered plain = render(base, window, aStart, plan.skipSecs, plan.introStartAt);
            assertTrue("窗口被拉长了，但渲染结果与「同样窗口的普通交叉淡化」逐位相同"
                            + "——循环路径根本没有被走上",
                    firstDifference(plain.mono, wired.mono) >= 0);
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 判据二：这道门只对「买时间」开，而且只许拉长
    // ==================================================================

    /**
     * <b>同一份素材、同一份计划形状，只翻 {@code buysTime} 这一个标志位</b>：
     * 「对齐网格」那一类在 AI 模式下照旧不循环（窗口一个字节都不动），
     * 而"买时间但一遍循环段比窗口还短"（那会把过渡<b>变短</b>）同样不许生效。
     *
     * <p>为什么用直接喂 {@code Cue} 的办法（{@code prepareLoopOutgoing} 是包内可见的，
     * 就是为这种判据留的）：生产路径上 {@code Cue.outroLoop} 只装"买时间"那一类
     * （接线口径 {@code AutoCue.loopIsWired} 挡在前面），所以另外两种情况在生产路径上
     * <b>根本出现不了</b>——而"这道门关着"正是它们该有的结论，必须能单独钉住。
     * 走的是<strong>同一份实现</strong>，只是绕过了那个上游的过滤。
     *
     * <p>三段断言各自回答一件事：
     *
     * <ol>
     *   <li>{@code buysTime = false}（对齐网格，8 小节、窗口 7.5 s）→ 不循环、窗口不动
     *       ——「只在买时间时这么做」；</li>
     *   <li>{@code buysTime = true} 但一遍只有 <b>4 小节</b>（7.5 s）而窗口是 <b>12 s</b>
     *       → 不循环、窗口不动——「延长而不是缩短」（那会给出一个更短的过渡）；</li>
     *   <li>把同一份 4 小节计划配上 <b>7.5 s</b> 窗口（正好等于一遍循环段）→ <b>生效</b>。
     *       这一条是上一条的对照组：它证明第 ②段红的是<b>长度</b>，不是"这一段 vamp
     *       压根用不了"。</li>
     * </ol>
     */
    @Test
    public void aiModeOnlyBuysTimeAndNeverShortens() {
        AiLoopFixture fx = fixture();
        Crossfade base = new Crossfade((float) NOMINAL_WINDOW);
        try {
            AutoCue.Envelope a = fx.outroEnv;
            double barSecs = 4.0 * 60.0 / a.tempo.bpm;
            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(a);
            assertNotNull("合成素材上应当找得到可循环的 vamp", vamp);

            // ① 「对齐网格」那一类：buysTime = false，其余一样
            AutoCueTransition grid = new AutoCueTransition(base, RATE, 1, null, false);
            grid.prepareLoopOutgoing(SRC_A, null, a, cueOf(vamp, 8, 1, false), NOMINAL_WINDOW);
            assertNull("「对齐网格」那一类在 AI 模式下也被接了——它没有「时间不够」这回事",
                    grid.outroLoopPlan());
            assertFalse("「对齐网格」那一类在 AI 模式下被接了", grid.hasLoopOutgoing());
            assertEquals("不循环时窗口被动了", base.windowFrames(RATE), grid.windowFrames(RATE));

            // ② 买时间，但一遍循环段（4 小节 = 7.5 s）比窗口（12 s）短 → 会把过渡变短
            AutoCueTransition tooShort = new AutoCueTransition(base, RATE, 1, null, false);
            tooShort.prepareLoopOutgoing(SRC_A, null, a, cueOf(vamp, 4, 1, true), 12.0);
            assertNull("一遍循环段比窗口还短的「买时间」被接了——那给出的过渡比用户设的窗口"
                    + "更短，不是买时间", tooShort.outroLoopPlan());
            assertFalse("一遍循环段比窗口还短的「买时间」被接了", tooShort.hasLoopOutgoing());
            assertEquals("不循环时窗口被动了", base.windowFrames(RATE),
                    tooShort.windowFrames(RATE));

            // ③ 对照组：同一份 4 小节计划配上 7.5 s 窗口（= 一遍循环段）→ 生效
            AutoCueTransition fits = new AutoCueTransition(base, RATE, 1, null, false);
            fits.prepareLoopOutgoing(SRC_A, null, a, cueOf(vamp, 4, 1, true), NOMINAL_WINDOW);
            assertNotNull("一遍循环段不短于窗口的「买时间」却没生效——上面那两条红的"
                    + "就不是长度问题了", fits.outroLoopPlan());
            assertTrue("一遍循环段不短于窗口的「买时间」却没拿到素材", fits.hasLoopOutgoing());
            assertEquals("窗口不是一遍循环段（4 小节）",
                    Math.round(4 * barSecs * RATE), fits.windowFrames(RATE));
        } finally {
            fx.close();
        }
    }

    /** 判据用的那份 {@code Cue}：点位与窗口都不参与这两条断言，装的是计划形状。 */
    private static AutoCue.Cue cueOf(AutoCue.VampSpan vamp, int bars, int repeats,
                                     boolean buysTime) {
        return new AutoCue.Cue(0.0, 0.0, NOMINAL_WINDOW, 0.0, 0.0, "判据", 0.5, true,
                new AutoCue.OutroLoopPlan(vamp, bars, repeats, buysTime,
                        buysTime ? "判据：买时间" : "判据：对齐网格"));
    }

    // ==================================================================
    // 夹具：合成素材 + 假解码器（与 AutoCueOutroLoopBuyTimeTest 同一份）
    // ==================================================================

    /** 一对合成素材（A 固定 45 s、B 60 s，同一个 BPM）与它扫出来的包络。 */
    private static final class AiLoopFixture {
        final float[] pcmA;
        final float[] pcmB;
        final AutoCue.Envelope outroEnv;
        final AutoCue.Envelope introEnv;

        AiLoopFixture(float[] pcmA, float[] pcmB, AutoCue.Envelope outroEnv,
                     AutoCue.Envelope introEnv) {
            this.pcmA = pcmA;
            this.pcmB = pcmB;
            this.outroEnv = outroEnv;
            this.introEnv = introEnv;
        }

        void close() {
            Decoder.install(null);
        }
    }

    private static AiLoopFixture fixture() {
        final float[] a = trackA();
        final float[] b = trackB();
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, java.util.Map<String, String> headers) {
                return source != null && source.contains("ailoop-b")
                        ? new Fake(b, RATE) : new Fake(a, RATE);
            }
        });
        return new AiLoopFixture(a, b, AutoCue.scan(SRC_A, null), AutoCue.scanIntro(SRC_B, null));
    }

    /**
     * 素材 A：128 BPM、24 小节 = 45.0 s。可循环的 vamp 落在 [26.25, 41.25)（8 小节 =
     * 15.00 s），它之后只有 2 小节稀疏收尾 —— <b>尾奏剩 3.75 s，放不下 7.5 s 窗口</b>。
     */
    private static float[] trackA() {
        StringBuilder kinds = new StringBuilder();
        float[] levels = new float[32];
        int bar = 0;
        for (int i = 0; i < 4; i++, bar++) {                 // 0..3   [0, 7.5)  安静引子
            kinds.append('i');
            levels[bar] = 0.25f;
        }
        append(kinds, levels, bar, "kkkkhhhh", 0.85f);       // 4..11  [7.5, 22.5)
        append(kinds, levels, bar += 8, "kk", 0.85f);        // 12..13 [22.5, 26.25) 过门
        append(kinds, levels, bar += 2, "vvvvvvvv", 1.0f);   // 14..21 [26.25, 41.25) ← vamp
        append(kinds, levels, bar + 8, "ff", 0.45f);         // 22..23 [41.25, 45.0) 稀疏收尾
        return synth(BPM, kinds.toString(), levels);
    }

    /** 素材 B：32 小节 = 60.0 s，<b>同样 128 BPM</b>（BPM 差 0% ⇒ 不落进"对齐网格"）。 */
    private static float[] trackB() {
        StringBuilder kinds = new StringBuilder();
        float[] levels = new float[32];
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
                        // 周期 4 小节而循环段是 8 小节 → 前后半逐位相同（自相似 ≈ 1）。
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
    // 渲染脚手架（照编排器的方式逐块推进整窗；与 AutoCueOutroLoopTest 同一套）
    // ==================================================================

    /** 一次整窗渲染的产物。 */
    private static final class Rendered {
        float[] mono;
        long frames;
    }

    /**
     * 按编排器的方式把整窗走完：两块素材来自真的 deck，{@code progress = 已渲染帧 / 窗口帧}，
     * 进场曲按 {@code introStartAt} **挡在门外**（循环那一档的前半段正是靠它"只放 A"）。
     *
     * <p>{@code aStartSecs} 由调用方按编排器的触发点算（"这一曲还剩 窗口 + 丢尾 时开始"），
     * 所以这里走的是"曲中某个位置起，整窗走完"——买时间那一档的窗口比尾奏长，
     * 它的成立前提之一正是"deck 还有整窗的帧"。
     */
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

    /** 第一个不同的采样下标（逐位比较；完全相同返回 -1）。 */
    private static int firstDifference(float[] a, float[] b) {
        if (a.length != b.length) {
            return Math.min(a.length, b.length);
        }
        for (int i = 0; i < a.length; i++) {
            if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])) {
                return i;
            }
        }
        return -1;
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

    /**
     * 这一组判据比的是**接法机制本身**（交叉淡化/分段/循环），与"融合期不炸"那套
     * （留白 + 前瞻软限幅）无关 —— 后者默认开、会改变窗口内的采样，于是这些
     * "与朴素交叉淡化逐位相同"的断言必然红。所以这里显式关掉它再比，
     * 保证这几条判据测的仍然是它们本来要测的东西（2026-10-07）。
     */
    @org.junit.Before
    public void disableAntiClipForMechanismJudges() {
        dev.t1m3.qplayer.audio.mix.AutoCueTransition.setAntiClipEnabled(false);
    }

    /** 还原：产品默认是开的，别影响同 JVM 里的其它判据。 */
    @org.junit.After
    public void restoreAntiClip() {
        dev.t1m3.qplayer.audio.mix.AutoCueTransition.setAntiClipEnabled(true);
    }
}
