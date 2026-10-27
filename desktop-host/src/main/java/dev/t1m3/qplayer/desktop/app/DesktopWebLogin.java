package dev.t1m3.qplayer.desktop.app;

import dev.t1m3.qplayer.i18n.I18n;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Official-site login hosted by the original system WebView native engines. */
final class DesktopWebLogin {
    private static Session active;

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
        if (DesktopNativeBrowser.headless()) {
            onFailure.accept(I18n.tr("login.error.noSystemWebView"));
            return;
        }
        DesktopNativeBrowser.EVENTS.execute(() -> {
            if (active != null) {
                active.browser.focus();
                return;
            }
            Session session = new Session(cookieUrl, credentialCookieName, onCookie, onFailure, onCancel);
            try {
                session.browser = new DesktopNativeBrowser(true, I18n.tr("login.title", providerName), null);
                active = session;
                session.browser.navigate(loginUrl);
                session.poll = DesktopNativeBrowser.EVENTS.scheduleWithFixedDelay(session::poll, 300, 650, TimeUnit.MILLISECONDS);
            } catch (Throwable error) {
                session.finish(null, error, false);
            }
        });
    }

    private static boolean validHttpsUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static void shutdown() {
        DesktopNativeBrowser.runAndWait(() -> {
            if (active != null) active.finish(null, null, true);
        });
    }

    private static final class Session {
        final String cookieUrl;
        final String cookieName;
        final Consumer<String> onCookie;
        final Consumer<String> onFailure;
        final Runnable onCancel;
        final long openedAt = System.nanoTime();
        DesktopNativeBrowser browser;
        ScheduledFuture<?> poll;
        boolean queryInFlight;
        boolean finished;
        int failures;

        Session(String cookieUrl, String cookieName, Consumer<String> onCookie,
                Consumer<String> onFailure, Runnable onCancel) {
            this.cookieUrl = cookieUrl;
            this.cookieName = cookieName;
            this.onCookie = onCookie;
            this.onFailure = onFailure;
            this.onCancel = onCancel;
        }

        void poll() {
            if (finished) return;
            try {
                if (!browser.isOpen()) {
                    finish(null, null, true);
                    return;
                }
                if (queryInFlight) return;
                queryInFlight = true;
                browser.getCookies(cookieUrl).whenComplete((header, error) -> {
                    queryInFlight = false;
                    if (finished) return;
                    if (!browser.isOpen()) {
                        finish(null, null, true);
                        return;
                    }
                    if (error != null) {
                        failures++;
                        if (failures >= 8 && System.nanoTime() - openedAt >= TimeUnit.SECONDS.toNanos(8)) {
                            finish(null, error, false);
                        }
                        return;
                    }
                    failures = 0;
                    if (containsLoginCredential(header, cookieName)) finish(header, null, false);
                });
            } catch (Throwable error) {
                finish(null, error, false);
            }
        }

        void finish(String header, Throwable error, boolean cancelled) {
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
            if (error != null) onFailure.accept(friendlyError(error));
            else if (cancelled) onCancel.run();
            else onCookie.accept(header);
        }
    }

    private static boolean containsLoginCredential(String header, String cookieName) {
        if (header == null || header.isEmpty()) return false;
        String prefix = cookieName + "=";
        for (String part : header.split(";")) {
            String pair = part.trim();
            if (pair.startsWith(prefix) && pair.length() > prefix.length()) return true;
        }
        return false;
    }

    private static String friendlyError(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) cause = cause.getCause();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) return I18n.tr("login.error.webkitgtk");
        if (os.startsWith("windows")) return I18n.tr("login.error.webview2");
        return I18n.tr("login.error.noSystemWebView");
    }
}
