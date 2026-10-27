package dev.t1m3.qplayer.desktop.media;

import dev.t1m3.qplayer.desktop.window.DesktopWindow;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Linux system media controls: the MPRIS2 D-Bus interface every desktop's media
 * widget speaks (KDE's panel applet and lock screen, GNOME's shell menu,
 * {@code playerctl}, and the keyboard's play/next/previous keys, which the
 * desktop routes to whichever MPRIS player is active).
 *
 * <p>Implemented straight on GLib's GDBus through JNA — the same approach as
 * {@link LinuxTray}'s libappindicator binding, and for the same reason: no new
 * dependency. Native Image metadata covers the JNA interfaces.
 * D-Bus is a protocol, not a library, so the only thing GDBus provides
 * here is the connection and the marshalling.
 *
 * <p>Two objects are exported at {@code /org/mpris/MediaPlayer2}:
 * {@code org.mpris.MediaPlayer2} (identity, Raise, Quit) and
 * {@code org.mpris.MediaPlayer2.Player} (transport + metadata). Values are read
 * from the {@link PlayerController} when asked, so there is no state to keep in
 * sync here beyond emitting {@code PropertiesChanged} when something the widget
 * displays actually changes.
 *
 * <p>Threading: GDBus callbacks arrive on this class's own GMainContext thread.
 * Playback commands hop to the app's main loop the same way tray clicks do
 * ({@code window.postMainTask}), since {@link PlayerController} expects to be
 * driven from there. Emitting a signal is safe from any thread — GDBusConnection
 * is documented thread-safe — so updates push straight from the caller.
 */
public final class MprisControls implements DesktopMediaControls {

    private static final String BUS_NAME = "org.mpris.MediaPlayer2.qplayer";
    private static final String OBJECT_PATH = "/org/mpris/MediaPlayer2";
    private static final String IFACE_ROOT = "org.mpris.MediaPlayer2";
    private static final String IFACE_PLAYER = "org.mpris.MediaPlayer2.Player";
    private static final String IFACE_PROPS = "org.freedesktop.DBus.Properties";

    private static final int G_BUS_TYPE_SESSION = 2;
    /** G_BUS_NAME_OWNER_FLAGS_REPLACE | ALLOW_REPLACEMENT: a stale name from a
     *  crashed instance must not keep the new one off the bus. */
    private static final int OWNER_FLAGS = 0x1 | 0x2;

    // The interfaces as MPRIS2 defines them. Only what a media widget actually
    // uses is declared — an undeclared member is simply not offered.
    private static final String INTROSPECTION_XML =
            "<node>"
            + "<interface name='org.mpris.MediaPlayer2'>"
            + "  <method name='Raise'/>"
            + "  <method name='Quit'/>"
            + "  <property name='CanQuit' type='b' access='read'/>"
            + "  <property name='CanRaise' type='b' access='read'/>"
            + "  <property name='HasTrackList' type='b' access='read'/>"
            + "  <property name='Identity' type='s' access='read'/>"
            + "  <property name='DesktopEntry' type='s' access='read'/>"
            + "  <property name='SupportedUriSchemes' type='as' access='read'/>"
            + "  <property name='SupportedMimeTypes' type='as' access='read'/>"
            + "</interface>"
            + "<interface name='org.mpris.MediaPlayer2.Player'>"
            + "  <method name='Next'/>"
            + "  <method name='Previous'/>"
            + "  <method name='Pause'/>"
            + "  <method name='PlayPause'/>"
            + "  <method name='Stop'/>"
            + "  <method name='Play'/>"
            + "  <method name='Seek'><arg name='Offset' type='x' direction='in'/></method>"
            + "  <method name='SetPosition'>"
            + "    <arg name='TrackId' type='o' direction='in'/>"
            + "    <arg name='Position' type='x' direction='in'/>"
            + "  </method>"
            + "  <method name='OpenUri'><arg name='Uri' type='s' direction='in'/></method>"
            + "  <signal name='Seeked'><arg name='Position' type='x'/></signal>"
            + "  <property name='PlaybackStatus' type='s' access='read'/>"
            + "  <property name='LoopStatus' type='s' access='readwrite'/>"
            + "  <property name='Rate' type='d' access='readwrite'/>"
            + "  <property name='Shuffle' type='b' access='readwrite'/>"
            + "  <property name='Metadata' type='a{sv}' access='read'/>"
            + "  <property name='Volume' type='d' access='readwrite'/>"
            + "  <property name='Position' type='x' access='read'/>"
            + "  <property name='MinimumRate' type='d' access='read'/>"
            + "  <property name='MaximumRate' type='d' access='read'/>"
            + "  <property name='CanGoNext' type='b' access='read'/>"
            + "  <property name='CanGoPrevious' type='b' access='read'/>"
            + "  <property name='CanPlay' type='b' access='read'/>"
            + "  <property name='CanPause' type='b' access='read'/>"
            + "  <property name='CanSeek' type='b' access='read'/>"
            + "  <property name='CanControl' type='b' access='read'/>"
            + "</interface>"
            + "</node>";

