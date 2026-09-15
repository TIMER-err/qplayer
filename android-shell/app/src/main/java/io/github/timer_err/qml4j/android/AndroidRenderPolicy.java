package io.github.timer_err.qml4j.android;

import io.github.timer_err.qml4j.render.items.animation.AbstractAnimation;
import io.github.timer_err.qml4j.render.items.animation.Timer;
import io.github.timer_err.qml4j.render.items.core.Image;
import io.github.timer_err.qml4j.render.items.core.Item;

/**
 * Extra work that keeps an otherwise idle Android QML surface rendering.
 *
 * <p>{@link android.opengl.GLSurfaceView#RENDERMODE_WHEN_DIRTY} cannot observe
 * clocks owned by qml4j or an image decoder publishing into an {@link Image}.
 * Stopping after the frame that starts either operation leaves it suspended
 * until an unrelated touch requests another frame.</p>
 */
final class AndroidRenderPolicy {
    private AndroidRenderPolicy() { }

    static boolean hasActiveQmlWork(Item root) {
        return hasRunningClock(root) || hasPendingVisibleImage(root, true);
    }

    static boolean shouldRenderContinuously(boolean hostAnimation,
                                             boolean changedThisFrame,
                                             boolean settlingAfterWake,
                                             Item root) {
        return hostAnimation || changedThisFrame || settlingAfterWake
                || hasActiveQmlWork(root);
    }

    private static boolean hasRunningClock(Item item) {
        if (item == null) return false;
        if (item instanceof AbstractAnimation
                && Boolean.TRUE.equals(((AbstractAnimation) item).running.peek())) {
            return true;
        }
        if (item instanceof Timer
                && Boolean.TRUE.equals(((Timer) item).running.peek())) {
            return true;
        }
        for (int i = 0; i < item.children.size(); i++) {
            if (hasRunningClock(item.children.get(i))) return true;
        }
        for (int i = 0; i < item.resources.size(); i++) {
            if (hasRunningClock(item.resources.get(i))) return true;
        }
        return false;
    }

    private static boolean hasPendingVisibleImage(Item item, boolean ancestorsVisible) {
        if (item == null) return false;
        boolean visible = ancestorsVisible
                && Boolean.TRUE.equals(item.visible.peek())
                && item.opacity.peekFloat() > 0.001f;
        if (!visible) return false;

        if (item instanceof Image) {
            Image image = (Image) item;
            String loaded = image.loadedSource;
            if (loaded != null && !loaded.isEmpty()
                    && image.decodeGen != image.adoptedGen) {
                return true;
            }
        }
        for (int i = 0; i < item.children.size(); i++) {
            if (hasPendingVisibleImage(item.children.get(i), true)) return true;
        }
        for (int i = 0; i < item.resources.size(); i++) {
            if (hasPendingVisibleImage(item.resources.get(i), true)) return true;
        }
        return false;
    }
}
