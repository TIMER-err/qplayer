package dev.t1m3.qplayer.desktop;

import io.github.timer_err.qml4j.render.Clipboard;

import org.lwjgl.glfw.GLFW;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * System clipboard via GLFW, for QML text fields (copy/paste).
 *
 * <p>Replaces the AWT clipboard, whose {@code Toolkit.getDefaultToolkit()} path
 * doesn't work in the native image (the same headful-AWT init that broke the
 * tray), so Ctrl+V silently returned nothing. GLFW is already the windowing
 * backend, needs no AWT, and works in the native binary.
 *
 * <p>Threading: the engine calls get/set on the render thread (clipboard access
 * happens during QML key handling), but GLFW's clipboard functions are
 * main-thread-only — on Windows the Win32 clipboard is bound to the helper
 * window owned by the main thread, and cross-thread access fails silently. So
 * each call is marshalled onto the main event loop ({@code postMainTask} +
 * {@code glfwPostEmptyEvent}, which is documented as callable from any thread)
 * and the render thread blocks briefly for the result. The timeout keeps a
 * shutting-down main loop from wedging the render thread.
 */
final class GlfwClipboard implements Clipboard {

    private static final long TIMEOUT_MS = 500;

    private final DesktopWindow win;

    GlfwClipboard(DesktopWindow win) {
        this.win = win;
    }

    @Override
    public String getText() {
        return onMainThread(() -> GLFW.glfwGetClipboardString(win.window()));
    }

    @Override
    public void setText(String text) {
        final String s = text == null ? "" : text;
        onMainThread(() -> {
            GLFW.glfwSetClipboardString(win.window(), s);
            return null;
        });
    }

    private <T> T onMainThread(Supplier<T> op) {
        if (Thread.currentThread() == win.mainThread()) {
            try {
                return op.get();
            } catch (Throwable t) {
                return null;
            }
        }
        CompletableFuture<T> f = new CompletableFuture<>();
        win.postMainTask(() -> {
            try {
                f.complete(op.get());
            } catch (Throwable t) {
                f.complete(null);
            }
        });
        GLFW.glfwPostEmptyEvent(); // wake the main loop; safe from any thread
        try {
            return f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }
}
