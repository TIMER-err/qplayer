package dev.t1m3.qplayer.android.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import dev.t1m3.qplayer.bridge.WatchmanProbeScript;

import org.json.JSONObject;

import java.util.concurrent.CompletableFuture;

/** One attached Android system-WebView session for an 易盾 environment probe. */
final class AndroidWatchmanProbe {
    private static final long TIMEOUT_MS = 45_000L;
    private static final String BRIDGE_NAME = "QPlayerWatchman";

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session active;

    AndroidWatchmanProbe(Activity activity) {
        this.activity = activity;
    }

    CompletableFuture<String> request(String originUrl, String scriptUrl,
                                      String productNumber, String businessId) {
        CompletableFuture<String> result = new CompletableFuture<>();
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                result.completeExceptionally(new IllegalStateException("activity is unavailable"));
                return;
            }
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

    void shutdown() {
        Runnable close = () -> {
            Session session = active;
            if (session != null) session.fail(new IllegalStateException("activity was destroyed"));
        };
        if (Looper.myLooper() == Looper.getMainLooper()) close.run();
        else main.post(close);
    }

    private final class Session {
        private final CompletableFuture<String> result;
        private final String bootstrap;
        private final String originUrl;
        private final WebView webView;
        private final Runnable timeout;
        private boolean injected;
        private boolean finished;

        @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
        Session(CompletableFuture<String> result, String originUrl, String scriptUrl,
                String productNumber, String businessId) {
            this.result = result;
            this.originUrl = originUrl;
            bootstrap = "window.qplayerWatchmanDone=function(payload){window."
                    + BRIDGE_NAME + ".done(payload);};"
                    + WatchmanProbeScript.javascript(
                            originUrl, scriptUrl, productNumber, businessId);
            webView = new WebView(activity);
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            webView.addJavascriptInterface(new ResultBridge(), BRIDGE_NAME);
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    injected = false;
                }

                @Override public void onPageFinished(WebView view, String url) {
                    if (finished || injected) return;
                    injected = true;
                    view.evaluateJavascript(bootstrap, null);
                }

                @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                      WebResourceError error) {
                    if (request.isForMainFrame()) {
                        fail(new IllegalStateException("system WebView failed to create the probe page"));
                    }
                }
            });
            timeout = () -> fail(new IllegalStateException("watchman WebView probe timed out"));
        }

        void start() {
            ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
            float density = activity.getResources().getDisplayMetrics().density;
            int width = Math.round(360f * density);
            int height = Math.round(640f * density);
            webView.setTranslationX(-width - density);
            root.addView(webView, new ViewGroup.LayoutParams(width, height));
            main.postDelayed(timeout, TIMEOUT_MS);
            webView.loadDataWithBaseURL(originUrl,
                    "<!doctype html><html><head><meta charset=\"utf-8\"></head><body></body></html>",
                    "text/html", "UTF-8", null);
        }

        private final class ResultBridge {
            @JavascriptInterface public void done(String payload) {
                main.post(() -> receive(payload));
            }
        }

        private void receive(String payload) {
            if (finished) return;
            try {
                JSONObject parsed = new JSONObject(payload);
                String token = parsed.optString("token", "");
                if (!token.isEmpty()) {
                    succeed(token);
                    return;
                }
                String error = parsed.optString("error", "");
                fail(new IllegalStateException(error.isEmpty()
                        ? "watchman returned an empty token" : error));
            } catch (Throwable error) {
                fail(new IllegalStateException("watchman returned an invalid result", error));
            }
        }

        private void succeed(String token) {
            close();
            result.complete(token);
        }

        private void fail(Throwable error) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                main.post(() -> fail(error));
                return;
            }
            if (finished) return;
            close();
            result.completeExceptionally(error);
        }

        private void close() {
            if (finished) return;
            finished = true;
            main.removeCallbacks(timeout);
            if (active == this) active = null;
            webView.removeJavascriptInterface(BRIDGE_NAME);
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.stopLoading();
            webView.destroy();
        }
    }
}
