package io.github.timer_err.qml4j.render;

import io.github.humbleui.skija.Canvas;
import io.github.timer_err.qml4j.render.items.core.Item;

/** Narrow, version-local bridge to qml4j 0.2.33's live Skija drawing path. */
public abstract class SkijaItem extends Item {
    private final Renderer renderer;

    protected SkijaItem(Renderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public final void paint(Painter painter, float width, float height, float alpha) {
        paintSkija(painter, painter.canvas(), width, height, alpha);
    }

    protected abstract void paintSkija(Painter painter, Canvas canvas,
                                       float width, float height, float alpha);

    protected final boolean hasGpuContext() {
        return renderer.gpuContext() != null;
    }

    /** Hidden draw sources need their own layout pass to resolve nested Loaders. */
    protected final void layoutSource(Item source) {
        renderer.layoutOnly(source);
    }

    /** Unlike renderSubtree, this does not replace the renderer's enclosing clip. */
    protected final void drawSource(Painter painter, Canvas target, Item source, float alpha) {
        Canvas previous = painter.canvas();
        painter.bind(target);
        try {
            renderer.drawForced(target, source, alpha);
        } finally {
            painter.bind(previous);
        }
    }
}
