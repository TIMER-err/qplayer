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
}
