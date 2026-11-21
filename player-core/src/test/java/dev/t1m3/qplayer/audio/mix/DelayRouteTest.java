package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

/**
 * <b>名字里带 DELAY 的那 3 档必须真的有跟拍延迟</b>（2026-10-07「全部让他们名副其实」）。
 *
 * <p>判据：出场曲在**第 0 个样本**给一个很短的脉冲、之后全零 ✓，进场曲静音 ✓ ⇒
 * 输出里**脉冲之外的能量只可能来自延迟** ✓，而且它必须出现在**拍长算出来的那一刻** ✓：
 * 本次 `beatSecs` 取默认 0.5 s（没说 prepare ⇒ 兜底 120 BPM ✓），
 * {@code delayBeats = 0.75} ⇒ 期望回声在 **0.375 s = 18000 样本** ✓（±10 ms ✓）。
 */
public class DelayRouteTest {

    private static final int RATE = 48_000;
    private static final int CH = 2;
    private static final int BLOCK = Deck.RENDER_FRAMES;
    /** 脉冲长度（样本 ✓）—— 远短于延时，回声不会和它糊在一起 ✓。 */
    private static final int BURST = 240;
    /** 期望延时：0.75 拍 × 0.5 s ✓。 */
    private static final int EXPECTED = (int) Math.round(0.75 * 0.5 * RATE);

    private static float[] render(MixStyle style, boolean delayOn) {
        MixTuning tuning = MixTuning.defaults();
        if (!delayOn) {
            tuning.delaySend = 0f;
            tuning.delayWet = 0f;
        }
        AiMix mix = new AiMix(style, RATE, CH, tuning, () -> 6.0);
        long frames = 16L * BLOCK;
        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, BLOCK);
        AudioBuf from = new AudioBuf(ChannelLayout.STEREO, BLOCK);
        AudioBuf to = new AudioBuf(ChannelLayout.STEREO, BLOCK);
        float[] mono = new float[(int) frames];
        long seed = 24680L;
        int written = 0;
        long pos = 0L;
        while (pos < frames) {
            float[] fb = from.backingArray();
            float[] tb = to.backingArray();
            for (int i = 0; i < BLOCK; i++) {
                float v = 0f;
                if (pos + i < BURST) {
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

    /** 脉冲之后（跳过脉冲本身 ✓）绝对值最大的那一点。 */
    private static int echoPeak(float[] pcm) {
        int best = -1;
        double bestV = 0.0;
        for (int i = BURST; i < pcm.length; i++) {
            double v = Math.abs(pcm[i]);
            if (v > bestV) {
                bestV = v;
                best = i;
            }
        }
        return best;
    }

    /** 这条判据量"我们自己的延迟机制" ✓；照搬后参数走他的表 ✓ ⇒ 成对关掉再还原 ✓。 */
    @org.junit.BeforeClass
    public static void useOurOwnDelayParams() {
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleDelayParamsPort(false);
    }

    @org.junit.AfterClass
    public static void restoreAppleDelayParams() {
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleDelayParamsPort(true);
    }

    @Test
    public void delayRoutesEchoOnTheBeatAndDryRoutesAreSilent() {
        MixStyle[][] pairs = {
            {MixStyle.HIPHOP_DELAY_OUT, MixStyle.CROSSFADE_RINGOUT},
            {MixStyle.POP_DELAY_OUT, MixStyle.CROSSFADE_RINGOUT},
            {MixStyle.FILTER_LONG_OUT_SHORT_IN_DELAY, MixStyle.FILTER_LONG_OUT_SHORT_IN},
        };
        for (MixStyle[] pair : pairs) {
            float[] wet = render(pair[0], true);
            int peak = echoPeak(wet);
            double peakMs = peak / (double) RATE * 1000.0;
            double wantMs = EXPECTED / (double) RATE * 1000.0;
            double dryTail = 0.0;
            float[] dry = render(pair[1], true);
            for (int i = BURST; i < dry.length; i++) {
                dryTail = Math.max(dryTail, Math.abs(dry[i]));
            }
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[延迟] %s：回声峰 @ %.1f ms（期望 %.1f ms）· 对照 %s 脉冲后最大 %.9f",
                    pair[0].label(), Double.valueOf(peakMs), Double.valueOf(wantMs),
                    pair[1].label(), Double.valueOf(dryTail)));
            assertTrue(pair[0].label() + " 脉冲之后必须有回声（峰在 " + peakMs + " ms）", peak >= 0);
            assertTrue(pair[0].label() + " 的回声必须落在附点八分音符上（实测 " + peakMs
                    + " ms，期望 " + wantMs + " ms）", Math.abs(peakMs - wantMs) <= 10.0);
            assertTrue(pair[1].label() + " 脉冲之后必须恰好静音（实测 " + dryTail + "）", dryTail < 1e-7);
        }
    }

    @Test
    public void turningDelayOffIsBitIdentical() {
        float[] off = render(MixStyle.POP_DELAY_OUT, false);
        float[] base = render(MixStyle.CROSSFADE_RINGOUT, true);
        assertEquals("长度必须一样", base.length, off.length);
        int firstDiff = -1;
        for (int i = 0; i < base.length; i++) {
            if (Float.floatToRawIntBits(base[i]) != Float.floatToRawIntBits(off[i])) {
                firstDiff = i;
                break;
            }
        }
        System.out.println(String.format(java.util.Locale.ROOT,
                "[延迟] 关掉之后与底座逐位比较：%d 帧，第一个不同 = %d",
                Integer.valueOf(base.length), Integer.valueOf(firstDiff)));
        assertEquals("关掉延迟 ⇒ 必须逐位相同（第一个不同在下标 " + firstDiff + "）", -1, firstDiff);
    }
}
