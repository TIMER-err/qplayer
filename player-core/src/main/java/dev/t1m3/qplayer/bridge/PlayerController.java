package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.cache.DiskCache;
import dev.t1m3.qplayer.library.LibraryScanner;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricParser;
import dev.t1m3.qplayer.lyric.TtmlParser;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.netease.dto.NeteaseLyric;
import dev.t1m3.qplayer.unblock.SongUnblocker;
import dev.t1m3.qplayer.netease.dto.NeteasePlaylist;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.netease.dto.NeteaseUser;
import dev.t1m3.qplayer.util.Logger;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.runtime.color.StyleManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Unified disk cache (audio / lyrics / images) with LRU eviction. */
    public final DiskCache diskCache = new DiskCache(200); // default 200 MB

    private final List<Track> library = new CopyOnWriteArrayList<>();
    private final List<Track> queue = new CopyOnWriteArrayList<>();
    private final Queue<Runnable> uiQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> likedSet = new HashSet<>();
    private final Random rng = new Random();

    // Playback control runs on the host's main thread (always alive — unlike the GL
    // render thread, which pauses in the background and would stall auto-advance);
    // UI Property writes still marshal to the render thread via post()/pump().
    // mainExec is null on hosts with no main loop (desktop), where control runs inline.
    private volatile java.util.concurrent.Executor mainExec;
    // Source-of-truth play position. The `index` Property mirrors it for the UI but
    // lags in the background (pump paused), so all playback logic uses this instead.
    private volatile int playIndex = -1;
    // Intended play/pause state. The backend (MediaPlayer) prepares asynchronously, so
    // backend.isPlaying() is briefly false right after play() — reporting that to the
    // media session shows a stale "paused". The session uses this intent instead.
    private volatile boolean playingIntent = false;
    private volatile PlaybackListener playbackListener;

    /** Host hook (e.g. the Android foreground service) notified on the main thread
     *  whenever the current track or play/pause state changes, so it can refresh the
     *  media session + notification. */
    public interface PlaybackListener {
        void onPlaybackChanged();
    }

    public void setMainExecutor(java.util.concurrent.Executor e) {
        this.mainExec = e;
    }

    public void setPlaybackListener(PlaybackListener l) {
        this.playbackListener = l;
    }

    /** Run playback control on the main thread (inline if the host has no executor). */
    private void onMain(Runnable r) {
        java.util.concurrent.Executor e = mainExec;
        if (e != null) e.execute(r);
        else r.run();
    }

    private void notifyPlayback() {
        PlaybackListener l = playbackListener;
        if (l != null) onMain(l::onPlaybackChanged);
    }

    // --- Search cache ------------------------------------------------------
    /** TTL for cached search results: 5 minutes. */
    private static final long SEARCH_CACHE_TTL_MS = 5 * 60 * 1000L;
    /** Max number of search results to keep in memory (LRU eviction). */
    private static final int SEARCH_CACHE_MAX_SIZE = 20;
    /** Bounded LRU cache: evicts oldest entry when capacity is reached. */
    @SuppressWarnings("serial")
    private final Map<String, CacheEntry> searchCache =
            Collections.synchronizedMap(new LinkedHashMap<String, CacheEntry>(
                    SEARCH_CACHE_MAX_SIZE + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > SEARCH_CACHE_MAX_SIZE;
                }
            });

    /** Holds a cached search result with its creation timestamp. */
    private static final class CacheEntry {
        final List<NeteaseSong> songs;
        final long timestamp;
        CacheEntry(List<NeteaseSong> songs) {
            this.songs = songs;
            this.timestamp = System.currentTimeMillis();
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > SEARCH_CACHE_TTL_MS;
        }
    }

    private volatile String playLevel = "exhigh";
    private volatile boolean unblockEnabled = true;
    private volatile long uid;
    // neteaseId of the track we last re-resolved after a playback error; cleared
    // when a track actually starts. Stops a persistently-failing track from looping
    // error→re-resolve→error forever instead of advancing.
    private volatile long errorRetryId = -1;
    private long lastPositionPush;
    private long lastLogVersion = -1;
    private volatile boolean logVisible = false;

    // --- Playback state ---------------------------------------------------
    public final Property<Boolean> playing = new Property<>(false);
    public final Property<String> title = new Property<>("");
    public final Property<String> artist = new Property<>("");
    public final Property<String> album = new Property<>("");
    public final Property<String> coverUrl = new Property<>("");
    /** Absolute path to the current track's cover in the disk cache, or "" when not
     *  cached. QML prefers it over {@link #coverUrl} so the now-playing art shows with
     *  no network (the asset loader is file-aware via FileResourceLoader). */
    public final Property<String> coverPath = new Property<>("");
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
    /** Whether the current track can be liked — netease only; local files have no
     *  server-side "我喜欢的音乐", so the player's like button binds enabled to this. */
    public final Property<Boolean> currentLikeable = new Property<>(false);
    // 0 = list loop (default, current behaviour), 1 = shuffle, 2 = repeat one.
    public final Property<Integer> playMode = new Property<>(0);
    public final Property<List<LyricLine>> lyrics = new Property<>(Collections.<LyricLine>emptyList());
    /** Cover-centered layout flag for the lyric page: true when there are no lyrics, or
     *  it's an instrumental ("纯音乐") track with fewer than 3 lines. Both the QML chrome
     *  (centers the cover) and the host compositor (drops the side lyric column in
     *  landscape) read it. */
    public final Property<Boolean> lyricsCoverOnly = new Property<>(Boolean.TRUE);
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
    /** Current disk cache usage in MB (updated after each cache write). */
    public final Property<Long> cacheSizeMB = new Property<>(0L);

    // --- Local library ----------------------------------------------------
    public final Property<List<Track>> tracks = new Property<>(Collections.<Track>emptyList());
    public final Property<Integer> libraryCount = new Property<>(0);

    // --- Netease content (Repeater model: player.xxx; delegate reads modelData) ---
    public final Property<List<NeteaseSong>> searchResults = new Property<>(Collections.<NeteaseSong>emptyList());
    public final Property<Integer> resultCount = new Property<>(0);
    /** Hot search keywords shown when search input is empty. */
    public final Property<List<String>> hotSearches = new Property<>(Collections.<String>emptyList());
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
    /** Id of the currently open playlist (0 = none); guards stale async results. */
    private volatile long currentPlaylistId;
    /** Whether the signed-in user has collected the open playlist — drives the detail
     *  page's collect icon. Resolved from playlist/detail, so it reflects the real state
     *  on open (no guessing). */
    public final Property<Boolean> playlistSubscribed = new Property<>(false);
    /** Whether the open playlist is the user's own (can't collect your own). */
    public final Property<Boolean> playlistOwned = new Property<>(false);
    /** Snapshot of the live play queue for the queue page; current track is {@link #index}. */
    public final Property<List<Track>> queueTracks = new Property<>(Collections.<Track>emptyList());
    public final Property<Boolean> queueOpen = new Property<>(false);

    // --- Account ----------------------------------------------------------
    public final Property<Boolean> loggedIn = new Property<>(false);
    public final Property<String> userName = new Property<>("");
    /** Square avatar URL; the QML Image fetches + decodes it off-thread. */
    public final Property<String> userAvatar = new Property<>("");
    /** 0 = free, 10/11 = VIP. */
    public final Property<Integer> userVipType = new Property<>(0);
    /** Account level (roughly 1-10). */
    public final Property<Integer> userLevel = new Property<>(0);
    public final Property<String> userSignature = new Property<>("");
    /** Counts for the account page header stats. Kept in sync with the
     *  liked-id set and the my-playlists list so QML binds an int, not a
     *  Java List length (which the engine doesn't expose to QML). */
    public final Property<Integer> likedCount = new Property<>(0);
    public final Property<Integer> playlistCount = new Property<>(0);

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
        // Surface any netease failure reason (private playlist, risk control, ...) as a
        // toast, same Snackbar the auto-source notice uses. Fires on a worker thread, so
        // hop to the render thread to touch the Property.
        netease.setErrorListener(msg -> post(() -> toast.set(msg)));
        backend.setVolume(volume.peek());
        backend.setOnComplete(() -> onMain(this::autoAdvance));
        // Re-baseline the media session's position once audio actually starts (the
        // backend prepares asynchronously, so the position at play() time is stale).
        backend.setOnStarted(() -> { errorRetryId = -1; notifyPlayback(); });
        // Audio-focus driven pause/resume (phone call, another player): keep the
        // intended-play state, the UI, and the media session in sync.
        backend.setOnPaused(() -> {
            playingIntent = false;
            post(() -> playing.set(false));
            notifyPlayback();
        });
        backend.setOnResumed(() -> {
            playingIntent = true;
            post(() -> playing.set(true));
            notifyPlayback();
        });
        // On playback error: retry netease tracks whose cached streamUrl went stale
        // (expired VIP link, region lock, etc.). Non-netease or already-retried
        // tracks fall through to autoAdvance.
        backend.setOnError(() -> onMain(this::onPlaybackError));
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

    /** Publish a new lyric list and derive {@link #lyricsCoverOnly}. */
    private void applyLyrics(List<LyricLine> ly) {
        lyrics.set(ly);
        lyricsCoverOnly.set(computeCoverOnly(ly));
    }

    /** Cover-only when there are no lyrics, or an instrumental marker ("纯音乐") with
     *  fewer than 3 lines — a lone "纯音乐，请欣赏" placeholder centers the cover instead
     *  of floating a single line beside it. */
    private static boolean computeCoverOnly(List<LyricLine> ly) {
        if (ly == null || ly.isEmpty()) return true;
        if (ly.size() < 3) {
            for (LyricLine l : ly) {
                if (l == null) continue;
                if (l.text().contains("纯音乐")) return true;
                if (l.translation != null && l.translation.contains("纯音乐")) return true;
            }
        }
        return false;
    }

    private void updateLyricIndex(long pos) {
        List<LyricLine> ly = lyrics.peek();
        if (ly == null || ly.isEmpty()) return;
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

    /** Bumped by the host on a system back press; QML watches it and pops the topmost
     *  open overlay/page, calling {@link #requestExit()} when there's nothing to pop. */
    public final Property<Integer> backTick = new Property<>(0);

    /** Host hook to finish the activity when QML has nothing left to navigate back from. */
    public interface ExitListener {
        void onExit();
    }

    private volatile ExitListener exitListener;

    public void setExitListener(ExitListener l) {
        this.exitListener = l;
    }

    /** Host calls this on a back press; routed to QML via {@link #backTick}. */
    public void pressBack() {
        post(() -> backTick.set(backTick.peek() + 1));
    }

    /** Invoked from QML when no overlay/page consumed the back press. */
    public void requestExit() {
        ExitListener l = exitListener;
        if (l != null) onMain(l::onExit);
    }

    // --- App update check --------------------------------------------------
    // On startup the host calls checkForUpdate(); we GET the latest GitHub
    // release, compare its tag against the running version, and (if newer) expose
    // the version + release notes + download url so QML pops an update dialog.

    /** Latest GitHub release endpoint for the qplayer repo. gh-proxy.com proxies
     *  api.github.com too, so with the mirror on the whole flow (check + download)
     *  works on mainland networks where api.github.com is unreliable. */
    private static final String RELEASE_API =
            "https://api.github.com/repos/TIMER-err/qplayer/releases/latest";
    /** gh-proxy.com prefix — the API check uses it (it's the one mirror that proxies
     *  api.github.com). */
    private static final String MIRROR_PREFIX = "https://gh-proxy.com/";
    /** Download mirrors for the APK, fastest-first; the host tries them in order and
     *  falls through to the next (then the direct url) when one is down or refuses.
     *  Public instances come and go, so resilience matters more than any single one. */
    private static final String[] DOWNLOAD_MIRRORS = {
            "https://gh.ddlc.top/", "https://ghfast.top/", "https://gh-proxy.com/"
    };

    /** When true, the APK download url is routed through {@link #MIRROR_PREFIX}. */
    private volatile boolean updateMirror = false;

    /** Toggle the GitHub download mirror (driven by the settings switch). */
    public void setUpdateMirror(boolean enabled) {
        this.updateMirror = enabled;
    }

    /** True once a newer release than the running version is found; QML watches it
     *  to pop the update dialog. */
    public final Property<Boolean> updateAvailable = new Property<>(false);
    /** The newer release's version (tag without the leading "v"). */
    public final Property<String> updateVersion = new Property<>("");
    /** The newer release's notes (GitHub release body / changelog). */
    public final Property<String> updateNotes = new Property<>("");

    /** APK asset (or release page) url of the newer release; opened by the browser
     *  fallback. */
    private volatile String updateUrl = "";
    /** Raw (un-mirrored) github.com APK download url of the newer release, or "" when
     *  the release has no APK asset. The in-app downloader prefixes mirrors onto it. */
    private volatile String updateApkRaw = "";
    /** Running app version, injected by the host (PackageInfo.versionName). */
    private volatile String currentVersion = "";
    /** Same value, exposed to QML (e.g. the About card). */
    public final Property<String> appVersion = new Property<>("");

    /** Host hook to open a url externally (browser fallback for the update). */
    public interface UrlOpener {
        void open(String url);
    }

    private volatile UrlOpener urlOpener;

    public void setUrlOpener(UrlOpener o) {
        this.urlOpener = o;
    }

    /** Host hook to download an APK in-app and launch the system package installer.
     *  Receives candidate urls (mirror-prefixed, then direct) to try in order. */
    public interface Installer {
        void downloadAndInstall(String[] urls);
    }

    private volatile Installer installer;

    public void setInstaller(Installer i) {
        this.installer = i;
    }

    /** In-app update download progress: -1 idle, 0..100 downloading, 100 handing off
     *  to the installer, -2 failed. QML shows it and the host drives it. */
    public final Property<Integer> updateProgress = new Property<>(-1);

    /** Push download progress from the host (any thread). */
    public void setUpdateProgress(int pct) {
        post(() -> updateProgress.set(pct));
    }

    /** Start the in-app download + install of the pending update (QML "更新" button).
     *  Falls back to opening the url in a browser when there's no APK or no installer. */
    public void startUpdateDownload() {
        Installer in = installer;
        String apk = updateApkRaw;
        if (in == null || apk == null || apk.isEmpty()) {
            openUpdateUrl();
            return;
        }
        final String[] candidates = downloadCandidates(apk);
        post(() -> updateProgress.set(0));
        onMain(() -> in.downloadAndInstall(candidates));
    }

    /** Build the ordered download urls: mirrors first (when enabled) then the direct
     *  github url, or direct-first when the mirror is off. Duplicates collapsed. */
    private String[] downloadCandidates(String apk) {
        List<String> urls = new ArrayList<>();
        if (updateMirror) {
            for (String m : DOWNLOAD_MIRRORS) urls.add(m + apk);
            urls.add(apk);
        } else {
            urls.add(apk);
            for (String m : DOWNLOAD_MIRRORS) urls.add(m + apk);
        }
        return urls.toArray(new String[0]);
    }

    /** Host injects the running app version (e.g. "0.5.2") for the update compare. */
    public void setCurrentVersion(String version) {
        this.currentVersion = version == null ? "" : version;
        post(() -> appVersion.set(this.currentVersion));
    }

    /** Fetch the latest release off the worker thread; on a newer version, publish
     *  it to the update Properties (QML pops the dialog). Best-effort: any failure
     *  (offline, rate-limited, parse error) just logs and leaves the dialog closed. */
    public void checkForUpdate() {
        worker.submit(() -> {
            try {
                String json = fetchReleaseJson();
                JsonElement root = JsonParser.parseString(json);
                if (!root.isJsonObject()) return;
                JsonObject obj = root.getAsJsonObject();

                String tag = optString(obj, "tag_name");
                String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                if (!isNewer(latest, currentVersion)) return;

                String notes = optString(obj, "body");
                String apk = "";
                if (obj.has("assets") && obj.get("assets").isJsonArray()) {
                    JsonArray assets = obj.getAsJsonArray("assets");
                    for (JsonElement e : assets) {
                        if (!e.isJsonObject()) continue;
                        JsonObject a = e.getAsJsonObject();
                        if (optString(a, "name").toLowerCase().endsWith(".apk")) {
                            apk = optString(a, "browser_download_url");
                            break;
                        }
                    }
                }
                // Keep the raw APK url for the (mirror-cycling) in-app downloader; the
                // browser fallback opens the APK directly, or the release page if none.
                final String fApk = apk;
                final String fUrl = apk.isEmpty() ? optString(obj, "html_url") : apk;
                final String fVer = latest;
                final String fNotes = notes;
                post(() -> {
                    updateApkRaw = fApk;
                    updateUrl = fUrl;
                    updateVersion.set(fVer);
                    updateNotes.set(fNotes);
                    updateAvailable.set(true);
                });
                Logger.info("update available: {} (running {})", latest, currentVersion);
            } catch (Throwable e) {
                Logger.warn("update check failed: {}", e.toString());
            }
        });
    }

    /** Fetch the latest-release JSON, preferring the mirror when enabled (so the
     *  version check works on mainland networks where api.github.com is unreliable),
     *  and falling back to the other endpoint if the first fails. */
    private String fetchReleaseJson() throws java.io.IOException {
        String mirrored = MIRROR_PREFIX + RELEASE_API;
        String primary = updateMirror ? mirrored : RELEASE_API;
        String secondary = updateMirror ? RELEASE_API : mirrored;
        try {
            return httpGet(primary);
        } catch (java.io.IOException e) {
            return httpGet(secondary);
        }
    }

    /** Open an arbitrary external url via the host browser (e.g. the project page). */
    public void openExternalUrl(String url) {
        UrlOpener o = urlOpener;
        if (o != null && url != null && !url.isEmpty()) {
            final String u = url;
            onMain(() -> o.open(u));
        }
    }

    /** Open the stored download url via the host (invoked from the QML dialog). */
    public void openUpdateUrl() {
        UrlOpener o = urlOpener;
        String url = updateUrl;
        if (o != null && url != null && !url.isEmpty()) {
            final String u = url;
            onMain(() -> o.open(u));
        }
    }

    private static String optString(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return (e == null || e.isJsonNull()) ? "" : e.getAsString();
    }

    /** Semver compare on the first three numeric components; pre-release/suffix
     *  parts (e.g. the "-debug" on debug builds) are ignored. */
    private static boolean isNewer(String latest, String current) {
        if (latest == null || latest.isEmpty() || current == null || current.isEmpty()) {
            return false;
        }
        int[] a = parseVersion(latest);
        int[] b = parseVersion(current);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    private static int[] parseVersion(String v) {
        int[] out = new int[3];
        String[] parts = v.split("[.+\\-]");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
                // leave 0
            }
        }
        return out;
    }

    /** Minimal HTTP GET (same HttpURLConnection-only stance as NeteaseClient — no
     *  extra deps). GitHub requires a User-Agent and rejects requests without one. */
    private static String httpGet(String urlStr) throws java.io.IOException {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", "qplayer-update-check");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            int code = conn.getResponseCode();
            java.io.InputStream is =
                    (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            if (is != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
                is.close();
            }
            String body = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            if (code >= 400) {
                throw new java.io.IOException("HTTP " + code);
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    /** Jump to a slot in the live queue (queue-page tap). */
    public void playQueueIndex(int i) {
        playAt(i);
    }

    /** Drop a slot from the queue; keep playing the right track. */
    public void removeFromQueue(int i) {
        if (i < 0 || i >= queue.size()) return;
        int cur = playIndex;
        queue.remove(i);
        queueTracks.set(new ArrayList<>(queue));
        if (queue.isEmpty()) {
            playIndex = -1;
            index.set(-1);
            return;
        }
        if (i < cur) {
            playIndex = cur - 1;
            index.set(cur - 1);
        } else if (i == cur) {
            onMain(() -> playAt(Math.min(cur, queue.size() - 1)));
        }
    }

    // --- Local library ----------------------------------------------------

    /** Scan a local folder for audio files (platform-neutral, uses Files.walk). */
    public void scan(String folder) {
        worker.submit(() -> {
            try {
                LibraryScanner scanner = new LibraryScanner(metadataReader);
                List<Track> found = scanner.scan(folder);
                post(() -> applyLibrary(found));
            } catch (Throwable e) {
                Logger.exception(e);
                post(() -> toast.set("扫描失败：" + e.getMessage()));
            }
        });
    }

    /** Accept a pre-scanned track list (e.g. from MediaStore on Android 11+). */
    public void scanTracks(List<Track> tracks) {
        post(() -> applyLibrary(tracks));
    }

    private void applyLibrary(List<Track> found) {
        library.clear();
        library.addAll(found);
        tracks.set(new ArrayList<>(library));
        libraryCount.set(library.size());
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
        onMain(() -> playAt(start));
    }

    // Runs on the main thread (via onMain). Updates the plain playIndex synchronously,
    // marshals UI Property writes to the render thread via post(), and drives the
    // backend directly so playback advances even while the GL pump is paused.
    private void playAt(int i) {
        if (i < 0 || i >= queue.size()) return;
        playIndex = i;
        final int idx = i;
        final Track t = queue.get(i);
        post(() -> {
            index.set(idx);
            title.set(orEmpty(t.title));
            artist.set(orEmpty(t.artist));
            album.set(orEmpty(t.album));
            coverUrl.set(orEmpty(thumbUrl(t.coverUrl, "512")));
            durationMs.set(t.durationMs);
            positionMs.set(0L);
            currentLiked.set(t.neteaseId != 0 && likedSet.contains(t.neteaseId));
            currentLikeable.set(t.neteaseId != 0);
        });
        updateCover(t, i);

        if (t.source == Track.Source.LOCAL) {
            String src = t.playable();
            if (src == null || src.isEmpty()) return;
            loadLocalLyrics(t);
            Logger.info("play local: {}", t.title);
            backend.play(src, 0L);
            playingIntent = true;
            post(() -> playing.set(true));
            notifyPlayback();
        } else if (t.source == Track.Source.NETEASE) {
            // Always prefer a cached local file over (re-)streaming, regardless of
            // play mode — a song played once is served from disk on every later play.
            String cached = t.neteaseId != 0 ? diskCache.getAudio(t.neteaseId) : null;
            if (cached != null) {
                Logger.info("play netease (audio cache): {}", t.title);
                backend.play(cached, 0L);
                playingIntent = true;
                post(() -> playing.set(true));
                notifyPlayback();
                // The audio fast-path skips resolveAndPlayNetease, so load the lyrics
                // (cache-first inside) here too — else a cached song plays wordless.
                loadNeteaseLyrics(t, i);
            } else if (t.streamUrl != null) {
                Logger.info("play netease (cached url): {}", t.title);
                backend.play(t.playable(), 0L);
                playingIntent = true;
                post(() -> playing.set(true));
                notifyPlayback();
                loadNeteaseLyrics(t, i);
                // Populate the disk cache so the next play is local (skip trial clips).
                cacheAudioAsync(t);
            } else {
                resolveAndPlayNetease(t, i);
            }
        }
    }

    /** Feed coverBytes for the fluid backdrop: local tracks carry embedded
     *  bytes; NETEASE tracks download lazily off-thread, keyed by queue index
     *  so a stale fetch for a skipped-past track is dropped. */
    private void updateCover(Track t, int expectedIndex) {
        if (t.coverBytes != null) {
            final byte[] cb = t.coverBytes;
            post(() -> { applyCover(cb); coverPath.set(""); });
            notifyPlayback();
            return;
        }
        // Local track: cover lives in a cache file (an absolute path, not an http url).
        // Prefer the larger now-playing copy over the row thumbnail for the fluid
        // backdrop + Monet seed; fall back to the thumbnail if only it exists.
        String localCover = t.coverLocalPath != null ? t.coverLocalPath : t.coverThumbPath;
        if (localCover != null && localCover.startsWith("/")) {
            byte[] data = readBytesFromFile(localCover);
            if (data != null && data.length > 0) {
                final String path = localCover;
                post(() -> { applyCover(data); coverPath.set(path); });
                notifyPlayback();
                return;
            }
        }
        post(() -> { applyCover(null); coverPath.set(""); });
        final String url = t.coverUrl;
        if (url == null || url.isEmpty()) return;

        // Check disk cache first.
        String cachedImg = diskCache.getImage(url);
        if (cachedImg != null) {
            byte[] data = readBytesFromFile(cachedImg);
            if (data != null && data.length > 0) {
                t.coverBytes = data;
                final String path = cachedImg;
                post(() -> { if (playIndex == expectedIndex) { applyCover(data); coverPath.set(path); } });
                notifyPlayback();
                return;
            }
        }

        worker.submit(() -> {
            byte[] data = downloadBytes(url);
            if (data == null) return;
            t.coverBytes = data;
            // Cache cover image to disk (write already-downloaded bytes, no re-fetch).
            String imgPath = DiskCache.imagePath(url);
            if (imgPath != null) writeBytesToFile(data, imgPath);
            final String path = imgPath;
            post(() -> {
                if (playIndex == expectedIndex) {
                    applyCover(data);
                    if (path != null) coverPath.set(path);
                }
            });
            notifyPlayback(); // refresh the media-notification artwork
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
    private List<LyricLine> tryAmllTtml(long songId) {
        // Check disk cache first.
        String cached = diskCache.getLyric(songId);
        if (cached != null) {
            try {
                byte[] data = readBytesFromFile(cached);
                if (data != null && data.length > 0) {
                    String ttml = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                    if (!ttml.trim().isEmpty()) return TtmlParser.parse(ttml);
                }
            } catch (Throwable ignored) { }
        }
        byte[] data = downloadBytes("https://amlldb.bikonoo.com/ncm-lyrics/" + songId + ".ttml");
        if (data == null || data.length == 0) return Collections.emptyList();
        // Cache for next time.
        diskCache.cacheLyric(data, songId);
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
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
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
            // Shared by cover + ttml-lyric fetches; log the URL so the failing
            // resource is clear instead of always blaming the cover.
            Logger.warn("download failed for {}: {}", url, e.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- disk I/O helpers ------------------------------------------------

    private static byte[] readBytesFromFile(String path) {
        if (path == null) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    private static void writeBytesToFile(byte[] data, String path) {
        if (data == null || path == null) return;
        java.io.File parent = new java.io.File(path).getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(path)) {
            out.write(data);
        } catch (Throwable ignored) { }
    }

    public void toggle() {
        onMain(() -> {
            if (playIndex < 0 && !library.isEmpty()) {
                play(0);
                return;
            }
            if (playingIntent) {
                backend.pause();
                playingIntent = false;
                post(() -> playing.set(false));
            } else {
                backend.resume();
                playingIntent = true;
                post(() -> playing.set(true));
            }
            notifyPlayback();
        });
    }

    /** Cycle list-loop -> shuffle -> repeat-one -> list-loop. */
    public void cyclePlayMode() {
        playMode.set((playMode.peek() + 1) % 3);
    }

    // A different queue slot than the current one (shuffle never repeats a track
    // back-to-back unless the queue has a single entry).
    private int randomIndex() {
        int n = queue.size();
        if (n == 0) return 0;
        if (n == 1) return 0;
        int r;
        do {
            r = rng.nextInt(n);
        } while (r == playIndex);
        return r;
    }

    // Manual skip: shuffle picks a random slot, otherwise step forward and wrap.
    // Repeat-one only affects auto-advance -- a manual press still moves on.
    public void next() {
        onMain(() -> {
            if (queue.isEmpty()) return;
            playAt(playMode.peek() == 1 ? randomIndex() : (playIndex + 1) % queue.size());
        });
    }

    public void prev() {
        onMain(() -> {
            if (queue.isEmpty()) return;
            int n = queue.size();
            playAt(playMode.peek() == 1 ? randomIndex() : (playIndex - 1 + n) % n);
        });
    }

    // Track finished on its own: repeat-one replays it, shuffle jumps randomly,
    // list-loop advances. Wired to backend.onComplete (not next()) so repeat-one
    // doesn't fight a user's manual skip. Already on the main thread (onComplete).
    private void autoAdvance() {
        if (queue.isEmpty()) return;
        switch (playMode.peek()) {
            case 2:
                playAt(playIndex);
                break;
            case 1:
                playAt(randomIndex());
                break;
            default:
                playAt((playIndex + 1) % queue.size());
                break;
        }
    }

    public void seek(long ms) {
        final long t = Math.max(0L, ms);
        onMain(() -> {
            backend.seek(t);
            post(() -> positionMs.set(t));
            notifyPlayback();
        });
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

    /** Toggle source-switching: when on, blocked/trial netease tracks fall back to
     *  the unblock sources (gdstudio / bodian / kuwo) before being skipped. */
    public void setUnblockEnabled(boolean enabled) {
        this.unblockEnabled = enabled;
    }

    /** Load a netease track's lyrics off-thread (AMLL TTML mirror, else Netease's
     *  own). tryAmllTtml hits the disk cache first, so a previously-played song shows
     *  its lyrics with no network. Called on every netease play — including the
     *  audio-cache fast path, which bypasses the URL resolve that used to fetch them. */
    private void loadNeteaseLyrics(Track t, int expectedIndex) {
        final long songId = t.neteaseId;
        if (songId == 0) return;
        worker.submit(() -> {
            List<LyricLine> lines = tryAmllTtml(songId);
            if (lines.isEmpty()) {
                lines = neteaseLyricCacheFirst(songId);
            }
            final List<LyricLine> ly = lines;
            post(() -> { if (playIndex == expectedIndex) applyLyrics(ly); });
        });
    }

    private static final Gson LYRIC_GSON = new Gson();

    /** Netease's own lyric payload (YRC/LRC/translation/romaji), disk-cache-first so a
     *  previously-played song shows lyrics offline even when it has no AMLL TTML. The
     *  payload is serialized to JSON next to the TTML cache (a .nlrc file). */
    private List<LyricLine> neteaseLyricCacheFirst(long songId) {
        String cached = diskCache.getNeteaseLyric(songId);
        if (cached != null) {
            try {
                byte[] data = readBytesFromFile(cached);
                if (data != null && data.length > 0) {
                    NeteaseLyric nl = LYRIC_GSON.fromJson(
                            new String(data, java.nio.charset.StandardCharsets.UTF_8), NeteaseLyric.class);
                    if (nl != null && !nl.isEmpty()) {
                        return LyricParser.fromNeteaseStrings(nl.yrc, nl.lrc, nl.tlyric, nl.romalrc);
                    }
                }
            } catch (Throwable ignored) { }
        }
        try {
            NeteaseLyric nl = netease.lyric(songId);
            if (nl.isEmpty()) return Collections.emptyList();
            byte[] data = LYRIC_GSON.toJson(nl).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            diskCache.cacheNeteaseLyric(data, songId);
            return LyricParser.fromNeteaseStrings(nl.yrc, nl.lrc, nl.tlyric, nl.romalrc);
        } catch (Throwable e) {
            Logger.warn("lyric load failed for {}: {}", songId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Cache a netease track's audio to disk (off-thread) for local replay. Skips
     *  trial/preview clips and tracks lacking an id or resolved url. */
    private void cacheAudioAsync(Track t) {
        if (t == null || t.trial || t.neteaseId == 0 || t.streamUrl == null) return;
        final String url = t.streamUrl;
        final long nid = t.neteaseId;
        worker.submit(() -> diskCache.cacheAudio(url, nid));
    }

    private void resolveAndPlayNetease(Track t, int expectedIndex) {
        long songId = t.neteaseId;
        worker.submit(() -> {
            try {
                Logger.info("netease: resolve song {} (loggedIn={}, level={})",
                        songId, netease.isLoggedIn(), playLevel);
                // Legacy /search/get returns no album picUrl, so search-sourced tracks
                // arrive without a cover; fetch song detail to fill any missing field.
                if (t.title == null || t.title.isEmpty()
                        || t.coverUrl == null || t.coverUrl.isEmpty()) {
                    NeteaseSong sd = netease.songDetail(songId);
                    if (sd != null) {
                        if (t.title == null || t.title.isEmpty()) t.title = sd.name;
                        if (t.artist == null || t.artist.isEmpty()) t.artist = sd.artist;
                        if (t.album == null || t.album.isEmpty()) t.album = sd.album;
                        if (t.coverUrl == null || t.coverUrl.isEmpty()) t.coverUrl = sd.coverUrl;
                        if (t.durationMs <= 0) t.durationMs = sd.durationMs;
                    }
                }
                NeteaseClient.UrlInfo info = netease.songUrlInfo(songId, playLevel);
                // Official url wins only when it's a full track. A trial-only clip,
                // a missing url, or a blocked/VIP song all fall through to the
                // unblock sources; the trial clip is kept as a last-resort fallback.
                String url = (info != null && !info.trial) ? info.url : null;
                boolean unblocked = false;
                if (url == null && unblockEnabled) {
                    String un = SongUnblocker.resolve(songId, t.title, t.artist);
                    if (un != null) {
                        url = un;
                        unblocked = true;
                    }
                }
                if (url == null && info != null && info.trial && info.url != null) {
                    url = info.url; // nothing better available — play the preview clip
                }
                final boolean isUnblocked = unblocked;
                final boolean isTrialOnly = !unblocked && info != null && info.trial && url != null;
                Logger.info("netease: url={} (unblocked={}, trial={})", url, unblocked, isTrialOnly);
                final String playUrl = url;
                // Hop to the main thread for the backend control (works backgrounded);
                // UI Property writes still marshal to the render thread via post().
                onMain(() -> {
                    if (playIndex != expectedIndex) return; // user moved on
                    if (playUrl == null) {
                        Logger.warn("netease song {} has no url (blocked/VIP/login required)", songId);
                        post(() -> toast.set(netease.isLoggedIn()
                                ? "无法播放：VIP/灰色歌曲" : "无法播放：请先登录"));
                        return;
                    }
                    t.streamUrl = playUrl;
                    t.trial = isTrialOnly;
                    post(() -> {
                        if (isUnblocked) toast.set("已为该歌曲自动换源");
                        else if (isTrialOnly) toast.set("当前歌曲仅可试听");
                        title.set(orEmpty(t.title));
                        artist.set(orEmpty(t.artist));
                        album.set(orEmpty(t.album));
                        coverUrl.set(orEmpty(thumbUrl(t.coverUrl, "512")));
                        durationMs.set(t.durationMs);
                    });
                    updateCover(t, expectedIndex);
                    loadNeteaseLyrics(t, expectedIndex);
                    Logger.info("play netease: {} — {}", t.title, playUrl);
                    backend.play(playUrl, 0L);
                    playingIntent = true;
                    post(() -> playing.set(true));
                    notifyPlayback();
                    // Populate the disk cache so later plays are served locally.
                    cacheAudioAsync(t);
                });
            } catch (Throwable e) {
                Logger.warn("netease resolve failed for {}: {}", songId, e.getMessage());
                post(() -> toast.set("播放失败：" + e.getMessage()));
            }
        });
    }

    private void loadLocalLyrics(Track t) {
        if (t.lyricFilePath != null) {
            try {
                applyLyrics(LyricParser.parse(t.lyricFilePath, t.translationFilePath, t.romajiFilePath));
                return;
            } catch (Throwable e) {
                Logger.warn("lyric parse failed: {}", e.getMessage());
            }
        }
        applyLyrics(Collections.<LyricLine>emptyList());
    }

    private static Track toTrack(NeteaseSong s) {
        Track t = new Track();
        t.source = Track.Source.NETEASE;
        t.neteaseId = s.id;
        t.title = s.name;
        t.artist = s.artist;
        t.album = s.album;
        t.coverUrl = s.coverUrl;
        t.coverThumbPath = s.coverThumbPath != null ? s.coverThumbPath : NeteaseClient.thumbUrl(s.coverUrl);
        t.durationMs = s.durationMs;
        return t;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // --- Netease discovery ------------------------------------------------

    /** Load hot search keywords from Netease API. Called once on search page open. */
    public void loadHotSearches() {
        worker.submit(() -> {
            try {
                List<String> hot = netease.searchHot();
                post(() -> hotSearches.set(hot));
            } catch (Throwable e) {
                Logger.warn("loadHotSearches failed: {}", e.getMessage());
            }
        });
    }

    /** Search and publish to {@link #searchResults}. Results are cached for
     *  {@value #SEARCH_CACHE_TTL_MS} ms; a cache hit returns immediately without
     *  a network round-trip. */
    public void search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        final String key = keyword.trim().toLowerCase();
        // Fast path: check cache on the calling (render) thread.
        CacheEntry entry = searchCache.get(key);
        if (entry != null && !entry.isExpired()) {
            searchResults.set(entry.songs);
            resultCount.set(entry.songs.size());
            Logger.info("search cache hit: {}", key);
            return;
        }
        worker.submit(() -> {
            try {
                // Double-check: another search for the same keyword may have
                // completed while we were waiting for the worker slot.
                CacheEntry existing = searchCache.get(key);
                if (existing != null && !existing.isExpired()) {
                    post(() -> {
                        searchResults.set(existing.songs);
                        resultCount.set(existing.songs.size());
                    });
                    return;
                }
                List<NeteaseSong> r = netease.searchSongs(keyword, 30, 0);
                // Legacy /search/get omits album picUrl; batch-fetch details, then
                // refresh the thumbnail URL for the rows whose cover we just filled
                // (parseSong already set it for songs that had a cover).
                fillMissingCovers(r);
                buildSongThumbs(r, "128");
                searchCache.put(key, new CacheEntry(r));
                post(() -> {
                    searchResults.set(r);
                    resultCount.set(r.size());
                });
            } catch (Throwable e) {
                Logger.warn("search failed: {}", e.getMessage());
            }
        });
    }

    /** Batch-fill missing coverUrl fields from /v3/song/detail (the legacy
     *  /search/get omits album picUrl). Runs on the worker thread. */
    private void fillMissingCovers(List<NeteaseSong> songs) {
        if (songs == null || songs.isEmpty()) return;
        List<Long> missingIds = new ArrayList<>();
        for (NeteaseSong s : songs) {
            if (s.coverUrl == null || s.coverUrl.isEmpty()) missingIds.add(s.id);
        }
        if (missingIds.isEmpty()) return;
        try {
            List<NeteaseSong> details = netease.songDetails(missingIds);
            Map<Long, NeteaseSong> detailMap = new HashMap<>();
            for (NeteaseSong d : details) detailMap.put(d.id, d);
            for (NeteaseSong s : songs) {
                if ((s.coverUrl == null || s.coverUrl.isEmpty())) {
                    NeteaseSong d = detailMap.get(s.id);
                    if (d != null && d.coverUrl != null && !d.coverUrl.isEmpty()) {
                        s.coverUrl = d.coverUrl;
                    }
                }
            }
        } catch (Throwable e) {
            Logger.warn("fillMissingCovers failed: {}", e.getMessage());
        }
    }

    /** Build a Netease CDN thumbnail URL (e.g. coverUrl + ?param=128y128).
     *  Returns the original url unchanged if it is null/empty or already has params. */
    private static String thumbUrl(String url, String size) {
        if (url == null || url.isEmpty()) return "";
        return url.contains("?") ? url + "&param=" + size + "y" + size
                                 : url + "?param=" + size + "y" + size;
    }

    /** Batch-build {@link NeteaseSong#coverThumbPath} for a list of songs. */
    private static void buildSongThumbs(List<NeteaseSong> songs, String size) {
        if (songs == null) return;
        for (NeteaseSong s : songs) {
            if (s.coverUrl != null && !s.coverUrl.isEmpty()) {
                s.coverThumbPath = thumbUrl(s.coverUrl, size);
            }
        }
    }

    /** Handle a playback error from the audio backend. For netease tracks whose
     *  cached streamUrl went stale (expired VIP link, region lock, etc.), clear
     *  the cache and re-resolve. Everything else falls through to autoAdvance. */
    private void onPlaybackError() {
        Track t = currentTrack();
        // Retry a netease track once: clear the (likely stale) url and re-resolve.
        // errorRetryId guards against an endless error→re-resolve loop when the
        // fresh url also fails; it's reset when a track actually starts playing.
        if (t != null && t.source == Track.Source.NETEASE && t.streamUrl != null
                && t.neteaseId != errorRetryId) {
            errorRetryId = t.neteaseId;
            int idx = playIndex;
            Logger.warn("playback error on netease track {}, clearing stale url and retrying", t.neteaseId);
            t.streamUrl = null;
            resolveAndPlayNetease(t, idx);
            return;
        }
        autoAdvance();
    }

    /** Load the home content: recommended songs (login) + recommended playlists. */
    public void loadHome() {
        worker.submit(() -> {
            try {
                List<NeteasePlaylist> picks = netease.personalizedPlaylists(12);
                for (NeteasePlaylist p : picks) {
                    p.coverThumbPath = thumbUrl(p.coverUrl, "512");
                }
                post(() -> recommendPlaylists.set(picks));
            } catch (Throwable e) {
                Logger.warn("personalized playlists failed: {}", e.getMessage());
            }
            if (netease.isLoggedIn()) {
                try {
                    List<NeteaseSong> daily = netease.recommendSongs();
                    fillMissingCovers(daily);
                    buildSongThumbs(daily, "128");
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
        currentPlaylistId = playlistId;
        playlistLoading.set(true);
        playlistTracks.set(Collections.<NeteaseSong>emptyList());
        playlistTitle.set("");
        // Reset the collect state; the real values land once playlist/detail resolves, so
        // the icon stays hidden (loading) until then rather than flashing a wrong state.
        playlistSubscribed.set(false);
        playlistOwned.set(false);
        worker.submit(() -> {
            try {
                NeteasePlaylist detail = netease.playlistDetail(playlistId);
                List<NeteaseSong> songs = netease.playlistTracks(playlistId, 200);
                fillMissingCovers(songs);
                buildSongThumbs(songs, "128");
                String name = detail != null ? detail.name : "";
                boolean subscribed = detail != null && detail.subscribed;
                boolean owned = detail != null && uid != 0 && detail.creatorUid == uid;
                post(() -> {
                    if (currentPlaylistId != playlistId) return;   // a newer open won
                    playlistTitle.set(name == null ? "" : name);
                    playlistTracks.set(songs);
                    playlistSubscribed.set(subscribed);
                    playlistOwned.set(owned);
                    playlistLoading.set(false);
                });
            } catch (Throwable e) {
                Logger.warn("open playlist {} failed: {}", playlistId, e.getMessage());
                post(() -> playlistLoading.set(false));
            }
        });
    }

    /** Collect / un-collect the currently open playlist. No-op on your own playlist or
     *  when signed out. Optimistically flips the icon, reverting if the server refuses. */
    public void togglePlaylistSubscribe() {
        if (!loggedIn.get() || playlistOwned.get()) return;
        final long id = currentPlaylistId;
        if (id == 0) return;
        final boolean target = !playlistSubscribed.get();
        playlistSubscribed.set(target);
        worker.submit(() -> {
            boolean ok = false;
            try {
                ok = netease.playlistSubscribe(id, target);
            } catch (Throwable e) {
                Logger.warn("playlist subscribe {} -> {} failed: {}", id, target, e.getMessage());
            }
            final boolean done = ok;
            post(() -> {
                if (currentPlaylistId != id) return;
                if (done) {
                    toast.set(target ? "已收藏歌单" : "已取消收藏");
                    loadMyPlaylists();   // reflect the change in 我的
                } else {
                    playlistSubscribed.set(!target);   // revert the optimistic flip
                }
            });
        });
    }

    /** Load the signed-in user's playlists (favorites + created). */
    public void loadMyPlaylists() {
        if (uid == 0) return;
        worker.submit(() -> {
            try {
                List<NeteasePlaylist> pls = netease.userPlaylists(uid, 100);
                for (NeteasePlaylist p : pls) {
                    p.coverThumbPath = thumbUrl(p.coverUrl, "512");
                }
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
                    likedCount.set(likedSet.size());
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
                if (!ok) {
                    // song/like hit risk control (code 524 "当前环境异常") for this track;
                    // fall back to adding/removing it via the "我喜欢的音乐" playlist.
                    ok = netease.setFavorite(uid, id, target);
                }
                if (ok) {
                    post(() -> {
                        if (target) likedSet.add(id);
                        else likedSet.remove(id);
                        likedCount.set(likedSet.size());
                        Track c = currentTrack();
                        if (c != null && c.neteaseId == id) currentLiked.set(target);
                    });
                } else {
                    post(() -> toast.set(netease.isLoggedIn()
                            ? (target ? "收藏失败" : "取消收藏失败") : "请先登录"));
                }
            } catch (Throwable e) {
                Logger.warn("like toggle failed: {}", e.getMessage());
                post(() -> toast.set("收藏失败：" + e.getMessage()));
            }
        });
    }

    /** Current queue track (the playback source of truth) or null. Safe off the
     *  render thread — reads the plain playIndex, not the lagging Property. Used by
     *  the host media service to build the notification / session metadata. */
    public Track currentTrack() {
        int i = playIndex;
        return i >= 0 && i < queue.size() ? queue.get(i) : null;
    }

    /** Intended play state for the media session — true from play/resume until pause,
     *  unaffected by the backend's brief async-prepare gap. */
    public boolean isPlaying() {
        return playingIntent;
    }

    /** Current source duration in ms (0 if unknown). */
    public long duration() {
        long d = backend.duration();
        return d > 0 ? d : 0L;
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
                String avatar = u != null && u.avatarUrl != null ? u.avatarUrl : "";
                int vip = u != null ? u.vipType : 0;
                int lvl = u != null ? u.level : 0;
                String sig = u != null && u.signature != null ? u.signature : "";
                // uid is a plain volatile field (not a Property), so set it here on
                // the worker thread -- refreshLiked() runs synchronously right below
                // and reads uid; deferring it via post() left uid == 0 there, so the
                // liked set never loaded and the like button never lit.
                uid = id;
                post(() -> {
                    loggedIn.set(in);
                    userName.set(name == null ? "" : name);
                    userAvatar.set(avatar);
                    userVipType.set(vip);
                    userLevel.set(lvl);
                    userSignature.set(sig);
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
        userAvatar.set("");
        userVipType.set(0);
        userLevel.set(0);
        userSignature.set("");
        likedSet.clear();
        likedCount.set(0);
        playlistCount.set(0);
        myPlaylists.set(Collections.<NeteasePlaylist>emptyList());
        recommendations.set(Collections.<NeteaseSong>emptyList());
        recentSongs.set(Collections.<NeteaseSong>emptyList());
        toast.set("已退出登录");
    }

    public void shutdown() {
        backend.release();
        worker.shutdownNow();
    }

    // --- Disk cache management (called from QML settings page) -------------

    /** Update the {@link #cacheSizeMB} property from the actual disk usage. */
    public void refreshCacheSize() {
        long bytes = diskCache.totalSize();
        cacheSizeMB.set(bytes / (1024 * 1024));
    }

    /** Change the max cache size and trigger eviction if needed. */
    public void setCacheMaxSizeMB(long mb) {
        diskCache.setMaxSizeMB(mb);
        refreshCacheSize();
    }

    /** Clear all disk cache (audio + lyrics + images). */
    public void clearDiskCache() {
        diskCache.clearAll();
        refreshCacheSize();
        toast.set("缓存已清除");
    }
}
