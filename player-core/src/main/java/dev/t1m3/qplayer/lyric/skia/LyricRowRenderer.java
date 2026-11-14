package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LyricTimeline;
import dev.t1m3.qplayer.lyric.Syllable;
import dev.t1m3.qplayer.lyric.skia.LyricTextShaper.ShapedRow;
import dev.t1m3.qplayer.lyric.skia.LyricTextShaper.ShapedText;
import dev.t1m3.qplayer.lyric.skia.LyricTextShaper.WordSpan;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.FilterBlurMode;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.MaskFilter;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.RuntimeEffectBuilder;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.TextBlob;
import io.github.humbleui.skija.impl.Managed;
import io.github.humbleui.skija.impl.Native;
import io.github.humbleui.skija.impl.RefCnt;
import io.github.humbleui.types.Rect;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

/**
 * Owns reusable Skia resources and effects used to paint shaped lyric rows.
 *
 * <p>The active row moves as one continuous vertical displacement field: the
 * karaoke edge brightens sung text, every sung syllable floats up (neighbouring
 * rises cross-fade instead of stepping), and a word held at least
 * {@link LyricMotion#RIBBON_MIN_MS} carries a ribbon crest that rides the karaoke
 * edge across it, with a soft halo on its sung part. Distances are fractions of
 * the row's font size so every lyric size reads alike.
 */
final class LyricRowRenderer implements AutoCloseable {

    private static final float RISE_EM = 0.05f;
    private static final float RISE_BLEND_EM = 0.09f;
    private static final float RIBBON_EM = 0.055f;
    private static final float RIBBON_EDGE_EM = 0.06f;
    private static final float RIBBON_MIN_HALF_WIDTH_EM = 0.55f;
    private static final float RIBBON_MAX_HALF_WIDTH_EM = 2.2f;
    private static final float GLOW_ALPHA = 0.55f;
    private static final float GLOW_SIGMA_EM = 0.12f;
    private static final float FEATHER_EM = 0.4f;
    private static final float MIN_FEATHER_PX = 10f;
    private static final float DARK_MASK_ALPHA = 0.36f;
    private static final long RISE_MIN_DURATION_MS = 1000L;
    private static final double RISE_OMEGA0 = 3.7416574;
    private static final double RISE_ZETA = 0.935414;
    private static final float TEXT_SHADOW_OFFSET_Y = 2f;
    private static final float TEXT_SHADOW_ALPHA = 0.48f;
    private static final float ROW_EDGE_PX = 8f;
    private static final int MAX_SEGMENTS = 128;
    private static final int MAX_RIBBONS = 16;
    private static final float TEXT_SUPERSAMPLE = 2f;
    private static final int MAX_HIGH_RES_ROWS = 12;
    private static final String ROW_SHADER_RESOURCE = "/shaders/lyric/lyric_row.sksl";

    private final Paint lyricLayerPaint = new Paint();
    private final Paint sweepPaint = new Paint();
    private final Paint textShadowPaint = newTextShadowPaint();
    private final Rect sweepBigRect = Rect.makeLTRB(-100000f, -100000f, 100000f, 100000f);
    private final float[] segmentUniforms = new float[MAX_SEGMENTS * 4];
    private final float[] ribbonUniforms = new float[MAX_RIBBONS * 4];
    private final float[] ribbonShapeUniforms = new float[MAX_RIBBONS * 4];
    private final ArrayDeque<ShapedRow> highResolutionRows = new ArrayDeque<>();
    private float[] syllableLefts = new float[0];
    private float[] lifts = new float[0];
    private Shader sweepShader;
    private float sweepShaderDark = Float.NaN;
    private Shader emptyShader;
    private RuntimeEffect rowEffect;
    private RuntimeEffectBuilder rowBuilder;
    private boolean rowEffectUnavailable;

