package io.github.timer_err.qml4j.android;

import io.github.timer_err.qml4j.compiler.CompiledScene;
import io.github.timer_err.qml4j.compiler.CompiledSceneCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 磁盘持久化的 QML 编译产物缓存。
 *
 * <p>命中后跳过 parse → bytecode(启动的主要耗时之一);生成的类字节码再走
 * {@link DexClassLoaderBackend} 的 dex 缓存,因此第二次启动几乎零编译。
 * 缓存目录在 APK 重装时清空(与 dex 缓存同策略),key 由调用方提供。
 */
public final class DiskCompiledSceneCache implements CompiledSceneCache {

    private static final String MAGIC = "QMLS";

    private final File dir;

    public DiskCompiledSceneCache(File dir) {
        this.dir = dir;
        if (dir != null) dir.mkdirs();
    }

    @Override
    public CompiledScene load(String key) {
        if (dir == null || key == null) return null;
        File f = new File(dir, key + ".scene");
        if (!f.exists()) return null;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(f)))) {
            if (!MAGIC.equals(readStr(in))) return null;
            if (in.readInt() != CompiledScene.FORMAT_VERSION) return null;
            String root = readStr(in);

            int n = in.readInt();
            Map<String, byte[]> classes = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                String name = readStr(in);
                int len = in.readInt();
                if (len < 0 || len > 1 << 26) return null;
                byte[] b = new byte[len];
                in.readFully(b);
                classes.put(name, b);
            }

            Map<String, String> imported = new LinkedHashMap<>();
            int mi = in.readInt();
            for (int i = 0; i < mi; i++) imported.put(readStr(in), readStr(in));

            Map<String, Map<String, String>> singletons = new LinkedHashMap<>();
            int si = in.readInt();
            for (int i = 0; i < si; i++) {
                String name = readStr(in);
                int k = in.readInt();
                Map<String, String> m = new LinkedHashMap<>();
                for (int j = 0; j < k; j++) m.put(readStr(in), readStr(in));
                singletons.put(name, m);
            }

            int ji = in.readInt();
            List<CompiledScene.JsImport> imports = new ArrayList<>();
            for (int i = 0; i < ji; i++) {
                imports.add(new CompiledScene.JsImport(readStr(in), readStr(in)));
            }
            return new CompiledScene(root, classes, imported, singletons, imports);
        } catch (IOException | RuntimeException e) {
            return null;   // 损坏/版本不符 → 交给引擎重新编译
        }
    }

    @Override
    public void store(String key, CompiledScene scene) {
        if (dir == null || key == null || scene == null) return;
        try {
            File tmp = new File(dir, key + ".scene.tmp");
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tmp)))) {
                writeStr(out, MAGIC);
                out.writeInt(CompiledScene.FORMAT_VERSION);
                writeStr(out, scene.rootClassName());

                out.writeInt(scene.classes().size());
                for (Map.Entry<String, byte[]> e : scene.classes().entrySet()) {
                    writeStr(out, e.getKey());
                    out.writeInt(e.getValue().length);
                    out.write(e.getValue());
                }

                out.writeInt(scene.importedTypes().size());
                for (Map.Entry<String, String> e : scene.importedTypes().entrySet()) {
                    writeStr(out, e.getKey());
                    writeStr(out, e.getValue());
                }

                out.writeInt(scene.singletons().size());
                for (Map.Entry<String, Map<String, String>> e : scene.singletons().entrySet()) {
                    writeStr(out, e.getKey());
                    out.writeInt(e.getValue().size());
                    for (Map.Entry<String, String> kv : e.getValue().entrySet()) {
                        writeStr(out, kv.getKey());
                        writeStr(out, kv.getValue());
                    }
                }

                out.writeInt(scene.jsImports().size());
                for (CompiledScene.JsImport ji : scene.jsImports()) {
                    writeStr(out, ji.alias());
                    writeStr(out, ji.path());
                }
            }
            File target = new File(dir, key + ".scene");
            if (!tmp.renameTo(target)) tmp.delete();
        } catch (IOException ignored) {
            // 缓存失败是 best-effort,不影响功能
        }
    }

    private static void writeStr(DataOutputStream out, String s) throws IOException {
        byte[] b = s == null ? new byte[0] : s.getBytes("UTF-8");
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readStr(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 1 << 24) throw new IOException("bad string length");
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, "UTF-8");
    }
}
