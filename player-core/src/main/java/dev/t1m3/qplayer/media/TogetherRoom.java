package dev.t1m3.qplayer.media;

import java.util.ArrayList;
import java.util.List;

/** Provider-neutral Listen Together room metadata. */
public final class TogetherRoom {
    public String id = "";
    public String creatorAccountId = "";
    public long effectiveDurationMs;
    public List<AccountProfile> members = new ArrayList<>();
}
