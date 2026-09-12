package dev.t1m3.qplayer.desktop.lyric.tempera;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import io.github.humbleui.skija.Paint;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class TemperaTextLayerTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 160;

    @Test
    public void shadowsDoNotChangeOpaqueLetterInteriors() throws Exception {
        verify(true, 0);
    }

    @Test
    public void echoesStayBehindOpaqueLetterInteriors() throws Exception {
        verify(false, 2);
    }

    private void verify(boolean shadows, int echoes) throws Exception {
        try {
            List<TemperaTextView.View> glyphs = List.of(glyph(135), glyph(200));
            if (echoes > 0) assertTrue(TemperaMotion.resolve(glyphs.get(0).motion, 0.6, 1).echoAlpha > 0.004f);
            byte[] reference = render(glyphs, false, 0, "plain");
            byte[] decorated = render(glyphs, shadows, echoes, shadows ? "shadow" : "echo");
            int checked = 0;
            int damaged = 0;
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int offset = (y * WIDTH + x) * 4;
                    int background = x < WIDTH / 2 ? 0 : 255;
                    boolean interior = true;
                    for (int channel = 0; channel < 3; channel++) {
                        if (Math.abs((reference[offset + channel] & 255) - background) < 255) {
                            interior = false;
                        }
                    }
                    if (!interior) continue;
                    checked++;
                    for (int channel = 0; channel < 3; channel++) {
                        if (Math.abs((reference[offset + channel] & 255)
                                - (decorated[offset + channel] & 255)) > 0) {
                            damaged++;
                            break;
                        }
                    }
                }
            }
            assertTrue("Must inspect actual opaque letter pixels", checked > 500);
            assertEquals("Decorations changed letter interiors", 0, damaged);
        } finally {
            TemperaTextView.dispose();
            TemperaMeasure.dispose();
            TemperaFonts.dispose();
        }
    }

    private byte[] render(List<TemperaTextView.View> glyphs, boolean shadows, int echoes,
                          String name) throws Exception {
        try (Surface surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT);
             Paint white = new Paint().setColor(0xFFFFFFFF)) {
            surface.getCanvas().clear(0xFF000000);
            surface.getCanvas().drawRect(Rect.makeXYWH(WIDTH / 2f, 0, WIDTH / 2f, HEIGHT), white);
            TemperaTextView.paint(surface.getCanvas(), glyphs, 0.6, 1, echoes, shadows, true);
            try (Image image = surface.makeImageSnapshot(); Data png = image.encodeToData()) {
                Path output = Path.of("target", "tempera-layer");
                Files.createDirectories(output);
                Files.write(output.resolve(name + ".png"), png.getBytes());
                ByteBuffer pixels = image.peekPixels();
                assertNotNull(pixels);
                byte[] bytes = new byte[pixels.remaining()];
                pixels.get(bytes);
                return bytes;
            }
        }
    }

    private TemperaTextView.View glyph(float x) {
        TemperaTextView.View glyph = new TemperaTextView.View();
        glyph.charText = "M";
        glyph.fontWeight = 600;
        glyph.fontSize = 96;
        glyph.baseX = x;
        glyph.baseY = HEIGHT / 2f;
        glyph.displayColor = "#FFFFFF";
        glyph.shadowColor = "#FFFFFF";
        glyph.echoColor = "#706080";
        glyph.shadowDX = -16;
        glyph.shadowDY = 8;
        glyph.motion.startTime = 0;
        glyph.motion.settleTime = 3;
        glyph.motion.endTime = 10;
        glyph.motion.releaseTime = 12;
        glyph.motion.enterScale = 1;
        return glyph;
    }
}
