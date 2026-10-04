package dev.t1m3.qplayer.bridge;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PlayerControllerUpdateCheckTest {

    @Test
    public void aHigherPatchReleaseIsNewer() {
        assertTrue(PlayerController.isNewer("1.8.1", "1.8.0"));
        assertTrue(PlayerController.isNewer("1.9.0", "1.8.22"));
        assertFalse(PlayerController.isNewer("1.8.1", "1.8.1"));
        assertFalse(PlayerController.isNewer("1.8.0", "1.8.1"));
    }

    @Test
    public void debugSuffixDoesNotHideANewerRelease() {
        assertTrue(PlayerController.isNewer("1.8.1", "1.8.0-debug"));
        assertFalse(PlayerController.isNewer("1.8.0", "1.8.0-debug"));
    }

    @Test
    public void emptyVersionsAreNeverNewer() {
        assertFalse(PlayerController.isNewer("", "1.8.0"));
        assertFalse(PlayerController.isNewer("1.8.1", ""));
        assertFalse(PlayerController.isNewer(null, "1.8.0"));
        assertFalse(PlayerController.isNewer("1.8.1", null));
    }

    @Test
    public void aReleaseObjectNeedsATag() throws Exception {
        JsonObject obj = PlayerController.parseReleaseObject(
                "{\"tag_name\":\"v1.8.1\",\"body\":\"notes\"}");
        assertEquals("v1.8.1", obj.get("tag_name").getAsString());
    }

    @Test
    public void proxyHtmlOrEmptyJsonIsNotARelease() {
        assertInvalid("{");
        assertInvalid("[]");
        assertInvalid("{}");
        assertInvalid("{\"tag_name\":\"\"}");
        assertInvalid("<html>not json</html>");
    }

    private static void assertInvalid(String json) {
        try {
            PlayerController.parseReleaseObject(json);
            fail("expected invalid release JSON: " + json);
        } catch (IOException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }
}
