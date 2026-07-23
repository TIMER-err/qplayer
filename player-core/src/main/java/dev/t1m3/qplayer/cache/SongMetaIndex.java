package dev.t1m3.qplayer.cache;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small persisted id -&gt; {@link NeteaseSong} metadata index, built up
 * incrementally from whatever songs actually pass through the app (search
 * results, and anything whose audio gets disk-cached). {@link DiskCache}
 * itself only ever sees a bare neteaseId as a filename — it has no title/
 * artist to show a human, so offline search (no network, but the app still
 * wants to surface "songs you've seen before") needed this separate index.
 *
 * <p>Not a general-purpose "recently played" list — just a lookup table this
 * process can query by keyword when a live search fails. Capped and LRU'd by
 * insertion order (a {@link LinkedHashMap} access-order map), same idea as
 * {@code PlayerController}'s in-memory lyric caches.
 */
public final class SongMetaIndex {

    private static final int MAX_ENTRIES = 3000;

    private final File file = new File(AppDirs.base(), "song_meta_index.json");
    private final Gson gson = new Gson();
    private final Map<Long, NeteaseSong> byId = Collections.synchronizedMap(
            new LinkedHashMap<Long, NeteaseSong>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, NeteaseSong> e) {
                    return size() > MAX_ENTRIES;
                }
            });
    private volatile boolean dirty = false;

    public void load() {
        try {
            if (!file.isFile()) return;
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Type t = new TypeToken<List<NeteaseSong>>() {}.getType();
            List<NeteaseSong> list = gson.fromJson(json, t);
            if (list == null) return;
            synchronized (byId) {
                for (NeteaseSong s : list) {
                    if (s != null && s.id != 0 && s.name != null && !s.name.isEmpty()) byId.put(s.id, s);
                }
            }
        } catch (Throwable e) {
            Logger.warn("SongMetaIndex load failed: {}", e.getMessage());
        }
    }

    /** Record/refresh one song's metadata. Cheap — no disk I/O; call {@link #save()}
     *  after a batch (e.g. once per finished search) rather than per song. */
    public void upsert(long id, String name, String artist, String album, String coverUrl, long durationMs) {
        if (id == 0 || name == null || name.isEmpty()) return;
        NeteaseSong s = new NeteaseSong();
        s.id = id;
        s.name = name;
        s.artist = artist;
        s.album = album;
        s.coverUrl = coverUrl;
        s.durationMs = durationMs;
        byId.put(id, s);
        dirty = true;
    }

    public void upsert(NeteaseSong s) {
        if (s == null) return;
        upsert(s.id, s.name, s.artist, s.album, s.coverUrl, s.durationMs);
    }

    /** Keyword substring match over title + artist, most-recently-seen first
     *  (the backing map is access/insertion ordered). Blocking; call off the
     *  render thread. */
    public List<NeteaseSong> search(String keywordLower, int limit) {
        List<NeteaseSong> out = new ArrayList<>();
        if (keywordLower == null || keywordLower.isEmpty()) return out;
        List<NeteaseSong> snapshot;
        synchronized (byId) {
            snapshot = new ArrayList<>(byId.values());
        }
        // Most-recently-touched last in a LinkedHashMap's access-order iteration —
        // reverse so the freshest matches come first.
        for (int i = snapshot.size() - 1; i >= 0 && out.size() < limit; i--) {
            NeteaseSong s = snapshot.get(i);
            String name = s.name != null ? s.name.toLowerCase(Locale.ROOT) : "";
            String artist = s.artist != null ? s.artist.toLowerCase(Locale.ROOT) : "";
            if (name.contains(keywordLower) || artist.contains(keywordLower)) out.add(s);
        }
        return out;
    }

    public void save() {
        if (!dirty) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            List<NeteaseSong> snapshot;
            synchronized (byId) {
                snapshot = new ArrayList<>(byId.values());
            }
            Files.write(file.toPath(), gson.toJson(snapshot).getBytes(StandardCharsets.UTF_8));
            dirty = false;
        } catch (Throwable e) {
            Logger.warn("SongMetaIndex save failed: {}", e.getMessage());
        }
    }
}
