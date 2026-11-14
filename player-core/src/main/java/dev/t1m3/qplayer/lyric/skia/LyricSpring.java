package dev.t1m3.qplayer.lyric.skia;

/**
 * Damped harmonic spring with unit mass, advanced by its exact closed-form
 * solution instead of numeric integration. A step of {@code 2 dt} lands exactly
 * where two steps of {@code dt} do, so the motion is the same at any frame rate,
 * and velocity carries over when the target moves mid-flight.
 */
final class LyricSpring {

    private static final float SETTLE_DISTANCE = 0.05f;
    private static final float SETTLE_VELOCITY = 0.5f;

    private final double omega;
    private final double zeta;

    LyricSpring(double stiffness, double damping) {
        omega = Math.sqrt(stiffness);
        zeta = damping / (2.0 * omega);
    }

    static LyricSpring critical(double stiffness) {
        return new LyricSpring(stiffness, 2.0 * Math.sqrt(stiffness));
    }

    /** Advance {@code position[i]} / {@code velocity[i]} toward {@code target} by {@code dt} seconds. */
    void step(float[] position, float[] velocity, int i, float target, double dt) {
        double x = position[i] - target;
        double v = velocity[i];
        if (dt > 0.0) {
            double nextX;
            double nextV;
            if (zeta < 1.0) {
                double damped = omega * Math.sqrt(1.0 - zeta * zeta);
                double decay = Math.exp(-zeta * omega * dt);
                double cos = Math.cos(damped * dt);
                double sin = Math.sin(damped * dt);
                nextX = decay * (x * cos + (v + zeta * omega * x) / damped * sin);
                nextV = decay * (v * cos - (zeta * omega * v + omega * omega * x) / damped * sin);
            } else if (zeta == 1.0) {
                double decay = Math.exp(-omega * dt);
                double c = v + omega * x;
                nextX = (x + c * dt) * decay;
                nextV = (v - omega * c * dt) * decay;
            } else {
                double root = Math.sqrt(zeta * zeta - 1.0);
                double r1 = -omega * (zeta - root);
                double r2 = -omega * (zeta + root);
                double a = (v - r2 * x) / (r1 - r2);
                double b = x - a;
                double e1 = Math.exp(r1 * dt);
                double e2 = Math.exp(r2 * dt);
                nextX = a * e1 + b * e2;
                nextV = a * r1 * e1 + b * r2 * e2;
            }
            x = nextX;
            v = nextV;
        }
        if (Math.abs(x) < SETTLE_DISTANCE && Math.abs(v) < SETTLE_VELOCITY) {
            x = 0.0;
            v = 0.0;
        }
        position[i] = (float) (target + x);
        velocity[i] = (float) v;
    }
}
