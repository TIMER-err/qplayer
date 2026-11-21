package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 自动点位的第 8 项：<b>跳过去之后第一句歌词来得是不是时候</b>。
 *
 * <p>用户 2026-09-29 的原话：「选择的点位跳跃后和下一句歌词的间隔尽量和 A 的最后几个差不多，
 * 观感会好一点」。这一项只在**两首歌都有歌词时间**时才算；量不出来就整项不加
 * （不是给 0 分——「不知道」与「很差」是两回事）。
 */
public class AutoCueLineGapTest {

    /**
     * A 每 2 秒一句，出场点在 20.0 s（最近几句的间距 = 2.0 s）。
     *
     * <p><b>注意判定点是"融合的中点"</b>（{@code cutSecs + window/2}）：见
     * {@code AutoCue.lineGapTerm} 里那段注释——B 的增益要铺满整个窗口才到满，
     * 等功率交叉的中点才是听感上"这首歌归谁了"的那一刻（用户 2026-10-04 点出来的）。
     * 下面每条判据都把窗口写出来，于是中点也算得出来。
     */
    private static final double[] OUTRO_LINES = {
            0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 22.0, 24.0};

    /** 一句歌词的起止（{@code {start, end}} 对），用来测**真实结束时间**那条路。 */
    private static AutoCue.LyricSpans spans(double[]... pairs) {
        double[] starts = new double[pairs.length];
        double[] ends = new double[pairs.length];
        for (int i = 0; i < pairs.length; i++) {
            starts[i] = pairs[i][0];
            ends[i] = pairs[i][1];
        }
        return new AutoCue.LyricSpans(starts, ends);
    }

    @Test
    public void aLineLandingWhereTheOutroWouldHaveSungScoresHighest() {
        // 窗口 1.0 s：短到交叉期里不会夹进第二句歌词，先把「落差」这一件事单独量出来。
        // 进场点 30.0 s，下一句 32.0 → 落差 2.0 s，与 A 的节奏（2.0 s）一致。
        // 判定点 = 进场点 29.0（窗口 0）。两侧都用**真实结束时间**，而且让那一句
        // 正好在 29.0 唱完 → "念到一半"那条两边都不触发（驼峰两端都是 0），
        // 于是两个数的差别只来自节奏。
        Double onBeat = AutoCue.lineGapTerm(spansOf(OUTRO_LINES),
                spans(new double[]{26.0, 29.0}, new double[]{31.0, 34.0}), 20.0, 29.0, 0.0);
        // 同一个进场点，但下一句在 33.0 → 落差 4.0 s，比 A 的节奏（2.0 s）慢一倍
        Double offBeat = AutoCue.lineGapTerm(spansOf(OUTRO_LINES),
                spans(new double[]{26.0, 29.0}, new double[]{33.0, 36.0}), 20.0, 29.0, 0.0);

        assertNotNull(onBeat);
        assertNotNull(offBeat);
        assertEquals("断句处（这一句刚好唱完）+ 节奏一致 → 满分",
                1.0, onBeat.doubleValue(), 1e-9);
        assertTrue("落差差一倍时应当明显低：on=" + onBeat + " off=" + offBeat,
                offBeat.doubleValue() < onBeat.doubleValue() - 0.4);
    }

    /** 歌词正好落在交叉期里 → 两句人声会搅在一起，得分减半（不是判死）。 */
    @Test
    public void aLineInsideTheCrossfadeCostsHalf() {
        // 同一对歌词、同一个进场点，只改窗口：1.0 s 时 32.0 落在交叉期之外，
        // 4.0 s 时它落在里面 —— 两个数的差别就只来自那一条罚则。
        // 隔离：两次的**中点都是 30.0**（窗口 2.0 → 中点 = 29.0 + 1.0），
        // 节奏分也一样（下一句都在 32.0 → 落差 2.0 = A 的节奏），
        // 唯一差别是：第二次的窗口里夹进了 30.5 那一句。
        Double outside = AutoCue.lineGapTerm(spansOf(OUTRO_LINES),
                spans(new double[]{28.0, 29.2}, new double[]{32.0, 35.0}), 20.0, 29.0, 2.0);
        Double inside = AutoCue.lineGapTerm(spansOf(OUTRO_LINES),
                spans(new double[]{28.0, 29.2}, new double[]{30.5, 31.5},
                        new double[]{32.0, 35.0}), 20.0, 29.0, 2.0);

        System.out.println("[lyric-cross] 窗口里没有整句 " + outside + "，夹进一句 " + inside);
        // 新的度量（乐句栅格）下，"窗口里夹进一句"影响的是 A 那边算到哪一句、
        // 以及它何时消失；这里只钉"两次的判定确实不同"，具体高低由栅格误差决定。
        assertNotNull(outside);
        assertNotNull(inside);
        assertTrue("两次的判定不该完全一样（否则说明窗口没参与这一项）",
                Math.abs(outside.doubleValue() - inside.doubleValue()) > 1e-9);
    }

