package dev.t1m3.qplayer.desktop.app;

import ca.weblite.webview.JavascriptFunction;
import ca.weblite.webview.swing.WebViewComponent;
import com.google.gson.Gson;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;

/** Runs plugin-provided JavaScript in an attached system browser context. The frame
 * stays laid out so browser environment APIs work, but remains non-focusable and
 * outside the work area. */
final class DesktopWebAuthScript {
    private static final Gson GSON = new Gson();
    private static final long TIMEOUT_MS = 45_000L;
    private static Session active;

    private DesktopWebAuthScript() {}

    static CompletableFuture<String> run(String originUrl, String script) {
        CompletableFuture<String> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            if (active != null) {
                result.completeExceptionally(
                        new IllegalStateException("another WebView script is already running"));
                return;
            }
            try {
                active = new Session(result, originUrl, script);
                active.start();
            } catch (Throwable error) {
                active = null;
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    static void shutdown() {
        Runnable close = () -> {
            Session session = active;
            if (session != null) {
                session.fail(new IllegalStateException("desktop host is shutting down"));
            }
        };
        if (SwingUtilities.isEventDispatchThread()) close.run();
        else {
            try { SwingUtilities.invokeAndWait(close); }
            catch (Exception ignored) { }
        }
    }

    private static final class Session {
        private final CompletableFuture<String> result;
        private final WebViewComponent webView;
        private final JFrame frame;
        private final Timer readinessPoll;
        private final long startedAt = System.currentTimeMillis();
        private final String readinessCheck;
        private final String script;
        private boolean injected;
        private boolean finished;

        Session(CompletableFuture<String> result, String originUrl, String script) {
            this.result = result;
            this.script = script;
            webView = WebViewComponent.create();
            webView.setUrl(originUrl);
            webView.setPreferredSize(new Dimension(900, 700));
            readinessCheck = "return !!document.documentElement && location.href.indexOf("
                    + GSON.toJson(originUrl) + ") === 0;";

            frame = new JFrame();
            frame.setUndecorated(true);
            frame.setFocusableWindowState(false);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setLayout(new BorderLayout());
            frame.add(webView, BorderLayout.CENTER);
            frame.pack();
            frame.setLocation(-32_000, -32_000);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) {
                    if (!finished) {
                        fail(new IllegalStateException("system WebView closed during script"));
                    }
                }
            });

            readinessPoll = new Timer(250, event -> poll());
            readinessPoll.setInitialDelay(300);
        }

        void start() {
            frame.setVisible(true);
            readinessPoll.start();
        }

        private void poll() {
            if (finished) return;
            if (System.currentTimeMillis() - startedAt >= TIMEOUT_MS) {
                fail(new IllegalStateException("system WebView script timed out"));
                return;
            }
            if (injected) return;
            webView.evalAsync(readinessCheck).whenComplete((state, error) -> {
                if (finished || injected || error != null || !"true".equals(state)) return;
                injected = true;
                try {
                    webView.eval("document.open();document.write('<!doctype html><html><head>"
                            + "<meta charset=\"utf-8\"></head><body></body></html>');document.close();");
                    // document.open() removes JavaScript wrappers. Install the typed function
                    // afterward: the legacy callback exposes the native RPC envelope instead.
                    webView.addJavascriptFunction("qplayerWebAuthDone",
                            (JavascriptFunction) payload -> {
                        receive(payload);
                        return "";
                    });
                    webView.eval(script);
                } catch (Throwable failure) {
                    fail(failure);
                }
            });
        }

        private void receive(String payload) {
            Runnable handle = () -> {
                if (finished) return;
                if (payload == null) {
                    fail(new IllegalStateException("WebView script returned null"));
                } else {
                    succeed(payload);
                }
            };
            if (SwingUtilities.isEventDispatchThread()) handle.run();
            else SwingUtilities.invokeLater(handle);
        }

        private void succeed(String value) {
            close();
            result.complete(value);
        }

        private void fail(Throwable error) {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(() -> fail(error));
                return;
            }
            if (finished) return;
            close();
            result.completeExceptionally(error);
        }

        private void close() {
            if (finished) return;
            finished = true;
            readinessPoll.stop();
            if (active == this) active = null;
            try { webView.dispose(); }
            catch (Throwable ignored) { }
            frame.dispose();
        }
    }
}
