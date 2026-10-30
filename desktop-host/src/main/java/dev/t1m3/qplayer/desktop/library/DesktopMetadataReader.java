package dev.t1m3.qplayer.desktop.library;

import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.util.Logger;
import io.github.humbleui.skija.Image;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture;
import org.jaudiotagger.tag.FieldDataInvalidException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagField;
import org.jaudiotagger.tag.flac.FlacTag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.jaudiotagger.tag.reference.PictureTypes;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentFieldKey;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag;

import java.io.File;
import java.util.Base64;
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

    @Override
    public void writeTags(String path, String title, String artist, String album,
                          byte[] cover, String lyrics) {
        if (path == null || path.isEmpty()) return;
        try {
            File file = new File(path);
            AudioFile af = AudioFileIO.read(file);
            Tag tag = af.getTagOrCreateAndSetDefault();
            setTag(tag, FieldKey.TITLE, title);
            setTag(tag, FieldKey.ARTIST, artist);
            setTag(tag, FieldKey.ALBUM, album);
            if (lyrics != null && !lyrics.isEmpty()) setTag(tag, FieldKey.LYRICS, lyrics);
            if (cover != null && cover.length > 0) {
                try {
                    TagField artwork = createArtworkField(tag, cover);
                    tag.deleteArtworkField();
                    tag.setField(artwork);
                } catch (Throwable error) {
                    Logger.warn("DesktopMetadataReader cover write: {} — {}", path, error.getMessage());
                }
            }
            af.commit();
        } catch (Throwable e) {
            Logger.warn("DesktopMetadataReader write: {} — {}", path, e.getMessage());
        }
    }

    private static TagField createArtworkField(Tag tag, byte[] cover) throws FieldDataInvalidException {
        String mime = coverMime(cover);
        int pictureType = PictureTypes.DEFAULT_ID;
        if (tag instanceof FlacTag || tag instanceof VorbisCommentTag) {
            // Both Artwork overloads call StandardArtwork.setImageFromData(), even
            // when dimensions are already set, which initializes AWT/ImageIO.
            // Skija reads the encoded header without decoding/re-encoding pixels.
            try (Image image = Image.makeFromEncoded(cover)) {
                MetadataBlockDataPicture picture = new MetadataBlockDataPicture(
                        cover, pictureType, mime, "", image.getWidth(), image.getHeight(), 0, 0);
                if (tag instanceof FlacTag) return picture;
                return ((VorbisCommentTag) tag).createField(
                        VorbisCommentFieldKey.METADATA_BLOCK_PICTURE,
                        Base64.getEncoder().encodeToString(picture.getBytes().array()));
            }
        }
        Artwork artwork = ArtworkFactory.getNew();
        artwork.setBinaryData(cover);
        artwork.setMimeType(mime);
        artwork.setPictureType(pictureType);
        return tag.createField(artwork);
    }

    private static void setTag(Tag tag, FieldKey key, String value) {
        if (value == null || value.isEmpty()) return;
        try {
            tag.setField(key, value);
        } catch (Throwable ignored) {
        }
    }

    private static String coverMime(byte[] cover) {
        if (cover.length >= 3 && cover[0] == (byte) 0xFF && cover[1] == (byte) 0xD8) return "image/jpeg";
        if (cover.length >= 8 && cover[0] == (byte) 0x89 && cover[1] == 0x50) return "image/png";
        return "image/jpeg";
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
