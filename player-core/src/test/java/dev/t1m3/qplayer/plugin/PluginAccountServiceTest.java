package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * A login QR image comes straight from a plugin and goes straight into an
 * Image element — worth rejecting anything that is not what it claims to be
 * before it ever reaches the renderer.
 */
public class PluginAccountServiceTest {

    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    public void absentImageIsEmpty() {
        assertEquals("", PluginAccountService.qrImage(null));
        assertEquals("", PluginAccountService.qrImage(""));
    }

    @Test
    public void aValidPngRoundTrips() {
        byte[] png = new byte[PNG_SIGNATURE.length + 4];
        System.arraycopy(PNG_SIGNATURE, 0, png, 0, PNG_SIGNATURE.length);
        String encoded = b64(png);
        assertEquals(encoded, PluginAccountService.qrImage(encoded));
    }

    @Test
    public void malformedBase64IsRejected() {
        PluginExecutionException error = assertThrows(PluginExecutionException.class,
                () -> PluginAccountService.qrImage("not base64!!"));
        assertEquals("login QR image is not valid base64", error.getMessage());
    }

    @Test
    public void nonPngBytesAreRejected() {
        // A JPEG's opening bytes, not a PNG's.
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        PluginExecutionException error = assertThrows(PluginExecutionException.class,
                () -> PluginAccountService.qrImage(b64(jpeg)));
        assertEquals("login QR image must be a PNG", error.getMessage());
    }

    @Test
    public void tooShortToBePngIsRejected() {
        PluginExecutionException error = assertThrows(PluginExecutionException.class,
                () -> PluginAccountService.qrImage(b64(new byte[]{(byte) 0x89, 0x50})));
        assertEquals("login QR image must be a PNG", error.getMessage());
    }

    @Test
    public void oversizedImageIsRejected() {
        // Comfortably past MAX_QR_IMAGE_BASE64_CHARS without allocating a real PNG.
        StringBuilder huge = new StringBuilder(300_001);
        for (int i = 0; i < 300_001; i++) huge.append('A');
        PluginExecutionException error = assertThrows(PluginExecutionException.class,
                () -> PluginAccountService.qrImage(huge.toString()));
        assertEquals("login QR image is too large", error.getMessage());
    }
}
