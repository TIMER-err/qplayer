package dev.t1m3.qplayer.playlist;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.*;

public class PlaylistStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void corruptPlaylistFileIsRetainedAndCannotBeOverwritten() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("local-playlists.json");
        for (String original : new String[]{"{broken", "{}", "null"}) {
            Files.write(file, (original).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            LocalPlaylistStore store = new LocalPlaylistStore(file);
            store.load();
            assertFalse(store.save());
            assertThrows(IllegalStateException.class, () -> store.create("new"));
            assertThrows(IllegalStateException.class,
                    () -> store.insertImported(LocalPlaylist.create("import", 1L)));
            assertEquals(original, new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    public void failedLegacyMigrationDoesNotCommitAnEmptyReplacement() throws Exception {
        Path directory = temporary.newFolder("legacy").toPath();
        Path legacy = directory.resolve("custom-playlist.json");
        Path file = directory.resolve("local-playlists.json");
        Files.write(legacy, ("{broken").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        LocalPlaylistStore store = new LocalPlaylistStore(file);
        store.load();
        assertFalse(store.save());
        assertThrows(IllegalStateException.class, () -> store.create("new"));
        assertFalse(Files.exists(file));
        assertEquals("{broken", new String(Files.readAllBytes(legacy), java.nio.charset.StandardCharsets.UTF_8));
        Files.write(legacy, ("{\"tracks\":[]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        store.load();
        assertTrue(store.save());
    }

    @Test
    public void unreadablePlaylistPathIsNotTreatedAsAbsent() throws Exception {
        Path file = temporary.newFolder("not-a-file").toPath();
        LocalPlaylistStore store = new LocalPlaylistStore(file);
        store.load();
        assertThrows(IllegalStateException.class, () -> store.create("new"));
        assertFalse(store.save());
        assertTrue(Files.isDirectory(file));
    }

    @Test
    public void playlistSaveFailureRemainsDirtyAndCanBeRetried() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("local-playlists.json");
        LocalPlaylistStore store = new LocalPlaylistStore(file);
        store.load();
        String id = store.create("retained").id;
        Files.createDirectory(file);
        assertFalse(store.save());
        Files.delete(file);
        assertTrue(store.save());
        LocalPlaylistStore restored = new LocalPlaylistStore(file);
        restored.load();
        assertEquals("retained", restored.get(id).name);
    }

    @Test
    public void repairedFileCanBeLoadedAgainWithoutLosingIt() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("local-playlists.json");
        Files.write(file, ("broken").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        LocalPlaylistStore store = new LocalPlaylistStore(file);
        store.load();
        Files.write(file, ("{\"schemaVersion\":1,\"items\":[]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        store.load();
        store.create("after repair");
        assertTrue(store.save());
    }

    @Test
    public void metadataQueriesReturnDetachedImmutableSummaries() throws Exception {
        LocalPlaylistStore store = new LocalPlaylistStore(
                temporary.getRoot().toPath().resolve("local-playlists.json"));
        store.load();
        String id = store.create("first").id;
        store.mutate(id, playlist -> {
            PlaylistTrack track = new PlaylistTrack();
            track.mediaId = "fixture:song:1";
            track.provider = "fixture";
            track.coverUrl = "https://example.com/cover";
            playlist.tracks.add(track);
            playlist.description = "description";
            return true;
        });
        assertTrue(store.contains(id, "fixture:song:1"));
        assertFalse(store.contains(id, "fixture:song:2"));
        LocalPlaylistStore.Summary summary = store.summaries().get(0);
        assertEquals("first", summary.name);
        assertEquals("description", summary.description);
        assertEquals("https://example.com/cover", summary.artworkUrl);
        assertEquals(1, summary.trackCount);
        assertThrows(UnsupportedOperationException.class, () -> store.summaries().clear());
        store.rename(id, "second");
        store.mutate(id, playlist -> { playlist.tracks.clear(); return true; });
        assertEquals("first", summary.name);
        assertEquals(1, summary.trackCount);
    }

    @Test
    public void corruptOrderIsRetainedAndSaveFailureCanBeRetried() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("my-playlist-order.json");
        Files.write(file, ("{broken").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MyPlaylistOrderStore corrupt = new MyPlaylistOrderStore(file);
        corrupt.load();
        assertFalse(corrupt.save());
        assertThrows(IllegalStateException.class,
                () -> corrupt.setOrder(Collections.singletonList("fixture:playlist:1")));
        assertEquals("{broken", new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
        Files.delete(file);
        MyPlaylistOrderStore store = new MyPlaylistOrderStore(file);
        store.load();
        store.setOrder(Collections.singletonList("fixture:playlist:1"));
        Files.createDirectory(file);
        assertFalse(store.save());
        Files.delete(file);
        assertTrue(store.save());
        MyPlaylistOrderStore restored = new MyPlaylistOrderStore(file);
        restored.load();
        assertEquals(Collections.singletonList("fixture:playlist:1"), restored.order());
        assertEquals(MyPlaylistOrderStore.MODE_CUSTOM, restored.mode());
    }
}
