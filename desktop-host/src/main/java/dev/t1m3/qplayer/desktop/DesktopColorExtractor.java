package dev.t1m3.qplayer.desktop;

import dev.t1m3.qplayer.bridge.ColorExtractor;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * Desktop {@link ColorExtractor}: the AWT mirror of {@code AndroidColorExtractor}.
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
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (Exception e) {
            return null;
        }
        if (src == null) return null;
        BufferedImage small = new BufferedImage(SAMPLE, SAMPLE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = small.createGraphics();
        g.drawImage(src, 0, 0, SAMPLE, SAMPLE, null);
        g.dispose();

        double[] binWeight = new double[HUE_BINS];
        double[] binR = new double[HUE_BINS];
        double[] binG = new double[HUE_BINS];
        double[] binB = new double[HUE_BINS];
        double avgR = 0, avgG = 0, avgB = 0;
        int avgN = 0;

        float[] hsv = new float[3];
        for (int y = 0; y < SAMPLE; y++) {
            for (int x = 0; x < SAMPLE; x++) {
                int p = small.getRGB(x, y);
                int r = (p >> 16) & 0xFF, gg = (p >> 8) & 0xFF, b = p & 0xFF;
                avgR += r; avgG += gg; avgB += b; avgN++;
                Color.RGBtoHSB(r, gg, b, hsv);
                float sat = hsv[1], val = hsv[2];
                if (sat < 0.2f || val < 0.15f || val > 0.95f) continue;
                double w = sat * val;
                int bin = (int) (hsv[0] * HUE_BINS) % HUE_BINS;
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