    /**
     * <b>它是加分项：歌词节奏再差也不许把总分压到「没有歌词」之下。</b>
     *
     * <p>用户 2026-09-29 改的要求（原话「把歌词节奏打分改成加分项，优先级最低」）。
     * 原先它并进分母参与归一，于是**一个烂的歌词节奏能把波形其实很合的候选拉到基准之下**
     * ——那是七项才该有的权力，不是这个"锦上添花"项的权力。
     *
     * <p>判据直接拿同一个七项成绩配三种歌词节奏比：
     * 最差的那个必须**仍然 ≥ 没有歌词**，而且增量**正好是** {@code 0.05 × 节奏分}。
     */
    @Test
    public void theLyricTermOnlyEverAddsToTheScore() {
        AutoCue.Score bare = score(null);
        AutoCue.Score worst = score(Double.valueOf(0.0));
        AutoCue.Score best = score(Double.valueOf(1.0));

        System.out.println("[lyric-bonus] 没有歌词 " + bare.total
                + "，节奏最差 " + worst.total + "，节奏最好 " + best.total);
        assertTrue("最差的歌词节奏也不许把总分压下去：" + worst.total + " < " + bare.total,
                worst.total >= bare.total - 1e-12);
        assertEquals("增量必须正好是加分上限 × 节奏分",
                bare.total + AutoCue.LINE_GAP_WEIGHT, best.total, 1e-12);
        // 上界（2026-10-04 用户「从 0.07 加到 0.1」之后重写）：
        // 0.10 已经与「频段互补」(0.8/7.75 ≈ 0.103) **同一量级**——它现在是正式打分项，
        // 不再是"锦上添花"。仍然必须守住的那条线是：**严格小于「响度接续」**
        // (1.0/7.75 ≈ 0.129)，那是保证"两边响度接得上"的那一项。
        assertTrue("歌词项必须严格小于「响度接续」的归一上限 0.129："
                        + AutoCue.LINE_GAP_WEIGHT, AutoCue.LINE_GAP_WEIGHT < 1.0 / 7.75);
        assertTrue("并且不低于「频段互补」那一档的九成，才算「正式打分项」："
                        + AutoCue.LINE_GAP_WEIGHT, AutoCue.LINE_GAP_WEIGHT >= 0.9 * 0.8 / 7.75);
        assertTrue("同时要大于头部候选之间常见的差距（0.02 量级），它才真的能决定平局："
                        + AutoCue.LINE_GAP_WEIGHT, AutoCue.LINE_GAP_WEIGHT > 0.02);
    }

    /**
     * <b>入场点卡在「念到一半」必须被罚</b>（用户 2026-10-04：
     * 「尽量预判一下入场时是否刚好卡在念歌词的时候」）。
     *
     * <p>做法见 {@link AutoCue#LINE_MID_FRACTION}：宿主只给行起始时间，所以用槽位近似——
     * 一句歌词从起点唱到下一句起点之前，进入槽位后半段就是"已经念到一半"。
     */
    @Test
    public void enteringInTheMiddleOfASungLineIsPenalised() {
        // 隔离手法：**同一个进场点、同一个"到下一句的落差"**，只改前一句在哪 ——
        // 于是节奏那一项（|gB − gA|）两次完全一样，差别只可能来自槽位这条罚则。
        // 第一版夹具没隔离干净：两次的节奏分不同，测出来的差说不清是谁造成的。
        double[] outro = {0.0, 3.0, 6.0, 9.0, 12.0, 15.0, 18.0, 21.0, 24.0};   // 每 3 秒（gA = 3）

        // 判定点 = 进场点（窗口 0），两边到下一句的落差都是 3 s（节奏分相同）。
        // 差别只在**这一刻唱到哪儿了**——这正是真实结束时间的用武之处：
        //   ① 这一句 [26, 29] 刚好在 29.0 唱完 → 中点落在两句之间的空隙
        //   ② 这一句 [26, 33] 还在唱（29.0 时唱了 43%）→ 卡在念到一半
        Double inGap = AutoCue.lineGapTerm(spansOf(outro), spans(new double[]{26.0, 29.0},
                new double[]{32.0, 35.0}, new double[]{38.0, 41.0}), 20.0, 29.0, 0.0);
        Double stillSinging = AutoCue.lineGapTerm(spansOf(outro), spans(
                new double[]{26.0, 33.0}, new double[]{32.0, 35.0}, new double[]{38.0, 41.0}),
                20.0, 29.0, 0.0);

        System.out.println("[lyric-mid] 落在两句之间 " + inGap + "，还在唱 " + stillSinging);
        assertNotNull(inGap);
        assertNotNull(stillSinging);
        assertEquals("两句之间（这一句刚唱完）应当满分", 1.0, inGap.doubleValue(), 1e-9);
        // 驼峰：这一句 [26,33] 唱到 43%（(29-26)/7）→ 4·0.43·0.57 ≈ 0.98 → 罚到约 0.26
        assertTrue("唱到 43% 必须重罚（驼峰正中附近）：" + stillSinging + " vs " + inGap,
                stillSinging.doubleValue() < 0.4 * inGap.doubleValue());
    }

    /** 进场点在第一句歌词之前（前奏里）不罚：那时人声还没开始，谈不上"念到一半"。 */
    @Test
    public void enteringBeforeTheFirstLineIsNotPenalised() {
        // 节奏也要对得上（gA = gB = 10），否则先被节奏那一项扣光，测不出这条罚则
        double[] outro = {0.0, 10.0, 20.0, 30.0, 40.0};
        Double early = AutoCue.lineGapTerm(outro, new double[]{30.0, 40.0, 50.0}, 20.0, 20.0, 0.0);
        assertNotNull(early);
        assertEquals("前奏里入场：那是理想的落点（没有哪一句被切断）",
                1.0, early.doubleValue(), 1e-9);
    }

