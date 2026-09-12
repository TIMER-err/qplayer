package dev.t1m3.qplayer.desktop.lyric.tempera;

import io.github.humbleui.skija.Canvas;

import java.util.ArrayList;
import java.util.List;

/**
 * 待机／前奏画面。
 *
 * <p>folia 的运行时在「没有段落可以画」时直接返回，什么都不留 —— 在它的宿主里那只是露出应用
 * 自己的背景，但 qplayer 的凝彩是<b>整帧独占</b>的宿主 pass：什么都不画就是一块黑板。于是
 * 「歌还没放到歌词」和「这首歌根本没有歌词」这两段会变成完全静止的黑屏。
 *
 * <p>这里补上那一层：纸色底 + 缓慢滚动的网点场 + 两块呼吸的几何板 + 曲名／歌手。前奏等待
 * 因此是一张像样的标题卡，而不是黑屏；整首没歌词的歌也有东西可看。
 *
 * <p>全部由绝对时间决定，seek 到同一时刻得到同一帧，不持有任何随机状态。
 */
public final class TemperaIdle {
    private TemperaIdle() {
    }

    /** 网点场只有平移在变，所以按尺寸 + 颜色缓存整块图形（{@code Discs} 只建一次 Path）。 */
    private static float cacheW = -1f;
    private static float cacheH = -1f;
    private static String cacheColor;
    private static float cacheAlpha = -1f;
    private static TemperaDraw.Graphic cacheDots;

    private static final int FONT_WEIGHT = 700;

    public static void paint(Canvas canvas, TemperaPalette.Palette palette,
                             float width, float height, double time,
                             String title, String artist, boolean hasLyrics,
                             boolean inversion) {
        if (palette == null || width < 1f || height < 1f) return;
        int save = canvas.save();
        // 极慢的整体呼吸：静止的几何也需要一点生命感，否则等待看起来像卡住了。
        float breath = 1f + (float) Math.sin(time * 0.22) * 0.012f;
        canvas.translate(width / 2f, height / 2f);
        canvas.scale(breath, breath);
        canvas.translate(-width / 2f, -height / 2f);

        paintDriftingPanels(canvas, palette, width, height, time);
        paintDotField(canvas, palette, width, height, time);
        paintTitle(canvas, palette, width, height, time, title, artist, hasLyrics, inversion);

        canvas.restoreToCount(save);
    }

    /** 两块缓慢横移的色板：给空画面一个方向感，也给文字一个可反色的底。 */
    private static void paintDriftingPanels(Canvas canvas, TemperaPalette.Palette palette,
                                            float width, float height, double time) {
        float drift = (float) Math.sin(time * 0.11) * width * 0.06f;
        float drift2 = (float) Math.cos(time * 0.07) * width * 0.05f;

        float upperY = height * 0.14f;
        float lowerY = height * 0.74f;
        float bandH = height * 0.09f;

        TemperaDraw.Graphic upper = TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-width * 0.1f + drift, upperY,
                        width * 1.2f, bandH), palette.tone1, 0.85f);
        upper.paint(canvas);

        TemperaDraw.Graphic lower = TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-width * 0.1f + drift2, lowerY,
                        width * 1.2f, bandH * 0.7f), palette.tone2, 0.7f);
        lower.paint(canvas);
    }

    /** 缓慢滚动的网点场：它是「凝彩」的底噪，静止时也一直在流动。 */
    private static void paintDotField(Canvas canvas, TemperaPalette.Palette palette,
                                      float width, float height, double time) {
        float spacing = Math.max(20f, Math.min(46f, Math.min(width, height) * 0.05f));
        float alpha = 0.5f;
        if (cacheDots == null || Math.abs(cacheW - width) > 0.5f || Math.abs(cacheH - height) > 0.5f
                || cacheAlpha != alpha || !palette.tone3.equals(cacheColor)) {
            List<TemperaCurves.Disc> dots = new ArrayList<>();
            for (float y = -spacing; y <= height + spacing; y += spacing) {
                for (float x = -spacing; x <= width + spacing; x += spacing) {
                    dots.add(new TemperaCurves.Disc(x, y, spacing * 0.055f));
                }
            }
            cacheW = width;
            cacheH = height;
            cacheColor = palette.tone3;
            cacheAlpha = alpha;
            cacheDots = TemperaShapes.drawDiscs(dots, palette.tone3, alpha);
        }
        float period = spacing * 4f;
        float offset = (float) ((time * 9.0) % period);
        cacheDots.setPosition(offset, -offset * 0.45f).paint(canvas);
    }

    /** 曲名 / 歌手 / 状态提示。曲名与歌词字形一样走差分反色，所以始终读得出来。 */
    private static void paintTitle(Canvas canvas, TemperaPalette.Palette palette,
                                   float width, float height, double time,
                                   String title, String artist, boolean hasLyrics,
                                   boolean inversion) {
        float centerX = width / 2f;
        // 标题略微上下浮动，幅度控制在「看得出在动、但不像在抖」。
        float floatY = (float) Math.sin(time * 0.35) * height * 0.012f;
        float centerY = height * 0.44f + floatY;

        String text = title == null ? "" : title.trim();
        float used = 0f;
        if (!text.isEmpty()) {
            used = TemperaTextView.drawFittedLine(canvas, text, FONT_WEIGHT,
                    Math.min(height * 0.26f, width * 0.16f), width * 0.72f,
                    inversion ? palette.paper : palette.ink, 1f,
                    centerX, centerY, inversion);
        }

        float cursor = centerY + used * 0.78f;
        String artistText = artist == null ? "" : artist.trim();
        if (!artistText.isEmpty()) {
            float subSize = used > 0f ? Math.max(15f, used * 0.24f) : height * 0.035f;
            TemperaTextView.drawFittedLine(canvas, artistText, 500, subSize, width * 0.6f,
                    palette.tone4, 0.78f, centerX, cursor + subSize, false);
            cursor += subSize * 2.4f;
        }
        if (!hasLyrics) {
            float hintSize = used > 0f ? Math.max(14f, used * 0.2f) : height * 0.03f;
            TemperaTextView.drawFittedLine(canvas, "暂无歌词", 500,
                    hintSize, width * 0.6f, palette.tone4, 0.5f, centerX, cursor + hintSize, false);
        }
    }
}