    /** @param align 0 left, 0.5 centred, 1 right — within the block the main rows
     *               actually occupy ({@code blockLeftX}..{@code blockRightX}), not
     *               the column, so a translation stays under its own line. */
    void drawSubLine(ShapedText text, float blockLeftX, float blockRightX, float y,
                     float alpha, float align, boolean shadowOn) {
        if (text == null || text.blob == null) return;
        float x = blockLeftX + (blockRightX - blockLeftX - text.width) * align;
        if (shadowOn) drawTextShadow(text.blob, x, y, alpha);
        Paint paint = LyricSkia.scratchPaint();
        paint.setColor(0xFFE6E6E6);
        paint.setAlphaf(alpha);
        paint.setAntiAlias(true);
        LyricSkia.getCanvas().drawTextBlob(text.blob, x, y, paint);
    }

    void drawRow(List<Syllable> syllables, ShapedRow row,
                 float startX, float baselineY, float ascent, float descent, long positionMs,
                 float baseAlpha, float activeK, boolean animatePerToken, boolean spring,
                 boolean glowOn, boolean shadowOn, boolean perSyllableSource) {
        if (row == null || row.blob == null || row.from >= row.to || row.width <= 0f) return;
        Canvas canvas = LyricSkia.getCanvas();
        if (activeK <= 0.001f || !animatePerToken) {
            drawStaticRow(canvas, row.blob, startX, baselineY, baseAlpha, shadowOn);
            return;
        }

        int count = row.to - row.from;
        if (syllableLefts.length < count + 1) syllableLefts = new float[count + 1];
        for (int i = 0; i <= count; i++) syllableLefts[i] = startX + row.syllableX[i];
        float feather = Math.max(MIN_FEATHER_PX, FEATHER_EM * row.em);
        float sweepX = computeSweepX(syllables, row.from, row.to, syllableLefts, positionMs, feather);
        float maskDark = 1f - (1f - DARK_MASK_ALPHA) * activeK;

        if (lifts.length < count) lifts = new float[count];
        for (int i = 0; i < count; i++) {
            float k = syllableRise(syllables.get(row.from + i), positionMs, spring);
            lifts[i] = -RISE_EM * row.em * k * activeK;
        }

        float pad = rasterPad(row.em);
        float margin = motionMargin(row.em);
        lyricLayerPaint.setAlphaf(baseAlpha);
        canvas.saveLayer(startX - pad - margin, baselineY + ascent - pad - margin,
                startX + row.width + pad + margin, baselineY + descent + pad + margin,
                lyricLayerPaint);
        try {
            if (rowBuilder() == null) {
                drawStaticRow(canvas, row.blob, startX, baselineY, 1f, shadowOn);
                applySweepMask(canvas, sweepX, feather, maskDark);
                return;
            }
            ensureHighResolutionRaster(row, ascent, descent, shadowOn);
            long held = glowOn && perSyllableSource ? heldWords(row, syllables) : 0L;
            ensureGlowRaster(row, held);
            long shader = makeRowShader(row, syllables, startX, baselineY, count, held,
                    positionMs, activeK, sweepX, feather);
            if (shader == 0L) {
                drawStaticRow(canvas, row.blob, startX, baselineY, 1f, shadowOn);
                applySweepMask(canvas, sweepX, feather, maskDark);
                return;
            }
            try {
                Paint textPaint = LyricSkia.scratchPaint();
                setShader(textPaint, shader);
                try {
                    textPaint.setAlphaf(1f);
                    textPaint.setAntiAlias(true);
                    drawRect(canvas, startX + row.rasterLeft - margin,
                            baselineY + row.rasterTop - margin,
                            startX + row.rasterLeft + row.rasterWidth + margin,
                            baselineY + row.rasterTop + row.rasterHeight + margin, textPaint);
                } finally {
                    setShader(textPaint, 0L);
                }
            } finally {
                Managed._nInvokeFinalizer(RefCnt._FinalizerHolder.PTR, shader);
            }
            applySweepMask(canvas, sweepX, feather, maskDark);
        } finally {
            canvas.restore();
        }
    }

    void clearRasterCache() {
        highResolutionRows.clear();
    }

