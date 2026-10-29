package dev.t1m3.qplayer.qml;

import io.github.timer_err.qml4j.engine.binding.Binding;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.engine.binding.Property;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class QmlRuntimeLockTest {

    @Test
    public void lockBlocksAConcurrentWriteUntilFlushFinishes() throws Exception {
        Property<String> source = new Property<>("one");
        Property<String> derived = new Property<>("");
        CountDownLatch inEvaluate = new CountDownLatch(1);
        CountDownLatch releaseEvaluate = new CountDownLatch(1);
        AtomicInteger evaluations = new AtomicInteger();
        derived.bind(new Binding() {
            @Override
            public Object evaluate() {
                String value = source.get();
                if (evaluations.incrementAndGet() >= 2) {
                    inEvaluate.countDown();
                    try {
                        if (!releaseEvaluate.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("evaluate was not released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return value;
            }
        });

        AtomicReference<Throwable> flushError = new AtomicReference<>();
        AtomicReference<Throwable> writeError = new AtomicReference<>();
        Thread flusher = new Thread(() -> {
            DirtyQueue queue = new DirtyQueue();
            synchronized (QmlRuntimeLock.MONITOR) {
                queue.install();
                try {
                    source.set("two");
                    queue.flush();
                } catch (Throwable error) {
                    flushError.set(error);
                } finally {
                    queue.uninstall();
                }
            }
        }, "qml-flush");
        Thread writer = new Thread(() -> {
            try {
                if (!inEvaluate.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("flush never entered evaluate");
                }
                synchronized (QmlRuntimeLock.MONITOR) {
                    source.set("three");
                }
            } catch (Throwable error) {
                writeError.set(error);
            }
        }, "qml-write");

        flusher.start();
        writer.start();
        assertTrue("flush never entered evaluate", inEvaluate.await(2, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + 1_000L;
        while (writer.getState() != Thread.State.BLOCKED
                && writer.getState() != Thread.State.TIMED_WAITING
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals("writer must wait for the flush lock, not reenter evaluating",
                Thread.State.BLOCKED, writer.getState());
        releaseEvaluate.countDown();
        flusher.join(3_000L);
        writer.join(3_000L);
        assertNull(String.valueOf(flushError.get()), flushError.get());
        assertNull(String.valueOf(writeError.get()), writeError.get());
        assertEquals("three", source.peek());
        assertEquals("three", derived.peek());
    }
}