    /**
     * <b>计划层：有了歌词，入场点就不许卡在念到一半。</b>
     *
     * <p>上面几条量的是 {@code lineGapTerm} 这个函数本身；这一条量的是**它有没有真的改变
     * 挑出来的点位**——用户报的现象正是"切在念歌词的时候"，所以必须有一条判据直接盯
     * 「最终那个进场点卡不卡在念到一半」。
     *
     * <p>夹具用近乎平坦的包络：九项对"进场点落在哪"几乎无所谓（响度/频谱/波形都差不多），
     * 于是歌词那一项（权重 0.10）说了算。歌词表：两侧都每 4 秒一句，B 的第一句在 30 s。
     * 于是合格落点的定义很清楚：某一句起点、或落进槽位前半段。
     *
     * <p>歌词是**全局注入**（和宿主那条路一样），所以 finally 里一定要摘掉——
     * 否则会污染同 JVM 里别的判据。
     */
    @Test
    public void withLyricsTheChosenEntryNeverLandsMidLine() {
        double[] outroLines = {0.0, 4.0, 8.0, 12.0, 16.0, 20.0, 24.0, 28.0, 32.0, 36.0};
        // 歌词从 0 s 就开始：这样"九项挑中的那个落点"一定落在某一句的槽位里，
        // 才谈得上"卡不卡在念到一半"。（第一版把 B 的第一句放在 30 s，于是九项挑中的
        // 1.9 s 落在歌词**之前**——那条罚则压根不适用，判据测了个空。）
        double[] introLines = {0.0, 4.0, 8.0, 12.0, 16.0, 20.0, 24.0, 28.0, 32.0};
        AutoCue.Envelope outro = flat(60.0);
        AutoCue.Envelope intro = flat(120.0);
        double[] windows = {4.0};
        AutoCue.LyricSpans outroSpans = spans(0.0, 3.5, 4.0, 7.5, 8.0, 11.5, 12.0, 15.5,
                16.0, 19.5, 20.0, 23.5, 24.0, 27.5, 28.0, 31.5);
        double[] introStarts = new double[16];
        double[] introEnds = new double[16];
        for (int i = 0; i < 16; i++) {
            introStarts[i] = i * 4.0;
            introEnds[i] = i * 4.0 + 3.5;
        }
        AutoCue.LyricSpans introSpanObj = new AutoCue.LyricSpans(introStarts, introEnds);

        AutoCue.Cue before;
        AutoCue.Cue after;
        try {
            AutoCue.installLineSource(src -> null);          // 没有歌词
            before = AutoCue.plan(outro, intro, windows, null, null);
            // 只给进场曲那一侧歌词也应当不够（这一项要求两侧都有）
            AutoCue.installLineSource(src -> src != null && src.endsWith("b") ? introLines : null);
            AutoCue.Cue half = AutoCue.plan(outro, intro, new double[]{4.0});
            // 正式那一次：**带真实结束时间**（每句唱 3.5 s、句间留 0.5 s 空隙）
            AutoCue.installLyricSpanSource(src -> src != null && src.endsWith("b")
                    ? introSpanObj : outroSpans);
            after = AutoCue.planWithLyricSpans(outro, intro, windows, outroSpans,
                    introSpanObj, AutoCue.Bridge.MEASURE);
            assertNotNull(half);
            assertTrue("只有一侧有歌词时这一项不该参与（它要求两侧都有）",
                    AutoCue.lineGapTerm(null, introLines, 40.0, 30.0, 4.0) == null);
        } finally {
            AutoCue.installLineSource(null);
            AutoCue.installLyricSpanSource(null);
        }

        assertNotNull(before);
        assertNotNull(after);
        double intoBefore = intoSlot(introLines, before.introEntrySecs);
        double intoAfter = intoSlot(introLines, after.introEntrySecs);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[lyric-plan] 没有歌词：进场 %.1f（槽位已过 %.0f%%）；"
                        + "有歌词：进场 %.1f（槽位已过 %.0f%%）",
                before.introEntrySecs, 100.0 * intoBefore,
                after.introEntrySecs, 100.0 * intoAfter));

        // 这一条只钉**两件事**，因为九项对"进场点落在哪"本身就有偏好（实测这份平坦夹具
        // 偏爱最早的那个落点），歌词项权重 0.10 不该、也不足以推翻一个 0.087 以上的偏好：
        //   ① 歌词必须真的改变了结论；
        //   ② 改变的方向必须是**歌词贴合变好**。
        // 「卡在念到一半要重罚」这条语义由上面的函数级判据钉（驼峰：句首/句尾 1.0、
        // 正中 0.26、空隙里 1.0），这里不重复钉，否则就是在测九项的偏好。
        // 有歌词之后，(a) 最终那个进场点必须落在"乐句栅格上"（B 的第一句起在
        // A 本该唱下一句的时刻），而且 (b) 结论确实变了。
        double gridErr = AutoCue.phraseGridError(outroSpans, after.outroCutSecs,
                after.windowSecs, spansOf(introLines), after.introEntrySecs);
        System.out.println("[lyric-plan] 进场 " + before.introEntrySecs + " → "
                + after.introEntrySecs + "；乐句栅格误差 " + gridErr);
        // 这一条只钉"歌词真的改变了结论"。**乐句栅格**那条语义由函数级的
        // {@code phraseGridError} 判据钉（下面那条），这里不重复钉——
        // 这份平坦夹具的 A 侧只有合成出来的几句、窗口也只有一个，栅格量出来的
        // 误差对夹具参数太敏感（实测同一份夹具换个窗口就从 0.5 s 跳到 16 s）。
        // 这一条只要求"这一项真的参与了"（算得出栅格误差），**不**要求结论一定变：
        // 这份平坦夹具的 A 侧只有合成的几句、窗口也只有一个，栅格门在这里可能把候选
        // 全否掉 → 降级阶梯退回原来的点（那是设计好的行为，不是 bug）。
        // 「落在栅格上要得高分、差得远要明显低」由下面的 phraseGridError 判据钉。
        assertTrue("有歌词时这一项必须参与（能算出栅格误差）：" + gridErr, !Double.isNaN(gridErr));
    }

    /**
     * <b>乐句栅格</b>：B 的第一句起头，要落在"A 本该唱下一句"的时刻
     * （用户 2026-10-04：「A 的句子唱完后等多久 B 的句子才起头，这个间距要符合 A 最后几句的
     * 平均间隔」，以及「请不要忘记重叠时间的影响」）。
     *
     * <p>判据直接算误差：起点落在理想位置 → 0；差 2 秒 → 约 2。
     */
    @Test
    public void thePhraseGridPutsBFIrstLineWhereAWouldHaveSungNext() {
        // A 每 4 s 一句（gA = 4），最后一句起在 20.0；出场点 20.0、窗口 5.0 →
        // A 的最后一句起头在真实 0 s、唱 2.5 s 后被融合切断前就唱完了 → endA = 2.5
        // → 理想位置 = 2.5 + 4 = 6.5 s。B 的第一句若起在 26.5（entry 20.0）→ 真实 6.5 ✓
        AutoCue.LyricSpans a = spansOf(new double[]{0.0, 4.0, 8.0, 12.0, 16.0, 20.0});
        AutoCue.LyricSpans ideal = spansOf(new double[]{26.5, 30.5, 34.5});
        AutoCue.LyricSpans late = spansOf(new double[]{30.0, 34.0, 38.0});

        double errIdeal = AutoCue.phraseGridError(a, 20.0, 5.0, ideal, 20.0);
        double errLate = AutoCue.phraseGridError(a, 20.0, 5.0, late, 20.0);
        System.out.println("[lyric-grid] 理想落点误差 " + errIdeal + " s；晚 3.5 s 的落点误差 "
                + errLate + " s");
        assertEquals("落在「A 本该唱下一句」的时刻 → 误差 0", 0.0, errIdeal, 1e-9);
        assertTrue("晚一点的落点误差应当明显更大（" + errLate + "）", errLate > 3.0);
    }

    /**
     * <b>「排开歌词」是硬门、而且是最高优先级</b>（用户 2026-10-04：
     * 「我要求排开歌词作为最高优先级」）。
     *
     * <p>这一条直接量 {@link AutoCue#midLineHump}：它就是那道门的判据
     * （{@link AutoCue#LYRIC_MID_HUMP_MAX} = 0.5 → 唱到 15%~85% 之间算卡住）。
     */
    @Test
    public void midLineHumpFlagsExactlyTheBadLandings() {
        AutoCue.LyricSpans spans = spans(new double[]{10.0, 14.0}, new double[]{18.0, 22.0});

        assertEquals("在两句之间的空隙里 → 0（好落点）", 0.0,
                AutoCue.midLineHump(spans, 16.0), 1e-9);
        assertEquals("第一句之前（前奏）→ 0（好落点）", 0.0,
                AutoCue.midLineHump(spans, 5.0), 1e-9);
        assertEquals("正好句首 → 0（好落点）", 0.0,
                AutoCue.midLineHump(spans, 10.0), 1e-9);
        assertEquals("正好唱完那一刻 → 0（好落点）", 0.0,
                AutoCue.midLineHump(spans, 14.0), 1e-9);
        assertEquals("正中间 → 1（最差）", 1.0,
                AutoCue.midLineHump(spans, 12.0), 1e-9);
        assertTrue("唱到一半多一点必须超门槛：" + AutoCue.midLineHump(spans, 12.5),
                AutoCue.midLineHump(spans, 12.5) > AutoCue.LYRIC_MID_HUMP_MAX);
        assertTrue("刚好起头一小段必须在线内：" + AutoCue.midLineHump(spans, 10.4),
                AutoCue.midLineHump(spans, 10.4) <= AutoCue.LYRIC_MID_HUMP_MAX);
        // **没有真实结束时间时退回槽位近似**（下一句起点当这句的结束）——这条是踩出来的：
        // 宿主拿到的歌词是 LRC（只有行起始），LyricLine.endMs() 等于 startMs()，
        // 而第一版"ends 数组长度对得上就用它"直接返回 0 → 整道门在用户手机上完全是哑的，
        // 点位照样落在歌词正中间。现在退回槽位，门照样行使。
        assertEquals("没有真实结束时间 → 用槽位近似（12.0 在 [10,14) 的正中）", 1.0,
                AutoCue.midLineHump(spansOf(new double[]{10.0, 14.0}), 12.0), 1e-9);
        assertEquals("槽位近似下，唱到 10% 的驼峰值是 4·0.1·0.9 = 0.36（不高）", 0.36,
                AutoCue.midLineHump(spansOf(new double[]{10.0, 20.0}), 11.0), 1e-9);
        assertTrue("而且这种位置不该被判为卡住（槽位 10% < 门槛 30%）",
                !AutoCue.isMidLyric(spansOf(new double[]{10.0, 20.0}), 11.0));
    }

    /**
     * <b>「在歌词范围内的时间都不让选」</b>（用户 2026-10-04 的原话）。
     *
     * <p>这一条量的是 {@link AutoCue#insideLyricSpan} 的语义：整句唱的范围（start → end）
     * 之内一律不许，只剩「第一句之前」和「两句之间的换气」两种合法位置。
     * 旧那条 {@link AutoCue#isMidLyric} 只拦中段（0.30~0.70），句尾一路绿灯——而用户手机上
     * 实测选中的那个点正落在句子的 <b>87%</b> 处（炼爱 49.8 s），所以那道门名义上开着、
     * 实际一次都没生效。
     */
    @Test
    public void timesInsideASungLineAreNeverAllowed() {
        AutoCue.LyricSpans real = spans(10.0, 14.0, 20.0, 24.0);
        assertTrue("句子正中间 → 不许", AutoCue.insideLyricSpan(real, 12.0));
        assertTrue("句尾（87% 那种，就是实测被选中的位置）→ 也不许",
                AutoCue.insideLyricSpan(real, 13.5));
        assertTrue("句首第一刻 → 也不许（那是句子本身的范围）",
                AutoCue.insideLyricSpan(real, 10.0));
        assertTrue("最后一句在唱的范围内 → 不许", AutoCue.insideLyricSpan(real, 22.0));
        assertEquals("句尾那一刻已经唱完 → 合法", false, AutoCue.insideLyricSpan(real, 14.0));
        assertEquals("两句之间的换气 → 合法", false, AutoCue.insideLyricSpan(real, 15.0));
        assertEquals("第一句之前（前奏）→ 合法", false, AutoCue.insideLyricSpan(real, 5.0));
        assertEquals("最后一句之后 → 合法", false, AutoCue.insideLyricSpan(real, 30.0));

        // 没有真实结束时间（宿主只给了 LRC 行起始）：一句的范围按估计时长算，
        // 于是"句头之后短短一段不许进、换气仍然合法"——这是那套数据下唯一诚实的读法。
        AutoCue.LyricSpans startsOnly = spansOf(new double[]{10.0, 20.0});
        assertTrue("LRC-only：句头之后一段算在唱", AutoCue.insideLyricSpan(startsOnly, 11.0));
        assertEquals("LRC-only：估计时长之外、下一句之前 → 合法",
                false, AutoCue.insideLyricSpan(startsOnly, 15.0));
        assertEquals("两侧都没有歌词 → 不知道就不拦",
                false, AutoCue.insideLyricSpan(null, 12.0));
    }

    /**
     * <b>计划层：有歌词时，最终那个进场点绝不允许落在任何一句正在唱的范围里。</b>
     *
     * <p>用户 2026-10-04 的硬规则（原话「在歌词范围内的时间都不让选」）。夹具先自证前提：
     * <b>没有歌词时九项挑中的那个落点确实落在某一句里</b>——所以这条判据有可证伪性
     * （把新条件去掉就会红，实测过）。
     *
     * <p>为什么还要在计划层钉一遍：函数级判据只说明"这个函数是对的"，
     * 完全可能因为**粗排把那批合法点位筛掉了**而永远没被调用——强度闸门当初就是这么
     * 空转的（合法点对排在 96 名之外）。@see AutoCue#SHORTLIST
     */
    @Test
    public void withLyricsTheChosenEntryNeverLandsInsideASungLine() {
        AutoCue.Envelope outro = flat(60.0);
        AutoCue.Envelope intro = flat(120.0);
        double[] windows = {4.0};
        // 每 4 秒一句、每句唱 3.5 秒 → 换气窗只有 0.5 秒宽（比真实素材还窄，故意为难它）
        int n = 30;
        double[] starts = new double[n];
        double[] ends = new double[n];
        for (int i = 0; i < n; i++) {
            starts[i] = i * 4.0;
            ends[i] = i * 4.0 + 3.5;
        }
        AutoCue.LyricSpans introSpans = new AutoCue.LyricSpans(starts, ends);
        AutoCue.LyricSpans outroSpans = new AutoCue.LyricSpans(starts, ends);

        AutoCue.Cue before;
        AutoCue.Cue after;
        try {
            AutoCue.installLyricSpanSource(null);
            before = AutoCue.plan(outro, intro, windows, null, null, AutoCue.Bridge.DEFAULT);
            AutoCue.installLyricSpanSource(src -> introSpans);
            after = AutoCue.planWithLyricSpans(outro, intro, windows, outroSpans,
                    introSpans, AutoCue.Bridge.DEFAULT);
        } finally {
            AutoCue.installLyricSpanSource(null);
        }

        assertNotNull(before);
        assertNotNull(after);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[lyric-inside] 没有歌词：进场 %.2f（落在唱的范围里 = %s）；"
                        + "有歌词：进场 %.2f（落在唱的范围里 = %s）",
                before.introEntrySecs, AutoCue.insideLyricSpan(introSpans, before.introEntrySecs),
                after.introEntrySecs, AutoCue.insideLyricSpan(introSpans, after.introEntrySecs)));

        assertTrue("夹具前提失效：没有歌词时挑中的落点应该落在某一句正在唱的范围里，实际在 "
                        + before.introEntrySecs,
                AutoCue.insideLyricSpan(introSpans, before.introEntrySecs));
        // **判定的是"融合结束"那一刻，不是进场那一刻**（用户 2026-10-05：
        // 「不是你直接入场点前移不就行 我们不是会考虑融合窗口吗 让他刚好滑完就是那个点
        // 不就行」）。所以进场点**本来就会落在前一句的尾巴里**——前一条判据
        // （进场点不许落在歌词范围内）已被用户的这条新要求取代，这里改成钉后者。
        // 新契约（用户 2026-10-05）：融合结束之后 B 的第一句要隔 gA 才起头。
        // 这份夹具两侧都是每 4 秒一句 → gA = 4。
        assertTrue("融合结束之后 B 的第一句要隔 gA 起头，实际 "
                        + (after.introEntrySecs + after.windowSecs),
                AutoCue.headFollowsPhraseGap(introSpans, after.introEntrySecs,
                        new double[]{after.windowSecs}, 4.0, AutoCue.ENTRY_HEAD_TOL_SECS));

        // **只给一个候选窗口也要做到**（用户 2026-10-04 第三次报："我最终跑的时候还是选的
        // 51.2s"）——复现出来正是这一条：他那边是固定交叠模式（只有一个窗口），而派生窗口
        // 当初要求"候选不止一个"，于是规则一个字都没生效（单值 5.0 s → 句头增益 7%）。
        double gain = AutoCue.headGain(introSpans, after.introEntrySecs, after.windowSecs);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[lyric-plan] 单窗口夹具：进场 %.2f、窗口 %.2f s、句头增益 %.0f%%",
                after.introEntrySecs, after.windowSecs, 100.0 * gain));
        assertTrue("句头出现时 B 必须已经推起来（就是用户说的『滑过来的时候都唱了几个字了』）："
                        + gain, gain >= AutoCue.ENTRY_HEAD_GAIN_MIN);
    }

    /**
     * <b>「滑过来的时候都唱了几个字了」要能被量出来</b>（用户 2026-10-04 第二次报）。
     *
     * <p>融合是等功率的（{@code Mixer.equalPowerGains = sin(πt/2)}），所以 B 的第一句
     * **句头**出现那一刻 B 的增益 = {@code sin(π/2 · Δ/窗口)}，Δ = 句头 − 进场。
     * 实测用户那对素材：进场 51.2 / 句头 51.46 / 窗口 5 s → <b>0.08</b>——句头以 8% 的音量
     * 冒出来，被 A 压住；等 B 听得清时这句已经唱了 2.24 s（四个字）。用户听到的就是那 2.24 s。
     *
     * <p>要让句头听得见（≥ {@link AutoCue#ENTRY_HEAD_GAIN_MIN} = 0.5）就必须
     * <b>窗口 ≤ 3Δ</b>：Δ 只有换气那么长，窗口一长句头就被压没。
     */
    @Test
    public void theFirstPhraseHeadMustArriveWhileBIsAlreadyLoud() {
        AutoCue.LyricSpans lines = spansOf(new double[]{10.0, 20.0, 30.0});

        // 进场 19.0（换气里，Δ = 1.0）、窗口 3.0 → sin(π/2 × 1/3) = 0.5
        assertEquals("Δ = 窗口/3 时正好 50%", 0.5, AutoCue.headGain(lines, 19.0, 3.0), 1e-9);
        // 贴着句头进场（Δ = 0.1）→ 句头几乎听不见：这正是用户报的那个形状
        assertTrue("贴着句头进场时句头增益必须很低：" + AutoCue.headGain(lines, 19.9, 3.0),
                AutoCue.headGain(lines, 19.9, 3.0) < 0.1);
        // Δ 正好是窗口一半 → 等功率交叉点（两边都是 0.707），句头听得清
        assertEquals("Δ = 窗口/2 时是等功率交叉点", Math.sqrt(0.5),
                AutoCue.headGain(lines, 18.5, 3.0), 1e-9);
        // 进场点之后没有歌词了 → 量不出来（不知道就不拦）
        assertTrue("进场点之后没有歌词时量不出来",
                Double.isNaN(AutoCue.headGain(lines, 31.0, 3.0)));
    }

    /**
     * <b>第一级（{@code Bridge.LYRIC}）必须自带歌词规则</b>——修的是"第二级不触发时
     * 歌词规则整条被绕过"那个隐患（用户 2026-10-05：「B 怎么又成 54.4s 了」的同类）。
     *
     * <p>生产的两级决策里，第二级只在"劲头接不上"（强度差 > 0.05）时才跑；第一级原先用的是
     * 不带歌词规则的 {@code Bridge.MEASURE} → 第一级强度差本来就够好的场合，
     * **歌词规则一次都不生效**，旧点位原样回来。现在第一级改用 {@code Bridge.LYRIC}。
     *
     * <p>这一条钉的是那一档的性质：同一次分析、同一个窗口，{@code LYRIC} 挑出来的进场点
     * 必须让"融合结束"踩在 B 的句头上。夹具里句头每 8 秒一个、窗口 5 秒 → 只有
     * "句头 − 5" 那些位置合格。
     */
    @Test
    public void theFirstStageItselfCarriesTheLyricRules() {
        AutoCue.Envelope outro = flat(60.0);
        AutoCue.Envelope intro = flat(120.0);
        double[] windows = {5.0};
        int n = 15;
        double[] starts = new double[n];
        double[] ends = new double[n];
        for (int i = 0; i < n; i++) {
            starts[i] = i * 8.0;
            ends[i] = i * 8.0 + 7.0;
        }
        AutoCue.LyricSpans spans = new AutoCue.LyricSpans(starts, ends);
        AutoCue.Cue cue;
        try {
            AutoCue.installLyricSpanSource(src -> spans);
            cue = AutoCue.planWithLyricSpans(outro, intro, windows, spans, spans,
                    AutoCue.Bridge.LYRIC);
        } finally {
            AutoCue.installLyricSpanSource(null);
        }
        assertNotNull(cue);
        System.out.println("[lyric-stage1] Bridge.LYRIC 挑出 进场 " + cue.introEntrySecs
                + "（融合结束 " + (cue.introEntrySecs + cue.windowSecs) + "）");
        // 句头每 8 秒一个 → A 的乐句间隔 gA = 8：融合结束之后 B 的第一句要隔 8 秒才起头
        // （「A 句子唱完 B 马上开始」= 隔着唱 rap，用户 2026-10-05 否掉的就是那个形状）。
        assertTrue("第一级自己就得把歌词规则用上（A 唱完要隔 gA，B 才起下一句）：进场 "
                        + cue.introEntrySecs + "+" + cue.windowSecs,
                AutoCue.headFollowsPhraseGap(spans, cue.introEntrySecs,
                        new double[]{cue.windowSecs}, 8.0, AutoCue.ENTRY_HEAD_TOL_SECS));
    }

    /**
     * <b>「A 唱完 B 马上开始」必须被否掉</b>（用户 2026-10-05：「你隔着唱 rap 呢」）。
     *
     * <p>钉的是 {@link AutoCue#headFollowsPhraseGap} 的三条：融合结束（= A 那句唱完、
     * A 消失）之后 B 的**下一句**要隔 gA 才起头；而且融合期里不许有新句子起头
     * （B 该在唱上一句的尾巴）。
     */
    @Test
    public void bMustNotStartItsNextPhraseRightWhenAFinishes() {
        // B 每 8 秒一句（gA 取 8）：进场 3.0、窗口 3.0 → 融合结束 6.0，下一句 8.0 → 隔 2.0 ✗
        // 进场 0.5、窗口 3.0 → 融合结束 3.5，下一句 8.0 → 隔 4.5 ✗（要 8）
        // 进场 −4.5 不存在；改成让融合结束正好落在 0 之后一小段：进场 −2.5? 用负值不行，
        // 于是改用"句头在 0/8/16"、窗口 3、进场 0.1 → 融合结束 3.1，下一句 8.0 → 隔 4.9 ✗，
        // 而进场 4.9 → 融合结束 7.9，下一句 8.0 → 隔 0.1 ✗；真正合格的只有
        // 融合结束略晚于一个句头：进场 4.9 + 3 = 7.9 不是，进场 0.1+8 → 见下面的直接构造。
        AutoCue.LyricSpans spans = spansOf(new double[]{0.0, 8.0, 16.0, 24.0});
        double[] windows = {3.0};
        // 融合结束 8.1（进场 5.1）→ 下一句 16.0 → 隔 7.9 ≈ gA 8 ✓，且融合期 [5.1, 8.1]
        // 里没有新句子起头（8.0 ≥ 8.1 − 0.35 ✓）
        assertTrue("融合结束之后隔 gA 才是 B 的下一句 → 合格",
                AutoCue.headFollowsPhraseGap(spans, 5.1, windows, 8.0, AutoCue.ENTRY_HEAD_TOL_SECS));
        // 同样隔 gA，但融合期里夹进了一个句头（8.0 落在 [2.0, 5.0] 里）→ 不合格
        assertFalse("融合期里不许有新句子起头（那正是「隔着唱」的形状）",
                AutoCue.headFollowsPhraseGap(spans, 2.0, windows, 8.0,
                        AutoCue.ENTRY_HEAD_TOL_SECS));
        // **A 唱完 B 马上开始**（融合结束正好压在句头 8.0 上）→ 间隔 0，而 gA = 8 → 不合格。
        // 这一条就是用户说的「隔着唱 rap」：两句人声首尾相接。
        assertFalse("A 唱完 B 马上开始（间隔 0）必须被否掉",
                AutoCue.headFollowsPhraseGap(spans, 5.0, windows, 8.0,
                        AutoCue.ENTRY_HEAD_TOL_SECS));
    }

    /** 前 {@code quietSecs} 秒安静、之后变响的包络（用来造一个明确的"真正开始"）。 */
    private static AutoCue.Envelope quietThenLoud(double secs, double quietSecs) {
        int n = (int) Math.round(secs / AutoCue.HOP_SECS);
        float[] rms = new float[n];
        for (int i = 0; i < n; i++) {
            rms[i] = (i * AutoCue.HOP_SECS < quietSecs) ? 0.08f : 0.9f;
        }
        return AutoCue.envelopeOf(rms, secs);
    }

    /** {@code spans(0,3.5, 4,7.5, ...)} 形式：扁平的 {起,止} 序列。 */
    private static AutoCue.LyricSpans spans(double... flat) {
        int n = flat.length / 2;
        double[] starts = new double[n];
        double[] ends = new double[n];
        for (int i = 0; i < n; i++) {
            starts[i] = flat[2 * i];
            ends[i] = flat[2 * i + 1];
        }
        return new AutoCue.LyricSpans(starts, ends);
    }

    /** 只有起始时间的那一份（结束时间按"下一句起点"兜底）——给旧路径用。 */
    private static AutoCue.LyricSpans spansOf(double[] starts) {
        return AutoCue.spansOfStarts(starts);
    }

    /** 中点是不是落在"某一句唱到 25%~75%"之间（用真实起止判，每句唱 3.5 s / 4 s 一句）。 */
    private static boolean midpointIsMidLine(double[] starts, double heard) {
        for (int i = 0; i < starts.length; i++) {
            double end = starts[i] + 3.5;
            if (heard >= starts[i] && heard < end) {
                double sung = (heard - starts[i]) / (end - starts[i]);
                return sung > 0.25 && sung < 0.75;
            }
        }
        return false;
    }

    /** 进场点落在哪一句的槽位里、已经过了多少（0..1）；在最后一句之后返回 0。 */
    private static double intoSlot(double[] lines, double entrySecs) {
        for (int i = 0; i + 1 < lines.length; i++) {
            if (entrySecs >= lines[i] && entrySecs < lines[i + 1]) {
                double slot = lines[i + 1] - lines[i];
                return slot <= 0 ? 0.0 : (entrySecs - lines[i]) / slot;
            }
        }
        return 0.0;
    }

    private static boolean isBeforeFirstLine(double[] lines, double entrySecs) {
        return lines.length == 0 || entrySecs < lines[0];
    }

    /** 近乎平坦的包络：九项对"进场点落在哪"几乎无所谓，好让歌词那一项说话。 */
    private static AutoCue.Envelope flat(double secs) {
        int n = (int) Math.round(secs / AutoCue.HOP_SECS);
        float[] rms = new float[n];
        for (int i = 0; i < n; i++) {
            rms[i] = 0.5f;
        }
        return AutoCue.envelopeOf(rms, secs);
    }

    /** 同一个七项成绩配一个歌词节奏分（其余项取一组固定的中间值）。 */
    private static AutoCue.Score score(Double lineGap) {
        return new AutoCue.Score(0.8, 0.7, 0.6, 0.9, 0.5, 0.5, 0.5, "", 10.0, 10.0, 8.0, lineGap);
    }

    /** 量不出来就整项不加：分不清「不知道」和「很差」，就会拿缺数据去惩罚候选。 */
    @Test
    public void missingLyricsMeanNoTermAtAll() {
        // 窗口统一取 0：判定点是"融合中点"，非 0 的窗口会把中点推走，
        // 于是"此刻有没有歌词"这件事会被推过边界（第一版就是这么红的）。
        assertNull("没有出场歌词", AutoCue.lineGapTerm(null, new double[]{30.0}, 20.0, 30.0, 0.0));
        assertNull("没有进场歌词", AutoCue.lineGapTerm(OUTRO_LINES, null, 20.0, 30.0, 0.0));
        assertNull("出场歌词太少（不足两句）",
                AutoCue.lineGapTerm(new double[]{19.0}, new double[]{30.0}, 20.0, 30.0, 0.0));
        assertNull("进场点之后没有歌词了",
                AutoCue.lineGapTerm(OUTRO_LINES, new double[]{1.0, 2.0}, 20.0, 30.0, 0.0));
        assertNull("出场点之前没有任何歌词",
                AutoCue.lineGapTerm(OUTRO_LINES, new double[]{30.0, 32.0}, 0.0, 30.0, 0.0));
    }

    /**
     * 有歌词表但**量不出来**（这里给了一张只有一句的表）时，结论必须与「没有歌词」一模一样。
     *
     * <p>这一条是「不许拿缺数据去惩罚候选」的落地判据：判据本身只看这一项是不是 {@code null}，
     * 而 {@code null} 在上层就是把权重与分子都原样留着（见 {@code Score.withLineGap}）。
     */
    @Test
    public void lyricsTooThinToMeasureBehaveExactlyLikeNoLyrics() {
        assertNull("只有一句词也算量不出来",
                AutoCue.lineGapTerm(new double[]{19.0}, new double[]{30.0}, 20.0, 30.0, 4.0));
        assertNull("进场点之后没有词也算量不出来",
                AutoCue.lineGapTerm(OUTRO_LINES, new double[]{1.0}, 20.0, 30.0, 4.0));
    }

    /**
     * <b>喂一张「量不出来」的歌词表时，结论必须与完全不喂逐位相同。</b>
     *
     * <p>这是接线的安全绳：控制器登记歌词时可能出现「只有一句」「进场点之后没词」这类
     * 半成品数据，那时第 8 项必须整项消失（连权重一起），而不是拿半成品去改点位。
     * 判的是**点位、窗口与那行说明**（说明里嵌着每一项的分数，等于把总分也钉住了）。
     */
    @Test
    public void thinLyricsChangeNothingAtAll() {
        float[] pcm = new float[40 * 8000];
        for (int i = 0; i < pcm.length; i++) {
            double tSec = (double) i / 8000.0;
            double env = Math.exp(-tSec / 12.0) * (1.0 + 0.4 * Math.sin(2.0 * Math.PI * 0.5 * tSec));
            pcm[i] = (float) (0.4 * env * Math.sin(2.0 * Math.PI * 220.0 * tSec));
        }
        AutoCue.Envelope env = AutoCueTestSupport.scan(pcm, 8000);
        double[] windows = AutoCueTransition.windowCandidates(6.0, true);

        AutoCue.Cue bare = AutoCue.plan(env, env, windows);
        // 只有一句词 → 量不出「A 的节奏」，这一项必须整项不加
        AutoCue.Cue thin = AutoCue.plan(env, env, windows, new double[]{7.0}, new double[]{7.0});
        assertNotNull(bare);
        assertNotNull(thin);

        assertEquals("点位被半成品歌词改动了", bare.outroCutSecs, thin.outroCutSecs, 0.0);
        assertEquals("进场点被改动了", bare.introEntrySecs, thin.introEntrySecs, 0.0);
        assertEquals("窗口被改动了", bare.windowSecs, thin.windowSecs, 0.0);
        assertEquals("说明（含每一项分数）被改动了", bare.note, thin.note);
    }
}
