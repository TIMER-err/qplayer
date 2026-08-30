package dev.t1m3.qplayer.media;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Collision-free identity for media owned by a source plugin.
 *
 * <p>The wire form is {@code provider:kind:percent-encoded-native-id}. Plugins
 * return native ids; the host creates this value so a plugin cannot impersonate
 * another provider.
 */
public final class MediaId {
    private static final Pattern PROVIDER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final int MAX_NATIVE_ID_BYTES = 2048;

    private final String provider;
    private final MediaKind kind;
    private final String nativeId;

    private MediaId(String provider, MediaKind kind, String nativeId) {
        this.provider = provider;
        this.kind = kind;
        this.nativeId = nativeId;
    }

    public static MediaId of(String provider, MediaKind kind, String nativeId) {
        validateProvider(provider);
        if (kind == null) throw new IllegalArgumentException("kind == null");
        if (nativeId == null || nativeId.isEmpty()) {
            throw new IllegalArgumentException("native id is empty");
        }
        if (nativeId.getBytes(StandardCharsets.UTF_8).length > MAX_NATIVE_ID_BYTES) {
            throw new IllegalArgumentException("native id is too long");
        }
        for (int i = 0; i < nativeId.length(); i++) {
            char value = nativeId.charAt(i);
            if (Character.isISOControl(value)) {
                throw new IllegalArgumentException("native id contains control characters");
            }
        }
        return new MediaId(provider, kind, nativeId);
    }

    public static MediaId parse(String value) {
        if (value == null) throw new IllegalArgumentException("media id == null");
        int first = value.indexOf(':');
        int second = first < 0 ? -1 : value.indexOf(':', first + 1);
        if (first <= 0 || second <= first + 1 || second == value.length() - 1
                || value.indexOf(':', second + 1) >= 0) {
            throw new IllegalArgumentException("invalid media id: " + value);
        }
        String provider = value.substring(0, first);
        MediaKind kind = MediaKind.fromWireName(value.substring(first + 1, second));
        return of(provider, kind, decode(value.substring(second + 1)));
    }

    public static boolean isValidProvider(String provider) {
        return provider != null && PROVIDER.matcher(provider).matches();
    }

    public static void validateProvider(String provider) {
        if (!isValidProvider(provider)) {
            throw new IllegalArgumentException("invalid provider id: " + provider);
        }
    }

    public String provider() { return provider; }
    public MediaKind kind() { return kind; }
    public String nativeId() { return nativeId; }

    public MediaId requireKind(MediaKind expected) {
        if (kind != expected) {
            throw new IllegalArgumentException("expected " + expected.wireName()
                    + " id, got " + kind.wireName());
        }
        return this;
    }

    @Override public String toString() {
        return provider + ':' + kind.wireName() + ':' + encode(nativeId);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MediaId)) return false;
        MediaId that = (MediaId) other;
        return provider.equals(that.provider) && kind == that.kind && nativeId.equals(that.nativeId);
    }

    @Override public int hashCode() {
        return Objects.hash(provider, kind, nativeId);
    }

    static String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length);
        final char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte raw : bytes) {
            int b = raw & 0xff;
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                    || (b >= '0' && b <= '9') || b == '-' || b == '.' || b == '_' || b == '~') {
                out.append((char) b);
            } else {
                out.append('%').append(hex[b >>> 4]).append(hex[b & 0xf]);
            }
        }
        return out.toString();
    }

    static String decode(String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length();) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 >= value.length()) throw new IllegalArgumentException("truncated escape");
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) throw new IllegalArgumentException("invalid escape");
                out.write((hi << 4) | lo);
                i += 3;
            } else {
                if (c > 0x7f || !isUnreserved(c)) {
                    throw new IllegalArgumentException("native id must be percent encoded");
                }
                out.write((byte) c);
                i++;
            }
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray()));
            String result = decoded.toString();
            if (result.isEmpty()) throw new IllegalArgumentException("native id is empty");
            return result;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("native id is not valid UTF-8", e);
        }
    }

    private static boolean isUnreserved(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
    }
}
