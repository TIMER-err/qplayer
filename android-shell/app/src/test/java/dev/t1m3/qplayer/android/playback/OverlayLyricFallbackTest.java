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
}
