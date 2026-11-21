package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.cache.DiskCache;
import dev.t1m3.qplayer.model.Track;
import java.io.IOException;
import java.nio.file.Files;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/** Shared artwork identity/cache policy. Disk methods are called only by IO workers. */
final class ArtworkResolver {
    private final DiskCache cache;
    // Only the current local track and its two neighbours are warmed in memory.
    // Queue/library Track objects must not own every cover played this session.
    private final Map<Track, Cover> warmed = new IdentityHashMap<>(3);
    private Track current;
    private Track previous;
    private Track next;

    ArtworkResolver(DiskCache cache) { this.cache = cache; }

    synchronized void setQueueWindow(Track current, Track previous, Track next) {
        this.current = current;
        this.previous = previous;
        this.next = next;
        Iterator<Track> tracks = warmed.keySet().iterator();
        while (tracks.hasNext()) {
            Track track = tracks.next();
            if (track != current && track != previous && track != next) tracks.remove();
        }
    }

    static String url(Track track, String size) {
        if (track == null || track.coverUrl == null || track.coverUrl.isEmpty()) return "";
        String url = track.coverUrl;
        return track.source == Track.Source.NETEASE
                ? url + (url.contains("?") ? "&" : "?") + "param=" + size + "y" + size : url;
    }

    String cachedPath(Track track) {
        if (track.source == Track.Source.LOCAL) {
            String local = track.coverLocalPath != null ? track.coverLocalPath : track.coverThumbPath;
            return local != null && !local.startsWith("http") ? local : null;
        }
        String path = cache.getImage(url(track, "1024"));
        if (path == null) path = cache.getThumb64(url(track, "512"));
        if (path == null) path = cache.getThumb64(url(track, "64"));
        return path;
    }

    Cover cachedCover(Track track) throws IOException {
        synchronized (this) {
            Cover ready = warmed.get(track);
            if (ready != null) return ready;
        }
        String path = track.source == Track.Source.LOCAL
                ? cachedPath(track) : cache.getImage(url(track, "1024"));
        byte[] bytes = track.coverBytes;
        if (bytes == null && path != null && !path.isEmpty()) bytes = Files.readAllBytes(java.nio.file.Paths.get(path));
        Cover cover = new Cover(bytes, path == null ? "" : path);
        if (bytes != null && track.source == Track.Source.LOCAL) {
            synchronized (this) {
                // A late IO completion must not reinsert a track already skipped past.
                if (track == current || track == previous || track == next) warmed.put(track, cover);
            }
        }
        return cover;
    }

    static final class Cover {
        private final byte[] bytes;
        private final String path;
        Cover(byte[] bytes, String path) { this.bytes = bytes; this.path = path; }
        byte[] bytes() { return bytes; }
        String path() { return path; }
    }
}
