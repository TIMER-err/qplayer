package dev.t1m3.qplayer.desktop;

import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.util.Logger;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.LogManager;

/**
 * jaudiotagger-backed tag/duration/cover reader for the desktop host.
 */
public final class DesktopMetadataReader implements MetadataReader {

    static {
        // jaudiotagger logs an INFO line per tag read by default — silence it.
        try {
            LogManager.getLogManager().getLogger("org.jaudiotagger").setLevel(Level.OFF);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void read(Track track) {
        if (track.filePath == null) return;
        try {
            AudioFile af = AudioFileIO.read(new File(track.filePath));
            AudioHeader header = af.getAudioHeader();
            if (header != null) {
                track.durationMs = header.getTrackLength() * 1000L;
            }
            Tag tag = af.getTag();
            if (tag != null) {
                String title = safeTag(tag, FieldKey.TITLE);
                if (title != null && !title.isEmpty()) track.title = title;
                track.artist = nullToEmpty(safeTag(tag, FieldKey.ARTIST));
                track.album = nullToEmpty(safeTag(tag, FieldKey.ALBUM));
                Artwork art = tag.getFirstArtwork();
                if (art != null) track.coverBytes = art.getBinaryData();
                String lyric = safeTag(tag, FieldKey.LYRICS);
                if (lyric != null && !lyric.isEmpty()) track.embeddedLyricText = lyric;
            }
        } catch (Throwable e) {
            // Unknown/odd formats (e.g. bare .wav) throw — keep filename defaults.
            Logger.warn("DesktopMetadataReader: {} — {}", track.filePath, e.getMessage());
        }
    }

    private static String safeTag(Tag tag, FieldKey key) {
        try {
            return tag.getFirst(key);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
