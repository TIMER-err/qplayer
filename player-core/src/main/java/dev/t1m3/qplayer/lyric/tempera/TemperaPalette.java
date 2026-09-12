package dev.t1m3.qplayer.lyric.tempera;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 凝彩的色块调色板，1:1 移植自 folia-major {@code tempera/temperaPalette.ts}。
 *
 * <p>从当前主题的一侧推导出来；mono 模式会把每一级都塌成纸↔墨的灰阶梯，让色相不外泄。
 */
public final class TemperaPalette {
    private TemperaPalette() {
    }

    /** 主题输入。folia 的 {@code Theme} 里调色板用到的字段只有这四个。 */
    public static final class Theme {
        public final String backgroundColor;
        public final String primaryColor;
        public final String secondaryColor;
        public final String accentColor;

        public Theme(String backgroundColor, String primaryColor,
                     String secondaryColor, String accentColor) {
            this.backgroundColor = backgroundColor;
            this.primaryColor = primaryColor;
            this.secondaryColor = secondaryColor;
            this.accentColor = accentColor;
        }
    }

    /** 渲染器读的调色板。 */
    public static final class Palette {
        public String paper;
        public String ink;
        public String blockA;
        public String blockB;
        public String blockC;
        public String accent;
        public String line;
        public String shadow;
        /** 由纸到墨的单调明度梯，驱动网点密度与图形明暗。 */
        public String tone1;
        public String tone2;
        public String tone3;
        public String tone4;
        /**
         * gradient 模式的四色坡道，按明度从纸到墨排序；平涂模式下为 null。
         * 形状填充会读它来做线性渐变而不是实色。
         */
        public String[] gradient;
        /**
         * gradient 模式下歌词自己的鲜艳四色坡道。与 {@code gradient} 不同，这条
         * <b>不会</b>被压到纸墨梯上：字才是承载封面颜色的东西，所以保留色相，
         * 只对纸面强制一个对比下限。
         */
        public String[] textGradient;
    }

    /** 固定的纸→墨混合位置；网点层把调子索引映射到网点密度。 */
    public static final float[] TEMPERA_TONE_STOPS = {0.12f, 0.30f, 0.52f, 0.72f};

    private static final float MIN_INK_CONTRAST = 96f;
    private static final float THEME_HUE_MIX = 0.32f;

    // ------------------------------------------------------------------
    // 颜色小工具（与 colorMix.ts 的字符串语义逐字对齐）
    // ------------------------------------------------------------------

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampChannel(float value) {
        return Math.round(clamp(value, 0f, 255f));
    }

    private static String rgba(int r, int g, int b, float alpha) {
        return "rgba(" + r + ", " + g + ", " + b + ", " + clamp(alpha, 0f, 1f) + ")";
    }

    private static String rgb(int r, int g, int b) {
        return "rgb(" + r + ", " + g + ", " + b + ")";
    }

    /** {@code colorWithAlpha}：不可解析时落白。 */
    private static String colorWithAlpha(String color, float alpha) {
        float[] channels = TemperaColor.parseChannels(color);
        if (channels == null) return rgba(255, 255, 255, alpha);
        return rgba(clampChannel(channels[0]), clampChannel(channels[1]), clampChannel(channels[2]), alpha);
    }

    /** {@code mixColors}：任一不可解析时按 {@code amount >= 0.5} 二选一。 */
    private static String mixColors(String from, String to, float amount) {
        return mixColors(from, to, amount, 1f);
    }

    private static String mixColors(String from, String to, float amount, float alpha) {
        float normalized = clamp(amount, 0f, 1f);
        float[] a = TemperaColor.parseChannels(from);
        float[] b = TemperaColor.parseChannels(to);
        if (a == null || b == null) {
            return colorWithAlpha(normalized >= 0.5f ? to : from, alpha);
        }
        return rgba(
                Math.round(a[0] + (b[0] - a[0]) * normalized),
                Math.round(a[1] + (b[1] - a[1]) * normalized),
                Math.round(a[2] + (b[2] - a[2]) * normalized),
                alpha);
    }

    private static float luminanceOf(String color) {
        float[] channels = TemperaColor.parseChannels(color);
        if (channels == null) return 128f;
        return channels[0] * 0.2126f + channels[1] * 0.7152f + channels[2] * 0.0722f;
    }

    private static String toGray(String color, String fallback) {
        float[] channels = TemperaColor.parseChannels(color);
        if (channels == null) return fallback;
        int luminance = Math.round(channels[0] * 0.2126f + channels[1] * 0.7152f + channels[2] * 0.0722f);
        return rgb(luminance, luminance, luminance);
    }

    private static float grayLevel(String color) {
        float[] channels = TemperaColor.parseChannels(color);
        return channels == null ? 128f : channels[0];
    }

