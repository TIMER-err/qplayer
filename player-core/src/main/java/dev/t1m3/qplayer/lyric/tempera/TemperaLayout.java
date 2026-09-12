package dev.t1m3.qplayer.lyric.tempera;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 拼贴排版，1:1 移植自 folia-major {@code tempera/temperaLayout.ts}。
 *
 * <p>编译期切好的词先被量出宽度，在镜头的区域里贪心排成参差的行，然后每行、每词都被一个
 * 有种子的缩进 / 基线偏移 / 旋转接管。结果既保持可读的阅读顺序，又看起来像被「拼」出来
 * 而不是被「排」出来的。
 */
public final class TemperaLayout {
    private TemperaLayout() {
    }

    /** 一个可渲染的字形，以及它的全部播放常量。 */
    public static final class GlyphPlacement {
        public String charText;
        public int lineIndex;
        public int segmentIndex;
        public float x;
        public float y;
        public float rotation;
        public double startTime;
        public double endTime;
        public double settleTime;
        public float fontSize;
        /** 主题给的关键字颜色；保留它自己的色相，不参与反色。 */
        public String color;
        public float enterX;
        public float enterY;
        public float enterRotation;
        public float enterScale;
        /** 这个字怎么到来；按词挑选，所以一个词是整体落地。 */
        public String enterStyle;
        /** 唱之后的释放：字距扩张完成的时刻，以及这个字素的力臂。 */
        public double releaseTime;
        public float trackingX;
        public float trackingY;
    }

    /** 排版输入。{@code lines} 每一项是一行歌词的 segments（词序）。 */
    public static final class Options {
        public List<List<TemperaTypes.Segment>> lines = new ArrayList<>();
        public String shotKind;
        public float width;
        public float height;
        public float baseFontSize;
        public int fontWeight;
        public int seed;
        /** 逐 segment 的关键字颜色，形状与 {@code lines} 完全一致。 */
        public List<List<String>> segmentColors;
        /** 0..1 入场节奏；见 SETTLE_STRETCH。默认取均衡的中点。 */
        public Float settleStretch;
    }

    private static final class LayoutRegion {
        float centerX;
        float centerY;
        float width;
        float height;
        String align;
        float rotation;
        float fontScale;
    }

    private static final class LayoutRow {
        List<TemperaMeasure.WordUnit> words = new ArrayList<>();
        float width;
        float height;
    }

    public static boolean isLayoutSegment(TemperaTypes.Segment segment) {
        return segment.text != null && !segment.text.trim().isEmpty();
    }

    // 区域与入场矢量是数据，逐构图存放在 temperaShotProfiles 里。
    private static LayoutRegion resolveRegion(String shotKind, float width, float height) {
        TemperaShotProfiles.Region region = TemperaShotProfiles.resolve(shotKind).region;
        LayoutRegion out = new LayoutRegion();
        out.centerX = region.cx * width;
        out.centerY = region.cy * height;
        out.width = region.w * width;
        out.height = region.h * height;
        out.align = region.align;
        out.rotation = region.rotation;
        out.fontScale = region.fontScale;
        return out;
    }

    private static float[] resolveEnterVector(String shotKind, float fontSize) {
        TemperaShotProfiles.Enter enter = TemperaShotProfiles.resolve(shotKind).enter;
        return new float[]{enter.x * fontSize, enter.y * fontSize};
    }

    private static float[] rotateAbout(float x, float y, float cx, float cy, float angle) {
        if (angle == 0f) return new float[]{x, y};
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float dx = x - cx;
        float dy = y - cy;
        return new float[]{cx + dx * cos - dy * sin, cy + dx * sin + dy * cos};
    }

    // 每行歌词里有一个词被提升成主字号，其余围绕基准轻微变化，于是一行读起来是一个被
    // 组织过的组，而不是一条均匀的带。
    private static float[] resolveWordScales(List<TemperaTypes.Segment> segments, int seed, int lineIndex) {
        int[] lengths = new int[segments.size()];
        int maxLength = 0;
        for (int index = 0; index < segments.size(); index++) {
            TemperaTypes.Segment segment = segments.get(index);
            lengths[index] = segment.isWordLike && segment.graphemes != null
                    ? segment.graphemes.size() : 0;
            maxLength = Math.max(maxLength, lengths[index]);
        }
        List<Integer> heroCandidates = new ArrayList<>();
        for (int index = 0; index < lengths.length; index++) {
            int length = lengths[index];
            if (length > 0 && length >= maxLength - 1) heroCandidates.add(index);
        }
        int heroIndex = -1;
        if (!heroCandidates.isEmpty()) {
            int pick = (int) Math.floor(TemperaRandom.hash01(seed, lineIndex, 101) * heroCandidates.size());
            heroIndex = heroCandidates.get(Math.floorMod(pick, heroCandidates.size()));
        }
        // 主字号与其余之间那道宽缝，正是让这个镜头有了重心。
        float[] scales = new float[segments.size()];
        for (int index = 0; index < segments.size(); index++) {
            scales[index] = index == heroIndex
                    ? (float) (1.34 + TemperaRandom.hash01(seed, lineIndex * 31 + index, 103) * 0.26)
                    : (float) (0.7 + TemperaRandom.hash01(seed, lineIndex * 31 + index, 107) * 0.16);
        }
        return scales;
    }

