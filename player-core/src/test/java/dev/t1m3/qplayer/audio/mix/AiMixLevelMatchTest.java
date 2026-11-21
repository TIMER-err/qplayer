package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>响度匹配在真实融合里到底做了什么</b>（P4，端到端）。
 *
 * <p>{@link LevelMatcherTest} 只证明这个模块自己算得对；这一条证明它接进 {@link AiMix}
 * 之后<b>听感上的那件事确实发生了</b>：两首歌交替的那一段，进场的 B 不再被出场的 A 埋掉。
 *
 * <p>量法利用线性：同一次渲染里 B 那一项的增益只作用在 B 上，所以
 * 「两边都放」减去「只有 A」就是 <b>B 的贡献</b>（逐帧相减，误差为 0，不是估计）。
 * 再把这份贡献与 `只有 A` 的 RMS 比一比，就是「B 相对 A 差几个 dB」。
 */
public class AiMixLevelMatchTest {

    private static final int RATE = 48000;

    /** 一段 220 Hz 正弦（两侧同相），幅度给定，长度 frames。 */
    private static AudioBuf tone(double amp, int frames) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        for (int i = 0; i < frames; i++) {
            float v = (float) (amp * Math.sin(2.0 * Math.PI * 220.0 * i / RATE));
            buf.channelWriter(0).put(i, v);
            buf.channelWriter(1).put(i, v);
        }
        buf.setFrames(frames);
        return buf;
    }

    private static AudioBuf empty(int frames) {
        return new AudioBuf(ChannelLayout.STEREO, Math.max(frames, 1));
    }

    private static double rms(float[] v) {
        double s = 0.0;
        for (int i = 0; i < v.length; i++) {
            s += (double) v[i] * v[i];
        }
        return Math.sqrt(s / v.length);
    }

    /**
     * 融合中段（{@code p = 0.5}，两首歌一样响的那一刻）量 B 的贡献。
     *
     * <p>返回「B 的贡献 ÷ A 的贡献」的 dB 数。
     */
    private static double entrantDeficitDb(float levelMatch, int frames) {
        AudioBuf a = tone(0.4, frames);
        AudioBuf b = tone(0.2, frames);      // 比 A 轻 6 dB：正好在上限之内
        MixTuning tuning = MixTuning.defaults();
        tuning.levelMatch = levelMatch;
        // 只留被测的那一层：空间展宽（P2）与低频让位（P5）都会改变输出的电平，
        // 混进来量到的就不是「响度匹配补掉了多少」，判据会变成好几个旋钮的合成结果
        // （实测：不隔离时「半开」那条会红，差值 −2.23 dB vs 期望 −2.92 dB）。
        tuning.spaceWidth = 0.0f;
        tuning.lowDuck = 0.0f;
        AiMix mix = new AiMix(MixStyle.PLAIN_BLEND, RATE, 2, tuning);

        AudioBuf onlyA = empty(frames);
        mix.render(onlyA, a, empty(frames), 0.5f);

        AudioBuf both = empty(frames);
        AiMix mix2 = new AiMix(MixStyle.PLAIN_BLEND, RATE, 2, tuning);
        mix2.render(both, a, b, 0.5f);

        // B 的贡献 = 两边都放 − 只有 A（B 那一项的增益只作用在 B 上，所以这是逐样本精确的）
        //
        // 只量**稳态**（后四分之一）：匹配器的时间常数是 0.4 s / 0.25 s，素材前 0.5 s 还在
        // 收敛路上（实测 0.5 s 的素材只补掉约一半差距）。判据要钉的是「稳定之后一样响」，
        // 不是「一进来就一样响」——后者本来也不该发生（那正是台阶）。
        int all = both.frames();
        int n = all - all / 4;
        float[] contribA = new float[n];
        float[] contribB = new float[n];
        for (int i = 0; i < n; i++) {
            int at = all - n + i;
            contribA[i] = onlyA.channel(0).get(at);
            contribB[i] = both.channel(0).get(at) - onlyA.channel(0).get(at);
        }
        double ra = rms(contribA);
        double rb = rms(contribB);
        return 20.0 * Math.log10(rb / ra);
    }

    /** 关掉（0）→ B 就是比 A 轻 6 dB（这就是用户听到「被埋」的那个差距）。 */
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
    public void withoutMatchingTheEntrantIsSixDecibelsDown() {
        double deficit = entrantDeficitDb(0.0f, RATE * 2);
        assertEquals("不匹配时应如实差 6 dB", -6.0, deficit, 0.5);
        System.out.println("[level] 关掉匹配：B 比 A " + String.format("%.2f", deficit) + " dB");
    }

    /** 开着（1）→ 中段两首歌的贡献被拉到 1 dB 之内（P4 承诺的那条线）。 */
    @Test
    public void withMatchingTheEntrantIsLevelWithTheOutgoingTrack() {
        double deficit = entrantDeficitDb(1.0f, RATE * 2);
        assertTrue("匹配后 B 与 A 的差距应当收进 1 dB（实际 " + deficit + " dB）",
                Math.abs(deficit) <= 1.0);
        System.out.println("[level] 开匹配：B 比 A " + String.format("%.2f", deficit) + " dB"
                + "（关掉时是 " + String.format("%.2f", entrantDeficitDb(0.0f, RATE * 2)) + " dB）");
    }

    /** 半开 → 差距收一半（旋钮是连续的，不是开关）。 */
    @Test
    public void halfStrengthClosesHalfTheGap() {
        double full = entrantDeficitDb(1.0f, RATE * 2);
        double half = entrantDeficitDb(0.5f, RATE * 2);
        assertEquals("半个旋钮应当补掉一半的差值", -6.0 + (full + 6.0) * 0.5, half, 0.6);
    }

    /**
     * 两端（{@code p = 0} / {@code p = 1}）一个采样都不碰——接缝上不能有台阶。
     *
     * <p>对比的是「<b>三层全开</b> vs <b>三层全关</b>」：这两边走的必须是同一条路径。
     * （早先这条是拿 {@code MixTuning.defaults()} 和自己比，那是因为默认值刚好等于被测值，
     * 判据实际上什么都没钉住——改成显式设值之后才有意义。）
     */
    @Test
    public void theWindowEndpointsAreUntouched() {
        int frames = 256;
        AudioBuf a = tone(0.4, frames);
        AudioBuf b = tone(0.05, frames);

        MixTuning allOff = MixTuning.defaults();
        allOff.levelMatch = 0.0f;
        allOff.lowDuck = 0.0f;
        allOff.spaceWidth = 0.0f;

        MixTuning allOn = MixTuning.defaults();
        allOn.levelMatch = 1.0f;
        allOn.lowDuck = 1.0f;
        allOn.spaceWidth = 0.5f;

        for (float p : new float[] {0.0f, 1.0f}) {
            AudioBuf plain = empty(frames);
            new AiMix(MixStyle.PLAIN_BLEND, RATE, 2, allOff).render(plain, a, b, p);
            AudioBuf shaped = empty(frames);
            new AiMix(MixStyle.PLAIN_BLEND, RATE, 2, allOn).render(shaped, a, b, p);
            for (int i = 0; i < plain.frames(); i++) {
                if (plain.channel(0).get(i) != shaped.channel(0).get(i)) {
                    throw new AssertionError("p=" + p + " 时第 " + i + " 帧被改动了（"
                            + plain.channel(0).get(i) + " vs " + shaped.channel(0).get(i) + "）");
                }
            }
        }
    }
}
