package dev.t1m3.qplayer.lyric.tempera;

import dev.t1m3.qplayer.lyric.skia.Fonts;

import io.github.humbleui.skija.Font;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本度量，1:1 移植自 folia-major {@code tempera/temperaMeasure.ts}（原文用 pretext，这里换成
 * Skija 的直接测量，语义一致）。
 *
 * <p>词来自编译期的切分；先量整词、再把逐字素的步进按比例归一化到那个宽度，这样整形/字距
 * 得以保留，同时又允许逐字摆放。
 */
public final class TemperaMeasure {
    private TemperaMeasure() {
    }

    /**
     * 度量缓存是全局共享的，不是每次布局一份。key 命名整个规格（weight size family|text），
     * 所以场景、镜头、歌曲都不可能让两个同 key 的条目互相矛盾；而且同样的字素反复出现：
     * fit 循环会把一个镜头最多重测四次，一个段落有好几个镜头，相邻歌曲共享绝大部分字符集。
     * 每次调用新建缓存会把这些全扔掉，让切歌在落地那一帧重新测一遍所有东西。
     */
    private static final int MEASURE_CACHE_LIMIT = 20000;
    private static final Map<String, Float> CACHE = new LinkedHashMap<>();

    /** Font 对象由 {@link Fonts} 缓存，所以 identity 在一次运行内稳定，可当缓存 key 前缀。 */
    private static final IdentityHashMap<Font, String> FONT_KEYS = new IdentityHashMap<>();

    static void releaseFont(Font font) {
        FONT_KEYS.remove(font);
    }

    static void dispose() {
        CACHE.clear();
        FONT_KEYS.clear();
    }

    private static String fontKey(Font font) {
        String key = FONT_KEYS.get(font);
        if (key == null) {
            key = Float.floatToIntBits(font.getSize()) + "@" + System.identityHashCode(font);
            FONT_KEYS.put(font, key);
        }
        return key;
    }

    /**
     * 普通字重沿用主程序的字体；粗体和超重使用凝彩自己的字体资源。
     */
    public static Fonts.Weight weightOf(int weight) {
        if (weight <= 150) return Fonts.Weight.THIN;
        if (weight <= 350) return Fonts.Weight.LIGHT;
        if (weight <= 450) return Fonts.Weight.REGULAR;
        return Fonts.Weight.MEDIUM;
    }

    /** 按字重取一个尺寸匹配的字体（CJK 由 {@link #fontFor} 再回退一层）。 */
    public static Font font(int weight, float size) {
        return weight > 550 ? TemperaFonts.get(weight, size) : Fonts.get(weightOf(weight), size);
    }

    /**
     * 覆盖 {@code text} 的字体：与歌词渲染同一套回退顺序（韩/泰/日/汉），
     * 保证一个汉字不会被画成缺字方框。
     */
    public static Font fontFor(int weight, float size, String text) {
        Font base = font(weight, size);
        if (base == null) return null;
        if (needsKorean(text)) {
            Font korean = Fonts.korean(base);
            if (korean != null) return korean;
        }
        if (needsThai(text)) {
            Font thai = Fonts.thai(base);
            if (thai != null) return thai;
        }
        if (needsJapanese(text)) {
            Font japanese = Fonts.japanese(base);
            if (japanese != null) return japanese;
        }
        if (needsHan(text)) {
            if (base.getTypeface() == null || base.getTypeface().getUTF32Glyph('汉') == 0) {
                return Fonts.get(Fonts.Weight.MEDIUM, size);
            }
        }
        return base;
    }

