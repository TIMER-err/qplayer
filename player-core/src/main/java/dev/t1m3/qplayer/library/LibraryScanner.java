package dev.t1m3.qplayer.library;

import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Recursively scans a folder for supported audio files and builds {@link Track}
 * descriptors. Tag/duration/cover reading is delegated to a platform
 * {@link MetadataReader}; sidecar / embedded cover + lyric discovery and the
 * filename fallback are platform-neutral (java.nio only), so this dexes for
 * Android. Results are cached by {@link LibraryCache} so an unchanged file skips
 * the expensive re-read on the next scan.
 */
public final class LibraryScanner {

    private static final String[] SUPPORTED_EXTS = {".wav", ".mp3", ".ogg", ".flac"};

    private final MetadataReader reader;
    private final LibraryCache cache = new LibraryCache();

    public LibraryScanner(MetadataReader reader) {
        this.reader = reader;
    }

    /** Synchronous recursive scan. Callers thread it; order is filesystem walk order. */
    public List<Track> scan(String folder) {
        List<Track> out = new ArrayList<>();
        Path root = Paths.get(folder);
        if (!Files.isDirectory(root)) {
            Logger.warn("LibraryScanner: folder not found: {}", folder);
            return out;
        }
        Map<String, Track> cached = cache.load();
        int[] reused = {0};
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(LibraryScanner::isAudio)
                    .forEach(p -> {
                        Track t = resolve(p, cached, reused);
                        if (t != null) out.add(t);
                    });
        } catch (IOException e) {
            Logger.exception(e);
        }
        cache.save(out);
        Logger.info("LibraryScanner: scan done, {} tracks ({} reused from cache)", out.size(), reused[0]);
        return out;
    }

    /** Cache hit (unchanged file) reuses metadata; otherwise a full build. */
    private Track resolve(Path audio, Map<String, Track> cached, int[] reused) {
        String filePath = audio.toString();
        long size;
        long mtime;
        try {
            size = Files.size(audio);
            mtime = Files.getLastModifiedTime(audio).toMillis();
        } catch (IOException e) {
            size = 0;
            mtime = 0;
        }
        Track prev = cached.get(filePath);
        if (LibraryCache.isFresh(prev, size, mtime)) {
            reused[0]++;
            return prev;
        }
        return buildTrack(audio, size, mtime);
    }

    private Track buildTrack(Path audio, long size, long mtime) {
        Track t = new Track();
        t.source = Track.Source.LOCAL;
        t.filePath = audio.toString();
        t.fileSize = size;
        t.fileMtime = mtime;
        String fileName = audio.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot >= 0 ? fileName.substring(0, dot) : fileName;

        // Filename defaults — the MetadataReader overwrites what it can read.
        t.title = base;
        t.artist = "";
        t.album = "";
        t.durationMs = 0L;

        try {
            reader.read(t);
        } catch (Throwable e) {
            Logger.warn("LibraryScanner: tag read failed for {}: {}", audio, e.getMessage());
        }

        // Embedded cover -> downscaled cache files (paths on the Track, not bytes held
        // in memory).
        if (t.coverBytes != null) {
            cache.writeCovers(t, t.filePath, t.coverBytes);
            t.coverBytes = null;
        }

        Path parent = audio.getParent();
        if (t.coverThumbPath == null) {
            byte[] sidecar = readSidecarCover(parent, base);
            if (sidecar != null) cache.writeCovers(t, t.filePath, sidecar);
        }

        // Highest fidelity first: TTML/LYS/QRC/YRC are syllable-level,
        // ESLrc is per-syllable LRC, plain LRC is line-only.
        t.lyricFilePath = pickSidecar(parent, base,
                ".ttml", ".lys", ".qrc", ".yrc", ".eslrc", ".lrc");
        t.translationFilePath = pickSidecar(parent, base, ".tlrc", ".translation.lrc");
        t.romajiFilePath = pickSidecar(parent, base, ".romaji.lrc", ".romaji.lys");

        // No sidecar lyric -> try the file's embedded lyrics, extracted once to a cache .lrc.
        if (t.lyricFilePath == null) {
            String embedded = extractEmbeddedLyric(audio);
            if (embedded != null) t.lyricFilePath = cache.writeLyric(t.filePath, embedded);
        }
        return t;
    }

    private static String extractEmbeddedLyric(Path audio) {
        try (InputStream in = Files.newInputStream(audio)) {
            return EmbeddedLyrics.extract(in);
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean isAudio(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : SUPPORTED_EXTS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    private static byte[] readSidecarCover(Path dir, String baseName) {
        if (dir == null) return null;
        String[] candidates = {
                baseName + ".jpg", baseName + ".jpeg", baseName + ".png",
                "cover.jpg", "cover.jpeg", "cover.png",
                "folder.jpg", "folder.jpeg", "folder.png",
                "album.jpg", "album.jpeg", "album.png",
        };
        for (String name : candidates) {
            Path p = dir.resolve(name);
            if (Files.exists(p)) {
                try {
                    return Files.readAllBytes(p);
                } catch (IOException ignored) {
                    // unreadable sidecar — fall through to the next candidate
                }
            }
        }
        return null;
    }

    private static String pickSidecar(Path dir, String base, String... exts) {
        if (dir == null) return null;
        for (String ext : exts) {
            Path candidate = dir.resolve(base + ext);
            if (Files.exists(candidate)) return candidate.toString();
        }
        return null;
    }

    public static String[] supportedExtensions() {
        return SUPPORTED_EXTS.clone();
    }
}
