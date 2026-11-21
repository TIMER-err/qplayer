package dev.t1m3.qplayer.media;

/** One active account profile owned by one source plugin. */
public final class AccountProfile {
    public String id = "";
    public String displayName = "";
    public String avatarUrl = "";
    public boolean loggedIn;
    /** 这个音源能不能新建歌单（2.1.1 的库页判据读它）。 */
    public boolean canCreatePlaylist;
    public int membershipTier;
    public int level;
    public String signature = "";
}
