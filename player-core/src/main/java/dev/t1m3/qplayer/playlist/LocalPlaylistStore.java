package dev.t1m3.qplayer.playlist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence for the user's local playlists, in one JSON file.
 *
 * <p>Every mutating call returns after updating the in-memory list and marking
 * the file dirty; {@link #save()} is what touches the disk, so a caller can batch
 * a sync's worth of changes into one write from a worker thread.
 *
 * <p>All state is guarded by this object's monitor. The playlists handed out by
 * {@link #all()} and {@link #get(String)} are deep copies, so a caller can render
 * or export them without holding the lock or racing a concurrent sync.
 */
public final class LocalPlaylistStore {

    private static final int SCHEMA = 1;
    /** The single mixed list this feature grew out of. Its songs become the first
     *  local playlist the first time the new store loads. */
    private static final String LEGACY_FILE = "custom-playlist.json";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file = AppDirs.stateFile("local-playlists.json");
    private final List<LocalPlaylist> playlists = new ArrayList<>();
    private boolean dirty;
    private boolean loaded;

    /** Reads the file, migrating the pre-multi-playlist list on first run. */
    public synchronized void load() {
        if (loaded) return;
        loaded = true;
        boolean present = Files.isRegularFile(file);
        if (present) {
            try {
                State state = gson.fromJson(StorageFiles.readUtf8(file), State.class);
                if (state != null && state.items != null) {
                    for (LocalPlaylist playlist : state.items) {
                        if (playlist == null) continue;
                        playlist.sanitize();
                        playlists.add(playlist);
                    }
                }
            } catch (Throwable error) {
                Logger.warn("local playlists load failed: {}", error.getMessage());
            }
            return;
        }
        // No file yet: this is either a fresh install or an upgrade from the
        // single custom playlist. Write the result either way, so the migration
        // is attempted exactly once even if it finds nothing.
        List<PlaylistTrack> legacy = readLegacyTracks();
        if (!legacy.isEmpty()) {
            LocalPlaylist migrated = LocalPlaylist.create(
                    I18n.tr("playlist.local.migratedName"), System.currentTimeMillis());
            for (PlaylistTrack track : legacy) migrated.add(track);
            playlists.add(migrated);
            Logger.info("migrated {} songs from the previous custom playlist", legacy.size());
        }
        dirty = true;
    }

    public synchronized List<LocalPlaylist> all() {
        List<LocalPlaylist> out = new ArrayList<>(playlists.size());
        for (LocalPlaylist playlist : playlists) out.add(copy(playlist));
        return out;
    }

    public synchronized int count() {
        return playlists.size();
    }

    /** A detached copy, safe to read off-thread. */
    public synchronized LocalPlaylist get(String id) {
        LocalPlaylist found = find(id);
        return found != null ? copy(found) : null;
    }

    public synchronized LocalPlaylist create(String name) {
        // Two playlists called the same thing are indistinguishable on the grid,
        // and the default name is reached often (the "new playlist" menu entry).
        LocalPlaylist playlist = LocalPlaylist.create(
                uniqueName(name), System.currentTimeMillis());
        playlists.add(playlist);
        dirty = true;
        return copy(playlist);
    }

    public synchronized boolean delete(String id) {
        boolean removed = playlists.removeIf(p -> p.id.equals(id));
        if (removed) dirty = true;
        return removed;
    }

    public synchronized boolean rename(String id, String name) {
        LocalPlaylist playlist = find(id);
        if (playlist == null || name == null || name.trim().isEmpty()) return false;
        playlist.name = name.trim();
        touch(playlist);
        return true;
    }

    /**
     * Apply a change to one playlist under the lock.
     *
     * <p>Mutating through this rather than through a handed-out copy is what
     * keeps a concurrent subscription sync and a user edit from overwriting each
     * other: both go through the same monitor, against the live object.
     *
     * @return whether the mutation reported a change (and thus marked it dirty)
     */
    public synchronized boolean mutate(String id, Mutation mutation) {
        LocalPlaylist playlist = find(id);
        if (playlist == null || mutation == null) return false;
        boolean changed = mutation.apply(playlist);
        if (changed) touch(playlist);
        return changed;
    }

    @FunctionalInterface
    public interface Mutation {
        boolean apply(LocalPlaylist playlist);
    }

    /** Insert an imported playlist under a fresh id, so importing the same file
     *  twice produces two playlists rather than silently overwriting one. */
    public synchronized LocalPlaylist insertImported(LocalPlaylist imported) {
        if (imported == null) return null;
        imported.id = LocalPlaylist.newId();
        long now = System.currentTimeMillis();
        if (imported.createdAtMs == 0L) imported.createdAtMs = now;
        imported.updatedAtMs = now;
        imported.sanitize();
        imported.name = uniqueName(imported.name);
        playlists.add(imported);
        dirty = true;
        return copy(imported);
    }

    public synchronized void save() {
        if (!dirty) return;
        try {
            State state = new State();
            state.items = new ArrayList<>(playlists);
            StorageFiles.writeUtf8Atomic(file, gson.toJson(state));
            dirty = false;
        } catch (Throwable error) {
            Logger.warn("local playlists save failed: {}", error.getMessage());
        }
    }

    // ---- internals ---------------------------------------------------------

    private LocalPlaylist find(String id) {
        if (id == null || id.isEmpty()) return null;
        for (LocalPlaylist playlist : playlists) {
            if (id.equals(playlist.id)) return playlist;
        }
        return null;
    }

    private void touch(LocalPlaylist playlist) {
        playlist.updatedAtMs = System.currentTimeMillis();
        dirty = true;
    }

    /** "名字" -> "名字 (2)" when the name is taken, so an import is identifiable. */
    private String uniqueName(String requested) {
        String base = requested != null && !requested.trim().isEmpty()
                ? requested.trim() : I18n.tr("playlist.local.defaultName");
        String candidate = base;
        int suffix = 2;
        while (nameTaken(candidate)) candidate = base + " (" + suffix++ + ")";
        return candidate;
    }

    private boolean nameTaken(String name) {
        for (LocalPlaylist playlist : playlists) {
            if (name.equals(playlist.name)) return true;
        }
        return false;
    }

    private LocalPlaylist copy(LocalPlaylist playlist) {
        return gson.fromJson(gson.toJson(playlist), LocalPlaylist.class);
    }

    /** Read the pre-multi-playlist file. Its records are the same shape modulo
     *  field names, and it is left on disk untouched as a rollback path. */
    private List<PlaylistTrack> readLegacyTracks() {
        List<PlaylistTrack> out = new ArrayList<>();
        try {
            Path legacy = AppDirs.stateFile(LEGACY_FILE);
            if (!Files.isRegularFile(legacy)) return out;
            com.google.gson.JsonObject root = com.google.gson.JsonParser
                    .parseString(StorageFiles.readUtf8(legacy)).getAsJsonObject();
            if (!root.has("tracks")) return out;
            long now = System.currentTimeMillis();
            for (com.google.gson.JsonElement element : root.getAsJsonArray("tracks")) {
                if (!element.isJsonObject()) continue;
                com.google.gson.JsonObject object = element.getAsJsonObject();
                // Rebuild a Track first rather than mapping the fields straight
                // across: the oldest records predate the mediaId field entirely,
                // and Track.canonicalId() is what derives an id from a file path
                // or a legacy numeric id for them.
                dev.t1m3.qplayer.model.Track track = new dev.t1m3.qplayer.model.Track();
                String source = string(object, "source");
                track.source = "LOCAL".equals(source) ? dev.t1m3.qplayer.model.Track.Source.LOCAL
                        : "CUSTOM_API".equals(source) ? dev.t1m3.qplayer.model.Track.Source.CUSTOM_API
                        : "PLUGIN".equals(source) ? dev.t1m3.qplayer.model.Track.Source.PLUGIN
                        : dev.t1m3.qplayer.model.Track.Source.NETEASE;
                track.neteaseId = object.has("neteaseId") ? object.get("neteaseId").getAsLong() : 0L;
                String mediaId = string(object, "mediaId");
                if (!mediaId.isEmpty()) {
                    try {
                        track.applyCanonicalId(mediaId);
                    } catch (IllegalArgumentException ignored) {
                        // fall through to the derived id below
                    }
                }
                track.title = string(object, "title");
                track.artist = string(object, "artist");
                track.artistMediaId = string(object, "artistMediaId");
                track.artistIdsCsv = string(object, "artistIdsCsv");
                track.artistNamesCsv = string(object, "artistNamesCsv");
                track.album = string(object, "album");
                track.coverUrl = string(object, "coverUrl");
                String filePath = string(object, "filePath");
                String contentUri = string(object, "contentUri");
                track.filePath = filePath.isEmpty() ? null : filePath;
                track.contentUri = contentUri.isEmpty() ? null : contentUri;
                track.durationMs = object.has("durationMs") ? object.get("durationMs").getAsLong() : 0L;
                PlaylistTrack converted = PlaylistTrack.from(track, PlaylistTrack.MANUAL, now);
                if (converted.valid()) out.add(converted);
            }
        } catch (Throwable error) {
            Logger.warn("custom playlist migration failed: {}", error.getMessage());
        }
        return out;
    }

    private static String string(com.google.gson.JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : "";
    }

    private static final class State {
        int schemaVersion = SCHEMA;
        List<LocalPlaylist> items = new ArrayList<>();
    }
}
