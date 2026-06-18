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
    /** CDN thumbnail URL (coverUrl + ?param=128y128) for QML Image.source.
     *  Set by the controller after a search completes; null until resolved. */
    public String coverThumbPath;
    /** Track length in milliseconds (field "dt" in the JSON). */
    public long durationMs;
    /** Set when the song is VIP / unavailable to anonymous clients. */
    public boolean fee;
}
