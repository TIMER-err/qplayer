package dev.t1m3.qplayer.lyric.tempera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 把统一歌词编译成「可 seek、确定性」的块状 PV 时间线，1:1 移植自 folia-major
 * {@code components/visualizer/tempera/temperaProgram.ts}。
 *
 * <p>机制：所有随机性（镜头挑选、装饰母题、碎片、水印、流式角度微调、转场种类与时长）都在编译期
 * 用 {@link TemperaRandom} 烧进 {@link TemperaTypes} 模型；运行时只查表 + 插值，所以任意一次 seek
 * 都重绘出逐像素相同的帧。时间单位是<b>秒</b>（double）。
 *
 * <p>注：folia 源文件里 {@code Line} 还带 {@code blockIndex}/{@code songPart}/{@code isChorus}/
 * {@code wordSegments} 等元数据；本移植的输入是 {@link TemperaTypes.SourceLine}，它只暴露
 * {@code fullText/startTime/endTime/words}。因此「按元数据切段落」({@link #metadataChanged}) 恒假、
 * 「chorus/break」靠 songPart 判定的分支被省略、用户自定义 {@code wordSegments} 覆盖走默认切分——这些
 * 是数据模型层面的等价降级，其余算法与全部常量逐字保留。
 */
public final class TemperaProgram {
    private TemperaProgram() {
    }

    // ------------------------------------------------------------------
    // 内部数据
    // ------------------------------------------------------------------

    /** 一个镜头切块：指向某编译行的半开片段区间，以及它的起止/歌词止时间。 */
    private static final class ShotChunk {
        int lineIndex;
        int segmentStart;
        int segmentEnd;
        double startTime;
        double endTime;
        double lyricEndTime;
    }

    /** 带原始片段下标的「可渲染段」引用，用于镜头切块。 */
    private static final class IndexSegment {
        final TemperaTypes.Segment segment;
        final int index;

        IndexSegment(TemperaTypes.Segment segment, int index) {
            this.segment = segment;
            this.index = index;
        }
    }

    /** 相机起止关键帧对。 */
    private static final class CameraKeyPair {
        final TemperaTypes.CameraKey start;
        final TemperaTypes.CameraKey end;

        CameraKeyPair(TemperaTypes.CameraKey start, TemperaTypes.CameraKey end) {
            this.start = start;
            this.end = end;
        }
    }

    /** 段落草稿：一组编译行 + 切分依据。 */
    private static final class ParagraphDraft {
        List<TemperaTypes.CompiledLine> lines;
        String boundary;

        ParagraphDraft(List<TemperaTypes.CompiledLine> lines, String boundary) {
            this.lines = lines;
            this.boundary = boundary;
        }
    }

    /** 一个词/标点切片的边界，等价 folia 的 {@code LyricWordSegment}（offset 为 UTF-16 码元）。 */
    private static final class LyricWordSegment {
        final String segment;
        final int index;
        final boolean isWordLike;

        LyricWordSegment(String segment, int index, boolean isWordLike) {
            this.segment = segment;
            this.index = index;
            this.isWordLike = isWordLike;
        }
    }

    /** 字素区间（UTF-16 码元）。 */
    private static final class Range {
        final int start;
        final int end;

        Range(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /** 段落超长时的候选切点。 */
    private static final class Cand {
        final int splitIndex;
        final double gap;

        Cand(int splitIndex, double gap) {
            this.splitIndex = splitIndex;
            this.gap = gap;
        }
    }

    // ------------------------------------------------------------------
    // 常量与正则
    // ------------------------------------------------------------------

    /** 桥段镜头：短于此的空档已由段落转场自身覆盖，不再额外补镜头。 */
    private static final double BRIDGE_MIN_GAP = 1.2;
    private static final double BRIDGE_MAX_LENGTH = 5;

    /** 用于段落分类里的标点计数（! ? ！ ？ …）。 */
    private static final Pattern PUNCTUATION_MARK = Pattern.compile("[!?！？…]");

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0.5;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            double a = sorted.get(middle - 1);
            return (a + sorted.get(middle)) / 2;
        }
        return sorted.get(middle);
    }

    // ==================================================================
    // 段落间隙阈值
    // ==================================================================

    /**
     * 段落切分的间隙阈值：取各行「渲染尾 → 下一行起点」间隙的中位数 ×2.5，夹在 [1.25, 3.5] 秒。
     * 1:1 移植自 {@code resolveTemperaParagraphGapThreshold}。
     */
    public static double resolveTemperaParagraphGapThreshold(List<TemperaTypes.SourceLine> lines) {
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            double gap = lines.get(i).startTime - Math.min(
                    TemperaText.getLineRenderEndTime(
                            lines.get(i - 1).startTime, lines.get(i - 1).endTime, lines.get(i - 1).words),
                    lines.get(i).startTime);
            if (gap > 0) {
                gaps.add(gap);
            }
        }
        return clamp(median(gaps) * 2.5, 1.25, 3.5);
    }

    // ==================================================================
    // 词切分（等价于 Intl.Segmenter 的 word 粒度；Intl 不可用时的替换）
    // ==================================================================

    /**
     * 等价实现规则（对应 folia {@code segmentTextWords} 的默认路径）：
     * <ol>
     *   <li>遇<b>空白</b>：连续空白合并成一段（isWordLike=false），与 Intl 把空白单独成段一致。</li>
     *   <li>遇<b>CJK</b>（汉字/假名/谚文/注音等）：每个字各自成一段（isWordLike=true）——Intl word 粒度下
     *       CJK 逐字切，这样半句切点能落在每个字之间。</li>
     *   <li>遇<b>拉丁/数字</b>（字母或数字）：连续同类合并成一个词段（isWordLike=true）。</li>
     *   <li>其余<b>标点/符号</b>：每个自成一段（isWordLike=false）。</li>
     * </ol>
     * 偏移用 UTF-16 码元，与字素区间的码元偏移对齐。词切分只决定「半句切点」位置，不影响逐字时间——
     * 逐字时间永远来自 {@link TemperaText#buildLineGraphemeTimeline}。
     */
    private static final int CAT_WS = 0;
    private static final int CAT_CJK = 1;
    private static final int CAT_WORD = 2;
    private static final int CAT_OTHER = 3;

    private static boolean isWhitespaceCp(int cp) {
        if (Character.isWhitespace(cp)) {
            return true;
        }
        switch (cp) {
            case 0x00A0: case 0x1680: case 0x180E: case 0x2000: case 0x2001: case 0x2002:
            case 0x2003: case 0x2004: case 0x2005: case 0x2006: case 0x2007: case 0x2008:
            case 0x2009: case 0x200A: case 0x200B: case 0x2028: case 0x2029: case 0x202F:
            case 0x205F: case 0x3000:
                return true;
            default:
                return false;
        }
    }

    private static boolean isCjkCp(int cp) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        if (block == null) {
            return false;
        }
        // UnicodeBlock 不是枚举，Java 17 的 switch 只认 int/String/枚举，所以这里用等值判断。
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.BOPOMOFO
                || block == Character.UnicodeBlock.BOPOMOFO_EXTENDED;
    }

    private static int categoryOf(int cp) {
        if (isWhitespaceCp(cp)) {
            return CAT_WS;
        }
        if (isCjkCp(cp)) {
            return CAT_CJK;
        }
        if (Character.isLetterOrDigit(cp)) {
            return CAT_WORD;
        }
        return CAT_OTHER;
    }

    private static List<LyricWordSegment> segmentTextWords(String text) {
        List<LyricWordSegment> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return parts;
        }
        StringBuilder cur = new StringBuilder();
        int curStart = -1;
        int curCat = -1;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            int cat = categoryOf(cp);
            String piece = text.substring(i, i + len);
            if (cat == CAT_CJK || cat == CAT_OTHER) {
                // CJK 逐字、标点独立成段：先 flush 已缓冲段。
                if (cur.length() > 0) {
                    parts.add(new LyricWordSegment(cur.toString(), curStart, curCat == CAT_WORD));
                    cur.setLength(0);
                    curCat = -1;
                }
                parts.add(new LyricWordSegment(piece, i, cat == CAT_CJK));
            } else {
                // 空白 / 拉丁数字：同类连续合并成一段。
                if (curCat != cat) {
                    if (cur.length() > 0) {
                        parts.add(new LyricWordSegment(cur.toString(), curStart, curCat == CAT_WORD));
                        cur.setLength(0);
                    }
                    curStart = i;
                    curCat = cat;
                }
                cur.append(piece);
            }
            i += len;
        }
        if (cur.length() > 0) {
            parts.add(new LyricWordSegment(cur.toString(), curStart, curCat == CAT_WORD));
        }
        return parts;
    }


    /**
     * folia 的 {@code segmentLyricWords}：有用户自定义 {@code wordSegments} 且有效时优先用之，否则走默认。
     * 本移植的 SourceLine 不带 wordSegments，故永远走默认切分。
     */
    private static List<LyricWordSegment> segmentLyricWords(TemperaTypes.SourceLine line) {
        return segmentTextWords(line.fullText);
    }

    // ==================================================================
    // 片段构建（buildTemperaSegments）
    // ==================================================================

    /**
     * 无损词级片段：把展示偏移映射回解析器的字素时间；黏连标点向前合并，使色块永不丢符号。
     * 1:1 移植自 {@code buildTemperaSegments}。
     */
    public static List<TemperaTypes.Segment> buildTemperaSegments(TemperaTypes.SourceLine line) {
        if (line.fullText == null || line.fullText.isEmpty()) {
            return new ArrayList<>();
        }
        List<TemperaTypes.GraphemeTiming> timeline = TemperaText.buildLineGraphemeTimeline(line);
        int cursor = 0;
        List<Range> ranges = new ArrayList<>();
        for (String grapheme : TemperaText.splitLyricGraphemes(line.fullText)) {
            ranges.add(new Range(cursor, cursor + grapheme.length()));
            cursor += grapheme.length();
        }
        List<LyricWordSegment> parts = segmentLyricWords(line);
        List<TemperaTypes.Segment> segments = new ArrayList<>();
        for (int p = 0; p < parts.size(); p += 1) {
            LyricWordSegment part = parts.get(p);
            int startOffset = part.index;
            int endOffset = (p + 1 < parts.size()) ? parts.get(p + 1).index : line.fullText.length();
            List<Integer> indices = new ArrayList<>();
            for (int ri = 0; ri < ranges.size(); ri += 1) {
                Range range = ranges.get(ri);
                if (range.end > startOffset && range.start < endOffset) {
                    indices.add(ri);
                }
            }
            List<TemperaTypes.GraphemeTiming> graphemes = new ArrayList<>();
            for (int idx : indices) {
                TemperaTypes.GraphemeTiming gt = (idx < timeline.size()) ? timeline.get(idx) : null;
                if (gt != null) {
                    graphemes.add(gt);
                }
            }
            TemperaTypes.Segment seg = new TemperaTypes.Segment();
            seg.text = line.fullText.substring(startOffset, endOffset);
            seg.startOffset = startOffset;
            seg.endOffset = endOffset;
            seg.graphemes = graphemes;
            seg.startTime = graphemes.isEmpty() ? line.startTime : graphemes.get(0).startTime;
            seg.endTime = graphemes.isEmpty() ? line.endTime : graphemes.get(graphemes.size() - 1).endTime;
            seg.isWordLike = part.isWordLike;
            segments.add(seg);
        }

        // 黏连标点合并：把无时间的标点并到前一个词段后面，让它随下一个词一起出现，
        // 并把本段结尾拖到那个词起点（解析器没覆盖的标点自身无时间）。
        List<TemperaTypes.Segment> sticky = new ArrayList<>();
        for (TemperaTypes.Segment segment : segments) {
            TemperaTypes.Segment previous = sticky.isEmpty() ? null : sticky.get(sticky.size() - 1);
            if (previous != null && !segment.isWordLike && !isWhitespaceOnly(segment.text)) {
                previous.text += segment.text;
                previous.endOffset = segment.endOffset;
                double tail = previous.endTime;
                List<TemperaTypes.GraphemeTiming> merged = new ArrayList<>();
                for (TemperaTypes.GraphemeTiming gt : segment.graphemes) {
                    merged.add(gt.endTime > gt.startTime
                            ? gt
                            : new TemperaTypes.GraphemeTiming(gt.charText, tail, tail));
                }
                previous.graphemes.addAll(merged);
                double maxEnd = previous.endTime;
                for (TemperaTypes.GraphemeTiming gt : merged) {
                    maxEnd = Math.max(maxEnd, gt.endTime);
                }
                previous.endTime = maxEnd;
            } else {
                sticky.add(segment.copy());
            }
        }
        return sticky;
    }

    private static boolean isWhitespaceOnly(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (!isWhitespaceCp(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    // ==================================================================
    // 段落
    // ==================================================================

    /** folia 按 blockIndex / songPart 变化切段；本移植无这些元数据，故恒假（时间/长度切分仍生效）。 */
    private static boolean metadataChanged(TemperaTypes.SourceLine previous, TemperaTypes.SourceLine next) {
        return false;
    }

    /**
     * 把超长草稿再切开：超过 6 行，或总时长 > 18 秒时，按「行间出界间隙最大」处切，直到合规。
     * 1:1 移植自 {@code splitOversizedDraft}。
     */
    private static List<ParagraphDraft> splitOversizedDraft(ParagraphDraft draft) {
        List<ParagraphDraft> output = new ArrayList<>();
        List<TemperaTypes.CompiledLine> remaining = draft.lines;
        String boundary = draft.boundary;
        int loopGuard = 0;
        while (remaining.size() > 6
                || (remaining.size() > 1
                    && (remaining.get(remaining.size() - 1).renderEndTime - remaining.get(0).startTime) > 18)) {
            if (loopGuard++ > 1000) {
                System.err.println("splitOversizedDraft: 检测到死循环，跳出");
                break;
            }
            List<Cand> candidates = new ArrayList<>();
            for (int s = 2; s < remaining.size() - 1; s += 1) {
                double gap = remaining.get(s).startTime - remaining.get(s + 1).renderEndTime;
                if (!Double.isNaN(gap)) {
                    candidates.add(new Cand(s, gap));
                }
            }
            double bestGap = Double.NEGATIVE_INFINITY;
            int rawSplitIndex = Math.min(4, remaining.size() - 1);
            for (Cand c : candidates) {
                if (c.gap > bestGap) {
                    bestGap = c.gap;
                    rawSplitIndex = c.splitIndex;
                }
            }
            int splitIndex = Math.max(1, rawSplitIndex);
            output.add(new ParagraphDraft(new ArrayList<>(remaining.subList(0, splitIndex)), boundary));
            remaining = new ArrayList<>(remaining.subList(splitIndex, remaining.size()));
            boundary = output.get(output.size() - 1).lines.size() >= 6 ? "line-cap" : "duration-cap";
        }
        output.add(new ParagraphDraft(remaining, boundary));
        return output;
    }

    /**
     * 段落分类。注：folia 还会用 line.isChorus / line.songPart 判定 chorus/break，
     * 本移植 SourceLine 无这些字段，故只保留时长/词数/标点的判定（chorus/break 走不到）。
     */
    private static String classifyParagraph(List<TemperaTypes.CompiledLine> lines, int index, int total) {
        double duration = lines.get(lines.size() - 1).renderEndTime - lines.get(0).startTime;
        int segmentCount = 0;
        for (TemperaTypes.CompiledLine l : lines) {
            for (TemperaTypes.Segment s : l.segments) {
                if (s.isWordLike) {
                    segmentCount += 1;
                }
            }
        }
        int punctuationCount = 0;
        for (TemperaTypes.CompiledLine l : lines) {
            if (l.fullText != null) {
                Matcher m = PUNCTUATION_MARK.matcher(l.fullText);
                while (m.find()) {
                    punctuationCount += 1;
                }
            }
        }
        if (duration <= 3.5 || segmentCount <= 3) {
            return "breath";
        }
        if (punctuationCount >= 2 || segmentCount / Math.max(duration, 1) > 2.5) {
            return "lift";
        }
        return "verse";
    }

    // ==================================================================
    // 镜头切块
    // ==================================================================

    private static boolean isRenderableSegment(TemperaTypes.Segment segment) {
        return segment.text.trim().length() > 0 && !segment.graphemes.isEmpty();
    }

    /**
     * 把可渲染片段聚成镜头切块：默认在攒够 2..4 词或跑了约 2.2s 时收尾；wholeLineLyrics 下整行一段。
     * 1:1 移植自 {@code buildShotChunks}。最后平铺时间线，使运行时「最后一个已开始的镜头」查询永不落空。
     */
    private static List<ShotChunk> buildShotChunks(
            List<TemperaTypes.CompiledLine> lines, String seed, int paragraphIndex, boolean wholeLineLyrics) {
        List<ShotChunk> chunks = new ArrayList<>();
        for (TemperaTypes.CompiledLine line : lines) {
            List<IndexSegment> usable = new ArrayList<>();
            for (int idx = 0; idx < line.segments.size(); idx += 1) {
                TemperaTypes.Segment seg = line.segments.get(idx);
                if (isRenderableSegment(seg)) {
                    usable.add(new IndexSegment(seg, idx));
                }
            }
            if (usable.isEmpty()) {
                continue;
            }
            if (wholeLineLyrics) {
                IndexSegment first = usable.get(0);
                IndexSegment last = usable.get(usable.size() - 1);
                ShotChunk c = new ShotChunk();
                c.lineIndex = line.sourceIndex;
                c.segmentStart = first.index;
                c.segmentEnd = last.index + 1;
                c.startTime = first.segment.startTime;
                c.endTime = Math.max(last.segment.endTime, first.segment.startTime + 0.2);
                c.lyricEndTime = c.endTime;
                chunks.add(c);
                continue;
            }
            int segmentStart = usable.get(0).index;
            double startTime = usable.get(0).segment.startTime;
            int words = 0;
            for (int order = 0; order < usable.size(); order += 1) {
                IndexSegment entry = usable.get(order);
                words += 1;
                int chunkSeed = TemperaRandom.hashSeed(
                        seed + ":" + paragraphIndex + ":" + line.sourceIndex + ":" + chunks.size());
                int target = 2 + (int) Math.floor(TemperaRandom.hash01(chunkSeed, 1, 179) * 3);
                double spent = entry.segment.endTime - startTime;
                boolean isLast = order == usable.size() - 1;
                if (!isLast && words < target && spent < 2.2) {
                    continue;
                }
                ShotChunk c = new ShotChunk();
                c.lineIndex = line.sourceIndex;
                c.segmentStart = segmentStart;
                c.segmentEnd = entry.index + 1;
                c.startTime = startTime;
                c.endTime = Math.max(entry.segment.endTime, startTime + 0.2);
                c.lyricEndTime = c.endTime;
                chunks.add(c);
                if (order + 1 < usable.size()) {
                    IndexSegment next = usable.get(order + 1);
                    segmentStart = next.index;
                    startTime = next.segment.startTime;
                    words = 0;
                }
            }
        }
        // 平铺：每个镜头一直撑到下一个镜头开始，收尾镜头撑到段落尾。
        List<ShotChunk> tiled = new ArrayList<>();
        for (int idx = 0; idx < chunks.size(); idx += 1) {
            ShotChunk c = chunks.get(idx);
            ShotChunk t = new ShotChunk();
            t.lineIndex = c.lineIndex;
            t.segmentStart = c.segmentStart;
            t.segmentEnd = c.segmentEnd;
            t.startTime = c.startTime;
            t.lyricEndTime = c.lyricEndTime;
            double nextStart = (idx + 1 < chunks.size()) ? chunks.get(idx + 1).startTime : c.endTime;
            t.endTime = Math.max(c.startTime + 0.2, nextStart);
            tiled.add(t);
        }
        return tiled;
    }

    // ==================================================================
    // 相机 / 装饰 / 流式角度（全部确定性）
    // ==================================================================

    /**
     * 方向永远来自镜头的 flowAngle；profile 只决定相机沿它走多远、变焦怎么坡。
     * 1:1 移植自 {@code buildCameraKeys}，抖动用 TemperaRandom。
     */
    private static CameraKeyPair buildCameraKeys(String kind, int seed, double flowAngle) {
        double jitterX = (TemperaRandom.hash01(seed, 1, 11) - 0.5) * 0.02;
        double jitterY = (TemperaRandom.hash01(seed, 2, 23) - 0.5) * 0.02;
        double jitterZoom = TemperaRandom.hash01(seed, 3, 37) * 0.025;
        double jitterRotation = (TemperaRandom.hash01(seed, 4, 51) - 0.5) * 0.012;
        TemperaShotProfiles.CameraProfile camera = TemperaShotProfiles.resolve(kind).camera;
        double travelX = Math.cos(flowAngle) * camera.travel;
        double travelY = Math.sin(flowAngle) * camera.travel;
        TemperaTypes.CameraKey start = new TemperaTypes.CameraKey(
                (float) (-travelX / 2 + jitterX),
                (float) (-travelY / 2 + jitterY),
                (float) (camera.zoomStart + jitterZoom),
                (float) jitterRotation);
        TemperaTypes.CameraKey end = new TemperaTypes.CameraKey(
                (float) (travelX / 2 + jitterX),
                (float) (travelY / 2 + jitterY),
                (float) (camera.zoomEnd + jitterZoom),
                (float) (-jitterRotation));
        return new CameraKeyPair(start, end);
    }

    /**
     * 边角余白处的「页边注」：取整词而非单字——单字会被读成排版碎屑（看起来像上一句歌词
     * 掉出来的字母），整词读起来才像一句有意的批注。一镜最多两条，沿同一条纵向基线排在
     * 画面一侧，于是它们成行而不是成屑；只出现在稀疏/间奏镜头里，喧闹构图自身已经够响。
     */
    private static List<TemperaTypes.DecorFragment> buildDecorFragments(
            List<String> wordPool, int count, int seed) {
        if (wordPool.isEmpty() || count <= 0) return new ArrayList<>();
        List<String> words = new ArrayList<>();
        for (String raw : wordPool) {
            String t = raw == null ? "" : raw.trim();
            // 单字 / 超长串都不要：单字读成碎屑，长串挤出版心。
            if (t.length() >= 2 && t.length() <= 6 && !words.contains(t)) words.add(t);
        }
        if (words.isEmpty()) return new ArrayList<>();
        boolean onLeft = TemperaRandom.hash01(seed, 17, 197) > 0.5;
        float edge = (float) (onLeft
                ? 0.06 + TemperaRandom.hash01(seed, 19, 199) * 0.04
                : 0.94 - TemperaRandom.hash01(seed, 19, 199) * 0.04);
        float baseY = (float) (0.32 + TemperaRandom.hash01(seed, 23, 211) * 0.36);
        float tilt = (float) ((TemperaRandom.hash01(seed, 29, 223) - 0.5) * 0.10);
        float scale = (float) (0.20 + TemperaRandom.hash01(seed, 31, 227) * 0.06);
        List<TemperaTypes.DecorFragment> out = new ArrayList<>();
        String previous = null;
        for (int index = 0; index < count; index += 1) {
            String word = null;
            for (int tries = 0; tries < words.size(); tries += 1) {
                int pick = (int) (TemperaRandom.hash01(seed, index * 7 + 3, 233 + tries) * words.size()) % words.size();
                String candidate = words.get(pick);
                if (!candidate.equals(previous)) { word = candidate; break; }
            }
            if (word == null) word = words.get(0);
            previous = word;
            // 两条时沿基线上下错开，读成两行批注而不是两个孤点。
            float y = count > 1 ? baseY + (index - (count - 1) / 2f) * 0.07f : baseY;
            out.add(new TemperaTypes.DecorFragment(word, edge, y, tilt, scale));
        }
        return out;
    }

    /** 超大的装饰水印词：取自本镜头没唱到的词，loud 构图跳过（自身已经够响）。 */
    private static TemperaTypes.DecorWatermark buildDecorWatermark(
            List<String> pool, int seed, boolean allowed) {
        List<String> words = new ArrayList<>();
        for (String w : pool) {
            String t = w.trim();
            if (!t.isEmpty() && t.length() <= 12) {
                words.add(t);
            }
        }
        if (!allowed || words.isEmpty() || TemperaRandom.hash01(seed, 4, 107) > 0.62) {
            return null;
        }
        int pick = (int) (TemperaRandom.hash01(seed, 5, 109) * words.size()) % words.size();
        return new TemperaTypes.DecorWatermark(
                words.get(pick),
                (float) (0.28 + TemperaRandom.hash01(seed, 6, 113) * 0.44),
                (float) (0.26 + TemperaRandom.hash01(seed, 7, 127) * 0.48),
                (float) ((TemperaRandom.hash01(seed, 8, 131) - 0.5) * 0.5),
                (float) (2.6 + TemperaRandom.hash01(seed, 9, 137) * 1.9));
    }

    /** 编译期解算某镜头的网点装饰：母题/斜线角/交叉数/边角碎片全部种子派生。 */
    private static TemperaTypes.DecorSpec buildDecorSpec(
            String paragraphKind, String shotKind, String seedKey,
            List<String> watermarkPool, String previousMotif) {
        int seed = TemperaRandom.hashSeed(seedKey);
        String motif = TemperaRandom.chooseWithoutRepeat(TemperaTypes.DECOR_MOTIFS, seedKey, previousMotif);
        boolean sparse = TemperaShotProfiles.resolve(shotKind).mood.equals("quiet")
                || paragraphKind.equals("break")
                || paragraphKind.equals("outro");
        TemperaTypes.DecorSpec spec = new TemperaTypes.DecorSpec();
        spec.motif = motif;
        // 只用浅斜线：陡斜线一旦落上后处理颗粒就成了噪点。
        spec.hatchAngle = (float) ((TemperaRandom.hash01(seed, 1, 83) - 0.5) * (Math.PI / 2));
        spec.crossCount = 1 + (int) Math.floor(TemperaRandom.hash01(seed, 2, 89) * 3);
        spec.scribbleSeed = TemperaRandom.mixSeed(seed, 97);
        spec.fragments = sparse
                ? buildDecorFragments(watermarkPool,
                        1 + (TemperaRandom.hash01(seed, 3, 101) < 0.35 ? 1 : 0), seed)
                : new ArrayList<>();
        spec.watermark = buildDecorWatermark(
                watermarkPool, seed, !TemperaShotProfiles.resolve(shotKind).mood.equals("loud"));
        return spec;
    }

    /**
     * 垂直是凝彩的母语轴：构图靠彼此错身交接，近乎垂直的流向读起来像「俯冲进场」而非横向幻灯片。
     * 每个镜头只小幅转向并拉回主轴。
     */
    private static double resolveFlowAngle(Double previous, int seed) {
        if (previous == null) {
            int sign = TemperaRandom.hash01(seed, 6, 71) > 0.5 ? 1 : -1;
            return sign * Math.PI / 2 + (TemperaRandom.hash01(seed, 8, 79) - 0.5) * 0.4;
        }
        double axis = (Math.sin(previous) >= 0 ? 1 : -1) * Math.PI / 2;
        return previous + (axis - previous) * 0.3 + (TemperaRandom.hash01(seed, 7, 73) - 0.5) * 0.5;
    }

    // ==================================================================
    // 镜头
    // ==================================================================

    private static List<TemperaTypes.Shot> buildShots(
            List<TemperaTypes.CompiledLine> lines,
            List<TemperaTypes.CompiledLine> songLines,
            String kind,
            int paragraphIndex,
            String seed,
            String[] prevShot,
            String[] prevMotif,
            Double[] prevFlow,
            boolean wholeLineLyrics) {
        String lastKind = prevShot[0];
        String lastMotif = prevMotif[0];
        Double lastFlow = prevFlow[0];
        double paragraphEnd = lines.get(lines.size() - 1).renderEndTime;
        List<ShotChunk> chunks = buildShotChunks(lines, seed, paragraphIndex, wholeLineLyrics);
        Map<Integer, TemperaTypes.CompiledLine> byIndex = new HashMap<>();
        for (TemperaTypes.CompiledLine l : lines) {
            byIndex.put(l.sourceIndex, l);
        }

        List<TemperaTypes.Shot> out = new ArrayList<>();
        for (int shotIndex = 0; shotIndex < chunks.size(); shotIndex += 1) {
            ShotChunk chunk = chunks.get(shotIndex);
            TemperaTypes.CompiledLine line = byIndex.get(chunk.lineIndex);
            List<TemperaTypes.Segment> sliceSegments = (line != null)
                    ? new ArrayList<>(line.segments.subList(chunk.segmentStart, chunk.segmentEnd))
                    : new ArrayList<>();
            String sliceText = sliceSegments.stream().map(s -> s.text).collect(Collectors.joining());
            int wordCount = 0;
            for (TemperaTypes.Segment s : sliceSegments) {
                if (s.isWordLike) {
                    wordCount += 1;
                }
            }
            // 呼吸段读起来稀疏；副歌从不轻声。
            boolean sparse = kind.equals("breath") || (!kind.equals("chorus") && wordCount <= 2);
            List<String> moods;
            if (sparse) {
                moods = java.util.Arrays.asList("quiet");
            } else if (kind.equals("chorus")) {
                moods = java.util.Arrays.asList("neutral", "loud");
            } else {
                moods = java.util.Arrays.asList("quiet", "neutral", "loud");
            }
            String shotKind = TemperaRandom.chooseWithoutRepeat(
                    TemperaShotProfiles.resolveCandidates(moods).toArray(new String[0]),
                    seed + ":" + paragraphIndex + ":" + shotIndex + ":" + sliceText,
                    lastKind);
            lastKind = shotKind;

            int cameraSeed = TemperaRandom.hashSeed(seed + ":" + paragraphIndex + ":" + shotIndex + ":camera");
            double flowAngle = resolveFlowAngle(lastFlow, cameraSeed);
            lastFlow = flowAngle;
            CameraKeyPair ck = buildCameraKeys(shotKind, cameraSeed, flowAngle);

            // 边角碎片与水印来自本段落其余部分，绝不取自本镜头正在唱的词。
            List<TemperaTypes.Segment> outsideSlice = new ArrayList<>();
            for (TemperaTypes.CompiledLine item : lines) {
                if (item.sourceIndex == chunk.lineIndex) {
                    for (int si = 0; si < item.segments.size(); si += 1) {
                        if (si < chunk.segmentStart || si >= chunk.segmentEnd) {
                            outsideSlice.add(item.segments.get(si));
                        }
                    }
                } else {
                    outsideSlice.addAll(item.segments);
                }
            }
            List<TemperaTypes.Segment> decorPool = outsideSlice.isEmpty()
                    ? songLines.stream()
                        .flatMap(item -> item.sourceIndex == chunk.lineIndex
                                ? Stream.<TemperaTypes.Segment>empty()
                                : item.segments.stream())
                        .collect(Collectors.toList())
                    : outsideSlice;
            List<String> watermarkPool = new ArrayList<>();
            for (TemperaTypes.Segment s : decorPool) {
                if (s.isWordLike) {
                    watermarkPool.add(s.text);
                }
            }
            TemperaTypes.DecorSpec decor = buildDecorSpec(
                    kind, shotKind, seed + ":" + paragraphIndex + ":" + shotIndex + ":decor",
                    watermarkPool, lastMotif);
            lastMotif = decor.motif;

            List<TemperaTypes.ShotSlice> slices = new ArrayList<>();
            slices.add(new TemperaTypes.ShotSlice(chunk.lineIndex, chunk.segmentStart, chunk.segmentEnd));

            TemperaTypes.Shot shot = new TemperaTypes.Shot();
            shot.id = "p" + paragraphIndex + "-s" + shotIndex;
            shot.kind = shotKind;
            shot.startTime = chunk.startTime;
            // 收尾镜头撑到段落自身的渲染尾。
            shot.endTime = (shotIndex == chunks.size() - 1)
                    ? Math.max(chunk.endTime, paragraphEnd)
                    : chunk.endTime;
            shot.lyricEndTime = chunk.lyricEndTime;
            shot.slices = slices;
            shot.isBridge = false;
            shot.camera = ck.start;
            shot.cameraEnd = ck.end;
            shot.flowAngle = (float) flowAngle;
            shot.decor = decor;
            out.add(shot);
        }
        return out;
    }

    /**
     * 用纯器乐镜头填满段落间的空档：走与普通镜头相同的交接/相机/装饰机制，长空档也持续运动。
     * 1:1 移植自 {@code buildBridgeShots}。
     */
    private static List<TemperaTypes.Shot> buildBridgeShots(
            String paragraphKind,
            int paragraphIndex,
            String seed,
            double gapStart,
            double gapEnd,
            String[] prevShot,
            String[] prevMotif,
            Double[] prevFlow) {
        double gap = gapEnd - gapStart;
        if (gap < BRIDGE_MIN_GAP) {
            return new ArrayList<>();
        }
        int count = Math.min(3, Math.max(1, (int) Math.ceil(gap / BRIDGE_MAX_LENGTH)));
        double step = gap / count;
        String lastKind = prevShot[0];
        String lastMotif = prevMotif[0];
        Double lastFlow = prevFlow[0];

        List<TemperaTypes.Shot> out = new ArrayList<>();
        for (int index = 0; index < count; index += 1) {
            // 器乐拍永远不是全曲最响的。
            String shotKind = TemperaRandom.chooseWithoutRepeat(
                    TemperaShotProfiles.resolveCandidates(java.util.Arrays.asList("quiet", "neutral")).toArray(new String[0]),
                    seed + ":" + paragraphIndex + ":bridge" + index,
                    lastKind);
            lastKind = shotKind;
            int cameraSeed = TemperaRandom.hashSeed(seed + ":" + paragraphIndex + ":bridge" + index + ":camera");
            double flowAngle = resolveFlowAngle(lastFlow, cameraSeed);
            lastFlow = flowAngle;
            CameraKeyPair ck = buildCameraKeys(shotKind, cameraSeed, flowAngle);
            TemperaTypes.DecorSpec decor = buildDecorSpec(
                    paragraphKind, shotKind, seed + ":" + paragraphIndex + ":bridge" + index + ":decor",
                    new ArrayList<>(), lastMotif);
            lastMotif = decor.motif;
            TemperaTypes.Shot shot = new TemperaTypes.Shot();
            shot.id = "p" + paragraphIndex + "-b" + index;
            shot.kind = shotKind;
            shot.startTime = gapStart + step * index;
            shot.endTime = gapStart + step * (index + 1);
            // 桥段无歌词，图形按整段空档铺。
            shot.lyricEndTime = gapStart + step * (index + 1);
            shot.slices = new ArrayList<>();
            shot.isBridge = true;
            shot.camera = ck.start;
            shot.cameraEnd = ck.end;
            shot.flowAngle = (float) flowAngle;
            shot.decor = decor;
            out.add(shot);
        }
        return out;
    }

    // ==================================================================
    // 编译主入口
    // ==================================================================

    /**
     * 把统一歌词编译成确定性块状 PV 程序。1:1 移植自 {@code compileTemperaProgram(lines, seed, options)}，
     * folia 的 options 在本签名里收敛为 {@code tuning}（只用 {@code wholeLineLyrics}）；其余选项（如段落
     * 阈值覆盖）folia 也没有，故无需额外 Options 内部类。
     */
    public static TemperaTypes.Program compile(
            List<TemperaTypes.SourceLine> lines, String seed, TemperaTuning tuning) {
        List<TemperaTypes.CompiledLine> compiled = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += 1) {
            compiled.add(TemperaText.compileLine(lines.get(i), i));
        }
        // 渲染尾不得侵入下一行：与 folia 内联的 min(nextStart) 夹紧一致。
        for (int i = 0; i < compiled.size(); i += 1) {
            double nextStart = (i + 1 < compiled.size())
                    ? compiled.get(i + 1).startTime
                    : Double.POSITIVE_INFINITY;
            TemperaTypes.CompiledLine cl = compiled.get(i);
            cl.renderEndTime = Math.max(cl.startTime, Math.min(cl.renderEndTime, nextStart));
        }

        double paragraphGapThreshold = resolveTemperaParagraphGapThreshold(lines);
        List<ParagraphDraft> drafts = new ArrayList<>();
        ParagraphDraft current = new ParagraphDraft(new ArrayList<>(), "song-start");

        for (int i = 0; i < compiled.size(); i += 1) {
            TemperaTypes.CompiledLine line = compiled.get(i);
            TemperaTypes.CompiledLine previous = (i > 0) ? compiled.get(i - 1) : null;
            double gap = previous != null ? line.startTime - previous.renderEndTime : 0;
            String boundary;
            if (previous != null && metadataChanged(lines.get(i - 1), lines.get(i))) {
                boundary = "metadata";
            } else if (previous != null && gap >= paragraphGapThreshold) {
                boundary = "time-gap";
            } else {
                boundary = null;
            }
            if (boundary != null && !current.lines.isEmpty()) {
                drafts.addAll(splitOversizedDraft(current));
                current = new ParagraphDraft(new ArrayList<>(), boundary);
            }
            current.lines.add(line);
        }
        if (!current.lines.isEmpty()) {
            drafts.addAll(splitOversizedDraft(current));
        }

        String resolvedSeed = seed;
        String[] prevShot = {null};
        String[] prevMotif = {null};
        Double[] prevFlow = {null};
        String[] prevTransition = {null};

        List<TemperaTypes.Paragraph> paragraphs = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index += 1) {
            ParagraphDraft draft = drafts.get(index);
            String kind = classifyParagraph(draft.lines, index, drafts.size());
            List<TemperaTypes.Shot> lyricShots = buildShots(
                    draft.lines, compiled, kind, index, resolvedSeed,
                    prevShot, prevMotif, prevFlow, tuning.wholeLineLyrics);
            if (!lyricShots.isEmpty()) {
                prevShot[0] = lyricShots.get(lyricShots.size() - 1).kind;
                prevMotif[0] = lyricShots.get(lyricShots.size() - 1).decor.motif;
                prevFlow[0] = (double) lyricShots.get(lyricShots.size() - 1).flowAngle;
            }

            TemperaTypes.CompiledLine lastLine = draft.lines.get(draft.lines.size() - 1);
            double endTime = lastLine.renderEndTime;
            ParagraphDraft next = (index + 1 < drafts.size()) ? drafts.get(index + 1) : null;
            double gap = (next != null) ? next.lines.get(0).startTime - endTime : 0;

            // 段落后的器乐空档自己变成无歌词镜头。
            List<TemperaTypes.Shot> bridgeShots = (next != null)
                    ? buildBridgeShots(kind, index, resolvedSeed,
                        endTime, next.lines.get(0).startTime,
                        prevShot, prevMotif, prevFlow)
                    : new ArrayList<>();
            List<TemperaTypes.Shot> shots = new ArrayList<>(lyricShots);
            shots.addAll(bridgeShots);
            if (!shots.isEmpty()) {
                prevShot[0] = shots.get(shots.size() - 1).kind;
                prevMotif[0] = shots.get(shots.size() - 1).decor.motif;
                prevFlow[0] = (double) shots.get(shots.size() - 1).flowAngle;
            }

            String transitionKind = null;
            if (next != null) {
                transitionKind = TemperaRandom.chooseWithoutRepeat(
                        TemperaTypes.TRANSITION_KINDS,
                        resolvedSeed + ":" + index + ":transition",
                        prevTransition[0]);
            }
            if (transitionKind != null) {
                prevTransition[0] = transitionKind;
            }
            // 够图形撑过切点，又不啃掉出段尾超过约 0.3s。
            double transitionDuration = (next != null)
                    ? Math.min(1, Math.max(0.35, Math.max(gap, 0) + 0.3))
                    : 0;
            double transitionEndTime = (next != null) ? next.lines.get(0).startTime : endTime;

            TemperaTypes.Paragraph p = new TemperaTypes.Paragraph();
            p.id = "tempera-p" + index;
            p.kind = kind;
            p.boundary = draft.boundary;
            p.startTime = draft.lines.get(0).startTime;
            p.endTime = endTime;
            p.lines = draft.lines;
            p.shots = shots;
            if (transitionKind != null) {
                TemperaTypes.Transition tr = new TemperaTypes.Transition();
                tr.kind = transitionKind;
                tr.startTime = Math.max(draft.lines.get(0).startTime, transitionEndTime - transitionDuration);
                tr.endTime = transitionEndTime;
                p.transitionOut = tr;
            } else {
                p.transitionOut = null;
            }
            paragraphs.add(p);
        }

        // 下一段的入场构图在前一段还在转场时就开搭：否则边界落在歌词空档时，进场的场景在过渡里
        // 只剩一张纸地，平移转场会把出场段落滑进空壳。字形时间不受影响，只挪镜头自身的时钟。
        for (int index = 0; index < paragraphs.size(); index += 1) {
            TemperaTypes.Paragraph paragraph = paragraphs.get(index);
            if (paragraph.shots.isEmpty()) {
                continue;
            }
            TemperaTypes.Shot incoming = paragraph.shots.get(0);
            TemperaTypes.Transition transition = (index > 0) ? paragraphs.get(index - 1).transitionOut : null;
            if (transition == null || transition.kind.equals("block-wipe")) {
                continue;
            }
            incoming.startTime = Math.min(
                    incoming.startTime,
                    Math.max(transition.startTime, paragraphs.get(index - 1).endTime));
        }

        TemperaTypes.Program program = new TemperaTypes.Program();
        program.version = 1;
        program.seed = resolvedSeed;
        program.paragraphGapThreshold = paragraphGapThreshold;
        program.paragraphs = paragraphs;
        return program;
    }

    // ==================================================================
    // 运行时查询
    // ==================================================================

    /** 给定时间，返回其所属段落的下标（倒序找第一个起点已过的段落，否则 0）。 */
    public static int findTemperaParagraphIndexAtTime(TemperaTypes.Program program, double time) {
        for (int index = program.paragraphs.size() - 1; index >= 0; index -= 1) {
            if (time >= program.paragraphs.get(index).startTime) {
                return index;
            }
        }
        return 0;
    }
}
