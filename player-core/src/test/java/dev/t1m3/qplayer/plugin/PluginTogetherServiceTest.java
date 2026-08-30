package dev.t1m3.qplayer.plugin;

import dev.t1m3.qplayer.media.MediaKind;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class PluginTogetherServiceTest {
    @Test public void unwrapsCanonicalRoomAndUserIdsAtPluginBoundary() {
        assertEquals("room:42", PluginTogetherService.nativeOrLegacy("netease",
                MediaKind.ROOM, "netease:room:room%3A42", false));
        assertEquals("1001", PluginTogetherService.nativeOrLegacy("netease",
                MediaKind.USER, "netease:user:1001", true));
    }

    @Test public void acceptsLegacyBareInvitationIds() {
        assertEquals("legacy-room", PluginTogetherService.nativeOrLegacy("netease",
                MediaKind.ROOM, "legacy-room", false));
        assertEquals("1001", PluginTogetherService.nativeOrLegacy("netease",
                MediaKind.USER, "1001", true));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsCanonicalIdsOwnedByAnotherProvider() {
        PluginTogetherService.nativeOrLegacy("netease", MediaKind.ROOM,
                "other:room:42", false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWrongCanonicalKind() {
        PluginTogetherService.nativeOrLegacy("netease", MediaKind.ROOM,
                "netease:user:42", false);
    }
}
