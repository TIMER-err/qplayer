package dev.t1m3.qplayer.desktop.window;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class InputBridgeDropTest {

    @Test
    public void firstDroppedPluginPicksTheQplugAndIgnoresOtherFiles() {
        assertEquals("C:\\plugins\\netease.qplug",
                InputBridge.firstDroppedPlugin(new String[] {
                        "C:\\Music\\song.mp3",
                        "C:\\plugins\\netease.qplug",
                        "C:\\plugins\\other.qplug"
                }));
        assertEquals("/tmp/Source.QPLUG",
                InputBridge.firstDroppedPlugin(new String[] { "/tmp/Source.QPLUG" }));
        assertNull(InputBridge.firstDroppedPlugin(new String[] { "readme.txt", "cover.png" }));
        assertNull(InputBridge.firstDroppedPlugin(new String[0]));
        assertNull(InputBridge.firstDroppedPlugin(null));
    }
}
