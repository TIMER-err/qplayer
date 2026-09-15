package dev.t1m3.qplayer.desktop.lyric.tempera;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// src/components/visualizer/tempera/compositions/temperaCinemaCompositions.ts
// 影院遮幅：一整块实色遮体，正中冲出一个固定画幅比的窗口。洞是歌词所在处，
// 所以周围的色调读作遮幅而非色块；差值滤镜有一条硬边可以把字切出来。
// 注意：本文件与 TemperaShapes / TemperaHatch / TemperaBlocks / TemperaRandom / TemperaPalette
// 同处一个扁平包，互相引用不需要 import。

public final class TemperaCinemaCompositions {

    private TemperaCinemaCompositions() {
    }

    /** 一个被适配进画面的窗口：用真实像素尺寸而非视口比例，因为「正方」窗口在任意屏上都要方。 */
    private record Win(float x, float y, float width, float height) {}

    /** 遮体上的一条横/竖条：它自己带一段入场位移。 */
    private record Bar(float[] polygon, float enterDX, float enterDY) {}

    /**
     * 把指定画幅比的窗口适配进画面。用真实像素尺寸而非视口比例，因为「正方」窗口在任意屏上都要方。
     */
    private static Win fitWindow(float width, float height, float aspect, float fill) {
        final float maxWidth = width * fill;
        final float maxHeight = height * fill;
        final float windowWidth = Math.min(maxWidth, maxHeight * aspect);
        final float windowHeight = windowWidth / aspect;
        return new Win((width - windowWidth) / 2, (height - windowHeight) / 2, windowWidth, windowHeight);
    }

