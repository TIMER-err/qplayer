package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/** Bounded, source-qualified MRU using the queue's durable descriptor format. */
public final class PlaybackHistoryStore {
    public static final int MAX_TRACKS = 200;

    private final QueuePersistence persistence;
    private final Executor ioExecutor;
    private final Path resourcesDir;
    /** Last committed rows also recover stale cache paths in a restored live queue. IO-owned. */
    private final Map<String, Track> persistedTracks = new HashMap<>();
    private volatile Runnable onChanged;
    private final List<Track> tracks = new ArrayList<>();
    private CompletableFuture<Void> loading;
    private CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);
    private boolean loaded;

    public PlaybackHistoryStore(Path file, Executor ioExecutor) {
        this.ioExecutor = java.util.Objects.requireNonNull(ioExecutor, "ioExecutor");
        persistence = new QueuePersistence(file, ioExecutor);
        resourcesDir = file.toAbsolutePath().normalize().resolveSibling(
                file.getFileName().toString() + ".resources");
    }

    /** One IO read per session. Starts recorded during the read always win. */
    public synchronized CompletableFuture<Void> load() {
        if (loading != null) return loading.thenApply(unused -> null);
        CompletableFuture<Void> result = new CompletableFuture<>();
        loading = result;
        ioExecutor.execute(() -> {
            try {
                List<Track> restored = persistence.load().tracks();
                for (Track track : restored) persistedTracks.put(track.canonicalId(), track);
                synchronized (this) {
                    boolean changedWhileLoading = !tracks.isEmpty();
                    Set<String> seen = new HashSet<>();
                    for (Track track : tracks) seen.add(track.canonicalId());
                    for (Track track : restored) {
                        if (tracks.size() >= MAX_TRACKS) break;
                        String id = track.canonicalId();
                        if (!id.isEmpty() && seen.add(id)) tracks.add(track);
                    }
                    loaded = true;
                    if (changedWhileLoading) save();
                }
                result.complete(null);
            } catch (Exception error) {
                // Do not overwrite an unreadable document with a partial session.
                result.completeExceptionally(error);
            }
        });
        return result.thenApply(unused -> null);
    }

    /** Notify the controller when IO has replaced library-cache paths with owned paths. */
    void setOnChanged(Runnable listener) {
        onChanged = listener;
    }

    /** Repoint a restored local queue descriptor without touching its playable locator. */
    synchronized boolean restoreResources(Track target) {
        if (target.source != Track.Source.LOCAL) return false;
        for (Track row : tracks) {
            if (!row.canonicalId().equals(target.canonicalId())) continue;
            String cover = ownedOrCurrent(row.coverLocalPath, target.coverLocalPath);
            String thumb = ownedOrCurrent(row.coverThumbPath, target.coverThumbPath);
            String lyric = ownedOrCurrent(row.lyricFilePath, target.lyricFilePath);
            String translation = ownedOrCurrent(row.translationFilePath, target.translationFilePath);
            String romaji = ownedOrCurrent(row.romajiFilePath, target.romajiFilePath);
            boolean changed = !java.util.Objects.equals(cover, target.coverLocalPath)
                    || !java.util.Objects.equals(thumb, target.coverThumbPath)
                    || !java.util.Objects.equals(lyric, target.lyricFilePath)
                    || !java.util.Objects.equals(translation, target.translationFilePath)
                    || !java.util.Objects.equals(romaji, target.romajiFilePath);
            target.coverLocalPath = cover;
            target.coverThumbPath = thumb;
            target.lyricFilePath = lyric;
            target.translationFilePath = translation;
            target.romajiFilePath = romaji;
            return changed;
        }
        return false;
    }

    private String ownedOrCurrent(String retained, String current) {
        if (retained != null && !retained.isEmpty()) {
            try {
                if (Paths.get(retained).toAbsolutePath().normalize().startsWith(resourcesDir))
                    return retained;
            } catch (RuntimeException ignored) {
                // Optional non-file metadata cannot replace a queue's resource.
            }
        }
        return current;
    }

    /** Capture only durable fields; live stream resolutions never enter this owner. */
    public synchronized void record(Track track) {
        Track copy = QueuePersistence.copyPersistent(track);
        String id = copy.canonicalId();
        if (id.isEmpty()) return;
        copy.mediaId = id;
        tracks.removeIf(previous -> id.equals(previous.canonicalId()));
        tracks.add(0, copy);
        if (tracks.size() > MAX_TRACKS) tracks.remove(tracks.size() - 1);
        if (loaded) save();
        else load();
    }

    /** Detached descriptors: callers may install them in a mutable live queue. */
    public synchronized List<Track> tracks() {
        List<Track> result = new ArrayList<>(tracks.size());
        for (Track track : tracks) result.add(QueuePersistence.copyPersistent(track));
        return result;
    }

    /** Shutdown barrier includes the merge/write accepted by an unfinished load. */
    public synchronized CompletableFuture<Void> pendingWrites() {
        return load().handle((unused, error) -> null).thenCompose(unused -> {
            synchronized (this) {
                return pending.thenApply(ignored -> null);
            }
        });
    }

    private void save() {
        // Keep asset copies, document commits and pruning in the same ordered chain.
        // Capture at execution time so queued writes cannot resurrect obsolete rows/assets.
        pending = pending.handle((unused, error) -> null)
                .thenApplyAsync(unused -> preserveResources(), ioExecutor)
                .thenCompose(rows -> persistence.save(
                        new QueuePersistence.Snapshot(rows, -1, 0L, 0))
                        .thenRunAsync(() -> pruneResources(rows), ioExecutor));
        pending.whenComplete((unused, error) -> {
            if (error != null) Logger.warn("savePlaybackHistory failed: {}", error.getMessage());
        });
    }

    private List<Track> preserveResources() {
        List<Track> originals;
        List<Track> rows;
        synchronized (this) {
            originals = new ArrayList<>(tracks);
            rows = tracks();
        }
        Path localCache = AppDirs.localCacheDir().toAbsolutePath().normalize();
        for (Track row : rows) {
            if (row.source != Track.Source.LOCAL) continue;
            Track previous = persistedTracks.get(row.canonicalId());
            row.coverLocalPath = preserveResource(row.coverLocalPath,
                    previous != null ? previous.coverLocalPath : null, localCache);
            row.coverThumbPath = preserveResource(row.coverThumbPath,
                    previous != null ? previous.coverThumbPath : null, localCache);
            row.lyricFilePath = preserveResource(row.lyricFilePath,
                    previous != null ? previous.lyricFilePath : null, localCache);
            row.translationFilePath = preserveResource(row.translationFilePath,
                    previous != null ? previous.translationFilePath : null, localCache);
            row.romajiFilePath = preserveResource(row.romajiFilePath,
                    previous != null ? previous.romajiFilePath : null, localCache);
        }
        synchronized (this) {
            for (int i = 0; i < originals.size(); i++) {
                // A start during IO owns its own descriptor; never overwrite it.
                int retained = tracks.indexOf(originals.get(i));
                if (retained >= 0) tracks.set(retained, rows.get(i));
            }
        }
        Runnable listener = onChanged;
        if (listener != null) listener.run();
        return rows;
    }

    private String preserveResource(String value, String previous, Path localCache) {
        String fallback = previous != null && Paths.get(previous).toAbsolutePath().normalize()
                .startsWith(resourcesDir) && Files.isRegularFile(Paths.get(previous)) ? previous : value;
        if (value == null || value.isEmpty()) return fallback;
        try {
            Path source = Paths.get(value).toAbsolutePath().normalize();
            if (source.startsWith(resourcesDir) || !source.startsWith(localCache)) return value;
            if (!Files.isRegularFile(source)) {
                return fallback;
            }
            String version = source + "\n" + Files.size(source) + "\n"
                    + Files.getLastModifiedTime(source).toMillis();
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(version.getBytes(StandardCharsets.UTF_8));
            char[] hash = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                hash[i * 2] = "0123456789abcdef".charAt((digest[i] & 0xff) >>> 4);
                hash[i * 2 + 1] = "0123456789abcdef".charAt(digest[i] & 0xf);
            }
            Path target = resourcesDir.resolve(new String(hash) + "-" + source.getFileName());
            if (!Files.isRegularFile(target)) {
                Files.createDirectories(resourcesDir);
                Path staging = StorageFiles.pendingPath(target);
                try {
                    Files.copy(source, staging, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    StorageFiles.replace(staging, target);
                } finally {
                    Files.deleteIfExists(staging);
                }
            }
            return target.toString();
        } catch (IOException | java.security.NoSuchAlgorithmException | RuntimeException error) {
            throw new java.util.concurrent.CompletionException(error);
        }
    }

    private void pruneResources(List<Track> committed) {
        persistedTracks.clear();
        for (Track track : committed) persistedTracks.put(track.canonicalId(), track);
        Set<Path> retained = new HashSet<>();
        for (Track track : committed) collectResources(track, retained);
        synchronized (this) {
            // Preserve the last committed document as well as starts awaiting their write.
            for (Track track : tracks) collectResources(track, retained);
        }
        if (!Files.isDirectory(resourcesDir)) return;
        try (Stream<Path> files = Files.list(resourcesDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (Files.isRegularFile(file) && !retained.contains(file)) Files.deleteIfExists(file);
            }
        } catch (IOException error) {
            Logger.warn("prunePlaybackHistory resources failed: {}", error.getMessage());
        }
    }

    private void collectResources(Track track, Set<Path> retained) {
        for (String value : new String[] {track.coverLocalPath, track.coverThumbPath,
                track.lyricFilePath, track.translationFilePath, track.romajiFilePath}) {
            if (value == null || value.isEmpty()) continue;
            try {
                Path path = Paths.get(value).toAbsolutePath().normalize();
                if (path.startsWith(resourcesDir)) retained.add(path);
            } catch (RuntimeException ignored) {
                // Non-file optional values are not history-owned resources.
            }
        }
    }
}
