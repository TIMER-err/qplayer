package dev.t1m3.qplayer.lyric.skia;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FluidBackgroundColorTest {

    @Test
    public void aNeonCoverDoesNotStayAFluorescentPrimary() {
        float[] rgb = FluidBackground.amllAdjust(new int[]{0xFFFF0000});
        assertTrue("pure red must not clip to a fluorescent primary, got "
                        + rgb[0] + "," + rgb[1] + "," + rgb[2],
                chroma(rgb) <= FluidBackground.CHROMA_CAP + 0.01f);
        assertTrue("and must keep some red character", rgb[0] > rgb[1] && rgb[0] > rgb[2]);
    }

    @Test
    public void greyPhotographyIsLeftAloneByTheChromaCap() {
        float[] rgb = FluidBackground.amllAdjust(new int[]{0xFF808080});
        assertEquals(rgb[0], rgb[1], 0.5f);
        assertEquals(rgb[1], rgb[2], 0.5f);
        assertEquals(96f, rgb[0], 2f);
    }

    @Test
    public void aQuietCoverStaysUnderTheCapWithoutBeingGreyed() {
        float[] rgb = FluidBackground.amllAdjust(new int[]{0xFF908878});
        assertTrue(chroma(rgb) <= FluidBackground.CHROMA_CAP);
        assertTrue("warm grey must stay slightly warm", rgb[0] >= rgb[2]);
    }

    @Test
    public void aWhiteCoverIsPulledDownSoLyricsStayReadable() {
        float[] rgb = FluidBackground.amllAdjust(new int[]{0xFFFFFFFF});
        float y = rgb[0] * 0.3f + rgb[1] * 0.59f + rgb[2] * 0.11f;
        assertTrue("white cover luma " + y + " must sit under the cap",
                y <= FluidBackground.LUMA_CAP + 0.01f);
    }

    private static float chroma(float[] rgb) {
        float y = rgb[0] * 0.3f + rgb[1] * 0.59f + rgb[2] * 0.11f;
        return Math.max(Math.abs(rgb[0] - y),
                Math.max(Math.abs(rgb[1] - y), Math.abs(rgb[2] - y)));
    }
}
