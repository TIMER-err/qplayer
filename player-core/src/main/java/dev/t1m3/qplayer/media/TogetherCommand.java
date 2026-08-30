package dev.t1m3.qplayer.media;

/** Latest synchronized transport command, with source-qualified song identities. */
public final class TogetherCommand {
    public String accountId = "";
    public String type = "";
    public String formerSongId = "";
    public String targetSongId = "";
    public long progressMs;
    public boolean playing;
    public long sequence;
}
