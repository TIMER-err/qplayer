package dev.t1m3.qplayer.lyric.tempera;

import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.Typeface;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TemperaResourceOwnershipTest {
    @Test
    public void scaledFontEvictionAndDisposalReleaseAllNativeTypefaceReferences() {
        TemperaFonts.dispose();
        try (Typeface face = FontMgr.getDefault().matchFamilyStyle("sans-serif", FontStyle.NORMAL);
             Font template = new Font(face, 64f)) {
            int baseline = face.getRefCount();
            for (int size = 1; size <= 300; size++) TemperaFonts.scaled(template, size);
            TemperaFonts.dispose();
            assertEquals("no temporary makeWithSize/getTypeface references may survive pool disposal",
                    baseline, face.getRefCount());
        } finally {
            TemperaFonts.dispose();
        }
    }

    @Test
    public void repeatedHanCoverageChecksReleaseTheirTemporaryTypefaceReferences() {
        TemperaFonts.dispose();
        Font template = TemperaFonts.template(700);
        try (Typeface face = template.getTypeface()) {
            assertNotNull(face);
            TemperaMeasure.fontFor(700, 32f, "汉");
            int baseline = face.getRefCount();
            for (int i = 0; i < 200; i++) TemperaMeasure.fontFor(700, 32f, "汉");
            assertEquals("coverage checks must not defer native reference release to GC",
                    baseline, face.getRefCount());
        } finally {
            TemperaFonts.dispose();
        }
    }
}
