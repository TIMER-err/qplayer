package dev.t1m3.qplayer.plugin;

/** Flat QML-facing account summary for one source plugin. */
public final class SourceAccountRow {
    public String providerId = "";
    /** 这个音源能不能新建歌单（2.1.1 库页判据读它）。 */
    public boolean canCreatePlaylist;
    /** Display name of the source itself, e.g. QQ音乐 -- not the account. */
    public String sourceName = "";
    public boolean primary;
    public boolean loggedIn;
    public String displayName = "";
    public String avatarUrl = "";
    public int membershipTier;
    public int level;
    public String signature = "";
}
