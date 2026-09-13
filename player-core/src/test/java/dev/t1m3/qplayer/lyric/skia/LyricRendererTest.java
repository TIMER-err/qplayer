package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LrcParser;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricTimeline;
import dev.t1m3.qplayer.lyric.Syllable;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LyricRendererTest {

    @Test
    public void glowFollowsTheMeasuredWordAndStaysBoundedOutsideIt() {
        assertEquals(1f, LyricRowRenderer.glowWindow(100f, 0f, 200f, 20f), 0.001f);
        assertTrue(LyricRowRenderer.glowWindow(10f, 0f, 200f, 20f) > 0f);
        assertTrue(LyricRowRenderer.glowWindow(190f, 0f, 200f, 20f) > 0f);
        assertEquals(0f, LyricRowRenderer.glowWindow(-20f, 0f, 200f, 20f), 0.001f);
        assertEquals(0f, LyricRowRenderer.glowWindow(300f, 0f, 200f, 20f), 0.001f);
        assertEquals(1f, LyricRowRenderer.glowWindow(30f, 30f, 30f, 0f), 0.001f);
    }

    @Test
    public void brokenShaderResourceDisablesOnlyTheLiftEffect() {
        AtomicBoolean compilerCalled = new AtomicBoolean();

        assertNull(LyricRowRenderer.compileShaderResource(
                () -> { throw new ZipException("invalid LOC header"); },
                source -> {
                    compilerCalled.set(true);
                    return null;
                }));
        assertFalse(compilerCalled.get());
    }

    @Test
    public void shaderCompilerFailureAlsoDegradesWithoutEscaping() {
        assertNull(LyricRowRenderer.compileShaderResource(
                () -> new ByteArrayInputStream(
                        "not valid sksl".getBytes(StandardCharsets.UTF_8)),
                source -> { throw new IllegalArgumentException("compile failed"); }));
    }

    @Test
    public void repeatedPlainLrcPreparationDoesNotBecomePerSyllable() {
        List<LyricLine> cached = LrcParser.parse(
                "[00:01.00]这是普通歌词\n[00:05.00]这是下一行");

        LyricTimeline.Prepared first = LyricTimeline.prepare(cached, false);
        assertFalse(first.animatablePerToken);
        assertTrue(first.lines.get(0).syllables.size() > 1); // private wrap tokens
        assertEquals(1, cached.get(0).syllables.size());    // cache stays pristine

        LyricTimeline.Prepared second = LyricTimeline.prepare(cached, false);
        assertFalse(second.animatablePerToken);
        assertEquals(1, cached.get(0).syllables.size());
    }

    @Test
    public void plainLrcLinearSettingStillEnablesSyntheticSweep() {
        List<LyricLine> cached = LrcParser.parse(
                "[00:01.00]这是普通歌词\n[00:05.00]这是下一行");

        LyricTimeline.Prepared prepared = LyricTimeline.prepare(cached, true);

        assertTrue(prepared.animatablePerToken);
        assertTrue(prepared.lines.get(0).syllables.size() > 1);
        assertEquals(1, cached.get(0).syllables.size());
    }

    @Test
    public void sharedTimelineSelectsCurrentAndNextCompactLines() {
        LyricTimeline.Prepared prepared = LyricTimeline.prepare(LrcParser.parse(
                "[00:01.00]第一行\n[00:05.00]第二行\n[00:09.00]第三行"), false);

        LyricTimeline.Frame frame = LyricTimeline.frameAt(prepared, 6_000L);

        assertEquals("第一行", frame.previous);
        assertEquals("第二行", frame.current);
        assertEquals("第三行", frame.next);
        StringBuilder timedText = new StringBuilder();
        for (Syllable syllable : frame.currentSyllables) timedText.append(syllable.text);
        assertEquals("第二行", timedText.toString());
        assertEquals(1, frame.groupIndex);
    }

    @Test
    public void desktopOverflowScrollStopsExactlyAtTheTrailingEdge() {
        assertEquals(0f, DesktopLyricRenderer.scrollOffset(300f, 200f, -1f), 0.001f);
        assertEquals(50f, DesktopLyricRenderer.scrollOffset(300f, 200f, 0.5f), 0.001f);
        assertEquals(100f, DesktopLyricRenderer.scrollOffset(300f, 200f, 1f), 0.001f);
        assertEquals(100f, DesktopLyricRenderer.scrollOffset(300f, 200f, 2f), 0.001f);
        assertEquals(0f, DesktopLyricRenderer.scrollOffset(120f, 200f, 1f), 0.001f);
    }

    @Test
    public void desktopLineChangeUsesBoundedNonLinearEaseOut() {
        assertEquals(0f, DesktopLyricRenderer.transitionEasing(-1f), 0.001f);
        assertTrue(DesktopLyricRenderer.transitionEasing(0.5f) > 0.5f);
        assertEquals(1f, DesktopLyricRenderer.transitionEasing(2f), 0.001f);
    }

    @Test
    public void sharedTimelineKeepsFollowingBackgroundVocalInActiveGroup() {
        LyricLine main = new LyricLine();
        main.syllables.add(new Syllable("主唱", 1_000L, 3_000L));
        LyricLine background = new LyricLine();
        background.vocalChannel = LyricLine.VocalChannel.BACKGROUND;
        background.syllables.add(new Syllable("和声", 1_500L, 2_000L));
        LyricLine next = new LyricLine();
        next.syllables.add(new Syllable("下一句", 5_000L, 2_000L));

        LyricTimeline.Frame frame = LyricTimeline.frameAt(
                LyricTimeline.prepare(Arrays.asList(main, background, next), false), 2_000L);

        assertEquals("主唱  ·  和声", frame.current);
        StringBuilder timedText = new StringBuilder();
        for (Syllable syllable : frame.currentSyllables) timedText.append(syllable.text);
        assertEquals(frame.current, timedText.toString());
        assertEquals("下一句", frame.next);
    }

    @Test
    public void splitLatinSyllablesFormOneDisplayWordButKeepBothAnimationSegments() {
        // UTF-16 offsets for timed syllables "en" + "dure".
        int[][] words = LyricTextLayout.displayWordSyllableRanges(
                "endure forever", new int[]{0, 2, 6, 7, 14});

        assertEquals(2, words.length);
        assertEquals(0, words[0][0]);
        assertEquals(6, words[0][1]);
        assertEquals(0, words[0][2]);
        assertEquals(1, words[0][3]);
    }

    @Test
    public void everyVisibleWordGetsItsOwnGlowGroup() {
        int[][] words = LyricTextLayout.displayWordRanges("hold on, 再见");

        assertEquals(4, words.length); // hold, on, 再, 见
        assertEquals("hold", "hold on, 再见".substring(words[0][0], words[0][1]));
        assertEquals("on", "hold on, 再见".substring(words[1][0], words[1][1]));
    }

    @Test
    public void wrappingBalancesAOneWordOrphanAcrossBothRows() {
        List<Syllable> words = Arrays.asList(
                new Syllable("one ", 0, 100),
                new Syllable("two ", 100, 100),
                new Syllable("three ", 200, 100),
                new Syllable("four", 300, 100));

        int[] starts = LyricTextLayout.wrapStarts(words,
                new float[]{30f, 30f, 30f, 30f}, 100f);

        assertEquals(3, starts.length);
        assertEquals(0, starts[0]);
        assertEquals(2, starts[1]);
        assertEquals(4, starts[2]);
    }

    @Test
    public void wrappingKeepsGreedyShapeWhenFinalRowIsNotTooShort() {
        List<Syllable> words = Arrays.asList(
                new Syllable("one ", 0, 100),
                new Syllable("two ", 100, 100),
                new Syllable("three ", 200, 100),
                new Syllable("four ", 300, 100),
                new Syllable("five", 400, 100));

        int[] starts = LyricTextLayout.wrapStarts(words,
                new float[]{30f, 30f, 30f, 30f, 30f}, 100f);

        assertEquals(3, starts.length);
        assertEquals(0, starts[0]);
        assertEquals(3, starts[1]);
        assertEquals(5, starts[2]);
    }

    @Test
    public void noSpaceMinorityScriptStillHasEmergencyWrapPoints() {
        List<Syllable> khmer = Arrays.asList(
                new Syllable("ខ្ញុំ", 0, 100),
                new Syllable("ស្រឡាញ់", 100, 100),
                new Syllable("ភាសា", 200, 100),
                new Syllable("ខ្មែរ", 300, 100));

        int[] starts = LyricTextLayout.wrapStarts(khmer,
                new float[]{30f, 30f, 30f, 30f}, 65f);

        assertEquals(3, starts.length);
        assertEquals(0, starts[0]);
        assertEquals(2, starts[1]);
        assertEquals(4, starts[2]);
    }

    @Test
    public void wrappingNeverStrandsKhmerCoengAtThePreviousRowEnd() {
        List<Syllable> conjunct = Arrays.asList(
                new Syllable("ខ្", 0, 100),
                new Syllable("ម ", 100, 100),
                new Syllable("បន្ទាប់", 200, 100));

        int[] starts = LyricTextLayout.wrapStarts(conjunct,
                new float[]{40f, 40f, 30f}, 50f);

        assertEquals(3, starts.length);
        assertEquals(0, starts[0]);
        assertEquals(2, starts[1]);
        assertEquals(3, starts[2]);
    }
}
