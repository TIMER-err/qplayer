package dev.t1m3.qplayer.desktop.app;

import io.github.timer_err.qml4j.engine.QmlEngine;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.desktop.audio.DesktopAudioBackend;
import dev.t1m3.qplayer.desktop.graphics.DesktopColorExtractor;
import dev.t1m3.qplayer.desktop.library.DesktopMetadataReader;
import dev.t1m3.qplayer.desktop.library.LibraryWatcher;
import dev.t1m3.qplayer.desktop.media.DesktopMediaControls;
import dev.t1m3.qplayer.desktop.media.MacMediaControls;
import dev.t1m3.qplayer.desktop.media.MprisControls;
import dev.t1m3.qplayer.desktop.media.WindowsMediaControls;
import dev.t1m3.qplayer.desktop.resources.ClasspathResourceLoader;
import dev.t1m3.qplayer.desktop.resources.DiskCompiledSceneCache;
import dev.t1m3.qplayer.desktop.resources.DiskDecompressedResourceCache;
import dev.t1m3.qplayer.desktop.security.DesktopCredentialProtection;
import dev.t1m3.qplayer.desktop.settings.DesktopThemeMonitor;
import dev.t1m3.qplayer.desktop.settings.JsonSettingsStore;
import dev.t1m3.qplayer.desktop.tray.TrayController;
import dev.t1m3.qplayer.desktop.window.DesktopWindow;
import dev.t1m3.qplayer.desktop.window.PluginQmlWindows;
import dev.t1m3.qplayer.library.LibraryScanner;
import dev.t1m3.qplayer.lyric.skia.Fonts;
import dev.t1m3.qplayer.resources.CompressedResources;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import dev.t1m3.qplayer.settings.SettingsCore;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.util.Logger;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Shell32;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Desktop entry point (the LWJGL twin of {@code QPlayerActivity.onCreate}): wires
 * the platform-neutral {@link PlayerController} over the desktop audio / metadata
 * / color backends, builds the GLFW window + tray, loads {@code Main.qml} from the
 * shared QML tree on the classpath, then runs the main event loop until quit.
 *
 * <p>The render thread (GPU + Skija) is owned by {@link DesktopWindow} and can be
 * destroyed/respawned on minimize-to-tray; the controller, audio and settings live
 * here and survive, so playback continues while hidden.
 *
 * <p>On macOS, launch with {@code -XstartOnFirstThread}.
 */
public final class Main {

    /** True when running from a jpackage-produced bundle (the launcher sets this
     *  property), false on a plain `mvn exec:exec` dev run. */
    private static final boolean PACKAGED = System.getProperty("jpackage.app-path") != null;

