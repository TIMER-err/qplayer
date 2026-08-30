package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class PluginCatalogServiceTest {
    @Test public void bundledCatalogAndPublisherKeyAreSignedAndValid() throws Exception {
        List<PluginCatalogEntry> entries =
                new PluginCatalogService(new PluginPackageVerifier()).loadBundled();
        assertFalse(entries.isEmpty());
        PluginCatalogEntry entry = entries.get(0);
        assertEquals("netease", entry.id);
        assertEquals("0.1.0", entry.version);
        assertFalse(entry.publisherKey.isEmpty());
    }
}
