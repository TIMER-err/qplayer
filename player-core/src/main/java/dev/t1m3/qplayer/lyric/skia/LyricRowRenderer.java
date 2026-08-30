package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.Syllable;
import dev.t1m3.qplayer.lyric.skia.LyricTextShaper.ShapedRow;
import dev.t1m3.qplayer.lyric.skia.LyricTextShaper.ShapedText;
import dev.t1m3.qplayer.lyric.skia.LyricTextShaper.WordSpan;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.MaskFilter;
import io.github.humbleui.skija.FilterBlurMode;
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

/** Owns reusable Skia resources and effects used to paint shaped lyric rows. */
final class LyricRowRenderer implements AutoCloseable {

    private static final float LIFT_PEAK_PX = 2.0f;
    private static final long LIFT_MIN_DURATION_MS = 1000L;
    private static final float SWEEP_FADE_PX = 16f;
    private static final float DARK_MASK_ALPHA = 0.36f;
    private static final double LIFT_OMEGA0 = 3.7416574;
    private static final double LIFT_ZETA = 0.935414;
    // 辉光门控与强度照搬 Melodify（qplayerWordGlow）：GLOW_ALPHA 0.7、词时长 ≥1000ms
    // （无条件，不再依赖 dropShadow）、纯 ASCII 可打印且长度 >7 的词才发光。
    private static final float GLOW_ALPHA = 0.7f;
    private static final long WORD_GLOW_MIN_DURATION_MS = 1000L;
    private static final float WORD_RIBBON_LIFT_PX = 2f;
    private static final float MAX_SHADER_LIFT_PX = LIFT_PEAK_PX + WORD_RIBBON_LIFT_PX;
    private static final float TEXT_SHADOW_OFFSET_Y = 2f;
    private static final float TEXT_SHADOW_ALPHA = 0.48f;
    private static final int MAX_LIFT_SEGMENTS = 128;
    private static final int MAX_WORD_LIFT_SEGMENTS = 32;
    private static final float TEXT_SUPERSAMPLE = 2f;
    private static final float TEXT_RASTER_PAD = 8f;
    private static final int MAX_HIGH_RES_ROWS = 12;
    private static final String LIFT_SHADER_RESOURCE = "/shaders/lyric/syllable_lift.sksl";

    private final Paint lyricLayerPaint = new Paint();
    private final Paint sweepPaint = new Paint();
    private final int[] sweepColors = new int[2];
    private final float[] sweepStops = new float[2];
    private final Rect sweepBigRect = Rect.makeLTRB(-100000f, -100000f, 100000f, 100000f);
    private final Paint glowGlyphPaint = newGlowGlyphPaint();
    private final Paint glowLayerPaint = newGlowLayerPaint();
    private final Paint textShadowPaint = newTextShadowPaint();
    private final float[] liftUniforms = new float[MAX_LIFT_SEGMENTS * 4];
    private final float[] wordLiftUniforms = new float[MAX_WORD_LIFT_SEGMENTS * 4];
    private final ArrayDeque<ShapedRow> highResolutionRows = new ArrayDeque<>();
    private float[] syllableLefts = new float[0];
    private float[] lifts = new float[0];
    private Shader sweepShader;
    private float sweepShaderDark = Float.NaN;
    private RuntimeEffect liftEffect;
    private RuntimeEffectBuilder liftBuilder;
    private boolean liftEffectUnavailable;

