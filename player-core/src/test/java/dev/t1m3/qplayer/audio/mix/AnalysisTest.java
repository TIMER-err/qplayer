package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 拍点与节奏分析的对齐测试：对应 Sonalis {@code sonalis-mix/src/analysis.rs} 的 {@code mod tests}。
 *
 * <p>上游 15 条测试逐条对应到本类的 15 个方法（名字按 camelCase 映射），
 * 断言强度保持一致；末尾另有两<strong>补充</strong>测试（上游没有），把
 * 「起始包络的时间归属修正」这条算法规范钉成可执行的证据——上游只在两条相位测试里
 * 覆盖了修正<strong>之后</strong>的结果，没覆盖「修正量本身、它的量级、它的方向」。
 *
 * <h3>容差说明</h3>
 *
 * <p>本类里所有容差都直接照抄上游（4 帧 / 4 BPM / 6 BPM / 8 BPM / 1 个 HOP / 2 样本 /
 * 0.2 置信度 / 1024 采样），<strong>没有为了「跑得过」而放宽</strong>：
 * 上游这些容差本来就留得宽（都是给不同整数延迟、不同起点偏移留的余量），
 * Java 的 {@code Math.sin}/{@code Math.cos}/{@code Math.exp}/{@code Math.log}
 * 与 Rust 的 {@code f32} 超越函数最多差 1 ulp，落进这些容差里绰绰有余。
 * 唯一用零容差的是 {@code monoFoldAveragesChannels}（合成数据，(1+3)/2 在 f32 下精确）
 * 与两条补充测试里的 {@code ENV_CENTER_OFFSET}——那是个能精确表示的常量（3.5），
 * 不存在 ulp 问题。
 */
public class AnalysisTest {

    /** 上游测试模块里的 {@code const RATE: u32 = 48_000;}。 */
    private static final int RATE = 48_000;

    /**
     * 造一段带拍点的信号：每 {@code bpm} 拍一个衰减脉冲 + 一点底噪。
     *
     * <p>用脉冲而不是真实音乐，是因为测试要的是<strong>可推导的答案</strong>——
     * BPM 已知，正确的估计就该是它。
     *
     * <p>逐位对应上游：噪声用同一个 LCG（{@code state.wrapping_mul(1_664_525)
     * .wrapping_add(1_013_904_223)}，Java 的 int 溢出就是同一套回绕补码运算，
     * {@code (state >> 16) as f32} 里的 {@code state} 是 u32，所以对应 Java 的
     * {@code >>>}）；脉冲的指数衰减与正弦也都按 f32 算完再累加。
     */
    private static float[] clickTrack(float bpm, double secs, double offsetSecs) {
        int n = (int) ((double) RATE * secs);
        float[] v = new float[n];
        int period = (int) ((double) RATE * 60.0 / (double) bpm);
        int offset = (int) ((double) RATE * offsetSecs);

        int state = 0x2545_F491;
        for (int s = 0; s < n; s++) {
            state = state * 1_664_525 + 1_013_904_223;
            v[s] = (float) (state >>> 16) / 32768.0f * 0.002f - 0.001f; // 很轻的底噪
        }

        int t = offset;
        while (t < n) {
            // 一个短的宽带脉冲，带指数衰减
            int len = (int) ((float) RATE * 0.03f);
            for (int k = 0; k < len; k++) {
                if (t + k >= n) {
                    break;
                }
                float env = (float) Math.exp((double) (-((float) k) / ((float) RATE * 0.006f)));
                float noise = (float) Math.sin((double) ((float) (t + k) * 12_989.0f));
                v[t + k] += env * noise * 0.5f;
            }
            t += period;
        }
        return v;
    }

    /**
     * 环形距离：相位是<strong>模周期</strong>的量，直接相减会把「差一点点」和
     * 「差了将近一整个周期」混为一谈。
     */
    private static long ringDistance(long a, long b, long period) {
        long d = Math.floorMod(a - b, period);
        return Math.min(d, period - d);
    }

    /** 带符号的环形距离（正 = 偏晚，负 = 偏早），落在 {@code [-period/2, period/2)}。 */
    private static long signedDistance(long a, long b, long period) {
        return Math.floorMod(a - b + period / 2, period) - period / 2;
    }

