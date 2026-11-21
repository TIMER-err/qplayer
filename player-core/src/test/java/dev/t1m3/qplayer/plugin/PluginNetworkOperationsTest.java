package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class PluginNetworkOperationsTest {
    @Test
    public void stopCompletesQueuedResultsButWaitsForActualProducerCleanup() throws Exception {
        PluginNetworkOperations operations = new PluginNetworkOperations(1, "network-stop-fixture");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean();
        AtomicBoolean queuedRan = new AtomicBoolean();
        CompletableFuture<Boolean> running = operations.submit(() -> {
            started.countDown();
            try {
                while (release.getCount() != 0) {
                    try { release.await(); }
                    catch (InterruptedException ignored) { interrupted.countDown(); }
                }
                return true;
            } finally {
                cleaned.set(true);
            }
        });
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            CompletableFuture<Boolean> queued = operations.submit(() -> {
                queuedRan.set(true);
                return true;
            });
            CompletableFuture<Void> stopped = operations.cancelPendingOperations();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertTrue(queued.isDone());
            assertTrue(queued.isCancelled());
            assertFalse(queuedRan.get());
            assertFalse("a requested stop is not an exited producer", stopped.isDone());
            assertFalse("the writer result must retain its lease until cleanup", running.isDone());
            release.countDown();
            stopped.get(2, TimeUnit.SECONDS);
            assertTrue(cleaned.get());
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> running.get(2, TimeUnit.SECONDS));
            assertTrue(operations.submit(() -> true).isCancelled());
            assertSame(stopped, operations.cancelPendingOperations());
        } finally {
            release.countDown();
            operations.cancelPendingOperations().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void cancellingAResultDoesNotReleaseAStillRunningWriter() throws Exception {
        PluginNetworkOperations operations = new PluginNetworkOperations(1, "network-result-fixture");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Boolean> result = operations.submit(() -> {
            started.countDown();
            while (release.getCount() != 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { interrupted.countDown(); }
            }
            throw new IOException("fixture writer exited");
        });
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            result.cancel(true);
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            release.countDown();
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> result.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            operations.cancelPendingOperations().get(2, TimeUnit.SECONDS);
        }
    }
}
