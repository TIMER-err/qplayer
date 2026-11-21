package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * <b>ReferenceParityTest（区域索引算术 + 响度窗）</b>：{@link ReferenceRegions} 必须与参考包
 * {@code candidate_paths} / {@code recovered_logic} 逐位一致。
 *
 * <p>参考包用 beatEvents 索引表达区间（整数算术），这里逐个把结果对上：
 * scale / shift / loudness windows / vocal overlap / loudness significant。
 */
public class ReferenceRegionsParityTest {

    @Test
    public void 拍数换算与参考包一致() {
        assertEquals("nib 0@0.5", 0, ReferenceRegions.normalizedIncomingBeats(0, 0.5));
        assertEquals("nib 0@1.0", 0, ReferenceRegions.normalizedIncomingBeats(0, 1.0));
        assertEquals("nib 0@2.0", 0, ReferenceRegions.normalizedIncomingBeats(0, 2.0));
        assertEquals("nib 5@0.5", 2, ReferenceRegions.normalizedIncomingBeats(5, 0.5));
        assertEquals("nib 5@1.0", 5, ReferenceRegions.normalizedIncomingBeats(5, 1.0));
        assertEquals("nib 5@2.0", 10, ReferenceRegions.normalizedIncomingBeats(5, 2.0));
        assertEquals("nib 16@0.5", 8, ReferenceRegions.normalizedIncomingBeats(16, 0.5));
        assertEquals("nib 16@1.0", 16, ReferenceRegions.normalizedIncomingBeats(16, 1.0));
        assertEquals("nib 16@2.0", 32, ReferenceRegions.normalizedIncomingBeats(16, 2.0));
        assertEquals("nib 33@0.5", 16, ReferenceRegions.normalizedIncomingBeats(33, 0.5));
        assertEquals("nib 33@1.0", 33, ReferenceRegions.normalizedIncomingBeats(33, 1.0));
        assertEquals("nib 33@2.0", 66, ReferenceRegions.normalizedIncomingBeats(33, 2.0));
    }

