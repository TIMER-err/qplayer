package dev.t1m3.qplayer.cache;

import dev.t1m3.qplayer.media.Playlist;
import dev.t1m3.qplayer.media.Song;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public final class MediaPlaylistCacheIndexTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void keepsProviderQualifiedPlaylistAndSongs() throws Exception {
        String oldBase = AppDirs.base();
        String oldCache = AppDirs.cacheBase();
        try {
            Path root = temporary.newFolder("playlist-index").toPath();
            AppDirs.setBase(root.toString());
            Playlist playlist = new Playlist();
            playlist.id = "fixture:playlist:daily";
            playlist.name = "Daily";
            Song song = new Song();
            song.id = "fixture:song:1";
            song.title = "One";
            playlist.songs.add(song);

            MediaPlaylistCacheIndex written = new MediaPlaylistCacheIndex();
            written.upsert(playlist);
            written.save();
            MediaPlaylistCacheIndex restored = new MediaPlaylistCacheIndex();
            restored.load();

            Playlist value = restored.get(playlist.id);
            assertEquals("Daily", value.name);
            assertEquals("fixture:song:1", value.songs.get(0).id);
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }
}
