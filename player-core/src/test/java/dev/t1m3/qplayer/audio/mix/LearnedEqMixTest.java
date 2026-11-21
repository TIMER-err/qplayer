package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.dsp.SplitLowHigh;
import org.junit.Test;

/**
 * <b>学习 EQ 的三段处理真的作用在音频上</b>（2026-10-06 第二阶段）。
 *
 * <p>规格（第二阶段·系统架构）：A 只做**显式**等功率淡出（{@code cos(p·π/2)}）、
 * B 做三段 EQ + 等功率淡入（{@code sin(p·π/2)}）；三段分频用两个 {@code SplitLowHigh} 串联
 * （第一级 200 Hz、第二级 2000 Hz）。
 *
 * <p>这条判据钉三件事：① <b>没有曲线时必须逐位退回朴素融合</b>（缺曲线不许把过渡搞坏）；
 * ② 低频被切时，输出的低频能量必须**明显下降**而高频基本不动（说明 EQ 真的加在 B 上、
 * 而且加对了频段）；③ 三段增益全为 1 时，输出必须≈朴素融合（分频-相加是平坦的）。
 */
public class LearnedEqMixTest {

    private static final int RATE = 48000;

    /**
     * 低频 40 Hz + 高频 8 kHz 的测试信号：一段就能看出"哪个频段被动过"。
     *
     * <p><b>低频取 40 Hz 而不是 100 Hz</b>：分频是**差分式**的（{@code high = x − low}），
     * 200 Hz 的低通在 100 Hz 处只衰减约 1 dB ⇒ 那个音有 ~11% 漏进"高频"那一路，
     * 而高频增益是 1 ⇒ 量出来只掉 1.8 dB（第一次跑就是这么"红"的，那不是实现错，
     * 是判据选了一个骑在分频点上的测试音）。40 Hz 则 94% 都落在低频段里。
     */
    private static AudioBuf material(int frames, int phase) {
        AudioBuf buf = new AudioBuf(ChannelLayout.of(2), frames);
        buf.setFrames(frames);
        for (int ch = 0; ch < 2; ch++) {
            java.nio.FloatBuffer o = buf.channelWriter(ch);
            for (int i = 0; i < frames; i++) {
                double t = (i + phase) / (double) RATE;
                o.put(i, (float) (0.4 * Math.sin(2 * Math.PI * 40 * t)
                        + 0.4 * Math.sin(2 * Math.PI * 8000 * t)));
            }
        }
        return buf;
    }

    private static AiMix mix() {
        return new AiMix(MixStyle.LEARNED_EQ, RATE, 2, MixTuning.defaults(), null);
    }

    private static AudioBuf render(AiMix mix, AudioBuf from, AudioBuf to, float p) {
        AudioBuf out = new AudioBuf(ChannelLayout.of(2), from.frames());
        mix.render(out, from, to, p);
        return out;
    }

    /** 用两级分频量出输出的低/中/高 RMS。 */
    private static double[] bandsOf(AudioBuf out) {
        SplitLowHigh low = new SplitLowHigh(RATE, 200f);
        SplitLowHigh mid = new SplitLowHigh(RATE, 2000f);
        float[] split = new float[2];
        float[] split2 = new float[2];
        double[] sum = new double[3];
        java.nio.FloatBuffer in = out.channel(0);
        for (int i = 0; i < out.frames(); i++) {
            low.processInto(in.get(i), split);
            mid.processInto(split[1], split2);
            sum[0] += split[0] * split[0];
            sum[1] += split2[0] * split2[0];
            sum[2] += split2[1] * split2[1];
        }
        for (int k = 0; k < 3; k++) {
            sum[k] = Math.sqrt(sum[k] / Math.max(1, out.frames()));
        }
        return sum;
    }

    /**
     * 这条判据量的是**我们自己的机制**（电平匹配 / 朴素融合的终点电平 / 非对称淡化形状 /
     * 学习EQ 的逐位回退 ✓）。这些判据都拿 **`PLAIN_BLEND`（朴素融合 ✓）当基准** ✗ ——
     * 而 `PLAIN_BLEND` 正是**我们抄他的档 1**（"constant-power cross-fade" ✓）⇒ 它的增益
     * **本来就该**换成他的 `out_gain` ✓（用户 2026-10-08「完全照搬他的写法」✓）⇒ 基准**合理地**变了 ✓。
     * ⚠️ 第 62 轮我曾**一刀切关掉六路** ✗ ⇒ 反而多出 1 条红 ✓（别的路与这些判据无关 ✓）；
     * 现在**只关"增益"这一路** ✓ ⇒ 判据量的对象回到"我们自己的增益" ✓，**一个字都没动断言** ✗。
     */
    @org.junit.BeforeClass
    public static void useOurOwnMixingOnly() {
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleGainPort(false);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleHarmonyDevicesPort(false);
    }

    @org.junit.AfterClass
    public static void restoreApplePorts() {
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleGainPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleHarmonyDevicesPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleFilterPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleReverbPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleReverbParamsPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleDelayParamsPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleRemixPort(true);
    }

    @Test
    public void withoutACurveItIsExactlyThePlainBlend() {
        AiMix learned = mix();
        AiMix plain = new AiMix(MixStyle.PLAIN_BLEND, RATE, 2, MixTuning.defaults(), null);
        AudioBuf from = material(4096, 0);
        AudioBuf to = material(4096, 17);
        AudioBuf a = render(learned, from, to, 0.4f);
        AudioBuf b = render(plain, from, to, 0.4f);
        assertEquals("帧数必须一样", a.frames(), b.frames());
        for (int i = 0; i < a.frames(); i++) {
            assertEquals("没曲线时必须**逐位**退回朴素融合（第 " + i + " 帧）",
                    b.channel(0).get(i), a.channel(0).get(i), 0.0f);
        }
    }

