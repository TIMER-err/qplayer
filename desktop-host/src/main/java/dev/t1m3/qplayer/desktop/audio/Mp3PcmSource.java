package dev.t1m3.qplayer.desktop.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;

/**
 * MP3 decoder over JLayer. A sparse, per-source frame index is extended only
 * into unscanned audio and reused by subsequent seeks. Entries count actual
 * MPEG frame durations (including VBR), not estimated byte/bitrate ratios.
 * Decode a short lead-in before the target to rebuild Layer III's reservoir
 * and synthesis filters.
 */
final class Mp3PcmSource implements PcmSource {

    private final SeekableByteSource src;
    private Bitstream bitstream;
    private Decoder decoder;
    private final int sampleRate;
    private final int channels;

    private byte[] pending = new byte[0];
    private int pendingPos = 0;
    private int pendingLen = 0;
    private boolean eof = false;
    private TreeMap<Double, Long> seekIndex = new TreeMap<>();
    private final byte[] scanHeader = new byte[4];
    private long scanOffset;
    private long scannedFrames;
    private double scannedMs;
    private boolean scanEnded;

    @Override public boolean hasBuffered(int n) {
        return pendingPos < pendingLen || eof || src.hasBuffered(n);
    }

    Mp3PcmSource(SeekableByteSource src) throws IOException {
        this.src = src;
        src.seek(0);
        this.bitstream = new Bitstream(new SrcStream());
        this.decoder = new Decoder();
        scanOffset = bitstream.header_pos();
        seekIndex.put(0.0, scanOffset);
        // Decode the first frame to nail down the output format.
        try {
            Header h = bitstream.readFrame();
            if (h == null) throw new IOException("empty / unrecognised MP3 stream");
            SampleBuffer sb = (SampleBuffer) decoder.decodeFrame(h, bitstream);
            bitstream.closeFrame();
            this.sampleRate = decoder.getOutputFrequency();
            this.channels = decoder.getOutputChannels();
            stage(sb);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("MP3 decode failed", e);
        }
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public int read(byte[] dst, int off, int len) throws IOException {
        if (len <= 0) return 0;
        // Keep filling past empty frames: the first frame after a seek can decode
        // to zero samples (Layer III bit reservoir has no prior-frame context), and
        // returning 0 must not be mistaken for end-of-stream by the caller.
        while (pendingPos >= pendingLen) {
            if (!fill()) return -1;
        }
        int n = Math.min(len, pendingLen - pendingPos);
        System.arraycopy(pending, pendingPos, dst, off, n);
        pendingPos += n;
        return n;
    }

    private boolean fill() throws IOException {
        if (eof) return false;
        try {
            Header h = bitstream.readFrame();
            if (h == null) {
                eof = true;
                return false;
            }
            SampleBuffer sb = (SampleBuffer) decoder.decodeFrame(h, bitstream);
            bitstream.closeFrame();
            stage(sb);
            return true;
        } catch (Exception e) {
            throw new IOException("MP3 decode failed", e);
        }
    }

    /** Copy a decoded frame's 16-bit samples into {@link #pending} as LE bytes. */
    private void stage(SampleBuffer sb) {
        short[] s = sb.getBuffer();
        int count = sb.getBufferLength();
        int outLen = count * 2;
        if (pending.length < outLen) pending = new byte[outLen];
        for (int i = 0, o = 0; i < count; i++) {
            short v = s[i];
            pending[o++] = (byte) (v & 0xff);
            pending[o++] = (byte) ((v >> 8) & 0xff);
        }
        pendingPos = 0;
        pendingLen = outLen;
    }

    @Override
    public long seek(long ms) throws IOException {
        try {
            long target = Math.max(0L, ms);
            extendIndex(target);
            Map.Entry<Double, Long> entry = seekIndex.floorEntry(Math.max(0.0, target - 500.0));
            bitstream.close();
            src.seek(entry.getValue());
            bitstream = new Bitstream(new SrcStream());
            decoder = new Decoder();
            double accumMs = entry.getKey();
            while (accumMs < target) {
                Header h = bitstream.readFrame();
                if (h == null) break;
                decoder.decodeFrame(h, bitstream);
                accumMs += h.ms_per_frame();
                bitstream.closeFrame();
            }
            pendingPos = pendingLen = 0;
            eof = false;
            return (long) accumMs;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("MP3 seek failed", e);
        }
    }

    private void extendIndex(long target) throws IOException {
        while (!scanEnded && scannedMs < target) {
            src.seek(scanOffset);
            int got = 0;
            while (got < 4) {
                int n = src.read(scanHeader, got, 4 - got);
                if (n < 0) { scanEnded = true; return; }
                got += n;
            }
            int header = ((scanHeader[0] & 255) << 24) | ((scanHeader[1] & 255) << 16)
                    | ((scanHeader[2] & 255) << 8) | (scanHeader[3] & 255);
            int versionBits = (header >>> 19) & 3;
            int layerBits = (header >>> 17) & 3;
            int rateIndex = (header >>> 10) & 3;
            int bitrateIndex = (header >>> 12) & 15;
            if ((header & 0xffe00000) != 0xffe00000 || versionBits == 1 || layerBits == 0
                    || rateIndex == 3 || bitrateIndex == 0 || bitrateIndex == 15) {
                scanOffset++;
                continue;
            }
            int version = versionBits == 3 ? Header.MPEG1
                    : versionBits == 2 ? Header.MPEG2_LSF : Header.MPEG25_LSF;
            int layer = 4 - layerBits;
            int rate = Header.frequencies[version][rateIndex];
            int bitrate = Header.bitrates[version][layer - 1][bitrateIndex];
            int padding = (header >>> 9) & 1;
            int frameBytes = layer == 1 ? (12 * bitrate / rate + padding) * 4
                    : (layer == 3 && version != Header.MPEG1 ? 72 : 144) * bitrate / rate + padding;
            int samples = layer == 1 ? 384 : layer == 3 && version != Header.MPEG1 ? 576 : 1152;
            if ((scannedFrames++ & 63) == 0) seekIndex.put(scannedMs, scanOffset);
            scanOffset += frameBytes;
            scannedMs += samples * 1000.0 / rate;
        }
    }

    /** Audio-thread handoff only: the old decoder has already relinquished this stream. */
    void reuseSeekIndex(Mp3PcmSource previous) {
        if (sampleRate != previous.sampleRate || channels != previous.channels) return;
        seekIndex = previous.seekIndex;
        scanOffset = previous.scanOffset;
        scannedFrames = previous.scannedFrames;
        scannedMs = previous.scannedMs;
        scanEnded = previous.scanEnded;
    }

    @Override
    public long durationMs() {
        return 0L; // unknown without a full scan; the UI uses track metadata
    }

    @Override
    public void close() {
        try {
            bitstream.close();
        } catch (Exception ignored) {
        }
        src.close();
    }

    /** Adapts the seekable byte source to the InputStream JLayer expects. */
    private final class SrcStream extends InputStream {
        private final byte[] one = new byte[1];
        @Override
        public int read() throws IOException {
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return src.read(b, off, len);
        }
    }
}
