package dev.t1m3.qplayer.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.cache.DiskCache;
import dev.t1m3.qplayer.cache.MediaMetaIndex;
import dev.t1m3.qplayer.cache.MediaPlaylistCacheIndex;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.library.LibraryScanner;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricParser;
import dev.t1m3.qplayer.lyric.TtmlParser;
import dev.t1m3.qplayer.lyric.skia.LyricConfig;
import dev.t1m3.qplayer.media.AccountProfile;
import dev.t1m3.qplayer.media.Album;
import dev.t1m3.qplayer.media.Artist;
import dev.t1m3.qplayer.media.HomeSection;
import dev.t1m3.qplayer.media.LoginChallenge;
import dev.t1m3.qplayer.media.LoginMethod;
import dev.t1m3.qplayer.media.MediaId;
import dev.t1m3.qplayer.media.Page;
import dev.t1m3.qplayer.media.Playlist;
import dev.t1m3.qplayer.media.ProviderHome;
import dev.t1m3.qplayer.media.Song;
import dev.t1m3.qplayer.media.StreamDescriptor;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.netease.dto.NeteaseAlbum;
import dev.t1m3.qplayer.netease.dto.NeteaseArtist;
import dev.t1m3.qplayer.netease.dto.NeteaseLyric;
import dev.t1m3.qplayer.netease.dto.NeteasePlaylist;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.playlist.LocalPlaylist;
import dev.t1m3.qplayer.playlist.LocalPlaylistStore;
import dev.t1m3.qplayer.playlist.MyPlaylistOrderStore;
import dev.t1m3.qplayer.playlist.PlaylistSubscription;
import dev.t1m3.qplayer.playlist.PlaylistTrack;
import dev.t1m3.qplayer.playlist.PlaylistTransfer;
import dev.t1m3.qplayer.plugin.CorePluginHostApi;
import dev.t1m3.qplayer.plugin.PluginAccountService;
import dev.t1m3.qplayer.plugin.PluginCatalogEntry;
import dev.t1m3.qplayer.plugin.PluginCatalogService;
import dev.t1m3.qplayer.plugin.PluginCompatibility;
import dev.t1m3.qplayer.plugin.PluginCredentialVault;
import dev.t1m3.qplayer.plugin.PluginInstaller;
import dev.t1m3.qplayer.plugin.PluginManager;
import dev.t1m3.qplayer.plugin.PluginManifest;
import dev.t1m3.qplayer.plugin.PluginPackageVerifier;
import dev.t1m3.qplayer.plugin.PluginPermission;
import dev.t1m3.qplayer.plugin.PluginProviderService;
import dev.t1m3.qplayer.plugin.PluginRegistry;
import dev.t1m3.qplayer.plugin.PluginRow;
import dev.t1m3.qplayer.plugin.PluginSetupState;
import dev.t1m3.qplayer.plugin.PluginUiContributionRow;
import dev.t1m3.qplayer.plugin.PluginUiDescription;
import dev.t1m3.qplayer.plugin.ProviderCapability;
import dev.t1m3.qplayer.plugin.SourceAccountRow;
import dev.t1m3.qplayer.plugin.VerifiedPluginPackage;
import dev.t1m3.qplayer.qml.QmlRuntimeLock;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.GitHubDownloadUrls;
import dev.t1m3.qplayer.util.Logger;
import dev.t1m3.qplayer.util.QrMatrix;

