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
    /** Replace a playlist's artwork. Separate from {@link #PLAYLIST_MUTATION}
     * because it carries an image payload rather than song ids, and because the
     * host needs to know whether a given source can do it at all — the change-cover
     * action stays hidden on sources that cannot, instead of failing on tap. */
    PLAYLIST_COVER("playlistCover"),
    /** Rearrange the songs in a playlist the user owns. Separate from
     * {@link #PLAYLIST_MUTATION} for the same reason as the cover: the host has to
     * know up front whether the source supports it, so the drag grip stays hidden
     * on the ones that do not rather than failing after the gesture. The handler
     * receives the complete new order, not a from/to pair — both providers that
     * implement it replace the whole list server-side anyway. */
    PLAYLIST_REORDER("playlistReorder"),
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
