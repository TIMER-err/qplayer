package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.compiler.CompiledScene;
import io.github.timer_err.qml4j.compiler.bytecode.QmlCompiler;
import io.github.timer_err.qml4j.compiler.bytecode.rhino.RhinoScope;
import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.js.JsRuntime;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Build-only adapter for qml4j 0.2.33's package-private scene recorder.
 * No QML objects, renderers, fonts, or platform natives are instantiated. */
public final class QPlayerSceneCapture {
    private QPlayerSceneCapture() { }

    public static CompiledScene capture(ResourceLoader resources, String entry,
                                        List<String> bundledFiles, int firstComponent) throws Exception {
        for (String name : new String[] {"player", "settings", "i18n", "hostWindow", "desktopLyric"}) {
            RhinoScope.registerContextProperty(name);
        }
        QmlEngine engine = new QmlEngine();
        Loader loader = new Loader(engine, StockTypes.registry());
        loader.setResources(resources);
        CompilationRecorder recorder = new CompilationRecorder();
        Field recorderField = Loader.class.getDeclaredField("recorder");
        recorderField.setAccessible(true);
        recorderField.set(loader, recorder);
        Field compilerField = Loader.class.getDeclaredField("compiler");
        compilerField.setAccessible(true);
        Field counterField = QmlCompiler.class.getDeclaredField("componentCounter");
        counterField.setAccessible(true);
        AtomicInteger counter = (AtomicInteger) counterField.get(compilerField.get(loader));
        counter.set(firstComponent);
        Method resolve = Loader.class.getDeclaredMethod("resolveCompound", String.class, List.class);
        resolve.setAccessible(true);
        try (JsRuntime.Activation ignored = JsRuntime.activate(engine.jsRealm())) {
            Class<? extends QObject> root = resolve(loader, resolve, entry);
            for (String path : bundledFiles) resolve(loader, resolve, path);
            if (counter.get() - firstComponent >= 1_000_000) {
                throw new IllegalStateException("Generated scene exceeds its reserved class namespace");
            }
            return recorder.finish(root);
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends QObject> resolve(Loader loader, Method method, String path) throws Exception {
        int slash = path.lastIndexOf('/');
        String directory = slash < 0 ? "" : path.substring(0, slash);
        String type = path.substring(slash + 1, path.length() - ".qml".length());
        try {
            Class<? extends QObject> result = (Class<? extends QObject>) method.invoke(
                    loader, type, Collections.singletonList(directory));
            if (result == null) throw new IllegalArgumentException("Cannot compile bundled QML: " + path);
            return result;
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("Build-time QML compilation failed in " + path, error.getCause());
        }
    }
}
