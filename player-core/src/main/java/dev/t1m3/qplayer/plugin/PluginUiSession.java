package dev.t1m3.qplayer.plugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.QmlSafeBridge;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.ResourceLoader;
import io.github.timer_err.qml4j.render.StockTypes;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A third-party QML document in its own safe Rhino realm and resource namespace.
 * It receives no QPlayer controller, filesystem object, Java package access or
 * shared QML globals. The only bridge calls the owning plugin's matching ui.*
 * handler and publishes JSON results through render-thread-pumped properties.
 */
public final class PluginUiSession implements AutoCloseable {
    private static final long MAX_RESOURCE_BYTES = 1024L * 1024L;
    private static final int MAX_JSON_CHARS = 1024 * 1024;
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();

    private final PluginManager manager;
    private final CorePluginHostApi hostApi;
    private final Path root;
    private final PluginUiContributionRow contribution;
    private final boolean clipboardAllowed;
    private final Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
    private final Gson gson = new Gson();
    private final UiBridge bridge = new UiBridge();
    private final QmlView view;
    private volatile boolean closed;

    PluginUiSession(PluginManager manager, CorePluginHostApi hostApi, Path root,
                    PluginUiContributionRow contribution, boolean clipboardAllowed) throws IOException {
        this.manager = manager;
        this.hostApi = hostApi;
        this.root = root.toAbsolutePath().normalize();
        this.contribution = contribution;
        this.clipboardAllowed = clipboardAllowed;
        QmlEngine engine = new QmlEngine(new io.github.timer_err.qml4j.engine.classloader.JvmClassLoaderBackend(), true);
        this.view = new QmlView(engine, StockTypes.safeRegistry())
                .resources(new ConfinedResources(this.root))
                .networkPolicy(url -> hostApi.allowsReturnedUrl(contribution.pluginId, url))
                .context("plugin", bridge);
        Path source = resolve(contribution.source);
        String qml = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        if (qml.length() > MAX_RESOURCE_BYTES) throw new IOException("plugin QML is too large");
        String base = parent(contribution.source);
        view.load(qml, base);
    }

    public QmlView view() { return view; }
    public PluginUiContributionRow contribution() { return contribution; }
    public boolean clipboardAllowed() { return clipboardAllowed; }

    /** Must be called by the host on this session's render thread before a frame. */
    public void pump() {
        Runnable callback;
        while ((callback = callbacks.poll()) != null) callback.run();
    }

    public final class UiBridge implements QmlSafeBridge {
        public final Property<Boolean> busy = new Property<>(false);
        public final Property<String> resultJson = new Property<>("");
        public final Property<String> error = new Property<>("");
        public final Property<Long> revision = new Property<>(0L);

        public void call(String action, String payloadJson) {
            if (closed || Boolean.TRUE.equals(busy.peek())) return;
            if (action == null || !action.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                error.set("invalid UI action");
                return;
            }
            String raw = payloadJson != null ? payloadJson : "{}";
            if (raw.length() > MAX_JSON_CHARS) { error.set("UI payload is too large"); return; }
            final Map<String, Object> payload;
            try {
                Map<String, Object> parsed = gson.fromJson(raw, MAP_TYPE);
                payload = parsed != null ? parsed : Collections.<String, Object>emptyMap();
            } catch (RuntimeException parseError) {
                error.set("UI payload is not a JSON object");
                return;
            }
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("action", action);
            arguments.put("payload", payload);
            busy.set(true);
            manager.invoke(contribution.pluginId, "ui." + contribution.id, arguments)
                    .whenComplete((value, failure) -> callbacks.add(() -> {
                        if (closed) return;
                        busy.set(false);
                        if (failure != null) {
                            resultJson.set("");
                            error.set(failure.getMessage() != null ? failure.getMessage() : "plugin UI call failed");
                        } else {
                            error.set("");
                            resultJson.set(gson.toJson(value));
                        }
                        revision.set(revision.peek() + 1L);
                    }));
        }

        @Override public boolean allowsQmlMethod(String name) {
            return "call".equals(name);
        }
    }

    private Path resolve(String source) throws IOException {
        Path path = root.resolve(source).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            throw new IOException("plugin UI resource is outside its package");
        }
        if (Files.size(path) > MAX_RESOURCE_BYTES) throw new IOException("plugin UI resource is too large");
        return path;
    }

    private static String parent(String source) {
        int slash = source.lastIndexOf('/');
        return slash >= 0 ? source.substring(0, slash) : "";
    }

    @Override public void close() { closed = true; callbacks.clear(); }

    /** Package-visible for a security regression test; still not exposed to plugins. */
    static final class ConfinedResources implements ResourceLoader {
        private final Path root;
        ConfinedResources(Path root) { this.root = root; }
        @Override public byte[] load(String source) {
            if (source == null || source.contains(":")) return null;
            try {
                Path file = root.resolve(source).normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file)
                        || Files.size(file) > MAX_RESOURCE_BYTES) return null;
                return Files.readAllBytes(file);
            } catch (IOException ignored) { return null; }
        }
    }
}
