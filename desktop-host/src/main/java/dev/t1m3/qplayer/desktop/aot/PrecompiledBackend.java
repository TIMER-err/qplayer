package dev.t1m3.qplayer.desktop.aot;

import io.github.timer_err.qml4j.engine.classloader.ClassLoaderBackend;
import dev.t1m3.qplayer.util.Logger;

/**
 * Runtime {@link ClassLoaderBackend} that loads the AOT-dumped QML classes from the
 * classpath by name (via {@link Class#forName(String, boolean, ClassLoader)}) instead of defining fresh bytecode
 * at runtime — the property GraalVM native-image requires (no runtime
 * {@code defineClass} of new classes). The compiler still produces the bytecode
 * (pure byte[] generation, which native-image allows); this backend simply ignores
 * it and binds the precompiled class.
 *
 * <p>If a class was not captured at build time it falls back to defining the live
 * bytecode (so a normal JVM run never breaks) and logs the miss, so the AOT capture
 * can be made exhaustive before a native build.
 */
public final class PrecompiledBackend implements ClassLoaderBackend {

    private final ClassLoader classpath = PrecompiledBackend.class.getClassLoader();
    private final Fallback fallback = new Fallback(PrecompiledBackend.class.getClassLoader());
    private int misses;

    @Override
    public Class<?> defineClass(String name, byte[] jvmBytecode) {
        try {
            return Class.forName(name, false, classpath);
        } catch (ClassNotFoundException notAot) {
            if (misses++ == 0) {
                Logger.warn("AOT miss (defining live): {} — capture is not exhaustive", name);
            } else {
                Logger.warn("AOT miss (defining live): {}", name);
            }
            return fallback.define(name, jvmBytecode);
        }
    }

    public int misses() {
        return misses;
    }

    private static final class Fallback extends ClassLoader {
        Fallback(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