import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.runtime.color.StyleManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The single QML-facing object. Registered as a context global ({@code view.context("player",
 * controller)}); QML binds to its public {@link Property} fields (reactive — reading registers a
 * dependency, the controller's {@code set} re-evaluates the binding) and invokes its public methods
 * from event handlers.
 *
 * <h3>Playback queue</h3>
 *
 * Local files and source plugins feed one {@link PlaybackSession}. Source URLs are resolved lazily
 * for the selected request; old Source/ID records are routed only after their matching provider is
 * available. {@code next}/{@code prev} walk the queue; in shuffle, {@code prev} pops a play-history
 * stack so it returns the song that was actually playing rather than another random slot.
 * Auto-advance wires {@code backend.onComplete -> next}.
 *
 * <h3>Threading</h3>
 *
 * Queue, selection, playback intent and reactive publication share {@link QmlRuntimeLock#MONITOR}.
 * Host control tasks join this owner via {@link #onMain(Runnable)}; IO results either join it or
 * post to {@link #pump()}. Host callbacks use a separate unlocked dispatcher because they may
 * stop/join the render thread. Queue IO, artwork lookup and release checks have dedicated
 * responsibility classes rather than independently mutating playback state.
 */
public final class PlayerController {

    private final AudioBackend backend;
    private final MetadataReader metadataReader;
    private final NeteaseClient netease;
    private volatile ColorExtractor colorExtractor;
    private volatile java.util.function.Consumer<String> clipboard;
    private volatile WebLoginLauncher webLoginLauncher;
    private volatile WebAuthScriptLauncher webAuthScriptLauncher;
    private volatile AppLinkLauncher appLinkLauncher;
    private volatile String hostPlatform = "desktop";
    private volatile boolean monetEnabled = true;
    private static final String DEFAULT_SEED = "#6750A4";
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qplayer-net");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService ioWorker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread thread = new Thread(r, "qplayer-io");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final QueuePersistence queuePersistence =
            new QueuePersistence(AppDirs.stateFile("queue.json"), ioWorker);
    private final AudioCacheCoordinator audioCacheCoordinator;
    private CompletableFuture<Void> sessionSave = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> shutdownFuture;
    private CompletableFuture<Object> downloadChoiceResult;
    private String downloadChoiceMediaId = "";
    private ScheduledFuture<?> cacheResizeTask;
    private final AtomicLong cacheResizeRevision = new AtomicLong();
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

    private final ArtworkResolver artworkResolver = new ArtworkResolver(diskCache);

    /** id -> title/artist/cover lookup for songs seen before, so search() has
     *  something to show when the live network call fails (offline, or the API
     *  is just down) — DiskCache itself only knows bare ids, no display text. */
    private final MediaMetaIndex mediaMetaIndex = new MediaMetaIndex();
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
    private volatile FileSavePicker fileSavePicker;
    private volatile VerifiedPluginPackage pendingPluginPackage;
    private volatile boolean pendingPluginPackageTemporary;

    private final List<Track> library = new CopyOnWriteArrayList<>();
    private final PlaybackSession playbackSession = new PlaybackSession();
    private byte[] snapshotArtworkSource;
    private PlaybackSnapshot.Artwork snapshotArtwork;
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
    /** Union of every signed-in source's liked songs, keyed by canonical id. The ids
     *  carry their provider, so a lookup is inherently scoped to the song's own
     *  source even while another source is primary. */
    private final Set<String> pluginLikedSet = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Per-source slices behind {@link #pluginLikedSet}, so one source signing out or
     *  failing to answer only drops its own ids. Main thread only. */
    private final Map<String, Set<String>> pluginLikedBySource = new LinkedHashMap<>();
    private final Random rng = new Random();
    /** Shuffle-mode back-stack of previously played track identities. {@link #prev}
     *  pops this instead of calling {@link #randomIndex()}, so "previous" means the
     *  song that was actually playing a moment ago. Identities rather than queue
     *  indices: {@link #removeFromQueue} renumbers slots, and a replaced queue can
     *  drop entries; missing ids are skipped. Not persisted across sessions. */
    private final ArrayDeque<String> shuffleHistory = new ArrayDeque<>();
    private static final int MAX_SHUFFLE_HISTORY = 100;
    /** True while {@link #prev} is replaying a remembered track, so that
     *  {@link #playAt} does not push the song we are leaving back onto the stack
     *  (which would make the next prev() bounce between two tracks). */
    private boolean consumingShuffleHistory;

    // Control remains available while the render pump is suspended. mainExec is
    // supplied by hosts with a main loop; inline control still joins the same owner.
    private volatile java.util.concurrent.Executor mainExec;
    private volatile String currentQueueMediaPlaylistId = "";
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
        synchronized (QmlRuntimeLock.MONITOR) {
            this.mainExec = e;
        }
    }

    public void setPlaybackListener(PlaybackListener l) {
        synchronized (QmlRuntimeLock.MONITOR) {
            this.playbackListener = l;
        }
    }

    /** All host controls join the QML/state owner, including desktop inline calls. */
    private void onMain(Runnable r) {
        Runnable owned =
                () -> {
                    synchronized (QmlRuntimeLock.MONITOR) {
                        r.run();
                    }
                };
        java.util.concurrent.Executor e = mainExec;
        if (e != null) e.execute(owned);
        else owned.run();
    }

    /** Host callbacks may open modal UI or join the render thread: never hold its lock. */
    private void onHost(Runnable callback) {
        Runnable unlocked =
                () -> {
                    if (Thread.holdsLock(QmlRuntimeLock.MONITOR)) {
                        CompletableFuture.runAsync(callback);
                    } else {
                        callback.run();
                    }
                };
        java.util.concurrent.Executor executor = mainExec;
        if (executor != null) executor.execute(unlocked);
        else unlocked.run();
    }

    private CompletableFuture<Boolean> invokeFileSave(
            FileSavePicker picker,
            String name,
            String mime,
            java.util.function.BiConsumer<String, java.util.function.Consumer<Boolean>> writer) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        onHost(
                () -> {
                    try {
                        picker.save(name, mime, writer)
                                .whenComplete(
                                        (ok, error) -> {
                                            if (error != null) result.completeExceptionally(error);
                                            else result.complete(Boolean.TRUE.equals(ok));
                                        });
                    } catch (RuntimeException error) {
                        result.completeExceptionally(error);
                    }
                });
        return result;
    }

    private void notifyPlayback() {
        wakeRenderer();
        PlaybackListener listener = playbackListener;
        if (listener != null)
            onHost(
                    () -> {
                        if (playbackListener == listener) listener.onPlaybackChanged();
                    });
    }

    // --- Search history ---------------------------------------------------
    private static final int HISTORY_MAX = 100;
    private final List<String> historyList = new ArrayList<>();

    private static final int SEARCH_PAGE_SIZE = 50;

    // Guards against stale async search results: set to the trimmed key before
    // each search(); async workers check equality before publishing results.
    private volatile String currentSearchKey = "";
    private volatile String currentSearchQuery = "";
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
    /** Playing track's canonical media id ("" when nothing is loaded). The
     *  source-neutral counterpart of {@link #currentFilePath}: a row compares
     *  {@code view.rowId(modelData)} against this to highlight "now playing"
     *  regardless of which list it lives in — a bare {@link #index} comparison
     *  is only a position in the current queue and coincidentally lights up
     *  unrelated rows in any other list. */
    public final Property<String> currentMediaId = new Property<>("");
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
    /** True while a fullscreen route (settings, lyrics, playlist, …) covers the
     *  root tabs. Root TextFields hide their TextInput so qml4j's hit-test cannot
     *  focus a buried search box and pop the IME. */
    public final Property<Boolean> rootInputBlocked = new Property<>(false);
    /** Independent, session-only visualizer page; never restored from settings. */
    public final Property<Boolean> temperaOpen = new Property<>(false);
    public final Property<Double> temperaOpacity = new Property<>(0.0);
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
    /** Host-published progress of the lyric page's full-width layout change
     *  (0 cover beside the lyrics .. 1 lyrics across the page, chrome along the
     *  bottom). The QML chrome reads this instead of animating the setting
     *  itself, so it cannot drift from the host-drawn column it moves with. */
    public final Property<Double> lyricFullWidth = new Property<>(0.0);
    /** Current disk cache usage in MB (updated after each cache write). */
    public final Property<Long> cacheSizeMB = new Property<>(0L);

    // --- Local library ----------------------------------------------------
    public final Property<List<Track>> tracks = new Property<>(Collections.<Track>emptyList());
    public final Property<Integer> libraryCount = new Property<>(0);

    // --- Source plugins ---------------------------------------------------
    public final Property<List<PluginRow>> sourcePlugins =
            new Property<>(Collections.<PluginRow>emptyList());
    public final Property<String> primarySourcePlugin = new Property<>("");
    /** True while the unlock-download helper is enabled. Playlist rows show a
     *  download button and owned-playlist right-click keeps the song menu. */
    public final Property<Boolean> downloadHelperEnabled = new Property<>(false);
    /** Every enabled source that can report an account, primary first. The header
     *  properties above stay bound to the primary source alone; this is what the
     *  account page lists so a second signed-in source is not invisible. */
    public final Property<List<SourceAccountRow>> sourceAccounts =
            new Property<>(Collections.<SourceAccountRow>emptyList());
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
    /** Titled playlist groups the source wants drawn above the plain grid, e.g.
     *  NetEase's radar lists. Each carries the range it occupies in
     *  {@link #sourceSectionPlaylists}: QML positions cards from one flat list. */
    public final Property<List<HomeSection>> sourceHomeSections =
            new Property<>(Collections.<HomeSection>emptyList());
    public final Property<List<Playlist>> sourceSectionPlaylists =
            new Property<>(Collections.<Playlist>emptyList());
    /** True while {@link #loadHome} is in flight — lets HomePage.qml tell "still
     *  loading" from "tried and failed" (both look like empty lists otherwise) so
     *  it can show a tap-to-retry affordance instead of a permanent spinner. */
    public final Property<Boolean> homeLoading = new Property<>(Boolean.FALSE);
    public final Property<List<NeteasePlaylist>> myPlaylists = new Property<>(Collections.<NeteasePlaylist>emptyList());
    public final Property<List<NeteaseSong>> recentSongs = new Property<>(Collections.<NeteaseSong>emptyList());
    /** 我的 aggregates every enabled source that can list user playlists, so a
     *  second account's playlists stay visible no matter which source is primary. */
    public final Property<List<Playlist>> sourceMyPlaylists =
            new Property<>(Collections.<Playlist>emptyList());
    public final Property<List<Song>> sourceRecentSongs =
            new Property<>(Collections.<Song>emptyList());
    /** Whether the primary source can create playlists (drives 我的's new-playlist
     *  action, which necessarily targets one source). */
    public final Property<Boolean> sourcePlaylistMutationAvailable = new Property<>(false);
    /** Whether the source of the playlist currently open supports heart
     *  recommendations — not the primary one, since 我的 lists several sources. */
    public final Property<Boolean> sourceHeartRecommendationAvailable = new Property<>(false);
    /** Whether the open playlist's own source can replace its artwork. Scoped to
     *  that playlist's source for the same reason as the flag above, and kept
     *  separate from {@link #sourcePlaylistMutationAvailable} because changing a
     *  cover is its own provider capability: a source may well support adding and
     *  removing songs without supporting artwork upload. */
    public final Property<Boolean> sourcePlaylistCoverAvailable = new Property<>(false);
    /** Whether the open playlist can be rearranged by dragging: its source
     *  advertises {@link ProviderCapability#PLAYLIST_REORDER} and the user owns it.
     *  Someone else's playlist is read-only no matter what the source can do. */
    public final Property<Boolean> sourcePlaylistReorderable = new Property<>(false);
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
    /** Snapshot of the offline-cached netease songs (cache/audio/*.cache), shown in
     *  the rail's download menu; rebuilt on open via {@link #refreshCachedSongs}. */
    public final Property<List<Track>> cachedSongs = new Property<>(Collections.<Track>emptyList());

    // --- Local playlists (user-owned, mixed-source) ------------------------
    /** The user's own playlists, as Playlist DTOs so the existing cards/grids
     *  render them next to source-owned ones without a second component. */
    public final Property<List<Playlist>> localPlaylists =
            new Property<>(Collections.<Playlist>emptyList());
    /** Non-zero whenever at least one local playlist exists; QML binds an int
     *  rather than a Java List's length, same as {@link #playlistCount}. */
    public final Property<Integer> localPlaylistCount = new Property<>(0);
    /** Currently open local playlist, empty when none is. */
    public final Property<String> openLocalPlaylistId = new Property<>("");
    public final Property<String> localPlaylistTitle = new Property<>("");
    /** Cover of the open local playlist: the user's own image when they picked
     *  one, otherwise derived from the songs. */
    public final Property<String> localPlaylistCover = new Property<>("");
    /** Whether that cover is the user's own — gates the "restore default" entry. */
    public final Property<Boolean> localPlaylistCustomCover = new Property<>(false);
    /** Rows of the open local playlist. */
    public final Property<List<Track>> localPlaylistTracks =
            new Property<>(Collections.<Track>emptyList());
    /** Source playlists the open local playlist follows. */
    public final Property<List<PlaylistSubscription>> localPlaylistSubscriptions =
            new Property<>(Collections.<PlaylistSubscription>emptyList());
    /** True while subscriptions of the open playlist are being pulled. */
    public final Property<Boolean> localPlaylistSyncing = new Property<>(false);
    /** How the open playlist is sorted; empty is the user's own order. */
    public final Property<String> localPlaylistSortField = new Property<>(LocalPlaylist.SORT_CUSTOM);
    public final Property<Boolean> localPlaylistSortDescending = new Property<>(false);
    /** Whether rows can be dragged — only meaningful in the user's own order,
     *  since dragging a row inside a sorted view would have nowhere to land. */
    public final Property<Boolean> localPlaylistReorderable = new Property<>(true);

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
    /** Monotonic counter bumped on every liked-set change. Search/queue rows
     *  bind {@code isMediaLiked()} (a plain method, not a Property), so qml4j
     *  never re-evaluates the heart after a like succeeds; reading this from
     *  the same binding is what actually invalidates it. {@link #likedCount}
     *  is not enough: adding one id and dropping another, or wiping then
     *  restoring the same size, is a no-op to Property equality. */
    public final Property<Integer> likedRevision = new Property<>(0);
    public final Property<Integer> playlistCount = new Property<>(0);
    /** Standardized login metadata for the current primary source. */
    public final Property<String> loginProviderName =
            new Property<>(I18n.tr("login.defaultProvider"));
    public final Property<Boolean> pluginLoginActive = new Property<>(false);
    public final Property<List<LoginMethod>> loginMethods =
            new Property<>(Collections.<LoginMethod>emptyList());
    public final Property<Boolean> pluginQrLoginAvailable = new Property<>(false);
    public final Property<Boolean> pluginAppLoginAvailable = new Property<>(false);
    public final Property<Boolean> pluginCredentialLoginAvailable = new Property<>(false);
    public final Property<String> loginAppLabel = new Property<>("");
    public final Property<String> loginAppButtonLabel =
            new Property<>(I18n.tr("login.app.open"));
    public final Property<String> loginAppInstructions =
            new Property<>(I18n.tr("login.app.instructions"));
    public final Property<String> loginAppUrl = new Property<>("");
    public final Property<Boolean> loginAppOpenAvailable = new Property<>(false);
    public final Property<String> loginWebInstructions =
            new Property<>(I18n.tr("login.web.instructions"));
    public final Property<String> loginCredentialInstructions =
            new Property<>(I18n.tr("login.credential.instructions"));
    public final Property<String> loginCredentialLabel =
            new Property<>(I18n.tr("login.credential.label"));

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
    /** Discard home/account results of a source that is no longer the primary one. */
    private final AtomicLong homeGeneration = new AtomicLong();
    private final AtomicLong accountGeneration = new AtomicLong();
    private final AtomicLong sourceAccountsGeneration = new AtomicLong();
    private final AtomicLong loginMetadataGeneration = new AtomicLong();
    private final Map<String, SourceAccountRow> sourceAccountsBySource = new LinkedHashMap<>();
    private List<String> sourceAccountOrder = Collections.emptyList();
    private final AtomicLong pluginLikedGeneration = new AtomicLong();

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
     *  the real message. Safe to call from any thread.
     *
     *  <p>The delay runs on {@link #fadeWorker}, a scheduler, rather than being a
     *  {@code Thread.sleep} submitted to {@code worker}: worker is the general
     *  single-threaded network queue, so a toast used to queue behind whatever
     *  request was already running and could surface tens of seconds late (a
     *  15s connect + 30s read timeout is the worst case) — long after whatever
     *  the user did to trigger it. It also no longer blocks a thread to wait. */
    private void showToast(String msg) {
        post(() -> toast.set(""));
        try {
            fadeWorker.schedule(() -> post(() -> toast.set(msg)), 30, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            // shutdown() stops fadeWorker; a toast queued past that point has no UI
            // left to reach anyway.
        }
    }

    public PlayerController(AudioBackend backend, MetadataReader metadataReader) {
        this(backend, metadataReader, NeteaseClient.INSTANCE);
    }

    public PlayerController(
            AudioBackend backend, MetadataReader metadataReader, NeteaseClient netease) {
        this.backend = backend;
        this.metadataReader = metadataReader;
        this.netease = netease;
        this.audioCacheCoordinator =
                new AudioCacheCoordinator(
                        diskCache,
                        ioWorker,
                        this::downloadPluginCache,
                        track -> {
                            mediaMetaIndex.upsert(track);
                            mediaMetaIndex.save();
                        });
        synchronized (QmlRuntimeLock.MONITOR) {
            // Surface any netease failure reason (private playlist, risk control, ...) as a
            // toast, same Snackbar the auto-source notice uses. Fires on a worker thread, so
            // hop to the render thread to touch the Property.
            netease.setErrorListener(msg -> post(() -> toast.set(msg)));
            netease.setCredentialListener(this::showCredentialNotice);
            pluginHostApi.setCredentialListener(this::showPluginCredentialNotice);
            pluginHostApi.setAppBridge(
                    new CorePluginHostApi.AppBridge() {
                        @Override
                        public CompletableFuture<Object> call(
                                String pluginId, String method, Map<String, Object> arguments) {
                            return pluginAppCall(pluginId, method, arguments);
                        }

                        @Override
                        public void unavailable(String pluginId) {
                            pluginAppUnavailable(pluginId);
                        }
                    });
            credentialOwnerOnlyFallback.set(pluginHostApi.usesOwnerOnlyCredentialProtection());
            backend.setVolume(volume.peek());

            // Re-baseline the media session's position once audio actually starts (the
            // backend prepares asynchronously, so the position at play() time is stale).

            // Audio-focus driven pause/resume (phone call, another player): keep the
            // intended-play state, the UI, and the media session in sync.
            backend.setOnPaused(
                    () ->
                            onMain(
                                    () -> {
                                        // Audio focus is an external discontinuity, not part of a
                                        // user-requested
                                        // ramp. Settle at full logical gain while silent so focus
                                        // regain cannot
                                        // inherit an interrupted fade's small intermediate value.
                                        // Keep playingIntent: OEM ROMs often send AUDIOFOCUS_LOSS
                                        // when the
                                        // screen turns off and never GAIN on unlock. Clearing
                                        // intent here
                                        // made the UI look paused and blocked Activity.onResume
                                        // from
                                        // restarting the decoder. User pause still goes through
                                        // mediaPause().
                                        cancelFadeAtGain(1f);
                                        notifyPlayback();
                                    }));
            backend.setOnResumed(
                    () ->
                            onMain(
                                    () -> {
                                        cancelFadeAtGain(1f);
                                        if (!playbackSession.playing || playbackSession.resolving) {
                                            backend.pause();
                                            return;
                                        }
                                        playing.set(true);
                                        notifyPlayback();
                                    }));
            // On playback error: retry netease tracks whose cached streamUrl went stale
            // (expired VIP link, region lock, etc.). Non-netease or already-retried
            // tracks fall through to autoAdvance.

            worker.submit(this::loadSearchHistory);
            loadLyricOffsets();
            mediaMetaIndex.load();
            mediaPlaylistCacheIndex.load();

            diskCache.loadActivelyCached();
            pluginManager.startEnabled();
            loadQueue();

            // LocalPlaylistStore owns the old custom-playlist file migration.
            localPlaylistStore.load();
            publishLocalPlaylists();
            saveLocalPlaylists();
            myPlaylistOrderStore.load();
            librarySortMode.set(myPlaylistOrderStore.mode());
            legacySourceMigrationAvailable.set(detectLegacySourceMigration());
            publishPlugins();
        }
    }

    /** Enable/disable an installed provider without coupling it to a QML scene. */
    public void setSourcePluginEnabled(String pluginId, boolean enabled) {
        synchronized (QmlRuntimeLock.MONITOR) {
            worker.submit(
                    () -> {
                        try {
                            if (enabled) pluginManager.enable(pluginId);
                            else pluginManager.disable(pluginId);
                            post(this::publishPlugins);
                        } catch (Throwable error) {
                            showToast(I18n.tr("toast.plugin.stateFailed", error.getMessage()));
                        }
                    });
        }
    }

    public void setPrimarySourcePlugin(String pluginId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            worker.submit(
                    () -> {
                        try {
                            pluginRegistry.setPrimaryProvider(pluginId);
                            post(this::publishPlugins);
                        } catch (Throwable error) {
                            showToast(I18n.tr("toast.plugin.primaryFailed", error.getMessage()));
                        }
                    });
        }
    }

    /**
     * One-step setup action for an already installed but disabled/non-primary provider. Keeping
     * both registry writes on the worker avoids the enable/primary race that two independent QML
     * button calls would create.
     */
    public void activateSourcePlugin(String pluginId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (pluginId == null || pluginId.isEmpty()) return;
            worker.submit(
                    () -> {
                        try {
                            PluginRegistry.Entry entry = pluginRegistry.get(pluginId);
                            if (entry == null)
                                throw new IllegalArgumentException(
                                        I18n.tr("toast.plugin.notInstalled"));
                            if (!entry.enabled) pluginManager.enable(pluginId);
                            pluginRegistry.setPrimaryProvider(pluginId);
                            post(this::publishPlugins);
                        } catch (Throwable error) {
                            showToast(I18n.tr("toast.plugin.enableFailed", safeMessage(error)));
                        }
                    });
        }
    }

    public void requestSourcePluginRemoval(String pluginId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            PluginRegistry.Entry entry = pluginRegistry.get(pluginId);
            if (entry == null || Boolean.TRUE.equals(pluginInstallBusy.peek())) return;
            pendingPluginRemovalId.set(entry.id);
            pendingPluginRemovalName.set(entry.name);
            pluginRemovalPromptRevision.set(pluginRemovalPromptRevision.peek() + 1L);
        }
    }

    public void cancelSourcePluginRemoval() {
        synchronized (QmlRuntimeLock.MONITOR) {
            pendingPluginRemovalId.set("");
            pendingPluginRemovalName.set("");
        }
    }

    public void confirmSourcePluginRemoval() {
        synchronized (QmlRuntimeLock.MONITOR) {
            final String pluginId = pendingPluginRemovalId.peek();
            if (pluginId == null
                    || pluginId.isEmpty()
                    || Boolean.TRUE.equals(pluginInstallBusy.peek())) return;
            pluginInstallBusy.set(true);
            worker.submit(
                    () -> {
                        try {
                            pluginManager.remove(pluginId);
                            post(
                                    () -> {
                                        cancelSourcePluginRemoval();
                                        pluginInstallBusy.set(false);
                                        publishPlugins();
                                    });
                            showToast(I18n.tr("toast.plugin.removed"));
                        } catch (Throwable error) {
                            post(() -> pluginInstallBusy.set(false));
                            showToast(I18n.tr("toast.plugin.removeFailed", safeMessage(error)));
                        }
                    });
        }
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
        downloadHelperEnabled.set(pluginManager.isEnabled("unlock"));
        pluginUiContributions.set(pluginManager.uiContributions());
        publishCatalogInstalledState();
        // Home content, the account header and the liked set all belong to whichever
        // source was primary until now. Drop them before the new source's own loads
        // start, otherwise the previous source's recommendations and signed-in user
        // stay on screen (and a source without HOME/ACCOUNT would never replace them).
        boolean primaryChanged = !primary.equals(orEmpty(primarySourcePlugin.peek()));
        if (primaryChanged) clearSourceScopedContent();
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
        String openCoverPlaylist = openSourcePlaylistId.peek();
        if (openCoverPlaylist != null && !openCoverPlaylist.isEmpty()) {
            sourcePlaylistCoverAvailable.set(providerForMediaHas(
                    openCoverPlaylist, ProviderCapability.PLAYLIST_COVER));
        } else {
            sourcePlaylistCoverAvailable.set(primaryProviderWith(
                    ProviderCapability.PLAYLIST_COVER) != null);
        }
        routeInstalledPluginTracks();
        refreshPrimaryPluginLoginMetadata();
        // The enabled set may have changed (enable/disable/remove), so 我的 and the
        // liked sets have to be re-aggregated even when the primary source stayed
        // the same.
        loadMyPlaylists();
        refreshPluginLiked();
        refreshSourceAccounts();
        if (!primary.isEmpty()) loadHome();
    }

    /**
     * Ask every enabled source that can report an account, primary first, and
     * publish the lot. The scalar account header still belongs to the primary
     * source; without this list a second signed-in source has nowhere to appear.
     */
    private void refreshSourceAccounts() {
        List<PluginManifest> providers = new ArrayList<>();
        String primary = pluginRegistry.primaryProvider();
        for (PluginManifest manifest : pluginManager.enabledProviders()) {
            if (!manifest.capabilitySet().contains(ProviderCapability.ACCOUNT)) continue;
            if (manifest.id.equals(primary)) providers.add(0, manifest);
            else providers.add(manifest);
        }
        if (providers.isEmpty()) {
            sourceAccounts.set(Collections.<SourceAccountRow>emptyList());
            return;
        }
        final long generation = sourceAccountsGeneration.incrementAndGet();
        // Seed the order straight away so the page can render the source names
        // while the individual account calls are still in flight.
        final List<String> order = new ArrayList<>();
        for (PluginManifest manifest : providers) order.add(manifest.id);
        post(() -> {
            sourceAccountOrder = order;
            sourceAccountsBySource.keySet().retainAll(order);
            publishSourceAccounts();
        });
        for (PluginManifest manifest : providers) {
            final String providerId = manifest.id;
            final String providerName = manifest.name;
            final boolean canCreatePlaylist = manifest.capabilitySet()
                    .contains(ProviderCapability.PLAYLIST_MUTATION);
            pluginAccounts.account(providerId).whenComplete((account, error) -> post(() -> {
                if (generation != sourceAccountsGeneration.get()) return;
                SourceAccountRow row = new SourceAccountRow();
                row.providerId = providerId;
                row.sourceName = providerName;
                row.canCreatePlaylist = canCreatePlaylist;
                row.primary = providerId.equals(pluginRegistry.primaryProvider());
                if (error == null && account != null) {
                    row.loggedIn = account.loggedIn;
                    row.displayName = orEmpty(account.displayName);
                    row.avatarUrl = orEmpty(account.avatarUrl);
                    if (account.loggedIn) {
                        row.membershipTier = account.membershipTier;
                        row.level = account.level;
                        row.signature = orEmpty(account.signature);
                    }
                } else if (error != null) {
                    // A signed-out source usually just fails the call; show it as
                    // signed out rather than dropping the row, so the user can still
                    // see that the source exists and sign in from here.
                    Logger.warn("plugin {} account failed: {}", providerId, safeMessage(error));
                }
                sourceAccountsBySource.put(providerId, row);
                publishSourceAccounts();
            }));
        }
    }

    /**
     * Aim the login surface at one listed source, so the account page can sign in to a source that
     * is not the primary one. Returns false when that source cannot be signed into, and the caller
     * should not open the login dialog.
     */
    public boolean beginSourceLogin(String providerId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (providerId == null || providerId.isEmpty()) return false;
            for (PluginManifest manifest : pluginManager.enabledProviders()) {
                if (!manifest.id.equals(providerId)) continue;
                if (!manifest.capabilitySet().contains(ProviderCapability.LOGIN)) return false;
                publishLoginMetadata(manifest);
                return true;
            }
            return false;
        }
    }

    /** Sign out of one listed source, leaving every other source signed in. */
    public void logoutSource(String providerId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (providerId == null || providerId.isEmpty()) return;
            final String provider = providerId;
            if (provider.equals(pendingPluginLoginProvider)) {
                legacyCredentialMigrationGeneration.incrementAndGet();
                legacyCredentialMigrationAttempted = true;
            }
            pluginAccounts
                    .logout(provider)
                    .whenComplete(
                            (ignored, error) ->
                                    post(
                                            () -> {
                                                if (error != null) {
                                                    showToast(
                                                            I18n.tr(
                                                                    "toast.logout.failed",
                                                                    safeMessage(error)));
                                                    return;
                                                }
                                                // Only the primary source owns the account header;
                                                // signing out of any
                                                // other source must not blank it.
                                                if (provider.equals(
                                                        pluginRegistry.primaryProvider())) {
                                                    if ("netease".equals(provider))
                                                        netease.logout();
                                                    clearPublishedAccount();
                                                }
                                                dropSourceUserData(provider);
                                                refreshSourceAccounts();
                                                showToast(I18n.tr("toast.logout.done"));
                                            }));
        }
    }

    private void publishSourceAccounts() {
        List<SourceAccountRow> rows = new ArrayList<>();
        for (String providerId : sourceAccountOrder) {
            SourceAccountRow row = sourceAccountsBySource.get(providerId);
            if (row != null) rows.add(row);
        }
        sourceAccounts.set(rows);
    }

    /** Reset everything published on behalf of the source that is no longer primary. */
    private void clearSourceScopedContent() {
        clearHomeSections();
        sourceRecommendPlaylists.set(Collections.<Playlist>emptyList());
        sourceRecommendations.set(Collections.<Song>emptyList());
        sourceRecentSongs.set(Collections.<Song>emptyList());
        clearPublishedPluginAccount();
    }

    /** Account header state only (unlike {@link #clearPublishedAccount}, 我的 is
     *  aggregated across sources and must survive a primary switch). */
    private void clearPublishedPluginAccount() {
        accountGeneration.incrementAndGet();
        pendingCredentialEncryptedNotice = false;
        loggedIn.set(false);
        userName.set("");
        userAvatar.set("");
        userVipType.set(0);
        userLevel.set(0);
        userSignature.set("");
    }

    /** Open the app-wide source setup flow from Home or any future empty state. */
    public void requestSourceSetup() {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (pluginCatalogEntries.peek() == null || pluginCatalogEntries.peek().isEmpty()) {
                refreshPluginCatalog();
            }
            sourceSetupRevision.set(sourceSetupRevision.peek() + 1L);
        }
    }

    /**
     * Navigate to one host-owned plugin settings page. The page may represent either an installed
     * plugin or an entry that is only available in catalog.
     */
    public void requestPluginSettings(String pluginId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (pluginId == null || pluginId.trim().isEmpty()) return;
            pluginSettingsId.set(pluginId.trim());
            pluginSettingsRevision.set(pluginSettingsRevision.peek() + 1L);
        }
    }

    /**
     * Provider-neutral services for plugin-owned app features. Protocol state, room semantics and
     * synchronization policy deliberately stay in JavaScript.
     */
    private CompletableFuture<Object> pluginAppCall(
            String pluginId, String method, Map<String, Object> arguments) {
        if (appShuttingDown) return failedPluginCall("controller is shutting down");
        Map<String, Object> args = arguments != null ? arguments : Collections.emptyMap();
        switch (method) {
            case "playback.read":
                return onMainResult(() -> pluginPlaybackSnapshot(pluginId));
            case "playback.play":
                return onMainResult(
                        () -> {
                            mediaResume();
                            return Boolean.TRUE;
                        });
            case "playback.pause":
                return onMainResult(
                        () -> {
                            mediaPause();
                            return Boolean.TRUE;
                        });
            case "playback.seek":
                {
                    long target =
                            boundedPluginLong(
                                    args.get("positionMs"), 0L, 31L * 24L * 60L * 60L * 1000L);
                    return onMainResult(
                            () -> {
                                mediaSeek(target);
                                return Boolean.TRUE;
                            });
                }
            case "playback.select":
                {
                    String songId = pluginSongId(pluginId, args.get("songId"));
                    long target =
                            boundedPluginLong(
                                    args.get("positionMs"), 0L, 31L * 24L * 60L * 60L * 1000L);
                    boolean shouldPlay = !Boolean.FALSE.equals(args.get("playing"));
                    return onMainResult(() -> selectPluginTrack(songId, target, shouldPlay));
                }
            case "playback.next":
                return onMainResult(
                        () -> {
                            performAutoAdvance();
                            return Boolean.TRUE;
                        });
            case "playback.blockAutoAdvance":
                {
                    boolean block = Boolean.TRUE.equals(args.get("blocked"));
                    return onMainResult(
                            () -> {
                                if (block) pluginAutoAdvanceBlocker = pluginId;
                                else if (pluginId.equals(pluginAutoAdvanceBlocker)) {
                                    pluginAutoAdvanceBlocker = "";
                                    if (pendingNaturalEnd && !playbackSession.queue.isEmpty())
                                        performAutoAdvance();
                                }
                                return Boolean.TRUE;
                            });
                }
            case "queue.replace":
                {
                    List<Song> songs = pluginProviders.validateSongs(pluginId, args.get("songs"));
                    String songId = pluginSongId(pluginId, args.get("currentSongId"));
                    long target =
                            boundedPluginLong(
                                    args.get("positionMs"), 0L, 31L * 24L * 60L * 60L * 1000L);
                    boolean shouldPlay = !Boolean.FALSE.equals(args.get("playing"));
                    return onMainResult(
                            () -> replacePluginQueue(songs, songId, target, shouldPlay));
                }
            case "notifications.toast":
                showToast(pluginText(args.get("message"), 500));
                return CompletableFuture.completedFuture(Boolean.TRUE);
            case "clipboard.write":
                {
                    String text = pluginText(args.get("text"), 16 * 1024);
                    java.util.function.Consumer<String> sink = clipboard;
                    if (sink == null) return failedPluginCall("clipboard is unavailable");
                    sink.accept(text);
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                }
            case "files.save":
                return pluginSaveFile(pluginId, args);
            case "files.downloadCurrent":
                return pluginDownloadCurrent();
            case "webAuth.runScript":
                return runWebAuthScript(pluginId, args);
            default:
                return failedPluginCall("host method is not implemented: " + method);
        }
    }

    private Map<String, Object> pluginPlaybackSnapshot(String pluginId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Track current = currentTrack();
        String currentId =
                current != null && pluginId.equals(providerOf(current))
                        ? nativeSongId(current)
                        : "";
        List<String> ids = new ArrayList<>();
        boolean queueOwned = true;
        for (Track track : playbackSession.queue) {
            if (!pluginId.equals(providerOf(track))) {
                queueOwned = false;
                break;
            }
            ids.add(nativeSongId(track));
        }
        result.put("currentSongId", currentId);
        result.put("queueSongIds", queueOwned ? ids : Collections.emptyList());
        result.put("positionMs", Math.max(0L, backend.position()));
        result.put("durationMs", Math.max(0L, backend.duration()));
        result.put("playing", playbackSession.playing);
        result.put(
                "transitioning",
                playbackSession.resolving
                        || (!playbackStarted
                                && playbackSession.playing
                                && !needsReplay
                                && current != null));
        result.put("seekRevision", seekRevision.get());
        result.put("endRevision", playbackEndRevision.get());
        return result;
    }

    private Boolean selectPluginTrack(String canonicalId, long target, boolean shouldPlay) {
        int targetIndex = -1;
        for (int i = 0; i < playbackSession.queue.size(); i++) {
            if (canonicalId.equals(playbackSession.queue.get(i).canonicalId())) {
                targetIndex = i;
                break;
            }
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
            if (shouldPlay) mediaResume();
            else mediaPause();
        }
        return Boolean.TRUE;
    }

    private Boolean replacePluginQueue(
            List<Song> songs, String canonicalId, long target, boolean shouldPlay) {
        List<Track> tracks = new ArrayList<>(songs.size());
        for (Song song : songs) tracks.add(toTrackPlugin(song));
        int targetIndex = -1;
        for (int i = 0; i < tracks.size(); i++) {
            if (canonicalId.equals(tracks.get(i).canonicalId())) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < 0)
            throw new IllegalArgumentException("target song is not in replacement queue");
        playbackSession.invalidate();
        playbackSession.queue.clear();
        shuffleHistory.clear();
        playbackSession.index = -1;
        playbackSession.queue.addAll(tracks);

        currentQueueMediaPlaylistId = "";
        post(() -> queueTracks.set(new ArrayList<>(playbackSession.queue)));
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
            if (!pluginId.equals(id.provider()))
                throw new IllegalArgumentException("song provider mismatch");
            return id.toString();
        }
        return MediaId.of(pluginId, dev.t1m3.qplayer.media.MediaKind.SONG, value).toString();
    }

    private static String pluginText(Object raw, int limit) {
        String value = raw == null ? "" : String.valueOf(raw);
        if (value.length() > limit) throw new IllegalArgumentException("plugin text is too long");
        return value;
    }

    private CompletableFuture<Object> pluginSaveFile(String pluginId, Map<String, Object> args) {
        String url = pluginText(args.get("url"), 16 * 1024);
        if (url.isEmpty() || !pluginHostApi.allowsReturnedUrl(pluginId, url)) {
            return failedPluginCall("download URL is outside the plugin network grant");
        }
        String filename = sanitizeDownloadName(pluginText(args.get("filename"), 180));
        String mime = pluginText(args.get("mimeType"), 128);
        Map<String, String> headers = pluginDownloadHeaders(args.get("headers"));
        FileSavePicker picker = fileSavePicker;
        if (picker == null) return failedPluginCall("save dialog is unavailable");
        CompletableFuture<Object> result = new CompletableFuture<>();
        onMain(
                () -> {
                    try {
                        invokeFileSave(
                                        picker,
                                        filename,
                                        mime,
                                        (path, done) -> {
                                            if (path == null || path.trim().isEmpty()) {
                                                done.accept(false);
                                                return;
                                            }
                                            pluginHostApi
                                                    .downloadReturnedFile(
                                                            pluginId,
                                                            url,
                                                            headers,
                                                            java.nio.file.Paths.get(path),
                                                            1024L * 1024L * 1024L,
                                                            120_000)
                                                    .whenComplete(
                                                            (ok, error) ->
                                                                    done.accept(
                                                                            error == null
                                                                                    && Boolean.TRUE
                                                                                            .equals(
                                                                                                    ok)));
                                        })
                                .whenComplete(
                                        (ok, error) -> {
                                            if (error != null) result.completeExceptionally(error);
                                            else result.complete(Boolean.TRUE.equals(ok));
                                        });
                    } catch (RuntimeException error) {
                        result.completeExceptionally(error);
                    }
                });
        return result;
    }

    private static Map<String, String> pluginDownloadHeaders(Object raw) {
        if (!(raw instanceof Map)) return java.util.Collections.emptyMap();
        Map<?, ?> source = (Map<?, ?>) raw;
        Map<String, String> headers = new LinkedHashMap<>();
        int n = 0;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (n++ >= 64) break;
            String key = String.valueOf(entry.getKey());
            String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            if (key.isEmpty() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) continue;
            if (value.length() > 8192) continue;
            headers.put(key, value);
        }
        return headers;
    }

    static String sanitizeDownloadName(String raw) {
        String value = raw == null ? "" : raw.trim();
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == '/' || c == '\\' || c == ':' || c == '*'
                    || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        value = out.toString().replaceAll("\\s+", " ").trim();
        if (value.isEmpty() || value.equals(".") || value.equals("..")) value = "audio";
        if (value.length() > 180) value = value.substring(0, 180).trim();
        return value;
    }

    static String suggestedAudioName(String title, String artist, String url, String mimeType) {
        String name = title == null || title.trim().isEmpty() ? "audio" : title.trim();
        if (artist != null && !artist.trim().isEmpty()) name = name + " - " + artist.trim();
        name = sanitizeDownloadName(name);
        String ext = audioExtension(url, mimeType);
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(ext)) name = name + ext;
        return name;
    }

    /** Tokens accepted by {@code resolveStream} quality. Unknown values fall back
     *  to the current playback level, then to 320 kbps. */
    static String sanitizeDownloadQuality(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (isDownloadQuality(value)) return value;
        String next = fallback == null ? "" : fallback.trim().toLowerCase(java.util.Locale.ROOT);
        return isDownloadQuality(next) ? next : "exhigh";
    }

    private static boolean isDownloadQuality(String value) {
        return "standard".equals(value) || "higher".equals(value) || "exhigh".equals(value)
                || "lossless".equals(value) || "hires".equals(value);
    }

    static String audioExtension(String url, String mimeType) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(java.util.Locale.ROOT);
        if (mime.contains("flac")) return ".flac";
        if (mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac")) return ".m4a";
        if (mime.contains("ogg")) return ".ogg";
        String lower = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        if (lower.endsWith(".flac")) return ".flac";
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) return ".m4a";
        if (lower.endsWith(".ogg")) return ".ogg";
        return ".mp3";
    }

    private CompletableFuture<Object> pluginDownloadCurrent() {
        CompletableFuture<Object> result = new CompletableFuture<>();
        onMain(
                () -> {
                    Track track = playbackSession.current();
                    if (track == null) {
                        result.completeExceptionally(
                                new IllegalStateException("no song is playing"));
                        return;
                    }
                    if (downloadChoiceResult != null) downloadChoiceResult.complete(Boolean.FALSE);
                    downloadChoiceResult = result;
                    downloadChoiceMediaId = track.canonicalId();
                    requestDownloadQuality(track.canonicalId());
                });
        return result;
    }

    private void completeDownloadChoice(CompletableFuture<Boolean> saved) {
        CompletableFuture<Object> waiting = downloadChoiceResult;
        downloadChoiceResult = null;
        downloadChoiceMediaId = "";
        if (waiting == null) return;
        saved.whenComplete(
                (ok, error) -> {
                    if (error != null) waiting.completeExceptionally(error);
                    else waiting.complete(Boolean.TRUE.equals(ok));
                });
    }

    public final Property<Boolean> downloadQualityOpen = new Property<>(false);
    public final Property<String> downloadQualityMediaId = new Property<>("");
    public final Property<String> downloadQualityTitle = new Property<>("");

    /**
     * Open the host quality sheet. qml4j drops JS closures on Menu subItems, so the picker cannot
     * live in the context-menu model.
     */
    public void requestDownloadQuality(String mediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (mediaId == null || mediaId.isEmpty()) return;
            if (downloadChoiceResult != null && !downloadChoiceMediaId.equals(mediaId)) {
                completeDownloadChoice(CompletableFuture.completedFuture(Boolean.FALSE));
            }
            // A playerAction contribution always opens its plugin dialog first.
            // Close it so the host quality sheet is not buried underneath.
            closePluginDialog();
            FileSavePicker picker = fileSavePicker;
            if (picker == null) {
                completeDownloadChoice(CompletableFuture.completedFuture(Boolean.FALSE));
                showToast(I18n.tr("toast.download.unsupported"));
                return;
            }
            Track track = findTrackForMedia(mediaId);
            if (track != null && track.source == Track.Source.LOCAL) {
                String local =
                        track.filePath != null && !track.filePath.isEmpty()
                                ? track.filePath
                                : track.contentUri;
                if (local == null || local.isEmpty()) {
                    completeDownloadChoice(CompletableFuture.completedFuture(Boolean.FALSE));
                    showToast(I18n.tr("toast.download.staleSong"));
                    return;
                }
                completeDownloadChoice(saveLocalAudioCopy(picker, track, local));
                return;
            }
            String title =
                    track != null && track.title != null && !track.title.isEmpty()
                            ? track.title
                            : mediaId;
            downloadQualityMediaId.set(mediaId);
            downloadQualityTitle.set(title);
            downloadQualityOpen.set(true);
        }
    }

    public void confirmDownloadQuality(String quality) {
        synchronized (QmlRuntimeLock.MONITOR) {
            String canonical = downloadQualityMediaId.peek();
            downloadQualityOpen.set(false);
            downloadQualityMediaId.set("");
            downloadQualityTitle.set("");
            completeDownloadChoice(downloadMediaSongAtQuality(canonical, quality));
        }
    }

    public void cancelDownloadQuality() {
        synchronized (QmlRuntimeLock.MONITOR) {
            downloadQualityOpen.set(false);
            downloadQualityMediaId.set("");
            downloadQualityTitle.set("");
            completeDownloadChoice(CompletableFuture.completedFuture(Boolean.FALSE));
        }
    }

    /**
     * Right-click Download: show the quality sheet, then resolve the same stream path playback
     * uses.
     */
    public void downloadMediaSong(String mediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            requestDownloadQuality(mediaId);
        }
    }

    /**
     * Distinct from {@link #downloadMediaSong(String)} so QML can pass a quality token without
     * relying on Java overload resolution.
     */
    public CompletableFuture<Boolean> downloadMediaSongAtQuality(String mediaId, String quality) {
        synchronized (QmlRuntimeLock.MONITOR) {
            FileSavePicker picker = fileSavePicker;
            if (picker == null) {
                showToast(I18n.tr("toast.download.unsupported"));
                return CompletableFuture.completedFuture(Boolean.FALSE);
            }
            final MediaId id;
            try {
                id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.SONG);
            } catch (IllegalArgumentException error) {
                showToast(I18n.tr("toast.id.song"));
                return CompletableFuture.completedFuture(Boolean.FALSE);
            }
            Track known = findTrackForMedia(mediaId);
            if ("local".equals(id.provider())) {
                String local = known != null ? known.filePath : null;
                if (local == null || local.isEmpty()) {
                    showToast(I18n.tr("toast.download.staleSong"));
                    return CompletableFuture.completedFuture(Boolean.FALSE);
                }
                return saveLocalAudioCopy(picker, known, local);
            }
            final Track named = known != null ? copyForQueue(known) : new Track();
            if (known == null) {
                named.applyCanonicalId(mediaId);
                named.source = Track.Source.PLUGIN;
            }
            String level = sanitizeDownloadQuality(quality, playLevel);
            showToast(I18n.tr("toast.download.started"));
            return pluginProviders
                    .resolveStream(id, level)
                    .handle(
                            (stream, error) -> {
                                if (error != null
                                        || stream == null
                                        || stream.url == null
                                        || stream.url.isEmpty()) {
                                    showToast(
                                            I18n.tr(
                                                    "toast.download.failed",
                                                    error != null
                                                            ? safeMessage(error)
                                                            : I18n.tr("toast.download.noStream")));
                                    return CompletableFuture.completedFuture(Boolean.FALSE);
                                }
                                if (stream.trial) {
                                    showToast(I18n.tr("toast.download.trialOnly"));
                                    return CompletableFuture.completedFuture(Boolean.FALSE);
                                }
                                return saveRemoteAudio(
                                        picker,
                                        id,
                                        named,
                                        stream.url,
                                        stream.headers,
                                        stream.mimeType);
                            })
                    .thenCompose(saved -> saved);
        }
    }

    /** Copy a local library file through the same save dialog as plugin downloads. */
    public void downloadLocalFile(String filePath) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (filePath == null || filePath.isEmpty()) return;
            FileSavePicker picker = fileSavePicker;
            if (picker == null) {
                showToast(I18n.tr("toast.download.unsupported"));
                return;
            }
            java.nio.file.Path source = java.nio.file.Paths.get(filePath);
            Track track = new Track();
            track.title = source.getFileName().toString();
            saveLocalAudioCopy(picker, track, filePath);
        }
    }

    private CompletableFuture<Boolean> saveLocalAudioCopy(
            FileSavePicker picker, Track track, String cached) {
        String suggested = suggestedAudioName(track.title, track.artist, cached, "");
        CompletableFuture<Boolean> saved =
                invokeFileSave(
                        picker,
                        suggested,
                        "audio/*",
                        (path, done) ->
                                ioWorker.submit(
                                        () -> {
                                            boolean success = false;
                                            try {
                                                if (path != null && !path.trim().isEmpty()) {
                                                    java.nio.file.Files.copy(
                                                            java.nio.file.Paths.get(cached),
                                                            java.nio.file.Paths.get(path),
                                                            java.nio.file.StandardCopyOption
                                                                    .REPLACE_EXISTING);
                                                    success = true;
                                                }
                                            } catch (Exception error) {
                                                Logger.warn(
                                                        "local audio copy failed: {}",
                                                        safeMessage(error));
                                            } finally {
                                                if (done != null) done.accept(success);
                                            }
                                        }));
        observeFileSave(saved);
        return saved;
    }

    private void observeFileSave(CompletableFuture<Boolean> saved) {
        saved.whenComplete(
                (ok, error) -> {
                    if (error != null)
                        showToast(I18n.tr("toast.download.failed", safeMessage(error)));
                    else if (!Boolean.TRUE.equals(ok))
                        showToast(I18n.tr("toast.download.notSaved"));
                    else showToast(I18n.tr("toast.download.done"));
                });
    }

    private CompletableFuture<Boolean> saveRemoteAudio(
            FileSavePicker picker,
            MediaId id,
            Track track,
            String url,
            Map<String, String> headers,
            String mimeType) {
        String suggested = suggestedAudioName(track.title, track.artist, url, mimeType);
        String mime = mimeType == null || mimeType.isEmpty() ? "audio/*" : mimeType;
        CompletableFuture<Boolean> saved =
                invokeFileSave(
                        picker,
                        suggested,
                        mime,
                        (path, done) -> {
                            if (path == null || path.trim().isEmpty()) {
                                done.accept(false);
                                return;
                            }
                            pluginHostApi
                                    .downloadReturnedFile(
                                            id.provider(),
                                            url,
                                            headers,
                                            java.nio.file.Paths.get(path),
                                            1024L * 1024L * 1024L,
                                            120_000,
                                            true)
                                    .whenCompleteAsync(
                                            (ok, error) -> {
                                                boolean success =
                                                        error == null && Boolean.TRUE.equals(ok);
                                                if (success) embedDownloadTags(id, track, path);
                                                if (done != null) done.accept(success);
                                            },
                                            ioWorker);
                        });
        observeFileSave(saved);
        return saved;
    }

    private void embedDownloadTags(MediaId id, Track track, String path) {
        try {
            byte[] cover = loadCoverBytesForDownload(id, track);
            String lyrics = loadLyricTextForDownload(id);
            metadataReader.writeTags(path,
                    track.title != null ? track.title : "",
                    track.artist != null ? track.artist : "",
                    track.album != null ? track.album : "",
                    cover, lyrics);
        } catch (Throwable error) {
            Logger.warn("download tags failed for {}: {}", path, safeMessage(error));
        }
    }

    private byte[] loadCoverBytesForDownload(MediaId id, Track track) {
        if (track.coverBytes != null && track.coverBytes.length > 0) return track.coverBytes;
        String url = track.coverUrl;
        if (url == null || url.isEmpty()) return null;
        String sized = trackCoverUrl(track, "1024");
        String cached = diskCache.getImage(sized);
        if (cached != null) {
            byte[] data = readBytesFromFile(cached);
            if (data != null && data.length > 0) return data;
        }
        try {
            byte[] data = pluginHostApi.fetchReturnedBytes(id.provider(), sized,
                    java.util.Collections.emptyMap(), 16L * 1024L * 1024L, 8_000)
                    .get(12, java.util.concurrent.TimeUnit.SECONDS);
            if (data != null && data.length > 0) {
                String imgPath = diskCache.imagePath(sized);
                if (imgPath != null) writeBytesToFile(data, imgPath);
                return data;
            }
        } catch (Throwable error) {
            Logger.warn("download cover fetch failed: {}", safeMessage(error));
        }
        return null;
    }

    private String loadLyricTextForDownload(MediaId id) {
        if (!pluginHasCapability(id.provider(), ProviderCapability.LYRICS)) return "";
        try {
            dev.t1m3.qplayer.media.LyricsPayload payload = pluginProviders.lyrics(id)
                    .get(12, java.util.concurrent.TimeUnit.SECONDS);
            return lyricText(payload);
        } catch (Throwable error) {
            Logger.warn("download lyric fetch failed: {}", safeMessage(error));
            return "";
        }
    }

    static String lyricText(dev.t1m3.qplayer.media.LyricsPayload payload) {
        if (payload == null || payload.assets == null) return "";
        String original = "";
        String fallback = "";
        for (dev.t1m3.qplayer.media.LyricsPayload.Asset asset : payload.assets) {
            if (asset == null || asset.text == null || asset.text.isEmpty()) continue;
            String role = asset.role == null ? "original" : asset.role;
            if ("original".equals(role) && original.isEmpty()) original = asset.text;
            else if (fallback.isEmpty()) fallback = asset.text;
        }
        return original.isEmpty() ? fallback : original;
    }

    private CompletableFuture<Object> runWebAuthScript(String pluginId,
                                                       Map<String, Object> arguments) {
        String originUrl = pluginText(arguments.get("originUrl"), 2048);
        String script = pluginText(arguments.get("script"), 256 * 1024);
        if (originUrl.isEmpty() || !pluginHostApi.allowsReturnedUrl(pluginId, originUrl)) {
            throw new SecurityException("web auth URL is outside the plugin network grant");
        }
        if (script.isEmpty()) throw new IllegalArgumentException("web auth script is empty");
        WebAuthScriptLauncher launcher = webAuthScriptLauncher;
        if (launcher == null) return failedPluginCall("system WebView is unavailable");
        CompletableFuture<String> launched;
        try {
            launched = launcher.run(originUrl, script);
        } catch (Throwable error) {
            CompletableFuture<Object> failed = new CompletableFuture<>();
            failed.completeExceptionally(error);
            return failed;
        }
        if (launched == null) return failedPluginCall("system WebView did not start");
        return launched.thenApply(value -> {
            if (value == null) throw new IllegalStateException("system WebView returned null");
            if (value.length() > 64 * 1024) {
                throw new IllegalArgumentException("system WebView result is too long");
            }
            return value;
        });
    }

    private static long boundedPluginLong(Object raw, long min, long max) {
        if (!(raw instanceof Number)) return min;
        long value = ((Number) raw).longValue();
        return Math.max(min, Math.min(max, value));
    }

    private CompletableFuture<Object> onMainResult(java.util.concurrent.Callable<Object> action) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        onMain(
                () -> {
                    try {
                        if (appShuttingDown)
                            throw new IllegalStateException("controller is shutting down");
                        future.complete(action.call());
                    } catch (Throwable error) {
                        future.completeExceptionally(error);
                    }
                });
        return future;
    }

    private static CompletableFuture<Object> failedPluginCall(String message) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        future.completeExceptionally(new UnsupportedOperationException(message));
        return future;
    }

    private void pluginAppUnavailable(String pluginId) {
        onMain(
                () -> {
                    if (!pluginId.equals(pluginAutoAdvanceBlocker)) return;
                    pluginAutoAdvanceBlocker = "";
                    if (!appShuttingDown && pendingNaturalEnd && !playbackSession.queue.isEmpty())
                        performAutoAdvance();
                });
    }

    /**
     * Remember an explicit local-only choice so onboarding is not forced again on every launch.
     * Home keeps a persistent setup button for changing that choice.
     */
    public void dismissSourceSetup() {
        synchronized (QmlRuntimeLock.MONITOR) {
            pluginSetupState.acknowledge();
            sourceSetupPending.set(false);
        }
    }

    private boolean detectLegacySourceMigration() {
        if (netease.hasStoredLegacyCredentials()) return true;
        for (Track track : playbackSession.queue) if (isLegacyOnlineTrack(track)) return true;
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
        synchronized (QmlRuntimeLock.MONITOR) {
            pluginUpdateAvailable.set(false);
            pluginUpdateId.set("");
            pluginUpdateName.set("");
            pluginUpdateVersion.set("");
            pluginUpdateInstalledVersion.set("");
            // Another installed plugin may also be out of date; offer the next one.
            publishPluginUpdatePrompt();
        }
    }

    /**
     * QML calls this when the app updater pre-empts a plugin prompt that was already on screen.
     * Unlike {@link #acknowledgePluginUpdate()} this does not consume the offer: the key is
     * released so the same plugin is proposed again once the app dialog is gone.
     */
    public void deferPluginUpdate() {
        synchronized (QmlRuntimeLock.MONITOR) {
            promptedPluginUpdates.remove(pluginUpdateId.peek() + "@" + pluginUpdateVersion.peek());
            pluginUpdateAvailable.set(false);
        }
    }

    /** QML calls this when the app-update dialog closes, releasing the hold. */
    public void appUpdatePromptClosed() {
        synchronized (QmlRuntimeLock.MONITOR) {
            appUpdatePromptDismissed = true;
            publishPluginUpdatePrompt();
        }
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
        synchronized (QmlRuntimeLock.MONITOR) {
            if (Boolean.TRUE.equals(pluginCatalogLoading.peek())) return;
            pluginCatalogLoading.set(true);
            worker.submit(
                    () -> {
                        try {
                            List<PluginCatalogEntry> entries =
                                    pluginCatalog.loadLatest(updateMirror);
                            markCatalogInstalledState(entries);
                            final List<PluginCatalogEntry> publishedEntries = entries;
                            post(
                                    () -> {
                                        pluginCatalogEntries.set(publishedEntries);
                                        pluginCatalogLoading.set(false);
                                        publishPluginUpdatePrompt();
                                    });
                        } catch (Throwable error) {
                            post(() -> pluginCatalogLoading.set(false));
                            Logger.warn("plugin release lookup failed: {}", error.getMessage());
                            showToast(I18n.tr("toast.plugin.catalogFailed"));
                        }
                    });
        }
    }

    public void installCatalogPlugin(String pluginId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (pluginId == null
                    || pluginId.isEmpty()
                    || Boolean.TRUE.equals(pluginInstallBusy.peek())) return;
            PluginCatalogEntry selected = null;
            for (PluginCatalogEntry entry : pluginCatalogEntries.peek()) {
                if (pluginId.equals(entry.id)) {
                    selected = entry;
                    break;
                }
            }
            if (selected == null) {
                showToast(I18n.tr("toast.plugin.unknownEntry"));
                return;
            }
            final PluginCatalogEntry entry = selected;
            pluginInstallBusy.set(true);
            worker.submit(
                    () -> {
                        try {
                            VerifiedPluginPackage verified =
                                    pluginCatalog.downloadAndVerify(
                                            entry,
                                            GitHubDownloadUrls.candidates(
                                                    entry.downloadUrl, updateMirror));
                            pendingPluginPackage = verified;
                            pendingPluginPackageTemporary = true;
                            post(
                                    () -> {
                                        pendingPluginName.set(verified.manifest().name);
                                        pendingPluginId.set(verified.manifest().id);
                                        pendingPluginVersion.set(verified.manifest().version);
                                        pendingPluginPermissions.set(
                                                verified.manifest().permissions.isEmpty()
                                                        ? I18n.tr("plugin.entry.noPermissions")
                                                        : String.join(
                                                                I18n.tr("common.listSeparator"),
                                                                verified.manifest().permissions));
                                        pendingPluginTrusted.set(true);
                                        pluginInstallBusy.set(false);
                                        pluginInstallPromptRevision.set(
                                                pluginInstallPromptRevision.peek() + 1L);
                                    });
                        } catch (Throwable error) {
                            post(() -> pluginInstallBusy.set(false));
                            showToast(I18n.tr("toast.plugin.downloadFailed", safeMessage(error)));
                        }
                    });
        }
    }

    /**
     * Delayed, non-destructive legacy migration: old queue records already carry canonical ids
     * after schema-v2 loading, but remain on their compatibility route until a matching provider is
     * actually installed and enabled. Once it is available, route them through the plugin without
     * rewriting or deleting the old state file first. The next normal atomic save records PLUGIN.
     */
    private void routeInstalledPluginTracks() {
        boolean changed = false;
        for (Track track : playbackSession.queue) changed |= routeInstalledPluginTrack(track);
        if (changed) {
            queueTracks.set(new ArrayList<>(playbackSession.queue));
            saveQueue();
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

    private List<PluginManifest> providersWith(ProviderCapability capability) {
        List<PluginManifest> out = new ArrayList<>();
        String primary = pluginRegistry.primaryProvider();
        for (PluginManifest manifest : pluginManager.enabledProviders()) {
            if (manifest.capabilitySet().contains(capability)) out.add(manifest);
        }
        out.sort((a, b) -> {
            if (a.id.equals(primary)) return b.id.equals(primary) ? 0 : -1;
            if (b.id.equals(primary)) return 1;
            return 0;
        });
        return out;
    }

    /** Album/artist search used to query only the primary source. A primary
     *  without SEARCH_ALBUMS/SEARCH_ARTISTS then returned an empty page even
     *  though another enabled plugin could answer — SearchPage looked broken. */
    private void searchPluginCatalog(List<PluginManifest> providers, String query, String key,
                                     ProviderCapability capability) {
        final long generation = pluginSearchGeneration.incrementAndGet();
        AtomicInteger pending = new AtomicInteger(providers.size());
        List<Album> albums = capability == ProviderCapability.SEARCH_ALBUMS
                ? Collections.synchronizedList(new ArrayList<>()) : null;
        List<Artist> artists = capability == ProviderCapability.SEARCH_ARTISTS
                ? Collections.synchronizedList(new ArrayList<>()) : null;
        for (PluginManifest manifest : providers) {
            CompletableFuture<?> request = capability == ProviderCapability.SEARCH_ALBUMS
                    ? pluginProviders.searchAlbums(manifest.id, query, "", SEARCH_PAGE_SIZE)
                    : pluginProviders.searchArtists(manifest.id, query, "", SEARCH_PAGE_SIZE);
            request.whenComplete((page, error) -> post(() -> {
                if (generation != pluginSearchGeneration.get() || !key.equals(currentSearchKey)) return;
                if (error != null) {
                    Logger.warn("plugin {} {} search failed: {}", manifest.id,
                            capability.wireName(), safeMessage(error));
                } else if (page instanceof Page) {
                    @SuppressWarnings("unchecked")
                    Page<?> typed = (Page<?>) page;
                    if (typed.items != null) {
                        if (albums != null) for (Object item : typed.items) albums.add((Album) item);
                        if (artists != null) for (Object item : typed.items) artists.add((Artist) item);
                    }
                }
                if (pending.decrementAndGet() > 0) return;
                searchLoading.set(false);
                if (albums != null) {
                    List<Album> results = Collections.unmodifiableList(new ArrayList<>(albums));
                    sourceSearchAlbumResults.set(results);
                    resultCount.set(results.size());
                }
                if (artists != null) {
                    List<Artist> results = Collections.unmodifiableList(new ArrayList<>(artists));
                    sourceSearchArtistResults.set(results);
                    resultCount.set(results.size());
                }
            }));
        }
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
            pluginAppMethodId = "";
            pluginCredentialMethodId = "";
            loginProviderName.set(I18n.tr("login.defaultProvider"));
            pluginLoginActive.set(false);
            loginMethods.set(Collections.<LoginMethod>emptyList());
            pluginQrLoginAvailable.set(false);
            pluginAppLoginAvailable.set(false);
            pluginCredentialLoginAvailable.set(false);
            webLoginAvailable.set(false);
            publishAppLink("", "");
            // Nothing can own the account header now, so it must not keep showing the
            // previous source's user (the "logged into a source I just switched away
            // from" state that made 我的/首页 disagree with the selected source).
            clearPublishedPluginAccount();
            return;
        }
        final PluginManifest provider = selected;
        // A persisted session is what the account header should show; ask for it
        // independently so a failed/slow methods() can't leave the user "logged out"
        // until the next restart.
        refreshPluginAccount(provider.id, null);
        publishLoginMetadata(provider);
    }

    /**
     * Point the login surface (the scalars LoginDialog reads, and the provider
     * startQrLogin/submit act on) at one source. Normally that is the primary
     * source; the account page also aims it at a specific source so each listed
     * account can be signed in on its own.
     */
    private void publishLoginMetadata(PluginManifest provider) {
        final long generation = loginMetadataGeneration.incrementAndGet();
        loginProviderName.set(provider.name);
        pluginLoginActive.set(true);
        // Claim the login provider before the metadata round trip: restoring a stored
        // session (and logging out of it) must not depend on that call succeeding.
        pendingPluginLoginProvider = provider.id;
        pluginAccounts.methods(provider.id, hostPlatform).whenComplete((methods, error) -> post(() -> {
            // Another source claimed the login surface while this was in flight.
            if (generation != loginMetadataGeneration.get()) return;
            if (error != null) {
                Logger.warn("plugin {} login metadata failed: {}", provider.id, safeMessage(error));
                return;
            }
            LoginMethod qr = null, web = null, credential = null, app = null;
            for (LoginMethod method : methods) {
                if ("qr".equals(method.type) && qr == null) qr = method;
                else if ("web".equals(method.type) && web == null) web = method;
                else if ("credential".equals(method.type) && credential == null) credential = method;
                else if ("app".equals(method.type) && app == null) app = method;
            }
            pluginQrMethodId = qr != null ? qr.id : "";
            pluginWebMethodId = web != null ? web.id : "";
            pluginAppMethodId = app != null ? app.id : "";
            pluginCredentialMethodId = credential != null ? credential.id : "";
            activeWebLoginMethod = web;
            loginProviderName.set(provider.name);
            loginMethods.set(methods);
            pluginQrLoginAvailable.set(qr != null);
            pluginAppLoginAvailable.set(app != null && appLinkLauncher != null);
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
            if (app != null) {
                loginAppLabel.set(app.label);
                loginAppButtonLabel.set(!app.appLabel.isEmpty() ? app.appLabel : app.label);
                if (!app.instructions.isEmpty()) loginAppInstructions.set(app.instructions);
            } else {
                loginAppLabel.set("");
                loginAppButtonLabel.set(I18n.tr("login.app.open"));
                loginAppInstructions.set(I18n.tr("login.app.instructions"));
            }
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
                    showToast(I18n.tr("toast.credential.migrated"));
                }));
    }

    @FunctionalInterface
    public interface PluginPicker { void pick(); }

    public void setPluginPicker(PluginPicker picker) {
        synchronized (QmlRuntimeLock.MONITOR) {
            this.pluginPicker = picker;
        }
    }

    /** Host-owned save dialog. The plugin never sees a filesystem path. */
    @FunctionalInterface
    public interface FileSavePicker {
        CompletableFuture<Boolean> save(
                String suggestedFileName,
                String mimeType,
                java.util.function.BiConsumer<String, java.util.function.Consumer<Boolean>> writer);
    }

    public void setFileSavePicker(FileSavePicker picker) {
        synchronized (QmlRuntimeLock.MONITOR) {
            this.fileSavePicker = picker;
        }
    }

    // --- Plugin dialogs -----------------------------------------------------
    // A plugin contributes no QML. It returns a validated description from its
    // ui.<contribution> handler and QPlayer renders it with md3.Core, so the
    // dialog follows the app theme and no third-party document is ever loaded.
    // Every action (open, refresh, a button id) re-invokes the handler and
    // replaces the description — the plugin owns the state, the host owns the
    // pixels.

    /** True while a plugin dialog is on screen. */
    public final Property<Boolean> pluginDialogOpen = new Property<>(false);

    public final Property<Long> pluginDialogSessionRevision = new Property<>(0L);

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
        synchronized (QmlRuntimeLock.MONITOR) {
            if (pluginId == null || contributionId == null) return;
            if ("unlock".equals(pluginId) && "download".equals(contributionId)) {
                Track current = currentTrack();
                if (current == null
                        || current.canonicalId() == null
                        || current.canonicalId().isEmpty()) {
                    showToast(I18n.tr("toast.download.noCurrent"));
                    return;
                }
                requestDownloadQuality(current.canonicalId());
                return;
            }
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
            pluginDialogSessionRevision.set(pluginDialogSessionRevision.peek() + 1L);
            pluginDialogOpen.set(true);
            invokePluginDialog("open", Collections.<String, Object>emptyMap(), true);
        }
    }

    /** A button press: {@code actionId} is the button's own id. */
    public void pluginDialogAction(String actionId, String inputsJson) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (actionId == null || actionId.isEmpty()) return;
            Map<String, Object> inputs;
            try {
                Map<String, Object> parsed =
                        new Gson()
                                .fromJson(
                                        inputsJson == null || inputsJson.isEmpty()
                                                ? "{}"
                                                : inputsJson,
                                        new com.google.gson.reflect.TypeToken<
                                                java.util.LinkedHashMap<
                                                        String, Object>>() {}.getType());
                inputs = parsed != null ? parsed : Collections.<String, Object>emptyMap();
            } catch (RuntimeException error) {
                inputs = Collections.emptyMap();
            }
            invokePluginDialog(actionId, inputs, true);
        }
    }

    /** Driven by the description's refreshMs; never blocks the buttons. */
    public void pluginDialogRefresh() {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (Boolean.TRUE.equals(pluginDialogBusy.peek())) return;
            invokePluginDialog("refresh", Collections.<String, Object>emptyMap(), false);
        }
    }

    public void closePluginDialog() {
        synchronized (QmlRuntimeLock.MONITOR) {
            dialogPluginId = "";
            dialogContributionId = "";
            pluginDialogOpen.set(false);
            pluginDialogJson.set("");
            pluginDialogError.set("");
            pluginDialogBusy.set(false);
        }
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
        synchronized (QmlRuntimeLock.MONITOR) {
            PluginPicker picker = pluginPicker;
            if (picker == null) {
                showToast(I18n.tr("toast.plugin.pickUnsupported"));
                return;
            }
            onHost(picker::pick);
        }
    }

    /** Inspect on a worker before QML presents the mandatory code-execution warning. */
    public void inspectPluginPackage(String path) {
        synchronized (QmlRuntimeLock.MONITOR) {
            inspectPluginPackage(path, false);
        }
    }

    /** Host-only variant for a package copied out of a content URI. */
    public void inspectTemporaryPluginPackage(String path) {
        synchronized (QmlRuntimeLock.MONITOR) {
            inspectPluginPackage(path, true);
        }
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
                        ? I18n.tr("plugin.entry.noPermissions")
                        : String.join(I18n.tr("common.listSeparator"),
                                verified.manifest().permissions);
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
                showToast(I18n.tr("toast.plugin.invalidPackage", error.getMessage()));
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
        synchronized (QmlRuntimeLock.MONITOR) {
            final VerifiedPluginPackage verified = pendingPluginPackage;
            if (verified == null || Boolean.TRUE.equals(pluginInstallBusy.peek())) return;
            pluginInstallBusy.set(true);
            worker.submit(
                    () -> {
                        try {
                            Set<PluginPermission> grants = verified.manifest().permissionSet();
                            pluginManager.installAndEnable(pluginInstaller, verified, grants);
                            pendingPluginPackage = null;
                            post(
                                    () -> {
                                        pluginInstallBusy.set(false);
                                        publishPlugins();
                                    });
                            showToast(I18n.tr("toast.plugin.installed", verified.manifest().name));
                        } catch (Throwable error) {
                            pendingPluginPackage = null;
                            post(() -> pluginInstallBusy.set(false));
                            showToast(I18n.tr("toast.plugin.installFailed", error.getMessage()));
                        } finally {
                            if (pendingPluginPackageTemporary) {
                                try {
                                    java.nio.file.Files.deleteIfExists(verified.file());
                                } catch (java.io.IOException ignored) {
                                }
                                pendingPluginPackageTemporary = false;
                            }
                        }
                    });
        }
    }

    public void cancelPendingPluginInstall() {
        synchronized (QmlRuntimeLock.MONITOR) {
            VerifiedPluginPackage previous = pendingPluginPackage;
            pendingPluginPackage = null;
            if (pendingPluginPackageTemporary && previous != null) {
                try {
                    java.nio.file.Files.deleteIfExists(previous.file());
                } catch (java.io.IOException ignored) {
                }
            }
            pendingPluginPackageTemporary = false;
            pendingPluginName.set("");
            pendingPluginId.set("");
            pendingPluginVersion.set("");
            pendingPluginPermissions.set("");
            pendingPluginTrusted.set(false);
        }
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
        synchronized (QmlRuntimeLock.MONITOR) {
            showToast(I18n.tr("toast.keystore.waitingUnlock"));
            worker.submit(
                    () -> {
                        if (pluginHostApi.retryCredentialUnlock()) {
                            String provider = pluginRegistry.primaryProvider();
                            if (provider != null && !provider.isEmpty())
                                refreshPluginAccount(provider, null);
                        }
                    });
        }
    }

    /** Abandon the inaccessible envelope and persistently use owner-only encryption. */
    public boolean fallbackCredentialsToOwnerOnly() {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (!pluginHostApi.fallbackUnreadableCredentials()) return false;
            credentialOwnerOnlyFallback.set(true);
            clearAccountStateAfterCredentialReset();
            return true;
        }
    }

    /** Discard unreadable credentials and retry system-store encryption on login. */
    public void prepareEncryptedRelogin() {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (credentialReloginBusy) return;
            credentialReloginBusy = true;
            showToast(I18n.tr("toast.keystore.checking"));
            worker.submit(
                    () -> {
                        boolean ready = pluginHostApi.resetUnreadableCredentialsForPlatformLogin();
                        post(
                                () -> {
                                    credentialReloginBusy = false;
                                    if (ready) {
                                        credentialOwnerOnlyFallback.set(false);
                                        clearAccountStateAfterCredentialReset();
                                    }
                                    credentialReloginResult.set(ready ? 1 : 2);
                                    credentialReloginRevision.set(
                                            credentialReloginRevision.peek() + 1L);
                                });
                    });
        }
    }

    private void clearAccountStateAfterCredentialReset() {

        loggedIn.set(false);
        userName.set("");
        userAvatar.set("");
        userVipType.set(0);
        userLevel.set(0);
        userSignature.set("");

        likedCount.set(0);
        likedRevision.set(likedRevision.peek() + 1);
        playlistCount.set(0);
        myPlaylists.set(Collections.<NeteasePlaylist>emptyList());
        recommendations.set(Collections.<NeteaseSong>emptyList());
        recentSongs.set(Collections.<NeteaseSong>emptyList());
    }

    /** User-triggered migration from owner-only storage back to the system store. */
    public void reenableSystemCredentialProtection() {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (Boolean.TRUE.equals(credentialProtectionBusy.peek())) return;
            credentialProtectionBusy.set(true);
            showToast(I18n.tr("toast.keystore.waiting"));
            worker.submit(
                    () -> {
                        boolean enabled = pluginHostApi.enableSystemCredentialProtection();
                        post(
                                () -> {
                                    credentialOwnerOnlyFallback.set(
                                            pluginHostApi.usesOwnerOnlyCredentialProtection());
                                    credentialProtectionBusy.set(false);
                                    if (!enabled) showToast(I18n.tr("toast.keystore.enableFailed"));
                                });
                    });
        }
    }

    /** Platform color extractor for Monet seeds; set once at startup. */
    public void setColorExtractor(ColorExtractor extractor) {
        synchronized (QmlRuntimeLock.MONITOR) {
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
    }

    /**
     * Platform clipboard sink (copies text to the system clipboard), set at startup. The shell is
     * responsible for putting the write on the right thread.
     */
    public void setClipboard(java.util.function.Consumer<String> sink) {
        synchronized (QmlRuntimeLock.MONITOR) {
            this.clipboard = sink;
        }
    }

    public boolean allowRemoteQmlResource(String url) {
        synchronized (QmlRuntimeLock.MONITOR) {
            return pluginHostApi.allowsAnyReturnedUrl(url);
        }
    }

    @FunctionalInterface
    public interface WebLoginLauncher {
        void launch(String loginUrl, String cookieUrl, String credentialCookieName,
                    String providerName);
    }

    @FunctionalInterface
    public interface WebAuthScriptLauncher {
        CompletableFuture<String> run(String originUrl, String script);
    }

    /** Install the shell's bounded system-WebView JavaScript bridge. */
    public void setWebAuthScriptLauncher(WebAuthScriptLauncher launcher) {
        synchronized (QmlRuntimeLock.MONITOR) {
            this.webAuthScriptLauncher = launcher;
        }
    }

    /** Install the shell's in-process system WebView login launcher. */
    public void setWebLoginLauncher(WebLoginLauncher launcher) {
        synchronized (QmlRuntimeLock.MONITOR) {
            this.webLoginLauncher = launcher;
            webLoginAvailable.set(
                    launcher != null
                            && (pendingPluginLoginProvider.isEmpty()
                                    || !pluginWebMethodId.isEmpty()));
        }
    }

    /** "android" or "desktop". Plugins only advertise {@code app} login on Android. */
    public void setHostPlatform(String platform) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (platform != null && !platform.isEmpty()) hostPlatform = platform;
        }
    }

    @FunctionalInterface
    public interface AppLinkLauncher {
        boolean open(String url);
    }

    /** Install the shell's handler for confirming a login in another app. */
    public void setAppLinkLauncher(AppLinkLauncher launcher) {
        synchronized (QmlRuntimeLock.MONITOR) {
            this.appLinkLauncher = launcher;
            pluginAppLoginAvailable.set(launcher != null && !pluginAppMethodId.isEmpty());
            loginAppOpenAvailable.set(launcher != null && !loginAppUrl.peek().isEmpty());
        }
    }

    /** Copy a canonical reference for a delayed-migration numeric song row. */
    public void copySongLink(long songId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (songId == 0) return;
            String url = "netease:song:" + songId;
            java.util.function.Consumer<String> c = clipboard;
            if (c != null) {
                c.accept(url);
                showToast(I18n.tr("toast.linkCopied"));
            } else {
                showToast(url);
            }
        }
    }

    /** Copy a canonical reference for a delayed-migration numeric playlist row. */
    public void copyPlaylistLink(long playlistId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (playlistId == 0) return;
            String url = "netease:playlist:" + playlistId;
            java.util.function.Consumer<String> c = clipboard;
            if (c != null) {
                c.accept(url);
                showToast(I18n.tr("toast.linkCopied"));
            } else {
                showToast(url);
            }
        }
    }

    // --- Volume fade in/out -------------------------------------------------

    /**
     * Start a fade-in from silence to the user's set volume. Called from the backend's onStarted,
     * i.e. once playback of the (possibly async-resolved) source has actually begun -- not from
     * playAt() itself, so the ramp's full duration is real audible time regardless of how long
     * resolving the source took.
     */
    private void startFadeIn() {
        fadeOutDoneForTrack = false;
        if (!playbackSession.playing) {
            cancelFadeAtGain(1f);
            backend.pause();
            return;
        }
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
        long request = playbackSession.request();
        Track track = playbackSession.current();
        backend.setOnError(sourceCallback(request, track, this::onPlaybackError));
        backend.setOnComplete(sourceCallback(request, track, this::autoAdvance));
        backend.setOnStarted(sourceCallback(request, track, this::onPlaybackStarted));
        backend.play(
                source,
                headers != null ? headers : Collections.<String, String>emptyMap(),
                startMs);
        bindGrowingCacheForCurrent();
    }

    private Runnable sourceCallback(long request, Track track, Runnable action) {
        return () ->
                onMain(
                        () -> {
                            if (!appShuttingDown && playbackSession.accepts(request, track))
                                action.run();
                        });
    }

    private void onPlaybackStarted() {
        if (appShuttingDown || playbackSession.resolving) return;
        errorRetryMediaId = "";
        consecutivePlaybackFailures = 0;
        stoppedLyricPositionMs = Math.max(0L, backend.position());
        playbackStarted = true;
        post(() -> loading.set(false));
        // User pause can land while prepareAsync is still in flight. Fade-out
        // defers backend.pause(), so wantPlay stays true and this callback still
        // fires. Honour playingIntent here: otherwise startFadeIn() bumps
        // fadeGeneration and silently drops the deferred pause.
        if (!playbackSession.playing) {
            cancelFadeAtGain(1f);
            backend.pause();
            post(() -> playing.set(false));
            notifyPlayback();
            return;
        }
        notifyPlayback();
        startFadeIn();
    }

    private void bindGrowingCacheForCurrent() {
        Track t = currentTrack();
        if (t == null) {
            backend.bindGrowingCache(null, null, 0L);
            return;
        }
        String pending;
        String complete;
        if (t.source == Track.Source.PLUGIN) {
            pending = diskCache.audioPendingPath(t.canonicalId());
            complete = diskCache.audioPath(t.canonicalId());
        } else if (t.source == Track.Source.NETEASE && t.neteaseId != 0) {
            pending = diskCache.audioPendingPath(t.neteaseId);
            complete = diskCache.audioPath(t.neteaseId);
        } else {
            backend.bindGrowingCache(null, null, 0L);
            return;
        }
        backend.bindGrowingCache(pending, complete, t.durationMs);
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
        synchronized (QmlRuntimeLock.MONITOR) {
            synchronized (QmlRuntimeLock.MONITOR) {
                pumpLocked();
            }
        }
    }

    private void pumpLocked() {
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
        synchronized (QmlRuntimeLock.MONITOR) {
            this.logVisible = visible;
            if (visible) lastLogVersion = -1;
        }
    }

    /**
     * Hosts gate expensive per-frame diagnostics (Android's 120-frame summary) on this so a closed
     * overlay never hits logcat / the ring buffer.
     */
    public boolean isLogVisible() {
        synchronized (QmlRuntimeLock.MONITOR) {
            return logVisible;
        }
    }

    /**
     * Zero the one-shot event counters QML watches, for a host that is about to build a fresh scene
     * over a controller that outlived the previous one (Android keeps this instance in a static
     * across Activity recreations so backgrounded playback stays connected).
     *
     * <p>Each of these properties is an event channel, not state: QML mirrors it into a {@code
     * property real ...Watch} and acts in the Changed handler. A rebuilt scene's binding therefore
     * initialises to whatever count the previous scene already consumed, which reads as a change
     * from the property's 0 default and replays the last prompt -- during construction, before the
     * scene has been parented or laid out. Every watcher ignores 0, so resetting is invisible to a
     * scene that is still attached.
     *
     * <p>Call this before the new scene is created, never from a secondary scene (the desktop lyric
     * window) that shares the same controller.
     */
    public void resetSceneEventRevisions() {
        synchronized (QmlRuntimeLock.MONITOR) {
            pluginInstallPromptRevision.set(0L);
            pluginRemovalPromptRevision.set(0L);
            pluginSettingsRevision.set(0L);
            sourceSetupRevision.set(0L);
            pageNavigationRevision.set(0L);
            credentialNoticeRevision.set(0L);
            credentialReloginRevision.set(0L);
            webLoginSuccessRevision.set(0L);
            debugRouteRevision.set(0L);
            // The install prompt is the only one of these holding work in progress.
            // With its event channel zeroed nothing can ever confirm it again, and
            // the host may already have swept the package out of the cache, so let
            // it go rather than leaving a reference to a file that is gone.
            cancelPendingPluginInstall();
        }
    }

    /** Host debug automation (adb broadcast). QML watches the revision and
     *  applies {@link #debugRouteType}/{@link #debugRouteId}. */
    public final Property<String> debugRouteType = new Property<>("");
    public final Property<String> debugRouteId = new Property<>("");
    public final Property<Long> debugRouteRevision = new Property<>(0L);

    /** Drive navigation/playback from a host debug channel without tapping QML. */
    public void debugCommand(String command, String arg) {
        synchronized (QmlRuntimeLock.MONITOR) {
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
    }

    public String debugStatus() {
        synchronized (QmlRuntimeLock.MONITOR) {
            List<Song> recs = sourceRecommendations.peek();
            List<Playlist> playlists = sourceRecommendPlaylists.peek();
            return "playing="
                    + playing.peek()
                    + " loggedIn="
                    + loggedIn.peek()
                    + " setup="
                    + sourceSetupRequired.peek()
                    + " title="
                    + title.peek()
                    + " queue="
                    + playbackSession.queue.size()
                    + " recs="
                    + (recs == null ? 0 : recs.size())
                    + " playlists="
                    + (playlists == null ? 0 : playlists.size())
                    + " homeLoading="
                    + homeLoading.peek()
                    + " lyrics="
                    + lyricsOpen.peek();
        }
    }

    private void pushDebugRoute(String type, String id) {
        post(
                () -> {
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

    private void applyLyricsIfCurrent(Track t, List<LyricLine> ly) {
        onMain(
                () -> {
                    if (isCurrentTrack(t)) applyLyrics(ly);
                });
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
        // Direct Property.set does not go through post(), so Android WHEN_DIRTY
        // never sees a wake. Compact hosts (overlay) read peek() immediately;
        // the full lyric page still needs a frame to re-feed LyricRenderer.
        wakeRenderer();
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
        int idx = -1;
        if (ly != null && !ly.isEmpty()) {
            for (int i = 0; i < ly.size(); i++) {
                if (ly.get(i).startMs() <= pos) idx = i;
                else break;
            }
        }
        if (idx != lyricIndex.peek()) lyricIndex.set(idx);
    }

    private volatile Runnable renderWake;

    /** Android GLSurfaceView uses this to leave WHEN_DIRTY when a host task lands. */
    public void setRenderWake(Runnable wake) {
        synchronized (QmlRuntimeLock.MONITOR) {
            this.renderWake = wake;
        }
    }

    /** A retiring scene must not clear a replacement scene's wake callback. */
    public void clearRenderWake(Runnable expected) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (renderWake == expected) renderWake = null;
        }
    }

    private void post(Runnable r) {
        uiQueue.add(r);
        wakeRenderer();
    }

    private void wakeRenderer() {
        Runnable wake = renderWake;
        if (wake != null) wake.run();
    }

    public void clearLog() {
        synchronized (QmlRuntimeLock.MONITOR) {
            Logger.clear();
        }
    }

    public void setWideLayout(boolean wide) {
        synchronized (QmlRuntimeLock.MONITOR) {
            wideLayout.set(wide);
        }
    }

    /**
     * Physical pixels per QML logical pixel, pushed by each shell (GLFW window content scale on
     * desktop, display density on Android). QML is authored in logical units and qml4j does NOT
     * scale Image.sourceSize by it, so anything that decodes to a display size has to multiply by
     * this itself or it decodes at 1x and gets upscaled into a blurry draw -- see CoverImage.qml.
     */
    public void setPixelRatio(double ratio) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (ratio > 0 && ratio != pixelRatio.peek()) pixelRatio.set(ratio);
        }
    }

    /**
     * Rewrites each album's coverThumbPath to {@link #gridCoverSize()} -- NeteaseClient.parseAlbum
     * bakes in a fixed 128px thumb (it has no layout awareness of its own), which looked small/soft
     * in an AlbumCard grid tile (ArtistDetailPage's album row, album search results) that's well
     * over 128px on most layouts.
     */
    public void setRootInputBlocked(boolean blocked) {
        synchronized (QmlRuntimeLock.MONITOR) {
            rootInputBlocked.set(blocked);
        }
    }

    public void setLyricsOpen(boolean open) {
        synchronized (QmlRuntimeLock.MONITOR) {
            lyricsOpen.set(open);
            // Closing the lyric page (Esc / Android back / the collapse button all funnel
            // here) must also drop the offset panel's gesture-suppression flag — otherwise
            // it stays stuck true and the lyric body's tap-to-seek never re-arms next time.
            if (!open) lyricOffsetPanelOpen.set(false);
        }
    }

    public void setTemperaOpen(boolean open) {
        synchronized (QmlRuntimeLock.MONITOR) {
            temperaOpen.set(open);
        }
    }

    public void setLyricOffsetPanelOpen(boolean open) {
        synchronized (QmlRuntimeLock.MONITOR) {
            lyricOffsetPanelOpen.set(open);
        }
    }

    /** Set from the fixed-step lyric-page slider. */
    public void setLyricOffset(int valueMs) {
        synchronized (QmlRuntimeLock.MONITOR) {
            Track track = currentTrack();
            if (track == null) return;
            int snapped = Math.round(valueMs / 50f) * 50;
            setCurrentLyricOffset(track, Math.max(-5000, Math.min(5000, snapped)));
        }
    }

    public void resetLyricOffset() {
        synchronized (QmlRuntimeLock.MONITOR) {
            Track track = currentTrack();
            if (track != null) setCurrentLyricOffset(track, 0);
        }
    }

    /** LyricOverlay.qml's manual lyrics/cover switch — see {@link #coverModeManual}. */
    public void setCoverMode(boolean on) {
        synchronized (QmlRuntimeLock.MONITOR) {
            coverModeManual.set(on);
        }
    }

    public void setQueueOpen(boolean open) {
        synchronized (QmlRuntimeLock.MONITOR) {
            queueOpen.set(open);
        }
    }

    /**
     * Closes the artist/album drill-in pages (Main.qml's back handling and its "opening something
     * else replaces the current overlay" resets); opening them back up is {@link
     * #openArtist}/{@link #openAlbum} themselves.
     */
    public void setArtistPageOpen(boolean open) {
        synchronized (QmlRuntimeLock.MONITOR) {
            artistPageOpen.set(open);
        }
    }

    public void setAlbumPageOpen(boolean open) {
        synchronized (QmlRuntimeLock.MONITOR) {
            albumPageOpen.set(open);
        }
    }

    /**
     * SearchPage's type dropdown. Only sets the mode + clears the other kinds' stale results -- the
     * caller (QML) re-issues the actual search itself so this doesn't need to remember the current
     * query text.
     */
    public void setSearchMode(String mode) {
        synchronized (QmlRuntimeLock.MONITOR) {
            searchMode.set(mode);
            searchAlbumResults.set(Collections.<NeteaseAlbum>emptyList());
            searchArtistResults.set(Collections.<NeteaseArtist>emptyList());
            searchResults.set(Collections.<NeteaseSong>emptyList());
            localSearchResults.set(Collections.<Track>emptyList());
            rebuildSearchRows();
        }
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
        synchronized (QmlRuntimeLock.MONITOR) {
            this.exitListener = l;
        }
    }

    /** Host calls this on a back press; routed to QML via {@link #backTick}. */
    public void pressBack() {
        synchronized (QmlRuntimeLock.MONITOR) {
            post(() -> backTick.set(backTick.peek() + 1));
        }
    }

    /** Invoked from QML when no overlay/page consumed the back press. */
    public void requestExit() {
        ExitListener listener;
        synchronized (QmlRuntimeLock.MONITOR) {
            listener = exitListener;
        }
        if (listener != null) onHost(listener::onExit);
    }

    // --- App update check --------------------------------------------------
    // On startup the host calls checkForUpdate(); we GET the latest GitHub
    // release, compare its tag against the running version, and (if newer) expose
    // the version + release notes + download url so QML pops an update dialog.

    /** When true, application and plugin GitHub downloads prefer proxy URLs. */
    private volatile boolean updateMirror = false;

    /** Toggle the GitHub download mirror (driven by the settings switch). */
    public void setUpdateMirror(boolean enabled) {
        synchronized (QmlRuntimeLock.MONITOR) {
            this.updateMirror = enabled;
        }
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

    private final ReleaseUpdateChecker releaseUpdateChecker = new ReleaseUpdateChecker();

    /**
     * Desktop hosts call this at startup with an OS-specific matcher (installer .exe / .dmg /
     * .AppImage) so {@link #checkForUpdate} finds their own asset instead of never matching
     * anything (there is no .apk in a desktop release).
     */
    public void setAssetMatcher(AssetMatcher m) {
        synchronized (QmlRuntimeLock.MONITOR) {
            this.assetMatcher = m != null ? m : (name -> name.toLowerCase().endsWith(".apk"));
        }
    }

    /** True once a newer release than the running version is found; QML watches it
     *  to pop the update dialog. */
    public final Property<Boolean> updateAvailable = new Property<>(false);
    /** Bumped every time a newer release is published, including when
     *  {@link #updateAvailable} was already true. qml4j skips Property
     *  notifications on an unchanged value, so a dismissed dialog would
     *  otherwise never reopen from Settings > 检查更新. */
    public final Property<Integer> updatePromptRevision = new Property<>(0);
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
        synchronized (QmlRuntimeLock.MONITOR) {
            this.urlOpener = o;
        }
    }

    /** Host hook to download an APK in-app and launch the system package installer.
     *  Receives candidate urls (mirror-prefixed, then direct) to try in order. */
    public interface Installer {
        void downloadAndInstall(String[] urls);
    }

    private volatile Installer installer;

    public void setInstaller(Installer i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            this.installer = i;
        }
    }

    /** In-app update download progress: -1 idle, 0..100 downloading, 100 handing off
     *  to the installer, -2 failed. QML shows it and the host drives it. */
    public final Property<Integer> updateProgress = new Property<>(-1);

    /** Push download progress from the host (any thread). */
    public void setUpdateProgress(int pct) {
        synchronized (QmlRuntimeLock.MONITOR) {
            post(() -> updateProgress.set(pct));
        }
    }

    /**
     * Start the in-app download + install of the pending update (QML "更新" button). Falls back to
     * opening the url in a browser when there's no APK or no installer.
     */
    public void startUpdateDownload() {
        synchronized (QmlRuntimeLock.MONITOR) {
            Installer in = installer;
            String apk = updateApkRaw;
            if (in == null || apk == null || apk.isEmpty()) {
                openUpdateUrl();
                return;
            }
            final String[] candidates = downloadCandidates(apk);
            post(() -> updateProgress.set(0));
            onHost(() -> in.downloadAndInstall(candidates));
        }
    }

    /** Build the ordered download urls: mirrors first (when enabled) then the direct
     *  github url, or direct-first when the mirror is off. Duplicates collapsed. */
    private String[] downloadCandidates(String apk) {
        return GitHubDownloadUrls.candidates(apk, updateMirror);
    }

    /** Host injects the running app version (e.g. "0.5.2") for the update compare. */
    public void setCurrentVersion(String version) {
        synchronized (QmlRuntimeLock.MONITOR) {
            this.currentVersion = version == null ? "" : version;
            post(() -> appVersion.set(this.currentVersion));
        }
    }

    /**
     * Silent (startup) check — no feedback when already up to date or on failure, only the update
     * dialog when a newer release is actually found.
     */
    public void checkForUpdate() {
        synchronized (QmlRuntimeLock.MONITOR) {
            checkForUpdate(false);
        }
    }

    /**
     * Settings > 关于's "检查更新" button (a distinct name rather than an overload call from QML, to
     * sidestep any doubt about how the QML/Java bridge resolves overloaded method calls).
     */
    public void checkForUpdateManual() {
        synchronized (QmlRuntimeLock.MONITOR) {
            checkForUpdate(true);
        }
    }

    /**
     * Fetch the latest release off the worker thread; on a newer version, publish it to the update
     * Properties (QML pops the dialog). Best-effort on failure (offline, rate-limited, parse
     * error): just logs, unless {@code manual}.
     *
     * @param manual true for a user-initiated "检查更新" tap (Settings > 关于) — toasts
     *     I18n.tr("toast.update.upToDate")/an error message instead of failing silently, since a
     *     button the user just pressed needs to visibly do *something*.
     */
    public void checkForUpdate(boolean manual) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (appShuttingDown) return;
            String version = currentVersion;
            boolean mirror = updateMirror;
            AssetMatcher matcher = assetMatcher;
            worker.submit(
                    () -> {
                        try {
                            java.util.Optional<ReleaseUpdateChecker.Update> update =
                                    releaseUpdateChecker.check(version, mirror, matcher::matches);
                            if (!update.isPresent()) {
                                if (manual) showToast(I18n.tr("toast.update.upToDate"));
                                return;
                            }
                            ReleaseUpdateChecker.Update release = update.get();
                            post(
                                    () -> {
                                        if (manual) appUpdatePromptDismissed = false;
                                        updateApkRaw = release.assetUrl();
                                        updateUrl = release.browserUrl();
                                        updateVersion.set(release.version());
                                        updateNotes.set(release.notes());
                                        updateAvailable.set(true);
                                        updatePromptRevision.set(updatePromptRevision.peek() + 1);
                                    });
                        } catch (Exception error) {
                            if (appShuttingDown) return;
                            Logger.warn("update check failed: {}", error.toString());
                            if (manual) showToast(I18n.tr("toast.update.checkFailed"));
                        }
                    });
        }
    }

    /** Open an arbitrary external url via the host browser (e.g. the project page). */
    public void openExternalUrl(String url) {
        synchronized (QmlRuntimeLock.MONITOR) {
            UrlOpener o = urlOpener;
            if (o != null && url != null && !url.isEmpty()) {
                final String u = url;
                onHost(() -> o.open(u));
            }
        }
    }

    /** Open the stored download url via the host (invoked from the QML dialog). */
    public void openUpdateUrl() {
        synchronized (QmlRuntimeLock.MONITOR) {
            UrlOpener o = urlOpener;
            String url = updateUrl;
            if (o != null && url != null && !url.isEmpty()) {
                final String u = url;
                onHost(() -> o.open(u));
            }
        }
    }

    /** Jump to a slot in the live queue (queue-page tap). */
    public void playQueueIndex(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            playAt(i);
        }
    }

    /** Insert a live-list track immediately after the currently playing one. */
    public void playNext(int listIndex) {
        synchronized (QmlRuntimeLock.MONITOR) {
            Track track = copyForQueue(trackFromVisibleList(listIndex));
            if (track == null) {
                showToast(I18n.tr("toast.playlist.addFailed"));
                return;
            }
            insertNext(track);
        }
    }

    public void playNextMedia(String mediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            Track track = copyForQueue(findTrackForMedia(mediaId));
            if (track == null) {
                showToast(I18n.tr("toast.playlist.addFailed"));
                return;
            }
            insertNext(track);
        }
    }

    public void playNextLocalFile(String filePath) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (filePath == null || filePath.isEmpty()) return;
            Track found = null;
            for (Track t : library) {
                if (filePath.equals(t.filePath) || filePath.equals(t.contentUri)) {
                    found = t;
                    break;
                }
            }
            if (found == null) {
                for (Track t : playbackSession.queue) {
                    if (filePath.equals(t.filePath) || filePath.equals(t.contentUri)) {
                        found = t;
                        break;
                    }
                }
            }
            Track track = copyForQueue(found);
            if (track == null) {
                showToast(I18n.tr("toast.playlist.addFailed"));
                return;
            }
            insertNext(track);
        }
    }

    private void insertNext(Track track) {
        if (playbackSession.queue.isEmpty() || playbackSession.index < 0) {
            playbackSession.queue.clear();
            shuffleHistory.clear();
            playbackSession.queue.add(track);
            queueTracks.set(new ArrayList<>(playbackSession.queue));
            saveQueue();
            playAt(0);
            showToast(I18n.tr("toast.queue.playNext"));
            return;
        }
        int at = Math.min(playbackSession.index + 1, playbackSession.queue.size());
        playbackSession.queue.add(at, track);
        queueTracks.set(new ArrayList<>(playbackSession.queue));
        saveQueue();
        showToast(I18n.tr("toast.queue.playNext"));
    }

    private Track trackFromVisibleList(int i) {
        if (i < 0) return null;
        List<Track> local = localPlaylistTracks.peek();
        if (local != null && i < local.size()) return local.get(i);
        if (i < library.size()) return library.get(i);
        if (i < playbackSession.queue.size()) return playbackSession.queue.get(i);
        return null;
    }

    private static Track copyForQueue(Track src) {
        if (src == null) return null;
        Track t = new Track();
        t.source = src.source;
        t.mediaId = src.mediaId;
        t.renditionId = src.renditionId;
        t.filePath = src.filePath;
        t.contentUri = src.contentUri;
        t.neteaseId = src.neteaseId;
        t.customId = src.customId;
        t.title = src.title;
        t.artist = src.artist;
        t.artistId = src.artistId;
        t.artistMediaId = src.artistMediaId;
        t.artistIdsCsv = src.artistIdsCsv;
        t.artistNamesCsv = src.artistNamesCsv;
        t.album = src.album;
        t.durationMs = src.durationMs;
        t.coverUrl = src.coverUrl;
        t.coverThumbPath = src.coverThumbPath;
        t.coverLocalPath = src.coverLocalPath;
        t.lyricFilePath = src.lyricFilePath;
        t.translationFilePath = src.translationFilePath;
        t.romajiFilePath = src.romajiFilePath;
        t.canonicalId();
        return t;
    }

    /** Drop a slot from the queue; keep playing the right track. */
    public void removeFromQueue(int position) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (position < 0 || position >= playbackSession.queue.size()) return;
            int current = playbackSession.index;
            playbackSession.queue.remove(position);
            queueTracks.set(new ArrayList<>(playbackSession.queue));
            if (playbackSession.queue.isEmpty()) {
                playbackSession.invalidate();
                playbackSession.index = -1;
                playbackSession.playing = false;
                backend.pause();
                playing.set(false);
                loading.set(false);
                shuffleHistory.clear();
                index.set(-1);
                currentFilePath.set("");
                currentMediaId.set("");
                title.set("");
                artist.set("");
                album.set("");
                durationMs.set(0L);
                positionMs.set(0L);
                currentLiked.set(false);
                currentLikeable.set(false);
                coverUrl.set("");
                coverPath.set("");
                applyCover(null, coverRevision.incrementAndGet());
                pendingResumeIndex = -1;
                pendingResumeMs = 0L;
                needsReplay = false;
                beginLyricClockLoad(0L);
                applyLyrics(Collections.emptyList());
                saveQueue();
                notifyPlayback();
                return;
            }
            if (position < current) {
                playbackSession.index = current - 1;
                index.set(playbackSession.index);
            } else if (position == current) {
                playbackSession.invalidate();
                playAt(Math.min(current, playbackSession.queue.size() - 1));
            }
            saveQueue();
        }
    }

    // --- Custom playlist (local "play later" list, independent of the live queue) --

    /**
     * The track list behind {@link #cachedSongs}, kept so {@link #playCachedSong} can re-queue it
     * without touching the (render-thread) Property directly.
     */
    private final List<Track> cachedSongTracks = new ArrayList<>();

    /** Compatibility entry for a pre-plugin numeric menu row. */
    public void cacheSong(long songId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            showToast(I18n.tr("toast.cache.legacyEntry"));
        }
    }

    /**
     * Rebuild {@link #cachedSongs} from the audio cache dir + the metadata index. Called by the
     * rail's download menu when it opens. Cheap: one directory listing + a membership check per
     * indexed song — no per-file I/O beyond that.
     */
    public void refreshCachedSongs() {
        worker.submit(
                () -> {
                    try {
                        List<Track> out = new ArrayList<>();
                        for (Track track : mediaMetaIndex.all()) {
                            if (!diskCache.hasAudio(track.canonicalId())) continue;
                            String local = artworkResolver.cachedPath(track);
                            if (local != null) track.coverThumbPath = local;
                            out.add(track);
                        }
                        post(
                                () -> {
                                    cachedSongTracks.clear();
                                    cachedSongTracks.addAll(out);
                                    cachedSongs.set(Collections.unmodifiableList(out));
                                });
                    } catch (Exception error) {
                        Logger.warn("refreshCachedSongs failed: {}", safeMessage(error));
                    }
                });
    }

    /** Play the offline-cached list starting at a slot (rail download-menu tap). */
    public void playCachedSong(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (i < 0 || i >= cachedSongTracks.size()) return;
            playQueue(new ArrayList<>(cachedSongTracks), i);
        }
    }

    /**
     * Delete a cached song's audio from disk (cached-songs list right-click menu). Drops the row
     * from {@link #cachedSongs} so the open list updates in place; reopening via {@link
     * #refreshCachedSongs} is a fresh disk scan.
     */
    public void deleteCachedSong(long songId) {
        removeMediaCache(neteaseMediaId(songId));
    }

    /** Remove a netease song from the custom playlist by id (song long-press menu). */
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

    /**
     * Resolve a netease song id to a fresh Track from whichever live list opened the shared context
     * menu. Track-backed rows are copied so playback mutations cannot alias into the custom/cached
     * lists; page rows use NeteaseSong.
     */
    private Track findLiveTrack(long songId) {
        for (Track t : playbackSession.queue) if (t.neteaseId == songId) return copyNeteaseTrack(t);
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

    

    

    // --- Local playlists --------------------------------------------------
    //
    // A local playlist mixes songs from every source in one user-owned list. Two
    // routes put songs in it: adding a song directly, and adding a whole source
    // playlist, which is recorded as a subscription and re-pulled on open. The
    // set-arithmetic for that lives in LocalPlaylist; everything here is the QML
    // surface, the threading, and the network fetch.

    private final LocalPlaylistStore localPlaylistStore = new LocalPlaylistStore();
    private final MyPlaylistOrderStore myPlaylistOrderStore = new MyPlaylistOrderStore();
    /** "source" (server grouping, primary first) or "custom" (user-dragged). QML
     *  drives the sort menu off this and only allows the drag gesture in custom. */
    public final Property<String> librarySortMode =
            new Property<>(MyPlaylistOrderStore.MODE_SOURCE);
    /** Don't re-pull a subscription that was refreshed this recently on open.
     *  The manual refresh button ignores it. */
    private static final long SUBSCRIPTION_SYNC_COOLDOWN_MS = 10 * 60 * 1000L;
    /** Playlists with a sync in flight, so opening one twice doesn't double-fetch. */
    private final java.util.Set<String> syncingLocalPlaylists =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Render thread: republish the playlist list after any structural change. */
    private void publishLocalPlaylists() {
        List<LocalPlaylistStore.Summary> all = localPlaylistStore.summaries();
        List<Playlist> cards = new ArrayList<>(all.size());
        for (LocalPlaylistStore.Summary playlist : all) {
            Playlist card = new Playlist();
            card.id = playlist.id;
            card.name = playlist.name;
            card.description = playlist.description;
            card.artworkUrl = playlist.artworkUrl;
            card.coverUrl = card.artworkUrl;
            card.sourceName = I18n.tr("playlist.local.sourceName");
            card.trackCount = playlist.trackCount;
            card.owned = true;
            card.mutable = true;
            card.deletable = true;
            cards.add(card);
        }
        localPlaylists.set(Collections.unmodifiableList(cards));
        localPlaylistCount.set(cards.size());
    }

    /** Render thread: republish the open playlist's rows. */
    private void publishOpenLocalPlaylist() {
        String id = openLocalPlaylistId.peek();
        if (id == null || id.isEmpty()) return;
        LocalPlaylist playlist = localPlaylistStore.get(id);
        if (playlist == null) {
            closeLocalPlaylist();
            return;
        }
        localPlaylistTitle.set(playlist.name);
        localPlaylistCover.set(playlist.artworkUrl());
        localPlaylistCustomCover.set(playlist.hasCustomCover());
        localPlaylistSortField.set(playlist.sortField);
        localPlaylistSortDescending.set(playlist.sortDescending);
        localPlaylistReorderable.set(LocalPlaylist.SORT_CUSTOM.equals(playlist.sortField));
        List<PlaylistTrack> ordered = playlist.sortedTracks();
        List<Track> rows = new ArrayList<>(ordered.size());
        for (PlaylistTrack stored : ordered) {
            Track track = stored.toTrack();
            if (track.source != Track.Source.LOCAL) {
                track.coverThumbPath = trackCoverUrl(track, "128");
            } else {
                // A local song's own cached thumbnail lives in the library, not in
                // the playlist record; recover it so rows aren't blank after restart.
                for (Track known : library) {
                    if (known.filePath != null && known.filePath.equals(track.filePath)) {
                        track.coverThumbPath = known.coverThumbPath;
                        track.coverLocalPath = known.coverLocalPath;
                        break;
                    }
                }
            }
            rows.add(track);
        }
        localPlaylistTracks.set(Collections.unmodifiableList(rows));
        localPlaylistSubscriptions.set(Collections.unmodifiableList(
                new ArrayList<>(playlist.subscriptions)));
    }

    private void savePlaylistOrder() {
        ioWorker.submit(
                () -> {
                    if (!myPlaylistOrderStore.save()) {
                        Logger.warn("playlist order save failed");
                        showToast(I18n.tr("toast.actionFailed", "storage unavailable"));
                    }
                });
    }

    private CompletableFuture<Boolean> saveLocalPlaylists() {
        return CompletableFuture.supplyAsync(localPlaylistStore::save, ioWorker)
                .whenComplete(
                        (saved, error) -> {
                            if (error != null || !Boolean.TRUE.equals(saved)) {
                                Logger.warn(
                                        "local playlist save failed: {}",
                                        error != null ? safeMessage(error) : "storage unavailable");
                                showToast(
                                        I18n.tr(
                                                "toast.actionFailed",
                                                error != null
                                                        ? safeMessage(error)
                                                        : "storage unavailable"));
                            }
                        });
    }

    /** Both republish and persist, after a mutation to {@code playlistId}. */
    private CompletableFuture<Boolean> afterLocalPlaylistChange(String playlistId) {
        publishLocalPlaylists();
        if (playlistId != null && playlistId.equals(openLocalPlaylistId.peek())) {
            publishOpenLocalPlaylist();
        }
        return saveLocalPlaylists();
    }

    public void createLocalPlaylist(String name) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                LocalPlaylist created = localPlaylistStore.create(name);
                publishLocalPlaylists();
                saveLocalPlaylists()
                        .thenAccept(
                                saved -> {
                                    if (saved)
                                        showToast(
                                                I18n.tr(
                                                        "toast.localPlaylist.created",
                                                        created.name));
                                });
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    public void renameLocalPlaylist(String playlistId, String name) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                if (!localPlaylistStore.rename(playlistId, name)) return;
                afterLocalPlaylistChange(playlistId);
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    public void deleteLocalPlaylist(String playlistId) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                LocalPlaylist doomed = localPlaylistStore.get(playlistId);
                if (!localPlaylistStore.delete(playlistId)) return;
                if (playlistId.equals(openLocalPlaylistId.peek())) closeLocalPlaylist();
                publishLocalPlaylists();
                saveLocalPlaylists()
                        .thenAccept(
                                saved -> {
                                    if (!saved) return;
                                    if (doomed != null && doomed.hasCustomCover())
                                        deleteQuietly(doomed.coverPath);
                                    showToast(I18n.tr("toast.localPlaylist.deleted"));
                                });
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    public void openLocalPlaylist(String playlistId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (!LocalPlaylist.isLocalPlaylistId(playlistId)) return;
            LocalPlaylist playlist = localPlaylistStore.get(playlistId);
            if (playlist == null) {
                showToast(I18n.tr("toast.localPlaylist.missing"));
                return;
            }
            openLocalPlaylistId.set(playlistId);
            publishOpenLocalPlaylist();
            resetSyncIndicatorFor(playlistId);
            // Opening is also the sync trigger, subject to the cooldown so flipping
            // between playlists doesn't hammer every source.
            syncLocalPlaylist(playlistId, false);
        }
    }

    public void closeLocalPlaylist() {
        synchronized (QmlRuntimeLock.MONITOR) {
            openLocalPlaylistId.set("");
            localPlaylistTitle.set("");
            localPlaylistCover.set("");
            localPlaylistCustomCover.set(false);
            localPlaylistTracks.set(Collections.<Track>emptyList());
            localPlaylistSubscriptions.set(Collections.<PlaylistSubscription>emptyList());
            localPlaylistSyncing.set(false);
        }
    }

    /** Menu state: whether this playlist already holds this song. */
    public boolean isInLocalPlaylist(String playlistId, String songMediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            return localPlaylistStore.contains(playlistId, songMediaId);
        }
    }

    // The row menu offers the same three identities the rest of the app uses —
    // a media id, a legacy numeric netease id, and a local file path. Resolving
    // each to its canonical id here keeps that mapping out of QML.

    public boolean isSongInLocalPlaylist(String playlistId, long songId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            return isInLocalPlaylist(playlistId, neteaseMediaId(songId));
        }
    }

    public boolean isLocalFileInLocalPlaylist(String playlistId, String filePath) {
        synchronized (QmlRuntimeLock.MONITOR) {
            return isInLocalPlaylist(playlistId, localFileMediaId(filePath));
        }
    }

    public void removeSongFromLocalPlaylist(String playlistId, long songId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            removeMediaFromLocalPlaylist(playlistId, neteaseMediaId(songId));
        }
    }

    public void removeLocalFileFromLocalPlaylist(String playlistId, String filePath) {
        synchronized (QmlRuntimeLock.MONITOR) {
            removeMediaFromLocalPlaylist(playlistId, localFileMediaId(filePath));
        }
    }

    private static String neteaseMediaId(long songId) {
        if (songId == 0L) return "";
        return MediaId.of("netease", dev.t1m3.qplayer.media.MediaKind.SONG,
                Long.toString(songId)).toString();
    }

    /** A local file's id is derived from its locator, exactly as Track does it. */
    private String localFileMediaId(String filePath) {
        if (filePath == null || filePath.isEmpty()) return "";
        for (Track candidate : library) {
            if (filePath.equals(candidate.filePath)) return candidate.canonicalId();
        }
        Track probe = new Track();
        probe.source = Track.Source.LOCAL;
        probe.filePath = filePath;
        return probe.canonicalId();
    }

    /** True when any local playlist holds it — drives the row's filled/outline icon. */
    public boolean isInAnyLocalPlaylist(String songMediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            for (LocalPlaylistStore.Summary playlist : localPlaylistStore.summaries()) {
                if (localPlaylistStore.contains(playlist.id, songMediaId)) return true;
            }
            return false;
        }
    }

    public void addMediaToLocalPlaylist(String playlistId, String songMediaId) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                Track track = findTrackForMedia(songMediaId);
                if (track == null) {
                    showToast(I18n.tr("toast.custom.staleSong"));
                    return;
                }
                addTrackToLocalPlaylist(playlistId, track);
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    /** Netease compatibility entry (numeric id from a legacy row's menu). */
    public void addSongToLocalPlaylist(String playlistId, long songId) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                Track track = findLiveTrack(songId);
                if (track == null) {
                    showToast(I18n.tr("toast.playlist.addFailed"));
                    return;
                }
                addTrackToLocalPlaylist(playlistId, track);
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    public void addLocalFileToLocalPlaylist(String playlistId, String filePath) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                if (filePath == null || filePath.isEmpty()) return;
                Track found = null;
                for (Track candidate : library) {
                    if (filePath.equals(candidate.filePath)) {
                        found = candidate;
                        break;
                    }
                }
                if (found == null) {
                    showToast(I18n.tr("toast.playlist.addFailed"));
                    return;
                }
                addTrackToLocalPlaylist(playlistId, found);
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    private void addTrackToLocalPlaylist(String playlistId, Track track) {
        final PlaylistTrack entry =
                PlaylistTrack.from(track, PlaylistTrack.MANUAL, System.currentTimeMillis());
        if (!entry.valid()) {
            showToast(I18n.tr("toast.custom.staleSong"));
            return;
        }
        boolean added = localPlaylistStore.mutate(playlistId, playlist -> playlist.add(entry));
        if (!added) {
            showToast(I18n.tr("toast.localPlaylist.duplicate"));
            return;
        }
        afterLocalPlaylistChange(playlistId)
                .thenAccept(
                        saved -> {
                            if (saved) showToast(I18n.tr("toast.custom.added"));
                        });
    }

    // One-step "new playlist + add", so the row menu can offer it without owning
    // a dialog. The playlist takes the default name, de-duplicated by the store.

    public void addMediaToNewLocalPlaylist(String songMediaId) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                addMediaToLocalPlaylist(newLocalPlaylistId(), songMediaId);
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    public void addSongToNewLocalPlaylist(long songId) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                addSongToLocalPlaylist(newLocalPlaylistId(), songId);
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    public void addLocalFileToNewLocalPlaylist(String filePath) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                addLocalFileToLocalPlaylist(newLocalPlaylistId(), filePath);
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    public void followSourcePlaylistInNewLocalPlaylist(String sourcePlaylistId) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                // Name the new playlist after the source it is being seeded from, which is
                // almost always what the user would have typed anyway.
                String name = "";
                try {
                    name = sourcePlaylistDisplayName(MediaId.parse(sourcePlaylistId));
                } catch (IllegalArgumentException ignored) {
                    // fall back to the default name
                }
                LocalPlaylist created = localPlaylistStore.create(name);
                publishLocalPlaylists();
                followSourcePlaylist(created.id, sourcePlaylistId);
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    private String newLocalPlaylistId() {
        LocalPlaylist created = localPlaylistStore.create("");
        publishLocalPlaylists();
        return created.id;
    }

    /**
     * Remove by the row's position on screen.
     *
     * <p>That is a position in the sorted view, which is not the stored position once any sort is
     * active — so it is resolved to the song's id against the published rows rather than used as an
     * index into the stored list.
     */
    public void removeFromLocalPlaylistAt(String playlistId, int index) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                List<Track> rows =
                        playlistId != null && playlistId.equals(openLocalPlaylistId.peek())
                                ? localPlaylistTracks.peek()
                                : tracksOf(playlistId);
                if (rows == null || index < 0 || index >= rows.size()) return;
                removeMediaFromLocalPlaylist(playlistId, rows.get(index).canonicalId());
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    public void removeMediaFromLocalPlaylist(String playlistId, String songMediaId) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                if (!localPlaylistStore.mutate(
                        playlistId, playlist -> playlist.remove(songMediaId))) return;
                afterLocalPlaylistChange(playlistId);
                showToast(I18n.tr("toast.custom.removed"));
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    /** QML selection mode: newline-separated canonical ids. */
    public void removeMediaManyFromLocalPlaylist(String playlistId, String idsText) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                List<String> ids = splitIds(idsText);
                if (ids.isEmpty()) return;
                final int[] removed = {0};
                boolean changed =
                        localPlaylistStore.mutate(
                                playlistId,
                                playlist -> {
                                    removed[0] = playlist.removeAll(ids);
                                    return removed[0] > 0;
                                });
                if (!changed) return;
                afterLocalPlaylistChange(playlistId);
                showToast(I18n.tr("toast.playlist.songsRemoved", removed[0]));
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    public void playLocalPlaylistIndex(String playlistId, int index) {
        synchronized (QmlRuntimeLock.MONITOR) {
            List<Track> rows =
                    playlistId != null && playlistId.equals(openLocalPlaylistId.peek())
                            ? localPlaylistTracks.peek()
                            : tracksOf(playlistId);
            if (rows == null || index < 0 || index >= rows.size()) return;
            playQueue(new ArrayList<>(rows), index);
        }
    }

    public void playLocalPlaylist(String playlistId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            List<Track> rows = tracksOf(playlistId);
            if (rows == null || rows.isEmpty()) {
                showToast(I18n.tr("toast.localPlaylist.empty"));
                return;
            }
            playQueue(rows, 0);
        }
    }

    /** Rows in the order the page shows them, so "play all" and a card tap follow
     *  the visible sort rather than the stored one. */
    private List<Track> tracksOf(String playlistId) {
        LocalPlaylist playlist = localPlaylistStore.get(playlistId);
        if (playlist == null) return null;
        List<PlaylistTrack> ordered = playlist.sortedTracks();
        List<Track> rows = new ArrayList<>(ordered.size());
        for (PlaylistTrack stored : ordered) rows.add(stored.toTrack());
        return rows;
    }

    // --- Local playlists: ordering -----------------------------------------

    /**
     * Change the sort of one playlist. A sort between two non-custom fields is a view only — the
     * stored order underneath is untouched. Entering custom FROM one, though, bakes in whatever
     * that sort was just displaying as the new stored/draggable order: "start dragging from here",
     * not "jump back to however it was arranged before that sort".
     */
    public void setLocalPlaylistSort(String playlistId, String field, boolean descending) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                final String safe =
                        LocalPlaylist.isSortField(field) ? field : LocalPlaylist.SORT_CUSTOM;
                boolean changed =
                        localPlaylistStore.mutate(
                                playlistId,
                                playlist -> {
                                    if (safe.equals(playlist.sortField)
                                            && playlist.sortDescending == descending) {
                                        return false;
                                    }
                                    if (LocalPlaylist.SORT_CUSTOM.equals(safe)
                                            && !LocalPlaylist.SORT_CUSTOM.equals(
                                                    playlist.sortField)) {
                                        playlist.tracks = playlist.sortedTracks();
                                    }
                                    playlist.sortField = safe;
                                    playlist.sortDescending = descending;
                                    return true;
                                });
                if (!changed) return;
                afterLocalPlaylistChange(playlistId);
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    /**
     * Move a row during a drag. Both positions are stored positions, which is the same as on-screen
     * positions because dragging is only offered in the custom sort ({@link
     * #localPlaylistReorderable}).
     *
     * <p>Deliberately does not persist; the page calls {@link #commitLocalPlaylistOrder} when the
     * gesture ends. The list now sends one of these per gesture rather than one per row crossed (it
     * previews the gap on screen and only applies the move on release), but keeping the write out
     * of here also keeps a programmatic caller from writing the file per step.
     */
    public void moveLocalPlaylistTrack(String playlistId, int from, int to) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                if (!Boolean.TRUE.equals(localPlaylistReorderable.peek())) return;
                if (!localPlaylistStore.mutate(playlistId, playlist -> playlist.move(from, to)))
                    return;
                publishLocalPlaylists();
                if (playlistId.equals(openLocalPlaylistId.peek())) publishOpenLocalPlaylist();
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    /** Persist the arrangement once a drag finishes. */
    public void commitLocalPlaylistOrder(String playlistId) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                saveLocalPlaylists();
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    // --- Local playlists: following a source playlist ----------------------

    /** Whether a local playlist already follows this source playlist. */
    public boolean isSourcePlaylistFollowed(String playlistId, String sourcePlaylistId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            LocalPlaylist playlist = localPlaylistStore.get(playlistId);
            return playlist != null && playlist.subscribed(sourcePlaylistId);
        }
    }

    /**
     * Add a whole source playlist to a local one. The songs are pulled immediately, and re-pulled
     * whenever the local playlist is opened, so the local copy follows the source.
     */
    public void followSourcePlaylist(String playlistId, String sourcePlaylistId) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                if (!LocalPlaylist.isLocalPlaylistId(playlistId)) return;
                final MediaId id;
                try {
                    id =
                            MediaId.parse(sourcePlaylistId)
                                    .requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST);
                } catch (IllegalArgumentException error) {
                    showToast(I18n.tr("toast.id.playlist"));
                    return;
                }
                PlaylistSubscription subscription =
                        new PlaylistSubscription(
                                id.toString(),
                                sourcePlaylistDisplayName(id),
                                providerDisplayName(id.provider()),
                                sourcePlaylistArtwork(id));
                boolean added =
                        localPlaylistStore.mutate(
                                playlistId, playlist -> playlist.subscribe(subscription));
                if (!added) {
                    showToast(I18n.tr("toast.localPlaylist.alreadyFollowed"));
                    return;
                }
                afterLocalPlaylistChange(playlistId)
                        .thenAccept(
                                saved -> {
                                    if (saved)
                                        showToast(
                                                I18n.tr(
                                                        "toast.localPlaylist.following",
                                                        subscription.name));
                                });
                syncLocalPlaylist(playlistId, true);
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    public void unfollowSourcePlaylist(String playlistId, String sourcePlaylistId) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                if (!localPlaylistStore.mutate(
                        playlistId, playlist -> playlist.unsubscribe(sourcePlaylistId))) {
                    return;
                }
                afterLocalPlaylistChange(playlistId)
                        .thenAccept(
                                saved -> {
                                    if (saved) showToast(I18n.tr("toast.localPlaylist.unfollowed"));
                                });
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    /** Manual refresh button: ignores the cooldown. */
    public void refreshLocalPlaylist(String playlistId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            syncLocalPlaylist(playlistId, true);
        }
    }

    /**
     * Re-pull every subscription of one local playlist.
     *
     * <p>Each subscription is fetched independently and applied as it lands, so
     * one dead source cannot hold up the others; the playlist is saved and the
     * spinner cleared once the last one settles.
     */
    private void syncLocalPlaylist(String playlistId, boolean force) {
        LocalPlaylist playlist = localPlaylistStore.get(playlistId);
        if (playlist == null || playlist.subscriptions.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<PlaylistSubscription> due = new ArrayList<>();
        for (PlaylistSubscription subscription : playlist.subscriptions) {
            if (force || now - subscription.lastSyncedAtMs >= SUBSCRIPTION_SYNC_COOLDOWN_MS) {
                due.add(subscription);
            }
        }
        if (due.isEmpty()) return;
        if (!syncingLocalPlaylists.add(playlistId)) return;
        if (playlistId.equals(openLocalPlaylistId.peek())) localPlaylistSyncing.set(true);

        final java.util.concurrent.atomic.AtomicInteger pending =
                new java.util.concurrent.atomic.AtomicInteger(due.size());
        for (PlaylistSubscription subscription : due) {
            final String sourceId = subscription.sourcePlaylistId;
            final MediaId id;
            try {
                id = MediaId.parse(sourceId).requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST);
            } catch (IllegalArgumentException error) {
                finishSubscriptionSync(playlistId, pending);
                continue;
            }
            pluginProviders.playlist(id).whenComplete((resolved, error) -> post(() -> {
                // Whatever applying the result does, this subscription has to be
                // counted off: anything thrown here (a plugin fault, a class that
                // failed to load) would otherwise leave the playlist permanently
                // "syncing" and, because the id stays in syncingLocalPlaylists,
                // refuse every later sync of it too.
                try {
                    applySubscriptionResult(playlistId, sourceId, resolved, error);
                } finally {
                    finishSubscriptionSync(playlistId, pending);
                }
            }));
        }
    }

    /** Render thread: fold one fetched source playlist into the local one. */
    private void applySubscriptionResult(String playlistId, String sourceId,
                                         Playlist resolved, Throwable error) {
        if (error != null || resolved == null) {
            Logger.warn("local playlist sync of {} failed: {}", sourceId, safeMessage(error));
            final String message = I18n.tr("playlist.local.syncFailed");
            localPlaylistStore.mutate(playlistId, playlist -> {
                PlaylistSubscription subscription = playlist.subscription(sourceId);
                if (subscription == null) return false;
                subscription.lastError = message;
                return true;
            });
            return;
        }
        final long now = System.currentTimeMillis();
        final List<PlaylistTrack> incoming = new ArrayList<>(resolved.songs.size());
        for (Song song : resolved.songs) {
            PlaylistTrack entry = PlaylistTrack.from(toTrackPlugin(song), sourceId, now);
            if (entry.valid()) incoming.add(entry);
        }
        final String name = resolved.name;
        final String artwork = resolved.artworkUrl;
        localPlaylistStore.mutate(playlistId, playlist -> {
            PlaylistSubscription subscription = playlist.subscription(sourceId);
            if (subscription == null) return false;
            if (name != null && !name.isEmpty()) subscription.name = name;
            if (artwork != null && !artwork.isEmpty()) subscription.artworkUrl = artwork;
            playlist.applySync(sourceId, incoming, now);
            return true;
        });
    }

    /** Clears the in-flight state first and publishes second, so a failure while
     *  publishing cannot strand the spinner either. */
    private void finishSubscriptionSync(String playlistId,
                                        java.util.concurrent.atomic.AtomicInteger pending) {
        if (pending.decrementAndGet() > 0) return;
        syncingLocalPlaylists.remove(playlistId);
        if (playlistId.equals(openLocalPlaylistId.peek())) localPlaylistSyncing.set(false);
        afterLocalPlaylistChange(playlistId);
    }

    /** Opening a different playlist must not inherit the previous one's spinner:
     *  the in-flight sync belongs to a playlist that is no longer on screen, and
     *  its completion will not touch this one's state. */
    private void resetSyncIndicatorFor(String playlistId) {
        localPlaylistSyncing.set(syncingLocalPlaylists.contains(playlistId));
    }

    private String providerDisplayName(String providerId) {
        if (providerId == null || providerId.isEmpty()) return "";
        for (PluginManifest manifest : pluginManager.enabledProviders()) {
            if (providerId.equals(manifest.id)) return manifest.name;
        }
        return providerId;
    }

    /** Best currently-known name for a source playlist: the open page, the cards
     *  on 我的, or the offline snapshot — whichever has it. */
    private String sourcePlaylistDisplayName(MediaId id) {
        String key = id.toString();
        if (key.equals(openSourcePlaylistId.peek())) {
            String title = playlistTitle.peek();
            if (title != null && !title.isEmpty()) return title;
        }
        for (List<Playlist> slice : myPlaylistsBySource.values()) {
            for (Playlist playlist : slice) {
                if (key.equals(playlist.id) && playlist.name != null) return playlist.name;
            }
        }
        Playlist cached = mediaPlaylistCacheIndex.get(key);
        return cached != null && cached.name != null ? cached.name : "";
    }

    private String sourcePlaylistArtwork(MediaId id) {
        String key = id.toString();
        for (List<Playlist> slice : myPlaylistsBySource.values()) {
            for (Playlist playlist : slice) {
                if (key.equals(playlist.id) && playlist.artworkUrl != null) return playlist.artworkUrl;
            }
        }
        Playlist cached = mediaPlaylistCacheIndex.get(key);
        return cached != null && cached.artworkUrl != null ? cached.artworkUrl : "";
    }

    /** Resolve a song media id to a Track from whichever list is showing it. */
    private Track findTrackForMedia(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return null;
        Song song = findPluginSong(mediaId);
        if (song != null) return toTrackPlugin(song);
        List<List<Track>> lists =
                java.util.Arrays.asList(
                        playbackSession.queue,
                        cachedSongTracks,
                        localPlaylistTracks.peek(),
                        library);
        for (List<Track> list : lists) {
            if (list == null) continue;
            for (Track track : list) {
                if (mediaId.equals(track.canonicalId())) return track;
            }
        }
        return null;
    }

    // --- Local playlists: cover --------------------------------------------

    /** Open the host image picker for a local playlist (QML entry point). */
    public void pickLocalPlaylistCover(String playlistId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (!LocalPlaylist.isLocalPlaylistId(playlistId)) return;
            pickPlaylistCover(playlistId);
        }
    }

    /** Drop the chosen cover and go back to deriving one from the songs. */
    public void clearLocalPlaylistCover(String playlistId) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                final java.util.concurrent.atomic.AtomicReference<String> removed =
                        new java.util.concurrent.atomic.AtomicReference<>("");
                boolean changed =
                        localPlaylistStore.mutate(
                                playlistId,
                                playlist -> {
                                    if (!playlist.hasCustomCover()) return false;
                                    removed.set(playlist.coverPath);
                                    playlist.coverPath = "";
                                    return true;
                                });
                if (!changed) return;
                final String path = removed.get();
                afterLocalPlaylistChange(playlistId)
                        .thenAccept(
                                saved -> {
                                    if (saved) {
                                        deleteQuietly(path);
                                        showToast(I18n.tr("toast.cover.cleared"));
                                    }
                                });
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    /**
     * Worker thread: keep a copy of the picked image and point the playlist at it.
     *
     * <p>Copied rather than referenced so the cover survives the original being moved or deleted,
     * and named after the playlist so one playlist can only ever own one cover file.
     */
    private void storeLocalPlaylistCover(String playlistId, byte[] data, String filename) {
        String extension = imageExtension(filename);
        java.nio.file.Path target;
        try {
            java.nio.file.Path dir = AppDirs.playlistCoversDir();
            java.nio.file.Files.createDirectories(dir);
            target =
                    dir.resolve(
                            MediaId.parse(playlistId).nativeId()
                                    + "-"
                                    + System.nanoTime()
                                    + '.'
                                    + extension);
            java.nio.file.Files.write(target, data);
        } catch (Throwable error) {
            Logger.warn("store local playlist cover failed: {}", error.getMessage());
            showToast(I18n.tr("toast.cover.updateFailed"));
            return;
        }
        final String path = target.toAbsolutePath().toString();
        final java.util.concurrent.atomic.AtomicReference<String> previous =
                new java.util.concurrent.atomic.AtomicReference<>("");
        boolean changed;
        try {
            changed =
                    localPlaylistStore.mutate(
                            playlistId,
                            playlist -> {
                                previous.set(playlist.coverPath);
                                playlist.coverPath = path;
                                return true;
                            });
        } catch (IllegalStateException error) {
            deleteQuietly(path);
            Logger.warn("local playlist cover mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.cover.updateFailed"));
            return;
        }
        if (!changed) {
            // The playlist was deleted while the picker was open.
            deleteQuietly(path);
            return;
        }
        // Replacing a .png with a .jpg leaves the old file behind otherwise.
        String old = previous.get();
        post(
                () -> {
                    afterLocalPlaylistChange(playlistId)
                            .thenAccept(
                                    saved -> {
                                        if (!saved) return;
                                        if (old != null && !old.isEmpty() && !old.equals(path))
                                            deleteQuietly(old);
                                        showToast(I18n.tr("toast.cover.updated"));
                                    });
                });
    }

    private static void deleteQuietly(String path) {
        if (path == null || path.isEmpty()) return;
        try {
            java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(path));
        } catch (Throwable ignored) {
            // A leftover cover file is harmless; nothing reads it any more.
        }
    }

    /** Extension for the stored copy, from the picked file name. Restricted to
     *  what the image decoders actually handle, so a misnamed pick still lands
     *  as something loadable rather than as an arbitrary extension. */
    private static String imageExtension(String filename) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && dot < filename.length() - 1) {
                String candidate = filename.substring(dot + 1)
                        .toLowerCase(java.util.Locale.ROOT);
                if ("png".equals(candidate) || "jpg".equals(candidate)
                        || "jpeg".equals(candidate) || "webp".equals(candidate)
                        || "bmp".equals(candidate) || "gif".equals(candidate)) {
                    return candidate;
                }
            }
        }
        return "jpg";
    }

    // --- Local playlists: import / export ----------------------------------

    /** Host bridge for the two file dialogs an import/export needs. Desktop
     *  installs it; a host without one simply reports the feature as unavailable
     *  rather than failing silently. */
    public interface PlaylistFileBridge {
        /** Choose an existing playlist file to read. */
        void open(java.util.function.Consumer<String> onPicked);
        /** Choose where to write, seeded with {@code suggestedFileName}. */
        void save(String suggestedFileName, java.util.function.Consumer<String> onPicked);
    }

    private volatile PlaylistFileBridge playlistFiles;

    public void setPlaylistFileBridge(PlaylistFileBridge bridge) {
        synchronized (QmlRuntimeLock.MONITOR) {
            this.playlistFiles = bridge;
            post(() -> playlistTransferAvailable.set(bridge != null));
        }
    }

    /** Whether QML should offer the import/export rows at all. */
    public final Property<Boolean> playlistTransferAvailable = new Property<>(false);

    public void requestLocalPlaylistExport(String playlistId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            PlaylistFileBridge bridge = playlistFiles;
            final LocalPlaylist playlist = localPlaylistStore.get(playlistId);
            if (bridge == null || playlist == null) {
                showToast(I18n.tr("toast.localPlaylist.transferUnavailable"));
                return;
            }
            if (playlist.tracks.isEmpty()) {
                showToast(I18n.tr("toast.localPlaylist.empty"));
                return;
            }
            final String suggested = PlaylistTransfer.suggestedFileName(playlist.name);
            onHost(
                    () ->
                            bridge.save(
                                    suggested,
                                    path ->
                                            worker.submit(
                                                    () -> {
                                                        try {
                                                            String json =
                                                                    PlaylistTransfer.export(
                                                                            playlist,
                                                                            currentVersion,
                                                                            this
                                                                                    ::providerDisplayName);
                                                            StorageFiles.writeUtf8Atomic(
                                                                    java.nio.file.Paths.get(path),
                                                                    json);
                                                            post(
                                                                    () ->
                                                                            showToast(
                                                                                    I18n.tr(
                                                                                            "toast.localPlaylist.exported",
                                                                                            playlist
                                                                                                    .tracks
                                                                                                    .size())));
                                                        } catch (Throwable error) {
                                                            Logger.warn(
                                                                    "playlist export failed: {}",
                                                                    error.getMessage());
                                                            post(
                                                                    () ->
                                                                            showToast(
                                                                                    I18n.tr(
                                                                                            "toast.localPlaylist.exportFailed")));
                                                        }
                                                    })));
        }
    }

    public void requestLocalPlaylistImport() {
        synchronized (QmlRuntimeLock.MONITOR) {
            PlaylistFileBridge bridge = playlistFiles;
            if (bridge == null) {
                showToast(I18n.tr("toast.localPlaylist.transferUnavailable"));
                return;
            }
            onHost(
                    () ->
                            bridge.open(
                                    path ->
                                            worker.submit(
                                                    () -> {
                                                        try {
                                                            String json =
                                                                    StorageFiles.readUtf8(
                                                                            java.nio.file.Paths.get(
                                                                                    path));
                                                            LocalPlaylist parsed =
                                                                    PlaylistTransfer.parse(json);
                                                            LocalPlaylist inserted =
                                                                    localPlaylistStore
                                                                            .insertImported(parsed);
                                                            if (!localPlaylistStore.save())
                                                                throw new java.io.IOException(
                                                                        "local playlist save"
                                                                                + " failed");
                                                            post(
                                                                    () -> {
                                                                        publishLocalPlaylists();
                                                                        showToast(
                                                                                I18n.tr(
                                                                                        "toast.localPlaylist.imported",
                                                                                        inserted.name,
                                                                                        inserted
                                                                                                .size()));
                                                                        // A file written on another
                                                                        // machine carries a
                                                                        // snapshot of its
                                                                        // subscriptions; pull them
                                                                        // now so the copy is
                                                                        // current rather
                                                                        // than however stale the
                                                                        // export was.
                                                                        if (!inserted.subscriptions
                                                                                .isEmpty()) {
                                                                            syncLocalPlaylist(
                                                                                    inserted.id,
                                                                                    true);
                                                                        }
                                                                    });
                                                        } catch (
                                                                PlaylistTransfer.UnsupportedFormat
                                                                        error) {
                                                            post(
                                                                    () ->
                                                                            showToast(
                                                                                    I18n.tr(
                                                                                            "toast.localPlaylist.importUnsupported")));
                                                        } catch (Throwable error) {
                                                            Logger.warn(
                                                                    "playlist import failed: {}",
                                                                    error.getMessage());
                                                            post(
                                                                    () ->
                                                                            showToast(
                                                                                    I18n.tr(
                                                                                            "toast.localPlaylist.importFailed")));
                                                        }
                                                    })));
        }
    }

    // --- Local library ----------------------------------------------------

    /** Scan a local folder for audio files (platform-neutral, uses Files.walk). */
    public void scan(String folder) {
        synchronized (QmlRuntimeLock.MONITOR) {
            worker.submit(
                    () -> {
                        try {
                            LibraryScanner scanner = new LibraryScanner(metadataReader);
                            List<Track> found = scanner.scan(folder);
                            post(() -> applyLibrary(found));
                        } catch (Throwable e) {
                            Logger.exception(e);
                            post(
                                    () ->
                                            toast.set(
                                                    I18n.tr(
                                                            "toast.local.scanFailed",
                                                            e.getMessage())));
                        }
                    });
        }
    }

    /** Accept a pre-scanned track list (e.g. from MediaStore on Android 11+). */
    public void scanTracks(List<Track> tracks) {
        synchronized (QmlRuntimeLock.MONITOR) {
            applyLibrary(tracks);
        }
    }

    private void applyLibrary(List<Track> found) {
        library.clear();
        library.addAll(found);
        tracks.set(new ArrayList<>(library));
        libraryCount.set(library.size());
    }

    public int trackCount() {
        synchronized (QmlRuntimeLock.MONITOR) {
            return library.size();
        }
    }

    public String trackTitle(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            return i >= 0 && i < library.size() ? orEmpty(library.get(i).title) : "";
        }
    }

    public String trackArtist(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            return i >= 0 && i < library.size() ? orEmpty(library.get(i).artist) : "";
        }
    }

    public String resultTitle(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            List<NeteaseSong> r = searchResults.peek();
            return i >= 0 && i < r.size() ? orEmpty(r.get(i).name) : "";
        }
    }

    public String resultArtist(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            List<NeteaseSong> r = searchResults.peek();
            return i >= 0 && i < r.size() ? orEmpty(r.get(i).artist) : "";
        }
    }

    public long resultId(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            List<NeteaseSong> r = searchResults.peek();
            return i >= 0 && i < r.size() ? r.get(i).id : 0L;
        }
    }

    // --- Playback control -------------------------------------------------

    /** Play local library starting at {@code i}. */
    public void play(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (i < 0 || i >= library.size()) return;
            // Snapshot: library is the live scan list — a later rescan rewrites it in
            // place, and handing the same reference to the queue would let that rescan
            // silently swap the track playIndex points at mid-playback.
            playQueue(new ArrayList<>(library), i);
        }
    }

    /**
     * Queue a netease song-list and start at {@code i}. Search history is fed by the query text the
     * user actually typed/submitted (SearchPage.qml), not by which result they clicked — a song
     * title isn't a search the user made.
     */
    public void playSearchResult(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            List<NeteaseSong> songs = searchResults.peek();
            playSongList(songs, i);
        }
    }

    public void playRecommendation(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (Boolean.TRUE.equals(sourceContentActive.peek())) {
                playPluginSongList(sourceRecommendations.peek(), i);
                return;
            }
            playSongList(recommendations.peek(), i);
        }
    }

    public void playRecentSong(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (Boolean.TRUE.equals(sourceContentActive.peek())) {
                playPluginSongList(sourceRecentSongs.peek(), i);
                return;
            }
            playSongList(recentSongs.peek(), i);
        }
    }

    public void playPlaylistTrack(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (!openSourcePlaylistId.peek().isEmpty()) {
                playPluginSongList(sourcePlaylistTracks.peek(), i, openSourcePlaylistId.peek());
                return;
            }
            playSongList(playlistTracks.peek(), i, currentPlaylistId);
        }
    }

    /**
     * Replace the queue with NetEase's heart-mode recommendations, using the current song when this
     * playlist already owns the queue and otherwise its first track as the seed.
     */
    public void startIntelligenceMode(long playlistId) {
        startMediaIntelligenceMode("netease:playlist:" + playlistId);
    }

    public void startMediaIntelligenceMode(String playlistMediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (!canHeartMediaPlaylist(playlistMediaId)) {
                showToast(I18n.tr("toast.heart.signIn"));
                return;
            }
            if (playlistMediaId == null
                    || playlistMediaId.isEmpty()
                    || Boolean.TRUE.equals(intelligenceLoading.peek())) return;
            final MediaId playlistId;
            try {
                playlistId =
                        MediaId.parse(playlistMediaId)
                                .requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST);
            } catch (IllegalArgumentException error) {
                showToast(I18n.tr("toast.id.playlist"));
                return;
            }
            if (!pluginHasCapability(
                    playlistId.provider(), ProviderCapability.HEART_RECOMMENDATION)) {
                showToast(I18n.tr("toast.heart.unsupported"));
                return;
            }
            MediaId seed = null;
            Track current = currentTrack();
            if (playlistMediaId.equals(currentQueueMediaPlaylistId) && current != null) {
                try {
                    MediaId candidate =
                            MediaId.parse(current.canonicalId())
                                    .requireKind(dev.t1m3.qplayer.media.MediaKind.SONG);
                    if (playlistId.provider().equals(candidate.provider())) seed = candidate;
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (seed == null && !sourcePlaylistTracks.peek().isEmpty()) {
                try {
                    seed = MediaId.parse(sourcePlaylistTracks.peek().get(0).id);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (seed == null) {
                showToast(I18n.tr("toast.heart.empty"));
                return;
            }
            intelligenceLoading.set(true);
            final MediaId selectedSeed = seed;
            pluginProviders
                    .heartRecommendations(selectedSeed, playlistId, 100)
                    .whenComplete(
                            (songs, error) ->
                                    post(
                                            () -> {
                                                intelligenceLoading.set(false);
                                                if (error != null) {
                                                    showToast(
                                                            I18n.tr(
                                                                    "toast.heart.failed",
                                                                    safeMessage(error)));
                                                } else if (songs == null || songs.isEmpty()) {
                                                    showToast(I18n.tr("toast.heart.none"));
                                                } else {
                                                    playPluginSongList(songs, 0, playlistMediaId);
                                                    showToast(I18n.tr("toast.heart.started"));
                                                }
                                            }));
        }
    }

    /**
     * Fetch a playlist from its card context menu and start it from the first song. This
     * deliberately does not open the detail page or replace its loading state.
     */
    public void playPlaylist(long playlistId) {
        playMediaPlaylist("netease:playlist:" + playlistId);
    }

    /** Source-neutral playlist playback entry point used by cards and menus. */
    public void playMediaPlaylist(String mediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (mediaId == null || mediaId.isEmpty()) return;
            if (mediaId.indexOf(':') < 0) {
                try {
                    playPlaylist(Long.parseLong(mediaId));
                } catch (NumberFormatException ignored) {
                }
                return;
            }
            final MediaId id;
            try {
                id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST);
            } catch (IllegalArgumentException error) {
                showToast(I18n.tr("toast.id.playlist"));
                return;
            }
            pluginProviders
                    .playlist(id)
                    .whenComplete(
                            (playlist, error) ->
                                    post(
                                            () -> {
                                                Playlist resolved = playlist;
                                                if (error != null || resolved == null) {
                                                    resolved =
                                                            mediaPlaylistCacheIndex.get(
                                                                    id.toString());
                                                    if (resolved == null) {
                                                        showToast(
                                                                I18n.tr(
                                                                        "toast.playlist.playFailedReason",
                                                                        safeMessage(error)));
                                                        return;
                                                    }
                                                }
                                                if (resolved.songs.isEmpty()) {
                                                    showToast(I18n.tr("toast.playlist.empty"));
                                                    return;
                                                }
                                                mediaPlaylistCacheIndex.upsert(resolved);
                                                worker.submit(mediaPlaylistCacheIndex::save);
                                                playPluginSongList(
                                                        resolved.songs, 0, id.toString());
                                            }));
        }
    }

    public void copyMediaReference(String mediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (mediaId == null || mediaId.isEmpty()) return;
            if (mediaId.indexOf(':') < 0) {
                try {
                    copyPlaylistLink(Long.parseLong(mediaId));
                } catch (NumberFormatException ignored) {
                }
                return;
            }
            java.util.function.Consumer<String> sink = clipboard;
            if (sink != null) {
                sink.accept(mediaId);
                showToast(I18n.tr("toast.mediaIdCopied"));
            } else {
                showToast(mediaId);
            }
        }
    }

    public void shareMedia(String mediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            final MediaId id;
            try {
                id = MediaId.parse(mediaId);
            } catch (IllegalArgumentException error) {
                showToast(I18n.tr("toast.id.media"));
                return;
            }
            if (!pluginHasCapability(id.provider(), ProviderCapability.SHARE)) {
                copyMediaReference(mediaId);
                return;
            }
            pluginProviders
                    .share(id)
                    .whenComplete(
                            (url, error) ->
                                    post(
                                            () -> {
                                                if (error != null || url == null || url.isEmpty()) {
                                                    showToast(
                                                            I18n.tr(
                                                                    "toast.share.failed",
                                                                    safeMessage(error)));
                                                    return;
                                                }
                                                java.util.function.Consumer<String> sink =
                                                        clipboard;
                                                if (sink != null) {
                                                    sink.accept(url);
                                                    showToast(I18n.tr("toast.linkCopied"));
                                                } else showToast(url);
                                            }));
        }
    }

    /** Play a single netease song id (no surrounding queue). */
    public void playNetease(long songId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (!pluginHasCapability("netease", ProviderCapability.RESOLVE_STREAM)) {
                showToast(I18n.tr("toast.play.missingPlugin"));
                return;
            }
            Track track = findLiveTrack(songId);
            if (track == null) {
                showToast(I18n.tr("toast.cache.staleSong"));
                return;
            }
            routeInstalledPluginTrack(track);
            playQueue(Collections.singletonList(track), 0);
        }
    }

    private void playSongList(List<NeteaseSong> songs, int i) {
        playSongList(songs, i, 0L);
    }

    private void playSongList(List<NeteaseSong> songs, int i, long playlistId) {
        if (songs == null || i < 0 || i >= songs.size()) return;
        List<Track> q = new ArrayList<>(songs.size());
        for (NeteaseSong s : songs) q.add(toTrack(s));
        playQueue(q, i, playlistId != 0L ? "netease:playlist:" + playlistId : "");
    }

    private void playPluginSongList(List<Song> songs, int i) {
        playPluginSongList(songs, i, "");
    }

    private void playPluginSongList(List<Song> songs, int i, String playlistId) {
        if (songs == null || i < 0 || i >= songs.size()) return;
        List<Track> tracks = new ArrayList<>(songs.size());
        for (Song song : songs) tracks.add(toTrackPlugin(song));
        playQueue(tracks, i, playlistId);
    }

    /** Queue one provider's stable search bucket and start the selected row. */
    public void playPluginSearchResult(String providerId, int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            List<Song> songs = pluginSearchByProvider.get(providerId);
            if (songs == null || i < 0 || i >= songs.size()) return;
            List<Track> q = new ArrayList<>(songs.size());
            for (Song song : songs) q.add(toTrackPlugin(song));
            playQueue(q, i);
        }
    }

    private void playQueue(List<Track> q, int start) {
        playQueue(q, start, "");
    }

    private void playQueue(List<Track> tracks, int start, String playlistMediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (tracks == null || start < 0 || start >= tracks.size()) return;
            if (blockedByPluginSession(tracks.get(start))) return;
            playbackSession.queue.clear();
            playbackSession.queue.addAll(tracks);
            playbackSession.index = -1;
            currentQueueMediaPlaylistId = playlistMediaId != null ? playlistMediaId : "";
            shuffleHistory.clear();
            queueTracks.set(new ArrayList<>(playbackSession.queue));
            playAt(start);
        }
    }

    /** Report the outgoing source's live played duration through its plugin capability. */
    private void scrobbleOutgoingTrack(boolean naturalEnd) {
        Track track = playbackSession.current();
        if (track == null || track.source != Track.Source.PLUGIN) return;
        long played = Math.max(0L, backend.position());
        if (played < 3000L) return;
        MediaId id = MediaId.parse(track.canonicalId());
        if (!pluginHasCapability(id.provider(), ProviderCapability.SCROBBLE)) return;
        pluginProviders
                .scrobble(id, played, track.durationMs, naturalEnd)
                .exceptionally(
                        error -> {
                            Logger.warn("plugin scrobble failed: {}", safeMessage(error));
                            return null;
                        });
    }

    private void playAt(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            playAtLocked(i);
        }
    }

    private void playAtLocked(int requestedIndex) {
        if (appShuttingDown || requestedIndex < 0 || requestedIndex >= playbackSession.queue.size())
            return;
        Track track = playbackSession.queue.get(requestedIndex);
        routeInstalledPluginTrack(track);
        if (blockedByPluginSession(track)) return;
        Track outgoing = playbackSession.current();
        scrobbleOutgoingTrack(pendingNaturalEnd);
        pendingNaturalEnd = false;
        rememberShufflePredecessor(requestedIndex);
        boolean desiredPlaying =
                pendingPluginDesiredPlaying == null
                        || !pendingPluginTargetMediaId.equals(track.canonicalId())
                        || pendingPluginDesiredPlaying;
        pendingPluginDesiredPlaying = null;
        pendingPluginTargetMediaId = "";
        playbackSession.index = requestedIndex;
        playbackSession.select(desiredPlaying);
        needsReplay = false;
        long revision = coverRevision.incrementAndGet();
        long resumeMs = requestedIndex == pendingResumeIndex ? Math.max(0L, pendingResumeMs) : 0L;
        pendingResumeIndex = -1;
        pendingResumeMs = 0L;
        playbackSession.startPositionMs = resumeMs;
        beginLyricClockLoad(resumeMs);
        if (fadeEnabled && backend.isPlaying()) startFadeOut(FADE_OUT_MS, backend::pause);
        else backend.pause();
        loading.set(true);
        playing.set(desiredPlaying);
        applyCover(null, revision);
        coverPath.set("");
        boolean keepLyrics =
                outgoing != null
                        && outgoing.canonicalId().equals(track.canonicalId())
                        && lyrics.peek() != null
                        && !lyrics.peek().isEmpty();
        if (!keepLyrics) {
            applyLyrics(Collections.emptyList());
            coverModeManual.set(false);
        }
        applyTrackLyricOffset(track);
        index.set(requestedIndex);
        currentFilePath.set(track.source == Track.Source.LOCAL ? orEmpty(track.filePath) : "");
        currentMediaId.set(track.canonicalId());
        title.set(orEmpty(track.title));
        artist.set(orEmpty(track.artist));
        publishPlayingArtist(track);
        album.set(orEmpty(track.album));
        coverUrl.set(trackCoverUrl(track, "512"));
        durationMs.set(track.durationMs);
        positionMs.set(resumeMs);
        boolean likeable =
                track.source == Track.Source.PLUGIN
                        && providerForMediaHas(track.canonicalId(), ProviderCapability.LIKE);
        currentLikeable.set(likeable);
        currentLiked.set(likeable && pluginLikedSet.contains(track.canonicalId()));
        updateCover(track, revision);
        if (track.source == Track.Source.PLUGIN) diskCache.setCurrentlyPlaying(track.canonicalId());
        else diskCache.clearCurrentlyPlaying();
        saveQueue();
        if (track.source == Track.Source.LOCAL) {
            String source = track.playable();
            if (source == null || source.isEmpty()) {
                skipUnplayable(track, I18n.tr("toast.play.audioFailed"));
                return;
            }
            if (!keepLyrics) ioWorker.submit(() -> loadLocalLyrics(track));
            playBackend(source, resumeMs);
            if (!playbackSession.playing) backend.pause();
        } else if (track.source == Track.Source.PLUGIN) {
            preparePluginPlayback(track, keepLyrics, revision);
        } else {
            skipUnplayable(track, I18n.tr("toast.play.missingPlugin"));
            return;
        }
        preloadAdjacent();
        notifyPlayback();
    }

    /**
     * Feed coverBytes for the fluid backdrop: local tracks carry embedded bytes; NETEASE tracks
     * download lazily off-thread, keyed by the caller's {@link #coverRevision} so a stale fetch for
     * a skipped-past track is dropped (see {@link #applyCoverAndPath} for why the queue index
     * cannot do that job).
     */
    private void updateCover(Track t, long revision) {
        ioWorker.submit(() -> updateCoverOnIo(t, revision));
    }

    private void updateCoverOnIo(Track track, long revision) {
        if (coverRevision.get() != revision) return;
        try {
            ArtworkResolver.Cover cached = artworkResolver.cachedCover(track);
            if (cached.bytes() != null && cached.bytes().length > 0) {
                publishArtwork(track, cached.bytes(), cached.path(), revision);
                return;
            }
        } catch (java.io.IOException error) {
            Logger.warn("cached cover read failed: {}", safeMessage(error));
        }
        scheduleFastMonet(track, revision);
        if (track.source != Track.Source.PLUGIN) return;
        String url = trackCoverUrl(track, "1024");
        if (url.isEmpty()) return;
        String provider = providerOf(track);
        pluginHostApi
                .fetchReturnedBytes(
                        provider, url, Collections.emptyMap(), 16L * 1024L * 1024L, 8_000)
                .whenCompleteAsync(
                        (data, error) -> {
                            if (error != null || data == null || data.length == 0) {
                                Logger.warn(
                                        "plugin {} cover fetch failed: {}",
                                        provider,
                                        error != null ? safeMessage(error) : "empty response");
                                return;
                            }
                            String path = diskCache.imagePath(url);
                            if (path != null) writeBytesToFile(data, path);
                            publishArtwork(track, data, path, revision);
                        },
                        ioWorker);
    }

    private void publishArtwork(Track track, byte[] data, String path, long revision) {
        onMain(
                () -> {
                    if (coverRevision.get() != revision || !isCurrentTrack(track)) return;
                    track.coverBytes = data;
                    applyCoverAndPath(data, path, revision);
                    notifyPlayback();
                });
    }

    /** Commit one track switch's cover bytes AND its on-disk path together, on the
     *  render thread.
     *
     *  <p>{@link #coverRevision} is the guard, not the queue index these callbacks
     *  used to compare. An index does not identify a track from either direction:
     *  two consecutive selections can land on the SAME slot (playlist A's row 3 ->
     *  playlist B's row 3), letting a superseded download through, while
     *  {@link #removeFromQueue} renumbers the CURRENT track's slot without any track
     *  change at all, so an index check would throw away artwork that is still
     *  wanted. The revision is bumped once per {@link #playAt}, which is exactly the
     *  "is this still the selection I was loaded for" question.
     *
     *  <p>{@link #applyCover} already carried this check for the bytes, but
     *  {@link #coverPath} was set outside it — and MiniPlayer.qml / LyricOverlay.qml
     *  bind the now-playing art to coverPath, so the previous song's cover stayed on
     *  screen over the new one. A null {@code path} leaves coverPath untouched (the
     *  caller had no file); pass "" to deliberately clear it. */
    private void applyCoverAndPath(byte[] data, String path, long revision) {
        if (coverRevision.get() != revision) return;
        applyCover(data, revision);
        if (path != null) coverPath.set(path);
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

    /** Read a local thumbnail independently of full-artwork IO for a faster theme seed. */
    private void scheduleFastMonet(Track t, long revision) {
        final ColorExtractor ex = colorExtractor;
        if (ex == null || t == null) return;
        String localThumb = t.coverThumbPath;
        if (localThumb != null
                && !localThumb.isEmpty()
                && !localThumb.startsWith("http://")
                && !localThumb.startsWith("https://")) {
            final String path = localThumb;
            monetFetchWorker.execute(
                    () -> {
                        if (coverRevision.get() != revision) return;
                        byte[] data = readBytesFromFile(path);
                        if (data != null && data.length > 0)
                            scheduleSeedExtraction(data, revision, 0);
                    });
            return;
        }
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

    /**
     * After the current track settles, warm the next + previous tracks' lyrics and cover bytes on
     * the worker so switching to them is instant (no load-in stutter). Runs on the main thread;
     * submits per-track work that queues behind the current track's own fetches (single worker), so
     * the current song always loads first.
     */
    private void preloadAdjacent() {
        int n = playbackSession.queue.size();
        if (n <= 1) return;
        int cur = playbackSession.index;
        if (cur < 0 || cur >= n) return;
        Track next = playbackSession.queue.get((cur + 1) % n);
        Track prev = playbackSession.queue.get((cur - 1 + n) % n);
        preloadTrack(next);
        if (prev != next) preloadTrack(prev);
    }

    private void preloadTrack(Track track) {
        if (track == null || track.source != Track.Source.LOCAL || track.coverBytes != null) return;
        ioWorker.submit(
                () -> {
                    try {
                        ArtworkResolver.Cover cover = artworkResolver.cachedCover(track);
                        post(() -> track.coverBytes = cover.bytes());
                    } catch (java.io.IOException error) {
                        Logger.warn("cover preload failed: {}", safeMessage(error));
                    }
                });
    }

    /** Toggle Monet dynamic color; re-applies the seed (render thread). */
    public void setMonetEnabled(boolean enabled) {
        synchronized (QmlRuntimeLock.MONITOR) {
            this.monetEnabled = enabled;
            post(this::reapplySeed);
        }
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

    private void writeBytesToFile(byte[] data, String path) {
        if (data == null || path == null) return;
        try (DiskCache.WriteLease lease = diskCache.beginExternalWrite(path)) {
            StorageFiles.writeBytesAtomic(java.nio.file.Paths.get(path), data);
        } catch (Exception error) {
            Logger.warn("artwork cache write failed: {}", safeMessage(error));
        }
    }

    public void toggle() {
        onMain(
                () -> {
                    if (playbackSession.index < 0 && !library.isEmpty()) {
                        play(0);
                        return;
                    }
                    if (playbackSession.playing) mediaPause();
                    else mediaResume();
                });
    }

    /** Cycle list-loop -> shuffle -> repeat-one -> list-loop. */
    public void cyclePlayMode() {
        synchronized (QmlRuntimeLock.MONITOR) {
            setPlayMode((playMode.peek() + 1) % 3);
        }
    }

    /**
     * Select list-loop (0), shuffle (1), or repeat-one (2). Host media-session adapters use this
     * for their explicit shuffle/repeat properties.
     */
    public void setPlayMode(int mode) {
        synchronized (QmlRuntimeLock.MONITOR) {
            playMode.set(Math.max(0, Math.min(2, mode)));
            notifyPlayback();
        }
    }

    // A different queue slot than the current one (shuffle never repeats a track
    // back-to-back unless the queue has a single entry).
    private int randomIndex() {
        int n = playbackSession.queue.size();
        if (n == 0) return 0;
        if (n == 1) return 0;
        int r;
        do {
            r = rng.nextInt(n);
        } while (r == playbackSession.index);
        return r;
    }

    // Manual skip: shuffle picks a random slot, otherwise step forward and wrap.
    // Repeat-one only affects auto-advance -- a manual press still moves on.
    public void next() {
        synchronized (QmlRuntimeLock.MONITOR) {
            onMain(
                    () -> {
                        if (playbackSession.queue.isEmpty()) return;
                        suppressNextFadeIn = true;
                        playAt(
                                playMode.peek() == 1
                                        ? randomIndex()
                                        : (playbackSession.index + 1)
                                                % playbackSession.queue.size());
                    });
        }
    }

    public void prev() {
        synchronized (QmlRuntimeLock.MONITOR) {
            onMain(
                    () -> {
                        if (playbackSession.queue.isEmpty()) return;
                        int n = playbackSession.queue.size();
                        suppressNextFadeIn = true;
                        if (playMode.peek() == 1) {
                            int remembered = popShuffleHistoryIndex();
                            if (remembered >= 0) {
                                consumingShuffleHistory = true;
                                try {
                                    playAt(remembered);
                                } finally {
                                    consumingShuffleHistory = false;
                                }
                                return;
                            }
                        }
                        playAt(
                                playMode.peek() == 1
                                        ? randomIndex()
                                        : (playbackSession.index - 1 + n) % n);
                    });
        }
    }

    /**
     * Record the track we are leaving so shuffle's {@link #prev} can find it. No-op when replaying
     * the same slot, when prev() itself is consuming the stack, or when the outgoing id is
     * empty/identical to the incoming one.
     */
    private void rememberShufflePredecessor(int incomingIndex) {
        if (consumingShuffleHistory) return;
        if (playbackSession.index < 0
                || playbackSession.index >= playbackSession.queue.size()
                || playbackSession.index == incomingIndex) return;
        if (incomingIndex < 0 || incomingIndex >= playbackSession.queue.size()) return;
        String outgoing = playbackSession.queue.get(playbackSession.index).canonicalId();
        if (outgoing == null || outgoing.isEmpty()) return;
        if (outgoing.equals(playbackSession.queue.get(incomingIndex).canonicalId())) return;
        shuffleHistory.addLast(outgoing);
        while (shuffleHistory.size() > MAX_SHUFFLE_HISTORY) shuffleHistory.removeFirst();
    }

    /**
     * Most recent remembered track that is still in the queue and is not the one already playing.
     * Skips ids dropped by a queue edit.
     */
    private int popShuffleHistoryIndex() {
        while (!shuffleHistory.isEmpty()) {
            String id = shuffleHistory.removeLast();
            if (id == null || id.isEmpty()) continue;
            for (int i = 0; i < playbackSession.queue.size(); i++) {
                if (i != playbackSession.index
                        && id.equals(playbackSession.queue.get(i).canonicalId())) return i;
            }
        }
        return -1;
    }

    /** A real end-of-track reports a position within a few seconds of the known
     *  duration (VBR frame-count estimates and container padding both introduce
     *  slack). A much larger shortfall means the stream was cut short server-side
     *  (CDN truncation, a dropped edge node) rather than actually finished. */
    private static final long TRUNCATED_END_GUARD_MS = 5_000L;

    // Track finished on its own: repeat-one replays it, shuffle jumps randomly,
    // list-loop advances. Wired to backend.onComplete (not next()) so repeat-one
    // doesn't fight a user's manual skip. Already on the main thread (onComplete).
    private void autoAdvance() {
        if (playbackSession.queue.isEmpty()) return;
        Track ending = currentTrack();
        if (ending != null
                && ending.durationMs > 0
                && ending.durationMs - Math.max(0L, backend.position()) > TRUNCATED_END_GUARD_MS
                && retryStaleStreamUrl(ending)) {
            return;
        }
        pendingNaturalEnd = true;
        playbackEndRevision.incrementAndGet();
        if (!pluginAutoAdvanceBlocker.isEmpty()) return;
        performAutoAdvance();
    }

    private void performAutoAdvance() {
        // autoAdvance() and pluginAppUnavailable() both check this, but the plugin
        // host's "playback.next" calls straight in — and the list-loop branch below
        // divides by the queue size.
        if (playbackSession.queue.isEmpty()) return;
        switch (playMode.peek()) {
            case 2:
                playAt(playbackSession.index);
                break;
            case 1:
                playAt(randomIndex());
                break;
            default:
                playAt((playbackSession.index + 1) % playbackSession.queue.size());
                break;
        }
    }

    public void seek(long ms) {
        onMain(() -> mediaSeek(ms));
    }

    /**
     * Seek immediately — like {@link #mediaPause()} / {@link #mediaResume()}, bypasses the
     * main-thread Handler to avoid OEM background throttling.
     */
    public void mediaSeek(long ms) {
        synchronized (QmlRuntimeLock.MONITOR) {
            final long t = Math.max(0L, ms);
            resetNaturalEndFadeAfterSeek();
            seekRevision.incrementAndGet();
            stoppedLyricPositionMs = t;
            playbackSession.startPositionMs = t;
            if (pendingResumeIndex == playbackSession.index) pendingResumeMs = t;
            if (!playbackSession.resolving) backend.seek(t);
            post(() -> positionMs.set(t));
            notifyPlayback();
        }
    }

    public long seekRevision() {
        synchronized (QmlRuntimeLock.MONITOR) {
            return seekRevision.get();
        }
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
        synchronized (QmlRuntimeLock.MONITOR) {
            return backend.position();
        }
    }

    /**
     * True only while song time should advance visually. Unlike {@link #isPlaying()}, this stays
     * false during async source loading. During a manual fade-out it stays true until the backend
     * really pauses, keeping lyrics aligned with audible audio.
     */
    public boolean isLyricClockRunning() {
        synchronized (QmlRuntimeLock.MONITOR) {
            return playbackStarted && backend.isPlaying();
        }
    }

    /**
     * Exact lyric-clock baseline. While running this is the live backend position; after a source
     * has started, its paused position remains the source of truth too, so pause/resume cannot
     * diverge and then visibly jump back into alignment.
     */
    public long lyricClockPosition() {
        synchronized (QmlRuntimeLock.MONITOR) {
            return playbackStarted
                    ? Math.max(0L, backend.position())
                    : Math.max(0L, stoppedLyricPositionMs);
        }
    }

    public long playbackRevision() {
        synchronized (QmlRuntimeLock.MONITOR) {
            return playbackRevision.get();
        }
    }

    private void beginLyricClockLoad(long startMs) {
        stoppedLyricPositionMs = Math.max(0L, startMs);
        playbackStarted = false;
        playbackRevision.incrementAndGet();
    }

    /**
     * Position intended for a host media session. Unlike {@link #position()}, this also sees a
     * queue's saved resume point before the asynchronous UI queue has published it and before the
     * audio backend has started.
     */
    public long mediaSessionPosition() {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (playbackSession.resolving || needsReplay) {
                if (pendingResumeIndex == playbackSession.index)
                    return Math.max(0L, pendingResumeMs);
                return Math.max(0L, stoppedLyricPositionMs);
            }
            long live = Math.max(0L, backend.position());
            if (playbackStarted || backend.isPlaying() || live > 0L) return live;
            return Math.max(0L, stoppedLyricPositionMs);
        }
    }

    public void setVolume(float v) {
        synchronized (QmlRuntimeLock.MONITOR) {
            float clamped = Math.max(0f, Math.min(1f, v));
            userVolume = clamped;
            applyEffectiveVolume(currentFadeGain());
            volume.set(clamped);
        }
    }

    public void setPlayLevel(String level) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (level != null && !level.isEmpty()) playLevel = level;
        }
    }

    /**
     * Settings toggle: netease playback quality. On (default) requests "exhigh" (~320kbps); off
     * requests "standard" (~128kbps) to save bandwidth. Only affects tracks resolved after the
     * change, not the currently playing one.
     */
    public void setHighQualityEnabled(boolean enabled) {
        synchronized (QmlRuntimeLock.MONITOR) {
            setPlayLevel(enabled ? "exhigh" : "standard");
        }
    }

    /**
     * Settings toggle: fade the volume in at the start of a track and out approaching its natural
     * end, instead of a hard cut. Turning it off mid-fade snaps straight back to the user's actual
     * volume setting.
     */
    public void setFadeEnabled(boolean enabled) {
        synchronized (QmlRuntimeLock.MONITOR) {
            this.fadeEnabled = enabled;
            if (!enabled) {
                cancelFadeAtGain(1f);
            }
        }
    }

    /**
     * Load a netease track's lyrics off-thread (AMLL TTML mirror, else Netease's own). tryAmllTtml
     * hits the disk cache first, so a previously-played song shows its lyrics with no network.
     * Called on every netease play — including the audio-cache fast path, which bypasses the URL
     * resolve that used to fetch them.
     */
    private void loadPluginLyrics(Track track) {
        final String canonical = track.canonicalId();
        final MediaId id;
        try {
            id = MediaId.parse(canonical);
        } catch (IllegalArgumentException error) {
            return;
        }
        ioWorker.submit(
                () -> {
                    List<LyricLine> cached = pluginLyricMem.get(canonical);
                    if (cached == null && "netease".equals(id.provider())) {
                        try {
                            cached = fetchNeteaseLyrics(Long.parseLong(id.nativeId()));
                        } catch (NumberFormatException ignored) {
                            cached = Collections.emptyList();
                        }
                    }
                    if (cached != null && !cached.isEmpty()) applyLyricsIfCurrent(track, cached);
                    if (!pluginHasCapability(id.provider(), ProviderCapability.LYRICS)) return;
                    pluginProviders
                            .lyrics(id)
                            .whenCompleteAsync(
                                    (payload, error) -> {
                                        if (error != null) {
                                            Logger.warn(
                                                    "plugin {} lyric fetch failed: {}",
                                                    id.provider(),
                                                    safeMessage(error));
                                            return;
                                        }
                                        try {
                                            List<LyricLine> lines =
                                                    LyricParser.fromPluginAssets(payload);
                                            if (!lines.isEmpty())
                                                pluginLyricMem.put(canonical, lines);
                                            applyLyricsIfCurrent(track, lines);
                                        } catch (RuntimeException parseError) {
                                            Logger.warn(
                                                    "plugin {} lyric parse failed: {}",
                                                    id.provider(),
                                                    safeMessage(parseError));
                                        }
                                    },
                                    ioWorker);
                });
    }

     /** True while {@code t} is still the track a loader fetched data for. Compares
     *  track identity rather than the queue slot it occupied: two consecutive
     *  selections can land on the same index (playlist A's row 3 -> playlist B's
     *  row 3), and a bare index check then lets a superseded fetch play the
     *  previous song's stream, skip past the new one, or publish its lyrics.
     *  Identity also survives an in-place retry — onPlaybackError re-resolves the
     *  very same track — which a monotonic revision counter would wrongly treat as
     *  a track change and discard. */
    private boolean isCurrentTrack(Track t) {
        if (t == null) return false;
        Track current = currentTrack();
        return current != null && current.canonicalId().equals(t.canonicalId());
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

    /**
     * Netease's own lyric payload (YRC/LRC/translation/romaji), disk-cache-first so a
     * previously-played song shows lyrics offline even when it has no AMLL TTML. The payload is
     * serialized to JSON next to the TTML cache (a .nlrc file).
     */
    private List<LyricLine> neteaseLyricCacheFirst(long songId) {
        String cached = diskCache.getNeteaseLyric(songId);
        if (cached != null) {
            try {
                byte[] data = readBytesFromFile(cached);
                if (data != null && data.length > 0) {
                    NeteaseLyric nl =
                            LYRIC_GSON.fromJson(
                                    new String(data, java.nio.charset.StandardCharsets.UTF_8),
                                    NeteaseLyric.class);
                    if (nl != null && !nl.isEmpty()) {
                        return LyricParser.fromNeteaseStrings(
                                nl.yrc, nl.lrc, nl.tlyric, nl.romalrc);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return Collections.emptyList();
    }

    private void preparePluginPlayback(Track track, boolean keepLyrics, long coverRevision) {
        playbackSession.resolving = true;
        long request = playbackSession.request();
        ioWorker.submit(
                () -> {
                    String cached = diskCache.getAudio(track.canonicalId());
                    onMain(
                            () -> {
                                if (!playbackSession.accepts(request, track)) return;
                                if (cached == null && !hasFreshPluginStream(track)) {
                                    track.streamUrl = null;
                                    track.streamHeaders.clear();
                                    resolveAndPlayPlugin(
                                            track, playbackSession.startPositionMs, coverRevision);
                                    return;
                                }
                                playbackSession.resolving = false;
                                if (cached != null)
                                    playBackend(cached, playbackSession.startPositionMs);
                                else
                                    playBackend(
                                            track.playable(),
                                            track.streamHeaders,
                                            playbackSession.startPositionMs);
                                if (!playbackSession.playing) backend.pause();
                                playing.set(playbackSession.playing);
                                if (!keepLyrics) loadPluginLyrics(track);
                                if (cached == null) cachePluginAudioAfterStart(track);
                                notifyPlayback();
                            });
                });
    }

    private void resolveAndPlayPlugin(Track track, long resumeMs, long expectedCoverRevision) {
        final MediaId id;
        try {
            id = MediaId.parse(track.canonicalId());
        } catch (IllegalArgumentException error) {
            skipUnplayable(track, I18n.tr("toast.play.badPluginId"));
            return;
        }
        if (!pluginHasCapability(id.provider(), ProviderCapability.RESOLVE_STREAM)) {
            skipUnplayable(track, I18n.tr("toast.play.pluginNoStream"));
            return;
        }
        playbackSession.resolving = true;
        final long request = playbackSession.request();
        pluginProviders
                .resolveStream(id, playLevel)
                .whenComplete(
                        (stream, error) ->
                                onMain(
                                        () ->
                                                completePluginResolution(
                                                        request,
                                                        track,
                                                        id,
                                                        stream,
                                                        error,
                                                        expectedCoverRevision)));
    }

    private void completePluginResolution(
            long request,
            Track track,
            MediaId id,
            StreamDescriptor stream,
            Throwable error,
            long expectedCoverRevision) {
        if (!playbackSession.accepts(request, track)) return;
        playbackSession.resolving = false;
        if (error != null || stream == null || stream.url == null || stream.url.isEmpty()) {
            Logger.warn(
                    "plugin {} stream resolve failed: {}",
                    id.provider(),
                    error != null ? safeMessage(error) : "empty URL");
            skipUnplayable(track, I18n.tr("toast.play.pluginStreamFailed"));
            return;
        }
        track.streamUrl = stream.url;
        track.streamHeaders.clear();
        track.streamHeaders.putAll(stream.headers);
        track.streamExpiresAtMs = stream.expiresAtMs;
        track.renditionId = stream.renditionId;
        track.trial = stream.trial;
        track.streamCacheable = stream.cacheable;
        post(
                () -> {
                    if (!playbackSession.accepts(request, track)) return;
                    title.set(orEmpty(track.title));
                    artist.set(orEmpty(track.artist));
                    album.set(orEmpty(track.album));
                    coverUrl.set(orEmpty(track.coverUrl));
                    durationMs.set(track.durationMs);
                    if (stream.trial) showToast(I18n.tr("toast.play.trialOnly"));
                });
        updateCover(track, expectedCoverRevision);
        loadPluginLyrics(track);
        Logger.info("play plugin {}: {}", id.provider(), track.title);
        playBackend(stream.url, stream.headers, playbackSession.startPositionMs);
        if (!playbackSession.playing) backend.pause();
        post(
                () -> {
                    if (playbackSession.accepts(request, track))
                        playing.set(playbackSession.playing);
                });
        notifyPlayback();
        cachePluginAudioAfterStart(track);
    }

    /**
     * Delay before the background auto-cache download starts, in ms.
     *
     * <p>Playing an uncached track pulls the same bytes twice at once: the
     * backend streams the URL to play it, and this cache download fetches the
     * identical file again. Where bandwidth is the limit -- phones especially --
     * the two halve each other, and the half that suffers is the one the user is
     * waiting on, which is why a first play drags while a second (served from
     * disk, no network at all) starts immediately. Letting playback have the
     *  pipe to itself first costs the cache nothing: it still finishes long
     *  before the track does. Two seconds is enough head-start for playback
     *  while still letting the cache get ahead if the live connection stalls.
     */
    private static final long AUTO_CACHE_DELAY_MS = 2_000L;

    /**
     * Queue the auto-cache for a track that just started playing. Skipped if the user has moved on
     * by the time it fires -- caching a track they skipped past would take bandwidth from whatever
     * they skipped to.
     */
    private void cachePluginAudioAfterStart(Track track) {
        final String mediaId = track != null ? track.canonicalId() : "";
        if (mediaId.isEmpty()) return;
        fadeWorker.schedule(
                () ->
                        onMain(
                                () -> {
                                    Track current = currentTrack();
                                    if (current == null || !mediaId.equals(current.canonicalId()))
                                        return;
                                    cachePluginAudioAsync(track, false);
                                }),
                AUTO_CACHE_DELAY_MS,
                TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<Boolean> cachePluginAudioAsync(Track track, boolean active) {
        if (track == null || track.canonicalId().isEmpty() || appShuttingDown) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        Track request = copyForQueue(track);
        if (hasFreshPluginStream(track)) {
            request.streamUrl = track.streamUrl;
            request.streamHeaders.putAll(track.streamHeaders);
            request.streamExpiresAtMs = track.streamExpiresAtMs;
            request.streamCacheable = track.streamCacheable;
            request.trial = track.trial;
        }
        String canonical = request.canonicalId();
        CompletableFuture<Boolean> result = audioCacheCoordinator.cache(request, active);
        result.whenComplete(
                (saved, error) -> {
                    if (error != null)
                        Logger.warn(
                                "plugin audio cache failed for {}: {}",
                                canonical,
                                safeMessage(error));
                    if (error == null && Boolean.TRUE.equals(saved))
                        onMain(
                                () -> {
                                    Track current = playbackSession.current();
                                    if (!appShuttingDown
                                            && current != null
                                            && canonical.equals(current.canonicalId())) {
                                        backend.notifyCacheComplete();
                                    }
                                });
                });
        return result;
    }

    private CompletableFuture<Boolean> downloadPluginCache(Track track, String target) {
        MediaId id =
                MediaId.parse(track.canonicalId())
                        .requireKind(dev.t1m3.qplayer.media.MediaKind.SONG);
        CompletableFuture<StreamDescriptor> stream;
        if (hasFreshPluginStream(track)) {
            StreamDescriptor known = new StreamDescriptor();
            known.url = track.streamUrl;
            known.headers.putAll(track.streamHeaders);
            known.trial = track.trial;
            known.cacheable = track.streamCacheable;
            stream = CompletableFuture.completedFuture(known);
        } else {
            stream = pluginProviders.resolveStream(id, playLevel);
        }
        return stream.thenCompose(
                resolved -> {
                    if (resolved == null
                            || resolved.url == null
                            || resolved.url.isEmpty()
                            || resolved.trial
                            || !resolved.cacheable) {
                        return CompletableFuture.completedFuture(Boolean.FALSE);
                    }
                    return pluginHostApi.downloadReturnedFile(
                            id.provider(),
                            resolved.url,
                            resolved.headers,
                            java.nio.file.Paths.get(target),
                            1024L * 1024L * 1024L,
                            120_000,
                            true);
                });
    }

    public void cacheMediaSong(String mediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            Track known = findTrackForMedia(mediaId);
            if (known == null) {
                showToast(I18n.tr("toast.cache.staleSong"));
                return;
            }
            try {
                MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.SONG);
            } catch (IllegalArgumentException error) {
                showToast(I18n.tr("toast.id.song"));
                return;
            }
            showToast(I18n.tr("toast.cache.started"));
            cachePluginAudioAsync(known, true)
                    .whenComplete(
                            (saved, error) -> {
                                if (error != null)
                                    showToast(
                                            I18n.tr(
                                                    "toast.cache.failedReason",
                                                    safeMessage(error)));
                                else
                                    showToast(
                                            I18n.tr(
                                                    Boolean.TRUE.equals(saved)
                                                            ? "toast.cache.done"
                                                            : "toast.cache.failed"));
                            });
        }
    }

    public void removeMediaCache(String mediaId) {
        ioWorker.submit(
                () -> {
                    boolean removed = diskCache.deleteAudio(mediaId);
                    if (removed) refreshCachedSongs();
                    showToast(
                            removed
                                    ? I18n.tr("toast.cache.removed")
                                    : I18n.tr("toast.cache.removeFailed"));
                });
    }

    private boolean pluginHasCapability(String provider, ProviderCapability capability) {
        if (provider == null || provider.isEmpty()) return false;
        for (PluginManifest manifest : pluginManager.enabledProviders()) {
            if (provider.equals(manifest.id)) return manifest.capabilitySet().contains(capability);
        }
        return false;
    }

    /** Account projection is scoped to the actual target, never the primary source. */
    public boolean providerLoggedIn(String providerId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            List<SourceAccountRow> accounts = sourceAccounts.peek();
            if (accounts == null) return false;
            for (SourceAccountRow account : accounts) {
                if (Objects.equals(providerId, account.providerId)) return account.loggedIn;
            }
            return false;
        }
    }

    public boolean canMutateMediaPlaylist(String canonicalId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            return authorizedPlaylist(canonicalId, ProviderCapability.PLAYLIST_MUTATION);
        }
    }

    public boolean canHeartMediaPlaylist(String canonicalId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            return authorizedPlaylist(canonicalId, ProviderCapability.HEART_RECOMMENDATION);
        }
    }

    private boolean authorizedPlaylist(String canonicalId, ProviderCapability capability) {
        try {
            MediaId id =
                    MediaId.parse(canonicalId)
                            .requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST);
            return providerLoggedIn(id.provider())
                    && pluginHasCapability(id.provider(), capability);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private boolean providerForMediaHas(String mediaId, ProviderCapability capability) {
        try { return pluginHasCapability(MediaId.parse(mediaId).provider(), capability); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private static String providerOf(Track track) {
        try { return MediaId.parse(track.canonicalId()).provider(); }
        catch (IllegalArgumentException ignored) { return ""; }
    }

    /** True when a plugin is currently driving playback (it holds the auto-advance
     *  block) and {@code track} is not one of that provider's songs. Whatever the
     *  plugin is coordinating, it can only address its own ids, so starting a local
     *  file or another source's song would break that coordination rather than play
     *  anything meaningful. The host stays out of what the feature actually is. */
    private boolean blockedByPluginSession(Track track) {
        String owner = pluginAutoAdvanceBlocker;
        if (owner.isEmpty() || track == null || owner.equals(providerOf(track))) return false;
        showToast(I18n.tr("toast.play.sessionLocked", pluginDisplayName(owner)));
        return true;
    }

    private String pluginDisplayName(String pluginId) {
        for (PluginManifest manifest : pluginManager.enabledProviders()) {
            if (manifest.id.equals(pluginId) && !manifest.name.isEmpty()) return manifest.name;
        }
        return pluginId;
    }

    private static boolean hasFreshPluginStream(Track track) {
        return track.streamUrl != null && !track.streamUrl.isEmpty()
                && (track.streamExpiresAtMs <= 0L
                || track.streamExpiresAtMs > System.currentTimeMillis() + 5_000L);
    }

    private void loadLocalLyrics(Track t) {
        List<LyricLine> lines = Collections.emptyList();
        if (t.lyricFilePath != null) {
            try {
                lines = LyricParser.parse(t.lyricFilePath, t.translationFilePath, t.romajiFilePath);
            } catch (Throwable e) {
                Logger.warn("lyric parse failed: {}", e.getMessage());
            }
        }
        applyLyricsIfCurrent(t, lines);
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
        synchronized (QmlRuntimeLock.MONITOR) {
            if (keyword == null || keyword.trim().isEmpty()) return;
            String kw = keyword.trim();
            synchronized (historyList) {
                for (int i = historyList.size() - 1; i >= 0; i--) {
                    if (historyList.get(i).equalsIgnoreCase(kw)) historyList.remove(i);
                }
                historyList.add(0, kw);
                if (historyList.size() > HISTORY_MAX) historyList.remove(historyList.size() - 1);
                List<String> snap = new ArrayList<>(historyList);
                post(() -> searchHistory.set(snap));
            }
            worker.submit(this::saveSearchHistory);
        }
    }

    public void removeSearchHistory(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            synchronized (historyList) {
                if (i < 0 || i >= historyList.size()) return;
                historyList.remove(i);
                List<String> snap = new ArrayList<>(historyList);
                post(() -> searchHistory.set(snap));
            }
            worker.submit(this::saveSearchHistory);
        }
    }

    public void clearSearchHistory() {
        synchronized (QmlRuntimeLock.MONITOR) {
            synchronized (historyList) {
                historyList.clear();
                post(() -> searchHistory.set(Collections.<String>emptyList()));
            }
            worker.submit(this::saveSearchHistory);
        }
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

    private void saveQueue() {
        saveSessionStateAsync();
    }

    private void loadQueue() {
        try {
            QueuePersistence.Snapshot restored = queuePersistence.load();
            List<Track> loaded = restored.tracks();
            for (Track track : loaded) routeInstalledPluginTrack(track);
            int savedIdx = restored.playIndex();
            long savedPos = restored.positionMs();
            int savedMode = restored.playMode();
            if (!loaded.isEmpty()) {
                playbackSession.queue.addAll(loaded);
                final List<Track> snap = new ArrayList<>(loaded);
                int idx = Math.max(0, Math.min(savedIdx, loaded.size() - 1));
                playbackSession.index = idx;
                long restoredCoverRevision = coverRevision.incrementAndGet();
                needsReplay = true;
                long clampedPos = Math.max(0L, savedPos);
                stoppedLyricPositionMs = clampedPos;
                pendingResumeIndex = idx;
                pendingResumeMs = clampedPos;
                final Track cur = loaded.get(idx);
                // Match playAt's pump-independent publication. A queued restore
                // would overwrite newer background metadata on the next frame.
                applyTrackLyricOffset(cur);
                queueTracks.set(snap);
                index.set(idx);
                currentFilePath.set(
                        cur.source == Track.Source.LOCAL && cur.filePath != null
                                ? cur.filePath
                                : "");
                currentMediaId.set(cur.canonicalId());
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
                currentLiked.set(pluginLikedSet.contains(cur.canonicalId()));
                currentLikeable.set(isMediaLikeable(cur.canonicalId()));
                playMode.set(Math.max(0, Math.min(2, savedMode)));
                wakeRenderer();
                // Load the full cover art + lyrics now (both cache-first internally)
                // instead of waiting for the user to press play — playAt() normally
                // does this, but playAt() itself isn't called until then.
                updateCover(cur, restoredCoverRevision);
                if (cur.source == Track.Source.LOCAL) {
                    ioWorker.submit(() -> loadLocalLyrics(cur));
                } else if (cur.source == Track.Source.PLUGIN) {
                    loadPluginLyrics(cur);
                } else if (cur.source == Track.Source.NETEASE && cur.neteaseId != 0L) {
                    ioWorker.submit(
                            () -> applyLyricsIfCurrent(cur, fetchNeteaseLyrics(cur.neteaseId)));
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
        synchronized (QmlRuntimeLock.MONITOR) {
            PluginManifest provider = primaryProviderWith(ProviderCapability.HOT_SEARCH);
            if (provider != null) {
                pluginProviders
                        .hotSearch(provider.id)
                        .whenComplete(
                                (values, error) ->
                                        post(
                                                () -> {
                                                    if (!provider.id.equals(
                                                            pluginRegistry.primaryProvider()))
                                                        return;
                                                    if (error != null) {
                                                        Logger.warn(
                                                                "plugin {} hot search failed: {}",
                                                                provider.id,
                                                                safeMessage(error));
                                                        return;
                                                    }
                                                    hotSearches.set(values);
                                                }));
                return;
            }

            hotSearches.set(Collections.<String>emptyList());
            return;
        }
    }

    /** Search enabled source plugins and publish provider-scoped result buckets. */
    public void search(String keyword) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (keyword == null || keyword.trim().isEmpty()) return;
            final String query = keyword.trim();
            final String key = query.toLowerCase(Locale.ROOT);
            currentSearchKey = key;
            currentSearchQuery = query;
            boolean pluginSearchStarted = searchPlugins(query, key);

            searchResults.set(Collections.<NeteaseSong>emptyList());

            if (!pluginSearchStarted) searchLoading.set(false);
            searchHasMore.set(false);
            rebuildSearchRows();
            return;
        }
    }

    /**
     * Album-mode counterpart to {@link #search}: cloudsearch type 10, no cache/
     * offline-fallback/pagination layer -- SearchPage's album filter is a much smaller surface than
     * song search, so it stays a plain single-shot fetch.
     */
    public void searchAlbums(String keyword) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (keyword == null || keyword.trim().isEmpty()) return;
            final String query = keyword.trim();
            final String key = query.toLowerCase(Locale.ROOT);
            currentSearchKey = key;
            currentSearchQuery = query;
            searchLoading.set(true);
            List<PluginManifest> providers = providersWith(ProviderCapability.SEARCH_ALBUMS);
            if (!providers.isEmpty()) {
                sourceSearchAlbumResults.set(Collections.<Album>emptyList());
                searchPluginCatalog(providers, query, key, ProviderCapability.SEARCH_ALBUMS);
                return;
            }

            searchLoading.set(false);
            sourceSearchAlbumResults.set(Collections.<Album>emptyList());
            return;
        }
    }

    /** Artist-mode counterpart to {@link #search}; see {@link #searchAlbums}. */
    public void searchArtists(String keyword) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (keyword == null || keyword.trim().isEmpty()) return;
            final String query = keyword.trim();
            final String key = query.toLowerCase(Locale.ROOT);
            currentSearchKey = key;
            currentSearchQuery = query;
            searchLoading.set(true);
            List<PluginManifest> providers = providersWith(ProviderCapability.SEARCH_ARTISTS);
            if (!providers.isEmpty()) {
                sourceSearchArtistResults.set(Collections.<Artist>emptyList());
                searchPluginCatalog(providers, query, key, ProviderCapability.SEARCH_ARTISTS);
                return;
            }

            searchLoading.set(false);
            sourceSearchArtistResults.set(Collections.<Artist>emptyList());
            return;
        }
    }

    /** Fetch the next cloudsearch page when SearchPage's virtual list nears its end. */
    public void loadMoreSearch() {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (!pluginSearchByProvider.isEmpty()) {
                loadMorePluginSearch();
                return;
            }
            return;
        }
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

    /**
     * Invalidate visible results as soon as the input text changes, before the debounce timer
     * starts the next requests. This also advances both network generation keys immediately, so an
     * older response cannot be published in the 350 ms debounce window and appear under an
     * unrelated keyword.
     */
    public void prepareSearch(String keyword) {
        synchronized (QmlRuntimeLock.MONITOR) {
            String query = keyword == null ? "" : keyword.trim();
            pluginSearchGeneration.incrementAndGet();
            pluginSearchByProvider.clear();
            pluginProviderNames.clear();
            pluginSearchCursors.clear();
            pluginSearchPageInFlight = false;
            currentSearchKey = query.toLowerCase(Locale.ROOT);
            currentSearchQuery = query;

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

    /**
     * Filter the local library by title/artist/album substring (case-insensitive) and publish to
     * {@link #localSearchResults}. Synchronous — the library is already in memory (scanned at
     * startup), no network round-trip to wait on.
     */
    public void searchLocal(String keyword) {
        synchronized (QmlRuntimeLock.MONITOR) {
            String q = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
            if (q.isEmpty()) {
                localSearchResults.set(Collections.<Track>emptyList());
                rebuildSearchRows();
                return;
            }
            List<Track> matches = new ArrayList<>();
            for (Track t : library) {
                if (containsIgnoreCase(t.title, q)
                        || containsIgnoreCase(t.artist, q)
                        || containsIgnoreCase(t.album, q)) {
                    matches.add(t);
                }
            }
            localSearchResults.set(matches);
            rebuildSearchRows();
        }
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
                row.kindLabel = I18n.tr("source.netease");
                row.index = i;
                row.name = s.name;
                row.artist = s.artist;
                row.artistId = s.artistId;
                row.artistIdsCsv = s.artistIdsCsv;
                row.artistNamesCsv = s.artistNamesCsv;
                row.coverThumbPath = s.coverThumbPath;
                row.id = s.id;
                row.menuEnabled = s.id != 0;
                // Same canonical form Track.canonicalId() derives for a NETEASE-source
                // track — gives the like button one id shape to parse regardless of
                // which of the three sources a row came from.
                row.mediaId = s.id != 0 ? MediaId.of("netease",
                        dev.t1m3.qplayer.media.MediaKind.SONG, Long.toString(s.id)).toString() : "";
                rows.add(row);
            }
        }
        // Interleave the sources instead of appending one whole list after another:
        // grouping them buried every source but the first under a full page of the
        // one before it. Rank 0 of every source comes first, then rank 1, and so on,
        // with pluginSearchByProvider's insertion order (primary first) breaking ties.
        // row.index stays the index within its own source because playPluginSearchResult
        // resolves the song through pluginSearchByProvider.get(providerId).get(index).
        int deepest = 0;
        for (List<Song> songs : pluginSearchByProvider.values()) {
            if (songs != null) deepest = Math.max(deepest, songs.size());
        }
        for (int rank = 0; rank < deepest; rank++) {
            for (Map.Entry<String, List<Song>> provider : pluginSearchByProvider.entrySet()) {
                List<Song> songs = provider.getValue();
                if (songs == null || rank >= songs.size()) continue;
                Song song = songs.get(rank);
                SearchRow row = new SearchRow();
                row.kind = "plugin";
                row.kindLabel = pluginProviderNames.getOrDefault(provider.getKey(), provider.getKey());
                row.providerId = provider.getKey();
                row.mediaId = song.id;
                row.artistMediaId = song.artistMediaId;
                row.index = rank;
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
                row.kindLabel = I18n.tr("source.local");
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

    /**
     * Route a click on the unified search list (SearchPage.qml) back to the right source-specific
     * play method by {@link SearchRow#kind}.
     */
    public void playSearchRow(int rowIndex) {
        synchronized (QmlRuntimeLock.MONITOR) {
            List<SearchRow> rows = searchRows.peek();
            if (rows == null || rowIndex < 0 || rowIndex >= rows.size()) return;
            SearchRow row = rows.get(rowIndex);
            switch (row.kind) {
                case "plugin":
                    playPluginSearchResult(row.providerId, row.index);
                    break;
                case "netease":
                    playSearchResult(row.index);
                    break;
                case "local":
                    playLocalSearchResult(row.index);
                    break;
                default:
                    break;
            }
        }
    }

    private static boolean containsIgnoreCase(String s, String needleLower) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    /**
     * Play a track from {@link #localSearchResults} — a filtered view of {@link #library}, so it
     * needs its own queue-index mapping rather than {@link #play(int)}, which indexes into the full
     * library.
     */
    public void playLocalSearchResult(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            List<Track> results = localSearchResults.peek();
            if (results == null || i < 0 || i >= results.size()) return;
            // Snapshot for the same reason as play(int): typing a new query rewrites
            // localSearchResults in place and must not mutate the live queue.
            playQueue(new ArrayList<>(results), i);
        }
    }

    /** Build a Netease CDN thumbnail URL (e.g. coverUrl + ?param=128y128).
     *  Returns the original url unchanged if it is null/empty or already has params. */
    private static String thumbUrl(String url, String size) {
        if (url == null || url.isEmpty()) return "";
        return url.contains("?") ? url + "&param=" + size + "y" + size
                                 : url + "?param=" + size + "y" + size;
    }

    /**
     * Only the legacy NetEase CDN contract understands the {@code param=} resize suffix. Provider
     * and custom-source artwork URLs must remain byte-for-byte as returned instead of having a
     * host-specific query parameter grafted onto them.
     */
    private static String trackCoverUrl(Track track, String size) {
        return ArtworkResolver.url(track, size);
    }

    /** Handle a playback error from the audio backend. For netease/plugin tracks
     *  whose cached streamUrl went stale (expired VIP link, region lock, a
     *  server-truncated response, etc.), clear the cache and re-resolve. Everything
     *  else falls through to autoAdvance. */
    private void onPlaybackError() {
        if (retryStaleStreamUrl(currentTrack())) return;
        skipUnplayable(currentTrack(), I18n.tr("toast.play.audioFailed"));
    }

    /**
     * Retry a track whose cached streamUrl may be bad (expired VIP link, region lock, a CDN that
     * cut the response short, ...): clear the url and re-resolve from wherever playback actually
     * stopped. errorRetryId/errorRetryMediaId guard against an endless error→re-resolve loop when
     * the fresh url also fails; they're reset when a track actually starts playing.
     *
     * @return true if a retry was launched (caller must not also skip/advance).
     */
    private boolean retryStaleStreamUrl(Track t) {
        if (t == null || t.streamUrl == null) return false;
        long backendMs = Math.max(0L, backend.position());
        Long shown = positionMs.peek();
        long resumeMs = Math.max(backendMs, shown != null ? shown : 0L);
        if (t.source == Track.Source.PLUGIN && !t.canonicalId().equals(errorRetryMediaId)) {
            errorRetryMediaId = t.canonicalId();
            beginLyricClockLoad(resumeMs);
            Logger.warn(
                    "playback error on plugin track {}, clearing stale url and retrying at {}ms",
                    t.canonicalId(),
                    resumeMs);
            t.streamUrl = null;
            t.streamHeaders.clear();
            playbackSession.startPositionMs = resumeMs;
            playbackSession.invalidate();
            resolveAndPlayPlugin(t, resumeMs, coverRevision.get());
            return true;
        }
        return false;
    }

    /**
     * Stop waiting on an unplayable queue entry and advance once. The failure count is reset only
     * by backend.onStarted, so a queue in which every entry is blocked makes at most one full pass
     * instead of spinning forever.
     */
    private void skipUnplayable(Track expected, String reason) {
        if (!isCurrentTrack(expected)) return;
        if (!playbackSession.playing) {
            needsReplay = true;
            pendingResumeIndex = playbackSession.index;
            pendingResumeMs = mediaSessionPosition();
            loading.set(false);
            showToast(reason);
            notifyPlayback();
            return;
        }
        consecutivePlaybackFailures++;
        int failures = consecutivePlaybackFailures;
        if (playbackSession.queue.size() <= 1 || failures >= playbackSession.queue.size()) {
            stoppedLyricPositionMs = Math.max(0L, backend.position());
            playbackStarted = false;
            playbackSession.playing = false;
            backend.pause();
            post(
                    () -> {
                        loading.set(false);
                        playing.set(false);
                        showToast(I18n.tr("toast.play.failed", reason));
                    });
            notifyPlayback();
            return;
        }
        post(() -> showToast(I18n.tr("toast.play.skipped", reason)));
        // Failed tracks must never obey repeat-one, and a deterministic walk avoids
        // shuffle selecting the same broken entry again before trying the others.
        playAt((playbackSession.index + 1) % playbackSession.queue.size());
    }

    /** How many plain recommended playlists to ask the source for; the titled
     *  sections it also returns are extra and are not counted against this. */
    private volatile int homePlaylistLimit = 12;
    private boolean homeRequested;

    public void setHomePlaylistLimit(int value) {
        synchronized (QmlRuntimeLock.MONITOR) {
            int next = Math.max(1, Math.min(100, value));
            if (next == homePlaylistLimit) return;
            homePlaylistLimit = next;
            // Startup seeds this before the first load; only a real change reloads.
            if (homeRequested) loadHome();
        }
    }

    private void publishHomeSections(List<HomeSection> sections) {
        List<HomeSection> published = new ArrayList<>();
        List<Playlist> cards = new ArrayList<>();
        for (HomeSection section : sections) {
            section.start = cards.size();
            section.count = section.playlists.size();
            cards.addAll(section.playlists);
            published.add(section);
        }
        sourceSectionPlaylists.set(Collections.unmodifiableList(cards));
        sourceHomeSections.set(Collections.unmodifiableList(published));
    }

    private void clearHomeSections() {
        sourceHomeSections.set(Collections.<HomeSection>emptyList());
        sourceSectionPlaylists.set(Collections.<Playlist>emptyList());
    }

    /** Load the home content: recommended songs (login) + recommended playlists. */
    public void loadHome() {
        synchronized (QmlRuntimeLock.MONITOR) {
            post(() -> homeLoading.set(true));
            // Switching away and back fast would otherwise let the first source's
            // in-flight result land on top of the second one's.
            final long generation = homeGeneration.incrementAndGet();
            homeRequested = true;
            PluginManifest provider = primaryProviderWith(ProviderCapability.HOME);
            if (provider != null) {
                pluginProviders
                        .home(provider.id, homePlaylistLimit)
                        .whenComplete(
                                (home, error) ->
                                        post(
                                                () -> {
                                                    if (generation != homeGeneration.get()) return;
                                                    if (!provider.id.equals(
                                                            pluginRegistry.primaryProvider()))
                                                        return;
                                                    homeLoading.set(false);
                                                    if (error != null) {
                                                        Logger.warn(
                                                                "plugin {} home failed: {}",
                                                                provider.id,
                                                                safeMessage(error));
                                                        clearHomeSections();
                                                        sourceRecommendPlaylists.set(
                                                                Collections.<Playlist>emptyList());
                                                        sourceRecommendations.set(
                                                                Collections.<Song>emptyList());
                                                        return;
                                                    }
                                                    ProviderHome value =
                                                            home != null
                                                                    ? home
                                                                    : new ProviderHome();
                                                    publishHomeSections(value.sections);
                                                    sourceRecommendPlaylists.set(
                                                            Collections.unmodifiableList(
                                                                    new ArrayList<>(
                                                                            value.playlists)));
                                                    sourceRecommendations.set(
                                                            Collections.unmodifiableList(
                                                                    new ArrayList<>(value.songs)));
                                                }));
                return;
            }
            clearHomeSections();
            // No source can serve home right now: publish the empty state instead of
            // leaving the previous source's recommendations up forever.
            sourceRecommendPlaylists.set(Collections.<Playlist>emptyList());
            sourceRecommendations.set(Collections.<Song>emptyList());

            recommendations.set(Collections.<NeteaseSong>emptyList());
            recommendPlaylists.set(Collections.<NeteasePlaylist>emptyList());
            homeLoading.set(false);
            return;
        }
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
        synchronized (QmlRuntimeLock.MONITOR) {
            openSongArtistPicker(playingArtistIdsCsv.peek(), playingArtistNamesCsv.peek());
        }
    }

    /**
     * Opens SongContextMenu's "查看歌手" picker. QML hands over the song's full artist list as two
     * parallel CSVs (ids comma-joined, names joined on U+0001 so a name containing a comma can't
     * desync the pairing) rather than a structured object -- passing a List built in QML script
     * back across the bridge as a Java method parameter isn't a pattern used anywhere else in this
     * codebase, while plain-string method args are.
     */
    public void openSongArtistPicker(String idsCsv, String namesCsv) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (idsCsv == null || idsCsv.isEmpty()) return;
            String[] ids = idsCsv.split(",", -1);
            String[] namesArr =
                    namesCsv != null ? namesCsv.split(String.valueOf((char) 1), -1) : new String[0];
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
    }

    public void openArtistCredit(NeteaseSong.ArtistRef ref) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (ref == null) return;
            if (ref.mediaId != null && !ref.mediaId.isEmpty()) openMediaArtist(ref.mediaId);
            else openArtist(ref.id);
        }
    }

    private void fetchSongArtistAvatars(List<NeteaseSong.ArtistRef> refs, long revision) {
        for (NeteaseSong.ArtistRef ref : refs) {
            if (songArtistPickerRevision != revision) return; // a newer picker open superseded this
            try {
                String cover = fetchArtistCreditCover(ref);
                if (cover == null || cover.isEmpty()) continue;
                ref.coverUrl = cover;
                ref.coverThumbPath =
                        ref.mediaId != null && !ref.mediaId.isEmpty()
                                ? cover
                                : (cover.startsWith("http") ? thumbUrl(cover, "128") : cover);
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
                post(
                        () -> {
                            if (songArtistPickerRevision != revision) return;
                            songArtistPickerList.set(copy);
                        });
            } catch (Throwable e) {
                Logger.warn(
                        "song-artist-picker avatar fetch failed for {}: {}",
                        ref.mediaId != null && !ref.mediaId.isEmpty()
                                ? ref.mediaId
                                : Long.toString(ref.id),
                        e.toString());
            }
        }
    }

    private String fetchArtistCreditCover(NeteaseSong.ArtistRef ref) throws Exception {
        String canonical =
                ref.mediaId != null && !ref.mediaId.isEmpty()
                        ? ref.mediaId
                        : "netease:artist:" + ref.id;
        MediaId id = MediaId.parse(canonical);
        if (!pluginHasCapability(id.provider(), ProviderCapability.ARTIST_DETAILS)) return null;
        Artist artist = pluginProviders.artist(id).get(8, TimeUnit.SECONDS);
        if (artist == null) return null;
        return artist.artworkUrl != null && !artist.artworkUrl.isEmpty()
                ? artist.artworkUrl
                : artist.coverUrl;
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
        synchronized (QmlRuntimeLock.MONITOR) {
            songArtistPickerRevision++;
            songArtistPickerOpen.set(false);
        }
    }

    /** Open an artist page: profile (name/avatar/bio) + hot songs + albums. */
    public void openMediaArtist(String mediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (mediaId == null || mediaId.isEmpty()) return;
            if (mediaId.indexOf(':') < 0) {
                try {
                    openArtist(Long.parseLong(mediaId));
                } catch (NumberFormatException ignored) {
                }
                return;
            }
            final MediaId id;
            try {
                id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.ARTIST);
            } catch (IllegalArgumentException error) {
                showToast(I18n.tr("toast.id.artist"));
                return;
            }
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
            pluginProviders
                    .artist(id)
                    .whenComplete(
                            (artist, error) ->
                                    post(
                                            () -> {
                                                if (!id.toString()
                                                        .equals(openSourceArtistId.peek())) return;
                                                artistLoading.set(false);
                                                if (error != null || artist == null) {
                                                    Logger.warn(
                                                            "plugin artist {} failed: {}",
                                                            id,
                                                            safeMessage(error));
                                                    showToast(
                                                            I18n.tr(
                                                                    "toast.artist.loadFailedPlugin"));
                                                    return;
                                                }
                                                artistName.set(artist.name);
                                                artistCoverPath.set(artist.artworkUrl);
                                                artistBriefDesc.set(artist.description);
                                                sourceArtistSongs.set(
                                                        Collections.unmodifiableList(
                                                                new ArrayList<>(artist.songs)));
                                                sourceArtistAlbums.set(
                                                        Collections.unmodifiableList(
                                                                new ArrayList<>(artist.albums)));
                                            }));
        }
    }

    public void openArtist(long artistId) {
        openMediaArtist("netease:artist:" + artistId);
    }

    /** Play a song from the open artist's hot-songs list. */
    public void playArtistSong(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (!openSourceArtistId.peek().isEmpty()) {
                playPluginSongList(sourceArtistSongs.peek(), i);
                return;
            }
            playSongList(artistSongs.peek(), i);
        }
    }

    /** Open an album page: profile (name/cover/artist) + full tracklist. */
    public void openMediaAlbum(String mediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (mediaId == null || mediaId.isEmpty()) return;
            if (mediaId.indexOf(':') < 0) {
                try {
                    openAlbum(Long.parseLong(mediaId));
                } catch (NumberFormatException ignored) {
                }
                return;
            }
            final MediaId id;
            try {
                id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.ALBUM);
            } catch (IllegalArgumentException error) {
                showToast(I18n.tr("toast.id.album"));
                return;
            }
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
            pluginProviders
                    .album(id)
                    .whenComplete(
                            (album, error) ->
                                    post(
                                            () -> {
                                                if (!id.toString().equals(openSourceAlbumId.peek()))
                                                    return;
                                                albumLoading.set(false);
                                                if (error != null || album == null) {
                                                    Logger.warn(
                                                            "plugin album {} failed: {}",
                                                            id,
                                                            safeMessage(error));
                                                    showToast(
                                                            I18n.tr(
                                                                    "toast.album.loadFailedPlugin"));
                                                    return;
                                                }
                                                albumName.set(album.name);
                                                albumCoverPath.set(album.artworkUrl);
                                                albumArtistName.set(album.artistName);
                                                albumArtistMediaId.set(album.artistMediaId);
                                                albumPublishYear.set(
                                                        album.publishTimeMs > 0
                                                                ? new java.text.SimpleDateFormat(
                                                                                I18n.tr(
                                                                                        "album.yearFormat"),
                                                                                java.util.Locale
                                                                                        .getDefault())
                                                                        .format(
                                                                                new java.util.Date(
                                                                                        album.publishTimeMs))
                                                                : "");
                                                sourceAlbumTracks.set(
                                                        Collections.unmodifiableList(
                                                                new ArrayList<>(album.songs)));
                                            }));
        }
    }

    public void openAlbum(long albumId) {
        openMediaAlbum("netease:album:" + albumId);
    }

    /** Play a song from the open album's tracklist. */
    public void playAlbumTrack(int i) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (!openSourceAlbumId.peek().isEmpty()) {
                playPluginSongList(sourceAlbumTracks.peek(), i);
                return;
            }
            playSongList(albumTracks.peek(), i);
        }
    }

    public void openMediaPlaylist(String mediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            loadMediaPlaylist(mediaId, false);
        }
    }

    /** Refresh the current playlist without clearing its rows or filter state. */
    public void refreshPlaylist() {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (Boolean.TRUE.equals(playlistLoading.peek())) return;
            String mediaId = openSourcePlaylistId.peek();
            if (mediaId != null && !mediaId.isEmpty()) loadMediaPlaylist(mediaId, true);
            else if (currentPlaylistId > 0) openPlaylist(currentPlaylistId);
        }
    }

    private void loadMediaPlaylist(String mediaId, boolean refreshing) {
        if (mediaId == null || mediaId.isEmpty()) return;
        if (mediaId.indexOf(':') < 0) {
            try { openPlaylist(Long.parseLong(mediaId)); } catch (NumberFormatException ignored) {}
            return;
        }
        final MediaId id;
        try { id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST); }
        catch (IllegalArgumentException error) { showToast(I18n.tr("toast.id.playlist")); return; }
        currentPlaylistId = 0L;
        openPlaylistId.set(0L);
        openSourcePlaylistId.set(id.toString());
        // Capabilities belong to the playlist's own source, not to whichever source
        // happens to be primary: an open QQ playlist must not offer NetEase's
        // heart-recommendation button (and vice versa).
        sourceHeartRecommendationAvailable.set(
                pluginHasCapability(id.provider(), ProviderCapability.HEART_RECOMMENDATION));
        sourcePlaylistCoverAvailable.set(
                pluginHasCapability(id.provider(), ProviderCapability.PLAYLIST_COVER));
        playlistLoading.set(true);
        playlistOffline.set(false);
        sourcePlaylistReorderable.set(false);
        if (!refreshing) {
            playlistTracks.set(Collections.<NeteaseSong>emptyList());
            sourcePlaylistTracks.set(Collections.<Song>emptyList());
            playlistTitle.set("");
            playlistCoverPath.set("");
            playlistSubscribed.set(false);
            playlistOwned.set(false);
            playlistDeletable.set(false);
        }
        pluginProviders.playlist(id).whenComplete((playlist, error) -> post(() -> {
            if (!id.toString().equals(openSourcePlaylistId.peek())) return;
            playlistLoading.set(false);
            Playlist resolved = playlist;
            if (error != null || resolved == null) {
                Logger.warn("plugin playlist {} failed: {}", id, safeMessage(error));
                resolved = mediaPlaylistCacheIndex.get(id.toString());
                if (resolved == null) {
                    showToast(I18n.tr("toast.playlist.loadFailedPlugin"));
                    return;
                }
                playlistOffline.set(true);
            }
            long publishStartNanos = System.nanoTime();
            playlistTitle.set(resolved.name);
            playlistCoverPath.set(resolved.artworkUrl);
            playlistSubscribed.set(resolved.subscribed);
            playlistOwned.set(resolved.owned);
            playlistDeletable.set(resolved.deletable);
            // Dragging is offered only on a playlist that is both the user's own and
            // whose source can save an order. An offline snapshot is read-only too:
            // there is nowhere to send the new arrangement.
            sourcePlaylistReorderable.set(resolved.owned
                    && !Boolean.TRUE.equals(playlistOffline.peek())
                    && pluginHasCapability(id.provider(), ProviderCapability.PLAYLIST_REORDER));
            sourcePlaylistTracks.set(Collections.unmodifiableList(
                    new ArrayList<>(resolved.songs)));
            // Snapshotting the playlist costs a full JSON round trip of every track
            // — measured at 30-70 ms for a thousand-track list — which has no
            // business running on the render thread while the rows are appearing.
            final Playlist snapshot = resolved;
            worker.submit(() -> {
                mediaPlaylistCacheIndex.upsert(snapshot);
                mediaPlaylistCacheIndex.save();
            });
            Logger.info("playlist {} published {} rows in {} ms", id,
                    resolved.songs.size(),
                    (System.nanoTime() - publishStartNanos) / 1_000_000L);
        }));
    }

    public void openPlaylist(long playlistId) {
        openMediaPlaylist("netease:playlist:" + playlistId);
    }

    /**
     * Collect / un-collect the currently open playlist. No-op on your own playlist or when signed
     * out. Optimistically flips the icon, reverting if the server refuses.
     */
    public void togglePlaylistSubscribe() {
        synchronized (QmlRuntimeLock.MONITOR) {
            String id = openSourcePlaylistId.peek();
            if (id != null && !id.isEmpty()) toggleMediaPlaylistSubscribe(id);
        }
    }

    private void toggleMediaPlaylistSubscribe(String mediaId) {
        if (Boolean.TRUE.equals(playlistOwned.peek()) || !canMutateMediaPlaylist(mediaId)) return;
        setMediaPlaylistSubscribed(mediaId, !Boolean.TRUE.equals(playlistSubscribed.peek()));
    }

    /**
     * Collect / un-collect a playlist by id, whether or not it is the one currently open. 我的's card
     * menu acts on a grid entry rather than on the detail page, so it cannot read the target's
     * state from {@link #playlistSubscribed} — it passes the desired state in instead.
     *
     * <p>The optimistic flip and its revert only apply while the affected playlist is the open one;
     * the toast and the 我的 refresh always run, since the result matters to the grid even when no
     * detail page is showing it.
     */
    public void setMediaPlaylistSubscribed(String mediaId, boolean subscribed) {
        synchronized (QmlRuntimeLock.MONITOR) {
            // A collect is heavily risk-controlled upstream, so a second tap while one
            // is in flight is dropped rather than queued — same rule the detail page's
            // button has always followed.
            if (subscribeBusy) return;
            final MediaId id;
            try {
                id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST);
            } catch (IllegalArgumentException error) {
                showToast(I18n.tr("toast.id.playlist"));
                return;
            }
            if (!pluginHasCapability(id.provider(), ProviderCapability.PLAYLIST_MUTATION)) return;
            if (!canMutateMediaPlaylist(id.toString())) return;
            subscribeBusy = true;
            if (id.toString().equals(openSourcePlaylistId.peek()))
                playlistSubscribed.set(subscribed);
            pluginProviders
                    .mutatePlaylist(
                            id,
                            subscribed ? "subscribe" : "unsubscribe",
                            Collections.<MediaId>emptyList(),
                            null)
                    .whenComplete(
                            (success, error) ->
                                    post(
                                            () -> {
                                                subscribeBusy = false;
                                                boolean stillOpen =
                                                        id.toString()
                                                                .equals(
                                                                        openSourcePlaylistId
                                                                                .peek());
                                                if (error == null && Boolean.TRUE.equals(success)) {
                                                    showToast(
                                                            I18n.tr(
                                                                    subscribed
                                                                            ? "toast.playlist.subscribed"
                                                                            : "toast.playlist.unsubscribed"));
                                                    loadMyPlaylists();
                                                } else {
                                                    if (stillOpen)
                                                        playlistSubscribed.set(!subscribed);
                                                    showToast(
                                                            I18n.tr(
                                                                    "toast.actionFailed",
                                                                    safeMessage(error)));
                                                }
                                            }));
        }
    }

    /** Per-source slices of 我的, keyed by provider id. Main thread only. */
    private final Map<String, List<Playlist>> myPlaylistsBySource = new LinkedHashMap<>();
    /** Provider order the current aggregation publishes in (primary first). */
    private List<String> myPlaylistSourceOrder = Collections.emptyList();
    /** Discards results of a superseded aggregation (source enabled/disabled/switched). */
    private final AtomicLong myPlaylistsGeneration = new AtomicLong();
    private int myPlaylistsPending;

    /**
     * Load the signed-in user's playlists (favorites + created) from every enabled source, not just
     * the primary one: sources keep independent accounts, and a playlist stays openable by its own
     * provider regardless of which one is primary.
     */
    public void loadMyPlaylists() {
        synchronized (QmlRuntimeLock.MONITOR) {
            List<PluginManifest> providers = new ArrayList<>();
            String primary = pluginRegistry.primaryProvider();
            for (PluginManifest manifest : pluginManager.enabledProviders()) {
                if (manifest.capabilitySet().contains(ProviderCapability.USER_PLAYLISTS)) {
                    if (manifest.id.equals(primary)) providers.add(0, manifest);
                    else providers.add(manifest);
                }
            }
            if (!providers.isEmpty()) {
                final long generation = myPlaylistsGeneration.incrementAndGet();
                final List<String> order = new ArrayList<>();
                for (PluginManifest manifest : providers) order.add(manifest.id);
                post(
                        () -> {
                            myPlaylistSourceOrder = order;
                            // A source that is gone (disabled/removed) must not keep contributing.
                            myPlaylistsBySource.keySet().retainAll(order);
                            myPlaylistsPending = order.size();
                            publishMyPlaylists();
                        });
                for (PluginManifest manifest : providers) {
                    final String providerId = manifest.id;
                    final String providerName = manifest.name;
                    pluginProviders
                            .userPlaylists(providerId, 100)
                            .whenComplete(
                                    (playlists, error) ->
                                            post(
                                                    () -> {
                                                        if (generation
                                                                != myPlaylistsGeneration.get())
                                                            return;
                                                        myPlaylistsPending--;
                                                        if (error != null) {
                                                            Logger.warn(
                                                                    "plugin {} user playlists"
                                                                            + " failed: {}",
                                                                    providerId,
                                                                    safeMessage(error));
                                                            myPlaylistsBySource.remove(providerId);
                                                        } else {
                                                            for (Playlist playlist : playlists) {
                                                                playlist.sourceName = providerName;
                                                            }
                                                            myPlaylistsBySource.put(
                                                                    providerId, playlists);
                                                        }
                                                        publishMyPlaylists();
                                                        // A signed-out source simply reports an
                                                        // error, so only
                                                        // complain once every source failed while
                                                        // signed in.
                                                        if (myPlaylistsPending == 0
                                                                && myPlaylistsBySource.isEmpty()
                                                                && Boolean.TRUE.equals(
                                                                        loggedIn.peek())) {
                                                            showToast(
                                                                    I18n.tr(
                                                                            "toast.playlist.loadFailedPlugin"));
                                                        }
                                                    }));
                }
                return;
            }
            myPlaylistsGeneration.incrementAndGet();
            post(
                    () -> {
                        myPlaylistSourceOrder = Collections.emptyList();
                        myPlaylistsBySource.clear();
                        myPlaylistsPending = 0;
                        publishMyPlaylists();
                    });

            myPlaylists.set(Collections.<NeteasePlaylist>emptyList());
            return;
        }
    }

    /** Flatten {@link #myPlaylistsBySource} into 我的, primary source first. Called
     *  after every per-source completion so the primary's playlists appear without
     *  waiting on a slow secondary source. */
    private void publishMyPlaylists() {
        List<Playlist> merged = new ArrayList<>();
        for (String providerId : myPlaylistSourceOrder) {
            List<Playlist> slice = myPlaylistsBySource.get(providerId);
            if (slice != null) merged.addAll(slice);
        }
        if (MyPlaylistOrderStore.MODE_CUSTOM.equals(librarySortMode.peek())) {
            merged = applyMyPlaylistOrder(merged);
        }
        // Providers return fresh DTOs on every visit. Preserve the live model
        // when all content is unchanged so virtual delegates keep their covers.
        if (!samePlaylistRows(sourceMyPlaylists.peek(), merged)) {
            sourceMyPlaylists.set(Collections.unmodifiableList(merged));
        }
        playlistCount.set(merged.size());
    }

    private static boolean samePlaylistRows(List<Playlist> previous, List<Playlist> next) {
        if (previous == next) return true;
        if (previous == null || previous.size() != next.size()) return false;
        for (int i = 0; i < next.size(); i++) {
            Playlist a = previous.get(i), b = next.get(i);
            if (a == b) continue;
            if (a == null || b == null
                    || !Objects.equals(a.id, b.id)
                    || !Objects.equals(a.name, b.name)
                    || !Objects.equals(a.description, b.description)
                    || !Objects.equals(a.artworkUrl, b.artworkUrl)
                    || !Objects.equals(a.coverUrl, b.coverUrl)
                    || !Objects.equals(a.coverThumbPath, b.coverThumbPath)
                    || !Objects.equals(a.sourceName, b.sourceName)
                    || a.trackCount != b.trackCount || a.playCount != b.playCount
                    || a.subscribed != b.subscribed || a.owned != b.owned
                    || a.mutable != b.mutable || a.deletable != b.deletable
                    || !Objects.equals(a.songs, b.songs)) {
                return false;
            }
            if (a.owner != b.owner && (a.owner == null || b.owner == null
                    || !Objects.equals(a.owner.id, b.owner.id)
                    || !Objects.equals(a.owner.name, b.owner.name))) {
                return false;
            }
        }
        return true;
    }

    /** Reorders {@code merged} (already grouped by source) against the stored
     *  custom order: a stored id that is still present keeps that relative
     *  position, anything not yet stored (a playlist followed since, or the very
     *  first drag) keeps its source-grouped position among the rest. */
    private List<Playlist> applyMyPlaylistOrder(List<Playlist> merged) {
        List<String> order = myPlaylistOrderStore.order();
        if (order.isEmpty()) return merged;
        Map<String, Playlist> byId = new LinkedHashMap<>();
        for (Playlist playlist : merged) byId.put(playlist.id, playlist);
        List<Playlist> out = new ArrayList<>(merged.size());
        for (String id : order) {
            Playlist playlist = byId.remove(id);
            if (playlist != null) out.add(playlist);
        }
        out.addAll(byId.values());
        return out;
    }

    /**
     * Sort-menu entry point: "source" reverts to the server's own grouping. Switching into "custom"
     * from anything else seeds it from whatever is on screen right now — not a stale arrangement
     * dragged into place in some earlier session — so it reads as "start dragging from here" rather
     * than "jump back to where I left off". Once already in custom, re-picking it is a no-op that
     * leaves an in-progress drag alone.
     */
    public void setLibrarySortMode(String mode) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                boolean enteringCustom =
                        MyPlaylistOrderStore.MODE_CUSTOM.equals(mode)
                                && !MyPlaylistOrderStore.MODE_CUSTOM.equals(
                                        myPlaylistOrderStore.mode());
                if (enteringCustom) {
                    List<Playlist> current = sourceMyPlaylists.peek();
                    List<String> ids = new ArrayList<>(current != null ? current.size() : 0);
                    if (current != null) for (Playlist playlist : current) ids.add(playlist.id);
                    myPlaylistOrderStore.setOrder(ids);
                } else {
                    myPlaylistOrderStore.setMode(mode);
                }
                librarySortMode.set(myPlaylistOrderStore.mode());
                publishMyPlaylists();
                savePlaylistOrder();
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    /**
     * Drag-to-reorder on 我的's grid/list: {@code from}/{@code to} are positions in the currently
     * published {@link #sourceMyPlaylists}. Persists at once — the id list is tiny — and switches
     * the sort mode to custom, same as picking it from the menu would.
     */
    public void moveMyPlaylist(int from, int to) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                List<Playlist> current = sourceMyPlaylists.peek();
                if (current == null
                        || from < 0
                        || from >= current.size()
                        || to < 0
                        || to >= current.size()
                        || from == to) {
                    return;
                }
                List<Playlist> reordered = new ArrayList<>(current);
                reordered.add(to, reordered.remove(from));
                List<String> ids = new ArrayList<>(reordered.size());
                for (Playlist playlist : reordered) ids.add(playlist.id);
                myPlaylistOrderStore.setOrder(ids);
                librarySortMode.set(myPlaylistOrderStore.mode());
                sourceMyPlaylists.set(Collections.unmodifiableList(reordered));
                savePlaylistOrder();
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    /**
     * Drag-to-reorder on 本地歌单's grid/list — always available, since custom is the only order a set
     * of local playlists has.
     */
    public void moveLocalPlaylistCard(int from, int to) {
        try {
            synchronized (QmlRuntimeLock.MONITOR) {
                if (!localPlaylistStore.move(from, to)) return;
                saveLocalPlaylists();
                publishLocalPlaylists();
            }

        } catch (IllegalStateException error) {
            Logger.warn("playlist mutation failed: {}", safeMessage(error));
            showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
        }
    }

    /**
     * Create a new (public) playlist named {@code name} on {@code providerId}, then refresh 我的. An
     * empty {@code providerId} keeps the old behaviour of targeting whichever source is currently
     * primary, so the picker in the create dialog only needs to pass something when the user chose
     * a non-primary source.
     */
    public void createPlaylist(String name, String providerId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            String normalized = name != null ? name.trim() : "";
            if (normalized.isEmpty()) return;
            String target =
                    providerId != null && !providerId.trim().isEmpty()
                            ? providerId.trim()
                            : pluginRegistry.primaryProvider();
            if (!providerLoggedIn(target)
                    || !pluginHasCapability(target, ProviderCapability.PLAYLIST_MUTATION)) return;
            pluginProviders
                    .createPlaylist(target, normalized, false)
                    .whenComplete(
                            (id, error) ->
                                    post(
                                            () -> {
                                                if (error == null && id != null && !id.isEmpty()) {
                                                    showToast(I18n.tr("toast.playlist.created"));
                                                    loadMyPlaylists();
                                                } else
                                                    showToast(
                                                            I18n.tr(
                                                                    "toast.playlist.createFailedReason",
                                                                    safeMessage(error)));
                                            }));
        }
    }

    /**
     * Set a playlist's cover to a local image file, then refresh the detail view (and 我的, whose
     * cards also show it). {@code playlistKey} is the open playlist's canonical media id, or a
     * leftover numeric netease id. Owned-playlist enforcement lives in the QML (same as the delete
     * button — {@code playlistOwned}), not here.
     */
    public void setPlaylistCover(String playlistKey, String localImagePath) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (playlistKey == null || localImagePath == null) return;
            final String key = playlistKey.trim();
            final String path = localImagePath.trim();
            if (key.isEmpty() || path.isEmpty()) return;
            worker.submit(
                    () -> {
                        byte[] data;
                        try {
                            data = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
                        } catch (Throwable e) {
                            Logger.warn("read cover file {} failed: {}", path, e.getMessage());
                            showToast(I18n.tr("toast.cover.readFailed"));
                            return;
                        }
                        uploadPlaylistCover(key, data, new java.io.File(path).getName());
                    });
        }
    }

    /**
     * Set a playlist's cover from raw image bytes — Android's native gallery picker hands over a
     * {@code content://} URI with no filesystem path to read, so the host reads it itself and
     * passes the bytes straight through.
     */
    public void setPlaylistCoverBytes(String playlistKey, byte[] data, String filename) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (playlistKey == null || data == null) return;
            final String key = playlistKey.trim();
            if (key.isEmpty()) return;
            worker.submit(
                    () ->
                            uploadPlaylistCover(
                                    key, data, filename == null ? "cover.jpg" : filename));
        }
    }

    private void uploadPlaylistCover(String playlistKey, byte[] data, String filename) {
        if (data.length == 0) {
            showToast(I18n.tr("toast.cover.empty"));
            return;
        }
        if (data.length > PluginProviderService.MAX_COVER_IMAGE_BYTES) {
            showToast(I18n.tr("toast.cover.tooLarge"));
            return;
        }
        if (LocalPlaylist.isLocalPlaylistId(playlistKey)) {
            storeLocalPlaylistCover(playlistKey, data, filename);
        } else {
            String canonical =
                    playlistKey.indexOf(':') >= 0 ? playlistKey : "netease:playlist:" + playlistKey;
            onMain(() -> uploadPluginPlaylistCover(canonical, data, filename));
        }
    }

    private void uploadPluginPlaylistCover(String playlistKey, byte[] data, String filename) {
        final MediaId id;
        try {
            id = MediaId.parse(playlistKey).requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST);
        } catch (IllegalArgumentException error) {
            showToast(I18n.tr("toast.id.playlist"));
            return;
        }
        if (!providerLoggedIn(id.provider())) return;
        if (!pluginHasCapability(id.provider(), ProviderCapability.PLAYLIST_COVER)) {
            showToast(I18n.tr("toast.cover.unsupported"));
            return;
        }
        pluginProviders
                .setPlaylistCover(id, data, filename, mimeFromFilename(filename))
                .whenComplete(
                        (success, error) ->
                                post(
                                        () -> {
                                            if (error == null && Boolean.TRUE.equals(success)) {
                                                showToast(I18n.tr("toast.cover.updated"));
                                                // refreshPlaylist(), not openMediaPlaylist(): the
                                                // user is
                                                // looking at this playlist right now, and a full
                                                // open clears
                                                // the rows and the in-playlist filter text before
                                                // refetching,
                                                // so the list blinks out and their filter is lost
                                                // just to
                                                // pick up one new artwork url.
                                                if (id.toString()
                                                        .equals(openSourcePlaylistId.peek())) {
                                                    refreshPlaylist();
                                                }
                                                loadMyPlaylists();
                                            } else {
                                                showToast(
                                                        I18n.tr(
                                                                "toast.cover.updateFailedReason",
                                                                error != null
                                                                        ? safeMessage(error)
                                                                        : I18n.tr(
                                                                                "toast.cover.updateFailed")));
                                            }
                                        }));
    }

    private static String mimeFromFilename(String filename) {
        if (filename == null) return "";
        String lower = filename.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        if ("png".equals(ext)) return "image/png";
        if ("gif".equals(ext)) return "image/gif";
        if ("webp".equals(ext)) return "image/webp";
        if ("bmp".equals(ext)) return "image/bmp";
        if ("jpg".equals(ext) || "jpeg".equals(ext)) return "image/jpeg";
        return "";
    }

    /** Host hook to launch the platform image picker for a playlist cover. */
    public interface CoverPicker {
        void pick(String playlistKey);
    }

    private volatile CoverPicker coverPicker;

    public void setCoverPicker(CoverPicker p) {
        synchronized (QmlRuntimeLock.MONITOR) {
            this.coverPicker = p;
        }
    }

    /**
     * QML calls this to launch the platform picker for the currently open playlist. Android reads
     * the picked image's bytes and calls {@link #setPlaylistCoverBytes}; desktop passes the
     * selected local path to {@link #setPlaylistCover}.
     */
    public void pickPlaylistCover() {
        synchronized (QmlRuntimeLock.MONITOR) {
            String sourceId = openSourcePlaylistId.peek();
            if (sourceId != null && !sourceId.isEmpty()) {
                pickPlaylistCover(sourceId);
                return;
            }
            if (currentPlaylistId != 0L) pickPlaylistCover(Long.toString(currentPlaylistId));
        }
    }

    public void pickPlaylistCover(String playlistKey) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (playlistKey == null || playlistKey.trim().isEmpty()) return;
            final String key = playlistKey.trim();
            CoverPicker p = coverPicker;
            if (p != null) onHost(() -> p.pick(key));
        }
    }

    /** Delete a playlist owned by the user, then refresh 我的. */
    public void deletePlaylist(long playlistId) {
        deleteMediaPlaylist("netease:playlist:" + playlistId);
    }

    public void deleteMediaPlaylist(String playlistMediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            final MediaId id;
            try {
                id =
                        MediaId.parse(playlistMediaId)
                                .requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST);
            } catch (IllegalArgumentException error) {
                showToast(I18n.tr("toast.id.playlist"));
                return;
            }
            if (!canMutateMediaPlaylist(id.toString())) return;
            pluginProviders
                    .mutatePlaylist(id, "delete", Collections.<MediaId>emptyList(), null)
                    .whenComplete(
                            (success, error) ->
                                    post(
                                            () -> {
                                                if (error == null && Boolean.TRUE.equals(success)) {
                                                    showToast(I18n.tr("toast.playlist.deleted"));
                                                    loadMyPlaylists();
                                                } else
                                                    showToast(
                                                            I18n.tr(
                                                                    "toast.playlist.deleteFailedReason",
                                                                    safeMessage(error)));
                                            }));
        }
    }

    /** Add a track to one of the user's playlists (from a song's long-press menu). */
    public void addToPlaylist(long playlistId, long songId) {
        addMediaToPlaylist("netease:playlist:" + playlistId, neteaseMediaId(songId));
    }

    public void addMediaToPlaylist(String playlistMediaId, String songMediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            mutateMediaPlaylist(playlistMediaId, songMediaId, true, false);
        }
    }

    public void removeMediaFromCurrentPlaylist(String songMediaId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            mutateMediaPlaylist(openSourcePlaylistId.peek(), songMediaId, false, true);
        }
    }

    public void removeMediaManyFromCurrentPlaylist(String idsText) {
        synchronized (QmlRuntimeLock.MONITOR) {
            List<String> ids = splitIds(idsText);
            String source = openSourcePlaylistId.peek();
            if (!ids.isEmpty() && canMutateMediaPlaylist(source))
                removeManyPluginPlaylistTracks(source, ids);
        }
    }

    private void removeManyPluginPlaylistTracks(String playlistMediaId, List<String> songMediaIds) {
        final MediaId playlistId;
        try {
            playlistId =
                    MediaId.parse(playlistMediaId)
                            .requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST);
        } catch (IllegalArgumentException error) {
            showToast(I18n.tr("toast.id.media"));
            return;
        }
        if (!canMutateMediaPlaylist(playlistId.toString())) return;
        List<MediaId> songs = new ArrayList<>();
        for (String raw : songMediaIds) {
            try {
                songs.add(MediaId.parse(raw).requireKind(dev.t1m3.qplayer.media.MediaKind.SONG));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (songs.isEmpty()) return;
        pluginProviders
                .mutatePlaylist(playlistId, "remove", songs, null)
                .whenComplete(
                        (success, error) ->
                                post(
                                        () -> {
                                            if (error == null && Boolean.TRUE.equals(success)) {
                                                Set<String> gone = new HashSet<>();
                                                for (MediaId song : songs)
                                                    gone.add(song.toString());
                                                if (playlistId
                                                        .toString()
                                                        .equals(openSourcePlaylistId.peek())) {
                                                    dropOpenSourcePlaylistTracks(gone);
                                                }
                                                showToast(
                                                        I18n.tr(
                                                                "toast.playlist.songsRemoved",
                                                                gone.size()));
                                            } else {
                                                showToast(
                                                        I18n.tr(
                                                                "toast.playlist.removeFailedReason",
                                                                safeMessage(error)));
                                            }
                                        }));
    }

    public void unlikeMediaMany(String idsText) {
        synchronized (QmlRuntimeLock.MONITOR) {
            List<String> ids = splitIds(idsText);
            if (ids.isEmpty()) return;
            worker.submit(
                    () -> {
                        int count = 0;
                        for (String raw : ids) {
                            if (unlikeOne(raw)) count++;
                        }
                        final int removed = count;
                        post(
                                () -> {
                                    showToast(
                                            removed > 0
                                                    ? I18n.tr(
                                                            "toast.playlist.songsUnliked", removed)
                                                    : I18n.tr("toast.unlike.failed"));
                                    // Same staleness as the single-song toggle: refresh once for
                                    // the
                                    // whole batch rather than once per song.
                                    if (removed > 0) loadMyPlaylists();
                                });
                    });
        }
    }

    private boolean unlikeOne(String raw) {
        try {
            MediaId id = MediaId.parse(raw).requireKind(dev.t1m3.qplayer.media.MediaKind.SONG);
            if (!providerLoggedIn(id.provider())
                    || !pluginHasCapability(id.provider(), ProviderCapability.LIKE)) return false;
            Boolean success = pluginProviders.setLiked(id, false).get();
            if (!Boolean.TRUE.equals(success)) return false;
            post(() -> applyPluginLiked(id, false));
            return true;
        } catch (Exception error) {
            Logger.warn("unlike {} failed: {}", raw, safeMessage(error));
            return false;
        }
    }

    // --- Liking an arbitrary row (search results, queue) --------------------
    //
    // toggleLike() above only ever resolves the CURRENT track; these three let a
    // row that is not (or not yet) playing carry its own heart button. Same
    // netease-legacy-vs-plugin routing as unlikeOne, generalized to toggle
    // either direction instead of only unliking.

    /**
     * Whether {@code canonicalId} (a song media id) can be liked at all — the row's heart button
     * binds its visibility to this.
     */
    public boolean isMediaLikeable(String canonicalId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            try {
                MediaId id =
                        MediaId.parse(canonicalId)
                                .requireKind(dev.t1m3.qplayer.media.MediaKind.SONG);
                return pluginHasCapability(id.provider(), ProviderCapability.LIKE);
            } catch (IllegalArgumentException error) {
                return false;
            }
        }
    }

    /** Current liked state, for the heart's filled/outline glyph. */
    public boolean isMediaLiked(String canonicalId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            return pluginLikedSet.contains(canonicalId);
        }
    }

    public void toggleLikeMedia(String canonicalId) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (canonicalId == null || canonicalId.isEmpty()) return;
            final MediaId id;
            try {
                id = MediaId.parse(canonicalId).requireKind(dev.t1m3.qplayer.media.MediaKind.SONG);
            } catch (IllegalArgumentException error) {
                return;
            }
            if (!pluginHasCapability(id.provider(), ProviderCapability.LIKE)) {
                showToast(I18n.tr("toast.play.missingPlugin"));
                return;
            }
            if (!providerLoggedIn(id.provider())) {
                showToast(I18n.tr("toast.signInRequired"));
                return;
            }
            final boolean target = !pluginLikedSet.contains(id.toString());
            applyPluginLiked(id, target);
            pluginProviders
                    .setLiked(id, target)
                    .whenComplete(
                            (success, error) ->
                                    post(
                                            () -> {
                                                if (error == null && Boolean.TRUE.equals(success)) {
                                                    applyPluginLiked(id, target);
                                                    loadMyPlaylists();
                                                } else {
                                                    applyPluginLiked(id, !target);
                                                    showToast(
                                                            I18n.tr(
                                                                    target
                                                                            ? "toast.like.failed"
                                                                            : "toast.unlike.failed"));
                                                }
                                            }));
        }
    }

    private static List<String> splitIds(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String id = line.trim();
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    private void mutateMediaPlaylist(
            String playlistMediaId, String songMediaId, boolean add, boolean refreshDetail) {
        final MediaId playlistId;
        final MediaId songId;
        try {
            playlistId =
                    MediaId.parse(playlistMediaId)
                            .requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST);
            songId = MediaId.parse(songMediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.SONG);
        } catch (IllegalArgumentException error) {
            showToast(I18n.tr("toast.id.media"));
            return;
        }
        if (!canMutateMediaPlaylist(playlistId.toString())
                || !playlistId.provider().equals(songId.provider())) return;
        pluginProviders
                .mutatePlaylist(
                        playlistId, add ? "add" : "remove", Collections.singletonList(songId), null)
                .whenComplete(
                        (success, error) ->
                                post(
                                        () -> {
                                            if (error == null && Boolean.TRUE.equals(success)) {
                                                showToast(
                                                        I18n.tr(
                                                                add
                                                                        ? "toast.playlist.songAdded"
                                                                        : "toast.playlist.songRemoved"));
                                                if (refreshDetail
                                                        && playlistId
                                                                .toString()
                                                                .equals(
                                                                        openSourcePlaylistId
                                                                                .peek())) {
                                                    dropOpenSourcePlaylistTrack(songId.toString());
                                                }
                                            } else
                                                showToast(
                                                        I18n.tr(
                                                                add
                                                                        ? "toast.playlist.addFailedReason"
                                                                        : "toast.playlist.removeFailedReason",
                                                                safeMessage(error)));
                                        }));
    }

    /**
     * Take one song out of the open source playlist's rows after the provider
     * confirmed the removal.
     *
     * <p>This used to re-open the playlist, which re-fetched every page of it from
     * the network — several seconds and a scroll back to the top to delete one row,
     * and on a thousand-track playlist a visible freeze. The provider already told
     * us the removal succeeded, so the local rows are authoritative enough; the next
     * open (or the refresh button) reconciles anything else that changed server-side.
     */
    private void dropOpenSourcePlaylistTrack(String songMediaId) {
        dropOpenSourcePlaylistTracks(Collections.singleton(songMediaId));
    }

    private void dropOpenSourcePlaylistTracks(Set<String> songMediaIds) {
        if (songMediaIds == null || songMediaIds.isEmpty()) return;
        List<Song> current = sourcePlaylistTracks.peek();
        if (current == null || current.isEmpty()) return;
        List<Song> remaining = new ArrayList<>(current.size());
        for (Song song : current) {
            if (!songMediaIds.contains(song.id)) remaining.add(song);
        }
        if (remaining.size() == current.size()) return;
        sourcePlaylistTracks.set(Collections.unmodifiableList(remaining));
        final String playlistId = openSourcePlaylistId.peek();
        final Set<String> gone = new HashSet<>(songMediaIds);
        worker.submit(() -> {
            Playlist cached = mediaPlaylistCacheIndex.get(playlistId);
            if (cached == null) return;
            if (!cached.songs.removeIf(song -> gone.contains(song.id))) return;
            cached.trackCount = cached.songs.size();
            mediaPlaylistCacheIndex.upsert(cached);
            mediaPlaylistCacheIndex.save();
        });
    }

    /**
     * Remove a track from the currently open playlist (the "从此歌单移除" menu entry only appears there).
     * Reads the open id internally so QML needn't round-trip a 64-bit playlist id back through a
     * numeric property. Drops the row locally rather than re-fetching, for the reason in {@link
     * #dropOpenSourcePlaylistTrack}.
     */
    public void removeFromCurrentPlaylist(long songId) {
        removeMediaFromCurrentPlaylist(neteaseMediaId(songId));
    }

    /**
     * Move a row in the open source playlist while a drag is being applied. Local only: {@link
     * #commitSourcePlaylistOrder} is what reaches the provider, so the whole gesture costs one
     * network call rather than one per row crossed.
     */
    public void moveSourcePlaylistTrack(int from, int to) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (!Boolean.TRUE.equals(sourcePlaylistReorderable.peek())) return;
            List<Song> current = sourcePlaylistTracks.peek();
            if (current == null) return;
            if (from < 0 || from >= current.size() || to < 0 || to >= current.size()) return;
            if (from == to) return;
            List<Song> reordered = new ArrayList<>(current);
            reordered.add(to, reordered.remove(from));
            sourcePlaylistTracks.set(Collections.unmodifiableList(reordered));
        }
    }

    /** True while a reorder is on its way to the provider. */
    private boolean sourceOrderSaving;
    /** Set when a drag lands while the previous one is still in flight, so the
     *  final arrangement is sent rather than an intermediate one. */
    private boolean sourceOrderPending;

    /** Send the open source playlist's current arrangement to its provider. */
    public void commitSourcePlaylistOrder() {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (!Boolean.TRUE.equals(sourcePlaylistReorderable.peek())) return;
            if (sourceOrderSaving) {
                sourceOrderPending = true;
                return;
            }
            final String playlistMediaId = openSourcePlaylistId.peek();
            if (playlistMediaId == null || playlistMediaId.isEmpty()) return;
            List<Song> rows = sourcePlaylistTracks.peek();
            if (rows == null || rows.isEmpty()) return;
            final MediaId playlistId;
            final List<MediaId> ordered = new ArrayList<>(rows.size());
            try {
                playlistId =
                        MediaId.parse(playlistMediaId)
                                .requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST);
                for (Song song : rows) {
                    ordered.add(
                            MediaId.parse(song.id)
                                    .requireKind(dev.t1m3.qplayer.media.MediaKind.SONG));
                }
            } catch (IllegalArgumentException error) {
                showToast(I18n.tr("toast.id.media"));
                return;
            }
            if (!providerLoggedIn(playlistId.provider())
                    || !pluginHasCapability(
                            playlistId.provider(), ProviderCapability.PLAYLIST_REORDER)) return;
            sourceOrderSaving = true;
            pluginProviders
                    .reorderPlaylist(playlistId, ordered)
                    .whenComplete(
                            (success, error) ->
                                    post(
                                            () -> {
                                                sourceOrderSaving = false;
                                                boolean stillOpen =
                                                        playlistId
                                                                .toString()
                                                                .equals(
                                                                        openSourcePlaylistId
                                                                                .peek());
                                                if (error == null && Boolean.TRUE.equals(success)) {
                                                    if (sourceOrderPending && stillOpen) {
                                                        sourceOrderPending = false;
                                                        commitSourcePlaylistOrder();
                                                    } else {
                                                        sourceOrderPending = false;
                                                    }
                                                    return;
                                                }
                                                sourceOrderPending = false;
                                                Logger.warn(
                                                        "playlist {} reorder failed: {}",
                                                        playlistId,
                                                        safeMessage(error));
                                                showToast(
                                                        I18n.tr(
                                                                "toast.playlist.orderFailedReason",
                                                                safeMessage(error)));
                                                // The rows on screen no longer match the server.
                                                // Re-read rather
                                                // than leave the user looking at an order that did
                                                // not save.
                                                if (stillOpen)
                                                    loadMediaPlaylist(playlistId.toString(), true);
                                            }));
        }
    }

    /** Recently played (netease listen history). */
    public void loadRecent() {
        synchronized (QmlRuntimeLock.MONITOR) {
            PluginManifest provider = primaryProviderWith(ProviderCapability.RECENT);
            if (provider != null) {
                pluginProviders
                        .recent(provider.id, 100)
                        .whenComplete(
                                (songs, error) ->
                                        post(
                                                () -> {
                                                    if (!provider.id.equals(
                                                            pluginRegistry.primaryProvider()))
                                                        return;
                                                    if (error != null) {
                                                        Logger.warn(
                                                                "plugin {} recent failed: {}",
                                                                provider.id,
                                                                safeMessage(error));
                                                        return;
                                                    }
                                                    sourceRecentSongs.set(songs);
                                                }));
                return;
            }

            recentSongs.set(Collections.<NeteaseSong>emptyList());
            return;
        }
    }

    /** Load the liked set of every signed-in source, not just the primary one: the
     *  heart on a QQ song has to reflect QQ's own favourites even while NetEase is
     *  the primary source. */
    private void refreshPluginLiked() {
        List<PluginManifest> providers = new ArrayList<>();
        for (PluginManifest manifest : pluginManager.enabledProviders()) {
            if (manifest.capabilitySet().contains(ProviderCapability.LIKE)) providers.add(manifest);
        }
        final long generation = pluginLikedGeneration.incrementAndGet();
        final List<String> order = new ArrayList<>();
        for (PluginManifest manifest : providers) order.add(manifest.id);
        post(() -> {
            pluginLikedBySource.keySet().retainAll(order);
            publishPluginLiked();
        });
        for (PluginManifest manifest : providers) {
            final String providerId = manifest.id;
            pluginProviders.likedSongs(providerId).whenComplete((ids, error) -> post(() -> {
                if (generation != pluginLikedGeneration.get()) return;
                if (error != null) {
                    Logger.warn("plugin {} liked list failed: {}", providerId, safeMessage(error));
                    pluginLikedBySource.remove(providerId);
                } else {
                    pluginLikedBySource.put(providerId, new java.util.LinkedHashSet<>(ids));
                }
                publishPluginLiked();
            }));
        }
    }

    private void publishPluginLiked() {
        pluginLikedSet.clear();
        for (Set<String> slice : pluginLikedBySource.values()) pluginLikedSet.addAll(slice);
        likedCount.set(pluginLikedSet.size());
        likedRevision.set(likedRevision.peek() + 1);
        Track current = currentTrack();
        currentLiked.set(current != null && pluginLikedSet.contains(current.canonicalId()));
    }

    /** Apply a like/unlike to both the union set and the per-source slice, then
     *  bump {@link #likedRevision} so QML hearts rebind. The slice write is what
     *  keeps a just-toggled id alive across a later {@link #publishPluginLiked}. */
    private void applyPluginLiked(MediaId id, boolean liked) {
        String key = id.toString();
        if (liked) {
            pluginLikedBySource.computeIfAbsent(id.provider(), p -> new java.util.LinkedHashSet<>()).add(key);
            pluginLikedSet.add(key);
        } else {
            Set<String> slice = pluginLikedBySource.get(id.provider());
            if (slice != null) slice.remove(key);
            pluginLikedSet.remove(key);
        }
        likedCount.set(pluginLikedSet.size());
        likedRevision.set(likedRevision.peek() + 1);
        Track current = currentTrack();
        if (current != null && key.equals(current.canonicalId())) currentLiked.set(liked);
    }

    /** Like / unlike the current netease track. */
    public void toggleLike() {
        synchronized (QmlRuntimeLock.MONITOR) {
            Track track = playbackSession.current();
            if (track != null) toggleLikeMedia(track.canonicalId());
        }
    }

    /** Current state-owned track. Hosts needing multiple fields use {@link #playbackSnapshot()}. */
    public Track currentTrack() {
        synchronized (QmlRuntimeLock.MONITOR) {
            return playbackSession.current();
        }
    }

    public PlaybackSnapshot playbackSnapshot() {
        synchronized (QmlRuntimeLock.MONITOR) {
            Track track = playbackSession.current();
            PlaybackSnapshot.TrackMetadata metadata = null;
            if (track != null) {
                byte[] data = track.coverBytes;
                if (data != snapshotArtworkSource) {
                    snapshotArtworkSource = data;
                    snapshotArtwork = data != null ? new PlaybackSnapshot.Artwork(data) : null;
                }
                metadata =
                        new PlaybackSnapshot.TrackMetadata(
                                track.canonicalId(),
                                orEmpty(track.title),
                                orEmpty(track.artist),
                                orEmpty(track.album),
                                orEmpty(track.filePath),
                                orEmpty(track.coverUrl),
                                orEmpty(coverPath.peek()),
                                track.durationMs,
                                snapshotArtwork);
            }
            if (track == null) {
                snapshotArtworkSource = null;
                snapshotArtwork = null;
            }
            return new PlaybackSnapshot(
                    metadata,
                    playbackSession.playing,
                    mediaSessionPosition(),
                    duration(),
                    playbackRevision.get(),
                    seekRevision.get());
        }
    }

    public boolean seekIfCurrent(String expectedCanonicalId, long ms) {
        synchronized (QmlRuntimeLock.MONITOR) {
            Track track = playbackSession.current();
            if (track == null || !Objects.equals(track.canonicalId(), expectedCanonicalId))
                return false;
            mediaSeek(ms);
            return true;
        }
    }

    /**
     * Best local artwork path for the current track, including a downloaded network cover in
     * DiskCache. Safe while the render pump is suspended.
     */
    public String currentCoverPath() {
        synchronized (QmlRuntimeLock.MONITOR) {
            return orEmpty(coverPath.peek());
        }
    }

    /**
     * Intended play state for the media session — true from play/resume until pause, unaffected by
     * the backend's brief async-prepare gap.
     */
    public boolean isPlaying() {
        synchronized (QmlRuntimeLock.MONITOR) {
            return playbackSession.playing;
        }
    }

    /**
     * Pause playback immediately — intended for MediaSession callbacks that may arrive on a binder
     * thread on some OEM ROMs (MIUI/HyperOS, HarmonyOS) where main-thread message delivery is
     * throttled in the background.
     */
    public void mediaPause() {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (!playbackSession.playing) return;
            playbackSession.playing = false;
            playing.set(false);
            // playingIntent flips immediately (MediaSession state must be immediate by
            // contract); the actual backend.pause() rides the same fade-out toggle()
            // uses, deferred until silence, so lock-screen/notification/dynamic-island
            // pause ramps down instead of cutting audio hard.
            if (fadeEnabled && (playbackStarted || backend.isPlaying())) {
                startFadeOut(
                        FADE_OUT_MS,
                        () -> {
                            if (!playbackSession.playing) backend.pause();
                        });
            } else {
                cancelFadeAtGain(1f);
                backend.pause();
            }
            notifyPlayback();
        }
    }

    /**
     * Restart the decoder if we still intend to play but the backend went silent (OEM screen-off
     * stall, a focus loss that never sent GAIN). Safe no-op when the user actually paused.
     */
    public void ensurePlaying() {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (!playbackSession.playing || playbackSession.resolving) return;
            if (!backend.isPlaying()) backend.resume();
        }
    }

    /**
     * Resume playback immediately — counterpart to {@link #mediaPause()} for MediaSession callbacks
     * that may run off the main thread.
     */
    public void mediaResume() {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (playbackSession.playing) return;
            playbackSession.playing = true;
            if (playbackSession.resolving) {
                playbackSession.playing = true;
                playing.set(true);
                notifyPlayback();
                return;
            }
            if (needsReplay && playbackSession.index >= 0) {
                needsReplay = false;
                onMain(() -> playAt(playbackSession.index));
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
            playbackSession.playing = true;
            playing.set(true);
            notifyPlayback();
        }
    }

    /** Current source duration in ms (0 if unknown). */
    public long duration() {
        synchronized (QmlRuntimeLock.MONITOR) {
            Track track = playbackSession.current();
            long actual = playbackStarted ? backend.duration() : 0L;
            return actual > 0L ? actual : track != null ? Math.max(0L, track.durationMs) : 0L;
        }
    }

    /** Message for a toast/dialog. Async failures arrive wrapped in a
     *  CompletionException whose own message is "<cause class>: <cause message>",
     *  which put a Java class name in front of every plugin error the user saw. */
    private static String safeMessage(Throwable error) {
        Throwable root = error;
        while ((root instanceof java.util.concurrent.CompletionException
                || root instanceof java.util.concurrent.ExecutionException)
                && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root == null ? null : root.getMessage();
        return message == null || message.trim().isEmpty() ? I18n.tr("error.unknown") : message;
    }

    // --- Login (fully async: qrLoginKey/qrLoginCheck are blocking HTTP, must
    //     never run on the render thread the QML handlers call from) ----------

    private volatile String pendingUnikey;
    private volatile String pendingPluginLoginProvider = "";
    private volatile String pendingPluginLoginChallenge = "";
    private volatile String pluginQrMethodId = "";
    private volatile String pluginWebMethodId = "";
    private volatile String pluginAppMethodId = "";
    private volatile String pluginCredentialMethodId = "";
    private volatile boolean pendingLoginIsApp;
    private volatile LoginMethod activeWebLoginMethod;
    // QR/app login is polled from QML, but jumping to Douyin/QQ pauses the GL
    // thread and those timers freeze — the confirm happens while we are in the
    // background, so the host has to keep polling itself.
    private final Object loginPollLock = new Object();
    private ScheduledFuture<?> loginPollTask;
    private final AtomicBoolean loginPollInFlight = new AtomicBoolean(false);
    /** QR module matrix (true=dark) as nested Lists so QML can index [y][x]. */
    public final Property<List<List<Boolean>>> qrImage =
            new Property<>(Collections.<List<Boolean>>emptyList());
    /** Absolute path to a QR image a plugin rendered itself (see
     *  LoginChallenge#qrImageBase64) — shown as-is instead of {@link #qrImage}
     *  when non-empty. Some providers' QR encodes a server token that only
     *  exists inside the image the provider's own endpoint returns; there is no
     *  text for the host to re-encode. qml4j's Image has no data: URI support
     *  (confirmed against its ImageLoader — only http(s) and local paths), so
     *  the bytes are written to the cache dir and referenced by path, the same
     *  way a downloaded cover art file is. A fresh, distinctly-named file per
     *  challenge is required too: qml4j only redecodes an Image when its source
     *  STRING changes, so overwriting one fixed filename would leave a refreshed
     *  QR showing the previous (now stale, or already-expired) picture. */
    public final Property<String> qrImagePath = new Property<>("");
    private volatile java.nio.file.Path pendingQrImageFile;
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
        final WebLoginLauncher launcher;
        final LoginMethod method;
        final String providerName;
        synchronized (QmlRuntimeLock.MONITOR) {
            launcher = webLoginLauncher;
            if (launcher == null) {
                webLoginError.set(I18n.tr("login.error.noWebView"));
                return;
            }
            if (Boolean.TRUE.equals(webLoginBusy.peek())) return;
            method = activeWebLoginMethod;
            if (pendingPluginLoginProvider.isEmpty() || method == null) {
                webLoginBusy.set(false);
                webLoginError.set(I18n.tr("login.error.noPlugin"));
                return;
            }
            providerName = loginProviderName.peek();
            webLoginError.set("");
            webLoginBusy.set(true);
        }
        onHost(
                () -> {
                    try {
                        launcher.launch(
                                method.webUrl,
                                method.cookieUrl,
                                method.credentialCookieName,
                                providerName);
                    } catch (RuntimeException error) {
                        Logger.warn("web login launcher failed: {}", safeMessage(error));
                        onMain(
                                () -> {
                                    webLoginBusy.set(false);
                                    webLoginError.set(I18n.tr("login.error.openFailed"));
                                });
                    }
                });
    }

    /** Called by a shell after reading the official WebView cookie store. */
    public void completeWebLogin(String cookieHeader) {
        synchronized (QmlRuntimeLock.MONITOR) {
            importLoginCookie(cookieHeader, pluginWebMethodId);
        }
    }

    /** Paste-login fallback invoked directly by QML. */
    public void submitCookieLogin(String cookieHeader) {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (Boolean.TRUE.equals(webLoginBusy.peek())) return;
            webLoginBusy.set(true);
            webLoginError.set("");
            importLoginCookie(cookieHeader, pluginCredentialMethodId);
        }
    }

    /** Shell callback when its browser window is closed before obtaining MUSIC_U. */
    public void cancelWebLogin() {
        synchronized (QmlRuntimeLock.MONITOR) {
            stopLoginPoll();
            pendingPluginLoginChallenge = "";
            pendingUnikey = null;
            post(() -> webLoginBusy.set(false));
        }
    }

    /** Shell callback for browser creation/native-engine failures. */
    public void failWebLogin(String message) {
        synchronized (QmlRuntimeLock.MONITOR) {
            final String safe =
                    message == null || message.trim().isEmpty()
                            ? I18n.tr("login.error.openSourceFailed")
                            : message.trim();
            post(
                    () -> {
                        webLoginBusy.set(false);
                        webLoginError.set(safe);
                    });
        }
    }

    public void clearWebLoginError() {
        synchronized (QmlRuntimeLock.MONITOR) {
            webLoginError.set("");
        }
    }

    private void importLoginCookie(String cookieHeader, String pluginMethodId) {
        final String candidate = cookieHeader == null ? "" : cookieHeader;
        if (!pendingPluginLoginProvider.isEmpty()
                && pluginMethodId != null
                && !pluginMethodId.isEmpty()) {
            pluginAccounts
                    .submit(pendingPluginLoginProvider, pluginMethodId, candidate)
                    .whenComplete(
                            (challenge, error) ->
                                    post(
                                            () -> {
                                                if (error != null) {
                                                    webLoginBusy.set(false);
                                                    webLoginError.set(safeMessage(error));
                                                    return;
                                                }
                                                applyPluginLoginChallenge(challenge, true);
                                            }));
            return;
        }

        post(
                () -> {
                    webLoginBusy.set(false);
                    webLoginError.set(I18n.tr("login.error.noLoginPlugin"));
                });
        return;
    }

    /** Mint a login key + matrix off-thread; publishes to {@link #qrImage}/{@link #qrStatus}. */
    public void startQrLogin() {
        synchronized (QmlRuntimeLock.MONITOR) {
            pendingLoginIsApp = false;
            post(
                    () -> {
                        qrStatus.set(0);
                        qrImagePath.set("");
                        publishAppLink("", loginAppButtonLabel.peek());
                    });
            if (!pendingPluginLoginProvider.isEmpty() && !pluginQrMethodId.isEmpty()) {
                final String provider = pendingPluginLoginProvider;
                pluginAccounts
                        .begin(provider, pluginQrMethodId)
                        .whenComplete(
                                (challenge, error) ->
                                        post(
                                                () -> {
                                                    if (!provider.equals(pendingPluginLoginProvider)
                                                            || pendingLoginIsApp) return;
                                                    if (error != null) {
                                                        Logger.warn(
                                                                "plugin {} QR login start failed:"
                                                                        + " {}",
                                                                provider,
                                                                safeMessage(error));
                                                        qrStatus.set(800);
                                                        webLoginError.set(safeMessage(error));
                                                        return;
                                                    }
                                                    pendingPluginLoginChallenge = challenge.id;
                                                    applyPluginLoginChallenge(challenge, false);
                                                    startLoginPoll();
                                                }));
                return;
            }

            post(
                    () -> {
                        qrStatus.set(800);
                        webLoginError.set(I18n.tr("login.error.noLoginPlugin"));
                    });
            return;
        }
    }

    /** Begin an {@code app} login challenge. The OS jump happens on {@link #openLoginApp()}. */
    public void startAppLogin() {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (pluginAppMethodId.isEmpty() || pendingPluginLoginProvider.isEmpty()) {
                webLoginError.set(I18n.tr("login.error.noApp"));
                return;
            }
            pendingLoginIsApp = true;
            post(
                    () -> {
                        qrStatus.set(0);
                        publishAppLink("", loginAppButtonLabel.peek());
                    });
            final String provider = pendingPluginLoginProvider;
            final String methodId = pluginAppMethodId;
            pluginAccounts
                    .begin(provider, methodId)
                    .whenComplete(
                            (challenge, error) ->
                                    post(
                                            () -> {
                                                if (!provider.equals(pendingPluginLoginProvider)
                                                        || !pendingLoginIsApp) return;
                                                if (error != null) {
                                                    Logger.warn(
                                                            "plugin {} app login start failed: {}",
                                                            provider,
                                                            safeMessage(error));
                                                    qrStatus.set(800);
                                                    webLoginError.set(safeMessage(error));
                                                    return;
                                                }
                                                pendingPluginLoginChallenge = challenge.id;
                                                applyPluginLoginChallenge(challenge, false);
                                                if (loginAppUrl.peek().isEmpty()) {
                                                    qrStatus.set(800);
                                                    webLoginError.set(I18n.tr("login.error.noApp"));
                                                } else {
                                                    startLoginPoll();
                                                }
                                            }));
        }
    }

    /** Open the current challenge's deep link in the matching official app. */
    public void openLoginApp() {
        synchronized (QmlRuntimeLock.MONITOR) {
            final String url = loginAppUrl.peek();
            if (url == null || url.isEmpty()) {
                webLoginError.set(I18n.tr("login.error.noApp"));
                return;
            }
            AppLinkLauncher launcher = appLinkLauncher;
            if (launcher == null) {
                webLoginError.set(I18n.tr("login.error.appMissing"));
                return;
            }
            webLoginError.set("");
            onHost(
                    () -> {
                        if (!launcher.open(url))
                            onMain(() -> webLoginError.set(I18n.tr("login.error.appMissing")));
                    });
        }
    }

    private void publishAppLink(String url, String label) {
        loginAppUrl.set(url == null ? "" : url);
        if (label != null && !label.isEmpty()) loginAppButtonLabel.set(label);
        loginAppOpenAvailable.set(appLinkLauncher != null && !loginAppUrl.peek().isEmpty());
    }

    /** Poll the scan status off-thread; updates {@link #qrStatus}. */
    public void pollQrLogin() {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (!loginPollInFlight.compareAndSet(false, true)) return;
            if (!pendingPluginLoginProvider.isEmpty() && !pendingPluginLoginChallenge.isEmpty()) {
                final String provider = pendingPluginLoginProvider;
                final String challenge = pendingPluginLoginChallenge;
                pluginAccounts
                        .poll(provider, challenge)
                        .whenComplete(
                                (result, error) -> {
                                    try {
                                        post(
                                                () -> {
                                                    if (!provider.equals(pendingPluginLoginProvider)
                                                            || !challenge.equals(
                                                                    pendingPluginLoginChallenge))
                                                        return;
                                                    if (error == null)
                                                        applyPluginLoginChallenge(result, false);
                                                });
                                    } finally {
                                        loginPollInFlight.set(false);
                                    }
                                });
                return;
            }

            loginPollInFlight.set(false);
            return;
        }
    }

    private void startLoginPoll() {
        synchronized (loginPollLock) {
            if (loginPollTask != null && !loginPollTask.isCancelled()) return;
            try {
                loginPollTask = fadeWorker.scheduleAtFixedRate(() -> {
                    try { pollQrLogin(); } catch (Throwable ignored) {}
                }, 800, 800, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
            }
        }
    }

    private void stopLoginPoll() {
        synchronized (loginPollLock) {
            if (loginPollTask != null) {
                loginPollTask.cancel(false);
                loginPollTask = null;
            }
        }
        loginPollInFlight.set(false);
    }

    private void applyPluginLoginChallenge(LoginChallenge challenge, boolean credentialFlow) {
        if (challenge == null) return;
        if (challenge.id != null && !challenge.id.isEmpty()) {
            pendingPluginLoginChallenge = challenge.id;
        }
        if (challenge.qrImageBase64 != null && !challenge.qrImageBase64.isEmpty()) {
            writeQrImageFile(challenge.qrImageBase64);
        } else if (challenge.qrContent != null && !challenge.qrContent.isEmpty()) {
            qrImagePath.set("");
            qrImage.set(QrMatrix.encode(challenge.qrContent));
        }
        if (challenge.appUrl != null && !challenge.appUrl.isEmpty()) {
            String label = challenge.appLabel != null && !challenge.appLabel.isEmpty()
                    ? challenge.appLabel : loginAppButtonLabel.peek();
            publishAppLink(challenge.appUrl, label);
        }
        switch (challenge.status) {
            case "scanned": qrStatus.set(802); break;
            case "success":
                stopLoginPoll();
                qrStatus.set(803);
                webLoginBusy.set(false);
                webLoginError.set("");
                webLoginSuccessRevision.set(webLoginSuccessRevision.peek() + 1L);
                refreshPluginAccount(pendingPluginLoginProvider, challenge.account);
                // The signed-in source may not be the primary one, and only this
                // list can show it.
                refreshSourceAccounts();
                showToast(I18n.tr("toast.login.success"));
                break;
            case "expired":
                qrStatus.set(800);
                if (credentialFlow) break;
                if (pendingLoginIsApp) startAppLogin();
                else startQrLogin();
                break;
            case "failed":
                stopLoginPoll();
                pendingCredentialEncryptedNotice = false;
                qrStatus.set(800);
                webLoginBusy.set(false);
                webLoginError.set(challenge.message == null || challenge.message.isEmpty()
                        ? I18n.tr("login.error.failed") : challenge.message);
                break;
            default: qrStatus.set(801); break;
        }
    }

    /** Writes a plugin-supplied QR PNG to the cache dir under a fresh name (see
     *  {@link #qrImagePath}) and deletes whichever one preceded it. */
    private void writeQrImageFile(String base64Png) {
        java.nio.file.Path previous = pendingQrImageFile;
        try {
            java.nio.file.Path dir = AppDirs.cacheDir();
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path file = dir.resolve("login-qr-" + System.nanoTime() + ".png");
            java.nio.file.Files.write(file, java.util.Base64.getDecoder().decode(base64Png));
            pendingQrImageFile = file;
            qrImagePath.set(file.toAbsolutePath().toString());
        } catch (Throwable e) {
            Logger.warn("failed to write login QR image: {}", safeMessage(e));
            return;
        }
        if (previous != null) {
            try { java.nio.file.Files.deleteIfExists(previous); } catch (Throwable ignored) { }
        }
    }

    private void refreshPluginAccount(String provider, AccountProfile supplied) {
        if (provider == null || provider.isEmpty()) return;
        if (supplied != null) {
            publishPluginAccount(provider, supplied);
            return;
        }
        if (!pluginHasCapability(provider, ProviderCapability.ACCOUNT)) return;
        final long generation = accountGeneration.incrementAndGet();
        pluginAccounts.account(provider).whenComplete((account, error) -> post(() -> {
            // A switch away (or a logout) that happened while this was in flight
            // already bumped the generation: publishing now would restore the
            // account header of a source the user has left.
            if (generation != accountGeneration.get()) return;
            if (error == null && provider.equals(pluginRegistry.primaryProvider())) {
                publishPluginAccount(provider, account);
            }
        }));
    }

    private void publishPluginAccount(String provider, AccountProfile account) {
        if (account == null) return;
        SourceAccountRow row = new SourceAccountRow();
        row.providerId = provider;
        row.sourceName = providerDisplayName(provider);
        row.primary = provider.equals(pluginRegistry.primaryProvider());
        row.loggedIn = account.loggedIn;
        row.canCreatePlaylist = pluginHasCapability(provider, ProviderCapability.PLAYLIST_MUTATION);
        row.displayName = orEmpty(account.displayName);
        row.avatarUrl = orEmpty(account.avatarUrl);
        row.membershipTier = account.membershipTier;
        row.level = account.level;
        row.signature = orEmpty(account.signature);
        sourceAccountsBySource.put(provider, row);
        publishSourceAccounts();
        if (!row.primary) {
            refreshPluginLiked();
            loadMyPlaylists();
            return;
        }
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
                showToast(I18n.tr("toast.keystore.restored"));
            }
            refreshPluginLiked();
            loadMyPlaylists();
            loadRecent();
        } else {
            pendingCredentialEncryptedNotice = false;
            pluginLikedBySource.remove(provider);
            publishPluginLiked();
        }
    }

    public void logout() {
        synchronized (QmlRuntimeLock.MONITOR) {
            pendingCredentialEncryptedNotice = false;
            String provider = pendingPluginLoginProvider;
            if (!provider.isEmpty()) {
                legacyCredentialMigrationGeneration.incrementAndGet();
                pluginAccounts
                        .logout(provider)
                        .whenComplete(
                                (ignored, error) ->
                                        post(
                                                () -> {
                                                    if (error != null) {
                                                        showToast(
                                                                I18n.tr(
                                                                        "toast.logout.failed",
                                                                        safeMessage(error)));
                                                        return;
                                                    }
                                                    legacyCredentialMigrationAttempted = true;
                                                    netease.logout();
                                                    clearPublishedAccount();
                                                    dropSourceUserData(provider);
                                                    showToast(I18n.tr("toast.logout.done"));
                                                }));
                return;
            }

            legacyCredentialMigrationGeneration.incrementAndGet();
            legacyCredentialMigrationAttempted = true;
            netease.logout();
            clearPublishedAccount();
            return;
        }
    }

    private void clearPublishedAccount() {

        myPlaylists.set(Collections.<NeteasePlaylist>emptyList());
        recommendations.set(Collections.<NeteaseSong>emptyList());
        recentSongs.set(Collections.<NeteaseSong>emptyList());
        sourceRecentSongs.set(Collections.<Song>emptyList());
        clearPublishedPluginAccount();
        publishMyPlaylists();
        publishPluginLiked();
    }

    /** 我的 and the liked hearts keep the other sources' data after one source
     *  signs out; only the leaving source's own slice goes away. */
    private void dropSourceUserData(String providerId) {
        myPlaylistsBySource.remove(providerId);
        publishMyPlaylists();
        pluginLikedBySource.remove(providerId);
        publishPluginLiked();
    }

    /** Capture a coherent in-memory session now; its IO owner commits it asynchronously. */
    public CompletableFuture<Void> saveSessionStateAsync() {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (appShuttingDown) return sessionSave;
            long position = playbackSession.index >= 0 ? mediaSessionPosition() : 0L;
            sessionSave =
                    queuePersistence.save(
                            new QueuePersistence.Snapshot(
                                    playbackSession.queue,
                                    playbackSession.index,
                                    position,
                                    playMode.peek()));
            sessionSave.whenComplete(
                    (ignored, error) -> {
                        if (error != null) Logger.warn("saveQueue failed: {}", safeMessage(error));
                    });
            return sessionSave;
        }
    }

    public CompletableFuture<Void> shutdownAsync() {
        synchronized (QmlRuntimeLock.MONITOR) {
            if (shutdownFuture != null) return shutdownFuture;
            CompletableFuture<Void> saved = saveSessionStateAsync();
            appShuttingDown = true;
            completeDownloadChoice(CompletableFuture.completedFuture(Boolean.FALSE));
            playbackSession.invalidate();
            playbackSession.playing = false;
            synchronized (fadeLock) {
                fadeGeneration++;
                fadeRunning = false;
                fadeCompleteAction = null;
            }
            stopLoginPoll();
            worker.shutdownNow();
            fadeWorker.shutdownNow();
            monetFetchWorker.shutdownNow();
            monetWorker.shutdownNow();
            backend.release();
            CompletableFuture<Void> admissionStopped = audioCacheCoordinator.stopAdmission();
            shutdownFuture =
                    CompletableFuture.runAsync(() -> retireResources(saved, admissionStopped));
            return shutdownFuture;
        }
    }

    private void retireResources(
            CompletableFuture<Void> saved, CompletableFuture<Void> admissionStopped) {
        Throwable failure = null;
        // Start real network cancellation before waiting for IO/worker barriers.
        // A future cancelled in isolation is not proof that its writer has stopped.
        releaseUpdateChecker.cancelPendingRequests();
        CompletableFuture<Void> catalogStopped = pluginCatalog.cancelPendingOperations();
        CompletableFuture<Void> hostStopped = pluginHostApi.cancelPendingOperations();
        try {
            admissionStopped.join();
        } catch (Throwable error) {
            failure = collectRetirementFailure(failure, error);
        }
        awaitStopped(worker);
        awaitStopped(fadeWorker);
        awaitStopped(monetFetchWorker);
        awaitStopped(monetWorker);
        // No worker can re-enable a runtime after this point. Closing actors also
        // stops source resolutions whose derived CompletableFuture never settled.
        try {
            pluginManager.close();
        } catch (Throwable error) {
            failure = collectRetirementFailure(failure, error);
        }
        try {
            audioCacheCoordinator
                    .finishCancellation(CompletableFuture.allOf(catalogStopped, hostStopped))
                    .join();
        } catch (Throwable error) {
            failure = collectRetirementFailure(failure, error);
        }
        try {
            saved.join();
        } catch (Throwable error) {
            failure = collectRetirementFailure(failure, error);
        }
        try {
            CompletableFuture.runAsync(
                            () -> {
                                saveLyricOffsets();
                                boolean playlistsSaved = localPlaylistStore.save();
                                boolean orderSaved = myPlaylistOrderStore.save();
                                if (!playlistsSaved || !orderSaved)
                                    throw new IllegalStateException("playlist state save failed");
                            },
                            ioWorker)
                    .join();
        } catch (Throwable error) {
            failure = collectRetirementFailure(failure, error);
        }
        try {
            pluginCatalog.close();
        } catch (Throwable error) {
            failure = collectRetirementFailure(failure, error);
        }
        try {
            pluginHostApi.close();
        } catch (Throwable error) {
            failure = collectRetirementFailure(failure, error);
        } finally {
            ioWorker.shutdown();
            awaitStopped(ioWorker);
        }
        if (failure != null) throw new java.util.concurrent.CompletionException(failure);
    }

    private static Throwable collectRetirementFailure(Throwable primary, Throwable error) {
        Throwable cause =
                error instanceof java.util.concurrent.CompletionException
                                && error.getCause() != null
                        ? error.getCause()
                        : error;
        if (primary == null) return cause;
        if (primary != cause) primary.addSuppressed(cause);
        return primary;
    }

    private static void awaitStopped(ExecutorService executor) {
        boolean interrupted = false;
        for (; ; ) {
            try {
                if (executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) break;
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    // --- Disk cache management (called from QML settings page) -------------

    /** Update the {@link #cacheSizeMB} property from the actual disk usage. */
    public void refreshCacheSize() {
        ioWorker.submit(
                () -> {
                    long bytes = diskCache.totalSize();
                    post(() -> cacheSizeMB.set(bytes / (1024 * 1024)));
                });
    }

    /** Coalesce slider changes; directory eviction never runs on a control thread. */
    public void setCacheMaxSizeMB(long mb) {
        synchronized (QmlRuntimeLock.MONITOR) {
            long revision = cacheResizeRevision.incrementAndGet();
            if (cacheResizeTask != null) cacheResizeTask.cancel(false);
            cacheResizeTask =
                    fadeWorker.schedule(
                            () ->
                                    ioWorker.submit(
                                            () -> {
                                                if (revision != cacheResizeRevision.get()) return;
                                                diskCache.setMaxSizeMB(mb);
                                                long bytes = diskCache.totalSize();
                                                post(
                                                        () -> {
                                                            if (revision
                                                                    == cacheResizeRevision.get())
                                                                cacheSizeMB.set(
                                                                        bytes / (1024 * 1024));
                                                        });
                                            }),
                            150,
                            TimeUnit.MILLISECONDS);
        }
    }

    public void clearDiskCache() {
        ioWorker.submit(
                () -> {
                    diskCache.clearAll();
                    refreshCacheSize();
                    showToast(I18n.tr("toast.cache.cleared"));
                });
    }
}
