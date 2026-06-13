package dev.t1m3.qplayer.android.lyric;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;

// Minimal port of Haedus's Skia helper for the lyric renderer: the current draw
// canvas (set per frame by the host) plus reusable scratch Paints. A small
// round-robin pool (not a single shared paint) so a helper that holds two at
// once -- the interlude-dot glow uses glow1 + glow2 -- doesn't alias them.
public final class LyricSkia {

    private LyricSkia() {
    }

    private static Canvas current;
    private static final Paint[] POOL = new Paint[6];
    private static int next;

    public static void setCanvas(Canvas c) {
        current = c;
    }

    public static Canvas getCanvas() {
        return current;
    }

    public static Paint scratchPaint() {
        int i = next;
        next = (next + 1) % POOL.length;
        Paint p = POOL[i];
        if (p == null) {
            p = new Paint();
            POOL[i] = p;
        }
        p.reset();
        p.setAntiAlias(true);
        return p;
    }
}
