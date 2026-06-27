package dev.t1m3.qplayer.desktop;

import io.github.timer_err.qml4j.render.Clipboard;

import org.lwjgl.glfw.GLFW;

/**
 * System clipboard via GLFW, for QML text fields (copy/paste).
 *
 * <p>Replaces the AWT clipboard, whose {@code Toolkit.getDefaultToolkit()} path
 * doesn't work in the native image (the same headful-AWT init that broke the
 * tray), so Ctrl+V silently returned nothing. GLFW is already the windowing
 * backend, needs no AWT, and works in the native binary.
 *
 * <p>Threading: get/set run on the render thread, which owns the GLFW window and
 * event loop — clipboard access during QML key handling happens there, so these
 * GLFW calls are on the required thread.
 */
final class GlfwClipboard implements Clipboard {

    private final long window;

    GlfwClipboard(long window) {
        this.window = window;
    }

    @Override
    public String getText() {
        try {
            return GLFW.glfwGetClipboardString(window);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void setText(String text) {
        try {
            GLFW.glfwSetClipboardString(window, text == null ? "" : text);
        } catch (Throwable ignored) {
        }
    }
}
