package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class PluginCatalogServiceTest {
    @Test public void bundledCatalogAndPublisherKeyAreSignedAndValid() throws Exception {
        List<PluginCatalogEntry> entries =
                new PluginCatalogService(new PluginPackageVerifier()).loadBundled();
        assertFalse(entries.isEmpty());
        PluginCatalogEntry entry = entries.get(0);
        assertEquals("netease", entry.id);
        assertEquals("0.2.0", entry.version);
        assertFalse(entry.publisherKey.isEmpty());
    }

    @Test public void remoteCatalogUsesNextCandidateAndVerifiesSignature() throws Exception {
        byte[] signedCatalog;
        try (InputStream input = PluginCatalogServiceTest.class
                .getResourceAsStream("/plugin-catalog-v1.json")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            signedCatalog = output.toByteArray();
        }
        AtomicInteger calls = new AtomicInteger();
        PluginCatalogService service = new PluginCatalogService(
                new PluginPackageVerifier(), (url, limit) -> {
                    if (calls.getAndIncrement() == 0) return "invalid".getBytes("UTF-8");
                    return signedCatalog;
                });

        List<PluginCatalogEntry> entries = service.loadRemote(
                new String[]{"https://first.invalid/catalog", "https://second.invalid/catalog"});

        assertEquals(2, calls.get());
        assertEquals("0.2.0", entries.get(0).version);
    }
}
