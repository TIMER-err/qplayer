package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>「下一首的引子也太短 ⇒ 来不及把 B 铺进来 ⇒ 循环买时间」这一条真的接进决策层了</b>
 * （用户 2026-10-05 规格的第四条触发：「尾奏太短，<b>下一首 intro 也太短</b>：两首歌之间
 * 只剩 2–4 小节，直接交叉淡化会很赶」）。
 *
 * <h2>缺口是什么（这条判据修的那个）</h2>
 *
 * <p>规格里那一行原来在文档里写着「<b>尚未实现</b>：{@code planOutroLoop} 只从 {@code intro}
 * 取 BPM，没有 B 侧 intro 长度的判据」。这次补上：量的是 B 的"真正开始"
 * （{@code AutoCue.outroLoopIntroLeadSecs} → {@code introStartSecs}），门取
 * {@code AutoCue.LOOP_SHORT_INTRO_BARS = 4} 小节（为什么是 4 而不是规格里写的 8：
 * 8 会把参考对逼到只剩 0.17 小节余量——实测春めく浅扫引子 16.34 s = 8.17 小节——
 * 理由写在那个常量上）。它归"买时间"那一档：两边都铺不开时缺的是时间。
 *
 * <h2>素材：自己造的，答案是已知的（照 {@code AutoCueOutroLoopShortTailTest} 的做法）</h2>
 *
 * <p>合成 4/4 打击乐（振荡器只有 60/120 Hz 与 8 kHz 正弦 → 代理 VAD 判"没人唱"），
 * 走生产那一套扫描（降采样、RMS、谱通量、拍点估计都真的跑）：
 *
 * <ul>
 *   <li><b>A</b>：128 BPM。<b>14 小节前段 + 8 小节 vamp + 6 小节稀疏收尾</b>
 *       ——尾奏剩 6 小节（&gt; 4），所以<a>不是</a>"尾奏太短"那一条触发的；</li>
 *   <li><b>B</b>：32 小节 = 60.0 s、<b>同样 128 BPM</b>（BPM 差 0% ⇒ 不落进"对齐网格"
 *       那一档），开头 {@code introBars} 小节是安静的底鼓（0.15），之后进主段（0.85）
 *       ——引子多长由这一个参数说了算。</li>
 * </ul>
 *
 * <p>三条判据共用这一份 A，只把 B 的引子长度换掉：
 *
 * <ol>
 *   <li>{@link #aTooShortIntroOnTheNextTrackBuysTimeWithALoop}：<b>B 的引子只有 1 小节</b>
 *       → 引擎给 {@code buysTime} 的循环计划，理由里写着<b>「B 的引子」</b>；</li>
 *   <li>{@link #anEightBarIntroIsNotEnoughToTriggerTheLoop}：<b>B 的引子 8 小节</b>
 *       → <b>不</b>因这条触发（对照）；</li>
 *   <li>{@link #anIntroLongerThanTheShallowScanIsNotMisreadAsShort}：<b>B 的引子 12 小节</b>
 *       ——比进场侧的浅扫（20 s）还长，整段扫描都是引子。这时**读数不可信**，
 *       必须如实报"不知道"、<b>不</b>因此触发（"扫到的这一段本身就短"那一类误判，
 *       理由见 {@code AutoCue.LOOP_INTRO_RISE_RATIO}）。</li>
 * </ol>
 */
public class AutoCueOutroLoopShortIntroTest {

    private static final int RATE = 22050;

    /** A 与 B 同一个 BPM：把"对齐网格"那一档排除掉，触发只剩"买时间"这一族。 */
    private static final double BPM = 128.0;

    /** 交叠时长（4 小节 @128 BPM）：放得下，所以旧的两条时间触发都不成立。 */
    private static final double WINDOW = 7.5;

    private static final String SRC_A = "test://shortintro-a";
    private static final String SRC_B = "test://shortintro-b";

    // ==================================================================
    // 判据一：B 的引子只有 1 小节 ⇒ 循环买时间，理由里写着「B 的引子」
    // ==================================================================

    /**
     * <b>B 的引子只有 1 小节 ⇒ 循环买时间</b>。
     *
     * <p>断言四件事：
     *
     * <ol>
     *   <li><b>前提</b>（与实现无关的算术）：A 的尾奏里有一段 8 小节干净 vamp；赢的那个点
     *       剩下<b>超过 4 小节</b>的尾巴（所以不是"尾奏太短"那一条）；这次窗口是放得下的
     *       ——旧的三条触发在 (+BPM 差 0%) 这套点位上都不成立。没有这一条，
     *       "循环了"可以狡辩成"碰巧是别的触发"；</li>
     *   <li><b>决策层</b>：{@code Cue.outroLoop} 非 null、{@code buysTime} 为真、
     *       被接线口径收下（{@code loopIsWired}）；</li>
     *   <li><b>理由分得清是哪一侧</b>：写着「B 的引子」，且<b>没有</b>「尾奏只剩」——
     *       用户读日志验收的就是这一句；</li>
     *   <li><b>读数是量出来的</b>：{@code outroLoopIntroLeadSecs} 给出一个正的小节数
     *       （1~2 小节），不是靠"不知道"蒙的。</li>
     * </ol>
     */
    @Test
    public void aTooShortIntroOnTheNextTrackBuysTimeWithALoop() {
        Fixture fx = fixture(1);
        try {
            AutoCue.Envelope a = fx.outroEnv;
            double barA = 4.0 * 60.0 / a.tempo.bpm;
            double barB = 4.0 * 60.0 / fx.introEnv.tempo.bpm;
            AutoCue.Cue cue = cueOf(fx);
            assertNotNull("合成素材上应当挑得出点位（否则这条判据什么都没测）", cue);

            // ── 前提①：尾奏里有一段干净、整数小节的可循环 vamp ─────────────
            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(a);
            assertNotNull("尾奏里应当找得到可循环的 vamp（合成素材里它是 8 小节的纯打击乐重复）",
                    vamp);
            assertEquals("vamp 应当是整数 8 小节", 8, (int) Math.round(vamp.length() / barA));

            // ── 前提②：A 的尾奏**够长**（不是"尾奏太短"那一条触发的）─────────
            double tail = a.durationSecs - cue.outroCutSecs;
            double tailBars = tail / barA;
            assertTrue(String.format(java.util.Locale.US,
                            "这条判据要的是「尾奏够长、只是 B 的引子太短」，量到的尾奏是"
                                    + " %.2f s = %.2f 小节（出场点 %.2f s、全曲 %.2f s）"
                                    + "——夹具的形状变了，或者触发的根本不是 B 那一条",
                            Double.valueOf(tail), Double.valueOf(tailBars),
                            Double.valueOf(cue.outroCutSecs), Double.valueOf(a.durationSecs)),
                    tailBars >= 4.0);
            assertTrue("这次窗口是放得下的（放不下就不是 B 那一条）",
                    tail > cue.windowSecs);
            assertTrue("出场点 + 窗口伸到曲子外面去了——那就不是 B 那一条",
                    cue.outroCutSecs + cue.windowSecs <= a.durationSecs);

            // ── 前提③：B 的引子真的短（量出来的，不是猜的）──────────────────
            double lead = AutoCue.outroLoopIntroLeadSecs(fx.introEnv);
            double leadBars = lead / barB;
            assertTrue(String.format(java.util.Locale.US,
                            "这条判据要的是「B 的引子很短」，量到的是 %.2f s = %.2f 小节"
                                    + "（B 的浅扫包络扫到 %.2f s）——夹具的形状变了",
                            Double.valueOf(lead), Double.valueOf(leadBars),
                            Double.valueOf(fx.introEnv.rms.length * AutoCue.HOP_SECS)),
                    lead > 0.0 && leadBars < AutoCue.LOOP_SHORT_INTRO_BARS);

            // ── 判据：计划层给的是「买时间」，而且理由写的是 B 那一条 ──────────
            assertNotNull(String.format(java.util.Locale.US,
                            "B 的引子只有 %.2f 小节（不足 %.1f 小节，来不及把 B 铺进来），"
                                    + "尾奏里又有一段 8 小节可循环的 vamp（%.2f→%.2f s），"
                                    + "引擎却没给循环计划（出场点 %.2f s、窗口 %.2f s）",
                            Double.valueOf(leadBars),
                            Double.valueOf(AutoCue.LOOP_SHORT_INTRO_BARS),
                            Double.valueOf(vamp.from), Double.valueOf(vamp.to),
                            Double.valueOf(cue.outroCutSecs), Double.valueOf(cue.windowSecs)),
                    cue.outroLoop);
            assertTrue("计划给了，但不是「买时间」那一类 → 接线口径不收它，等于没修："
                    + cue.outroLoop.reason, cue.outroLoop.buysTime);
            assertTrue("「买时间」那一类必须被接线口径收下", AutoCue.loopIsWired(cue.outroLoop));
            assertTrue("理由里应当写着是 B 侧那一条（用户读日志验收的就是这一句）："
                    + cue.outroLoop.reason, cue.outroLoop.reason.contains("B 的引子"));
            assertFalse("这一次尾奏是够长的，理由里不该写成「尾奏只剩」——两侧必须分得清："
                    + cue.outroLoop.reason, cue.outroLoop.reason.contains("尾奏只剩"));
            assertTrue("理由里应当写着买时间：" + cue.outroLoop.reason,
                    cue.outroLoop.reason.contains("买时间"));
            assertTrue("说明行里应当写着这次循环（它是产物的一部分）",
                    cue.note.contains("尾奏循环"));

            System.out.printf(java.util.Locale.ROOT,
                    "%n[B 引子太短] A 全曲 %.2f s、出场 %.2f s、尾奏剩 %.2f s = %.2f 小节；"
                            + "B 的引子 %.2f s = %.2f 小节（不足 %.1f 小节）；"
                            + "窗口 %.2f s。理由：%s%n",
                    Double.valueOf(a.durationSecs), Double.valueOf(cue.outroCutSecs),
                    Double.valueOf(tail), Double.valueOf(tailBars),
                    Double.valueOf(lead), Double.valueOf(leadBars),
                    Double.valueOf(AutoCue.LOOP_SHORT_INTRO_BARS),
                    Double.valueOf(cue.windowSecs), cue.outroLoop.reason);
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 判据二（对照）：B 的引子 8 小节 ⇒ 不因这条触发
    // ==================================================================

    /**
     * <b>B 的引子 8 小节 ⇒ 不循环</b>——钉住门的位置（也钉住"参考对不受影响"这一类）。
     *
     * <p>这一份 B 与判据一只差引子长度（8 小节而不是 1 小节）：8 &gt; 4，所以这一条
     * <b>结构上不可能</b>成立；而 A 侧也一样（尾奏剩 6 小节 &gt; 4、窗口放得下、
     * BPM 差 0%）——于是两条新老触发一条都不成立，计划是 {@code null}。
     *
     * <p>它挡的是"随手把门放宽"：一旦有人把 {@code LOOP_SHORT_INTRO_BARS} 抬到 8
     * （§8.0.9 表格里写的就是 8 小节），这条对照立刻红——而那正是参考对
     * （春めく 浅扫引子 16.34 s = 8.17 小节）会被卷进来的那一步。
     */
    @Test
    public void anEightBarIntroIsNotEnoughToTriggerTheLoop() {
        Fixture fx = fixture(8);
        try {
            AutoCue.Envelope a = fx.outroEnv;
            double barB = 4.0 * 60.0 / fx.introEnv.tempo.bpm;
            AutoCue.Cue cue = cueOf(fx);
            assertNotNull("合成素材上应当挑得出点位", cue);

            double lead = AutoCue.outroLoopIntroLeadSecs(fx.introEnv);
            assertTrue(String.format(java.util.Locale.US,
                            "这条对照要的是「B 的引子够长」，量到的是 %.2f s = %.2f 小节"
                                    + "（门槛 %.1f 小节）——夹具的形状变了",
                            Double.valueOf(lead), Double.valueOf(lead / barB),
                            Double.valueOf(AutoCue.LOOP_SHORT_INTRO_BARS)),
                    lead > 0.0 && lead / barB > AutoCue.LOOP_SHORT_INTRO_BARS);

            assertNull(String.format(java.util.Locale.US,
                            "B 的引子有 %.2f 小节（超过 %.1f 小节那条线），尾奏也够长、"
                                    + "BPM 差 0%% → 一条触发都不成立，不该循环，引擎却给了计划：%s",
                            Double.valueOf(lead / barB),
                            Double.valueOf(AutoCue.LOOP_SHORT_INTRO_BARS),
                            cue.outroLoop == null ? "" : cue.outroLoop.reason),
                    cue.outroLoop);
            assertFalse("不该循环的计划却被接线口径收了", AutoCue.loopIsWired(cue.outroLoop));
            assertFalse("说明行里不该出现「尾奏循环」", cue.note.contains("尾奏循环"));

            System.out.printf(java.util.Locale.ROOT,
                    "%n[8 小节引子对照] B 的引子 %.2f s = %.2f 小节（> %.1f 小节）→ 不循环%n",
                    Double.valueOf(lead), Double.valueOf(lead / barB),
                    Double.valueOf(AutoCue.LOOP_SHORT_INTRO_BARS));
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 判据三：引子比浅扫还长 ⇒ 读数不可信，如实"不知道"
    // ==================================================================

    /**
     * <b>引子比进场侧的浅扫还长时，不许把"扫到的这一段"误读成"B 的引子很短"</b>。
     *
     * <p>B 的引子取 12 小节 = 22.5 s，而 B 是 60 s 的歌 → 进场侧只扫
     * {@code min(20, 25%) + 5 = 20 s}（{@code AutoCue.introScanSecs}），<b>整段扫描都在引子里</b>。
     * 这时 {@code introStartSecs} 的参考电平（"扫到的那一段"前 60 s 的 85 分位）会跟着降成
     * <b>引子自己的电平</b>，于是它在开头几十毫秒就报"到主电平了"——读数看着是"引子极短"，
     * 其实什么都没量到。这正是"扫到的这一段本身就短"那一类误判。
     *
     * <p>所以断言两件事：① {@code outroLoopIntroLeadSecs} 如实返回负值（<b>不知道</b>）；
     * ② 计划因此不循环——"不知道就不拦"。同时把判据一侧的读数（正的小节数）也钉住，
     * 免得这条判据退化成"永远返回 -1 也能过"。
     */
    @Test
    public void anIntroLongerThanTheShallowScanIsNotMisreadAsShort() {
        Fixture fx = fixture(12);
        try {
            double scanned = fx.introEnv.rms.length * AutoCue.HOP_SECS;
            assertTrue(String.format(java.util.Locale.US,
                            "这条判据要的是「引子比浅扫还长」，而浅扫只扫到 %.2f s"
                                    + "（B 全曲 %.2f s）——夹具的形状变了",
                            Double.valueOf(scanned), Double.valueOf(fx.introEnv.durationSecs)),
                    scanned < fx.introEnv.durationSecs - 1.0);

            double lead = AutoCue.outroLoopIntroLeadSecs(fx.introEnv);
            assertTrue(String.format(java.util.Locale.US,
                            "整段扫描都落在引子里时读数不可信（参考电平跟着降成引子自己的电平），"
                                    + "必须如实报「不知道」；现在报的是 %.2f s —— 那会把"
                                    + "「没扫到主段」当成「B 的引子太短」，正是这条判据要防的误判",
                            Double.valueOf(lead)),
                    lead < 0.0);

            AutoCue.Cue cue = cueOf(fx);
            assertNotNull("合成素材上应当挑得出点位", cue);
            assertNull("读数不知道 → 这一条不参与（不知道就不拦），计划应当是 null："
                            + (cue.outroLoop == null ? "" : cue.outroLoop.reason),
                    cue.outroLoop);

            System.out.printf(java.util.Locale.ROOT,
                    "%n[引子比浅扫还长] 浅扫 %.2f s 全在引子里 → 读数 %.1f（不知道）→ 不循环%n",
                    Double.valueOf(scanned), Double.valueOf(lead));
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 夹具：合成 4/4 打击乐 + 假解码器（与 AutoCueOutroLoopShortTailTest 同一套）
    // ==================================================================

    static final class Fixture {
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

        public void close() {
            Decoder.install(null);
        }
    }

    /** 生产口径的一次挑点：A 用整曲包络、B 用浅扫包络（与 {@code AutoCueTransition.prepare} 一致）。 */
    private static AutoCue.Cue cueOf(Fixture fx) {
        return AutoCue.planWithLyricSpans(fx.outroEnv, fx.introEnv, new double[]{WINDOW},
                AutoCue.vocalPhraseSpans(fx.outroEnv),
                AutoCue.vocalPhraseSpans(fx.introEnv), AutoCue.Bridge.MEASURE);
    }

    static Fixture fixtureFor(int introBars) {
        return fixture(introBars);
    }

    static Fixture fixture(int introBars) {
        final float[] a = trackA();
        final float[] b = trackB(introBars, BPM);
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, java.util.Map<String, String> headers) {
                return source != null && source.contains("shortintro-b")
                        ? new Fake(b, RATE) : new Fake(a, RATE);
            }
        });
        return new Fixture(a, b, AutoCue.scan(SRC_A, null), AutoCue.scanIntro(SRC_B, null));
    }

    /**
     * 素材 A：128 BPM。<b>14 小节固定前段 + 8 小节 vamp + 6 小节稀疏收尾</b>
     * （尾奏剩 6 小节 &gt; 4，所以不是"尾奏太短"那一条）。
     */
    private static float[] trackA() {
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
        append(kinds, levels, bar + 8, "ffffff", 0.45f);     // 22..27 稀疏收尾（6 小节）
        return synth(BPM, kinds.toString(), levels);
    }

    /**
     * 素材 B：32 小节 = 60.0 s。<b>开头 {@code introBars} 小节安静底鼓（0.15）然后进主段</b>
     * ——引子长度由它决定；128 BPM 与 A 相同（BPM 差 0%）。
     *
     * <p>浅扫只覆盖前 {@code min(20, 25%) + 5 = 20 s}：{@code introBars = 1/8} 时扫描
     * 覆盖得到主段（读数可信），{@code introBars = 12}（22.5 s）时整段扫描都在引子里
     * （读数不可信，判据三）。
     *
     * <p><b>为什么整条 B 要错开 0.37 拍</b>（只有 B 错开，A 保持与
     * {@code AutoCueOutroLoopShortTailTest} 逐字相同的形状）：不错开的话第一记底鼓正好落在
     * 采样 0 上，于是**第 0 个 hop 装着完整的起振、后面的底鼓都跨在两个 hop 之间**——
     * 第 0 格的 RMS 会变成整段的峰值（实测 1.00，而平滑电平的 85 分位只有 0.19），
     * 而 {@code smoothLevel} 在第 0 格没有窗（{@code sum / min(i+1, w)}），于是
     * {@code introStartSecs} 一律返回 0.0。那是**夹具的起振对齐**造出来的假象，
     * 真素材上不存在（实测那 8 首的读数都是 0.46~17.4 s）——修夹具，不是修判据。
     */
    private static float[] trackB(int introBars, double bpm) {
        StringBuilder kinds = new StringBuilder();
        float[] levels = new float[64];
        int bar = 0;
        for (int i = 0; i < introBars && bar < 32; i++, bar++) {
            kinds.append('i');
            levels[bar] = 0.15f;
        }
        for (; bar < 32; bar++) {                            // 主段：每拍一记底鼓
            kinds.append('k');
            levels[bar] = 0.85f;
        }
        return synth(bpm, kinds.toString(), levels, 0.37);
    }

    /** 造一条 4/4 打击乐轨：{@code kinds} 每小节一个字符，{@code levels} 每小节一个电平。 */
    private static float[] synth(double bpm, String kinds, float[] levels) {
        return synth(bpm, kinds, levels, 0.0);
    }

    /** 同上，但整条轨的拍点再往后错开 {@code offsetBeats} 拍（见 {@link #trackB}）。 */
    private static float[] synth(double bpm, String kinds, float[] levels, double offsetBeats) {
        double beat = 60.0 / bpm;
        double bar = 4 * beat;
        float[] out = new float[(int) Math.round((kinds.length() + offsetBeats / 4.0) * bar * RATE)];
        for (int b = 0; b < kinds.length(); b++) {
            char kind = kinds.charAt(b);
            float level = levels[Math.min(b, levels.length - 1)];
            for (int k = 0; k < 4; k++) {
                double t = (b * 4 + k + offsetBeats) * beat;
                switch (kind) {
                    case 'i':
                        kick(out, t, level, false);
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
                    default:                                 // 'k' / 'f'：每拍一记底鼓
                        kick(out, t, level, k % 2 == 1);
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
}