    void drawSubLine(ShapedText text, float leftX, float rightAnchorX, float y,
                     float alpha, boolean alignRight, boolean shadowOn) {
        if (text == null || text.blob == null) return;
        float x = alignRight ? rightAnchorX - text.width : leftX;
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
                 boolean glowOn, boolean shadowOn, boolean wordGlowSupported) {
        if (row == null || row.blob == null || row.from >= row.to || row.width <= 0f) return;
        Canvas canvas = LyricSkia.getCanvas();
        if (activeK <= 0.001f || !animatePerToken) {
            drawStaticRow(canvas, row.blob, startX, baselineY, baseAlpha, shadowOn);
            return;
        }

        int count = row.to - row.from;
        if (syllableLefts.length < count + 1) syllableLefts = new float[count + 1];
        for (int i = 0; i <= count; i++) syllableLefts[i] = startX + row.syllableX[i];
        float rowRightX = startX + row.width;
        float sweepX = computeSweepX(syllables, row.from, row.to, syllableLefts, positionMs);

        if (lifts.length < count) lifts = new float[count];
        for (int i = 0; i < count; i++) {
            float k = syllableAnimation(syllables.get(row.from + i), positionMs, spring);
            lifts[i] = -LIFT_PEAK_PX * k * activeK;
        }

        lyricLayerPaint.setAlphaf(baseAlpha);
        canvas.saveLayer(startX - 8f,
                baselineY + ascent - MAX_SHADER_LIFT_PX - 8f,
                rowRightX + 8f, baselineY + descent + 8f, lyricLayerPaint);
        try {
            if (liftEffectUnavailable) {
                drawStaticRow(canvas, row.blob, startX, baselineY, 1f, shadowOn);
                applySweepMask(canvas, sweepX, 1f - (1f - DARK_MASK_ALPHA) * activeK);
                return;
            }
            ensureHighResolutionRaster(row, ascent, descent, shadowOn);
            long liftedShader = makeLiftShader(row, startX, baselineY, rowRightX, count,
                    syllables, positionMs, sweepX, activeK, glowOn, shadowOn,
                    wordGlowSupported);
            if (liftedShader == 0L) {
                drawStaticRow(canvas, row.blob, startX, baselineY, 1f, shadowOn);
                applySweepMask(canvas, sweepX, 1f - (1f - DARK_MASK_ALPHA) * activeK);
                return;
            }
            try {
                Paint textPaint = LyricSkia.scratchPaint();
                setShader(textPaint, liftedShader);
                try {
                    textPaint.setAlphaf(1f);
                    textPaint.setAntiAlias(true);
                    drawRect(canvas, startX + row.rasterLeft,
                            baselineY + row.rasterTop - MAX_SHADER_LIFT_PX,
                            startX + row.rasterLeft + row.rasterWidth,
                            baselineY + row.rasterTop + row.rasterHeight, textPaint);
                } finally {
                    setShader(textPaint, 0L);
                }
                applySweepMask(canvas, sweepX, 1f - (1f - DARK_MASK_ALPHA) * activeK);
                drawWordGlows(canvas, syllables, row, startX, baselineY, ascent, descent,
                        positionMs, activeK, glowOn, shadowOn, wordGlowSupported, liftedShader);
            } finally {
                Managed._nInvokeFinalizer(RefCnt._FinalizerHolder.PTR, liftedShader);
            }
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

    private void ensureHighResolutionRaster(ShapedRow row, float ascent, float descent,
                                            boolean shadowOn) {
        if (row.highResImage != null && row.rasterWithShadow == shadowOn) {
            highResolutionRows.remove(row);
            highResolutionRows.addLast(row);
            return;
        }
        highResolutionRows.remove(row);
        row.closeRaster();
        row.rasterLeft = -TEXT_RASTER_PAD;
        row.rasterTop = ascent - TEXT_RASTER_PAD;
        row.rasterWidth = Math.max(1f, row.width + TEXT_RASTER_PAD * 2f);
        row.rasterHeight = Math.max(1f, descent - ascent + TEXT_RASTER_PAD * 2f);
        int pixelWidth = Math.max(1, (int) Math.ceil(row.rasterWidth * TEXT_SUPERSAMPLE));
        int pixelHeight = Math.max(1, (int) Math.ceil(row.rasterHeight * TEXT_SUPERSAMPLE));
        try (Surface surface = Surface.makeRasterN32Premul(pixelWidth, pixelHeight)) {
            Canvas raster = surface.getCanvas();
            raster.clear(0x00000000);
            raster.scale(TEXT_SUPERSAMPLE, TEXT_SUPERSAMPLE);
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

    private long makeLiftShader(ShapedRow row, float startX, float baselineY,
                                float rowRightX, int count, List<Syllable> syllables,
                                long positionMs, float sweepX, float activeK,
                                boolean glowOn, boolean shadowOn,
                                boolean wordGlowSupported) {
        int segmentCount = Math.min(count, MAX_LIFT_SEGMENTS);
        Arrays.fill(liftUniforms, 0f);
        for (int i = 0; i < segmentCount; i++) {
            int offset = i * 4;
            liftUniforms[offset] = i == 0 ? startX - 8f : startX + row.syllableX[i];
            liftUniforms[offset + 1] = i == segmentCount - 1
                    ? rowRightX + 8f : startX + row.syllableX[i + 1];
            liftUniforms[offset + 2] = lifts[i];
        }

        Arrays.fill(wordLiftUniforms, 0f);
        int wordLiftCount = 0;
        if (wordGlowSupported && glowOn && shadowOn) {
            for (WordSpan word : row.words) {
                if (wordLiftCount >= MAX_WORD_LIFT_SEGMENTS) break;
                Syllable first = syllables.get(row.from + word.firstSyllable);
                Syllable last = syllables.get(row.from + word.lastSyllable);
                long end = last.startMs + Math.max(0L, last.durationMs);
                if (end - first.startMs < WORD_GLOW_MIN_DURATION_MS
                        || positionMs < first.startMs || positionMs > end) continue;
                int offset = wordLiftCount * 4;
                float wordX0 = startX + Math.min(word.x0, word.x1);
                float wordX1 = startX + Math.max(word.x0, word.x1);
                float progress = Math.max(0f, Math.min(1f,
                        (sweepX - wordX0) / Math.max(0.001f, wordX1 - wordX0)));
                float amount = -WORD_RIBBON_LIFT_PX
                        * (float) Math.sin(Math.PI * progress) * activeK;
                if (Math.abs(amount) <= 0.001f) continue;
                wordLiftUniforms[offset] = wordX0;
                wordLiftUniforms[offset + 1] = wordX1;
                wordLiftUniforms[offset + 2] = amount;
                wordLiftUniforms[offset + 3] = progress;
                wordLiftCount++;
            }
        }
        RuntimeEffectBuilder builder = liftBuilder();
        if (builder == null) return 0L;
        builder.setUniform("segments", liftUniforms);
        builder.setUniform("segmentCount", segmentCount);
        builder.setUniform("wordLifts", wordLiftUniforms);
        builder.setUniform("wordLiftCount", wordLiftCount);
        builder.setUniform("sourceOrigin", startX + row.rasterLeft, baselineY + row.rasterTop);
        builder.setUniform("sourceScale", TEXT_SUPERSAMPLE);
        builder.setChild("content", row.highResImageShader);
        return RuntimeEffectBuilder._nMakeShader(Native.getPtr(builder), null);
    }

    private void drawWordGlows(Canvas canvas, List<Syllable> syllables, ShapedRow row,
                               float startX, float baselineY, float ascent, float descent,
                               long positionMs, float activeK, boolean glowOn, boolean shadowOn,
                               boolean wordGlowSupported, long liftedShader) {
        if (!wordGlowSupported || !glowOn || row.words.length == 0) return;
        boolean layerSaved = false;
        try {
            for (WordSpan word : row.words) {
                Syllable first = syllables.get(row.from + word.firstSyllable);
                Syllable last = syllables.get(row.from + word.lastSyllable);
                long wordEnd = last.startMs + Math.max(0L, last.durationMs);
                long wordDuration = wordEnd - first.startMs;
                // 门控（照搬 Melodify qplayerWordGlow）：词时长 ≥1000ms（无条件）+
                // 纯 ASCII 可打印、长度 >7 的词才发光（中文/短词/非可打印 ASCII 不亮）。
                if (wordDuration < WORD_GLOW_MIN_DURATION_MS) continue;
                StringBuilder gw = new StringBuilder();
                for (int k = word.firstSyllable; k <= word.lastSyllable; k++) {
                    gw.append(syllables.get(row.from + k).text);
                }
                String gwt = gw.toString();
                if (gwt.length() <= 7) continue;
                boolean gAscii = true;
                for (int ci = 0; ci < gwt.length(); ci++) {
                    char c = gwt.charAt(ci);
                    boolean ws = c >= 0x09 && c <= 0x0d;
                    if (!ws && (c < 0x20 || c > 0x7e)) { gAscii = false; break; }
                }
                if (!gAscii) continue;
                if (positionMs < first.startMs || positionMs > wordEnd) continue;
                float progress = (positionMs - first.startMs) / (float) Math.max(1L, wordDuration);
                float alpha = activeK * smoothstep(0f, 0.18f, progress)
                        * (1f - smoothstep(0.90f, 1f, progress)) * GLOW_ALPHA;
                if (alpha <= 0.01f) continue;
                if (!layerSaved) {
                    canvas.saveLayer(startX - 8f,
                            baselineY + ascent - MAX_SHADER_LIFT_PX - 8f,
                            startX + row.width + 8f, baselineY + descent + 8f, glowLayerPaint);
                    layerSaved = true;
                }
                float x0 = startX + Math.min(word.x0, word.x1) - 1f;
                float x1 = startX + Math.max(word.x0, word.x1) + 1f;
                canvas.save();
                try {
                    clipRect(canvas, x0, baselineY + ascent - MAX_SHADER_LIFT_PX,
                            x1, baselineY + descent + 2f);
                    setShader(glowGlyphPaint, liftedShader);
                    glowGlyphPaint.setAlphaf(alpha);
                    drawRect(canvas, startX + row.rasterLeft,
                            baselineY + row.rasterTop - MAX_SHADER_LIFT_PX,
                            startX + row.rasterLeft + row.rasterWidth,
                            baselineY + row.rasterTop + row.rasterHeight, glowGlyphPaint);
                } finally {
                    setShader(glowGlyphPaint, 0L);
                    canvas.restore();
                }
            }
        } finally {
            if (layerSaved) canvas.restore();
        }
    }

    private static float syllableAnimation(Syllable syllable, long positionMs, boolean spring) {
        long start = syllable.startMs;
        if (spring) return liftSpring((positionMs - start) / 1000.0);
        long duration = Math.max(LIFT_MIN_DURATION_MS, Math.max(0L, syllable.durationMs));
        float progress = positionMs <= start ? 0f : positionMs >= start + duration
                ? 1f : (positionMs - start) / (float) duration;
        return 1f - (1f - progress) * (1f - progress) * (1f - progress);
    }

    private static float computeSweepX(List<Syllable> syllables, int from, int to,
                                       float[] syllableLefts, long positionMs) {
        int count = to - from;
        long firstStart = syllables.get(from).startMs;
        Syllable last = syllables.get(to - 1);
        long lastEnd = last.startMs + Math.max(0L, last.durationMs);
        if (positionMs < firstStart) return syllableLefts[0] - SWEEP_FADE_PX * 2f;
        if (positionMs >= lastEnd) return syllableLefts[count] + SWEEP_FADE_PX * 2f;
        for (int i = 0; i < count; i++) {
            Syllable syllable = syllables.get(from + i);
            long start = syllable.startMs;
            long end = syllable.durationMs > 0L
                    ? start + syllable.durationMs
                    : ((i + 1 < count) ? syllables.get(from + i + 1).startMs : start);
            if (end <= start) end = start + 1L;
            if (positionMs < start) return syllableLefts[i];
            if (positionMs < end) {
                float progress = (positionMs - start) / (float) (end - start);
                return syllableLefts[i] + (syllableLefts[i + 1] - syllableLefts[i]) * progress;
            }
        }
        return syllableLefts[count] + SWEEP_FADE_PX * 2f;
    }

    private void applySweepMask(Canvas canvas, float sweepX, float maskDark) {
        if (sweepShader == null || sweepShaderDark != maskDark) {
            if (sweepShader != null) sweepShader.close();
            int dark = ((int) (maskDark * 255f) << 24) | 0x00FFFFFF;
            sweepColors[0] = 0xFFFFFFFF;
            sweepColors[1] = dark;
            sweepStops[0] = 0f;
            sweepStops[1] = 1f;
            sweepShader = Shader.makeLinearGradient(
                    0f, 0f, SWEEP_FADE_PX, 0f, sweepColors, sweepStops);
            sweepShaderDark = maskDark;
        }
        sweepPaint.setShader(sweepShader);
        sweepPaint.setBlendMode(BlendMode.DST_IN);
        canvas.save();
        canvas.translate(sweepX - SWEEP_FADE_PX * 0.5f, 0f);
        canvas.drawRect(sweepBigRect, sweepPaint);
        canvas.restore();
        sweepPaint.setShader(null);
    }

    private RuntimeEffectBuilder liftBuilder() {
        if (liftEffectUnavailable) return null;
        if (liftBuilder == null) {
            RuntimeEffect effect = liftEffect();
            if (effect == null) {
                liftEffectUnavailable = true;
                return null;
            }
            liftBuilder = new RuntimeEffectBuilder(effect);
        }
        return liftBuilder;
    }

    private RuntimeEffect liftEffect() {
        if (liftEffect == null && !liftEffectUnavailable) {
            liftEffect = compileShaderResource();
            if (liftEffect == null) liftEffectUnavailable = true;
        }
        return liftEffect;
    }

    private static RuntimeEffect compileShaderResource() {
        return compileShaderResource(
                () -> LyricRowRenderer.class.getResourceAsStream(LIFT_SHADER_RESOURCE),
                RuntimeEffect::makeForShader);
    }

    static RuntimeEffect compileShaderResource(ShaderSource source,
                                               ShaderCompiler compiler) {
        try (InputStream input = source.open()) {
            if (input == null) throw new IOException("resource not found: " + LIFT_SHADER_RESOURCE);
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
                    "lyric lift shader unavailable; continuing without lift effect: {}",
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

    private static float liftSpring(double elapsedSeconds) {
        if (elapsedSeconds <= 0.0) return 0f;
        double dampedFrequency = LIFT_ZETA * LIFT_OMEGA0;
        double frequency = LIFT_OMEGA0 * Math.sqrt(1.0 - LIFT_ZETA * LIFT_ZETA);
        double envelope = Math.exp(-dampedFrequency * elapsedSeconds);
        double value = 1.0 - envelope * (Math.cos(frequency * elapsedSeconds)
                + (dampedFrequency / frequency) * Math.sin(frequency * elapsedSeconds));
        return (float) Math.max(0.0, value);
    }

    private static float smoothstep(float minimum, float maximum, float value) {
        if (maximum <= minimum) return value < minimum ? 0f : 1f;
        float progress = (value - minimum) / (maximum - minimum);
        if (progress <= 0f) return 0f;
        if (progress >= 1f) return 1f;
        return progress * progress * (3f - 2f * progress);
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

    private static Paint newGlowGlyphPaint() {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        return paint;
    }

    private static Paint newGlowLayerPaint() {
        Paint paint = new Paint();
        paint.setImageFilter(ImageFilter.makeBlur(2.5f, 2.5f, FilterTileMode.CLAMP));
        return paint;
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
        if (liftBuilder != null) {
            liftBuilder.close();
            liftBuilder = null;
        }
        if (liftEffect != null) {
            liftEffect.close();
            liftEffect = null;
        }
        lyricLayerPaint.close();
        sweepPaint.close();
        glowGlyphPaint.close();
        glowLayerPaint.close();
        textShadowPaint.close();
    }
}
