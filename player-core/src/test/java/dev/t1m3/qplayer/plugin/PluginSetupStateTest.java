package dev.t1m3.qplayer.plugin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PluginSetupStateTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void promptsUntilUserAcknowledgesSetup() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("config/source-setup.json");
        PluginSetupState state = new PluginSetupState(file);

        assertFalse(state.acknowledged());
        state.acknowledge();
        assertTrue(new PluginSetupState(file).acknowledged());
    }

    @Test public void corruptOrUnsupportedStatePromptsAgain() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("source-setup.json");
        Files.write(file, "{\"schemaVersion\":99,\"acknowledged\":true}"
                .getBytes(StandardCharsets.UTF_8));

        assertFalse(new PluginSetupState(file).acknowledged());
    }
}
