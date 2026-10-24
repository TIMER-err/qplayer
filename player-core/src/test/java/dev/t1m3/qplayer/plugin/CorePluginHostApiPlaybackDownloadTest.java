package dev.t1m3.qplayer.plugin;

import com.sun.net.httpserver.HttpServer;
import dev.t1m3.qplayer.store.CredentialCipher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CorePluginHostApiPlaybackDownloadTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void playbackDownloadFollowsAnUndeclaredCdnHop() throws Exception {
        byte[] body = "AUDIO".getBytes(StandardCharsets.UTF_8);
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer cdn;
        try {
            cdn = HttpServer.create(new InetSocketAddress("127.0.0.2", 0), 0);
        } catch (Exception error) {
            origin.stop(0);
            throw error;
        }
        origin.createContext("/start", exchange -> {
            exchange.getResponseHeaders().set("Location",
                    "http://127.0.0.2:" + cdn.getAddress().getPort() + "/song.mp3");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        cdn.createContext("/song.mp3", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        origin.start();
        cdn.start();
        Path target = temporary.getRoot().toPath().resolve("saved.mp3");
        String start = "http://127.0.0.1:" + origin.getAddress().getPort() + "/start";
        try (CorePluginHostApi api = new CorePluginHostApi(
                temporary.getRoot().toPath().resolve("plugin-data"),
                new PluginCredentialVault(
                        temporary.getRoot().toPath().resolve("credentials"),
                        new CredentialCipher(temporary.getRoot().toPath().resolve("master.key"))))) {
            api.register(granted());
            Boolean ok = api.downloadReturnedFile("fixture", start, Collections.emptyMap(),
                    target, 1024, 5_000, true).get(10, TimeUnit.SECONDS);
            assertTrue(ok);
            assertArrayEquals(body, Files.readAllBytes(target));

            Path rejected = temporary.getRoot().toPath().resolve("rejected.mp3");
            try {
                api.downloadReturnedFile("fixture", start, Collections.emptyMap(),
                        rejected, 1024, 5_000, false).get(10, TimeUnit.SECONDS);
                fail("strict grant should reject the undeclared hop");
            } catch (Exception error) {
                Throwable root = error;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                assertTrue(root.getMessage(),
                        root.getMessage().contains("network domain is not declared: 127.0.0.2"));
            }
        } finally {
            origin.stop(0);
            cdn.stop(0);
        }
    }

    private static PluginManifest granted() {
        PluginManifest manifest = new PluginManifest();
        manifest.schemaVersion = 1;
        manifest.id = "fixture";
        manifest.name = "Fixture";
        manifest.version = "1.0.0";
        manifest.apiVersion = "1.0";
        manifest.entry = "main.js";
        manifest.permissions = Arrays.asList("network", "clearTextNetwork", "localNetwork");
        manifest.networkDomains = Arrays.asList("127.0.0.1");
        return manifest;
    }
}
