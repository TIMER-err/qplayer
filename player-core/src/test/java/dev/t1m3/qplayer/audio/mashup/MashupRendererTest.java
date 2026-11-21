package dev.t1m3.qplayer.audio.mashup;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link MashupRenderer} 的第一阶段判据：<b>拍点落在哪一帧</b>必须可数。
 *
 * <p>做法不靠"听起来对不对"：把两路素材都做成<b>已知位置的点击</b>，分别单独渲染，
 * 再比较输出里峰值出现的帧号。这样"映射错了一帧"是可判的，而不是主观的。
 */
public class MashupRendererTest {

    private static final int RATE = 48_000;

    /** 点击间隔 0.5 s。 */
    private static final int PERIOD = RATE / 2;

    @Test
    public void vocalsLandOnTheInstrumentalBeat() throws IOException {
        // B（伴奏）第 5000 帧第一次响，之后每 0.5 s 一次
        float[] instrumental = clicks(96_000, 5_000, PERIOD, 0.8f);
        // A（人声）第 9000 帧第一次响，之后同样每 0.5 s —— 要它落到 B 的拍（第 5000 帧）上，
        // 得把映射整体往后挪 4000 帧（out 5000 → src 9000）
        float[] vocals = clicks(100_000, 9_000, PERIOD, 0.8f);

        TimeMap map = TimeMap.of(new long[]{0, 96_000}, new long[]{4_000, 100_000});

        float[] aOnly = renderLeft(96_000, vocals, map, null, 0L, 1f, 0f);
        float[] bOnly = renderLeft(96_000, null, null, instrumental, 0L, 0f, 1f);

        assertEquals("B 单独渲染的第一个拍", 5_000, firstPeak(bOnly, 0.5f));
        assertEquals("A 经过映射后的第一个拍", 5_000, firstPeak(aOnly, 0.5f));
        assertEquals("第二个拍", 5_000 + PERIOD, firstPeak(aOnly, 29_000, 0.5f));
        assertEquals("第二个拍", 5_000 + PERIOD, firstPeak(bOnly, 29_000, 0.5f));
    }

    @Test
    public void totalFramesAreExactEvenWhenNotABlockMultiple() throws IOException {
        int out = 10_000;
        float[] vocals = tone(20_000, 0.1f);
        float[] instrumental = tone(20_000, 0.1f);
        CountingSink sink = new CountingSink();
        MashupRenderer.Report report = MashupRenderer.render(
                MashupRenderer.builder(RATE, 1)
                        .vocals(PcmTrack.mono(vocals), TimeMap.identity(out))
                        .instrumental(PcmTrack.mono(instrumental), 0)
                        .frames(out)
                        .blockFrames(4_096)
                        .gains(1f, 1f)
                        .build(),
                sink);
        assertEquals(out, report.frames);
        assertEquals(out, sink.frames);
        assertEquals(3, sink.blocks.size());
        assertEquals(4_096, sink.blocks.get(0).intValue());
        assertEquals(1_808, sink.blocks.get(2).intValue());
    }

    @Test
    public void limiterCapsTheInPhaseSumAndSaysItActed() throws IOException {
        float[] loud = constant(8_000, 1.0f);
        CapturingSink sink = new CapturingSink(8_000);
        MashupRenderer.Report report = MashupRenderer.render(
                MashupRenderer.builder(RATE, 2)
                        .vocals(PcmTrack.stereo(loud, loud), TimeMap.identity(8_000))
                        .instrumental(PcmTrack.stereo(loud, loud), 0)
                        .frames(8_000)
                        .gains(1f, 1f)
                        .fades(0.0, 0.0)
                        .build(),
                sink);
        assertTrue("同相相加应当是 2.0 左右，实际 " + report.peakBeforeLimiter,
                report.peakBeforeLimiter > 1.9f);
        assertTrue("限幅后必须低于天花板，实际 " + report.peakAfterLimiter,
                report.peakAfterLimiter <= ceiling() + 1e-4f);
        assertTrue("这一块确实需要限幅", report.limiterActed);
        assertEquals(8_000, report.frames);
    }

