package dev.t1m3.qplayer.playlist;

import dev.t1m3.qplayer.media.MediaId;
import dev.t1m3.qplayer.media.MediaKind;
import dev.t1m3.qplayer.model.Track;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The set arithmetic a mixed playlist rests on: a sync must follow its source
 * exactly, while never disturbing what the user curated by hand.
 */
public class LocalPlaylistTest {

    private static final String SOURCE = "netease:playlist:1";
    private static final String OTHER_SOURCE = "qq:playlist:9";

    private static PlaylistTrack song(String provider, String nativeId, String title) {
        Track track = new Track();
        track.source = Track.Source.PLUGIN;
        track.mediaId = MediaId.of(provider, MediaKind.SONG, nativeId).toString();
        track.title = title;
        track.artist = "artist";
        track.durationMs = 1000L;
        return PlaylistTrack.from(track, PlaylistTrack.MANUAL, 1L);
    }

    private static List<String> titles(LocalPlaylist playlist) {
        List<String> out = new ArrayList<>();
        for (PlaylistTrack track : playlist.tracks) out.add(track.title);
        return out;
    }

    private static LocalPlaylist withSubscription() {
        LocalPlaylist playlist = LocalPlaylist.create("mixed", 1L);
        playlist.subscribe(new PlaylistSubscription(SOURCE, "source", "NetEase", ""));
        return playlist;
    }

    @Test
    public void aSyncFollowsTheSourceInBothDirections() {
        LocalPlaylist playlist = withSubscription();
        playlist.applySync(SOURCE, Arrays.asList(
                song("netease", "1", "a"), song("netease", "2", "b")), 10L);
        assertEquals(Arrays.asList("a", "b"), titles(playlist));

        // The source dropped "a" and gained "c".
        playlist.applySync(SOURCE, Arrays.asList(
                song("netease", "2", "b"), song("netease", "3", "c")), 20L);
        assertEquals("a song the source removed is gone locally too",
                Arrays.asList("b", "c"), titles(playlist));
    }

    @Test
    public void aSyncNeverTouchesHandAddedSongs() {
        LocalPlaylist playlist = withSubscription();
        playlist.add(song("local", "mine", "kept"));
        playlist.applySync(SOURCE, Arrays.asList(song("netease", "1", "a")), 10L);
        // The source now returns nothing at all — a signed-out or emptied playlist.
        playlist.applySync(SOURCE, new ArrayList<PlaylistTrack>(), 20L);
        assertEquals("only the subscription's songs are replaced",
                Arrays.asList("kept"), titles(playlist));
    }

    @Test
    public void aSubscriptionBlockKeepsItsPositionAcrossSyncs() {
        LocalPlaylist playlist = withSubscription();
        playlist.add(song("local", "first", "first"));
        playlist.applySync(SOURCE, Arrays.asList(song("netease", "1", "a")), 10L);
        playlist.add(song("local", "last", "last"));
        assertEquals(Arrays.asList("first", "a", "last"), titles(playlist));

        playlist.applySync(SOURCE, Arrays.asList(
                song("netease", "1", "a"), song("netease", "2", "b")), 20L);
        assertEquals("the synced block stays where it was rather than moving to the end",
                Arrays.asList("first", "a", "b", "last"), titles(playlist));
    }

    @Test
    public void aSyncCannotStealASongTheUserAlreadyAdded() {
        LocalPlaylist playlist = withSubscription();
        playlist.add(song("netease", "1", "mine"));
        playlist.applySync(SOURCE, Arrays.asList(
                song("netease", "1", "theirs"), song("netease", "2", "b")), 10L);

        assertEquals("the hand-added copy wins and is not duplicated",
                Arrays.asList("mine", "b"), titles(playlist));
        // Because that song stayed manual, unfollowing must not take it away.
        playlist.unsubscribe(SOURCE);
        assertEquals(Arrays.asList("mine"), titles(playlist));
    }

    @Test
    public void unfollowingRemovesOnlyThatSourcesSongs() {
        LocalPlaylist playlist = withSubscription();
        playlist.subscribe(new PlaylistSubscription(OTHER_SOURCE, "other", "QQ", ""));
        playlist.add(song("local", "mine", "manual"));
        playlist.applySync(SOURCE, Arrays.asList(song("netease", "1", "from-netease")), 10L);
        playlist.applySync(OTHER_SOURCE, Arrays.asList(song("qq", "1", "from-qq")), 10L);

        assertTrue(playlist.unsubscribe(SOURCE));
        assertEquals(Arrays.asList("manual", "from-qq"), titles(playlist));
        assertNull(playlist.subscription(SOURCE));
        assertNotNull(playlist.subscription(OTHER_SOURCE));
    }

