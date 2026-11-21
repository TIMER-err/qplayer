package dev.t1m3.qplayer.plugin;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Provider-neutral cryptographic primitives for protocols that cannot use WebCrypto
 * inside Rhino. Algorithms and input sizes are deliberately allow-listed. */
final class PluginCryptoServices {
    private static final int MAX_INPUT = 8 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BigInteger X25519_P = BigInteger.valueOf(2).pow(255)
            .subtract(BigInteger.valueOf(19));
    private static final BigInteger X25519_A24 = BigInteger.valueOf(121665);

    private PluginCryptoServices() {}

    static Object call(String method, Map<String, Object> arguments) throws Exception {
        switch (method) {
            case "crypto.sha256":
                return hex(MessageDigest.getInstance("SHA-256").digest(
                        string(arguments, "value").getBytes(StandardCharsets.UTF_8)));
            case "crypto.digest": return digest(arguments);
            case "crypto.random": return random(arguments);
            case "crypto.aes": return aes(arguments);
            case "crypto.hmac": return hmac(arguments);
            case "crypto.modPow": return modPow(arguments);
            case "crypto.x25519": return x25519(arguments);
            case "compression.gunzip": return gunzip(arguments);
            default: throw new UnsupportedOperationException("unknown crypto method " + method);
        }
    }

    private static String digest(Map<String, Object> args) throws Exception {
        String algorithm = string(args, "algorithm").toUpperCase(Locale.ROOT);
        if (!("SHA-256".equals(algorithm) || "SHA-1".equals(algorithm) || "MD5".equals(algorithm))) {
            throw new IllegalArgumentException("digest algorithm is not allowed");
        }
        return encode(MessageDigest.getInstance(algorithm).digest(data(args, "data")), output(args));
    }

    private static String random(Map<String, Object> args) {
        int length = number(args.get("length"), 16);
        if (length < 1 || length > 1024) throw new IllegalArgumentException("invalid random length");
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return encode(bytes, output(args));
    }

