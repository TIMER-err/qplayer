package dev.t1m3.qplayer.desktop.audio;

import net.sourceforge.jaad.SampleBuffer;
import net.sourceforge.jaad.aac.Decoder;

import java.io.IOException;

/** AAC in ordinary or fragmented MP4, decoded to OpenAL's interleaved 16-bit LE PCM. */
final class AacPcmSource implements PcmSource {

    private final SeekableByteSource src;
    private final Mp4AacTrack track;
    private final SampleBuffer buffer = new SampleBuffer();
    private Decoder decoder;
    private int sampleRate;
    private int channels;
    private byte[] pending = new byte[0];
    private int pendingPos;
    private long pendingTimeMs;
    private boolean eof;

    @Override public boolean hasBuffered(int n) { return src.hasBuffered(n); }

    AacPcmSource(SeekableByteSource src) throws IOException {
        this.src = src;
        track = new Mp4AacTrack(src);
        buffer.setBigEndian(false);
        resetDecoder();
        // The decoded format can differ from the MP4 entry (for example HE-AAC).
        if (!fill()) throw new IOException("empty AAC track in MP4");
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
        if (len <= 0) return 0;
        if (pendingPos >= pending.length && !fill()) return -1;
        int count = Math.min(len, pending.length - pendingPos);
        System.arraycopy(pending, pendingPos, dst, off, count);
        pendingPos += count;
        return count;
    }

    private boolean fill() throws IOException {
        if (eof) return false;
        Mp4AacTrack.Frame frame = track.nextFrame();
        if (frame == null) {
            pending = new byte[0];
            pendingPos = 0;
            eof = true;
            return false;
        }
        try {
            // JAAD can swallow a truncated-frame exception. Clear its output so
            // that a corrupt packet cannot replay the previous packet's PCM.
            buffer.setData(new byte[0], 0, 0, 0, 0);
            decoder.decodeFrame(frame.data, (samples, length, rate) -> {
                // JAAD reserves stereo output for possible parametric stereo by
                // adding the same array twice for mono. Preserve true mono;
                // decoded stereo channels have distinct arrays.
                if (samples.size() == 2 && samples.get(0) == samples.get(1)) {
                    samples = samples.subList(0, 1);
                }
                buffer.accept(samples, length, rate);
            });
        } catch (RuntimeException e) {
            throw new IOException("AAC decode failed", e);
        }
        if (buffer.getData().length == 0) throw new IOException("empty or truncated AAC frame");
        int rate = buffer.getSampleRate();
        int count = buffer.getChannels();
        if (rate <= 0 || count < 1 || count > 2 || buffer.getBitsPerSample() != 16) {
            throw new IOException("unsupported AAC output: " + rate + " Hz, " + count + " channels");
        }
        if (sampleRate != 0 && (sampleRate != rate || channels != count)) {
            throw new IOException("AAC output format changed during playback");
        }
        sampleRate = rate;
        channels = count;
        pending = buffer.getData();
        pendingPos = 0;
        pendingTimeMs = frame.timeMs;
        return true;
    }

    @Override
    public long seek(long ms) throws IOException {
        long target = Math.max(0, ms);
        long duration = durationMs();
        if (duration > 0) target = Math.min(target, duration);
        pending = new byte[0];
        pendingPos = 0;
        eof = false;
        resetDecoder();
        if (duration > 0 && target == duration) {
            eof = true;
            return duration;
        }
        // Rebuild AAC overlap/SBR state from a few preceding packets after a
        // jump, then discard PCM up to the requested position.
        track.seek(Math.max(0, target - 100));
        while (fill()) {
            long skipMs = Math.max(0, target - pendingTimeMs);
            int frames = pending.length / (channels * 2);
            if (skipMs >= (frames * 1000L + sampleRate - 1) / sampleRate) continue;
            pendingPos = (int) Math.min(frames, skipMs * sampleRate / 1000L) * channels * 2;
            if (pendingPos < pending.length) return Math.max(target, pendingTimeMs);
        }
        return track.durationMs();
    }

    private void resetDecoder() throws IOException {
        try {
            decoder = Decoder.create(track.decoderConfig());
        } catch (RuntimeException e) {
            throw new IOException("unsupported or malformed AAC configuration", e);
        }
    }

    @Override
    public long durationMs() {
        return track.durationMs();
    }

    @Override
    public void close() {
        src.close();
    }
}
