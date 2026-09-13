package dev.t1m3.qplayer.lyric.tempera;

import java.util.ArrayList;
import java.util.List;

/**
 * 粗野主义语汇，{@code monolith} 与 {@code terrain} 两族共用，1:1 移植自 folia-major
 * {@code tempera/compositions/temperaMonolithKit.ts}。
 *
 * <p>一块巨大的哑光实体、两三条横贯整张画面的发丝线、一面排线、几枚小小的测绘标记。
 * 实体自己撑起整个镜头，所以这里别的元素都刻意做得很细——被装饰围住的巨物就不再纪念性了。
 *
 * <p>每一块实体至少有一边跑出画框。四条边都在画面里的实体读起来是「一页纸上的一个物件」；
 * 被画框裁掉的同一块实体读起来是「大到装不下」，而这正是全部效果。
 */
public final class TemperaMonolithKit {
    private TemperaMonolithKit() {
    }

    /** {@code addMass} 的参数，对应 JS 的 options 对象字面量。 */
    public static final class MassOptions {
        public float alpha = 0.95f;
        public float delay = 0.04f;
        public float span = 0.6f;
        public float enterDX;
        public float enterDY;
        /** 墨色轮廓。与另一块实体相接（而不是接地）的实体关掉它。 */
        public boolean edge = true;
        public float edgeWidth = 2.4f;

        public static MassOptions of() {
            return new MassOptions();
        }

        public MassOptions alpha(float value) {
            this.alpha = value;
            return this;
        }

        public MassOptions delay(float value) {
            this.delay = value;
            return this;
        }

        public MassOptions span(float value) {
            this.span = value;
            return this;
        }

        public MassOptions enterDX(float value) {
            this.enterDX = value;
            return this;
        }

        public MassOptions enterDY(float value) {
            this.enterDY = value;
            return this;
        }

        public MassOptions edge(boolean value) {
            this.edge = value;
            return this;
        }

        public MassOptions edgeWidth(float value) {
            this.edgeWidth = value;
            return this;
        }
    }

    public static void addMass(TemperaCompositionContext ctx, float[] polygon, String color) {
        addMass(ctx, polygon, color, MassOptions.of());
    }

    public static void addMass(TemperaCompositionContext ctx, float[] polygon, String color,
                               MassOptions options) {
        ctx.add(TemperaShapes.drawPolygonFill(polygon, color, options.alpha, ctx.gradient),
                TemperaBlocks.BlockOptions.of()
                        .delay(options.delay)
                        .span(options.span)
                        .enterDX(options.enterDX)
                        .enterDY(options.enterDY));
        if (!options.edge) return;
        ctx.add(TemperaShapes.drawPolygonOutline(polygon, ctx.palette.ink, options.edgeWidth, 0.8f),
                TemperaBlocks.BlockOptions.of()
                        .delay(options.delay + 0.06f)
                        .span(0.5f)
                        .enterDX(options.enterDX * 0.6f)
                        .enterDY(options.enterDY * 0.6f));
    }

    /** 实体所立的那片通屏地面。 */
    public static void addGround(TemperaCompositionContext ctx, String color) {
        addGround(ctx, color, 0.94f);
    }

    public static void addGround(TemperaCompositionContext ctx, String color, float alpha) {
        ctx.add(TemperaShapes.drawPolygonFill(
                        TemperaHatch.rectPolygon(-ctx.bleed, -ctx.bleed,
                                ctx.width + ctx.bleed * 2, ctx.height + ctx.bleed * 2),
                        color, alpha, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.5f));
    }

    /**
     * 实体某一面上的排线。多边形必须是凸的——{@code buildHatchLines} 靠边的半平面做裁剪——
     * 所以调用方传的是实体的一个凸切片，而不是实体本身（实体通常带台阶或缺口）。
     */
    public static void addFaceRuling(TemperaCompositionContext ctx, float[] face, int salt) {
        addFaceRuling(ctx, face, salt, ctx.palette.tone4, 0.5f);
    }

    public static void addFaceRuling(TemperaCompositionContext ctx, float[] face, int salt,
                                     String color, float alpha) {
        ctx.add(TemperaShapes.drawHatchFill(face, TemperaHatch.buildHatchSpec(ctx.seed, salt, 0.7f),
                        color, alpha),
                TemperaBlocks.BlockOptions.of().delay(0.16f).span(0.65f).grow());
    }

    /**
     * 两三条以极浅角度横贯整张画面的发丝线。它们是这些构图里唯一无视实体的东西，
     * 而正是这一点给了实体尺度感：一条横穿一切却什么都没碰到的线。
     */
    public static void addSurveyLines(TemperaCompositionContext ctx) {
        addSurveyLines(ctx, 3, 211);
    }

    public static void addSurveyLines(TemperaCompositionContext ctx, int count) {
        addSurveyLines(ctx, count, 211);
    }

    public static void addSurveyLines(TemperaCompositionContext ctx, int count, int salt) {
        float width = ctx.width;
        float height = ctx.height;
        float bleed = ctx.bleed;
        List<TemperaHatch.Line> lines = new ArrayList<>();
        int total = Math.max(1, count);
        for (int index = 0; index < total; index++) {
            float anchor = (float) (height * (0.16 + ((index * 0.31 + (ctx.seed % 7) * 0.04) % 0.7)));
            float lean = height * (index % 2 == 0 ? 0.22f : -0.16f);
            lines.add(new TemperaHatch.Line(-bleed, anchor - lean, width + bleed, anchor + lean));
        }
        ctx.add(TemperaShapes.drawLines(lines, ctx.palette.paper, 1f, 0.45f),
                TemperaBlocks.BlockOptions.of()
                        .delay(0.2f + (salt % 3) * 0.02f)
                        .span(0.7f)
                        .enterDX(width * 0.2f));
    }

    /** 对角两个角落里的测绘标记：为某个大到根本没法登记的东西做登记。 */
    public static void addCornerTicks(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float inset = Math.min(width, height) * 0.06f;
        float arm = Math.min(width, height) * 0.05f;
        int[][] signs = {{1, 1}, {-1, -1}};
        for (int index = 0; index < signs.length; index++) {
            int sx = signs[index][0];
            int sy = signs[index][1];
            float x = sx > 0 ? inset : width - inset;
            float y = sy > 0 ? inset : height - inset;
            List<TemperaHatch.Line> lines = new ArrayList<>();
            lines.add(new TemperaHatch.Line(x, y, x + sx * arm, y));
            lines.add(new TemperaHatch.Line(x, y, x, y + sy * arm));
            ctx.add(TemperaShapes.drawLines(lines, palette.paper, 1.6f, 0.6f),
                    TemperaBlocks.BlockOptions.of().delay(0.26f + index * 0.04f).span(0.5f));
        }
    }

    /** 停在角落里的一圈松垮轮廓——一段并不存在的形状的测绘描迹。 */
    public static void addWireTrace(TemperaCompositionContext ctx, float cx, float cy, float radius) {
        // 放在已定位的分组里的局部坐标：`drift` 是让节点绕自身原点缩放与旋转的，
        // 所以按绝对坐标画的描迹会绕着画面原点公转。
        TemperaDraw.Graphic group = ctx.createGroup(0f, cx, cy);
        ctx.add(TemperaShapes.drawPolyline(
                        TemperaHatch.buildScribblePath(ctx.decor.scribbleSeed, 199, 0f, 0f, radius, 1f),
                        ctx.palette.paper, 1.2f, 0.4f),
                TemperaBlocks.BlockOptions.of().delay(0.3f).span(0.6f).drift(), group);
    }
}
