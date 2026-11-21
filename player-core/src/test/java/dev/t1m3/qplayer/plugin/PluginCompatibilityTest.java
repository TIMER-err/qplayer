package dev.t1m3.qplayer.plugin;

import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class PluginCompatibilityTest {

    @After public void restoreBaseline() {
        PluginCompatibility.setHostVersion("1.5.0");
    }

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

    /** The gate used to compare against a constant that nobody bumped, so a
     *  plugin requiring the version the app actually was got rejected. */
    @Test public void acceptsAPluginRequiringExactlyTheRunningVersion() {
        PluginCompatibility.setHostVersion("1.5.0");
        PluginCompatibility.requireCompatible(manifest("1.0", "1.5.0"));
        PluginCompatibility.requireHostAtLeast("1.5.0");

        PluginCompatibility.setHostVersion("1.7.2");
        PluginCompatibility.requireCompatible(manifest("1.0", "1.5.0"));
    }

    /** Debug/perf builds carry a suffix; it is not part of the version order. */
    @Test public void ignoresABuildSuffix() {
        PluginCompatibility.setHostVersion("1.5.0-debug");
        PluginCompatibility.requireCompatible(manifest("1.0", "1.5.0"));
    }

    @Test public void keepsTheBaselineWhenAHostReportsNothing() {
        PluginCompatibility.setHostVersion("1.6.0");
        PluginCompatibility.setHostVersion("");
        PluginCompatibility.setHostVersion(null);
        assertEquals("1.6.0", PluginCompatibility.hostVersion());
    }

    private static PluginManifest manifest(String api, String host) {
        PluginManifest manifest = new PluginManifest();
        manifest.apiVersion = api;
        manifest.minHostVersion = host;
        return manifest;
    }
}
