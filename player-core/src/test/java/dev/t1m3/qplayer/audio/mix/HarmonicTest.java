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
 * 和声匹配的对齐测试：对应 Sonalis {@code sonalis-mix/src/harmonic.rs} 的 {@code mod tests}。
 *
 * <p>上游 13 条测试逐条对应到本类的 13 个方法（名字按 camelCase 映射），
 * 断言强度保持一致；末尾另有一条 <strong>补充</strong>测试，用于钉住「f32 走到阈值判定」
 * 这条性质（上游没有，是 Java 侧数值路径的额外保险）。
 */
public class HarmonicTest {

    /** 上游测试模块里的 {@code const RATE: u32 = 22_050;}。 */
    private static final int RATE = 22_050;

    /** {@code core::f32::consts::PI} 的 f32 镜像，与 {@link Harmonic} 里同一用意。 */
    private static final float PI = (float) Math.PI;

    /**
     * 造一段指定音级的持续和弦。
     *
     * <p>用纯正弦叠加而不是真实乐器，是因为测试要的是<strong>可推导的答案</strong>：
     * 音级集合已知，正确的匹配结果就该是确定的。
     */
    private static float[] chord(int[] pcs, double secs) {
        return chordAt(pcs, secs, RATE);
    }

    /**
     * 与 {@link #chord} 相同，但可以指定采样率——「同一个调、不同的文件采样率」
     * 那条回归测试要用它造两份素材。
     */
    private static float[] chordAt(int[] pcs, double secs, int rate) {
        int n = (int) ((double) rate * secs);
        float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            float t = (float) i / (float) rate;
            float x = 0.0f;
            for (int p = 0; p < pcs.length; p++) {
                float f = 220.0f * (float) Math.pow(2.0, (double) ((float) pcs[p] / 12.0f));
                // 上游：x += (2.0 * PI * f * t).sin()——相位先在 f32 里乘出来再取 sin
                float phase = 2.0f * PI * f * t;
                x += (float) Math.sin((double) phase);
            }
            v[i] = x / (float) pcs.length * 0.6f;
        }
        return v;
    }

    /** 固定种子的伪随机噪声（可复现，不引 rand）。 */
    private static float[] noise(double secs) {
        int n = (int) ((double) RATE * secs);
        float[] v = new float[n];
        // Rust: state.wrapping_mul(1_664_525).wrapping_add(1_013_904_223)
        // Java 的 int 乘法/加法本身就是回绕的补码运算，逐位一致。
        int state = 0x1234_5678;
        for (int i = 0; i < n; i++) {
            state = state * 1_664_525 + 1_013_904_223;
            // Rust 里 state 是 u32，(state >> 8) 是逻辑右移 → Java 用 >>>
            v[i] = ((float) (state >>> 8) / 8_388_608.0f - 1.0f) * 0.3f;
        }
        return v;
    }

    /**
     * <strong>两个文件的采样率不同时，判调性必须仍然正确。</strong>
     *
     * <p>队列里 44.1 kHz 的 MP3 接 48 kHz 的 FLAC 是最平常不过的情况。
     * 曾经这里只传一个采样率、两侧共用：进场曲的每个音级都按错误的
     * 频率坐标去算，整体偏移 8.8%（约 1.5 个半音），于是本该原样交接的
     * 两首歌被判成「差一个多半音」。转调建议因此变成一个与素材无关的
     * 伪结论——而「声波重合」整个立论就架在这个结论上。
     *
     * <p>这里拿<strong>当初那种写法</strong>当对照，而不是断言一个绝对值：
     * 与 {@code mixer} 里「线性淡化会掉 3 dB」那条同一个路数——
     * 把「为什么必须两个采样率」写成可执行的证据。
     */
    @Test
    public void differentSampleRatesDoNotShiftTheKey() {
        float[] a = chordAt(new int[]{0, 4, 7}, 10.0, 44_100);
        float[] b = chordAt(new int[]{0, 4, 7}, 10.0, 48_000);

        Harmonic.Match right = Harmonic.match(a, 44_100, b, 48_000);
        assertNotNull("同一个调应能给出结论", right);
        assertTrue("同一个调在两种采样率下应当高度相似，实际 " + right.plainScore
                        + "（" + right.describe() + "）",
                right.plainScore > 0.9f);
        assertFalse("同一个调不该因为文件采样率不同就转调：" + right.describe(),
                right.shouldShift);

        // 对照：把两侧按**同一个**采样率分析（修复前的写法）。
        // 实测这个纯三和弦会被判成「素材没有调性信息」——频率坐标算错之后，
        // 能量散到了别的音级上，连清晰度门槛都过不去。这一条的后果不是
        // 「转错半音」，而是**一次都不会转**：本该转调的接缝全部退回朴素淡化。
        Harmonic.Match wrong = Harmonic.match(a, 44_100, b, 44_100);
        if (wrong != null) {
            // 或者给出一个明显更低的相似度，同样不可信
            assertTrue("用对采样率应当明显更像：" + right.plainScore + " 对 " + wrong.plainScore
                            + "（" + right.describe() + " vs " + wrong.describe() + "）",
                    right.plainScore > wrong.plainScore + 0.05f);
        }
        // wrong == null 就是「判成无调性」——正是上面那种后果
    }

    @Test
    public void rotateShiftsEnergyForward() {
        float[] c = new float[Harmonic.PITCH_CLASSES];
        c[0] = 1.0f;
        float[] r = Harmonic.rotate(c, 2);
        assertEquals("分量应向前移两格", 1.0f, r[2], 0.0f);
        assertEquals(0.0f, r[0], 0.0f);
    }

    @Test
    public void rotateWrapsAround() {
        float[] c = new float[Harmonic.PITCH_CLASSES];
        c[11] = 1.0f;
        float[] r = Harmonic.rotate(c, 3);
        assertEquals("越过 12 应回到开头", 1.0f, r[2], 0.0f);

        // 负数方向也要正确回绕
        float[] d = new float[Harmonic.PITCH_CLASSES];
        d[1] = 1.0f;
        assertEquals(1.0f, Harmonic.rotate(d, -3)[10], 0.0f);
    }

    @Test
    public void rotateByZeroIsIdentity() {
        float[] c = new float[Harmonic.PITCH_CLASSES];
        c[5] = 0.7f;
        c[9] = 0.3f;
        // 上游是 assert_eq!(rotate(&c, 0), c)：数组逐元素相等，不带容差（delta = 0）
        assertArrayEquals("旋转 0 半音应当原样返回", c, Harmonic.rotate(c, 0), 0.0f);
    }

    @Test
    public void windowsAreTakenFromTheRightEnds() {
        float[] longPcm = new float[RATE * 20];
        Arrays.fill(longPcm, 7.0f);
        assertEquals(RATE * 8, Harmonic.tailWindow(longPcm, RATE).length);
        assertEquals(RATE * 8, Harmonic.headWindow(longPcm, RATE).length);

        // 短素材不该 panic，也不该补零
        float[] shortPcm = new float[100];
        Arrays.fill(shortPcm, 1.0f);
        assertEquals(100, Harmonic.tailWindow(shortPcm, RATE).length);
        assertEquals(100, Harmonic.headWindow(shortPcm, RATE).length);
    }

    /** 同一段素材与自己匹配：相似度应当是 1，且不该建议转调。 */
    @Test
    public void identicalMaterialMatchesWithoutShifting() {
        float[] a = chord(new int[]{0, 4, 7}, 10.0);
        Harmonic.Match m = Harmonic.match(a, RATE, a, RATE);
        assertNotNull("同调素材应能给出结论", m);
        assertTrue("自比应为 1，实际 " + m.plainScore, m.plainScore > 0.99f);
        assertFalse(m.shouldShift);
        assertEquals(0, m.semitones);
    }

    /** 差两个半音的大三和弦：正确答案就是「升/降两个半音」。 */
    @Test
    public void aWholeToneApartIsDetected() {
        float[] a = chord(new int[]{0, 4, 7}, 10.0);
        float[] b = chord(new int[]{2, 6, 9}, 10.0);

        Harmonic.Match m = Harmonic.match(a, RATE, b, RATE);
        assertNotNull("应能给出结论", m);
        assertTrue("差两个半音应该建议转调，实际 " + m.describe(), m.shouldShift);
        assertEquals("转调量应为两个半音：" + m.describe(), 2, Math.abs(m.semitones));
        assertTrue(m.bestScore > m.plainScore);
    }

    /**
     * 纯噪声没有调性，<strong>绝不能给出转调建议</strong>。
     * 这是这套逻辑最容易出错的地方：噪声里总能「搜」出一个偶然的高分。
     */
    @Test
    public void noiseYieldsNoAdvice() {
        float[] n = noise(10.0);
        Harmonic.Match m = Harmonic.match(n, RATE, n, RATE);
        if (m != null) {
            // 要么干脆不表态（m == null）……要么表态「不转调」。总之不能建议转。
            assertFalse("噪声不该触发转调：" + m.describe(), m.shouldShift);
        }
    }

    @Test
    public void silenceYieldsNoAdvice() {
        float[] s = new float[RATE * 10];
        assertNull("静音没有调性，应当不表态", Harmonic.match(s, RATE, s, RATE));
    }

    @Test
    public void tooShortInputYieldsNone() {
        float[] tiny = new float[100];
        Arrays.fill(tiny, 0.1f);
        assertNull(Harmonic.match(tiny, RATE, tiny, RATE));
        assertNull(Harmonic.weightedChroma(tiny, RATE));
    }

    @Test
    public void zeroRateIsHandled() {
        float[] a = chord(new int[]{0, 4, 7}, 2.0);
        assertNull(Harmonic.match(a, 0, a, 0));
    }

    @Test
    public void claritySeparatesToneFromNoise() {
        // 单音应当有较高的清晰度
        float[] tone = chord(new int[]{0}, 1.0);
        float[] frame = Arrays.copyOf(tone, Harmonic.FRAME_LEN);
        assertTrue("纯音应当通过清晰度门槛",
                Harmonic.clarityOf(Harmonic.frameChroma(frame, RATE)) > Harmonic.CLARITY_MIN);

        // 噪声的 12 个分量接近均匀，应当落在门槛之下
        float[] n = noise(1.0);
        float[] noiseFrame = Arrays.copyOf(n, Harmonic.FRAME_LEN);
        float noiseClarity = Harmonic.clarityOf(Harmonic.frameChroma(noiseFrame, RATE));
        assertTrue("噪声清晰度 " + noiseClarity + " 不应超过门槛 " + Harmonic.CLARITY_MIN,
                noiseClarity < Harmonic.CLARITY_MIN);
    }

    @Test
    public void describeIsReadable() {
        Harmonic.Match m = new Harmonic.Match(-2, 0.4f, 0.9f, true);
        String s = m.describe();
        assertTrue(s, s.contains("转调"));
        assertTrue(s, s.contains("-2"));

        Harmonic.Match m2 = new Harmonic.Match(0, 0.95f, 0.95f, false);
        assertTrue(m2.describe().contains("不转调"));
        // 转调量为正时也要带符号（上游的 {:+.0}）
        assertTrue(new Harmonic.Match(3, 0.4f, 0.9f, true).describe().contains("+3"));
    }

    /**
     * <strong>补充测试</strong>（上游没有）：加权后的 chroma 必须把能量留在和弦的音级上。
     *
     * <p>这条钉的是「f32 一路走到阈值判定」这条性质：Goertzel 的 {@code coeff} 只要被
     * 提升成 double 参与递推，2048 次迭代下来各音级的能量比就会漂移，
     * 12 个分量里谁进前三名就可能变。三个音级是确定的，所以前三名也必须是它们。
     */
    @Test
    public void weightedChromaKeepsTheChordTones() {
        float[] a = chord(new int[]{0, 4, 7}, 10.0);
        float[] chroma = Harmonic.weightedChroma(Harmonic.tailWindow(a, RATE), RATE);
        assertNotNull(chroma);
        assertEquals(Harmonic.PITCH_CLASSES, chroma.length);

        // 取三个最大的分量下标
        boolean[] top = new boolean[Harmonic.PITCH_CLASSES];
        for (int k = 0; k < 3; k++) {
            int best = -1;
            for (int i = 0; i < Harmonic.PITCH_CLASSES; i++) {
                if (top[i]) {
                    continue;
                }
                if (best < 0 || chroma[i] > chroma[best]) {
                    best = i;
                }
            }
            top[best] = true;
        }
        assertTrue("前三名应是和弦音级 {0,4,7}，实际峰值分布 " + Arrays.toString(chroma),
                top[0] && top[4] && top[7]);
    }

    /**
     * <b>逐帧并行的 chroma 必须与串行实现<u>逐位相同</u>。</b>
     *
     * <p>2026-09-30 为了压缩 automix 预处理时间，{@code weightedChroma} 把「逐帧算
     * chroma/clarity/rms」分成几块交给几个线程，而**累加仍然严格按帧序串行做**——
     * 浮点加法不满足结合律，跨帧并行累加会改结果，而这一层的结果要用来判调性、
     * 挑移调量。这条判据就是把「只并行算、不并行加」钉住：这里照原样再写一遍串行实现，
     * 两者必须**一个 bit 都不差**。
     */
    @Test
    public void theParallelChromaIsBitIdenticalToTheSerialOne() {
        for (float hz : new float[]{110.0f, 220.0f, 440.0f}) {
            float[] pcm = harmonicTone(hz, 8.0);
            float[] fast = Harmonic.weightedChroma(pcm, RATE);
            float[] slow = serialChroma(pcm, RATE);
            assertNotNull("并行版算不出结论", fast);
            assertNotNull("串行版算不出结论", slow);
            assertEquals("维数应当一致", slow.length, fast.length);
            for (int i = 0; i < slow.length; i++) {
                assertEquals("第 " + i + " 个音级不一致（并行化改了数值路径）",
                        Float.floatToIntBits(slow[i]), Float.floatToIntBits(fast[i]));
            }
        }
    }

    /** {@code weightedChroma} 的串行参照实现（逐帧算、逐帧加，就是改动前那份）。 */
    private static float[] serialChroma(float[] pcm, int rate) {
        if (rate == 0 || pcm.length < 2048) {
            return null;
        }
        float[] acc = new float[12];
        float weightSum = 0.0f;
        int used = 0;
        float[] frame = new float[2048];
        int start = 0;
        while (start + 2048 <= pcm.length) {
            System.arraycopy(pcm, start, frame, 0, 2048);
            float[] chroma = Harmonic.frameChroma(frame, rate);
            float clarity = Harmonic.clarityOf(chroma);
            float loudness = Harmonic.rms(frame);
            if (clarity >= 0.28f && loudness > 0.005f) {
                float w = clarity * loudness;
                for (int i = 0; i < 12; i++) {
                    acc[i] += chroma[i] * w;
                }
                weightSum += w;
                used++;
            }
            start += 512;
        }
        if (used < 8 || weightSum <= 0.0f) {
            return null;
        }
        float norm = 0.0f;
        for (float v : acc) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 1e-9f) {
            for (int i = 0; i < acc.length; i++) {
                acc[i] /= norm;
            }
        }
        return acc;
    }

    /** 一段有明确调性的素材（基频 + 两个泛音），够 8 秒。 */
    private static float[] harmonicTone(float hz, double secs) {
        int n = (int) Math.round(secs * RATE);
        float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            v[i] = (float) (0.4 * Math.sin(2.0 * Math.PI * hz * t)
                    + 0.2 * Math.sin(2.0 * Math.PI * 2.0 * hz * t)
                    + 0.1 * Math.sin(2.0 * Math.PI * 3.0 * hz * t));
        }
        return v;
    }
}
