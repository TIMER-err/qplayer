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

    private AppDirs() {
    }

    /** Override the base directory (Android host calls this with filesDir). */
    public static void setBase(String dir) {
        base = dir;
    }

    /** Absolute path of the data directory. Callers create it as needed. */
    public static String base() {
        return base;
    }
}
