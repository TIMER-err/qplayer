package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LrcParser;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricTimeline;
import dev.t1m3.qplayer.lyric.Syllable;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LyricMotionTest {

    private static LyricLine line(long startMs, long endMs) {
        LyricLine line = new LyricLine();
        line.syllables.add(new Syllable("line", startMs, endMs - startMs));
        return line;
    }

    private static LyricTimeline.Group group(long startMs, long endMs) {
        return LyricTimeline.prepare(Collections.singletonList(line(startMs, endMs)), false)
                .groups.get(0);
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
    public void lineHandsFocusToItsSuccessorUnlessTheyOverlap() {
        List<LyricTimeline.Group> groups = LyricTimeline.prepare(Arrays.asList(
                line(1_000L, 3_000L),     // followed back to back
                line(3_000L, 6_000L),     // overlapped by the next line
                line(4_000L, 5_000L)), false).groups;
        long[] release = LyricMotion.releaseTimes(groups);

        assertEquals("the scroll to the next line and the release are one event",
                LyricMotion.fadeInStart(groups.get(1)), release[0]);
        assertTrue("an overlapped line keeps focus until it is sung",
                release[1] > groups.get(1).endMs);
    }

    @Test
    public void backgroundLinePopsInWithFocusAndIsReclaimedOnRelease() {
        LyricTimeline.Group group = group(1_000L, 3_000L);
        long open = LyricMotion.fadeInStart(group);
        long release = 3_100L;

        assertEquals(0f, LyricMotion.backgroundPresence(open, group, release), 0f);
        assertTrue(LyricMotion.backgroundPresence(open + 400L, group, release) > 0.5f);
        assertEquals(1f, LyricMotion.backgroundPresence(2_500L, group, release), 0f);
        assertEquals(0f, LyricMotion.backgroundPresence(release + 300L, group, release), 0f);
        assertTrue(LyricMotion.focused(2_500L, group, release));
        assertTrue(!LyricMotion.focused(release, group, release));
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

    @Test
    public void ribbonRidesOnlyHeldWordsAndSettlesAfterThem() {
        assertEquals("short words carry no ribbon",
                0f, LyricMotion.ribbonEnvelope(1_400L, 1_000L, 1_900L), 0f);
        assertEquals(0f, LyricMotion.ribbonEnvelope(1_000L, 1_000L, 3_000L), 0f);
        assertEquals(1f, LyricMotion.ribbonEnvelope(2_000L, 1_000L, 3_000L), 0f);
        assertTrue(LyricMotion.ribbonEnvelope(3_200L, 1_000L, 3_000L) < 1f);
        assertEquals(0f, LyricMotion.ribbonEnvelope(3_500L, 1_000L, 3_000L), 0f);
    }
}
