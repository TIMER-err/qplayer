package dev.t1m3.qplayer.desktop.audio;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AacPcmSourceTest {

    @Test(timeout = 10_000)
    public void decodesMonoM4aWithIndexAfterMedia() throws Exception {
        assertFile("mono-tail.m4a", 44_100, 1);
    }

    @Test(timeout = 10_000)
    public void decodesStereoMp4WithIndexBeforeMedia() throws Exception {
        assertFile("stereo-faststart.mp4", 48_000, 2);
    }

    @Test(timeout = 10_000)
    public void decodesEveryDashFragment() throws Exception {
        assertFile("stereo-dash.m4s", 44_100, 2);
    }

    @Test(timeout = 10_000)
    public void selectsAudioWhenVideoIsTheFirstTrack() throws Exception {
        assertFile("video-audio.mp4", 48_000, 2);
    }

    @Test(timeout = 10_000)
    public void selectsAudioInFragmentsWithVideoAndExplicitBaseDataOffsets() throws Exception {
        assertFile("video-audio-fragmented.mp4", 48_000, 2);
    }

    @Test(timeout = 10_000)
    public void decodesM4aWith64BitChunkOffsets() throws Exception {
        assertFile("mono-co64.m4a", 44_100, 1);
    }

    @Test(timeout = 10_000)
    public void seeksBeyondEndBeforeAllFragmentDurationsAreKnown() throws Exception {
        for (String name : new String[]{"stereo-dash.m4s", "video-audio-fragmented.mp4"}) {
            try (PcmSource source = PcmSource.open(fixturePath(name))) {
                long initialDuration = source.durationMs();
                assertTrue("initial duration must be unknown or complete, never just the first fragment",
                        initialDuration == 0 || (initialDuration >= 2_350 && initialDuration <= 2_500));
                long end = source.seek(60_000);
                assertTrue("seek must discover the final fragment in " + name + ", got " + end,
                        end >= 2_350 && end <= 2_500);
                assertTrue("complete duration is available after seeking to EOF",
                        Math.abs(end - source.durationMs()) <= 30);
                assertEquals("no samples remain after end seek", -1, source.read(new byte[4_096], 0, 4_096));
                assertSeekTone(source, 300, 440);
            }
        }
    }

    @Test(timeout = 10_000)
    public void seeksForwardBackwardAndToEndInOrdinaryAndFragmentedMp4() throws Exception {
        for (String name : new String[]{"mono-tail.m4a", "mono-co64.m4a", "stereo-faststart.mp4",
                "stereo-dash.m4s", "video-audio-fragmented.mp4"}) {
            try (PcmSource source = PcmSource.open(fixturePath(name))) {
                assertSeekTone(source, 1_100, 880);
                assertSeekTone(source, 300, 440);
                assertSeekTone(source, 2_000, 1_320);
                assertEquals("negative seeks clamp to start", 0, source.seek(-500));
                assertTone(readPcm(source, source.sampleRate() / 5), source.channels(),
                        source.sampleRate(), 440);
                // Fragmented duration can remain unknown until the final fragment is indexed.
                long end = source.seek(60_000);
                long duration = source.durationMs();
                assertTrue("known duration after seeking to EOF for " + name,
                        duration >= 2_350 && duration <= 2_500);
                assertTrue("end seek clamps to duration", Math.abs(end - duration) <= 30);
                assertEquals("no samples remain after end seek", -1, source.read(new byte[4_096], 0, 4_096));
                assertSeekTone(source, 300, 440);
            }
        }
    }

    @Test(timeout = 15_000)
    public void startsFaststartHttpPlaybackBeforeChunkedDownloadCompletes() throws Exception {
        assertChunkedHttp("stereo-faststart.mp4", 48_000, true);
    }

    @Test(timeout = 15_000)
    public void decodesDashFromExtensionlessHttpUrlWithRequestHeaders() throws Exception {
        assertChunkedHttp("stereo-dash.m4s", 44_100, true);
    }

    @Test(timeout = 10_000)
    public void rejectsBoxesSmallerThanTheirHeaders() throws Exception {
        byte[] payload = fixtureBytes("stereo-faststart.mp4");
        payload[3] = 7; // A normal box header alone requires eight bytes.
        assertInvalidMp4(payload);

        payload = fixtureBytes("stereo-faststart.mp4");
        payload[3] = 1; // Extended-size header, with a declared size of only 12 bytes.
        Arrays.fill(payload, 8, 16, (byte) 0);
        payload[15] = 12;
        assertInvalidMp4(payload);
    }

    @Test(timeout = 10_000)
    public void rejectsTruncatedAudioSamplesInsteadOfEndingPlaybackSilently() throws Exception {
        byte[] payload = fixtureBytes("stereo-faststart.mp4");
        assertInvalidMp4(Arrays.copyOf(payload, payload.length - 100));
        payload = fixtureBytes("stereo-dash.m4s");
        // Remove the trailing mfra and part of the final mdat sample payload.
        assertInvalidMp4(Arrays.copyOf(payload, payload.length - 512));
    }

    @Test(timeout = 10_000)
    public void rejectsTruncatedInitializationBox() throws Exception {
        assertInvalidMp4(Arrays.copyOf(fixtureBytes("stereo-faststart.mp4"), 500));
    }

    @Test(timeout = 10_000)
    public void rejectsUnsupportedAudioCodecWithoutFallingBackToMp3() throws Exception {
        byte[] payload = fixtureBytes("stereo-faststart.mp4");
        byte[] type = "mp4a".getBytes(StandardCharsets.US_ASCII);
        int offset = -1;
        for (int i = 0; i <= payload.length - type.length; i++) {
            if (Arrays.equals(payload, i, i + type.length, type, 0, type.length)) {
                offset = i;
                break;
            }
        }
        assertTrue("fixture contains an AAC sample entry", offset >= 0);
        System.arraycopy("zzzz".getBytes(StandardCharsets.US_ASCII), 0, payload, offset, 4);
        assertInvalidMp4(payload);
    }

    private static void assertInvalidMp4(byte[] payload) throws Exception {
        Path file = Files.createTempFile("qplayer-invalid-aac-", ".bin");
        try {
            Files.write(file, payload);
            try (PcmSource source = PcmSource.open(file.toString())) {
                readPcm(source, Integer.MAX_VALUE);
                fail("malformed or unsupported MP4 must fail with IOException");
            } catch (IOException expected) {
                assertNotNull("failure should explain why the stream cannot be decoded", expected.getMessage());
                assertTrue("failure message must not be empty", !expected.getMessage().isBlank());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static byte[] fixtureBytes(String name) throws IOException {
        try (InputStream stream = resource(name).openStream()) {
            return stream.readAllBytes();
        }
    }

    private static void assertFile(String name, int rate, int channels) throws Exception {
        try (PcmSource source = PcmSource.open(fixturePath(name))) {
            assertEquals("sample rate", rate, source.sampleRate());
            assertEquals("channel count", channels, source.channels());
            byte[] pcm = readPcm(source, Integer.MAX_VALUE);
            assertCompletePcm(pcm, channels, rate);
            assertEquals("EOF is stable", -1, source.read(new byte[4_096], 0, 4_096));
            assertTrue("duration", source.durationMs() >= 2_350 && source.durationMs() <= 2_500);
        }
    }

    private static void assertCompletePcm(byte[] pcm, int channels, int rate) {
        double seconds = pcm.length / (2.0 * channels * rate);
        assertEquals("all samples must be decoded, including the last fragment", 2.4, seconds, 0.08);
        assertTone(window(pcm, channels, rate, 0.2, 0.2), channels, rate, 440);
        assertTone(window(pcm, channels, rate, 1.0, 0.2), channels, rate, 880);
        assertTone(window(pcm, channels, rate, 2.0, 0.2), channels, rate, 1_320);
    }

    private static void assertSeekTone(PcmSource source, long ms, int frequency) throws IOException {
        long actual = source.seek(ms);
        assertTrue("seek " + ms + " landed at " + actual, Math.abs(actual - ms) <= 30);
        assertTone(readPcm(source, source.sampleRate() / 5), source.channels(),
                source.sampleRate(), frequency);
    }

    private static byte[] readPcm(PcmSource source, int maxFrames) throws IOException {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        int frameSize = source.channels() * 2;
        byte[] buffer = new byte[4_096 + 8];
        int remaining = maxFrames;
        while (remaining > 0) {
            int wanted = Math.min(remaining, 4_096 / frameSize) * frameSize;
            Arrays.fill(buffer, (byte) 0x5a);
            int count = source.read(buffer, 4, wanted);
            if (count < 0) break;
            assertTrue("read must make progress", count > 0);
            assertTrue("read must respect requested length", count <= wanted);
            assertEquals("read must return complete PCM frames", 0, count % frameSize);
            for (int i = 0; i < 4; i++) assertEquals("prefix must be preserved", (byte) 0x5a, buffer[i]);
            for (int i = 4 + count; i < buffer.length; i++) {
                assertEquals("suffix must be preserved", (byte) 0x5a, buffer[i]);
            }
            pcm.write(buffer, 4, count);
            remaining -= count / frameSize;
        }
        return pcm.toByteArray();
    }

    private static byte[] window(byte[] pcm, int channels, int rate, double start, double length) {
        int from = (int) (start * rate) * channels * 2;
        int to = from + (int) (length * rate) * channels * 2;
        assertTrue("PCM must contain requested time window", to <= pcm.length);
        return Arrays.copyOfRange(pcm, from, to);
    }

    private static void assertTone(byte[] pcm, int channels, int rate, int frequency) {
        int frames = pcm.length / (2 * channels);
        // Allow one AAC overlap/priming frame after opening or resetting the decoder.
        int skip = Math.min(2_048, frames / 3);
        assertTrue("enough decoded samples to identify a tone", frames - skip > rate / 20);
        for (int channel = 0; channel < channels; channel++) {
            long absoluteSum = 0;
            int crossings = 0;
            int previous = 0;
            for (int frame = skip; frame < frames; frame++) {
                int offset = (frame * channels + channel) * 2;
                int sample = (short) ((pcm[offset] & 0xff) | (pcm[offset + 1] << 8));
                absoluteSum += Math.abs(sample);
                if (previous < 0 && sample >= 0) crossings++;
                previous = sample;
            }
            assertTrue("channel " + channel + " must contain audible PCM", absoluteSum / (frames - skip) > 500);
            double observedFrequency = crossings * (double) rate / (frames - skip);
            int expectedFrequency = frequency * (channel + 1);
            assertEquals("tone frequency in channel " + channel, expectedFrequency, observedFrequency,
                    Math.max(25, expectedFrequency * 0.05));
        }
    }

    private static void assertChunkedHttp(String name, int rate, boolean progressive) throws Exception {
        byte[] payload = fixtureBytes(name);
        CountDownLatch releaseTail = new CountDownLatch(progressive ? 1 : 0);
        CountDownLatch firstPcm = new CountDownLatch(1);
        AtomicReference<String> referer = new AtomicReference<>();
        AtomicReference<String> userAgent = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        server.createContext("/opaque", exchange -> {
            referer.set(exchange.getRequestHeaders().getFirst("Referer"));
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, 0); // Chunked, so total size is unknown until EOF.
            try (var body = exchange.getResponseBody()) {
                int split = payload.length * 2 / 3;
                body.write(payload, 0, split);
                body.flush();
                if (!releaseTail.await(10, TimeUnit.SECONDS)) throw new IOException("decoder did not start progressively");
                body.write(payload, split, payload.length - split);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/opaque?token=fixture";
            Future<byte[]> decoded = worker.submit(() -> {
                try (PcmSource source = PcmSource.open(url,
                        Map.of("Referer", "https://example.test/player", "User-Agent", "qplayer-aac-test"))) {
                    assertEquals(rate, source.sampleRate());
                    assertEquals(2, source.channels());
                    ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                    byte[] initial = readPcm(source, rate / 5);
                    assertTone(initial, 2, rate, 440);
                    pcm.write(initial);
                    firstPcm.countDown();
                    pcm.write(readPcm(source, Integer.MAX_VALUE));
                    assertSeekTone(source, 1_100, 880);
                    assertSeekTone(source, 300, 440);
                    return pcm.toByteArray();
                }
            });
            if (progressive) {
                boolean started = firstPcm.await(5, TimeUnit.SECONDS);
                if (!started && decoded.isDone()) decoded.get(); // Report the original decoder failure.
                assertTrue("first PCM must be decoded while the HTTP tail is withheld", started);
                releaseTail.countDown();
            }
            assertCompletePcm(decoded.get(8, TimeUnit.SECONDS), 2, rate);
            assertEquals("https://example.test/player", referer.get());
            assertEquals("qplayer-aac-test", userAgent.get());
        } finally {
            releaseTail.countDown();
            worker.shutdownNow();
            server.stop(0);
        }
    }

    private static String fixturePath(String name) throws Exception {
        return Path.of(resource(name).toURI()).toString();
    }

    private static URL resource(String name) {
        URL resource = AacPcmSourceTest.class.getResource("/audio/" + name);
        assertNotNull("missing fixture " + name, resource);
        return resource;
    }
}