    /**
     * 保证墨/纸这一对真的有对比。主题色并不承诺这一点：很多主题拿一个很浅的强调色配
     * 一个很浅的背景，歌词会读不出来，反色滤镜也会拿到两个几乎一样的颜色去二选一。
     * 这时把墨推到纸的另一端，只留一丝主题色相。
     */
    private static String ensureInkContrast(String paper, String ink) {
        if (Math.abs(luminanceOf(ink) - luminanceOf(paper)) >= MIN_INK_CONTRAST) return ink;
        String target = luminanceOf(paper) < 128f ? "#f4f4f2" : "#141414";
        String tinted = mixColors(target, ink, 0.14f);
        return Math.abs(luminanceOf(tinted) - luminanceOf(paper)) >= MIN_INK_CONTRAST ? tinted : target;
    }

    /**
     * 把一个带色相的颜色重新缩回未染色时的亮度上，于是「加色相」永远不会打乱调子阶梯。
     */
    private static String matchLuminance(String color, String target) {
        float[] channels = TemperaColor.parseChannels(color);
        if (channels == null) return target;
        float current = luminanceOf(color);
        float wanted = luminanceOf(target);
        if (current <= 0.5f) return target;
        float gain = wanted / current;
        int r = Math.round(Math.min(255f, channels[0] * gain));
        int g = Math.round(Math.min(255f, channels[1] * gain));
        int b = Math.round(Math.min(255f, channels[2] * gain));
        // 某个亮通道被裁剪会破坏这次匹配；退回中性那一级。
        if (Math.abs(luminanceOf(rgb(r, g, b)) - wanted) > 1.5f) return target;
        return rgb(r, g, b);
    }

    /** 四级网点阶梯。色相只挪动彩度：每一级都被拉回中性级的亮度。 */
    private static final class ToneLadder {
        String tone1;
        String tone2;
        String tone3;
        String tone4;

        ToneLadder(String paper, String ink, String tintA, String tintB) {
            tone1 = step(paper, ink, 0, tintA);
            tone2 = step(paper, ink, 1, tintB);
            tone3 = step(paper, ink, 2, tintA);
            tone4 = step(paper, ink, 3, null);
        }

        private static String step(String paper, String ink, int index, String tint) {
            String base = mixColors(paper, ink, TEMPERA_TONE_STOPS[index]);
            return tint != null ? matchLuminance(mixColors(base, tint, 0.3f), base) : base;
        }
    }

    /**
     * 四色渐变坡道。封面颜色带来色相，但每个都先被拉到纸→墨的明度梯上：一条无序的坡道
     * 会同时破坏构图的调子结构和读它的歌词反色。
     */
    private static String[] buildGradientRamp(String paper, String ink, String[] sources) {
        List<String> usable = new ArrayList<>();
        for (String color : sources) {
            if (color != null && !color.trim().isEmpty()) usable.add(color.trim());
        }
        usable.sort((a, b) -> Float.compare(luminanceOf(a), luminanceOf(b)));
        boolean towardInk = luminanceOf(ink) < luminanceOf(paper);
        if (towardInk) java.util.Collections.reverse(usable);
        String[] ramp = new String[TEMPERA_TONE_STOPS.length];
        for (int index = 0; index < ramp.length; index++) {
            String rung = mixColors(paper, ink, TEMPERA_TONE_STOPS[index]);
            String hue = usable.isEmpty() ? null : usable.get(index % usable.size());
            ramp[index] = hue != null ? matchLuminance(mixColors(rung, hue, 0.78f), rung) : rung;
        }
        return ramp;
    }

    /**
     * 让封面颜色仍然可辨认，同时保证它在纸面上读得出来。不做拉到墨梯这种事（那会把背景
     * 坡道灰掉），只是把它推离纸的亮度直到越过下限。
     */
    private static String enforceReadable(String paper, String color) {
        float paperLuminance = luminanceOf(paper);
        if (Math.abs(luminanceOf(color) - paperLuminance) >= 88f) return color;
        String away = paperLuminance < 128f ? "#ffffff" : "#101010";
        for (int step = 1; step <= 5; step++) {
            String pushed = mixColors(color, away, step * 0.16f);
            if (Math.abs(luminanceOf(pushed) - paperLuminance) >= 88f) return pushed;
        }
        return mixColors(color, away, 0.8f);
    }

    /** 每个抽取到的封面颜色里掺多少主题。封面仍然是主体，否则这个模式会显得无视用户配色。 */
    private static String[] blendCoverWithTheme(String[] cover, String[] themeHues) {
        int count = Math.min(4, cover.length);
        String[] out = new String[count];
        for (int index = 0; index < count; index++) {
            out[index] = mixColors(cover[index], themeHues[index % themeHues.length], THEME_HUE_MIX);
        }
        return out;
    }

