package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricTimeline;

import java.util.List;

/** Spring/cascade rules used by the LyricBlossom motion mode. */
public final class LyricSprings {
    /** Advance the visual anchor before the next line's timestamp. */
    public static final double ANTICIPATION_S = 0.71;
    private static final double CASCADE_STEP_S = 0.05;

    private LyricSprings() {}

    public static final class Physics {
        public final double mass;
        public final double damping;
        public final double stiffness;

        Physics(double mass, double damping, double stiffness) {
            this.mass = mass;
            this.damping = damping;
            this.stiffness = stiffness;
        }
    }

    public static boolean isHardClockDiscontinuity(double previous, double current) {
        double delta = current - previous;
        return delta < -0.08 || delta > 0.35;
    }

    public static Physics seekSpring(int lineDistance, double deltaSeconds) {
        if (Math.abs(lineDistance) <= 1 && Math.abs(deltaSeconds) < 1.0) {
            return criticalSpring(2.0, 0.10);
        }
        return new Physics(1.0, 18.0, 100.0);
    }

    public static Physics lineTransitionSpring(boolean perToken, double gapSeconds) {
        double gap = Math.max(0.0, Math.min(2.0, gapSeconds));
        double stiffness = perToken ? 90.0 : 75.0;
        stiffness -= gap * 10.0;
        double damping = 2.0 * 0.82 * Math.sqrt(stiffness);
        return new Physics(1.0, damping, stiffness);
    }

    public static Physics retimeLineSpring(Physics base, double lineEndSeconds,
                                            double positionSeconds, boolean continuous) {
        double remaining = lineEndSeconds - positionSeconds - 0.5;
        if (!continuous || remaining >= 0.6) return base;
        double ratio = Math.max(0.0, Math.min(1.0, (0.6 - remaining) / 0.6));
        double stiffness = base.stiffness + ratio * 55.0;
        double damping = 2.0 * 0.86 * Math.sqrt(stiffness * base.mass);
        return new Physics(base.mass, damping, stiffness);
    }

    public static int validLineDistance(List<LyricLine> lines, int from, int to) {
        if (lines == null || lines.isEmpty() || from == to) return 0;
        int lower = Math.max(0, Math.min(from, to));
        int upper = Math.min(lines.size() - 1, Math.max(from, to));
        int distance = 0;
        for (int i = lower + 1; i <= upper; i++) {
            if (!LyricTimeline.isBackground(lines.get(i).vocalChannel)) distance++;
        }
        return distance;
    }

    public static double cascadeDelay(int validDistance, boolean disabled) {
        return disabled ? 0.0 : Math.max(validDistance - 1, 0) * CASCADE_STEP_S;
    }

    private static Physics criticalSpring(double mass, double settlingSeconds) {
        double omega = 4.6 / Math.max(0.05, settlingSeconds);
        double stiffness = mass * omega * omega;
        double damping = 2.0 * mass * omega;
        return new Physics(mass, damping, stiffness);
    }
}
