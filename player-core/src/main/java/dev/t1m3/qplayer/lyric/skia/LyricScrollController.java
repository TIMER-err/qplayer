package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LyricLine;

import java.util.List;

/**
 * User-controlled lyric scrolling: drag, fling, wheel, idle hold, and return.
 *
 * <p>The controller uses the same content-space offset as the renderer but owns
 * all gesture state. Hosts can therefore share the interaction behavior without
 * depending on Skia drawing code.
 */
final class LyricScrollController {

    private static final float DECELERATION = 2400f;
    private static final float MIN_FLING_VELOCITY = 60f;
    private static final long IDLE_RETURN_NS = 3_000_000_000L;
    private static final int VELOCITY_SAMPLES = 8;
    private static final float VELOCITY_WINDOW_SECONDS = 0.09f;
    private static final float WHEEL_STEP_PX = 100f;

    private boolean active;
    private boolean dragging;
    private boolean flinging;
    private boolean returning;
    private float offset;
    private float flingVelocity;
    private long lastStepNs;
    private long lastInteractionNs;
    private int holdAnchor = Integer.MIN_VALUE;
    private int previousAnchor = Integer.MIN_VALUE;
    private float lastRenderedOffset;
    private float centerY;
    private float minimum;
    private float maximum;
    private final long[] sampleTimes = new long[VELOCITY_SAMPLES];
    private final float[] samplePositions = new float[VELOCITY_SAMPLES];
    private int sampleCount;

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
        sampleCount = 0;
        holdAnchor = Integer.MIN_VALUE;
    }

    void pointerDown(float y) {
        active = true;
        dragging = true;
        flinging = false;
        returning = false;
        offset = clamp(lastRenderedOffset);
        flingVelocity = 0f;
        sampleCount = 0;
        addSample(y);
        lastInteractionNs = System.nanoTime();
    }

    void pointerMove(float y) {
        if (!dragging || sampleCount == 0) return;
        float previousY = samplePositions[sampleCount - 1];
        offset = clamp(offset - (y - previousY));
        addSample(y);
        lastInteractionNs = System.nanoTime();
    }

    void pointerUp() {
        if (!dragging) return;
        dragging = false;
        flingVelocity = computeFlingVelocity();
        flinging = Math.abs(flingVelocity) >= MIN_FLING_VELOCITY;
        lastStepNs = System.nanoTime();
        lastInteractionNs = lastStepNs;
    }

    void wheel(float notches) {
        if (!active) offset = clamp(lastRenderedOffset);
        active = true;
        dragging = false;
        flinging = false;
        returning = false;
        offset = clamp(offset - notches * WHEEL_STEP_PX);
        lastInteractionNs = System.nanoTime();
    }

    float step(float targetOffset, long nowNs, int anchorIndex, SpringAnim returnSpring) {
        if (dragging) {
            holdAnchor = anchorIndex;
            lastStepNs = nowNs;
            return offset;
        }
        if (flinging) {
            float dt = Math.min(0.05f, (nowNs - lastStepNs) / 1_000_000_000f);
            lastStepNs = nowNs;
            float before = offset;
            offset = clamp(offset + flingVelocity * dt);
            if (offset != before) {
                flinging = false;
            } else {
                flingVelocity = decayVelocity(flingVelocity, dt);
                if (Math.abs(flingVelocity) < 30f) flinging = false;
            }
            lastInteractionNs = nowNs;
            return offset;
        }
        if (returning) {
            // Melodify: 3s 空闲后每帧 lerp k=0.18 回 0,无弹簧。
            offset += (targetOffset - offset) * 0.18f;
            if (Math.abs(offset - targetOffset) < 0.5f) {
                offset = targetOffset;
                returning = false;
                active = false;
            }
            return offset;
        }
        if ((nowNs - lastInteractionNs) > IDLE_RETURN_NS && anchorIndex != holdAnchor) {
            returning = true;
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

    private void addSample(float y) {
        if (sampleCount == VELOCITY_SAMPLES) {
            System.arraycopy(sampleTimes, 1, sampleTimes, 0, VELOCITY_SAMPLES - 1);
            System.arraycopy(samplePositions, 1, samplePositions, 0, VELOCITY_SAMPLES - 1);
            sampleCount--;
        }
        sampleTimes[sampleCount] = System.nanoTime();
        samplePositions[sampleCount] = y;
        sampleCount++;
    }

    private float computeFlingVelocity() {
        if (sampleCount < 2) return 0f;
        long newest = sampleTimes[sampleCount - 1];
        int oldest = sampleCount - 1;
        for (int i = sampleCount - 1; i >= 0; i--) {
            if ((newest - sampleTimes[i]) / 1_000_000_000f > VELOCITY_WINDOW_SECONDS) break;
            oldest = i;
        }
        float dt = (newest - sampleTimes[oldest]) / 1_000_000_000f;
        if (dt < 0.001f) return 0f;
        return -(samplePositions[sampleCount - 1] - samplePositions[oldest]) / dt;
    }

    private static float decayVelocity(float value, float dt) {
        float delta = DECELERATION * dt;
        if (value > 0f) return Math.max(0f, value - delta);
        return Math.min(0f, value + delta);
    }
}
