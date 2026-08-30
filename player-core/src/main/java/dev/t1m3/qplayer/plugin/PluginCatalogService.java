package dev.t1m3.qplayer.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.GitHubDownloadUrls;
import dev.t1m3.qplayer.util.Logger;

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
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves the newest release of each known plugin repository and verifies the
 * package it publishes.
 *
 * <p>The repository list and publisher trust anchors live in a bundled JSON
 * resource. The available version is whatever GitHub reports as each repository's
 * latest release. Updating the list is still a reviewed QPlayer code change, but
 * the data is maintained separately from this implementation.
 *
 * <p>The publisher key stays pinned because GitHub downloads may travel through
 * third-party mirrors (see {@link GitHubDownloadUrls}); the package signature is
 * what makes an untrusted transport acceptable.
 */
public final class PluginCatalogService {
    private static final String CATALOG_RESOURCE =
            "/dev/t1m3/qplayer/plugin/plugin-catalog.json";

    /** A plugin project QPlayer knows about. Not user-editable and not downloaded. */
    static final class Source {
        public final String id;
        public final String name;
        public final String description;
        /** {@code owner/repo} on GitHub. */
        public final String repo;
        /** Base64 X.509 P-256 key that must have signed the package. */
        public final String publisherKey;

        Source(String id, String name, String description, String repo, String publisherKey) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.repo = repo;
            this.publisherKey = publisherKey;
        }

        String releaseApi() {
            return "https://api.github.com/repos/" + repo + "/releases/latest";
        }

