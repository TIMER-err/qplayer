package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.model.BeatModels;
import dev.t1m3.qplayer.audio.model.ModelDownloads;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>AI 比较选中的那套方案，必须就是真正跑的那一套。</b>
 *
 * <h3>用户报的问题（2026-09-30，另一个 AI 从日志里看出来的）</h3>
 *
 * <p>「AI 比较里选声波重合 @ 窗口 W1，实际装载的是窗口 W2」——两次方向还不一样（一次变长、
 * 一次变短）。核实下来根因在 {@code AiMix.prepare}：AI 比较选出声波重合之后，
 * 下面那段「按现有接法的拍数算窗口」会**无条件**把窗口覆盖掉：
 *
 * <pre>
 * window = alignedWindow;      // ← 现有接法的窗口，把比较选中的那个盖掉了
 * </pre>
 *
 * <p>于是「选 12.0 s」实际按 4.0 s 跑，而且 {@code plan.note} 里还写着 12.0 s——
 * 决策与执行两张皮、日志里那个数从来没生效过。同一处还漏了另外三个数：那个方案自带的
 * <b>出场点 / 进场点 / 丢尾</b>（`decided.cue`）在装载时全丢了，用的是现有接法那一套点位
 * （进场点甚至是 0——`HarmonicBlend.prepare` 恒返回 0，它不认识自动点位挑的点）。
 *
 * <h3>判据怎么立</h3>
 *
 * <p>素材造得让现有接法与声波重合的窗口差三倍（探针实测：现有接法 4.0 s、
 * 声波重合七项更高 @ 12.0 s），然后<b>拿 note 里那几个数去核对真正装载的那几个数</b>——
 * note 是我们告诉用户的，装载是引擎真跑的，两者必须逐项相等：
 *
 * <ul>
 *   <li>「交叠 X s」（那套方案的窗口）↔ {@code windowFrames(rate)}；</li>
 *   <li>「进场 Z s」↔ {@code plan.skipSecs}；</li>
 *   <li>「丢尾 Y s」↔ {@code plan.outroTailSecs}；</li>
 *   <li>顺带核对它自洽：{@code 出场点 + 交叠 + 丢尾 = 曲长}。</li>
 * </ul>
 */
public class AiMixHarmonicCueTest {

    private static final int RATE = 48_000;

    /**
     * 出场曲 <b>120 秒</b>、进场曲 20 秒。
     *
     * <p>出场曲原来是 40 秒，2026-10-02 加「出场点必须落在最后 15%」这条硬约束之后不成立了：
     * 40 秒的 15% 只有 6 秒，而这一对要用的窗口是 12 秒——「窗口放得进最后 15%」在数学上
     * 互斥（切割点还是 20 ms 网格，连"恰好"都做不到），于是 12 秒档全部被否、声波重合
     * 掉到 3 秒档、比较结果翻成现有接法，判据红得莫名其妙。素材长度拉到 120 秒之后
     * （0.15 × 120 = 18 s > 12 s）约束与窗口都成立，判据钉的性质没变：
     * <b>比较选中的窗口与点位必须就是真正装载的那一套</b>。
     */
    private static final double OUTRO_SECS = 120.0;

    @org.junit.Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private Decoder.Opener saved;

    @Before
    public void installSeams() {
        saved = currentOpener();
        Decoder.install(TestWavFile.opener());
        BeatModels.uninstall();
        ModelDownloads.uninstall();
    }

    @After
    public void restoreSeams() {
        BeatModels.uninstall();
        ModelDownloads.uninstall();
        Decoder.install(saved);
    }

