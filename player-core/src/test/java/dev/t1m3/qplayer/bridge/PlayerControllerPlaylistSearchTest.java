package dev.t1m3.qplayer.bridge;

import com.google.gson.Gson;
import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.media.Playlist;
import dev.t1m3.qplayer.plugin.PluginInstaller;
import dev.t1m3.qplayer.plugin.PluginManager;
import dev.t1m3.qplayer.plugin.PluginManifest;
import dev.t1m3.qplayer.plugin.PluginPackageVerifier;
import dev.t1m3.qplayer.plugin.PluginRegistry;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** Offline plugins exercise pagination and stale-response isolation through the real runtime. */
public class PlayerControllerPlaylistSearchTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    @Rule public final ControllerTestLifecycle lifecycle = new ControllerTestLifecycle();
    private String oldBase;
    private String oldCache;
    private Path base;
    private PlayerController controller;
    private PluginManager manager;

    @Before public void isolate() throws Exception {
        oldBase = AppDirs.base();
        oldCache = AppDirs.cacheBase();
        base = temporary.newFolder("playlist-search").toPath();
        AppDirs.setBase(base.toString());
        AppDirs.setCacheBase(base.resolve("cache").toString());
    }

    @After public void close() {
        try {
            if (controller != null) lifecycle.close(controller);
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    @Test public void pagesDeduplicateWithinEachProviderButKeepDistinctSources() throws Exception {
        install("alpha");
        install("beta");
        new PluginRegistry().setPrimaryProvider("beta");
        start();
        controller.setSearchMode("playlist");
        controller.searchPlaylists("piano");
        settle();

        assertEquals(Arrays.asList("beta:playlist:1", "beta:playlist:2",
                "alpha:playlist:1", "alpha:playlist:2"), ids());
        assertEquals("beta", controller.searchPlaylistResults.peek().get(0).sourceName);
        assertTrue(controller.searchHasMore.peek());

        controller.loadMoreSearch();
        settle();
        assertEquals(Arrays.asList("beta:playlist:1", "beta:playlist:2", "beta:playlist:3",
                "alpha:playlist:1", "alpha:playlist:2", "alpha:playlist:3"), ids());
        assertEquals(Integer.valueOf(6), controller.resultCount.peek());
        assertFalse(controller.searchHasMore.peek());
    }

    @Test public void changingQueryOrModeDiscardsAlreadyQueuedPlaylistResponses() throws Exception {
        install("alpha");
        start();
        controller.setSearchMode("playlist");
        controller.searchPlaylists("old");
        barrier();
        controller.prepareSearch("new");
        controller.pump();
        assertTrue(ids().isEmpty());
        assertFalse(controller.searchHasMore.peek());

        controller.searchPlaylists("new");
        settle();
        assertEquals("new 1", controller.searchPlaylistResults.peek().get(0).name);
        controller.loadMoreSearch();
        barrier();
        controller.setSearchMode("song");
        controller.pump();
        assertTrue(ids().isEmpty());
        assertFalse(controller.searchLoading.peek());
        assertFalse(controller.searchHasMore.peek());
        assertEquals(Integer.valueOf(0), controller.resultCount.peek());
    }

    @Test public void aProviderCannotKeepPagingWithAnUnchangedCursor() throws Exception {
        install("alpha");
        start();
        controller.setSearchMode("playlist");
        controller.searchPlaylists("stuck");
        settle();
        assertTrue(controller.searchHasMore.peek());
        controller.loadMoreSearch();
        settle();
        assertEquals(Arrays.asList("alpha:playlist:1", "alpha:playlist:2", "alpha:playlist:3"), ids());
        assertFalse(controller.searchHasMore.peek());
    }

    @Test public void failedPagesDoNotTriggerAutomaticViewportRefill() throws Exception {
        install("alpha");
        start();
        controller.setSearchMode("playlist");
        controller.searchPlaylists("failure");
        settle();
        long completed = controller.searchPlaylistPageRevision.peek();
        controller.loadMoreSearch();
        settle();
        assertEquals(completed, controller.searchPlaylistPageRevision.peek().longValue());
        assertEquals(Arrays.asList("alpha:playlist:1", "alpha:playlist:2"), ids());
        assertTrue("The failed cursor remains available for an explicit scroll retry",
                controller.searchHasMore.peek());
    }

    private void start() throws Exception {
        AudioBackend backend = (AudioBackend) Proxy.newProxyInstance(
                AudioBackend.class.getClassLoader(), new Class<?>[]{AudioBackend.class},
                (proxy, method, args) -> {
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
        controller = new PlayerController(backend, track -> {});
        Field field = PlayerController.class.getDeclaredField("pluginManager");
        field.setAccessible(true);
        manager = (PluginManager) field.get(controller);
    }

    private void barrier() throws Exception {
        for (dev.t1m3.qplayer.plugin.PluginManifest provider : manager.enabledProviders()) {
            manager.invoke(provider.id, "barrier", Collections.emptyMap()).get(5, TimeUnit.SECONDS);
        }
    }

    private void settle() throws Exception {
        barrier();
        controller.pump();
        assertFalse(controller.searchLoading.peek());
    }

    private List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (Playlist playlist : controller.searchPlaylistResults.peek()) ids.add(playlist.id);
        return ids;
    }

    private void install(String id) throws Exception {
        PluginManifest manifest = new PluginManifest();
        manifest.schemaVersion = 1;
        manifest.id = id;
        manifest.name = id;
        manifest.version = "1.0.0";
        manifest.apiVersion = "1.0";
        manifest.capabilities.add("searchPlaylists");
        byte[] json = new Gson().toJson(manifest).getBytes(StandardCharsets.UTF_8);
        byte[] script = ("module.exports={handlers:{barrier:function(){return true;},"
                + "searchPlaylists:function(a){if(a.query==='failure'&&a.cursor)throw new Error('offline');"
                + "var ids=a.cursor?[2,3]:[1,2];return {"
                + "items:ids.map(function(id){return {id:String(id),name:a.query+' '+id};}),"
                + "nextCursor:!a.cursor||a.query==='stuck'?'next':''};}}};")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("plugin.json", hex(json));
        hashes.put("main.js", hex(script));
        Path archive = base.resolve(id + ".qplug");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            put(zip, "plugin.json", json);
            put(zip, "main.js", script);
            put(zip, PluginPackageVerifier.HASHES_PATH,
                    new Gson().toJson(hashes).getBytes(StandardCharsets.UTF_8));
        }
        new PluginInstaller(new PluginRegistry()).install(
                new PluginPackageVerifier().verify(archive, null, true), Collections.emptySet());
    }

    private static String hex(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        }
        return out.toString();
    }

    private static void put(ZipOutputStream zip, String name, byte[] body) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body);
        zip.closeEntry();
    }
}
