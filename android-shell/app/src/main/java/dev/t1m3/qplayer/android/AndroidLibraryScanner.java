package dev.t1m3.qplayer.android;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.library.EmbeddedLyrics;
import dev.t1m3.qplayer.library.LibraryCache;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Android 11+ (Scoped Storage) compatible library scanner using MediaStore.
 *
 * <p>MediaStore enumeration is cheap, but the per-file tag/duration/embedded-art/
 * lyric extraction is not, so results are cached by {@link LibraryCache}: any file
 * whose size + modification time are unchanged since the last scan reuses its
 * cached metadata and skips the expensive read. Embedded covers and embedded
 * lyrics are extracted once to cache files (a path on the {@link Track}, not bytes
 * held for the whole library) so list rows lazy-load art and large libraries don't
 * OOM.
 */
public final class AndroidLibraryScanner {

    private final ContentResolver resolver;
    private final MetadataReader reader;
    private final LibraryCache cache = new LibraryCache();

    public AndroidLibraryScanner(ContentResolver resolver, MetadataReader reader) {
        this.resolver = resolver;
        this.reader = reader;
    }

    /** Query all audio from MediaStore, reusing cached metadata for unchanged files.
     *  Runs on the caller's thread (keep it off the render thread). */
    public List<Track> scan() {
        List<Track> out = new ArrayList<>();
        Map<String, Track> cached = cache.load();
        int reused = 0;
        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);

        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED,
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String orderBy = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";

        try (Cursor cursor = resolver.query(collection, projection, selection, null, orderBy)) {
            if (cursor == null) {
                Logger.warn("AndroidLibraryScanner: MediaStore query returned null");
                return out;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
            int mtimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED);

            while (cursor.moveToNext()) {
                String filePath = cursor.getString(dataCol);
                if (filePath == null) continue;
                long id = cursor.getLong(idCol);
                long size = cursor.getLong(sizeCol);
                long mtime = cursor.getLong(mtimeCol);
                String contentUri = Uri.withAppendedPath(collection, String.valueOf(id)).toString();

                Track prev = cached.get(filePath);
                if (LibraryCache.isFresh(prev, size, mtime)) {
                    prev.contentUri = contentUri; // ids can change across reboots
                    out.add(prev);
                    reused++;
                    continue;
                }
                out.add(buildTrack(cursor, filePath, contentUri, size, mtime,
                        titleCol, artistCol, albumCol, durationCol));
            }
        } catch (Throwable e) {
            Logger.exception(e);
        }

        cache.save(out);
        Logger.info("AndroidLibraryScanner: scan done, {} tracks ({} reused from cache)", out.size(), reused);
        return out;
    }

    /** Full (expensive) build for a new or changed file. */
    private Track buildTrack(Cursor cursor, String filePath, String contentUri, long size, long mtime,
            int titleCol, int artistCol, int albumCol, int durationCol) {
        Track t = new Track();
        t.source = Track.Source.LOCAL;
        t.filePath = filePath;
        t.contentUri = contentUri;
        t.fileSize = size;
        t.fileMtime = mtime;

        String title = cursor.getString(titleCol);
        String artist = cursor.getString(artistCol);
        String album = cursor.getString(albumCol);
        long duration = cursor.getLong(durationCol);
        t.title = title != null ? title : "";
        t.artist = artist != null ? artist : "";
        t.album = album != null ? album : "";
        t.durationMs = duration > 0 ? duration : 0L;

        try {
            reader.read(t); // tags / duration / embedded cover bytes
        } catch (Throwable e) {
            Logger.warn("AndroidLibraryScanner: tag read failed for {}: {}", filePath, e.getMessage());
        }

        // Embedded cover -> downscaled cache files (paths on the Track, not bytes held
        // in memory).
        if (t.coverBytes != null) {
            cache.writeCovers(t, filePath, t.coverBytes);
            t.coverBytes = null;
        }

        File parentFile = new File(filePath).getParentFile();
        Path parent = parentFile != null ? parentFile.toPath() : null;
        String baseName = baseName(filePath);
        if (parent != null) {
            if (t.coverThumbPath == null) {
                byte[] sidecar = readSidecarCover(parent, baseName);
                if (sidecar != null) cache.writeCovers(t, filePath, sidecar);
            }
            t.lyricFilePath = pickSidecar(parent, baseName,
                    ".ttml", ".lys", ".qrc", ".yrc", ".eslrc", ".lrc");
            t.translationFilePath = pickSidecar(parent, baseName, ".tlrc", ".translation.lrc");
            t.romajiFilePath = pickSidecar(parent, baseName, ".romaji.lrc", ".romaji.lys");
        }

        // No sidecar lyric -> try the file's embedded lyrics (via content URI so it
        // works under Scoped Storage), extracted once to a cache .lrc.
        if (t.lyricFilePath == null) {
            String embedded = extractEmbeddedLyric(contentUri);
            if (embedded != null) t.lyricFilePath = cache.writeLyric(filePath, embedded);
        }
        return t;
    }

    private String extractEmbeddedLyric(String contentUri) {
        try (InputStream in = resolver.openInputStream(Uri.parse(contentUri))) {
            return in == null ? null : EmbeddedLyrics.extract(in);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String baseName(String filePath) {
        String name = new File(filePath).getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
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
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private static String pickSidecar(Path dir, String baseName, String... exts) {
        if (dir == null) return null;
        for (String ext : exts) {
            Path candidate = dir.resolve(baseName + ext);
            if (Files.exists(candidate)) return candidate.toString();
        }
        return null;
    }
}
