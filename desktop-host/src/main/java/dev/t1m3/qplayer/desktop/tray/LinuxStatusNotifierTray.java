package dev.t1m3.qplayer.desktop.tray;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import dev.t1m3.qplayer.util.Logger;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Native Wayland/X11-independent Linux tray using StatusNotifierItem and
 * DBusMenu directly over GDBus. The desktop owns all pointer input: left click
 * invokes {@code Activate}, right click renders the exported menu. Consequently
 * this neither creates an XEmbed input window nor asks a Wayland compositor for
 * input-capture permission.
 */
final class LinuxStatusNotifierTray extends LinuxTrayBackend {

    private static final String ITEM_PATH = "/StatusNotifierItem";
    private static final String MENU_PATH = "/MenuBar";
    private static final String ITEM_IFACE = "org.kde.StatusNotifierItem";
    private static final String MENU_IFACE = "com.canonical.dbusmenu";
    private static final String WATCHER = "org.kde.StatusNotifierWatcher";
    private static final int SESSION_BUS = 2;
    private static final int OWNER_FLAGS = 0x1 | 0x2;
    private static final int CALL_FLAGS_NONE = 0;

    private static final String BUS_NAME = "org.freedesktop.StatusNotifierItem-"
            + ProcessHandle.current().pid() + "-1";

    private static final String XML =
            "<node>"
            + "<interface name='org.kde.StatusNotifierItem'>"
            + " <method name='ContextMenu'><arg type='i' direction='in'/><arg type='i' direction='in'/></method>"
            + " <method name='Activate'><arg type='i' direction='in'/><arg type='i' direction='in'/></method>"
            + " <method name='SecondaryActivate'><arg type='i' direction='in'/><arg type='i' direction='in'/></method>"
            + " <method name='Scroll'><arg type='i' direction='in'/><arg type='s' direction='in'/></method>"
            + " <property name='Category' type='s' access='read'/>"
            + " <property name='Id' type='s' access='read'/>"
            + " <property name='Title' type='s' access='read'/>"
            + " <property name='Status' type='s' access='read'/>"
            + " <property name='WindowId' type='u' access='read'/>"
            + " <property name='IconName' type='s' access='read'/>"
            + " <property name='IconPixmap' type='a(iiay)' access='read'/>"
            + " <property name='OverlayIconName' type='s' access='read'/>"
            + " <property name='OverlayIconPixmap' type='a(iiay)' access='read'/>"
            + " <property name='AttentionIconName' type='s' access='read'/>"
            + " <property name='AttentionIconPixmap' type='a(iiay)' access='read'/>"
            + " <property name='AttentionMovieName' type='s' access='read'/>"
            + " <property name='ToolTip' type='(sa(iiay)ss)' access='read'/>"
            + " <property name='ItemIsMenu' type='b' access='read'/>"
            + " <property name='Menu' type='o' access='read'/>"
            + " <property name='IconThemePath' type='s' access='read'/>"
            + " <signal name='NewTitle'/><signal name='NewIcon'/>"
            + " <signal name='NewAttentionIcon'/><signal name='NewOverlayIcon'/>"
            + " <signal name='NewToolTip'/>"
            + " <signal name='NewStatus'><arg type='s'/></signal>"
            + "</interface>"
            + "<interface name='com.canonical.dbusmenu'>"
            + " <method name='GetLayout'>"
            + "  <arg type='i' direction='in'/><arg type='i' direction='in'/><arg type='as' direction='in'/>"
            + "  <arg type='u' direction='out'/><arg type='(ia{sv}av)' direction='out'/>"
            + " </method>"
            + " <method name='GetGroupProperties'>"
            + "  <arg type='ai' direction='in'/><arg type='as' direction='in'/>"
            + "  <arg type='a(ia{sv})' direction='out'/>"
            + " </method>"
            + " <method name='Event'>"
            + "  <arg type='i' direction='in'/><arg type='s' direction='in'/><arg type='v' direction='in'/><arg type='u' direction='in'/>"
            + " </method>"
            + " <method name='EventGroup'>"
            + "  <arg type='a(isvu)' direction='in'/><arg type='ai' direction='out'/>"
            + " </method>"
            + " <method name='AboutToShow'><arg type='i' direction='in'/><arg type='b' direction='out'/></method>"
            + " <method name='AboutToShowGroup'>"
            + "  <arg type='ai' direction='in'/><arg type='ai' direction='out'/><arg type='ai' direction='out'/>"
            + " </method>"
            + " <property name='Version' type='u' access='read'/>"
            + " <property name='TextDirection' type='s' access='read'/>"
            + " <property name='Status' type='s' access='read'/>"
            + " <property name='IconThemePath' type='as' access='read'/>"
            + " <signal name='ItemsPropertiesUpdated'><arg type='a(ia{sv})'/><arg type='a(ias)'/></signal>"
            + " <signal name='LayoutUpdated'><arg type='u'/><arg type='i'/></signal>"
            + "</interface>"
            + "</node>";

