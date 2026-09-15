package dev.t1m3.qplayer.desktop.lyric.tempera;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// src/components/visualizer/tempera/compositions/temperaSparseCompositions.ts
// 给呼吸段落用的近乎空场：发丝线、点阵和手绘笔触，几乎不带调子质量，
// 于是字读起来像一声低语。

/**
 * 稀疏族构图：近空的呼吸段落，发丝线、点阵、手绘笔触为主。
 * 1:1 移植自 folia-major {@code tempera/compositions/temperaSparseCompositions.ts}。
 */
public final class TemperaSparseCompositions {
    private TemperaSparseCompositions() {
    }

    // 几条细水平线，像格线一样把字托起来。
    private static void quietLine(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float gridWidth = width * 0.7f;
        float gridX = (width - gridWidth) / 2;
        float[] ratios = {0.38f, 0.5f, 0.62f};
        for (int index = 0; index < ratios.length; index += 1) {
            float ratio = ratios[index];
            ctx.add(TemperaShapes.drawPolygonFill(
                    TemperaHatch.rectPolygon(gridX, height * ratio, gridWidth, 1),
                    palette.line, 1, ctx.gradient),
                    TemperaBlocks.BlockOptions.of().delay(index * 0.08f).span(0.6f)
                            .enterDX((index % 2 == 0 ? -1 : 1) * width * 0.2f));
        }
        if (!ctx.showDecor) return;
        ctx.add(TemperaShapes.drawPolyline(
                TemperaHatch.buildScribblePath(ctx.decor.scribbleSeed, 47, width * 0.2f, height * 0.26f,
                        Math.min(width, height) * 0.09f, 2),
                palette.tone4, 1.6f, 0.7f),
                TemperaBlocks.BlockOptions.of().delay(0.24f).span(0.6f));
        // 草丛：从一条基线点扇开的一簇短笔触。
        float tuftX = width * 0.82f;
        float tuftY = height * 0.74f;
        List<TemperaHatch.Line> tufts = new ArrayList<>();
        for (int index = 0; index < 6; index += 1) {
            float lean = (float) (TemperaRandom.hash01(ctx.decor.scribbleSeed, index, 59) - 0.5) * 34;
            tufts.addAll(TemperaShapes.line(tuftX + index * 7, tuftY,
                    tuftX + index * 7 + lean, tuftY - 24 - index * 3));
        }
        ctx.add(TemperaShapes.drawLines(tufts, palette.tone4, 1.4f, 0.7f),
                TemperaBlocks.BlockOptions.of().delay(0.3f).span(0.55f));
    }

    // 一个朝一侧变稀的点阵；字漂在稀疏的那一半。
    private static void starfieldDots(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float spacing = Math.max(24f, (float) Math.sqrt((width * height) / 900));
        List<TemperaHatch.DecorMark> marks = TemperaHatch.buildDotGrid(width + bleed, height + bleed, spacing, 2.6f);
        // 两个独立的过滤：下方为密，上方且落在对角带的为疏。
        List<TemperaHatch.DecorMark> dense = new ArrayList<>();
        for (TemperaHatch.DecorMark mark : marks) {
            if (mark.y > height * 0.45f) dense.add(mark);
        }
        List<TemperaHatch.DecorMark> sparse = new ArrayList<>();
        for (TemperaHatch.DecorMark mark : marks) {
            if (mark.y <= height * 0.45f && (mark.x + mark.y) % 3 < 1) sparse.add(mark);
        }
        ctx.add(TemperaShapes.drawSquareMarks(dense, palette.tone4, 0.45f),
                TemperaBlocks.BlockOptions.of().span(0.6f).enterDY(height * 0.2f));
        ctx.add(TemperaShapes.drawSquareMarks(sparse, palette.tone4, 0.25f),
                TemperaBlocks.BlockOptions.of().delay(0.08f).span(0.6f).enterDY(-height * 0.15f));
        if (!ctx.showDecor) return;
        ctx.add(TemperaShapes.drawLines(
                TemperaShapes.line(-bleed, height * 0.45f, width + bleed, height * 0.45f),
                palette.tone4, 1.2f, 0.5f),
                TemperaBlocks.BlockOptions.of().delay(0.2f).span(0.5f).enterDX(width * 0.2f));
    }

    // 同心的水波线，像从水面下刚一点看过去的表面。
    private static void rippleLines(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        int count = 7;
        for (int index = 0; index < count; index += 1) {
            float y = height * (0.16f + index * 0.11f);
            float amplitude = height * (0.006f + index * 0.004f);
            ctx.add(TemperaShapes.drawPolyline(
                    TemperaHatch.buildWavyPath(ctx.seed, 89 + index, -bleed, width + bleed, y, amplitude, 26),
                    palette.tone4, index % 3 == 0 ? 2 : 1.1f, 0.55f),
                    TemperaBlocks.BlockOptions.of().delay(index * 0.045f).span(0.6f)
                            .enterDX((index % 2 == 0 ? -1 : 1) * width * 0.15f));
        }
    }

