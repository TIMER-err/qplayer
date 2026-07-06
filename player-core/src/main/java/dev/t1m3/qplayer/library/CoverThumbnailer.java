package dev.t1m3.qplayer.library;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.FilterMipmap;
import io.github.humbleui.skija.FilterMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.MipmapMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;

/**
 * Decode an embedded/sidecar cover once and re-encode downscaled JPEG copies sized
 * for on-screen use. A local library stores multi-megapixel embedded art; without
 * this, every list row that scrolls into view decodes the full image on the render
 * thread (a per-frame hitch), and the retained texture dwarfs its 48px box.
 */
final class CoverThumbnailer {

    private CoverThumbnailer() {
    }

    /**
     * Decode {@code encoded} once and return one JPEG copy per requested max edge (same
     * order). A size whose max edge already covers the source is returned re-encoded at
     * that scale (never upscaled). Returns null if the bytes can't be decoded — the
     * caller then falls back to storing the original bytes.
     */
    static byte[][] downscale(byte[] encoded, int... maxEdges) {
        if (encoded == null || encoded.length == 0) return null;
        Image full = null;
        try {
            full = Image.makeFromEncoded(encoded);
            int iw = full.getWidth();
            int ih = full.getHeight();
            if (iw <= 0 || ih <= 0) return null;
            int longest = Math.max(iw, ih);
            byte[][] out = new byte[maxEdges.length][];
            for (int i = 0; i < maxEdges.length; i++) {
                out[i] = encodeScaled(full, iw, ih, Math.min(1f, (float) maxEdges[i] / longest));
            }
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (full != null) full.close();
        }
    }

    private static byte[] encodeScaled(Image full, int iw, int ih, float f) {
        int tw = Math.max(1, Math.round(iw * f));
        int th = Math.max(1, Math.round(ih * f));
        // Trilinear (mipmap) for the one-time shrink: box-averaged mip levels antialias
        // the downscale cleanly where a single bilinear pass would skip source pixels.
        try (Surface surf = Surface.makeRasterN32Premul(tw, th)) {
            surf.getCanvas().drawImageRect(full,
                    Rect.makeXYWH(0, 0, iw, ih), Rect.makeXYWH(0, 0, tw, th),
                    new FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true);
            try (Image scaled = surf.makeImageSnapshot();
                 Data data = scaled.encodeToData(EncodedImageFormat.JPEG)) {
                return data == null ? null : data.getBytes();
            }
        }
    }
}
