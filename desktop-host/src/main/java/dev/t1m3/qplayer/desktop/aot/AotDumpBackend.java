package dev.t1m3.qplayer.desktop.aot;

import io.github.timer_err.qml4j.engine.classloader.ClassLoaderBackend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Build-time {@link ClassLoaderBackend} that captures every class the QML compiler
 * generates: it writes each {@code (name, bytecode)} to a {@code .class} file under
 * an output directory AND defines it normally (so the build-time {@code view.load}
 * still works and resolves cross-references). Running a full {@code view.load} once
 * with this backend therefore dumps the exact set of classes the runtime needs,
 * with the same names the compiler would produce live — ready to be put on the
 * classpath so a {@link PrecompiledBackend} can load them at runtime (and so
 * native-image can include them ahead of time).
 */
public final class AotDumpBackend implements ClassLoaderBackend {

    private final Path outDir;
    private final Dyn loader = new Dyn(AotDumpBackend.class.getClassLoader());
    private final AtomicInteger count = new AtomicInteger();
    private final List<String> names = new CopyOnWriteArrayList<>();

    public AotDumpBackend(Path outDir) {
        this.outDir = outDir;
    }

    public int dumpedCount() {
        return count.get();
    }

    // Names of every dumped class, so the native build can register them for reflection
    // (PrecompiledBackend loads them by name via Class.forName). See GeneratedClassesFeature.
    public List<String> dumpedNames() {
        return Collections.unmodifiableList(names);
    }

    @Override
    public Class<?> defineClass(String name, byte[] jvmBytecode) {
        try {
            Path f = outDir.resolve(name.replace('.', '/') + ".class");
            Files.createDirectories(f.getParent());
            Files.write(f, jvmBytecode);
            count.incrementAndGet();
            names.add(name);
        } catch (IOException e) {
            throw new RuntimeException("failed writing AOT class " + name, e);
        }
        return loader.define(name, jvmBytecode);
    }

    // A single loader holds every defined class so the generated classes resolve
    // each other (same as qml4j's JvmClassLoaderBackend).
    private static final class Dyn extends ClassLoader {
        Dyn(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
