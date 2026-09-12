package dev.t1m3.qplayer.desktop.lyric.tempera;

import java.util.ArrayList;
import java.util.List;

// src/components/visualizer/tempera/compositions/temperaBandCompositions.ts
// 水平的色层。配合 Tempera 的纵向流动，这些色层读起来像景深：
// 画面顺着色层往下沉，这正把一次镜头交接从「硬切」变成「俯冲」。
// 与同包其他类互引用不到 import。

public final class TemperaBandCompositions {

    private TemperaBandCompositions() {
    }

    // 一条色带，两侧有导引线贴着边；歌词在这两条线之间的中间调上反色。
    private static void bandStrip(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float bandY = height * 0.37f;
        float bandHeight = height * 0.3f;
        ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(-bleed, bandY, width + bleed * 2, bandHeight), palette.tone3, 0.96f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().span(0.55f).enterDX(-width * 0.5f));
        List<TemperaHatch.Line> guideLines = new ArrayList<>();
        guideLines.addAll(TemperaShapes.line(-bleed, bandY - 10, width + bleed, bandY - 22));
        guideLines.addAll(TemperaShapes.line(-bleed, bandY + bandHeight + 22, width + bleed, bandY + bandHeight + 10));
        ctx.add(TemperaShapes.drawLines(guideLines, palette.tone4, 1.4f, 0.75f),
            TemperaBlocks.BlockOptions.of().delay(0.12f).enterDX(width * 0.25f));

