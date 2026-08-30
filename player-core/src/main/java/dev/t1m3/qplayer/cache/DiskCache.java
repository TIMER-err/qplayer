package dev.t1m3.qplayer.cache;

import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.nio.charset.StandardCharsets;

/**
 * Unified disk cache for audio files, lyrics and cover images.
 * <p>
 * Four sub-directories under {@code AppDirs.cacheBase()/cache/}:
 * {@code audio/}, {@code lyric/}, {@code image/}, {@code thumb64/}.
 * <p>
 * LRU eviction by last-modified time: after every write the total size is
 * checked against {@link #maxSizeBytes} and oldest files are deleted until
 * the limit is satisfied.  Callers should use the typed helper methods
 * ({@link #cacheAudio}, {@link #cacheLyric}, {@link #cacheImage}) which
 * touch the file on read (via {@link #getAudio}, etc.) so that actively-used
 * entries survive eviction.
 */
public final class DiskCache {

    /** Not final: {@link #setBaseDir} lets the desktop settings page repoint the
     *  cache root at runtime, which a compile-time constant couldn't support. */
    private volatile String baseDir = AppDirs.cacheDir().toString();

    /** Sub-directory names. */
    public static final String AUDIO   = "audio";
    public static final String LYRIC   = "lyric";
    public static final String IMAGE   = "image";
    /** Offline-playlist-browsing thumbnails (64x64), kept separate from the
     *  general {@link #IMAGE} cache: capped by file *count*
     *  ({@link #THUMB64_MAX_COUNT}), not the byte-size budget the other three
     *  sub-caches share, since a meaningful byte budget for images this tiny
     *  would be a near-unlimited file count anyway. */
    public static final String THUMB64 = "thumb64";

    /** Oldest files (by lastModified) are deleted once the count exceeds this,
     *  every time a new one is cached — see {@link #cacheThumb64}. */
    private static final int THUMB64_MAX_COUNT = 128;

    private volatile long maxSizeBytes;

    /** Netease song ids the user explicitly asked to cache (song menu's
     *  "缓存此歌曲", or {@code cacheSong}) — as opposed to a track that just
     *  got auto-cached from being played/skipped past. Consulted by
     *  {@link #evictIfNeeded()}: an actively-cached audio file is never
     *  evicted for space, and eviction among the rest prefers the NEWEST
     *  auto-cached file first (a burst of skip-through auto-caches is low
     *  value; older auto-cached songs more likely got actually listened to).
     *  Persisted as one id per line in {@code <baseDir>/actively-cached.txt}
     *  — colocated with the cache it protects (not a fixed AppDirs path) so
     *  it follows the desktop "custom cache location" setting's repointing. */
    private final Set<String> activelyCachedIds = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean activelyCachedDirty = false;

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

    /** Repoint the cache root (e.g. the desktop "custom cache location" setting).
     *  Does not move existing files — the caller decides whether to migrate or
     *  just let the old location go stale. */
    public void setBaseDir(String dir) {
        if (dir == null || dir.trim().isEmpty()) return;
        this.baseDir = Paths.get(dir, "cache").toString();
    }

    public String baseDir() {
        return baseDir;
    }

    // ---- path helpers ----------------------------------------------------

    /** Resolve cache file for an audio track keyed by netease song id. */
    public String audioPath(long neteaseId) {
        if (neteaseId <= 0) return null;
        return baseDir + "/" + AUDIO + "/" + neteaseId + ".cache";
    }

    /** Provider-neutral audio path. Canonical ids are hashed so percent-encoded
     * native ids can never become filesystem syntax and filenames stay bounded. */
    public String audioPath(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return null;
        return baseDir + "/" + AUDIO + "/" + audioKey(mediaId) + ".cache";
    }

    /** Resolve cache file for AMLL TTML lyrics keyed by song id. */
    public String lyricPath(long songId) {
        if (songId <= 0) return null;
        return baseDir + "/" + LYRIC + "/" + songId + ".ttml";
    }

    /** Resolve cache file for Netease's own lyric payload (serialized YRC/LRC). */
    public String neteaseLyricPath(long songId) {
        if (songId <= 0) return null;
        return baseDir + "/" + LYRIC + "/" + songId + ".nlrc";
    }

