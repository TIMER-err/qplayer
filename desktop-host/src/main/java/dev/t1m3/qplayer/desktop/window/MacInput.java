package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.desktop.app.DesktopNativeBrowser;
import dev.t1m3.qplayer.util.Logger;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.input.TextEditable;
import org.lwjgl.glfw.GLFWNativeCocoa;

import java.util.Locale;

/** Small Cocoa bridge for input details GLFW does not expose. */
final class MacInput {
    private static final double PRECISE_SCROLL_GAIN = 0.2;
    private boolean available;
    private boolean imeInstalled;

    void install(long glfwWindow) {
        if (!isMac(System.getProperty("os.name", ""))) return;
        try {
            DesktopNativeBrowser.ensureNativeLibraryLoaded();
            available = true;
            long cocoaWindow = GLFWNativeCocoa.glfwGetCocoaWindow(glfwWindow);
            imeInstalled = cocoaWindow != 0L && installImeNative(cocoaWindow);
            if (!imeInstalled) Logger.warn("macOS IME candidate-position bridge unavailable");
        } catch (Throwable error) {
            Logger.warn("macOS native input bridge unavailable: {}", error);
        }
    }

    boolean currentScrollIsPrecise() {
        if (!available) return false;
        try {
            return currentScrollIsPreciseNative();
        } catch (Throwable ignored) {
            return false;
        }
    }

    void updateIme(DesktopWindow window) {
        if (!imeInstalled) return;
        QmlView view = window.view();
        Item focused = view == null ? null : view.focused();
        if (view == null || view.root() == null || !(focused instanceof TextEditable)) {
            updateImeNative(false, 0, 0, 0, 0);
            return;
        }
        int[] box = WinIme.boxFromItem(view.root(), focused, 1f);
        if (box == null) updateImeNative(false, 0, 0, 0, 0);
        else updateImeNative(true, box[0], box[1], box[2], box[3]);
    }

    static boolean isMac(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("mac");
    }

    static double preciseScrollGain() {
        return PRECISE_SCROLL_GAIN;
    }

    private static native boolean installImeNative(long cocoaWindow);
    private static native void updateImeNative(boolean active, double left, double top,
                                               double right, double bottom);
    private static native boolean currentScrollIsPreciseNative();
}
