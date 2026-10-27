package dev.t1m3.qplayer.aot.build;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Build-only metadata for the host's dynamic interop, not a runtime dependency. */
public final class DesktopNativeMetadata {
    private static final String HOST = "dev.t1m3.qplayer.desktop.";
    private static final String SKIJA = "io.github.humbleui.skija.";
    private static final String TYPES = "io.github.humbleui.types.";
    private final ClassLoader loader = Thread.currentThread().getContextClassLoader();
    private final Map<String, JsonObject> reflect = new TreeMap<>();
    private final Map<String, JsonObject> jni = new TreeMap<>();
    private final Set<String> proxies = new TreeSet<>();
    private final Set<String> models = new HashSet<>();
    private final Set<String> interopTypes = new HashSet<>();
    private final Set<String> queriedTypes = new TreeSet<>();
    private final Map<String, Set<String>> callbackLambdas = new TreeMap<>();
    private final Class<?> structure;
    private final Class<?> nativeMapped;

    private DesktopNativeMetadata() throws ClassNotFoundException {
        structure = type("com.sun.jna.Structure");
        nativeMapped = type("com.sun.jna.NativeMapped");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"--output".equals(args[0])) {
            throw new IllegalArgumentException("--output <desktop classes directory>");
        }
        new DesktopNativeMetadata().generate(Paths.get(args[1]));
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private void generate(Path output) throws Exception {
        for (String name : new String[]{"window.WindowChrome", "window.DesktopLyricState"}) {
            JsonObject entry = entry(reflect, HOST + name);
            entry.addProperty("allDeclaredFields", true);
            entry.addProperty("allDeclaredMethods", true);
        }
        hostInterop();
        // GLFW's preserved error callback reflects over its symbolic error tokens.
        entry(reflect, "org.lwjgl.glfw.GLFW").addProperty("allDeclaredFields", true);
        for (String name : new String[]{"Kernel32", "User32", "GDI32", "Advapi32", "Shell32", "Crypt32"}) {
            library("com.sun.jna.platform.win32." + name);
        }
        library("com.sun.jna.platform.mac.CoreFoundation");
        for (String name : new String[]{
                "com.sun.jna.NativeLong", "com.sun.jna.platform.win32.Guid$GUID",
                "com.sun.jna.platform.win32.WinGDI$BITMAPINFO", "com.sun.jna.platform.win32.WinGDI$BITMAPINFOHEADER",
                "com.sun.jna.platform.win32.WinGDI$RGBQUAD", "com.sun.jna.platform.win32.WinCrypt$DATA_BLOB",
                "com.sun.jna.platform.win32.WinCrypt$CRYPTPROTECT_PROMPTSTRUCT",
                "com.sun.jna.platform.win32.WinNT$HANDLE", "com.sun.jna.platform.win32.WinNT$HRESULT",
                "com.sun.jna.platform.win32.WinReg$HKEY", "com.sun.jna.platform.mac.CoreFoundation$CFTypeRef",
                "com.sun.jna.platform.mac.CoreFoundation$CFTypeID"}) {
            interop(type(name));
        }
        for (String name : new String[]{"POINT", "RECT", "HWND", "HICON", "HINSTANCE", "HMODULE", "HMENU",
                "HDC", "HBITMAP", "ATOM", "UINT_PTR", "WPARAM", "LPARAM", "LRESULT"}) {
            interop(type("com.sun.jna.platform.win32.WinDef$" + name));
        }
        for (String name : new String[]{
                "cache.MediaMetaIndex$State", "cache.MediaMetaIndex$Entry", "cache.MediaPlaylistCacheIndex$State",
                "cache.PlaylistCacheIndex$Cached", "library.LibraryCache$Index", "library.LibraryCache$Entry",
                "playlist.LocalPlaylistStore$State", "playlist.MyPlaylistOrderStore$State",
                "playlist.PlaylistTransfer$Document", "playlist.PlaylistTransfer$SourceSummary",
                "plugin.CorePluginHostApi$StoredValue", "plugin.PluginInstaller$Receipt",
                "plugin.PluginManager$InstallReceipt", "plugin.PluginRegistry$State", "plugin.PluginRegistry$Entry",
                "plugin.PluginSetupState$State", "plugin.PluginManifest", "plugin.PluginManifest$UiContribution",
                "netease.dto.NeteaseLyric"}) {
            model(type("dev.t1m3.qplayer." + name));
        }
        audioFactories();
        skija();
        // Log4j reifies TypeConverter<T> signatures through Class.forName in the
        // JDK's generic parser. Derive the required type queries from this version.
        for (Class<?> converter : type("org.apache.logging.log4j.core.config.plugins.convert.TypeConverters").getDeclaredClasses()) {
            for (Type signature : converter.getGenericInterfaces()) model(signature);
        }
        // Gson 2.10 targets Java 8 and discovers the newer record API reflectively.
        methods(reflect, type("java.lang.Class"), "isRecord", "getRecordComponents");
        methods(reflect, type("java.lang.reflect.RecordComponent"), "getName", "getType");
        entry(reflect, "org.mozilla.javascript.Interpreter").addProperty("allDeclaredConstructors", true);
        // These are class-presence queries, not requests to enable Rhino codegen.
        for (String name : new String[]{
                "io.github.timer_err.qml4j.runtime.member.MemberAccess",
                "org.mozilla.javascript.Context", "org.mozilla.javascript.ContextFactory",
                "org.mozilla.javascript.Function", "org.mozilla.javascript.ScriptableObject",
                "org.mozilla.javascript.optimizer.Codegen", "org.mozilla.javascript.regexp.RegExpLoaderImpl"}) {
            type(name);
            entry(reflect, name);
        }
        for (String name : new String[]{"NativeContinuation", "NativeJavaTopPackage", "JavaAdapter", "ImporterTopLevel"}) {
            Class<?> type = type("org.mozilla.javascript." + name);
            methods(reflect, type, "init");
        }
        Path directory = output.resolve("META-INF/native-image/dev.t1m3.qplayer/desktop");
        write(directory.resolve("reflect-config.json"), array(reflect));
        write(directory.resolve("jni-config.json"), array(jni));
        JsonArray proxyConfig = new JsonArray();
        for (String name : proxies) { JsonArray interfaces = new JsonArray(); interfaces.add(name); proxyConfig.add(interfaces); }
        write(directory.resolve("proxy-config.json"), proxyConfig);
        // Globs also register absent probes (Log4j/Rhino configuration, system
        // libraries and locale fallbacks) under exact reachability checking.
        JsonArray resourceGlobs = new JsonArray();
        for (String glob : new String[]{
                "META-INF/qplayer/qml/**", "fonts/**", "lang/**", "shaders/**", "tempera/**",
                "app-icon.*", "version.properties", "log4j2*", "rhino*.config",
                "META-INF/log4j*", "META-INF/org/apache/logging/log4j/**",
                "META-INF/services/**", "META-INF/**/*.sha1",
                "javazoom/jl/decoder/sfd.ser", "dev/t1m3/qplayer/plugin/**/*.json",
                "dev/t1m3/qplayer/plugin/**/*.js", "com/sun/jna/**/*.so",
                "com/sun/jna/**/*.dll", "com/sun/jna/**/*.dylib", "com/sun/jna/**/*.jnilib",
                "linux/**", "windows/**", "macos/**",
                "io/github/humbleui/skija/linux/**", "io/github/humbleui/skija/windows/**",
                "io/github/humbleui/skija/macos/**", "io/github/humbleui/skija/impl/Library.class",
                "org/mozilla/javascript/resources/Messages*.properties",
                "*.so", "*.so.*", "*.dylib", "*.dll", "native-webview/**"}) {
            addResourceGlob(resourceGlobs, glob);
        }
        for (String prefix : new String[]{"miuix", "components", "dialogs", "pages", "settings"}) {
            for (String extension : new String[]{"js", "json", "svg", "png", "webp"}) {
                addResourceGlob(resourceGlobs, prefix + "/**/*." + extension);
            }
        }
        JsonObject reachability = new JsonObject(); reachability.add("resources", resourceGlobs);
        // Optional-dependency detection must still return ClassNotFoundException,
        // not terminate the executable under exact metadata checking.
        JsonArray classQueries = new JsonArray();
        Collections.addAll(queriedTypes, "javax.servlet.Servlet", "jakarta.servlet.Servlet",
                "org.osgi.framework.FrameworkUtil", "com.fasterxml.jackson.databind.ObjectMapper",
                "android.os.Build$VERSION");
        for (String name : queriedTypes) {
            JsonObject query = new JsonObject(); query.addProperty("type", name); classQueries.add(query);
        }
        for (Map.Entry<String, Set<String>> owner : callbackLambdas.entrySet()) {
            for (String callback : owner.getValue()) {
                JsonArray interfaces = new JsonArray(); interfaces.add(callback);
                JsonObject lambda = new JsonObject(); lambda.addProperty("declaringClass", owner.getKey()); lambda.add("interfaces", interfaces);
                JsonObject descriptor = new JsonObject(); descriptor.add("lambda", lambda);
                JsonObject query = new JsonObject(); query.add("type", descriptor);
                JsonObject condition = new JsonObject(); condition.addProperty("typeReached", owner.getKey()); query.add("condition", condition);
                // JNA probes OPTIONS even when absent, then inspects callback methods.
                query.addProperty("allPublicFields", true);
                query.addProperty("allDeclaredMethods", true);
                query.addProperty("allPublicMethods", true);
                classQueries.add(query);
            }
        }
        reachability.add("reflection", classQueries);
        write(directory.resolve("reachability-metadata.json"), reachability);
        JsonObject resources = new JsonObject();
        JsonArray bundles = new JsonArray(); JsonObject messages = new JsonObject();
        messages.addProperty("name", "org.mozilla.javascript.resources.Messages"); bundles.add(messages);
        resources.add("bundles", bundles);
        write(directory.resolve("resource-config.json"), resources);
        JsonObject serialization = new JsonObject(); JsonArray types = new JsonArray();
        JsonObject floats = new JsonObject(); floats.addProperty("name", "[F"); types.add(floats);
        serialization.add("types", types); serialization.add("lambdaCapturingTypes", new JsonArray());
        write(directory.resolve("serialization-config.json"), serialization);
        System.out.println("Native host metadata: " + reflect.size() + " reflection types, " + jni.size() + " JNI types");
    }