    @Test
    public void theSameSongIsNeverStoredTwice() {
        LocalPlaylist playlist = LocalPlaylist.create("mixed", 1L);
        assertTrue(playlist.add(song("netease", "1", "a")));
        assertFalse("adding the same song again is refused",
                playlist.add(song("netease", "1", "a")));
        assertEquals(1, playlist.size());
    }

    @Test
    public void aSyncReportsHowManySongsTheSubscriptionContributes() {
        LocalPlaylist playlist = withSubscription();
        playlist.add(song("netease", "1", "mine"));
        int applied = playlist.applySync(SOURCE, Arrays.asList(
                song("netease", "1", "dup"), song("netease", "2", "b")), 30L);
        assertEquals("the skipped duplicate is not counted", 1, applied);
        PlaylistSubscription subscription = playlist.subscription(SOURCE);
        assertEquals(1, subscription.trackCount);
        assertEquals(30L, subscription.lastSyncedAtMs);
        assertEquals("a successful sync clears the previous error", "", subscription.lastError);
    }

    @Test
    public void loadingRepairsRecordsThatCannotIdentifyASong() {
        LocalPlaylist playlist = withSubscription();
        playlist.tracks.add(song("netease", "1", "good"));
        PlaylistTrack broken = song("netease", "2", "broken");
        broken.mediaId = "not-a-media-id";
        playlist.tracks.add(broken);
        PlaylistTrack duplicate = song("netease", "1", "duplicate");
        playlist.tracks.add(duplicate);
        // An origin pointing at a subscription that is no longer followed.
        PlaylistTrack orphan = song("netease", "3", "orphan");
        orphan.origin = OTHER_SOURCE;
        playlist.tracks.add(orphan);

        playlist.sanitize();

        assertEquals(Arrays.asList("good", "orphan"), titles(playlist));
        assertEquals("an unreachable origin becomes a hand-added song rather than "
                        + "a row no sync can ever refresh",
                PlaylistTrack.MANUAL, playlist.tracks.get(1).origin);
    }

    // ---- sorting -----------------------------------------------------------

    private static PlaylistTrack song(String nativeId, String title, String artist,
                                      long durationMs, long addedAtMs) {
        PlaylistTrack track = song("netease", nativeId, title);
        track.artist = artist;
        track.durationMs = durationMs;
        track.addedAtMs = addedAtMs;
        return track;
    }

    private static LocalPlaylist threeSongs() {
        LocalPlaylist playlist = LocalPlaylist.create("mixed", 1L);
        playlist.add(song("1", "Banana", "Zoe", 300L, 30L));
        playlist.add(song("2", "apple", "adam", 100L, 20L));
        playlist.add(song("3", "Cherry", "Mia", 200L, 10L));
        return playlist;
    }

    private static List<String> sortedTitles(LocalPlaylist playlist, String field, boolean desc) {
        playlist.sortField = field;
        playlist.sortDescending = desc;
        List<String> out = new ArrayList<>();
        for (PlaylistTrack track : playlist.sortedTracks()) out.add(track.title);
        return out;
    }

    @Test
    public void sortingIsAViewAndNeverRewritesTheStoredOrder() {
        LocalPlaylist playlist = threeSongs();
        assertEquals(Arrays.asList("apple", "Banana", "Cherry"),
                sortedTitles(playlist, LocalPlaylist.SORT_TITLE, false));
        assertEquals("the stored order is what a sync and a drag act on, so it "
                        + "must survive sorting untouched",
                Arrays.asList("Banana", "apple", "Cherry"), titles(playlist));
        assertEquals("switching back restores exactly what was arranged",
                Arrays.asList("Banana", "apple", "Cherry"),
                sortedTitles(playlist, LocalPlaylist.SORT_CUSTOM, false));
    }

    @Test
    public void sortingByTitleIgnoresCase() {
        LocalPlaylist playlist = threeSongs();
        assertEquals("'apple' must not sort after 'Cherry' just for being lowercase",
                Arrays.asList("apple", "Banana", "Cherry"),
                sortedTitles(playlist, LocalPlaylist.SORT_TITLE, false));
        assertEquals(Arrays.asList("Cherry", "Banana", "apple"),
                sortedTitles(playlist, LocalPlaylist.SORT_TITLE, true));
    }

