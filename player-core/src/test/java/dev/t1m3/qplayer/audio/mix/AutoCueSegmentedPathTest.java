package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>「多段拼接」的判据</b>（2026-10-05；用户原话：
 *
 * <blockquote>
 * 差距三：不支持"多段拼接"。你现在一次过渡只能用 A 的尾奏 + B 的 intro 两段素材。但有些场景
 * 最优解是：A 的尾奏 + A 的间奏循环 + B 的主歌；A 的 bridge + B 的副歌前奏 + B 的副歌；
 * A 的尾奏 + B 的纯鼓段循环 + B 的主歌。<br>
 * 改法：在 Cue 里加一个 {@code List<Segment>}，每个 Segment 是
 * (source, startSecs, endSecs, repeat)。planCollect 从"选两个点"变成"选一条路径"。
 * 然后 AutoCueTransition.render() 按顺序喂素材。这需要把现在的 stretchedOutgoing /
 * loopedOutgoing 合并成一个 segmentedOutgoing 队列。<br>
 * 一句话：你现在的系统是"在时间轴上找最优切点"，主动编配是"在结构图上找最优路径"。
 * </blockquote>
 *
 * <h2>素材：自己造的，答案是已知的</h2>
 *
 * <p>与 {@code AutoCueOutroLoopTest} 同一套做法（合成 4/4 打击乐 + 假解码器 + 真 deck +
 * 真等功率交叉淡化）：<strong>每一段素材在源里的位置、同一段里第几遍、段与段的先后</strong>
 * 都是已知的，所以"渲染层到底喂了什么"可以用<strong>逐位比较</strong>答出来，不需要听感。
 *
 * <ul>
 *   <li><b>A</b>：128 BPM、56 小节 = 105.0 s。8 小节安静引子 / 底鼓 hi-hat 块 ×3 /
 *       <b>vamp [60, 75)（8 小节：每拍底鼓 + 反拍 hat，逐小节相同 → 前后半自相似 1.00、
 *       没人唱）</b>/ 稀疏收尾 / 收尾后段。</li>
 *   <li><b>B</b>：同样 128 BPM、40 小节 = 75.0 s，块划分与 A 不同（判据靠这一点分得清
 *       "这一段是从 B 读的"）——判据用的那一段是 <b>[22.5, 30) 的 4 小节纯 hi-hat</b>。</li>
 * </ul>
 *
 * <h2>判据各自钉什么</h2>
 *
 * <ol>
 *   <li>{@link #withoutAPathOrALoopTheQueueStaysEmptyAndThePlainCrossfadeIsUntouched}：
 *       <b>没有路径时队列必须是空的</b>，窗口一个字节都不动、渲染与普通交叉淡化逐位相同
 *       ——这是"{@code segments == null} 时整条渲染路径与今天逐位相同"这条硬要求在渲染层的
 *       守门人（另一半是位指纹判据 {@code AutomixBitExactReferenceTest}：参考对走的是变速
 *       那一档，合并队列之后它的指纹<strong>一个字符都没变</strong>）；</li>
 *   <li>{@link #thePlanLayerEmitsAnEquivalentPathWhenTheOutroLoopIsWired}：
 *       <b>计划层真的会"选路径"</b>——尾奏循环接线时 {@code Cue.segments} 是由那份决策
 *       导出的一条等价路径（一段、来源是出场曲、长度是整数小节、重复遍数一致）；</li>
 *   <li>{@link #theWiredPathIsConsumedAndRendersBitIdenticallyToTheDecision}：
 *       <b>路径是被消费的，而且与今天逐位相同</b>——同一份计划，带路径与不带路径各渲染一遍，
 *       喂进去的素材与产出的 PCM <strong>逐位相同</strong>，同时
 *       {@code usesExplicitPath()} 一真一假（否则这条判据测到的会是兜底那支）；</li>
 *   <li>{@link #aMultiSegmentPathIsPlayedInOrderAndEverySegmentMatchesItsSource}：
 *       <b>多段路径按顺序播放，每一段与源 PCM 对应区间逐位相同</b>
 *       （A 的尾奏 8 小节 + A 的 vamp 4 小节 × 2 + B 的 4 小节纯 hi-hat），
 *       段的边界全落在小节线上，窗口与淡化按路径长度与用户窗口分开；</li>
 *   <li>{@link #repeatedSegmentsRepeatTheSameSamplesInAMultiSegmentPath}：
 *       <b>重复段真的重复</b>（中间那一段的第二遍与第一遍逐位相同——"只读一遍、游标绕回"）；</li>
 *   <li>{@link #aPathThatStartsWithTheIncomingTrackIsRefusedInsteadOfGuessed}：
 *       <b>已知边界</b>——路径的第一段必须是出场曲的素材（"A 自然播到哪"只有它有定义），
 *       第一段是 B 时如实退回普通交叉淡化，而不是猜一个接缝出来。</li>
 * </ol>
 */
public class AutoCueSegmentedPathTest {

    private static final int RATE = 22050;

    /** 用户设的交叠时长（秒）：在 128 BPM 的合成素材上正好 4 小节。 */
    private static final double NOMINAL_WINDOW = 7.5;

    private static final String SRC_A = "test://path-a";
    private static final String SRC_B = "test://path-b";

    // ==================================================================
    // 判据一：没有路径 ⇒ 队列为空、窗口不动、渲染与普通交叉淡化逐位相同
    // ==================================================================

    /**
     * <b>「{@code segments == null} ⇒ 整条渲染路径与今天逐位相同」</b>（硬要求）。
     *
     * <p>这一条钉三件事：队列<strong>是空的</strong>（这一层一个采样都不碰）、
     * 窗口没有被改（还是被包接法自己那个）、以及渲染出来的 PCM 与"直接把同一份
     * 出场曲素材喂给普通交叉淡化"<strong>逐位相同</strong>。
     *
     * <p>红-前记录：让新队列"永远有效"（把 {@code render} 里"队列空就整条交回被包接法"
     * 那一支短路，或让 {@code prepareLoopOutgoing} 无条件塞一段素材），这条判据立刻红在
     * 第一句"队列必须是空的"上，而 {@code AutoCueOutroLoopTest} 的四条也会一起红
     * （它们钉的是循环那一档拿没拿到素材、点位有没有被改）。
     */
    @Test
    public void withoutAPathOrALoopTheQueueStaysEmptyAndThePlainCrossfadeIsUntouched() {
        PathFixture fx = fixture();
        Crossfade base = new Crossfade((float) NOMINAL_WINDOW);
        try {
            AutoCue.Cue cue = cueOf(fx);
            assertNotNull("合成素材上应当挑得出点位（否则这条判据什么都没测）", cue);
            assertNull("这一对不该有路径（决策层没给循环计划）", cue.segments);
            assertNull("这一对不该有循环计划", cue.outroLoop);

            AutoCueTransition wrap = new AutoCueTransition(base, RATE, 1,
                    () -> NOMINAL_WINDOW, false);
            wrap.prepareLoopOutgoing(SRC_A, null, fx.outroEnv, cue, NOMINAL_WINDOW);
            assertEquals("没有路径时队列必须是空的（这一层不许碰素材）",
                    0, wrap.outgoingSegmentCount());
            assertFalse("没有路径时不该报「走了显式路径」", wrap.usesExplicitPath());
            assertNull("没有循环计划时不该有循环素材", wrap.outroLoopPlan());
            assertFalse("没有循环计划时不该拿循环素材", wrap.hasLoopOutgoing());
            assertEquals("窗口被动了——没有路径的那一档不许改窗口",
                    base.windowFrames(RATE), wrap.windowFrames(RATE));
            assertEquals("没有路径时不该报自己的窗口帧数", 0L, wrap.outgoingWindowFrames());

            // 渲染：整条交回被包接法 ⇒ 与"普通交叉淡化"逐位相同
            long windowFrames = wrap.windowFrames(RATE);
            double windowSecs = windowFrames / (double) RATE;
            double aStart = fx.outroEnv.durationSecs - windowSecs;
            Rendered wired = render(wrap, windowFrames, aStart, 0.0, 0.0f);
            Rendered plain = render(base, windowFrames, aStart, 0.0, 0.0f);
            assertEquals("逐块推进的帧数都不一样（窗口/记账被动过）",
                    plain.frames, wired.frames);
            assertTrue("整窗渲染几乎是空的，这条判据没测到东西",
                    wired.frames > windowFrames / 2);
            assertEquals("没有路径的那一档渲染结果变了——这就是「不改变现有默认行为」的红线",
                    -1, firstDifference(plain.mono, wired.mono));
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 判据二：计划层在尾奏循环接线时给出一条等价路径
    // ==================================================================

    /**
     * <b>计划层真的会"选路径"</b>：尾奏循环接线（{@code AutoCue.loopIsWired}）时，
     * {@code Cue.segments} 是<strong>由那份决策导出</strong>的一条等价路径。
     *
     * <p>素材是"尾奏放不下这次窗口"的形状（与 {@code AutoCueOutroLoopBuyTimeTest} 同一份
     * 合成素材：vamp 之后只剩 3.75 s），所以计划路径上真的会走到"买时间"那一档
     * ——也就是说<strong>生产路径上路径是会被填上的</strong>，不是只有判据里才有。
     *
     * <p>断言：路径非空、恰好一段、来源是出场曲、覆盖的正是那段 vamp（起点吸到小节线、
     * 长度是整数小节）、重复遍数与计划一致；而 {@code outroLoopPath} 在拿不到包络 / 没有计划时
     * 如实返回 {@code null}（那是"说不出来就不说"的兜底，不该硬编一条出来）。
     */
    @Test
    public void thePlanLayerEmitsAnEquivalentPathWhenTheOutroLoopIsWired() {
        BuyTimeFixture fx = buyTimeFixture();
        try {
            AutoCue.Cue cue = AutoCue.planWithLyricSpans(fx.outroEnv, fx.introEnv,
                    new double[]{NOMINAL_WINDOW}, AutoCue.vocalPhraseSpans(fx.outroEnv),
                    AutoCue.vocalPhraseSpans(fx.introEnv), AutoCue.Bridge.MEASURE);
            assertNotNull("合成素材上应当挑得出点位", cue);
            assertNotNull("这一对应当走进「买时间」那一档（尾奏放不下窗口）", cue.outroLoop);
            assertTrue("「买时间」这一类必须落在接线口径内", AutoCue.loopIsWired(cue.outroLoop));
            assertNotNull("接线时计划层应当给出一条等价路径（Cue.segments）", cue.segments);
            assertEquals("等价路径就是一段（循环段 × repeats）", 1, cue.segments.size());

            AutoCue.Segment seg = cue.segments.get(0);
            assertEquals("这一段该是出场曲的素材", "outro", seg.source);
            assertEquals("重复遍数该与计划一致（最多 2）",
                    Math.min(2, cue.outroLoop.repeats), seg.repeat);
            assertEquals("这一段该恰好覆盖那段 vamp 的整数小节",
                    cue.outroLoop.bars * fx.barSecs, seg.spanSecs(), 1e-9);
            assertTrue("段的起点不在小节线上：" + seg.startSecs,
                    isOnBarGrid(seg.startSecs, fx.phase, fx.barSecs));
            assertTrue("段的终点不在小节线上：" + seg.endSecs,
                    isOnBarGrid(seg.endSecs, fx.phase, fx.barSecs));
            assertEquals("段的起点该是吸到小节线之后的 vamp 起点",
                    AutoCue.snapToBar(cue.outroLoop.span.from, fx.phase, fx.barSecs),
                    seg.startSecs, 1e-9);
            assertTrue("说明行里应当写着这条路径（它是产物的一部分）",
                    cue.note.contains("路径："));

            // 「说不出来就不说」那一支
            assertNull("拿不到包络时不该硬编一条路径出来",
                    AutoCue.outroLoopPath(null, cue.outroLoop));
            assertNull("没有计划时没有路径可说", AutoCue.outroLoopPath(fx.outroEnv, null));
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 判据三：路径被真的消费，而且与"决策合成的那一段"逐位相同
    // ==================================================================

    /**
     * <b>"等价路径"三个字要能被验收</b>：同一份循环计划，一份带 {@code segments}、
     * 一份不带，各建一次队列并整窗渲染：
     *
     * <ol>
     *   <li>两边的窗口、淡化长度、切点<strong>逐位相同</strong>；</li>
     *   <li>喂进被包接法的素材<strong>逐位相同</strong>（含窗口开头的接缝淡入）；</li>
     *   <li>产出的 PCM <strong>逐位相同</strong>；</li>
     *   <li>而 {@code usesExplicitPath()} 一真一假——没有这一条，前面三条可能只是
     *       "两边都走了兜底那支"而毫无意义。</li>
     * </ol>
     */
    @Test
    public void theWiredPathIsConsumedAndRendersBitIdenticallyToTheDecision() {
        BuyTimeFixture fx = buyTimeFixture();
        try {
            AutoCue.Cue planned = AutoCue.planWithLyricSpans(fx.outroEnv, fx.introEnv,
                    new double[]{NOMINAL_WINDOW}, AutoCue.vocalPhraseSpans(fx.outroEnv),
                    AutoCue.vocalPhraseSpans(fx.introEnv), AutoCue.Bridge.MEASURE);
            assertNotNull("合成素材上应当挑得出点位", planned);
            assertNotNull("这一对应当有循环计划", planned.outroLoop);
            assertNotNull("这一对应当有等价路径", planned.segments);
            // 同一份计划，只是把路径拿掉（其余字段一个不差）
            AutoCue.Cue legacy = new AutoCue.Cue(planned.outroCutSecs, planned.introEntrySecs,
                    planned.windowSecs, planned.tailDropSecs, planned.cost, planned.note,
                    planned.intensityGap, planned.intensityOk, planned.outroLoop, null);

            RecordingCrossfade recorderA = new RecordingCrossfade(NOMINAL_WINDOW);
            RecordingCrossfade recorderB = new RecordingCrossfade(NOMINAL_WINDOW);
            AutoCueTransition withPath = new AutoCueTransition(recorderA, RATE, 1, null, false);
            AutoCueTransition without = new AutoCueTransition(recorderB, RATE, 1, null, false);
            withPath.prepareLoopOutgoing(SRC_A, null, fx.outroEnv, planned, NOMINAL_WINDOW);
            without.prepareLoopOutgoing(SRC_A, null, fx.outroEnv, legacy, NOMINAL_WINDOW);
            recorderA.arm(withPath.windowFrames(RATE));
            recorderB.arm(without.windowFrames(RATE));

            assertTrue("带路径的那一份没有被当成路径（这条判据于是什么都没测到）",
                    withPath.usesExplicitPath());
            assertFalse("不带路径的那一份不该报走了路径", without.usesExplicitPath());
            assertTrue("带路径的那一份没有拿到素材", withPath.hasLoopOutgoing());
            assertTrue("不带路径的那一份没有拿到素材", without.hasLoopOutgoing());
            assertEquals("两边的窗口不一样",
                    without.windowFrames(RATE), withPath.windowFrames(RATE));
            assertEquals("两边的循环窗口不一样",
                    without.loopWindowFrames(), withPath.loopWindowFrames());
            assertEquals("两边的淡化长度不一样",
                    without.loopFadeFrames(), withPath.loopFadeFrames());
            // 切点：**按帧**比。计划层只有秒（它不知道采样率），而这一层的帧几何是决策直接
            // 算出来的（{@code loopFrames}）——两者本来就差"半帧以内"的一次舍入。
            // 差出半帧就是两个不同的位置了，所以容差写一帧而不是"随便给个误差"。
            assertEquals("两边的切点落在不同的帧上",
                    without.loopCutSecs() * RATE, withPath.loopCutSecs() * RATE, 1.0);
            assertEquals("两边报的窗口帧数不一样",
                    without.outgoingWindowFrames(), withPath.outgoingWindowFrames());
            assertEquals("这一段路径该是一段", 1, withPath.outgoingSegmentCount());

            long windowFrames = withPath.windowFrames(RATE);
            double tail = fx.outroEnv.durationSecs
                    - (withPath.loopCutSecs() + windowFrames / (double) RATE);
            double aStart = fx.outroEnv.durationSecs - windowFrames / (double) RATE
                    - Math.max(0.0, tail);
            float introStartAt = (float) ((double) (windowFrames - withPath.loopFadeFrames())
                    / (double) windowFrames);
            Rendered rPath = render(withPath, windowFrames, aStart, 0.0, introStartAt);
            Rendered rLegacy = render(without, windowFrames, aStart, 0.0, introStartAt);
            assertTrue("整窗渲染几乎是空的，这条判据没测到东西",
                    rPath.frames > windowFrames - Deck.RENDER_FRAMES);
            assertEquals("走了路径之后产出的 PCM 变了——「等价路径」不成立",
                    -1, firstDifference(rLegacy.mono, rPath.mono));
            assertEquals("走了路径之后喂进接法的素材变了——「等价路径」不成立",
                    -1, firstDifference(recorderB.fed, recorderA.fed));
            assertEquals("两边的进度序列长度不一样",
                    recorderB.progressSeen.size(), recorderA.progressSeen.size());
            // 素材确实是那段 vamp（不是 A 本来的收尾）：逐位比源 PCM
            AutoCue.Segment seg = planned.segments.get(0);
            int seamFrames = (int) Math.round(AutoCueTransition.LOOP_SEAM_FADE_SECS * RATE);
            int pass = framesOf(seg.spanSecs());
            int srcV0 = (int) Math.round(seg.startSecs * RATE);
            int alignV0 = align(recorderA.fed, seamFrames + 64, fx.pcmA, srcV0);
            assertTrue("窗口头对不上那段 vamp 的起点（落在 " + alignV0 + "，期望 " + srcV0 + "）",
                    alignV0 >= 0 && Math.abs(alignV0 - srcV0) <= 3);
            assertPass("循环素材（那段 vamp 的第一遍）", recorderA.fed, 0, alignV0, fx.pcmA, 0,
                    pass, seamFrames + 1);
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 判据四：多段路径按顺序播放，每一段与源逐位相同
    // ==================================================================

    /**
     * <b>"多段拼接"本身</b>：路径 = {@code A 的尾奏 8 小节 + A 的 vamp 4 小节 × 2
     * + B 的 4 小节纯 hi-hat}（用户三个例子里第一个的形状）。
     *
     * <p>断言六件事：
     *
     * <ol>
     *   <li><b>顺序</b>：喂进接法的素材依次是这三段（逐位比源 PCM）——段数、每段长度、
     *       界标全都按路径走；</li>
     *   <li><b>换源</b>：第三段是<strong>进场曲</strong>的素材（与 A 在同一时刻的素材对不上），
     *       也就是"一段 outro 接一段 intro"真的能走通；</li>
     *   <li><b>段边界落在小节线上</b>（尺子取自 {@code AutoCue.barPhaseOf}，与生产同一把）；</li>
     *   <li><b>窗口与淡化</b>：窗口 = 各段总长、淡化 = 用户设的 7.5 s 吸到 4 小节
     *       （= 最后那一段）；</li>
     *   <li><b>接缝</b>：整条路径开头 {@value AutoCueTransition#LOOP_SEAM_FADE_SECS} 秒是
     *       有意的淡入（A 刚自然播过这一段，重复进来要磨一下；离线原型的 loopTail 同一处理）；</li>
     *   <li><b>丢尾与切点</b>：接缝 = 路径第一段的<strong>终点</strong>（A 自然播到那里），
     *       丢尾 = 全曲 − (接缝 + 窗口)。</li>
     * </ol>
     */
    @Test
    public void aMultiSegmentPathIsPlayedInOrderAndEverySegmentMatchesItsSource() {
        PathFixture fx = fixture();
        RecordingCrossfade recorder = new RecordingCrossfade(NOMINAL_WINDOW);
        try {
            AutoCue.Envelope a = fx.outroEnv;
            double barSecs = 4.0 * 60.0 / a.tempo.bpm;
            double phase = AutoCue.barPhaseOf(a, barSecs);
            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(a);
            assertNotNull("合成素材上应当找得到可循环的 vamp", vamp);
            double vampFrom = AutoCue.snapToBar(vamp.from, phase, barSecs);
            // B 那一段的起点也吸到**同一把尺子**的小节线上：段的边界属于哪条网格，
            // 这一层只有一把尺子（出场曲的相位）
            double bFrom = AutoCue.snapToBar(22.5, phase, barSecs);
            List<AutoCue.Segment> path = new ArrayList<AutoCue.Segment>();
            path.add(new AutoCue.Segment("outro", vampFrom - 8 * barSecs, vampFrom, 1));
            path.add(new AutoCue.Segment("outro", vampFrom, vampFrom + 4 * barSecs, 2));
            path.add(new AutoCue.Segment("intro", bFrom, bFrom + 4 * barSecs, 1));
            for (AutoCue.Segment s : path) {
                assertTrue("判据自己造出来的段起点不在小节线上：" + s,
                        isOnBarGrid(s.startSecs, phase, barSecs));
                assertTrue("判据自己造出来的段终点不在小节线上：" + s,
                        isOnBarGrid(s.endSecs, phase, barSecs));
            }
            AutoCue.Cue cue = new AutoCue.Cue(0.0, 0.0, NOMINAL_WINDOW, 0.0, 0.0, "判据",
                    0.5, true, null, path);

            AutoCueTransition wrap = new AutoCueTransition(recorder, RATE, 1, null, false);
            wrap.prepareLoopOutgoing(SRC_A, null, SRC_B, null, a, fx.introEnv, cue,
                    NOMINAL_WINDOW);
            assertTrue("多段路径没有被当成路径", wrap.usesExplicitPath());
            assertEquals("队列里的段数不对", 3, wrap.outgoingSegmentCount());
            recorder.arm(wrap.windowFrames(RATE));

            // **帧几何按"整段一次性换算"算**（不是"每小节取整再乘"）：那是这一层的纪律
            // （见 prepareLoopOutgoing 里那段实测：逐小节取整会每小节攒下半帧），
            // 判据必须用同一把尺子，否则它自己就会因为舍入而红。
            int pass1 = framesOf(8 * barSecs);
            int pass2 = framesOf(4 * barSecs);
            int pass3 = framesOf(4 * barSecs);
            long windowFrames = pass1 + 2L * pass2 + pass3;
            int fadeFrames = framesOf(Math.round(NOMINAL_WINDOW / barSecs) * barSecs);
            assertEquals("窗口不是各段总长", windowFrames, wrap.windowFrames(RATE));
            assertEquals("窗口帧数报得不对", windowFrames, wrap.outgoingWindowFrames());
            assertEquals("淡化不是用户设的窗口吸到整数小节",
                    Math.round(NOMINAL_WINDOW / barSecs) * barSecs * RATE, fadeFrames, 1.0);
            assertEquals("接缝不是第一段的终点（= 吸到小节线之后的 vamp 起点）",
                    vampFrom, wrap.loopCutSecs(), 0.0);
            // 丢尾 = 全曲 − (接缝 + 窗口)
            double tail = a.durationSecs - (vampFrom + windowFrames / (double) RATE);
            assertTrue("判据构造的路径本身放不进曲子（丢尾 " + tail + "）", tail >= 0.0);

            // 整窗渲染：按编排器的方式逐块推进（B 按 introStartAt 挡在门外）
            int seamFrames = (int) Math.round(AutoCueTransition.LOOP_SEAM_FADE_SECS * RATE);
            float introStartAt = (float) ((double) (windowFrames - fadeFrames)
                    / (double) windowFrames);
            Rendered r = render(wrap, windowFrames, vampFrom, 0.0, introStartAt);
            assertTrue("整窗渲染几乎是空的", r.frames > windowFrames - Deck.RENDER_FRAMES);
            assertEquals("喂进接法的素材长度不对（" + recorder.fedFrames + " 帧）",
                    windowFrames, recorder.fedFrames);

            int at = 0;
            // ① 段 1：A 的尾奏 8 小节 → A[vampFrom − 8 小节, vampFrom)
            //    （整条路径开头 seamFrames 帧是有意的接缝淡入，比对的起点跳过它）
            int src1 = (int) Math.round((vampFrom - 8 * barSecs) * RATE);
            int d1 = measureShift(recorder.fed, at, fx.pcmA, src1, pass1, seamFrames + 1);
            assertTrue("第一段对不上 A 的尾奏（源下标偏了 " + d1 + " 帧）", Math.abs(d1) <= 3);
            assertPass("第一段（A 的尾奏 8 小节）", recorder.fed, at, d1, fx.pcmA, src1, pass1,
                    seamFrames + 1);
            at += pass1;
            // ② 段 2：A 的 vamp 4 小节 × 2 → 两遍都是 A[vampFrom, vampFrom + 4 小节)
            int src2 = (int) Math.round(vampFrom * RATE);
            int d2 = measureShift(recorder.fed, at, fx.pcmA, src2, pass2, 0);
            assertTrue("第二段对不上 A 的 vamp（源下标偏了 " + d2 + " 帧）", Math.abs(d2) <= 3);
            assertPass("第二段第一遍（A 的 vamp）", recorder.fed, at, d2, fx.pcmA, src2, pass2, 0);
            assertPass("第二段第二遍（重复的那一遍）", recorder.fed, at + pass2, d2, fx.pcmA,
                    src2, pass2, 0);
            at += 2 * pass2;
            // ③ 段 3：B[bFrom, bFrom + 4 小节)（**换了源**：这一段只能从进场曲读）
            int src3 = (int) Math.round(bFrom * RATE);
            int d3 = measureShift(recorder.fed, at, fx.pcmB, src3, pass3, 0);
            assertTrue("第三段对不上 B 的 hi-hat 段（源下标偏了 " + d3 + " 帧）", Math.abs(d3) <= 3);
            assertPass("第三段（B 的 4 小节）", recorder.fed, at, d3, fx.pcmB, src3, pass3, 0);
            // 换成"出场曲在同一个时刻的素材"就对不上——这条证明第三段真的换了源
            assertTrue("第三段与 A 在同一时刻的素材一样——这条判据分不出换了源",
                    differs(recorder.fed, at, fx.pcmA, src3, pass3));

            // ④ 顺序的另一半：第一段与"接缝处的自然延续"明显不同（A 被重放，不是接着播）
            double continuationDiff = 0.0;
            int srcCut = (int) Math.round(vampFrom * RATE);
            for (int i = seamFrames; i < pass1; i++) {
                continuationDiff = Math.max(continuationDiff,
                        Math.abs(recorder.fed[i] - fx.pcmA[srcCut + i]));
            }
            assertTrue("窗口头与「接缝处的自然延续」几乎一样（最大差 " + continuationDiff
                            + "）——这条判据分不出重放与延续",
                    continuationDiff > 0.02);

            // ⑤ 内层接法看到的进度：前 16 小节恒为 0（"只放 A"），之后从 0 走到 1
            assertTrue("内层一次都没被调到", recorder.progressSeen.size() > 8);
            float previous = -1f;
            for (Float p : recorder.progressSeen) {
                assertTrue("进度回跳了：" + previous + " → " + p, p.floatValue() >= previous);
                previous = p.floatValue();
            }
            assertEquals("最前一块的进度不是 0（B 在路径前段就进来了）",
                    0f, recorder.progressSeen.get(0).floatValue(), 0f);
            float last = recorder.progressSeen.get(recorder.progressSeen.size() - 1).floatValue();
            assertTrue("最后一块的进度离 1 太远（" + last + "）——淡化没在路径末尾结束",
                    last >= 1.0f - (float) Deck.RENDER_FRAMES / (float) fadeFrames - 1e-4f);
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 判据五：多段路径里的重复段真的重复
    // ==================================================================

    /**
     * <b>中间那一段的第二遍与第一遍逐位相同</b>（"只读一遍、游标绕回"）。
     *
     * <p>与判据四同一份路径（只是第一段换成 4 小节），但这一条只盯重复：把重复段的两遍
     * 摆在一起逐位比。它单独成条的理由与
     * {@code AutoCueOutroLoopTest#repeatedPassesAreBitIdenticalToTheFirstOne} 一样——
     * "重复"这件事在产物上很容易被淡化增益盖住（那里也叠着等功率曲线），
     * 所以比的是<strong>喂进来的素材</strong>，不是产物。
     */
    @Test
    public void repeatedSegmentsRepeatTheSameSamplesInAMultiSegmentPath() {
        PathFixture fx = fixture();
        RecordingCrossfade recorder = new RecordingCrossfade(NOMINAL_WINDOW);
        try {
            AutoCue.Envelope a = fx.outroEnv;
            double barSecs = 4.0 * 60.0 / a.tempo.bpm;
            double phase = AutoCue.barPhaseOf(a, barSecs);
            double vampFrom = AutoCue.snapToBar(AutoCue.outroVampSpan(a).from, phase, barSecs);
            double bFrom = AutoCue.snapToBar(22.5, phase, barSecs);
            List<AutoCue.Segment> path = new ArrayList<AutoCue.Segment>();
            path.add(new AutoCue.Segment("outro", vampFrom - 4 * barSecs, vampFrom, 1));
            path.add(new AutoCue.Segment("outro", vampFrom, vampFrom + 4 * barSecs, 2));
            path.add(new AutoCue.Segment("intro", bFrom, bFrom + 4 * barSecs, 1));
            AutoCue.Cue cue = new AutoCue.Cue(0.0, 0.0, NOMINAL_WINDOW, 0.0, 0.0, "判据",
                    0.5, true, null, path);
            AutoCueTransition wrap = new AutoCueTransition(recorder, RATE, 1, null, false);
            wrap.prepareLoopOutgoing(SRC_A, null, SRC_B, null, a, fx.introEnv, cue,
                    NOMINAL_WINDOW);
            assertTrue("多段路径没有被当成路径", wrap.usesExplicitPath());

            int pass = framesOf(4 * barSecs);
            long windowFrames = 4L * pass;
            recorder.arm(wrap.windowFrames(RATE));
            float introStartAt = (float) ((double) (windowFrames - pass)
                    / (double) windowFrames);
            render(wrap, windowFrames, vampFrom, 0.0, introStartAt);
            assertEquals("喂进接法的素材长度不对", windowFrames, recorder.fedFrames);

            int firstPassAt = pass;                  // 第二段的第一遍从这里开始
            int first = -1;
            for (int i = 0; i < pass; i++) {
                if (Float.floatToRawIntBits(recorder.fed[firstPassAt + i])
                        != Float.floatToRawIntBits(recorder.fed[firstPassAt + pass + i])) {
                    first = i;
                    break;
                }
            }
            assertEquals("第二遍与第一遍不是同一份样本（第一个不同在下标 " + first
                    + "）——重复没有按「只读一遍、游标绕回」的方式做", -1, first);
            // 反过来：第一段的素材与重复段不同（否则这条判据可能比的是同一段）
            assertTrue("第一段与重复段是同一份素材，这条判据分不出「重复」与「一直在放同一段」",
                    differs(recorder.fed, 0, recorder.fed, firstPassAt, pass));
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 判据六：已知边界——第一段必须是出场曲的素材
    // ==================================================================

    /**
     * <b>路径的第一段是进场曲时如实退回</b>，而不是猜一个接缝出来。
     *
     * <p>理由是"接缝 = A 自然播到哪"这件事只有出场曲有定义（见
     * {@code AutoCueTransition.prepareFromPath} 的注释）。这条路径在听感上并非无意义
     * （"B 的鼓组铺底"那一类），但它要的接缝语义与今天这套触发点记账不同，
     * 属于<strong>还没有支持的形状</strong>——如实退回普通交叉淡化，并留一行 warn。
     */
    @Test
    public void aPathThatStartsWithTheIncomingTrackIsRefusedInsteadOfGuessed() {
        PathFixture fx = fixture();
        Crossfade base = new Crossfade((float) NOMINAL_WINDOW);
        try {
            double barSecs = 4.0 * 60.0 / fx.outroEnv.tempo.bpm;
            List<AutoCue.Segment> path = new ArrayList<AutoCue.Segment>();
            path.add(new AutoCue.Segment("intro", 22.5, 22.5 + 4 * barSecs, 1));
            path.add(new AutoCue.Segment("outro", 60.0, 60.0 + 4 * barSecs, 2));
            AutoCue.Cue cue = new AutoCue.Cue(0.0, 0.0, NOMINAL_WINDOW, 0.0, 0.0, "判据",
                    0.5, true, null, path);
            AutoCueTransition wrap = new AutoCueTransition(base, RATE, 1, null, false);
            wrap.prepareLoopOutgoing(SRC_A, null, SRC_B, null, fx.outroEnv, fx.introEnv, cue,
                    NOMINAL_WINDOW);
            assertFalse("第一段是进场曲的路径不该被接上", wrap.usesExplicitPath());
            assertEquals("被退回的路径却留了半条队列", 0, wrap.outgoingSegmentCount());
            assertEquals("被退回时窗口被动过了",
                    base.windowFrames(RATE), wrap.windowFrames(RATE));

            // 另一种退回：这一层没拿到进场曲的源，而路径里要它的素材
            List<AutoCue.Segment> needsIntro = new ArrayList<AutoCue.Segment>();
            needsIntro.add(new AutoCue.Segment("outro", 45.0, 60.0, 1));
            needsIntro.add(new AutoCue.Segment("intro", 22.5, 30.0, 1));
            AutoCue.Cue cue2 = new AutoCue.Cue(0.0, 0.0, NOMINAL_WINDOW, 0.0, 0.0, "判据",
                    0.5, true, null, needsIntro);
            AutoCueTransition noIntro = new AutoCueTransition(base, RATE, 1, null, false);
            noIntro.prepareLoopOutgoing(SRC_A, null, fx.outroEnv, cue2, NOMINAL_WINDOW);
            assertFalse("没拿到进场曲的源却把路径接上了", noIntro.usesExplicitPath());
            assertEquals("半条路径被留下了", 0, noIntro.outgoingSegmentCount());
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 夹具：合成素材 + 假解码器
    // ==================================================================

    /** 一对合成素材（A 105 s、B 75 s，同一个 BPM）与它扫出来的包络。 */
    private static final class PathFixture {
        final float[] pcmA;
        final float[] pcmB;
        final AutoCue.Envelope outroEnv;
        final AutoCue.Envelope introEnv;

        PathFixture(float[] pcmA, float[] pcmB, AutoCue.Envelope outroEnv,
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

    /** 「买时间」那一档的素材（与 {@code AutoCueOutroLoopBuyTimeTest} 同一形状）。 */
    private static final class BuyTimeFixture {
        final float[] pcmA;
        final float[] pcmB;
        final AutoCue.Envelope outroEnv;
        final AutoCue.Envelope introEnv;
        final double barSecs;
        final double phase;

        BuyTimeFixture(float[] pcmA, float[] pcmB, AutoCue.Envelope outroEnv,
                       AutoCue.Envelope introEnv, double barSecs, double phase) {
            this.pcmA = pcmA;
            this.pcmB = pcmB;
            this.outroEnv = outroEnv;
            this.introEnv = introEnv;
            this.barSecs = barSecs;
            this.phase = phase;
        }

        void close() {
            Decoder.install(null);
        }
    }

    private static PathFixture fixture() {
        final float[] a = trackA();
        final float[] b = trackB();
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, java.util.Map<String, String> headers) {
                return source != null && source.contains("path-b")
                        ? new Fake(b, RATE) : new Fake(a, RATE);
            }
        });
        return new PathFixture(a, b, AutoCue.scan(SRC_A, null), AutoCue.scanIntro(SRC_B, null));
    }

    /**
     * 「买时间」那一档的素材：A 24 小节 = 45.0 s（vamp 8 小节之后只剩 3.75 s，
     * 放不下 7.5 s 窗口）、B 32 小节 = 60.0 s、同一个 BPM。
     *
     * <p>与 {@code AutoCueOutroLoopBuyTimeTest} 的合成素材同一形状（那边的方法是 private，
     * 所以这里抄一份——那份判据自己也说明了它是照 {@code AutoCueOutroLoopTest} 抄的）。
     * 它存在的意义是：<strong>让计划层真的走进"买时间"那一档</strong>，
     * 于是判据二、三测的是生产路径上真会发生的形状。
     */
    private static BuyTimeFixture buyTimeFixture() {
        final float[] a = buyTimeTrackA();
        final float[] b = buyTimeTrackB();
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, java.util.Map<String, String> headers) {
                return source != null && source.contains("path-b")
                        ? new Fake(b, RATE) : new Fake(a, RATE);
            }
        });
        AutoCue.Envelope outro = AutoCue.scan(SRC_A, null);
        AutoCue.Envelope intro = AutoCue.scanIntro(SRC_B, null);
        double barSecs = 4.0 * 60.0 / outro.tempo.bpm;
        return new BuyTimeFixture(a, b, outro, intro, barSecs,
                AutoCue.barPhaseOf(outro, barSecs));
    }

    /** 秒 → 帧（**整段一次性换算**，与实现同一把尺子：不是"每小节取整再乘"）。 */
    private static int framesOf(double secs) {
        return (int) Math.round(secs * RATE);
    }

    /** 走生产那条计划路径（与 {@code AutoCueTransition.prepare} 里的调用完全一致）。 */
    private static AutoCue.Cue cueOf(PathFixture fx) {
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
                        // vamp：每拍底鼓 + 反拍 hat；每 4 小节的第一拍加重音当"地标"，
                        // 于是"倒回段首重播"与"接着往下播"能用样本逐位比出来。
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

    /** 素材 A：105 s、128 BPM、56 小节，可循环的 vamp 落在 [60, 75)（8 小节）。 */
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
        append(kinds, levels, bar += 8, "kkkkhhhh", 0.8f);  // [45, 60) ← 判据四的第一段
        append(kinds, levels, bar += 8, "vvvvvvvv", 1.0f);  // [60, 75) ← 可循环段
        append(kinds, levels, bar += 8, "oooooooo", 0.35f); // [75, 90)
        append(kinds, levels, bar + 8, "ffffffff", 0.5f);   // [90, 105)
        return synth(128.0, kinds.toString(), levels);
    }

    /** 素材 B：75 s、128 BPM、40 小节；[22.5, 30) 是 4 小节纯 hi-hat（判据的第三段）。 */
    private static float[] trackB() {
        StringBuilder kinds = new StringBuilder();
        float[] levels = new float[64];
        int bar = 0;
        for (int i = 0; i < 8; i++, bar++) {
            kinds.append('i');
            levels[bar] = 0.3f;
        }
        append(kinds, levels, bar, "kkkkhhhh", 0.75f);      // [15, 30)
        append(kinds, levels, bar += 8, "hhhhkkkk", 0.8f);  // [30, 45)
        append(kinds, levels, bar += 8, "kkkkvvvv", 0.9f);  // [45, 60)
        append(kinds, levels, bar + 8, "ffffffff", 0.5f);   // [60, 75)
        return synth(128.0, kinds.toString(), levels);
    }

    /** 「买时间」那一档的 A：24 小节 = 45.0 s，vamp [26.25, 41.25) 之后只剩 2 小节。 */
    private static float[] buyTimeTrackA() {
        StringBuilder kinds = new StringBuilder();
        float[] levels = new float[32];
        int bar = 0;
        for (int i = 0; i < 4; i++, bar++) {
            kinds.append('i');
            levels[bar] = 0.25f;
        }
        append(kinds, levels, bar, "kkkkhhhh", 0.85f);      // 4..11  [7.5, 22.5)
        append(kinds, levels, bar += 8, "kk", 0.85f);       // 12..13 [22.5, 26.25) 过门
        append(kinds, levels, bar += 2, "vvvvvvvv", 1.0f);  // 14..21 [26.25, 41.25) vamp
        append(kinds, levels, bar + 8, "ff", 0.45f);        // 22..23 [41.25, 45.0) 稀疏收尾
        return synth(128.0, kinds.toString(), levels);
    }

    /** 「买时间」那一档的 B：32 小节 = 60.0 s，同一个 BPM（不落进"对齐网格"那一档）。 */
    private static float[] buyTimeTrackB() {
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
        return synth(128.0, kinds.toString(), levels);
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
     * 等功率交叉淡化 + <strong>记录喂进来的素材</strong>。
     *
     * <p>为什么记"喂进来的"而不是"产出的"：{@code from} 上叠着等功率增益、{@code to}
     * 上叠着 B 的素材与另一条增益曲线，逐位比产物只能证明"两边一样"，
     * 证明不了"这一段素材是从源的那一段读的"。记素材就能直接与源 PCM 逐位比。
     */
    private static final class RecordingCrossfade implements Transition {
        private final Crossfade inner;
        final List<Float> progressSeen = new ArrayList<Float>();
        /** 喂进来的出场素材（按块拼接，只取接法**真正吃掉**的那些帧）。 */
        float[] fed;
        int fedFrames;

        RecordingCrossfade(double seconds) {
            this.inner = new Crossfade((float) seconds);
        }

        /** 挂上记录缓冲（整窗那么长）——必须在渲染**之前**调。 */
        void arm(long windowFrames) {
            fed = new float[(int) (windowFrames + Deck.RENDER_FRAMES)];
        }

        @Override
        public long windowFrames(int sampleRate) {
            return inner.windowFrames(sampleRate);
        }

        @Override
        public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
            progressSeen.add(Float.valueOf(progress));
            int n = inner.render(out, from, to, progress);
            java.nio.FloatBuffer src = from.channel(0);
            int take = Math.max(0, Math.min(n, from.frames()));
            for (int i = 0; i < take; i++) {
                if (fedFrames < fed.length) {
                    fed[fedFrames++] = src.get(i);
                }
            }
            return n;
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
     * 进场曲按 {@code introStartAt}**挡在门外**（路径前半段正是靠它"只放 A"）。
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
            while (pos < windowFrames && guard++ < 100_000) {
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

    /**
     * <b>这一段实际读到的源下标比期望偏了几帧</b>（在 ±3 帧里取最像的那个）。
     *
     * <p>为什么要允许偏移：段起点是<strong>秒</strong>，而解码器的 seek 只到帧
     * （{@code (int)(secs × rate)} 是截断），最多差一帧；判据自己算的期望值也各差半帧。
     * 这条容差是"秒 ↔ 帧"换算的代价，不是"差不多就行"——偏出 ±3 帧就说明读错了地方。
     */
    private static int measureShift(float[] out, int o0, float[] src, int s0, int length,
                                    int skip) {
        int best = Integer.MIN_VALUE;
        int bestMatches = -1;
        for (int delta = -3; delta <= 3; delta++) {
            int matches = 0;
            for (int i = skip; i < length; i++) {
                if (o0 + i >= out.length || s0 + delta + i >= src.length) {
                    break;
                }
                if (Float.floatToRawIntBits(out[o0 + i])
                        == Float.floatToRawIntBits(src[s0 + delta + i])) {
                    matches++;
                }
            }
            if (matches > bestMatches) {
                bestMatches = matches;
                best = delta;
            }
        }
        return best;
    }

    /**
     * <strong>逐位</strong>比一段（{@code out[o0 + i] == src[s0 + delta + i]}），
     * 开头 {@code skip} 帧不参与（那是整条路径的接缝淡入）。
     */
    private static void assertPass(String what, float[] out, int o0, int delta, float[] src,
                                   int s0, int length, int skip) {
        int checked = 0;
        int firstMismatch = -1;
        for (int i = skip; i < length; i++) {
            if (o0 + i >= out.length || s0 + delta + i >= src.length) {
                break;
            }
            checked++;
            if (Float.floatToRawIntBits(out[o0 + i])
                    != Float.floatToRawIntBits(src[s0 + delta + i])) {
                firstMismatch = i;
                break;
            }
        }
        assertEquals(what + " 与源 PCM 的对应区间不一致（段内第 " + firstMismatch + " 个采样）",
                -1, firstMismatch);
        assertTrue(what + " 只比了 " + checked + " 个采样，这条判据没测到东西",
                checked > (length - skip) / 2);
    }

    /** {@code a[at..]} 与 {@code b[bt..]} 在 {@code length} 帧内有没有差。 */
    private static boolean differs(float[] a, int at, float[] b, int bt, int length) {
        for (int i = 0; i < length; i++) {
            if (at + i >= a.length || bt + i >= b.length) {
                return true;
            }
            if (Float.floatToRawIntBits(a[at + i]) != Float.floatToRawIntBits(b[bt + i])) {
                return true;
            }
        }
        return false;
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

    /**
     * 在 {@code expected} 附近 ±3 帧里找"这一段最像源素材哪一帧"的那个落点
     * （返回的是"输出下标 0 处对应的源下标"）。
     */
    private static int align(float[] out, int from, float[] src, int expected) {
        int best = -1;
        int bestMatches = 0;
        for (int delta = -3; delta <= 3; delta++) {
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
     * （22050 Hz 下 45 µs）。实现那边把"几小节"<strong>一次性</strong>换算成帧
     * （不是"每小节取整再乘"），所以残差只有一次舍入（≤ 半帧）、<strong>不随小节数累积</strong>。
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
