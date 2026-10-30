package dev.t1m3.qplayer.desktop.audio;

import com.sun.net.httpserver.HttpServer;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Opt-in native Vorbis smoke test with -Dqplayer.test.ogg=/path/to/an/8-second.ogg. */
public class OggPcmSourceStreamingTest {
    @Test(timeout = 15_000)
    public void pushdataStartsBeforeDownloadCompletesAndRetainsSeeking() throws Exception {
        String fixture = System.getProperty("qplayer.test.ogg");
        Assume.assumeNotNull(fixture);
        byte[] payload = Files.readAllBytes(Path.of(fixture));
        int rate;
        int channels;
        try (PcmSource local = PcmSource.open(fixture)) {
            rate = local.sampleRate();
            channels = local.channels();
            assertTrue("use a real Vorbis track longer than two seconds", local.durationMs() > 2_000);
            assertEquals(750, local.seek(750), 1);
            assertTrue(local.read(new byte[4_096], 0, 4_096) > 0);
        }
        CountDownLatch tail = new CountDownLatch(1);
        CountDownLatch firstPcm = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (var output = exchange.getResponseBody()) {
                int split = payload.length * 2 / 3;
                output.write(payload, 0, split);
                output.flush();
                tail.await(10, TimeUnit.SECONDS);
                output.write(payload, split, payload.length - split);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException expectedOnCancellation) {
                // Closing the test tears down any still-open HTTP response.
            } finally { exchange.close(); }
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        server.start();
        try {
            Future<Integer> decoded = worker.submit(() -> {
                try (PcmSource source = PcmSource.open(ProgressiveAudioTest.url(server), Map.of())) {
                    assertEquals(rate, source.sampleRate());
                    assertEquals(channels, source.channels());
                    byte[] pcm = new byte[4_096];
                    int bytes = source.read(pcm, 0, pcm.length);
                    assertTrue(bytes > 0);
                    firstPcm.countDown();
                    int n;
                    while ((n = source.read(pcm, 0, pcm.length)) >= 0) bytes += n;
                    assertEquals(750, source.seek(750), 1);
                    assertTrue(source.read(pcm, 0, pcm.length) > 0);
                    return bytes;
                }
            });
            boolean progressive = firstPcm.await(4, TimeUnit.SECONDS);
            if (!progressive && decoded.isDone()) decoded.get();
            assertTrue("stb pushdata must produce PCM while the tail is withheld", progressive);
            assertEquals(1L, tail.getCount());
            tail.countDown();
            assertTrue(decoded.get(5, TimeUnit.SECONDS) > rate * channels * 2);
        } finally {
            tail.countDown();
            worker.shutdownNow();
            server.stop(0);
        }
    }
}
