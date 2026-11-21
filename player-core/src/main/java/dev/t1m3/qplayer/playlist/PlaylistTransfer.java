package dev.t1m3.qplayer.playlist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The exchange format for a local playlist: one self-describing JSON document
 * carrying every song's metadata and the source it came from.
 *
 * <p>An export is meant to survive being opened on a machine that has different
 * plugins installed, so it never assumes the reader can resolve anything. Each
 * song carries its full metadata alongside its source-qualified id, and the
 * document repeats the sources it uses in a {@link Document#sources} summary, so
 * an importer can tell the user which sources a file needs before anything is
 * played. Songs whose source is missing still import — they just cannot play
 * until that plugin is installed.
 */
public final class PlaylistTransfer {

    /** Identifies our own files; an import that doesn't match is rejected rather
     *  than half-read as some other app's playlist JSON. */
    public static final String FORMAT = "qplayer.playlist";
    public static final int FORMAT_VERSION = 1;
    public static final String FILE_SUFFIX = ".qpl.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PlaylistTransfer() {
    }

    /** Wire shape. Public fields, because this IS the documented file format. */
    public static final class Document {
        public String format = FORMAT;
        public int formatVersion = FORMAT_VERSION;
        public String app = "";
        public long exportedAtMs;

        public String name = "";
        public String description = "";
        public int trackCount;
        /** Every source the songs below come from, with how many each contributes.
         *  Purely derived from the track list — a reader's summary, not truth. */
        public List<SourceSummary> sources = new ArrayList<>();
        public List<PlaylistSubscription> subscriptions = new ArrayList<>();
        public List<PlaylistTrack> tracks = new ArrayList<>();
    }

    public static final class SourceSummary {
        public String provider = "";
        public String providerName = "";
        public int trackCount;

        SourceSummary(String provider, String providerName) {
            this.provider = provider;
            this.providerName = providerName;
        }
    }

    /**
     * Serialize a playlist.
     *
     * @param providerName resolves a source id to its display name; the export is
     *                     still valid when it returns null (the id stays).
     */
    public static String export(LocalPlaylist playlist, String appVersion,
                                Function<String, String> providerName) {
        Document document = new Document();
        document.app = appVersion != null ? appVersion : "";
        document.exportedAtMs = System.currentTimeMillis();
        document.name = playlist.name;
        document.description = playlist.description;
        document.trackCount = playlist.tracks.size();

        Map<String, SourceSummary> summaries = new LinkedHashMap<>();
        for (PlaylistTrack track : playlist.tracks) {
            PlaylistTrack copy = GSON.fromJson(GSON.toJson(track), PlaylistTrack.class);
            if (copy.provider == null || copy.provider.isEmpty()) {
                copy.provider = PlaylistTrack.providerOf(copy.mediaId);
            }
            copy.providerName = resolve(providerName, copy.provider);
            document.tracks.add(copy);
            summaries.computeIfAbsent(copy.provider,
                    key -> new SourceSummary(key, copy.providerName)).trackCount++;
        }
        document.sources.addAll(summaries.values());

        for (PlaylistSubscription subscription : playlist.subscriptions) {
            PlaylistSubscription copy = GSON.fromJson(
                    GSON.toJson(subscription), PlaylistSubscription.class);
            if (copy.providerName == null || copy.providerName.isEmpty()) {
                copy.providerName = resolve(providerName, copy.provider);
            }
            // The error is about the exporting machine's last sync; it would be
            // meaningless, and misleading, on someone else's import.
            copy.lastError = "";
            document.subscriptions.add(copy);
        }
        return GSON.toJson(document);
    }

    /** Thrown for a file that is not one of ours, or is too new to read. */
    public static final class UnsupportedFormat extends Exception {
        public UnsupportedFormat(String message) {
            super(message);
        }
    }

    /**
     * Parse an exported document back into a playlist.
     *
     * <p>The result has no id yet — {@link LocalPlaylistStore#insertImported} is
     * what assigns one, so importing a file twice yields two playlists instead of
     * overwriting whatever happens to share its id.
     */
    public static LocalPlaylist parse(String json) throws UnsupportedFormat {
        Document document;
        try {
            document = GSON.fromJson(json, Document.class);
        } catch (Throwable error) {
            throw new UnsupportedFormat("not valid JSON");
        }
        if (document == null || !FORMAT.equals(document.format)) {
            throw new UnsupportedFormat("not a QPlayer playlist file");
        }
        if (document.formatVersion > FORMAT_VERSION) {
            throw new UnsupportedFormat("written by a newer version of QPlayer");
        }
        LocalPlaylist playlist = new LocalPlaylist();
        playlist.name = document.name != null ? document.name : "";
        playlist.description = document.description != null ? document.description : "";
        playlist.createdAtMs = document.exportedAtMs;
        if (document.subscriptions != null) {
            for (PlaylistSubscription subscription : document.subscriptions) {
                if (subscription == null || subscription.sourcePlaylistId == null
                        || subscription.sourcePlaylistId.isEmpty()) {
                    continue;
                }
                // Never trust an imported sync timestamp: the songs in the file are
                // a snapshot from another machine, so this copy is due a sync now.
                subscription.lastSyncedAtMs = 0L;
                subscription.lastError = "";
                playlist.subscriptions.add(subscription);
            }
        }
        if (document.tracks != null) {
            for (PlaylistTrack track : document.tracks) {
                if (track == null) continue;
                playlist.add(track);
            }
        }
        playlist.sanitize();
        return playlist;
    }

    /** Filesystem-safe default name for the exported file. */
    public static String suggestedFileName(String playlistName) {
        String base = playlistName != null ? playlistName.trim() : "";
        StringBuilder out = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char value = base.charAt(i);
            boolean illegal = value < 0x20 || value == '/' || value == '\\' || value == ':'
                    || value == '*' || value == '?' || value == '"' || value == '<'
                    || value == '>' || value == '|';
            out.append(illegal ? '_' : value);
        }
        String name = out.toString().trim();
        // Windows also rejects a trailing dot, and an all-illegal name would leave
        // the file called nothing but its suffix.
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1).trim();
        if (name.isEmpty()) name = "playlist";
        if (name.length() > 80) name = name.substring(0, 80).trim();
        return name + FILE_SUFFIX;
    }

    private static String resolve(Function<String, String> providerName, String provider) {
        if (providerName == null || provider == null || provider.isEmpty()) return "";
        String resolved = providerName.apply(provider);
        return resolved != null ? resolved : "";
    }
}
