package dev.t1m3.qplayer.android.app;

import dev.t1m3.qplayer.android.playback.PlaybackService;
import dev.t1m3.qplayer.android.security.AndroidKeystoreKeyProtector;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Process;

import dev.t1m3.qplayer.util.Logger;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.CredentialKeyProtection;

/**
 * Installs a process-wide uncaught-exception handler whose only job is to
 * best-effort cancel the playback notification before the crash takes the
 * process down. {@link PlaybackService#onDestroy()} does the same cleanup, but
 * has no guarantee of running when the crash originates outside its own
 * thread/lifecycle (render thread, a worker, another component entirely) —
 * without this, the foreground-service notification is left stuck in the
 * status bar until the user force-clears the app.
 */
public final class QPlayerApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Install credential protection at process start, before an Activity or a
        // restarted service can initialize the delayed credential-migration reader.
        AppDirs.setBase(getFilesDir().getAbsolutePath());
        AppDirs.migrateLegacyLayout();
        CredentialKeyProtection.install(new AndroidKeystoreKeyProtector());

        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                Logger.error("uncaught exception on {}: {}", thread.getName(), ex.toString());
            } catch (Throwable ignored) {
            }
            try {
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(PlaybackService.NOTIF_ID);
            } catch (Throwable ignored) {
                // Best-effort — cleanup must never mask or interfere with the real crash.
            }
            if (previous != null) {
                previous.uncaughtException(thread, ex);
            } else {
                // No previous handler installed (unusual) — fall back to killing the
                // process ourselves so the crash doesn't silently hang.
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }
}