    // 带种子的逐行填充预算下的贪心装箱，这正是让行参差的原因。
    // 新的一行歌词永远另起一行，所以阅读顺序始终一目了然。
    private static List<LayoutRow> packRows(List<TemperaMeasure.WordUnit> words,
                                            float maxWidth, int seed) {
        List<LayoutRow> rows = new ArrayList<>();
        List<TemperaMeasure.WordUnit> current = new ArrayList<>();
        float currentWidth = 0f;

        for (TemperaMeasure.WordUnit word : words) {
            boolean newLine = !current.isEmpty() && word.lineIndex != current.get(0).lineIndex;
            // advance 与 budget 都用 flush 之前的 current 状态求值（与 folia 一致）。
            float advance = current.isEmpty() ? word.width : word.width + word.leadingGap;
            float budget = (float) (maxWidth
                    * (0.72 + TemperaRandom.hash01(seed, rows.size(), 109) * 0.28));
            if (newLine || (!current.isEmpty() && currentWidth + advance > budget)) {
                if (!current.isEmpty()) {
                    LayoutRow row = new LayoutRow();
                    row.words = new ArrayList<>(current);
                    row.width = currentWidth;
                    row.height = 0f;
                    rows.add(row);
                    current = new ArrayList<>();
                    currentWidth = 0f;
                }
            }
            currentWidth += current.isEmpty() ? word.width : advance;
            current.add(word);
        }
        if (!current.isEmpty()) {
            LayoutRow row = new LayoutRow();
            row.words = new ArrayList<>(current);
            row.width = currentWidth;
            row.height = 0f;
            rows.add(row);
        }
        return rows;
    }