    /**
     * 遮体画成四条条而不是一个带孔的形状：条是凸的（网点裁剪器需要），而且每条都能各自入场。
     */
    private static void addMatte(TemperaCompositionContext ctx, Win hole, String tone) {
        final float width = ctx.width;
        final float height = ctx.height;
        final float bleed = ctx.bleed;
        final Bar[] bars = new Bar[]{
            new Bar(TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, hole.y + bleed), 0, -height * 0.2f),
            new Bar(TemperaHatch.rectPolygon(-bleed, hole.y + hole.height, width + bleed * 2, height - hole.y - hole.height + bleed), 0, height * 0.2f),
            new Bar(TemperaHatch.rectPolygon(-bleed, hole.y, hole.x + bleed, hole.height), -width * 0.2f, 0),
            new Bar(TemperaHatch.rectPolygon(hole.x + hole.width, hole.y, width - hole.x - hole.width + bleed, hole.height), width * 0.2f, 0),
        };
        for (int index = 0; index < bars.length; index += 1) {
            final Bar bar = bars[index];
            ctx.add(TemperaShapes.drawPolygonFill(bar.polygon, tone, 0.96f, ctx.gradient),
                TemperaBlocks.BlockOptions.of()
                    .delay(index * 0.04f)
                    .span(0.5f)
                    .enterDX(bar.enterDX)
                    .enterDY(bar.enterDY));
        }
        // 内缘是遮幅的全部意义，所以单独给它一条线。
        ctx.add(TemperaShapes.drawPolygonOutline(
            TemperaHatch.rectPolygon(hole.x, hole.y, hole.width, hole.height),
            ctx.palette.ink, 1.6f, 0.5f),
            TemperaBlocks.BlockOptions.of().delay(0.2f).span(0.5f));
    }

    // 窗口里一层极淡的色调，让洞不至于只是裸地面。
    private static void addWindowWash(TemperaCompositionContext ctx, Win hole, float alpha) {
        final float[] polygon = TemperaHatch.rectPolygon(hole.x, hole.y, hole.width, hole.height);
        ctx.add(TemperaShapes.drawPolygonFill(polygon, ctx.palette.tone1, alpha, ctx.gradient),
            TemperaBlocks.BlockOptions.of().span(0.55f));
        ctx.add(TemperaShapes.drawHatchFill(polygon, TemperaHatch.buildHatchSpec(ctx.seed, 149, 1.5f), ctx.palette.tone4, 0.2f),
            TemperaBlocks.BlockOptions.of().delay(0.1f).span(0.6f).grow());
    }

    private static void matte(TemperaCompositionContext ctx, float aspect, float fill, float washAlpha) {
        final Win hole = fitWindow(ctx.width, ctx.height, aspect, fill);
        addWindowWash(ctx, hole, washAlpha);
        addMatte(ctx, hole, ctx.palette.tone4);
        if (!ctx.showDecor) return;
        // 遮体上的套准刻度，对齐窗口边缘。
        final List<TemperaHatch.Line> ticks = new ArrayList<>();
        ticks.add(new TemperaHatch.Line(hole.x, hole.y - ctx.height * 0.05f, hole.x, hole.y - ctx.height * 0.02f));
        ticks.add(new TemperaHatch.Line(hole.x + hole.width, hole.y + hole.height + ctx.height * 0.02f,
            hole.x + hole.width, hole.y + hole.height + ctx.height * 0.05f));
        ctx.add(TemperaShapes.drawLines(ticks, ctx.palette.paper, 2f, 0.6f),
            TemperaBlocks.BlockOptions.of().delay(0.26f).span(0.5f));
    }

    // 一个略微偏方的窗口随种子漂移，于是同样画幅比不会两次落得完全一样。
    private static float jitteredFill(TemperaCompositionContext ctx, float base) {
        return (float) (base + (TemperaRandom.hash01(ctx.seed, 157, 163) - 0.5) * 0.06);
    }

    private static void cinemaScope(TemperaCompositionContext ctx) {
        matte(ctx, 2.39f, jitteredFill(ctx, 0.88f), 0.35f);
    }

    private static void cinemaWide(TemperaCompositionContext ctx) {
        matte(ctx, 1.85f, jitteredFill(ctx, 0.84f), 0.35f);
    }

    private static void cinemaAcademy(TemperaCompositionContext ctx) {
        matte(ctx, 1.33f, jitteredFill(ctx, 0.78f), 0.35f);
    }

    private static void cinemaSquare(TemperaCompositionContext ctx) {
        matte(ctx, 1f, jitteredFill(ctx, 0.72f), 0.35f);
    }

    private static void cinemaPortrait(TemperaCompositionContext ctx) {
        matte(ctx, 0.75f, jitteredFill(ctx, 0.72f), 0.35f);
    }

    private static void cinemaTall(TemperaCompositionContext ctx) {
        matte(ctx, 0.5625f, jitteredFill(ctx, 0.72f), 0.35f);
    }

    // 左右两个并排窗口；歌词用更宽的左边那个。
    private static void cinemaTwin(TemperaCompositionContext ctx) {
        final float width = ctx.width;
        final float height = ctx.height;
        final TemperaPalette.Palette palette = ctx.palette;
        final float bleed = ctx.bleed;
        final float inset = height * 0.16f;
        final float gutter = width * 0.04f;
        final Win left = new Win(width * 0.08f, inset, width * 0.5f, height - inset * 2);
        final Win right = new Win(left.x + left.width + gutter, inset + height * 0.1f,
            width * 0.28f, height - inset * 2 - height * 0.2f);
        ctx.add(TemperaShapes.drawPolygonFill(
            TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
            palette.tone4, 0.96f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().span(0.5f));
        final Win[] holes = new Win[]{ left, right };
        for (int index = 0; index < holes.length; index += 1) {
            final Win hole = holes[index];
            final float[] polygon = TemperaHatch.rectPolygon(hole.x, hole.y, hole.width, hole.height);
            ctx.add(TemperaShapes.drawPolygonFill(polygon, palette.tone1, index == 0 ? 0.4f : 0.85f, ctx.gradient),
                TemperaBlocks.BlockOptions.of()
                    .delay(index * 0.08f)
                    .span(0.55f)
                    .enterDX((index == 0 ? -1 : 1) * width * 0.14f));
            ctx.add(TemperaShapes.drawPolygonOutline(polygon, palette.ink, 1.6f, 0.5f),
                TemperaBlocks.BlockOptions.of().delay(0.16f + index * 0.05f).span(0.5f));
        }
        if (!ctx.showDecor) return;
        ctx.add(TemperaShapes.drawHatchFill(
            TemperaHatch.rectPolygon(right.x, right.y, right.width, right.height),
            TemperaHatch.buildHatchSpec(ctx.seed, 151),
            palette.tone4, 0.35f),
            TemperaBlocks.BlockOptions.of().delay(0.24f).span(0.6f).grow());
    }

    // 注册本文件里的全部构图。
    public static void register(Map<String, TemperaCompositions.Drawer> into) {
        into.put("cinema-scope", TemperaCinemaCompositions::cinemaScope);
        into.put("cinema-wide", TemperaCinemaCompositions::cinemaWide);
        into.put("cinema-academy", TemperaCinemaCompositions::cinemaAcademy);
        into.put("cinema-square", TemperaCinemaCompositions::cinemaSquare);
        into.put("cinema-portrait", TemperaCinemaCompositions::cinemaPortrait);
        into.put("cinema-tall", TemperaCinemaCompositions::cinemaTall);
        into.put("cinema-twin", TemperaCinemaCompositions::cinemaTwin);
    }
}
