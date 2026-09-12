package dev.t1m3.qplayer.desktop.lyric.tempera;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// src/components/visualizer/tempera/compositions/temperaFrameCompositions.ts
// 描边的窗口。它们把字安放进一处安静的口袋，让周围的调子去做主要工作，
// 所以一段需要喘口气的段落会切到这类构图。

/**
 * 框族构图：外轮廓的小窗、错位双框、圆窗、阶梯括号等。
 * 1:1 移植自 folia-major {@code tempera/compositions/temperaFrameCompositions.ts}。
 */
public final class TemperaFrameCompositions {
    private TemperaFrameCompositions() {
    }

    // 一个描边菱形窗：字落在安静的口袋里，调子做主要工作。
    private static void frameWindow(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float cx = width / 2;
        float cy = height / 2;
        float rx = width * 0.42f;
        float ry = height * 0.44f;
        ctx.add(TemperaShapes.drawHatchFill(
                TemperaHatch.diamondPolygon(cx, cy, rx * 0.78f, ry * 0.78f),
                TemperaHatch.buildHatchSpec(ctx.seed, 23, 1.4f), palette.tone2, 0.45f),
                TemperaBlocks.BlockOptions.of().grow().span(0.6f));
        ctx.add(TemperaShapes.drawConcentricDiamonds(cx, cy, rx, ry, 3, palette.ink, 0.9f),
                TemperaBlocks.BlockOptions.of().delay(0.08f).enterDY(height * 0.06f).span(0.55f));
        if (!ctx.showDecor) return;
        ctx.add(TemperaShapes.drawCrossMarks(
                TemperaHatch.buildCrossRow(ctx.seed, 29, width * 0.08f, height * 0.14f, 3,
                        width * 0.045f, 8f, 0f),
                palette.tone4, 1.8f, 0.85f),
                TemperaBlocks.BlockOptions.of().delay(0.22f));
        ctx.add(TemperaShapes.drawSquareMarks(
                TemperaHatch.buildDotRow(ctx.seed, 37, width * 0.92f, height * 0.62f, 4,
                        height * 0.06f, 7f, (float) (Math.PI / 2)),
                palette.tone4, 0.8f),
                TemperaBlocks.BlockOptions.of().delay(0.28f));
    }

    // 两个错位的矩形：字落在其中一个里面，并搭到另一个的边缘上。
    private static void doubleFrame(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float offset = width * 0.05f;
        float[] box = TemperaHatch.rectPolygon(width * 0.16f, height * 0.2f, width * 0.68f, height * 0.6f);
        float[] shifted = TemperaHatch.rectPolygon(width * 0.16f + offset, height * 0.2f + offset * 0.6f,
                width * 0.68f, height * 0.6f);
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone1, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        ctx.add(TemperaShapes.drawPolygonFill(shifted, palette.tone3, 0.8f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.06f).span(0.55f).enterDX(offset * 3f));
        ctx.add(TemperaShapes.drawHatchFill(shifted, TemperaHatch.buildHatchSpec(ctx.seed, 43),
                palette.tone4, 0.4f),
                TemperaBlocks.BlockOptions.of().delay(0.1f).span(0.55f).grow());
        ctx.add(TemperaShapes.drawPolygonOutline(box, palette.ink, 3.5f, 0.9f),
                TemperaBlocks.BlockOptions.of().delay(0.14f).span(0.5f).enterDY(-height * 0.08f));
    }

