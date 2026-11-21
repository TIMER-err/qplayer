package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * <b>ReferenceParityTest（路径级端到端）</b>：把参考包"区域算术 → 评分"两步串起来对照：
 * {@code shift_bars_for → scale_incoming_region / shift_region_pair → evaluate_candidate}。
 *
 * <p>期望值是 Python 侧对同一组输入实跑出来的；覆盖 11 条路径的 15 个 (path, style) 组合。
 */
public class ReferencePathPipelineParityTest {

    private static ReferencePaths.Signals signals(double alias) {
        return new ReferencePaths.Signals()
                .strictTempo(Boolean.TRUE).looseTempo(Boolean.TRUE).tonalities(Boolean.TRUE)
                .matchingBars(Integer.valueOf(8)).outgoingBeats(Integer.valueOf(32))
                .incomingBeats(Integer.valueOf(32)).tempoAlias(Double.valueOf(alias))
                .leadingStrength(Integer.valueOf(4))
                .incomingLoudness(Double.valueOf(-12.0))
                .incomingTrailingLoudness(Double.valueOf(-9.0))
                .vocalOverlap(Boolean.FALSE)
                .loudnessRelation(Integer.valueOf(0))
                .trailingSpan(Double.valueOf(6.0))
                .outgoingLoudnessSignificant(Boolean.TRUE);
    }

