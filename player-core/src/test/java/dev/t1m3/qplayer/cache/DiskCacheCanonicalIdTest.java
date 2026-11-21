package dev.t1m3.qplayer.cache;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiskCacheCanonicalIdTest {
    @Test public void canonicalIdsUseBoundedSafeNames() throws Exception {
        Path root = Files.createTempDirectory("qplayer-cache-id-");
        DiskCache cache = new DiskCache(0);
        cache.setBaseDir(root.toString());
        String path = cache.audioPath("provider:song:a%2Fb%3Ac");
        String name = java.nio.file.Paths.get(path).getFileName().toString();
        assertTrue(name.matches("v2-[a-f0-9]{64}\\.cache"));
        assertFalse(name.contains("%"));
    }

    @Test public void legacyNumericAudioMovesOnlyAfterCanonicalLookup() throws Exception {
        Path root = Files.createTempDirectory("qplayer-cache-migration-");
        DiskCache cache = new DiskCache(0);
        cache.setBaseDir(root.toString());
        Path legacy = java.nio.file.Paths.get(cache.audioPath(123L));
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, new byte[]{1, 2, 3});

        String migrated = cache.getAudio("netease:song:123");
        assertEquals(3L, Files.size(java.nio.file.Paths.get(migrated)));
        assertFalse(Files.exists(legacy));
        assertTrue(migrated.endsWith(".cache"));
    }
}
