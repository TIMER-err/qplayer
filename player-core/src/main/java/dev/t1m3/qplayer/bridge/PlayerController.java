package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.library.LibraryScanner;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricParser;
import dev.t1m3.qplayer.lyric.TtmlParser;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.netease.dto.NeteaseLyric;
import dev.t1m3.qplayer.netease.dto.NeteasePlaylist;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.netease.dto.NeteaseUser;
import dev.t1m3.qplayer.util.Logger;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.runtime.color.StyleManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
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
    private volatile ColorExtractor colorExtractor;
    private volatile boolean monetEnabled = true;
    private static final String DEFAULT_SEED = "#6750A4";
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qplayer-net");
        t.setDaemon(true);
        return t;
    });

    private final List<Track> library = new CopyOnWriteArrayList<>();
    private final List<Track> queue = new CopyOnWriteArrayList<>();
    private final Queue<Runnable> uiQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> likedSet = new HashSet<>();
    private final Random rng = new Random();

    private volatile String playLevel = "exhigh";
    private volatile long uid;
    private long lastPositionPush;
    private long lastLogVersion = -1;
    private volatile boolean logVisible = false;

    // --- Playback state ---------------------------------------------------
    public final Property<Boolean> playing = new Property<>(false);
    public final Property<String> title = new Property<>("");
    public final Property<String> artist = new Property<>("");
    public final Property<String> album = new Property<>("");
    public final Property<String> coverUrl = new Property<>("");
    /** Cover image bytes of the current track (local embedded or downloaded),
     *  for the host-drawn fluid lyric backdrop. Null until available. */
    public final Property<byte[]> coverBytes = new Property<>(null);
    /** Material You seed color ("#rrggbb") derived from the current cover, or ""
     *  when none. QML feeds it into StyleManager.seedColor when Monet is enabled. */
    public final Property<String> coverSeed = new Property<>("");
    public final Property<Long> durationMs = new Property<>(0L);
    public final Property<Long> positionMs = new Property<>(0L);
    public final Property<Integer> index = new Property<>(-1);
    public final Property<Float> volume = new Property<>(0.8f);
    public final Property<Boolean> currentLiked = new Property<>(false);
    // 0 = list loop (default, current behaviour), 1 = shuffle, 2 = repeat one.
    public final Property<Integer> playMode = new Property<>(0);
    public final Property<List<LyricLine>> lyrics = new Property<>(Collections.<LyricLine>emptyList());
    /** Index of the current lyric line for player.positionMs, or -1. */
    public final Property<Integer> lyricIndex = new Property<>(-1);
    /** Whether the full-screen lyric page is open (host draws it via Skija). */
    public final Property<Boolean> lyricsOpen = new Property<>(false);
    /** Host-published lyric-overlay slide progress (0 closed .. 1 open); the QML
     *  LyricOverlay chrome fades with it in lockstep with the host lyric layer. */
    public final Property<Double> lyricSlide = new Property<>(0.0);
    /** Host-published playback fraction (0..1) for the lyric page progress bar, set
     *  every frame from the live position so the wavy bar advances smoothly (the 5 Hz
     *  positionMs would step it). */
    public final Property<Double> lyricProgress = new Property<>(0.0);

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
    /** True while an opened playlist's tracks are loading, so the detail page shows a
     *  spinner instead of the previous playlist's content. */
    public final Property<Boolean> playlistLoading = new Property<>(false);
    /** Snapshot of the live play queue for the queue page; current track is {@link #index}. */
    public final Property<List<Track>> queueTracks = new Property<>(Collections.<Track>emptyList());
    public final Property<Boolean> queueOpen = new Property<>(false);

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
        backend.setOnComplete(() -> post(this::autoAdvance));
        if (netease.isLoggedIn()) {
            loggedIn.set(true);
            refreshLogin();
        }
    }

    /** Platform color extractor for Monet seeds; set once at startup. */
    public void setColorExtractor(ColorExtractor extractor) {
        this.colorExtractor = extractor;
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
                long pos = backend.position();
                positionMs.set(pos);
                updateLyricIndex(pos);
            }
        }
        // Rebuild the debug log text only while the overlay is open. Otherwise every
        // log line (e.g. the ~2 s frame-profiler summary) rebuilt the string and called
        // logText.set, whose version bump forced a whole-tree relayout -- a periodic
        // stutter even with the log closed.
        if (logVisible) {
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
    }

    /** The debug log overlay's visibility; gates the per-frame logText rebuild. */
    public void setLogVisible(boolean visible) {
        this.logVisible = visible;
        if (visible) lastLogVersion = -1;
    }

    private void updateLyricIndex(long pos) {
        List<LyricLine> ly = lyrics.peek();
        int idx = -1;
        for (int i = 0; i < ly.size(); i++) {
            if (ly.get(i).startMs() <= pos) idx = i;
            else break;
        }
        if (idx != lyricIndex.peek()) lyricIndex.set(idx);
    }

    private void post(Runnable r) {
        uiQueue.add(r);
    }

    public void clearLog() {
        Logger.clear();
    }

    public void setLyricsOpen(boolean open) {
        lyricsOpen.set(open);
    }

    public void setQueueOpen(boolean open) {
        queueOpen.set(open);
    }

    /** Jump to a slot in the live queue (queue-page tap). */
    public void playQueueIndex(int i) {
        playAt(i);
    }

    /** Drop a slot from the queue; keep playing the right track. */
    public void removeFromQueue(int i) {
        if (i < 0 || i >= queue.size()) return;
        int cur = index.peek();
        queue.remove(i);
        queueTracks.set(new ArrayList<>(queue));
        if (queue.isEmpty()) {
            index.set(-1);
            return;
        }
        if (i < cur) {
            index.set(cur - 1);
        } else if (i == cur) {
            playAt(Math.min(cur, queue.size() - 1));
        }
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
        queueTracks.set(new ArrayList<>(queue));
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
        updateCover(t, i);
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

    /** Feed coverBytes for the fluid backdrop: local tracks carry embedded
     *  bytes; NETEASE tracks download lazily off-thread, keyed by queue index
     *  so a stale fetch for a skipped-past track is dropped. */
    private void updateCover(Track t, int expectedIndex) {
        if (t.coverBytes != null) {
            applyCover(t.coverBytes);
            return;
        }
        applyCover(null);
        final String url = t.coverUrl;
        if (url == null || url.isEmpty()) return;
        worker.submit(() -> {
            byte[] data = downloadBytes(url);
            if (data == null) return;
            t.coverBytes = data;
            post(() -> {
                if (index.peek() == expectedIndex) applyCover(data);
            });
        });
    }

    /** Push cover bytes (render thread) and kick off Monet seed extraction off it. */
    private void applyCover(byte[] data) {
        coverBytes.set(data);
        final ColorExtractor ex = colorExtractor;
        if (ex == null) return;
        // No cover yet (a netease track's art is still downloading): keep the previous
        // seed so the theme doesn't flash back to the default purple between songs. The
        // new cover's seed replaces it directly once extracted.
        if (data == null) return;
        worker.submit(() -> {
            // Cover bytes are untrusted (downloaded); a bad image must not kill the worker.
            String hex;
            try {
                hex = ex.dominantHex(data);
            } catch (Throwable e) {
                return;
            }
            if (hex != null) post(() -> { coverSeed.set(hex); reapplySeed(); });
        });
    }

    /** Toggle Monet dynamic color; re-applies the seed (render thread). */
    public void setMonetEnabled(boolean enabled) {
        this.monetEnabled = enabled;
        post(this::reapplySeed);
    }

    /** Push the effective seed into StyleManager: the cover seed when Monet is on and
     *  one exists, else the default. Driven from Java because a QML Binding on
     *  StyleManager.seedColor did not re-fire on coverSeed changes. Render thread. */
    private void reapplySeed() {
        String s = coverSeed.peek();
        String seed = (monetEnabled && s != null && !s.isEmpty()) ? s : DEFAULT_SEED;
        StyleManager sm = (StyleManager) StyleManager.__instance();
        sm.seedColor.set(seed);
    }

    /** Community AMLL TTML mirror: syllable-level lyrics with background-vocal
     *  and duet annotations. Empty list on 404 / network failure / parse error,
     *  which signals the caller to fall back to Netease's own lyric. */
    private static List<LyricLine> tryAmllTtml(long songId) {
        byte[] data = downloadBytes("https://amlldb.bikonoo.com/ncm-lyrics/" + songId + ".ttml");
        if (data == null || data.length == 0) return Collections.emptyList();
        String ttml = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        if (ttml.trim().isEmpty()) return Collections.emptyList();
        try {
            return TtmlParser.parse(ttml);
        } catch (Throwable e) {
            Logger.warn("ttml parse failed for {}: {}", songId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private static byte[] downloadBytes(String url) {
        try {
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "qplayer/1.0");
            try (java.io.InputStream in = c.getInputStream();
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Throwable e) {
            Logger.warn("cover fetch failed: {}", e.getMessage());
            return null;
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

    /** Cycle list-loop -> shuffle -> repeat-one -> list-loop. */
    public void cyclePlayMode() {
        playMode.set((playMode.peek() + 1) % 3);
    }

    // A different queue slot than the current one (shuffle never repeats a track
    // back-to-back unless the queue has a single entry).
    private int randomIndex() {
        int n = queue.size();
        if (n <= 1) return 0;
        int r;
        do {
            r = rng.nextInt(n);
        } while (r == index.peek());
        return r;
    }

    // Manual skip: shuffle picks a random slot, otherwise step forward and wrap.
    // Repeat-one only affects auto-advance -- a manual press still moves on.
    public void next() {
        if (queue.isEmpty()) return;
        playAt(playMode.peek() == 1 ? randomIndex() : (index.peek() + 1) % queue.size());
    }

    public void prev() {
        if (queue.isEmpty()) return;
        int n = queue.size();
        playAt(playMode.peek() == 1 ? randomIndex() : (index.peek() - 1 + n) % n);
    }

    // Track finished on its own: repeat-one replays it, shuffle jumps randomly,
    // list-loop advances. Wired to backend.onComplete (not next()) so repeat-one
    // doesn't fight a user's manual skip.
    private void autoAdvance() {
        if (queue.isEmpty()) return;
        switch (playMode.peek()) {
            case 2:
                playAt(index.peek());
                break;
            case 1:
                playAt(randomIndex());
                break;
            default:
                playAt((index.peek() + 1) % queue.size());
                break;
        }
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
                Logger.info("netease: url={}", url);
                // Prefer the AMLL TTML mirror (full syllable + bg/duet metadata);
                // fall back to Netease's own lyric (YRC if present, else LRC).
                List<LyricLine> ttml = tryAmllTtml(songId);
                if (ttml.isEmpty()) {
                    NeteaseLyric nl = netease.lyric(songId);
                    ttml = nl == null ? Collections.<LyricLine>emptyList()
                            : LyricParser.fromNeteaseStrings(nl.yrc, nl.lrc, nl.tlyric, nl.romalrc);
                }
                final List<LyricLine> ly = ttml;
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
                    updateCover(t, expectedIndex);
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
        // Called on the render thread from QML: clear the previous playlist and show
        // the spinner immediately, before the off-thread fetch starts.
        playlistLoading.set(true);
        playlistTracks.set(Collections.<NeteaseSong>emptyList());
        playlistTitle.set("");
        worker.submit(() -> {
            try {
                NeteasePlaylist detail = netease.playlistDetail(playlistId);
                List<NeteaseSong> songs = netease.playlistTracks(playlistId, 500);
                String name = detail != null ? detail.name : "";
                post(() -> {
                    playlistTitle.set(name == null ? "" : name);
                    playlistTracks.set(songs);
                    playlistLoading.set(false);
                });
            } catch (Throwable e) {
                Logger.warn("open playlist {} failed: {}", playlistId, e.getMessage());
                post(() -> playlistLoading.set(false));
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

    // --- Login (fully async: qrLoginKey/qrLoginCheck are blocking HTTP, must
    //     never run on the render thread the QML handlers call from) ----------

    private volatile String pendingUnikey;
    /** QR module matrix (true=dark) as nested Lists so QML can index [y][x]. */
    public final Property<List<List<Boolean>>> qrImage =
            new Property<>(Collections.<List<Boolean>>emptyList());
    /** 0 loading / 800 expired / 801 waiting / 802 scanned / 803 success. */
    public final Property<Integer> qrStatus = new Property<>(0);

    /** Mint a login key + matrix off-thread; publishes to {@link #qrImage}/{@link #qrStatus}. */
    public void startQrLogin() {
        post(() -> qrStatus.set(0));
        worker.submit(() -> {
            try {
                String key = netease.qrLoginKey();
                pendingUnikey = key;
                List<List<Boolean>> m = toMatrix(netease.qrMatrix(key));
                post(() -> {
                    qrImage.set(m);
                    qrStatus.set(801);
                });
            } catch (Throwable e) {
                Logger.warn("startQrLogin failed: {}", e.getMessage());
                post(() -> qrStatus.set(800));
            }
        });
    }

    /** Poll the scan status off-thread; updates {@link #qrStatus}. */
    public void pollQrLogin() {
        String key = pendingUnikey;
        if (key == null) return;
        worker.submit(() -> {
            try {
                int code = netease.qrLoginCheck(key);
                post(() -> qrStatus.set(code));
                if (code == 803) refreshLogin();
                else if (code == 800) startQrLogin();
            } catch (Throwable e) {
                // transient network blip — keep waiting
            }
        });
    }

    private static List<List<Boolean>> toMatrix(boolean[][] m) {
        if (m == null) return Collections.emptyList();
        List<List<Boolean>> out = new ArrayList<>(m.length);
        for (boolean[] row : m) {
            List<Boolean> r = new ArrayList<>(row.length);
            for (boolean b : row) r.add(b);
            out.add(r);
        }
        return out;
    }

    private void refreshLogin() {
        worker.submit(() -> {
            try {
                long id = netease.loginUid();
                NeteaseUser u = id > 0 ? netease.userDetail(id) : null;
                boolean in = netease.isLoggedIn();
                String name = u != null ? u.nickname : "";
                // uid is a plain volatile field (not a Property), so set it here on
                // the worker thread -- refreshLiked() runs synchronously right below
                // and reads uid; deferring it via post() left uid == 0 there, so the
                // liked set never loaded and the like button never lit.
                uid = id;
                post(() -> {
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
