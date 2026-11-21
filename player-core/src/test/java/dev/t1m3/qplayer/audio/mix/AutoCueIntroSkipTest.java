package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>跳过进场曲的冗余开头段</b>——判据。
 *
 * <p>用户 2026-10-05 的原话：「很多歌的 intro 是 30 秒纯鼓点 + 1 分钟铺垫，真正该进的地方是
 * 第一个副歌前 8 小节。Apple 的做法是：允许从 intro 里的任意结构边界进入，跳过前面的冗余段」。
 *
 * <h3>这一条钉的是什么</h3>
 *
 * <p>候选点那一半已经就位（{@link AutoCue#structureBoundaries}：8 小节线 / 结构地标 /
 * 人声活动段起止 / 能量骤变四路，见 {@code AutoCueStructureBoundaryTest}）。
 * 这一条钉的是<b>范围</b>：结构锚点曾经把进场范围收成一个十几秒的窄带
 * （{@link AutoCue#ENTRY_ANCHOR_BACK_SECS} / {@link AutoCue#ENTRY_ANCHOR_AHEAD_SECS}），
 * 于是"副歌前 8 小节"那种<b>离锚点很远</b>的结构边界连候选都不是——
 * 锚点本该是**偏好**（打分项 {@code STRUCTURE_INTRO_SCALE}），不是**天花板**。
 *
 * <p>夹具按用户那句话搭：B 有「很长又冗余的开头」（0~30 s 安静前奏 + 30~80 s 铺垫），
 * 第一个副歌落在 96 s（8 小节线），所以**该进的地方是 80 s**（副歌前 8 小节 = 16 s，
 * 也是一条 8 小节线）。120 BPM 下：一小节 2 s、8 小节 16 s，8 小节线在 16 的倍数上。
 *
 * <p>三件事各自有断言：
 *
 * <ol>
 *   <li><b>够得到</b>：放宽后的候选范围里真的有 80 s 那条结构边界
 *       （直接对着 {@link AutoCue#structureBoundaries} 断言，与打分无关）；</li>
 *   <li><b>真的选到了</b>：{@link AutoCue.Bridge#DEFAULT}（放宽那一档）把进场点选在副歌前
 *       那条边界上——<b>红-前</b>：同一对夹具用 {@code Bridge.DEFAULT.noWiden()}
 *       （只关掉放宽）时够不到那里，断言当场红，见 {@code theWideningIsWhatMakesItReachable};</li>
 *   <li><b>不放宽那条路逐位不变</b>：沿用 {@code AutoCueIntensityBridgeTest} 那条口径，
 *       关掉桥接的结论与五参旧入口逐位相同。</li>
 * </ol>
 */
public class AutoCueIntroSkipTest {

    private static final double HOP = AutoCue.HOP_SECS;

    /** 120 BPM：一小节 2 s（4/4）、8 小节 16 s——用户说的"8 小节"就是 16 s。 */
    private static final double BPM = 120.0;
    private static final double BAR_SECS = 4.0 * 60.0 / BPM;
    private static final double PHRASE_SECS = 8.0 * BAR_SECS;

    /** 进场曲长度：240 s（好让「留 60 秒」那条线允许进到 84 s，副歌前那条边界 80 s 够得到）。 */
    private static final double INTRO_SECS = 240.0;

    /** 冗余开头段的终点（= B 的"真正开始"，也是铺垫段的起点）。 */
    private static final double REDUNDANT_HEAD_SECS = 30.0;

    /** 副歌前抬升的起点 = **该进的地方**（副歌前 8 小节 = 16 s）。 */
    private static final double PRE_CHORUS_SECS = 80.0;

    /** 第一个副歌的起点（8 小节线：96 = 16 × 6）。 */
    private static final double CHORUS_SECS = 96.0;

    private static final double LEVEL_INTRO = 0.35;
    private static final double LEVEL_BUILD = 0.55;
    private static final double LEVEL_PRE = 0.70;
    private static final double LEVEL_CHORUS = 0.85;

    // ------------------------------------------------------------------
    // ① 够得到：宽范围里真的有「副歌前 8 小节」那条结构边界
    // ------------------------------------------------------------------

    /**
     * <b>放宽之后，副歌前 8 小节那条边界进得了候选集</b>。
     *
     * <p>这一条与打分无关，只算范围：进场曲的"真正开始"（{@link AutoCue#introStartSecs}）
     * 落在 30 s 那一带，而「副歌前 8 小节」是 80 s——两者差 50 s，
     * 远超过锚点带宽（−{@link AutoCue#ENTRY_ANCHOR_BACK_SECS} /
     * +{@link AutoCue#ENTRY_ANCHOR_AHEAD_SECS}）。所以能不能够到，
     * 完全取决于范围有没有被锚点收窄。
     */
    @Test
    public void thePreChorusBoundaryIsReachableOnceTheRangeIsWidened() {
        AutoCue.Envelope intro = introWithRedundantHead();
        double structure = AutoCue.introStartSecs(intro);
        double floor = Math.max(0.0, structure - PHRASE_SECS);
        double ceil = AutoCue.Bridge.DEFAULT.entryCeil(INTRO_SECS);
        double[] found = AutoCue.structureBoundaries(intro, floor, ceil, AutoCue.SIDE_INTRO);
        System.out.println(String.format(Locale.ROOT,
                "[intro-skip] B 的「真正开始」%.2f s；放宽后的候选范围 [%.2f, %.2f] → 结构边界 %s",
                Double.valueOf(structure), Double.valueOf(floor), Double.valueOf(ceil),
                describe(found)));

        // 平滑窗（{@code STRUCTURE_SMOOTH_SECS}）会把这一格往后挪一点，所以容差给 3 s：
        // 夹具要保证的只是"真正开始"落在**冗余段的末尾那一带**，而不是精确的 30.00
        assertTrue("夹具没搭好：B 的「真正开始」应当落在冗余开头段的末尾（" + REDUNDANT_HEAD_SECS
                + "~34 s 那一带），实际 " + fmt(structure),
                structure >= REDUNDANT_HEAD_SECS && structure <= REDUNDANT_HEAD_SECS + 4.0);
        assertTrue("副歌前 8 小节那条边界（" + fmt(PRE_CHORUS_SECS)
                        + " s）必须在候选范围里，离它最近的边界差 " + fmt(nearest(found, PRE_CHORUS_SECS))
                        + " s",
                nearest(found, PRE_CHORUS_SECS) <= AutoCue.STRUCTURE_SNAP_TOL_SECS + 1e-9);
        // 而且这条边界是**真的结构边界**（落在 8 小节线上），不是随便一个点
        assertTrue("该进的地方本身就是一条 8 小节线（" + fmt(PRE_CHORUS_SECS) + " s = 16 × 5）",
                distanceToPhraseLine(PRE_CHORUS_SECS) < 1e-9);
        assertTrue("候选集应当够密（≥ " + AutoCue.MIN_STRUCTURE_CANDIDATES + " 个），实际 "
                + found.length, found.length >= AutoCue.MIN_STRUCTURE_CANDIDATES);
    }

    // ------------------------------------------------------------------
    // ② 真的选到了：跳过冗余开头、从副歌前的结构边界进入
    // ------------------------------------------------------------------

    /**
     * <b>引擎真的把进场点选在「副歌前 8 小节」那条结构边界上</b>——
     * 也就是"跳过 intro，直接从主歌进"。
     *
     * <p>为什么用 {@link AutoCue.Bridge#DEFAULT}（第二级 / 放宽那一档）：放宽只在这一档生效
     * （第一级必须与加桥接之前逐位相同，位指纹钉着）。而这一档在真实引擎里是**自动触发**的
     * ——第一级（浅范围）挑出来的接缝强度差超过 {@link AutoCue#INTENSITY_GOOD_ENOUGH_GAP}
     * 时就会跑第二级；这条夹具里 B 的前 80 秒是温和的铺垫、A 的接缝在高潮里，
     * 强度差本来就接不上（见 {@code theRedundantHeadIsWhatTheFirstStageCannotMatch}）。
     */
    @Test
    public void theEngineSkipsTheRedundantHeadAndEntersAtThePreChorusBoundary() {
        AutoCue.Envelope outro = hypeSeamOutro();
        AutoCue.Envelope intro = introWithRedundantHead();
        double[] windows = {4.0, 6.0, 8.0};

        AutoCue.Cue cue = AutoCue.plan(outro, intro, windows, null, null, AutoCue.Bridge.DEFAULT);
        assertNotNull("放宽那一档必须给出结论", cue);
        System.out.println(String.format(Locale.ROOT,
                "[intro-skip] 放宽后：出场 %.2f / 进场 %.2f / 窗口 %.2f · %s",
                Double.valueOf(cue.outroCutSecs), Double.valueOf(cue.introEntrySecs),
                Double.valueOf(cue.windowSecs), cue.note));

        assertTrue("进场点必须跳过那段冗余开头（≥ " + fmt(PRE_CHORUS_SECS)
                        + " s，实际 " + fmt(cue.introEntrySecs) + " s）——"
                        + "它现在的意思就是「B 从第几秒开始播」"
                        + "（TransitionPlan.skipSecs，见 Cue.introEntrySecs 的注释）",
                cue.introEntrySecs >= PRE_CHORUS_SECS - AutoCue.STRUCTURE_SNAP_TOL_SECS);
        assertTrue("而且不许跳到副歌后面去（入场要落在「副歌前 8 小节」那一条边界上，"
                        + "而不是骑到副歌中间）：实际 " + fmt(cue.introEntrySecs) + " s",
                cue.introEntrySecs <= CHORUS_SECS + 1e-9);
        assertTrue("落点必须是一条 8 小节线（结构边界，不是网格点）：离最近那条差 "
                        + fmt(distanceToPhraseLine(cue.introEntrySecs)) + " s",
                distanceToPhraseLine(cue.introEntrySecs) <= AutoCue.STRUCTURE_SNAP_TOL_SECS + 1e-9);
    }

    /**
     * <b>红-前那一半：只关掉"放宽"，同一对夹具就够不到那条边界。</b>
     *
     * <p>{@link AutoCue.Bridge#noWiden()} 与 {@link AutoCue.Bridge#DEFAULT} 只差
     * {@code widen} 这一个开关（其余量、评、否决全同），所以这一条把
     * 「是放宽把这条边界变成可选的」这件事单独钉住——没有它，
     * {@code theEngineSkipsTheRedundantHeadAndEntersAtThePreChorusBoundary} 可能
     * 在别的机缘下变绿，判据就失去了红-前的意义。
     */
    @Test
    public void theWideningIsWhatMakesItReachable() {
        AutoCue.Envelope outro = hypeSeamOutro();
        AutoCue.Envelope intro = introWithRedundantHead();
        double[] windows = {4.0, 6.0, 8.0};

        AutoCue.Cue narrow = AutoCue.plan(outro, intro, windows, null, null,
                AutoCue.Bridge.DEFAULT.noWiden());
        assertNotNull("不放宽那一档也要给出结论", narrow);
        System.out.println(String.format(Locale.ROOT,
                "[intro-skip] 只关掉放宽：出场 %.2f / 进场 %.2f / 窗口 %.2f",
                Double.valueOf(narrow.outroCutSecs), Double.valueOf(narrow.introEntrySecs),
                Double.valueOf(narrow.windowSecs)));

        assertTrue("不放宽时进场范围还是「min(20 s, 25%)」那一档，够不到副歌前的边界"
                        + "（实际 " + fmt(narrow.introEntrySecs) + " s）——"
                        + "这一条就是把「红-前」写进判据里",
                narrow.introEntrySecs
                        < PRE_CHORUS_SECS - AutoCue.STRUCTURE_SNAP_TOL_SECS);
    }

    /**
     * <b>第一级（浅范围）确实接不上——所以第二级会自动跑。</b>
     *
     * <p>没有这一条，"放宽那一档选对了"可能是靠别的机缘；有了它，"真实引擎会自己走到
     * 那条深范围路径上"才是被证明的（触发条件是
     * {@link AutoCue#INTENSITY_GOOD_ENOUGH_GAP}）。
     */
    @Test
    public void theRedundantHeadIsWhatTheFirstStageCannotMatch() {
        AutoCue.Envelope outro = hypeSeamOutro();
        AutoCue.Envelope intro = introWithRedundantHead();
        double[] windows = {4.0, 6.0, 8.0};

        AutoCue.Cue stage1 = AutoCue.plan(outro, intro, windows, null, null, AutoCue.Bridge.MEASURE);
        assertNotNull(stage1);
        System.out.println(String.format(Locale.ROOT,
                "[intro-skip] 第一级：进场 %.2f s，强度差 %.4f（够好的线 %.4f）",
                Double.valueOf(stage1.introEntrySecs), Double.valueOf(stage1.intensityGap),
                Double.valueOf(AutoCue.INTENSITY_GOOD_ENOUGH_GAP)));
        assertTrue("第一级必须如实报出「接不上」（差 " + fmt4(stage1.intensityGap) + " > 线 "
                        + fmt4(AutoCue.INTENSITY_GOOD_ENOUGH_GAP) + "）——"
                        + "不够好才会去跑放宽那一档",
                stage1.intensityGap > AutoCue.INTENSITY_GOOD_ENOUGH_GAP);
    }

    // ------------------------------------------------------------------
    // ③ 不放宽那条路逐位不变
    // ------------------------------------------------------------------

    /** 关掉桥接 = 五参旧入口，逐位相同（沿用 {@code AutoCueIntensityBridgeTest} 的口径）。 */
    @Test
    public void theOffPathIsBitIdenticalToTheFiveArgEntryPoint() {
        AutoCue.Envelope outro = hypeSeamOutro();
        AutoCue.Envelope intro = introWithRedundantHead();
        double[] windows = {4.0, 6.0, 8.0};

        AutoCue.Cue today = AutoCue.plan(outro, intro, windows);
        AutoCue.Cue off = AutoCue.plan(outro, intro, windows, null, null, AutoCue.Bridge.OFF);

        assertEquals("出场点", today.outroCutSecs, off.outroCutSecs, 0.0);
        assertEquals("进场点", today.introEntrySecs, off.introEntrySecs, 0.0);
        assertEquals("窗口", today.windowSecs, off.windowSecs, 0.0);
        assertEquals("代价", today.cost, off.cost, 0.0);
        assertEquals("那句话", today.note, off.note);
    }

    /**
     * <b>放宽后的两条界线各是什么：下界在冗余段的边缘，上界不再被锚点封顶。</b>
     *
     * <p>这条把这次改动的算术单独钉住（免得它只以"某个夹具上选到了 80 s"的形式存在）：
     *
     * <ul>
     *   <li>下界 = {@code max(0, introStructure − INTRO_SKIP_FLOOR_SECS)}——用户给的算术原文。
     *       它<b>大于 0</b>，也就是"纯鼓点那一段"（0~30 s）已经不在可选范围里；</li>
     *   <li>上界 = 策略给的（{@code Bridge.entryCeil}），<b>远在锚点带之外</b>——
     *       按老做法它会被夹到 {@code target + ENTRY_ANCHOR_AHEAD_SECS ≈ introStructure + 2.5 s}，
     *       连"副歌前 8 小节"（80 s）都够不到。</li>
     * </ul>
     */
    @Test
    public void theFloorSitsAtTheRedundantHeadsEdgeAndTheAnchorNoLongerCapsTheCeiling() {
        AutoCue.Envelope intro = introWithRedundantHead();
        double structure = AutoCue.introStartSecs(intro);
        double floor = Math.max(0.0, structure - AutoCue.INTRO_SKIP_FLOOR_SECS);
        double ceil = AutoCue.Bridge.DEFAULT.entryCeil(INTRO_SECS);
        double oldAnchorCeil = structure + AutoCue.ENTRY_ANCHOR_AHEAD_SECS;
        System.out.println(String.format(Locale.ROOT,
                "[intro-skip] 下界 %.2f s（老锚点带的下沿是 %.2f s）· 上界 %.2f s"
                        + "（老锚点带的上沿是 %.2f s）",
                Double.valueOf(floor), Double.valueOf(structure - AutoCue.ENTRY_ANCHOR_BACK_SECS),
                Double.valueOf(ceil), Double.valueOf(oldAnchorCeil)));

        assertTrue("下界必须真的把冗余开头段挡在外面（> 0，它是 " + fmt(floor) + " s）", floor > 0.0);
        assertTrue("下界必须早于「该进的地方」，否则跳过头了：" + fmt(floor) + " s",
                floor < PRE_CHORUS_SECS);
        assertTrue("上界必须是**策略**给的、且远在锚点带之外（老做法的上沿是 "
                        + fmt(oldAnchorCeil) + " s，够不到 " + fmt(PRE_CHORUS_SECS) + " s）",
                ceil > oldAnchorCeil);
        assertTrue("上界要够得着「副歌前 8 小节」那条边界：" + fmt(ceil) + " s",
                ceil >= PRE_CHORUS_SECS);
        // 策略上界本身仍然守着"不能是 outro"这条老要求（不因为这次放宽而变）
        assertTrue("放宽上界不等于放开尾声：还是要给 B 留 "
                        + fmt(AutoCue.ENTRY_OUTRO_FLOOR_SECS) + " s",
                ceil <= INTRO_SECS - AutoCue.ENTRY_OUTRO_FLOOR_SECS + 1e-9);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /**
     * <b>B：很长又冗余的开头 + 明显的第一个副歌</b>（用户那句话的形状）。
     *
     * <pre>
     *   0 ~ 30 s   安静前奏（纯鼓点那种：有拍、但温和、没劲）  ← 冗余段
     *  30 ~ 80 s   铺垫（更响一点，但还是温和）              ← 还是冗余段
     *  80 ~ 96 s   副歌前的抬升（激进 + 起音密）              ← **该进的地方**
     *  96 ~ 240 s  第一个副歌（8 小节线 96 上）
     * </pre>
     *
     * <p>低频每小节第 1 拍一记鼓（{@code barPhaseOf} 就是靠这个定相位的），
     * 所以 8 小节线落在 0 / 16 / 32 / … 上。
     */
    static AutoCue.Envelope introWithRedundantHead() {
        int hops = (int) Math.round(INTRO_SECS / HOP);
        int barHops = (int) Math.round(BAR_SECS / HOP);
        float[] rms = new float[hops];
        float[] onset = new float[hops];
        float[][] band = new float[hops][];
        for (int i = 0; i < hops; i++) {
            double t = i * HOP;
            double level;
            boolean fierce;
            if (t >= CHORUS_SECS) {
                level = LEVEL_CHORUS;
                fierce = true;
            } else if (t >= PRE_CHORUS_SECS) {
                level = LEVEL_PRE;
                fierce = true;
            } else if (t >= REDUNDANT_HEAD_SECS) {
                level = LEVEL_BUILD;
                fierce = false;
            } else {
                level = LEVEL_INTRO;
                fierce = false;
            }
            // 起音密（"带感"）只在副歌那一带：这是"劲头"与"响度"分开的那把尺子
            rms[i] = (float) (level * (fierce ? (i % 2 == 0 ? 1.15 : 0.75) : 1.0));
            float low = i % barHops == 0 ? 0.6f : 0.4f;
            band[i] = fierce
                    ? new float[] {low, 0.10f, 0.45f}       // V 型：两端厚、中频薄
                    : new float[] {low, 0.75f, 0.10f};      // 温和：中频厚
            onset[i] = i == 0 ? 0f : Math.max(0f, band[i][0] - band[i - 1][0]);
        }
        return new AutoCue.Envelope(rms, onset, INTRO_SECS, beatGrid(),
                AutoCue.TARGET_ONSET_RATE, band, null, 0);
    }

    /**
     * <b>A：整首都在高潮里——接缝无论落在哪都是"激进带感"的</b>。
     *
     * <p>形状与 {@code AutoCueIntensityBridgeTest.outroAllHype} 同一个用意（响度全程恒定、
     * 只有"劲头"满格）：这条判据要测的是**进场侧**能不能跳过冗余开头，
     * 所以出场侧不能自己变成变量——接缝落在哪一带都得是同一个强度，
     * 否则"选到哪一段"就成了"哪一段跟 A 那个偶然的接缝更像"。
     *
     * <p>试过把高潮放在尾巴上（70~90 s，与 {@code outroWithHypeSeam} 同形）：那时引擎把
     * 出场点选在 61.7 s（**高潮之前**，因为"最后 15%"那条硬约束在这一对夹具上无法
     * 同时满足），于是 A 的接缝是温和的，B 的冗余开头反而成了"更像"的那一段——
     * 夹具自己把结论带偏了。整首高潮就消掉了这个变量。
     */
    static AutoCue.Envelope hypeSeamOutro() {
        return outroEnvelope(90.0);
    }

    private static AutoCue.Envelope outroEnvelope(double secs) {
        int hops = (int) Math.round(secs / HOP);
        float[] rms = new float[hops];
        float[][] band = new float[hops][];
        final double level = 0.55;
        for (int i = 0; i < hops; i++) {
            rms[i] = (float) Math.max(0.0, Math.min(1.0, level * (i % 2 == 0 ? 1.2 : 0.7)));
            band[i] = new float[] {0.45f, 0.10f, 0.45f};     // V 型 + 中频薄 = "带感"
        }
        return AutoCue.envelopeOf(rms, secs, null, 0, band, null, 0);
    }

    /** 120 BPM 的合成拍栅格（置信度拉满，好让 {@code hasBeatGrid()} 成立）。 */
    private static Analysis.Tempo beatGrid() {
        double periodFrames = 60.0 / BPM * AutoCue.TARGET_ONSET_RATE;
        return new Analysis.Tempo((float) BPM, (float) periodFrames, 0.0f, 0.95f);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static double distanceToPhraseLine(double secs) {
        double k = secs / PHRASE_SECS;
        return Math.abs(secs - Math.round(k) * PHRASE_SECS);
    }

    private static double nearest(double[] values, double target) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            best = Math.min(best, Math.abs(values[i] - target));
        }
        return best;
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

    private static String fmt4(double v) {
        return String.format(Locale.ROOT, "%.4f", Double.valueOf(v));
    }
}