    private void drawStaticRow(Canvas canvas, TextBlob blob, float x, float baselineY,
                               float alpha, boolean shadowOn) {
        Paint paint = LyricSkia.scratchPaint();
        paint.setColor(0xFFFFFFFF);
        paint.setAlphaf(alpha);
        paint.setAntiAlias(true);
        if (shadowOn) drawTextShadow(blob, x, baselineY, alpha);
        canvas.drawTextBlob(blob, x, baselineY, paint);
    }

    /** Transparent border around the cached glyphs; holds the halo's blur tail. */
    private static float rasterPad(float em) {
        return Math.max(8f, em * 0.5f);
    }

    /** Room outside the raster for lifted glyphs. */
    private static float motionMargin(float em) {
        return em * (RISE_EM + RIBBON_EM) + 4f;
    }

    private void ensureHighResolutionRaster(ShapedRow row, float ascent, float descent,
                                            boolean shadowOn) {
        if (row.highResImage != null && row.rasterWithShadow == shadowOn) {
            highResolutionRows.remove(row);
            highResolutionRows.addLast(row);
            return;
        }
        highResolutionRows.remove(row);
        row.closeRaster();
        float pad = rasterPad(row.em);
        row.rasterLeft = -pad;
        row.rasterTop = ascent - pad;
        row.rasterWidth = Math.max(1f, row.width + pad * 2f);
        row.rasterHeight = Math.max(1f, descent - ascent + pad * 2f);
        try (Surface surface = makeRasterSurface(row)) {
            Canvas raster = surface.getCanvas();
            float x = -row.rasterLeft;
            float baseline = -row.rasterTop;
            if (shadowOn) {
                textShadowPaint.setAlphaf(TEXT_SHADOW_ALPHA);
                raster.drawTextBlob(row.blob, x, baseline + TEXT_SHADOW_OFFSET_Y, textShadowPaint);
            }
            Paint glyphPaint = LyricSkia.scratchPaint();
            glyphPaint.setColor(0xFFFFFFFF);
            glyphPaint.setAlphaf(1f);
            glyphPaint.setAntiAlias(true);
            raster.drawTextBlob(row.blob, x, baseline, glyphPaint);
            row.highResImage = surface.makeImageSnapshot();
        }
        row.highResImageShader = row.highResImage.makeShader(
                FilterTileMode.DECAL, FilterTileMode.DECAL,
                SamplingMode.LINEAR, Matrix33.IDENTITY);
        row.rasterWithShadow = shadowOn;
        highResolutionRows.addLast(row);
        while (highResolutionRows.size() > MAX_HIGH_RES_ROWS) {
            ShapedRow evicted = highResolutionRows.removeFirst();
            if (evicted != row) evicted.closeRaster();
        }
    }

    private static Surface makeRasterSurface(ShapedRow row) {
        int pixelWidth = Math.max(1, (int) Math.ceil(row.rasterWidth * TEXT_SUPERSAMPLE));
        int pixelHeight = Math.max(1, (int) Math.ceil(row.rasterHeight * TEXT_SUPERSAMPLE));
        Surface surface = Surface.makeRasterN32Premul(pixelWidth, pixelHeight);
        surface.getCanvas().clear(0x00000000);
        surface.getCanvas().scale(TEXT_SUPERSAMPLE, TEXT_SUPERSAMPLE);
        return surface;
    }

    /** Words of this row held long enough for the ribbon, one bit per word index. */
    private static long heldWords(ShapedRow row, List<Syllable> syllables) {
        long mask = 0L;
        int limit = Math.min(row.words.length, 64);
        for (int w = 0; w < limit; w++) {
            WordSpan word = row.words[w];
            long start = syllables.get(row.from + word.firstSyllable).startMs;
            if (wordEnd(syllables, row, word) - start >= LyricMotion.RIBBON_MIN_MS) mask |= 1L << w;
        }
        return mask;
    }

    private static long wordEnd(List<Syllable> syllables, ShapedRow row, WordSpan word) {
        Syllable last = syllables.get(row.from + word.lastSyllable);
        return last.startMs + Math.max(0L, last.durationMs);
    }

