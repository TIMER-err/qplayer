package dev.t1m3.qplayer.cache;

import com.google.gson.Gson;
import dev.t1m3.qplayer.media.MediaId;
import dev.t1m3.qplayer.media.MediaKind;
import dev.t1m3.qplayer.media.Playlist;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, source-qualified playlist snapshots for offline reopening. */
public final class MediaPlaylistCacheIndex {
    private static final int SCHEMA = 1;
    private static final int MAX_ENTRIES = 100;
    private final Path file = AppDirs.indexFile("playlists-v2.json");
    private final Gson gson = new Gson();
    private final Map<String, Playlist> byId = Collections.synchronizedMap(
            new LinkedHashMap<String, Playlist>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Playlist> entry) {
                    return size() > MAX_ENTRIES;
                }
            });
    private volatile boolean dirty;

    public void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            State state = gson.fromJson(StorageFiles.readUtf8(file), State.class);
            if (state == null || state.schemaVersion != SCHEMA || state.items == null) {
                throw new java.io.IOException("unsupported playlist metadata index");
            }
            synchronized (byId) {
                for (Playlist playlist : state.items) {
                    if (valid(playlist)) byId.put(playlist.id, playlist);
                }
            }
        } catch (Throwable error) {
            Logger.warn("MediaPlaylistCacheIndex load failed: {}", error.getMessage());
        }
    }

    public void upsert(Playlist playlist) {
        if (!valid(playlist)) return;
        // A JSON round trip makes the cache independent of plugin-owned mutable lists.
        Playlist copy = gson.fromJson(gson.toJson(playlist), Playlist.class);
        byId.put(copy.id, copy);
        dirty = true;
    }

    public Playlist get(String id) {
        Playlist value = byId.get(id);
        return value != null ? gson.fromJson(gson.toJson(value), Playlist.class) : null;
    }

    public void save() {
        if (!dirty) return;
        try {
            State state = new State();
            synchronized (byId) { state.items = new ArrayList<>(byId.values()); }
            StorageFiles.writeUtf8Atomic(file, gson.toJson(state));
            dirty = false;
        } catch (Throwable error) {
            Logger.warn("MediaPlaylistCacheIndex save failed: {}", error.getMessage());
        }
    }

    private static boolean valid(Playlist playlist) {
        if (playlist == null || playlist.id == null || playlist.id.isEmpty()) return false;
        try { MediaId.parse(playlist.id).requireKind(MediaKind.PLAYLIST); return true; }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private static final class State {
        int schemaVersion = SCHEMA;
        List<Playlist> items = new ArrayList<>();
    }
}
