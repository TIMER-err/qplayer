package dev.t1m3.qplayer.plugin;

import com.google.gson.Gson;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Core-owned, namespaced implementations of the non-UI plugin host services. */
public final class CorePluginHostApi implements PolicyAwarePluginHostApi, AutoCloseable {
    private static final long MAX_STORAGE_BYTES = 1024L * 1024L;
    private static final long MAX_HTTP_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_URL_CHARS = 16 * 1024;
    private static final int MAX_HEADER_COUNT = 64;
    private static final int MAX_HEADER_VALUE_CHARS = 8192;
    private static final int MAX_REDIRECTS = 5;
    private final Map<String, PluginManifest> policies = new ConcurrentHashMap<>();
    private final ExecutorService network = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "qplayer-plugin-net");
        thread.setDaemon(true);
        return thread;
    });
    private final Path storageRoot;
    private final PluginCredentialVault credentials;
    private final Gson gson = new Gson();

    public CorePluginHostApi() {
        this(AppDirs.configFile("plugin-data"), new PluginCredentialVault());
    }

    CorePluginHostApi(Path storageRoot, PluginCredentialVault credentials) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        this.credentials = credentials;
    }

    public void setCredentialListener(PluginCredentialVault.CredentialListener listener) {
        credentials.setCredentialListener(listener);
    }

    public boolean consumeCredentialUnlock() { return credentials.consumeCredentialUnlock(); }
    public boolean retryCredentialUnlock() { return credentials.retryCredentialUnlock(); }
    public boolean fallbackUnreadableCredentials() {
        return credentials.fallbackUnreadableCredentials();
    }
    public boolean resetUnreadableCredentialsForPlatformLogin() {
        return credentials.resetUnreadableCredentialsForPlatformLogin();
    }
    public boolean enableSystemCredentialProtection() {
        return credentials.enableSystemCredentialProtection();
    }
    public boolean usesOwnerOnlyCredentialProtection() {
        return credentials.usesOwnerOnlyCredentialProtection();
    }

    public boolean hasPermission(String pluginId, PluginPermission permission) {
        PluginManifest manifest = policies.get(pluginId);
        return manifest != null && manifest.permissionSet().contains(permission);
    }

    @Override public void register(PluginManifest manifest) {
        manifest.validate();
        policies.put(manifest.id, manifest);
    }

    @Override public void unregister(String pluginId) {
        policies.remove(pluginId);
    }

    /** Validate a URL returned to a host-owned Image/audio path, where the
     * plugin cannot rely on qplayer.http to enforce its domain grant. */
    public boolean allowsReturnedUrl(String pluginId, String url) {
        PluginManifest manifest = policies.get(pluginId);
        if (manifest == null) return false;
        try {
            validateNetworkUrl(manifest, url);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Policy used by QML image loading when the owning row no longer carries a
     * plugin id. A URL is accepted only if at least one active plugin declared it. */
    public boolean allowsAnyReturnedUrl(String url) {
        for (String pluginId : policies.keySet()) {
            if (allowsReturnedUrl(pluginId, url)) return true;
        }
        return false;
    }

    /** Fetch a host-rendered plugin asset under the same redirect, DNS and domain
     * policy as qplayer.http. This prevents artwork paths from becoming a bypass. */
    public CompletableFuture<byte[]> fetchReturnedBytes(String pluginId, String url,
                                                         Map<String, String> headers,
                                                         long maxBytes, int timeoutMs) {
        PluginManifest manifest = policies.get(pluginId);
        if (manifest == null) return failed(new SecurityException("plugin is not active"));
        final long limit = Math.max(1L, Math.min(MAX_HTTP_BYTES, maxBytes));
        final int timeout = Math.max(1_000, Math.min(30_000, timeoutMs));
        final Map<String, String> safeHeaders = headers != null
                ? new LinkedHashMap<>(headers) : Collections.<String, String>emptyMap();
        return CompletableFuture.supplyAsync(() -> {
            try { return requestBytes(manifest, url, safeHeaders, limit, timeout); }
            catch (IOException error) { throw new CompletionException(error); }
        }, network);
    }

    /** Stream a provider-returned asset into a host-selected cache path without
     * exposing filesystem access to JavaScript. Every redirect is checked against
     * the provider grant and a pending sibling is atomically promoted only after a
     * complete bounded download. */
    public CompletableFuture<Boolean> downloadReturnedFile(String pluginId, String url,
                                                            Map<String, String> headers,
                                                            Path target, long maxBytes,
                                                            int timeoutMs) {
        PluginManifest manifest = policies.get(pluginId);
        if (manifest == null) return failed(new SecurityException("plugin is not active"));
        if (target == null) return failed(new IllegalArgumentException("target is null"));
        final long limit = Math.max(1L, Math.min(1024L * 1024L * 1024L, maxBytes));
        final int timeout = Math.max(1_000, Math.min(120_000, timeoutMs));
        final Map<String, String> safeHeaders = headers != null
                ? new LinkedHashMap<>(headers) : Collections.<String, String>emptyMap();
        return CompletableFuture.supplyAsync(() -> {
            try {
                downloadFile(manifest, url, safeHeaders, target, limit, timeout);
                return Boolean.TRUE;
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        }, network);
    }

    @Override public CompletableFuture<Object> call(String pluginId, String method,
                                                     Map<String, Object> arguments) {
        PluginManifest manifest = policies.get(pluginId);
        if (manifest == null) return failed(new SecurityException("plugin is not active"));
        Map<String, Object> args = arguments != null ? arguments : Collections.emptyMap();
        try {
            switch (method) {
                case "storage.get": return completed(storageGet(pluginId, string(args, "key")));
                case "storage.put":
                    storagePut(pluginId, string(args, "key"), args.get("value"));
                    return completed(Boolean.TRUE);
                case "storage.delete":
                    return completed(Files.deleteIfExists(storagePath(pluginId, string(args, "key"))));
                case "credentials.get":
                    require(manifest, PluginPermission.CREDENTIALS);
                    return completed(readCredential(pluginId, string(args, "key")));
                case "credentials.put":
                    require(manifest, PluginPermission.CREDENTIALS);
                    credentials.put(pluginId, string(args, "key"),
                            string(args, "value").getBytes(StandardCharsets.UTF_8));
                    return completed(Boolean.TRUE);
                case "credentials.delete":
                    require(manifest, PluginPermission.CREDENTIALS);
                    return completed(credentials.delete(pluginId, string(args, "key")));
                case "crypto.sha256":
                case "crypto.digest":
                case "crypto.random":
                case "crypto.aes":
                case "crypto.hmac":
                case "crypto.modPow":
                case "crypto.x25519":
                case "compression.gunzip":
                    return completed(PluginCryptoServices.call(method, args));
                case "http.request":
                    require(manifest, PluginPermission.NETWORK);
                    return CompletableFuture.supplyAsync(() -> {
                        try { return request(manifest, args); }
                        catch (IOException error) { throw new CompletionException(error); }
                    }, network);
                default:
                    return failed(new UnsupportedOperationException("host method is not implemented: " + method));
            }
        } catch (Exception error) {
            return failed(error);
        }
    }

    private Object storageGet(String pluginId, String key) throws IOException {
        Path file = storagePath(pluginId, key);
        if (!Files.isRegularFile(file)) return null;
        StoredValue stored = gson.fromJson(StorageFiles.readUtf8(file), StoredValue.class);
        if (stored == null || !key.equals(stored.key)) throw new IOException("plugin storage namespace mismatch");
        return stored.value;
    }

    private void storagePut(String pluginId, String key, Object value) throws IOException {
        StoredValue stored = new StoredValue();
        stored.key = key;
        stored.value = value;
        String json = gson.toJson(stored);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_STORAGE_BYTES) {
            throw new IOException("plugin storage value is too large");
        }
        StorageFiles.writeUtf8Atomic(storagePath(pluginId, key), json);
    }

    private Path storagePath(String pluginId, String key) {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("invalid storage key");
        }
        Path pluginRoot = storageRoot.resolve(pluginId).normalize();
        Path target = pluginRoot.resolve(hex(sha256(key.getBytes(StandardCharsets.UTF_8))) + ".json")
                .normalize();
        if (!pluginRoot.startsWith(storageRoot) || !target.startsWith(pluginRoot)) {
            throw new IllegalArgumentException("storage path escapes plugin namespace");
        }
        return target;
    }

    private String readCredential(String pluginId, String key)
            throws IOException, GeneralSecurityException {
        byte[] value = credentials.get(pluginId, key);
        return value != null ? new String(value, StandardCharsets.UTF_8) : null;
    }

    private Map<String, Object> request(PluginManifest manifest, Map<String, Object> args)
            throws IOException {
        String current = string(args, "url");
        String method = args.containsKey("method")
                ? string(args, "method").toUpperCase(Locale.ROOT) : "GET";
        Set<String> methods = new LinkedHashSet<>(manifest.networkMethods);
        if (methods.isEmpty()) {
            methods.add("GET");
            methods.add("POST");
        }
        if (!methods.contains(method)) throw new SecurityException("HTTP method is not declared: " + method);
        int timeout = number(args.get("timeoutMs"), 10_000);
        timeout = Math.max(1_000, Math.min(30_000, timeout));
        byte[] body = args.containsKey("body")
                ? string(args, "body").getBytes(StandardCharsets.UTF_8) : null;
        if (body != null && body.length > MAX_HTTP_BYTES) {
            throw new IOException("plugin HTTP request body is too large");
        }
        Map<String, String> requestHeaders = stringMap(args.get("headers"));

        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            URL url = validateNetworkUrl(manifest, current);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setRequestProperty("User-Agent", "QPlayer-Plugin/1");
            for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
                String name = header.getKey();
                if ("Host".equalsIgnoreCase(name) || "Content-Length".equalsIgnoreCase(name)
                        || "Connection".equalsIgnoreCase(name)) continue;
                connection.setRequestProperty(name, header.getValue());
            }
            if (body != null && body.length > 0) {
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || redirect == MAX_REDIRECTS) {
                    throw new IOException("HTTP redirect limit reached");
                }
                current = new URL(url, location).toString();
                if (status == 303 || ((status == 301 || status == 302) && "POST".equals(method))) {
                    method = "GET";
                    body = null;
                    if (!methods.contains(method)) {
                        throw new SecurityException("redirected HTTP method is not declared: " + method);
                    }
                }
                continue;
            }
            InputStream raw = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] response = raw != null ? readLimited(raw, MAX_HTTP_BYTES) : new byte[0];
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            result.put("finalUrl", current);
            result.put("body", new String(response, StandardCharsets.UTF_8));
            result.put("bodyBase64", Base64.getEncoder().encodeToString(response));
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            List<String> setCookies = new ArrayList<>();
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() != null && header.getValue() != null) {
                    responseHeaders.put(header.getKey(), String.join(", ", header.getValue()));
                    if ("Set-Cookie".equalsIgnoreCase(header.getKey())) {
                        setCookies.addAll(header.getValue());
                    }
                }
            }
            result.put("headers", responseHeaders);
            result.put("setCookies", setCookies);
            connection.disconnect();
            return result;
        }
        throw new IOException("HTTP request failed");
    }

    private byte[] requestBytes(PluginManifest manifest, String initial,
                                Map<String, String> headers, long limit, int timeout)
            throws IOException {
        String current = initial;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            URL url = validateNetworkUrl(manifest, current);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setRequestProperty("User-Agent", "QPlayer-Plugin/1");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String name = header.getKey();
                if ("Host".equalsIgnoreCase(name) || "Content-Length".equalsIgnoreCase(name)
                        || "Connection".equalsIgnoreCase(name)) continue;
                connection.setRequestProperty(name, header.getValue());
            }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || redirect == MAX_REDIRECTS) {
                    throw new IOException("HTTP redirect limit reached");
                }
                current = new URL(url, location).toString();
                continue;
            }
            if (status >= 400) {
                connection.disconnect();
                throw new IOException("HTTP " + status);
            }
            try {
                return readLimited(connection.getInputStream(), limit);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("HTTP request failed");
    }

    private void downloadFile(PluginManifest manifest, String initial,
                              Map<String, String> headers, Path target,
                              long limit, int timeout) throws IOException {
        String current = initial;
        Path normalized = target.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) throw new IOException("download target has no parent");
        Files.createDirectories(parent);
        Path pending = StorageFiles.pendingPath(normalized);
        try {
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                URL url = validateNetworkUrl(manifest, current);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(timeout);
                connection.setReadTimeout(timeout);
                connection.setRequestProperty("User-Agent", "QPlayer-Plugin/1");
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    String name = header.getKey();
                    if ("Host".equalsIgnoreCase(name) || "Content-Length".equalsIgnoreCase(name)
                            || "Connection".equalsIgnoreCase(name)) continue;
                    connection.setRequestProperty(name, header.getValue());
                }
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null || redirect == MAX_REDIRECTS) {
                        throw new IOException("HTTP redirect limit reached");
                    }
                    current = new URL(url, location).toString();
                    continue;
                }
                if (status >= 400) {
                    connection.disconnect();
                    throw new IOException("HTTP " + status);
                }
                long declared = connection.getContentLengthLong();
                if (declared > limit) {
                    connection.disconnect();
                    throw new IOException("plugin download exceeds size limit");
                }
                try (InputStream input = connection.getInputStream();
                     OutputStream output = Files.newOutputStream(pending)) {
                    byte[] buffer = new byte[32 * 1024];
                    long total = 0L;
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        total += count;
                        if (total > limit) throw new IOException("plugin download exceeds size limit");
                        output.write(buffer, 0, count);
                    }
                } finally {
                    connection.disconnect();
                }
                StorageFiles.replace(pending, normalized);
                return;
            }
            throw new IOException("HTTP request failed");
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private static URL validateNetworkUrl(PluginManifest manifest, String raw) throws IOException {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_URL_CHARS) {
            throw new IOException("invalid plugin URL length");
        }
        final URI uri;
        try { uri = URI.create(raw); }
        catch (IllegalArgumentException error) { throw new IOException("invalid plugin URL", error); }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new SecurityException("only HTTP(S) plugin URLs are allowed");
        }
        if ("http".equalsIgnoreCase(scheme)
                && !manifest.permissionSet().contains(PluginPermission.CLEAR_TEXT_NETWORK)) {
            throw new SecurityException("clear-text HTTP permission was not granted");
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        boolean allowed = false;
        for (String declared : manifest.networkDomains) {
            String domain = declared.toLowerCase(Locale.ROOT);
            if (domain.startsWith("*.")) {
                String suffix = domain.substring(1);
                if (normalized.endsWith(suffix) && normalized.length() > suffix.length()) allowed = true;
            } else if (normalized.equals(domain)) {
                allowed = true;
            }
        }
        if (!allowed) throw new SecurityException("network domain is not declared: " + host);
        if (!manifest.permissionSet().contains(PluginPermission.LOCAL_NETWORK)) {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new SecurityException("private and local network addresses are blocked");
                }
            }
        }
        return uri.toURL();
    }

    private static byte[] readLimited(InputStream input, long limit) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = source.read(buffer)) >= 0) {
                total += count;
                if (total > limit) throw new IOException("plugin HTTP response is too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void require(PluginManifest manifest, PluginPermission permission) {
        if (!manifest.permissionSet().contains(permission)) {
            throw new SecurityException("plugin permission not declared: " + permission.wireName());
        }
    }

    private static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IllegalArgumentException("missing string argument " + key);
        }
        return (String) value;
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map)) return Collections.emptyMap();
        if (((Map<?, ?>) value).size() > MAX_HEADER_COUNT) {
            throw new IllegalArgumentException("too many plugin HTTP headers");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) {
            String name = String.valueOf(item.getKey());
            String headerValue = String.valueOf(item.getValue());
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
                    || headerValue.length() > MAX_HEADER_VALUE_CHARS
                    || headerValue.indexOf('\r') >= 0 || headerValue.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("invalid plugin HTTP header");
            }
            if ("Host".equalsIgnoreCase(name) || "Content-Length".equalsIgnoreCase(name)
                    || "Connection".equalsIgnoreCase(name)) continue;
            result.put(name, headerValue);
        }
        return result;
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (GeneralSecurityException e) { throw new AssertionError(e); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private static <T> CompletableFuture<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    @Override public void close() {
        network.shutdownNow();
        policies.clear();
    }

    private static final class StoredValue {
        String key;
        Object value;
    }
}
