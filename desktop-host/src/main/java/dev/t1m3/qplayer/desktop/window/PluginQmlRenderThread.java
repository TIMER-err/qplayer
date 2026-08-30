package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.plugin.PluginUiSession;
import dev.t1m3.qplayer.util.Logger;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;

import java.util.concurrent.locks.LockSupport;

/** GPU/QML owner for one isolated plugin UI session. */
final class PluginQmlRenderThread extends Thread {
    private static final long FRAME_NANOS = 1_000_000_000L / 60L;
    private final PlayerController controller;
    private final String pluginId;
    private final String contributionId;
    private final PluginQmlWindows.Entry owner;
    private final GraphicsBackend backend;
    private volatile boolean running = true;

    PluginQmlRenderThread(PlayerController controller, String pluginId, String contributionId,
                          PluginQmlWindows.Entry owner) {
        super("qplayer-plugin-ui-" + pluginId + "-" + contributionId);
        this.controller = controller;
        this.pluginId = pluginId;
        this.contributionId = contributionId;
        this.owner = owner;
        this.backend = new GLBackend(owner.window);
        setDaemon(true);
    }

    void shutdown() { running = false; LockSupport.unpark(this); }

    @Override public void run() {
        PluginUiSession session = null;
        QmlView view = null;
        try {
            int[] size = owner.resize;
            backend.init(size[0], size[1]);
            synchronized (QmlRuntimeLock.MONITOR) {
                session = controller.createPluginUiSession(pluginId, contributionId);
                view = session.view();
                if (session.clipboardAllowed()) view.setClipboard(owner.windows.clipboard());
                view.renderer().setPictureCache(false);
                sizeRoot(view, size[0], size[1]);
            }
            while (running && !owner.closed) {
                long started = System.nanoTime();
                int[] resized = owner.resize;
                owner.resize = null;
                synchronized (QmlRuntimeLock.MONITOR) {
                    DirtyQueue dirty = view.dirtyQueue();
                    dirty.install();
                    try {
                        if (resized != null) {
                            backend.resize(resized[0], resized[1]);
                            sizeRoot(view, resized[0], resized[1]);
                        }
                        owner.drain(view);
                        session.pump();
                        view.tickAnimations(started);
                        dirty.flush();
                        view.renderer().setGpuContext(backend.recordingContext());
                        view.renderer().render(backend.acquireCanvas(), view.root(), false);
                    } finally { dirty.uninstall(); }
                }
                backend.present();
                owner.firstFrame = true;
                long remaining = FRAME_NANOS - (System.nanoTime() - started);
                if (remaining > 0) LockSupport.parkNanos(remaining);
            }
        } catch (Throwable error) {
            Logger.warn("plugin UI {}.{} failed: {}", pluginId, contributionId, error.getMessage());
            owner.closed = true;
        } finally {
            synchronized (QmlRuntimeLock.MONITOR) {
                try { if (view != null) GpuCaches.invalidate(view.root()); } catch (Throwable ignored) { }
                try { if (view != null) view.dispose(); } catch (Throwable ignored) { }
                try { if (session != null) session.close(); } catch (Throwable ignored) { }
            }
            try { backend.dispose(); } catch (Throwable ignored) { }
        }
    }

    private static void sizeRoot(QmlView view, int width, int height) {
        if (view.root() == null) return;
        view.root().width.set((double) width);
        view.root().height.set((double) height);
    }
}
