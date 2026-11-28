package dev.t1m3.qplayer.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/** A single verified background download. Never selects or runs an installer. */
final class StagedAppUpdate {
    private static final List<String> ASSETS = Arrays.asList("QPlayer-android.apk",
            "QPlayer-windows-x64-setup.exe", "QPlayer-windows-x64-user-setup.exe",
            "QPlayer-windows-x64-portable-update.exe", "QPlayer-macos-arm64.dmg",
            "QPlayer-linux-x64.AppImage");
    private static final long MAX_BYTES = 512L * 1024 * 1024;
    final String url;
    private final String digest;
    private final long size;
    private final Path file;

    StagedAppUpdate(ReleaseUpdateChecker.Update release, Path directory) throws IOException {
        this.url = release.assetUrl();
        this.digest = release.digest();
        this.size = release.size();
        if (!isTrustedAsset(url, release.version())
                || !digest.matches("sha256:[0-9a-f]{64}") || size < 1 || size > MAX_BYTES) {
            throw new IOException("Release has no eligible, verifiable update asset");
        }
        file = directory.resolve("verified-update");
    }

    Path file() { return file; }
    String sha256() { return digest.substring("sha256:".length()); }

    static boolean isTrustedAsset(String url, String version) {
        try {
            URI uri = URI.create(url);
            String prefix = "/TIMER-err/qplayer/releases/download/v" + version + "/";
            String path = uri.getRawPath();
            if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null
                    || uri.getFragment() != null || path == null
                    || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                    || !path.regionMatches(true, 0, prefix, 0, prefix.length())) return false;
            return ASSETS.contains(path.substring(prefix.length()));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    void download(BooleanSupplier allowed) throws IOException {
        if (!allowed.getAsBoolean()) throw new IOException("Background update canceled");
        Files.createDirectories(file.getParent());
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            try (InputStream in = Files.newInputStream(file)) {
                verify(in, null, allowed);
                return;
            } catch (IOException stale) {
                Files.deleteIfExists(file);
            }
        }
        Path part = file.resolveSibling("verified-update.part");
        Files.deleteIfExists(part); // only this app's dedicated, single-worker staging path
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "qplayer-updater");
        try {
            if (connection.getResponseCode() != 200
                    || !"https".equalsIgnoreCase(connection.getURL().getProtocol()))
                throw new IOException("Invalid update download response");
            try (InputStream in = connection.getInputStream();
                 OutputStream out = Files.newOutputStream(part, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                verify(in, out, allowed);
            }
            if (!allowed.getAsBoolean()) throw new IOException("Background update canceled");
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
            Files.deleteIfExists(part);
        }
    }

    /** Verify while copying so an edited/corrupt cache entry is never handed off as a staged update. */
    void copyVerified(Path destination, BooleanSupplier allowed) throws IOException {
        Path part = Files.createTempFile(destination.getParent(), ".qplayer-update-", ".tmp");
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Staged update was removed");
            try (InputStream in = Files.newInputStream(file); OutputStream out = Files.newOutputStream(part)) {
                verify(in, out, allowed);
            }
            if (!allowed.getAsBoolean()) throw new IOException("Background update canceled");
            Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private void verify(InputStream in, OutputStream out, BooleanSupplier allowed) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 unavailable", error);
        }
        byte[] buffer = new byte[64 * 1024];
        long count = 0;
        for (int n; (n = in.read(buffer)) != -1;) {
            count += n;
            if (count > size || !allowed.getAsBoolean()) throw new IOException("Invalid update size or canceled");
            sha.update(buffer, 0, n);
            if (out != null) out.write(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder("sha256:");
        for (byte b : sha.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!allowed.getAsBoolean() || count != size || !digest.contentEquals(hex)) throw new IOException("Update digest mismatch");
    }
}
