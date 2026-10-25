// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
package dev.t1m3.qplayer.lyric.skia;

/** Render-thread port of miuix DampedDragAnimation; units are dp and tab indices. */
final class LiquidGlassMotion {
    final Spring position = new Spring(1, 1000, 0.001);
    final Spring press = new Spring(1, 1000, 0.001);
    final Spring scaleX = new Spring(0.6, 250, 0.001);
    final Spring scaleY = new Spring(0.7, 250, 0.001);
    final Spring velocity = new Spring(0.5, 300, 0.01);
    final Spring panel = new Spring(1, 300, 0.5);
    final Spring spot = new Spring(0.5, 300, 0.001);
    private long previousNs;
    private boolean initialized;
    private boolean down;
    private boolean releasing;
    private double lastTarget;
    private LiquidGlassLsq2Tracker velocityTracker;
    private boolean trackVelocity;

    void reset(int selectedIndex) {
        position.snap(selectedIndex);
        press.snap(0);
        scaleX.snap(1);
        scaleY.snap(1);
        velocity.snap(0);
        panel.snap(0);
        spot.snap(0);
        lastTarget = selectedIndex;
        previousNs = 0;
        initialized = true;
        down = false;
        releasing = false;
        if (velocityTracker == null) velocityTracker = new LiquidGlassLsq2Tracker();
        else velocityTracker.reset();
        trackVelocity = false;
    }

    void advance(long nowNs, int selectedIndex, int count, boolean pressed,
                 double targetIndex, double dragPixels) {
        if (!initialized) reset(selectedIndex);
        double dt = previousNs == 0 ? 0 : Math.max(0, (nowNs - previousNs) * 1e-9);
        previousNs = nowNs;
        // Advance the previous animation before retargeting. In particular, a
        // gesture after a long idle must start now, not consume the idle interval.
        boolean positionMoving = position.moving();
        position.step(dt);
        press.step(dt);
        scaleX.step(dt);
        scaleY.step(dt);
        panel.step(dt);
        spot.step(dt);
        if (trackVelocity && positionMoving) {
            float sampled = velocityTracker.addPosition(nowNs / 1_000_000, (float) position.value);
            velocity.snap(sampled / Math.max(1, count - 1));
        } else {
            velocity.step(dt);
        }
        boolean releaseStarted = false;
        if (pressed && !down) {
            beginPress(true);
            trackVelocity = true;
        } else if (!pressed && down) {
            releasing = true;
            releaseStarted = true;
            panel.target = 0;
            spot.target = 0;
        } else if (!pressed && targetIndex != lastTarget && !releasing) {
            beginPress(false);
            trackVelocity = false;
            velocity.target = 0;
            releasing = true;
            releaseStarted = true;
        }
        down = pressed;
        lastTarget = targetIndex;
        position.target = Math.max(0, Math.min(count - 1, targetIndex));
        if (pressed) panel.snap(dragPixels);
        if (releasing && !releaseStarted
                && Math.abs(position.value - position.target) < Math.max(0.001, (count - 1) * 0.025)) {
            press.target = 0;
            scaleX.target = 1;
            scaleY.target = 1;
            releasing = false;
        }
    }

    private void beginPress(boolean pointer) {
        press.target = 1;
        scaleX.target = 78.0 / 56.0;
        scaleY.target = 78.0 / 56.0;
        spot.target = pointer ? 1 : 0;
        releasing = false;
        velocityTracker.reset();
    }

    boolean isAnimating() {
        return releasing || position.moving() || press.moving()
                || scaleX.moving() || scaleY.moving() || velocity.moving()
                || panel.moving() || spot.moving();
    }

    /** Closed-form unit-mass damped spring, matching Compose's stiffness/damping units. */
    static final class Spring {
        private final double damping;
        private final double omega;
        private final double threshold;
        double value;
        double target;
        double speed;

        Spring(double damping, double stiffness, double threshold) {
            this.damping = damping;
            this.omega = Math.sqrt(stiffness);
            this.threshold = threshold;
        }

        void snap(double value) {
            this.value = value;
            target = value;
            speed = 0;
        }

        boolean moving() {
            return Math.abs(value - target) >= threshold || Math.abs(speed) >= threshold * 62.5;
        }

        void step(double dt) {
            if (dt <= 0) return;
            if (!moving()) {
                value = target;
                speed = 0;
                return;
            }
            double displacement = value - target;
            double decay = Math.exp(-damping * omega * dt);
            if (damping == 1) {
                double c = speed + omega * displacement;
                value = target + (displacement + c * dt) * decay;
                speed = (speed - omega * c * dt) * decay;
            } else {
                double frequency = omega * Math.sqrt(1 - damping * damping);
                double c = (speed + damping * omega * displacement) / frequency;
                double cos = Math.cos(frequency * dt);
                double sin = Math.sin(frequency * dt);
                double y = displacement * cos + c * sin;
                value = target + decay * y;
                speed = decay * (-damping * omega * y
                        + frequency * (-displacement * sin + c * cos));
            }
        }
    }
}
