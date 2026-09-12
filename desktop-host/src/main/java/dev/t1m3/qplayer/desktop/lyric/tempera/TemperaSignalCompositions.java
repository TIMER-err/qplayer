package dev.t1m3.qplayer.desktop.lyric.tempera;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘构图族，1:1 移植自 folia-major {@code tempera/compositions/temperaSignalCompositions.ts}。
 *
 * <p>仪表盘：硬朗的几何装饰——刻度线、刻度、括号、重复的板条——围着一块厚重的、与字共框的质量
 * 排布。装饰故意做得规整而非外露，读起来像一张印出来的图；质量则是全框唯一被允许「大声」的东西。
 *
 * <p>有几个种类把开口冲在质量本身上，而不是冲在场里，于是洞跟着形状走，而不是坐在它背后。
 */
public final class TemperaSignalCompositions {
    private TemperaSignalCompositions() {
    }

    /** 通屏的色调底，带一个可省的透明度。 */
    private static void addField(TemperaCompositionContext ctx, String color) {
        float width = ctx.width;
        float height = ctx.height;
        float bleed = ctx.bleed;
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2f, height + bleed * 2f),
                color, 0.92f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().span(0.5f));
    }

    /** 十字准星：通屏的刻度线、一个框住的中心、还有四角的刻度。交叉下方的实块就是字站的地方。 */
    private static void sightMark(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float cx = width * 0.5f;
        float cy = height * 0.5f;
        addField(ctx, palette.tone1);
        List<TemperaHatch.Line> cross = new ArrayList<>();
        cross.add(new TemperaHatch.Line(-bleed, cy, width + bleed, cy));
        cross.add(new TemperaHatch.Line(cx, -bleed, cx, height + bleed));
        ctx.add(TemperaShapes.drawLines(cross, palette.tone4, 1.4f, 0.6f),
            TemperaBlocks.BlockOptions.of().delay(0.04f).span(0.55f).enterDX(width * 0.1f));
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(width * 0.24f, height * 0.36f, width * 0.52f, height * 0.28f),
                palette.tone3, 0.94f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.1f).span(0.55f).enterDY(height * 0.06f));
        ctx.add(TemperaShapes.drawPolygonOutline(
                TemperaHatch.rectPolygon(width * 0.2f, height * 0.32f, width * 0.6f, height * 0.36f),
                palette.ink, 2.4f, 0.85f),
            TemperaBlocks.BlockOptions.of().delay(0.16f).span(0.5f));
        if (!ctx.showDecor) return;
        // 四角刻度，各一条臂，指向框内。
        float[][] corners = {
            {0.2f, 0.32f, 1f, 1f},
            {0.8f, 0.32f, -1f, 1f},
            {0.2f, 0.68f, 1f, -1f},
            {0.8f, 0.68f, -1f, -1f},
        };
        for (int index = 0; index < corners.length; index += 1) {
            float fx = corners[index][0];
            float fy = corners[index][1];
            float sx = corners[index][2];
            float sy = corners[index][3];
            float x = width * fx;
            float y = height * fy;
            List<TemperaHatch.Line> arms = new ArrayList<>();
            arms.add(new TemperaHatch.Line(x, y, x + sx * width * 0.04f, y));
            arms.add(new TemperaHatch.Line(x, y, x, y + sy * height * 0.05f));
            ctx.add(TemperaShapes.drawLines(arms, palette.ink, 3f, 0.8f),
                TemperaBlocks.BlockOptions.of().delay(0.2f + index * 0.03f).span(0.5f));
        }
    }

    /** 一个表盘：实心环、环周的刻度、一根厚重的指针。轮毂留实给字用。 */
    private static void dialScale(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, height);
        float cx = width * 0.5f;
        float cy = height * 0.5f;
        addField(ctx, palette.tone2);
        // 画出来的环，不是中间挖洞的圆盘：轮毂要托住字，所以它必须是实心的场，
        // 而不是一个「背后恰好有点东西」的开口。
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaCurves.annularSectorPolygon(cx, cy, unit * 0.34f, unit * 0.44f,
                        0f, (float) Math.PI * 2f - 0.006f, 60),
                palette.tone4, 0.92f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.6f));
        List<TemperaHatch.Line> ticks = new ArrayList<>();
        for (int index = 0; index < 24; index += 1) {
            float angle = (index / 24f) * (float) Math.PI * 2f;
            float inner = unit * (index % 6 == 0 ? 0.46f : 0.475f);
            ticks.add(new TemperaHatch.Line(
                    (float) (cx + Math.cos(angle) * inner),
                    (float) (cy + Math.sin(angle) * inner),
                    (float) (cx + Math.cos(angle) * unit * 0.5f),
                    (float) (cy + Math.sin(angle) * unit * 0.5f)));
        }
        ctx.add(TemperaShapes.drawLines(ticks, palette.ink, 1.6f, 0.7f),
            TemperaBlocks.BlockOptions.of().delay(0.14f).span(0.55f));
        float pointer = 0.4f + (float) TemperaRandom.hash01(ctx.seed, 5, 149) * 0.5f;
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaCutout.axisRect(
                    cx + (float) Math.cos(pointer * (float) Math.PI * 2f) * unit * 0.2f,
                    cy + (float) Math.sin(pointer * (float) Math.PI * 2f) * unit * 0.2f,
                    unit * 0.4f, unit * 0.035f, pointer * (float) Math.PI * 2f),
                palette.ink, 0.9f),
            TemperaBlocks.BlockOptions.of().delay(0.2f).span(0.5f));
    }

    /** 沿画面重复的雪佛龙，其中一个倒过来：重复让交接看不出来，那个异类才是眼睛落点。 */
    private static void chevronRun(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        int focus = 2;
        addField(ctx, palette.tone1);
        for (int index = 0; index < 5; index += 1) {
            float y = height * (0.02f + index * 0.24f);
            float rise = height * 0.12f;
            float thickness = height * 0.07f;
            float[] chevron = {
                -bleed, y, width * 0.5f, y - rise, width + bleed, y,
                width + bleed, y + thickness, width * 0.5f, y - rise + thickness, -bleed, y + thickness,
            };
            ctx.add(TemperaShapes.drawPolygonFill(chevron,
                    index == focus ? palette.tone4 : palette.tone3,
                    index == focus ? 0.95f : 0.7f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(index * 0.04f).span(0.55f).enterDY(-height * 0.12f));
        }
    }

    /** 贴着一条边的一列 tally 标记，长那笔是焦点元素。 */
    private static void tallyColumn(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float x = width * 0.82f;
        addField(ctx, palette.tone1);
        List<TemperaHatch.Line> marks = new ArrayList<>();
        for (int index = 0; index < 11; index += 1) {
            boolean longMark = index % 4 == 0;
            float y = height * (0.1f + index * 0.075f);
            marks.add(new TemperaHatch.Line(x, y, x + width * (longMark ? 0.12f : 0.06f), y));
        }
        ctx.add(TemperaShapes.drawLines(marks, palette.tone4, 2.4f, 0.75f),
            TemperaBlocks.BlockOptions.of().delay(0.06f).span(0.6f).enterDX(width * 0.1f));
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(x - width * 0.02f, height * 0.4f, width * 0.16f, height * 0.12f),
                palette.tone4, 0.95f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.16f).span(0.55f).enterDX(width * 0.14f));
        ctx.add(TemperaShapes.drawLines(
                TemperaShapes.line(x, height * 0.06f, x, height * 0.92f),
                palette.ink, 1.6f, 0.6f),
            TemperaBlocks.BlockOptions.of().delay(0.2f).span(0.5f));
    }

    /** 一张细网格，其中一格填实、两格冲穿。 */
    private static void gridFocus(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float left = width * 0.08f;
        float top = height * 0.12f;
        float cellWidth = (width * 0.84f) / 4f;
        float cellHeight = (height * 0.76f) / 3f;
        float[] cell00 = TemperaHatch.rectPolygon(left + cellWidth * 0f, top + cellHeight * 0f, cellWidth, cellHeight);
        float[] cell32 = TemperaHatch.rectPolygon(left + cellWidth * 3f, top + cellHeight * 2f, cellWidth, cellHeight);
        List<float[]> holes = new ArrayList<>();
        holes.add(cell00);
        holes.add(cell32);
        TemperaCutout.addCutField(ctx, palette.tone2, holes);
        TemperaCutout.addHoleLip(ctx, cell00, 2f, 0.7f, 0.1f);
        TemperaCutout.addHoleLip(ctx, cell32, 2f, 0.7f, 0.14f);
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(left + cellWidth * 3f, top, cellWidth, cellHeight),
                palette.tone4, 0.95f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.08f).span(0.55f).enterDX(width * 0.08f));
        List<TemperaHatch.Line> lines = new ArrayList<>();
        for (int column = 0; column <= 4; column += 1) {
            lines.add(new TemperaHatch.Line(left + cellWidth * column, top,
                    left + cellWidth * column, top + cellHeight * 3f));
        }
        for (int row = 0; row <= 3; row += 1) {
            lines.add(new TemperaHatch.Line(left, top + cellHeight * row,
                    left + cellWidth * 4f, top + cellHeight * row));
        }
        ctx.add(TemperaShapes.drawLines(lines, palette.tone4, 1.2f, 0.6f),
            TemperaBlocks.BlockOptions.of().delay(0.16f).span(0.55f));
    }

    /**
     * 一根横贯画面的厚重轴，中间钻一个孔、两端各一块端帽。孔跟着杆走，不跟着背后的场走。
     */
    private static void axisCaps(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, height);
        float cx = width * 0.5f;
        float cy = height * 0.42f;
        float[] bar = TemperaCutout.axisRect(cx, cy, TemperaCutout.flowSpan(ctx), unit * 0.22f, ctx.flowAngle);
        // 孔既从场里、也从杆上挖掉。只挖杆会露出底下的场，而不是背景——只有背后什么都不剩，才算窗口。
        float[] port = TemperaHatch.circlePolygon(cx, cy, unit * 0.08f, 32);
        List<float[]> portHoles = new ArrayList<>();
        portHoles.add(port);
        TemperaCutout.addCutField(ctx, palette.tone1, portHoles, 0.92f);
        ctx.add(TemperaShapes.drawPolygonFillWithHoles(bar, portHoles, palette.tone4, 0.94f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.05f).span(0.6f));
        ctx.add(TemperaShapes.drawRings(TemperaShapes.disc(cx, cy, unit * 0.08f), palette.ink, 2.4f, 0.85f),
            TemperaBlocks.BlockOptions.of().delay(0.12f).span(0.5f));
        float[] sides = {1f, -1f};
        for (int index = 0; index < sides.length; index += 1) {
            float side = sides[index];
            float distance = unit * 0.46f;
            float capX = cx + (float) Math.cos(ctx.flowAngle) * distance * side;
            float capY = cy + (float) Math.sin(ctx.flowAngle) * distance * side;
            ctx.add(TemperaShapes.drawPolygonFill(
                    TemperaCutout.axisRect(capX, capY, unit * 0.1f, unit * 0.34f, ctx.flowAngle),
                    palette.ink, 0.85f),
                TemperaBlocks.BlockOptions.of().delay(0.16f + index * 0.04f).span(0.5f));
        }
    }

    /** 宽度交错的板条。最宽的那条是字去的地方，所以它用浅色调画，其余向它合拢。 */
    private static void strobeSlats(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        addField(ctx, palette.tone1);
        float[] fractions = {0.06f, 0.03f, 0.09f, 0.04f, 0.34f, 0.04f, 0.08f, 0.03f, 0.06f};
        float cursor = width * 0.03f;
        for (int index = 0; index < fractions.length; index += 1) {
            float fraction = fractions[index];
            float[] slat = TemperaHatch.rectPolygon(cursor, -bleed, width * fraction, height + bleed * 2f);
            boolean wide = fraction > 0.2f;
            ctx.add(TemperaShapes.drawPolygonFill(slat,
                    wide ? palette.tone2 : palette.tone4,
                    wide ? 0.9f : 0.85f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(index * 0.03f).span(0.55f)
                    .enterDY((index % 2 == 0 ? 1f : -1f) * height * 0.14f));
            cursor += width * (fraction + 0.02f);
        }
    }

    /**
     * 三块同样大小的板子错开套印，叠出经典的套版错位。最上面的板钻一个登记孔，直穿整叠。
     */
    private static void offsetPlate(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, height);
        float step = unit * 0.06f;
        // 一个登记孔，笔直钻穿整个错位的叠层——场和每一块板都在此处、同地开同一个口，所以它真的透。
        float holeX = width * 0.72f;
        float holeY = height * 0.36f;
        float[] hole = TemperaHatch.circlePolygon(holeX, holeY, unit * 0.05f, 28);
        List<float[]> holeList = new ArrayList<>();
        holeList.add(hole);
        TemperaCutout.addCutField(ctx, palette.tone1, holeList, 0.9f);
        String[] tones = {palette.tone2, palette.tone3, palette.tone4};
        for (int index = 0; index < tones.length; index += 1) {
            String tone = tones[index];
            float[] plate = TemperaHatch.rectPolygon(width * 0.2f + step * index,
                    height * 0.2f + step * index, width * 0.5f, height * 0.5f);
            ctx.add(TemperaShapes.drawPolygonFillWithHoles(plate, holeList, tone,
                    index == 2 ? 0.95f : 0.8f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(index * 0.06f).span(0.55f)
                    .enterDX(-step * 2f).enterDY(-step));
        }
        ctx.add(TemperaShapes.drawRings(TemperaShapes.disc(holeX, holeY, unit * 0.05f),
                palette.ink, 2f, 0.8f),
            TemperaBlocks.BlockOptions.of().delay(0.22f).span(0.5f));
    }

    /** 从框外一处轮毂放射的一排光线，配一个实心扇区作质量。 */
    private static void radialComb(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float hubX = width * 1.06f;
        float hubY = height * 1.1f;
        float reach = (float) (Math.hypot(width, height) * 1.3);
        addField(ctx, palette.tone2);
        List<TemperaHatch.Line> rays = new ArrayList<>();
        for (int index = 0; index < 11; index += 1) {
            float angle = (float) Math.PI + 0.16f + index * 0.062f;
            rays.add(new TemperaHatch.Line(hubX, hubY,
                    (float) (hubX + Math.cos(angle) * reach),
                    (float) (hubY + Math.sin(angle) * reach)));
        }
        ctx.add(TemperaShapes.drawLines(rays, palette.tone4, 2.6f, 0.8f),
            TemperaBlocks.BlockOptions.of().delay(0.06f).span(0.6f));
        float startA = (float) Math.PI + 0.44f;
        float endA = (float) Math.PI + 0.56f;
        ctx.add(TemperaShapes.drawPolygonFill(new float[]{
                hubX, hubY,
                (float) (hubX + Math.cos(startA) * reach), (float) (hubY + Math.sin(startA) * reach),
                (float) (hubX + Math.cos(endA) * reach), (float) (hubY + Math.sin(endA) * reach),
            }, palette.tone4, 0.95f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.14f).span(0.6f));
        if (!ctx.showDecor) return;
        ctx.add(TemperaShapes.drawHatchFill(
                TemperaHatch.rectPolygon(width * 0.06f, height * 0.08f, width * 0.2f, height * 0.14f),
                TemperaHatch.buildHatchSpec(ctx.seed, 151), palette.tone4, 0.4f),
            TemperaBlocks.BlockOptions.of().delay(0.24f).span(0.6f).grow());
    }

    /** 取景框：开口下方四周的括号，字在开口上方的板上。 */
    private static void bracketTarget(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float[] box = TemperaHatch.rectPolygon(width * 0.28f, height * 0.54f, width * 0.44f, height * 0.34f);
        List<float[]> holes = new ArrayList<>();
        holes.add(box);
        TemperaCutout.addCutField(ctx, palette.tone3, holes);
        TemperaCutout.addHoleLip(ctx, box, 2.4f, 0.85f, 0.08f);
        float arm = Math.min(width, height) * 0.08f;
        float[][] corners = {
            {0.28f, 0.54f, 1f, 1f},
            {0.72f, 0.54f, -1f, 1f},
            {0.28f, 0.88f, 1f, -1f},
            {0.72f, 0.88f, -1f, -1f},
        };
        for (int index = 0; index < corners.length; index += 1) {
            float fx = corners[index][0];
            float fy = corners[index][1];
            float sx = corners[index][2];
            float sy = corners[index][3];
            float x = width * fx + sx * width * 0.03f;
            float y = height * fy + sy * height * 0.04f;
            List<TemperaHatch.Line> arms = new ArrayList<>();
            arms.add(new TemperaHatch.Line(x, y, x + sx * arm, y));
            arms.add(new TemperaHatch.Line(x, y, x, y + sy * arm));
            ctx.add(TemperaShapes.drawLines(arms, palette.ink, 3.5f, 0.9f),
                TemperaBlocks.BlockOptions.of().delay(0.12f + index * 0.04f).span(0.5f).enterDX(sx * width * 0.05f));
        }
        ctx.add(TemperaShapes.drawLines(
                TemperaShapes.line(width * 0.1f, height * 0.44f, width * 0.9f, height * 0.44f),
                palette.tone4, 1.6f, 0.6f),
            TemperaBlocks.BlockOptions.of().delay(0.24f).span(0.5f).enterDX(-width * 0.1f));
    }

    public static void register(Map<String, TemperaCompositions.Drawer> into) {
        into.put("sight-mark", TemperaSignalCompositions::sightMark);
        into.put("dial-scale", TemperaSignalCompositions::dialScale);
        into.put("chevron-run", TemperaSignalCompositions::chevronRun);
        into.put("tally-column", TemperaSignalCompositions::tallyColumn);
        into.put("grid-focus", TemperaSignalCompositions::gridFocus);
        into.put("axis-caps", TemperaSignalCompositions::axisCaps);
        into.put("strobe-slats", TemperaSignalCompositions::strobeSlats);
        into.put("offset-plate", TemperaSignalCompositions::offsetPlate);
        into.put("radial-comb", TemperaSignalCompositions::radialComb);
        into.put("bracket-target", TemperaSignalCompositions::bracketTarget);
    }
}