    /**
     * 声波重合赢了比较时，装载的窗口/进场点/丢尾都得是**它自己**那套数。
     *
     * <p>素材：A 是 40 秒的 220 Hz 乐音、B 是 20 秒的 246.94 Hz（高两个半音，让
     * {@code Harmonic} 认为该转调，声波重合才有资格进候选）；两侧都没拍点，
     * 于是现有接法退回它的固定窗口（朴素融合 4.0 s），而声波重合的候选是按「现读值 6 s」
     * 展开的那一组——七项在那里能挑到 12.0 s（实测 0.689 vs 0.686）。
     */
    @Test
    public void theWindowAndPointsTheComparisonChoseAreTheOnesThatRun() throws Exception {
        Path dir = temporary.newFolder().toPath();
        Path a = TestWavFile.write(dir.resolve("cue-a.wav"),
                tone(220.0, OUTRO_SECS), tone(220.0, OUTRO_SECS), RATE);
        Path b = TestWavFile.write(dir.resolve("cue-b.wav"),
                tone(246.94, 20.0), tone(246.94, 20.0), RATE);

        AiMix t = new AiMix(MixStyle.AUTO, RATE, 2, MixTuning.defaults(), () -> 6.0);
        TransitionPlan plan = t.prepare(a.toString(), b.toString(), RATE);

        System.out.println("[ai-cue] note: " + plan.note);
        assertTrue("这一轮没有选出声波重合，测不到这条判据：" + plan.note,
                plan.note.contains("选 声波重合"));
        assertTrue("note 必须说明实际跑的是哪一套：" + plan.note,
                plan.note.contains("实际跑 声波重合"));

        // note 里那套方案的四个数（AutoCue 的挑点说明格式：
        // 「出场 26.0s（丢尾 2.0s）、进场 5.0s、交叠 12.0s」）
        double cueOutro = seconds(plan.note, "出场 ([0-9.]+)s");
        double cueTail = seconds(plan.note, "丢尾 ([0-9.]+)s");
        double cueEntry = seconds(plan.note, "进场 ([0-9.]+)s");
        double cueWindow = seconds(plan.note, "交叠 ([0-9.]+)s");
        double installed = t.windowFrames(RATE) / (double) RATE;

        System.out.println(String.format(java.util.Locale.ROOT,
                "[ai-cue] 比较选中：出场 %.1fs / 进场 %.1fs / 交叠 %.1fs / 丢尾 %.1fs"
                        + "；实际装载：窗口 %.3fs / 进场 %.3fs / 丢尾 %.3fs",
                Double.valueOf(cueOutro), Double.valueOf(cueEntry), Double.valueOf(cueWindow),
                Double.valueOf(cueTail), Double.valueOf(installed),
                Double.valueOf(plan.skipSecs), Double.valueOf(plan.outroTailSecs)));

        // 容差 0.06 s：note 里那几个数按 `%.1f` 印（实测真正的进场点是 4.98 s、note 写 5.0 s），
        // 所以核对的是「同一个数」，不是「同一个字符串」。
        assertEquals("装载的窗口必须是比较选中那个（原先被现有接法的窗口覆盖了）",
                cueWindow, installed, 0.06);
        assertEquals("装载的进场点必须是比较选中那个（`HarmonicBlend.prepare` 恒返回 0，"
                        + "原先 B 一律从 0 进）",
                cueEntry, plan.skipSecs, 0.06);
        assertEquals("装载的丢尾必须是比较选中那个（它决定触发点提前多少，"
                        + "丢了它融合点就不在比较时选的位置上）",
                cueTail, plan.outroTailSecs, 0.06);
        // 自洽：出场点 + 交叠 + 丢尾 = 曲长（三个数得是同一套方案的）
        assertEquals("那三个数不是同一套方案的：", OUTRO_SECS,
                cueOutro + cueWindow + cueTail, 0.2);
    }

