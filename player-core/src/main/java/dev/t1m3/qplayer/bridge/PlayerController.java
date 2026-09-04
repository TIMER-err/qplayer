package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.cache.DiskCache;
import dev.t1m3.qplayer.cache.PlaylistCacheIndex;
import dev.t1m3.qplayer.cache.MediaMetaIndex;
import dev.t1m3.qplayer.cache.MediaPlaylistCacheIndex;
import dev.t1m3.qplayer.library.LibraryScanner;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricParser;
import dev.t1m3.qplayer.lyric.TtmlParser;
import dev.t1m3.qplayer.lyric.WordTimeLrcParser;
import dev.t1m3.qplayer.lyric.skia.LyricConfig;
import dev.t1m3.qplayer.media.LyricsPayload;
import dev.t1m3.qplayer.media.AccountProfile;
import dev.t1m3.qplayer.media.Album;
import dev.t1m3.qplayer.media.Artist;
import dev.t1m3.qplayer.media.LoginChallenge;
import dev.t1m3.qplayer.media.LoginMethod;
import dev.t1m3.qplayer.media.MediaId;
import dev.t1m3.qplayer.media.Page;
import dev.t1m3.qplayer.media.Playlist;
import dev.t1m3.qplayer.media.ProviderHome;
import dev.t1m3.qplayer.media.Song;
import dev.t1m3.qplayer.media.StreamDescriptor;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.plugin.CorePluginHostApi;
import dev.t1m3.qplayer.plugin.PluginManager;
import dev.t1m3.qplayer.plugin.PluginAccountService;
import dev.t1m3.qplayer.plugin.PluginInstaller;
import dev.t1m3.qplayer.plugin.PluginCredentialVault;
import dev.t1m3.qplayer.plugin.PluginCatalogEntry;
import dev.t1m3.qplayer.plugin.PluginCatalogService;
import dev.t1m3.qplayer.plugin.PluginCompatibility;
import dev.t1m3.qplayer.plugin.PluginUiContributionRow;
import dev.t1m3.qplayer.plugin.PluginUiDescription;
import dev.t1m3.qplayer.plugin.PluginPackageVerifier;
import dev.t1m3.qplayer.plugin.PluginPermission;
import dev.t1m3.qplayer.plugin.PluginProviderService;
import dev.t1m3.qplayer.plugin.PluginRegistry;
import dev.t1m3.qplayer.plugin.PluginRow;
import dev.t1m3.qplayer.plugin.PluginManifest;
import dev.t1m3.qplayer.plugin.PluginSetupState;
import dev.t1m3.qplayer.plugin.ProviderCapability;
import dev.t1m3.qplayer.plugin.VerifiedPluginPackage;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.netease.dto.NeteaseAlbum;
import dev.t1m3.qplayer.netease.dto.NeteaseArtist;
import dev.t1m3.qplayer.netease.dto.NeteaseLyric;
import dev.t1m3.qplayer.netease.dto.NeteasePlaylist;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.netease.dto.NeteaseUser;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.GitHubDownloadUrls;
import dev.t1m3.qplayer.util.Logger;
import dev.t1m3.qplayer.util.QrMatrix;
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
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

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
    private volatile java.util.function.Consumer<String> clipboard;
    private volatile WebLoginLauncher webLoginLauncher;
    private volatile boolean monetEnabled = true;
    private static final String DEFAULT_SEED = "#6750A4";
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qplayer-net");
        t.setDaemon(true);
        return t;
    });
    // Interactive searches must not queue behind cover/home/playlist requests on
    // worker, and rapid typing must not leave an unbounded list of obsolete searches
    // in memory. Keep at most the request currently running plus the newest pending
    // request; DiscardOldestPolicy coalesces everything in between.
    private final ExecutorService searchWorker = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), r -> {
                Thread t = new Thread(r, "qplayer-search");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    // Bulk, non-urgent disk-cache downloads (full audio files, thumbnails) get their
    // own queue so a full-track download never blocks interactive work
    // was split off: a full-track FLAC download is tens of MB and can take many
    // seconds, and every track played queues one right after it starts. Anything
    // that shares `worker` with these — a quick loadRecent()/search() the user is
    // actively waiting on — would otherwise sit stuck behind however many downloads
    // happened to be queued first, looking like the feature itself is slow/broken
    // when it's actually just waiting in line.
    private final ExecutorService cacheWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qplayer-cache");
        t.setDaemon(true);
        return t;
    });
    // resolveAndPlayNetease submits the now-playing track's cover download to
    // `worker` before its lyric fetch -- same head-of-line-blocking shape as
    // the cache worker above, just within one track's own resolve instead
    // of across tracks: a single-thread worker forces the lyric request to sit
    // queued behind the cover's own network round trip before it can even start,
    // on top of the lyric API call's own latency, while playback itself starts
    // instantly on its own dedicated audio thread. Users noticed lyrics visibly
    // lagging behind the song already audibly playing. Splitting lyric fetches
    // onto their own queue lets them start at the same time as the cover fetch
    // instead of after it.
    private final ExecutorService lyricWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qplayer-lyric");
        t.setDaemon(true);
        return t;
    });
    // offlinePlaylistFallback's background retry (Thread.sleep-and-retry-online)
    // needs its own queue for the same reason as the three above: it deliberately
    // blocks its own thread for the whole retry interval, and doing that on
    // `worker` would head-of-line-block every other netease read (a track resolve,
    // a search) behind it for the entire wait.
    private final ExecutorService retryWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qplayer-retry");
        t.setDaemon(true);
        return t;
    });
    // Monet must never wait behind the general network queue: on Android in
    // particular, the QML Image can already be showing its 512px cover while a
    // seed extraction submitted to `worker` is still behind unrelated requests.
    // Keep thumbnail I/O and bitmap decoding separate so even a slow 128px CDN
    // request cannot delay extraction from cover bytes that become available.
    private final ExecutorService monetFetchWorker = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), r -> {
                Thread t = new Thread(r, "qplayer-monet-fetch");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ExecutorService monetWorker = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), r -> {
                Thread t = new Thread(r, "qplayer-monet");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    /** Unified disk cache (audio / lyrics / images) with LRU eviction. */
    public final DiskCache diskCache = new DiskCache(200); // default 200 MB
    /** id -> title/artist/cover lookup for songs seen before, so search() has
     *  something to show when the live network call fails (offline, or the API
     *  is just down) — DiskCache itself only knows bare ids, no display text. */
    private final MediaMetaIndex mediaMetaIndex = new MediaMetaIndex();
    /** playlistId -> summary + song-list snapshot, so 我的歌单 and a
     *  previously-opened playlist still render with no network. */
    private final PlaylistCacheIndex playlistCacheIndex = new PlaylistCacheIndex();
    private final MediaPlaylistCacheIndex mediaPlaylistCacheIndex = new MediaPlaylistCacheIndex();
    /** Source runtimes outlive the QML renderer and remain active in the tray/background. */
    private final PluginRegistry pluginRegistry = new PluginRegistry();
    private final CorePluginHostApi pluginHostApi = new CorePluginHostApi();
    private final PluginManager pluginManager = new PluginManager(
            AppDirs.pluginsDir(), pluginRegistry, pluginHostApi);
    private final PluginProviderService pluginProviders =
            new PluginProviderService(pluginManager, pluginHostApi);
    private final PluginAccountService pluginAccounts =
            new PluginAccountService(pluginManager, pluginHostApi);
    private final PluginInstaller pluginInstaller = new PluginInstaller(pluginRegistry);
    private final PluginPackageVerifier pluginVerifier = new PluginPackageVerifier();
    private final PluginCatalogService pluginCatalog = new PluginCatalogService(pluginVerifier);
    private final PluginSetupState pluginSetupState = new PluginSetupState();
    private volatile PluginPicker pluginPicker;
    private volatile VerifiedPluginPackage pendingPluginPackage;
    private volatile boolean pendingPluginPackageTemporary;

    private final List<Track> library = new CopyOnWriteArrayList<>();
    private final List<Track> queue = new CopyOnWriteArrayList<>();
    /** User-curated "play later" list — unlike {@link #queue}, never auto-changes when
     *  you tap a song elsewhere; only explicit add/remove (song long-press menu) and
     *  the queue-page toggle touch it. Local-only, no netease sync. */
    private final List<Track> customPlaylist = new CopyOnWriteArrayList<>();
    // In-memory LRU of parsed lyrics by netease songId. A preloaded (next/prev) or
    // recently played track then shows lyrics the instant it becomes current — no
    // network / disk read / parse on the switch. Bounded, access-order eviction.
    private static final int LYRIC_MEM_MAX = 12;
    private final Map<Long, List<LyricLine>> lyricMem = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<Long, List<LyricLine>>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Long, List<LyricLine>> e) {
                    return size() > LYRIC_MEM_MAX;
                }
            });
    /** Per-track lyric timing corrections. The live LyricConfig value still feeds
     *  both renderers, but it is replaced whenever the current track changes. */
    private final Map<String, Integer> lyricOffsets =
            java.util.Collections.synchronizedMap(new HashMap<String, Integer>());
    private final Queue<Runnable> uiQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> likedSet = new HashSet<>();
    private final Set<String> pluginLikedSet = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Random rng = new Random();

    // Playback control runs on the host's main thread (always alive — unlike the GL
    // render thread, which pauses in the background and would stall auto-advance);
    // UI Property writes still marshal to the render thread via post()/pump().
    // mainExec is null on hosts with no main loop (desktop), where control runs inline.
    private volatile java.util.concurrent.Executor mainExec;
    // Source-of-truth play position. The `index` Property mirrors it for the UI but
    // lags in the background (pump paused), so all playback logic uses this instead.
    private volatile int playIndex = -1;
    /** Playlist that produced the current queue, or 0 for search/local/custom queues.
     *  Heart mode needs the original playlist id in addition to its seed song. */
    private volatile long currentQueuePlaylistId;
    private volatile String currentQueueMediaPlaylistId = "";
    // Intended play/pause state. The backend (MediaPlayer) prepares asynchronously, so
    // backend.isPlaying() is briefly false right after play() — reporting that to the
    // media session shows a stale "paused". The session uses this intent instead.
    private volatile boolean playingIntent = false;
    /** One-shot desired state for a plugin-selected track whose source is still
     * preparing asynchronously. This is transport-neutral host plumbing. */
    private volatile Boolean pendingPluginDesiredPlaying;
    private volatile String pendingPluginTargetMediaId = "";
    // The lyric renderer needs a stricter state than playingIntent: play() expresses
    // intent before an async source has opened/decoded/primed, while lyrics may already
    // be available. Only onStarted marks the media clock as genuinely running.
    private volatile boolean playbackStarted = false;
    // Stable visual position before a source has actually started (loading/session
    // restore). Once started, the backend position remains authoritative while paused.
    private volatile long stoppedLyricPositionMs = 0L;
    // Track changes are discontinuities even when two tracks share the same position.
    private final AtomicLong playbackRevision = new AtomicLong();
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

    // --- Search history ---------------------------------------------------
    private static final int HISTORY_MAX = 100;
    private final List<String> historyList = new ArrayList<>();

    // --- Search cache ------------------------------------------------------
    /** TTL for cached search results: 5 minutes. */
    private static final long SEARCH_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int SEARCH_PAGE_SIZE = 50;
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
        final int nextOffset;
        final boolean hasMore;
        final long timestamp;
        CacheEntry(List<NeteaseSong> songs, int nextOffset, boolean hasMore) {
            this.songs = Collections.unmodifiableList(new ArrayList<>(songs));
            this.nextOffset = nextOffset;
            this.hasMore = hasMore;
            this.timestamp = System.currentTimeMillis();
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > SEARCH_CACHE_TTL_MS;
        }
    }

    // Guards against stale async search results: set to the trimmed key before
    // each search(); async workers check equality before publishing results.
    private volatile String currentSearchKey = "";
    private volatile String currentSearchQuery = "";
    private volatile int searchNextOffset;
    private volatile boolean searchPageInFlight;
    /** Render-thread-owned provider buckets. Keeping one bucket per manifest order
     * prevents late async results from visually reordering other providers. */
    private final Map<String, List<Song>> pluginSearchByProvider = new LinkedHashMap<>();
    private final Map<String, String> pluginProviderNames = new LinkedHashMap<>();
    private final Map<String, String> pluginSearchCursors = new LinkedHashMap<>();
    private final AtomicLong pluginSearchGeneration = new AtomicLong();
    private volatile boolean pluginSearchPageInFlight;
    private final Map<String, List<LyricLine>> pluginLyricMem = Collections.synchronizedMap(
            new LinkedHashMap<String, List<LyricLine>>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<LyricLine>> e) {
                    return size() > LYRIC_MEM_MAX;
                }
            });

    // True after loadQueue() restores a previous session's track — toggle() will
    // call playAt() instead of resume() so the URL is freshly resolved.
    private volatile boolean needsReplay = false;
    // The saved position to resume at, and which queue slot it applies to (so
    // clicking a DIFFERENT track before resuming doesn't inherit the old song's
    // position). playAt() consumes both unconditionally on its very first call
    // after a restore, applying the offset only when the index still matches.
    private volatile long pendingResumeMs = 0L;
    private volatile int pendingResumeIndex = -1;
    // Set by autoAdvance() just before it calls playAt() for a track that finished on
    // its own, so the scrobble this playAt() fires for the *outgoing* track can tell a
    // natural finish from a manual skip. Consumed (reset) unconditionally on every
    // playAt() call, same one-shot pattern as pendingResumeMs/-Index above.
    private volatile boolean pendingNaturalEnd = false;
    private volatile String playLevel = "exhigh";
    // --- Volume fade in/out (Settings toggle) ------------------------------
    // Drives backend.setVolume() directly, never the public volume Property/
    // setVolume(float) (those are the user's own slider — a fade must not
    // overwrite what it displays). The clock deliberately lives outside the render
    // pump: Android can suspend the Surface and desktop can destroy its render thread
    // while audio keeps playing, and a frame-driven ramp would then remain stuck at a
    // small intermediate gain indefinitely.
    private volatile boolean fadeEnabled = false;
    private static final long FADE_IN_MS = 700;
    private static final long FADE_OUT_MS = 900;
    private static final long FADE_TICK_MS = 16;
    private final ScheduledExecutorService fadeWorker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "qplayer-volume-fade");
        t.setDaemon(true);
        return t;
    });
    private final Object fadeLock = new Object();
    // Guarded by fadeLock. Every start/cancel increments generation, so a queued tick
    // or completion belonging to an old track becomes a harmless no-op.
    private long fadeGeneration;
    private boolean fadeRunning;
    private long fadeStartNs;
    private long fadeDurationNs = TimeUnit.MILLISECONDS.toNanos(FADE_IN_MS);
    private float fadeFromGain = 1f;
    private float fadeToGain = 1f;
    private float fadeCurrentGain = 1f;
    private Runnable fadeCompleteAction;
    // Separate from the QML Property so the audio clock can read it safely and a fade
    // never mistakes its current effective gain for the user's requested volume.
    private volatile float userVolume = 0.8f;
    // Set once a fade-out has been STARTED for the CURRENT track, so pump()'s
    // near-the-end check doesn't keep re-triggering it every tick for the
    // remainder of playback.
    private volatile boolean fadeOutDoneForTrack = false;
    // Set by next()/prev() just before their playAt() call, consumed (and reset)
    // the next time startFadeIn() runs. The outgoing track may fade while an async
    // URL resolves, but the incoming one starts at full gain — another fade-in would
    // only add a second delay after however long the new track already took to load.
    private volatile boolean suppressNextFadeIn = false;
    private volatile long uid;
    // neteaseId of the track we last re-resolved after a playback error; cleared
    // when a track actually starts. Stops a persistently-failing track from looping
    // error→re-resolve→error forever instead of advancing.
    private volatile long errorRetryId = -1;
    private volatile String errorRetryMediaId = "";
    /** Number of consecutive tracks that failed before audio actually started.
     *  Bounds automatic skipping when an entire queue is unavailable. */
    private volatile int consecutivePlaybackFailures;
    private long lastPositionPush;
    /** Monotonic marker used by the host lyric renderer to identify explicit seeks. */
    private final AtomicLong seekRevision = new AtomicLong();
    /** Invalidates asynchronous cover/Monet work after every track change. */
    private final AtomicLong coverRevision = new AtomicLong();
    /** Render-thread-only ordering: the full cover may refine a thumbnail seed,
     *  but a late thumbnail must never overwrite the full-cover result. */
    private long appliedSeedRevision = -1L;
    private int appliedSeedQuality = -1;
    private long lastLogVersion = -1;
    private volatile boolean logVisible = false;

    // --- Playback state ---------------------------------------------------
    public final Property<Boolean> playing = new Property<>(false);
    public final Property<String> title = new Property<>("");
    public final Property<String> artist = new Property<>("");
    /** Id of the current track's first-listed artist (NETEASE source only, 0
     *  otherwise) -- lets the lyric page's artist name jump straight to that
     *  artist's page. */
    public final Property<Long> playingArtistId = new Property<>(0L);
    public final Property<String> playingArtistIdsCsv = new Property<>("");
    public final Property<String> playingArtistNamesCsv = new Property<>("");
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
    /** Playing track's local filePath, or "" when the current track isn't a LOCAL
     *  one. {@link #index} alone isn't enough to tell a local list's row it's the
     *  one playing: it's a position in whatever queue is currently loaded (a
     *  netease playlist, search results, ...), which coincidentally can equal a
     *  given row's position in an unrelated local-library view. Comparing by
     *  filePath instead of index sidesteps that — see VirtualSongList.highlightByFilePath. */
    public final Property<String> currentFilePath = new Property<>("");
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
    /** User-toggled cover view (LyricOverlay.qml's "switch to cover" button / tapping
     *  the cover to switch back) — independent of {@link #lyricsCoverOnly}'s automatic
     *  no-lyrics detection. The two are OR'd together wherever the effective cover-only
     *  state is needed (see LyricCompositor.drawLyricOverlay and LyricOverlay.qml's
     *  own `coverOnly`), so this only ever ADDS cover time, never hides a track that
     *  genuinely has lyrics against the user's wishes. */
    public final Property<Boolean> coverModeManual = new Property<>(Boolean.FALSE);
    /** Index of the current lyric line for player.positionMs, or -1. */
    public final Property<Integer> lyricIndex = new Property<>(-1);
    /** Whether the full-screen lyric page is open (host draws it via Skija). */
    public final Property<Boolean> lyricsOpen = new Property<>(false);
    /** Whether the lyric page's QML offset-adjust panel is open. The host-drawn lyric
     *  column has no QML underneath it, so its own tap = seek / drag = scroll gesture
     *  is normally recognized before any QML dispatch; while this is true the input
     *  layer skips that recognition so taps land on the panel's QML controls instead. */
    public final Property<Boolean> lyricOffsetPanelOpen = new Property<>(false);
    /** Timing correction for the current song only, in milliseconds. */
    public final Property<Integer> lyricOffsetMs = new Property<>(0);
    /** True from a track switch until the new source actually starts playing — the
     *  progress bars show a moving "loading" sweep while the (possibly async) source
     *  resolves. Cleared by the backend's onStarted, or on a failed/absent url. */
    public final Property<Boolean> loading = new Property<>(false);
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

    // --- Source plugins ---------------------------------------------------
    public final Property<List<PluginRow>> sourcePlugins =
            new Property<>(Collections.<PluginRow>emptyList());
    public final Property<String> primarySourcePlugin = new Property<>("");
    /** True when the selected provider owns the online discovery/detail surface. */
    public final Property<Boolean> sourceContentActive = new Property<>(false);
    public final Property<String> pendingPluginName = new Property<>("");
    public final Property<String> pendingPluginId = new Property<>("");
    public final Property<String> pendingPluginVersion = new Property<>("");
    public final Property<String> pendingPluginPermissions = new Property<>("");
    public final Property<Boolean> pendingPluginTrusted = new Property<>(false);
    public final Property<Long> pluginInstallPromptRevision = new Property<>(0L);
    public final Property<Boolean> pluginInstallBusy = new Property<>(false);
    public final Property<String> pendingPluginRemovalId = new Property<>("");
    public final Property<String> pendingPluginRemovalName = new Property<>("");
    public final Property<Long> pluginRemovalPromptRevision = new Property<>(0L);
    public final Property<List<PluginCatalogEntry>> pluginCatalogEntries =
            new Property<>(Collections.<PluginCatalogEntry>emptyList());
    public final Property<Boolean> pluginCatalogLoading = new Property<>(false);
    public final Property<List<PluginUiContributionRow>> pluginUiContributions =
            new Property<>(Collections.<PluginUiContributionRow>emptyList());
    /** Per-plugin settings navigation event. */
    public final Property<String> pluginSettingsId = new Property<>("");
    public final Property<Long> pluginSettingsRevision = new Property<>(0L);
    /** No enabled primary provider is available. Home renders a setup action instead
     * of pretending an online request is still connecting. */
    public final Property<Boolean> sourceSetupRequired = new Property<>(true);
    /** Auto-open only until the user installs a source or explicitly chooses local-only. */
    public final Property<Boolean> sourceSetupPending = new Property<>(false);
    /** Monotonic event used by Home/Settings to reopen the global setup dialog. */
    public final Property<Long> sourceSetupRevision = new Property<>(0L);
    /** Older provider credentials or queued online tracks can be migrated after install. */
    public final Property<Boolean> legacySourceMigrationAvailable = new Property<>(false);

    // --- Netease content (Repeater model: player.xxx; delegate reads modelData) ---
    public final Property<List<NeteaseSong>> searchResults = new Property<>(Collections.<NeteaseSong>emptyList());
    public final Property<Integer> resultCount = new Property<>(0);
    public final Property<Boolean> searchLoading = new Property<>(false);
    public final Property<Boolean> searchHasMore = new Property<>(false);
    /** Unified view over online plugin results and {@link #localSearchResults} for
     *  SearchPage.qml's single results list. Rebuilt by {@link #rebuildSearchRows()}
     *  whenever any of the three source lists changes. In "album"/"artist"
     *  {@link #searchMode}, rebuilt from {@link #searchAlbumResults}/
     *  {@link #searchArtistResults} instead (local files have
     *  no album/artist entities of their own). */
    public final Property<List<SearchRow>> searchRows = new Property<>(Collections.<SearchRow>emptyList());
    /** SearchPage's type filter: "song" (default) | "album" | "artist". Selects
     *  which of {@link #search}/{@link #searchAlbums(String)}/{@link #searchArtists(String)}
     *  drives {@link #searchRows}. */
    public final Property<String> searchMode = new Property<>("song");
    public final Property<List<NeteaseAlbum>> searchAlbumResults = new Property<>(Collections.<NeteaseAlbum>emptyList());
    public final Property<List<NeteaseArtist>> searchArtistResults = new Property<>(Collections.<NeteaseArtist>emptyList());
    public final Property<List<Album>> sourceSearchAlbumResults =
            new Property<>(Collections.<Album>emptyList());
    public final Property<List<Artist>> sourceSearchArtistResults =
            new Property<>(Collections.<Artist>emptyList());
    /** Local-library matches for the same query text, shown alongside searchResults. */
    public final Property<List<Track>> localSearchResults = new Property<>(Collections.<Track>emptyList());
    /** Hot search keywords shown when search input is empty. */
    public final Property<List<String>> hotSearches = new Property<>(Collections.<String>emptyList());
    /** User's search history (most recent first, max {@value #HISTORY_MAX} entries). */
    public final Property<List<String>> searchHistory = new Property<>(Collections.<String>emptyList());
    public final Property<List<NeteaseSong>> recommendations = new Property<>(Collections.<NeteaseSong>emptyList());
    public final Property<List<NeteasePlaylist>> recommendPlaylists = new Property<>(Collections.<NeteasePlaylist>emptyList());
    public final Property<List<Song>> sourceRecommendations =
            new Property<>(Collections.<Song>emptyList());
    public final Property<List<Playlist>> sourceRecommendPlaylists =
            new Property<>(Collections.<Playlist>emptyList());
    /** True while {@link #loadHome} is in flight — lets HomePage.qml tell "still
     *  loading" from "tried and failed" (both look like empty lists otherwise) so
     *  it can show a tap-to-retry affordance instead of a permanent spinner. */
    public final Property<Boolean> homeLoading = new Property<>(Boolean.FALSE);
    public final Property<List<NeteasePlaylist>> myPlaylists = new Property<>(Collections.<NeteasePlaylist>emptyList());
    public final Property<List<NeteaseSong>> recentSongs = new Property<>(Collections.<NeteaseSong>emptyList());
    public final Property<List<Playlist>> sourceMyPlaylists =
            new Property<>(Collections.<Playlist>emptyList());
    public final Property<List<Song>> sourceRecentSongs =
            new Property<>(Collections.<Song>emptyList());
    public final Property<Boolean> sourcePlaylistMutationAvailable = new Property<>(false);
    public final Property<Boolean> sourceHeartRecommendationAvailable = new Property<>(false);
    /** Currently opened playlist. */
    public final Property<List<NeteaseSong>> playlistTracks = new Property<>(Collections.<NeteaseSong>emptyList());
    public final Property<List<Song>> sourcePlaylistTracks =
            new Property<>(Collections.<Song>emptyList());
    public final Property<String> playlistTitle = new Property<>("");
    /** Cover for the currently open playlist — netease CDN thumb, or empty while
     *  loading/absent. {@code CoverImage.source} accepts this directly (http url). */
    public final Property<String> playlistCoverPath = new Property<>("");
    /** True while an opened playlist's tracks are loading, so the detail page shows a
     *  spinner instead of the previous playlist's content. */
    public final Property<Boolean> playlistLoading = new Property<>(false);
    /** True once {@link #openPlaylist} actually had to fall back to
     *  {@link #offlinePlaylistFallback} for the currently-open playlist -- i.e. the
     *  live netease call failed, not just "offline mode by user choice". Drives
     *  SongRow's offline-ready badge (only shown while this is true, so it doesn't
     *  clutter the normal online view) and {@link #retryPlaylistIfOffline}'s retry
     *  loop. Cleared the moment a real online refresh of this playlist succeeds. */
    public final Property<Boolean> playlistOffline = new Property<>(false);
    /** Id of the currently open playlist (0 = none); guards stale async results. */
    private volatile long currentPlaylistId;
    /** Whether the signed-in user has collected the open playlist — drives the detail
     *  page's collect icon. Resolved from playlist/detail, so it reflects the real state
     *  on open (no guessing). */
    public final Property<Boolean> playlistSubscribed = new Property<>(false);
    /** Guards against stacking subscribe requests: a collect is heavily risk-controlled,
     *  so a second tap while one is in flight is ignored rather than re-fired. */
    private volatile boolean subscribeBusy;
    /** Whether the open playlist is the user's own (can't collect your own). */
    public final Property<Boolean> playlistOwned = new Property<>(false);
    /** Whether the open playlist can be deleted: owned AND not the "我喜欢的音乐"
     *  default (netease forbids removing it). Drives the detail page's delete icon. */
    public final Property<Boolean> playlistDeletable = new Property<>(false);
    /** True while the server is building a heart-mode continuation. */
    public final Property<Boolean> intelligenceLoading = new Property<>(false);
    /** Id of the "我喜欢的音乐" playlist (the user's first/owned default), captured on
     *  loadMyPlaylists; 0 until known. Compared in Java so QML needn't equate Longs. */
    private volatile long favoritePid;
    /** Id of the open playlist, mirrored to QML (the volatile above isn't exposed).
     *  Lets the detail page pass the id back for delete / remove-track actions. */
    public final Property<Long> openPlaylistId = new Property<>(0L);
    public final Property<String> openSourcePlaylistId = new Property<>("");
    /** Currently opened artist (drill-in from a song row's artist name / an
     *  album's artist credit). Main.qml owns the actual navigation stack; the
     *  open flags mirror only its currently visible route for host/QML state. */
    public final Property<Long> openArtistId = new Property<>(0L);
    public final Property<String> openSourceArtistId = new Property<>("");
    public final Property<Boolean> artistPageOpen = new Property<>(false);
    public final Property<Boolean> albumPageOpen = new Property<>(false);
    /** Every artist/album navigation request bumps this revision, including a
     *  second artist opened while an artist page is already visible. A boolean
     *  open flag cannot represent that case, so Main.qml consumes this event and
     *  pushes a route carrying {@link #openArtistId} or {@link #openAlbumId}. */
    public final Property<String> pageNavigationTarget = new Property<>("");
    public final Property<Long> pageNavigationRevision = new Property<>(0L);
    public final Property<String> pageNavigationEntityId = new Property<>("");
    private long pageNavigationSequence;
    /** Shared song-credit picker for SongRow and SongContextMenu. Multiple
     *  artists are exposed here for SongArtistsDialog; a single artist bypasses
     *  the dialog and opens directly. */
    public final Property<Boolean> songArtistPickerOpen = new Property<>(false);
    public final Property<List<NeteaseSong.ArtistRef>> songArtistPickerList =
            new Property<>(Collections.<NeteaseSong.ArtistRef>emptyList());
    /** Guards the picker's background avatar fetch (below) against a newer
     *  "查看歌手" click superseding a slower, still-in-flight older one. */
    private volatile long songArtistPickerRevision;
    /** Mirrors Main.qml's `app.wide` (width >= 600) -- QML pushes this over on
     *  every change (and once at startup) since Java has no window-layout
     *  awareness of its own. Read by {@link #gridCoverSize()} to pick a
     *  playlist cover's fetch resolution: a phone/narrow window's card grid
     *  doesn't need the same pixels a desktop wide layout displays at. */
    public final Property<Boolean> wideLayout = new Property<>(false);
    /** See {@link #setPixelRatio(double)}. */
    public final Property<Double> pixelRatio = new Property<>(1.0);
    public final Property<String> artistName = new Property<>("");
    public final Property<String> artistCoverPath = new Property<>("");
    public final Property<String> artistBriefDesc = new Property<>("");
    public final Property<Boolean> artistLoading = new Property<>(false);
    public final Property<List<NeteaseSong>> artistSongs = new Property<>(Collections.<NeteaseSong>emptyList());
    public final Property<List<NeteaseAlbum>> artistAlbums = new Property<>(Collections.<NeteaseAlbum>emptyList());
    public final Property<List<Song>> sourceArtistSongs =
            new Property<>(Collections.<Song>emptyList());
    public final Property<List<Album>> sourceArtistAlbums =
            new Property<>(Collections.<Album>emptyList());
    /** Guards against a slower, superseded fetch overwriting a newer {@link #openArtist} call. */
    private volatile long currentArtistId;
    /** Currently opened album (drill-in from a song row's album name / an
     *  artist's album list). */
    public final Property<Long> openAlbumId = new Property<>(0L);
    public final Property<String> openSourceAlbumId = new Property<>("");
    public final Property<String> albumName = new Property<>("");
    public final Property<String> albumCoverPath = new Property<>("");
    public final Property<String> albumArtistName = new Property<>("");
    public final Property<Long> albumArtistId = new Property<>(0L);
    public final Property<String> albumArtistMediaId = new Property<>("");
    /** Release year, pre-formatted ("2019年") so QML doesn't need Date parsing; empty if unknown. */
    public final Property<String> albumPublishYear = new Property<>("");
    public final Property<Boolean> albumLoading = new Property<>(false);
    public final Property<List<NeteaseSong>> albumTracks = new Property<>(Collections.<NeteaseSong>emptyList());
    public final Property<List<Song>> sourceAlbumTracks =
            new Property<>(Collections.<Song>emptyList());
    /** Guards against a slower, superseded fetch overwriting a newer {@link #openAlbum} call. */
    private volatile long currentAlbumId;
    /** Snapshot of the live play queue for the queue page; current track is {@link #index}. */
    public final Property<List<Track>> queueTracks = new Property<>(Collections.<Track>emptyList());
    public final Property<Boolean> queueOpen = new Property<>(false);
    /** Snapshot of {@link #customPlaylist} for the queue page's second tab. */
    public final Property<List<Track>> customPlaylistTracks = new Property<>(Collections.<Track>emptyList());
    /** Snapshot of the offline-cached netease songs (cache/audio/*.cache), shown in
     *  the rail's download menu; rebuilt on open via {@link #refreshCachedSongs}. */
    public final Property<List<Track>> cachedSongs = new Property<>(Collections.<Track>emptyList());

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
    /** Standardized login metadata for the current primary source. */
    public final Property<String> loginProviderName = new Property<>("音源账号");
    public final Property<Boolean> pluginLoginActive = new Property<>(false);
    public final Property<List<LoginMethod>> loginMethods =
            new Property<>(Collections.<LoginMethod>emptyList());
    public final Property<Boolean> pluginQrLoginAvailable = new Property<>(false);
    public final Property<Boolean> pluginCredentialLoginAvailable = new Property<>(false);
    public final Property<String> loginWebInstructions = new Property<>(
            "将在系统 WebView 中打开官方网站。登录成功后，QPlayer 会自动读取登录凭据、验证账号并加密保存。");
    public final Property<String> loginCredentialInstructions = new Property<>(
            "在官方网站登录后，复制请求头中的 Cookie 值并粘贴到下方。凭据仅用于验证，成功后会加密保存。");
    public final Property<String> loginCredentialLabel = new Property<>("Cookie 请求头");

    /** Generic plugin playback coordination; no protocol or provider semantics. */
    private volatile String pluginAutoAdvanceBlocker = "";
    private final AtomicLong playbackEndRevision = new AtomicLong();
    private volatile boolean appShuttingDown;

    // --- Debug ------------------------------------------------------------
    public final Property<String> logText = new Property<>("");
    /** Transient user-facing message; the UI shows a Snackbar when it changes. */
    public final Property<String> toast = new Property<>("");
    /** 1 encrypted, 2 platform-store fallback, 3 platform-store read failure. */
    public final Property<Integer> credentialNoticeType = new Property<>(0);
    /** Monotonic trigger so the same notice can be shown again after a later login. */
    public final Property<Long> credentialNoticeRevision = new Property<>(0L);
    /** True while credentials use the weaker owner-readable local key. */
    public final Property<Boolean> credentialOwnerOnlyFallback = new Property<>(false);
    public final Property<Boolean> credentialProtectionBusy = new Property<>(false);
    /** 1 = encrypted relogin may proceed, 2 = platform store still unavailable. */
    public final Property<Integer> credentialReloginResult = new Property<>(0);
    public final Property<Long> credentialReloginRevision = new Property<>(0L);
    private volatile boolean credentialReloginBusy;
    private volatile boolean pendingCredentialEncryptedNotice;
    private volatile boolean legacyCredentialMigrationAttempted;
    private final AtomicLong legacyCredentialMigrationGeneration = new AtomicLong();

    /** Sets {@link #toast} to {@code msg}, forcing a Snackbar even if it's the
     *  exact same text as last time. qml4j's property-changed notification
     *  doesn't fire when a Property is set to a value equal to its current
     *  one, so a plain {@code toast.set(msg)} silently no-ops on a repeat
     *  (e.g. tapping Settings > 关于's "检查更新" twice with nothing newer
     *  either time — the second tap's ripple fires but no Snackbar appears).
     *  Clearing to "" and immediately back to {@code msg} within the same
     *  synchronous callback wasn't enough either — the two writes land in the
     *  same render pass and get coalesced into "no net change", so the clear
     *  is pushed out with a genuine delay to land in a separate frame before
     *  the real message. Safe to call from any thread. */
    private void showToast(String msg) {
        post(() -> toast.set(""));
        worker.submit(() -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            post(() -> toast.set(msg));
        });
    }

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
        netease.setCredentialListener(this::showCredentialNotice);
        pluginHostApi.setCredentialListener(this::showPluginCredentialNotice);
        pluginHostApi.setAppBridge(new CorePluginHostApi.AppBridge() {
            @Override public CompletableFuture<Object> call(String pluginId, String method,
                                                              Map<String, Object> arguments) {
                return pluginAppCall(pluginId, method, arguments);
            }
            @Override public void unavailable(String pluginId) {
                pluginAppUnavailable(pluginId);
            }
        });
        credentialOwnerOnlyFallback.set(pluginHostApi.usesOwnerOnlyCredentialProtection());
        backend.setVolume(volume.peek());
        backend.setOnComplete(() -> onMain(this::autoAdvance));
        // Re-baseline the media session's position once audio actually starts (the
        // backend prepares asynchronously, so the position at play() time is stale).
        backend.setOnStarted(() -> {
            errorRetryId = -1;
            errorRetryMediaId = "";
            consecutivePlaybackFailures = 0;
            stoppedLyricPositionMs = Math.max(0L, backend.position());
            playbackStarted = true;
            post(() -> loading.set(false));
            Boolean pluginDesired = pendingPluginDesiredPlaying;
            Track startedTrack = currentTrack();
            if (pluginDesired != null && startedTrack != null
                    && !pendingPluginTargetMediaId.isEmpty()
                    && pendingPluginTargetMediaId.equals(startedTrack.canonicalId())) {
                pendingPluginDesiredPlaying = null;
                pendingPluginTargetMediaId = "";
                if (!pluginDesired) {
                    cancelFadeAtGain(1f);
                    backend.pause();
                    playingIntent = false;
                    post(() -> playing.set(false));
                    notifyPlayback();
                    return;
                }
            }
            notifyPlayback();
            startFadeIn();
        });
        // Audio-focus driven pause/resume (phone call, another player): keep the
        // intended-play state, the UI, and the media session in sync.
        backend.setOnPaused(() -> {
            // Audio focus is an external discontinuity, not part of a user-requested
            // ramp. Settle at full logical gain while silent so focus regain cannot
            // inherit an interrupted fade's small intermediate value.
            cancelFadeAtGain(1f);
            playingIntent = false;
            post(() -> playing.set(false));
            notifyPlayback();
        });
        backend.setOnResumed(() -> {
            cancelFadeAtGain(1f);
            playingIntent = true;
            post(() -> playing.set(true));
            notifyPlayback();
        });
        // On playback error: retry netease tracks whose cached streamUrl went stale
        // (expired VIP link, region lock, etc.). Non-netease or already-retried
        // tracks fall through to autoAdvance.
        backend.setOnError(() -> onMain(this::onPlaybackError));
        worker.submit(this::loadSearchHistory);
        loadLyricOffsets();
        mediaMetaIndex.load();
        mediaPlaylistCacheIndex.load();
        playlistCacheIndex.load();
        diskCache.loadActivelyCached();
        pluginManager.startEnabled();
        loadQueue();
        loadCustomPlaylist();
        legacySourceMigrationAvailable.set(detectLegacySourceMigration());
        publishPlugins();
        refreshPluginCatalog();
        if (!onlineSourcesArePluginOnly() && primaryProvider() == null && netease.isLoggedIn()) {
            loggedIn.set(true);
            refreshLogin();
        }
    }

    /** Enable/disable an installed provider without coupling it to a QML scene. */
    public void setSourcePluginEnabled(String pluginId, boolean enabled) {
        worker.submit(() -> {
            try {
                if (enabled) pluginManager.enable(pluginId);
                else pluginManager.disable(pluginId);
                post(this::publishPlugins);
            } catch (Throwable error) {
                showToast("插件状态更新失败：" + error.getMessage());
            }
        });
    }

    public void setPrimarySourcePlugin(String pluginId) {
        worker.submit(() -> {
            try {
                pluginRegistry.setPrimaryProvider(pluginId);
                post(this::publishPlugins);
            } catch (Throwable error) {
                showToast("无法切换主音源：" + error.getMessage());
            }
        });
    }

    /** One-step setup action for an already installed but disabled/non-primary
     * provider. Keeping both registry writes on the worker avoids the enable/primary
     * race that two independent QML button calls would create. */
    public void activateSourcePlugin(String pluginId) {
        if (pluginId == null || pluginId.isEmpty()) return;
        worker.submit(() -> {
            try {
                PluginRegistry.Entry entry = pluginRegistry.get(pluginId);
                if (entry == null) throw new IllegalArgumentException("音源插件尚未安装");
                if (!entry.enabled) pluginManager.enable(pluginId);
                pluginRegistry.setPrimaryProvider(pluginId);
                post(this::publishPlugins);
            } catch (Throwable error) {
                showToast("无法启用音源插件：" + safeMessage(error));
            }
        });
    }

    public void requestSourcePluginRemoval(String pluginId) {
        PluginRegistry.Entry entry = pluginRegistry.get(pluginId);
        if (entry == null || Boolean.TRUE.equals(pluginInstallBusy.peek())) return;
        pendingPluginRemovalId.set(entry.id);
        pendingPluginRemovalName.set(entry.name);
        pluginRemovalPromptRevision.set(pluginRemovalPromptRevision.peek() + 1L);
    }

    public void cancelSourcePluginRemoval() {
        pendingPluginRemovalId.set("");
        pendingPluginRemovalName.set("");
    }

    public void confirmSourcePluginRemoval() {
        final String pluginId = pendingPluginRemovalId.peek();
        if (pluginId == null || pluginId.isEmpty()
                || Boolean.TRUE.equals(pluginInstallBusy.peek())) return;
        pluginInstallBusy.set(true);
        worker.submit(() -> {
            try {
                pluginManager.remove(pluginId);
                post(() -> {
                    cancelSourcePluginRemoval();
                    pluginInstallBusy.set(false);
                    publishPlugins();
                });
                showToast("音源插件已移除");
            } catch (Throwable error) {
                post(() -> pluginInstallBusy.set(false));
                showToast("移除插件失败：" + safeMessage(error));
            }
        });
    }

    private void publishPlugins() {
        String primary = pluginRegistry.primaryProvider();
        List<PluginRow> rows = new ArrayList<>();
        for (PluginRegistry.Entry entry : pluginRegistry.entries()) {
            PluginRow row = new PluginRow();
            row.id = entry.id;
            row.name = entry.name;
            row.version = entry.activeVersion;
            row.enabled = entry.enabled;
            row.primary = entry.id.equals(primary);
            row.signed = entry.signed;
            row.permissions = String.join(" · ", entry.grantedPermissions);
            rows.add(row);
        }
        sourcePlugins.set(rows);
        pluginUiContributions.set(pluginManager.uiContributions());
        publishCatalogInstalledState();
        primarySourcePlugin.set(primary);
        boolean sourceReady = primaryProvider() != null;
        sourceContentActive.set(sourceReady);
        sourceSetupRequired.set(!sourceReady);
        if (sourceReady) pluginSetupState.acknowledge();
        sourceSetupPending.set(!sourceReady && !pluginSetupState.acknowledged());
        sourcePlaylistMutationAvailable.set(primaryProviderWith(
                ProviderCapability.PLAYLIST_MUTATION) != null);
        sourceHeartRecommendationAvailable.set(primaryProviderWith(
                ProviderCapability.HEART_RECOMMENDATION) != null);
        pluginLikedSet.clear();
        currentLiked.set(false);
        routeInstalledPluginTracks();
        refreshPrimaryPluginLoginMetadata();
        if (!primary.isEmpty()) loadHome();
    }

    /** Open the app-wide source setup flow from Home or any future empty state. */
    public void requestSourceSetup() {
        if (pluginCatalogEntries.peek() == null || pluginCatalogEntries.peek().isEmpty()) {
            refreshPluginCatalog();
        }
        sourceSetupRevision.set(sourceSetupRevision.peek() + 1L);
    }

    /** Navigate to one host-owned plugin settings page. The page may represent
     * either an installed plugin or an entry that is only available in catalog. */
    public void requestPluginSettings(String pluginId) {
        if (pluginId == null || pluginId.trim().isEmpty()) return;
        pluginSettingsId.set(pluginId.trim());
        pluginSettingsRevision.set(pluginSettingsRevision.peek() + 1L);
    }

    /** Provider-neutral services for plugin-owned app features. Protocol state,
     * room semantics and synchronization policy deliberately stay in JavaScript. */
    private CompletableFuture<Object> pluginAppCall(String pluginId, String method,
                                                     Map<String, Object> arguments) {
        Map<String, Object> args = arguments != null ? arguments : Collections.emptyMap();
        switch (method) {
            case "playback.read":
                return onMainResult(() -> pluginPlaybackSnapshot(pluginId));
            case "playback.play":
                return onMainResult(() -> { mediaResume(); return Boolean.TRUE; });
            case "playback.pause":
                return onMainResult(() -> { mediaPause(); return Boolean.TRUE; });
            case "playback.seek": {
                long target = boundedPluginLong(args.get("positionMs"), 0L, 31L * 24L * 60L * 60L * 1000L);
                return onMainResult(() -> { mediaSeek(target); return Boolean.TRUE; });
            }
            case "playback.select": {
                String songId = pluginSongId(pluginId, args.get("songId"));
                long target = boundedPluginLong(args.get("positionMs"), 0L,
                        31L * 24L * 60L * 60L * 1000L);
                boolean shouldPlay = !Boolean.FALSE.equals(args.get("playing"));
                return onMainResult(() -> selectPluginTrack(songId, target, shouldPlay));
            }
            case "playback.next":
                return onMainResult(() -> { performAutoAdvance(); return Boolean.TRUE; });
            case "playback.blockAutoAdvance": {
                boolean block = Boolean.TRUE.equals(args.get("blocked"));
                return onMainResult(() -> {
                    if (block) pluginAutoAdvanceBlocker = pluginId;
                    else if (pluginId.equals(pluginAutoAdvanceBlocker)) {
                        pluginAutoAdvanceBlocker = "";
                        if (pendingNaturalEnd && !queue.isEmpty()) performAutoAdvance();
                    }
                    return Boolean.TRUE;
                });
            }
            case "queue.replace": {
                List<Song> songs = pluginProviders.validateSongs(pluginId, args.get("songs"));
                String songId = pluginSongId(pluginId, args.get("currentSongId"));
                long target = boundedPluginLong(args.get("positionMs"), 0L,
                        31L * 24L * 60L * 60L * 1000L);
                boolean shouldPlay = !Boolean.FALSE.equals(args.get("playing"));
                return onMainResult(() -> replacePluginQueue(songs, songId, target, shouldPlay));
            }
            case "notifications.toast":
                showToast(pluginText(args.get("message"), 500));
                return CompletableFuture.completedFuture(Boolean.TRUE);
            case "clipboard.write": {
                String text = pluginText(args.get("text"), 16 * 1024);
                java.util.function.Consumer<String> sink = clipboard;
                if (sink == null) return failedPluginCall("clipboard is unavailable");
                sink.accept(text);
                return CompletableFuture.completedFuture(Boolean.TRUE);
            }
            default:
                return failedPluginCall("host method is not implemented: " + method);
        }
    }

    private Map<String, Object> pluginPlaybackSnapshot(String pluginId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Track current = currentTrack();
        String currentId = current != null && pluginId.equals(providerOf(current))
                ? nativeSongId(current) : "";
        List<String> ids = new ArrayList<>();
        boolean queueOwned = true;
        for (Track track : queue) {
            if (!pluginId.equals(providerOf(track))) { queueOwned = false; break; }
            ids.add(nativeSongId(track));
        }
        result.put("currentSongId", currentId);
        result.put("queueSongIds", queueOwned ? ids : Collections.emptyList());
        result.put("positionMs", Math.max(0L, backend.position()));
        result.put("durationMs", Math.max(0L, backend.duration()));
        result.put("playing", playingIntent);
        result.put("transitioning", pendingPluginDesiredPlaying != null);
        result.put("seekRevision", seekRevision.get());
        result.put("endRevision", playbackEndRevision.get());
        return result;
    }

    private Boolean selectPluginTrack(String canonicalId, long target, boolean shouldPlay) {
        int targetIndex = -1;
        for (int i = 0; i < queue.size(); i++) {
            if (canonicalId.equals(queue.get(i).canonicalId())) { targetIndex = i; break; }
        }
        if (targetIndex < 0) throw new IllegalArgumentException("target song is not in the queue");
        Track current = currentTrack();
        if (current == null || !canonicalId.equals(current.canonicalId())) {
            pendingPluginDesiredPlaying = shouldPlay;
            pendingPluginTargetMediaId = canonicalId;
            pendingResumeMs = target;
            pendingResumeIndex = targetIndex;
            playAt(targetIndex);
        } else {
            mediaSeek(target);
            if (shouldPlay) mediaResume(); else mediaPause();
        }
        return Boolean.TRUE;
    }

    private Boolean replacePluginQueue(List<Song> songs, String canonicalId, long target,
                                       boolean shouldPlay) {
        List<Track> tracks = new ArrayList<>(songs.size());
        for (Song song : songs) tracks.add(toTrackPlugin(song));
        int targetIndex = -1;
        for (int i = 0; i < tracks.size(); i++) {
            if (canonicalId.equals(tracks.get(i).canonicalId())) { targetIndex = i; break; }
        }
        if (targetIndex < 0) throw new IllegalArgumentException("target song is not in replacement queue");
        queue.clear();
        queue.addAll(tracks);
        currentQueuePlaylistId = 0L;
        currentQueueMediaPlaylistId = "";
        post(() -> queueTracks.set(new ArrayList<>(queue)));
        return selectPluginTrack(canonicalId, target, shouldPlay);
    }

    private static String nativeSongId(Track track) {
        try { return MediaId.parse(track.canonicalId()).nativeId(); }
        catch (IllegalArgumentException ignored) { return ""; }
    }

    private static String pluginSongId(String pluginId, Object raw) {
        String value = pluginText(raw, 2048);
        if (value.isEmpty()) return "";
        if (value.indexOf(':') >= 0) {
            MediaId id = MediaId.parse(value).requireKind(dev.t1m3.qplayer.media.MediaKind.SONG);
            if (!pluginId.equals(id.provider())) throw new IllegalArgumentException("song provider mismatch");
            return id.toString();
        }
        return MediaId.of(pluginId, dev.t1m3.qplayer.media.MediaKind.SONG, value).toString();
    }

    private static String pluginText(Object raw, int limit) {
        String value = raw == null ? "" : String.valueOf(raw);
        if (value.length() > limit) throw new IllegalArgumentException("plugin text is too long");
        return value;
    }

    private static long boundedPluginLong(Object raw, long min, long max) {
        if (!(raw instanceof Number)) return min;
        long value = ((Number) raw).longValue();
        return Math.max(min, Math.min(max, value));
    }

    private CompletableFuture<Object> onMainResult(java.util.concurrent.Callable<Object> action) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        onMain(() -> {
            try { future.complete(action.call()); }
            catch (Throwable error) { future.completeExceptionally(error); }
        });
        return future;
    }

    private static CompletableFuture<Object> failedPluginCall(String message) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        future.completeExceptionally(new UnsupportedOperationException(message));
        return future;
    }

    private void pluginAppUnavailable(String pluginId) {
        onMain(() -> {
            if (!pluginId.equals(pluginAutoAdvanceBlocker)) return;
            pluginAutoAdvanceBlocker = "";
            if (!appShuttingDown && pendingNaturalEnd && !queue.isEmpty()) performAutoAdvance();
        });
    }

    /** Remember an explicit local-only choice so onboarding is not forced again on
     * every launch. Home keeps a persistent setup button for changing that choice. */
    public void dismissSourceSetup() {
        pluginSetupState.acknowledge();
        sourceSetupPending.set(false);
    }

    private boolean detectLegacySourceMigration() {
        if (netease.hasStoredLegacyCredentials()) return true;
        for (Track track : queue) if (isLegacyOnlineTrack(track)) return true;
        for (Track track : customPlaylist) if (isLegacyOnlineTrack(track)) return true;
        return false;
    }

    private static boolean isLegacyOnlineTrack(Track track) {
        if (track == null || track.source == Track.Source.LOCAL) return false;
        try {
            return "netease".equals(MediaId.parse(track.canonicalId()).provider());
        } catch (IllegalArgumentException ignored) {
            return track.source == Track.Source.NETEASE;
        }
    }

    private void publishCatalogInstalledState() {
        List<PluginCatalogEntry> current = pluginCatalogEntries.peek();
        if (current == null || current.isEmpty()) return;
        markCatalogInstalledState(current);
        pluginCatalogEntries.set(new ArrayList<>(current));
        publishPluginUpdatePrompt();
    }

    private void markCatalogInstalledState(List<PluginCatalogEntry> entries) {
        Map<String, PluginRegistry.Entry> installed = new HashMap<>();
        for (PluginRegistry.Entry value : pluginRegistry.entries()) installed.put(value.id, value);
        for (PluginCatalogEntry entry : entries) {
            PluginRegistry.Entry current = installed.get(entry.id);
            entry.installed = current != null;
            entry.installedVersion = current != null ? current.activeVersion : "";
            entry.updateAvailable = current != null
                    && PluginCompatibility.compare(entry.version, current.activeVersion) > 0;
        }
    }

    // --- Plugin update prompt ----------------------------------------------
    // A newer plugin release is announced with a dialog, but never on top of the
    // app's own update dialog: a QPlayer release can change what a plugin needs
    // (minHostVersion), so updating the app first is the order that cannot get
    // stuck, and two stacked update dialogs on startup is the collision this
    // exists to avoid. QML re-checks on the app dialog's close, so whichever
    // check finishes first, the plugin prompt still gets its turn.

    /** True while a plugin update prompt is waiting to be shown or is showing. */
    public final Property<Boolean> pluginUpdateAvailable = new Property<>(false);
    public final Property<String> pluginUpdateId = new Property<>("");
    public final Property<String> pluginUpdateName = new Property<>("");
    public final Property<String> pluginUpdateVersion = new Property<>("");
    public final Property<String> pluginUpdateInstalledVersion = new Property<>("");

    /** id@version already offered in this session, so a periodic catalog refresh
     *  does not re-prompt for something the user dismissed. */
    private final java.util.Set<String> promptedPluginUpdates =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** QML calls this once the prompt has been accepted or dismissed. */
    public void acknowledgePluginUpdate() {
        pluginUpdateAvailable.set(false);
        pluginUpdateId.set("");
        pluginUpdateName.set("");
        pluginUpdateVersion.set("");
        pluginUpdateInstalledVersion.set("");
        // Another installed plugin may also be out of date; offer the next one.
        publishPluginUpdatePrompt();
    }

    /**
     * QML calls this when the app updater pre-empts a plugin prompt that was
     * already on screen. Unlike {@link #acknowledgePluginUpdate()} this does not
     * consume the offer: the key is released so the same plugin is proposed
     * again once the app dialog is gone.
     */
    public void deferPluginUpdate() {
        promptedPluginUpdates.remove(pluginUpdateId.peek() + "@" + pluginUpdateVersion.peek());
        pluginUpdateAvailable.set(false);
    }

    /** QML calls this when the app-update dialog closes, releasing the hold. */
    public void appUpdatePromptClosed() {
        appUpdatePromptDismissed = true;
        publishPluginUpdatePrompt();
    }

    private volatile boolean appUpdatePromptDismissed;

    private void publishPluginUpdatePrompt() {
        if (Boolean.TRUE.equals(pluginUpdateAvailable.peek())) return;
        if (Boolean.TRUE.equals(updateAvailable.peek()) && !appUpdatePromptDismissed) return;
        List<PluginCatalogEntry> entries = pluginCatalogEntries.peek();
        if (entries == null) return;
        for (PluginCatalogEntry entry : entries) {
            if (!entry.updateAvailable) continue;
            if (!promptedPluginUpdates.add(entry.id + "@" + entry.version)) continue;
            pluginUpdateId.set(entry.id);
            pluginUpdateName.set(entry.name);
            pluginUpdateVersion.set(entry.version);
            pluginUpdateInstalledVersion.set(entry.installedVersion);
            pluginUpdateAvailable.set(true);
            return;
        }
    }

    public void refreshPluginCatalog() {
        if (Boolean.TRUE.equals(pluginCatalogLoading.peek())) return;
        pluginCatalogLoading.set(true);
        worker.submit(() -> {
            try {
                List<PluginCatalogEntry> entries = pluginCatalog.loadLatest(updateMirror);
                markCatalogInstalledState(entries);
                final List<PluginCatalogEntry> publishedEntries = entries;
                post(() -> {
                    pluginCatalogEntries.set(publishedEntries);
                    pluginCatalogLoading.set(false);
                    publishPluginUpdatePrompt();
                });
            } catch (Throwable error) {
                post(() -> pluginCatalogLoading.set(false));
                Logger.warn("plugin release lookup failed: {}", error.getMessage());
                showToast("获取插件列表失败");
            }
        });
    }

    public void installCatalogPlugin(String pluginId) {
        if (pluginId == null || pluginId.isEmpty() || Boolean.TRUE.equals(pluginInstallBusy.peek())) return;
        PluginCatalogEntry selected = null;
        for (PluginCatalogEntry entry : pluginCatalogEntries.peek()) {
            if (pluginId.equals(entry.id)) { selected = entry; break; }
        }
        if (selected == null) { showToast("插件列表中不存在该项目"); return; }
        final PluginCatalogEntry entry = selected;
        pluginInstallBusy.set(true);
        worker.submit(() -> {
            try {
                VerifiedPluginPackage verified = pluginCatalog.downloadAndVerify(
                        entry, GitHubDownloadUrls.candidates(entry.downloadUrl, updateMirror));
                pendingPluginPackage = verified;
                pendingPluginPackageTemporary = true;
                post(() -> {
                    pendingPluginName.set(verified.manifest().name);
                    pendingPluginId.set(verified.manifest().id);
                    pendingPluginVersion.set(verified.manifest().version);
                    pendingPluginPermissions.set(verified.manifest().permissions.isEmpty()
                            ? "无额外权限" : String.join("、", verified.manifest().permissions));
                    pendingPluginTrusted.set(true);
                    pluginInstallBusy.set(false);
                    pluginInstallPromptRevision.set(pluginInstallPromptRevision.peek() + 1L);
                });
            } catch (Throwable error) {
                post(() -> pluginInstallBusy.set(false));
                showToast("下载插件失败：" + safeMessage(error));
            }
        });
    }

    /** Delayed, non-destructive legacy migration: old queue records already carry
     * canonical ids after schema-v2 loading, but remain on their compatibility
     * route until a matching provider is actually installed and enabled. Once it
     * is available, route them through the plugin without rewriting or deleting
     * the old state file first. The next normal atomic save records PLUGIN. */
    private void routeInstalledPluginTracks() {
        boolean queueChanged = false;
        for (Track track : queue) queueChanged |= routeInstalledPluginTrack(track);
        boolean customChanged = false;
        for (Track track : customPlaylist) customChanged |= routeInstalledPluginTrack(track);
        if (queueChanged) {
            queueTracks.set(new ArrayList<>(queue));
            worker.submit(this::saveQueue);
        }
        if (customChanged) {
            customPlaylistTracks.set(new ArrayList<>(customPlaylist));
            worker.submit(this::saveCustomPlaylist);
        }
    }

    private boolean routeInstalledPluginTrack(Track track) {
        if (track == null || track.source == Track.Source.LOCAL) return false;
        String id = track.canonicalId();
        try {
            String provider = MediaId.parse(id).requireKind(
                    dev.t1m3.qplayer.media.MediaKind.SONG).provider();
            if (pluginHasCapability(provider, ProviderCapability.RESOLVE_STREAM)
                    && track.source != Track.Source.PLUGIN) {
                track.source = Track.Source.PLUGIN;
                track.streamUrl = null;
                track.streamHeaders.clear();
                return true;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return false;
    }

    private PluginManifest primaryProvider() {
        String primary = pluginRegistry.primaryProvider();
        if (primary == null || primary.isEmpty()) return null;
        for (PluginManifest manifest : pluginManager.enabledProviders()) {
            if (primary.equals(manifest.id)) return manifest;
        }
        return null;
    }

    private PluginManifest primaryProviderWith(ProviderCapability capability) {
        PluginManifest provider = primaryProvider();
        return provider != null && provider.capabilitySet().contains(capability) ? provider : null;
    }

    private void refreshPrimaryPluginLoginMetadata() {
        String primary = pluginRegistry.primaryProvider();
        PluginManifest selected = null;
        for (PluginManifest manifest : pluginManager.enabledProviders()) {
            if (primary.equals(manifest.id)
                    && manifest.capabilitySet().contains(ProviderCapability.LOGIN)) {
                selected = manifest;
                break;
            }
        }
        if (selected == null) {
            pendingPluginLoginProvider = "";
            pluginQrMethodId = "";
            pluginWebMethodId = "";
            pluginCredentialMethodId = "";
            loginProviderName.set("音源账号");
            pluginLoginActive.set(false);
            loginMethods.set(Collections.<LoginMethod>emptyList());
            pluginQrLoginAvailable.set(false);
            pluginCredentialLoginAvailable.set(false);
            webLoginAvailable.set(false);
            return;
        }
        final PluginManifest provider = selected;
        loginProviderName.set(provider.name);
        pluginLoginActive.set(true);
        pluginAccounts.methods(provider.id).whenComplete((methods, error) -> post(() -> {
            if (!provider.id.equals(pluginRegistry.primaryProvider())) return;
            if (error != null) {
                Logger.warn("plugin {} login metadata failed: {}", provider.id, safeMessage(error));
                return;
            }
            LoginMethod qr = null, web = null, credential = null;
            for (LoginMethod method : methods) {
                if ("qr".equals(method.type) && qr == null) qr = method;
                else if ("web".equals(method.type) && web == null) web = method;
                else if ("credential".equals(method.type) && credential == null) credential = method;
            }
            pendingPluginLoginProvider = provider.id;
            pluginQrMethodId = qr != null ? qr.id : "";
            pluginWebMethodId = web != null ? web.id : "";
            pluginCredentialMethodId = credential != null ? credential.id : "";
            activeWebLoginMethod = web;
            loginProviderName.set(provider.name);
            loginMethods.set(methods);
            pluginQrLoginAvailable.set(qr != null);
            pluginCredentialLoginAvailable.set(credential != null);
            webLoginAvailable.set(web != null && webLoginLauncher != null);
            if (web != null && !web.instructions.isEmpty()) {
                loginWebInstructions.set(web.instructions);
            }
            if (credential != null) {
                if (!credential.instructions.isEmpty()) {
                    loginCredentialInstructions.set(credential.instructions);
                }
                loginCredentialLabel.set(credential.credentialLabel);
            }
            refreshPluginAccount(provider.id, null);
            migrateLegacyCredentialsIfAvailable(provider.id);
        }));
    }

    private void migrateLegacyCredentialsIfAvailable(String provider) {
        if (legacyCredentialMigrationAttempted || !"netease".equals(provider)
                || pluginCredentialMethodId.isEmpty()) return;
        String credential = netease.legacyCookieHeaderForMigration();
        if (credential == null || credential.isEmpty()) return;
        legacyCredentialMigrationAttempted = true;
        final long generation = legacyCredentialMigrationGeneration.incrementAndGet();
        pluginAccounts.submit(provider, pluginCredentialMethodId, credential)
                .whenComplete((challenge, error) -> post(() -> {
                    if (generation != legacyCredentialMigrationGeneration.get()) {
                        // Logout won the race. If the delayed submit nevertheless
                        // persisted the credential, remove it again instead of
                        // resurrecting the account after the user logged out.
                        if (error == null && challenge != null && challenge.account != null
                                && challenge.account.loggedIn) {
                            pluginAccounts.logout(provider);
                        }
                        pendingCredentialEncryptedNotice = false;
                        netease.logout();
                        return;
                    }
                    if (error != null || challenge == null || challenge.account == null
                            || !challenge.account.loggedIn) {
                        pendingCredentialEncryptedNotice = false;
                        legacyCredentialMigrationAttempted = false;
                        Logger.warn("legacy credential migration to plugin failed: {}",
                                safeMessage(error));
                        return;
                    }
                    // The source plugin has persisted and verified the credential;
                    // keeping the old envelope would silently log the user back in
                    // after a later plugin logout.
                    netease.logout();
                    publishPluginAccount(provider, challenge.account);
                    Logger.info("legacy source credential migrated and removed from core storage");
                    showToast("登录凭据已迁移到音源插件");
                }));
    }

    @FunctionalInterface
    public interface PluginPicker { void pick(); }

    public void setPluginPicker(PluginPicker picker) { this.pluginPicker = picker; }

    // --- Plugin dialogs -----------------------------------------------------
    // A plugin contributes no QML. It returns a validated description from its
    // ui.<contribution> handler and QPlayer renders it with md3.Core, so the
    // dialog follows the app theme and no third-party document is ever loaded.
    // Every action (open, refresh, a button id) re-invokes the handler and
    // replaces the description — the plugin owns the state, the host owns the
    // pixels.

    /** True while a plugin dialog is on screen. */
    public final Property<Boolean> pluginDialogOpen = new Property<>(false);
    /** Canonical JSON from {@link PluginUiDescription}; "" before the first reply. */
    public final Property<String> pluginDialogJson = new Property<>("");
    /** True while a user-initiated action is in flight (background refreshes do not set it). */
    public final Property<Boolean> pluginDialogBusy = new Property<>(false);
    public final Property<String> pluginDialogError = new Property<>("");
    /** Title shown before the plugin's first description arrives. */
    public final Property<String> pluginDialogTitle = new Property<>("");

    private volatile String dialogPluginId = "";
    private volatile String dialogContributionId = "";

    public void requestPluginUi(String pluginId, String contributionId) {
        if (pluginId == null || contributionId == null) return;
        String label = contributionId;
        for (PluginUiContributionRow row : pluginUiContributions.peek()) {
            if (row.pluginId.equals(pluginId) && row.id.equals(contributionId)) {
                label = row.label;
                break;
            }
        }
        dialogPluginId = pluginId;
        dialogContributionId = contributionId;
        pluginDialogTitle.set(label);
        pluginDialogJson.set("");
        pluginDialogError.set("");
        pluginDialogBusy.set(false);
        pluginDialogOpen.set(true);
        invokePluginDialog("open", Collections.<String, Object>emptyMap(), true);
    }

    /** A button press: {@code actionId} is the button's own id. */
    public void pluginDialogAction(String actionId, String inputsJson) {
        if (actionId == null || actionId.isEmpty()) return;
        Map<String, Object> inputs;
        try {
            Map<String, Object> parsed = new Gson().fromJson(
                    inputsJson == null || inputsJson.isEmpty() ? "{}" : inputsJson,
                    new com.google.gson.reflect.TypeToken<java.util.LinkedHashMap<String, Object>>() {}.getType());
            inputs = parsed != null ? parsed : Collections.<String, Object>emptyMap();
        } catch (RuntimeException error) {
            inputs = Collections.emptyMap();
        }
        invokePluginDialog(actionId, inputs, true);
    }

    /** Driven by the description's refreshMs; never blocks the buttons. */
    public void pluginDialogRefresh() {
        if (Boolean.TRUE.equals(pluginDialogBusy.peek())) return;
        invokePluginDialog("refresh", Collections.<String, Object>emptyMap(), false);
    }

    public void closePluginDialog() {
        dialogPluginId = "";
        dialogContributionId = "";
        pluginDialogOpen.set(false);
        pluginDialogJson.set("");
        pluginDialogError.set("");
        pluginDialogBusy.set(false);
    }

    private void invokePluginDialog(String action, Map<String, Object> inputs, boolean showBusy) {
        final String pluginId = dialogPluginId;
        final String contributionId = dialogContributionId;
        if (pluginId.isEmpty() || contributionId.isEmpty()) return;
        if (showBusy) pluginDialogBusy.set(true);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inputs", inputs);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("action", action);
        arguments.put("payload", payload);
        pluginManager.invoke(pluginId, "ui." + contributionId, arguments)
                .whenComplete((value, failure) -> post(() -> {
                    // A reply that arrives after the user closed or switched dialogs
                    // must not repaint the new one.
                    if (!pluginId.equals(dialogPluginId)
                            || !contributionId.equals(dialogContributionId)) return;
                    if (showBusy) pluginDialogBusy.set(false);
                    if (failure != null) {
                        pluginDialogError.set(safeMessage(failure));
                        return;
                    }
                    try {
                        pluginDialogJson.set(PluginUiDescription.normalize(value));
                        pluginDialogError.set("");
                    } catch (RuntimeException error) {
                        pluginDialogError.set(safeMessage(error));
                    }
                }));
    }

    public void requestPluginImport() {
        PluginPicker picker = pluginPicker;
        if (picker == null) {
            showToast("当前平台暂不支持选择插件包");
            return;
        }
        picker.pick();
    }

    /** Inspect on a worker before QML presents the mandatory code-execution warning. */
    public void inspectPluginPackage(String path) {
        inspectPluginPackage(path, false);
    }

    /** Host-only variant for a package copied out of a content URI. */
    public void inspectTemporaryPluginPackage(String path) {
        inspectPluginPackage(path, true);
    }

    private void inspectPluginPackage(String path, boolean deleteAfterInspection) {
        if (path == null || path.trim().isEmpty()) return;
        post(() -> pluginInstallBusy.set(true));
        worker.submit(() -> {
            try {
                VerifiedPluginPackage verified = pluginVerifier.verify(
                        java.nio.file.Paths.get(path), null, true);
                pendingPluginPackage = verified;
                pendingPluginPackageTemporary = deleteAfterInspection;
                String permissions = verified.manifest().permissions.isEmpty()
                        ? "无额外权限"
                        : String.join("、", verified.manifest().permissions);
                post(() -> {
                    pendingPluginName.set(verified.manifest().name);
                    pendingPluginId.set(verified.manifest().id);
                    pendingPluginVersion.set(verified.manifest().version);
                    pendingPluginPermissions.set(permissions);
                    pendingPluginTrusted.set(verified.signed());
                    pluginInstallBusy.set(false);
                    pluginInstallPromptRevision.set(pluginInstallPromptRevision.peek() + 1L);
                });
            } catch (Throwable error) {
                pendingPluginPackage = null;
                post(() -> pluginInstallBusy.set(false));
                showToast("插件包无效：" + error.getMessage());
            } finally {
                if (deleteAfterInspection && pendingPluginPackage == null) {
                    try { java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(path)); }
                    catch (java.io.IOException ignored) {}
                }
            }
        });
    }

    /** Called only after the user accepts the non-dismissable warning dialog. */
    public void confirmPendingPluginInstall() {
        final VerifiedPluginPackage verified = pendingPluginPackage;
        if (verified == null || Boolean.TRUE.equals(pluginInstallBusy.peek())) return;
        pluginInstallBusy.set(true);
        worker.submit(() -> {
            try {
                Set<PluginPermission> grants = verified.manifest().permissionSet();
                pluginManager.installAndEnable(pluginInstaller, verified, grants);
                pendingPluginPackage = null;
                post(() -> {
                    pluginInstallBusy.set(false);
                    publishPlugins();
                });
                showToast("插件已安装：" + verified.manifest().name);
            } catch (Throwable error) {
                pendingPluginPackage = null;
                post(() -> pluginInstallBusy.set(false));
                showToast("插件安装失败：" + error.getMessage());
            } finally {
                if (pendingPluginPackageTemporary) {
                    try { java.nio.file.Files.deleteIfExists(verified.file()); }
                    catch (java.io.IOException ignored) {}
                    pendingPluginPackageTemporary = false;
                }
            }
        });
    }

    public void cancelPendingPluginInstall() {
        VerifiedPluginPackage previous = pendingPluginPackage;
        pendingPluginPackage = null;
        if (pendingPluginPackageTemporary && previous != null) {
            try { java.nio.file.Files.deleteIfExists(previous.file()); }
            catch (java.io.IOException ignored) {}
        }
        pendingPluginPackageTemporary = false;
        pendingPluginName.set("");
        pendingPluginId.set("");
        pendingPluginVersion.set("");
        pendingPluginPermissions.set("");
        pendingPluginTrusted.set(false);
    }

    private void showCredentialNotice(NeteaseClient.CredentialEvent event) {
        if (event == NeteaseClient.CredentialEvent.ENCRYPTED) {
            pendingCredentialEncryptedNotice = true;
            return;
        }
        pendingCredentialEncryptedNotice = false;
        final int type;
        if (event == NeteaseClient.CredentialEvent.KEYSTORE_FALLBACK) type = 2;
        else type = 3;
        post(() -> {
            if (event == NeteaseClient.CredentialEvent.KEYSTORE_FALLBACK) {
                credentialOwnerOnlyFallback.set(true);
            }
            credentialNoticeType.set(type);
            credentialNoticeRevision.set(credentialNoticeRevision.peek() + 1L);
        });
    }

    private void showPluginCredentialNotice(PluginCredentialVault.CredentialEvent event) {
        if (event == PluginCredentialVault.CredentialEvent.ENCRYPTED) {
            pendingCredentialEncryptedNotice = true;
            return;
        }
        pendingCredentialEncryptedNotice = false;
        final int type;
        if (event == PluginCredentialVault.CredentialEvent.KEYSTORE_FALLBACK) type = 2;
        else type = 3;
        post(() -> {
            if (event == PluginCredentialVault.CredentialEvent.KEYSTORE_FALLBACK) {
                credentialOwnerOnlyFallback.set(true);
            }
            credentialNoticeType.set(type);
            credentialNoticeRevision.set(credentialNoticeRevision.peek() + 1L);
        });
    }

    private void publishPendingCredentialEncryptedNotice() {
        if (!pendingCredentialEncryptedNotice) return;
        pendingCredentialEncryptedNotice = false;
        credentialOwnerOnlyFallback.set(false);
        credentialNoticeType.set(1);
        credentialNoticeRevision.set(credentialNoticeRevision.peek() + 1L);
    }

    /** Retry a potentially interactive key-store unlock without blocking rendering. */
    public void retryCredentialUnlock() {
        showToast("正在等待系统密钥库解锁…");
        worker.submit(() -> {
            if (pluginHostApi.retryCredentialUnlock()) {
                String provider = pluginRegistry.primaryProvider();
                if (provider != null && !provider.isEmpty()) refreshPluginAccount(provider, null);
            }
        });
    }

    /** Abandon the inaccessible envelope and persistently use owner-only encryption. */
    public boolean fallbackCredentialsToOwnerOnly() {
        if (!pluginHostApi.fallbackUnreadableCredentials()) return false;
        credentialOwnerOnlyFallback.set(true);
        clearAccountStateAfterCredentialReset();
        return true;
    }

    /** Discard unreadable credentials and retry system-store encryption on login. */
    public void prepareEncryptedRelogin() {
        if (credentialReloginBusy) return;
        credentialReloginBusy = true;
        showToast("正在检查系统密钥库…");
        worker.submit(() -> {
            boolean ready = pluginHostApi.resetUnreadableCredentialsForPlatformLogin();
            post(() -> {
                credentialReloginBusy = false;
                if (ready) {
                    credentialOwnerOnlyFallback.set(false);
                    clearAccountStateAfterCredentialReset();
                }
                credentialReloginResult.set(ready ? 1 : 2);
                credentialReloginRevision.set(credentialReloginRevision.peek() + 1L);
            });
        });
    }

    private void clearAccountStateAfterCredentialReset() {
        uid = 0L;
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
    }

    /** User-triggered migration from owner-only storage back to the system store. */
    public void reenableSystemCredentialProtection() {
        if (Boolean.TRUE.equals(credentialProtectionBusy.peek())) return;
        credentialProtectionBusy.set(true);
        showToast("正在等待系统密钥库…");
        worker.submit(() -> {
            boolean enabled = pluginHostApi.enableSystemCredentialProtection();
            post(() -> {
                credentialOwnerOnlyFallback.set(
                        pluginHostApi.usesOwnerOnlyCredentialProtection());
                credentialProtectionBusy.set(false);
                if (!enabled) showToast("未能启用系统加密，已保留普通加密");
            });
        });
    }

    /** Platform color extractor for Monet seeds; set once at startup. */
    public void setColorExtractor(ColorExtractor extractor) {
        this.colorExtractor = extractor;
        if (extractor == null) return;

        // Hosts install the platform extractor immediately after constructing the
        // controller, while loadQueue() runs from the constructor. Do not miss a
        // restored cover merely because it was read before this hook was installed.
        long revision = coverRevision.get();
        byte[] currentBytes = coverBytes.peek();
        if (currentBytes != null && currentBytes.length > 0) {
            scheduleSeedExtraction(currentBytes, revision, 1);
        } else {
            scheduleFastMonet(currentTrack(), revision);
        }
    }

    /** Platform clipboard sink (copies text to the system clipboard), set at startup.
     *  The shell is responsible for putting the write on the right thread. */
    public void setClipboard(java.util.function.Consumer<String> sink) {
        this.clipboard = sink;
    }

    public boolean allowRemoteQmlResource(String url) {
        return pluginHostApi.allowsAnyReturnedUrl(url);
    }

    @FunctionalInterface
    public interface WebLoginLauncher {
        void launch(String loginUrl, String cookieUrl, String credentialCookieName,
                    String providerName);
    }

    /** Install the shell's in-process system WebView login launcher. */
    public void setWebLoginLauncher(WebLoginLauncher launcher) {
        this.webLoginLauncher = launcher;
        webLoginAvailable.set(launcher != null
                && (pendingPluginLoginProvider.isEmpty() || !pluginWebMethodId.isEmpty()));
    }

    /** Copy a canonical reference for a delayed-migration numeric song row. */
    public void copySongLink(long songId) {
        if (songId == 0) return;
        String url = "netease:song:" + songId;
        java.util.function.Consumer<String> c = clipboard;
        if (c != null) {
            c.accept(url);
            showToast("已复制链接");
        } else {
            showToast(url);
        }
    }

    /** Copy a canonical reference for a delayed-migration numeric playlist row. */
    public void copyPlaylistLink(long playlistId) {
        if (playlistId == 0) return;
        String url = "netease:playlist:" + playlistId;
        java.util.function.Consumer<String> c = clipboard;
        if (c != null) {
            c.accept(url);
            showToast("已复制链接");
        } else {
            showToast(url);
        }
    }

    // --- Volume fade in/out -------------------------------------------------

    /** Start a fade-in from silence to the user's set volume. Called from the
     *  backend's onStarted, i.e. once playback of the (possibly async-resolved)
     *  source has actually begun -- not from playAt() itself, so the ramp's
     *  full duration is real audible time regardless of how long resolving
     *  the source took. */
    private void startFadeIn() {
        fadeOutDoneForTrack = false;
        if (suppressNextFadeIn) {
            suppressNextFadeIn = false;
            cancelFadeAtGain(1f);
            return;
        }
        if (!fadeEnabled) {
            cancelFadeAtGain(1f);
            return;
        }
        startVolumeFade(0f, 1f, FADE_IN_MS, null);
    }

    /** Ramp toward silence over {@code durationMs} starting from wherever the
     *  gain currently sits (not always 1 -- e.g. pausing again while an
     *  earlier pause's fade-out is still in flight), then run {@code
     *  onComplete} once (e.g. the actual backend.pause() a manual pause
     *  deferred). Overwrites any in-flight ramp. */
    private void startFadeOut(long durationMs, Runnable onComplete) {
        startVolumeFade(currentFadeGain(), 0f, durationMs, onComplete);
    }

    /** Start one generation-bound ramp. Its first sample is applied immediately;
     *  later samples run on a tiny daemon clock, never on the GL/render thread. */
    private void startVolumeFade(float from, float to, long durationMs, Runnable onComplete) {
        final long generation;
        float start = clampGain(from);
        synchronized (fadeLock) {
            generation = ++fadeGeneration;
            fadeRunning = true;
            fadeStartNs = System.nanoTime();
            fadeDurationNs = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, durationMs));
            fadeFromGain = start;
            fadeToGain = clampGain(to);
            fadeCurrentGain = start;
            fadeCompleteAction = onComplete;
        }
        applyEffectiveVolume(start);
        scheduleFadeTick(generation);
    }

    private void scheduleFadeTick(long generation) {
        if (fadeWorker.isShutdown()) return;
        try {
            fadeWorker.schedule(() -> tickVolumeFade(generation), FADE_TICK_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // shutdown() raced this final sample; the backend is being released too.
        }
    }

    private void tickVolumeFade(long generation) {
        float gain;
        boolean again;
        Runnable completion = null;
        synchronized (fadeLock) {
            if (!fadeRunning || generation != fadeGeneration) return;
            long elapsed = Math.max(0L, System.nanoTime() - fadeStartNs);
            float t = elapsed >= fadeDurationNs ? 1f : (float) elapsed / fadeDurationNs;
            gain = fadeFromGain + (fadeToGain - fadeFromGain) * t;
            fadeCurrentGain = gain;
            again = t < 1f;
            if (!again) {
                fadeRunning = false;
                completion = fadeCompleteAction;
                fadeCompleteAction = null;
            }
        }
        applyEffectiveVolume(gain);
        if (again) {
            scheduleFadeTick(generation);
        } else if (completion != null) {
            final Runnable action = completion;
            // Android's main executor may not run this immediately. Re-check the
            // generation at execution time so switching/resuming in the meantime
            // cannot let an old fade-out pause the new source.
            onMain(() -> {
                synchronized (fadeLock) {
                    if (fadeGeneration != generation || fadeRunning) return;
                }
                action.run();
            });
        }
    }

    private void cancelFadeAtGain(float gain) {
        float clamped = clampGain(gain);
        synchronized (fadeLock) {
            fadeGeneration++;
            fadeRunning = false;
            fadeCompleteAction = null;
            fadeCurrentGain = clamped;
        }
        applyEffectiveVolume(clamped);
    }

    /** Cancel the outgoing track's fade before replacing the backend source. The new
     *  source is armed at silence for a real fade-in, or at full gain when a manual
     *  next/previous explicitly suppresses that fade. */
    private void playBackend(String source, long startMs) {
        playBackend(source, Collections.<String, String>emptyMap(), startMs);
    }

    private void playBackend(String source, Map<String, String> headers, long startMs) {
        float initialGain = fadeEnabled && !suppressNextFadeIn ? 0f : 1f;
        cancelFadeAtGain(initialGain);
        backend.play(source, headers != null ? headers : Collections.<String, String>emptyMap(), startMs);
    }

    private void applyEffectiveVolume(float gain) {
        backend.setVolume(userVolume * clampGain(gain));
    }

    private static float clampGain(float gain) {
        return Math.max(0f, Math.min(1f, gain));
    }

    /** The gain the in-flight ramp is at right now (or its last settled target). */
    private float currentFadeGain() {
        synchronized (fadeLock) {
            if (!fadeRunning) return fadeCurrentGain;
            long elapsed = Math.max(0L, System.nanoTime() - fadeStartNs);
            float t = elapsed >= fadeDurationNs ? 1f : (float) elapsed / fadeDurationNs;
            return fadeFromGain + (fadeToGain - fadeFromGain) * t;
        }
    }

    private boolean isFadeRunning() {
        synchronized (fadeLock) {
            return fadeRunning;
        }
    }

    /** Notice when the current track enters its natural end window. The render pump
     *  only detects this boundary; once started, the independent fade clock above
     *  always carries the ramp to its exact target. */
    private void tickFade() {
        if (!fadeEnabled) return;
        if (isFadeRunning()) return;
        if (fadeOutDoneForTrack || !backend.isPlaying()) return;
        long dur = backend.duration();
        if (dur <= 0) return;
        long remaining = dur - backend.position();
        if (remaining >= 0 && remaining <= FADE_OUT_MS) {
            fadeOutDoneForTrack = true;
            startVolumeFade(currentFadeGain(), 0f, Math.max(1L, remaining), null);
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
        tickFade();
        if (now - lastPositionPush >= 200L) {
            lastPositionPush = now;
            if (backend.isPlaying()) {
                long pos = backend.position();
                positionMs.set(pos);
                updateLyricIndex(pos - LyricConfig.instance.offsetMs.getValue());
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

    /** Hosts gate expensive per-frame diagnostics (Android's 120-frame summary)
     *  on this so a closed overlay never hits logcat / the ring buffer. */
    public boolean isLogVisible() {
        return logVisible;
    }

    /** Host debug automation (adb broadcast). QML watches the revision and
     *  applies {@link #debugRouteType}/{@link #debugRouteId}. */
    public final Property<String> debugRouteType = new Property<>("");
    public final Property<String> debugRouteId = new Property<>("");
    public final Property<Long> debugRouteRevision = new Property<>(0L);

    /** Drive navigation/playback from a host debug channel without tapping QML. */
    public void debugCommand(String command, String arg) {
        if (command == null || command.isEmpty()) return;
        String cmd = command.trim().toLowerCase(java.util.Locale.ROOT);
        final String a = arg != null ? arg : "";
        switch (cmd) {
            case "status":
                Logger.info("debug {}", debugStatus());
                break;
            case "toggle":
                toggle();
                break;
            case "next":
                next();
                break;
            case "prev":
                prev();
                break;
            case "play":
                onMain(() -> playRecommendation(0));
                break;
            case "home":
                loadHome();
                pushDebugRoute("tab", "0");
                break;
            case "search":
                if (!a.isEmpty()) search(a);
                pushDebugRoute("tab", "1");
                break;
            case "library":
                loadMyPlaylists();
                pushDebugRoute("tab", "2");
                break;
            case "local":
                pushDebugRoute("tab", "3");
                break;
            case "settings":
                pushDebugRoute("settings", "");
                break;
            case "lyrics":
                post(() -> setLyricsOpen(true));
                pushDebugRoute("lyrics", "");
                break;
            case "lyrics-close":
            case "close-lyrics":
                post(() -> setLyricsOpen(false));
                pushDebugRoute("pop", "");
                break;
            case "playlist":
                openDebugPlaylist(a);
                break;
            case "back":
                pressBack();
                break;
            default:
                Logger.warn("unknown debug command {}", cmd);
        }
    }

    public String debugStatus() {
        List<Song> recs = sourceRecommendations.peek();
        List<Playlist> playlists = sourceRecommendPlaylists.peek();
        return "playing=" + playing.peek()
                + " loggedIn=" + loggedIn.peek()
                + " setup=" + sourceSetupRequired.peek()
                + " title=" + title.peek()
                + " queue=" + queue.size()
                + " recs=" + (recs == null ? 0 : recs.size())
                + " playlists=" + (playlists == null ? 0 : playlists.size())
                + " homeLoading=" + homeLoading.peek()
                + " lyrics=" + lyricsOpen.peek();
    }

    private void pushDebugRoute(String type, String id) {
        post(() -> {
            debugRouteType.set(type);
            debugRouteId.set(id);
            Long rev = debugRouteRevision.peek();
            debugRouteRevision.set((rev == null ? 0L : rev) + 1L);
        });
    }

    private void openDebugPlaylist(String arg) {
        List<Playlist> playlists = sourceRecommendPlaylists.peek();
        if (playlists == null || playlists.isEmpty()) {
            playlists = sourceMyPlaylists.peek();
        }
        if (playlists == null || playlists.isEmpty()) {
            Logger.warn("debug playlist: none loaded");
            return;
        }
        int i = 0;
        try { if (!arg.isEmpty()) i = Integer.parseInt(arg.trim()); } catch (NumberFormatException ignored) {}
        if (i < 0 || i >= playlists.size()) i = 0;
        String id = playlists.get(i).id;
        openMediaPlaylist(id);
        pushDebugRoute("detail", id);
    }

    /** Publish a new lyric list and derive {@link #lyricsCoverOnly}. */
    private void applyLyrics(List<LyricLine> ly) {
        lyrics.set(ly);
        lyricsCoverOnly.set(computeCoverOnly(ly));
        // Sync the highlighted line to wherever the transport already sits. Normally
        // redundant — pump() re-derives lyricIndex from backend.position() every ~200ms
        // while playing — but it's the ONLY thing that sets it when not playing yet,
        // e.g. right after a session restore: positionMs is set correctly, but
        // pump()'s own updateLyricIndex call is gated on backend.isPlaying(), which
        // isn't true until the user actually presses play.
        Long pos = positionMs.peek();
        updateLyricIndex((pos != null ? pos : 0L) - LyricConfig.instance.offsetMs.getValue());
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

    public void setWideLayout(boolean wide) {
        wideLayout.set(wide);
    }

    /** Physical pixels per QML logical pixel, pushed by each shell (GLFW window
     *  content scale on desktop, display density on Android). QML is authored in
     *  logical units and qml4j does NOT scale Image.sourceSize by it, so anything
     *  that decodes to a display size has to multiply by this itself or it decodes
     *  at 1x and gets upscaled into a blurry draw -- see CoverImage.qml. */
    public void setPixelRatio(double ratio) {
        if (ratio > 0 && ratio != pixelRatio.peek()) pixelRatio.set(ratio);
    }

    /** Resolution to fetch/cache a grid-card cover at (playlist / album) --
     *  see {@link #wideLayout}. */
    private String gridCoverSize() {
        return Boolean.TRUE.equals(wideLayout.peek()) ? "1024" : "512";
    }

    /** Rewrites each album's coverThumbPath to {@link #gridCoverSize()} --
     *  NeteaseClient.parseAlbum bakes in a fixed 128px thumb (it has no
     *  layout awareness of its own), which looked small/soft in an
     *  AlbumCard grid tile (ArtistDetailPage's album row, album search
     *  results) that's well over 128px on most layouts. */
    private void applyAlbumCoverSize(List<NeteaseAlbum> albums) {
        if (albums == null) return;
        String size = gridCoverSize();
        for (NeteaseAlbum a : albums) {
            if (a.coverUrl != null && !a.coverUrl.isEmpty()) a.coverThumbPath = thumbUrl(a.coverUrl, size);
        }
    }

    public void setLyricsOpen(boolean open) {
        lyricsOpen.set(open);
        // Closing the lyric page (Esc / Android back / the collapse button all funnel
        // here) must also drop the offset panel's gesture-suppression flag — otherwise
        // it stays stuck true and the lyric body's tap-to-seek never re-arms next time.
        if (!open) lyricOffsetPanelOpen.set(false);
    }

    public void setLyricOffsetPanelOpen(boolean open) {
        lyricOffsetPanelOpen.set(open);
    }

    /** Set from the fixed-step lyric-page slider. */
    public void setLyricOffset(int valueMs) {
        Track track = currentTrack();
        if (track == null) return;
        int snapped = Math.round(valueMs / 50f) * 50;
        setCurrentLyricOffset(track, Math.max(-5000, Math.min(5000, snapped)));
    }

    public void resetLyricOffset() {
        Track track = currentTrack();
        if (track != null) setCurrentLyricOffset(track, 0);
    }

    /** LyricOverlay.qml's manual lyrics/cover switch — see {@link #coverModeManual}. */
    public void setCoverMode(boolean on) {
        coverModeManual.set(on);
    }

    public void setQueueOpen(boolean open) {
        queueOpen.set(open);
    }

    /** Closes the artist/album drill-in pages (Main.qml's back handling and its
     *  "opening something else replaces the current overlay" resets); opening
     *  them back up is {@link #openArtist}/{@link #openAlbum} themselves. */
    public void setArtistPageOpen(boolean open) {
        artistPageOpen.set(open);
    }

    public void setAlbumPageOpen(boolean open) {
        albumPageOpen.set(open);
    }

    /** SearchPage's type dropdown. Only sets the mode + clears the other kinds'
     *  stale results -- the caller (QML) re-issues the actual search itself so
     *  this doesn't need to remember the current query text. */
    public void setSearchMode(String mode) {
        searchMode.set(mode);
        searchAlbumResults.set(Collections.<NeteaseAlbum>emptyList());
        searchArtistResults.set(Collections.<NeteaseArtist>emptyList());
        searchResults.set(Collections.<NeteaseSong>emptyList());
        localSearchResults.set(Collections.<Track>emptyList());
        rebuildSearchRows();
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
    /** When true, application and plugin GitHub downloads prefer proxy URLs. */
    private volatile boolean updateMirror = false;

    /** Toggle the GitHub download mirror (driven by the settings switch). */
    public void setUpdateMirror(boolean enabled) {
        this.updateMirror = enabled;
    }

    /** Picks this host's own downloadable release asset out of the release's asset
     *  list (Android: the .apk; desktop: the installer/AppImage for the running OS).
     *  {@link #checkForUpdate(boolean)} calls this once per asset name, in listed
     *  order, and takes the first match. */
    public interface AssetMatcher { boolean matches(String assetName); }

    /** Default: Android's original hardcoded rule, kept as the fallback so a host
     *  that never calls {@link #setAssetMatcher} (i.e. Android, unchanged) still
     *  works exactly as before. */
    private volatile AssetMatcher assetMatcher = name -> name.toLowerCase().endsWith(".apk");

    /** Desktop hosts call this at startup with an OS-specific matcher (installer
     *  .exe / .dmg / .AppImage) so {@link #checkForUpdate} finds their own asset
     *  instead of never matching anything (there is no .apk in a desktop release). */
    public void setAssetMatcher(AssetMatcher m) {
        this.assetMatcher = m != null ? m : (name -> name.toLowerCase().endsWith(".apk"));
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
        return GitHubDownloadUrls.candidates(apk, updateMirror);
    }

    /** Host injects the running app version (e.g. "0.5.2") for the update compare. */
    public void setCurrentVersion(String version) {
        this.currentVersion = version == null ? "" : version;
        post(() -> appVersion.set(this.currentVersion));
    }

    /** Silent (startup) check — no feedback when already up to date or on failure,
     *  only the update dialog when a newer release is actually found. */
    public void checkForUpdate() {
        checkForUpdate(false);
    }

    /** Settings > 关于's "检查更新" button (a distinct name rather than an overload
     *  call from QML, to sidestep any doubt about how the QML/Java bridge resolves
     *  overloaded method calls). */
    public void checkForUpdateManual() {
        checkForUpdate(true);
    }

    /** Fetch the latest release off the worker thread; on a newer version, publish
     *  it to the update Properties (QML pops the dialog). Best-effort on failure
     *  (offline, rate-limited, parse error): just logs, unless {@code manual}.
     *
     * @param manual true for a user-initiated "检查更新" tap (Settings > 关于) —
     *  toasts "已是最新版本"/an error message instead of failing silently, since a
     *  button the user just pressed needs to visibly do *something*. */
    public void checkForUpdate(boolean manual) {
        worker.submit(() -> {
            try {
                String json = fetchReleaseJson();
                JsonElement root = JsonParser.parseString(json);
                if (!root.isJsonObject()) return;
                JsonObject obj = root.getAsJsonObject();

                String tag = optString(obj, "tag_name");
                String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                if (!isNewer(latest, currentVersion)) {
                    if (manual) showToast("已是最新版本");
                    return;
                }

                String notes = optString(obj, "body");
                String apk = "";
                if (obj.has("assets") && obj.get("assets").isJsonArray()) {
                    JsonArray assets = obj.getAsJsonArray("assets");
                    for (JsonElement e : assets) {
                        if (!e.isJsonObject()) continue;
                        JsonObject a = e.getAsJsonObject();
                        if (assetMatcher.matches(optString(a, "name"))) {
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
                if (manual) showToast("检查更新失败，请稍后重试");
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
            currentFilePath.set("");
            return;
        }
        if (i < cur) {
            playIndex = cur - 1;
            index.set(cur - 1);
        } else if (i == cur) {
            onMain(() -> playAt(Math.min(cur, queue.size() - 1)));
        }
    }

    // --- Custom playlist (local "play later" list, independent of the live queue) --

    /** True when a netease song is already in the custom playlist — drives the
     *  song long-press menu's add/remove toggle. */
    public boolean isInCustomPlaylist(long songId) {
        if (songId == 0) return false;
        for (Track t : customPlaylist) if (t.neteaseId == songId) return true;
        return false;
    }

    /** Add a netease song (looked up from whichever live list the long-press menu was
     *  opened from) to the custom playlist. */
    public void addToCustomPlaylist(long songId) {
        if (songId == 0 || isInCustomPlaylist(songId)) return;
        Track t = findLiveTrack(songId);
        if (t == null) {
            showToast("添加失败");
            return;
        }
        customPlaylist.add(t);
        customPlaylistTracks.set(new ArrayList<>(customPlaylist));
        showToast("已加入播放列表");
        worker.submit(this::saveCustomPlaylist);
    }

    /** The track list behind {@link #cachedSongs}, kept so {@link #playCachedSong}
     *  can re-queue it without touching the (render-thread) Property directly. */
    private final List<Track> cachedSongTracks = new ArrayList<>();

    /** Compatibility entry for a pre-plugin numeric menu row. */
    public void cacheSong(long songId) {
        showToast("旧音源条目无法联网缓存，请通过音源插件重新打开歌曲");
    }

    /** Rebuild {@link #cachedSongs} from the audio cache dir + the metadata index.
     *  Called by the rail's download menu when it opens. Cheap: one directory
     *  listing + a membership check per indexed song — no per-file I/O beyond that. */
    public void refreshCachedSongs() {
        try {
            List<Track> out = new ArrayList<>();
            for (Track t : mediaMetaIndex.all()) {
                if (diskCache.hasAudio(t.canonicalId())) {
                    // Prefer an on-disk thumbnail so the cached list renders fully
                    // offline; toTrack's fallback is the network thumb URL. The
                    // 1024px image is the one cacheSongAsync actually downloads
                    // for every manually-cached song (see its "fully offline-ready"
                    // comment) into DiskCache.IMAGE, whose eviction budget scales
                    // with the audio cache itself; THUMB64 is a much flimsier,
                    // hard-capped-at-128-files browsing cache shared by every
                    // playlist/search view, so a cached song's own thumbnail
                    // routinely gets evicted from THUMB64 by unrelated browsing
                    // long before its audio does -- check IMAGE first.
                    String localThumb = diskCache.getImage(thumbUrl(t.coverUrl, "1024"));
                    if (localThumb == null) localThumb = diskCache.getThumb64(thumbUrl(t.coverUrl, "512"));
                    if (localThumb == null) localThumb = diskCache.getThumb64(thumbUrl(t.coverUrl, "64"));
                    if (localThumb != null) t.coverThumbPath = localThumb;
                    out.add(t);
                }
            }
            cachedSongTracks.clear();
            cachedSongTracks.addAll(out);
            cachedSongs.set(new ArrayList<>(out));
        } catch (Throwable e) {
            Logger.warn("refreshCachedSongs failed: {}", e.getMessage());
        }
    }

    /** Play the offline-cached list starting at a slot (rail download-menu tap). */
    public void playCachedSong(int i) {
        if (i < 0 || i >= cachedSongTracks.size()) return;
        playQueue(new ArrayList<>(cachedSongTracks), i);
    }

    /** Delete a cached song's audio from disk (cached-songs list right-click menu).
     *  Drops the row from {@link #cachedSongs} so the open list updates in place;
     *  reopening via {@link #refreshCachedSongs} is a fresh disk scan. */
    public void deleteCachedSong(long songId) {
        if (songId == 0) return;
        boolean ok = diskCache.deleteAudio(songId);
        if (ok) {
            for (int i = 0; i < cachedSongTracks.size(); i++) {
                if (cachedSongTracks.get(i).neteaseId == songId) {
                    cachedSongTracks.remove(i);
                    break;
                }
            }
            cachedSongs.set(new ArrayList<>(cachedSongTracks));
            showToast("已删除缓存");
        } else {
            showToast("删除失败");
        }
    }

    /** Remove a netease song from the custom playlist by id (song long-press menu). */
    public void removeFromCustomPlaylist(long songId) {
        for (Track t : customPlaylist) {
            if (t.neteaseId == songId) {
                customPlaylist.remove(t);
                customPlaylistTracks.set(new ArrayList<>(customPlaylist));
                showToast("已移出播放列表");
                worker.submit(this::saveCustomPlaylist);
                return;
            }
        }
    }

    public boolean isMediaInCustomPlaylist(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return false;
        for (Track track : customPlaylist) {
            if (mediaId.equals(track.canonicalId())) return true;
        }
        return false;
    }

    public void addMediaToCustomPlaylist(String mediaId) {
        if (mediaId == null || mediaId.isEmpty() || isMediaInCustomPlaylist(mediaId)) return;
        Song song = findPluginSong(mediaId);
        if (song == null) {
            showToast("添加失败：歌曲信息已失效");
            return;
        }
        customPlaylist.add(toTrackPlugin(song));
        customPlaylistTracks.set(new ArrayList<>(customPlaylist));
        showToast("已加入播放列表");
        worker.submit(this::saveCustomPlaylist);
    }

    public void removeMediaFromCustomPlaylist(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return;
        for (Track track : customPlaylist) {
            if (mediaId.equals(track.canonicalId())) {
                customPlaylist.remove(track);
                customPlaylistTracks.set(new ArrayList<>(customPlaylist));
                showToast("已移出播放列表");
                worker.submit(this::saveCustomPlaylist);
                return;
            }
        }
    }

    private Song findPluginSong(String mediaId) {
        for (List<Song> values : pluginSearchByProvider.values()) {
            for (Song song : values) if (mediaId.equals(song.id)) return song;
        }
        List<List<Song>> visibleLists = java.util.Arrays.asList(
                sourceRecommendations.peek(), sourcePlaylistTracks.peek(),
                sourceArtistSongs.peek(), sourceAlbumTracks.peek());
        for (List<Song> values : visibleLists) {
            if (values == null) continue;
            for (Song song : values) if (mediaId.equals(song.id)) return song;
        }
        return null;
    }

    // --- Custom playlist: local tracks (issue #15's local-library favorites ask —
    // the add/remove-by-id methods above are netease-only, since a local Track has
    // no neteaseId; filePath is the local equivalent of a stable identity). ---

    /** True when a local file is already in the custom playlist. */
    public boolean isLocalInCustomPlaylist(String filePath) {
        if (filePath == null || filePath.isEmpty()) return false;
        for (Track t : customPlaylist) {
            if (t.source == Track.Source.LOCAL && filePath.equals(t.filePath)) return true;
        }
        return false;
    }

    /** Add a local file (looked up from the scanned library by path) to the custom
     *  playlist. */
    public void addLocalToCustomPlaylist(String filePath) {
        if (filePath == null || filePath.isEmpty() || isLocalInCustomPlaylist(filePath)) return;
        Track found = null;
        for (Track t : library) if (filePath.equals(t.filePath)) { found = t; break; }
        if (found == null) {
            showToast("添加失败");
            return;
        }
        customPlaylist.add(found);
        customPlaylistTracks.set(new ArrayList<>(customPlaylist));
        showToast("已加入播放列表");
        worker.submit(this::saveCustomPlaylist);
    }

    /** Remove a local file from the custom playlist by path. */
    public void removeLocalFromCustomPlaylist(String filePath) {
        if (filePath == null || filePath.isEmpty()) return;
        for (Track t : customPlaylist) {
            if (t.source == Track.Source.LOCAL && filePath.equals(t.filePath)) {
                customPlaylist.remove(t);
                customPlaylistTracks.set(new ArrayList<>(customPlaylist));
                showToast("已移出播放列表");
                worker.submit(this::saveCustomPlaylist);
                return;
            }
        }
    }

    /** Drop a slot from the custom playlist by position (queue-page tab). */
    public void removeFromCustomPlaylistIndex(int i) {
        if (i < 0 || i >= customPlaylist.size()) return;
        customPlaylist.remove(i);
        customPlaylistTracks.set(new ArrayList<>(customPlaylist));
        worker.submit(this::saveCustomPlaylist);
    }

    /** Play the custom playlist starting at a slot (queue-page tab tap). Replaces the
     *  live queue with a snapshot of the custom list, same as opening a real playlist. */
    public void playCustomPlaylistIndex(int i) {
        if (i < 0 || i >= customPlaylist.size()) return;
        playQueue(new ArrayList<>(customPlaylist), i);
    }

    /** Resolve a netease song id to a fresh Track from whichever live list opened
     *  the shared context menu. Track-backed rows are copied so playback mutations
     *  cannot alias into the custom/cached lists; page rows use NeteaseSong. */
    private Track findLiveTrack(long songId) {
        for (Track t : queue) if (t.neteaseId == songId) return copyNeteaseTrack(t);
        for (Track t : customPlaylist) if (t.neteaseId == songId) return copyNeteaseTrack(t);
        for (Track t : cachedSongTracks) if (t.neteaseId == songId) return copyNeteaseTrack(t);
        NeteaseSong s = findLiveSong(songId);
        return s != null ? toTrack(s) : null;
    }

    /** Copy the persisted subset of a NETEASE Track (matches saveCustomPlaylist),
     *  so a queue entry added to the custom list doesn't share the queue's Track
     *  object (whose streamUrl/coverUrl the player mutates during playback). */
    private static Track copyNeteaseTrack(Track src) {
        Track t = new Track();
        t.source = Track.Source.NETEASE;
        t.neteaseId = src.neteaseId;
        t.title = src.title;
        t.artist = src.artist;
        t.artistId = src.artistId;
        t.artistIdsCsv = src.artistIdsCsv;
        t.artistNamesCsv = src.artistNamesCsv;
        t.album = src.album;
        t.coverUrl = src.coverUrl;
        t.coverThumbPath = src.coverThumbPath != null ? src.coverThumbPath
                : NeteaseClient.thumbUrl(src.coverUrl);
        t.durationMs = src.durationMs;
        return t;
    }

    /** Find a netease song by id among every currently-loaded song-row model.
     *  SongRow exposes the same context menu in all of these views, so its actions
     *  must resolve the full metadata regardless of which page opened the menu. */
    private NeteaseSong findLiveSong(long songId) {
        @SuppressWarnings("unchecked")
        List<NeteaseSong>[] sources = new List[] {
                searchResults.peek(), playlistTracks.peek(), recommendations.peek(),
                recentSongs.peek(), artistSongs.peek(), albumTracks.peek()
        };
        for (List<NeteaseSong> songs : sources) {
            if (songs == null) continue;
            for (NeteaseSong song : songs) {
                if (song.id == songId) return song;
            }
        }
        return null;
    }

    private void saveCustomPlaylist() {
        try {
            java.nio.file.Path file = AppDirs.stateFile("custom-playlist.json");
            StringBuilder sb = new StringBuilder();
            sb.append("{\"schemaVersion\":2,\"tracks\":[");
            List<Track> snap = new ArrayList<>(customPlaylist);
            for (int i = 0; i < snap.size(); i++) {
                Track t = snap.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"source\":\"").append(t.source).append('"');
                String mediaId = t.canonicalId();
                if (!mediaId.isEmpty()) sb.append(",\"mediaId\":").append(jsonStr(mediaId));
                if (t.neteaseId != 0) sb.append(",\"neteaseId\":").append(t.neteaseId);
                sb.append(",\"title\":").append(jsonStr(t.title));
                sb.append(",\"artist\":").append(jsonStr(t.artist));
                if (t.artistId != 0) sb.append(",\"artistId\":").append(t.artistId);
                if (t.artistMediaId != null && !t.artistMediaId.isEmpty())
                    sb.append(",\"artistMediaId\":").append(jsonStr(t.artistMediaId));
                if (t.artistIdsCsv != null && !t.artistIdsCsv.isEmpty()) {
                    sb.append(",\"artistIdsCsv\":").append(jsonStr(t.artistIdsCsv));
                    sb.append(",\"artistNamesCsv\":").append(jsonStr(t.artistNamesCsv));
                }
                sb.append(",\"album\":").append(jsonStr(t.album));
                sb.append(",\"coverUrl\":").append(jsonStr(t.coverUrl));
                sb.append(",\"durationMs\":").append(t.durationMs);
                if (t.filePath != null) sb.append(",\"filePath\":").append(jsonStr(t.filePath));
                if (t.contentUri != null) sb.append(",\"contentUri\":").append(jsonStr(t.contentUri));
                sb.append('}');
            }
            sb.append("]}");
            StorageFiles.writeUtf8Atomic(file, sb.toString());
        } catch (Throwable e) {
            Logger.warn("saveCustomPlaylist failed: {}", e.getMessage());
        }
    }

    private void loadCustomPlaylist() {
        try {
            java.nio.file.Path file = AppDirs.stateFile("custom-playlist.json");
            if (!java.nio.file.Files.exists(file)) return;
            String text = StorageFiles.readUtf8(file);
            com.google.gson.JsonObject root = new com.google.gson.JsonParser().parse(text).getAsJsonObject();
            com.google.gson.JsonArray arr = root.has("tracks") ? root.getAsJsonArray("tracks") : new com.google.gson.JsonArray();
            List<Track> loaded = new ArrayList<>();
            for (com.google.gson.JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                com.google.gson.JsonObject o = el.getAsJsonObject();
                Track t = new Track();
                String src = o.has("source") ? o.get("source").getAsString() : "NETEASE";
                t.source = "LOCAL".equals(src) ? Track.Source.LOCAL
                        : "CUSTOM_API".equals(src) ? Track.Source.CUSTOM_API
                        : "PLUGIN".equals(src) ? Track.Source.PLUGIN
                        : Track.Source.NETEASE;
                t.neteaseId = o.has("neteaseId") ? o.get("neteaseId").getAsLong() : 0;
                if (o.has("mediaId") && !o.get("mediaId").isJsonNull()) {
                    t.applyCanonicalId(o.get("mediaId").getAsString());
                }
                t.title    = o.has("title")    && !o.get("title").isJsonNull()    ? o.get("title").getAsString()    : "";
                t.artist   = o.has("artist")   && !o.get("artist").isJsonNull()   ? o.get("artist").getAsString()   : "";
                t.artistId = o.has("artistId") ? o.get("artistId").getAsLong() : 0L;
                t.artistMediaId = o.has("artistMediaId") && !o.get("artistMediaId").isJsonNull()
                        ? o.get("artistMediaId").getAsString() : "";
                t.artistIdsCsv = o.has("artistIdsCsv") && !o.get("artistIdsCsv").isJsonNull()
                        ? o.get("artistIdsCsv").getAsString() : "";
                t.artistNamesCsv = o.has("artistNamesCsv") && !o.get("artistNamesCsv").isJsonNull()
                        ? o.get("artistNamesCsv").getAsString() : "";
                t.album    = o.has("album")    && !o.get("album").isJsonNull()    ? o.get("album").getAsString()    : "";
                t.durationMs = o.has("durationMs") ? o.get("durationMs").getAsLong() : 0;
                if (t.source == Track.Source.LOCAL) {
                    t.filePath   = o.has("filePath")   && !o.get("filePath").isJsonNull()   ? o.get("filePath").getAsString()   : null;
                    t.contentUri = o.has("contentUri") && !o.get("contentUri").isJsonNull() ? o.get("contentUri").getAsString() : null;
                    // A saved local entry whose file the library no longer has (deleted,
                    // or a rescan just hasn't run yet this launch) still shows up with
                    // its last-known title/artist rather than silently vanishing; play
                    // will simply fail like any other missing local file would.
                } else {
                    t.coverUrl = o.has("coverUrl") && !o.get("coverUrl").isJsonNull() ? o.get("coverUrl").getAsString() : "";
                    t.coverThumbPath = trackCoverUrl(t, "128");
                }
                t.canonicalId();
                loaded.add(t);
            }
            if (!loaded.isEmpty()) {
                customPlaylist.addAll(loaded);
                final List<Track> snap = new ArrayList<>(loaded);
                post(() -> customPlaylistTracks.set(snap));
            }
        } catch (Throwable e) {
            Logger.warn("loadCustomPlaylist failed: {}", e.getMessage());
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

    /** Queue a netease song-list and start at {@code i}. Search history is fed by
     *  the query text the user actually typed/submitted (SearchPage.qml), not by
     *  which result they clicked — a song title isn't a search the user made. */
    public void playSearchResult(int i) {
        List<NeteaseSong> songs = searchResults.peek();
        playSongList(songs, i);
    }

    public void playRecommendation(int i) {
        if (Boolean.TRUE.equals(sourceContentActive.peek())) {
            playPluginSongList(sourceRecommendations.peek(), i);
            return;
        }
        playSongList(recommendations.peek(), i);
    }

    public void playRecentSong(int i) {
        if (Boolean.TRUE.equals(sourceContentActive.peek())) {
            playPluginSongList(sourceRecentSongs.peek(), i);
            return;
        }
        playSongList(recentSongs.peek(), i);
    }

    public void playPlaylistTrack(int i) {
        if (!openSourcePlaylistId.peek().isEmpty()) {
            playPluginSongList(sourcePlaylistTracks.peek(), i, openSourcePlaylistId.peek());
            return;
        }
        playSongList(playlistTracks.peek(), i, currentPlaylistId);
    }

    /** Replace the queue with NetEase's heart-mode recommendations, using the
     *  current song when this playlist already owns the queue and otherwise its
     *  first track as the seed. */
    public void startIntelligenceMode(long playlistId) {
        if (!loggedIn.peek()) {
            showToast("请先登录后使用心动推荐");
            return;
        }
        if (playlistId == 0L || Boolean.TRUE.equals(intelligenceLoading.peek())) return;
        List<NeteaseSong> visible = playlistTracks.peek();
        long seedId = 0L;
        Track current = currentTrack();
        if (currentQueuePlaylistId == playlistId && current != null
                && current.source == Track.Source.NETEASE) {
            seedId = current.neteaseId;
        }
        if (seedId == 0L && visible != null && !visible.isEmpty()) seedId = visible.get(0).id;
        if (seedId == 0L) {
            showToast("歌单中暂无可推荐歌曲");
            return;
        }
        final long seed = seedId;
        intelligenceLoading.set(true);
        worker.submit(() -> {
            try {
                // NetEase currently accepts only the account's default "我喜欢的音乐"
                // playlist as the intelligence context. The seed itself may come
                // from any normal playlist (the official client does the same).
                // Passing the visible playlist directly makes the endpoint reject
                // nearly every user-created/subscribed list with "不支持该歌单类型".
                long intelligencePid = favoritePid;
                if (intelligencePid == 0L) {
                    long accountId = uid != 0L ? uid : netease.loginUid();
                    List<NeteasePlaylist> playlists = netease.userPlaylists(accountId, 1);
                    if (!playlists.isEmpty()) {
                        intelligencePid = playlists.get(0).id;
                        favoritePid = intelligencePid;
                    }
                }
                if (intelligencePid == 0L) throw new java.io.IOException("无法获取我喜欢的音乐歌单");
                List<NeteaseSong> songs =
                        netease.intelligenceSongs(seed, intelligencePid, seed);
                fillMissingCovers(songs);
                buildSongThumbs(songs, "128");
                if (songs.isEmpty()) {
                    post(() -> {
                        intelligenceLoading.set(false);
                        showToast("暂时没有心动推荐");
                    });
                    return;
                }
                post(() -> {
                    intelligenceLoading.set(false);
                    playSongList(songs, 0, playlistId);
                    showToast("已开启心动推荐");
                });
            } catch (Throwable e) {
                Logger.warn("heart-mode recommendation failed: {}", e.getMessage());
                post(() -> {
                    intelligenceLoading.set(false);
                    showToast("获取心动推荐失败：" + safeMessage(e));
                });
            }
        });
    }

    public void startMediaIntelligenceMode(String playlistMediaId) {
        if (!loggedIn.peek()) {
            showToast("请先登录后使用心动推荐");
            return;
        }
        if (playlistMediaId == null || playlistMediaId.isEmpty()
                || Boolean.TRUE.equals(intelligenceLoading.peek())) return;
        final MediaId playlistId;
        try {
            playlistId = MediaId.parse(playlistMediaId).requireKind(
                    dev.t1m3.qplayer.media.MediaKind.PLAYLIST);
        } catch (IllegalArgumentException error) {
            showToast("无效的歌单标识");
            return;
        }
        if (!pluginHasCapability(playlistId.provider(), ProviderCapability.HEART_RECOMMENDATION)) {
            showToast("当前音源不支持心动推荐");
            return;
        }
        MediaId seed = null;
        Track current = currentTrack();
        if (playlistMediaId.equals(currentQueueMediaPlaylistId) && current != null) {
            try {
                MediaId candidate = MediaId.parse(current.canonicalId()).requireKind(
                        dev.t1m3.qplayer.media.MediaKind.SONG);
                if (playlistId.provider().equals(candidate.provider())) seed = candidate;
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (seed == null && !sourcePlaylistTracks.peek().isEmpty()) {
            try { seed = MediaId.parse(sourcePlaylistTracks.peek().get(0).id); }
            catch (IllegalArgumentException ignored) { }
        }
        if (seed == null) {
            showToast("歌单中暂无可推荐歌曲");
            return;
        }
        intelligenceLoading.set(true);
        final MediaId selectedSeed = seed;
        pluginProviders.heartRecommendations(selectedSeed, playlistId, 100)
                .whenComplete((songs, error) -> post(() -> {
                    intelligenceLoading.set(false);
                    if (error != null) {
                        showToast("获取心动推荐失败：" + safeMessage(error));
                    } else if (songs == null || songs.isEmpty()) {
                        showToast("暂时没有心动推荐");
                    } else {
                        playPluginSongList(songs, 0, playlistMediaId);
                        showToast("已开启心动推荐");
                    }
                }));
    }

    /** Fetch a playlist from its card context menu and start it from the first song.
     *  This deliberately does not open the detail page or replace its loading state. */
    public void playPlaylist(long playlistId) {
        if (playlistId == 0) return;
        worker.submit(() -> {
            List<NeteaseSong> songs;
            try {
                songs = netease.playlistTracks(playlistId, 200);
                fillMissingCovers(songs);
                buildSongThumbs(songs, "128");
            } catch (Throwable e) {
                Logger.warn("play playlist {} failed: {}", playlistId, e.getMessage());
                PlaylistCacheIndex.Cached cached = playlistCacheIndex.get(playlistId);
                if (cached == null || cached.songs.isEmpty()) {
                    showToast("播放歌单失败");
                    return;
                }
                songs = new ArrayList<>(cached.songs.size());
                for (NeteaseSong song : cached.songs) songs.add(withLocalThumb(song));
            }
            if (songs.isEmpty()) {
                showToast("歌单中暂无歌曲");
                return;
            }
            List<NeteaseSong> ready = songs;
            post(() -> playSongList(ready, 0, playlistId));
        });
    }

    /** Source-neutral playlist playback entry point used by cards and menus. */
    public void playMediaPlaylist(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return;
        if (mediaId.indexOf(':') < 0) {
            try { playPlaylist(Long.parseLong(mediaId)); } catch (NumberFormatException ignored) {}
            return;
        }
        final MediaId id;
        try { id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST); }
        catch (IllegalArgumentException error) { showToast("无效的歌单标识"); return; }
        pluginProviders.playlist(id).whenComplete((playlist, error) -> post(() -> {
            Playlist resolved = playlist;
            if (error != null || resolved == null) {
                resolved = mediaPlaylistCacheIndex.get(id.toString());
                if (resolved == null) {
                    showToast("播放歌单失败：" + safeMessage(error));
                    return;
                }
            }
            if (resolved.songs.isEmpty()) {
                showToast("歌单中暂无歌曲");
                return;
            }
            mediaPlaylistCacheIndex.upsert(resolved);
            worker.submit(mediaPlaylistCacheIndex::save);
            playPluginSongList(resolved.songs, 0, id.toString());
        }));
    }

    public void copyMediaReference(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return;
        if (mediaId.indexOf(':') < 0) {
            try { copyPlaylistLink(Long.parseLong(mediaId)); } catch (NumberFormatException ignored) {}
            return;
        }
        java.util.function.Consumer<String> sink = clipboard;
        if (sink != null) {
            sink.accept(mediaId);
            showToast("已复制媒体标识");
        } else {
            showToast(mediaId);
        }
    }

    public void shareMedia(String mediaId) {
        final MediaId id;
        try { id = MediaId.parse(mediaId); }
        catch (IllegalArgumentException error) { showToast("无效的媒体标识"); return; }
        if (!pluginHasCapability(id.provider(), ProviderCapability.SHARE)) {
            copyMediaReference(mediaId);
            return;
        }
        pluginProviders.share(id).whenComplete((url, error) -> post(() -> {
            if (error != null || url == null || url.isEmpty()) {
                showToast("获取分享链接失败：" + safeMessage(error));
                return;
            }
            java.util.function.Consumer<String> sink = clipboard;
            if (sink != null) {
                sink.accept(url);
                showToast("已复制链接");
            } else showToast(url);
        }));
    }

    /** Play a single netease song id (no surrounding queue). */
    public void playNetease(long songId) {
        Track t = new Track();
        t.source = Track.Source.NETEASE;
        t.neteaseId = songId;
        playQueue(Collections.singletonList(t), 0);
    }

    private void playSongList(List<NeteaseSong> songs, int i) {
        playSongList(songs, i, 0L);
    }

    private void playSongList(List<NeteaseSong> songs, int i, long playlistId) {
        if (songs == null || i < 0 || i >= songs.size()) return;
        List<Track> q = new ArrayList<>(songs.size());
        for (NeteaseSong s : songs) q.add(toTrack(s));
        playQueue(q, i, playlistId);
    }

    private void playPluginSongList(List<Song> songs, int i) {
        playPluginSongList(songs, i, "");
    }

    private void playPluginSongList(List<Song> songs, int i, String playlistId) {
        if (songs == null || i < 0 || i >= songs.size()) return;
        List<Track> tracks = new ArrayList<>(songs.size());
        for (Song song : songs) tracks.add(toTrackPlugin(song));
        currentQueueMediaPlaylistId = playlistId != null ? playlistId : "";
        playQueue(tracks, i);
    }

    /** Queue one provider's stable search bucket and start the selected row. */
    public void playPluginSearchResult(String providerId, int i) {
        List<Song> songs = pluginSearchByProvider.get(providerId);
        if (songs == null || i < 0 || i >= songs.size()) return;
        List<Track> q = new ArrayList<>(songs.size());
        for (Song song : songs) q.add(toTrackPlugin(song));
        playQueue(q, i);
    }

    private void playQueue(List<Track> q, int start) {
        playQueue(q, start, 0L);
    }

    private void playQueue(List<Track> q, int start, long sourcePlaylistId) {
        currentQueuePlaylistId = sourcePlaylistId;
        if (sourcePlaylistId != 0L) currentQueueMediaPlaylistId = "";
        queue.clear();
        queue.addAll(q);
        queueTracks.set(new ArrayList<>(queue));
        onMain(() -> playAt(start));
    }

    // Runs on the main thread (via onMain). Updates the plain playIndex synchronously,
    // marshals UI Property writes to the render thread via post(), and drives the
    // backend directly so playback advances even while the GL pump is paused.
    /** Reports the track being switched away from to netease's play-report endpoint,
     *  so its own server-side 最近播放/play-count history reflects plays made through
     *  qplayer (see {@link NeteaseClient#scrobble}). Only netease tracks qualify —
     *  local/custom-API sources have no netease-side record to update. Skips a track
     *  abandoned within its first few seconds (accidental clicks, rapid browsing),
     *  same rough threshold the official client applies. Reads the *live* backend
     *  clock, not the {@link #positionMs} Property (which only refreshes from
     *  pump() and can be stale right at a transition) — same reasoning as
     *  {@link #saveQueue()}'s position capture. */
    private void scrobbleOutgoingTrack(boolean naturalEnd) {
        if (playIndex < 0 || playIndex >= queue.size()) return;
        Track t = queue.get(playIndex);
        if (t.source == Track.Source.PLUGIN && t.mediaId != null) {
            long played = Math.max(0L, backend.position());
            if (played < 3000L) return;
            try {
                MediaId id = MediaId.parse(t.mediaId).requireKind(
                        dev.t1m3.qplayer.media.MediaKind.SONG);
                if (pluginHasCapability(id.provider(), ProviderCapability.SCROBBLE)) {
                    pluginProviders.scrobble(id, played, t.durationMs, naturalEnd)
                            .exceptionally(error -> {
                                Logger.warn("plugin scrobble failed: {}", safeMessage(error));
                                return null;
                            });
                }
            } catch (IllegalArgumentException ignored) {
            }
            return;
        }
        if (t.source != Track.Source.NETEASE || t.neteaseId == 0) return;
        long seconds = Math.max(0L, backend.position()) / 1000L;
        if (seconds < 3) return;
        long songId = t.neteaseId;
        String end = naturalEnd ? "playend" : "ui";
        worker.submit(() -> netease.scrobble(songId, 0L, seconds, end));
    }

    private void playAt(int i) {
        if (i < 0 || i >= queue.size()) return;
        String requestedMediaId = queue.get(i).canonicalId();
        if (pendingPluginDesiredPlaying != null
                && !pendingPluginTargetMediaId.equals(requestedMediaId)) {
            pendingPluginDesiredPlaying = null;
            pendingPluginTargetMediaId = "";
        }
        // Any real local/remote selection supersedes a follower's delayed takeover.
        // The generation also makes an already-queued timeout callback harmless.
        // loadQueue() sets needsReplay because its restored track exists only as
        // metadata until the user resumes it. Any successful route into playAt(),
        // including clicking a different song first, is now taking responsibility
        // for loading a real backend source, so the restore-only marker must not
        // survive. If it leaked, the first pause -> resume later called playAt()
        // again instead of backend.resume(); pendingResumeMs had already been
        // consumed, so that accidental replay restarted the current song at 0.
        needsReplay = false;
        scrobbleOutgoingTrack(pendingNaturalEnd);
        pendingNaturalEnd = false;
        playIndex = i;
        final long currentCoverRevision = coverRevision.incrementAndGet();
        // Consumed unconditionally on every call (see field comment), so a saved
        // session position only ever gets one shot at applying, and only to the
        // exact slot it was saved for.
        long resumeMs = (i == pendingResumeIndex) ? Math.max(0L, pendingResumeMs) : 0L;
        pendingResumeMs = 0L;
        pendingResumeIndex = -1;
        beginLyricClockLoad(resumeMs);
        // Blank the now-playing surface (lyrics + cover fall back to their
        // placeholders) and start the progress bars' loading sweep, whether or not
        // the outgoing track fades. The loaders below re-apply the real lyrics/cover
        // in the same render-queue drain (no flash for instant sources); the
        // backend's onStarted ends the sweep once playback actually begins.
        // If something is actually audible, ramp it down instead of a hard cut —
        // FADE_OUT_MS is well under how long a netease URL resolve typically takes,
        // so this never adds perceptible wait on top of that; backend.pause() only
        // runs once the ramp reaches silence. Nothing to fade when already silent
        // (fresh start, already paused) — pause immediately as before.
        if (fadeEnabled && backend.isPlaying()) {
            startFadeOut(FADE_OUT_MS, () -> backend.pause());
        } else {
            backend.pause();
        }
        post(() -> {
            loading.set(true);
            applyLyrics(Collections.<LyricLine>emptyList());
            applyCover(null, currentCoverRevision);
            coverPath.set("");
        });
        worker.submit(this::saveQueue);
        final int idx = i;
        final Track t = queue.get(i);
        post(() -> {
            applyTrackLyricOffset(t);
            index.set(idx);
            currentFilePath.set(t.source == Track.Source.LOCAL && t.filePath != null ? t.filePath : "");
            title.set(orEmpty(t.title));
            artist.set(orEmpty(t.artist));
            publishPlayingArtist(t);
            album.set(orEmpty(t.album));
            coverUrl.set(orEmpty(trackCoverUrl(t, "512")));
            durationMs.set(t.durationMs);
            positionMs.set(resumeMs);
            String canonical = t.canonicalId();
            boolean pluginLikeable = t.source == Track.Source.PLUGIN
                    && providerForMediaHas(canonical, ProviderCapability.LIKE);
            currentLiked.set(pluginLikeable ? pluginLikedSet.contains(canonical)
                    : t.neteaseId != 0 && likedSet.contains(t.neteaseId));
            currentLikeable.set(pluginLikeable || t.neteaseId != 0);
        });
        updateCover(t, i, currentCoverRevision);

        if (t.source == Track.Source.LOCAL) {
            String src = t.playable();
            if (src == null || src.isEmpty()) return;
            loadLocalLyrics(t);
            Logger.info("play local: {}", t.title);
            playBackend(src, resumeMs);
            playingIntent = true;
            post(() -> playing.set(true));
            notifyPlayback();
        } else if (t.source == Track.Source.NETEASE) {
            if (onlineSourcesArePluginOnly()) {
                playingIntent = false;
                post(() -> {
                    playing.set(false);
                    loading.set(false);
                    showToast("请安装并启用与该歌曲匹配的音源插件");
                });
                notifyPlayback();
                return;
            }
            // Always prefer a cached local file over (re-)streaming, regardless of
            // play mode — a song played once is served from disk on every later play.
            String cached = t.neteaseId != 0 ? diskCache.getAudio(t.neteaseId) : null;
            if (cached != null) {
                Logger.info("play netease (audio cache): {}", t.title);
                playBackend(cached, resumeMs);
                playingIntent = true;
                post(() -> playing.set(true));
                notifyPlayback();
                // The audio fast-path skips resolveAndPlayNetease, so load the lyrics
                // (cache-first inside) here too — else a cached song plays wordless.
                loadNeteaseLyrics(t, i);
                // Same reason: cacheAudioAsync (which downloads the 64x64 offline-
                // playlist thumbnail) never runs on this path either, so a track
                // cached before that thumbnail existed — or just replayed a second
                // time — would otherwise never get one and stay a gray placeholder
                // in an offline playlist's song list forever.
                cacheThumb64Async(t.coverUrl);
            } else if (t.streamUrl != null) {
                Logger.info("play netease (cached url): {}", t.title);
                playBackend(t.playable(), resumeMs);
                playingIntent = true;
                post(() -> playing.set(true));
                notifyPlayback();
                loadNeteaseLyrics(t, i);
                // Populate the disk cache so the next play is local (skip trial clips).
                cacheAudioAsync(t);
            } else {
                resolveAndPlayNetease(t, i, resumeMs, currentCoverRevision);
            }
        } else if (t.source == Track.Source.CUSTOM_API) {
            skipUnplayable(i, "旧自定义音源已移除，请安装对应音源插件");
        } else if (t.source == Track.Source.PLUGIN) {
            String cached = diskCache.getAudio(t.canonicalId());
            if (cached != null) {
                Logger.info("play plugin {} (audio cache): {}", providerOf(t), t.title);
                playBackend(cached, resumeMs);
                playingIntent = true;
                post(() -> playing.set(true));
                notifyPlayback();
                loadPluginLyrics(t, i);
            } else if (hasFreshPluginStream(t)) {
                Logger.info("play plugin {} (cached url): {}", providerOf(t), t.title);
                playBackend(t.playable(), t.streamHeaders, resumeMs);
                playingIntent = true;
                post(() -> playing.set(true));
                notifyPlayback();
                loadPluginLyrics(t, i);
                cachePluginAudioAsync(t, false, null);
            } else {
                t.streamUrl = null;
                t.streamHeaders.clear();
                resolveAndPlayPlugin(t, i, resumeMs, currentCoverRevision);
            }
        }
        // Current track's fetches are now queued; warm next/prev behind them.
        preloadAdjacent();
    }

    /** Feed coverBytes for the fluid backdrop: local tracks carry embedded
     *  bytes; NETEASE tracks download lazily off-thread, keyed by queue index
     *  so a stale fetch for a skipped-past track is dropped. */
    private void updateCover(Track t, int expectedIndex, long revision) {
        if (t.coverBytes != null) {   // present (embedded, or preloaded by preloadTrack)
            final byte[] cb = t.coverBytes;
            final String path = coverDiskPath(t);   // local file for the QML cover image
            post(() -> { if (playIndex == expectedIndex) { applyCover(cb, revision); coverPath.set(path); } });
            notifyPlayback();
            return;
        }
        // Local track: cover lives in a cache file (an absolute path, not an http url).
        // Prefer the larger now-playing copy over the row thumbnail for the fluid
        // backdrop + Monet seed; fall back to the thumbnail if only it exists.
        // Note: this must not test for a leading "/" — that only holds for Unix-style
        // paths (Android) and silently breaks Windows desktop, where local-cache cover
        // paths look like "C:\Users\...\covers\<hash>.img" instead.
        // LOCAL-source only: a NETEASE coverThumbPath can also hold a local 64px offline
        // thumb (diskCache.getThumb64) — treating that as the now-playing cover would
        // stick the lyric page / SMTC on a postage-stamp image even while the full-size
        // cover is a moment away online. Those tracks fall through to the 1024 fetch.
        String localCover = t.coverLocalPath != null ? t.coverLocalPath : t.coverThumbPath;
        if (t.source == Track.Source.LOCAL
                && localCover != null && !localCover.startsWith("http://") && !localCover.startsWith("https://")) {
            byte[] data = readBytesFromFile(localCover);
            if (data != null && data.length > 0) {
                // Keep the bytes on the current Track so the media session can read the
                // cover off the render thread (see PlaybackService): the coverBytes
                // Property is only committed on the render queue, which is paused while
                // backgrounded, so a background track-switch would otherwise show stale art.
                t.coverBytes = data;
                final String path = localCover;
                post(() -> {
                    if (playIndex == expectedIndex) {
                        applyCover(data, revision);
                        coverPath.set(path);
                    }
                });
                notifyPlayback();
                return;
            }
        }
        // A 128px netease thumbnail is enough for the 32px color histogram and
        // usually arrives well before the 1024px lyric/background copy below.
        // Its lower-quality seed is replaced (never overwritten) by the full one.
        scheduleFastMonet(t, revision);
        post(() -> {
            if (playIndex == expectedIndex) {
                applyCover(null, revision);
                coverPath.set("");
            }
        });
        if (t.coverUrl == null || t.coverUrl.isEmpty()) return;
        // The original (netease covers are commonly 1000-3000px+) was being fetched
        // uncapped for the fluid lyric backdrop -- on a slow connection that routinely
        // missed the fixed download timeout below while the UI's own 512px thumbnail
        // (see the coverUrl Property, thumbUrl(t.coverUrl, "512")) came in fine, so the
        // lyric page sat on its gray placeholder even though a perfectly good cover was
        // already showing elsewhere. A blurred full-screen backdrop doesn't need more
        // detail than this anyway.
        final String url = trackCoverUrl(t, "1024");

        // Check disk cache first.
        String cachedImg = diskCache.getImage(url);
        if (cachedImg != null) {
            byte[] data = readBytesFromFile(cachedImg);
            if (data != null && data.length > 0) {
                t.coverBytes = data;
                final String path = cachedImg;
                post(() -> { if (playIndex == expectedIndex) { applyCover(data, revision); coverPath.set(path); } });
                notifyPlayback();
                return;
            }
        }

        if (t.source == Track.Source.PLUGIN) {
            String provider = providerOf(t);
            pluginHostApi.fetchReturnedBytes(provider, url, Collections.<String, String>emptyMap(),
                    16L * 1024L * 1024L, 8_000).whenComplete((data, error) -> {
                if (error != null || data == null || data.length == 0) {
                    Logger.warn("plugin {} cover fetch failed: {}", provider,
                            error != null ? safeMessage(error) : "empty response");
                    return;
                }
                t.coverBytes = data;
                String imgPath = diskCache.imagePath(url);
                if (imgPath != null) writeBytesToFile(data, imgPath);
                post(() -> {
                    if (playIndex == expectedIndex) {
                        applyCover(data, revision);
                        if (imgPath != null) coverPath.set(imgPath);
                    }
                });
                notifyPlayback();
            });
            return;
        }

        worker.submit(() -> {
            byte[] data = downloadBytes(url);
            if (data == null) return;
            t.coverBytes = data;
            // Cache cover image to disk (write already-downloaded bytes, no re-fetch).
            String imgPath = diskCache.imagePath(url);
            if (imgPath != null) writeBytesToFile(data, imgPath);
            final String path = imgPath;
            post(() -> {
                if (playIndex == expectedIndex) {
                    applyCover(data, revision);
                    if (path != null) coverPath.set(path);
                }
            });
            notifyPlayback(); // refresh the media-notification artwork
        });
    }

    /** Push cover bytes (render thread) and kick off Monet seed extraction on its
     *  own bounded executor, never the general network queue. */
    private void applyCover(byte[] data, long revision) {
        if (coverRevision.get() != revision) return;
        coverBytes.set(data);
        // No cover yet (a netease track's art is still downloading): keep the previous
        // seed so the theme doesn't flash back to the default purple between songs. The
        // new cover's seed replaces it directly once extracted.
        if (data == null) return;
        scheduleSeedExtraction(data, revision, 1);
    }

    /** Fetch a tiny current-track cover independently of the 1024px backdrop.
     *  Netease supports CDN resize parameters; other sources keep using their full
     *  cover path to avoid assuming an unsupported URL format. */
    private void scheduleFastMonet(Track t, long revision) {
        final ColorExtractor ex = colorExtractor;
        if (ex == null || t == null) return;
        String localThumb = t.coverThumbPath;
        if (localThumb != null && !localThumb.isEmpty()
                && !localThumb.startsWith("http://") && !localThumb.startsWith("https://")) {
            final String path = localThumb;
            monetFetchWorker.execute(() -> {
                if (coverRevision.get() != revision) return;
                byte[] data = readBytesFromFile(path);
                if (data != null && data.length > 0) scheduleSeedExtraction(data, revision, 0);
            });
            return;
        }
        if (t.source != Track.Source.NETEASE || t.coverUrl == null || t.coverUrl.isEmpty()) return;
        final String url = thumbUrl(t.coverUrl, "128");
        monetFetchWorker.execute(() -> {
            if (coverRevision.get() != revision) return;
            byte[] data = null;
            String cached = diskCache.getImage(url);
            if (cached != null) data = readBytesFromFile(cached);
            if (data == null || data.length == 0) {
                data = downloadBytes(url, 3000);
                if (data != null) {
                    String path = diskCache.imagePath(url);
                    if (path != null) writeBytesToFile(data, path);
                }
            }
            if (data != null && data.length > 0 && coverRevision.get() == revision) {
                scheduleSeedExtraction(data, revision, 0);
            }
        });
    }

    private void scheduleSeedExtraction(byte[] data, long revision, int quality) {
        final ColorExtractor ex = colorExtractor;
        if (ex == null || data == null || data.length == 0) return;
        monetWorker.execute(() -> {
            if (coverRevision.get() != revision) return;
            String hex;
            try {
                hex = ex.dominantHex(data);
            } catch (Throwable ignored) {
                return;
            }
            if (hex == null || coverRevision.get() != revision) return;
            post(() -> {
                if (coverRevision.get() != revision) return;
                if (appliedSeedRevision == revision && quality < appliedSeedQuality) return;
                appliedSeedRevision = revision;
                appliedSeedQuality = quality;
                coverSeed.set(hex);
                reapplySeed();
            });
        });
    }

    /** Local file path for a track's cover (for the QML cover Image): the on-disk
     *  full/thumb copy for a local track, or the disk-cached download for a netease
     *  one; "" when only a remote URL is available. */
    private String coverDiskPath(Track t) {
        // On-disk full/thumb cover files exist only for LOCAL tracks (LibraryCache).
        // A NETEASE coverThumbPath may instead point at a tiny 64px offline thumb —
        // never surface that as the now-playing cover; that must come from the
        // 1024px cache/download below (SMTC/MiniPlayer fall back to the remote URL).
        if (t.source == Track.Source.LOCAL) {
            String local = t.coverLocalPath != null ? t.coverLocalPath : t.coverThumbPath;
            if (local != null && !local.startsWith("http://") && !local.startsWith("https://")) return local;
        }
        if (t.coverUrl != null && !t.coverUrl.isEmpty()) {
            // Must use the same 1024px key updateCover() and loadCoverBytes() cache
            // under: hashing the raw coverUrl points at a file nothing ever writes,
            // so this always returned "". That went unnoticed everywhere except the
            // lyric page, whose cover Image binds coverPath alone (the MiniPlayer's
            // falls back to the remote coverUrl) -- so playing a track preloaded by
            // preloadAdjacent, which takes updateCover's coverBytes fast path and
            // gets its coverPath from here, left the lyric page on its placeholder
            // while the fluid backdrop and Monet seed came up fine from the same bytes.
            String cached = diskCache.getImage(trackCoverUrl(t, "1024"));
            if (cached != null) return cached;
        }
        return "";
    }

    /** Resolve a track's cover bytes (embedded -> local file -> disk cache -> download,
     *  writing the download to the disk cache). Blocking; call on the worker thread. */
    private byte[] loadCoverBytes(Track t) {
        if (t.coverBytes != null) return t.coverBytes;
        // Same LOCAL-only rule as updateCover/coverDiskPath: a local coverThumbPath on
        // a netease track is its 64px offline thumb, not the now-playing artwork.
        if (t.source == Track.Source.LOCAL) {
            String local = t.coverLocalPath != null ? t.coverLocalPath : t.coverThumbPath;
            if (local != null && !local.startsWith("http://") && !local.startsWith("https://")) {
                byte[] d = readBytesFromFile(local);
                if (d != null && d.length > 0) return d;
            }
        }
        if (t.coverUrl == null || t.coverUrl.isEmpty()) return null;
        // Same 1024px cap as updateCover() -- see its comment for why.
        String url = trackCoverUrl(t, "1024");
        String cachedImg = diskCache.getImage(url);
        if (cachedImg != null) {
            byte[] d = readBytesFromFile(cachedImg);
            if (d != null && d.length > 0) return d;
        }
        byte[] data = downloadBytes(url);
        if (data != null) {
            String imgPath = diskCache.imagePath(url);
            if (imgPath != null) writeBytesToFile(data, imgPath);
        }
        return data;
    }

    /** After the current track settles, warm the next + previous tracks' lyrics and
     *  cover bytes on the worker so switching to them is instant (no load-in stutter).
     *  Runs on the main thread; submits per-track work that queues behind the current
     *  track's own fetches (single worker), so the current song always loads first. */
    private void preloadAdjacent() {
        int n = queue.size();
        if (n <= 1) return;
        int cur = playIndex;
        if (cur < 0 || cur >= n) return;
        Track next = queue.get((cur + 1) % n);
        Track prev = queue.get((cur - 1 + n) % n);
        preloadTrack(next);
        if (prev != next) preloadTrack(prev);
    }

    private void preloadTrack(Track t) {
        if (t == null) return;
        if (t.source == Track.Source.NETEASE && t.neteaseId != 0 && !lyricMem.containsKey(t.neteaseId)) {
            final long id = t.neteaseId;
            worker.submit(() -> fetchNeteaseLyrics(id));
        }
        if (t.coverBytes == null && t.source != Track.Source.PLUGIN) {
            final Track tr = t;
            worker.submit(() -> {
                byte[] data = loadCoverBytes(tr);
                if (data != null) tr.coverBytes = data;
            });
        }
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

    /** Read the former AMLL TTML cache during delayed migration. New online lyric
     * assets are supplied and cached through the selected source plugin. */
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
        return Collections.emptyList();
    }

    private static byte[] downloadBytes(String url) {
        return downloadBytes(url, 8000);
    }

    private static byte[] downloadBytes(String url, int timeoutMs) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
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
        try {
            StorageFiles.writeBytesAtomic(java.nio.file.Paths.get(path), data);
        } catch (Throwable ignored) { }
    }

    public void toggle() {
        onMain(() -> {
            if (playIndex < 0 && !library.isEmpty()) {
                play(0);
                return;
            }
            if (playingIntent) {
                playingIntent = false;
                post(() -> playing.set(false));
                if (fadeEnabled) {
                    // UI reflects paused immediately; the actual backend.pause()
                    // is deferred until the fade-out reaches silence so it's a
                    // ramp-down, not a hard cut. If the user hits resume again
                    // before that fires, playingIntent is already back to true
                    // by then and this deferred call is skipped entirely — the
                    // resume branch below picks it up as "never really paused".
                    startFadeOut(FADE_OUT_MS, () -> { if (!playingIntent) backend.pause(); });
                } else {
                    backend.pause();
                }
            } else {
                if (needsReplay && playIndex >= 0) {
                    needsReplay = false;
                    playAt(playIndex);
                    return;
                }
                if (isFadeRunning() && backend.isPlaying()) {
                    // Caught mid a pause's deferred fade-out (backend.pause() never
                    // actually ran) -- cancel that pending pause and ramp back up
                    // from wherever the gain currently sits instead of restarting
                    // from silence or leaving it stuck fading down.
                    if (fadeEnabled) {
                        startVolumeFade(currentFadeGain(), 1f, FADE_IN_MS, null);
                    } else {
                        cancelFadeAtGain(1f);
                    }
                } else {
                    // Genuinely paused already (no ramp in flight to pick back up) --
                    // fade back in from silence, symmetric with the pause fade-out.
                    if (fadeEnabled) {
                        startVolumeFade(0f, 1f, FADE_IN_MS, null);
                    } else {
                        cancelFadeAtGain(1f);
                    }
                    backend.resume();
                }
                playingIntent = true;
                post(() -> playing.set(true));
            }
            notifyPlayback();
        });
    }

    /** Cycle list-loop -> shuffle -> repeat-one -> list-loop. */
    public void cyclePlayMode() {
        setPlayMode((playMode.peek() + 1) % 3);
    }

    /** Select list-loop (0), shuffle (1), or repeat-one (2). Host media-session
     *  adapters use this for their explicit shuffle/repeat properties. */
    public void setPlayMode(int mode) {
        playMode.set(Math.max(0, Math.min(2, mode)));
        notifyPlayback();
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
            suppressNextFadeIn = true;
            playAt(playMode.peek() == 1 ? randomIndex() : (playIndex + 1) % queue.size());
        });
    }

    public void prev() {
        onMain(() -> {
            if (queue.isEmpty()) return;
            int n = queue.size();
            suppressNextFadeIn = true;
            playAt(playMode.peek() == 1 ? randomIndex() : (playIndex - 1 + n) % n);
        });
    }

    // Track finished on its own: repeat-one replays it, shuffle jumps randomly,
    // list-loop advances. Wired to backend.onComplete (not next()) so repeat-one
    // doesn't fight a user's manual skip. Already on the main thread (onComplete).
    private void autoAdvance() {
        if (queue.isEmpty()) return;
        pendingNaturalEnd = true;
        playbackEndRevision.incrementAndGet();
        if (!pluginAutoAdvanceBlocker.isEmpty()) return;
        performAutoAdvance();
    }

    private void performAutoAdvance() {
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
            resetNaturalEndFadeAfterSeek();
            seekRevision.incrementAndGet();
            stoppedLyricPositionMs = t;
            backend.seek(t);
            post(() -> positionMs.set(t));
            notifyPlayback();
        });
    }

    /** Seek immediately — like {@link #mediaPause()} / {@link #mediaResume()},
     *  bypasses the main-thread Handler to avoid OEM background throttling. */
    public void mediaSeek(long ms) {
        final long t = Math.max(0L, ms);
        resetNaturalEndFadeAfterSeek();
        seekRevision.incrementAndGet();
        stoppedLyricPositionMs = t;
        backend.seek(t);
        post(() -> positionMs.set(t));
        notifyPlayback();
    }

    public long seekRevision() {
        return seekRevision.get();
    }

    private void resetNaturalEndFadeAfterSeek() {
        // Seeking away from the final fade window must restore normal gain. Without
        // this, the one-shot end marker remains set and the track continues silently
        // at the completed fade's zero gain for the rest of its new position.
        if (fadeOutDoneForTrack) {
            fadeOutDoneForTrack = false;
            cancelFadeAtGain(1f);
        }
    }

    public long position() {
        return backend.position();
    }

    /** True only while song time should advance visually. Unlike {@link #isPlaying()},
     * this stays false during async source loading. During a manual fade-out it stays
     * true until the backend really pauses, keeping lyrics aligned with audible audio. */
    public boolean isLyricClockRunning() {
        return playbackStarted && backend.isPlaying();
    }

    /** Exact lyric-clock baseline. While running this is the live backend position;
     * after a source has started, its paused position remains the source of truth too,
     * so pause/resume cannot diverge and then visibly jump back into alignment. */
    public long lyricClockPosition() {
        return playbackStarted
                ? Math.max(0L, backend.position())
                : Math.max(0L, stoppedLyricPositionMs);
    }

    public long playbackRevision() {
        return playbackRevision.get();
    }

    private void beginLyricClockLoad(long startMs) {
        stoppedLyricPositionMs = Math.max(0L, startMs);
        playbackStarted = false;
        playbackRevision.incrementAndGet();
    }

    /**
     * Position intended for a host media session. Unlike {@link #position()},
     * this also sees a queue's saved resume point before the asynchronous UI
     * queue has published it and before the audio backend has started.
     */
    public long mediaSessionPosition() {
        long backendPosition = Math.max(0L, backend.position());
        if (playingIntent || backendPosition > 0L) return backendPosition;
        if (pendingResumeIndex == playIndex) return Math.max(0L, pendingResumeMs);
        Long propertyPosition = positionMs.peek();
        return propertyPosition != null ? Math.max(0L, propertyPosition) : 0L;
    }

    public void setVolume(float v) {
        float clamped = Math.max(0f, Math.min(1f, v));
        userVolume = clamped;
        applyEffectiveVolume(currentFadeGain());
        volume.set(clamped);
    }

    public void setPlayLevel(String level) {
        if (level != null && !level.isEmpty()) playLevel = level;
    }

    /** Settings toggle: netease playback quality. On (default) requests "exhigh"
     *  (~320kbps); off requests "standard" (~128kbps) to save bandwidth. Only
     *  affects tracks resolved after the change, not the currently playing one. */
    public void setHighQualityEnabled(boolean enabled) {
        setPlayLevel(enabled ? "exhigh" : "standard");
    }

    /** Settings toggle: fade the volume in at the start of a track and out
     *  approaching its natural end, instead of a hard cut. Turning it off
     *  mid-fade snaps straight back to the user's actual volume setting. */
    public void setFadeEnabled(boolean enabled) {
        this.fadeEnabled = enabled;
        if (!enabled) {
            cancelFadeAtGain(1f);
        }
    }

    /** Load a netease track's lyrics off-thread (AMLL TTML mirror, else Netease's
     *  own). tryAmllTtml hits the disk cache first, so a previously-played song shows
     *  its lyrics with no network. Called on every netease play — including the
     *  audio-cache fast path, which bypasses the URL resolve that used to fetch them. */
    private void loadNeteaseLyrics(Track t, int expectedIndex) {
        final long songId = t.neteaseId;
        if (songId == 0) return;
        List<LyricLine> mem = lyricMem.get(songId);
        if (mem != null) {   // preloaded / recently played -> apply instantly
            post(() -> { if (playIndex == expectedIndex) applyLyrics(mem); });
            return;
        }
        // (Lyrics were already blanked at the track switch in playAt; the async fetch
        // eases them back in when it lands.) lyricWorker, not worker: this runs
        // concurrently with updateCover()'s own worker-queued download instead of
        // sitting behind it -- see lyricWorker's field javadoc.
        lyricWorker.submit(() -> {
            List<LyricLine> ly = fetchNeteaseLyrics(songId);
            post(() -> { if (playIndex == expectedIndex) applyLyrics(ly); });
        });
    }

    private void loadPluginLyrics(Track track, int expectedIndex) {
        final String id = track.canonicalId();
        if (id.isEmpty() || !pluginHasCapability(providerOf(track), ProviderCapability.LYRICS)) return;
        List<LyricLine> cached = pluginLyricMem.get(id);
        if (cached != null) {
            post(() -> { if (playIndex == expectedIndex) applyLyrics(cached); });
            return;
        }
        final MediaId mediaId;
        try {
            mediaId = MediaId.parse(id);
        } catch (IllegalArgumentException error) {
            Logger.warn("invalid plugin lyric id {}: {}", id, error.getMessage());
            return;
        }
        pluginProviders.lyrics(mediaId).whenComplete((payload, error) -> {
            if (error != null) {
                Logger.warn("plugin {} lyric fetch failed: {}", mediaId.provider(), safeMessage(error));
                return;
            }
            List<LyricLine> lines;
            try {
                lines = LyricParser.fromPluginAssets(payload);
            } catch (Throwable parseError) {
                Logger.warn("plugin {} lyric parse failed: {}", mediaId.provider(),
                        safeMessage(parseError));
                return;
            }
            if (!lines.isEmpty()) pluginLyricMem.put(id, lines);
            post(() -> { if (playIndex == expectedIndex) applyLyrics(lines); });
        });
    }

    /** Resolve a song's lyrics (mem cache -> AMLL TTML -> netease), caching non-empty
     *  results in memory. Blocking; call on the worker thread. */
    private List<LyricLine> fetchNeteaseLyrics(long songId) {
        List<LyricLine> mem = lyricMem.get(songId);
        if (mem != null) return mem;
        List<LyricLine> lines = tryAmllTtml(songId);
        if (lines.isEmpty()) lines = neteaseLyricCacheFirst(songId);
        if (!lines.isEmpty()) lyricMem.put(songId, lines);
        return lines;
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

    /** Coalescing 1-slot mailbox for {@link #cacheAudioAsync}: rapid skipping
     *  (or a burst of auto-advances) must not queue every skipped-past track's
     *  audio for download one after another on the single-threaded cacheWorker
     *  — by the time an earlier one would start, it's already stale. A new
     *  request just overwrites whatever was pending-but-not-yet-started, so
     *  once the worker is free again it always picks up the LATEST track
     *  (typically whatever's actually still playing), silently dropping the
     *  ones skipped past in between. A download already in flight for a
     *  since-superseded track is still let to finish (aborting a raw socket
     *  read mid-flight isn't worth the complexity for this) before the queue
     *  is drained again. */
    private final Object autoCacheLock = new Object();
    private Track autoCachePending;
    private boolean autoCacheRunning;

    /** Auto-cache a netease track's audio to disk for local replay (playback's
     *  own paths only — the user-initiated "缓存此歌曲" flow is {@link
     *  #cacheSongAsync}, which also marks the result actively-cached). Skips
     *  trial/preview clips and tracks lacking an id or resolved url. */
    private void cacheAudioAsync(Track t) {
        if (t == null || t.trial || t.neteaseId == 0 || t.streamUrl == null) return;
        // Whatever gets its audio cached is, by definition, playable offline —
        // remember its title/artist/cover so offline search can actually surface
        // it later, regardless of whether it was ever a search result itself
        // (played from a playlist/recommendation/liked list, say). Cheap/in-memory,
        // so this always runs even for a track whose actual audio download below
        // ends up coalesced away.
        mediaMetaIndex.upsert(t);
        cacheThumb64Async(t.coverUrl);
        synchronized (autoCacheLock) {
            autoCachePending = t;
            if (autoCacheRunning) return; // the drain loop will pick this up when it's free
            autoCacheRunning = true;
        }
        cacheWorker.submit(this::drainAutoCacheQueue);
    }

    private void drainAutoCacheQueue() {
        while (true) {
            Track t;
            synchronized (autoCacheLock) {
                t = autoCachePending;
                autoCachePending = null;
                if (t == null) {
                    autoCacheRunning = false;
                    return;
                }
            }
            diskCache.cacheAudio(t.streamUrl, t.neteaseId);
            mediaMetaIndex.save();
        }
    }

    /** Manual-cache a netease track (song long-press menu): audio + thumbnail +
     *  full cover + lyrics, so a song cached without ever being played still shows
     *  its art and words offline. Playback's own paths cache those separately on
     *  first play ({@link #updateCover}, {@link #loadNeteaseLyrics}); this makes
     *  the manual action produce the same fully-offline result up front. Lyrics
     *  resolve through the same cache-writing chain as playback (AMLL TTML, then
     *  Netease's own), the cover at the same 1024px key {@link #updateCover} uses.
     *  All disk-cache-first, so re-caching a track that was already played skips
     *  the network. Runs on the cache worker; {@code onDone} fires on the render
     *  thread when the whole job finishes (success or failure). */
    private void cacheSongAsync(Track t, Runnable onDone) {
        if (t == null || t.trial || t.neteaseId == 0 || t.streamUrl == null) return;
        final String url = t.streamUrl;
        final long nid = t.neteaseId;
        final String cover = t.coverUrl;
        // Whatever gets its audio cached is, by definition, playable offline —
        // remember its title/artist/cover so offline search can actually surface
        // it later, regardless of whether it was ever a search result itself.
        mediaMetaIndex.upsert(t);
        cacheWorker.submit(() -> {
            diskCache.cacheAudio(url, nid);
            // Protect it from auto-cache eviction only once the download actually
            // landed -- marking a failed download would protect a file that
            // doesn't exist.
            if (diskCache.hasAudio(nid)) diskCache.markActivelyCached(nid);
            // A manually cached song must be fully offline-ready: pull the cover
            // and lyrics that playback paths only fetch on first play.
            if (cover != null && !cover.isEmpty()) {
                diskCache.cacheImage(thumbUrl(cover, "1024"));
            }
            fetchNeteaseLyrics(nid);   // disk-caches AMLL TTML and/or .nlrc
            mediaMetaIndex.save();
            if (onDone != null) post(onDone);
        });
        cacheThumb64Async(cover);
    }

    /** A 64x64 cover thumbnail for offline playlist browsing (PlaylistCacheIndex
     *  persists coverUrl only, no thumbnail bytes — this is the only place one
     *  actually gets downloaded). Stored in DiskCache's dedicated thumb64
     *  sub-cache, capped by file count (oldest evicted first, see
     *  {@code DiskCache.THUMB64_MAX_COUNT}) rather than a per-call limit here —
     *  browsing a playlist queues one download per track (hasThumb64 skips
     *  ones already cached, so reopening the same playlist is cheap), and the
     *  disk-side cap keeps total thumbnail storage/count bounded regardless of
     *  how many different playlists get browsed over time. Idempotent and safe
     *  to call from any thread — only the actual download runs on
     *  {@link #cacheWorker}. Called from {@link #openPlaylist} for every track in
     *  a freshly (re)opened playlist, and from {@link #cacheAudioAsync} / playAt()'s
     *  already-cached fast path so a track actually played still gets one even
     *  if it was evicted (or never browsed) since. */
    private void cacheThumb64Async(String coverUrl) {
        if (coverUrl == null || coverUrl.isEmpty()) return;
        String thumb64 = thumbUrl(coverUrl, "64");
        if (diskCache.hasThumb64(thumb64)) return;
        cacheWorker.submit(() -> diskCache.cacheThumb64(thumb64));
    }

    /** Same idea as {@link #cacheThumb64Async}, but at 512 instead of 64 —
     *  a playlist's own cover is shown at a much larger size than a track row's
     *  (PlaylistCard tiles, the playlist-detail header), so a 64px cache fallback
     *  looked visibly blurry next to the CDN image it was standing in for. Still
     *  keyed by url (same DiskCache thumb64 sub-cache and file-count cap — the
     *  size lives in the url's own ?param= query, not a separate cache dir). */
    private void cachePlaylistCoverAsync(String coverUrl) {
        if (coverUrl == null || coverUrl.isEmpty()) return;
        String thumb = thumbUrl(coverUrl, gridCoverSize());
        if (diskCache.hasThumb64(thumb)) return;
        cacheWorker.submit(() -> diskCache.cacheThumb64(thumb));
    }

    private void resolveAndPlayNetease(Track t, int expectedIndex, long resumeMs,
                                       long expectedCoverRevision) {
        long songId = t.neteaseId;
        // Compatibility-only playback path for queues persisted before canonical
        // media IDs. Online resolution now belongs to PluginProviderService; this
        // path fails closed after the migration window and contains no endpoint.
        long tSubmit = System.currentTimeMillis();
        worker.submit(() -> {
            long t0 = System.currentTimeMillis();
            long queueWaitMs = t0 - tSubmit;
            if (queueWaitMs > 50) Logger.info("netease: timing queued behind other worker tasks for {}ms", queueWaitMs);
            try {
                Logger.info("netease: resolve song {} (loggedIn={}, level={})",
                        songId, netease.isLoggedIn(), playLevel);
                // Legacy /search/get returns no album picUrl, so search-sourced tracks
                // arrive without a cover; fetch song detail to fill any missing field.
                if (t.title == null || t.title.isEmpty()
                        || t.coverUrl == null || t.coverUrl.isEmpty()) {
                    NeteaseSong sd = netease.songDetail(songId);
                    Logger.info("netease: timing songDetail +{}ms", System.currentTimeMillis() - t0);
                    if (sd != null) {
                        if (t.title == null || t.title.isEmpty()) t.title = sd.name;
                        if (t.artist == null || t.artist.isEmpty()) t.artist = sd.artist;
                        if (t.album == null || t.album.isEmpty()) t.album = sd.album;
                        if (t.coverUrl == null || t.coverUrl.isEmpty()) t.coverUrl = sd.coverUrl;
                        if (t.durationMs <= 0) t.durationMs = sd.durationMs;
                    }
                }
                NeteaseClient.UrlInfo info = netease.songUrlInfo(songId, playLevel);
                Logger.info("netease: timing songUrlInfo +{}ms", System.currentTimeMillis() - t0);
                String url = (info != null && !info.trial) ? info.url : null;
                if (url == null && info != null && info.trial && info.url != null) {
                    url = info.url; // nothing better available — play the preview clip
                }
                final boolean isTrialOnly = info != null && info.trial && url != null;
                Logger.info("netease: url={} (trial={})", url, isTrialOnly);
                Logger.info("netease: timing resolve total +{}ms (click-to-resolve-start {}ms)",
                        System.currentTimeMillis() - t0, queueWaitMs);
                final String playUrl = url;
                // Hop to the main thread for the backend control (works backgrounded);
                // UI Property writes still marshal to the render thread via post().
                onMain(() -> {
                    if (playIndex != expectedIndex) return; // user moved on
                    if (playUrl == null) {
                        Logger.warn("netease song {} has no url (blocked/VIP/login required)", songId);
                        skipUnplayable(expectedIndex, netease.isLoggedIn()
                                ? "VIP/灰色歌曲" : "请先登录");
                        return;
                    }
                    t.streamUrl = playUrl;
                    t.trial = isTrialOnly;
                    post(() -> {
                        if (isTrialOnly) showToast("当前歌曲仅可试听");
                        title.set(orEmpty(t.title));
                        artist.set(orEmpty(t.artist));
                        publishPlayingArtist(t);
                        album.set(orEmpty(t.album));
                        coverUrl.set(orEmpty(thumbUrl(t.coverUrl, "512")));
                        durationMs.set(t.durationMs);
                    });
                    updateCover(t, expectedIndex, expectedCoverRevision);
                    loadNeteaseLyrics(t, expectedIndex);
                    Logger.info("play netease: {} — {}", t.title, playUrl);
                    playBackend(playUrl, resumeMs);
                    playingIntent = true;
                    post(() -> playing.set(true));
                    notifyPlayback();
                    // Populate the disk cache so later plays are served locally.
                    cacheAudioAsync(t);
                });
            } catch (Throwable e) {
                Logger.warn("netease resolve failed for {}: {}", songId, e.getMessage());
                onMain(() -> skipUnplayable(expectedIndex, "解析失败"));
            }
        });
    }

    private void resolveAndPlayPlugin(Track track, int expectedIndex, long resumeMs,
                                      long expectedCoverRevision) {
        final MediaId id;
        try {
            id = MediaId.parse(track.canonicalId());
        } catch (IllegalArgumentException error) {
            skipUnplayable(expectedIndex, "插件歌曲标识无效");
            return;
        }
        if (!pluginHasCapability(id.provider(), ProviderCapability.RESOLVE_STREAM)) {
            skipUnplayable(expectedIndex, "插件不支持音频解析");
            return;
        }
        pluginProviders.resolveStream(id, playLevel).whenComplete((stream, error) -> onMain(() -> {
            if (playIndex != expectedIndex) return;
            if (error != null || stream == null || stream.url == null || stream.url.isEmpty()) {
                Logger.warn("plugin {} stream resolve failed: {}", id.provider(),
                        error != null ? safeMessage(error) : "empty URL");
                skipUnplayable(expectedIndex, "插件音频解析失败");
                return;
            }
            track.streamUrl = stream.url;
            track.streamHeaders.clear();
            track.streamHeaders.putAll(stream.headers);
            track.streamExpiresAtMs = stream.expiresAtMs;
            track.renditionId = stream.renditionId;
            track.trial = stream.trial;
            track.streamCacheable = stream.cacheable;
            post(() -> {
                title.set(orEmpty(track.title));
                artist.set(orEmpty(track.artist));
                album.set(orEmpty(track.album));
                coverUrl.set(orEmpty(track.coverUrl));
                durationMs.set(track.durationMs);
                if (stream.trial) showToast("当前歌曲仅可试听");
            });
            updateCover(track, expectedIndex, expectedCoverRevision);
            loadPluginLyrics(track, expectedIndex);
            Logger.info("play plugin {}: {}", id.provider(), track.title);
            playBackend(stream.url, stream.headers, resumeMs);
            playingIntent = true;
            post(() -> playing.set(true));
            notifyPlayback();
            cachePluginAudioAsync(track, false, null);
        }));
    }

    private void cachePluginAudioAsync(Track track, boolean active, Runnable completion) {
        String mediaId = track != null ? track.canonicalId() : "";
        if (mediaId.isEmpty() || track.streamUrl == null || track.streamUrl.isEmpty()
                || track.trial || !track.streamCacheable || diskCache.hasAudio(mediaId)) {
            if (completion != null) post(completion);
            return;
        }
        final MediaId id;
        try { id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.SONG); }
        catch (IllegalArgumentException error) { if (completion != null) post(completion); return; }
        mediaMetaIndex.upsert(track);
        String path = diskCache.audioPath(mediaId);
        pluginHostApi.downloadReturnedFile(id.provider(), track.streamUrl,
                        track.streamHeaders, java.nio.file.Paths.get(path),
                        1024L * 1024L * 1024L, 120_000)
                .whenComplete((success, error) -> {
                    if (error == null && Boolean.TRUE.equals(success)) {
                        if (active) diskCache.markActivelyCached(mediaId);
                        diskCache.finishExternalWrite();
                        mediaMetaIndex.save();
                    } else if (error != null) {
                        Logger.warn("plugin audio cache failed for {}: {}", mediaId, safeMessage(error));
                    }
                    if (completion != null) post(completion);
                });
    }

    public void cacheMediaSong(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return;
        if (diskCache.hasAudio(mediaId)) { showToast("这首歌已缓存"); return; }
        Song song = findPluginSong(mediaId);
        if (song == null) { showToast("无法缓存：歌曲信息已失效"); return; }
        Track track = toTrackPlugin(song);
        final MediaId id;
        try { id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.SONG); }
        catch (IllegalArgumentException error) { showToast("无效的歌曲标识"); return; }
        showToast("已开始缓存");
        pluginProviders.resolveStream(id, playLevel).whenComplete((stream, error) -> {
            if (error != null || stream == null || stream.trial || !stream.cacheable) {
                post(() -> showToast("无法缓存：" + safeMessage(error)));
                return;
            }
            track.streamUrl = stream.url;
            track.streamHeaders.putAll(stream.headers);
            track.streamExpiresAtMs = stream.expiresAtMs;
            track.streamCacheable = stream.cacheable;
            cachePluginAudioAsync(track, true, () -> showToast(
                    diskCache.hasAudio(mediaId) ? "缓存完成" : "缓存失败"));
        });
    }

    public void removeMediaCache(String mediaId) {
        boolean removed = diskCache.deleteAudio(mediaId);
        if (removed) refreshCachedSongs();
        showToast(removed ? "已删除缓存" : "删除失败");
    }

    private boolean pluginHasCapability(String provider, ProviderCapability capability) {
        if (provider == null || provider.isEmpty()) return false;
        for (PluginManifest manifest : pluginManager.enabledProviders()) {
            if (provider.equals(manifest.id)) return manifest.capabilitySet().contains(capability);
        }
        return false;
    }

    private boolean providerForMediaHas(String mediaId, ProviderCapability capability) {
        try { return pluginHasCapability(MediaId.parse(mediaId).provider(), capability); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private static String providerOf(Track track) {
        try { return MediaId.parse(track.canonicalId()).provider(); }
        catch (IllegalArgumentException ignored) { return ""; }
    }

    private static boolean hasFreshPluginStream(Track track) {
        return track.streamUrl != null && !track.streamUrl.isEmpty()
                && (track.streamExpiresAtMs <= 0L
                || track.streamExpiresAtMs > System.currentTimeMillis() + 5_000L);
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
        t.canonicalId();
        t.title = s.name;
        t.artist = s.artist;
        t.artistId = s.artistId;
        t.artistIdsCsv = s.artistIdsCsv;
        t.artistNamesCsv = s.artistNamesCsv;
        t.album = s.album;
        t.coverUrl = s.coverUrl;
        t.coverThumbPath = s.coverThumbPath != null ? s.coverThumbPath : NeteaseClient.thumbUrl(s.coverUrl);
        t.durationMs = s.durationMs;
        return t;
    }

    private static Track toTrackPlugin(Song song) {
        Track track = new Track();
        track.applyCanonicalId(song.id);
        // A provider id named "netease" is still an external plugin. Never let
        // the legacy compatibility mapping route an installed plugin's tracks
        // through QPlayer's old built-in client.
        track.source = Track.Source.PLUGIN;
        track.title = song.title;
        track.artist = joinArtistNames(song);
        track.artistMediaId = song.artistMediaId;
        track.artistIdsCsv = song.artistIdsCsv;
        track.artistNamesCsv = song.artistNamesCsv;
        track.album = song.album != null ? song.album.name : "";
        track.coverUrl = song.artworkUrl;
        track.coverThumbPath = song.artworkUrl;
        track.durationMs = song.durationMs;
        track.trial = song.trial;
        return track;
    }

    private static String joinArtistNames(Song song) {
        if (song == null || song.artists == null || song.artists.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (dev.t1m3.qplayer.media.MediaRef artist : song.artists) {
            if (artist == null || artist.name == null || artist.name.isEmpty()) continue;
            if (result.length() > 0) result.append(" / ");
            result.append(artist.name);
        }
        return result.toString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // --- Search history ---------------------------------------------------

    public void addSearchHistory(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        String kw = keyword.trim();
        synchronized (historyList) {
            historyList.remove(kw);
            historyList.add(0, kw);
            if (historyList.size() > HISTORY_MAX) historyList.remove(historyList.size() - 1);
            List<String> snap = new ArrayList<>(historyList);
            post(() -> searchHistory.set(snap));
        }
        worker.submit(this::saveSearchHistory);
    }

    public void removeSearchHistory(int i) {
        synchronized (historyList) {
            if (i < 0 || i >= historyList.size()) return;
            historyList.remove(i);
            List<String> snap = new ArrayList<>(historyList);
            post(() -> searchHistory.set(snap));
        }
        worker.submit(this::saveSearchHistory);
    }

    public void clearSearchHistory() {
        synchronized (historyList) {
            historyList.clear();
            post(() -> searchHistory.set(Collections.<String>emptyList()));
        }
        worker.submit(this::saveSearchHistory);
    }

    private void saveSearchHistory() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonArray items = new JsonArray();
            synchronized (historyList) {
                for (String s : historyList) items.add(s);
            }
            root.add("items", items);
            StorageFiles.writeUtf8Atomic(AppDirs.stateFile("search-history.json"), root.toString());
        } catch (Throwable e) {
            Logger.warn("saveSearchHistory failed: {}", e.getMessage());
        }
    }

    private void loadSearchHistory() {
        try {
            java.nio.file.Path file = AppDirs.stateFile("search-history.json");
            java.nio.file.Path legacy = AppDirs.legacyFile("search_history.txt");
            List<String> loaded = new ArrayList<>();
            boolean convertedLegacy = false;
            if (java.nio.file.Files.isRegularFile(file)) {
                JsonObject root = new JsonParser().parse(StorageFiles.readUtf8(file)).getAsJsonObject();
                JsonArray items = root.has("items") && root.get("items").isJsonArray()
                        ? root.getAsJsonArray("items") : new JsonArray();
                for (JsonElement item : items) {
                    if (!item.isJsonPrimitive()) continue;
                    String value = item.getAsString().trim();
                    if (!value.isEmpty() && !loaded.contains(value)) loaded.add(value);
                    if (loaded.size() >= HISTORY_MAX) break;
                }
            } else if (java.nio.file.Files.isRegularFile(legacy)) {
                String content = StorageFiles.readUtf8(legacy);
                for (String line : content.split("\\R")) {
                    String value = line.trim();
                    if (!value.isEmpty() && !loaded.contains(value)) loaded.add(value);
                    if (loaded.size() >= HISTORY_MAX) break;
                }
                convertedLegacy = true;
            } else {
                return;
            }
            synchronized (historyList) {
                historyList.clear();
                historyList.addAll(loaded);
                List<String> snap = new ArrayList<>(historyList);
                post(() -> searchHistory.set(snap));
            }
            if (convertedLegacy) {
                saveSearchHistory();
                if (java.nio.file.Files.isRegularFile(file)) {
                    java.nio.file.Files.deleteIfExists(legacy);
                }
            }
        } catch (Throwable e) {
            Logger.warn("loadSearchHistory failed: {}", e.getMessage());
        }
    }

    // --- Queue persistence ------------------------------------------------

    private synchronized void saveQueue() {
        try {
            java.nio.file.Path target = AppDirs.stateFile("queue.json");
            // Live backend position, not the positionMs Property: the Property only
            // refreshes from pump() (render thread, paused while backgrounded/during
            // shutdown), so it can be stale exactly when this matters most — the
            // final save on app exit.
            long pos = playIndex >= 0 ? Math.max(0L, backend.position()) : 0L;
            StringBuilder sb = new StringBuilder();
            sb.append("{\"schemaVersion\":2,\"playIndex\":").append(playIndex)
              .append(",\"positionMs\":").append(pos)
              .append(",\"playMode\":").append(playMode.peek())
              .append(",\"tracks\":[");
            List<Track> snap = new ArrayList<>(queue);
            for (int i = 0; i < snap.size(); i++) {
                Track t = snap.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"source\":\"").append(t.source).append('"');
                String mediaId = t.canonicalId();
                if (!mediaId.isEmpty()) sb.append(",\"mediaId\":").append(jsonStr(mediaId));
                if (t.neteaseId != 0) sb.append(",\"neteaseId\":").append(t.neteaseId);
                if (t.customId != null) sb.append(",\"customId\":").append(jsonStr(t.customId));
                sb.append(",\"title\":").append(jsonStr(t.title));
                sb.append(",\"artist\":").append(jsonStr(t.artist));
                if (t.artistId != 0) sb.append(",\"artistId\":").append(t.artistId);
                if (t.artistMediaId != null && !t.artistMediaId.isEmpty())
                    sb.append(",\"artistMediaId\":").append(jsonStr(t.artistMediaId));
                if (t.artistIdsCsv != null && !t.artistIdsCsv.isEmpty()) {
                    sb.append(",\"artistIdsCsv\":").append(jsonStr(t.artistIdsCsv));
                    sb.append(",\"artistNamesCsv\":").append(jsonStr(t.artistNamesCsv));
                }
                sb.append(",\"album\":").append(jsonStr(t.album));
                sb.append(",\"coverUrl\":").append(jsonStr(t.coverUrl));
                sb.append(",\"durationMs\":").append(t.durationMs);
                if (t.filePath != null) sb.append(",\"filePath\":").append(jsonStr(t.filePath));
                if (t.contentUri != null) sb.append(",\"contentUri\":").append(jsonStr(t.contentUri));
                // Persist the local cover path: without it a restored LOCAL track
                // has null coverLocalPath/coverThumbPath, so updateCover() bails
                // and the now-playing card / SMTC keeps no artwork across restarts.
                // Prefer the full-size cover, fall back to the row thumbnail.
                if (t.coverLocalPath != null) {
                    sb.append(",\"coverLocalPath\":").append(jsonStr(t.coverLocalPath));
                } else if (t.coverThumbPath != null && !t.coverThumbPath.startsWith("http")) {
                    sb.append(",\"coverLocalPath\":").append(jsonStr(t.coverThumbPath));
                }
                sb.append('}');
            }
            sb.append("]}");
            StorageFiles.writeUtf8Atomic(target, sb.toString());
        } catch (Throwable e) {
            Logger.warn("saveQueue failed: {}", e.getMessage());
        }
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private void loadQueue() {
        try {
            java.nio.file.Path file = AppDirs.stateFile("queue.json");
            if (!java.nio.file.Files.exists(file)) return;
            String text = StorageFiles.readUtf8(file);
            com.google.gson.JsonObject root = new com.google.gson.JsonParser().parse(text).getAsJsonObject();
            int savedIdx = root.has("playIndex") ? root.get("playIndex").getAsInt() : 0;
            long savedPos = root.has("positionMs") ? root.get("positionMs").getAsLong() : 0L;
            int savedMode = root.has("playMode") ? root.get("playMode").getAsInt() : 0;
            com.google.gson.JsonArray arr = root.has("tracks") ? root.getAsJsonArray("tracks") : new com.google.gson.JsonArray();
            List<Track> loaded = new ArrayList<>();
            for (com.google.gson.JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                com.google.gson.JsonObject o = el.getAsJsonObject();
                Track t = new Track();
                String src = o.has("source") ? o.get("source").getAsString() : "NETEASE";
                t.source = "LOCAL".equals(src) ? Track.Source.LOCAL
                        : "CUSTOM_API".equals(src) ? Track.Source.CUSTOM_API
                        : "PLUGIN".equals(src) ? Track.Source.PLUGIN
                        : Track.Source.NETEASE;
                t.neteaseId = o.has("neteaseId") ? o.get("neteaseId").getAsLong() : 0;
                t.customId  = o.has("customId")  && !o.get("customId").isJsonNull()  ? o.get("customId").getAsString()  : null;
                if (o.has("mediaId") && !o.get("mediaId").isJsonNull()) {
                    t.applyCanonicalId(o.get("mediaId").getAsString());
                } else {
                    t.canonicalId();
                }
                t.title     = o.has("title")     && !o.get("title").isJsonNull()     ? o.get("title").getAsString()     : "";
                t.artist    = o.has("artist")    && !o.get("artist").isJsonNull()    ? o.get("artist").getAsString()    : "";
                t.artistId  = o.has("artistId") ? o.get("artistId").getAsLong() : 0L;
                t.artistMediaId = o.has("artistMediaId") && !o.get("artistMediaId").isJsonNull()
                        ? o.get("artistMediaId").getAsString() : "";
                t.artistIdsCsv = o.has("artistIdsCsv") && !o.get("artistIdsCsv").isJsonNull()
                        ? o.get("artistIdsCsv").getAsString() : "";
                t.artistNamesCsv = o.has("artistNamesCsv") && !o.get("artistNamesCsv").isJsonNull()
                        ? o.get("artistNamesCsv").getAsString() : "";
                t.album     = o.has("album")     && !o.get("album").isJsonNull()     ? o.get("album").getAsString()     : "";
                t.coverUrl  = o.has("coverUrl")  && !o.get("coverUrl").isJsonNull()  ? o.get("coverUrl").getAsString()  : "";
                t.durationMs = o.has("durationMs") ? o.get("durationMs").getAsLong() : 0;
                if (t.source == Track.Source.LOCAL) {
                    t.filePath   = o.has("filePath")   && !o.get("filePath").isJsonNull()   ? o.get("filePath").getAsString()   : null;
                    t.contentUri = o.has("contentUri") && !o.get("contentUri").isJsonNull() ? o.get("contentUri").getAsString() : null;
                    // Restore the persisted local cover path (saved as coverLocalPath,
                    // the full-size cover preferred over the row thumbnail) so updateCover
                    // has a file to read and the now-playing card / SMTC keeps its art.
                    t.coverLocalPath = o.has("coverLocalPath") && !o.get("coverLocalPath").isJsonNull()
                            ? o.get("coverLocalPath").getAsString() : null;
                } else if (t.source == Track.Source.CUSTOM_API || t.source == Track.Source.PLUGIN) {
                    // No netease-CDN thumbnail convention for a custom source — the
                    // Remote cover URL doubles as its own thumbnail for legacy rows.
                    t.coverThumbPath = t.coverUrl;
                } else if (t.coverUrl != null && !t.coverUrl.isEmpty()) {
                    // NETEASE row art is a CDN thumbnail URL derived from coverUrl; the
                    // queue JSON only persists coverUrl, so rebuild it here (loadCustom-
                    // Playlist does the same) — else restored queue rows show no cover.
                    t.coverThumbPath = NeteaseClient.thumbUrl(t.coverUrl);
                }
                t.canonicalId();
                loaded.add(t);
            }
            if (!loaded.isEmpty()) {
                queue.addAll(loaded);
                final List<Track> snap = new ArrayList<>(loaded);
                int idx = Math.max(0, Math.min(savedIdx, loaded.size() - 1));
                playIndex = idx;
                long restoredCoverRevision = coverRevision.incrementAndGet();
                needsReplay = true;
                long clampedPos = Math.max(0L, savedPos);
                stoppedLyricPositionMs = clampedPos;
                pendingResumeIndex = idx;
                pendingResumeMs = clampedPos;
                final int finalIdx = idx;
                final Track cur = loaded.get(idx);
                post(() -> {
                    applyTrackLyricOffset(cur);
                    queueTracks.set(snap);
                    index.set(finalIdx);
                    currentFilePath.set(cur.source == Track.Source.LOCAL && cur.filePath != null ? cur.filePath : "");
                    title.set(cur.title != null ? cur.title : "");
                    artist.set(cur.artist != null ? cur.artist : "");
                    publishPlayingArtist(cur);
                    album.set(cur.album != null ? cur.album : "");
                    coverUrl.set(trackCoverUrl(cur, "512"));
                    durationMs.set(cur.durationMs);
                    // So the progress bar shows the resume point before playback
                    // actually starts (toggle() only plays on the user's first tap).
                    positionMs.set(clampedPos);
                    // The restored track is already the current track even though its
                    // audio source is not opened until the first Play press. Publish
                    // the same favorite-button state as playAt() now, rather than
                    // leaving the heart disabled for the whole pre-playback session.
                    currentLiked.set(cur.neteaseId != 0 && likedSet.contains(cur.neteaseId));
                    currentLikeable.set(cur.neteaseId != 0);
                    playMode.set(Math.max(0, Math.min(2, savedMode)));
                });
                // Load the full cover art + lyrics now (both cache-first internally)
                // instead of waiting for the user to press play — playAt() normally
                // does this, but playAt() itself isn't called until then.
                updateCover(cur, idx, restoredCoverRevision);
                if (cur.source == Track.Source.LOCAL) {
                    loadLocalLyrics(cur);
                } else if (cur.source == Track.Source.NETEASE) {
                    loadNeteaseLyrics(cur, idx);
                } else if (cur.source == Track.Source.PLUGIN) {
                    loadPluginLyrics(cur, idx);
                }
            }
        } catch (Throwable e) {
            Logger.warn("loadQueue failed: {}", e.getMessage());
        }
    }

    private void applyTrackLyricOffset(Track track) {
        String key = lyricOffsetKey(track);
        Integer saved = key != null ? lyricOffsets.get(key) : null;
        int value = saved != null ? saved : 0;
        lyricOffsetMs.set(value);
        LyricConfig.instance.offsetMs.setValue(value);
    }

    private void setCurrentLyricOffset(Track track, int value) {
        String key = lyricOffsetKey(track);
        if (key == null) return;
        if (value == 0) lyricOffsets.remove(key);
        else lyricOffsets.put(key, value);
        lyricOffsetMs.set(value);
        LyricConfig.instance.offsetMs.setValue(value);
        Long pos = positionMs.peek();
        updateLyricIndex((pos != null ? pos : 0L) - value);
        worker.submit(this::saveLyricOffsets);
    }

    private static String lyricOffsetKey(Track track) {
        if (track == null) return null;
        String canonical = track.canonicalId();
        return canonical.isEmpty() ? null : canonical;
    }

    private void loadLyricOffsets() {
        try {
            java.nio.file.Path file = AppDirs.stateFile("lyric-offsets.json");
            if (!java.nio.file.Files.isRegularFile(file)) return;
            String json = StorageFiles.readUtf8(file);
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            synchronized (lyricOffsets) {
                lyricOffsets.clear();
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    int value = Math.max(-5000, Math.min(5000, entry.getValue().getAsInt()));
                    if (value != 0) lyricOffsets.put(canonicalLyricOffsetKey(entry.getKey()), value);
                }
            }
        } catch (Throwable e) {
            Logger.warn("load lyric offsets failed: {}", e.getMessage());
        }
    }

    private static String canonicalLyricOffsetKey(String key) {
        if (key == null || key.isEmpty()) return "";
        try {
            return dev.t1m3.qplayer.media.MediaId.parse(key).toString();
        } catch (IllegalArgumentException ignored) {
        }
        if (key.startsWith("netease:")) {
            return dev.t1m3.qplayer.media.MediaId.of("netease",
                    dev.t1m3.qplayer.media.MediaKind.SONG,
                    key.substring("netease:".length())).toString();
        }
        if (key.startsWith("custom:")) {
            return dev.t1m3.qplayer.media.MediaId.of("legacy-custom",
                    dev.t1m3.qplayer.media.MediaKind.SONG,
                    key.substring("custom:".length())).toString();
        }
        if (key.startsWith("local:")) {
            Track legacy = new Track();
            legacy.source = Track.Source.LOCAL;
            legacy.filePath = key.substring("local:".length());
            return legacy.canonicalId();
        }
        return key;
    }

    private void saveLyricOffsets() {
        try {
            JsonObject root = new JsonObject();
            synchronized (lyricOffsets) {
                for (Map.Entry<String, Integer> entry : lyricOffsets.entrySet())
                    root.addProperty(entry.getKey(), entry.getValue());
            }
            StorageFiles.writeUtf8Atomic(
                    AppDirs.stateFile("lyric-offsets.json"), root.toString());
        } catch (Throwable e) {
            Logger.warn("save lyric offsets failed: {}", e.getMessage());
        }
    }

    // --- Netease discovery ------------------------------------------------

    /** Load hot search keywords from Netease API. Called once on search page open. */
    public void loadHotSearches() {
        PluginManifest provider = primaryProviderWith(ProviderCapability.HOT_SEARCH);
        if (provider != null) {
            pluginProviders.hotSearch(provider.id).whenComplete((values, error) -> post(() -> {
                if (!provider.id.equals(pluginRegistry.primaryProvider())) return;
                if (error != null) {
                    Logger.warn("plugin {} hot search failed: {}", provider.id, safeMessage(error));
                    return;
                }
                hotSearches.set(values);
            }));
            return;
        }
        if (onlineSourcesArePluginOnly()) {
            hotSearches.set(Collections.<String>emptyList());
            return;
        }
        worker.submit(() -> {
            try {
                List<String> hot = netease.searchHot();
                post(() -> hotSearches.set(hot));
            } catch (Throwable e) {
                Logger.warn("loadHotSearches failed: {}", e.toString());
            }
        });
    }

    /** Search and publish to {@link #searchResults}. Results are cached for
     *  {@value #SEARCH_CACHE_TTL_MS} ms; a cache hit returns immediately without
     *  a network round-trip. */
    public void search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        final String query = keyword.trim();
        final String key = query.toLowerCase(Locale.ROOT);
        currentSearchKey = key;
        currentSearchQuery = query;
        boolean pluginSearchStarted = searchPlugins(query, key);
        if (onlineSourcesArePluginOnly()) {
            searchResults.set(Collections.<NeteaseSong>emptyList());
            searchPageInFlight = false;
            if (!pluginSearchStarted) searchLoading.set(false);
            searchHasMore.set(false);
            rebuildSearchRows();
            return;
        }
        // Fast path: check cache on the calling (render) thread.
        CacheEntry entry = searchCache.get(key);
        if (entry != null && !entry.isExpired()) {
            searchResults.set(entry.songs);
            resultCount.set(entry.songs.size());
            searchNextOffset = entry.nextOffset;
            searchPageInFlight = false;
            searchLoading.set(false);
            searchHasMore.set(entry.hasMore);
            rebuildSearchRows();
            Logger.info("search cache hit: {}", key);
            return;
        }
        searchPageInFlight = true;
        searchLoading.set(true);
        searchHasMore.set(false);
        searchWorker.submit(() -> {
            try {
                if (!key.equals(currentSearchKey)) return;
                // Double-check: another search for the same keyword may have
                // completed while we were waiting for the worker slot.
                CacheEntry existing = searchCache.get(key);
                if (existing != null && !existing.isExpired()) {
                    if (!key.equals(currentSearchKey)) return;
                    post(() -> {
                        if (!key.equals(currentSearchKey)) return;
                        searchResults.set(existing.songs);
                        resultCount.set(existing.songs.size());
                        searchNextOffset = existing.nextOffset;
                        searchPageInFlight = false;
                        searchLoading.set(false);
                        searchHasMore.set(existing.hasMore);
                        rebuildSearchRows();
                    });
                    return;
                }
                NeteaseClient.SongSearchPage page =
                        netease.searchSongsPage(query, SEARCH_PAGE_SIZE, 0);
                if (!key.equals(currentSearchKey)) return;
                // Refresh the thumbnail URL for any rows whose cover metadata had
                // to be completed through the song-detail endpoint.
                List<NeteaseSong> r = page.songs;
                fillMissingCovers(r);
                buildSongThumbs(r, "128");
                int nextOffset = page.consumed;
                boolean hasMore = page.hasMore(0, SEARCH_PAGE_SIZE);
                searchCache.put(key, new CacheEntry(r, nextOffset, hasMore));
                for (NeteaseSong s : r) mediaMetaIndex.upsert(toTrack(s));
                mediaMetaIndex.save();
                post(() -> {
                    if (!key.equals(currentSearchKey)) return;
                    searchResults.set(r);
                    resultCount.set(r.size());
                    searchNextOffset = nextOffset;
                    searchPageInFlight = false;
                    searchLoading.set(false);
                    searchHasMore.set(hasMore);
                    rebuildSearchRows();
                });
            } catch (Throwable e) {
                Logger.warn("search failed: {}", e.getMessage());
                // Offline (or the API's just down): fall back to songs this process
                // has actually seen before (search results, anything ever played) —
                // DiskCache alone only knows bare ids, no title/artist to show, so
                // this is the only source offline search has to work with.
                List<NeteaseSong> offline = Collections.emptyList();
                if (!key.equals(currentSearchKey)) return;
                post(() -> {
                    if (!key.equals(currentSearchKey)) return;
                    searchResults.set(offline);
                    resultCount.set(offline.size());
                    searchNextOffset = 0;
                    searchPageInFlight = false;
                    searchLoading.set(false);
                    searchHasMore.set(false);
                    rebuildSearchRows();
                    // Give feedback either way -- an empty offline list used to
                    // leave the page blank with zero explanation (looked stuck,
                    // not "no matches"); this covers both "no network + found
                    // some cached matches" and "no network + nothing matched".
                    showToast(offline.isEmpty()
                        ? "当前无网络，且没有找到本地缓存结果"
                        : "当前无网络，显示本地缓存结果");
                });
            }
        });
    }

    /** Album-mode counterpart to {@link #search}: cloudsearch type 10, no cache/
     *  offline-fallback/pagination layer -- SearchPage's album filter is a much
     *  smaller surface than song search, so it stays a plain single-shot fetch. */
    public void searchAlbums(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        final String query = keyword.trim();
        final String key = query.toLowerCase(Locale.ROOT);
        currentSearchKey = key;
        currentSearchQuery = query;
        searchLoading.set(true);
        PluginManifest provider = primaryProviderWith(ProviderCapability.SEARCH_ALBUMS);
        if (provider != null) {
            sourceSearchAlbumResults.set(Collections.<Album>emptyList());
            pluginProviders.searchAlbums(provider.id, query, "", SEARCH_PAGE_SIZE)
                    .whenComplete((page, error) -> post(() -> {
                        if (!key.equals(currentSearchKey)
                                || !provider.id.equals(pluginRegistry.primaryProvider())) return;
                        searchLoading.set(false);
                        if (error != null) {
                            Logger.warn("plugin {} album search failed: {}", provider.id,
                                    safeMessage(error));
                            showToast("专辑搜索失败，请检查网络或插件状态");
                            return;
                        }
                        List<Album> results = page != null ? page.items
                                : Collections.<Album>emptyList();
                        sourceSearchAlbumResults.set(results);
                        resultCount.set(results.size());
                    }));
            return;
        }
        if (onlineSourcesArePluginOnly()) {
            searchLoading.set(false);
            sourceSearchAlbumResults.set(Collections.<Album>emptyList());
            return;
        }
        searchWorker.submit(() -> {
            try {
                List<NeteaseAlbum> r = netease.searchAlbums(query, SEARCH_PAGE_SIZE);
                applyAlbumCoverSize(r);
                if (!key.equals(currentSearchKey)) return;
                post(() -> {
                    if (!key.equals(currentSearchKey)) return;
                    searchAlbumResults.set(r);
                    resultCount.set(r.size());
                    searchLoading.set(false);
                    rebuildSearchRows();
                });
            } catch (Throwable e) {
                Logger.warn("album search failed: {}", e.getMessage());
                if (!key.equals(currentSearchKey)) return;
                post(() -> {
                    if (!key.equals(currentSearchKey)) return;
                    searchLoading.set(false);
                    showToast("专辑搜索失败，请检查网络");
                });
            }
        });
    }

    /** Artist-mode counterpart to {@link #search}; see {@link #searchAlbums}. */
    public void searchArtists(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        final String query = keyword.trim();
        final String key = query.toLowerCase(Locale.ROOT);
        currentSearchKey = key;
        currentSearchQuery = query;
        searchLoading.set(true);
        PluginManifest provider = primaryProviderWith(ProviderCapability.SEARCH_ARTISTS);
        if (provider != null) {
            sourceSearchArtistResults.set(Collections.<Artist>emptyList());
            pluginProviders.searchArtists(provider.id, query, "", SEARCH_PAGE_SIZE)
                    .whenComplete((page, error) -> post(() -> {
                        if (!key.equals(currentSearchKey)
                                || !provider.id.equals(pluginRegistry.primaryProvider())) return;
                        searchLoading.set(false);
                        if (error != null) {
                            Logger.warn("plugin {} artist search failed: {}", provider.id,
                                    safeMessage(error));
                            showToast("歌手搜索失败，请检查网络或插件状态");
                            return;
                        }
                        List<Artist> results = page != null ? page.items
                                : Collections.<Artist>emptyList();
                        sourceSearchArtistResults.set(results);
                        resultCount.set(results.size());
                    }));
            return;
        }
        if (onlineSourcesArePluginOnly()) {
            searchLoading.set(false);
            sourceSearchArtistResults.set(Collections.<Artist>emptyList());
            return;
        }
        searchWorker.submit(() -> {
            try {
                List<NeteaseArtist> r = netease.searchArtists(query, SEARCH_PAGE_SIZE);
                if (!key.equals(currentSearchKey)) return;
                post(() -> {
                    if (!key.equals(currentSearchKey)) return;
                    searchArtistResults.set(r);
                    resultCount.set(r.size());
                    searchLoading.set(false);
                    rebuildSearchRows();
                });
            } catch (Throwable e) {
                Logger.warn("artist search failed: {}", e.getMessage());
                if (!key.equals(currentSearchKey)) return;
                post(() -> {
                    if (!key.equals(currentSearchKey)) return;
                    searchLoading.set(false);
                    showToast("歌手搜索失败，请检查网络");
                });
            }
        });
    }

    /** Fetch the next cloudsearch page when SearchPage's virtual list nears its end. */
    public void loadMoreSearch() {
        if (!pluginSearchByProvider.isEmpty()) {
            loadMorePluginSearch();
            if (onlineSourcesArePluginOnly()) return;
        }
        if (onlineSourcesArePluginOnly()) return;
        if (searchPageInFlight || !Boolean.TRUE.equals(searchHasMore.peek())) return;
        final String query = currentSearchQuery;
        final String key = currentSearchKey;
        final int offset = searchNextOffset;
        if (query.isEmpty() || key.isEmpty()) return;

        List<NeteaseSong> current = searchResults.peek();
        final List<NeteaseSong> base = current == null
                ? new ArrayList<NeteaseSong>() : new ArrayList<>(current);
        searchPageInFlight = true;
        searchLoading.set(true);
        searchWorker.submit(() -> {
            try {
                if (!key.equals(currentSearchKey) || offset != searchNextOffset) return;
                NeteaseClient.SongSearchPage page =
                        netease.searchSongsPage(query, SEARCH_PAGE_SIZE, offset);
                if (!key.equals(currentSearchKey) || offset != searchNextOffset) return;

                List<NeteaseSong> additions = page.songs;
                fillMissingCovers(additions);
                buildSongThumbs(additions, "128");
                List<NeteaseSong> merged = appendUniqueSongs(base, additions);
                int nextOffset = offset + page.consumed;
                boolean hasMore = page.hasMore(offset, SEARCH_PAGE_SIZE)
                        && page.consumed > 0;
                searchCache.put(key, new CacheEntry(merged, nextOffset, hasMore));
                for (NeteaseSong song : additions) mediaMetaIndex.upsert(toTrack(song));
                mediaMetaIndex.save();
                post(() -> {
                    if (!key.equals(currentSearchKey) || offset != searchNextOffset) return;
                    searchResults.set(merged);
                    resultCount.set(merged.size());
                    searchNextOffset = nextOffset;
                    searchPageInFlight = false;
                    searchLoading.set(false);
                    searchHasMore.set(hasMore);
                    rebuildSearchRows();
                });
            } catch (Throwable e) {
                Logger.warn("load more search failed at offset {}: {}", offset, e.getMessage());
                post(() -> {
                    if (!key.equals(currentSearchKey) || offset != searchNextOffset) return;
                    searchPageInFlight = false;
                    searchLoading.set(false);
                    // Keep hasMore=true: scrolling away and back can retry.
                });
            }
        });
    }

    /** Fetch one additional cursor page from every provider that still has one.
     * Buckets remain in manifest order and each provider advances independently,
     * so a slow or exhausted source neither reorders nor truncates the others. */
    private void loadMorePluginSearch() {
        if (pluginSearchPageInFlight || pluginSearchByProvider.isEmpty()) return;
        final String key = currentSearchKey;
        final String query = currentSearchQuery;
        final long generation = pluginSearchGeneration.get();
        final Map<String, String> cursors = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : pluginSearchCursors.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                cursors.put(entry.getKey(), entry.getValue());
            }
        }
        if (query.isEmpty() || key.isEmpty() || cursors.isEmpty()) {
            searchHasMore.set(false);
            return;
        }
        pluginSearchPageInFlight = true;
        searchLoading.set(true);
        AtomicInteger pending = new AtomicInteger(cursors.size());
        for (Map.Entry<String, String> request : cursors.entrySet()) {
            final String provider = request.getKey();
            final String requestedCursor = request.getValue();
            pluginProviders.searchSongs(provider, query, requestedCursor, SEARCH_PAGE_SIZE)
                    .whenComplete((page, error) -> post(() -> {
                        if (generation != pluginSearchGeneration.get()
                                || !key.equals(currentSearchKey)) return;
                        // A second request cannot start while this batch is in flight,
                        // but still reject a response for a cursor the provider no longer owns.
                        if (!requestedCursor.equals(pluginSearchCursors.get(provider))) return;
                        if (error != null) {
                            Logger.warn("plugin {} search page failed: {}", provider,
                                    safeMessage(error));
                            // Keep the cursor so scrolling away and back can retry.
                        } else {
                            List<Song> additions = page != null && page.items != null
                                    ? page.items : Collections.<Song>emptyList();
                            List<Song> base = pluginSearchByProvider.get(provider);
                            pluginSearchByProvider.put(provider,
                                    appendUniquePluginSongs(base, additions));
                            pluginSearchCursors.put(provider,
                                    page != null && page.nextCursor != null
                                            ? page.nextCursor : "");
                            rebuildSearchRows();
                        }
                        if (pending.decrementAndGet() == 0) {
                            pluginSearchPageInFlight = false;
                            searchLoading.set(false);
                            searchHasMore.set(hasMorePluginSearch());
                        }
                    }));
        }
    }

    private static List<Song> appendUniquePluginSongs(List<Song> base, List<Song> additions) {
        List<Song> existing = base != null ? base : Collections.<Song>emptyList();
        List<Song> incoming = additions != null ? additions : Collections.<Song>emptyList();
        List<Song> merged = new ArrayList<>(existing.size() + incoming.size());
        Set<String> ids = new HashSet<>();
        for (Song song : existing) {
            if (song == null || song.id == null || !ids.add(song.id)) continue;
            merged.add(song);
        }
        for (Song song : incoming) {
            if (song == null || song.id == null || !ids.add(song.id)) continue;
            merged.add(song);
        }
        return Collections.unmodifiableList(merged);
    }

    private boolean hasMorePluginSearch() {
        for (String cursor : pluginSearchCursors.values()) {
            if (cursor != null && !cursor.isEmpty()) return true;
        }
        return false;
    }

    private static List<NeteaseSong> appendUniqueSongs(List<NeteaseSong> base,
            List<NeteaseSong> additions) {
        List<NeteaseSong> merged = new ArrayList<>(base.size() + additions.size());
        Set<Long> ids = new HashSet<>();
        for (NeteaseSong song : base) {
            merged.add(song);
            if (song.id != 0L) ids.add(song.id);
        }
        for (NeteaseSong song : additions) {
            if (song.id != 0L && !ids.add(song.id)) continue;
            merged.add(song);
        }
        return merged;
    }

    /**
     * Invalidate visible results as soon as the input text changes, before the
     * debounce timer starts the next requests. This also advances both network
     * generation keys immediately, so an older response cannot be published in
     * the 350 ms debounce window and appear under an unrelated keyword.
     */
    public void prepareSearch(String keyword) {
        String query = keyword == null ? "" : keyword.trim();
        pluginSearchGeneration.incrementAndGet();
        pluginSearchByProvider.clear();
        pluginProviderNames.clear();
        pluginSearchCursors.clear();
        pluginSearchPageInFlight = false;
        currentSearchKey = query.toLowerCase(Locale.ROOT);
        currentSearchQuery = query;
        searchNextOffset = 0;
        searchPageInFlight = false;
        searchResults.set(Collections.<NeteaseSong>emptyList());
        localSearchResults.set(Collections.<Track>emptyList());
        searchAlbumResults.set(Collections.<NeteaseAlbum>emptyList());
        searchArtistResults.set(Collections.<NeteaseArtist>emptyList());
        sourceSearchAlbumResults.set(Collections.<Album>emptyList());
        sourceSearchArtistResults.set(Collections.<Artist>emptyList());
        resultCount.set(0);
        searchLoading.set(false);
        searchHasMore.set(false);
        rebuildSearchRows();
    }

    /** Start one isolated search per enabled provider. Each completion is merged into
     * its preallocated provider bucket, so network timing never changes row order. */
    private boolean searchPlugins(String query, String key) {
        final long generation = pluginSearchGeneration.incrementAndGet();
        List<PluginManifest> providers = new ArrayList<>();
        for (PluginManifest manifest : pluginManager.enabledProviders()) {
            if (manifest.capabilitySet().contains(ProviderCapability.SEARCH_SONGS)) {
                providers.add(manifest);
            }
        }
        String primary = pluginRegistry.primaryProvider();
        providers.sort((a, b) -> {
            if (a.id.equals(primary)) return b.id.equals(primary) ? 0 : -1;
            if (b.id.equals(primary)) return 1;
            return 0;
        });
        pluginSearchByProvider.clear();
        pluginProviderNames.clear();
        pluginSearchCursors.clear();
        pluginSearchPageInFlight = false;
        for (PluginManifest manifest : providers) {
            pluginSearchByProvider.put(manifest.id, Collections.<Song>emptyList());
            pluginProviderNames.put(manifest.id, manifest.name);
            pluginSearchCursors.put(manifest.id, "");
        }
        rebuildSearchRows();
        if (providers.isEmpty()) return false;
        searchLoading.set(true);
        searchHasMore.set(false);
        AtomicInteger pending = new AtomicInteger(providers.size());
        for (PluginManifest manifest : providers) {
            pluginProviders.searchSongs(manifest.id, query, "", SEARCH_PAGE_SIZE)
                    .whenComplete((page, error) -> post(() -> {
                        if (generation != pluginSearchGeneration.get()
                                || !key.equals(currentSearchKey)) return;
                        if (error != null) {
                            Logger.warn("plugin {} search failed: {}", manifest.id,
                                    safeMessage(error));
                            pluginSearchByProvider.put(manifest.id,
                                    cachedSongsForProvider(manifest.id, key, SEARCH_PAGE_SIZE));
                            pluginSearchCursors.put(manifest.id, "");
                            rebuildSearchRows();
                        } else {
                            List<Song> songs = page != null && page.items != null
                                    ? Collections.unmodifiableList(new ArrayList<>(page.items))
                                    : Collections.<Song>emptyList();
                            pluginSearchByProvider.put(manifest.id, songs);
                            pluginSearchCursors.put(manifest.id,
                                    page != null && page.nextCursor != null
                                            ? page.nextCursor : "");
                            rebuildSearchRows();
                        }
                        if (pending.decrementAndGet() == 0) {
                            searchLoading.set(false);
                            searchHasMore.set(hasMorePluginSearch());
                        }
                    }));
        }
        return true;
    }

    private List<Song> cachedSongsForProvider(String provider, String query, int limit) {
        List<Song> result = new ArrayList<>();
        for (Track track : mediaMetaIndex.search(query, limit)) {
            try {
                if (!provider.equals(MediaId.parse(track.canonicalId()).provider())) continue;
            } catch (IllegalArgumentException ignored) { continue; }
            Song song = new Song();
            song.id = track.canonicalId();
            song.title = orEmpty(track.title);
            song.name = song.title;
            song.artist = orEmpty(track.artist);
            if (!song.artist.isEmpty()) {
                song.artists.add(new dev.t1m3.qplayer.media.MediaRef(
                        orEmpty(track.artistMediaId), song.artist));
            }
            song.artistMediaId = orEmpty(track.artistMediaId);
            song.artistIdsCsv = orEmpty(track.artistIdsCsv);
            song.artistNamesCsv = orEmpty(track.artistNamesCsv);
            if (track.album != null && !track.album.isEmpty()) {
                song.album = new dev.t1m3.qplayer.media.MediaRef("", track.album);
            }
            song.artworkUrl = orEmpty(track.coverUrl);
            song.coverUrl = song.artworkUrl;
            song.coverThumbPath = song.artworkUrl;
            song.durationMs = track.durationMs;
            song.cachedOffline = diskCache.hasAudio(song.id);
            result.add(song);
        }
        return Collections.unmodifiableList(result);
    }

    /** Compatibility readers remain for one delayed-migration release, but the
     * core never uses them as an online source. */
    private static boolean onlineSourcesArePluginOnly() { return true; }

    /** Filter the local library by title/artist/album substring (case-insensitive)
     *  and publish to {@link #localSearchResults}. Synchronous — the library is
     *  already in memory (scanned at startup), no network round-trip to wait on. */
    public void searchLocal(String keyword) {
        String q = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            localSearchResults.set(Collections.<Track>emptyList());
            rebuildSearchRows();
            return;
        }
        List<Track> matches = new ArrayList<>();
        for (Track t : library) {
            if (containsIgnoreCase(t.title, q) || containsIgnoreCase(t.artist, q)
                    || containsIgnoreCase(t.album, q)) {
                matches.add(t);
            }
        }
        localSearchResults.set(matches);
        rebuildSearchRows();
    }

    /** Flatten online provider results and {@link #localSearchResults} into
     *  {@link #searchRows}, in stable provider order
     *  order — SearchPage.qml renders one unified list instead of three
     *  independently-scrolling ones (which fought over layout space; see
     *  SearchPage.qml). Must run on the render thread (Property write).
     *
     * <p>Only meaningful in "song" {@link #searchMode} — SearchPage.qml shows
     * album/artist results as their own card grids straight off {@link
     * #searchAlbumResults}/{@link #searchArtistResults}, bypassing this list. */
    void rebuildSearchRows() {
        List<SearchRow> rows = new ArrayList<>();
        List<NeteaseSong> ns = searchResults.peek();
        if (ns != null) {
            for (int i = 0; i < ns.size(); i++) {
                NeteaseSong s = ns.get(i);
                SearchRow row = new SearchRow();
                row.kind = "netease";
                row.kindLabel = "网易云";
                row.index = i;
                row.name = s.name;
                row.artist = s.artist;
                row.artistId = s.artistId;
                row.artistIdsCsv = s.artistIdsCsv;
                row.artistNamesCsv = s.artistNamesCsv;
                row.coverThumbPath = s.coverThumbPath;
                row.id = s.id;
                row.menuEnabled = s.id != 0;
                rows.add(row);
            }
        }
        for (Map.Entry<String, List<Song>> provider : pluginSearchByProvider.entrySet()) {
            List<Song> songs = provider.getValue();
            for (int i = 0; songs != null && i < songs.size(); i++) {
                Song song = songs.get(i);
                SearchRow row = new SearchRow();
                row.kind = "plugin";
                row.kindLabel = pluginProviderNames.getOrDefault(provider.getKey(), provider.getKey());
                row.providerId = provider.getKey();
                row.mediaId = song.id;
                row.artistMediaId = song.artistMediaId;
                row.index = i;
                row.name = song.title;
                row.artist = joinArtistNames(song);
                row.coverThumbPath = song.artworkUrl;
                // Generic context actions are added only when their corresponding
                // provider capabilities exist; playback itself is always available.
                row.menuEnabled = !song.id.isEmpty();
                rows.add(row);
            }
        }
        List<Track> ls = localSearchResults.peek();
        if (ls != null) {
            for (int i = 0; i < ls.size(); i++) {
                Track t = ls.get(i);
                SearchRow row = new SearchRow();
                row.kind = "local";
                row.kindLabel = "本地";
                row.index = i;
                row.name = t.title;
                row.artist = t.artist;
                row.coverThumbPath = t.coverThumbPath;
                row.filePath = t.filePath;
                row.menuEnabled = t.filePath != null && !t.filePath.isEmpty();
                rows.add(row);
            }
        }
        searchRows.set(rows);
        if ("song".equals(searchMode.peek())) resultCount.set(rows.size());
    }

    /** Route a click on the unified search list (SearchPage.qml) back to the
     *  right source-specific play method by {@link SearchRow#kind}. */
    public void playSearchRow(int rowIndex) {
        List<SearchRow> rows = searchRows.peek();
        if (rows == null || rowIndex < 0 || rowIndex >= rows.size()) return;
        SearchRow row = rows.get(rowIndex);
        switch (row.kind) {
            case "plugin": playPluginSearchResult(row.providerId, row.index); break;
            case "netease": playSearchResult(row.index); break;
            case "local": playLocalSearchResult(row.index); break;
            default: break;
        }
    }

    private static boolean containsIgnoreCase(String s, String needleLower) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    /** Play a track from {@link #localSearchResults} — a filtered view of
     *  {@link #library}, so it needs its own queue-index mapping rather than
     *  {@link #play(int)}, which indexes into the full library. */
    public void playLocalSearchResult(int i) {
        List<Track> results = localSearchResults.peek();
        if (results == null || i < 0 || i >= results.size()) return;
        playQueue(results, i);
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

    /** Only the legacy NetEase CDN contract understands the {@code param=} resize
     * suffix. Provider and custom-source artwork URLs must remain byte-for-byte as
     * returned instead of having a host-specific query parameter grafted onto them. */
    private static String trackCoverUrl(Track track, String size) {
        if (track == null || track.coverUrl == null) return "";
        return track.source == Track.Source.NETEASE
                ? thumbUrl(track.coverUrl, size) : track.coverUrl;
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
            long backendMs = Math.max(0L, backend.position());
            Long shown = positionMs.peek();
            long resumeMs = Math.max(backendMs, shown != null ? shown : 0L);
            beginLyricClockLoad(resumeMs);
            Logger.warn("playback error on netease track {}, clearing stale url and retrying at {}ms",
                    t.neteaseId, resumeMs);
            t.streamUrl = null;
            resolveAndPlayNetease(t, idx, resumeMs, coverRevision.get());
            return;
        }
        if (t != null && t.source == Track.Source.PLUGIN && t.streamUrl != null
                && !t.canonicalId().equals(errorRetryMediaId)) {
            errorRetryMediaId = t.canonicalId();
            int idx = playIndex;
            long backendMs = Math.max(0L, backend.position());
            Long shown = positionMs.peek();
            long resumeMs = Math.max(backendMs, shown != null ? shown : 0L);
            beginLyricClockLoad(resumeMs);
            Logger.warn("playback error on plugin track {}, clearing stale url and retrying at {}ms",
                    t.canonicalId(), resumeMs);
            t.streamUrl = null;
            t.streamHeaders.clear();
            resolveAndPlayPlugin(t, idx, resumeMs, coverRevision.get());
            return;
        }
        skipUnplayable(playIndex, "音频加载失败");
    }

    /** Stop waiting on an unplayable queue entry and advance once. The failure
     *  count is reset only by backend.onStarted, so a queue in which every entry
     *  is blocked makes at most one full pass instead of spinning forever. */
    private void skipUnplayable(int expectedIndex, String reason) {
        if (playIndex != expectedIndex) return;
        consecutivePlaybackFailures++;
        int failures = consecutivePlaybackFailures;
        if (queue.size() <= 1 || failures >= queue.size()) {
            stoppedLyricPositionMs = Math.max(0L, backend.position());
            playbackStarted = false;
            playingIntent = false;
            backend.pause();
            post(() -> {
                loading.set(false);
                playing.set(false);
                showToast("无法播放：" + reason);
            });
            notifyPlayback();
            return;
        }
        post(() -> showToast("已跳过无法播放的歌曲：" + reason));
        // Failed tracks must never obey repeat-one, and a deterministic walk avoids
        // shuffle selecting the same broken entry again before trying the others.
        playAt((playIndex + 1) % queue.size());
    }

    /** Load the home content: recommended songs (login) + recommended playlists. */
    public void loadHome() {
        post(() -> homeLoading.set(true));
        PluginManifest provider = primaryProviderWith(ProviderCapability.HOME);
        if (provider != null) {
            pluginProviders.home(provider.id, 50).whenComplete((home, error) -> post(() -> {
                if (!provider.id.equals(pluginRegistry.primaryProvider())) return;
                homeLoading.set(false);
                if (error != null) {
                    Logger.warn("plugin {} home failed: {}", provider.id, safeMessage(error));
                    sourceRecommendPlaylists.set(Collections.<Playlist>emptyList());
                    sourceRecommendations.set(Collections.<Song>emptyList());
                    return;
                }
                ProviderHome value = home != null ? home : new ProviderHome();
                sourceRecommendPlaylists.set(Collections.unmodifiableList(
                        new ArrayList<>(value.playlists)));
                sourceRecommendations.set(Collections.unmodifiableList(
                        new ArrayList<>(value.songs)));
            }));
            return;
        }
        if (onlineSourcesArePluginOnly()) {
            recommendations.set(Collections.<NeteaseSong>emptyList());
            recommendPlaylists.set(Collections.<NeteasePlaylist>emptyList());
            homeLoading.set(false);
            return;
        }
        sourceRecommendPlaylists.set(Collections.<Playlist>emptyList());
        sourceRecommendations.set(Collections.<Song>emptyList());
        worker.submit(() -> {
            try {
                List<NeteasePlaylist> picks = netease.personalizedPlaylists(12);
                String size = gridCoverSize();
                for (NeteasePlaylist p : picks) {
                    p.coverThumbPath = thumbUrl(p.coverUrl, size);
                }
                post(() -> recommendPlaylists.set(picks));
            } catch (Throwable e) {
                Logger.warn("personalized playlists failed: {}", e.toString());
            }
            if (netease.isLoggedIn()) {
                try {
                    List<NeteaseSong> daily = netease.recommendSongs();
                    fillMissingCovers(daily);
                    buildSongThumbs(daily, "128");
                    post(() -> recommendations.set(daily));
                } catch (Throwable e) {
                    Logger.warn("daily recommend failed: {}", e.toString());
                }
            }
            post(() -> homeLoading.set(false));
        });
    }

    private void publishPlayingArtist(Track t) {
        playingArtistId.set(t != null ? t.artistId : 0L);
        String ids = t != null ? orEmpty(t.artistIdsCsv) : "";
        if (ids.isEmpty() && t != null) ids = orEmpty(t.artistMediaId);
        playingArtistIdsCsv.set(ids);
        playingArtistNamesCsv.set(t != null ? orEmpty(t.artistNamesCsv) : "");
    }

    /** Lyric-page artist tap: picker when several credits, otherwise the one artist. */
    public void openPlayingArtist() {
        openSongArtistPicker(playingArtistIdsCsv.peek(), playingArtistNamesCsv.peek());
    }

    /** Opens SongContextMenu's "查看歌手" picker. QML hands over the song's full
     *  artist list as two parallel CSVs (ids comma-joined, names joined on
     *  U+0001 so a name containing a comma can't desync the pairing) rather
     *  than a structured object -- passing a List built in QML script back
     *  across the bridge as a Java method parameter isn't a pattern used
     *  anywhere else in this codebase, while plain-string method args are. */
    public void openSongArtistPicker(String idsCsv, String namesCsv) {
        if (idsCsv == null || idsCsv.isEmpty()) return;
        String[] ids = idsCsv.split(",", -1);
        String[] namesArr = namesCsv != null ? namesCsv.split(String.valueOf((char) 1), -1) : new String[0];
        List<NeteaseSong.ArtistRef> refs = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            String token = ids[i] != null ? ids[i].trim() : "";
            if (token.isEmpty()) continue;
            NeteaseSong.ArtistRef ref = new NeteaseSong.ArtistRef();
            if (token.indexOf(':') >= 0) {
                ref.mediaId = token;
            } else {
                try {
                    ref.id = Long.parseLong(token);
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            ref.name = i < namesArr.length ? namesArr[i] : "";
            refs.add(ref);
        }
        if (refs.isEmpty()) return;
        // A single credit has no choice to make. Use the exact same entry point as
        // selecting an item from the multi-artist dialog, but skip the dialog and
        // its avatar request entirely.
        if (refs.size() == 1) {
            closeSongArtistPicker();
            openArtistCredit(refs.get(0));
            return;
        }
        // The lyric chrome is a separate host pass above Main.qml. A picker
        // opened while that layer is up is covered, so drop lyrics first.
        if (Boolean.TRUE.equals(lyricsOpen.peek())) setLyricsOpen(false);
        songArtistPickerList.set(refs);
        songArtistPickerOpen.set(true);
        // A song's own artist credits carry no avatar (id/name only) -- fetch
        // each one's profile picture in the background and patch it in as it
        // arrives, same "show text now, images pop in" idea as everywhere else
        // covers load lazily.
        final long revision = ++songArtistPickerRevision;
        worker.submit(() -> fetchSongArtistAvatars(refs, revision));
    }

    public void openArtistCredit(NeteaseSong.ArtistRef ref) {
        if (ref == null) return;
        if (ref.mediaId != null && !ref.mediaId.isEmpty()) openMediaArtist(ref.mediaId);
        else openArtist(ref.id);
    }

    private void fetchSongArtistAvatars(List<NeteaseSong.ArtistRef> refs, long revision) {
        for (NeteaseSong.ArtistRef ref : refs) {
            if (songArtistPickerRevision != revision) return; // a newer picker open superseded this
            try {
                String cover = fetchArtistCreditCover(ref);
                if (cover == null || cover.isEmpty()) continue;
                ref.coverUrl = cover;
                ref.coverThumbPath = cover.startsWith("http") ? thumbUrl(cover, "128") : cover;
                if (songArtistPickerRevision != revision) return;
                // Fresh ArtistRef instances, not the same mutated objects re-wrapped in
                // a new ArrayList: List.equals() compares elements pairwise, and two
                // lists holding the SAME (reference-equal) element objects come out
                // "equal" even after those objects' own fields changed in place --
                // Property.set() skips firing its change listeners for a value that
                // equals the current one (the exact repeat-toast bug from
                // [[qplayer-2026-07-status]]), so QML would never see this update.
                List<NeteaseSong.ArtistRef> copy = new ArrayList<>(refs.size());
                for (NeteaseSong.ArtistRef r : refs) copy.add(copyArtistRef(r));
                post(() -> {
                    if (songArtistPickerRevision != revision) return;
                    songArtistPickerList.set(copy);
                });
            } catch (Throwable e) {
                Logger.warn("song-artist-picker avatar fetch failed for {}: {}",
                        ref.mediaId != null && !ref.mediaId.isEmpty() ? ref.mediaId : Long.toString(ref.id),
                        e.toString());
            }
        }
    }

    private String fetchArtistCreditCover(NeteaseSong.ArtistRef ref) throws Exception {
        if (ref.mediaId != null && !ref.mediaId.isEmpty()) {
            Artist artist = pluginProviders.artist(MediaId.parse(ref.mediaId)).get(8, TimeUnit.SECONDS);
            if (artist == null) return null;
            if (artist.artworkUrl != null && !artist.artworkUrl.isEmpty()) return artist.artworkUrl;
            return artist.coverUrl;
        }
        if (ref.id == 0L) return null;
        NeteaseClient.ArtistPage page = netease.artistDetail(ref.id);
        NeteaseArtist artist = page != null ? page.artist : null;
        return artist != null ? artist.coverUrl : null;
    }

    private static NeteaseSong.ArtistRef copyArtistRef(NeteaseSong.ArtistRef r) {
        NeteaseSong.ArtistRef c = new NeteaseSong.ArtistRef();
        c.id = r.id;
        c.mediaId = r.mediaId;
        c.name = r.name;
        c.coverUrl = r.coverUrl;
        c.coverThumbPath = r.coverThumbPath;
        return c;
    }

    public void closeSongArtistPicker() {
        songArtistPickerRevision++;
        songArtistPickerOpen.set(false);
    }

    /** Open a playlist: detail (name) + its tracks. */
    /** Open an artist page: profile (name/avatar/bio) + hot songs + albums. */
    public void openMediaArtist(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return;
        if (mediaId.indexOf(':') < 0) {
            try { openArtist(Long.parseLong(mediaId)); } catch (NumberFormatException ignored) {}
            return;
        }
        final MediaId id;
        try { id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.ARTIST); }
        catch (IllegalArgumentException error) { showToast("无效的歌手标识"); return; }
        openSourceArtistId.set(id.toString());
        openArtistId.set(0L);
        artistPageOpen.set(true);
        pageNavigationTarget.set("artist");
        pageNavigationEntityId.set(id.toString());
        pageNavigationRevision.set(++pageNavigationSequence);
        artistLoading.set(true);
        artistName.set("");
        artistCoverPath.set("");
        artistBriefDesc.set("");
        sourceArtistSongs.set(Collections.<Song>emptyList());
        sourceArtistAlbums.set(Collections.<Album>emptyList());
        pluginProviders.artist(id).whenComplete((artist, error) -> post(() -> {
            if (!id.toString().equals(openSourceArtistId.peek())) return;
            artistLoading.set(false);
            if (error != null || artist == null) {
                Logger.warn("plugin artist {} failed: {}", id, safeMessage(error));
                showToast("加载歌手信息失败，请检查网络或插件状态");
                return;
            }
            artistName.set(artist.name);
            artistCoverPath.set(artist.artworkUrl);
            artistBriefDesc.set(artist.description);
            sourceArtistSongs.set(Collections.unmodifiableList(new ArrayList<>(artist.songs)));
            sourceArtistAlbums.set(Collections.unmodifiableList(new ArrayList<>(artist.albums)));
        }));
    }

    public void openArtist(long artistId) {
        if (onlineSourcesArePluginOnly()) { showToast("请通过音源插件打开歌手"); return; }
        if (artistId == 0L) return;
        openSourceArtistId.set("");
        pageNavigationEntityId.set(Long.toString(artistId));
        currentArtistId = artistId;
        openArtistId.set(artistId);
        artistPageOpen.set(true);
        pageNavigationTarget.set("artist");
        pageNavigationRevision.set(++pageNavigationSequence);
        artistLoading.set(true);
        artistName.set("");
        artistCoverPath.set("");
        artistBriefDesc.set("");
        artistSongs.set(Collections.<NeteaseSong>emptyList());
        artistAlbums.set(Collections.<NeteaseAlbum>emptyList());
        worker.submit(() -> {
            try {
                NeteaseClient.ArtistPage page = netease.artistDetail(artistId);
                List<NeteaseAlbum> albums = netease.artistAlbums(artistId, 50);
                applyAlbumCoverSize(albums);
                List<NeteaseSong> hotSongs = page != null ? page.hotSongs : Collections.<NeteaseSong>emptyList();
                fillMissingCovers(hotSongs);
                buildSongThumbs(hotSongs, "128");
                NeteaseArtist artist = page != null ? page.artist : null;
                post(() -> {
                    if (currentArtistId != artistId) return;   // a newer open won
                    artistName.set(artist != null && artist.name != null ? artist.name : "");
                    artistCoverPath.set(artist != null && artist.coverUrl != null
                            ? thumbUrl(artist.coverUrl, "256") : "");
                    artistBriefDesc.set(artist != null && artist.briefDesc != null ? artist.briefDesc : "");
                    artistSongs.set(hotSongs);
                    artistAlbums.set(albums);
                    artistLoading.set(false);
                });
            } catch (Throwable e) {
                Logger.warn("open artist {} failed: {}", artistId, e.getMessage());
                post(() -> {
                    if (currentArtistId != artistId) return;
                    artistLoading.set(false);
                    showToast("加载歌手信息失败，请检查网络");
                });
            }
        });
    }

    /** Play a song from the open artist's hot-songs list. */
    public void playArtistSong(int i) {
        if (!openSourceArtistId.peek().isEmpty()) {
            playPluginSongList(sourceArtistSongs.peek(), i);
            return;
        }
        playSongList(artistSongs.peek(), i);
    }

    /** Open an album page: profile (name/cover/artist) + full tracklist. */
    public void openMediaAlbum(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return;
        if (mediaId.indexOf(':') < 0) {
            try { openAlbum(Long.parseLong(mediaId)); } catch (NumberFormatException ignored) {}
            return;
        }
        final MediaId id;
        try { id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.ALBUM); }
        catch (IllegalArgumentException error) { showToast("无效的专辑标识"); return; }
        openSourceAlbumId.set(id.toString());
        openAlbumId.set(0L);
        albumPageOpen.set(true);
        pageNavigationTarget.set("album");
        pageNavigationEntityId.set(id.toString());
        pageNavigationRevision.set(++pageNavigationSequence);
        albumLoading.set(true);
        albumName.set("");
        albumCoverPath.set("");
        albumArtistName.set("");
        albumArtistId.set(0L);
        albumArtistMediaId.set("");
        albumPublishYear.set("");
        sourceAlbumTracks.set(Collections.<Song>emptyList());
        pluginProviders.album(id).whenComplete((album, error) -> post(() -> {
            if (!id.toString().equals(openSourceAlbumId.peek())) return;
            albumLoading.set(false);
            if (error != null || album == null) {
                Logger.warn("plugin album {} failed: {}", id, safeMessage(error));
                showToast("加载专辑信息失败，请检查网络或插件状态");
                return;
            }
            albumName.set(album.name);
            albumCoverPath.set(album.artworkUrl);
            albumArtistName.set(album.artistName);
            albumArtistMediaId.set(album.artistMediaId);
            albumPublishYear.set(album.publishTimeMs > 0
                    ? new java.text.SimpleDateFormat("yyyy年", java.util.Locale.CHINA)
                            .format(new java.util.Date(album.publishTimeMs)) : "");
            sourceAlbumTracks.set(Collections.unmodifiableList(new ArrayList<>(album.songs)));
        }));
    }

    public void openAlbum(long albumId) {
        if (onlineSourcesArePluginOnly()) { showToast("请通过音源插件打开专辑"); return; }
        if (albumId == 0L) return;
        openSourceAlbumId.set("");
        albumArtistMediaId.set("");
        pageNavigationEntityId.set(Long.toString(albumId));
        currentAlbumId = albumId;
        openAlbumId.set(albumId);
        albumPageOpen.set(true);
        pageNavigationTarget.set("album");
        pageNavigationRevision.set(++pageNavigationSequence);
        albumLoading.set(true);
        albumName.set("");
        albumCoverPath.set("");
        albumArtistName.set("");
        albumArtistId.set(0L);
        albumPublishYear.set("");
        albumTracks.set(Collections.<NeteaseSong>emptyList());
        worker.submit(() -> {
            try {
                NeteaseClient.AlbumPage page = netease.albumDetail(albumId);
                List<NeteaseSong> songs = page != null ? page.songs : Collections.<NeteaseSong>emptyList();
                fillMissingCovers(songs);
                buildSongThumbs(songs, "128");
                NeteaseAlbum album = page != null ? page.album : null;
                post(() -> {
                    if (currentAlbumId != albumId) return;   // a newer open won
                    albumName.set(album != null && album.name != null ? album.name : "");
                    albumCoverPath.set(album != null && album.coverUrl != null
                            ? thumbUrl(album.coverUrl, "256") : "");
                    albumArtistName.set(album != null && album.artistName != null ? album.artistName : "");
                    albumArtistId.set(album != null ? album.artistId : 0L);
                    albumPublishYear.set(album != null && album.publishTime > 0
                            ? new java.text.SimpleDateFormat("yyyy年", java.util.Locale.CHINA)
                                    .format(new java.util.Date(album.publishTime))
                            : "");
                    albumTracks.set(songs);
                    albumLoading.set(false);
                });
            } catch (Throwable e) {
                Logger.warn("open album {} failed: {}", albumId, e.getMessage());
                post(() -> {
                    if (currentAlbumId != albumId) return;
                    albumLoading.set(false);
                    showToast("加载专辑信息失败，请检查网络");
                });
            }
        });
    }

    /** Play a song from the open album's tracklist. */
    public void playAlbumTrack(int i) {
        if (!openSourceAlbumId.peek().isEmpty()) {
            playPluginSongList(sourceAlbumTracks.peek(), i);
            return;
        }
        playSongList(albumTracks.peek(), i);
    }

    public void openMediaPlaylist(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return;
        if (mediaId.indexOf(':') < 0) {
            try { openPlaylist(Long.parseLong(mediaId)); } catch (NumberFormatException ignored) {}
            return;
        }
        final MediaId id;
        try { id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST); }
        catch (IllegalArgumentException error) { showToast("无效的歌单标识"); return; }
        currentPlaylistId = 0L;
        openPlaylistId.set(0L);
        openSourcePlaylistId.set(id.toString());
        playlistLoading.set(true);
        playlistOffline.set(false);
        playlistTracks.set(Collections.<NeteaseSong>emptyList());
        sourcePlaylistTracks.set(Collections.<Song>emptyList());
        playlistTitle.set("");
        playlistCoverPath.set("");
        playlistSubscribed.set(false);
        playlistOwned.set(false);
        playlistDeletable.set(false);
        pluginProviders.playlist(id).whenComplete((playlist, error) -> post(() -> {
            if (!id.toString().equals(openSourcePlaylistId.peek())) return;
            playlistLoading.set(false);
            Playlist resolved = playlist;
            if (error != null || resolved == null) {
                Logger.warn("plugin playlist {} failed: {}", id, safeMessage(error));
                resolved = mediaPlaylistCacheIndex.get(id.toString());
                if (resolved == null) {
                    showToast("加载歌单失败，请检查网络或插件状态");
                    return;
                }
                playlistOffline.set(true);
            }
            playlistTitle.set(resolved.name);
            playlistCoverPath.set(resolved.artworkUrl);
            playlistSubscribed.set(resolved.subscribed);
            playlistOwned.set(resolved.owned);
            playlistDeletable.set(resolved.deletable);
            sourcePlaylistTracks.set(Collections.unmodifiableList(
                    new ArrayList<>(resolved.songs)));
            mediaPlaylistCacheIndex.upsert(resolved);
            worker.submit(mediaPlaylistCacheIndex::save);
        }));
    }

    public void openPlaylist(long playlistId) {
        if (onlineSourcesArePluginOnly()) { showToast("请通过音源插件打开歌单"); return; }
        // Called on the render thread from QML: clear the previous playlist and show
        // the spinner immediately, before the off-thread fetch starts.
        currentPlaylistId = playlistId;
        openSourcePlaylistId.set("");
        openPlaylistId.set(playlistId);
        playlistLoading.set(true);
        playlistTracks.set(Collections.<NeteaseSong>emptyList());
        playlistTitle.set("");
        playlistCoverPath.set("");
        // Reset the collect state; the real values land once playlist/detail resolves, so
        // the icon stays hidden (loading) until then rather than flashing a wrong state.
        playlistSubscribed.set(false);
        playlistOwned.set(false);
        playlistDeletable.set(false);
        worker.submit(() -> {
            try {
                fetchAndPublishPlaylist(playlistId);
            } catch (Throwable e) {
                Logger.warn("open playlist {} failed: {}", playlistId, e.getMessage());
                offlinePlaylistFallback(playlistId);
            }
        });
    }

    /** The actual network fetch + publish, shared by {@link #openPlaylist}'s own
     *  attempt and {@link #scheduleOfflineRetry}'s background retry loop once
     *  offline data is already showing — same fetch/build/publish either way, they
     *  only differ in whether the caller already reset the UI to a loading state
     *  first (retry deliberately doesn't, so the offline content stays on screen
     *  until a real update actually lands, no loading-spinner flash every retry). */
    private void fetchAndPublishPlaylist(long playlistId) throws Exception {
        NeteasePlaylist detail = netease.playlistDetail(playlistId);
        List<NeteaseSong> songs = netease.playlistTracks(playlistId, 200);
        fillMissingCovers(songs);
        buildSongThumbs(songs, "128");
        // Thumbnails are keyed by coverUrl, not by playlist, so a track already
        // cached from being seen in another playlist is reused here rather than
        // re-fetched over the network; getThumb64 also touches it, so a song
        // that keeps showing up across playlists stays recently-used and
        // survives THUMB64_MAX_COUNT eviction instead of aging out unnoticed.
        for (NeteaseSong s : songs) {
            if (s.coverUrl != null && !s.coverUrl.isEmpty()) {
                String localThumb = diskCache.getThumb64(thumbUrl(s.coverUrl, "64"));
                if (localThumb != null) s.coverThumbPath = localThumb;
            }
            s.cachedOffline = s.id != 0 && diskCache.hasAudio(s.id);
        }
        String name = detail != null ? detail.name : "";
        // Same cache-preference as loadMyPlaylists: this playlist's cover was
        // very likely already cached (at this same layout's size) from
        // appearing in 我的, so prefer that over the CDN url to survive a
        // mid-session network drop.
        String localCover = detail != null && detail.coverUrl != null
                ? diskCache.getThumb64(thumbUrl(detail.coverUrl, gridCoverSize())) : null;
        String cover = localCover != null ? localCover : detail != null
                ? (detail.coverThumbPath != null ? detail.coverThumbPath : detail.coverUrl) : null;
        boolean subscribed = detail != null && detail.subscribed;
        boolean owned = detail != null && uid != 0 && detail.creatorUid == uid;
        post(() -> {
            if (currentPlaylistId != playlistId) return;   // a newer open won
            playlistTitle.set(name == null ? "" : name);
            playlistCoverPath.set(cover == null ? "" : cover);
            playlistTracks.set(songs);
            playlistSubscribed.set(subscribed);
            playlistOwned.set(owned);
            playlistDeletable.set(owned && favoritePid != 0L && playlistId != favoritePid);
            playlistLoading.set(false);
            playlistOffline.set(false);
        });
        // mine=false: opening a playlist (推荐, search, a shared link, or one of
        // 我的 own) doesn't by itself prove membership in 我的 — only
        // loadMyPlaylists's own enumeration does, and that upsert is sticky
        // (see PlaylistCacheIndex.upsert), so an actually-owned playlist keeps
        // its mine=true from there regardless of this call.
        playlistCacheIndex.upsert(playlistId, name,
                detail != null ? detail.coverUrl : null, songs.size(), songs, false);
        cachePlaylistCoverAsync(detail != null ? detail.coverUrl : null);
        // One download per track (DiskCache's thumb64 count-cap bounds
        // total storage/downloads over time, not this call).
        for (NeteaseSong s : songs) cacheThumb64Async(s.coverUrl);
        // cacheWorker runs its downloads in submission order (single thread), so
        // appending this after the loop above guarantees it only runs once every
        // one of those downloads has finished (succeeded OR failed) — the one
        // signal we need to re-apply "prefer local cache" for any track that
        // wasn't cached yet when the list was first built above. Covers exactly
        // "opened this playlist online, network dropped moments later": whatever
        // finished downloading before it dropped still gets picked up here, no
        // network needed for that re-check.
        cacheWorker.submit(() -> refreshPlaylistCoversFromCache(playlistId, songs));
        playlistCacheIndex.save();
    }

    /** Re-checks disk cache for every track's thumbnail once all of {@link
     *  #fetchAndPublishPlaylist}'s cacheThumb64Async downloads for this open have
     *  settled, and republishes {@link #playlistTracks} only if something actually
     *  changed. Mutates the same {@code NeteaseSong} instances the UI is currently
     *  showing (fine — a plain field write off the render thread, only ever read
     *  from it after the post() below) rather than rebuilding the list. */
    private void refreshPlaylistCoversFromCache(long playlistId, List<NeteaseSong> songs) {
        if (currentPlaylistId != playlistId) return;
        boolean changed = false;
        for (NeteaseSong s : songs) {
            if (s.coverUrl == null || s.coverUrl.isEmpty()) continue;
            String localThumb = diskCache.getThumb64(thumbUrl(s.coverUrl, "64"));
            if (localThumb != null && !localThumb.equals(s.coverThumbPath)) {
                s.coverThumbPath = localThumb;
                changed = true;
            }
        }
        if (!changed) return;
        post(() -> { if (currentPlaylistId == playlistId) playlistTracks.set(new ArrayList<>(songs)); });
    }

    /** {@link #openPlaylist} couldn't reach the network: fall back to whatever
     *  {@link #playlistCacheIndex} has for this id, if anything. Songs never
     *  actually played have no cached thumbnail (only {@code cacheAudioAsync}
     *  downloads one) — those rows just show the placeholder glyph, same as a
     *  cover still decoding. */
    private void offlinePlaylistFallback(long playlistId) {
        PlaylistCacheIndex.Cached cached = playlistCacheIndex.get(playlistId);
        if (cached == null || cached.songs.isEmpty()) {
            post(() -> {
                if (currentPlaylistId != playlistId) return;
                playlistLoading.set(false);
                showToast("当前无网络，且未缓存过该歌单");
            });
            return;
        }
        List<NeteaseSong> offline = new ArrayList<>(cached.songs.size());
        for (NeteaseSong s : cached.songs) offline.add(withLocalThumb(s));
        String cover = cached.coverUrl != null ? diskCache.getThumb64(thumbUrl(cached.coverUrl, gridCoverSize())) : null;
        final String name = cached.name;
        post(() -> {
            if (currentPlaylistId != playlistId) return;
            playlistTitle.set(name == null ? "" : name);
            playlistCoverPath.set(cover == null ? "" : cover);
            playlistTracks.set(offline);
            playlistLoading.set(false);
            playlistOffline.set(true);
            showToast("当前无网络，显示已缓存的歌单内容");
        });
        scheduleOfflineRetry(playlistId);
    }

    /** Quietly retries {@link #fetchAndPublishPlaylist} in the background every 20s
     *  while this playlist is still open and still showing offline data, so it
     *  updates itself the moment the network comes back instead of staying stuck
     *  on a stale offline snapshot until the user happens to reopen it. Stops the
     *  moment the user navigates away ({@code currentPlaylistId} changes) or a
     *  retry actually succeeds (fetchAndPublishPlaylist itself clears
     *  playlistOffline). Runs on the dedicated retryWorker, not worker, since it
     *  deliberately blocks its own thread for the whole wait. */
    private void scheduleOfflineRetry(long playlistId) {
        retryWorker.submit(() -> {
            try {
                Thread.sleep(20_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (currentPlaylistId != playlistId) return;             // user moved on
            if (!Boolean.TRUE.equals(playlistOffline.peek())) return; // already back online
            try {
                fetchAndPublishPlaylist(playlistId);
                post(() -> { if (currentPlaylistId == playlistId) showToast("网络已恢复，歌单已更新"); });
            } catch (Throwable e) {
                scheduleOfflineRetry(playlistId); // still offline -- try again in another 20s
            }
        });
    }

    /** Copy of {@code s} with {@code coverThumbPath} resolved to its disk-cached
     *  64x64 file (empty if that track was never actually played/cached) — never
     *  mutates the shared instance living inside {@link #playlistCacheIndex}. */
    private NeteaseSong withLocalThumb(NeteaseSong s) {
        NeteaseSong copy = new NeteaseSong();
        copy.id = s.id;
        copy.name = s.name;
        copy.artist = s.artist;
        copy.album = s.album;
        copy.coverUrl = s.coverUrl;
        copy.durationMs = s.durationMs;
        copy.fee = s.fee;
        copy.cachedOffline = s.id != 0 && diskCache.hasAudio(s.id);
        if (s.coverUrl != null && !s.coverUrl.isEmpty()) {
            String local = diskCache.getThumb64(thumbUrl(s.coverUrl, "64"));
            copy.coverThumbPath = local != null ? local : "";
        }
        return copy;
    }

    /** Collect / un-collect the currently open playlist. No-op on your own playlist or
     *  when signed out. Optimistically flips the icon, reverting if the server refuses. */
    public void togglePlaylistSubscribe() {
        String sourceId = openSourcePlaylistId.peek();
        if (sourceId != null && !sourceId.isEmpty()) {
            toggleMediaPlaylistSubscribe(sourceId);
            return;
        }
        if (!loggedIn.get() || playlistOwned.get()) return;
        if (subscribeBusy) return;   // one in flight: ignore the tap, never stack/retry
        final long id = currentPlaylistId;
        if (id == 0) return;
        final boolean target = !playlistSubscribed.get();
        subscribeBusy = true;
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
                subscribeBusy = false;
                if (currentPlaylistId != id) return;
                if (done) {
                    showToast(target ? "已收藏歌单" : "已取消收藏");
                    loadMyPlaylists();   // reflect the change in 我的
                } else {
                    playlistSubscribed.set(!target);   // revert the optimistic flip; no auto-retry
                }
            });
        });
    }

    private void toggleMediaPlaylistSubscribe(String mediaId) {
        if (!loggedIn.peek() || playlistOwned.peek() || subscribeBusy) return;
        final MediaId id;
        try { id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST); }
        catch (IllegalArgumentException error) { return; }
        if (!pluginHasCapability(id.provider(), ProviderCapability.PLAYLIST_MUTATION)) return;
        final boolean target = !Boolean.TRUE.equals(playlistSubscribed.peek());
        subscribeBusy = true;
        playlistSubscribed.set(target);
        pluginProviders.mutatePlaylist(id, target ? "subscribe" : "unsubscribe",
                        Collections.<MediaId>emptyList(), null)
                .whenComplete((success, error) -> post(() -> {
                    subscribeBusy = false;
                    if (!id.toString().equals(openSourcePlaylistId.peek())) return;
                    if (error == null && Boolean.TRUE.equals(success)) {
                        showToast(target ? "已收藏歌单" : "已取消收藏");
                        loadMyPlaylists();
                    } else {
                        playlistSubscribed.set(!target);
                        showToast("操作失败：" + safeMessage(error));
                    }
                }));
    }

    /** Load the signed-in user's playlists (favorites + created). */
    public void loadMyPlaylists() {
        PluginManifest provider = primaryProviderWith(ProviderCapability.USER_PLAYLISTS);
        if (provider != null) {
            pluginProviders.userPlaylists(provider.id, 100).whenComplete((playlists, error) -> post(() -> {
                if (!provider.id.equals(pluginRegistry.primaryProvider())) return;
                if (error != null) {
                    Logger.warn("plugin {} user playlists failed: {}", provider.id,
                            safeMessage(error));
                    showToast("加载歌单失败，请检查网络或插件状态");
                    return;
                }
                sourceMyPlaylists.set(playlists);
                playlistCount.set(playlists.size());
            }));
            return;
        }
        if (onlineSourcesArePluginOnly()) {
            myPlaylists.set(Collections.<NeteasePlaylist>emptyList());
            return;
        }
        // uid == 0 normally means "not logged in, nothing to load" -- except when
        // refreshLogin's own live check just failed offline but cookies say we
        // were logged in last session (see its catch block): there's no live uid
        // to call userPlaylists(uid, ...) with, but there may still be a cached
        // snapshot from a previous online session worth falling back to.
        if (uid == 0) {
            worker.submit(this::offlineMyPlaylistsFallback);
            return;
        }
        worker.submit(() -> {
            try {
                List<NeteasePlaylist> pls = netease.userPlaylists(uid, 100);
                long favPid = 0L;
                String size = gridCoverSize();
                for (NeteasePlaylist p : pls) {
                    // Prefer an already-cached thumbnail over the CDN url: a playlist
                    // browsed before survives the network dropping mid-session without
                    // needing an app restart to fall back to offlineMyPlaylistsFallback.
                    // Playlist covers are cached at gridCoverSize() (matches the
                    // online display size, unlike the small per-track thumbnails) —
                    // see cachePlaylistCoverAsync.
                    String local = diskCache.getThumb64(thumbUrl(p.coverUrl, size));
                    p.coverThumbPath = local != null ? local : thumbUrl(p.coverUrl, size);
                    p.owned = p.creatorUid == uid;
                    // The "我喜欢的音乐" default is the first playlist the user owns.
                    if (favPid == 0L && p.owned) favPid = p.id;
                    playlistCacheIndex.upsert(p.id, p.name, p.coverUrl, p.trackCount, null, true);
                    cachePlaylistCoverAsync(p.coverUrl);
                }
                favoritePid = favPid;
                playlistCacheIndex.save();
                post(() -> {
                    myPlaylists.set(pls);
                    playlistCount.set(pls.size());
                });
            } catch (Throwable e) {
                Logger.warn("user playlists failed: {}", e.getMessage());
                offlineMyPlaylistsFallback();
            }
        });
    }

    /** {@link #loadMyPlaylists} couldn't reach the network (or has no live uid to
     *  even try with): fall back to whatever {@link #playlistCacheIndex} has from
     *  previous online sessions, regardless of uid. No-op (leaves whatever 我的
     *  already showed) when there's nothing cached at all. */
    private void offlineMyPlaylistsFallback() {
        List<PlaylistCacheIndex.Cached> cached = playlistCacheIndex.snapshot();
        if (cached.isEmpty()) return;
        List<NeteasePlaylist> offline = new ArrayList<>(cached.size());
        for (PlaylistCacheIndex.Cached e : cached) {
            // playlistCacheIndex also holds playlists merely opened from 推荐/search/a
            // shared link — those aren't actually part of 我的 and must not show up
            // here just because their song list happened to get cached too.
            if (!e.mine) continue;
            NeteasePlaylist p = new NeteasePlaylist();
            p.id = e.id;
            p.name = e.name;
            p.coverUrl = e.coverUrl;
            p.trackCount = e.trackCount;
            if (e.coverUrl != null && !e.coverUrl.isEmpty()) {
                String local = diskCache.getThumb64(thumbUrl(e.coverUrl, gridCoverSize()));
                p.coverThumbPath = local != null ? local : "";
            }
            offline.add(p);
        }
        post(() -> {
            myPlaylists.set(offline);
            playlistCount.set(offline.size());
            showToast("当前无网络，显示已缓存的歌单列表");
        });
    }

    /** Create a new (public) playlist named {@code name}, then refresh 我的. */
    public void createPlaylist(String name) {
        PluginManifest provider = primaryProviderWith(ProviderCapability.PLAYLIST_MUTATION);
        if (provider != null) {
            final String normalized = name != null ? name.trim() : "";
            if (!loggedIn.peek() || normalized.isEmpty()) return;
            pluginProviders.createPlaylist(provider.id, normalized, false)
                    .whenComplete((id, error) -> post(() -> {
                        if (error == null && id != null && !id.isEmpty()) {
                            showToast("歌单已创建");
                            loadMyPlaylists();
                        } else showToast("创建歌单失败：" + safeMessage(error));
                    }));
            return;
        }
        if (uid == 0 || name == null) return;
        final String nm = name.trim();
        if (nm.isEmpty()) return;
        worker.submit(() -> {
            try {
                long id = netease.createPlaylist(nm, false);
                if (id != 0) {
                    post(() -> {
                        showToast("歌单已创建");
                        loadMyPlaylists();
                    });
                } else {
                    showToast("创建歌单失败");
                }
            } catch (Throwable e) {
                Logger.warn("create playlist failed: {}", e.getMessage());
                showToast("创建歌单失败");
            }
        });
    }

    /** Set a playlist's cover to a local image file, then refresh the detail view
     *  (and 我的, whose cards also show it). Owned-playlist enforcement lives in the
     *  QML (same as the delete button — {@code playlistOwned}), not here, since the
     *  server itself rejects a cover change on a playlist you don't own. */
    public void setPlaylistCover(long playlistId, String localImagePath) {
        if (uid == 0 || playlistId == 0 || localImagePath == null) return;
        final String path = localImagePath.trim();
        if (path.isEmpty()) return;
        worker.submit(() -> {
            byte[] data;
            try {
                data = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            } catch (Throwable e) {
                Logger.warn("read cover file {} failed: {}", path, e.getMessage());
                showToast("读取图片文件失败");
                return;
            }
            uploadPlaylistCover(playlistId, data, new java.io.File(path).getName());
        });
    }

    /** Set a playlist's cover from raw image bytes — Android's native gallery picker
     *  hands over a {@code content://} URI with no filesystem path to read, so the
     *  host reads it itself and passes the bytes straight through. */
    public void setPlaylistCoverBytes(long playlistId, byte[] data, String filename) {
        if (uid == 0 || playlistId == 0 || data == null) return;
        worker.submit(() -> uploadPlaylistCover(playlistId, data, filename == null ? "cover.jpg" : filename));
    }

    private void uploadPlaylistCover(long playlistId, byte[] data, String filename) {
        if (data.length == 0) {
            showToast("图片文件为空");
            return;
        }
        try {
            long imgId = netease.uploadImage(data, filename);
            boolean ok = imgId != 0 && netease.updatePlaylistCover(playlistId, imgId);
            post(() -> {
                if (ok) {
                    showToast("封面已更新");
                    if (currentPlaylistId == playlistId) openPlaylist(playlistId);
                    loadMyPlaylists();
                } else {
                    showToast("封面更新失败");
                }
            });
        } catch (Throwable e) {
            Logger.warn("set playlist cover {} failed: {}", playlistId, e.getMessage());
            showToast("封面更新失败");
        }
    }

    /** Host hook to launch the platform image picker for a playlist cover. */
    public interface CoverPicker {
        void pick(long playlistId);
    }

    private volatile CoverPicker coverPicker;

    public void setCoverPicker(CoverPicker p) {
        this.coverPicker = p;
    }

    /** QML calls this to launch the platform picker. Android reads the picked image's
     *  bytes and calls {@link #setPlaylistCoverBytes}; desktop passes the selected
     *  local path to {@link #setPlaylistCover}. */
    public void pickPlaylistCover(long playlistId) {
        CoverPicker p = coverPicker;
        if (p != null) onMain(() -> p.pick(playlistId));
    }

    /** Delete a playlist owned by the user, then refresh 我的. */
    public void deletePlaylist(long playlistId) {
        if (uid == 0 || playlistId == 0) return;
        worker.submit(() -> {
            try {
                if (netease.deletePlaylist(playlistId)) {
                    post(() -> {
                        showToast("歌单已删除");
                        loadMyPlaylists();
                    });
                } else {
                    showToast("删除歌单失败");
                }
            } catch (Throwable e) {
                Logger.warn("delete playlist {} failed: {}", playlistId, e.getMessage());
                showToast("删除歌单失败");
            }
        });
    }

    public void deleteMediaPlaylist(String playlistMediaId) {
        final MediaId id;
        try { id = MediaId.parse(playlistMediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST); }
        catch (IllegalArgumentException error) { showToast("无效的歌单标识"); return; }
        pluginProviders.mutatePlaylist(id, "delete", Collections.<MediaId>emptyList(), null)
                .whenComplete((success, error) -> post(() -> {
                    if (error == null && Boolean.TRUE.equals(success)) {
                        showToast("歌单已删除");
                        loadMyPlaylists();
                    } else showToast("删除歌单失败：" + safeMessage(error));
                }));
    }

    /** Add a track to one of the user's playlists (from a song's long-press menu). */
    public void addToPlaylist(long playlistId, long songId) {
        if (uid == 0 || playlistId == 0 || songId == 0) return;
        worker.submit(() -> {
            try {
                boolean ok = netease.manipulatePlaylistTracks(playlistId, songId, true);
                showToast(ok ? "已添加到歌单" : "添加失败");
            } catch (Throwable e) {
                Logger.warn("add track {} -> playlist {} failed: {}", songId, playlistId, e.getMessage());
                showToast("添加失败");
            }
        });
    }

    public void addMediaToPlaylist(String playlistMediaId, String songMediaId) {
        mutateMediaPlaylist(playlistMediaId, songMediaId, true, false);
    }

    public void removeMediaFromCurrentPlaylist(String songMediaId) {
        mutateMediaPlaylist(openSourcePlaylistId.peek(), songMediaId, false, true);
    }

    private void mutateMediaPlaylist(String playlistMediaId, String songMediaId,
                                     boolean add, boolean refreshDetail) {
        final MediaId playlistId;
        final MediaId songId;
        try {
            playlistId = MediaId.parse(playlistMediaId).requireKind(
                    dev.t1m3.qplayer.media.MediaKind.PLAYLIST);
            songId = MediaId.parse(songMediaId).requireKind(
                    dev.t1m3.qplayer.media.MediaKind.SONG);
        } catch (IllegalArgumentException error) {
            showToast("无效的媒体标识");
            return;
        }
        pluginProviders.mutatePlaylist(playlistId, add ? "add" : "remove",
                        Collections.singletonList(songId), null)
                .whenComplete((success, error) -> post(() -> {
                    if (error == null && Boolean.TRUE.equals(success)) {
                        showToast(add ? "已添加到歌单" : "已从歌单移除");
                        if (refreshDetail && playlistId.toString().equals(openSourcePlaylistId.peek())) {
                            openMediaPlaylist(playlistId.toString());
                        }
                    } else showToast((add ? "添加失败：" : "移除失败：") + safeMessage(error));
                }));
    }

    /** Remove a track from the currently open playlist (the "从此歌单移除" menu
     *  entry only appears there), then refresh the detail view. Reads the open id
     *  internally so QML needn't round-trip a 64-bit playlist id back through a
     *  numeric property. */
    public void removeFromCurrentPlaylist(long songId) {
        final long playlistId = currentPlaylistId;
        if (uid == 0 || playlistId == 0 || songId == 0) return;
        worker.submit(() -> {
            try {
                boolean ok = netease.manipulatePlaylistTracks(playlistId, songId, false);
                post(() -> {
                    showToast(ok ? "已从歌单移除" : "移除失败");
                    if (ok && currentPlaylistId == playlistId) openPlaylist(playlistId);
                });
            } catch (Throwable e) {
                Logger.warn("remove track {} <- playlist {} failed: {}", songId, playlistId, e.getMessage());
                showToast("移除失败");
            }
        });
    }

    /** Recently played (netease listen history). */
    public void loadRecent() {
        PluginManifest provider = primaryProviderWith(ProviderCapability.RECENT);
        if (provider != null) {
            pluginProviders.recent(provider.id, 100).whenComplete((songs, error) -> post(() -> {
                if (!provider.id.equals(pluginRegistry.primaryProvider())) return;
                if (error != null) {
                    Logger.warn("plugin {} recent failed: {}", provider.id, safeMessage(error));
                    return;
                }
                sourceRecentSongs.set(songs);
            }));
            return;
        }
        if (onlineSourcesArePluginOnly()) {
            recentSongs.set(Collections.<NeteaseSong>emptyList());
            return;
        }
        if (uid == 0) return;
        worker.submit(() -> {
            try {
                List<NeteaseSong> rec = netease.recentPlayed(100);
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

    private void refreshPluginLiked(String provider) {
        if (!pluginHasCapability(provider, ProviderCapability.LIKE)) {
            pluginLikedSet.clear();
            likedCount.set(0);
            currentLiked.set(false);
            return;
        }
        pluginProviders.likedSongs(provider).whenComplete((ids, error) -> post(() -> {
            if (!provider.equals(pluginRegistry.primaryProvider())) return;
            if (error != null) {
                Logger.warn("plugin {} liked list failed: {}", provider, safeMessage(error));
                return;
            }
            pluginLikedSet.clear();
            pluginLikedSet.addAll(ids);
            likedCount.set(ids.size());
            Track current = currentTrack();
            currentLiked.set(current != null && ids.contains(current.canonicalId()));
        }));
    }

    /** Like / unlike the current netease track. */
    public void toggleLike() {
        Track cur = currentTrack();
        if (cur != null && cur.source == Track.Source.PLUGIN) {
            final MediaId id;
            try { id = MediaId.parse(cur.canonicalId()).requireKind(
                    dev.t1m3.qplayer.media.MediaKind.SONG); }
            catch (IllegalArgumentException error) { return; }
            if (!pluginHasCapability(id.provider(), ProviderCapability.LIKE)) return;
            final boolean target = !pluginLikedSet.contains(id.toString());
            pluginProviders.setLiked(id, target).whenComplete((success, error) -> post(() -> {
                if (error == null && Boolean.TRUE.equals(success)) {
                    if (target) pluginLikedSet.add(id.toString());
                    else pluginLikedSet.remove(id.toString());
                    likedCount.set(pluginLikedSet.size());
                    Track current = currentTrack();
                    if (current != null && id.toString().equals(current.canonicalId())) {
                        currentLiked.set(target);
                    }
                } else showToast(target ? "收藏失败" : "取消收藏失败");
            }));
            return;
        }
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
                    showToast(netease.isLoggedIn()
                            ? (target ? "收藏失败" : "取消收藏失败") : "请先登录");
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

    /** Best local artwork path for the current track, including a downloaded
     * network cover in DiskCache. Safe while the render pump is suspended. */
    public String currentCoverPath() {
        Track track = currentTrack();
        return track != null ? coverDiskPath(track) : "";
    }

    /** Intended play state for the media session — true from play/resume until pause,
     *  unaffected by the backend's brief async-prepare gap. */
    public boolean isPlaying() {
        return playingIntent;
    }

    /** Pause playback immediately — intended for MediaSession callbacks that may
     *  arrive on a binder thread on some OEM ROMs (MIUI/HyperOS, HarmonyOS)
     *  where main-thread message delivery is throttled in the background. */
    public void mediaPause() {
        if (!playingIntent) return;
        playingIntent = false;
        post(() -> playing.set(false));
        // playingIntent flips immediately (MediaSession state must be immediate by
        // contract); the actual backend.pause() rides the same fade-out toggle()
        // uses, deferred until silence, so lock-screen/notification/dynamic-island
        // pause ramps down instead of cutting audio hard.
        if (fadeEnabled) {
            startFadeOut(FADE_OUT_MS, () -> { if (!playingIntent) backend.pause(); });
        } else {
            cancelFadeAtGain(1f);
            backend.pause();
        }
        notifyPlayback();
    }

    /** Resume playback immediately — counterpart to {@link #mediaPause()} for
     *  MediaSession callbacks that may run off the main thread. */
    public void mediaResume() {
        if (playingIntent) return;
        if (needsReplay && playIndex >= 0) {
            needsReplay = false;
            onMain(() -> playAt(playIndex));
            return;
        }
        // Mirrors toggle()'s resume branch: pick the fade back up mid-ramp if a
        // pause's fade-out is still in flight, otherwise fade in from silence.
        if (isFadeRunning() && backend.isPlaying()) {
            if (fadeEnabled) {
                startVolumeFade(currentFadeGain(), 1f, FADE_IN_MS, null);
            } else {
                cancelFadeAtGain(1f);
            }
        } else {
            if (fadeEnabled) {
                startVolumeFade(0f, 1f, FADE_IN_MS, null);
            } else {
                cancelFadeAtGain(1f);
            }
            if (!backend.isPlaying()) backend.resume();
        }
        playingIntent = true;
        post(() -> playing.set(true));
        notifyPlayback();
    }

    /** Current source duration in ms (0 if unknown). */
    public long duration() {
        long d = backend.duration();
        return d > 0 ? d : 0L;
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ? "未知错误" : message;
    }

    // --- Login (fully async: qrLoginKey/qrLoginCheck are blocking HTTP, must
    //     never run on the render thread the QML handlers call from) ----------

    private volatile String pendingUnikey;
    private volatile String pendingPluginLoginProvider = "";
    private volatile String pendingPluginLoginChallenge = "";
    private volatile String pluginQrMethodId = "";
    private volatile String pluginWebMethodId = "";
    private volatile String pluginCredentialMethodId = "";
    private volatile LoginMethod activeWebLoginMethod;
    /** QR module matrix (true=dark) as nested Lists so QML can index [y][x]. */
    public final Property<List<List<Boolean>>> qrImage =
            new Property<>(Collections.<List<Boolean>>emptyList());
    /** 0 loading / 800 expired / 801 waiting / 802 scanned / 803 success. */
    public final Property<Integer> qrStatus = new Property<>(0);
    /** Whether this shell can embed the official website in a system WebView. */
    public final Property<Boolean> webLoginAvailable = new Property<>(false);
    /** True while the browser is open or a pasted/browser Cookie is being checked. */
    public final Property<Boolean> webLoginBusy = new Property<>(false);
    /** Safe user-facing failure reason; never contains the submitted Cookie. */
    public final Property<String> webLoginError = new Property<>("");
    /** Incremented only after server validation and encrypted persistence succeed. */
    public final Property<Long> webLoginSuccessRevision = new Property<>(0L);

    /** Open the shell-owned official-site login window. */
    public void startWebLogin() {
        WebLoginLauncher launcher = webLoginLauncher;
        if (launcher == null) {
            webLoginError.set("当前平台不支持内嵌网页登录，请粘贴 Cookie 登录");
            return;
        }
        if (Boolean.TRUE.equals(webLoginBusy.peek())) return;
        webLoginError.set("");
        webLoginBusy.set(true);
        try {
            LoginMethod method = activeWebLoginMethod;
            if (!pendingPluginLoginProvider.isEmpty() && method != null) {
                launcher.launch(method.webUrl, method.cookieUrl,
                        method.credentialCookieName, loginProviderName.peek());
            } else {
                webLoginBusy.set(false);
                webLoginError.set("请先安装并启用支持登录的音源插件");
            }
        } catch (Throwable e) {
            Logger.warn("web login launcher failed: {}", safeMessage(e));
            webLoginBusy.set(false);
            webLoginError.set("无法打开登录页面");
        }
    }

    /** Called by a shell after reading the official WebView cookie store. */
    public void completeWebLogin(String cookieHeader) {
        importLoginCookie(cookieHeader, pluginWebMethodId);
    }

    /** Paste-login fallback invoked directly by QML. */
    public void submitCookieLogin(String cookieHeader) {
        if (Boolean.TRUE.equals(webLoginBusy.peek())) return;
        webLoginBusy.set(true);
        webLoginError.set("");
        importLoginCookie(cookieHeader, pluginCredentialMethodId);
    }

    /** Shell callback when its browser window is closed before obtaining MUSIC_U. */
    public void cancelWebLogin() {
        post(() -> webLoginBusy.set(false));
    }

    /** Shell callback for browser creation/native-engine failures. */
    public void failWebLogin(String message) {
        final String safe = message == null || message.trim().isEmpty()
                ? "无法打开音源登录页面" : message.trim();
        post(() -> {
            webLoginBusy.set(false);
            webLoginError.set(safe);
        });
    }

    public void clearWebLoginError() {
        webLoginError.set("");
    }

    private void importLoginCookie(String cookieHeader, String pluginMethodId) {
        final String candidate = cookieHeader == null ? "" : cookieHeader;
        if (!pendingPluginLoginProvider.isEmpty()
                && pluginMethodId != null && !pluginMethodId.isEmpty()) {
            pluginAccounts.submit(pendingPluginLoginProvider, pluginMethodId, candidate)
                    .whenComplete((challenge, error) -> post(() -> {
                        if (error != null) {
                            webLoginBusy.set(false);
                            webLoginError.set(safeMessage(error));
                            return;
                        }
                        applyPluginLoginChallenge(challenge, true);
                    }));
            return;
        }
        if (onlineSourcesArePluginOnly()) {
            post(() -> {
                webLoginBusy.set(false);
                webLoginError.set("当前没有可用的登录插件");
            });
            return;
        }
        worker.submit(() -> {
            try {
                long accountId = netease.importLoginCookies(candidate);
                uid = accountId;
                post(() -> {
                    webLoginBusy.set(false);
                    webLoginError.set("");
                    webLoginSuccessRevision.set(webLoginSuccessRevision.peek() + 1L);
                    showToast("登录成功");
                });
                refreshLogin();
            } catch (Throwable e) {
                // Never log the candidate header. Parser/network errors have safe,
                // credential-free messages by contract.
                String reason = safeMessage(e);
                Logger.warn("cookie login failed: {}", reason);
                post(() -> {
                    webLoginBusy.set(false);
                    webLoginError.set(reason == null || reason.trim().isEmpty()
                            ? "Cookie 登录失败" : reason);
                });
            }
        });
    }

    /** Mint a login key + matrix off-thread; publishes to {@link #qrImage}/{@link #qrStatus}. */
    public void startQrLogin() {
        post(() -> qrStatus.set(0));
        if (!pendingPluginLoginProvider.isEmpty() && !pluginQrMethodId.isEmpty()) {
            final String provider = pendingPluginLoginProvider;
            pluginAccounts.begin(provider, pluginQrMethodId).whenComplete((challenge, error) -> post(() -> {
                if (!provider.equals(pendingPluginLoginProvider)) return;
                if (error != null) {
                    Logger.warn("plugin {} QR login start failed: {}", provider, safeMessage(error));
                    qrStatus.set(800);
                    return;
                }
                pendingPluginLoginChallenge = challenge.id;
                applyPluginLoginChallenge(challenge, false);
            }));
            return;
        }
        if (onlineSourcesArePluginOnly()) {
            post(() -> qrStatus.set(800));
            return;
        }
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
        if (!pendingPluginLoginProvider.isEmpty() && !pendingPluginLoginChallenge.isEmpty()) {
            final String provider = pendingPluginLoginProvider;
            final String challenge = pendingPluginLoginChallenge;
            pluginAccounts.poll(provider, challenge).whenComplete((result, error) -> post(() -> {
                if (!provider.equals(pendingPluginLoginProvider)
                        || !challenge.equals(pendingPluginLoginChallenge)) return;
                if (error == null) applyPluginLoginChallenge(result, false);
            }));
            return;
        }
        if (onlineSourcesArePluginOnly()) return;
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

    private void applyPluginLoginChallenge(LoginChallenge challenge, boolean credentialFlow) {
        if (challenge == null) return;
        if (challenge.id != null && !challenge.id.isEmpty()) {
            pendingPluginLoginChallenge = challenge.id;
        }
        if (challenge.qrContent != null && !challenge.qrContent.isEmpty()) {
            qrImage.set(QrMatrix.encode(challenge.qrContent));
        }
        switch (challenge.status) {
            case "scanned": qrStatus.set(802); break;
            case "success":
                qrStatus.set(803);
                webLoginBusy.set(false);
                webLoginError.set("");
                webLoginSuccessRevision.set(webLoginSuccessRevision.peek() + 1L);
                refreshPluginAccount(pendingPluginLoginProvider, challenge.account);
                showToast("登录成功");
                break;
            case "expired":
                qrStatus.set(800);
                if (!credentialFlow) startQrLogin();
                break;
            case "failed":
                pendingCredentialEncryptedNotice = false;
                qrStatus.set(800);
                webLoginBusy.set(false);
                webLoginError.set(challenge.message == null || challenge.message.isEmpty()
                        ? "登录失败" : challenge.message);
                break;
            default: qrStatus.set(801); break;
        }
    }

    private void refreshPluginAccount(String provider, AccountProfile supplied) {
        if (provider == null || provider.isEmpty()) return;
        if (supplied != null) {
            publishPluginAccount(provider, supplied);
            return;
        }
        if (!pluginHasCapability(provider, ProviderCapability.ACCOUNT)) return;
        pluginAccounts.account(provider).whenComplete((account, error) -> post(() -> {
            if (error == null && provider.equals(pluginRegistry.primaryProvider())) {
                publishPluginAccount(provider, account);
            }
        }));
    }

    private void publishPluginAccount(String provider, AccountProfile account) {
        if (account == null || !provider.equals(pluginRegistry.primaryProvider())) return;
        loggedIn.set(account.loggedIn);
        userName.set(orEmpty(account.displayName));
        userAvatar.set(orEmpty(account.avatarUrl));
        if (!account.loggedIn) {
            userVipType.set(0);
            userLevel.set(0);
            userSignature.set("");
        } else {
            userVipType.set(account.membershipTier);
            userLevel.set(account.level);
            userSignature.set(orEmpty(account.signature));
        }
        if (account.loggedIn) {
            publishPendingCredentialEncryptedNotice();
            if (pluginHostApi.consumeCredentialUnlock()) {
                showToast("已从系统密钥库安全恢复登录凭据");
            }
            refreshPluginLiked(provider);
            loadMyPlaylists();
            loadRecent();
        } else {
            pendingCredentialEncryptedNotice = false;
            pluginLikedSet.clear();
            likedCount.set(0);
            currentLiked.set(false);
        }
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
        // Cheap, local (cookie presence only, no network) -- read before the network
        // attempt below so the catch block still knows "was logged in last session"
        // even when loginUid()'s actual API call is what throws.
        final boolean cookieLoggedIn = netease.isLoggedIn();
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
                    if (in) publishPendingCredentialEncryptedNotice();
                    else pendingCredentialEncryptedNotice = false;
                });
                if (in) {
                    if (id > 0 && netease.consumeCredentialUnlock()) {
                        showToast("已从系统密钥库安全恢复登录凭据");
                    }
                    loadHome();
                    loadMyPlaylists();
                    refreshLiked();
                }
            } catch (Throwable e) {
                Logger.warn("refreshLogin failed: {}", e.toString());
                // Offline (or the API's just down) but cookies say we were logged in
                // last session: don't fall back to a logged-out UI just because the
                // live refresh couldn't reach the network -- still surface it as
                // logged in and let loadHome/loadMyPlaylists fall back to their own
                // cached data (uid stays 0 this session; loadMyPlaylists() no longer
                // requires it to at least try the offline path).
                if (cookieLoggedIn) {
                    post(() -> loggedIn.set(true));
                    loadHome();
                    loadMyPlaylists();
                }
            }
        });
    }

    public void logout() {
        pendingCredentialEncryptedNotice = false;
        String provider = pendingPluginLoginProvider;
        if (!provider.isEmpty()) {
            legacyCredentialMigrationGeneration.incrementAndGet();
            pluginAccounts.logout(provider).whenComplete((ignored, error) -> post(() -> {
                if (error != null) {
                    showToast("退出登录失败：" + safeMessage(error));
                    return;
                }
                legacyCredentialMigrationAttempted = true;
                netease.logout();
                clearPublishedAccount();
                showToast("已退出登录");
            }));
            return;
        }
        if (onlineSourcesArePluginOnly()) {
            legacyCredentialMigrationGeneration.incrementAndGet();
            legacyCredentialMigrationAttempted = true;
            netease.logout();
            clearPublishedAccount();
            return;
        }
        netease.logout();
        clearPublishedAccount();
        showToast("已退出登录");
    }

    private void clearPublishedAccount() {
        pendingCredentialEncryptedNotice = false;
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
    }

    /** Persist the queue + live playback position + play mode right now. The only
     *  other {@link #saveQueue()} call site is a track change, which is long past
     *  by the time the app actually exits mid-song — callers that own an app-is-
     *  really-going-away moment (desktop's {@link #shutdown()}; Android's
     *  PlaybackService.onTaskRemoved/onDestroy, since QPlayerActivity.onDestroy()
     *  deliberately skips shutdown() while playing so the service can keep going
     *  in the background) should call this so "resume where I left off" actually
     *  reflects where playback was, not wherever the last track switch left it. */
    public void saveSessionState() {
        saveQueue();
    }

    public void shutdown() {
        appShuttingDown = true;
        // Capture the final position before release() tears down the backend (a
        // released MediaPlayer's position() is undefined/0).
        saveSessionState();
        // A just-clicked lyric adjustment may still be queued behind network work;
        // persist the current in-memory map synchronously before stopping the worker.
        saveLyricOffsets();
        synchronized (fadeLock) {
            fadeGeneration++;
            fadeRunning = false;
            fadeCompleteAction = null;
        }
        fadeWorker.shutdownNow();
        backend.release();
        worker.shutdownNow();
        searchWorker.shutdownNow();
        cacheWorker.shutdownNow();
        lyricWorker.shutdownNow();
        retryWorker.shutdownNow();
        monetFetchWorker.shutdownNow();
        monetWorker.shutdownNow();
        pluginManager.close();
        pluginHostApi.close();
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
        showToast("缓存已清除");
    }
}
