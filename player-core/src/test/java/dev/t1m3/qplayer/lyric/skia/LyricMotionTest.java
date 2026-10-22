package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LrcParser;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricTimeline;
import dev.t1m3.qplayer.lyric.Syllable;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LyricMotionTest {

    private static LyricTimeline.Group group(long startMs, long endMs) {
        LyricLine line = new LyricLine();
        line.syllables.add(new Syllable("line", startMs, endMs - startMs));
        return LyricTimeline.prepare(Collections.singletonList(line), false).groups.get(0);
    }

    @Test
    public void activeCurveFadesInHoldsAndFadesOut() {
        LyricTimeline.Group group = group(1_000L, 3_000L);

        assertEquals(0f, LyricMotion.active(500L, group), 0f);
        assertTrue(LyricMotion.active(800L, group) > 0f);
        assertEquals(1f, LyricMotion.active(1_200L, group), 0f);
        assertEquals(1f, LyricMotion.active(3_050L, group), 0f);
        assertTrue(LyricMotion.active(3_250L, group) < 1f);
        assertEquals(0f, LyricMotion.active(3_450L, group), 0f);
    }

    @Test
    public void backgroundScaleUsesDelayedPopAndReturnsToZero() {
        LyricTimeline.Group group = group(1_000L, 3_000L);

        assertEquals(0f, LyricMotion.backgroundScale(1_100L, group), 0f);
        assertTrue(LyricMotion.backgroundScale(1_300L, group) > 0f);
        assertEquals(1f, LyricMotion.backgroundScale(2_000L, group), 0f);
        assertEquals(0f, LyricMotion.backgroundScale(3_280L, group), 0f);
    }

    @Test
    public void interludeSlotRampsAtBothEdges() {
        assertEquals(0f, LyricMotion.interludeSlot(999L, 1_000L, 2_000L, 40f), 0f);
        assertEquals(0f, LyricMotion.interludeSlot(1_000L, 1_000L, 2_000L, 40f), 0f);
        assertEquals(40f, LyricMotion.interludeSlot(1_500L, 1_000L, 2_000L, 40f), 0f);
        assertTrue(LyricMotion.interludeSlot(1_950L, 1_000L, 2_000L, 40f) < 40f);
        assertEquals(0f, LyricMotion.interludeSlot(2_001L, 1_000L, 2_000L, 40f), 0f);
    }

    @Test
    public void cloudFlyReturnsHomeBeforeTheNextLineEnters() {
        LyricTimeline.Prepared prepared = LyricTimeline.prepare(LrcParser.parse(
                "[00:01.00]第一行\n[00:05.00]第二行"), false);
        LyricTimeline.Group first = prepared.groups.get(0);
        LyricTimeline.Group second = prepared.groups.get(1);

        assertEquals(1f, LyricMotion.cloudFly(2_000L, first, second), 0f);
        float midOut = LyricMotion.cloudFly(second.startMs - 50L, first, second);
        float midIn = LyricMotion.cloudFly(second.startMs - 50L, second, null);
        assertTrue(midOut > 0f);
        assertEquals(0f, midIn, 0f);
        assertEquals(0f, LyricMotion.cloudFly(second.startMs, first, second), 0.01f);
        assertTrue(LyricMotion.cloudFly(second.startMs + 50L, second, null) > 0f);
    }
}
