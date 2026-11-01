package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LyricLine;
import io.github.timer_err.qml4j.render.PointerVelocityTracker;

import java.util.List;

/**
 * User-controlled lyric scrolling: drag, fling, wheel, idle hold, and return.
 *
 * <p>The controller uses the same content-space offset as the renderer but owns
 * all gesture state. Hosts can therefore share the interaction behavior without
 * depending on Skia drawing code.
 */
final class LyricScrollController {

    private static final double FRICTION = 4.2;
    private static final float STOP_VELOCITY = 5f;
    private static final float MIN_FLING_VELOCITY = 60f;
    private static final long IDLE_RETURN_NS = 4_000_000_000L;
    private static final float WHEEL_STEP_PX = 48f;

    private boolean active;
    private boolean dragging;
    private boolean flinging;
    private boolean returning;
    private float offset;
    private float flingVelocity;
    private long lastStepNs;
    private boolean clockStarted;
    private boolean interactionPending;
    private long lastInteractionNs;
    private int holdAnchor = Integer.MIN_VALUE;
    private int previousAnchor = Integer.MIN_VALUE;
    private float lastRenderedOffset;
    private float centerY;
    private float minimum;
    private float maximum;
    private final PointerVelocityTracker velocity = new PointerVelocityTracker();
    private float lastPointerY;

    boolean isActive() {
        return active;
    }

    float lastRenderedOffset() {
        return lastRenderedOffset;
    }

    void setLastRenderedOffset(float value) {
        lastRenderedOffset = value;
    }

    int previousAnchor() {
        return previousAnchor;
    }

    void setPreviousAnchor(int value) {
        previousAnchor = value;
    }

    void setViewport(float centerY, float minimum, float maximum) {
        this.centerY = centerY;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    float clamp(float value) {
        if (value < minimum) return minimum;
        return Math.min(value, maximum);
    }

    void reset() {
        cancel();
        previousAnchor = Integer.MIN_VALUE;
        holdAnchor = Integer.MIN_VALUE;
    }

    void cancel() {
        active = false;
        dragging = false;
        flinging = false;
        returning = false;
        flingVelocity = 0f;
        velocity.clear();
        clockStarted = false;
        interactionPending = false;
        holdAnchor = Integer.MIN_VALUE;
    }

    void pointerDown(float y, long eventTimeNanos) {
        active = true;
        dragging = true;
        flinging = false;
        returning = false;
        offset = clamp(lastRenderedOffset);
        flingVelocity = 0f;
        velocity.reset(0f, y, eventTimeNanos);
        lastPointerY = y;
        clockStarted = false;
        interactionPending = true;
    }

    void pointerMove(float y, long eventTimeNanos) {
        if (!dragging) return;
        offset = clamp(offset - (y - lastPointerY));
        lastPointerY = y;
        velocity.add(0f, y, eventTimeNanos);
        interactionPending = true;
    }

    void pointerUp(long eventTimeNanos) {
        if (!dragging) return;
        velocity.release(eventTimeNanos);
        dragging = false;
        flingVelocity = -velocity.velocityY();
        flinging = Math.abs(flingVelocity) > MIN_FLING_VELOCITY;
        clockStarted = false;
        interactionPending = true;
    }

    /** Cancel the gesture without flinging or discarding the user's held position. */
    void pointerCancel() {
        if (returning) offset = clamp(lastRenderedOffset);
        returning = false;
        dragging = false;
        flinging = false;
        flingVelocity = 0f;
        velocity.clear();
        clockStarted = false;
        interactionPending = true;
    }

    void wheel(float notches) {
        if (!active) offset = clamp(lastRenderedOffset);
        active = true;
        dragging = false;
        flinging = false;
        returning = false;
        offset = clamp(offset - notches * WHEEL_STEP_PX);
        velocity.clear();
        clockStarted = false;
        interactionPending = true;
    }

    float step(float targetOffset, long nowNs, int anchorIndex, SpringAnim returnSpring) {
        if (interactionPending) {
            lastInteractionNs = nowNs;
            interactionPending = false;
        }
        if (dragging) {
            holdAnchor = anchorIndex;
            lastStepNs = nowNs;
            return offset;
        }
        if (flinging) {
            holdAnchor = anchorIndex;
            if (!clockStarted) {
                lastStepNs = nowNs;
                clockStarted = true;
                return offset;
            }
            if (nowNs <= lastStepNs) return offset;
            double dt = (nowNs - lastStepNs) / 1_000_000_000d;
            lastStepNs = nowNs;
            double decay = Math.exp(-FRICTION * dt);
            float nextVelocity = (float) (flingVelocity * decay);
            if (Math.abs(nextVelocity) <= STOP_VELOCITY) {
                nextVelocity = Math.copySign(STOP_VELOCITY, flingVelocity);
                flinging = false;
            }
            float next = offset + (float) ((flingVelocity - nextVelocity) / FRICTION);
            offset = clamp(next);
            if (offset != next) flinging = false;
            flingVelocity = flinging ? nextVelocity : 0f;
            return offset;
        }
        if (returning) {
            float value = (float) returnSpring.animate(targetOffset);
            if (Math.abs(value - targetOffset) < 0.5f) {
                returning = false;
                active = false;
            }
            return value;
        }
        if ((nowNs - lastInteractionNs) > IDLE_RETURN_NS && anchorIndex != holdAnchor) {
            returning = true;
            returnSpring.setValue(offset);
            return (float) returnSpring.animate(targetOffset);
        }
        return offset;
    }

    long timeAtScreenY(float screenY, List<LyricLine> lines,
                       float[] lineTops, float[] lineHeights) {
        int count = lines.size();
        if (count == 0 || lineTops.length < count
                || lineHeights == null || lineHeights.length < count) {
            return -1L;
        }
        float contentY = screenY - centerY + lastRenderedOffset;
        if (contentY < lineTops[0]
                || contentY >= lineTops[count - 1] + lineHeights[count - 1]) {
            return -1L;
        }
        LyricLine line = lines.get(lineIndexAt(lineTops, count, contentY));
        if (line.syllables.isEmpty()) return -1L;
        return line.syllables.get(0).startMs;
    }

    static int lineIndexAt(float[] lineTops, int count, float offset) {
        if (count <= 1) return 0;
        int low = 0;
        int high = count - 1;
        int result = 0;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lineTops[middle] <= offset) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result;
    }

}
