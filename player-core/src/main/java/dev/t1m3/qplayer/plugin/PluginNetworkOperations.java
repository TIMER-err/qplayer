package dev.t1m3.qplayer.plugin;

import java.io.IOException;
import java.io.InterruptedIOException;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns network producers, not merely their result futures. */
final class PluginNetworkOperations {
    @FunctionalInterface interface IoSupplier<T> { T get() throws IOException; }

    private final Object lock = new Object();
    private final Set<Operation> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ThreadLocal<Operation> current = new ThreadLocal<>();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final ExecutorService workers;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    private boolean stopped;

    PluginNetworkOperations(int concurrency, String threadName) {
        java.util.Objects.requireNonNull(threadName);
        workers = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    <T> CompletableFuture<T> submit(IoSupplier<T> supplier) {
        Job<T> job = new Job<>(supplier);
        synchronized (lock) {
            if (stopped) {
                job.result.completeExceptionally(new CancellationException("network service is closed"));
                return job.result;
            }
            active.add(job);
            workers.execute(job);
        }
        return job.result;
    }

    /** Tracks a synchronous catalog invocation running on its caller's worker. */
    Scope begin() throws IOException {
        Operation operation = new Operation();
        synchronized (lock) {
            if (stopped) throw new InterruptedIOException("network service is closed");
            operation.started = true;
            operation.runner = Thread.currentThread();
            active.add(operation);
        }
        current.set(operation);
        return new Scope(operation);
    }

    Response execute(URL url, String method, Map<String, String> headers, byte[] body,
                     int connectTimeout, int readTimeout) throws IOException {
        checkCancelled();
        Request.Builder request = new Request.Builder().url(url)
                .header("User-Agent", "QPlayer-Plugin/1")
                // Keep raw wire bytes unless the caller explicitly negotiates
                // gzip. HostApi owns that decoding/headers contract.
                .header("Accept-Encoding", "identity");
        boolean explicitContentType = false;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey();
            if (!"Host".equalsIgnoreCase(name) && !"Content-Length".equalsIgnoreCase(name)
                    && !"Connection".equalsIgnoreCase(name)) {
                request.header(name, header.getValue());
                if ("Content-Type".equalsIgnoreCase(name)) explicitContentType = true;
            }
        }
        String wireMethod = method;
        RequestBody requestBody = null;
        if (body != null && body.length > 0) {
            // Preserve URLConnection's historical GET-with-output wire behavior.
            if ("GET".equals(wireMethod)) wireMethod = "POST";
            requestBody = RequestBody.create(body, null);
            if (!explicitContentType) {
                request.header("Content-Type", "application/x-www-form-urlencoded");
            }
        } else if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
            requestBody = RequestBody.create(new byte[0], null);
        }
        Call call = http.newBuilder()
                .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request.method(wireMethod, requestBody).build());
        synchronized (lock) {
            Operation operation = current.get();
            if (operation == null || operation.cancelled || stopped) {
                call.cancel();
                throw new InterruptedIOException("network operation was stopped");
            }
            operation.calls.add(call);
        }
        return call.execute();
    }

    void checkCancelled() throws InterruptedIOException {
        synchronized (lock) {
            Operation operation = current.get();
            if (stopped || (operation != null && operation.cancelled)
                    || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("network operation was stopped");
            }
        }
    }

    /** Permanent admission stop. Completion acknowledges that all producers exited. */
    CompletableFuture<Void> cancelPendingOperations() {
        List<Operation> operations;
        List<Job<?>> queued = new ArrayList<>();
        synchronized (lock) {
            if (stopped) return terminated;
            stopped = true;
            operations = new ArrayList<>(active);
            for (Operation operation : operations) {
                operation.cancelled = true;
                if (!operation.started) {
                    operation.finished = true;
                    active.remove(operation);
                    queued.add((Job<?>) operation);
                }
            }
        }
        // Mark every job before invoking callbacks or interrupting any worker.
        // A callback is then free to wait for the same termination barrier.
        workers.shutdownNow();
        for (Operation operation : operations) interruptAndDisconnect(operation);
        signalTermination();
        for (Job<?> job : queued) job.result.completeExceptionally(stoppedFailure());
        return terminated;
    }

    private boolean cancel(Operation operation) {
        boolean queued;
        synchronized (lock) {
            if (operation.finished || operation.cancelled) return false;
            operation.cancelled = true;
            queued = !operation.started;
            if (queued) {
                operation.finished = true;
                active.remove(operation);
            }
        }
        interruptAndDisconnect(operation);
        signalTermination();
        if (queued) ((Job<?>) operation).result.completeExceptionally(stoppedFailure());
        return true;
    }

    private void interruptAndDisconnect(Operation operation) {
        Thread runner;
        List<Call> calls;
        synchronized (lock) {
            runner = operation.runner;
            calls = new ArrayList<>(operation.calls);
        }
        // Call.cancel closes the socket/HTTP2 stream directly; unlike
        // URLConnection.disconnect it does not wait for a blocked body read.
        for (Call call : calls) call.cancel();
        if (runner != null) runner.interrupt();
    }

    private void finish(Operation operation, boolean successful) {
        List<Call> calls;
        synchronized (lock) {
            calls = successful && !operation.cancelled
                    ? Collections.emptyList() : new ArrayList<>(operation.calls);
        }
        try {
            for (Call call : calls) call.cancel();
        } finally {
            current.remove();
            synchronized (lock) {
                operation.finished = true;
                operation.runner = null;
                operation.calls.clear();
                active.remove(operation);
            }
            signalTermination();
        }
    }

    private void signalTermination() {
        boolean complete;
        synchronized (lock) { complete = stopped && active.isEmpty(); }
        if (complete) {
            http.connectionPool().evictAll();
            terminated.complete(null);
        }
    }

    private static CancellationException stoppedFailure() {
        return new CancellationException("network operation was stopped");
    }

    private static class Operation {
        final List<Call> calls = new ArrayList<>();
        Thread runner;
        boolean started;
        boolean finished;
        boolean cancelled;
    }

    final class Scope implements AutoCloseable {
        private final Operation operation;
        private Scope(Operation operation) { this.operation = operation; }
        @Override public void close() { finish(operation, false); }
    }

    private final class Job<T> extends Operation implements Runnable {
        final IoSupplier<T> supplier;
        final CompletableFuture<T> result = new CompletableFuture<T>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                // Request a stop, but a running writer cannot become terminal
                // synchronously. Return true only if cleanup has actually exited.
                PluginNetworkOperations.this.cancel(Job.this);
                return isCancelled();
            }
        };
        Job(IoSupplier<T> supplier) { this.supplier = supplier; }

        @Override public void run() {
            synchronized (lock) {
                if (finished) return;
                started = true;
                runner = Thread.currentThread();
            }
            current.set(this);
            T value = null;
            Throwable failure = null;
            try {
                checkCancelled();
                value = supplier.get();
                checkCancelled();
            } catch (Throwable error) {
                failure = error;
            } finally {
                try { finish(this, failure == null); }
                catch (Throwable cleanup) {
                    if (failure == null) failure = cleanup;
                    else failure.addSuppressed(cleanup);
                }
            }
            synchronized (lock) {
                if (cancelled) {
                    CancellationException cancellation = stoppedFailure();
                    if (failure != null) cancellation.addSuppressed(failure);
                    failure = cancellation;
                }
            }
            if (failure == null) result.complete(value);
            else result.completeExceptionally(failure);
        }
    }
}
