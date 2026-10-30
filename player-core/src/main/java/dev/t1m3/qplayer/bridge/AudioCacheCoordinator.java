package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.cache.DiskCache;
import dev.t1m3.qplayer.model.Track;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** IO-executor-owned downloads: one lease and shared final result per canonical song. */
final class AudioCacheCoordinator {
    interface Downloader {
        CompletableFuture<Boolean> download(Track track, String target);
    }

    private final DiskCache cache;
    private final Executor io;
    private final Downloader downloader;
    private final Consumer<Track> committed;
    private final Map<String, Download> inFlight = new LinkedHashMap<>();
    private volatile boolean accepting = true;

    AudioCacheCoordinator(DiskCache cache, Executor io, Downloader downloader, Consumer<Track> committed) {
        this.cache = cache;
        this.io = io;
        this.downloader = downloader;
        this.committed = committed;
    }

    CompletableFuture<Boolean> cache(Track track, boolean pin) {
        CompletableFuture<Boolean> requested = new CompletableFuture<>();
        try {
            io.execute(() -> admit(track, pin, requested));
        } catch (RejectedExecutionException error) {
            requested.completeExceptionally(error);
        }
        return requested;
    }

    private void admit(Track track, boolean pin, CompletableFuture<Boolean> requested) {
            if (!accepting) { requested.complete(false); return; }
            String canonical = track.canonicalId();
            Download existing = inFlight.get(canonical);
            if (existing != null) {
                existing.pin |= pin;
                deliver(existing.result, requested);
                return;
            }
            Download download = new Download(canonical, track, pin);
            inFlight.put(canonical, download);
            deliver(download.result, requested);
            try {
                String target = cache.audioPath(canonical);
                download.lease = cache.beginExternalWrite(target);
                if (cache.hasAudio(canonical)) {
                    finish(download, true, null);
                    return;
                }
                download.producer = downloader.download(track, target);
                download.producer.whenComplete((saved, error) -> {
                    try {
                        io.execute(() -> {
                            if (!download.cancelling) finish(download, Boolean.TRUE.equals(saved), error);
                        });
                    } catch (RejectedExecutionException rejected) {
                        // The executor must remain alive through finishCancellation.
                        // Preserve termination even if its owner violates that contract.
                        try {
                            if (download.lease != null) download.lease.close();
                        } catch (Throwable closeError) {
                            rejected.addSuppressed(closeError);
                        } finally {
                            download.result.completeExceptionally(rejected);
                        }
                    }
                });
            } catch (Throwable error) {
                if (accepting) finish(download, false, error);
                else download.cancelling = true;
            }
        }

    private void finish(Download download, boolean saved, Throwable error) {
            if (download.finished) return;
            download.finished = true;
            Throwable failure = error;
            try {
                if (failure == null && saved) {
                    if (download.pin) cache.markActivelyCached(download.canonical);
                    committed.accept(download.track);
                }
            } catch (Throwable commitError) {
                failure = commitError;
            } finally {
                try {
                    if (download.lease != null) download.lease.close();
                } catch (Throwable closeError) {
                    if (failure == null) failure = closeError;
                    else if (failure != closeError) failure.addSuppressed(closeError);
                } finally {
                    inFlight.remove(download.canonical);
                    if (failure != null) download.result.completeExceptionally(failure);
                    else download.result.complete(saved);
                }
            }
        }

    /** Close admission immediately, then freeze completion publication on the IO owner. */
    CompletableFuture<Void> stopAdmission() {
        accepting = false;
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        try {
            io.execute(() -> {
                for (Download download : inFlight.values()) download.cancelling = true;
                stopped.complete(null);
            });
        } catch (RejectedExecutionException error) {
            stopped.completeExceptionally(error);
        }
        return stopped;
    }

    /**
     * The supplied barrier acknowledges actual producer termination, not merely
     * cancellation of a CompletableFuture. Only then may leases be released.
     */
    CompletableFuture<Void> finishCancellation(CompletableFuture<Void> producersStopped) {
        CompletableFuture<Void> drained = new CompletableFuture<>();
        producersStopped.whenComplete((ignored, producerError) -> {
            try {
                io.execute(() -> {
                    if (accepting) {
                        drained.completeExceptionally(new IllegalStateException("cache admission is still open"));
                        return;
                    }
                    for (Download download : new ArrayList<>(inFlight.values())) {
                        download.cancelling = true;
                        // Real writers/actors have stopped. Cancel orphaned derived
                        // futures too, even when their upstream promise never settled.
                        if (download.producer != null) download.producer.cancel(true);
                        finish(download, false, null);
                    }
                    if (producerError != null) drained.completeExceptionally(producerError);
                    else drained.complete(null);
                });
            } catch (RejectedExecutionException error) {
                drained.completeExceptionally(error);
            }
        });
        return drained;
    }

    private static void deliver(CompletableFuture<Boolean> actual, CompletableFuture<Boolean> requested) {
        actual.whenComplete((saved, error) -> {
            if (error != null) requested.completeExceptionally(error);
            else requested.complete(saved);
        });
    }

    private static final class Download {
        final String canonical;
        final Track track;
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        boolean pin;
        boolean finished;
        boolean cancelling;
        CompletableFuture<Boolean> producer;
        DiskCache.WriteLease lease;
        Download(String canonical, Track track, boolean pin) {
            this.canonical = canonical;
            this.track = track;
            this.pin = pin;
        }
    }
}
