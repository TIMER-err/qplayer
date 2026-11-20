package dev.t1m3.qplayer.lyric.skia;

import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.Rect;

/**
 * HyperOS-style 缩略图 blur: run the gaussian on a 1/{@link #SCALE}-resolution thumbnail and
 * scale the result back up, as one Skia ImageFilter chain (downscale → blur → upscale) so no
 * Surface has to be managed by the caller.
 *
 * <p>Why this is cheaper than it looks: a gaussian's cost scales with the pixel count
 * <em>and</em> with sigma, so blurring at 1/N touches 1/N² of the pixels with a sigma that is
 * also 1/N — ~N³ fewer texel fetches for the same apparent radius — and the full-resolution
 * blur intermediate disappears entirely.
 *
 * <p>Why N is 2 and not larger: linear sampling at exactly 1/2 is a proper 2×2 box average, so
 * the thumbnail carries no aliasing into the upscale. N=3/4 subsample the source between texels
 * and lose ink on thin strokes (measured: 1/3 dropped 24% of a glyph column's ink, which reads
 * as the blurred text dimming). For soft, large-radius shadows N=4 measured fine as well
 * (0.53/255 mean, peak 3/255) at half the cost of N=2, but text and shadows share this code
 * path deliberately: one factor, one rule to remember, and the text case is the one that breaks.
 */
public final class ThumbnailBlur {

    /** Thumbnail downscale factor. See the class comment before changing it. */
    public static final float SCALE = 2f;

    private ThumbnailBlur() {
    }

    /** Gaussian of {@code sigma} device pixels, computed on a 1/{@link #SCALE} thumbnail. */
    public static ImageFilter gaussian(float sigma) {
        return gaussian(sigma, SCALE);
    }

    /** Gaussian of {@code sigma} device pixels computed on a 1/{@code scale} thumbnail. */
    public static ImageFilter gaussian(float sigma, float scale) {
        float thumbSigma = Math.max(0.4f, sigma / scale);
        ImageFilter down = ImageFilter.makeMatrixTransform(
                Matrix33.makeScale(1f / scale), SamplingMode.LINEAR, null);
        ImageFilter blurred = ImageFilter.makeBlur(
                thumbSigma, thumbSigma, FilterTileMode.DECAL, down, (Rect) null);
        return ImageFilter.makeMatrixTransform(
                Matrix33.makeScale(scale), SamplingMode.LINEAR, blurred);
    }
}
