package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>中/侧让位</b>（P2）：把一路推向两侧、另一路收窄到中间，让重叠期的两首歌在空间上分开。
 *
 * <p>判据只钉数学与两条纪律：**关闭时逐位不动**（不是「乘 1」）、能量与相关度的变化方向正确。
 */
public class MixerSpaceTest {

    private static final int N = 1024;

    /** 关闭（两个增益都是 1）→ **逐位相同**。 */
    @Test
    public void unityGainsLeaveTheBufferBitIdentical() {
        AudioBuf buf = stereo(0.31f, -0.22f);
        float[] before = snapshot(buf);
        Mixer.applyStereoWidth(buf, N, 1.0f, 1.0f);
        float[] after = snapshot(buf);
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                throw new AssertionError("关闭时不该动任何一个采样（第 " + i + " 个不同）");
            }
        }
    }

    /** 纯中间信号（L=R）压低中间 → 电平按增益成比例下降；纯侧信号（L=−R）不受中间增益影响。 */
    @Test
    public void midAndSideGainsActOnTheirOwnComponentOnly() {
        AudioBuf center = stereo(1.0f, 1.0f);
        Mixer.applyStereoWidth(center, N, 0.5f, 1.0f);
        assertEquals("纯中间信号压低中间后应当只剩一半", 0.5f, center.channel(0).get(10), 1e-6f);

        AudioBuf side = stereo(1.0f, -1.0f);
        Mixer.applyStereoWidth(side, N, 0.5f, 1.0f);
        assertEquals("纯侧信号不受中间增益影响", 1.0f, side.channel(0).get(10), 1e-6f);

        AudioBuf sideMuted = stereo(1.0f, -1.0f);
        Mixer.applyStereoWidth(sideMuted, N, 1.0f, 0.0f);
        assertEquals("两侧压到 0 后纯侧信号应当消失", 0.0f, sideMuted.channel(0).get(10), 1e-6f);
    }

    /** 把侧分量拿掉 → 两个声道变成完全相同（相关度 = 1，即「收窄到中间」）。 */
    @Test
    public void removingTheSideComponentCollapsesToMono() {
        AudioBuf buf = randomStereo();
        Mixer.applyStereoWidth(buf, N, 1.0f, 0.0f);
        for (int i = 0; i < N; i++) {
            assertEquals("两侧分量拿掉后左右应当完全相同（第 " + i + " 帧）",
                    buf.channel(0).get(i), buf.channel(1).get(i), 1e-6f);
        }
    }

    /** 加宽（中间压低、两侧抬高）→ 左右相关度**下降**，而总能量大致守恒（不相关素材）。 */
    @Test
    public void wideningLowersInterChannelCorrelation() {
        AudioBuf buf = randomStereo();
        double before = correlation(buf);
        double energyBefore = energy(buf);
        Mixer.applyStereoWidth(buf, N, 0.5f, 1.5f);
        double after = correlation(buf);
        double energyAfter = energy(buf);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[space] 相关度 %.3f → %.3f · 能量 %.3f → %.3f",
                Double.valueOf(before), Double.valueOf(after),
                Double.valueOf(energyBefore), Double.valueOf(energyAfter)));
        assertTrue("加宽之后相关度必须下降（两路在空间上分开）", after < before);
        assertTrue("能量不该出现量级变化（0.5~2 倍以内）",
                energyAfter > energyBefore * 0.5 && energyAfter < energyBefore * 2.0);
    }

    /** 单声道缓冲：no-op（M/S 在单声道上没有定义）。 */
    @Test
    public void monoBuffersAreLeftAlone() {
        AudioBuf mono = new AudioBuf(ChannelLayout.MONO, N);
        for (int i = 0; i < N; i++) {
            mono.channelWriter(0).put(i, 0.4f);
        }
        mono.setFrames(N);
        Mixer.applyStereoWidth(mono, N, 0.5f, 1.5f);
        assertEquals("单声道不该被改写", 0.4f, mono.channel(0).get(5), 1e-9f);
    }

    /**
     * <b>接线判据</b>：把旋钮打开，{@code AiMix} 渲染出来的出场曲**真的**变宽了；
     * 关着时与没有这个功能一样（逐位相同）。
     */
    @Test
    public void turningTheKnobOnMakesTheOutgoingDeckWider() {
        double before = correlation(renderAiMix(0.0f));
        double after = correlation(renderAiMix(1.0f));
        System.out.println(String.format(java.util.Locale.ROOT,
                "[space] AiMix 渲染的出场曲相关度：关 %.3f → 开 %.3f",
                Double.valueOf(before), Double.valueOf(after)));
        assertTrue("开了空间让位之后出场曲必须被推向两侧（相关度下降），实际 "
                + before + " → " + after, after < before - 0.05f);
    }

    /** 用 {@code AiMix} 渲染一块：A 是不相关的立体声，B 静音，所以输出就是「加宽后的 A」。 */
    private static AudioBuf renderAiMix(float spaceWidth) {
        MixTuning tuning = MixTuning.defaults();
        tuning.spaceWidth = spaceWidth;
        AiMix mix = new AiMix(MixStyle.PLAIN_BLEND, 48_000, 2, tuning);
        AudioBuf from = randomStereo();
        AudioBuf to = silent();
        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        try {
            mix.render(out, from, to, 0.5f);      // 窗口正中：空间让位最强
        } catch (RuntimeException e) {
            throw new AssertionError("AiMix 渲染不该抛异常：" + e, e);
        }
        return out;
    }

    private static AudioBuf silent() {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, N * 4);
        for (int i = 0; i < N * 4; i++) {
            buf.channelWriter(0).put(i, 0.0f);
            buf.channelWriter(1).put(i, 0.0f);
        }
        buf.setFrames(N * 4);
        return buf;
    }

    // ------------------------------------------------------------------

    private static AudioBuf stereo(float l, float r) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, N);
        for (int i = 0; i < N; i++) {
            buf.channelWriter(0).put(i, l);
            buf.channelWriter(1).put(i, r);
        }
        buf.setFrames(N);
        return buf;
    }

    private static AudioBuf randomStereo() {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, N);
        java.util.Random rnd = new java.util.Random(7L);
        for (int i = 0; i < N; i++) {
            buf.channelWriter(0).put(i, (float) (rnd.nextDouble() * 2 - 1) * 0.3f);
            buf.channelWriter(1).put(i, (float) (rnd.nextDouble() * 2 - 1) * 0.3f);
        }
        buf.setFrames(N);
        return buf;
    }

    private static float[] snapshot(AudioBuf buf) {
        float[] out = new float[N * 2];
        for (int i = 0; i < N; i++) {
            out[i * 2] = buf.channel(0).get(i);
            out[i * 2 + 1] = buf.channel(1).get(i);
        }
        return out;
    }

    private static double correlation(AudioBuf buf) {
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < N; i++) {
            double l = buf.channel(0).get(i);
            double r = buf.channel(1).get(i);
            dot += l * r;
            na += l * l;
            nb += r * r;
        }
        return dot / Math.sqrt(Math.max(na * nb, 1e-12));
    }

    private static double energy(AudioBuf buf) {
        double sum = 0.0;
        for (int i = 0; i < N; i++) {
            double l = buf.channel(0).get(i);
            double r = buf.channel(1).get(i);
            sum += l * l + r * r;
        }
        return sum;
    }
}
