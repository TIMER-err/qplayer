package dev.t1m3.qplayer.netease;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Netease "weapi" client-side request encryption.
 *
 * <p>The web client encrypts every POST body with a double-AES + RSA scheme:
 * <ol>
 *   <li>Stringify JSON request body.</li>
 *   <li>{@code AES-CBC(presetKey, IV)} the body → base64.</li>
 *   <li>Generate a random 16-char secret. {@code AES-CBC(secret, IV)} the
 *       previous base64 → final {@code params} (base64).</li>
 *   <li>RSA-encrypt the reversed secret with the well-known 1024-bit public
 *       key (no padding) → {@code encSecKey} (hex).</li>
 *   <li>POST {@code params} + {@code encSecKey} as form-urlencoded.</li>
 * </ol>
 *
 * <p>{@code presetKey}, {@code IV}, RSA {@code pubKey} and {@code modulus}
 * are public constants baked into the netease web bundle — they are not
 * secret, they protect against trivial replay only. Algorithm is identical
 * to {@code Binaryify/NeteaseCloudMusicApi}'s {@code crypto.js}.
 */
public final class NeteaseCrypto {

    private static final String PRESET_KEY = "0CoJUm6Qyw8W8jud";
    private static final String EAPI_KEY = "e82ckenh8dichen8";
    private static final String IV = "0102030405060708";
    private static final String RSA_PUB_KEY = "010001";
    private static final String RSA_MODULUS =
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725"
          + "152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312"
          + "ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424"
          + "d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";

    /** AES-256-ECB key the "xeapi" scheme wraps its inner ciphertext, session
     *  R-token and key-exchange public-key blob with. Hard-coded into the
     *  official Android client (ported from NeteaseCloudMusicApiEnhanced). */
    private static final byte[] XEAPI_STATIC_KEY = hexToBytes(
            "ab1d5a430f6bb04a3f01e81ddd72bd916d5ce591248ac128714806d7f8fb1b84");
    /** HMAC-SHA256 key signing the anti-crawler session-key registration. Used
     *  verbatim as UTF-8 bytes (it only looks like base64). */
    private static final String XEAPI_SIGN_KEY =
            "mUHCwVNWJbunMqAHf5MImuirT6plvs6VSFW62MGHstFQxhBGdEoIhLItH3djc4+FB/OKty3+lL2rGeoFBpVe5g==";

    private static final char[] BASE62 =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    private NeteaseCrypto() {}

    /**
     * Encrypt the JSON body into {@code params} + {@code encSecKey} ready
     * to POST as form fields to {@code /weapi/...}.
     */
    public static Map<String, String> weapi(String jsonText) throws Exception {
        String secret = randomString(16);
        String first = aesEncrypt(jsonText, PRESET_KEY);
        String params = aesEncrypt(first, secret);
        String encSecKey = rsaEncrypt(secret);
        Map<String, String> out = new HashMap<>();
        out.put("params", params);
        out.put("encSecKey", encSecKey);
        return out;
    }

    /**
     * Mobile-client "eapi" encryption (the one the official Android/iOS apps use).
     * The signed plaintext is
     * {@code <url>-36cd479b6b5-<json>-36cd479b6b5-md5("nobody"+url+"use"+json+"md5forencrypt")},
     * AES-ECB-encrypted with {@link #EAPI_KEY} and hex-encoded upper-case into a
     * single {@code params} form field. {@code url} is the {@code /api/...} signing
     * path — the request itself is POSTed to {@code /eapi/...}. Risk control is far
     * laxer on this path than on weapi, so sensitive ops (favoriting, subscribing)
     * succeed here where weapi trips "操作频繁" / 524.
     */
    public static Map<String, String> eapi(String url, String jsonText) throws Exception {
        String digest = md5Hex("nobody" + url + "use" + jsonText + "md5forencrypt");
        String data = url + "-36cd479b6b5-" + jsonText + "-36cd479b6b5-" + digest;
        Map<String, String> out = new HashMap<>();
        out.put("params", aesEcbHex(data, EAPI_KEY));
        return out;
    }

