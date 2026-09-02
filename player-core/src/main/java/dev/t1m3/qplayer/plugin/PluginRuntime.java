package dev.t1m3.qplayer.plugin;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One isolated Rhino actor for one enabled source plugin.
 *
 * <p>Only safe standard objects and the narrow {@code qplayer.call()} bridge are
 * installed. Java/Packages/require-from-outside-the-package are unavailable.
 * Every JS entry point runs on the actor thread in interpreted mode and is
 * guarded by Rhino's instruction observer.
 */
public final class PluginRuntime implements AutoCloseable {
    private static final long MAX_MODULE_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_RESULT_DEPTH = 32;
    private static final int MAX_RESULT_NODES = 500_000;
    private static final long MAX_RESULT_STRING_CHARS = 24L * 1024L * 1024L;
    /** Large playlists need several song-detail batches after playlist/detail.
      * Rhino computation is still interrupted by the same deadline. */
    private static final long DEFAULT_CALL_TIMEOUT_MS = 90_000L;
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(
            daemonFactory("qplayer-plugin-timeouts"));

    private final Path root;
    private final PluginManifest manifest;
    private final PluginHostApi hostApi;
    private final Set<PluginPermission> permissions;
    private final ExecutorService actor;
    private final SandboxContextFactory contexts = new SandboxContextFactory();
    private final Map<String, Scriptable> modules = new LinkedHashMap<>();
    private volatile boolean closed;
    private Scriptable scope;
    private Scriptable handlers;
    private Scriptable registered;

    private PluginRuntime(Path root, PluginManifest manifest, PluginHostApi hostApi) {
        this.root = root.toAbsolutePath().normalize();
        this.manifest = manifest;
        this.hostApi = hostApi;
        this.permissions = manifest.permissionSet();
        this.actor = Executors.newSingleThreadExecutor(daemonFactory("qplayer-plugin-" + manifest.id));
    }