    /** 把包络帧坐标换算成采样位置（`帧 * hop`），与上游 `(x * o.hop as f32) as i64` 一致。 */
    private static long envFramesToSamples(float frames, int hop) {
        return (long) (frames * (float) hop);
    }

    @Test
    public void onsetEnvelopeHasPeaksAtClicks() {
        float[] pcm = clickTrack(120.0f, 6.0, 0.0);
        Analysis.Onsets o = Analysis.onsetEnvelope(pcm, RATE);
        assertFalse("应当产出起始包络", o.isEmpty());

        // 120 BPM = 每 0.5 s 一次 = 每 24000 样本一次 = 每 46.875 帧一次
        // 峰值应当出现在 46.875 的整数倍附近。
        // 上游用 max_by，它在并列最大时返回**最后**一个，所以这里用 >= 而不是 >。
        int peak = 0;
        for (int i = 1; i < o.envelope.length; i++) {
            if (o.envelope[i] >= o.envelope[peak]) {
                peak = i;
            }
        }
        float period = o.frameRate(RATE) * 0.5f; // 帧
        float rem = (float) peak % period;
        float dist = Math.min(rem, period - rem);
        assertTrue("最强峰在第 " + peak + " 帧，距 120 BPM 的拍栅格（周期 " + period
                        + " 帧）有 " + dist + " 帧",
                dist < 4.0f);
    }

    /** 起点偏移不应该改变 BPM 估计，只应该改变相位。 */
    @Test
    public void tempoIsInsensitiveToTheStartingOffset() {
        Analysis.Tempo a = Analysis.analyze(clickTrack(120.0f, 8.0, 0.0), RATE);
        Analysis.Tempo b = Analysis.analyze(clickTrack(120.0f, 8.0, 0.17), RATE);
        assertNotNull("应有结论", a);
        assertNotNull("应有结论", b);
        assertTrue("起点偏移不该改变 BPM：" + a.bpm + " vs " + b.bpm,
                Math.abs(a.bpm - b.bpm) < 4.0f);
    }

    /** 120 BPM 的节拍必须被估成 120（而不是 60 或 240）。 */
    @Test
    public void detects120Bpm() {
        Analysis.Tempo t = Analysis.analyze(clickTrack(120.0f, 10.0, 0.0), RATE);
        assertNotNull("应有结论", t);
        assertTrue("应估成 120 BPM，实际 " + t.bpm + "（" + t.describe() + "）",
                Math.abs(t.bpm - 120.0f) < 6.0f);
        assertTrue("置信度偏低：" + t.confidence, t.confidence > 0.2f);
    }

    /** 100 BPM 也必须对——只测一个速度会漏掉倍速错误。 */
    @Test
    public void detects100Bpm() {
        Analysis.Tempo t = Analysis.analyze(clickTrack(100.0f, 12.0, 0.0), RATE);
        assertNotNull("应有结论", t);
        assertTrue("应估成 100 BPM，实际 " + t.bpm + "（" + t.describe() + "）",
                Math.abs(t.bpm - 100.0f) < 6.0f);
    }

    /** 140 BPM：验证高速区间，也验证先验没有过度偏向 120。 */
    @Test
    public void detects140Bpm() {
        Analysis.Tempo t = Analysis.analyze(clickTrack(140.0f, 10.0, 0.0), RATE);
        assertNotNull("应有结论", t);
        assertTrue("应估成 140 BPM，实际 " + t.bpm + "（" + t.describe() + "）",
                Math.abs(t.bpm - 140.0f) < 8.0f);
    }

    // ==================================================================
    // 硬算 = Melodify 的 BeatAnalyzer（照搬）
    // ==================================================================

