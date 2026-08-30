package dev.t1m3.qplayer.desktop.audio.automix;

/**
 * Lightweight pure-Java vocal (center-channel) suppression for the automix stem path.
 *
 * <p>Principle: lead vocals are almost always panned to the center, while most drums /
 * guitars / pads carry stereo energy. Per STFT frame we decompose the stereo field into
 * center (L+R)/2 and side (L−R)/2, then attenuate ONLY the center component inside the
 * vocal band (~200 Hz–5 kHz, smooth edges) — bass/kick (below ~200 Hz) and cymbals/hi-hats
 * (above ~5 kHz) keep their center energy, so the "instrumental" still has weight.
 *
 * <p>Not Spleeter/Demucs — a karaoke-grade approximation. Good enough for an automix
 * overlap ("normal experience"), runs offline in milliseconds, no ML deps, and the same
 * routine doubles as the transition preview renderer.
 *
 * <p>{@code vocalK} ∈ [0,1] is the vocal retention: 0 = fully removed (instrumental),
 * 1 = original (pass-through). The automix fades vocalK 0→1 after the handover so B's
 * vocals "come back" once A is gone.
 */
public final class VocalRemover {

    private VocalRemover() {}

    /** STFT size — 2048 @ 44.1 kHz ≈ 46 ms window, 4× overlap (hop 512). */
    private static final int N = 2048;
    private static final int HOP = 512;
    private static final int HALF = N / 2 + 1;

    /** Center-attenuation band (Hz): full suppression between the inner edges, smooth
     *  raised-cosine ramps between inner and outer edges. Low edge sits low enough
     *  (100 Hz) that kick/bass fundamentals pass untouched. */
    private static final double LO_OUTER = 100.0;
    private static final double LO_INNER = 280.0;
    private static final double HI_INNER = 4000.0;
    private static final double HI_OUTER = 5200.0;

    /**
     * Process interleaved stereo samples in place? No — returns a new array (input untouched).
     *
     * @param stereo interleaved L/R float samples
     * @param sr     sample rate
     * @param vocalK vocal retention 0..1 (0 = instrumental, 1 = unchanged)
     * @return processed interleaved stereo
     */
    static float[] process(float[] stereo, int sr, double vocalK) {
        if (vocalK >= 1.0) return stereo;
        if (stereo == null || stereo.length < N * 2) return stereo;
        int frames = stereo.length / 2;

        double[] outL = new double[frames];
        double[] outR = new double[frames];
        double[] winSum = new double[frames];

        double[] win = new double[N];
        for (int i = 0; i < N; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));

        double[] lRe = new double[N], lIm = new double[N];
        double[] rRe = new double[N], rIm = new double[N];

        // Per-bin center gain (precomputed): 1 below LO_OUTER, vocalK in the band, 1 above.
        double[] centerGain = new double[HALF];
        for (int b = 0; b < HALF; b++) {
            double f = (double) b * sr / N;
            double g;
            if (f < LO_OUTER) g = 1.0;
            else if (f < LO_INNER) g = 1.0 + (vocalK - 1.0) * 0.5 * (1 - Math.cos(Math.PI * (f - LO_OUTER) / (LO_INNER - LO_OUTER)));
            else if (f <= HI_INNER) g = vocalK;
            else if (f < HI_OUTER) g = vocalK + (1.0 - vocalK) * 0.5 * (1 - Math.cos(Math.PI * (f - HI_INNER) / (HI_OUTER - HI_INNER)));
            else g = 1.0;
            centerGain[b] = g;
        }

        for (int off = 0; off + N <= frames; off += HOP) {
            int idx = off * 2;
            for (int i = 0; i < N; i++) {
                lRe[i] = stereo[idx + i * 2] * win[i];
                lIm[i] = 0;
                rRe[i] = stereo[idx + i * 2 + 1] * win[i];
                rIm[i] = 0;
            }
            fft(lRe, lIm, false);
            fft(rRe, rIm, false);
            // Reconstruct with center scaled per-bin, side untouched.
            for (int b = 0; b < HALF; b++) {
                double g = centerGain[b];
                double cRe = (lRe[b] + rRe[b]) * 0.5 * g;
                double cIm = (lIm[b] + rIm[b]) * 0.5 * g;
                double sRe = (lRe[b] - rRe[b]) * 0.5;
                double sIm = (lIm[b] - rIm[b]) * 0.5;
                lRe[b] = cRe + sRe;
                lIm[b] = cIm + sIm;
                rRe[b] = cRe - sRe;
                rIm[b] = cIm - sIm;
            }
            // Rebuild the conjugate half (Hermitian symmetry) — zeroing it cost half the
            // spectrum (−6 dB on the whole band, which read as "25% bass kept").
            for (int b = 1; b < HALF - 1; b++) {
                lRe[N - b] = lRe[b]; lIm[N - b] = -lIm[b];
                rRe[N - b] = rRe[b]; rIm[N - b] = -rIm[b];
            }
            fft(lRe, lIm, true);
            fft(rRe, rIm, true);
            double scale = 1.0 / N;
            for (int i = 0; i < N && off + i < frames; i++) {
                double w = win[i];
                outL[off + i] += lRe[i] * scale * w;
                outR[off + i] += rRe[i] * scale * w;
                winSum[off + i] += w * w;
            }
        }

        float[] out = new float[stereo.length];
        for (int i = 0; i < frames; i++) {
            double ws = winSum[i] > 1e-9 ? winSum[i] : 1.0;
            out[i * 2] = (float) Math.max(-1.0, Math.min(1.0, outL[i] / ws));
            out[i * 2 + 1] = (float) Math.max(-1.0, Math.min(1.0, outR[i] / ws));
        }
        return out;
    }

    /** In-place radix-2 Cooley–Tukey FFT (N power of two). */
    private static void fft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wRe = Math.cos(ang), wIm = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double curRe = 1, curIm = 0;
                for (int j = 0; j < len / 2; j++) {
                    double uRe = re[i + j], uIm = im[i + j];
                    double vRe = re[i + j + len / 2] * curRe - im[i + j + len / 2] * curIm;
                    double vIm = re[i + j + len / 2] * curIm + im[i + j + len / 2] * curRe;
                    re[i + j] = uRe + vRe; im[i + j] = uIm + vIm;
                    re[i + j + len / 2] = uRe - vRe; im[i + j + len / 2] = uIm - vIm;
                    double newRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = newRe;
                }
            }
        }
    }
}
