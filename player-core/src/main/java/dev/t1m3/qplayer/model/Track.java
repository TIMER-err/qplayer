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
    /** Content URI (LOCAL source on Android 13+). Preferred over filePath because
     *  Scoped Storage blocks direct file-path access to /storage/emulated/0/. */
    public String contentUri;
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

    /** Sidecar lyric paths discovered next to a LOCAL file; null if absent. */
    public String lyricFilePath;
    public String translationFilePath;
    public String romajiFilePath;

    /** The source string the audio backend should open: content URI, file path, or stream url. */
    public String playable() {
        if (source == Source.NETEASE) return streamUrl;
        return contentUri != null ? contentUri : filePath;
    }

    @Override
    public String toString() {
        return (artist != null ? artist : "?") + " — " + (title != null ? title : "?");
    }
}
