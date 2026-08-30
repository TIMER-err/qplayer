package dev.t1m3.qplayer.media;

import java.util.ArrayList;
import java.util.List;

/** Source-neutral artist detail DTO. */
public final class Artist {
    public String id = "";
    public String name = "";
    public String artworkUrl = "";
    public String coverUrl = "";
    public String coverThumbPath = "";
    public String description = "";
    public int albumCount;
    public int songCount;
    public List<Song> songs = new ArrayList<>();
    public List<Album> albums = new ArrayList<>();
}
