package dev.t1m3.qplayer.desktop.audio.automix;

import dev.t1m3.qplayer.desktop.audio.PcmSource;

import java.io.IOException;

/**
 * A {@link PcmSource} that produces an audio-transport interpolated transition from
 * source A (the outgoing track, already playing) to source B (the incoming track).
 *
 * <p>The whole transition (A's outro + B's intro, ~{@code transitionMs}) is decoded
 * from both sources up front and glided in a single
 * {@link AudioTransportStretcher#interpolate(double[], double[], int, double, double)}
 * call with the interpolation factor ramping 0→1 across the analysis windows — the
 * same shape as the C++ original, which processed two whole buffers. Processing the
 * full transition at once keeps the overlap-add flat (no per-chunk hann envelope)
 * and keeps phase continuity across the entire glide.
 *
 * <p>The earlier chunked port instead fed 2048-frame chunks per call: at 44.1/48 kHz
 * the 50 ms window (N=2206) no longer spanned two hops (numWindows = 0) so every call
 * returned nothing and the read loop spun forever; it also ran the radix-2 FFT on
 * non-power-of-two lengths (Npadded = N*8), silently corrupting the spectrum.
 *
 * <p>After the transition completes, reads are served directly from B with zero
 * overhead.
 *
 * <p>Threading: not thread-safe — intended to be owned by a single AudioPlayer audio
 * thread. Construction decodes up to ~transitionMs of audio from each source; if the
 * sources throw, the constructor propagates the exception and AudioPlayer falls back
 * to a plain crossfade.
 */
public final class AudioTransportPcmSource implements PcmSource {

    private final PcmSource srcA;
    private final PcmSource srcB;
    private final int sampleRate;
    private final int channels;
    private final long transitionFrames;

    /** Precomputed transition PCM (mono). Empty when the transition degenerated. */
    private final double[] transition;
    private int pos = 0;
    private boolean transitionDone;

    public AudioTransportPcmSource(PcmSource srcA, PcmSource srcB, float transitionMs)
            throws IOException {
        this.srcA = srcA;
        this.srcB = srcB;
        this.sampleRate     = srcB.sampleRate();
        this.channels       = Math.max(1, Math.min(2, srcB.channels()));
        this.transitionFrames = (long) (transitionMs * sampleRate / 1000f);

        // Decode the whole transition up front. Both halves are crossfade-length,
        // so this is at most a few MB; any decode error propagates to the caller's
        // fallback (plain crossfade) instead of wedging the audio thread.
        int cap = (int) transitionFrames + 1;
        double[] monoA = decodeMono(srcA, cap);
        double[] monoB = decodeMono(srcB, cap);
        double[] out = AudioTransportStretcher.interpolate(monoA, monoB, sampleRate, 0.0, 1.0);
        if (out.length == 0) {
            // Degenerate input (e.g. both sources exhausted within a window) —
            // skip the transition and stream B straight through.
            transition = new double[0];
            transitionDone = true;
        } else {
            transition = out;
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
        if (transitionDone) return srcB.read(dst, off, len);

        int frameBytes = channels * 2;
        int wrote = 0;
        while (len - wrote >= frameBytes && pos < transition.length) {
            int frames = Math.min((len - wrote) / frameBytes, transition.length - pos);
            for (int f = 0; f < frames; f++) {
                int v = clamp16(transition[pos + f]);
                for (int c = 0; c < channels; c++) {
                    int bi = off + wrote + f * frameBytes + c * 2;
                    dst[bi]     = (byte) (v & 0xFF);
                    dst[bi + 1] = (byte) ((v >> 8) & 0xFF);
                }
            }
            pos += frames;
            wrote += frames * frameBytes;
        }
        if (pos >= transition.length) transitionDone = true;
        if (wrote > 0) return wrote;
        return transitionDone ? srcB.read(dst, off, len) : -1;
    }

    /** Decode {@code wantFrames} of mono doubles from {@code src}. Returns shorter array at EOF. */
    private double[] decodeMono(PcmSource src, int wantFrames) throws IOException {
        int frameBytes = Math.max(1, Math.min(2, src.channels())) * 2;
        int ch = frameBytes / 2;
        byte[] buf = new byte[wantFrames * frameBytes];
        int got = 0;
        while (got < buf.length) {
            int r = src.read(buf, got, buf.length - got);
            if (r <= 0) break;
            got += r;
        }
        int frames = got / frameBytes;
        double[] mono = new double[frames];
        for (int f = 0; f < frames; f++) {
            int sum = 0;
            for (int c = 0; c < ch; c++) {
                int bi = (f * ch + c) * 2;
                sum += (short) ((buf[bi + 1] << 8) | (buf[bi] & 0xFF));
            }
            mono[f] = (sum / ch) / 32768.0;
        }
        return mono;
    }

    private static int clamp16(double v) {
        int i = (int) Math.round(v * 32767.0);
        return Math.max(-32768, Math.min(32767, i));
    }

    @Override
    public long seek(long ms) throws IOException {
        // Seeking during a transport transition is not meaningful; skip to the post-B path.
        transitionDone = true;
        return srcB.seek(ms);
    }

    @Override
    public void close() {
        try { srcA.close(); } catch (Exception ignored) {}
        srcB.close();
    }
}
