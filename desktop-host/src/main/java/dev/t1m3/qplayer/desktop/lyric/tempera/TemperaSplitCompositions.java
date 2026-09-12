package dev.t1m3.qplayer.desktop.lyric.tempera;

// src/components/visualizer/tempera/compositions/temperaSplitCompositions.ts
// 把画面切成几块扁平的色调面板的构图。反转滤镜对它们反应最强烈：
// 一个跨在面板边界上的字，会在笔画中途翻色。
// 注意：本文件与 TemperaShapes / TemperaHatch / TemperaBlocks / TemperaRandom / TemperaPalette
// 同处一个扁平包，因此互相引用不需要 import。

public final class TemperaSplitCompositions {

    /** 一块面板：多边形、色调、以及入场时的位移。 */
    private record Panel(float[] polygon, String tone, float enterDX, float enterDY) {
    }

    private TemperaSplitCompositions() {
    }

    // 把面板错落地铺进来，并给其中一块叠一层排线，让分割不会读成扁平的矢量图。
    private static void addPanels(TemperaCompositionContext ctx, Panel[] panels, int hatchIndex) {
        TemperaHatch.HatchSpec hatch = TemperaHatch.buildHatchSpec(ctx.seed, 5);
        for (int index = 0; index < panels.length; index += 1) {
            Panel panel = panels[index];
            ctx.add(TemperaShapes.drawPolygonFill(panel.polygon, panel.tone, 0.96f, ctx.gradient),
                TemperaBlocks.BlockOptions.of()
                    .delay(index * 0.05f)
                    .span(0.5f)
                    .enterDX(panel.enterDX)
                    .enterDY(panel.enterDY));
            if (index != hatchIndex) continue;
            ctx.add(TemperaShapes.drawHatchFill(panel.polygon, hatch, ctx.palette.tone4, 0.5f),
                TemperaBlocks.BlockOptions.of()
                    .delay(index * 0.05f + 0.06f)
                    .span(0.5f)
                    .grow());
        }
    }

    private static void addSeam(TemperaCompositionContext ctx, float[] polygon, float delay) {
        ctx.add(TemperaShapes.drawPolygonFill(polygon, ctx.palette.ink, 0.85f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(delay).span(0.5f));
    }

    // 上下或左右两块，中间留一道缝；其中一块带排线。
    private static void duoSplit(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        boolean horizontal = TemperaRandom.hash01(ctx.seed, 1, 3) > 0.5;
        Panel[] panels = horizontal
            ? new Panel[]{
                new Panel(TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height * 0.52f + bleed), palette.tone1, 0, -height * 0.55f),
                new Panel(TemperaHatch.rectPolygon(-bleed, height * 0.52f, width + bleed * 2, height * 0.48f + bleed), palette.tone3, 0, height * 0.55f),
            }
            : new Panel[]{
                new Panel(TemperaHatch.rectPolygon(-bleed, -bleed, width * 0.5f + bleed, height + bleed * 2), palette.tone1, -width * 0.5f, 0),
                new Panel(TemperaHatch.rectPolygon(width * 0.5f, -bleed, width * 0.5f + bleed, height + bleed * 2), palette.tone3, width * 0.5f, 0),
            };
        addPanels(ctx, panels, 0);
        addSeam(ctx, horizontal
            ? TemperaHatch.rectPolygon(-bleed, height * 0.52f - 1.5f, width + bleed * 2, 3)
            : TemperaHatch.rectPolygon(width * 0.5f - 1.5f, -bleed, 3, height + bleed * 2), 0.16f);
    }

    // 四块色调面板在字面下方交汇，每个象限从自己的角上入场。
    private static void quadSplit(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float splitX = (float) (width * (0.42 + TemperaRandom.hash01(ctx.seed, 2, 7) * 0.16));
        float splitY = (float) (height * (0.42 + TemperaRandom.hash01(ctx.seed, 3, 11) * 0.16));
        addPanels(ctx, new Panel[]{
            new Panel(TemperaHatch.rectPolygon(-bleed, -bleed, splitX + bleed, splitY + bleed), palette.tone1, -width * 0.3f, -height * 0.3f),
            new Panel(TemperaHatch.rectPolygon(splitX, -bleed, width - splitX + bleed, splitY + bleed), palette.tone4, width * 0.3f, -height * 0.3f),
            new Panel(TemperaHatch.rectPolygon(-bleed, splitY, splitX + bleed, height - splitY + bleed), palette.tone3, -width * 0.3f, height * 0.3f),
            new Panel(TemperaHatch.rectPolygon(splitX, splitY, width - splitX + bleed, height - splitY + bleed), palette.tone2, width * 0.3f, height * 0.3f),
        }, 3);
        addSeam(ctx, TemperaHatch.rectPolygon(splitX - 1.5f, -bleed, 3, height + bleed * 2), 0.2f);
        addSeam(ctx, TemperaHatch.rectPolygon(-bleed, splitY - 1.5f, width + bleed * 2, 3), 0.24f);
    }

