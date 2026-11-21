package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * **接缝错位 → 样本跳变** 的量化关系（2026-10-09 ✓）。
 *
 * <p>动机：用户说交接那一刻"像 seek 了一下"✓。判据 {@code TempoTailOffsetTest} 量到变速尾部
 * 与文档位置相差 **−548 帧（11.42 ms）** ✓ —— 本判据把那个数换算成听感量 ✓：
 * 220 Hz 正弦，后半段整体平移 N 帧后拼接，量接缝处的相邻样本跳变 ✓。
 *
 * <p>实测（python 复算一致 ✓）：
 * <pre>
 *   错位    0 帧（0.00 ms）⇒ 跳变 0.0128  ✓ 连续（只剩正弦自身斜率）
 *   错位  548 帧（11.42 ms）⇒ 跳变 0.5185  ✗ **40 倍**
 *   错位 1096 帧（22.83 ms）⇒ 跳变 0.0731  （注意：**不单调** ✓ 所以不能靠跳变反推错位）
 * </pre>
 * ⇒ 结论：11.42 ms 的错位**足以**产生那一下 ✓；而"消掉它"只能靠**内容对齐** ✓，
 *    淡化只会把台阶摊成斜坡（历史数据：5 ms 只降 1.8 倍 ✓ 10/20 ms 更差 ✓）。
 */
public class SeamMisalignmentTest {
    private static final int RATE = 48000;
    private static final int SEAM = 1000;

    private static float at(int i, int offsetFrames) {
        return (float) (0.5 * Math.sin(2.0 * Math.PI * 220.0 * (i + offsetFrames) / RATE));
    }

    private static double jumpAtSeam(int offsetFrames) {
        float[] x = new float[SEAM + 64];
        for (int i = 0; i < SEAM; i++) {
            x[i] = at(i, 0);
        }
        for (int i = SEAM; i < SEAM + 64; i++) {
            x[i] = at(i, offsetFrames);
        }
        double m = 0;
        for (int i = SEAM - 1; i < SEAM + 3; i++) {
            m = Math.max(m, Math.abs(x[i] - x[i - 1]));
        }
        return m;
    }

    @Test
    public void misalignmentProducesAnAudibleStepWhileAlignmentDoesNot() {
        double aligned = jumpAtSeam(0);
        double measured = jumpAtSeam(548);
        assertTrue("对齐必须连续（实际 " + aligned + "）", aligned < 0.05);
        assertTrue("错 548 帧（11.42 ms）必须显著（实际 " + measured + "）", measured > 0.3);
        assertTrue("至少差 10 倍（实际 " + (measured / Math.max(1e-9, aligned)) + "）",
                measured > aligned * 10.0);
    }
}