    public static void main(String[] args) {
        long startupStartedNanos = System.nanoTime();
        // The jpackage launcher hands the command line straight to main() instead of
        // to the JVM (and drops -J flags), so pull the -Dkey=value ones back out
        // ourselves. Keeps the packaged app's launch knobs identical to a dev run:
        // -Dqplayer.gfx=vulkan, -Dqplayer.width/height=…, -Dqplayer.tray=false.
        for (String a : args) {
            if (!a.startsWith("-D")) continue;
            int eq = a.indexOf('=');
            if (eq > 2) System.setProperty(a.substring(2, eq), a.substring(eq + 1));
        }

        // Windows packaged launcher (GUI subsystem): no console on double-click, but
        // attach to the launching terminal's console so logs still stream there.
        // Before anything writes to stdout (log4j console appender resolves it).
        if (PACKAGED && System.getProperty("os.name", "").toLowerCase().contains("win")) {
            WinConsole.attachParentConsole();
        }

        // Single instance: if QPlayer is already running, raise its window and exit.
        // Checked before log4j inits so this short-lived second process never opens
        // the shared rolling log file. The activation target is wired once the window
        // exists (below).
        java.util.concurrent.atomic.AtomicReference<Runnable> onActivate =
                new java.util.concurrent.atomic.AtomicReference<>(() -> {});
        if (!SingleInstance.acquire(() -> onActivate.get().run())) {
            return;
        }

        // We own the instance lock, so no running process can still write the old
        // flat layout while it is moved. SingleInstance has already migrated just
        // its lock/port pair so this check itself also works across upgrades.
        AppDirs.migrateLegacyLayout();

        // Put the rolling log under the writable app data dir (~/.qplayer/logs) —
        // when installed to Program Files the working dir isn't writable, so a
        // CWD-relative logs/ would silently fail. Set before log4j2 first inits
        // (in Log4j2Sink below); log4j2.xml reads ${sys:qplayer.logs}.
        if (System.getProperty("qplayer.logs") == null) {
            System.setProperty("qplayer.logs", AppDirs.logsDir().toFile().getAbsolutePath());
        }

        // Route the shared player-core logger to log4j2 (colored console + rolling
        // file, config in log4j2.xml). Early in main so every later line lands in
        // the configured format.
        Logger.setSink(new Log4j2Sink());
        configureWindowsAppIdentity();
        DesktopCredentialProtection.install();

        ClasspathResourceLoader classpathResources = new ClasspathResourceLoader();
        DiskDecompressedResourceCache resources = new DiskDecompressedResourceCache(
                classpathResources, AppDirs.cacheDir().resolve("expanded-resources"));
        String currentVersion = appVersion();
        DiskCompiledSceneCache qmlCompilationCache = new DiskCompiledSceneCache(
                AppDirs.cacheDir().resolve("qml"),
                classpathResources.qmlFingerprint(currentVersion));

        // Platform backends (the desktop impls already exist).
        AudioBackend audio = new DesktopAudioBackend();
        MetadataReader reader = new DesktopMetadataReader();

        PlayerController controller = new PlayerController(audio, reader);
        controller.setColorExtractor(new DesktopColorExtractor());
        controller.setCurrentVersion(currentVersion);
        controller.setWebLoginLauncher((loginUrl, cookieUrl, credentialCookieName, providerName) ->
                DesktopWebLogin.open(loginUrl, cookieUrl, credentialCookieName, providerName,
                controller::completeWebLogin,
                controller::failWebLogin,
                controller::cancelWebLogin));

        // Settings: the catalog, the value plumbing and every side effect live in
        // player-core (shared with Android). The host contributes a store, the
        // platform id, the defaults only it knows, and the actions/live text its
        // own rows need.
        SettingsCore settings = new SettingsCore();
        settings.attach(controller);
        settings.setDefault("musicFolder",
                new File(System.getProperty("user.home", "."), "Music").getAbsolutePath());
        settings.setDefault("cacheFolder", AppDirs.cacheBase());
        boolean initialSystemDark = DesktopThemeMonitor.detectSystemDark();
        settings.setSystemDark(initialSystemDark);
        settings.registerAction("clearCache", controller::clearDiskCache);
        settings.registerAction("checkUpdate", controller::checkForUpdateManual);
        settings.registerAction("openRepo", () -> openUrl("https://github.com/TIMER-err/qplayer"));
        settings.registerAction("importPlugin", controller::requestPluginImport);
        settings.registerInfo("version", () -> "v" + controller.appVersion.peek());
        settings.registerInfo("cacheUsage", () -> controller.cacheSizeMB.peek() + " MB");
        JsonSettingsStore desktopStore = new JsonSettingsStore();
        settings.load(desktopStore, SettingsCatalog.DESKTOP);
        DesktopFilePicker.initializeLookAndFeel(settings.resolvedDarkValue());

        // Fonts for the host-drawn lyric renderer (the QML scene fonts are set on the
        // view in DesktopWindow.ensureView).
        Fonts.init(weight -> CompressedResources.load(resources,
                "fonts/PingFangSC-" + switch (weight) {
            case THIN -> "Thin";
            case LIGHT -> "Light";
            case REGULAR -> "Regular";
            case MEDIUM -> "Medium";
        } + ".otf"));
        byte[] qmlBytes = resources.load("Main.qml");
        if (qmlBytes == null) throw new IllegalStateException("Main.qml not found on classpath");
        String qml = new String(qmlBytes, StandardCharsets.UTF_8);

        QmlEngine engine = new QmlEngine();
        DesktopWindow window = new DesktopWindow(engine, qml, resources, controller, settings,
                qmlCompilationCache);
        PluginQmlWindows pluginWindows = new PluginQmlWindows(controller);
        window.setPluginQmlWindows(pluginWindows);
        controller.setPluginUiLauncher((pluginId, contributionId) ->
                window.postMainTask(() -> pluginWindows.open(pluginId, contributionId)));
        settings.onChange("darkMode", ignored -> {
            DesktopFilePicker.setDarkTheme(settings.resolvedDarkValue());
            window.refreshSystemChromeTheme();
        });
        DesktopThemeMonitor themeMonitor = new DesktopThemeMonitor(initialSystemDark, dark ->
                window.postRenderTask(() -> {
                    settings.setSystemDark(dark);
                    DesktopFilePicker.setDarkTheme(settings.resolvedDarkValue());
                    window.refreshSystemChromeTheme();
                }));
        // Desktop lyrics shares SettingsCore's store so its settings-page toggle
        // and tray toggle remain one value; window position uses adjacent host keys.
        window.setLyricSettingsStore(desktopStore);

        // Playback control runs on the main event loop (alive even while the render
        // thread is dead); back/exit folds the window to the tray.
        controller.setMainExecutor(window::postMainTask);
        controller.setExitListener(window::onExitRequested);
        // Open external links (the About page) in the system browser. The Android
        // host uses an ACTION_VIEW intent; on the desktop hand the URL to the OS
        // (no java.awt.Desktop, which needs a working desktop integration and can
        // block on some Linux setups).
        controller.setUrlOpener(Main::openUrl);
        // Pick this OS's own release asset (there's no .apk on a desktop release —
        // PlayerController's default matcher, unchanged for Android, would never
        // match anything here). "setup.exe" (not the plain .zip) so the in-app
        // downloader hands off to the real installer.
        controller.setAssetMatcher(name -> {
            String n = name.toLowerCase();
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) return n.endsWith("setup.exe");
            if (os.contains("mac")) return n.endsWith(".dmg");
            return n.endsWith(".appimage");
        });
        controller.setInstaller(urls -> downloadAndInstallUpdate(controller, window, urls));

