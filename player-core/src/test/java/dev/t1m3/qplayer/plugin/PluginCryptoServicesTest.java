package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public final class PluginCryptoServicesTest {
    @Test public void x25519MatchesRfc7748Vector() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("scalar", "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        args.put("scalarEncoding", "hex");
        args.put("outputEncoding", "hex");
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
                PluginCryptoServices.call("crypto.x25519", args));
    }

    @Test public void aesCbcCanRoundTripUtf8() throws Exception {
        Map<String, Object> encrypt = new LinkedHashMap<>();
        encrypt.put("transformation", "AES/CBC/PKCS5Padding");
        encrypt.put("key", "30313233343536373839616263646566");
        encrypt.put("keyEncoding", "hex");
        encrypt.put("iv", "30313032303330343035303630373038");
        encrypt.put("ivEncoding", "hex");
        encrypt.put("data", "插件协议");
        String ciphertext = (String) PluginCryptoServices.call("crypto.aes", encrypt);

        Map<String, Object> decrypt = new LinkedHashMap<>(encrypt);
        decrypt.put("operation", "decrypt");
        decrypt.put("data", ciphertext);
        decrypt.put("dataEncoding", "base64");
        decrypt.put("outputEncoding", "utf8");
        assertEquals("插件协议", PluginCryptoServices.call("crypto.aes", decrypt));
    }
}
