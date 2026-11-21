package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.Syllable;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class LyricScrollControllerTest {

    @Test
    public void lineLookupClampsToNearestPreviousRow() {
        float[] lineTops = {20f, 80f, 160f};

        assertEquals(0, LyricScrollController.lineIndexAt(lineTops, 3, -10f));
        assertEquals(0, LyricScrollController.lineIndexAt(lineTops, 3, 79f));
        assertEquals(1, LyricScrollController.lineIndexAt(lineTops, 3, 80f));
        assertEquals(2, LyricScrollController.lineIndexAt(lineTops, 3, 999f));
    }

    @Test
    public void screenPositionMapsBackToLyricTime() {
        LyricScrollController controller = new LyricScrollController();
        controller.setViewport(100f, 0f, 200f);
        controller.setLastRenderedOffset(50f);

        LyricLine first = line("first", 1_000L);
        LyricLine second = line("second", 2_000L);
        float[] lineTops = {0f, 60f};
        float[] lineHeights = {40f, 40f};

        assertEquals(1_000L, controller.timeAtScreenY(
                60f, Arrays.asList(first, second), lineTops, lineHeights));
        assertEquals(2_000L, controller.timeAtScreenY(
                120f, Arrays.asList(first, second), lineTops, lineHeights));
        assertEquals(-1L, controller.timeAtScreenY(
                151f, Arrays.asList(first, second), lineTops, lineHeights));
    }

    private static LyricLine line(String text, long startMs) {
        LyricLine line = new LyricLine();
        line.syllables.add(new Syllable(text, startMs, 500L));
        return line;
    }
}
