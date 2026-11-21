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

    /** Best-effort write of tags onto a just-downloaded audio file. */
    default void writeTags(String path, String title, String artist, String album,
                           byte[] cover, String lyrics) {}
}
