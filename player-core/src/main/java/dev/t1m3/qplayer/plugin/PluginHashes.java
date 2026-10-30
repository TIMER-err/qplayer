package dev.t1m3.qplayer.plugin;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/** Internal wire-compatible digest/encoding helpers for plugin storage and packages. */
final class PluginHashes {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private PluginHashes() {}

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (GeneralSecurityException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = HEX[value >>> 4];
            result[i * 2 + 1] = HEX[value & 15];
        }
        return new String(result);
    }
}
