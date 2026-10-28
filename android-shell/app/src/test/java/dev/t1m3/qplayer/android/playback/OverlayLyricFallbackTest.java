package dev.t1m3.qplayer.android.playback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Overlay idle text must match the desktop lyric: keep the current track
 * while paused, and only show "未播放" when nothing is loaded.
 */
public class OverlayLyricFallbackTest {

    @Test
    public void pausedPlaybackKeepsTheCurrentTitle() {
        assertEquals("previous song  ·  artist",
                OverlayLyricWindow.fallbackText(false, "previous song", "artist"));
        assertEquals("song",
                OverlayLyricWindow.fallbackText(false, "song", ""));
    }

    @Test
    public void noTrackWhilePausedIsIdle() {
        assertEquals("未播放", OverlayLyricWindow.fallbackText(false, "", ""));
        assertEquals("未播放", OverlayLyricWindow.fallbackText(false, null, null));
    }

    @Test
    public void activePlaybackFallsBackToTrackMetadataWhileLyricsLoad() {
        assertEquals("song  ·  artist",
                OverlayLyricWindow.fallbackText(true, "song", "artist"));
        assertEquals("song",
                OverlayLyricWindow.fallbackText(true, "song", ""));
        assertEquals("", OverlayLyricWindow.fallbackText(true, "", ""));
    }

    @Test
    public void landscapeAbsolutePositionMapsOntoPortrait() {
        // 3200x2136 landscape, overlay 2880x160 near the bottom-right.
        float nx = OverlayLyricWindow.storedFraction(280, 3200, 2880, 0.5f);
        float ny = OverlayLyricWindow.storedFraction(1600, 2136, 160, 0.72f);
        // Portrait 2136x3200 with overlay width 1922 keeps the same fractions.
        assertEquals(187, OverlayLyricWindow.mappedPosition(nx, 2136, 1922));
        assertEquals(2462, OverlayLyricWindow.mappedPosition(ny, 3200, 160));
    }

    @Test
    public void fractionStaysInsideTheNewScreen() {
        assertEquals(0, OverlayLyricWindow.mappedPosition(-1f, 1080, 900));
        assertEquals(180, OverlayLyricWindow.mappedPosition(2f, 1080, 900));
        assertEquals(0, OverlayLyricWindow.mappedPosition(0.5f, 800, 900));
        assertEquals(0.5f, OverlayLyricWindow.storedFraction(90, 1080, 900, 0.72f), 0.001f);
    }

    @Test
    public void controlsSitCentredAboveTheLyricWindow() {
        int[] xy = OverlayLyricWindow.controlPosition(100, 400, 800, 160, 280, 56, 1080, 1920, 8);
        assertEquals(100 + (800 - 280) / 2, xy[0]);
        assertEquals(400 - 8 - 56, xy[1]);
    }

    @Test
    public void controlsFlipBelowWhenPushedAgainstTheTop() {
        int[] xy = OverlayLyricWindow.controlPosition(40, 10, 900, 160, 280, 56, 1080, 1920, 8);
        assertEquals(10 + 160 + 8, xy[1]);
        assertEquals(40 + (900 - 280) / 2, xy[0]);
    }

    @Test
    public void controlsDoNotClampOntoTheLyricWindow() {
        int[] xy = OverlayLyricWindow.controlPosition(0, 0, 1080, 160, 280, 56, 1080, 1920, 8);
        assertEquals(168, xy[1]);
        assertEquals(400, xy[0]);
        int[] tight = OverlayLyricWindow.controlPosition(0, 0, 1080, 1920, 280, 56, 1080, 1920, 8);
        assertEquals(-64, tight[1]);
    }
}
