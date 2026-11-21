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
import dev.t1m3.qplayer.audio.mix.AutoCue;
import dev.t1m3.qplayer.media.LyricsPayload;
import dev.t1m3.qplayer.media.AccountProfile;
import dev.t1m3.qplayer.media.Album;
import dev.t1m3.qplayer.media.Artist;
import dev.t1m3.qplayer.media.LoginChallenge;
import dev.t1m3.qplayer.media.LoginMethod;
import dev.t1m3.qplayer.media.MediaId;
import dev.t1m3.qplayer.media.Page;
import dev.t1m3.qplayer.media.Playlist;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.media.HomeSection;
import dev.t1m3.qplayer.media.ProviderHome;
import dev.t1m3.qplayer.media.Song;
import dev.t1m3.qplayer.media.StreamDescriptor;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.playlist.LocalPlaylist;
import dev.t1m3.qplayer.playlist.LocalPlaylistStore;
import dev.t1m3.qplayer.playlist.PlaylistSubscription;
import dev.t1m3.qplayer.playlist.PlaylistTrack;
import dev.t1m3.qplayer.playlist.PlaylistTransfer;
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
import dev.t1m3.qplayer.plugin.SourceAccountRow;
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

import java.util.ArrayDeque;
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
 * {@code next}/{@code prev} walk the queue; in shuffle, {@code prev} pops a
 * play-history stack so it returns the song that was actually playing rather
 * than another random slot. Auto-advance wires {@code backend.onComplete -> next}.
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
    private volatile WatchmanTokenLauncher watchmanTokenLauncher;

    // ── 听歌识曲（移植自她的 recognize/ 包）────────────────────────────────────
    // 与 watchman 同一个模式：桌面/安卓各自把实现塞进来，QML 只认 player 上的入口与 Property。
    private volatile RecognitionLauncher recognitionLauncher;

    /** 0 未开始 / 1 正在听 / 2 正在匹配 / 3 已找到 / 4 失败。 */
    /** 2.1.1 宿主的"选择下载音质"弹层状态（smoke 测试会读它）。 */
    public final Property<Boolean> downloadQualityOpen = new Property<>(Boolean.FALSE);
    public final Property<String> downloadQualityTitle = new Property<>("");

    public void cancelDownloadQuality() {
        downloadQualityOpen.set(Boolean.FALSE);
        downloadQualityTitle.set("");
    }

    public final Property<Integer> recognizePhase = new Property<>(Integer.valueOf(0));
    /** 状态行（听了几秒、失败原因……）。 */
    public final Property<String> recognizeStatus = new Property<>("");
    public final Property<String> recognizeTitle = new Property<>("");
    public final Property<String> recognizeArtist = new Property<>("");

    /** 宿主实现：开始/停止采集与匹配。回调**允许来自任意线程**，本类经 {@code post} 回投。 */
    public interface RecognitionLauncher {
        void start(RecognitionCallback callback);
        void cancel();
    }

    /** 识别进度回投给界面的通道。 */
    public interface RecognitionCallback {
        void onUpdate(int phase, String status, String title, String artist);
    }

    /** 宿主是否注入了识别实现。安卓有（WebView + AudioRecord）；桌面的 LWJGL 宿主没有 WebView，
     *  而指纹是 WASM，qml4j 的 Rhino 跑不了 —— 所以桌面为 false，界面据此不显示入口。 */
    public final Property<Boolean> recognizeSupported = new Property<>(Boolean.FALSE);

    public void setRecognitionLauncher(RecognitionLauncher launcher) {
        this.recognitionLauncher = launcher;
        recognizeSupported.set(Boolean.valueOf(launcher != null));
    }

    public void startRecognize() {
        RecognitionLauncher launcher = recognitionLauncher;
        if (launcher == null) {
            recognizePhase.set(Integer.valueOf(4));
            recognizeStatus.set("当前平台不支持听歌识曲");
            return;
        }
        recognizePhase.set(Integer.valueOf(1));
        recognizeStatus.set("");
        recognizeTitle.set("");
        recognizeArtist.set("");
        launcher.start((phase, status, title, artist) -> post(() -> {
            recognizePhase.set(Integer.valueOf(phase));
            recognizeStatus.set(status == null ? "" : status);
            if (title != null && !title.isEmpty()) recognizeTitle.set(title);
            if (artist != null && !artist.isEmpty()) recognizeArtist.set(artist);
        }));
    }

    public void cancelRecognize() {
        RecognitionLauncher launcher = recognitionLauncher;
        if (launcher != null) launcher.cancel();
        recognizePhase.set(Integer.valueOf(0));
        recognizeStatus.set("");
    }

    /** 宿主的系统 WebView 跑插件脚本那件事（桌面/安卓各自实现，见 {@link WebAuthScriptLauncher}）。 */
    private volatile WebAuthScriptLauncher webAuthScriptLauncher;
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

    /**
     * 自动混音总开关（{@code SettingsCatalog.AUTOMIX_ENABLED_KEY}）的影子值。
     *
     * <p>默认 <strong>false</strong>：与设置里那个键的默认值一致，也与
     * {@code AutomixSettings} 读不到设置时的取值一致。三处默认值必须一样，
     * 否则会出现「设置页说关着、引擎却在过渡」这种最难查的不一致。
     *
     * <p>它由宿主通过 {@link #setAutomixEnabled(boolean)} 从设置里推过来
     * （见 {@code SettingsCore.apply}），控制器自己不读设置——设置中枢本来
     * 就是唯一真源，这里只是一份给播放线程看的影子。
     */
    private volatile boolean automixEnabled = false;

    /**
     * **自动接法的风格白名单**（逗号分隔的风格名；空串 = 全允许 ✓）。
     * 用户 2026-10-09：「给 AI 自动模式添加个列表选择，我可以选自动模式会选哪些模式」✓。
     */
    private volatile String automixAutoStyles = "";

    /** 设置白名单（逗号分隔 ✓ 空串 = 全允许 ✓）；立刻推给引擎 ✓。 */
    public void setAutomixAutoStyles(String names) {
        automixAutoStyles = names == null ? "" : names.trim();
        dev.t1m3.qplayer.audio.mix.AiMix.setAutoAllowedStyleNames(automixAutoStyles);
    }

    /** 当前白名单（逗号分隔 ✓）。 */
    public String automixAutoStyles() {
        return automixAutoStyles;
    }

    /**
     * **自动接法可选的所有风格**（给界面列勾选列表用 ✓）：每项 `name|显示名`，多项用 `;` 分隔 ✓。
     * `name` 是写进白名单的值 ✓，`显示名` 是界面上给人看的文字 ✓。
     */
    /** 把「允许自动选中的风格」开关汇总成白名单并推给引擎（用户 2026-10-09 ✓）。 */
    public void attachAutomixStyleWhitelist(dev.t1m3.qplayer.settings.SettingsCore core) {
        if (core == null) {
            return;
        }
        for (dev.t1m3.qplayer.audio.mix.MixStyle m
                : dev.t1m3.qplayer.audio.mix.MixStyle.values()) {
            core.onChange("automix.allow." + m.name(), value -> pushAutomixWhitelist(core));
        }
        pushAutomixWhitelist(core);
    }

    private void pushAutomixWhitelist(dev.t1m3.qplayer.settings.SettingsCore core) {
        StringBuilder sb = new StringBuilder();
        for (dev.t1m3.qplayer.audio.mix.MixStyle m
                : dev.t1m3.qplayer.audio.mix.MixStyle.values()) {
            Object v = core.value("automix.allow." + m.name());
            if (v == null || Boolean.TRUE.equals(v)) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(m.name());
            }
        }
        setAutomixAutoStyles(sb.toString());
    }

    public String automixStyleCatalog() {
        StringBuilder sb = new StringBuilder();
        for (dev.t1m3.qplayer.audio.mix.MixStyle m
                : dev.t1m3.qplayer.audio.mix.MixStyle.values()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(m.name()).append('|').append(m.toString());
        }
        return sb.toString();
    }

    /**
     * 「这一次换曲真的会做过渡吗」的影子值：总开关<strong>打开</strong>且接法
     * <strong>不是</strong>「无缝拼接」（{@code SettingsCatalog.AUTOMIX_MODE_GAPLESS}）。
     *
     * <p>为什么单独要它：{@link #prefetchNextForAutomix} 会替用户去解析并下载
     * 「下一首」的音频——那是要花带宽和电的。开关关着、或者接法选的是无缝拼接时，
     * 过渡压根不会发生，那就等于替用户随机把后面的歌下载下来。所以预取只看这一个值，
     * 而预告（{@link #armNextSourceForAutomix}）不看它：预告同时承担
     * 「曲终无缝换曲」那条与过渡无关的用途。
     */
    private volatile boolean automixTransitionRequested = false;
    /**
     * 一个「稍后再做一件事」的小调度器。
     *
     * <p>它从前叫 {@code fadeWorker}——给音量淡变那条 16 ms 的时钟用的。那个设置项
     * （{@code settings.fade.*}）已经删掉了：它的用途（切歌/曲终时把音量渐变过去）
     * 现在由引擎在接缝上做（自动混音 + 无缝换曲），而它在过渡窗口里还会把
     * <strong>整条混音</strong>（含正在进场的 B）拉到 0，正是要拆掉的东西。
     * 这里只剩「延迟一小会儿再发一条 toast」这一件事，名字如实改过来。
     */
    private final ScheduledExecutorService postWorker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "qplayer-post-delay");
        t.setDaemon(true);
        return t;
    });
    // Separate from the QML Property so the audio clock can read it safely.
    private volatile float userVolume = 0.8f;
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
    /**
     * <strong>自动混音的交接正在进行</strong>：过渡开始那一刻界面已经切到进场曲了，
     * 但要等曲终对账才算交接完（见 {@link #adoptIncomingTrack} 与
     * {@link #consumeAdoptedHandover()}）。
     *
     * <p>界面拿它决定封面怎么换：自动交接时做<strong>交叉融合</strong>
     * （旧封面淡出、新封面淡入），手动换曲仍然是生硬切换——那一次是用户自己按的，
     * 没有任何需要缝起来的接缝。
     *
     * <p>为什么需要这个标志，而不是让界面「凡是换图就融合」：进场曲在过渡窗口一开始
     * 就已经在出声，那时<em>音频</em>还基本是出场曲，界面却整页切了过去——封面正是
     * 那一瞬间最扎眼的一处。不是自动交接的换图没有这个问题，一律融合反而会把
     * 列表与卡片该有的即时感弄没（所以 {@code CoverImage} 里这个开关默认是关的）。
     */
    public final Property<Boolean> automixHandover = new Property<>(Boolean.FALSE);
    /** Material You seed color ("#rrggbb") derived from the current cover, or ""
     *  when none. QML feeds it into StyleManager.seedColor when Monet is enabled. */
    public final Property<String> coverSeed = new Property<>("");
    public final Property<Long> durationMs = new Property<>(0L);
    public final Property<Long> positionMs = new Property<>(0L);
    /** 进度跳变看门狗的状态（2026-10-11 ✓ 见 ticker 里那段注释 ✓）。 */
    private long lastPosWatched = -1L;
    private long lastPosWatchAt = 0L;
    /**
     * 经典歌词页进度条的<strong>过渡提示</strong>该不该亮。
     *
     * <p>亮的时间窗是「<b>过渡开始前三秒</b> → 过渡结束」：用户 2026-09-30 的要求
     * （「让进度条发光在 automix 开始前三秒就有」）。原来它等于
     * {@code snap.inTransition}，而那一位是过渡<b>开始那一刻</b>才翻的——偏偏界面也是
     * 那一刻就跳到了进场曲，于是提示与跳页同时发生，用户只看到「A 直接跳了」。
     *
     * <p>事实来源是引擎自己的快照（{@code AudioBackend.transitionSnapshot().leadInSecs}，
     * 由 {@code Director.transitionLeadInSecs()} 算、<b>含自动点位丢掉的尾巴</b>）。
     * 规则本身在 {@link AutomixGlow} 里，是纯函数。
     *
     * <p>每帧跟着位置一起同步（见 {@code pump()}）：过渡结束没有单独的回调，
     * 而"轮询同一个事实"天然自愈——界面不会因为漏掉一次回调而永远亮着。
     */
    public final Property<Boolean> automixTransitionActive = new Property<>(Boolean.FALSE);
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
    /** 歌词诊断节流（只打每 2 秒一次 ✓）。 */
    private long lyricDiagAtMs;
    /** Whether the full-screen lyric page is open (host draws it via Skija). */
    public final Property<Boolean> lyricsOpen = new Property<>(false);
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
    /**
     * 工作室页面是不是正在显示（由 QML 在页面进出时置位）。
     *
     * <p>它决定 {@link #pump()} 要不要每帧刷实时数据：频谱与表头只在有人看的时候才算，
     * 页面关着时一帧都不花（那 64 条频带的装箱不是免费的）。
     */
    private volatile boolean studioPageVisible;

    /** QML：工作室页面进入/离开时调它（{@code player.setStudioPageVisible(true/false)}）。 */
    public void setStudioPageVisible(boolean visible) {
        studioPageVisible = visible;
        // 引擎那一边也要知道：表头与频谱（4× 过采样的真峰 + 每块一次 FFT）只在有人看的时候算
        backend.setStudioMonitoring(visible);
        if (visible && studioRef != null) {
            studioRef.refresh();
        }
    }

    /** 设置页那一行「打开音频工作室」：请求 QML push 路由（页面栈归 QML）。 */
    public void requestOpenStudio() {
        studioOpenRevision.set(Long.valueOf(studioOpenRevision.peek().longValue() + 1L));
    }

    /** Host-published playback fraction (0..1) for the lyric page progress bar, set
     *  every frame from the live position so the wavy bar advances smoothly (the 5 Hz
     *  positionMs would step it). */
    public final Property<Double> lyricProgress = new Property<>(0.0);
    /** Current disk cache usage in MB (updated after each cache write). */
    public final Property<Long> cacheSizeMB = new Property<>(0L);

    /**
     * <b>音频工作室</b>（工作站页面）的桥：段表 / 频响曲线 / 实时频谱 / 表头 / 控制室。
     *
     * <p>为什么另起一个对象而不是往控制器上再挂几十个属性：工作站要读写的东西是
     * 「一条链」——十几段均衡 + 四组模块 + 表头 + 控制室。摊在控制器上会把那个类再撑大一圈，
     * 而且它们的<strong>落点也不同</strong>（段表进自定义 JSON、模块参数进设置行，
     * 见 {@code StudioBridge} 的类注释）。QML 那边读 {@code player.studio.xxx} 即可
     * （{@link #studio()}；设置中枢还没装上时是 null）。
     */

    /**
     * 「打开音频工作室」的版本号：设置页那一行（行动作）与测试用它请求打开页面。
     *
     * <p>QML 监听它的变化并 push 路由——与 {@code pageNavigationRevision} 同一个套路
     * （QML 的页面栈归 QML 管，Java 只负责说「现在该开了」）。
     */
    public final Property<Long> studioOpenRevision = new Property<>(0L);

    /**
     * 工作室桥（QML 读 {@code player.studioBridge}）。
     *
     * <p>为什么是**属性**而不是一个 {@code studio()} 方法：qml4j 里 Java 的方法暴露成
     * JS 的**函数**（要写成 {@code player.studio()}），而 QML 那一侧要的是「拿到这个对象
     * 然后读它的字段」——写成属性才对得上。第一版用的就是方法，表现是页面能渲染、
     * 但所有数据都是 undefined（{@code page.bridge !== null} 对 undefined 居然成立，
     * 于是连"没接上"的提示都不显示）。
     *
     * <p>宿主还没装上设置中枢时它是 {@code null}（页面会显示「没有接上设置」）。
     */
    public final Property<StudioBridge> studioBridge = new Property<>(null);

    // --- Local library ----------------------------------------------------
    public final Property<List<Track>> tracks = new Property<>(Collections.<Track>emptyList());
    public final Property<Integer> libraryCount = new Property<>(0);

    // --- Source plugins ---------------------------------------------------
    public final Property<List<PluginRow>> sourcePlugins =
            new Property<>(Collections.<PluginRow>emptyList());
    public final Property<String> primarySourcePlugin = new Property<>("");
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

    // --- 一键排列（听感顺序） ----------------------------------------------
    //
    // 排队页右上角那个按钮的状态。分析一首歌要读两遍（开头 30 秒 + 结尾 15 秒）
    // 再各跑一次 Analysis：单首几十到几百毫秒，一整个队列是分钟级。所以它全部
    // 跑在 flowWorker 上，界面这一侧只有这几个数字与一句话。
    //
    // 这里**不加**「排列依据/强度」这类设置：算法没有用户该调的旋钮
    // （见 FlowPlanner 的类注释——权重是「情绪最高、速度次之、响度再次」这条
    // 固定排序，调它只会让结果变得难以解释），而按钮本身已经是开关。
    /** 正在分析中（按钮变成取消、标题变成进度）。 */
    public final Property<Boolean> queueFlowSorting = new Property<>(Boolean.FALSE);
    /** 已完成的分析首数。 */
    public final Property<Integer> queueFlowSortDone = new Property<>(0);
    /** 这一趟要分析的首数（含正在播的那一首，它只作为「接缝的上一首」参与）。 */
    public final Property<Integer> queueFlowSortTotal = new Property<>(0);
    /** 完成后的那一句话（含「顺序没变」与「已取消」两种情况）；空串表示没事可说。 */
    public final Property<String> queueFlowSortNotice = new Property<>("");
    /** 没能分析的曲目标题，换行分隔——点旁边的详情看完整名单。 */
    public final Property<String> queueFlowSortSkipped = new Property<>("");

    /** 把"没能分析的曲目"拼成一句提示（2.1.1 的队列页没有弹窗组件，先就地显示）。 */
    public void showQueueFlowSortSkippedNotice() {
        String skipped = queueFlowSortSkipped.peek();
        if (skipped == null || skipped.isEmpty()) return;
        queueFlowSortNotice.set(dev.t1m3.qplayer.i18n.I18n.tr("queue.flow.skipped.body")
                + " " + skipped);
    }
    /** 没能分析的曲目<strong>首数</strong>（QML 里判断要不要显示「详情」）。 */
    public final Property<Integer> queueFlowSortSkippedCount = new Property<>(0);
    /** 上一次排列还能撤销吗。 */
    public final Property<Boolean> queueFlowSortUndoAvailable = new Property<>(Boolean.FALSE);

    /** 排列之前的队列快照。只在 {@link #queueFlowSortUndoAvailable} 为真时有意义。 */
    private volatile List<Track> queueFlowSortUndoOrder;

    /**
     * 「没能分析」名单里最多列多少首。
     *
     * <p>对话框里的文字是整块渲染、不滚动的：一个五十首的名单会把对话框撑出屏幕，
     * 于是最上面那几行（也就是用户最想看的那几首）反而被裁掉。超出部分折成
     * 「……还有 N 首」——首数在 {@link #queueFlowSortSkippedCount} 里仍然是真的。
     */
    private static final int SKIPPED_NAMES_MAX = 12;

    /**
     * 「一键排列」里现去解析一条直链的上限（秒）。
     *
     * <p>一次解析卡住不该拖住整趟排列——三十秒一首、一队一百首就是五十分钟。超时就如实
     * 把它记成「这一首没分析出来」，下一首继续。
     */
    private static final int FLOW_RESOLVE_TIMEOUT_S = 20;

    /** 一队待播曲目的听感特征缓存 + 后台分析。 */
    private final dev.t1m3.qplayer.audio.mix.FlowSorter flowSorter =
            new dev.t1m3.qplayer.audio.mix.FlowSorter(
                    Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "qplayer-flowsort");
                        t.setDaemon(true);
                        // 分析是纯 CPU 的，别跟界面抢：让出优先级，
                        // 用户一边听歌一边排歌单时不该出现掉音。
                        t.setPriority(Thread.MIN_PRIORITY + 1);
                        return t;
                    }),
                    new dev.t1m3.qplayer.audio.mix.FlowAnalyzer());

    /** Snapshot of {@link #customPlaylist} for the queue page's second tab. */
    public final Property<List<Track>> customPlaylistTracks = new Property<>(Collections.<Track>emptyList());
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
    public final Property<Integer> playlistCount = new Property<>(0);
    /** Standardized login metadata for the current primary source. */
    public final Property<String> loginProviderName =
            new Property<>(I18n.tr("login.defaultProvider"));
    public final Property<Boolean> pluginLoginActive = new Property<>(false);
    public final Property<List<LoginMethod>> loginMethods =
            new Property<>(Collections.<LoginMethod>emptyList());
    public final Property<Boolean> pluginQrLoginAvailable = new Property<>(false);
    public final Property<Boolean> pluginCredentialLoginAvailable = new Property<>(false);
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
     *  <p>The delay runs on {@link #postWorker}, a scheduler, rather than being a
     *  {@code Thread.sleep} submitted to {@code worker}: worker is the general
     *  single-threaded network queue, so a toast used to queue behind whatever
     *  request was already running and could surface tens of seconds late (a
     *  15s connect + 30s read timeout is the worst case) — long after whatever
     *  the user did to trigger it. It also no longer blocks a thread to wait. */
    private void showToast(String msg) {
        post(() -> toast.set(""));
        try {
            postWorker.schedule(() -> post(() -> toast.set(msg)), 30, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            // shutdown() stops postWorker; a toast queued past that point has no UI
            // left to reach anyway.
        }
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
        // 工作室桥：设置中枢从 SettingsCore.install 之后才拿得到，所以这里是懒创建
        // （第一次访问 studio() 时建）。构造函数里拿不到 settings 的那个引用——见 studio()。
        backend.setVolume(volume.peek());
        backend.setOnComplete(() -> onMain(this::autoAdvance));
        // 过渡**开始**那一刻（进场曲已经在出声）就把队列/界面推进过去，而不是等曲终。
        // 引擎的 onComplete 报的是「交接完成」，那已经是整个过渡窗口之后了——等它，
        // 界面就会在整个窗口里停在出场曲上、进度条一路走到 100%（用户实测的抱怨）。
        backend.setOnTransitionStart(source -> onMain(() -> onTransitionStarted(source)));
        // Re-baseline the media session's position once audio actually starts (the
        // backend prepares asynchronously, so the position at play() time is stale).
        backend.setOnStarted(() -> {
            errorRetryId = -1;
            errorRetryMediaId = "";
            consecutivePlaybackFailures = 0;
            stoppedLyricPositionMs = Math.max(0L, backend.audiblePosition());
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
                    backend.pause();
                    playingIntent = false;
                    post(() -> playing.set(false));
                    notifyPlayback();
                    return;
                }
            }
            notifyPlayback();
            // 当前曲**真的开始出声**了：第三处重算预告。
            //
            // 为什么非要在这里再来一次：前两处（playAt 末尾、直链解析成功之后）都可能在
            // 「队列还没定下来 / 当前曲还没真的接管输出」的时候跑，而这一处是
            // 「确定了在放这一首」的时刻——它同时也是最好的预热时机（见
            // prefetchNextForAutomix：这一刻去解析「下一首」的直链最不容易白做）。
            armNextSourceForAutomix();
            prefetchNextForAutomix();
        });
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
        worker.submit(this::loadSearchHistory);
        loadLyricOffsets();
        mediaMetaIndex.load();
        mediaPlaylistCacheIndex.load();
        playlistCacheIndex.load();
        diskCache.loadActivelyCached();
        pluginManager.startEnabled();
        loadQueue();
        loadCustomPlaylist();
        // Loading also migrates the previous single custom playlist on first run,
        // so it has to happen after loadCustomPlaylist has settled that file.
        localPlaylistStore.load();
        publishLocalPlaylists();
        saveLocalPlaylists();
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
                showToast(I18n.tr("toast.plugin.stateFailed", error.getMessage()));
            }
        });
    }

    public void setPrimarySourcePlugin(String pluginId) {
        worker.submit(() -> {
            try {
                pluginRegistry.setPrimaryProvider(pluginId);
                post(this::publishPlugins);
            } catch (Throwable error) {
                showToast(I18n.tr("toast.plugin.primaryFailed", error.getMessage()));
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
                if (entry == null) throw new IllegalArgumentException(I18n.tr("toast.plugin.notInstalled"));
                if (!entry.enabled) pluginManager.enable(pluginId);
                pluginRegistry.setPrimaryProvider(pluginId);
                post(this::publishPlugins);
            } catch (Throwable error) {
                showToast(I18n.tr("toast.plugin.enableFailed", safeMessage(error)));
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
                showToast(I18n.tr("toast.plugin.removed"));
            } catch (Throwable error) {
                post(() -> pluginInstallBusy.set(false));
                showToast(I18n.tr("toast.plugin.removeFailed", safeMessage(error)));
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
            pluginAccounts.account(providerId).whenComplete((account, error) -> post(() -> {
                if (generation != sourceAccountsGeneration.get()) return;
                SourceAccountRow row = new SourceAccountRow();
                row.providerId = providerId;
                row.sourceName = providerName;
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
     * Aim the login surface at one listed source, so the account page can sign in
     * to a source that is not the primary one. Returns false when that source
     * cannot be signed into, and the caller should not open the login dialog.
     */
    public boolean beginSourceLogin(String providerId) {
        if (providerId == null || providerId.isEmpty()) return false;
        for (PluginManifest manifest : pluginManager.enabledProviders()) {
            if (!manifest.id.equals(providerId)) continue;
            if (!manifest.capabilitySet().contains(ProviderCapability.LOGIN)) return false;
            publishLoginMetadata(manifest);
            return true;
        }
        return false;
    }

    /** Sign out of one listed source, leaving every other source signed in. */
    public void logoutSource(String providerId) {
        if (providerId == null || providerId.isEmpty()) return;
        final String provider = providerId;
        if (provider.equals(pendingPluginLoginProvider)) {
            legacyCredentialMigrationGeneration.incrementAndGet();
            legacyCredentialMigrationAttempted = true;
        }
        pluginAccounts.logout(provider).whenComplete((ignored, error) -> post(() -> {
            if (error != null) {
                showToast(I18n.tr("toast.logout.failed", safeMessage(error)));
                return;
            }
            // Only the primary source owns the account header; signing out of any
            // other source must not blank it.
            if (provider.equals(pluginRegistry.primaryProvider())) {
                if ("netease".equals(provider)) netease.logout();
                clearPublishedAccount();
            }
            dropSourceUserData(provider);
            refreshSourceAccounts();
            showToast(I18n.tr("toast.logout.done"));
        }));
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
            case "webAuth.runScript":
                // 上游把这一档从「宿主写死 SDK、只还 token」改成了 provider 无关：
                // 插件自己给 origin 与脚本，宿主只负责在一个离屏、不可聚焦的系统 WebView 里
                // 跑完并把 qplayerWebAuthDone(value) 的字符串还回去。插件为此要的宿主版本更高，
                // 新插件接不上就是这一条。
                return runWebAuthScript(pluginId, args);
            case "webAuth.watchmanToken":
                // 老档保留：已经能用的老插件不改它。新插件走上面那条。
                return requestWatchmanToken(pluginId, args);
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
        result.put("positionMs", Math.max(0L, backend.audiblePosition()));
        result.put("automixAutoStyles", automixAutoStyles);
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
        shuffleHistory.clear();
        playIndex = -1;
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

    /**
     * {@code webAuth.runScript}：在一个离屏、不可聚焦的系统 WebView 里跑插件给的脚本。
     *
     * <p>与 {@link #requestWatchmanToken} 的区别就是"谁拥有 SDK 细节"：老档由宿主写死
     * Watchman 的 URL 与初始化，只把 token 还回去；这一档**全是插件的事**——它给
     * {@code originUrl} 与 {@code script}，宿主只保证：origin 在该插件的 networkDomains 白名单里、
     * 脚本不超过 256 KiB、只暴露 {@code qplayerWebAuthDone(string)} 这一个出口、
     * 一次会话只出一个结果（或超时），结果不超过 64 KiB。
     *
     * <p>{@code webAuth} 这个权限因此要按"允许在该 origin 上跑浏览器代码"来理解：
     * 子资源请求是平台 WebView 发的，不走 {@code http.request} 的策略。
     */
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

    private static String pluginText(Object raw, int limit) {
        String value = raw == null ? "" : String.valueOf(raw);
        if (value.length() > limit) throw new IllegalArgumentException("plugin text is too long");
        return value;
    }

    private CompletableFuture<Object> requestWatchmanToken(String pluginId,
                                                            Map<String, Object> arguments) {
        String originUrl = pluginText(arguments.get("originUrl"), 2048);
        String scriptUrl = pluginText(arguments.get("scriptUrl"), 2048);
        String productNumber = pluginText(arguments.get("productNumber"), 128);
        String businessId = pluginText(arguments.get("businessId"), 128);
        if (originUrl.isEmpty() || scriptUrl.isEmpty()
                || !pluginHostApi.allowsReturnedUrl(pluginId, originUrl)
                || !pluginHostApi.allowsReturnedUrl(pluginId, scriptUrl)) {
            throw new SecurityException("web auth URL is outside the plugin network grant");
        }
        if (!productNumber.matches("[A-Za-z0-9_-]{1,128}")
                || !businessId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("invalid watchman identifier");
        }
        WatchmanTokenLauncher launcher = watchmanTokenLauncher;
        if (launcher == null) return failedPluginCall("system WebView is unavailable");
        CompletableFuture<String> launched;
        try {
            launched = launcher.request(originUrl, scriptUrl, productNumber, businessId);
        } catch (Throwable error) {
            CompletableFuture<Object> failed = new CompletableFuture<>();
            failed.completeExceptionally(error);
            return failed;
        }
        if (launched == null) return failedPluginCall("system WebView did not start");
        return launched.thenApply(token -> {
            if (token == null || token.isEmpty()) {
                throw new IllegalStateException("watchman returned an empty token");
            }
            if (token.length() > 16 * 1024) {
                throw new IllegalArgumentException("watchman token is too long");
            }
            return token;
        });
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
                showToast(I18n.tr("toast.plugin.catalogFailed"));
            }
        });
    }

    public void installCatalogPlugin(String pluginId) {
        if (pluginId == null || pluginId.isEmpty() || Boolean.TRUE.equals(pluginInstallBusy.peek())) return;
        PluginCatalogEntry selected = null;
        for (PluginCatalogEntry entry : pluginCatalogEntries.peek()) {
            if (pluginId.equals(entry.id)) { selected = entry; break; }
        }
        if (selected == null) { showToast(I18n.tr("toast.plugin.unknownEntry")); return; }
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
                            ? I18n.tr("plugin.entry.noPermissions")
                            : String.join(I18n.tr("common.listSeparator"),
                                    verified.manifest().permissions));
                    pendingPluginTrusted.set(true);
                    pluginInstallBusy.set(false);
                    pluginInstallPromptRevision.set(pluginInstallPromptRevision.peek() + 1L);
                });
            } catch (Throwable error) {
                post(() -> pluginInstallBusy.set(false));
                showToast(I18n.tr("toast.plugin.downloadFailed", safeMessage(error)));
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
            loginProviderName.set(I18n.tr("login.defaultProvider"));
            pluginLoginActive.set(false);
            loginMethods.set(Collections.<LoginMethod>emptyList());
            pluginQrLoginAvailable.set(false);
            pluginCredentialLoginAvailable.set(false);
            webLoginAvailable.set(false);
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
        pluginAccounts.methods(provider.id).whenComplete((methods, error) -> post(() -> {
            // Another source claimed the login surface while this was in flight.
            if (generation != loginMetadataGeneration.get()) return;
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
            showToast(I18n.tr("toast.plugin.pickUnsupported"));
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
                showToast(I18n.tr("toast.plugin.installed", verified.manifest().name));
            } catch (Throwable error) {
                pendingPluginPackage = null;
                post(() -> pluginInstallBusy.set(false));
                showToast(I18n.tr("toast.plugin.installFailed", error.getMessage()));
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
        boolean temporary = pendingPluginPackageTemporary;
        pendingPluginPackage = null;
        // 删临时安装包同样是在渲染线程上做 I/O（QML 直接调这个方法）。状态必须在本方法
        // 里就清干净 —— 那是用户看得见的部分，回投只会让它晚一帧 —— 而磁盘上的删除没有
        // 任何人在等，交给 worker 就好。顺序也刻意保持：先摘掉引用，再删文件，否则可能删到
        // 正在被使用的包。
        if (temporary && previous != null) {
            final java.nio.file.Path file = previous.file();
            worker.execute(() -> {
                try { java.nio.file.Files.deleteIfExists(file); }
                catch (java.io.IOException ignored) {}
            });
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
        showToast(I18n.tr("toast.keystore.waitingUnlock"));
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
        showToast(I18n.tr("toast.keystore.checking"));
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
        showToast(I18n.tr("toast.keystore.waiting"));
        worker.submit(() -> {
            boolean enabled = pluginHostApi.enableSystemCredentialProtection();
            post(() -> {
                credentialOwnerOnlyFallback.set(
                        pluginHostApi.usesOwnerOnlyCredentialProtection());
                credentialProtectionBusy.set(false);
                if (!enabled) showToast(I18n.tr("toast.keystore.enableFailed"));
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

    /** 宿主跑一段插件脚本并还回它的结果（{@code webAuth.runScript}）。 */
    @FunctionalInterface
    public interface WebAuthScriptLauncher {
        CompletableFuture<String> run(String originUrl, String script);
    }

    /** 装上宿主的系统 WebView 脚本桥（桌面 {@code DesktopWebAuthScript} / 安卓 {@code AndroidWebAuthScript}）。 */
    public void setWebAuthScriptLauncher(WebAuthScriptLauncher launcher) {
        this.webAuthScriptLauncher = launcher;
    }

    @FunctionalInterface
    public interface WatchmanTokenLauncher {
        CompletableFuture<String> request(String originUrl, String scriptUrl,
                                          String productNumber, String businessId);
    }

    /** Install the shell's bounded system-WebView bridge for anti-cheat probes. */
    public void setWatchmanTokenLauncher(WatchmanTokenLauncher launcher) {
        this.watchmanTokenLauncher = launcher;
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
            showToast(I18n.tr("toast.linkCopied"));
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
            showToast(I18n.tr("toast.linkCopied"));
        } else {
            showToast(url);
        }
    }

    /**
     * 把后端音源的音量设成「用户要的那一档」。
     *
     * <p>这里从前是 {@code cancelFadeAtGain(1f)}：把音量淡变那条时钟取消，并把增益
     * 落回 1。淡变随设置项一起删掉之后，增益恒为 1，这件事就只剩「换源之前把用户的
     * 音量设回去」——后端换源时自己的增益可能是单位值，不重设就是接缝上一跳。
     */
    private void playBackend(String source, long startMs) {
        playBackend(source, Collections.<String, String>emptyMap(), startMs);
    }

    private void playBackend(String source, Map<String, String> headers, long startMs) {
        backend.setVolume(userVolume);
        backend.play(source, headers != null ? headers : Collections.<String, String>emptyMap(), startMs);
    }

    // --- Frame pump (render thread) --------------------------------------

    /**
     * Queue a mutation to run on the render thread at the next {@link #pump()}.
     *
     * <p>Public so a subsystem that produces its own background updates (the model
     * downloader in {@code audio.model}, which reports progress from a worker
     * thread) can hand them over without opening a second queue: writing a qml4j
     * {@code Property} notifies its bindings on the calling thread, so a worker
     * writing one directly would be mutating the scene the renderer is walking.
     */
    public void runOnRenderThread(Runnable r) {
        if (r != null) post(r);
    }

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
        if (studioPageVisible) {
            // 工作站在看的时候每帧刷实时数据（频谱/表头/控制室）。它每次都装箱几十个
            // Float——但那是渲染线程上的 60 Hz，不是音频线程，而页面关着时一次都不做。
            StudioBridge s = studioRef;
            if (s != null) {
                s.tick();
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastPositionPush >= 200L) {
            lastPositionPush = now;
            if (backend.isPlaying()) {
                long pos = backend.audiblePosition();
                // **进度跳变看门狗**（2026-10-11 ✓ 用户：「切换过来后瞬间有概率出现进度轻微跳变」✗）：
                // 位置由这里每 200 ms 拉一次 ✓ 引擎侧已验证连续（交接前后差 +0 帧 ✓）
                // ⇒ 若"位置推进量"和"真实流逝时间"差超过 500 ms ✓ 就一定是某处在跳 ✓
                // ⇒ 自己打出来（不用你盯着时钟 ✓ 下次跑一定抓到 ✓）
                final long wallNow = System.currentTimeMillis();
                if (lastPosWatchAt > 0L) {
                    final long dPos = pos - lastPosWatched;
                    final long dWall = wallNow - lastPosWatchAt;
                    if (Math.abs(dPos - dWall) > 500L) {
                        dev.t1m3.qplayer.util.Logger.info(
                                "进度跳变抓现行 · 位置 {} → {} ms（推进 {} ms）· 实际流逝 {} ms · 差 {} ms · 时长 {} ms",
                                Long.valueOf(lastPosWatched), Long.valueOf(pos), Long.valueOf(dPos),
                                Long.valueOf(dWall), Long.valueOf(dPos - dWall),
                                Long.valueOf(durationMs.get().longValue()));
                    }
                }
                lastPosWatched = pos;
                lastPosWatchAt = wallNow;
                positionMs.set(pos);
                updateLyricIndex(pos - LyricConfig.instance.offsetMs.getValue());
            }
            dev.t1m3.qplayer.audio.engine.TransitionSnapshot snap = backend.transitionSnapshot();
            // 发光窗 = 开始前三秒 → 结束（规则是纯函数，见 AutomixGlow）：
            // 「过渡开始那一瞬才亮」等于没提示——界面也是那一瞬跳到下一首的。
            boolean glow = snap != null && AutomixGlow.active(snap.leadInSecs);
            if (glow != Boolean.TRUE.equals(automixTransitionActive.peek())) {
                automixTransitionActive.set(Boolean.valueOf(glow));
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

    /**
     * Zero the one-shot event counters QML watches, for a host that is about to
     * build a fresh scene over a controller that outlived the previous one
     * (Android keeps this instance in a static across Activity recreations so
     * backgrounded playback stays connected).
     *
     * <p>Each of these properties is an event channel, not state: QML mirrors it
     * into a {@code property real ...Watch} and acts in the Changed handler. A
     * rebuilt scene's binding therefore initialises to whatever count the
     * previous scene already consumed, which reads as a change from the
     * property's 0 default and replays the last prompt -- during construction,
     * before the scene has been parented or laid out. Every watcher ignores 0,
     * so resetting is invisible to a scene that is still attached.
     *
     * <p>Call this before the new scene is created, never from a secondary scene
     * (the desktop lyric window) that shares the same controller.
     */
    public void resetSceneEventRevisions() {
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
        // redundant — pump() re-derives lyricIndex from backend.audiblePosition() every ~200ms
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

    /**
     * 位置 → 当前歌词行。
     *
     * <h3>「不许往回退」（2026-09-29 用户报的手机端现象）</h3>
     *
     * <p>用户：「特别是手机端，歌词进度一直在超小进度波动，逐词显示能看到歌词卡了一下
     * 往回去了一丢丢」。原因是这个映射**每次照当前位置重算**，而位置是每 ~200ms 从宿主
     * 取一次的：手机端位置抖动（或一次取到略旧的值）就会让同一行里算出的行号往回跳一格，
     * 逐词高亮的扫光于是往回退一点点。
     *
     * <p>所以：位置只**小幅**回退（小于 {@link #LYRIC_BACKWARD_TOLERANCE_MS}）时，
     * 行号保持不动——那是测量抖动，不是用户在往回听；真的往回跳（拖动进度条、
     * 重新开始、换曲）超过这个量就照实跟随。
     */
    private void updateLyricIndex(long pos) {
        List<LyricLine> ly = lyrics.peek();
        if (ly == null || ly.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < ly.size(); i++) {
            if (ly.get(i).startMs() <= pos) idx = i;
            else break;
        }
        int prev = lyricIndex.peek() == null ? -1 : lyricIndex.peek().intValue();
        if (idx < prev && lastLyricPositionMs - pos < LYRIC_BACKWARD_TOLERANCE_MS) {
            // 小幅回退：当作抖动，行号不动（位置继续按真实值走，下一句照常接上）
            lastLyricPositionMs = Math.max(lastLyricPositionMs, pos);
            return;
        }
        lastLyricPositionMs = pos;
        if (idx != prev) lyricIndex.set(idx);
        // **歌词对齐诊断**（2026-10-09 ✓ 用户：「永远显示下一句」✗）只打每 2 秒一次 ✓
        long nowL = System.currentTimeMillis();
        if (nowL - lyricDiagAtMs > 2000L) {
            lyricDiagAtMs = nowL;
            int n = idx < 0 ? -1 : (idx + 1 < ly.size() ? idx + 1 : -1);
            Logger.info("[歌词诊断] pos={}ms · 当前行={}（start={}）· 下一行start={} · 行首={}ms · 句数={}",
                    Long.valueOf(pos), Integer.valueOf(idx),
                    Long.valueOf(idx < 0 ? -1L : ly.get(idx).startMs()),
                    Long.valueOf(n < 0 ? -1L : ly.get(n).startMs()),
                    Long.valueOf(ly.get(0).startMs()), Integer.valueOf(ly.size()));
        }
    }

    /**
     * 行号允许「不往回退」的位置回退量（毫秒）。
     *
     * <p>1.5 秒：手机端位置抖动是几十毫秒量级，而拖动进度条/重开至少是秒级——
     * 取这个量能把两者分开，且不影响任何正常播放。
     */
    private static final long LYRIC_BACKWARD_TOLERANCE_MS = 1500L;

    /** 上一次算行号用的位置（只用于判断"是不是小幅回退"）。 */
    private long lastLyricPositionMs;

    private volatile Runnable renderWake;

    /** Android GLSurfaceView uses this to leave WHEN_DIRTY when a host task lands. */
    public synchronized void setRenderWake(Runnable wake) {
        this.renderWake = wake;
    }

    /** A retiring scene must not clear a replacement scene's wake callback. */
    public synchronized void clearRenderWake(Runnable expected) {
        if (renderWake == expected) renderWake = null;
    }

    private void post(Runnable r) {
        uiQueue.add(r);
        Runnable wake = renderWake;
        if (wake != null) wake.run();
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

    public void setTemperaOpen(boolean open) {
        temperaOpen.set(open);
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
    /**
     * QML 告诉 Java"**封面中心的绝对 y**"（2026-10-11 ✓ 用户要求把活跃行锚到封面中心 ✓）。
     *
     * <p>QML 侧调用：`player.setLyricCoverMidY(artFrame.y + artFrame.height / 2)` ✓
     * 传窗口坐标系里的值 ✓（和歌词列同一个坐标系 ✓）。
     * <p>比例由 `LyricCompositor` 算 ✓（它知道列顶与列高 ✓）。传 -1 表示"不知道"✓ 会退回默认锚点 ✓。
     */
    public void setLyricCoverMidY(float y) {
        dev.t1m3.qplayer.lyric.skia.LyricCompositor.coverMidY = y;
    }

    // ---- 官方 UI 需要的"远端歌单能力门禁"（2026-10-11 ✓ B 方案 ✓）----
    // 我们的引擎没有统一的 provider 登录态，也不支持回写远端歌单（顺序/点赞都在服务端）。
    // 这三个门禁一律 false ⇒ 官方详情页里对应的按钮自然收起 ✓ 不会出现"点了没反应"✗
    // （不是空实现蒙混：语义就是"这个宿主做不到"✓ 与 SettingsCatalog 里远端歌单的定位一致）

    /** 我们引擎不支持"回写远端歌单"（顺序/封面/删除都做不到）⇒ 一律 false。 */
    public boolean canMutateMediaPlaylist(String playlistId) {
        return false;
    }

    /** 同上：远端歌单的"点赞/收藏"也不支持。 */
    public boolean canHeartMediaPlaylist(String playlistId) {
        return false;
    }

    /** 没有统一登录态（各插件自己管凭据）⇒ 一律 false。 */
    public boolean providerLoggedIn(String provider) {
        return false;
    }

    /** 官方页面批量移除时调用；我们的队列由本地维护 ⇒ 记日志并忽略（不假装成功 ✗）。 */
    public void removeMediaManyFromCurrentPlaylist(java.util.List<String> ids) {
        dev.t1m3.qplayer.util.Logger.info("批量移除远端歌单不受支持（B 方案）：{} 项",
                Integer.valueOf(ids == null ? 0 : ids.size()));
    }

    /** 官方页面的"取消喜欢"批量入口；我们不做远端点赞 ⇒ 记日志并忽略。 */
    public void unlikeMediaMany(java.util.List<String> ids) {
        dev.t1m3.qplayer.util.Logger.info("批量取消喜欢不受支持（B 方案）：{} 项",
                Integer.valueOf(ids == null ? 0 : ids.size()));
    }

    /** 官方页面在删除远端歌单时调用；我们做不到 ⇒ 记一条日志并忽略。 */
    public void removeMediaPlaylist(String playlistId) {
        dev.t1m3.qplayer.util.Logger.info("远端歌单删除不受支持（B 方案）：{}", playlistId);
    }

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
     *  toasts I18n.tr("toast.update.upToDate")/an error message instead of failing silently, since a
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
                    if (manual) showToast(I18n.tr("toast.update.upToDate"));
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
                if (manual) showToast(I18n.tr("toast.update.checkFailed"));
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
            shuffleHistory.clear();
            index.set(-1);
            currentFilePath.set("");
            // 队列空了：预告要撤掉，临时钉住的音频也要交还（重算一次预告就是这两件事）
            onMain(this::armNextSourceForAutomix);
            return;
        }
        if (i < cur) {
            playIndex = cur - 1;
            index.set(cur - 1);
        } else if (i == cur) {
            onMain(() -> playAt(Math.min(cur, queue.size() - 1)));
            return;
        }
        // 删掉的那一首很可能正是**已经预告给引擎的「下一首」**：不重算的话
        // 引擎会在曲终把一首已经被用户删掉的歌接上来（控制器随后又会切到对的下一首，
        // 于是接缝上出现一次多余的换曲）。重算同时把该撤的临时钉住撤掉。
        onMain(this::armNextSourceForAutomix);
    }

    /**
     * 把排队页里的第 {@code from} 项拖到 {@code to}（上游 2.2.x 的 QML 会调它）。
     *
     * <p>从上游合进来时只移植了这条**纯列表操作**（不碰播放状态机）：
     * 播放位置跟着同一条映射挪，预告按新顺序重算。
     */
    public void moveQueueItem(int from, int to) {
        int size = queue.size();
        if (size < 2) return;
        if (from < 0 || from >= size || to < 0 || to >= size || from == to) return;
        Track moved = queue.remove(from);
        queue.add(to, moved);
        int cur = playIndex;
        if (cur >= 0) {
            playIndex = movedQueueIndex(cur, from, to);
            index.set(playIndex);
        }
        if (pendingResumeIndex >= 0) {
            pendingResumeIndex = movedQueueIndex(pendingResumeIndex, from, to);
        }
        queueTracks.set(new ArrayList<>(queue));
        saveQueue();
        // 顺序变了 ⇒ 预告的「下一首」也变了
        onMain(this::armNextSourceForAutomix);
    }

    /** 第 {@code i} 项在第 {@code from} 项移到 {@code to} 之后落在哪个下标。 */
    private static int movedQueueIndex(int i, int from, int to) {
        if (i == from) return to;
        if (from < i && to >= i) return i - 1;
        if (from > i && to <= i) return i + 1;
        return i;
    }

    /**
     * 清空队列并结束播放（上游 2.2.x 的 QML 会调它）。
     *
     * <p>收尾口径与 {@link #removeFromQueue(int)} 删掉最后一项时一致：
     * 队列清空、位置复位、预告撤销、临时钉住的音频交还、队列落盘，
     * 区别只是这里还把播放停掉。
     */
    public void clearQueue() {
        if (queue.isEmpty()) return;
        queue.clear();
        playIndex = -1;
        shuffleHistory.clear();
        index.set(-1);
        currentFilePath.set("");
        playing.set(false);
        loading.set(false);
        backend.pause();
        queueTracks.set(new ArrayList<>(queue));
        saveQueue();
        onMain(this::armNextSourceForAutomix);
    }

    // --- 一键排列：把排队页的歌重排成「相邻两首接得上」的顺序 ----------------
    //
    // 算法本体在 player-core 的 dev.t1m3.qplayer.audio.mix 里（FlowPlanner 纯逻辑 +
    // FlowAnalyzer 量特征 + FlowSorter 后台跑/缓存/进度/取消），这一层只负责三件事：
    // 把 Track 变成「音源句柄 + 缓存键」、把排列结果写回**既有的** queue 列表、
    // 以及把「哪几首没分析出来」如实说出来。
    //
    // 依据 LiveSort（https://github.com/LuN3cy/LiveSort）公开的算法说明做的
    // 洁净室重实现：那个仓库里没有 LICENSE 文件（按「保留所有权利」处理），
    // 所以这里**没有**逐行移植它——见 FlowFeatures 的类注释。

    /**
     * 按听感把<strong>待播部分</strong>重排一遍。QML 的「一键排列」按钮调它。
     *
     * <h3>正在播的那一首不动</h3>
     *
     * <p>只排 {@code playIndex} <strong>之后</strong>的歌，正在播的那一首以及它前面的
     * 历史原地不动。理由（这是最容易被忽略、也最容易让用户觉得「我歌怎么换了」的地方）：
     *
     * <ol>
     *   <li><strong>用户的心智模型是「接下来听什么」</strong>。LiveSort 排的是还没开始听的
     *       歌单，而 QPlayer 排的是一个<strong>已经在播</strong>的队列——把当前这首挪走，
     *       它要么立刻换歌，要么 {@code playIndex} 指向了另一首（高亮跳走，
     *       自动续播的歌也不是用户以为的那首）。两种都不是用户点这个按钮想要的结果。</li>
     *   <li><strong>前面的部分是「已经发生过」的记录</strong>。重排它等于改写历史，
     *       「上一首」会变成一首你根本没听过的歌，而这是不可撤销的体验。</li>
     * </ol>
     *
     * <p>当前这首<strong>仍然参与</strong>判断：它作为排序的「锚点」，
     * 让排在第一的那首去接它的结尾（见 {@code FlowPlanner.plan} 的 anchor 参数）。
     * 最需要顺的那一处接缝恰恰就是这里，少了它这一步就白做了。
     *
     * <p>队列里没有正在播的曲子时（{@code playIndex < 0}）整队重排。
     */
    public void sortQueueByFlow() {
        if (Boolean.TRUE.equals(queueFlowSorting.peek())) return;

        final List<Track> before = new ArrayList<>(queue);
        final int current = playIndex;
        final int from = (current >= 0 && current < before.size()) ? current + 1 : 0;
        final int count = before.size() - from;
        if (count < 2) {
            showToast(I18n.tr("queue.flow.tooFew"));
            return;
        }

        // 待排的每一位一个请求。**手上没有句柄的位也会照常请求**：分析线程上会现去解析一次直链
        // （见下面的 setResolver 与 resolveFlowSortSource）。真的解析不出来时，FlowPlanner 会让
        // 那一位原地不动，并在结果里如实报出来。
        final List<dev.t1m3.qplayer.audio.mix.FlowSorter.Request> requests = new ArrayList<>(count + 1);
        /** 与 requests 逐个对齐的曲目（解析那一侧要拿它去问插件要直链）。 */
        final List<Track> targets = new ArrayList<>(count + 1);
        final boolean[] hasFeatures = new boolean[count];
        for (int k = 0; k < count; k++) {
            Track t = before.get(from + k);
            targets.add(t);
            dev.t1m3.qplayer.audio.mix.FlowSorter.Request req = flowRequestOf(t);
            requests.add(req);
            hasFeatures[k] = req.source != null && !req.source.isEmpty();
        }
        // 锚点放在**最后**一位：槽位 k 就是 features[k]，不需要任何下标搬运。
        // 分析不出来就干脆不请求，锚点自然是 null（退化成 LiveSort 的纯情绪贴合起点）。
        final boolean anchorRequested = current >= 0 && current < before.size()
                && appendFlowRequest(requests, before.get(current));
        if (anchorRequested) {
            targets.add(before.get(current));
        }

        // 装上「按需解析直链」：在线曲目的直链只有播放过才存在，所以一个刚打开的歌单里绝大多数歌
        // 都没有句柄。没有这条接缝时它们全部落进「没能分析」——真机上就是「只能排几首，剩下全说
        // 分析不了」（2026-09-20 实测）。代价是每首一次直链请求；分析窗口只读开头与结尾那几十秒，
        // 且特征会按曲目身份进缓存，所以**再排一次不再联网**。
        final java.util.concurrent.atomic.AtomicInteger resolvedFromNetwork =
                new java.util.concurrent.atomic.AtomicInteger();
        flowSorter.setResolver((index, request) -> {
            dev.t1m3.qplayer.audio.mix.FlowSorter.Resolution resolution =
                    resolveFlowSortSource(targets, index);
            if (resolution.source != null && !resolution.source.isEmpty()) {
                resolvedFromNetwork.incrementAndGet();
            }
            return resolution;
        });

        queueFlowSortUndoOrder = null;
        queueFlowSortUndoAvailable.set(false);
        queueFlowSortNotice.set("");
        queueFlowSortSkipped.set("");
        queueFlowSortSkippedCount.set(0);
        queueFlowSortDone.set(0);
        queueFlowSortTotal.set(requests.size());
        queueFlowSorting.set(true);

        flowSorter.start(requests, new dev.t1m3.qplayer.audio.mix.FlowSorter.Listener() {
            @Override
            public void onProgress(int done, int total) {
                post(() -> {
                    queueFlowSortDone.set(done);
                    queueFlowSortTotal.set(total);
                });
            }

            @Override
            public void onFinished(dev.t1m3.qplayer.audio.mix.FlowSorter.Result result) {
                final int fetched = resolvedFromNetwork.get();
                post(() -> applyQueueFlowSort(before, from, count, hasFeatures,
                        anchorRequested, result, fetched));
            }

            @Override
            public void onFailed(String message) {
                post(() -> {
                    queueFlowSorting.set(false);
                    queueFlowSortNotice.set(I18n.tr("queue.flow.failed", message));
                    Logger.warn("一键排列失败：{}", message);
                });
            }
        });
    }

    /** 取消正在跑的一趟排列（协作式：当前这一首会跑完，见 {@code FlowSorter} 的类注释）。 */
    public void cancelQueueFlowSort() {
        if (!Boolean.TRUE.equals(queueFlowSorting.peek())) return;
        flowSorter.cancel();
    }

    /**
     * 撤销上一次排列。
     *
     * <p>只在<strong>队列里的歌一首没变</strong>时才认：用户中途删掉一首、
     * 或者换了整个队列再点撤销，恢复一份旧快照会把他后来的操作一起抹掉。
     * 两侧的曲目集合不同就直接拒绝，而不是「尽力而为」地合并——
     * 那种合并没有任何可解释的结果。
     */
    public void undoQueueFlowSort() {
        if (!Boolean.TRUE.equals(queueFlowSortUndoAvailable.peek())) return;
        final List<Track> restore = queueFlowSortUndoOrder;
        if (restore == null || restore.isEmpty()) return;
        if (!sameSongSet(restore, new ArrayList<>(queue))) {
            queueFlowSortUndoOrder = null;
            queueFlowSortUndoAvailable.set(false);
            queueFlowSortNotice.set("");
            showToast(I18n.tr("queue.flow.undoStale"));
            return;
        }
        queueFlowSortUndoOrder = null;
        queueFlowSortUndoAvailable.set(false);
        queueFlowSortNotice.set("");
        queueFlowSortSkipped.set("");
        queueFlowSortSkippedCount.set(0);
        queue.clear();
        queue.addAll(restore);
        queueTracks.set(new ArrayList<>(queue));
        onMain(this::armNextSourceForAutomix);
        worker.submit(this::saveQueue);
        showToast(I18n.tr("queue.flow.undone"));
    }

    /**
     * 把分析结果落成新顺序（渲染线程）。
     *
     * <p>槽位与请求逐个对齐；{@code null} 的那些是「没分析出来」，
     * {@link dev.t1m3.qplayer.audio.mix.FlowPlanner#plan} 会让它们原地不动。
     */
    private void applyQueueFlowSort(List<Track> before, int from, int count,
                                    boolean[] hasFeatures, boolean anchorRequested,
                                    dev.t1m3.qplayer.audio.mix.FlowSorter.Result result,
                                    int fetchedFromNetwork) {
        queueFlowSorting.set(false);
        if (result == null) return;
        if (result.cancelled) {
            queueFlowSortNotice.set(I18n.tr("queue.flow.cancelled"));
            return;
        }
        // 分析期间队列被改过（删了一首、换了整个队列、播了别的歌单）：
        // 快照里的下标已经不指向同一批歌了，照它写回去会**复活**已删的曲目、
        // 或者覆盖用户后来的操作。宁可什么都不做，也不能做错。
        if (!sameQueueContent(before, new ArrayList<>(queue))) {
            queueFlowSortNotice.set(I18n.tr("queue.flow.queueChanged"));
            Logger.warn("一键排列：分析期间队列被改动，本次不改动顺序");
            return;
        }

        List<dev.t1m3.qplayer.audio.mix.FlowFeatures> slots = new ArrayList<>(count);
        int skipped = 0;
        for (int k = 0; k < count; k++) {
            dev.t1m3.qplayer.audio.mix.FlowFeatures f =
                    hasFeatures[k] ? result.features.get(k) : null;
            slots.add(f);
            if (f == null) skipped++;
        }
        dev.t1m3.qplayer.audio.mix.FlowFeatures anchor = anchorRequested
                ? result.features.get(count) : null;

        dev.t1m3.qplayer.audio.mix.FlowPlanner.Plan plan =
                dev.t1m3.qplayer.audio.mix.FlowPlanner.plan(slots, anchor);

        boolean changed = false;
        for (int k = 0; k < plan.order.length; k++) {
            if (plan.order[k] != k) {
                changed = true;
                break;
            }
        }

        if (changed) {
            // **就地**按置换重排，而不是 clear() + addAll()：
            // queue 是播放线程（自动续播）也会读的列表，clear 之后到 addAll 之前
            // 有一小段窗口，那一瞬间队列是空的——正好撞上就变成「歌播完了却没有下一首」。
            // 这里每一步都是一次 set，任何一个瞬间列表里都是**同一批歌、不多不少**，
            // 读到的顺序最坏只是新旧混合，不会缺歌、也不会重复。
            //
            // 每一步都从 before（那份没被改动过的快照）取值，所以「覆盖掉还没搬走的
            // 那一个」这个问题天然不存在。（queue 的 set 本身是原子的。）
            boolean[] moved = new boolean[count];
            for (int start = 0; start < count; start++) {
                if (moved[start] || plan.order[start] == start) {
                    moved[start] = true;
                    continue;
                }
                Track temp = before.get(from + start);
                int i = start;
                while (plan.order[i] != start) {
                    queue.set(from + i, before.get(from + plan.order[i]));
                    moved[i] = true;
                    i = plan.order[i];
                }
                queue.set(from + i, temp);
                moved[i] = true;
            }
            queueTracks.set(new ArrayList<>(queue));
            // 顺序变了，「下一首」也就变了：不重新预告的话，AI 模式 / 声波重合
            // 会去接原来那一首——用户看到的新顺序与听到的过渡对不上。
            onMain(this::armNextSourceForAutomix);
            worker.submit(this::saveQueue);
            // 撤销点记录的是**改动前**的完整队列（含正在播的那一首与它前面的历史，
            // 所以撤销之后连播放位置都不需要动）。
            queueFlowSortUndoOrder = new ArrayList<>(before);
            queueFlowSortUndoAvailable.set(true);
        }

        String notice;
        if (plan.movable < 2) {
            // 能分析的不足两首：没有「相邻」可言，顺序一动不动。
            // 这与「已经是最顺的」是两件事，不能共用一句话——后者会让人以为
            // 算法看过整队之后认可了这个顺序。
            notice = skipped > 0
                    ? I18n.tr("queue.flow.notEnough", skipped)
                    : I18n.tr("queue.flow.unchanged", plan.movable);
        } else if (changed) {
            notice = skipped > 0
                    ? I18n.tr("queue.flow.doneSkipped", plan.movable, skipped)
                    : I18n.tr("queue.flow.done", plan.movable);
        } else {
            notice = skipped > 0
                    ? I18n.tr("queue.flow.unchangedSkipped", plan.movable, skipped)
                    : I18n.tr("queue.flow.unchanged", plan.movable);
        }
        queueFlowSortNotice.set(notice);

        // 「哪几首没分析出来」——如实列出来，不藏在日志里。
        // 名单就是槽位为 null 的那些位（两种原因：拿不到句柄、解码/分析失败），
        // 顺序与队列一致，所以用户在列表里能一一对上。
        // 锚点（正在播的那一首）不在被排的范围内，它的失败不该出现在这份名单里。
        StringBuilder names = new StringBuilder();
        int listed = 0;
        for (int k = 0; k < count; k++) {
            if (slots.get(k) != null) continue;
            if (listed == SKIPPED_NAMES_MAX) {
                names.append('\n').append(I18n.tr("queue.flow.skipped.more", skipped - listed));
                break;
            }
            if (names.length() > 0) names.append('\n');
            names.append(flowLabel(before.get(from + k)));
            listed++;
        }
        for (dev.t1m3.qplayer.audio.mix.FlowSorter.Failure failure : result.failures) {
            Logger.warn("一键排列：{} 没能分析（{}）", failure.label, failure.detail);
        }
        if (fetchedFromNetwork > 0) {
            // 如实记一笔「这次排列花了流量」：每首一次直链请求，分析只读开头与结尾那几十秒。
            Logger.info("一键排列：其中 {} 首的音源是本次现去解析出来的（每首一次直链请求；"
                    + "特征已按曲目身份进缓存，再排一次不会重复联网）",
                    Integer.valueOf(fetchedFromNetwork));
        }
        queueFlowSortSkipped.set(names.toString());
        queueFlowSortSkippedCount.set(skipped);
    }

    /**
     * 一个曲目的分析请求；拿不到音源句柄时返回一个 {@code source == null} 的请求
     * （{@code FlowSorter} 会如实把它记成「没能分析」，而不是跳过不算）。
     *
     * <p>句柄的取法与 {@link #automixSourceOf} 一致——<strong>本地缓存优先</strong>：
     * 在线曲目如果已经落到磁盘缓存里，就在本地分析，不必为了一次排序把几十条直链
     * 重新拉一遍，断网时也能排。
     */
    private dev.t1m3.qplayer.audio.mix.FlowSorter.Request flowRequestOf(Track t) {
        if (t == null) {
            return new dev.t1m3.qplayer.audio.mix.FlowSorter.Request("", null, null, "");
        }
        String handle = t.source == Track.Source.LOCAL ? t.playable() : cachedOrStreamOf(t);
        boolean local = t.source == Track.Source.LOCAL
                || (handle != null && !handle.startsWith("http"));
        return new dev.t1m3.qplayer.audio.mix.FlowSorter.Request(
                flowCacheKey(t, handle),
                handle,
                local ? null : t.streamHeaders,
                flowLabel(t));
    }

    /** 追加一个请求，拿不到句柄就返回 {@code false}（不进请求列表）。 */
    private boolean appendFlowRequest(List<dev.t1m3.qplayer.audio.mix.FlowSorter.Request> into,
                                      Track t) {
        dev.t1m3.qplayer.audio.mix.FlowSorter.Request req = flowRequestOf(t);
        if (req.source == null || req.source.isEmpty()) return false;
        into.add(req);
        return true;
    }

    /** 在线曲目的可用句柄：本地音频缓存优先，其次是手上那条直链。 */
    private String cachedOrStreamOf(Track t) {
        String cached = t.neteaseId != 0
                ? diskCache.getAudio(t.neteaseId) : diskCache.getAudio(t.canonicalId());
        if (cached != null) return cached;
        String stream = t.playable();
        return stream == null || stream.isEmpty() ? null : stream;
    }

    /**
     * 「一键排列」里现去解析一首曲目的音源（<strong>在分析线程上调用，所以可以阻塞</strong>）。
     *
     * <h3>为什么这件事必须做</h3>
     *
     * <p>在线曲目的直链<strong>只有播放过（或预取过）才存在</strong>。一个刚打开的歌单里，
     * 除了正在播的那一首，其它歌手上什么句柄都没有——于是它们全部落进「没能分析」，
     * 用户看到的就是「只能排几首，剩下全说分析不了」（2026-09-20 真机实测）。
     * 所以这里替它解析一次：与播放路径同一个 API、同一个播放档位，解析到的直链也照
     * 播放路径那样写回曲目，后面真的播到它时能省掉一次解析。
     *
     * <h3>代价，照实说</h3>
     *
     * <p>每首一次直链请求；分析只读开头与结尾那几十秒（{@code FlowAnalyzer} 的三段窗），
     * 不是整首下载。特征按<strong>曲目身份</strong>进缓存，所以同一首歌第二次排列不再联网。
     * 超时 {@value #FLOW_RESOLVE_TIMEOUT_S} 秒判死——一次解析卡住不该拖住整趟排列。
     */
    private dev.t1m3.qplayer.audio.mix.FlowSorter.Resolution resolveFlowSortSource(
            List<Track> targets, int index) {
        if (targets == null || index < 0 || index >= targets.size()) {
            return dev.t1m3.qplayer.audio.mix.FlowSorter.Resolution.failed(
                    "解析请求与曲目对不上（内部下标越界）");
        }
        Track t = targets.get(index);
        if (t == null) {
            return dev.t1m3.qplayer.audio.mix.FlowSorter.Resolution.failed("队列里没有这一首");
        }
        if (t.source == Track.Source.LOCAL) {
            String path = t.playable();
            return path == null || path.isEmpty()
                    ? dev.t1m3.qplayer.audio.mix.FlowSorter.Resolution.failed("本地曲目没有文件路径")
                    : dev.t1m3.qplayer.audio.mix.FlowSorter.Resolution.ok(path, null);
        }
        if (t.source != Track.Source.PLUGIN) {
            // 网易云直连那条老路有自己的解析入口，这里不替它编一条
            return dev.t1m3.qplayer.audio.mix.FlowSorter.Resolution.failed(
                    "这一种音源排列时不能现解析（只在播放时才有直链）");
        }
        String mediaId = t.canonicalId();
        final MediaId id;
        try {
            id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.SONG);
        } catch (IllegalArgumentException e) {
            return dev.t1m3.qplayer.audio.mix.FlowSorter.Resolution.failed(
                    "曲目标识不合法：" + mediaId);
        }
        if (!pluginHasCapability(id.provider(), ProviderCapability.RESOLVE_STREAM)) {
            return dev.t1m3.qplayer.audio.mix.FlowSorter.Resolution.failed(
                    "这个插件不支持解析直链（" + id.provider() + "）");
        }
        try {
            final StreamDescriptor stream = pluginProviders.resolveStream(id, playLevel)
                    .get(FLOW_RESOLVE_TIMEOUT_S, TimeUnit.SECONDS);
            if (stream == null || stream.url == null || stream.url.isEmpty()) {
                return dev.t1m3.qplayer.audio.mix.FlowSorter.Resolution.failed("插件没有给回直链");
            }
            // 写回曲目：**在主线程上做**——这些字段是渲染线程也会读的（换曲、预取、界面），
            // 从分析线程直接写就是一次数据竞争。分析本身用下面返回的那一份，不必等它。
            final Track target = t;
            onMain(() -> {
                target.streamUrl = stream.url;
                target.streamHeaders.clear();
                target.streamHeaders.putAll(stream.headers);
                target.streamExpiresAtMs = stream.expiresAtMs;
                target.renditionId = stream.renditionId;
                target.trial = stream.trial;
                target.streamCacheable = stream.cacheable;
            });
            return dev.t1m3.qplayer.audio.mix.FlowSorter.Resolution.ok(stream.url, stream.headers);
        } catch (java.util.concurrent.TimeoutException e) {
            return dev.t1m3.qplayer.audio.mix.FlowSorter.Resolution.failed(
                    "解析直链超时（" + FLOW_RESOLVE_TIMEOUT_S + " 秒）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return dev.t1m3.qplayer.audio.mix.FlowSorter.Resolution.failed("解析直链被中断");
        } catch (Exception e) {
            return dev.t1m3.qplayer.audio.mix.FlowSorter.Resolution.failed(
                    "解析直链失败：" + safeMessage(e));
        }
    }

    /**
     * 缓存键：稳定标识 + 文件大小 + 修改时间。
     *
     * <p>本地文件才有大小与时间（拿得到就带上，拿不到给 0）；在线直链的 URL 会过期换新，
     * 拿它当键会让缓存永远不命中，所以按<strong>曲目身份</strong>缓存——
     * 同一首在线歌曲的音频不会变。
     */
    private static String flowCacheKey(Track t, String handle) {
        long size = 0L;
        long mtime = 0L;
        if (t.source == Track.Source.LOCAL && t.filePath != null) {
            try {
                java.io.File f = new java.io.File(t.filePath);
                size = f.length();
                mtime = f.lastModified();
            } catch (RuntimeException ignored) {
                // 拿不到就退化成「只按身份缓存」：安全性由 canonicalId（含路径哈希）保证
            }
        }
        String identity = t.canonicalId();
        if (identity.isEmpty()) {
            identity = handle == null ? "" : handle;
        }
        return dev.t1m3.qplayer.audio.mix.FlowSorter.cacheKey(identity, size, mtime);
    }

    /** 给「没能分析」名单用的一行：有曲名用曲名，没有就退回艺术家/句柄，不出现空行。 */
    private static String flowLabel(Track t) {
        if (t == null) return "?";
        if (t.title != null && !t.title.isEmpty()) {
            return t.artist != null && !t.artist.isEmpty() ? t.artist + " - " + t.title : t.title;
        }
        String handle = t.playable();
        return handle == null || handle.isEmpty() ? "?" : handle;
    }

    /** 两份队列是不是<strong>同一批歌</strong>（与顺序无关）。撤销的安全闸门。 */
    private static boolean sameSongSet(List<Track> a, List<Track> b) {
        if (a.size() != b.size()) return false;
        Map<String, Integer> counts = new HashMap<>();
        for (Track t : a) {
            String id = t == null ? "" : t.canonicalId();
            Integer prev = counts.get(id);
            counts.put(id, prev == null ? 1 : prev + 1);
        }
        for (Track t : b) {
            String id = t == null ? "" : t.canonicalId();
            Integer prev = counts.get(id);
            if (prev == null || prev <= 0) return false;
            counts.put(id, prev - 1);
        }
        return true;
    }

    /**
     * 两份队列是不是<strong>逐位同一批 Track 对象</strong>（比对身份，不是内容）。
     *
     * <p>比对象身份而不是 id：删掉一首又加回同一首，内容上看不出差别，
     * 但队列的形状已经变了，而排列结果按下标写回去靠的正是那份形状。
     * 身份比对会把这种情况如实判成「变过了」。
     */
    private static boolean sameQueueContent(List<Track> a, List<Track> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
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
            showToast(I18n.tr("toast.playlist.addFailed"));
            return;
        }
        customPlaylist.add(t);
        customPlaylistTracks.set(new ArrayList<>(customPlaylist));
        showToast(I18n.tr("toast.custom.added"));
        worker.submit(this::saveCustomPlaylist);
    }

    /** The track list behind {@link #cachedSongs}, kept so {@link #playCachedSong}
     *  can re-queue it without touching the (render-thread) Property directly. */
    private final List<Track> cachedSongTracks = new ArrayList<>();

    /** Compatibility entry for a pre-plugin numeric menu row. */
    public void cacheSong(long songId) {
        showToast(I18n.tr("toast.cache.legacyEntry"));
    }

    /** Rebuild {@link #cachedSongs} from the audio cache dir + the metadata index.
     *  Called by the rail's download menu when it opens.
     *
     *  <p>这里的 I/O 比注释原先说的重得多：索引里**每一首**都要 `diskCache.hasAudio()`
     * 探测一次文件，命中之后还要最多三次封面查找（IMAGE / THUMB64 512 / THUMB64 64）。
     * 曲库大了就是渲染线程上一次 O(n) 的文件系统遍历 —— QML 是在**打开「已下载」那一帧**
     * 调它的（`AppRail.qml:202`、`Main.qml:691`），所以它直接记在页面切换上。现在整个扫描
     * 交给 cacheWorker，只有赋值回投。
     *
     *  <p>回投不只是为了 Property：循环里的 `t.coverThumbPath = …` 改的是**列表正在渲染
     * 的那批 Track 对象**，在 worker 上改就是边画边改数据。所以 worker 只负责算出
     * (Track, 封面路径) 两个平行列表，赋值留在渲染线程上做。 */
    public void refreshCachedSongs() {
        final long generation = ++cachedSongsGeneration;
        cacheWorker.execute(() -> {
            List<Track> out = new ArrayList<>();
            List<String> thumbs = new ArrayList<>();
            try {
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
                        out.add(t);
                        thumbs.add(localThumb);
                    }
                }
            } catch (Throwable e) {
                Logger.warn("refreshCachedSongs failed: {}", e.getMessage());
                return;
            }
            final List<Track> scanned = out;
            final List<String> scannedThumbs = thumbs;
            post(() -> {
                // 两次刷新（打开下载页 + 删除后重扫）会排队，晚开始的可能先跑完；
                // 用代数丢弃过期结果，否则列表会停在旧盘面上。
                if (generation != cachedSongsGeneration) return;
                for (int i = 0; i < scanned.size(); i++) {
                    String localThumb = scannedThumbs.get(i);
                    if (localThumb != null) scanned.get(i).coverThumbPath = localThumb;
                }
                cachedSongTracks.clear();
                cachedSongTracks.addAll(scanned);
                cachedSongs.set(new ArrayList<>(scanned));
            });
        });
    }

    private long cachedSongsGeneration;

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
        // QML 调进来时这是**渲染线程**，而删文件是 I/O —— 一次删除就是一次真实的磁盘操作，
        // 慢盘上足够掉帧。所以删除交给 cacheWorker（它本来就是单线程串行，两次快速删除
        // 也不会互相插队），只有列表更新与提示经 `post` 回投。
        //
        // 回投是必须的，不是讲究：`Property` 在**调用线程**上通知它的绑定，worker 直接写就
        // 是在渲染器遍历场景的同时改场景（本类 `runOnRenderThread` 的注释写明了这条规矩），
        // 而且 `cachedSongTracks` 必须只在渲染线程上改，否则和这里的遍历/移除构成竞态。
        cacheWorker.execute(() -> {
            boolean ok = diskCache.deleteAudio(songId);
            post(() -> {
                if (ok) {
                    for (int i = 0; i < cachedSongTracks.size(); i++) {
                        if (cachedSongTracks.get(i).neteaseId == songId) {
                            cachedSongTracks.remove(i);
                            break;
                        }
                    }
                    cachedSongs.set(new ArrayList<>(cachedSongTracks));
                    showToast(I18n.tr("toast.cache.removed"));
                } else {
                    showToast(I18n.tr("toast.cache.removeFailed"));
                }
            });
        });
    }

    /** Remove a netease song from the custom playlist by id (song long-press menu). */
    public void removeFromCustomPlaylist(long songId) {
        for (Track t : customPlaylist) {
            if (t.neteaseId == songId) {
                customPlaylist.remove(t);
                customPlaylistTracks.set(new ArrayList<>(customPlaylist));
                showToast(I18n.tr("toast.custom.removed"));
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
            showToast(I18n.tr("toast.custom.staleSong"));
            return;
        }
        customPlaylist.add(toTrackPlugin(song));
        customPlaylistTracks.set(new ArrayList<>(customPlaylist));
        showToast(I18n.tr("toast.custom.added"));
        worker.submit(this::saveCustomPlaylist);
    }

    public void removeMediaFromCustomPlaylist(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return;
        for (Track track : customPlaylist) {
            if (mediaId.equals(track.canonicalId())) {
                customPlaylist.remove(track);
                customPlaylistTracks.set(new ArrayList<>(customPlaylist));
                showToast(I18n.tr("toast.custom.removed"));
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
            showToast(I18n.tr("toast.playlist.addFailed"));
            return;
        }
        customPlaylist.add(found);
        customPlaylistTracks.set(new ArrayList<>(customPlaylist));
        showToast(I18n.tr("toast.custom.added"));
        worker.submit(this::saveCustomPlaylist);
    }

    /** Remove a local file from the custom playlist by path. */
    public void removeLocalFromCustomPlaylist(String filePath) {
        if (filePath == null || filePath.isEmpty()) return;
        for (Track t : customPlaylist) {
            if (t.source == Track.Source.LOCAL && filePath.equals(t.filePath)) {
                customPlaylist.remove(t);
                customPlaylistTracks.set(new ArrayList<>(customPlaylist));
                showToast(I18n.tr("toast.custom.removed"));
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
                boolean persistedAsPlugin = "PLUGIN".equals(src);
                t.source = "LOCAL".equals(src) ? Track.Source.LOCAL
                        : "CUSTOM_API".equals(src) ? Track.Source.CUSTOM_API
                        : persistedAsPlugin ? Track.Source.PLUGIN
                        : Track.Source.NETEASE;
                t.neteaseId = o.has("neteaseId") ? o.get("neteaseId").getAsLong() : 0;
                if (o.has("mediaId") && !o.get("mediaId").isJsonNull()) {
                    t.applyCanonicalId(o.get("mediaId").getAsString());
                    // See loadQueue(): a "netease" provider id is also how an
                    // installed netease plugin addresses songs; don't let
                    // applyCanonicalId() downgrade a plugin-sourced record onto the
                    // legacy built-in client.
                    if (persistedAsPlugin) t.source = Track.Source.PLUGIN;
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

    // --- Local playlists --------------------------------------------------
    //
    // A local playlist mixes songs from every source in one user-owned list. Two
    // routes put songs in it: adding a song directly, and adding a whole source
    // playlist, which is recorded as a subscription and re-pulled on open. The
    // set-arithmetic for that lives in LocalPlaylist; everything here is the QML
    // surface, the threading, and the network fetch.

    private final LocalPlaylistStore localPlaylistStore = new LocalPlaylistStore();
    /** Don't re-pull a subscription that was refreshed this recently on open.
     *  The manual refresh button ignores it. */
    private static final long SUBSCRIPTION_SYNC_COOLDOWN_MS = 10 * 60 * 1000L;
    /** Playlists with a sync in flight, so opening one twice doesn't double-fetch. */
    private final java.util.Set<String> syncingLocalPlaylists =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Render thread: republish the playlist list after any structural change. */
    private void publishLocalPlaylists() {
        List<LocalPlaylist> all = localPlaylistStore.all();
        List<Playlist> cards = new ArrayList<>(all.size());
        for (LocalPlaylist playlist : all) {
            Playlist card = new Playlist();
            card.id = playlist.id;
            card.name = playlist.name;
            card.description = playlist.description;
            card.artworkUrl = playlist.artworkUrl();
            card.coverUrl = card.artworkUrl;
            card.sourceName = I18n.tr("playlist.local.sourceName");
            card.trackCount = playlist.size();
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

    private void saveLocalPlaylists() {
        worker.submit(localPlaylistStore::save);
    }

    /** Both republish and persist, after a mutation to {@code playlistId}. */
    private void afterLocalPlaylistChange(String playlistId) {
        publishLocalPlaylists();
        if (playlistId != null && playlistId.equals(openLocalPlaylistId.peek())) {
            publishOpenLocalPlaylist();
        }
        saveLocalPlaylists();
    }

    public void createLocalPlaylist(String name) {
        LocalPlaylist created = localPlaylistStore.create(name);
        publishLocalPlaylists();
        saveLocalPlaylists();
        showToast(I18n.tr("toast.localPlaylist.created", created.name));
    }

    public void renameLocalPlaylist(String playlistId, String name) {
        if (!localPlaylistStore.rename(playlistId, name)) return;
        afterLocalPlaylistChange(playlistId);
    }

    public void deleteLocalPlaylist(String playlistId) {
        // Read the cover before the record goes, or the file is orphaned.
        LocalPlaylist doomed = localPlaylistStore.get(playlistId);
        if (!localPlaylistStore.delete(playlistId)) return;
        if (doomed != null && doomed.hasCustomCover()) {
            final String cover = doomed.coverPath;
            worker.submit(() -> deleteQuietly(cover));
        }
        if (playlistId.equals(openLocalPlaylistId.peek())) closeLocalPlaylist();
        publishLocalPlaylists();
        saveLocalPlaylists();
        showToast(I18n.tr("toast.localPlaylist.deleted"));
    }

    public void openLocalPlaylist(String playlistId) {
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

    public void closeLocalPlaylist() {
        openLocalPlaylistId.set("");
        localPlaylistTitle.set("");
        localPlaylistCover.set("");
        localPlaylistCustomCover.set(false);
        localPlaylistTracks.set(Collections.<Track>emptyList());
        localPlaylistSubscriptions.set(Collections.<PlaylistSubscription>emptyList());
        localPlaylistSyncing.set(false);
    }

    /** Menu state: whether this playlist already holds this song. */
    public boolean isInLocalPlaylist(String playlistId, String songMediaId) {
        LocalPlaylist playlist = localPlaylistStore.get(playlistId);
        return playlist != null && playlist.contains(songMediaId);
    }

    // The row menu offers the same three identities the rest of the app uses —
    // a media id, a legacy numeric netease id, and a local file path. Resolving
    // each to its canonical id here keeps that mapping out of QML.

    public boolean isSongInLocalPlaylist(String playlistId, long songId) {
        return isInLocalPlaylist(playlistId, neteaseMediaId(songId));
    }

    public boolean isLocalFileInLocalPlaylist(String playlistId, String filePath) {
        return isInLocalPlaylist(playlistId, localFileMediaId(filePath));
    }

    public void removeSongFromLocalPlaylist(String playlistId, long songId) {
        removeMediaFromLocalPlaylist(playlistId, neteaseMediaId(songId));
    }

    public void removeLocalFileFromLocalPlaylist(String playlistId, String filePath) {
        removeMediaFromLocalPlaylist(playlistId, localFileMediaId(filePath));
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
        if (songMediaId == null || songMediaId.isEmpty()) return false;
        for (LocalPlaylist playlist : localPlaylistStore.all()) {
            if (playlist.contains(songMediaId)) return true;
        }
        return false;
    }

    public void addMediaToLocalPlaylist(String playlistId, String songMediaId) {
        Track track = findTrackForMedia(songMediaId);
        if (track == null) {
            showToast(I18n.tr("toast.custom.staleSong"));
            return;
        }
        addTrackToLocalPlaylist(playlistId, track);
    }

    /** Netease compatibility entry (numeric id from a legacy row's menu). */
    public void addSongToLocalPlaylist(String playlistId, long songId) {
        Track track = findLiveTrack(songId);
        if (track == null) {
            showToast(I18n.tr("toast.playlist.addFailed"));
            return;
        }
        addTrackToLocalPlaylist(playlistId, track);
    }

    public void addLocalFileToLocalPlaylist(String playlistId, String filePath) {
        if (filePath == null || filePath.isEmpty()) return;
        Track found = null;
        for (Track candidate : library) {
            if (filePath.equals(candidate.filePath)) { found = candidate; break; }
        }
        if (found == null) {
            showToast(I18n.tr("toast.playlist.addFailed"));
            return;
        }
        addTrackToLocalPlaylist(playlistId, found);
    }

    private void addTrackToLocalPlaylist(String playlistId, Track track) {
        final PlaylistTrack entry = PlaylistTrack.from(
                track, PlaylistTrack.MANUAL, System.currentTimeMillis());
        if (!entry.valid()) {
            showToast(I18n.tr("toast.custom.staleSong"));
            return;
        }
        boolean added = localPlaylistStore.mutate(playlistId, playlist -> playlist.add(entry));
        if (!added) {
            showToast(I18n.tr("toast.localPlaylist.duplicate"));
            return;
        }
        afterLocalPlaylistChange(playlistId);
        showToast(I18n.tr("toast.custom.added"));
    }

    // One-step "new playlist + add", so the row menu can offer it without owning
    // a dialog. The playlist takes the default name, de-duplicated by the store.

    public void addMediaToNewLocalPlaylist(String songMediaId) {
        addMediaToLocalPlaylist(newLocalPlaylistId(), songMediaId);
    }

    public void addSongToNewLocalPlaylist(long songId) {
        addSongToLocalPlaylist(newLocalPlaylistId(), songId);
    }

    public void addLocalFileToNewLocalPlaylist(String filePath) {
        addLocalFileToLocalPlaylist(newLocalPlaylistId(), filePath);
    }

    public void followSourcePlaylistInNewLocalPlaylist(String sourcePlaylistId) {
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

    private String newLocalPlaylistId() {
        LocalPlaylist created = localPlaylistStore.create("");
        publishLocalPlaylists();
        return created.id;
    }

    /**
     * Remove by the row's position on screen.
     *
     * <p>That is a position in the sorted view, which is not the stored position
     * once any sort is active — so it is resolved to the song's id against the
     * published rows rather than used as an index into the stored list.
     */
    public void removeFromLocalPlaylistAt(String playlistId, int index) {
        List<Track> rows = playlistId != null && playlistId.equals(openLocalPlaylistId.peek())
                ? localPlaylistTracks.peek() : tracksOf(playlistId);
        if (rows == null || index < 0 || index >= rows.size()) return;
        removeMediaFromLocalPlaylist(playlistId, rows.get(index).canonicalId());
    }

    public void removeMediaFromLocalPlaylist(String playlistId, String songMediaId) {
        if (!localPlaylistStore.mutate(playlistId, playlist -> playlist.remove(songMediaId))) return;
        afterLocalPlaylistChange(playlistId);
        showToast(I18n.tr("toast.custom.removed"));
    }

    public void playLocalPlaylistIndex(String playlistId, int index) {
        List<Track> rows = playlistId != null && playlistId.equals(openLocalPlaylistId.peek())
                ? localPlaylistTracks.peek() : tracksOf(playlistId);
        if (rows == null || index < 0 || index >= rows.size()) return;
        playQueue(new ArrayList<>(rows), index);
    }

    public void playLocalPlaylist(String playlistId) {
        List<Track> rows = tracksOf(playlistId);
        if (rows == null || rows.isEmpty()) {
            showToast(I18n.tr("toast.localPlaylist.empty"));
            return;
        }
        playQueue(rows, 0);
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

    /** Change the sort of one playlist. Sorting is a view over the stored order,
     *  so switching back to the custom sort restores the arrangement untouched. */
    public void setLocalPlaylistSort(String playlistId, String field, boolean descending) {
        final String safe = LocalPlaylist.isSortField(field) ? field : LocalPlaylist.SORT_CUSTOM;
        boolean changed = localPlaylistStore.mutate(playlistId, playlist -> {
            if (safe.equals(playlist.sortField) && playlist.sortDescending == descending) {
                return false;
            }
            playlist.sortField = safe;
            playlist.sortDescending = descending;
            return true;
        });
        if (!changed) return;
        afterLocalPlaylistChange(playlistId);
    }

    /**
     * Move a row during a drag. Both positions are stored positions, which is the
     * same as on-screen positions because dragging is only offered in the custom
     * sort ({@link #localPlaylistReorderable}).
     *
     * <p>Deliberately does not persist: a drag emits one of these per row crossed,
     * and writing the file on each would be dozens of writes per gesture. The page
     * calls {@link #commitLocalPlaylistOrder} when the gesture ends.
     */
    /** 歌词是否铺满整宽（2.1.1 的宿主/判据会读它；这里是直通属性，渲染器用自己的版面）。 */
    public final Property<Double> lyricFullWidth = new Property<>(Double.valueOf(0.0));

    /** 插件对话框会话修订号（2.1.1 宿主按它判断"这次会话是不是新的"）。 */
    public final Property<Long> pluginDialogSessionRevision = new Property<>(Long.valueOf(0L));

    /** 我的歌单排序模式（2.1.1 有 MyPlaylistOrderStore 做持久化；这里先做成内存值）。 */
    public final Property<String> librarySortMode = new Property<>("");


    /** 确保在播（2.1.1 的安卓服务从通知/蓝牙恢复时调它）。 */
    public void ensurePlaying() {
        if (!isPlaying()) {
            play(Math.max(0, index.peek()));
        }
    }

    /** 会话状态异步落盘（2.1.1 的安卓壳在 onStop 时调它；这里同步保存并返回）。 */
    public void saveSessionStateAsync() {
        dev.t1m3.qplayer.util.Logger.info("saveSessionStateAsync：由宿主自行持久化（B 迁移中）");
    }

    /** 外部链接打开器（2.1.1 的安卓壳注入；返回"是否成功打开"）。 */
    @FunctionalInterface
    public interface AppLinkLauncher {
        boolean open(String url);
    }

    private volatile AppLinkLauncher appLinkLauncher;

    public void setAppLinkLauncher(AppLinkLauncher launcher) {
        this.appLinkLauncher = launcher;
    }

    /** 液态玻璃是否开启（用户明确不要玻璃效果 ⇒ 恒 false ✓）。 */
    public boolean liquidGlassActive() {
        return false;
    }

    /** 液态玻璃是否正在动画（同上 ⇒ 恒 false）。 */
    public boolean liquidGlassAnimating() {
        return false;
    }

    /** 液态玻璃的重力角（无玻璃 ⇒ 空实现）。 */
    public void setLiquidGlassGravityAngle(float degrees) {
    }

    /** 音频输出被拔掉（耳机断开）时暂停（2.1.1 的安卓壳监听音频路由后调它）。 */
    public void pauseForOutputLoss() {
        if (isPlaying()) {
            mediaPause();
        }
    }

    /** 当前媒体的 id（2.1.1 的安卓浮窗用它判断"歌词归属哪首"）。 */
    public final Property<String> currentMediaId = new Property<>("");

    /** 当前音源能不能新建歌单（2.1.1 的判据/QML 读它；我们默认允许本地新建）。 */
    public final Property<Boolean> canCreatePlaylist = new Property<>(Boolean.TRUE);

    public void setLibrarySortMode(String mode) {
        librarySortMode.set(mode == null ? "" : mode);
    }

    /** 我的歌单里把第 from 项挪到第 to 位（2.1.1 宿主拖拽用）。 */
    public void moveMyPlaylist(int from, int to) {
        moveLocalPlaylistCard(from, to);
    }

    /**
     * 本地歌单卡片的顺序移动（2.1.1 宿主拖拽我的歌单时调它）。
     *
     * <p><b>暂未实现排序持久化</b>：你那套歌单顺序存在 {@code moveLocalPlaylistTrack}
     * 那条"曲目移动"通路上，卡片顺序没有对应的存储。这里先如实记一条日志、不动数据
     * （不假装成功 ✗）——要真做需要把 2.1.1 的 MyPlaylistOrderStore 一起搬过来。
     */
    public void moveLocalPlaylistCard(int from, int to) {
        dev.t1m3.qplayer.util.Logger.info(
                "我的歌单卡片排序尚未实现（B 迁移中）：{} → {}", Integer.valueOf(from), Integer.valueOf(to));
    }

    public void moveLocalPlaylistTrack(String playlistId, int from, int to) {
        if (!Boolean.TRUE.equals(localPlaylistReorderable.peek())) return;
        if (!localPlaylistStore.mutate(playlistId, playlist -> playlist.move(from, to))) return;
        publishLocalPlaylists();
        if (playlistId.equals(openLocalPlaylistId.peek())) publishOpenLocalPlaylist();
    }

    /** Persist the arrangement once a drag finishes. */
    public void commitLocalPlaylistOrder(String playlistId) {
        saveLocalPlaylists();
    }

    // --- Local playlists: following a source playlist ----------------------

    /** Whether a local playlist already follows this source playlist. */
    public boolean isSourcePlaylistFollowed(String playlistId, String sourcePlaylistId) {
        LocalPlaylist playlist = localPlaylistStore.get(playlistId);
        return playlist != null && playlist.subscribed(sourcePlaylistId);
    }

    /**
     * Add a whole source playlist to a local one. The songs are pulled
     * immediately, and re-pulled whenever the local playlist is opened, so the
     * local copy follows the source.
     */
    public void followSourcePlaylist(String playlistId, String sourcePlaylistId) {
        if (!LocalPlaylist.isLocalPlaylistId(playlistId)) return;
        final MediaId id;
        try {
            id = MediaId.parse(sourcePlaylistId).requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST);
        } catch (IllegalArgumentException error) {
            showToast(I18n.tr("toast.id.playlist"));
            return;
        }
        PlaylistSubscription subscription = new PlaylistSubscription(
                id.toString(), sourcePlaylistDisplayName(id), providerDisplayName(id.provider()),
                sourcePlaylistArtwork(id));
        boolean added = localPlaylistStore.mutate(playlistId,
                playlist -> playlist.subscribe(subscription));
        if (!added) {
            showToast(I18n.tr("toast.localPlaylist.alreadyFollowed"));
            return;
        }
        afterLocalPlaylistChange(playlistId);
        showToast(I18n.tr("toast.localPlaylist.following", subscription.name));
        syncLocalPlaylist(playlistId, true);
    }

    public void unfollowSourcePlaylist(String playlistId, String sourcePlaylistId) {
        if (!localPlaylistStore.mutate(playlistId,
                playlist -> playlist.unsubscribe(sourcePlaylistId))) {
            return;
        }
        afterLocalPlaylistChange(playlistId);
        showToast(I18n.tr("toast.localPlaylist.unfollowed"));
    }

    /** Manual refresh button: ignores the cooldown. */
    public void refreshLocalPlaylist(String playlistId) {
        syncLocalPlaylist(playlistId, true);
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
        List<List<Track>> lists = java.util.Arrays.asList(
                queue, customPlaylist, cachedSongTracks, localPlaylistTracks.peek(), library);
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
        if (!LocalPlaylist.isLocalPlaylistId(playlistId)) return;
        pickPlaylistCover(playlistId);
    }

    /** Drop the chosen cover and go back to deriving one from the songs. */
    public void clearLocalPlaylistCover(String playlistId) {
        final java.util.concurrent.atomic.AtomicReference<String> removed =
                new java.util.concurrent.atomic.AtomicReference<>("");
        boolean changed = localPlaylistStore.mutate(playlistId, playlist -> {
            if (!playlist.hasCustomCover()) return false;
            removed.set(playlist.coverPath);
            playlist.coverPath = "";
            return true;
        });
        if (!changed) return;
        final String path = removed.get();
        worker.submit(() -> deleteQuietly(path));
        afterLocalPlaylistChange(playlistId);
        showToast(I18n.tr("toast.cover.cleared"));
    }

    /**
     * Worker thread: keep a copy of the picked image and point the playlist at it.
     *
     * <p>Copied rather than referenced so the cover survives the original being
     * moved or deleted, and named after the playlist so one playlist can only
     * ever own one cover file.
     */
    private void storeLocalPlaylistCover(String playlistId, byte[] data, String filename) {
        String extension = imageExtension(filename);
        java.nio.file.Path target;
        try {
            java.nio.file.Path dir = AppDirs.playlistCoversDir();
            java.nio.file.Files.createDirectories(dir);
            target = dir.resolve(MediaId.parse(playlistId).nativeId() + '.' + extension);
            java.nio.file.Files.write(target, data);
        } catch (Throwable error) {
            Logger.warn("store local playlist cover failed: {}", error.getMessage());
            showToast(I18n.tr("toast.cover.updateFailed"));
            return;
        }
        final String path = target.toAbsolutePath().toString();
        final java.util.concurrent.atomic.AtomicReference<String> previous =
                new java.util.concurrent.atomic.AtomicReference<>("");
        boolean changed = localPlaylistStore.mutate(playlistId, playlist -> {
            previous.set(playlist.coverPath);
            playlist.coverPath = path;
            return true;
        });
        if (!changed) {
            // The playlist was deleted while the picker was open.
            deleteQuietly(path);
            return;
        }
        // Replacing a .png with a .jpg leaves the old file behind otherwise.
        String old = previous.get();
        if (old != null && !old.isEmpty() && !old.equals(path)) deleteQuietly(old);
        post(() -> {
            afterLocalPlaylistChange(playlistId);
            showToast(I18n.tr("toast.cover.updated"));
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
        this.playlistFiles = bridge;
        post(() -> playlistTransferAvailable.set(bridge != null));
    }

    /** Whether QML should offer the import/export rows at all. */
    public final Property<Boolean> playlistTransferAvailable = new Property<>(false);

    public void requestLocalPlaylistExport(String playlistId) {
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
        onMain(() -> bridge.save(suggested, path -> worker.submit(() -> {
            try {
                String json = PlaylistTransfer.export(playlist, currentVersion,
                        this::providerDisplayName);
                StorageFiles.writeUtf8Atomic(java.nio.file.Paths.get(path), json);
                post(() -> showToast(I18n.tr("toast.localPlaylist.exported",
                        playlist.tracks.size())));
            } catch (Throwable error) {
                Logger.warn("playlist export failed: {}", error.getMessage());
                post(() -> showToast(I18n.tr("toast.localPlaylist.exportFailed")));
            }
        })));
    }

    public void requestLocalPlaylistImport() {
        PlaylistFileBridge bridge = playlistFiles;
        if (bridge == null) {
            showToast(I18n.tr("toast.localPlaylist.transferUnavailable"));
            return;
        }
        onMain(() -> bridge.open(path -> worker.submit(() -> {
            try {
                String json = StorageFiles.readUtf8(java.nio.file.Paths.get(path));
                LocalPlaylist parsed = PlaylistTransfer.parse(json);
                LocalPlaylist inserted = localPlaylistStore.insertImported(parsed);
                localPlaylistStore.save();
                post(() -> {
                    publishLocalPlaylists();
                    showToast(I18n.tr("toast.localPlaylist.imported",
                            inserted.name, inserted.size()));
                    // A file written on another machine carries a snapshot of its
                    // subscriptions; pull them now so the copy is current rather
                    // than however stale the export was.
                    if (!inserted.subscriptions.isEmpty()) {
                        syncLocalPlaylist(inserted.id, true);
                    }
                });
            } catch (PlaylistTransfer.UnsupportedFormat error) {
                post(() -> showToast(I18n.tr("toast.localPlaylist.importUnsupported")));
            } catch (Throwable error) {
                Logger.warn("playlist import failed: {}", error.getMessage());
                post(() -> showToast(I18n.tr("toast.localPlaylist.importFailed")));
            }
        })));
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
                post(() -> toast.set(I18n.tr("toast.local.scanFailed", e.getMessage())));
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
            showToast(I18n.tr("toast.heart.signIn"));
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
            showToast(I18n.tr("toast.heart.empty"));
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
                if (intelligencePid == 0L) throw new java.io.IOException(I18n.tr("toast.heart.noLikedPlaylist"));
                List<NeteaseSong> songs =
                        netease.intelligenceSongs(seed, intelligencePid, seed);
                fillMissingCovers(songs);
                buildSongThumbs(songs, "128");
                if (songs.isEmpty()) {
                    post(() -> {
                        intelligenceLoading.set(false);
                        showToast(I18n.tr("toast.heart.none"));
                    });
                    return;
                }
                post(() -> {
                    intelligenceLoading.set(false);
                    playSongList(songs, 0, playlistId);
                    showToast(I18n.tr("toast.heart.started"));
                });
            } catch (Throwable e) {
                Logger.warn("heart-mode recommendation failed: {}", e.getMessage());
                post(() -> {
                    intelligenceLoading.set(false);
                    showToast(I18n.tr("toast.heart.failed", safeMessage(e)));
                });
            }
        });
    }

    public void startMediaIntelligenceMode(String playlistMediaId) {
        if (!loggedIn.peek()) {
            showToast(I18n.tr("toast.heart.signIn"));
            return;
        }
        if (playlistMediaId == null || playlistMediaId.isEmpty()
                || Boolean.TRUE.equals(intelligenceLoading.peek())) return;
        final MediaId playlistId;
        try {
            playlistId = MediaId.parse(playlistMediaId).requireKind(
                    dev.t1m3.qplayer.media.MediaKind.PLAYLIST);
        } catch (IllegalArgumentException error) {
            showToast(I18n.tr("toast.id.playlist"));
            return;
        }
        if (!pluginHasCapability(playlistId.provider(), ProviderCapability.HEART_RECOMMENDATION)) {
            showToast(I18n.tr("toast.heart.unsupported"));
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
            showToast(I18n.tr("toast.heart.empty"));
            return;
        }
        intelligenceLoading.set(true);
        final MediaId selectedSeed = seed;
        pluginProviders.heartRecommendations(selectedSeed, playlistId, 100)
                .whenComplete((songs, error) -> post(() -> {
                    intelligenceLoading.set(false);
                    if (error != null) {
                        showToast(I18n.tr("toast.heart.failed", safeMessage(error)));
                    } else if (songs == null || songs.isEmpty()) {
                        showToast(I18n.tr("toast.heart.none"));
                    } else {
                        playPluginSongList(songs, 0, playlistMediaId);
                        showToast(I18n.tr("toast.heart.started"));
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
                    showToast(I18n.tr("toast.playlist.playFailed"));
                    return;
                }
                songs = new ArrayList<>(cached.songs.size());
                for (NeteaseSong song : cached.songs) songs.add(withLocalThumb(song));
            }
            if (songs.isEmpty()) {
                showToast(I18n.tr("toast.playlist.empty"));
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
        catch (IllegalArgumentException error) { showToast(I18n.tr("toast.id.playlist")); return; }
        pluginProviders.playlist(id).whenComplete((playlist, error) -> post(() -> {
            Playlist resolved = playlist;
            if (error != null || resolved == null) {
                resolved = mediaPlaylistCacheIndex.get(id.toString());
                if (resolved == null) {
                    showToast(I18n.tr("toast.playlist.playFailedReason", safeMessage(error)));
                    return;
                }
            }
            if (resolved.songs.isEmpty()) {
                showToast(I18n.tr("toast.playlist.empty"));
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
            showToast(I18n.tr("toast.mediaIdCopied"));
        } else {
            showToast(mediaId);
        }
    }

    public void shareMedia(String mediaId) {
        final MediaId id;
        try { id = MediaId.parse(mediaId); }
        catch (IllegalArgumentException error) { showToast(I18n.tr("toast.id.media")); return; }
        if (!pluginHasCapability(id.provider(), ProviderCapability.SHARE)) {
            copyMediaReference(mediaId);
            return;
        }
        pluginProviders.share(id).whenComplete((url, error) -> post(() -> {
            if (error != null || url == null || url.isEmpty()) {
                showToast(I18n.tr("toast.share.failed", safeMessage(error)));
                return;
            }
            java.util.function.Consumer<String> sink = clipboard;
            if (sink != null) {
                sink.accept(url);
                showToast(I18n.tr("toast.linkCopied"));
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
        // Checked before the queue is replaced: a rejected selection must leave the
        // queue exactly as the plugin driving playback last set it.
        if (q != null && start >= 0 && start < q.size() && blockedByPluginSession(q.get(start))) {
            return;
        }
        currentQueuePlaylistId = sourcePlaylistId;
        if (sourcePlaylistId != 0L) currentQueueMediaPlaylistId = "";
        queue.clear();
        shuffleHistory.clear();
        playIndex = -1;
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
        scrobbleTrack(queue.get(playIndex), Math.max(0L, backend.audiblePosition()), naturalEnd);
    }

    /**
     * 报一首歌「放过」，{@code playedMs} 由调用方给。
     *
     * <p>为什么要带进来而不是自己去读 {@code backend.audiblePosition()}：自动混音的过渡窗口里
     * 引擎报的位置已经是<strong>进场曲</strong>的（界面此刻也在说它），拿它去报出场曲
     * 会把「放了多久」算成几秒——出场曲的收尾因此要么报错、要么被那个 3 秒门槛丢掉。
     * 所以「谁 + 放了多久」必须由知道上下文的那一方给。
     */
    private void scrobbleTrack(Track t, long playedMs, boolean naturalEnd) {
        if (t == null) return;
        if (t.source == Track.Source.PLUGIN && t.mediaId != null) {
            long played = Math.max(0L, playedMs);
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
        long seconds = Math.max(0L, playedMs) / 1000L;
        if (seconds < 3) return;
        long songId = t.neteaseId;
        String end = naturalEnd ? "playend" : "ui";
        worker.submit(() -> netease.scrobble(songId, 0L, seconds, end));
    }

    private void playAt(int i) {
        if (i < 0 || i >= queue.size()) return;
        if (blockedByPluginSession(queue.get(i))) return;
        // 一次真正的换曲压过「过渡开始时那一次界面推进」：那一次不再等交接
        // （用户点了别的歌，引擎那边会因为这次 play 结束掉旧会话）。
        forgetAdoptedHandover();
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
        rememberShufflePredecessor(i);
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
        // placeholders) and start the progress bars' loading sweep. The loaders below
        // re-apply the real lyrics/cover in the same render-queue drain (no flash for
        // instant sources); the backend's onStarted ends the sweep once playback
        // actually begins.
        // 换曲就是硬停：从前的「先把音量渐变到 0 再暂停」那条（settings.fade）已经删掉，
        // 它会在自动混音的过渡窗口里把整条混音（含正在进场的下一首）一起拉到 0。
        backend.pause();
        post(() -> {
            loading.set(true);
            applyLyrics(Collections.<LyricLine>emptyList());
            applyCover(null, currentCoverRevision);
            coverPath.set("");
        });
        worker.submit(this::saveQueue);
        final int idx = i;
        final Track t = queue.get(i);
        publishNowPlaying(t, idx, resumeMs, currentCoverRevision);

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
                    showToast(I18n.tr("toast.play.missingPlugin"));
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
                loadNeteaseLyrics(t);
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
                loadNeteaseLyrics(t);
                // Populate the disk cache so the next play is local (skip trial clips).
                cacheAudioAsync(t);
            } else {
                resolveAndPlayNetease(t, i, resumeMs, currentCoverRevision);
            }
        } else if (t.source == Track.Source.CUSTOM_API) {
            skipUnplayable(i, I18n.tr("toast.play.legacySourceGone"));
        } else if (t.source == Track.Source.PLUGIN) {
            String cached = diskCache.getAudio(t.canonicalId());
            if (cached != null) {
                Logger.info("play plugin {} (audio cache): {}", providerOf(t), t.title);
                playBackend(cached, resumeMs);
                playingIntent = true;
                post(() -> playing.set(true));
                notifyPlayback();
                loadPluginLyrics(t);
            } else if (hasFreshPluginStream(t)) {
                Logger.info("play plugin {} (cached url): {}", providerOf(t), t.title);
                playBackend(t.playable(), t.streamHeaders, resumeMs);
                playingIntent = true;
                post(() -> playing.set(true));
                notifyPlayback();
                loadPluginLyrics(t);
                cachePluginAudioAfterStart(t);
            } else {
                t.streamUrl = null;
                t.streamHeaders.clear();
                resolveAndPlayPlugin(t, i, resumeMs, currentCoverRevision);
            }
        }
        // Current track's fetches are now queued; warm next/prev behind them.
        preloadAdjacent();
        // 自动混音的前提：把「下一首」预告给后端。没有这一步，AI 模式与声波重合
        // 永远不会触发——引擎的 Director.prepare 拿不到下一首，就只剩交叉淡化。
        armNextSourceForAutomix();
        // 上面那一次常常预告不出来（在线的「下一首」此刻既没解析过直链、也没落盘），
        // 所以顺手把它的直链解析提前做掉；解析成功/音频落盘之后各有一次重算。
        prefetchNextForAutomix();
    }

    /**
     * 把「现在这一首」发布到界面（标题/歌手/专辑/封面/时长/位置/点赞态）。
     *
     * <p>抽出来是为了让<strong>两条</strong>路共用同一份「界面显示什么」：
     * {@link #playAt(int)}（一次真正的换曲）与 {@link #adoptIncomingTrack}
     * （自动混音的过渡已经开始、进场曲在出声，界面跟着切过去）。两处各写一份的话，
     * 迟早会出现「自动混音切过去时少了一个字段」这种只在过渡后看得见的差异。
     *
     * <p>它<strong>只碰界面</strong>：既不动后端、也不动 {@code playIndex}/队列下标
     * （那是调用方的责任），所以可以安全地在「后端已经在放这一首」的时刻调用。
     *
     * @param positionMsValue 这一首此刻的位置（换曲时是恢复点，过渡接管时是引擎报的位置）
     */
    private void publishNowPlaying(final Track t, final int idx, long positionMsValue,
                                   long coverRevisionValue) {
        final long position = positionMsValue;
        final long coverRev = coverRevisionValue;
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
            positionMs.set(position);
            String canonical = t.canonicalId();
            boolean pluginLikeable = t.source == Track.Source.PLUGIN
                    && providerForMediaHas(canonical, ProviderCapability.LIKE);
            currentLiked.set(pluginLikeable ? pluginLikedSet.contains(canonical)
                    : t.neteaseId != 0 && likedSet.contains(t.neteaseId));
            currentLikeable.set(pluginLikeable || t.neteaseId != 0);
        });
        updateCover(t, coverRev);
    }

    /**
     * 把「下一首」的音源句柄预告给后端（{@link AudioBackend#setNextSource}）。
     *
     * <p>为什么这件事只能在控制器里做：推进队列一直是控制器的活（见
     * {@link AudioBackend} 的类注释），后端只看得到「现在放这一首」。而自动混音
     * （AI 模式 / 声波重合）要在当前曲结束<strong>之前</strong>就开始准备——对拍与
     * 判调性要提前 30 秒以上动手，引擎那边靠的就是这份预告。
     *
     * <p><strong>这件事要算好几次，不是一次</strong>（每一处都对应一个真实时刻）：
     *
     * <ul>
     *   <li>{@code playAt} 末尾：换曲的入口，能预告的（本地文件、已缓存的在线歌）当场就预告；</li>
     *   <li>直链解析成功之后（{@code resolveAndPlayPlugin} 与
     *       {@link #prefetchNextForAutomix} 的回调）：在线曲目的句柄到这一刻才存在；</li>
     *   <li>音频缓存落盘之后（两个缓存回调）：本地文件不过期、不需要请求头，
     *       是对拍分析最可靠的那一种句柄；</li>
     *   <li>当前曲<strong>真的开始出声</strong>之后（{@code backend.setOnStarted}）：
     *       到这一刻才确定「在放的就是这一首」，也是预热下一首最不容易白做的时机。</li>
     * </ul>
     *
     * <p>拿不到句柄时<strong>如实放弃</strong>，并在日志里说清是哪一种拿不到——
     * 判据与原因都在 {@link #automixArmHandle}（它和播放路径用同一套取法）。
     *
     * <p>单曲循环（{@code playMode == 2}）预告的就是当前这一首：引擎据此把句尾
     * 无缝接回句首。起点一律 0——只有会话恢复那条路会带位置，而那里不预告。
     */
    /**
     * 音源字符串（直链或本地路径）→ <b>那首歌</b>。引擎问歌词时按它反查。
     *
     * <p>给自动点位第 8 项「跳跃后第一句歌词来得是不是时候」用：引擎准备过渡时手上只有
     * 路径/直链，所以这里按**引擎看到的那个字符串**登记，它问什么就答什么。
     *
     * <p><b>只登记"句柄 → 曲目"，不登记歌词本身</b>（2026-10-04 修）：原来这里是
     * 「句柄 → 歌词行时间的快照」，在 arm 那一刻**同步**读 {@code lyricMem}。
     * 可歌词是**异步预取**的（{@link #preloadTrack} 把 {@code fetchNeteaseLyrics} 丢给
     * worker 线程），arm 经常跑在它前面——于是**下一首**那侧永远登记不到歌词，
     * 而 {@code AutoCue.lineGapTerm} 要求两侧都有，结果整项静默不参与。
     * 用户实测的正是这个：「他切的点刚好就是歌词正在念的时候，我测的所有歌曲都这样」。
     * 现在查询时才去 {@code lyricMem} / {@code pluginLyricMem} 取，预取早就落下来了。
     */
    private final Map<String, Track> automixTrackByHandle =
            Collections.synchronizedMap(new LinkedHashMap<String, Track>());

    /** 宿主注入只做一次（放在第一次登记时，省得改构造器）。 */
    private boolean lineSourceInstalled;

    /**
     * 把这首歌的歌词行时间登记到它交给引擎的那个句柄上。
     *
     * <p>没有歌词就什么都不登记——引擎那侧查不到就是「这一项不加」，
     * 而不是「给 0 分」（见 {@code AutoCue.lineGapTerm}）。
     *
     * <p><b>已知的不覆盖</b>：同一首在线曲的句柄分两步到位（先直链、后缓存文件），
     * 而这里只登记当下这一个。于是「deck 手里那个字符串恰好不是这一个」时，出场侧
     * 拿不到歌词、第 8 项不加——只少了收益，不会有任何错误行为。
     */
    private void registerAutomixLyrics(Track track, String source) {
        if (track == null || source == null || source.isEmpty()) return;
        automixTrackByHandle.put(source, track);
        if (!lineSourceInstalled) {
            lineSourceInstalled = true;
            AutoCue.installLyricSpanSource(this::lyricSpansByHandle_lookup);
            Logger.info("自动点位：歌词节奏项已接通（句柄反查曲目 → 行起止时间，含结束时间）");
        }
    }

    /** {@link #automixTrackByHandle} 的查询口（引擎侧回调到这里）。 */
    private AutoCue.LyricSpans lyricSpansByHandle_lookup(String source) {
        if (source == null) return null;
        Track track = automixTrackByHandle.get(source);
        if (track == null) return null;
        return lineSpansOf(track);
    }

    /**
     * 这首歌此刻的歌词行起始秒；没有（还没取到 / 这首歌没词）就返回 {@code null}。
     *
     * <p><b>每次现取</b>，不缓存快照：引擎在准备过渡时问几次，而这几次之间预取可能刚好落地
     * （见 {@link #automixTrackByHandle} 的注释）。构建一个几十上百个 double 的数组是纳秒级的。
     */
    private AutoCue.LyricSpans lineSpansOf(Track track) {
        List<LyricLine> lines = null;
        if (track.source == Track.Source.NETEASE && track.neteaseId != 0) {
            lines = lyricMem.get(Long.valueOf(track.neteaseId));
        } else if (track.source == Track.Source.PLUGIN) {
            lines = pluginLyricMem.get(track.canonicalId());
        }
        if (lines == null || lines.isEmpty()) return null;
        double[] starts = new double[lines.size()];
        double[] ends = new double[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            starts[i] = (double) lines.get(i).startMs() / 1000.0;
            // **真实结束时间**：自动点位要靠它判「融合中点是不是落在某一句正在唱的时候」
            // （只给起始时间的话只能靠槽位近似，而那个近似会和"节奏"那条规则互相抵消）。
            double end = (double) lines.get(i).endMs() / 1000.0;
            ends[i] = end > starts[i] ? end : starts[i];
        }
        return new AutoCue.LyricSpans(starts, ends);
    }

    private void armNextSourceForAutomix() {
        // 无论从哪一条分支出去，都要重算一次「该钉住哪些音频」
        // （当前曲那一份不能被淘汰掉，预告出去的那一份必须活到引擎去打开它）。
        try {
        // 总开关关着时**照样预告**，只是预告的用途变了——这一点值得写清楚：
        //
        // 预告 ≠ 过渡。引擎那边「总开关关着」= 窗口 0（见 AutomixSettings），
        // 于是它既不 prepare（一次分析都不跑）也不进过渡窗口，这份预告只用来
        // **把下一首接在同一个输出流里**（曲终无缝换曲，ADR-003 §2 的前提）。
        // 反过来「关掉就不预告」会把这条无缝换曲的路一起关掉：曲终引擎只能结束会话、
        // 由控制器重新开一次播放，接缝上多出一小段空隙——那是另一个功能被顺手拿走了。
        //
        // 「关了还在跑」由引擎那道闸门挡住：开关一改，下一次预告会让它重建编排器，
        // 新的窗口就是 0，当前这一曲的结尾也不会再做交叠（见 PlaybackEngine.handleArmNext）。
        boolean off = !automixEnabled;
        // 单曲循环时「下一首」就是这一首：队列只有一首也照样要预告——引擎据此
        // 把句尾无缝接回句首（这条注释一直这么写着，而下面那个 size 检查以前
        // 恰好把「队列里只有一首 + 单曲循环」这个最需要它的组合挡掉了）。
        boolean repeatOne = playMode.peek() == 2;
        if (queue.size() <= 1 && !repeatOne) {
            Logger.info("自动混音：队列里只有 {} 首 → 没有下一首可预告（曲终换曲由控制器接手）",
                    Integer.valueOf(queue.size()));
            backend.clearNextSource();
            return;
        }
        if (playMode.peek() == 1) {
            // 下一首由 RNG 决定，这里再摇一次必然是另一个下标——预告错了会让引擎
            // 把一首不该放的曲子接上来，比不做过渡更糟。
            Logger.info("自动混音：随机播放 → 下一首此刻未定，不预告");
            backend.clearNextSource();
            return;
        }
        int cur = playIndex;
        if (cur < 0 || cur >= queue.size()) {
            backend.clearNextSource();
            return;
        }
        int nextIndex = repeatOne ? cur : (cur + 1) % queue.size();
        // 出场侧（当前曲）与进场侧（下一首）都登记一次：引擎准备过渡时按它手上的句柄来问。
        registerAutomixLyrics(queue.get(cur), automixArmHandle(queue.get(cur)).handle);
        ArmHandle arm = automixArmHandle(queue.get(nextIndex));
        registerAutomixLyrics(queue.get(nextIndex), arm.handle);
        if (arm.handle == null || arm.handle.isEmpty()) {
            // 如实说清是哪一种「拿不到句柄」——这一条以前是**静默**的，用户只能听出
            // 「自动混音没效果」，而看不出原因。在线源的直链是异步解析出来的、音频是
            // 播放后才落盘的，所以「下一首」这一侧常常什么都还没有：那一次换曲只能是硬接。
            Logger.info("自动混音：下一首「{}」拿不到句柄 → 不预告（{}；{} 源）。"
                            + "这一次换曲没有过渡，曲终由控制器接手换曲（接缝上会有一小段空隙）",
                    flowLabel(queue.get(nextIndex)), arm.why,
                    sourceLabel(queue.get(nextIndex)));
            automixArmedNext = null;
            automixArmedHandle = "";
            backend.clearNextSource();
        } else if (off) {
            Logger.info("自动混音：总开关关闭 → 不做过渡（无交叠、无分析）· 下一首「{}」仍然预告给引擎，"
                            + "只为曲终无缝换曲；要真的做过渡请在设置里打开总开关",
                    flowLabel(queue.get(nextIndex)));
            automixArmedNext = queue.get(nextIndex);
            automixArmedHandle = arm.handle;
            backend.setNextSource(arm.handle, arm.headers, 0L);
        } else if (isHandleUpgradeForSameTrack(automixArmedNext, automixArmedHandle,
                arm.handle, queue.get(nextIndex))) {
            // **同一首歌的另一个句柄不算「换了下一首」**（2026-09-25 实测报告：
            // 「生成式过渡准备好了，但是没过渡，A 完全走完走的 B」）。
            //
            // 在线源的句柄是分两步到位的：先解析出直链，音频落盘之后再换成缓存文件——
            // 日志里就是 20:59:40 预告直链、20:59:46 预告缓存文件。而引擎那边的
            // 「换队首」会把**已经备好的过渡整份作废**（`Director.replaceQueued` →
            // `resetPreparation`）。生成式那一档一次准备要 70 秒，一次句柄升级就够让它
            // 赶不上：引擎在触发点手上什么都没有，于是这一轮不开始过渡，
            // A 一路播到底、曲终由控制器硬切到 B。
            //
            // 句柄升级对引擎没有价值：那首曲子的 deck 在准备时就打开好了，
            // 句柄只影响「以后再打开它」。所以这里如实记一句，然后什么都不做。
            Logger.info("自动混音：下一首「{}」换了句柄（{} → {}）但**还是同一首** → "
                            + "不重新预告，保住引擎那边已经备好的过渡",
                    flowLabel(queue.get(nextIndex)), automixArmedHandle, arm.handle);
            automixArmedNext = queue.get(nextIndex);
            automixArmedHandle = arm.handle;
        } else {
            Logger.info("自动混音：已预告下一首「{}」→ {}",
                    flowLabel(queue.get(nextIndex)), arm.handle);
            automixArmedNext = queue.get(nextIndex);
            automixArmedHandle = arm.handle;
            backend.setNextSource(arm.handle, arm.headers, 0L);
        }
        } finally {
            refreshAutomixAudioPins();
        }
    }

    /**
     * 这次句柄变化是不是「同一首歌换了个句柄」。
     *
     * <p>是的话<b>不重新预告</b>：引擎收到「换队首」会把已经备好的过渡整份作废，
     * 而生成式那一档一次准备要 70 秒——在线源的「直链 → 本地缓存」这一步升级就足以
     * 让它赶不上（用户实测：A 播完直接硬接 B）。
     *
     * <p>判据是<b>曲目的稳定身份</b>（{@link Track#canonicalId()}），不是句柄字符串：
     * 同一首歌的两个句柄（直链 / 缓存文件）本来就是两回事。
     *
     * @param armed       上一次预告的那一首（没有就传 {@code null}）
     * @param armedHandle 上一次预告的句柄
     * @param newHandle   这次拿到的句柄
     * @param next        这次要预告的那一首
     */
    static boolean isHandleUpgradeForSameTrack(Track armed, String armedHandle, String newHandle,
                                               Track next) {
        if (armed == null || next == null || armedHandle == null || armedHandle.isEmpty()
                || newHandle == null || newHandle.isEmpty() || newHandle.equals(armedHandle)) {
            return false;
        }
        String before = armed.canonicalId();
        String after = next.canonicalId();
        return !before.isEmpty() && before.equals(after);
    }

    /** 正在为自动混音预解析「下一首」的那个 mediaId（空 = 没有在飞）。只在主线程读写。 */
    private String automixPrefetchMediaId = "";

    /** 正在为自动混音预取音频的那一首（null = 没有在预取）。只在主线程读写。 */
    private Track automixPrefetchingNext;

    /** 已经预告给引擎的「下一首」（null = 当前没有预告）。只在主线程读写。 */
    private Track automixArmedNext;

    /**
     * 上面那一首交给引擎的<strong>句柄原文</strong>（{@link AudioBackend#setNextSource} 收的
     * 就是它）。只在主线程读写。
     *
     * <p>过渡开始时引擎报上来的进场曲句柄要与它逐字相等，我们才认这是「自己预告出去的那一次
     * 过渡」并推进界面：句柄是在预告那一刻定下的（缓存优先/直链），不能再算一遍——
     * 期间音频落盘、直链过期都会让同一条路径算出另一个字符串。
     */
    private String automixArmedHandle = "";

    /**
     * 过渡开始时<strong>已经把界面推进过去</strong>的那一首（null = 没有待交接的推进）。
     * 只在主线程读写。
     *
     * <p>它与 {@link #automixAdoptedHandle} 一起构成「恰好一次推进」的凭据：曲终那次
     * {@code onComplete} 到达时，只有「引擎现在放的正是这一首」才说明那一次推进已经被交接掉、
     * 队列不许再往前走第二下（见 {@link #consumeAdoptedHandover()}）。
     */
    private Track automixAdoptedNext;

    /**
     * 过渡开始那一刻引擎报上来的进场曲句柄（= 交给引擎的那一份），用于与
     * {@link AudioBackend#currentSource()} 核对这次 {@code onComplete} 是不是它的交接。
     */
    private String automixAdoptedHandle = "";

    /** 被推进过去的这一首「顶掉」的出场曲（曲终时补它的 scrobble 用）。 */
    private Track automixAdoptedFrom;

    /** 当前被自动混音临时钉住的 mediaId（见 {@link #refreshAutomixAudioPins}）。只在主线程读写。 */
    private final java.util.Set<String> automixPinnedMediaIds = new java.util.LinkedHashSet<>();

    /**
     * 这个音源的音频会不会落在 {@code cache/audio} 里（本地文件不走那条缓存，钉它没有意义）。
     */
    private static boolean cachesAudioToDisk(Track t) {
        return t != null && (t.source == Track.Source.NETEASE || t.source == Track.Source.PLUGIN);
    }

    /**
     * 重算「哪些音频文件要临时钉住」，把增删落到 {@link DiskCache#pinAudio} 上。
     *
     * <h3>为什么要钉</h3>
     *
     * <p>{@code DiskCache.evictIfNeeded} 对<strong>未受保护</strong>的音频是「最新优先」淘汰
     * （上游刻意的设计：连着跳过几十首留下的缓存没价值）。而在缓存已经满了的机器上，
     * 它的后果是自毁——刚下载完的那一份就是最新的一份，于是它把自己删掉。
     * 实测过：用户的缓存卡在 200 MB 上限，每首歌起播后约 7 秒落盘、随即被删，
     * 5 首歌的文件一个都没留住。
     *
     * <p>这对自动混音是致命的：引擎要在曲终前三十几秒拿到「下一首」的句柄，
     * 那份文件必须活到那一刻。对普通播放也是坏的：正在听的那一首会在播放中途从缓存里消失。
     *
     * <p>要钉的只有两种，而且都是<strong>临时</strong>的（{@code pinAudio} 不落盘，
     * 进程重启即失效——不像「缓存此歌曲」那样写进 {@code actively-cached.txt} 永远留一份，
     * 那会反过来把用户真想离线听的东西挤出去）：
     *
     * <ol>
     *   <li>正在听的那一首；</li>
     *   <li>已经取到句柄、并且已经/正在为它预取音频的「下一首」。</li>
     * </ol>
     *
     * <p>重算而不是逐个记账：换曲、改队列、拨开关、换模式都会让「该钉谁」变一次，
     * 一处算清楚比在七八个地方各删一次可靠。
     */
    private void refreshAutomixAudioPins() {
        java.util.Set<String> want = new java.util.LinkedHashSet<>();
        Track current = currentTrack();
        if (cachesAudioToDisk(current)) {
            String id = current.canonicalId();
            if (!id.isEmpty()) want.add(id);
        }
        if (cachesAudioToDisk(automixArmedNext)) {
            String id = automixArmedNext.canonicalId();
            if (!id.isEmpty()) want.add(id);
        }
        if (cachesAudioToDisk(automixPrefetchingNext)) {
            String id = automixPrefetchingNext.canonicalId();
            if (!id.isEmpty()) want.add(id);
        }
        // 先加后减：一个 id 同时是「当前曲」和「下一首」时不会被自己撤掉
        for (String id : want) {
            if (automixPinnedMediaIds.add(id)) {
                diskCache.pinAudio(id);
            }
        }
        java.util.Iterator<String> it = automixPinnedMediaIds.iterator();
        while (it.hasNext()) {
            String id = it.next();
            if (!want.contains(id)) {
                it.remove();
                diskCache.unpinAudio(id);
            }
        }
    }

    /**
     * 为自动混音<strong>预热「下一首」</strong>：把它的直链解析出来，
     * 插件允许时顺带把音频拉到本地缓存。
     *
     * <h3>为什么必须有这一步（本次修的核心）</h3>
     *
     * <p>在线的「下一首」在点歌那一刻<strong>什么都没有</strong>：直链是异步解析出来的
     * （而且只解析**当前**这一首），音频缓存是播放之后才落的。于是
     * {@code armNextSourceForAutomix} 每次都拿不到句柄，引擎那边队列为空、
     * 一次过渡都不做——用户能听到的只有「自动混音没效果」。
     * {@code preloadAdjacent}/{@code preloadTrack} 也帮不上忙：它们只预热歌词与封面，
     * 既不解直链也不缓存音频。
     *
     * <h3>为什么解析出来还不算完</h3>
     *
     * <p>解析成功只是一条 http 直链：它<strong>有时效</strong>，而且引擎要读它的尾部
     * 来做对拍/判调性。所以这里顺手把音频拉到本地缓存（复用播放路径自己那套
     * {@link #cachePluginAudioAsync}）：本地文件不过期、不需要请求头，分析读尾部也更可靠。
     * 落盘之后 {@link #armNextSourceForAutomix} 会自动改用缓存那份（缓存优先）。
     *
     * <h3>什么情况下什么都不做</h3>
     *
     * <ul>
     *   <li><strong>总开关关着</strong>：关掉就是不做任何准备——不为一个关着的功能
     *       花用户的带宽与电。</li>
     *   <li>队列只有一首 / 随机播放 / 本地文件 / 已经在盘上 / 已经有新鲜直链 /
     *       同一首已经在飞：都是「不需要预热」。</li>
     *   <li>插件不支持解析直链：如实记一条（预告只能等它落到本地缓存）。</li>
     * </ul>
     *
     * <p>试听片段（{@code trial}）与非缓存许可的流只用来预告、<strong>不落盘</strong>——
     * 落盘那一步的口径与 {@link #cachePluginAudioAsync} 完全一致（它自己会再判一次）。
     */
    private void prefetchNextForAutomix() {
        // 门槛只有一条：**没有下一首就别忙**（下面各分支逐条说清）。
        //
        // 从前这里写着「只有真的会做过渡时才预取」——开关关着、或者接法是无缝拼接就整个不做。
        // 那漏掉了另一半：**预告同时承担「曲终无缝换曲」**，而 armNextSourceForAutomix 的
        // 注释自己也写着「开关关着时照样预告，这份预告只用来把下一首接在同一个输出流里」。
        // 可要预告就得先有句柄，而流式曲目（安卓上整队都是）**只有解析过直链才有**——
        // 于是那条承诺在真机上根本没兑现：每次换曲都是硬接，接缝上一小段空隙
        // （2026-09-20 安卓实机日志就是一串「拿不到句柄 → 不预告」）。
        //
        // 所以：**解析（一次直链请求，几 KB）不再看开关与接法**；真正花带宽的那一步
        // （音频落盘）仍然只在「真的会做过渡」时才做，见下面 automixTransitionRequested。
        // 单曲循环时「下一首」就是这一首：队列只有一首也照样要预热——引擎据此把句尾
        // 无缝接回句首（本地文件能直接接，在线曲目至少先把直链拿到）。
        boolean repeatOne = playMode.peek() == 2;
        if (queue.size() <= 1 && !repeatOne) return;
        if (playMode.peek() == 1) return;
        int cur = playIndex;
        if (cur < 0 || cur >= queue.size()) return;
        int nextIndex = repeatOne ? cur : (cur + 1) % queue.size();
        Track next = queue.get(nextIndex);
        if (next == null) return;
        // 直链这一套只有插件源有（NETEASE 源是历史遗留的直连客户端，走它自己的解析路径）
        if (next.source != Track.Source.PLUGIN) return;
        String mediaId = next.canonicalId();
        if (mediaId.isEmpty()) return;
        if (diskCache.hasAudio(mediaId) || hasFreshPluginStream(next)) {
            automixPrefetchMediaId = "";   // 已经够用了（也许上一次预热刚成功）
            return;
        }
        if (mediaId.equals(automixPrefetchMediaId)) return;   // 同一首已经在飞，别重复请求

        final MediaId id;
        try {
            id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.SONG);
        } catch (IllegalArgumentException error) {
            return;
        }
        if (!pluginHasCapability(id.provider(), ProviderCapability.RESOLVE_STREAM)) {
            Logger.info("自动混音：下一首「{}」所在的插件不支持解析直链（{}）→ 只能等它的音频落到本地缓存",
                    flowLabel(next), id.provider());
            return;
        }
        automixPrefetchMediaId = mediaId;
        // 预取期间就把它钉住：音频写进缓存那一刻，淘汰扫描（未保护的「最新优先」）
        // 会把它当成第一个候选删掉——钉晚了等于没钉。
        automixPrefetchingNext = next;
        refreshAutomixAudioPins();
        Logger.info(automixTransitionRequested
                        ? "自动混音：为下一首「{}」预解析直链（过渡要提前 30 秒以上拿到句柄）"
                        : "自动混音：为下一首「{}」预解析直链（当前接法是无缝拼接："
                                + "这份预告只为曲终的无缝换曲，不做任何分析、也不落盘）",
                flowLabel(next));
        pluginProviders.resolveStream(id, playLevel).whenComplete((stream, error) -> onMain(() -> {
            if (mediaId.equals(automixPrefetchMediaId)) automixPrefetchMediaId = "";
            if (error != null || stream == null || stream.url == null || stream.url.isEmpty()) {
                Logger.warn("自动混音：下一首「{}」的直链预解析失败（{}）→ 只能等它的音频落到本地缓存",
                        flowLabel(next), error != null ? safeMessage(error) : "空 URL");
                automixPrefetchingNext = null;
                refreshAutomixAudioPins();
                return;
            }
            // 与 resolveAndPlayPlugin 写回的字段一模一样：这一首随后真的被播放时可以直接用，
            // 省掉一次解析（它自己也会用 hasFreshPluginStream 认出这条还新鲜）。
            next.streamUrl = stream.url;
            next.streamHeaders.clear();
            next.streamHeaders.putAll(stream.headers);
            next.streamExpiresAtMs = stream.expiresAtMs;
            next.renditionId = stream.renditionId;
            next.trial = stream.trial;
            next.streamCacheable = stream.cacheable;
            Logger.info("自动混音：下一首「{}」的直链已就绪（时效 {}，请求头 {} 项）",
                    flowLabel(next),
                    stream.expiresAtMs > 0L ? "剩余 " + Math.max(0L, stream.expiresAtMs - System.currentTimeMillis()) + " ms" : "未声明",
                    Integer.valueOf(next.streamHeaders.size()));
            // 句柄到手：立刻（重）算预告，别等下一次换曲
            armNextSourceForAutomix();
            if (!stream.trial && stream.cacheable && automixTransitionRequested) {
                // 顺手落盘：本地文件不过期、不需要请求头，对拍分析读尾部也更可靠。
                // **这一步才是真正花带宽的**（整首下到磁盘），所以它仍然只在
                // 「真的会做过渡」时才做——接法是无缝拼接时，曲终交接只需要上面那条直链。
                cachePluginAudioAsync(next, false, null);
            } else {
                // 不落盘（试听片段 / 未授权缓存 / 当前不做过渡）：预取到此结束，
                // 钉子由 arm 那一路接管
                automixPrefetchingNext = null;
                refreshAutomixAudioPins();
            }
        }));
    }

    /** 音源类别的一句话（日志用；不缩写，免得看不出是本地还是在线）。 */
    private static String sourceLabel(Track t) {
        if (t == null || t.source == null) return "未知";
        switch (t.source) {
            case LOCAL: return "本地文件";
            case NETEASE: return "网易云";
            case PLUGIN: return "插件";
            default: return "自定义接口";
        }
    }

    /**
     * 预告句柄 + 「拿不到」时的一句原因（日志用）。
     *
     * <p>为什么要带着原因一起返回：有<b>四种完全不同的</b>拿不到——还没解析过、直链过期了、
     * 直链要请求头（混音层没有那条通路）、插件曲目压根没有本地缓存。它们的处理一样
     * （都如实放弃），但用户看到的解释必须不同，否则「自动混音没效果」还是查不出为什么。
     */
    private static final class ArmHandle {
        final String handle;
        final String why;
        /**
         * 这条音源需要的请求头（插件直链的 token/Referer）。
         *
         * <p>2026-09-24 之前这里什么都不带：混音层没有 headers 通路，所以这类音源只能
         * <strong>如实放弃</strong>——表现是「自动混音对插件源永远没效果」。
         * 现在那条通路有了（{@code AudioBackend.setNextSource(source, headers, startMs)}
         * → {@code Director.HeadersLookup} → 分析与开流），所以照实交出去。
         */
        final java.util.Map<String, String> headers;

        ArmHandle(String handle, String why) {
            this(handle, why, null);
        }

        ArmHandle(String handle, String why, java.util.Map<String, String> headers) {
            this.handle = handle;
            this.why = why;
            this.headers = headers;
        }
    }

    /** 「下一首」**现在**就能用的音源句柄；要现去解析（或需要请求头）时返回 null。 */
    private String automixSourceOf(Track t) {
        return automixArmHandle(t).handle;
    }

    /**
     * 取「下一首」的句柄，并把拿不到时的原因一起带出来。
     *
     * <p>三种在线/本地源各有一条路，但规矩是同一条：<strong>本地缓存优先，其次手上那条
     * 还新鲜的直链</strong>。插件源以前<b>只有缓存那一条</b>——同一份文件里就有
     * {@link #hasFreshPluginStream(Track)} 却没用上，而用户的整个曲库都是插件源，
     * 于是「下一首」几乎永远是 null，一次过渡都发生不了（这是实测出来的根因之一）。
     *
     * <p>直链要<b>新鲜</b>才用：{@code hasFreshPluginStream} 连
     * {@code streamExpiresAtMs} 一起看，过期/被清掉的那条不该拿去开文件——引擎那边
     * 打不开只会多记一条 {@code skipped}，白折腾一次过渡。
     *
     * <p>插件直链还多一道：{@code streamHeaders} 非空时<strong>如实放弃</strong>。
     * 混音层没有 headers 通路（{@code PlaybackEngine} 会打一条警告），硬把句柄递下去
     * 就是让引擎去开一个一定打不开的 URL——「这首歌预不了」比「引擎去撞墙」诚实。
     */
    private ArmHandle automixArmHandle(Track t) {
        if (t == null) return new ArmHandle(null, "队列里没有这一首");
        if (t.source == Track.Source.LOCAL) {
            String path = t.playable();
            if (path == null || path.isEmpty()) return new ArmHandle(null, "本地曲目没有文件路径");
            return new ArmHandle(path, null);
        }
        if (t.source == Track.Source.NETEASE) {
            // 与 playAt 的取法一致：本地缓存优先，其次手上那条还没过期的直链
            String cached = t.neteaseId != 0 ? diskCache.getAudio(t.neteaseId) : null;
            if (cached != null) return new ArmHandle(cached, null);
            if (t.streamUrl == null || t.streamUrl.isEmpty()) {
                return new ArmHandle(null, "网易云源还没有解析出直链，也没有落到本地缓存");
            }
            return new ArmHandle(t.playable(), null);
        }
        if (t.source == Track.Source.PLUGIN) {
            String cached = diskCache.getAudio(t.canonicalId());
            if (cached != null) return new ArmHandle(cached, null);
            if (!hasFreshPluginStream(t)) {
                return new ArmHandle(null, t.streamUrl == null || t.streamUrl.isEmpty()
                        ? "插件曲目还没有解析出直链，也没有落到本地缓存"
                        : "插件曲目那条直链已经过期（解析出来的直链有时效）");
            }
            // 需要请求头也照实交出去：那条通路已经接通（分析与开流都能带上头）。
            // 空表与「没有头」等价，所以照常传（下游按空表处理）。
            return new ArmHandle(t.playable(), null,
                    t.streamHeaders == null ? null : new java.util.HashMap<String, String>(t.streamHeaders));
        }
        return new ArmHandle(null, "这一种音源不支持预告");
    }

    /**
     * 装上设置中枢（由 {@code SettingsCore.attach} 调）。
     *
     * <p>工作室桥需要设置中枢才能读写（段表进自定义 JSON、模块参数进设置行），
     * 而设置中枢是宿主在自己那一步创建的、<strong>控制器构造时还不存在</strong>。
     * 所以推给它，而不是让控制器去全局找一个实例（那会引入一个隐式的单例）。
     */
    public void attachStudioSettings(dev.t1m3.qplayer.settings.SettingsCore core) {
        if (core == null || studioRef != null) {
            return;
        }
        studioRef = new StudioBridge(core, backend);
        studioRef.setReprepareRequest(this::requestReprepareTransition);
        studioBridge.set(studioRef);
    }

    /**
     * 播放设备（设置页「播放」下的下拉框，只有桌面端有这一行）。
     *
     * <h3>为什么不能像别的设置那样"用到时现读"</h3>
     *
     * <p>它改的是**输出设备**：引擎只在开流那一刻问一次
     * （{@code PlaybackEngine} 里设备为 null 时懒解析 {@code OutputDevices.defaultDevice()}），
     * 所以换设备必须**重开输出**。做法就是设置中枢的 {@code onChange} 通知到这里，
     * 由控制器把选中项落到 {@link OutputDevices#select}，再把当前这首原位置重放一遍。
     *
     * <p>存两份：下拉框那个下标（界面用）与**设备名**（隐藏项，事实来源）。
     * 名字是必须的——设备枚举顺序会变（插拔、驱动重排），只存下标下一次启动可能指着别的设备。
     */
    public void attachOutputDeviceSettings(dev.t1m3.qplayer.settings.SettingsCore core) {
        if (core == null) {
            return;
        }
        // 启动时照上次存的名字装上。**必须在任何一次开流之前**——
        // settings.attach() 的约定就是"在 load 之前接上"，宿主的引擎还没开流。
        String stored = core.str(dev.t1m3.qplayer.settings.SettingsCatalog.OUTPUT_DEVICE_NAME_KEY);
        if (stored != null && !stored.isEmpty()) {
            dev.t1m3.qplayer.audio.output.OutputDevices.select(stored);
        }
        core.onChange(dev.t1m3.qplayer.settings.SettingsCatalog.OUTPUT_DEVICE_KEY, value -> {
            int index = value instanceof Number ? ((Number) value).intValue() : 0;
            java.util.List<String> names =
                    dev.t1m3.qplayer.audio.output.OutputDevices.listOutputDeviceNames();
            // 下标 0 = 跟随系统默认；其余按 names 的顺序（与下拉框的标签一一对应）
            String want = index >= 1 && index - 1 < names.size() ? names.get(index - 1) : null;

            // **没变就什么都不做。** 这条守卫是必须的：`SettingsCore.load()` 末尾的
            // `applyAll()` 会给每个**有值**的设置项广播一遍 hook（见 applyAll 的
            // `fireHooks(s.key, ...)`），于是这一行在**启动时**也会被调一次。
            // 少了它，上次选过设备的人一打开程序就会走到下面的重开输出那一步——
            // 而那时 `loadQueue()` 已经把上一场的队列恢复了、`playIndex` 有效，
            // 结果就是**一启动就自己开始放音乐**（用户实测报的正是这个）。
            String current = dev.t1m3.qplayer.audio.output.OutputDevices.selectedName();
            boolean unchanged = want == null ? current == null : want.equals(current);
            if (unchanged) {
                return;
            }
            dev.t1m3.qplayer.audio.output.OutputDevices.select(want);
            core.setValue(dev.t1m3.qplayer.settings.SettingsCatalog.OUTPUT_DEVICE_NAME_KEY,
                    want == null ? "" : want);
            restartForOutputChange(want);
        });
    }

    /** 换输出设备之后把当前这首重新开一遍（原位置继续），并把结果告诉用户。 */
    private void restartForOutputChange(String deviceName) {
        String label = deviceName == null || deviceName.isEmpty()
                ? I18n.tr("settings.outputDevice.systemDefault") : deviceName;
        final int idx = playIndex;
        // **没在放的时候只换设备，不要顺手把音乐放起来。** 用户选了设备但当时是暂停的，
        // 期待的是"下次播的时候从这台出来"，不是"点到哪台就开始放"。
        if (!backend.isPlaying() || idx < 0 || idx >= queue.size()) {
            // 设备已经落到 OutputDevices 上，下一次开流就是它
            Logger.info("播放设备：已切换到「{}」（当前没有在播，下一次播放生效）", label);
            showToast(I18n.tr("toast.outputDevice.nextPlay", label));
            return;
        }
        pendingResumeIndex = idx;
        pendingResumeMs = Math.max(0L, backend.position());
        Logger.info("播放设备：切换到「{}」，在 {} ms 处重开输出", label,
                Long.valueOf(pendingResumeMs));
        showToast(I18n.tr("toast.outputDevice.switched", label));
        playAt(idx);
    }

    private StudioBridge studioRef;

    /**
     * 工作室桥；宿主还没装上设置中枢时返回 {@code null}（页面那边会显示「没有接上设置」，
     * 而不是抛异常——测试与裸控制器不该因为没装设置就崩）。
     */
    public StudioBridge studio() {
        return studioRef;
    }

    /**
     * 请引擎重做这次过渡（工作室页面的那个按钮）。
     *
     * <p>走的是引擎自己的命令通道：{@code Director.reprepare()} 必须在生产者线程上落地
     * （那几个字段都归它独占）。这里只投一条命令。
     */
    private void requestReprepareTransition() {
        AudioBackend b = backend;
        if (b instanceof dev.t1m3.qplayer.audio.engine.PlaybackEngine) {
            ((dev.t1m3.qplayer.audio.engine.PlaybackEngine) b).reprepareTransition();
        }
    }

    /** Feed coverBytes for the fluid backdrop: local tracks carry embedded
     *  bytes; NETEASE tracks download lazily off-thread, keyed by the caller's
     *  {@link #coverRevision} so a stale fetch for a skipped-past track is dropped
     *  (see {@link #applyCoverAndPath} for why the queue index cannot do that job). */
    private void updateCover(Track t, long revision) {
        if (t.coverBytes != null) {   // present (embedded, or preloaded by preloadTrack)
            final byte[] cb = t.coverBytes;
            final String path = coverDiskPath(t);   // local file for the QML cover image
            post(() -> applyCoverAndPath(cb, path, revision));
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
                post(() -> applyCoverAndPath(data, path, revision));
                notifyPlayback();
                return;
            }
        }
        // A 128px netease thumbnail is enough for the 32px color histogram and
        // usually arrives well before the 1024px lyric/background copy below.
        // Its lower-quality seed is replaced (never overwritten) by the full one.
        scheduleFastMonet(t, revision);
        post(() -> applyCoverAndPath(null, "", revision));
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
                post(() -> applyCoverAndPath(data, path, revision));
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
                post(() -> applyCoverAndPath(data, imgPath, revision));
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
            post(() -> applyCoverAndPath(data, path, revision));
            notifyPlayback(); // refresh the media-notification artwork
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
        // 插件源的词**只在它自己开始播时才取**，所以切过去的那一两秒里界面上没有它的词——
        // 自动混音过渡时尤其刺眼（界面已经在进场曲上了，音乐也已经是它了）。
        // 这里提前取进缓存，真正播到它时就能直接渲染。
        if (t.source == Track.Source.PLUGIN) {
            prefetchPluginLyrics(t);
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
                // 直接暂停（从前这里会先把音量渐变 900 ms 到 0 再暂停，见 settings.fade）。
                // 那条路的代价是「按了暂停还要响将近一秒」，而它想避免的咔哒声在
                // 「输出流暂停」这件事上本来就没有开关可调——它改的只是主增益。
                backend.pause();
            } else {
                if (needsReplay && playIndex >= 0) {
                    needsReplay = false;
                    playAt(playIndex);
                    return;
                }
                backend.resume();
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
            playAt(playMode.peek() == 1 ? randomIndex() : (playIndex + 1) % queue.size());
        });
    }

    public void prev() {
        onMain(() -> {
            if (queue.isEmpty()) return;
            int n = queue.size();
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
            playAt(playMode.peek() == 1 ? randomIndex() : (playIndex - 1 + n) % n);
        });
    }

    /** Record the track we are leaving so shuffle's {@link #prev} can find it.
     *  No-op when replaying the same slot, when prev() itself is consuming the
     *  stack, or when the outgoing id is empty/identical to the incoming one. */
    private void rememberShufflePredecessor(int incomingIndex) {
        if (consumingShuffleHistory) return;
        if (playIndex < 0 || playIndex >= queue.size() || playIndex == incomingIndex) return;
        if (incomingIndex < 0 || incomingIndex >= queue.size()) return;
        String outgoing = queue.get(playIndex).canonicalId();
        if (outgoing == null || outgoing.isEmpty()) return;
        if (outgoing.equals(queue.get(incomingIndex).canonicalId())) return;
        shuffleHistory.addLast(outgoing);
        while (shuffleHistory.size() > MAX_SHUFFLE_HISTORY) shuffleHistory.removeFirst();
    }

    /** Most recent remembered track that is still in the queue and is not the
     *  one already playing. Skips ids dropped by a queue edit. */
    private int popShuffleHistoryIndex() {
        while (!shuffleHistory.isEmpty()) {
            String id = shuffleHistory.removeLast();
            if (id == null || id.isEmpty()) continue;
            for (int i = 0; i < queue.size(); i++) {
                if (i != playIndex && id.equals(queue.get(i).canonicalId())) return i;
            }
        }
        return -1;
    }

    // Track finished on its own: repeat-one replays it, shuffle jumps randomly,
    // list-loop advances. Wired to backend.onComplete (not next()) so repeat-one
    // doesn't fight a user's manual skip. Already on the main thread (onComplete).
    private void autoAdvance() {
        if (queue.isEmpty()) return;
        pendingNaturalEnd = true;
        playbackEndRevision.incrementAndGet();
        final int before = playIndex;
        traceAdvance("收到曲终", "引擎报曲终/交接", before, before);
        if (!pluginAutoAdvanceBlocker.isEmpty()) {
            traceAdvance("拦下", "插件阻塞自动推进", before, before);
            return;
        }
        // 这一次曲终如果是「过渡开始时那一次推进」的交接，队列就不许再往前走第二下。
        if (consumeAdoptedHandover()) {
            traceAdvance("拦下", "被认作过渡交接（已经推进过）", before, playIndex);
            return;
        }
        performAutoAdvance();
        traceAdvance("推进", "普通曲终 → performAutoAdvance", before, playIndex);
    }

    /** 「推进队列」的追踪序号：把「谁先谁后」变成日志里可读的一列（竞态排查用）。 */
    private final java.util.concurrent.atomic.AtomicLong advanceTraceSeq =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 记一条「推进队列」的追踪（<strong>只加日志，不改变任何行为</strong>）。
     *
     * <p>为什么需要它：那次「放几秒又跳」是<strong>随机</strong>的，而日志里什么都看不出来。
     * 因为触发它的是两条线程往<strong>同一条渲染线程队列</strong>上投递的顺序——引擎的交接通知
     * （{@code fire(onComplete)} 在生产者线程上投递）与过渡开始时的界面推进
     * （{@code onTransitionStart} 同样投递）。谁先被处理，取决于那一帧队列里的顺序与渲染线程
     * 当时的繁忙程度：<strong>不抛异常、不打日志、只在渲染线程上改变一次顺序</strong>。
     *
     * <p>所以这里把每一次「收到曲终 / 推进 / 拦下」按发生顺序编号记下来。下次复现时，
     * 日志里会直接写出<strong>是谁推的</strong>、当时 {@code playIndex} 在哪、引擎在放什么、
     * 手上有没有一笔待交接。
     */
    private void traceAdvance(String stage, String trigger, int from, int to) {
        Logger.info("推进追踪 #{} · {} · {} · playIndex {}→{} · 引擎现在放「{}」· 待交接={}",
                Long.valueOf(advanceTraceSeq.incrementAndGet()), stage, trigger,
                Integer.valueOf(from), Integer.valueOf(to),
                String.valueOf(backend.currentSource()),
                automixAdoptedNext == null ? "无" : flowLabel(automixAdoptedNext));
    }

    /**
     * 引擎报告「自动混音的一次过渡<strong>已经开始</strong>」——进场曲已经在出声。
     *
     * <h3>为什么这一刻就要推进</h3>
     *
     * <p>引擎的自动交接是「窗口走完 → 进场曲上位 → {@code onComplete}」，而窗口本身
     * 就是几秒到十几秒（交叉淡化 6 s、低音交接 8 s、表现模式 16 s）。这些秒里进场曲
     * 已经在出声，等 {@code onComplete} 才推进，界面就会<strong>整个窗口</strong>停在
     * 出场曲上：标题、封面、歌词、队列高亮全是旧的，进度条一路走到 100% 才跳。
     * 用户实测的抱怨原文就是这句（「过渡到 B 后 UI 应该直接跳转到下首歌，现在还是 A
     * 完全进度走完了才跳跃」）。
     *
     * <h3>只推进界面，一个采样都不动</h3>
     *
     * <p>这里<strong>不调用 {@code backend.play}</strong>，也不暂停、不 seek：引擎那边
     * 进场曲已经在放，任何一次多余的后端调用都可能把交叉淡化的接缝撕开。所以推进的
     * 内容只有「谁算当前曲」——{@code playIndex}、队列下标与界面上那几个字段，
     * 以及进场曲自己的歌词/封面装载（它们本来只会在换曲时做）。
     *
     * <h3>恰好一次</h3>
     *
     * <p>推进记一笔 {@link #automixAdoptedNext}；曲终那次 {@code onComplete} 走到
     * {@link #consumeAdoptedHandover()}，对上号就把那一次曲终当成「已经推进过」而不再
     * 推进（列表循环不会从 B 再跳到 C）。对不上号（引擎报的当前曲不是这一首）就按老路
     * 推进——这条兜底让「没有自动混音的后端」「过渡被打断」这些情形退回改动前的行为。
     */
    private void onTransitionStarted(String introHandle) {
        if (introHandle == null || introHandle.isEmpty()) return;
        // 已经有一次推进在等交接：不重复推进（同一次过渡只会报一次，双保险）
        if (automixAdoptedNext != null) return;
        Track armed = automixArmedNext;
        if (armed == null || !handleNamesTrack(armed, introHandle)) {
            // 进场曲不是我们预告出去的那一份（队列刚被改过、或这一份预告来自更早的时点）：
            // 如实放弃这次提前推进，曲终那条老路照样会把界面推过去（不会比改动前更差）。
            Logger.info("自动混音：过渡已经开始，但进场曲不是当前这一份预告（{}）→ "
                    + "界面等曲终再推进（日志里那句「过渡结束」之后）", introHandle);
            return;
        }
        int idx = -1;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i) == armed) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            Logger.info("自动混音：过渡开始时预告的那一首已经不在队列里 → 界面等曲终再推进");
            return;
        }
        if (idx == playIndex) {
            // 单曲循环：下一首就是这一首，没有可推进的下标（曲终那条老路会重发一次
            // 同一首的界面状态，与改动前一致）。
            return;
        }
        adoptIncomingTrack(armed, idx, introHandle);
    }

    /** 过渡开始那一刻把「当前曲」推进到进场曲（只改状态与界面，不碰后端）。 */
    private void adoptIncomingTrack(Track incoming, int idx, String handle) {
        final int wasIndex = playIndex;
        automixAdoptedNext = incoming;
        // 界面上的封面从这一刻起做交叉融合（见 automixHandover 的注释）
        automixHandover.set(Boolean.TRUE);
        automixAdoptedHandle = handle;
        automixAdoptedFrom = (playIndex >= 0 && playIndex < queue.size()) ? queue.get(playIndex) : null;
        // 队列下标的推进必须在下标还指着出场曲的时候先做「离开」那一半的记账
        // （rememberShufflePredecessor 读的也是 playIndex）。
        rememberShufflePredecessor(idx);
        playIndex = idx;
        traceAdvance("过渡开始", "界面提前推进到进场曲", wasIndex, idx);
        // 界面切过去：与一次真正的换曲发布同一份字段，只是位置用引擎此刻报的值
        // （过渡窗口里引擎按进场 deck 报位置/时长，见 PlaybackEngine.publishSnapshot）。
        final long enginePosition = Math.max(0L, backend.audiblePosition());
        final long coverRev = coverRevision.incrementAndGet();
        publishNowPlaying(incoming, idx, enginePosition, coverRev);
        loadLyricsForTrack(incoming);
        // 进场曲已经在出声：没有「等加载」这一段，界面上的扫掠要收掉。
        post(() -> loading.set(false));
        // 歌词时钟的基线要跟着换一首（位置从出场曲的结尾跳到进场曲的当前位置），
        // playbackRevision 就是那个「不连续」信号；这里不能调 beginLyricClockLoad：
        // 它会把 playbackStarted 落成 false，而音频其实一直在响。
        stoppedLyricPositionMs = enginePosition;
        playbackRevision.incrementAndGet();
        notifyPlayback();
        // **进度跳变诊断**（2026-10-11 ✓ 用户：「切换过来后瞬间有概率出现进度轻微跳变」✗）：
        // 引擎位置在交接前后**已经相等** ✓（日志：差 +0 帧 ✓）⇒ 跳变只能在**显示层**✗
        // ⇒ 这里把"界面此刻用的位置"和"引擎报的位置"一起打出来 ✓ 两处对比就能定位 ✓
        Logger.info("进度诊断 · 过渡开始 · 界面位置 {} ms · 引擎位置 {} ms",
                Long.valueOf(stoppedLyricPositionMs), Long.valueOf(enginePosition));
    }

    /**
     * 这一次 {@code onComplete} 是不是「过渡开始时已经推进过的那一次」的交接。
     *
     * <p>判据只有一条：<strong>引擎现在放的正是过渡开始那一刻切过去的那一首</strong>
     * （{@link AudioBackend#currentSource()}）。对上了就说明那一次推进已经被交接掉，
     * 队列<b>不许</b>再往前走第二下；对不上就按老路推进，并且把这一次交接补全：
     *
     * <ul>
     *   <li>认掉引擎的一次性令牌：{@code confirmHandoff} 等的是「控制器随后
     *       {@code play(同一首)}」（见 {@code PlaybackEngine} 的注释）。这里也照做，
     *       只是<strong>不重开一次播放路径</strong>——播放位置、界面、队列都已经是对的，
     *       那次 {@code play} 只会被引擎认掉（它什么都不重启）。不认的话令牌会一直挂着，
     *       用户下一次点这一首会被它吃掉（点了没反应）。</li>
     *   <li>补上出场曲的 scrobble：推进那一刻它还在出声，不能算作一次播放完成，
     *       到这里它才真的放完。</li>
     *   <li>重算「下一首」预告：引擎的编排器已经把进场曲提上来了，队首换了一首。</li>
     * </ul>
     *
     * @return true = 这次曲终已经处理过（调用方不要推进队列）
     */
    private boolean consumeAdoptedHandover() {
        Track adopted = automixAdoptedNext;
        if (adopted == null) return false;
        String handle = automixAdoptedHandle;
        Track from = automixAdoptedFrom;
        forgetAdoptedHandover();
        // 兜底判据（不依赖句柄字符串）：过渡那一刻队列**已经**推进到进场曲了
        // （见 onTransitionStarted：推进时记下 automixAdoptedNext）。所以只要队列此刻
        // 指的正是这一首，这次曲终就一定属于「交接」，绝不是普通曲终——再推进一次
        // 就会多走一首（用户听感：切换刚完成就跳到下一首）。
        //
        // 为什么需要这条：engineIsPlayingAdopted 比的是句柄，而同一首歌的句柄会变
        // （直链 → 缓存文件），安卓上还会出现引擎从**临时下载文件**解码、控制器手上
        // 是**缓存文件**的第三种情形——对不上就走进普通推进，队列于是多走一首。
        boolean queueAlreadyOnAdopted = false;
        try {
            if (playIndex >= 0 && playIndex < queue.size() && queue.get(playIndex) != null) {
                String a = adopted.canonicalId();
                String b = queue.get(playIndex).canonicalId();
                queueAlreadyOnAdopted = a != null && a.equals(b);
            }
        } catch (Throwable ignored) { }
        if (!engineIsPlayingAdopted(adopted, handle) && !queueAlreadyOnAdopted) {
            // 引擎现在放的不是过渡时切过去的那一首：这次曲终属于别的曲子
            // （过渡被打断 / 用户中途换歌 / 后端本来就没有自动混音）→ 走老路推进。
            //
            // **这一条以前是静默的**，而后果是实打实的：控制器会把一次「已经推进过、
            // 现在只是来交接」的曲终当成普通曲终，队列于是再往前走一首——听感就是
            // 「刚交接过去的那首放了几秒又跳了」。所以这里必须留下能查的日志。
            Logger.warn("自动混音：曲终对账没对上——过渡时切过去的是「{}」，而引擎现在放的是「{}」"
                            + " → 按普通曲终推进（队列会多走一首）",
                    handle, String.valueOf(backend.currentSource()));
            traceAdvance("对账失败", "对账没对上 → 按普通曲终推进（会多走一首）", playIndex, playIndex);
            return false;
        }
        scrobbleTrack(from, from == null ? 0L : from.durationMs, true);
        pendingNaturalEnd = false;
        // **确认交接，而不是再播一次。**
        //
        // 这里从前是 `playBackend(handle, 0L)`，指望引擎把它当成「追认」而不是「换曲」
        // （靠引擎那枚一次性令牌）。令牌按句柄字符串逐字比对，而同一首歌的直链与缓存文件
        // 是两个字符串——对不上的那一次引擎就真的从头重开一遍，用户听到的正是
        // 「过渡完成后歌曲进度被重置回 0 从头播放」。现在改成显式的确认命令：
        // 引擎只认账，没有「重开」这条路径（见 AudioBackend#confirmHandoff）。
        backend.confirmHandoff(handle);
        armNextSourceForAutomix();
        return true;
    }

    /** 丢掉一笔「过渡开始时推进过、还没等到交接」的记录（用户换了曲、或已经交接掉）。 */
    private void forgetAdoptedHandover() {
        automixAdoptedNext = null;
        automixAdoptedHandle = "";
        automixAdoptedFrom = null;
        // 交接结束（对账完成 / 用户换曲 / 会话被顶掉）：封面回到普通换图
        automixHandover.set(Boolean.FALSE);
    }

    /**
     * 引擎现在放的，是不是「过渡开始时切过去的那一首」。
     *
     * <h3>为什么不比一个字符串</h3>
     *
     * <p>同一首歌的音源句柄<strong>会变</strong>：先以直链预告、音频落盘之后又以缓存文件预告，
     * 而引擎的 deck 停在它<strong>当初打开的那一份</strong>上。实测日志里就是两个不同的
     * {@code v2-….cache}：控制器手上是新的那份，引擎放着的是旧的那份——字符串一比就"对不上"，
     * 于是这一次「已经推进过、现在只是来交接」的曲终被当成普通曲终，<strong>队列多走一首</strong>
     * （听感：刚交接过去的那首放几秒又跳了，桌面与安卓都会）。
     *
     * <p>所以判据改成「这一首的音源有哪几种可能」：过渡那一刻的句柄、随后又预告出去的那一份、
     * 它自己的缓存文件路径、它手上那条直链、以及本地路径。这些里任何一个对上，都说明
     * 引擎放的正是这一首。
     */
    private boolean engineIsPlayingAdopted(Track adopted, String adoptedHandle) {
        String now = backend.currentSource();
        if (now == null || now.isEmpty()) {
            return false;
        }
        if (now.equals(adoptedHandle)) {
            return true;
        }
        return handleNamesTrack(adopted, now);
    }

    /**
     * 这个句柄是不是「这一首」的音源之一。
     *
     * <h3>为什么判据不是字符串相等（2026-09-26 实测）</h3>
     *
     * <p>同一首歌的音源句柄会变：先以<strong>直链</strong>预告，音频落盘之后又变成
     * <strong>缓存文件</strong>。而「同一首歌换个句柄不算换了下一首」那条纪律
     * （见 {@link #isHandleUpgradeForSameTrack}）会让第二次<strong>不再告诉引擎</strong>——
     * 于是引擎手上是直链、控制器手上是缓存路径，两边一旦拿字符串比对就永远对不上。
     *
     * <p>对不上的后果分两处，都是用户能听见的：
     *
     * <ul>
     *   <li>过渡<strong>开始</strong>时（{@link #onTransitionStarted}）：界面不推进，
     *       整个窗口停在出场曲上；</li>
     *   <li>过渡<strong>结束</strong>时（{@link #autoAdvance} → {@link #performAutoAdvance}）：
     *       这次曲终被当成普通曲终，控制器对进场曲再发一次 {@code play(0)}
     *       ——<strong>进度被重置回 0 从头播放</strong>（用户实测原话）。</li>
     * </ul>
     *
     * <p>所以判据改成「这一首有哪几种可能的句柄」：最近预告出去的那一份、它的缓存文件、
     * 它手上的直链、以及本地路径。这些里任何一个对上，都说明说的就是这一首。
     */
    private boolean handleNamesTrack(Track t, String handle) {
        if (handle == null || handle.isEmpty()) {
            return false;
        }
        // 「最近预告出去的那一份句柄」只对**它自己的那一首**算数。
        //
        // 这一处原来只比句柄、不看 `t`：而过渡开始之后控制器立刻会去预告**再下一首**
        // （adopted = B、armed = C），于是「引擎报的是 C」会被当成「引擎报的是 B」——
        // 曲终那条兜底判定（对不上号就按老路推进队列）永远走不到，
        // 表现是队列停在这一首不再往下走。2026-09-26 由
        // AutomixArmingTest.aCompletionForADifferentTrackStillAdvancesTheQueueTheOldWay 照出来。
        // 判不了「预告的是哪一首」（automixArmedNext 为空）时保留旧行为。
        if (handle.equals(automixArmedHandle)
                && (t == null || automixArmedNext == null || t == automixArmedNext)) {
            return true;
        }
        if (t == null) {
            return false;
        }
        String cached = t.neteaseId != 0
                ? diskCache.audioPath(t.neteaseId) : diskCache.audioPath(t.canonicalId());
        return handle.equals(cached) || handle.equals(t.streamUrl) || handle.equals(t.playable());
    }

    /** 按音源类型装载这一首的歌词（只有换曲时才会做，所以没有别处的分发点）。 */
    private void loadLyricsForTrack(Track t) {
        if (t == null) return;
        clearLyricsForPendingTrack();
        if (t.source == Track.Source.LOCAL) {
            loadLocalLyrics(t);
        } else if (t.source == Track.Source.NETEASE) {
            loadNeteaseLyrics(t);
        } else if (t.source == Track.Source.PLUGIN) {
            loadPluginLyrics(t);
        }
    }

    /**
     * 换曲时先把上一首的歌词<strong>清掉</strong>，绝不把它挂到新歌上。
     *
     * <h3>为什么必须清</h3>
     *
     * <p>歌词是异步取的（在线曲目要走网络，一次一两秒），而在它回来之前 {@link #lyrics} 里
     * 还是<strong>上一首</strong>的词。那时播放位置已经在新歌上，于是界面拿着新歌的进度去高亮
     * 上一首的句子——用户看到的就是「切歌后先显示旧歌词，过一会儿才跳成新的」。
     * 这个现象在自动混音过渡时最刺眼：界面是在过渡一开始就切到进场曲的（那时候音乐已经是新歌了），
     * 而它的词可能要过一两秒才到（2026-09-20 用户实测）。
     *
     * <p><strong>与其显示错的词，不如先什么都不显示。</strong> 空表会被
     * {@link #computeCoverOnly} 当成「没有歌词」，界面因此退回封面（那是诚实的：这一刻我们
     * 确实还没有新歌的词），等真词到了再渲染出来。本地曲目的词是同步读的（有缓存文件时
     * 同一次调用里就 set 完了），所以这条清理对它们只是一次多余的空写，看不出跳动。
     */
    private void clearLyricsForPendingTrack() {
        List<LyricLine> current = lyrics.peek();
        if (current != null && !current.isEmpty()) {
            applyLyrics(Collections.<LyricLine>emptyList());
        }
    }

    private void performAutoAdvance() {
        // autoAdvance() and pluginAppUnavailable() both check this, but the plugin
        // host's "playback.next" calls straight in — and the list-loop branch below
        // divides by the queue size.
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
        seekRevision.incrementAndGet();
        stoppedLyricPositionMs = t;
        backend.seek(t);
        post(() -> positionMs.set(t));
        notifyPlayback();
    }

    public long seekRevision() {
        return seekRevision.get();
    }

    public long position() {
        return backend.audiblePosition();
    }

    /** True only while song time should advance visually. Unlike {@link #isPlaying()},
     * this stays false during async source loading. */
    public boolean isLyricClockRunning() {
        return playbackStarted && backend.isPlaying();
    }

    /** Exact lyric-clock baseline. While running this is the live backend position;
     * after a source has started, its paused position remains the source of truth too,
     * so pause/resume cannot diverge and then visibly jump back into alignment. */
    public long lyricClockPosition() {
        return playbackStarted
                ? Math.max(0L, backend.audiblePosition())
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
        long backendPosition = Math.max(0L, backend.audiblePosition());
        if (playingIntent || backendPosition > 0L) return backendPosition;
        if (pendingResumeIndex == playIndex) return Math.max(0L, pendingResumeMs);
        Long propertyPosition = positionMs.peek();
        return propertyPosition != null ? Math.max(0L, propertyPosition) : 0L;
    }

    public void setVolume(float v) {
        float clamped = Math.max(0f, Math.min(1f, v));
        userVolume = clamped;
        backend.setVolume(clamped);
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

    /**
     * 设置项：自动混音<strong>总开关</strong>（见 {@code SettingsCatalog.AUTOMIX_ENABLED_KEY}）。
     *
     * <p>立即重算一次预告，而不是等下一次换曲才生效，理由有两条：
     *
     * <ul>
     *   <li><strong>关掉时不能让当前这一曲还在做过渡。</strong>编排器的窗口是开会话时按
     *       当时设置定下的，光改设置不会动它。这次重算会再发一条预告，而引擎发现
     *       「开关和这份编排器建的时候不一样」就会<strong>重建编排器</strong>
     *       （见 {@code PlaybackEngine.handleArmNext}），新窗口立刻是 0——
     *       用户拨下开关之后，这一曲的结尾就不会再交叠了。</li>
     *   <li><strong>打开时也立刻预告一次。</strong>当前这一曲还在放，而引擎正是靠
     *       这份预告提前 30 秒以上做对拍/判调性的；等到下一曲才开始，如果那一曲很短，
     *       就已经晚了。</li>
     * </ul>
     *
     * <p>{@code onMain}：预告要碰队列与 {@code playIndex}，那是主线程的东西
     * （设置页的通知可能来自渲染线程）。
     */
    public void setAutomixEnabled(boolean enabled) {
        this.automixEnabled = enabled;
        onMain(() -> {
            armNextSourceForAutomix();
            // 打开时顺手把「下一首」的直链解析做掉：当前这一曲还在放，而引擎要的正是
            // 「提前 30 秒以上拿到句柄」。关掉时 prefetchNextForAutomix 自己会直接返回
            // （关 = 不为一个关着的功能花带宽）。
            prefetchNextForAutomix();
        });
    }

    /** ③ 分层取模（Apple weighted modulo）：设置页开关推过来时同步给选点层。 */
    public void setAutomixTieredSelection(boolean on) {
        dev.t1m3.qplayer.audio.mix.AutoCue.setTieredSelectionEnabled(on);
        dev.t1m3.qplayer.util.Logger.info("[qplayer-engine] 分层取模（同层随机取点）：{}",
                on ? "开（落点可能变化）" : "关（永远取最高分，可复现）");
    }

    /** 自动混音总开关的当前值（诊断/测试用）。 */
    public boolean isAutomixEnabled() {
        return automixEnabled;
    }

    /**
     * 设置项：当前的接法<strong>真的会做过渡</strong>吗——
     * 总开关打开 <b>且</b> 接法不是「无缝拼接」。宿主（{@code SettingsCore}）在开关或
     * 接法变化时推过来。
     *
     * <p>它只影响一件事：要不要为「下一首」花带宽做预解析/预取
     * （见 {@link #prefetchNextForAutomix} 的注释）。开关一变就重算一次：
     * 打开时立刻开始预热，关掉时把预取留下的钉子交还给 {@link #armNextSourceForAutomix} 那一路。
     */
    public void setAutomixTransitionRequested(boolean requested) {
        this.automixTransitionRequested = requested;
        onMain(() -> {
            prefetchNextForAutomix();
            refreshAutomixAudioPins();
        });
    }

    /** 「这次换曲真的会做过渡吗」的当前值（诊断/测试用）。 */
    public boolean isAutomixTransitionRequested() {
        return automixTransitionRequested;
    }

    /** Load a netease track's lyrics off-thread (AMLL TTML mirror, else Netease's
     *  own). tryAmllTtml hits the disk cache first, so a previously-played song shows
     *  its lyrics with no network. Called on every netease play — including the
     *  audio-cache fast path, which bypasses the URL resolve that used to fetch them. */
    private void loadNeteaseLyrics(Track t) {
        final long songId = t.neteaseId;
        if (songId == 0) return;
        List<LyricLine> mem = lyricMem.get(songId);
        if (mem != null) {   // preloaded / recently played -> apply instantly
            post(() -> { if (isCurrentTrack(t)) applyLyrics(mem); });
            return;
        }
        // (Lyrics were already blanked at the track switch in playAt; the async fetch
        // eases them back in when it lands.) lyricWorker, not worker: this runs
        // concurrently with updateCover()'s own worker-queued download instead of
        // sitting behind it -- see lyricWorker's field javadoc.
        lyricWorker.submit(() -> {
            List<LyricLine> ly = fetchNeteaseLyrics(songId);
            post(() -> { if (isCurrentTrack(t)) applyLyrics(ly); });
        });
    }

    /**
     * 只把插件源的歌词取进缓存，<strong>不碰界面</strong>（给「下一首」预热用）。
     *
     * <p>与 {@link #loadPluginLyrics} 的区别只有一条：取回来不 {@code applyLyrics}。
     * 预热那首歌还不是当前曲，往界面上写它就会把正在听的这一首的词盖掉。
     *
     * <p>失败一律安静：预热本来就是"顺手"的事，网络抖一下、插件不支持歌词都不该刷日志；
     * 真播到它时会再走一次完整路径，那时才如实报错。
     */
    /**
     * 预热下一首/上一首的歌词（插件源）。
     *
     * <p><b>每一条「没预热成」的路都要留下原因</b>：这条路上原先只有「解析失败」会说话，
     * 而用户实际遇到的是「切过去还是没有词」——那可能是插件压根没声明 {@code LYRICS} 能力
     * （这里直接 return，静默）、id 解析不出来、或者请求本身失败（原先也直接 return）。
     * 三种情况在界面上长得一模一样，所以三种都要记一行，理由与
     * {@code StemSeparators.unavailableReason} 是同一条：原因与结论写两处，迟早对不上。
     */
    private void prefetchPluginLyrics(Track track) {
        if (track == null || track.source != Track.Source.PLUGIN) return;
        final String id = track.canonicalId();
        if (id.isEmpty()) {
            Logger.info("预热歌词：{} 没有规范 id（音源 {}），跳过", track.title,
                    providerOf(track));
            return;
        }
        if (pluginLyricMem.containsKey(id)) {
            return;
        }
        if (!pluginHasCapability(providerOf(track), ProviderCapability.LYRICS)) {
            Logger.info("预热歌词：{} 的音源没有声明 lyrics 能力，跳过（切过去不会有词）", id);
            return;
        }
        final MediaId mediaId;
        try {
            mediaId = MediaId.parse(id);
        } catch (IllegalArgumentException error) {
            Logger.info("预热歌词：{} 的 id 解析不了（{}），跳过", id, error.getMessage());
            return;
        }
        final long startedAt = System.nanoTime();
        pluginProviders.lyrics(mediaId).whenComplete((payload, error) -> {
            if (error != null) {
                Logger.warn("预热歌词失败（{}，耗时 {} ms）：{}", id,
                        Long.valueOf((System.nanoTime() - startedAt) / 1_000_000L),
                        safeMessage(error));
                return;
            }
            try {
                List<LyricLine> lines = LyricParser.fromPluginAssets(payload);
                long ms = (System.nanoTime() - startedAt) / 1_000_000L;
                if (!lines.isEmpty()) {
                    pluginLyricMem.put(id, lines);
                    Logger.info("预热歌词：{} 拿到 {} 行（{} ms）", id, Integer.valueOf(lines.size()),
                            Long.valueOf(ms));
                } else {
                    Logger.info("预热歌词：{} 返回了空歌词（{} ms）——切过去不会有词", id,
                            Long.valueOf(ms));
                }
            } catch (Throwable parseError) {
                // 解析失败留一条日志：它解释的是「为什么这首歌切过去还是没词」
                Logger.warn("预热歌词解析失败（{}）：{}", id, safeMessage(parseError));
            }
        });
    }

    private void loadPluginLyrics(Track track) {
        final String id = track.canonicalId();
        if (id.isEmpty() || !pluginHasCapability(providerOf(track), ProviderCapability.LYRICS)) return;
        List<LyricLine> cached = pluginLyricMem.get(id);
        if (cached != null) {
            post(() -> { if (isCurrentTrack(track)) applyLyrics(cached); });
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
            post(() -> { if (isCurrentTrack(track)) applyLyrics(lines); });
        });
    }

    /** True while {@code t} is still the track a loader fetched data for. Compares
     *  track identity rather than the queue slot it occupied: two consecutive
     *  selections can land on the same index (playlist A's row 3 -> playlist B's
     *  row 3), and a bare index check then lets a superseded fetch publish the
     *  previous song's lyrics over the new one. Identity also survives an in-place
     *  retry — onPlaybackError re-resolves the very same track — which a monotonic
     *  revision counter would wrongly treat as a track change and discard. */
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
        // The running flag has to be cleared even on an unexpected throw: leaving it
        // set makes every later cacheAudioAsync() return at its `if (autoCacheRunning)`
        // check, silently disabling auto-caching for the rest of the session with no
        // way back. The layers below all catch their own failures today, so this is
        // insurance against an Error rather than a live path.
        try {
            while (true) {
                Track t;
                synchronized (autoCacheLock) {
                    t = autoCachePending;
                    autoCachePending = null;
                    if (t == null) return;
                }
                // 钉在写盘**之前**：cacheAudio 内部写完就做一次淘汰扫描，缓存满时
                // 未受保护的「最新」文件（= 刚写完的这一个）是第一个候选。
                refreshAutomixAudioPins();
                diskCache.cacheAudio(t.streamUrl, t.neteaseId);
                mediaMetaIndex.save();
                // 音频刚落盘：重算一次预告——「下一首」如果是这一首（单曲循环、来回切歌），
                // 它此刻才第一次拿得到句柄（缓存优先那一条）。
                onMain(this::armNextSourceForAutomix);
            }
        } finally {
            // Clearing the flag is not enough on its own: between the loop's own
            // `pending == null` check (which releases the lock to return) and this
            // block, a cacheAudioAsync() can have queued a track and seen
            // autoCacheRunning still true, so it submitted nothing. Re-check under
            // the lock and hand the drain off to a fresh task when that happened.
            boolean requeue;
            synchronized (autoCacheLock) {
                requeue = autoCachePending != null;
                autoCacheRunning = requeue;
            }
            if (requeue) {
                try {
                    cacheWorker.submit(this::drainAutoCacheQueue);
                } catch (RejectedExecutionException shuttingDown) {
                    // shutdown() stopped the pool. Release the flag rather than
                    // letting this throw out of a finally block and mask whatever
                    // sent us here; there is no session left to auto-cache for.
                    synchronized (autoCacheLock) {
                        autoCacheRunning = false;
                    }
                }
            }
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
                                ? I18n.tr("toast.play.vipOrBlocked") : I18n.tr("toast.signInRequired"));
                        return;
                    }
                    t.streamUrl = playUrl;
                    t.trial = isTrialOnly;
                    post(() -> {
                        if (isTrialOnly) showToast(I18n.tr("toast.play.trialOnly"));
                        title.set(orEmpty(t.title));
                        artist.set(orEmpty(t.artist));
                        publishPlayingArtist(t);
                        album.set(orEmpty(t.album));
                        coverUrl.set(orEmpty(thumbUrl(t.coverUrl, "512")));
                        durationMs.set(t.durationMs);
                    });
                    updateCover(t, expectedCoverRevision);
                    loadNeteaseLyrics(t);
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
                onMain(() -> skipUnplayable(expectedIndex, I18n.tr("toast.play.resolveFailed")));
            }
        });
    }

    private void resolveAndPlayPlugin(Track track, int expectedIndex, long resumeMs,
                                      long expectedCoverRevision) {
        final MediaId id;
        try {
            id = MediaId.parse(track.canonicalId());
        } catch (IllegalArgumentException error) {
            skipUnplayable(expectedIndex, I18n.tr("toast.play.badPluginId"));
            return;
        }
        if (!pluginHasCapability(id.provider(), ProviderCapability.RESOLVE_STREAM)) {
            skipUnplayable(expectedIndex, I18n.tr("toast.play.pluginNoStream"));
            return;
        }
        pluginProviders.resolveStream(id, playLevel).whenComplete((stream, error) -> onMain(() -> {
            if (playIndex != expectedIndex) return;
            if (error != null || stream == null || stream.url == null || stream.url.isEmpty()) {
                Logger.warn("plugin {} stream resolve failed: {}", id.provider(),
                        error != null ? safeMessage(error) : "empty URL");
                skipUnplayable(expectedIndex, I18n.tr("toast.play.pluginStreamFailed"));
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
                if (stream.trial) showToast(I18n.tr("toast.play.trialOnly"));
            });
            updateCover(track, expectedCoverRevision);
            loadPluginLyrics(track);
            Logger.info("play plugin {}: {}", id.provider(), track.title);
            playBackend(stream.url, stream.headers, resumeMs);
            playingIntent = true;
            post(() -> playing.set(true));
            notifyPlayback();
            cachePluginAudioAfterStart(track);
            // 直链是**异步**解析出来的：playAt 末尾那一次预告跑在这之前，那时这一首
            // 手上还没有任何句柄。这里重算一次——「下一首」正好是它时（单曲循环、
            // 队列刚改过、来回切歌）这次才算得准。已经在 main 线程上（上面的 onMain）。
            armNextSourceForAutomix();
        }));
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
     * pipe to itself first costs the cache nothing: it still finishes long
     * before the track does.
     */
    private static final long AUTO_CACHE_DELAY_MS = 6_000L;

    /** Queue the auto-cache for a track that just started playing. Skipped if the
     *  user has moved on by the time it fires -- caching a track they skipped past
     *  would take bandwidth from whatever they skipped to. */
    private void cachePluginAudioAfterStart(Track track) {
        final String mediaId = track != null ? track.canonicalId() : "";
        if (mediaId.isEmpty() || diskCache.hasAudio(mediaId)) return;
        postWorker.schedule(() -> onMain(() -> {
            Track current = currentTrack();
            if (current == null || !mediaId.equals(current.canonicalId())) return;
            cachePluginAudioAsync(track, false, null);
        }), AUTO_CACHE_DELAY_MS, TimeUnit.MILLISECONDS);
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
                        // **必须在 finishExternalWrite 之前钉**：那一步就是淘汰扫描，
                        // 而缓存满时未受保护的「最新」文件（= 刚写完的这一个）是第一个候选。
                        refreshAutomixAudioPins();
                        diskCache.finishExternalWrite();
                        mediaMetaIndex.save();
                        // 音频刚落盘：「下一首」如果是这一首（单曲循环、或用户来回切），
                        // 此刻它才第一次拿得到句柄。重算一次预告，别让这份缓存白等下一曲。
                        onMain(this::armNextSourceForAutomix);
                    } else if (error != null) {
                        Logger.warn("plugin audio cache failed for {}: {}", mediaId, safeMessage(error));
                    }
                    // 这一首的预取到此结束：还该钉的就由 arm 那一路继续钉着
                    // （它已经是「下一首」就继续钉，不是了就撤掉）。
                    if (automixPrefetchingNext == track) {
                        automixPrefetchingNext = null;
                        onMain(this::refreshAutomixAudioPins);
                    }
                    if (completion != null) post(completion);
                });
    }

    public void cacheMediaSong(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return;
        if (diskCache.hasAudio(mediaId)) { showToast(I18n.tr("toast.cache.already")); return; }
        Song song = findPluginSong(mediaId);
        if (song == null) { showToast(I18n.tr("toast.cache.staleSong")); return; }
        Track track = toTrackPlugin(song);
        final MediaId id;
        try { id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.SONG); }
        catch (IllegalArgumentException error) { showToast(I18n.tr("toast.id.song")); return; }
        showToast(I18n.tr("toast.cache.started"));
        pluginProviders.resolveStream(id, playLevel).whenComplete((stream, error) -> {
            if (error != null || stream == null || stream.trial || !stream.cacheable) {
                post(() -> showToast(I18n.tr("toast.cache.failedReason", safeMessage(error))));
                return;
            }
            track.streamUrl = stream.url;
            track.streamHeaders.putAll(stream.headers);
            track.streamExpiresAtMs = stream.expiresAtMs;
            track.streamCacheable = stream.cacheable;
            cachePluginAudioAsync(track, true, () -> showToast(
                    diskCache.hasAudio(mediaId) ? I18n.tr("toast.cache.done") : I18n.tr("toast.cache.failed")));
        });
    }

    public void removeMediaCache(String mediaId) {
        // 这里原来是「渲染线程删文件 + 紧跟一次全量重扫」的双重 I/O（QML 直接调进来）。
        // 删除挪到 cacheWorker；提示依赖删除结果，所以整段关系原样保留，只是提示经 post
        // 回投 —— 它自己就在写 Property，只能在渲染线程上做。`refreshCachedSongs` 内部
        // 已经是异步的，从回投里再调它是安全的：那一刻执行在渲染线程，而不是 cacheWorker
        // 的任务里，所以不会自我提交成死锁。
        cacheWorker.execute(() -> {
            boolean removed = diskCache.deleteAudio(mediaId);
            post(() -> {
                if (removed) refreshCachedSongs();
                showToast(removed ? I18n.tr("toast.cache.removed")
                                  : I18n.tr("toast.cache.removeFailed"));
            });
        });
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
            long pos = playIndex >= 0 ? Math.max(0L, backend.audiblePosition()) : 0L;
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
                boolean persistedAsPlugin = "PLUGIN".equals(src);
                t.source = "LOCAL".equals(src) ? Track.Source.LOCAL
                        : "CUSTOM_API".equals(src) ? Track.Source.CUSTOM_API
                        : persistedAsPlugin ? Track.Source.PLUGIN
                        : Track.Source.NETEASE;
                t.neteaseId = o.has("neteaseId") ? o.get("neteaseId").getAsLong() : 0;
                t.customId  = o.has("customId")  && !o.get("customId").isJsonNull()  ? o.get("customId").getAsString()  : null;
                if (o.has("mediaId") && !o.get("mediaId").isJsonNull()) {
                    t.applyCanonicalId(o.get("mediaId").getAsString());
                    // A provider id named "netease" is also how an installed netease
                    // plugin addresses songs; applyCanonicalId() maps that provider to
                    // the legacy built-in Source.NETEASE unconditionally, which would
                    // silently route a plugin-sourced queue entry back onto the old
                    // built-in client on every restart (see toTrackPlugin()).
                    if (persistedAsPlugin) t.source = Track.Source.PLUGIN;
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
                updateCover(cur, restoredCoverRevision);
                if (cur.source == Track.Source.LOCAL) {
                    loadLocalLyrics(cur);
                } else if (cur.source == Track.Source.NETEASE) {
                    loadNeteaseLyrics(cur);
                } else if (cur.source == Track.Source.PLUGIN) {
                    loadPluginLyrics(cur);
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
                        ? I18n.tr("toast.offline.searchEmpty")
                        : I18n.tr("toast.offline.searchCached"));
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
                            showToast(I18n.tr("toast.search.albumFailedPlugin"));
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
                    showToast(I18n.tr("toast.search.albumFailed"));
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
                            showToast(I18n.tr("toast.search.artistFailedPlugin"));
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
                    showToast(I18n.tr("toast.search.artistFailed"));
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
            long backendMs = Math.max(0L, backend.audiblePosition());
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
            long backendMs = Math.max(0L, backend.audiblePosition());
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
        skipUnplayable(playIndex, I18n.tr("toast.play.audioFailed"));
    }

    /** Stop waiting on an unplayable queue entry and advance once. The failure
     *  count is reset only by backend.onStarted, so a queue in which every entry
     *  is blocked makes at most one full pass instead of spinning forever. */
    private void skipUnplayable(int expectedIndex, String reason) {
        if (playIndex != expectedIndex) return;
        consecutivePlaybackFailures++;
        int failures = consecutivePlaybackFailures;
        if (queue.size() <= 1 || failures >= queue.size()) {
            stoppedLyricPositionMs = Math.max(0L, backend.audiblePosition());
            playbackStarted = false;
            playingIntent = false;
            backend.pause();
            post(() -> {
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
        playAt((playIndex + 1) % queue.size());
    }

    /** How many plain recommended playlists to ask the source for; the titled
     *  sections it also returns are extra and are not counted against this. */
    private volatile int homePlaylistLimit = 12;
    private boolean homeRequested;

    public void setHomePlaylistLimit(int value) {
        int next = Math.max(1, Math.min(100, value));
        if (next == homePlaylistLimit) return;
        homePlaylistLimit = next;
        // Startup seeds this before the first load; only a real change reloads.
        if (homeRequested) loadHome();
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
        post(() -> homeLoading.set(true));
        // Switching away and back fast would otherwise let the first source's
        // in-flight result land on top of the second one's.
        final long generation = homeGeneration.incrementAndGet();
        homeRequested = true;
        PluginManifest provider = primaryProviderWith(ProviderCapability.HOME);
        if (provider != null) {
            pluginProviders.home(provider.id, homePlaylistLimit)
                    .whenComplete((home, error) -> post(() -> {
                if (generation != homeGeneration.get()) return;
                if (!provider.id.equals(pluginRegistry.primaryProvider())) return;
                homeLoading.set(false);
                if (error != null) {
                    Logger.warn("plugin {} home failed: {}", provider.id, safeMessage(error));
                    clearHomeSections();
                    sourceRecommendPlaylists.set(Collections.<Playlist>emptyList());
                    sourceRecommendations.set(Collections.<Song>emptyList());
                    return;
                }
                ProviderHome value = home != null ? home : new ProviderHome();
                publishHomeSections(value.sections);
                sourceRecommendPlaylists.set(Collections.unmodifiableList(
                        new ArrayList<>(value.playlists)));
                sourceRecommendations.set(Collections.unmodifiableList(
                        new ArrayList<>(value.songs)));
            }));
            return;
        }
        clearHomeSections();
        // No source can serve home right now: publish the empty state instead of
        // leaving the previous source's recommendations up forever.
        sourceRecommendPlaylists.set(Collections.<Playlist>emptyList());
        sourceRecommendations.set(Collections.<Song>emptyList());
        if (onlineSourcesArePluginOnly()) {
            recommendations.set(Collections.<NeteaseSong>emptyList());
            recommendPlaylists.set(Collections.<NeteasePlaylist>emptyList());
            homeLoading.set(false);
            return;
        }
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
        catch (IllegalArgumentException error) { showToast(I18n.tr("toast.id.artist")); return; }
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
                showToast(I18n.tr("toast.artist.loadFailedPlugin"));
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
        if (onlineSourcesArePluginOnly()) { showToast(I18n.tr("toast.openWithPlugin.artist")); return; }
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
                    showToast(I18n.tr("toast.artist.loadFailed"));
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
        catch (IllegalArgumentException error) { showToast(I18n.tr("toast.id.album")); return; }
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
                showToast(I18n.tr("toast.album.loadFailedPlugin"));
                return;
            }
            albumName.set(album.name);
            albumCoverPath.set(album.artworkUrl);
            albumArtistName.set(album.artistName);
            albumArtistMediaId.set(album.artistMediaId);
            albumPublishYear.set(album.publishTimeMs > 0
                    ? new java.text.SimpleDateFormat(I18n.tr("album.yearFormat"), java.util.Locale.getDefault())
                            .format(new java.util.Date(album.publishTimeMs)) : "");
            sourceAlbumTracks.set(Collections.unmodifiableList(new ArrayList<>(album.songs)));
        }));
    }

    public void openAlbum(long albumId) {
        if (onlineSourcesArePluginOnly()) { showToast(I18n.tr("toast.openWithPlugin.album")); return; }
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
                            ? new java.text.SimpleDateFormat(I18n.tr("album.yearFormat"), java.util.Locale.getDefault())
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
                    showToast(I18n.tr("toast.album.loadFailed"));
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
        loadMediaPlaylist(mediaId, false);
    }

    /** Refresh the current playlist without clearing its rows or filter state. */
    public void refreshPlaylist() {
        if (Boolean.TRUE.equals(playlistLoading.peek())) return;
        String mediaId = openSourcePlaylistId.peek();
        if (mediaId != null && !mediaId.isEmpty()) loadMediaPlaylist(mediaId, true);
        else if (currentPlaylistId > 0) openPlaylist(currentPlaylistId);
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
            playlistTitle.set(resolved.name);
            playlistCoverPath.set(resolved.artworkUrl);
            playlistSubscribed.set(resolved.subscribed);
            playlistOwned.set(resolved.owned);
            playlistDeletable.set(resolved.deletable);
            long publishStartNanos = System.nanoTime();
            // 拖拽只在你自己的歌单、且音源支持保存顺序时提供；离线快照也是只读的 ——
            // 新顺序没有地方可送。
            sourcePlaylistReorderable.set(resolved.owned
                    && !Boolean.TRUE.equals(playlistOffline.peek())
                    && pluginHasCapability(id.provider(), ProviderCapability.PLAYLIST_REORDER));
            sourcePlaylistTracks.set(Collections.unmodifiableList(
                    new ArrayList<>(resolved.songs)));
            // 快照要把每首歌 JSON 往返一遍（实测千首 30-70ms），不该在行正在出现时
            // 占着渲染线程。
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
        if (onlineSourcesArePluginOnly()) { showToast(I18n.tr("toast.openWithPlugin.playlist")); return; }
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
                showToast(I18n.tr("toast.offline.playlistNone"));
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
            showToast(I18n.tr("toast.offline.playlistCached"));
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
                post(() -> { if (currentPlaylistId == playlistId) showToast(I18n.tr("toast.offline.playlistRefreshed")); });
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
                    showToast(I18n.tr(target ? "toast.playlist.subscribed" : "toast.playlist.unsubscribed"));
                    loadMyPlaylists();   // reflect the change in 我的
                } else {
                    playlistSubscribed.set(!target);   // revert the optimistic flip; no auto-retry
                }
            });
        });
    }

    private void toggleMediaPlaylistSubscribe(String mediaId) {
        if (!loggedIn.peek() || playlistOwned.peek()) return;
        setMediaPlaylistSubscribed(mediaId, !Boolean.TRUE.equals(playlistSubscribed.peek()));
    }

    /**
     * Collect / un-collect a playlist by id, whether or not it is the one currently
     * open. 我的's card menu acts on a grid entry rather than on the detail page, so
     * it cannot read the target's state from {@link #playlistSubscribed} — it passes
     * the desired state in instead.
     *
     * <p>The optimistic flip and its revert only apply while the affected playlist
     * is the open one; the toast and the 我的 refresh always run, since the result
     * matters to the grid even when no detail page is showing it.
     */
    public void setMediaPlaylistSubscribed(String mediaId, boolean subscribed) {
        // A collect is heavily risk-controlled upstream, so a second tap while one
        // is in flight is dropped rather than queued — same rule the detail page's
        // button has always followed.
        if (subscribeBusy) return;
        final MediaId id;
        try { id = MediaId.parse(mediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST); }
        catch (IllegalArgumentException error) { showToast(I18n.tr("toast.id.playlist")); return; }
        if (!pluginHasCapability(id.provider(), ProviderCapability.PLAYLIST_MUTATION)) return;
        subscribeBusy = true;
        if (id.toString().equals(openSourcePlaylistId.peek())) playlistSubscribed.set(subscribed);
        pluginProviders.mutatePlaylist(id, subscribed ? "subscribe" : "unsubscribe",
                        Collections.<MediaId>emptyList(), null)
                .whenComplete((success, error) -> post(() -> {
                    subscribeBusy = false;
                    boolean stillOpen = id.toString().equals(openSourcePlaylistId.peek());
                    if (error == null && Boolean.TRUE.equals(success)) {
                        showToast(I18n.tr(subscribed
                                ? "toast.playlist.subscribed" : "toast.playlist.unsubscribed"));
                        loadMyPlaylists();
                    } else {
                        if (stillOpen) playlistSubscribed.set(!subscribed);
                        showToast(I18n.tr("toast.actionFailed", safeMessage(error)));
                    }
                }));
    }

    /** Per-source slices of 我的, keyed by provider id. Main thread only. */
    private final Map<String, List<Playlist>> myPlaylistsBySource = new LinkedHashMap<>();
    /** Provider order the current aggregation publishes in (primary first). */
    private List<String> myPlaylistSourceOrder = Collections.emptyList();
    /** Discards results of a superseded aggregation (source enabled/disabled/switched). */
    private final AtomicLong myPlaylistsGeneration = new AtomicLong();
    private int myPlaylistsPending;

    /** Load the signed-in user's playlists (favorites + created) from every enabled
     *  source, not just the primary one: sources keep independent accounts, and a
     *  playlist stays openable by its own provider regardless of which one is primary. */
    public void loadMyPlaylists() {
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
            post(() -> {
                myPlaylistSourceOrder = order;
                // A source that is gone (disabled/removed) must not keep contributing.
                myPlaylistsBySource.keySet().retainAll(order);
                myPlaylistsPending = order.size();
                publishMyPlaylists();
            });
            for (PluginManifest manifest : providers) {
                final String providerId = manifest.id;
                final String providerName = manifest.name;
                pluginProviders.userPlaylists(providerId, 100)
                        .whenComplete((playlists, error) -> post(() -> {
                            if (generation != myPlaylistsGeneration.get()) return;
                            myPlaylistsPending--;
                            if (error != null) {
                                Logger.warn("plugin {} user playlists failed: {}", providerId,
                                        safeMessage(error));
                                myPlaylistsBySource.remove(providerId);
                            } else {
                                for (Playlist playlist : playlists) {
                                    playlist.sourceName = providerName;
                                }
                                myPlaylistsBySource.put(providerId, playlists);
                            }
                            publishMyPlaylists();
                            // A signed-out source simply reports an error, so only
                            // complain once every source failed while signed in.
                            if (myPlaylistsPending == 0 && myPlaylistsBySource.isEmpty()
                                    && Boolean.TRUE.equals(loggedIn.peek())) {
                                showToast(I18n.tr("toast.playlist.loadFailedPlugin"));
                            }
                        }));
            }
            return;
        }
        myPlaylistsGeneration.incrementAndGet();
        post(() -> {
            myPlaylistSourceOrder = Collections.emptyList();
            myPlaylistsBySource.clear();
            myPlaylistsPending = 0;
            publishMyPlaylists();
        });
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

    /** Flatten {@link #myPlaylistsBySource} into 我的, primary source first. Called
     *  after every per-source completion so the primary's playlists appear without
     *  waiting on a slow secondary source. */
    private void publishMyPlaylists() {
        List<Playlist> merged = new ArrayList<>();
        for (String providerId : myPlaylistSourceOrder) {
            List<Playlist> slice = myPlaylistsBySource.get(providerId);
            if (slice != null) merged.addAll(slice);
        }
        sourceMyPlaylists.set(Collections.unmodifiableList(merged));
        playlistCount.set(merged.size());
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
            showToast(I18n.tr("toast.offline.playlistsCached"));
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
                            showToast(I18n.tr("toast.playlist.created"));
                            loadMyPlaylists();
                        } else showToast(I18n.tr("toast.playlist.createFailedReason", safeMessage(error)));
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
                        showToast(I18n.tr("toast.playlist.created"));
                        loadMyPlaylists();
                    });
                } else {
                    showToast(I18n.tr("toast.playlist.createFailed"));
                }
            } catch (Throwable e) {
                Logger.warn("create playlist failed: {}", e.getMessage());
                showToast(I18n.tr("toast.playlist.createFailed"));
            }
        });
    }

    /** Set a playlist's cover to a local image file, then refresh the detail view
     *  (and 我的, whose cards also show it). {@code playlistKey} is the open
     *  playlist's canonical media id, or a leftover numeric netease id.
     *  Owned-playlist enforcement lives in the QML (same as the delete button —
     *  {@code playlistOwned}), not here. */
    public void setPlaylistCover(String playlistKey, String localImagePath) {
        if (playlistKey == null || localImagePath == null) return;
        final String key = playlistKey.trim();
        final String path = localImagePath.trim();
        if (key.isEmpty() || path.isEmpty()) return;
        worker.submit(() -> {
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

    /** Set a playlist's cover from raw image bytes — Android's native gallery picker
     *  hands over a {@code content://} URI with no filesystem path to read, so the
     *  host reads it itself and passes the bytes straight through. */
    public void setPlaylistCoverBytes(String playlistKey, byte[] data, String filename) {
        if (playlistKey == null || data == null) return;
        final String key = playlistKey.trim();
        if (key.isEmpty()) return;
        worker.submit(() -> uploadPlaylistCover(key, data, filename == null ? "cover.jpg" : filename));
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
        // A local playlist has no server to upload to: its cover is a file we
        // keep ourselves. Same entry point either way, so both hosts' pickers
        // (desktop file chooser, Android gallery bytes) work unchanged.
        if (LocalPlaylist.isLocalPlaylistId(playlistKey)) {
            storeLocalPlaylistCover(playlistKey, data, filename);
            return;
        }
        if (playlistKey.indexOf(':') >= 0) {
            uploadPluginPlaylistCover(playlistKey, data, filename);
            return;
        }
        long playlistId;
        try {
            playlistId = Long.parseLong(playlistKey);
        } catch (NumberFormatException ignored) {
            showToast(I18n.tr("toast.id.playlist"));
            return;
        }
        if (uid == 0 || playlistId == 0) return;
        try {
            long imgId = netease.uploadImage(data, filename);
            boolean ok = imgId != 0 && netease.updatePlaylistCover(playlistId, imgId);
            post(() -> {
                if (ok) {
                    showToast(I18n.tr("toast.cover.updated"));
                    if (currentPlaylistId == playlistId) openPlaylist(playlistId);
                    loadMyPlaylists();
                } else {
                    showToast(I18n.tr("toast.cover.updateFailed"));
                }
            });
        } catch (Throwable e) {
            Logger.warn("set playlist cover {} failed: {}", playlistId, e.getMessage());
            showToast(I18n.tr("toast.cover.updateFailed"));
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
        if (!pluginHasCapability(id.provider(), ProviderCapability.PLAYLIST_COVER)) {
            showToast(I18n.tr("toast.cover.updateFailed"));
            return;
        }
        pluginProviders.setPlaylistCover(id, data, filename, mimeFromFilename(filename))
                .whenComplete((success, error) -> post(() -> {
                    if (error == null && Boolean.TRUE.equals(success)) {
                        showToast(I18n.tr("toast.cover.updated"));
                        // refreshPlaylist(), not openMediaPlaylist(): the user is
                        // looking at this playlist right now, and a full open clears
                        // the rows and the in-playlist filter text before refetching,
                        // so the list blinks out and their filter is lost just to
                        // pick up one new artwork url.
                        if (id.toString().equals(openSourcePlaylistId.peek())) {
                            refreshPlaylist();
                        }
                        loadMyPlaylists();
                    } else {
                        showToast(I18n.tr("toast.cover.updateFailed"));
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
        this.coverPicker = p;
    }

    /** QML calls this to launch the platform picker for the currently open playlist.
     *  Android reads the picked image's bytes and calls {@link #setPlaylistCoverBytes};
     *  desktop passes the selected local path to {@link #setPlaylistCover}. */
    public void pickPlaylistCover() {
        String sourceId = openSourcePlaylistId.peek();
        if (sourceId != null && !sourceId.isEmpty()) {
            pickPlaylistCover(sourceId);
            return;
        }
        if (currentPlaylistId != 0L) pickPlaylistCover(Long.toString(currentPlaylistId));
    }

    public void pickPlaylistCover(String playlistKey) {
        if (playlistKey == null || playlistKey.trim().isEmpty()) return;
        final String key = playlistKey.trim();
        CoverPicker p = coverPicker;
        if (p != null) onMain(() -> p.pick(key));
    }

    /** Delete a playlist owned by the user, then refresh 我的. */
    public void deletePlaylist(long playlistId) {
        if (uid == 0 || playlistId == 0) return;
        worker.submit(() -> {
            try {
                if (netease.deletePlaylist(playlistId)) {
                    post(() -> {
                        showToast(I18n.tr("toast.playlist.deleted"));
                        loadMyPlaylists();
                    });
                } else {
                    showToast(I18n.tr("toast.playlist.deleteFailed"));
                }
            } catch (Throwable e) {
                Logger.warn("delete playlist {} failed: {}", playlistId, e.getMessage());
                showToast(I18n.tr("toast.playlist.deleteFailed"));
            }
        });
    }

    public void deleteMediaPlaylist(String playlistMediaId) {
        final MediaId id;
        try { id = MediaId.parse(playlistMediaId).requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST); }
        catch (IllegalArgumentException error) { showToast(I18n.tr("toast.id.playlist")); return; }
        pluginProviders.mutatePlaylist(id, "delete", Collections.<MediaId>emptyList(), null)
                .whenComplete((success, error) -> post(() -> {
                    if (error == null && Boolean.TRUE.equals(success)) {
                        showToast(I18n.tr("toast.playlist.deleted"));
                        loadMyPlaylists();
                    } else showToast(I18n.tr("toast.playlist.deleteFailedReason", safeMessage(error)));
                }));
    }

    /** Add a track to one of the user's playlists (from a song's long-press menu). */
    public void addToPlaylist(long playlistId, long songId) {
        if (uid == 0 || playlistId == 0 || songId == 0) return;
        worker.submit(() -> {
            try {
                boolean ok = netease.manipulatePlaylistTracks(playlistId, songId, true);
                showToast(I18n.tr(ok ? "toast.playlist.songAdded" : "toast.playlist.addFailed"));
            } catch (Throwable e) {
                Logger.warn("add track {} -> playlist {} failed: {}", songId, playlistId, e.getMessage());
                showToast(I18n.tr("toast.playlist.addFailed"));
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
            showToast(I18n.tr("toast.id.media"));
            return;
        }
        pluginProviders.mutatePlaylist(playlistId, add ? "add" : "remove",
                        Collections.singletonList(songId), null)
                .whenComplete((success, error) -> post(() -> {
                    if (error == null && Boolean.TRUE.equals(success)) {
                        showToast(I18n.tr(add ? "toast.playlist.songAdded" : "toast.playlist.songRemoved"));
                        if (refreshDetail && playlistId.toString().equals(openSourcePlaylistId.peek())) {
                            dropOpenSourcePlaylistTrack(songId.toString());
                        }
                    } else showToast(I18n.tr(add ? "toast.playlist.addFailedReason" : "toast.playlist.removeFailedReason", safeMessage(error)));
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
        List<Song> current = sourcePlaylistTracks.peek();
        if (current == null || current.isEmpty()) return;
        List<Song> remaining = new ArrayList<>(current.size());
        for (Song song : current) {
            if (!songMediaId.equals(song.id)) remaining.add(song);
        }
        if (remaining.size() == current.size()) return;
        sourcePlaylistTracks.set(Collections.unmodifiableList(remaining));
        // 离线快照要跟着改，否则没网重开时那首歌又回来了。JSON 往返对大歌单不便宜，
        // 所以放 worker。
        final String playlistId = openSourcePlaylistId.peek();
        worker.submit(() -> {
            Playlist cached = mediaPlaylistCacheIndex.get(playlistId);
            if (cached == null) return;
            if (!cached.songs.removeIf(song -> songMediaId.equals(song.id))) return;
            cached.trackCount = cached.songs.size();
            mediaPlaylistCacheIndex.upsert(cached);
            mediaPlaylistCacheIndex.save();
        });
    }

    /**
     * Move a row in the open source playlist while a drag is being applied. Local
     * only: {@link #commitSourcePlaylistOrder} is what reaches the provider, so the
     * whole gesture costs one network call rather than one per row crossed.
     */
    public void moveSourcePlaylistTrack(int from, int to) {
        if (!Boolean.TRUE.equals(sourcePlaylistReorderable.peek())) return;
        List<Song> current = sourcePlaylistTracks.peek();
        if (current == null) return;
        if (from < 0 || from >= current.size() || to < 0 || to >= current.size()) return;
        if (from == to) return;
        List<Song> reordered = new ArrayList<>(current);
        reordered.add(to, reordered.remove(from));
        sourcePlaylistTracks.set(Collections.unmodifiableList(reordered));
    }

    /** True while a reorder is on its way to the provider. */
    private boolean sourceOrderSaving;
    /** Set when a drag lands while the previous one is still in flight, so the
     *  final arrangement is sent rather than an intermediate one. */
    private boolean sourceOrderPending;

    /** Send the open source playlist's current arrangement to its provider. */
    public void commitSourcePlaylistOrder() {
        if (!Boolean.TRUE.equals(sourcePlaylistReorderable.peek())) return;
        if (sourceOrderSaving) { sourceOrderPending = true; return; }
        final String playlistMediaId = openSourcePlaylistId.peek();
        if (playlistMediaId == null || playlistMediaId.isEmpty()) return;
        List<Song> rows = sourcePlaylistTracks.peek();
        if (rows == null || rows.isEmpty()) return;
        final MediaId playlistId;
        final List<MediaId> ordered = new ArrayList<>(rows.size());
        try {
            playlistId = MediaId.parse(playlistMediaId)
                    .requireKind(dev.t1m3.qplayer.media.MediaKind.PLAYLIST);
            for (Song song : rows) {
                ordered.add(MediaId.parse(song.id)
                        .requireKind(dev.t1m3.qplayer.media.MediaKind.SONG));
            }
        } catch (IllegalArgumentException error) {
            showToast(I18n.tr("toast.id.media"));
            return;
        }
        sourceOrderSaving = true;
        pluginProviders.reorderPlaylist(playlistId, ordered)
                .whenComplete((success, error) -> post(() -> {
                    sourceOrderSaving = false;
                    boolean stillOpen = playlistId.toString().equals(openSourcePlaylistId.peek());
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
                    Logger.warn("playlist {} reorder failed: {}", playlistId, safeMessage(error));
                    showToast(I18n.tr("toast.playlist.orderFailedReason", safeMessage(error)));
                    // 屏幕上的行已经和服务器不一致了，与其让用户看着一个没保存的顺序，
                    // 不如重新读一遍。
                    if (stillOpen) loadMediaPlaylist(playlistId.toString(), true);
                }));
    }

    /** {@link #dropOpenSourcePlaylistTrack} for the built-in netease playlist view. */
    private void dropOpenPlaylistTrack(long songId) {
        List<NeteaseSong> current = playlistTracks.peek();
        if (current == null || current.isEmpty()) return;
        List<NeteaseSong> remaining = new ArrayList<>(current.size());
        for (NeteaseSong song : current) {
            if (song.id != songId) remaining.add(song);
        }
        if (remaining.size() == current.size()) return;
        playlistTracks.set(Collections.unmodifiableList(remaining));
    }

    /** Remove a track from the currently open playlist (the "从此歌单移除" menu
     *  entry only appears there). Reads the open id internally so QML needn't
     *  round-trip a 64-bit playlist id back through a numeric property. Drops the
     *  row locally rather than re-fetching, for the reason in
     *  {@link #dropOpenSourcePlaylistTrack}. */
    public void removeFromCurrentPlaylist(long songId) {
        final long playlistId = currentPlaylistId;
        if (uid == 0 || playlistId == 0 || songId == 0) return;
        worker.submit(() -> {
            try {
                boolean ok = netease.manipulatePlaylistTracks(playlistId, songId, false);
                post(() -> {
                    showToast(I18n.tr(ok ? "toast.playlist.songRemoved" : "toast.playlist.removeFailed"));
                    if (ok && currentPlaylistId == playlistId) dropOpenPlaylistTrack(songId);
                });
            } catch (Throwable e) {
                Logger.warn("remove track {} <- playlist {} failed: {}", songId, playlistId, e.getMessage());
                showToast(I18n.tr("toast.playlist.removeFailed"));
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
        Track current = currentTrack();
        currentLiked.set(current != null && pluginLikedSet.contains(current.canonicalId()));
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
                } else showToast(I18n.tr(target ? "toast.like.failed" : "toast.unlike.failed"));
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
                            ? (I18n.tr(target ? "toast.like.failed" : "toast.unlike.failed")) : I18n.tr("toast.signInRequired"));
                }
            } catch (Throwable e) {
                Logger.warn("like toggle failed: {}", e.getMessage());
                post(() -> toast.set(I18n.tr("toast.like.failedReason", e.getMessage())));
            }
        });
    }

    /** Current queue track (the playback source of truth) or null. Safe off the
     *  render thread — reads the plain playIndex, not the lagging Property. Used by
     *  the host media service to build the notification / session metadata. */
    /** 宿主自己的保存对话框（插件永远拿不到文件系统路径）。2.1.1 的宿主用它导出音频。 */
    @FunctionalInterface
    public interface FileSavePicker {
        java.util.concurrent.CompletableFuture<Boolean> save(
                String suggestedFileName,
                String mimeType,
                java.util.function.BiConsumer<String, java.util.function.Consumer<Boolean>> writer);
    }

    private volatile FileSavePicker fileSavePicker;
    private volatile String hostPlatform = "";

    public void setFileSavePicker(FileSavePicker picker) {
        this.fileSavePicker = picker;
    }

    /** 宿主标识（"desktop" / "android"），2.1.1 的宿主启动时设置。 */
    public void setHostPlatform(String platform) {
        if (platform != null && !platform.isEmpty()) hostPlatform = platform;
    }

    public String hostPlatform() {
        return hostPlatform;
    }

    /**
     * 宿主用的一个原子快照（2026-11-15 ✓ 2.1.1 的桌面宿主按它取"当前曲目 + 播放态 + 进度"）。
     *
     * <p>这里用**你现有**的状态组装（currentTrack / isPlaying / position / duration）——
     * 不引入上游那套 playbackSession 内部结构 ✓ 语义等价：同一个锁内取一遍，宿主拿到的一致。
     */
    public PlaybackSnapshot playbackSnapshot() {
        Track track = currentTrack();
        PlaybackSnapshot.TrackMetadata metadata = null;
        if (track != null) {
            metadata = new PlaybackSnapshot.TrackMetadata(
                    track.canonicalId(), track.title, track.artist, track.album,
                    track.filePath, track.coverUrl, null, track.durationMs, null);
        }
        return new PlaybackSnapshot(metadata, isPlaying(), position(), duration(), 0L, 0L);
    }

    /** 只有当给定曲目**仍是当前曲**时才跳转（2.1.1 宿主用它，避免异步跳错曲）。 */
    public void seekIfCurrent(String canonicalId, long positionMs) {
        Track track = currentTrack();
        if (track != null && canonicalId != null && canonicalId.equals(track.canonicalId())) {
            seek(positionMs);
        }
    }

    /** 异步关闭（2.1.1 宿主在窗口关闭时调它，避免卡住 UI 线程）。 */
    public java.util.concurrent.CompletableFuture<Void> shutdownAsync() {
        return java.util.concurrent.CompletableFuture.runAsync(this::shutdown);
    }

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
        // 与 toggle() 一样直接暂停：从前这条路会把 backend.pause() 推迟到音量渐变
        // 走完（settings.fade），锁屏/通知栏的暂停因此慢将近一秒。
        backend.pause();
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
        if (!backend.isPlaying()) backend.resume();
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
        return message == null || message.trim().isEmpty() ? I18n.tr("error.unknown") : message;
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
            webLoginError.set(I18n.tr("login.error.noWebView"));
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
                webLoginError.set(I18n.tr("login.error.noPlugin"));
            }
        } catch (Throwable e) {
            Logger.warn("web login launcher failed: {}", safeMessage(e));
            webLoginBusy.set(false);
            webLoginError.set(I18n.tr("login.error.openFailed"));
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
                ? I18n.tr("login.error.openSourceFailed") : message.trim();
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
                webLoginError.set(I18n.tr("login.error.noLoginPlugin"));
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
                    showToast(I18n.tr("toast.login.success"));
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
                            ? I18n.tr("login.error.cookieFailed") : reason);
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
                // The signed-in source may not be the primary one, and only this
                // list can show it.
                refreshSourceAccounts();
                showToast(I18n.tr("toast.login.success"));
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
                        ? I18n.tr("login.error.failed") : challenge.message);
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
                        showToast(I18n.tr("toast.keystore.restored"));
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
                    showToast(I18n.tr("toast.logout.failed", safeMessage(error)));
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
        if (onlineSourcesArePluginOnly()) {
            legacyCredentialMigrationGeneration.incrementAndGet();
            legacyCredentialMigrationAttempted = true;
            netease.logout();
            clearPublishedAccount();
            return;
        }
        netease.logout();
        clearPublishedAccount();
        showToast(I18n.tr("toast.logout.done"));
    }

    private void clearPublishedAccount() {
        uid = 0;
        likedSet.clear();
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
        // Same reason: every local-playlist edit persists through the worker, and
        // shutdownNow() below discards whatever is still queued. A song added or a
        // row dragged immediately before quitting would be lost.
        localPlaylistStore.save();
        postWorker.shutdownNow();
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
        showToast(I18n.tr("toast.cache.cleared"));
    }
}
