package dev.t1m3.qplayer.desktop.window;

import com.sun.jna.NativeLibrary;
import dev.t1m3.qplayer.util.Logger;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/** Selects the tested EGL path on XWayland without losing X11 window positioning. */
final class GlContextPolicy {
    private GlContextPolicy() {}

    static void prepareEnvironment() {
        String requested = requested();
        boolean linux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
        if (!linux || requested.equals("native")) return;
        if (!requested.equals("egl") && !waylandSession()) return;
        // Must precede GLFW/driver initialization. Native getenv sees this process-
        // local setting; overwrite=0 preserves any explicit launch environment.
        // NVIDIA's threaded EGL initialization can return EGL_BAD_DISPLAY in Java.
        int result = NativeLibrary.getInstance("c").getFunction("setenv").invokeInt(
                new Object[]{"__GL_THREADED_OPTIMIZATIONS", "0", 0});
        if (result != 0) throw new IllegalStateException("Cannot configure EGL driver environment");
    }

    static void configureWindow() {
        int api = select(requested(), GLFW.glfwGetPlatform(), waylandSession());
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, api);
        Logger.info("OpenGL context API: {}", api == GLFW.GLFW_EGL_CONTEXT_API ? "egl" : "native");
    }

    static int select(String requested, int platform, boolean waylandSession) {
        return switch (requested) {
            case "egl" -> GLFW.GLFW_EGL_CONTEXT_API;
            case "native" -> GLFW.GLFW_NATIVE_CONTEXT_API;
            case "auto" -> platform == GLFW.GLFW_PLATFORM_X11 && waylandSession
                    ? GLFW.GLFW_EGL_CONTEXT_API : GLFW.GLFW_NATIVE_CONTEXT_API;
            default -> throw new IllegalArgumentException("qplayer.glContext must be auto, native, or egl");
        };
    }

    private static String requested() {
        String value = System.getProperty("qplayer.glContext", "auto").toLowerCase(Locale.ROOT);
        select(value, GLFW.GLFW_PLATFORM_NULL, false);
        return value;
    }

    private static boolean waylandSession() {
        String display = System.getenv("WAYLAND_DISPLAY");
        return display != null && !display.isBlank();
    }
}
