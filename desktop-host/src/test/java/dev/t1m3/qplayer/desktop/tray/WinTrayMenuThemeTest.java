package dev.t1m3.qplayer.desktop.tray;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WinTrayMenuThemeTest {

    @Test
    public void preferredAppModeFollowsTheAppTheme() {
        assertEquals(2, WinTray.preferredAppMode(true));
        assertEquals(3, WinTray.preferredAppMode(false));
    }
}