    /**
     * <b>外面那层「自动点位」既不许改窗口，挑点也必须按真正会跑的那个窗口挑。</b>
     *
     * <p>另一个 AI 的日志分析怀疑是这一层覆盖了 AI 比较选中的窗口。核实结果：这一层在 AI 模式
     * （没有现读滑块、{@code tuneWindow=false}）下**早就不改窗口了**（2026-09-29 修的，
     * 见 {@code AutoCueTransition.prepare} 里那段「AI 模式就是这一档」的注释），真正的元凶在
     * {@code AiMix} 里。但这条链还有一半要钉：它挑点用的候选窗口是
     * {@code windowCandidates(nominal, tuneWindow)}——{@code tuneWindow=false} 时**只有一个**，
     * 而那个 nominal 来自 {@code base.windowFrames()}。上面那条判据修好之后它读到的是
     * 声波重合方案自己的窗口，所以「挑点」与「执行的窗口」终于是同一个数；
     * 这一条就是把它固定下来（判据里直接核 note 的「交叠」与装载窗口是否同一个数）。
     */
    @Test
    public void theAutoCueLayerDecidesPointsForTheWindowThatActuallyRuns() throws Exception {
        Path dir = temporary.newFolder().toPath();
        Path a = TestWavFile.write(dir.resolve("chain-a.wav"),
                tone(220.0, OUTRO_SECS), tone(220.0, OUTRO_SECS), RATE);
        Path b = TestWavFile.write(dir.resolve("chain-b.wav"),
                tone(246.94, 20.0), tone(246.94, 20.0), RATE);

        AiMix inner = new AiMix(MixStyle.AUTO, RATE, 2, MixTuning.defaults(), () -> 6.0);
        // 与线上 AI 模式同一档参数：没有现读来源（用户滑块）、不许调窗口
        AutoCueTransition engine = new AutoCueTransition(inner, RATE, 2, null, false);
        TransitionPlan plan = engine.prepare(a.toString(), b.toString(), RATE);

        double installed = engine.windowFrames(RATE) / (double) RATE;
        System.out.println(String.format(java.util.Locale.ROOT,
                "[ai-cue] 整条链：装载窗口 %.3fs；note: %s",
                Double.valueOf(installed), plan.note));
        // 里面那层（AI 比较）选中的窗口还在——这是上面那条判据在整条链上的复核。
        //
        // 这里原先写死 12.0 s（当年按旧候选空间量出来的数）。2026-10-01 修了候选上界
        // （原来按**最宽**候选窗口算，会把「短窗 + 晚点」整片剪掉）之后，这堆合成素材上
        // 挑出来的是 3.0 s——**具体秒数不是这条判据的契约**，契约是「note 里写的、
        // 里面那层挑的、真正装载的，是同一个数」，所以改成按契约断言；另外钉住
        // 「不许是外层那个 4.0 s 的朴素融合标称值」，覆盖原本那个「改窗口」的失败模式。
        double innerWindow = inner.windowFrames(RATE) / (double) RATE;
        double noteWindow = seconds(plan.note, "交叠 ([0-9.]+)s");
        assertEquals("装载的必须就是里面那层挑中的那个窗口",
                innerWindow, installed, 0.01);
                assertEquals("note 里写的窗口必须就是真正装载的那个", noteWindow, installed, 0.06);
        assertTrue("窗口不许是外层那个不随速度变的 4.0 s 标称值（那正是原先的失败模式）："
                        + plan.note,
                Math.abs(installed - 4.0) > 0.2);
        // 它挑点用的候选窗口**只有一个**（tune=false）——就是真正会跑的那个，
        // 所以「它挑的点」与「执行的窗口」从构造上就是同一套数
        assertTrue("它挑点用的候选窗口必须只有实际会跑的那一个：" + plan.note,
                plan.note.contains("候选 1 个窗口"));
        assertEquals("它挑出来的「交叠」与实际装载的窗口不是一个数就说明两张皮：" + plan.note,
                installed, seconds(plan.note, "交叠 ([0-9.]+)s"), 0.06);
    }

    /** 从 note 里取一个形如 {@code 前缀 12.0s} 的数（取不到就直接红，别静默当成 0）。 */

    private static double seconds(String note, String regex) {
        Matcher m = Pattern.compile(regex).matcher(note);
        assertTrue("note 里找不到「" + regex + "」：" + note, m.find());
        return Double.parseDouble(m.group(1));
    }

    /**
     * 一条<b>没有起音、也没有周期性</b>的稳态乐音：220 Hz 基频 + 二次谐波。
     *
     * <p>相位每 {@value #PHASE_CHUNK_SECS} 秒随机重置一次。这一条是 2026-10-01 加的，
     * 起因是<b>置信度换尺子</b>：恒幅正弦在逐格 RMS 上会留下「正弦相位相对 hop 的拍频
     * 抖动」，那是个**真周期**——新的归一化自相关尺子会给它 0.82（真歌 skydive 只有
     * 0.21），于是这条素材会被测出一个假的拍栅格，本判据「两侧都没拍点 → 现有接法退回
     * 固定窗口」的前提就不成立了。相位断续之后抖动的周期性被破坏，音高（谐波结构）
     * 不变，所以 {@code Harmonic} 认定该转调这件事照旧——素材对本判据的用途没变。
     */
    private static float[] tone(double hz, double secs) {
        int n = (int) Math.round(secs * RATE);
        float[] v = new float[n];
        int chunk = Math.max(1, (int) Math.round(PHASE_CHUNK_SECS * RATE));
        java.util.Random random = new java.util.Random(17);
        double phase = 0.0;
        double lastPhase = 0.0;
        for (int i = 0; i < n; i++) {
            if (i % chunk == 0) {
                lastPhase = phase;
                phase = random.nextDouble() * 2.0 * Math.PI;
            }
            double t = (double) (i % chunk) / RATE;
            v[i] = (float) (0.3 * Math.sin(2.0 * Math.PI * hz * t + phase)
                    + 0.15 * Math.sin(4.0 * Math.PI * hz * t + 2.0 * (phase - lastPhase)));
        }
        return v;
    }

    /** {@link #tone} 的相位重置间隔（秒）。 */
    private static final double PHASE_CHUNK_SECS = 0.05;

    private static Decoder.Opener currentOpener() {
        try {
            Field f = Decoder.Holder.class.getDeclaredField("opener");
            f.setAccessible(true);
            return (Decoder.Opener) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