    /** Resolve cache file for a cover image keyed by url hash. */
    public String imagePath(String url) {
        if (url == null || url.isEmpty()) return null;
        return baseDir + "/" + IMAGE + "/" + Math.abs(url.hashCode()) + ".img";
    }

    /** Resolve cache file for a 64x64 offline-playlist thumbnail, keyed by
     *  url hash (the url is expected to already carry its own size param,
     *  e.g. {@code ?param=64y64} — same convention as {@link #imagePath}). */
    public String thumb64Path(String url) {
        if (url == null || url.isEmpty()) return null;
        return baseDir + "/" + THUMB64 + "/" + Math.abs(url.hashCode()) + ".img";
    }

    // ---- existence check -------------------------------------------------

    public boolean hasAudio(long neteaseId) {
        String p = audioPath(neteaseId);
        return p != null && new File(p).exists();
    }

    public boolean hasAudio(String mediaId) { return getAudioWithoutTouch(mediaId) != null; }

    /** Delete a single cached audio file (cached-songs list right-click menu). */
    public boolean deleteAudio(long neteaseId) {
        String p = audioPath(neteaseId);
        if (p == null) return false;
        File f = new File(p);
        boolean deleted = f.exists() && f.delete();
        if (deleted) unmarkActivelyCached(neteaseId);
        return deleted;
    }

    public boolean deleteAudio(String mediaId) {
        String path = getAudioWithoutTouch(mediaId);
        if (path == null) return false;
        boolean deleted = new File(path).delete();
        if (deleted) unmarkActivelyCachedKey(fileStem(path));
        return deleted;
    }

    // ---- actively-cached marker (see the field's own doc comment) --------

    private String activelyCachedPath() {
        return baseDir + "/actively-cached.txt";
    }

