package dev.t1m3.qplayer.bridge;

/**
 * Derives a representative seed color from album-cover image bytes, used to drive
 * the Material You (Monet) dynamic color scheme. Implemented in the platform layer
 * (Android decodes the bitmap); player-core only knows the abstraction.
 */
public interface ColorExtractor {

    /** Dominant/vibrant color of the image as a {@code "#rrggbb"} string, or null
     *  if it cannot be derived. Called off the render thread. */
    String dominantHex(byte[] imageBytes);
}
