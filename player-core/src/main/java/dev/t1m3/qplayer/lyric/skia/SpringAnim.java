package dev.t1m3.qplayer.lyric.skia;

/**
 * Spring animation — closed-form analytic solver ported from AMLL
 * (applemusic-like-lyrics, packages/core/src/utils/spring.ts, MIT).
 *
 * <p>Uses the exact analytic solution rather than per-frame numeric integration.
 * The underdamped branch (ζ &lt; 1) produces the slight overshoot that gives
 * Apple Music's lyric lines their signature bounce. Velocity is sampled and
 * carried across retargets so an interrupted animation keeps its momentum.
 */
public class SpringAnim {

    /** AMLL posYSpringParams — drives lyric line float-up. ζ ≈ 0.833. */
    public static final double DEFAULT_MASS      = 0.9;
    public static final double DEFAULT_DAMPING   = 15.0;
    public static final double DEFAULT_STIFFNESS = 90.0;

    private static final double H   = 0.001; // numeric-derivative step
    private static final double EPS = 0.01;  // arrival epsilon

    private interface Solver { double at(double t); }

    private double mass, damping, stiffness;
    private boolean soft = false;

    private double currentPosition;
    private double targetPosition;
    private double currentTime = 0.0;
    private Solver solver;

    private boolean hasQueuedPosition = false;
    private double  queuedPosition     = 0.0;
    private double  queuedPositionTime = 0.0;

    private boolean hasQueuedParams    = false;
    private double  queuedMass, queuedDamping, queuedStiffness;
    private double  queuedParamsTime   = 0.0;

    private long lastNs = System.nanoTime();

    public SpringAnim() {
        this(DEFAULT_MASS, DEFAULT_DAMPING, DEFAULT_STIFFNESS);
    }

    /** Legacy 2-arg form (stiffness, damping); mass assumed 1.0. */
    public SpringAnim(double stiffness, double damping) {
        this(1.0, damping, stiffness);
    }

    public SpringAnim(double mass, double damping, double stiffness) {
        this.mass      = mass;
        this.damping   = damping;
        this.stiffness = stiffness;
        this.currentPosition = 0.0;
        this.targetPosition  = 0.0;
        this.solver = t -> 0.0;
    }

    // ── Params ────────────────────────────────────────────────────────────────

    /** Legacy 2-arg setParams kept for existing call sites. */
    public void setParams(double stiffness, double damping) {
        setParams(this.mass, damping, stiffness, this.soft, 0.0);
    }

    public void setParams(double mass, double damping, double stiffness) {
        setParams(mass, damping, stiffness, this.soft, 0.0);
    }

    public void setParams(double mass, double damping, double stiffness,
                          boolean soft, double delay) {
        if (delay > 0.0) {
            hasQueuedParams    = true;
            queuedMass         = mass;
            queuedDamping      = damping;
            queuedStiffness    = stiffness;
            queuedParamsTime   = delay;
            return;
        }
        hasQueuedPosition = false;
        this.mass      = mass;
        this.damping   = damping;
        this.stiffness = stiffness;
        this.soft      = soft;
        resetSolver();
    }

    // ── Targets ───────────────────────────────────────────────────────────────

    public void setTargetPosition(double target, double delay) {
        if (delay > 0.0) {
            hasQueuedPosition  = true;
            queuedPosition     = target;
            queuedPositionTime = delay;
            return;
        }
        hasQueuedPosition   = false;
        this.targetPosition = target;
        resetSolver();
    }

    public void setTargetPosition(double target) {
        setTargetPosition(target, 0.0);
    }

    /** Hard snap — kills velocity and queues. */
    public void setValue(double v) {
        this.targetPosition    = v;
        this.currentPosition   = v;
        this.currentTime       = 0.0;
        this.hasQueuedPosition = false;
        this.hasQueuedParams   = false;
        final double snap = v;
        this.solver = t -> snap;
        this.lastNs = System.nanoTime();
    }

