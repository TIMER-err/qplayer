package dev.t1m3.qplayer.plugin;

import com.google.gson.Gson;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Loads a signed catalog and verifies/downloads its independently hosted packages. */
public final class PluginCatalogService {
    public static final String REMOTE_CATALOG_URL =
            "https://raw.githubusercontent.com/TIMER-err/qplayer/"
                    + "plugin-catalog/player-core/src/main/resources/plugin-catalog-v1.json";
    private static final String RESOURCE = "/plugin-catalog-v1.json";
    private static final String CATALOG_PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE4Ihxr2JTSXh9h3Bzh9EvKN0TYQsWreamrUqtHfuo+UQ1C7tIT08W/DTGhsh7nSxXiWRy4ysj/hRpCefkg+nUkQ==";
    private static final int MAX_CATALOG_BYTES = 2 * 1024 * 1024;
    private static final long MAX_PLUGIN_BYTES = 64L * 1024L * 1024L;
    private final Gson gson = new Gson();
    private final PluginPackageVerifier packageVerifier;
    private final CatalogDownloader catalogDownloader;

    public PluginCatalogService(PluginPackageVerifier packageVerifier) {
        this(packageVerifier, PluginCatalogService::downloadBytes);
    }

    PluginCatalogService(PluginPackageVerifier packageVerifier,
                         CatalogDownloader catalogDownloader) {
        this.packageVerifier = packageVerifier;
        this.catalogDownloader = catalogDownloader;
    }

    public List<PluginCatalogEntry> loadBundled() throws IOException, GeneralSecurityException {
        try (InputStream input = PluginCatalogService.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("plugin catalog resource is missing");
            return verifyEnvelope(readLimited(input, MAX_CATALOG_BYTES));
        }
    }

    /** Download and verify the current signed catalog, trying candidates in order. */
    public List<PluginCatalogEntry> loadRemote(String[] candidates)
            throws IOException, GeneralSecurityException {
        if (candidates == null || candidates.length == 0) {
            throw new IOException("plugin catalog has no remote URL candidates");
        }
        Exception last = null;
        for (String candidate : candidates) {
            try {
                return verifyEnvelope(catalogDownloader.download(candidate, MAX_CATALOG_BYTES));
            } catch (IOException | GeneralSecurityException | RuntimeException error) {
                last = error;
            }
        }
        if (last instanceof GeneralSecurityException) {
            throw (GeneralSecurityException) last;
        }
        if (last instanceof IOException) throw (IOException) last;
        throw new IOException("plugin catalog download failed", last);
    }

    public VerifiedPluginPackage downloadAndVerify(PluginCatalogEntry entry)
            throws IOException, GeneralSecurityException {
        return downloadAndVerify(entry,
                new String[]{entry == null ? "" : entry.downloadUrl});
    }

