package dev.t1m3.qplayer.desktop.window;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DesktopLyricStateTest {

    @Test
    public void idlePlaybackAlwaysUsesPlaceholderInsteadOfStaleMetadata() {
        assertEquals(DesktopLyricState.IDLE_PLACEHOLDER,
                DesktopLyricState.fallback(snapshot("previous song", "artist", false)));
        assertEquals(DesktopLyricState.IDLE_PLACEHOLDER,
                DesktopLyricState.fallback(snapshot("", "", false)));
    }

    @Test
    public void activePlaybackFallsBackToTrackMetadataWhileLyricsLoad() {
        assertEquals("song  ·  artist",
                DesktopLyricState.fallback(snapshot("song", "artist", true)));
        assertEquals("song",
                DesktopLyricState.fallback(snapshot("song", "", true)));
    }

    private static DesktopLyricSnapshot snapshot(String title, String artist, boolean playing) {
        return new DesktopLyricSnapshot(null, title, artist,
                0L, false, 0L, 0L, "", 26, 2, true, true,
                playing, DesktopLyricSnapshot.EMPTY.palette);
    }
}
