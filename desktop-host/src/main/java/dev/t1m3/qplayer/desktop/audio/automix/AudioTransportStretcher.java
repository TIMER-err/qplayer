package dev.t1m3.qplayer.desktop.audio.automix;

/**
 * Pure-Java port of audio_transport (github.com/sportdeath/audio_transport).
 *
 * Given two mono PCM frames arrays (leftAudio / rightAudio at the same sample rate),
 * produces an interpolated output where frequencies in one signal "slide" to
 * frequencies in the other via 1-D optimal transport — the classic portamento/glide
 * transition.  interpolation=0 → sounds like left; 1 → sounds like right.
 *
 * Pipeline (mirrors the C++ library exactly):
 *   1. analysis()  — windowed STFT with reassigned frequency/time (3 transforms per window)
 *   2. equal_loudness — A-weighting applied before transport, removed after
 *   3. interpolate() — per-window optimal-transport spectral mass assignment
 *   4. synthesis()  — overlap-add ISTFT
 *
 * The FFT is a self-contained radix-2 Cooley–Tukey (no external dependencies);
 * {@link #interpolate} rounds the analysis window down to a power of two so the
 * padded FFT length is always a power of two.
 * Only the real→complex forward transform and complex→real inverse are needed.
 */
public final class AudioTransportStretcher {

    // ── tuneable constants (match the C++ example/transport.cpp) ────────────
    /** 50 ms analysis window is the documented default (spec §12.1, range 0.03-0.08s);
     *  the live value is runtime-tunable via {@link MusicSettings#effectiveAutomixWindowSec()}
     *  (SW_AUTOMIX_ENHANCED swaps in the tuned preset). */
    private static final double WINDOW_SIZE_SEC_DEFAULT = 0.05;
    private static final int    PADDING         = 7;     // zero-pad multiplier
    private static final int    OVERLAP         = 1;     // overlap factor

    // ── spectral point ───────────────────────────────────────────────────────
    private static final class Point {
        double valueRe, valueIm;
        double freq;
        double freqReassigned;
        // Precomputed once per window (after A-weighting) so the hot paths
        // (groupSpectrum / placeMass) avoid a per-bin atan2 + hypot — the single
        // biggest cost in the original port.
        double mag;
        double phase;
    }

    // ── spectral mass (for optimal transport grouping) ───────────────────────
    private static final class Mass {
        int leftBin, rightBin, centerBin;
        double mass;
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Compute the audio-transport interpolation between {@code leftAudio} and
     * {@code rightAudio} (both mono, same {@code sampleRate}).
     * {@code interpolation} in [0,1]: 0 = left, 1 = right.
     *
     * Returns mono PCM doubles aligned with min(left,right) length.
     */
    public static double[] interpolate(double[] leftAudio, double[] rightAudio,
                                int sampleRate, double interpolation) {
        return interpolate(leftAudio, rightAudio, sampleRate, interpolation, interpolation);
    }

    /**
     * Like {@link #interpolate(double[], double[], int, double)} but ramps the
     * interpolation factor from {@code interpStart} to {@code interpEnd} across the
     * analysis windows, so a single call over the whole transition glides A → B in
     * time. (The earlier chunked caller used to perform this ramp call-by-call,
     * which lost phase continuity at every chunk boundary.)
     */
    public static double[] interpolate(double[] leftAudio, double[] rightAudio,
                                int sampleRate, double interpStart, double interpEnd) {
        return interpolate(leftAudio, rightAudio, sampleRate, interpStart, interpEnd,
                WINDOW_SIZE_SEC_DEFAULT);
    }

