package dev.t1m3.qplayer.plugin;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Asynchronous, permission-checked host services exposed to one plugin runtime. */
public interface PluginHostApi {
    CompletableFuture<Object> call(String pluginId, String method, Map<String, Object> arguments);
}
