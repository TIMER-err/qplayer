package dev.t1m3.qplayer.library;

import com.google.gson.Gson;

import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent cache of scanned LOCAL tracks so a relaunch doesn't re-open every
 * file. The slow part of a scan is the per-file tag/duration/embedded-cover/
 * lyric extraction; enumerating files (MediaStore on Android) is cheap. So we
 * key each entry on {@code filePath + size + mtime} and, on the next scan, reuse
 * the cached metadata for any file that hasn't changed — skipping the expensive
 * read entirely.
 *
 * <p>Embedded covers and embedded lyrics are extracted once and written to files
 * under the cache dir (a cover/lyric path on the {@link Track}, not bytes held in
 * memory for the whole library), so list rows can lazy-load them and large
 * libraries don't OOM.
 */
public final class LibraryCache {

    /** Bump when the extraction logic changes so stale caches (e.g. built before a
     *  cover/lyric fix) are discarded and every file is re-read once. */
    private static final int CACHE_VERSION = 3;

    /** List-row thumbnail edge; comfortably covers the 48px row box at high DPI. */
    private static final int THUMB_EDGE = 256;
    /** Now-playing / lyric-view cover edge; matches the netease player art size (512). */
    private static final int PLAY_EDGE = 512;

    private final Path indexFile;
    private final Path coversDir;
    private final Path lyricsDir;
    private final Gson gson = new Gson();

    public LibraryCache() {
        Path baseDir = Paths.get(AppDirs.base(), "local-cache");
        this.indexFile = baseDir.resolve("library.json");
        this.coversDir = baseDir.resolve("covers");
        this.lyricsDir = baseDir.resolve("lyrics");
    }

    /** On-disk wrapper: a schema version plus the cached entries. */
    private static final class Index {
        int version;
        List<Entry> entries;
    }

    /** Serializable shape of one cached track (no in-memory cover bytes). */
    private static final class Entry {
        String filePath;
        String contentUri;
        String title;
        String artist;
        String album;
        long durationMs;
        String coverThumbPath;
        String coverLocalPath;
        String lyricFilePath;
        String translationFilePath;
        String romajiFilePath;
        long fileSize;
        long fileMtime;
    }

    /** Load the cache keyed by file path. Empty map if absent/corrupt. */
    public Map<String, Track> load() {
        Map<String, Track> out = new HashMap<>();
        if (!Files.exists(indexFile)) return out;
        try {
            String json = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
            Index index = gson.fromJson(json, Index.class);
            if (index == null || index.version != CACHE_VERSION || index.entries == null) {
                Logger.info("LibraryCache: schema {} != {}, discarding cache",
                        index == null ? -1 : index.version, CACHE_VERSION);
                return out;
            }
            for (Entry e : index.entries) {
                if (e == null || e.filePath == null) continue;
                out.put(e.filePath, toTrack(e));
            }
            Logger.info("LibraryCache: loaded {} cached tracks", out.size());
        } catch (Throwable t) {
            Logger.warn("LibraryCache: load failed: {}", t.getMessage());
        }
        return out;
    }

    /** Persist the given tracks as the new cache, pruning orphaned cover/lyric files. */
    public void save(List<Track> tracks) {
        try {
            Files.createDirectories(indexFile.getParent());
            Index index = new Index();
            index.version = CACHE_VERSION;
            index.entries = new ArrayList<>(tracks.size());
            for (Track t : tracks) {
                if (t.source != Track.Source.LOCAL || t.filePath == null) continue;
                index.entries.add(toEntry(t));
            }
            Files.write(indexFile, gson.toJson(index).getBytes(StandardCharsets.UTF_8));
            pruneOrphans(tracks);
        } catch (Throwable t) {
            Logger.warn("LibraryCache: save failed: {}", t.getMessage());
        }
    }

    /** True when {@code cached} still matches the live file's size + mtime. */
    public static boolean isFresh(Track cached, long size, long mtime) {
        return cached != null && cached.fileSize == size && cached.fileMtime == mtime;
    }

