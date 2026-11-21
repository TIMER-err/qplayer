package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>「哪里需要去人声」不许漏判</b>——这是「开了 DSP 还是听到两首人声叠在一起」的根因判据。
 *
 * <p>用户报：开了 DSP 中心声道抑制，仍然听到两首歌的人声叠在一起。查下来，问题不在分离器，
 * 而在<b>「哪几秒要去人声」这个结论把真叠唱判成了没叠唱</b>：
 *
 * <ol>
 *   <li>{@code vocalOverlapRanges} 最后有一道「整段短于 {@code VOICE_RANGE_MIN_SECS}（0.4 s）
 *       就丢掉」的过滤。注释写的是「短于 0.4 秒的段<b>并进前一段</b>」，但实现里只有
 *       <b>换气缝</b>会被并段，长度不够的整段是<b>直接丢弃</b>——于是「两边唱了 0.2~0.3 秒」
 *       这种<b>听得见的撞车</b>会产生空数组，而空数组的语义是「明确没有叠唱 → 整段不去人声」。
 *       结论：去人声层什么都不做，用户听到两个人声。</li>
 *   <li>P6 加的<b>中置门</b>（「在不在中间」）也参与了同一个判断。它用在打分/硬约束上是对的，
 *       用在「该去哪里动手」上是错的：这里宁可多动一点，也不能漏。</li>
 * </ol>
 *
 * <p>判据钉三件事：短叠唱必须是非空区间、被摆开的人声也要算数、换气缝仍然要并段
 * （不许因为放宽而把素材切碎）。
 */
public class AutoCueVocalRangeTest {

    private static final double HOP = 0.02;
    private static final int HOPS = 200;      // 4 秒

    /**
     * 造一个包络：中频在「唱」的格子上按音节起伏，其余格安静；{@code centerValue} 给中置显著度。
     *
     * @param singing 长度为 HOPS 的布尔表：这一格在不在唱
     */
    private static AutoCue.Envelope envelope(boolean[] singing, Float centerValue) {
        float[] rms = new float[HOPS];
        float[][] band = new float[HOPS][];
        float[] center = centerValue == null ? null : new float[HOPS];
        Arrays.fill(rms, 0.5f);
        for (int i = 0; i < HOPS; i++) {
            float mid;
            if (singing[i]) {
                mid = (i / 5) % 2 == 0 ? 0.5f : 0.2f;      // 音节起伏，过 VOICE_MODULATION
            } else {
                mid = 0.02f;                               // 安静（在 VOICE_FLOOR 之下）
            }
            band[i] = new float[] {0.05f, mid, 0.05f};
            if (center != null) {
                center[i] = centerValue.floatValue();
            }
        }
        return new AutoCue.Envelope(rms, new float[HOPS], HOPS * HOP, null, 0, band, null, 0,
                -1f, -1f, center);
    }

    private static boolean[] nobody() {
        return new boolean[HOPS];
    }

    private static boolean[] singing(int fromHop, int toHop) {
        boolean[] on = new boolean[HOPS];
        for (int i = fromHop; i < toHop; i++) {
            on[i] = true;
        }
        return on;
    }

    /** ① <b>0.2 秒的真叠唱必须留下区间</b>（原来被 0.4 s 的过滤丢掉 → 结论是「没叠唱」）。 */
    @Test
    public void aTwoTenthsOverlapIsNotErased() {
        // 两边都只在第 1.00~1.20 秒唱（10 格 = 0.2 秒）
        int from = (int) Math.round(1.00 / HOP);
        int to = from + 10;
        AutoCue.Envelope a = envelope(singing(from, to), null);
        AutoCue.Envelope b = envelope(singing(from, to), null);
        double[] ranges = AutoCue.vocalOverlapRanges(a, b, 0, 0, HOPS);
        assertNotNull("两侧都有频段数据，不该返回 null", ranges);
        System.out.println("[duck] 0.2 秒叠唱 → 区间 " + Arrays.toString(ranges));
        assertTrue("0.2 秒的叠唱是听得见的撞车，必须留下区间（空了就等于整段不去人声）",
                ranges.length >= 2);
        assertEquals("起点应当 ≈1.00 s", 1.00, ranges[0], 0.05);
        assertTrue("终点应当 ≈1.20 s", Math.abs(ranges[1] - 1.20) <= 0.06);
    }

    /** ② 被摆开的人声（中置显著度 0.5）也要算数——中置门只该用在打分上。 */
    @Test
    public void aPannedVoiceStillCountsForDucking() {
        int from = (int) Math.round(1.0 / HOP);
        AutoCue.Envelope a = envelope(singing(from, from + 25), Float.valueOf(0.50f));
        AutoCue.Envelope b = envelope(singing(from, from + 25), Float.valueOf(0.50f));
        double[] ranges = AutoCue.vocalOverlapRanges(a, b, 0, 0, HOPS);
        assertNotNull(ranges);
        System.out.println("[duck] 被摆开的人声（中置 0.50）→ 区间 " + Arrays.toString(ranges));
        assertTrue("被摆开的也可能是人声（宽混响/加倍录），这里不许漏（区间空了就是整段不动手）",
                ranges.length >= 2);
    }

    /** ③ 换气缝仍然要并段：不许因为放宽而把素材切成碎条。 */
    @Test
    public void breathGapsStillMergeIntoOneRange() {
        // 两边各唱两段：1.0~1.3 与 1.5~1.8（中间 0.2 秒的缝，小于 0.4 秒 → 应当并成一段）
        boolean[] on = nobody();
        for (int i = 50; i < 65; i++) {
            on[i] = true;
        }
        for (int i = 75; i < 90; i++) {
            on[i] = true;
        }
        double[] ranges = AutoCue.vocalOverlapRanges(envelope(on, null), envelope(on, null),
                0, 0, HOPS);
        assertNotNull(ranges);
        System.out.println("[duck] 两段之间 0.2 秒换气缝 → 区间 " + Arrays.toString(ranges));
        assertEquals("换气缝（0.2 s < 0.4 s）应当并成一段，而不是切成两条", 2, ranges.length);
        assertTrue("合并后的终点应当 ≈1.80 s", Math.abs(ranges[1] - 1.80) <= 0.06);
    }
}