        if (!ctx.showDecor) return;
        ctx.add(TemperaShapes.drawCrossMarks(TemperaHatch.buildCrossRow(ctx.seed, 17, width * 0.06f, bandY - height * 0.16f, 4, width * 0.055f), palette.ink, 2, 0.8f),
            TemperaBlocks.BlockOptions.of().delay(0.2f).span(0.5f));
        ctx.add(TemperaShapes.drawSquareMarks(TemperaHatch.buildDotRow(ctx.seed, 19, width * 0.94f, bandY + bandHeight + height * 0.06f, 3, height * 0.05f, 6, (float) (Math.PI / 2)), palette.ink, 0.75f),
            TemperaBlocks.BlockOptions.of().delay(0.26f).drift());
    }

    // 一条水位线：上方浅、下方密，两道水之间是一道起伏的弯月面。
    private static void horizonBand(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float waterline = (float) (height * (0.5 + TemperaRandom.hash01(ctx.seed, 1, 23) * 0.12));
        ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, waterline + bleed), palette.tone1, 0.94f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().span(0.55f).enterDY(-height * 0.3f));
        float[] water = TemperaHatch.rectPolygon(-bleed, waterline, width + bleed * 2, height - waterline + bleed);
        ctx.add(TemperaShapes.drawPolygonFill(water, palette.tone3, 0.95f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.55f).enterDY(height * 0.3f));
        ctx.add(TemperaShapes.drawHatchFill(water, TemperaHatch.buildHatchSpec(ctx.seed, 29).withAngle(0f), palette.tone4, 0.55f),
            TemperaBlocks.BlockOptions.of().delay(0.1f).span(0.55f).grow());
        ctx.add(TemperaShapes.drawPolyline(TemperaHatch.buildWavyPath(ctx.seed, 31, -bleed, width + bleed, waterline, height * 0.012f, 30), palette.ink, 2.2f, 0.85f),
            TemperaBlocks.BlockOptions.of().delay(0.16f).span(0.5f));
    }

    // 层叠的色层，越往画面底部越密。
    private static void deepDive(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        String[] tones = { palette.tone1, palette.tone2, palette.tone3, palette.tone4 };
        float bandHeight = (height + bleed * 2) / tones.length;
        for (int index = 0; index < tones.length; index += 1) {
            String tone = tones[index];
            float top = -bleed + bandHeight * index;
            ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(-bleed, top, width + bleed * 2, bandHeight + 1), tone, 0.95f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(index * 0.06f).span(0.55f).enterDY(height * 0.3f));
            if (index == 0) continue;
            ctx.add(TemperaShapes.drawPolyline(TemperaHatch.buildWavyPath(ctx.seed, 37 + index, -bleed, width + bleed, top, height * 0.008f, 24), palette.paper, 1.6f, 0.5f),
                TemperaBlocks.BlockOptions.of().delay(index * 0.06f + 0.05f).span(0.5f));
        }
    }

    // 不是离散面板，而是一段密度斜坡：同样的排线角度，间距不断收紧。
    private static void toneRamp(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        TemperaHatch.HatchSpec spec = TemperaHatch.buildHatchSpec(ctx.seed, 41);
        ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2), palette.tone1, 0.92f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().span(0.5f));
        int steps = 4;
        for (int index = 0; index < steps; index += 1) {
            float columnWidth = (width + bleed * 2) / steps;
            float[] column = TemperaHatch.rectPolygon(-bleed + columnWidth * index, -bleed, columnWidth, height + bleed * 2);
            ctx.add(TemperaShapes.drawHatchFill(column, spec.withSpacing(spec.spacing * (1.6f - index * 0.32f)), palette.tone4, 0.6f),
                TemperaBlocks.BlockOptions.of().delay(index * 0.06f).span(0.55f).grow());
        }
    }

    // 两道导轨，中间留出亮缝；歌词就坐在缝里。
    private static void doubleBand(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float slot = (float) (height * (0.2 + TemperaRandom.hash01(ctx.seed, 51, 43) * 0.08));
        float rail = height * 0.3f;
        float top = (height - slot) / 2 - rail;
        ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(-bleed, top, width + bleed * 2, rail), palette.tone3, 0.95f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().span(0.55f).enterDX(-width * 0.4f));
        ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(-bleed, top + rail + slot, width + bleed * 2, rail), palette.tone4, 0.95f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.07f).span(0.55f).enterDX(width * 0.4f));
    }

    // 一条偏离水平、贯穿两侧边缘的斜色带。
    private static void tiltBand(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float lean = (float) (height * (0.14 + TemperaRandom.hash01(ctx.seed, 53, 47) * 0.14));
        float half = height * 0.17f;
        float[] band = new float[]{
            -bleed, height / 2 + lean - half,
            width + bleed, height / 2 - lean - half,
            width + bleed, height / 2 - lean + half,
            -bleed, height / 2 + lean + half,
        };
        ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2), palette.tone1, 0.9f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().span(0.5f));
        ctx.add(TemperaShapes.drawPolygonFill(band, palette.tone4, 0.95f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.06f).span(0.55f).enterDX(-width * 0.35f));
        ctx.add(TemperaShapes.drawHatchFill(band, TemperaHatch.buildHatchSpec(ctx.seed, 59).withAngle((float) (Math.PI / 2)), palette.paper, 0.3f),
            TemperaBlocks.BlockOptions.of().delay(0.12f).span(0.55f).grow());
    }

    // 贴着上下边缘的厚重导轨，中间留空。
    private static void edgeRails(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float rail = (float) (height * (0.16 + TemperaRandom.hash01(ctx.seed, 61, 53) * 0.06));
        ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, rail + bleed), palette.tone3, 0.94f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().span(0.55f).enterDY(-height * 0.2f));
        ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(-bleed, height - rail, width + bleed * 2, rail + bleed), palette.tone3, 0.94f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.06f).span(0.55f).enterDY(height * 0.2f));
        if (!ctx.showDecor) return;
        List<TemperaHatch.Line> rails = new ArrayList<>();
        rails.addAll(TemperaShapes.line(-bleed, rail + 8, width + bleed, rail + 8));
        rails.addAll(TemperaShapes.line(-bleed, height - rail - 8, width + bleed, height - rail - 8));
        ctx.add(TemperaShapes.drawLines(rails, palette.tone4, 1.2f, 0.6f),
            TemperaBlocks.BlockOptions.of().delay(0.16f).span(0.5f).enterDX(width * 0.2f));
    }

    // 一整面满版色调，排线朝一侧不断收紧——是一堵色调墙，不是一条色带。
    private static void gradientWall(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        TemperaHatch.HatchSpec spec = TemperaHatch.buildHatchSpec(ctx.seed, 67);
        boolean downward = TemperaRandom.hash01(ctx.seed, 71, 59) > 0.5;
        ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2), palette.tone2, 0.94f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().span(0.5f));
        int steps = 5;
        float bandHeight = height / steps;
        for (int index = 0; index < steps; index += 1) {
            int rank = downward ? index : steps - 1 - index;
            float top = bandHeight * index - (index == 0 ? bleed : 0);
            float bottom = bandHeight * (index + 1) + (index == steps - 1 ? bleed : 0);
            ctx.add(TemperaShapes.drawHatchFill(
                TemperaHatch.rectPolygon(-bleed, top, width + bleed * 2, bottom - top),
                spec.withSpacing(spec.spacing * (1.9f - rank * 0.34f)),
                palette.tone4,
                0.55f),
                TemperaBlocks.BlockOptions.of().delay(index * 0.05f).span(0.55f).grow());
        }
    }

    // 阶梯状的色层，每一层都比上面那层更往里缩。
    private static void terrace(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        String[] tones = { palette.tone1, palette.tone2, palette.tone3, palette.tone4 };
        float bandHeight = height / tones.length;
        ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2), palette.tone1, 0.9f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().span(0.5f));
        for (int index = 0; index < tones.length; index += 1) {
            String tone = tones[index];
            float left = width * index * 0.14f - bleed;
            float top = bandHeight * index - (index == 0 ? bleed : 0);
            float bottom = bandHeight * (index + 1) + (index == tones.length - 1 ? bleed : 1);
            ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(left, top, width + bleed * 2 - left - bleed, bottom - top), tone, 0.94f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(index * 0.06f).span(0.55f).enterDX(-width * 0.25f));
        }
    }

    // 注册本文件里的全部构图。
    public static void register(java.util.Map<String, TemperaCompositions.Drawer> into) {
        into.put("band-strip", TemperaBandCompositions::bandStrip);
        into.put("horizon-band", TemperaBandCompositions::horizonBand);
        into.put("deep-dive", TemperaBandCompositions::deepDive);
        into.put("tone-ramp", TemperaBandCompositions::toneRamp);
        into.put("double-band", TemperaBandCompositions::doubleBand);
        into.put("tilt-band", TemperaBandCompositions::tiltBand);
        into.put("edge-rails", TemperaBandCompositions::edgeRails);
        into.put("gradient-wall", TemperaBandCompositions::gradientWall);
        into.put("terrace", TemperaBandCompositions::terrace);
    }
}
