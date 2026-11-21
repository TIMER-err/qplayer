package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The host asks for gzip on a plugin's behalf and hands back the plain bytes. A
 * playlist page compresses better than 3:1, so this is most of the cost of
 * opening a large playlist.
 */
public class CorePluginHostApiCompressionTest {

    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(bytes)) {
            output.write(plain);
        }
        return bytes.toByteArray();
    }

    @Test
    public void compressedBodyIsDecoded() throws Exception {
        byte[] plain = "{\"songs\":[1,2,3]}".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(plain, CorePluginHostApi.gunzipLimited(gzip(plain), 1024 * 1024));
    }

    /** A response labelled gzip that is not gzip stays readable instead of
     *  failing the call. */
    @Test
    public void bodyThatIsNotGzipPassesThrough() throws Exception {
        byte[] plain = "{\"code\":0}".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(plain, CorePluginHostApi.gunzipLimited(plain, 1024 * 1024));
    }

    @Test
    public void emptyAndTinyBodiesAreLeftAlone() throws Exception {
        assertArrayEquals(new byte[0], CorePluginHostApi.gunzipLimited(new byte[0], 1024));
        assertArrayEquals(new byte[]{0x1F}, CorePluginHostApi.gunzipLimited(new byte[]{0x1F}, 1024));
    }

    /** The decompressed size is held to the same cap as a plain body, so a small
     *  archive cannot expand past it. */
    @Test
    public void expansionBeyondTheCapIsRejected() throws Exception {
        byte[] archive = gzip(new byte[512 * 1024]);
        assertTrue("zeroes must compress well enough to be a useful bomb",
                archive.length < 4096);
        IOException error = assertThrows(IOException.class,
                () -> CorePluginHostApi.gunzipLimited(archive, 64 * 1024));
        assertTrue(error.getMessage().contains("too large"));
    }

    /** A plugin that negotiates its own encoding keeps the raw bytes: the host
     *  must notice the header whatever case it was written in. */
    @Test
    public void ownAcceptEncodingIsDetectedWhateverTheCase() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept-encoding", "br");
        assertTrue(CorePluginHostApi.hasHeader(headers, "Accept-Encoding"));
        assertFalse(CorePluginHostApi.hasHeader(new LinkedHashMap<>(), "Accept-Encoding"));
        assertFalse(CorePluginHostApi.hasHeader(null, "Accept-Encoding"));
    }

    @Test
    public void unrelatedHeadersDoNotSuppressNegotiation() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "*/*");
        assertFalse(CorePluginHostApi.hasHeader(headers, "Accept-Encoding"));
        assertEquals(2, headers.size());
    }
}
