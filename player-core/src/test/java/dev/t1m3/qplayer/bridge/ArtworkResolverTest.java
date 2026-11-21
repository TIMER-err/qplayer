package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.cache.DiskCache;
import dev.t1m3.qplayer.model.Track;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class ArtworkResolverTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void preloadsSurviveAdjacentSwitchesButLeaveTheQueueWindow() throws Exception {
        DiskCache cache = new DiskCache(200);
        cache.setBaseDir(temporaryFolder.getRoot().getAbsolutePath());
        ArtworkResolver resolver = new ArtworkResolver(cache);
        Track first = localCover("first", 1);
        Track second = localCover("second", 2);
        Track third = localCover("third", 3);
        Track fourth = localCover("fourth", 4);
        resolver.setQueueWindow(first, third, second);
        resolver.cachedCover(first);
        resolver.cachedCover(second);
        resolver.cachedCover(third);
        Files.write(Paths.get(first.coverLocalPath), new byte[]{9});
        Files.write(Paths.get(second.coverLocalPath), new byte[]{8});

        resolver.setQueueWindow(second, first, third);
        assertArrayEquals("adjacent artwork was already read", new byte[]{2},
                resolver.cachedCover(second).bytes());
        resolver.setQueueWindow(third, second, fourth);
        resolver.setQueueWindow(first, fourth, second);
        assertArrayEquals("leaving the window releases the old bytes", new byte[]{9},
                resolver.cachedCover(first).bytes());
        assertNull("warming must not root the pixels through queue records", first.coverBytes);
    }

    @Test public void clearingQueueReleasesPreloadedArtwork() throws Exception {
        DiskCache cache = new DiskCache(200);
        cache.setBaseDir(temporaryFolder.getRoot().getAbsolutePath());
        ArtworkResolver resolver = new ArtworkResolver(cache);
        Track track = localCover("clear", 1);
        resolver.setQueueWindow(track, null, null);
        resolver.cachedCover(track);
        Files.write(Paths.get(track.coverLocalPath), new byte[]{7});
        resolver.setQueueWindow(null, null, null);
        resolver.setQueueWindow(track, null, null);
        assertArrayEquals(new byte[]{7}, resolver.cachedCover(track).bytes());
    }

    private Track localCover(String name, int value) throws Exception {
        Track track = new Track();
        track.source = Track.Source.LOCAL;
        Path path = temporaryFolder.newFile(name + ".png").toPath();
        Files.write(path, new byte[]{(byte) value});
        track.coverLocalPath = path.toString();
        return track;
    }

    @Test public void pluginArtworkUsesTheExactProviderCacheKey() throws Exception {
        DiskCache cache = new DiskCache(200);
        cache.setBaseDir(temporaryFolder.getRoot().getAbsolutePath());
        Track track = new Track();
        track.source = Track.Source.PLUGIN;
        track.coverUrl = "https://images.example/cover?token=signed";
        Path image = Paths.get(cache.imagePath(track.coverUrl));
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{1, 2, 3});
        ArtworkResolver resolver = new ArtworkResolver(cache);
        assertEquals(track.coverUrl, ArtworkResolver.url(track, "1024"));
        assertEquals(image.toString(), resolver.cachedPath(track));
        assertArrayEquals(new byte[]{1, 2, 3}, resolver.cachedCover(track).bytes());
    }

    @Test public void browsingThumbnailDoesNotReplaceFullNowPlayingArtwork() throws Exception {
        DiskCache cache = new DiskCache(200);
        cache.setBaseDir(temporaryFolder.getRoot().getAbsolutePath());
        Track track = new Track();
        track.source = Track.Source.PLUGIN;
        track.coverUrl = "https://images.example/cover";
        Path thumb = Paths.get(cache.thumb64Path(track.coverUrl));
        Files.createDirectories(thumb.getParent());
        Files.write(thumb, new byte[]{1});
        ArtworkResolver resolver = new ArtworkResolver(cache);
        assertEquals(thumb.toString(), resolver.cachedPath(track));
        assertNull("the full artwork loader must continue fetching", resolver.cachedCover(track).bytes());
    }

    @Test public void onlyLegacyNeteaseRecordsReceiveTheResizeSuffix() {
        Track track = new Track();
        track.source = Track.Source.NETEASE;
        track.coverUrl = "https://p1.music.126.net/cover.jpg";
        assertEquals(track.coverUrl + "?param=512y512", ArtworkResolver.url(track, "512"));
        track.source = Track.Source.PLUGIN;
        assertEquals(track.coverUrl, ArtworkResolver.url(track, "512"));
    }
}
