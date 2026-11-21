package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * <b>ReferenceParityTest（路径级评分）</b>：{@link ReferencePaths#evaluate} 必须与参考包
 * {@code candidate_paths.evaluate_candidate} 逐位一致（status / score / factors / missing）。
 *
 * <p>期望值由 Python 侧真实实现算出后固化，容差 1e-12。
 */
public class ReferencePathsParityTest {

    private static ReferencePaths.Signals full() {
        return new ReferencePaths.Signals()
                .strictTempo(Boolean.TRUE).looseTempo(Boolean.TRUE).tonalities(Boolean.TRUE)
                .matchingBars(Integer.valueOf(8)).outgoingBeats(Integer.valueOf(32))
                .incomingBeats(Integer.valueOf(32)).tempoAlias(Double.valueOf(1.0))
                .leadingStrength(Integer.valueOf(1))
                .incomingLoudness(Double.valueOf(-12.0))
                .incomingTrailingLoudness(Double.valueOf(-10.0))
                .vocalOverlap(Boolean.FALSE)
                .loudnessRelation(Integer.valueOf(0))
                .trailingSpan(Double.valueOf(6.0))
                .outgoingLoudnessSignificant(Boolean.TRUE);
    }

    @Test
    public void 路径评分与参考包逐位一致() {
        check("dance-original", 19, 200.0, 40.0, full(), "scored",
                Double.valueOf(17.16), null);
        check("dance-shifted", 17, 200.0, 40.0, full(), "scored",
                Double.valueOf(20.56), null);
        check("filtered-direct", 8, 200.0, 40.0, full(), "scored",
                Double.valueOf(24.16), null);
        check("filtered-direct", 9, 200.0, 40.0, full(), "scored",
                Double.valueOf(60.16), null);
        check("filtered-shifted", 12, 200.0, 40.0, full(), "scored",
                Double.valueOf(0.0), null);
        check("hiphop-direct", 18, 200.0, 40.0, full(), "scored",
                Double.valueOf(120.16), null);
        check("hiphop-shifted", 15, 200.0, 40.0, full(), "scored",
                Double.valueOf(120.16), null);
        check("pop-scaled-shifted", 16, 200.0, 40.0, full(), "scored",
                Double.valueOf(60.16), null);
        check("pop-scaled-shifted", 20, 200.0, 40.0, full(), "scored",
                Double.valueOf(60.16), null);
        check("smart-crossfade", 3, 200.0, 40.0, full(), "scored",
                Double.valueOf(10.16), null);
        check("smart-crossfade", 33, 200.0, 40.0, full(), "scored",
                Double.valueOf(0.0), null);
        check("smart-crossfade", 4, 200.0, 40.0, full(), "scored",
                Double.valueOf(0.0), null);
        check("smart-crossfade", 44, 200.0, 40.0, full(), "scored",
                Double.valueOf(0.0), null);
        check("dead-air", 0, 200.0, 40.0, full(), "scored",
                Double.valueOf(1.16), null);
        check("fallback-crossfade", 1, 200.0, 40.0, full(), "scored",
                Double.valueOf(1.16), null);
        check("soft-skip", 5, 200.0, 40.0, full(), "scored",
                Double.valueOf(10.16), null);
        // missing/matching
        ReferencePaths.Signals s = full();
        s.matchingBars(null);
        s.provided.remove("matching_bar_count");
        check("dance-original", 19, 200.0, 40.0, s, "missing_upstream_signals", null, new String[] {"matching_bar_count"});
        // none/strict（显式 null 的布尔不算缺，按 false）
        check("dance-original", 19, 200.0, 40.0, full().strictTempo(null),
                "scored", Double.valueOf(0.0), null);
        // smart/short
        check("smart-crossfade", 3, 100.0, 10.0, full().trailingSpan(Double.valueOf(3.0)),
                "scored", Double.valueOf(0.0), null);
        // smart/high-code
        check("smart-crossfade", 44, 100.0, 10.0, full().loudnessRelation(Integer.valueOf(5)),
                "scored", Double.valueOf(0.0), null);
    }

    private static void check(String path, int style, double outEnd, double inEnd,
                              ReferencePaths.Signals signals, String status, Double score,
                              String[] missing) {
        ReferencePaths.Result r = ReferencePaths.evaluate(path, style, outEnd, inEnd, signals, true);
        assertEquals("status " + path + "/" + style, status, r.status);
        if (score == null) {
            assertNull("score " + path, r.score);
        } else {
            assertNotNull("score " + path, r.score);
            assertEquals("score " + path + "/" + style, score.doubleValue(), r.score.doubleValue(), 1e-12);
        }
        if (missing != null) {
            assertEquals("missing", missing.length, r.missingInputs.size());
            for (int i = 0; i < missing.length; i++) {
                assertEquals("missing[" + i + "]", missing[i], r.missingInputs.get(i));
            }
        }
    }
}
