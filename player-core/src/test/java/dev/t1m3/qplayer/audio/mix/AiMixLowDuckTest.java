package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * <b>低频让位接进融合之后的效果</b>（P5，端到端）。
 *
 * <p>{@link LowDuckTest} 在 {@link Mixer} 那一层量；这一条走 {@link AiMix}，
 * 钉的是「<b>默认设置下</b>真的会发生」——旋钮在宿主里没有接入点，默认关等于没做。
 */
public class AiMixLowDuckTest {

    private static final int RATE = 48000;
    private static final double BASS_HZ = 40.0;

    private static AudioBuf bass(int frames, double amp) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        for (int i = 0; i < frames; i++) {
            float v = (float) (amp * Math.sin(2.0 * Math.PI * BASS_HZ * i / RATE));
            buf.channelWriter(0).put(i, v);
            buf.channelWriter(1).put(i, v);
        }
        buf.setFrames(frames);
        return buf;
    }

    private static AudioBuf empty(int frames) {
        return new AudioBuf(ChannelLayout.STEREO, Math.max(frames, 1));
    }

    /** 稳态段的 RMS。 */
    private static double rms(AudioBuf buf) {
        int n = buf.frames();
        int from = n - n / 4;
        double sum = 0.0;
        for (int i = from; i < n; i++) {
            float v = buf.channel(0).get(i);
            sum += (double) v * v;
        }
        return Math.sqrt(sum / (n - from));
    }

    /** 默认设置下，两首都是低频的曲子叠唱时，低频不该涨成两倍。 */
    @Test
    public void theDefaultTuningKeepsTheBassFromStackingUp() {
        int frames = RATE * 4;
        AudioBuf a = bass(frames, 0.4);
        AudioBuf b = bass(frames, 0.4);

        MixTuning off = MixTuning.defaults();
        off.lowDuck = 0.0f;
        off.spaceWidth = 0.0f;
        off.levelMatch = 0.0f;
        AudioBuf plain = empty(frames);
        new AiMix(MixStyle.PLAIN_BLEND, RATE, 2, off).render(plain, a, b, 0.5f);

        AudioBuf ducked = empty(frames);
        new AiMix(MixStyle.PLAIN_BLEND, RATE, 2).render(ducked, a, b, 0.5f);

        double ratio = rms(ducked) / rms(plain);
        assertTrue("默认设置下低频该被压下来（比值 " + ratio + "）", ratio < 0.75);
        assertTrue("但也不该压过头（比值 " + ratio + "）", ratio > 0.45);
        System.out.println("[low] AiMix 默认设置：叠唱低频 " + String.format("%.4f", rms(plain))
                + " → " + String.format("%.4f", rms(ducked))
                + "（" + String.format("%.2f", 20 * Math.log10(ratio)) + " dB）");
    }

    /** 两端（{@code p = 0} / {@code p = 1}）一个采样都不碰——让位也不能在出口留台阶。 */
    @Test
    public void theWindowEndpointsAreUntouched() {
        int frames = 512;
        AudioBuf a = bass(frames, 0.4);
        AudioBuf b = bass(frames, 0.4);

        MixTuning off = MixTuning.defaults();
        off.lowDuck = 0.0f;
        off.spaceWidth = 0.0f;
        off.levelMatch = 0.0f;

        for (float p : new float[] {0.0f, 1.0f}) {
            AudioBuf plain = empty(frames);
            new AiMix(MixStyle.PLAIN_BLEND, RATE, 2, off).render(plain, a, b, p);
            AudioBuf withDuck = empty(frames);
            new AiMix(MixStyle.PLAIN_BLEND, RATE, 2).render(withDuck, a, b, p);
            for (int i = 0; i < frames; i++) {
                if (plain.channel(0).get(i) != withDuck.channel(0).get(i)) {
                    throw new AssertionError("p=" + p + " 时第 " + i + " 帧被改动了");
                }
            }
        }
    }
}
