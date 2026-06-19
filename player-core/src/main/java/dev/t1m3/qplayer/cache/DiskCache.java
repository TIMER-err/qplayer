package dev.t1m3.qplayer.cache;

import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

/**
 * Unified disk cache for audio files, lyrics and cover images.
 * <p>
 * Three sub-directories under {@code AppDirs.base()/cache/}:
 * {@code audio/}, {@code lyric/}, {@code image/}.
 * <p>
 * LRU eviction by last-modified time: after every write the total size is
 * checked against {@link #maxSizeBytes} and oldest files are deleted until
 * the limit is satisfied.  Callers should use the typed helper methods
 * ({@link #cacheAudio}, {@link #cacheLyric}, {@link #cacheImage}) which
 * touch the file on read (via {@link #getAudio}, etc.) so that actively-used
 * entries survive eviction.
 */
public final class DiskCache {

    private static final String BASE_DIR = AppDirs.base() + "/cache";

    /** Sub-directory names. */
    public static final String AUDIO  = "audio";
    public static final String LYRIC  = "lyric";
    public static final String IMAGE  = "image";

    private volatile long maxSizeBytes;

    public DiskCache(long maxSizeMB) {
        this.maxSizeBytes = maxSizeMB * 1024L * 1024L;
    }

    public void setMaxSizeMB(long mb) {
        this.maxSizeBytes = mb * 1024L * 1024L;
        evictIfNeeded();
    }

    public long getMaxSizeMB() {
        return maxSizeBytes / (1024L * 1024L);
    }

    // ---- path helpers ----------------------------------------------------

    /** Resolve cache file for an audio track keyed by netease song id. */
    public static String audioPath(long neteaseId) {
        if (neteaseId <= 0) return null;
        return BASE_DIR + "/" + AUDIO + "/" + neteaseId + ".cache";
    }

    /** Resolve cache file for lyrics keyed by song id. */
    public static String lyricPath(long songId) {
        if (songId <= 0) return null;
        return BASE_DIR + "/" + LYRIC + "/" + songId + ".ttml";
    }

    /** Resolve cache file for a cover image keyed by url hash. */
    public static String imagePath(String url) {
        if (url == null || url.isEmpty()) return null;
        return BASE_DIR + "/" + IMAGE + "/" + Math.abs(url.hashCode()) + ".img";
    }

    // ---- existence check -------------------------------------------------

    public boolean hasAudio(long neteaseId) {
        String p = audioPath(neteaseId);
        return p != null && new File(p).exists();
    }

    public boolean hasLyric(long songId) {
        String p = lyricPath(songId);
        return p != null && new File(p).exists();
    }

    public boolean hasImage(String url) {
        String p = imagePath(url);
        return p != null && new File(p).exists();
    }

    // ---- read (touches lastModified for LRU) ------------------------------

    /**
     * Return the cached audio file path, touching its timestamp so it
     * survives LRU eviction. Returns null if not cached.
     */
    public String getAudio(long neteaseId) {
        String p = audioPath(neteaseId);
        return touch(p);
    }

    /** Return the cached lyric file path, or null. */
    public String getLyric(long songId) {
        String p = lyricPath(songId);
        return touch(p);
    }

    /** Return the cached image file path, or null. */
    public String getImage(String url) {
        String p = imagePath(url);
        return touch(p);
    }

    // ---- write (download to cache) ---------------------------------------

    /**
     * Download an HTTP URL straight to the audio cache file.
     * Non-fatal: logs and cleans up on error.
     */
    public void cacheAudio(String url, long neteaseId) {
        String path = audioPath(neteaseId);
        downloadToFile(url, path);
    }

    /** Write raw bytes to the lyric cache file. */
    public void cacheLyric(byte[] data, long songId) {
        String path = lyricPath(songId);
        writeBytes(data, path);
    }

    /** Download an HTTP URL to the image cache file. */
    public void cacheImage(String url) {
        String path = imagePath(url);
        downloadToFile(url, path);
    }

    // ---- size & cleanup ---------------------------------------------------

    /** Total bytes used by all three cache sub-directories. */
    public long totalSize() {
        long total = 0;
        for (String sub : new String[]{AUDIO, LYRIC, IMAGE}) {
            total += dirSize(new File(BASE_DIR, sub));
        }
        return total;
    }

    /** Delete all cached files. */
    public void clearAll() {
        for (String sub : new String[]{AUDIO, LYRIC, IMAGE}) {
            deleteRecursive(new File(BASE_DIR, sub));
        }
    }

    /** Delete all cached files of one type. */
    public void clearType(String type) {
        deleteRecursive(new File(BASE_DIR, type));
    }

    // ---- internals --------------------------------------------------------

    private String touch(String path) {
        if (path == null) return null;
        File f = new File(path);
        if (!f.exists()) return null;
        f.setLastModified(System.currentTimeMillis());
        return path;
    }

    private void writeBytes(byte[] data, String path) {
        if (data == null || path == null) return;
        ensureParent(path);
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(data);
            Logger.info("disk cache written: {} ({} B)", fileName(path), data.length);
        } catch (Throwable e) {
            Logger.warn("disk cache write failed: {}", e.getMessage());
            cleanPartial(path);
        }
        evictIfNeeded();
    }

    private void downloadToFile(String url, String path) {
        if (url == null || path == null) return;
        ensureParent(path);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "qplayer/1.0");
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(path)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            long size = new File(path).length();
            Logger.info("disk cache downloaded: {} ({} B)", fileName(path), size);
        } catch (Throwable e) {
            Logger.warn("disk cache download failed: {}", e.getMessage());
            cleanPartial(path);
        } finally {
            if (c != null) c.disconnect();
        }
        evictIfNeeded();
    }

    /**
     * If total cache size exceeds {@link #maxSizeBytes}, delete the
     * least-recently-used files (oldest lastModified) until under limit.
     */
    private void evictIfNeeded() {
        long limit = maxSizeBytes;
        if (limit <= 0) return; // 0 = unlimited
        long total = totalSize();
        if (total <= limit) return;

        // Collect all cache files across all sub-dirs.
        File[] dirs = {new File(BASE_DIR, AUDIO), new File(BASE_DIR, LYRIC), new File(BASE_DIR, IMAGE)};
        java.util.List<File> files = new java.util.ArrayList<>();
        for (File dir : dirs) {
            if (dir.isDirectory()) {
                File[] children = dir.listFiles();
                if (children != null) files.addAll(Arrays.asList(children));
            }
        }
        // Sort by lastModified ascending (oldest first).
        files.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        for (File f : files) {
            if (total <= limit) break;
            long sz = f.length();
            if (f.delete()) {
                total -= sz;
                Logger.info("disk cache evicted: {}", f.getName());
            }
        }
    }

    private static void ensureParent(String path) {
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
    }

    private static void cleanPartial(String path) {
        File f = new File(path);
        if (f.exists()) f.delete();
    }

    private static long dirSize(File dir) {
        if (!dir.isDirectory()) return 0;
        long total = 0;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) total += f.length();
        }
        return total;
    }

    private static void deleteRecursive(File dir) {
        if (!dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) f.delete();
        }
        dir.delete();
    }

    private static String fileName(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
