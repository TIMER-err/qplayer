package dev.t1m3.qplayer.desktop.app;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.lang.ref.Reference;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** xdg-desktop-portal FileChooser over D-Bus; deliberately never initializes GTK. */
final class LinuxPortalFileDialog {
    private static final String SERVICE = "org.freedesktop.portal.Desktop";
    private static final String DESKTOP = "/org/freedesktop/portal/desktop";
    private static final String REQUEST = "org.freedesktop.portal.Request";

    interface GLib extends Library {
        Pointer g_main_context_new();
        void g_main_context_push_thread_default(Pointer context);
        void g_main_context_pop_thread_default(Pointer context);
        void g_main_context_unref(Pointer context);
        Pointer g_main_loop_new(Pointer context, boolean running);
        void g_main_loop_run(Pointer loop);
        void g_main_loop_quit(Pointer loop);
        void g_main_loop_unref(Pointer loop);
        Pointer g_variant_new_string(String value);
        Pointer g_variant_new_boolean(boolean value);
        Pointer g_variant_new_uint32(int value);
        Pointer g_variant_new_bytestring(byte[] value);
        Pointer g_variant_new_tuple(Pointer[] children, long count);
        Pointer g_variant_new_array(Pointer type, Pointer[] children, long count);
        Pointer g_variant_new_dict_entry(Pointer key, Pointer value);
        Pointer g_variant_new_variant(Pointer value);
        Pointer g_variant_get_child_value(Pointer value, long index);
        Pointer g_variant_lookup_value(Pointer value, String key, Pointer type);
        long g_variant_n_children(Pointer value);
        int g_variant_get_uint32(Pointer value);
        Pointer g_variant_get_string(Pointer value, Pointer length);
        void g_variant_unref(Pointer value);
        void g_error_free(Pointer error);
    }

    interface Gio extends Library {
        Pointer g_bus_get_sync(int busType, Pointer cancellable, Pointer[] error);
        Pointer g_dbus_connection_get_unique_name(Pointer connection);
        Pointer g_dbus_connection_call_sync(Pointer connection, String busName, String path,
                                            String iface, String method, Pointer parameters,
                                            Pointer replyType, int flags, int timeout,
                                            Pointer cancellable, Pointer[] error);
        int g_dbus_connection_signal_subscribe(Pointer connection, String sender, String iface,
                                               String member, String path, String arg0, int flags,
                                               Response callback, Pointer data, Pointer destroy);
        void g_dbus_connection_signal_unsubscribe(Pointer connection, int id);
        void g_object_unref(Pointer object);
    }

    interface Response extends Callback {
        void invoke(Pointer connection, String sender, String path, String iface,
                    String member, Pointer parameters, Pointer data);
    }

