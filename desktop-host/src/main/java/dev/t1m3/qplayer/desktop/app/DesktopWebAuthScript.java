package dev.t1m3.qplayer.desktop.app;

import com.google.gson.Gson;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Runs bounded plugin JavaScript in a hidden standalone native browser session. */
final class DesktopWebAuthScript {
    private static final Gson GSON = new Gson();
    private static Session active;

    private DesktopWebAuthScript() {}

    static CompletableFuture<String> run(String originUrl, String script) {
        CompletableFuture<String> result = new CompletableFuture<>();
        if (DesktopNativeBrowser.headless()) {
            result.completeExceptionally(new IllegalStateException("system WebView is unavailable in headless mode"));
            return result;
        }
        final String origin;
        try {
            URI uri = URI.create(originUrl);
            if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || script == null || script.isBlank()) {
                throw new IllegalArgumentException("invalid WebView script origin or script");
            }
            // URL.origin supplies the canonical default-port handling used by the engine.
            origin = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toASCIIString();
        } catch (Exception error) {
            result.completeExceptionally(error);
            return result;
        }
        result.orTimeout(45, TimeUnit.SECONDS);
        DesktopNativeBrowser.EVENTS.execute(() -> {
            if (result.isDone()) return;
            if (active != null) {
                result.completeExceptionally(new IllegalStateException("another WebView script is already running"));
                return;
            }
            Session session = new Session(result, origin, script);
            active = session;
            try {
                session.browser = new DesktopNativeBrowser(false, "", session::receive);
                session.browser.navigate(originUrl);
                result.whenComplete((value, error) -> {
                    if (error != null) DesktopNativeBrowser.EVENTS.execute(() ->
                            session.finish(null, error));
                });
                session.poll = DesktopNativeBrowser.EVENTS.scheduleWithFixedDelay(session::poll, 300, 250, TimeUnit.MILLISECONDS);
            } catch (Throwable error) {
                session.finish(null, error);
            }
        });
        return result;
    }

    static void shutdown() {
        DesktopNativeBrowser.runAndWait(() -> {
            if (active != null) active.finish(null, new IllegalStateException("desktop host is shutting down"));
        });
    }

    private static final class Session {
        final CompletableFuture<String> result;
        final String origin;
        final String script;
        DesktopNativeBrowser browser;
        ScheduledFuture<?> poll;
        boolean injected;
        boolean finished;

        Session(CompletableFuture<String> result, String origin, String script) {
            this.result = result;
            this.origin = origin;
            this.script = script;
        }

        void poll() {
            if (finished) return;
            try {
                if (!browser.isOpen()) {
                    finish(null, new IllegalStateException("system WebView closed during script"));
                    return;
                }
                // Navigation can discard a document's bridge reply. Allow harmless
                // readiness probes to overlap; injected guards the single commit.
                if (injected) return;
                browser.evalAsync("return !!document.documentElement && location.origin === new URL("
                        + GSON.toJson(origin) + ").origin;").whenComplete((state, error) -> {
                    if (finished || injected || error != null || !"true".equals(state)) return;
                    injected = true;
                    browser.runPrepared(origin, script).whenComplete((value, failure) -> {
                        if (!finished && failure != null) finish(null, failure);
                    });
                });
            } catch (Throwable error) {
                finish(null, error);
            }
        }

        void receive(String value) {
            if (value == null) finish(null, new IllegalStateException("WebView script returned null"));
            else finish(value, null);
        }

        void finish(String value, Throwable error) {
            if (finished) return;
            finished = true;
            if (poll != null) poll.cancel(false);
            if (active == this) active = null;
            try {
                if (browser != null) browser.close();
            } catch (Throwable cleanupError) {
                if (error == null) error = cleanupError;
                else error.addSuppressed(cleanupError);
            }
            if (error == null) result.complete(value);
            else result.completeExceptionally(error);
        }
    }
}
