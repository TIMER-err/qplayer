package dev.t1m3.qplayer.desktop;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.ResourceLoader;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.lyric.skia.LyricCompositor;
import dev.t1m3.qplayer.util.Logger;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Owns the GLFW window and the render-thread lifecycle on the process main
 * thread. GLFW window/event calls stay here (they must be main-thread; macOS
 * additionally needs {@code -XstartOnFirstThread}); the GPU stack + Skija live on
 * a disposable {@link RenderThread}. Minimize-to-tray stops that thread (which
 * destroys the GPU resources on its own context-owning thread) and hides the
 * window; the persistent {@link QmlView} + {@link PlayerController} stay alive so
 * the audio keeps playing and the UI keeps its state, and restore respawns the
 * render thread against a fresh context.
 */
public final class DesktopWindow {

    private static final int INITIAL_W = Integer.getInteger("qplayer.width", 1100);
    private static final int INITIAL_H = Integer.getInteger("qplayer.height", 720);

    private final QmlEngine engine;
    private final String qmlSource;
    private final ResourceLoader resources;
    private final PlayerController controller;
    private final DesktopSettings settings;
    private final LyricCompositor compositor = new LyricCompositor();
    private final GraphicsBackend.Kind kind = GraphicsBackend.Kind.fromProperty();

    // Input events (main thread) marshalled onto the render thread; playback/tray
    // tasks (controller main executor + tray menu) run on the main event loop.
    private final ConcurrentLinkedQueue<Runnable> renderTasks = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Runnable> mainTasks = new ConcurrentLinkedQueue<>();

    private long window;
    private volatile float uiScale = 1f;
    // Framebuffer size cached from the main-thread callback so the render thread
    // never has to call GLFW.
    private volatile int fbW = INITIAL_W;
    private volatile int fbH = INITIAL_H;
    private volatile int[] pendingResize;

    // Persistent across render-thread respawns (built once, on the render thread).
    private volatile QmlView view;
    private boolean lastSpawnWasRespawn;

    private InputBridge input;
    private volatile RenderThread renderThread;
    private volatile boolean quitRequested;
    private volatile boolean hiddenToTray;
    // Whether a system tray actually installed. Without one, hiding the window would
    // make the app vanish with no way back, so the close button quits instead and
    // recovery is left to the window manager's minimise (the taskbar icon is set).
    private volatile boolean trayAvailable;
    private Runnable firstFrameListener;

    DesktopWindow(QmlEngine engine, String qmlSource, ResourceLoader resources,
                  PlayerController controller, DesktopSettings settings) {
        this.engine = engine;
        this.qmlSource = qmlSource;
        this.resources = resources;
        this.controller = controller;
        this.settings = settings;
    }

    // --- accessors used by the render thread (all read cached/persistent state) ---

    long window() {
        return window;
    }

    QmlView view() {
        return view;
    }

    GraphicsBackend.Kind kind() {
        return kind;
    }

    float uiScale() {
        return uiScale;
    }

    int[] framebufferSize() {
        return new int[]{fbW, fbH};
    }

    PlayerController controller() {
        return controller;
    }

    DesktopSettings settings() {
        return settings;
    }

    LyricCompositor compositor() {
        return compositor;
    }

    void setFirstFrameListener(Runnable r) {
        this.firstFrameListener = r;
    }

    /**
     * Post a task to run on the render thread (input events).
     */
    void postRenderTask(Runnable r) {
        renderTasks.add(r);
    }

    /**
     * Post a task to run on the main event loop (playback control, tray).
     */
    void postMainTask(Runnable r) {
        mainTasks.add(r);
    }

    void drainRenderTasks() {
        Runnable r;
        while ((r = renderTasks.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                Logger.warn("render task failed: {}", t);
            }
        }
    }

    /**
     * Per-frame input animation (smooth wheel scrolling); render thread.
     */
    void tickInput() {
        if (input != null) input.tickScroll();
    }

    int[] consumePendingResize() {
        int[] r = pendingResize;
        if (r != null) pendingResize = null;
        return r;
    }

