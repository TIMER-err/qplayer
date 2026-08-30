package dev.t1m3.qplayer.plugin;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

public final class PluginCompatibilityTest {
    @Test public void acceptsCurrentApiAndHost() {
        PluginManifest manifest = manifest("1.0", "1.4.0");
        PluginCompatibility.requireCompatible(manifest);
        assertTrue(PluginCompatibility.compare("1.4.1", "1.4.0") > 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsFutureHost() {
        PluginCompatibility.requireCompatible(manifest("1.0", "9.0.0"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsFutureApiMajor() {
        PluginCompatibility.requireCompatible(manifest("2.0", "1.0.0"));
    }

    private static PluginManifest manifest(String api, String host) {
        PluginManifest manifest = new PluginManifest();
        manifest.apiVersion = api;
        manifest.minHostVersion = host;
        return manifest;
    }
}
