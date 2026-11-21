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

    /**
     * <b>第三方库的 INFO 噪音压掉</b>（2026-10-05）。
     *
     * <p>用户日志里出现过成片的
     * {@code org.jaudiotagger.audio.wav.WavInfoReader readChunk 信息: … Reading Chunk:fmt …}——
     * jaudiotagger 用 {@code java.util.logging} 在 **INFO** 级逐块打印它读到的每个 WAV chunk，
     * 于是曲库里每有一个 WAV（我们自己的演示素材就写在那儿）就会刷十几行，把真正的引擎日志淹掉。
     *
     * <p>这不是我们的日志，也不该由我们按级别过滤——直接把那个 logger 调高到 WARNING：
     * 它真正出错时（读不了文件）仍然会说话，只是不再报"我读到了 fmt 块"这种过程信息。
     */
    static {
        try {
            java.util.logging.Logger.getLogger("org.jaudiotagger")
                    .setLevel(java.util.logging.Level.WARNING);
        } catch (RuntimeException ignored) {
            // 日志设置失败不能影响播放：拿不到就不设，最坏情况是噪音还在。
        }
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
            // "D" 也走 INFO：它只有在详细开关打开时才会走到这里，而打开的目的就是**看见**它
            // ——映射成 FINE 的话 logcat 默认不显示，等于开了也白开。
            // 想区分时看环形缓冲里的前缀（"D "/"I "）或行首的 [qplayer-engine]。
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

    /**
     * <b>过程细节</b>：默认<b>一条都不输出</b>（不进 sink、也不进日志面板的环形缓冲）。
     *
     * <p>为什么要有这一级：自动混音一次换曲原本会打十几行（拍栅格怎么来的、窗口候选怎么排的、
     * 去人声曲线长什么样、准备余量为什么抬高……）。这些在排查时是证据，正常听歌时是噪音
     * ——用户 2026-10-03 的原话是「现在太繁琐了」。所以把它们整体降到这里：正常听歌每次
     * 换曲只剩几行摘要，要排查时打开设置里那一项
     * （{@code SettingsCatalog.AUTOMIX_DIAGNOSTIC_LOG_KEY}）就原样回来。
     *
     * <p><b>关着的时候短路在 {@code format} 之前</b>：连占位符替换都不跑。
     * 但这只省掉<b>拼串</b>这一步——写在<b>实参</b>里的计算（{@code String.format}、
     * {@code describeRanges(...)} 之类）仍然会先算出来，因为它们在进入本方法之前就求值了。
     * 默认路径上真不想付那种开销，调用处要写成 {@code if (Logger.verbose())}——
     * 本项目里只有两处值得这么写（窗口里叠唱时段的区间列表、交接位置那一行），
     * 其余那些参数本来就是要算的状态。
     */
    public static void debug(String str, Object... o) {
        if (!VERBOSE) {
            return;
        }
        record("D", format(str, o));
    }

    /** 过程细节是否输出（{@link #debug}）。默认关。 */
    public static boolean verbose() {
        return VERBOSE;
    }

    /**
     * 开关过程细节。宿主把它接到设置项
     * {@code SettingsCatalog.AUTOMIX_DIAGNOSTIC_LOG_KEY}（由 {@code SettingsCore} 直接落地），
     * 另外也认系统属性 / 环境变量 {@code qplayer.verbose}——跑测试、命令行排查时不用改设置。
     */
    public static void setVerbose(boolean on) {
        VERBOSE = on;
    }

    private static volatile boolean VERBOSE = initialVerbose();

    private static boolean initialVerbose() {
        if (Boolean.getBoolean("qplayer.verbose")) {
            return true;
        }
        String env = System.getenv("QPLAYER_VERBOSE");
        return env != null && !env.isEmpty() && !"0".equals(env) && !"false".equalsIgnoreCase(env);
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
