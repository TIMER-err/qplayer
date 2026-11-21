package dev.t1m3.qplayer.audio.studio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 「只作用在人声轨」的硬判据（docs/audio-studio.md §8）。
 *
 * <p>修跑调与去混响都只能作用在人声上（理由见那两个 Params 的类注释）。这条判据把它变成
 * <strong>可执行的</strong>：一段「故意唱高 60 音分的人声 + 一段伴奏」跑过
 * {@link VocalPolisher}，
 *
 * <ol>
 *   <li>人声必须被修到音阶上（离最近的音 ≤ 10 音分）；</li>
 *   <li><strong>伴奏那两条数组必须逐位不变</strong>——这是「只修人声」的定义，
 *       而不是「听起来伴奏没怎么变」；</li>
 *   <li>参数全关时输入输出<strong>逐位相同</strong>（不能白跑一遍声码器：
 *       相位声码器对宽带素材本身就有约 4% 的频谱重心影响，见 {@code AutotuneTest}）。</li>
 * </ol>
 */
public class VocalPolisherTest {

    private static final int RATE = 48_000;

    /** 一段「唱高了 {@code cents} 音分」的合成人声（两个谐波 + 颤音，相位累加）。 */
    private static float[] vocal(double hz, double cents, double seconds) {
        int total = (int) (seconds * RATE);
        float[] out = new float[total];
        double phase = 0.0;
        double phase2 = 0.0;
        for (int i = 0; i < total; i++) {
            double t = i / (double) RATE;
            double vib = 20.0 * Math.sin(2 * Math.PI * 5.0 * t);
            double f = hz * Math.pow(2.0, (cents + vib) / 1200.0);
            phase += 2.0 * Math.PI * f / RATE;
            phase2 += 2.0 * Math.PI * 2 * f / RATE;
            out[i] = (float) (0.35 * Math.sin(phase) + 0.1 * Math.sin(phase2));
        }
        return out;
    }

    /** 伴奏：几个不成谐波关系的音（复音素材——正是「修跑调对它没有意义」的那种）。 */
    private static float[] instrumental(double seconds) {
        int total = (int) (seconds * RATE);
        float[] out = new float[total];
        double[] f = {130.81, 164.81, 196.00, 246.94};
        double[] ph = new double[f.length];
        for (int i = 0; i < total; i++) {
            double v = 0;
            for (int k = 0; k < f.length; k++) {
                ph[k] += 2.0 * Math.PI * f[k] / RATE;
                v += 0.12 * Math.sin(ph[k]);
            }
            out[i] = (float) v;
        }
        return out;
    }

    private static float[] copy(float[] v) {
        return v.clone();
    }

    private static void assertBitIdentical(String what, float[] before, float[] after) {
        assertEquals("长度", before.length, after.length);
        for (int i = 0; i < before.length; i++) {
            if (Float.floatToIntBits(before[i]) != Float.floatToIntBits(after[i])) {
                throw new AssertionError(what + "：第 " + i + " 个采样变了（"
                        + before[i] + " → " + after[i] + "）");
            }
        }
    }

