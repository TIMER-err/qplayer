package dev.t1m3.qplayer.model;

/**
 * Flat track descriptor shared between the library, the netease layer, the
 * audio backend and the QML bridge. Kept a primitive/jdk-only POJO so it
 * dexes for Android and the QML bridge can read its fields directly.
 */
public class Track {

    public enum Source { LOCAL, NETEASE }

    public Source source = Source.LOCAL;

    /** Local file path (LOCAL source); null for NETEASE. */
    public String filePath;
    /** Direct HTTP CDN url (NETEASE source) — fetched lazily, may be null. */
    public String streamUrl;
    /** Netease song id (NETEASE source); 0 for LOCAL. Lets the controller
     *  refetch {@link #streamUrl} when the previous one expires. */
    public long neteaseId;

    public String title;
    public String artist;
    public String album;
    public long durationMs;

    /** Embedded / sidecar cover bytes (JPEG/PNG), or null. */
    public byte[] coverBytes;
    /** Remote cover URL (NETEASE source); resolved lazily when coverBytes is null. */
    public String coverUrl;
    /** CDN thumbnail URL for list-row art (coverUrl + size param); null if none. */
    public String coverThumbPath;

    /** Sidecar lyric paths discovered next to a LOCAL file; null if absent. */
    public String lyricFilePath;
    public String translationFilePath;
    public String romajiFilePath;

    /** The source string the audio backend should open: file path or stream url. */
    public String playable() {
        return source == Source.NETEASE ? streamUrl : filePath;
    }

    @Override
    public String toString() {
        return (artist != null ? artist : "?") + " — " + (title != null ? title : "?");
    }
}
