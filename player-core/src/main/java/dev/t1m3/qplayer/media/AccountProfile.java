package dev.t1m3.qplayer.media;

/** One active account profile owned by one source plugin. */
public final class AccountProfile {
    public String id = "";
    public String displayName = "";
    public String avatarUrl = "";
    public boolean loggedIn;
    public int membershipTier;
    public int level;
    public String signature = "";
}
