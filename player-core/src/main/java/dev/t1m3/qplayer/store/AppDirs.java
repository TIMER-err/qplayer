package dev.t1m3.qplayer.store;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Owns QPlayer's complete on-disk layout.
 *
 * <p>Desktop defaults to {@code ~/.qplayer}; Android injects its private files
 * directory. Persistent files are grouped by purpose instead of accumulating in
 * the root:
 *
 * <pre>
 * config/       user preferences
 * credentials/  login credentials/cookies
 * state/        queue, custom playlist, search history, lyric offsets
 * indexes/      rebuildable offline metadata indexes
 * runtime/      single-instance lock/port
 * plugins/      installed source plugins, one version directory per plugin
 * cache/        audio, artwork, lyrics and local-library extraction cache
 * logs/         rolling desktop logs
 * </pre>
 *
 * <p>{@link #migrateLegacyLayout()} moves files from the legacy flat layout in
 * place. Moves are same-filesystem renames, so even a large cache is not copied.
 */
public final class AppDirs {

    private static volatile String base = initialBase();
    private static volatile String cacheBase = base;

    private AppDirs() {
    }

    /** Desktop/dev override for isolated profiles and parallel instances. Main
     *  accepts -D arguments before SingleInstance first touches this class, so a
     *  packaged launch can use the same knob as Maven/Java. */
    private static String initialBase() {
        String override = System.getProperty("qplayer.data.dir", "").trim();
        if (!override.isEmpty()) return new File(override).getAbsolutePath();
        return new File(System.getProperty("user.home", "."), ".qplayer").getAbsolutePath();
    }

    /** Override the data directory (Android host calls this with filesDir). */
    public static synchronized void setBase(String dir) {
        base = dir;
        cacheBase = dir;
    }

    public static String base() {
        return base;
    }

    /** Override just the cache root, independent of settings/cookies/state. */
    public static synchronized void setCacheBase(String dir) {
        cacheBase = (dir == null || dir.trim().isEmpty()) ? base : dir;
        migrateCacheLayout();
    }

    public static String cacheBase() {
        return cacheBase;
    }

    public static Path configFile(String name) {
        Path target = basePath().resolve("config").resolve(name);
        if ("settings.json".equals(name)) moveBase("settings.json", target);
        return target;
    }

    public static Path credentialsFile(String name) {
        Path target = basePath().resolve("credentials").resolve(name);
        if ("netease-cookies.json".equals(name)) moveBase("netease-cookies.json", target);
        StorageFiles.restrictOwnerOnly(target);
        return target;
    }

    public static Path stateFile(String name) {
        Path target = basePath().resolve("state").resolve(name);
        if ("queue.json".equals(name)) moveBase("queue.json", target);
        else if ("custom-playlist.json".equals(name)) moveBase("custom_playlist.json", target);
        else if ("lyric-offsets.json".equals(name)) moveBase("lyric_offsets.json", target);
        else if ("recent-songs.json".equals(name)) moveBase("recent_songs.json", target);
        else if ("search-history.json".equals(name)) moveBase("search_history.json", target);
        return target;
    }

    public static Path indexFile(String name) {
        Path target = basePath().resolve("indexes").resolve(name);
        if ("songs.json".equals(name)) moveBase("song_meta_index.json", target);
        else if ("playlists.json".equals(name)) moveBase("playlist_cache_index.json", target);
        return target;
    }

    public static Path runtimeFile(String name) {
        Path target = basePath().resolve("runtime").resolve(name);
        if ("instance.lock".equals(name)) moveBase("instance.lock", target);
        else if ("instance.port".equals(name)) moveBase("instance.port", target);
        return target;
    }

    public static Path logsDir() {
        return basePath().resolve("logs");
    }

    public static Path pluginsDir() {
        return basePath().resolve("plugins");
    }

    /** Root containing every cache category, including {@code local/}. */
    public static Path cacheDir() {
        return cacheBasePath().resolve("cache");
    }

    public static Path localCacheDir() {
        migrateCacheLayout();
        return cacheDir().resolve("local");
    }

    public static Path updatesDir() {
        return cacheDir().resolve("updates");
    }

    /** Legacy flat-root path, used only by format conversions such as search history. */
    public static Path legacyFile(String name) {
        return basePath().resolve(name);
    }

    /** Idempotently migrate every known file from the old flat layout. */
    public static synchronized void migrateLegacyLayout() {
        moveBase("settings.json", configFile("settings.json"));
        moveBase("netease-cookies.json", credentialsFile("netease-cookies.json"));
        moveBase("queue.json", stateFile("queue.json"));
        moveBase("custom_playlist.json", stateFile("custom-playlist.json"));
        moveBase("lyric_offsets.json", stateFile("lyric-offsets.json"));
        moveBase("recent_songs.json", stateFile("recent-songs.json"));
        // A short-lived build used JSON at the root; the older TXT format is
        // converted by PlayerController because a rename alone would be invalid.
        moveBase("search_history.json", stateFile("search-history.json"));
        moveBase("song_meta_index.json", indexFile("songs.json"));
        moveBase("playlist_cache_index.json", indexFile("playlists.json"));
        moveBase("instance.lock", runtimeFile("instance.lock"));
        moveBase("instance.port", runtimeFile("instance.port"));
        migrateCacheLayout();
    }

    private static void moveBase(String oldName, Path target) {
        StorageFiles.moveIfAbsent(basePath().resolve(oldName), target);
    }

    private static synchronized void migrateCacheLayout() {
        StorageFiles.moveIfAbsent(cacheBasePath().resolve("local-cache"),
                cacheDir().resolve("local"));
        StorageFiles.moveIfAbsent(cacheBasePath().resolve("updates"),
                cacheDir().resolve("updates"));
    }

    private static Path basePath() {
        return Paths.get(base);
    }

    private static Path cacheBasePath() {
        return Paths.get(cacheBase);
    }
}
