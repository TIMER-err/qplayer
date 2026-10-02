package dev.t1m3.qplayer.desktop.window;

import io.github.timer_err.qml4j.engine.binding.Property;

import org.lwjgl.glfw.GLFW;

/**
 * QML-facing desktop window bridge. Every desktop platform receives a live
 * instance so shared QML can observe exclusive-fullscreen state; {@link
 * #available} remains true only for the Windows custom title bar. Android
 * registers {@link dev.t1m3.qplayer.bridge.WindowChromeStub} with the same
 * field/method shape because qml4j rejects unknown context identifiers while
 * compiling, even on unreachable branches.
 *
 * <p>Methods may be called from QML on the render thread and therefore marshal
 * GLFW work onto {@link DesktopWindow#postMainTask}.
 */
public final class WindowChrome {

    /** Logical-px width of each of the three caption buttons -- single source
     *  of truth shared with {@link WinFrameless}'s hit-test math and (via a
     *  bound Property below) {@code TitleBar.qml}'s own layout, so the two
     *  can never drift out of sync. */
    static final double BUTTON_WIDTH_LOGICAL_PX = 46;
    static final int BUTTON_COUNT = 3;

    public final Property<Boolean> available;
    public final Property<Boolean> maximized = new Property<>(Boolean.FALSE);
    public final Property<Boolean> focused = new Property<>(Boolean.TRUE);
    public final Property<Boolean> fullscreen = new Property<>(Boolean.FALSE);
    public final Property<Boolean> fullscreenExitHold = new Property<>(Boolean.FALSE);
    public final Property<Double> fullscreenExitProgress = new Property<>(0.0);
    public final Property<Double> buttonWidthPx = new Property<>(BUTTON_WIDTH_LOGICAL_PX);

    private final DesktopWindow window;

    WindowChrome(DesktopWindow window, boolean customTitleBarAvailable) {
        this.window = window;
        this.available = new Property<>(customTitleBarAvailable);
    }

    /** Plain OS iconify -- matches the native minimize button's existing
     *  behavior exactly (taskbar-minimize, NOT hide-to-tray). */
    public void minimize() {
        window.postMainTask(() -> GLFW.glfwIconifyWindow(window.window()));
    }

    public void toggleMaximize() {
        window.postMainTask(() -> {
            long w = window.window();
            if (GLFW.glfwGetWindowAttrib(w, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE) {
                GLFW.glfwRestoreWindow(w);
            } else {
                GLFW.glfwMaximizeWindow(w);
            }
        });
    }

    public void toggleFullscreen() {
        window.postMainTask(window::toggleFullscreen);
    }

    /** Reuses the exact same hide-to-tray-if-available-else-quit decision the
     *  native close button has always gone through -- only the trigger path
     *  (a QML click instead of a native WM_CLOSE) is new. */
    public void close() {
        window.postMainTask(window::onExitRequested);
    }
}
