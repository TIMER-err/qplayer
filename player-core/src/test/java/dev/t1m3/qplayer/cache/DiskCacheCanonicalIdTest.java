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

    /**
     * Reproduces the mid-playback auto-skip: {@link DiskCache#getAudio} bumps a
     * file's mtime on every read, the audio eviction policy evicts the NEWEST
     * unprotected file first, and nothing used to stop those two facts from
     * combining to delete the very file a still-open MediaPlayer was reading
     * from. setCurrentlyPlaying must keep it out of eviction regardless of how
     * recently it was touched.
     */
    @Test public void currentlyPlayingAudioSurvivesEvictionEvenWhenNewest() throws Exception {
        Path root = Files.createTempDirectory("qplayer-cache-protect-");
        DiskCache cache = new DiskCache(0);
        cache.setBaseDir(root.toString());
        byte[] payload = new byte[2 * 1024 * 1024]; // 2MB — three of these force eviction under a 3MB cap

        long now = System.currentTimeMillis();
        writeAudioFile(cache, 1L, payload, now - 30_000);
        writeAudioFile(cache, 2L, payload, now - 20_000);
        writeAudioFile(cache, 3L, payload, now - 10_000);

        // Playing song 2: a real play touches it (mtime -> now, the newest file
        // in the directory) and marks it protected.
        cache.getAudio(2L);
        cache.setCurrentlyPlaying(2L);

        // 3MB cap over 6MB of files forces evicting both unprotected ones even
        // though song 2 — being protected — is skipped despite being newest.
        cache.setMaxSizeMB(3);

        assertTrue("the currently-playing song must survive", cache.hasAudio(2L));
        assertEquals(payload.length, Files.size(java.nio.file.Paths.get(cache.audioPath(2L))));
        assertFalse("an older, unprotected song must be evicted first", cache.hasAudio(1L));
        assertFalse("a newer, unprotected song is still evicted before the"
                + " protected one, matching the newest-first policy", cache.hasAudio(3L));
    }

    private static void writeAudioFile(DiskCache cache, long neteaseId, byte[] payload,
                                       long lastModifiedMs) throws Exception {
        Path path = java.nio.file.Paths.get(cache.audioPath(neteaseId));
        Files.createDirectories(path.getParent());
        Files.write(path, payload);
        path.toFile().setLastModified(lastModifiedMs);
    }
}
