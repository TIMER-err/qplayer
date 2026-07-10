package dev.t1m3.qplayer.store;

import java.io.File;

/**
 * Resolves the per-user data directory for cookies / recent / config.
 *
 * <p>Desktop defaults to {@code ~/.qplayer}. The Android host injects its
 * {@code context.getFilesDir()} at startup via {@link #setBase(String)}, since
 * there is no writable home directory there.
 */
public final class AppDirs {

    private static volatile String base =
            new File(System.getProperty("user.home", "."), ".qplayer").getAbsolutePath();

    /** Cache root (local library covers/lyrics + netease audio/image/lyric cache).
     *  Defaults to {@link #base}; desktop users can point it elsewhere (e.g. a
     *  bigger/faster disk) without moving settings, cookies, or the search/queue
     *  state that also live under {@link #base}. */
    private static volatile String cacheBase = base;

    private AppDirs() {
    }

    /** Override the base directory (Android host calls this with filesDir). */
    public static void setBase(String dir) {
        base = dir;
        cacheBase = dir;
    }

    /** Absolute path of the data directory. Callers create it as needed. */
    public static String base() {
        return base;
    }

    /** Override just the cache root, independent of {@link #base}. */
    public static void setCacheBase(String dir) {
        cacheBase = (dir == null || dir.trim().isEmpty()) ? base : dir;
    }

    /** Absolute path of the cache root. Callers create it as needed. */
    public static String cacheBase() {
        return cacheBase;
    }
}
