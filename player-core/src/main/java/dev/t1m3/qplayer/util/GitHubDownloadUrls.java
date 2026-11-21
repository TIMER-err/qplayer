package dev.t1m3.qplayer.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One source of truth for application and plugin GitHub download mirrors. */
public final class GitHubDownloadUrls {
    private static final String[] MIRRORS = {
            "https://gh.ddlc.top/", "https://ghfast.top/", "https://gh-proxy.com/"
    };
    /** The one mirror that also proxies api.github.com. */
    private static final String API_MIRROR = "https://gh-proxy.com/";

    private GitHubDownloadUrls() {}

    /**
     * Returns the same resilient candidate order for every GitHub download.
     * Enabling the global proxy setting puts mirrors first; disabling it puts
     * GitHub first while retaining mirrors as network fallbacks. Non-GitHub
     * hosts are never disclosed to a GitHub proxy.
     */
    public static String[] candidates(String rawUrl, boolean proxyFirst) {
        if (rawUrl == null || rawUrl.isEmpty()) return new String[0];
        if (!isGitHubUrl(rawUrl) || isMirrored(rawUrl)) return new String[]{rawUrl};

        Set<String> ordered = new LinkedHashSet<>();
        if (!proxyFirst) ordered.add(rawUrl);
        for (String mirror : MIRRORS) ordered.add(mirror + rawUrl);
        if (proxyFirst) ordered.add(rawUrl);
        List<String> result = new ArrayList<>(ordered);
        return result.toArray(new String[0]);
    }

    /**
     * Candidate order for an api.github.com request. Only one mirror proxies the
     * API, so the general {@link #candidates} order would spend two failed
     * requests before reaching a usable host.
     */
    public static String[] apiCandidates(String rawUrl, boolean proxyFirst) {
        if (rawUrl == null || rawUrl.isEmpty()) return new String[0];
        if (!isGitHubUrl(rawUrl) || isMirrored(rawUrl)) return new String[]{rawUrl};
        String mirrored = API_MIRROR + rawUrl;
        return proxyFirst
                ? new String[]{mirrored, rawUrl}
                : new String[]{rawUrl, mirrored};
    }

    static boolean isGitHubUrl(String rawUrl) {
        try {
            URI uri = new URI(rawUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return "github.com".equals(host)
                    || host.endsWith(".github.com")
                    || "githubusercontent.com".equals(host)
                    || host.endsWith(".githubusercontent.com");
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private static boolean isMirrored(String rawUrl) {
        for (String mirror : MIRRORS) if (rawUrl.startsWith(mirror)) return true;
        return false;
    }
}
