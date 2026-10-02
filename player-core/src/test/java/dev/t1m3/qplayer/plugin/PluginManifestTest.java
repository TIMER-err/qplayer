package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertThrows;

public class PluginManifestTest {
    @Test
    public void loginCapabilityRequiresCredentialPermission() {
        PluginManifest manifest = baseManifest();
        manifest.capabilities = Arrays.asList("login");

        assertThrows(IllegalArgumentException.class, manifest::validate);

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

        assertThrows(IllegalArgumentException.class, manifest::validate);
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
