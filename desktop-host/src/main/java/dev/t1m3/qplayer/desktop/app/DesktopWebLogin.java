package dev.t1m3.qplayer.desktop.app;

import ca.weblite.webview.swing.WebViewComponent;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * In-process official-site login using the OS browser engine supplied by
 * swingwebview. The native cookie store is queried directly so the
 * provider-declared HttpOnly credential cookie is available; no second JVM,
 * local HTTP callback, or credential log is involved.
 */
final class DesktopWebLogin {
    private static final String WEBVIEW2_DATA_ENV = "WEBVIEW2_USER_DATA_FOLDER";
    private static final int CLEANUP_ATTEMPTS = 20;
    private static final ScheduledExecutorService DATA_CLEANER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "qplayer-webview-data-cleaner");
                thread.setDaemon(true);
                return thread;
            });

    private static JFrame activeFrame;

    private DesktopWebLogin() {}

    static void open(String loginUrl, String cookieUrl, String credentialCookieName,
            String providerName, Consumer<String> onCookie, Consumer<String> onFailure,
            Runnable onCancel) {
        if (!validHttpsUrl(loginUrl) || !validHttpsUrl(cookieUrl)
                || credentialCookieName == null
                || !credentialCookieName.matches("[A-Za-z0-9_.-]{1,64}")) {
            onFailure.accept("音源插件提供了无效的网页登录配置");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (activeFrame != null && activeFrame.isDisplayable()) {
                DesktopSwingFocus.requestForeground(activeFrame);
                return;
            }
            try {
                createWindow(loginUrl, cookieUrl, credentialCookieName, providerName,
                        onCookie, onFailure, onCancel);
            } catch (Throwable error) {
                activeFrame = null;
                onFailure.accept(friendlyError(error));
            }
        });
    }

    private static boolean validHttpsUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            java.net.URI uri = java.net.URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** Close an in-flight login before the desktop host exits. JFrame disposal
     *  alone does not release swingwebview's native peer/WebKit child processes. */
    static void shutdown() {
        Runnable close = () -> {
            JFrame frame = activeFrame;
            if (frame != null && frame.isDisplayable()) frame.dispose();
            activeFrame = null;
        };
        if (SwingUtilities.isEventDispatchThread()) {
            close.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(close);
        } catch (Exception ignored) {
            // The AWT event thread may already be shutting down.
        }
    }

    private static void createWindow(String loginUrl, String cookieUrl,
            String credentialCookieName, String providerName, Consumer<String> onCookie,
            Consumer<String> onFailure, Runnable onCancel) {
        WebView2SessionData sessionData = WebView2SessionData.create();
        WebViewComponent webView;
        try {
            webView = WebViewComponent.create();
            webView.setUrl(loginUrl);
            webView.setPreferredSize(new Dimension(900, 700));
        } catch (Throwable error) {
            sessionData.close();
            throw error;
        }

        JFrame frame = new JFrame("登录" + providerName);
        activeFrame = frame;
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(webView, BorderLayout.CENTER);
        frame.setMinimumSize(new Dimension(640, 560));
        frame.pack();
        frame.setLocationRelativeTo(null);

        final boolean[] submitted = {false};
        final boolean[] queryInFlight = {false};
        final boolean[] disposed = {false};
        final int[] consecutiveFailures = {0};
        final long openedAt = System.currentTimeMillis();
        Timer cookiePoll = new Timer(650, event -> {
            if (queryInFlight[0] || !frame.isDisplayable()) return;
            queryInFlight[0] = true;
            webView.getCookies(cookieUrl).whenComplete((header, error) -> {
                queryInFlight[0] = false;
                if (!frame.isDisplayable() || submitted[0]) return;
                if (error != null) {
                    consecutiveFailures[0]++;
                    // Attach can still be pending during the first few ticks. Only
                    // surface a genuine persistent native-engine failure.
                    if (consecutiveFailures[0] >= 8
                            && System.currentTimeMillis() - openedAt >= 8_000L) {
                        submitted[0] = true;
                        ((Timer) event.getSource()).stop();
                        frame.dispose();
                        onFailure.accept(friendlyError(error));
                    }
                    return;
                }
                consecutiveFailures[0] = 0;
                if (!containsLoginCredential(header, credentialCookieName)) return;
                submitted[0] = true;
                ((Timer) event.getSource()).stop();
                frame.dispose();
                onCookie.accept(header);
            });
        });
        cookiePoll.setInitialDelay(900);

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent event) {
                cookiePoll.start();
            }

            @Override public void windowClosed(WindowEvent event) {
                cookiePoll.stop();
                if (!disposed[0]) {
                    disposed[0] = true;
                    try {
                        webView.dispose();
                    } catch (Throwable ignored) {
                    }
                    sessionData.close();
                }
                if (activeFrame == frame) activeFrame = null;
                if (!submitted[0]) onCancel.run();
            }
        });
        DesktopSwingFocus.show(frame);
    }

    /**
     * WebView2 persists HttpOnly login cookies in its user-data directory. A
     * website-login handoff must instead start with a fresh profile every time,
     * otherwise the cookie poll immediately imports the previously used account.
     * Keep the override active until the native peer is disposed because the
     * WebView2 environment is created asynchronously on its engine thread.
     */
    private static final class WebView2SessionData implements AutoCloseable {
        private static final WebView2SessionData NONE =
                new WebView2SessionData(null, null, false);

        private final Path directory;
        private final String previousOverride;
        private final boolean windows;
        private boolean closed;

        private WebView2SessionData(Path directory, String previousOverride,
                boolean windows) {
            this.directory = directory;
            this.previousOverride = previousOverride;
            this.windows = windows;
        }

        static WebView2SessionData create() {
            if (!isWindows()) return NONE;

            Path directory = null;
            try {
                Path root = Path.of(System.getProperty("java.io.tmpdir"),
                        "QPlayer", "web-login").toAbsolutePath().normalize();
                Files.createDirectories(root);
                directory = Files.createTempDirectory(root, "session-");
                String previous = System.getenv(WEBVIEW2_DATA_ENV);
                if (!Kernel32.INSTANCE.SetEnvironmentVariable(
                        WEBVIEW2_DATA_ENV, directory.toString())) {
                    throw new IOException("SetEnvironmentVariable failed: "
                            + Native.getLastError());
                }
                return new WebView2SessionData(directory, previous, true);
            } catch (Throwable error) {
                if (directory != null) scheduleDelete(directory, 0);
                throw new IllegalStateException(
                        "无法创建临时 WebView2 登录数据目录", error);
            }
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            if (!windows) return;

            // Restore a caller-provided override after the login-only native peer
            // has stopped using our session directory.
            Kernel32.INSTANCE.SetEnvironmentVariable(
                    WEBVIEW2_DATA_ENV, previousOverride);
            scheduleDelete(directory, 0);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void scheduleDelete(Path directory, int attempt) {
        if (directory == null) return;
        long delayMs = attempt == 0 ? 250L : Math.min(2_000L, 250L * attempt);
        DATA_CLEANER.schedule(() -> {
            try {
                deleteTree(directory);
            } catch (IOException error) {
                // WebView2 child processes may briefly retain files after the
                // component is disposed. Retry without blocking Swing's EDT.
                if (attempt + 1 < CLEANUP_ATTEMPTS) {
                    scheduleDelete(directory, attempt + 1);
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path dir,
                    IOException error) throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean containsLoginCredential(String header, String cookieName) {
        if (header == null || header.isEmpty()) return false;
        String prefix = cookieName + "=";
        for (String part : header.split(";")) {
            String pair = part.trim();
            if (pair.startsWith(prefix) && pair.length() > prefix.length()) {
                return true;
            }
        }
        return false;
    }

    private static String friendlyError(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) {
            return "无法启动系统 WebView，请确认已安装 WebKitGTK 4.1";
        }
        if (os.contains("win")) {
            return "无法启动系统 WebView，请确认已安装 WebView2 Runtime";
        }
        return "无法启动系统 WebView，请使用粘贴 Cookie 登录";
    }
}