    @Test
    public void 路径级流水线与参考包逐位一致() {
        // 0: dance-shifted/17 alias=1.0 bars=[2, 2]
        assertArrayEquals(new int[] {2, 2},
                ReferenceRegions.shiftBarsFor("dance-shifted", 17));
        ReferenceRegions.BeatRegion scaled0 = ReferenceRegions.scaleIncomingRegion(
                new ReferenceRegions.BeatRegion(0, 32), 32, 1.0, null);
        assertEquals("dance-shifted scaled.start", 0, scaled0.start);
        assertEquals("dance-shifted scaled.end", 32, scaled0.end);
        ReferenceRegions.Pair shifted0 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                2, 2, 1.0, null, null);
        assertEquals("dance-shifted shifted.out.start", 16, shifted0.outgoing.start);
        assertEquals("dance-shifted shifted.in.end", 40, shifted0.incoming.end);
        ReferencePaths.Result r0 = ReferencePaths.evaluate("dance-shifted", 17, 200.0, 40.0,
                signals(1.0), true);
        assertEquals("dance-shifted/17 status", "scored", r0.status);
        assertEquals("dance-shifted/17 score", 22.826666666666664, r0.score.doubleValue(), 1e-12);
        // 1: filtered-shifted/12 alias=1.0 bars=[0, 4]
        assertArrayEquals(new int[] {0, 4},
                ReferenceRegions.shiftBarsFor("filtered-shifted", 12));
        ReferenceRegions.BeatRegion scaled1 = ReferenceRegions.scaleIncomingRegion(
                new ReferenceRegions.BeatRegion(0, 32), 32, 1.0, null);
        assertEquals("filtered-shifted scaled.start", 0, scaled1.start);
        assertEquals("filtered-shifted scaled.end", 32, scaled1.end);
        ReferenceRegions.Pair shifted1 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 4, 1.0, null, null);
        assertEquals("filtered-shifted shifted.out.start", 8, shifted1.outgoing.start);
        assertEquals("filtered-shifted shifted.in.end", 48, shifted1.incoming.end);
        ReferencePaths.Result r1 = ReferencePaths.evaluate("filtered-shifted", 12, 200.0, 40.0,
                signals(1.0), true);
        assertEquals("filtered-shifted/12 status", "scored", r1.status);
        assertEquals("filtered-shifted/12 score", 0.0, r1.score.doubleValue(), 1e-12);
        // 2: hiphop-shifted/15 alias=1.0 bars=[1, 1]
        assertArrayEquals(new int[] {1, 1},
                ReferenceRegions.shiftBarsFor("hiphop-shifted", 15));
        ReferenceRegions.BeatRegion scaled2 = ReferenceRegions.scaleIncomingRegion(
                new ReferenceRegions.BeatRegion(0, 32), 32, 1.0, null);
        assertEquals("hiphop-shifted scaled.start", 0, scaled2.start);
        assertEquals("hiphop-shifted scaled.end", 32, scaled2.end);
        ReferenceRegions.Pair shifted2 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                1, 1, 1.0, null, null);
        assertEquals("hiphop-shifted shifted.out.start", 12, shifted2.outgoing.start);
        assertEquals("hiphop-shifted shifted.in.end", 36, shifted2.incoming.end);
        ReferencePaths.Result r2 = ReferencePaths.evaluate("hiphop-shifted", 15, 200.0, 40.0,
                signals(1.0), true);
        assertEquals("hiphop-shifted/15 status", "scored", r2.status);
        assertEquals("hiphop-shifted/15 score", 100.16, r2.score.doubleValue(), 1e-12);
        // 3: pop-scaled-shifted/16 alias=1.0 bars=[0, 2]
        assertArrayEquals(new int[] {0, 2},
                ReferenceRegions.shiftBarsFor("pop-scaled-shifted", 16));
        ReferenceRegions.BeatRegion scaled3 = ReferenceRegions.scaleIncomingRegion(
                new ReferenceRegions.BeatRegion(0, 32), 32, 1.0, null);
        assertEquals("pop-scaled-shifted scaled.start", 0, scaled3.start);
        assertEquals("pop-scaled-shifted scaled.end", 32, scaled3.end);
        ReferenceRegions.Pair shifted3 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 2, 1.0, null, null);
        assertEquals("pop-scaled-shifted shifted.out.start", 8, shifted3.outgoing.start);
        assertEquals("pop-scaled-shifted shifted.in.end", 40, shifted3.incoming.end);
        ReferencePaths.Result r3 = ReferencePaths.evaluate("pop-scaled-shifted", 16, 200.0, 40.0,
                signals(1.0), true);
        assertEquals("pop-scaled-shifted/16 status", "scored", r3.status);
        assertEquals("pop-scaled-shifted/16 score", 50.16, r3.score.doubleValue(), 1e-12);
        // 4: pop-scaled-shifted/20 alias=1.0 bars=[2, 1]
        assertArrayEquals(new int[] {2, 1},
                ReferenceRegions.shiftBarsFor("pop-scaled-shifted", 20));
        ReferenceRegions.BeatRegion scaled4 = ReferenceRegions.scaleIncomingRegion(
                new ReferenceRegions.BeatRegion(0, 32), 32, 1.0, null);
        assertEquals("pop-scaled-shifted scaled.start", 0, scaled4.start);
        assertEquals("pop-scaled-shifted scaled.end", 32, scaled4.end);
        ReferenceRegions.Pair shifted4 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                2, 1, 1.0, null, null);
        assertEquals("pop-scaled-shifted shifted.out.start", 16, shifted4.outgoing.start);
        assertEquals("pop-scaled-shifted shifted.in.end", 36, shifted4.incoming.end);
        ReferencePaths.Result r4 = ReferencePaths.evaluate("pop-scaled-shifted", 20, 200.0, 40.0,
                signals(1.0), true);
        assertEquals("pop-scaled-shifted/20 status", "scored", r4.status);
        assertEquals("pop-scaled-shifted/20 score", 50.16, r4.score.doubleValue(), 1e-12);
        // 5: dance-original/19 alias=1.0 bars=[0, 0]
        assertArrayEquals(new int[] {0, 0},
                ReferenceRegions.shiftBarsFor("dance-original", 19));
        ReferenceRegions.BeatRegion scaled5 = ReferenceRegions.scaleIncomingRegion(
                new ReferenceRegions.BeatRegion(0, 32), 32, 1.0, null);
        assertEquals("dance-original scaled.start", 0, scaled5.start);
        assertEquals("dance-original scaled.end", 32, scaled5.end);
        ReferenceRegions.Pair shifted5 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 0, 1.0, null, null);
        assertEquals("dance-original shifted.out.start", 8, shifted5.outgoing.start);
        assertEquals("dance-original shifted.in.end", 32, shifted5.incoming.end);
        ReferencePaths.Result r5 = ReferencePaths.evaluate("dance-original", 19, 200.0, 40.0,
                signals(1.0), true);
        assertEquals("dance-original/19 status", "scored", r5.status);
        assertEquals("dance-original/19 score", 12.91, r5.score.doubleValue(), 1e-12);
        // 6: filtered-direct/8 alias=1.0 bars=[0, 0]
        assertArrayEquals(new int[] {0, 0},
                ReferenceRegions.shiftBarsFor("filtered-direct", 8));
        ReferenceRegions.BeatRegion scaled6 = ReferenceRegions.scaleIncomingRegion(
                new ReferenceRegions.BeatRegion(0, 32), 32, 1.0, null);
        assertEquals("filtered-direct scaled.start", 0, scaled6.start);
        assertEquals("filtered-direct scaled.end", 32, scaled6.end);
        ReferenceRegions.Pair shifted6 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 0, 1.0, null, null);
        assertEquals("filtered-direct shifted.out.start", 8, shifted6.outgoing.start);
        assertEquals("filtered-direct shifted.in.end", 32, shifted6.incoming.end);
        ReferencePaths.Result r6 = ReferencePaths.evaluate("filtered-direct", 8, 200.0, 40.0,
                signals(1.0), true);
        assertEquals("filtered-direct/8 status", "scored", r6.status);
        assertEquals("filtered-direct/8 score", 20.16, r6.score.doubleValue(), 1e-12);
        // 7: filtered-direct/9 alias=1.0 bars=[0, 0]
        assertArrayEquals(new int[] {0, 0},
                ReferenceRegions.shiftBarsFor("filtered-direct", 9));
        ReferenceRegions.BeatRegion scaled7 = ReferenceRegions.scaleIncomingRegion(
                new ReferenceRegions.BeatRegion(0, 32), 32, 1.0, null);
        assertEquals("filtered-direct scaled.start", 0, scaled7.start);
        assertEquals("filtered-direct scaled.end", 32, scaled7.end);
        ReferenceRegions.Pair shifted7 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 0, 1.0, null, null);
        assertEquals("filtered-direct shifted.out.start", 8, shifted7.outgoing.start);
        assertEquals("filtered-direct shifted.in.end", 32, shifted7.incoming.end);
        ReferencePaths.Result r7 = ReferencePaths.evaluate("filtered-direct", 9, 200.0, 40.0,
                signals(1.0), true);
        assertEquals("filtered-direct/9 status", "scored", r7.status);
        assertEquals("filtered-direct/9 score", 50.16, r7.score.doubleValue(), 1e-12);
        // 8: smart-crossfade/3 alias=1.0 bars=[0, 0]
        assertArrayEquals(new int[] {0, 0},
                ReferenceRegions.shiftBarsFor("smart-crossfade", 3));
        ReferenceRegions.BeatRegion scaled8 = ReferenceRegions.scaleIncomingRegion(
                new ReferenceRegions.BeatRegion(0, 32), 32, 1.0, null);
        assertEquals("smart-crossfade scaled.start", 0, scaled8.start);
        assertEquals("smart-crossfade scaled.end", 32, scaled8.end);
        ReferenceRegions.Pair shifted8 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 0, 1.0, null, null);
        assertEquals("smart-crossfade shifted.out.start", 8, shifted8.outgoing.start);
        assertEquals("smart-crossfade shifted.in.end", 32, shifted8.incoming.end);
        ReferencePaths.Result r8 = ReferencePaths.evaluate("smart-crossfade", 3, 200.0, 40.0,
                signals(1.0), true);
        assertEquals("smart-crossfade/3 status", "scored", r8.status);
        assertEquals("smart-crossfade/3 score", 10.16, r8.score.doubleValue(), 1e-12);
        // 9: smart-crossfade/33 alias=1.0 bars=[0, 0]
        assertArrayEquals(new int[] {0, 0},
                ReferenceRegions.shiftBarsFor("smart-crossfade", 33));
        ReferenceRegions.BeatRegion scaled9 = ReferenceRegions.scaleIncomingRegion(
                new ReferenceRegions.BeatRegion(0, 32), 32, 1.0, null);
        assertEquals("smart-crossfade scaled.start", 0, scaled9.start);
        assertEquals("smart-crossfade scaled.end", 32, scaled9.end);
        ReferenceRegions.Pair shifted9 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 0, 1.0, null, null);
        assertEquals("smart-crossfade shifted.out.start", 8, shifted9.outgoing.start);
        assertEquals("smart-crossfade shifted.in.end", 32, shifted9.incoming.end);
        ReferencePaths.Result r9 = ReferencePaths.evaluate("smart-crossfade", 33, 200.0, 40.0,
                signals(1.0), true);
        assertEquals("smart-crossfade/33 status", "scored", r9.status);
        assertEquals("smart-crossfade/33 score", 0.0, r9.score.doubleValue(), 1e-12);
        // 10: smart-crossfade/4 alias=1.0 bars=[0, 0]
        assertArrayEquals(new int[] {0, 0},
                ReferenceRegions.shiftBarsFor("smart-crossfade", 4));
        ReferenceRegions.BeatRegion scaled10 = ReferenceRegions.scaleIncomingRegion(
                new ReferenceRegions.BeatRegion(0, 32), 32, 1.0, null);
        assertEquals("smart-crossfade scaled.start", 0, scaled10.start);
        assertEquals("smart-crossfade scaled.end", 32, scaled10.end);
        ReferenceRegions.Pair shifted10 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 0, 1.0, null, null);
        assertEquals("smart-crossfade shifted.out.start", 8, shifted10.outgoing.start);
        assertEquals("smart-crossfade shifted.in.end", 32, shifted10.incoming.end);
        ReferencePaths.Result r10 = ReferencePaths.evaluate("smart-crossfade", 4, 200.0, 40.0,
                signals(1.0), true);
        assertEquals("smart-crossfade/4 status", "scored", r10.status);
        assertEquals("smart-crossfade/4 score", 0.0, r10.score.doubleValue(), 1e-12);
        // 11: smart-crossfade/44 alias=1.0 bars=[0, 0]
        assertArrayEquals(new int[] {0, 0},
                ReferenceRegions.shiftBarsFor("smart-crossfade", 44));
        ReferenceRegions.BeatRegion scaled11 = ReferenceRegions.scaleIncomingRegion(
                new ReferenceRegions.BeatRegion(0, 32), 32, 1.0, null);
        assertEquals("smart-crossfade scaled.start", 0, scaled11.start);
        assertEquals("smart-crossfade scaled.end", 32, scaled11.end);
        ReferenceRegions.Pair shifted11 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 0, 1.0, null, null);
        assertEquals("smart-crossfade shifted.out.start", 8, shifted11.outgoing.start);
        assertEquals("smart-crossfade shifted.in.end", 32, shifted11.incoming.end);
        ReferencePaths.Result r11 = ReferencePaths.evaluate("smart-crossfade", 44, 200.0, 40.0,
                signals(1.0), true);
        assertEquals("smart-crossfade/44 status", "scored", r11.status);
        assertEquals("smart-crossfade/44 score", 0.0, r11.score.doubleValue(), 1e-12);
        // 12: dead-air/0 alias=1.0 bars=[0, 0]
        assertArrayEquals(new int[] {0, 0},
                ReferenceRegions.shiftBarsFor("dead-air", 0));
        ReferenceRegions.BeatRegion scaled12 = ReferenceRegions.scaleIncomingRegion(
                new ReferenceRegions.BeatRegion(0, 32), 32, 1.0, null);
        assertEquals("dead-air scaled.start", 0, scaled12.start);
        assertEquals("dead-air scaled.end", 32, scaled12.end);
        ReferenceRegions.Pair shifted12 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 0, 1.0, null, null);
        assertEquals("dead-air shifted.out.start", 8, shifted12.outgoing.start);
        assertEquals("dead-air shifted.in.end", 32, shifted12.incoming.end);
        ReferencePaths.Result r12 = ReferencePaths.evaluate("dead-air", 0, 200.0, 40.0,
                signals(1.0), true);
        assertEquals("dead-air/0 status", "scored", r12.status);
        assertEquals("dead-air/0 score", 1.16, r12.score.doubleValue(), 1e-12);
        // 13: fallback-crossfade/1 alias=1.0 bars=[0, 0]
        assertArrayEquals(new int[] {0, 0},
                ReferenceRegions.shiftBarsFor("fallback-crossfade", 1));
        ReferenceRegions.BeatRegion scaled13 = ReferenceRegions.scaleIncomingRegion(
                new ReferenceRegions.BeatRegion(0, 32), 32, 1.0, null);
        assertEquals("fallback-crossfade scaled.start", 0, scaled13.start);
        assertEquals("fallback-crossfade scaled.end", 32, scaled13.end);
        ReferenceRegions.Pair shifted13 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 0, 1.0, null, null);
        assertEquals("fallback-crossfade shifted.out.start", 8, shifted13.outgoing.start);
        assertEquals("fallback-crossfade shifted.in.end", 32, shifted13.incoming.end);
        ReferencePaths.Result r13 = ReferencePaths.evaluate("fallback-crossfade", 1, 200.0, 40.0,
                signals(1.0), true);
        assertEquals("fallback-crossfade/1 status", "scored", r13.status);
        assertEquals("fallback-crossfade/1 score", 1.16, r13.score.doubleValue(), 1e-12);
        // 14: soft-skip/5 alias=1.0 bars=[0, 0]
        assertArrayEquals(new int[] {0, 0},
                ReferenceRegions.shiftBarsFor("soft-skip", 5));
        ReferenceRegions.BeatRegion scaled14 = ReferenceRegions.scaleIncomingRegion(
                new ReferenceRegions.BeatRegion(0, 32), 32, 1.0, null);
        assertEquals("soft-skip scaled.start", 0, scaled14.start);
        assertEquals("soft-skip scaled.end", 32, scaled14.end);
        ReferenceRegions.Pair shifted14 = ReferenceRegions.shiftRegionPair(
                new ReferenceRegions.BeatRegion(8, 40), new ReferenceRegions.BeatRegion(0, 32),
                0, 0, 1.0, null, null);
        assertEquals("soft-skip shifted.out.start", 8, shifted14.outgoing.start);
        assertEquals("soft-skip shifted.in.end", 32, shifted14.incoming.end);
        ReferencePaths.Result r14 = ReferencePaths.evaluate("soft-skip", 5, 200.0, 40.0,
                signals(1.0), true);
        assertEquals("soft-skip/5 status", "scored", r14.status);
        assertEquals("soft-skip/5 score", 10.16, r14.score.doubleValue(), 1e-12);
    }
}