    /**
     * Explicit-window variant — used by the tuning harness so it can A/B the
     * analysis window without mutating global settings. {@code windowSec} is the
     * STFT analysis window in seconds (default 0.05 when ≤ 0).
     */
    public static double[] interpolate(double[] leftAudio, double[] rightAudio,
                                int sampleRate, double interpStart, double interpEnd,
                                double windowSec) {
        if (windowSec <= 0.0) windowSec = WINDOW_SIZE_SEC_DEFAULT;
        int N = (int) Math.round(windowSec * sampleRate);
        // The padded FFT length (N * (1 + PADDING)) must be a power of two — fft()
        // is a plain radix-2 Cooley–Tukey and is only correct for power-of-two sizes.
        // Rounding N DOWN to a power of two makes Npadded one too (N = 2^k → Npadded
        // = 2^(k+3)). Previously N was only made even, so e.g. 44.1 kHz gave N=2206,
        // Npadded=17648 — not a power of two — and every FFT silently computed garbage.
        N = Integer.highestOneBit(N);
        // At least one analysis window must fit: numWindows = numHops - 1 >= 1
        // requires input length >= 2 * hopSize == N. If the caller handed us shorter
        // buffers (EOF tail), shrink the window so one window still fits.
        int fit = Math.min(leftAudio.length, rightAudio.length);
        while (N > fit) N >>= 1;
        if (N < 64) return new double[0];   // degenerate input — caller falls back

        int Npadded = N * (1 + PADDING);
        int fftSize = Npadded / 2 + 1;

        int hopSize   = N / (2 * OVERLAP);
        int numHopsL  = leftAudio.length  / hopSize;
        int numHopsR  = rightAudio.length / hopSize;
        int numWindows = Math.min(numHopsL, numHopsR) - (2 * OVERLAP - 1);
        if (numWindows <= 0) return new double[0];

        Point[][] pointsL = analysis(leftAudio,  sampleRate, N, Npadded, fftSize);
        Point[][] pointsR = analysis(rightAudio, sampleRate, N, Npadded, fftSize);

        equalLoudnessApply(pointsL);
        equalLoudnessApply(pointsR);
        cacheMagPhase(pointsL);
        cacheMagPhase(pointsR);

        int numW = Math.min(pointsL.length, pointsR.length);
        Point[][] pointsOut = new Point[numW][];
        double[] phases = new double[fftSize];

        for (int w = 0; w < numW; w++) {
            double interp = (numW <= 1) ? interpStart
                    : interpStart + (interpEnd - interpStart) * w / (numW - 1);
            pointsOut[w] = transportInterpolate(
                    pointsL[w], pointsR[w], phases, windowSec, interp);
        }

        equalLoudnessRemove(pointsOut);
        return synthesis(pointsOut, Npadded, fftSize);
    }

    // ── spectral analysis (reassigned STFT) ──────────────────────────────────

    private static Point[][] analysis(double[] audio, int sr, int N, int Npadded, int fftSize) {
        int paddingSamples = (Npadded - N) / 2;
        int hopSize = N / (2 * OVERLAP);
        int numHops = audio.length / hopSize;
        int numWindows = numHops - (2 * OVERLAP - 1);
        if (numWindows <= 0) return new Point[0][];

        double[] winBuf  = new double[Npadded];
        double[] winBufT = new double[Npadded];
        double[] winBufD = new double[Npadded];

        Point[][] result = new Point[numWindows][];

        for (int w = 0; w < numWindows; w++) {
            for (int i = 0; i < N; i++) {
                double n = i - (N - 1) / 2.0;
                int ai = i + w * hopSize;
                double a = (ai < audio.length) ? audio[ai] : 0.0;
                int pi = i + paddingSamples;
                winBuf [pi] = a * hann (n, N);
                winBufT[pi] = a * hannT(n, N, sr);
                winBufD[pi] = a * hannD(n, N, sr);
            }

            double[] fftRe  = new double[Npadded], fftIm  = new double[Npadded];
            double[] fftReT = new double[Npadded], fftImT = new double[Npadded];
            double[] fftReD = new double[Npadded], fftImD = new double[Npadded];
            rfft(winBuf,  fftRe,  fftIm);
            rfft(winBufT, fftReT, fftImT);
            rfft(winBufD, fftReD, fftImD);

            double t = ((N - 1) / 2.0 + w * hopSize) / sr;
            result[w] = new Point[fftSize];

            for (int i = 0; i < fftSize; i++) {
                double xRe = fftRe[i],  xIm = fftIm[i];
                double xtRe = fftReT[i], xtIm = fftImT[i];
                double xdRe = fftReD[i], xdIm = fftImD[i];

                double norm2 = xRe * xRe + xIm * xIm;
                Point p = new Point();
                p.valueRe = xRe; p.valueIm = xIm;
                p.freq = (2 * Math.PI * i * sr) / (double) Npadded;

                if (norm2 > 1e-30) {
                    // conj(X)/|X|^2: (xRe - i*xIm) / norm2
                    double conjRe = xRe / norm2, conjIm = -xIm / norm2;
                    // dphase_domega = Re( X_t * conj(X)/|X|^2 )
                    double dpdo = xtRe * conjRe - xtIm * conjIm;
                    // dphase_dt    = -Im( X_d * conj(X)/|X|^2 )
                    double dpdt = -(xdRe * conjIm + xdIm * conjRe);  // -Im(...)
                    // actually: -Im(X_d * conjOverNorm) = -(xdRe*conjIm + xdIm*conjRe)
                    // wait, Im(a*b) = a.re*b.im + a.im*b.re
                    dpdt = -(xdRe * conjIm + xdIm * conjRe);
                    p.freqReassigned = p.freq + dpdt;
                } else {
                    p.freqReassigned = p.freq;
                }
                result[w][i] = p;
            }
        }
        return result;
    }

