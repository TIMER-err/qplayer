package dev.t1m3.qplayer.media;

import java.util.ArrayList;
import java.util.List;

/** Queue and command snapshot returned by a provider's Together implementation. */
public final class TogetherSnapshot {
    public List<String> songIds = new ArrayList<>();
    public TogetherCommand command;
}