    interface GLib extends Library {
        Pointer g_main_context_new();
        void g_main_context_push_thread_default(Pointer context);
        Pointer g_main_loop_new(Pointer context, boolean running);
        void g_main_loop_run(Pointer loop);
        void g_main_loop_quit(Pointer loop);
        Pointer g_variant_parse(Pointer type, String text, Pointer limit,
                                Pointer endptr, Pointer[] error);
        Pointer g_variant_new_string(String value);
        Pointer g_variant_new_boolean(boolean value);
        Pointer g_variant_new_uint32(int value);
        Pointer g_variant_new_object_path(String value);
        Pointer g_variant_get_child_value(Pointer value, long index);
        int g_variant_get_int32(Pointer value);
        Pointer g_variant_get_string(Pointer value, Pointer length);
        void g_variant_unref(Pointer value);
        void g_error_free(Pointer error);
    }

    interface Gio extends Library {
        int g_bus_own_name(int busType, String name, int flags,
                           BusAcquired acquired, NameAcquired nameAcquired,
                           NameLost nameLost, Pointer data, Pointer destroy);
        void g_bus_unown_name(int ownerId);
        int g_bus_watch_name(int busType, String name, int flags,
                             NameAppeared appeared, NameVanished vanished,
                             Pointer data, Pointer destroy);
        void g_bus_unwatch_name(int watcherId);
        Pointer g_dbus_node_info_new_for_xml(String xml, Pointer[] error);
        Pointer g_dbus_node_info_lookup_interface(Pointer node, String name);
        int g_dbus_connection_register_object(Pointer connection, String path,
                                              Pointer info, VTable table,
                                              Pointer data, Pointer destroy,
                                              Pointer[] error);
        Pointer g_dbus_connection_call_sync(Pointer connection, String busName,
                                            String objectPath, String interfaceName,
                                            String methodName, Pointer parameters,
                                            Pointer replyType, int flags,
                                            int timeoutMs, Pointer cancellable,
                                            Pointer[] error);
        boolean g_dbus_connection_emit_signal(Pointer connection, String destination,
                                              String path, String iface, String signal,
                                              Pointer parameters, Pointer[] error);
        void g_dbus_method_invocation_return_value(Pointer invocation, Pointer value);
    }

    interface BusAcquired extends Callback {
        void invoke(Pointer connection, String name, Pointer data);
    }

    interface NameAcquired extends Callback {
        void invoke(Pointer connection, String name, Pointer data);
    }

    interface NameLost extends Callback {
        void invoke(Pointer connection, String name, Pointer data);
    }

    interface NameAppeared extends Callback {
        void invoke(Pointer connection, String name, String owner, Pointer data);
    }

    interface NameVanished extends Callback {
        void invoke(Pointer connection, String name, Pointer data);
    }

    interface MethodCall extends Callback {
        void invoke(Pointer connection, String sender, String path, String iface,
                    String method, Pointer parameters, Pointer invocation, Pointer data);
    }

    interface GetProperty extends Callback {
        Pointer invoke(Pointer connection, String sender, String path, String iface,
                       String property, Pointer error, Pointer data);
    }

    interface SetProperty extends Callback {
        boolean invoke(Pointer connection, String sender, String path, String iface,
                       String property, Pointer value, Pointer error, Pointer data);
    }