    @Test
    public void 进场区缩放与参考包一致() {
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(0, 32), 8, 0.5, null), 16, 32);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(0, 32), 8, 1.0, null), 24, 32);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(0, 32), 8, 2.0, null), 28, 32);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(0, 32), 16, 0.5, null), 0, 32);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(0, 32), 16, 1.0, null), 16, 32);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(0, 32), 16, 2.0, null), 24, 32);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(0, 32), 32, 0.5, null), 0, 32);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(0, 32), 32, 1.0, null), 0, 32);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(0, 32), 32, 2.0, null), 16, 32);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(4, 40), 8, 0.5, null), 24, 40);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(4, 40), 8, 1.0, null), 32, 40);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(4, 40), 8, 2.0, null), 36, 40);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(4, 40), 16, 0.5, null), 8, 40);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(4, 40), 16, 1.0, null), 24, 40);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(4, 40), 16, 2.0, null), 32, 40);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(4, 40), 32, 0.5, null), 0, 40);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(4, 40), 32, 1.0, null), 8, 40);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(4, 40), 32, 2.0, null), 24, 40);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(10, 51), 8, 0.5, null), 35, 51);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(10, 51), 8, 1.0, null), 43, 51);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(10, 51), 8, 2.0, null), 47, 51);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(10, 51), 16, 0.5, null), 19, 51);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(10, 51), 16, 1.0, null), 35, 51);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(10, 51), 16, 2.0, null), 43, 51);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(10, 51), 32, 0.5, null), 3, 51);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(10, 51), 32, 1.0, null), 19, 51);
        checkRegion(ReferenceRegions.scaleIncomingRegion(new ReferenceRegions.BeatRegion(10, 51), 32, 2.0, null), 35, 51);
    }

    @Test
    public void 区间平移与参考包一致() {
        ReferenceRegions.Pair p_2306 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                2, 2, 0.5, null, null);
        checkRegion(p_2306.outgoing, 16, 48);
        checkRegion(p_2306.incoming, 16, 48);
        ReferenceRegions.Pair p_1560 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                2, 2, 1.0, null, null);
        checkRegion(p_1560.outgoing, 16, 48);
        checkRegion(p_1560.incoming, 8, 40);
        ReferenceRegions.Pair p_4140 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                2, 2, 2.0, null, null);
        checkRegion(p_4140.outgoing, 16, 48);
        checkRegion(p_4140.incoming, 4, 36);
        ReferenceRegions.Pair p_4005 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 4, 0.5, null, null);
        checkRegion(p_4005.outgoing, 8, 40);
        checkRegion(p_4005.incoming, 32, 64);
        ReferenceRegions.Pair p_3328 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 4, 1.0, null, null);
        checkRegion(p_3328.outgoing, 8, 40);
        checkRegion(p_3328.incoming, 16, 48);
        ReferenceRegions.Pair p_8613 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 4, 2.0, null, null);
        checkRegion(p_8613.outgoing, 8, 40);
        checkRegion(p_8613.incoming, 8, 40);
        ReferenceRegions.Pair p_6835 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                1, 1, 0.5, null, null);
        checkRegion(p_6835.outgoing, 12, 44);
        checkRegion(p_6835.incoming, 8, 40);
        ReferenceRegions.Pair p_6158 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                1, 1, 1.0, null, null);
        checkRegion(p_6158.outgoing, 12, 44);
        checkRegion(p_6158.incoming, 4, 36);
        ReferenceRegions.Pair p_1375 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                1, 1, 2.0, null, null);
        checkRegion(p_1375.outgoing, 12, 44);
        checkRegion(p_1375.incoming, 2, 34);
        ReferenceRegions.Pair p_9645 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 2, 0.5, null, null);
        checkRegion(p_9645.outgoing, 8, 40);
        checkRegion(p_9645.incoming, 16, 48);
        ReferenceRegions.Pair p_8968 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 2, 1.0, null, null);
        checkRegion(p_8968.outgoing, 8, 40);
        checkRegion(p_8968.incoming, 8, 40);
        ReferenceRegions.Pair p_1549 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 2, 2.0, null, null);
        checkRegion(p_1549.outgoing, 8, 40);
        checkRegion(p_1549.incoming, 4, 36);
    }

    @Test
    public void 响度窗与参考包一致() {
        ReferenceRegions.Windows w0 = ReferenceRegions.loudnessWindows(100.0, 108.0, "dance-shifted", 17);
        assertEquals("dance-shifted out[0]", 100.0, w0.aStart, 1e-12);
        assertEquals("dance-shifted out[0]end", 107.11111111111111, w0.aEnd, 1e-12);
        assertEquals("dance-shifted out[1]end", 114.22222222222223, w0.bEnd, 1e-12);
        ReferenceRegions.Windows iw0 = ReferenceRegions.incomingLoudnessWindows(10.0, 18.0, "dance-shifted");
        assertEquals("dance-shifted in[0]end", 17.11111111111111, iw0.aEnd, 1e-12);
        assertEquals("dance-shifted in[1]end", 24.22222222222222, iw0.bEnd, 1e-12);
        ReferenceRegions.Windows w1 = ReferenceRegions.loudnessWindows(100.0, 108.0, "hiphop-shifted", 15);
        assertEquals("hiphop-shifted out[0]", 98.0, w1.aStart, 1e-12);
        assertEquals("hiphop-shifted out[0]end", 106.0, w1.aEnd, 1e-12);
        assertEquals("hiphop-shifted out[1]end", 114.0, w1.bEnd, 1e-12);
        ReferenceRegions.Windows iw1 = ReferenceRegions.incomingLoudnessWindows(10.0, 18.0, "hiphop-shifted");
        assertEquals("hiphop-shifted in[0]end", 18.0, iw1.aEnd, 1e-12);
        assertEquals("hiphop-shifted in[1]end", 26.0, iw1.bEnd, 1e-12);
        ReferenceRegions.Windows w2 = ReferenceRegions.loudnessWindows(100.0, 108.0, "pop-scaled-shifted", 16);
        assertEquals("pop-scaled-shifted out[0]", 96.0, w2.aStart, 1e-12);
        assertEquals("pop-scaled-shifted out[0]end", 104.0, w2.aEnd, 1e-12);
        assertEquals("pop-scaled-shifted out[1]end", 112.0, w2.bEnd, 1e-12);
        ReferenceRegions.Windows iw2 = ReferenceRegions.incomingLoudnessWindows(10.0, 18.0, "pop-scaled-shifted");
        assertEquals("pop-scaled-shifted in[0]end", 18.0, iw2.aEnd, 1e-12);
        assertEquals("pop-scaled-shifted in[1]end", 26.0, iw2.bEnd, 1e-12);
        ReferenceRegions.Windows w3 = ReferenceRegions.loudnessWindows(100.0, 108.0, "filtered-direct", 9);
        assertEquals("filtered-direct out[0]", 100.0, w3.aStart, 1e-12);
        assertEquals("filtered-direct out[0]end", 108.0, w3.aEnd, 1e-12);
        assertEquals("filtered-direct out[1]end", 116.0, w3.bEnd, 1e-12);
        ReferenceRegions.Windows iw3 = ReferenceRegions.incomingLoudnessWindows(10.0, 18.0, "filtered-direct");
        assertEquals("filtered-direct in[0]end", 18.0, iw3.aEnd, 1e-12);
        assertEquals("filtered-direct in[1]end", 26.0, iw3.bEnd, 1e-12);
        ReferenceRegions.Windows w4 = ReferenceRegions.loudnessWindows(100.0, 108.0, "smart-crossfade", 3);
        assertEquals("smart-crossfade out[0]", 100.0, w4.aStart, 1e-12);
        assertEquals("smart-crossfade out[0]end", 108.0, w4.aEnd, 1e-12);
        assertEquals("smart-crossfade out[1]end", 116.0, w4.bEnd, 1e-12);
        ReferenceRegions.Windows iw4 = ReferenceRegions.incomingLoudnessWindows(10.0, 18.0, "smart-crossfade");
        assertEquals("smart-crossfade in[0]end", 18.0, iw4.aEnd, 1e-12);
        assertEquals("smart-crossfade in[1]end", 26.0, iw4.bEnd, 1e-12);
    }

    @Test
    public void 叠唱冲突与响度显著性跟参考包一致() {
        assertEquals(true, ReferenceRegions.alignedVocalOverlapIncompatible(new Integer[] {1, 2, 0}, new Integer[] {1, 0, 0}));
        assertEquals(false, ReferenceRegions.alignedVocalOverlapIncompatible(new Integer[] {0, 0}, new Integer[] {0, 0}));
        assertEquals(true, ReferenceRegions.alignedVocalOverlapIncompatible(new Integer[] {3, null}, new Integer[] {4, 2}));
        assertEquals(true, ReferenceRegions.alignedVocalOverlapIncompatible(null, new Integer[] {1}));
        assertEquals(true, ReferenceRegions.outgoingLoudnessSignificant(Double.valueOf(-10.0), true));
        assertEquals(false, ReferenceRegions.outgoingLoudnessSignificant(Double.valueOf(-20.0), true));
        assertEquals(false, ReferenceRegions.outgoingLoudnessSignificant(null, true));
        assertEquals(true, ReferenceRegions.outgoingLoudnessSignificant(Double.valueOf(-30.0), false));
    }

    private static void checkRegion(ReferenceRegions.BeatRegion r, int start, int end) {
        assertEquals("start", start, r.start);
        assertEquals("end", end, r.end);
    }
}
