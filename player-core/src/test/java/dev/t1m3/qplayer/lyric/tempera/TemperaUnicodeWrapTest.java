package dev.t1m3.qplayer.lyric.tempera;

import io.github.timer_err.qml4j.render.text.UnicodeBreaks;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class TemperaUnicodeWrapTest {
    @Test public void segmentsPreserveExtendedGraphemesAndUtf16Offsets() {
        String text = "主词（e\u0301👩🏽‍🚀🇨🇳）尾！";
        List<String> expected = Arrays.asList("主", "词", "（", "e\u0301", "👩🏽‍🚀", "🇨🇳", "）", "尾", "！");
        assertEquals(expected, TemperaText.splitLyricGraphemes(text));
        TemperaTypes.SourceLine line = new TemperaTypes.SourceLine(text, 2, 11);
        List<TemperaTypes.Segment> segments = TemperaProgram.buildTemperaSegments(line);
        List<String> actual = new ArrayList<>();
        int offset = 0;
        for (TemperaTypes.Segment segment : segments) {
            assertEquals(offset, segment.startOffset);
            assertEquals(text.substring(segment.startOffset, segment.endOffset), segment.text);
            assertEquals(segment.text, segment.graphemes.stream().map(g -> g.charText).collect(Collectors.joining()));
            for (TemperaTypes.GraphemeTiming glyph : segment.graphemes) actual.add(glyph.charText);
            offset = segment.endOffset;
        }
        assertEquals(text.length(), offset);
        assertEquals(expected, actual);
        assertEquals(2, segments.get(0).startTime, 0);
        assertEquals(11, segments.get(segments.size() - 1).endTime, 0);
    }

    @Test public void syllableAndWordBoundariesInsideAClusterKeepTheirWholeTimeCoverage() {
        TemperaTypes.Word word = new TemperaTypes.Word("e\u0301X", 1, 4)
                .addSyllable("e", 1, 2).addSyllable("\u0301", 2, 3).addSyllable("X", 3, 4);
        List<TemperaTypes.GraphemeTiming> syllables = TemperaText.buildLineGraphemeTimeline(
                new TemperaTypes.SourceLine("e\u0301X", 1, 4).addWord(word));
        assertEquals(2, syllables.size());
        assertTiming(syllables.get(0), "e\u0301", 1, 3);
        assertTiming(syllables.get(1), "X", 3, 4);

        List<TemperaTypes.GraphemeTiming> words = TemperaText.buildLineGraphemeTimeline(
                new TemperaTypes.SourceLine("e\u0301👩🏽‍🚀", 1, 5)
                        .addWord(new TemperaTypes.Word("e", 1, 2))
                        .addWord(new TemperaTypes.Word("\u0301", 2, 3))
                        .addWord(new TemperaTypes.Word("👩🏽‍🚀", 3, 5)));
        assertEquals(2, words.size());
        assertTiming(words.get(0), "e\u0301", 1, 3);
        assertTiming(words.get(1), "👩🏽‍🚀", 3, 5);
    }

    @Test public void narrowRowsKeepPunctuationAndBracketsWithTheirNeighbours() {
        assertEquals(Arrays.asList("主", "词，"), rowTexts(pack("主词，", 1, 0)));
        assertEquals(Arrays.asList("(A", "B)"), rowTexts(pack("(AB)", 1, 0)));
        assertEquals(Arrays.asList("（👩🏽‍🚀）", "e\u0301", "尾！"),
                rowTexts(pack("（👩🏽‍🚀）e\u0301尾！", 1, 0)));
    }

    @Test public void normalWordBoundaryWinsOverEmergencyAndUnbreakableLinksOverflowTogether() {
        assertEquals("ab ", rowTexts(pack("ab cd", 5, 0)).get(0));
        assertEquals(Arrays.asList("a\u00a0b"), rowTexts(pack("a\u00a0b", 1, 0)));
        assertEquals(Arrays.asList("a\u2060b"), rowTexts(pack("a\u2060b", 1, 0)));
        assertEquals(Arrays.asList("ab\n", "cd"), rowTexts(pack("ab\ncd", 100, 0)));
    }

    @Test public void ordinaryWordTimingAndMissingPunctuationTimingStayUnchanged() {
        List<TemperaTypes.GraphemeTiming> timeline = TemperaText.buildLineGraphemeTimeline(
                new TemperaTypes.SourceLine("Hi, world!", 1, 9)
                        .addWord(new TemperaTypes.Word("Hi", 1, 3))
                        .addWord(new TemperaTypes.Word("world", 4, 9)));
        assertTiming(timeline.get(0), "H", 1, 2);
        assertTiming(timeline.get(1), "i", 2, 3);
        assertTiming(timeline.get(2), ",", 4, 4);
        assertTiming(timeline.get(3), " ", 4, 4);
        assertTiming(timeline.get(4), "w", 4, 5);
        assertTiming(timeline.get(8), "d", 8, 9);
        assertTiming(timeline.get(9), "!", 9, 9);
    }

    @Test public void sceneFilteringKeepsMandatorySourceBreaksButNotPlainSpaces() {
        TemperaTypes.Segment segment = new TemperaTypes.Segment();
        segment.text = "   ";
        assertFalse(TemperaLayout.isLayoutSegment(segment));
        segment.text = "\r\n";
        assertTrue(TemperaLayout.isLayoutSegment(segment));
        segment.text = "\u00a0";
        assertTrue(TemperaLayout.isLayoutSegment(segment));
    }

    @Test public void packingPreservesStyleTimingAndOriginalLineOrder() {
        List<TemperaMeasure.WordUnit> units = units("主词（👩🏽‍🚀）尾！", 0);
        units.addAll(units("e\u0301X", 1));
        List<TemperaLayout.LayoutRow> rows = TemperaLayout.packRows(units, 2, 17);
        assertEquals("主词（👩🏽‍🚀）尾！e\u0301X", String.join("", rowTexts(rows)));
        int previousLine = 0;
        for (TemperaLayout.LayoutRow row : rows) {
            int lineIndex = row.words.get(0).lineIndex;
            assertTrue(lineIndex >= previousLine);
            previousLine = lineIndex;
            for (TemperaMeasure.WordUnit word : row.words) {
                assertEquals(lineIndex, word.lineIndex);
                assertEquals(1.5f, word.scale, 0);
                assertEquals("glyph positions retain their shaped advances", word.width, word.glyphs.size(), 0);
                for (int index = 0; index < word.glyphs.size(); index++) {
                    TemperaMeasure.WordGlyph glyph = word.glyphs.get(index);
                    assertEquals(index, glyph.offset, 0);
                    assertEquals(word.glyphStart + index, glyph.startTime, 0);
                    assertEquals(glyph.startTime + 1, glyph.endTime, 0);
                }
            }
        }
    }

    private static List<TemperaLayout.LayoutRow> pack(String text, float width, int seed) {
        return TemperaLayout.packRows(units(text, 0), width, seed);
    }

    private static List<TemperaMeasure.WordUnit> units(String text, int lineIndex) {
        UnicodeBreaks.Analysis breaks = UnicodeBreaks.analyze(text);
        List<TemperaMeasure.WordUnit> out = new ArrayList<>();
        for (int index = 1; index < breaks.graphemes.length; index++) {
            int start = breaks.graphemes[index - 1];
            int end = breaks.graphemes[index];
            TemperaMeasure.WordUnit unit = new TemperaMeasure.WordUnit();
            unit.lineIndex = lineIndex;
            unit.segmentIndex = 0;
            unit.text = text.substring(start, end);
            unit.startOffset = start;
            unit.endOffset = end;
            unit.width = 1;
            unit.scale = 1.5f;
            unit.glyphStart = index - 1;
            unit.lineBreakAfter = breaks.line[end];
            unit.emergencyBreakAfter = breaks.emergency[end];
            unit.mandatoryBreakAfter = breaks.mandatory[end];
            TemperaMeasure.WordGlyph glyph = new TemperaMeasure.WordGlyph();
            glyph.charText = unit.text;
            glyph.width = 1;
            glyph.startTime = index - 1;
            glyph.endTime = index;
            unit.startTime = glyph.startTime;
            unit.endTime = glyph.endTime;
            unit.glyphs.add(glyph);
            out.add(unit);
        }
        return out;
    }

    private static List<String> rowTexts(List<TemperaLayout.LayoutRow> rows) {
        return rows.stream().map(row -> row.words.stream().flatMap(word -> word.glyphs.stream())
                .map(glyph -> glyph.charText).collect(Collectors.joining())).collect(Collectors.toList());
    }

    private static void assertTiming(TemperaTypes.GraphemeTiming actual, String text, double start, double end) {
        assertEquals(text, actual.charText);
        assertEquals(start, actual.startTime, 0);
        assertEquals(end, actual.endTime, 0);
    }
}