    /**
     * Build the persistent QML view on first call (render thread), else return it.
     * Records whether this was a respawn so the caller can invalidate GPU caches.
     */
    QmlView ensureView() {
        if (view != null) {
            lastSpawnWasRespawn = true;
            return view;
        }
        lastSpawnWasRespawn = false;
        QmlView v = QmlView.withStockTypes(engine).resources(resources);
        v.setClipboard(new GlfwClipboard(window));
        if (controller != null) v.context("player", controller);
        if (settings != null) v.context("settings", settings);
        loadFonts(v, resources);
        v.load(qmlSource);
        view = v;
        return v;
    }

    public static void loadFonts(QmlView v, ResourceLoader resources) {
        byte[] reg = resources.load("fonts/PingFangSC-Regular.otf");
        byte[] med = resources.load("fonts/PingFangSC-Medium.otf");
        if (reg != null || med != null) v.uiTypefaces(reg, med);
        byte[] iconFont = resources.load("fonts/MaterialSymbolsRounded.ttf");
        if (iconFont != null) v.iconTypeface(iconFont);
    }

    boolean markViewLive() {
        return lastSpawnWasRespawn;
    }

    void onFirstFramePainted() {
        // The window is created hidden so the first visible frame is real content,
        // not a blank flash during the QML compile. Show it now (on the main thread,
        // where GLFW window ops must run).
        postMainTask(() -> {
            if (!hiddenToTray) {
                GLFW.glfwShowWindow(window);
                GLFW.glfwFocusWindow(window);
            }
        });
        Runnable r = firstFrameListener;
        if (r != null) {
            firstFrameListener = null;
            postMainTask(r);
        }
    }

    void onRenderError(Throwable t) {
        Logger.error("render thread crashed: {}", t);
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        Logger.error(sw.toString());
    }

    // --- main-thread lifecycle -------------------------------------------------

    void init() {
        GLFWErrorCallback.createPrint(System.err).set();
        preferStablePlatform();
        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");

        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        if (kind == GraphicsBackend.Kind.VULKAN) {
            // Vulkan manages its own surface; GLFW must not create a GL context.
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        } else {
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
        }

        window = GLFW.glfwCreateWindow(INITIAL_W, INITIAL_H, "QPlayer", MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            GLFW.glfwTerminate();
            throw new IllegalStateException("glfwCreateWindow failed");
        }
        // IMPORTANT: do NOT make the GL context current here — the render thread
        // owns it. (For Vulkan there is no GL context at all.)

        setWindowIcon();
        cacheFramebufferAndScale();
        installCallbacks();
        input = new InputBridge(this);
        input.install(window);
        Logger.info("desktop window created ({}x{}), graphics backend = {}", fbW, fbH, kind);
    }

