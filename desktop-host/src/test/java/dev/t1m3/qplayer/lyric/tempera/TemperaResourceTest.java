package dev.t1m3.qplayer.lyric.tempera;

import dev.t1m3.qplayer.lyric.skia.Fonts;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class TemperaResourceTest {
    @BeforeClass
    public static void initializeFonts() {
        TemperaTestFonts.initialize();
    }

    @Test public void fontEvictionAndDisposalPreserveRenderedGlyphsAndBorrowedFonts() {
        Font borrowed = Fonts.get(Fonts.Weight.MEDIUM, 64f);
        TemperaLayout.GlyphPlacement placement = new TemperaLayout.GlyphPlacement();
        placement.charText = "汉";
        placement.x = 50;
        placement.y = 70;
        placement.fontSize = 33.137f;
        placement.startTime = 0;
        placement.settleTime = 1;
        placement.endTime = 2;
        placement.releaseTime = 3;
        placement.enterScale = 1;
        TemperaPalette.Palette palette = new TemperaPalette.Palette();
        palette.ink = "#112233";
        palette.tone4 = "#445566";
        List<TemperaTextView.View> views = TemperaTextView.build(
                Collections.singletonList(placement), palette, 900, true, 2);
        byte[] borrowedBefore;
        try (Surface surface = Surface.makeRasterN32Premul(140, 140)) {
            byte[] before = render(surface, views);
            TemperaPalette.Palette other = new TemperaPalette.Palette();
            other.ink = "#AABBCC";
            other.tone4 = "#DDEEFF";
            List<TemperaTextView.View> recolored = TemperaTextView.build(
                    Collections.singletonList(placement), other, 900, true, 2);
            assertFalse("the visible glyph must respond to the requested palette",
                    Arrays.equals(before, render(surface, recolored)));
            borrowedBefore = renderBorrowed(surface, borrowed);
            for (int i = 0; i < 1500; i++) {
                TemperaMeasure.fontFor(900, 20.123f + i * 0.017f, "汉");
                TemperaMeasure.fontFor(400, 19.321f + i * 0.013f, "汉");
            }
            assertArrayEquals("evicted text lines must be rebuilt without changing pixels",
                    before, render(surface, views));
        } finally {
            TemperaTextView.dispose();
            TemperaMeasure.dispose();
            TemperaFonts.dispose();
        }
        try (Surface surface = Surface.makeRasterN32Premul(140, 140)) {
            assertArrayEquals("closing Tempera must not invalidate a shared font",
                    borrowedBefore, renderBorrowed(surface, borrowed));
        }
    }

    private static byte[] render(Surface surface, List<TemperaTextView.View> views) {
        surface.getCanvas().clear(0);
        TemperaTextView.paint(surface.getCanvas(), views, 1.5, 1f, 2, true, false);
        return pixels(surface);
    }

    private static byte[] renderBorrowed(Surface surface, Font font) {
        surface.getCanvas().clear(0);
        try (Paint paint = new Paint()) {
            paint.setColor(0xFF334455);
            surface.getCanvas().drawString("汉", 40, 90, font, paint);
        }
        return pixels(surface);
    }

    private static byte[] pixels(Surface surface) {
        try (Image image = surface.makeImageSnapshot(); Data encoded = image.encodeToData()) {
            return encoded.getBytes();
        }
    }
}
