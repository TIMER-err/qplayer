package dev.t1m3.qplayer.bridge;

import io.github.timer_err.qml4j.engine.binding.Property;

/**
 * No-op {@code hostWindow} context object for Android. Desktop always registers
 * its live bridge, even when the platform uses a system title bar, because
 * fullscreen state and its hold-to-exit indicator are cross-platform desktop
 * features. qml4j rejects an undeclared top-level identifier at compile time,
 * so Android keeps the same field/method shape with inert values.
 */
public final class WindowChromeStub {
    public final Property<Boolean> available = new Property<>(Boolean.FALSE);
    public final Property<Boolean> maximized = new Property<>(Boolean.FALSE);
    public final Property<Boolean> focused = new Property<>(Boolean.TRUE);
    public final Property<Boolean> fullscreen = new Property<>(Boolean.FALSE);
    public final Property<Boolean> fullscreenExitHold = new Property<>(Boolean.FALSE);
    public final Property<Double> fullscreenExitProgress = new Property<>(0.0);
    public final Property<Double> buttonWidthPx = new Property<>(46.0);

    public void minimize() { }

    public void toggleMaximize() { }

    public void toggleFullscreen() { }

    public void close() { }
}
