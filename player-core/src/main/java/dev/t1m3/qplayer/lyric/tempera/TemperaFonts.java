package dev.t1m3.qplayer.lyric.tempera;

import dev.t1m3.qplayer.lyric.skia.Fonts;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontEdging;
import io.github.humbleui.skija.FontHinting;
import io.github.humbleui.skija.Typeface;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Tempera's display faces, isolated from the ordinary lyric font selection. */
final class TemperaFonts {
    private static final int LIMIT = 256;
    private static final Map<Key, Font> CACHE = new LinkedHashMap<>(32, 0.75f, true);
    private static final class Key {
        final int sizeBits;
        final Font template;

        Key(int sizeBits, Font template) {
            this.sizeBits = sizeBits;
            this.template = template;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return sizeBits == key.sizeBits && template == key.template;
        }

        @Override public int hashCode() {
            return 31 * sizeBits + System.identityHashCode(template);
        }
    }
    private static Typeface bold;
    private static Typeface heavy;
    private static Font boldTemplate;
    private static Font heavyTemplate;

    private TemperaFonts() { }

    /** Shared faces are borrowed at one fixed template size only. Every exact
     *  floating-point size belongs to this bounded, render-thread-owned pool. */
    static Font template(int weight) {
        if (weight <= 550) return Fonts.get(TemperaMeasure.weightOf(weight), 64f);
        boolean useHeavy = weight > 800;
        if (useHeavy && heavy == null) heavy = load("SFPro-Heavy.otf");
        if (!useHeavy && bold == null) bold = load("SFPro-Bold.ttf");
        Typeface face = useHeavy ? heavy : bold;
        if (face == null) return Fonts.get(Fonts.Weight.MEDIUM, 64f);
        Font template = useHeavy ? heavyTemplate : boldTemplate;
        if (template == null) {
            template = new Font(face, 64f);
            template.setBaselineSnapped(false);
            template.setSubpixel(true);
            template.setHinting(FontHinting.NONE);
            template.setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS);
            if (useHeavy) heavyTemplate = template;
            else boldTemplate = template;
        }
        return template;
    }

    static Font get(int weight, float size) {
        return scaled(template(weight), size);
    }

    static Font scaled(Font template, float size) {
        Key key = new Key(Float.floatToIntBits(size), template);
        Font cached = CACHE.get(key);
        if (cached != null) return cached;
        Font font = template.makeWithSize(size);
        if (CACHE.size() >= LIMIT) {
            java.util.Iterator<Font> oldest = CACHE.values().iterator();
            Font evicted = oldest.next();
            oldest.remove();
            release(evicted);
        }
        CACHE.put(key, font);
        return font;
    }

    private static void release(Font font) {
        TemperaMeasure.releaseFont(font);
        TemperaTextView.releaseFont(font);
        font.close();
    }

    private static Typeface load(String name) {
        try (InputStream stream = TemperaFonts.class.getResourceAsStream("/tempera/fonts/" + name)) {
            if (stream == null) return null;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            try (Data data = Data.makeFromBytes(bytes.toByteArray())) {
                return FontMgr.getDefault().makeFromData(data);
            }
        } catch (IOException e) {
            return null;
        }
    }

    static void dispose() {
        for (Font font : CACHE.values()) release(font);
        CACHE.clear();
        if (boldTemplate != null) boldTemplate.close();
        if (heavyTemplate != null) heavyTemplate.close();
        boldTemplate = null;
        heavyTemplate = null;
        if (bold != null) bold.close();
        if (heavy != null) heavy.close();
        bold = null;
        heavy = null;
    }
}
