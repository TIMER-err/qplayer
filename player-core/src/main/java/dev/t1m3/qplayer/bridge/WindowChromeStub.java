package dev.t1m3.qplayer.bridge;

import io.github.timer_err.qml4j.engine.binding.Property;

/**
 * No-op {@code hostWindow} context object for every platform that isn't
 * Windows desktop (Android, macOS, Linux). qml4j's compiler rejects an
 * undeclared top-level identifier at COMPILE time -- even one only ever
 * referenced inside a {@code typeof x !== "undefined"} guard, and even on a
 * branch that never actually runs -- so {@code shared-qml} (Main.qml,
 * TitleBar.qml, NavigationRail's header) cannot simply have {@code
 * hostWindow} be absent on platforms without the custom title bar; it must
 * always resolve to *something*. This stub gives it the exact same field/
 * method shape as the real {@code WindowChrome} (desktop-host, Windows only)
 * with {@code available} false and every action a no-op, so QML code can
 * always safely check {@code hostWindow.available} instead of {@code typeof}.
 */
public final class WindowChromeStub {
    public final Property<Double> nativeButtonsEnd = new Property<>(0.0);
    public final Property<Boolean> available = new Property<>(Boolean.FALSE);
    public final Property<Boolean> maximized = new Property<>(Boolean.FALSE);
    public final Property<Boolean> focused = new Property<>(Boolean.TRUE);
    public final Property<Double> buttonWidthPx = new Property<>(46.0);

    public void minimize() { }

    public void toggleMaximize() { }

    public void close() { }
}
