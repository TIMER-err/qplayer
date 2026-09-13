package dev.t1m3.qplayer.lyric.tempera;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TemperaTextContrastTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 160;

    @Test
    public void invertedTextRemainsVisibleOnDarkAndLightArtworkInEitherTheme() throws Exception {
        Path output = Path.of("target", "tempera-contrast");
        Files.createDirectories(output);
        try {
            for (boolean darkTheme : new boolean[]{true, false}) {
                TemperaPalette.Palette palette = TemperaPalette.resolve(new TemperaPalette.Theme(
                        darkTheme ? "#0B0B10" : "#F2F0E8", "#8C86A8", "#6A6470", "#B9B3CC"), "duo");
                for (int background : new int[]{0xFF000000, 0xFFFFFFFF}) {
                    for (String kind : new String[]{"lyric", "title", "fragment"}) {
                        try (Surface surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT)) {
                            Canvas canvas = surface.getCanvas();
                            canvas.clear(background);
                            draw(canvas, palette, kind);
                            String name = (darkTheme ? "dark" : "light") + "-"
                                    + (background == 0xFF000000 ? "black" : "white") + "-" + kind;
                            try (Image image = surface.makeImageSnapshot(); Data png = image.encodeToData()) {
                                Files.write(output.resolve(name + ".png"), png.getBytes());
                                ByteBuffer pixels = image.peekPixels();
                                assertNotNull(pixels);
                                int contrasted = 0;
                                int channel = background & 255;
                                while (pixels.remaining() >= 4) {
                                    int b = pixels.get() & 255;
                                    int g = pixels.get() & 255;
                                    int r = pixels.get() & 255;
                                    pixels.get();
                                    if ((Math.abs(r - channel) + Math.abs(g - channel)
                                            + Math.abs(b - channel)) / 3 > 80) contrasted++;
                                }
                                assertTrue(name + ": readable glyph pixels = " + contrasted, contrasted > 250);
                            }
                        }
                    }
                }
            }
        } finally {
            TemperaTextView.dispose();
            TemperaMeasure.dispose();
            TemperaFonts.dispose();
        }
    }

    private static void draw(Canvas canvas, TemperaPalette.Palette palette, String kind) {
        if ("lyric".equals(kind)) {
            TemperaTextView.View glyph = new TemperaTextView.View();
            glyph.charText = "M";
            glyph.fontWeight = 600;
            glyph.fontSize = 96;
            glyph.baseX = WIDTH / 2f;
            glyph.baseY = HEIGHT / 2f;
            glyph.displayColor = palette.ink;
            glyph.motion.startTime = 0;
            glyph.motion.settleTime = 0.5;
            glyph.motion.endTime = 10;
            glyph.motion.releaseTime = 12;
            glyph.motion.enterScale = 1;
            TemperaTextView.paint(canvas, List.of(glyph), 2, 0, 0, false, true);
        } else if ("title".equals(kind)) {
            TemperaTextView.drawFittedLine(canvas, "Tempera", 600, 72, WIDTH - 24,
                    palette.paper, 1, WIDTH / 2f, HEIGHT / 2f, true);
        } else {
            TemperaTypes.DecorFragment fragment =
                    new TemperaTypes.DecorFragment("M", 0.5f, 0.5f, 0f, 1f);
            TemperaTextView.drawFragment(canvas, fragment, palette, 600, 96, WIDTH, HEIGHT, true);
        }
    }
}
