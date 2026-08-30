package dev.t1m3.qplayer.automix;

/**
 * Offline tempo + beat-grid estimator for automix. Pure DSP: takes a mono PCM window
 * and returns an estimated BPM plus the phase (ms position of the first beat) so a
 * crossfade can align the two tracks' kicks.
 *
 * <p>Pipeline (classic onset-autocorrelation tempo induction, no external deps):
 * <ol>
 *   <li>Slice the signal into hops of {@link #HOP} samples; per hop compute short-time
 *       energy and its positive first difference (spectral-flux-ish onset strength on
 *       the time-domain energy envelope — we have no FFT here, energy flux tracks
 *       percussive onsets well enough for beat spacing).</li>
 *   <li>Autocorrelate the onset envelope over the lag range that maps to
 *       {@link #MIN_BPM}‥{@link #MAX_BPM}; the strongest lag is the beat period.</li>
 *   <li>Fit the phase by sliding a pulse train at that period across the envelope and
 *       taking the offset with the highest onset sum.</li>
 * </ol>
 *
 * <p>This is an estimate, not ground truth — good enough to beat-align a crossfade,
 * which only needs the period and one anchor beat within the mix zone.
 */
public final class BeatAnalyzer {

    /** Envelope hop in samples (~11.6 ms at 44.1 kHz) — fine enough for beat spacing. */
    private static final int   HOP     = 512;
    private static final float MIN_BPM = 70f;
    private static final float MAX_BPM = 180f;

    /** Result: estimated tempo, the ms position of the first beat, and the ms position
     *  of the first BAR boundary (4 beats). Apple's AutoMix aligns to bars — "the exact
     *  bar where an outro meets an intro" — so the handover lands on a phrase boundary,
     *  not mid-measure. */
    public static final class BeatGrid {
        public final float bpm;
        public final float phaseMs;
        public final float barPhaseMs;

        BeatGrid(float bpm, float phaseMs, float barPhaseMs) {
            this.bpm = bpm;
            this.phaseMs = phaseMs;
            this.barPhaseMs = barPhaseMs;
        }

        public float bpm() { return bpm; }
        public float phaseMs() { return phaseMs; }
        public float barPhaseMs() { return barPhaseMs; }

        float beatPeriodMs() { return 60_000f / bpm; }
        public float barPeriodMs() { return 4f * 60_000f / bpm; }
    }

    private BeatAnalyzer() {}


    /**
     * Estimate the beat grid of a mono PCM window.
     *
     * @param mono normalised mono samples in [-1,1]
     * @param sr   sample rate (Hz)
     * @return the estimated grid, or {@code null} if the window is too short / silent.
     */
    public static BeatGrid analyze(float[] mono, int sr) {
        if (mono == null || sr <= 0) return null;
        int hops = mono.length / HOP;
        if (hops < 16) return null;   // need a few seconds to induce a tempo

        // ── 1. Onset envelope: positive energy flux per hop ──────────────────
        float[] onset = new float[hops];
        float prevE = 0f;
        double total = 0.0;
        for (int h = 0; h < hops; h++) {
            int base = h * HOP;
            float e = 0f;
            for (int i = 0; i < HOP; i++) {
                float s = mono[base + i];
                e += s * s;
            }
            e = (float) Math.sqrt(e / HOP);          // RMS of the hop
            float flux = e - prevE;
            onset[h] = flux > 0f ? flux : 0f;        // half-wave rectify → onsets only
            prevE = e;
            total += onset[h];
        }
        if (total < 1e-4) return null;               // effectively silent

        // Mean-remove so the autocorrelation measures periodicity, not DC level.
        float mean = (float) (total / hops);
        for (int h = 0; h < hops; h++) onset[h] -= mean;

        float envRate = (float) sr / HOP;            // onset samples per second
        int minLag = Math.max(1, Math.round(envRate * 60f / MAX_BPM));
        int maxLag = Math.min(hops - 1, Math.round(envRate * 60f / MIN_BPM));
        if (maxLag <= minLag) return null;

        // ── 2. Autocorrelation over the tempo lag range ──────────────────────
        int   bestLag = minLag;
        double bestAc  = Double.NEGATIVE_INFINITY;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double ac = 0.0;
            for (int h = lag; h < hops; h++) ac += (double) onset[h] * onset[h - lag];
            // Slight bias toward faster tempi cancels the natural AC falloff at long lags.
            ac *= 1.0 + 0.0005 * (maxLag - lag);
            if (ac > bestAc) { bestAc = ac; bestLag = lag; }
        }
        float bpm = 60f * envRate / bestLag;

        // ── 3. Phase fit: best pulse-train offset at that period ─────────────
        int    bestOff = 0;
        float  bestSum = Float.NEGATIVE_INFINITY;
        for (int off = 0; off < bestLag; off++) {
            float sum = 0f;
            for (int h = off; h < hops; h += bestLag) sum += onset[h];
            if (sum > bestSum) { bestSum = sum; bestOff = off; }
        }
        float phaseMs = bestOff * (HOP * 1000f / sr);

        // ── 3b. Bar phase: re-fit the pulse train at 4× the period, but only testing
        //      the 4 candidate bar offsets that are consistent with the beat phase, so
        //      the bar boundary is a real downbeat (beat 1 of 4), not an arbitrary beat.
        int barLag = bestLag * 4;
        if (barLag < hops) {
            float bestBarSum = Float.NEGATIVE_INFINITY;
            int bestBarOff = bestOff;
            for (int off = bestOff; off < bestOff + bestLag; off += Math.max(1, bestLag / 4)) {
                float sum = 0f;
                for (int h = off; h < hops; h += barLag) sum += onset[h];
                if (sum > bestBarSum) { bestBarSum = sum; bestBarOff = off; }
            }
            float barPhaseMs = bestBarOff * (HOP * 1000f / sr);
            return new BeatGrid(bpm, phaseMs, barPhaseMs);
        }
        return new BeatGrid(bpm, phaseMs, phaseMs);
    }
}
