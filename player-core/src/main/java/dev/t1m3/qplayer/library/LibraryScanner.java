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

    private static final String[] SUPPORTED_EXTS = {".wav", ".mp3", ".ogg", ".flac", ".m4a", ".mp4"};

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
            LibraryCache.Resources resources = cache.resources(filePath);
            if (resources == null
                    || (resources.embeddedCover && (!readable(prev.coverThumbPath) || !readable(prev.coverLocalPath)))
                    || (resources.embeddedLyric != null && !readable(resources.embeddedLyric))) {
                return buildTrack(audio, size, mtime);
            }
            refreshResources(prev, audio, resources);
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

        LibraryCache.Resources resources = new LibraryCache.Resources();
        resources.embeddedCover = t.coverBytes != null;
        if (resources.embeddedCover) cache.writeCovers(t, t.filePath, t.coverBytes);
        t.coverBytes = null;
        String embedded = t.embeddedLyricText != null ? t.embeddedLyricText : extractEmbeddedLyric(audio);
        t.embeddedLyricText = null;
        if (embedded != null) resources.embeddedLyric = cache.writeLyric(t.filePath, embedded);
        cache.resources(t.filePath, resources);
        refreshResources(t, audio, resources);
        return t;
    }

    private void refreshResources(Track track, Path audio, LibraryCache.Resources resources) {
        String base = baseName(audio);
        Path parent = audio.getParent();
        if (!resources.embeddedCover) {
            Path cover = sidecarCover(parent, base);
            String version = coverVersion(cover);
            if (!java.util.Objects.equals(resources.coverSource, version)
                    || (cover != null && (!readable(track.coverThumbPath) || !readable(track.coverLocalPath)))) {
                track.coverThumbPath = null;
                track.coverLocalPath = null;
                if (cover != null) {
                    try {
                        cache.writeCovers(track, track.filePath, Files.readAllBytes(cover));
                    } catch (IOException e) {
                        Logger.warn("LibraryScanner: cover read failed for {}: {}", cover, e.getMessage());
                    }
                }
                resources.coverSource = version;
            }
            if (cover == null) {
                track.coverThumbPath = null;
                track.coverLocalPath = null;
            }
        }
        track.lyricFilePath = pickSidecar(parent, base, ".ttml", ".lys", ".qrc", ".yrc", ".eslrc", ".lrc");
        if (track.lyricFilePath == null) track.lyricFilePath = resources.embeddedLyric;
        track.translationFilePath = pickSidecar(parent, base, ".tlrc", ".translation.lrc");
        track.romajiFilePath = pickSidecar(parent, base, ".romaji.lrc", ".romaji.lys");
    }

    /** Restore queue-side local resource paths without reading tags or lyrics. */
    public static void discoverSidecars(Track track) {
        if (track == null || track.filePath == null || track.filePath.isEmpty()) return;
        Path audio = Paths.get(track.filePath);
        String base = baseName(audio);
        Path parent = audio.getParent();
        track.lyricFilePath = discoveredOrExisting(track.lyricFilePath, pickSidecar(parent, base,
                ".ttml", ".lys", ".qrc", ".yrc", ".eslrc", ".lrc"));
        track.translationFilePath = discoveredOrExisting(track.translationFilePath,
                pickSidecar(parent, base, ".tlrc", ".translation.lrc"));
        track.romajiFilePath = discoveredOrExisting(track.romajiFilePath,
                pickSidecar(parent, base, ".romaji.lrc", ".romaji.lys"));
    }

    private static String discoveredOrExisting(String existing, String discovered) {
        return discovered != null ? discovered : readable(existing) ? existing : null;
    }

    private static boolean readable(String value) {
        if (value == null) return false;
        try {
            Path path = Paths.get(value);
            return Files.isRegularFile(path) && Files.isReadable(path);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String baseName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private static String coverVersion(Path path) {
        if (path == null) return null;
        try {
            return path + ":" + Files.size(path) + ":" + Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return path.toString();
        }
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

    private static Path sidecarCover(Path dir, String baseName) {
        if (dir == null) return null;
        String[] candidates = {
                baseName + ".jpg", baseName + ".jpeg", baseName + ".png",
                "cover.jpg", "cover.jpeg", "cover.png",
                "folder.jpg", "folder.jpeg", "folder.png",
                "album.jpg", "album.jpeg", "album.png",
        };
        for (String name : candidates) {
            Path p = dir.resolve(name);
            if (Files.isRegularFile(p) && Files.isReadable(p)) return p;
        }
        return null;
    }

    private static String pickSidecar(Path dir, String base, String... exts) {
        if (dir == null) return null;
        for (String ext : exts) {
            Path candidate = dir.resolve(base + ext);
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) return candidate.toString();
        }
        return null;
    }

    public static String[] supportedExtensions() {
        return SUPPORTED_EXTS.clone();
    }
}
