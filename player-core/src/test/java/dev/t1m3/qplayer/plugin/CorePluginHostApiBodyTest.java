package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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

    /** The regression the base64 path exists to prevent. */
    @Test
    public void textPathWouldHaveCorruptedBinary() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        byte[] throughText = new String(jpeg, StandardCharsets.UTF_8)
                .getBytes(StandardCharsets.UTF_8);
        assertTrue("UTF-8 round trip must not preserve these bytes, else the "
                        + "base64 path would be unnecessary",
                throughText.length != jpeg.length);
    }

    @Test
    public void bothFormsAtOnceIsRejected() {
        Map<String, Object> both = args("body", "text");
        both.put("bodyBase64", Base64.getEncoder().encodeToString(new byte[]{1}));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CorePluginHostApi.requestBody(both));
        assertTrue(error.getMessage().contains("not both"));
    }

    @Test
    public void malformedBase64IsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CorePluginHostApi.requestBody(args("bodyBase64", "not base64!!")));
        assertEquals("bodyBase64 is not valid base64", error.getMessage());
    }

    @Test
    public void oversizedBinaryBodyIsRejected() {
        byte[] huge = new byte[17 * 1024 * 1024];
        Map<String, Object> map = args("bodyBase64", Base64.getEncoder().encodeToString(huge));
        IOException error = assertThrows(IOException.class,
                () -> CorePluginHostApi.requestBody(map));
        assertTrue(error.getMessage().contains("too large"));
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