    // ── optimal transport interpolation (per window) ─────────────────────────

    private static Point[] transportInterpolate(Point[] left, Point[] right,
                                                double[] phases, double windowSizeSec,
                                                double interp) {
        Mass[] lMasses = groupSpectrum(left);
        Mass[] rMasses = groupSpectrum(right);
        transportMatrix(lMasses, rMasses);

        Point[] out = new Point[left.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = new Point();
            out[i].freq = left[i].freq;
        }
        double[] newAmplitudes = new double[phases.length];
        double[] newPhases     = new double[phases.length];
        for (int k = 0; k < _tLen; k++) {
            int li = _tLeft[k], ri = _tRight[k];
            Mass lm = lMasses[li], rm = rMasses[ri];
            double tm = _tMass[k];

            int interpBin = (int) Math.round(
                    (1 - interp) * lm.centerBin + interp * rm.centerBin);

            double interpRounded = interp;
            if (lm.centerBin != rm.centerBin) {
                interpRounded = ((double) interpBin - lm.centerBin)
                        / (double) (rm.centerBin - lm.centerBin);
            }
            double interpFreq =
                    (1 - interpRounded) * left [lm.centerBin].freqReassigned
                  + interpRounded       * right[rm.centerBin].freqReassigned;

            double centerPhase = phases[interpBin]
                    + (interpFreq * windowSizeSec / 2.0) / 2.0
                    - (Math.PI * interpBin);
            double newPhase = centerPhase
                    + (interpFreq * windowSizeSec / 2.0) / 2.0
                    + (Math.PI * interpBin);

            placeMass(lm, interpBin, (1 - interp) * tm / lm.mass,
                    interpFreq, centerPhase, left,  out, newPhase, newPhases, newAmplitudes);
            placeMass(rm, interpBin,      interp  * tm / rm.mass,
                    interpFreq, centerPhase, right, out, newPhase, newPhases, newAmplitudes);
        }
        for (int i = 0; i < phases.length; i++) phases[i] = newPhases[i];
        return out;
    }

    // Shared scratch for transportMatrix output to avoid allocation per window (the
    // whole transition is processed on a single thread, so no thread-local needed).
    private static int[]    _tLeft  = new int   [4096];
    private static int[]    _tRight = new int   [4096];
    private static double[] _tMass  = new double[4096];
    private static int      _tLen   = 0;

    private static void transportMatrix(Mass[] left, Mass[] right) {
        int maxLen = left.length + right.length;
        if (_tLeft.length < maxLen) {
            _tLeft  = new int   [maxLen * 2];
            _tRight = new int   [maxLen * 2];
            _tMass  = new double[maxLen * 2];
        }
        int li = 0, ri = 0, len = 0;
        double lm = left[0].mass, rm = right[0].mass;
        while (true) {
            if (lm < rm) {
                _tLeft[len] = li; _tRight[len] = ri; _tMass[len] = lm; len++;
                rm -= lm;
                li++;
                if (li >= left.length) break;
                lm = left[li].mass;
            } else {
                _tLeft[len] = li; _tRight[len] = ri; _tMass[len] = rm; len++;
                lm -= rm;
                ri++;
                if (ri >= right.length) break;
                rm = right[ri].mass;
            }
        }
        _tLen = len;
    }

