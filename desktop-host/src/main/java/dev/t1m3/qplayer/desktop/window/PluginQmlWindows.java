package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.plugin.PluginUiSession;
import dev.t1m3.qplayer.util.Logger;
import io.github.timer_err.qml4j.render.QmlView;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** Main-thread owner for isolated third-party QML windows. */
public final class PluginQmlWindows implements AutoCloseable {
    private static final int WIDTH = 720;
    private static final int HEIGHT = 560;
    private final PlayerController controller;
    private final GlfwClipboard clipboard;
    private final List<Entry> entries = new ArrayList<>();

    public PluginQmlWindows(PlayerController controller, DesktopWindow window) {
        this.controller = controller;
        this.clipboard = new GlfwClipboard(window);
    }

    GlfwClipboard clipboard() { return clipboard; }

    public void open(String pluginId, String contributionId) {
        for (Entry entry : entries) {
            if (entry.key.equals(pluginId + ":" + contributionId) && !entry.closed) {
                GLFW.glfwShowWindow(entry.window);
                GLFW.glfwFocusWindow(entry.window);
                return;
            }
        }
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
        long handle = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "QPlayer Plugin",
                MemoryUtil.NULL, MemoryUtil.NULL);
        GLFW.glfwDefaultWindowHints();
        if (handle == MemoryUtil.NULL) {
            Logger.warn("plugin QML window creation failed");
            return;
        }
        Entry entry = new Entry(this, pluginId + ":" + contributionId, handle);
        entries.add(entry);
        installCallbacks(entry);
        entry.thread = new PluginQmlRenderThread(controller, pluginId, contributionId, entry);
        entry.thread.start();
    }

    /** Main event-loop maintenance: reveal first frames and collect closed windows. */
    public void pump() {
        Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.firstFrame && !entry.shown) {
                entry.shown = true;
                GLFW.glfwShowWindow(entry.window);
            }
            if (!entry.closed && GLFW.glfwWindowShouldClose(entry.window)) entry.closed = true;
            if (!entry.closed) continue;
            if (entry.thread != null) {
                entry.thread.shutdown();
                try { entry.thread.join(25L); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // Never destroy a GLFW window/context while its render thread may
                // still be using it. The next main-loop pump collects it after the
                // render thread's finally block has disposed the backend.
                if (entry.thread.isAlive()) continue;
            }
            Callbacks.glfwFreeCallbacks(entry.window);
            GLFW.glfwDestroyWindow(entry.window);
            iterator.remove();
        }
    }

    private static void installCallbacks(Entry entry) {
        GLFW.glfwSetFramebufferSizeCallback(entry.window, (window, w, h) -> {
            if (w > 0 && h > 0) entry.resize = new int[]{w, h};
        });
        GLFW.glfwSetCursorPosCallback(entry.window, (window, x, y) -> {
            entry.cursorX = x;
            entry.cursorY = y;
            entry.input.add(view -> view.dispatchPointerMove((float) x, (float) y));
        });
        GLFW.glfwSetMouseButtonCallback(entry.window, (window, button, action, mods) -> {
            int qmlButton = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? 2
                    : button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE ? 4 : 1;
            float x = (float) entry.cursorX, y = (float) entry.cursorY;
            if (action == GLFW.GLFW_PRESS) {
                entry.input.add(view -> view.dispatchPointerDown(x, y, qmlButton));
            } else if (action == GLFW.GLFW_RELEASE) {
                entry.input.add(view -> view.dispatchPointerUp(x, y, qmlButton));
            }
        });
        GLFW.glfwSetScrollCallback(entry.window, (window, dx, dy) -> {
            float x = (float) entry.cursorX, y = (float) entry.cursorY;
            entry.input.add(view -> view.dispatchWheel(x, y, (float) dx, (float) dy));
        });
        GLFW.glfwSetCharCallback(entry.window, (window, codepoint) -> {
            String text = new String(Character.toChars(codepoint));
            entry.input.add(view -> view.dispatchKey(0, text, true));
        });
        GLFW.glfwSetKeyCallback(entry.window, (window, key, scan, action, mods) -> {
            if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) return;
            int mapped = mapKey(key, (mods & GLFW.GLFW_MOD_SHIFT) != 0);
            if (mapped != 0) {
                boolean down = action == GLFW.GLFW_PRESS;
                boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
                entry.input.add(view -> view.dispatchKey(mapped, "", down, shift));
            }
        });
    }

    private static int mapKey(int key, boolean shift) {
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE: return QmlView.KEY_BACKSPACE;
            case GLFW.GLFW_KEY_ENTER: return QmlView.KEY_ENTER;
            case GLFW.GLFW_KEY_LEFT: return QmlView.KEY_LEFT;
            case GLFW.GLFW_KEY_RIGHT: return QmlView.KEY_RIGHT;
            case GLFW.GLFW_KEY_UP: return QmlView.KEY_UP;
            case GLFW.GLFW_KEY_DOWN: return QmlView.KEY_DOWN;
            case GLFW.GLFW_KEY_HOME: return QmlView.KEY_HOME;
            case GLFW.GLFW_KEY_END: return QmlView.KEY_END;
            case GLFW.GLFW_KEY_ESCAPE: return QmlView.KEY_ESCAPE;
            case GLFW.GLFW_KEY_TAB: return shift ? QmlView.KEY_BACKTAB : QmlView.KEY_TAB;
            default: return 0;
        }
    }

    @Override public void close() {
        for (Entry entry : entries) entry.closed = true;
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!entries.isEmpty() && System.nanoTime() < deadline) {
            pump();
            if (!entries.isEmpty()) java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
        }
        if (!entries.isEmpty()) {
            Logger.warn("{} plugin QML window(s) did not stop before shutdown", entries.size());
        }
    }

    static final class Entry {
        final PluginQmlWindows windows;
        final String key;
        final long window;
        final ConcurrentLinkedQueue<Consumer<QmlView>> input = new ConcurrentLinkedQueue<>();
        volatile int[] resize = new int[]{WIDTH, HEIGHT};
        volatile double cursorX, cursorY;
        volatile boolean firstFrame, shown, closed;
        PluginQmlRenderThread thread;
        Entry(PluginQmlWindows windows, String key, long window) {
            this.windows = windows;
            this.key = key;
            this.window = window;
        }
        void drain(QmlView view) {
            Consumer<QmlView> event;
            while ((event = input.poll()) != null) event.accept(view);
        }
    }
}
