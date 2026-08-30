package dev.t1m3.qplayer.plugin;

/** Host capabilities that must be declared before a plugin is activated. */
public enum PluginPermission {
    NETWORK("network"), CLEAR_TEXT_NETWORK("clearTextNetwork"), LOCAL_NETWORK("localNetwork"),
    CREDENTIALS("credentials"), WEB_AUTH("webAuth"), CLIPBOARD("clipboard"),
    OPEN_URL("openUrl"), PLAYBACK_READ("playbackRead"), PLAYBACK_CONTROL("playbackControl"),
    QUEUE_WRITE("queueWrite"), NOTIFICATIONS("notifications"),
    CUSTOM_UI("customUi"), BACKGROUND_TIMERS("backgroundTimers");

    private final String wireName;

    PluginPermission(String wireName) { this.wireName = wireName; }
    public String wireName() { return wireName; }

    public static PluginPermission fromWireName(String value) {
        for (PluginPermission permission : values()) {
            if (permission.wireName.equals(value)) return permission;
        }
        throw new IllegalArgumentException("unknown plugin permission: " + value);
    }
}
