package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.desktop.app.DesktopNativeBrowser;
import dev.t1m3.qplayer.util.Logger;
import org.lwjgl.glfw.GLFWNativeCocoa;

/** Native controls stay owned by the titled, resizable GLFW window. Main thread only. */
final class MacWindowChrome {
    private final long cocoaWindow;
    final double buttonsEnd;
    final double height;
    private boolean hidden;

    private MacWindowChrome(long cocoaWindow, double[] metrics) {
        this.cocoaWindow = cocoaWindow;
        buttonsEnd = metrics[0];
        height = metrics[1];
    }

    static MacWindowChrome install(long glfwWindow) {
        if (!MacInput.isMac(System.getProperty("os.name"))) return null;
        try {
            DesktopNativeBrowser.ensureNativeLibraryLoaded();
            long handle = GLFWNativeCocoa.glfwGetCocoaWindow(glfwWindow);
            double[] metrics = installNative(handle);
            if (metrics != null) return new MacWindowChrome(handle, metrics);
        } catch (LinkageError | RuntimeException error) {
            Logger.warn("macOS integrated title bar unavailable: {}", error);
        }
        return null;
    }

    void setHidden(boolean value) {
        if (hidden == value) return;
        setHiddenNative(cocoaWindow, value);
        hidden = value;
    }

    private static native double[] installNative(long cocoaWindow);
    private static native void setHiddenNative(long cocoaWindow, boolean hidden);
}
