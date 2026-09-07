package dev.t1m3.qplayer.desktop.audio.automix;

import dev.t1m3.qplayer.desktop.audio.PcmSource;

import java.io.IOException;

/**
 * Automix stem entry: plays B's INSTRUMENTAL (vocals suppressed via
 * {@link VocalRemover}, precomputed offline into a stem file by {@link StemCache})
 * through the automix overlap, then crossfades the vocals back in after the handover —
 * the two tracks never clash vocally.
 *
 * <p>Mixing model (vocals layer): {@code out = stem×G(k) + (raw − stem)×k}, where
 * {@code k} is the vocal retention (0 during the hold = overlap, 0→1 over the fade) and
 * {@code G(k) = boost + (1−boost)×k} decays the stem's loudness compensation as the
 * vocals return, so {@code k=1} reproduces the raw source EXACTLY (no doubled backing).
 * After the fade window the wrapper bypasses to {@code raw} with zero overhead.
 *
 * <p>Not thread-safe — owned by one deck's audio thread. {@code stemStartMs} is the raw
 * position the stem was cut at, so seeks stay aligned across both sources.
 */
public final class VocalFadePcmSource implements PcmSource {

    private final PcmSource raw;            // original B (deck-seeked to its entry)
    private final PcmSource stem;           // instrumental WAV cut at stemStartMs
    private final long    holdFrames;       // overlap: vocals suppressed
    private final long    fadeFrames;       // restore: vocals ease back
    private final long    stemStartMs;
    private final int     channels;
    private final int     sampleRate;
    private final int     frameBytes;

    private long   framesSinceStem = 0;     // frames played since stemStartMs
    private boolean done = false;
    private float  boost = 2.0f;            // stem loudness compensation (measured at open)
    private boolean boostReady = false;

    private final byte[] tmpRaw;            // per-read scratch
    private final byte[] tmpStem;           // per-read scratch (stem side)

    public VocalFadePcmSource(PcmSource raw, PcmSource stem, float holdMs, float fadeMs,
                       long stemStartMs) {
        this.raw = raw;
        this.stem = stem;
        this.sampleRate = raw.sampleRate();
        this.channels = Math.max(1, Math.min(2, raw.channels()));
        this.frameBytes = channels * 2;
        this.holdFrames = (long) (holdMs * sampleRate / 1000f);
        this.fadeFrames = Math.max(1L, (long) (fadeMs * sampleRate / 1000f));
        this.stemStartMs = stemStartMs;
        this.tmpRaw = new byte[16 * 1024 - (16 * 1024 % frameBytes)];
        this.tmpStem = new byte[tmpRaw.length];
    }

    @Override public int sampleRate() { return sampleRate; }
    @Override public int channels()   { return channels; }

    @Override
    public long durationMs() {
        return 0L;
    }

    @Override
    public int read(byte[] dst, int off, int len) throws IOException {
        if (done) return raw.read(dst, off, len);

        int wantFrames = Math.min(len / frameBytes, tmpRaw.length / frameBytes);
        if (wantFrames <= 0) return raw.read(dst, off, len);
        int got = 0;
        while (got < wantFrames * frameBytes) {
            int r = raw.read(tmpRaw, got, wantFrames * frameBytes - got);
            if (r <= 0) break;
            got += r;
        }
        int frames = got / frameBytes;
        if (frames <= 0) return got > 0 ? got : raw.read(dst, off, len);   // EOF passthrough

        int gotS = 0;
        while (gotS < got) {
            int r = stem.read(tmpStem, gotS, got - gotS);
            if (r <= 0) break;
            gotS += r;
        }

        // Measure the loudness compensation on the first chunk (boost = raw/stem RMS).
        if (!boostReady) {
            boost = measureBoost(tmpRaw, tmpStem, Math.min(frames, 4000));
            boostReady = true;
        }

        long fBase = framesSinceStem;
        long end = holdFrames + fadeFrames;
        for (int f = 0; f < frames; f++) {
            long pos = fBase + f;
            float k;
            if (pos < holdFrames) k = 0f;
            else if (pos >= end) k = 1f;
            else k = (pos - holdFrames) / (float) fadeFrames;
            float g = boost + (1f - boost) * k;
            int bi = f * frameBytes;
            int oi = off + bi;
            for (int c = 0; c < channels; c++) {
                short rawS = (short) ((tmpRaw[bi + c * 2 + 1] << 8) | (tmpRaw[bi + c * 2] & 0xFF));
                short stemS = bi < gotS
                        ? (short) ((tmpStem[bi + c * 2 + 1] << 8) | (tmpStem[bi + c * 2] & 0xFF))
                        : rawS;   // stem exhausted → raw
                float v = stemS * g + (rawS - stemS) * k;
                int vi = Math.max(-32768, Math.min(32767, Math.round(v)));
                dst[oi + c * 2]     = (byte) (vi & 0xFF);
                dst[oi + c * 2 + 1] = (byte) ((vi >> 8) & 0xFF);
            }
        }
        framesSinceStem += frames;
        if (framesSinceStem >= end) done = true;   // vocals fully back → raw pass-through
        return frames * frameBytes;
    }

    /** RMS-ratio boost capped to [1, 3]: the instrumental alone shouldn't feel quiet. */
    private float measureBoost(byte[] rawBuf, byte[] stemBuf, int frames) {
        double r = 0, s = 0;
        for (int f = 0; f < frames; f++) {
            int bi = f * frameBytes;
            short rawS = (short) ((rawBuf[bi + 1] << 8) | (rawBuf[bi] & 0xFF));
            short stemS = (short) ((stemBuf[bi + 1] << 8) | (stemBuf[bi] & 0xFF));
            r += (double) rawS * rawS;
            s += (double) stemS * stemS;
        }
        double rRms = Math.sqrt(r / Math.max(1, frames));
        double sRms = Math.sqrt(s / Math.max(1, frames));
        float b = sRms > 1e-3 ? (float) (rRms / sRms) : 1f;
        return Math.max(1f, Math.min(3f, b));
    }

    @Override
    public long seek(long ms) throws IOException {
        long r = raw.seek(ms);
        // Reposition the stem relative to its cut point; past the window → plain raw.
        long rel = Math.max(0L, ms - stemStartMs);
        framesSinceStem = rel * sampleRate / 1000L;
        done = framesSinceStem >= holdFrames + fadeFrames;
        if (!done) stem.seek(rel);
        return r;
    }

    @Override
    public void close() {
        try { stem.close(); } catch (Exception ignored) {}
        try { raw.close(); } catch (Exception ignored) {}
    }
}
