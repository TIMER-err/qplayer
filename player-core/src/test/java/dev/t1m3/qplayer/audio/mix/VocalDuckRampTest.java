package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>去人声区间的边缘必须有坡</b>（2026-10-02 修用户报的「切换时叫一声」）。
 *
 * <p>第一版是硬开关：区间内替换素材、区间外原样——两者差着「人声那一路 ×（1−包络）」，
 * 于是边界上是一个**波形台阶**，听感就是「叫一声」。这一版按 30 ms 走上下游坡
 * （与噼哒修正的 30 ms 斜坡同一个量级）消掉台阶。
 */
public class VocalDuckRampTest {

    /** 没有这个信息（{@code null}）→ 恒为 1：整窗去人声，与加这个字段之前逐位相同。 */
    @Test
    public void withoutRangesEverythingIsDucked() {
        assertEquals("null = 照旧整窗去人声", 1.0,
                VocalSeparationTransition.duckWeight(null, 3.0), 1e-12);
    }

    /** 空数组 = 明确「没有叠唱」→ 恒为 0：整段不去人声。 */
    @Test
    public void withNoRangesNothingIsDucked() {
        assertEquals("空区间 = 整段不去人声", 0.0,
                VocalSeparationTransition.duckWeight(new double[0], 3.0), 1e-12);
    }

    /** 平台内 1、坡上居中、坡外 0。 */
    @Test
    public void theEdgesWalkUpAndDownAroundTheRange() {
        double[] ranges = {2.0, 4.0};
        double ramp = VocalSeparationTransition.DUCK_EDGE_RAMP_SECS;
        System.out.println(String.format(java.util.Locale.ROOT,
                "[duck] 坡长 %.0f ms · 2.00/4.00 的区间：坡外 %.2f · 上坡中 %.2f · 平台 %.2f · 下坡中 %.2f",
                Double.valueOf(ramp * 1000.0),
                Double.valueOf(VocalSeparationTransition.duckWeight(ranges, 2.0 - ramp * 3)),
                Double.valueOf(VocalSeparationTransition.duckWeight(ranges, 2.0 - ramp / 2)),
                Double.valueOf(VocalSeparationTransition.duckWeight(ranges, 3.0)),
                Double.valueOf(VocalSeparationTransition.duckWeight(ranges, 4.0 + ramp / 2))));

        assertEquals("坡外应当完全是 0", 0.0,
                VocalSeparationTransition.duckWeight(ranges, 2.0 - ramp * 3), 1e-12);
        assertEquals("平台内应当完全是 1", 1.0,
                VocalSeparationTransition.duckWeight(ranges, 3.0), 1e-12);
        assertTrue("上坡正中应当落在中间（0,1）",
                VocalSeparationTransition.duckWeight(ranges, 2.0 - ramp / 2) > 0.3
                        && VocalSeparationTransition.duckWeight(ranges, 2.0 - ramp / 2) < 0.7);
        assertTrue("下坡正中应当落在中间（0,1）",
                VocalSeparationTransition.duckWeight(ranges, 4.0 + ramp / 2) > 0.3
                        && VocalSeparationTransition.duckWeight(ranges, 4.0 + ramp / 2) < 0.7);
        assertEquals("区间两端点本身是平台边界", 1.0,
                VocalSeparationTransition.duckWeight(ranges, 2.0), 1e-12);
        assertEquals("区间另一端同样是 1", 1.0,
                VocalSeparationTransition.duckWeight(ranges, 4.0), 1e-12);
    }

    /** 权重沿时间**单调**上升再下降，而且**每一步都很小**（这正是「没有台阶」的定义）。 */
    @Test
    public void theWeightNeverJumps() {
        double[] ranges = {2.0, 4.0};
        int rate = 48_000;
        double prev = 0.0;
        double maxStep = 0.0;
        boolean rising = true;
        for (int i = 0; i <= 6 * rate; i++) {
            double w = VocalSeparationTransition.duckWeight(ranges, (double) i / rate);
            maxStep = Math.max(maxStep, Math.abs(w - prev));
            if (rising && w < prev - 1e-12) {
                rising = false;
            } else if (!rising && w > prev + 1e-12) {
                throw new AssertionError("下坡段又回升了：第 " + i + " 帧 " + prev + " → " + w);
            }
            prev = w;
        }
        // 硬开关的版本在边界上一步就是 0 → 1（台阶 = 那一声）；有了坡，单帧变化应当是
        // 坡长的倒数（30 ms @48k ≈ 1/1440 ≈ 0.0007）。
        double limit = 2.0 / (VocalSeparationTransition.DUCK_EDGE_RAMP_SECS * rate);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[duck] 单帧最大变化 %.5f（上限 %.5f）", Double.valueOf(maxStep), Double.valueOf(limit)));
        assertTrue("单帧权重不该跳变（实测 " + maxStep + "，上限 " + limit + "）", maxStep <= limit);
    }

    /** 多段时取最大权重（相邻两段叠在一起也不会互相抵消）。 */
    @Test
    public void overlappingRangesTakeTheLargestWeight() {
        double[] ranges = {0.0, 1.0, 1.0, 2.0};
        assertEquals("两段相接处仍然是平台", 1.0,
                VocalSeparationTransition.duckWeight(ranges, 1.0), 1e-12);
        assertTrue("两段之间的坡不该掉到 0 以下",
                VocalSeparationTransition.duckWeight(ranges, 1.0 + VocalSeparationTransition.DUCK_EDGE_RAMP_SECS / 2) >= 0.0);
    }
}
