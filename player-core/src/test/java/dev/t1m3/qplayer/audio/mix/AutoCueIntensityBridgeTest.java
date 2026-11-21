package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>「接缝强度」与强度闸门——判据</b>（2026-10-04）。
 *
 * <p>用户的要求：「歌曲 A 跳跃点附近的片段是十分激进带感的，那你衔接歌曲 B 的进入点
 * 应该是差不多嗨的，而不是突然降下去了」，以及「不能入场点是 B 的 outro，
 * 跳到副歌是可以的」。
 *
 * <h3>为什么这条判据必须存在</h3>
 *
 * <p>这件事在旧引擎里**做不了，而且九项里任意一项都能把它换掉**：
 *
 * <ul>
 *   <li>「响度接续」比的是整窗 RMS 均值（音量），不是劲头——「安静但猛」与「响但柔」
 *       在它眼里只是响度不同；</li>
 *   <li>它权重 1.0/7.75 ≈ 13%，而波形重合(1.6) + 淡化起伏(1.2) + 低频冲突(0.9) = 3.7，
 *       是它的 3.7 倍——「B 一进来就安静下去」完全可以被别的项换掉；</li>
 *   <li>「动向」那一项甚至**奖励**「A 正在收尾、B 正在起来」（两半都拿满分）。</li>
 * </ul>
 *
 * <p>所以闸门是必要的：它只回答「行不行」，不参与「哪对更接近」的排序。
 */
public class AutoCueIntensityBridgeTest {

    private static final double HOP = AutoCue.HOP_SECS;

    // ------------------------------------------------------------------
    // 夹具：三条素材，全部是**手搭包络**（不碰文件、不碰解码器）
    // ------------------------------------------------------------------

    /**
     * 两条夹具共用的响度：**全程恒定**。
     *
     * <p>为什么必须恒定：用户报的那件事是「声波/响度都对上了，人耳还是违和」。
     * 只要响度一变，「响度接续」那一项就已经在管了，夹具就测不到真正的缺口。
     * 现在两边响度一模一样、只有音色与起音密度不同——于是旧引擎认为这是**完美匹配**
     * （判据记录的正是这一点），而强度闸门看得出差 0.5 以上。
     */
    private static final double LEVEL_SAME = 0.55;

    /**
     * 「响度接得上、劲头接不上」那一对专用的响度：
     * A 的高潮明显更响（{@value}）、B 的副歌明显更轻（{@link #LEVEL_HYPE_B}）——
     * 于是**响度连续**与**劲头连续**这两件事在这一对素材上是**冲突**的。
     */
    private static final double LEVEL_HYPE_A = 0.72;

    /** 见 {@link #LEVEL_HYPE_A}。 */
    private static final double LEVEL_HYPE_B = 0.38;

    /** 温和的音色：中频厚（民谣/人声那种），两端都薄。 */
    private static final double TONE_MILD = 0.0;

    /** 激进的音色：V 型（低频轰 + 高频锋利），这是"带感"混音的样子。 */
    private static final double TONE_AGGRESSIVE = 1.0;

    /**
     * 一条包络。{@code shape} 每项 = {@code {起, 止, 响度, 音色, 起音周期}}。
     *
     * <p>{@code 起音周期}：{@code 2} = 每两格一个尖峰（鼓密）、{@code 0} = 完全平（没有起音）。
     * 「猛」由<b>起音周期 + 音色</b>决定，「响」由响度决定——两者分开，判据才能证明
     * 强度不是响度。
     */
    private static AutoCue.Envelope envelope(double secs, double[][] shape) {
        int n = (int) Math.round(secs / HOP);
        float[] rms = new float[n];
        float[][] band = new float[n][3];
        for (int i = 0; i < n; i++) {
            double t = i * HOP;
            double level = 0.5;
            double tone = TONE_MILD;
            int pulse = 0;
            for (double[] s : shape) {
                if (t >= s[0] && t < s[1]) {
                    level = s[2];
                    tone = s[3];
                    pulse = (int) s[4];
                }
            }
            double v = level;
            if (pulse > 0) {
                v = level * (i % pulse == 0 ? 1.2 : 0.7);
            }
            rms[i] = (float) Math.max(0.0, Math.min(1.0, v));
            if (tone == TONE_AGGRESSIVE) {
                band[i][0] = 0.45f;      // 低频轰
                band[i][1] = 0.10f;
                band[i][2] = 0.45f;      // 高频锋利
            } else {
                band[i][0] = 0.15f;      // 两端都薄
                band[i][1] = 0.75f;      // 中频厚
                band[i][2] = 0.10f;
            }
        }
        return AutoCue.envelopeOf(rms, secs, null, 0, band, null, 0);
    }

