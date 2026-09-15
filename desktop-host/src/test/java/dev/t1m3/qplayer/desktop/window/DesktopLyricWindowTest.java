package dev.t1m3.qplayer.desktop.window;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DesktopLyricWindowTest {

    @Test
    public void onlyThePersistentLockButtonIsAnUnlockRegion() {
        assertTrue(DesktopLyricWindow.isUnlockPoint(870, 146));
        assertFalse(DesktopLyricWindow.isUnlockPoint(72, 146));
        assertFalse(DesktopLyricWindow.isUnlockPoint(870, 34));
        assertFalse(DesktopLyricWindow.isUnlockPoint(900, 146));
    }

    @Test
    public void qmlInputRegionsFollowTheFourCornerLayout() {
        assertTrue(DesktopLyricWindow.isControlPoint(32, 34));
        assertTrue(DesktopLyricWindow.isControlPoint(870, 34));
        assertTrue(DesktopLyricWindow.isControlPoint(72, 146));
        assertTrue(DesktopLyricWindow.isControlPoint(870, 146));
        assertFalse(DesktopLyricWindow.isControlPoint(450, 90));
    }

    /** A drag is constrained as it happens, so no intermediate position can ever
     *  leave the work area -- it is not just snapped back on release. */
    @Test
    public void aDragCanNeverLeaveTheWorkArea() {
        int[] workArea = {0, 0, 1920, 1040};
        assertArrayEquals("a fully on-screen position is left alone",
                new int[]{400, 300}, DesktopLyricWindow.clampToWorkArea(400, 300, workArea));
        assertArrayEquals("dragging past the left/top edge stops at it",
                new int[]{0, 0}, DesktopLyricWindow.clampToWorkArea(-600, -400, workArea));
        assertArrayEquals("dragging past the right/bottom edge stops flush with it",
                new int[]{1920 - DesktopLyricWindow.WIDTH, 1040 - DesktopLyricWindow.HEIGHT},
                DesktopLyricWindow.clampToWorkArea(5000, 5000, workArea));
    }

    /** A monitor narrower than the window must still yield a usable origin rather
     *  than a negative-width range that clamps below the work area. */
    @Test
    public void aWorkAreaSmallerThanTheWindowPinsItToTheOrigin() {
        int[] tiny = {100, 50, 640, 120};
        assertArrayEquals(new int[]{100, 50},
                DesktopLyricWindow.clampToWorkArea(-999, -999, tiny));
        assertArrayEquals(new int[]{100, 50},
                DesktopLyricWindow.clampToWorkArea(9999, 9999, tiny));
    }
}