    private static void placeMass(Mass mass, int centerBin, double scale,
                                  double interpFreq, double centerPhase,
                                  Point[] input, Point[] output,
                                  double nextPhase, double[] phases, double[] amplitudes) {
        double phaseShift = centerPhase - input[mass.centerBin].phase;
        for (int i = mass.leftBin; i < mass.rightBin; i++) {
            int newI = i + centerBin - mass.centerBin;
            if (newI < 0 || newI >= output.length) continue;
            double phase   = phaseShift + input[i].phase;
            double mag     = scale * input[i].mag;
            output[newI].valueRe += mag * Math.cos(phase);
            output[newI].valueIm += mag * Math.sin(phase);
            if (mag > amplitudes[newI]) {
                amplitudes[newI] = mag;
                phases[newI] = nextPhase;
                output[newI].freqReassigned = interpFreq;
            }
        }
    }

    /** Precompute per-bin magnitude/phase once per window (hot-path cache). */
    private static void cacheMagPhase(Point[][] pts) {
        for (Point[] w : pts) for (Point p : w) {
            p.mag   = Math.hypot(p.valueRe, p.valueIm);
            p.phase = Math.atan2(p.valueIm, p.valueRe);
        }
    }

    private static Mass[] groupSpectrum(Point[] spectrum) {
        double massSum = 0;
        for (Point p : spectrum) massSum += p.mag;
        if (massSum < 1e-30) {
            Mass m = new Mass();
            m.leftBin = 0; m.rightBin = spectrum.length; m.centerBin = 0; m.mass = 1.0;
            return new Mass[]{m};
        }

        java.util.ArrayList<Mass> masses = new java.util.ArrayList<>();
        Mass cur = new Mass(); cur.leftBin = 0; cur.centerBin = 0;
        masses.add(cur);

        boolean sign = false, first = true;
        for (int i = 0; i < spectrum.length; i++) {
            boolean curSign = spectrum[i].freqReassigned > spectrum[i].freq;
            if (first) { first = false; sign = curSign; continue; }
            if (curSign == sign) continue;
            if (sign) {
                // falling edge → center bin
                double ld = spectrum[i-1].freqReassigned - spectrum[i-1].freq;
                double rd = spectrum[i].freq - spectrum[i].freqReassigned;
                masses.get(masses.size()-1).centerBin = (ld < rd) ? i-1 : i;
            } else {
                // rising edge → end of current mass
                Mass last = masses.get(masses.size()-1);
                last.mass = 0;
                for (int j = last.leftBin; j < i; j++)
                    last.mass += spectrum[j].mag;
                if (last.mass > 0) {
                    last.mass /= massSum;
                    last.rightBin = i;
                    Mass next = new Mass(); next.leftBin = i; next.centerBin = i;
                    masses.add(next);
                }
            }
            sign = curSign;
        }
        Mass last = masses.get(masses.size()-1);
        last.rightBin = spectrum.length;
        last.mass = 0;
        for (int j = last.leftBin; j < spectrum.length; j++)
            last.mass += spectrum[j].mag;
        last.mass /= massSum;
        return masses.toArray(new Mass[0]);
    }

    // ── synthesis (overlap-add ISTFT) ─────────────────────────────────────────

    private static double[] synthesis(Point[][] points, int Npadded, int fftSize) {
        int windowSize = Npadded / (1 + PADDING);
        int paddingSamples = (Npadded - windowSize) / 2;
        int hopSize = windowSize / (2 * OVERLAP);
        int numHops = points.length + 2 * OVERLAP - 1;
        double[] audio = new double[numHops * hopSize];

        double[] reX = new double[Npadded], imX = new double[Npadded];
        double[] timeOut = new double[Npadded];

        for (int w = 0; w < points.length; w++) {
            java.util.Arrays.fill(reX, 0); java.util.Arrays.fill(imX, 0);
            for (int i = 0; i < fftSize; i++) {
                reX[i] = points[w][i].valueRe;
                imX[i] = points[w][i].valueIm;
            }
            irfft(reX, imX, timeOut, Npadded);
            double scale = 1.0 / (OVERLAP * Npadded);
            for (int i = 0; i < windowSize; i++) {
                int ai = i + w * hopSize;
                if (ai < audio.length)
                    audio[ai] += timeOut[i + paddingSamples] * scale;
            }
        }
        return audio;
    }

