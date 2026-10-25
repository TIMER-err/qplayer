package dev.t1m3.qplayer.lyric.skia;

import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.items.animation.AbstractAnimation;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.runtime.member.MemberAccess;

import java.util.function.Consumer;

/**
 * Uses qml4j's existing frame clock without its one-tick loop restart gap.
 * Absolute elapsed time keeps the phase continuous across skipped frames;
 * AbstractAnimation.running also keeps Android's on-demand renderer awake.
 */
final class MiniPlayerLoadingAnimation extends AbstractAnimation {
    private static final long CYCLE_NANOS = 1_000_000_000L;
    private final Property<Object> sweeping;
    private final Property<Object> shown;
    private final Property<Object> phase;
    private final Property<Object> completedCycles;
    private final Consumer<Object> stateChanged = ignored -> updateRunning();
    private boolean started;
    private long epochNanos;
    private long lastCycle;

    MiniPlayerLoadingAnimation(Item miniPlayer) {
        sweeping = property(miniPlayer, "sweeping");
        shown = property(miniPlayer, "visible");
        phase = property(miniPlayer, "_sweepPhase");
        completedCycles = property(miniPlayer, "_sweepCycle");
        sweeping.addListener(stateChanged);
        shown.addListener(stateChanged);
        updateRunning();
    }

    @SuppressWarnings("unchecked")
    private static Property<Object> property(Item item, String name) {
        Property<?> value = MemberAccess.propertyOf(item, name);
        if (value == null) throw new IllegalArgumentException("MiniPlayer lacks " + name);
        return (Property<Object>) value;
    }

    private void updateRunning() {
        boolean active = Boolean.TRUE.equals(sweeping.peek()) && Boolean.TRUE.equals(shown.peek());
        if (!active) started = false;
        running.set(active);
    }

    @Override
    public void tick(long nowNanos) {
        if (!Boolean.TRUE.equals(running.peek())) return;
        if (!started) {
            epochNanos = nowNanos;
            lastCycle = 0;
            started = true;
            completedCycles.set(0.0);
        }
        long elapsed = nowNanos - epochNanos;
        phase.set((elapsed % CYCLE_NANOS) / (double) CYCLE_NANOS);
        long cycle = elapsed / CYCLE_NANOS;
        if (cycle != lastCycle) {
            lastCycle = cycle;
            completedCycles.set((double) cycle);
        }
    }

    @Override
    protected void releaseResources() {
        sweeping.removeListener(stateChanged);
        shown.removeListener(stateChanged);
        stop();
        super.releaseResources();
    }
}
