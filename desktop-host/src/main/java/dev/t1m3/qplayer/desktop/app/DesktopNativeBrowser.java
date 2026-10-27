package dev.t1m3.qplayer.desktop.app;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Standalone JNI hosting of swingwebview's native engines, without its AWT layer. */
final class DesktopNativeBrowser implements AutoCloseable {
    private static volatile Thread eventThread;
    static final ScheduledExecutorService EVENTS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "qplayer-native-browser");
        thread.setDaemon(true);
        eventThread = thread;
        return thread;
    });
    private static final Gson GSON = new Gson();
    private final Map<String, CompletableFuture<String>> evaluations = new HashMap<>();
    private final Map<CookieReply, CompletableFuture<String>> cookieQueries = new HashMap<>();
    private final Path profile;
    private final Consumer<String> resultHandler;
    private final String resultToken = UUID.randomUUID().toString();
    private long peer;

    DesktopNativeBrowser(boolean visible, String title, Consumer<String> resultHandler) throws IOException {
        Library.ensureLoaded();
        this.resultHandler = resultHandler;
        profile = windows() ? Files.createTempDirectory("qplayer-webview-session-") : null;
        try {
            peer = create(visible, title, profile == null ? "" : profile.toString(), new Bridge(this));
            if (peer == 0) throw new IllegalStateException("native WebView initialization failed");
        } catch (Throwable failure) {
            deleteProfile();
            throw failure;
        }
    }

    static void runAndWait(Runnable action) {
        if (Thread.currentThread() == eventThread) action.run();
        else CompletableFuture.runAsync(action, EVENTS).join();
    }

    static boolean headless() {
        if (Boolean.getBoolean("java.awt.headless")) return true;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // This backend uses GTK/X11, including XWayland on Wayland desktops.
        return os.contains("linux") && (System.getenv("DISPLAY") == null || System.getenv("DISPLAY").isBlank());
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    void navigate(String url) { checkAlive(); navigate(peer, url); }
    void focus() { if (peer != 0) focus(peer); }
    boolean isOpen() { return peer != 0 && isOpen(peer); }

    CompletableFuture<String> evalAsync(String body) {
        checkAlive();
        String token = UUID.randomUUID().toString();
        CompletableFuture<String> result = new CompletableFuture<>();
        evaluations.put(token, result);
        String js = "(function(){try{var v=(function(){" + body + "})();"
                + send(token, "eval", "String(v)", "null")
                + "}catch(e){" + send(token, "eval", "null", "String(e)") + "}})();";
        evaluate(peer, js);
        return result;
    }

    /** Preparation, origin recheck and result binding execute atomically in one document. */
    CompletableFuture<String> runPrepared(String expectedOrigin, String script) {
        return evalAsync("if(location.origin!==new URL(" + GSON.toJson(expectedOrigin)
                + ").origin)throw new Error('WebView navigated outside the requested origin');"
                + "document.open();document.write('<!doctype html><html><head><meta charset=\"utf-8\">"
                + "</head><body></body></html>');document.close();"
                + "window.qplayerWebAuthDone=(function(){var origin=new URL(" + GSON.toJson(expectedOrigin)
                + ").origin;return function(value){if(location.origin!==origin)return;"
                + send(resultToken, "result", "value==null?null:String(value)", "null")
                + "};})();\n" + script + "\nreturn 'started';");
    }

    private static String send(String token, String kind, String value, String error) {
        return "window.external.invoke(JSON.stringify({name:'__qplayer',token:"
                + GSON.toJson(token) + ",kind:'" + kind + "',value:" + value + ",error:" + error + "}));";
    }

    CompletableFuture<String> getCookies(String url) {
        checkAlive();
        CompletableFuture<String> result = new CompletableFuture<>();
        CookieReply reply = new CookieReply(this);
        cookieQueries.put(reply, result);
        cookies(peer, url, reply);
        return result;
    }

    private void receive(String payload) {
        if (peer == 0) return;
        try {
            JsonObject message = JsonParser.parseString(payload).getAsJsonObject();
            if (!message.has("token") || !message.has("kind")) return;
            String token = message.get("token").getAsString();
            if ("result".equals(message.get("kind").getAsString())) {
                if (resultToken.equals(token) && resultHandler != null) {
                    resultHandler.accept(message.get("value").isJsonNull() ? null : message.get("value").getAsString());
                }
                return;
            }
            CompletableFuture<String> future = evaluations.remove(token);
            if (future == null) return;
            if (!message.get("error").isJsonNull()) {
                future.completeExceptionally(new IllegalStateException(message.get("error").getAsString()));
            } else {
                future.complete(message.get("value").isJsonNull() ? null : message.get("value").getAsString());
            }
        } catch (RuntimeException ignored) {
            // Web content can post arbitrary bridge messages. Only our envelopes count.
        }
    }

    @Override public void close() {
        if (peer == 0) return;
        long nativePeer = peer;
        peer = 0;
        destroy(nativePeer);
        var failure = new IllegalStateException("native WebView session closed");
        for (var future : evaluations.values()) future.completeExceptionally(failure);
        evaluations.clear();
        for (var future : cookieQueries.values()) future.completeExceptionally(failure);
        cookieQueries.clear();
        deleteProfile();
    }

    private void checkAlive() {
        if (peer == 0) throw new IllegalStateException("native WebView session closed");
    }

    private void deleteProfile() {
        if (profile == null) return;
        IOException failure = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                if (!Files.exists(profile)) return;
                Files.walkFileTree(profile, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                        if (error != null) throw error;
                        Files.deleteIfExists(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
                return;
            } catch (IOException error) {
                failure = error;
                try { Thread.sleep(125); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while deleting temporary WebView data", interrupted);
                }
            }
        }
        throw new IllegalStateException("could not delete temporary WebView data", failure);
    }

    /** JNI callbacks are concrete classes so Native Image needs no lambda discovery. */
    static final class Bridge {
        private final DesktopNativeBrowser owner;
        Bridge(DesktopNativeBrowser owner) { this.owner = owner; }
        public void invoke(String message, long ignoredPeer) {
            EVENTS.execute(() -> owner.receive(message));
        }
    }
    static final class CookieReply {
        private final DesktopNativeBrowser owner;
        CookieReply(DesktopNativeBrowser owner) { this.owner = owner; }
        public void completed(String header, String error) {
            EVENTS.execute(() -> {
                CompletableFuture<String> future = owner.cookieQueries.remove(this);
                if (future == null) return;
                if (error == null) future.complete(header);
                else future.completeExceptionally(new IllegalStateException(error));
            });
        }
    }

    private static final class Library {
        static final boolean LOADED = load();
        static void ensureLoaded() { if (!LOADED) throw new IllegalStateException("native browser unavailable"); }
        private static boolean load() {
            String name = System.mapLibraryName("qplayerwebview");
            try (InputStream stream = DesktopNativeBrowser.class.getResourceAsStream("/native-webview/" + name)) {
                if (stream == null) throw new IOException("missing standalone WebView native resource: " + name);
                Path directory = Files.createTempDirectory("qplayer-webview-native-");
                Path library = directory.resolve(name);
                Files.copy(stream, library);
                directory.toFile().deleteOnExit();
                library.toFile().deleteOnExit();
                System.load(library.toAbsolutePath().toString());
                return true;
            } catch (IOException error) {
                throw new IllegalStateException("cannot load standalone WebView native backend", error);
            }
        }
    }

    private static native long create(boolean visible, String title, String dataDirectory, Bridge bridge);
    private static native void navigate(long peer, String url);
    private static native void evaluate(long peer, String script);
    private static native void cookies(long peer, String url, CookieReply reply);
    private static native boolean isOpen(long peer);
    private static native void focus(long peer);
    private static native void destroy(long peer);
}
