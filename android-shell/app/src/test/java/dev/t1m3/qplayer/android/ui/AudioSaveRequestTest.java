package dev.t1m3.qplayer.android.ui;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class AudioSaveRequestTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final class WorkQueue implements Executor {
        final Queue<Runnable> work = new ArrayDeque<>();
        @Override public void execute(Runnable action) { work.add(action); }
        void drain() { while (!work.isEmpty()) work.remove().run(); }
    }

    @Test public void stageCompletionWaitsForFinalDocumentClose() throws Exception {
        WorkQueue io = new WorkQueue();
        AtomicReference<Path> stage = new AtomicReference<>();
        ByteArrayOutputStream document = new ByteArrayOutputStream();
        AudioSaveRequest request = new AudioSaveRequest(temporary.newFolder().toPath(), (path, done) -> {
            try {
                stage.set(Path.of(path));
                Files.write(stage.get(), new byte[]{1, 2, 3});
                done.accept(true);
            } catch (IOException error) { throw new RuntimeException(error); }
        }, io);
        request.write(() -> new OutputStream() {
            @Override public void write(int value) { document.write(value); }
            @Override public void close() { assertFalse(request.result.isDone()); }
        });
        assertNull(stage.get()); // neither staging nor copying executes on the caller.
        io.work.remove().run();
        assertFalse(request.result.isDone());
        io.drain();
        assertTrue(request.result.join());
        assertArrayEquals(new byte[]{1, 2, 3}, document.toByteArray());
        assertFalse(Files.exists(stage.get()));
    }

    @Test public void providerCloseFailureIsNotReportedAsSuccess() throws Exception {
        WorkQueue io = new WorkQueue();
        AtomicReference<Path> stage = new AtomicReference<>();
        AudioSaveRequest request = new AudioSaveRequest(temporary.newFolder().toPath(), (path, done) -> {
            stage.set(Path.of(path));
            done.accept(true);
        }, io);
        request.write(() -> new ByteArrayOutputStream() {
            @Override public void close() throws IOException { throw new IOException("provider close"); }
        });
        io.drain();
        try {
            request.result.join();
            fail("provider failure must propagate");
        } catch (CompletionException error) {
            assertEquals("provider close", error.getCause().getMessage());
        }
        assertFalse(Files.exists(stage.get()));
    }

    @Test public void destructionCancelsAndCleansLateWriterOutput() throws Exception {
        WorkQueue io = new WorkQueue();
        AtomicReference<Path> stage = new AtomicReference<>();
        AtomicReference<Consumer<Boolean>> complete = new AtomicReference<>();
        AudioSaveRequest request = new AudioSaveRequest(temporary.newFolder().toPath(), (path, done) -> {
            stage.set(Path.of(path));
            complete.set(done);
        }, io);
        request.write(() -> { throw new AssertionError("cancelled save must not open provider"); });
        io.drain();
        request.cancel();
        io.drain();
        assertFalse(request.result.join());
        Files.write(stage.get(), new byte[]{4}); // a writer completes after Activity destruction.
        complete.get().accept(true);
        io.drain();
        assertFalse(Files.exists(stage.get()));
        assertFalse(request.result.join());
    }

    @Test public void writerFailureRemovesStageWithoutOpeningProvider() throws Exception {
        WorkQueue io = new WorkQueue();
        AtomicReference<Path> stage = new AtomicReference<>();
        AudioSaveRequest request = new AudioSaveRequest(temporary.newFolder().toPath(), (path, done) -> {
            stage.set(Path.of(path));
            done.accept(false);
        }, io);
        request.write(() -> { throw new AssertionError("writer failed"); });
        io.drain();
        assertFalse(request.result.join());
        assertFalse(Files.exists(stage.get()));
    }

    @Test public void cancelledPickerNeverInvokesWriter() throws Exception {
        WorkQueue io = new WorkQueue();
        AudioSaveRequest request = new AudioSaveRequest(temporary.newFolder().toPath(),
                (path, done) -> { throw new AssertionError("cancelled picker invoked writer"); }, io);
        request.cancel();
        request.write(ByteArrayOutputStream::new);
        io.drain();
        assertFalse(request.result.join());
    }
}
