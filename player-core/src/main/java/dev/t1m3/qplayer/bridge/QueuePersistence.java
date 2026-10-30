package dev.t1m3.qplayer.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.t1m3.qplayer.library.LibraryScanner;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.store.StorageFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Owns the queue document, not the live playback session or its UI projection. */
public final class QueuePersistence {
    private final Path file;
    private final Executor ioExecutor;
    private CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);

    public QueuePersistence(Path file, Executor ioExecutor) {
        this.file = Objects.requireNonNull(file, "file");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    /**
     * A detached, immutable persistence value. Capture the arguments together under
     * the playback state owner's synchronization; this class never reads a backend.
     * Track is mutable, so neither the supplied instances nor accessor results are
     * retained as externally reachable state. Transient provider resolutions are
     * deliberately excluded, even from the in-memory snapshot.
     */
    public static final class Snapshot {
        private final List<Track> tracks;
        private final int playIndex;
        private final long positionMs;
        private final int playMode;

        public Snapshot(List<Track> tracks, int playIndex, long positionMs, int playMode) {
            this.tracks = new ArrayList<>(tracks.size());
            for (Track track : tracks) {
                Track copy = copyPersistent(track);
                copy.mediaId = copy.canonicalId();
                this.tracks.add(copy);
            }
            this.playIndex = playIndex;
            this.positionMs = Math.max(0L, positionMs);
            this.playMode = playMode;
        }

        /** Fresh descriptors: a consumer may safely install/mutate its own queue. */
        public List<Track> tracks() {
            List<Track> result = new ArrayList<>(tracks.size());
            for (Track track : tracks) result.add(copyPersistent(track));
            return result;
        }

        public int playIndex() { return playIndex; }
        public long positionMs() { return positionMs; }
        public int playMode() { return playMode; }
    }

    /**
     * Read on the caller's IO thread. Missing/empty queues restore no selection.
     * Invalid documents fail without partially changing any live playback state.
     */
    public Snapshot load() throws IOException {
        if (!Files.exists(file)) return new Snapshot(Collections.emptyList(), -1, 0L, 0);
        JsonObject root = new JsonParser().parse(StorageFiles.readUtf8(file)).getAsJsonObject();
        int savedIndex = integer(root, "playIndex", 0);
        long savedPosition = number(root, "positionMs", 0L);
        int savedMode = integer(root, "playMode", 0);
        JsonArray rows = root.has("tracks") ? root.getAsJsonArray("tracks") : new JsonArray();
        List<Track> loaded = new ArrayList<>(rows.size());
        for (JsonElement row : rows) {
            if (row.isJsonObject()) loaded.add(readTrack(row.getAsJsonObject()));
        }
        if (loaded.isEmpty()) return new Snapshot(Collections.emptyList(), -1, 0L, 0);
        return new Snapshot(loaded, Math.max(0, Math.min(savedIndex, loaded.size() - 1)),
                savedPosition, Math.max(0, Math.min(2, savedMode)));
    }

    /**
     * Accept snapshots in invocation order, including when the supplied executor
     * is a pool. Each write atomically replaces the complete document. A failed
     * write is reported to its caller but does not poison subsequent saves.
     * Keep the executor alive until the last returned future completes. Cancelling
     * that future does not cancel a write already accepted by this owner.
     */
    public synchronized CompletableFuture<Void> save(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        pending = pending.handle((unused, failure) -> null).thenRunAsync(() -> {
            try {
                StorageFiles.writeUtf8Atomic(file, toJson(snapshot));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, ioExecutor);
        return pending.thenApply(unused -> null);
    }

    private static String toJson(Snapshot snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 2);
        root.addProperty("playIndex", snapshot.playIndex);
        root.addProperty("positionMs", snapshot.positionMs);
        root.addProperty("playMode", snapshot.playMode);
        JsonArray rows = new JsonArray();
        for (Track track : snapshot.tracks) {
            JsonObject row = new JsonObject();
            row.addProperty("source", track.source.name());
            if (track.mediaId != null && !track.mediaId.isEmpty()) row.addProperty("mediaId", track.mediaId);
            if (track.neteaseId != 0) row.addProperty("neteaseId", track.neteaseId);
            optional(row, "customId", track.customId);
            row.addProperty("title", track.title);
            row.addProperty("artist", track.artist);
            if (track.artistId != 0) row.addProperty("artistId", track.artistId);
            if (track.artistMediaId != null && !track.artistMediaId.isEmpty()) {
                row.addProperty("artistMediaId", track.artistMediaId);
            }
            if (track.artistIdsCsv != null && !track.artistIdsCsv.isEmpty()) {
                row.addProperty("artistIdsCsv", track.artistIdsCsv);
                row.addProperty("artistNamesCsv", track.artistNamesCsv);
            }
            row.addProperty("album", track.album);
            row.addProperty("coverUrl", track.coverUrl);
            row.addProperty("durationMs", track.durationMs);
            optional(row, "filePath", track.filePath);
            optional(row, "contentUri", track.contentUri);
            if (track.coverLocalPath != null) {
                row.addProperty("coverLocalPath", track.coverLocalPath);
            } else if (track.coverThumbPath != null && !track.coverThumbPath.startsWith("http")) {
                row.addProperty("coverLocalPath", track.coverThumbPath);
            }
            optional(row, "lyricFilePath", track.lyricFilePath);
            optional(row, "translationFilePath", track.translationFilePath);
            optional(row, "romajiFilePath", track.romajiFilePath);
            rows.add(row);
        }
        root.add("tracks", rows);
        return root.toString();
    }

    private static Track readTrack(JsonObject row) {
        Track track = new Track();
        String source = text(row, "source", "NETEASE");
        boolean plugin = "PLUGIN".equals(source);
        track.source = "LOCAL".equals(source) ? Track.Source.LOCAL
                : "CUSTOM_API".equals(source) ? Track.Source.CUSTOM_API
                : plugin ? Track.Source.PLUGIN : Track.Source.NETEASE;
        track.neteaseId = number(row, "neteaseId", 0L);
        track.customId = text(row, "customId", null);
        String mediaId = text(row, "mediaId", null);
        if (mediaId != null) {
            track.applyCanonicalId(mediaId);
            // An installed provider named netease must not migrate to the retired
            // built-in client merely because applyCanonicalId recognizes its id.
            if (plugin) track.source = Track.Source.PLUGIN;
        } else {
            track.canonicalId();
        }
        track.title = text(row, "title", "");
        track.artist = text(row, "artist", "");
        track.artistId = number(row, "artistId", 0L);
        track.artistMediaId = text(row, "artistMediaId", "");
        track.artistIdsCsv = text(row, "artistIdsCsv", "");
        track.artistNamesCsv = text(row, "artistNamesCsv", "");
        track.album = text(row, "album", "");
        track.coverUrl = text(row, "coverUrl", "");
        track.durationMs = number(row, "durationMs", 0L);
        if (track.source == Track.Source.LOCAL) {
            track.filePath = text(row, "filePath", null);
            track.contentUri = text(row, "contentUri", null);
            track.coverLocalPath = text(row, "coverLocalPath", null);
            track.lyricFilePath = text(row, "lyricFilePath", null);
            track.translationFilePath = text(row, "translationFilePath", null);
            track.romajiFilePath = text(row, "romajiFilePath", null);
            LibraryScanner.discoverSidecars(track);
        } else if (track.source == Track.Source.CUSTOM_API || track.source == Track.Source.PLUGIN) {
            track.coverThumbPath = track.coverUrl;
        } else if (!track.coverUrl.isEmpty()) {
            track.coverThumbPath = NeteaseClient.thumbUrl(track.coverUrl);
        }
        track.canonicalId();
        return track;
    }

    private static Track copyPersistent(Track source) {
        Track copy = new Track();
        copy.source = source.source;
        copy.mediaId = source.mediaId;
        copy.neteaseId = source.neteaseId;
        copy.customId = source.customId;
        copy.title = source.title;
        copy.artist = source.artist;
        copy.artistId = source.artistId;
        copy.artistMediaId = source.artistMediaId;
        copy.artistIdsCsv = source.artistIdsCsv;
        copy.artistNamesCsv = source.artistNamesCsv;
        copy.album = source.album;
        copy.coverUrl = source.coverUrl;
        copy.durationMs = source.durationMs;
        copy.filePath = source.filePath;
        copy.contentUri = source.contentUri;
        copy.coverLocalPath = source.coverLocalPath;
        copy.coverThumbPath = source.coverThumbPath;
        copy.lyricFilePath = source.lyricFilePath;
        copy.translationFilePath = source.translationFilePath;
        copy.romajiFilePath = source.romajiFilePath;
        return copy;
    }

    private static void optional(JsonObject object, String key, String value) {
        if (value != null) object.addProperty(key, value);
    }

    private static String text(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static long number(JsonObject object, String key, long fallback) {
        return object.has(key) ? object.get(key).getAsLong() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }
}