        TrayController tray = new TrayController(controller, window, resources.load("app-icon.png"));
        window.setDesktopLyricStateListener(ignored -> tray.onDesktopLyricChanged());
        // Windows tray: hand it the multi-size .ico the installer also uses.
        tray.setIcoBytes(resources.load("app-icon.ico"));
        DesktopMediaControls systemMedia = createSystemMediaControls(controller, window);

        // PlayerController intentionally has one host callback (Android installs its
        // media-session service there). Combine the two desktop consumers here so
        // bringing up MPRIS never steals playback updates from the tray.
        DesktopMediaControls finalSystemMedia = systemMedia;
        controller.setPlaybackListener(() -> {
            tray.onPlaybackChanged();
            if (finalSystemMedia != null) finalSystemMedia.onPlaybackChanged();
        });

        window.init();
        // QML text fields already use DesktopWindow's GLFW clipboard bridge. Wire
        // controller-generated links to it too; without this sink desktop merely
        // displayed a success toast while nothing reached the Linux clipboard.
        controller.setClipboard(window::setClipboardText);

        // Platform pickers. Android keeps its existing ACTION_GET_CONTENT cover
        // flow; desktop uses a FlatMac-themed Swing chooser on Windows/Linux/macOS.
        // Marshal accepted paths back to the render thread before touching QML-
        // observable settings/controller state.
        settings.setDirectoryPicker((initialPath, onPicked) ->
                DesktopFilePicker.pickDirectory(initialPath,
                        selected -> window.postRenderTask(() -> onPicked.accept(selected))));
        controller.setCoverPicker(playlistId ->
                DesktopFilePicker.pickImage(selected -> window.postRenderTask(() ->
                        controller.setPlaylistCover(playlistId, selected))));
        controller.setPluginPicker(() -> DesktopFilePicker.pickPlugin(
                controller::inspectPluginPackage));

