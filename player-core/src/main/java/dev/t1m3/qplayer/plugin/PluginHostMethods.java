package dev.t1m3.qplayer.plugin;

/** Single permission declaration shared by the JavaScript gate and host dispatcher. */
final class PluginHostMethods {
    private PluginHostMethods() {}

    static PluginPermission requiredPermission(String method) {
        if (method.startsWith("http.")) return PluginPermission.NETWORK;
        if (method.startsWith("credentials.")) return PluginPermission.CREDENTIALS;
        if (method.startsWith("webAuth.")) return PluginPermission.WEB_AUTH;
        if (method.startsWith("clipboard.")) return PluginPermission.CLIPBOARD;
        if (method.startsWith("openUrl.")) return PluginPermission.OPEN_URL;
        if ("playback.read".equals(method)) return PluginPermission.PLAYBACK_READ;
        if (method.startsWith("playback.")) return PluginPermission.PLAYBACK_CONTROL;
        if (method.startsWith("queue.")) return PluginPermission.QUEUE_WRITE;
        if (method.startsWith("notifications.")) return PluginPermission.NOTIFICATIONS;
        if (method.startsWith("timers.")) return PluginPermission.BACKGROUND_TIMERS;
        if (method.startsWith("files.")) return PluginPermission.FILES_SAVE;
        return null;
    }

    static boolean isAppMethod(String method) {
        return method.startsWith("playback.") || method.startsWith("queue.")
                || method.startsWith("notifications.") || method.startsWith("clipboard.")
                || method.startsWith("webAuth.") || method.startsWith("files.");
    }
}
