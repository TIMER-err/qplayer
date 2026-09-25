package dev.t1m3.qplayer.playlist;

import dev.t1m3.qplayer.media.MediaId;
import dev.t1m3.qplayer.model.Track;

/**
 * One song as it is persisted inside a local playlist, and as it appears in an
 * exported playlist file.
 *
 * <p>Deliberately a separate DTO rather than {@link Track} itself: a Track also
 * carries transient playback state (resolved stream URL and its headers, embedded
 * cover bytes, expiry) that must never be written to disk, let alone handed to
 * someone else in an export. Everything here is durable identity and metadata.
 *
 * <p>{@link #origin} is what makes a mixed playlist work: an empty value marks a
 * song the user added by hand, and anything else is the media id of the source
 * playlist it was pulled in with. A sync replaces exactly the entries carrying
 * that origin and leaves hand-added songs alone.
 */
public final class PlaylistTrack {

    /** {@link #origin} value for a song the user added individually. */
    public static final String MANUAL = "";

    public String mediaId = "";
    /** Owning source id, split out of {@link #mediaId} so an export stays readable
     *  and an importer can report which sources it needs without parsing ids. */
    public String provider = "";
    /** Human-readable source name at export time ("网易云音乐"); display only. */
    public String providerName = "";
    /** {@link Track.Source} name — kept so a legacy/local track round-trips exactly. */
    public String source = Track.Source.PLUGIN.name();

    public String title = "";
    public String artist = "";
    public String artistMediaId = "";
    public String artistIdsCsv = "";
    public String artistNamesCsv = "";
    public String album = "";
    public long durationMs;
    public String coverUrl = "";

    /** LOCAL source only: where the file was on the exporting machine. */
    public String filePath = "";
    public String contentUri = "";
    /** Legacy numeric netease id, retained so old records keep resolving. */
    public long neteaseId;

    public String origin = MANUAL;
    public long addedAtMs;

    public boolean fromSubscription() {
        return origin != null && !origin.isEmpty();
    }

    public static PlaylistTrack from(Track track, String origin, long addedAtMs) {
        PlaylistTrack out = new PlaylistTrack();
        if (track == null) return out;
        out.mediaId = track.canonicalId();
        out.provider = providerOf(out.mediaId);
        out.source = track.source != null ? track.source.name() : Track.Source.PLUGIN.name();
        out.title = safe(track.title);
        out.artist = safe(track.artist);
        out.artistMediaId = safe(track.artistMediaId);
        out.artistIdsCsv = safe(track.artistIdsCsv);
        out.artistNamesCsv = safe(track.artistNamesCsv);
        out.album = safe(track.album);
        out.durationMs = track.durationMs;
        out.coverUrl = safe(track.coverUrl);
        out.filePath = safe(track.filePath);
        out.contentUri = safe(track.contentUri);
        out.neteaseId = track.neteaseId;
        out.origin = origin != null ? origin : MANUAL;
        out.addedAtMs = addedAtMs;
        return out;
    }

    /**
     * Rebuild a playable Track. The identity is restored from {@link #mediaId}
     * when it parses, which is also what re-establishes {@link Track#source}; the
     * stored source name is only the fallback for a record whose id is unusable.
     */
    public Track toTrack() {
        Track track = new Track();
        Track.Source persistedSource = parseSource(source);
        track.source = persistedSource;
        track.neteaseId = neteaseId;
        if (mediaId != null && !mediaId.isEmpty()) {
            try {
                track.applyCanonicalId(mediaId);
                // A provider id named "netease" is also how an installed netease
                // plugin's songs are addressed. applyCanonicalId() maps that
                // provider to the legacy built-in Source.NETEASE unconditionally,
                // which would silently downgrade a plugin-sourced record back onto
                // QPlayer's old built-in client the moment it round-trips through a
                // local playlist (see PlayerController.toTrackPlugin()). Restore
                // what was actually persisted.
                if (persistedSource == Track.Source.PLUGIN) {
                    track.source = Track.Source.PLUGIN;
                }
            } catch (IllegalArgumentException ignored) {
                // Keep the stored source; a record with a broken id still shows its
                // metadata rather than disappearing from the playlist.
            }
        }
        track.title = safe(title);
        track.artist = safe(artist);
        track.artistMediaId = safe(artistMediaId);
        track.artistIdsCsv = safe(artistIdsCsv);
        track.artistNamesCsv = safe(artistNamesCsv);
        track.album = safe(album);
        track.durationMs = durationMs;
        if (track.source == Track.Source.LOCAL) {
            track.filePath = emptyToNull(filePath);
            track.contentUri = emptyToNull(contentUri);
        } else {
            track.coverUrl = safe(coverUrl);
        }
        return track;
    }

    /** A record is usable only if it can still name the song it refers to. */
    public boolean valid() {
        if (mediaId == null || mediaId.isEmpty()) return false;
        try {
            MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.SONG);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static String providerOf(String mediaId) {
        try {
            return MediaId.parse(mediaId).provider();
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static Track.Source parseSource(String value) {
        if (value == null) return Track.Source.PLUGIN;
        try {
            return Track.Source.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return Track.Source.PLUGIN;
        }
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
