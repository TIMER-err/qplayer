package dev.t1m3.qplayer.lyric.tempera;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Monogatari 风格的间场卡片构图，1:1 移植自 folia-major
 * {@code tempera/compositions/temperaMonogatariCompositions.ts}。
 *
 * <p>整张卡片就是一片边缘到边缘的平涂场，字就是全部画面。这些构图故意几乎不带几何——前后的镜头做
 * 了活，卡片只是它们之间的那拍静默。
 */
public final class TemperaMonogatariCompositions {

    private TemperaMonogatariCompositions() {
    }

    // 平涂整张场。每帧都从通屏矩形起步。
    private static void addField(TemperaCompositionContext ctx, String tone) {
        float width = ctx.width;
        float height = ctx.height;
        float bleed = ctx.bleed;
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, -bleed, width + bleed * 2f, height + bleed * 2f),
                tone, 1f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().span(0.45f));
    }

    // 在色阶两端之间轮换取色，于是同一首歌里前后两张卡片读成剪辑而非定格。
    private static String pickField(TemperaCompositionContext ctx) {
        return TemperaRandom.hash01(ctx.seed, 167, 173) > 0.5
                ? ctx.palette.tone4 : ctx.palette.tone1;
    }

    // 空卡片：平涂场，别的什么都没有。字独自撑起它。
    private static void monogatariCard(TemperaCompositionContext ctx) {
        addField(ctx, pickField(ctx));
        if (!ctx.showDecor) return;
        // 最底部一条发丝线，像题名卡那样带一条页脚规线。
        List<TemperaHatch.Line> line = new ArrayList<>();
        line.add(new TemperaHatch.Line(ctx.width * 0.12f, ctx.height * 0.9f,
                ctx.width * 0.88f, ctx.height * 0.9f));
        ctx.add(TemperaShapes.drawLines(line, ctx.palette.paper, 1.2f, 0.45f),
                TemperaBlocks.BlockOptions.of().delay(0.22f).span(0.6f).enterDX(ctx.width * 0.15f));
    }

    // 平涂场，字下方压一条重规线，把卡片分成两半。
    private static void monogatariRule(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        addField(ctx, pickField(ctx));
        float y = height * (0.62f + (float) (TemperaRandom.hash01(ctx.seed, 179, 181) * 0.08));
        ctx.add(TemperaShapes.drawPolygonFill(
                TemperaHatch.rectPolygon(-bleed, y, width + bleed * 2f, height * 0.012f),
                palette.paper, 0.85f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.12f).span(0.55f).enterDX(-width * 0.3f));
        if (!ctx.showDecor) return;
        List<TemperaHatch.Line> line = new ArrayList<>();
        line.add(new TemperaHatch.Line(-bleed, y + height * 0.05f, width + bleed, y + height * 0.05f));
        ctx.add(TemperaShapes.drawLines(line, palette.paper, 1f, 0.35f),
                TemperaBlocks.BlockOptions.of().delay(0.2f).span(0.6f).enterDX(width * 0.2f));
    }

    // 平涂场，一侧一条反差带，像装订过的书页。
    private static void monogatariEdge(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        String field = pickField(ctx);
        addField(ctx, field);
        float band = width * (0.1f + (float) (TemperaRandom.hash01(ctx.seed, 191, 193) * 0.05));
        boolean fromLeft = TemperaRandom.hash01(ctx.seed, 197, 199) > 0.5;
        float[] spine = TemperaHatch.rectPolygon(fromLeft ? -bleed : width - band, -bleed,
                band + bleed, height + bleed * 2f);
        ctx.add(TemperaShapes.drawPolygonFill(spine,
                field.equals(palette.tone4) ? palette.tone1 : palette.tone4,
                0.95f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.1f).span(0.55f)
                        .enterDX((fromLeft ? -1f : 1f) * width * 0.2f));
        if (!ctx.showDecor) return;
        ctx.add(TemperaShapes.drawHatchFill(spine, TemperaHatch.buildHatchSpec(ctx.seed, 211),
                palette.paper, 0.2f),
                TemperaBlocks.BlockOptions.of().delay(0.18f).span(0.6f).grow());
    }

    // 平涂场，窄窄的字列：卡片读成一则堆叠的题注。
    private static void monogatariStack(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float bleed = ctx.bleed;
        addField(ctx, pickField(ctx));
        float column = width * 0.5f;
        List<TemperaHatch.Line> lines = new ArrayList<>();
        lines.add(new TemperaHatch.Line((width - column) / 2f, -bleed, (width - column) / 2f, height + bleed));
        lines.add(new TemperaHatch.Line((width + column) / 2f, -bleed, (width + column) / 2f, height + bleed));
        ctx.add(TemperaShapes.drawLines(lines, palette.paper, 1.1f, 0.4f),
                TemperaBlocks.BlockOptions.of().delay(0.14f).span(0.6f).enterDY(height * 0.12f));
    }

    // 最响的一张：色阶最远端的场、尺寸最大的字、一排标记点。
    private static void monogatariFlash(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        addField(ctx, palette.tone4);
        if (!ctx.showDecor) return;
        ctx.add(TemperaShapes.drawCrossMarks(
                TemperaHatch.buildCrossRow(ctx.seed, 223, width * 0.1f, height * 0.18f, 4, width * 0.05f, 10f),
                palette.paper, 2.4f, 0.6f),
                TemperaBlocks.BlockOptions.of().delay(0.2f).span(0.5f).enterDX(-width * 0.1f));
    }

    /** 注册本文件里的全部构图。 */
    public static void register(Map<String, TemperaCompositions.Drawer> into) {
        into.put("monogatari-card", TemperaMonogatariCompositions::monogatariCard);
        into.put("monogatari-rule", TemperaMonogatariCompositions::monogatariRule);
        into.put("monogatari-edge", TemperaMonogatariCompositions::monogatariEdge);
        into.put("monogatari-stack", TemperaMonogatariCompositions::monogatariStack);
        into.put("monogatari-flash", TemperaMonogatariCompositions::monogatariFlash);
    }
}
