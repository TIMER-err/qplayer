package dev.t1m3.qplayer.desktop.lyric.tempera;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.Syllable;
import dev.t1m3.qplayer.lyric.skia.LyricConfig;

import io.github.humbleui.skija.Canvas;

import java.util.ArrayList;
import java.util.List;

/**
 * 「凝彩」全屏页的宿主桥：把 {@link PlayerController} 的歌词与播放时钟接到
 * {@link TemperaPageRenderer} 上。
 *
 * <p>这一层<b>完全独立</b>于原有歌词页：它自己编译歌词程序、自己外推时钟、自己决定主题色，
 * 原有的 {@code LyricCompositor} / {@code LyricRenderer} / {@code LyricOverlay.qml} 一行都不改。
 * 宿主只在「凝彩」路由打开时调用 {@link #render}。
 */
public final class TemperaHostPage {

    private final TemperaPageRenderer renderer = new TemperaPageRenderer();
    private TemperaTuning tuning = new TemperaTuning();

    /**
     * 歌词页的滑入进度（0 关 / 1 完全盖住）。标准歌词页由 {@code LyricCompositor} 推进并发布
     * 到 {@code controller.lyricSlide}；凝彩接管这一帧时，同一根弹簧必须由这里推进——否则
     * QML 侧所有依赖 {@code lyricSlide} 的东西（标题栏隐藏、歌词控件层）都会以为页面还没开，
     * 于是标题栏一直压在画面上把点击全吃掉。
     */
    private float slide;

    private List<LyricLine> lastLyrics;
    private TemperaTypes.Program program;
    private String lastCoverSeed;
    private boolean lastDark;
    private TemperaPalette.Theme lastTheme;
    private boolean programDirty = true;

    // 墙钟外推：与 LyricCompositor 同一套判据，保证「凝彩」与别的画面同一时刻。
    private long rawLast = -1;
    private boolean clockRunningLast;
    private long baseMs;
    private long baseNanos;
    private long playbackRevision = -1L;
    private long clockSeekRevision = -1L;

    public void dispose() {
        renderer.dispose();
    }

    /**
     * 这一帧该不该由凝彩出画：开了凝彩模式，且歌词页正开着（或者还在关闭的滑出动画里）。
     *
     * <p>关页的最后几帧仍归凝彩画，否则页面会在滑出的中途被硬切回标准歌词。
     */
    public boolean wantsFrame(PlayerController controller, boolean temperaEnabled) {
        if (controller == null || !temperaEnabled) return false;
        boolean open = Boolean.TRUE.equals(controller.lyricsOpen.peek());
        return open || slide > 0.001f;
    }

    /** 平滑后的滑入进度，供宿主把整帧从底部推上来。 */
    public float slideEase() {
        return slide * slide * (3f - 2f * slide);
    }

    public void advanceSlide(PlayerController controller) {
        boolean open = Boolean.TRUE.equals(controller.lyricsOpen.peek());
        float target = open ? 1f : 0f;
        slide += (target - slide) * 0.18f;
        if (Math.abs(target - slide) < 0.002f) slide = target;
        controller.lyricSlide.set((double) slide);
    }

    /** 用当前设置刷新调参；只有真正影响「场景是什么」的项才触发重建。 */
    public void configure(TemperaTuning next, float fontScale, boolean staticMode) {
        if (next != null) {
            boolean wholeLineChanged = this.tuning.wholeLineLyrics != next.wholeLineLyrics;
            renderer.setTuning(next);
            this.tuning = next;
            if (wholeLineChanged) programDirty = true;
        }
        renderer.setLyricsFontScale(fontScale);
        renderer.setStaticMode(staticMode);
    }

    /**
     * 编译当前曲目的凝彩程序。歌词列表按引用比较——解析器只在切歌/重新解析时换对象，
     * 所以这不会在每帧重编。
     *
     * @return 程序是否被替换（调用方据此重新装配渲染器）
     */
    private boolean ensureProgram(PlayerController controller) {
        List<LyricLine> lyrics = controller.lyrics.peek();
        if (lyrics == lastLyrics && !programDirty) return false;
        programDirty = false;
        lastLyrics = lyrics;
        if (lyrics == null || lyrics.isEmpty()) {
            program = null;
            return true;
        }
        List<TemperaTypes.SourceLine> source = new ArrayList<>();
        for (LyricLine line : lyrics) {
            if (line == null) continue;
            String text = line.text();
            if (text == null || text.trim().isEmpty()) continue;
            double start = line.startMs() / 1000.0;
            double end = line.endMs() / 1000.0;
            if (end <= start) end = start + 0.6;
            TemperaTypes.SourceLine sourceLine = new TemperaTypes.SourceLine(text, start, end);
            for (Syllable syllable : line.syllables) {
                if (syllable == null || syllable.text == null) continue;
                double syllableStart = syllable.startMs / 1000.0;
                double syllableEnd = syllable.endMs() / 1000.0;
                if (syllableEnd <= syllableStart) syllableEnd = syllableStart + 0.05;
                TemperaTypes.Word word = new TemperaTypes.Word(
                        syllable.text, syllableStart, syllableEnd);
                word.addSyllable(syllable.text, syllableStart, syllableEnd);
                sourceLine.addWord(word);
            }
            source.add(sourceLine);
        }
        if (source.isEmpty()) {
            program = null;
            return true;
        }
        String seed = controller.coverSeed.peek();
        if (seed == null || seed.isEmpty()) seed = "tempera";
        program = TemperaProgram.compile(source, seed, tuning);
        return true;
    }

