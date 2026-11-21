package dev.t1m3.qplayer.desktop.audio;

import com.sun.net.httpserver.HttpServer;
import dev.t1m3.qplayer.util.Logger;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

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

    @Test(timeout = 12_000)
    public void sameNameRouteChangeRecoversPlayingTrackAndPreservesPause() throws Exception {
        Assume.assumeTrue("requires the OpenAL Soft null output", "null".equals(System.getenv("ALSOFT_DRIVERS")));
        AtomicLong generation = new AtomicLong(System.nanoTime());
        DesktopAudioBackend backend = new DesktopAudioBackend(generation::get);
        AtomicInteger starts = new AtomicInteger();
        AtomicBoolean ended = new AtomicBoolean();
        backend.setOnStarted(starts::incrementAndGet);
        backend.setOnComplete(() -> ended.set(true));
        backend.setOnError(() -> ended.set(true));
        Path local = Files.createTempFile("qplayer-reconnect-", ".wav");
        Files.write(local, ProgressiveAudioTest.wav(32_000 * 12));
        try {
            backend.play(local.toString(), 1000);
            await(() -> starts.get() == 1 && backend.position() > 1200);
            long before = backend.position();
            // Two notifications inside one poll, with exactly the same device name.
            long roundTrip = generation.addAndGet(2);
            await(() -> reopened(roundTrip));
            await(() -> backend.position() > before + 500);
            assertEquals(1, starts.get());

            backend.pause();
            long pausedRoute = generation.incrementAndGet();
            await(() -> reopened(pausedRoute));
            long pausedAt = backend.position();
            Thread.sleep(150);
            assertEquals(pausedAt, backend.position());
            assertFalse(backend.isPlaying());
            backend.resume();
            await(() -> backend.position() > pausedAt + 100);
            assertFalse(ended.get());
        } finally {
            backend.release();
            Files.deleteIfExists(local);
        }
    }

    @Test(timeout = 10_000)
    public void resumeKeepsPreparedStreamAndDoesNotRestartItsClock() throws Exception {
        Assume.assumeTrue("requires the OpenAL Soft null output", "null".equals(System.getenv("ALSOFT_DRIVERS")));
        byte[] payload = ProgressiveAudioTest.wav(32_000 * 12);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio", exchange -> {
            try {
                // A resolved, single-use stream need not remain reopenable after pause.
                if (requests.getAndIncrement() != 0) {
                    exchange.sendResponseHeaders(410, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, payload.length);
                try (var output = exchange.getResponseBody()) { output.write(payload); }
            } finally { exchange.close(); }
        });
        server.start();
        DesktopAudioBackend backend = new DesktopAudioBackend();
        AtomicInteger starts = new AtomicInteger();
        AtomicBoolean failed = new AtomicBoolean();
        backend.setOnStarted(starts::incrementAndGet);
        backend.setOnError(() -> failed.set(true));
        try {
            backend.play(ProgressiveAudioTest.url(server), 1000);
            await(() -> starts.get() == 1 && backend.position() >= 1200);
            for (int round = 0; round < 2; round++) {
                backend.pause();
                Thread.sleep(80);
                long pausedAt = backend.position();
                Thread.sleep(80);
                assertEquals("the prepared playhead freezes while paused", pausedAt, backend.position());
                backend.resume();
                await(() -> backend.position() > pausedAt + 100 || failed.get());
                assertFalse("resume must not reopen the single-use stream", failed.get());
                assertTrue("resume continues from the paused samples", backend.position() > pausedAt + 100);
                long previous = backend.position();
                for (int sample = 0; sample < 15; sample++) {
                    Thread.sleep(20);
                    long current = backend.position();
                    assertTrue("the resumed playhead must not move backwards", current >= previous);
                    previous = current;
                }
                assertEquals("resume must not restart the controller's onStarted fade", 1, starts.get());
            }
        } finally {
            backend.release();
            server.stop(0);
        }
    }

    @Test(timeout = 10_000)
    public void pauseDuringHeaderReadCanResumeWithoutStaleError() throws Exception {
        Assume.assumeTrue("requires the OpenAL Soft null output", "null".equals(System.getenv("ALSOFT_DRIVERS")));
        CountDownLatch head = new CountDownLatch(1);
        CountDownLatch tail = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        HttpServer server = delayedServer(ProgressiveAudioTest.wav(32_000 * 4), 8, head, tail);
        DesktopAudioBackend backend = new DesktopAudioBackend();
        backend.setOnStarted(started::countDown);
        backend.setOnError(() -> failed.set(true));
        try {
            backend.play(ProgressiveAudioTest.url(server), 500);
            assertTrue(head.await(2, TimeUnit.SECONDS));
            backend.pause();
            assertEquals(500L, backend.position());
            backend.resume();
            tail.countDown();
            assertTrue("the cancelled initial open can be retried", started.await(3, TimeUnit.SECONDS));
            assertFalse("a paused open is not a playback error", failed.get());
            assertTrue("the initial seek survives the cancelled open", backend.position() >= 500);
        } finally {
            backend.release();
            tail.countDown();
            server.stop(0);
        }
    }

    private static boolean reopened(long generation) {
        return Logger.snapshot().stream().anyMatch(line ->
                line.contains("reopened default output") && line.contains("(route " + generation + ","));
    }

    private static void await(BooleanSupplier predicate) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!predicate.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue("timed out waiting for playback or output recovery", predicate.getAsBoolean());
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
