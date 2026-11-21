package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.Syllable;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Typeface;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class LyricResourceOwnershipTest {
    @Test
    public void repeatedScriptFallbackChecksDoNotRetainNativeTypefaceReferences() {
        try (Typeface face = FontMgr.getDefault().matchFamilyStyle("sans-serif", FontStyle.NORMAL);
             Font font = new Font(face, 32f)) {
            assertNotNull(face);
            // Resolve long-lived fallback faces/fonts before taking the baseline.
            checkFallbacks(font);
            int baseline = face.getRefCount();
            for (int i = 0; i < 200; i++) checkFallbacks(font);
            assertEquals("temporary getTypeface() references must be closed without GC",
                    baseline, face.getRefCount());
        }
    }

    private static void checkFallbacks(Font font) {
        Fonts.korean(font);
        Fonts.thai(font);
        Fonts.japanese(font);
    }

    @Test
    public void closingPageReleasesTheBuildersLastRasterAndGlowChildren() {
        assertRasterOwnershipReleased(false);
    }

    @Test
    public void replacingLayoutReleasesTheBuildersLastRasterAndGlowChildren() {
        assertRasterOwnershipReleased(true);
    }

    @Test
    public void supersededAndPostDisposalDecodeResultsReleaseTheirNativeImages() throws Exception {
        FluidBackground background = new FluidBackground(0);
        boolean disposed = false;
        Class<?> texturesClass = Class.forName(FluidBackground.class.getName() + "$DecodedTextures");
        Constructor<?> constructor = texturesClass.getDeclaredConstructor(
                String.class, Image.class, Image.class, float[].class, AMLLMeshGradient.Data.class);
        constructor.setAccessible(true);
        Method publish = FluidBackground.class.getDeclaredMethod("publishDecoded", texturesClass);
        publish.setAccessible(true);
        try (Image firstRaw = pixel(); Image firstAdjusted = pixel();
             Image nextRaw = pixel(); Image nextAdjusted = pixel();
             Image lateRaw = pixel(); Image lateAdjusted = pixel();
             Image firstRawObserver = observe(firstRaw); Image firstAdjustedObserver = observe(firstAdjusted);
             Image nextRawObserver = observe(nextRaw); Image nextAdjustedObserver = observe(nextAdjusted);
             Image lateRawObserver = observe(lateRaw); Image lateAdjustedObserver = observe(lateAdjusted)) {
            Object first = constructor.newInstance("first", firstRaw, firstAdjusted, new float[4], null);
            Object next = constructor.newInstance("next", nextRaw, nextAdjusted, new float[4], null);
            Object late = constructor.newInstance("late", lateRaw, lateAdjusted, new float[4], null);
            publish.invoke(background, first);
            publish.invoke(background, next);
            assertEquals(1, firstRawObserver.getRefCount());
            assertEquals(1, firstAdjustedObserver.getRefCount());
            background.dispose();
            disposed = true;
            assertEquals(1, nextRawObserver.getRefCount());
            assertEquals(1, nextAdjustedObserver.getRefCount());
            // Deterministically exercise a worker finishing after shutdownNow().
            publish.invoke(background, late);
            assertEquals(1, lateRawObserver.getRefCount());
            assertEquals(1, lateAdjustedObserver.getRefCount());
        } finally {
            if (!disposed) background.dispose();
        }
    }

    private static Image pixel() {
        return Image.makeRasterFromBytes(ImageInfo.makeN32Premul(1, 1), new byte[4], 4);
    }

    private static Image observe(Image image) {
        try (Shader shader = image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                SamplingMode.LINEAR, Matrix33.IDENTITY)) {
            return shader.getImage();
        }
    }

    private static void assertRasterOwnershipReleased(boolean replaceLayout) {
        List<Syllable> syllables = Collections.singletonList(new Syllable("Held", 0, 4000));
        try (Typeface face = FontMgr.getDefault().matchFamilyStyle("sans-serif", FontStyle.NORMAL);
             Font font = new Font(face, 32f);
             Surface surface = Surface.makeRasterN32Premul(400, 150);
             LyricTextShaper shaper = new LyricTextShaper();
             LyricRowRenderer renderer = new LyricRowRenderer()) {
            LyricSkia.setCanvas(surface.getCanvas());
            LyricTextShaper.ShapedRow row = shaper.shapeMainRow(syllables, 0, 1, font);
            try {
                renderer.drawRow(syllables, row, 30, 70,
                        font.getMetrics().getAscent(), font.getMetrics().getDescent(),
                        2000, 1f, 1f, true, false, true, false, true);
                assertNotNull("the real shader path must have rasterized the row", row.highResImageShader);
                assertNotNull("the held word must have produced a glow raster", row.glowImageShader);
                // Independent owning wrappers keep each image observable after all
                // production owners release it; a refcount of one proves no hidden
                // native builder/Paint child is retaining those pixel buffers.
                try (Image content = row.highResImageShader.getImage();
                     Image glow = row.glowImageShader.getImage()) {
                    if (replaceLayout) {
                        row.close();
                        row = null;
                        renderer.clearRasterCache();
                    } else {
                        renderer.releaseRasterCache();
                    }
                    assertEquals("only the test observer may own the content raster", 1, content.getRefCount());
                    assertEquals("only the test observer may own the glow raster", 1, glow.getRefCount());
                }
            } finally {
                if (row != null) row.close();
                LyricSkia.setCanvas(null);
            }
        }
    }
}
