package dev.t1m3.qplayer.cache;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.t1m3.qplayer.media.MediaId;
import dev.t1m3.qplayer.media.MediaKind;
import dev.t1m3.qplayer.media.Song;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.Logger;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Source-qualified metadata for offline audio files and offline search. */
public final class MediaMetaIndex {
    private static final int SCHEMA = 1;
    private static final int MAX_ENTRIES = 3000;
    private final Path file = AppDirs.indexFile("media-v2.json");
    private final Path legacyFile = AppDirs.indexFile("songs.json");
    private final Gson gson = new Gson();
    private final Map<String, Entry> byId = Collections.synchronizedMap(
            new LinkedHashMap<String, Entry>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        Map.Entry<String, MediaMetaIndex.Entry> value) {
                    return size() > MAX_ENTRIES;
                }
            });
    private volatile boolean dirty;

    public void load() {
        try {
            if (Files.isRegularFile(file)) {
                State state = gson.fromJson(StorageFiles.readUtf8(file), State.class);
                if (state == null || state.schemaVersion != SCHEMA || state.items == null) {
                    throw new java.io.IOException("unsupported media metadata index");
                }
                synchronized (byId) {
                    for (Entry entry : state.items) if (valid(entry)) byId.put(entry.id, entry);
                }
            }
            migrateLegacy();
        } catch (Throwable error) {
            Logger.warn("MediaMetaIndex load failed: {}", error.getMessage());
        }
    }

    private void migrateLegacy() {
        if (!Files.isRegularFile(legacyFile)) return;
        try {
            Type type = new TypeToken<List<NeteaseSong>>() { }.getType();
            List<NeteaseSong> legacy = gson.fromJson(StorageFiles.readUtf8(legacyFile), type);
            if (legacy == null) return;
            for (NeteaseSong song : legacy) {
                if (song == null || song.id == 0L) continue;
                Track track = new Track();
                track.mediaId = MediaId.of("netease", MediaKind.SONG,
                        Long.toString(song.id)).toString();
                track.title = song.name;
                track.artist = song.artist;
                track.artistId = song.artistId;
                track.artistIdsCsv = song.artistIdsCsv;
                track.artistNamesCsv = song.artistNamesCsv;
                track.album = song.album;
                track.coverUrl = song.coverUrl;
                track.durationMs = song.durationMs;
                upsert(track);
            }
            save();
            // Deliberately retain the v1 file for one rollback-compatible release.
        } catch (Throwable error) {
            Logger.warn("legacy song metadata migration failed: {}", error.getMessage());
        }
    }

    public void upsert(Track track) {
        if (track == null) return;
        String id = track.canonicalId();
        if (id.isEmpty() || track.title == null || track.title.isEmpty()) return;
        Entry value = new Entry();
        value.id = id;
        value.title = track.title;
        value.artist = orEmpty(track.artist);
        value.artistMediaId = orEmpty(track.artistMediaId);
        value.artistIdsCsv = orEmpty(track.artistIdsCsv);
        value.artistNamesCsv = orEmpty(track.artistNamesCsv);
        value.album = orEmpty(track.album);
        value.artworkUrl = orEmpty(track.coverUrl);
        value.durationMs = Math.max(0L, track.durationMs);
        byId.put(id, value);
        dirty = true;
    }

    public void upsert(Song song) {
        if (song == null) return;
        Track track = new Track();
        track.mediaId = song.id;
        track.title = song.title;
        track.artist = song.artist;
        track.artistMediaId = song.artistMediaId;
        track.artistIdsCsv = song.artistIdsCsv;
        track.artistNamesCsv = song.artistNamesCsv;
        track.album = song.album != null ? song.album.name : "";
        track.coverUrl = song.artworkUrl;
        track.durationMs = song.durationMs;
        upsert(track);
    }

    public List<Track> search(String keywordLower, int limit) {
        String query = keywordLower != null ? keywordLower.toLowerCase(Locale.ROOT) : "";
        if (query.isEmpty()) return Collections.emptyList();
        List<Entry> values;
        synchronized (byId) { values = new ArrayList<>(byId.values()); }
        List<Track> result = new ArrayList<>();
        for (int i = values.size() - 1; i >= 0 && result.size() < limit; i--) {
            Entry entry = values.get(i);
            if (entry.title.toLowerCase(Locale.ROOT).contains(query)
                    || entry.artist.toLowerCase(Locale.ROOT).contains(query)) {
                result.add(toTrack(entry));
            }
        }
        return result;
    }

    public List<Track> all() {
        List<Entry> values;
        synchronized (byId) { values = new ArrayList<>(byId.values()); }
        Collections.reverse(values);
        List<Track> result = new ArrayList<>(values.size());
        for (Entry entry : values) result.add(toTrack(entry));
        return result;
    }

    public void save() {
        if (!dirty) return;
        try {
            State state = new State();
            synchronized (byId) { state.items = new ArrayList<>(byId.values()); }
            StorageFiles.writeUtf8Atomic(file, gson.toJson(state));
            dirty = false;
        } catch (Throwable error) {
            Logger.warn("MediaMetaIndex save failed: {}", error.getMessage());
        }
    }

    private static Track toTrack(Entry entry) {
        Track track = new Track();
        track.mediaId = entry.id;
        track.source = "local".equals(MediaId.parse(entry.id).provider())
                ? Track.Source.LOCAL : Track.Source.PLUGIN;
        track.title = entry.title;
        track.artist = entry.artist;
        track.artistMediaId = entry.artistMediaId;
        track.artistIdsCsv = entry.artistIdsCsv;
        track.artistNamesCsv = entry.artistNamesCsv;
        track.album = entry.album;
        track.coverUrl = entry.artworkUrl;
        track.coverThumbPath = entry.artworkUrl;
        track.durationMs = entry.durationMs;
        return track;
    }

    private static boolean valid(Entry entry) {
        if (entry == null || entry.id == null || entry.title == null || entry.title.isEmpty()) return false;
        try { MediaId.parse(entry.id).requireKind(MediaKind.SONG); return true; }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private static String orEmpty(String value) { return value != null ? value : ""; }

    private static final class State {
        int schemaVersion = SCHEMA;
        List<Entry> items = new ArrayList<>();
    }

    private static final class Entry {
        String id = "";
        String title = "";
        String artist = "";
        String artistMediaId = "";
        String artistIdsCsv = "";
        String artistNamesCsv = "";
        String album = "";
        String artworkUrl = "";
        long durationMs;
    }
}