        String homepage() {
            return "https://github.com/" + repo;
        }
    }

    private static final int MAX_RELEASE_BYTES = 2 * 1024 * 1024;
    private static final long MAX_PLUGIN_BYTES = 64L * 1024L * 1024L;
    private final PluginPackageVerifier packageVerifier;
    private final CatalogLoader catalogLoader;
    private final CatalogDownloader catalogDownloader;
    private volatile List<Source> sources;

    public PluginCatalogService(PluginPackageVerifier packageVerifier) {
        this(packageVerifier, PluginCatalogService::loadBundledSources,
                PluginCatalogService::downloadBytes);
    }

    PluginCatalogService(PluginPackageVerifier packageVerifier,
                         List<Source> sources, CatalogDownloader catalogDownloader) {
        this(packageVerifier, () -> sources, catalogDownloader);
    }

    private PluginCatalogService(PluginPackageVerifier packageVerifier,
                                 CatalogLoader catalogLoader,
                                 CatalogDownloader catalogDownloader) {
        this.packageVerifier = packageVerifier;
        this.catalogLoader = catalogLoader;
        this.catalogDownloader = catalogDownloader;
    }

    /**
     * Queries every known repository for its latest release. A source that fails
     * is skipped so one unreachable repository cannot hide the others; the call
     * fails only when nothing at all could be resolved.
     */
    public List<PluginCatalogEntry> loadLatest(boolean proxyFirst) throws IOException {
        List<PluginCatalogEntry> entries = new ArrayList<>();
        IOException last = null;
        for (Source source : sources()) {
            try {
                entries.add(loadLatest(source, proxyFirst));
            } catch (IOException | RuntimeException error) {
                last = error instanceof IOException
                        ? (IOException) error
                        : new IOException("plugin release lookup failed", error);
                Logger.warn("plugin release lookup failed for {}: {}", source.repo, error.getMessage());
            }
        }
        if (entries.isEmpty()) {
            throw last != null ? last : new IOException("no plugin sources are configured");
        }
        return Collections.unmodifiableList(entries);
    }

    private List<Source> sources() throws IOException {
        List<Source> loaded = sources;
        if (loaded != null) return loaded;
        synchronized (this) {
            if (sources == null) sources = catalogLoader.load();
            return sources;
        }
    }

    static List<Source> loadBundledSources() throws IOException {
        try (InputStream input = PluginCatalogService.class.getResourceAsStream(CATALOG_RESOURCE)) {
            if (input == null) throw new IOException("plugin catalog resource is missing");
            return parseSources(readLimited(input, MAX_RELEASE_BYTES));
        }
    }

    static List<Source> parseSources(byte[] json) throws IOException {
        try {
            JsonElement root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
            if (root == null || !root.isJsonArray() || root.getAsJsonArray().isEmpty()) {
                throw new IOException("plugin catalog must be a non-empty array");
            }
            List<Source> parsed = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    throw new IOException("plugin catalog entries must be objects");
                }
                JsonObject object = element.getAsJsonObject();
                Source source = new Source(
                        requiredString(object, "id"),
                        requiredString(object, "name"),
                        requiredString(object, "description"),
                        requiredString(object, "repo"),
                        requiredString(object, "publisherKey"));
                try { dev.t1m3.qplayer.media.MediaId.validateProvider(source.id); }
                catch (IllegalArgumentException error) {
                    throw new IOException("plugin catalog id is invalid: " + source.id, error);
                }
                if (!ids.add(source.id)) {
                    throw new IOException("plugin catalog id is duplicated: " + source.id);
                }
                if (!source.repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
                    throw new IOException("plugin catalog repository is invalid: " + source.repo);
                }
                try {
                    PublicKey key = decodeKey(source.publisherKey);
                    if (!(key instanceof ECPublicKey)
                            || ((ECPublicKey) key).getParams().getCurve().getField().getFieldSize() != 256) {
                        throw new GeneralSecurityException("publisher key is not P-256");
                    }
                } catch (GeneralSecurityException error) {
                    throw new IOException("plugin catalog publisher key is invalid: " + source.id, error);
                }
                parsed.add(source);
            }
            return Collections.unmodifiableList(parsed);
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IOException("plugin catalog JSON is invalid", error);
        }
    }

    private static String requiredString(JsonObject object, String member) throws IOException {
        JsonElement value = object.get(member);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().trim().isEmpty()) {
            throw new IOException("plugin catalog field is invalid: " + member);
        }
        return value.getAsString().trim();
    }

    private PluginCatalogEntry loadLatest(Source source, boolean proxyFirst) throws IOException {
        String[] candidates = GitHubDownloadUrls.apiCandidates(source.releaseApi(), proxyFirst);
        IOException last = null;
        for (String candidate : candidates) {
            try {
                return parseRelease(source,
                        catalogDownloader.download(candidate, MAX_RELEASE_BYTES));
            } catch (IOException | RuntimeException error) {
                last = error instanceof IOException
                        ? (IOException) error
                        : new IOException("plugin release response is invalid", error);
            }
        }
        throw last != null ? last : new IOException("plugin release lookup has no candidates");
    }

    private PluginCatalogEntry parseRelease(Source source, byte[] json) throws IOException {
        JsonElement root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
        if (root == null || !root.isJsonObject()) {
            throw new IOException("plugin release response is not an object");
        }
        JsonObject release = root.getAsJsonObject();
        String tag = optString(release, "tag_name");
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        if (version.isEmpty()) throw new IOException("plugin release has no tag");

        String downloadUrl = "";
        if (release.has("assets") && release.get("assets").isJsonArray()) {
            JsonArray assets = release.getAsJsonArray("assets");
            for (JsonElement element : assets) {
                if (!element.isJsonObject()) continue;
                JsonObject asset = element.getAsJsonObject();
                if (optString(asset, "name").toLowerCase(Locale.ROOT).endsWith(".qplug")) {
                    downloadUrl = optString(asset, "browser_download_url");
                    break;
                }
            }
        }
        if (!downloadUrl.startsWith("https://")) {
            throw new IOException("plugin release has no .qplug asset");
        }

        PluginCatalogEntry entry = new PluginCatalogEntry();
        entry.id = source.id;
        entry.name = source.name;
        entry.description = source.description;
        entry.version = version;
        entry.homepage = source.homepage();
        entry.downloadUrl = downloadUrl;
        entry.publisherKey = source.publisherKey;
        return entry;
    }

    private static String optString(JsonObject object, String member) {
        if (object == null || !object.has(member)) return "";
        JsonElement value = object.get(member);
        return value.isJsonPrimitive() ? value.getAsString() : "";
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
            StorageFiles.replace(pending, target);
            PublicKey publisher = decodeKey(entry.publisherKey);
            // The pinned publisher key, not the release tag, is the trust anchor:
            // require a signature from it and require the package to be the plugin
            // this source is pinned for. The version is whatever the signed
            // manifest says, since the tag is only a naming convention.
            VerifiedPluginPackage verified = packageVerifier.verify(target, publisher, false);
            if (!entry.id.equals(verified.manifest().id)) {
                throw new GeneralSecurityException("plugin package identity mismatch");
            }
            if (!entry.version.equals(verified.manifest().version)) {
                Logger.warn("plugin {} release tag {} does not match manifest version {}",
                        entry.id, entry.version, verified.manifest().version);
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

    private static void validateEntry(PluginCatalogEntry entry) throws IOException {
        if (entry == null) throw new IOException("plugin entry is null");
        try { dev.t1m3.qplayer.media.MediaId.validateProvider(entry.id); }
        catch (IllegalArgumentException error) { throw new IOException("invalid plugin id", error); }
        if (entry.name == null || entry.name.isEmpty()
                || entry.version == null || entry.version.isEmpty()) {
            throw new IOException("plugin entry fields are invalid");
        }
        if (entry.downloadUrl == null || !entry.downloadUrl.startsWith("https://")
                || entry.homepage == null || !entry.homepage.startsWith("https://")) {
            throw new IOException("plugin entry URLs must use HTTPS");
        }
        try { decodeKey(entry.publisherKey); }
        catch (GeneralSecurityException error) { throw new IOException("publisher key is invalid", error); }
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
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("plugin download must use HTTPS");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(120_000);
            connection.setRequestProperty("User-Agent", "QPlayer-Plugin/1");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || redirects == 5) throw new IOException("plugin download redirect failed");
                current = new URL(url, location).toString();
                continue;
            }
            if (status < 200 || status >= 300) {
                connection.disconnect();
                throw new IOException("plugin download HTTP " + status);
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
        throw new IOException("plugin download failed");
    }

    private static byte[] downloadBytes(String raw, int limit) throws IOException {
        String current = raw;
        for (int redirects = 0; redirects <= 5; redirects++) {
            URL url = new URL(current);
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IOException("plugin release lookup must use HTTPS");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("User-Agent", "QPlayer-Plugin/1");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || redirects == 5) {
                    throw new IOException("plugin release lookup redirect failed");
                }
                current = new URL(url, location).toString();
                continue;
            }
            if (status < 200 || status >= 300) {
                connection.disconnect();
                throw new IOException("plugin release lookup HTTP " + status);
            }
            long declared = connection.getContentLengthLong();
            if (declared > limit) {
                connection.disconnect();
                throw new IOException("plugin release response is too large");
            }
            try (InputStream input = connection.getInputStream()) {
                return readLimited(input, limit);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("plugin release lookup failed");
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0, count;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > limit) throw new IOException("plugin release response is too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    interface CatalogDownloader {
        byte[] download(String url, int limit) throws IOException;
    }

    interface CatalogLoader {
        List<Source> load() throws IOException;
    }
}
