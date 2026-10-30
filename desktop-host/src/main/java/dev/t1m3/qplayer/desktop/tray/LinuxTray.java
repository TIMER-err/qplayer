package dev.t1m3.qplayer.desktop.tray;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * Linux system tray via libayatana-appindicator (falling back to the older
 * libappindicator) + GTK, driven through JNA. The menu is a {@code GtkMenu} whose
 * items are created with {@code gtk_menu_item_new_with_label} (UTF-8), so GTK
 * renders CJK with the system font, without initializing a Java font manager.
 *
 * <p>All GTK calls run on a dedicated thread that owns {@code gtk_main}; updates
 * from other threads (the play/pause relabel) are marshalled back with
 * {@code g_idle_add}. Menu clicks fire on that thread and hop to the app main loop
 * via the wrapped actions. If the libraries aren't present the install fails
 * gracefully and the app runs windowed.
 */
final class LinuxTray extends LinuxTrayBackend {

    private static final int CATEGORY_APPLICATION_STATUS = 0;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_PASSIVE = 0;

    // ---- GTK / GObject / GLib / AppIndicator bindings ----
    interface Gtk extends Library {
        void gtk_init(Pointer argc, Pointer argv);
        void gtk_main();
        void gtk_main_quit();
        Pointer gtk_menu_new();
        Pointer gtk_menu_item_new_with_label(String label);
        Pointer gtk_separator_menu_item_new();
        void gtk_menu_shell_append(Pointer menuShell, Pointer child);
        void gtk_menu_item_set_label(Pointer menuItem, String label);
        void gtk_widget_show(Pointer widget);
        void gtk_widget_show_all(Pointer widget);
        void gtk_widget_destroy(Pointer widget);
    }

    interface GObject extends Library {
        long g_signal_connect_data(Pointer instance, String detailedSignal, Callback handler,
                                   Pointer data, Pointer destroyData, int connectFlags);
        void g_object_get(Pointer instance, String property, int[] value, Pointer terminator);
        void g_object_unref(Pointer instance);
        Pointer g_object_ref_sink(Pointer instance);
    }

    interface GLib extends Library {
        int g_idle_add_full(int priority, GSourceFunc function, Pointer data, DestroyNotify destroy);
        boolean g_source_remove(int sourceId);
    }

    interface AppIndicator extends Library {
        Pointer app_indicator_new(String id, String iconName, int category);
        void app_indicator_set_status(Pointer self, int status);
        void app_indicator_set_menu(Pointer self, Pointer menu);
        void app_indicator_set_icon_theme_path(Pointer self, String path);
        void app_indicator_set_icon_full(Pointer self, String iconName, String iconDesc);
        void app_indicator_set_title(Pointer self, String title);
    }

    interface GCallback extends Callback {
        void invoke(Pointer widget, Pointer data);
    }

    interface GSourceFunc extends Callback {
        boolean invoke(Pointer data);
    }
    interface DestroyNotify extends Callback {
        void invoke(Pointer data);
    }

    interface ConnectionChanged extends Callback {
        void invoke(Pointer indicator, boolean connected, Pointer data);
    }


