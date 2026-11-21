package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>出场曲的降速斜坡（beatmatch ramp）</b>——四条判据，每条都对着实测目标。
 *
 * <p>背景（取证报告 {@code tools/automix-forensics/APPLE_AUTOMIX_FORENSIC_REPORT.md}）：
 * Apple 在 skydive → 春めく 上对<b>出场曲</b>做了保音高的<b>渐进</b>降速——平均速率
 * <b>0.978~0.982</b>、坡道末端瞬时速率 <b>0.963 ± 0.01</b>、分数半音扫描 <b>+0.00</b>
 * （保音高），单侧淡化 <b>2.4~2.8 s</b>。我们当时是"完全不变速"，窗口是 7.5 s（Apple 的三倍）。
 *
 * <p>这一层（{@link TempoRamp} + {@link TempoBridge#stretchRamp}）要做到的就是那两件事，
 * 所以判据直接钉那四个读数，而不是"函数返回了某个值"：
 *
 * <ol>
 *   <li>{@link #theRampIsMonotoneAndStopsWhereAppleStopped()}：<b>速率曲线单调下降</b>、
 *       末端落在实测带里、窗口落在 2.4~2.8 s，且<b>只减不增</b>（用户/风格值只是上界）；</li>
 *   <li>{@link #theWindowCapFollowsTheBpmGap()}：窗口是<b>按 BPM 差</b>推出来的
 *       （差越大越长），拍长差进听阈以内就完全不动；</li>
 *   <li>{@link #theRenderedMaterialReallySlowsDownInTheWindowBytByt()}：
 *       <b>渲染出来的 PCM</b> 真的在窗口里单调降速、末端速率落在目标上
 *       （点击轨测位置，比读计划硬）；</li>
 *   <li>{@link #theRampKeepsThePitchLikeWsolaDoes()}：<b>保音高</b>——纯 WSOLA，
 *       不是重采样（正弦过零率不变，且明确不是"重采样会给的那个数"）。</li>
 * </ol>
 */
public class AutoCueTempoRampTest {

    private static final int RATE = 48000;

    /** 参考对：skydive 127.9999 BPM / 春めく 119.9702 BPM（引擎自己扫出来的）。 */
    private static final double RATIO = 119.9702 / 127.9999;

    // ==================================================================
    // ① 速率曲线与窗口（纯算术，参考对）
    // ==================================================================

    /**
     * <b>参考对上：窗口 2.4~2.8 s、速率 1.000 单调降到 0.963 附近。</b>
     *
     * <p>两个读数直接对着 Apple 的实测带：<b>平均 0.978~0.982</b>、
     * <b>末端 0.963 ± 0.01</b>。窗口对着它实测的单侧淡化 2.4~2.8 s。
     */
    @Test
    public void theRampIsMonotoneAndStopsWhereAppleStopped() {
        TempoRamp ramp = TempoRamp.derive(RATIO, 7.4913);
        assertTrue("参考对上必须出手（差 6.3% 远大于听阈）", ramp.active);

        System.out.println(String.format(java.util.Locale.ROOT,
                "[ramp] 窗口 %.4f s（用户值 %.2f s）· 1.0000 → %.4f×（平均 %.4f×，吸收 %.0f%% 的差）",
                Double.valueOf(ramp.windowSecs), Double.valueOf(ramp.userWindowSecs),
                Double.valueOf(ramp.endSpeedFor(ramp.windowSecs)),
                Double.valueOf(ramp.meanSpeedFor(ramp.windowSecs)),
                Double.valueOf(100.0 * ramp.absorbedFraction(ramp.windowSecs))));

        assertTrue("窗口应当落在 Apple 实测的单侧淡化带里（2.4~2.8 s），实际 "
                + ramp.windowSecs, ramp.windowSecs >= 2.4 && ramp.windowSecs <= 2.8);
        assertTrue("窗口只许**缩短**（用户/风格给的 7.49 s 是上界），实际 "
                        + ramp.windowSecs,
                ramp.windowSecs <= ramp.userWindowSecs + 1e-9);

        // 速率曲线：起点 1.000、严格单调下降、末端 = 报告的实测值
        double window = ramp.windowSecs;
        assertEquals("窗口起点必须与自然播放同速（否则接缝处是个速率阶跃）",
                1.0, ramp.speedAt(window, 0.0), 1e-12);
        double prev = Double.MAX_VALUE;
        for (int i = 0; i <= 200; i++) {
            double s = ramp.speedAt(window, window * i / 200.0);
            assertTrue("速率必须单调下降（第 " + i + " 个采样点 " + s
                    + " 不小于前一个 " + prev + "）", s < prev + 1e-12);
            prev = s;
        }
        double end = ramp.endSpeedFor(window);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[ramp] 末端瞬时速率 %.4f（Apple 实测 0.963 ± 0.01）· 平均 %.4f"
                        + "（Apple 实测 0.978~0.982）",
                Double.valueOf(end), Double.valueOf(ramp.meanSpeedFor(window))));
        assertTrue("末端瞬时速率应当落在 Apple 实测带里（0.963 ± 0.01），实际 " + end,
                Math.abs(end - 0.963) <= 0.01);
        double mean = ramp.meanSpeedFor(window);
        assertTrue("平均速率应当落在 Apple 实测带里（0.978~0.982），实际 " + mean,
                mean >= 0.978 && mean <= 0.982);

        // 两个方向都要成立：不许降到比进场曲还慢（那是"重塑"，不是"对齐"），
        // 也不许把斜率上限踩过去。
        assertTrue("末端速率不该低于进场曲的速率 " + RATIO, end >= RATIO - 1e-12);
        assertTrue("速率变化必须落在斜率上限以内（|1−末端| ≤ SLEW_MAX × 窗口）",
                1.0 - end <= TempoRamp.SLEW_MAX * window + 1e-9);
    }

    /**
     * <b>窗口按 BPM 差定（差越大越长），而且只减不增。</b>
     *
     * <p>三种情形各自钉一件事：
     * <ul>
     *   <li>差越大 → 需要的 ramp 越长（同一条 {@code |1−比| / 斜率上限}）；</li>
     *   <li>用户/风格给的值比推导值<b>短</b>时不许把它拉长（上界就是上界）；</li>
     *   <li>拍长差落在听阈以内（{@code 比 × (1 + JND) ≥ 1}）→ <b>完全不动</b>，
     *       窗口保持原样（这正是"最小干预"那条纪律在门槛上的样子）。</li>
     * </ul>
     */
    @Test
    public void theWindowCapFollowsTheBpmGap() {
        TempoRamp small = TempoRamp.derive(0.96, 20.0);
        TempoRamp big = TempoRamp.derive(0.92, 20.0);
        assertTrue("差 4% 要出手", small.active);
        assertTrue("差 8% 要出手", big.active);
        assertTrue("差越大 → ramp 越长（4% 给 " + small.windowSecs + " s，8% 给 "
                        + big.windowSecs + " s）", big.windowSecs > small.windowSecs);
        assertEquals("窗口 = |1 − 比| / 斜率上限", 0.04 / TempoRamp.SLEW_MAX,
                small.windowSecs, 1e-9);
        assertEquals("窗口 = |1 − 比| / 斜率上限", 0.08 / TempoRamp.SLEW_MAX,
                big.windowSecs, 1e-9);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[ramp] 窗口 ∝ BPM 差：4%% → %.2f s，8%% → %.2f s（斜率上限 %.3f/s）",
                Double.valueOf(small.windowSecs), Double.valueOf(big.windowSecs),
                Double.valueOf(TempoRamp.SLEW_MAX)));

        TempoRamp capped = TempoRamp.derive(0.92, 1.0);
        assertEquals("用户/风格给的值是**上界**：比推导值短就按用户值来，不许拉长",
                1.0, capped.windowSecs, 1e-9);

        TempoRamp inside = TempoRamp.derive(1.0 - TempoRamp.TEMPO_JND * 0.5, 7.5);
        assertFalse("拍长差落到听阈以内就不该出手", inside.active);
        assertEquals("不动的时候窗口一个字节都不许改", 7.5, inside.windowSecs, 1e-9);
        System.out.println("[ramp] 差 1.35% 在听阈（2.7%）以内 → 不降速、窗口保持 7.5 s");

        // 候选集：给了上界就只在上界以内挑（分析层仍然可以挑更短的）
        double[] cands = AutoCueTransition.windowCandidates(7.4913, true, 2.5093);
        for (double c : cands) {
            assertTrue("候选 " + c + " 超过了降速斜坡给的上界", c <= 2.5093 + 1e-9);
        }
        assertTrue("上界本身必须留在候选里", contains(cands, 2.5093));
        double[] unramped = AutoCueTransition.windowCandidates(7.4913, true, 0.0);
        assertTrue("没有斜坡时必须逐位走原来那一份候选（含 1.5× / 2× 那两档）",
                contains(unramped, 7.4913 * 1.5) && contains(unramped, 7.4913 * 2.0));
    }

    private static boolean contains(double[] array, double v) {
        for (double x : array) {
            if (Math.abs(x - v) < 1e-9) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // ③ 渲染出来的 PCM 真的在窗口里单调降速
    // ==================================================================

    /**
     * <b>硬证据：拿渲染出来的 PCM 量速率曲线。</b>
     *
     * <p>做法是"已知答案"：合成一条<b>点击轨</b>（每 0.125 s 一下），用
     * {@link TempoBridge#stretchRamp} 按参考对的目标（1.0000 → 0.9626，窗口 2.5093 s）
     * 变速，然后<b>在输出里找每一击的位置</b>——每一击在源里的时刻是已知的，
     * 于是：
     *
     * <pre>
     *   第 k 击出现在输出的哪一刻  ← 由速率的积分决定（源时刻 = ∫speed）
     *   相邻两击的距离倒数 = 那一刻的**实际播放速率**
     * </pre>
     *
     * <p>断言三件事：实测速率<b>单调不增</b>、末端速率落在目标上（±1.5%）、
     * 窗口内的点击总时长与"平均速率"一致（±0.5%）——最后一条把"平均速率是两端均值"
     * 这句话也钉住了。
     *
     * <p>为什么不用"读计划"了事：计划里的数是<b>打算</b>怎么做，这一条量的是<b>做出来</b>
     * 怎么样——分段拼接、积分写错、帧数算错都会在这里露出来（而计划那边看不出来）。
     */
    @Test
    public void theRenderedMaterialReallySlowsDownInTheWindow() {
        // 目标不是写死的：从**计划**里取（参考对那一条），所以"把降速关掉"这一改
        // 会同时把这条 PCM 判据打红——它量的正是计划落到素材上的样子。
        TempoRamp ramp = TempoRamp.derive(RATIO, 7.4913);
        double windowSecs = ramp.windowSecs;
        double endSpeed = ramp.endSpeedFor(windowSecs);
        int windowFrames = (int) Math.round(windowSecs * RATE);
        double clickSecs = 0.125;
        int need = TempoBridge.requiredFramesRamp(windowFrames, RATE, 1.0, endSpeed);
        float[][] in = clickTrack(need, clickSecs);
        float[][] out = TempoBridge.stretchRamp(in, RATE, windowFrames, 1.0, endSpeed);
        assertEquals("输出帧数必须精确", windowFrames, out[0].length);

        List<Double> hits = clickTimes(out[0], clickSecs * 0.5);
        assertTrue("输出里应当找得到足够多的点击（实际 " + hits.size() + "）", hits.size() >= 12);

        List<Double> rates = new ArrayList<Double>();
        for (int i = 1; i < hits.size(); i++) {
            double gap = hits.get(i) - hits.get(i - 1);
            assertTrue("相邻点击的间隔必须是正数", gap > 0.0);
            rates.add(clickSecs / gap);
        }
        double worstStep = 0.0;
        for (int i = 1; i < rates.size(); i++) {
            worstStep = Math.max(worstStep, rates.get(i) - rates.get(i - 1));
        }
        System.out.println(String.format(java.util.Locale.ROOT,
                "[ramp] 渲染出来的 PCM：%d 击 · 首段速率 %.4f · 末段速率 %.4f · 最大回升 %.4f",
                Integer.valueOf(hits.size()), rates.get(0), rates.get(rates.size() - 1),
                Double.valueOf(worstStep)));
        assertTrue("速率必须单调不增（最大回升 " + worstStep + "）", worstStep < 0.004);
        assertTrue("窗口**第一段**的速率必须 ≈1.000（实际 " + rates.get(0) + "）——起点没有"
                        + "速率阶跃，这正是「渐变」相对「恒定变速」的全部意义：内容与速率同时接得上",
                Math.abs(rates.get(0) - 1.0) <= 0.012);
        double measuredEnd = rates.get(rates.size() - 1);
        assertTrue("末端实测速率 " + measuredEnd + " 应当落在目标 " + endSpeed
                + " 附近（±1.5%）", Math.abs(measuredEnd - endSpeed) <= endSpeed * 0.015);

        // 平均速率 = 窗口内的源素材长度 / 窗口长度（两端均值）
        double span = hits.get(hits.size() - 1) - hits.get(0);
        double measuredMean = clickSecs * (hits.size() - 1) / span;
        double wantMean = (1.0 + endSpeed) / 2.0;
        System.out.println(String.format(java.util.Locale.ROOT,
                "[ramp] 实测平均速率 %.5f（设计 %.5f）· 逐段速率 %s",
                Double.valueOf(measuredMean), Double.valueOf(wantMean),
                rates.size() > 8 ? String.format(java.util.Locale.ROOT,
                        "%.4f→%.4f", rates.get(0), rates.get(rates.size() - 1)) : "—"));
        assertTrue("实测平均速率 " + measuredMean + " 应当等于两端均值 " + wantMean,
                Math.abs(measuredMean - wantMean) <= wantMean * 0.005);

        // 与"计划的积分"逐点对账：第 m 击的源时刻 T_m 应当出现在输出的 ∫ 反函数处。
        // 检测偶尔会吃掉/重复一击（WSOLA 颗粒边界上真会），所以先用近似速率把每一击
        // 认到"整拍号 m"上，再对账——认不上的那些如实跳过并计数。
        int worstAt = -1;
        int skipped = 0;
        double worst = 0.0;
        for (int k = 0; k < hits.size(); k++) {
            double approxSource = hits.get(k) * wantMean;
            int m = (int) Math.round(approxSource / clickSecs);
            if (m <= 0) {
                continue;
            }
            if (k > 0 && Math.abs(m - (int) Math.round(hits.get(k - 1) * wantMean / clickSecs)) > 1) {
                skipped++;
            }
            double want = outputTimeForSource(ramp, windowSecs, m * clickSecs);
            double err = Math.abs(hits.get(k) - want);
            if (err > worst) {
                worst = err;
                worstAt = m;
            }
        }
        System.out.println(String.format(java.util.Locale.ROOT,
                "[ramp] 与积分曲线的最大对账误差 %.2f ms（第 %d 击；漏检/重复 %d 处）",
                Double.valueOf(worst * 1000.0), Integer.valueOf(worstAt), Integer.valueOf(skipped)));
        assertTrue("点击出现的位置必须与速率的积分一致（最大偏 " + worst * 1000.0 + " ms，"
                        + "第 " + worstAt + " 击）", worst <= 0.02);
    }

    /**
     * 源时刻 → 输出时刻：对 {@link TempoRamp#sourceSecsAt} 做二分（不在这里重写一遍积分，
     * 免得判据与实现各有一份"速率的积分"——同一个式子只能有一处）。
     */
    private static double outputTimeForSource(TempoRamp ramp, double window, double source) {
        double lo = 0.0;
        double hi = window;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2.0;
            if (ramp.sourceSecsAt(window, mid) < source) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2.0;
    }

    /** 一条点击轨：每 {@code clickSecs} 一下，宽 5 ms 的因果脉冲（带一点衰减，像鼓点）。 */
    private static float[][] clickTrack(int frames, double clickSecs) {
        float[][] out = new float[2][frames];
        int period = (int) Math.round(clickSecs * RATE);
        int width = (int) Math.round(0.005 * RATE);
        for (int start = 0; start + width < frames; start += period) {
            for (int i = 0; i < width; i++) {
                double env = Math.exp(-i / (double) width * 4.0);
                float v = (float) (0.8 * env * Math.sin(2 * Math.PI * 180.0 * i / RATE));
                out[0][start + i] += v;
                out[1][start + i] += v;
            }
        }
        return out;
    }

    /** 找输出里的点击时刻（秒）：2 ms 包络上的局部极大值，且比最大击低不了太多。 */
    private static List<Double> clickTimes(float[] x, double minGapSecs) {
        int win = Math.max(8, (int) Math.round(0.002 * RATE));
        int n = x.length / win;
        double[] env = new double[n];
        for (int i = 0; i < n; i++) {
            double peak = 0.0;
            for (int j = i * win; j < (i + 1) * win && j < x.length; j++) {
                peak = Math.max(peak, Math.abs(x[j]));
            }
            env[i] = peak;
        }
        double max = 0.0;
        for (double v : env) {
            max = Math.max(max, v);
        }
        int minGapFrames = (int) Math.round(minGapSecs * RATE);
        List<Double> hits = new ArrayList<Double>();
        int lastAt = -minGapFrames;
        for (int i = 1; i + 1 < n; i++) {
            if (env[i] < 0.3 * max || env[i] < env[i - 1] || env[i] < env[i + 1]) {
                continue;                       // 不是显著的局部峰值
            }
            int best = i * win;
            float peak = 0f;
            for (int j = i * win; j < Math.min((i + 1) * win, x.length); j++) {
                if (Math.abs(x[j]) > peak) {
                    peak = Math.abs(x[j]);
                    best = j;
                }
            }
            if (best - lastAt < minGapFrames) {
                continue;
            }
            hits.add(best / (double) RATE);
            lastAt = best;
        }
        return hits;
    }

    // ==================================================================
    // ④ 保音高（纯 WSOLA，不是重采样）
    // ==================================================================

    /**
     * <b>降速不许变调。</b>
     *
     * <p>Apple 的取证就是这么判的：分数半音扫描 +0.00 ⇒ 保音高；若是把出场曲重采样到
     * 0.9626×，音高应当下降 {@code 12·log2(0.9626) = −0.66} 半音。
     *
     * <p>这里用一个 220 Hz 正弦做同一件事：变速之后过零率必须还在 220 Hz 附近，
     * 而且<b>明显不是重采样会给的那个数</b>（220 × 0.94 = 206.8 Hz）——后半句才是
     * 这条判据真正排掉的东西。
     */
    @Test
    public void theRampKeepsThePitchLikeWsolaDoes() {
        int frames = (int) Math.round(2.0 * RATE);
        double end = 0.94;
        int need = TempoBridge.requiredFramesRamp(frames, RATE, 1.0, end);
        float[][] in = sineStereo(220.0, need);
        float[][] out = TempoBridge.stretchRamp(in, RATE, frames, 1.0, end);
        double hz = zeroCrossHz(out[0]);
        double resampled = 220.0 * end;
        System.out.println(String.format(java.util.Locale.ROOT,
                "[ramp] 220 Hz 正弦按 1.000→%.2f 变速：过零估计 %.1f Hz"
                        + "（保音高应 ≈220，重采样会给 %.1f）",
                Double.valueOf(end), Double.valueOf(hz), Double.valueOf(resampled)));
        assertEquals("降速不该改变音高", 220.0, hz, 220.0 * 0.03);
        assertTrue("测到的音高更靠近重采样会给的那个数（" + resampled
                        + "）而不是 220——那就不是保音高变速了",
                Math.abs(hz - 220.0) < Math.abs(hz - resampled));
    }

    private static float[][] sineStereo(double hz, int frames) {
        float[][] out = new float[2][frames];
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < frames; i++) {
                out[c][i] = (float) (0.5 * Math.sin(2 * Math.PI * hz * i / RATE));
            }
        }
        return out;
    }

    /** 过零率当频率的粗糙代理（与 {@code TempoBridgeTest} 同一把尺子）。 */
    private static double zeroCrossHz(float[] x) {
        int zc = 0;
        for (int i = 1; i < x.length; i++) {
            if ((x[i - 1] < 0) != (x[i] < 0)) {
                zc++;
            }
        }
        return zc * (double) RATE / (2.0 * x.length);
    }
}