    /** 一段素材的主频（自相关 + 抛物线插值，只在期望周期 ±10% 里找）。 */
    private static double freqOf(float[] v, int from, int to, double expected) {
        int n = to - from;
        int minLag = Math.max(2, (int) (RATE / (expected * 1.1)));
        int maxLag = Math.min(n / 2, (int) (RATE / (expected / 1.1)));
        double best = -1;
        int bestLag = minLag;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double c = corr(v, from, to, lag);
            if (c > best) {
                best = c;
                bestLag = lag;
            }
        }
        double y0 = corr(v, from, to, bestLag - 1);
        double y1 = best;
        double y2 = corr(v, from, to, bestLag + 1);
        double denom = y0 - 2 * y1 + y2;
        double delta = Math.abs(denom) < 1e-12 ? 0 : 0.5 * (y0 - y2) / denom;
        if (delta > 1.0) {
            delta = 1.0;
        } else if (delta < -1.0) {
            delta = -1.0;
        }
        return RATE / (bestLag + delta);
    }

    private static double corr(float[] v, int from, int to, int lag) {
        if (lag <= 0) {
            return 0;
        }
        double sum = 0;
        double e1 = 0;
        double e2 = 0;
        for (int i = 0; i + lag < to - from; i++) {
            double a = v[from + i];
            double b = v[from + i + lag];
            sum += a * b;
            e1 += a * a;
            e2 += b * b;
        }
        return sum / Math.sqrt(Math.max(e1 * e2, 1e-30));
    }

    // ==================================================================

    /**
     * <b>唱跑调的人声被修回音阶；伴奏逐位不变。</b>
     */
    @Test
    public void theVocalIsCorrectedWhileTheInstrumentalStaysBitIdentical() {
        double a4 = 440.0;
        float[] vocalsL = vocal(a4, 60.0, 2.0);          // 唱高 60 音分
        float[] vocalsR = copy(vocalsL);
        float[] instL = instrumental(2.0);
        float[] instR = instrumental(2.0);
        float[] instLBefore = copy(instL);
        float[] instRBefore = copy(instR);

        AutotuneParams autotune = AutotuneParams.of(ScaleQuantizer.Scale.CHROMATIC, 9, a4,
                1.0, 10.0, true, 1.0);
        VocalPolisher polisher = new VocalPolisher(RATE, autotune, DereverbParams.off());
        assertTrue("这一套参数算「在工作」", polisher.active());
        int frames = polisher.polish(vocalsL, vocalsR);
        assertEquals("处理帧数 = 素材长度（延迟已经在这一层补掉）", 2 * RATE, frames);

        double measured = freqOf(vocalsL, RATE / 2, RATE * 2, 466.16);
        double off = ScaleQuantizer.centsOff(measured, ScaleQuantizer.Scale.CHROMATIC, a4, 9);
        System.out.printf("### 人声后期：唱 +60 音分（455.52 Hz）→ 输出 %.2f Hz，"
                        + "离音阶 %+.1f 音分%n", Double.valueOf(measured), Double.valueOf(off));
        assertTrue("修完必须落在音阶上（离最近音 " + off + " 音分）", Math.abs(off) < 10.0);

        assertBitIdentical("伴奏左声道", instLBefore, instL);
        assertBitIdentical("伴奏右声道", instRBefore, instR);
    }

    /**
     * <b>参数全关时一个采样都不碰（逐位相同）。</b>
     *
     * <p>这一条不能省：相位声码器<strong>本身</strong>对宽带素材就有约 4% 的频谱重心影响
     * （见 {@code AutotuneTest#noiseIsNotTouched} 的实测）。所以「关着也跑一遍」不是无害的，
     * 它会把没被要求处理的东西改掉。
     */
    @Test
    public void offIsBitExact() {
        float[] left = vocal(440.0, 0.0, 1.0);
        float[] right = instrumental(1.0);
        float[] leftBefore = copy(left);
        float[] rightBefore = copy(right);
        VocalPolisher polisher = new VocalPolisher(RATE, AutotuneParams.off(), DereverbParams.off());
        assertFalse("全关不算在工作", polisher.active());
        assertEquals("全关时返回 0（什么都没做）", 0, polisher.polish(left, right));
        assertBitIdentical("干声左", leftBefore, left);
        assertBitIdentical("干声右", rightBefore, right);
    }

    /**
     * <b>去混响只在人声上生效，而且延迟被补掉了。</b>
     *
     * <p>判据两条：① 处理之后人声的尾巴掉下去（这里用「静音段」的能量粗测）；
     * ② 输出与输入<strong>逐个采样对齐</strong>——{@link VocalPolisher} 把模块的延迟补掉了，
     * 于是「唱到第几秒」在输出里没变（这一点是离线处理与实时链的关键差别）。
     */
    @Test
    public void dereverbRemovesTailAndTheLatencyIsCompensated() {
        int seconds = 2;
        float[] dry = vocal(196.0, 0.0, seconds);
        // 造混响：与 DereverbTest 同一套办法（干声卷一个指数衰减的冲激响应），
        // 后半段让干声留白——于是「尾巴」是**算得出来的**
        int half = seconds * RATE / 2;
        for (int i = half; i < dry.length; i++) {
            dry[i] = 0f;
        }
        float[] ir = new float[(int) (0.3 * RATE)];
        java.util.Random rng = new java.util.Random(3);
        double decay = Math.log(1000.0) / (0.5 * RATE);
        double energy = 0;
        for (int i = 0; i < ir.length; i++) {
            double v = (rng.nextDouble() * 2 - 1) * Math.exp(-decay * i);
            ir[i] = (float) v;
            energy += v * v;
        }
        double norm = 1.0 / Math.sqrt(Math.max(energy, 1e-12));
        for (int i = 0; i < ir.length; i++) {
            ir[i] *= norm;
        }
        ir[0] += 1f;
        float[] wetL = new float[dry.length];
        for (int i = 0; i < dry.length; i++) {
            double sum = 0;
            int last = Math.min(i, ir.length - 1);
            for (int k = 0; k <= last; k++) {
                sum += (double) dry[i - k] * ir[k];
            }
            wetL[i] = (float) sum;
        }
        float[] wetR = copy(wetL);
        // **处理前先留一份未处理的**：polish 是就地改写，拿 wetR 当参照等于拿输出比输出
        // （第一版就是这么写的，量出来「掉 −0.00002 dB」）。
        float[] before = wetL.clone();

        VocalPolisher polisher = new VocalPolisher(RATE, AutotuneParams.off(),
                DereverbParams.of(0.9, -18.0, 1.0));
        int frames = polisher.polish(wetL, wetR);
        assertEquals("长度不变（延迟在这一层补掉）", dry.length, frames);

        double tailIn = rmsOf(before, half + 2000, dry.length - 2000);
        double tailOut = rmsOf(wetL, half + 2000, dry.length - 2000);
        double drop = 20 * Math.log10(tailIn / Math.max(tailOut, 1e-12));
        System.out.printf("### 人声去混响：尾巴 %.3e → %.3e（掉 %.1f dB）%n",
                Double.valueOf(tailIn), Double.valueOf(tailOut), Double.valueOf(drop));
        assertTrue("尾巴必须掉下去（实测 " + drop + " dB）", drop > 6.0);
    }

    private static double rmsOf(float[] v, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += (double) v[i] * v[i];
        }
        return Math.sqrt(sum / Math.max(1, to - from));
    }
}
