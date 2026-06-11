package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.library.LibraryScanner;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricParser;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.netease.dto.NeteaseLyric;
import dev.t1m3.qplayer.netease.dto.NeteasePlaylist;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.netease.dto.NeteaseUser;
import dev.t1m3.qplayer.util.Logger;
import io.github.timer_err.qml4j.engine.binding.Property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The single QML-facing object. Registered as a context global
 * ({@code view.context("player", controller)}); QML binds to its public
 * {@link Property} fields (reactive — reading registers a dependency, the
 * controller's {@code set} re-evaluates the binding) and invokes its public
 * methods from event handlers.
 *
 * <h3>Playback queue</h3>
 * Local files and netease lists both feed one {@link #queue} of {@link Track}s.
 * Netease tracks carry their id + metadata but resolve their CDN url lazily on
 * first play (so a whole playlist can be queued without fetching every url).
 * {@code next}/{@code prev} walk the queue; auto-advance wires
 * {@code backend.onComplete -> next}.
 *
 * <h3>Threading</h3>
 * The qml4j renderer is single-threaded, so every {@code Property.set} must run
 * on the render thread. QML-invoked methods run there and mutate directly. Work
 * that must run off it — audio completion, blocking netease HTTP — posts its
 * result via {@link #post(Runnable)}; the host drains the queue once per frame
 * in {@link #pump()}.
 */
public final class PlayerController {

    private final AudioBackend backend;
    private final MetadataReader metadataReader;
    private final NeteaseClient netease;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qplayer-net");
        t.setDaemon(true);
        return t;
    });

    private final List<Track> library = new CopyOnWriteArrayList<>();
    private final List<Track> queue = new CopyOnWriteArrayList<>();
    private final Queue<Runnable> uiQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> likedSet = new HashSet<>();

    private volatile String playLevel = "exhigh";
    private volatile long uid;
    private long lastPositionPush;
    private long lastLogVersion = -1;

    // --- Playback state ---------------------------------------------------
    public final Property<Boolean> playing = new Property<>(false);
    public final Property<String> title = new Property<>("");
    public final Property<String> artist = new Property<>("");
    public final Property<String> album = new Property<>("");
    public final Property<String> coverUrl = new Property<>("");
    public final Property<Long> durationMs = new Property<>(0L);
    public final Property<Long> positionMs = new Property<>(0L);
    public final Property<Integer> index = new Property<>(-1);
    public final Property<Float> volume = new Property<>(0.8f);
    public final Property<Boolean> currentLiked = new Property<>(false);
    public final Property<List<LyricLine>> lyrics = new Property<>(Collections.<LyricLine>emptyList());

    // --- Local library ----------------------------------------------------
    public final Property<List<Track>> tracks = new Property<>(Collections.<Track>emptyList());
    public final Property<Integer> libraryCount = new Property<>(0);

    // --- Netease content (Repeater model: player.xxx; delegate reads modelData) ---
    public final Property<List<NeteaseSong>> searchResults = new Property<>(Collections.<NeteaseSong>emptyList());
    public final Property<Integer> resultCount = new Property<>(0);
    public final Property<List<NeteaseSong>> recommendations = new Property<>(Collections.<NeteaseSong>emptyList());
    public final Property<List<NeteasePlaylist>> recommendPlaylists = new Property<>(Collections.<NeteasePlaylist>emptyList());
    public final Property<List<NeteasePlaylist>> myPlaylists = new Property<>(Collections.<NeteasePlaylist>emptyList());
    public final Property<List<NeteaseSong>> recentSongs = new Property<>(Collections.<NeteaseSong>emptyList());
    /** Currently opened playlist. */
    public final Property<List<NeteaseSong>> playlistTracks = new Property<>(Collections.<NeteaseSong>emptyList());
    public final Property<String> playlistTitle = new Property<>("");

    // --- Account ----------------------------------------------------------
    public final Property<Boolean> loggedIn = new Property<>(false);
    public final Property<String> userName = new Property<>("");

    // --- Debug ------------------------------------------------------------
    public final Property<String> logText = new Property<>("");
    /** Transient user-facing message; the UI shows a Snackbar when it changes. */
    public final Property<String> toast = new Property<>("");

    public PlayerController(AudioBackend backend, MetadataReader metadataReader) {
        this(backend, metadataReader, NeteaseClient.INSTANCE);
    }

    public PlayerController(AudioBackend backend, MetadataReader metadataReader, NeteaseClient netease) {
        this.backend = backend;
        this.metadataReader = metadataReader;
        this.netease = netease;
        backend.setVolume(volume.peek());
        backend.setOnComplete(() -> post(this::next));
        if (netease.isLoggedIn()) {
            loggedIn.set(true);
            refreshLogin();
        }
    }

    // --- Frame pump (render thread) --------------------------------------

    /** Drain queued UI mutations, refresh the play head + log. Call once per frame. */
    public void pump() {
        Runnable r;
        while ((r = uiQueue.poll()) != null) {
            try {
                r.run();
            } catch (Throwable e) {
                Logger.exception(e);
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastPositionPush >= 200L) {
            lastPositionPush = now;
            if (backend.isPlaying()) {
                positionMs.set(backend.position());
            }
        }
        long lv = Logger.version();
        if (lv != lastLogVersion) {
            lastLogVersion = lv;
            List<String> lines = Logger.snapshot();
            int from = Math.max(0, lines.size() - 60);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.size(); i++) {
                sb.append(lines.get(i)).append('\n');
            }
            logText.set(sb.toString());
        }
    }

    private void post(Runnable r) {
        uiQueue.add(r);
    }

    public void clearLog() {
        Logger.clear();
    }

    // --- Local library ----------------------------------------------------

    public void scan(String folder) {
        worker.submit(() -> {
            try {
                LibraryScanner scanner = new LibraryScanner(metadataReader);
                List<Track> found = scanner.scan(folder);
                post(() -> {
                    library.clear();
                    library.addAll(found);
                    tracks.set(new ArrayList<>(library));
                    libraryCount.set(library.size());
                });
            } catch (Throwable e) {
                Logger.exception(e);
            }
        });
    }

    public int trackCount() {
        return library.size();
    }

    public String trackTitle(int i) {
        return i >= 0 && i < library.size() ? orEmpty(library.get(i).title) : "";
    }

    public String trackArtist(int i) {
        return i >= 0 && i < library.size() ? orEmpty(library.get(i).artist) : "";
    }

    public String resultTitle(int i) {
        List<NeteaseSong> r = searchResults.peek();
        return i >= 0 && i < r.size() ? orEmpty(r.get(i).name) : "";
    }

    public String resultArtist(int i) {
        List<NeteaseSong> r = searchResults.peek();
        return i >= 0 && i < r.size() ? orEmpty(r.get(i).artist) : "";
    }

    public long resultId(int i) {
        List<NeteaseSong> r = searchResults.peek();
        return i >= 0 && i < r.size() ? r.get(i).id : 0L;
    }

    // --- Playback control -------------------------------------------------

    /** Play local library starting at {@code i}. */
    public void play(int i) {
        if (i < 0 || i >= library.size()) return;
        playQueue(library, i);
    }

    /** Queue a netease song-list and start at {@code i}. */
    public void playSearchResult(int i) {
        playSongList(searchResults.peek(), i);
    }

    public void playRecommendation(int i) {
        playSongList(recommendations.peek(), i);
    }

    public void playRecentSong(int i) {
        playSongList(recentSongs.peek(), i);
    }

    public void playPlaylistTrack(int i) {
        playSongList(playlistTracks.peek(), i);
    }

    /** Play a single netease song id (no surrounding queue). */
    public void playNetease(long songId) {
        Track t = new Track();
        t.source = Track.Source.NETEASE;
        t.neteaseId = songId;
        playQueue(Collections.singletonList(t), 0);
    }

    private void playSongList(List<NeteaseSong> songs, int i) {
        if (songs == null || i < 0 || i >= songs.size()) return;
        List<Track> q = new ArrayList<>(songs.size());
        for (NeteaseSong s : songs) q.add(toTrack(s));
        playQueue(q, i);
    }

    private void playQueue(List<Track> q, int start) {
        queue.clear();
        queue.addAll(q);
        playAt(start);
    }

    private void playAt(int i) {
        if (i < 0 || i >= queue.size()) return;
        Track t = queue.get(i);
        index.set(i);
        title.set(orEmpty(t.title));
        artist.set(orEmpty(t.artist));
        album.set(orEmpty(t.album));
        coverUrl.set(orEmpty(t.coverUrl));
        durationMs.set(t.durationMs);
        positionMs.set(0L);
        currentLiked.set(t.neteaseId != 0 && likedSet.contains(t.neteaseId));

        if (t.source == Track.Source.LOCAL) {
            loadLocalLyrics(t);
            Logger.info("play local: {}", t.title);
            backend.play(t.filePath, 0L);
            playing.set(true);
        } else if (t.streamUrl != null) {
            Logger.info("play netease (cached url): {}", t.title);
            backend.play(t.streamUrl, 0L);
            playing.set(true);
        } else {
            resolveAndPlayNetease(t, i);
        }
    }

    public void toggle() {
        if (index.peek() < 0 && !library.isEmpty()) {
            play(0);
            return;
        }
        if (backend.isPlaying()) {
            backend.pause();
            playing.set(false);
        } else {
            backend.resume();
            playing.set(true);
        }
    }

    public void next() {
        if (queue.isEmpty()) return;
        playAt((index.peek() + 1) % queue.size());
    }

    public void prev() {
        if (queue.isEmpty()) return;
        int n = queue.size();
        playAt((index.peek() - 1 + n) % n);
    }

    public void seek(long ms) {
        backend.seek(ms);
        positionMs.set(Math.max(0L, ms));
    }

    public long position() {
        return backend.position();
    }

    public void setVolume(float v) {
        float clamped = Math.max(0f, Math.min(1f, v));
        backend.setVolume(clamped);
        volume.set(clamped);
    }

    public void setPlayLevel(String level) {
        if (level != null && !level.isEmpty()) playLevel = level;
    }

    private void resolveAndPlayNetease(Track t, int expectedIndex) {
        long songId = t.neteaseId;
        worker.submit(() -> {
            try {
                Logger.info("netease: resolve song {} (loggedIn={}, level={})",
                        songId, netease.isLoggedIn(), playLevel);
                if (t.title == null || t.title.isEmpty()) {
                    NeteaseSong sd = netease.songDetail(songId);
                    if (sd != null) {
                        t.title = sd.name;
                        t.artist = sd.artist;
                        t.album = sd.album;
                        t.coverUrl = sd.coverUrl;
                        t.durationMs = sd.durationMs;
                    }
                }
                String url = netease.songUrl(songId, playLevel);
                NeteaseLyric nl = netease.lyric(songId);
                Logger.info("netease: url={}", url);
                List<LyricLine> ly = nl == null ? Collections.<LyricLine>emptyList()
                        : LyricParser.fromNeteaseStrings(nl.yrc, nl.lrc, nl.tlyric, nl.romalrc);
                post(() -> {
                    if (index.peek() != expectedIndex) return; // user moved on
                    if (url == null) {
                        Logger.warn("netease song {} has no url (blocked/VIP/login required)", songId);
                        toast.set(netease.isLoggedIn() ? "无法播放：VIP/灰色歌曲" : "无法播放：请先登录");
                        return;
                    }
                    t.streamUrl = url;
                    title.set(orEmpty(t.title));
                    artist.set(orEmpty(t.artist));
                    album.set(orEmpty(t.album));
                    coverUrl.set(orEmpty(t.coverUrl));
                    durationMs.set(t.durationMs);
                    lyrics.set(ly);
                    Logger.info("play netease: {} — {}", t.title, url);
                    backend.play(url, 0L);
                    playing.set(true);
                });
            } catch (Throwable e) {
                Logger.warn("netease resolve failed for {}: {}", songId, e.getMessage());
            }
        });
    }

    private void loadLocalLyrics(Track t) {
        if (t.lyricFilePath != null) {
            try {
                lyrics.set(LyricParser.parse(t.lyricFilePath, t.translationFilePath, t.romajiFilePath));
                return;
            } catch (Throwable e) {
                Logger.warn("lyric parse failed: {}", e.getMessage());
            }
        }
        lyrics.set(Collections.<LyricLine>emptyList());
    }

    private static Track toTrack(NeteaseSong s) {
        Track t = new Track();
        t.source = Track.Source.NETEASE;
        t.neteaseId = s.id;
        t.title = s.name;
        t.artist = s.artist;
        t.album = s.album;
        t.coverUrl = s.coverUrl;
        t.durationMs = s.durationMs;
        return t;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // --- Netease discovery ------------------------------------------------

    /** Search and publish to {@link #searchResults}. */
    public void search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        worker.submit(() -> {
            try {
                List<NeteaseSong> r = netease.searchSongs(keyword, 30, 0);
                post(() -> {
                    searchResults.set(r);
                    resultCount.set(r.size());
                });
            } catch (Throwable e) {
                Logger.warn("search failed: {}", e.getMessage());
            }
        });
    }

    /** Load the home content: recommended songs (login) + recommended playlists. */
    public void loadHome() {
        worker.submit(() -> {
            try {
                List<NeteasePlaylist> picks = netease.personalizedPlaylists(12);
                post(() -> recommendPlaylists.set(picks));
            } catch (Throwable e) {
                Logger.warn("personalized playlists failed: {}", e.getMessage());
            }
            if (netease.isLoggedIn()) {
                try {
                    List<NeteaseSong> daily = netease.recommendSongs();
                    post(() -> recommendations.set(daily));
                } catch (Throwable e) {
                    Logger.warn("daily recommend failed: {}", e.getMessage());
                }
            }
        });
    }

    /** Open a playlist: detail (name) + its tracks. */
    public void openPlaylist(long playlistId) {
        worker.submit(() -> {
            try {
                NeteasePlaylist detail = netease.playlistDetail(playlistId);
                List<NeteaseSong> songs = netease.playlistTracks(playlistId, 500);
                String name = detail != null ? detail.name : "";
                post(() -> {
                    playlistTitle.set(name == null ? "" : name);
                    playlistTracks.set(songs);
                });
            } catch (Throwable e) {
                Logger.warn("open playlist {} failed: {}", playlistId, e.getMessage());
            }
        });
    }

    /** Load the signed-in user's playlists (favorites + created). */
    public void loadMyPlaylists() {
        if (uid == 0) return;
        worker.submit(() -> {
            try {
                List<NeteasePlaylist> pls = netease.userPlaylists(uid, 100);
                post(() -> myPlaylists.set(pls));
            } catch (Throwable e) {
                Logger.warn("user playlists failed: {}", e.getMessage());
            }
        });
    }

    /** Recently played (netease listen history). */
    public void loadRecent() {
        if (uid == 0) return;
        worker.submit(() -> {
            try {
                List<NeteaseSong> rec = netease.userRecord(uid, 0);
                post(() -> recentSongs.set(rec));
            } catch (Throwable e) {
                Logger.warn("recent failed: {}", e.getMessage());
            }
        });
    }

    private void refreshLiked() {
        if (uid == 0) return;
        worker.submit(() -> {
            try {
                Set<Long> ids = netease.likedSongIds(uid);
                post(() -> {
                    likedSet.clear();
                    likedSet.addAll(ids);
                    Track cur = currentTrack();
                    currentLiked.set(cur != null && likedSet.contains(cur.neteaseId));
                });
            } catch (Throwable e) {
                Logger.warn("liked ids failed: {}", e.getMessage());
            }
        });
    }

    /** Like / unlike the current netease track. */
    public void toggleLike() {
        Track cur = currentTrack();
        if (cur == null || cur.neteaseId == 0) return;
        long id = cur.neteaseId;
        boolean target = !likedSet.contains(id);
        worker.submit(() -> {
            try {
                boolean ok = netease.like(id, target);
                if (ok) {
                    post(() -> {
                        if (target) likedSet.add(id);
                        else likedSet.remove(id);
                        Track c = currentTrack();
                        if (c != null && c.neteaseId == id) currentLiked.set(target);
                    });
                }
            } catch (Throwable e) {
                Logger.warn("like toggle failed: {}", e.getMessage());
            }
        });
    }

    private Track currentTrack() {
        int i = index.peek();
        return i >= 0 && i < queue.size() ? queue.get(i) : null;
    }

    // --- Login ------------------------------------------------------------

    private volatile String pendingUnikey;

    /** QR payload to encode for login (the QML dialog draws it via {@link #qrMatrix()}). */
    public String qrLoginContent() {
        try {
            String key = netease.qrLoginKey();
            pendingUnikey = key;
            return netease.qrLoginContent(key);
        } catch (Throwable e) {
            Logger.warn("qrLoginContent failed: {}", e.getMessage());
            return "";
        }
    }

    /** QR module matrix ({@code true}=dark) for the current login key, as nested
     *  Lists so QML can index it (.length / [y][x]); null if unavailable. */
    public List<List<Boolean>> qrMatrix() {
        String key = pendingUnikey;
        boolean[][] m = key == null ? null : netease.qrMatrix(key);
        if (m == null) return null;
        List<List<Boolean>> out = new ArrayList<>(m.length);
        for (boolean[] row : m) {
            List<Boolean> r = new ArrayList<>(row.length);
            for (boolean b : row) r.add(b);
            out.add(r);
        }
        return out;
    }

    /** Poll QR status: 800 expired / 801 waiting / 802 scanned / 803 success. */
    public int qrLoginCheck() {
        String key = pendingUnikey;
        if (key == null) return 800;
        try {
            int code = netease.qrLoginCheck(key);
            if (code == 803) refreshLogin();
            return code;
        } catch (Throwable e) {
            return 801;
        }
    }

    private void refreshLogin() {
        worker.submit(() -> {
            try {
                long id = netease.loginUid();
                NeteaseUser u = id > 0 ? netease.userDetail(id) : null;
                boolean in = netease.isLoggedIn();
                String name = u != null ? u.nickname : "";
                post(() -> {
                    uid = id;
                    loggedIn.set(in);
                    userName.set(name == null ? "" : name);
                });
                if (in) {
                    loadHome();
                    loadMyPlaylists();
                    refreshLiked();
                }
            } catch (Throwable e) {
                Logger.warn("refreshLogin failed: {}", e.getMessage());
            }
        });
    }

    public void logout() {
        netease.logout();
        uid = 0;
        loggedIn.set(false);
        userName.set("");
        likedSet.clear();
        myPlaylists.set(Collections.<NeteasePlaylist>emptyList());
        recommendations.set(Collections.<NeteaseSong>emptyList());
        recentSongs.set(Collections.<NeteaseSong>emptyList());
    }

    public void shutdown() {
        backend.release();
        worker.shutdownNow();
    }
}
