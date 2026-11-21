package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

/**
 * <b>名字里带 REVERB 的那 5 档必须真的有混响</b>（2026-10-07，用户"全部让他们名副其实"）。
 *
 * <p>判据用**只在前 2000 个样本发声**的出场曲（之后全零）+ 静音的进场曲 ✓：
 * 输出里"尾巴段"的能量**只可能来自混响** ✓ ——
 * <ul>
 *   <li>带 REVERB 的档：尾巴能量必须明显 &gt; 0 ✓（而且随时间衰减 ✓）；</li>
 *   <li>对应的**不带混响**的档（同一个底座 ✓）：尾巴必须**恰好是 0** ✓（输入全零 ⇒ 输出全零 ✓）；</li>
 *   <li>把 `reverbSend` 设成 0 ⇒ 与不带混响的那一档**逐位相同** ✓（能精确回退 ✓）。</li>
 * </ul>
 */
public class ReverbRouteTest {

    private static final int RATE = 48_000;
    private static final int CH = 2;
    private static final int BLOCK = Deck.RENDER_FRAMES;
    /** 出声的前多少个样本（之后全零 ⇒ 尾巴里只可能是混响 ✓）。 */
    private static final int BURST = 2000;

    private static float[] render(MixStyle style, boolean reverbOn) {
        MixTuning tuning = MixTuning.defaults();
        if (!reverbOn) {
            tuning.reverbSend = 0f;
            tuning.reverbWet = 0f;
        }
        AiMix mix = new AiMix(style, RATE, CH, tuning, () -> 6.0);
        long frames = 12L * BLOCK;
        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, BLOCK);
        AudioBuf from = new AudioBuf(ChannelLayout.STEREO, BLOCK);
        AudioBuf to = new AudioBuf(ChannelLayout.STEREO, BLOCK);
        float[] mono = new float[(int) frames];
        long seed = 987654321L;
        int written = 0;
        long pos = 0L;
        while (pos < frames) {
            float[] fb = from.backingArray();
            float[] tb = to.backingArray();
            for (int i = 0; i < BLOCK; i++) {
                long absolute = pos + i;
                float v = 0f;
                if (absolute < BURST) {
                    seed = seed * 6364136223846793005L + 1442695040888963407L;
                    v = ((float) ((seed >> 40) / (double) (1 << 23)) - 1.0f) * 0.5f;
                }
                fb[i] = v;
                fb[from.channelBase(1) + i] = v;
                tb[i] = 0f;
                tb[to.channelBase(1) + i] = 0f;
            }
            from.setFrames(BLOCK);
            to.setFrames(BLOCK);
            float progress = (float) Math.min(1.0, pos / (double) frames);
            int got = mix.render(out, from, to, progress);
            float[] ob = out.backingArray();
            for (int i = 0; i < got && written < mono.length; i++) {
                mono[written++] = ob[i];
            }
            pos += BLOCK;
        }
        float[] trimmed = new float[written];
        System.arraycopy(mono, 0, trimmed, 0, written);
        return trimmed;
    }

    private static double tailRms(float[] pcm) {
        int from = pcm.length / 2;                 // 后半段：输入早已全零 ✓
        double sum = 0.0;
        for (int i = from; i < pcm.length; i++) {
            sum += pcm[i] * (double) pcm[i];
        }
        return Math.sqrt(sum / Math.max(1, pcm.length - from));
    }

    /**
     * 这条判据量的是「**我们自己的**混响机制：关掉混响 ⇒ 与从前逐位相同」✓。
     *
     * <p>用户 2026-10-08「完全照搬他的写法」之后 ✓，同一批档的**增益/截止频率**已改走他的段表 ✓
     * ⇒ "关掉混响"这个动作不再能回到从前的逐位一致 ✓（第一个不同出现在下标 1215 ✓，那是照搬改的地方 ✓，
     * 不是混响造成的 ✗）。所以这里把**照搬的两个开关成对关掉** ✓（判据量的对象因此仍是"我们自己的混响" ✓），
     * 跑完再**还原** ✓ —— 静态开关一律配对，这是我给自己定的纪律 ✓（此前吃过静态泄漏的亏 ✓）。
     */
    @org.junit.BeforeClass
    public static void useOurOwnStagesOnly() {
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleFilterPort(false);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleGainPort(false);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleReverbPort(false);
        // 混响的**参数**（衰减/湿/送）也已改走他的表 ✓ ⇒ 这条判据要量"我们自己的混响"
        // 就必须同样关掉它 ✓（成对设置 ✓）。
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleReverbParamsPort(false);
    }

    @org.junit.AfterClass
    public static void restoreApplePorts() {
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleFilterPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleGainPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleReverbPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleReverbParamsPort(true);
    }

    @Test
    public void reverbRoutesRingOutAndDryRoutesAreExactlySilent() {
        MixStyle[][] pairs = {
            {MixStyle.CROSSFADE_OVERLAP_REVERB, MixStyle.CROSSFADE_OVERLAP},
            {MixStyle.CROSSFADE_RINGOUT_REVERB, MixStyle.CROSSFADE_RINGOUT},
            // ⚠️ 对照要挑**同底座、而且现在真的没加效果**的档 ✓：
            // 我第一版这里写的是 `POP_DELAY_OUT` ✗ —— 它在这一轮**自己也有了延迟** ✓
            // ⇒ 不再静音，判据当场照出来 ✓（这正是判据该干的事 ✓）。
            {MixStyle.POP_REVERB, MixStyle.CROSSFADE_OVERLAP},
            {MixStyle.FILTER_SOFT_REVERB, MixStyle.FILTER_HIGH_TO_LOW},
            {MixStyle.FILTER_LONG_OUT_SHORT_IN_REVERB, MixStyle.FILTER_LONG_OUT_SHORT_IN},
        };
        for (MixStyle[] pair : pairs) {
            double wet = tailRms(render(pair[0], true));
            double dry = tailRms(render(pair[1], true));
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[混响] %s：尾巴 RMS %.6f（该 > 0）· 对照 %s：%.9f（该 = 0）",
                    pair[0].label(), Double.valueOf(wet), pair[1].label(), Double.valueOf(dry)));
            assertTrue(pair[0].label() + " 的尾巴必须是混响（实测 RMS " + wet + "）", wet > 1e-4);
            assertTrue(pair[1].label() + " 不该有尾巴（实测 RMS " + dry + "）", dry < 1e-7);
        }
    }

    @Test
    public void turningReverbOffIsBitIdentical() {
        float[] off = render(MixStyle.CROSSFADE_OVERLAP_REVERB, false);
        float[] base = render(MixStyle.CROSSFADE_OVERLAP, true);
        assertEquals("长度必须一样", base.length, off.length);
        int firstDiff = -1;
        for (int i = 0; i < base.length; i++) {
            if (Float.floatToRawIntBits(base[i]) != Float.floatToRawIntBits(off[i])) {
                firstDiff = i;
                break;
            }
        }
        System.out.println(String.format(java.util.Locale.ROOT,
                "[混响] 关掉之后与不带混响的档逐位比较：%d 帧，第一个不同 = %d",
                Integer.valueOf(base.length), Integer.valueOf(firstDiff)));
        assertEquals("关掉混响 ⇒ 必须逐位相同（第一个不同在下标 " + firstDiff + "）", -1, firstDiff);
    }
}
