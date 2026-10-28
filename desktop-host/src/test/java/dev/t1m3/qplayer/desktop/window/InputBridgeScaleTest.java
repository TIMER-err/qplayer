package dev.t1m3.qplayer.desktop.window;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class InputBridgeScaleTest {

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
