package dev.t1m3.qplayer.audio.model;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

/**
 * 峰值拾取的对齐测试：对应 Sonalis {@code sonalis-model/src/beat.rs} 里
 * {@code mod tests} 关于 {@code pick_peaks} 的那六条。
 *
 * <p>上游把它们单独拆出来的理由是「模型上万个参数，但峰值拾取只有十几行，
 * 它出错时更应该一眼看出来」——这几条<b>一个 ONNX 都不需要</b>。
 */
public class PeaksTest {

    /** 上游 {@code picks_an_obvious_peak}。 */
    @Test
    public void picksAnObviousPeak() {
        float[] act = {0.0f, 0.1f, 0.9f, 0.1f, 0.0f, 0.0f};
        assertArrayEquals(new int[]{2}, Peaks.pickPeaks(act, PeakParams.defaults()));
    }

    /** 上游 {@code rejects_weak_peaks}：第二个峰只有最大值的 20%，被阈值挡掉。 */
    @Test
    public void rejectsWeakPeaks() {
        float[] act = {0.0f, 1.0f, 0.0f, 0.0f, 0.2f, 0.0f};
        assertArrayEquals(new int[]{1}, Peaks.pickPeaks(act, PeakParams.defaults()));
    }

    /** 上游 {@code keeps_peaks_that_are_far_enough_apart}：相隔 6 帧、min_gap 为 3 → 两个都留。 */
    @Test
    public void keepsPeaksThatAreFarEnoughApart() {
        float[] act = {0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.9f, 0.0f};
        PeakParams p = PeakParams.defaults();
        p.thresholdRatio = 0.5f;
        assertArrayEquals(new int[]{1, 6}, Peaks.pickPeaks(act, p));
    }

    /** 上游 {@code nearby_peaks_keep_the_higher_one}：挨得太近时留更高的那个，而不是留更早的那个。 */
    @Test
    public void nearbyPeaksKeepTheHigherOne() {
        float[] act = {0.0f, 0.8f, 1.0f, 0.0f, 0.0f};
        PeakParams p = new PeakParams(0.5f, 3, 1, 1, 0.0f);
        int[] got = Peaks.pickPeaks(act, p);
        assertArrayEquals("应当留更高的第 2 帧，实际 " + Arrays.toString(got),
                new int[]{2}, got);
    }

    /** 上游 {@code empty_and_flat_input_yield_nothing}。 */
    @Test
    public void emptyAndFlatInputYieldNothing() {
        assertTrue(Peaks.pickPeaks(new float[0], PeakParams.defaults()).length == 0);
        assertTrue(Peaks.pickPeaks(new float[32], PeakParams.defaults()).length == 0);
        // 全负：没有任何峰
        float[] neg = new float[32];
        Arrays.fill(neg, -1.0f);
        assertTrue(Peaks.pickPeaks(neg, PeakParams.defaults()).length == 0);
    }

    /**
     * 上游 {@code offset_is_subtracted_before_thresholding}：
     * {@code offset} 要真的从激活值里减掉——模型常有非零基线。
     */
    @Test
    public void offsetIsSubtractedBeforeThresholding() {
        // 基线 0.5，真实峰在 0.6 —— 减掉后峰值只剩 0.1，阈值 0.05，仍能挑出
        float[] act = {0.5f, 0.5f, 0.6f, 0.5f, 0.5f};
        PeakParams p = new PeakParams(0.5f, 1, 1, 1, 0.5f);
        assertArrayEquals(new int[]{2}, Peaks.pickPeaks(act, p));

        // 不减的话最高值就是 0.6、阈值 0.3，三个 0.5 都过阈，
        // 中间的 0.6 未必是唯一局部极大 —— 所以两种情况要分开验
        PeakParams p0 = new PeakParams(0.5f, 1, 1, 1, 0.0f);
        assertTrue("不减 offset 时结果不该与减了之后相同",
                !Arrays.equals(Peaks.pickPeaks(act, p0), new int[]{2}));
    }

    /**
     * 局部极大的判据是「窗口内都不能<b>更大</b>」，也就是允许相等。
     *
     * <p>上游直接写的是 {@code all(|&v| v <= vals[i])}。这一条盯住它：
     * 写成严格大于会把一段平台整段丢掉，而那时候拍点会<b>整个消失</b>——
     * 不报错，只是没有拍点，听感上「这次没对上拍」。
     */
    @Test
    public void aPlateauIsStillALocalMaximum() {
        float[] act = {0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f};
        PeakParams p = PeakParams.defaults();
        int[] got = Peaks.pickPeaks(act, p);
        assertTrue("平台应当至少挑出一个峰，实际 " + Arrays.toString(got), got.length >= 1);
        // min_gap 为 3 时三个并列的极值会被收敛成一个
        assertArrayEquals(new int[]{2}, got);
    }
}
