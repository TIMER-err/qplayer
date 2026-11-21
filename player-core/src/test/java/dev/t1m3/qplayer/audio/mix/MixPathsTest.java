package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** {@link MixPaths} 的判据：闸门（淘汰）与分层取模（选择）各钉一条。 */
public class MixPathsTest {

    private static MixPaths.Features feats(double ratio, double semis) {
        MixPaths.Features f = new MixPaths.Features();
        f.tempoRatio = ratio;
        f.semitones = semis;
        f.barsA = 4;
        f.barsB = 4;
        return f;
    }

    @Test
    public void 拍速差超过百分之六时对拍类路径全部被淘汰() {
        MixPaths.Pick p = MixPaths.choose(feats(1.067, 0.0), 12345L);
        for (MixPaths.Id id : new MixPaths.Id[] {
                MixPaths.Id.DANCE_ORIGINAL, MixPaths.Id.DANCE_SHIFTED,
                MixPaths.Id.HIPHOP_DIRECT, MixPaths.Id.HIPHOP_SHIFTED}) {
            for (MixPaths.Choice c : MixPaths.evaluate(feats(1.067, 0.0))) {
                if (c.template.id == id) {
                    assertNotNull("对拍路径应当被淘汰：" + id, c.verdict);
                }
            }
        }
        assertFalse("挑出来的必须不是对拍类", p.template.beatMatched);
    }

    @Test
    public void 调性差半音以内时对拍原调路径可用() {
        MixPaths.Pick p = MixPaths.choose(feats(1.01, 0.0), 7L);
        assertTrue("拍速与调性都合时应当选对拍路径", p.template.beatMatched);
    }

    @Test
    public void 移调超过五半音时移调类路径被淘汰() {
        for (MixPaths.Choice c : MixPaths.evaluate(feats(1.0, 7.0))) {
            if (c.template.pitchShift) {
                assertNotNull("移调类应当被淘汰：" + c.template.id, c.verdict);
            }
        }
    }

    @Test
    public void 叠唱超限时对拍路径被淘汰() {
        MixPaths.Features f = feats(1.0, 0.0);
        f.vocalOverlapSecs = 1.2;
        for (MixPaths.Choice c : MixPaths.evaluate(f)) {
            if (c.template.beatMatched) {
                assertNotNull("叠唱超限应当淘汰对拍路径：" + c.template.id, c.verdict);
            }
        }
    }

    @Test
    public void 同一个种子结果稳定_不同种子可能不同() {
        MixPaths.Pick a = MixPaths.choose(feats(1.067, 0.0), 42L);
        MixPaths.Pick b = MixPaths.choose(feats(1.067, 0.0), 42L);
        assertEquals(a.template.id, b.template.id);
        MixPaths.Pick c = MixPaths.choose(feats(1.067, 0.0), 43L);
        assertNotNull(c.template.id);
    }
}