    // ------------------------------------------------------------------
    //  xeapi — the newest mobile scheme (interface3.music.163.com/xeapi/...)
    //
    //  The official Android app moved risk-controlled writes (playlist
    //  subscribe, ...) off eapi onto "xeapi". A request is three form fields:
    //    B = AES-128-ECB(dynKey, midTransform(AES-256-ECB(staticKey, plain)))
    //    S = ephPub ‖ iv ‖ AES-128-GCM(ecdhKey, iv, "<dynKeyB64>|<os>|<sk>")
    //    R = AES-256-ECB(staticKey, "<keyVersion>|<sessionId>")
    //  where (publicKey, version, sk) come from a one-off session-key
    //  registration (see NeteaseClient#xeapiSession). Ported byte-for-byte
    //  from NeteaseCloudMusicApiEnhanced (util/crypto.js).
    // ------------------------------------------------------------------

    /** {@code base64(HMAC-SHA256(XEAPI_SIGN_KEY, timestamp + nonce))} — signs the
     *  session-key registration request (and verifies its response). */
    public static String xeapiSign(String timestamp, String nonce) throws Exception {
        return Base64.getEncoder().encodeToString(
                hmacSha256(XEAPI_SIGN_KEY.getBytes(StandardCharsets.UTF_8),
                        (timestamp + nonce).getBytes(StandardCharsets.UTF_8)));
    }

    /** Decrypt the {@code encryptedData} blob from {@code /api/gorilla/anti/crawler
     *  /security/key/get} into its JSON {@code {publicKey, version, sk}}. */
    public static String xeapiDecryptPublicKey(String encryptedDataB64) throws Exception {
        byte[] pt = aesEcb(Cipher.DECRYPT_MODE, XEAPI_STATIC_KEY,
                Base64.getDecoder().decode(encryptedDataB64));
        return new String(pt, StandardCharsets.UTF_8);
    }

    /**
     * Encrypt a request into the {@code B}/{@code S}/{@code R} form fields.
     *
     * @param formBody  the URL-encoded request body (e.g. {@code "id=123456"})
     * @param peerPubB64 base64 X25519 public key from the registered session
     * @param keyVersion the registered key's {@code version}
     * @param sk        the registered session {@code sk}
     * @param os        client os string baked into S (always {@code "android"})
     */
    public static Map<String, String> xeapi(String formBody, String peerPubB64,
            String keyVersion, String sk, String os) throws Exception {
        byte[] dynKey = randomBytes(16);
        String plain = "{\"body\":\"" + base64(formBody.getBytes(StandardCharsets.UTF_8))
                + "\",\"queryString\":\"e_r=true\"}";

        byte[] inner = aesEcb(Cipher.ENCRYPT_MODE, XEAPI_STATIC_KEY,
                plain.getBytes(StandardCharsets.UTF_8));
        byte[] b = aesEcb(Cipher.ENCRYPT_MODE, dynKey, midTransform(inner, randomBytes(16)));
        byte[] s = encryptS(dynKey, peerPubB64, sk, os);
        byte[] r = aesEcb(Cipher.ENCRYPT_MODE, XEAPI_STATIC_KEY,
                (keyVersion + "|").getBytes(StandardCharsets.UTF_8));

        Map<String, String> out = new HashMap<>();
        out.put("B", base64(b));
        out.put("S", base64(s));
        out.put("R", base64(r));
        return out;
    }

    /** Decrypt an xeapi response body: {@code AES-128-ECB(eapiKey)} then gunzip
     *  if the plaintext is gzip-framed. Returns the JSON text. */
    public static String xeapiResDecrypt(byte[] body) throws Exception {
        byte[] dec = aesEcb(Cipher.DECRYPT_MODE,
                EAPI_KEY.getBytes(StandardCharsets.UTF_8), body);
        if (dec.length >= 2 && (dec[0] & 0xff) == 0x1f && (dec[1] & 0xff) == 0x8b) {
            dec = gunzip(dec);
        }
        return new String(dec, StandardCharsets.UTF_8);
    }

    // ---- xeapi internals ----