    private final GLib glib = Native.load("glib-2.0", GLib.class,
            Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
    private final Gio gio = Native.load("gio-2.0", Gio.class,
            Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
    private Pointer loop;
    private String requestPath;
    private String selection;
    private RuntimeException failure;
    private boolean completed;

    private LinuxPortalFileDialog() {}

    static String show(String title, String directory, boolean directoryOnly,
                       String description, String[] extensions, String suggestedName, boolean save) {
        return new LinuxPortalFileDialog().run(title, directory, directoryOnly,
                description, extensions, suggestedName, save);
    }

    private String run(String title, String directory, boolean directoryOnly,
                       String description, String[] extensions, String suggestedName, boolean save) {
        Pointer context = glib.g_main_context_new();
        if (context == null) throw new IllegalStateException("Cannot create portal GLib context");
        glib.g_main_context_push_thread_default(context);
        Pointer connection = null;
        int subscription = 0;
        int ownerSubscription = 0;
        Response response = this::onResponse;
        Response ownerChanged = (conn, sender, path, iface, member, parameters, data) -> {
            if (completed) return;
            Pointer owner = glib.g_variant_get_child_value(parameters, 2);
            try {
                if (text(glib.g_variant_get_string(owner, Pointer.NULL)).isEmpty()) {
                    failure = new IllegalStateException("FileChooser portal exited before responding");
                    completed = true;
                    glib.g_main_loop_quit(loop);
                }
            } finally {
                glib.g_variant_unref(owner);
            }
        };
        try {
            loop = glib.g_main_loop_new(context, false);
            if (loop == null) throw new IllegalStateException("Cannot create portal GLib loop");
            Pointer[] error = new Pointer[1];
            connection = gio.g_bus_get_sync(2, Pointer.NULL, error); // session bus
            if (connection == null) throw error("Connecting to session D-Bus", error[0]);
            String sender = text(gio.g_dbus_connection_get_unique_name(connection));
            String token = "qplayer" + UUID.randomUUID().toString().replace("-", "");
            requestPath = DESKTOP + "/request/" + sender.substring(1).replace('.', '_') + "/" + token;
            // Subscribe BEFORE OpenFile/SaveFile. A fast cancellation may precede the reply.
            // Match the returned path in the callback, including older portal implementations.
            subscription = gio.g_dbus_connection_signal_subscribe(connection, SERVICE, REQUEST,
                    "Response", null, null, 0, response, Pointer.NULL, Pointer.NULL);
            if (subscription == 0) throw new IllegalStateException("Cannot subscribe to portal response");
            ownerSubscription = gio.g_dbus_connection_signal_subscribe(connection,
                    "org.freedesktop.DBus", "org.freedesktop.DBus", "NameOwnerChanged",
                    "/org/freedesktop/DBus", SERVICE, 0, ownerChanged, Pointer.NULL, Pointer.NULL);
            if (ownerSubscription == 0) throw new IllegalStateException("Cannot watch portal lifetime");
            List<Pointer> options = new ArrayList<>();
            option(options, "handle_token", glib.g_variant_new_string(token));
            option(options, "modal", glib.g_variant_new_boolean(true));
            option(options, "current_folder", glib.g_variant_new_bytestring(Native.toByteArray(directory, "UTF-8")));
            if (save) {
                if (suggestedName != null && !suggestedName.isEmpty()) {
                    option(options, "current_name", glib.g_variant_new_string(suggestedName));
                }
            } else {
                option(options, "multiple", glib.g_variant_new_boolean(false));
                option(options, "directory", glib.g_variant_new_boolean(directoryOnly));
            }
            if (!directoryOnly && extensions != null && extensions.length > 0) {
                Pointer[] patterns = new Pointer[extensions.length];
                for (int i = 0; i < extensions.length; i++) {
                    patterns[i] = tuple(glib.g_variant_new_uint32(0),
                            glib.g_variant_new_string(extensionPattern(extensions[i])));
                }
                Pointer filter = tuple(glib.g_variant_new_string(description), array(patterns));
                option(options, "filters", array(filter));
            }
            // An empty parent identifier is supported on both X11 and Wayland. The portal
            // owns the actual dialog in its own process, independent of GLFW's toolkit.
            Pointer parameters = tuple(glib.g_variant_new_string(""),
                    glib.g_variant_new_string(title), array(options.toArray(Pointer[]::new)));
            Pointer reply = gio.g_dbus_connection_call_sync(connection, SERVICE, DESKTOP,
                    "org.freedesktop.portal.FileChooser", save ? "SaveFile" : "OpenFile",
                    parameters, Pointer.NULL, 0, 30000, Pointer.NULL, error);
            if (reply == null) throw error("Opening xdg-desktop-portal file chooser (install a FileChooser portal backend)", error[0]);
            try {
                Pointer path = glib.g_variant_get_child_value(reply, 0);
                try { requestPath = text(glib.g_variant_get_string(path, Pointer.NULL)); }
                finally { glib.g_variant_unref(path); }
            } finally {
                glib.g_variant_unref(reply);
            }
            glib.g_main_loop_run(loop);
            if (failure != null) throw failure;
            return selection;
        } finally {
            if (connection != null) {
                if (!completed && requestPath != null) {
                    Pointer reply = gio.g_dbus_connection_call_sync(connection, SERVICE, requestPath,
                            REQUEST, "Close", Pointer.NULL, Pointer.NULL, 0, 1000, Pointer.NULL, null);
                    if (reply != null) glib.g_variant_unref(reply);
                }
                if (subscription != 0) gio.g_dbus_connection_signal_unsubscribe(connection, subscription);
                if (ownerSubscription != 0) gio.g_dbus_connection_signal_unsubscribe(connection, ownerSubscription);
                gio.g_object_unref(connection);
            }
            Reference.reachabilityFence(response);
            Reference.reachabilityFence(ownerChanged);
            if (loop != null) glib.g_main_loop_unref(loop);
            glib.g_main_context_pop_thread_default(context);
            glib.g_main_context_unref(context);
        }
    }

    private void onResponse(Pointer connection, String sender, String path, String iface,
                            String member, Pointer parameters, Pointer data) {
        if (!path.equals(requestPath) || completed) return;
        try {
            Pointer status = glib.g_variant_get_child_value(parameters, 0);
            int code;
            try { code = glib.g_variant_get_uint32(status); }
            finally { glib.g_variant_unref(status); }
            if (code == 1) return; // User cancellation is not a successful empty selection.
            if (code != 0) throw new IllegalStateException("FileChooser portal failed with response " + code);
            Pointer results = glib.g_variant_get_child_value(parameters, 1);
            try {
                Pointer uris = glib.g_variant_lookup_value(results, "uris", Pointer.NULL);
                if (uris == null) throw new IllegalStateException("FileChooser portal returned no URI list");
                try {
                    if (glib.g_variant_n_children(uris) != 1) {
                        throw new IllegalStateException("FileChooser portal did not return one selection");
                    }
                    Pointer uri = glib.g_variant_get_child_value(uris, 0);
                    try {
                        selection = Path.of(URI.create(text(glib.g_variant_get_string(uri, Pointer.NULL)))).toString();
                    } finally {
                        glib.g_variant_unref(uri);
                    }
                } finally {
                    glib.g_variant_unref(uris);
                }
            } finally {
                glib.g_variant_unref(results);
            }
        } catch (Throwable error) {
            failure = new IllegalStateException("Invalid FileChooser portal response", error);
        } finally {
            completed = true;
            glib.g_main_loop_quit(loop);
        }
    }

    private void option(List<Pointer> options, String key, Pointer value) {
        options.add(glib.g_variant_new_dict_entry(glib.g_variant_new_string(key),
                glib.g_variant_new_variant(value)));
    }

    private Pointer tuple(Pointer... values) {
        return glib.g_variant_new_tuple(values, values.length);
    }

    private Pointer array(Pointer... values) {
        return glib.g_variant_new_array(Pointer.NULL, values, values.length);
    }

    /** Portal globs are case-sensitive, unlike the old extension filter. */
    private static String extensionPattern(String extension) {
        StringBuilder pattern = new StringBuilder("*.");
        for (int i = 0; i < extension.length(); i++) {
            char c = extension.charAt(i);
            if (c >= 'a' && c <= 'z') pattern.append('[').append(c).append((char) (c - 'a' + 'A')).append(']');
            else if (c >= 'A' && c <= 'Z') pattern.append('[').append((char) (c - 'A' + 'a')).append(c).append(']');
            else pattern.append(c);
        }
        return pattern.toString();
    }

    private RuntimeException error(String operation, Pointer error) {
        if (error == null) return new IllegalStateException(operation + " failed");
        try {
            return new IllegalStateException(operation + ": " + text(error.getPointer(8)));
        } finally {
            glib.g_error_free(error);
        }
    }

    private static String text(Pointer string) {
        return string == null ? "" : string.getString(0, "UTF-8");
    }
}