    // ---- menu model ----
    private static final class Item {
        final String label;
        final Runnable action;
        final boolean separator;
        volatile Pointer widget;     // the GtkMenuItem, set on the GTK thread
        Item(String label, Runnable action, boolean separator) {
            this.label = label; this.action = action; this.separator = separator;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final List<Callback> keepAlive = new ArrayList<>(); // native signal handlers only
    private final Object lifecycleLock = new Object();
    private final Set<IdleTask> pending = new HashSet<>();

    /** GLib's destroy notify releases both callbacks only after native use ends. */
    private final class IdleTask {
        int sourceId;
        final GSourceFunc callback;
        final DestroyNotify destroy;

        IdleTask(Runnable action) {
            callback = data -> {
                try { action.run(); }
                catch (Throwable error) { Logger.warn("Linux tray task failed: {}", error); }
                return false;
            };
            destroy = data -> {
                synchronized (lifecycleLock) { pending.remove(this); }
            };
        }
    }

    private Gtk gtk;
    private GObject gobject;
    private GLib glib;
    private AppIndicator appIndicator;

    private Pointer indicator;
    private Pointer menu;
    private boolean stopped;
    private boolean loopExpected;
    private boolean failed;
    private byte[] iconPng;
    private String tooltip = "QPlayer";
    private File iconDir;

    Object addItem(String label, Runnable action) {
        Item it = new Item(label, action, false);
        items.add(it);
        return it;
    }

    void addSeparator() {
        items.add(new Item(null, null, true));
    }

    void setLabel(Object handle, String label) {
        if (!(handle instanceof Item it)) return;
        enqueue(() -> {
            if (!stopped && it.widget != null) gtk.gtk_menu_item_set_label(it.widget, label);
        }, false);
    }

    void setIconPng(byte[] png) {
        this.iconPng = png;
    }

    void setTooltip(String tip) {
        synchronized (lifecycleLock) {
            this.tooltip = tip;
            enqueue(() -> {
                if (!stopped && indicator != null) appIndicator.app_indicator_set_title(indicator, tip);
            }, false);
        }
    }

    boolean install() {
        try {
            gtk = Native.load("gtk-3", Gtk.class);
            gobject = Native.load("gobject-2.0", GObject.class);
            glib = Native.load("glib-2.0", GLib.class);
            appIndicator = loadAppIndicator();
        } catch (Throwable t) {
            Logger.warn("Linux tray libraries unavailable ({}); tray disabled", t.toString());
            return false;
        }
        if (appIndicator == null) {
            Logger.warn("libappindicator not found; tray disabled");
            return false;
        }

        Thread t = new Thread(() -> {
            try {
                boolean built = build();
                synchronized (lifecycleLock) {
                    failed = !built;
                    loopExpected = built && !stopped;
                    lifecycleLock.notifyAll();
                }
                if (loopExpected) gtk.gtk_main();
            } catch (Throwable error) {
                Logger.warn("Linux tray thread failed: {}", error);
            } finally {
                synchronized (lifecycleLock) {
                    stopped = true;
                    failed = true;
                    loopExpected = false;
                    setAvailable(false);
                    // Remove native sources before releasing their JNA callbacks.
                    for (IdleTask task : new ArrayList<>(pending)) glib.g_source_remove(task.sourceId);
                    if (indicator != null) {
                        appIndicator.app_indicator_set_status(indicator, STATUS_PASSIVE);
                        gobject.g_object_unref(indicator);
                        indicator = null;
                    }
                    if (menu != null) {
                        gtk.gtk_widget_destroy(menu);
                        gobject.g_object_unref(menu);
                        menu = null;
                    }
                    for (Item item : items) item.widget = null;
                    keepAlive.clear();
                    lifecycleLock.notifyAll();
                }
            }
        }, "qplayer-gtktray");
        t.setDaemon(true);
        t.start();
        boolean available;
        synchronized (lifecycleLock) {
            long deadline = System.nanoTime() + 4_000_000_000L;
            while (!isAvailable() && !failed && !stopped) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { lifecycleLock.wait(Math.max(1L, remaining / 1_000_000L)); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            available = isAvailable() && !stopped;
        }
        if (!available) shutdown();
        return available;
    }

    private boolean build() {
        try {
            gtk.gtk_init(Pointer.NULL, Pointer.NULL);

            String iconName = writeIcon();
            indicator = appIndicator.app_indicator_new("qplayer",
                    iconName != null ? iconName : "", CATEGORY_APPLICATION_STATUS);
            if (indicator == null) {
                Logger.warn("app_indicator_new returned null");
                return false;
            }
            ConnectionChanged connected = (self, value, data) -> {
                synchronized (lifecycleLock) {
                    setAvailable(value && !stopped);
                    lifecycleLock.notifyAll();
                }
            };
            keepAlive.add(connected);
            gobject.g_signal_connect_data(indicator, "connection-changed", connected,
                    Pointer.NULL, Pointer.NULL, 0);
            if (iconDir != null) {
                appIndicator.app_indicator_set_icon_theme_path(indicator, iconDir.getAbsolutePath());
                if (iconName != null) appIndicator.app_indicator_set_icon_full(indicator, iconName, "QPlayer");
            }
            appIndicator.app_indicator_set_title(indicator, tooltip);

            menu = gtk.gtk_menu_new();
            gobject.g_object_ref_sink(menu);
            for (Item it : items) {
                Pointer w;
                if (it.separator) {
                    w = gtk.gtk_separator_menu_item_new();
                } else {
                    w = gtk.gtk_menu_item_new_with_label(it.label);
                    GCallback cb = (widget, data) -> { if (it.action != null) it.action.run(); };
                    keepAlive.add(cb);
                    gobject.g_signal_connect_data(w, "activate", cb, Pointer.NULL, Pointer.NULL, 0);
                }
                it.widget = w;
                gtk.gtk_widget_show(w);
                gtk.gtk_menu_shell_append(menu, w);
            }
            gtk.gtk_widget_show_all(menu);

            appIndicator.app_indicator_set_menu(indicator, menu);
            appIndicator.app_indicator_set_status(indicator, STATUS_ACTIVE);
            synchronized (lifecycleLock) {
                int[] connectedValue = new int[1];
                gobject.g_object_get(indicator, "connected", connectedValue, Pointer.NULL);
                setAvailable(connectedValue[0] != 0 && !stopped);
                lifecycleLock.notifyAll();
            }
            Logger.info("system tray initialized: Linux AppIndicator");
            return true;
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            Logger.warn("Linux tray build failed:\n{}", sw);
            return false;
        }
    }

    void shutdown() {
        synchronized (lifecycleLock) {
            if (stopped) return;
            stopped = true;
            setAvailable(false);
            lifecycleLock.notifyAll();
            // Build may still be running. It checks stopped before entering GTK.
            if (loopExpected) enqueue(gtk::gtk_main_quit, true);
        }
    }

    private void enqueue(Runnable action, boolean shutdownTask) {
        synchronized (lifecycleLock) {
            if (glib == null || !loopExpected || (stopped && !shutdownTask)) return;
            IdleTask task = new IdleTask(() -> {
                synchronized (lifecycleLock) { action.run(); }
            });
            pending.add(task);
            try {
                task.sourceId = glib.g_idle_add_full(200, task.callback, Pointer.NULL, task.destroy);
                if (task.sourceId == 0) pending.remove(task);
            } catch (Throwable error) {
                pending.remove(task);
                throw error;
            }
        }
    }

    private AppIndicator loadAppIndicator() {
        for (String name : new String[]{"ayatana-appindicator3", "appindicator3"}) {
            try {
                return Native.load(name, AppIndicator.class);
            } catch (Throwable ignored) {
                // try next
            }
        }
        return null;
    }

    /** Write the icon PNG into a temp theme dir; returns the icon name (basename
     *  without extension) for app_indicator_set_icon_full, or null on failure. */
    private String writeIcon() {
        if (iconPng == null) return null;
        try {
            iconDir = java.nio.file.Files.createTempDirectory("qplayer-tray").toFile();
            iconDir.deleteOnExit();
            String name = "qplayer-tray";
            File png = new File(iconDir, name + ".png");
            png.deleteOnExit();
            java.nio.file.Files.write(png.toPath(), iconPng);
            return name;
        } catch (Throwable t) {
            Logger.warn("Linux tray icon write failed: {}", t);
            return null;
        }
    }
}
