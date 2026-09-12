package dev.t1m3.qplayer.desktop.lyric.tempera;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 巨构族构图，1:1 移植自 folia-major
 * {@code tempera/compositions/temperaMonolithCompositions.ts}。
 *
 * <p>粗野主义：一块巨大、哑光、被画框裁掉的实体，字坐在它的轮廓之上。整个家族都建立在一个动作上——
 * 一块尺度大到画框装不下的实体——而 {@link TemperaMonolithKit} 里那些细标记正是给它这种尺度的。
 *
 * <p>字被放在轮廓<b>横跨</b>的位置，只要背后的场是实的；因为一半在实体上一半在外的字形，正是反色
 * 滤镜最好发挥的地方。当某个构图把地面朝背景敞开时，区域就挪到实体上；{@link TemperaCutout} 解释了
 * 它为什么绝不能同时跨在开口上。
 */
public final class TemperaMonolithCompositions {

    private TemperaMonolithCompositions() {
    }

    // 一块硕大的尖顶实体压住画框，字立在它剪影上。整族都建在「实体大到画框装不下」这一个动作上。
    private static void apexMass(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        // 顶点在水平方向带一点随机抖动，免得每次都落在同一个位置。
        float apexX = width * (0.46f + (float) (TemperaRandom.hash01(ctx.seed, 3, 227) * 0.12));
        float apexY = height * 0.38f;
        TemperaMonolithKit.addGround(ctx, palette.tone1);
        float[] mass = {-bleed, height + bleed, apexX, apexY, width + bleed, height + bleed};
        TemperaMonolithKit.addMass(ctx, mass, palette.tone3,
                TemperaMonolithKit.MassOptions.of().enterDY(height * 0.1f));
        // 只在一侧排线。两侧都排会读成纹理，而不是一块被光照亮的面。
        TemperaMonolithKit.addFaceRuling(ctx,
                new float[]{apexX, apexY, width + bleed, height + bleed, apexX + width * 0.24f, height + bleed}, 229);
        TemperaMonolithKit.addSurveyLines(ctx, 3);
        if (!ctx.showDecor) return;
        TemperaMonolithKit.addCornerTicks(ctx);
        TemperaMonolithKit.addWireTrace(ctx, width * 0.12f, height * 0.84f, Math.min(width, height) * 0.09f);
    }

    // 一层层台阶状的板岩山。每一道都是独立节点，于是整堆是从下往上盖起来的。
    private static void ziggurat(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        TemperaMonolithKit.addGround(ctx, palette.tone1);
        String[] tones = {palette.tone4, palette.tone3, palette.tone3, palette.tone2};
        for (int course = 0; course < 4; course++) {
            float half = width * (0.44f - course * 0.09f);
            float top = height * (0.9f - course * 0.13f) - height * 0.13f;
            TemperaMonolithKit.addMass(ctx,
                    TemperaHatch.rectPolygon(width / 2f - half, top, half * 2f, height * 0.13f + bleed),
                    tones[course],
                    TemperaMonolithKit.MassOptions.of()
                            .delay(0.04f + course * 0.05f)
                            .enterDY(height * 0.08f)
                            .edgeWidth(2f));
        }
        TemperaMonolithKit.addSurveyLines(ctx, 2);
        if (!ctx.showDecor) return;
        TemperaMonolithKit.addCornerTicks(ctx);
    }

    // 一堵几乎填满画框的墙，沿其前缘有一条深接缝。
    private static void slabWall(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float edge = width * 0.36f;
        TemperaMonolithKit.addGround(ctx, palette.tone1);
        TemperaMonolithKit.addMass(ctx,
                TemperaHatch.rectPolygon(edge, -bleed, width - edge + bleed, height + bleed * 2f),
                palette.tone3, TemperaMonolithKit.MassOptions.of().edge(false));
        List<TemperaHatch.Line> line = new ArrayList<>();
        line.add(new TemperaHatch.Line(edge, -bleed, edge, height + bleed));
        ctx.add(TemperaShapes.drawLines(line, palette.ink, 3.5f, 0.9f),
                TemperaBlocks.BlockOptions.of().delay(0.1f).span(0.55f).enterDX(width * 0.06f));
        TemperaMonolithKit.addFaceRuling(ctx,
                TemperaHatch.rectPolygon(width * 0.62f, height * 0.1f, width * 0.3f, height * 0.8f), 233);
        TemperaMonolithKit.addSurveyLines(ctx, 3);
        if (!ctx.showDecor) return;
        TemperaMonolithKit.addWireTrace(ctx, width * 0.16f, height * 0.24f, Math.min(width, height) * 0.08f);
    }

