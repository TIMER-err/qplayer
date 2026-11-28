package dev.t1m3.qplayer.desktop.app;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.desktop.window.DesktopWindow;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class WindowsInstallMigration {
    private static final String KEY = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\"
            + "{8F3A1C2E-5B6D-4E7F-9A0B-1C2D3E4F5A6B}_is1";
    private static final String SIGNAL_DIR = "qplayer-switch-";
    private static final String READY = "profile-ready";
    private static final String SWITCH = """
            $ErrorActionPreference = 'Stop'
            $folder = $env:QPLAYER_SWITCH_SIGNAL
            $old = $env:QPLAYER_SWITCH_OLD
            $new = $env:QPLAYER_SWITCH_NEW
            $parent = Get-Process -Id ([int]$env:QPLAYER_SWITCH_PARENT) -ErrorAction SilentlyContinue
            function Notice($text) {
                try { (New-Object -ComObject WScript.Shell).Popup($text, 0, 'QPlayer', 48) | Out-Null }
                catch {}
            }
            try {
                if ($parent -and $parent.ProcessName -eq 'qplayer' -and -not $parent.WaitForExit(30000)) {
                    Notice $env:QPLAYER_SWITCH_FAILED
                    exit 2
                }
                $child = Start-Process -FilePath $new -WorkingDirectory ([IO.Path]::GetDirectoryName($new)) -PassThru
                $signal = Join-Path $folder 'profile-ready'
                for ($i = 0; $i -lt 300 -and -not (Test-Path -LiteralPath $signal -PathType Leaf); $i++) {
                    if ($child.HasExited) { break }
                    Start-Sleep -Milliseconds 200
                }
                if ($child.HasExited -or -not (Test-Path -LiteralPath $signal -PathType Leaf)) {
                    Notice $env:QPLAYER_SWITCH_FAILED
                    if ($child.HasExited) { Start-Process -FilePath $old }
                    exit 3
                }
                $uninstaller = $env:QPLAYER_SWITCH_UNINSTALL
                if (Test-Path -LiteralPath $uninstaller -PathType Leaf) {
                    try {
                        $r = Start-Process -FilePath $uninstaller -Wait -PassThru
                        if ($r.ExitCode -ne 0 -or (Test-Path -LiteralPath $old -PathType Leaf)) {
                            Notice $env:QPLAYER_SWITCH_CLEANUP_FAILED
                        }
                    } catch { Notice $env:QPLAYER_SWITCH_CLEANUP_FAILED }
                }
            } catch {
                Notice $env:QPLAYER_SWITCH_FAILED
            } finally {
                Remove-Item -LiteralPath (Join-Path $folder 'profile-ready') -ErrorAction SilentlyContinue
                Remove-Item -LiteralPath $folder -ErrorAction SilentlyContinue
            }
            """;

    private final Path oldApp;
    private final Path uninstaller;
    private final Path newApp;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean ready;

    private WindowsInstallMigration(Path oldApp, Path uninstaller, Path newApp) {
        this.oldApp = oldApp;
        this.uninstaller = uninstaller;
        this.newApp = newApp;
    }

    static WindowsInstallMigration find() {
        String local = System.getenv("LOCALAPPDATA");
        String command = ProcessHandle.current().info().command().orElse(null);
        if (local == null || command == null) return null;
        Path current = Path.of(command).toAbsolutePath();
        for (int view : new int[]{0x0100, 0x0200}) { // 64-bit, then 32-bit HKLM
            try {
                Map<String, Object> entry = Advapi32Util.registryGetValues(WinReg.HKEY_LOCAL_MACHINE, KEY, view);
                WindowsInstallMigration found = match(current, entry,
                        Path.of(local, "Programs", "QPlayer", "qplayer.exe"));
                if (found != null) return found;
            } catch (RuntimeException | IOException ignored) {
                // An unregistered/copied executable is not a machine installation.
            }
        }
        return null;
    }

    static WindowsInstallMigration match(Path current, Map<String, Object> entry, Path newApp)
            throws IOException {
        if (!(entry.get("DisplayName") instanceof String name)
                || !(name.equals("QPlayer") || name.startsWith("QPlayer "))
                || !(entry.get("InstallLocation") instanceof String location) || location.isBlank()
                || !(entry.get("UninstallString") instanceof String uninstall)
                || uninstall.length() < 3 || !uninstall.startsWith("\"") || !uninstall.endsWith("\""))
            return null;
        Path oldDir = Path.of(location);
        Path remove = Path.of(uninstall.substring(1, uninstall.length() - 1));
        if (!oldDir.isAbsolute() || !remove.isAbsolute() || remove.getParent() == null
                || !remove.getFileName().toString().matches("(?i)unins[0-9]{3}\\.exe")
                || !Files.isRegularFile(remove, LinkOption.NOFOLLOW_LINKS)) return null;
        Path realOld = oldDir.toRealPath();
        Path oldApp = realOld.resolve("qplayer.exe");
        Path newDir = newApp.getParent().toAbsolutePath().normalize();
        if (Files.exists(newDir)) newDir = newDir.toRealPath();
        if (newDir.startsWith(realOld) || realOld.startsWith(newDir)
                || !Files.isRegularFile(oldApp, LinkOption.NOFOLLOW_LINKS)
                || !Files.isSameFile(oldApp, current)
                || !Files.isSameFile(realOld, remove.getParent())
                || !remove.toRealPath().getParent().equals(realOld)) return null;
        return new WindowsInstallMigration(oldApp, remove.toRealPath(), newApp);
    }

    void start(PlayerController controller, DesktopWindow window) {
        if (!running.compareAndSet(false, true)) return;
        Thread task = new Thread(() -> {
            Path temp = null;
            boolean installed = false;
            try {
                controller.showToast(I18n.tr("settings.userInstall.downloading"));
                temp = Files.createTempDirectory("qplayer-user-installer-");
                Path setup = temp.resolve("QPlayer-windows-x64-user-setup.exe");
                controller.prepareWindowsUserInstaller(setup);
                Process installer = new ProcessBuilder(setup.toString(), "/QPLAYERMIGRATION=1").start();
                if (installer.waitFor() != 0) {
                    controller.showToast(I18n.tr("settings.userInstall.failed"));
                    return;
                }
                if (!Files.isRegularFile(newApp, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isRegularFile(newApp.resolveSibling(".qplayer-user-install"),
                                                LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("User installer did not produce the expected installation");
                window.postMainTask(() -> {
                    ready = true;
                    window.requestQuit();
                });
                installed = true;
            } catch (IOException | InterruptedException | RuntimeException failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                Logger.warn("QPlayer installation switch failed: {}", failure.toString());
                controller.showToast(I18n.tr("settings.userInstall.failed"));
            } finally {
                if (temp != null) {
                    try (var files = Files.list(temp)) {
                        for (Path file : files.toList()) Files.deleteIfExists(file);
                    } catch (IOException failure) {
                        Logger.warn("Could not clean QPlayer installer staging: {}", failure.toString());
                    }
                    try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
                }
                if (!installed) running.set(false);
            }
        }, "qplayer-user-install");
        task.setDaemon(true);
        task.start();
    }

    void switchAfterExit() {
        if (!ready) return;
        Path signal = null;
        try {
            String root = System.getenv("SystemRoot");
            if (root == null) throw new IOException("Windows system directory is unavailable");
            signal = Files.createTempDirectory(SIGNAL_DIR);
            Path powershell = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            ProcessBuilder pb = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile",
                    "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand",
                    Base64.getEncoder().encodeToString(SWITCH.getBytes(StandardCharsets.UTF_16LE)));
            Map<String, String> env = pb.environment();
            env.put("QPLAYER_SWITCH_SIGNAL", signal.toString());
            env.put("QPLAYER_SWITCH_PARENT", Long.toString(ProcessHandle.current().pid()));
            env.put("QPLAYER_SWITCH_OLD", oldApp.toString());
            env.put("QPLAYER_SWITCH_NEW", newApp.toString());
            env.put("QPLAYER_SWITCH_UNINSTALL", uninstaller.toString());
            env.put("QPLAYER_SWITCH_PROFILE", Path.of(AppDirs.base()).toRealPath().toString());
            env.put("QPLAYER_SWITCH_FAILED", I18n.tr("settings.userInstall.failed"));
            env.put("QPLAYER_SWITCH_CLEANUP_FAILED", I18n.tr("settings.userInstall.cleanupFailed"));
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
        } catch (IOException | RuntimeException failure) {
            if (signal != null) try { Files.deleteIfExists(signal); } catch (IOException ignored) {}
            Logger.warn("Could not finish the QPlayer installation switch: {}", failure.toString());
        }
    }

    /** Only the installed recipient, with the same profile initialized, acknowledges startup. */
    static void acknowledgeProfile() {
        String value = System.getenv("QPLAYER_SWITCH_SIGNAL");
        String expected = System.getenv("QPLAYER_SWITCH_PROFILE");
        if (value == null || expected == null) return;
        WindowsUserUpdate.Target installed = WindowsUserUpdate.installedDirectory();
        if (installed == null || !"QPlayer-windows-x64-user-setup.exe".equals(installed.asset())) return;
        try {
            Path dir = Path.of(value);
            Path temp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            if (dir.getFileName() == null || !dir.getFileName().toString().startsWith(SIGNAL_DIR)
                    || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                    || !dir.toRealPath().getParent().equals(temp)
                    || !Files.isSameFile(Path.of(expected), Path.of(AppDirs.base()))) return;
            Files.createFile(dir.resolve(READY));
        } catch (IOException | RuntimeException failure) {
            Logger.warn("Could not acknowledge the migrated QPlayer profile: {}", failure.toString());
        }
    }
}
