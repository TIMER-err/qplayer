package dev.t1m3.qplayer.desktop.lyric.tempera;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 地形族构图，1:1 移植自 folia-major
 * {@code tempera/compositions/temperaTerrainCompositions.ts}。
 *
 * <p>粗野主义那一套的另一半：实体被读成地面与结构，而非物件——山脊、跨梁、桥墩、护坡。规则与
 * {@code temperaMonolithCompositions.ts} 相同（一个主导实体、细测绘标记、全部被画框裁掉）；变化的只是
 * 它们造出的地平线是画框身在其中的，而不是画框在看的。
 */
public final class TemperaTerrainCompositions {

    private TemperaTerrainCompositions() {
    }

    // 山脊就是构图；字被放在它最高的一段上跨坐。
    private static void ridgeLine(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        // 整体抬升量带一点随机，免得每帧都压在同一个高度。
        float lift = (float) (TemperaRandom.hash01(ctx.seed, 5, 251) * 0.06);
        TemperaMonolithKit.addGround(ctx, palette.tone1);
        float[] mass = {
                -bleed, height * (0.68f + lift),
                width * 0.34f, height * (0.4f + lift),
                width * 0.62f, height * (0.58f + lift),
                width + bleed, height * (0.34f + lift),
                width + bleed, height + bleed,
                -bleed, height + bleed};
        TemperaMonolithKit.addMass(ctx, mass, palette.tone3,
                TemperaMonolithKit.MassOptions.of().enterDY(height * 0.08f));
        float[] face = {
                width * 0.34f, height * (0.42f + lift),
                width * 0.62f, height * (0.6f + lift),
                width * 0.62f, height + bleed,
                width * 0.34f, height + bleed};
        // 只在一侧排线，让受光面读起来像坡而不是纹理。
        TemperaMonolithKit.addFaceRuling(ctx, face, 253, palette.tone4, 0.45f);
        TemperaMonolithKit.addSurveyLines(ctx, 3);
        if (!ctx.showDecor) return;
        TemperaMonolithKit.addCornerTicks(ctx);
        TemperaMonolithKit.addWireTrace(ctx, width * 0.1f, height * 0.86f, Math.min(width, height) * 0.08f);
    }

    // 两块不相连的实体。它们之间的缝被切到背景，于是成了整帧里唯一亮的东西。
    private static void chasm(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float[] gap = TemperaHatch.rectPolygon(width * 0.46f, -bleed, width * 0.1f, height + bleed * 2f);
        TemperaCutout.addCutField(ctx, palette.tone1, List.of(gap), 0.94f);
        TemperaMonolithKit.addMass(ctx,
                TemperaHatch.rectPolygon(-bleed, height * 0.3f, width * 0.46f + bleed, height * 0.7f + bleed),
                palette.tone3,
                TemperaMonolithKit.MassOptions.of().enterDX(-width * 0.1f).edgeWidth(2f));
        TemperaMonolithKit.addMass(ctx,
                TemperaHatch.rectPolygon(width * 0.56f, height * 0.2f, width * 0.44f + bleed, height * 0.8f + bleed),
                palette.tone4,
                TemperaMonolithKit.MassOptions.of().delay(0.08f).enterDX(width * 0.1f).edgeWidth(2f));
        TemperaMonolithKit.addSurveyLines(ctx, 2);
    }

