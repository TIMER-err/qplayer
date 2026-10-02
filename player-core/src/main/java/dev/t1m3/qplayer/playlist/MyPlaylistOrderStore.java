package dev.t1m3.qplayer.playlist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * How 我的's cross-source playlist cards should be ordered: the server's own
 * grouping (primary source first, each source's own order within it), or an
 * order the user dragged into place.
 *
 * <p>Only ids are kept here — everything else about a card is re-fetched live
 * from its source on every {@code loadMyPlaylists()} — so this file stays tiny
 * even with hundreds of playlists across every signed-in source. An id from a
 * source that has since gone (unfollowed, source removed) simply has nothing to
 * match against and drops out on its own; a new playlist not yet in the stored
 * order is appended after the ones that are.
 *
 * <p>{@link #save()} is the only method that touches disk, same split as
 * {@link LocalPlaylistStore}: a caller mutates on whatever thread it is already
 * on, then submits {@code save()} to a worker.
 */
public final class MyPlaylistOrderStore {

    public static final String MODE_SOURCE = "source";
    public static final String MODE_CUSTOM = "custom";

    private static final int SCHEMA = 1;

    private final Gson gson = new GsonBuilder().create();
    private final Path file = AppDirs.stateFile("my-playlist-order.json");
    private final List<String> order = new ArrayList<>();
    private String mode = MODE_SOURCE;
    private boolean dirty;
    private boolean loaded;

    public synchronized void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(file)) return;
        try {
            State state = gson.fromJson(StorageFiles.readUtf8(file), State.class);
            if (state == null) return;
            if (MODE_CUSTOM.equals(state.mode)) mode = MODE_CUSTOM;
            if (state.ids != null) order.addAll(state.ids);
        } catch (Throwable error) {
            Logger.warn("my-playlist order load failed: {}", error.getMessage());
        }
    }

    public synchronized String mode() {
        return mode;
    }

    public synchronized List<String> order() {
        return new ArrayList<>(order);
    }

    public synchronized void setMode(String newMode) {
        String next = MODE_CUSTOM.equals(newMode) ? MODE_CUSTOM : MODE_SOURCE;
        if (next.equals(mode)) return;
        mode = next;
        dirty = true;
    }

    /** Replace the stored order wholesale — a drag hands over the whole resulting
     *  sequence, not one move to replay, since it is already the flattened id list
     *  the user just arranged. Also switches to custom mode: dragging only makes
     *  sense as "keep it exactly like this from now on". */
    public synchronized void setOrder(List<String> ids) {
        order.clear();
        if (ids != null) order.addAll(ids);
        mode = MODE_CUSTOM;
        dirty = true;
    }

    public synchronized void save() {
        if (!dirty) return;
        try {
            State state = new State();
            state.mode = mode;
            state.ids = order;
            StorageFiles.writeUtf8Atomic(file, gson.toJson(state));
            dirty = false;
        } catch (Throwable error) {
            Logger.warn("my-playlist order save failed: {}", error.getMessage());
        }
    }

    private static final class State {
        int schemaVersion = SCHEMA;
        String mode = MODE_SOURCE;
        List<String> ids = new ArrayList<>();
    }
}
