package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>「尾奏循环 · 买时间」这一档现在根本触发不了</b>——这条判据把那个缺口钉死。
 *
 * <h2>缺口是什么</h2>
 *
 * <p>接线的口径（{@code AutoCue.loopIsWired}）<strong>只收「买时间」那一类</strong>循环：
 * 渲染路径（{@code AutoCueTransition.prepareLoopOutgoing}）只把 {@code buysTime = true}
 * 的计划变成真的素材替换。而 {@code AutoCue.planCollect} 里有一条硬约束：
 *
 * <pre>
 *   double lastLegalCut = outroDuration - TAIL_GUARD_SECS;        // 2.0 s 退出余量
 *   ...
 *   if (cutTimes[c] + windows[w] &gt; lastLegalCut) continue;        // 窗口必须放得进尾奏
 * </pre>
 *
 * <p>也就是说：<strong>「过渡窗口伸到尾奏外面」的候选在打分之前就被丢掉了</strong>。
 * 而被丢掉的恰恰就是"重复段落用来买时间"的<strong>唯一</strong>用武之地——尾奏剩不下一个
 * 窗口，才需要把尾奏里那段干净的器乐 vamp 重复一遍来把时间补出来。于是
 * {@code planOutroLoop} 收到的 {@code cutSecs} 永远满足
 * {@code 全曲 − cutSecs ≥ 窗口 + 2 s}，里面那个
 * {@code needsTime = tailRemaining < windowSecs || cutSecs + windowSecs > duration}
 * <strong>恒为 false</strong>（本机 6 对真素材实测 {@code LoopDecisionProbe} 全为 false），
 * {@code Cue.outroLoop} 于是永远只有 {@code null} 与"对齐网格"两类——而后者又有意不接线
 * （见 {@code AutoCue.loopIsWired}）。结论：<strong>这一档现在一次都跑不到</strong>。
 *
 * <h2>素材：自己造的，答案是已知的</h2>
 *
 * <p>合成素材（照 {@code AutoCueOutroLoopTest} 的做法），振荡器只有 60/120 Hz 与 8 kHz
 * 正弦——代理 VAD（200–2000 Hz 那条带）因此判"没人唱"，与真素材上 vamp 的处境一样：
 *
 * <ul>
 *   <li><b>A</b>：128 BPM、<b>24 小节 = 45.0 s</b>（小节 1.875 s）。安静引子 / 底鼓 hi-hat 段
 *       / 两小节过门 / <b>vamp：8 小节 [26.25, 41.25)</b>（每拍底鼓 + 反拍 hat、逐小节相同
 *       → 前后半自相似≈1、电平稳、人声 0%）/ <b>最后 2 小节稀疏收尾 [41.25, 45.0)</b>。
 *       于是 <b>vamp 之后只剩 3.75 s</b>；</li>
 *   <li><b>B</b>：32 小节 = 60.0 s、<b>同样 128 BPM</b>（BPM 差 0% ⇒ 不会落进"对齐网格"
 *       那一档，触发只剩"买时间"这一条——变量只有一个）。</li>
 * </ul>
 *
 * <p>用户设的交叠时长 <b>7.5 s</b>。这条素材的形状正好把缺口夹在中间：
 *
 * <pre>
 *   计划路径的出场点上界 = 全曲 − 窗口 − 退出余量 = 45.0 − 7.5 − 2.0 = 35.5 s
 *   买时间需要的出场点   = vamp 终点                                = 41.25 s
 *   35.5 &lt; 41.25  ⇒  那个点位压根不在候选里，needsTime 无从成立
 * </pre>
 *
 * <p>（量出来的数会差一点点：{@code barPhaseOf} 在 128 BPM 的合成素材上把小节相位定在
 * 26.25 + 0.43 s 处——每一拍都有底鼓，相位本来就是弱可辨的——于是引擎报出来的 vamp 是
 * <b>[26.68, 41.68)</b>，长度仍是整数 8 小节、仍在同一条算术的另一侧。判据里的阈值都写成
 * 从量出来的 {@code vamp} 推出来的量，不写死 26.25/41.25，所以这一点不影响结论。）
 *
 * <h2>这条判据断言什么</h2>
 *
 * <ol>
 *   <li><b>前提</b>（现在就绿）：量得出点位、尾奏里确实有 8 小节可循环 vamp、
 *       而且"播到 vamp 结束再切"这个点位放不下窗口——缺口确实被构造出来了；</li>
 *   <li><b>决策层本身是愿意的</b>（现在就绿）：直接问
 *       {@code AutoCue.planOutroLoop(outroEnv, introEnv, vamp, vamp.to, 7.5, false)}，
 *       它给出<strong>非 null 且 {@code buysTime == true}</strong> 的计划，而且
 *       {@code loopIsWired} 收它。这一条把「决策层没问题、是计划路径把候选丢了」
 *       钉成事实——没有它，缺口可以狡辩成"决策层不认这段 vamp"；</li>
 *   <li><b>缺口</b>（<b>现在红</b>）：这种素材上 {@code Cue.outroLoop} 仍然是
 *       {@code null}。断言写成"必须非 null"（也就是期望的正确行为），所以今天失败、
 *       修好之后转绿；失败信息里带着这一对的现状数字，读的人不用自己去算。</li>
 * </ol>
 *
 * <p><b>修法的一句话建议</b>：把那句 {@code continue} 从"丢弃"改成"记下来、并把它交给
 * 尾奏循环那一档"——即候选在 {@code cutTimes[c] + windows[w] > lastLegalCut} 时，
 * 若尾奏里量得出可循环 vamp（{@code outroVampSpan(outro) != null}），就<strong>照常打分</strong>
 * 并给这个候选打上 {@code needsTime} 标记，让它走进 {@code planOutroLoop} 的"买时间"分支；
 * 窗口放得进尾奏的那些候选一个字节都不受影响（新分支只在原来 {@code continue} 的那一支上
 * 生效），"窗口放得进时行为逐位不变"因此仍然成立。
 */
