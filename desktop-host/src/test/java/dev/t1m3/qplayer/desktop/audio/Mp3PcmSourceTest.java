package dev.t1m3.qplayer.desktop.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Header;
import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** Real codec regression; CI supplies an eight-second libmp3lame VBR fixture. */
public class Mp3PcmSourceTest {
    @Test(timeout = 15_000)
    public void repeatedVbrSeeksMatchSequentialPcmIncludingReservoirLeadIn() throws Exception {
        String fixture = System.getProperty("qplayer.test.mp3");
        Assume.assumeNotNull(fixture);
        Path file = Path.of(fixture);
        assertTrue("MP3 fixture must exist", Files.isRegularFile(file));
        assertVariableBitrate(file);

        byte[] sequential;
        int rate;
        int channels;
        try (PcmSource source = new Mp3PcmSource(new FileByteSource(fixture))) {
            rate = source.sampleRate();
            channels = source.channels();
            sequential = readAll(source);
        }
        int frameBytes = channels * 2;
        assertTrue("fixture must contain at least seven seconds of decoded audio",
                sequential.length / frameBytes >= rate * 7);
        assertAudible(sequential);

        CountingSource bytes = new CountingSource(new FileByteSource(fixture));
        try (PcmSource source = new Mp3PcmSource(bytes)) {
            assertSeekMatchesSequential(source, sequential, 6_200, rate, channels);
            bytes.readBytes = 0;
            assertSeekMatchesSequential(source, sequential, 5_600, rate, channels);
            assertTrue("revisiting indexed audio must not reread most of the compressed fixture",
                    bytes.readBytes <= Math.max(16 * 1024L, Files.size(file) / 2));
            assertSeekMatchesSequential(source, sequential, 1_300, rate, channels);
            assertSeekMatchesSequential(source, sequential, 6_500, rate, channels);
            assertSeekMatchesSequential(source, sequential, 750, rate, channels);
        }
    }

    private static void assertVariableBitrate(Path file) throws Exception {
        Set<Integer> rates = new HashSet<>();
        try (var input = Files.newInputStream(file)) {
            Bitstream stream = new Bitstream(input);
            try {
                Header header;
                int frames = 0;
                while ((header = stream.readFrame()) != null) {
                    // Ignore the Xing/header lead-in when proving actual audio is VBR.
                    if (frames++ >= 10) rates.add(header.bitrate_index());
                    stream.closeFrame();
                }
            } finally { stream.close(); }
        }
        assertTrue("fixture must contain multiple audio-frame bitrates", rates.size() > 1);
    }

    private static void assertSeekMatchesSequential(PcmSource source, byte[] sequential,
                                                     long targetMs, int rate, int channels) throws Exception {
        long landedMs = source.seek(targetMs);
        assertTrue("seek must stay near its requested time", landedMs >= targetMs - 1 && landedMs <= targetMs + 35);
        byte[] sought = new byte[(rate / 4) * channels * 2];
        int got = 0;
        while (got < sought.length) {
            int n = source.read(sought, got, sought.length - got);
            assertTrue("seek must produce the complete comparison window", n > 0);
            got += n;
        }
        assertAudible(sought);

        // The API reports whole milliseconds, while MP3 boundaries are exact
        // sample positions. Compare every possible alignment within that one
        // reported millisecond, not an arbitrary neighbourhood of the target.
        int firstFrame = Math.max(0, (int) (landedMs * rate / 1000L) - 1);
        int lastFrame = (int) (((landedMs + 1) * rate + 999) / 1000L) + 1;
        long bestSquaredError = Long.MAX_VALUE;
        int bestMaximumError = Integer.MAX_VALUE;
        for (int frame = firstFrame; frame <= lastFrame; frame++) {
            int offset = frame * channels * 2;
            if (offset + sought.length > sequential.length) break;
            long squaredError = 0;
            int maximumError = 0;
            for (int i = 0; i < sought.length; i += 2) {
                int difference = sample(sought, i) - sample(sequential, offset + i);
                squaredError += (long) difference * difference;
                maximumError = Math.max(maximumError, Math.abs(difference));
            }
            if (squaredError < bestSquaredError) {
                bestSquaredError = squaredError;
                bestMaximumError = maximumError;
            }
        }
        assertTrue("seek PCM must match sequential decoding at " + targetMs + "ms (including its first samples)",
                bestSquaredError <= (sought.length / 2L) * 4L && bestMaximumError <= 8);
    }

    private static byte[] readAll(PcmSource source) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        byte[] block = new byte[16_384];
        int n;
        while ((n = source.read(block, 0, block.length)) >= 0) {
            assertTrue("non-empty PCM reads must make progress", n > 0);
            decoded.write(block, 0, n);
        }
        return decoded.toByteArray();
    }

    private static void assertAudible(byte[] pcm) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) peak = Math.max(peak, Math.abs(sample(pcm, i)));
        assertTrue("silent fabricated frames cannot exercise reservoir/PCM correctness", peak > 256);
    }

    private static int sample(byte[] pcm, int offset) {
        return (short) ((pcm[offset] & 255) | (pcm[offset + 1] << 8));
    }

    private static final class CountingSource implements SeekableByteSource {
        private final SeekableByteSource delegate;
        private long readBytes;
        private CountingSource(SeekableByteSource delegate) { this.delegate = delegate; }
        @Override public int read(byte[] dst, int off, int len) throws IOException {
            int n = delegate.read(dst, off, len);
            if (n > 0) readBytes += n;
            return n;
        }
        @Override public void seek(long position) throws IOException { delegate.seek(position); }
        @Override public long position() { return delegate.position(); }
        @Override public long size() { return delegate.size(); }
        @Override public File backingFile() { return delegate.backingFile(); }
        @Override public void close() { delegate.close(); }
    }
}
