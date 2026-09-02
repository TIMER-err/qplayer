package dev.t1m3.qplayer.netease.dto;

/**
 * Minimal song descriptor decoded from netease search / playlist /
 * recommend responses. Keep this a flat POJO so the QML bridge can read its
 * fields directly.
 */
public class NeteaseSong {
    /** One credited artist -- id + name, so the UI can list every creator of a
     *  song (not just the first-listed one) and let the user pick whose page
     *  to open. */
    public static class ArtistRef {
        public long id;
        /** Canonical plugin artist id such as {@code netease:artist:123}. Empty for
         *  legacy numeric credits. */
        public String mediaId = "";
        public String name;
        /** Avatar, filled in lazily (a song's own artist list has no picture
         *  data) after {@code PlayerController#openSongArtistPicker} fetches
         *  each artist's profile in the background. Null until then. */
        public String coverUrl;
        public String coverThumbPath;
    }

    public long id;
    public String name;
    /** All artists joined with " / ". Null if absent. */
    public String artist;
    /** Id of the first-listed artist -- lets the UI open that artist's page. 0 if absent. */
    public long artistId;
    /** Every credited artist's id, comma-joined, in listed order. Empty if absent.
     *  A flat String rather than a List&lt;ArtistRef&gt; field deliberately: this
     *  codebase has no proven case of a List-typed field NESTED inside a row
     *  object (as opposed to a top-level bridge Property) surviving the QML
     *  bridge correctly -- plain String fields are proven everywhere, so
     *  SongContextMenu's "查看歌手" picker parses these two CSVs instead of
     *  iterating a nested list. See {@link ArtistRef} / {@code
     *  PlayerController#openSongArtistPicker}. */
    public String artistIdsCsv = "";
    /** Every credited artist's name, in the same order as {@link #artistIdsCsv},
     *  joined on U+0001 (not comma) so a name containing a comma can't desync
     *  the pairing. Empty if absent. */
    public String artistNamesCsv = "";
    public String album;
    /** Id of the album -- lets the UI open the album page. 0 if absent. */
    public long albumId;
    /** Album cover URL (CDN, jpg/png). Renderer fetches bytes lazily. */
    public String coverUrl;
    /** CDN thumbnail URL (coverUrl + ?param=128y128) for QML Image.source.
     *  Set by the controller after a search completes; null until resolved. */
    public String coverThumbPath;
    /** Track length in milliseconds (field "dt" in the JSON). */
    public long durationMs;
    /** Set when the song is VIP / unavailable to anonymous clients. */
    public boolean fee;
    /** Whether this song's audio is already on disk ({@code DiskCache.hasAudio}) --
     *  i.e. playable with no network. Set by whoever builds the list (openPlaylist,
     *  offlinePlaylistFallback); QML uses it to badge "offline-ready" tracks while
     *  {@code player.playlistOffline} is true. Not persisted -- computed fresh
     *  every time a song list is built, since the cache itself can change. */
    public boolean cachedOffline;
}
