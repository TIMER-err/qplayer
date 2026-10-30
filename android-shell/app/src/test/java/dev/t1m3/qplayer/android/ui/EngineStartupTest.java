package dev.t1m3.qplayer.android.ui;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class EngineStartupTest {
    private static final class WorkQueue implements Executor {
        final Queue<Runnable> work = new ArrayDeque<>();
        @Override public void execute(Runnable action) { work.add(action); }
        void drain() { while (!work.isEmpty()) work.remove().run(); }
    }

    @Test public void pendingAndSuccessfulInitializationAreSharedAcrossActivities() {
        WorkQueue worker = new WorkQueue();
        EngineStartup<Object> startup = new EngineStartup<>(error -> fail(error.toString()));
        AtomicInteger created = new AtomicInteger();
        CompletableFuture<Object> first = startup.getOrStart(() -> {
            created.incrementAndGet();
            return new Object();
        }, worker);
        assertSame(first, startup.getOrStart(() -> { throw new AssertionError("duplicate init"); }, worker));
        assertFalse(first.isDone());
        worker.drain();
        assertEquals(1, created.get());
        assertSame(first, startup.getOrStart(Object::new, worker));
    }

    @Test public void failedFinalSaveReportsErrorButDoesNotPoisonNextInitialization() {
        WorkQueue worker = new WorkQueue();
        List<Throwable> errors = new ArrayList<>();
        EngineStartup<Object> startup = new EngineStartup<>(errors::add);
        CompletableFuture<Object> old = startup.getOrStart(Object::new, worker);
        worker.drain();
        CompletableFuture<Void> terminated = new CompletableFuture<>();
        startup.retire(terminated);
        AtomicInteger created = new AtomicInteger();
        CompletableFuture<Object> replacement = startup.getOrStart(() -> {
            created.incrementAndGet();
            return new Object();
        }, worker);
        worker.drain();
        assertEquals(0, created.get());
        assertFalse(replacement.isDone());
        IOException saveFailure = new IOException("final save failed after termination");
        terminated.completeExceptionally(saveFailure);
        assertSame(saveFailure, errors.get(0));
        worker.drain();
        assertEquals(1, created.get());
        assertNotSame(old.join(), replacement.join());
    }

    @Test public void failedInitializationCanRetryWithoutAnActivityCompletionCallback() {
        WorkQueue worker = new WorkQueue();
        EngineStartup<Object> startup = new EngineStartup<>(error -> fail(error.toString()));
        CompletableFuture<Object> failed = startup.getOrStart(() -> {
            throw new IllegalStateException("initialization failed");
        }, worker);
        worker.drain();
        assertTrue(failed.isCompletedExceptionally());
        CompletableFuture<Object> retried = startup.getOrStart(Object::new, worker);
        assertNotSame(failed, retried);
        worker.drain();
        assertNotNull(retried.join());
        assertSame(retried, startup.getOrStart(Object::new, worker));
    }

    @Test public void abandonedInitializationRetiresOnlyAfterItCompletesAndOnlyOnce() {
        WorkQueue worker = new WorkQueue();
        EngineStartup<Object> startup = new EngineStartup<>(error -> fail(error.toString()));
        CompletableFuture<Object> abandoned = startup.getOrStart(Object::new, worker);
        AtomicInteger closed = new AtomicInteger();
        assertFalse(startup.retireIfCurrent(abandoned, engine -> {
            closed.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }));
        assertEquals(0, closed.get());
        worker.drain();
        assertTrue(startup.retireIfCurrent(abandoned, engine -> {
            assertSame(abandoned.join(), engine);
            closed.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }));
        assertFalse(startup.retireIfCurrent(abandoned, engine -> {
            throw new AssertionError("already retired");
        }));
        assertEquals(1, closed.get());
    }

    @Test public void lateOldCompletionCannotRetireReplacementGeneration() {
        WorkQueue worker = new WorkQueue();
        EngineStartup<Object> startup = new EngineStartup<>(error -> fail(error.toString()));
        CompletableFuture<Object> old = startup.getOrStart(Object::new, worker);
        worker.drain();
        startup.retire(CompletableFuture.completedFuture(null));
        CompletableFuture<Object> replacement = startup.getOrStart(Object::new, worker);
        worker.drain();
        assertFalse(startup.retireIfCurrent(old, engine -> {
            throw new AssertionError("old Activity closed replacement");
        }));
        assertSame(replacement, startup.getOrStart(Object::new, worker));
    }
}
