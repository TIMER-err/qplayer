package dev.t1m3.qplayer.desktop.window;

/**
 * Interruptible frame-clock motion for the floating lyric controls.
 *
 * <p>The window is five times wider than it is tall, so equal percentage scaling
 * looks almost entirely horizontal. Independent canvas scales make the physical
 * expansion about 18 logical pixels on every edge without moving any control's
 * own coordinates.</p>
 */
final class DesktopLyricChromeMotion {

    static final long DURATION_NANOS = 160_000_000L;
    private static final float HIDDEN_SCALE_X = 1.04f;
    private static final float HIDDEN_SCALE_Y = 1.20f;
    private static final float BACKGROUND_SHOWN_ALPHA = 0.92f;

    private boolean initialized;
    private boolean targetShown;
    private long startedNanos;
    private float startProgress;
    private float progress;

    /** User-configurable via settings.desktopLyricIdleOpacity; see
     *  DesktopLyricWindow.Appearance.idleBackgroundOpacity. */
    Frame update(boolean shown, long nowNanos, float hiddenAlpha) {
        float clampedHiddenAlpha = Math.max(0f, Math.min(1f, hiddenAlpha));
        if (!initialized) {
            initialized = true;
            targetShown = shown;
            startProgress = shown ? 1f : 0f;
            progress = startProgress;
            startedNanos = nowNanos;
        } else if (shown != targetShown) {
            progress = valueAt(nowNanos);
            startProgress = progress;
            targetShown = shown;
            startedNanos = nowNanos;
        }
        progress = valueAt(nowNanos);
        float hidden = 1f - progress;
        return new Frame(
                progress,
                1f + (HIDDEN_SCALE_X - 1f) * hidden,
                1f + (HIDDEN_SCALE_Y - 1f) * hidden,
                clampedHiddenAlpha
                        + (BACKGROUND_SHOWN_ALPHA - clampedHiddenAlpha) * progress);
    }

    private float valueAt(long nowNanos) {
        float target = targetShown ? 1f : 0f;
        if (progress == target && startProgress == target) return target;
        float elapsed = Math.max(0f, (nowNanos - startedNanos) / (float) DURATION_NANOS);
        if (elapsed >= 1f) return target;
        // Enter decelerates inward; exit accelerates outward. Both are evaluated
        // from the current presentation value so a quick pointer reversal is smooth.
        float eased = targetShown
                ? 1f - (1f - elapsed) * (1f - elapsed)
                : elapsed * elapsed;
        return startProgress + (target - startProgress) * eased;
    }

    record Frame(float opacity, float scaleX, float scaleY, float backgroundOpacity) {
    }
}
