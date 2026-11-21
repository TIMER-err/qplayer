package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricTimeline;

import java.util.List;

/** Spring/cascade rules used by the LyricBlossom motion mode. */
public final class LyricSprings {
    /** Advance the visual anchor before the next line's timestamp. */
    public static final double ANTICIPATION_S = 0.71;
    private static final double CASCADE_STEP_S = 0.05;
    private static final double TAU = Math.PI * 2;
    private static final double LOG_ONE_PERCENT = -Math.log(0.01);

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

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
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

    public static Physics responsePhysics(double dampingRatio, double response, double mass) {
        double omega = TAU / Math.max(response, 0.001);
        return new Physics(mass, 2 * dampingRatio * mass * omega, mass * omega * omega);
    }

    public static Physics gapDrivenLineSpring(double interLineGap) {
        double amount = clamp((interLineGap - 0.20) / 0.55, 0, 1);
        return responsePhysics(lerp(0.90, 0.78, amount), lerp(0.48, 0.75, amount), 1);
    }

    public static Physics lineTransitionSpring(boolean hasTimedSyllables, double interLineGap) {
        return hasTimedSyllables
                ? gapDrivenLineSpring(interLineGap)
                : new Physics(1, 18, 100);
    }

    public static Physics criticalEnvelopeSpring(double mass, double response) {
        double rate = LOG_ONE_PERCENT / Math.max(response, 0.01);
        return new Physics(mass, 2 * mass * rate, mass * rate * rate);
    }

    public static Physics retimeLineSpring(Physics physics, double lineEnd, double playhead, boolean continuous) {
        if (continuous) return criticalEnvelopeSpring(physics.mass, 0.30);
        double remaining = lineEnd - playhead - 0.50;
        double naturalRate = Math.sqrt(physics.stiffness / physics.mass);
        double dampingRatio = physics.damping / (2 * Math.sqrt(physics.mass * physics.stiffness));
        double oldEnvelope = LOG_ONE_PERCENT / (naturalRate * clamp(dampingRatio, 0.10, 1));
        if (remaining < 0.80 && oldEnvelope - remaining < -0.05) {
            double response = Math.max(0.01, Math.max(remaining - 0.40, 0.30));
            return criticalEnvelopeSpring(physics.mass, response);
        }
        return physics;
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
