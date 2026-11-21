package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;

import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>尾奏循环接进渲染路径的判据</b>（2026-10-05；用户：「你这一套可以 你可以接入了
 * 自己测试 可以跑全盘测试 自己找最优 全力接近苹果的」）。
 *
 * <h2>素材是自己造的，答案是已知的</h2>
 *
 * <p>循环这件事的"正确答案"最终只能靠听，而听不能进 CI。所以这里用<strong>合成的 4/4
 * 打击乐</strong>造出<strong>结构已知</strong>的一对曲子：
 *
 * <ul>
 *   <li>A：128 BPM、105 s，每 8 小节一块 —— 安静引子 / 底鼓块 / hi-hat 块 / 底鼓块 /
 *       <b>vamp（[60, 75)，每拍底鼓 + 反拍 hat、逐小节相同 → 前后半自相似 1.00、
 *       电平稳、没有人唱）</b>/ 稀疏收尾 / 收尾后段。振荡器只有 60~120 Hz 与 8 kHz 正弦，
 *       所以代理 VAD（200–2000 Hz 那条带）判"没人唱"——与真素材上 vamp 的处境一样；</li>
 *   <li>B：同样的编排，BPM 可换。BPM 差是这一档唯一能被触发的尺子
 *       （3%~8% 才循环，见 {@code AutoCue.planOutroLoop}）：
 *       <b>124 BPM（差 3.1%）→ 落进那一档</b>；<b>128.4 BPM（差 0.3%）→ 什么都不触发</b>。
 *       两条判据用<strong>同一份 A</strong>，只换 B 的 BPM —— 变量只有一个。</li>
 * </ul>
 *
 * <h2>这四条判据各自钉什么</h2>
 *
 * <ol>
 *   <li>{@link #withoutALoopDecisionTheRenderingIsBitIdenticalToThePlainCrossfade}：
 *       <b>不需要循环时，点位与渲染与接线之前逐位相同</b>（"不改变现有默认行为"那条红线）；</li>
 *   <li>{@link #theGridClassDecisionIsDeliberatelyNotWired}：
 *       <b>"对齐网格"那一类循环有意不接线</b>——决策层会给计划，但接线口径
 *       （{@code AutoCue.loopIsWired}）不收它。这条判据就是「skydive → 春めく 不循环」
 *       这条结论的可执行版本（实测那对曲子 BPM 128.00 / 119.97 = 差 6.27%，正落在这一类里，
 *       见 {@code AutoCue.loopIsWired} 的注释）；</li>
 *   <li>{@link #aTimeClassLoopRepeatsTheVampFromItsBarAlignedStart}：
 *       <b>需要循环时，窗口里放的确实是被重复的那一段</b>：A 倒回循环段起点重播
 *       （与源 PCM 逐位相同）、循环点与淡化起点都落在小节线上、内层接法看到的进度在
 *       "只放 A"那一段恒为 0（B 不进来）、到循环段末尾才到 1；</li>
 *   <li>{@link #repeatedPassesAreBitIdenticalToTheFirstOne}：
 *       <b>重复 {@code repeats} 遍是真的重复</b>（第二遍与第一遍逐位相同）。</li>
 * </ol>
 *
 * <p>判据三、四要的那份 {@code Cue} 是<strong>直接造出来喂进渲染层</strong>的
 * （{@code AutoCueTransition.prepareLoopOutgoing} 是包内可见的，正是为这种判据留的）：
 * 生产路径现在拿不到 {@code buysTime = true} 的计划——{@code planCollect} 的硬约束
 * （出场点 + 交叠 ≤ 全曲 − {@value AutoCue#TAIL_GUARD_SECS} s）保证尾奏永远放得下窗口，
 * 所以"买时间"那一类在设计上就走不到（探针 {@code LoopDecisionProbe} 在本机 6 对素材上
 * 实测 {@code needsTime} 全为 false）。渲染那一半不能因此没有判据，所以这里按"计划层会给
 * 的形状"直接把 {@code Cue} 喂进去——走的是<strong>同一份实现</strong>，只是绕过了
 * 决定不接的那一类决策。
 */
@Ignore("legacy：旧决策链的判据；忠实移植 Apple 决策链期间停用 "
        + "（旧逻辑快照在 git tag automix-legacy-20261121，还原后可恢复）")
public class AutoCueOutroLoopTest {

    private static final int RATE = 22050;

    /** 用户设的交叠时长（秒）：与参考对实测的 7.5 s 同一个量级，在合成素材上正好 4 小节。 */
    private static final double NOMINAL_WINDOW = 7.5;

    private static final String SRC_A = "test://loop-a";
    private static final String SRC_B = "test://loop-b";

    // ==================================================================
    // 判据一：不需要循环时，点位与渲染与接线之前**逐位相同**
    // ==================================================================

    /**
     * <b>「不循环 ⇒ 一个字节都不变」</b>。
     *
     * <p>夹具：A 128 BPM + B <b>128.4 BPM</b>（差 0.3% &lt; 3%，两条触发都不成立）
     * → {@code planOutroLoop} 返回 null。断言三件事：
     *
     * <ol>
     *   <li>计划层：这一对的 {@code Cue.outroLoop} 是 {@code null}（决策层的结论，
     *       不是渲染层的猜测），走生产路径的 {@code AutoCueTransition} 也如实报
     *       {@code outroLoopPlan() == null}、{@code hasLoopOutgoing() == false}；</li>
     *   <li>点位：窗口没有被拉长（本层报的窗口 = 被包接法自己的窗口），丢尾也没变
     *       （= 全曲 − 出场点 − 窗口，也就是触发点仍是原来那个）；</li>
     *   <li>渲染：整窗逐块推进出来的 PCM 与"直接把被包的交叉淡化接法喂同样两份素材、
     *       同一个进度序列"<strong>逐位相同</strong>——这是"接线没有改变默认行为"的硬证据
     *       （听不出差别不等于逐位相同，逐位相同一定听不出差别）。</li>
     * </ol>
     */
    @Test
    public void withoutALoopDecisionTheRenderingIsBitIdenticalToThePlainCrossfade() {
        LoopFixture fx = fixture(128.4);
        Crossfade base = new Crossfade((float) NOMINAL_WINDOW);
        try {
            AutoCue.Cue cue = cueOf(fx);
            assertNotNull("合成素材上应当挑得出点位（否则这条判据什么都没测）", cue);
            assertNull("这一对两条触发都不成立 → 决策层不该给出循环计划", cue.outroLoop);
            assertFalse("不该循环的计划却被接线口径收了", AutoCue.loopIsWired(cue.outroLoop));
            assertFalse("计划层的说明行里不该出现「尾奏循环」（它进 plan 与日志，"
                    + "是产物的一部分）", cue.note.contains("尾奏循环"));

            AutoCueTransition wrap = new AutoCueTransition(base, RATE, 1,
                    () -> NOMINAL_WINDOW, false);
            TransitionPlan plan = wrap.prepare(SRC_A, SRC_B, RATE);
            assertNull("不该循环的这一对，本层却没有如实报 null", wrap.outroLoopPlan());
            assertFalse("不该循环的这一对，本层却拿了自己的循环素材", wrap.hasLoopOutgoing());
            assertEquals("窗口被拉长了——不循环的那一档不许动窗口",
                    base.windowFrames(RATE), wrap.windowFrames(RATE));
            double windowSecs = wrap.windowFrames(RATE) / (double) RATE;
            assertEquals("丢尾变了（= 触发点被挪了）",
                    fx.outroEnv.durationSecs - (cue.outroCutSecs + windowSecs),
                    plan.outroTailSecs, 1e-6);

            // 渲染：同一条路上的两个实现必须逐位相同
            long windowFrames = wrap.windowFrames(RATE);
            double aStart = fx.outroEnv.durationSecs - windowSecs - plan.outroTailSecs;
            Rendered wired = render(wrap, windowFrames, aStart, plan.skipSecs,
                    plan.introStartAt);
            Rendered plain = render(base, windowFrames, aStart, plan.skipSecs,
                    plan.introStartAt);
            assertEquals("逐块推进的帧数都不一样（窗口/记账被动过）",
                    plain.frames, wired.frames);
            assertTrue("整窗渲染几乎是空的，这条判据没测到东西", wired.frames > windowFrames / 2);
            assertEquals("接了循环之后，不需要循环的这一对渲染结果变了——这一条就是"
                            + "「不改变现有默认行为」的红线", -1,
                    firstDifference(plain.mono, wired.mono));
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 判据二：「对齐网格」那一类**有意不接线**（参考对就落在这一类里）
    // ==================================================================

    /**
     * <b>「决策层会给计划，但接线口径不收它」</b>。
     *
     * <p>夹具与判据一共用同一份 A，只把 B 换成 <b>124 BPM（差 3.1%）</b>——落进
     * {@code planOutroLoop} 的"对齐网格"那一档。断言：
     *
     * <ol>
     *   <li><b>决策层确实会给计划</b>（{@code planOutroLoop != null}、
     *       {@code buysTime == false}）——这一条把"为什么参考对要特别关照"钉成事实：
     *       真实素材上 skydive → 春めく 差 6.27%，同样落在这一档；</li>
     *   <li><b>接线口径不收它</b>（{@code AutoCue.loopIsWired} 为 false）→
     *       {@code Cue.outroLoop} 是 null、说明行里没有"尾奏循环"；</li>
     *   <li><b>于是渲染一个字节都不变</b>：窗口没被拉长、没有循环素材、整窗 PCM 与
     *       普通交叉淡化逐位相同。</li>
     * </ol>
     *
     * <p>另外顺带钉一句反过来的话：{@code buysTime = true} 的计划**是**接的
     * （{@code loopIsWired} 为 true）——否则"有意不接"就退化成"根本没接"。
     */
    /**
     * <b>循环段里一句人声都不许有</b>（2026-10-07 用户原话：「不允许有人声」）。
     *
     * <p>起因是实测：`Wake Me Up → After Hours` 触发了"买时间"，而循环的那段尾奏**带着人声**
     * ⇒ 同一句人声被放了两遍（用户描述「人声叫了两声」）✗。
     *
     * <p>这条判据不依赖素材：把同一段 vamp 的 {@code vocalFrac} 从 0 改成 0.01（1%），
     * 计划就必须从"给"变成"不给" —— 门槛是 **0**，不是 {@code VAMP_MAX_VOCAL_FRACTION} 的 0.2 ✗。
     */
    @Test
    public void aVampWithAnyVocalsIsNeverLooped() {
        LoopFixture fx = fixture(124.0);
        AutoCue.Envelope a = fx.outroEnv;
        AutoCue.VampSpan clean = AutoCue.outroVampSpan(a);
        assertNotNull("合成素材上应当找得到可循环的 vamp", clean);
        assertTrue("这条夹具的 vamp 本来就是「没人唱」的（vocalFrac = " + clean.vocalFrac + "）",
                clean.vocalFrac <= 0.0);
        AutoCue.Cue cue = cueOf(fx);
        assertNotNull("合成素材上应当挑得出点位", cue);

        assertNotNull("无人声的 vamp ⇒ 决策层给计划",
                AutoCue.planOutroLoop(a, fx.introEnv, clean,
                        cue.outroCutSecs, cue.windowSecs, false));

        // 同一段、只加 1% 的人声活动 ⇒ 一句都不许循环
        AutoCue.VampSpan sung = new AutoCue.VampSpan(clean.from, clean.to,
                clean.selfSim, clean.levelCv, 0.01);
        assertNull("有人声的循环段 ⇒ 不给计划（「不允许有人声」）",
                AutoCue.planOutroLoop(a, fx.introEnv, sung,
                        cue.outroCutSecs, cue.windowSecs, false));
        assertFalse("顺带：有人声的 vamp 也不该被当成「买得起时间」",
                AutoCue.buysTimePair(a, fx.introEnv, sung, cue.outroCutSecs, cue.windowSecs));
    }

    @Test
    public void theGridClassDecisionIsDeliberatelyNotWired() {
        LoopFixture fx = fixture(124.0);
        Crossfade base = new Crossfade((float) NOMINAL_WINDOW);
        try {
            AutoCue.Envelope a = fx.outroEnv;
            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(a);
            assertNotNull("合成素材上应当找得到可循环的 vamp", vamp);
            AutoCue.Cue cue = cueOf(fx);
            assertNotNull("合成素材上应当挑得出点位", cue);
            AutoCue.OutroLoopPlan decided = AutoCue.planOutroLoop(a, fx.introEnv, vamp,
                    cue.outroCutSecs, cue.windowSecs, false);
            assertNotNull("BPM 差 3.1% 落在 3%~8% 里 → 决策层应当给出计划（这一条是事实，"
                    + "不是期望）", decided);
            assertFalse("BPM 差这一档是「对齐网格」，不是「买时间」", decided.buysTime);
            assertTrue("这一类的理由里应当写着网格（参考对同一类，实测差 6.27%）",
                    decided.reason.contains("网格"));
            assertFalse("「对齐网格」那一类有意不接线（见 AutoCue.loopIsWired）",
                    AutoCue.loopIsWired(decided));
            assertNull("接线口径不收它 → Cue 里不该有循环计划（渲染路径据此一字不动）",
                    cue.outroLoop);
            assertFalse("说明行里不该出现「尾奏循环」", cue.note.contains("尾奏循环"));
            // 反过来：真正"买时间"的那一类是接的
            AutoCue.OutroLoopPlan timeClass = new AutoCue.OutroLoopPlan(vamp, 8, 1, true,
                    "判据：买时间");
            assertTrue("「买时间」那一类必须接（否则就是根本没接）",
                    AutoCue.loopIsWired(timeClass));

            // **变速要关掉**：这一对的 BPM 差 3.1% 会让"过渡期变速"那一档真的动 A 的素材
            // （`prepareStretchedOutgoing`），于是"与普通交叉淡化逐位相同"这条断言测到的会是
            // 变速、而不是循环接线。关掉它，两边就只差循环这一件事。
            MixTuning tuning = MixTuning.defaults();
            tuning.tempoStretch = 0.0f;
            AutoCueTransition wrap = new AutoCueTransition(base, RATE, 1,
                    () -> NOMINAL_WINDOW, false, tuning);
            TransitionPlan plan = wrap.prepare(SRC_A, SRC_B, RATE);
            assertFalse("这一档不该变速（本判据要把它排除掉）", wrap.hasStretchedOutgoing());
            assertNull("「对齐网格」那一类被接上了——参考对会因为这条接线开始循环",
                    wrap.outroLoopPlan());
            assertFalse("「对齐网格」那一类被接上了", wrap.hasLoopOutgoing());
            assertEquals("窗口被拉长了", base.windowFrames(RATE), wrap.windowFrames(RATE));

            long windowFrames = wrap.windowFrames(RATE);
            double windowSecs = windowFrames / (double) RATE;
            double aStart = fx.outroEnv.durationSecs - windowSecs - plan.outroTailSecs;
            Rendered wired = render(wrap, windowFrames, aStart, plan.skipSecs,
                    plan.introStartAt);
            Rendered plain = render(base, windowFrames, aStart, plan.skipSecs,
                    plan.introStartAt);
            assertEquals("这一类被接线了：渲染与普通交叉淡化不一样", -1,
                    firstDifference(plain.mono, wired.mono));
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 判据三：需要循环时，窗口里放的是**被重复的那一段**，切点在小节线上
    // ==================================================================

    /**
     * <b>「买时间」那一类的渲染（repeats = 1）</b>。
     *
     * <p>喂进去的 {@code Cue} 就是计划层在"尾奏放不下窗口"时会给出的形状：
     * 循环段 = 8 小节、{@code repeats = 1}、窗口 = 一遍循环段、淡化 = 用户设的 7.5 s
     * 吸附到 4 小节。于是形状是离线原型验收过的那个变体：
     *
     * <pre>
     *   A 自然播到 v0 → 循环段第一遍播完（= 切点，落在小节线上）
     *   ├─ 窗口前 4 小节：A 把循环段重复一遍的前半（B 不进来）
     *   └─ 窗口后 4 小节：与 B 等功率交叉淡化，结束正好是这一遍的末尾
     * </pre>
     *
     * <p>断言四件事：
     *
     * <ol>
     *   <li>窗口 = 一遍循环段 = 整数小节（帧数对得上），淡化 = 用户设的窗口吸附到整数小节；</li>
     *   <li>循环段起点、切点、淡化起点三处都落在小节线上（相位取自
     *       {@code AutoCue.barPhaseOf}，与生产同一把尺子）；</li>
     *   <li>窗口头（"只放 A"那一段）的采样与源 PCM 在<strong>循环段起点</strong>处逐位相同
     *       ——A 被倒回循环段重播了，而不是接着播它自己的收尾；而它与"切点处的自然延续"
     *       明显不同（否则"重复"这件事无从证实）；</li>
     *   <li>内层接法看到的进度：淡化起点之前恒为 0（B 一个采样都不进来），之后从 0 走到 1
     *       ——<strong>淡化的起点与终点就是"循环段末尾往回数 4 小节"与"循环段末尾"</strong>
     *       （这条比任何音频相关性都准，因为它是接口上的契约）。</li>
     * </ol>
     */
    @Test
    public void aTimeClassLoopRepeatsTheVampFromItsBarAlignedStart() {
        LoopFixture fx = fixture(124.0);
        RecordingCrossfade base = new RecordingCrossfade(NOMINAL_WINDOW);
        try {
            AutoCue.Envelope a = fx.outroEnv;
            double barSecs = 4.0 * 60.0 / a.tempo.bpm;
            double phase = AutoCue.barPhaseOf(a, barSecs);
            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(a);
            assertNotNull("合成素材上应当找得到可循环的 vamp", vamp);
            AutoCue.OutroLoopPlan plan = new AutoCue.OutroLoopPlan(vamp, 8, 1, true,
                    "判据：买时间（尾奏放不下窗口）");
            AutoCue.Cue cue = new AutoCue.Cue(0.0, 0.0, NOMINAL_WINDOW, 0.0, 0.0, "判据",
                    0.5, true, plan);
            AutoCueTransition wrap = new AutoCueTransition(base, RATE, 1,
                    () -> NOMINAL_WINDOW, false);
            wrap.prepareLoopOutgoing(SRC_A, null, a, cue, NOMINAL_WINDOW);
            assertTrue("「买时间」的计划没有生效", wrap.hasLoopOutgoing());
            assertEquals("这一层没有把窗口报成循环窗口",
                    wrap.loopWindowFrames(), wrap.windowFrames(RATE));

            // ① 窗口 = 一遍循环段 = 整数小节；淡化 = 用户设的窗口吸附到整数小节
            long windowFrames = wrap.loopWindowFrames();
            assertTrue("窗口不是一遍整数小节的循环段（" + windowFrames + " 帧 vs "
                            + (8 * barSecs * RATE) + "）",
                    Math.abs(windowFrames - Math.round(8 * barSecs * RATE)) <= 1.0);
            long fadeFrames = wrap.loopFadeFrames();
            assertEquals("淡化不是用户设的窗口吸附到整数小节",
                    Math.round(NOMINAL_WINDOW / barSecs) * barSecs * RATE, fadeFrames, 1.0);

            // ② 三处都在小节线上
            double v0 = plan.span.from;
            double cutSecs = wrap.loopCutSecs();
            double fadeStartSecs = cutSecs - fadeFrames / (double) RATE;
            assertTrue("循环段起点不在小节线上：" + v0, isOnBarGrid(v0, phase, barSecs));
            assertTrue("切点（循环段第一遍播完）不在小节线上：" + cutSecs,
                    isOnBarGrid(cutSecs, phase, barSecs));
            assertTrue("淡化起点不在小节线上：" + fadeStartSecs,
                    isOnBarGrid(fadeStartSecs, phase, barSecs));
            assertEquals("淡化起点不是「循环段末尾往回数 fadeFrames」",
                    v0 + (windowFrames - fadeFrames) / (double) RATE, fadeStartSecs, 2e-5);

            // ③ 窗口头 = 循环段（倒回 v0 重播），逐位相同
            Rendered r = render(wrap, windowFrames, cutSecs, 0.0, 1.0f);
            assertTrue("整窗渲染几乎是空的", r.frames > windowFrames - Deck.RENDER_FRAMES);
            int seamFrames = (int) Math.round(AutoCueTransition.LOOP_SEAM_FADE_SECS * RATE);
            int insertFrames = (int) (windowFrames - fadeFrames);
            int srcV0 = (int) Math.round(v0 * RATE);
            int srcCut = (int) Math.round(cutSecs * RATE);
            // 真实落点允许 ±2 帧（解码器 seek 的粒度）；先对齐再逐位比
            int alignV0 = align(r.mono, seamFrames + 64, fx.pcmA, srcV0);
            assertTrue("窗口头对不上循环段起点（落在 " + alignV0 + "，期望 " + srcV0 + "）"
                            + "——A 没有倒回循环段重播",
                    alignV0 >= 0 && Math.abs(alignV0 - srcV0) <= 2);
            int checked = 0;
            int firstMismatch = -1;
            for (int i = seamFrames + 1; i < insertFrames; i++) {
                checked++;
                if (Float.floatToRawIntBits(r.mono[i])
                        != Float.floatToRawIntBits(fx.pcmA[alignV0 + i])) {
                    firstMismatch = i;
                    break;
                }
            }
            assertEquals("窗口里的 A 素材与循环段的样本不一致（第一个不同在下标 " + firstMismatch
                            + "）——重播的不是同一份素材", -1, firstMismatch);
            assertTrue("比对的采样太少（" + checked + "），这条判据没测到东西",
                    checked > insertFrames / 2);
            // 不是自然延续（否则说明我们其实什么都没换）
            double continuationDiff = 0.0;
            for (int i = seamFrames; i < insertFrames; i++) {
                continuationDiff = Math.max(continuationDiff,
                        Math.abs(r.mono[i] - fx.pcmA[srcCut + i]));
            }
            assertTrue("窗口头与「切点处的自然延续」几乎一样（最大差 " + continuationDiff
                            + "）——循环段与它后面的素材太像，这条判据分不出重复与延续",
                    continuationDiff > 0.02);
            // 接缝那 20 ms 是有意的淡入（离线原型的 loopTail 就是这么拼的）
            assertEquals("接缝淡入的起点不是 0", 0.0f, r.mono[0], 0.0f);
            // **2026-10-06 按证据重述**：原来是
            // `|mono[s+4]| > 0.5 × |pcmA[align+s+4]|`，它额外假定了"那一处的**源素材**不是静音"。
            // 乐句栅格改成只准落在拍位置之后，循环段的小节对齐起点挪了，那段素材在 +20 ms
            // 处正好是静音（源样本 == 0），断言于是以 `0 > 0` 失败——而"淡入是否在
            // 20 ms 内结束"这件事与素材响不响**无关**。
            // 改成本来要验的两件事，一条都不放：
            //   ① 淡入期内幅度**不许超过源素材**（增益是单调爬到 1 的，不是过冲）；
            //   ② 必须在 seamFrames 那一格**逐位**回到源素材（下面那条循环从 seamFrames+1
            //      起，所以这一格原先没人管）。
            for (int i = 0; i < seamFrames; i++) {
                assertTrue("淡入期内的幅度超过了源素材（下标 " + i + "，mono "
                                + r.mono[i] + " vs 源 " + fx.pcmA[alignV0 + i] + "）",
                        Math.abs(r.mono[i]) <= Math.abs(fx.pcmA[alignV0 + i]) + 1e-6);
            }
            assertEquals("淡入必须在 " + seamFrames + " 帧处逐位回到源素材",
                    Float.floatToRawIntBits(fx.pcmA[alignV0 + seamFrames]),
                    Float.floatToRawIntBits(r.mono[seamFrames]));

            // ④ 内层接法看到的进度：前 insertFrames 恒为 0，之后线性到 1
            assertTrue("内层一次都没被调到（进度序列是空的）", base.progressSeen.size() > 8);
            // 进度的粒度是**一块**（编排器每块给一个进度，被包的接法按这个值算等功率增益）
            // ——与接线之前完全一样。所以"第一块看到非零进度"的那一块 = 起点第一次越过
            // 淡化起点的那一块，残差最多一块（48 kHz 下 85 ms，这里 22050 Hz 下 186 ms）。
            int wantFirstNonZero = (insertFrames + Deck.RENDER_FRAMES - 1) / Deck.RENDER_FRAMES;
            assertEquals("内层第一次看到非零进度的那一块不在「只放 A」那一段的边界上"
                            + "（就在淡化起点 = 循环段末尾往回数 4 小节）",
                    wantFirstNonZero, base.firstNonZeroAt);
            long firstNonZeroFrame = (long) wantFirstNonZero * Deck.RENDER_FRAMES;
            assertTrue("淡化起点那一块的残差超过了一块（粒度变了）",
                    firstNonZeroFrame - insertFrames < Deck.RENDER_FRAMES);
            // 进度是**单调不减**的（"进度回跳"那一类缺陷的守门人），而且一路走到接近 1：
            // 最后一块的进度是"这一块的起点 / 窗口"，所以与 1 的差最多一块
            // （48 kHz 下 85 ms；这就是编排器给的粒度，接线前后一样）。
            float previous = -1f;
            for (Float p : base.progressSeen) {
                if (p.floatValue() < previous) {
                    fail("进度回跳了：" + previous + " → " + p);
                }
                previous = p.floatValue();
            }
            float last = base.progressSeen.get(base.progressSeen.size() - 1).floatValue();
            assertTrue("最后一块的进度离 1 太远（" + last + "）——淡化没有在循环段末尾结束",
                    last >= 1.0f - (float) Deck.RENDER_FRAMES / (float) fadeFrames - 1e-4f);
            assertEquals("B 的淡入点不在「窗口里离末尾还有 fadeFrames 帧」那个位置",
                    (windowFrames - fadeFrames) / (double) RATE, fadeStartSecs - v0, 2e-5);
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 判据四：repeats 遍是真的重复
    // ==================================================================

    /**
     * <b>「循环段重复 {@code repeats} 遍」</b>：窗口里第二遍与第一遍<strong>逐位相同</strong>。
     *
     * <p>为什么要单独一条：{@code planOutroLoop} 只在"买时间且尾奏很短"时才给
     * {@code repeats = 2}，而那一档在生产路径上走不到（见类注释）。渲染那一半已经实现了，
     * 就得有判据守着——哪天真从计划层跑出来时，这一条是现成的守门人。
     */
    @Test
    public void repeatedPassesAreBitIdenticalToTheFirstOne() {
        LoopFixture fx = fixture(124.0);
        Crossfade base = new Crossfade((float) NOMINAL_WINDOW);
        try {
            AutoCue.Envelope a = fx.outroEnv;
            double barSecs = 4.0 * 60.0 / a.tempo.bpm;
            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(a);
            assertNotNull("合成素材上应当找得到可循环的 vamp", vamp);
            AutoCue.OutroLoopPlan two = new AutoCue.OutroLoopPlan(vamp, 8, 2, true,
                    "判据：买时间，重复两遍");
            AutoCue.Cue cue = new AutoCue.Cue(0.0, 0.0, NOMINAL_WINDOW, 0.0, 0.0, "判据",
                    0.5, true, two);
            AutoCueTransition wrap = new AutoCueTransition(base, RATE, 1,
                    () -> NOMINAL_WINDOW, false);
            wrap.prepareLoopOutgoing(SRC_A, null, a, cue, NOMINAL_WINDOW);
            assertTrue("repeats = 2 的计划没有生效", wrap.hasLoopOutgoing());
            // **「只有 A 在响」的前导**必须如实报出来（2026-10-07 契约改动）：
            // 引擎靠它把"通知 / 位置 / 时长"三样一起顺延到 B 出声 ✓ ——
            // 报 0 就等于回到"窗口一开界面就切 B"✗（用户实测的进度不一致）。
            assertTrue("买时间那一档必须有前导（界面据此延后切 B）：实测 "
                            + wrap.outroLoopLeadInSecs() + " s",
                    wrap.outroLoopLeadInSecs() > 0.05);
            // 反过来：普通过渡（接口默认实现）必须是 0 ⇒ 行为与从前逐位相同 ✓
            AutoCueTransition plain = new AutoCueTransition(base, RATE, 1,
                    () -> NOMINAL_WINDOW, false);
            assertEquals("普通过渡不许报前导（否则界面会无谓地延迟切 B）",
                    0.0, plain.outroLoopLeadInSecs(), 1e-9);
            long windowFrames = wrap.loopWindowFrames();
            long pass = Math.round(8 * barSecs * RATE);
            assertEquals("两遍的窗口不是一遍的两倍", 2L * pass, windowFrames);

            double v0 = AutoCueTransition.snapToBar(vamp.from,
                    AutoCue.barPhaseOf(a, barSecs), barSecs);
            Rendered r = render(wrap, windowFrames, v0 + pass / (double) RATE, 0.0, 1.0f);
            int seamFrames = (int) Math.round(AutoCueTransition.LOOP_SEAM_FADE_SECS * RATE);
            // 只比**淡化之前**那一段：淡化区里等于功率增益逐块不同，拿它比"同一份样本"
            // 比的是增益曲线、不是素材。
            long compareTo = pass - wrap.loopFadeFrames();
            int first = -1;
            for (long i = seamFrames + 1; i < compareTo; i++) {
                if (Float.floatToRawIntBits(r.mono[(int) i])
                        != Float.floatToRawIntBits(r.mono[(int) (pass + i)])) {
                    first = (int) i;
                    break;
                }
            }
            assertEquals("第二遍与第一遍不是同一份样本（第一个不同在下标 " + first + "，共比了 "
                            + (compareTo - seamFrames - 1) + " 个采样）——重复没有按"
                            + "「只读一遍、游标绕回」的方式做", -1, first);
            assertTrue("第二遍几乎没交付（渲染提前停了）",
                    r.frames > windowFrames - Deck.RENDER_FRAMES);
        } finally {
            fx.close();
        }
    }

    /**
     * <b>遍数只许在用户规格的范围里</b>（默认 1、最多 {@code LOOP_MAX_REPEATS} = 2）。
     *
     * <p>⚠️ <b>已知待改</b>（2026-10-07 用户实测）：「最终效果还是第二遍循环开始混音，
     * 那你循环有毛用」✗ —— 现有规则是 `尾巴 &lt; 半遍 ⇒ 2 遍`，那一对（尾巴 2.7 s、
     * 一遍 17.3 s、窗口 17.4 s）其实**一遍就够**（14.7 s），多的一遍把交叉淡化推到第二遍 ✗。
     * 已写好"按需要算最少遍数"的算术，但它会连锁到旋转/多段那两条判据（
     * `AutoCueSegmentedPathTest` 的"等价路径就是一段"、`AutoCueOutroLoopAiWindowTest`
     * 的"AI 模式下被接住"）⇒ **退回原式**，留成独立一轮 ✓（理由写在 `planOutroLoopCore` 原处）。
     *
     * <p>这条判据守的是"别越界"：一遍够时可能是 1 也可能是 2（现状 ✗），但**永远不许超过 2** ✓。
     */
    @Test
    public void theRepeatCountStaysWithinTheSpecifiedRange() {
        LoopFixture fx = fixture(124.0);
        try {
            AutoCue.Envelope a = fx.outroEnv;
            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(a);
            assertNotNull("合成素材上应当找得到可循环的 vamp", vamp);
            double barSecs = 4.0 * 60.0 / a.tempo.bpm;
            double passSecs = 8 * barSecs;
            double almostGone = a.durationSecs - 0.2;

            for (double window : new double[]{passSecs + 0.2, passSecs * 2.0}) {
                AutoCue.OutroLoopPlan plan = AutoCue.planOutroLoop(a, fx.introEnv, vamp,
                        almostGone, window, false);
                assertNotNull("尾奏几乎没有 ⇒ 必然买时间（窗口 " + window + "）", plan);
                assertTrue("遍数不得小于 1（窗口 " + window + "）", plan.repeats >= 1);
                assertTrue("遍数不得超过规格上限 " + AutoCue.LOOP_MAX_REPEATS + "（窗口 "
                        + window + "，实际 " + plan.repeats + "）",
                        plan.repeats <= AutoCue.LOOP_MAX_REPEATS);
            }
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 夹具：合成素材 + 假解码器
    // ==================================================================

    /** 一对合成素材（A 固定，B 的 BPM 可换）与它扫出来的包络。 */
    private static final class LoopFixture {
        final float[] pcmA;
        final float[] pcmB;
        final AutoCue.Envelope outroEnv;
        final AutoCue.Envelope introEnv;

        LoopFixture(float[] pcmA, float[] pcmB, AutoCue.Envelope outroEnv,
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

    private static LoopFixture fixture(double bpmB) {
        final float[] a = trackA();
        final float[] b = trackB(bpmB);
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, java.util.Map<String, String> headers) {
                return source != null && source.contains("loop-b")
                        ? new Fake(b, RATE) : new Fake(a, RATE);
            }
        });
        return new LoopFixture(a, b, AutoCue.scan(SRC_A, null), AutoCue.scanIntro(SRC_B, null));
    }

    /** 走生产那条计划路径（与 {@code AutoCueTransition.prepare} 里的调用完全一致）。 */
    private static AutoCue.Cue cueOf(LoopFixture fx) {
        return AutoCue.planWithLyricSpans(fx.outroEnv, fx.introEnv,
                new double[]{NOMINAL_WINDOW}, AutoCue.vocalPhraseSpans(fx.outroEnv),
                AutoCue.vocalPhraseSpans(fx.introEnv), AutoCue.Bridge.MEASURE);
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
                        // 周期因此是 4 小节而循环段是 8 小节——于是"倒回起点重播"与
                        // "接着往下播"能用样本逐位比出来。
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

    /** 素材 A：105 s、128 BPM，可循环的 vamp 落在 [60, 75)（8 小节 = 15.00 s）。 */
    private static float[] trackA() {
        StringBuilder kinds = new StringBuilder();
        float[] levels = new float[64];
        int bar = 0;
        for (int i = 0; i < 8; i++, bar++) {
            kinds.append('i');
            levels[bar] = 0.25f;
        }
        append(kinds, levels, bar, "kkkkhhhh", 0.8f);       // [15, 30)
        append(kinds, levels, bar += 8, "hhhhkkkk", 0.8f);  // [30, 45)
        append(kinds, levels, bar += 8, "kkkkhhhh", 0.8f);  // [45, 60)
        append(kinds, levels, bar += 8, "vvvvvvvv", 1.0f);  // [60, 75) ← 可循环段
        append(kinds, levels, bar += 8, "oooooooo", 0.35f); // [75, 90)
        append(kinds, levels, bar + 8, "ffffffff", 0.5f);   // [90, 105)
        return synth(128.0, kinds.toString(), levels);
    }

    /** 素材 B：40 小节，BPM 由调用方给（计划层用它算"BPM 差"）。 */
    private static float[] trackB(double bpm) {
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
        return synth(bpm, kinds.toString(), levels);
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
    // 渲染脚手架（照编排器的方式逐块推进整窗）
    // ==================================================================

    /** 一次整窗渲染的产物。 */
    private static final class Rendered {
        float[] mono;
        long frames;
    }

    /**
     * 记录内层接法<strong>看到的进度</strong>的等功率交叉淡化：判据用它钉"淡化从哪一块开始、
     * 到哪一块结束"（那是接口上的契约，比看音频相关性准得多）。
     */
    private static final class RecordingCrossfade implements Transition {
        private final Crossfade inner;
        final List<Float> progressSeen = new ArrayList<Float>();
        /** 第一次看到非零进度的那一块的下标（-1 = 一次都没有）。 */
        int firstNonZeroAt = -1;
        private int block;

        RecordingCrossfade(double seconds) {
            this.inner = new Crossfade((float) seconds);
        }

        @Override
        public long windowFrames(int sampleRate) {
            return inner.windowFrames(sampleRate);
        }

        @Override
        public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
            if (progress > 0f && firstNonZeroAt < 0) {
                firstNonZeroAt = block;
            }
            progressSeen.add(Float.valueOf(progress));
            block++;
            return inner.render(out, from, to, progress);
        }

        @Override
        public String name() {
            return inner.name();
        }

        @Override
        public boolean rewritesSamples() {
            return true;
        }

        @Override
        public PitchPlan pitchAt(float progress) {
            return inner.pitchAt(progress);
        }
    }

    /**
     * 按编排器的方式把整窗走完：两块素材来自真的 deck，{@code progress = 已渲染帧 / 窗口帧}，
     * 进场曲按 {@code introStartAt}**挡在门外**（循环那一档的前半段正是靠它"只放 A"）。
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
     * 时刻是不是落在小节网格上（相位取自 {@code AutoCue.barPhaseOf}）。
     *
     * <p>容差一<strong>帧</strong>：小节线是时间刻度上的位置，只能落在采样上
     * （22050 Hz 下 45 µs），比这更小就是在要求"采样之间有声音"。实现那边把"几小节"
     * <strong>一次性</strong>换算成帧（不是"每小节取整再乘"），所以残差只有一次舍入
     * （≤ 半帧）、<strong>不随小节数累积</strong>——这条容差钉的正是这件事。
     */
    private static boolean isOnBarGrid(double secs, double phase, double barSecs) {
        double bars = (secs - phase) / barSecs;
        return Math.abs(bars - Math.round(bars)) * barSecs < 1.0 / RATE;
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