    private static void write(String key, float width) {
        // 朴素的 FIFO 淘汰：条目重算的代价相同，所以淘汰策略只需要限定内存，不必预测复用。
        if (CACHE.size() >= MEASURE_CACHE_LIMIT) {
            java.util.Iterator<String> it = CACHE.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        CACHE.put(key, width);
    }

    private static float measureText(Font font, String text) {
        if (font == null) return 0f;
        float size = font.getSize();
        String key = fontKey(font) + "|" + text;
        Float cached = CACHE.get(key);
        if (cached != null) return cached;
        float measured;
        try {
            measured = font.measureTextWidth(text);
        } catch (Throwable t) {
            measured = text.length() * size * 0.6f;
        }
        float width = Math.max(size * 0.08f, measured);
        write(key, width);
        return width;
    }

    /** 一个空白字素也占位，否则一个空格词会让整行塌陷。 */
    public static float measureGrapheme(Font font, String charText, float fontSize) {
        if (charText == null || charText.trim().isEmpty()) return fontSize * 0.3f;
        return measureText(font, charText);
    }

    /** 一个词的某字素：从词左缘到该字左缘的步进，以及它自身的宽度。 */
    public static final class WordGlyph {
        public String charText;
        public double startTime;
        public double endTime;
        public float offset;
        public float width;
    }

    /** 一个词单元：字号倍率在这里体现层级重音，字形按整形宽度铺开。 */
    public static final class WordUnit {
        public int lineIndex;
        public int segmentIndex;
        public String text;
        /** 源偏移，用于区分真正的空格与单纯的分词边界。 */
        public int startOffset;
        public int endOffset;
        /** 该词之前要插入的水平空隙，单位像素。 */
        public float leadingGap;
        public float scale;
        public float width;
        public List<WordGlyph> glyphs = new ArrayList<>();
        public double startTime;
        public double endTime;
    }

    /**
     * 量一个词，并把它的字素铺在整形后的宽度里，于是逐字步进之和永远等于整词的测量宽度。
     */
    public static WordUnit buildWordUnit(int weight, float fontSize, float scale,
                                         int lineIndex, int segmentIndex,
                                         TemperaTypes.Segment segment) {
        List<TemperaTypes.GraphemeTiming> glyphs = new ArrayList<>();
        for (TemperaTypes.GraphemeTiming timing : segment.graphemes) {
            if (timing.charText != null && timing.charText.length() > 0) glyphs.add(timing);
        }
        if (glyphs.isEmpty()) return null;
        float scaledSize = fontSize * scale;
        float[] raw = new float[glyphs.size()];
        float rawTotal = 0f;
        for (int index = 0; index < glyphs.size(); index++) {
            Font glyphFont = fontFor(weight, scaledSize, glyphs.get(index).charText);
            raw[index] = measureGrapheme(glyphFont, glyphs.get(index).charText, scaledSize);
            rawTotal += raw[index];
        }
        String wordText = segment.text == null ? "" : segment.text.replaceAll("\\s+$", "");
        Font wordFont = fontFor(weight, scaledSize, wordText);
        float shaped = measureText(wordFont, wordText);
        // 按比例分摊整形差异，而不是把校正全推给某一个字素。
        float correction = rawTotal > 0f ? shaped / rawTotal : 1f;
        WordUnit unit = new WordUnit();
        unit.lineIndex = lineIndex;
        unit.segmentIndex = segmentIndex;
        unit.text = segment.text;
        unit.startOffset = segment.startOffset;
        unit.endOffset = segment.endOffset;
        unit.leadingGap = 0f;
        unit.scale = scale;
        float offset = 0f;
        for (int index = 0; index < glyphs.size(); index++) {
            WordGlyph glyph = new WordGlyph();
            glyph.charText = glyphs.get(index).charText;
            glyph.startTime = glyphs.get(index).startTime;
            glyph.endTime = glyphs.get(index).endTime;
            glyph.offset = offset;
            glyph.width = raw[index] * correction;
            offset += glyph.width;
            unit.glyphs.add(glyph);
        }
        unit.width = offset;
        unit.startTime = unit.glyphs.get(0).startTime;
        unit.endTime = unit.glyphs.get(unit.glyphs.size() - 1).endTime;
        return unit;
    }

    private static boolean needsHan(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if ((value >= 0x4E00 && value <= 0x9FFF)
                    || (value >= 0x3400 && value <= 0x4DBF)
                    || (value >= 0x3000 && value <= 0x303F)
                    || (value >= 0xFF00 && value <= 0xFFEF)) {
                return true;
            }
        }
        return false;
    }

    private static boolean needsKorean(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if ((value >= 0xAC00 && value <= 0xD7FF) || (value >= 0x1100 && value <= 0x11FF)
                    || (value >= 0x3130 && value <= 0x318F)
                    || (value >= 0xA960 && value <= 0xA97F)) {
                return true;
            }
        }
        return false;
    }

    private static boolean needsThai(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value >= 0x0E00 && value <= 0x0E7F) return true;
        }
        return false;
    }

    private static boolean needsJapanese(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if ((value >= 0x3040 && value <= 0x30FF) || (value >= 0x31F0 && value <= 0x31FF)
                    || (value >= 0xFF65 && value <= 0xFF9F)) {
                return true;
            }
        }
        return false;
    }
}
