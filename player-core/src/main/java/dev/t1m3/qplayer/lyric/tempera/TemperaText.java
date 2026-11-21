package dev.t1m3.qplayer.lyric.tempera;

import io.github.timer_err.qml4j.render.text.UnicodeBreaks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 歌词文本层的确定性工具，1:1 移植自 folia-major：
 * <ul>
 *   <li>{@code utils/lyrics/graphemeTiming.ts} —— 把解析器给的逐词/逐音节时间映射回整行字素时间轴。</li>
 *   <li>{@code utils/lyrics/renderHints.ts} —— 由行时长推算「这一行最晚可以留到什么时候」的渲染提示。</li>
 * </ul>
 *
 * <p>全部随机性为零：这里只做确定性的时间换算与边界分类，镜头/装饰的随机在 {@link TemperaProgram} 里走
 * {@link TemperaRandom}。时间单位统一为<b>秒</b>（double）。
 */
public final class TemperaText {
    private TemperaText() {
    }

    // ------------------------------------------------------------------
    // renderHints 常量（原样保留 folia 的数值）
    // ------------------------------------------------------------------

    /** 行时长低于此值视为 micro（几乎瞬间闪过）。 */
    public static final double MICRO_LINE_DURATION_THRESHOLD = 0.10;
    /** 行时长低于此值视为 short（短促一行）。 */
    public static final double SHORT_LINE_DURATION_THRESHOLD = 0.18;
    /** micro 行至少也要留这么久（秒），否则字根本来不及看清。 */
    public static final double MICRO_LINE_RENDER_FLOOR = 0.067;

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    // ==================================================================
    // graphemeTiming.ts
    // ==================================================================

    /** 按共享 Unicode 扩展字素边界切分，UTF-16 偏移与源歌词及时间映射一致。 */
    public static List<String> splitLyricGraphemes(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int[] boundaries = UnicodeBreaks.analyze(text).graphemes;
        for (int index = 1; index < boundaries.length; index++) {
            out.add(text.substring(boundaries[index - 1], boundaries[index]));
        }
        return out;
    }

    /**
     * 把一个字符串在 [startTime, endTime] 内均等铺成逐字时间；无时长时退化为零长（落在 startTime）。
     */
    private static List<TemperaTypes.GraphemeTiming> buildEvenGraphemeTimings(
            String text, double startTime, double endTime) {
        List<String> graphemes = splitLyricGraphemes(text);
        if (graphemes.isEmpty()) {
            return new ArrayList<>();
        }
        double duration = Math.max(endTime - startTime, 0);
        double unit = duration / graphemes.size();
        List<TemperaTypes.GraphemeTiming> out = new ArrayList<>();
        for (int index = 0; index < graphemes.size(); index++) {
            double gs = startTime + unit * index;
            double ge = (index == graphemes.size() - 1) ? endTime : startTime + unit * (index + 1);
            out.add(new TemperaTypes.GraphemeTiming(graphemes.get(index), gs, ge));
        }
        return out;
    }

    /** 音节可以在字素内部切时间；显示字素取所有重叠音节的时间并集。 */
    private static List<TemperaTypes.GraphemeTiming> buildWordGraphemeTimings(TemperaTypes.Word word) {
        if (word.syllables.isEmpty()) {
            return buildEvenGraphemeTimings(word.text, word.startTime, word.endTime);
        }
        List<TemperaTypes.GraphemeTiming> pieces = new ArrayList<>();
        for (TemperaTypes.SyllableTime syllable : word.syllables) {
            pieces.addAll(buildEvenGraphemeTimings(syllable.text, syllable.startTime, syllable.endTime));
        }
        List<TemperaTypes.GraphemeTiming> out = new ArrayList<>();
        int offset = 0;
        int pieceIndex = 0;
        int pieceOffset = 0;
        for (String grapheme : splitLyricGraphemes(word.text)) {
            int end = offset + grapheme.length();
            double startTime = Double.POSITIVE_INFINITY;
            double endTime = Double.NEGATIVE_INFINITY;
            while (pieceIndex < pieces.size() && pieceOffset < end) {
                TemperaTypes.GraphemeTiming piece = pieces.get(pieceIndex);
                int pieceEnd = pieceOffset + piece.charText.length();
                if (pieceEnd > offset) {
                    startTime = Math.min(startTime, piece.startTime);
                    endTime = Math.max(endTime, piece.endTime);
                }
                if (pieceEnd > end) break;
                pieceOffset = pieceEnd;
                pieceIndex++;
            }
            out.add(new TemperaTypes.GraphemeTiming(grapheme,
                    Double.isFinite(startTime) ? startTime : word.startTime,
                    Double.isFinite(endTime) ? endTime : word.endTime));
            offset = end;
        }
        return out;
    }

