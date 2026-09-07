package dev.t1m3.qplayer.desktop.audio.automix;

import dev.t1m3.qplayer.desktop.audio.PcmSource;

import java.io.IOException;

/**
 * A {@link PcmSource} that pitch-preservingly time-stretches an inner source so a deck
 * reads tempo-matched audio without knowing. Wraps {@link WsolaStretcher}, converting
 * between the inner source's interleaved 16-bit little-endian PCM and the stretcher's
 * interleaved float frames.
 *
 * <p>{@code tempo} follows SoundTouch semantics ({@code >1} faster/shorter). For automix,
 * to play a {@code nativeBpm} track at the deck-A {@code targetBpm} pass
 * {@code tempo = targetBpm / nativeBpm}. A tempo of exactly 1 still routes through the
 * stretcher (it becomes a near-transparent copy); callers that want zero overhead should
 * skip the wrapper entirely.
 */
public final class WsolaPcmSource implements PcmSource {

    private final PcmSource inner;
    private final int channels;
    private final int sampleRate;
    private final WsolaStretcher stretcher;

    // Scratch buffers reused across reads.
    private final byte[]  inBytes;      // raw PCM read from inner
    private final float[] inFloats;     // decoded interleaved float frames
    private float[]       outFloats = new float[4096]; // stretched frames drained from WSOLA
    private boolean innerEof = false;

    private static final int READ_FRAMES = 2048;   // frames pulled from inner per pump

    public WsolaPcmSource(PcmSource inner, float tempo) {
        this.inner      = inner;
        this.channels   = Math.max(1, Math.min(2, inner.channels()));
        this.sampleRate = inner.sampleRate();
        this.stretcher  = new WsolaStretcher(channels, sampleRate);
        this.stretcher.setTempo(tempo);
        this.inBytes  = new byte[READ_FRAMES * channels * 2];
        this.inFloats = new float[READ_FRAMES * channels];
    }

    /** Adjust the stretch factor live (targetBpm / nativeBpm). */
    public void setTempo(float tempo) { stretcher.setTempo(tempo); }

    @Override public int sampleRate() { return sampleRate; }
    @Override public int channels()   { return channels; }

    @Override
    public long durationMs() {
        return 0L;
    }

    @Override
    public int read(byte[] dst, int off, int len) throws IOException {
        int frameBytes = channels * 2;
        int wantFrames = len / frameBytes;
        if (wantFrames <= 0) return 0;

        // Pump the inner source through the stretcher until enough output is ready or EOF.
        while (stretcher.available() < wantFrames && !innerEof) {
            int got = fillInner();          // bytes read from inner
            if (got <= 0) { innerEof = true; break; }
            int frames = got / frameBytes;
            // 16-bit LE → float [-1,1], interleaved.
            for (int i = 0; i < frames * channels; i++) {
                int b = i * 2;
                short s = (short) ((inBytes[b + 1] << 8) | (inBytes[b] & 0xFF));
                inFloats[i] = s / 32768f;
            }
            stretcher.putSamples(inFloats, frames);
        }

        int avail = stretcher.available();
        int outFrames = Math.min(wantFrames, avail);
        if (outFrames <= 0) return innerEof ? -1 : 0;

        if (outFloats.length < outFrames * channels)
            outFloats = new float[outFrames * channels];
        int drawn = stretcher.receive(outFloats, outFrames);

        // float → 16-bit LE, clamped.
        for (int i = 0; i < drawn * channels; i++) {
            float f = outFloats[i];
            int v = Math.round(f * 32767f);
            if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
            int b = off + i * 2;
            dst[b]     = (byte) (v & 0xFF);
            dst[b + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return drawn * frameBytes;
    }

    /** Read one block from the inner source into {@link #inBytes}; returns bytes read. */
    private int fillInner() throws IOException {
        int frameBytes = channels * 2;
        int want = inBytes.length - (inBytes.length % frameBytes);
        int got = 0;
        while (got < want) {
            int r = inner.read(inBytes, got, want - got);
            if (r <= 0) break;
            got += r;
        }
        return got - (got % frameBytes);
    }

    @Override
    public long seek(long ms) throws IOException {
        long r = inner.seek(ms);
        stretcher.reset();
        innerEof = false;
        return r;
    }

    @Override
    public void close() { inner.close(); }
}
