package dev.t1m3.qplayer.plugin;

/** A plugin failed validation, threw, timed out or violated its declared permissions. */
public final class PluginExecutionException extends RuntimeException {
    public PluginExecutionException(String message) { super(message); }
    public PluginExecutionException(String message, Throwable cause) { super(message, cause); }
}
