package dev.t1m3.qplayer.lyric.tempera;

import dev.t1m3.qplayer.bridge.PlayerController;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import io.github.timer_err.qml4j.render.Renderer;
import io.github.timer_err.qml4j.render.items.core.Item;

/** Composites the artwork and controls as one fading page over the current scene. */
public final class TemperaCompositor {
    private TemperaCompositor() { }

    public static void composite(Canvas canvas, Renderer renderer, Item chrome,
                                 TemperaHostPage page, PlayerController controller,
                                 float uiScale, int width, int height, boolean dark,
                                 Runnable drawUnderlay) {
        if (page.opacity() < 0.999f) {
            Boolean visible = chrome == null ? null : chrome.visible.peek();
            // Property.set() clears the QML binding. Preserve it while excluding
            // these controls from the underlay, so closing still hides the subtree.
            if (chrome != null) chrome.visible.setBypassInterceptor(false);
            try {
                drawUnderlay.run();
            } finally {
                if (chrome != null) chrome.visible.setBypassInterceptor(visible);
            }
        }

        float lw = width / uiScale, lh = height / uiScale;
        // The opaque page needs no extra full-window surface.
        try (Paint fadePaint = page.opacity() < 0.999f
                ? new Paint().setAlphaf(page.opacity()) : null) {
            int save = fadePaint == null ? canvas.save()
                    : canvas.saveLayer(Rect.makeWH(width, height), fadePaint);
            try {
                page.render(canvas, controller, uiScale, lw, lh, System.nanoTime(), dark);
                if (chrome != null) {
                    renderer.layoutOnly(chrome);
                    int controls = canvas.save();
                    try {
                        canvas.scale(uiScale, uiScale);
                        renderer.renderSubtree(canvas, chrome, lw, lh);
                    } finally {
                        canvas.restoreToCount(controls);
                    }
                }
            } finally {
                canvas.restoreToCount(save);
            }
        }
    }
}
