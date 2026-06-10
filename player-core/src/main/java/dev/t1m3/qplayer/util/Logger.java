package dev.t1m3.qplayer.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Minimal SLF4J-style logger ({@code {}} placeholders) backed by
 * {@code java.util.logging}. Mirrors the subset of the Haedus logger API used
 * by the migrated netease / lyric sources so those files copy over unchanged.
 *
 * <p>Also keeps a capped in-memory ring buffer so an on-device log panel (the
 * QML bridge polls {@link #version()} / {@link #snapshot()}) can surface what's
 * happening without adb/logcat.
 */
public final class Logger {

    private static final java.util.logging.Logger DELEGATE =
            java.util.logging.Logger.getLogger("musicplayer");

    private static final int CAPACITY = 200;
    private static final Deque<String> RING = new ArrayDeque<>(CAPACITY);
    private static final AtomicLong VERSION = new AtomicLong();

    private Logger() {
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
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        record("E", ex.toString());
        DELEGATE.log(Level.SEVERE, ex.getMessage(), ex);
    }

    private static void record(String level, String msg) {
        Level jul = "E".equals(level) ? Level.SEVERE
                : "W".equals(level) ? Level.WARNING : Level.INFO;
        DELEGATE.log(jul, msg);
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
