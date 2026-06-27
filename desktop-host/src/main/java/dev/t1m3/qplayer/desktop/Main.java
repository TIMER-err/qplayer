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
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
        // Windows native binary (GUI subsystem): no console on double-click, but
        // attach to the launching terminal's console so logs still stream there.
        // Before anything writes to stdout (log4j console appender resolves it).
        if (System.getProperty("java.vm.name", "").contains("Substrate")
                && System.getProperty("os.name", "").toLowerCase().contains("win")) {
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

        // Put the rolling log under the writable app data dir (~/.qplayer/logs) —
        // when installed to Program Files the working dir isn't writable, so a
        // CWD-relative logs/ would silently fail. Set before log4j2 first inits
        // (in Log4j2Sink below); log4j2.xml reads ${sys:qplayer.logs}.
        if (System.getProperty("qplayer.logs") == null) {
            System.setProperty("qplayer.logs",
                    new File(dev.t1m3.qplayer.store.AppDirs.base(), "logs").getAbsolutePath());
        }

        // Route the shared player-core logger to log4j2 (colored console + rolling
        // file, config in log4j2.xml). First thing in main so every later line — incl.
        // the startup property fixups below — lands in the configured format.
        Logger.setSink(new Log4j2Sink());

        // Pin Rhino to the interpreter BEFORE any qml4j class loads. JsRuntime caches
        // the optimization level into a `static final` field whose initializer reads
        // `qml4j.rhino.opt`, and `-Dqml4j.rhino.opt=-1` in native-image.properties is
        // not reliably baked into runtime System properties by newer GraalVM. Without
        // the interpreter, Rhino's Codegen path calls ClassLoader.defineClass at
        // runtime — which native-image forbids — and the render thread crashes with
        // "No classes have been predefined during the image build".
        if (System.getProperty("qml4j.rhino.opt") == null) {
            System.setProperty("qml4j.rhino.opt", "-1");
        }

        // In a native image there is no JDK home; javax.sound's provider loader throws
        // "Can't find java.home" reading the optional lib/sound.properties. Point it at
        // a real dir so the (absent) file is simply skipped.
        if (System.getProperty("java.home") == null) {
            System.setProperty("java.home", System.getProperty("user.dir", "/tmp"));
        }

        // AWT's logical-font init reads <java.home>/lib/fontconfig.bfc; the native
        // binary has no JDK lib dir, so it throws "Fontconfig head is null" and any
        // downstream font-metrics call (incl. the tray's Swing JPopupMenu sizing) dies.
        // The native build bundles the host JDK's fontconfig.bfc as a classpath
        // resource (maven-antrun-plugin in the native profile); extract it to a temp
        // file and point AWT at it (sun.awt.fontconfig is the documented override —
        // FontConfiguration.findFontConfigFile reads it before falling back to the
        // default lib/fontconfig.bfc lookup) BEFORE any tray/Swing code runs.
        if (System.getProperty("java.vm.name", "").contains("Substrate")
                && System.getProperty("sun.awt.fontconfig") == null) {
            try (InputStream is = Main.class.getResourceAsStream("/fontconfig.bfc")) {
                if (is == null) {
                    Logger.warn("fontconfig.bfc not bundled — tray menus will fail");
                } else {
                    File f = File.createTempFile("qplayer-fontconfig", ".bfc");
                    f.deleteOnExit();
                    try (OutputStream os = new FileOutputStream(f)) { is.transferTo(os); }
                    System.setProperty("sun.awt.fontconfig", f.getAbsolutePath());
                    Logger.info("fontconfig extracted: {}", f.getAbsolutePath());
                }
            } catch (Throwable t) {
                Logger.warn("fontconfig extract failed: {}", t);
            }
        }

        // In the native image, make the bare binary self-sufficient: point Skija +
        // LWJGL at the native libs that ship beside the executable, so `qplayer[.exe]`
        // works without a wrapper script (and the path is read straight from the OS,
        // dodging the mojibake you get passing a non-ASCII dir through a .cmd file).
        // On Windows this dir also holds icudtl.dat (Skija's ICU data; Linux/macOS
        // bake it into the lib). A launcher that already set these (e.g. the AppImage
        // AppRun, pointing at its native-libs subdir) wins via the null check.
        // Skipped on a normal JVM (dev `exec:exec`), where Skija extracts its own
        // natives and the executable is `java`, not us.
        if (System.getProperty("java.vm.name", "").contains("Substrate")) {
            defaultNativePath("skija.library.path");
            defaultNativePath("org.lwjgl.librarypath");
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
        // Open external links (the About page) in the system browser. The Android
        // host uses an ACTION_VIEW intent; on the desktop hand the URL to the OS
        // (no java.awt.Desktop, which is unreliable in the native image).
        controller.setUrlOpener(Main::openUrl);

        TrayController tray = new TrayController(controller, window, resources.load("app-icon.png"));

        window.init();
        // Start rendering immediately — the render thread is the core; the tray is
        // best-effort and may block on GTK init in some environments, so it must
        // never gate the window coming up.
        window.spawnRenderThread();

        // A second launch now surfaces this window instead of starting a new process.
        onActivate.set(() -> window.postMainTask(window::restoreFromTray));

        // Initial content + a background scan of the local music folder.
        controller.loadHome();
        startLibraryScan(controller, reader, window);

        // Tray init on a daemon thread so a GTK/AppIndicator hang can't freeze the app.
        // (-Dqplayer.tray=false disables it, e.g. for headless rendering checks.)
        if (!"false".equals(System.getProperty("qplayer.tray", "true"))) {
            Thread trayThread = getTrayThread(tray, window, controller);
            trayThread.start();
        }

        window.runEventLoop(); // blocks on the main thread until quit

        tray.shutdown();
        try {
            controller.shutdown();
        } catch (Throwable ignored) {
        }
        window.shutdown();
        Logger.info("QPlayer desktop exited");
        killSelf();
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

    @NotNull
    private static Thread getTrayThread(TrayController tray, DesktopWindow window, PlayerController controller) {
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
            if (ok) controller.setPlaybackListener(tray);
        }, "qplayer-tray-init");
        trayThread.setDaemon(true);
        return trayThread;
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

    // Set a native-library path property to the running executable's directory,
    // unless a launcher already set it. Used to make the bare native binary find
    // the Skija/LWJGL libs (and icudtl.dat) that ship beside it.
    private static void defaultNativePath(String prop) {
        if (System.getProperty(prop) != null) return;
        try {
            String cmd = ProcessHandle.current().info().command().orElse(null);
            if (cmd != null) {
                File parent = new File(cmd).getAbsoluteFile().getParentFile();
                if (parent != null) {
                    System.setProperty(prop, parent.getAbsolutePath());
                    return;
                }
            }
        } catch (Throwable ignored) {
            // ProcessHandle may be restricted; fall through to the CWD.
        }
        String cwd = System.getProperty("user.dir");
        if (cwd != null) System.setProperty(prop, cwd);
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
