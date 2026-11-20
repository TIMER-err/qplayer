package dev.t1m3.qplayer.lyric.skia;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import io.github.humbleui.types.RRect;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * The liquid-glass outer and inner shadows now run on a 1/2 thumbnail of the mask
 * (see {@link ThumbnailBlur}) instead of a full-resolution gaussian. A shadow is pure
 * low-frequency content, so this must be invisible: these assert the blurred result
 * stays within a level of the old filter and does not lose ink.
 */
public class ShadowBlurThumbnailTest {

    private static final int W = 400, H = 160;

    private static byte[] render(ImageFilter filter) {
        try (Surface surface = Surface.makeRasterN32Premul(W, H);
             Bitmap px = new Bitmap();
             Paint layer = new Paint().setImageFilter(filter);
             Paint ink = new Paint().setColor(0xFF000000).setAlphaf(0.2f).setAntiAlias(true)) {
            Canvas c = surface.getCanvas();
            c.clear(0);
            int saved = c.saveLayer(Rect.makeWH(W, H), layer);
            // The capsule shape a glass panel's shadow mask uses.
            c.drawRRect(RRect.makeXYWH(40, 40, W - 80f, H - 80f, 24f), ink);
            c.restoreToCount(saved);
            px.allocN32Pixels(W, H);
            assertTrue(surface.readPixels(px, 0, 0));
            return px.readPixels();
        }
    }

    private static void assertEquivalent(String label, float sigma) {
        byte[] plain;
        byte[] thumb;
        try (ImageFilter full = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.DECAL);
             ImageFilter chain = ThumbnailBlur.gaussian(sigma)) {
            plain = render(full);
            thumb = render(chain);
        }
        long diffSum = 0, plainInk = 0, thumbInk = 0;
        int maxDiff = 0;
        for (int i = 0; i < W * H; i++) {
            int pi = plain[i * 4 + 3] & 0xFF;
            int ti = thumb[i * 4 + 3] & 0xFF;
            int d = Math.abs(pi - ti);
            diffSum += d;
            if (d > maxDiff) maxDiff = d;
            plainInk += pi;
            thumbInk += ti;
        }
        double meanDiff = diffSum / (double) (W * H);
        double inkRatio = thumbInk / (double) plainInk;
        System.out.printf("%s sigma=%.2f  mean=%.3f/255  peak=%d/255  ink=%.4f%n",
                label, sigma, meanDiff, maxDiff, inkRatio);
        assertTrue(label + " mean |diff| was " + meanDiff, meanDiff < 1.0);
        assertTrue(label + " peak alpha diff was " + maxDiff, maxDiff < 12);
        assertTrue(label + " ink ratio was " + inkRatio, inkRatio > 0.96 && inkRatio < 1.02);
    }

    @Test
    public void outerShadowMatchesTheFullResolutionGaussian() {
        // 10 dp elevation at a 2x panel: the sigma the glass panel actually uses.
        assertEquivalent("outer", 10f * 2f * 0.57735f + 0.5f);
    }

    @Test
    public void innerShadowMatchesTheFullResolutionGaussian() {
        // The smallest inner-shadow radius the panel reaches (radius 8 dp * p, p small).
        assertEquivalent("inner-small", 2.3f);
        assertEquivalent("inner-large", 16f);
    }
}
