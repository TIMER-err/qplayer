package dev.t1m3.qplayer.desktop;

import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/**
 * Single-instance guard. The first instance takes an exclusive OS lock on
 * {@code ~/.qplayer/instance.lock} (released automatically when the process dies,
 * unlike a PID file) and listens on a loopback socket whose port it records in
 * {@code instance.port}. A second launch fails the lock, pings that port to bring
 * the running window to the front, and exits.
 */
final class SingleInstance {

    private static final int ASFW_ANY = -1;

    private static FileChannel channel;   // held open for the process lifetime
    private static FileLock lock;
    private static ServerSocket server;

    // Windows only: lets this (foreground) process grant the running instance the
    // right to raise its window — otherwise SetForegroundWindow from a background
    // process is downgraded to a taskbar flash.
    interface U32 extends StdCallLibrary {
        U32 I = Native.load("user32", U32.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean AllowSetForegroundWindow(int processId);
    }

    private SingleInstance() {}

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * @param onActivate invoked (on a background thread) when another launch pings
     *                   this instance — wire it to raise the window.
     * @return true if this is the sole instance (proceed); false if another instance
     *         is already running (it was signalled; the caller should exit).
     */
    static boolean acquire(Runnable onActivate) {
        File dir = new File(AppDirs.base());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File lockFile = new File(dir, "instance.lock");
        File portFile = new File(dir, "instance.port");

        try {
            channel = FileChannel.open(lockFile.toPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
        } catch (Throwable t) {
            // Locking unavailable (odd filesystem) — fail open: better to allow a
            // second instance than to refuse to start at all.
            Logger.warn("single-instance lock unavailable ({}); not enforcing", t.toString());
            return true;
        }

        if (lock == null) {
            // Another instance holds the lock — ping it to surface, then bow out.
            pingExisting(portFile);
            closeQuietly();
            return false;
        }

        // We're first. Start the activation listener and record its port.
        try {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            Files.writeString(portFile.toPath(), Integer.toString(server.getLocalPort()),
                    StandardCharsets.US_ASCII);
            Thread t = new Thread(() -> {
                while (server != null && !server.isClosed()) {
                    try (Socket ignored = server.accept()) {
                        onActivate.run();
                    } catch (IOException e) {
                        if (server == null || server.isClosed()) break;
                    } catch (Throwable ignored) {
                    }
                }
            }, "qplayer-single-instance");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            // No IPC channel — the lock alone still guarantees single-instance; a
            // second launch just won't be able to raise this window.
            Logger.warn("single-instance activation listener unavailable: {}", t.toString());
        }
        return true;
    }

    private static void pingExisting(File portFile) {
        try {
            int port = Integer.parseInt(Files.readString(portFile.toPath()).trim());
            // Grant the running instance permission to foreground itself before we
            // poke it, so it actually raises instead of just flashing the taskbar.
            if (isWindows()) {
                try { U32.I.AllowSetForegroundWindow(ASFW_ANY); } catch (Throwable ignored) {}
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 800);
                OutputStream os = s.getOutputStream();
                os.write('A');
                os.flush();
            }
        } catch (Throwable t) {
            Logger.warn("could not signal the running instance: {}", t.toString());
        }
    }

    private static void closeQuietly() {
        try { if (channel != null) channel.close(); } catch (Throwable ignored) {}
    }
}
