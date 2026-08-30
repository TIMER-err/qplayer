package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PluginCatalogServiceTest {
    private static final String PUBLISHER_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEb/6w4W2oyOmqybld26Cnv17mXEA6x1Xkw"
                    + "4zCrd+hfAnmcEJjBTKtC9jO0Q4DVDOzXsmehlW+PJRINAZ99up6aQ==";
    private static final String RELEASE_JSON = "{"
            + "\"tag_name\":\"v0.2.0\","
            + "\"assets\":["
            + "{\"name\":\"netease-0.2.0.qplug.sha256\","
            + "\"browser_download_url\":\"https://example.invalid/wrong\"},"
            + "{\"name\":\"netease-0.2.0.qplug\","
            + "\"browser_download_url\":\"https://github.com/o/r/releases/download/v0.2.0/n.qplug\"}"
            + "]}";

    @Test public void pinnedSourcesCarryAValidIdAndPublisherKey() throws Exception {
        List<PluginCatalogService.Source> sources = PluginCatalogService.loadBundledSources();
        assertEquals(2, sources.size());
        Set<String> ids = new HashSet<>();
        for (PluginCatalogService.Source source : sources) {
            dev.t1m3.qplayer.media.MediaId.validateProvider(source.id);
            assertTrue(source.repo.contains("/"));
            assertTrue(ids.add(source.id));
            KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(source.publisherKey)));
        }
        assertTrue(ids.contains("netease"));
        assertTrue(ids.contains("qq"));
    }

    @Test public void latestReleaseFallsBackToTheNextCandidateAndPicksTheQplugAsset()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PluginCatalogService service = new PluginCatalogService(
                new PluginPackageVerifier(), Collections.singletonList(source()), (url, limit) -> {
                    if (calls.getAndIncrement() == 0) throw new IOException("unreachable");
                    return RELEASE_JSON.getBytes("UTF-8");
                });

        List<PluginCatalogEntry> entries = service.loadLatest(false);

        assertEquals(2, calls.get());
        PluginCatalogEntry entry = entries.get(0);
        assertEquals("netease", entry.id);
        assertEquals("0.2.0", entry.version);
        assertEquals("https://github.com/o/r/releases/download/v0.2.0/n.qplug", entry.downloadUrl);
        assertFalse(entry.publisherKey.isEmpty());
    }

    @Test public void releaseWithoutAQplugAssetIsRejected() {
        PluginCatalogService service = new PluginCatalogService(
                new PluginPackageVerifier(),
                Collections.singletonList(source()),
                (url, limit) -> "{\"tag_name\":\"v1.0.0\",\"assets\":[]}".getBytes("UTF-8"));
        try {
            service.loadLatest(false);
            fail("expected a release without a .qplug asset to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains(".qplug"));
        }
    }

    @Test public void catalogRejectsDuplicateIdsAndInvalidPublisherKeys() throws Exception {
        String source = "{\"id\":\"netease\",\"name\":\"N\",\"description\":\"D\","
                + "\"repo\":\"owner/repo\",\"publisherKey\":\"" + PUBLISHER_KEY + "\"}";
        for (String json : Arrays.asList(
                "[" + source + "," + source + "]",
                "[{\"id\":\"qq\",\"name\":\"Q\",\"description\":\"D\","
                        + "\"repo\":\"owner/repo\",\"publisherKey\":\"bad\"}]")) {
            try {
                PluginCatalogService.parseSources(json.getBytes("UTF-8"));
                fail("expected invalid catalog to be rejected");
            } catch (IOException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }
    }

    private static PluginCatalogService.Source source() {
        return new PluginCatalogService.Source("netease", "网易云音乐", "description",
                "owner/repo", PUBLISHER_KEY);
    }
}
