package dev.t1m3.qplayer.lyric;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Sub-second digits in an LRC timestamp are a decimal fraction, so how many there
 * are sets the scale. The one-digit form used to fall through to a truncating
 * branch that read it unscaled — "[00:12.5]" became 12005ms instead of 12500ms,
 * pulling the line half a second early.
 */
public class LrcParserTimestampTest {

    private static long firstStart(String lrc) {
        List<LyricLine> lines = LrcParser.parse(lrc);
        assertEquals(1, lines.size());
        return lines.get(0).startMs();
    }

    @Test
    public void oneFractionDigitIsTenths() {
        assertEquals(12_500L, firstStart("[00:12.5]hello"));
    }

    @Test
    public void twoFractionDigitsAreCentiseconds() {
        assertEquals(12_050L, firstStart("[00:12.05]hello"));
    }

    @Test
    public void threeFractionDigitsAreMilliseconds() {
        assertEquals(12_005L, firstStart("[00:12.005]hello"));
    }

    @Test
    public void extraFractionDigitsTruncateToMilliseconds() {
        assertEquals(12_123L, firstStart("[00:12.12345]hello"));
    }

    @Test
    public void absentFractionKeepsWholeSeconds() {
        assertEquals(12_000L, firstStart("[00:12]hello"));
    }

    @Test
    public void minutesAndSecondsStillAccumulate() {
        assertEquals(3L * 60_000L + 7_500L, firstStart("[03:07.5]hello"));
    }

    @Test
    public void enhancedInlineTagsUseTheSameScale() {
        List<LyricLine> lines = LrcParser.parse("[00:10.0]<00:10.0>ab<00:10.5>cd");
        assertEquals(1, lines.size());
        List<Syllable> syllables = lines.get(0).syllables;
        assertEquals(2, syllables.size());
        assertEquals(10_000L, syllables.get(0).startMs);
        // 10.5 is half a second after 10.0, so the first syllable spans 500ms.
        assertEquals(500L, syllables.get(0).durationMs);
        assertEquals(10_500L, syllables.get(1).startMs);
    }
}
