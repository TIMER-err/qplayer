package dev.t1m3.qplayer.android.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** A save belongs to its final document, not to the intermediate writer callback.
 * Owns no Activity; late writer callbacks still remove their temporary files. */
final class AudioSaveRequest {
    interface Destination {
        OutputStream open() throws IOException;
    }

    private static final Executor IO = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "qplayer-audio-save");
        thread.setDaemon(true);
        return thread;
    });

    final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private final Path exports;
    private final BiConsumer<String, Consumer<Boolean>> writer;
    private final Executor io;
    private final AtomicBoolean reported = new AtomicBoolean();
    private volatile Path stage;

    AudioSaveRequest(Path exports, BiConsumer<String, Consumer<Boolean>> writer) {
        this(exports, writer, IO);
    }

    AudioSaveRequest(Path exports, BiConsumer<String, Consumer<Boolean>> writer, Executor io) {
        this.exports = exports;
        this.writer = writer;
        this.io = io;
    }

    void cancel() {
        result.complete(false);
        io.execute(this::cleanStage);
    }

    void write(Destination destination) {
        io.execute(() -> {
            if (result.isDone()) return;
            try {
                Files.createDirectories(exports);
                stage = Files.createTempFile(exports, "audio-", ".stage");
                if (result.isDone()) {
                    cleanStage();
                    return;
                }
                writer.accept(stage.toString(), ok -> {
                    if (!reported.compareAndSet(false, true)) return;
                    io.execute(() -> finish(destination, Boolean.TRUE.equals(ok)));
                });
            } catch (Throwable error) {
                cleanStage();
                result.completeExceptionally(error);
            }
        });
    }

    private void finish(Destination destination, boolean staged) {
        Throwable failure = null;
        try {
            if (result.isDone()) return;
            if (staged) {
                try (OutputStream out = destination.open(); InputStream in = Files.newInputStream(stage)) {
                    if (out == null) throw new IOException("Document provider returned no output stream");
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (result.isDone()) return;
                        out.write(buffer, 0, count);
                    }
                }
            }
        } catch (Throwable error) {
            failure = error;
        } finally {
            cleanStage();
        }
        if (failure == null) result.complete(staged);
        else result.completeExceptionally(failure);
    }

    private void cleanStage() {
        Path path = stage;
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException error) {
            dev.t1m3.qplayer.util.Logger.warn("delete audio stage failed: {}", error.toString());
        }
    }
}
