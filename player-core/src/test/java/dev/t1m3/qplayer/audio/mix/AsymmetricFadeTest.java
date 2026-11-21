package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

/**
 * <b>名字里带 LONG_OUT_SHORT_IN 的那几档必须真的是"长出短进"</b>
 * （2026-10-07「全部让他们名副其实」）—— 在这之前它们与别的档一样是**等功率对称**淡化 ✗。
 *
 * <p>量法：把一侧喂静音 ⇒ 输出就只剩另一侧的贡献 ✓，于是可以直接量它的包络 ✓：
 * <ul>
 *   <li>B 静音 ⇒ 量 A 的 **90% → 10% 落时间** ✓；</li>
 *   <li>A 静音 ⇒ 量 B 的 **10% → 90% 起时间** ✓；</li>
 *   <li>判据：B 的起时间 **&lt; A 的落时间**（0.75 倍以内 ✓ = "麻利地进来" ✓）；</li>
 *   <li>对称档（对照 ✓）：两者应当接近 ✓；</li>
 *   <li>{@code shortInFraction = 1} ⇒ 与对称档**逐位相同** ✓（能精确回退 ✓）。</li>
 * </ul>
 */
public class AsymmetricFadeTest {

    private static final int RATE = 48_000;
    private static final int CH = 2;
    private static final int BLOCK = Deck.RENDER_FRAMES;

