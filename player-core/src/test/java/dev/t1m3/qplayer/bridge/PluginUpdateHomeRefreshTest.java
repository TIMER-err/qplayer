package dev.t1m3.qplayer.bridge;

import com.google.gson.Gson;
import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.media.Playlist;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.plugin.PluginManifest;
import dev.t1m3.qplayer.plugin.PluginPackageVerifier;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Installing a newer build of the already-primary source must republish home
 *  content without an app restart (issue #34). */
public class PluginUpdateHomeRefreshTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void updatingThePrimarySourceRepublishesHome() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("plugin-update-home").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            controller = new PlayerController(
                    new SilentAudioBackend(), track -> { }, NeteaseClient.INSTANCE);

            install(controller, packageFile("fixture-1.qplug", "1.0.0", "V1"), "1.0.0");
            assertEquals("first install publishes home", "V1", awaitPlaylistName(controller, "V1"));

            install(controller, packageFile("fixture-2.qplug", "1.0.1", "V2"), "1.0.1");
            assertEquals("update republishes home without restart",
                    "V2", awaitPlaylistName(controller, "V2"));
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    private static String awaitPlaylistName(PlayerController controller, String expected)
            throws Exception {
        long deadline = System.currentTimeMillis() + 10000L;
        String seen = "<none>";
        while (System.currentTimeMillis() < deadline) {
            controller.pump();
            List<Playlist> published = controller.sourceRecommendPlaylists.peek();
            if (published != null && !published.isEmpty()) {
                seen = published.get(0).name;
                if (expected.equals(seen)) return seen;
            }
            Thread.sleep(20L);
        }
        return seen;
    }

    /** Drive the real QML-facing install path: inspect, then confirm. */
    private static void install(PlayerController controller, Path archive, String version)
            throws Exception {
        controller.inspectPluginPackage(archive.toString());
        awaitUi(controller, () -> version.equals(controller.pendingPluginVersion.peek()));
        controller.confirmPendingPluginInstall();
        // confirm flips the busy flag off on the ui queue once the swap is done.
        awaitUi(controller, () -> Boolean.FALSE.equals(controller.pluginInstallBusy.peek()));
    }

    private interface Condition { boolean ready(); }

    private static void awaitUi(PlayerController controller, Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 15000L;
        while (System.currentTimeMillis() < deadline) {
            controller.pump();
            if (condition.ready()) return;
            Thread.sleep(20L);
        }
        fail("timed out waiting for the install step");
    }

    private Path packageFile(String name, String version, String playlistName) throws Exception {
        PluginManifest manifest = new PluginManifest();
        manifest.schemaVersion = 1;
        manifest.id = "fixture";
        manifest.name = "Fixture";
        manifest.version = version;
        manifest.apiVersion = "1.0";
        manifest.capabilities.add("home");
        byte[] manifestBytes = new Gson().toJson(manifest).getBytes(StandardCharsets.UTF_8);
        byte[] scriptBytes = ("module.exports = { handlers: {\n"
                + " home: function() { return { songs: [], sections: [],"
                + " playlists: [ { id: 'p1', name: '" + playlistName + "' } ] }; }\n"
                + "} };\n").getBytes(StandardCharsets.UTF_8);
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("plugin.json", hex(MessageDigest.getInstance("SHA-256").digest(manifestBytes)));
        hashes.put("main.js", hex(MessageDigest.getInstance("SHA-256").digest(scriptBytes)));
        byte[] hashesBytes = new Gson().toJson(hashes).getBytes(StandardCharsets.UTF_8);

        Path archive = temporaryFolder.getRoot().toPath().resolve(name);
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            put(zip, "plugin.json", manifestBytes);
            put(zip, "main.js", scriptBytes);
            put(zip, PluginPackageVerifier.HASHES_PATH, hashesBytes);
        }
        return archive;
    }

    private static void put(ZipOutputStream zip, String name, byte[] body) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body);
        zip.closeEntry();
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte item : value) out.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return out.toString();
    }

    private static final class SilentAudioBackend implements AudioBackend {
        @Override public void play(String source, long startMs) { }
        @Override public void pause() { }
        @Override public void resume() { }
        @Override public boolean isPlaying() { return false; }
        @Override public void seek(long ms) { }
        @Override public long position() { return 0L; }
        @Override public long duration() { return 0L; }
        @Override public void setVolume(float volume) { }
        @Override public void setOnComplete(Runnable callback) { }
        @Override public void setOnStarted(Runnable callback) { }
        @Override public void release() { }
    }
}
