package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LyricTimeline;

import java.util.List;

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
    /** A group whose successor starts within this of its end is followed in turn. */
    private static final long OVERLAP_TOLERANCE_MS = 100L;
    /**
     * The same for a group without background lines. Handing over early only moves
     * the view on (its highlight keeps its own timing), so a last syllable that
     * still rings under the next line's start doesn't hold the view back.
     */
    private static final long MAIN_ONLY_OVERLAP_TOLERANCE_MS = 500L;
    /** Shortest word that carries the ribbon and glow. */
    static final long RIBBON_MIN_MS = 1000L;
    private static final long RIBBON_RELEASE_MS = 450L;

    private LyricMotion() {}

    static float active(long positionMs, LyricTimeline.Group group) {
        long fadeInStart = fadeInStart(group);
        long fadeInEnd = fadeInStart + ACTIVE_FADE_IN_MS;
        if (positionMs < fadeInStart) return 0f;
        if (positionMs < fadeInEnd) {
            return smoothstep(0f, 1f, (positionMs - fadeInStart) / (float) ACTIVE_FADE_IN_MS);
        }
        long fadeOutStart = group.endMs + ACTIVE_FADE_OUT_DELAY_MS;
        if (positionMs < fadeOutStart) return 1f;
        return 1f - smoothstep(0f, 1f, (positionMs - fadeOutStart) / (float) ACTIVE_FADE_OUT_MS);
    }

    /** When a group takes focus: its first visible brightening, ahead of its start. */
    static long fadeInStart(LyricTimeline.Group group) {
        return group.startMs - ACTIVE_FADE_IN_MS + ACTIVE_FADE_IN_DELAY_MS;
    }

    /**
     * When each group gives up focus, its background slot and its claim on the
     * viewport. Normally just after it ends; but as soon as a later group that does
     * not overlap it takes focus, it hands over then, so the scroll toward the next
     * line and the collapse of this group's background slot are one event. Groups
     * that overlap their successors (duets, echoes) keep focus until they end.
     */
    static long[] releaseTimes(List<LyricTimeline.Group> groups) {
        long[] release = new long[groups.size()];
        for (int g = 0; g < groups.size(); g++) {
            LyricTimeline.Group group = groups.get(g);
            long at = group.endMs + ACTIVE_FADE_OUT_DELAY_MS;
            long tolerance = group.to - group.from > 1
                    ? OVERLAP_TOLERANCE_MS : MAIN_ONLY_OVERLAP_TOLERANCE_MS;
            for (int h = g + 1; h < groups.size(); h++) {
                if (groups.get(h).startMs >= group.endMs - tolerance) {
                    at = Math.min(at, fadeInStart(groups.get(h)));
                    break;
                }
            }
            release[g] = Math.max(at, fadeInStart(group));
        }
        return release;
    }

    /** Whether a group holds focus: it has taken it and not yet released it. */
    static boolean focused(long positionMs, LyricTimeline.Group group, long releaseMs) {
        return positionMs >= fadeInStart(group) && positionMs < releaseMs;
    }

    /**
     * Background-vocal text presence, 0..1: pops in (with a slight overshoot) as its
     * slot opens with the group's focus, and shrinks away as the slot is reclaimed.
     */
    static float backgroundPresence(long positionMs, LyricTimeline.Group group, long releaseMs) {
        long popStart = fadeInStart(group) + BACKGROUND_POP_IN_DELAY_MS;
        if (positionMs < popStart) return 0f;
        float in = positionMs >= popStart + BACKGROUND_POP_IN_MS ? 1f
                : easeOutBack((positionMs - popStart) / (float) BACKGROUND_POP_IN_MS);
        if (positionMs < releaseMs) return in;
        float out = (positionMs - releaseMs) / (float) BACKGROUND_POP_OUT_MS;
        return in * (1f - smoothstep(0f, 1f, out));
    }

    /**
     * Ribbon amplitude of a held word, 0..1: rises as the word starts, holds while
     * it is sung and settles after it ends.
     */
    static float ribbonEnvelope(long positionMs, long wordStartMs, long wordEndMs) {
        long duration = wordEndMs - wordStartMs;
        if (duration < RIBBON_MIN_MS || positionMs <= wordStartMs) return 0f;
        float attack = Math.max(180f, Math.min(420f, duration * 0.3f));
        float rise = Math.min(1f, (positionMs - wordStartMs) / attack);
        float inverse = 1f - rise;
        float up = 1f - inverse * inverse * inverse;
        if (positionMs <= wordEndMs) return up;
        float release = (positionMs - wordEndMs) / (float) RIBBON_RELEASE_MS;
        return up * (1f - smoothstep(0f, 1f, release));
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
