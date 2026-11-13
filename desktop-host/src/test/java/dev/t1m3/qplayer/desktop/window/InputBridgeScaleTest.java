package dev.t1m3.qplayer.desktop.window;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InputBridgeScaleTest {

    @Test public void macDetectionIsPlatformSpecific() {
        assertTrue(MacInput.isMac("Mac OS X"));
        assertFalse(MacInput.isMac("Windows 11"));
        assertFalse(MacInput.isMac("Linux"));
    }

    @Test public void preciseTrackpadDeltaIsReduced() {
        assertEquals(0.2, MacInput.preciseScrollGain(), 0.0001);
    }

    @Test
    public void macCursorCoordinatesAreAlreadyLogicalPoints() {
        assertEquals(1f, InputBridge.pointerScale("Mac OS X", 2f), 0f);
    }

    @Test
    public void framebufferBasedPlatformsStillUseContentScale() {
        assertEquals(1.5f, InputBridge.pointerScale("Windows 11", 1.5f), 0f);
        assertEquals(2f, InputBridge.pointerScale("Linux", 2f), 0f);
    }

    @Test
    public void invalidContentScaleFallsBackToOne() {
        assertEquals(1f, InputBridge.pointerScale("Windows 11", 0f), 0f);
    }
}
