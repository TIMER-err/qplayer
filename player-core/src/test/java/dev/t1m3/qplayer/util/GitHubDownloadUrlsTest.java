package dev.t1m3.qplayer.util;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GitHubDownloadUrlsTest {
    private static final String RELEASE =
            "https://github.com/TIMER-err/plugin/releases/download/v1/plugin.qplug";

    @Test public void enabledGlobalProxyPutsMirrorsBeforeGitHub() {
        assertArrayEquals(new String[]{
                "https://gh.ddlc.top/" + RELEASE,
                "https://ghfast.top/" + RELEASE,
                "https://gh-proxy.com/" + RELEASE,
                RELEASE
        }, GitHubDownloadUrls.candidates(RELEASE, true));
    }

    @Test public void disabledGlobalProxyPutsGitHubBeforeFallbackMirrors() {
        assertArrayEquals(new String[]{
                RELEASE,
                "https://gh.ddlc.top/" + RELEASE,
                "https://ghfast.top/" + RELEASE,
                "https://gh-proxy.com/" + RELEASE
        }, GitHubDownloadUrls.candidates(RELEASE, false));
    }

    @Test public void thirdPartyHostsNeverPassThroughGitHubProxy() {
        String url = "https://plugins.example.org/releases/plugin.qplug";
        assertArrayEquals(new String[]{url}, GitHubDownloadUrls.candidates(url, true));
    }

    @Test public void recognizesGitHubDownloadHostsOnly() {
        assertTrue(GitHubDownloadUrls.isGitHubUrl(RELEASE));
        assertTrue(GitHubDownloadUrls.isGitHubUrl(
                "https://objects.githubusercontent.com/path/plugin.qplug"));
        assertFalse(GitHubDownloadUrls.isGitHubUrl("https://example.org/github.com/file"));
    }
}
