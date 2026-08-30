package dev.t1m3.qplayer.desktop.resources;

import dev.t1m3.qplayer.resources.DiskCompiledSceneCache;
import io.github.timer_err.qml4j.compiler.CompiledScene;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class DiskCompiledSceneCacheTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void roundTripsCompleteCompiledScene() throws Exception {
        Path temporaryDirectory = temporaryFolder.getRoot().toPath();
        DiskCompiledSceneCache cache = new DiskCompiledSceneCache(temporaryDirectory, "bundle-a");
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("qml.GeneratedCard", new byte[]{1, 2, 3});
        classes.put("qml.GeneratedRoot", new byte[]{4, 5, 6});
        Map<String, String> importedTypes = Map.of("widgets/Card.qml", "qml.GeneratedCard");
        Map<String, Map<String, String>> singletons =
                Map.of("theme", Map.of("Theme", "qml.GeneratedTheme"));
        List<CompiledScene.JsImport> jsImports =
                List.of(new CompiledScene.JsImport("Logic", "scripts/Logic.js"));
        CompiledScene scene = new CompiledScene("qml.GeneratedRoot", classes,
                importedTypes, singletons, jsImports);

        String key = cache.sceneKey("Main.qml");
        cache.store(key, scene);
        CompiledScene restored = cache.load(key);

        assertNotNull(restored);
        assertEquals(scene.rootClassName(), restored.rootClassName());
        assertArrayEquals(classes.get("qml.GeneratedCard"),
                restored.classes().get("qml.GeneratedCard"));
        assertEquals(importedTypes, restored.importedTypes());
        assertEquals(singletons, restored.singletons());
        assertEquals("Logic", restored.jsImports().get(0).alias());
        assertEquals("scripts/Logic.js", restored.jsImports().get(0).path());
    }

    @Test
    public void rejectsAndDeletesCorruptEntry() throws Exception {
        Path temporaryDirectory = temporaryFolder.getRoot().toPath();
        DiskCompiledSceneCache cache = new DiskCompiledSceneCache(temporaryDirectory, "bundle-a");
        String key = cache.sceneKey("Main.qml");
        CompiledScene scene = new CompiledScene("qml.Root",
                Map.of("qml.Root", new byte[]{1}), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyList());
        cache.store(key, scene);
        Path file;
        try (var files = Files.list(temporaryDirectory)) {
            file = files.findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(file);
        bytes[0] ^= 0x7f;
        Files.write(file, bytes);

        assertNull(cache.load(key));
        assertFalse(Files.exists(file));
    }

    @Test
    public void resourceFingerprintIsOrderIndependentAndTracksQmlChanges() throws Exception {
        Map<String, byte[]> first = new LinkedHashMap<>();
        first.put("Main.qml", new byte[]{1});
        first.put("md3/Core/qmldir", new byte[]{2});
        first.put("ignored.png", new byte[]{3});
        Map<String, byte[]> reordered = new LinkedHashMap<>();
        reordered.put("ignored.png", new byte[]{9});
        reordered.put("md3/Core/qmldir", new byte[]{2});
        reordered.put("Main.qml", new byte[]{1});

        String original = QmlResourceFingerprint.digest("1.2.0", new byte[]{7}, first);
        String same = QmlResourceFingerprint.digest("1.2.0", new byte[]{7}, reordered);
        assertEquals(original, same);

        reordered.put("Main.qml", new byte[]{8});
        String qmlChanged = QmlResourceFingerprint.digest("1.2.0", new byte[]{7}, reordered);
        assertNotEquals(original, qmlChanged);
        assertNotEquals(original,
                QmlResourceFingerprint.digest("1.2.0", new byte[]{8}, first));
        assertNotEquals(original,
                QmlResourceFingerprint.digest("1.2.1", new byte[]{7}, first));
    }

    @Test
    public void fingerprintsThePackagedDesktopResources() {
        String fingerprint = new ClasspathResourceLoader().qmlFingerprint("1.2.0");
        assertNotNull(fingerprint);
        assertEquals(64, fingerprint.length());
    }
}
