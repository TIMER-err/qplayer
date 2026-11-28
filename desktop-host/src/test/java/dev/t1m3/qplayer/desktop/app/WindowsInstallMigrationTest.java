package dev.t1m3.qplayer.desktop.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class WindowsInstallMigrationTest {
    @Rule public TemporaryFolder root = new TemporaryFolder();

    @Test public void detectsOnlyRegisteredRunningCopy() throws Exception {
        Path oldDir = root.newFolder("old location").toPath();
        Path app = Files.createFile(oldDir.resolve("qplayer.exe"));
        Path uninstaller = Files.createFile(oldDir.resolve("unins000.exe"));
        Path userDir = root.newFolder("user location").toPath();
        Path userApp = Files.createFile(userDir.resolve("qplayer.exe"));
        assertNotNull(WindowsInstallMigration.match(app, entry(oldDir, uninstaller), userApp));
        assertNull(WindowsInstallMigration.match(userApp, entry(oldDir, uninstaller), userApp));
        assertNull(WindowsInstallMigration.match(app, entry(oldDir, uninstaller), app));
        assertNull(WindowsInstallMigration.match(app, entry(oldDir, uninstaller),
                oldDir.resolve("nested").resolve("qplayer.exe")));
    }

    @Test public void rejectsAnUninstallerOutsideTheRegisteredDirectory() throws Exception {
        Path oldDir = root.newFolder("legacy").toPath();
        Path app = Files.createFile(oldDir.resolve("qplayer.exe"));
        Path elsewhere = root.newFolder("elsewhere").toPath();
        Path uninstaller = Files.createFile(elsewhere.resolve("unins000.exe"));
        assertNull(WindowsInstallMigration.match(app, entry(oldDir, uninstaller),
                root.getRoot().toPath().resolve("user/qplayer.exe")));
    }

    private static Map<String, Object> entry(Path installed, Path uninstaller) throws IOException {
        return Map.of("DisplayName", "QPlayer 1.9.3", "InstallLocation", installed.toRealPath().toString(),
                "UninstallString", "\"" + uninstaller + "\"");
    }
}
