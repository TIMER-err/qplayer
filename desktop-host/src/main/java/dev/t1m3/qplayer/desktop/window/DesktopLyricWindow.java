package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.resources.DiskCompiledSceneCache;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricTimeline;
import dev.t1m3.qplayer.lyric.skia.LyricConfig;
import dev.t1m3.qplayer.settings.SettingsStore;
import dev.t1m3.qplayer.util.Logger;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.ResourceLoader;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Main-thread GLFW shell for desktop lyrics. GPU and QML ownership live in a
 * dedicated {@link DesktopLyricRenderThread}; this class only manages the native
 * window and publishes immutable controller/settings snapshots to that thread.
 */
public final class DesktopLyricWindow {

    private static final String ENABLED_KEY = "desktopLyricEnabled";
    private static final String PASSTHROUGH_KEY = "desktopLyricMousePassthrough";
    private static final String X_KEY = "desktopLyricX";
    private static final String Y_KEY = "desktopLyricY";
    static final int WIDTH = 900;
    static final int HEIGHT = 180;
    static final float LYRIC_LEFT = 160f;
    static final float LYRIC_RIGHT_MARGIN = 112f;
    private static final float LEFT_HIT_LEFT = 10f;
    private static final float LEFT_SINGLE_HIT_RIGHT = 58f;
    private static final float LEFT_PLAYBACK_HIT_RIGHT = 138f;
    private static final float RIGHT_HIT_LEFT = 848f;
    private static final float RIGHT_HIT_RIGHT = 894f;
    private static final float TOP_HIT_TOP = 10f;
    private static final float TOP_HIT_BOTTOM = 58f;
    private static final float BOTTOM_HIT_TOP = 122f;
    private static final float BOTTOM_HIT_BOTTOM = 170f;
    private static final float UNLOCK_LEFT = 848f;
    private static final float UNLOCK_RIGHT = 894f;

    private final SettingsStore store;
    private final ResourceLoader resources;
    private final DiskCompiledSceneCache qmlCompilationCache;
    private final String qmlSource;
    private final Consumer<Boolean> settingsWriter;
    private final Consumer<Runnable> mainPoster;
    private final Runnable playerRestorer;
    private volatile Consumer<Boolean> stateListener;
    private final AtomicReference<DesktopLyricSnapshot> snapshot =
            new AtomicReference<>(DesktopLyricSnapshot.EMPTY);
    private final AtomicReference<FramebufferSize> framebufferSize =
            new AtomicReference<>(new FramebufferSize(WIDTH, HEIGHT));
    private final ConcurrentLinkedQueue<Consumer<QmlView>> inputEvents =
            new ConcurrentLinkedQueue<>();

    private volatile boolean enabled;
    private volatile boolean snapshotPublished;
    private volatile DesktopLyricRenderThread renderThread;
    private volatile long window = MemoryUtil.NULL;
    private volatile boolean firstFrameReady;
    private volatile PlayerController controller;
    private volatile boolean pointerInside;
    private volatile boolean mousePassthrough;
    private boolean nativeMousePassthrough;
    private boolean x11InputRegionSupported;
    private GraphicsBackend.Kind kind;
    private boolean positioningSupported = true;
    private List<LyricLine> lastLines;
    private boolean lastLinearPlainLrc;
    private LyricTimeline.Prepared prepared;
    private Object lastPaletteScheme;
    private DesktopLyricPalette palette;

    private boolean dragging;
    private boolean controlsPressed;
    private double cursorX;
    private double cursorY;
    private double dragCursorX0;
    private double dragCursorY0;

