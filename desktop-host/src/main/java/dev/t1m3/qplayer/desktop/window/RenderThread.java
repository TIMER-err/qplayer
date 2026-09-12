package dev.t1m3.qplayer.desktop.window;

import io.github.humbleui.skija.Canvas;

import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.Renderer;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.desktop.lyric.tempera.TemperaHostPage;
import dev.t1m3.qplayer.desktop.lyric.tempera.TemperaCompositor;
import dev.t1m3.qplayer.lyric.skia.LyricCompositor;

import java.util.concurrent.locks.LockSupport;

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

    enum FailureStage {
        BACKEND_INITIALIZATION(true),
        BACKEND_FRAME(true),
        APPLICATION_FRAME(false);

        final boolean backendFailure;

        FailureStage(boolean backendFailure) {
            this.backendFailure = backendFailure;
        }
    }

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
        FailureStage failureStage = FailureStage.BACKEND_INITIALIZATION;
        try {
            dev.t1m3.qplayer.util.Logger.info("render thread starting (backend {})", backend.kind());
            int[] fb = win.framebufferSize();
            backend.init(fb[0], fb[1]);
            dev.t1m3.qplayer.util.Logger.info("backend initialized {}x{}", fb[0], fb[1]);
            failureStage = FailureStage.APPLICATION_FRAME;

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
            DesktopLyricWindow lyricWindow = win.lyricWindow();
            if (respawn) compositor.onRenderResumed();
            // glfwSwapInterval normally blocks present until vblank. Some X11/
            // XWayland drivers only honour it for processes launched from an
            // interactive shell, however; a .desktop/AppImage launch then runs this
            // loop thousands of times per second and produces visibly oscillating
            // animation/scroll presentation. Pace independently to the monitor as a
            // ceiling. If swapBuffers already blocked, the deadline has passed and
            // this adds no second wait.
            final long frameNanos = 1_000_000_000L / Math.max(30, win.refreshHz());

            while (running) {
                long frameStarted = System.nanoTime();
                // Re-read uiScale each frame so a DPI change (e.g. moving between
                // monitors) or a late-fired content-scale callback is picked up before
                // the next sizeRoot / composite call — avoids stale-scale mismatch.
                float uiScale = win.uiScale();
                DirtyQueue dq = view.dirtyQueue();
                // qml4j's dirty queue is thread-local, but Property.changeVersion
                // is process-global in 0.2.x. Keep mutation + layout atomic against
                // the independent desktop-lyric scene; present remains outside so
                // either window's vsync cannot block the other scene's QML work.
                synchronized (QmlRuntimeLock.MONITOR) {
                    dq.install();
                    try {
                        win.drainRenderTasks();
                        win.tickInput(); // smooth wheel-scroll easing
                        int[] size = win.consumePendingResize();
                        if (size != null) {
                            failureStage = FailureStage.BACKEND_FRAME;
                            backend.resize(size[0], size[1]);
                            failureStage = FailureStage.APPLICATION_FRAME;
                            sizeRoot(view, size[0], size[1], uiScale);
                        }
                        if (controller != null) controller.pump();
                        if (lyricWindow != null) lyricWindow.publish(controller,
                                win.settings() == null || win.settings().resolvedDarkValue());
                        view.tickAnimations(System.nanoTime());
                        dq.flush();

                        failureStage = FailureStage.BACKEND_FRAME;
                        Canvas canvas = backend.acquireCanvas();
                        failureStage = FailureStage.APPLICATION_FRAME;
                        Renderer renderer = view.renderer();
                        renderer.setGpuContext(backend.recordingContext());
                        win.temperaPage().advanceFade(controller, frameStarted);
                        dq.flush();
                        if (win.temperaPageVisible()) {
                            drawTemperaFrame(canvas, renderer, view, controller, uiScale);
                        } else {
                            compositor.composite(canvas, renderer, view, controller, win.settings(),
                                    backend.recordingContext(), uiScale,
                                    backend.width(), backend.height());
                        }
                    } finally {
                        dq.uninstall();
                    }
                }

                failureStage = FailureStage.BACKEND_FRAME;
                backend.present();
                failureStage = FailureStage.APPLICATION_FRAME;

                if (!firstFrameDone) {
                    firstFrameDone = true;
                    dev.t1m3.qplayer.util.Logger.info("first frame painted");
                    win.onFirstFramePainted();
                }

                // Pace relative to this frame, rather than an accumulated absolute
                // deadline. If swapBuffers misses a vblank, an absolute scheduler
                // tries to catch up by emitting the next frame immediately, creating
                // a repeating long/near-zero interval that looks like UI bouncing.
                // Dropped display frames cannot be recovered, so never catch them up.
                long remaining = frameNanos - (System.nanoTime() - frameStarted);
                if (remaining > 0L) {
                    // parkNanos can return early; finish the short remainder so
                    // frame intervals do not alternate around the deadline.
                    while (remaining > 0L && running) {
                        LockSupport.parkNanos(remaining);
                        remaining = frameNanos - (System.nanoTime() - frameStarted);
                    }
                }
            }
        } catch (Throwable t) {
            win.onRenderError(t, failureStage);
        } finally {
            // LyricCompositor survives render-thread respawns, but SPlayer's cached
            // GPU Surfaces/snapshots do not. Release them while THIS DirectContext
            // is still alive; otherwise restore draws stale resources (black), and
            // the next cover swap can crash in Skia MeshOp on the new context.
            try {
                win.compositor().invalidateGpuContext();
            } catch (Throwable ignored) {
            }
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

    /** Fade the independent visualizer and its controls over the original page. */
    private void drawTemperaFrame(Canvas canvas, Renderer renderer, QmlView view,
                                  PlayerController controller, float uiScale) {
        TemperaHostPage page = win.temperaPage();
        page.configure(win.temperaTuning(), win.settings().intOf("lyricFontSize") / 28f,
                win.settings().lyricBgStatic());
        TemperaCompositor.composite(canvas, renderer, win.temperaChrome(view), page, controller,
                uiScale, backend.width(), backend.height(), win.settings().resolvedDarkValue(),
                () -> win.compositor().composite(canvas, renderer, view, controller, win.settings(),
                        backend.recordingContext(), uiScale, backend.width(), backend.height()));
    }
}
