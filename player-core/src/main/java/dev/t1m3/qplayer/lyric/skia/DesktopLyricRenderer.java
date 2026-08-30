package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LyricTimeline;
import dev.t1m3.qplayer.lyric.Syllable;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMetrics;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;

import java.util.Collections;
import java.util.List;

/**
 * Compact, fixed three-row lyric renderer for a transparent desktop overlay.
 *
 * <p>All rows use the full lyric page's HarfBuzz shaping path but never wrap.
 * The current row is painted twice: an unsung Monet role first, then the primary
 * role clipped to the exact timed-syllable sweep. Plain line-timed lyrics fall
 * back to a continuous line-progress sweep. A line change moves the old triplet
 * one slot out and the new triplet one slot in with a fixed-duration cubic
 * ease-out; it intentionally contains no spring state or overshoot.
 */
public final class DesktopLyricRenderer implements AutoCloseable {

    private static final float SIDE_SIZE_RATIO = 0.72f;
    private static final float TRANSLATION_SIZE_RATIO = 0.52f;
    private static final float SHADOW_OPACITY = 0.32f;
    private static final float TRANSLATION_OPTICAL_GAP = -3f;
    private static final long LINE_TRANSITION_NANOS = 380_000_000L;

    private final LyricTextShaper shaper = new LyricTextShaper();
    private final Paint textPaint = new Paint().setAntiAlias(true);
    private final Paint shadowPaint = new Paint().setAntiAlias(true);
    private Visual current;
    private Visual outgoing;
    private long transitionStartNanos;
    private int transitionDirection = 1;
    private int cachedFontSize = -1;
    private Fonts.Weight cachedWeight;

