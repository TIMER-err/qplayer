package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.cache.DiskCache;
import dev.t1m3.qplayer.model.Track;
import java.io.IOException;
import java.nio.file.Files;

/** Shared artwork identity/cache policy. Disk methods are called only by IO workers. */
final class ArtworkResolver {
    private final DiskCache cache;

    ArtworkResolver(DiskCache cache) { this.cache = cache; }

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
        String path = track.source == Track.Source.LOCAL
                ? cachedPath(track) : cache.getImage(url(track, "1024"));
        byte[] bytes = track.coverBytes;
        if (bytes == null && path != null && !path.isEmpty()) bytes = Files.readAllBytes(java.nio.file.Paths.get(path));
        return new Cover(bytes, path == null ? "" : path);
    }

    static final class Cover {
        private final byte[] bytes;
        private final String path;
        Cover(byte[] bytes, String path) { this.bytes = bytes; this.path = path; }
        byte[] bytes() { return bytes; }
        String path() { return path; }
    }
}
