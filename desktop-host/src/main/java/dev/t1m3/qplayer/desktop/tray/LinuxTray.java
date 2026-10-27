package dev.t1m3.qplayer.desktop.tray;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
    }

    interface GObject extends Library {
        long g_signal_connect_data(Pointer instance, String detailedSignal, Callback handler,
                                   Pointer data, Pointer destroyData, int connectFlags);
    }

    interface GLib extends Library {
        int g_idle_add(GSourceFunc function, Pointer data);
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
    private final List<Callback> keepAlive = new ArrayList<>(); // JNA callbacks must not be GC'd

    private Gtk gtk;
    private GObject gobject;
    private GLib glib;
    private AppIndicator appIndicator;

    private Pointer indicator;
    private volatile boolean running;
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
        if (!(handle instanceof Item it) || it.widget == null || glib == null) return;
        GSourceFunc fn = data -> {
            try { gtk.gtk_menu_item_set_label(it.widget, label); } catch (Throwable ignored) {}
            return false; // one-shot
        };
        keepAlive.add(fn);
        glib.g_idle_add(fn, Pointer.NULL);
    }

    void setIconPng(byte[] png) {
        this.iconPng = png;
    }

    void setTooltip(String tip) {
        this.tooltip = tip;
        if (appIndicator != null && indicator != null && glib != null) {
            GSourceFunc fn = data -> {
                try { appIndicator.app_indicator_set_title(indicator, tip); } catch (Throwable ignored) {}
                return false;
            };
            keepAlive.add(fn);
            glib.g_idle_add(fn, Pointer.NULL);
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

        final Object ready = new Object();
        final boolean[] ok = {false};
        final boolean[] done = {false};
        Thread t = new Thread(() -> {
            boolean built = build();
            synchronized (ready) { ok[0] = built; done[0] = true; ready.notifyAll(); }
            if (built) {
                running = true;
                try { gtk.gtk_main(); } catch (Throwable e) { Logger.warn("gtk_main exited: {}", e); }
            }
        }, "qplayer-gtktray");
        t.setDaemon(true);
        t.start();
        synchronized (ready) {
            while (!done[0]) {
                try { ready.wait(5000); } catch (InterruptedException e) { break; }
                break;
            }
        }
        return ok[0];
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
            if (iconDir != null) {
                appIndicator.app_indicator_set_icon_theme_path(indicator, iconDir.getAbsolutePath());
                if (iconName != null) appIndicator.app_indicator_set_icon_full(indicator, iconName, "QPlayer");
            }
            appIndicator.app_indicator_set_title(indicator, tooltip);

            Pointer menu = gtk.gtk_menu_new();
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
        if (!running) return;
        running = false;
        if (glib == null) return;
        GSourceFunc fn = data -> {
            try {
                if (indicator != null) appIndicator.app_indicator_set_status(indicator, STATUS_PASSIVE);
                gtk.gtk_main_quit();
            } catch (Throwable ignored) {}
            return false;
        };
        keepAlive.add(fn);
        glib.g_idle_add(fn, Pointer.NULL);
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