    // 一条抛向空中的梁，靠一根短桩撑着它，这才有悬臂的样子。
    private static void cantilever(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        TemperaMonolithKit.addGround(ctx, palette.tone1);
        TemperaMonolithKit.addMass(ctx,
                TemperaHatch.rectPolygon(0.16f * width, height * 0.5f, width * 0.1f, height * 0.5f + bleed),
                palette.tone4,
                TemperaMonolithKit.MassOptions.of().delay(0.02f).enterDY(height * 0.1f).edge(false));
        TemperaMonolithKit.addMass(ctx,
                TemperaHatch.rectPolygon(-bleed, height * 0.34f, width * 0.78f + bleed, height * 0.16f),
                palette.tone3,
                TemperaMonolithKit.MassOptions.of().delay(0.08f).enterDX(-width * 0.14f));
        TemperaMonolithKit.addFaceRuling(ctx,
                TemperaHatch.rectPolygon(width * 0.3f, height * 0.36f, width * 0.44f, height * 0.12f),
                239, palette.paper, 0.35f);
        TemperaMonolithKit.addSurveyLines(ctx, 2);
        if (!ctx.showDecor) return;
        TemperaMonolithKit.addCornerTicks(ctx);
    }

    // 两根桥墩与它们托着的楣石。两墩之间是直接从地面挖穿的，所以那道缝是天空而不是纸面。
    private static void pylonPair(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float[] bay = TemperaHatch.rectPolygon(width * 0.26f, height * 0.3f, width * 0.48f, height * 0.7f + bleed);
        TemperaCutout.addCutField(ctx, palette.tone1, List.of(bay), 0.94f);
        TemperaCutout.addHoleLip(ctx, bay, 2f, 0.6f, 0.12f);
        float[] xs = {0.1f, 0.74f};
        for (int index = 0; index < xs.length; index++) {
            float x = xs[index];
            TemperaMonolithKit.addMass(ctx,
                    TemperaHatch.rectPolygon(width * x, height * 0.28f, width * 0.16f, height * 0.72f + bleed),
                    palette.tone4,
                    TemperaMonolithKit.MassOptions.of()
                            .delay(0.04f + index * 0.05f)
                            .enterDY(height * 0.08f)
                            .edgeWidth(2f));
        }
        TemperaMonolithKit.addMass(ctx,
                TemperaHatch.rectPolygon(width * 0.04f, height * 0.14f, width * 0.92f, height * 0.16f),
                palette.tone3,
                TemperaMonolithKit.MassOptions.of().delay(0.14f).enterDY(-height * 0.1f));
        TemperaMonolithKit.addSurveyLines(ctx, 2);
    }

