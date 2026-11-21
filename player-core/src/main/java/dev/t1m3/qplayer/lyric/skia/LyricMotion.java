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

    // ── 逐字放大（照搬 Melodify MusicPlayerGui.emphasizeAmount / emphasizeK）──────────
    // 只对拉丁词生效（Melodify 注释：non-Latin text…so CJK/Thai/etc. don't bulge），
    // 幅度按词时长取幂曲线、最后一个词更狠、上限 1.2；每字再叠一个 AMLL 的错开脉冲，
    // 于是鼓包从左往右扫过整个词。

    /** 每个词的放大幅度 [0,1.2]（Melodify emphasizeAmount，原样照搬）。 */
    static float emphasisAmount(long durationMs, int indexInLine, int lineCount) {
        float du = Math.max(1000f, durationMs);
        float amount = du / 2000f;
        amount = amount > 1f ? (float) Math.sqrt(amount) : amount * amount * amount;
        amount *= 0.6f;
        if (indexInLine == lineCount - 1) amount *= 1.6f;   // 最后一个词鼓得更狠（AMLL）
        return Math.min(1.2f, amount);
    }

    /**
     * 词内第 {@code ci} 个字的脉冲进度（Melodify emphasizeK）：AMLL 把每字错开
     * {@code du/2.5/len}，于是鼓包从左往右扫；脉冲本身是 empEasing。
     */
    static float emphasisSwell(long positionMs, long startMs, long durationMs, int ci, int len) {
        float effDu = Math.max(1000f, durationMs);
        float delay = (effDu / 2.5f / Math.max(1, len)) * ci;
        float t = (positionMs - startMs - delay) / effDu;
        return (float) empEasing(t);
    }

    /** AMLL makeEmpEasing(0.5)：升到中点再落回（两段 bezier 拼成）。 */
    private static double empEasing(double x) {
        if (x <= 0.0) return 0.0;
        if (x >= 1.0) return 0.0;
        if (x < 0.5) return cssBezier(x / 0.5, 0.2, 0.4, 0.58, 1.0);
        return 1.0 - cssBezier((x - 0.5) / 0.5, 0.3, 0.0, 0.58, 1.0);
    }

    /** CSS cubic-bezier(x1,y1,x2,y2)，牛顿解参数后取 y（与 Melodify cssBezier 同法）。 */
    private static double cssBezier(double p, double x1, double y1, double x2, double y2) {
        if (p <= 0.0) return 0.0;
        if (p >= 1.0) return 1.0;
        double s = p;
        for (int i = 0; i < 8; i++) {
            double bx = bezierAxis(x1, x2, s);
            double d = bezierDeriv(x1, x2, s);
            if (Math.abs(d) < 1e-6) break;
            s -= (bx - p) / d;
            s = Math.max(0.0, Math.min(1.0, s));
        }
        return bezierAxis(y1, y2, s);
    }

    /** 三次贝塞尔某一轴取值（端点固定 0 与 1）。 */
    private static double bezierAxis(double c1, double c2, double s) {
        double u = 1.0 - s;
        return 3.0 * u * u * s * c1 + 3.0 * u * s * s * c2 + s * s * s;
    }

    private static double bezierDeriv(double c1, double c2, double s) {
        double u = 1.0 - s;
        return 3.0 * u * u * c1 + 6.0 * u * s * (c2 - c1) + 3.0 * s * s * (1.0 - c2);
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