    // 从上方压下来的屋顶，连着它撞上的墙，以及它投下的影子。
    private static void overhang(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        TemperaMonolithKit.addGround(ctx, palette.tone1);
        TemperaMonolithKit.addMass(ctx,
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2f, height * 0.34f + bleed),
                palette.tone4,
                TemperaMonolithKit.MassOptions.of().enterDY(-height * 0.1f).edgeWidth(3f));
        TemperaMonolithKit.addMass(ctx,
                TemperaHatch.rectPolygon(width * 0.72f, height * 0.34f, width * 0.28f + bleed, height * 0.66f + bleed),
                palette.tone3,
                TemperaMonolithKit.MassOptions.of().delay(0.08f).enterDX(width * 0.1f).edge(false));
        // 影子是一层洗刷，不是实体：它绝不能单独承载字。
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, height * 0.34f, width + bleed * 2f, height * 0.14f),
                palette.tone2, 0.5f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.16f).span(0.6f));
        TemperaMonolithKit.addSurveyLines(ctx, 3);
        if (!ctx.showDecor) return;
        TemperaMonolithKit.addCornerTicks(ctx);
    }

    // 阶井：一道道台阶朝内下挖，直到一根贯穿每一道台阶的竖井。
    private static void stepWell(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float[] shaft = TemperaHatch.rectPolygon(width * 0.36f, height * 0.6f, width * 0.28f, height * 0.4f + bleed);
        TemperaCutout.addCutField(ctx, palette.tone2, List.of(shaft), 0.94f);
        // [x, y, w, tone] 四元组，逐阶下挖，竖井从每一阶里穿出去。
        float[][] courses = {
                {0.14f, 0.3f, 0.72f, 0f},
                {0.24f, 0.44f, 0.52f, 0f}};
        String[] tones = {palette.tone3, palette.tone4};
        for (int index = 0; index < courses.length; index++) {
            float x = courses[index][0];
            float y = courses[index][1];
            float w = courses[index][2];
            ctx.add(TemperaShapes.drawPolygonFillWithHoles(
                    TemperaHatch.rectPolygon(width * x, height * y, width * w, height * (1f - y) + bleed),
                    List.of(shaft),
                    tones[index],
                    0.95f,
                    ctx.gradient),
                    TemperaBlocks.BlockOptions.of().delay(0.06f + index * 0.06f).span(0.6f).enterDY(height * 0.06f));
        }
        TemperaCutout.addHoleLip(ctx, shaft, 3f, 0.85f, 0.18f);
        TemperaMonolithKit.addSurveyLines(ctx, 2);
    }

    // 站在敞开带里的桥墩，以及横穿所有桥墩、它们托着的桥面。
    private static void pierRow(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float[] water = TemperaHatch.rectPolygon(-bleed, height * 0.46f, width + bleed * 2f, height * 0.54f + bleed);
        TemperaCutout.addCutField(ctx, palette.tone1, List.of(water), 0.94f);
        float[] xs = {0.04f, 0.28f, 0.52f, 0.76f};
        for (int index = 0; index < xs.length; index++) {
            float x = xs[index];
            TemperaMonolithKit.addMass(ctx,
                    TemperaHatch.rectPolygon(width * x, height * 0.46f, width * 0.14f, height * 0.54f + bleed),
                    palette.tone4,
                    TemperaMonolithKit.MassOptions.of()
                            .delay(0.06f + index * 0.04f)
                            .enterDY(height * 0.1f)
                            .edge(false));
        }
        // 桥面必须比字所在的框更深：它是敞开带上唯一实的东西，任何从它上面垂下来的部分都会落在赤裸的背景上。
        TemperaMonolithKit.addMass(ctx,
                TemperaHatch.rectPolygon(-bleed, height * 0.32f, width + bleed * 2f, height * 0.16f),
                palette.tone3,
                TemperaMonolithKit.MassOptions.of().delay(0.02f).enterDX(-width * 0.12f).edgeWidth(2f));
        TemperaMonolithKit.addSurveyLines(ctx, 2);
    }

    // 一道护坡，带肋，两端都冲出画框。
    private static void revetment(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        TemperaMonolithKit.addGround(ctx, palette.tone1);
        float[] slope = {
                -bleed, height * 0.72f,
                width * 0.62f, height * 0.3f,
                width + bleed, height * 0.34f,
                width + bleed, height + bleed,
                -bleed, height + bleed};
        TemperaMonolithKit.addMass(ctx, slope, palette.tone3,
                TemperaMonolithKit.MassOptions.of().enterDY(height * 0.1f));
        List<TemperaHatch.Line> ribs = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            float x = width * (0.04f + index * 0.11f);
            // 肋的顶端随坡顶线下降，且不低于 0.3 这道底限。
            float top = height * (0.72f - (x / (width * 0.62f)) * 0.42f);
            ribs.add(new TemperaHatch.Line(x, Math.max(top, height * 0.3f) + height * 0.03f,
                    x - width * 0.03f, height + bleed));
        }
        ctx.add(TemperaShapes.drawLines(ribs, palette.tone4, 2.4f, 0.5f),
                TemperaBlocks.BlockOptions.of().delay(0.16f).span(0.65f));
        TemperaMonolithKit.addSurveyLines(ctx, 3);
        if (!ctx.showDecor) return;
        TemperaMonolithKit.addCornerTicks(ctx);
    }

    // 某个巨大物的一角，外加一大片空。这族能摆出尺度感最安静的方式。
    private static void towerCrop(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        TemperaMonolithKit.addGround(ctx, palette.tone1);
        TemperaMonolithKit.addMass(ctx,
                TemperaHatch.rectPolygon(width * 0.58f, height * 0.18f, width * 0.42f + bleed, height * 0.82f + bleed),
                palette.tone4,
                TemperaMonolithKit.MassOptions.of()
                        .enterDX(width * 0.08f).enterDY(height * 0.06f).edgeWidth(3f));
        TemperaMonolithKit.addFaceRuling(ctx,
                TemperaHatch.rectPolygon(width * 0.62f, height * 0.26f, width * 0.12f, height * 0.6f),
                257, palette.paper, 0.3f);
        TemperaMonolithKit.addSurveyLines(ctx, 2);
        if (!ctx.showDecor) return;
        TemperaMonolithKit.addWireTrace(ctx, width * 0.2f, height * 0.7f, Math.min(width, height) * 0.1f);
        TemperaMonolithKit.addCornerTicks(ctx);
    }

    // 一根楣石，两个支座都落在画框外：剩下的只有这一跨。
    private static void lintel(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        TemperaMonolithKit.addGround(ctx, palette.tone1);
        TemperaMonolithKit.addMass(ctx,
                TemperaHatch.rectPolygon(-bleed, height * 0.4f, width + bleed * 2f, height * 0.2f),
                palette.tone3,
                TemperaMonolithKit.MassOptions.of().enterDX(-width * 0.16f).edgeWidth(3f));
        float[] xs = {0.18f, 0.66f};
        for (int index = 0; index < xs.length; index++) {
            float x = xs[index];
            TemperaMonolithKit.addMass(ctx,
                    TemperaHatch.rectPolygon(width * x, height * 0.6f, width * 0.16f, height * 0.12f),
                    palette.tone4,
                    TemperaMonolithKit.MassOptions.of()
                            .delay(0.12f + index * 0.04f)
                            .enterDY(height * 0.06f)
                            .edge(false));
        }
        TemperaMonolithKit.addSurveyLines(ctx, 3);
        if (!ctx.showDecor) return;
        TemperaMonolithKit.addCornerTicks(ctx);
    }

    // 从画框外一点呈扇形散开的一堆碎板——同一块实体垮掉之后的样子。
    private static void rubbleFan(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float hubX = -width * 0.12f;
        float hubY = height * 1.12f;
        TemperaMonolithKit.addGround(ctx, palette.tone1);
        for (int index = 0; index < 5; index++) {
            float angle = (float) (-1.24 + index * 0.19 + TemperaRandom.hash01(ctx.seed, index, 259) * 0.05);
            float reach = (float) (Math.hypot(width, height) * (0.5 + index * 0.09));
            float[] slab = TemperaCurves.rotatePolygon(
                    TemperaHatch.rectPolygon(hubX + reach * 0.34f, hubY - height * 0.06f,
                            reach * 0.5f, height * 0.12f + index * 8f),
                    hubX, hubY, angle);
            TemperaMonolithKit.addMass(ctx, slab,
                    index % 2 == 0 ? palette.tone3 : palette.tone4,
                    TemperaMonolithKit.MassOptions.of()
                            .delay(0.04f + index * 0.05f)
                            .enterDX(-width * 0.06f)
                            .enterDY(height * 0.06f)
                            .edgeWidth(2f));
        }
        TemperaMonolithKit.addSurveyLines(ctx, 2);
    }

    // 一根日晷针与它投下的影子：两块实体，其中一块只代表一个方向。
    private static void gnomon(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        TemperaMonolithKit.addGround(ctx, palette.tone1);
        float[] shadow = {
                width * 0.42f, height * 0.86f,
                width + bleed, height * 0.6f,
                width + bleed, height * 0.78f,
                width * 0.46f, height * 0.94f};
        ctx.add(TemperaShapes.drawPolygonFill(shadow, palette.tone2, 0.6f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.1f).span(0.65f).enterDX(width * 0.1f));
        float[] gnomonMass = {
                width * 0.3f, height + bleed,
                width * 0.36f, height * 0.16f,
                width * 0.42f, height * 0.16f,
                width * 0.46f, height + bleed};
        TemperaMonolithKit.addMass(ctx, gnomonMass, palette.tone4,
                TemperaMonolithKit.MassOptions.of().delay(0.04f).enterDY(height * 0.08f).edgeWidth(2f));
        TemperaMonolithKit.addSurveyLines(ctx, 3);
        if (!ctx.showDecor) return;
        TemperaMonolithKit.addCornerTicks(ctx);
        TemperaMonolithKit.addWireTrace(ctx, width * 0.8f, height * 0.82f, Math.min(width, height) * 0.08f);
    }

    /** 注册本文件里的全部构图。 */
    public static void register(Map<String, TemperaCompositions.Drawer> into) {
        into.put("ridge-line", TemperaTerrainCompositions::ridgeLine);
        into.put("chasm", TemperaTerrainCompositions::chasm);
        into.put("overhang", TemperaTerrainCompositions::overhang);
        into.put("step-well", TemperaTerrainCompositions::stepWell);
        into.put("pier-row", TemperaTerrainCompositions::pierRow);
        into.put("revetment", TemperaTerrainCompositions::revetment);
        into.put("tower-crop", TemperaTerrainCompositions::towerCrop);
        into.put("lintel", TemperaTerrainCompositions::lintel);
        into.put("rubble-fan", TemperaTerrainCompositions::rubbleFan);
        into.put("gnomon", TemperaTerrainCompositions::gnomon);
    }
}