    /**
     * <b>硬算的读数表：70–180 BPM 逐个对一遍。</b>
     *
     * <p>用户 2026-09-30 的要求：「硬算 bpm 你直接照搬 Melodify 的，你现在这个太不准了
     * 如果不开 ai」。这张表就是那次改动的判据（实测值直接写在断言里）：
     *
     * <table>
     *   <tr><th>真实</th><th>旧（频谱通量 + 120 先验）</th><th>新（Melodify）</th><th>新相位误差</th></tr>
     *   <tr><td>90</td><td>90.73</td><td><b>90.00</b></td><td>6.7 ms</td></tr>
     *   <tr><td>120</td><td>119.89</td><td><b>120.00</b></td><td>6.7 ms</td></tr>
     *   <tr><td>150</td><td>149.99</td><td><b>150.03</b></td><td>6.7 ms</td></tr>
     *   <tr><td>180</td><td>180.43</td><td><b>179.92</b></td><td>1.3 ms</td></tr>
     * </table>
     *
     * <p>容差写成 ±1 BPM 而不是「更松」：这张表的用处正是「量得准不准」，
     * 放宽容差它就白写了。相位容差一个分析格（11.6 ms）＋一点余量。
     */
    @Test
    public void theComputedTempoIsTheMelodifyEstimator() {
        float[] bpms = {70.0f, 75.0f, 90.0f, 100.0f, 120.0f, 128.0f, 140.0f, 150.0f, 180.0f};
        for (float bpm : bpms) {
            double offsetSecs = 0.1;
            Analysis.Tempo t = Analysis.analyze(clickTrack(bpm, 20.0, offsetSecs), RATE);
            assertNotNull(bpm + " BPM 的素材测不出速度", t);
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[tempo] 真实 %.0f → 实测 %.2f BPM（周期 %.0f 帧，首拍 %.0f，置信度 %.2f）",
                    Float.valueOf(bpm), Float.valueOf(t.bpm), Float.valueOf(t.periodFrames),
                    Float.valueOf(t.phaseFrames), Float.valueOf(t.confidence)));
            assertEquals("BPM 读数不准：" + bpm + " → " + t.bpm, bpm, t.bpm, 1.0f);