    /**
     * 把逐词时间映射回整行字素时间轴，含解析器没覆盖的空格与标点（它们被钉到最近词的起点）。
     * 1:1 移植自 {@code buildLineGraphemeTimeline}。
     */
    public static List<TemperaTypes.GraphemeTiming> buildLineGraphemeTimeline(TemperaTypes.SourceLine line) {
        List<String> lineGraphemes = splitLyricGraphemes(line.fullText);
        if (lineGraphemes.isEmpty()) {
            return new ArrayList<>();
        }
        // 没有词：整行均等铺时间。
        if (line.words.isEmpty()) {
            return buildEvenGraphemeTimings(line.fullText, line.startTime, line.endTime);
        }

        List<TemperaTypes.GraphemeTiming> timeline = new ArrayList<>(
                Collections.nCopies(lineGraphemes.size(), (TemperaTypes.GraphemeTiming) null));
        int[] boundaries = UnicodeBreaks.analyze(line.fullText).graphemes;
        int cursor = 0;
        int glyphCursor = 0;
        double lastResolvedTime = line.startTime;

        for (TemperaTypes.Word word : line.words) {
            if (word.text == null || word.text.isEmpty()) continue;
            int matchedStart = line.fullText.indexOf(word.text, cursor);
            int start = matchedStart >= 0 ? matchedStart : cursor;
            int end = Math.min(start + word.text.length(), line.fullText.length());
            while (glyphCursor < lineGraphemes.size() && boundaries[glyphCursor + 1] <= start) {
                if (timeline.get(glyphCursor) == null) {
                    timeline.set(glyphCursor, new TemperaTypes.GraphemeTiming(
                            lineGraphemes.get(glyphCursor), word.startTime, word.startTime));
                }
                glyphCursor++;
            }
            int localOffset = start;
            int target = glyphCursor;
            for (TemperaTypes.GraphemeTiming timing : buildWordGraphemeTimings(word)) {
                int localEnd = Math.min(end, localOffset + timing.charText.length());
                while (target < lineGraphemes.size() && boundaries[target] < localEnd) {
                    if (boundaries[target + 1] > localOffset) {
                        TemperaTypes.GraphemeTiming previous = timeline.get(target);
                        timeline.set(target, new TemperaTypes.GraphemeTiming(lineGraphemes.get(target),
                                previous == null ? timing.startTime : Math.min(previous.startTime, timing.startTime),
                                previous == null ? timing.endTime : Math.max(previous.endTime, timing.endTime)));
                        lastResolvedTime = Math.max(lastResolvedTime, timing.endTime);
                    }
                    if (boundaries[target + 1] > localEnd) break;
                    target++;
                }
                localOffset = localEnd;
            }
            cursor = Math.max(cursor, end);
            glyphCursor = target;
        }

        // 词没覆盖到的尾部字素：沿用最后一个已解算的时间。
        for (int index = 0; index < lineGraphemes.size(); index += 1) {
            if (timeline.get(index) != null) {
                continue;
            }
            timeline.set(index, new TemperaTypes.GraphemeTiming(
                    lineGraphemes.get(index), lastResolvedTime, lastResolvedTime));
        }
        return timeline;
    }

    /**
     * 编译一行：填好 fullText / 起止时间 / 渲染尾时间 / 片段。渲染尾时间先按渲染提示的裸值算，
     * 真正跨行夹紧（不得超过下一行起点）由 {@link TemperaProgram#compile} 完成——与 folia 的内联逻辑一致。
     */
    public static TemperaTypes.CompiledLine compileLine(TemperaTypes.SourceLine line, int sourceIndex) {
        TemperaTypes.CompiledLine compiled = new TemperaTypes.CompiledLine(sourceIndex);
        compiled.fullText = line.fullText;
        compiled.startTime = line.startTime;
        compiled.endTime = line.endTime;
        compiled.renderEndTime = getLineRenderEndTime(line.startTime, line.endTime, line.words);
        compiled.segments = TemperaProgram.buildTemperaSegments(line);
        return compiled;
    }

