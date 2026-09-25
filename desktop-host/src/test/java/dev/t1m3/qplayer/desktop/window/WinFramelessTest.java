package dev.t1m3.qplayer.desktop.window;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WinFramelessTest {

    @Test
    public void lyricTopControlsRemainClickableAndEmptySpaceDrags() {
        double width = 1100;

        assertTrue(WinFrameless.isLyricButton(20, 20, width, 1));
        assertTrue(WinFrameless.isLyricButton(width - 20, 20, width, 1));
        assertTrue(WinFrameless.isLyricButton(width - 70, 20, width, 1));

        assertFalse(WinFrameless.isLyricButton(width / 2, 20, width, 1));
        assertFalse(WinFrameless.isLyricButton(20, 3, width, 1));
        assertFalse(WinFrameless.isLyricButton(width - 49, 20, width, 1));
    }

    /**
     * Every button in the row, not just the two nearest the edge.
     *
     * <p>A button past the last reserved slot renders normally and is completely
     * dead: Windows answers the press as a window drag before the app sees it,
     * so no QML test can catch it (a dispatched pointer never goes through
     * WM_NCHITTEST) and nothing appears in any log. That is exactly what
     * happened when the full-width toggle was added as a third one.
     */
    @Test
    public void everyButtonInTheLyricTopRightRowIsClickable() {
        double width = 1100;
        // Centres at 6+20, then each next one 46 further in.
        assertTrue("offset", WinFrameless.isLyricButton(width - 26, 20, width, 1));
        assertTrue("cover mode", WinFrameless.isLyricButton(width - 72, 20, width, 1));
        assertTrue("full width", WinFrameless.isLyricButton(width - 118, 20, width, 1));
        // Past the row, the strip drags the window again.
        assertFalse("beyond the row", WinFrameless.isLyricButton(width - 164, 20, width, 1));
    }

    @Test
    public void lyricTopControlHitBoxesScaleWithDpi() {
        double scale = 1.5;
        double width = 1650;

        assertTrue(WinFrameless.isLyricButton(30, 30, width, scale));
        assertTrue(WinFrameless.isLyricButton(width - 105, 30, width, scale));
        assertFalse(WinFrameless.isLyricButton(width / 2, 30, width, scale));
    }
}