    /** Blurred glyphs of only the held words, so their halo never lights a neighbour. */
    private void ensureGlowRaster(ShapedRow row, long held) {
        if (held == row.glowWords && (held == 0L || row.glowImage != null)) return;
        row.closeGlow();
        if (held == 0L) return;
        // The layer filter runs under the 2x raster matrix, so sigma stays logical.
        float sigma = GLOW_SIGMA_EM * row.em;
        try (Surface surface = makeRasterSurface(row);
             Paint blur = new Paint()) {
            Canvas raster = surface.getCanvas();
            blur.setImageFilter(ImageFilter.makeBlur(sigma, sigma, FilterTileMode.DECAL));
            raster.saveLayer(null, blur);
            Paint glyphPaint = LyricSkia.scratchPaint();
            glyphPaint.setColor(0xFFFFFFFF);
            glyphPaint.setAntiAlias(true);
            float x = -row.rasterLeft;
            float baseline = -row.rasterTop;
            for (int w = 0; w < Math.min(row.words.length, 64); w++) {
                if ((held & (1L << w)) == 0L) continue;
                WordSpan word = row.words[w];
                raster.save();
                clipRect(raster, x + Math.min(word.x0, word.x1), -1e4f,
                        x + Math.max(word.x0, word.x1), 1e4f);
                raster.drawTextBlob(row.blob, x, baseline, glyphPaint);
                raster.restore();
            }
            raster.restore();
            row.glowImage = surface.makeImageSnapshot();
        }
        row.glowImageShader = row.glowImage.makeShader(
                FilterTileMode.DECAL, FilterTileMode.DECAL,
                SamplingMode.LINEAR, Matrix33.IDENTITY);
        row.glowWords = held;
    }

    private long makeRowShader(ShapedRow row, List<Syllable> syllables, float startX,
                               float baselineY, int count, long held, long positionMs,
                               float activeK, float sweepX, float feather) {
        RuntimeEffectBuilder builder = rowBuilder();
        if (builder == null) return 0L;
        Arrays.fill(segmentUniforms, 0f);
        Arrays.fill(ribbonUniforms, 0f);
        Arrays.fill(ribbonShapeUniforms, 0f);
        float rowMin = startX + Math.min(row.syllableX[0], row.syllableX[count]);
        float rowMax = startX + Math.max(row.syllableX[0], row.syllableX[count]);
        int segments = 0;
        for (int i = 0; i < count && segments < MAX_SEGMENTS; i++) {
            float x0 = Math.min(syllableLefts[i], syllableLefts[i + 1]);
            float x1 = Math.max(syllableLefts[i], syllableLefts[i + 1]);
            if (x1 - x0 <= 0f) continue;
            int offset = segments * 4;
            segmentUniforms[offset] = x0 <= rowMin + 0.01f ? x0 - ROW_EDGE_PX : x0;
            segmentUniforms[offset + 1] = x1 >= rowMax - 0.01f ? x1 + ROW_EDGE_PX : x1;
            segmentUniforms[offset + 2] = lifts[i];
            segments++;
        }

        int ribbons = 0;
        float glowTotal = 0f;
        for (int w = 0; w < Math.min(row.words.length, 64) && ribbons < MAX_RIBBONS; w++) {
            if ((held & (1L << w)) == 0L) continue;
            WordSpan word = row.words[w];
            long wordStart = syllables.get(row.from + word.firstSyllable).startMs;
            float envelope = LyricMotion.ribbonEnvelope(positionMs, wordStart,
                    wordEnd(syllables, row, word)) * activeK;
            if (envelope <= 0.001f) continue;
            float x0 = startX + Math.min(word.x0, word.x1);
            float x1 = startX + Math.max(word.x0, word.x1);
            float halfWidth = Math.max(RIBBON_MIN_HALF_WIDTH_EM * row.em,
                    Math.min(RIBBON_MAX_HALF_WIDTH_EM * row.em, (x1 - x0) * 0.5f));
            int offset = ribbons * 4;
            ribbonUniforms[offset] = x0;
            ribbonUniforms[offset + 1] = x1;
            ribbonUniforms[offset + 2] = Math.max(x0, Math.min(x1, sweepX));
            ribbonUniforms[offset + 3] = halfWidth;
            ribbonShapeUniforms[offset] = -RIBBON_EM * row.em * envelope;
            ribbonShapeUniforms[offset + 1] = GLOW_ALPHA * envelope;
            ribbonShapeUniforms[offset + 2] = RIBBON_EDGE_EM * row.em;
            ribbonShapeUniforms[offset + 3] = 3f * GLOW_SIGMA_EM * row.em;
            glowTotal += envelope;
            ribbons++;
        }

        builder.setUniform("segments", segmentUniforms);
        builder.setUniform("segmentCount", segments);
        builder.setUniform("riseBlend", RISE_BLEND_EM * row.em);
        builder.setUniform("ribbons", ribbonUniforms);
        builder.setUniform("ribbonShapes", ribbonShapeUniforms);
        builder.setUniform("ribbonCount", ribbons);
        builder.setUniform("sourceOrigin", startX + row.rasterLeft, baselineY + row.rasterTop);
        builder.setUniform("sourceScale", TEXT_SUPERSAMPLE);
        builder.setUniform("sweepX", sweepX);
        builder.setUniform("sweepFeather", feather);
        builder.setChild("content", row.highResImageShader);
        builder.setChild("glow", glowTotal > 0f && row.glowImageShader != null
                ? row.glowImageShader : emptyShader());
        return RuntimeEffectBuilder._nMakeShader(Native.getPtr(builder), null);
    }

