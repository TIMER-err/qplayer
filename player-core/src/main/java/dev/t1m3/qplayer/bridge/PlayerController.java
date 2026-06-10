package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.library.LibraryScanner;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricParser;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.netease.dto.NeteaseLyric;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.netease.dto.NeteaseUser;
import dev.t1m3.qplayer.util.Logger;
import io.github.timer_err.qml4j.engine.binding.Property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
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
 * <h3>Threading</h3>
 * The qml4j renderer is single-threaded, so every {@code Property.set} must
 * happen on the render thread. Public methods invoked from QML already run
 * there and mutate Properties directly. Work that must run off the render
 * thread — audio completion callbacks, blocking netease HTTP — instead posts
 * its result via {@link #post(Runnable)}; the host drains the queue once per
 * frame by calling {@link #pump()} just before {@code renderFrame}.
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
    private final Queue<Runnable> uiQueue = new ConcurrentLinkedQueue<>();

    /** Netease stream quality (see {@link NeteaseClient#songUrl}). */
    private volatile String playLevel = "exhigh";
    private long lastPositionPush;

    // --- Reactive state for QML bindings ---------------------------------
    public final Property<Boolean> playing = new Property<>(false);
    public final Property<String> title = new Property<>("");
    public final Property<String> artist = new Property<>("");
    public final Property<String> album = new Property<>("");
    public final Property<Long> durationMs = new Property<>(0L);
    public final Property<Long> positionMs = new Property<>(0L);
    public final Property<Integer> index = new Property<>(-1);
    public final Property<Float> volume = new Property<>(0.8f);

    /** Current library (local + transient netease). Bumped on any change. */
    public final Property<List<Track>> tracks = new Property<>(Collections.<Track>emptyList());
    /** Parsed lyric lines for the current track (syllable-level when available). */
    public final Property<List<LyricLine>> lyrics = new Property<>(Collections.<LyricLine>emptyList());
    /** Latest netease search results. */
    public final Property<List<NeteaseSong>> searchResults = new Property<>(Collections.<NeteaseSong>emptyList());

    public final Property<Boolean> loggedIn = new Property<>(false);
    public final Property<String> userName = new Property<>("");

    public PlayerController(AudioBackend backend, MetadataReader metadataReader) {
        this(backend, metadataReader, NeteaseClient.INSTANCE);
    }

    public PlayerController(AudioBackend backend, MetadataReader metadataReader, NeteaseClient netease) {
        this.backend = backend;
        this.metadataReader = metadataReader;
        this.netease = netease;
        backend.setVolume(volume.peek());
        // Audio completion fires on the audio thread — marshal to the UI thread.
        backend.setOnComplete(() -> post(this::next));
        loggedIn.set(netease.isLoggedIn());
    }

    // --- Frame pump (render thread) --------------------------------------

    /** Drain queued UI mutations and refresh the play head. Call once per frame. */
    public void pump() {
        Runnable r;
        while ((r = uiQueue.poll()) != null) {
            try {
                r.run();
            } catch (Throwable e) {
                Logger.exception(e);
            }
        }
        // Throttle the position push so a bound progress bar re-evaluates a few
        // times a second, not every frame.
        long now = System.currentTimeMillis();
        if (now - lastPositionPush >= 200L) {
            lastPositionPush = now;
            if (backend.isPlaying()) {
                positionMs.set(backend.position());
            }
        }
    }

    /** Enqueue a mutation to run on the next {@link #pump()} (render thread). */
    private void post(Runnable r) {
        uiQueue.add(r);
    }

    // --- Library ----------------------------------------------------------

    /** Recursively scan a folder for local audio; updates {@link #tracks} when done. */
    public void scan(String folder) {
        worker.submit(() -> {
            try {
                LibraryScanner scanner = new LibraryScanner(metadataReader);
                List<Track> found = scanner.scan(folder);
                post(() -> setLibrary(found));
            } catch (Throwable e) {
                Logger.exception(e);
            }
        });
    }

    private void setLibrary(List<Track> found) {
        library.clear();
        library.addAll(found);
        tracks.set(new ArrayList<>(library));
    }

    public int trackCount() {
        return library.size();
    }

    // --- Playback control (invoked from QML, render thread) ---------------

    public void play(int i) {
        if (i < 0 || i >= library.size()) return;
        selectAndPlay(i, library.get(i));
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
        if (library.isEmpty()) return;
        play((index.peek() + 1) % library.size());
    }

    public void prev() {
        if (library.isEmpty()) return;
        int n = library.size();
        play((index.peek() - 1 + n) % n);
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

    private void selectAndPlay(int i, Track t) {
        index.set(i);
        title.set(orEmpty(t.title));
        artist.set(orEmpty(t.artist));
        album.set(orEmpty(t.album));
        durationMs.set(t.durationMs);
        positionMs.set(0L);
        loadLyrics(t);
        String src = t.playable();
        if (src == null && t.source == Track.Source.NETEASE) {
            // Stream url not yet resolved — fetch then play.
            fetchAndPlayNetease(t.neteaseId);
            return;
        }
        backend.play(src, 0L);
        playing.set(true);
    }

    private void loadLyrics(Track t) {
        if (t.source == Track.Source.LOCAL && t.lyricFilePath != null) {
            try {
                lyrics.set(LyricParser.parse(t.lyricFilePath, t.translationFilePath, t.romajiFilePath));
                return;
            } catch (Throwable e) {
                Logger.warn("lyric parse failed: {}", e.getMessage());
            }
        }
        lyrics.set(Collections.<LyricLine>emptyList());
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // --- Netease ----------------------------------------------------------

    public void setPlayLevel(String level) {
        if (level != null && !level.isEmpty()) playLevel = level;
    }

    /** Search netease and publish results to {@link #searchResults}. */
    public void search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        worker.submit(() -> {
            try {
                List<NeteaseSong> r = netease.searchSongs(keyword, 30, 0);
                post(() -> searchResults.set(r));
            } catch (Throwable e) {
                Logger.warn("search failed: {}", e.getMessage());
            }
        });
    }

    /** Append a netease song to the library and start streaming it. */
    public void playNetease(long songId) {
        fetchAndPlayNetease(songId);
    }

    private void fetchAndPlayNetease(long songId) {
        worker.submit(() -> {
            try {
                NeteaseSong sd = netease.songDetail(songId);
                String url = netease.songUrl(songId, playLevel);
                NeteaseLyric nl = netease.lyric(songId);
                if (url == null) {
                    Logger.warn("netease song {} has no playable url (blocked/VIP)", songId);
                    return;
                }
                Track t = new Track();
                t.source = Track.Source.NETEASE;
                t.neteaseId = songId;
                t.streamUrl = url;
                if (sd != null) {
                    t.title = sd.name;
                    t.artist = sd.artist;
                    t.album = sd.album;
                    t.coverUrl = sd.coverUrl;
                    t.durationMs = sd.durationMs;
                }
                List<LyricLine> ly = nl == null
                        ? Collections.<LyricLine>emptyList()
                        : LyricParser.fromNeteaseStrings(nl.yrc, nl.lrc, nl.tlyric, nl.romalrc);
                post(() -> addAndPlayNetease(t, ly));
            } catch (Throwable e) {
                Logger.warn("netease play failed for {}: {}", songId, e.getMessage());
            }
        });
    }

    /** UI thread: insert the resolved netease track and begin playback. */
    private void addAndPlayNetease(Track t, List<LyricLine> ly) {
        int last = library.size() - 1;
        int idx;
        if (last >= 0 && library.get(last).source == Track.Source.NETEASE
                && library.get(last).neteaseId == t.neteaseId && t.neteaseId != 0L) {
            library.set(last, t);
            idx = last;
        } else {
            library.add(t);
            idx = library.size() - 1;
        }
        tracks.set(new ArrayList<>(library));
        index.set(idx);
        title.set(orEmpty(t.title));
        artist.set(orEmpty(t.artist));
        album.set(orEmpty(t.album));
        durationMs.set(t.durationMs);
        positionMs.set(0L);
        lyrics.set(ly);
        backend.play(t.streamUrl, 0L);
        playing.set(true);
    }

    // --- Login ------------------------------------------------------------

    /** QR payload to encode for login (drawn by the QML login dialog). */
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

    private volatile String pendingUnikey;

    /** The QR module matrix for the current login key, or null. */
    public boolean[][] qrMatrix() {
        String key = pendingUnikey;
        return key == null ? null : netease.qrMatrix(key);
    }

    /** Poll QR scan status: 800 expired / 801 waiting / 802 scanned / 803 success. */
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
                long uid = netease.loginUid();
                NeteaseUser u = uid > 0 ? netease.userDetail(uid) : null;
                boolean in = netease.isLoggedIn();
                String name = u != null ? u.nickname : "";
                post(() -> {
                    loggedIn.set(in);
                    userName.set(name == null ? "" : name);
                });
            } catch (Throwable e) {
                Logger.warn("refreshLogin failed: {}", e.getMessage());
            }
        });
    }

    public void logout() {
        netease.logout();
        loggedIn.set(false);
        userName.set("");
    }

    public void shutdown() {
        backend.release();
        worker.shutdownNow();
    }
}
