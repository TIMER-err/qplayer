package dev.t1m3.qplayer.library;

import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class LibraryScannerResourcesTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void freshAudioDiscoversSidecarPriorityDeletionAndCoverChangesWithoutTagReads() throws Exception {
        String oldBase = AppDirs.base();
        String oldCache = AppDirs.cacheBase();
        try {
            AppDirs.setBase(temporary.newFolder("data").toString());
            AppDirs.setCacheBase(temporary.newFolder("cache").toString());
            Path music = temporary.newFolder("music").toPath();
            Files.write(music.resolve("song.mp3"), new byte[]{0});
            AtomicInteger reads = new AtomicInteger();
            LibraryScanner scanner = new LibraryScanner(track -> {
                reads.incrementAndGet();
                track.title = "tag title";
            });
            Track first = scanner.scan(music.toString()).get(0);
            assertNull(first.lyricFilePath);
            Path lrc = Files.write(music.resolve("song.lrc"), "[00:01]line".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Path cover = Files.write(music.resolve("cover.jpg"), new byte[]{1, 2, 3});
            Track second = scanner.scan(music.toString()).get(0);
            assertEquals(lrc.toString(), second.lyricFilePath);
            assertNotNull(second.coverLocalPath);
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(java.nio.file.Paths.get(second.coverLocalPath)));
            Path ttml = Files.write(music.resolve("song.ttml"), "<tt/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.write(cover, new byte[]{4, 5, 6, 7});
            Track third = scanner.scan(music.toString()).get(0);
            assertEquals(ttml.toString(), third.lyricFilePath);
            assertArrayEquals(new byte[]{4, 5, 6, 7}, Files.readAllBytes(java.nio.file.Paths.get(third.coverLocalPath)));
            Files.delete(java.nio.file.Paths.get(third.coverThumbPath));
            Track regenerated = scanner.scan(music.toString()).get(0);
            assertTrue(Files.isRegularFile(java.nio.file.Paths.get(regenerated.coverThumbPath)));
            Files.delete(ttml);
            Files.delete(cover);
            Track fourth = scanner.scan(music.toString()).get(0);
            assertEquals(lrc.toString(), fourth.lyricFilePath);
            assertNull(fourth.coverLocalPath);
            Files.delete(lrc);
            assertNull(scanner.scan(music.toString()).get(0).lyricFilePath);
            assertEquals("tag metadata should stay fresh through resource-only edits", 1, reads.get());
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    @Test public void deletedEmbeddedExtractionIsRebuiltAndSurvivesSidecarRoundTrip() throws Exception {
        String oldBase = AppDirs.base();
        String oldCache = AppDirs.cacheBase();
        try {
            AppDirs.setBase(temporary.newFolder("embedded-data").toString());
            AppDirs.setCacheBase(temporary.newFolder("embedded-cache").toString());
            Path music = temporary.newFolder("embedded-music").toPath();
            Files.write(music.resolve("song.mp3"), new byte[]{0});
            AtomicInteger reads = new AtomicInteger();
            LibraryScanner scanner = new LibraryScanner(track -> {
                reads.incrementAndGet();
                track.embeddedLyricText = "[00:01]embedded";
            });
            Track first = scanner.scan(music.toString()).get(0);
            String extracted = first.lyricFilePath;
            Path sidecar = Files.write(music.resolve("song.lrc"), "[00:01]sidecar".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(sidecar.toString(), scanner.scan(music.toString()).get(0).lyricFilePath);
            assertTrue("fallback extraction must not be pruned while sidecar wins", Files.exists(java.nio.file.Paths.get(extracted)));
            Files.delete(sidecar);
            assertEquals(extracted, scanner.scan(music.toString()).get(0).lyricFilePath);
            assertEquals(1, reads.get());
            Files.delete(java.nio.file.Paths.get(extracted));
            Track rebuilt = scanner.scan(music.toString()).get(0);
            assertEquals("[00:01]embedded", new String(Files.readAllBytes(java.nio.file.Paths.get(rebuilt.lyricFilePath)), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(2, reads.get());
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    @Test public void queueDiscoveryRejectsDirectoriesAndRetainsValidExplicitPaths() throws Exception {
        Path music = temporary.newFolder("queue-music").toPath();
        Path audio = Files.write(music.resolve("song.mp3"), new byte[]{0});
        Files.createDirectory(music.resolve("song.ttml"));
        Path lrc = Files.write(music.resolve("song.lrc"), "[00:01]line".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path translation = Files.write(music.resolve("custom.translation"), "[00:01]translation".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Track track = new Track();
        track.filePath = audio.toString();
        track.translationFilePath = translation.toString();
        track.romajiFilePath = music.resolve("missing.romaji").toString();
        LibraryScanner.discoverSidecars(track);
        assertEquals(lrc.toString(), track.lyricFilePath);
        assertEquals(translation.toString(), track.translationFilePath);
        assertNull(track.romajiFilePath);
    }
}
