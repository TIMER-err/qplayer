package dev.t1m3.qplayer.desktop.window;

import io.github.timer_err.qml4j.render.items.core.Canvas;
import io.github.timer_err.qml4j.render.items.core.Item;

/**
 * Invalidates the QML scene's GPU-backed caches while the {@code DirectContext}
 * they were created against is still current, before render-thread teardown.
 * The context-bound cache in the scene tree is {@link Canvas}'s offscreen
 * {@code backing} surface. Closing and nulling it here releases native resources
 * safely; restore lazily rebuilds it against the new context on the next paint.
 *
 * <p>Runs on the departing render thread, before its backend is disposed.
 */
final class GpuCaches {

    private GpuCaches() {}

    static void invalidate(Item root) {
        if (root == null) return;
        if (root instanceof Canvas) {
            Canvas c = (Canvas) root;
            if (c.backing != null) {
                try {
                    c.backing.close();
                } catch (Throwable ignored) {
                    // Still discard the failed cache handle so restore cannot
                    // reuse resources from the departing context.
                }
                c.backing = null;
                c.backingW = 0;
                c.backingH = 0;
                c.dirty = true;
                c.requestPaint();
            }
        }
        // children is a plain List on every Item; recurse depth-first.
        for (int i = 0; i < root.children.size(); i++) {
            invalidate(root.children.get(i));
        }
    }
}
