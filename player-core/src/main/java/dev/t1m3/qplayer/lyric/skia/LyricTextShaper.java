package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.Syllable;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontEdging;
import io.github.humbleui.skija.FontHinting;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.TextBlob;
import io.github.humbleui.skija.TextLine;
import io.github.humbleui.skija.shaper.Shaper;

import java.util.ArrayList;
import java.util.List;

/**
 * Shapes and wraps lyric text while owning the native HarfBuzz shaper.
 *
 * <p>This class deliberately contains no playback or drawing state. A main lyric
 * row, translation row, or desktop-lyric surface can reuse the same shaping rules
 * without depending on {@link LyricRenderer}'s scroll and animation pipeline.
 */
final class LyricTextShaper implements AutoCloseable {

    private Shaper harfBuzzShaper;

    static void configureForAnimation(Font font) {
        font.setBaselineSnapped(false);
        font.setSubpixel(true);
        font.setHinting(FontHinting.NONE);
        font.setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS);
    }

    List<Syllable> splitOversizedSyllables(List<Syllable> source, Font font,
                                           float maxWidth) {
        if (source == null || source.isEmpty() || maxWidth <= 0f) return source;
        ArrayList<Syllable> result = null;
        for (int sourceIndex = 0; sourceIndex < source.size(); sourceIndex++) {
            Syllable syllable = source.get(sourceIndex);
            List<Syllable> fragments = splitOversizedSyllable(syllable, font, maxWidth);
            if (fragments == null) {
                if (result != null) result.add(syllable);
                continue;
            }
            if (result == null) {
                result = new ArrayList<>(source.size() + fragments.size());
                result.addAll(source.subList(0, sourceIndex));
            }
            result.addAll(fragments);
        }
        return result == null ? source : result;
    }

    /** Returns null when no split is necessary. */
    private List<Syllable> splitOversizedSyllable(Syllable syllable, Font font,
                                                  float maxWidth) {
        String text = syllable.text == null ? "" : syllable.text;
        if (text.isEmpty()) return null;
        try (TextLine line = shapeLine(text, font)) {
            if (line.getWidth() <= maxWidth + 0.5f) return null;

            boolean[] preferred = LyricTextLayout.unicodeLineBreakOffsets(text);
            int[] graphemes = LyricTextLayout.graphemeBoundaries(text);
            boolean usableCaretWidths = false;
            float origin = line.getCoordAtOffset(0);
            for (int i = 1; i + 1 < graphemes.length; i++) {
                if (Math.abs(line.getCoordAtOffset(graphemes[i]) - origin) > 0.01f) {
                    usableCaretWidths = true;
                    break;
                }
            }
            ArrayList<Syllable> out = new ArrayList<>();
            int start = 0;
            while (start < text.length()) {
                int bestPreferred = -1;
                int bestGrapheme = -1;
                for (int boundary : graphemes) {
                    if (boundary <= start) continue;
                    float width;
                    if (usableCaretWidths) {
                        width = Math.abs(line.getCoordAtOffset(boundary)
                                - line.getCoordAtOffset(start));
                    } else {
                        try (TextLine fragment = shapeLine(text.substring(start, boundary), font)) {
                            width = fragment.getWidth();
                        }
                    }
                    if (width <= maxWidth + 0.5f || bestGrapheme < 0) {
                        bestGrapheme = boundary;
                        if (preferred[boundary]) bestPreferred = boundary;
                    } else {
                        break;
                    }
                }
                int end = bestPreferred > start ? bestPreferred : bestGrapheme;
                if (end <= start) {
                    int codePoint = text.codePointAt(start);
                    end = start + Character.charCount(codePoint);
                }

                double startProgress = start / (double) text.length();
                double endProgress = end / (double) text.length();
                long fragmentStart = syllable.startMs
                        + Math.round(syllable.durationMs * startProgress);
                long fragmentEnd = end == text.length()
                        ? syllable.startMs + syllable.durationMs
                        : syllable.startMs + Math.round(syllable.durationMs * endProgress);
                out.add(new Syllable(text.substring(start, end), fragmentStart,
                        Math.max(0L, fragmentEnd - fragmentStart)));
                start = end;
            }
            return out.size() <= 1 ? null : out;
        }
    }

    float[] shapeSyllableAdvances(List<Syllable> syllables, Font font) {
        int count = syllables.size();
        float[] widths = new float[count];
        if (count == 0) return widths;
        StringBuilder text = new StringBuilder();
        int[] offsets = new int[count + 1];
        for (int i = 0; i < count; i++) {
            offsets[i] = text.length();
            String value = syllables.get(i).text;
            if (value != null) text.append(value);
        }
        offsets[count] = text.length();
        try (TextLine line = shapeLine(text.toString(), font)) {
            float measuredTotal = 0f;
            int nonZeroAdvances = 0;
            for (int i = 0; i < count; i++) {
                widths[i] = Math.abs(
                        line.getCoordAtOffset(offsets[i + 1]) - line.getCoordAtOffset(offsets[i]));
                measuredTotal += widths[i];
                if (widths[i] > 0.01f) nonZeroAdvances++;
            }
            float tolerance = Math.max(1f, line.getWidth() * 0.02f);
            boolean collapsedCarets = count > 1 && nonZeroAdvances <= 1 && line.getWidth() > 0.01f;
            if (collapsedCarets || Math.abs(measuredTotal - line.getWidth()) > tolerance) {
                for (int i = 0; i < count; i++) {
                    String value = syllables.get(i).text;
                    try (TextLine segment = shapeLine(value == null ? "" : value, font)) {
                        widths[i] = segment.getWidth();
                    }
                }
            }
        }
        return widths;
    }

    ShapedRow shapeMainRow(List<Syllable> syllables, int from, int to, Font font) {
        int count = Math.max(0, to - from);
        StringBuilder text = new StringBuilder();
        int[] offsets = new int[count + 1];
        for (int i = 0; i < count; i++) {
            offsets[i] = text.length();
            String value = syllables.get(from + i).text;
            if (value != null) text.append(value);
        }
        offsets[count] = text.length();

        try (TextLine line = shapeLine(text.toString(), font)) {
            float[] x = new float[count + 1];
            for (int i = 0; i <= count; i++) x[i] = line.getCoordAtOffset(offsets[i]);
            int visibleOffset = 0;
            while (visibleOffset < text.length()) {
                int codePoint = text.codePointAt(visibleOffset);
                if (!Character.isWhitespace(codePoint)) break;
                visibleOffset += Character.charCount(codePoint);
            }
            WordSpan[] words = buildWordSpans(text.toString(), offsets, line);
            return new ShapedRow(from, to, line.getTextBlob(), line.getWidth(),
                    line.getCoordAtOffset(visibleOffset), x, words);
        }
    }

    ShapedText[] shapeWrappedText(String text, Font font, float maxWidth) {
        if (text == null || text.isEmpty()) return new ShapedText[]{shapeText("", font)};
        ArrayList<ShapedText> rows = new ArrayList<>();
        try (TextLine full = shapeLine(text, font)) {
            int start = 0;
            while (start < text.length()) {
                float startX = full.getCoordAtOffset(start);
                int best = start;
                int bestBreak = -1;
                int cursor = start;
                while (cursor < text.length()) {
                    int codePoint = text.codePointAt(cursor);
                    int next = cursor + Character.charCount(codePoint);
                    if (Character.isWhitespace(codePoint)) bestBreak = cursor;
                    if (full.getCoordAtOffset(next) - startX > maxWidth && cursor > start) break;
                    best = next;
                    cursor = next;
                }
                int end = best;
                int nextStart = best;
                if (cursor < text.length() && bestBreak > start) {
                    end = bestBreak;
                    nextStart = bestBreak;
                    while (nextStart < text.length()
                            && Character.isWhitespace(text.codePointAt(nextStart))) {
                        nextStart += Character.charCount(text.codePointAt(nextStart));
                    }
                }
                if (end <= start) {
                    end = Math.min(text.length(), start + Character.charCount(text.codePointAt(start)));
                }
                rows.add(shapeText(text.substring(start, end), font));
                start = Math.max(end, nextStart);
            }
        }
        return rows.toArray(new ShapedText[0]);
    }

    private ShapedText shapeText(String text, Font font) {
        try (TextLine line = shapeLine(text, font)) {
            return new ShapedText(line.getTextBlob(), line.getWidth());
        }
    }

    /** Shapes one unwrapped row for compact lyric surfaces. */
    ShapedText shapeSingleLine(String text, Font font) {
        return shapeText(text == null ? "" : text, font);
    }

    private TextLine shapeLine(String text, Font baseFont) {
        return shaper().shapeLine(text, fontForText(text, baseFont));
    }

    private Shaper shaper() {
        if (harfBuzzShaper == null) harfBuzzShaper = Shaper.makeBestAvailable();
        return harfBuzzShaper;
    }

    private static WordSpan[] buildWordSpans(String text, int[] syllableOffsets, TextLine line) {
        int[][] ranges = LyricTextLayout.displayWordSyllableRanges(text, syllableOffsets);
        WordSpan[] words = new WordSpan[ranges.length];
        for (int index = 0; index < ranges.length; index++) {
            int start = ranges[index][0];
            int end = ranges[index][1];
            words[index] = new WordSpan(ranges[index][2], ranges[index][3], start, end,
                    line.getCoordAtOffset(start), line.getCoordAtOffset(end));
        }
        return words;
    }

    private static Font fontForText(String text, Font base) {
        if (needsKorean(text)) {
            Font korean = Fonts.korean(base);
            if (korean != null) return korean;
        }
        if (needsThai(text)) {
            Font thai = Fonts.thai(base);
            if (thai != null) return thai;
        }
        if (needsJapanese(text)) {
            Font japanese = Fonts.japanese(base);
            if (japanese != null) return japanese;
        }
        if (needsHan(text)) {
            Font han = Fonts.han(base);
            if (han != null) return han;
        }
        return base;
    }

    private static boolean needsHan(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if ((value >= 0x4E00 && value <= 0x9FFF)
                    || (value >= 0x3400 && value <= 0x4DBF)
                    || (value >= 0x3000 && value <= 0x303F)
                    || (value >= 0xFF00 && value <= 0xFFEF)) {
                return true;
            }
        }
        return false;
    }

    private static boolean needsKorean(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if ((value >= 0xAC00 && value <= 0xD7FF) || (value >= 0x1100 && value <= 0x11FF)
                    || (value >= 0x3130 && value <= 0x318F)
                    || (value >= 0xA960 && value <= 0xA97F)) {
                return true;
            }
        }
        return false;
    }

    private static boolean needsThai(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value >= 0x0E00 && value <= 0x0E7F) return true;
        }
        return false;
    }

    private static boolean needsJapanese(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if ((value >= 0x3040 && value <= 0x30FF) || (value >= 0x31F0 && value <= 0x31FF)
                    || (value >= 0xFF65 && value <= 0xFF9F)) {
                return true;
            }
        }
        return false;
    }

    static void closeRows(ShapedRow[][] rows) {
        if (rows == null) return;
        for (ShapedRow[] line : rows) {
            if (line == null) continue;
            for (ShapedRow row : line) if (row != null) row.close();
        }
    }

    static void closeTexts(ShapedText[][] rows) {
        if (rows == null) return;
        for (ShapedText[] line : rows) {
            if (line == null) continue;
            for (ShapedText row : line) if (row != null) row.close();
        }
    }

    @Override
    public void close() {
        if (harfBuzzShaper != null) {
            harfBuzzShaper.close();
            harfBuzzShaper = null;
        }
    }

    /** Immutable shaped main row plus its optional high-resolution raster cache. */
    static final class ShapedRow implements AutoCloseable {
        final int from;
        final int to;
        final TextBlob blob;
        final float width;
        final float leadingWidth;
        final float[] syllableX;
        final WordSpan[] words;
        Image highResImage;
        Shader highResImageShader;
        float rasterLeft;
        float rasterTop;
        float rasterWidth;
        float rasterHeight;
        boolean rasterWithShadow;

        ShapedRow(int from, int to, TextBlob blob, float width, float leadingWidth,
                  float[] syllableX, WordSpan[] words) {
            this.from = from;
            this.to = to;
            this.blob = blob;
            this.width = width;
            this.leadingWidth = leadingWidth;
            this.syllableX = syllableX;
            this.words = words;
        }

        @Override
        public void close() {
            closeRaster();
            if (blob != null) blob.close();
        }

        void closeRaster() {
            if (highResImageShader != null) {
                highResImageShader.close();
                highResImageShader = null;
            }
            if (highResImage != null) {
                highResImage.close();
                highResImage = null;
            }
        }
    }

    static final class ShapedText implements AutoCloseable {
        final TextBlob blob;
        final float width;

        ShapedText(TextBlob blob, float width) {
            this.blob = blob;
            this.width = width;
        }

        @Override
        public void close() {
            if (blob != null) blob.close();
        }
    }

    /** Display-word span independent of the source's timed-syllable boundaries. */
    static final class WordSpan {
        final int firstSyllable;
        final int lastSyllable;
        final int utf16Start;
        final int utf16End;
        final float x0;
        final float x1;

        WordSpan(int firstSyllable, int lastSyllable, int utf16Start,
                 int utf16End, float x0, float x1) {
            this.firstSyllable = firstSyllable;
            this.lastSyllable = lastSyllable;
            this.utf16Start = utf16Start;
            this.utf16End = utf16End;
            this.x0 = x0;
            this.x1 = x1;
        }
    }
}
