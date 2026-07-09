package dev.t1m3.qplayer.desktop;

import io.github.humbleui.skija.Canvas;

import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.Renderer;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.lyric.skia.LyricCompositor;

/**
 * The disposable render thread. It owns the GPU stack — a {@link GraphicsBackend}
 * (GL or Vulkan) plus its Skija {@code DirectContext} — and runs the per-frame
 * loop (drain input, pump the controller, tick animations, composite, present).
 *
 * <p>It is spawned by {@link DesktopWindow} and can be fully torn down on
 * minimize-to-tray ({@code running=false} + join): the GPU resources are
 * destroyed here, on the context-owning thread, in {@link #run}'s finally block.
 * The persistent {@link QmlView} / engine / {@link PlayerController} live in
 * {@link DesktopWindow} and survive across respawns, so the UI keeps its state;
 * only the context-bound {@code Canvas} offscreens are invalidated and lazily
 * rebuilt against the fresh context on restore.
 */
final class RenderThread extends Thread {

    private final DesktopWindow win;
    private final GraphicsBackend backend;
    private volatile boolean running = true;

    RenderThread(DesktopWindow win) {
        super("qplayer-render");
        this.win = win;
        this.backend = win.kind() == GraphicsBackend.Kind.VULKAN
                ? new VulkanBackend(win.window())
                : new GLBackend(win.window());
    }

    /** Signal the loop to stop; the caller then {@link #join}s and the GPU stack is
     *  destroyed on this thread before it exits. */
    void shutdown() {
        running = false;
    }

    GraphicsBackend.Kind kind() {
        return backend.kind();
    }

    @Override
    public void run() {
        boolean firstFrameDone = false;
        QmlView view = null;
        try {
            dev.t1m3.qplayer.util.Logger.info("render thread starting (backend {})", backend.kind());
            int[] fb = win.framebufferSize();
            backend.init(fb[0], fb[1]);
            dev.t1m3.qplayer.util.Logger.info("backend initialized {}x{}", fb[0], fb[1]);

            // The persistent QML view is built once and survives render-thread
            // respawns. On a respawn its Canvas offscreens were already closed+nulled
            // during the previous teardown (below, while THAT context was still
            // current), so here they just lazily rebuild against this fresh context.
            view = win.ensureView();
            boolean respawn = win.markViewLive();
            dev.t1m3.qplayer.util.Logger.info("QML view ready (respawn={}, root={})",
                    respawn, view.root() != null);
            sizeRoot(view, fb[0], fb[1], win.uiScale());

            PlayerController controller = win.controller();
            LyricCompositor compositor = win.compositor();

            while (running) {
                // Re-read uiScale each frame so a DPI change (e.g. moving between
                // monitors) or a late-fired content-scale callback is picked up before
                // the next sizeRoot / composite call — avoids stale-scale mismatch.
                float uiScale = win.uiScale();
                DirtyQueue dq = view.dirtyQueue();
                dq.install();
                try {
                    win.drainRenderTasks();
                    win.tickInput(); // smooth wheel-scroll easing
                    int[] size = win.consumePendingResize();
                    if (size != null) {
                        backend.resize(size[0], size[1]);
                        sizeRoot(view, size[0], size[1], uiScale);
                    }
                    if (controller != null) controller.pump();
                    view.tickAnimations(System.nanoTime());
                    dq.flush();

                    Canvas canvas = backend.acquireCanvas();
                    Renderer renderer = view.renderer();
                    renderer.setGpuContext(backend.recordingContext());
                    compositor.composite(canvas, renderer, view, controller, win.settings(),
                            backend.recordingContext(), uiScale, backend.width(), backend.height());

                    backend.present();

                    if (!firstFrameDone) {
                        firstFrameDone = true;
                        dev.t1m3.qplayer.util.Logger.info("first frame painted");
                        win.onFirstFramePainted();
                    }
                } finally {
                    dq.uninstall();
                }
            }
        } catch (Throwable t) {
            win.onRenderError(t);
        } finally {
            // Close the QML scene's GPU-backed Canvas offscreens HERE, while this
            // thread's DirectContext is still current — they were created against it,
            // so deleting their GL/VK objects must happen before the context is
            // destroyed. Doing it on the next (respawned) thread instead deletes them
            // against a dead context → SIGSEGV in the GL/VK driver. They lazily
            // rebuild against the fresh context on restore.
            try {
                if (view != null) GpuCaches.invalidate(view.root());
            } catch (Throwable ignored) {
            }
            try {
                backend.dispose();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void sizeRoot(QmlView view, int fbW, int fbH, float uiScale) {
        if (view.root() == null) return;
        view.root().width.set(fbW / uiScale);
        view.root().height.set(fbH / uiScale);
    }
}
