package dev.t1m3.qplayer.android.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Main-thread-owned process initialization. A retirement failure reports lost
 * persistence, but its completion still means the old engine has terminated. */
final class EngineStartup<T> {
    private final Consumer<Throwable> retirementFailure;
    private CompletableFuture<T> current;
    private CompletableFuture<Void> retired = CompletableFuture.completedFuture(null);

    EngineStartup(Consumer<Throwable> retirementFailure) {
        this.retirementFailure = retirementFailure;
    }

    CompletableFuture<T> getOrStart(Supplier<T> initializer, Executor executor) {
        if (current == null || current.isCompletedExceptionally()) {
            current = retired.thenApplyAsync(ignored -> initializer.get(), executor);
        }
        return current;
    }

    /** Retire an unclaimed result only if it still belongs to this generation.
     * A late completion must never close a replacement engine. Never waits. */
    boolean retireIfCurrent(CompletableFuture<T> expected,
                            Function<T, CompletableFuture<Void>> terminate) {
        if (expected == null || current != expected || !expected.isDone()
                || expected.isCompletedExceptionally()) return false;
        T engine = expected.getNow(null);
        retire(terminate.apply(engine));
        return true;
    }

    void retire(CompletableFuture<Void> termination) {
        current = null;
        // shutdownAsync completes only after resource/worker termination. Do not
        // confuse a failed final save with an engine that is still running.
        retired = termination.handle((ignored, error) -> {
            if (error != null) retirementFailure.accept(error);
            return null;
        });
    }
}
