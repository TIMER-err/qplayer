package dev.t1m3.qplayer.desktop.lyric.tempera;

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

    /**
     * 把文本按字素切开。folia 优先用 {@code Intl.Segmenter('grapheme')}，Java 没有等价运行时，
     * 这里用<b>码点迭代</b>做回退：每个 Unicode 码点自成一段，代理对（emoji 等）不会被拆开，
     * 与 folia 回退路径 {@code Array.from(text)} 的语义一致。每个字素字符串的 {@code length()} 即
     * UTF-16 码元数，所以后续按「码元偏移」对齐的切点不会错位。
     */
    public static List<String> splitLyricGraphemes(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            out.add(text.substring(i, i + n));
            i += n;
        }
        return out;
    }

    /**
     * 把一个字符串在 [startTime, endTime] 内均等铺成逐字时间；无时长时退化为零长（落在 startTime）。
     */
    private static List<TemperaTypes.GraphemeTiming> buildEvenGraphemeTimings(
            String text, double startTime, double endTime, int wordIndex) {
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

    /** 一个词：有音节就按音节铺时间，否则整词均等铺。 */
    private static List<TemperaTypes.GraphemeTiming> buildWordGraphemeTimings(
            TemperaTypes.Word word, int wordIndex) {
        if (word.syllables.isEmpty()) {
            return buildEvenGraphemeTimings(word.text, word.startTime, word.endTime, wordIndex);
        }
        List<TemperaTypes.GraphemeTiming> out = new ArrayList<>();
        for (TemperaTypes.SyllableTime syllable : word.syllables) {
            out.addAll(buildEvenGraphemeTimings(syllable.text, syllable.startTime, syllable.endTime, wordIndex));
        }
        return out;
    }

    /**
     * 在 source 里从 fromIndex 起找 target 首次整体出现的位置（按字素相等），找不到返回 -1。
     * 用于把「解析器给的词文本」对齐回「整行字素序列」——因为整行可能含词里没有的空格/标点。
     */
    private static int findGraphemeSequence(List<String> source, List<String> target, int fromIndex) {
        if (target.isEmpty()) {
            return fromIndex;
        }
        for (int index = fromIndex; index <= source.size() - target.size(); index += 1) {
            boolean matched = true;
            for (int t = 0; t < target.size(); t += 1) {
                if (!source.get(index + t).equals(target.get(t))) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return index;
            }
        }
        return -1;
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
            return buildEvenGraphemeTimings(line.fullText, line.startTime, line.endTime, -1);
        }

        List<TemperaTypes.GraphemeTiming> timeline = new ArrayList<>(
                Collections.nCopies(lineGraphemes.size(), (TemperaTypes.GraphemeTiming) null));
        int cursor = 0;
        double lastResolvedTime = line.startTime;

        for (int wordIndex = 0; wordIndex < line.words.size(); wordIndex += 1) {
            TemperaTypes.Word word = line.words.get(wordIndex);
            List<String> wordGraphemes = splitLyricGraphemes(word.text);
            if (wordGraphemes.isEmpty()) {
                continue;
            }
            int matchedStart = findGraphemeSequence(lineGraphemes, wordGraphemes, cursor);
            int start = matchedStart >= 0 ? matchedStart : cursor;
            int end = Math.min(start + wordGraphemes.size(), lineGraphemes.size());

            // 词之前、叉在词边界外的字素（空格/标点）：钉到本词起点。
            for (int gap = cursor; gap < start; gap += 1) {
                timeline.set(gap, new TemperaTypes.GraphemeTiming(lineGraphemes.get(gap), word.startTime, word.startTime));
            }

            List<TemperaTypes.GraphemeTiming> wordTimings = buildWordGraphemeTimings(word, wordIndex);
            for (int local = 0; local < end - start; local += 1) {
                TemperaTypes.GraphemeTiming timing = (local < wordTimings.size())
                        ? wordTimings.get(local)
                        : buildEvenGraphemeTimings(wordGraphemes.get(local), word.startTime, word.endTime, wordIndex).get(0);
                if (timing == null) {
                    continue;
                }
                timeline.set(start + local, new TemperaTypes.GraphemeTiming(
                        lineGraphemes.get(start + local), timing.startTime, timing.endTime));
                lastResolvedTime = Math.max(lastResolvedTime, timing.endTime);
            }
            cursor = Math.max(cursor, end);
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
