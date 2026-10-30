package dev.t1m3.qplayer.desktop.library;

import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches a local music-library folder tree for filesystem changes and triggers
 * a debounced rescan, so adding/removing/editing files under {@code musicFolder}
 * is picked up automatically instead of requiring the user to re-touch Settings
 * or restart the app. LibraryScanner's per-file cache already makes a rescan
 * itself cheap (an unchanged file skips the expensive tag/cover/lyric re-read),
 * so this deliberately just re-triggers a full {@code scan()} on any change
 * rather than diffing individual files itself.
 */
public final class LibraryWatcher {

    private static final long DEBOUNCE_MS = 1000;

    private final Runnable onChange;
    // Session identity is the generation token; workers only use their own resources.
    private volatile Session currentSession;

    public LibraryWatcher(Runnable onChange) {
        this.onChange = onChange;
    }

    /** Start watching {@code root} recursively; safe to call again (e.g. the user
     *  changed musicFolder in Settings) — replaces any previous watch. No-op if
     *  root isn't a real directory. */
    public synchronized void start(String root) {
        stop();
        if (root == null || root.isEmpty()) return;
        Path rootPath = Paths.get(root);
        if (!Files.isDirectory(rootPath)) return;
        WatchService service;
        try {
            service = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            Logger.warn("LibraryWatcher: newWatchService failed: {}", e.getMessage());
            return;
        }
        Session session = new Session(service);
        currentSession = session;
        try (java.util.stream.Stream<Path> walk = Files.walk(rootPath)) {
            walk.filter(Files::isDirectory).forEach(dir -> registerDir(session, dir));
        } catch (IOException e) {
            Logger.warn("LibraryWatcher: initial registration failed: {}", e.getMessage());
        }
        Thread loopThread = new Thread(() -> loop(session), "qplayer-library-watch");
        loopThread.setDaemon(true);
        loopThread.start();
    }

    public synchronized void stop() {
        Session session = currentSession;
        currentSession = null;
        if (session == null) return;
        if (session.pendingRescan != null) {
            session.pendingRescan.cancel();
            session.pendingRescan = null;
        }
        session.debounceTimer.cancel();
        try {
            session.service.close();
        } catch (IOException ignored) {
        }
        session.watchedDirs.clear();
    }

    private synchronized void registerDir(Session session, Path dir) {
        if (currentSession != session) return;
        try {
            WatchKey key = dir.register(session.service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            session.watchedDirs.put(key, dir);
        } catch (IOException e) {
            Logger.warn("LibraryWatcher: register failed for {}: {}", dir, e.getMessage());
        }
    }

    private void loop(Session session) {
        while (currentSession == session) {
            WatchKey key;
            try {
                key = session.service.take();
            } catch (ClosedWatchServiceException | InterruptedException e) {
                return;
            }
            if (currentSession != session) return;
            Path dir = session.watchedDirs.get(key);
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW || dir == null) continue;
                Object ctx = event.context();
                if (kind == StandardWatchEventKinds.ENTRY_CREATE && ctx instanceof Path) {
                    // A newly created subfolder needs its own watch registered, or
                    // files added inside it later would go unnoticed.
                    Path created = dir.resolve((Path) ctx);
                    if (Files.isDirectory(created)) registerDir(session, created);
                }
            }
            boolean valid = key.reset();
            if (!valid) session.watchedDirs.remove(key);
            scheduleRescan(session);
        }
    }

    private synchronized void scheduleRescan(Session session) {
        if (currentSession != session) return;
        if (session.pendingRescan != null) session.pendingRescan.cancel();
        session.pendingRescan = new TimerTask() {
            @Override
            public void run() {
                synchronized (LibraryWatcher.this) {
                    if (currentSession != session || session.pendingRescan != this) return;
                    session.pendingRescan = null;
                    onChange.run();
                }
            }
        };
        session.debounceTimer.schedule(session.pendingRescan, DEBOUNCE_MS);
    }

    private static final class Session {
        final WatchService service;
        final Map<WatchKey, Path> watchedDirs = new ConcurrentHashMap<>();
        final Timer debounceTimer = new Timer("qplayer-library-watch-debounce", true);
        TimerTask pendingRescan;

        Session(WatchService service) {
            this.service = service;
        }
    }
}