    private static float syllableRise(Syllable syllable, long positionMs, boolean spring) {
        long start = syllable.startMs;
        if (spring) return riseSpring((positionMs - start) / 1000.0);
        long duration = Math.max(RISE_MIN_DURATION_MS, Math.max(0L, syllable.durationMs));
        float progress = positionMs <= start ? 0f : positionMs >= start + duration
                ? 1f : (positionMs - start) / (float) duration;
        return 1f - (1f - progress) * (1f - progress) * (1f - progress);
    }

    private static float computeSweepX(List<Syllable> syllables, int from, int to,
                                       float[] syllableLefts, long positionMs, float feather) {
        int count = to - from;
        long firstStart = syllables.get(from).startMs;
        long lastEnd = LyricTimeline.syllableEndMs(syllables, to - 1);
        if (positionMs < firstStart) return syllableLefts[0] - feather * 2f;
        if (positionMs >= lastEnd) return syllableLefts[count] + feather * 2f;
        for (int i = 0; i < count; i++) {
            Syllable syllable = syllables.get(from + i);
            long start = syllable.startMs;
            long end = LyricTimeline.syllableEndMs(syllables, from + i);
            if (positionMs < start) return syllableLefts[i];
            if (positionMs < end) {
                float progress = (positionMs - start) / (float) (end - start);
                return syllableLefts[i] + (syllableLefts[i + 1] - syllableLefts[i]) * progress;
            }
        }
        return syllableLefts[count] + feather * 2f;
    }

    private void applySweepMask(Canvas canvas, float sweepX, float feather, float maskDark) {
        if (sweepShader == null || sweepShaderDark != maskDark) {
            if (sweepShader != null) sweepShader.close();
            int dark = ((int) (maskDark * 255f) << 24) | 0x00FFFFFF;
            sweepShader = Shader.makeLinearGradient(0f, 0f, 1f, 0f,
                    new int[]{0xFFFFFFFF, dark}, new float[]{0f, 1f});
            sweepShaderDark = maskDark;
        }
        sweepPaint.setShader(sweepShader);
        sweepPaint.setBlendMode(BlendMode.DST_IN);
        canvas.save();
        canvas.translate(sweepX - feather * 0.5f, 0f);
        canvas.scale(feather, 1f);
        canvas.drawRect(sweepBigRect, sweepPaint);
        canvas.restore();
        sweepPaint.setShader(null);
    }