    // 一整张通栏发丝格架；短语浮在它上面。
    private static void hairGrid(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        int columns = 6;
        int rows = 4;
        List<TemperaHatch.Line> vertical = new ArrayList<>();
        for (int index = 0; index <= columns; index += 1) {
            vertical.addAll(TemperaShapes.line((width / columns) * index, -bleed,
                    (width / columns) * index, height + bleed));
        }
        List<TemperaHatch.Line> horizontal = new ArrayList<>();
        for (int index = 0; index <= rows; index += 1) {
            horizontal.addAll(TemperaShapes.line(-bleed, (height / rows) * index,
                    width + bleed, (height / rows) * index));
        }
        ctx.add(TemperaShapes.drawLines(vertical, palette.line, 1, 0.8f),
                TemperaBlocks.BlockOptions.of().span(0.6f).enterDY(-height * 0.1f));
        ctx.add(TemperaShapes.drawLines(horizontal, palette.line, 1, 0.8f),
                TemperaBlocks.BlockOptions.of().delay(0.08f).span(0.6f).enterDX(width * 0.1f));
    }

    // 一条落在一边页边上的重规线，旁边排几个套准标记。
    private static void marginRule(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        boolean fromLeft = TemperaRandom.hash01(ctx.seed, 121, 127) > 0.5;
        float x = fromLeft ? width * 0.12f : width * 0.88f;
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(x - 3, -bleed, 6, height + bleed * 2),
                palette.tone4, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.6f).enterDY(height * 0.15f));
        if (!ctx.showDecor) return;
        List<TemperaHatch.DecorMark> marks = new ArrayList<>();
        for (int index = 0; index < 3; index += 1) {
            marks.add(new TemperaHatch.DecorMark(x + (fromLeft ? 22f : -22f),
                    height * (0.32f + index * 0.18f), 7, 0));
        }
        ctx.add(TemperaShapes.drawSquareMarks(marks, palette.tone4, 0.8f),
                TemperaBlocks.BlockOptions.of().delay(0.22f).drift());
    }

    // 沿一条对角线变稀的点阵。
    private static void dotDrift(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float spacing = Math.max(22f, (float) Math.sqrt((width * height) / 1200));
        List<TemperaHatch.DecorMark> marks = TemperaHatch.buildDotGrid(width + bleed, height + bleed, spacing, 2.4f);
        float span = width + height;
        List<TemperaHatch.DecorMark> near = new ArrayList<>();
        for (TemperaHatch.DecorMark mark : marks) {
            if (mark.x + mark.y > span * 0.5f) near.add(mark);
        }
        List<TemperaHatch.DecorMark> far = new ArrayList<>();
        for (TemperaHatch.DecorMark mark : marks) {
            if (mark.x + mark.y <= span * 0.5f && (mark.x + mark.y) % 2 < 1) far.add(mark);
        }
        ctx.add(TemperaShapes.drawSquareMarks(near, palette.tone4, 0.5f),
                TemperaBlocks.BlockOptions.of().span(0.6f).enterDX(width * 0.12f));
        ctx.add(TemperaShapes.drawSquareMarks(far, palette.tone4, 0.24f),
                TemperaBlocks.BlockOptions.of().delay(0.08f).span(0.6f).enterDX(-width * 0.12f));
    }

    // 以画框外为中心的同心弧，于是只有弧线穿过画面。
    private static void arcSweep(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float cx = width * (TemperaRandom.hash01(ctx.seed, 131, 137) > 0.5 ? 1.15f : -0.15f);
        float cy = height * 1.05f;
        float base = (float) Math.hypot(width, height) * 0.42f;
        for (int index = 0; index < 5; index += 1) {
            ctx.add(TemperaShapes.drawPolygonOutline(
                    TemperaHatch.circlePolygon(cx, cy, base + index * base * 0.22f, 72),
                    palette.tone4, index % 2 == 0 ? 1.8f : 1, 0.55f),
                    TemperaBlocks.BlockOptions.of().delay(index * 0.05f).span(0.6f).enterDY(height * 0.08f));
        }
    }

    // 几乎空白的纸，只有一个小小的套准标记。一段 verse 前的那口气。
    private static void blankPage(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        ctx.add(TemperaShapes.drawLines(
                TemperaShapes.line(width * 0.18f, height * 0.7f, width * 0.36f, height * 0.7f),
                palette.line, 1.4f, 0.8f),
                TemperaBlocks.BlockOptions.of().span(0.6f).enterDX(-width * 0.12f));
        if (!ctx.showDecor) return;
        ctx.add(TemperaShapes.drawPolyline(
                TemperaHatch.buildScribblePath(ctx.decor.scribbleSeed, 139, width * 0.82f, height * 0.26f,
                        Math.min(width, height) * 0.06f, 2),
                palette.tone4, 1.4f, 0.6f),
                TemperaBlocks.BlockOptions.of().delay(0.28f).span(0.6f));
    }

    public static void register(Map<String, TemperaCompositions.Drawer> into) {
        into.put("quiet-line", TemperaSparseCompositions::quietLine);
        into.put("starfield-dots", TemperaSparseCompositions::starfieldDots);
        into.put("ripple-lines", TemperaSparseCompositions::rippleLines);
        into.put("hair-grid", TemperaSparseCompositions::hairGrid);
        into.put("margin-rule", TemperaSparseCompositions::marginRule);
        into.put("dot-drift", TemperaSparseCompositions::dotDrift);
        into.put("arc-sweep", TemperaSparseCompositions::arcSweep);
        into.put("blank-page", TemperaSparseCompositions::blankPage);
    }
}
