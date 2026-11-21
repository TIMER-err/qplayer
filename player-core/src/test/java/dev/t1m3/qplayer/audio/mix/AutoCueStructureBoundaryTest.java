package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>结构边界候选</b>（{@link AutoCue#structureBoundaries} 与 {@link AutoCue#candidates}）。
 *
 * <p>用户的要求是「候选点应该是结构边界——每个 8 小节 / 16 小节乐句边界、有人开始唱 /
 * 唱完的位置，而不是几百上千个『主歌中间切一刀』的点」。这条判据钉三件事：
 *
 * <ol>
 *   <li><b>边界真的落在小节线上</b>：稳定网格的夹具上，边界集合<b>恰好</b>是 8 小节线
 *       （16 的倍数天然在 8 的倍数里，所以一条规则同时满足两级乐句）；</li>
 *   <li><b>四路来源都通</b>：小节线、人声活动段起止、能量骤变、尾奏/进场结构地标；</li>
 *   <li><b>兜底逐位相同</b>：结构边界不足 4 个（或个数够却铺不开，见
 *       {@link AutoCue#STRUCTURE_MAX_GAP_SECS}）时退回 20 ms 均匀网格，
 *       且那一串点与改这一行之前的实现<b>逐位相同</b>（仓库那条"关掉特性 ⇒ 逐位相同"的纪律）。</li>
 * </ol>
 *
 * <p>夹具全部是合成的（不需要解码器、不需要模型），搭法与 {@code AutoCueBarTest} 一致：
 * 直接调包内构造器 {@code new AutoCue.Envelope(...)}，把包络的每个字段都握在手里——
 * 这样"边界应该出现在哪"是算得出来的，而不是"跑出来什么样就断言什么样"。
 */
public class AutoCueStructureBoundaryTest {

    /** 拍长 0.5 s、一小节 2.0 s、8 小节 16.0 s（4/4）。 */
    private static final double BPM = 120.0;
    private static final double BAR_SECS = 4.0 * 60.0 / BPM;
    private static final double PHRASE_SECS = 8.0 * BAR_SECS;
    private static final double LEN_SECS = 64.0;

    /** 夹具里 8 小节线应该在的位置：0 / 16 / 32 / 48。 */
    private static final double[] EXPECTED_PHRASE_LINES = {0.0, 16.0, 32.0, 48.0};

    /**
     * <b>稳定网格的夹具上，边界恰好是 8 小节线</b>（这一条钉"只收 8 的倍数 + 落在小节线上"）。
     *
     * <p>夹具刻意"没有别的事发生"：RMS 平（不产生能量骤变）、中频带恒定（没有人声段）、
     * 低频只在每小节第 1 拍轻微强调（够 {@code barPhaseOf} 求出相位，但那一记起音太小、
     * 电平曲线的窗差跨不过 {@link AutoCue#STRUCTURE_JUMP_DIFF}）。
     * 于是四条来源里只剩"小节线"这一路，断言"恰好"才有意义。
     */
    @Test
    public void theBoundariesAreExactlyTheEightBarLinesOnASteadyGrid() {
        AutoCue.Envelope env = fixture(0.0, 0.0, 0.0, 0.5f, 0.4f);
        // 上界取 63 而不是 64：最后一条 8 小节线正好压在曲尾（64 s）上，
        // 那一条要不要收是边界约定问题，与"只收 8 的倍数"无关，所以避开它。
        double[] found = AutoCue.structureBoundaries(env, 0.0, 63.0, AutoCue.SIDE_OUTRO);
        System.out.println("[structure] 稳定网格 → " + describe(found));
        for (int i = 0; i < found.length; i++) {
            double off = distanceToBarLine(found[i]);
            assertTrue("边界 " + fmt(found[i]) + " s 没落在小节线上（离最近的小节线 "
                    + fmt(off) + " s；相位扫描的分辨率是 小节长/48 = "
                    + fmt(BAR_SECS / 48.0) + " s，所以容差取 0.06）", off <= 0.06);
        }
        assertEquals("稳定网格上应当只有 8 小节线这几条边界", EXPECTED_PHRASE_LINES.length,
                found.length);
        for (int i = 0; i < EXPECTED_PHRASE_LINES.length; i++) {
            assertEquals("第 " + (i + 1) + " 条 8 小节线不在边界里",
                    EXPECTED_PHRASE_LINES[i], found[i], 1e-9);
        }
    }

    /**
     * <b>带人声段与尾声的夹具上：边界数远少于均匀网格，而且候选集默认就走它们</b>。
     *
     * <p>同一段范围里，20 ms 均匀网格有 {@code (to−from)/0.02} 个点——那正是用户说的
     * "几百上千个主歌中间切一刀的点"。
     */
    @Test
    public void theBoundariesAreFarFewerThanTheUniformGridAndDriveTheCandidates() {
        AutoCue.Envelope env = fixture(19.0, 27.0, 48.0, 0.6f, 0.3f);
        double from = 16.0;
        double to = 46.0;
        double[] found = AutoCue.structureBoundaries(env, from, to, AutoCue.SIDE_OUTRO);
        double[] grid = uniformGrid(env, from, to);
        System.out.println("[structure] 范围 [" + fmt(from) + ", " + fmt(to) + "] 结构边界 "
                + found.length + " 个（均匀网格 " + grid.length + " 个）→ " + describe(found));
        assertTrue("结构边界太少了——这一条要的是「用它当候选」，实际 " + found.length + " 个",
                found.length >= AutoCue.MIN_STRUCTURE_CANDIDATES);
        assertTrue("结构边界应当远少于均匀网格（实际 " + found.length + " vs " + grid.length + "）",
                found.length * 50 < grid.length);
        for (int i = 0; i < found.length; i++) {
            assertTrue("第 " + i + " 个边界 " + fmt(found[i]) + " s 跑到范围外了",
                    found[i] >= from && found[i] <= to);
        }
        // 默认路径：候选集就是结构边界（**不是**网格），逐位相同
        double[] cand = AutoCue.candidates(env, from, to, AutoCue.SIDE_OUTRO);
        assertArrayEquals("结构边界够用时候选集必须原样就是它们", found, cand, 0.0);
    }

    /**
     * <b>人声活动段的起点与终点都是边界</b>（"有人开始唱 / 唱完"）。
     *
     * <p>合成的人声段故意<b>不落在 8 小节线上</b>（19.0 s 离最近的小节线 1 s、离 8 小节线 3 s）：
     * 于是这两条边界不可能是网格给的，只能是这一路来源。
     */
    @Test
    public void theStartsAndEndsOfVocalPhrasesAreBoundaries() {
        AutoCue.Envelope env = fixture(19.0, 27.0, 0.0, 0.6f, 0.3f);
        AutoCue.LyricSpans spans = AutoCue.vocalPhraseSpans(env);
        assertNotNull("夹具里的人声段没被 VAD 认出来，判据无从谈起", spans);
        assertEquals("夹具里只应当有一段人声", 1, spans.starts.length);
        assertTrue("夹具没搭好：人声段的起点正好落在 8 小节线上，这样就分不清边界来自哪一路",
                distanceToPhraseLine(spans.starts[0]) > 0.5);
        double[] found = AutoCue.structureBoundaries(env, 0.0, LEN_SECS, AutoCue.SIDE_OUTRO);
        System.out.println("[structure] 人声段 " + fmt(spans.starts[0]) + " → " + fmt(spans.ends[0])
                + " s；边界里有 " + describe(found));
        assertTrue("人声开始唱的位置（" + fmt(spans.starts[0]) + " s）不在边界里",
                nearest(found, spans.starts[0]) <= 0.1);
        assertTrue("人声唱完的位置（" + fmt(spans.ends[0]) + " s）不在边界里",
                nearest(found, spans.ends[0]) <= 0.1);
    }

    /**
     * <b>能量骤变点是边界</b>：没有拍栅格、没有分频数据也照样能用。
     *
     * <p>夹具是一记干净的台阶（RMS 0.2 → 0.9），没有任何别的来源——所以这里测到的那个边界
     * 只可能是"劲头曲线上的骤变"给的。
     */
    @Test
    public void anEnergyStepIsABoundaryEvenWithoutABeatGrid() {
        AutoCue.Envelope env = bareStep(20.0, 0.2f, 0.9f);
        double[] found = AutoCue.structureBoundaries(env, 10.0, 30.0, AutoCue.SIDE_OUTRO);
        System.out.println("[structure] 台阶（无拍栅格）→ " + describe(found));
        assertTrue("RMS 从 0.2 跳到 0.9 的地方必须有一条边界",
                nearest(found, 20.0) <= 0.2);
        assertTrue("没有拍栅格时这一路给不出多少点（好让 candidates 走兜底），实际 "
                + found.length + " 个", found.length < AutoCue.MIN_STRUCTURE_CANDIDATES);
    }

    /**
     * <b>尾奏起点是边界</b>：平滑衰减（没有台阶、也没有拍栅格）时，这一路是唯一的来源。
     *
     * <p>与 {@link AutoCue#outroStartSecs} 直接对账（它正是结构打分用的那个量，
     * 见 <code>STRUCTURE_WEIGHT</code>）：边界必须落在它身上，差不超过吸附容差。
     */
    @Test
    public void theOutroStartIsABoundary() {
        AutoCue.Envelope env = bareFade(40.0);
        double anchor = AutoCue.outroStartSecs(env);
        assertTrue("夹具没搭好：尾声起点没被测出来", anchor > 0.0);
        double[] found = AutoCue.structureBoundaries(env, 30.0, 64.0, AutoCue.SIDE_OUTRO);
        System.out.println("[structure] 平滑衰减（无拍栅格）→ 尾声起点 " + fmt(anchor)
                + " s；边界 " + describe(found));
        assertTrue("尾声起点处必须有边界（实际离它最近的边界差 "
                        + fmt(nearest(found, anchor)) + " s）",
                nearest(found, anchor) <= AutoCue.STRUCTURE_SNAP_TOL_SECS + 1e-9);
    }

    /**
     * <b>铺不开的结构边界同样退回网格</b>（个数够但空档太大）。
     *
     * <p>为什么"个数够"之外还要这一条：判据 {@code AutoCueIntensityBridgeTest} 的夹具
     * （全程响度一样、只有音色变过，VAD 在音色台阶上误报出两段 0.8 s 的"人声"）给出的正是
     * "5 个点铺在 140 秒里"——挑点于是退化成"没有选择"，引擎从"进在副歌起点"退到"进在副歌末尾"。
     * 这条夹具把那个形状（4 条边界、空档 45 秒）单独钉住。
     */
    @Test
    public void boundariesThatCannotCoverTheRangeFallBackToTheGrid() {
        double len = 150.0;
        double[] stepSecs = {5.0, 50.0, 55.0, 100.0};
        AutoCue.Envelope env = bareSteps(len, stepSecs);
        double from = 0.0;
        double to = len - 1.0;
        double[] found = AutoCue.structureBoundaries(env, from, to, AutoCue.SIDE_OUTRO);
        System.out.println("[structure] 4 条铺不开的边界 → " + describe(found));
        assertEquals("夹具搭得不对：这里要的正是 4 条边界（个数够，所以过得了个数那一关）",
                stepSecs.length, found.length);
        assertTrue("夹具搭得不对：最大空档 " + fmt(maxGap(found, from, to))
                        + " s 应当超过 STRUCTURE_MAX_GAP_SECS = "
                        + fmt(AutoCue.STRUCTURE_MAX_GAP_SECS),
                maxGap(found, from, to) > AutoCue.STRUCTURE_MAX_GAP_SECS);
        double[] grid = uniformGrid(env, from, to);
        double[] cand = AutoCue.candidates(env, from, to, AutoCue.SIDE_OUTRO);
        assertEquals("铺不开时必须退回 20 ms 网格", grid.length, cand.length);
        for (int i = 0; i < grid.length; i++) {
            assertEquals("第 " + i + " 个候选点与 20 ms 网格不同（这一条要求逐位相同）",
                    grid[i], cand[i], 0.0);
        }
    }

    /**
     * <b>结构边界不够 4 个 → 退回 20 ms 均匀网格，而且逐位相同</b>。
     *
     * <p>这就是仓库那条纪律的落点：平坦、无拍、无分频的素材（"什么都测不出来"）
     * 走的一定是今天这条路——同一串 {@code env.secs(first + i)}、同一个下标范围。
     */
    @Test
    public void tooFewBoundariesFallBackToTheUntouchedTwentyMillisGrid() {
        AutoCue.Envelope bare = new AutoCue.Envelope(new float[1000], new float[1000], 20.0);
        double[] found = AutoCue.structureBoundaries(bare, 2.0, 14.0, AutoCue.SIDE_OUTRO);
        assertEquals("什么都测不出来的素材上不该有结构边界", 0, found.length);

        double from = 2.0;
        double to = 14.0;
        double[] grid = uniformGrid(bare, from, to);
        double[] cand = AutoCue.candidates(bare, from, to, AutoCue.SIDE_OUTRO);
        System.out.println("[structure] 兜底：候选 " + cand.length + " 个（20 ms 网格），"
                + "首 " + fmt(cand[0]) + " s、末 " + fmt(cand[cand.length - 1]) + " s");
        assertEquals("兜底的候选数必须是 20 ms 网格的个数", grid.length, cand.length);
        for (int i = 0; i < grid.length; i++) {
            assertEquals("第 " + i + " 个候选点与 20 ms 网格不同（这一条要求逐位相同）",
                    grid[i], cand[i], 0.0);
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /**
     * 一条 {@value #LEN_SECS} 秒的合成包络，参数就是"哪一路来源要响"：
     *
     * @param vocalFrom 人声段的起点（秒；{@code <= 0} = 这段素材没有人声）
     * @param vocalTo   人声段的终点（秒）
     * @param dropSecs  尾声起点（秒；{@code <= 0} = 不衰减）
     * @param drumPeak  每小节第 1 拍的低频峰值（相对 {@code drumBase} 的一记鼓）
     * @param drumBase  其余位置的低频基线
     */
    private static AutoCue.Envelope fixture(double vocalFrom, double vocalTo, double dropSecs,
                                            float drumPeak, float drumBase) {
        int hops = (int) Math.round(LEN_SECS / AutoCue.HOP_SECS);
        int barHops = (int) Math.round(BAR_SECS / AutoCue.HOP_SECS);
        float[] rms = new float[hops];
        float[] onset = new float[hops];
        float[][] band = new float[hops][];
        for (int i = 0; i < hops; i++) {
            double secs = i * AutoCue.HOP_SECS;
            rms[i] = dropSecs > 0.0 && secs >= dropSecs ? 0.25f : 0.9f;
            float low = i % barHops == 0 ? drumPeak : drumBase;
            boolean singing = vocalFrom > 0.0 && secs >= vocalFrom && secs < vocalTo;
            // 中频按音节起伏（隔一格 0.2 / 0.6）：VAD 要的正是"在动"，见 voiceActiveMask
            float mid = singing ? (i % 2 == 0 ? 0.2f : 0.6f) : 0.4f;
            band[i] = new float[] {low, mid, 0.4f};
            // 包络的 onset 跟着低频走：barPhaseOf 扫相位读的就是它（与真实扫描同一个关系）
            onset[i] = i == 0 ? 0f : Math.max(0f, band[i][0] - band[i - 1][0]);
        }
        return new AutoCue.Envelope(rms, onset, LEN_SECS, beatGrid(),
                AutoCue.TARGET_ONSET_RATE, band, null, 0);
    }

    /** 没有拍栅格、没有分频数据的台阶：{@code stepSecs} 之前 {@code low}、之后 {@code high}。 */
    private static AutoCue.Envelope bareStep(double stepSecs, float low, float high) {
        int hops = (int) Math.round(LEN_SECS / AutoCue.HOP_SECS);
        float[] rms = new float[hops];
        float[] onset = new float[hops];
        for (int i = 0; i < hops; i++) {
            double secs = i * AutoCue.HOP_SECS;
            rms[i] = secs >= stepSecs ? high : low;
            onset[i] = i == 0 ? 0f : Math.max(0f, rms[i] - rms[i - 1]);
        }
        return new AutoCue.Envelope(rms, onset, LEN_SECS);
    }

    /**
     * 多次台阶（电平在 0.3 / 0.8 之间来回），没有拍栅格也没有分频数据：
     * 用来造"边界个数够、但铺不开"的那种形状。
     */
    private static AutoCue.Envelope bareSteps(double lenSecs, double[] stepSecs) {
        int hops = (int) Math.round(lenSecs / AutoCue.HOP_SECS);
        float[] rms = new float[hops];
        float[] onset = new float[hops];
        for (int i = 0; i < hops; i++) {
            double secs = i * AutoCue.HOP_SECS;
            int level = 0;
            for (int s = 0; s < stepSecs.length; s++) {
                if (secs >= stepSecs[s]) {
                    level = s + 1;
                }
            }
            rms[i] = level % 2 == 0 ? 0.3f : 0.8f;
            onset[i] = i == 0 ? 0f : Math.max(0f, rms[i] - rms[i - 1]);
        }
        return new AutoCue.Envelope(rms, onset, lenSecs);
    }

    /**
     * 没有拍栅格、没有分频数据的<b>平滑衰减</b>：{@code fadeSecs} 之前满电平，
     * 之后在 12 秒里线性收到四分之一，再平着走到结尾。
     *
     * <p>线性是刻意的：一次台阶会在劲头曲线上留一记骤变，而"尾声起点"这一路要单独被验到，
     * 所以这里不能有台阶（缓变跨不过 {@link AutoCue#STRUCTURE_JUMP_DIFF}）。
     */
    private static AutoCue.Envelope bareFade(double fadeSecs) {
        int hops = (int) Math.round(LEN_SECS / AutoCue.HOP_SECS);
        double span = 12.0;
        float[] rms = new float[hops];
        float[] onset = new float[hops];
        for (int i = 0; i < hops; i++) {
            double secs = i * AutoCue.HOP_SECS;
            double v = 0.9;
            if (secs >= fadeSecs) {
                double k = Math.min(1.0, (secs - fadeSecs) / span);
                v = 0.9 - 0.65 * k;
            }
            rms[i] = (float) v;
            onset[i] = i == 0 ? 0f : Math.max(0f, rms[i] - rms[i - 1]);
        }
        return new AutoCue.Envelope(rms, onset, LEN_SECS);
    }

    /** 120 BPM 的合成拍栅格（置信度拉满，好让 {@code hasBeatGrid()} 成立）。 */
    private static Analysis.Tempo beatGrid() {
        double periodFrames = 60.0 / BPM * AutoCue.TARGET_ONSET_RATE;
        return new Analysis.Tempo((float) BPM, (float) periodFrames, 0.0f, 0.95f);
    }

    // ------------------------------------------------------------------
    // 对照与打印
    // ------------------------------------------------------------------

    /** 改这一行之前那条实现（原样抄下来当对照：兜底要与之**逐位相同**）。 */
    private static double[] uniformGrid(AutoCue.Envelope env, double fromSecs, double toSecs) {
        int first = env.indexAt(fromSecs);
        int last = env.indexAt(toSecs);
        if (last < first) {
            return new double[0];
        }
        int count = Math.min(last - first + 1, 20_000);
        double[] times = new double[count];
        for (int i = 0; i < count; i++) {
            times[i] = env.secs(first + i);
        }
        return times;
    }

    /** 到最近一条小节线的距离（秒）。 */
    private static double distanceToBarLine(double secs) {
        double k = secs / BAR_SECS;
        return Math.abs(secs - Math.round(k) * BAR_SECS);
    }

    /** 到最近一条 8 小节线的距离（秒）。 */
    private static double distanceToPhraseLine(double secs) {
        double k = secs / PHRASE_SECS;
        return Math.abs(secs - Math.round(k) * PHRASE_SECS);
    }

    /** {@code values} 里离 {@code target} 最近的那个点的距离（空数组算无穷远）。 */
    private static double nearest(double[] values, double target) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            best = Math.min(best, Math.abs(values[i] - target));
        }
        return best;
    }

    /** 一串候选点里最大的空档（含范围两端），与 {@code AutoCue.maxGapSecs} 同一个口径。 */
    private static double maxGap(double[] times, double from, double to) {
        if (times.length == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double best = 0.0;
        double prev = from;
        for (int i = 0; i < times.length; i++) {
            best = Math.max(best, times[i] - prev);
            prev = times[i];
        }
        return Math.max(best, to - prev);
    }

    private static String describe(double[] values) {
        StringBuilder b = new StringBuilder(values.length + " 个 [");
        for (int i = 0; i < values.length; i++) {
            b.append(i == 0 ? "" : ", ").append(fmt(values[i]));
        }
        return b.append(']').toString();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", Double.valueOf(v));
    }
}