    // Rendering happens on a dedicated thread while the main thread pumps events.
    // On a Wayland session NVIDIA's EGL driver crashes under that split; X11/GLX
    // (via XWayland) is stable, so prefer X11 when a DISPLAY is available. Override
    // with -Dqplayer.glfw.platform=wayland|x11|any.
    private void preferStablePlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) return;
        String pref = System.getProperty("qplayer.glfw.platform", "").toLowerCase();
        int platform;
        if ("wayland".equals(pref)) {
            platform = GLFW.GLFW_PLATFORM_WAYLAND;
        } else if ("any".equals(pref)) {
            return;
        } else if ("x11".equals(pref) || System.getenv("DISPLAY") != null) {
            platform = GLFW.GLFW_PLATFORM_X11;
        } else {
            return;
        }
        if (GLFW.glfwPlatformSupported(platform)) {
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, platform);
            Logger.info("forcing GLFW platform = {}",
                    platform == GLFW.GLFW_PLATFORM_X11 ? "x11" : "wayland");
        }
    }

    private void cacheFramebufferAndScale() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window, w, h);
            fbW = Math.max(1, w.get(0));
            fbH = Math.max(1, h.get(0));
            java.nio.FloatBuffer sx = stack.mallocFloat(1), sy = stack.mallocFloat(1);
            GLFW.glfwGetWindowContentScale(window, sx, sy);
            float s = sx.get(0);
            uiScale = s > 0 ? s : 1f;
        }
    }

    @SuppressWarnings("resource")
    private void installCallbacks() {
        GLFW.glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
            if (w <= 0 || h <= 0) return;
            fbW = w;
            fbH = h;
            pendingResize = new int[]{w, h};
        });
        GLFW.glfwSetWindowContentScaleCallback(window, (win, sx, sy) -> {
            if (sx > 0) uiScale = sx;
        });
        // With a tray: close hides to it (real quit = tray "Quit"). Without a tray:
        // close just quits, so the app can't vanish to nowhere.
        GLFW.glfwSetWindowCloseCallback(window, win -> {
            // Veto the actual close; onExitRequested decides hide-to-tray vs quit.
            GLFW.glfwSetWindowShouldClose(win, false);
            onExitRequested();
        });
    }

    /**
     * Told by the host once the tray install has finished (on its own thread).
     */
    void setTrayAvailable(boolean available) {
        this.trayAvailable = available;
    }

    private void setWindowIcon() {
        byte[] png = resources.load("app-icon.png");
        if (png == null) return;
        try {
            BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) return;
            int w = img.getWidth(), h = img.getHeight();
            ByteBuffer pixels = MemoryUtil.memAlloc(w * h * 4);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = img.getRGB(x, y);          // ARGB
                    pixels.put((byte) ((p >> 16) & 0xFF)); // R
                    pixels.put((byte) ((p >> 8) & 0xFF));  // G
                    pixels.put((byte) (p & 0xFF));         // B
                    pixels.put((byte) ((p >> 24) & 0xFF)); // A
                }
            }
            pixels.flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GLFWImage.Buffer icons = GLFWImage.malloc(1, stack);
                icons.position(0).width(w).height(h).pixels(pixels);
                GLFW.glfwSetWindowIcon(window, icons);
            }
            MemoryUtil.memFree(pixels);
        } catch (Throwable t) {
            Logger.warn("window icon load failed: {}", t);
        }
    }

    void spawnRenderThread() {
        RenderThread rt = renderThread;
        if (rt != null && rt.isAlive()) return;
        rt = new RenderThread(this);
        renderThread = rt;
        rt.start();
    }

    void stopRenderThread() {
        RenderThread rt = renderThread;
        if (rt == null) return;
        rt.shutdown();
        try {
            rt.join(5000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        renderThread = null;
    }

    /**
     * Close-button / back policy: hide to the tray if there is one, else quit (so
     * the app never vanishes with no way back).
     */
    void onExitRequested() {
        if (trayAvailable) minimizeToTray();
        else requestQuit();
    }

    /**
     * Tear down the render thread + GPU stack and hide the window (main thread).
     */
    void minimizeToTray() {
        if (hiddenToTray) return;
        hiddenToTray = true;
        stopRenderThread();
        GLFW.glfwHideWindow(window);
        Logger.info("minimized to tray (render thread + GPU destroyed)");
    }

    /**
     * Show the window again and respawn the render thread (main thread).
     */
    void restoreFromTray() {
        if (!hiddenToTray) {
            GLFW.glfwShowWindow(window);
            GLFW.glfwFocusWindow(window);
            return;
        }
        hiddenToTray = false;
        GLFW.glfwShowWindow(window);
        GLFW.glfwFocusWindow(window);
        spawnRenderThread();
        Logger.info("restored from tray (render thread respawned)");
    }

    boolean isHiddenToTray() {
        return hiddenToTray;
    }

    void requestQuit() {
        quitRequested = true;
        GLFW.glfwPostEmptyEvent();
    }

    /**
     * Main loop: pump GLFW events + the main-task queue until quit. Keeps running
     * (and draining playback/tray tasks) even while the render thread is dead.
     */
    void runEventLoop() {
        while (!quitRequested) {
            // Block briefly so a hidden-to-tray window doesn't spin the CPU, but stay
            // responsive to tray actions posted to the main-task queue.
            GLFW.glfwWaitEventsTimeout(0.05);
            Runnable r;
            while ((r = mainTasks.poll()) != null) {
                try {
                    r.run();
                } catch (Throwable t) {
                    Logger.warn("main task failed: {}", t);
                }
            }
        }
    }

    void shutdown() {
        stopRenderThread();
        if (view != null) {
            try {
                view.dispose();
            } catch (Throwable ignored) {
            }
        }
        org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(window);
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        @SuppressWarnings("resource") GLFWErrorCallback cb = GLFW.glfwSetErrorCallback(null);
        if (cb != null) cb.free();
    }
}
