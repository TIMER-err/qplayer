package dev.t1m3.qplayer.desktop.app;

import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

/** A separate per-user Inno installer, run only after normal app exit. */
final class WindowsUserUpdate {
    private static final String RUN_INSTALLER = """
            $ErrorActionPreference = 'Stop'
            $parent = Get-Process -Id ([int]$env:QPLAYER_UPDATE_PARENT) -ErrorAction SilentlyContinue
            if ($parent -and $parent.ProcessName -eq 'qplayer' -and -not $parent.WaitForExit(30000)) { exit 2 }
            $source = $env:QPLAYER_UPDATE_SOURCE
            $temp = Join-Path ([IO.Path]::GetTempPath()) ('qplayer-' + [Guid]::NewGuid().ToString('N') + '.exe')
            try {
                [IO.File]::Copy($source, $temp, $false)
                if ((Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash -ne $env:QPLAYER_UPDATE_SHA256) { exit 3 }
                $dir = '/DIR="' + $env:QPLAYER_UPDATE_DIRECTORY + '"'
                $result = Start-Process -FilePath $temp -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES',
                    '/NORESTART', '/NOCLOSEAPPLICATIONS', '/NORESTARTAPPLICATIONS', '/SP-', $dir) -Wait -PassThru -WindowStyle Hidden
                exit $result.ExitCode
            } finally {
                Remove-Item -LiteralPath $temp -ErrorAction SilentlyContinue
            }
            """;

    record Target(Path directory, String asset) {}

    static Target installedDirectory() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return null;
        String local = System.getenv("LOCALAPPDATA");
        String command = ProcessHandle.current().info().command().orElse(null);
        if (local == null || command == null) return null;
        Path actual = Path.of(command).toAbsolutePath();
        Path dir = actual.getParent();
        Path exe = dir.resolve("qplayer.exe");
        Path perUser = Path.of(local).resolve("Programs").resolve("QPlayer");
        try {
            if (!Files.isWritable(dir) || !Files.isWritable(exe)
                    || !Files.isSameFile(exe, actual)) return null;
            if (Files.isRegularFile(dir.resolve("qplayer-portable.marker"), LinkOption.NOFOLLOW_LINKS))
                return new Target(dir, "QPlayer-windows-x64-portable-update.exe");
            if (Files.isRegularFile(dir.resolve(".qplayer-user-install"), LinkOption.NOFOLLOW_LINKS)
                    && Files.isSameFile(perUser, dir))
                return new Target(dir, "QPlayer-windows-x64-user-setup.exe");
        } catch (IOException ignored) {}
        return null;
    }

    static void runAfterExit(Path directory, Path asset, String digest) {
        try {
            String root = System.getenv("SystemRoot");
            if (root == null) return;
            Path powershell = Path.of(root).resolve("System32/WindowsPowerShell/v1.0/powershell.exe");
            String command = Base64.getEncoder().encodeToString(RUN_INSTALLER.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder process = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile",
                    "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", command);
            process.environment().put("QPLAYER_UPDATE_PARENT", Long.toString(ProcessHandle.current().pid()));
            process.environment().put("QPLAYER_UPDATE_SOURCE", asset.toAbsolutePath().toString());
            process.environment().put("QPLAYER_UPDATE_SHA256", digest);
            process.environment().put("QPLAYER_UPDATE_DIRECTORY", directory.toString());
            process.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            process.redirectError(ProcessBuilder.Redirect.DISCARD);
            process.start();
        } catch (IOException | RuntimeException error) {
            Logger.warn("Could not hand off the Windows update: {}", error.toString());
        }
    }

    private WindowsUserUpdate() {}
}
