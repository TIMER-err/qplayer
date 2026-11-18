package dev.t1m3.qplayer.lyric.tempera;

import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;

final class TemperaTestSurface {
    private TemperaTestSurface() { }

    /** These assertions read B, G, R, A bytes. Skia's native N32 order varies by platform. */
    static Surface makeBgra(int width, int height) {
        return Surface.makeRaster(new ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL));
    }
}
