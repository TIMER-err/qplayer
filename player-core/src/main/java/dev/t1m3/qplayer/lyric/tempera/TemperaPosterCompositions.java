package dev.t1m3.qplayer.lyric.tempera;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// src/components/visualizer/tempera/compositions/temperaPosterCompositions.ts
// 大幅倾斜的色块。这些都是喧闹的构图：一个占主导的实心形状，字得跟它较劲，
// 而这恰恰是让反相滤镜现形的原因。

/**
 * 海报族构图：倾斜的大色块、菱形堆叠、斜切带、楔形、三角块等。
 * 1:1 移植自 folia-major {@code tempera/compositions/temperaPosterCompositions.ts}。
 */
public final class TemperaPosterCompositions {
    private TemperaPosterCompositions() {
    }

    // 一个大块倾斜的实心：字得跟它较劲，反相滤镜才看得出来。
    private static void posterPanel(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        // 倾斜子组：子节点坐标保持局部坐标，原样挂进去。
        TemperaDraw.Graphic poster = ctx.createGroup(-0.06f, width / 2, height / 2);
        float[] solid = TemperaHatch.diamondPolygon(-width * 0.12f, 0, width * 0.42f, height * 0.66f);
        float[] hatched = TemperaHatch.diamondPolygon(width * 0.2f, -height * 0.06f, width * 0.3f, height * 0.48f);
        ctx.add(TemperaShapes.drawPolygonFill(solid, palette.ink, 0.92f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().enterDX(-width * 0.6f).span(0.55f), poster);
        ctx.add(TemperaShapes.drawHatchFill(hatched, TemperaHatch.buildHatchSpec(ctx.seed, 41),
                palette.tone4, 0.7f),
                TemperaBlocks.BlockOptions.of().delay(0.08f).grow().span(0.55f), poster);
        ctx.add(TemperaShapes.drawPolygonOutline(hatched, palette.ink, 2, 0.8f),
                TemperaBlocks.BlockOptions.of().delay(0.12f).span(0.55f), poster);
        ctx.add(TemperaShapes.drawPolyline(
                TemperaHatch.buildWavyPath(ctx.seed, 43, -width * 0.6f, width * 0.6f, height * 0.42f, height * 0.02f, 24),
                palette.tone4, 2, 0.7f),
                TemperaBlocks.BlockOptions.of().delay(0.2f).enterDY(height * 0.1f), poster);
    }

    // 三个实心从画框边上按递减尺寸列队而下；字骑在最大的那块上。
    private static void diamondStack(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone1, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        // 三块依次递减的实心菱形，字落在最大的那块上。
        String[] tones = {palette.ink, palette.tone4, palette.tone3};
        for (int index = 0; index < tones.length; index += 1) {
            String tone = tones[index];
            float scale = 1f - index * 0.28f;
            float cx = width * (0.34f + index * 0.24f);
            float cy = height * (0.52f - index * 0.14f);
            ctx.add(TemperaShapes.drawPolygonFill(
                    TemperaHatch.diamondPolygon(cx, cy, width * 0.3f * scale, height * 0.46f * scale),
                    tone, 0.93f, ctx.gradient),
                    TemperaBlocks.BlockOptions.of().delay(index * 0.07f).span(0.55f)
                            .enterDX(width * 0.25f).enterDY(-height * 0.12f));
        }
    }

    // 一条斜跨画框的宽带，反向的衬底网点从另一方向跑。
    private static void slashPoster(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float lean = height * 0.34f;
        float[] band = {
                -bleed, height * 0.24f + lean,
                width + bleed, height * 0.24f - lean,
                width + bleed, height * 0.72f - lean,
                -bleed, height * 0.72f + lean,
        };
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone1, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        ctx.add(TemperaShapes.drawPolygonFill(band, palette.tone4, 0.94f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.55f).enterDX(-width * 0.4f));
        ctx.add(TemperaShapes.drawHatchFill(band,
                TemperaHatch.buildHatchSpec(ctx.seed, 67).withAngle((float) (Math.PI / 3)),
                palette.paper, 0.3f),
                TemperaBlocks.BlockOptions.of().delay(0.1f).span(0.55f).grow());
        ctx.add(TemperaShapes.drawPolygonOutline(band, palette.ink, 2.2f, 0.8f),
                TemperaBlocks.BlockOptions.of().delay(0.14f).span(0.5f));
    }

    // 一个斜指画框的雪佛龙，呼应参考图的屋脊母题。
    private static void arrowWedge(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float apex = (float) (height * (0.24 + TemperaRandom.hash01(ctx.seed, 3, 71) * 0.14));
        float thickness = height * 0.2f;
        // 注意：顶点两侧各有一段跑到画框外，所以数组里出现了重复的角点。
        float[] chevron = {
                -bleed, height + bleed,
                width * 0.5f, apex,
                width + bleed, height + bleed,
                width + bleed, height + bleed,
                width * 0.5f, apex + thickness,
                -bleed, height + bleed,
        };
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone1, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        ctx.add(TemperaShapes.drawPolygonFill(
                new float[]{-bleed, height + bleed, width * 0.5f, apex, width + bleed, height + bleed},
                palette.tone4, 0.94f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.55f).enterDY(height * 0.3f));
        ctx.add(TemperaShapes.drawHatchFill(chevron,
                TemperaHatch.buildHatchSpec(ctx.seed, 73).withAngle((float) (-Math.PI / 4)),
                palette.paper, 0.35f),
                TemperaBlocks.BlockOptions.of().delay(0.1f).span(0.55f).grow());
        ctx.add(TemperaShapes.drawPolyline(
                new float[]{-bleed, height * 0.9f, width * 0.5f, apex - thickness * 0.4f, width + bleed, height * 0.9f},
                palette.ink, 2, 0.7f),
                TemperaBlocks.BlockOptions.of().delay(0.16f).span(0.5f).enterDY(-height * 0.1f));
    }

    // 一块从一侧边缘顶进来的色块，把字留在旁边露出的纸上。
    private static void edgeBleed(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        boolean fromLeft = TemperaRandom.hash01(ctx.seed, 4, 79) > 0.5;
        float cover = width * 0.42f;
        float[] mass = fromLeft
                ? TemperaHatch.rectPolygon(-bleed, -bleed, cover + bleed, height + bleed * 2)
                : TemperaHatch.rectPolygon(width - cover, -bleed, cover + bleed, height + bleed * 2);
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone1, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        ctx.add(TemperaShapes.drawPolygonFill(mass, palette.ink, 0.93f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.55f)
                        .enterDX((fromLeft ? -1 : 1) * width * 0.4f));
        ctx.add(TemperaShapes.drawHatchFill(mass, TemperaHatch.buildHatchSpec(ctx.seed, 83),
                palette.paper, 0.22f),
                TemperaBlocks.BlockOptions.of().delay(0.12f).span(0.55f).grow());
    }

    // 一个锚在一边、几乎占满画框的三角形。
    private static void triangleMass(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        boolean fromLeft = TemperaRandom.hash01(ctx.seed, 91, 89) > 0.5;
        float apexX = fromLeft ? width * 1.02f : -width * 0.02f;
        float[] triangle = {
                fromLeft ? -bleed : width + bleed, -bleed,
                fromLeft ? -bleed : width + bleed, height + bleed,
                apexX, (float) (height * (0.3 + TemperaRandom.hash01(ctx.seed, 93, 97) * 0.3)),
        };
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone1, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        ctx.add(TemperaShapes.drawPolygonFill(triangle, palette.tone4, 0.94f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.55f)
                        .enterDX((fromLeft ? -1 : 1) * width * 0.35f));
        ctx.add(TemperaShapes.drawHatchFill(triangle, TemperaHatch.buildHatchSpec(ctx.seed, 101),
                palette.paper, 0.24f),
                TemperaBlocks.BlockOptions.of().delay(0.12f).span(0.55f).grow());
    }

    // 两条倾斜的缎带交叉；重叠处调子叠成双倍。
    private static void ribbonCross(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float half = height * 0.14f;
        float lean = height * 0.3f;
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone1, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        ctx.add(TemperaShapes.drawPolygonFill(ribbon(1f, width, height, bleed, lean, half),
                palette.tone3, 0.8f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.55f).enterDX(-width * 0.3f));
        ctx.add(TemperaShapes.drawPolygonFill(ribbon(-1f, width, height, bleed, lean, half),
                palette.tone4, 0.8f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.11f).span(0.55f).enterDX(width * 0.3f));
    }

    // 一条倾斜缎带的多边形；sign 决定它往哪边斜。
    private static float[] ribbon(float sign, float width, float height, float bleed, float lean, float half) {
        return new float[]{
                -bleed, height / 2 + sign * lean - half,
                width + bleed, height / 2 - sign * lean - half,
                width + bleed, height / 2 - sign * lean + half,
                -bleed, height / 2 + sign * lean + half,
        };
    }

    // 一个大到只有肩线还在画框里的大圆盘。
    private static void halfDisc(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        boolean fromRight = TemperaRandom.hash01(ctx.seed, 103, 107) > 0.5;
        float radius = (float) Math.hypot(width, height) * 0.62f;
        float[] disc = TemperaHatch.circlePolygon(fromRight ? width + radius * 0.55f : -radius * 0.55f,
                height * 0.5f, radius, 64);
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone1, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        ctx.add(TemperaShapes.drawPolygonFill(disc, palette.tone4, 0.94f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.55f)
                        .enterDX((fromRight ? 1 : -1) * width * 0.3f));
        ctx.add(TemperaShapes.drawPolygonOutline(disc, palette.ink, 2, 0.6f),
                TemperaBlocks.BlockOptions.of().delay(0.12f).span(0.5f));
    }

    // 三张错位旋转的板，像随手丢在桌上的纸。
    private static void stackedSlabs(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone1, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        String[] tones = {palette.tone2, palette.tone3, palette.tone4};
        for (int index = 0; index < tones.length; index += 1) {
            String tone = tones[index];
            // 每张板各自挂进一个倾斜子组，子坐标保持局部。
            TemperaDraw.Graphic group = ctx.createGroup(-0.09f + index * 0.08f, width / 2, height / 2);
            float[] slab = TemperaHatch.rectPolygon(
                    -width * (0.42f - index * 0.05f), -height * (0.3f - index * 0.03f),
                    width * (0.84f - index * 0.1f), height * (0.6f - index * 0.06f));
            ctx.add(TemperaShapes.drawPolygonFill(slab, tone, 0.93f, ctx.gradient),
                    TemperaBlocks.BlockOptions.of().delay(index * 0.07f).span(0.55f)
                            .enterDX(width * 0.2f).enterDY(height * 0.12f), group);
        }
    }

    // 两片从对边咬进来的楔形，中间留一个沙漏形的空白。
    private static void wedgePair(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float waist = (float) (width * (0.2 + TemperaRandom.hash01(ctx.seed, 109, 113) * 0.1));
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
                palette.tone1, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
        ctx.add(TemperaShapes.drawPolygonFill(
                new float[]{-bleed, -bleed, (width - waist) / 2, height / 2, -bleed, height + bleed},
                palette.tone4, 0.94f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.55f).enterDX(-width * 0.25f));
        ctx.add(TemperaShapes.drawPolygonFill(
                new float[]{width + bleed, -bleed, (width + waist) / 2, height / 2, width + bleed, height + bleed},
                palette.tone4, 0.94f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.11f).span(0.55f).enterDX(width * 0.25f));
    }

    public static void register(Map<String, TemperaCompositions.Drawer> into) {
        into.put("poster-panel", TemperaPosterCompositions::posterPanel);
        into.put("diamond-stack", TemperaPosterCompositions::diamondStack);
        into.put("slash-poster", TemperaPosterCompositions::slashPoster);
        into.put("arrow-wedge", TemperaPosterCompositions::arrowWedge);
        into.put("edge-bleed", TemperaPosterCompositions::edgeBleed);
        into.put("triangle-mass", TemperaPosterCompositions::triangleMass);
        into.put("ribbon-cross", TemperaPosterCompositions::ribbonCross);
        into.put("half-disc", TemperaPosterCompositions::halfDisc);
        into.put("stacked-slabs", TemperaPosterCompositions::stackedSlabs);
        into.put("wedge-pair", TemperaPosterCompositions::wedgePair);
    }
}