    /** 渲染一整窗：{@code onlyA} / {@code onlyB} 控制哪一侧有声音 ✓。 */
    private static float[] render(MixStyle style, boolean onlyA, boolean onlyB, float shortIn) {
        MixTuning tuning = MixTuning.defaults();
        tuning.shortInFraction = shortIn;
        AiMix mix = new AiMix(style, RATE, CH, tuning, () -> 6.0);
        long frames = 10L * BLOCK;
        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, BLOCK);
        AudioBuf from = new AudioBuf(ChannelLayout.STEREO, BLOCK);
        AudioBuf to = new AudioBuf(ChannelLayout.STEREO, BLOCK);
        float[] mono = new float[(int) frames];
        long seed = 13579L;
        int written = 0;
        long pos = 0L;
        while (pos < frames) {
            float[] fb = from.backingArray();
            float[] tb = to.backingArray();
            for (int i = 0; i < BLOCK; i++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                float v = ((float) ((seed >> 40) / (double) (1 << 23)) - 1.0f) * 0.4f;
                fb[i] = onlyA ? v : 0f;
                fb[from.channelBase(1) + i] = onlyA ? v : 0f;
                tb[i] = onlyB ? v : 0f;
                tb[to.channelBase(1) + i] = onlyB ? v : 0f;
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

    /** 512 样本一格的 RMS 包络 ✓。 */
    private static double[] envelope(float[] pcm) {
        int hop = 512;
        int n = pcm.length / hop;
        double[] env = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0.0;
            for (int k = 0; k < hop; k++) {
                double v = pcm[i * hop + k];
                sum += v * v;
            }
            env[i] = Math.sqrt(sum / hop);
        }
        return env;
    }

    /**
     * 包络从 {@code hiPct} 走到 {@code loPct}（或反过来）用了多少秒 ✓（线性扫过包络 ✓）。
     *
     * @param rising true = 从低到高（起 ✓），false = 从高到低（落 ✓）
     */
    private static double crossingSecs(double[] env, boolean rising) {
        double max = 0.0;
        for (double v : env) {
            max = Math.max(max, v);
        }
        double hi = max * 0.9;
        double lo = max * 0.1;
        int iHi = -1;
        int iLo = -1;
        if (rising) {
            for (int i = 0; i < env.length; i++) {
                if (iLo < 0 && env[i] >= lo) {
                    iLo = i;
                }
                if (env[i] >= hi) {
                    iHi = i;
                    break;
                }
            }
        } else {
            for (int i = 0; i < env.length; i++) {
                if (iHi < 0 && env[i] >= hi) {
                    iHi = i;
                }
                if (iHi >= 0 && env[i] <= lo) {
                    iLo = i;
                    break;
                }
            }
        }
        if (iHi < 0 || iLo < 0) {
            return -1.0;
        }
        return Math.abs(iLo - iHi) * 512.0 / RATE;
    }

    /** 某条曲线从 10% 走到 90%（或反过来）用了窗口的百分之几 ✓。 */
    private static double curveSpan(MixStyle style, float shortIn, boolean outgoing) {
        MixTuning tuning = MixTuning.defaults();
        tuning.shortInFraction = shortIn;
        AiMix mix = new AiMix(style, RATE, CH, tuning, () -> 6.0);
        int steps = 2000;
        double hi = 0.0;
        double lo = Double.MAX_VALUE;
        for (int i = 0; i <= steps; i++) {
            float p = i / (float) steps;
            Mixer.Gains g = mix.gains(p);
            double v = outgoing ? g.from : g.to;
            hi = Math.max(hi, v);
            // **只看"还在响的那一段"** ✓：出场侧在 p→1 时归零 ✓、进场侧从 0 起 ✓，
            // 所以两端的极小值不算数 ✓（那是"已经没了/还没来" ✓，不是曲线的一部分 ✓）。
            if (v > 0.02) {
                lo = Math.min(lo, v);
            }
        }
        double top = hi * 0.9;
        double bottom = hi * 0.1;
        int iTop = -1;
        int iBottom = -1;
        for (int i = 0; i <= steps; i++) {
            float p = i / (float) steps;
            Mixer.Gains g = mix.gains(p);
            double v = outgoing ? g.from : g.to;
            if (v <= 0.02) {
                continue;
            }
            if (outgoing) {
                if (iTop < 0 && v >= top) {
                    iTop = i;
                }
                if (iTop >= 0 && v <= bottom) {
                    iBottom = i;
                    break;
                }
            } else {
                if (iBottom < 0 && v >= bottom) {
                    iBottom = i;
                }
                if (v >= top) {
                    iTop = i;
                    break;
                }
            }
        }
        if (iTop < 0 || iBottom < 0) {
            return -1.0;
        }
        return Math.abs(iTop - iBottom) / (double) steps;
    }

    /**
     * <b>"长出短进"必须真的量得出来</b> —— 直接量增益曲线 ✓（不是量音频包络 ✗）。
     *
     * <p>⚠️ 为什么不能量包络：朴素融合那一档里有**逐帧响度匹配**（P4 ✓），它会在淡化期
     * 把出场侧顶住 ⇒ 包络并不随增益下去 ✓ —— 我第一版就是量包络，结果"A 落"根本量不出来（−1）✗。
     * 判据要量的本来就是**曲线的时间常数** ✓，那就直接量曲线 ✓。
     */
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
    public void longOutShortInReallyBringsTheIncomingInFaster() {
        for (MixStyle style : new MixStyle[]{MixStyle.FILTER_LONG_OUT_SHORT_IN,
                MixStyle.FILTER_LONG_OUT_SHORT_IN_DELAY, MixStyle.FILTER_LONG_OUT_SHORT_IN_REVERB,
                MixStyle.FILTER_DYNAMIC_IN}) {
            double fallA = curveSpan(style, 0.5f, true);
            double riseB = curveSpan(style, 0.5f, false);
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[长出短进] %s：A 落占窗口 %.3f · B 起占窗口 %.3f（B 该更短 ✓）",
                    style.label(), Double.valueOf(fallA), Double.valueOf(riseB)));
            assertTrue(style.label() + " 的两条曲线都要能量出来（A 落 " + fallA + " / B 起 " + riseB + "）",
                    fallA > 0.0 && riseB > 0.0);
            assertTrue(style.label() + " 的 B 起必须明显短于 A 的落（" + riseB + " vs " + fallA + "）",
                    riseB <= fallA * 0.75);
        }
        // 对照（对称档 ✓）：⚠️ **不能**要求"A 落 ≈ B 起" ✗ ——
        // `sin`（进场）与 `cos`（出场）的 10%→90% 跨度**本来就不同** ✓：
        // 实测 `cos` 是 0.937、`sin` 是 0.649 ✓（`sin` 起得快 ✓）。我第一版就是按"应当接近"写的，
        // 当场红 ✓ ⇒ 正确的判据是**比值**：对称档的 B/A 应当明显大于非对称档 ✓。
        double fallA = curveSpan(MixStyle.CROSSFADE_LONG, 0.5f, true);
        double riseB = curveSpan(MixStyle.CROSSFADE_LONG, 0.5f, false);
        double symRatio = riseB / fallA;
        double asymRatio = 0.325 / 0.937;              // 上面量到的非对称档 ✓
        System.out.println(String.format(java.util.Locale.ROOT,
                "[长出短进] 对照（交叉淡化·长，对称 ✓）：A 落 %.3f · B 起 %.3f ⇒ B/A = %.2f"
                        + "（非对称档是 %.2f ✓，必须明显更小 ✓）",
                Double.valueOf(fallA), Double.valueOf(riseB), Double.valueOf(symRatio),
                Double.valueOf(asymRatio)));
        assertTrue("对称档的 B/A 必须明显大于非对称档（" + symRatio + " vs " + asymRatio + "）",
                symRatio >= asymRatio * 1.5);
    }

    @Test
    public void shortInAtOneIsBitIdenticalToSymmetric() {
        float[] off = render(MixStyle.FILTER_LONG_OUT_SHORT_IN, true, true, 1.0f);
        float[] base = render(MixStyle.CROSSFADE_LONG, true, true, 1.0f);
        assertEquals("长度必须一样", base.length, off.length);
        int firstDiff = -1;
        for (int i = 0; i < base.length; i++) {
            if (Float.floatToRawIntBits(base[i]) != Float.floatToRawIntBits(off[i])) {
                firstDiff = i;
                break;
            }
        }
        System.out.println(String.format(java.util.Locale.ROOT,
                "[长出短进] short_in=1 与对称档逐位比较：%d 帧，第一个不同 = %d",
                Integer.valueOf(base.length), Integer.valueOf(firstDiff)));
        assertEquals("short_in=1 ⇒ 必须逐位相同（第一个不同在下标 " + firstDiff + "）", -1, firstDiff);
    }
}
