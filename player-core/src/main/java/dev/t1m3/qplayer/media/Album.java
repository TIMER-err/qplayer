package dev.t1m3.qplayer.media;

import java.util.ArrayList;
import java.util.List;

/** Source-neutral album detail DTO. */
public final class Album {
    public String id = "";
    public String name = "";
    public List<MediaRef> artists = new ArrayList<>();
    public String artworkUrl = "";
    public String coverUrl = "";
    public String coverThumbPath = "";
    public String artistName = "";
    public String artistMediaId = "";
    public long publishTimeMs;
    public String description = "";
    public int trackCount;
    public List<Song> songs = new ArrayList<>();
}
