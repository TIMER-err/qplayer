package dev.t1m3.qplayer.android;

import android.media.MediaMetadataRetriever;

import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.model.Track;

/**
 * {@link MetadataReader} over {@code android.media.MediaMetadataRetriever}.
 * Best-effort: leaves the caller's filename defaults on any failure.
 */
public final class AndroidMetadataReader implements MetadataReader {

    @Override
    public void read(Track track) {
        if (track.filePath == null) return;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(track.filePath);
            String title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            if (title != null && !title.isEmpty()) track.title = title;
            String artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (artist != null) track.artist = artist;
            String album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            if (album != null) track.album = album;
            String dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) {
                try {
                    track.durationMs = Long.parseLong(dur);
                } catch (NumberFormatException ignored) {
                }
            }
            byte[] art = r.getEmbeddedPicture();
            if (art != null) track.coverBytes = art;
        } catch (Throwable ignored) {
            // Unreadable / unsupported — keep filename defaults.
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
