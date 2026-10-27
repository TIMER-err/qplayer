package dev.t1m3.qplayer.desktop.graphics;

import dev.t1m3.qplayer.bridge.ColorExtractor;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Pixmap;
import io.github.humbleui.skija.SamplingMode;

import java.nio.ByteBuffer;

/**
 * Desktop {@link ColorExtractor}, using the same Skija codecs as the player UI.
 * Picks a vibrant seed color from a cover by histogramming pixels into hue bins
 * weighted by chroma*value, then averaging the heaviest bin; near-gray, very dark
 * and blown-out pixels are skipped so the seed is a real accent, not the
 * background. Falls back to the overall average when the art has no vibrant color.
 * Called off the render thread by the controller.
 */
public final class DesktopColorExtractor implements ColorExtractor {

    private static final int SAMPLE = 32;
    private static final int HUE_BINS = 24;

    @Override
    public String dominantHex(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) return null;
        try (Image source = Image.makeFromEncoded(imageBytes); Bitmap small = new Bitmap()) {
            if (!small.allocPixels(new ImageInfo(SAMPLE, SAMPLE, ColorType.RGBA_8888,
                    ColorAlphaType.UNPREMUL, ColorSpace.getSRGB()))) return null;
            try (Pixmap pixels = small.peekPixels()) {
                if (!source.scalePixels(pixels, SamplingMode.DEFAULT, false)) return null;
                return dominant(pixels.getBuffer(), pixels.getRowBytes());
            }
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String dominant(ByteBuffer pixels, int rowBytes) {
        double[] binWeight = new double[HUE_BINS];
        double[] binR = new double[HUE_BINS];
        double[] binG = new double[HUE_BINS];
        double[] binB = new double[HUE_BINS];
        double avgR = 0, avgG = 0, avgB = 0;
        int avgN = 0;

        for (int y = 0; y < SAMPLE; y++) {
            for (int x = 0; x < SAMPLE; x++) {
                int offset = y * rowBytes + x * 4;
                int alpha = pixels.get(offset + 3) & 0xFF;
                int r = alpha == 0 ? 0 : pixels.get(offset) & 0xFF;
                int gg = alpha == 0 ? 0 : pixels.get(offset + 1) & 0xFF;
                int b = alpha == 0 ? 0 : pixels.get(offset + 2) & 0xFF;
                avgR += r; avgG += gg; avgB += b; avgN++;
                int max = Math.max(r, Math.max(gg, b));
                int min = Math.min(r, Math.min(gg, b));
                float sat = max == 0 ? 0 : (max - min) / (float) max;
                float val = max / 255.0f;
                if (sat < 0.2f || val < 0.15f || val > 0.95f) continue;
                double w = sat * val;
                float red = (max - r) / (float) (max - min);
                float green = (max - gg) / (float) (max - min);
                float blue = (max - b) / (float) (max - min);
                float hue = (r == max ? blue - green : gg == max ? 2 + red - blue : 4 + green - red) / 6;
                if (hue < 0) hue += 1;
                int bin = (int) (hue * HUE_BINS) % HUE_BINS;
                binWeight[bin] += w;
                binR[bin] += r * w; binG[bin] += gg * w; binB[bin] += b * w;
            }
        }

        int best = -1;
        for (int i = 0; i < HUE_BINS; i++) {
            if (best < 0 || binWeight[i] > binWeight[best]) best = i;
        }
        if (binWeight[best] > 0) {
            double w = binWeight[best];
            return hex((int) (binR[best] / w), (int) (binG[best] / w), (int) (binB[best] / w));
        }
        if (avgN == 0) return null;
        return hex((int) (avgR / avgN), (int) (avgG / avgN), (int) (avgB / avgN));
    }

    private static String hex(int r, int g, int b) {
        return String.format("#%02x%02x%02x", clamp(r), clamp(g), clamp(b));
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (Math.min(v, 255));
    }
}
