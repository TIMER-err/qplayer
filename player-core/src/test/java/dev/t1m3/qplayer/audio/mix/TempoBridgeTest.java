package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>变速器（WSOLA）的三条底线</b>：帧数精确、音高不变、不许有台阶。
 *
 * <p>为什么这三条是底线：
 *
 * <ul>
 *   <li><b>帧数精确</b>——变速会改变"消耗多少源素材"，算错帧就是丢音频或放重复，
 *       而这一层最终要接进过渡，历史上"进度回跳"就是这么来的；</li>
 *   <li><b>音高不变</b>——用户要的是"听不出换歌"，如果出场曲在过渡里变了调，
 *       那是比鼓点错位更明显的破绽；</li>
 *   <li><b>没有台阶</b>——WSOLA 的交叠相加写错会在颗粒边界上留下咔哒。</li>
 * </ul>
 */
public class TempoBridgeTest {

    private static final int RATE = 48000;

    private static float[][] sineStereo(double hz, int frames) {
        float[][] out = new float[2][frames];
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < frames; i++) {
                out[c][i] = (float) (0.5 * Math.sin(2 * Math.PI * hz * i / RATE));
            }
        }
        return out;
    }

    /** 过零率当频率的粗糙代理（正弦上够准）：每帧的过零次数 × 采样率 ÷ 2 ÷ 帧数。 */
    private static double zeroCrossHz(float[] x) {
        int zc = 0;
        for (int i = 1; i < x.length; i++) {
            if ((x[i - 1] < 0) != (x[i] < 0)) {
                zc++;
            }
        }
        return zc * (double) RATE / (2.0 * x.length);
    }

    private static float maxStep(float[] x) {
        float worst = 0f;
        for (int i = 1; i < x.length; i++) {
            worst = Math.max(worst, Math.abs(x[i] - x[i - 1]));
        }
        return worst;
    }

    /** ① 速度 1 → 逐位相同（关掉这条路线时必须完全没影响）。 */
    @Test
    public void unitySpeedIsABitExactCopy() {
        float[][] in = sineStereo(220.0, RATE);
        float[][] out = TempoBridge.stretch(in, RATE, RATE, 1.0);
        assertEquals("声道数", in.length, out.length);
        assertEquals("帧数", RATE, out[0].length);
        for (int c = 0; c < in.length; c++) {
            for (int i = 0; i < RATE; i++) {
                if (in[c][i] != out[c][i]) {
                    fail("速度 1 时第 " + c + " 声道第 " + i + " 帧不同");
                }
            }
        }
    }

    /** ② 帧数必须精确等于要求值（多一帧少一帧都会变成过渡上的丢帧/重复）。 */
    @Test
    public void theFrameCountIsExact() {
        int frames = RATE * 2;
        for (double speed : new double[] {0.94, 0.97, 1.0, 1.03, 1.06}) {
            float[][] in = sineStereo(220.0, TempoBridge.requiredFrames(frames, RATE, speed));
            float[][] out = TempoBridge.stretch(in, RATE, frames, speed);
            assertEquals("speed=" + speed + " 的输出帧数必须精确", frames, out[0].length);
        }
        System.out.println("[tempo] 五个速度下输出帧数都精确等于要求值");
    }

    /** ③ <b>音高不变</b>：220 Hz 的正弦拉伸之后仍然是 220 Hz（±3%）。 */
    @Test
    public void thePitchIsPreserved() {
        int frames = RATE;
        for (double speed : new double[] {0.94, 0.97, 1.03, 1.06}) {
            float[][] in = sineStereo(220.0, TempoBridge.requiredFrames(frames, RATE, speed));
            float[][] out = TempoBridge.stretch(in, RATE, frames, speed);
            double hz = zeroCrossHz(out[0]);
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[tempo] speed=%.2f → 过零估计 %.1f Hz", Double.valueOf(speed), Double.valueOf(hz)));
            assertEquals("speed=" + speed + " 不该改变音高", 220.0, hz, 220.0 * 0.03);
        }
    }

    /** ④ 颗粒边界不许有台阶（交叠相加写错就会在输出上留下跳变）。 */
    @Test
    public void thereAreNoStepsAtGrainBoundaries() {
        int frames = RATE;
        float[][] in = sineStereo(220.0, TempoBridge.requiredFrames(frames, RATE, 0.94));
        float[][] out = TempoBridge.stretch(in, RATE, frames, 0.94);
        float inStep = maxStep(in[0]);
        float outStep = maxStep(out[0]);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[tempo] 逐帧最大跳变：输入 %.5f / 输出 %.5f", Float.valueOf(inStep),
                Float.valueOf(outStep)));
        assertTrue("输出的逐帧跳变 " + outStep + " 不该明显大于输入 " + inStep,
                outStep <= inStep * 1.5f + 1e-4f);
    }

    /**
     * ⑤ <b>变速真的改了速度</b>：128 BPM 的点击轨按 120/128 = 0.9375 变速之后，
     * 输出里的点击间隔必须正好是 0.5 秒（= 120 BPM）。
     *
     * <p>这条用合成素材，所以它是**确定性**的：不依赖任何 BPM 估计，也不依赖真曲目。
     * 上一条（真曲目）量的是"窗口内累积错位 470 ms → 0 ms"，但它算的是拍长的**算术**；
     * 这一条量的是**素材本身**的拍长真的变了——两条合起来才算把这件事钉住。
     */
    @Test
    public void theTempoOfTheMaterialActuallyChanges() {
        double fromBpm = 128.0;
        double toBpm = 120.0;
        double speed = toBpm / fromBpm;          // 0.9375：把 A 放慢
        double sourcePeriod = 60.0 / fromBpm;    // 0.46875 s
        int frames = RATE * 6;
        int sourceFrames = TempoBridge.requiredFrames(frames, RATE, speed);

        // 点击轨：每 sourcePeriod 一下（1 kHz 短脉冲，5 ms）
        float[][] clicks = new float[2][sourceFrames];
        for (int start = 0; start < sourceFrames; start += (int) Math.round(sourcePeriod * RATE)) {
            for (int i = 0; i < RATE / 200 && start + i < sourceFrames; i++) {
                float v = (float) (0.8 * Math.sin(2 * Math.PI * 1000.0 * i / RATE)
                        * Math.exp(-i / 60.0));
                clicks[0][start + i] = v;
                clicks[1][start + i] = v;
            }
        }
        float[][] out = TempoBridge.stretch(clicks, RATE, frames, speed);

        // 逐 ms 的强度包络，找峰值位置 → 点击间隔
        int step = RATE / 1000;
        java.util.List<Integer> peaks = new java.util.ArrayList<Integer>();
        double prev = 0;
        double prevPrev = 0;
        for (int i = 0; i < frames; i++) {
            double v = Math.abs(out[0][i]);
            if (prev > prevPrev && prev >= v && prev > 0.35) {
                int at = i - 1;
                if (peaks.isEmpty() || at - peaks.get(peaks.size() - 1) > RATE / 20) {
                    peaks.add(Integer.valueOf(at));
                }
            }
            prevPrev = prev;
            prev = v;
        }
        assertTrue("应当找到足够多的点击（" + peaks.size() + "）", peaks.size() >= 8);
        double sum = 0;
        for (int i = 1; i < peaks.size(); i++) {
            sum += peaks.get(i).intValue() - peaks.get(i - 1).intValue();
        }
        double period = sum / (peaks.size() - 1) / (double) RATE;
        System.out.println(String.format(java.util.Locale.ROOT,
                "[tempo] 128 BPM 点击轨按 %.4f 变速 → 实测间隔 %.4f s（目标 %.4f s = %.0f BPM）",
                Double.valueOf(speed), Double.valueOf(period), Double.valueOf(60.0 / toBpm),
                Double.valueOf(toBpm)));
        assertEquals("点击间隔必须变成 120 BPM 的 0.5 秒", 60.0 / toBpm, period, 60.0 / toBpm * 0.01);
        assertEquals("输入里的点击间隔应当被测量成 0.4688 s 左右",
                sourcePeriod, period * speed, sourcePeriod * 0.02);
    }

    /**
     * ⑥ 越界的速度与不足的素材必须**抛异常**（调用方据此降级为"不变速"，不能崩）。
     *
     * <p><b>2026-10-06 按证据重述</b>：原来钉的是旧带宽（0.91 / 1.09 越界），而用户在第一阶段
     * 工作二里明确要求把带宽放开到 {@code TempoBridge.MIN_SPEED~MAX_SPEED}(0.75~1.33)：
     * 「做这个就不要考虑任何复杂度，以完美无感为最高标准，该变速就变速，该咋滴就咋滴，
     * 不要受到神秘条款制约。」所以越界值改用**新带宽之外**的数（0.70 / 1.40），
     * 并直接引用常量——带宽再改时这一条会跟着走，不会又变成一份写死的旧数。
     */
    @Test
    public void outOfBandRequestsAreRejected() {
        float[][] in = sineStereo(220.0, RATE);
        for (double bad : new double[] {0.5, TempoBridge.MIN_SPEED - 0.05,
                TempoBridge.MAX_SPEED + 0.07, 2.0, Double.NaN}) {
            try {
                TempoBridge.stretch(in, RATE, RATE, bad);
                fail("speed=" + bad + " 应当被拒绝");
            } catch (IllegalArgumentException expected) {
                // 正确
            }
        }
        try {
            TempoBridge.stretch(new float[][] {new float[RATE / 4], new float[RATE / 4]},
                    RATE, RATE, 1.06);
            fail("素材不足时应当抛异常");
        } catch (IllegalArgumentException expected) {
            // 正确
        }
        System.out.println("[tempo] 越界速度与不足素材都如实抛异常（调用方降级，不崩）");
    }
}
