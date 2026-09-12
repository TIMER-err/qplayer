package dev.t1m3.qplayer.lyric.tempera;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 构图注册表，以及每个镜头共用的两层装饰，1:1 移植自 folia-major
 * {@code tempera/temperaCompositions.ts}。
 *
 * <p>逐种的绘制在 {@code compositions} 各族的文件里按族分组；同一批镜头的布局区域与
 * 相机档案在 {@link TemperaShotProfiles} 里。
 */
public final class TemperaCompositions {
    private TemperaCompositions() {
    }

    /** 一个构图绘制器：往上下文里塞图形。 */
    public interface Drawer {
        void draw(TemperaCompositionContext ctx);
    }

    private static final Map<String, Drawer> COMPOSITIONS = new HashMap<>();

    static {
        TemperaSplitCompositions.register(COMPOSITIONS);
        TemperaBandCompositions.register(COMPOSITIONS);
        TemperaFrameCompositions.register(COMPOSITIONS);
        TemperaPosterCompositions.register(COMPOSITIONS);
        TemperaSparseCompositions.register(COMPOSITIONS);
        TemperaCinemaCompositions.register(COMPOSITIONS);
        TemperaCharmCompositions.register(COMPOSITIONS);
        TemperaApertureCompositions.register(COMPOSITIONS);
        TemperaSignalCompositions.register(COMPOSITIONS);
        TemperaCorridorCompositions.register(COMPOSITIONS);
        TemperaMonolithCompositions.register(COMPOSITIONS);
        TemperaTerrainCompositions.register(COMPOSITIONS);
        TemperaMonogatariCompositions.register(COMPOSITIONS);
    }

    /** 每种镜头都必须解到一个绘制器；缺口的兜底是 {@code duo-split}。 */
    public static Drawer resolve(String kind) {
        Drawer drawer = COMPOSITIONS.get(kind);
        return drawer != null ? drawer : COMPOSITIONS.get("duo-split");
    }

    /** 覆盖度自检：返回所有没有登记绘制器的镜头种类。 */
    public static List<String> missingKinds() {
        List<String> missing = new java.util.ArrayList<>();
        for (String kind : TemperaTypes.SHOT_KINDS) {
            if (!COMPOSITIONS.containsKey(kind)) missing.add(kind);
        }
        return missing;
    }

    // 每种镜头共用的通屏引导线；它们负责把视线从一个镜头带到下一个。
    private static void addCrossingLines(TemperaCompositionContext ctx) {
        List<TemperaHatch.Line> lines = TemperaHatch.buildCrossingLines(
                ctx.seed, 31, ctx.width, ctx.height, ctx.decor.crossCount);
        if (lines.isEmpty()) return;
        ctx.add(TemperaShapes.drawLines(lines, ctx.palette.tone4, 1.3f, 0.6f),
                TemperaBlocks.BlockOptions.of()
                        .delay(0.14f).span(0.6f).enterDX(ctx.width * 0.25f));
    }

    // 母题叠加层：编译期选定的一个额外网点元素，叠在任意种类之上。
    private static void addMotif(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        TemperaTypes.DecorSpec decor = ctx.decor;
        float cornerX = TemperaRandom.hash01(ctx.seed, 61, 7) > 0.5 ? width * 0.14f : width * 0.86f;
        float cornerY = TemperaRandom.hash01(ctx.seed, 63, 7) > 0.5 ? height * 0.18f : height * 0.82f;
        String motif = decor.motif == null ? "doodle" : decor.motif;
        switch (motif) {
            case "diamonds":
                ctx.add(TemperaShapes.drawConcentricDiamonds(cornerX, cornerY, 34, 34, 3,
                                palette.tone4, 0.8f),
                        TemperaBlocks.BlockOptions.of().delay(0.34f).drift());
                return;
            case "hatch-twin": {
                TemperaHatch.HatchSpec spec = TemperaHatch.buildHatchSpec(ctx.seed, 67)
                        .withAngle(decor.hatchAngle);
                for (int index = 0; index < 2; index++) {
                    float[] box = TemperaHatch.rectPolygon(
                            cornerX - 34 + index * 46, cornerY - 26, 38, 38);
                    TemperaHatch.HatchSpec local = index == 0
                            ? spec : spec.withSpacing(spec.spacing * 0.55f);
                    ctx.add(TemperaShapes.drawHatchFill(box, local, palette.tone4, 0.75f),
                            TemperaBlocks.BlockOptions.of()
                                    .delay(0.34f + index * 0.05f).grow());
                    ctx.add(TemperaShapes.drawPolygonOutline(box, palette.tone4, 1.2f, 0.6f),
                            TemperaBlocks.BlockOptions.of().delay(0.38f + index * 0.05f));
                }
                return;
            }
            case "band-cross":
                ctx.add(TemperaShapes.drawCrossMarks(
                                TemperaHatch.buildCrossRow(ctx.seed, 71, cornerX - width * 0.1f,
                                        cornerY, 5, width * 0.05f, 8f, decor.hatchAngle * 0.4f),
                                palette.tone4, 2f, 0.8f),
                        TemperaBlocks.BlockOptions.of().delay(0.34f).span(0.5f));
                return;
            case "poster-diamond":
                // 故意停在边缘之外，让形状溢出画框。
                ctx.add(TemperaShapes.drawPolygonFill(
                                TemperaHatch.diamondPolygon(
                                        cornerX < width / 2f ? -width * 0.04f : width * 1.04f,
                                        cornerY, width * 0.14f, height * 0.2f),
                                palette.tone3, 0.85f, ctx.gradient),
                        TemperaBlocks.BlockOptions.of()
                                .delay(0.32f).span(0.55f)
                                .enterDX((cornerX < width / 2f ? -1f : 1f) * width * 0.1f));
                return;
            case "doodle":
            default:
                ctx.add(TemperaShapes.drawPolyline(
                                TemperaHatch.buildScribblePath(decor.scribbleSeed, 73, cornerX, cornerY,
                                        Math.min(width, height) * 0.08f, 3),
                                palette.tone4, 1.5f, 0.72f),
                        TemperaBlocks.BlockOptions.of().delay(0.34f).span(0.6f));
        }
    }

    public static void drawTemperaComposition(TemperaCompositionContext ctx) {
        Drawer drawer = resolve(ctx.kind);
        if (drawer != null) drawer.draw(ctx);
        // 过场卡按定义就是一片净场；共用的叠加层会把它们毁掉。
        if (!TemperaShotProfiles.resolve(ctx.kind).sharedDecor) return;
        addCrossingLines(ctx);
        if (ctx.showDecor) addMotif(ctx);
    }
}