    public static final class VTable extends Structure {
        public MethodCall methodCall;
        public GetProperty getProperty;
        public SetProperty setProperty;
        public Pointer p0, p1, p2, p3, p4, p5, p6, p7;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("methodCall", "getProperty", "setProperty",
                    "p0", "p1", "p2", "p3", "p4", "p5", "p6", "p7");
        }
    }

    private static final class Item {
        final int id;
        volatile String label;
        final Runnable action;
        final boolean separator;

        Item(int id, String label, Runnable action, boolean separator) {
            this.id = id;
            this.label = label;
            this.action = action;
            this.separator = separator;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final List<Callback> keepAlive = new ArrayList<>();
    private final Object readyLock = new Object();
    private GLib glib;
    private Gio gio;
    private Pointer loop;
    private volatile Pointer connection;
    private volatile boolean exported;
    private volatile boolean running;
    private int ownerId;
    private int watcherId;
    private VTable itemTable;
    private VTable menuTable;
    private byte[] iconPng;
    private String iconPixmap = "@a(iiay) []";
    private volatile String tooltip = "QPlayer";
    private volatile int revision = 1;
    private Runnable leftClickAction;

    @Override
    Object addItem(String label, Runnable action) {
        Item item = new Item(items.size() + 1, label, action, false);
        items.add(item);
        return item;
    }

    @Override
    void addSeparator() {
        items.add(new Item(items.size() + 1, null, null, true));
    }

    @Override
    void setLabel(Object handle, String label) {
        if (!(handle instanceof Item item) || label.equals(item.label)) return;
        item.label = label;
        revision++;
        emit(MENU_PATH, MENU_IFACE, "LayoutUpdated",
                "(uint32 " + revision + ", int32 0)");
    }

    @Override
    void setIconPng(byte[] png) {
        iconPng = png;
    }

    @Override
    void setLeftClickAction(Runnable action) {
        leftClickAction = action;
    }

    @Override
    void setTooltip(String tip) {
        tooltip = tip == null ? "QPlayer" : tip;
        emit(ITEM_PATH, ITEM_IFACE, "NewTitle", null);
        emit(ITEM_PATH, ITEM_IFACE, "NewToolTip", null);
    }

    @Override
    boolean install() {
        try {
            glib = Native.load("glib-2.0", GLib.class);
            gio = Native.load("gio-2.0", Gio.class);
            iconPixmap = encodeIconPixmaps(iconPng);
        } catch (Throwable error) {
            Logger.warn("Linux StatusNotifierItem unavailable: {}", error.toString());
            return false;
        }

        Thread thread = new Thread(this::run, "qplayer-sni-tray");
        thread.setDaemon(true);
        thread.start();
        synchronized (readyLock) {
            long deadline = System.currentTimeMillis() + 3000L;
            while (!exported && System.currentTimeMillis() < deadline) {
                try {
                    readyLock.wait(Math.max(1L, deadline - System.currentTimeMillis()));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!exported) shutdown();
        return exported;
    }

    private void run() {
        try {
            Pointer context = glib.g_main_context_new();
            glib.g_main_context_push_thread_default(context);

            BusAcquired busAcquired = (conn, name, data) -> export(conn);
            NameAcquired nameAcquired = (conn, name, data) -> registerWithWatcher(conn);
            NameLost nameLost = (conn, name, data) ->
                    Logger.warn("Linux StatusNotifierItem lost bus name {}", name);
            NameAppeared appeared = (conn, name, owner, data) -> registerWithWatcher(conn);
            NameVanished vanished = (conn, name, data) -> { };
            keepAlive.add(busAcquired);
            keepAlive.add(nameAcquired);
            keepAlive.add(nameLost);
            keepAlive.add(appeared);
            keepAlive.add(vanished);

            ownerId = gio.g_bus_own_name(SESSION_BUS, BUS_NAME, OWNER_FLAGS,
                    busAcquired, nameAcquired, nameLost, Pointer.NULL, Pointer.NULL);
            watcherId = gio.g_bus_watch_name(SESSION_BUS, WATCHER, 0,
                    appeared, vanished, Pointer.NULL, Pointer.NULL);
            loop = glib.g_main_loop_new(context, false);
            running = true;
            glib.g_main_loop_run(loop);
        } catch (Throwable error) {
            Logger.warn("Linux StatusNotifierItem thread failed: {}", error.toString());
            markExportFailed();
        }
    }

    private void export(Pointer conn) {
        try {
            connection = conn;
            Pointer[] error = new Pointer[1];
            Pointer node = gio.g_dbus_node_info_new_for_xml(XML, error);
            if (node == null) {
                logError("introspection XML", error);
                markExportFailed();
                return;
            }
            itemTable = table(this::onItemMethod, this::onItemProperty);
            menuTable = table(this::onMenuMethod, this::onMenuProperty);
            int itemId = gio.g_dbus_connection_register_object(conn, ITEM_PATH,
                    gio.g_dbus_node_info_lookup_interface(node, ITEM_IFACE), itemTable,
                    Pointer.NULL, Pointer.NULL, error);
            int menuId = gio.g_dbus_connection_register_object(conn, MENU_PATH,
                    gio.g_dbus_node_info_lookup_interface(node, MENU_IFACE), menuTable,
                    Pointer.NULL, Pointer.NULL, error);
            exported = itemId != 0 && menuId != 0;
            if (!exported) logError("object export", error);
            synchronized (readyLock) { readyLock.notifyAll(); }
            if (exported) Logger.info("system tray initialized: Linux StatusNotifierItem");
        } catch (Throwable error) {
            Logger.warn("Linux StatusNotifierItem export failed: {}", error.toString());
            markExportFailed();
        }
    }

    private VTable table(ItemMethod method, ItemProperty property) {
        VTable table = new VTable();
        table.methodCall = (conn, sender, path, iface, name, params, invocation, data) ->
                method.invoke(name, params, invocation);
        table.getProperty = (conn, sender, path, iface, name, error, data) ->
                property.get(name);
        table.setProperty = (conn, sender, path, iface, name, value, error, data) -> false;
        table.write();
        keepAlive.add(table.methodCall);
        keepAlive.add(table.getProperty);
        keepAlive.add(table.setProperty);
        return table;
    }

    private void registerWithWatcher(Pointer conn) {
        if (!exported || conn == null) return;
        Pointer[] error = new Pointer[1];
        Pointer reply = gio.g_dbus_connection_call_sync(conn, WATCHER,
                "/StatusNotifierWatcher", WATCHER, "RegisterStatusNotifierItem",
                parse("('" + BUS_NAME + "',)"), Pointer.NULL, CALL_FLAGS_NONE,
                3000, Pointer.NULL, error);
        if (reply != null) {
            glib.g_variant_unref(reply);
        } else {
            logError("watcher registration", error);
        }
    }

    private void onItemMethod(String method, Pointer parameters, Pointer invocation) {
        try {
            if (("Activate".equals(method) || "SecondaryActivate".equals(method))
                    && leftClickAction != null) {
                leftClickAction.run();
            }
            // ContextMenu is intentionally a no-op: Menu points at the DBusMenu
            // object, which lets the host render the right-click menu natively.
        } catch (Throwable error) {
            Logger.warn("Linux tray {} failed: {}", method, error.toString());
        }
        gio.g_dbus_method_invocation_return_value(invocation, Pointer.NULL);
    }

    private Pointer onItemProperty(String property) {
        return switch (property) {
            case "Category" -> glib.g_variant_new_string("ApplicationStatus");
            case "Id" -> glib.g_variant_new_string("qplayer");
            case "Title" -> glib.g_variant_new_string(tooltip);
            case "Status" -> glib.g_variant_new_string("Active");
            case "WindowId" -> glib.g_variant_new_uint32(0);
            case "IconName", "OverlayIconName", "AttentionIconName",
                    "AttentionMovieName", "IconThemePath" -> glib.g_variant_new_string("");
            case "IconPixmap" -> parse(iconPixmap);
            case "OverlayIconPixmap", "AttentionIconPixmap" -> parse("@a(iiay) []");
            case "ToolTip" -> parse("('', @a(iiay) [], 'QPlayer', '"
                    + esc(tooltip) + "')");
            case "ItemIsMenu" -> glib.g_variant_new_boolean(false);
            case "Menu" -> glib.g_variant_new_object_path(MENU_PATH);
            default -> null;
        };
    }

    private void onMenuMethod(String method, Pointer parameters, Pointer invocation) {
        Pointer result = null;
        try {
            switch (method) {
                case "GetLayout" -> result = parse(layout());
                case "GetGroupProperties" -> result = parse(groupProperties());
                case "Event" -> {
                    int id = childInt(parameters, 0);
                    String event = childString(parameters, 1);
                    if ("clicked".equals(event)) invokeItem(id);
                }
                case "EventGroup" -> result = parse("(@ai [],)");
                case "AboutToShow" -> result = parse("(false,)");
                case "AboutToShowGroup" -> result = parse("(@ai [], @ai [])");
                default -> { }
            }
        } catch (Throwable error) {
            Logger.warn("Linux DBusMenu {} failed: {}", method, error.toString());
        }
        gio.g_dbus_method_invocation_return_value(invocation, result);
    }

    private Pointer onMenuProperty(String property) {
        return switch (property) {
            case "Version" -> glib.g_variant_new_uint32(3);
            case "TextDirection" -> glib.g_variant_new_string("ltr");
            case "Status" -> glib.g_variant_new_string("normal");
            case "IconThemePath" -> parse("@as []");
            default -> null;
        };
    }

    private void invokeItem(int id) {
        for (Item item : items) {
            if (item.id == id && item.action != null) {
                item.action.run();
                return;
            }
        }
    }

    private String layout() {
        StringBuilder out = new StringBuilder("(uint32 ").append(revision)
                .append(", (int32 0, {'children-display': <'submenu'>}, [");
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) out.append(',');
            Item item = items.get(index);
            out.append("<(int32 ").append(item.id).append(", ")
                    .append(properties(item)).append(", @av [])>");
        }
        return out.append("]))").toString();
    }

    private String groupProperties() {
        StringBuilder out = new StringBuilder("(@a(ia{sv}) [");
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) out.append(',');
            Item item = items.get(index);
            out.append("(int32 ").append(item.id).append(", ")
                    .append(properties(item)).append(')');
        }
        return out.append("],)").toString();
    }

    private static String properties(Item item) {
        if (item.separator) return "{'type': <'separator'>, 'visible': <true>}";
        return "{'label': <'" + esc(item.label)
                + "'>, 'enabled': <true>, 'visible': <true>}";
    }

    private int childInt(Pointer tuple, int index) {
        Pointer child = glib.g_variant_get_child_value(tuple, index);
        if (child == null) return 0;
        try { return glib.g_variant_get_int32(child); }
        finally { glib.g_variant_unref(child); }
    }

    private String childString(Pointer tuple, int index) {
        Pointer child = glib.g_variant_get_child_value(tuple, index);
        if (child == null) return "";
        try {
            Pointer value = glib.g_variant_get_string(child, Pointer.NULL);
            return value == null ? "" : value.getString(0);
        } finally {
            glib.g_variant_unref(child);
        }
    }

    private void emit(String path, String iface, String signal, String parameters) {
        Pointer conn = connection;
        if (!exported || conn == null || gio == null) return;
        Pointer value = parameters == null ? Pointer.NULL : parse(parameters);
        gio.g_dbus_connection_emit_signal(conn, null, path, iface, signal,
                value, new Pointer[1]);
    }

    private Pointer parse(String text) {
        Pointer[] error = new Pointer[1];
        Pointer value = glib.g_variant_parse(Pointer.NULL, text,
                Pointer.NULL, Pointer.NULL, error);
        if (value == null) logError("variant " + abbreviate(text), error);
        return value;
    }

    private void logError(String operation, Pointer[] error) {
        Logger.warn("Linux StatusNotifierItem {} failed", operation);
        if (error != null && error.length > 0 && error[0] != null) {
            glib.g_error_free(error[0]);
            error[0] = null;
        }
    }

    private void markExportFailed() {
        synchronized (readyLock) { readyLock.notifyAll(); }
    }

    @Override
    void shutdown() {
        running = false;
        try {
            if (watcherId != 0 && gio != null) gio.g_bus_unwatch_name(watcherId);
            if (ownerId != 0 && gio != null) gio.g_bus_unown_name(ownerId);
            if (loop != null && glib != null) glib.g_main_loop_quit(loop);
        } catch (Throwable ignored) { }
    }

    private static String encodeIconPixmaps(byte[] png) {
        if (png == null || png.length == 0) return "@a(iiay) []";
        // Plasma scales the first pixmap; a single 128 px source avoids upscaling
        // a tiny entry on HiDPI panels. Rasterize in premultiplied color to prevent
        // hidden RGB in transparent border pixels from bleeding into the edge.
        int size = 128;
        try (Image source = Image.makeFromEncoded(png);
             Surface surface = Surface.makeRasterN32Premul(size, size);
             Bitmap pixels = new Bitmap()) {
            surface.getCanvas().clear(0);
            surface.getCanvas().drawImageRect(source,
                    Rect.makeWH(source.getWidth(), source.getHeight()),
                    Rect.makeWH(size, size), SamplingMode.MITCHELL, null, true);
            // SNI requires unpremultiplied ARGB in network byte order, not Skia's
            // platform-native N32 layout. Explicit RGBA readback also makes this
            // independent of CPU endianness; emit A,R,G,B below.
            if (!pixels.allocPixels(new ImageInfo(size, size,
                    ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
                    || !surface.readPixels(pixels, 0, 0)) {
                throw new IllegalStateException("Tray icon pixel readback failed");
            }
            byte[] rgba = pixels.readPixels();
            StringBuilder out = new StringBuilder(size * size * 16);
            out.append("@a(iiay) [(128,128,[");
            for (int i = 0; i < rgba.length; i += 4) {
                if (i != 0) out.append(',');
                out.append(rgba[i + 3] & 0xff).append(',')
                        .append(rgba[i] & 0xff).append(',')
                        .append(rgba[i + 1] & 0xff).append(',')
                        .append(rgba[i + 2] & 0xff);
            }
            return out.append("])]").toString();
        }
    }

    private static String esc(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String abbreviate(String value) {
        return value.length() <= 120 ? value : value.substring(0, 117) + "...";
    }

    @FunctionalInterface
    private interface ItemMethod {
        void invoke(String method, Pointer parameters, Pointer invocation);
    }

    @FunctionalInterface
    private interface ItemProperty {
        Pointer get(String property);
    }
}
