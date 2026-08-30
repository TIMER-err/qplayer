package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PluginCatalogServiceTest {
    private static final String RELEASE_JSON = "{"
            + "\"tag_name\":\"v0.2.0\","
            + "\"assets\":["
            + "{\"name\":\"netease-0.2.0.qplug.sha256\","
            + "\"browser_download_url\":\"https://example.invalid/wrong\"},"
            + "{\"name\":\"netease-0.2.0.qplug\","
            + "\"browser_download_url\":\"https://github.com/o/r/releases/download/v0.2.0/n.qplug\"}"
            + "]}";

    @Test public void pinnedSourcesCarryAValidIdAndPublisherKey() throws Exception {
        assertFalse(PluginCatalogService.SOURCES.length == 0);
        for (PluginCatalogService.Source source : PluginCatalogService.SOURCES) {
            dev.t1m3.qplayer.media.MediaId.validateProvider(source.id);
            assertTrue(source.repo.contains("/"));
            KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(source.publisherKey)));
        }
    }

    @Test public void latestReleaseFallsBackToTheNextCandidateAndPicksTheQplugAsset()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PluginCatalogService service = new PluginCatalogService(
                new PluginPackageVerifier(), (url, limit) -> {
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
                (url, limit) -> "{\"tag_name\":\"v1.0.0\",\"assets\":[]}".getBytes("UTF-8"));
        try {
            service.loadLatest(false);
            fail("expected a release without a .qplug asset to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains(".qplug"));
        }
    }
}
