package dev.t1m3.qplayer.desktop.audio;

import java.io.IOException;

/**
 * A decoded-audio stream: pulls 16-bit signed little-endian interleaved PCM out
 * of a compressed source (mp3/flac/ogg/wav) and supports real seeking. One per
 * playing track; owned by the audio thread. The 16-bit output matches OpenAL's
 * {@code AL_FORMAT_*16} buffer formats directly.
 */
public interface PcmSource extends AutoCloseable {

    int sampleRate();

    /** 1 (mono) or 2 (stereo). */
    int channels();

    /**
     * Read up to {@code len} bytes of interleaved 16-bit LE PCM into {@code dst}.
     * Returns the byte count (> 0), or -1 at end of stream. {@code len} is a
     * multiple of the frame size on the hot path; implementations may return
     * fewer bytes than asked.
     */
    int read(byte[] dst, int off, int len) throws IOException;

    /** Seek to {@code ms} (clamped to the track). @return the ms actually landed on. */
    long seek(long ms) throws IOException;

    /** Best-effort duration in ms, or 0 if unknown. */
    long durationMs();

    @Override
    void close();

    /** Open the right decoder for {@code src}, sniffing the container by magic
     *  bytes (URLs often lack a usable extension). */
    static PcmSource open(String src) throws IOException {
        SeekableByteSource bytes = SeekableByteSource.open(src);
        try {
            switch (sniff(bytes)) {
                case OGG:  return new OggPcmSource(bytes);
                case FLAC: return new FlacPcmSource(bytes);
                case WAV:  return new WavPcmSource(bytes);
                default:   return new Mp3PcmSource(bytes);
            }
        } catch (IOException | RuntimeException e) {
            bytes.close();
            throw e;
        }
    }

    enum Container { MP3, OGG, FLAC, WAV }

    /** Peek the leading magic bytes, then rewind. Falls back to MP3 (the most
     *  common netease payload, and the only one without a fixed magic). */
    private static Container sniff(SeekableByteSource bytes) throws IOException {
        byte[] head = new byte[12];
        bytes.seek(0);
        int got = 0;
        while (got < head.length) {
            int n = bytes.read(head, got, head.length - got);
            if (n < 0) break;
            got += n;
        }
        bytes.seek(0);
        if (got >= 4) {
            if (head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') return Container.OGG;
            if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') return Container.FLAC;
            if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F') return Container.WAV;
        }
        return Container.MP3;
    }
}
