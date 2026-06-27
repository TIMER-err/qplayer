package dev.t1m3.qplayer.library;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Extracts embedded unsynced lyrics from a local audio stream — the text that
 * Android's {@code MediaMetadataRetriever} can't reach (it has no lyrics API).
 *
 * <p>Handles the two formats that actually carry lyrics in the wild for local
 * libraries: MP3 {@code ID3v2 USLT} frames and FLAC {@code VORBIS_COMMENT}
 * {@code LYRICS}/{@code UNSYNCEDLYRICS} fields. Both keep their metadata at the
 * front of the file, so a bounded read off the head is enough; MP4/OGG (whose
 * metadata can live at the tail or across pages) are out of scope.
 */
public final class EmbeddedLyrics {

    /** Cap the head we buffer — tags live up front; this bounds memory/time per file. */
    private static final int MAX_HEAD = 4 * 1024 * 1024;

    private EmbeddedLyrics() {}

    /** Returns the embedded lyric text, or null if none / unsupported. Never throws. */
    public static String extract(InputStream in) {
        try {
            byte[] head = readHead(in);
            if (head.length >= 3 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                return fromId3(head);
            }
            if (head.length >= 4 && head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
                return fromFlac(head);
            }
        } catch (Throwable ignored) {
            // Malformed / truncated tag — treat as "no embedded lyrics".
        }
        return null;
    }

    private static byte[] readHead(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while (buf.size() < MAX_HEAD && (n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, Math.min(n, MAX_HEAD - buf.size()));
        }
        return buf.toByteArray();
    }

    // ---- ID3v2 (MP3) ----

    private static String fromId3(byte[] b) {
        int major = b[3] & 0xff;
        int tagSize = synchsafe(b, 6);
        int end = Math.min(b.length, 10 + tagSize);
        int pos = 10;
        // ID3v2.2 uses 3-char frame ids + 3-byte sizes; v2.3/v2.4 use 4 + 4.
        if (major == 2) {
            return scanFramesV22(b, pos, end);
        }
        boolean synchsafeFrames = major >= 4;
        while (pos + 10 <= end) {
            String id = new String(b, pos, 4, StandardCharsets.ISO_8859_1);
            if (id.charAt(0) == 0) break;
            int size = synchsafeFrames ? synchsafe(b, pos + 4) : be32(b, pos + 4);
            int dataStart = pos + 10;
            if (size <= 0 || dataStart + size > b.length) break;
            if (id.equals("USLT")) {
                return decodeUslt(b, dataStart, size);
            }
            pos = dataStart + size;
        }
        return null;
    }

    private static String scanFramesV22(byte[] b, int pos, int end) {
        while (pos + 6 <= end) {
            String id = new String(b, pos, 3, StandardCharsets.ISO_8859_1);
            if (id.charAt(0) == 0) break;
            int size = ((b[pos + 3] & 0xff) << 16) | ((b[pos + 4] & 0xff) << 8) | (b[pos + 5] & 0xff);
            int dataStart = pos + 6;
            if (size == 0 || dataStart + size > b.length) break;
            if (id.equals("ULT")) {
                return decodeUslt(b, dataStart, size);
            }
            pos = dataStart + size;
        }
        return null;
    }

    /** USLT data: encoding(1) + language(3) + content-descriptor(\0) + text. */
    private static String decodeUslt(byte[] b, int start, int size) {
        int enc = b[start] & 0xff;
        Charset cs = enc == 1 ? StandardCharsets.UTF_16
                : enc == 2 ? StandardCharsets.UTF_16BE
                : enc == 3 ? StandardCharsets.UTF_8
                : StandardCharsets.ISO_8859_1;
        int p = start + 4; // skip encoding + 3-byte language
        int textStart = skipDescriptor(b, p, start + size, enc);
        if (textStart < 0 || textStart >= start + size) return null;
        String text = new String(b, textStart, start + size - textStart, cs).trim();
        return text.isEmpty() ? null : text;
    }

    /** Advance past the null-terminated descriptor; UTF-16 terminators are 2 bytes. */
    private static int skipDescriptor(byte[] b, int p, int end, int enc) {
        boolean wide = enc == 1 || enc == 2;
        while (p < end) {
            if (wide) {
                if (p + 1 < end && b[p] == 0 && b[p + 1] == 0) return p + 2;
                p += 2;
            } else {
                if (b[p] == 0) return p + 1;
                p++;
            }
        }
        return -1;
    }

    private static int synchsafe(byte[] b, int off) {
        return ((b[off] & 0x7f) << 21) | ((b[off + 1] & 0x7f) << 14)
                | ((b[off + 2] & 0x7f) << 7) | (b[off + 3] & 0x7f);
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    // ---- FLAC ----

    private static String fromFlac(byte[] b) {
        int pos = 4; // skip "fLaC"
        while (pos + 4 <= b.length) {
            int header = b[pos] & 0xff;
            boolean last = (header & 0x80) != 0;
            int type = header & 0x7f;
            int len = ((b[pos + 1] & 0xff) << 16) | ((b[pos + 2] & 0xff) << 8) | (b[pos + 3] & 0xff);
            int blockStart = pos + 4;
            if (blockStart + len > b.length) break;
            if (type == 4) { // VORBIS_COMMENT
                String l = fromVorbisComment(b, blockStart, len);
                if (l != null) return l;
            }
            if (last) break;
            pos = blockStart + len;
        }
        return null;
    }

    /** Vorbis comment block: vendor + count + "KEY=value" entries, all little-endian lengths. */
    private static String fromVorbisComment(byte[] b, int start, int len) {
        int p = start;
        int end = start + len;
        int vendorLen = le32(b, p); p += 4 + vendorLen;
        if (p + 4 > end) return null;
        int count = le32(b, p); p += 4;
        for (int i = 0; i < count && p + 4 <= end; i++) {
            int clen = le32(b, p); p += 4;
            if (p + clen > end) break;
            String comment = new String(b, p, clen, StandardCharsets.UTF_8);
            p += clen;
            int eq = comment.indexOf('=');
            if (eq <= 0) continue;
            String key = comment.substring(0, eq).toUpperCase(java.util.Locale.ROOT);
            if (key.equals("LYRICS") || key.equals("UNSYNCEDLYRICS")) {
                String v = comment.substring(eq + 1).trim();
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }
}
