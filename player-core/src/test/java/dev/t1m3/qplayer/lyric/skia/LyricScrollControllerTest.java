package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.Syllable;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void canceledDragKeepsItsPositionAndIgnoresLatePointerEvents() {
        LyricScrollController controller = new LyricScrollController();
        controller.setViewport(100f, 0f, 1000f);
        controller.setLastRenderedOffset(200f);
        controller.pointerDown(400f, 0L);
        controller.pointerMove(300f, 20_000_000L);
        controller.pointerCancel();
        controller.pointerMove(100f, 30_000_000L);
        controller.pointerUp(40_000_000L);

        assertTrue("Cancel must not jump back into automatic lyric following", controller.isActive());
        long now = 0L;
        assertEquals(300f, controller.step(0f, now, 0, null), 0.001f);
        assertEquals(300f, controller.step(0f, now + 1_000_000_000L, 0, null), 0.001f);
    }

    @Test
    public void coalescedInputKeepsTheSameLyricFlingAcrossRefreshRates() {
        for (long frame : new long[]{8_333_333L, 16_666_667L, 33_333_333L, 2_000_000_000L}) {
            LyricScrollController controller = flingingController();
            assertEquals(700f, controller.step(0f, 0L, 0, null), 0.001f);
            for (long time = frame; time < 2_000_000_000L; time += frame) {
                controller.step(0f, time, 0, null);
            }
            // The input clock starts at 1s, independently of this render clock.
            // 2000px/s, friction 4.2/s and a 5px/s stop threshold travel 475px.
            assertEquals(1175f, controller.step(0f, 2_000_000_000L, 0, null), 0.03f);
        }
    }

    @Test
    public void releaseContinuesLyricFlingInTheMoveDirection() {
        for (float direction : new float[]{-1f, 1f}) {
            LyricScrollController controller = new LyricScrollController();
            controller.setViewport(100f, 0f, 20000f);
            controller.setLastRenderedOffset(5000f);
            controller.pointerDown(400f, 1_000_000_000L);
            for (int sample = 1; sample <= 10; sample++) {
                controller.pointerMove(400f + direction * sample * 20f,
                        1_000_000_000L + sample * 10_000_000L);
            }
            // Finger lift advances time, but contributes no terminal position.
            controller.pointerUp(1_104_000_000L);

            float releasedOffset = 5000f - direction * 200f;
            assertEquals(releasedOffset, controller.step(0f, 0L, 0, null), 0.001f);
            float flungOffset = controller.step(0f, 100_000_000L, 0, null);
            assertTrue("Release must continue scrolling in the MOVE direction",
                    (flungOffset - releasedOffset) * -direction > 0f);
        }
    }

    @Test
    public void holdingBeforeReleaseDiscardsTheEarlierLyricVelocity() {
        LyricScrollController controller = new LyricScrollController();
        controller.setViewport(100f, 0f, 20000f);
        controller.setLastRenderedOffset(500f);
        controller.pointerDown(400f, 1_000_000_000L);
        controller.pointerMove(200f, 1_100_000_000L);
        controller.pointerUp(1_250_000_000L);

        assertEquals(700f, controller.step(0f, 0L, 0, null), 0.001f);
        assertEquals(700f, controller.step(0f, 1_000_000_000L, 0, null), 0.001f);
    }

    @Test
    public void cancelingAnActiveLyricFlingPinsItsDisplayedPosition() {
        LyricScrollController controller = flingingController();
        controller.step(0f, 0L, 0, null);
        float displayed = controller.step(0f, 100_000_000L, 0, null);
        controller.pointerCancel();
        controller.pointerUp(1_500_000_000L);

        assertTrue(controller.isActive());
        assertEquals(displayed, controller.step(0f, 200_000_000L, 0, null), 0.001f);
        assertEquals(displayed, controller.step(0f, 2_000_000_000L, 0, null), 0.001f);
    }

    private static LyricScrollController flingingController() {
        LyricScrollController controller = new LyricScrollController();
        controller.setViewport(100f, 0f, 20000f);
        controller.setLastRenderedOffset(500f);
        controller.pointerDown(400f, 1_000_000_000L);
        for (int sample = 1; sample <= 10; sample++) {
            controller.pointerMove(400f - sample * 20f, 1_000_000_000L + sample * 10_000_000L);
        }
        controller.pointerUp(1_100_000_000L);
        return controller;
    }

    private static LyricLine line(String text, long startMs) {
        LyricLine line = new LyricLine();
        line.syllables.add(new Syllable(text, startMs, 500L));
        return line;
    }
}
