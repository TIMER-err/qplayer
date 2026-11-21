package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PluginManifestTest {
    @Test
    public void loginCapabilityRequiresCredentialPermission() {
        PluginManifest manifest = baseManifest();
        manifest.capabilities = Arrays.asList("login");

        try {
            manifest.validate();
            fail("login without credential permission must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("credentials"));
        }

        manifest.permissions = Arrays.asList("credentials");
        manifest.validate();
    }

    @Test
    public void customUiRequiresExplicitPermission() {
        PluginManifest manifest = baseManifest();
        PluginManifest.UiContribution ui = new PluginManifest.UiContribution();
        ui.id = "settings";
        ui.placement = "settings";
        ui.source = "ui/settings.qml";
        manifest.ui = Arrays.asList(ui);

        try {
            manifest.validate();
            fail("custom QML without permission must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("customUi"));
        }
    }

    private static PluginManifest baseManifest() {
        PluginManifest manifest = new PluginManifest();
        manifest.schemaVersion = 1;
        manifest.id = "fixture";
        manifest.name = "Fixture";
        manifest.version = "1.0.0";
        manifest.apiVersion = "1.0";
        manifest.entry = "main.js";
        return manifest;
    }
}
