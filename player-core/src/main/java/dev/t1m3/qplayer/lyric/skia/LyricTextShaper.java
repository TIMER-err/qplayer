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
import io.github.timer_err.qml4j.render.text.UnicodeBreaks;

import java.util.ArrayList;
import java.util.Arrays;
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
        StringBuilder text = new StringBuilder();
        for (Syllable syllable : source) {
            if (syllable.text != null) text.append(syllable.text);
        }
        UnicodeBreaks.Analysis breaks = UnicodeBreaks.analyze(text.toString());
        ArrayList<Syllable> result = null;
        int offset = 0;
        for (int sourceIndex = 0; sourceIndex < source.size(); sourceIndex++) {
            Syllable syllable = source.get(sourceIndex);
            List<Syllable> fragments = splitOversizedSyllable(
                    syllable, font, maxWidth, breaks, offset);
            if (syllable.text != null) offset += syllable.text.length();
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

    /** Returns null when neither an oversized token nor an inner hard break needs splitting. */
    private List<Syllable> splitOversizedSyllable(Syllable syllable, Font font,
                                                  float maxWidth,
                                                  UnicodeBreaks.Analysis breaks,
                                                  int sourceOffset) {
        String text = syllable.text == null ? "" : syllable.text;
        if (text.isEmpty()) return null;
        boolean hardBreakInside = false;
        for (int i = sourceOffset + 1; i < sourceOffset + text.length(); i++) {
            if (breaks.mandatory[i]) {
                hardBreakInside = true;
                break;
            }
        }
        try (TextLine line = shapeLine(text, font)) {
            if (!hardBreakInside && line.getWidth() <= maxWidth + 0.5f) return null;
            // Use the complete source's boundaries: a source syllable may end in
            // the middle of a grapheme or next to a non-breaking joiner.
            int first = Arrays.binarySearch(breaks.graphemes, sourceOffset);
            first = first >= 0 ? first + 1 : -first - 1;
            int last = first;
            int sourceEnd = sourceOffset + text.length();
            while (last < breaks.graphemes.length && breaks.graphemes[last] < sourceEnd) last++;
            int[] offsets = new int[last - first + 2];
            offsets[0] = sourceOffset;
            System.arraycopy(breaks.graphemes, first, offsets, 1, last - first);
            offsets[offsets.length - 1] = sourceEnd;
            float[] widths = measureAdvances(text, font, line, offsets, sourceOffset);
            int[] starts = LyricTextLayout.wrapStarts(breaks, offsets, widths, maxWidth);
            if (starts.length <= 2) return null;
            int[] fragments = new int[starts.length];
            for (int i = 0; i < starts.length; i++) fragments[i] = offsets[starts[i]] - sourceOffset;
            return LyricTextLayout.splitTimedSyllable(syllable, fragments);
        }
    }

    /** Reuses one HarfBuzz result; collapsed carets are measured once per unit. */
    private float[] measureAdvances(String text, Font font, TextLine line,
                                    int[] offsets, int origin) {
        float[] widths = new float[offsets.length - 1];
        float measuredTotal = 0f;
        int nonZeroAdvances = 0;
        for (int i = 0; i < widths.length; i++) {
            widths[i] = Math.abs(line.getCoordAtOffset(offsets[i + 1] - origin)
                    - line.getCoordAtOffset(offsets[i] - origin));
            measuredTotal += widths[i];
            if (widths[i] > 0.01f) nonZeroAdvances++;
        }
        float tolerance = Math.max(1f, line.getWidth() * 0.02f);
        boolean collapsedCarets = widths.length > 1 && nonZeroAdvances <= 1 && line.getWidth() > 0.01f;
        if (collapsedCarets || Math.abs(measuredTotal - line.getWidth()) > tolerance) {
            for (int i = 0; i < widths.length; i++) {
                int start = offsets[i] - origin;
                int end = LyricTextLayout.visibleRowEnd(text, start, offsets[i + 1] - origin);
                try (TextLine unit = shapeLine(text.substring(start, end), font)) {
                    widths[i] = unit.getWidth();
                }
            }
        }
        return widths;
    }

    float[] shapeSyllableAdvances(List<Syllable> syllables, Font font) {
        int count = syllables.size();
        if (count == 0) return new float[0];
        StringBuilder text = new StringBuilder();
        int[] offsets = new int[count + 1];
        for (int i = 0; i < count; i++) {
            offsets[i] = text.length();
            String value = syllables.get(i).text;
            if (value != null) text.append(value);
        }
        offsets[count] = text.length();
        String sourceText = text.toString();
        try (TextLine line = shapeLine(sourceText, font)) {
            return measureAdvances(sourceText, font, line, offsets, 0);
        }
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

        String sourceText = text.toString();
        int visibleEnd = LyricTextLayout.visibleRowEnd(sourceText, 0, sourceText.length());
        String rowText = sourceText.substring(0, visibleEnd);
        try (TextLine line = shapeLine(rowText, font)) {
            float[] x = new float[count + 1];
            for (int i = 0; i <= count; i++) {
                x[i] = line.getCoordAtOffset(Math.min(offsets[i], visibleEnd));
            }
            int visibleOffset = 0;
            while (visibleOffset < rowText.length()) {
                int codePoint = rowText.codePointAt(visibleOffset);
                if (!Character.isWhitespace(codePoint)) break;
                visibleOffset += Character.charCount(codePoint);
            }
            WordSpan[] words = buildWordSpans(rowText, offsets, line);
            return new ShapedRow(from, to, line.getTextBlob(), line.getWidth(),
                    line.getCoordAtOffset(visibleOffset), x, words);
        }
    }

    ShapedText[] shapeWrappedText(String text, Font font, float maxWidth) {
        if (text == null || text.isEmpty()) return new ShapedText[]{shapeText("", font)};
        UnicodeBreaks.Analysis breaks = UnicodeBreaks.analyze(text);
        int[] offsets = breaks.graphemes;
        ArrayList<ShapedText> rows = new ArrayList<>();
        try (TextLine full = shapeLine(text, font)) {
            float[] widths = measureAdvances(text, font, full, offsets, 0);
            int[] starts = LyricTextLayout.wrapTextStarts(breaks, widths, maxWidth);
            for (int i = 0; i + 1 < starts.length; i++) {
                int start = starts[i];
                int end = LyricTextLayout.visibleRowEnd(text, start, starts[i + 1]);
                rows.add(shapeText(text.substring(start, end), font));
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
        return base;
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
