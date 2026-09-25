package dev.t1m3.qplayer.desktop.app;

import ca.weblite.webview.swing.WebViewComponent;
import com.google.gson.Gson;
import dev.t1m3.qplayer.bridge.WatchmanProbeScript;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;

/** Runs one 易盾 Watchman probe in the installed system browser engine. The frame
 * is attached and laid out so the native browser receives a real browsing context,
 * but remains a one-pixel non-focusable utility surface outside the work area. */
final class DesktopWatchmanProbe {
    private static final Gson GSON = new Gson();
    private static final long TIMEOUT_MS = 45_000L;
    private static Session active;

    private DesktopWatchmanProbe() {}

    static CompletableFuture<String> request(String originUrl, String scriptUrl,
                                             String productNumber, String businessId) {
        CompletableFuture<String> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            if (active != null) {
                result.completeExceptionally(
                        new IllegalStateException("another WebView probe is already running"));
                return;
            }
            try {
                active = new Session(result, originUrl, scriptUrl, productNumber, businessId);
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
            if (session != null) session.fail(new IllegalStateException("desktop host is shutting down"));
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
        private final String bootstrap;
        private boolean injected;
        private boolean finished;

        Session(CompletableFuture<String> result, String originUrl, String scriptUrl,
                String productNumber, String businessId) {
            this.result = result;
            webView = WebViewComponent.create();
            webView.addJavascriptCallback("qplayerWatchmanDone", this::receive);
            webView.setUrl(originUrl);
            webView.setPreferredSize(new Dimension(900, 700));
            bootstrap = "document.open();document.write('<!doctype html><html><head>"
                    + "<meta charset=\"utf-8\"></head><body></body></html>');document.close();"
                    + WatchmanProbeScript.javascript(
                            originUrl, scriptUrl, productNumber, businessId);

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
                    if (!finished) fail(new IllegalStateException("system WebView closed during probe"));
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
                fail(new IllegalStateException("watchman WebView probe timed out"));
                return;
            }
            if (injected) return;
            webView.evalAsync("return document.readyState === 'complete';").whenComplete((state, error) -> {
                if (finished || injected || error != null || !"true".equals(state)) return;
                injected = true;
                try { webView.eval(bootstrap); }
                catch (Throwable failure) { fail(failure); }
            });
        }

        private void receive(String payload) {
            Runnable handle = () -> {
                if (finished) return;
                try {
                    ProbeResult parsed = GSON.fromJson(payload, ProbeResult.class);
                    if (parsed != null && parsed.token != null && !parsed.token.isEmpty()) {
                        succeed(parsed.token);
                    } else {
                        String message = parsed != null && parsed.error != null && !parsed.error.isEmpty()
                                ? parsed.error : "watchman returned an empty token";
                        fail(new IllegalStateException(message));
                    }
                } catch (Throwable error) {
                    fail(new IllegalStateException("watchman returned an invalid result", error));
                }
            };
            if (SwingUtilities.isEventDispatchThread()) handle.run();
            else SwingUtilities.invokeLater(handle);
        }

        private void succeed(String token) {
            close();
            result.complete(token);
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

    private static final class ProbeResult {
        String token;
        String error;
    }
}
