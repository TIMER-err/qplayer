package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import org.junit.Test;

/**
 * <b>过渡的前瞻软限幅</b>（2026-10-07）。
 *
 * <p>起因：用户实测 skydive→春めく 这类两张都响的素材，融合期"有点点炸麦"——
 * 等功率淡化让功率守恒，但峰值会在拍点上超过满刻度。这条判据钉三件事：
 * ① <b>天花板以下逐位透明</b>（不该动的绝不改，这是全仓库的纪律）；
 * ② 超过天花板时输出<b>真的不越界</b>（软拐点保证）；
 * ③ 它是"<b>软</b>"的：同样输入下，相邻样本的最大跳变必须比硬削小。
 */
public class TransitionLimiterTest {

    private static final int RATE = 48000;

    private static AudioBuf sine(double amplitude, int frames) {
        AudioBuf buf = new AudioBuf(ChannelLayout.of(2), frames);
        buf.setFrames(frames);
        for (int ch = 0; ch < 2; ch++) {
            java.nio.FloatBuffer o = buf.channelWriter(ch);
            for (int i = 0; i < frames; i++) {
                o.put(i, (float) (amplitude * Math.sin(2 * Math.PI * 220 * i / RATE)));
            }
        }
        return buf;
    }

    private static float peakOf(AudioBuf buf) {
        float peak = 0f;
        for (int ch = 0; ch < buf.channels(); ch++) {
            java.nio.FloatBuffer in = buf.channel(ch);
            for (int i = 0; i < buf.frames(); i++) {
                peak = Math.max(peak, Math.abs(in.get(i)));
            }
        }
        return peak;
    }

    private static float maxJump(AudioBuf buf) {
        float worst = 0f;
        java.nio.FloatBuffer in = buf.channel(0);
        for (int i = 1; i < buf.frames(); i++) {
            worst = Math.max(worst, Math.abs(in.get(i) - in.get(i - 1)));
        }
        return worst;
    }

    @Test
    public void belowTheCeilingNothingIsTouched() {
        AudioBuf buf = sine(0.5, 4096);              // −6 dBFS：远在天花板以下
        float[] before = new float[buf.frames()];
        java.nio.FloatBuffer in = buf.channel(0);
        for (int i = 0; i < buf.frames(); i++) {
            before[i] = in.get(i);
        }
        TransitionLimiter limiter = new TransitionLimiter(RATE, 2);
        limiter.process(buf, buf.frames());
        for (int i = 0; i < buf.frames(); i++) {
            assertEquals("天花板以下必须逐位不动（第 " + i + " 帧）", before[i], in.get(i), 0.0f);
        }
        assertTrue("没动作时不该报告动过", !limiter.acted());
    }

    @Test
    public void aboveTheCeilingThePeakIsContainedAndTheShapeStaysSoft() {
        AudioBuf loud = sine(1.6, 8192);             // +4 dBFS：一定会越界
        AudioBuf hard = sine(1.6, 8192);
        java.nio.FloatBuffer h = hard.channel(0);
        for (int i = 0; i < hard.frames(); i++) {     // 对照：硬削
            h.put(i, Math.max(-1f, Math.min(1f, h.get(i))));
        }
        TransitionLimiter limiter = new TransitionLimiter(RATE, 2);
        limiter.process(loud, loud.frames());
        float ceiling = limiter.ceilingLinear();
        assertTrue("输出峰值必须被压到天花板以内（实测 " + peakOf(loud) + " > " + ceiling + "）",
                peakOf(loud) <= ceiling + 1e-4f);
        assertTrue("软限幅必须比硬削平滑（软 " + maxJump(loud) + " vs 硬 " + maxJump(hard) + "）",
                maxJump(loud) < maxJump(hard) * 0.9f);
        assertTrue("该动作时必须报告动过", limiter.acted());
    }
}
