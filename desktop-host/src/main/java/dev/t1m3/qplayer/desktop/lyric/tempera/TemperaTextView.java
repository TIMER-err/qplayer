package dev.t1m3.qplayer.desktop.lyric.tempera;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMetrics;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.TextLine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 字形视图，1:1 移植自 folia-major {@code tempera/temperaTextView.ts}。
 *
 * <p>每个字素一个视图，外加一份偏移的「重影」拷贝，读起来像印刷套印没对准。
 *
 * <p>重影<b>位于反色层内部</b>，而不是它下面。放到下面，重影就成了滤镜采样的背景的一部分：
 * 每个字随后会相对自己的影子翻转颜色，沿笔画裂成硬块。放在层内，滤镜把重影与字一样上色，
 * 于是那份偏移拷贝读起来像是第二块印版，而不是污染了判定。也从不画什么在字后面强调它——
 * 与作品之间的反色本身就是强调。
 */
public final class TemperaTextView {
    private TemperaTextView() {
    }

    private static final float SHADOW_OFFSET_X = 0.06f;
    private static final float SHADOW_OFFSET_Y = 0.08f;
    /** 重影由滤镜上色，所以只有不透明度归它自己。 */
    private static final float SHADOW_ALPHA = 0.34f;

    /** 一个字素的全部绘制常量；运行时只读，不再回看排版。 */
    public static final class View {
        public String charText;
        public int fontWeight;
        public float fontSize;
        /** display 的填充色：关键字色或 palette.ink。 */
        public String displayColor;
        /** 残影的填充色：关键字色或 palette.tone4。 */
        public String echoColor;
        public String shadowColor;
        /** 关键字字形在反色层之上、永不参与反色，于是主题的色相得以保留。 */
        public boolean keyword;
        public float baseX;
        public float baseY;
        public float shadowDX;
        public float shadowDY;
        /** 逐帧求解的输入。 */
        public final TemperaMotion.Input motion = new TemperaMotion.Input();
    }

    /**
     * 从排版结果构建视图。{@code shadowEnabled} 与 {@code echoCount} 对应
     * {@code tuning.showDecor} 与静态模式。
     */
    public static List<View> build(List<TemperaLayout.GlyphPlacement> placements,
                                   TemperaPalette.Palette palette,
                                   int fontWeight,
                                   boolean shadowEnabled,
                                   int echoCount) {
        List<View> views = new ArrayList<>();
        for (TemperaLayout.GlyphPlacement placement : placements) {
            if (placement.charText == null || placement.charText.trim().isEmpty()) continue;
            View view = new View();
            view.charText = placement.charText;
            view.fontWeight = fontWeight;
            view.fontSize = placement.fontSize;
            view.displayColor = placement.color != null ? placement.color : palette.ink;
            view.echoColor = placement.color != null ? placement.color : palette.tone4;
            view.shadowColor = palette.ink;
            view.keyword = placement.color != null;
            view.baseX = placement.x;
            view.baseY = placement.y;
            view.shadowDX = placement.fontSize * SHADOW_OFFSET_X;
            view.shadowDY = placement.fontSize * SHADOW_OFFSET_Y;
            view.motion.startTime = placement.startTime;
            view.motion.settleTime = placement.settleTime;
            view.motion.endTime = placement.endTime;
            view.motion.enterX = placement.enterX;
            view.motion.enterY = placement.enterY;
            view.motion.enterRotation = placement.enterRotation;
            view.motion.enterScale = placement.enterScale;
            view.motion.rotation = placement.rotation;
            view.motion.enterStyle = placement.enterStyle;
            view.motion.releaseTime = placement.releaseTime;
            view.motion.trackingX = placement.trackingX;
            view.motion.trackingY = placement.trackingY;
            views.add(view);
        }
        return views;
    }

