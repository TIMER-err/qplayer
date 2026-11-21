package dev.t1m3.qplayer.audio.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 声道布局的对齐测试：对应 Sonalis {@code sonalis-core/src/channel.rs} 的 {@code mod tests}。
 */
public class ChannelLayoutTest {

    @Test
    public void countRoundtrip() {
        for (int n : new int[] {1, 2, 6, 8}) {
            assertEquals(n, ChannelLayout.of(n).count());
        }
    }

    @Test
    public void otherPreservesCount() {
        ChannelLayout l = ChannelLayout.of(4);
        assertEquals(4, l.count());
        assertEquals("4ch", l.toString());
    }

    @Test
    public void surroundThresholdIsStrictlyAboveStereo() {
        assertFalse(ChannelLayout.MONO.isSurround());
        assertFalse(ChannelLayout.STEREO.isSurround());
        assertTrue(ChannelLayout.of(4).isSurround());
        assertTrue(ChannelLayout.SURROUND_5_1.isSurround());
        assertTrue(ChannelLayout.SURROUND_7_1.isSurround());
    }

    /**
     * 超过上限的声道数必须被夹住而不是回绕——回绕会把 65536 变成 0，
     * 下游 {@link AudioBuf} 的声道数断言直接抛异常。
     */
    @Test
    public void absurdChannelCountsAreClampedNotWrapped() {
        ChannelLayout l = ChannelLayout.of(65_536);
        assertEquals("夹到上界，而不是回绕成 0", 65_535, l.count());
        assertEquals(65_535, ChannelLayout.of(Integer.MAX_VALUE).count());
    }

    @Test
    public void sameGeometryComparesCountsOnly() {
        assertTrue(ChannelLayout.of(4).sameGeometry(ChannelLayout.of(4)));
        assertFalse(ChannelLayout.STEREO.sameGeometry(ChannelLayout.SURROUND_5_1));
    }
}
