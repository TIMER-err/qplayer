package dev.t1m3.qplayer.android.app;

import dev.t1m3.qplayer.android.playback.OverlayLyricWindow;
import dev.t1m3.qplayer.android.playback.PlaybackService;
import dev.t1m3.qplayer.android.security.AndroidKeystoreKeyProtector;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Process;

import dev.t1m3.qplayer.util.Logger;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.CredentialKeyProtection;
import dev.t1m3.qplayer.android.resources.FileResourceLoader;
import dev.t1m3.qplayer.resources.DiskDecompressedResourceCache;

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

    private DiskDecompressedResourceCache sceneResources;

    /** Assets and expanded fonts belong to the process, not to a recreated Activity. */
    public synchronized DiskDecompressedResourceCache sceneResources() {
        if (sceneResources == null) {
            sceneResources = new DiskDecompressedResourceCache(
                    new FileResourceLoader(getAssets()),
                    new java.io.File(getCacheDir(), "expanded-resources").toPath());
        }
        return sceneResources;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Install credential protection at process start, before an Activity or a
        // restarted service can initialize the delayed credential-migration reader.
        AppDirs.setBase(getFilesDir().getAbsolutePath());
        CredentialKeyProtection.install(new AndroidKeystoreKeyProtector());

        // **三个宿主接缝**（2026-11-15 ✓ 用户日志「打开输出失败：没有可用的输出设备」就是漏了它们 ✗）
        //   解码层：media3（提取器 + MediaCodec）
        //   输出层：AudioTrack
        //   模型层：AndroidModelFetcher（离线即时失败）
        // 引擎（PlaybackEngine）靠这两个接缝才能开出输出流；不装就只能在日志里报错。
        installAudioHostSeams();
        installModelHostSeam();

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

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        OverlayLyricWindow.onConfigurationChanged();
    }

    /** 解码 + 输出两个接缝。装失败只记日志（App 仍能起，引擎会如实报"没有可用的输出设备"）。 */
    private void installAudioHostSeams() {
        try {
            dev.t1m3.qplayer.android.audio.decode.AndroidDecoder.install(this);
            dev.t1m3.qplayer.audio.output.OutputDevices.install(
                    new dev.t1m3.qplayer.android.audio.output.AndroidOutputProvider(this));
            Logger.info("音频宿主接缝已装配：Decoder=media3（提取器 + MediaCodec）, OutputDevices=AudioTrack");
        } catch (Throwable t) {
            Logger.error("音频宿主接缝装配失败：{}", t.toString());
        }
    }

    /** 模型字节从哪来（权重不打包进成品，按需下载）。 */
    private void installModelHostSeam() {
        try {
            dev.t1m3.qplayer.android.audio.model.AndroidModelFetcher.install(this);
            Logger.info("模型下载接缝已装配：AndroidModelFetcher");
        } catch (Throwable t) {
            Logger.error("模型下载接缝装配失败：{}", t.toString());
        }
    }
}