    /** Downscale the raw cover once and cache a row thumbnail + a now-playing copy,
     *  setting both paths on the track. Undecodable bytes fall back to storing the
     *  original for both. */
    public void writeCovers(Track t, String filePath, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        byte[][] sized = CoverThumbnailer.downscale(bytes, THUMB_EDGE, PLAY_EDGE);
        byte[] thumb = sized != null ? sized[0] : bytes;
        byte[] play = sized != null ? sized[1] : bytes;
        t.coverThumbPath = writeCoverFile(filePath, thumb, ".thumb.img");
        t.coverLocalPath = writeCoverFile(filePath, play, ".img");
    }

    private String writeCoverFile(String filePath, byte[] bytes, String suffix) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            Files.createDirectories(coversDir);
            Path p = coversDir.resolve(hash(filePath) + suffix);
            Files.write(p, bytes);
            return p.toString();
        } catch (Throwable t) {
            Logger.warn("LibraryCache: cover write failed for {}: {}", filePath, t.getMessage());
            return null;
        }
    }

    /** Write extracted embedded lyric text to the cache and return the file path (or null). */
    public String writeLyric(String filePath, String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            Files.createDirectories(lyricsDir);
            Path p = lyricsDir.resolve(hash(filePath) + ".lrc");
            Files.write(p, text.getBytes(StandardCharsets.UTF_8));
            return p.toString();
        } catch (Throwable t) {
            Logger.warn("LibraryCache: lyric write failed for {}: {}", filePath, t.getMessage());
            return null;
        }
    }

    // ---- internals ----

    private static Track toTrack(Entry e) {
        Track t = new Track();
        t.source = Track.Source.LOCAL;
        t.filePath = e.filePath;
        t.contentUri = e.contentUri;
        t.title = e.title;
        t.artist = e.artist;
        t.album = e.album;
        t.durationMs = e.durationMs;
        t.coverThumbPath = e.coverThumbPath;
        t.coverLocalPath = e.coverLocalPath;
        t.lyricFilePath = e.lyricFilePath;
        t.translationFilePath = e.translationFilePath;
        t.romajiFilePath = e.romajiFilePath;
        t.fileSize = e.fileSize;
        t.fileMtime = e.fileMtime;
        return t;
    }

    private static Entry toEntry(Track t) {
        Entry e = new Entry();
        e.filePath = t.filePath;
        e.contentUri = t.contentUri;
        e.title = t.title;
        e.artist = t.artist;
        e.album = t.album;
        e.durationMs = t.durationMs;
        e.coverThumbPath = t.coverThumbPath;
        e.coverLocalPath = t.coverLocalPath;
        e.lyricFilePath = t.lyricFilePath;
        e.translationFilePath = t.translationFilePath;
        e.romajiFilePath = t.romajiFilePath;
        e.fileSize = t.fileSize;
        e.fileMtime = t.fileMtime;
        return e;
    }

    /** Delete cover/lyric files this cache produced that no live track references. */
    private void pruneOrphans(List<Track> tracks) {
        java.util.Set<String> live = new java.util.HashSet<>();
        for (Track t : tracks) {
            if (t.coverThumbPath != null && t.coverThumbPath.startsWith(coversDir.toString()))
                live.add(t.coverThumbPath);
            if (t.coverLocalPath != null && t.coverLocalPath.startsWith(coversDir.toString()))
                live.add(t.coverLocalPath);
            if (t.lyricFilePath != null && t.lyricFilePath.startsWith(lyricsDir.toString()))
                live.add(t.lyricFilePath);
        }
        pruneDir(coversDir, live);
        pruneDir(lyricsDir, live);
    }

    private static void pruneDir(Path dir, java.util.Set<String> live) {
        File d = dir.toFile();
        File[] files = d.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!live.contains(f.toString())) {
                try { f.delete(); } catch (Throwable ignored) { }
            }
        }
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
