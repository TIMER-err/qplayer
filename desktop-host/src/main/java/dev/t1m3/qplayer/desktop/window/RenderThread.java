package dev.t1m3.qplayer.desktop.window;

import io.github.humbleui.skija.Canvas;

import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.Renderer;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.lyric.skia.LyricCompositor;
import dev.t1m3.qplayer.qml.QmlRuntimeLock;
import dev.t1m3.qplayer.util.Logger;

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

    private boolean firstFrameDone;
    private QmlView view;
    private float appliedScale;
    private FailureStage failureStage = FailureStage.BACKEND_INITIALIZATION;
    private long nextFrame;
    private boolean inFrame;
    private boolean disposed;

    // On Cocoa this session is driven by GLFW's event thread. AppKit updates
    // the same native drawable synchronously, before the framebuffer callback.
    void frameOnEventThread(boolean resized) {
        if (!running || inFrame || (!resized && System.nanoTime() < nextFrame)) return;
        inFrame = true;
        try {
            if (view == null) initialize();
            nextFrame = System.nanoTime() + frameInterval();
            drawFrame();
        } catch (Throwable t) {
            running = false;
            win.onRenderError(t, failureStage);
        } finally {
            inFrame = false;
            if (!running) disposeSession();
        }
    }

    double eventWaitSeconds() {
        return Math.max(0, nextFrame - System.nanoTime()) / 1_000_000_000.0;
    }

    void stopOnEventThread() {
        running = false;
        if (!inFrame) disposeSession();
    }

    void shutdown() {
        running = false;
        LockSupport.unpark(this);
    }

    @Override
    public void run() {
        try {
            initialize();
            while (running) {
                long deadline = System.nanoTime() + frameInterval();
                drawFrame();
                long remaining;
                while (running && (remaining = deadline - System.nanoTime()) > 0) {
                    LockSupport.parkNanos(remaining);
                }
            }
        } catch (Throwable t) {
            win.onRenderError(t, failureStage);
        } finally {
            disposeSession();
        }
    }

    private long frameInterval() {
        return win.windowVisible() ? 1_000_000_000L / Math.max(30, win.refreshHz()) : 30_000_000L;
    }

    private void initialize() {
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
        appliedScale = win.uiScale();
        sizeRoot(view, fb[0], fb[1], appliedScale);

        if (respawn) win.compositor().onRenderResumed();
    }

    private void drawFrame() {
        PlayerController controller = win.controller();
        LyricCompositor compositor = win.compositor();
        DesktopLyricWindow lyricWindow = win.lyricWindow();
        boolean renderFrame = win.windowVisible();
        // Layout and composition must use the same scale even when a DPI
        // callback arrives without a framebuffer-size callback.
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
                // Keep playback, bindings and the independent desktop
                // lyrics live while minimized, but defer all main-window
                // GPU work (including resize) until restoration.
                int[] size = renderFrame ? win.consumePendingResize() : null;
                if (size != null) {
                    failureStage = FailureStage.BACKEND_FRAME;
                    backend.resize(size[0], size[1]);
                    failureStage = FailureStage.APPLICATION_FRAME;
                }
                if (renderFrame && (size != null || uiScale != appliedScale)) {
                    sizeRoot(view, backend.width(), backend.height(), uiScale);
                    appliedScale = uiScale;
                }
                if (controller != null) controller.pump();
                if (lyricWindow != null) lyricWindow.publish(controller,
                        win.settings() == null || win.settings().resolvedDarkValue());
                view.tickAnimations(System.nanoTime());
                dq.flush();

                if (renderFrame) {
                    failureStage = FailureStage.BACKEND_FRAME;
                    if (backend instanceof GLBackend gl) {
                        gl.setBackdropCapture(win.settings() != null
                                && win.settings().bool("liquidGlassNavigation"));
                    }
                    Canvas canvas = backend.acquireCanvas();
                    failureStage = FailureStage.APPLICATION_FRAME;
                    Renderer renderer = view.renderer();
                    renderer.setGpuContext(backend.recordingContext());
                    compositor.composite(canvas, renderer, view, controller, win.settings(),
                            backend.recordingContext(), uiScale, backend.width(), backend.height());
                }
            } finally {
                dq.uninstall();
            }
        }

        // Iconification may happen during composition. Recheck outside
        // the QML lock immediately before swapping GL buffers. Vulkan
        // must present an already-acquired image to release ownership;
        // dropping it here would exhaust the swapchain on later restores.
        if (renderFrame && (backend.kind() != GraphicsBackend.Kind.GL || win.windowVisible())) {
            failureStage = FailureStage.BACKEND_FRAME;
            backend.present();
            failureStage = FailureStage.APPLICATION_FRAME;

            if (!firstFrameDone) {
                firstFrameDone = true;
                dev.t1m3.qplayer.util.Logger.info("first frame painted");
                win.onFirstFramePainted();
            }
        }
    }

    private void disposeSession() {
        if (disposed) return;
        disposed = true;
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
        if (win.isClosing()) {
            try {
                win.disposeScene();
            } catch (Throwable error) {
                Logger.warn("QML scene cleanup failed: {}", error);
            }
        }
        try {
            backend.dispose();
        } catch (Throwable ignored) {
        }
    }

    private static void sizeRoot(QmlView view, int fbW, int fbH, float uiScale) {
        if (view.root() == null) return;
        view.root().width.set(fbW / uiScale);
        view.root().height.set(fbH / uiScale);
    }

}
