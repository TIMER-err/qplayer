package dev.t1m3.qplayer.desktop.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class AppImageUpdateTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void atomicReplaceKeepsOpenOldImageReadableWithoutAnOldFile() throws Exception {
        Path path = image();
        try (var existing = Files.newInputStream(path)) {
            assertTrue(AppImageUpdate.replace(path, out -> Files.write(out, content(2)), () -> true));
            assertArrayEquals(content(1), existing.readAllBytes());
        }
        assertArrayEquals(content(2), Files.readAllBytes(path));
        assertTrue(Files.isExecutable(path));
        try (var files = Files.list(path.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test public void invalidCopyAndLateOptOutLeaveOldImageAndNoTempFiles() throws Exception {
        Path path = image();
        assertThrows(IOException.class, () ->
                AppImageUpdate.replace(path, out -> Files.writeString(out, "invalid"), () -> true));
        AtomicBoolean allowed = new AtomicBoolean(true);
        assertFalse(AppImageUpdate.replace(path, out -> {
            Files.write(out, content(2));
            allowed.set(false);
        }, allowed::get));
        assertArrayEquals(content(1), Files.readAllBytes(path));
        try (var files = Files.list(path.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test public void doesNotFollowAUserSymlink() throws Exception {
        Path path = image();
        Path link = path.resolveSibling("alias.AppImage");
        Files.createSymbolicLink(link, path);
        assertFalse(AppImageUpdate.replace(link, ignored -> fail("unexpected write"), () -> true));
        assertTrue(Files.isSymbolicLink(link));
        assertArrayEquals(content(1), Files.readAllBytes(path));
    }

    private Path image() throws IOException {
        Path dir = tmp.newFolder().toPath();
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"));
        Path image = Files.write(dir.resolve("QPlayer.AppImage"), content(1));
        Files.setPosixFilePermissions(image, PosixFilePermissions.fromString("rwx------"));
        return image;
    }

    private static byte[] content(int version) {
        return new byte[] {0x7f, 'E', 'L', 'F', 0, 0, 0, 0, 'A', 'I', 2, (byte) version};
    }
}
