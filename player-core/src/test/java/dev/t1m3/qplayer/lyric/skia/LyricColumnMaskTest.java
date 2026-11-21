package dev.t1m3.qplayer.lyric.skia;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LyricColumnMaskTest {
    @Test
    public void fractionalDensityLeavesNoBrightPixelsAtEitherFadeBoundary() {
        LyricCompositor compositor = new LyricCompositor();
        Rect bounds = Rect.makeLTRB(8.25f, 11.3f, 72.75f, 91.9f);
        float fade = bounds.getHeight() * 0.2f;
        try (Paint white = new Paint().setColor(0xFFFFFFFF);
             Shader shader = Shader.makeLinearGradient(0, bounds.getTop(), 0, bounds.getBottom(),
                     new int[]{0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                     new float[]{0, 0.2f, 0.8f, 1})) {
            for (float density : new float[]{1, 1.25f, 1.5f, 2, 2.625f, 2.75f, 3.5f}) {
                try (Surface surface = Surface.makeRasterN32Premul(288, 360);
                     Bitmap pixels = new Bitmap()) {
                    Canvas canvas = surface.getCanvas();
                    canvas.clear(0);
                    int saved = canvas.save();
                    canvas.scale(density, density);
                    canvas.saveLayer(bounds, null);
                    // A glyph crossing either boundary can cover every pixel of a scanline.
                    canvas.drawPaint(white);
                    compositor.applyColumnMask(canvas, shader);
                    canvas.restoreToCount(saved);
                    pixels.allocN32Pixels(288, 360);
                    assertTrue(surface.readPixels(pixels, 0, 0));

                    int x = (int) ((bounds.getLeft() + bounds.getRight()) * 0.5f * density);
                    int middle = (int) ((bounds.getTop() + bounds.getBottom()) * 0.5f * density);
                    assertEquals("the readable plateau must remain opaque", 255,
                            pixels.getColor(x, middle) >>> 24);
                    for (int y = 0; y < 360; y++) {
                        // Use the maximum alpha anywhere inside this device pixel, allowing
                        // two quantization levels but not an unmasked antialias fringe.
                        float distance = Math.min(y + 1f - bounds.getTop() * density,
                                bounds.getBottom() * density - y);
                        int ceiling = (int) Math.ceil(255 * Math.max(0, Math.min(1,
                                distance / (fade * density))));
                        int alpha = pixels.getColor(x, y) >>> 24;
                        assertTrue("density=" + density + ", y=" + y + ", alpha=" + alpha
                                        + ", ceiling=" + ceiling,
                                alpha <= ceiling + 2);
                    }
                }
            }
        } finally {
            compositor.dispose();
        }
    }
}