    public VerifiedPluginPackage downloadAndVerify(PluginCatalogEntry entry, String[] candidates)
            throws IOException, GeneralSecurityException {
        validateEntry(entry);
        Path target = AppDirs.cacheDir().resolve("plugin-downloads")
                .resolve(entry.id + "-" + entry.version + ".qplug").normalize();
        Files.createDirectories(target.getParent());
        Path pending = StorageFiles.pendingPath(target);
        try {
            downloadFirst(candidates, pending, MAX_PLUGIN_BYTES);
            String actual = hex(digestFile(pending));
            if (!actual.equals(entry.sha256.toLowerCase(Locale.ROOT))) {
                throw new GeneralSecurityException("catalog package digest mismatch");
            }
            StorageFiles.replace(pending, target);
            PublicKey publisher = decodeKey(entry.publisherKey);
            VerifiedPluginPackage verified = packageVerifier.verify(target, publisher, false);
            if (!entry.id.equals(verified.manifest().id)
                    || !entry.version.equals(verified.manifest().version)) {
                throw new GeneralSecurityException("catalog package identity mismatch");
            }
            return verified;
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private static void downloadFirst(String[] candidates, Path target, long limit)
            throws IOException {
        if (candidates == null || candidates.length == 0) {
            throw new IOException("plugin download has no URL candidates");
        }
        IOException last = null;
        for (String candidate : candidates) {
            try {
                download(candidate, target, limit);
                return;
            } catch (IOException error) {
                last = error;
                Files.deleteIfExists(target);
            }
        }
        throw last != null ? last : new IOException("plugin download failed");
    }

    private List<PluginCatalogEntry> verifyEnvelope(byte[] encoded)
            throws IOException, GeneralSecurityException {
        Envelope envelope = gson.fromJson(new String(encoded, StandardCharsets.UTF_8), Envelope.class);
        if (envelope == null || envelope.payload == null || envelope.signature == null) {
            throw new IOException("plugin catalog envelope is invalid");
        }
        final byte[] payload;
        final byte[] signature;
        try {
            payload = Base64.getDecoder().decode(envelope.payload);
            signature = Base64.getDecoder().decode(envelope.signature);
        } catch (IllegalArgumentException error) {
            throw new GeneralSecurityException("plugin catalog signature encoding is invalid", error);
        }
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(decodeKey(CATALOG_PUBLIC_KEY));
        verifier.update(payload);
        if (!verifier.verify(signature)) throw new GeneralSecurityException("plugin catalog signature is invalid");
        Catalog catalog = gson.fromJson(new String(payload, StandardCharsets.UTF_8), Catalog.class);
        if (catalog == null || catalog.schemaVersion != 1 || catalog.entries == null) {
            throw new IOException("unsupported plugin catalog");
        }
        List<PluginCatalogEntry> entries = new ArrayList<>();
        for (PluginCatalogEntry entry : catalog.entries) {
            validateEntry(entry);
            entries.add(entry);
        }
        return Collections.unmodifiableList(entries);
    }

    private static void validateEntry(PluginCatalogEntry entry) throws IOException {
        if (entry == null) throw new IOException("catalog entry is null");
        try { dev.t1m3.qplayer.media.MediaId.validateProvider(entry.id); }
        catch (IllegalArgumentException error) { throw new IOException("invalid catalog plugin id", error); }
        if (entry.name == null || entry.name.isEmpty() || entry.version == null
                || entry.version.isEmpty() || entry.sha256 == null
                || !entry.sha256.matches("[a-fA-F0-9]{64}")) {
            throw new IOException("catalog entry fields are invalid");
        }
        if (entry.downloadUrl == null || !entry.downloadUrl.startsWith("https://")
                || entry.homepage == null || !entry.homepage.startsWith("https://")) {
            throw new IOException("catalog entry URLs must use HTTPS");
        }
        PluginCompatibility.requireHostAtLeast(entry.minHostVersion);
        try { decodeKey(entry.publisherKey); }
        catch (GeneralSecurityException error) { throw new IOException("catalog publisher key is invalid", error); }
    }

    private static PublicKey decodeKey(String base64) throws GeneralSecurityException {
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(base64)));
        } catch (IllegalArgumentException error) {
            throw new GeneralSecurityException("public key is not base64", error);
        }
    }

    private static void download(String raw, Path target, long limit) throws IOException {
        String current = raw;
        for (int redirects = 0; redirects <= 5; redirects++) {
            URL url = new URL(current);
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("catalog download must use HTTPS");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(120_000);
            connection.setRequestProperty("User-Agent", "QPlayer-Plugin-Catalog/1");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || redirects == 5) throw new IOException("catalog download redirect failed");
                current = new URL(url, location).toString();
                continue;
            }
            if (status < 200 || status >= 300) {
                connection.disconnect();
                throw new IOException("catalog download HTTP " + status);
            }
            long declared = connection.getContentLengthLong();
            if (declared > limit) { connection.disconnect(); throw new IOException("plugin package is too large"); }
            try (InputStream input = connection.getInputStream();
                 java.io.OutputStream output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[32 * 1024];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    total += count;
                    if (total > limit) throw new IOException("plugin package is too large");
                    output.write(buffer, 0, count);
                }
            } finally { connection.disconnect(); }
            return;
        }
        throw new IOException("catalog download failed");
    }

    private static byte[] downloadBytes(String raw, int limit) throws IOException {
        String current = raw;
        for (int redirects = 0; redirects <= 5; redirects++) {
            URL url = new URL(current);
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IOException("plugin catalog download must use HTTPS");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("User-Agent", "QPlayer-Plugin-Catalog/1");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || redirects == 5) {
                    throw new IOException("plugin catalog redirect failed");
                }
                current = new URL(url, location).toString();
                continue;
            }
            if (status < 200 || status >= 300) {
                connection.disconnect();
                throw new IOException("plugin catalog HTTP " + status);
            }
            long declared = connection.getContentLengthLong();
            if (declared > limit) {
                connection.disconnect();
                throw new IOException("plugin catalog is too large");
            }
            try (InputStream input = connection.getInputStream()) {
                return readLimited(input, limit);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("plugin catalog download failed");
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0, count;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > limit) throw new IOException("plugin catalog is too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static byte[] digestFile(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            return digest.digest();
        } catch (GeneralSecurityException error) { throw new AssertionError(error); }
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static final class Envelope { String payload; String signature; }
    private static final class Catalog {
        int schemaVersion;
        String generatedAt;
        List<PluginCatalogEntry> entries;
    }

    interface CatalogDownloader {
        byte[] download(String url, int limit) throws IOException;
    }
}