    /** Load the actively-cached id set from disk. Call once at startup (mirrors
     *  {@code SongMetaIndex.load()}/{@code PlaylistCacheIndex.load()}) — cheap
     *  no-op if the marker file doesn't exist yet. */
    public void loadActivelyCached() {
        try {
            Path p = Paths.get(activelyCachedPath());
            if (!Files.isRegularFile(p)) return;
            String text = StorageFiles.readUtf8(p);
            Set<String> ids = new HashSet<>();
            for (String line : text.split("\\R")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.matches("[A-Za-z0-9._-]{1,128}")) ids.add(line);
            }
            activelyCachedIds.addAll(ids);
        } catch (Throwable e) {
            Logger.warn("DiskCache: loading actively-cached marker failed: {}", e.getMessage());
        }
    }

    public boolean isActivelyCached(long neteaseId) {
        return activelyCachedIds.contains(Long.toString(neteaseId));
    }


    public boolean isActivelyCached(String mediaId) {
        return activelyCachedIds.contains(audioKey(mediaId));
    }

    /** Mark a song as user-actively-cached (protects it from {@link #evictIfNeeded}).
     *  Call only after {@link #cacheAudio} actually succeeded — marking a song whose
     *  download failed would protect a file that doesn't exist. */
    public void markActivelyCached(long neteaseId) {
        if (neteaseId == 0 || !activelyCachedIds.add(Long.toString(neteaseId))) return;
        activelyCachedDirty = true;
        saveActivelyCached();
    }

    public void markActivelyCached(String mediaId) {
        if (mediaId == null || mediaId.isEmpty() || !activelyCachedIds.add(audioKey(mediaId))) return;
        activelyCachedDirty = true;
        saveActivelyCached();
    }

    private void unmarkActivelyCached(long neteaseId) {
        unmarkActivelyCachedKey(Long.toString(neteaseId));
    }

    private void unmarkActivelyCachedKey(String key) {
        if (activelyCachedIds.remove(key)) {
            activelyCachedDirty = true;
            saveActivelyCached();
        }
    }

    private void saveActivelyCached() {
        if (!activelyCachedDirty) return;
        try {
            StringBuilder sb = new StringBuilder();
            synchronized (activelyCachedIds) {
                for (String id : activelyCachedIds) sb.append(id).append('\n');
            }
            StorageFiles.writeUtf8Atomic(Paths.get(activelyCachedPath()), sb.toString());
            activelyCachedDirty = false;
        } catch (Throwable e) {
            Logger.warn("DiskCache: saving actively-cached marker failed: {}", e.getMessage());
        }
    }

    public boolean hasLyric(long songId) {
        String p = lyricPath(songId);
        return p != null && new File(p).exists();
    }

    public boolean hasImage(String url) {
        String p = imagePath(url);
        return p != null && new File(p).exists();
    }

    public boolean hasThumb64(String url) {
        String p = thumb64Path(url);
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

    public String getAudio(String mediaId) {
        return touch(getAudioWithoutTouch(mediaId));
    }

    /** Return the cached AMLL TTML lyric file path, or null. */
    public String getLyric(long songId) {
        String p = lyricPath(songId);
        return touch(p);
    }

    /** Return the cached Netease lyric payload file path, or null. */
    public String getNeteaseLyric(long songId) {
        String p = neteaseLyricPath(songId);
        return touch(p);
    }

    /** Return the cached image file path, or null. */
    public String getImage(String url) {
        String p = imagePath(url);
        return touch(p);
    }

    /** Return the cached 64x64 thumbnail file path, or null. */
    public String getThumb64(String url) {
        String p = thumb64Path(url);
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

    public void cacheAudio(String url, String mediaId) {
        downloadToFile(url, audioPath(mediaId));
    }

    /** Called after a policy-aware host download atomically populated audioPath. */
    public void finishExternalWrite() { evictIfNeeded(); }

    /** Write raw bytes to the AMLL TTML lyric cache file. */
    public void cacheLyric(byte[] data, long songId) {
        String path = lyricPath(songId);
        writeBytes(data, path);
    }

    /** Write the serialized Netease lyric payload to its cache file. */
    public void cacheNeteaseLyric(byte[] data, long songId) {
        String path = neteaseLyricPath(songId);
        writeBytes(data, path);
    }

    /** Download an HTTP URL to the image cache file. */
    public void cacheImage(String url) {
        String path = imagePath(url);
        downloadToFile(url, path);
    }

    /** Download an HTTP URL to the 64x64 thumbnail cache file, then evict the
     *  oldest thumbnails (by lastModified) past {@link #THUMB64_MAX_COUNT} —
     *  a file *count* cap, independent of the byte-size budget the other
     *  three sub-caches share via {@link #evictIfNeeded}. */
    public void cacheThumb64(String url) {
        String path = thumb64Path(url);
        downloadToFile(url, path, true);
        evictThumb64IfOverCount();
    }

    // ---- size & cleanup ---------------------------------------------------

    /** Total bytes used by all four cache sub-directories. */
    public long totalSize() {
        long total = 0;
        for (String sub : new String[]{AUDIO, LYRIC, IMAGE, THUMB64}) {
            total += dirSize(new File(baseDir, sub));
        }
        return total;
    }

    /** Delete all cached files. */
    public void clearAll() {
        for (String sub : new String[]{AUDIO, LYRIC, IMAGE, THUMB64}) {
            deleteRecursive(new File(baseDir, sub));
        }
    }

    /** Delete all cached files of one type. */
    public void clearType(String type) {
        deleteRecursive(new File(baseDir, type));
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
        try {
            StorageFiles.writeBytesAtomic(Paths.get(path), data);
            Logger.info("disk cache written: {} ({} B)", fileName(path), data.length);
        } catch (Throwable e) {
            Logger.warn("disk cache write failed: {}", e.getMessage());
        }
        evictIfNeeded();
    }

    private void downloadToFile(String url, String path) {
        downloadToFile(url, path, false);
    }

    /** {@code quiet} suppresses the per-file success line: warming a playlist's
     *  thumbnails queues one download per track, which drowned the log (a few
     *  hundred lines per playlist opened). Failures are still logged. */
    private void downloadToFile(String url, String path, boolean quiet) {
        if (url == null || path == null) return;
        ensureParent(path);
        HttpURLConnection c = null;
        Path target = Paths.get(path);
        Path pending = StorageFiles.pendingPath(target);
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "qplayer/1.0");
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(pending.toFile())) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            StorageFiles.replace(pending, target);
            if (!quiet) {
                Logger.info("disk cache downloaded: {} ({} B)", fileName(path), new File(path).length());
            }
        } catch (Throwable e) {
            Logger.warn("disk cache download failed: {}", e.getMessage());
            try { Files.deleteIfExists(pending); } catch (Throwable ignored) {}
        } finally {
            if (c != null) c.disconnect();
        }
        evictIfNeeded();
    }

    /**
     * If total cache size exceeds {@link #maxSizeBytes}, delete files until
     * under limit. Lyric/image files: oldest (lastModified) first, as
     * before. Audio files: an actively-cached one (see {@link
     * #activelyCachedIds}) is never a candidate at all; among the rest,
     * NEWEST first -- a burst of skip-through auto-caches is low value, and
     * an older auto-cached song more likely got actually listened to.
     */
    private void evictIfNeeded() {
        long limit = maxSizeBytes;
        if (limit <= 0) return; // 0 = unlimited
        long total = totalSize();
        if (total <= limit) return;

        File audioDir = new File(baseDir, AUDIO);
        File[] dirs = {audioDir, new File(baseDir, LYRIC), new File(baseDir, IMAGE)};
        java.util.List<File> files = new java.util.ArrayList<>();
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File f : children) {
                if (dir == audioDir && activelyCachedIds.contains(fileStem(f.getPath()))) continue; // protected
                files.add(f);
            }
        }
        // Ascending by eviction priority: an unprotected audio file's key is negated
        // (newest -> smallest -> evicted first); lyric/image keep oldest-first.
        files.sort((a, b) -> Long.compare(evictionKey(a, audioDir), evictionKey(b, audioDir)));

        for (File f : files) {
            if (total <= limit) break;
            long sz = f.length();
            if (f.delete()) {
                total -= sz;
                Logger.info("disk cache evicted: {}", f.getName());
            }
        }
    }

    private static long evictionKey(File f, File audioDir) {
        long lm = f.lastModified();
        return f.getParentFile() != null && f.getParentFile().equals(audioDir) ? -lm : lm;
    }

    /** Parses the netease id back out of an audio cache filename ("{id}.cache"), or
     *  0 if it doesn't look like one (defensively -- a stray non-cache file should
     *  never match a real id and get treated as protected). */
    private String getAudioWithoutTouch(String mediaId) {
        String generic = audioPath(mediaId);
        if (generic != null && new File(generic).isFile()) return generic;
        // Delayed migration for the old numeric NetEase filename. Never remove it
        // until the canonical target was moved successfully.
        try {
            dev.t1m3.qplayer.media.MediaId id = dev.t1m3.qplayer.media.MediaId.parse(mediaId)
                    .requireKind(dev.t1m3.qplayer.media.MediaKind.SONG);
            if ("netease".equals(id.provider()) && id.nativeId().matches("[1-9][0-9]*")) {
                String legacy = audioPath(Long.parseLong(id.nativeId()));
                if (legacy != null && new File(legacy).isFile()) {
                    Path source = Paths.get(legacy);
                    Path target = Paths.get(generic);
                    try {
                        Files.createDirectories(target.getParent());
                        Files.move(source, target);
                        if (activelyCachedIds.remove(id.nativeId())) {
                            activelyCachedIds.add(audioKey(mediaId));
                            activelyCachedDirty = true;
                            saveActivelyCached();
                        }
                        return generic;
                    } catch (IOException ignored) {
                        return legacy;
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private static String audioKey(String mediaId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(mediaId.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("v2-");
            for (byte value : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    private static String fileStem(String path) {
        String name = new File(path).getName();
        return name.endsWith(".cache") ? name.substring(0, name.length() - 6) : name;
    }

    /** Count (not byte-size) cap on {@link #THUMB64}: delete the oldest files
     *  once there are more than {@link #THUMB64_MAX_COUNT}. */
    private void evictThumb64IfOverCount() {
        File dir = new File(baseDir, THUMB64);
        File[] files = dir.listFiles();
        if (files == null || files.length <= THUMB64_MAX_COUNT) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int overBy = files.length - THUMB64_MAX_COUNT;
        // Unlogged: this runs after every warmed thumbnail, so once the cache is at
        // its cap it fires on each one -- a line here is pure noise, not a signal.
        for (int i = 0; i < overBy; i++) {
            files[i].delete();
        }
    }

    private static void ensureParent(String path) {
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
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