    /**
     * 词切分是为了选字号，不是为了把字拆开排。一个词之前的空隙，只有在源里真的有空白
     * 时才是真正的空格；一个单纯的分词边界（每个 CJK 词界）只给一根视觉上的发丝。
     */
    private static List<TemperaMeasure.WordUnit> buildWordUnits(int fontWeight,
                                                                List<List<TemperaTypes.Segment>> lines,
                                                                float fontSize, int seed) {
        List<TemperaMeasure.WordUnit> out = new ArrayList<>();
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            List<TemperaTypes.Segment> segments = lines.get(lineIndex);
            float[] scales = resolveWordScales(segments, seed, lineIndex);
            Integer previousEnd = null;
            for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
                TemperaTypes.Segment segment = segments.get(segmentIndex);
                TemperaMeasure.WordUnit unit = TemperaMeasure.buildWordUnit(
                        fontWeight, fontSize, scales[segmentIndex], lineIndex, segmentIndex, segment);
                if (unit == null) continue;
                boolean spaced = previousEnd != null && segment.startOffset > previousEnd;
                unit.leadingGap = previousEnd == null ? 0f : fontSize * (spaced ? 0.26f : 0.035f);
                previousEnd = segment.endOffset;
                out.add(unit);
            }
        }
        return out;
    }

    /** 任何字形能拿到的最短入场时长，不管镜头的节奏怎么说。 */
    private static final double MIN_SETTLE_SECONDS = 0.34;
    /** 入场向镜头歌词结尾拉伸多远的默认值；调参项 {@code glyphSettleStretch} 暴露它。 */
    private static final float DEFAULT_SETTLE_STRETCH = 0.5f;

    /**
     * 让每次入场都对着这个镜头承载的歌词结尾定节奏，然后设置唱完之后的释放。
     *
     * <p>一个字素在自己那句歌词的时间上起跳，窗口是地板加上到那个结尾的剩余距离的一部分，
     * 于是错峰会从第一个字素平滑地缩短到最后一个，整个镜头作为一次横扫落地，而不是一排
     * 各自独立的弹出。因为曲线极度前置，长窗口是一次果断的开幕接一段缓慢爬行，而不是
     * 一次缓慢的到达。
     */
    private static List<GlyphPlacement> applyGlyphTiming(List<GlyphPlacement> placements,
                                                        float settleStretch) {
        if (placements.isEmpty()) return placements;
        float stretch = Float.isFinite(settleStretch)
                ? Math.min(1f, Math.max(0f, settleStretch)) : DEFAULT_SETTLE_STRETCH;
        Map<Integer, Double> lineSpans = new HashMap<>();
        for (GlyphPlacement placement : placements) {
            lineSpans.merge(placement.lineIndex, placement.endTime, Math::max);
        }
        Map<Integer, Double> lineStarts = new HashMap<>();
        for (GlyphPlacement placement : placements) {
            lineStarts.merge(placement.lineIndex, placement.startTime, Math::min);
        }

        // 这里的每个 placement 都属于正在排版的那一个镜头。
        double shotLyricEnd = 0;
        for (GlyphPlacement placement : placements) {
            shotLyricEnd = Math.max(shotLyricEnd, placement.endTime);
        }
        for (GlyphPlacement placement : placements) {
            double reach = Math.max(0, shotLyricEnd - placement.startTime - MIN_SETTLE_SECONDS);
            placement.settleTime = placement.startTime + MIN_SETTLE_SECONDS + reach * stretch;
        }
        // 拼贴块的中心；扩张从它量起，所以排版保持精确形状，只有间距打开。
        double centerX = 0;
        double centerY = 0;
        for (GlyphPlacement placement : placements) {
            centerX += placement.x;
            centerY += placement.y;
        }
        centerX /= placements.size();
        centerY /= placements.size();
        for (GlyphPlacement placement : placements) {
            double span = Math.max(0.5,
                    lineSpans.getOrDefault(placement.lineIndex, placement.endTime)
                            - lineStarts.getOrDefault(placement.lineIndex, placement.startTime));
            placement.releaseTime = Math.max(placement.endTime, placement.settleTime) + span;
            placement.trackingX = placement.x - (float) centerX;
            placement.trackingY = placement.y - (float) centerY;
        }
        return placements;
    }

    /** 主入口：把镜头的歌词切片排成一组可播放的字形。 */
    public static List<GlyphPlacement> resolve(Options options) {
        LayoutRegion region = resolveRegion(options.shotKind, options.width, options.height);
        String[] styles = TemperaEnterStyles.STYLES;

        // 适配循环：一直缩到装箱后的行落进区域里。
        float fontSize = Math.max(14f, options.baseFontSize * region.fontScale);
        List<LayoutRow> rows = new ArrayList<>();
        float blockHeight = 0f;
        for (int attempt = 0; attempt < 4; attempt++) {
            rows = packRows(buildWordUnits(options.fontWeight, options.lines, fontSize, options.seed),
                    region.width, options.seed);
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                LayoutRow row = rows.get(rowIndex);
                float tallest = 0f;
                for (TemperaMeasure.WordUnit word : row.words) tallest = Math.max(tallest, word.scale);
                // 紧行距：整块应该读成一个体量，而不是一行行散开。
                row.height = (float) (fontSize * tallest
                        * (1.02 + TemperaRandom.hash01(options.seed, rowIndex, 113) * 0.1));
            }
            blockHeight = 0f;
            for (LayoutRow row : rows) blockHeight += row.height;
            float maxRowWidth = 1f;
            for (LayoutRow row : rows) maxRowWidth = Math.max(maxRowWidth, row.width);
            float fit = Math.min(1f, Math.min(
                    blockHeight > region.height ? region.height / blockHeight : 1f,
                    maxRowWidth > region.width ? region.width / maxRowWidth : 1f));
            if (fit >= 0.999f) break;
            fontSize *= fit;
        }

        float[] base = resolveEnterVector(options.shotKind, fontSize);
        float baseAngle = (float) Math.atan2(base[1], base[0]);
        float baseMagnitude = (float) Math.hypot(base[0], base[1]);
        List<GlyphPlacement> placements = new ArrayList<>();
        float cursorY = region.centerY - blockHeight / 2f;

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            LayoutRow row = rows.get(rowIndex);
            float rowCenterY = cursorY + row.height / 2f;
            cursorY += row.height;
            // 行会水平错开并微微倾斜；这就是层叠的拼贴读法。
            float drift = (float) ((TemperaRandom.hash01(options.seed, rowIndex, 127) - 0.5) * region.width * 0.06);
            float rowRotation = (float) ((TemperaRandom.hash01(options.seed, rowIndex, 131) - 0.5) * 0.06);
            float rowLeft;
            if ("center".equals(region.align)) {
                rowLeft = region.centerX - row.width / 2f + drift;
            } else if ("left".equals(region.align)) {
                rowLeft = region.centerX - region.width / 2f + Math.abs(drift) * 0.6f;
            } else {
                rowLeft = region.centerX + region.width / 2f - row.width - Math.abs(drift) * 0.6f;
            }
            float rowCenterX = rowLeft + row.width / 2f;

            float cursorX = rowLeft;
            for (int wordIndex = 0; wordIndex < row.words.size(); wordIndex++) {
                TemperaMeasure.WordUnit word = row.words.get(wordIndex);
                int salt = rowIndex * 37 + wordIndex;
                // 每个词一种入场样式：相邻词到达方式不同，但一个词永远不会拆成几种姿态。
                int styleIndex = (int) Math.floor(
                        TemperaRandom.hash01(options.seed, salt, 193) * styles.length) % styles.length;
                String enterStyle = styles[styleIndex];
                float wordRotation = (float) ((TemperaRandom.hash01(options.seed, salt, 137) - 0.5) * 0.07);
                float wordShiftY = (float) ((TemperaRandom.hash01(options.seed, salt, 139) - 0.5) * fontSize * 0.09);
                float wordLeft = cursorX + (wordIndex == 0 ? 0f : word.leadingGap);
                cursorX = wordLeft + word.width;
                float wordCenterX = wordLeft + word.width / 2f;
                float wordCenterY = rowCenterY + wordShiftY;
                float glyphSize = fontSize * word.scale;

                for (int glyphIndex = 0; glyphIndex < word.glyphs.size(); glyphIndex++) {
                    TemperaMeasure.WordGlyph glyph = word.glyphs.get(glyphIndex);
                    if (glyph.charText == null || glyph.charText.trim().isEmpty()) continue;
                    float localX = wordLeft + glyph.offset + glyph.width / 2f;
                    float[] rotatedWord = rotateAbout(localX, wordCenterY, wordCenterX, wordCenterY, wordRotation);
                    float[] rotatedRow = rotateAbout(rotatedWord[0], rotatedWord[1], rowCenterX, rowCenterY, rowRotation);
                    float[] finalPoint = rotateAbout(rotatedRow[0], rotatedRow[1],
                            region.centerX, region.centerY, region.rotation);

                    // 逐字素入场：镜头的基础方向被扇开并重新尺度化，于是一行里没有两个字素
                    // 走在完全相同的矢量上。
                    int glyphSalt = salt * 53 + glyphIndex;
                    float spread = (float) ((TemperaRandom.hash01(options.seed, glyphSalt, 149) - 0.5) * 1.7);
                    float magnitude = (float) (baseMagnitude * (0.55 + TemperaRandom.hash01(options.seed, glyphSalt, 151) * 1.05));
                    float angle = baseAngle + spread;
                    GlyphPlacement placement = new GlyphPlacement();
                    placement.charText = glyph.charText;
                    placement.lineIndex = word.lineIndex;
                    placement.segmentIndex = word.segmentIndex;
                    placement.x = finalPoint[0];
                    placement.y = finalPoint[1];
                    placement.rotation = region.rotation + rowRotation + wordRotation;
                    placement.startTime = glyph.startTime;
                    placement.endTime = glyph.endTime;
                    placement.settleTime = glyph.endTime;
                    placement.fontSize = glyphSize;
                    placement.color = colorAt(options.segmentColors, word.lineIndex, word.segmentIndex);
                    placement.enterX = (float) Math.cos(angle) * magnitude;
                    placement.enterY = (float) Math.sin(angle) * magnitude;
                    placement.enterRotation = (float) ((TemperaRandom.hash01(options.seed, glyphSalt, 157) - 0.5) * 0.7);
                    placement.enterScale = (float) (0.6 + TemperaRandom.hash01(options.seed, glyphSalt, 163) * 0.3);
                    placement.enterStyle = enterStyle;
                    placement.releaseTime = 0;
                    placement.trackingX = 0;
                    placement.trackingY = 0;
                    placements.add(placement);
                }
            }
        }

        return applyGlyphTiming(placements,
                options.settleStretch == null ? DEFAULT_SETTLE_STRETCH : options.settleStretch);
    }

    private static String colorAt(List<List<String>> colors, int line, int segment) {
        if (colors == null || line < 0 || line >= colors.size()) return null;
        List<String> row = colors.get(line);
        if (row == null || segment < 0 || segment >= row.size()) return null;
        return row.get(segment);
    }
}
