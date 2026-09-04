package io.github.timer_err.qml4j.android;

import com.android.tools.r8.ByteDataView;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.DexIndexedConsumer;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.origin.Origin;

import android.os.Build;

import dalvik.system.InMemoryDexClassLoader;

import io.github.timer_err.qml4j.engine.classloader.ClassLoaderBackend;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class DexClassLoaderBackend implements ClassLoaderBackend {

    // Bump when the dex layout/keying changes so old caches are ignored.
    private static final int CACHE_FORMAT = 1;

    private ClassLoader parent;
    private InMemoryDexClassLoader combined;
    private final int minApi;
    // On-disk dex cache (D8 dexing is the slow part of QML startup). Null disables it.
    private final File cacheDir;

    public DexClassLoaderBackend(ClassLoader parent) {
        this(parent, 26, null);
    }

    public DexClassLoaderBackend(ClassLoader parent, int minApi) {
        this(parent, minApi, null);
    }

    public DexClassLoaderBackend(ClassLoader parent, int minApi, File cacheDir) {
        this.parent = parent;
        this.minApi = minApi;
        this.cacheDir = cacheDir;
        if (cacheDir != null) cacheDir.mkdirs();
        preloadCachedDex();
    }

    @Override
    public Class<?> defineClass(String name, byte[] jvmBytecode) {
        Map<String, byte[]> one = new LinkedHashMap<>();
        one.put(name, jvmBytecode);
        return defineClasses(one).get(name);
    }

    @Override
    public Map<String, Class<?>> defineClasses(Map<String, byte[]> classes) {
        if (classes.isEmpty()) return new LinkedHashMap<>();
        Map<String, Class<?>> fromCache = loadFromCombined(classes);
        if (fromCache != null) return fromCache;
        byte[] dex = dexAll(classes);
        ByteBuffer buf = ByteBuffer.wrap(dex);
        InMemoryDexClassLoader loader = new InMemoryDexClassLoader(buf, parent);
        Map<String, Class<?>> out = new LinkedHashMap<>();
        try {
            for (String name : classes.keySet()) {
                out.put(name, loader.loadClass(name));
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        parent = loader;
        return out;
    }

    /** Second launch: one classloader for every cached dex instead of an
     *  80-deep InMemoryDexClassLoader parent chain. API 27+ only. */
    private void preloadCachedDex() {
        if (Build.VERSION.SDK_INT < 27 || cacheDir == null) return;
        File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(".dex"));
        if (files == null || files.length == 0) return;
        List<ByteBuffer> buffers = new ArrayList<>(files.length);
        for (int i = 0; i < files.length; i++) {
            byte[] data = readFile(files[i]);
            if (data != null && data.length > 0) buffers.add(ByteBuffer.wrap(data));
        }
        if (buffers.isEmpty()) return;
        combined = new InMemoryDexClassLoader(
                buffers.toArray(new ByteBuffer[0]), parent);
        parent = combined;
    }

    private Map<String, Class<?>> loadFromCombined(Map<String, byte[]> classes) {
        if (combined == null) return null;
        Map<String, Class<?>> out = new LinkedHashMap<>();
        try {
            for (String name : classes.keySet()) {
                out.put(name, combined.loadClass(name));
            }
            return out;
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    // Dex the batch, reading from / writing to the on-disk cache when enabled. The key
    // hashes the (sorted) input bytecode + minApi, so it is stable across launches of the
    // same build and naturally misses when any class changes; a reinstall additionally
    // wipes the directory (see QPlayerActivity).
    private byte[] dexAll(Map<String, byte[]> classes) {
        File cacheFile = cacheDir != null ? new File(cacheDir, cacheKey(classes) + ".dex") : null;
        if (cacheFile != null) {
            byte[] cached = readFile(cacheFile);
            if (cached != null) return cached;
        }
        byte[] dex = d8(classes);
        if (cacheFile != null) writeFile(cacheFile, dex);
        return dex;
    }

    private byte[] d8(Map<String, byte[]> classes) {
        D8Command.Builder b = D8Command.builder();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            b.addClassProgramData(e.getValue(), Origin.unknown());
        }
        b.setMinApiLevel(minApi);
        final byte[][] out = new byte[1][];
        b.setProgramConsumer(new DexIndexedConsumer.ForwardingConsumer(null) {
            @Override
            public void accept(int fileIndex,
                               ByteDataView data,
                               java.util.Set<String> descriptors,
                               DiagnosticsHandler handler) {
                if (out[0] != null) {
                    throw new IllegalStateException(
                        "D8 produced multiple dex files; expected single");
                }
                out[0] = data.copyByteData();
            }
        });
        try {
            D8.run(b.build());
        } catch (Exception ex) {
            throw new RuntimeException("D8 dexing failed", ex);
        }
        if (out[0] == null) {
            throw new IllegalStateException("D8 produced no dex output");
        }
        return out[0];
    }

    private String cacheKey(Map<String, byte[]> classes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((byte) CACHE_FORMAT);
            md.update((byte) minApi);
            // TreeMap so the digest is independent of iteration order.
            for (Map.Entry<String, byte[]> e : new TreeMap<>(classes).entrySet()) {
                md.update(e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(e.getValue());
            }
            byte[] h = md.digest();
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte x : h) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16));
                sb.append(Character.forDigit(x & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] readFile(File f) {
        if (!f.exists()) return null;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] data = new byte[(int) raf.length()];
            raf.readFully(data);
            return data;
        } catch (IOException e) {
            return null; // unreadable cache entry: fall back to recompiling
        }
    }

    private static void writeFile(File f, byte[] data) {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        } catch (IOException e) {
            tmp.delete();
            return; // caching is best-effort
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }
}
