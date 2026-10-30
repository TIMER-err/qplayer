package dev.t1m3.qplayer.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.t1m3.qplayer.util.GitHubDownloadUrls;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Blocking release lookup; the caller owns scheduling, UI projection and host callbacks. */
public final class ReleaseUpdateChecker {
    private static final String RELEASE_API =
            "https://api.github.com/repos/TIMER-err/qplayer/releases/latest";
    private static final Predicate<String> APK_MATCHER =
            name -> name.toLowerCase(Locale.ROOT).endsWith(".apk");

    private final Transport transport;
    private final Set<Call> activeCalls = new HashSet<>();
    private boolean cancelled;

    private static final class DefaultClient {
        static final OkHttpClient INSTANCE =
                new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .writeTimeout(0, TimeUnit.MILLISECONDS)
                        .build();
    }

    /** Asset URLs remain unmirrored so the host can apply its download candidate policy. */
    public static final class Update {
        private final String version;
        private final String notes;
        private final String assetUrl;
        private final String browserUrl;

        public Update(String version, String notes, String assetUrl, String browserUrl) {
            this.version = version;
            this.notes = notes;
            this.assetUrl = assetUrl;
            this.browserUrl = browserUrl;
        }

        public String version() {
            return version;
        }

        public String notes() {
            return notes;
        }

        public String assetUrl() {
            return assetUrl;
        }

        public String browserUrl() {
            return browserUrl;
        }
    }

    @FunctionalInterface
    interface Transport {
        String get(String url) throws IOException;
    }

    public ReleaseUpdateChecker() {
        this.transport = url -> httpGet(DefaultClient.INSTANCE, url);
    }

    ReleaseUpdateChecker(Transport transport) {
        this.transport = Objects.requireNonNull(transport);
    }

    ReleaseUpdateChecker(OkHttpClient client) {
        Objects.requireNonNull(client);
        this.transport = url -> httpGet(client, url);
    }

    /** Stop admission and abort live sockets; the caller still awaits its worker. */
    public void cancelPendingRequests() {
        Call[] calls;
        synchronized (this) {
            cancelled = true;
            calls = activeCalls.toArray(new Call[0]);
        }
        for (Call call : calls) call.cancel();
    }