    private Shader emptyShader() {
        if (emptyShader == null) emptyShader = Shader.makeColor(0x00000000);
        return emptyShader;
    }

    private RuntimeEffectBuilder rowBuilder() {
        if (rowEffectUnavailable) return null;
        if (rowBuilder == null) {
            if (rowEffect == null) rowEffect = compileShaderResource();
            if (rowEffect == null) {
                rowEffectUnavailable = true;
                return null;
            }
            rowBuilder = new RuntimeEffectBuilder(rowEffect);
        }
        return rowBuilder;
    }

    private static RuntimeEffect compileShaderResource() {
        return compileShaderResource(
                () -> LyricRowRenderer.class.getResourceAsStream(ROW_SHADER_RESOURCE),
                RuntimeEffect::makeForShader);
    }

    static RuntimeEffect compileShaderResource(ShaderSource source,
                                               ShaderCompiler compiler) {
        try (InputStream input = source.open()) {
            if (input == null) throw new IOException("resource not found: " + ROW_SHADER_RESOURCE);
            InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
            StringBuilder text = new StringBuilder(4096);
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) >= 0) text.append(buffer, 0, read);
            return compiler.compile(text.toString());
        } catch (ThreadDeath fatal) {
            throw fatal;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            dev.t1m3.qplayer.util.Logger.warn(
                    "lyric row shader unavailable; continuing without per-syllable motion: {}",
                    failure.getMessage());
            return null;
        }
    }

    interface ShaderSource {
        InputStream open() throws Exception;
    }

    interface ShaderCompiler {
        RuntimeEffect compile(String source);
    }

    private void drawTextShadow(TextBlob blob, float x, float baselineY, float alpha) {
        textShadowPaint.setAlphaf(alpha * TEXT_SHADOW_ALPHA);
        LyricSkia.getCanvas().drawTextBlob(
                blob, x, baselineY + TEXT_SHADOW_OFFSET_Y, textShadowPaint);
    }

    private static float riseSpring(double elapsedSeconds) {
        if (elapsedSeconds <= 0.0) return 0f;
        double dampedFrequency = RISE_ZETA * RISE_OMEGA0;
        double frequency = RISE_OMEGA0 * Math.sqrt(1.0 - RISE_ZETA * RISE_ZETA);
        double envelope = Math.exp(-dampedFrequency * elapsedSeconds);
        double value = 1.0 - envelope * (Math.cos(frequency * elapsedSeconds)
                + (dampedFrequency / frequency) * Math.sin(frequency * elapsedSeconds));
        return (float) Math.max(0.0, value);
    }

    private static void drawRect(Canvas canvas, float left, float top,
                                 float right, float bottom, Paint paint) {
        Canvas._nDrawRect(Native.getPtr(canvas), left, top, right, bottom, Native.getPtr(paint));
    }

    private static void clipRect(Canvas canvas, float left, float top,
                                 float right, float bottom) {
        Canvas._nClipRect(Native.getPtr(canvas), left, top, right, bottom,
                ClipMode.INTERSECT.ordinal(), false);
    }

    private static void setShader(Paint paint, long shader) {
        Paint._nSetShader(Native.getPtr(paint), shader);
    }

    private static Paint newTextShadowPaint() {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(0xFF000000);
        paint.setMaskFilter(MaskFilter.makeBlur(FilterBlurMode.NORMAL, 2.2f));
        return paint;
    }

    @Override
    public void close() {
        if (sweepShader != null) {
            sweepShader.close();
            sweepShader = null;
        }
        if (emptyShader != null) {
            emptyShader.close();
            emptyShader = null;
        }
        if (rowBuilder != null) {
            rowBuilder.close();
            rowBuilder = null;
        }
        if (rowEffect != null) {
            rowEffect.close();
            rowEffect = null;
        }
        lyricLayerPaint.close();
        sweepPaint.close();
        textShadowPaint.close();
    }
}
