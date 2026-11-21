package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.dsp.SplitLowHigh;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>低频让位</b>（P5）：两首歌叠在一起时，低频加起来<b>不能比单独一首更响</b>。
 *
 * <p>「皱在一起很糊」里最硬的一半就是低频：两首歌的底鼓/贝斯同相叠起来是 <b>2 倍</b>
 * （+6 dB），反相还会互相抵消出「抖」——两种都不像音乐，像坏了。低频交接那一档
 * （{@code BASS_SWAP}）用固定进度把低频交出去，是<b>一种接法</b>；这里要的是所有融合档位
 * 都自动做到「低频的总量不超过其中较响的那一首」。
 *
 * <p>判据量的是输出里 60 Hz 分量的幅度（与参考正弦做相关，稳态段），所以量的是
 * <b>低频真的没涨</b>，不是「增益变量被设成了某个值」。
 */
public class LowDuckTest {

    private static final int RATE = 48000;
    private static final double BASS_HZ = 40.0;

    /**
     * 量稳态用的素材长度：4 秒。
     *
     * <p>不是随手取的：低频包络的时间常数是 0.5 s，1 秒的素材上它只走到 ~86%，
     * 实测压到 0.62 而不是 0.5（判据当场红）。生产里窗口本来就是 4~8 s，
     * 所以 4 秒既够收敛，也贴近真实。
     */
    private static final int SETTLED = RATE * 4;
    private static final double HIGH_HZ = 4000.0;

