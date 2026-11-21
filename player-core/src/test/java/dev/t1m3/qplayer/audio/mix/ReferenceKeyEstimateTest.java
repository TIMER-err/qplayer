package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link ReferenceKeyEstimate} 的判据：合成三和弦的 chroma 必须估出对应的调。
 */
public class ReferenceKeyEstimateTest {

    @Test
    public void C大调三和弦估出C大调() {
        double[] chroma = triad(0, 4, 7);
        ReferenceKeyEstimate.Estimate e = ReferenceKeyEstimate.estimate(chroma);
        assertEquals(0, e.tonic);
        assertEquals("major", e.mode);
        assertTrue("置信度应 > 0.3（参考包的闸门门槛）", e.confidence > 0.3);
    }

    @Test
    public void A小三和弦估出A小调() {
        double[] chroma = triad(9, 0, 4);
        ReferenceKeyEstimate.Estimate e = ReferenceKeyEstimate.estimate(chroma);
        assertEquals(9, e.tonic);
        assertEquals("minor", e.mode);
    }

    @Test
    public void 均匀chroma没有置信度() {
        double[] flat = new double[12];
        for (int i = 0; i < 12; i++) {
            flat[i] = 1.0 / 12.0;
        }
        ReferenceKeyEstimate.Estimate e = ReferenceKeyEstimate.estimate(flat);
        assertEquals(0.0, e.confidence, 1e-9);
    }

    private static double[] triad(int root, int third, int fifth) {
        double[] chroma = new double[12];
        chroma[root] = 0.5;
        chroma[third] = 0.3;
        chroma[fifth] = 0.2;
        double sum = 0.0;
        for (int i = 0; i < 12; i++) {
            sum += chroma[i];
        }
        for (int i = 0; i < 12; i++) {
            chroma[i] /= sum;
        }
        return chroma;
    }
}