    /**
     * 文本行缓存：{@code TextLine} 是原生资源，每个字形每帧新建会在长歌里堆出可观的分配。
     * 字体本身由 {@code Fonts} 缓存，所以 (Font identity, char) 就是稳定的键。
     */
    private static final int LINE_CACHE_LIMIT = 8192;
    private record LineKey(Font font, String text) { }
    private static final LinkedHashMap<LineKey, TextLine> LINES =
            new LinkedHashMap<>(64, 0.75f, true);
    /**
     * 共享的画笔。{@link #dispose()} 会把它关掉，所以这里<b>不能</b>是 final 常量——渲染线程
     * 重启（或渲染器被释放后又被复用）时，下一次绘制必须能重新造一支，否则就会拿着已释放的
     * 原生对象继续画，直接崩在 Skia 里。
     */
    private static Paint sharedPaint;

    private static Paint paint() {
        if (sharedPaint == null) sharedPaint = new Paint().setAntiAlias(true);
        return sharedPaint;
    }

    private static TextLine lineFor(Font font, String text) {
        LineKey key = new LineKey(font, text);
        TextLine cached = LINES.get(key);
        if (cached != null) return cached;
        TextLine line = TextLine.make(text, font);
        if (LINES.size() >= LINE_CACHE_LIMIT) {
            Iterator<TextLine> oldest = LINES.values().iterator();
            TextLine evicted = oldest.next();
            oldest.remove();
            evicted.close();
        }
        LINES.put(key, line);
        return line;
    }

