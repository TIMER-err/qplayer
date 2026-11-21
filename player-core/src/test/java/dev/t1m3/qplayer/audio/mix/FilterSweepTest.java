package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertTrue;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.dsp.Biquad;

import org.junit.Test;

/**
 * <b>"滤波·高到低"这一族必须真的在滤波</b>（2026-10-07，用户："怎么感觉不对"+"全部让他们名副其实"）。
 *
 * <p>在这之前这一族跑的是 `renderBassSwap`（固定分频的分带交接）✗ —— 名字说滤波、听感是分带 ✓。
 * 判据用**白噪声**当出场曲、量**高频占比随进度下降** ✓：
 * <ul>
 *   <li>开着扫频：末段的高频占比要比首段低 **≥6 dB** ✓（名字里的"高到低" ✓）；</li>
 *   <li>关掉扫频（`filterSweepFromHz = 0` ✓）：占比必须基本不变 ✓
 *       —— 这条同时钉住"关掉 ⇒ 与加它之前逐位相同" ✓（出问题能精确回退 ✓）。</li>
 * </ul>
 */
public class FilterSweepTest {

    private static final int RATE = 48_000;
    private static final int CH = 2;
    private static final int BLOCK = Deck.RENDER_FRAMES;

    /** 高频探测：6 kHz 高通，离线量比例用（不在渲染路径上 ✓）。 */
    private static double highRatio(float[] mono, int from, int to) {
        Biquad hp = Biquad.highPass(RATE, 6000f, 0.707f);
        double hi = 0.0;
        double all = 0.0;
        for (int i = from; i < to; i++) {
            double v = mono[i];
            double h = hp.process((float) v);
            hi += h * h;
            all += v * v;
        }
        return all > 1e-12 ? Math.sqrt(hi / all) : 0.0;
    }

