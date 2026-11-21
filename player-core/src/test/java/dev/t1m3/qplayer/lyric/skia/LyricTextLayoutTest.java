package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.Syllable;
import io.github.timer_err.qml4j.render.text.UnicodeBreaks;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LyricTextLayoutTest {

    @Test
    public void cjkPunctuationDoesNotBecomeAnEmergencyRowStart() {
        String text = "你好，世界。再见！";
        List<String> rows = timedRows(text, 20f);
        assertTrue(rows.size() > 1);
        assertEquals(text, String.join("", rows));
        for (String row : rows) {
            assertFalse(row.startsWith("，"));
            assertFalse(row.startsWith("。"));
            assertFalse(row.startsWith("！"));
        }
    }

    @Test
    public void parenthesesStayAttachedEvenWhenTheirSmallestUnitOverflows() {
        List<String> rows = timedRows("甲（乙丙）丁", 10f);
        assertTrue(rows.size() > 1);
        assertEquals("甲（乙丙）丁", String.join("", rows));
        for (String row : rows) {
            assertFalse(row.endsWith("（"));
            assertFalse(row.startsWith("）"));
        }
    }

    @Test
    public void ordinaryEnglishWordBreakWinsOverALaterEmergencyBoundary() {
        assertEquals(Arrays.asList("hello ", "world"), timedRows("hello world", 90f));
    }

    @Test
    public void aLongEnglishWordCanFallBackWithoutLosingText() {
        String text = "supercalifragilistic";
        List<String> rows = timedRows(text, 30f);
        assertTrue(rows.size() > 1);
        assertEquals(text, String.join("", rows));
        for (String row : rows) assertTrue(row.length() <= 3);
    }

    @Test
    public void nonBreakingSpacesAndWordJoinersRemainIndivisible() {
        for (String text : Arrays.asList("a\u00a0b", "a\u202fb", "a\u2060b")) {
            assertEquals(Collections.singletonList(text), timedRows(text, 1f));
            assertEquals(Collections.singletonList(text), secondaryRows(text, 1f, false));
        }
    }

    @Test
    public void timedSyllableBoundariesCannotSplitEmojiFlagsOrCombiningMarks() {
        String text = "👩‍🚀🇨🇳e\u0301X";
        List<String> rows = timedRows(text, 1f);
        assertEquals(Arrays.asList("👩‍🚀", "🇨🇳", "e\u0301", "X"), rows);
        assertArrayEquals(new int[]{0, 5, 9, 11, 12}, LyricTextLayout.graphemeBoundaries(text));
    }

    @Test
    public void secondaryRowsUseTheSameRulesForTranslationAndRomanization() {
        for (String text : Arrays.asList("你好，世界。", "甲（乙丙）丁", "hello world",
                "👩‍🚀🇨🇳e\u0301X", "a\u00a0b c", "one\ntwo\r\nthree")) {
            assertEquals(text, timedRows(text, 30f), secondaryRows(text, 30f, false));
        }
    }

    @Test
    public void hardBreaksKeepEmptyRowsAndCrLfIsOneBreak() {
        String text = "one\n(two)\r\n\nlast\u2028";
        List<String> expectedSourceRows = Arrays.asList("one\n", "(two)\r\n", "\n", "last\u2028", "");
        assertEquals(expectedSourceRows, timedRows(text, 1000f));
        assertEquals(expectedSourceRows, secondaryRows(text, 1000f, false));
        assertEquals(Arrays.asList("one", "(two)", "", "last", ""), secondaryRows(text, 1000f, true));
    }

    @Test
    public void finalOrphanBalancingCannotMoveAnExplicitLineBreak() {
        String text = "hello world\nx";
        assertEquals(Arrays.asList("hello world\n", "x"), timedRows(text, 1000f));
        assertEquals(Arrays.asList("hello world", "x"), secondaryRows(text, 1000f, true));
    }

    @Test
    public void fragmentTimingCoversTheOriginalWithoutChangingSourceOffsets() {
        Syllable source = new Syllable("a👩‍🚀🇨🇳e\u0301z", 1234L, 7L);
        UnicodeBreaks.Analysis breaks = UnicodeBreaks.analyze(source.text);
        float[] widths = new float[breaks.graphemes.length - 1];
        Arrays.fill(widths, 10f);
        int[] offsets = LyricTextLayout.wrapTextStarts(breaks, widths, 1f);
        List<Syllable> fragments = LyricTextLayout.splitTimedSyllable(source, offsets);
        StringBuilder reconstructed = new StringBuilder();
        long nextStart = source.startMs;
        for (int i = 0; i < fragments.size(); i++) {
            Syllable fragment = fragments.get(i);
            assertEquals(source.text.substring(offsets[i], offsets[i + 1]), fragment.text);
            assertEquals(nextStart, fragment.startMs);
            assertTrue(fragment.durationMs >= 0L);
            nextStart = fragment.startMs + fragment.durationMs;
            reconstructed.append(fragment.text);
        }
        assertEquals(source.text, reconstructed.toString());
        assertEquals(source.startMs + source.durationMs, nextStart);
        assertEquals("a👩‍🚀🇨🇳e\u0301z", source.text);
        assertEquals(1234L, source.startMs);
        assertEquals(7L, source.durationMs);
    }

    @Test
    public void aHardBreakInsideATokenRetainsItsSourceTextAndTiming() {
        Syllable source = new Syllable("first\r\nsecond", 100L, 101L);
        UnicodeBreaks.Analysis breaks = UnicodeBreaks.analyze(source.text);
        float[] widths = new float[breaks.graphemes.length - 1];
        Arrays.fill(widths, 10f);
        int[] offsets = LyricTextLayout.wrapTextStarts(breaks, widths, 1000f);
        List<Syllable> fragments = LyricTextLayout.splitTimedSyllable(source, offsets);
        assertEquals(2, fragments.size());
        assertEquals("first\r\n", fragments.get(0).text);
        assertEquals("second", fragments.get(1).text);
        assertEquals(source.startMs, fragments.get(0).startMs);
        assertEquals(fragments.get(0).startMs + fragments.get(0).durationMs, fragments.get(1).startMs);
        assertEquals(source.startMs + source.durationMs,
                fragments.get(1).startMs + fragments.get(1).durationMs);
    }

    @Test
    public void unsplitTokensAndWrappingKeepTheOriginalSyllableObjects() {
        Syllable first = new Syllable("hello ", 100L, 300L);
        Syllable second = new Syllable("world", 400L, 700L);
        List<Syllable> source = Arrays.asList(first, second);
        assertSame(first, LyricTextLayout.splitTimedSyllable(first, new int[]{0, first.text.length()}).get(0));
        LyricTextLayout.wrapStarts(source, new float[]{60f, 50f}, 90f);
        assertSame(first, source.get(0));
        assertSame(second, source.get(1));
        assertEquals(100L, first.startMs);
        assertEquals(300L, first.durationMs);
        assertEquals(400L, second.startMs);
        assertEquals(700L, second.durationMs);
    }

    @Test
    public void displayWordHighlightingDoesNotCutAnExtendedGrapheme() {
        String text = "か\u3099 e\u0301";
        int[][] ranges = LyricTextLayout.displayWordRanges(text);
        assertEquals(2, ranges.length);
        assertEquals("か\u3099", text.substring(ranges[0][0], ranges[0][1]));
        assertEquals("e\u0301", text.substring(ranges[1][0], ranges[1][1]));
    }

    private static List<String> timedRows(String text, float width) {
        List<Syllable> syllables = new ArrayList<>();
        for (int offset = 0; offset < text.length();) {
            int end = offset + Character.charCount(text.codePointAt(offset));
            syllables.add(new Syllable(text.substring(offset, end), offset * 100L, 100L));
            offset = end;
        }
        float[] widths = new float[syllables.size()];
        Arrays.fill(widths, 10f);
        int[] starts = LyricTextLayout.wrapStarts(syllables, widths, width);
        List<String> rows = new ArrayList<>();
        for (int i = 0; i + 1 < starts.length; i++) {
            StringBuilder row = new StringBuilder();
            for (int index = starts[i]; index < starts[i + 1]; index++) row.append(syllables.get(index).text);
            rows.add(row.toString());
        }
        return rows;
    }

    private static List<String> secondaryRows(String text, float width, boolean visible) {
        UnicodeBreaks.Analysis breaks = UnicodeBreaks.analyze(text);
        float[] widths = new float[breaks.graphemes.length - 1];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = text.codePointCount(breaks.graphemes[i], breaks.graphemes[i + 1]) * 10f;
        }
        int[] starts = LyricTextLayout.wrapTextStarts(breaks, widths, width);
        List<String> rows = new ArrayList<>();
        for (int i = 0; i + 1 < starts.length; i++) {
            int end = visible ? LyricTextLayout.visibleRowEnd(text, starts[i], starts[i + 1]) : starts[i + 1];
            rows.add(text.substring(starts[i], end));
        }
        return rows;
    }
}
