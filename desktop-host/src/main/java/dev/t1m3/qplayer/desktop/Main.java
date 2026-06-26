package dev.t1m3.qplayer.desktop;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.ResourceLoader;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.library.LibraryScanner;
import dev.t1m3.qplayer.lyric.skia.Fonts;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
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

    public static void main(String[] args) {
        // In a native image there is no JDK home; javax.sound's provider loader throws
        // "Can't find java.home" reading the optional lib/sound.properties. Point it at
        // a real dir so the (absent) file is simply skipped.
        if (System.getProperty("java.home") == null) {
            System.setProperty("java.home", System.getProperty("user.dir", "/tmp"));
        }

        ResourceLoader resources = new ClasspathResourceLoader();

        // Platform backends (the desktop impls already exist).
        AudioBackend audio = new dev.t1m3.qplayer.desktop.DesktopAudioBackend();
        MetadataReader reader = new dev.t1m3.qplayer.desktop.DesktopMetadataReader();

        PlayerController controller = new PlayerController(audio, reader);
        controller.setColorExtractor(new DesktopColorExtractor());

        DesktopSettings settings = new DesktopSettings();
        settings.setMonetListener(controller::setMonetEnabled);
        settings.setUnblockListener(controller::setUnblockEnabled);
        settings.setMirrorListener(controller::setUpdateMirror);
        settings.setCacheSizeListener(controller::setCacheMaxSizeMB);
        settings.load();

        // Fonts for the host-drawn lyric renderer (the QML scene fonts are set on the
        // view in DesktopWindow.ensureView).
        Fonts.init(
                resources.load("fonts/PingFangSC-Thin.otf"),
                resources.load("fonts/PingFangSC-Light.otf"),
                resources.load("fonts/PingFangSC-Regular.otf"),
                resources.load("fonts/PingFangSC-Medium.otf"));
        Fonts.initIcon(resources.load("fonts/MaterialSymbolsRounded.ttf"));

        byte[] qmlBytes = resources.load("Main.qml");
        if (qmlBytes == null) throw new IllegalStateException("Main.qml not found on classpath");
        String qml = new String(qmlBytes, StandardCharsets.UTF_8);

        // In a GraalVM native image (or with -Dqplayer.aot=true) load the build-time
        // AOT-compiled QML classes instead of generating them at runtime — the
        // no-runtime-codegen path native-image's closed world requires (paired with
        // interpreted Rhino, baked via -Dqml4j.rhino.opt=-1 at build time).
        boolean nativeImage = System.getProperty("java.vm.name", "").contains("Substrate");
        boolean aot = nativeImage || "true".equals(System.getProperty("qplayer.aot"));
        QmlEngine engine = aot
                ? new QmlEngine(new dev.t1m3.qplayer.desktop.aot.PrecompiledBackend())
                : new QmlEngine();
        DesktopWindow window = new DesktopWindow(engine, qml, resources, controller, settings);

        // Playback control runs on the main event loop (alive even while the render
        // thread is dead); back/exit folds the window to the tray.
        controller.setMainExecutor(window::postMainTask);
        controller.setExitListener(window::onExitRequested);

        TrayController tray = new TrayController(controller, window, resources.load("app-icon.png"));

        window.init();
        // Start rendering immediately — the render thread is the core; the tray is
        // best-effort and may block on GTK init in some environments, so it must
        // never gate the window coming up.
        window.spawnRenderThread();

        // Initial content + a background scan of the local music folder.
        controller.loadHome();
        startLibraryScan(controller, reader, window);

        // Tray init on a daemon thread so a GTK/AppIndicator hang can't freeze the app.
        // (-Dqplayer.tray=false disables it, e.g. for headless rendering checks.)
        if (!"false".equals(System.getProperty("qplayer.tray", "true"))) {
            Thread trayThread = new Thread(() -> {
                boolean ok = tray.install();
                window.setTrayAvailable(ok);
                if (ok) controller.setPlaybackListener(tray);
            }, "qplayer-tray-init");
            trayThread.setDaemon(true);
            trayThread.start();
        }

        // Synthetic interaction exercise (-Dqplayer.exercise=true): drives playback,
        // the lyric page (wavy-progress Canvas) and loading states so a native-image
        // tracing-agent run captures the audio-provider + Context2D reflection that a
        // passive headless run never touches.
        if ("true".equals(System.getProperty("qplayer.exercise"))) {
            Thread ex = new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    window.postRenderTask(controller::loadMyPlaylists); // loading indicator
                    Thread.sleep(1500);
                    window.postRenderTask(controller::loadRecent);
                    Thread.sleep(1500);
                    window.postRenderTask(() -> controller.playRecommendation(0)); // audio path
                    Thread.sleep(3500);
                    window.postRenderTask(() -> controller.setLyricsOpen(true)); // wavy Canvas
                    Thread.sleep(2500);
                    window.postRenderTask(controller::toggle);
                    Thread.sleep(1500);
                    window.postRenderTask(controller::next);
                    Thread.sleep(2000);
                    window.postRenderTask(() -> controller.setLyricsOpen(false));
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }, "qplayer-exercise");
            ex.setDaemon(true);
            ex.start();
        }

        // Self-test for the minimize→restore GPU teardown/respawn path
        // (-Dqplayer.cycleTest=true): exercises it without clicking the tray.
        if ("true".equals(System.getProperty("qplayer.cycleTest"))) {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(4000);
                    Logger.info("cycleTest: minimizing to tray");
                    window.postMainTask(window::minimizeToTray);
                    Thread.sleep(2000);
                    Logger.info("cycleTest: restoring from tray");
                    window.postMainTask(window::restoreFromTray);
                    Thread.sleep(4000);
                    Logger.info("cycleTest: survived minimize+restore");
                } catch (InterruptedException ignored) {
                }
            }, "qplayer-cycletest");
            t.setDaemon(true);
            t.start();
        }

        window.runEventLoop(); // blocks on the main thread until quit

        tray.shutdown();
        try { controller.shutdown(); } catch (Throwable ignored) {}
        window.shutdown();
        Logger.info("QPlayer desktop exited");
        killSelf();
    }

    private static void startLibraryScan(PlayerController controller, MetadataReader reader,
                                         DesktopWindow window) {
        File music = new File(System.getProperty("user.home", "."), "Music");
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

    // The desktop GL drivers (notably NVIDIA's) can SIGSEGV a worker thread the
    // instant the process begins to exit, and the AWT EDT (clipboard / tray) is
    // non-daemon and would keep the JVM alive past main(). SIGKILL terminates the
    // whole process atomically before either can bite. Mirrors the qml4j demo host.
    private static void killSelf() {
        try {
            // Java 8: derive the pid from the "pid@host" runtime name.
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            String pid = name.contains("@") ? name.substring(0, name.indexOf('@')) : null;
            if (pid != null) {
                new ProcessBuilder("kill", "-9", pid).start();
                Thread.sleep(10_000);
            }
        } catch (Exception ignored) {
        }
        Runtime.getRuntime().halt(0);
    }
}