    /**
     * 画一帧「凝彩」。{@code w}/{@code h} 是逻辑像素，{@code uiScale} 由画布换算，
     * {@code nowNanos} 是本帧的墙钟，用于平滑外推播放位置。
     */
    public void render(Canvas canvas, PlayerController controller, float uiScale,
                       float w, float h, long nowNanos, boolean dark) {
        if (controller == null) return;
        boolean programChanged = ensureProgram(controller);

        String coverSeed = controller.coverSeed.peek();
        if (coverSeed == null) coverSeed = "";
        boolean themeChanged = !coverSeed.equals(lastCoverSeed) || dark != lastDark;
        if (themeChanged) {
            lastCoverSeed = coverSeed;
            lastDark = dark;
            lastTheme = themeFor(coverSeed, dark);
        }
        if (programChanged || themeChanged || lastTheme == null) {
            if (lastTheme == null) lastTheme = themeFor(coverSeed, dark);
            // program 可能是 null（这首歌没有歌词）：调色板仍然要装配，否则待机画面连纸色都
            // 没有，整帧就是一块黑板。
            renderer.setProgram(program, lastTheme, null);
        }

        double timeSec = extrapolatedSeconds(controller, nowNanos);
        renderer.render(canvas, timeSec, uiScale, w, h,
                text(controller.title.peek()), text(controller.artist.peek()));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    /** 与歌词页完全一致的位置外推。 */
    private double extrapolatedSeconds(PlayerController controller, long nowNanos) {
        long durationMs = controller.durationMs.peek();
        boolean clockRunning = controller.isLyricClockRunning();
        long raw = controller.lyricClockPosition();
        long currentPlaybackRevision = controller.playbackRevision();
        long currentClockSeekRevision = controller.seekRevision();
        boolean discontinuity = currentPlaybackRevision != playbackRevision
                || currentClockSeekRevision != clockSeekRevision;
        if (discontinuity || raw != rawLast || clockRunning != clockRunningLast) {
            rawLast = raw;
            clockRunningLast = clockRunning;
            baseMs = raw;
            baseNanos = nowNanos;
            playbackRevision = currentPlaybackRevision;
            clockSeekRevision = currentClockSeekRevision;
        }
        long predicted = clockRunning ? baseMs + (nowNanos - baseNanos) / 1_000_000L : baseMs;
        if (durationMs > 0 && predicted > durationMs) predicted = durationMs;
        long relative = predicted - LyricConfig.instance.offsetMs.getValue();
        return relative / 1000.0;
    }

    /**
     * 从当前主题推导凝彩的四色输入。qplayer 的主题色没有直接暴露到渲染线程，所以用
     * Material You 的封面种子色（{@code coverSeed}）作为色相来源，纸墨按深浅色模式取定值：
     * 这与 folia 的「纸 / 墨 / 两个彩色」结构一致。
     */
    private static TemperaPalette.Theme themeFor(String coverSeed, boolean dark) {
        String paper = dark ? "#0B0B10" : "#F2F0E8";
        String ink = dark ? "#F2F0E8" : "#0B0B10";
        String primary = (coverSeed != null && coverSeed.startsWith("#") && coverSeed.length() >= 7)
                ? coverSeed : (dark ? "#8C86A8" : "#5A5470");
        // 次色向墨压暗，重音向纸提亮：两者都还带着种子色的色相。
        String secondary = TemperaColor.toHex(TemperaColor.mixColors(primary, ink, 0.42f, 1f));
        String accent = TemperaColor.toHex(TemperaColor.mixColors(primary, paper, 0.35f, 1f));
        return new TemperaPalette.Theme(paper, primary, secondary, accent);
    }
}