    private static String aes(Map<String, Object> args) throws Exception {
        String transformation = string(args, "transformation");
        if (!("AES/CBC/PKCS5Padding".equals(transformation)
                || "AES/ECB/PKCS5Padding".equals(transformation)
                || "AES/GCM/NoPadding".equals(transformation))) {
            throw new IllegalArgumentException("AES transformation is not allowed");
        }
        byte[] key = material(args, "key");
        if (!(key.length == 16 || key.length == 24 || key.length == 32)) {
            throw new IllegalArgumentException("invalid AES key length");
        }
        boolean encrypt = !"decrypt".equalsIgnoreCase(optional(args, "operation", "encrypt"));
        Cipher cipher = Cipher.getInstance(transformation);
        SecretKeySpec secret = new SecretKeySpec(key, "AES");
        if (transformation.contains("/ECB/")) {
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, secret);
        } else {
            byte[] iv = material(args, "iv");
            if (transformation.contains("/GCM/")) {
                if (iv.length != 12) throw new IllegalArgumentException("GCM IV must be 12 bytes");
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, secret,
                        new GCMParameterSpec(128, iv));
                if (args.containsKey("aad")) cipher.updateAAD(material(args, "aad"));
            } else {
                if (iv.length != 16) throw new IllegalArgumentException("CBC IV must be 16 bytes");
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, secret,
                        new IvParameterSpec(iv));
            }
        }
        return encode(cipher.doFinal(data(args, "data")), output(args));
    }

    private static String hmac(Map<String, Object> args) throws Exception {
        String algorithm = string(args, "algorithm");
        if (!("HmacSHA256".equals(algorithm) || "HmacSHA1".equals(algorithm))) {
            throw new IllegalArgumentException("HMAC algorithm is not allowed");
        }
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(material(args, "key"), algorithm));
        return encode(mac.doFinal(data(args, "data")), output(args));
    }

    private static String modPow(Map<String, Object> args) {
        BigInteger base = new BigInteger(string(args, "baseHex"), 16);
        BigInteger exponent = new BigInteger(string(args, "exponentHex"), 16);
        BigInteger modulus = new BigInteger(string(args, "modulusHex"), 16);
        if (base.bitLength() > 16_384 || exponent.bitLength() > 4_096
                || modulus.bitLength() < 2 || modulus.bitLength() > 16_384) {
            throw new IllegalArgumentException("modPow operand is too large");
        }
        String result = base.modPow(exponent, modulus).toString(16);
        int width = number(args.get("width"), 0);
        if (width < 0 || width > 32_768) throw new IllegalArgumentException("invalid output width");
        if (result.length() < width) {
            StringBuilder padded = new StringBuilder(width);
            for (int i = result.length(); i < width; i++) padded.append('0');
            padded.append(result);
            result = padded.toString();
        }
        return result;
    }

    private static String x25519(Map<String, Object> args) {
        byte[] scalar = material(args, "scalar");
        byte[] point;
        if (args.containsKey("point")) point = material(args, "point");
        else { point = new byte[32]; point[0] = 9; }
        if (scalar.length != 32 || point.length != 32) {
            throw new IllegalArgumentException("X25519 inputs must be 32 bytes");
        }
        return encode(x25519ScalarMult(scalar, point), output(args));
    }

    private static String gunzip(Map<String, Object> args) throws Exception {
        byte[] compressed = data(args, "data");
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > MAX_INPUT) throw new IllegalArgumentException("output too large");
                output.write(buffer, 0, count);
            }
            return encode(output.toByteArray(), output(args));
        }
    }

    private static byte[] x25519ScalarMult(byte[] scalar, byte[] point) {
        byte[] k = scalar.clone();
        k[0] &= (byte) 248;
        k[31] &= (byte) 127;
        k[31] |= (byte) 64;
        byte[] u = point.clone();
        u[31] &= (byte) 127;
        BigInteger kk = littleEndian(k);
        BigInteger x1 = littleEndian(u);
        BigInteger x2 = BigInteger.ONE, z2 = BigInteger.ZERO;
        BigInteger x3 = x1, z3 = BigInteger.ONE;
        int swap = 0;
        for (int bit = 254; bit >= 0; bit--) {
            int value = kk.testBit(bit) ? 1 : 0;
            swap ^= value;
            if (swap == 1) {
                BigInteger tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            swap = value;
            BigInteger a = x2.add(z2).mod(X25519_P);
            BigInteger aa = a.multiply(a).mod(X25519_P);
            BigInteger b = x2.subtract(z2).mod(X25519_P);
            BigInteger bb = b.multiply(b).mod(X25519_P);
            BigInteger e = aa.subtract(bb).mod(X25519_P);
            BigInteger c = x3.add(z3).mod(X25519_P);
            BigInteger d = x3.subtract(z3).mod(X25519_P);
            BigInteger da = d.multiply(a).mod(X25519_P);
            BigInteger cb = c.multiply(b).mod(X25519_P);
            x3 = da.add(cb).mod(X25519_P).pow(2).mod(X25519_P);
            z3 = x1.multiply(da.subtract(cb).mod(X25519_P).pow(2).mod(X25519_P)).mod(X25519_P);
            x2 = aa.multiply(bb).mod(X25519_P);
            z2 = e.multiply(aa.add(X25519_A24.multiply(e)).mod(X25519_P)).mod(X25519_P);
        }
        if (swap == 1) {
            BigInteger tmp = x2; x2 = x3; x3 = tmp;
            tmp = z2; z2 = z3; z3 = tmp;
        }
        return toLittleEndian(x2.multiply(z2.modPow(X25519_P.subtract(BigInteger.valueOf(2)),
                X25519_P)).mod(X25519_P));
    }

    private static byte[] data(Map<String, Object> args, String key) {
        byte[] value = decode(string(args, key), optional(args, key + "Encoding", "utf8"));
        if (value.length > MAX_INPUT) throw new IllegalArgumentException("crypto input is too large");
        return value;
    }

    private static byte[] material(Map<String, Object> args, String key) {
        return decode(string(args, key), optional(args, key + "Encoding", "base64"));
    }

    private static byte[] decode(String value, String encoding) {
        switch (encoding.toLowerCase(Locale.ROOT)) {
            case "utf8": return value.getBytes(StandardCharsets.UTF_8);
            case "base64": return Base64.getDecoder().decode(value);
            case "hex":
                if ((value.length() & 1) != 0 || !value.matches("[0-9A-Fa-f]*")) {
                    throw new IllegalArgumentException("invalid hex input");
                }
                byte[] bytes = new byte[value.length() / 2];
                for (int i = 0; i < value.length(); i += 2) {
                    bytes[i / 2] = (byte) Integer.parseInt(value.substring(i, i + 2), 16);
                }
                return bytes;
            default: throw new IllegalArgumentException("unknown encoding " + encoding);
        }
    }

    private static String encode(byte[] value, String encoding) {
        switch (encoding.toLowerCase(Locale.ROOT)) {
            case "utf8": return new String(value, StandardCharsets.UTF_8);
            case "base64": return Base64.getEncoder().encodeToString(value);
            case "hex": return hex(value);
            default: throw new IllegalArgumentException("unknown output encoding " + encoding);
        }
    }

    private static String output(Map<String, Object> args) {
        return optional(args, "outputEncoding", "base64");
    }

    private static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("missing string " + key);
        return (String) value;
    }

    private static String optional(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        return value instanceof String && !((String) value).isEmpty() ? (String) value : fallback;
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private static BigInteger littleEndian(byte[] bytes) {
        byte[] reversed = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) reversed[i] = bytes[bytes.length - 1 - i];
        return new BigInteger(1, reversed);
    }

    private static byte[] toLittleEndian(BigInteger value) {
        byte[] big = value.toByteArray();
        byte[] output = new byte[32];
        for (int i = 0; i < big.length && i < output.length; i++) {
            output[i] = big[big.length - 1 - i];
        }
        return output;
    }
}