    // ---- GLib / GIO bindings -------------------------------------------------

    interface GLib extends Library {
        Pointer g_main_context_new();
        void g_main_context_push_thread_default(Pointer context);
        Pointer g_main_loop_new(Pointer context, boolean isRunning);
        void g_main_loop_run(Pointer loop);
        void g_main_loop_quit(Pointer loop);
        void g_main_loop_unref(Pointer loop);

        Pointer g_variant_new_string(String value);
        Pointer g_variant_new_boolean(boolean value);
        Pointer g_variant_new_double(double value);
        Pointer g_variant_new_int64(long value);
        /** Text-format parser: the whole reason metadata dictionaries here are
         *  built as strings instead of through GVariantBuilder, which would mean
         *  hand-managing a builder struct and a pile of variadic calls. */
        Pointer g_variant_parse(Pointer type, String text, Pointer limit, Pointer endptr,
                                Pointer[] error);
        double g_variant_get_double(Pointer value);
        boolean g_variant_get_boolean(Pointer value);
        Pointer g_variant_get_string(Pointer value, Pointer length);
        void g_error_free(Pointer error);
        void g_free(Pointer mem);
    }

    interface Gio extends Library {
        int g_bus_own_name(int busType, String name, int flags,
                           BusAcquired busAcquired, NameAcquired nameAcquired, NameLost nameLost,
                           Pointer userData, Pointer freeFunc);
        void g_bus_unown_name(int ownerId);
        Pointer g_dbus_node_info_new_for_xml(String xml, Pointer[] error);
        Pointer g_dbus_node_info_lookup_interface(Pointer info, String name);
        int g_dbus_connection_register_object(Pointer connection, String objectPath,
                                              Pointer interfaceInfo, VTable vtable,
                                              Pointer userData, Pointer userDataFree,
                                              Pointer[] error);
        boolean g_dbus_connection_emit_signal(Pointer connection, String destination,
                                              String objectPath, String interfaceName,
                                              String signalName, Pointer parameters,
                                              Pointer[] error);
        void g_dbus_method_invocation_return_value(Pointer invocation, Pointer parameters);
    }

    interface BusAcquired extends Callback {
        void invoke(Pointer connection, String name, Pointer userData);
    }

    interface NameAcquired extends Callback {
        void invoke(Pointer connection, String name, Pointer userData);
    }

    interface NameLost extends Callback {
        void invoke(Pointer connection, String name, Pointer userData);
    }

    interface MethodCall extends Callback {
        void invoke(Pointer connection, String sender, String objectPath, String interfaceName,
                    String methodName, Pointer parameters, Pointer invocation, Pointer userData);
    }

    interface GetProperty extends Callback {
        Pointer invoke(Pointer connection, String sender, String objectPath, String interfaceName,
                       String propertyName, Pointer error, Pointer userData);
    }

    interface SetProperty extends Callback {
        boolean invoke(Pointer connection, String sender, String objectPath, String interfaceName,
                       String propertyName, Pointer value, Pointer error, Pointer userData);
    }

    /** GDBusInterfaceVTable: three handlers plus eight reserved slots. */
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

    // ---- state ---------------------------------------------------------------

