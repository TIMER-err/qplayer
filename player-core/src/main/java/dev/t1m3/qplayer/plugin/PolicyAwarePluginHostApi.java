package dev.t1m3.qplayer.plugin;

/** Receives an immutable manifest before the runtime can call host services. */
public interface PolicyAwarePluginHostApi extends PluginHostApi {
    void register(PluginManifest manifest);
    void unregister(String pluginId);
}
