package dev.t1m3.qplayer.desktop.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** For a writable AppImage only: don't change its inode while it is mounted. */
final class AppImageUpdate {
    @FunctionalInterface
    interface Copy {
        void copyTo(Path destination) throws IOException;
    }

    static boolean activateRunningImage(Copy copy, BooleanSupplier allowed) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "");
        if (!os.contains("linux") || !("amd64".equals(arch) || "x86_64".equals(arch))) return false;
        String image = System.getenv("APPIMAGE");
        String appDir = System.getenv("APPDIR");
        String command = ProcessHandle.current().info().command().orElse(null);
        if (image == null || appDir == null || command == null) return false;
        Path mount = Path.of(appDir);
        if (!mount.isAbsolute()
                || !Files.isSameFile(mount.resolve("usr/bin/qplayer"), Path.of(command))) return false;
        return replace(Path.of(image), copy, allowed);
    }

    static boolean replace(Path image, Copy copy, BooleanSupplier allowed) throws IOException {
        if (!allowed.getAsBoolean() || !image.isAbsolute() || !Files.isRegularFile(image, LinkOption.NOFOLLOW_LINKS)
                || !Files.isWritable(image) || !Files.isExecutable(image) || !isAppImage(image)) return false;
        Path next = Files.createTempFile(image.getParent(), ".qplayer-appimage-", ".tmp");
        try {
            copy.copyTo(next);
            if (!isAppImage(next)) throw new IOException("Downloaded file is not an AppImage");
            Files.setPosixFilePermissions(next, Files.getPosixFilePermissions(image));
            try (FileChannel channel = FileChannel.open(next, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // If atomic replacement isn't supported, leave the running image untouched.
            // Existing mounts keep the old inode alive; the kernel reclaims it on exit.
            if (!allowed.getAsBoolean()) return false;
            Files.move(next, image, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } finally {
            Files.deleteIfExists(next);
        }
    }

    private static boolean isAppImage(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] b = in.readNBytes(11);
            return b.length == 11 && b[0] == 0x7f && b[1] == 'E' && b[2] == 'L' && b[3] == 'F'
                    && b[8] == 'A' && b[9] == 'I' && b[10] == 2;
        }
    }

    private AppImageUpdate() {}
}