    @Test
    public void everySortFieldOrdersByItsOwnValue() {
        LocalPlaylist playlist = threeSongs();
        assertEquals(Arrays.asList("apple", "Cherry", "Banana"),
                sortedTitles(playlist, LocalPlaylist.SORT_ARTIST, false));
        assertEquals(Arrays.asList("apple", "Cherry", "Banana"),
                sortedTitles(playlist, LocalPlaylist.SORT_DURATION, false));
        assertEquals(Arrays.asList("Cherry", "apple", "Banana"),
                sortedTitles(playlist, LocalPlaylist.SORT_ADDED, false));
    }

    /** Descending must mirror the field only: two equal rows keep their stored
     *  order either way, or they would swap places between openings. */
    @Test
    public void tiesAlwaysFallBackToTheStoredOrder() {
        LocalPlaylist playlist = LocalPlaylist.create("mixed", 1L);
        playlist.add(song("1", "same", "first", 100L, 1L));
        playlist.add(song("2", "same", "second", 100L, 2L));
        playlist.add(song("3", "other", "third", 100L, 3L));

        playlist.sortField = LocalPlaylist.SORT_TITLE;
        playlist.sortDescending = false;
        assertEquals(Arrays.asList("first", "second"), artistsOf(playlist, "same"));

        playlist.sortDescending = true;
        assertEquals("the tie-break does not flip with the direction",
                Arrays.asList("first", "second"), artistsOf(playlist, "same"));
    }

    private static List<String> artistsOf(LocalPlaylist playlist, String title) {
        List<String> out = new ArrayList<>();
        for (PlaylistTrack track : playlist.sortedTracks()) {
            if (title.equals(track.title)) out.add(track.artist);
        }
        return out;
    }

    @Test
    public void untitledRowsSortLastRatherThanFirst() {
        LocalPlaylist playlist = LocalPlaylist.create("mixed", 1L);
        playlist.add(song("1", "", "", 0L, 1L));
        playlist.add(song("2", "beta", "", 0L, 2L));
        playlist.add(song("3", "alpha", "", 0L, 3L));
        assertEquals("a handful of blank rows must not push the real content down",
                Arrays.asList("alpha", "beta", ""),
                sortedTitles(playlist, LocalPlaylist.SORT_TITLE, false));
    }

    @Test
    public void anUnknownSortFieldFallsBackToTheStoredOrder() {
        LocalPlaylist playlist = threeSongs();
        playlist.sortField = "nonsense";
        assertEquals(Arrays.asList("Banana", "apple", "Cherry"),
                titlesOf(playlist.sortedTracks()));
        playlist.sanitize();
        assertEquals(LocalPlaylist.SORT_CUSTOM, playlist.sortField);
    }

    private static List<String> titlesOf(List<PlaylistTrack> tracks) {
        List<String> out = new ArrayList<>();
        for (PlaylistTrack track : tracks) out.add(track.title);
        return out;
    }

    @Test
    public void draggingRewritesTheStoredOrder() {
        LocalPlaylist playlist = threeSongs();
        assertTrue(playlist.move(0, 2));
        assertEquals(Arrays.asList("apple", "Cherry", "Banana"), titles(playlist));
        assertTrue(playlist.move(2, 0));
        assertEquals(Arrays.asList("Banana", "apple", "Cherry"), titles(playlist));
        assertFalse("a move to the same slot changes nothing", playlist.move(1, 1));
        assertFalse(playlist.move(-1, 0));
        assertFalse(playlist.move(0, 99));
    }

    /** A drag must not disturb which subscription a song belongs to, or the next
     *  sync would replace the wrong rows. */
    @Test
    public void draggingKeepsEveryOrigin() {
        LocalPlaylist playlist = withSubscription();
        playlist.add(song("local", "mine", "manual"));
        playlist.applySync(SOURCE, Arrays.asList(
                song("netease", "1", "a"), song("netease", "2", "b")), 10L);

        playlist.move(0, 2);
        for (PlaylistTrack track : playlist.tracks) {
            if ("manual".equals(track.title)) {
                assertEquals(PlaylistTrack.MANUAL, track.origin);
            } else {
                assertEquals(SOURCE, track.origin);
            }
        }
        // And the following sync still replaces exactly the subscription's rows.
        playlist.applySync(SOURCE, Arrays.asList(song("netease", "1", "a")), 20L);
        assertTrue(titles(playlist).contains("manual"));
        assertFalse(titles(playlist).contains("b"));
    }

    // ---- import / export ---------------------------------------------------

