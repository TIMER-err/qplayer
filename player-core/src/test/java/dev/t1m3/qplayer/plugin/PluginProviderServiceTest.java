package dev.t1m3.qplayer.plugin;

import dev.t1m3.qplayer.media.MediaId;
import dev.t1m3.qplayer.media.Page;
import dev.t1m3.qplayer.media.Playlist;
import dev.t1m3.qplayer.media.Song;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public final class PluginProviderServiceTest {

    private static Map<String, Object> named(String id, String name) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("name", name);
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Playlist parsePlaylist(Map<String, Object> playlist) throws Exception {
        // Artwork urls are left out so the parse never reaches the host api.
        PluginProviderService service = new PluginProviderService(null, null);
        Method parse = PluginProviderService.class.getDeclaredMethod(
                "parsePlaylist", String.class, Map.class);
        parse.setAccessible(true);
        return (Playlist) parse.invoke(service, "netease", playlist);
    }

    /** A real catalog answers with nameless credits (cloud-disk uploads, delisted
     *  artists). Rejecting the response made the whole playlist unopenable. */
    @Test public void namelessCreditsAreDroppedInsteadOfFailingThePlaylist() throws Exception {
        List<Object> artists = new ArrayList<>();
        artists.add(named("1", ""));
        artists.add(named("2", "有名字的歌手"));
        Map<String, Object> song = new LinkedHashMap<>();
        song.put("id", "42");
        song.put("title", "歌曲");
        song.put("artists", artists);
        song.put("album", named("7", ""));
        List<Object> songs = new ArrayList<>();
        songs.add(song);
        Map<String, Object> playlist = new LinkedHashMap<>();
        playlist.put("id", "9");
        playlist.put("name", "歌单");
        playlist.put("songs", songs);

        Playlist parsed = parsePlaylist(playlist);

        assertEquals(1, parsed.songs.size());
        Song only = parsed.songs.get(0);
        assertEquals(1, only.artists.size());
        assertEquals("有名字的歌手", only.artists.get(0).name);
        assertEquals("netease:artist:2", only.artists.get(0).id);
        assertNull("an unnamed album reference stays unset", only.album);
    }

    private static Map<String, Object> song(String id, String title) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("title", title);
        return value;
    }

    /** Services hand back stub entries for delisted or region-locked tracks: no id,
     *  and sometimes no title either. One of those anywhere in the response used to
     *  make the whole playlist, album, home shelf or search page throw, so the user
     *  got an empty page instead of the songs that were perfectly fine. */
    @Test public void anUnusableSongIsDroppedInsteadOfFailingThePlaylist() throws Exception {
        List<Object> songs = new ArrayList<>();
        songs.add(song("1", ""));               // delisted stub: no title
        songs.add(song("", "没有 id 的歌"));      // delisted stub: no id
        songs.add(song("42", "正常歌曲"));
        Map<String, Object> playlist = new LinkedHashMap<>();
        playlist.put("id", "9");
        playlist.put("name", "歌单");
        playlist.put("songs", songs);

        Playlist parsed = parsePlaylist(playlist);

        assertEquals(1, parsed.songs.size());
        assertEquals("正常歌曲", parsed.songs.get(0).title);
    }

    /** queue.replace hands over a list the plugin built itself, so a bad entry there
     *  is the plugin's own bug — it must surface rather than silently vanish. */
    @Test public void validateSongsStillRejectsAMalformedEntry() {
        PluginProviderService service = new PluginProviderService(null, null);
        List<Object> songs = new ArrayList<>();
        songs.add(song("1", ""));
        assertThrows(PluginExecutionException.class,
                () -> service.validateSongs("netease", songs));
    }

    /** List rows draw at ~48dp; without a small variant every visible row pulled
     *  and decoded the full-size cover while scrolling. */
    @Test public void aRowThumbFallsBackToTheFullArtwork() throws Exception {
        Map<String, Object> song = new LinkedHashMap<>();
        song.put("id", "42");
        song.put("title", "歌曲");
        Map<String, Object> playlist = new LinkedHashMap<>();
        playlist.put("id", "9");
        playlist.put("name", "歌单");
        List<Object> songs = new ArrayList<>();
        songs.add(song);
        playlist.put("songs", songs);

        Song parsed = parsePlaylist(playlist).songs.get(0);

        assertEquals(parsed.artworkUrl, parsed.coverThumbPath);
    }

    @SuppressWarnings("unchecked")
    private static Page<Playlist> parsePlaylistPage(Object raw) throws Exception {
        PluginProviderService service = new PluginProviderService(null, null);
        Method parse = PluginProviderService.class.getDeclaredMethod(
                "parsePlaylistPage", String.class, Object.class);
        parse.setAccessible(true);
        try {
            return (Page<Playlist>) parse.invoke(service, "netease", raw);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException) {
                throw (RuntimeException) exception.getCause();
            }
            throw exception;
        }
    }

    private static Map<String, Object> playlistPage(List<?> items, String cursor) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("items", items);
        value.put("nextCursor", cursor);
        return value;
    }

    @Test public void playlistSearchQualifiesIdsAndPreservesSummaryAndCursor() throws Exception {
        Map<String, Object> playlist = named("9", "歌单");
        playlist.put("owner", named("42", "创建者"));
        playlist.put("trackCount", 25);
        playlist.put("playCount", 1000);
        playlist.put("description", "简介");

        Page<Playlist> page = parsePlaylistPage(playlistPage(
                Collections.singletonList(playlist), "opaque:next/2"));

        assertEquals("opaque:next/2", page.nextCursor);
        assertEquals(1, page.items.size());
        Playlist parsed = page.items.get(0);
        assertEquals("netease:playlist:9", parsed.id);
        assertEquals("netease:user:42", parsed.owner.id);
        assertEquals("创建者", parsed.owner.name);
        assertEquals(25L, parsed.trackCount);
        assertEquals(1000L, parsed.playCount);
        assertEquals("简介", parsed.description);
        assertEquals(0, parsed.songs.size());
    }

    @Test public void playlistSearchKeepsNativeIdsInsideTheRespondingProvider() throws Exception {
        Map<String, Object> playlist = named("other:song:9", "歌单");
        playlist.put("owner", named("other:user:42", "创建者"));
        Playlist parsed = parsePlaylistPage(playlistPage(
                Collections.singletonList(playlist), "")).items.get(0);

        assertEquals("netease:playlist:other%3Asong%3A9", parsed.id);
        assertEquals("netease:user:other%3Auser%3A42", parsed.owner.id);
    }

    @Test public void playlistSearchEnforcesPageAndCursorBounds() throws Exception {
        Map<String, Object> playlist = named("9", "歌单");
        String maxCursor = String.join("", Collections.nCopies(8192, "x"));
        Page<Playlist> page = parsePlaylistPage(playlistPage(
                Collections.nCopies(200, playlist), maxCursor));
        assertEquals(200, page.items.size());
        assertEquals(maxCursor, page.nextCursor);

        assertThrows(PluginExecutionException.class, () -> parsePlaylistPage(
                playlistPage(Collections.nCopies(201, playlist), "")));
        assertThrows(PluginExecutionException.class, () -> parsePlaylistPage(
                playlistPage(Collections.emptyList(), maxCursor + "x")));
        assertEquals("", parsePlaylistPage(
                Collections.singletonMap("items", Collections.emptyList())).nextCursor);
    }

    @Test public void playlistSearchRejectsMalformedSummaries() {
        assertThrows(PluginExecutionException.class, () -> parsePlaylistPage(
                playlistPage(Collections.singletonList(named("", "歌单")), "")));
        assertThrows(PluginExecutionException.class, () -> parsePlaylistPage(
                playlistPage(Collections.singletonList(named("9", "")), "")));
        assertThrows(PluginExecutionException.class, () -> parsePlaylistPage(
                playlistPage(Collections.singletonList("not an object"), "")));
    }

    @Test public void playlistNavigationRejectsWrongKindsAndForeignSongIds() {
        PluginProviderService service = new PluginProviderService(null, null);
        assertThrows(IllegalArgumentException.class,
                () -> service.playlist(MediaId.parse("netease:song:9")));
        assertThrows(IllegalArgumentException.class, () -> service.songsByIds(
                "netease", Collections.singletonList("netease:playlist:9")));
        assertThrows(IllegalArgumentException.class, () -> service.songsByIds(
                "netease", Collections.singletonList("other:song:42")));
    }
}
