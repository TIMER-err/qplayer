package dev.t1m3.qplayer.desktop.audio.automix;

import java.util.Arrays;

/**
 * Time-domain pitch-preserving time-stretch (WSOLA / SoundTouch-style overlap-add with
 * a cross-correlation seek). Self-written — no external DSP dependency.
 *
 * <p>Operates on interleaved float frames. Push input with {@link #putSamples}, pull
 * stretched output with {@link #receive}. The {@code tempo} factor is SoundTouch
 * semantics: {@code >1} = faster/shorter (consume more input per output), {@code <1} =
 * slower/longer. To play a {@code nativeBpm} track at {@code targetBpm} use
 * {@code tempo = targetBpm / nativeBpm}.
 *
 * <p>Each iteration emits one sequence of {@code seq-ovl} frames built as:
 * {@code [ovl overlap-add][seq-2·ovl straight copy]}, then keeps the trailing {@code ovl}
 * frames as the next overlap reference ("midBuffer"). The read cursor advances by
 * {@code tempo·(seq-ovl)} frames (fractional part carried), which sets the stretch ratio.
 * The overlap offset is chosen by maximising cross-correlation between the midBuffer and
 * the incoming block over a {@code seek}-frame search window, so waveforms splice in phase
 * and the pitch is preserved with minimal artefacts.
 */
public final class WsolaStretcher {

    private final int channels;
    private final int seq;    // sequence length (frames)
    private final int seek;   // seek search range (frames)
    private final int ovl;    // overlap length (frames)

    private float  tempo = 1f;
    private double nominalSkip;   // tempo·(seq-ovl)
    private double skipFract;

    private final float[] midBuffer;   // ovl·channels — trailing overlap reference
    private boolean midInit = false;

    private final Fifo input  = new Fifo();
    private final Fifo output = new Fifo();

    WsolaStretcher(int channels, int sampleRate) {
        this.channels = Math.max(1, channels);
        // SoundTouch-ish defaults, scaled to the sample rate.
        this.seq  = ms(sampleRate, 40f);
        this.seek = ms(sampleRate, 15f);
        this.ovl  = ms(sampleRate, 12f);
        this.midBuffer = new float[ovl * this.channels];
        setTempo(1f);
    }

    private static int ms(int sr, float millis) {
        return Math.max(1, Math.round(sr * millis / 1000f));
    }

    void setTempo(float t) {
        tempo = Math.max(0.25f, Math.min(4f, t));
        nominalSkip = tempo * (seq - ovl);
    }

    /** Discard all buffered state (call on seek / track change). */
    void reset() {
        input.clear();
        output.clear();
        midInit = false;
        skipFract = 0.0;
        Arrays.fill(midBuffer, 0f);
    }

    /** Push {@code frames} interleaved frames of input. */
    void putSamples(float[] interleaved, int frames) {
        input.put(interleaved, 0, frames * channels);
        process();
    }

    /** Number of stretched frames currently available to {@link #receive}. */
    int available() { return output.size / channels; }

    /** Drain up to {@code maxFrames} stretched frames into {@code dst}; returns frames written. */
    int receive(float[] dst, int maxFrames) {
        int n = Math.min(output.size / channels, maxFrames);
        if (n <= 0) return 0;
        System.arraycopy(output.buf, 0, dst, 0, n * channels);
        output.remove(n * channels);
        return n;
    }

    private void process() {
        int reqFrames = seq + seek;              // must be able to seek a full sequence
        while (input.size / channels >= reqFrames) {
            int offset = (midInit && seek > 0) ? seekBestOverlap() : 0;

            // 1. Overlap-add ovl frames of the previous tail with the new block.
            if (midInit) {
                for (int i = 0; i < ovl; i++) {
                    float w = (float) i / ovl;    // 0→1 linear cross-fade
                    int mi = i * channels;
                    int si = (offset + i) * channels;
                    for (int c = 0; c < channels; c++)
                        output.put(midBuffer[mi + c] * (1f - w) + input.buf[si + c] * w);
                }
            } else {
                // First sequence: no tail to blend, emit the overlap region straight.
                int si = offset * channels;
                output.put(input.buf, si, ovl * channels);
                midInit = true;
            }

            // 2. Straight copy of the middle (seq - 2·ovl frames).
            int flat = seq - 2 * ovl;
            if (flat > 0)
                output.put(input.buf, (offset + ovl) * channels, flat * channels);

            // 3. Keep the trailing ovl frames as the next overlap reference.
            System.arraycopy(input.buf, (offset + seq - ovl) * channels,
                    midBuffer, 0, ovl * channels);

            // 4. Advance the read cursor by tempo·(seq-ovl), carrying the fraction.
            int skip = (int) (nominalSkip + skipFract);
            skipFract += nominalSkip - skip;
            if (skip < 1) skip = 1;
            input.remove(skip * channels);
        }
    }

    /** Best overlap offset in [0,seek]: maximises normalised cross-correlation. */
    private int seekBestOverlap() {
        int best = 0;
        double bestCorr = Double.NEGATIVE_INFINITY;
        for (int o = 0; o <= seek; o++) {
            double corr = 0.0, norm = 0.0;
            int baseO = o * channels;
            for (int i = 0; i < ovl; i++) {
                int mi = i * channels;
                int si = baseO + i * channels;
                for (int c = 0; c < channels; c++) {
                    float m = midBuffer[mi + c];
                    float s = input.buf[si + c];
                    corr += (double) m * s;
                    norm += (double) s * s;
                }
            }
            // Normalise so a merely-loud region doesn't always win over a well-matched one.
            double score = norm > 1e-9 ? corr / Math.sqrt(norm) : corr;
            if (score > bestCorr) { bestCorr = score; best = o; }
        }
        return best;
    }

    /** Minimal growable interleaved-float FIFO (compacting on remove from the front). */
    private static final class Fifo {
        float[] buf = new float[8192];
        int size = 0;   // valid floats [0,size)

        void ensure(int cap) {
            if (buf.length < cap) buf = Arrays.copyOf(buf, Math.max(cap, buf.length * 2));
        }
        void put(float v) { ensure(size + 1); buf[size++] = v; }
        void put(float[] src, int off, int n) {
            ensure(size + n);
            System.arraycopy(src, off, buf, size, n);
            size += n;
        }
        void remove(int n) {
            if (n >= size) { size = 0; return; }
            System.arraycopy(buf, n, buf, 0, size - n);
            size -= n;
        }
        void clear() { size = 0; }
    }
}
