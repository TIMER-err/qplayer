package dev.t1m3.qplayer.playlist;

/**
 * A source playlist a local playlist pulls its songs from.
 *
 * <p>Adding a whole playlist records one of these rather than a flat copy of its
 * songs: the songs it contributed all carry its {@link #sourcePlaylistId} as
 * their {@code origin}, so a later sync can replace exactly that set. Removing
 * the subscription removes those songs and nothing else.
 */
public final class PlaylistSubscription {

    /** Source playlist media id, e.g. {@code netease:playlist:123}. */
    public String sourcePlaylistId = "";
    /** Owning source id, split out for display and for import diagnostics. */
    public String provider = "";
    /** Last known name/cover of the source playlist, so the row still renders
     *  when the source is offline or signed out. */
    public String name = "";
    public String providerName = "";
    public String artworkUrl = "";
    public long lastSyncedAtMs;
    /** Songs this subscription contributed at the last successful sync. */
    public int trackCount;
    /** Populated when the last sync attempt failed; cleared on success. Shown on
     *  the playlist page so a silently stale subscription is still visible. */
    public String lastError = "";

    public PlaylistSubscription() {
    }

    public PlaylistSubscription(String sourcePlaylistId, String name, String providerName,
                                String artworkUrl) {
        this.sourcePlaylistId = sourcePlaylistId != null ? sourcePlaylistId : "";
        this.provider = PlaylistTrack.providerOf(this.sourcePlaylistId);
        this.name = name != null ? name : "";
        this.providerName = providerName != null ? providerName : "";
        this.artworkUrl = artworkUrl != null ? artworkUrl : "";
    }
}