    DesktopLyricWindow(SettingsStore store, ResourceLoader resources,
                       GraphicsBackend.Kind kind, DiskCompiledSceneCache qmlCompilationCache,
                       Consumer<Boolean> settingsWriter,
                       Consumer<Runnable> mainPoster,
                       Runnable playerRestorer) {
        this.store = store;
        this.resources = resources;
        this.qmlCompilationCache = qmlCompilationCache;
        this.kind = transparentBackend(kind);
        this.settingsWriter = settingsWriter;
        this.mainPoster = mainPoster;
        this.playerRestorer = playerRestorer;
        this.enabled = store.getBool(ENABLED_KEY, false);
        this.mousePassthrough = store.getBool(PASSTHROUGH_KEY, false);
        byte[] bytes = resources.load("DesktopLyric.qml");
        if (bytes == null) throw new IllegalStateException("DesktopLyric.qml not found on classpath");
        this.qmlSource = new String(bytes, StandardCharsets.UTF_8);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Observes the native lyric window's actual state. Unlike SettingsCore this
     * remains available while the main QML/render thread is destroyed in tray
     * mode, so host UI such as the tray menu cannot become stale.
     */
    public void setStateListener(Consumer<Boolean> listener) {
        stateListener = listener;
    }

    boolean isPointerInside() {
        return pointerInside;
    }

    public boolean isMousePassthrough() {
        return mousePassthrough;
    }

    GraphicsBackend.Kind kind() {
        return kind;
    }

    long window() {
        return window;
    }

    ResourceLoader resources() {
        return resources;
    }

    DiskCompiledSceneCache qmlCompilationCache() {
        return qmlCompilationCache;
    }

    String qmlSource() {
        return qmlSource;
    }

    DesktopLyricSnapshot snapshot() {
        return snapshot.get();
    }

    boolean hasPublishedSnapshot() {
        return snapshotPublished;
    }

    FramebufferSize framebufferSize() {
        return framebufferSize.get();
    }

    void drainInput(QmlView view) {
        Consumer<QmlView> event;
        while ((event = inputEvents.poll()) != null) event.accept(view);
    }

    /** Main thread: creates the native surface using the selected app backend. */
    void create() {
        if (window != MemoryUtil.NULL) return;
        positioningSupported = GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_WAYLAND;
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        // Desktop lyrics is an auxiliary overlay, never a normal document window:
        // keep it undecorated so the framebuffer can stay transparent and entirely
        // app-rendered on every supported window system.
        GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FLOATING, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_TRANSPARENT_FRAMEBUFFER, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        if (kind == GraphicsBackend.Kind.VULKAN) {
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        } else {
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
            GLFW.glfwWindowHint(GLFW.GLFW_ALPHA_BITS, 8);
        }
        window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "QPlayer Lyrics",
                MemoryUtil.NULL, MemoryUtil.NULL);
        GLFW.glfwDefaultWindowHints();
        if (window == MemoryUtil.NULL) {
            Logger.warn("desktop lyric window: glfwCreateWindow failed");
            return;
        }
        AuxiliaryWindowStyle.hideFromTaskSwitchers(window);
        boolean transparent = GLFW.glfwGetWindowAttrib(
                window, GLFW.GLFW_TRANSPARENT_FRAMEBUFFER) == GLFW.GLFW_TRUE;
        DesktopWindow.applyWindowsRoundedCorners(window);
        Logger.info("desktop lyric window created (backend {}, transparent framebuffer = {})",
                kind, transparent);
        if (!positioningSupported) {
            Logger.warn("desktop lyrics disabled: native Wayland cannot move or persist "
                    + "an undecorated window; enable XWayland so QPlayer can use X11");
        }
        cacheFramebufferSize();
        GLFW.glfwSetFramebufferSizeCallback(window, (win, width, height) -> {
            if (width > 0 && height > 0) {
                framebufferSize.set(new FramebufferSize(width, height));
            }
        });
        int x = store.getInt(X_KEY, -1);
        int y = store.getInt(Y_KEY, -1);
        if (positioningSupported && x >= 0 && y >= 0) {
            // A persisted position can be stale (dragged off-screen last session,
            // or an external monitor from back then is gone now) -- clamp/snap it
            // back into whichever current monitor it mostly overlaps, same as a
            // live drag does, rather than trusting the raw stored value.
            int[] snapped = clampAndSnap(x, y);
            GLFW.glfwSetWindowPos(window, snapped[0], snapped[1]);
        } else if (positioningSupported) centerBottom();
        if (positioningSupported) installDragHandlers();
        x11InputRegionSupported = AuxiliaryWindowStyle.setX11InputRegion(window,
                mousePassthrough, (int) UNLOCK_LEFT, (int) BOTTOM_HIT_TOP,
                (int) (UNLOCK_RIGHT - UNLOCK_LEFT),
                (int) (BOTTOM_HIT_BOTTOM - BOTTOM_HIT_TOP), WIDTH, HEIGHT);
        if (x11InputRegionSupported) nativeMousePassthrough = mousePassthrough;
        else updateMousePassthroughRegion();
        boolean requestedEnabled = store.getBool(ENABLED_KEY, false);
        enabled = positioningSupported && requestedEnabled;
        if (!positioningSupported) {
            store.putBool(ENABLED_KEY, false);
            if (requestedEnabled && settingsWriter != null) settingsWriter.accept(false);
        }
        // Stay hidden until qml4j has compiled and presented a real transparent
        // frame. Showing an uninitialized native backbuffer produces a black box.
        firstFrameReady = false;
        snapshotPublished = false;
    }