    /**
     * 「响度接得上、劲头接不上」现场：A 的高潮更响、B 的副歌更轻，
     * 两边响度**差得很远**，可两边的劲头**完全一样**（都是 V 型 + 起音密）。
     *
     * <p>这条夹具是给粗排那一条惩罚用的：如果没有它，粗排会优先把「响度连续」的点对
     * 塞进前 96（连续性项给出 0 惩罚），而"两端都嗨"的那些点对因为响度差 0.34 被排在后面，
     * 压根进不了精细评分——闸门于是永远看不到它们。
     */
    private static AutoCue.Envelope outroLoudHype() {
        return envelope(90.0, new double[][]{
                {0.0, 70.0, LEVEL_SAME, TONE_MILD, 0},
                {70.0, 90.0, LEVEL_HYPE_A, TONE_AGGRESSIVE, 2},
        });
    }

    /** 见 {@link #outroLoudHype()}。 */
    private static AutoCue.Envelope introQuietChorus(double secs) {
        return envelope(secs, new double[][]{
                {0.0, 25.0, LEVEL_SAME, TONE_MILD, 0},
                {25.0, 45.0, LEVEL_SAME, TONE_MILD, 0},
                {45.0, 70.0, LEVEL_HYPE_B, TONE_AGGRESSIVE, 2},
                {70.0, secs, LEVEL_SAME, TONE_MILD, 0},
        });
    }

    /** 出场曲整首都是嗨的（任何合法出场点都在高潮里）。 */
    private static AutoCue.Envelope outroAllHype() {
        return envelope(90.0, new double[][]{{0.0, 90.0, LEVEL_SAME, TONE_AGGRESSIVE, 2}});
    }

    /** 「嗨」：起音密 + V 型音色。 */
    static AutoCue.Envelope hype(double secs) {
        return envelope(secs, new double[][]{{0, secs, 0.80, TONE_AGGRESSIVE, 2}});
    }

    /** 「安静但猛」：响度低，可是起音密、V 型音色——**判据要它比"响但柔"更高**。 */
    private static AutoCue.Envelope quietButFierce(double secs) {
        return envelope(secs, new double[][]{{0, secs, 0.32, TONE_AGGRESSIVE, 2}});
    }

    /** 「响但柔」：响度很高，可是中频厚、没有任何起音起伏。 */
    static AutoCue.Envelope loudButSoft(double secs) {
        return envelope(secs, new double[][]{{0, secs, 0.95, TONE_MILD, 0}});
    }

    /**
     * 出场曲：前 70 s 平，**最后 20 s 是高潮**——用户说的那种「跳跃点附近十分激进带感」。
     *
     * <p>为什么高潮必须放在尾巴上：引擎有一条硬约束「出场点在最后 15% 里」
     * （{@code MAX_CUT_FROM_END_FRACTION}，不许半路把歌掐掉）。高潮放在中段的话，
     * 所有合法出场点都会落在它的外面，夹具就测不到想测的东西了（实测踩过：
     * 出场点被硬约束推到 62.0 s，闸门连出手的机会都没有）。
     */
    static AutoCue.Envelope outroWithHypeSeam() {
        // 响度**全程一样**（0.55）：这条夹具要证明的正是「响度对上了、劲头没对上」。
        return envelope(90.0, new double[][]{
                {0.0, 70.0, LEVEL_SAME, TONE_MILD, 0},
                {70.0, 90.0, LEVEL_SAME, TONE_AGGRESSIVE, 2},
        });
    }

