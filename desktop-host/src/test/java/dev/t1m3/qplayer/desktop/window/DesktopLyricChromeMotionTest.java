package dev.t1m3.qplayer.desktop.window;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DesktopLyricChromeMotionTest {

    @Test
    public void canvasMotionProducesIntermediateFramesAtSixtyHertz() {
        DesktopLyricChromeMotion motion = new DesktopLyricChromeMotion();
        motion.update(false, 0L, 0.28f);
        motion.update(true, 1L, 0.28f);

        Set<Integer> visibleSteps = new HashSet<>();
        for (int frame = 1; frame <= 9; frame++) {
            float opacity = motion.update(true, frame * 16_666_667L, 0.28f).opacity();
            visibleSteps.add(Math.round(opacity * 1000f));
        }

        assertTrue("motion must not collapse to two endpoint frames", visibleSteps.size() >= 7);
    }

    @Test
    public void expansionIsBalancedInLogicalPixelsAndReversible() {
        DesktopLyricChromeMotion motion = new DesktopLyricChromeMotion();
        DesktopLyricChromeMotion.Frame hidden = motion.update(false, 0L, 0.28f);
        assertEquals(18f, (hidden.scaleX() - 1f) * DesktopLyricWindow.WIDTH * 0.5f, 0.01f);
        assertEquals(18f, (hidden.scaleY() - 1f) * DesktopLyricWindow.HEIGHT * 0.5f, 0.01f);

        motion.update(true, 1L, 0.28f);
        DesktopLyricChromeMotion.Frame shown = motion.update(true,
                DesktopLyricChromeMotion.DURATION_NANOS + 1L, 0.28f);
        assertEquals(1f, shown.opacity(), 0.0001f);
        assertEquals(1f, shown.scaleX(), 0.0001f);
        assertEquals(1f, shown.scaleY(), 0.0001f);
    }

    /** settings.desktopLyricIdleOpacity feeds straight through to the hidden
     *  background alpha; the shown alpha stays fixed regardless (a hover
     *  always needs the same, more opaque backdrop to keep the buttons usable). */
    @Test
    public void idleOpacitySettingControlsOnlyTheHiddenBackgroundAlpha() {
        DesktopLyricChromeMotion low = new DesktopLyricChromeMotion();
        DesktopLyricChromeMotion high = new DesktopLyricChromeMotion();
        assertEquals(0.15f, low.update(false, 0L, 0.15f).backgroundOpacity(), 0.0001f);
        assertEquals(0.90f, high.update(false, 0L, 0.90f).backgroundOpacity(), 0.0001f);

        low.update(true, 1L, 0.15f);
        high.update(true, 1L, 0.90f);
        float shownLow = low.update(true, DesktopLyricChromeMotion.DURATION_NANOS + 1L, 0.15f)
                .backgroundOpacity();
        float shownHigh = high.update(true, DesktopLyricChromeMotion.DURATION_NANOS + 1L, 0.90f)
                .backgroundOpacity();
        assertEquals("the shown (hovered) alpha does not depend on the idle setting",
                shownLow, shownHigh, 0.0001f);
    }
}