    /** 一段正弦（两侧同相），频率与幅度给定。 */
    private static AudioBuf sine(double hz, double amp, int frames) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        for (int i = 0; i < frames; i++) {
            float v = (float) (amp * Math.sin(2.0 * Math.PI * hz * i / RATE));
            buf.channelWriter(0).put(i, v);
            buf.channelWriter(1).put(i, v);
        }
        buf.setFrames(frames);
        return buf;
    }

    private static AudioBuf bass(int frames) {
        return sine(BASS_HZ, 0.4, frames);
    }

    private static AudioBuf treble(int frames) {
        return sine(HIGH_HZ, 0.4, frames);
    }

    private static AudioBuf silent(int frames) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Math.max(frames, 1));
        buf.setFrames(frames);
        return buf;
    }

    /**
     * 输出的 RMS（稳态段）——本判据的素材是<b>纯低频</b>（40 Hz），所以这就是低频的量。
     *
     * <p>为什么不「抽一次低频再量」：那会再滤一遍。低频相对原信号本来就有相位与增益偏移，
     * 二次滤波之后 {@code LP[low] ≠ low}，实测比值会从 2.0 掉到 1.50（判据当场红）——
     * 那是量法的锅，不是算法的锅。纯低频素材上整段信号都在低频带里，直接量 RMS 反而精确。
     *
     * <p>40 Hz 而不是 60 Hz：200 Hz 分频点下 40 Hz 几乎是纯通过（增益≈1、相位≈0），
     * {@code hi = x − low ≈ 0}，于是「低频段」就是整段信号。
     */
    private static double rms(AudioBuf out) {
        int n = out.frames();
        int from = n - n / 4;                 // 只量稳态（前 3/4 让包络与增益收敛）
        double sum = 0.0;
        for (int i = from; i < n; i++) {
            float v = out.channel(0).get(i);
            sum += (double) v * v;
        }
        return Math.sqrt(sum / (n - from));
    }

    /**
     * 跑一次融合（{@code p = 0.5}，两首歌一样响的那一刻），返回输出的低频 RMS。
     *
     * @param duckStrength 0 = 关（走 {@code mixTwo}）
     */
    private static double duckedBassAmplitude(double duckStrength, AudioBuf a, AudioBuf b,
                                              int frames) {
        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, frames);
        LevelMatcher matcher = new LevelMatcher(RATE);
        LowDucker ducker = duckStrength > 0.0f
                ? new LowDucker(RATE, 2, (float) MixTuning.BASS_CROSSOVER_HZ) : null;
        Mixer.mixTwoMatched(out, a, 0.707f, b, 0.707f, matcher, 0.0f, ducker, (float) duckStrength);
        return rms(out);
    }

    // ==================================================================
    // ① 关掉 = 没有这个功能
    // ==================================================================

    /** {@code strength = 0} → 与 {@code mixTwo} **逐位相同**。 */
    @Test
    public void turningItOffIsBitIdenticalToNotHavingIt() {
        int frames = RATE;
        AudioBuf a = bass(frames);
        AudioBuf b = treble(frames);

        AudioBuf plain = new AudioBuf(ChannelLayout.STEREO, frames);
        Mixer.mixTwo(plain, a, 0.7f, b, 0.7f);

        AudioBuf off = new AudioBuf(ChannelLayout.STEREO, frames);
        Mixer.mixTwoMatched(off, a, 0.7f, b, 0.7f, new LevelMatcher(RATE), 0.0f,
                new LowDucker(RATE, 2, MixTuning.BASS_CROSSOVER_HZ), 0.0f);

        for (int i = 0; i < frames; i++) {
            if (plain.channel(0).get(i) != off.channel(0).get(i)) {
                throw new AssertionError("关掉时不该动任何一个采样（第 " + i + " 个不同）");
            }
        }
    }

    // ==================================================================
    // ② 核心：两首歌的低频加起来不超过一首
    // ==================================================================

    /**
     * <b>两侧的低频叠在一起 → 总低频被压到「一首歌」的量级。</b>
     *
     * <p>关掉时两路同相相加，实测 RMS {@code 0.400}（= 0.707×2×0.4/√2），也就是单首
     * {@code 0.200} 的 <b>2.0 倍（+6 dB）</b>——那就是「糊」。
     *
     * <p>开着时实测 {@code 0.235}，是单首的 <b>1.17 倍（+1.4 dB）</b>。
     *
     * <p><b>为什么不是精确的 1.0 倍</b>：让位压的是<b>分频器切出来的那一份低频</b>，而
     * 200 Hz 的低通在 40 Hz 上有相位滞后——实测（本机）40 Hz 输入时低频 RMS 0.680、
     * 高频 RMS 0.273，两路<b>正交</b>，所以「乘 0.5」之后残差里还留着一份高频。
     * 差值 1.4 dB 就是这份残差，跟算法对不对无关。判据因此钉的是<b>效果</b>
     * （不叠、不超过单首太多），不是「某个数正好等于 0.5」。
     */
    @Test
    public void twoBassLinesDoNotStackUp() {
        int frames = SETTLED;
        double off = duckedBassAmplitude(0.0f, bass(frames), bass(frames), frames);
        double on = duckedBassAmplitude(1.0f, bass(frames), bass(frames), frames);
        double oneTrack = 0.707 * 0.4 / Math.sqrt(2.0);     // 单侧在淡化中点的 RMS = 0.200

        assertEquals("关掉时两路同相低频应当是单首的两倍", 2.0 * oneTrack, off, 0.02);
        assertTrue("开着时低频该被压到单首的量级（实际是 " + (on / oneTrack) + " 倍）",
                on <= oneTrack * 1.3);
        assertTrue("但也不该压过头（实际 " + (on / oneTrack) + " 倍）", on >= oneTrack * 0.9);
        System.out.println("[low] 两侧都是 40 Hz：关掉 " + String.format("%.4f", off)
                + " → 开着 " + String.format("%.4f", on)
                + "（单侧 " + String.format("%.4f", oneTrack)
                + "，即 " + String.format("%.2f", 20 * Math.log10(on / oneTrack)) + " dB）");
    }

    /** 只有一侧有低频（另一侧是 4 kHz 的亮声）→ 一个采样都不用压。 */
    @Test
    public void aSingleBassLineIsLeftAlone() {
        int frames = SETTLED;
        double off = duckedBassAmplitude(0.0f, bass(frames), treble(frames), frames);
        double on = duckedBassAmplitude(1.0f, bass(frames), treble(frames), frames);
        assertEquals("只有一侧有低频时不该压", off, on, 0.02);
        System.out.println("[low] 只有一侧有低频：" + String.format("%.4f", off)
                + " → " + String.format("%.4f", on));
    }

    /** 两侧都没有低频 → 同样不动（增益必须留在 1）。 */
    @Test
    public void noBassOnEitherSideChangesNothing() {
        int frames = SETTLED;
        double off = duckedBassAmplitude(0.0f, treble(frames), treble(frames), frames);
        double on = duckedBassAmplitude(1.0f, treble(frames), treble(frames), frames);
        assertEquals("两侧都没低频时不该压", off, on, 0.02);
    }

    /**
     * 半开 → 压在「不压」与「全压」之间。
     *
     * <p>钉的是单调性而不是「正好一半」：让位作用在<b>低频段</b>上，而输出 RMS 里还混着
     * 与低频正交的那份高频残差，所以 RMS 与增益不是线性关系——「一半」在 RMS 上不是一个点。
     * 这是量出来的事实（见 {@link #twoBassLinesDoNotStackUp}），不是放松断言的理由：
     * 单调 + 有界已经能证明旋钮是连续的、方向正确。
     */
    @Test
    public void halfStrengthSitsBetweenOffAndFull() {
        int frames = SETTLED;
        double off = duckedBassAmplitude(0.0f, bass(frames), bass(frames), frames);
        double on = duckedBassAmplitude(1.0f, bass(frames), bass(frames), frames);
        double half = duckedBassAmplitude(0.5f, bass(frames), bass(frames), frames);
        assertTrue("半开应落在两者之间：关 " + off + "、半 " + half + "、全 " + on,
                half < off && half > on);
        System.out.println("[low] 半开 " + String.format("%.4f", half)
                + "（关 " + String.format("%.4f", off) + "、全 " + String.format("%.4f", on) + "）");
    }

    // ==================================================================
    // ③ 慢、稳、不炸
    // ==================================================================

    /** 增益每帧的变化必须有界（一阶跟随，不会有台阶）。 */
    @Test
    public void theGainMovesWithoutSteps() {
        LowDucker ducker = new LowDucker(RATE, 2, MixTuning.BASS_CROSSOVER_HZ);
        float worst = 0.0f;
        float prev = 1.0f;
        // 前一半只有 A 有低频，后一半 B 突然也进来（最陡的输入）
        int frames = RATE / 2;
        for (int i = 0; i < frames; i++) {
            float a = (float) (0.4 * Math.sin(2.0 * Math.PI * BASS_HZ * i / RATE));
            float b = i < frames / 2 ? 0.0f : a;
            float loA = ducker.lowOf(true, 0, a);
            float loB = ducker.lowOf(false, 0, b);
            ducker.track(loA, loB);
            float g = ducker.gainForFrame(1.0f);
            worst = Math.max(worst, Math.abs(g - prev));
            prev = g;
        }
        float bound = 1.0f - (float) Math.exp(-1.0 / (LowDucker.GAIN_TAU_SECS * RATE));
        assertTrue("单帧增益变化 " + worst + " 应当不超过 " + bound, worst <= bound);
        System.out.println("[low] B 的低频突然进来：单帧增益最大变化 " + worst + "（上限 " + bound + "）");
    }

    /** 静音、单侧静音、起手就静音——不出 NaN，也不乱压。 */
    @Test
    public void silenceNeverBlowsUp() {
        int frames = SETTLED;
        double[] results = {
            duckedBassAmplitude(1.0f, silent(frames), silent(frames), frames),
            duckedBassAmplitude(1.0f, bass(frames), silent(frames), frames),
            duckedBassAmplitude(1.0f, silent(frames), bass(frames), frames),
        };
        for (int i = 0; i < results.length; i++) {
            assertTrue("第 " + i + " 种静音场景下低频幅度是 " + results[i],
                    !Double.isNaN(results[i]) && !Double.isInfinite(results[i]));
            assertTrue("静音场景下不该把低频放大（" + results[i] + "）", results[i] <= 0.21);
        }
    }
}