    private static void addResourceGlob(JsonArray resources, String glob) {
        JsonObject entry = new JsonObject(); entry.addProperty("glob", glob); resources.add(entry);
    }

    private void hostInterop() throws Exception {
        Set<String> classes = new TreeSet<>();
        String prefix = "dev/t1m3/qplayer/";
        for (String anchor : new String[]{HOST + "app.Main", "dev.t1m3.qplayer.bridge.PlayerController"}) {
            Path location = Paths.get(type(anchor).getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isDirectory(location)) {
                try (Stream<Path> paths = Files.walk(location.resolve(prefix))) {
                    paths.filter(Files::isRegularFile).forEach(path -> {
                        String name = location.relativize(path).toString().replace('\\', '/');
                        if (name.endsWith(".class")) classes.add(name.substring(0, name.length() - 6).replace('/', '.'));
                    });
                }
            } else {
                try (JarFile jar = new JarFile(location.toFile())) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.startsWith(prefix) && name.endsWith(".class")) {
                            classes.add(name.substring(0, name.length() - 6).replace('/', '.'));
                        }
                    }
                }
            }
        }
        Class<?> library = type("com.sun.jna.Library");
        Class<?> callback = type("com.sun.jna.Callback");
        Class<?> gsonTypeToken = type("com.google.gson.reflect.TypeToken");
        for (String name : classes) {
            Class<?> candidate = type(name);
            collectCallbackLambdas(name, callback);
            if (gsonTypeToken.isAssignableFrom(candidate)) model(candidate.getGenericSuperclass());
            if (candidate.isInterface() && library.isAssignableFrom(candidate)) library(name);
            if (callback.isAssignableFrom(candidate)) callback(name);
            if (structure.isAssignableFrom(candidate) || nativeMapped.isAssignableFrom(candidate)) interop(candidate);
        }
    }

    private void collectCallbackLambdas(String owner, Class<?> callback) throws IOException {
        String resource = owner.replace('.', '/') + ".class";
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing host bytecode: " + resource);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitInvokeDynamicInsn(String name, String descriptor,
                                Handle bootstrap, Object... arguments) {
                            if (!"java/lang/invoke/LambdaMetafactory".equals(bootstrap.getOwner())) return;
                            String target = org.objectweb.asm.Type.getReturnType(descriptor).getClassName();
                            try {
                                if (callback.isAssignableFrom(type(target))) {
                                    callbackLambdas.computeIfAbsent(owner, key -> new TreeSet<>()).add(target);
                                }
                            } catch (ClassNotFoundException error) {
                                throw new IllegalStateException("Missing callback target: " + target, error);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
    }

    private void library(String name) throws Exception {
        Class<?> type = type(name);
        proxies.add(name);
        JsonObject entry = entry(reflect, name);
        entry.addProperty("allDeclaredMethods", true);
        entry.addProperty("allDeclaredFields", true);
        for (Method method : type.getMethods()) {
            interop(method.getReturnType());
            for (Class<?> parameter : method.getParameterTypes()) interop(parameter);
        }
    }

    private void callback(String name) throws Exception {
        Class<?> type = type(name);
        if (type.isInterface()) proxies.add(name);
        entry(reflect, name).addProperty("allDeclaredMethods", true);
        entry(reflect, name).addProperty("allPublicFields", true);
        entry(reflect, name).addProperty("allPublicMethods", true);
        entry(jni, name).addProperty("allDeclaredMethods", true);
        for (Method method : type.getMethods()) {
            interop(method.getReturnType());
            for (Class<?> parameter : method.getParameterTypes()) interop(parameter);
        }
    }

    private void interop(Class<?> type) {
        if (type.isArray()) { interop(type.getComponentType()); return; }
        if (type.isPrimitive() || type.isInterface() || !interopTypes.add(type.getName())) return;
        if (structure.isAssignableFrom(type)) {
            JsonObject entry = entry(reflect, type.getName());
            entry.addProperty("allDeclaredFields", true);
            entry.addProperty("allDeclaredConstructors", true);
            for (Field field : type.getFields()) if (!Modifier.isStatic(field.getModifiers())) interop(field.getType());
        } else if (nativeMapped.isAssignableFrom(type)) {
            entry(reflect, type.getName()).addProperty("allDeclaredConstructors", true);
        }
    }

    private void model(Type value) {
        if (value instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) value;
            model(parameterized.getRawType());
            for (Type argument : parameterized.getActualTypeArguments()) model(argument);
        } else if (value instanceof GenericArrayType) {
            model(((GenericArrayType) value).getGenericComponentType());
        } else if (value instanceof WildcardType) {
            for (Type bound : ((WildcardType) value).getUpperBounds()) model(bound);
        } else if (value instanceof Class<?>) {
            Class<?> type = (Class<?>) value;
            if (!type.isPrimitive()) queriedTypes.add(type.getTypeName());
            if (type.isArray()) { model(type.getComponentType()); return; }
            // Gson constructs concrete JSON containers reflectively, without
            // treating their implementation fields as application model fields.
            if (Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)) {
                entry(reflect, type.getName()).addProperty("allDeclaredConstructors", true);
                return;
            }
            if (!type.getName().startsWith("dev.t1m3.qplayer.") || !models.add(type.getName())) return;
            JsonObject entry = entry(reflect, type.getName());
            entry.addProperty("allDeclaredFields", true);
            entry.addProperty("allDeclaredConstructors", true);
            if (!type.isEnum() && !Modifier.isAbstract(type.getModifiers())) {
                try { type.getDeclaredConstructor(); }
                catch (NoSuchMethodException noDefaultConstructor) { entry.addProperty("unsafeAllocated", true); }
            }
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers())) model(field.getGenericType());
            }
            model(type.getGenericSuperclass());
        }
    }

    private void audioFactories() throws Exception {
        Class<?> anchor = type("org.jaudiotagger.tag.id3.framebody.FrameBodyAPIC");
        URI location = anchor.getProtectionDomain().getCodeSource().getLocation().toURI();
        Class<?> chunkReader = type("org.jaudiotagger.audio.asf.io.ChunkReader");
        try (JarFile jar = new JarFile(Paths.get(location).toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String path = entries.nextElement().getName();
                boolean tagType = path.startsWith("org/jaudiotagger/tag/");
                boolean asfType = path.startsWith("org/jaudiotagger/audio/asf/io/");
                if ((!tagType && !asfType) || !path.endsWith(".class")) continue;
                Class<?> type = type(path.substring(0, path.length() - 6).replace('/', '.'));
                if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) continue;
                boolean frameBody = path.startsWith("org/jaudiotagger/tag/id3/framebody/FrameBody");
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    Class<?>[] parameters = constructor.getParameterTypes();
                    // ID3Tags.copyObject uses a same-type constructor for frames AND datatypes.
                    boolean copy = tagType && parameters.length == 1 && parameters[0] == type;
                    // ChunkContainerReader registers concrete readers through Class.newInstance().
                    boolean asf = asfType && chunkReader.isAssignableFrom(type) && parameters.length == 0;
                    boolean frame = frameBody && (parameters.length == 0
                            || (parameters.length == 2 && parameters[0] == java.nio.ByteBuffer.class && parameters[1] == int.class));
                    if (copy || asf || frame) method(reflect, type.getName(), "<init>", parameters);
                }
            }
        }
    }

    private void skija() throws Exception {
        for (String name : new String[]{
                "AnimationFrameInfo", "Color4f", "RSXform", "FontFamilyName", "FontFeature", "FontMetrics",
                "FontVariation", "FontVariationAxis", "Image", "Path", "ImageInfo", "ImageWithFilterResult",
                "PathSegment", "RuntimeEffectUniformInfo", "RuntimeEffectChildInfo", "SurfaceProps", "TransferFunction",
                "paragraph.LineMetrics", "paragraph.TextBox", "paragraph.DecorationStyle", "paragraph.Shadow",
                "shaper.RunInfo", "svg.SVGLength", "svg.SVGPreserveAspectRatio"}) {
            entry(jni, SKIJA + name).addProperty("allDeclaredConstructors", true);
        }
        for (String name : new String[]{"IPoint", "Point"}) entry(jni, TYPES + name).addProperty("allDeclaredConstructors", true);
        fields(SKIJA + "impl.Native", "_ptr");
        fields(SKIJA + "FontArguments", "_collectionIndex", "_variations", "_paletteIndex", "_paletteOverrides");
        fields(SKIJA + "FontFeature", "_tag", "_value", "_start", "_end");
        fields(SKIJA + "FontPaletteOverride", "_index", "_color");
        fields(SKIJA + "FontVariation", "_tag", "_value");
        fields(SKIJA + "TransferFunction", "_g", "_a", "_b", "_c", "_d", "_e", "_f");
        fields(TYPES + "IRect", "_left", "_top", "_right", "_bottom");
        fields(TYPES + "Rect", "_left", "_top", "_right", "_bottom");
        fields(TYPES + "Point", "_x", "_y"); fields(TYPES + "RRect", "_radii");
        methods(jni, type(TYPES + "IRect"), "makeLTRB"); methods(jni, type(TYPES + "Rect"), "makeLTRB");
        methods(jni, type(TYPES + "RRect"), "makeLTRB", "makeNinePatchLTRB", "makeComplexLTRB");
        methods(jni, type(SKIJA + "Drawable"), "_onDraw", "onGetBounds");
        methods(jni, type(SKIJA + "PaintFilterCanvas"), "onFilter");
        methods(jni, type(SKIJA + "SurfaceProps"), "_getFlags", "_getPixelGeometryOrdinal");
        fields(SKIJA + "shaper.BidiRun", "_end", "_level"); fields(SKIJA + "shaper.FontRun", "_end");
        methods(jni, type(SKIJA + "shaper.FontRun"), "_getFontPtr");
        fields(SKIJA + "shaper.LanguageRun", "_end", "_language"); fields(SKIJA + "shaper.ScriptRun", "_end", "_scriptTag");
        fields(SKIJA + "shaper.RunInfo", "_fontPtr");
        fields(SKIJA + "shaper.ShapingOptions", "_fontMgr", "_features", "_leftToRight", "_approximateSpaces", "_approximatePunctuation");
        methods(jni, type(SKIJA + "shaper.RunHandler"), "beginLine", "runInfo", "commitRunInfo", "runOffset", "commitRun", "commitLine");
        methods(jni, type(SKIJA + "skottie.Logger"), "log"); fields(SKIJA + "skottie.LogLevel", "WARNING", "ERROR");
        for (String name : new String[]{"FontMgr", "shaper.FontMgrRunIterator", "shaper.HbIcuScriptRunIterator",
                "shaper.IcuBidiRunIterator", "shaper.TextBlobBuilderRunHandler", "shaper.TextLineRunHandler"}) entry(jni, SKIJA + name);
        methods(jni, java.io.OutputStream.class, "write", "flush");
        method(jni, "java.lang.Float", "<init>", float.class);
        methods(jni, Throwable.class, "printStackTrace"); methods(jni, java.util.Iterator.class, "next", "hasNext");
        methods(jni, java.util.function.BooleanSupplier.class, "getAsBoolean"); methods(jni, Runnable.class, "run");
        entry(jni, "java.lang.String"); method(jni, "java.lang.RuntimeException", "<init>", String.class);
        method(jni, "java.lang.IllegalArgumentException", "<init>", String.class);
    }

    private void fields(String name, String... names) {
        JsonObject entry = entry(jni, name);
        JsonArray fields = entry.has("fields") ? entry.getAsJsonArray("fields") : new JsonArray();
        for (String field : names) { JsonObject value = new JsonObject(); value.addProperty("name", field); fields.add(value); }
        entry.add("fields", fields);
    }

    private static void methods(Map<String, JsonObject> target, Class<?> type, String... names) {
        Set<String> wanted = new HashSet<>(); Collections.addAll(wanted, names);
        List<Method> matches = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) if (wanted.contains(method.getName())) matches.add(method);
        Collections.sort(matches, (a, b) -> a.toGenericString().compareTo(b.toGenericString()));
        for (Method match : matches) method(target, type.getName(), match.getName(), match.getParameterTypes());
    }

    private static void method(Map<String, JsonObject> target, String type, String name, Class<?>... parameters) {
        JsonObject entry = entry(target, type);
        JsonArray methods = entry.has("methods") ? entry.getAsJsonArray("methods") : new JsonArray();
        JsonObject method = new JsonObject(); method.addProperty("name", name);
        JsonArray arguments = new JsonArray(); for (Class<?> parameter : parameters) arguments.add(parameter.getTypeName());
        method.add("parameterTypes", arguments); methods.add(method); entry.add("methods", methods);
    }

    private static JsonObject entry(Map<String, JsonObject> target, String name) {
        JsonObject entry = target.get(name);
        if (entry == null) { entry = new JsonObject(); entry.addProperty("name", name); target.put(name, entry); }
        return entry;
    }

    private static JsonArray array(Map<String, JsonObject> values) {
        JsonArray array = new JsonArray(); for (JsonObject value : values.values()) array.add(value); return array;
    }

    private static void write(Path path, Object data) throws IOException {
        Files.createDirectories(path.getParent());
        byte[] bytes = (new GsonBuilder().setPrettyPrinting().create().toJson(data) + "\n").getBytes(StandardCharsets.UTF_8);
        if (!Files.exists(path) || !java.util.Arrays.equals(Files.readAllBytes(path), bytes)) Files.write(path, bytes);
    }
}