        // A second launch now surfaces this window instead of starting a new process.
        onActivate.set(() -> window.postMainTask(window::restoreFromTray));

        // Wire the music-folder change listener so Settings page edits trigger a rescan.
        // Must be wired after window.init() so postRenderTask() is available.
        String initialFolder = settings.str("musicFolder");
        // Watches the folder tree so adding/removing files is picked up on its own —
        // without this, a rescan only ever ran when the user re-touched the Settings
        // folder field or restarted the app. Each rescan the watcher triggers reuses
        // LibraryScanner's per-file cache, so it stays cheap even on a large library.
        LibraryWatcher watcher = new LibraryWatcher(
                () -> startLibraryScan(controller, reader, window, settings.str("musicFolder")));
        settings.onChange("musicFolder", v -> {
            String folder = String.valueOf(v);
            startLibraryScan(controller, reader, window, folder);
            startLibraryWatcher(watcher, folder);
        });

        // Cache root (local-library covers/lyrics + netease audio/image/lyric cache).
        // controller.diskCache was already constructed against the AppDirs default
        // above, so a persisted custom folder must be re-applied here before anything
        // reads/writes through it; a later edit re-points it and rescans so the
        // change is visible without a restart.
        String initialCacheFolder = settings.str("cacheFolder");
        if (!initialCacheFolder.isEmpty()) {
            AppDirs.setCacheBase(initialCacheFolder);
            controller.diskCache.setBaseDir(initialCacheFolder);
        }
        settings.onChange("cacheFolder", v -> {
            String folder = String.valueOf(v);
            AppDirs.setCacheBase(folder);
            controller.diskCache.setBaseDir(folder);
            startLibraryScan(controller, reader, window, settings.str("musicFolder"));
        });

        boolean systemMediaEnabled =
                !"false".equals(System.getProperty("qplayer.systemMedia", "true"))
                // Keep the Linux-specific switch accepted by the first MPRIS build.
                && (!(systemMedia instanceof MprisControls)
                    || !"false".equals(System.getProperty("qplayer.mpris", "true")));

        // Make the main QML scene the only startup-critical workload. Disk scans,
        // network requests, native tray/media integrations and the recursive folder
        // watcher all start after a real frame is visible, avoiding CPU/I/O/JNA
        // contention while qml4j instantiates Main.qml.
        window.setFirstFrameListener(() -> {
            long elapsedMs = (System.nanoTime() - startupStartedNanos) / 1_000_000L;
            Logger.info("startup first frame ready in {} ms; starting deferred services", elapsedMs);
            // Neither the host-drawn icon face nor stale installer cleanup is
            // required to produce the initial QML scene.
            Fonts.initIcon(resources.load("fonts/MaterialSymbolsRounded.ttf"));
            Thread cleanup = new Thread(
                    () -> deleteRecursive(AppDirs.updatesDir().toFile()),
                    "qplayer-update-cleanup");
            cleanup.setDaemon(true);
            cleanup.start();
            controller.loadHome();
            startLibraryScan(controller, reader, window, initialFolder);
            startLibraryWatcher(watcher, initialFolder);
            if (PACKAGED) controller.checkForUpdate();
            if (!"false".equals(System.getProperty("qplayer.tray", "true"))) {
                getTrayThread(tray, window).start();
            }
            if (systemMedia != null && systemMediaEnabled) systemMedia.start();
        });

        // Start rendering only after every callback and persisted cache path is
        // wired, then enter the native event loop immediately.
        window.spawnRenderThread();
        themeMonitor.start();