    static void releaseFont(Font font) {
        var entries = LINES.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (entry.getKey().font() == font) {
                entry.getValue().close();
                entries.remove();
            }
        }
    }

    /** 释放全部缓存的原生文本行（渲染线程销毁时调用）。之后仍可继续使用本类，缓存会重建。 */
    public static void dispose() {
        LINES.values().forEach(TextLine::close);
        LINES.clear();
        if (sharedPaint != null) {
            sharedPaint.close();
            sharedPaint = null;
        }
    }

    /**
     * 画一个字素（含影子的调用方自行传偏移）。
     *
     * <p>{@code difference} 为真时用 DIFFERENCE 混合，这是「凝彩」把字从作品里反色出来的那一步。
     * 关键在于<b>传进来的颜色</b>：差分算的是 {@code |字色 - 背景|}，所以字色必须取<b>纸色</b>，
     * 结果才会是「浅底上得到深字、深底上得到浅字」——对比度永不塌陷。用墨色去差分恰恰相反：
     * 浅纸底上会算出接近纸色的浅色，整行字都糊掉。
     */
    private static void drawGlyph(Canvas canvas, View view, float x, float y,
                                  float rotation, float scaleX, float scaleY,
                                  float alpha, String color, boolean difference) {
        if (alpha <= 0.002f) return;
        Font font = TemperaMeasure.fontFor(view.fontWeight, view.fontSize, view.charText);
        if (font == null) return;
        TextLine line = lineFor(font, view.charText);
        float width = line.getWidth();
        FontMetrics metrics = font.getMetrics();
        // Pixi 的 anchor(0.5) 以文本包围盒中心为原点；这里把基线挪到同一中心。
        float baseline = -(metrics.getAscent() + metrics.getDescent()) / 2f;
        int save = canvas.save();
        canvas.translate(x, y);
        if (rotation != 0f) canvas.rotate((float) Math.toDegrees(rotation));
        if (scaleX != 1f || scaleY != 1f) canvas.scale(scaleX, scaleY);
        paint().setColor(TemperaColor.withAlpha(color, 1f));
        paint().setAlphaf(alpha);
        paint().setBlendMode(difference ? BlendMode.DIFFERENCE : BlendMode.SRC_OVER);
        canvas.drawTextLine(line, -width / 2f, baseline, paint());
        canvas.restoreToCount(save);
    }

    /**
     * 求解并绘制一个镜头的整层文字。
     *
     * <p>分层顺序与 folia 一致：{@code textLayer}（影子 + 普通字形，走反色）→
     * {@code echoLayer}（残影，不反色）→ {@code keywordLayer}（关键字，不反色）。
     * fragments 与 watermark 由场景层负责，因为它们不在动态文字层里。
     *
     * <p>{@code paper} 是差分反色要用的字色，见 {@link #drawGlyph}。
     */
    public static void paint(Canvas canvas, List<View> views, double time, float motionAmount,
                             int echoCount, boolean shadowEnabled, boolean inversion,
                             String paper) {
        // 反色时字色取纸色：差分把它翻成「浅底深字 / 深底浅字」；关掉反色时才直接用墨色。
        String tone = inversion ? paper : null;
        // 1) 影子与普通字形同层：影子先画（addChildAt(0) 的等价物）。
        for (View view : views) {
            if (view.keyword) continue;
            TemperaMotion.Frame frame = TemperaMotion.resolve(view.motion, time, motionAmount);
            if (!frame.visible) continue;
            float x = view.baseX + frame.x;
            float y = view.baseY + frame.y;
            if (shadowEnabled) {
                drawGlyph(canvas, view, x + view.shadowDX, y + view.shadowDY,
                        frame.rotation, frame.scaleX, frame.scaleY,
                        frame.alpha * SHADOW_ALPHA,
                        tone != null ? tone : view.shadowColor, inversion);
            }
            drawGlyph(canvas, view, x, y, frame.rotation, frame.scaleX, frame.scaleY,
                    frame.alpha, tone != null ? tone : view.displayColor, inversion);
        }
        // 2) 残影：沿入场矢量越排越远，读起来是一条拖尾而不是一团模糊。
        if (echoCount > 0) {
            for (View view : views) {
                if (view.keyword) continue;
                TemperaMotion.Frame frame = TemperaMotion.resolve(view.motion, time, motionAmount);
                if (!frame.visible || frame.echoAlpha <= 0.004f) continue;
                for (int index = 0; index < echoCount; index++) {
                    float depth = 1f + index * 0.85f;
                    drawGlyph(canvas, view,
                            view.baseX + frame.echoX * depth,
                            view.baseY + frame.echoY * depth,
                            frame.rotation, frame.scaleX, frame.scaleY,
                            frame.echoAlpha / (index + 1.4f), view.echoColor, false);
                }
            }
        }
        // 3) 关键字字形：反色层之上、永不参与反色，主题的色相得以存活。
        for (View view : views) {
            if (!view.keyword) continue;
            TemperaMotion.Frame frame = TemperaMotion.resolve(view.motion, time, motionAmount);
            if (!frame.visible) continue;
            drawGlyph(canvas, view, view.baseX + frame.x, view.baseY + frame.y,
                    frame.rotation, frame.scaleX, frame.scaleY,
                    frame.alpha, view.displayColor, false);
        }
    }

    /*
     * 静态装饰（fragments / watermark）没有时间线：它们由镜头自己的入/退场不透明度接管，
     * 所以播放永远不碰这些节点。下面两个方法在构建场景时一次性记录，绘制时按镜头的
     * 容器变换一起走。
     */

    /** 背景装饰词：刻意放在反色文字层<b>之下</b>，于是歌词横穿它的笔画时翻色。 */
    public static void drawWatermark(Canvas canvas, TemperaTypes.DecorWatermark watermark,
                                     TemperaPalette.Palette palette, int fontWeight,
                                     float baseFontSize, float width, float height) {
        if (watermark == null || watermark.text == null || watermark.text.trim().isEmpty()) return;
        float size = Math.max(48f, baseFontSize * watermark.scale);
        Font font = TemperaMeasure.fontFor(fontWeight, size, watermark.text);
        if (font == null) return;
        TextLine line = lineFor(font, watermark.text);
        FontMetrics metrics = font.getMetrics();
        float baseline = -(metrics.getAscent() + metrics.getDescent()) / 2f;
        int save = canvas.save();
        canvas.translate(watermark.x * width, watermark.y * height);
        if (watermark.rotation != 0f) canvas.rotate((float) Math.toDegrees(watermark.rotation));
        paint().setColor(TemperaColor.withAlpha(palette.tone4, 1f));
        paint().setAlphaf(0.16f);
        paint().setBlendMode(BlendMode.SRC_OVER);
        canvas.drawTextLine(line, -line.getWidth() / 2f, baseline, paint());
        canvas.restoreToCount(save);
    }

    /** 停在稀疏构图边角上的孤立字形，不携带时间线；属于反色层，故与普通字形同样混合。 */
    public static void drawFragment(Canvas canvas, TemperaTypes.DecorFragment fragment,
                                    TemperaPalette.Palette palette, int fontWeight,
                                    float baseFontSize, float width, float height,
                                    boolean inversion) {
        if (fragment == null || fragment.charText == null || fragment.charText.trim().isEmpty()) return;
        float size = Math.max(12f, baseFontSize * fragment.scale);
        Font font = TemperaMeasure.fontFor(fontWeight, size, fragment.charText);
        if (font == null) return;
        TextLine line = lineFor(font, fragment.charText);
        FontMetrics metrics = font.getMetrics();
        float baseline = -(metrics.getAscent() + metrics.getDescent()) / 2f;
        int save = canvas.save();
        canvas.translate(fragment.x * width, fragment.y * height);
        if (fragment.rotation != 0f) canvas.rotate((float) Math.toDegrees(fragment.rotation));
        paint().setColor(TemperaColor.withAlpha(inversion ? palette.paper : palette.ink, 1f));
        paint().setAlphaf(0.42f);
        paint().setBlendMode(inversion ? BlendMode.DIFFERENCE : BlendMode.SRC_OVER);
        canvas.drawTextLine(line, -line.getWidth() / 2f, baseline, paint());
        canvas.restoreToCount(save);
    }

    /** 大字号标题（片尾卡/水印级别的整句）。 */
    public static void drawTextLine(Canvas canvas, String text, Font font, String color,
                                    float alpha, float centerX, float centerY, float rotation) {
        if (text == null || text.isEmpty() || font == null) return;
        TextLine line = lineFor(font, text);
        FontMetrics metrics = font.getMetrics();
        float baseline = -(metrics.getAscent() + metrics.getDescent()) / 2f;
        int save = canvas.save();
        canvas.translate(centerX, centerY);
        if (rotation != 0f) canvas.rotate((float) Math.toDegrees(rotation));
        paint().setColor(TemperaColor.withAlpha(color, 1f));
        paint().setAlphaf(alpha);
        paint().setBlendMode(BlendMode.SRC_OVER);
        canvas.drawTextLine(line, -line.getWidth() / 2f, baseline, paint());
        canvas.restoreToCount(save);
    }

    /**
     * 一行会自己缩到 {@code maxWidth} 以内的居中文本，用于待机/前奏的曲名。
     *
     * <p>返回真正使用的字号，方便调用方按同一比例摆放副标题。行太长就按比例缩小（而不是
     * 截断），这样中英混排的长曲名不会溢出画面。
     */
    public static float drawFittedLine(Canvas canvas, String text, int fontWeight,
                                       float fontSize, float maxWidth,
                                       String color, float alpha,
                                       float centerX, float centerY, boolean difference) {
        if (text == null || text.trim().isEmpty()) return 0f;
        String trimmed = text.trim();
        Font font = TemperaMeasure.fontFor(fontWeight, fontSize, trimmed);
        if (font == null) return 0f;
        TextLine line = lineFor(font, trimmed);
        float width = line.getWidth();
        if (width > maxWidth && width > 1f) {
            fontSize = Math.max(14f, fontSize * (maxWidth / width));
            font = TemperaMeasure.fontFor(fontWeight, fontSize, trimmed);
            if (font == null) return 0f;
            line = lineFor(font, trimmed);
        }
        FontMetrics metrics = font.getMetrics();
        float baseline = -(metrics.getAscent() + metrics.getDescent()) / 2f;
        int save = canvas.save();
        canvas.translate(centerX, centerY);
        paint().setColor(TemperaColor.withAlpha(color, 1f));
        paint().setAlphaf(alpha);
        paint().setBlendMode(difference ? BlendMode.DIFFERENCE : BlendMode.SRC_OVER);
        canvas.drawTextLine(line, -line.getWidth() / 2f, baseline, paint());
        canvas.restoreToCount(save);
        return fontSize;
    }
}
