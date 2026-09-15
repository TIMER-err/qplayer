package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.lyric.LyricTimeline;

/** Immutable hand-off from the main QML/controller thread to desktop lyrics. */
final class DesktopLyricSnapshot {

    static final DesktopLyricSnapshot EMPTY = new DesktopLyricSnapshot(
            null, "", "", 0L, false, System.nanoTime(), 0L,
            "", 26, 2, true, true, false, DesktopLyricPalette.capture(true));

    final LyricTimeline.Prepared timeline;
    final String title;
    final String artist;
    final long positionMs;
    final boolean running;
    final long capturedNanos;
    final long offsetMs;
    /** Desktop lyrics' own font source; empty follows the app-wide font setting. */
    final String fontFamily;
    final int fontSize;
    final int fontWeight;
    final boolean shadow;
    final boolean outline;
    final boolean playing;
    final DesktopLyricPalette palette;

    DesktopLyricSnapshot(LyricTimeline.Prepared timeline, String title, String artist,
                         long positionMs, boolean running, long capturedNanos, long offsetMs,
                         String fontFamily, int fontSize, int fontWeight,
                         boolean shadow, boolean outline,
                         boolean playing, DesktopLyricPalette palette) {
        this.timeline = timeline;
        this.title = title != null ? title : "";
        this.artist = artist != null ? artist : "";
        this.positionMs = Math.max(0L, positionMs);
        this.running = running;
        this.capturedNanos = capturedNanos;
        this.offsetMs = offsetMs;
        this.fontFamily = fontFamily != null ? fontFamily : "";
        this.fontSize = fontSize;
        this.fontWeight = fontWeight;
        this.shadow = shadow;
        this.outline = outline;
        this.playing = playing;
        this.palette = palette;
    }

    long predictedPosition(long nowNanos) {
        long predicted = positionMs;
        if (running && nowNanos > capturedNanos) predicted += (nowNanos - capturedNanos) / 1_000_000L;
        return Math.max(0L, predicted - offsetMs);
    }
}