    /** Starts the independent desktop-lyric GPU/QML owner once. */
    synchronized void startRenderThread() {
        if (window == MemoryUtil.NULL) return;
        DesktopLyricRenderThread current = renderThread;
        if (current != null && current.isAlive()) return;
        DesktopLyricRenderThread thread = new DesktopLyricRenderThread(this);
        renderThread = thread;
        thread.start();
    }

    /** Main render thread: copy all non-thread-safe QML/controller state. */
    void publish(PlayerController controller, boolean dark) {
        if (controller == null) return;
        this.controller = controller;
        List<LyricLine> lines = controller.lyrics.peek();
        LyricConfig config = LyricConfig.instance;
        boolean linear = Boolean.TRUE.equals(config.linearAnimForPlainLrc.getValue());
        if (lines != lastLines || linear != lastLinearPlainLrc) {
            lastLines = lines;
            lastLinearPlainLrc = linear;
            prepared = LyricTimeline.prepare(lines, linear);
        }
        int fontSize = config.lyricFontSize.getValue();
        int fontWeight = config.fontWeight.getValue().ordinal();
        boolean shadow = Boolean.TRUE.equals(config.dropShadow.getValue());
        Object paletteScheme = DesktopLyricPalette.scheme(dark);
        if (palette == null || paletteScheme != lastPaletteScheme) {
            lastPaletteScheme = paletteScheme;
            palette = DesktopLyricPalette.capture(paletteScheme, dark);
        }
        snapshot.set(new DesktopLyricSnapshot(prepared,
                controller.title.peek(), controller.artist.peek(),
                controller.lyricClockPosition(), controller.isLyricClockRunning(),
                System.nanoTime(), config.offsetMs.getValue(),
                fontSize, fontWeight, shadow,
                Boolean.TRUE.equals(controller.playing.peek()),
                palette));
        boolean firstSnapshot = !snapshotPublished;
        snapshotPublished = true;
        if (firstSnapshot) {
            DesktopLyricRenderThread thread = renderThread;
            if (thread != null) java.util.concurrent.locks.LockSupport.unpark(thread);
        }
    }

    /** Main thread. */
    public void setEnabled(boolean value) {
        applyEnabled(value);
        if (settingsWriter != null) settingsWriter.accept(enabled);
    }

    /** Main thread: applies a SettingsCore-originated change without echoing it. */
    void applyEnabled(boolean value) {
        boolean previous = enabled;
        applyEnabledInternal(value);
        if (previous != enabled) {
            Consumer<Boolean> listener = stateListener;
            if (listener != null) listener.accept(enabled);
        }
    }

