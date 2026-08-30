package dev.t1m3.qplayer.media;

/** Lightweight relation embedded in songs, albums and playlists. */
public final class MediaRef {
    public String id = "";
    public String name = "";

    public MediaRef() {}

    public MediaRef(String id, String name) {
        this.id = id != null ? id : "";
        this.name = name != null ? name : "";
    }
}
