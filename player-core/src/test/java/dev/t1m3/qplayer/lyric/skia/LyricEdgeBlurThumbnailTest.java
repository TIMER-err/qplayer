package dev.t1m3.qplayer.lyric.skia;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * The edge blur now runs on a 1/3-resolution thumbnail of the lyric column and
 * is scaled back up (HyperOS-style 缩略图 blur). These assert that the swap did
 * not change what the page looks like: the blurred copy must stay within a
 * couple of levels of the old full-resolution gaussian, and it must not lose ink
 * — a blur that dims thin strokes reads as flicker while scrolling.
 */
public class LyricEdgeBlurThumbnailTest {

    private static final int W = 360, H = 480;
    /** fontSize 40 * EDGE_BLUR_SIGMA_RATIO, i.e. a typical column. */
    private static final float SIGMA = 4.4f;

    private static byte[] renderBlurred(ImageFilter filter) {
        try (Surface surface = Surface.makeRasterN32Premul(W, H);
             Bitmap pixels = new Bitmap();
             Paint layer = new Paint().setImageFilter(filter);
             Paint ink = new Paint().setColor(0xFFFFFFFF).setAntiAlias(true)) {
            Canvas canvas = surface.getCanvas();
            canvas.clear(0);
            int saved = canvas.saveLayer(Rect.makeWH(W, H), layer);
            // Glyph-like high-frequency content: thin vertical bars of the stroke
            // widths a lyric column actually contains, plus thicker blobs.
            for (int row = 0; row < 7; row++) {
                float y = 24f + row * 64f;
                float height = 26f;
                for (int x = 8; x < W - 8; x += 11) {
                    float barWidth = ((x / 11) % 3 == 0) ? 2.5f : 5f;
                    canvas.drawRect(Rect.makeXYWH(x, y, barWidth, height), ink);
                }
                canvas.drawRect(Rect.makeXYWH(8, y + height + 4f, W - 16f, 2f), ink);
            }
            canvas.restoreToCount(saved);
            pixels.allocN32Pixels(W, H);
            assertTrue(surface.readPixels(pixels, 0, 0));
            return pixels.readPixels();
        }
    }

    @Test
    public void thumbnailBlurMatchesTheFullResolutionGaussian() {
        byte[] plain;
        byte[] thumb;
        try (ImageFilter full = ImageFilter.makeBlur(SIGMA, SIGMA, FilterTileMode.DECAL);
             ImageFilter chain = LyricCompositor.edgeBlurFilter(SIGMA)) {
            plain = renderBlurred(full);
            thumb = renderBlurred(chain);
        }

        long diffSum = 0;
        long plainInk = 0;
        long thumbInk = 0;
        int maxDiff = 0;
        int count = W * H;
        for (int i = 0; i < count; i++) {
            int pi = plain[i * 4 + 3] & 0xFF; // premultiplied, white ink -> alpha is the ink
            int ti = thumb[i * 4 + 3] & 0xFF;
            int d = Math.abs(pi - ti);
            diffSum += d;
            if (d > maxDiff) maxDiff = d;
            plainInk += pi;
            thumbInk += ti;
        }
        double meanDiff = diffSum / (double) count;
        double inkRatio = thumbInk / (double) plainInk;

        assertTrue("mean |diff| per channel was " + meanDiff, meanDiff < 2.0);
        assertTrue("peak alpha diff was " + maxDiff, maxDiff < 48);
        assertTrue("ink ratio was " + inkRatio, inkRatio > 0.97 && inkRatio < 1.03);
    }
}
