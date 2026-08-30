package dev.t1m3.qplayer.plugin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

public final class PluginUiSessionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void resourcesCannotEscapePluginPackage() throws Exception {
        Path packageRoot = temporary.newFolder("plugin").toPath();
        Path inside = packageRoot.resolve("ui/icon.txt");
        Files.createDirectories(inside.getParent());
        Files.write(inside, "inside".getBytes(StandardCharsets.UTF_8));
        Files.write(temporary.getRoot().toPath().resolve("outside.txt"),
                "outside".getBytes(StandardCharsets.UTF_8));

        PluginUiSession.ConfinedResources resources =
                new PluginUiSession.ConfinedResources(packageRoot);
        assertArrayEquals("inside".getBytes(StandardCharsets.UTF_8),
                resources.load("ui/icon.txt"));
        assertNull(resources.load("../outside.txt"));
        assertNull(resources.load("file:/etc/passwd"));
        assertNull(resources.load("https://example.com/image.png"));
    }
}
