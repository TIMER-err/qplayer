package dev.t1m3.qplayer.desktop.lyric.tempera;

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
    private static final Map<Long, Font> CACHE = new LinkedHashMap<>(32, 0.75f, true);
    private static Typeface bold;
    private static Typeface heavy;

    private TemperaFonts() { }

    static Font get(int weight, float size) {
        boolean useHeavy = weight > 800;
        long key = ((long) Float.floatToIntBits(size) << 1) | (useHeavy ? 1 : 0);
        Font cached = CACHE.get(key);
        if (cached != null) return cached;
        if (useHeavy && heavy == null) heavy = load("SFPro-Heavy.otf");
        if (!useHeavy && bold == null) bold = load("SFPro-Bold.ttf");
        Typeface face = useHeavy ? heavy : bold;
        if (face == null) return Fonts.get(Fonts.Weight.MEDIUM, size);
        Font font = new Font(face, size);
        font.setBaselineSnapped(false);
        font.setSubpixel(true);
        font.setHinting(FontHinting.NONE);
        font.setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS);
        if (CACHE.size() >= LIMIT) {
            var oldest = CACHE.entrySet().iterator();
            Font evicted = oldest.next().getValue();
            oldest.remove();
            TemperaMeasure.releaseFont(evicted);
            TemperaTextView.releaseFont(evicted);
            evicted.close();
        }
        CACHE.put(key, font);
        return font;
    }

    private static Typeface load(String name) {
        try (InputStream stream = TemperaFonts.class.getResourceAsStream("/tempera/fonts/" + name)) {
            if (stream == null) return null;
            try (Data data = Data.makeFromBytes(stream.readAllBytes())) {
                return FontMgr.getDefault().makeFromData(data);
            }
        } catch (IOException e) {
            return null;
        }
    }

    static void dispose() {
        for (Font font : CACHE.values()) font.close();
        CACHE.clear();
        if (bold != null) bold.close();
        if (heavy != null) heavy.close();
        bold = null;
        heavy = null;
    }
}
