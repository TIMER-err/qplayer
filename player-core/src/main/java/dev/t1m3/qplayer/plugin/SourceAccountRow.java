package dev.t1m3.qplayer.plugin;

/** Flat QML-facing account summary for one source plugin. */
public final class SourceAccountRow {
    public String providerId = "";
    /** Display name of the source itself, e.g. QQ音乐 -- not the account. */
    public String sourceName = "";
    public boolean primary;
    public boolean loggedIn;
    /** True when this source can create playlists (PLAYLIST_MUTATION), so the
     *  new-playlist dialog can offer it as a target. */
    public boolean canCreatePlaylist;
    public String displayName = "";
    public String avatarUrl = "";
    public int membershipTier;
    public int level;
    public String signature = "";
}
