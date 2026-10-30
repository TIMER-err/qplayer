package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.cache.DiskCache;
import dev.t1m3.qplayer.model.Track;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class AudioCacheCoordinatorTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void laterManualRequestsJoinAutomaticDownloadAndPinBeforeLeaseRelease() throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            DiskCache cache = cache();
            CompletableFuture<Boolean> transfer = new CompletableFuture<>();
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<Path> target = new AtomicReference<>();
            AtomicInteger downloads = new AtomicInteger();
            AtomicInteger commits = new AtomicInteger();
            AudioCacheCoordinator coordinator = new AudioCacheCoordinator(cache, io, (track, path) -> {
                downloads.incrementAndGet();
                target.set(Paths.get(path));
                started.countDown();
                return transfer;
            }, track -> commits.incrementAndGet());
            Track song = song();
            CompletableFuture<Boolean> automatic = coordinator.cache(song, false);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            CompletableFuture<Boolean> manual = coordinator.cache(song, true);
            CompletableFuture<Boolean> secondManual = coordinator.cache(song, true);
            io.submit(() -> { }).get(3, TimeUnit.SECONDS);
            assertFalse(manual.isDone());
            assertFalse(secondManual.isDone());
            write(target.get(), 2 * 1024 * 1024);
            transfer.complete(true);
            assertTrue(automatic.get(3, TimeUnit.SECONDS));
            assertTrue(manual.get(3, TimeUnit.SECONDS));
            assertTrue(secondManual.get(3, TimeUnit.SECONDS));
            assertEquals(1, downloads.get());
            assertEquals(1, commits.get());
            assertTrue("manual pin survives the lease-close eviction", cache.hasAudio(song.canonicalId()));
            assertTrue(cache.isActivelyCached(song.canonicalId()));
            ControllerTestLifecycle.releaseCompletedCache(coordinator);
        } finally { io.shutdownNow(); }
    }

    @Test public void failedTransferTerminatesEveryJoinedManualResultExactlyOnceAndReleasesLease() throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            DiskCache cache = cache();
            CompletableFuture<Boolean> transfer = new CompletableFuture<>();
            CountDownLatch started = new CountDownLatch(1);
            AudioCacheCoordinator coordinator = new AudioCacheCoordinator(cache, io, (track, path) -> {
                started.countDown();
                return transfer;
            }, track -> fail("failed download must not commit"));
            Track song = song();
            coordinator.cache(song, false);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            CompletableFuture<Boolean> manual = coordinator.cache(song, true);
            CompletableFuture<Boolean> second = coordinator.cache(song, true);
            AtomicInteger completions = new AtomicInteger();
            CompletableFuture<?> observed = manual.handle((saved, error) -> {
                assertNotNull(error);
                completions.incrementAndGet();
                return null;
            });
            io.submit(() -> { }).get(3, TimeUnit.SECONDS);
            transfer.completeExceptionally(new IOException("network failed"));
            observed.get(3, TimeUnit.SECONDS);
            try { second.get(3, TimeUnit.SECONDS); fail("second manual request must fail"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof IOException); }
            assertEquals(1, completions.get());
            assertFalse(cache.isActivelyCached(song.canonicalId()));
            ControllerTestLifecycle.releaseCompletedCache(coordinator);
            try (DiskCache.WriteLease lease = cache.beginExternalWrite(cache.audioPath(song.canonicalId()))) {
                assertNotNull(lease);
            }
        } finally { io.shutdownNow(); }
    }

    @Test public void commitExceptionStillCompletesTheResultAndReleasesTheLease() throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            DiskCache cache = cache();
            AudioCacheCoordinator coordinator = new AudioCacheCoordinator(cache, io, (track, path) -> {
                try { write(Paths.get(path), 4); }
                catch (IOException error) { throw new IllegalStateException(error); }
                return CompletableFuture.completedFuture(true);
            }, track -> { throw new IllegalStateException("metadata commit failed"); });
            Track song = song();
            try { coordinator.cache(song, true).get(3, TimeUnit.SECONDS); fail("commit failed"); }
            catch (ExecutionException expected) { assertEquals("metadata commit failed", expected.getCause().getMessage()); }
            ControllerTestLifecycle.releaseCompletedCache(coordinator);
            try (DiskCache.WriteLease lease = cache.beginExternalWrite(cache.audioPath(song.canonicalId()))) {
                assertNotNull(lease);
            }
        } finally { io.shutdownNow(); }
    }

    @Test public void cancellationWaitsForRealWriterCleanupEvenIfItsDerivedFutureNeverSettles() throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor();
        ExecutorService writer = Executors.newSingleThreadExecutor();
        CountDownLatch cleanupGate = new CountDownLatch(1);
        try {
            DiskCache cache = cache();
            CompletableFuture<Boolean> orphaned = new CompletableFuture<>();
            CompletableFuture<Void> stopped = new CompletableFuture<>();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch cleanupStarted = new CountDownLatch(1);
            AtomicReference<java.util.concurrent.Future<?>> task = new AtomicReference<>();
            Track song = song();
            AudioCacheCoordinator coordinator = new AudioCacheCoordinator(cache, io, (track, path) -> {
                Path pending = Paths.get(path + ".pending");
                task.set(writer.submit(() -> {
                    Throwable failure = null;
                    try {
                        Files.createDirectories(pending.getParent());
                        try (java.io.OutputStream output = Files.newOutputStream(pending)) {
                            output.write(1);
                            started.countDown();
                            new CountDownLatch(1).await();
                        }
                    } catch (InterruptedException cancelled) {
                        // Actual producer cancellation, not future completion.
                    } catch (Exception error) {
                        failure = error;
                    } finally {
                        cleanupStarted.countDown();
                        try {
                            cleanupGate.await();
                            Files.deleteIfExists(pending);
                            if (failure != null) stopped.completeExceptionally(failure);
                            else stopped.complete(null);
                        } catch (Exception error) {
                            stopped.completeExceptionally(error);
                        }
                    }
                }));
                return orphaned;
            }, track -> fail("cancelled download cannot commit"));
            CompletableFuture<Boolean> result = coordinator.cache(song, true);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            coordinator.stopAdmission().get(3, TimeUnit.SECONDS);
            CompletableFuture<Void> drained = coordinator.finishCancellation(stopped);
            assertFalse(coordinator.cache(song, true).get(3, TimeUnit.SECONDS));
            task.get().cancel(true);
            assertTrue(cleanupStarted.await(3, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            assertFalse(drained.isDone());
            try {
                cache.beginExternalWrite(cache.audioPath(song.canonicalId()));
                fail("lease must remain held through actual producer cleanup");
            } catch (IllegalStateException expected) { }
            cleanupGate.countDown();
            drained.get(3, TimeUnit.SECONDS);
            assertFalse(result.get(3, TimeUnit.SECONDS));
            assertTrue("orphaned future cannot block retirement", orphaned.isCancelled());
            try (DiskCache.WriteLease lease = cache.beginExternalWrite(cache.audioPath(song.canonicalId()))) {
                assertNotNull(lease);
            }
        } finally {
            cleanupGate.countDown();
            writer.shutdownNow();
            io.shutdownNow();
        }
    }

    private DiskCache cache() throws IOException {
        DiskCache cache = new DiskCache(1);
        cache.setBaseDir(temporaryFolder.newFolder().getAbsolutePath());
        return cache;
    }
    private static Track song() {
        Track track = new Track();
        track.applyCanonicalId("fixture:song:one");
        track.source = Track.Source.PLUGIN;
        return track;
    }
    private static void write(Path path, int size) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[size]);
    }
}
