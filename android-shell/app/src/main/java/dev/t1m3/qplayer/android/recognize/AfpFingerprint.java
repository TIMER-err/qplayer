package dev.t1m3.qplayer.android.recognize;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 听歌识曲's fingerprint engine, behind a hidden {@link WebView} (round 37).
 *
 * <p>Why a WebView: the fingerprint is NetEase's own AFP algorithm, shipped as WebAssembly
 * ({@code afp.wasm}, wrapped by {@code afp.js}). Reimplementing it in Java is not realistic and there
 * is no Java port, so the same route the other working Android clients take is used here — load
 * {@code assets/afp/host.html} offline, hand it the samples, take the blob back. The two scripts are
 * the official helper's own; {@code host.html} is ours.
 *
 * <p>The contract with the page is one function: {@code extractFp(base64 of the little-endian
 * float32 samples)} → {@code AndroidAfp.result(base64 blob)}. The page cannot reach the network
 * ({@link WebSettings#setBlockNetworkLoads}) and has no JavaScript bridge except this class, so a
 * compromised asset cannot do more than answer.
 *
 * <p>⚠️ One WebView is created per recorder and kept for its lifetime: creating one costs ~100 ms and
 * instantiating the wasm a few hundred more, which is not something to pay per attempt.
 */
public final class AfpFingerprint {

    /** Long enough for a cold wasm instantiation on a slow device, short enough not to hang a
     *  listener who is standing in a shop. */
    private static final long TIMEOUT_MS = 12_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    /** 主线程建、别处读，所以要 volatile。 */
    private volatile WebView web;
    private volatile CountDownLatch waiting;
    private volatile String answer;
    private volatile String failure;

    /**
     * Build the engine.
     *
     * <p><b>WebView 只能在「有 Looper 的线程」上建</b>（它内部会 {@code new Handler()}），
     * 而本类的调用方是 <b>qml4j 的 GL 渲染线程</b>——那个线程没有 Looper。用户 2026-10-03
     * 在手机上点「识别」时看到的那条报错就是它：
     *
     * <pre>
     * java.lang.NullPointerException: Attempt to read from field
     *   'android.os.MessageQueue android.os.Looper.mQueue' on a null object reference
     *   in method 'void android.os.Handler.&lt;init&gt;(android.os.Looper, ...)'
     * </pre>
     *
     * <p>所以这里把「建 WebView」整段<b>搬到主线程并等它建完</b>：构造返回时 WebView 已经
     * 就绪（{@link #of} 之后就能 evaluateJavascript）。与同仓库的
     * {@code AndroidWatchmanProbe} 是同一套做法——那边也是「Launcher 从渲染线程被调，
     * WebView 相关的事一律 post 到主线程」。原来那句「Must be called on the main thread」
     * 只是把要求写在了文档里，现在由本类自己保证。
     */
    public AfpFingerprint(Context context) {
        final Context app = context.getApplicationContext();
        runOnMainAndWait(() -> {
            WebView view = new WebView(app);
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setBlockNetworkLoads(true);
            settings.setAllowFileAccess(true);
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView finished, String url) {
                    // The page's own scripts have run by now, so `extractFp` exists.
                }
            });
            view.addJavascriptInterface(AfpFingerprint.this, "AndroidAfp");
            view.loadUrl("file:///android_asset/afp/host.html");
            web = view;
        });
    }

    /**
     * 在**主线程**上跑一段活并等它做完（已经站在主线程上时直接跑）。
     *
     * <p>为什么是「等」而不是「post 完就走」：构造完立刻要用（{@link #of} 会把
     * {@code web} 交给 {@code evaluateJavascript}），异步建会让第一批指纹撞上 null。
     */
    private void runOnMainAndWait(Runnable work) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            work.run();
            return;
        }
        final CountDownLatch done = new CountDownLatch(1);
        final Throwable[] failure = new Throwable[1];
        main.post(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("主线程建 WebView 超时（10 s）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等主线程建 WebView 时被打断", e);
        }
        if (failure[0] != null) {
            throw new IllegalStateException("主线程建 WebView 失败", failure[0]);
        }
    }

    /** The page's success callback. */
    @JavascriptInterface
    public void result(String fingerprint) {
        answer = fingerprint;
        CountDownLatch latch = waiting;
        if (latch != null) latch.countDown();
    }

    /** The page's failure callback. */
    @JavascriptInterface
    public void error(String why) {
        failure = why;
        CountDownLatch latch = waiting;
        if (latch != null) latch.countDown();
    }

    /**
     * The fingerprint of a 3 s window of 8 kHz mono samples — the base64 blob the match endpoint
     * wants. Blocking (the caller is the recogniser's own thread) and bounded by
     * {@link #TIMEOUT_MS}.
     *
     * @throws Exception when the engine could not answer, which the caller reports as a failed
     *                   attempt rather than as a failed feature
     */
    public String of(float[] pcm) throws Exception {
        if (pcm == null || pcm.length == 0) throw new IllegalArgumentException("no samples");
        byte[] raw = new byte[pcm.length * 4];
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (float sample : pcm) buffer.putFloat(sample);
        final String encoded = Base64.encodeToString(raw, Base64.NO_WRAP);
        answer = null;
        failure = null;
        final CountDownLatch latch = new CountDownLatch(1);
        waiting = latch;
        main.post(() -> {
            WebView view = web;
            if (view == null) {
                failure = "engine closed";
                latch.countDown();
                return;
            }
            view.evaluateJavascript("window.extractFp('" + encoded + "')", null);
        });
        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("指纹引擎超时");
        }
        waiting = null;
        if (failure != null) throw new IllegalStateException("指纹引擎失败：" + failure);
        if (answer == null || answer.isEmpty()) throw new IllegalStateException("指纹为空");
        return answer;
    }

    /** Release the WebView. Safe to call more than once（{@code destroy()} 同样只能在主线程，
     *  所以走 {@link #runOnMainAndWait}，调用方在哪个线程都行）。 */
    public void close() {
        final WebView view = web;
        web = null;
        if (view == null) return;
        runOnMainAndWait(() -> {
            view.removeJavascriptInterface("AndroidAfp");
            view.destroy();
        });
    }
}