    // ==================================================================
    // renderHints.ts
    // ==================================================================

    /** 一行的转场时间分解（入场/出场时长，以及词走完后的停留）。 */
    private static final class LineTransitionTiming {
        final double enterDuration;
        final double exitDuration;
        final double linePassHold;

        LineTransitionTiming(double enterDuration, double exitDuration, double linePassHold) {
            this.enterDuration = enterDuration;
            this.exitDuration = exitDuration;
            this.linePassHold = linePassHold;
        }
    }

    private static double getLastWordEndTime(double endTime, List<TemperaTypes.Word> words) {
        if (words.isEmpty()) {
            return endTime;
        }
        return words.get(words.size() - 1).endTime;
    }

    /** 由原始时长分出 normal / short / micro 三档。 */
    private static String getTimingClass(double rawDuration) {
        if (rawDuration < MICRO_LINE_DURATION_THRESHOLD) return "micro";
        if (rawDuration < SHORT_LINE_DURATION_THRESHOLD) return "short";
        return "normal";
    }

    private static String getLineTransitionMode(String timingClass) {
        if (timingClass.equals("micro")) return "none";
        if (timingClass.equals("short")) return "fast";
        return "normal";
    }

    private static String getWordRevealMode(String timingClass) {
        if (timingClass.equals("micro")) return "instant";
        if (timingClass.equals("short")) return "fast";
        return "normal";
    }

    /**
     * 转场三段时长。fast 档更短（micro 行几乎不转场），normal 档随行时长放大但有上下限；
     * instant 词揭示意味着词一落定就直接进出场停留（linePassHold 归零）。
     */
    private static LineTransitionTiming getLineTransitionTiming(
            double rawDuration, String lineTransitionMode, String wordRevealMode) {
        if (lineTransitionMode.equals("none")) {
            return new LineTransitionTiming(0, 0, 0);
        }
        if (lineTransitionMode.equals("fast")) {
            return new LineTransitionTiming(
                    clamp(rawDuration * 0.45, 0.045, 0.06),
                    clamp(rawDuration * 0.22, 0.03, 0.04),
                    wordRevealMode.equals("instant") ? 0 : 0.03);
        }
        return new LineTransitionTiming(
                Math.min(0.42, Math.max(0.22, Math.max(rawDuration, 0.12) * 0.34)),
                Math.min(0.32, Math.max(0.18, Math.max(rawDuration, 0.12) * 0.18)),
                wordRevealMode.equals("instant") ? 0 : 0.06);
    }

    /**
     * 这一行最晚可以留到什么时候：落位/经过/出场打磨都发生在揭示完成之后，但它不是一条独立的
     * 硬时间线——后一行可能更早开始，从而把这段额外的余裕截断。
     */
    private static double buildLineRenderEndTime(
            double startTime, double endTime, List<TemperaTypes.Word> words,
            double rawDuration, String lineTransitionMode, String wordRevealMode) {
        if (lineTransitionMode.equals("none")) {
            return Math.max(endTime, startTime + MICRO_LINE_RENDER_FLOOR);
        }
        LineTransitionTiming transitionTiming = getLineTransitionTiming(rawDuration, lineTransitionMode, wordRevealMode);
        double linePassStart = Math.max(getLastWordEndTime(endTime, words), startTime) + transitionTiming.linePassHold;
        double exitStart = lineTransitionMode.equals("fast")
                ? Math.max(startTime + transitionTiming.enterDuration + 0.01,
                        Math.max(linePassStart, endTime - transitionTiming.exitDuration))
                : Math.max(linePassStart, endTime - transitionTiming.exitDuration);
        return Math.max(endTime, exitStart + transitionTiming.exitDuration);
    }

    /**
     * 对应 folia {@code renderHints.getLineRenderEndTime}：由起止时间与词算出渲染尾时间。
     * 本移植的 SourceLine 不携带缓存的 renderHints，故总是从原始行当场重算。
     */
    public static double getLineRenderEndTime(double startTime, double endTime, List<TemperaTypes.Word> words) {
        double rawDuration = Math.max(endTime - startTime, 0);
        String timingClass = getTimingClass(rawDuration);
        String lineTransitionMode = getLineTransitionMode(timingClass);
        String wordRevealMode = getWordRevealMode(timingClass);
        return buildLineRenderEndTime(startTime, endTime, words, rawDuration, lineTransitionMode, wordRevealMode);
    }
}
