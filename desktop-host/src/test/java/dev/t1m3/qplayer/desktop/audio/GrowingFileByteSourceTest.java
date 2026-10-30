package dev.t1m3.qplayer.desktop.audio;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

public class GrowingFileByteSourceTest {
    @Test(timeout = 5_000)
    public void waitsAtPendingTailUntilAppendOrCommit() throws Exception {
        Path file = Files.createTempFile("qplayer-grow-", ".part");
        Files.write(file, new byte[]{1, 2, 3, 4});
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (GrowingFileByteSource src = new GrowingFileByteSource(file.toString(), false)) {
            byte[] buf = new byte[8];
            assertEquals(4, src.read(buf, 0, 8));
            assertFalse(src.hasBuffered(1));
            Future<Integer> pending = worker.submit(() -> src.read(buf, 0, 8));
            assertWaiting(pending);
            Files.write(file, new byte[]{5, 6}, StandardOpenOption.APPEND);
            assertEquals(2, (int) pending.get(1, TimeUnit.SECONDS));
            Future<Integer> eof = worker.submit(() -> src.read(buf, 0, 8));
            assertWaiting(eof);
            src.markComplete();
            assertEquals(-1, (int) eof.get(1, TimeUnit.SECONDS));
        } finally {
            worker.shutdownNow();
            Files.deleteIfExists(file);
        }
    }

    @Test(timeout = 5_000)
    public void closeCancelsPendingReadInsteadOfReportingEof() throws Exception {
        Path file = Files.createTempFile("qplayer-grow-cancel-", ".part");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        GrowingFileByteSource src = new GrowingFileByteSource(file.toString(), false);
        try {
            Future<Integer> read = worker.submit(() -> src.read(new byte[8], 0, 8));
            assertWaiting(read);
            src.close();
            try {
                read.get(1, TimeUnit.SECONDS);
                fail("cancelled IO must not look like a natural track end");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IOException);
            }
        } finally {
            src.close();
            worker.shutdownNow();
            Files.deleteIfExists(file);
        }
    }

    @Test(timeout = 5_000)
    public void observesPromotionWhileWaitingAtTail() throws Exception {
        Path pending = Files.createTempFile("qplayer-grow-promote-", ".part");
        Path complete = pending.resolveSibling(pending.getFileName() + ".cache");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (GrowingFileByteSource src = new GrowingFileByteSource(pending.toString(), false, complete.toString())) {
            Future<Integer> read = worker.submit(() -> src.read(new byte[8], 0, 8));
            assertWaiting(read);
            Files.write(pending, new byte[]{1, 2, 3});
            Files.move(pending, complete);
            assertEquals(3, (int) read.get(1, TimeUnit.SECONDS));
            assertEquals(-1, src.read(new byte[8], 0, 8));
        } finally {
            worker.shutdownNow();
            Files.deleteIfExists(pending);
            Files.deleteIfExists(complete);
        }
    }

    @Test(timeout = 5_000)
    public void growingWavWaitsWithoutReturningZeroOrPrematureEof() throws Exception {
        byte[] payload = ProgressiveAudioTest.wav(32_000);
        Path file = Files.createTempFile("qplayer-grow-wav-", ".part");
        Files.write(file, java.util.Arrays.copyOf(payload, 44 + 4_096));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        GrowingFileByteSource bytes = new GrowingFileByteSource(file.toString(), false);
        try (PcmSource pcm = PcmSource.decode(bytes)) {
            Future<Integer> read = worker.submit(() -> pcm.read(new byte[16_384], 0, 16_384));
            assertWaiting(read);
            Files.write(file, java.util.Arrays.copyOfRange(payload, 44 + 4_096, payload.length),
                    StandardOpenOption.APPEND);
            bytes.markComplete();
            assertEquals(16_384, (int) read.get(1, TimeUnit.SECONDS));
        } finally {
            bytes.close();
            worker.shutdownNow();
            Files.deleteIfExists(file);
        }
    }

    private static void assertWaiting(Future<?> future) throws Exception {
        try {
            future.get(150, TimeUnit.MILLISECONDS);
            fail("pending input must wait, not return zero/EOF");
        } catch (TimeoutException expected) {
            // The real source remains blocked until producer progress or cancellation.
        }
    }
}