    private static void circleWindow(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float radius = Math.min(width, height) * 0.34f;
        float[] circle = TemperaHatch.circlePolygon(width / 2, height / 2, radius, 28);
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone3, 0.92f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        ctx.add(TemperaShapes.drawHatchFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                TemperaHatch.buildHatchSpec(ctx.seed, 47), palette.tone4, 0.4f),
                TemperaBlocks.BlockOptions.of().delay(0.04f).span(0.6f).grow());
        ctx.add(TemperaShapes.drawPolygonFill(circle, palette.paper, 0.95f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.08f).span(0.55f));
        ctx.add(TemperaShapes.drawPolygonOutline(circle, palette.ink, 3, 0.9f),
                TemperaBlocks.BlockOptions.of().delay(0.14f).span(0.5f));
        if (!ctx.showDecor) return;
        ctx.add(TemperaShapes.drawPolygonOutline(
                TemperaHatch.circlePolygon(width / 2, height / 2, radius * 1.12f), palette.ink, 1.2f, 0.55f),
                TemperaBlocks.BlockOptions.of().delay(0.2f).drift());
    }

    // 一侧落下的阶梯括号：读起来像一条页边规线，而不是闭合的盒子。
    private static void ladderFrame(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float left = width * 0.14f;
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone1, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        int steps = 5;
        for (int index = 0; index < steps; index += 1) {
            float y = height * (0.18f + index * 0.14f);
            float run = width * (0.06f + index * 0.03f);
            List<TemperaHatch.Line> stepLines = new ArrayList<>();
            stepLines.addAll(TemperaShapes.line(left, y, left + run, y));
            stepLines.addAll(TemperaShapes.line(left, y, left, y + height * 0.14f));
            ctx.add(TemperaShapes.drawLines(stepLines, palette.tone4, index % 2 == 0 ? 3f : 1.4f, 0.85f),
                    TemperaBlocks.BlockOptions.of().delay(index * 0.05f).span(0.5f).enterDX(-width * 0.1f));
        }
        ctx.add(TemperaShapes.drawLines(
                TemperaShapes.line(width * 0.9f, -bleed, width * 0.9f, height + bleed),
                palette.tone4, 1.4f, 0.6f),
                TemperaBlocks.BlockOptions.of().delay(0.28f).span(0.5f).enterDY(height * 0.2f));
    }

    private static void cornerBrackets(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float inset = Math.min(width, height) * 0.12f;
        float arm = Math.min(width, height) * 0.16f;
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone2, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        // 四个角：每个 [x, y, 横向符号, 纵向符号]。
        float[][] corners = {
                {inset, inset, 1f, 1f},
                {width - inset, inset, -1f, 1f},
                {inset, height - inset, 1f, -1f},
                {width - inset, height - inset, -1f, -1f},
        };
        for (int index = 0; index < corners.length; index += 1) {
            float x = corners[index][0];
            float y = corners[index][1];
            float sx = corners[index][2];
            float sy = corners[index][3];
            List<TemperaHatch.Line> cornerLines = new ArrayList<>();
            cornerLines.addAll(TemperaShapes.line(x, y, x + sx * arm, y));
            cornerLines.addAll(TemperaShapes.line(x, y, x, y + sy * arm));
            ctx.add(TemperaShapes.drawLines(cornerLines, palette.ink, 3.5f, 0.9f),
                    TemperaBlocks.BlockOptions.of().delay(index * 0.05f).span(0.5f)
                            .enterDX(sx * width * 0.06f).enterDY(sy * height * 0.06f));
        }
        if (!ctx.showDecor) return;
        boolean dotted = TemperaRandom.hash01(ctx.seed, 2, 53) > 0.5;
        ctx.add(dotted
                ? TemperaShapes.drawSquareMarks(
                        TemperaHatch.buildDotRow(ctx.seed, 59, width * 0.5f, height * 0.16f, 3,
                                width * 0.03f, 6f, 0f),
                        palette.tone4, 0.8f)
                : TemperaShapes.drawCrossMarks(
                        TemperaHatch.buildCrossRow(ctx.seed, 61, width * 0.44f, height * 0.84f, 3,
                                width * 0.04f, 7f, 0f),
                        palette.tone4, 1.8f, 0.8f),
                TemperaBlocks.BlockOptions.of().delay(0.24f).drift());
    }

    // 一个内嵌矩形，淡淡地填一点：留住一句话最安静的方式。
    private static void insetBox(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float[] box = TemperaHatch.rectPolygon(width * 0.14f, height * 0.2f, width * 0.72f, height * 0.6f);
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone1, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        ctx.add(TemperaShapes.drawPolygonFill(box, palette.tone3, 0.55f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.55f).enterDY(height * 0.06f));
        ctx.add(TemperaShapes.drawPolygonOutline(box, palette.ink, 2.4f, 0.85f),
                TemperaBlocks.BlockOptions.of().delay(0.1f).span(0.5f));
    }

    // 一对相向的括号，而不是闭合的盒子；字落在两颌之间。
    private static void bracketPair(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float inset = width * 0.16f;
        float arm = width * 0.09f;
        float top = height * 0.26f;
        float bottom = height * 0.74f;
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone2, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        float[] sides = {1f, -1f};
        for (int index = 0; index < sides.length; index += 1) {
            float side = sides[index];
            float x = side == 1f ? inset : width - inset;
            List<TemperaHatch.Line> bracketLines = new ArrayList<>();
            bracketLines.addAll(TemperaShapes.line(x, top, x + side * arm, top));
            bracketLines.addAll(TemperaShapes.line(x, top, x, bottom));
            bracketLines.addAll(TemperaShapes.line(x, bottom, x + side * arm, bottom));
            ctx.add(TemperaShapes.drawLines(bracketLines, palette.ink, 4, 0.9f),
                    TemperaBlocks.BlockOptions.of().delay(index * 0.07f).span(0.5f).enterDX(side * width * 0.08f));
        }
    }

    // 一个圆顶的窗：半圆骑在矩形上。
    private static void archWindow(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float radius = width * 0.24f;
        float cx = width / 2;
        float shoulder = height * 0.42f;
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone3, 0.92f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        float[] arch = TemperaHatch.circlePolygon(cx, shoulder, radius, 48);
        float[] body = TemperaHatch.rectPolygon(cx - radius, shoulder, radius * 2, height * 0.4f);
        ctx.add(TemperaShapes.drawPolygonFill(arch, palette.paper, 0.95f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.55f).enterDY(-height * 0.08f));
        ctx.add(TemperaShapes.drawPolygonFill(body, palette.paper, 0.95f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.55f).enterDY(height * 0.08f));
        ctx.add(TemperaShapes.drawPolygonOutline(arch, palette.ink, 2.4f, 0.8f),
                TemperaBlocks.BlockOptions.of().delay(0.12f).span(0.5f));
        ctx.add(TemperaShapes.drawPolygonOutline(body, palette.ink, 2.4f, 0.8f),
                TemperaBlocks.BlockOptions.of().delay(0.12f).span(0.5f));
    }

    // 一张发丝级 3x3 格架；歌词跑过中间那一行。
    private static void gridCells(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone1, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        float left = width * 0.12f;
        float top = height * 0.18f;
        float cellWidth = (width * 0.76f) / 3;
        float cellHeight = (height * 0.64f) / 3;
        for (int row = 0; row < 3; row += 1) {
            for (int column = 0; column < 3; column += 1) {
                float[] cell = TemperaHatch.rectPolygon(left + cellWidth * column, top + cellHeight * row,
                        cellWidth, cellHeight);
                int index = row * 3 + column;
                if (row == 1 && column == 1) {
                    ctx.add(TemperaShapes.drawPolygonFill(cell, palette.tone4, 0.75f, ctx.gradient),
                            TemperaBlocks.BlockOptions.of().delay(index * 0.03f).span(0.5f));
                }
                ctx.add(TemperaShapes.drawPolygonOutline(cell, palette.tone4, 1.2f, 0.6f),
                        TemperaBlocks.BlockOptions.of().delay(index * 0.03f).span(0.5f));
            }
        }
    }

    // 圆盘压在一条窄轴上：剪影读起来像调子上凿出的锁孔。
    private static void keyhole(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float radius = Math.min(width, height) * 0.19f;
        float cx = width / 2;
        float cy = height * 0.36f;
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone4, 0.94f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        float[] head = TemperaHatch.circlePolygon(cx, cy, radius, 48);
        float[] shaft = TemperaHatch.rectPolygon(cx - radius * 0.55f, cy, radius * 1.1f, height * 0.46f);
        ctx.add(TemperaShapes.drawPolygonFill(head, palette.paper, 0.96f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.55f));
        ctx.add(TemperaShapes.drawPolygonFill(shaft, palette.paper, 0.96f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.08f).span(0.55f).enterDY(height * 0.1f));
        ctx.add(TemperaShapes.drawPolygonOutline(head, palette.ink, 2, 0.7f),
                TemperaBlocks.BlockOptions.of().delay(0.14f).span(0.5f));
    }

    public static void register(Map<String, TemperaCompositions.Drawer> into) {
        into.put("frame-window", TemperaFrameCompositions::frameWindow);
        into.put("double-frame", TemperaFrameCompositions::doubleFrame);
        into.put("circle-window", TemperaFrameCompositions::circleWindow);
        into.put("ladder-frame", TemperaFrameCompositions::ladderFrame);
        into.put("corner-brackets", TemperaFrameCompositions::cornerBrackets);
        into.put("inset-box", TemperaFrameCompositions::insetBox);
        into.put("bracket-pair", TemperaFrameCompositions::bracketPair);
        into.put("arch-window", TemperaFrameCompositions::archWindow);
        into.put("grid-cells", TemperaFrameCompositions::gridCells);
        into.put("keyhole", TemperaFrameCompositions::keyhole);
    }
}
