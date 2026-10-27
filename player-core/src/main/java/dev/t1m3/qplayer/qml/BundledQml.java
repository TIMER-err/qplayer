package dev.t1m3.qplayer.qml;

import io.github.timer_err.qml4j.compiler.CompiledScene;
import io.github.timer_err.qml4j.compiler.CompiledSceneCache;
import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.engine.classloader.ClassLoaderBackend;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.ResourceLoader;
import io.github.timer_err.qml4j.render.items.core.Item;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One packaged QML scene, including its independently generated singleton classes.
 * Own alongside the QmlView; close AFTER view.dispose(), on the scene's render thread. */
public final class BundledQml implements ClassLoaderBackend, AutoCloseable {
    public static final int FORMAT = 1;
    public static final int MAGIC = 0x51504D4C;
    private static final byte[] NO_BYTECODE = new byte[0];
    private final String entry;
    private final CompiledScene scene;
    private final ClassLoader loader;
    static {
        // Bindings/handlers use Rhino's interpreter on both Android and Native
        // Image. The QML component classes themselves are compiled by the build.
        System.setProperty("qml4j.rhino.opt", "-1");
    }
    private final Map<String, Class<?>> classes = new LinkedHashMap<>();
    private boolean closed;

    public BundledQml(String entry) {
        this.entry = entry;
        this.loader = BundledQml.class.getClassLoader();
        String path = "META-INF/qplayer/qml/" + entry + ".bin";
        InputStream stream = loader.getResourceAsStream(path);
        if (stream == null) throw new IllegalStateException("Missing build-time QML bundle: " + path);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(stream))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT
                    || input.readInt() != CompiledScene.FORMAT_VERSION) {
                throw new IOException("Unsupported QML bundle format");
            }
            String root = input.readUTF();
            Map<String, byte[]> names = new LinkedHashMap<>();
            for (int n = input.readInt(); n > 0; n--) names.put(input.readUTF(), NO_BYTECODE);
            Map<String, String> imports = readMap(input);
            Map<String, Map<String, String>> singletons = new LinkedHashMap<>();
            for (int n = input.readInt(); n > 0; n--) singletons.put(input.readUTF(), readMap(input));
            List<CompiledScene.JsImport> jsImports = new ArrayList<>();
            for (int n = input.readInt(); n > 0; n--) {
                jsImports.add(new CompiledScene.JsImport(input.readUTF(), input.readUTF()));
            }
            scene = new CompiledScene(root, names, imports, singletons, jsImports);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read build-time QML bundle " + entry, error);
        }
    }

    private static Map<String, String> readMap(DataInputStream input) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (int n = input.readInt(); n > 0; n--) result.put(input.readUTF(), input.readUTF());
        return result;
    }

    /** Configure before context() and load(). QML source resources are intentionally
     * inaccessible: an unindexed Loader source cannot fall through to the compiler. */
    public QmlView configure(QmlView view, ResourceLoader resources) {
        if (closed) throw new IllegalStateException("QML bundle is closed");
        return view.resources(path -> path.endsWith(".qml") ? null : resources.load(path))
                .compilationCache(new CompiledSceneCache() {
                    @Override public CompiledScene load(String key) {
                        if (!entry.equals(key)) throw new IllegalStateException("Unknown packaged scene: " + key);
                        return scene;
                    }
                    @Override public void store(String key, CompiledScene compiled) {
                        throw new IllegalStateException("Packaged QML must not compile at runtime: " + key);
                    }
                }, entry);
    }

    public Item load(QmlView view) {
        if (closed) throw new IllegalStateException("QML bundle is closed");
        // The mandatory cache supplies everything; no source is parsed on this path.
        return view.load("");
    }

    @Override public Class<?> defineClass(String name, byte[] ignored) {
        if (closed) throw new IllegalStateException("QML bundle is closed");
        if (!scene.classes().containsKey(name)) {
            throw new IllegalStateException("QML class was not compiled into this scene: " + name);
        }
        Class<?> cached = classes.get(name);
        if (cached != null) return cached;
        try {
            Class<?> type = Class.forName(name, false, loader);
            classes.put(name, type);
            return type;
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("Missing packaged QML class: " + name, error);
        }
    }

    @Override public Map<String, Class<?>> defineClasses(Map<String, byte[]> requested) {
        Map<String, Class<?>> result = new LinkedHashMap<>();
        for (String name : requested.keySet()) result.put(name, defineClass(name, NO_BYTECODE));
        return result;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, String> prefix : scene.singletons().values()) names.addAll(prefix.values());
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : names) {
            Class<?> type = classes.get(name);
            if (type == null) continue;
            try {
                // Never call __instance(): unused singletons must remain uninitialized.
                Field singleton = type.getDeclaredField("__singleton");
                singleton.setAccessible(true);
                Object value = singleton.get(null);
                singleton.set(null, null);
                unbindSingleton(value, visited);
            } catch (ReflectiveOperationException error) {
                dev.t1m3.qplayer.util.Logger.warn("QML singleton cleanup failed: {}", error);
            }
        }
        classes.clear();
    }

    private static void unbindSingleton(Object object, Set<Object> visited) throws IllegalAccessException {
        if (!(object instanceof QObject) || !visited.add(object)) return;
        if (object instanceof Item) ((Item) object).dispose();
        for (Field field : object.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Object value = field.get(object);
            if (value instanceof Property) {
                Property<?> property = (Property<?>) value;
                Object child = property.peek();
                property.unbind();
                unbindSingleton(child, visited);
            } else if (value instanceof QObject) {
                unbindSingleton(value, visited);
            }
        }
    }
}
