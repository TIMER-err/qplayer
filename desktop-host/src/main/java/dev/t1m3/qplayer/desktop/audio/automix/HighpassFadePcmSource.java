package dev.t1m3.qplayer.desktop.audio.automix;

import dev.t1m3.qplayer.desktop.audio.PcmSource;

import java.io.IOException;

/**
 * A {@link PcmSource} wrapper that high-passes the first {@link #FADE_MS} of playback
 * and sweeps the cutoff down to near-flat — the DJ trick of "easing the bass in" so the
 * incoming deck's low end doesn't fight the outgoing deck's during an automix overlap.
 * After the fade the filter is transparent (cutoff at 20 Hz ≈ unity for music).
 *
 * <p>Timing is content-based (samples read since {@link #resetFade()}), not wall-clock,
 * so it works regardless of decode speed. {@link AudioPlayer} calls {@link #resetFade()}
 * at the audible start (right after releasing an armed deck / before {@code alSourcePlay}),
 * so the arm-prime reads don't consume the fade.
 *
 * <p>One-pole high-pass per channel: {@code y[n] = a·(y[n-1] + x[n] − x[n-1])},
 * {@code a = 1 − exp(−2π·fc/fs)}. Stateless per block beyond the one-sample memory.
 */
public final class HighpassFadePcmSource implements PcmSource {

    /** How long (content ms) the high-pass sweep lasts. */
    static final int    FADE_MS     = 2500;
    /** Sweep start cutoff — a real low-cut, removes most of the kick/punch. */
    static final float  START_HP_HZ = 320f;
    /** Sweep end cutoff — effectively transparent for music. */
    static final float  END_HP_HZ   = 20f;

    private final PcmSource inner;
    private final int channels;
    private final int sampleRate;

    // Per-channel one-pole state + the fade clock in samples.
    private final float[] yPrev;
    private final float[] xPrev;
    private long fadeSamplesLeft;   // counts DOWN from FADE_MS worth of samples
    private boolean fading;

    public HighpassFadePcmSource(PcmSource inner) {
        this.inner      = inner;
        this.channels   = Math.max(1, Math.min(2, inner.channels()));
        this.sampleRate = inner.sampleRate();
        this.yPrev      = new float[channels];
        this.xPrev      = new float[channels];
        resetFade();
    }

    /** Restart the fade clock (call at the audible start of the track). */
    public void resetFade() {
        fadeSamplesLeft = (long) sampleRate * FADE_MS / 1000L;
        fading = fadeSamplesLeft > 0;
    }

    @Override public int sampleRate() { return sampleRate; }
    @Override public int channels()   { return channels; }

    @Override
    public long durationMs() {
        return 0L;
    }

    @Override
    public int read(byte[] dst, int off, int len) throws IOException {
        // Read raw from inner, then filter in place on the same buffer.
        int got = inner.read(dst, off, len);
        if (got <= 0) return got;
        int frames = got / (channels * 2);
        if (!fading) return got;                 // transparent after the sweep
        if (fadeSamplesLeft <= 0) { fading = false; return got; }

        for (int f = 0; f < frames; f++) {
            long remaining = fadeSamplesLeft - f;
            if (remaining <= 0) { fading = false; break; }
            // Exponential sweep: fc goes START → END over FADE_MS.
            float t = 1f - (float) remaining / ((float) sampleRate * FADE_MS / 1000f);
            float fc = END_HP_HZ + (START_HP_HZ - END_HP_HZ) * (1f - t);
            float a = 1f - (float) Math.exp(-2 * Math.PI * fc / sampleRate);
            for (int c = 0; c < channels; c++) {
                int b = off + (f * channels + c) * 2;
                short s = (short) ((dst[b + 1] << 8) | (dst[b] & 0xFF));
                float x = s / 32768f;
                float y = a * (yPrev[c] + x - xPrev[c]);
                yPrev[c] = y;
                xPrev[c] = x;
                int v = Math.round(y * 32767f);
                if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                dst[b]     = (byte) (v & 0xFF);
                dst[b + 1] = (byte) ((v >> 8) & 0xFF);
            }
        }
        if (fadeSamplesLeft > frames) fadeSamplesLeft -= frames; else { fadeSamplesLeft = 0; fading = false; }
        return got;
    }

    @Override
    public long seek(long ms) throws IOException {
        long r = inner.seek(ms);
        // Deliberately do NOT restart the fade clock here: a mid-track seek (user drags
        // the progress bar) must not re-run the bass sweep. The clock is owned by the
        // constructor (fade-in path) and AudioPlayer's releaseArmed() (armed path).
        java.util.Arrays.fill(yPrev, 0f);
        java.util.Arrays.fill(xPrev, 0f);
        return r;
    }

    @Override
    public void close() { inner.close(); }
}
