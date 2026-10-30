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
