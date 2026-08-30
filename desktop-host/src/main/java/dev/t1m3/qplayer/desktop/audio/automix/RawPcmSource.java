package dev.t1m3.qplayer.desktop.audio.automix;

import dev.t1m3.qplayer.desktop.audio.PcmSource;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Dependency-free raw 16-bit PCM source for the automix stem cache. Avoids
 * javax.sound's SPI machinery entirely (a WAV stem routed through {@code AudioSystem}
 * can be claimed by third-party SPIs like JAAD and fail) and supports EXACT seeks
 * (pure file offset arithmetic) — the stem must align sample-perfectly with the raw
 * track it was cut from.
 *
 * <p>File format ({@code .stem}): 20-byte header — magic "STEM1", int32 sample rate,
 * int32 channels, int32 frame count — followed by little-endian interleaved int16 PCM.
 */
public final class RawPcmSource implements PcmSource {

    private static final byte[] MAGIC = {'S', 'T', 'E', 'M', '1'};

    private final RandomAccessFile file;
    private final long dataStart;
    private final int sampleRate;
    private final int channels;
    private final long frameCount;
    private long framePos = 0;

    public RawPcmSource(String path) throws IOException {
        this.file = new RandomAccessFile(path, "r");
        byte[] m = new byte[5];
        file.readFully(m);
        for (int i = 0; i < 5; i++) if (m[i] != MAGIC[i]) throw new IOException("not a stem file: " + path);
        this.sampleRate = file.readInt();
        this.channels = file.readInt();
        this.frameCount = Integer.toUnsignedLong(file.readInt());
        this.dataStart = 5 + 12;
        if (sampleRate <= 0 || channels < 1 || channels > 2 || frameCount <= 0)
            throw new IOException("bad stem header: " + path);
    }

    /** Write {@code interleaved} int16-range floats as a {@code .stem} file. */
    public static void write(String path, float[] interleaved, int sr, int ch) throws IOException {
        try (RandomAccessFile out = new RandomAccessFile(path, "rw")) {
            out.setLength(0);
            out.write(MAGIC);
            out.writeInt(sr);
            out.writeInt(ch);
            out.writeInt(interleaved.length / ch);
            for (float s : interleaved) {
                int v = Math.max(-32768, Math.min(32767, Math.round(s * 32767f)));
                out.writeByte(v & 0xFF);
                out.writeByte((v >> 8) & 0xFF);
            }
        }
    }

    @Override public int sampleRate() { return sampleRate; }
    @Override public int channels()   { return channels; }

    @Override
    public long durationMs() {
        return 0L;
    }

    @Override
    public int read(byte[] dst, int off, int len) throws IOException {
        int frameBytes = channels * 2;
        int want = len - (len % frameBytes);
        long avail = (frameCount - framePos) * frameBytes;
        if (avail <= 0) return -1;
        int n = (int) Math.min(want, avail);
        n -= n % frameBytes;
        if (n <= 0) return -1;
        file.seek(dataStart + framePos * frameBytes);
        file.readFully(dst, off, n);
        framePos += n / frameBytes;
        return n;
    }

    @Override
    public long seek(long ms) throws IOException {
        long target = Math.max(0L, (long) ms * sampleRate / 1000L);
        framePos = Math.min(target, frameCount);
        return framePos * 1000L / sampleRate;
    }

    @Override
    public void close() {
        try { file.close(); } catch (Exception ignored) {}
    }
}
