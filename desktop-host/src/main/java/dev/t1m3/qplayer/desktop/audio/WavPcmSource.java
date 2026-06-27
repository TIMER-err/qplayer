package dev.t1m3.qplayer.desktop.audio;

import java.io.IOException;

/**
 * WAV/RIFF decoder. The payload is already PCM, so this is a thin reader with
 * byte-offset seek; integer samples wider or narrower than 16-bit are folded to
 * 16-bit on the way out (OpenAL plays {@code *16} buffers).
 */
final class WavPcmSource implements PcmSource {

    private final SeekableByteSource src;
    private final int sampleRate;
    private final int channels;
    private final int bits;          // 8 / 16 / 24 / 32
    private final long dataStart;
    private final long dataLen;
    private final int inFrame;        // bytes per input frame
    private byte[] in = new byte[0];

    WavPcmSource(SeekableByteSource src) throws IOException {
        this.src = src;
        // RIFF header: "RIFF" <size> "WAVE", then a sequence of <id><size><body>.
        byte[] h = readAt(0, 12);
        if (h[8] != 'W' || h[9] != 'A' || h[10] != 'V' || h[11] != 'E') {
            throw new IOException("not a WAVE file");
        }
        int sr = 0, ch = 0, bps = 0, fmt = 0;
        long dStart = -1, dLen = 0;
        long off = 12;
        long fileSize = src.size();
        while (fileSize < 0 || off + 8 <= fileSize) {
            byte[] ch8 = readAt(off, 8);
            if (ch8.length < 8) break;
            String id = new String(ch8, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long sz = u32(ch8, 4);
            long body = off + 8;
            if ("fmt ".equals(id)) {
                byte[] f = readAt(body, (int) Math.min(sz, 40));
                fmt = u16(f, 0);
                ch = u16(f, 2);
                sr = (int) u32(f, 4);
                bps = u16(f, 14);
            } else if ("data".equals(id)) {
                dStart = body;
                dLen = sz;
                break; // audio data starts here; we have what we need
            }
            off = body + sz + (sz & 1); // chunks are word-aligned
        }
        if (dStart < 0 || sr <= 0 || ch <= 0 || bps <= 0) {
            throw new IOException("malformed WAVE (fmt/data missing)");
        }
        if (fmt != 1 || ch > 2) {
            throw new IOException("unsupported WAVE: format=" + fmt + " channels=" + ch + " bits=" + bps);
        }
        this.sampleRate = sr;
        this.channels = ch;
        this.bits = bps;
        this.dataStart = dStart;
        this.dataLen = (fileSize > 0) ? Math.min(dLen, fileSize - dStart) : dLen;
        this.inFrame = ch * (bps / 8);
        src.seek(dataStart);
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
        int outFrames = len / (channels * 2);
        if (outFrames <= 0) return 0;
        long consumed = src.position() - dataStart;
        long remainBytes = dataLen - consumed;
        if (remainBytes <= 0) return -1;
        int wantInBytes = (int) Math.min((long) outFrames * inFrame, remainBytes);
        if (in.length < wantInBytes) in = new byte[wantInBytes];
        int got = 0;
        while (got < wantInBytes) {
            int n = src.read(in, got, wantInBytes - got);
            if (n < 0) break;
            got += n;
        }
        int frames = got / inFrame;
        if (frames <= 0) return -1;
        int bytesPerSample = bits / 8;
        int o = off;
        for (int f = 0; f < frames; f++) {
            int base = f * inFrame;
            for (int c = 0; c < channels; c++) {
                int s = base + c * bytesPerSample;
                short v;
                switch (bits) {
                    case 8:  v = (short) (((in[s] & 0xff) - 128) << 8); break;
                    case 16: v = (short) ((in[s] & 0xff) | (in[s + 1] << 8)); break;
                    case 24: v = (short) ((in[s + 1] & 0xff) | (in[s + 2] << 8)); break;
                    default: v = (short) ((in[s + 2] & 0xff) | (in[s + 3] << 8)); break; // 32
                }
                dst[o++] = (byte) (v & 0xff);
                dst[o++] = (byte) ((v >> 8) & 0xff);
            }
        }
        return frames * channels * 2;
    }

    @Override
    public long seek(long ms) throws IOException {
        long frame = ms * sampleRate / 1000L;
        long byteOff = frame * inFrame;
        if (byteOff < 0) byteOff = 0;
        if (byteOff > dataLen) byteOff = dataLen - (dataLen % inFrame);
        src.seek(dataStart + byteOff);
        return (byteOff / inFrame) * 1000L / sampleRate;
    }

    @Override
    public long durationMs() {
        long frames = dataLen / inFrame;
        return frames * 1000L / sampleRate;
    }

    @Override
    public void close() {
        src.close();
    }

    private byte[] readAt(long pos, int len) throws IOException {
        src.seek(pos);
        byte[] b = new byte[len];
        int got = 0;
        while (got < len) {
            int n = src.read(b, got, len - got);
            if (n < 0) break;
            got += n;
        }
        if (got < len) {
            byte[] t = new byte[got];
            System.arraycopy(b, 0, t, 0, got);
            return t;
        }
        return b;
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8) | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }
}
