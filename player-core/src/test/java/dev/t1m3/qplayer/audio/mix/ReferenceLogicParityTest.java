package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * <b>ReferenceParityTest（tempo / 调性）</b>：{@link ReferenceLogic} 必须与参考包
 * {@code recovered_logic.py} 的 {@code tempo_relationship} / {@code tonality_relationship} 逐位一致。
 *
 * <p>期望值由 Python 侧真实实现算出后固化，容差 1e-12。
 */
public class ReferenceLogicParityTest {

    @Test
    public void tempo关系与参考包逐位一致() {
        checkTempo(120.0, 120.0, 1.0, 0.0, "identical");
        checkTempo(128.0, 120.0, 1.0, 0.06453852113757108, "similar");
        checkTempo(174.0, 87.0, 2.0, 0.0, "identical");
        checkTempo(100.0, 129.0, 1.0, 0.25464221837358014, "incompatible");
        checkTempo(150.0, 126.0, 1.0, 0.17435338714477755, "incompatible");
        checkTempo(90.0, 180.0, 0.5, 0.0, "identical");
        checkTempo(121.0, 120.0, 1.0, 0.008298802814695527, "similar");
    }

    @Test
    public void 调性关系与参考包逐位一致() {
        assertEquals("tonality 0major vs 0major", "identical",
                ReferenceLogic.tonalityRelationship(new ReferenceLogic.Key(0, "major"),
                        new ReferenceLogic.Key(0, "major")));
        assertEquals("tonality 0major vs 9minor", "relative",
                ReferenceLogic.tonalityRelationship(new ReferenceLogic.Key(0, "major"),
                        new ReferenceLogic.Key(9, "minor")));
        assertEquals("tonality 2major vs 5major", "incompatible",
                ReferenceLogic.tonalityRelationship(new ReferenceLogic.Key(2, "major"),
                        new ReferenceLogic.Key(5, "major")));
        assertEquals("tonality 4major vs 11minor", "incompatible",
                ReferenceLogic.tonalityRelationship(new ReferenceLogic.Key(4, "major"),
                        new ReferenceLogic.Key(11, "minor")));
        assertEquals("tonality 0neutral vs 0neutral", "identical",
                ReferenceLogic.tonalityRelationship(new ReferenceLogic.Key(0, "neutral"),
                        new ReferenceLogic.Key(0, "neutral")));
        assertEquals("tonality 0neutral vs 3neutral", "incompatible",
                ReferenceLogic.tonalityRelationship(new ReferenceLogic.Key(0, "neutral"),
                        new ReferenceLogic.Key(3, "neutral")));
        assertEquals("tonality 11minor vs 4major", "incompatible",
                ReferenceLogic.tonalityRelationship(new ReferenceLogic.Key(11, "minor"),
                        new ReferenceLogic.Key(4, "major")));
    }

    private static void checkTempo(double out, double in, double alias, double logDistance,
                               String relationship) {
        ReferenceLogic.TempoRelation r = ReferenceLogic.tempoRelationship(out, in);
        assertEquals("alias", alias, r.alias, 1e-12);
        assertEquals("log_distance", logDistance, r.logDistance, 1e-12);
        assertEquals("relationship", relationship, r.relationship);
    }
}
