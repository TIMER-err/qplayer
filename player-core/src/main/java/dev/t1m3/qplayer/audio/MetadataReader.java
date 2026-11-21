package dev.t1m3.qplayer.audio;

import dev.t1m3.qplayer.model.Track;

/**
 * Reads tags (title/artist/album), duration and embedded cover from a LOCAL
 * track's {@code filePath} into the {@link Track}. Platform-specific: desktop
 * uses jaudiotagger, Android uses {@code MediaMetadataRetriever}.
 *
 * <p>Best-effort — implementations must leave the caller's filename-derived
 * defaults intact on any read failure rather than throwing.
 */
public interface MetadataReader {

    void read(Track track);

    /** 2.1.1 的宿主会调它把标签写回文件（含歌词）。默认空实现 ✓ */
    default void writeTags(String path, String title, String artist, String album,
                           byte[] cover, String lyrics) { }
}
