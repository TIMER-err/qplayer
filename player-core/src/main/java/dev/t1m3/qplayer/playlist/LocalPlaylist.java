package dev.t1m3.qplayer.playlist;

import dev.t1m3.qplayer.media.MediaId;
import dev.t1m3.qplayer.media.MediaKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A user-owned playlist that can mix songs from every source at once.
 *
 * <p>Two kinds of content live in the same ordered list. Songs added one at a
 * time carry {@link PlaylistTrack#MANUAL} as their origin and are only ever
 * changed by the user. Songs pulled in by adding a whole source playlist carry
 * that playlist's media id, and {@link #applySync} replaces exactly those on
 * every refresh — so the local copy follows the source, while everything the
 * user curated by hand survives untouched.
 *
 * <p>Identity is by song: the same song is never stored twice, whichever route
 * it arrived by, and the first arrival wins. That is why a sync cannot evict a
 * hand-added song — it simply skips one it already finds.
 */
public final class LocalPlaylist {

    public String id = "";
    public String name = "";
    public String description = "";
    /** Absolute path of a cover the user chose, or empty to derive one from the
     *  songs. The file lives under {@code AppDirs.playlistCoversDir()} — a copy,
     *  so the playlist does not break when the original image moves. */
    public String coverPath = "";
    public long createdAtMs;
    public long updatedAtMs;
    /** How the page presents {@link #tracks}; empty is the stored order. Sorting
     *  is a view, never a rewrite — see {@link #sortedTracks()}. Persisted per
     *  playlist so reopening one looks the way the user left it. */
    public String sortField = SORT_CUSTOM;
    public boolean sortDescending;
    public List<PlaylistTrack> tracks = new ArrayList<>();
    public List<PlaylistSubscription> subscriptions = new ArrayList<>();

    /** The stored order: what the user arranged, and where subscriptions sit. */
    public static final String SORT_CUSTOM = "";
    public static final String SORT_TITLE = "title";
    public static final String SORT_ARTIST = "artist";
    public static final String SORT_DURATION = "duration";
    public static final String SORT_ADDED = "added";
    public static final String SORT_SOURCE = "source";

    private static final List<String> SORT_FIELDS = Arrays.asList(
            SORT_CUSTOM, SORT_TITLE, SORT_ARTIST, SORT_DURATION, SORT_ADDED, SORT_SOURCE);

    public static boolean isSortField(String value) {
        return value != null && SORT_FIELDS.contains(value);
    }

    public static LocalPlaylist create(String name, long nowMs) {
        LocalPlaylist playlist = new LocalPlaylist();
        playlist.id = newId();
        playlist.name = name != null ? name.trim() : "";
        playlist.createdAtMs = nowMs;
        playlist.updatedAtMs = nowMs;
        return playlist;
    }

    /** Local playlists are addressed like any other media, under the reserved
     *  {@code local} provider, so one id type flows through the whole UI. */
    public static String newId() {
        return MediaId.of("local", MediaKind.PLAYLIST,
                UUID.randomUUID().toString()).toString();
    }

    public static boolean isLocalPlaylistId(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return false;
        try {
            MediaId parsed = MediaId.parse(mediaId);
            return "local".equals(parsed.provider()) && parsed.kind() == MediaKind.PLAYLIST;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public int size() {
        return tracks.size();
    }

    public boolean contains(String mediaId) {
        return indexOf(mediaId) >= 0;
    }

    public int indexOf(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return -1;
        for (int i = 0; i < tracks.size(); i++) {
            if (mediaId.equals(tracks.get(i).mediaId)) return i;
        }
        return -1;
    }

    /** Append one song. Returns false when the playlist already has it. */
    public boolean add(PlaylistTrack track) {
        if (track == null || !track.valid() || contains(track.mediaId)) return false;
        tracks.add(track);
        return true;
    }

    public boolean removeAt(int index) {
        if (index < 0 || index >= tracks.size()) return false;
        tracks.remove(index);
        return true;
    }

    public boolean remove(String mediaId) {
        int index = indexOf(mediaId);
        return index >= 0 && removeAt(index);
    }

    /** Move a song, e.g. from a drag in the playlist page. */
    public boolean move(int from, int to) {
        if (from < 0 || from >= tracks.size() || to < 0 || to >= tracks.size() || from == to) {
            return false;
        }
        tracks.add(to, tracks.remove(from));
        return true;
    }

    public PlaylistSubscription subscription(String sourcePlaylistId) {
        if (sourcePlaylistId == null || sourcePlaylistId.isEmpty()) return null;
        for (PlaylistSubscription subscription : subscriptions) {
            if (sourcePlaylistId.equals(subscription.sourcePlaylistId)) return subscription;
        }
        return null;
    }

    public boolean subscribed(String sourcePlaylistId) {
        return subscription(sourcePlaylistId) != null;
    }

    /** Record a source playlist to follow. Returns false if already followed. */
    public boolean subscribe(PlaylistSubscription subscription) {
        if (subscription == null || subscription.sourcePlaylistId.isEmpty()) return false;
        if (subscribed(subscription.sourcePlaylistId)) return false;
        subscriptions.add(subscription);
        return true;
    }

    /**
     * Stop following a source playlist and drop the songs it contributed. A song
     * the user had also added by hand is not one of those — it carries the manual
     * origin — so unsubscribing never removes hand-curated content.
     */
    public boolean unsubscribe(String sourcePlaylistId) {
        if (subscription(sourcePlaylistId) == null) return false;
        subscriptions.removeIf(s -> sourcePlaylistId.equals(s.sourcePlaylistId));
        tracks.removeIf(t -> sourcePlaylistId.equals(t.origin));
        return true;
    }

    /**
     * Replace everything one subscription contributed with the source's current
     * songs, in place.
     *
     * <p>In place matters: the subscription's block keeps its position in the
     * playlist instead of jumping to the end on every refresh. Songs the source
     * added appear, songs it removed disappear, and any song already present
     * under a different origin is skipped so the first arrival keeps its slot.
     *
     * @return the number of songs this subscription now contributes
     */
    public int applySync(String sourcePlaylistId, List<PlaylistTrack> incoming, long nowMs) {
        if (sourcePlaylistId == null || sourcePlaylistId.isEmpty()) return 0;
        int insertAt = -1;
        for (int i = 0; i < tracks.size(); i++) {
            if (sourcePlaylistId.equals(tracks.get(i).origin)) {
                insertAt = i;
                break;
            }
        }
        tracks.removeIf(t -> sourcePlaylistId.equals(t.origin));
        if (insertAt < 0 || insertAt > tracks.size()) insertAt = tracks.size();

        Set<String> taken = new HashSet<>();
        for (PlaylistTrack existing : tracks) taken.add(existing.mediaId);

        List<PlaylistTrack> accepted = new ArrayList<>();
        if (incoming != null) {
            for (PlaylistTrack candidate : incoming) {
                if (candidate == null || !candidate.valid()) continue;
                if (!taken.add(candidate.mediaId)) continue;
                candidate.origin = sourcePlaylistId;
                if (candidate.addedAtMs == 0L) candidate.addedAtMs = nowMs;
                accepted.add(candidate);
            }
        }
        tracks.addAll(insertAt, accepted);

        PlaylistSubscription subscription = subscription(sourcePlaylistId);
        if (subscription != null) {
            subscription.trackCount = accepted.size();
            subscription.lastSyncedAtMs = nowMs;
            subscription.lastError = "";
        }
        return accepted.size();
    }

    /**
     * The songs as the page should show them.
     *
     * <p>Sorting deliberately does not touch {@link #tracks}: the stored order is
     * what the user arranged by hand and what {@link #applySync} replaces a
     * subscription's block within, so rewriting it to sort by title would destroy
     * both. Switching back to {@link #SORT_CUSTOM} restores exactly what was there.
     *
     * <p>Ties fall back to the stored order, which makes every sort stable — two
     * songs with the same title keep their relative positions instead of swapping
     * around between openings.
     */
    public List<PlaylistTrack> sortedTracks() {
        if (!isSortField(sortField) || SORT_CUSTOM.equals(sortField)) {
            return new ArrayList<>(tracks);
        }
        List<PlaylistTrack> out = new ArrayList<>(tracks);
        final java.util.IdentityHashMap<PlaylistTrack, Integer> storedIndex =
                new java.util.IdentityHashMap<>();
        for (int i = 0; i < tracks.size(); i++) storedIndex.put(tracks.get(i), i);

        final java.text.Collator collator = java.text.Collator.getInstance();
        // TERTIARY would order "a" before "A"; for a song list case is noise.
        collator.setStrength(java.text.Collator.SECONDARY);
        final String field = sortField;
        Comparator<PlaylistTrack> comparator = new Comparator<PlaylistTrack>() {
            @Override public int compare(PlaylistTrack left, PlaylistTrack right) {
                switch (field) {
                    case SORT_DURATION:
                        return Long.compare(left.durationMs, right.durationMs);
                    case SORT_ADDED:
                        return Long.compare(left.addedAtMs, right.addedAtMs);
                    case SORT_ARTIST:
                        return compareText(collator, left.artist, right.artist);
                    case SORT_SOURCE:
                        return compareText(collator, left.provider, right.provider);
                    default:
                        return compareText(collator, left.title, right.title);
                }
            }
        };
        // Reverse the field comparison for descending, then break ties by stored
        // position -- NOT by reversing the whole result, which would also flip
        // equal rows and make two same-titled songs swap places between openings.
        final Comparator<PlaylistTrack> primary =
                sortDescending ? comparator.reversed() : comparator;
        out.sort((left, right) -> {
            int decided = primary.compare(left, right);
            if (decided != 0) return decided;
            return Integer.compare(storedIndex.get(left), storedIndex.get(right));
        });
        return out;
    }

    /** Empty values sort last in ascending order rather than clumping at the top,
     *  where a handful of untitled rows would push the real content down. */
    private static int compareText(java.text.Collator collator, String left, String right) {
        boolean leftEmpty = left == null || left.isEmpty();
        boolean rightEmpty = right == null || right.isEmpty();
        if (leftEmpty || rightEmpty) return leftEmpty == rightEmpty ? 0 : (leftEmpty ? 1 : -1);
        return collator.compare(left, right);
    }

    /** Cover for the playlist card: the user's own choice if they made one, else
     *  the first song that has artwork, else a followed playlist's cover. */
    public String artworkUrl() {
        if (coverPath != null && !coverPath.isEmpty()) return coverPath;
        for (PlaylistTrack track : tracks) {
            if (track.coverUrl != null && !track.coverUrl.isEmpty()) return track.coverUrl;
        }
        for (PlaylistSubscription subscription : subscriptions) {
            if (!subscription.artworkUrl.isEmpty()) return subscription.artworkUrl;
        }
        return "";
    }

    /** Whether the cover is the user's own rather than derived from the content. */
    public boolean hasCustomCover() {
        return coverPath != null && !coverPath.isEmpty();
    }

    /** Drop records that can no longer identify a song, and de-duplicate. Runs on
     *  load, so a hand-edited or partially-written file cannot poison the list. */
    public void sanitize() {
        if (id == null || !isLocalPlaylistId(id)) id = newId();
        if (name == null) name = "";
        if (description == null) description = "";
        if (!isSortField(sortField)) sortField = SORT_CUSTOM;
        // A cover file that is gone (profile moved, manually deleted) must fall
        // back to a derived one rather than leaving the card permanently blank.
        if (coverPath == null || (!coverPath.isEmpty()
                && !new java.io.File(coverPath).isFile())) {
            coverPath = "";
        }
        if (tracks == null) tracks = new ArrayList<>();
        if (subscriptions == null) subscriptions = new ArrayList<>();
        subscriptions.removeIf(s -> s == null || s.sourcePlaylistId == null
                || s.sourcePlaylistId.isEmpty());

        Set<String> seen = new HashSet<>();
        List<PlaylistTrack> kept = new ArrayList<>(tracks.size());
        for (PlaylistTrack track : tracks) {
            if (track == null || !track.valid()) continue;
            if (!seen.add(track.mediaId)) continue;
            if (track.origin == null) track.origin = PlaylistTrack.MANUAL;
            // An origin naming a subscription that is no longer followed would be
            // unreachable by any sync; treat it as hand-added instead of dropping.
            if (track.fromSubscription() && subscription(track.origin) == null) {
                track.origin = PlaylistTrack.MANUAL;
            }
            kept.add(track);
        }
        tracks = kept;
    }
}