    public void render(Canvas canvas, float left, float top, float width, float height,
                       LyricTimeline.Frame frame, String fallbackText,
                       int fontSize, int fontWeight, boolean shadow, Colors colors,
                       long positionMs, long nowNanos) {
        if (canvas == null || width <= 0f || height <= 0f || colors == null) return;
        int safeSize = Math.max(18, Math.min(64, fontSize));
        Fonts.Weight weight = toWeight(fontWeight);
        if (safeSize != cachedFontSize || weight != cachedWeight) {
            closeVisuals();
            cachedFontSize = safeSize;
            cachedWeight = weight;
        }

        LyricTimeline.Frame safeFrame = frame;
        String fallback = fallbackText == null ? "" : fallbackText;
        if (current == null || !current.matches(safeFrame, fallback)) {
            Visual replacement = createVisual(safeFrame, fallback, safeSize, weight);
            if (current != null) {
                if (outgoing != null) outgoing.close();
                outgoing = current;
                transitionDirection = direction(outgoing.groupIndex, replacement.groupIndex);
                transitionStartNanos = nowNanos;
            }
            current = replacement;
        }
        current.update(safeFrame, positionMs);

        int save = canvas.save();
        try {
            canvas.clipRect(Rect.makeXYWH(left, top, width, height));
            float slot = height / 3f;
            if (outgoing != null) {
                float raw = (nowNanos - transitionStartNanos) / (float) LINE_TRANSITION_NANOS;
                float eased = transitionEasing(raw);
                if (raw >= 1f) {
                    outgoing.close();
                    outgoing = null;
                    drawVisual(canvas, current, left, top, width, height,
                            0f, 1f, shadow, colors, false);
                } else {
                    drawVisual(canvas, outgoing, left, top, width, height,
                            transitionDirection * -slot * eased, 1f - eased,
                            shadow, colors, true);
                    drawVisual(canvas, current, left, top, width, height,
                            transitionDirection * slot * (1f - eased), eased,
                            shadow, colors, false);
                }
            } else {
                drawVisual(canvas, current, left, top, width, height,
                        0f, 1f, shadow, colors, false);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private Visual createVisual(LyricTimeline.Frame frame, String fallback,
                                int fontSize, Fonts.Weight weight) {
        String previous = frame != null ? safe(frame.previous) : "";
        String currentText = frame != null ? safe(frame.current) : "";
        String next = frame != null ? safe(frame.next) : "";
        String translation = frame != null ? safe(frame.translation) : "";
        if (currentText.isEmpty()) currentText = fallback;

        Font currentFont = Fonts.get(weight, fontSize);
        Font sideFont = Fonts.get(weight, fontSize * SIDE_SIZE_RATIO);
        Font translationFont = Fonts.get(weight,
                Math.max(12f, fontSize * TRANSLATION_SIZE_RATIO));
        LyricTextShaper.configureForAnimation(currentFont);
        LyricTextShaper.configureForAnimation(sideFont);
        LyricTextShaper.configureForAnimation(translationFont);

        List<Syllable> syllables = frame != null ? frame.currentSyllables : null;
        if (syllables == null || syllables.isEmpty() || frame.current.isEmpty()) {
            syllables = currentText.isEmpty()
                    ? Collections.<Syllable>emptyList()
                    : Collections.singletonList(new Syllable(currentText, 0L, 0L));
        }
        LyricTextShaper.ShapedRow main = shaper.shapeMainRow(
                syllables, 0, syllables.size(), currentFont);
        return new Visual(
                frame != null ? frame.groupIndex : -1,
                previous, currentText, translation, next,
                frame != null && frame.animatablePerToken,
                syllables,
                shaper.shapeSingleLine(previous, sideFont), main,
                shaper.shapeSingleLine(translation, translationFont),
                shaper.shapeSingleLine(next, sideFont),
                sideFont, currentFont, translationFont,
                frame != null ? frame.progress : 0f);
    }

    private void drawVisual(Canvas canvas, Visual visual,
                            float left, float top, float width, float height,
                            float translateY, float alpha, boolean shadow,
                            Colors colors, boolean outgoingVisual) {
        if (visual == null || alpha <= 0.001f) return;
        float slot = height / 3f;
        drawStatic(canvas, visual.previous, visual.sideFont,
                left, top + slot * 0.5f + translateY, width, 1f,
                colors.previous, alpha, shadow, colors.shadow);

        float currentCenter = top + slot * 1.5f + translateY;
        boolean hasTranslation = visual.translation != null
                && visual.translation.blob != null && visual.translation.width > 0f;
        float mainCenter = currentCenter;
        float translationCenter = currentCenter;
        if (hasTranslation) {
            mainCenter -= (visual.translationFont.getSpacing()
                    + TRANSLATION_OPTICAL_GAP) * 0.5f;
            translationCenter += (visual.currentFont.getSpacing()
                    + TRANSLATION_OPTICAL_GAP) * 0.5f;
        }
        drawCurrent(canvas, visual, left, mainCenter, width,
                alpha, shadow, colors, outgoingVisual);
        if (hasTranslation) {
            drawStatic(canvas, visual.translation, visual.translationFont,
                    left, translationCenter, width, visual.progress,
                    colors.next, alpha * 0.88f, shadow, colors.shadow);
        }

        drawStatic(canvas, visual.next, visual.sideFont,
                left, top + slot * 2.5f + translateY, width, 0f,
                colors.next, alpha, shadow, colors.shadow);
    }

    private void drawCurrent(Canvas canvas, Visual visual, float left, float centerY,
                             float viewportWidth, float alpha, boolean shadow,
                             Colors colors, boolean forceComplete) {
        LyricTextShaper.ShapedRow row = visual.current;
        if (row == null || row.blob == null || row.width <= 0f) return;
        float baseline = baseline(visual.currentFont, centerY);
        float x = textX(left, viewportWidth, row.width, visual.progress);
        if (shadow) drawBlob(canvas, row.blob, x + 0.75f, baseline + 1.25f,
                colors.shadow, alpha * SHADOW_OPACITY, shadowPaint);

        float sweep = forceComplete ? row.width : sweepWidth(visual, row);
        if (sweep >= row.width - 0.01f) {
            drawBlob(canvas, row.blob, x, baseline, colors.current, alpha, textPaint);
            return;
        }
        drawBlob(canvas, row.blob, x, baseline, colors.previous, alpha, textPaint);
        if (sweep <= 0.01f) return;
        int save = canvas.save();
        try {
            canvas.clipRect(Rect.makeLTRB(x, centerY - visual.currentFont.getSpacing(),
                    x + sweep, centerY + visual.currentFont.getSpacing()));
            drawBlob(canvas, row.blob, x, baseline, colors.current, alpha, textPaint);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private static float sweepWidth(Visual visual, LyricTextShaper.ShapedRow row) {
        if (!visual.animatablePerToken || visual.syllables.isEmpty()) {
            return row.width * clamp01(visual.progress);
        }
        int count = Math.min(visual.syllables.size(), row.syllableX.length - 1);
        if (count <= 0) return row.width * clamp01(visual.progress);
        long positionMs = visual.positionMs;
        for (int index = 0; index < count; index++) {
            Syllable syllable = visual.syllables.get(index);
            long start = syllable.startMs;
            long end = start + Math.max(1L, syllable.durationMs);
            if (positionMs < start) return clampSweep(row, row.syllableX[index]);
            if (positionMs < end) {
                float progress = (positionMs - start) / (float) (end - start);
                float startX = row.syllableX[index];
                float endX = row.syllableX[index + 1];
                return clampSweep(row, startX + (endX - startX) * progress);
            }
        }
        return row.width;
    }

    private void drawStatic(Canvas canvas, LyricTextShaper.ShapedText text, Font font,
                            float left, float centerY, float viewportWidth, float progress,
                            int color, float alpha, boolean shadow, int shadowColor) {
        if (text == null || text.blob == null || text.width <= 0f) return;
        float x = textX(left, viewportWidth, text.width, progress);
        float baseline = baseline(font, centerY);
        if (shadow) drawBlob(canvas, text.blob, x + 0.75f, baseline + 1.25f,
                shadowColor, alpha * SHADOW_OPACITY, shadowPaint);
        drawBlob(canvas, text.blob, x, baseline, color, alpha, textPaint);
    }

    private static void drawBlob(Canvas canvas, io.github.humbleui.skija.TextBlob blob,
                                 float x, float y, int color, float alpha, Paint paint) {
        paint.setColor(color);
        paint.setAlphaf(clamp01(alpha));
        canvas.drawTextBlob(blob, x, y, paint);
    }

    private static float baseline(Font font, float centerY) {
        FontMetrics metrics = font.getMetrics();
        return centerY - (metrics.getAscent() + metrics.getDescent()) * 0.5f;
    }

    private static float textX(float left, float viewportWidth, float textWidth, float progress) {
        return textWidth <= viewportWidth
                ? left + (viewportWidth - textWidth) * 0.5f
                : left - scrollOffset(textWidth, viewportWidth, progress);
    }

    private static float clampSweep(LyricTextShaper.ShapedRow row, float value) {
        return Math.max(0f, Math.min(row.width, value));
    }

    /** Visible for tests: cubic ease-out, with no spring/overshoot state. */
    static float transitionEasing(float progress) {
        float value = clamp01(progress);
        float inverse = 1f - value;
        return 1f - inverse * inverse * inverse;
    }

    /** Visible for tests: returns a finite offset in [0, textWidth - viewportWidth]. */
    static float scrollOffset(float textWidth, float viewportWidth, float progress) {
        float overflow = Math.max(0f, textWidth - Math.max(0f, viewportWidth));
        return overflow * clamp01(progress);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int direction(int from, int to) {
        return from >= 0 && to >= 0 && to < from ? -1 : 1;
    }

    private static Fonts.Weight toWeight(int value) {
        switch (Math.max(0, Math.min(3, value))) {
            case 0: return Fonts.Weight.THIN;
            case 1: return Fonts.Weight.LIGHT;
            case 3: return Fonts.Weight.MEDIUM;
            default: return Fonts.Weight.REGULAR;
        }
    }

    private void closeVisuals() {
        if (current != null) current.close();
        if (outgoing != null) outgoing.close();
        current = null;
        outgoing = null;
    }

    @Override
    public void close() {
        closeVisuals();
        shaper.close();
        textPaint.close();
        shadowPaint.close();
    }

    /** Monet-derived colors captured by the host alongside the lyric snapshot. */
    public static final class Colors {
        public final int previous;
        public final int current;
        public final int next;
        public final int shadow;

        public Colors(int previous, int current, int next, int shadow) {
            this.previous = previous;
            this.current = current;
            this.next = next;
            this.shadow = shadow;
        }
    }

    private static final class Visual implements AutoCloseable {
        final int groupIndex;
        final String previousText;
        final String currentText;
        final String translationText;
        final String nextText;
        final boolean animatablePerToken;
        final List<Syllable> syllables;
        final LyricTextShaper.ShapedText previous;
        final LyricTextShaper.ShapedRow current;
        final LyricTextShaper.ShapedText translation;
        final LyricTextShaper.ShapedText next;
        final Font sideFont;
        final Font currentFont;
        final Font translationFont;
        float progress;
        long positionMs;

        Visual(int groupIndex, String previousText, String currentText,
               String translationText, String nextText, boolean animatablePerToken,
               List<Syllable> syllables, LyricTextShaper.ShapedText previous,
               LyricTextShaper.ShapedRow current, LyricTextShaper.ShapedText translation,
               LyricTextShaper.ShapedText next, Font sideFont, Font currentFont,
               Font translationFont, float progress) {
            this.groupIndex = groupIndex;
            this.previousText = previousText;
            this.currentText = currentText;
            this.translationText = translationText;
            this.nextText = nextText;
            this.animatablePerToken = animatablePerToken;
            this.syllables = syllables;
            this.previous = previous;
            this.current = current;
            this.translation = translation;
            this.next = next;
            this.sideFont = sideFont;
            this.currentFont = currentFont;
            this.translationFont = translationFont;
            this.progress = clamp01(progress);
        }

        boolean matches(LyricTimeline.Frame frame, String fallback) {
            String frameCurrent = frame != null ? safe(frame.current) : "";
            if (frameCurrent.isEmpty()) frameCurrent = fallback;
            return groupIndex == (frame != null ? frame.groupIndex : -1)
                    && previousText.equals(frame != null ? safe(frame.previous) : "")
                    && currentText.equals(frameCurrent)
                    && translationText.equals(frame != null ? safe(frame.translation) : "")
                    && nextText.equals(frame != null ? safe(frame.next) : "");
        }

        void update(LyricTimeline.Frame frame, long positionMs) {
            progress = frame != null ? clamp01(frame.progress) : 0f;
            this.positionMs = positionMs;
        }

        @Override
        public void close() {
            if (previous != null) previous.close();
            if (current != null) current.close();
            if (translation != null) translation.close();
            if (next != null) next.close();
        }
    }
}
