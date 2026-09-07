package dev.t1m3.qplayer.desktop.audio.automix;

import dev.t1m3.qplayer.desktop.audio.PcmSource;
import dev.t1m3.qplayer.util.Logger;



import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * Offline stem cache for the automix vocal-suppression path: during planning
 * (background thread, inside the 16s lead window) the incoming track's intro segment is
 * decoded, run through {@link VocalRemover} (center-channel vocal suppression) and saved
 * as a 16-bit WAV. The armed deck then plays the stem through the overlap and restores
 * the vocals via {@link VocalFadePcmSource}.
 *
 * <p>Stems are keyed by (url, cut point, length) and cached under
 * {@code <run>/musicplayer/stems/} — repeat transitions reuse them. Every failure
 * (decode error, disk, too slow) returns {@code null} and the caller falls back to the
 * plain beat-matched crossfade, so the feature can never break a transition.
 */
public final class StemCache {

    private StemCache() {}

    /**
     * Ensure the instrumental stem for {@code url} starting at {@code startMs} covering
     * {@code lenMs} exists; generate it if missing. Returns the absolute path, or null
     * when the stem can't be produced (caller degrades gracefully).
     */
    public static String ensure(String url, long startMs, int lenMs) {
        if (url == null || url.isBlank()) return null;
        try {
            Path dir = dir();
            Files.createDirectories(dir);
            Path stem = dir.resolve(hash(url, startMs, lenMs) + ".stem");
            if (Files.exists(stem) && Files.size(stem) > 20) return stem.toString();
            boolean ok = generate(url, startMs, lenMs, stem);
            return ok ? stem.toString() : null;
        } catch (Throwable e) {
            Logger.error("automix stem generation failed", e);
            return null;
        }
    }

    private static Path dir() {
        String home = System.getProperty("user.home", ".");
        return new java.io.File(new java.io.File(home, ".qplayer"), "stems").toPath();
    }

    private static String hash(String url, long startMs, int lenMs) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update((url + "|" + startMs + "|" + lenMs).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(url.hashCode()); }
    }

    /** Decode + strip vocals + write WAV. Runs on the caller's (background) thread. */
    private static boolean generate(String url, long startMs, int lenMs, Path out) {
        PcmSource pcm = null;
        try {
            pcm = PcmSource.open(url);
            int sr = pcm.sampleRate();
            int ch = Math.max(1, Math.min(2, pcm.channels()));
            if (startMs > 0) pcm.seek(startMs);
            int frameBytes = ch * 2;
            int want = (int) ((long) sr * (lenMs + 500) / 1000L);
            float[] mono = new float[want];
            byte[] buf = new byte[64 * 1024 - (64 * 1024 % frameBytes)];
            int monoLen = 0;
            while (monoLen < mono.length) {
                int got = pcm.read(buf, 0, buf.length);
                if (got <= 0) break;
                int frames = got / frameBytes;
                for (int f = 0; f < frames && monoLen < mono.length; f++) {
                    int b = f * frameBytes;
                    int sum = 0;
                    for (int c = 0; c < ch; c++) {
                        int bi = b + c * 2;
                        sum += (short) ((buf[bi + 1] << 8) | (buf[bi] & 0xFF));
                    }
                    mono[monoLen++] = (sum / ch) / 32768f;
                }
            }
            if (monoLen < sr / 4) return false;
            // Stereo-fy (VocalRemover operates on interleaved L/R) and strip the vocals.
            float[] stereo = new float[monoLen * 2];
            for (int i = 0; i < monoLen; i++) { stereo[i * 2] = mono[i]; stereo[i * 2 + 1] = mono[i]; }
            float[] instr = VocalRemover.process(stereo, sr, 0.0);
            RawPcmSource.write(out.toString(), instr, sr, ch);
            Logger.info("automix stem generated: " + out.getFileName() + " (" + monoLen / sr + "s)");
            return true;
        } catch (Throwable e) {
            Logger.error("automix stem generate failed for " + url, e);
            return false;
        } finally {
            if (pcm != null) try { pcm.close(); } catch (Throwable ignored) {}
        }
    }

    private static void writeIntLE(DataOutputStream out, int v) throws Exception {
        out.writeByte(v & 0xFF); out.writeByte((v >> 8) & 0xFF);
        out.writeByte((v >> 16) & 0xFF); out.writeByte((v >> 24) & 0xFF);
    }

    private static void writeShortLE(DataOutputStream out, int v) throws Exception {
        out.writeByte(v & 0xFF); out.writeByte((v >> 8) & 0xFF);
    }
}