    private static void triColumn(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float edge = (float) (width * (0.26 + TemperaRandom.hash01(ctx.seed, 4, 13) * 0.06));
        addPanels(ctx, new Panel[]{
            new Panel(TemperaHatch.rectPolygon(-bleed, -bleed, edge + bleed, height + bleed * 2), palette.tone1, -width * 0.25f, 0),
            new Panel(TemperaHatch.rectPolygon(edge, -bleed, width - edge * 2, height + bleed * 2), palette.tone4, 0, -height * 0.3f),
            new Panel(TemperaHatch.rectPolygon(width - edge, -bleed, edge + bleed, height + bleed * 2), palette.tone1, width * 0.25f, 0),
        }, 0);
    }

    private static void thirdsStack(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float band = height * 0.34f;
        addPanels(ctx, new Panel[]{
            new Panel(TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, band + bleed), palette.tone2, -width * 0.2f, 0),
            new Panel(TemperaHatch.rectPolygon(-bleed, band, width + bleed * 2, band), palette.tone4, width * 0.2f, 0),
            new Panel(TemperaHatch.rectPolygon(-bleed, band * 2, width + bleed * 2, height - band * 2 + bleed), palette.tone1, -width * 0.2f, 0),
        }, 0);
    }

    // 对角象限共享同一色调，字穿过中心时会交替变色。
    private static void checkerQuad(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float splitX = width * 0.5f;
        float splitY = height * 0.5f;
        addPanels(ctx, new Panel[]{
            new Panel(TemperaHatch.rectPolygon(-bleed, -bleed, splitX + bleed, splitY + bleed), palette.tone4, 0, -height * 0.35f),
            new Panel(TemperaHatch.rectPolygon(splitX, -bleed, width - splitX + bleed, splitY + bleed), palette.tone1, 0, -height * 0.35f),
            new Panel(TemperaHatch.rectPolygon(-bleed, splitY, splitX + bleed, height - splitY + bleed), palette.tone1, 0, height * 0.35f),
            new Panel(TemperaHatch.rectPolygon(splitX, splitY, width - splitX + bleed, height - splitY + bleed), palette.tone4, 0, height * 0.35f),
        }, 1);
    }

    private static void cornerWedge(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        boolean fromLeft = TemperaRandom.hash01(ctx.seed, 5, 17) > 0.5;
        float apexX = fromLeft ? width * 0.78f : width * 0.22f;
        addPanels(ctx, new Panel[]{
            new Panel(TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2), palette.tone1, 0, 0),
            new Panel(fromLeft
                ? new float[]{ -bleed, -bleed, apexX, -bleed, -bleed, height + bleed }
                : new float[]{ width + bleed, -bleed, apexX, -bleed, width + bleed, height + bleed },
                palette.tone4,
                fromLeft ? -width * 0.4f : width * 0.4f, 0),
        }, 1);
    }

    private static void diagonalHalves(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float lean = (float) (height * (0.2 + TemperaRandom.hash01(ctx.seed, 6, 19) * 0.3));
        addPanels(ctx, new Panel[]{
            new Panel(new float[]{ -bleed, -bleed, width + bleed, -bleed, width + bleed, lean, -bleed, height - lean },
                palette.tone2, 0, -height * 0.4f),
            new Panel(new float[]{ -bleed, height - lean, width + bleed, lean, width + bleed, height + bleed, -bleed, height + bleed },
                palette.tone4, 0, height * 0.4f),
        }, 1);
    }

    // 两条粗轴把画面切进四象限，字就落在交点的十字上。
    private static void crossAxis(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float barX = width * 0.11f;
        float barY = height * 0.13f;
        ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2), palette.tone1, 0.9f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().span(0.5f));
        ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon((width - barX) / 2, -bleed, barX, height + bleed * 2), palette.tone4, 0.95f),
            TemperaBlocks.BlockOptions.of().delay(0.06f).span(0.5f).enterDY(-height * 0.5f));
        ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(-bleed, (height - barY) / 2, width + bleed * 2, barY), palette.tone3, 0.95f),
            TemperaBlocks.BlockOptions.of().delay(0.12f).span(0.5f).enterDX(width * 0.5f));
    }

    // 两半互相错开；它们错位的那道台阶就是这张构图。
    private static void offsetHalves(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float step = (float) (height * (0.06 + TemperaRandom.hash01(ctx.seed, 7, 23) * 0.06));
        float seam = width * 0.48f;
        addPanels(ctx, new Panel[]{
            new Panel(TemperaHatch.rectPolygon(-bleed, -bleed, seam + bleed, height * 0.5f + step + bleed), palette.tone1, -width * 0.3f, 0),
            new Panel(TemperaHatch.rectPolygon(seam, -bleed, width - seam + bleed, height * 0.5f - step + bleed), palette.tone3, width * 0.3f, 0),
            new Panel(TemperaHatch.rectPolygon(-bleed, height * 0.5f + step, seam + bleed, height * 0.5f - step + bleed), palette.tone4, -width * 0.2f, 0),
            new Panel(TemperaHatch.rectPolygon(seam, height * 0.5f - step, width - seam + bleed, height * 0.5f + step + bleed), palette.tone2, width * 0.2f, 0),
        }, 2);
    }

    // 四块方块沿对角线行进，每一块都比前一块更深一档。
    private static void stairBlocks(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        String[] tones = { palette.tone1, palette.tone2, palette.tone3, palette.tone4 };
        ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2), palette.tone1, 0.9f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().span(0.5f));
        for (int index = 0; index < tones.length; index += 1) {
            String tone = tones[index];
            float left = (float) (width * (0.06 + index * 0.2));
            float top = (float) (height * (0.1 + index * 0.16));
            ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(left, top, width * 0.3f, height * 0.34f), tone, 0.94f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(index * 0.06f).span(0.5f).enterDX(-width * 0.2f).enterDY(height * 0.12f));
        }
    }

    // 两道厚重的色块之间留出一道明亮的竖向缝隙，歌词就站在缝里。
    private static void pillarGap(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float gap = (float) (width * (0.26 + TemperaRandom.hash01(ctx.seed, 8, 29) * 0.08));
        float side = (width - gap) / 2;
        addPanels(ctx, new Panel[]{
            new Panel(TemperaHatch.rectPolygon(-bleed, -bleed, side + bleed, height + bleed * 2), palette.tone4, -width * 0.3f, 0),
            new Panel(TemperaHatch.rectPolygon(width - side, -bleed, side + bleed, height + bleed * 2), palette.tone4, width * 0.3f, 0),
        }, 1);
    }

    // 四块故意不等重的象限，在两个轴上都偏心地切分。
    private static void cornerQuad(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float splitX = (float) (width * (0.6 + TemperaRandom.hash01(ctx.seed, 9, 31) * 0.12));
        float splitY = (float) (height * (0.62 + TemperaRandom.hash01(ctx.seed, 10, 37) * 0.1));
        addPanels(ctx, new Panel[]{
            new Panel(TemperaHatch.rectPolygon(-bleed, -bleed, splitX + bleed, splitY + bleed), palette.tone2, 0, -height * 0.25f),
            new Panel(TemperaHatch.rectPolygon(splitX, -bleed, width - splitX + bleed, splitY + bleed), palette.tone4, width * 0.25f, 0),
            new Panel(TemperaHatch.rectPolygon(-bleed, splitY, splitX + bleed, height - splitY + bleed), palette.tone1, -width * 0.25f, 0),
            new Panel(TemperaHatch.rectPolygon(splitX, splitY, width - splitX + bleed, height - splitY + bleed), palette.tone3, 0, height * 0.25f),
        }, 0);
    }

    // 一摞细窄、交替的深色薄片，铺满画面的一侧。
    private static void sliverStack(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        boolean fromLeft = TemperaRandom.hash01(ctx.seed, 11, 41) > 0.5;
        float slabWidth = width * 0.46f;
        float left = fromLeft ? -bleed : width - slabWidth;
        ctx.add(TemperaShapes.drawPolygonFill(TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2), palette.tone1, 0.9f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().span(0.5f));
        int count = 9;
        float sliver = (height + bleed * 2) / count;
        for (int index = 0; index < count; index += 1) {
            if (index % 2 == 1) continue;
            ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(left, -bleed + sliver * index, slabWidth + bleed, sliver),
                palette.tone4,
                0.92f,
                ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(index * 0.02f).span(0.5f).enterDX((fromLeft ? -1 : 1) * width * 0.2f));
        }
    }

    // 注册本文件里的全部构图。
    public static void register(java.util.Map<String, TemperaCompositions.Drawer> into) {
        into.put("duo-split", TemperaSplitCompositions::duoSplit);
        into.put("quad-split", TemperaSplitCompositions::quadSplit);
        into.put("tri-column", TemperaSplitCompositions::triColumn);
        into.put("thirds-stack", TemperaSplitCompositions::thirdsStack);
        into.put("checker-quad", TemperaSplitCompositions::checkerQuad);
        into.put("corner-wedge", TemperaSplitCompositions::cornerWedge);
        into.put("diagonal-halves", TemperaSplitCompositions::diagonalHalves);
        into.put("cross-axis", TemperaSplitCompositions::crossAxis);
        into.put("offset-halves", TemperaSplitCompositions::offsetHalves);
        into.put("stair-blocks", TemperaSplitCompositions::stairBlocks);
        into.put("pillar-gap", TemperaSplitCompositions::pillarGap);
        into.put("corner-quad", TemperaSplitCompositions::cornerQuad);
        into.put("sliver-stack", TemperaSplitCompositions::sliverStack);
    }
}
