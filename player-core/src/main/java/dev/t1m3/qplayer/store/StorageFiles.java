package dev.t1m3.qplayer.store;

import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Common persistence primitives for QPlayer's state, configuration and cache files.
 * Writes always land in a sibling pending file first and then replace the target,
 * so a process kill cannot leave half a JSON document behind.
 */
public final class StorageFiles {

    private StorageFiles() {
    }

    public static String readUtf8(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    public static void writeUtf8Atomic(Path target, String content) throws IOException {
        writeUtf8Atomic(target, content, false);
    }

    /** Write UTF-8 atomically, optionally restricting the final file to its owner. */
    public static synchronized void writeUtf8Atomic(
            Path target, String content, boolean ownerOnly) throws IOException {
        writeBytesAtomic(target, content.getBytes(StandardCharsets.UTF_8), ownerOnly);
    }

    public static synchronized void writeBytesAtomic(Path target, byte[] content) throws IOException {
        writeBytesAtomic(target, content, false);
    }

    /** Atomic write for encrypted credentials and key material. The pending and
     * final files are both restricted before they become observable. */
    public static synchronized void writeCredentialBytesAtomic(Path target, byte[] content)
            throws IOException {
        writeBytesAtomic(target, content, true);
    }

    static synchronized void writeBytesAtomic(Path target, byte[] content, boolean ownerOnly)
            throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path pending = pendingPath(target);
        try {
            Files.write(pending, content);
            // Credential writes must never expose their complete pending file with
            // default umask permissions, even for the brief interval before rename.
            if (ownerOnly) restrictToOwner(pending);
            replace(pending, target);
            if (ownerOnly) restrictToOwner(target);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    public static Path pendingPath(Path target) {
        return target.resolveSibling(target.getFileName().toString() + ".pending");
    }

    /** Replace a completed sibling pending file with its final destination. */
    public static synchronized void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Move an old file/directory into the new layout only when the target is absent. */
    static synchronized boolean moveIfAbsent(Path legacy, Path target) {
        if (legacy == null || target == null || !Files.exists(legacy) || Files.exists(target)) {
            return false;
        }
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            try {
                Files.move(legacy, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
                Files.move(legacy, target);
            }
            return true;
        } catch (IOException e) {
            Logger.warn("storage migration failed ({} -> {}): {}",
                    legacy.getFileName(), target, e.getMessage());
            return false;
        }
    }

    public static void restrictOwnerOnly(Path file) {
        if (file != null && Files.exists(file)) restrictToOwner(file);
    }

    private static void restrictToOwner(Path file) {
        // File's owner-only flags work on Android, Unix and Windows without making
        // player-core depend on a platform-specific PosixFilePermission API.
        try {
            java.io.File f = file.toFile();
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setReadable(true, true);
            f.setWritable(true, true);
        } catch (Throwable ignored) {
        }
    }
}