    private static String[] buildTextGradient(String paper, String[] sources) {
        List<String> usable = new ArrayList<>();
        for (String color : sources) {
            if (color != null && !color.trim().isEmpty()) usable.add(color.trim());
        }
        if (usable.isEmpty()) return null;
        String[] out = new String[4];
        for (int index = 0; index < 4; index++) {
            out[index] = enforceReadable(paper, usable.get(index % usable.size()));
        }
        return out;
    }

    // ------------------------------------------------------------------

    public static Palette resolve(Theme theme, String colorMode) {
        return resolve(theme, colorMode, new String[0]);
    }

    public static Palette resolve(Theme theme, String colorMode, String[] coverColors) {
        if (colorMode == null) colorMode = "duo";
        if ("mono".equals(colorMode)) {
            String paper = toGray(theme.backgroundColor, "#111111");
            String ink = toGray(theme.primaryColor, "#f5f5f5");
            // 就算主题的墨是中灰，也要保证可读的对比。
            if (Math.abs(grayLevel(ink) - grayLevel(paper)) < 96f) {
                ink = grayLevel(paper) < 128f ? "#f2f2f2" : "#141414";
            }
            ToneLadder ladder = new ToneLadder(paper, ink, null, null);
            Palette palette = new Palette();
            palette.paper = paper;
            palette.ink = ink;
            palette.blockA = mixColors(paper, ink, 0.08f);
            palette.blockB = mixColors(paper, ink, 0.18f);
            palette.blockC = mixColors(paper, ink, 0.34f);
            palette.accent = mixColors(paper, ink, 0.85f);
            palette.line = colorWithAlpha(mixColors(paper, ink, 0.55f), 0.55f);
            palette.shadow = colorWithAlpha(mixColors(paper, ink, 0.75f), 0.35f);
            palette.tone1 = ladder.tone1;
            palette.tone2 = ladder.tone2;
            palette.tone3 = ladder.tone3;
            palette.tone4 = ladder.tone4;
            palette.gradient = null;
            palette.textGradient = null;
            return palette;
        }
        String paper = theme.backgroundColor;
        String ink = ensureInkContrast(paper, theme.primaryColor);
        if ("gradient".equals(colorMode)) {
            // 封面颜色承载坡道，每个都往主题染一点，让用户配色仍然在场；还没有封面时
            // 主题色相自己撑起来。
            String[] themeHues = {theme.accentColor, theme.secondaryColor, theme.primaryColor, ink};
            String[] hues = coverColors.length >= 2
                    ? blendCoverWithTheme(coverColors, themeHues)
                    : themeHues;
            String[] ramp = buildGradientRamp(paper, ink, hues);
            Palette palette = new Palette();
            palette.paper = paper;
            palette.ink = ink;
            palette.blockA = ramp[0];
            palette.blockB = ramp[1];
            palette.blockC = ramp[2];
            palette.accent = theme.accentColor;
            palette.line = colorWithAlpha(mixColors(paper, ink, 0.6f), 0.5f);
            palette.shadow = colorWithAlpha(mixColors(paper, ink, 0.8f), 0.32f);
            palette.tone1 = ramp[0];
            palette.tone2 = ramp[1];
            palette.tone3 = ramp[2];
            palette.tone4 = ramp[3];
            palette.gradient = ramp;
            palette.textGradient = buildTextGradient(paper, hues);
            return palette;
        }
        // duo：同一条明度梯，中间几级用主题色相染色，于是网点构图在两种色彩模式下读起来一样。
        ToneLadder ladder = new ToneLadder(paper, ink, theme.accentColor, theme.secondaryColor);
        Palette palette = new Palette();
        palette.paper = paper;
        palette.ink = ink;
        palette.blockA = mixColors(paper, theme.accentColor, 0.55f);
        palette.blockB = mixColors(paper, theme.secondaryColor, 0.6f);
        palette.blockC = mixColors(paper, ink, 0.78f);
        palette.accent = theme.accentColor;
        palette.line = colorWithAlpha(mixColors(paper, ink, 0.6f), 0.5f);
        palette.shadow = colorWithAlpha(mixColors(paper, ink, 0.8f), 0.32f);
        palette.tone1 = ladder.tone1;
        palette.tone2 = ladder.tone2;
        palette.tone3 = ladder.tone3;
        palette.tone4 = ladder.tone4;
        palette.gradient = null;
        palette.textGradient = null;
        return palette;
    }

    /** 调试用：把坡道拼成一行便于日志。 */
    public static String describe(Palette palette) {
        return "paper=" + palette.paper + " ink=" + palette.ink
                + " tones=" + Arrays.asList(palette.tone1, palette.tone2, palette.tone3, palette.tone4);
    }
}