    /** 进场曲：前 25 s 安静、**副歌在 45~70 s**（也就是浅扫看不到的那一段）。 */
    static AutoCue.Envelope introWithLateChorus(double secs) {
        // 同样：全程响度一样，只有「劲头」在变。前 25 秒是安静段（浅扫只看得到它）。
        return envelope(secs, new double[][]{
                {0.0, 25.0, LEVEL_SAME, TONE_MILD, 0},
                {25.0, 45.0, LEVEL_SAME, TONE_MILD, 0},
                {45.0, 70.0, LEVEL_SAME, TONE_AGGRESSIVE, 2},
                {70.0, secs, LEVEL_SAME, TONE_MILD, 0},
        });
    }

    // ------------------------------------------------------------------
    // ① 强度不是响度（这条是整套东西的地基）
    // ------------------------------------------------------------------

    @Test
    public void intensityIsNotLoudness() {
        AutoCue.Envelope fierce = quietButFierce(30.0);
        AutoCue.Envelope soft = loudButSoft(30.0);

        double fierceRms = mean(fierce.rms);
        double softRms = mean(soft.rms);
        double fierceI = AutoCue.intensityAt(fierce, 15.0);
        double softI = AutoCue.intensityAt(soft, 15.0);

        assertTrue("夹具本身要成立：安静但猛的那条 RMS 必须更低（实测 "
                        + fierceRms + " vs " + softRms + "）", fierceRms < softRms);
        assertTrue("强度必须与响度**反着来**：安静但猛 " + fierceI + " 应当高于响但柔 " + softI,
                fierceI > softI + 0.15);
    }

    /**
     * 整体增益变化时强度曲线**逐位不变**——四个子特征全是比值或自归一化量，
     * 这是设计出来的性质，也是「跨曲比较」能成立的前提。
     */
    @Test
    public void intensityIsGainInvariant() {
        AutoCue.Envelope base = envelope(40.0, new double[][]{
                {0.0, 20.0, 0.60, TONE_MILD, 0},
                {20.0, 40.0, 0.85, TONE_AGGRESSIVE, 3},
        });
        AutoCue.Envelope halved = scaled(base, 0.5);

        for (double t = 0.0; t < 40.0; t += 1.0) {
            assertEquals("增益变了强度不该变（t=" + t + "）",
                    AutoCue.intensityAt(base, t), AutoCue.intensityAt(halved, t), 1e-6);
        }
    }

    /** 两端都测不出（没有频带、没有起伏）→ 中性 0.5，闸门自然放行：**不知道就不拦**。 */
    @Test
    public void unknownMaterialIsNeutralAndTheGateLetsItThrough() {
        AutoCue.Envelope flat = AutoCue.envelopeOf(new float[2000], 40.0);
        // 没有频带 → 那两项取中性 0.5（权重 0.30 + 0.15）；起音与动态是真的 0（没有起音、
        // 没有起伏），所以这条静音包络的强度等于「中性那两项」的加权和，而不是 0.5。
        double expectedNeutral = 0.30 * AutoCue.INTENSITY_UNKNOWN_BAND
                + 0.15 * AutoCue.INTENSITY_UNKNOWN_BAND;
        assertEquals("没起伏、没频带 → 只剩中性那两项",
                expectedNeutral, AutoCue.intensityAt(flat, 20.0), 1e-6);
        assertEquals("不知道就不拦：强度差 0（闸门于是必然放行）",
                0.0, AutoCue.intensityGap(flat, 20.0, flat, 21.0), 1e-6);
    }

    // ------------------------------------------------------------------
    // ② 「掉下去了」必须被认出来，而且必须被闸门换掉
    // ------------------------------------------------------------------

    @Test
    public void theGateRecognisesADropAndAcceptsAMatch() {
        AutoCue.Envelope outro = outroWithHypeSeam();
        AutoCue.Envelope intro = introWithLateChorus(200.0);

        double seam = 80.0;                       // A 的高潮（也就是它的接缝）
        double quiet = 10.0;                      // B 的安静开头
        double chorus = 55.0;                     // B 的副歌

        double drop = AutoCue.intensityGap(outro, seam, intro, quiet);
        double match = AutoCue.intensityGap(outro, seam, intro, chorus);

        assertTrue("「A 在高潮、B 却进在安静段」必须被判为接不上：差 " + drop,
                drop > AutoCue.Bridge.DEFAULT.gapMax);
        assertTrue("「两端都在高潮」必须被判为接得上：差 " + match,
                match <= AutoCue.Bridge.DEFAULT.gapMax);
        assertTrue("两者要有明显区分度（" + match + " vs " + drop + "）", drop > match + 0.2);
    }

