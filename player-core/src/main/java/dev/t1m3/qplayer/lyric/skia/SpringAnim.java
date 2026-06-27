package dev.t1m3.qplayer.lyric.skia;

/**
 * Damped harmonic-oscillator animation. Unlike Common Animation, which
 * eases over a fixed duration from a restart, a spring carries velocity
 * across target changes — re-targets mid-flight blend smoothly into the
 * new motion instead of snapping back to rest first. That carry-over is
 * the visual signature of Apple Music's lyric scroller: when the user
 * skips ahead during a fade, the new line glides in with the lingering
 * momentum of the previous transition.
 *
 * <p>Equation of motion (per integration step):
 * <pre>a = -stiffness · (value - target) - damping · velocity</pre>
 * Default tuning (stiffness=180, damping=22) settles in ~500ms with a
 * faint over-damped overshoot — comparable to UIKit's
 * {@code UISpringTimingParameters} with a critical-damping-ratio &lt; 1.
 */
public class SpringAnim {

    private double stiffness;
    private double damping;
    private double value;
    private double velocity;
    private double target;
    private long lastNs = System.nanoTime();

    public SpringAnim(double stiffness, double damping) {
        this.stiffness = stiffness;
        this.damping = damping;
    }

    /** Retune mid-flight (e.g. a settings toggle) without snapping; the spring
     *  carries its current value/velocity into the new stiffness/damping. */
    public void setParams(double stiffness, double damping) {
        this.stiffness = stiffness;
        this.damping = damping;
    }

    public SpringAnim() {
        this(180.0, 22.0);
    }

    /**
     * Step the spring toward {@code newTarget} using real elapsed time
     * since the previous call. Safe to call every render frame.
     */
    public double animate(double newTarget) {
        if (!Double.isFinite(newTarget)) {
            return Double.isFinite(value) ? value : 0.0;
        }
        this.target = newTarget;

        long now = System.nanoTime();
        double dt = (now - lastNs) / 1_000_000_000.0;
        lastNs = now;
        // After a pause (window minimised, GC stall, etc.) dt can be
        // huge; integrating in one step would diverge for stiff springs.
        // Cap and sub-step.
        if (dt > 0.05) dt = 0.05;
        if (dt <= 0.0) return value;

        int steps = 1 + (int) (dt / 0.008);
        double sub = dt / steps;
        for (int i = 0; i < steps; i++) {
            double a = -stiffness * (value - target) - damping * velocity;
            velocity += a * sub;
            value += velocity * sub;
        }

        // Snap to rest below a sub-pixel threshold so we don't keep
        // animating forever at imperceptible amplitudes.
        if (Math.abs(velocity) < 0.01 && Math.abs(value - target) < 0.05) {
            value = target;
            velocity = 0.0;
        }
        return value;
    }

    /** Jump immediately to {@code v}, killing any in-flight velocity. */
    public void setValue(double v) {
        this.value = v;
        this.velocity = 0.0;
        this.target = v;
        this.lastNs = System.nanoTime();
    }

    public double getValue() {
        return value;
    }

    /** Re-seed the spring at the current target with zero velocity. */
    public void reset() {
        setValue(target);
    }
}