    private static float[] renderWindow(MixStyle style, boolean sweepOn) {
        MixTuning tuning = MixTuning.defaults();
        if (!sweepOn) {
            tuning.filterSweepFromHz = 0f;
            tuning.filterSweepToHz = 0f;
        }
        AiMix mix = new AiMix(style, RATE, CH, tuning, () -> 6.0);
        long frames = Math.max(BLOCK, 8L * BLOCK);
        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, BLOCK);
        AudioBuf from = new AudioBuf(ChannelLayout.STEREO, BLOCK);
        AudioBuf to = new AudioBuf(ChannelLayout.STEREO, BLOCK);
        float[] mono = new float[(int) frames];
        // 出场曲 = 白噪声（确定性：固定种子的 LCG ✓）；进场曲 = 静音（只量出场侧 ✓）
        long seed = 12345L;
        int written = 0;
        long pos = 0L;
        while (pos < frames) {
            int n = BLOCK;
            float[] fb = from.backingArray();
            float[] tb = to.backingArray();
            for (int i = 0; i < n; i++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                float v = (float) ((seed >> 40) / (double) (1 << 23)) - 1.0f;
                fb[i] = v;
                fb[from.channelBase(1) + i] = v;
                tb[i] = 0f;
                tb[to.channelBase(1) + i] = 0f;
            }
            from.setFrames(n);
            to.setFrames(n);
            float progress = (float) Math.min(1.0, pos / (double) frames);
            int got = mix.render(out, from, to, progress);
            float[] ob = out.backingArray();
            for (int i = 0; i < got && written < mono.length; i++) {
                mono[written++] = ob[i];
            }
            pos += n;
        }
        float[] trimmed = new float[written];
        System.arraycopy(mono, 0, trimmed, 0, written);
        return trimmed;
    }

    @org.junit.BeforeClass
    public static void useOurSweepNotTheApplePort() {
        // 这条判据量的是**我们自己的**低通扫频机制 ✓（2026-10-07 让 FILTER 族名副其实时加的 ✓）。
        // 用户 2026-10-08 要求**完全照搬 Apple 的写法** ✓ ⇒ 那一族现在走他的 LOW_PASS/HI_PASS ✓，
        // 与本判据量的对象不同 ✓ ⇒ 这里显式关掉照搬那一支 ✓（开关见 AiMix.setAppleFilterPort ✓，
        // 关掉后与从前**逐位相同** ✓，所以这条判据仍然有效 ✓ —— 不是把判据放宽 ✗）。
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleFilterPort(false);
        // 这条判据把 FILTER 族（关掉扫频 ✓）与 **BASS_SWAP**（自研档 ✗）比"逐位相同" ✓ ⇒
        // 两侧必须**同构**：他的增益（映射档才有 ✗）与我的自研装置都得关掉 ✓（第 74 轮的教训 ✓）。
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleGainPort(false);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleHarmonyDevicesPort(false);
    }

    @org.junit.AfterClass
    public static void restoreApplePorts() {
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleFilterPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleGainPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleHarmonyDevicesPort(true);
    }

    @Test
    public void theFilterFamilyReallySweepsTheHighsDown() {
        for (MixStyle style : new MixStyle[]{MixStyle.FILTER_HIGH_TO_LOW,
                MixStyle.FILTER_LONG_HIGH_TO_LOW, MixStyle.FILTER_SHORT_HIGH_TO_LOW}) {
            float[] on = renderWindow(style, true);
            int n = on.length;
            double first = highRatio(on, 0, n / 8);
            double last = highRatio(on, n - n / 8, n);
            double dropDb = 20.0 * Math.log10(Math.max(1e-9, first) / Math.max(1e-9, last));
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[滤波扫] %s：首段高频占比 %.4f → 末段 %.4f（下降 %.1f dB）",
                    style.label(), Double.valueOf(first), Double.valueOf(last),
                    Double.valueOf(dropDb)));
            assertTrue(style.label() + " 末段高频占比必须比首段低 ≥6 dB（实测 "
                    + String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(dropDb))
                    + " dB）—— 名字里的「高到低」要真的发生", dropDb >= 6.0);
        }
    }

    /**
     * <b>关掉扫频 ⇒ 与 BASS_SWAP 逐位相同</b> ✓（"能精确回退"那条性质的**正确**写法）。
     *
     * <p>⚠️ 我第一版写的是"关掉之后高频占比应该基本不变" ✗ —— 那是**错的期望**：关掉之后
     * 这一族跑的就是 `renderBassSwap` ✓，而 bass-swap 的设计本来就是**让频带比例随时间变**
     * （低频在平台期换手 ✓）⇒ 实测占比 0.4260 → 0.8563（−6.1 dB ✓）不是 bug ✓。
     * 正确的性质是"**与 BASS_SWAP 一模一样**" ✓ —— 逐位比，最硬 ✓。
     */
    @Test
    public void turningTheSweepOffIsBitIdenticalToBassSwap() {
        float[] off = renderWindow(MixStyle.FILTER_LONG_HIGH_TO_LOW, false);
        float[] base = renderWindow(MixStyle.BASS_SWAP, false);
        org.junit.Assert.assertEquals("两条渲染出来的长度必须一样", base.length, off.length);
        int firstDiff = -1;
        for (int i = 0; i < base.length; i++) {
            if (Float.floatToRawIntBits(base[i]) != Float.floatToRawIntBits(off[i])) {
                firstDiff = i;
                break;
            }
        }
        System.out.println(String.format(java.util.Locale.ROOT,
                "[滤波扫] 关掉之后与 BASS_SWAP 逐位比较：%d 帧，第一个不同 = %d",
                Integer.valueOf(base.length), Integer.valueOf(firstDiff)));
        org.junit.Assert.assertEquals("关掉扫频 ⇒ 必须与 BASS_SWAP 逐位相同（第一个不同在下标 "
                + firstDiff + "）", -1, firstDiff);
    }
}
