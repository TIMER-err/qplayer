package dev.t1m3.qplayer.media;

import java.util.ArrayList;
import java.util.List;

/** Cursor page; cursors remain opaque to the host. */
public final class Page<T> {
    public List<T> items = new ArrayList<>();
    public String nextCursor = "";
}
