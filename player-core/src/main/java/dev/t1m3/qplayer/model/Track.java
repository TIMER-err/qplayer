package dev.t1m3.qplayer.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Flat track descriptor shared between the local library, source plugins, the
 * audio backend and the QML bridge. Kept a primitive/JDK-only POJO so it
 * dexes for Android and the QML bridge can read its fields directly.
 */
public class Track {

    /** Legacy routing marker retained while built-in providers are migrated. */
    public enum Source { LOCAL, NETEASE, CUSTOM_API, PLUGIN }

    public Source source = Source.LOCAL;

    /** Canonical source-qualified identity, e.g. netease:song:2668056312. */
    public String mediaId;
    /** Actual rendition selected by generic fallback; logical identity stays mediaId. */
    public String renditionId;

    /** Local file path (LOCAL source); null for online providers. */
    public String filePath;
    /** Content URI (LOCAL source on Android 13+). Preferred over filePath because
     *  Scoped Storage blocks direct file-path access to /storage/emulated/0/. */
    public String contentUri;
    /** Provider stream URL — fetched lazily, transient, and possibly null. */
    public String streamUrl;
    /** Provider-required headers for {@link #streamUrl}; transient and never persisted. */
    public Map<String, String> streamHeaders = new LinkedHashMap<>();
    /** Epoch millis after which the provider says {@link #streamUrl} must be resolved again. */
    public long streamExpiresAtMs;
    /** True when {@link #streamUrl} is only a trial/preview clip (not the full
     *  track) — such a clip must never be written to the audio disk cache. */
    public boolean trial;
    /** Whether the provider permits the resolved rendition to be stored offline. */
    public boolean streamCacheable = true;
    /** Legacy provider id retained only for non-destructive v1 queue migration. */
    public long neteaseId;
    /** External id from a user-configured custom API (CUSTOM_API source); null
     *  otherwise. String rather than long since third-party ids aren't guaranteed
     *  numeric. Retained only to migrate delayed v1 queue records. */
    public String customId;

    public String title;
    public String artist;
    /** Legacy first-artist id retained while v1 queue records are migrated. */
    public long artistId;
    /** Canonical first-artist id for plugin tracks. */
    public String artistMediaId = "";
    /** Every credited artist id/name in the legacy comma-separated representation. Keeping these on
     *  Track preserves multi-artist navigation after a song enters the queue or
     *  the custom playlist instead of degrading to the first artist only. */
    public String artistIdsCsv = "";
    public String artistNamesCsv = "";
    public String album;
    public long durationMs;

    /** Embedded / sidecar cover bytes (JPEG/PNG), or null. */
    public byte[] coverBytes;
    /** Remote cover URL; accepted only when its owning plugin granted the domain. */
    public String coverUrl;
    /** List-row art. Plugins may provide a small remote thumbnail; local media uses a downscaled thumbnail
     *  cache file (~256px) so scrolling a list never decodes a multi-megapixel embedded
     *  cover per row. Null if none. */
    public String coverThumbPath;
    /** LOCAL full-ish cover cache file (~512px) for the now-playing / lyric view and the
     *  fluid backdrop; online providers use {@link #coverUrl}. */
    public String coverLocalPath;

    /** Sidecar lyric paths discovered next to a LOCAL file; null if absent. */
    public String lyricFilePath;
    public String translationFilePath;
    public String romajiFilePath;
    /** Embedded lyric text read straight off the file's tags by the platform
     *  MetadataReader (e.g. desktop's jaudiotagger FieldKey.LYRICS, which — unlike
     *  the hand-rolled {@code EmbeddedLyrics} fallback used where no such tag API
     *  exists — also covers OGG/MP4 containers). Same transient-then-cleared
     *  lifecycle as {@link #coverBytes}: LibraryScanner writes it to a cache .lrc
     *  and nulls it out, never persisted on the Track itself. */
    public String embeddedLyricText;

    /** File size / last-modified (LOCAL source) — the library cache's invalidation
     *  key, so an unchanged file skips the expensive tag/cover/lyric re-read. */
    public long fileSize;
    public long fileMtime;

    /** The source string the audio backend should open: content URI, file path, or stream url. */
    public String playable() {
        return source == Source.LOCAL ? (contentUri != null ? contentUri : filePath) : streamUrl;
    }

    /** Fill the v2 identity lazily when reading an old queue/cache record. */
    public String canonicalId() {
        if (mediaId != null && !mediaId.isEmpty()) {
            try {
                return dev.t1m3.qplayer.media.MediaId.parse(mediaId).requireKind(
                        dev.t1m3.qplayer.media.MediaKind.SONG).toString();
            } catch (IllegalArgumentException ignored) {
                mediaId = null;
            }
        }
        if (source == Source.NETEASE && neteaseId != 0) {
            mediaId = dev.t1m3.qplayer.media.MediaId.of("netease",
                    dev.t1m3.qplayer.media.MediaKind.SONG, Long.toString(neteaseId)).toString();
        } else if (source == Source.CUSTOM_API && customId != null && !customId.isEmpty()) {
            mediaId = dev.t1m3.qplayer.media.MediaId.of("legacy-custom",
                    dev.t1m3.qplayer.media.MediaKind.SONG, customId).toString();
        } else if (source == Source.LOCAL) {
            String locator = contentUri != null && !contentUri.isEmpty() ? contentUri : filePath;
            if (locator != null && !locator.isEmpty()) {
                mediaId = dev.t1m3.qplayer.media.MediaId.of("local",
                        dev.t1m3.qplayer.media.MediaKind.SONG, sha256(locator)).toString();
            }
        }
        return mediaId != null ? mediaId : "";
    }

    /** Populate the compatibility fields needed by the old controller. */
    public void applyCanonicalId(String id) {
        dev.t1m3.qplayer.media.MediaId parsed = dev.t1m3.qplayer.media.MediaId.parse(id)
                .requireKind(dev.t1m3.qplayer.media.MediaKind.SONG);
        mediaId = parsed.toString();
        if ("local".equals(parsed.provider())) {
            source = Source.LOCAL;
        } else if ("netease".equals(parsed.provider())) {
            source = Source.NETEASE;
            try { neteaseId = Long.parseLong(parsed.nativeId()); }
            catch (NumberFormatException ignored) { neteaseId = 0; source = Source.PLUGIN; }
        } else if ("legacy-custom".equals(parsed.provider())) {
            source = Source.CUSTOM_API;
            customId = parsed.nativeId();
        } else {
            source = Source.PLUGIN;
        }
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public String toString() {
        return (artist != null ? artist : "?") + " — " + (title != null ? title : "?");
    }
}
