package dev.t1m3.qplayer.desktop.app;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prepare the entire signed bundle, then exchange it atomically after playback has stopped. */
final class MacBundleUpdate {
    private static final Pattern TEAM = Pattern.compile("(?m)^TeamIdentifier=([A-Z0-9]{10})$");
    private static final String SLOT_PREFIX = ".qplayer-update-";
    private final Path app;
    private final String team;
    private volatile Path prepared;

    private MacBundleUpdate(Path app, String team) {
        this.app = app;
        this.team = team;
    }

    static MacBundleUpdate findRunningBundle() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return null;
        String command = ProcessHandle.current().info().command().orElse(null);
        if (command == null) return null;
        try {
            Path exe = Path.of(command).toRealPath();
            if (!exe.endsWith(Path.of("Contents", "MacOS", "qplayer"))) return null;
            Path app = exe.getParent().getParent().getParent();
            if (!app.getFileName().toString().endsWith(".app") || !Files.isWritable(app)
                    || !Files.isWritable(app.getParent()) || !Files.isRegularFile(swapper(app))) return null;
            Process p = new ProcessBuilder("/usr/bin/codesign", "-dv", "--verbose=4", app.toString())
                    .redirectErrorStream(true).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            Matcher m = TEAM.matcher(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            return p.exitValue() == 0 && m.find() ? new MacBundleUpdate(app, m.group(1)) : null;
        } catch (IOException | RuntimeException error) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    boolean prepare(PlayerController.VerifiedUpdateCopy copy, BooleanSupplier allowed) throws IOException {
        Path slot = Files.createTempDirectory(app.getParent(), SLOT_PREFIX);
        Path dmg = slot.resolve("QPlayer.dmg");
        Path mount = Files.createDirectory(slot.resolve("mount"));
        Path next = slot.resolve("QPlayer.app");
        boolean retain = false;
        try {
            copy.copyTo(dmg);
            if (!allowed.getAsBoolean()) return false;
            Path script = app.resolve("Contents/Resources/qplayer-update-stage");
            if (!run(300, "/bin/sh", script.toString(), dmg.toString(),
                    mount.toString(), next.toString(), team)) return false;
            if (!allowed.getAsBoolean()) return false;
            Files.deleteIfExists(dmg);
            Files.delete(mount); // do not retain a mount that could not be detached
            prepared = next;
            retain = true;
            return false; // The download is ready, not activated until graceful quit.
        } finally {
            if (!retain) {
                // If the OS did not detach this mount, never walk into the mounted volume.
                try {
                    Files.deleteIfExists(mount);
                    removeSlot(slot);
                } catch (IOException busy) {
                    Logger.warn("Could not clear macOS staging mount: {}", busy.toString());
                }
            }
        }
    }

    void switchAfterPlaybackStops() {
        Path next = prepared;
        if (next == null) return;
        try {
            if (run(8, swapper(app).toString(), app.toString(), next.toString())) {
                // The helper has now exited and player shutdown already released its resources.
                // Do not block relaunch on removal of the previous complete bundle.
                new ProcessBuilder("/bin/rm", "-rf", "--", next.getParent().toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
            }
        } catch (IOException error) {
            Logger.warn("Could not switch the macOS bundle: {}", error.toString());
        }
    }

    private static Path swapper(Path app) {
        return app.resolve("Contents/MacOS/qplayer-update-swap");
    }

    private static boolean run(long seconds, String... command) throws IOException {
        Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("macOS updater interrupted", error);
        }
    }

    private static void removeSlot(Path p) {
        try {
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                try (DirectoryStream<Path> children = Files.newDirectoryStream(p)) {
                    for (Path child : children) removeSlot(child);
                }
            }
            Files.deleteIfExists(p);
        } catch (IOException error) {
            Logger.warn("Could not delete a macOS update temporary file: {}", error.toString());
        }
    }
}
