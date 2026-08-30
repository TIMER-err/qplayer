package dev.t1m3.qplayer.automix;

/**
 * Key (tonality) detection for the automix pipeline — the "调性" dimension the tuning
 * notes call for. Chroma vector over a mono window, correlated against the 24 rotated
 * Krumhansl–Schmuckler key profiles; the best match is the estimated key.
 *
 * <p>Used for harmonic-compatibility evaluation (circle-of-fifths distance between the
 * two tracks' keys) — the basis for deciding whether a transition will sound consonant
 * and, per the design doc §14, whether a spectral glide is even desirable.
 */
public final class KeyDetector {

    /** Krumhansl–Schmuckler key profiles (C major / A minor templates). */
    private static final double[] KS_MAJOR = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    private static final double[] KS_MINOR = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};
    private static final String[] NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    public static final class Key {
        public final int root;
        public final boolean minor;
        public final double confidence;

        Key(int root, boolean minor, double confidence) {
            this.root = root;
            this.minor = minor;
            this.confidence = confidence;
        }

        public int root() { return root; }
        public boolean minor() { return minor; }
        public double confidence() { return confidence; }
        public String label() { return NAMES[root] + (minor ? "m" : ""); }
    }

    private KeyDetector() {}

    /**
     * Detect the dominant key of a mono window (chroma + Krumhansl–Schmuckler).
     *
     * @return the key, or {@code null} if the window is too short or silent.
     */
    public static Key detect(float[] mono, int sr) {
        if (mono == null || mono.length < sr / 4) return null;
        int N = 4096;
        if (N > mono.length) N = Integer.highestOneBit(mono.length);
        if (N < 512) return null;
        int hop = N / 2;
        double[] chroma = new double[12];
        double[] re = new double[N], im = new double[N];
        int frames = 0;
        for (int off = 0; off + N <= mono.length; off += hop) {
            for (int i = 0; i < N; i++) {
                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));   // Hann
                re[i] = mono[off + i] * w;
                im[i] = 0;
            }
            fft(re, im, false);
            for (int b = 1; b <= N / 2; b++) {
                double freq = (double) b * sr / N;
                double mag = Math.hypot(re[b], im[b]);
                if (mag < 1e-6) continue;
                // pitch class of this bin (A4 = 440 Hz, C = 0)
                double pc = 12.0 * (Math.log(freq / 440.0) / Math.log(2.0)) + 9.0;
                int idx = ((int) Math.round(pc)) % 12;
                if (idx < 0) idx += 12;
                chroma[idx] += mag;
            }
            frames++;
        }
        if (frames == 0) return null;
        double total = 0;
        for (double v : chroma) total += v;
        if (total < 1e-6) return null;
        for (int i = 0; i < 12; i++) chroma[i] /= total;

        // Correlate with all 24 rotated profiles; confidence = relative margin over 2nd best.
        double best = Double.NEGATIVE_INFINITY, second = Double.NEGATIVE_INFINITY;
        int bestRoot = 0;
        boolean bestMinor = false;
        for (int minor = 0; minor <= 1; minor++) {
            double[] prof = minor == 0 ? KS_MAJOR : KS_MINOR;
            double profEnergy = 0;
            for (double v : prof) profEnergy += v * v;
            double scale = 1.0 / Math.sqrt(profEnergy);
            for (int r = 0; r < 12; r++) {
                double corr = 0;
                for (int i = 0; i < 12; i++) corr += chroma[i] * prof[(i + 12 - r) % 12];
                corr *= scale;
                if (corr > best) { second = best; best = corr; bestRoot = r; bestMinor = minor == 1; }
                else if (corr > second) second = corr;
            }
        }
        double conf = Math.max(0.0, (best - second) / Math.max(1e-9, Math.abs(best)));
        return new Key(bestRoot, bestMinor, Math.min(1.0, conf));
    }

    /**
     * Harmonic distance on the circle of fifths (Krumhansl-style): 0 = same key,
     * 1 = adjacent fifth, ... 6 = tritone. Different modes add a penalty. -1 if either
     * key is unknown. Lower = more compatible.
     */
    public static int harmonicDistance(Key a, Key b) {
        if (a == null || b == null) return -1;
        // position on the circle of fifths: root*7 mod 12 (0=C, 7=G, 2=D, ...)
        int posA = (a.root() * 7) % 12;
        int posB = (b.root() * 7) % 12;
        int d = Math.abs(posA - posB);
        if (d > 6) d = 12 - d;                 // fifths steps (0-6)
        return d + (a.minor() == b.minor() ? 0 : 2);   // mode change = 2 more fifths-steps
    }

    /** Compatibility in [0,1] (1 = perfectly consonant). -1 if a key is unknown. */
    public static double compatibility(Key a, Key b) {
        int d = harmonicDistance(a, b);
        if (d < 0) return -1.0;
        return Math.max(0.0, 1.0 - d / 8.0);
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