    private synchronized void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("release lookup cancelled");
        }
    }

    /**
     * Returns an update only when the latest release is newer. A null matcher uses Android's APK
     * rule; desktop hosts supply their installer matcher. This method performs network IO and must
     * be called on the caller's worker thread.
     */
    public Optional<Update> check(
            String currentVersion, boolean preferMirror, Predicate<String> assetMatcher)
            throws IOException {
        if (currentVersion == null || currentVersion.isEmpty()) {
            throw new IllegalStateException("current version is unset");
        }
        JsonObject release = fetchRelease(preferMirror);
        String tag = optString(release, "tag_name");
        String latest = tag.startsWith("v") ? tag.substring(1) : tag;
        if (!isNewer(latest, currentVersion)) return Optional.empty();

        Predicate<String> matcher = assetMatcher != null ? assetMatcher : APK_MATCHER;
        String assetUrl = "";
        if (release.has("assets") && release.get("assets").isJsonArray()) {
            for (JsonElement element : release.getAsJsonArray("assets")) {
                if (!element.isJsonObject()) continue;
                JsonObject asset = element.getAsJsonObject();
                if (matcher.test(optString(asset, "name"))) {
                    assetUrl = optString(asset, "browser_download_url");
                    break;
                }
            }
        }
        String browserUrl = assetUrl.isEmpty() ? optString(release, "html_url") : assetUrl;
        return Optional.of(new Update(latest, optString(release, "body"), assetUrl, browserUrl));
    }

    /** An invalid successful response must fall back just like a failed request. */
    private JsonObject fetchRelease(boolean preferMirror) throws IOException {
        IOException last = null;
        for (String url : GitHubDownloadUrls.apiCandidates(RELEASE_API, preferMirror)) {
            checkCancelled();
            try {
                return parseReleaseObject(transport.get(url));
            } catch (IOException e) {
                last = e;
            } catch (RuntimeException e) {
                last = new IOException("release response is invalid", e);
            }
        }
        checkCancelled();
        throw last != null ? last : new IOException("release lookup has no candidates");
    }

    /** Require a GitHub release object with a non-empty tag_name. */
    static JsonObject parseReleaseObject(String json) throws IOException {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IOException("release JSON is invalid", e);
        }
        if (root == null || !root.isJsonObject()) {
            throw new IOException("release response is not an object");
        }
        JsonObject release = root.getAsJsonObject();
        try {
            if (optString(release, "tag_name").isEmpty()) {
                throw new IOException("release has no tag");
            }
        } catch (RuntimeException e) {
            throw new IOException("release tag is invalid", e);
        }
        return release;
    }

    private static String optString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    /**
     * Compare numeric release components, then SemVer prerelease identifiers. Build metadata and
     * Android's terminal -debug/-perf variant suffixes do not affect precedence: a variant is not
     * itself a prerelease, but rc.2-debug is.
     */
    static boolean isNewer(String latest, String current) {
        if (latest == null || latest.isEmpty() || current == null || current.isEmpty()) {
            return false;
        }
        Version a = parseVersion(latest);
        Version b = parseVersion(current);
        for (int i = 0; i < 3; i++) {
            if (a.numbers[i] != b.numbers[i]) return a.numbers[i] > b.numbers[i];
        }
        if (a.prerelease.isEmpty()) return !b.prerelease.isEmpty();
        if (b.prerelease.isEmpty()) return false;
        String[] left = a.prerelease.split("\\.");
        String[] right = b.prerelease.split("\\.");
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int comparison = compareIdentifier(left[i], right[i]);
            if (comparison != 0) return comparison > 0;
        }
        return left.length > right.length;
    }

    private static final class Version {
        private final int[] numbers;
        private final String prerelease;

        private Version(int[] numbers, String prerelease) {
            this.numbers = numbers;
            this.prerelease = prerelease;
        }
    }

    private static Version parseVersion(String version) {
        int metadata = version.indexOf('+');
        if (metadata >= 0) version = version.substring(0, metadata);
        if (version.endsWith("-debug")) {
            version = version.substring(0, version.length() - "-debug".length());
        } else if (version.endsWith("-perf")) {
            version = version.substring(0, version.length() - "-perf".length());
        }
        int separator = version.indexOf('-');
        String prerelease = separator < 0 ? "" : version.substring(separator + 1);
        String core = separator < 0 ? version : version.substring(0, separator);
        String[] parts = core.split("\\.");
        int[] numbers = new int[3];
        for (int i = 0; i < numbers.length && i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
                // Preserve the existing tolerance of omitted/non-numeric components.
            }
        }
        return new Version(numbers, prerelease);
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = isNumeric(left);
        boolean rightNumeric = isNumeric(right);
        if (leftNumeric != rightNumeric) return leftNumeric ? -1 : 1;
        if (!leftNumeric) return left.compareTo(right);
        // Numeric prerelease identifiers can exceed the range of a Java integer.
        int lengthComparison = Integer.compare(left.length(), right.length());
        return lengthComparison != 0 ? lengthComparison : left.compareTo(right);
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** GitHub requires a User-Agent. Keep the original timeout and HTTP status policy. */
    private String httpGet(OkHttpClient client, String url) throws IOException {
        Call call =
                client.newCall(
                        new Request.Builder()
                                .url(url)
                                .header("User-Agent", "qplayer-update-check")
                                .header("Accept", "application/vnd.github+json")
                                .build());
        synchronized (this) {
            checkCancelled();
            activeCalls.add(call);
        }
        try (Response response = call.execute()) {
            if (response.code() >= 400) throw new IOException("HTTP " + response.code());
            return response.body().source().readUtf8();
        } finally {
            synchronized (this) {
                activeCalls.remove(call);
            }
        }
    }
}
