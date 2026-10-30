package dev.t1m3.qplayer.desktop.audio;

import com.sun.net.httpserver.HttpServer;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Real OpenAL smoke tests; opt in with ALSOFT_DRIVERS=null for a headless output. */
public class DesktopAudioBackendStreamingTest {
    @Test(timeout = 10_000)
    public void first300BytesAndUnbufferedSeekCannotAutoAdvance() throws Exception {
        Assume.assumeTrue("requires the OpenAL Soft null output", "null".equals(System.getenv("ALSOFT_DRIVERS")));
        byte[] payload = ProgressiveAudioTest.wav(32_000);
        CountDownLatch head = new CountDownLatch(1);
        CountDownLatch tail = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        HttpServer server = delayedServer(payload, 300, head, tail);
        DesktopAudioBackend backend = new DesktopAudioBackend();
        backend.setOnComplete(complete::countDown);
        backend.setOnStarted(started::countDown);
        backend.setOnError(() -> failed.set(true));
        try {
            backend.play(ProgressiveAudioTest.url(server), 0);
            assertTrue(head.await(2, TimeUnit.SECONDS));
            assertFalse("temporary starvation is not end-of-track", complete.await(250, TimeUnit.MILLISECONDS));
            assertFalse(failed.get());
            backend.seek(500);
            assertFalse("unbuffered seek is not end-of-track", complete.await(250, TimeUnit.MILLISECONDS));
            assertFalse(failed.get());
            tail.countDown();
            assertTrue("playback starts after more data arrives", started.await(3, TimeUnit.SECONDS));
            assertTrue("only actual EOF completes the track", complete.await(3, TimeUnit.SECONDS));
            assertFalse(failed.get());
        } finally {
            backend.release();
            tail.countDown();
            server.stop(0);
        }
    }

    @Test(timeout = 10_000)
    public void replacementCancelsOldSourceBlockedDuringMagicRead() throws Exception {
        Assume.assumeTrue("requires the OpenAL Soft null output", "null".equals(System.getenv("ALSOFT_DRIVERS")));
        byte[] payload = ProgressiveAudioTest.wav(32_000);
        CountDownLatch head = new CountDownLatch(1);
        CountDownLatch tail = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        HttpServer server = delayedServer(payload, 8, head, tail);
        Path local = Files.createTempFile("qplayer-replacement-", ".wav");
        Files.write(local, payload);
        DesktopAudioBackend backend = new DesktopAudioBackend();
        backend.setOnStarted(started::countDown);
        backend.setOnError(() -> failed.set(true));
        try {
            backend.play(ProgressiveAudioTest.url(server), 0);
            assertTrue(head.await(2, TimeUnit.SECONDS));
            backend.play(local.toString(), 0);
            assertTrue("local replacement must not await the old HTTP response", started.await(2, TimeUnit.SECONDS));
            assertEquals("old network tail is still blocked", 1L, tail.getCount());
            assertFalse("intentional cancellation must not become onError", failed.get());
        } finally {
            backend.release();
            tail.countDown();
            server.stop(0);
            Files.deleteIfExists(local);
        }
    }

    @Test(timeout = 10_000)
    public void pendingRequestKeepsItsErrorCallbackWhenTheNextListenerIsConfigured() throws Exception {
        Assume.assumeTrue("requires the OpenAL Soft null output", "null".equals(System.getenv("ALSOFT_DRIVERS")));
        byte[] payload = ProgressiveAudioTest.wav(32_000);
        payload[20] = 3; // Unsupported IEEE-float format, discovered after the held header.
        CountDownLatch head = new CountDownLatch(1);
        CountDownLatch tail = new CountDownLatch(1);
        CountDownLatch originalError = new CountDownLatch(1);
        AtomicBoolean nextError = new AtomicBoolean();
        HttpServer server = delayedServer(payload, 8, head, tail);
        DesktopAudioBackend backend = new DesktopAudioBackend();
        backend.setOnError(originalError::countDown);
        try {
            backend.play(ProgressiveAudioTest.url(server), 0);
            assertTrue(head.await(2, TimeUnit.SECONDS));
            // Model PC configuring B's closure before it has published B's play.
            backend.setOnError(() -> nextError.set(true));
            tail.countDown();
            assertTrue("A must deliver to the callback frozen by A's play", originalError.await(2, TimeUnit.SECONDS));
            assertFalse("A must never invoke the next request's error listener", nextError.get());
        } finally {
            backend.release();
            tail.countDown();
            server.stop(0);
        }
    }

    private static HttpServer delayedServer(byte[] payload, int firstBytes,
                                            CountDownLatch head, CountDownLatch tail) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio", exchange -> {
            // Deliberately ignores Range: exercise the sequential-only fallback too.
            exchange.sendResponseHeaders(200, payload.length);
            try (var output = exchange.getResponseBody()) {
                output.write(payload, 0, firstBytes);
                output.flush();
                head.countDown();
                tail.await(8, TimeUnit.SECONDS);
                output.write(payload, firstBytes, payload.length - firstBytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException expectedOnCancellation) {
                // Switching away closes this deliberately stalled response.
            } finally { exchange.close(); }
        });
        server.start();
        return server;
    }
}