    /** {@code S} = X25519 ECDH → HKDF → AES-128-GCM over "<dynKeyB64>|<os>|<sk>",
     *  framed as {@code ephemeralPublicKey ‖ iv ‖ ciphertext ‖ tag}. */
    private static byte[] encryptS(byte[] dynKey, String peerPubB64, String sk, String os)
            throws Exception {
        byte[] peerRaw = Base64.getDecoder().decode(peerPubB64);
        byte[] ephPriv = randomBytes(32);
        byte[] ephPub = X25519.scalarMultBase(ephPriv);
        byte[] shared = X25519.scalarMult(ephPriv, peerRaw);
        byte[] aesKey = deriveX25519AesKey(shared, ephPub);

        byte[] iv = randomBytes(12);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                new GCMParameterSpec(128, iv));
        byte[] ctTag = gcm.doFinal(
                (base64(dynKey) + "|" + os + "|" + (sk == null ? "" : sk))
                        .getBytes(StandardCharsets.UTF_8));
        return concat(ephPub, iv, ctTag);
    }

    /** HKDF(SHA-256) with a zero salt: extract over the shared secret, expand
     *  with the ephemeral public key as info, take the first 16 bytes. */
    static byte[] deriveX25519AesKey(byte[] shared, byte[] ephPub) throws Exception {
        byte[] prk = hmacSha256(new byte[32], shared.length > 0 ? shared : new byte[32]);
        byte[] okm = hmacSha256(prk, concat(ephPub, new byte[]{1}));
        byte[] key = new byte[16];
        System.arraycopy(okm, 0, key, 0, 16);
        return key;
    }

    /** XOR the ciphertext under a random 16-byte pad, base64 it, then rotate the
     *  base64 left by {@code rnd[0] & 0xf} and prepend the pad. Reversible
     *  obfuscation the server peels back before the outer AES. {@code rnd} is a
     *  parameter (not generated inline) so it can be pinned in tests. */
    static byte[] midTransform(byte[] ct, byte[] rnd) {
        byte[] xored = new byte[ct.length];
        for (int i = 0; i < ct.length; i++) xored[i] = (byte) (ct[i] ^ rnd[i & 0x0f]);
        byte[] b64 = Base64.getEncoder().encode(xored);
        int rot = b64.length == 0 ? 0 : (rnd[0] & 0x0f) % b64.length;
        byte[] out = new byte[16 + b64.length];
        System.arraycopy(rnd, 0, out, 0, 16);
        System.arraycopy(b64, rot, out, 16, b64.length - rot);
        System.arraycopy(b64, 0, out, 16 + b64.length - rot, rot);
        return out;
    }

    static byte[] aesEcb(int mode, byte[] key, byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(data);
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] gunzip(byte[] gz) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gz))) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    private static String base64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    | Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    private static String aesEcbHex(String data, String key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
        byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return toHex(encrypted).toUpperCase(Locale.ROOT);
    }

    private static String md5Hex(String s) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        return toHex(digest);
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16));
            sb.append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }

    private static String aesEncrypt(String data, String key) throws Exception {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] ivBytes = IV.getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(keyBytes, "AES"),
                new IvParameterSpec(ivBytes));
        byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    /**
     * Raw RSA (no padding) over the *reversed* secret, then hex-encoded
     * left-padded to 256 chars. This matches the JS reference
     * implementation; standard RSA libraries can't do this (they all
     * apply PKCS#1 / OAEP padding), so we do modPow by hand with
     * {@link BigInteger}.
     */
    private static String rsaEncrypt(String secret) {
        String reversed = new StringBuilder(secret).reverse().toString();
        BigInteger text = new BigInteger(1, reversed.getBytes(StandardCharsets.UTF_8));
        BigInteger pubKey = new BigInteger(RSA_PUB_KEY, 16);
        BigInteger modulus = new BigInteger(RSA_MODULUS, 16);
        BigInteger result = text.modPow(pubKey, modulus);
        String hex = result.toString(16);
        if (hex.length() >= 256) return hex;
        StringBuilder pad = new StringBuilder(256);
        for (int i = hex.length(); i < 256; i++) pad.append('0');
        pad.append(hex);
        return pad.toString();
    }

    private static String randomString(int len) {
        char[] out = new char[len];
        for (int i = 0; i < len; i++) out[i] = BASE62[RNG.nextInt(BASE62.length)];
        return new String(out);
    }
}
