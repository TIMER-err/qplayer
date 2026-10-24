package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * Cached-audio / explicit song downloads follow playback: the first URL must sit
 * inside {@code networkDomains}, later CDN hops do not. Loopback and LAN are
 * still blocked on every hop.
 */
public class CorePluginHostApiPlaybackRedirectTest {

    @Test
    public void undeclaredHopIsRejectedWhenTheGrantIsEnforced() throws Exception {
        SecurityException error = assertThrows(SecurityException.class,
                () -> validate(granted("ws.stream.qqmusic.qq.com"),
                        "https://dl.stream.qqmusic.qq.com/song.mp3", true));
        assertEquals("network domain is not declared: dl.stream.qqmusic.qq.com",
                error.getMessage());
    }

    @Test
    public void undeclaredPublicHopIsAllowedWhenFollowingPlayback() throws Exception {
        URL url = validate(granted("ws.stream.qqmusic.qq.com"),
                "https://8.8.8.8/song.mp3", false);
        assertEquals("8.8.8.8", url.getHost());
    }

    @Test
    public void loopbackStaysBlockedOnAPlaybackHop() throws Exception {
        PluginManifest manifest = granted("ws.stream.qqmusic.qq.com");
        manifest.permissions = Arrays.asList("network", "clearTextNetwork");
        SecurityException error = assertThrows(SecurityException.class,
                () -> validate(manifest, "http://127.0.0.1/song.mp3", false));
        assertEquals("private and local network addresses are blocked", error.getMessage());
    }

    private static URL validate(PluginManifest manifest, String raw, boolean enforce)
            throws Exception {
        Method method = CorePluginHostApi.class.getDeclaredMethod(
                "validateNetworkUrl", PluginManifest.class, String.class, boolean.class);
        method.setAccessible(true);
        try {
            return (URL) method.invoke(null, manifest, raw, enforce);
        } catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }

    private static PluginManifest granted(String domain) {
        PluginManifest manifest = new PluginManifest();
        manifest.schemaVersion = 1;
        manifest.id = "fixture";
        manifest.name = "Fixture";
        manifest.version = "1.0.0";
        manifest.apiVersion = "1.0";
        manifest.entry = "main.js";
        manifest.permissions = Arrays.asList("network");
        manifest.networkDomains = Arrays.asList(domain);
        return manifest;
    }
}