    private void applyEnabledInternal(boolean value) {
        if (value && window == MemoryUtil.NULL) {
            // Disabled desktop lyrics are completely lazy: create their native
            // surface and qml4j instance only on the first explicit enable.
            store.putBool(ENABLED_KEY, true);
            enabled = true;
            create();
            if (window == MemoryUtil.NULL) {
                enabled = false;
                store.putBool(ENABLED_KEY, false);
                return;
            }
        }
        if (window == MemoryUtil.NULL) {
            enabled = false;
            store.putBool(ENABLED_KEY, false);
            return;
        }
        if (value && !positioningSupported) {
            enabled = false;
            store.putBool(ENABLED_KEY, false);
            Logger.warn("cannot enable desktop lyrics on native Wayland; "
                    + "start QPlayer through XWayland to keep it transparent and draggable");
            if (settingsWriter != null) settingsWriter.accept(false);
            return;
        }
        enabled = value;
        store.putBool(ENABLED_KEY, value);
        if (value) {
            startRenderThread();
            if (firstFrameReady) showWindow();
            DesktopLyricRenderThread thread = renderThread;
            if (thread != null) java.util.concurrent.locks.LockSupport.unpark(thread);
        } else {
            GLFW.glfwHideWindow(window);
        }
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    void requestPrevious() {
        postPlayback(PlayerController::prev);
    }

    void requestTogglePlayback() {
        postPlayback(PlayerController::toggle);
    }

    void requestNext() {
        postPlayback(PlayerController::next);
    }

    void requestToggleMousePassthrough() {
        if (mainPoster != null) mainPoster.accept(() -> setMousePassthrough(!mousePassthrough));
    }

    void requestOpenPlayer() {
        if (mainPoster != null && playerRestorer != null) mainPoster.accept(playerRestorer);
    }

    void requestClose() {
        if (mainPoster != null) mainPoster.accept(() -> setEnabled(false));
    }

    /** Main thread. The unlock button remains interactive through region polling. */
    public void setMousePassthrough(boolean value) {
        mousePassthrough = value;
        store.putBool(PASSTHROUGH_KEY, value);
        if (!value) pointerInside = cursorInsideWindow();
        updateMousePassthroughRegion();
        DesktopLyricRenderThread thread = renderThread;
        if (thread != null) java.util.concurrent.locks.LockSupport.unpark(thread);
    }

    /**
     * Main thread, called by the GLFW loop. GLFW's passthrough attribute applies
     * to the whole native window, so briefly disable it only while the pointer is
     * over the always-visible lock button. This keeps that button clickable while
     * every other pixel continues forwarding input to the window below.
     */
    void updateMousePassthroughRegion() {
        long handle = window;
        if (handle == MemoryUtil.NULL) return;
        if (x11InputRegionSupported) {
            if (mousePassthrough == nativeMousePassthrough) return;
            if (AuxiliaryWindowStyle.setX11InputRegion(handle, mousePassthrough,
                    (int) UNLOCK_LEFT, (int) BOTTOM_HIT_TOP,
                    (int) (UNLOCK_RIGHT - UNLOCK_LEFT),
                    (int) (BOTTOM_HIT_BOTTOM - BOTTOM_HIT_TOP), WIDTH, HEIGHT)) {
                nativeMousePassthrough = mousePassthrough;
                return;
            }
            x11InputRegionSupported = false;
        }
        boolean overUnlock = mousePassthrough && cursorOverUnlockButton();
        boolean desiredNative = mousePassthrough && !overUnlock;
        if (desiredNative == nativeMousePassthrough) return;
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_MOUSE_PASSTHROUGH,
                desiredNative ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        nativeMousePassthrough = desiredNative;
    }

    /** Main thread, used when the main Vulkan backend falls back before first frame. */
    void recreate(GraphicsBackend.Kind newKind) {
        shutdownRenderThread();
        disposeWindow();
        kind = transparentBackend(newKind);
        if (enabled) create();
    }

    void onRenderError(Throwable error) {
        Logger.error("desktop lyric render thread crashed: {}", error);
        if (mainPoster != null) mainPoster.accept(() -> setEnabled(false));
    }

    void onFirstFrameRendered() {
        firstFrameReady = true;
        if (mainPoster != null) mainPoster.accept(() -> {
            if (enabled && window != MemoryUtil.NULL) showWindow();
        });
    }

    private void showWindow() {
        AuxiliaryWindowStyle.hideFromTaskSwitchers(window);
        GLFW.glfwShowWindow(window);
    }

    void shutdown() {
        shutdownRenderThread();
        disposeWindow();
    }

    private void shutdownRenderThread() {
        DesktopLyricRenderThread thread = renderThread;
        if (thread == null) return;
        thread.shutdown();
        try {
            thread.join(5000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        renderThread = null;
    }

    private void centerBottom() {
        long monitor = GLFW.glfwGetPrimaryMonitor();
        if (monitor == MemoryUtil.NULL) return;
        GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
        if (mode != null) GLFW.glfwSetWindowPos(window,
                (mode.width() - WIDTH) / 2, mode.height() - HEIGHT - 96);
    }

    private void installDragHandlers() {
        GLFW.glfwSetCursorEnterCallback(window, (win, entered) -> {
            pointerInside = entered;
            DesktopLyricRenderThread thread = renderThread;
            if (thread != null) java.util.concurrent.locks.LockSupport.unpark(thread);
        });
        GLFW.glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
            if (action == GLFW.GLFW_PRESS) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    DoubleBuffer currentX = stack.mallocDouble(1);
                    DoubleBuffer currentY = stack.mallocDouble(1);
                    GLFW.glfwGetCursorPos(win, currentX, currentY);
                    cursorX = currentX.get(0);
                    cursorY = currentY.get(0);
                }
                if (isControlPoint(cursorX, cursorY)) {
                    controlsPressed = true;
                    final float eventX = (float) cursorX;
                    final float eventY = (float) cursorY;
                    postInput(view -> view.dispatchPointerDown(eventX, eventY));
                    return;
                }
                dragging = true;
                dragCursorX0 = cursorX;
                dragCursorY0 = cursorY;
            } else if (action == GLFW.GLFW_RELEASE) {
                if (controlsPressed) {
                    controlsPressed = false;
                    final float eventX = (float) cursorX;
                    final float eventY = (float) cursorY;
                    postInput(view -> view.dispatchPointerUp(eventX, eventY));
                } else if (dragging) {
                    dragging = false;
                    snapToScreenAfterDrag();
                    persistPosition();
                }
            }
        });
        GLFW.glfwSetCursorPosCallback(window, (win, x, y) -> {
            cursorX = x;
            cursorY = y;
            if (!dragging) {
                postInput(view -> view.dispatchPointerMove((float) x, (float) y));
                return;
            }
            // Cursor coordinates are window-local. Read the CURRENT window origin,
            // not the press-time origin: moving the window changes the local cursor
            // coordinate even when the physical pointer stands still. Adding the
            // delta to the current origin cancels that feedback and stays in GLFW's
            // own screen-coordinate units on HiDPI/Retina displays.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer windowX = stack.mallocInt(1);
                IntBuffer windowY = stack.mallocInt(1);
                GLFW.glfwGetWindowPos(win, windowX, windowY);
                GLFW.glfwSetWindowPos(win,
                        (int) Math.round(windowX.get(0) + x - dragCursorX0),
                        (int) Math.round(windowY.get(0) + y - dragCursorY0));
            }
        });
    }

    private void postInput(Consumer<QmlView> event) {
        inputEvents.add(event);
        DesktopLyricRenderThread thread = renderThread;
        if (thread != null) java.util.concurrent.locks.LockSupport.unpark(thread);
    }

    static boolean isControlPoint(double x, double y) {
        boolean top = y >= TOP_HIT_TOP && y < TOP_HIT_BOTTOM
                && ((x >= LEFT_HIT_LEFT && x < LEFT_SINGLE_HIT_RIGHT)
                || (x >= RIGHT_HIT_LEFT && x < RIGHT_HIT_RIGHT));
        boolean bottom = y >= BOTTOM_HIT_TOP && y < BOTTOM_HIT_BOTTOM
                && ((x >= LEFT_HIT_LEFT && x < LEFT_PLAYBACK_HIT_RIGHT)
                || (x >= RIGHT_HIT_LEFT && x < RIGHT_HIT_RIGHT));
        return top || bottom;
    }

    private boolean cursorOverUnlockButton() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer x = stack.mallocDouble(1);
            DoubleBuffer y = stack.mallocDouble(1);
            GLFW.glfwGetCursorPos(window, x, y);
            return isUnlockPoint(x.get(0), y.get(0));
        }
    }

    static boolean isUnlockPoint(double x, double y) {
        return x >= UNLOCK_LEFT && x < UNLOCK_RIGHT
                && y >= BOTTOM_HIT_TOP && y < BOTTOM_HIT_BOTTOM;
    }

    private boolean cursorInsideWindow() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer x = stack.mallocDouble(1);
            DoubleBuffer y = stack.mallocDouble(1);
            GLFW.glfwGetCursorPos(window, x, y);
            return x.get(0) >= 0d && x.get(0) < WIDTH && y.get(0) >= 0d && y.get(0) < HEIGHT;
        }
    }

    private void postPlayback(Consumer<PlayerController> action) {
        PlayerController target = controller;
        if (target == null || mainPoster == null) return;
        mainPoster.accept(() -> action.accept(target));
    }

    private void cacheFramebufferSize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window, width, height);
            framebufferSize.set(new FramebufferSize(
                    Math.max(1, width.get(0)), Math.max(1, height.get(0))));
        }
    }

    private void persistPosition() {
        if (window == MemoryUtil.NULL) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            GLFW.glfwGetWindowPos(window, x, y);
            store.putInt(X_KEY, x.get(0));
            store.putInt(Y_KEY, y.get(0));
        }
    }

    /** Distance (px) from a work-area edge within which a drag-released window
     *  snaps flush to it, rather than just being left wherever the cursor let go. */
    private static final int SNAP_PX = 24;

    /** After a drag ends: pull the window back onto whichever monitor it mostly
     *  overlaps (it may now be dragged fully or partly off every screen -- GLFW
     *  never stopped that during the drag itself) and snap it flush to a nearby
     *  edge. No-op, silently, if it's already fully on-screen and not near an edge. */
    private void snapToScreenAfterDrag() {
        if (window == MemoryUtil.NULL) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer xb = stack.mallocInt(1);
            IntBuffer yb = stack.mallocInt(1);
            GLFW.glfwGetWindowPos(window, xb, yb);
            int[] snapped = clampAndSnap(xb.get(0), yb.get(0));
            if (snapped[0] != xb.get(0) || snapped[1] != yb.get(0)) {
                GLFW.glfwSetWindowPos(window, snapped[0], snapped[1]);
            }
        }
    }

    /** Clamps (x, y) fully inside the work area it mostly overlaps, snapping flush
     *  to an edge within {@link #SNAP_PX} of it (also covers "way off-screen": that
     *  reads as "way past the edge threshold", clamped the same way). */
    private int[] clampAndSnap(int x, int y) {
        int[] wa = bestWorkArea(x, y);
        int wx = wa[0], wy = wa[1], ww = wa[2], wh = wa[3];
        int maxX = wx + Math.max(WIDTH, ww) - WIDTH;
        int maxY = wy + Math.max(HEIGHT, wh) - HEIGHT;
        int snappedX = x <= wx + SNAP_PX ? wx
                : x >= maxX - SNAP_PX ? maxX
                : Math.max(wx, Math.min(x, maxX));
        int snappedY = y <= wy + SNAP_PX ? wy
                : y >= maxY - SNAP_PX ? maxY
                : Math.max(wy, Math.min(y, maxY));
        return new int[]{snappedX, snappedY};
    }

    /** The work area (x, y, width, height) of whichever connected monitor the
     *  window rect at (x, y) overlaps the most; the primary monitor if it
     *  currently overlaps none of them (dragged fully off every screen). */
    private int[] bestWorkArea(int x, int y) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer monitors = GLFW.glfwGetMonitors();
            long best = MemoryUtil.NULL;
            long bestOverlap = 0L;
            if (monitors != null) {
                for (int i = 0; i < monitors.limit(); i++) {
                    long m = monitors.get(i);
                    IntBuffer mx = stack.mallocInt(1);
                    IntBuffer my = stack.mallocInt(1);
                    IntBuffer mw = stack.mallocInt(1);
                    IntBuffer mh = stack.mallocInt(1);
                    GLFW.glfwGetMonitorWorkarea(m, mx, my, mw, mh);
                    int overlapW = Math.max(0,
                            Math.min(x + WIDTH, mx.get(0) + mw.get(0)) - Math.max(x, mx.get(0)));
                    int overlapH = Math.max(0,
                            Math.min(y + HEIGHT, my.get(0) + mh.get(0)) - Math.max(y, my.get(0)));
                    long overlap = (long) overlapW * overlapH;
                    if (overlap > bestOverlap) {
                        bestOverlap = overlap;
                        best = m;
                    }
                }
            }
            if (best == MemoryUtil.NULL) best = GLFW.glfwGetPrimaryMonitor();
            IntBuffer wx = stack.mallocInt(1);
            IntBuffer wy = stack.mallocInt(1);
            IntBuffer ww = stack.mallocInt(1);
            IntBuffer wh = stack.mallocInt(1);
            GLFW.glfwGetMonitorWorkarea(best, wx, wy, ww, wh);
            return new int[]{wx.get(0), wy.get(0), ww.get(0), wh.get(0)};
        }
    }

    private void disposeWindow() {
        long handle = window;
        if (handle == MemoryUtil.NULL) return;
        Callbacks.glfwFreeCallbacks(handle);
        GLFW.glfwDestroyWindow(handle);
        window = MemoryUtil.NULL;
        nativeMousePassthrough = false;
        x11InputRegionSupported = false;
        pointerInside = false;
    }

    private static GraphicsBackend.Kind transparentBackend(GraphicsBackend.Kind requested) {
        if (requested == GraphicsBackend.Kind.VULKAN) {
            Logger.info("desktop lyric window: using OpenGL because GLFW NO_API/Vulkan "
                    + "windows do not expose portable per-pixel transparency");
            return GraphicsBackend.Kind.GL;
        }
        return requested;
    }

    record FramebufferSize(int width, int height) {
    }
}
