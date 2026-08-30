package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LyricTimeline;

/** Playback-time curves shared by lyric renderers. */
final class LyricMotion {

    private static final long ACTIVE_FADE_OUT_MS = 350L;
    private static final long ACTIVE_FADE_OUT_DELAY_MS = 100L;
    private static final long ACTIVE_FADE_IN_MS = 600L;
    private static final long ACTIVE_FADE_IN_DELAY_MS = 150L;
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

    static float backgroundScale(long positionMs, LyricTimeline.Group group) {
        long popStart = group.startMs + BACKGROUND_POP_IN_DELAY_MS;
        if (positionMs < popStart) return 0f;
        if (positionMs < popStart + BACKGROUND_POP_IN_MS) {
            float progress = (positionMs - popStart) / (float) BACKGROUND_POP_IN_MS;
            // 过阻尼（临界阻尼）弹簧阶跃响应——起步快、收敛无过冲，与 Melodify 的
            // 和声行滑入一致（替代原 easeOutBack 的欠阻尼回弹）。
            return overdampedSpring(progress);
        }
        if (positionMs < group.endMs) return 1f;
        float elapsed = (positionMs - group.endMs) / (float) BACKGROUND_POP_OUT_MS;
        if (elapsed >= 1f) return 0f;
        return 1f - smoothstep(0f, 1f, elapsed);
    }

    /** 临界阻尼弹簧阶跃响应 1-(1+ωt)·e^(-ωt)：无过冲，起步最快、尾部缓收。
     *  ω=8 使 460ms 出场窗口结束时收敛到 ~99.7%。 */
    private static float overdampedSpring(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        double x = 8.0 * t;
        return (float) (1.0 - (1.0 + x) * Math.exp(-x));
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
}
