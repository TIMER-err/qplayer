package dev.t1m3.qplayer.cache;

import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public final class MediaMetaIndexTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void persistsCanonicalProviderAndEveryArtistCredit() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        try {
            Path base = temporaryFolder.newFolder("media-meta").toPath();
            AppDirs.setBase(base.toString());
            Track track = new Track();
            track.source = Track.Source.PLUGIN;
            track.mediaId = "fixture:song:42";
            track.title = "song";
            track.artist = "first / second";
            track.artistMediaId = "fixture:artist:7";
            track.artistIdsCsv = "fixture:artist:7,fixture:artist:8";
            track.artistNamesCsv = "first\u0001second";

            MediaMetaIndex written = new MediaMetaIndex();
            written.upsert(track);
            written.save();

            MediaMetaIndex restored = new MediaMetaIndex();
            restored.load();
            Track value = restored.all().get(0);
            assertEquals("fixture:song:42", value.canonicalId());
            assertEquals("fixture:artist:7", value.artistMediaId);
            assertEquals("fixture:artist:7,fixture:artist:8", value.artistIdsCsv);
            assertEquals("first\u0001second", value.artistNamesCsv);
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }
}