    // 带一条观察缝的碉堡。缝是从地面里、也从实体里一起挖出来的：只在实体上挖洞只会露出背后的纸面。
    private static void bunkerSlit(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float[] slit = TemperaHatch.rectPolygon(-bleed, height * 0.62f, width + bleed * 2f, height * 0.06f);
        TemperaCutout.addCutField(ctx, palette.tone1, List.of(slit), 0.94f);
        // 实体分两道进来，各自停在缝的两边。画成一整块再挖缝也行，但两半从相反方向进入，
        // 才让这道开口读起来像被撬开。
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, height * 0.34f, width + bleed * 2f, height * 0.28f),
                palette.tone4, 0.95f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.6f).enterDY(-height * 0.08f));
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, height * 0.68f, width + bleed * 2f, height * 0.32f + bleed),
                palette.tone4, 0.95f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.08f).span(0.6f).enterDY(height * 0.1f));
        List<TemperaHatch.Line> line = new ArrayList<>();
        line.add(new TemperaHatch.Line(-bleed, height * 0.34f, width + bleed, height * 0.34f));
        ctx.add(TemperaShapes.drawLines(line, palette.ink, 3f, 0.85f),
                TemperaBlocks.BlockOptions.of().delay(0.14f).span(0.5f));
        TemperaMonolithKit.addFaceRuling(ctx,
                TemperaHatch.rectPolygon(width * 0.08f, height * 0.72f, width * 0.36f, height * 0.2f),
                241, palette.paper, 0.3f);
        TemperaMonolithKit.addSurveyLines(ctx, 2);
    }

    // 三层基座，每一层都比下一层往里收一点。
    private static void plinthStack(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        TemperaMonolithKit.addGround(ctx, palette.tone1);
        TemperaMonolithKit.addMass(ctx,
                TemperaHatch.rectPolygon(width * 0.06f, height * 0.72f, width * 0.88f, height * 0.28f + bleed),
                palette.tone4, TemperaMonolithKit.MassOptions.of().edgeWidth(2f));
        TemperaMonolithKit.addMass(ctx,
                TemperaHatch.rectPolygon(width * 0.16f, height * 0.52f, width * 0.68f, height * 0.2f),
                palette.tone3, TemperaMonolithKit.MassOptions.of().delay(0.08f).edgeWidth(2f));
        TemperaMonolithKit.addMass(ctx,
                TemperaHatch.rectPolygon(width * 0.28f, height * 0.34f, width * 0.44f, height * 0.18f),
                palette.tone2, TemperaMonolithKit.MassOptions.of().delay(0.14f).enterDY(height * 0.06f));
        TemperaMonolithKit.addFaceRuling(ctx,
                TemperaHatch.rectPolygon(width * 0.2f, height * 0.76f, width * 0.6f, height * 0.16f), 243);
        TemperaMonolithKit.addSurveyLines(ctx, 3);
        if (!ctx.showDecor) return;
        TemperaMonolithKit.addCornerTicks(ctx);
    }

    // 一排扶壁。地面被一条带整体敞开，鳍片嵌进这条带里，于是它们之间的天空自动露出来，
    // 不必每道缝都单独去挖。
    private static void buttressRun(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float[] band = TemperaHatch.rectPolygon(-bleed, height * 0.42f, width + bleed * 2f, height * 0.58f + bleed);
        TemperaCutout.addCutField(ctx, palette.tone1, List.of(band), 0.94f);
        for (int index = 0; index < 5; index++) {
            float left = width * (index * 0.2f + 0.01f);
            float[] poly = {left, height + bleed,
                    left + width * 0.09f, height * 0.42f,
                    left + width * 0.17f, height + bleed};
            TemperaMonolithKit.addMass(ctx, poly,
                    index % 2 == 0 ? palette.tone4 : palette.tone3,
                    TemperaMonolithKit.MassOptions.of()
                            .delay(0.04f + index * 0.04f)
                            .enterDY(height * 0.12f)
                            .edgeWidth(2f));
        }
        List<TemperaHatch.Line> line = new ArrayList<>();
        line.add(new TemperaHatch.Line(-bleed, height * 0.42f, width + bleed, height * 0.42f));
        ctx.add(TemperaShapes.drawLines(line, palette.ink, 2.4f, 0.8f),
                TemperaBlocks.BlockOptions.of().delay(0.2f).span(0.5f).enterDX(-width * 0.1f));
        TemperaMonolithKit.addSurveyLines(ctx, 2);
    }

    // 一块中央被掏空了的实体。这个开口是整族里最大的，它上方留下的那条带正是字所立之处。
    private static void voidCore(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float[] core = TemperaHatch.rectPolygon(width * 0.22f, height * 0.34f, width * 0.56f, height * 0.46f);
        TemperaCutout.addCutField(ctx, palette.tone3, List.of(core), 0.95f);
        TemperaCutout.addHoleLip(ctx, core, 3.5f, 0.9f, 0.08f);
        TemperaMonolithKit.addFaceRuling(ctx,
                TemperaHatch.rectPolygon(width * 0.04f, height * 0.36f, width * 0.14f, height * 0.42f),
                247, palette.tone4, 0.45f);
        TemperaMonolithKit.addSurveyLines(ctx, 3);
        if (!ctx.showDecor) return;
        TemperaMonolithKit.addCornerTicks(ctx);
        TemperaMonolithKit.addWireTrace(ctx, width * 0.86f, height * 0.86f, Math.min(width, height) * 0.08f);
    }

    // 一块沿对角线错开、两半被推过彼此的实体。
    private static void shearBlock(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float left = width * 0.14f;
        float right = width * 0.86f;
        float top = height * 0.2f;
        float bottom = height * 0.8f;
        float cutTop = width * 0.56f;
        float cutBottom = width * 0.44f;
        TemperaMonolithKit.addGround(ctx, palette.tone1);
        float[] polyA = {left, top, cutTop, top, cutBottom, bottom, left, bottom};
        TemperaMonolithKit.addMass(ctx, polyA, palette.tone3,
                TemperaMonolithKit.MassOptions.of()
                        .enterDX(-width * 0.08f).enterDY(-height * 0.03f).edgeWidth(2f));
        float[] polyB = {
                cutTop + width * 0.03f, top + height * 0.04f,
                right, top + height * 0.04f,
                right, bottom + height * 0.04f,
                cutBottom + width * 0.03f, bottom + height * 0.04f};
        TemperaMonolithKit.addMass(ctx, polyB, palette.tone4,
                TemperaMonolithKit.MassOptions.of()
                        .delay(0.1f).enterDX(width * 0.08f).enterDY(height * 0.03f).edgeWidth(2f));
        TemperaMonolithKit.addSurveyLines(ctx, 2);
        if (!ctx.showDecor) return;
        ctx.add(TemperaShapes.drawPolygonOutline(
                TemperaHatch.rectPolygon(width * 0.1f, height * 0.16f, width * 0.8f, height * 0.68f),
                palette.paper, 1.2f, 0.35f),
                TemperaBlocks.BlockOptions.of().delay(0.28f).span(0.55f));
    }

    /** 注册本文件里的全部构图。 */
    public static void register(Map<String, TemperaCompositions.Drawer> into) {
        into.put("apex-mass", TemperaMonolithCompositions::apexMass);
        into.put("ziggurat", TemperaMonolithCompositions::ziggurat);
        into.put("slab-wall", TemperaMonolithCompositions::slabWall);
        into.put("cantilever", TemperaMonolithCompositions::cantilever);
        into.put("pylon-pair", TemperaMonolithCompositions::pylonPair);
        into.put("bunker-slit", TemperaMonolithCompositions::bunkerSlit);
        into.put("plinth-stack", TemperaMonolithCompositions::plinthStack);
        into.put("buttress-run", TemperaMonolithCompositions::buttressRun);
        into.put("void-core", TemperaMonolithCompositions::voidCore);
        into.put("shear-block", TemperaMonolithCompositions::shearBlock);
    }
}
