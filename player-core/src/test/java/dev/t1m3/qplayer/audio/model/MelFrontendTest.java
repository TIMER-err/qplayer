package dev.t1m3.qplayer.audio.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * log-mel 前端的对齐测试：对应 Sonalis {@code sonalis-model/src/beat.rs} 里
 * {@code mod tests} 关于 {@code MelParams} 与 {@code MelFrontend} 的那几条。
 *
 * <p>方法名按 camelCase 逐条对应上游，断言强度一致（{@code assert_eq!} →
 * {@code assertEquals}，{@code assert!(x, "{}", y)} → {@code assertTrue(msg, x)}）。
 *
 * <p><b>这几条都不碰 ONNX</b>：前端是可测的纯数学，与模型上万个参数无关。
 * 这也是上游把 {@code pick_peaks} 单独拆出去的同一条理由。
 */
public class MelFrontendTest {

    /** {@code core::f32::consts::PI} 的 f32 镜像（造素材的相位链要按 f32 走）。 */
    private static final float PI_F32 = (float) Math.PI;

    /** 上游 {@code fn tone(hz, rate, n)}。 */
    private static float[] tone(float hz, int rate, int n) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (float) Math.sin((double) (2.0f * PI_F32 * hz * (float) i / (float) rate))
                    * 0.5f;
        }
        return out;
    }

    private static float[] zeros(int n) {
        return new float[n];
    }

    /**
     * 断言一次 {@code validate()} 抛出的消息里含某个片段。
     *
     * <p>上游是 {@code bad.validate().unwrap_err().contains("2 的幂")}；
     * Java 侧 {@code Result<(), String>} 映射成「抛 {@link IllegalArgumentException}」，
     * 所以对应物是 try/catch + 断言消息。
     */
    private static void assertRejects(MelParams p, String fragment) {
        try {
            p.validate();
            fail("这组参数应当被拒绝，但 validate() 通过了：" + p);
        } catch (IllegalArgumentException e) {
            assertTrue("错误消息里应当含「" + fragment + "」，实际：" + e.getMessage(),
                    e.getMessage().contains(fragment));
        }
    }

    /** 上游 {@code params_are_validated}。 */
    @Test
    public void paramsAreValidated() {
        MelParams.defaults().validate();

        MelParams badNfft = MelParams.defaults();
        badNfft.nFft = 1000;
        assertRejects(badNfft, "2 的幂");

        MelParams badHop = MelParams.defaults();
        badHop.hop = 4096;
        assertRejects(badHop, "hop");

        MelParams badFmax = MelParams.defaults();
        badFmax.fmax = 20_000.0f;
        assertRejects(badFmax, "奈奎斯特");

        MelParams badMels = MelParams.defaults();
        badMels.mels = 0;
        assertRejects(badMels, "mels");
    }

    /**
     * {@code n_fft = 0} 也必须被拒绝。
     *
     * <p>上游的 {@code !self.n_fft.is_power_of_two()} 对 0 成立（Rust 的
     * {@code is_power_of_two} 给 {@code false}）；Java 里如果照抄位运算
     * {@code (n & (n-1)) == 0} 就会把 0 判成 2 的幂——这一条专门盯住那个坑，
     * 上游没有对应测试（它的类型系统把这件事挡在了语言层）。
     */
    @Test
    public void zeroFftLengthIsNotAPowerOfTwo() {
        MelParams bad = MelParams.defaults();
        bad.nFft = 0;
        assertRejects(bad, "2 的幂");
    }

    /** 帧数与「窗口右端对齐样本末尾」这条约定一致。 */
    @Test
    public void frameCountMatchesTheConvention() {
        MelParams p = MelParams.defaults();
        p.nFft = 1024;
        p.hop = 256;
        MelFrontend fe = new MelFrontend(p);

        // 正好一帧
        MelFrontend.Features f = fe.compute(zeros(1024));
        assertEquals(1, f.frames);
        assertEquals(128 * 1, f.values.length);

        // 一帧多一点 → 还是 1 帧
        assertEquals(1, fe.compute(zeros(1279)).frames);

        // 正好两帧
        assertEquals(2, fe.compute(zeros(1280)).frames);
    }

    /** 短于一个窗口时<b>返回空</b>，不补零硬凑。 */
    @Test
    public void tooShortInputYieldsNoFrames() {
        MelParams p = MelParams.defaults();
        p.nFft = 1024;
        MelFrontend fe = new MelFrontend(p);

        float[] in = new float[1023];
        for (int i = 0; i < in.length; i++) {
            in[i] = 0.1f;
        }
        MelFrontend.Features f = fe.compute(in);
        assertEquals(0, f.frames);
        assertEquals(0, f.values.length);
    }

    /** 1 kHz 的能量应当落在 1 kHz 附近的梅尔带上。 */
    @Test
    public void energyLandsInTheRightMelBand() {
        MelParams p = MelParams.defaults();
        p.nFft = 2048;
        p.hop = 441;
        p.mels = 128;
        p.fmin = 30.0f;
        p.fmax = 11_025.0f;
        MelFrontend fe = new MelFrontend(p);

        float[] pcm = tone(1000.0f, 22_050, 8192);
        MelFrontend.Features f = fe.compute(pcm);
        assertTrue(f.frames > 2);

        // 逐带求平均，找能量最大的那一带，看它对应的频率
        int best = 0;
        float bestSum = -Float.MAX_VALUE;
        for (int m = 0; m < 128; m++) {
            float acc = 0.0f;
            for (int fr = 0; fr < f.frames; fr++) {
                acc += f.values[fr * 128 + m];
            }
            if (acc > bestSum) {
                bestSum = acc;
                best = m;
            }
        }

        float melLo = MelFrontend.hzToMel(30.0f);
        float melHi = MelFrontend.hzToMel(11_025.0f);
        float center = MelFrontend.melToHz(melLo + (melHi - melLo) * ((float) best + 1.5f) / 129.0f);
        assertTrue("1 kHz 的能量落到第 " + best + " 带（≈" + center + " Hz）",
                Math.abs(center - 1000.0f) < 300.0f);
    }

    /** 静音输入要给出全零特征，不能因为 {@code ln(1 + k*0) = 0} 之外的原因产生噪声。 */
    @Test
    public void silenceProducesZeroFeatures() {
        MelParams p = MelParams.defaults();
        p.nFft = 1024;
        MelFrontend fe = new MelFrontend(p);

        MelFrontend.Features f = fe.compute(zeros(4096));
        assertTrue(f.frames > 0);
        for (int i = 0; i < f.values.length; i++) {
            assertTrue("静音应当全是 0，第 " + i + " 个是 " + f.values[i],
                    Math.abs(f.values[i]) < 1e-6f);
        }
    }

    /** 帧 → 秒用窗口中心，这样时间戳与听觉上的「那一刻」对齐。 */
    @Test
    public void frameToSecsUsesWindowCenter() {
        MelParams p = MelParams.defaults();
        p.nFft = 2048;
        p.hop = 441;
        p.sampleRate = 22_050;
        MelFrontend fe = new MelFrontend(p);

        assertTrue(Math.abs(fe.frameToSecs(0) - (1024.0f / 22_050.0f)) < 1e-6f);
        float d1 = fe.frameToSecs(1) - fe.frameToSecs(0);
        assertTrue(Math.abs(d1 - (441.0f / 22_050.0f)) < 1e-6f);
    }

    /** 梅尔换算必须是可逆的——写反了会让滤波器全落在错误的频段上。 */
    @Test
    public void melConversionRoundTrips() {
        float[] hzs = {30.0f, 100.0f, 440.0f, 1000.0f, 4000.0f, 11_025.0f};
        for (float hz : hzs) {
            float back = MelFrontend.melToHz(MelFrontend.hzToMel(hz));
            assertTrue(hz + " Hz 往返后变成 " + back + " Hz", Math.abs(back - hz) < 0.5f);
        }
    }

    /**
     * <b>复用</b>同一个前端与<b>每次新建</b>一个前端，结果必须逐位相同。
     *
     * <p>上游这条测试的理由：{@code beats()} 原先每调用一次就新建一个前端；
     * 改成只建一次、之后复用之后，复用那条路多了一个变数——
     * 暂存缓冲区里留着上一条输入的残渣。所以中间故意夹一段别的音频，专门盯住这个。
     */
    @Test
    public void aReusedFrontendMatchesAFreshOne() {
        MelParams p = MelParams.defaults();
        p.nFft = 1024;
        p.hop = 256;
        float[] pcm = tone(440.0f, 22_050, 6000);
        float[] other = tone(1234.0f, 22_050, 5000);

        MelFrontend reused = new MelFrontend(p);
        MelFrontend.Features first = reused.compute(pcm);
        // 中间过一段别的音频：复用出来的缓冲区要是没被覆盖干净，就是在这里露馅
        reused.compute(other);
        MelFrontend.Features again = reused.compute(pcm);
        MelFrontend.Features fresh = new MelFrontend(p).compute(pcm);

        assertEquals("帧数不该因为复用而变", fresh.frames, first.frames);
        assertEquals("帧数不该因为复用而变", fresh.frames, again.frames);
        for (int i = 0; i < first.values.length; i++) {
            assertEquals("第 " + i + " 个特征不一致：首次算出 " + first.values[i]
                            + "、复用后算出 " + again.values[i] + "——复用留下了上一次的残渣",
                    Float.floatToIntBits(first.values[i]), Float.floatToIntBits(again.values[i]));
        }
        for (int i = 0; i < again.values.length; i++) {
            assertEquals("第 " + i + " 个特征不一致：复用算出 " + again.values[i]
                            + "、每次新建算出 " + fresh.values[i] + "——复用改变了数值结果",
                    Float.floatToIntBits(again.values[i]), Float.floatToIntBits(fresh.values[i]));
        }
    }

    /**
     * 前端<b>复制</b>传入的参数：建好之后改原对象不能影响已经建好的前端。
     *
     * <p>上游是按值传参（{@code MelParams} 是 {@code Clone}），Java 的字段可变，
     * 所以这条性质要靠构造函数里显式复制来保证——上游没有对应测试
     * （它的语言层面就做不到「改到别人那份」）。
     */
    @Test
    public void theFrontendCopiesItsParams() {
        MelParams p = MelParams.defaults();
        p.nFft = 1024;
        p.hop = 256;
        MelFrontend fe = new MelFrontend(p);

        p.mels = 8;
        p.nFft = 64;
        assertEquals("前端应当拿着一份自己的参数", 128, fe.params().mels);
        assertEquals("前端应当拿着一份自己的参数", 1024, fe.params().nFft);

        // 参数变了，结果就不该跟着变
        MelFrontend.Features f = fe.compute(zeros(4096));
        assertEquals(128 * f.frames, f.values.length);
    }

    /**
     * 校验值/参数判等：上游测试里有 {@code assert_eq!(model.mel_params(), &p)}，
     * Java 的对应物靠 {@code equals}，所以它<B>必须</B>按字段全比。
     */
    @Test
    public void paramsCompareByValue() {
        MelParams a = MelParams.defaults();
        MelParams b = MelParams.defaults();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        b.hop = 512;
        assertTrue("改了一个字段就不该再相等", !a.equals(b));

        PeakParams pa = PeakParams.defaults();
        PeakParams pb = PeakParams.defaults();
        assertEquals(pa, pb);
        pb.offset = 0.25f;
        assertTrue(!pa.equals(pb));
    }
}
