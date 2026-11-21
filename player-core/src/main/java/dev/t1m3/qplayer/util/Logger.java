package dev.t1m3.qplayer.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Minimal SLF4J-style logger ({@code {}} placeholders) with a pluggable output
 * {@link Sink}. Mirrors the subset of the Haedus logger API used by the migrated
 * netease / lyric sources so those files copy over unchanged.
 *
 * <p>The default sink writes to {@code java.util.logging} (→ logcat on Android,
 * which is where the shared code runs unchanged). The desktop host swaps in a
 * log4j2 sink via {@link #setSink} at startup, keeping the log4j dependency out
 * of player-core (and off the Android classpath).
 *
 * <p>Also keeps a capped in-memory ring buffer so an on-device log panel (the
 * QML bridge polls {@link #version()} / {@link #snapshot()}) can surface what's
 * happening without adb/logcat — independent of whichever sink is installed.
 */
public final class Logger {

    /** Output backend. {@code level} is one of {@code "I"}, {@code "W"}, {@code "E"}. */
    public interface Sink {
        void log(String level, String message);
        void exception(String message, Throwable t);
    }

    /** Default sink: java.util.logging (logcat on Android, console elsewhere). */
    private static final Sink JUL_SINK = new Sink() {
        private final java.util.logging.Logger delegate =
                java.util.logging.Logger.getLogger("musicplayer");

        @Override public void log(String level, String message) {
            delegate.log(julLevel(level), message);
        }

        @Override public void exception(String message, Throwable t) {
            delegate.log(Level.SEVERE, message, t);
        }

        private Level julLevel(String level) {
            return "E".equals(level) ? Level.SEVERE
                    : "W".equals(level) ? Level.WARNING : Level.INFO;
        }
    };

    private static volatile Sink sink = JUL_SINK;

    private static final int CAPACITY = 200;
    private static final Deque<String> RING = new ArrayDeque<>(CAPACITY);
    private static final AtomicLong VERSION = new AtomicLong();

    private Logger() {
    }

    /** Install a custom output backend (e.g. the desktop log4j2 sink). Pass
     *  {@code null} to fall back to the default java.util.logging sink. */
    public static void setSink(Sink s) {
        sink = s != null ? s : JUL_SINK;
    }

    public static void info(String str, Object... o) {
        record("I", format(str, o));
    }

    public static void warn(String str, Object... o) {
        record("W", format(str, o));
    }

    public static void error(String str, Object... o) {
        record("E", format(str, o));
    }

    public static void success(String str, Object... o) {
        record("I", format(str, o));
    }

    public static void exception(Throwable ex) {
        ringAdd("E", ex.toString());
        sink.exception(ex.getMessage(), ex);
    }

    private static void record(String level, String msg) {
        ringAdd(level, msg);
        sink.log(level, msg);
    }

    private static void ringAdd(String level, String msg) {
        synchronized (RING) {
            if (RING.size() >= CAPACITY) RING.removeFirst();
            RING.addLast(level + " " + msg);
        }
        VERSION.incrementAndGet();
    }

    /** Bumped on every log line — cheap change check for the UI poller. */
    public static long version() {
        return VERSION.get();
    }

    /** Snapshot of the buffered lines, oldest first. */
    public static List<String> snapshot() {
        synchronized (RING) {
            return new ArrayList<>(RING);
        }
    }

    public static void clear() {
        synchronized (RING) {
            RING.clear();
        }
        VERSION.incrementAndGet();
    }

    private static String format(String str, Object... o) {
        if (str == null || o == null || o.length == 0) {
            return str;
        }
        StringBuilder sb = new StringBuilder();
        int arg = 0;
        int i = 0;
        while (i < str.length()) {
            if (arg < o.length && i + 1 < str.length()
                    && str.charAt(i) == '{' && str.charAt(i + 1) == '}') {
                sb.append(String.valueOf(o[arg++]));
                i += 2;
            } else {
                sb.append(str.charAt(i++));
            }
        }
        return sb.toString();
    }
}
