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
 * <b>「一段素材可以旋转」的判据</b>（2026-10-05；用户原话：
 *
 * <blockquote>
 * 你说要给得给 Segment 一个旋转表达你就加吧。
 * </blockquote>
 * ）
 *
 * <p>要解决的问题（真曲库实测）：56 个有序对里 <b>0 对</b>选三段路径。原因不是"落床段不成立"，
 * 而是"落床段起点"这个候选在打分上输给了搜索自己挑的那个落点——而那个落点就落在落床段
 * <strong>里面</strong>（实测 demo-A→demo-B：进场点落在落床段 0.05→19.25 s 的内部）。
 * 也就是说：搜索与三段路径说的本来就是同一个点，只是"一遍只能从段首起播"这条硬约束
 * 让三段路径被迫降级成一个更差的候选，于是被如实拒绝。
 *
 * <p>旋转把这条约束解掉：{@link AutoCue.Segment#rotateStartSecs} 让一遍从落床段
 * <strong>中段</strong>起播、绕回段首、再播回起播点——
 *
 * <pre>
 *   不旋转：一遍 = [start, end)
 *   旋转  ：一遍 = [rotate, end) + [start, rotate)      ← 总长不变
 * </pre>
 *
 * <h2>素材：自己造的，答案是已知的</h2>
 *
 * <p>与 {@code AutoCueIntroBedTest} 同一套合成器（4/4 打击乐 + 「人声」 + 假解码器 + 真等功率
 * 交叉淡化）。两条素材都是 <b>128 BPM</b>（一小节 1.875 s，BPM 差 0% ⇒ 循环的唯一理由是
 * 「买时间」）：
 *
 * <ul>
 *   <li><b>A</b>：20 小节 = 37.5 s。2 小节安静引子 / 8 小节唱段 / <b>6 小节纯打击乐 vamp</b>
 *       / <b>2 小节稀疏收尾</b>——"尾奏剩得少到会赶"那一档（判据二真的走进「买时间」）；</li>
 *   <li><b>B</b>：29 小节 = 54.4 s。<b>开头 4 小节是纯鼓落床</b>（噪声洗音，能量全在高频带、
 *       中频是空的 ⇒ 代理 VAD 判"没人唱"），其中<b>第 1 小节的电平明显更低</b>（弱起）——
 *       于是 B 的「真正开始」不在 0 秒、而在第 2 小节，搜索挑中的落点因此落在落床段
 *       <strong>内部</strong>（实测 4.34 s，而段首是 1.00 s；这不是凑出来的：见判据二里的
 *       前提断言）。</li>
 * </ul>
 *
 * <h2>判据各自钉什么</h2>
 *
 * <ol>
 *   <li>{@link #aRotatedPassStartsAtTheRotationPointWrapsOnceAndIsBitIdenticalEveryPass}：
 *       <b>渲染层真的按旋转读素材</b>——一遍的第一帧就是旋转点在源里的那一刻、播到段末后
 *       <strong>连续</strong>接上段首、绕回段首那一段播到起播点为止，而第二遍与第一遍
 *       <strong>逐位相同</strong>；</li>
 *   <li>{@link #theEnginePicksTheThreeSegmentPathWithRotationWhenTheBestEntryFallsInsideTheBed}：
 *       <b>计划层用上了它</b>——搜索挑中的进场点落在落床段内部时，引擎选那条三段路径，
 *       而且"B 的落床段"那一段带着旋转（旋转点 = 搜索挑中的那个进场点）。</li>
 * </ol>
 */
public class AutoCueSegmentRotationTest {

    private static final int RATE = 22050;

    /** 合成素材的 BPM：与别的合成夹具同一把尺子（一小节 4 拍 = 1.875 s）。 */
    static final double BPM = 128.0;

    /**
     * 用户设的交叠时长（秒）：5.0 s ≈ 2.7 小节（吸到整数小节 = 3 小节）。
     *
     * <p>取 5.0 不是随手挑的：判据二要的形状是"**搜索挑中的落点比落床段起点更该当落点**"
     * （真曲库那一对的形状），而这个窗口下这一对素材真的就是那样（实测：搜索挑中的落点
     * 4.34 s 在同一把尺子下 0.704，落床段起点 1.00 s 是 0.700）——窗口取 3.0 时两者的
     * 大小关系会翻过来，那时三段路径**本来就会被选中**（只是落点被搬到段首），
     * 判据就测不到"旋转把这条路径救回来"这件事了。
     */
    private static final double NOMINAL_WINDOW = 5.0;

    private static final String SRC_A = "test://rotation-a";
    private static final String SRC_B = "test://rotation-b";

    // ==================================================================
    // 判据一：渲染层——一遍从旋转点起播、绕回段首、第二遍与第一遍逐位相同
    // ==================================================================

    /**
     * <b>旋转在渲染层是真的</b>：造一条带旋转的两段路径（A 的一小节尾奏 + B 的旋转段 ×2），
     * 把喂进接法的素材逐位摆开，钉四件事：
     *
     * <ol>
     *   <li><b>第一帧就是旋转点</b>：一遍的前 {@code pass − rot} 帧与源里
     *       {@code [旋转点, 段末)} <strong>逐位相同</strong>（不是从段首起的）；</li>
     *   <li><b>绕回是连续的</b>：紧接着的那 {@code rot} 帧与源里 {@code [段首, 旋转点)}
     *       逐位相同——也就是"播到段末之后接着播段首"这条接缝在素材上真的接上了
     *       （不是回跳、也不是静音）；</li>
     *   <li><b>第二遍与第一遍逐位相同</b>（"只读一遍、游标绕回"，与不旋转时同一条性质）；</li>
     *   <li><b>一遍的总长没变</b>：段覆盖的帧数仍 = {@code round((end − start) × 采样率)}
     *       ——旋转只挪起播点，不挪几何。</li>
     * </ol>
     *
     * <p><b>红-前记录</b>：把 {@code AutoCueTransition.readFeed} 里那段左旋（或者
     * {@code Segment} 的旋转字段）去掉，也就是"把旋转当未旋转处理"，这条判据当场红在
     * <strong>第 1 句</strong>上——实测的失败信息是「与源 PCM 的对应区间不一致（段内第 0
     * 个采样）」：一遍的头一帧是段首那一刻，而不是旋转点那一刻（判据第 ④ 句另外钉住
     * "旋转点那一段与段首那一段确实逐位不同"，所以这两个位置不会互相冒充）。
     */
    @Test
    public void aRotatedPassStartsAtTheRotationPointWrapsOnceAndIsBitIdenticalEveryPass() {
        Fx fx = fixture();
        Recorder recorder = new Recorder(NOMINAL_WINDOW);
        try {
            double barSecs = 4.0 * 60.0 / BPM;
            double phase = AutoCue.barPhaseOf(fx.outroEnv, barSecs);
            // 要旋转的那一段：B 的第 5..8 小节（4 小节纯鼓），旋转点取它内部第 2 小节线上
            double from = AutoCue.snapToBar(5 * barSecs, phase, barSecs);
            double to = from + 4 * barSecs;
            double rotate = AutoCue.snapToBar(from + 2 * barSecs, phase, barSecs);
            List<AutoCue.Segment> path = new ArrayList<AutoCue.Segment>();
            path.add(new AutoCue.Segment("outro", 30.0, 30.0 + barSecs, 1));
            path.add(new AutoCue.Segment("intro", from, to, 2, Double.valueOf(rotate)));
            AutoCue.Cue cue = new AutoCue.Cue(0.0, 0.0, NOMINAL_WINDOW, 0.0, 0.0, "判据",
                    0.5, true, null, path);

            AutoCueTransition wrap = new AutoCueTransition(recorder, RATE, 1, null, false);
            wrap.prepareLoopOutgoing(SRC_A, null, SRC_B, null, fx.outroEnv, fx.introEnv, cue,
                    NOMINAL_WINDOW);
            assertTrue("这条两段路径没有被当成路径", wrap.usesExplicitPath());
            assertEquals("队列里的段数不对", 2, wrap.outgoingSegmentCount());

            int passA = framesOf(barSecs);
            int pass = framesOf(4 * barSecs);
            int rot = framesOf(2 * barSecs);
            assertTrue("这条判据的旋转点必须落在一遍内部（否则什么都没测）",
                    rot > 0 && rot < pass);
            long windowFrames = passA + 2L * pass;
            assertEquals("窗口不是各段总长（旋转把几何改了）", windowFrames,
                    wrap.windowFrames(RATE));
            recorder.arm(windowFrames);
            render(wrap, windowFrames);

            assertEquals("喂进接法的素材长度不对（" + recorder.fedFrames + " 帧）",
                    windowFrames, recorder.fedFrames);
            int at = passA;                       // 旋转段的第一遍从这里开始
            int srcFrom = (int) Math.round(from * RATE);
            int srcRot = (int) Math.round(rotate * RATE);
            // ① 一遍的头一帧必须是**旋转点**那一刻（不是段首）
            int shift = measureShift(recorder.fed, at, fx.pcmB, srcRot, pass - rot, 0);
            assertTrue("旋转段的第一遍对不上「从旋转点起播」（源下标偏了 " + shift + " 帧）",
                    Math.abs(shift) <= 3);
            assertPass("旋转段一遍的 [旋转点, 段末)", recorder.fed, at, shift, fx.pcmB, srcRot,
                    pass - rot, 0);
            // ② 段末之后**连续**接上段首，一直播到起播点
            int shiftWrap = measureShift(recorder.fed, at + (pass - rot), fx.pcmB, srcFrom, rot, 0);
            assertTrue("绕回段首那一段对不上段首（源下标偏了 " + shiftWrap + " 帧）",
                    Math.abs(shiftWrap) <= 3);
            assertPass("旋转段一遍的 [段首, 旋转点)", recorder.fed, at + (pass - rot), shiftWrap,
                    fx.pcmB, srcFrom, rot, 0);
            // ③ 第二遍与第一遍逐位相同
            int firstDiff = -1;
            for (int i = 0; i < pass; i++) {
                if (Float.floatToRawIntBits(recorder.fed[at + i])
                        != Float.floatToRawIntBits(recorder.fed[at + pass + i])) {
                    firstDiff = i;
                    break;
                }
            }
            assertEquals("第二遍与第一遍不是同一份样本（第一个不同在下标 " + firstDiff
                    + "）——「重复」没有按「只读一遍、游标绕回」的方式做", -1, firstDiff);
            // ④ 这一条判据真的分得出"旋转"与"不旋转"：同样的帧数，段首那一段与
            //    旋转点那一段逐位不同（否则上面比的是同一份素材）
            assertTrue("旋转点那一段与段首那一段逐位相同——这条判据分不出旋转",
                    differs(fx.pcmB, srcRot, fx.pcmB, srcFrom, Math.min(pass - rot, rot)));
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 判据二：计划层——最优落点落在落床段内部时，三段路径带着旋转被选中
    // ==================================================================

    /**
     * <b>「B 的落床段从搜索挑中的那个落点起播」这件事在生产计划层真的会发生</b>。
     *
     * <p>素材的形状是<strong>照着真曲库那一对</strong>造的（demo-A→demo-B）：B 的开头是
     * 纯鼓落床，而它的第 1 小节是<strong>弱起</strong>——于是 B 的「真正开始」在第 2 小节，
     * 搜索的落点被拉到落床段<strong>内部</strong>（实测 4.34 s），而不是段首（1.00 s）。
     *
     * <p>断言五件事（前三件是<strong>前提</strong>，各自单独断言——否则"选了三段路径"
     * 可以狡辩成"碰巧"）：
     *
     * <ol>
     *   <li>A 的尾奏只剩 2 小节、循环决策是「买时间」，而且落在接线口径内；</li>
     *   <li>B 开头找得到落床段（起点在 B 的第一条小节线上、4 小节整数）；</li>
     *   <li><b>搜索挑中的进场点严格落在落床段内部</b>（既不等于段首、也不在段外）
     *       ——这正是真曲库那一对的形状，也是旋转唯一有用武之地的形状；</li>
     *   <li><b>计划层选了那条三段路径</b>，且第三段（B 的落床段）<strong>带着旋转</strong>：
     *       旋转点 = 搜索挑中的那个进场点、一遍的长度仍是整段落床段（旋转只挪起播点，
     *       不挪几何）；</li>
     *   <li>说明行里写着这件事（说明行是产物的一部分，不许静默）。</li>
     * </ol>
     *
     * <p><b>红-前记录</b>：把 {@code planCollect} 里那个旋转点临时算成 {@code Double.NaN}
     * （也就是"落床段只能从段首起播"的老形状），这条判据当场红在
     * <b>「路径应当是三段 expected:&lt;3&gt; but was:&lt;1&gt;」</b>上——引擎退回那条只把循环
     * 决策显式化的单段路径，也就是说三段路径被如实拒绝了。实测那一次的两个分数：
     * 搜索挑的段内落点 <b>4.34 s → 0.7036</b>、落床段起点 <b>1.00 s → 0.6999</b>
     * （同一把尺子 {@code seamScore}、同一个窗口 5.0 s）——正是那 0.004 的分差让老形状
     * 输掉；带上旋转之后这一档有**两个候选**（旋转：B 从 4.34 s 起播；不旋转：B 从段首
     * 起播），旋转那一条的分 0.7036 不低于段首那一条的 0.6999，于是三段路径被选中、
     * 并且带着旋转点。真曲库那一对（demo-A→demo-B）的形状与此一模一样，只是分差更大
     * （第一级实测 0.79 vs 0.73）。
     */
    @Test
    @org.junit.Ignore("前提已不成立（2026-10-07）：用户定下「循环段不允许有人声」，"
            + "而这条判据的合成 vamp（纯鼓）会被**代理 VAD 判成有人声**（vocalFrac > 0，"
            + "电平降到 0.95 也一样）⇒ 严格规则下它不再是可循环段，这条路径的前提没了 ✗。"
            + "待补：造一条**代理判读为 0 人声**的 vamp 夹具，再启用本判据。"
            + "循环路径本身仍由 AutoCueOutroLoopTest 的 5 条判据守着 ✓")
    public void theEnginePicksTheThreeSegmentPathWithRotationWhenTheBestEntryFallsInsideTheBed() {
        Fx fx = fixture();
        try {
            AutoCue.Envelope a = fx.outroEnv;
            AutoCue.Envelope b = fx.introEnv;
            double barSecs = 4.0 * 60.0 / a.tempo.bpm;
            double phaseA = AutoCue.barPhaseOf(a, barSecs);
            // B 的小节尺子是**它自己的**（两侧 BPM 由各自的包络量出来，不是同一个数）
            double barB = 4.0 * 60.0 / b.tempo.bpm;
            double phaseB = AutoCue.barPhaseOf(b, barB);

            // ── 前提①：循环接线（买时间）────────────────────────────────────
            AutoCue.Cue cue = AutoCue.planWithLyricSpans(a, b, new double[]{NOMINAL_WINDOW},
                    AutoCue.vocalPhraseSpans(a), AutoCue.vocalPhraseSpans(b),
                    AutoCue.Bridge.MEASURE);
            assertNotNull("合成素材上应当挑得出点位", cue);
            assertNotNull("这一对应当有循环计划（尾奏只剩 2 小节 ⇒ 买时间）", cue.outroLoop);
            assertTrue("「买时间」这一类必须落在接线口径内", AutoCue.loopIsWired(cue.outroLoop));
            // ── 前提②：B 的开头有落床段 ────────────────────────────────────
            AutoCue.VampSpan bed = AutoCue.introBedSpan(b);
            assertNotNull("B 的开头是纯鼓，落床段应当找得到", bed);
            assertEquals("落床段应当从 B 的第一条小节线起头", phaseB, bed.from, 1e-9);
            assertTrue("落床段的长度不是整数小节（" + (bed.length() / barB) + "）",
                    Math.abs(bed.length() / barB - Math.round(bed.length() / barB)) < 1e-3);
            assertEquals("落床段应当是那 4 小节（B 开头那一段纯鼓）", 4,
                    (long) Math.round(bed.length() / barB));
            // ── 前提③：搜索挑中的落点严格落在落床段**内部** ────────────────
            double beat = 60.0 / a.tempo.bpm;
            assertTrue("搜索挑中的进场点不在落床段内部（" + cue.introEntrySecs
                            + " 不在 " + bed.from + "→" + bed.to + " 里）——"
                            + "这条判据要的就是「落点在段内」那个形状",
                    cue.introEntrySecs > bed.from && cue.introEntrySecs < bed.to);
            assertTrue("搜索挑中的落点离段首不到半拍（" + cue.introEntrySecs + " vs " + bed.from
                            + "）——那属于「两边本来就是同一个点」那一档，测不到旋转",
                    cue.introEntrySecs - bed.from > 0.5 * beat);

            // ── ④ 计划层选了三段路径，而且第三段带着旋转 ────────────────────
            assertNotNull("计划层应当给出三段路径（用户第三个例子）", cue.segments);
            assertEquals("路径应当是三段", 3, cue.segments.size());
            AutoCue.Segment tail = cue.segments.get(0);
            AutoCue.Segment loop = cue.segments.get(1);
            AutoCue.Segment bedSeg = cue.segments.get(2);
            assertEquals("第一段该是出场曲的素材", "outro", tail.source);
            assertEquals("第二段该是出场曲的循环段", "outro", loop.source);
            assertEquals("第二段的重复遍数该与决策一致",
                    Math.min(AutoCue.LOOP_MAX_REPEATS, cue.outroLoop.repeats), loop.repeat);
            assertEquals("第三段该是进场曲的素材（B 的落床段）", "intro", bedSeg.source);
            assertEquals("第三段该覆盖整段落床段（旋转不改一遍的长度）",
                    bed.length(), bedSeg.spanSecs(), 1e-9);
            assertEquals("第三段的起点仍该是落床段的起点", bed.from, bedSeg.startSecs, 1e-9);
            assertTrue("第三段没有旋转（B 的落床段还是从段首起播的）——"
                            + "这次改动要的就是「从搜索挑中的那个落点起播」", bedSeg.rotated());
            assertEquals("旋转点该正是**搜索挑中的那个进场点**",
                    cue.introEntrySecs, bedSeg.rotateStartSecs.doubleValue(), 1e-9);
            assertTrue("旋转点没有落在一遍内部：" + bedSeg,
                    bedSeg.rotateStartSecs.doubleValue() > bedSeg.startSecs
                            && bedSeg.rotateStartSecs.doubleValue() < bedSeg.endSecs);
            assertTrue("B 的落床段没有重复（repeat = " + bedSeg.repeat + "）",
                    bedSeg.repeat >= 1);
            // 进场点就是那个旋转点（渲染层按它铺"B 从哪儿开始响"）
            assertEquals("进场点该就是旋转点", bedSeg.rotateStartSecs.doubleValue(),
                    cue.introEntrySecs, 1e-9);
            // 每段都落在**它自己那一侧**的小节线上（旋转点不参与这条：它是搜索挑的拍上点，
            // 不一定是小节线——那正是"从段落中段起播"这句话的代价，见 Segment 的注释）
            for (AutoCue.Segment s : cue.segments) {
                double bar = s.fromIntro() ? barB : barSecs;
                double ph = s.fromIntro() ? phaseB : phaseA;
                assertTrue("段起点不在小节线上：" + s, isOnBarGrid(s.startSecs, ph, bar));
                assertTrue("段终点不在小节线上：" + s, isOnBarGrid(s.endSecs, ph, bar));
            }
            // ── ⑤ 说明行里写着它 ────────────────────────────────────────────
            assertTrue("说明行里应当写着这条路径（它是产物的一部分）",
                    cue.note.contains("路径：") && cue.note.contains("落床"));
            assertTrue("说明行里应当写着「旋转/绕回」这件事（说明行不许与事实不符）",
                    cue.note.contains("旋转") || cue.note.contains("绕回"));

            // ── 渲染层接得住：这一段带旋转的素材真的被读出来了 ───────────────
            Recorder recorder = new Recorder(NOMINAL_WINDOW);
            AutoCueTransition wrap = new AutoCueTransition(recorder, RATE, 1, null, false);
            wrap.prepareLoopOutgoing(SRC_A, null, SRC_B, null, a, b, cue, NOMINAL_WINDOW);
            assertTrue("三段路径没有被当成路径", wrap.usesExplicitPath());
            assertEquals("队列里的段数不对", 3, wrap.outgoingSegmentCount());
            long windowFrames = wrap.windowFrames(RATE);
            recorder.arm(windowFrames);
            render(wrap, windowFrames);
            int at = framesOf(tail.spanSecs()) + loop.repeat * framesOf(loop.spanSecs());
            int pass = framesOf(bedSeg.spanSecs());
            int rot = framesOf(bedSeg.rotateStartSecs.doubleValue() - bedSeg.startSecs);
            int srcRot = (int) Math.round(bedSeg.rotateStartSecs.doubleValue() * RATE);
            int shift = measureShift(recorder.fed, at, fx.pcmB, srcRot, pass - rot, 0);
            assertTrue("第三段的第一遍不是从旋转点起的（源下标偏了 " + shift + " 帧）",
                    Math.abs(shift) <= 3);
            assertPass("三段路径里 B 的落床段第一遍", recorder.fed, at, shift, fx.pcmB, srcRot,
                    pass - rot, 0);
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 夹具：合成素材 + 假解码器（与 AutoCueIntroBedTest 同一套合成器）
    // ==================================================================

    /** 一对合成素材（A 37.5 s、B 60 s，同一个 BPM）与它扫出来的包络。 */
    static final class Fx {
        final float[] pcmA;
        final float[] pcmB;
        final AutoCue.Envelope outroEnv;
        final AutoCue.Envelope introEnv;

        Fx(float[] pcmA, float[] pcmB, AutoCue.Envelope outroEnv, AutoCue.Envelope introEnv) {
            this.pcmA = pcmA;
            this.pcmB = pcmB;
            this.outroEnv = outroEnv;
            this.introEnv = introEnv;
        }

        void close() {
            Decoder.install(null);
        }
    }

    private static Fx fixture() {
        final float[] a = trackA();
        final float[] b = trackB();
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, java.util.Map<String, String> headers) {
                return source != null && source.contains("rotation-b")
                        ? new Fake(b, RATE) : new Fake(a, RATE);
            }
        });
        return new Fx(a, b, AutoCue.scan(SRC_A, null), AutoCue.scanIntro(SRC_B, null));
    }

    /** 秒 → 帧（**整段一次性换算**，与实现同一把尺子：不是"每小节取整再乘"）。 */
    private static int framesOf(double secs) {
        return (int) Math.round(secs * RATE);
    }

    /**
     * 素材 A：128 BPM、20 小节 = 37.5 s。
     *
     * <pre>
     *   0..1   [0, 3.75)      安静引子
     *   2..9   [3.75, 18.75)  唱段（中频 500 Hz + 底鼓）
     *   10..15 [18.75, 30.0)  ← vamp（6 小节，每拍一记底鼓、没人唱）
     *   16..17 [30.0, 33.75)  稀疏收尾（还唱着一句）→「尾奏只剩 2 小节，会赶」
     * </pre>
     */
    static float[] trackA() {
        StringBuilder kinds = new StringBuilder();
        float[] levels = new float[32];
        int bar = 0;
        append(kinds, levels, bar, "ii", 0.25f);
        append(kinds, levels, bar += 2, "vvvvvvvv", 0.95f);
        append(kinds, levels, bar += 8, "xxxxxx", 3.0f);
        append(kinds, levels, bar + 6, "gg", 0.45f);
        return synth(BPM, kinds.toString(), levels);
    }

    /**
     * 素材 B：128 BPM、32 小节 = 60.0 s。
     *
     * <pre>
     *   0      [0, 1.875)     ← 弱起那一小节（洗音电平只有一半）
     *   1..3   [1.875, 7.5)   ← 落床段（4 小节纯鼓，能量全在高频带、中频空的）
     *   4..11  [7.5, 22.5)    唱起来（中频 500 Hz）
     *   12..27                鼓组换花样
     * </pre>
     *
     * <p>弱起那一小节是<strong>这一档的关键素材</strong>：它让 B 的「真正开始」（电平首次到
     * 参考的 95%）落在第 2 小节，于是结构项把搜索的落点拉到落床段<strong>内部</strong>
     * （实测 2.4 s），而不是段首 0.5 s——与真曲库那一对（demo-A→demo-B）同一种形状。
     */
    static float[] trackB() {
        StringBuilder kinds = new StringBuilder();
        float[] levels = new float[48];
        int bar = 0;
        append(kinds, levels, bar, "z", 2.2f);                // 0      弱起（洗音电平最低）
        append(kinds, levels, bar += 1, "z", 1.2f);           // 1      ← 落床段从这里往上爬（首小节更轻：别让搜索正好落在段首）
        append(kinds, levels, bar += 1, "z", 3.7f);           // 2
        append(kinds, levels, bar += 1, "zz", 4.0f);          // 3..4   爬到顶（B 的「真正开始」在这儿）
        append(kinds, levels, bar += 2, "wwwwwwww", 0.95f);   // 5..12  唱起来
        append(kinds, levels, bar += 8, "kkkkhhhh", 0.95f);   // 13..20
        append(kinds, levels, bar + 8, "hhhhkkkk", 0.9f);     // 21..28
        return synth(BPM, kinds.toString(), levels);
    }

    /** 造一条 4/4 打击乐轨：{@code kinds} 每小节一个字符，{@code levels} 每小节一个电平。 */
    static float[] synth(double bpm, String kinds, float[] levels) {
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
                        hat(out, t + beat * 0.5, level * 0.8f);
                        break;
                    case 'h':
                        hat(out, t, level);
                        hat(out, t + beat * 0.5, level * 0.7f);
                        break;
                    case 'v':
                        kick(out, t, level * 0.5f, false);
                        voice(out, t, beat * 3.4, level);
                        break;
                    case 'x':
                        // 尾奏 vamp：每拍一记底鼓（八分密度会把宽频"咔"灌进中频带、
                        // 被代理 VAD 误判成"有人在唱"，实测踩过）。
                        kick(out, t, level, false);
                        break;
                    case 'z':
                        // 落床：八分密度的噪声洗音（ride / shaker），能量整段落在高频带里，
                        // 中频是空的 → 代理 VAD 判"没人唱"，而电平足够稳。
                        wash(out, t, level);
                        break;
                    case 'w':
                        kick(out, t, level * 0.9f, false);
                        voice(out, t, beat * 3.4, level * 0.6f);
                        break;
                    case 'g':
                        if (k == 0) {
                            kick(out, t, level, false);
                        }
                        voice(out, t, beat * 3.6, level * 0.5f);
                        break;
                    default:
                        kick(out, t, level, false);
                        break;
                }
            }
        }
        return out;
    }

    static void append(StringBuilder sb, float[] levels, int bar, String kinds, float level) {
        sb.append(kinds);
        for (int i = 0; i < kinds.length(); i++) {
            levels[bar + i] = level;
        }
    }

    /** 底鼓：60 Hz（重音拍 120 Hz）正弦、指数衰减。 */
    static void kick(float[] out, double t, float level, boolean high) {
        double freq = high ? 120.0 : 60.0;
        int start = (int) Math.round(t * RATE);
        int n = (int) Math.round(0.18 * RATE);
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= out.length) {
                continue;
            }
            double env = Math.exp(-i / (0.05 * RATE)) * attack(i);
            out[idx] += (float) (level * 0.9 * env * Math.sin(2 * Math.PI * freq * i / RATE));
        }
    }

    /**
     * 起振包络（5 ms 线性上升）：{@code sin} 从 t=0 直接起跳是一个阶跃，宽频"咔"会灌进
     * 200 Hz–2 kHz 那条中频带，于是纯鼓段会被代理 VAD 判成"有人在唱"
     * （见 {@code AutoCueIntroBedTest.attack} 的实测记录）。
     */
    private static double attack(int i) {
        double ramp = 0.005 * RATE;
        return i >= ramp ? 1.0 : i / ramp;
    }

    /** 噪声洗音：8 kHz 正弦、0.2 s 衰减（高频带里的连续织体，电平稳）。 */
    static void wash(float[] out, double t, float level) {
        int start = (int) Math.round(t * RATE);
        int n = (int) Math.round(0.2 * RATE);
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= out.length) {
                continue;
            }
            double env = Math.exp(-i / (0.06 * RATE)) * attack(i);
            out[idx] += (float) (level * 0.5 * env * Math.sin(2 * Math.PI * 8000 * i / RATE));
        }
    }

    /** hi-hat：8 kHz 正弦、很短的衰减（能量整段落在高频带里，不进代理 VAD 的中频带）。 */
    static void hat(float[] out, double t, float level) {
        int start = (int) Math.round(t * RATE);
        int n = (int) Math.round(0.03 * RATE);
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= out.length) {
                continue;
            }
            double env = Math.exp(-i / (0.008 * RATE)) * attack(i);
            out[idx] += (float) (level * 0.5 * env * Math.sin(2 * Math.PI * 8000 * i / RATE));
        }
    }

    /** 「人声」：500 Hz 按 4 Hz 方波状起伏（音节速率），落在代理 VAD 的中频带里。 */
    private static void voice(float[] out, double t, double durSecs, float level) {
        int start = (int) Math.round(t * RATE);
        int n = (int) Math.round(durSecs * RATE);
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= out.length) {
                continue;
            }
            double phase = i / (0.1 * RATE);
            double amp = ((int) phase) % 2 == 0 ? level : level * 0.3;
            out[idx] += (float) (amp * 0.5 * Math.sin(2 * Math.PI * 500 * i / RATE));
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
    // 渲染脚手架（照编排器的方式逐块推进整窗，**记下喂进接法的素材**）
    // ==================================================================

    /**
     * 等功率交叉淡化 + <strong>记录喂进来的素材</strong>。
     *
     * <p>为什么记"喂进来的"而不是"产出的"：{@code from} 上叠着等功率增益，逐位比产物
     * 只能证明"两边一样"，证明不了"这一段素材是从源的哪一刻读的"；记素材就能直接与
     * 源 PCM 逐位比（与 {@code AutoCueSegmentedPathTest.RecordingCrossfade} 同一套做法）。
     */
    private static final class Recorder implements Transition {
        private final Crossfade inner;
        float[] fed;
        int fedFrames;

        Recorder(double seconds) {
            this.inner = new Crossfade((float) seconds);
        }

        void arm(long windowFrames) {
            fed = new float[(int) (windowFrames + Deck.RENDER_FRAMES)];
        }

        @Override
        public long windowFrames(int sampleRate) {
            return inner.windowFrames(sampleRate);
        }

        @Override
        public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
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
     * 把整窗走完：{@code from} 与 {@code to} 都只是"帧泵"（这一档的素材是离线读的，
     * 队列里有自己的段），所以两块都填满固定长度的静音即可——产出的 PCM 这条判据不看，
     * 看的是 {@link Recorder} 记下来的素材。
     */
    private static void render(Transition t, long windowFrames) {
        AudioBuf from = new AudioBuf(ChannelLayout.of(1), Deck.RENDER_FRAMES);
        AudioBuf to = new AudioBuf(ChannelLayout.of(1), Deck.RENDER_FRAMES);
        AudioBuf chunk = new AudioBuf(ChannelLayout.of(1), Deck.RENDER_FRAMES);
        from.setFrames(Deck.RENDER_FRAMES);
        long pos = 0L;
        int guard = 0;
        while (pos < windowFrames && guard++ < 100_000) {
            float progress = (float) Math.min(1.0, (double) pos / (double) windowFrames);
            int n = t.render(chunk, from, to, progress);
            if (n <= 0) {
                break;
            }
            pos += n;
        }
    }

    /**
     * <b>这一段实际读到的源下标比期望偏了几帧</b>（在 ±3 帧里取最像的那个）。
     *
     * <p>为什么要允许偏移：段起点是<strong>秒</strong>，而解码器的 seek 只到帧
     * （{@code (int)(secs × rate)} 是截断），最多差一帧；判据自己算的期望值也各差半帧。
     */
    private static int measureShift(float[] out, int o0, float[] src, int s0, int length,
                                    int skip) {
        int best = Integer.MIN_VALUE;
        int bestMatches = -1;
        for (int delta = -3; delta <= 3; delta++) {
            int matches = 0;
            for (int i = skip; i < length; i++) {
                if (o0 + i >= out.length || s0 + delta + i >= src.length || s0 + delta + i < 0) {
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

    /** <strong>逐位</strong>比一段（开头 {@code skip} 帧不参与：那是整条路径的接缝淡入）。 */
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

    /** 时刻是不是落在小节网格上（容差一帧）。 */
    private static boolean isOnBarGrid(double secs, double phase, double barSecs) {
        double k = (secs - phase) / barSecs;
        return Math.abs(k - Math.round(k)) * barSecs * RATE <= 1.0;
    }
}