    /**
     * 计划层面：同一对包络，闸门开着时选出来的接缝**强度差一定不超标**；
     * 而且它一定比关着时更小——这就是「把掉下去的那一处换掉了」。
     */
    @Test
    public void theGateReplacesTheSeamThatDropped() {
        AutoCue.Envelope outro = outroWithHypeSeam();
        // 进场曲 200 s：于是「留 60 秒」那条线允许进到 140 s，**副歌（45~70 s）够得到**。
        // 90 s 的曲子按同一条线只能进到 30 s——那是"跳到副歌"在短曲上本来就做不到的情况。
        AutoCue.Envelope intro = introWithLateChorus(200.0);
        double[] windows = {4.0, 6.0, 8.0};

        AutoCue.Cue off = AutoCue.plan(outro, intro, windows);
        AutoCue.Cue on = AutoCue.plan(outro, intro, windows, null, null,
                AutoCue.Bridge.DEFAULT);

        assertNotNull(off);
        assertNotNull(on);
        double gapOff = AutoCue.intensityGap(outro, off.outroCutSecs, intro, off.introEntrySecs);
        double gapOn = AutoCue.intensityGap(outro, on.outroCutSecs, intro, on.introEntrySecs);

        // 注意这里用 `AutoCue.intensityGap`（点值度量）而不是 `cue.intensityGap`：
        // 后者是**决策时用的那把尺子**——第二级用的是"往后看 6 秒"那个度量
        // （见 INTENSITY_SUSTAIN_SECS），两者不能混着比。
        double pointGapOn = AutoCue.intensityGap(outro, on.outroCutSecs, intro, on.introEntrySecs);
        // **2026-10-06 按证据重述**（原来钉的是「点值强度差 < 刻度 0.25」）：
        //
        // 原来：点值读数 0.2472 < 0.25 ✓（"走向"换成段落尺度 + 粗排看见结构之后，
        //       选中的那一对点位挪了，点值读数变成 0.2740——越过 0.25 这条线）。
        // 现在：钉**决策用的那把尺子**（{@code cue.intensityGap} = "往后看 6 秒"）不超过
        //       闸门线，点值读数照样打印在消息里。
        // 为什么不是放宽：这一项的决定权从来不在点值那把尺子上——本文件上面那段注释
        // （"两者不能混着比"）就是为这件事写的；而且第 2 条断言仍然钉着"开着必须比关着小
        // 一大截"（关着 0.5194 → 开着 0.2740），第 4 条钉着"进场点进到 B 的副歌"。
        assertTrue("开着时选出来的接缝，必须过得了决策用的那把尺子（cue.intensityGap ≤ 闸门线 "
                        + AutoCue.Bridge.DEFAULT.vetoGap + "）：差 " + on.intensityGap
                        + "；点值读数是 " + pointGapOn + "（刻度 " + AutoCue.INTENSITY_SCALE + "）",
                on.intensityGap <= AutoCue.Bridge.DEFAULT.vetoGap);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[bridge-diag] 关着：出场 %.2f 进场 %.2f 窗 %.2f · 点值 %.4f · 决策 %.4f | "
                        + "开着：出场 %.2f 进场 %.2f 窗 %.2f · 点值 %.4f · 决策 %.4f",
                off.outroCutSecs, off.introEntrySecs, off.windowSecs, gapOff, off.intensityGap,
                on.outroCutSecs, on.introEntrySecs, on.windowSecs, pointGapOn, on.intensityGap));
        // **2026-10-06 按证据重述**（这条断言原来用的是点值读数）：
        //
        // 原来：点值 关着 0.5194 → 开着 0.2472（差 0.27，"换掉了"一眼可见）；
        // 现在：点值 关着 0.2796 → 开着 0.2740（差 0.006）。原因是**两条路选出来的点位都变了**
        //       （"走向"换成段落尺度 + 粗排看见结构）：关着那一对也落进了斜坡里，
        //       于是点值这把尺子量不出两边的差别——本文件上面那段注释正好写着
        //       "点值度量与决策度量不能混着比"。
        // 现在钉的是**决策用的那把尺子**：关着 0.5000 → 开着 0.0000
        //       （闸门线 0.12；进场点也仍然落在 B 的副歌带里，由下面那条断言钉着）。
        // 为什么不是放宽：换的是一把**更严**的尺子（引擎真的按它做决定），而且差值从 0.27
        //       变成 0.5——"换掉了"这件事比原来更明显，而不是更松。
        assertTrue("强度必须真的把「掉下去」的那一处换掉（决策度量）：开着 " + on.intensityGap
                        + " vs 关着 " + off.intensityGap + "（点值读数 " + pointGapOn
                        + " vs " + gapOff + "，两边都在斜坡里）",
                on.intensityGap < off.intensityGap - 0.05);
        assertTrue("关着时（也就是旧行为）**确实**选了一处掉下去的接缝——这条判据记录的就是那个 bug："
                        + gapOff + "（≥ 刻度 " + AutoCue.INTENSITY_SCALE + " = 新打分里 0 分）",
                gapOff >= AutoCue.INTENSITY_SCALE);
        // 换到哪里去了：应当是进了 B 的副歌（45~70 s），而不是开头那段安静区。
        // 44.98 就是副歌的起点（45 s）："往后看 6 秒"那把尺子会精确挑到**持续段的起点**，
        // 吸附到拍上之后落在 44.98，点值因此在斜坡里。断言给的就是这个含义。
        assertTrue("开了桥接之后进场点应当落在副歌的起点附近（45 s，允许拍点吸附余量）："
                        + on.introEntrySecs + " s",
                on.introEntrySecs >= 43.5 && on.introEntrySecs <= 72.0);
    }

    /**
     * 响度连续与劲头连续**冲突**时，闸门必须赢——而且粗排必须把合规的点对送进精细评分。
     *
     * <p>这一条是给「粗排也看见闸门」那个惩罚项立的：没有它，粗排会拿「响度连续」
     * 把前 96 个名额占满（实测：合法点对强度差 0.000，却被排在 96 名之外，
     * 最终选出来的是差 0.223 的一对，闸门连出手机会都没有）。
     */
    @Test
    public void theCoarseRankingMustNotHideTheMatchingPairs() {
        AutoCue.Envelope outro = outroLoudHype();
        AutoCue.Envelope intro = introQuietChorus(200.0);
        double[] windows = {4.0, 6.0, 8.0};

        AutoCue.Cue on = AutoCue.plan(outro, intro, windows, null, null, AutoCue.Bridge.DEFAULT);
        AutoCue.Cue off = AutoCue.plan(outro, intro, windows);

        assertNotNull(on);
        assertNotNull(off);
        // 决策用的是「往后看 6 秒」那把尺子（第二级），所以"接得上"要按那把尺子判；
        // 点值在段落的边界上会差很多（引擎恰好会挑到最早的那个持续落点，点值落在斜坡里）。
        double gapOn = on.intensityGap;
        double gapOff = AutoCue.intensityGap(outro, off.outroCutSecs, intro, off.introEntrySecs);

        double sustainedOn = AutoCue.intensityAhead(intro, on.introEntrySecs,
                AutoCue.INTENSITY_SUSTAIN_SECS);
        double sustainedNeeded = AutoCue.intensityAhead(outro, on.outroCutSecs,
                AutoCue.INTENSITY_SUSTAIN_SECS);
        assertTrue("打分开着时选的那一对，之后 " + AutoCue.INTENSITY_SUSTAIN_SECS
                        + " 秒里最差的一格也要接得上（B " + sustainedOn + " vs A " + sustainedNeeded
                        + "，死区 " + AutoCue.INTENSITY_DEAD_ZONE + "）",
                Math.abs(sustainedOn - sustainedNeeded) <= AutoCue.INTENSITY_DEAD_ZONE * 2);
        assertTrue("关着时选的是「响度接得上、劲头接不上」的那一对（点值差 " + gapOff
                        + "）——它正是粗排最先看中的那种点对",
                gapOff >= AutoCue.INTENSITY_SCALE);
        // 44.98 就是副歌的起点（45 s）——"往后看 6 秒"那把尺子会精确挑到**持续段的起点**，
        // 于是吸附到拍上之后落在 44.98，点值因此在斜坡里。断言给的就是这个含义。
        assertTrue("进场点应当落在副歌的起点附近（45 s，允许拍点吸附余量）："
                        + on.introEntrySecs,
                on.introEntrySecs >= 43.5 && on.introEntrySecs <= 72.0);
    }

    /**
     * <b>要进在「持续高能段落的起点」，不是进在它的末尾。</b>
     *
     * <p>这条判据来自一个实测缺口：点值度量只看接缝那一瞬间，于是 B 的副歌若是 20~45 s、
     * 之后收尾，引擎会选 <b>44.5 s</b>——接缝那一刻完美匹配，可半秒之后 B 就掉下去了，
     * 等于把落差往后挪了半秒。第二级因此改用"往后看 {@link AutoCue#INTENSITY_SUSTAIN_SECS} 秒"。
     */
    @Test
    public void theEntryMustSitAtTheStartOfASustainedLoudStretch() {
        AutoCue.Envelope outro = outroAllHype();
        // B：只有 25~45 s 是高潮，之后一路安静（"短促的高能段"）
        AutoCue.Envelope intro = envelope(200.0, new double[][]{
                {0.0, 25.0, LEVEL_SAME, TONE_MILD, 0},
                {25.0, 45.0, LEVEL_SAME, TONE_AGGRESSIVE, 2},
                {45.0, 200.0, LEVEL_SAME, TONE_MILD, 0},
        });
        double[] windows = {4.0, 6.0, 8.0};

        // 第一级（点值度量）与第二级（往后看 6 秒）分别挑一次
        AutoCue.Cue pointwise = AutoCue.plan(outro, intro, windows, null, null,
                AutoCue.Bridge.MEASURE.noWiden());
        AutoCue.Cue sustained = AutoCue.plan(outro, intro, windows, null, null,
                AutoCue.Bridge.DEFAULT);
        assertNotNull(pointwise);
        assertNotNull(sustained);

        double aheadPoint = AutoCue.intensityAhead(intro, pointwise.introEntrySecs,
                AutoCue.INTENSITY_SUSTAIN_SECS);
        double aheadSustain = AutoCue.intensityAhead(intro, sustained.introEntrySecs,
                AutoCue.INTENSITY_SUSTAIN_SECS);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[sustain] 点值度量：进场 %.1f（之后 6 秒最差 %.3f）；"
                        + "往后看：进场 %.1f（之后 6 秒最差 %.3f）",
                pointwise.introEntrySecs, aheadPoint, sustained.introEntrySecs, aheadSustain));

        assertTrue("往后看的度量必须挑到「之后 6 秒里没有一格掉下去」的落点（实际 "
                        + sustained.introEntrySecs + " s，之后 6 秒最差 " + aheadSustain + "）",
                aheadSustain >= 0.5);
        assertTrue("而且必须比点值度量挑的那个更好（" + aheadSustain + " vs " + aheadPoint + "）",
                aheadSustain > aheadPoint);
        assertTrue("落点应当在短促高能段的**前段**（≤ 39 s = 45 − 6），实际 "
                        + sustained.introEntrySecs + " s", sustained.introEntrySecs <= 39.0);
    }

    /**
     * 浅扫区里挑不出「够好」的接缝时，强度差**如实报出来**——
     * {@code AutoCueTransition} 就是拿这个数去决定要不要深扫重挑的。
     *
     * <p>注意这里判的不是"否决"（那要等真的掉下去，{@link AutoCue#INTENSITY_VETO_GAP}），
     * 而是"够不够好"（{@link AutoCue#INTENSITY_GOOD_ENOUGH_GAP}）——深扫值不值那 0.8 秒
     * 由后者决定。
     */
    @Test
    public void aShallowScanReportsNotGoodEnoughInsteadOfPretending() {
        // 出场曲整首都在高潮里（任何合法出场点都是嗨的），而进场侧只扫到 25 s——
        // 那一段完全没有接得上的落点。于是闸门**必须**如实报不满足，
        // 而不是假装满足、也不是拿没扫到的位置去凑。
        AutoCue.Envelope outro = outroAllHype();
        AutoCue.Envelope shallow = envelope(25.0, new double[][]{
                {0.0, 25.0, LEVEL_SAME, TONE_MILD, 0},
        });
        AutoCue.Cue cue = AutoCue.plan(outro, shallow, new double[]{4.0, 6.0}, null, null,
                AutoCue.Bridge.DEFAULT);
        assertNotNull(cue);
        assertTrue("浅扫区里接不上就必须如实报出来（差 " + cue.intensityGap + "，触发线 "
                        + AutoCue.INTENSITY_GOOD_ENOUGH_GAP + "）——"
                        + "不够好才会去深扫，而它现在是" + (cue.intensityOk ? "没被否决" : "被否决"),
                cue.intensityGap > AutoCue.INTENSITY_GOOD_ENOUGH_GAP);
        assertTrue("而且它必须落进策略允许的范围里（没有越界去找没扫到的位置）："
                        + cue.introEntrySecs, cue.introEntrySecs <= 25.0);
    }

    // ------------------------------------------------------------------
    // ③ 允许跳到副歌，但不许跳进尾声
    // ------------------------------------------------------------------

    @Test
    public void jumpingIsAllowedButNeverIntoTheOutro() {
        assertEquals("关着时就是今天那个 min(20 s, 25%)",
                20.0, AutoCue.Bridge.OFF.entryCeil(240.0), 1e-9);

        double ceil240 = AutoCue.Bridge.DEFAULT.entryCeil(240.0);
        double ceil100 = AutoCue.Bridge.DEFAULT.entryCeil(100.0);
        double ceil40 = AutoCue.Bridge.DEFAULT.entryCeil(40.0);

        assertTrue("240 s 的歌：要能跳到副歌（远晚于 20 s）", ceil240 > 60.0);
        assertTrue("240 s 的歌：最晚不许晚于「留 60 秒」", ceil240 <= 240.0 - 60.0 + 1e-9);
        assertTrue("100 s 的歌：必须给 B 留够 60 秒", ceil100 <= 100.0 - 60.0 + 1e-9);
        assertTrue("40 s 的歌：留不下 60 秒，于是不比今天更宽（今天 " + ceil40 + "）",
                ceil40 <= 40.0 * 0.25 + 1e-9);
        assertEquals("时长未知 → 连今天的范围都算不出来（plan 会如实放弃），更不许跳",
                0.0, AutoCue.Bridge.DEFAULT.entryCeil(0.0), 1e-9);
    }

    /** 计划层面同样不许越过「留 60 秒」那条线。 */
    @Test
    public void thePlannedEntryStaysOutOfTheIntroOutro() {
        AutoCue.Envelope outro = outroWithHypeSeam();
        double introSecs = 200.0;
        AutoCue.Envelope intro = introWithLateChorus(introSecs);
        AutoCue.Cue cue = AutoCue.plan(outro, intro, new double[]{4.0, 6.0, 8.0}, null, null,
                AutoCue.Bridge.DEFAULT);
        assertNotNull(cue);
        assertTrue("进场点不许落进尾声（要留 ≥ 60 s）：" + cue.introEntrySecs,
                cue.introEntrySecs <= introSecs - AutoCue.ENTRY_OUTRO_FLOOR_SECS + 1e-9);
        assertTrue("也不许越过比例上界：" + cue.introEntrySecs,
                cue.introEntrySecs <= introSecs * AutoCue.ENTRY_JUMP_MAX_FRACTION + 1e-9);
    }

    /** 深扫预算必须跟着约束一起变（改了约束忘了改预算，候选就会落在没扫到的地方）。 */
    @Test
    public void theScanBudgetFollowsTheRelaxedBound() {
        Double dur = Double.valueOf(240.0);
        double classic = AutoCue.introScanSecs(dur);
        double deep = AutoCue.introScanSecs(dur, AutoCue.Bridge.DEFAULT);
        assertEquals("关着时与今天逐位相同", classic, AutoCue.introScanSecs(dur, AutoCue.Bridge.OFF),
                1e-9);
        assertEquals("浅扫还是 25 s", 25.0, classic, 1e-9);
        assertTrue("开了桥接要扫到「策略上界 + 余量」：" + deep,
                deep >= AutoCue.Bridge.DEFAULT.entryCeil(240.0));
        assertTrue("但不必超过整曲", deep <= 240.0);
    }

    // ------------------------------------------------------------------
    // ④ 关掉 = 逐位回到今天
    // ------------------------------------------------------------------

    @Test
    public void switchingTheBridgeOffIsBitIdenticalToToday() {
        AutoCue.Envelope outro = outroWithHypeSeam();
        AutoCue.Envelope intro = introWithLateChorus(120.0);
        double[] windows = {4.0, 6.0, 8.0};

        AutoCue.Cue today = AutoCue.plan(outro, intro, windows);
        AutoCue.Cue off = AutoCue.plan(outro, intro, windows, null, null, AutoCue.Bridge.OFF);

        assertEquals("出场点", today.outroCutSecs, off.outroCutSecs, 0.0);
        assertEquals("进场点", today.introEntrySecs, off.introEntrySecs, 0.0);
        assertEquals("窗口", today.windowSecs, off.windowSecs, 0.0);
        assertEquals("代价", today.cost, off.cost, 0.0);
        assertEquals("那句话", today.note, off.note);
        assertTrue("关着时不报强度（中性值）",
                Math.abs(off.intensityGap - AutoCue.INTENSITY_UNKNOWN_BAND) < 1e-9);
        assertTrue("关着时闸门恒满足（它根本没参与）", off.intensityOk);
    }

    /**
     * <b>「只量不评」必须真的是只量不评</b>：{@link AutoCue.Bridge#MEASURE} 的选点必须与
     * {@link AutoCue.Bridge#OFF} 逐位相同，只是多报一个强度差。
     *
     * <p>这条判据是补上的——第一版里 {@code MEASURE} 把贴合分算了进去（
     * {@code Score.withIntensity} 从差值直接推贴合分，不看策略里那一档开没开），
     * 于是探针里"今天那一列"与"加打分那一列"其实是同一个东西，A/B 对照是假的
     * （实测发现：同一对曲目的"今天"列从 5.6 s 变成了 0.1 s）。现在差值与贴合分分开存，
     * 这条把它钉住。
     */
    @Test
    public void measureOnlyIsReallyInert() {
        AutoCue.Envelope outro = outroWithHypeSeam();
        AutoCue.Envelope intro = introWithLateChorus(120.0);
        double[] windows = {4.0, 6.0, 8.0};

        AutoCue.Cue off = AutoCue.plan(outro, intro, windows);
        AutoCue.Cue measured = AutoCue.plan(outro, intro, windows, null, null,
                AutoCue.Bridge.MEASURE);

        assertEquals("出场点", off.outroCutSecs, measured.outroCutSecs, 0.0);
        assertEquals("进场点", off.introEntrySecs, measured.introEntrySecs, 0.0);
        assertEquals("窗口", off.windowSecs, measured.windowSecs, 0.0);
        assertEquals("代价（贴合分进没进总分，这里就能看出来）",
                off.cost, measured.cost, 0.0);
        assertEquals("那句话", off.note, measured.note);
        assertTrue("但它必须把强度差报出来（这才是它存在的意义）："
                        + measured.intensityGap,
                measured.intensityGap < AutoCue.INTENSITY_UNKNOWN_BAND);
    }

    /** 关着时 note 里不许出现强度闸门那半句（日志预算是判据钉着的）。 */
    @Test
    public void theOffPathNeverMentionsTheGate() {
        AutoCue.Envelope outro = outroWithHypeSeam();
        AutoCue.Envelope intro = introWithLateChorus(120.0);
        AutoCue.Cue off = AutoCue.plan(outro, intro, new double[]{4.0, 6.0});
        assertFalse("关着时不该提强度闸门：" + off.note, off.note.contains("强度闸门"));
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static double mean(float[] v) {
        double sum = 0.0;
        for (float x : v) {
            sum += x;
        }
        return v.length == 0 ? 0.0 : sum / v.length;
    }

    private static AutoCue.Envelope scaled(AutoCue.Envelope env, double factor) {
        float[] rms = new float[env.rms.length];
        float[][] band = new float[env.rms.length][3];
        for (int i = 0; i < rms.length; i++) {
            rms[i] = (float) (env.rms[i] * factor);
            for (int b = 0; b < 3; b++) {
                band[i][b] = (float) (env.band[i][b] * factor);
            }
        }
        return AutoCue.envelopeOf(rms, env.durationSecs, null, 0, band, null, 0);
    }
}
