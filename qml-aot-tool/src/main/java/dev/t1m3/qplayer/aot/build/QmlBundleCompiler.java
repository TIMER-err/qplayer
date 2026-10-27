package dev.t1m3.qplayer.aot.build;

import com.google.gson.GsonBuilder;
import dev.t1m3.qplayer.qml.BundledQml;
import io.github.timer_err.qml4j.compiler.CompiledScene;
import io.github.timer_err.qml4j.compiler.TypeRegistry;
import io.github.timer_err.qml4j.render.QPlayerSceneCapture;
import io.github.timer_err.qml4j.render.ResourceLoader;
import io.github.timer_err.qml4j.render.StockTypes;
import org.objectweb.asm.ClassReader;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Host-JVM build tool. Output classes are normal javac/D8/native-image inputs. */
public final class QmlBundleCompiler {
    private QmlBundleCompiler() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4 || !"--input".equals(arguments[0]) || !"--output".equals(arguments[2])) {
            throw new IllegalArgumentException("Usage: QmlBundleCompiler --input <shared-qml> --output <directory>");
        }
        // JS modules are evaluated by the build-time resolver, not JIT-compiled.
        System.setProperty("qml4j.rhino.opt", "-1");
        Path input = Paths.get(arguments[1]).toAbsolutePath().normalize();
        Path output = Paths.get(arguments[3]).toAbsolutePath().normalize();
        List<String> sources;
        try (Stream<Path> paths = Files.walk(input)) {
            sources = paths.filter(Files::isRegularFile)
                    .map(path -> input.relativize(path).toString().replace('\\', '/'))
                    .filter(path -> path.endsWith(".qml")).sorted().collect(Collectors.toList());
        }
        if (!sources.contains("Main.qml") || !sources.contains("DesktopLyric.qml")) {
            throw new IllegalArgumentException("Bundled QML entrypoints are missing from " + input);
        }
        ResourceLoader resources = path -> {
            Path resolved = input.resolve(path).normalize();
            if (!resolved.startsWith(input) || !Files.isRegularFile(resolved)) return null;
            try { return Files.readAllBytes(resolved); }
            catch (IOException error) { throw new UncheckedIOException(error); }
        };
        Map<String, byte[]> files = new TreeMap<>();
        Set<String> reflectTypes = new TreeSet<>();
        // Independent generated namespaces preserve qml4j's per-document singleton
        // semantics while both desktop windows are alive. The main scene indexes the
        // complete source tree, including components reached only by lazy Loaders.
        compile(resources, "Main.qml", sources, 0, files, reflectTypes);
        compile(resources, "DesktopLyric.qml", Collections.emptyList(), 1_000_000, files, reflectTypes);
        // qml4j 0.2.33 does not expose registry enumeration. Include stock types
        // with no generated subclass as well as the generated classes themselves.
        TypeRegistry registry = StockTypes.registry();
        for (String fieldName : new String[]{"types", "singletons"}) {
            Field field = TypeRegistry.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            for (Object type : ((Map<?, ?>) field.get(registry)).values()) {
                addReflectType((Class<?>) type, reflectTypes);
            }
        }
        for (String name : Arrays.asList("dev.t1m3.qplayer.bridge.PlayerController",
                "dev.t1m3.qplayer.settings.SettingsCore", "dev.t1m3.qplayer.i18n.I18n",
                "dev.t1m3.qplayer.bridge.WindowChromeStub",
                "dev.t1m3.qplayer.lyric.skia.LiquidGlassNode",
                "dev.t1m3.qplayer.lyric.skia.MiniPlayerLoadingAnimation",
                "io.github.timer_err.qml4j.runtime.color.StyleManager")) {
            addReflectType(Class.forName(name, false, QmlBundleCompiler.class.getClassLoader()), reflectTypes);
        }
        // Signal carries Object[]: these JS-visible payloads have no generic API edge.
        addReflectType(io.github.timer_err.qml4j.render.items.core.MouseEvent.class, reflectTypes);
        addReflectType(io.github.timer_err.qml4j.render.items.input.KeyEvent.class, reflectTypes);
        // Model properties expose List, but teardown queries the concrete wrapper.
        addReflectType(io.github.timer_err.qml4j.engine.binding.ObservableList.class, reflectTypes);
        List<Map<String, Object>> reflection = new ArrayList<>();
        for (String name : reflectTypes) {
            Map<String, Object> type = new LinkedHashMap<>();
            type.put("name", name);
            type.put("allDeclaredConstructors", true);
            type.put("allDeclaredFields", true);
            type.put("allDeclaredMethods", true);
            type.put("allPublicFields", true);
            type.put("allPublicMethods", true);
            reflection.add(type);
        }
        files.put("META-INF/native-image/dev.t1m3.qplayer/qml-bundle/reflect-config.json",
                new GsonBuilder().setPrettyPrinting().create().toJson(reflection).getBytes(StandardCharsets.UTF_8));
        files.put("META-INF/native-image/dev.t1m3.qplayer/qml-bundle/resource-config.json",
                "{\"resources\":{\"includes\":[{\"pattern\":\"META-INF/qplayer/qml/.*\"}]}}\n".getBytes(StandardCharsets.UTF_8));
        MessageDigest fingerprint = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> paths = Files.walk(input)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
                String name = input.relativize(path).toString().replace('\\', '/');
                if (!name.endsWith(".qml") && !name.endsWith(".js") && !name.endsWith("qmldir")) continue;
                fingerprint.update(name.getBytes(StandardCharsets.UTF_8));
                fingerprint.update((byte) 0);
                fingerprint.update(Files.readAllBytes(path));
            }
        }
        files.put("META-INF/qplayer/qml/source.sha256", hex(fingerprint.digest()).getBytes(StandardCharsets.UTF_8));
        Path classes = output.resolve("classes");
        Files.createDirectories(classes);
        try (Stream<Path> existing = Files.walk(classes)) {
            for (Path path : existing.filter(Files::isRegularFile).collect(Collectors.toList())) {
                if (!files.containsKey(classes.relativize(path).toString().replace('\\', '/'))) Files.delete(path);
            }
        }
        for (Map.Entry<String, byte[]> file : files.entrySet()) writeChanged(classes.resolve(file.getKey()), file.getValue());
        ByteArrayOutputStream jarBytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(jarBytes)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                JarEntry entry = new JarEntry(file.getKey());
                entry.setTime(0L);
                jar.putNextEntry(entry);
                jar.write(file.getValue());
                jar.closeEntry();
            }
        }
        writeChanged(output.resolve("qml-bundle.jar"), jarBytes.toByteArray());
    }

    private static void compile(ResourceLoader resources, String entry, List<String> sources,
                                int firstComponent, Map<String, byte[]> files, Set<String> reflection) throws Exception {
        CompiledScene scene = QPlayerSceneCapture.capture(resources, entry, sources, firstComponent);
        for (Map.Entry<String, byte[]> type : scene.classes().entrySet()) {
            String name = type.getKey();
            if (files.put(name.replace('.', '/') + ".class", type.getValue()) != null) {
                throw new IllegalStateException("Generated QML namespace collision: " + name);
            }
            reflection.add(name);
            ClassReader reader = new ClassReader(type.getValue());
            String parent = reader.getSuperName().replace('/', '.');
            if (!parent.startsWith("io.github.timer_err.qml4j.generated.")) {
                addReflectType(Class.forName(parent, false, QmlBundleCompiler.class.getClassLoader()), reflection);
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream manifest = new DataOutputStream(bytes)) {
            manifest.writeInt(BundledQml.MAGIC);
            manifest.writeInt(BundledQml.FORMAT);
            manifest.writeInt(CompiledScene.FORMAT_VERSION);
            manifest.writeUTF(scene.rootClassName());
            manifest.writeInt(scene.classes().size());
            for (String name : scene.classes().keySet()) manifest.writeUTF(name);
            writeMap(manifest, scene.importedTypes());
            manifest.writeInt(scene.singletons().size());
            for (Map.Entry<String, Map<String, String>> prefix : new TreeMap<>(scene.singletons()).entrySet()) {
                manifest.writeUTF(prefix.getKey());
                writeMap(manifest, prefix.getValue());
            }
            manifest.writeInt(scene.jsImports().size());
            for (CompiledScene.JsImport js : scene.jsImports()) {
                manifest.writeUTF(js.alias());
                manifest.writeUTF(js.path());
            }
        }
        files.put("META-INF/qplayer/qml/" + entry + ".bin", bytes.toByteArray());
    }

    private static void addReflectType(Type type, Set<String> types) {
        if (type instanceof ParameterizedType) {
            ParameterizedType generic = (ParameterizedType) type;
            addReflectType(generic.getRawType(), types);
            for (Type argument : generic.getActualTypeArguments()) addReflectType(argument, types);
            return;
        }
        if (!(type instanceof Class<?>)) return;
        Class<?> cls = (Class<?>) type;
        if (cls.isArray()) { addReflectType(cls.getComponentType(), types); return; }
        String name = cls.getName();
        if ((!name.startsWith("dev.t1m3.qplayer.") && !name.startsWith("io.github.timer_err.qml4j."))
                || !types.add(name)) return;
        addReflectType(cls.getGenericSuperclass(), types);
        for (Type parent : cls.getGenericInterfaces()) addReflectType(parent, types);
        for (Field field : cls.getFields()) addReflectType(field.getGenericType(), types);
        for (Method method : cls.getMethods()) {
            addReflectType(method.getGenericReturnType(), types);
            for (Type parameter : method.getGenericParameterTypes()) addReflectType(parameter, types);
        }
    }

    private static void writeMap(DataOutputStream output, Map<String, String> map) throws IOException {
        output.writeInt(map.size());
        for (Map.Entry<String, String> entry : new TreeMap<>(map).entrySet()) {
            output.writeUTF(entry.getKey());
            output.writeUTF(entry.getValue());
        }
    }

    private static void writeChanged(Path path, byte[] content) throws IOException {
        if (Files.isRegularFile(path) && Arrays.equals(Files.readAllBytes(path), content)) return;
        Files.createDirectories(path.getParent());
        Files.write(path, content);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }
}
