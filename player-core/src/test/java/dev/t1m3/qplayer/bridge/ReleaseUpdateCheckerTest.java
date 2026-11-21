package dev.t1m3.qplayer.bridge;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ReleaseUpdateCheckerTest {
    private static final String RELEASE_API =
            "https://api.github.com/repos/TIMER-err/qplayer/releases/latest";
    private static final String MIRRORED_API = "https://gh-proxy.com/" + RELEASE_API;
    private static final String RELEASE_PAGE =
            "https://github.com/TIMER-err/qplayer/releases/tag/v1.9.3";

    @Test
    public void aHigherPatchReleaseIsNewer() {
        assertTrue(ReleaseUpdateChecker.isNewer("1.8.1", "1.8.0"));
        assertTrue(ReleaseUpdateChecker.isNewer("1.9.0", "1.8.22"));
        assertFalse(ReleaseUpdateChecker.isNewer("1.8.1", "1.8.1"));
        assertFalse(ReleaseUpdateChecker.isNewer("1.8.0", "1.8.1"));
    }

    @Test
    public void debugAndPerfSuffixesAreBuildVariantsNotPrereleases() {
        for (String suffix : new String[]{"-debug", "-perf"}) {
            assertTrue(ReleaseUpdateChecker.isNewer("1.8.1", "1.8.0" + suffix));
            assertFalse(ReleaseUpdateChecker.isNewer("1.8.0", "1.8.0" + suffix));
            assertFalse(ReleaseUpdateChecker.isNewer("1.8.0" + suffix, "1.8.0"));
            assertFalse(ReleaseUpdateChecker.isNewer("1.8.0-rc.2", "1.8.0" + suffix));
            assertTrue(ReleaseUpdateChecker.isNewer("1.8.0", "1.8.0-rc.2" + suffix));
            assertFalse(ReleaseUpdateChecker.isNewer("1.8.0-rc.2", "1.8.0-rc.2" + suffix));
        }
    }

    @Test
    public void stableReleaseIsNewerThanTheSameNumberRc() {
        assertTrue(ReleaseUpdateChecker.isNewer("1.9.3", "1.9.3-rc.2"));
        assertFalse(ReleaseUpdateChecker.isNewer("1.9.3-rc.2", "1.9.3"));
        assertTrue(ReleaseUpdateChecker.isNewer("1.9.4", "1.9.3-rc.2"));
        assertFalse(ReleaseUpdateChecker.isNewer("1.9.2", "1.9.3-rc.2"));
    }

    @Test
    public void prereleaseIdentifiersUseSemverPrecedence() {
        String[] versions = {"1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta",
                "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0"};
        for (int i = 1; i < versions.length; i++) {
            assertTrue(ReleaseUpdateChecker.isNewer(versions[i], versions[i - 1]));
            assertFalse(ReleaseUpdateChecker.isNewer(versions[i - 1], versions[i]));
            assertFalse(ReleaseUpdateChecker.isNewer(versions[i], versions[i]));
        }
        assertTrue(ReleaseUpdateChecker.isNewer("1.9.3-rc.10", "1.9.3-rc.2"));
        assertTrue(ReleaseUpdateChecker.isNewer(
                "1.9.3-rc.100000000000000000000", "1.9.3-rc.99999999999999999999"));
        assertTrue(ReleaseUpdateChecker.isNewer("1.9.4-alpha", "1.9.3"));
    }

    @Test
    public void buildMetadataDoesNotAffectPrecedence() {
        assertFalse(ReleaseUpdateChecker.isNewer("1.9.3+release.1", "1.9.3+local.2"));
        assertFalse(ReleaseUpdateChecker.isNewer("1.9.3-rc.2+release", "1.9.3-rc.2+local"));
        assertFalse(ReleaseUpdateChecker.isNewer("1.9.3", "1.9.3-debug+local"));
        assertTrue(ReleaseUpdateChecker.isNewer("1.9.3+release", "1.9.3-rc.2-perf+local"));
    }

    @Test
    public void emptyVersionsAreNeverNewer() {
        assertFalse(ReleaseUpdateChecker.isNewer("", "1.8.0"));
        assertFalse(ReleaseUpdateChecker.isNewer("1.8.1", ""));
        assertFalse(ReleaseUpdateChecker.isNewer(null, "1.8.0"));
        assertFalse(ReleaseUpdateChecker.isNewer("1.8.1", null));
    }

    @Test
    public void aReleaseObjectNeedsATag() throws Exception {
        JsonObject obj = ReleaseUpdateChecker.parseReleaseObject(
                "{\"tag_name\":\"v1.8.1\",\"body\":\"notes\"}");
        assertEquals("v1.8.1", obj.get("tag_name").getAsString());
    }

    @Test
    public void proxyHtmlOrEmptyJsonIsNotARelease() {
        assertInvalid("{");
        assertInvalid("[]");
        assertInvalid("{}");
        assertInvalid("{\"tag_name\":\"\"}");
        assertInvalid("{\"tag_name\":null}");
        assertInvalid("{\"tag_name\":{}}");
        assertInvalid("<html>not json</html>");
    }

    @Test
    public void stableUpdateFromRcContainsNotesAndFirstMatchingRawApk() throws Exception {
        String first = "https://github.com/TIMER-err/qplayer/releases/download/v1.9.3/first.apk";
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(url ->
                release("[null,{\"name\":\"desktop.exe\",\"browser_download_url\":\"desktop\"},"
                        + "{\"name\":\"first.APK\",\"browser_download_url\":\"" + first + "\"},"
                        + "{\"name\":\"second.apk\",\"browser_download_url\":\"second\"}]"));

        ReleaseUpdateChecker.Update update = checker.check("1.9.3-rc.2", true, null)
                .orElseThrow(() -> new AssertionError("expected update"));

        assertEquals("1.9.3", update.version());
        assertEquals("release notes", update.notes());
        assertEquals(first, update.assetUrl());
        assertEquals(first, update.browserUrl());
    }

    @Test
    public void desktopMatcherSelectsItsFirstAssetInReleaseOrder() throws Exception {
        List<String> matchedNames = new ArrayList<>();
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(url -> release(
                "[{\"name\":\"android.apk\",\"browser_download_url\":\"apk\"},"
                        + "{\"name\":\"linux.AppImage\",\"browser_download_url\":\"linux\"},"
                        + "{\"name\":\"other.AppImage\",\"browser_download_url\":\"other\"}]"));

        ReleaseUpdateChecker.Update update = checker.check("1.9.2", false, name -> {
            matchedNames.add(name);
            return name.endsWith(".AppImage");
        }).orElseThrow(() -> new AssertionError("expected update"));

        assertEquals(Arrays.asList("android.apk", "linux.AppImage"), matchedNames);
        assertEquals("linux", update.assetUrl());
        assertEquals("linux", update.browserUrl());
    }

    @Test
    public void releasePageIsBrowserFallbackWhenHostAssetIsMissing() throws Exception {
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(url -> release(
                "[{\"name\":\"desktop.exe\",\"browser_download_url\":\"desktop\"}]"));

        ReleaseUpdateChecker.Update update = checker.check("1.9.2", false, null)
                .orElseThrow(() -> new AssertionError("expected update"));

        assertEquals("", update.assetUrl());
        assertEquals(RELEASE_PAGE, update.browserUrl());
    }

    @Test
    public void firstMatchingAssetWithoutUrlStillUsesReleasePage() throws Exception {
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(url -> release(
                "[{\"name\":\"first.apk\"},{\"name\":\"second.apk\",\"browser_download_url\":\"second\"}]"));

        ReleaseUpdateChecker.Update update = checker.check("1.9.2", false, null)
                .orElseThrow(() -> new AssertionError("expected update"));

        assertEquals("", update.assetUrl());
        assertEquals(RELEASE_PAGE, update.browserUrl());
    }

    @Test
    public void optionalNotesAndAssetsMayBeMissing() throws Exception {
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(url ->
                "{\"tag_name\":\"v1.9.3\",\"html_url\":\"" + RELEASE_PAGE + "\"}");

        ReleaseUpdateChecker.Update update = checker.check("1.9.2", false, null)
                .orElseThrow(() -> new AssertionError("expected update"));

        assertEquals("", update.notes());
        assertEquals("", update.assetUrl());
        assertEquals(RELEASE_PAGE, update.browserUrl());
    }

    @Test
    public void noUpdateDoesNotSelectAssetsOrRetryAValidRelease() throws Exception {
        List<String> requested = new ArrayList<>();
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(url -> {
            requested.add(url);
            return release("[]");
        });

        Optional<ReleaseUpdateChecker.Update> update = checker.check("1.9.3-debug", false, name -> {
            fail("asset matcher must not run for an already current release");
            return false;
        });

        assertFalse(update.isPresent());
        assertEquals(Arrays.asList(RELEASE_API), requested);
    }

    @Test
    public void mirrorFirstFallsBackToDirectForInvalidSuccessfulResponse() throws Exception {
        List<String> requested = new ArrayList<>();
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(url -> {
            requested.add(url);
            return requested.size() == 1 ? "<html>proxy error</html>" : release("[]");
        });

        assertTrue(checker.check("1.9.2", true, null).isPresent());
        assertEquals(Arrays.asList(MIRRORED_API, RELEASE_API), requested);
    }

    @Test
    public void directFirstFallsBackToMirrorForNetworkFailure() throws Exception {
        List<String> requested = new ArrayList<>();
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(url -> {
            requested.add(url);
            if (requested.size() == 1) throw new IOException("offline");
            return release("[]");
        });

        assertTrue(checker.check("1.9.2", false, null).isPresent());
        assertEquals(Arrays.asList(RELEASE_API, MIRRORED_API), requested);
    }

    @Test
    public void successfulFirstResponseDoesNotContactFallback() throws Exception {
        List<String> requested = new ArrayList<>();
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(url -> {
            requested.add(url);
            return release("[]");
        });

        assertTrue(checker.check("1.9.2", true, null).isPresent());
        assertEquals(Arrays.asList(MIRRORED_API), requested);
    }

    @Test
    public void allCandidateFailuresPropagateTheLastFailure() {
        List<String> requested = new ArrayList<>();
        IOException lastFailure = new IOException("mirror unavailable");
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(url -> {
            requested.add(url);
            if (requested.size() == 1) return "{}";
            throw lastFailure;
        });
        try {
            checker.check("1.9.2", false, null);
            fail("expected failed release lookup");
        } catch (IOException expected) {
            assertTrue(expected == lastFailure);
            assertEquals(Arrays.asList(RELEASE_API, MIRRORED_API), requested);
        }
    }

    @Test
    public void unsetCurrentVersionFailsWithoutNetworkRequest() throws Exception {
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(url -> {
            fail("must not contact GitHub without the running version");
            return "";
        });
        for (String current : new String[]{null, ""}) {
            try {
                checker.check(current, false, null);
                fail("expected unset current version");
            } catch (IllegalStateException expected) {
                assertEquals("current version is unset", expected.getMessage());
            }
        }
    }

    private static String release(String assets) {
        return "{\"tag_name\":\"v1.9.3\",\"body\":\"release notes\",\"html_url\":\""
                + RELEASE_PAGE + "\",\"assets\":" + assets + "}";
    }

    private static void assertInvalid(String json) {
        try {
            ReleaseUpdateChecker.parseReleaseObject(json);
            fail("expected invalid release JSON: " + json);
        } catch (IOException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }
}
