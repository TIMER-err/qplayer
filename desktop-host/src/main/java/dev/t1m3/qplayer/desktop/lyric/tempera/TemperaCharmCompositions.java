package dev.t1m3.qplayer.desktop.lyric.tempera;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// src/components/visualizer/tempera/compositions/temperaCharmCompositions.ts
// 圆润族，取法视觉小说宣传 PV 的运镜：气泡、荷叶边、心形、闪光与贴纸板。
// 其余族都用直边切画面；这一族把曲线形状*铺*在平场之上，于是字读作印在贴纸上，
// 而非跨在面板的接缝上。几何来自 temperaCurves.ts；那里只有凸形可以网点填充。
//
// 两条规则把这一族绑在 Tempera 其余部分上：墨线描边要留着（没有描边的软形会溶进场里，
// 让反转滤镜无物可切），以及这里没有任何东西随音频脉动——浮动就是共享的确定性 drift。
// 注意：本文件与 TemperaShapes / TemperaHatch / TemperaCurves / TemperaBlocks / TemperaRandom /
// TemperaPalette 同处一个扁平包，互相引用不需要 import。

public final class TemperaCharmCompositions {

    private TemperaCharmCompositions() {
    }

    /** 每张卡片都从一块平场起手；曲线形状叠在它上面。 */
    private static void addField(TemperaCompositionContext ctx, String color, float alpha) {
        final float width = ctx.width;
        final float height = ctx.height;
        final float bleed = ctx.bleed;
        ctx.add(TemperaShapes.drawPolygonFill(
            TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2, height + bleed * 2),
            color, alpha, ctx.gradient),
            TemperaBlocks.BlockOptions.of().span(0.5f));
    }

    // 气泡从字旁升起。大小跨度才是卖点：一大层托着字、一小层从上面漂过。
    private static void bubbleDrift(TemperaCompositionContext ctx) {
        final float width = ctx.width;
        final float height = ctx.height;
        final TemperaPalette.Palette palette = ctx.palette;
        final float unit = Math.min(width, height);
        addField(ctx, palette.tone1, 0.92f);
        final List<TemperaCurves.Disc> big = TemperaCurves.buildDiscField(ctx.seed, 11, width, height, 8, unit * 0.115f);
        final List<TemperaCurves.Disc> small = TemperaCurves.buildDiscField(ctx.seed, 17, width, height, 13, unit * 0.042f);
        ctx.add(TemperaShapes.drawDiscs(big, palette.tone3, 0.88f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.6f).enterDY(height * 0.16f).drift());
        ctx.add(TemperaShapes.drawRings(big, palette.ink, 2f, 0.55f),
            TemperaBlocks.BlockOptions.of().delay(0.12f).span(0.55f).enterDY(height * 0.12f));
        ctx.add(TemperaShapes.drawDiscs(small, palette.tone4, 0.7f),
            TemperaBlocks.BlockOptions.of().delay(0.18f).span(0.6f).enterDY(height * 0.22f).drift());
        if (!ctx.showDecor) return;
        // 两个主角气泡上的高光。没有它们圆盘就像平点。
        final List<TemperaCurves.Disc> highlights = new ArrayList<>();
        for (TemperaCurves.Disc disc : big.subList(0, 2)) {
            highlights.add(new TemperaCurves.Disc(disc.x - disc.radius * 0.36f, disc.y - disc.radius * 0.36f, disc.radius * 0.16f));
        }
        ctx.add(TemperaShapes.drawDiscs(highlights, palette.paper, 0.85f),
            TemperaBlocks.BlockOptions.of().delay(0.28f).drift());
    }

    // 一个叶片形徽章：宣传片把一行旁白丢进去的那种云框。
    private static void cloudWindow(TemperaCompositionContext ctx) {
        final float width = ctx.width;
        final float height = ctx.height;
        final TemperaPalette.Palette palette = ctx.palette;
        final float unit = Math.min(width, height);
        final float cx = width / 2;
        final float cy = height * 0.52f;
        final float rx = width * 0.3f;
        final float ry = height * 0.3f;
        addField(ctx, palette.tone3, 0.92f);
        final float[] cloud = TemperaCurves.lobedPolygon(cx, cy, rx, ry, 9, 0.1f);
        ctx.add(TemperaShapes.drawPolygonFill(
            TemperaCurves.lobedPolygon(cx, cy + height * 0.025f, rx * 1.05f, ry * 1.05f, 9, 0.1f),
            palette.tone4, 0.5f),
            TemperaBlocks.BlockOptions.of().delay(0.04f).span(0.6f));
        ctx.add(TemperaShapes.drawPolygonFill(cloud, palette.paper, 0.96f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.08f).span(0.55f));
        ctx.add(TemperaShapes.drawPolygonOutline(cloud, palette.ink, 2.4f, 0.85f),
            TemperaBlocks.BlockOptions.of().delay(0.12f).span(0.5f));
        if (!ctx.showDecor) return;
        // 对话气泡的尾巴，用两颗从它身上掉下来的气泡做成。
        ctx.add(TemperaShapes.drawDiscs(TemperaShapes.discs(new float[]{
            cx - rx * 1.16f, cy + ry * 0.66f, unit * 0.035f,
            cx - rx * 1.32f, cy + ry * 0.9f, unit * 0.02f,
        }), palette.paper, 0.92f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.22f).drift());
    }

    // 三个尺寸的心形列队出画；最大的那颗托着字。
    private static void heartBurst(TemperaCompositionContext ctx) {
        final float width = ctx.width;
        final float height = ctx.height;
        final TemperaPalette.Palette palette = ctx.palette;
        final int lean = TemperaRandom.hash01(ctx.seed, 3, 83) > 0.5 ? 1 : -1;
        final float cx = width * 0.5f;
        final float cy = height * 0.52f;
        final float rx = width * 0.32f;
        final float ry = height * 0.4f;
        addField(ctx, palette.tone4, 0.94f);
        final float[] main = TemperaCurves.rotatePolygon(TemperaCurves.heartPolygon(cx, cy, rx, ry, 64), cx, cy, lean * 0.06f);
        // 偏移那张先落：它读作一次套印错位，和字的回声层一个意思，不是投影。
        ctx.add(TemperaShapes.drawPolygonFill(
            TemperaCurves.rotatePolygon(TemperaCurves.heartPolygon(cx + lean * width * 0.022f, cy + height * 0.025f, rx, ry, 64), cx, cy, lean * 0.06f),
            palette.tone2, 0.6f),
            TemperaBlocks.BlockOptions.of().delay(0.04f).span(0.6f));
        ctx.add(TemperaShapes.drawPolygonFill(main, palette.paper, 0.95f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.08f).span(0.55f).enterDY(height * 0.1f));
        ctx.add(TemperaShapes.drawPolygonOutline(main, palette.ink, 3f, 0.9f),
            TemperaBlocks.BlockOptions.of().delay(0.14f).span(0.5f).enterDY(height * 0.08f));
        final float[] scales = { 0.4f, 0.24f };
        for (int index = 0; index < scales.length; index += 1) {
            final float scale = scales[index];
            // 在定位过的组里用局部坐标画：drift 绕节点自身原点缩放，所以偏心形状得先在这里居中，否则会绕圈。
            final float x = lean > 0 ? width * (0.88f + index * 0.08f) : width * (0.12f - index * 0.08f);
            final float y = height * (0.24f + index * 0.42f);
            final TemperaDraw.Graphic group = ctx.createGroup(0, x, y);
            ctx.add(TemperaShapes.drawPolygonFill(
                TemperaCurves.rotatePolygon(TemperaCurves.heartPolygon(0, 0, rx * scale, ry * scale, 64), 0, 0, -lean * 0.22f),
                palette.tone2, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.2f + index * 0.06f).span(0.5f).enterDX(lean * width * 0.12f).drift(), group);
        }
    }

    // Kirakira：两层四角闪光铺在近乎空白的场上。
    private static void sparkleField(TemperaCompositionContext ctx) {
        final float width = ctx.width;
        final float height = ctx.height;
        final TemperaPalette.Palette palette = ctx.palette;
        final float unit = Math.min(width, height);
        addField(ctx, palette.tone1, 0.88f);
        final List<TemperaCurves.Disc> seeds = TemperaCurves.buildDiscField(ctx.seed, 23, width, height, 9, unit * 0.07f);
        int s = 0;
        for (TemperaCurves.Disc disc : seeds.subList(0, 5)) {
            // 很细的腰让四角星读作闪光而非十字。在定位组里用局部坐标，drift 才能原地眨眼。
            final float[] star = TemperaCurves.starPolygon(0, 0, disc.radius, disc.radius * 0.16f, 4,
                (float) (TemperaRandom.hash01(ctx.seed, s, 29) * 0.9));
            final TemperaDraw.Graphic group = ctx.createGroup(0, disc.x, disc.y);
            ctx.add(TemperaShapes.drawPolygonFill(star, palette.tone4, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.06f + s * 0.05f).span(0.5f).drift(), group);
            s += 1;
        }
        final List<TemperaCurves.Disc> smallSeeds = new ArrayList<>();
        for (TemperaCurves.Disc disc : seeds.subList(5, seeds.size())) {
            smallSeeds.add(new TemperaCurves.Disc(disc.x, disc.y, disc.radius * 0.22f));
        }
        ctx.add(TemperaShapes.drawDiscs(smallSeeds, palette.tone4, 0.7f),
            TemperaBlocks.BlockOptions.of().delay(0.24f).span(0.55f).drift());
        ctx.add(TemperaShapes.drawLines(TemperaShapes.line(width * 0.08f, height * 0.74f, width * 0.92f, height * 0.72f),
            palette.tone4, 1.2f, 0.5f),
            TemperaBlocks.BlockOptions.of().delay(0.3f).span(0.5f).enterDX(-width * 0.12f));
    }

    // 从出画枢轴甩出的花瓣，加上它们来自的玫瑰饰。
    private static void petalArc(TemperaCompositionContext ctx) {
        final float width = ctx.width;
        final float height = ctx.height;
        final TemperaPalette.Palette palette = ctx.palette;
        final boolean fromLeft = TemperaRandom.hash01(ctx.seed, 5, 89) > 0.5;
        final float hubX = fromLeft ? -width * 0.1f : width * 1.1f;
        final float hubY = height * 0.12f;
        final float rx = width * 0.26f;
        final float ry = height * 0.085f;
        addField(ctx, palette.tone2, 0.92f);
        for (int index = 0; index < 5; index += 1) {
            // 每片花瓣都贴着枢轴生成，再绕枢轴甩出去，于是无论种子挑了哪个角，扇形都还是扇形。
            final float[] base = TemperaCurves.ellipsePolygon(hubX + (fromLeft ? rx : -rx), hubY, rx, ry, 28);
            final float[] petal = TemperaCurves.rotatePolygon(base, hubX, hubY, (fromLeft ? 1 : -1) * (0.18f + index * 0.3f));
            ctx.add(TemperaShapes.drawPolygonFill(petal, index % 2 == 0 ? palette.tone3 : palette.tone1, 0.9f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(index * 0.05f).span(0.55f).enterDY(-height * 0.1f));
            ctx.add(TemperaShapes.drawPolygonOutline(petal, palette.ink, 1.6f, 0.5f),
                TemperaBlocks.BlockOptions.of().delay(0.06f + index * 0.05f).span(0.5f));
        }
        final TemperaDraw.Graphic hub = ctx.createGroup(0, hubX, hubY);
        ctx.add(TemperaShapes.drawPolygonFill(
            TemperaCurves.lobedPolygon(0, 0, width * 0.11f, width * 0.11f, 6, 0.34f),
            palette.tone4, 0.9f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.28f).span(0.5f).drift(), hub);
        if (!ctx.showDecor) return;
        // 已经落下的花瓣，在对面的那半边：没有它们扇形会留白那一半。
        final List<TemperaCurves.Disc> petalsFell = new ArrayList<>();
        for (int index = 0; index < 3; index += 1) {
            petalsFell.add(new TemperaCurves.Disc(
                fromLeft ? width * (0.78f + index * 0.07f) : width * (0.22f - index * 0.07f),
                height * (0.7f + index * 0.1f),
                height * (0.03f - index * 0.006f)));
        }
        ctx.add(TemperaShapes.drawDiscs(petalsFell, palette.tone4, 0.75f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.32f).span(0.5f).drift());
    }

    // 两条荷叶边从边缘合拢；歌词落在它们之间明亮的槽里。
    private static void scallopBand(TemperaCompositionContext ctx) {
        final float width = ctx.width;
        final float height = ctx.height;
        final TemperaPalette.Palette palette = ctx.palette;
        final float bleed = ctx.bleed;
        addField(ctx, palette.tone1, 0.9f);
        final float[] top = TemperaCurves.scallopBandPolygon(-bleed, width + bleed, -bleed, height * 0.28f, 9, 10);
        final float[] bottom = TemperaCurves.scallopBandPolygon(-bleed, width + bleed, height + bleed, height * 0.72f, 9, 10);
        ctx.add(TemperaShapes.drawPolygonFill(top, palette.tone3, 0.92f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.04f).span(0.55f).enterDY(-height * 0.18f));
        ctx.add(TemperaShapes.drawPolygonFill(bottom, palette.tone4, 0.92f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.1f).span(0.55f).enterDY(height * 0.18f));
        ctx.add(TemperaShapes.drawPolygonOutline(top, palette.ink, 2f, 0.75f),
            TemperaBlocks.BlockOptions.of().delay(0.14f).span(0.5f).enterDY(-height * 0.12f));
        ctx.add(TemperaShapes.drawPolygonOutline(bottom, palette.ink, 2f, 0.75f),
            TemperaBlocks.BlockOptions.of().delay(0.18f).span(0.5f).enterDY(height * 0.12f));
        if (!ctx.showDecor) return;
        // 缝在两个凸起相接处的小珠，于是它们读作缝在荷叶边上，而非散落在上面——所以用凸起间距而非圆整比例。
        final float pitch = (width + bleed * 2) / 9;
        final List<TemperaCurves.Disc> beads = new ArrayList<>();
        for (int index = 0; index < 5; index += 1) {
            beads.add(new TemperaCurves.Disc(-bleed + pitch * (index + 2), height * 0.28f, Math.min(width, height) * 0.018f));
        }
        ctx.add(TemperaShapes.drawDiscs(beads, palette.ink, 0.7f),
            TemperaBlocks.BlockOptions.of().delay(0.26f).drift());
    }

    // 一条横越画面的缎带，上面钉一个结：宣传片的题词横幅。
    private static void ribbonLoop(TemperaCompositionContext ctx) {
        final float width = ctx.width;
        final float height = ctx.height;
        final TemperaPalette.Palette palette = ctx.palette;
        final float bleed = ctx.bleed;
        final float sag = height * (0.06f + (float) TemperaRandom.hash01(ctx.seed, 7, 97) * 0.05f)
            * (TemperaRandom.hash01(ctx.seed, 9, 97) > 0.5 ? 1 : -1);
        addField(ctx, palette.tone2, 0.92f);
        final float[] band = TemperaCurves.arcRibbonPolygon(-bleed, width + bleed, height * 0.5f, height * 0.3f, sag, 28);
        final float[] echo = TemperaCurves.arcRibbonPolygon(-bleed, width + bleed, height * 0.68f, height * 0.06f, -sag, 28);
        ctx.add(TemperaShapes.drawPolygonFill(echo, palette.tone4, 0.7f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.04f).span(0.6f).enterDX(-width * 0.16f));
        ctx.add(TemperaShapes.drawPolygonFill(band, palette.paper, 0.95f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.08f).span(0.55f).enterDX(width * 0.18f));
        ctx.add(TemperaShapes.drawPolygonOutline(band, palette.ink, 2.6f, 0.85f),
            TemperaBlocks.BlockOptions.of().delay(0.14f).span(0.5f).enterDX(width * 0.14f));
        // 缎带上的蝴蝶结。它故意落在字下方：字穿过它，正是反转滤镜把它变成双色词的地方。
        final float knotX = width * (TemperaRandom.hash01(ctx.seed, 11, 97) > 0.5 ? 0.24f : 0.76f);
        final float knotY = height * 0.5f + (float) (Math.sin((knotX + bleed) / (width + bleed * 2) * Math.PI) * sag);
        final TemperaDraw.Graphic bow = ctx.createGroup(0, knotX, knotY);
        final float[] sides = { -1f, 1f };
        for (int index = 0; index < sides.length; index += 1) {
            final float side = sides[index];
            final float[] loop = TemperaCurves.rotatePolygon(
                TemperaCurves.ellipsePolygon(side * width * 0.055f, 0, width * 0.05f, height * 0.07f, 26),
                0, 0, side * 0.3f);
            ctx.add(TemperaShapes.drawPolygonFill(loop, palette.tone4, 0.92f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.2f + index * 0.04f).span(0.5f).enterDX(side * width * 0.06f), bow);
            ctx.add(TemperaShapes.drawPolygonOutline(loop, palette.ink, 2f, 0.8f),
                TemperaBlocks.BlockOptions.of().delay(0.24f + index * 0.04f).span(0.5f), bow);
        }
        final float[] knot = TemperaCurves.roundedRectPolygon(-width * 0.018f, -height * 0.035f, width * 0.036f, height * 0.07f, height * 0.02f, 6);
        ctx.add(TemperaShapes.drawPolygonFill(knot, palette.tone4, 0.95f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.28f).span(0.5f), bow);
        ctx.add(TemperaShapes.drawPolygonOutline(knot, palette.ink, 2f, 0.85f),
            TemperaBlocks.BlockOptions.of().delay(0.3f).span(0.5f), bow);
    }

    // 贴纸板：圆角面板、双规线、四颗钉。这一族里最安静的一张。
    private static void roundPlate(TemperaCompositionContext ctx) {
        final float width = ctx.width;
        final float height = ctx.height;
        final TemperaPalette.Palette palette = ctx.palette;
        final float unit = Math.min(width, height);
        addField(ctx, palette.tone3, 0.92f);
        final float[] plate = TemperaCurves.roundedRectPolygon(width * 0.16f, height * 0.24f, width * 0.68f, height * 0.52f, unit * 0.11f, 6);
        final float[] inner = TemperaCurves.roundedRectPolygon(width * 0.19f, height * 0.29f, width * 0.62f, height * 0.42f, unit * 0.08f, 6);
        ctx.add(TemperaShapes.drawPolygonFill(plate, palette.paper, 0.95f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.55f).enterDY(height * 0.06f));
        // 圆角矩形是凸的，所以网点裁剪器直接填它——叶片板就只能免了。
        ctx.add(TemperaShapes.drawHatchFill(inner, TemperaHatch.buildHatchSpec(ctx.seed, 101, 1.3f), palette.tone2, 0.35f),
            TemperaBlocks.BlockOptions.of().delay(0.1f).span(0.6f).grow());
        ctx.add(TemperaShapes.drawPolygonOutline(plate, palette.ink, 3f, 0.9f),
            TemperaBlocks.BlockOptions.of().delay(0.12f).span(0.5f));
        ctx.add(TemperaShapes.drawPolygonOutline(inner, palette.tone4, 1.2f, 0.55f),
            TemperaBlocks.BlockOptions.of().delay(0.16f).span(0.5f));
        if (!ctx.showDecor) return;
        ctx.add(TemperaShapes.drawDiscs(TemperaShapes.discs(new float[]{
            width * 0.22f, height * 0.3f, unit * 0.016f,
            width * 0.78f, height * 0.3f, unit * 0.016f,
            width * 0.22f, height * 0.7f, unit * 0.016f,
            width * 0.78f, height * 0.7f, unit * 0.016f,
        }), palette.ink, 0.75f),
            TemperaBlocks.BlockOptions.of().delay(0.22f).span(0.5f));
    }

    // 一个柔光晕：从偏心枢轴张开的宽径向楔形，再叠圆盘与环。
    private static void haloBurst(TemperaCompositionContext ctx) {
        final float width = ctx.width;
        final float height = ctx.height;
        final TemperaPalette.Palette palette = ctx.palette;
        final float unit = Math.min(width, height);
        final float cx = width * (0.44f + (float) (TemperaRandom.hash01(ctx.seed, 13, 103) * 0.12));
        final float cy = height * 0.5f;
        final float reach = (float) (Math.hypot(width, height) * 0.7);
        final int wedges = 14;
        addField(ctx, palette.tone1, 0.92f);
        // 只用间隔的那一半楔形：空隙才让光晕柔和而非眩晕。
        for (int index = 0; index < wedges; index += 2) {
            final double angle = (index / (double) wedges) * Math.PI * 2 + TemperaRandom.hash01(ctx.seed, index, 107) * 0.09;
            final double spread = 0.11;
            final float[] wedge = new float[]{
                cx, cy,
                (float) (cx + Math.cos(angle - spread) * reach),
                (float) (cy + Math.sin(angle - spread) * reach),
                (float) (cx + Math.cos(angle + spread) * reach),
                (float) (cy + Math.sin(angle + spread) * reach),
            };
            ctx.add(TemperaShapes.drawPolygonFill(wedge, palette.tone3, 0.55f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.04f + index * 0.012f).span(0.6f));
        }
        ctx.add(TemperaShapes.drawPolygonFill(TemperaCurves.ellipsePolygon(cx, cy, unit * 0.34f, unit * 0.34f, 48), palette.paper, 0.94f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.14f).span(0.55f));
        // 环用圆盘工厂而非描边多边形：它把节点绕环心旋转，于是光晕原地呼吸而非绕画面原点打转。
        final float[][] rings = { { 0.34f, 3f }, { 0.44f, 1.4f } };
        for (int index = 0; index < rings.length; index += 1) {
            final float scale = rings[index][0];
            final float stroke = rings[index][1];
            ctx.add(TemperaShapes.drawRings(TemperaShapes.discs(new float[]{ cx, cy, unit * scale }),
                palette.ink, stroke, 0.75f),
                TemperaBlocks.BlockOptions.of().delay(0.2f + index * 0.06f).span(0.5f).drift());
        }
    }

    // 注册本文件里的全部构图。
    public static void register(Map<String, TemperaCompositions.Drawer> into) {
        into.put("bubble-drift", TemperaCharmCompositions::bubbleDrift);
        into.put("cloud-window", TemperaCharmCompositions::cloudWindow);
        into.put("heart-burst", TemperaCharmCompositions::heartBurst);
        into.put("sparkle-field", TemperaCharmCompositions::sparkleField);
        into.put("petal-arc", TemperaCharmCompositions::petalArc);
        into.put("scallop-band", TemperaCharmCompositions::scallopBand);
        into.put("ribbon-loop", TemperaCharmCompositions::ribbonLoop);
        into.put("round-plate", TemperaCharmCompositions::roundPlate);
        into.put("halo-burst", TemperaCharmCompositions::haloBurst);
    }
}