public class AutoCueOutroLoopBuyTimeTest {

    private static final int RATE = 22050;

    /** 用户设的交叠时长（秒）：与 {@code AutoCueOutroLoopTest} 同一个量级，在这份素材上正好 4 小节。 */
    private static final double NOMINAL_WINDOW = 7.5;

    /** A 与 B 同一个 BPM：把"对齐网格"那一档排除掉，触发只剩"买时间"这一条。 */
    private static final double BPM = 128.0;

    private static final String SRC_A = "test://buytime-a";
    private static final String SRC_B = "test://buytime-b";

    /**
     * <b>尾奏只有 3.75 s、里面却有一段 8 小节干净 vamp 的素材上，引擎给不出循环计划。</b>
     *
     * <p>判据三部分（前提 / 决策层 / 缺口）见类注释。第三部分今天应当是<strong>红的</strong>。
     */
    @Test
    public void shortTailWithALoopableVampIsNeverPlannedAsBuyTime() {
        float[] pcmA = trackA();
        float[] pcmB = trackB();
        AutoCue.Envelope outro;
        AutoCue.Envelope intro;
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, java.util.Map<String, String> headers) {
                return source != null && source.contains("buytime-b")
                        ? new Fake(pcmB, RATE) : new Fake(pcmA, RATE);
            }
        });
        try {
            // 走生产那一套扫描（降采样、RMS、谱通量、拍点估计都真的跑）——包络不是造出来的
            outro = AutoCue.scan(SRC_A, null);
            intro = AutoCue.scanIntro(SRC_B, null);
        } finally {
            Decoder.install(null);
        }
        assertNotNull("A 扫不出包络，这条判据什么都没测", outro);
        assertNotNull("B 扫不出包络，这条判据什么都没测", intro);

        double duration = outro.durationSecs;
        double barSecs = 4.0 * 60.0 / outro.tempo.bpm;
        assertTrue("A 的 BPM 没量到 128 附近（量到 " + outro.tempo.bpm
                + "）——小节网格与 vamp 都会跟着歪，判据不成立", Math.abs(outro.tempo.bpm - BPM) < 1.0);

        // ── 前提①：点位量得出来（否则下面的缺口证明不了什么）──────────────
        AutoCue.Cue cue = AutoCue.planWithLyricSpans(outro, intro,
                new double[]{NOMINAL_WINDOW}, AutoCue.vocalPhraseSpans(outro),
                AutoCue.vocalPhraseSpans(intro), AutoCue.Bridge.MEASURE);
        assertNotNull("合成素材上应当挑得出点位（否则这条判据什么都没测）", cue);

        // ── 前提②：尾奏里确实有一段干净、整数小节的可循环 vamp ────────────
        AutoCue.VampSpan vamp = AutoCue.outroVampSpan(outro);
        assertNotNull("尾奏里应当找得到可循环的 vamp（合成素材里它是 8 小节的纯打击乐重复）", vamp);
        assertEquals("vamp 应当是整数 8 小节（量到 " + vamp.length() + " s / 小节 " + barSecs + " s）",
                8, (int) Math.round(vamp.length() / barSecs));
        assertTrue("vamp 的起点不在小节线上：" + vamp.from,
                isOnBarGrid(vamp.from, AutoCue.barPhaseOf(outro, barSecs), barSecs));
        assertTrue("vamp 不够干净（自相似 " + vamp.selfSim + "、人声 " + vamp.vocalFrac
                + "、电平 cv " + vamp.levelCv + "）——素材没造对，缺口也就没构造出来",
                vamp.selfSim >= AutoCue.VAMP_MIN_SELF_SIM
                        && vamp.vocalFrac <= AutoCue.VAMP_MAX_VOCAL_FRACTION
                        && vamp.levelCv <= AutoCue.VAMP_MAX_CV);

        // ── 前提③：vamp 之后放不下这次窗口 —— 这正是"买时间"的用武之地 ────
        double tailAfterVamp = duration - vamp.to;
        assertTrue("vamp 之后还剩 " + tailAfterVamp + " s，本来就放得下 " + NOMINAL_WINDOW
                + " s 窗口 → 这条判据没有构造出「尾奏放不下窗口」",
                tailAfterVamp < NOMINAL_WINDOW);
        assertTrue("「播到 vamp 结束再切」并没有把窗口伸到曲子外面（"
                + vamp.to + " + " + NOMINAL_WINDOW + " ≤ " + duration + "）",
                vamp.to + NOMINAL_WINDOW > duration);

        // ── 前提④：这个买时间点位越过了计划路径的出场点上界，缺口才存在 ────
        //
        // 这一段是纯算术（全曲、窗口、退出余量、vamp 终点），修法改了代码也不会改它：
        // 它就是"那句 continue 把买时间唯一需要的点位丢掉"的算式形式。
        double planCutCeil = duration - NOMINAL_WINDOW - AutoCue.TAIL_GUARD_SECS;
        assertTrue("这条判据没构造出缺口：买时间需要的出场点（vamp 终点 " + vamp.to
                        + " s）没有越过计划路径的出场点上界（全曲 − 窗口 − 退出余量 = "
                        + planCutCeil + " s）",
                vamp.to > planCutCeil);

        // ── 第二部分：决策层本身是愿意的（拿 vamp 终点当出场点直接问它）──────
        //
        // 这一条现在就应当是绿的。它是缺口和"决策层不认这段 vamp"之间的分界线：
        // 它绿而下面那条红 ⇒ 丢候选的是计划路径，不是决策层。
        AutoCue.OutroLoopPlan decided =
                AutoCue.planOutroLoop(outro, intro, vamp, vamp.to, NOMINAL_WINDOW, false);
        assertNotNull("决策层在「播到 vamp 结束再切」这个点位上不给计划（尾奏只剩 "
                + tailAfterVamp + " s、放不下 " + NOMINAL_WINDOW + " s 窗口，本该是买时间那一档）"
                + "——那缺口就不是「计划路径丢候选」，而是「这一档压根没实现」", decided);
        assertTrue("这个点位是「买时间」（窗口伸到曲子外面），却没被标成买时间：" + decided.reason,
                decided.buysTime);
        assertTrue("理由里应当写着买时间：" + decided.reason, decided.reason.contains("买时间"));
        assertEquals("循环段应当是尾奏里那 8 小节", 8, decided.bars);
        assertTrue("「买时间」这一类必须落在接线口径内（否则改好了也不会生效）",
                AutoCue.loopIsWired(decided));

        // 缺口里的另一半事实（现在就绿）：引擎**认出了**这段 vamp，却什么都没计划。
        // 有这一条，缺口只能说成"计划这一环丢了候选"，不能说成"没认出素材"。
        assertTrue("引擎连这段 vamp 都没认出来（那缺口就在 vamp 判据、不在计划路径）：" + cue.note,
                cue.note.contains("尾奏 vamp"));

        // ── 第三部分（缺口）：生产路径上 Cue.outroLoop 仍然是 null ──────────
        //
        // 断言写成"必须非 null"——也就是期望的正确行为。今天红、修好之后绿。
        // 失败信息里带上这一对的现状数字：读的人不用自己回去算"它到底切在哪"。
        assertNotNull(String.format(java.util.Locale.US,
                        "尾奏只剩 %.2f s、里面就有一段 8 小节干净可循环的 vamp（%.2f→%.2f s），"
                                + "引擎却给不出循环计划：Cue.outroLoop == null。"
                                + "计划路径的出场点上界是 全曲 %.2f − 窗口 %.2f − 退出余量 %.2f = %.2f s，"
                                + "而买时间需要的出场点（vamp 终点）是 %.2f s —— 那个候选被 "
                                + "planCollect 里那句 `cutTimes[c] + windows[w] > lastLegalCut → continue` "
                                + "在打分之前丢掉了，于是 planOutroLoop 的 needsTime 恒为 false、"
                                + "「买时间」这一档一次都走不到（现状：出场点 %.2f s、窗口 %.2f s、丢尾 %.2f s）",
                        Double.valueOf(tailAfterVamp), Double.valueOf(vamp.from), Double.valueOf(vamp.to),
                        Double.valueOf(duration), Double.valueOf(NOMINAL_WINDOW),
                        Double.valueOf(AutoCue.TAIL_GUARD_SECS), Double.valueOf(planCutCeil),
                        Double.valueOf(vamp.to), Double.valueOf(cue.outroCutSecs),
                        Double.valueOf(cue.windowSecs), Double.valueOf(cue.tailDropSecs)),
                cue.outroLoop);

        // 下面的断言讲的是"有计划之后"的形状：缺口就在上面那条 null 上，
        // 这里加个守卫，免得 null 之后再炸一个 NPE 把失败原因搅浑。
        if (cue.outroLoop != null) {
            assertTrue("计划给了，但不是「买时间」那一类 → 接线口径不收它，等于没修："
                    + cue.outroLoop.reason, cue.outroLoop.buysTime);
            assertTrue("「买时间」那一类必须被接线口径收下", AutoCue.loopIsWired(cue.outroLoop));
            assertEquals("循环段不是尾奏里那一段 8 小节的 vamp",
                    vamp.from, cue.outroLoop.span.from, barSecs / 2.0);
            assertTrue("说明行里应当写着这次循环（它是产物的一部分）",
                    cue.note.contains("尾奏循环"));
        }
    }

    // ==================================================================
    // 素材：合成 4/4 打击乐 + 假解码器
    // （与 AutoCueOutroLoopTest 同一套做法；那边的方法是 private，所以这里抄一份）
    // ==================================================================

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

    /** 素材 B：32 小节 = 60.0 s，<b>同样 128 BPM</b>（BPM 差 0% ⇒ 不落进"对齐网格"那一档）。 */
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

    /**
     * 时刻是不是落在小节网格上（相位取自 {@code AutoCue.barPhaseOf}）。
     *
     * <p>容差一<strong>帧</strong>——与 {@code AutoCueOutroLoopTest} 同一把尺子：实现那边把
     * "几小节"一次性换算成秒，残差只有一次舍入（≤ 半帧），不随小节数累积。
     */
    private static boolean isOnBarGrid(double secs, double phase, double barSecs) {
        double bars = (secs - phase) / barSecs;
        return Math.abs(bars - Math.round(bars)) * barSecs < 1.0 / RATE;
    }
}