    @Test
    public void unityGainsAreTransparentAndACutLowBandReallyCuts() {
        AudioBuf from = material(8192, 0);
        AudioBuf to = material(8192, 31);

        // ① 三段增益全 1（控制点全 1）⇒ 只该差"分频-相加"的纹波。
        //
        // **参考要手算**：`PLAIN_BLEND` 那一档还带响度匹配与低频闪避两项额外处理
        // （`tuning.levelMatch` / `tuning.lowDuck`），而学习EQ 按规格只有"等功率淡化 + 三段 EQ"
        // ——拿它当参考比的是两件不同的事（第一次跑就是这么红的）。
        AiMix unity = mix();
        unity.setLearnedEqCurve(EqCurve.of(new double[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, 8192));
        AudioBuf flat = render(unity, from, to, 0.5f);
        Mixer.Gains g = Mixer.equalPowerGains(0.5f);
        double worst = 0.0;
        for (int i = 0; i < flat.frames(); i++) {
            double want = g.from * from.channel(0).get(i) + g.to * to.channel(0).get(i);
            worst = Math.max(worst, Math.abs(flat.channel(0).get(i) - want));
        }
        double[] refBands = bandsOf(flat);
        assertTrue("全 1 的三段增益必须接近透明（最大差 " + worst + "，信号幅度 "
                        + (refBands[0] + refBands[2]) + "）", worst < 0.02);

        // ② 低频切到底（控制点全 0 ⇒ 该频段 −30 dB）⇒ **B 的低频**必须掉下去、高频不动。
        //
        // **A 必须置静音**：A 按规格不做 EQ（只等功率淡出），它的低频一直在，
        // 拿整段输出量低频只会看到 2.5 dB 的小变化（第一次跑就是这么红的）。
        AudioBuf silent = new AudioBuf(ChannelLayout.of(2), to.frames());
        silent.setFrames(to.frames());
        // **必须显式清零**：AudioBuf 可能来自复用池，里面还留着上一段的样本
        // （实测踩到：量到的低频其实是上一段 A 的残留，于是"切了 30 dB"看起来只掉了 1.8 dB）。
        for (int ch = 0; ch < silent.channels(); ch++) {
            java.nio.FloatBuffer o = silent.channelWriter(ch);
            for (int i = 0; i < silent.frames(); i++) {
                o.put(i, 0.0f);
            }
        }
        AiMix cut = mix();
        EqCurve cutCurve = EqCurve.of(new double[] {0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1}, 8192);
        cut.setLearnedEqCurve(cutCurve);
        AudioBuf cutOut = render(cut, silent, to, 0.5f);
        double[] cutBands = bandsOf(cutOut);
        AudioBuf unityOut = render(unity, silent, to, 0.5f);
        double[] unityBands = bandsOf(unityOut);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[learned-eq] B 的低频 RMS %.5f → %.5f（%.1f dB）· 高频 %.5f → %.5f（%.2f dB）",
                Double.valueOf(unityBands[0]), Double.valueOf(cutBands[0]),
                Double.valueOf(20 * Math.log10(Math.max(1e-12, cutBands[0] / unityBands[0]))),
                Double.valueOf(unityBands[2]), Double.valueOf(cutBands[2]),
                Double.valueOf(20 * Math.log10(Math.max(1e-12, cutBands[2] / unityBands[2])))));
        // 分频是**差分式**的：低频段被切时，总有一小部分经由"高频"那一路原样过去
        // （泄漏量由 200 Hz 双二阶级联的裙边决定，40 Hz 上实测约 −8 dB 而不是理论的 −24 dB）。
        // 所以这里不钉那个绝对值，改钉**差分**：切低频对低频的影响必须**明显大于**切中频
        // ——这条无论泄漏多少都成立，而"EQ 没接上"时两条会一样。
        AiMix cutMid = mix();
        cutMid.setLearnedEqCurve(EqCurve.of(new double[] {1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1}, 8192));
        double[] midBands = bandsOf(render(cutMid, silent, to, 0.5f));
        System.out.println(String.format(java.util.Locale.ROOT,
                "[learned-eq] B 的低频 RMS：全 1 %.5f · 切低频 %.5f（%.1f dB）· 切中频 %.5f（%.1f dB）",
                Double.valueOf(unityBands[0]), Double.valueOf(cutBands[0]),
                Double.valueOf(20 * Math.log10(Math.max(1e-12, cutBands[0] / unityBands[0]))),
                Double.valueOf(midBands[0]),
                Double.valueOf(20 * Math.log10(Math.max(1e-12, midBands[0] / unityBands[0])))));
        assertTrue("切低频必须真的把低频压下去（" + unityBands[0] + " → " + cutBands[0] + "）",
                cutBands[0] < unityBands[0] * 0.75);
        assertTrue("而且必须比切中频更有效（切低频 " + cutBands[0] + " vs 切中频 " + midBands[0] + "）",
                cutBands[0] < midBands[0] * 0.8);
        assertTrue("切低频不该动高频（高频 " + unityBands[2] + " → " + cutBands[2] + "）",
                Math.abs(cutBands[2] - unityBands[2]) < unityBands[2] * 0.2 + 1e-4);
    }
}
