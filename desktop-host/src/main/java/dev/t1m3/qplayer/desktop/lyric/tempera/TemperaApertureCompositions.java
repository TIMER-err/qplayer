package dev.t1m3.qplayer.desktop.lyric.tempera;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 冲孔板构图族，1:1 移植自 folia-major {@code tempera/compositions/temperaApertureCompositions.ts}。
 *
 * <p>冲孔板：一整张平涂的色调板，中间干净利落地冲一个洞，于是壳里活的背景成了镜头的主角。
 * 开口的形状就是整个构图，这就是为什么它们几乎不带别的几何——一块装饰过的板会跟它自己的窗子抢戏。
 *
 * <p>所有布局区域都落在实色调上，绝不会压在开口上；原因见 {@link TemperaCutout}。
 * 孔的位置按种类固定，而不是随种子镜像，因为必须避开它们的区域本身也是固定数据。
 */
public final class TemperaApertureCompositions {
    private TemperaApertureCompositions() {
    }

    /** 一片平涂的色调上冲一个干净的洞，让壳里活的背景成为镜头的主体。 */
    private static void irisHole(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, height);
        float cx = width * 0.68f;
        float cy = height * 0.42f;
        float radius = unit * 0.3f;
        float[] hole = TemperaHatch.circlePolygon(cx, cy, radius, 56);
        List<float[]> holes = new ArrayList<>();
        holes.add(hole);
        TemperaCutout.addCutField(ctx, palette.tone3, holes);
        // 开口周围的墨色唇边：让洞读起来像装上去的，而不是浮着的。
        TemperaCutout.addHoleLip(ctx, hole, 3f, 0.9f, 0.08f);
        // 一根从远端伸进来的横杆：开口读起来像装上去的，而不是浮着的。
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-ctx.bleed, cy - unit * 0.035f,
                        cx - radius * 0.7f + ctx.bleed, unit * 0.07f),
                palette.tone4, 0.9f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.14f).span(0.55f).enterDX(-width * 0.12f));
        if (!ctx.showDecor) return;
        ctx.add(TemperaShapes.drawRings(TemperaShapes.disc(cx, cy, radius * 1.14f),
                palette.tone4, 1.4f, 0.6f),
            TemperaBlocks.BlockOptions.of().delay(0.2f).span(0.5f).drift());
    }

    /** 横跨板子的一条信箱缝。上下那两块质量是字待的地方，于是开口读起来是地平线，不是窗。 */
    private static void slotRail(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float top = height * 0.22f;
        float[] slot = TemperaHatch.rectPolygon(-bleed, top, width + bleed * 2f, height * 0.16f);
        List<float[]> holes = new ArrayList<>();
        holes.add(slot);
        TemperaCutout.addCutField(ctx, palette.tone4, holes);
        TemperaCutout.addHoleLip(ctx, slot, 2.4f, 0.8f, 0.1f);
        // 跨过缝的那块凸耳是焦点质量；也是唯一穿过缝的东西。
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(width * 0.66f, top - height * 0.06f,
                        width * 0.12f, height * 0.28f),
                palette.tone2, 0.95f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.16f).span(0.55f).enterDY(-height * 0.1f));
        ctx.add(TemperaShapes.drawLines(
                TemperaShapes.line(-bleed, top + height * 0.22f, width + bleed, top + height * 0.22f),
                palette.tone2, 1.4f, 0.55f),
            TemperaBlocks.BlockOptions.of().delay(0.22f).span(0.5f).enterDX(width * 0.14f));
    }

    /** 板子两条边上的链轮孔：它们之间的框才是画面。 */
    private static void punchRow(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, height);
        float radius = unit * 0.028f;
        List<float[]> holes = new ArrayList<>();
        for (int index = 0; index < 9; index += 1) {
            float x = width * (0.08f + index * 0.105f);
            holes.add(TemperaHatch.circlePolygon(x, height * 0.14f, radius, 20));
            holes.add(TemperaHatch.circlePolygon(x, height * 0.86f, radius, 20));
        }
        TemperaCutout.addCutField(ctx, palette.tone3, holes);
        // 两条横线，一条往左伸、一条往右伸，把视线拎进画面。
        float[] offsets = {0.22f, 0.78f};
        for (int index = 0; index < offsets.length; index += 1) {
            float y = height * offsets[index];
            ctx.add(TemperaShapes.drawLines(
                    TemperaShapes.line(0f, height * y, width, height * y),
                    palette.tone4, 1.6f, 0.6f),
                TemperaBlocks.BlockOptions.of()
                    .delay(0.12f + index * 0.04f).span(0.5f)
                    .enterDX((index == 0 ? 1f : -1f) * width * 0.12f));
        }
    }

    /** 一个片门：那个大矩形开口是正被曝光的画面，小的是把画面拉过去的输片孔。 */
    private static void filmGate(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, height);
        float[] gate = TemperaHatch.rectPolygon(width * 0.2f, height * 0.1f, width * 0.6f, height * 0.52f);
        List<float[]> holes = new ArrayList<>();
        holes.add(gate);
        for (int index = 0; index < 4; index += 1) {
            float y = height * (0.14f + index * 0.13f);
            holes.add(TemperaHatch.rectPolygon(width * 0.08f, y, width * 0.05f, height * 0.06f));
            holes.add(TemperaHatch.rectPolygon(width * 0.87f, y, width * 0.05f, height * 0.06f));
        }
        TemperaCutout.addCutField(ctx, palette.tone4, holes);
        TemperaCutout.addHoleLip(ctx, gate, 3f, 0.9f, 0.08f);
        ctx.add(TemperaShapes.drawHatchFill(
                TemperaHatch.rectPolygon(width * 0.2f, height * 0.68f, width * 0.6f, height * 0.1f),
                TemperaHatch.buildHatchSpec(ctx.seed, 131, 1.2f), palette.tone2, 0.5f),
            TemperaBlocks.BlockOptions.of().delay(0.18f).span(0.6f).grow());
    }

    /** 一道冲穿的十字，位置偏高，好让字把下半边质量留给自个儿。 */
    private static void crossVent(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float cx = width * 0.5f;
        float cy = height * 0.38f;
        float arm = Math.min(width, height) * 0.3f;
        float bar = Math.min(width, height) * 0.1f;
        float[] cross = {
            cx - bar, cy - arm, cx + bar, cy - arm, cx + bar, cy - bar,
            cx + arm, cy - bar, cx + arm, cy + bar, cx + bar, cy + bar,
            cx + bar, cy + arm, cx - bar, cy + arm, cx - bar, cy + bar,
            cx - arm, cy + bar, cx - arm, cy - bar, cx - bar, cy - bar,
        };
        List<float[]> holes = new ArrayList<>();
        holes.add(cross);
        TemperaCutout.addCutField(ctx, palette.tone3, holes);
        TemperaCutout.addHoleLip(ctx, cross, 2.6f, 0.85f, 0.1f);
        if (!ctx.showDecor) return;
        ctx.add(TemperaShapes.drawRings(TemperaShapes.disc(cx, cy, arm * 1.24f),
                palette.tone4, 1.2f, 0.5f),
            TemperaBlocks.BlockOptions.of().delay(0.2f).span(0.5f));
    }

    /** 平行百叶缝，微微偏离水平，越往下越细。 */
    private static void louvre(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        List<float[]> holes = new ArrayList<>();
        for (int index = 0; index < 6; index += 1) {
            float y = height * (0.08f + index * 0.1f);
            float lean = height * 0.03f;
            float thickness = height * (0.05f - index * 0.004f);
            holes.add(new float[]{
                -bleed, y, width + bleed, y - lean,
                width + bleed, y - lean + thickness, -bleed, y + thickness,
            });
        }
        TemperaCutout.addCutField(ctx, palette.tone4, holes);
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(width * 0.1f, height * 0.72f, width * 0.08f, height * 0.2f),
                palette.tone2, 0.9f, ctx.gradient),
            TemperaBlocks.BlockOptions.of().delay(0.2f).span(0.55f).enterDY(height * 0.1f));
    }

    /**
     * 一朵玫瑰窗：环被冲成十二段，于是段间的辐条和环内的轮毂仍是板本身。字骑在那轮毂上——
     * 它是全族唯一落在开口内部的区域，只因为轮毂从来没被冲掉才成立。拿一张圆盘盖回圆洞虽然
     * 看起来一样，却是骗人的：绘制顺序只要一变，字就会掉到bare背景上。
     */
    private static void ringEye(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, height);
        float cx = width * 0.5f;
        float cy = height * 0.5f;
        float inner = unit * 0.31f;
        float outer = unit * 0.44f;
        int segments = 12;
        float gap = 0.03f;
        List<float[]> holes = new ArrayList<>();
        for (int index = 0; index < segments; index += 1) {
            float start = (index / (float) segments) * (float) Math.PI * 2f + gap;
            float end = ((index + 1) / (float) segments) * (float) Math.PI * 2f - gap;
            holes.add(TemperaCurves.annularSectorPolygon(cx, cy, inner, outer, start, end, 8));
        }
        TemperaCutout.addCutField(ctx, palette.tone3, holes);
        ctx.add(TemperaShapes.drawRings(
                TemperaShapes.discs(new float[]{cx, cy, inner, cx, cy, outer}),
                palette.ink, 2.4f, 0.85f),
            TemperaBlocks.BlockOptions.of().delay(0.12f).span(0.5f));
    }

    /** 一侧依次缩小的方形开口，每一个都比上一个小一号。 */
    private static void notchStack(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, height);
        List<float[]> holes = new ArrayList<>();
        for (int index = 0; index < 4; index += 1) {
            float size = unit * (0.26f - index * 0.05f);
            holes.add(TemperaHatch.rectPolygon(width * (0.6f + index * 0.06f),
                    height * (0.1f + index * 0.19f), size, size));
        }
        TemperaCutout.addCutField(ctx, palette.tone4, holes);
        for (int index = 0; index < holes.size(); index += 1) {
            TemperaCutout.addHoleLip(ctx, holes.get(index), 2f, 0.75f, 0.1f + index * 0.04f);
        }
    }

    /** 从顶边楔进来的一刀。板子保住了整片下半，那是字去的地方。 */
    private static void wedgeGap(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        float apexX = width * (0.4f + (float) TemperaRandom.hash01(ctx.seed, 3, 137) * 0.2f);
        float[] wedge = {width * 0.1f, -bleed, width * 0.92f, -bleed, apexX, height * 0.56f};
        List<float[]> holes = new ArrayList<>();
        holes.add(wedge);
        TemperaCutout.addCutField(ctx, palette.tone3, holes);
        TemperaCutout.addHoleLip(ctx, wedge, 3f, 0.9f, 0.08f);
        ctx.add(TemperaShapes.drawLines(
                TemperaShapes.line(apexX, height * 0.58f, apexX, height + bleed),
                palette.tone4, 1.6f, 0.6f),
            TemperaBlocks.BlockOptions.of().delay(0.18f).span(0.5f).enterDY(height * 0.12f));
    }

    /**
     * 一个筛子：板子上零零碎碎的小孔，让背景是作为一片光的渐变透出来，而不是一个开口。
     */
    private static void dotSieve(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, height);
        List<float[]> holes = new ArrayList<>();
        for (int row = 0; row < 6; row += 1) {
            // 五列，不是九列：衰减必须在布局区域开始之前就归零，否则最后的散兵会直穿字那半边板子。
            for (int column = 0; column < 5; column += 1) {
                float density = 1f - column / 4.5f;
                if (TemperaRandom.hash01(ctx.seed, row * 9 + column, 139) > density) continue;
                float x = width * (0.06f + column * 0.105f) + (row % 2 == 0 ? 0f : width * 0.05f);
                holes.add(TemperaHatch.circlePolygon(x, height * (0.1f + row * 0.16f),
                        unit * 0.045f * density + unit * 0.012f, 18));
            }
        }
        TemperaCutout.addCutField(ctx, palette.tone2, holes, 0.92f);
    }

    public static void register(Map<String, TemperaCompositions.Drawer> into) {
        into.put("iris-hole", TemperaApertureCompositions::irisHole);
        into.put("slot-rail", TemperaApertureCompositions::slotRail);
        into.put("punch-row", TemperaApertureCompositions::punchRow);
        into.put("film-gate", TemperaApertureCompositions::filmGate);
        into.put("cross-vent", TemperaApertureCompositions::crossVent);
        into.put("louvre-slats", TemperaApertureCompositions::louvre);
        into.put("ring-eye", TemperaApertureCompositions::ringEye);
        into.put("notch-stack", TemperaApertureCompositions::notchStack);
        into.put("wedge-gap", TemperaApertureCompositions::wedgeGap);
        into.put("dot-sieve", TemperaApertureCompositions::dotSieve);
    }
}