    public double getValue()          { return currentPosition; }
    public double getTargetPosition() { return targetPosition; }
    public void   reset()             { setValue(targetPosition); }
    public double getVelocity()       { return velocityAt(currentTime); }

    /** True while a delayed target is still queued (not yet started). Retargeting
     *  during this window would discard the pending delay — callers use this to keep
     *  cascade delays intact across frames (Melodify scaleSpring sync). */
    public boolean hasPendingTarget() {
        return hasQueuedPosition;
    }

    public boolean arrived() {
        return Math.abs(targetPosition - currentPosition) < EPS
                && Math.abs(velocityAt(currentTime))     < EPS
                && Math.abs(accelerationAt(currentTime)) < EPS
                && !hasQueuedParams
                && !hasQueuedPosition;
    }

    // ── Stepping ──────────────────────────────────────────────────────────────

    /** Advance by an explicit delta in seconds (caller owns the clock). */
    public void update(double delta) {
        currentTime     += delta;
        currentPosition  = solver.at(currentTime);

        if (hasQueuedParams) {
            queuedParamsTime -= delta;
            if (queuedParamsTime <= 0.0) {
                hasQueuedParams = false;
                setParams(queuedMass, queuedDamping, queuedStiffness, soft, 0.0);
            }
        }
        if (hasQueuedPosition) {
            queuedPositionTime -= delta;
            if (queuedPositionTime <= 0.0) {
                hasQueuedPosition = false;
                setTargetPosition(queuedPosition, 0.0);
            }
        }
        if (arrived()) setValue(targetPosition);
    }

    /** Retarget then step using an internal wall clock. */
    public double animate(double newTarget) {
        if (!Double.isFinite(newTarget)) {
            return Double.isFinite(currentPosition) ? currentPosition : 0.0;
        }
        if (newTarget != targetPosition && !hasQueuedPosition) {
            setTargetPosition(newTarget, 0.0);
        }
        return animate();
    }

    public double animate() {
        long now = System.nanoTime();
        double dt = (now - lastNs) / 1_000_000_000.0;
        lastNs = now;
        if (dt > 0.05) dt = 0.05;
        if (dt <= 0.0) return currentPosition;
        update(dt);
        return currentPosition;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void resetSolver() {
        double curV = velocityAt(currentTime);
        currentTime = 0.0;
        solver = solveSpring(currentPosition, curV, targetPosition,
                0.0, mass, damping, stiffness, soft);
    }

    private double velocityAt(double t) {
        return (solver.at(t + H) - solver.at(t - H)) / (2.0 * H);
    }

    private double accelerationAt(double t) {
        return (velocityAt(t + H) - velocityAt(t - H)) / (2.0 * H);
    }

    private static Solver solveSpring(double from, double velocity, double to,
                                      double delay, double mass, double damping,
                                      double stiffness, boolean soft) {
        final double delta = to - from;
        // Overdamped / critically damped: pure exponential decay, no overshoot.
        if (soft || 1.0 <= damping / (2.0 * Math.sqrt(stiffness * mass))) {
            final double w = -Math.sqrt(stiffness / mass);
            final double leftover = -w * delta - velocity;
            return t -> {
                double tt = t - delay;
                if (tt < 0.0) return from;
                return to - (delta + tt * leftover) * Math.exp(tt * w);
            };
        }
        // Underdamped: oscillation inside an exponential envelope → the bounce.
        final double df  = Math.sqrt(4.0 * mass * stiffness - damping * damping);
        final double lo  = (damping * delta - 2.0 * mass * velocity) / df;
        final double dfm = (0.5 * df) / mass;
        final double dm  = -(0.5 * damping) / mass;
        return t -> {
            double tt = t - delay;
            if (tt < 0.0) return from;
            return to - (Math.cos(tt * dfm) * delta + Math.sin(tt * dfm) * lo)
                    * Math.exp(tt * dm);
        };
    }
}
