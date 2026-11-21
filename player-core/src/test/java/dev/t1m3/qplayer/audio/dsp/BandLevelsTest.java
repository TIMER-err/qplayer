package dev.t1m3.qplayer.audio.dsp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link BandLevels} 的测试：三个频段各认各的频率，静音给 0，块与块之间会平滑。
 *
 * <p>这些断言不追求「和某个上游一致」（上游没有这个东西），只钉住<strong>方向性</strong>：
 * 低音进低频段、高音进高频段、静音不给电平。方向错了，歌词背景就会「跟着镲片抖低频」，
 * 而那正是肉眼一眼能看出来的错。
 */
public class BandLevelsTest {

    private static final int RATE = 48_000;

    @Test
    public void sineWavesLandInTheirOwnBand() {
        float[] low = bandLevelsFor(100f);
        float[] mid = bandLevelsFor(1_000f);
        float[] high = bandLevelsFor(8_000f);

        assertTrue("100 Hz 应以低频段为主：低=" + low[0] + " 中=" + low[1] + " 高=" + low[2],
                low[0] > low[1] && low[0] > low[2]);
        assertTrue("1 kHz 应以中频段为主：低=" + mid[0] + " 中=" + mid[1] + " 高=" + mid[2],
                mid[1] > mid[0] && mid[1] > mid[2]);
        assertTrue("8 kHz 应以高频段为主：低=" + high[0] + " 中=" + high[1] + " 高=" + high[2],
                high[2] > high[0] && high[2] > high[1]);
    }

    @Test
    public void levelsStayInUnitRange() {
        for (float freq : new float[]{30f, 100f, 440f, 1_000f, 4_000f, 12_000f}) {
            for (float amplitude : new float[]{0.01f, 0.2f, 1.0f}) {
                float[] levels = bandLevelsFor(freq, amplitude);
                for (int i = 0; i < BandLevels.BANDS; i++) {
                    assertTrue("电平必须在 0..1：" + freq + " Hz amp=" + amplitude + " → " + levels[i],
                            levels[i] >= 0f && levels[i] <= 1f);
                }
            }
        }
    }

    @Test
    public void silenceProducesZeroLevels() {
        BandLevels levels = new BandLevels(RATE);
        float[] out = new float[BandLevels.BANDS];
        float[] block = new float[1024];
        for (int i = 0; i < 10; i++) {
            levels.process(block, block.length, 2, out, 0);
        }
        assertEquals(0f, out[0], 1e-6f);
        assertEquals(0f, out[1], 1e-6f);
        assertEquals(0f, out[2], 1e-6f);
    }

    @Test
    public void attackIsFasterThanRelease() {
        BandLevels levels = new BandLevels(RATE);
        float[] out = new float[BandLevels.BANDS];
        float[] loud = sine(440f, 1f, 1024 * 8);
        levels.process(loud, 1024 * 8, 1, out, 0);
        float afterFirstBlock = out[1];

        // 静音之后：释放是慢的。
        // 注意一块静音里还留着滤波器余振（低通/中通的尾部），所以判据要看**几块之后**的趋势：
        // 第一块不该掉到一半以下，连续几块必须一路往下走。
        float[] silence = new float[1024];
        levels.process(silence, silence.length, 1, out, 0);
        float afterOneSilentBlock = out[1];
        assertTrue("释放应该比攻击慢：一块之后还剩 " + afterOneSilentBlock,
                afterOneSilentBlock > afterFirstBlock * 0.5f);
        float previous = afterOneSilentBlock;
        for (int i = 0; i < 8; i++) {
            levels.process(silence, silence.length, 1, out, 0);
            assertTrue("释放过程必须单调下降：" + previous + " → " + out[1], out[1] <= previous + 1e-6f);
            previous = out[1];
        }
        assertTrue("连续静音之后应该明显降下来：开始 " + afterFirstBlock + "，现在 " + previous,
                previous < afterFirstBlock * 0.5f);
    }

    @Test
    public void resetClearsStateAndLevels() {
        BandLevels levels = new BandLevels(RATE);
        float[] out = new float[BandLevels.BANDS];
        float[] loud = sine(1_000f, 1f, 4096);
        levels.process(loud, loud.length, 1, out, 0);
        assertTrue(out[1] > 0f);
        levels.reset();
        float[] silence = new float[512];
        levels.process(silence, silence.length, 1, out, 0);
        assertEquals(0f, out[0], 1e-6f);
        assertEquals(0f, out[1], 1e-6f);
        assertEquals(0f, out[2], 1e-6f);
    }

    @Test
    public void stereoIsAveragedNotSummed() {
        // 左右反相（真实的相位相反信号）：平均之后是静音，不该报出电平
        float[] inverse = new float[2048];
        for (int i = 0; i < inverse.length; i += 2) {
            inverse[i] = 0.8f;
            inverse[i + 1] = -0.8f;
        }
        BandLevels levels = new BandLevels(RATE);
        float[] out = new float[BandLevels.BANDS];
        levels.process(inverse, inverse.length, 2, out, 0);
        for (int i = 0; i < BandLevels.BANDS; i++) {
            assertTrue("反相立体声平均后应为 0，实际 " + out[i], out[i] < 0.02f);
        }
    }

    @Test
    public void writesAtTheRequestedOffset() {
        BandLevels levels = new BandLevels(RATE);
        float[] out = new float[BandLevels.BANDS + 3];
        float[] tone = sine(1_000f, 0.5f, 4096);
        levels.process(tone, tone.length, 1, out, 3);
        assertEquals(0f, out[0], 0f);
        assertEquals(0f, out[1], 0f);
        assertEquals(0f, out[2], 0f);
        assertTrue(out[4] > 0f);
    }

    private static float[] bandLevelsFor(float frequencyHz) {
        return bandLevelsFor(frequencyHz, 0.5f);
    }

    private static float[] bandLevelsFor(float frequencyHz, float amplitude) {
        BandLevels levels = new BandLevels(RATE);
        float[] out = new float[BandLevels.BANDS];
        float[] tone = sine(frequencyHz, amplitude, 4096);
        // 多喂几块，让平滑值收敛（攻击系数 0.55，两三块就到顶了）
        for (int i = 0; i < 4; i++) {
            levels.process(tone, tone.length, 1, out, 0);
        }
        return out;
    }

    private static float[] sine(float frequencyHz, float amplitude, int length) {
        float[] out = new float[length];
        for (int i = 0; i < length; i++) {
            out[i] = amplitude * (float) Math.sin(2.0 * Math.PI * frequencyHz * i / RATE);
        }
        return out;
    }
}