    private final PlayerController controller;
    private final DesktopWindow window;
    // JNA callbacks are called from native code for the process's lifetime; a GC'd
    // one is a crash, so every instance handed to GLib is kept referenced here.
    private final List<Callback> keepAlive = new ArrayList<>();

    private GLib glib;
    private Gio gio;
    private Thread thread;
    private Pointer loop;
    private volatile Pointer connection;
    private volatile int ownerId;
    private volatile boolean registered;
    private VTable vtable;

    // Last values published, so a playback change that a widget can't see (a
    // position tick) doesn't spam PropertiesChanged.
    private volatile String lastStatus = "";
    private volatile String lastMetadata = "";
    private volatile String lastLoopStatus = "";
    private volatile Boolean lastShuffle;
    private volatile long pausedPositionMs;

    public MprisControls(PlayerController controller, DesktopWindow window) {
        this.controller = controller;
        this.window = window;
    }

    public static boolean isSupported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!(os.contains("nux") || os.contains("nix"))) return false;
        // No session bus (a bare TTY, a container) means nothing to talk to.
        if (System.getenv("DBUS_SESSION_BUS_ADDRESS") != null) return true;
        String runtimeDir = System.getenv("XDG_RUNTIME_DIR");
        return runtimeDir != null && new File(runtimeDir, "bus").exists();
    }

    /** Bring the interface up on its own thread. Failures are logged and ignored —
     *  the app simply has no system media controls then. */
    public void start() {
        thread = new Thread(this::run, "qplayer-mpris");
        thread.setDaemon(true);
        thread.start();
    }

    public void shutdown() {
        try {
            if (ownerId != 0 && gio != null) gio.g_bus_unown_name(ownerId);
            if (loop != null && glib != null) glib.g_main_loop_quit(loop);
        } catch (Throwable ignored) {
            // Shutting down anyway.
        }
    }

    private void run() {
        try {
            glib = Native.load("glib-2.0", GLib.class);
            gio = Native.load("gio-2.0", Gio.class);
        } catch (Throwable t) {
            Logger.warn("MPRIS unavailable: {}", t.getMessage());
            return;
        }
        try {
            // Our own main context, not the default one: the tray thread may be
            // running gtk_main on that, and two loops on one context fight.
            Pointer ctx = glib.g_main_context_new();
            glib.g_main_context_push_thread_default(ctx);

            BusAcquired onBus = (conn, name, data) -> registerObjects(conn);
            NameAcquired onName = (conn, name, data) ->
                    Logger.info("MPRIS: owning {}", name);
            NameLost onLost = (conn, name, data) ->
                    Logger.warn("MPRIS: lost the bus name (another instance?)");
            keepAlive.add(onBus);
            keepAlive.add(onName);
            keepAlive.add(onLost);

            ownerId = gio.g_bus_own_name(G_BUS_TYPE_SESSION, BUS_NAME, OWNER_FLAGS,
                    onBus, onName, onLost, Pointer.NULL, Pointer.NULL);

            loop = glib.g_main_loop_new(ctx, false);
            glib.g_main_loop_run(loop);
        } catch (Throwable t) {
            Logger.warn("MPRIS thread died: {}", t);
        }
    }

    private void registerObjects(Pointer conn) {
        try {
            this.connection = conn;
            Pointer[] err = new Pointer[1];
            Pointer node = gio.g_dbus_node_info_new_for_xml(INTROSPECTION_XML, err);
            if (node == null) {
                Logger.warn("MPRIS: introspection XML rejected");
                return;
            }
            vtable = new VTable();
            vtable.methodCall = (c, sender, path, iface, method, params, invocation, data) ->
                    onMethod(iface, method, params, invocation);
            vtable.getProperty = (c, sender, path, iface, prop, error, data) ->
                    onGetProperty(iface, prop);
            vtable.setProperty = (c, sender, path, iface, prop, value, error, data) ->
                    onSetProperty(iface, prop, value);
            vtable.write();
            keepAlive.add(vtable.methodCall);
            keepAlive.add(vtable.getProperty);
            keepAlive.add(vtable.setProperty);

            boolean allRegistered = true;
            for (String iface : new String[]{IFACE_ROOT, IFACE_PLAYER}) {
                Pointer info = gio.g_dbus_node_info_lookup_interface(node, iface);
                int id = gio.g_dbus_connection_register_object(conn, OBJECT_PATH, info, vtable,
                        Pointer.NULL, Pointer.NULL, err);
                if (id == 0) {
                    allRegistered = false;
                    Logger.warn("MPRIS: failed to export {}", iface);
                }
            }
            registered = allRegistered;
            if (registered) publish();
        } catch (Throwable t) {
            Logger.warn("MPRIS registration failed: {}", t);
        }
    }

    // ---- D-Bus method calls --------------------------------------------------

    private void onMethod(String iface, String method, Pointer params, Pointer invocation) {
        try {
            if (IFACE_ROOT.equals(iface)) {
                switch (method) {
                    case "Raise": window.postMainTask(window::restoreFromTray); break;
                    case "Quit": window.postMainTask(window::requestQuit); break;
                    default: break;
                }
            } else if (IFACE_PLAYER.equals(iface)) {
                switch (method) {
                    case "Next": window.postMainTask(controller::next); break;
                    case "Previous": window.postMainTask(controller::prev); break;
                    case "Play":
                        if (!playing()) window.postMainTask(controller::toggle);
                        break;
                    case "Pause":
                        if (playing()) window.postMainTask(controller::toggle);
                        break;
                    case "PlayPause": window.postMainTask(controller::toggle); break;
                    case "Stop":
                        if (playing()) window.postMainTask(controller::toggle);
                        break;
                    // Seek's offset and SetPosition's absolute position are both in
                    // microseconds; the parameters tuple is read through the same
                    // property path GDBus hands us, so pull the int64 out by index.
                    case "Seek": {
                        long deltaUs = tupleInt64(params, 0);
                        long target = positionMs() + deltaUs / 1000L;
                        window.postMainTask(() -> {
                            controller.seek(Math.max(0, target));
                            pausedPositionMs = Math.max(0, target);
                            emitSeeked(Math.max(0, target) * 1000L);
                        });
                        break;
                    }
                    case "SetPosition": {
                        long us = tupleInt64(params, 1);
                        window.postMainTask(() -> {
                            controller.seek(Math.max(0, us / 1000L));
                            pausedPositionMs = Math.max(0, us / 1000L);
                            emitSeeked(Math.max(0, us));
                        });
                        break;
                    }
                    default: break;
                }
            }
        } catch (Throwable t) {
            Logger.warn("MPRIS {}.{} failed: {}", iface, method, t);
        }
        gio.g_dbus_method_invocation_return_value(invocation, Pointer.NULL);
    }

    // g_variant_get_child_value + g_variant_get_int64, declared lazily here rather
    // than in the interface above because they're the only calls that need the
    // child-by-index form.
    private interface VariantChild extends Library {
        Pointer g_variant_get_child_value(Pointer value, long index);
        long g_variant_get_int64(Pointer value);
        void g_variant_unref(Pointer value);
    }

    private VariantChild variantChild;

    private long tupleInt64(Pointer tuple, int index) {
        if (tuple == null) return 0;
        if (variantChild == null) variantChild = Native.load("glib-2.0", VariantChild.class);
        Pointer child = variantChild.g_variant_get_child_value(tuple, index);
        if (child == null) return 0;
        long v = variantChild.g_variant_get_int64(child);
        variantChild.g_variant_unref(child);
        return v;
    }

    // ---- D-Bus properties ----------------------------------------------------

    private Pointer onGetProperty(String iface, String prop) {
        try {
            if (IFACE_ROOT.equals(iface)) {
                switch (prop) {
                    case "CanQuit": return glib.g_variant_new_boolean(true);
                    case "CanRaise": return glib.g_variant_new_boolean(true);
                    case "HasTrackList": return glib.g_variant_new_boolean(false);
                    case "Identity": return glib.g_variant_new_string("QPlayer");
                    case "DesktopEntry": return glib.g_variant_new_string("qplayer");
                    case "SupportedUriSchemes": return parse("@as []");
                    case "SupportedMimeTypes": return parse("@as []");
                    default: return null;
                }
            }
            switch (prop) {
                case "PlaybackStatus": return glib.g_variant_new_string(status());
                case "LoopStatus": return glib.g_variant_new_string(loopStatus());
                case "Rate": return glib.g_variant_new_double(1.0);
                case "MinimumRate": return glib.g_variant_new_double(1.0);
                case "MaximumRate": return glib.g_variant_new_double(1.0);
                case "Shuffle": return glib.g_variant_new_boolean(shuffle());
                case "Metadata": return parse(metadata());
                case "Volume": return glib.g_variant_new_double(volume());
                case "Position": return glib.g_variant_new_int64(positionMs() * 1000L);
                case "CanGoNext": return glib.g_variant_new_boolean(true);
                case "CanGoPrevious": return glib.g_variant_new_boolean(true);
                case "CanPlay": return glib.g_variant_new_boolean(true);
                case "CanPause": return glib.g_variant_new_boolean(true);
                case "CanSeek": return glib.g_variant_new_boolean(duration() > 0);
                case "CanControl": return glib.g_variant_new_boolean(true);
                default: return null;
            }
        } catch (Throwable t) {
            Logger.warn("MPRIS get {}.{} failed: {}", iface, prop, t);
            return null;
        }
    }

    private boolean onSetProperty(String iface, String prop, Pointer value) {
        try {
            if (!IFACE_PLAYER.equals(iface)) return false;
            if ("Volume".equals(prop)) {
                final float v = (float) glib.g_variant_get_double(value);
                window.postMainTask(() -> {
                    controller.setVolume(v);
                    emitPlayerProperties("{'Volume': <" + volume() + ">}");
                });
            } else if ("Shuffle".equals(prop)) {
                final boolean enabled = glib.g_variant_get_boolean(value);
                window.postMainTask(() -> {
                    if (enabled) controller.setPlayMode(1);
                    else if (playMode() == 1) controller.setPlayMode(0);
                });
            } else if ("LoopStatus".equals(prop)) {
                Pointer text = glib.g_variant_get_string(value, Pointer.NULL);
                final String mode = text != null ? text.getString(0) : "None";
                window.postMainTask(() -> {
                    if ("Track".equals(mode)) controller.setPlayMode(2);
                    else if (playMode() != 1) controller.setPlayMode(0);
                });
            } else if (!"Rate".equals(prop)) {
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- change notification -------------------------------------------------

    @Override
    public void onPlaybackChanged() {
        if (!playing()) pausedPositionMs = Math.max(0L, controller.position());
        publish();
    }

    /** Emit PropertiesChanged for whatever a widget actually shows, when it
     *  changed. Position is deliberately not included: MPRIS has clients poll it
     *  (that's why it's exempt from the changed signal in the spec). */
    private void publish() {
        if (!registered || connection == null) return;
        try {
            String status = status();
            String metadata = metadata();
            String loop = loopStatus();
            boolean shuffle = shuffle();
            boolean statusChanged = !status.equals(lastStatus);
            boolean metaChanged = !metadata.equals(lastMetadata);
            boolean loopChanged = !loop.equals(lastLoopStatus);
            boolean shuffleChanged = lastShuffle == null || shuffle != lastShuffle;
            if (!statusChanged && !metaChanged && !loopChanged && !shuffleChanged) return;
            lastStatus = status;
            lastMetadata = metadata;
            lastLoopStatus = loop;
            lastShuffle = shuffle;

            StringBuilder props = new StringBuilder("{");
            if (statusChanged) props.append("'PlaybackStatus': <'").append(status).append("'>");
            if (props.length() > 1 && metaChanged) props.append(", ");
            if (metaChanged) props.append("'Metadata': <").append(metadata).append(">");
            if (props.length() > 1 && loopChanged) props.append(", ");
            if (loopChanged) props.append("'LoopStatus': <'").append(loop).append("'>");
            if (props.length() > 1 && shuffleChanged) props.append(", ");
            if (shuffleChanged) props.append("'Shuffle': <").append(shuffle).append(">");
            props.append("}");

            emitPlayerProperties(props.toString());
        } catch (Throwable t) {
            Logger.warn("MPRIS publish failed: {}", t);
        }
    }

    private void emitPlayerProperties(String properties) {
        Pointer params = parse("('" + IFACE_PLAYER + "', " + properties + ", @as [])");
        if (params != null) {
            gio.g_dbus_connection_emit_signal(connection, null, OBJECT_PATH, IFACE_PROPS,
                    "PropertiesChanged", params, new Pointer[1]);
        }
    }

    private void emitSeeked(long positionUs) {
        if (!registered || connection == null) return;
        Pointer params = parse("(int64 " + positionUs + ",)");
        if (params != null) {
            gio.g_dbus_connection_emit_signal(connection, null, OBJECT_PATH, IFACE_PLAYER,
                    "Seeked", params, new Pointer[1]);
        }
    }

    // ---- value plumbing ------------------------------------------------------

    private boolean playing() {
        return controller.isPlaying();
    }

    private String status() {
        if (playing()) return "Playing";
        return duration() > 0 ? "Paused" : "Stopped";
    }

    private long duration() {
        Track track = controller.currentTrack();
        if (track != null && track.durationMs > 0) return track.durationMs;
        return controller.duration();
    }

    private long positionMs() {
        return playing() ? Math.max(0L, controller.position()) : pausedPositionMs;
    }

    private int playMode() {
        Integer mode = controller.playMode.peek();
        return mode != null ? mode : 0;
    }

    private boolean shuffle() {
        return playMode() == 1;
    }

    private String loopStatus() {
        return playMode() == 2 ? "Track" : "Playlist";
    }

    private double volume() {
        Float v = controller.volume.peek();
        return v != null ? v : 1.0;
    }

    /** The Metadata dictionary in GVariant text form (see {@link GLib#g_variant_parse}). */
    private String metadata() {
        Track track = controller.currentTrack();
        if (track == null) return "@a{sv} {}";
        String title = str(track.title);
        if (title.isEmpty()) return "@a{sv} {}";
        String artist = str(track.artist);
        String album = str(track.album);
        String art = artUrl(track);

        StringBuilder sb = new StringBuilder("{");
        // A track id is mandatory and must be a valid object path; widgets use it
        // to tell one track from the next, so derive it from the title's identity
        // rather than a constant (a constant makes every track look like the same one).
        sb.append("'mpris:trackid': <objectpath '/dev/t1m3/qplayer/track/")
          .append(Integer.toUnsignedString((title + album).hashCode())).append("'>");
        sb.append(", 'xesam:title': <'").append(esc(title)).append("'>");
        if (!artist.isEmpty()) sb.append(", 'xesam:artist': <['").append(esc(artist)).append("']>");
        if (!album.isEmpty()) sb.append(", 'xesam:album': <'").append(esc(album)).append("'>");
        if (!art.isEmpty()) sb.append(", 'mpris:artUrl': <'").append(esc(art)).append("'>");
        sb.append(", 'mpris:length': <int64 ").append(duration() * 1000L).append('>');
        sb.append("}");
        return sb.toString();
    }

    /** Prefer the cached cover file — a widget can load a local path instantly and
     *  without network — falling back to the remote URL. */
    private String artUrl(Track track) {
        String cached = controller.currentCoverPath();
        if (cached != null && !cached.isEmpty()) {
            File f = new File(cached);
            if (f.isFile()) return f.toURI().toString();
        }
        for (String path : new String[]{track.coverLocalPath, track.coverThumbPath}) {
            if (path != null && !path.startsWith("http")) {
                File f = new File(path);
                if (f.isFile()) return f.toURI().toString();
            }
        }
        String url = str(track.coverUrl);
        return url.startsWith("http") ? url : "";
    }

    private static String str(Object v) {
        return v instanceof String ? (String) v : "";
    }

    /** GVariant text format quotes with ', so those and backslashes must escape. */
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private Pointer parse(String text) {
        Pointer[] err = new Pointer[1];
        Pointer v = glib.g_variant_parse(Pointer.NULL, text, Pointer.NULL, Pointer.NULL, err);
        if (v == null) {
            Logger.warn("MPRIS: bad variant text: {}", text);
            if (err[0] != null) glib.g_error_free(err[0]);
        }
        return v;
    }
}