    public static PluginRuntime start(Path root, PluginManifest manifest, PluginHostApi hostApi)
            throws IOException {
        if (root == null || manifest == null || hostApi == null) {
            throw new IllegalArgumentException("root, manifest and hostApi are required");
        }
        manifest.validate();
        PluginRuntime runtime = new PluginRuntime(root, manifest, hostApi);
        try {
            runtime.submit(runtime::initialize).get(DEFAULT_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return runtime;
        } catch (Exception e) {
            runtime.close();
            Throwable cause = unwrap(e);
            if (cause instanceof IOException) throw (IOException) cause;
            throw new PluginExecutionException("failed to start plugin " + manifest.id, cause);
        }
    }

    public PluginManifest manifest() { return manifest; }

    public CompletableFuture<Boolean> hasHandler(String name) {
        return submit(() -> handler(name) != null);
    }

    public CompletableFuture<Object> invoke(String name, Map<String, Object> arguments) {
        if (name == null || name.isEmpty()) {
            return failed(new IllegalArgumentException("handler name is empty"));
        }
        CompletableFuture<Object> result = new CompletableFuture<>();
        submit(() -> {
            Function function = handler(name);
            if (function == null) throw new PluginExecutionException(
                    "plugin " + manifest.id + " does not implement " + name);
            Context cx = contexts.enterContext();
            contexts.beginDeadline(DEFAULT_CALL_TIMEOUT_MS);
            try {
                Object argument = toJs(arguments != null ? arguments : Collections.emptyMap(), scope);
                Object returned = function.call(cx, scope, handlers, new Object[]{argument});
                settle(cx, returned, result);
                cx.processMicrotasks();
            } finally {
                contexts.clearDeadline();
                Context.exit();
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) result.completeExceptionally(unwrap(error));
        });
        java.util.concurrent.ScheduledFuture<?> timeout = TIMEOUTS.schedule(
                () -> result.completeExceptionally(new TimeoutException(
                        "plugin call timed out: " + manifest.id + "." + name)),
                DEFAULT_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        result.whenComplete((value, error) -> timeout.cancel(false));
        return result;
    }

    private Void initialize() throws IOException {
        Context cx = contexts.enterContext();
        contexts.beginDeadline(DEFAULT_CALL_TIMEOUT_MS);
        try {
            scope = cx.initSafeStandardObjects(null, false);
            NativeObject qplayer = new NativeObject();
            qplayer.setParentScope(scope);
            qplayer.setPrototype(ScriptableObject.getObjectPrototype(scope));
            qplayer.put("call", qplayer, hostCall());
            qplayer.put("register", qplayer, register());
            qplayer.put("pluginId", qplayer, manifest.id);
            ScriptableObject.putProperty(scope, "qplayer", qplayer);
            Scriptable exports = loadModule(cx, normalizeModule(manifest.entry, null));
            Scriptable plugin = registered != null ? registered : exports;
            Object rawHandlers = ScriptableObject.getProperty(plugin, "handlers");
            if (!(rawHandlers instanceof Scriptable)) {
                throw new PluginExecutionException("plugin entry must export { handlers: {...} }");
            }
            handlers = (Scriptable) rawHandlers;
            for (ProviderCapability capability : manifest.capabilitySet()) {
                if (handler(capability.wireName()) == null) {
                    throw new PluginExecutionException("plugin advertises but does not implement "
                            + capability.wireName());
                }
            }
            for (PluginManifest.UiContribution contribution : manifest.ui) {
                String name = "ui." + contribution.id;
                if (handler(name) == null) {
                    throw new PluginExecutionException("plugin UI does not implement " + name);
                }
            }
            cx.processMicrotasks();
            return null;
        } finally {
            contexts.clearDeadline();
            Context.exit();
        }
    }

    private Function handler(String name) {
        if (handlers == null) return null;
        Object value = ScriptableObject.getProperty(handlers, name);
        return value instanceof Function ? (Function) value : null;
    }

    private Scriptable loadModule(Context cx, String relativePath) throws IOException {
        Scriptable cached = modules.get(relativePath);
        if (cached != null) return cached;
        Path path = resolveModule(relativePath);
        long size = Files.size(path);
        if (size < 0 || size > MAX_MODULE_BYTES) throw new IOException("plugin module is too large");
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        String wrapped = "(function(exports,module,require,qplayer){\n" + source
                + "\n;return module.exports;\n})";
        Script script = cx.compileString(wrapped, manifest.id + "/" + relativePath, 1, null);
        Object evaluated = script.exec(cx, scope);
        if (!(evaluated instanceof Function)) throw new IOException("invalid plugin module " + relativePath);
        NativeObject exports = new NativeObject();
        NativeObject module = new NativeObject();
        exports.setParentScope(scope);
        module.setParentScope(scope);
        module.put("exports", module, exports);
        String parent = parentPath(relativePath);
        BaseFunction require = require(parent);
        Object returned = ((Function) evaluated).call(cx, scope, scope,
                new Object[]{exports, module, require, ScriptableObject.getProperty(scope, "qplayer")});
        Object finalExports = ScriptableObject.getProperty(module, "exports");
        Scriptable result = finalExports instanceof Scriptable
                ? (Scriptable) finalExports
                : toJsObject(Collections.singletonMap("default", fromJs(returned)), scope);
        modules.put(relativePath, result);
        return result;
    }

    private BaseFunction require(String parent) {
        return new BaseFunction() {
            @Override public Object call(Context cx, Scriptable callScope, Scriptable thisObj, Object[] args) {
                if (args.length != 1 || !(args[0] instanceof String)) {
                    throw new PluginExecutionException("require expects one relative path");
                }
                try {
                    return loadModule(cx, normalizeModule((String) args[0], parent));
                } catch (IOException e) {
                    throw new PluginExecutionException("cannot load plugin module", e);
                }
            }
        };
    }

    private BaseFunction register() {
        return new BaseFunction() {
            @Override public Object call(Context cx, Scriptable callScope, Scriptable thisObj, Object[] args) {
                if (args.length != 1 || !(args[0] instanceof Scriptable)) {
                    throw new PluginExecutionException("qplayer.register expects one plugin object");
                }
                if (registered != null) throw new PluginExecutionException("plugin registered twice");
                registered = (Scriptable) args[0];
                return Undefined.instance;
            }
        };
    }

    private BaseFunction hostCall() {
        return new BaseFunction() {
            @Override public Object call(Context cx, Scriptable callScope, Scriptable thisObj, Object[] args) {
                String method = args.length > 0 ? Context.toString(args[0]) : "";
                PluginPermission required = requiredPermission(method);
                if ((required == null && !isUnprivilegedMethod(method))
                        || (required != null && !permissions.contains(required))) {
                    throw new PluginExecutionException("permission denied for host method " + method);
                }
                Map<String, Object> arguments = args.length > 1
                        ? asMap(fromJs(args[1])) : Collections.emptyMap();
                CompletableFuture<Object> future = hostApi.call(manifest.id, method, arguments);
                final Function[] resolution = new Function[2];
                BaseFunction executor = new BaseFunction() {
                    @Override public Object call(Context ignored, Scriptable s, Scriptable t, Object[] functions) {
                        resolution[0] = (Function) functions[0];
                        resolution[1] = (Function) functions[1];
                        return Undefined.instance;
                    }
                };
                Object promise = cx.newObject(scope, "Promise", new Object[]{executor});
                future.whenComplete((value, error) -> submit(() -> {
                    Context callback = contexts.enterContext();
                    contexts.beginDeadline(DEFAULT_CALL_TIMEOUT_MS);
                    try {
                        if (error == null) {
                            resolution[0].call(callback, scope, scope, new Object[]{toJs(value, scope)});
                        } else {
                            resolution[1].call(callback, scope, scope,
                                    new Object[]{String.valueOf(unwrap(error).getMessage())});
                        }
                        callback.processMicrotasks();
                    } finally {
                        contexts.clearDeadline();
                        Context.exit();
                    }
                    return null;
                }));
                return promise;
            }
        };
    }

    private void settle(Context cx, Object value, CompletableFuture<Object> target) {
        if (!(value instanceof Scriptable)) {
            target.complete(fromJs(value));
            return;
        }
        Object then = ScriptableObject.getProperty((Scriptable) value, "then");
        if (!(then instanceof Function)) {
            target.complete(fromJs(value));
            return;
        }
        BaseFunction resolve = new BaseFunction() {
            @Override public Object call(Context ignored, Scriptable s, Scriptable t, Object[] args) {
                target.complete(fromJs(args.length > 0 ? args[0] : null));
                return Undefined.instance;
            }
        };
        BaseFunction reject = new BaseFunction() {
            @Override public Object call(Context ignored, Scriptable s, Scriptable t, Object[] args) {
                target.completeExceptionally(new PluginExecutionException(
                        args.length > 0 ? Context.toString(args[0]) : "plugin promise rejected"));
                return Undefined.instance;
            }
        };
        ((Function) then).call(cx, scope, (Scriptable) value, new Object[]{resolve, reject});
    }

    private PluginPermission requiredPermission(String method) {
        if (method.startsWith("http.")) return PluginPermission.NETWORK;
        if (method.startsWith("credentials.")) return PluginPermission.CREDENTIALS;
        if (method.startsWith("webAuth.")) return PluginPermission.WEB_AUTH;
        if (method.startsWith("clipboard.")) return PluginPermission.CLIPBOARD;
        if (method.startsWith("openUrl.")) return PluginPermission.OPEN_URL;
        if (method.startsWith("playback.read")) return PluginPermission.PLAYBACK_READ;
        if (method.startsWith("playback.")) return PluginPermission.PLAYBACK_CONTROL;
        if (method.startsWith("queue.")) return PluginPermission.QUEUE_WRITE;
        if (method.startsWith("notifications.")) return PluginPermission.NOTIFICATIONS;
        if (method.startsWith("timers.")) return PluginPermission.BACKGROUND_TIMERS;
        return null;
    }

    private static boolean isUnprivilegedMethod(String method) {
        return method.startsWith("storage.") || method.startsWith("crypto.")
                || method.startsWith("compression.") || method.startsWith("events.")
                || method.startsWith("qr.");
    }

    private String normalizeModule(String requested, String parent) {
        if (requested == null || requested.isEmpty() || requested.contains(":")) {
            throw new PluginExecutionException("invalid module path");
        }
        if (parent != null && !(requested.startsWith("./") || requested.startsWith("../"))) {
            throw new PluginExecutionException("only relative plugin modules may be required");
        }
        String combined = parent == null || parent.isEmpty() ? requested : parent + "/" + requested;
        Path normalized = Paths.get(combined).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new PluginExecutionException("module escapes plugin package");
        }
        String value = normalized.toString().replace('\\', '/');
        if (!value.endsWith(".js")) value += ".js";
        PluginManifest.requirePackagePath("module", value, ".js");
        return value;
    }

    private Path resolveModule(String relative) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            throw new IOException("plugin module not found: " + relative);
        }
        return path;
    }

    private static String parentPath(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? "" : relative.substring(0, slash);
    }

    private <T> CompletableFuture<T> submit(CheckedSupplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IllegalStateException("plugin runtime is closed"));
            return future;
        }
        try {
            actor.execute(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            future.completeExceptionally(new IllegalStateException(
                    "plugin runtime is closed", error));
        }
        return future;
    }

    @Override public void close() {
        closed = true;
        actor.shutdownNow();
        modules.clear();
        scope = null;
        handlers = null;
        registered = null;
    }

    private static Scriptable toJsObject(Map<String, Object> value, Scriptable scope) {
        Scriptable object = Context.getCurrentContext().newObject(scope);
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            object.put(entry.getKey(), object, toJs(entry.getValue(), scope));
        }
        return object;
    }

    private static Object toJs(Object value, Scriptable parent) {
        if (value == null) return null;
        if (value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Scriptable) return value;
        if (value instanceof Map) {
            Scriptable object = Context.getCurrentContext().newObject(parent);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());
                object.put(key, object, toJs(entry.getValue(), parent));
            }
            return object;
        }
        if (value instanceof Iterable) {
            List<Object> values = new ArrayList<>();
            for (Object item : (Iterable<?>) value) values.add(toJs(item, parent));
            return Context.getCurrentContext().newArray(parent, values.toArray());
        }
        return String.valueOf(value);
    }

    private static Object fromJs(Object value) {
        return fromJs(value, new ConversionBudget(), 0);
    }

    private static Object fromJs(Object value, ConversionBudget budget, int depth) {
        if (depth > MAX_RESULT_DEPTH) {
            throw new PluginExecutionException("plugin value nesting is too deep");
        }
        if (++budget.nodes > MAX_RESULT_NODES) {
            throw new PluginExecutionException("plugin value contains too many items");
        }
        if (value == null || value == Undefined.instance) return null;
        // Rhino represents concatenated/template strings as ConsString. Never leak
        // engine-specific CharSequence implementations into the host ABI.
        if (value instanceof CharSequence) {
            String text = value.toString();
            budget.stringChars += text.length();
            if (budget.stringChars > MAX_RESULT_STRING_CHARS) {
                throw new PluginExecutionException("plugin value contains too much text");
            }
            return text;
        }
        if (value instanceof Double && !Double.isFinite((Double) value)) {
            throw new PluginExecutionException("plugin value contains a non-finite number");
        }
        if (value instanceof Float && !Float.isFinite((Float) value)) {
            throw new PluginExecutionException("plugin value contains a non-finite number");
        }
        if (value instanceof NativeArray) {
            NativeArray array = (NativeArray) value;
            long length = array.getLength();
            if (length > MAX_RESULT_NODES) {
                throw new PluginExecutionException("plugin array is too large");
            }
            enterObject(array, budget);
            try {
                List<Object> out = new ArrayList<>((int) length);
                for (int i = 0; i < length; i++) {
                    out.add(fromJs(array.get(i, array), budget, depth + 1));
                }
                return out;
            } finally {
                budget.active.remove(array);
            }
        }
        if (value instanceof Scriptable) {
            Scriptable object = (Scriptable) value;
            enterObject(object, budget);
            try {
                Object[] ids = object.getIds();
                if (ids.length > MAX_RESULT_NODES) {
                    throw new PluginExecutionException("plugin object has too many properties");
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (Object id : ids) {
                    String key = String.valueOf(id);
                    Object member = id instanceof Number
                            ? object.get(((Number) id).intValue(), object)
                            : object.get(key, object);
                    if (!(member instanceof Function)) {
                        out.put(key, fromJs(member, budget, depth + 1));
                    }
                }
                return out;
            } finally {
                budget.active.remove(object);
            }
        }
        return value;
    }

    private static void enterObject(Scriptable object, ConversionBudget budget) {
        if (budget.active.put(object, Boolean.TRUE) != null) {
            throw new PluginExecutionException("plugin value contains a cycle");
        }
    }

    private static final class ConversionBudget {
        int nodes;
        long stringChars;
        final IdentityHashMap<Scriptable, Boolean> active = new IdentityHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable value = error;
        while ((value instanceof CompletionException || value instanceof java.util.concurrent.ExecutionException)
                && value.getCause() != null) value = value.getCause();
        return value;
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    private static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private interface CheckedSupplier<T> { T get() throws Exception; }

    private static final class SandboxContextFactory extends ContextFactory {
        private final ThreadLocal<Long> deadline = new ThreadLocal<>();

        @Override protected Context makeContext() {
            Context context = super.makeContext();
            context.setOptimizationLevel(-1);
            context.setLanguageVersion(Context.VERSION_ES6);
            context.setInstructionObserverThreshold(10_000);
            context.setClassShutter(className -> false);
            context.setTrackUnhandledPromiseRejections(true);
            return context;
        }

        void beginDeadline(long timeoutMs) {
            deadline.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        }

        void clearDeadline() { deadline.remove(); }

        @Override protected void observeInstructionCount(Context context, int instructionCount) {
            Long limit = deadline.get();
            if (limit != null && System.nanoTime() > limit) {
                throw new PluginExecutionException("plugin JavaScript timed out");
            }
        }
    }
}
