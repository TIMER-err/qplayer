package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * A plugin uploading artwork needs to put raw bytes on the wire. The text body
 * cannot carry them, which is the whole reason bodyBase64 exists.
 */
public class CorePluginHostApiBodyTest {

    private static Map<String, Object> args(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    @Test
    public void absentBodyIsNull() throws Exception {
        assertNull(CorePluginHostApi.requestBody(new LinkedHashMap<>()));
    }

    @Test
    public void textBodyIsSentAsUtf8() throws Exception {
        assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                CorePluginHostApi.requestBody(args("body", "{\"a\":1}")));
    }

    @Test
    public void base64BodyRoundTripsExactBytes() throws Exception {
        // A JPEG's opening bytes: FF D8 FF E0 is not valid UTF-8.
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
        byte[] sent = CorePluginHostApi.requestBody(
                args("bodyBase64", Base64.getEncoder().encodeToString(jpeg)));
        assertArrayEquals(jpeg, sent);
    }


    @Test
    public void bothFormsAtOnceIsRejected() {
        Map<String, Object> both = args("body", "text");
        both.put("bodyBase64", Base64.getEncoder().encodeToString(new byte[]{1}));
        assertThrows(IllegalArgumentException.class,
                () -> CorePluginHostApi.requestBody(both));
    }

    @Test
    public void malformedBase64IsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CorePluginHostApi.requestBody(args("bodyBase64", "not base64!!")));
    }

    @Test
    public void oversizedBinaryBodyIsRejected() {
        byte[] huge = new byte[17 * 1024 * 1024];
        Map<String, Object> map = args("bodyBase64", Base64.getEncoder().encodeToString(huge));
        assertThrows(IOException.class, () -> CorePluginHostApi.requestBody(map));
    }

    @Test
    public void multipartCarriesTheFileBytesUnchanged() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("field", "data");
        file.put("filename", "cover.jpg");
        file.put("mimeType", "image/jpeg");
        file.put("bodyBase64", Base64.getEncoder().encodeToString(jpeg));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("uin", "123");
        Map<String, Object> multipart = new LinkedHashMap<>();
        multipart.put("fields", fields);
        multipart.put("file", file);
        CorePluginHostApi.Outgoing outgoing = CorePluginHostApi.outgoing(args("multipart", multipart));
        assertTrue(outgoing.contentType.startsWith("multipart/form-data; boundary="));
        String wire = new String(outgoing.body, StandardCharsets.ISO_8859_1);
        assertTrue(wire.contains("name=\"uin\""));
        assertTrue(wire.contains("123"));
        assertTrue(wire.contains("filename=\"cover.jpg\""));
        assertTrue(wire.contains("Content-Type: image/jpeg"));
        int fileAt = indexOf(outgoing.body, jpeg);
        assertTrue("the JPEG bytes must appear verbatim in the form", fileAt >= 0);
    }

    @Test
    public void multipartWithoutHeadersSendsARealRequest() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicReference<String> contentType =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> received =
                new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/upload", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytes;
            while ((bytes = exchange.getRequestBody().read(buffer)) >= 0) {
                body.write(buffer, 0, bytes);
            }
            received.set(new String(body.toByteArray(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        try (CorePluginHostApi api = new CorePluginHostApi()) {
            PluginManifest manifest = new PluginManifest();
            manifest.schemaVersion = 1;
            manifest.id = "upload-fixture";
            manifest.name = "Upload fixture";
            manifest.version = "1.0.0";
            manifest.apiVersion = "1.0";
            manifest.permissions = java.util.Arrays.asList(PluginPermission.NETWORK.wireName(),
                    PluginPermission.CLEAR_TEXT_NETWORK.wireName(), PluginPermission.LOCAL_NETWORK.wireName());
            manifest.networkDomains = java.util.Collections.singletonList("127.0.0.1");
            api.register(manifest);
            Map<String, Object> request = args("url",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/upload");
            request.put("method", "POST");
            request.put("multipart", args("fields", args("name", "fixture")));
            Map<?, ?> response = (Map<?, ?>) api.call(manifest.id, "http.request", request)
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            org.junit.Assert.assertEquals(200, ((Number) response.get("status")).intValue());
            assertTrue(contentType.get().startsWith("multipart/form-data; boundary="));
            assertTrue(received.get().contains("name=\"name\""));
            assertTrue(received.get().contains("fixture"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void multipartAndBodyTogetherAreRejected() {
        Map<String, Object> both = args("body", "text");
        both.put("multipart", new LinkedHashMap<String, Object>());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CorePluginHostApi.outgoing(both));
        assertTrue(error.getMessage().contains("not both"));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
