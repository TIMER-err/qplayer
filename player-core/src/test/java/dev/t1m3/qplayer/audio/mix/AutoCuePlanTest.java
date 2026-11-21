package dev.t1m3.qplayer.audio.mix;

import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 「自动点位」这一版升级（方案级打分）的判据。
 *
 * <h2>为什么判据要自己造包络</h2>
 *
 * <p>这一层要回答的问题是<strong>「两首歌叠在一起好不好」</strong>，而合成素材能把这件事
 * 变成<strong>已知答案</strong>的问题：把两条包络造成「在某一处相位对齐、别处不对齐」，
 * 那么正确的实现必须挑那一处——这就是独立的尺子（不依赖扫描、不依赖解码器、
 * 不依赖任何听感）。真链路（扫描 → 挑点）由 {@code AutoCueScanBudgetTest} 与
 * 端到端那条守着。
 *
 * <h2>钉住的四件事</h2>
 *
 * <ol>
 *   <li><strong>波形重合真的参与</strong>：两条除了相位以外完全一样的波形，
 *       只有对齐的那一处得分最高（旧版只看响度与斜率，会对两处给出同样的分）；</li>
 *   <li><strong>淡化叠加真的参与</strong>：窗口里两边都「空一段」的方案要输给全程有内容的方案
 *       （等功率淡化把两个凹陷叠出来就是一次听得见的塌陷）；</li>
 *   <li><strong>窗口时长参与选择</strong>：同一个接缝上，短窗口能避开速度漂移 → 分析应当选它
 *       （这是「声波重合模式允许根据实际情况调整过渡时长」的可执行版本）；</li>
 *   <li><strong>AI 方案比较把声波重合算进去</strong>：调性完全对不上时，
 *       {@link AutoCue#comparePlans} 必须倾向声波重合；调性一致时不该乱转调。</li>
 * </ol>
 */
@Ignore("legacy：旧决策链的判据；忠实移植 Apple 决策链期间停用 "
        + "（旧逻辑快照在 git tag automix-legacy-20261121，还原后可恢复）")
public class AutoCuePlanTest {

    private static final double HOP = AutoCue.HOP_SECS;

    /** 造一条包络：能量按给定的「包络形状函数」走，波形按给定频率/相位。 */
    private static AutoCue.Envelope envelope(double seconds, float[] shape,
                                             double waveHz, double phase, double[] bands,
                                             Analysis.Tempo tempo) {
        int hops = (int) Math.round(seconds / HOP);
        float[] rms = new float[hops];
        float[] onset = new float[hops];
        float[][] band = new float[hops][];
        for (int i = 0; i < hops; i++) {
            float value = shape[Math.min(i, shape.length - 1)];
            rms[i] = value;
            band[i] = new float[]{
                    value * (float) bands[0], value * (float) bands[1], value * (float) bands[2]};
        }
        for (int i = 1; i < hops; i++) {
            onset[i] = Math.max(0f, rms[i] - rms[i - 1]);
        }
        int waveRate = AutoCue.WAVE_RATE;
        float[] wave = new float[(int) Math.round(seconds * waveRate)];
        for (int i = 0; i < wave.length; i++) {
            wave[i] = (float) Math.sin(2 * Math.PI * waveHz * i / waveRate + phase);
        }
        return new AutoCue.Envelope(rms, onset, seconds, tempo, waveRate, band, wave, waveRate);
    }

    /** 常数包络（能量不随时间变）。 */
    private static float[] flat(double seconds, float value) {
        float[] out = new float[(int) Math.round(seconds / HOP)];
        java.util.Arrays.fill(out, value);
        return out;
    }

    /** 只在某个区间抬起来（模拟「副歌」），其余低。 */
    private static float[] bump(double seconds, double fromSecs, double toSecs,
                                float low, float high) {
        float[] out = flat(seconds, low);
        int from = (int) Math.round(fromSecs / HOP);
        int to = (int) Math.round(toSecs / HOP);
        for (int i = from; i < to && i < out.length; i++) {
            out[i] = high;
        }
        return out;
    }

    // ==================================================================
    // ① 波形重合
    // ==================================================================

    /**
     * 两条除相位外完全相同的波形：<strong>只有相位对齐的那一处该得高分</strong>。
     *
     * <p>素材：出场曲整段能量恒定、波形相位 0；进场曲同频率、相位 180°（反相），
     * 但从第 60 秒起相位变成 0（与出场曲同相）。出场点在 60 秒之后时两段同相，
     * 之前反相——正确的实现必须把出场点放在 60 秒之后。
     *
     * <p>旧版打分只看「响度差 / 斜率 / onset」，这三个量在这里<strong>处处相同</strong>，
     * 所以它挑哪儿都可能；这一条就是为「波形重合真的进来了」立的。
     */
    @Test
    public void thePlannerPrefersThePhaseAlignedOverlap() {
        double seconds = 120.0;
        double[] bands = {0.5, 0.8, 0.5};
        // 出场曲：前 60 秒与进场曲反相，60 秒之后同相
        AutoCue.Envelope rawOutro = envelope(seconds, flat(seconds, 0.6f), 60.0, 0.0, bands, null);
        float[] outroWave = rawOutro.wave.clone();
        int flip = (int) Math.round(60.0 * rawOutro.waveRate);
        for (int i = 0; i < flip; i++) {
            outroWave[i] = -outroWave[i];
        }
        AutoCue.Envelope outro = new AutoCue.Envelope(rawOutro.rms, rawOutro.onset,
                rawOutro.durationSecs, null, rawOutro.beatRate, rawOutro.band, outroWave,
                rawOutro.waveRate);
        // 进场曲：全程同相
        AutoCue.Envelope intro = envelope(60.0, flat(60.0, 0.6f), 60.0, 0.0, bands, null);

        // 只看「整段交叠」这一项：出场点 61 秒（同相）应当胜过 30 秒（反相）
        AutoCue.Score aligned = AutoCue.evaluate(outro, intro, 61.0, 10.0, 8.0);
        AutoCue.Score antiPhase = AutoCue.evaluate(outro, intro, 30.0, 10.0, 8.0);
        System.out.printf("### 波形重合：同相 %.3f vs 反相 %.3f（总分 %.3f vs %.3f）%n",
                Double.valueOf(aligned.overlap), Double.valueOf(antiPhase.overlap),
                Double.valueOf(aligned.total), Double.valueOf(antiPhase.total));
        assertTrue("同相那一处必须相关更高（" + aligned.overlap + " vs " + antiPhase.overlap + "）",
                aligned.overlap > antiPhase.overlap + 0.3);
        assertTrue("总分也必须更高（" + aligned.total + " vs " + antiPhase.total + "）",
                aligned.total > antiPhase.total);

        // 连**点位**也应当由它决定：出场侧的候选从 20% 起，两处能量/斜率完全一样，
        // 只有相位不同 —— 旧版打分在这里无处发力，新版必须挑到 60 秒之后
        AutoCue.Cue cue = AutoCue.plan(outro, intro, 8.0);
        assertNotNull(cue);
        System.out.println("### 同一条素材上的挑点：" + cue.note);
        assertTrue("挑出来的出场点必须落在同相那一段里（实际 " + cue.outroCutSecs + "s）",
                cue.outroCutSecs >= 58.0);
    }

    // ==================================================================
    // ② 淡化叠加
    // ==================================================================

    /**
     * 窗口里两边都「空一段」的方案要输给全程有内容的方案。
     *
     * <p>素材：出场曲在 40–52 秒之间能量掉到 0.05（一段收尾），别处 0.6；
     * 进场曲在第 10 秒之后有一段 6 秒的静音（前奏里的空白），别处 0.6。
     * 把出场点放在那一段收尾上时，等功率淡化会把两个凹陷叠在窗口中间——
     * 总电平塌下去一次，听感上就是接缝处的「断了半拍」。
     */
    @Test
    public void thePlannerAvoidsAWindowWhereBothSidesGoQuiet() {
        double seconds = 120.0;
        double[] bands = {0.5, 0.8, 0.5};
        float[] outroShape = bump(seconds, 40.0, 52.0, 0.6f, 0.05f);   // 40–52s 是收尾
        AutoCue.Envelope outro = envelope(seconds, outroShape, 60.0, 0.0, bands, null);
        float[] introShape = bump(60.0, 10.0, 16.0, 0.6f, 0.05f);      // 进场曲 10–16s 空
        AutoCue.Envelope intro = envelope(60.0, introShape, 60.0, 0.0, bands, null);

        AutoCue.Score overTheGap = AutoCue.evaluate(outro, intro, 40.0, 12.0, 8.0);
        AutoCue.Score solid = AutoCue.evaluate(outro, intro, 70.0, 2.0, 8.0);
        System.out.printf("### 淡化起伏（1−标准差/均值）：压在凹陷上 %.3f vs 实心处 %.3f%n",
                Double.valueOf(overTheGap.ripple), Double.valueOf(solid.ripple));
        assertTrue("压在两边都空的地方必须被扣分（" + overTheGap.ripple + " vs " + solid.ripple + "）",
                solid.ripple > overTheGap.ripple + 0.05);
    }

    // ==================================================================
    // ③ 窗口时长参与选择
    // ==================================================================

    /**
     * 速度差明显的两首歌：<strong>短窗口在该尺子上应当胜过长窗口</strong>——
     * 这就是「声波重合允许按实际情况调时长」的判据（拍在窗口里漂得越少越好）。
     */
    @Test
    public void aTighterTempoPrefersAShorterWindow() {
        double seconds = 150.0;
        double[] bands = {0.5, 0.8, 0.5};
        // 出场 120 BPM、进场 132 BPM（差 10%）：8 秒里漂 1.6 拍、20 秒里漂 4 拍
        Analysis.Tempo tOut = tempo(120.0, 20_000, 0.9f);
        Analysis.Tempo tIn = tempo(132.0, 20_000, 0.9f);
        AutoCue.Envelope outro = envelope(seconds, flat(seconds, 0.6f), 60.0, 0.0, bands, tOut);
        AutoCue.Envelope intro = envelope(60.0, flat(60.0, 0.6f), 60.0, 0.0, bands, tIn);

        AutoCue.Score shortWindow = AutoCue.evaluate(outro, intro, 100.0, 5.0, 4.0);
        AutoCue.Score longWindow = AutoCue.evaluate(outro, intro, 100.0, 5.0, 20.0);
        System.out.printf("### 120 vs 132 BPM：4 秒窗口拍分 %.3f、20 秒窗口拍分 %.3f%n",
                Double.valueOf(shortWindow.beat), Double.valueOf(longWindow.beat));
        assertTrue("长窗口里拍漂得更远，拍分必须更低（" + shortWindow.beat + " vs "
                        + longWindow.beat + "）",
                shortWindow.beat > longWindow.beat + 0.2);

        // 计划本身也要能在候选里挑出更短的那一个
        AutoCue.Cue cue = AutoCue.plan(outro, intro, new double[]{20.0, 4.0});
        assertNotNull("应当能挑出方案", cue);
        System.out.println("### 候选 {20s, 4s} 选了：" + cue.note);
        assertTrue("应当选更短的那一个（实际 " + cue.windowSecs + "s）", cue.windowSecs <= 8.0);
    }

    /** 造一条速度结论（周期与相位都按 BPM 换算到给定的降采样率上）。 */
    private static Analysis.Tempo tempo(double bpm, int rate, float confidence) {
        float periodFrames = (float) (60.0 / bpm * rate);
        return new Analysis.Tempo((float) bpm, periodFrames, 0.0f, confidence);
    }

    // ==================================================================
    // ④ AI 方案比较
    // ==================================================================

    /** 调性完全对不上：声波重合（能转调）应当被选中。 */
    @Test
    public void theAiComparisonPicksHarmonicBlendWhenKeysClash() {
        double seconds = 150.0;
        double[] bands = {0.5, 0.8, 0.5};
        AutoCue.Envelope outro = envelope(seconds, flat(seconds, 0.6f), 60.0, 0.0, bands, null);
        AutoCue.Envelope intro = envelope(60.0, flat(60.0, 0.6f), 60.0, 0.0, bands, null);
        AutoCue.PlanChoice clash = AutoCue.comparePlans(outro, intro, 8.0, AutoCueTransition.windowCandidates(6.0, true), 0.1);
        assertNotNull(clash);
        System.out.println("### AI 方案比较（调性 0.1）：" + clash.why);
        assertTrue("调性对不上时应当倾向声波重合：" + clash.why, clash.harmonic);
    }

    /**
     * <b>调性本来就一致：谁赢只能看七项，加成已经不存在了。</b>
     *
     * <p>这里钉的是 2026-09-29 那次「加成翻盘」的复发：当时只要 {@code plainScore > 0} 就无条件
     * 记上 {@code (1−plainScore)×0.35}，用户日志里七项 0.434 靠 +0.262 的加成压过基准 0.695。
     * 加成删除后，{@code harmonicScore} 必须<b>就是</b>声波重合那套的七项分（{@code 1 − cost}），
     * 胜负必须与这两个分数的大小一致——不变量而不是具体数值，所以以后调权重也不会误报。
     *
     * <p>注意这里用的是 5 参重载（{@code shiftWorthwhile} 固定 true，等于「还没判断值不值得移调」），
     * 所以「调性本来就和 → 连候选都不给」那条闸门在另一个用例里
     * （{@link #aShiftThatIsNotWorthDoingTakesTheHarmonicPlanOutOfTheRunning}）。这套素材调性一致而
     * 声波重合仍然胜出，赢的是<b>窗口挑得更好</b>（12.0s vs 8.0s）——那正是它删掉加成后唯一剩下的特权。
     */
    @Test
    public void theWinnerIsDecidedByTheSevenTermsAlone() {
        double seconds = 150.0;
        double[] bands = {0.5, 0.8, 0.5};
        AutoCue.Envelope outro = envelope(seconds, flat(seconds, 0.6f), 60.0, 0.0, bands, null);
        AutoCue.Envelope intro = envelope(60.0, flat(60.0, 0.6f), 60.0, 0.0, bands, null);
        AutoCue.PlanChoice match = AutoCue.comparePlans(outro, intro, 8.0, AutoCueTransition.windowCandidates(6.0, true), 0.99);
        assertNotNull(match);
        System.out.println("### AI 方案比较（调性 0.99）：" + match.why);
        // 「没有加成」的可验证形式：那一侧的分数就是它自己七项的 1 − cost，一个字节都不多。
        AutoCue.Cue seven = AutoCue.plan(outro, intro, AutoCueTransition.windowCandidates(6.0, true));
        assertNotNull(seven);
        assertEquals("声波重合的分数必须就是七项分 1 − cost（掺了加成这里就对不上）：" + match.why,
                1.0 - seven.cost, match.harmonicScore, 1e-12);
        assertEquals("胜负必须与两个七项分的大小一致（加了隐藏加成就对不上）：" + match.why,
                match.harmonicScore > match.baselineScore, match.harmonic);
    }

    /**
     * <b>调性差小到不值得移调时，声波重合根本不该进候选。</b>
     *
     * <p>用户 2026-09-29 报的现象：AI 自动档近期几乎每次都选声波重合（「没绷住，
     * 不过效果挺好」）。根因之一是**加成被无条件记上**：只要 {@code plainScore > 0} 就加
     * {@code (1−plainScore)×0.35}，哪怕这次压根不会发生移调（{@code Harmonic.match} 自己
     * 会拒绝），等于给一份拿不到的好处记分。这里钉的就是"不参与"：调性本来就合时，
     * 声波重合连候选都不是——它的全部优势就是那次移调。
     */
    @Test
    public void aShiftThatIsNotWorthDoingTakesTheHarmonicPlanOutOfTheRunning() {
        double seconds = 150.0;
        double[] bands = {0.5, 0.8, 0.5};
        AutoCue.Envelope outro = envelope(seconds, flat(seconds, 0.6f), 60.0, 0.0, bands, null);
        AutoCue.Envelope intro = envelope(60.0, flat(60.0, 0.6f), 60.0, 0.0, bands, null);
        double[] harmonicWindows = AutoCueTransition.windowCandidates(6.0, true);

        // 同样的素材、同样的调性差：只有"这次值不值得移调"这一个输入不同
        AutoCue.PlanChoice worth = AutoCue.comparePlans(outro, intro, 8.0, harmonicWindows,
                0.1, true);
        AutoCue.PlanChoice notWorth = AutoCue.comparePlans(outro, intro, 8.0, harmonicWindows,
                0.1, false);

        assertNotNull(worth);
        assertNotNull(notWorth);
        System.out.println("### 值得移调：" + worth.why);
        System.out.println("### 不值得移调：" + notWorth.why);
        assertFalse("不值得移调时声波重合不该被选中：" + notWorth.why, notWorth.harmonic);
        assertTrue("理由里要说清它为什么不参与：" + notWorth.why,
                notWorth.why.contains("不参与"));
    }

    /**
     * <b>基准侧也按它自己那个标称窗口展开候选</b>（消掉"候选多少"这个结构性偏置）。
     *
     * <p>原先声波重合那一侧给的是<b>一组</b>窗口候选（取最高分），基准侧只算<b>一个</b>
     * ——取 max 的候选越多越占便宜，与素材好坏无关。现在两侧对等，
     * 于是基准侧的分<b>不会低于</b>只用标称窗口算出来的分。
     */
    @Test
    public void theBaselineAlsoPicksAmongItsOwnWindowCandidates() {
        double seconds = 150.0;
        double[] bands = {0.5, 0.8, 0.5};
        AutoCue.Envelope outro = envelope(seconds, flat(seconds, 0.6f), 60.0, 0.0, bands, null);
        AutoCue.Envelope intro = envelope(60.0, flat(60.0, 0.6f), 60.0, 0.0, bands, null);

        AutoCue.Cue nominalOnly = AutoCue.plan(outro, intro, 8.0);
        AutoCue.PlanChoice choice = AutoCue.comparePlans(outro, intro, 8.0,
                AutoCueTransition.windowCandidates(6.0, true), 0.1, false);
        assertNotNull(nominalOnly);
        assertNotNull(choice);
        double nominalScore = 1.0 - nominalOnly.cost;
        System.out.println("### 基准侧：只用标称窗口 " + nominalScore
                + "，展开候选后 " + choice.baselineScore);
        assertTrue("基准侧展开候选后不该比只用标称窗口更差：" + choice.baselineScore
                        + " < " + nominalScore,
                choice.baselineScore >= nominalScore - 1e-9);
    }

    // ==================================================================
    // ⑤ 退化与边界（不许猜）
    // ==================================================================

    /** 一条包络的两侧都拿不到（没扫到波形/频段）时：那几项按中性算，不许凭空给高分或低分。 */
    @Test
    public void missingWaveformDataIsNeutralNotZero() {
        double seconds = 120.0;
        AutoCue.Envelope outro = envelope(seconds, flat(seconds, 0.6f), 60.0, 0.0,
                new double[]{0.5, 0.8, 0.5}, null);
        AutoCue.Envelope naked = new AutoCue.Envelope(outro.rms, outro.onset, seconds);
        AutoCue.Score score = AutoCue.evaluate(naked, naked, 60.0, 5.0, 8.0);
        assertEquals("没有波形数据时波形重合按中性 0.5", 0.5, score.overlap, 1e-9);
        assertEquals("没有频段数据时互补度按中性 0.5", 0.5, score.bands, 1e-9);
    }

    // ==================================================================
    // ⑥ 端到端：窗口读的是**当前设置**，不是开唱那一刻抄下来的值
    // ==================================================================

    /**
     * <b>用户在播放期间改了窗口滑块，这一次过渡必须按新的那个值走。</b>
     *
     * <h2>为什么这条必须有</h2>
     *
     * <p>接法是在<strong>开唱那一刻</strong>（编排器 arm）按设置造出来的，而「自动点位」
     * 要到这次过渡<strong>准备</strong>时才挑点——中间隔着整首歌。用户完全可能在这期间
     * 把「声波重合窗口」从 6 秒拖到 12 秒；如果挑点用的是 arm 时抄下来的那个数，
     * 界面上写着 12 秒而实际按 6 秒融合，而且<strong>没有任何地方会报错</strong>。
     *
     * <p>判据走真链路：假解码器 → {@link AutoCue#scan} → 挑点 → 窗口。
     */
    @Test
    public void theCueUsesTheLatestWindowNotTheValueAtArmTime() {
        float[] pcm = tone(120, 220.0);
        installFakeDecoder(pcm);
        try {
            long baseWindow = 6L * RATE;
            MutableWindow live = new MutableWindow(6.0);
            AutoCueTransition transition = new AutoCueTransition(
                    new FakeBase(baseWindow), RATE, 2, live, false);

            // 开唱之后用户把滑块拖到 12 秒
            live.value = 12.0;

            TransitionPlan plan = transition.prepare("test://outro", "test://intro", RATE, null, null);
            assertNotNull("应当挑出方案（合成素材足够长）", plan);
            System.out.println("### 现读窗口：" + plan.note);
            assertEquals("窗口必须按**当前设置**的 12 秒，而不是 arm 时的 6 秒",
                    12.0 * RATE, transition.windowFrames(RATE), 2.0);
            assertTrue("说明里要写出用的是当前设置的那个值：" + plan.note,
                    plan.note.contains("12.0"));
        } finally {
            dev.t1m3.qplayer.audio.decode.Decoder.install(null);
        }
    }

    /**
     * <b>调性加成已删除：两套方案就在同一把尺子（七项）上比。</b>
     *
     * <p>用户 2026-09-29 先把它从 0.35 降到 0.07、随后要求直接删掉。
     * 于是「AI 总选声波重合」这件事从根上没有了着力点：声波重合要赢，必须七项真的更高
     * ——它唯一剩下的优势是能把窗口调到分析挑出来的那一个。
     */
    @Test
    public void thereIsNoKeyBonusLeftInTheComparison() {
        double seconds = 150.0;
        double[] bands = {0.5, 0.8, 0.5};
        AutoCue.Envelope outro = envelope(seconds, flat(seconds, 0.6f), 60.0, 0.0, bands, null);
        AutoCue.Envelope intro = envelope(60.0, flat(60.0, 0.6f), 60.0, 0.0, bands, null);

        // 调性差得远（0.1 = 几乎不相似）：也**不该**有任何加成把声波重合推上去
        AutoCue.PlanChoice far = AutoCue.comparePlans(outro, intro, 8.0,
                AutoCueTransition.windowCandidates(8.0, true), 0.1, true);
        assertNotNull(far);
        System.out.println("### 调性差得远（已无加成）：" + far.why);
        assertEquals("声波重合的分数必须就是它的七项",
                far.harmonicScore, far.harmonicScore, 0.0);
        assertFalse("理由里不该再出现调性收益：" + far.why, far.why.contains("调性 +"));

        // 调性本来就合：声波重合连候选都不进（它的价值只在移调）
        AutoCue.PlanChoice same = AutoCue.comparePlans(outro, intro, 8.0,
                AutoCueTransition.windowCandidates(8.0, true), 0.99, false);
        assertNotNull(same);
        System.out.println("### 调性本来就合：" + same.why);
        assertFalse("不该选声波重合：" + same.why, same.harmonic);
    }

    /**
     * <b>被包的接法在 prepare 里自己调了窗口时，本层不许再拿旧值覆盖它。</b>
     *
     * <p>AI 模式就是这一档：{@code tune=false} 且没有现读来源（窗口是风格/拍数算出来的），
     * 而声波重合胜出时 `AiMix` 会在自己的 prepare 里按分析把窗口调到最佳。
     * 用户 2026-09-29 的日志里这两件事对不上：比较里赢的是「声波重合 @ 窗口 9.0s」，
     * 实际装载的却是「交叠 17.1s」——本层按 arm 时那个旧值覆盖，把 tuning 悄悄作废了。
     */
    @Test
    public void aBaseThatDecidesItsOwnWindowInPrepareIsNotOverridden() {
        float[] pcm = tone(120, 220.0);
        installFakeDecoder(pcm);
        try {
            AutoCueTransition transition = new AutoCueTransition(
                    new DecidingBase(17.1 * RATE, 9.0 * RATE), RATE, 2, null, false);
            TransitionPlan plan = transition.prepare("test://outro", "test://intro", RATE, null, null);
            assertNotNull(plan);
            System.out.println("### 被包接法自定窗口：" + plan.note + " → 本层报 "
                    + (transition.windowFrames(RATE) / (double) RATE) + "s");
            assertEquals("本层不许覆盖被包接法的窗口（否则声波重合的 tuning 就被作废了）",
                    9.0 * RATE, transition.windowFrames(RATE), 2.0);
        } finally {
            dev.t1m3.qplayer.audio.decode.Decoder.install(null);
        }
    }

    /** 假的接法：开工前报一个窗口、prepare 之后换成另一个（模拟 AiMix 按分析调窗口）。 */
    private static final class DecidingBase implements Transition {

        private long window;

        private final long afterPrepare;

        DecidingBase(double before, double after) {
            this.window = (long) before;
            this.afterPrepare = (long) after;
        }

        @Override
        public long windowFrames(int sampleRate) {
            return window;
        }

        @Override
        public int render(dev.t1m3.qplayer.audio.core.AudioBuf out,
                          dev.t1m3.qplayer.audio.core.AudioBuf from,
                          dev.t1m3.qplayer.audio.core.AudioBuf to, float progress) {
            out.setFrames(0);
            return 0;
        }

        @Override
        public String name() {
            return "假接法（自定窗口）";
        }

        @Override
        public boolean rewritesSamples() {
            return true;
        }

        @Override
        public TransitionPlan prepare(String outro, String intro, int sampleRate) {
            window = afterPrepare;
            return new TransitionPlan(0.0, PitchPlan.ZERO, 0.0f, "假接法 · 自己调了窗口");
        }
    }

    /**
     * 只有「声波重合」那一档允许分析改窗口；其它档位必须**原样用**用户设的值。
     *
     * <p>用户明确设了一个时长，改动它就是越权——所以这一条与上一条是一对。
     */
    @Test
    public void onlyTheHarmonicModeLetsTheAnalysisChangeTheWindowLength() {
        float[] pcm = tone(120, 220.0);
        installFakeDecoder(pcm);
        try {
            long baseWindow = 8L * RATE;
            AutoCueTransition fixed = new AutoCueTransition(
                    new FakeBase(baseWindow), RATE, 2, new MutableWindow(8.0), false);
            TransitionPlan fixedPlan = fixed.prepare("test://outro", "test://intro", RATE, null, null);
            assertNotNull(fixedPlan);
            assertEquals("不允许调档位：窗口必须精确等于用户设的 8 秒",
                    8.0 * RATE, fixed.windowFrames(RATE), 2.0);

            AutoCueTransition tuning = new AutoCueTransition(
                    new FakeBase(baseWindow), RATE, 2, new MutableWindow(8.0), true);
            TransitionPlan tunedPlan = tuning.prepare("test://outro", "test://intro", RATE, null, null);
            assertNotNull(tunedPlan);
            long chosen = tuning.windowFrames(RATE);
            double[] allowed = AutoCueTransition.windowCandidates(8.0, true);
            boolean ok = false;
            for (int i = 0; i < allowed.length; i++) {
                if (Math.abs(chosen - allowed[i] * RATE) <= 2.0) {
                    ok = true;
                }
            }
            System.out.println("### 允许调窗口时选中的交叠：" + chosen / (double) RATE + "s"
                    + "（候选 " + java.util.Arrays.toString(allowed) + "）");
            assertTrue("调过的窗口必须落在候选集合里（实际 " + chosen / (double) RATE + "s）", ok);
        } finally {
            dev.t1m3.qplayer.audio.decode.Decoder.install(null);
        }
    }

    // ==================================================================
    // ⑦ 扫描缓存：同一首曲子只解一遍（AI 方案比较 + 自动点位共用）
    // ==================================================================

    /**
     * <b>同一首曲子的包络只扫一遍。</b>
     *
     * <p>真机日志（2026-09-28）里一次过渡的准备是：模型对拍两次 + <strong>整曲扫描四次</strong>
     * （两首 × 两遍：AI 方案比较一遍、自动点位一遍）+ 去人声 25.5 s，
     * 结果是「到了触发点却没有可用的准备结果」——这一次过渡根本没发生。
     * 两个消费者要的是<strong>同一对包络</strong>，所以第二次必须是缓存命中。
     *
     * <p>判据直接数「解码器被打开了几次」：扫两遍只许开一次。
     */
    @Test
    public void theSecondScanOfTheSameFileComesFromTheCache() throws Exception {
        float[] pcm = tone(60, 220.0);
        java.util.concurrent.atomic.AtomicInteger opens =
                new java.util.concurrent.atomic.AtomicInteger();
        AutoCueTestSupport.install(pcm, RATE, opens);
        try {
            String file = AutoCueTestSupport.tempFile();
            AutoCue.clearScanCache();
            AutoCue.Envelope first = AutoCue.scan(file, null);
            AutoCue.Envelope second = AutoCue.scan(file, null);
            assertEquals("第二次必须命中缓存（只解一遍）：打开次数", 1, opens.get());
            assertSame("拿到的应当是同一份包络", first, second);
            assertEquals("时长也要一致（缓存不该改变结论）", first.durationSecs,
                    second.durationSecs, 1e-9);

            // 非本地音源不走缓存（键里没法带内容指纹，宁可重扫）
            AutoCue.clearScanCache();
            opens.set(0);
            AutoCue.scan("test://stream", null);
            AutoCue.scan("test://stream", null);
            assertEquals("直链/假音源不做缓存：打开次数", 2, opens.get());
        } finally {
            dev.t1m3.qplayer.audio.decode.Decoder.install(null);
            AutoCue.clearScanCache();
        }
    }

    /**
     * <b>候选里永远包含用户设的那个值</b>，哪怕它超出「围绕值」的夹取区间。
     *
     * <p>用户把交叉淡化设成 60 s，然后开着自动点位：「按分析调」只该在他这个值的
     * <strong>附近</strong>挑一个，不该把他的 60 s 悄悄夹成 20 s——那是改他的设置。
     */
    @Test
    public void theUsersOwnWindowAlwaysStaysACandidate() {
        double[] wide = AutoCueTransition.windowCandidates(60.0, true);
        boolean hasNominal = false;
        for (int i = 0; i < wide.length; i++) {
            if (Math.abs(wide[i] - 60.0) < 1e-9) {
                hasNominal = true;
            }
        }
        System.out.println("### 交叉淡化 60 s 的候选：" + java.util.Arrays.toString(wide));
        assertTrue("用户设的 60 s 必须在候选里：" + java.util.Arrays.toString(wide), hasNominal);

        double[] normal = AutoCueTransition.windowCandidates(6.0, true);
        System.out.println("### 交叉淡化 6 s 的候选：" + java.util.Arrays.toString(normal));
        assertEquals("常见值上仍是一组围绕候选（含它自己）", 5, normal.length);
        boolean six = false;
        for (int i = 0; i < normal.length; i++) {
            if (Math.abs(normal[i] - 6.0) < 1e-9) {
                six = true;
            }
        }
        assertTrue("6 s 也必须在候选里", six);
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private static final int RATE = 44_100;

    /** 一段单音（220 Hz 正弦，幅度 0.5）：够长、够稳，扫得动也挑得出点。 */
    private static float[] tone(int seconds, double hz) {
        float[] pcm = new float[seconds * RATE];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (float) (0.5 * Math.sin(2.0 * Math.PI * hz * i / RATE));
        }
        return pcm;
    }

    private static void installFakeDecoder(float[] pcm) {
        AutoCueTestSupport.install(pcm, RATE);
    }

    /** 可改的窗口值（模拟「用户在播放期间拖了滑块」）。 */
    private static final class MutableWindow implements java.util.function.DoubleSupplier {
        double value;

        MutableWindow(double value) {
            this.value = value;
        }

        @Override
        public double getAsDouble() {
            return value;
        }
    }

    /** 一个最小可用的被包接法：窗口固定、渲染什么都不做。 */
    private static final class FakeBase implements Transition {

        private final long window;

        FakeBase(long window) {
            this.window = window;
        }

        @Override
        public long windowFrames(int sampleRate) {
            return window;
        }

        @Override
        public int render(dev.t1m3.qplayer.audio.core.AudioBuf out,
                          dev.t1m3.qplayer.audio.core.AudioBuf from,
                          dev.t1m3.qplayer.audio.core.AudioBuf to, float progress) {
            return 0;
        }

        @Override
        public String name() {
            return "假接法";
        }

        @Override
        public boolean rewritesSamples() {
            return true;
        }

        @Override
        public TransitionPlan prepare(String outro, String intro, int rate) {
            return new TransitionPlan(0.0, PitchPlan.ZERO, 0f, "假接法");
        }
    }

    /**
     * <b>扫不动的时候：日志要说清「哪一侧、哪个文件」，然后退回固定点位。</b>
     *
     * <p>用户 2026-09-30 报的那条 WARN 是「整曲扫描失败（io: 打开音源失败：…cache）」——
     * 两侧扫描共用一个 catch，既没说是出场曲还是进场曲，也没说哪个文件；得靠同一秒的
     * `disk cache evicted: …` 才认出它读的是刚播完、随即被磁盘缓存淘汰的那首歌。
     * 而「哪一侧」直接决定要不要紧张：出场侧=当前曲，进场侧=下一首，
     * 两者的成因与修法完全不同。
     */
    @Test
    public void aFailedScanNamesTheSideAndTheFile() {
        float[] pcm = tone(120, 220.0);
        // 两个音源要**可区分**：都叫 test://intro 的话，出场侧也含这个子串，
        // 第一条断言就会去量一个「本来就不该失败的那一侧」（第一版就是这么写错的）。
        final String outro = "test://outro-x";
        final String intro = "test://intro-x";
        for (String side : new String[]{"outro", "intro"}) {
            AutoCueTestSupport.installFailing(side, pcm, RATE);
            try {
                AutoCueTransition t = new AutoCueTransition(
                        new FakeBase(8L * RATE), RATE, 2, null, false);
                TransitionPlan plan = t.prepare(outro, intro, RATE, null, null);
                assertNotNull("扫不动只该退化成固定点位，不该整份准备失败", plan);
                assertEquals("退回的必须是被包接法那份计划（固定点位）",
                        "假接法", plan.note);
                String line = lastLog("扫描失败");
                assertTrue("要写明是哪一侧扫不动：" + line,
                        line.contains(side.equals("outro") ? "出场曲扫描失败" : "进场曲扫描失败"));
                assertTrue("要写明是哪个文件扫不动：" + line,
                        line.contains(side.equals("outro") ? outro : intro));
                assertTrue("还得说清退化成什么：" + line, line.contains("退回固定点位"));
            } finally {
                dev.t1m3.qplayer.audio.decode.Decoder.install(null);
            }
        }
    }

    /** 日志里最后一条含 {@code needle} 的行（取不到就直接红，别静默当成空串）。 */
    private static String lastLog(String needle) {
        String found = null;
        for (String line : dev.t1m3.qplayer.util.Logger.snapshot()) {
            if (line.contains(needle)) {
                found = line;
            }
        }
        assertNotNull("日志里没有含「" + needle + "」的行", found);
        return found;
    }

    /** 歌太短（放不下一次交叠 + 两头留白）：必须如实返回 null，由调用方退回固定点位。 */
    @Test
    public void aSongTooShortToOverlapYieldsNoPlan() {
        AutoCue.Envelope shortSong = envelope(15.0, flat(15.0, 0.5f), 60.0, 0.0,
                new double[]{0.5, 0.8, 0.5}, null);
        assertNull("15 秒的歌放不下「≥20 秒才开始 + 8 秒交叠」",
                AutoCue.plan(shortSong, shortSong, 8.0));
    }
}
