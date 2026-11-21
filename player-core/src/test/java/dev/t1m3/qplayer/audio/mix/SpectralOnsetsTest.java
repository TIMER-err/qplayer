package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 白化对数频率谱通量前端：<b>流式与整段必须逐点一致</b>。
 *
 * <p>为什么这条判据非有不可：整曲扫描（{@link AutoCue#scan}）只能用流式版本
 * （常数内存），而准备阶段与判据用的是整段版本。两者一旦有差异，「扫描出来的拍栅格」
 * 与「准备阶段算出来的拍栅格」就是两套数——这类「两张皮」在本仓库已经出过好几次
 * （见 {@code AiMixHarmonicCueTest} 的注释）。所以这里逐点比，容差按浮点舍入给。
 */
public class SpectralOnsetsTest {

    private static final int RATE = 44_100;

    @Test
    public void theStreamingVersionMatchesTheBatchOne() {
        float[] pcm = clicks(128.0, 20.0);
        float[] batch = SpectralOnsets.envelope(pcm, RATE);
        assertNotNull("整段版应当给出包络", batch);

        SpectralOnsets.Stream stream = new SpectralOnsets.Stream(RATE);
        for (float sample : pcm) {
            stream.push(sample);
        }
        float[] streamed = stream.values();
        assertEquals("帧数必须一致", batch.length, streamed.length);
        float maxDiff = 0f;
        for (int i = 0; i < batch.length; i++) {
            maxDiff = Math.max(maxDiff, Math.abs(batch[i] - streamed[i]));
        }
        assertTrue("流式与整段必须逐点一致（最大差 " + maxDiff + "）", maxDiff < 1e-3f);
    }

    @Test
    public void aStationaryToneLeavesAlmostNoOnsets() {
        // 恒幅正弦：逐格 RMS 差分那条老路会在这里留下「相位拍频」的假周期，
        // 白化谱通量应当把它压到近乎为零（真歌的落点强度是它的两个数量级以上）。
        float[] tone = new float[RATE * 10];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (float) (0.3 * Math.sin(2.0 * Math.PI * 440.0 * i / RATE));
        }
        float[] onset = SpectralOnsets.envelope(tone, RATE);
        assertNotNull(onset);
        double peak = 0.0;
        for (float v : onset) {
            peak = Math.max(peak, v);
        }
        float[] music = clicks(128.0, 10.0);
        float[] musicOnset = SpectralOnsets.envelope(music, RATE);
        double musicPeak = 0.0;
        for (float v : musicOnset) {
            musicPeak = Math.max(musicPeak, v);
        }
        assertTrue("稳态音不该有明显起音（峰值 " + peak + "，音乐的 " + musicPeak + "）",
                peak < musicPeak / 5.0);
    }

    /** 一条 128 BPM 的点击轨：每拍一个宽带脉冲，底下垫一点底噪。 */
    private static float[] clicks(double bpm, double secs) {
        int n = (int) Math.round(secs * RATE);
        float[] v = new float[n];
        java.util.Random random = new java.util.Random(7);
        double beat = 60.0 / bpm;
        for (int i = 0; i < n; i++) {
            v[i] = (float) (0.02 * Math.sin(2.0 * Math.PI * 55.0 * i / RATE));
        }
        for (double at = 0.1; at < secs; at += beat) {
            int start = (int) Math.round(at * RATE);
            for (int i = 0; i < 700 && start + i < n; i++) {
                float decay = (float) Math.exp(-i / 120.0);
                v[start + i] += (random.nextFloat() * 2f - 1f) * decay * 0.7f;
            }
        }
        return v;
    }
}