    // ── equal loudness (A-weighting) ─────────────────────────────────────────

    private static double aWeight(double freq) {
        if (freq < 1e-6) return 0;
        double f = freq / (2 * Math.PI);
        double f2 = f * f;
        double top  = 12194.0 * 12194.0 * f2 * f2;
        double bot1 = 20.6 * 20.6 + f2;
        double bot2 = 107.7 * 107.7 + f2;
        double bot3 = 737.9 * 737.9 + f2;
        double bot4 = 12194.0 * 12194.0 + f2;
        return top / (bot1 * Math.sqrt(bot2 * bot3) * bot4);
    }

    private static void equalLoudnessApply(Point[][] pts) {
        for (Point[] w : pts) for (Point p : w) {
            double a = aWeight(p.freq);
            p.valueRe *= a; p.valueIm *= a;
        }
    }

    private static void equalLoudnessRemove(Point[][] pts) {
        for (Point[] w : pts) for (Point p : w) {
            double a = aWeight(p.freq);
            if (a > 1e-30) { p.valueRe /= a; p.valueIm /= a; }
        }
    }

    // ── window functions ──────────────────────────────────────────────────────

    private static double hann(double n, double N) {
        return 0.5 + 0.5 * Math.cos(2 * Math.PI * n / (N - 1));
    }
    private static double hannT(double n, double N, double sr) {
        return (n / sr) * hann(n, N);
    }
    private static double hannD(double n, double N, double sr) {
        return -(Math.PI * sr) / (N - 1) * Math.sin(2 * Math.PI * n / (N - 1));
    }

    // ── minimal real-input FFT ────────────────────────────────────────────────
    // radix-2 Cooley–Tukey — requires a power-of-two length, which interpolate()
    // guarantees by rounding the window size down to a power of two.
    // rfft: real[] → complex half-spectrum (bins 0..N/2)
    // irfft: complex half-spectrum → real[]

    private static void rfft(double[] x, double[] outRe, double[] outIm) {
        int N = x.length;
        // full complex DFT on the real input (imag = 0)
        double[] re = x.clone(), im = new double[N];
        fft(re, im, false);
        int half = N / 2 + 1;
        System.arraycopy(re, 0, outRe, 0, half);
        System.arraycopy(im, 0, outIm, 0, half);
    }

    private static void irfft(double[] inRe, double[] inIm, double[] out, int N) {
        double[] re = new double[N], im = new double[N];
        int half = N / 2 + 1;
        for (int i = 0; i < half; i++) {
            re[i] = inRe[i]; im[i] = inIm[i];
        }
        // Hermitian symmetry
        for (int i = 1; i < N / 2; i++) {
            re[N - i] =  re[i];
            im[N - i] = -im[i];
        }
        fft(re, im, true);
        System.arraycopy(re, 0, out, 0, N);
    }

    /** In-place Cooley-Tukey FFT. inverse=true computes IDFT (without 1/N scaling). */
    private static void fft(double[] re, double[] im, boolean inverse) {
        int N = re.length;
        // bit-reversal permutation
        for (int i = 1, j = 0; i < N; i++) {
            int bit = N >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= N; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wRe = Math.cos(ang), wIm = Math.sin(ang);
            for (int i = 0; i < N; i += len) {
                double curRe = 1, curIm = 0;
                for (int j = 0; j < len / 2; j++) {
                    double uRe = re[i+j], uIm = im[i+j];
                    double vRe = re[i+j+len/2]*curRe - im[i+j+len/2]*curIm;
                    double vIm = re[i+j+len/2]*curIm + im[i+j+len/2]*curRe;
                    re[i+j] = uRe+vRe; im[i+j] = uIm+vIm;
                    re[i+j+len/2] = uRe-vRe; im[i+j+len/2] = uIm-vIm;
                    double newRe = curRe*wRe - curIm*wIm;
                    curIm = curRe*wIm + curIm*wRe;
                    curRe = newRe;
                }
            }
        }
    }
}
