package dev.t1m3.qplayer.plugin;

/** Optional handler groups advertised by a source plugin. */
public enum ProviderCapability {
    HOT_SEARCH("hotSearch"),
    SEARCH_SONGS("searchSongs"), SEARCH_ALBUMS("searchAlbums"), SEARCH_ARTISTS("searchArtists"),
    SONG_DETAILS("songDetails"), PLAYLIST_DETAILS("playlistDetails"),
    ARTIST_DETAILS("artistDetails"), ALBUM_DETAILS("albumDetails"),
    HOME("home"), ACCOUNT("account"), USER_PLAYLISTS("userPlaylists"), RECENT("recent"),
    RESOLVE_STREAM("resolveStream"), LYRICS("lyrics"), SCROBBLE("scrobble"),
    LIKE("like"), PLAYLIST_MUTATION("playlistMutation"),
    HEART_RECOMMENDATION("heartRecommendation"), SHARE("share"),
    MATCH_SONG("matchSong"), LOGIN("login"),
    /** Manifest-only compatibility for pre-migration packages. The host does not
     * route or implement this handler; current plugins own the feature through
     * custom UI, backgroundTick and generic playback services. */
    @Deprecated LISTEN_TOGETHER("listenTogether");

    private final String wireName;

    ProviderCapability(String wireName) { this.wireName = wireName; }
    public String wireName() { return wireName; }

    public static ProviderCapability fromWireName(String value) {
        for (ProviderCapability capability : values()) {
            if (capability.wireName.equals(value)) return capability;
        }
        throw new IllegalArgumentException("unknown provider capability: " + value);
    }
}
