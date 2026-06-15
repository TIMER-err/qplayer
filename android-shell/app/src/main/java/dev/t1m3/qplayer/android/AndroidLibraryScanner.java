package dev.t1m3.qplayer.android;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Android 11+ (Scoped Storage) compatible library scanner using MediaStore API.
 * Falls back to the device's Music folder path for sidecar cover / lyric discovery
 * since MediaStore does not expose those files.
 */
public final class AndroidLibraryScanner {

    private final ContentResolver resolver;
    private final MetadataReader reader;
    private final String musicDirPath;

    public AndroidLibraryScanner(ContentResolver resolver, MetadataReader reader, String musicDirPath) {
        this.resolver = resolver;
        this.reader = reader;
        this.musicDirPath = musicDirPath;
    }

    /**
     * Query all audio files from MediaStore. Runs on the caller's thread
     * (should be off the render thread).
     */
    public List<Track> scan() {
        List<Track> out = new ArrayList<>();
        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);

        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.BUCKET_DISPLAY_PATH,
        };

        // Only scan music files, ordered by title.
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
            int bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.BUCKET_DISPLAY_PATH);

            while (cursor.moveToNext()) {
                String filePath = cursor.getString(dataCol);
                if (filePath == null) continue;

                Track t = new Track();
                t.source = Track.Source.LOCAL;
                t.filePath = filePath;

                // MediaStore metadata as defaults; MetadataReader may overwrite.
                String title = cursor.getString(titleCol);
                String artist = cursor.getString(artistCol);
                String album = cursor.getString(albumCol);
                long duration = cursor.getLong(durationCol);

                t.title = title != null ? title : "";
                t.artist = artist != null ? artist : "";
                t.album = album != null ? album : "";
                t.durationMs = duration > 0 ? duration : 0L;

                // Let the platform reader extract embedded tags / art.
                try {
                    reader.read(t);
                } catch (Throwable e) {
                    Logger.warn("AndroidLibraryScanner: tag read failed for {}: {}", filePath, e.getMessage());
                }

                // Sidecar cover / lyric discovery (same logic as LibraryScanner).
                if (t.coverBytes == null) {
                    Path parent = new File(filePath).getParentFile() != null
                            ? new File(filePath).getParentFile().toPath() : null;
                    String baseName = baseName(filePath);
                    if (parent != null) {
                        t.coverBytes = readSidecarCover(parent, baseName);
                        t.lyricFilePath = pickSidecar(parent, baseName,
                                ".ttml", ".lys", ".qrc", ".yrc", ".eslrc", ".lrc");
                        t.translationFilePath = pickSidecar(parent, baseName, ".tlrc", ".translation.lrc");
                        t.romajiFilePath = pickSidecar(parent, baseName, ".romaji.lrc", ".romaji.lys");
                    }
                }

                out.add(t);
            }
        } catch (Throwable e) {
            Logger.exception(e);
        }

        Logger.info("AndroidLibraryScanner: scan done, {} tracks", out.size());
        return out;
    }

    private static String baseName(String filePath) {
        File f = new File(filePath);
        String name = f.getName();
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
