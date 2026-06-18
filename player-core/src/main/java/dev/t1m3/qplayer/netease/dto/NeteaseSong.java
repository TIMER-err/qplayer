package dev.t1m3.qplayer.netease.dto;

/**
 * Minimal song descriptor decoded from netease search / playlist /
 * recommend responses. Keep this a flat POJO so the QML bridge can read its
 * fields directly.
 */
public class NeteaseSong {
    public long id;
    public String name;
    /** All artists joined with " / ". Null if absent. */
    public String artist;
    public String album;
    /** Album cover URL (CDN, jpg/png). Renderer fetches bytes lazily. */
    public String coverUrl;
    /** Base64 data-URI (data:image/jpeg;base64,…) of a pre-cached 128px
     *  thumbnail. Set by the thumbnail downloader after a search completes;
     *  null until cached. QML Image.source consumes it directly. */
    public String coverThumbPath;
    /** Track length in milliseconds (field "dt" in the JSON). */
    public long durationMs;
    /** Set when the song is VIP / unavailable to anonymous clients. */
    public boolean fee;
}
