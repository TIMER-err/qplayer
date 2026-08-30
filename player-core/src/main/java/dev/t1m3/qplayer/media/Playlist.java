package dev.t1m3.qplayer.media;

import java.util.ArrayList;
import java.util.List;

/** Source-neutral playlist detail DTO. */
public final class Playlist {
    public String id = "";
    public String name = "";
    public String description = "";
    public String artworkUrl = "";
    public String coverUrl = "";
    public String coverThumbPath = "";
    public MediaRef owner;
    public long trackCount;
    public long playCount;
    public boolean subscribed;
    public boolean owned;
    public boolean mutable;
    public boolean deletable;
    public List<Song> songs = new ArrayList<>();
}