        window.runEventLoop(); // blocks on the main thread until quit

        themeMonitor.close();
        watcher.stop();
        if (systemMedia != null) systemMedia.shutdown();
        tray.shutdown();
        DesktopWebLogin.shutdown();
        try {
            controller.shutdown();
        } catch (Throwable ignored) {
        }
        window.shutdown();
        Logger.info("QPlayer desktop exited");
    }

    /** Open a URL in the system default browser via the OS handler (no AWT). */
    private static void openUrl(String url) {
        if (url == null || url.isBlank()) return;
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] cmd;
        if (os.contains("win")) {
            cmd = new String[]{"rundll32", "url.dll,FileProtocolHandler", url};
        } else if (os.contains("mac")) {
            cmd = new String[]{"open", url};
        } else {
            cmd = new String[]{"xdg-open", url};
        }
        try {
            new ProcessBuilder(cmd).start();
        } catch (Exception e) {
            Logger.warn("open url failed ({}): {}", url, e.toString());
        }
    }

    /** {@link PlayerController.Installer}: download the matched release asset into
     *  the cache folder (not a temp dir — same place audio/cover caching already
     *  lives, so it's covered by the existing cache-size/clear-cache settings) and
     *  hand off to the OS to actually install it, mirroring how the Android side
     *  hands a downloaded APK to the system package installer. */
    private static void downloadAndInstallUpdate(PlayerController controller,
                                                 DesktopWindow window,
                                                 String[] urls) {
        new Thread(() -> {
            String name = urls.length > 0 ? fileNameOf(urls[0]) : "qplayer-update";
            File dir = AppDirs.updatesDir().toFile();
            dir.mkdirs();
            File out = new File(dir, name);
            for (String url : urls) {
                if (downloadOne(url, out, controller)) {
                    out.setExecutable(true, false);
                    controller.setUpdateProgress(100);
                    InstallerLaunch launch = launchInstaller(out);
                    if (!launch.started()) {
                        controller.setUpdateProgress(-2);
                    } else if (launch.requiresAppExit()) {
                        // Only quit after ProcessBuilder has successfully created
                        // the independent installer process. Use the normal main-
                        // loop shutdown path so playback, tray, GPU and settings
                        // resources are released before the installer replaces
                        // files from the current installation.
                        Logger.info("update installer started; shutting down QPlayer");
                        window.postMainTask(window::requestQuit);
                    }
                    return;
                }
                Logger.warn("update source failed, trying next: {}", url);
            }
            controller.setUpdateProgress(-2);
        }, "qplayer-update-dl").start();
    }

    private static String fileNameOf(String url) {
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }

    private static void deleteRecursive(File f) {
        if (!f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    /** Download a single url into {@code out}, reporting progress; false on any
     *  failure (so the caller can try the next mirror), same contract as the
     *  Android downloader this mirrors. */
    private static boolean downloadOne(String url, File out, PlayerController controller) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("User-Agent", "qplayer-updater");
            int code = conn.getResponseCode();
            if (code >= 400) return false;
            int total = conn.getContentLength();
            try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                long read = 0;
                int n;
                int lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    read += n;
                    if (total > 0) {
                        int pct = (int) (read * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            controller.setUpdateProgress(pct);
                        }
                    }
                }
            }
            return out.length() > 0;
        } catch (Throwable e) {
            Logger.warn("update download failed {}: {}", url, e.toString());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Hand the downloaded installer off to the OS — same "get out of the way,
     *  let the platform take it from here" spirit as Android's ACTION_VIEW to the
     *  system package installer. Windows: run the Inno Setup exe directly (it
     *  handles the "close the running app" prompt itself). macOS: {@code open} the
     *  dmg (mounts it, Finder shows the drag-to-Applications window). Linux:
     *  AppImage isn't a true installer, so just reveal the containing folder for
     *  the user to swap it in themselves. */
    private static InstallerLaunch launchInstaller(File out) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder(out.getAbsolutePath()).start();
                return new InstallerLaunch(true, true);
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", out.getAbsolutePath()).start();
                // Opening a DMG only mounts/reveals it; replacing the app remains
                // a later explicit drag, so there is no reason to close yet.
                return new InstallerLaunch(true, false);
            } else {
                new ProcessBuilder("xdg-open", out.getParentFile().getAbsolutePath()).start();
                // AppImage has no installer. We only reveal the downloaded file.
                return new InstallerLaunch(true, false);
            }
        } catch (Exception e) {
            Logger.warn("launch installer failed: {}", e.toString());
            return new InstallerLaunch(false, false);
        }
    }

    private record InstallerLaunch(boolean started, boolean requiresAppExit) { }

    /** Running app version from the Maven-filtered version.properties on the
     *  classpath, for the update check. Empty if unavailable. */
    private static String appVersion() {
        try (InputStream is = Main.class.getResourceAsStream("/version.properties")) {
            if (is == null) return "";
            java.util.Properties p = new java.util.Properties();
            p.load(is);
            return p.getProperty("version", "").trim();
        } catch (Exception e) {
            Logger.warn("version.properties read failed: {}", e.toString());
            return "";
        }
    }

    @NotNull
    private static Thread getTrayThread(TrayController tray, DesktopWindow window) {
        Thread trayThread = new Thread(() -> {
            boolean ok = false;
            try {
                ok = tray.install();
            } catch (Throwable t) {
                // Never let a tray failure (e.g. an AWT/JNI Error) kill the
                // thread silently and leave trayAvailable unset.
                Logger.warn("tray install threw: {}", t);
            }
            window.setTrayAvailable(ok);
        }, "qplayer-tray-init");
        trayThread.setDaemon(true);
        return trayThread;
    }

    private static DesktopMediaControls createSystemMediaControls(
            PlayerController controller, DesktopWindow window) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if ((os.contains("nux") || os.contains("nix")) && MprisControls.isSupported()) {
            return new MprisControls(controller, window);
        }
        if (os.contains("mac")) return new MacMediaControls(controller, window);
        if (os.contains("win")) return new WindowsMediaControls(controller, window);
        return null;
    }

    /**
     * Give the unpackaged Win32 process a stable Shell identity before GLFW
     * creates its top-level window. SMTC can accept updates without one, but the
     * Windows 10/11 Shell may not surface that anonymous session in its media UI.
     */
    private static void configureWindowsAppIdentity() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        try {
            int hr = Shell32.INSTANCE
                    .SetCurrentProcessExplicitAppUserModelID(
                            new WString("dev.t1m3.qplayer"))
                    .intValue();
            if (hr < 0) {
                Logger.warn("Windows AppUserModelID unavailable: 0x{}",
                        Integer.toHexString(hr));
            }
        } catch (Throwable t) {
            Logger.warn("Windows AppUserModelID unavailable: {}", t);
        }
    }

    private static void startLibraryScan(PlayerController controller, MetadataReader reader,
                                         DesktopWindow window, String folder) {
        if (folder == null || folder.isEmpty()) return;
        File music = new File(folder);
        if (!music.isDirectory()) return;
        Thread t = new Thread(() -> {
            try {
                List<Track> tracks = new LibraryScanner(reader).scan(music.getAbsolutePath());
                // Property writes happen on the render thread (mirrors Android's
                // runOnUiThread(controller.scanTracks)).
                window.postRenderTask(() -> controller.scanTracks(tracks));
            } catch (Throwable e) {
                Logger.warn("library scan failed: {}", e);
            }
        }, "qplayer-scan");
        t.setDaemon(true);
        t.start();
    }

    /** Recursive watch registration can be expensive for a deeply nested library. */
    private static void startLibraryWatcher(LibraryWatcher watcher, String folder) {
        Thread thread = new Thread(() -> watcher.start(folder), "qplayer-library-watch-init");
        thread.setDaemon(true);
        thread.start();
    }

}