            double truePeriod = 60.0 / bpm * RATE;
            double truth = (offsetSecs * RATE) % truePeriod;
            double d = Math.abs(Math.floorMod((long) t.phaseFrames - (long) truth,
                    (long) truePeriod));
            double errMs = Math.min(d, truePeriod - d) * 1000.0 / RATE;
            assertTrue("首拍偏离真实拍点 " + errMs + " ms（" + t.describe() + "）", errMs < 15.0);
        }
    }

    /**
     * <b>倍速消歧：150 BPM 不许报成 75，而真正的 75 也不许报成 150。</b>
     *
     * <p>这是照搬 Melodify 之后实测出来的坑：它的自相关选择只给整数延迟，
     * 而 150 BPM 在 48 kHz／跳 512 下的真实周期是 <b>37.5 格</b>——击打能量被摊进相邻两格，
     * 逐点相乘只有半高，于是「隔两拍」的整数格延迟（75 格）自相关反而更强，
     * 照搬那套把 150 报成 75.00（同一个素材上旧的先验实现报 149.99）。
     */
    @Test
    public void theOctaveTrapIsHandled() {
        assertEquals("150 BPM 被折成半速了", 150.0f,
                Analysis.analyze(clickTrack(150.0f, 20.0, 0.1), RATE).bpm, 1.0f);
        assertEquals("75 BPM 被折成倍速了", 75.0f,
                Analysis.analyze(clickTrack(75.0f, 20.0, 0.1), RATE).bpm, 1.0f);
    }

    /**
     * <b>搜索区间照搬 Melodify：70–180 BPM。</b>
     *
     * <p>超出去的速度会被自相关吸到它的倍速/半速上（200 → 100），这是照搬的一部分，
     * 不是笔误——本仓库原先那套是 60–200。写成判据，免得以后有人「顺手」把区间改回去。
     */
    @Test
    public void theSearchRangeIsMelodifyS() {
        Analysis.Tempo fast = Analysis.analyze(clickTrack(200.0f, 20.0, 0.1), RATE);
        assertNotNull(fast);
        assertTrue("200 BPM 在 70–180 之外，应当被吸到 100 左右，实测 " + fast.bpm,
                Math.abs(fast.bpm - 100.0f) < 2.0f);
        assertNull("60 BPM 也在区间之外：宁可不表态，也不报一个假的",
                Analysis.analyze(clickTrack(60.0f, 20.0, 0.1), RATE));
    }

    /**
     * 相位必须落在第一拍上：按它算出的拍点位置应当对上脉冲。
     *
     * <p>这条测试<strong>依赖</strong> {@link Analysis#ENV_CENTER_OFFSET} 那个时间归属
     * 修正：少了它，预测拍点会稳定偏早 1792 个采样（≈37 ms），这里 512 的容差立刻
     * 就兜不住了。见 {@code withoutTheOffsetThePhaseIsSystematicallyEarly}。
     */
    @Test
    public void phaseLandsOnTheFirstClick() {
        double offsetSecs = 0.31;
        float[] pcm = clickTrack(120.0f, 8.0, offsetSecs);
        Analysis.Tempo t = Analysis.analyze(pcm, RATE);
        assertNotNull("应有结论", t);

        long firstClick = (long) ((double) RATE * offsetSecs);
        long next = t.nextBeatAfter(firstClick - 1);
        long dist = Math.abs(next - firstClick);
        // 允许一个分析帧移的误差（包络的时间分辨率就是 hop）
        assertTrue("预测的拍点 " + next + " 与真实第一拍 " + firstClick + " 差 " + dist
                        + " 样本（" + t.describe() + "）",
                dist <= (long) Analysis.HOP);
    }

    /** {@code nextBeatAfter} 必须单调：拍点顺序不能乱。 */
    @Test
    public void beatIterationIsMonotonic() {
        Analysis.Tempo t = Analysis.analyze(clickTrack(120.0f, 8.0, 0.0), RATE);
        assertNotNull("应有结论", t);
        long cur = t.nextBeatAfter(0L);
        for (int i = 0; i < 20; i++) {
            long next = t.nextBeatAfter(cur + 1);
            assertTrue("拍点必须递增：" + cur + " → " + next, next > cur);
            long gap = next - cur;
            // 上游是 `t.period_frames as u64`（截断），这里同样截断
            long expect = (long) t.periodFrames;
            assertTrue("拍间距 " + gap + " 与周期 " + expect + " 不符",
                    Math.abs(gap - expect) <= 2);
            cur = next;
        }
    }

    /** 静音没有节拍——绝不能编一个 BPM 出来。 */
    @Test
    public void silenceYieldsNothing() {
        float[] s = new float[RATE * 10];
        assertNull("静音不该给出节奏结论", Analysis.analyze(s, RATE));
    }

    /** 常量直流没有拍——去均值之后应当什么都不剩。 */
    @Test
    public void dcYieldsNothing() {
        float[] s = new float[RATE * 10];
        Arrays.fill(s, 0.5f);
        assertNull("纯直流不该给出节奏结论", Analysis.analyze(s, RATE));
    }

    /** 太短的素材不该给出结论（样本不足，结论不稳）。 */
    @Test
    public void tooShortYieldsNothing() {
        float[] s = new float[1000];
        Arrays.fill(s, 0.1f);
        assertNull(Analysis.analyze(s, RATE));
        assertTrue(Analysis.onsetEnvelope(s, RATE).isEmpty());
    }

    @Test
    public void zeroRateIsHandled() {
        float[] s = new float[10_000];
        Arrays.fill(s, 0.1f);
        assertNull(Analysis.analyze(s, 0));
        assertTrue(Analysis.onsetEnvelope(s, 0).isEmpty());
    }

    /** 速度先验必须在 120 附近最大，并随偏离单调下降。 */
    @Test
    public void tempoPriorPeaksAtCenter() {
        float c = Analysis.tempoPrior(120.0f);
        assertTrue("60 BPM 的先验应更低", c > Analysis.tempoPrior(60.0f));
        assertTrue("240 BPM 的先验应更低", c > Analysis.tempoPrior(240.0f));
        assertTrue("应随偏离单调下降", Analysis.tempoPrior(110.0f) > Analysis.tempoPrior(80.0f));
        // 先验不能极端到把答案锁死：100 BPM 的权重仍应有意义
        assertTrue("100 BPM 的先验 " + Analysis.tempoPrior(100.0f) + " 太弱，会把正确的速度压掉",
                Analysis.tempoPrior(100.0f) > 0.5f);
    }

    /** 折叠相位必须比「取第一个峰」稳健：弱起的前置音符不该抢走相位。 */
    @Test
    public void phaseUsesFoldedSumNotFirstPeak() {
        // 先放一个很轻的孤立音符，再放规律的强拍
        float[] pcm = new float[RATE * 8];
        int period = (int) ((float) RATE * 0.5f);
        int start = 1571; // 任意的相位
        int t = start;
        while (t + 1000 < pcm.length) {
            for (int k = 0; k < 1000; k++) {
                float e = (float) Math.exp((double) (-((float) k) / 200.0f));
                pcm[t + k] += e * (float) Math.sin((double) ((float) k * 0.7f)) * 0.6f;
            }
            t += period;
        }
        // 一个又轻又早的孤立事件
        for (int k = 0; k < 200; k++) {
            pcm[100 + k] += (float) Math.exp((double) (-((float) k) / 40.0f)) * 0.02f;
        }

        Analysis.Onsets o = Analysis.onsetEnvelope(pcm, RATE);
        float envPeriod = o.frameRate(RATE) * 0.5f;
        long periodSamples = (long) (envPeriod * (float) o.hop);
        float ph = Analysis.beatPhase(o.envelope, envPeriod);

        // 相位是**模周期**的量，所以必须按环形距离比较——
        // 直接相减会把「差一点点」和「差了将近一整个周期」混为一谈。
        // 上游的 rem_euclid 对应 Java 的 Math.floorMod（两者对负数都给非负余数）。
        long phSamples = Math.floorMod(envFramesToSamples(ph, o.hop), periodSamples);
        long d = Math.floorMod(phSamples - (long) start, periodSamples);
        long dist = Math.min(d, periodSamples - d);
        assertTrue("相位 " + phSamples + " 应贴近真实的拍点 " + start + "，实际环形距离 " + dist,
                dist <= (long) (Analysis.HOP * 2));
    }

    @Test
    public void monoFoldAveragesChannels() {
        float[] a = {1.0f, 2.0f, 3.0f};
        float[] b = {3.0f, 2.0f, 1.0f};
        float[] m = Analysis.foldToMono(new float[][]{a, b});
        assertArrayEquals("两声道应取平均", new float[]{2.0f, 2.0f, 2.0f}, m, 0.0f);

        // 单声道应当直接返回，而不是被除以 1 之后变成副本
        assertArrayEquals("单声道应原样返回", new float[]{1.0f, 2.0f, 3.0f},
                Analysis.foldToMono(new float[][]{a}), 0.0f);
        assertTrue("空输入应给出空结果",
                Analysis.foldToMono(new float[0][]).length == 0);
    }

    @Test
    public void describeIsReadable() {
        Analysis.Tempo t = new Analysis.Tempo(128.0f, 22_500.0f, 100.0f, 0.8f);
        String s = t.describe();
        assertTrue(s, s.contains("128"));
        assertTrue(s, s.contains("BPM"));
    }

    // ------------------------------------------------------------------
    // 以下两条是补充测试（上游没有）：把「起始包络的时间归属修正」这条
    // 算法规范钉成可执行的证据。它是拍点对拍的立论基础，不能只写在注释里。
    // ------------------------------------------------------------------

    /**
     * 修正量必须是 3.5 个包络帧，且确实是「前移」的量。
     *
     * <p>频谱通量在<strong>新能量第一次进入某帧的窗</strong>时最大（它拿本帧减上一帧），
     * 那一刻位于该帧起点之后 {@code FRAME − HOP} 到 {@code FRAME} 之间，取中点就是
     * {@code (2048 − 256) / 512 = 3.5} 帧；在 48 kHz / hop 512 下是 1792 个采样，
     * 也就是约 37 ms。
     *
     * <p>三条断言各自钉住一件事：常量<strong>精确值</strong>（不能是 3.0 或 4.0——
     * 那是「取区间端点」而不是中点）、<strong>毫秒量级</strong>（写死的数字一旦跟着
     * FRAME/HOP 漂移，这条就失败）、以及 {@code period <= 1} 的早退分支直接返回它
     * （上游行为，也是这个常量唯一「自己冒出来」的地方）。
     */
    @Test
    public void envelopeCenterOffsetIsThreeAndAHalfFrames() {
        assertEquals("(2048 − 256) / 512 = 3.5 个包络帧",
                3.5f, Analysis.ENV_CENTER_OFFSET, 0.0f);

        float ms = Analysis.ENV_CENTER_OFFSET * (float) Analysis.HOP / (float) RATE * 1000.0f;
        // 3.5 * 512 = 1792 采样；1792 / 48000 = 37.33 ms
        assertEquals("修正量在 48 kHz 下应约为 37 ms", 37.33f, ms, 0.05f);

        // period <= 1 时折叠没有意义，上游直接返回修正量本身
        assertEquals("period <= 1 的早退分支应返回修正量",
                3.5f, Analysis.beatPhase(new float[]{1.0f, 2.0f, 3.0f}, 1.0f), 0.0f);
    }

    /**
     * 没有这个修正，相位会<strong>系统性偏早约 37 ms</strong>——这里用同一条素材算出
     * 「修正后」与「修正前」两个相位，让偏早这件事变成可以量出来的差值。
     *
     * <p>为什么这条不能只靠注释：切点偏早一个固定量，听感上只是「总差一点」，
     * 而且换首曲子偏的量还不一样（取决于该曲真实的相位），排查起来极其费劲——
     * 没有一条会失败的测试守着，很容易在某次「顺手优化」里把
     * {@code best_off + ENV_CENTER_OFFSET} 写成 {@code best_off}。
     *
     * <p>修正前的相位 = 修正后的相位 − {@link Analysis#ENV_CENTER_OFFSET} 换算成采样
     * （{@code beat_phase} 返回的就是这个和）。实测（48 kHz、120 BPM、起点 0.31 s）：
     * 真实第一拍在 14880 采样处，修正后的相位是 15232（距它 352 采样，在容差内），
     * 减掉 1792 采样后落在 13440 —— 整整早 1440 个采样（≈30 ms）。
     * 对拍点来说这个量级直接等于「切错位置」。
     */
    @Test
    public void withoutTheOffsetThePhaseIsSystematicallyEarly() {
        double offsetSecs = 0.31;
        float[] pcm = clickTrack(120.0f, 8.0, offsetSecs);

        // 用的是**生产路径**给出的相位（analyze），不是拿理想周期 46.875 重算一遍
        // beat_phase：分析用的是抛物线插值细化后的周期（这里 24022.4 而不是 24000），
        // 而折叠搜索的粒度是 1/4 包络帧（128 采样），换个周期就可能换一个量化格。
        // 拿理想周期重算会得到 15488（距真实拍点 608 采样），那是测试自己的构造偏差，
        // 不是算法结论——生产路径是 15232（距 352 采样，在上游那条 512 采样的容差内）。
        Analysis.Tempo t = Analysis.analyze(pcm, RATE);
        assertNotNull("应有结论", t);
        Analysis.Onsets o = Analysis.onsetEnvelope(pcm, RATE);

        long periodSamples = (long) (o.frameRate(RATE) * 0.5f * (float) o.hop);
        long truth = Math.floorMod((long) ((double) RATE * offsetSecs), periodSamples);
        long offsetSamples = envFramesToSamples(Analysis.ENV_CENTER_OFFSET, o.hop);

        // beat_phase 返回的是 `best_off + ENV_CENTER_OFFSET`，所以减掉它就能还原
        // 「不做时间归属修正」时的相位。
        long corrected = Math.floorMod((long) t.phaseFrames, periodSamples);
        long uncorrected = Math.floorMod(corrected - offsetSamples, periodSamples);

        long dCorrected = ringDistance(corrected, truth, periodSamples);
        long dUncorrected = ringDistance(uncorrected, truth, periodSamples);
        long signedUncorrected = signedDistance(uncorrected, truth, periodSamples);

        assertTrue("带修正的相位 " + corrected + " 应贴近真实第一拍 " + truth + "（环形距离 "
                        + dCorrected + "，上游对同一条素材的容差是一个 HOP = "
                        + Analysis.HOP + " 采样）",
                dCorrected <= (long) Analysis.HOP);
        assertTrue("去掉修正后相位落在 " + uncorrected + "，在真实拍点**之前**（有符号距离 "
                        + signedUncorrected + " 采样）——这正是「系统性偏早」",
                signedUncorrected < 0);
        // 偏早量 ≈ ENV_CENTER_OFFSET * HOP = 1792 采样 ≈ 37 ms（上下浮动 dCorrected）。
        // 容差取两个 HOP（1024）：理论下界是 1792 − 512 = 1280，所以这条离边界还远。
        assertTrue("偏早量应接近 " + offsetSamples + " 采样（≈37 ms），实测环形距离 "
                        + dUncorrected,
                dUncorrected > (long) (Analysis.HOP * 2));
        assertTrue("不修正应当明显比修正后差：偏早 " + dUncorrected + " 对 " + dCorrected,
                dUncorrected > dCorrected + (long) Analysis.HOP);
    }
}