    @Test
    public void varyingSpeedIsRejectedInsteadOfSilentlyResampled() {
        float[] mono = tone(10_000, 0.1f);
        try {
            MashupRenderer.builder(RATE, 1)
                    .vocals(PcmTrack.mono(mono), TimeMap.of(new long[]{0, 10_000},
                            new long[]{0, 9_000}))
                    .frames(10_000)
                    .build();
            fail("变速映射在第一阶段应当被拒绝");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("恒速"));
        }
    }

    @Test
    public void sourceShortageBecomesSilenceNotAnException() throws IOException {
        // 0.3 + 0.3 = 0.6，天花板（−1 dBFS ≈ 0.891）以下，限幅器不介入
        float[] shortVocals = constant(1_000, 0.3f);
        float[] shortInstrumental = constant(1_000, 0.3f);
        CapturingSink sink = new CapturingSink(4_000);
        MashupRenderer.Report report = MashupRenderer.render(
                MashupRenderer.builder(RATE, 1)
                        .vocals(PcmTrack.mono(shortVocals), TimeMap.identity(4_000))
                        .instrumental(PcmTrack.mono(shortInstrumental), 0)
                        .frames(4_000)
                        .gains(1f, 1f)
                        .fades(0.0, 0.0)
                        .build(),
                sink);
        assertEquals(4_000, report.frames);
        assertEquals(0f, sink.left(3_999), 0.0);
        assertEquals(0.6f, sink.left(500), 1e-6);
    }

    @Test
    public void vocalsFadeInAndOutOnAHalfCosine() {
        long out = 10_000;
        long fadeIn = 1_000;
        long fadeOut = 2_000;
        assertEquals(0f, MashupRenderer.envelope(0, out, fadeIn, fadeOut), 0f);
        assertEquals(1f, MashupRenderer.envelope(fadeIn, out, fadeIn, fadeOut), 1e-6f);
        assertEquals(1f, MashupRenderer.envelope(5_000, out, fadeIn, fadeOut), 1e-6f);
        assertEquals(0f, MashupRenderer.envelope(out - 1, out, fadeIn, fadeOut), 1e-6f);
        // 半余弦在中点应当恰好是 0.5，线性也会是 0.5 —— 但斜率不同，这里钉住值的对称性
        assertEquals(0.5f, MashupRenderer.envelope(500, out, fadeIn, fadeOut), 1e-6f);
    }

    // ---- 素材与工具 -------------------------------------------------------

    /** 点击：位置精确（首个样本就是峰值），后面 8 个样本线性衰减。 */
    private static float[] clicks(int frames, int firstAt, int period, float amp) {
        float[] s = new float[frames];
        for (int t = firstAt; t < frames; t += period) {
            for (int k = 0; k < 8 && t + k < frames; k++) {
                s[t + k] = amp * (1f - k / 8f);
            }
        }
        return s;
    }

    private static float[] tone(int frames, float amp) {
        float[] s = new float[frames];
        for (int i = 0; i < frames; i++) {
            s[i] = (float) (amp * Math.sin(2 * Math.PI * 440 * i / RATE));
        }
        return s;
    }

    private static float[] constant(int frames, float v) {
        float[] s = new float[frames];
        java.util.Arrays.fill(s, v);
        return s;
    }

    private static float ceiling() {
        return (float) Math.pow(10.0, -1.0 / 20.0);
    }

    private static int firstPeak(float[] data, float threshold) {
        return firstPeak(data, 0, threshold);
    }

    private static int firstPeak(float[] data, int from, float threshold) {
        for (int i = Math.max(0, from); i < data.length; i++) {
            if (Math.abs(data[i]) >= threshold) {
                return i;
            }
        }
        return -1;
    }

    private static float[] renderLeft(long outFrames, float[] vocals, TimeMap map,
                                      float[] instrumental, long instrStart,
                                      float vocalGain, float instrGain) throws IOException {
        MashupRenderer.Builder b = MashupRenderer.builder(RATE, 1).frames(outFrames)
                .gains(vocalGain, instrGain).fades(0.0, 0.0);
        if (vocals != null) {
            b.vocals(PcmTrack.mono(vocals), map);
        }
        if (instrumental != null) {
            b.instrumental(PcmTrack.mono(instrumental), instrStart);
        }
        CapturingSink sink = new CapturingSink(outFrames);
        MashupRenderer.render(b.build(), sink);
        return sink.left;
    }

    /** 收集左声道（单声道素材时就是全部内容）。 */
    private static final class CapturingSink implements MashupRenderer.Sink {

        private final float[] left;

        CapturingSink(long frames) {
            this.left = new float[(int) frames];
        }

        @Override
        public void write(AudioBuf block) {
            int base = offset;
            for (int i = 0; i < block.frames(); i++) {
                left[base + i] = block.channel(0).get(i);
            }
            offset += block.frames();
        }

        private int offset;

        float left(int frame) {
            return left[frame];
        }
    }

    /** 只数块：用于"块数/块长"这一类判据。 */
    private static final class CountingSink implements MashupRenderer.Sink {

        private final List<Integer> blocks = new ArrayList<>();
        private long frames;

        @Override
        public void write(AudioBuf block) {
            blocks.add(block.frames());
            frames += block.frames();
        }
    }
}
