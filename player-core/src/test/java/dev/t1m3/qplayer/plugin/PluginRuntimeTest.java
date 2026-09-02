package dev.t1m3.qplayer.plugin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PluginRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void executesHandlersWithoutJavaGlobals() throws Exception {
        Files.write(temporary.getRoot().toPath().resolve("main.js"), (
                "module.exports = { handlers: {\n"
                + " echo: function(arg) { return { value: arg.value, javaType: typeof java }; }\n"
                + "} };\n").getBytes(StandardCharsets.UTF_8));
        try (PluginRuntime runtime = PluginRuntime.start(
                temporary.getRoot().toPath(), manifest(), noOpHost())) {
            Object raw = runtime.invoke("echo", Collections.<String, Object>singletonMap("value", 12))
                    .get(2, TimeUnit.SECONDS);
            Map<?, ?> result = (Map<?, ?>) raw;
            assertEquals(12, ((Number) result.get("value")).intValue());
            assertEquals("undefined", result.get("javaType"));
        }
    }

    @Test
    public void bridgesHostFutureBackIntoPluginPromise() throws Exception {
        Files.write(temporary.getRoot().toPath().resolve("main.js"), (
                "module.exports = { handlers: {\n"
                + " load: function(arg) { return qplayer.call('storage.get', arg)"
                + ".then(function(value) { return { answer: value }; }); }\n"
                + "} };\n").getBytes(StandardCharsets.UTF_8));
        PluginHostApi host = (pluginId, method, arguments) -> CompletableFuture.completedFuture(42);
        try (PluginRuntime runtime = PluginRuntime.start(
                temporary.getRoot().toPath(), manifest(), host)) {
            Map<?, ?> result = (Map<?, ?>) runtime.invoke("load", Collections.emptyMap())
                    .get(2, TimeUnit.SECONDS);
            assertEquals(42, ((Number) result.get("answer")).intValue());
        }
    }

    @Test
    public void rejectsUndeclaredPrivilegedHostMethod() throws Exception {
        Files.write(temporary.getRoot().toPath().resolve("main.js"), (
                "module.exports = { handlers: {\n"
                + " load: function() { return qplayer.call('http.request', {}); }\n"
                + "} };\n").getBytes(StandardCharsets.UTF_8));
        try (PluginRuntime runtime = PluginRuntime.start(
                temporary.getRoot().toPath(), manifest(), noOpHost())) {
            try {
                runtime.invoke("load", Collections.emptyMap()).get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                assertFalse(String.valueOf(expected.getCause()).isEmpty());
                return;
            }
        }
        throw new AssertionError("undeclared network call should fail");
    }

    @Test
    public void hostListsAreRealJavascriptArrays() throws Exception {
        Files.write(temporary.getRoot().toPath().resolve("main.js"), (
                "module.exports = { handlers: {\n"
                + " map: function(arg) { return arg.values.map(function(v) { return v * 2; }); }\n"
                + "} };\n").getBytes(StandardCharsets.UTF_8));
        try (PluginRuntime runtime = PluginRuntime.start(
                temporary.getRoot().toPath(), manifest(), noOpHost())) {
            Object result = runtime.invoke("map", Collections.<String, Object>singletonMap(
                    "values", Arrays.asList(2, 3))).get(2, TimeUnit.SECONDS);
            java.util.List<?> values = (java.util.List<?>) result;
            assertEquals(4, ((Number) values.get(0)).intValue());
            assertEquals(6, ((Number) values.get(1)).intValue());
        }
    }

    @Test
    public void rejectsCyclicPluginResults() throws Exception {
        Files.write(temporary.getRoot().toPath().resolve("main.js"), (
                "module.exports = { handlers: {\n"
                + " cycle: function() { var value = {}; value.self = value; return value; }\n"
                + "} };\n").getBytes(StandardCharsets.UTF_8));
        try (PluginRuntime runtime = PluginRuntime.start(
                temporary.getRoot().toPath(), manifest(), noOpHost())) {
            try {
                runtime.invoke("cycle", Collections.emptyMap()).get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(String.valueOf(expected.getCause()).contains("cycle"));
                return;
            }
        }
        throw new AssertionError("cyclic result should fail");
    }

    @Test
    public void rejectsNonFinitePluginNumbers() throws Exception {
        Files.write(temporary.getRoot().toPath().resolve("main.js"), (
                "module.exports = { handlers: { bad: function() { return {value: NaN}; } } };\n")
                .getBytes(StandardCharsets.UTF_8));
        try (PluginRuntime runtime = PluginRuntime.start(
                temporary.getRoot().toPath(), manifest(), noOpHost())) {
            try {
                runtime.invoke("bad", Collections.emptyMap()).get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(String.valueOf(expected.getCause()).contains("non-finite"));
                return;
            }
        }
        throw new AssertionError("non-finite result should fail");
    }

    @Test
    public void acceptsThousandsOfSongLikeResults() throws Exception {
        Files.write(temporary.getRoot().toPath().resolve("main.js"), (
                "module.exports = { handlers: {\n"
                + " songs: function() {\n"
                + "   var out = [];\n"
                + "   for (var i = 0; i < 4000; i++) {\n"
                + "     out.push({id: String(i), title: 't' + i,\n"
                + "       artists: [{id: '1', name: 'a'}],\n"
                + "       album: {id: '2', name: 'b'}, durationMs: 1000, playable: true});\n"
                + "   }\n"
                + "   return {songs: out};\n"
                + " }\n"
                + "} };\n").getBytes(StandardCharsets.UTF_8));
        try (PluginRuntime runtime = PluginRuntime.start(
                temporary.getRoot().toPath(), manifest(), noOpHost())) {
            Map<?, ?> result = (Map<?, ?>) runtime.invoke("songs", Collections.emptyMap())
                    .get(30, TimeUnit.SECONDS);
            java.util.List<?> songs = (java.util.List<?>) result.get("songs");
            assertEquals(4000, songs.size());
            Map<?, ?> first = (Map<?, ?>) songs.get(0);
            assertEquals("0", first.get("id"));
        }
    }

    @Test
    public void rejectsAdvertisedCapabilitiesWithoutHandlers() throws Exception {
        Files.write(temporary.getRoot().toPath().resolve("main.js"),
                "module.exports = { handlers: {} };\n".getBytes(StandardCharsets.UTF_8));
        PluginManifest manifest = manifest();
        manifest.capabilities = Collections.singletonList("searchSongs");
        try {
            PluginRuntime.start(temporary.getRoot().toPath(), manifest, noOpHost());
        } catch (PluginExecutionException expected) {
            assertTrue(String.valueOf(expected.getMessage()).contains("failed to start"));
            return;
        }
        throw new AssertionError("missing advertised handler should fail startup");
    }

    private static PluginManifest manifest() {
        PluginManifest manifest = new PluginManifest();
        manifest.schemaVersion = 1;
        manifest.id = "test-source";
        manifest.name = "Test Source";
        manifest.version = "1.0.0";
        manifest.apiVersion = "1.0";
        manifest.entry = "main.js";
        manifest.capabilities = Collections.emptyList();
        return manifest;
    }

    private static PluginHostApi noOpHost() {
        return (pluginId, method, arguments) -> CompletableFuture.completedFuture(null);
    }
}
