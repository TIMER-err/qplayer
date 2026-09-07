package dev.t1m3.qplayer.desktop.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.IOException;
import java.io.InputStream;

/**
 * MP3 decoder over JLayer. Seeking walks frame <em>headers</em> from the start
 * — {@code readFrame}/{@code closeFrame} without the costly subband synthesis —
 * accumulating {@link Header#ms_per_frame()} until the target. That is roughly
 * two orders of magnitude cheaper than the old decode-everything-and-discard
 * seek, and stays accurate on VBR streams (it counts real frames, not an
 * estimated byte offset).
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

    Mp3PcmSource(SeekableByteSource src) throws IOException {
        this.src = src;
        src.seek(0);
        this.bitstream = new Bitstream(new SrcStream());
        this.decoder = new Decoder();
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
            src.seek(0);
            bitstream = new Bitstream(new SrcStream());
            decoder = new Decoder();
            double accumMs = 0;
            while (accumMs < ms) {
                Header h = bitstream.readFrame();
                if (h == null) break;
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
        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : (b[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return src.read(b, off, len);
        }
    }
}
