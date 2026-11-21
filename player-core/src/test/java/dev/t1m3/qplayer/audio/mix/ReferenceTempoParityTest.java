package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * <b>ReferenceParityTest（tempo 部分）</b>：{@link ReferenceTempo} 必须与参考包
 * {@code scoring.harmonic_tempo} 及 {@code _candidate} 的 {@code stretch_comfort} 逐位一致。
 *
 * <p>期望值由 Python 侧真实实现算出后固化，容差 1e-12。
 */
public class ReferenceTempoParityTest {

    @Test
    public void tempo关系与stretchComfort与参考包逐位一致() {
        check(120.0, 120.0, 1.0, 0.0, 120.0, 1.0, 1.0, 1.0);
        check(128.0, 120.0, 1.0, 0.06453852113757108, 123.93546707863734, 0.9682458365518543, 1.0327955589886446, 0.9377441790605585);
        check(174.0, 87.0, 2.0, 0.0, 174.0, 1.0, 1.0, 1.0);
        check(100.0, 129.0, 1.0, 0.25464221837358014, 113.57816691600547, 1.1357816691600546, 0.8804509063256238, 0.3676373371027914);
        check(150.0, 126.0, 1.0, 0.17435338714477755, 137.4772708486752, 0.916515138991168, 1.091089451179962, 0.6255512804685347);
    }

    private static void check(double out, double in, double alias, double logDistance,
                              double target, double outRate, double inRate, double comfort) {
        ReferenceTempo.Relationship r = ReferenceTempo.harmonicTempo(out, in, alias, logDistance);
        assertEquals("target_bpm", target, r.targetBpm, 1e-12);
        assertEquals("outgoing_rate", outRate, r.outgoingRate, 1e-12);
        assertEquals("incoming_rate", inRate, r.incomingRate, 1e-12);
        assertEquals("stretch_comfort", comfort,
                ReferenceTempo.stretchComfort(r, ReferenceProfiles.STRETCH_SIGMA), 1e-12);
    }
}