    @Test
    public void anExportedPlaylistRoundTrips() throws Exception {
        LocalPlaylist playlist = withSubscription();
        playlist.description = "notes";
        playlist.add(song("netease", "1", "a"));
        playlist.applySync(SOURCE, Arrays.asList(song("netease", "2", "b")), 10L);

        String json = PlaylistTransfer.export(playlist, "1.0.0",
                provider -> "netease".equals(provider) ? "NetEase" : provider);
        LocalPlaylist restored = PlaylistTransfer.parse(json);

        assertEquals(playlist.name, restored.name);
        assertEquals("notes", restored.description);
        assertEquals(titles(playlist), titles(restored));
        assertEquals(1, restored.subscriptions.size());
        assertEquals(SOURCE, restored.subscriptions.get(0).sourcePlaylistId);
        assertEquals("the imported copy is due a sync of its own",
                0L, restored.subscriptions.get(0).lastSyncedAtMs);
        // Origins have to survive, or an imported playlist would stop following
        // its sources the moment it was opened.
        assertEquals(PlaylistTrack.MANUAL, restored.tracks.get(0).origin);
        assertEquals(SOURCE, restored.tracks.get(1).origin);
    }

    @Test
    public void anExportNamesEverySourceItUses() {
        LocalPlaylist playlist = LocalPlaylist.create("mixed", 1L);
        playlist.add(song("netease", "1", "a"));
        playlist.add(song("netease", "2", "b"));
        playlist.add(song("qq", "1", "c"));

        String json = PlaylistTransfer.export(playlist, "1.0.0",
                provider -> "netease".equals(provider) ? "NetEase" : "QQ Music");

        assertTrue("the per-song source is recorded", json.contains("\"provider\": \"netease\""));
        assertTrue("and its display name travels with it", json.contains("NetEase"));
        assertTrue("QQ Music is summarised too", json.contains("QQ Music"));
        // The summary block is what lets an importer say which sources are needed
        // without walking every song.
        assertTrue(json.contains("\"sources\""));
    }

    @Test
    public void importingRejectsAFileThatIsNotOurs() {
        for (String candidate : Arrays.asList(
                "{\"format\":\"something.else\",\"tracks\":[]}",
                "[1,2,3]",
                "not json at all")) {
            try {
                PlaylistTransfer.parse(candidate);
                fail("should have rejected: " + candidate);
            } catch (PlaylistTransfer.UnsupportedFormat expected) {
                // expected
            }
        }
    }

    @Test
    public void importingRejectsAFormatFromTheFuture() {
        String json = "{\"format\":\"" + PlaylistTransfer.FORMAT
                + "\",\"formatVersion\":99,\"tracks\":[]}";
        try {
            PlaylistTransfer.parse(json);
            fail("a newer format must not be half-read");
        } catch (PlaylistTransfer.UnsupportedFormat expected) {
            assertTrue(expected.getMessage().contains("newer"));
        }
    }

    @Test
    public void suggestedFileNamesAreSafeOnEveryPlatform() {
        assertEquals("my songs" + PlaylistTransfer.FILE_SUFFIX,
                PlaylistTransfer.suggestedFileName("my songs"));
        assertEquals("a_b_c" + PlaylistTransfer.FILE_SUFFIX,
                PlaylistTransfer.suggestedFileName("a/b:c"));
        assertEquals("every illegal character is replaced rather than dropped",
                "___" + PlaylistTransfer.FILE_SUFFIX,
                PlaylistTransfer.suggestedFileName("///"));
        // A name that leaves nothing behind still has to produce a file.
        assertEquals("playlist" + PlaylistTransfer.FILE_SUFFIX,
                PlaylistTransfer.suggestedFileName(""));
        assertEquals("playlist" + PlaylistTransfer.FILE_SUFFIX,
                PlaylistTransfer.suggestedFileName("   "));
        // Windows rejects a trailing dot outright.
        assertFalse(PlaylistTransfer.suggestedFileName("trailing...")
                .contains("." + PlaylistTransfer.FILE_SUFFIX));
    }

    @Test
    public void aLocalFileSurvivesTheRoundTripAsAPlayableTrack() {
        Track original = new Track();
        original.source = Track.Source.LOCAL;
        original.filePath = "D:\\music\\song.flac";
        original.title = "song";
        original.artist = "artist";
        original.durationMs = 4321L;

        PlaylistTrack stored = PlaylistTrack.from(original, PlaylistTrack.MANUAL, 1L);
        assertTrue(stored.valid());
        assertEquals("local", stored.provider);

        Track restored = stored.toTrack();
        assertEquals(Track.Source.LOCAL, restored.source);
        assertEquals("D:\\music\\song.flac", restored.filePath);
        assertEquals("song", restored.title);
        assertEquals(4321L, restored.durationMs);
        assertEquals("identity is stable across the round trip",
                original.canonicalId(), restored.canonicalId());
    }
}
