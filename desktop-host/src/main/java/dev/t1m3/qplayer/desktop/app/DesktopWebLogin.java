package dev.t1m3.qplayer.desktop.app;

import ca.weblite.webview.swing.WebViewComponent;
import dev.t1m3.qplayer.i18n.I18n;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
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
            onFailure.accept(I18n.tr("login.error.badWebConfig"));
            return;
        }
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            onFailure.accept(I18n.tr("login.error.noSystemWebView"));
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
            webView.setPreferredSize(preferredLoginSize());
        } catch (Throwable error) {
            sessionData.close();
            throw error;
        }

        JFrame frame = new JFrame(I18n.tr("login.title", providerName));
        activeFrame = frame;
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(webView, BorderLayout.CENTER);
        frame.setMinimumSize(new Dimension(640, 560));
        frame.pack();
        frame.setLocationRelativeTo(null);
        // swingwebview sizes its native child from AWT logical pixels, so on a scaled
        // display the page only covers 1/scale of the window. Keep it matched to the
        // real client area instead. See syncNativeWebViewBounds.
        Timer nativeFit = new Timer(300, event -> syncNativeWebViewBounds(frame));
        nativeFit.start();
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                syncNativeWebViewBounds(frame);
            }
        });

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
                nativeFit.stop();
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

    /** Class name swingwebview gives the HWND it parents the browser into. */
    private static final String NATIVE_WEBVIEW_CLASS = "WebViewEmbedChild";
    /** Class name of the AWT heavyweight peer that HWND is a child of. */
    private static final String AWT_CANVAS_CLASS = "SunAwtCanvas";

    /** Roughly four fifths of the work area, so the login page is not a small
     *  box in the middle of a large screen, but still clearly a dialog. */
    private static Dimension preferredLoginSize() {
        try {
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            int width = Math.max(900, Math.min(1400, (int) (screen.width * 0.8)));
            int height = Math.max(700, Math.min(1000, (int) (screen.height * 0.8)));
            return new Dimension(width, height);
        } catch (Throwable ignored) {
            return new Dimension(900, 700);
        }
    }

    /**
     * swingwebview's sizeNative() hands {@code canvas.getSize()} — AWT logical
     * pixels — to a native SetWindowPos call that works in physical pixels, and
     * compensates for nothing. At 125% display scaling a 900x700 canvas occupies
     * 1125x875 real pixels while the browser child stays 900x700, so the page
     * covers the top-left 80% and leaves the rest blank.
     *
     * <p>Resize the child to the canvas's real client rect ourselves; WebView2's
     * own windows follow their parent, so the page then fills the window. Both
     * class names come from the library and the AWT peer, so if either ever
     * changes this quietly does nothing and we are no worse off than before.
     */
    private static void syncNativeWebViewBounds(JFrame frame) {
        if (!isWindows() || frame == null || !frame.isDisplayable()) return;
        try {
            Pointer handle = Native.getWindowPointer(frame);
            if (handle == null) return;
            WinDef.HWND top = new WinDef.HWND(handle);
            WinDef.HWND canvas = findChildByClass(top, AWT_CANVAS_CLASS);
            WinDef.HWND child = findChildByClass(top, NATIVE_WEBVIEW_CLASS);
            if (canvas == null || child == null) return;
            WinDef.RECT want = new WinDef.RECT();
            User32.INSTANCE.GetClientRect(canvas, want);
            int width = want.right - want.left;
            int height = want.bottom - want.top;
            if (width <= 0 || height <= 0) return;
            WinDef.RECT have = new WinDef.RECT();
            User32.INSTANCE.GetClientRect(child, have);
            if (have.right - have.left == width && have.bottom - have.top == height) return;
            User32.INSTANCE.SetWindowPos(child, null, 0, 0, width, height,
                    0x0004 /* SWP_NOZORDER */ | 0x0010 /* SWP_NOACTIVATE */);
        } catch (Throwable ignored) {
            // A native-layout mismatch must never take the login window down.
        }
    }

    private static WinDef.HWND findChildByClass(WinDef.HWND parent, String wanted) {
        final WinDef.HWND[] found = {null};
        User32.INSTANCE.EnumChildWindows(parent, (hwnd, data) -> {
            if (found[0] != null) return false;
            char[] name = new char[256];
            User32.INSTANCE.GetClassName(hwnd, name, name.length);
            if (wanted.equals(Native.toString(name))) found[0] = hwnd;
            return found[0] == null;
        }, Pointer.NULL);
        return found[0];
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
                        "failed to create the temporary WebView2 login data directory", error);
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
            return I18n.tr("login.error.webkitgtk");
        }
        if (os.contains("win")) {
            return I18n.tr("login.error.webview2");
        }
        return I18n.tr("login.error.noSystemWebView");
    }
}
