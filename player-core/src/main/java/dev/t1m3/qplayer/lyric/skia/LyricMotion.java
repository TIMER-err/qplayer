package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LyricTimeline;

/** Playback-time curves shared by lyric renderers. */
final class LyricMotion {

    private static final long ACTIVE_FADE_OUT_MS = 350L;
    private static final long ACTIVE_FADE_OUT_DELAY_MS = 100L;
    private static final long ACTIVE_FADE_IN_MS = 600L;
    private static final long ACTIVE_FADE_IN_DELAY_MS = 150L;
    private static final long CLOUD_FLY_MS = 380L;
    private static final long BACKGROUND_POP_IN_MS = 460L;
    private static final long BACKGROUND_POP_IN_DELAY_MS = 150L;
    private static final long BACKGROUND_POP_OUT_MS = 280L;

    private LyricMotion() {}

    static float active(long positionMs, LyricTimeline.Group group) {
        long fadeInStart = fadeInStart(group);
        long fadeInEnd = fadeInStart + ACTIVE_FADE_IN_MS;
        if (positionMs < fadeInStart) return 0f;
        if (positionMs < fadeInEnd) {
            float elapsed = (positionMs - fadeInStart) / (float) ACTIVE_FADE_IN_MS;
            return smoothstep(0f, 1f, elapsed);
        }
        long fadeOutStart = group.endMs + ACTIVE_FADE_OUT_DELAY_MS;
        long fadeOutEnd = fadeOutStart + ACTIVE_FADE_OUT_MS;
        if (positionMs < fadeOutStart) return 1f;
        if (positionMs >= fadeOutEnd) return 0f;
        float elapsed = (positionMs - fadeOutStart)
                / (float) Math.max(1L, fadeOutEnd - fadeOutStart);
        return 1f - smoothstep(0f, 1f, elapsed);
    }

    static long fadeInStart(LyricTimeline.Group group) {
        return group.startMs - ACTIVE_FADE_IN_MS + ACTIVE_FADE_IN_DELAY_MS;
    }

    /**
     * Cloud-layout occupancy of the centre stage. Only one main line sits there:
     * the outgoing line flies home, then the incoming line flies in. Column
     * layout still uses {@link #active}.
     */
    static float cloudFly(long positionMs, LyricTimeline.Group group, LyricTimeline.Group next) {
        if (group == null) return 0f;
        long inStart = group.startMs;
        long inEnd = inStart + CLOUD_FLY_MS;
        long outEnd = group.endMs + CLOUD_FLY_MS;
        if (next != null) outEnd = Math.min(outEnd, next.startMs);
        long outStart = Math.max(inStart, outEnd - CLOUD_FLY_MS);
        if (inEnd > outStart) {
            long mid = inStart + Math.max(1L, outEnd - inStart) / 2;
            inEnd = mid;
            outStart = mid;
        }
        if (positionMs < inStart) return 0f;
        if (positionMs < inEnd) {
            return smoothstep(0f, 1f, (positionMs - inStart) / (float) Math.max(1L, inEnd - inStart));
        }
        if (positionMs < outStart) return 1f;
        if (positionMs >= outEnd) return 0f;
        return 1f - smoothstep(0f, 1f, (positionMs - outStart) / (float) Math.max(1L, outEnd - outStart));
    }

    static float backgroundScale(long positionMs, LyricTimeline.Group group) {
        long popStart = group.startMs + BACKGROUND_POP_IN_DELAY_MS;
        if (positionMs < popStart) return 0f;
        if (positionMs < popStart + BACKGROUND_POP_IN_MS) {
            float progress = (positionMs - popStart) / (float) BACKGROUND_POP_IN_MS;
            return easeOutBack(progress);
        }
        if (positionMs < group.endMs) return 1f;
        float elapsed = (positionMs - group.endMs) / (float) BACKGROUND_POP_OUT_MS;
        if (elapsed >= 1f) return 0f;
        return 1f - smoothstep(0f, 1f, elapsed);
    }

    static float interludeSlot(long positionMs, long startMs, long endMs, float height) {
        final long rampMs = 150L;
        if (positionMs < startMs || positionMs > endMs) return 0f;
        long elapsed = positionMs - startMs;
        float progress;
        if (elapsed < rampMs) {
            progress = elapsed / (float) rampMs;
        } else if (endMs - positionMs < rampMs) {
            progress = (endMs - positionMs) / (float) rampMs;
        } else {
            progress = 1f;
        }
        return height * smoothstep(0f, 1f, progress);
    }

    static float smoothstep(float minimum, float maximum, float value) {
        if (maximum <= minimum) return value < minimum ? 0f : 1f;
        float progress = (value - minimum) / (maximum - minimum);
        if (progress <= 0f) return 0f;
        if (progress >= 1f) return 1f;
        return progress * progress * (3f - 2f * progress);
    }

    private static float easeOutBack(float value) {
        float c1 = 1.7f;
        float c3 = c1 + 1f;
        float shifted = value - 1f;
        return 1f + c3 * shifted * shifted * shifted + c1 * shifted * shifted;
    }
}
