package dev.t1m3.qplayer.media;

import java.util.ArrayList;
import java.util.List;

/** Ordered raw lyric variants; parsing and rendering remain host-owned. */
public final class LyricsPayload {
    public List<Asset> assets = new ArrayList<>();

    public static final class Asset {
        public String format = "";
        public String role = "original";
        public String text = "";
    }
}
