package dev.t1m3.qplayer.lyric.tempera;

import java.util.List;

/**
 * 三个「在色调场上打孔」的族共用的管道，1:1 移植自 folia-major
 * {@code tempera/compositions/temperaCutout.ts}。
 *
 * <p>孔是<b>真正透明</b>的——Pixi 画布跑在 {@code backgroundAlpha: 0} 上——所以它露出的是
 * 壳体里活的背景层，只被场景的纸浆薄涂层盖住（那层是按段落建的，没法按镜头裁）。
 *
 * <p>所有冲孔构图都遵守一条规矩：<b>歌词永远不会落在孔上。</b>反色滤镜读的是渲染目标，
 * 而 DOM 背景活在 WebGL 之外，所以在孔上滤镜只看到那层浅纸浆、会挑墨色——然后墨色还得
 * 在碰巧是什么的背景上活下来。字留在实色调子上；孔是画面，不是纸面。
 */
public final class TemperaCutout {
    private TemperaCutout() {
    }

    /** 一个二维点。 */
    public static final class Pt {
        public final float x;
        public final float y;

        public Pt(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    /** 每个构图都从这张通屏矩形起步。 */
    public static float[] fieldPolygon(TemperaCompositionContext ctx) {
        return TemperaHatch.rectPolygon(-ctx.bleed, -ctx.bleed,
                ctx.width + ctx.bleed * 2, ctx.height + ctx.bleed * 2);
    }

    /** 通屏色调，中间冲穿 {@code holes}。孔的描边要用独立节点。 */
    public static void addCutField(TemperaCompositionContext ctx, String color, List<float[]> holes) {
        addCutField(ctx, color, holes, 0.94f, 0f, 0.5f);
    }

    public static void addCutField(TemperaCompositionContext ctx, String color, List<float[]> holes,
                                   float alpha) {
        addCutField(ctx, color, holes, alpha, 0f, 0.5f);
    }

    public static void addCutField(TemperaCompositionContext ctx, String color, List<float[]> holes,
                                   float alpha, float delay, float span) {
        ctx.add(TemperaShapes.drawPolygonFillWithHoles(
                        fieldPolygon(ctx), holes, color, alpha, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(delay).span(span));
    }

    /**
     * 孔周围那圈墨色唇边。它让冲孔读作一块被冲穿的板，而不是渲染里的一个缺口，
     * 同时给反色滤镜一个紧邻开口的边缘。
     */
    public static void addHoleLip(TemperaCompositionContext ctx, float[] hole) {
        addHoleLip(ctx, hole, 2.4f, 0.85f, 0.1f);
    }

    public static void addHoleLip(TemperaCompositionContext ctx, float[] hole,
                                  float width, float alpha, float delay) {
        ctx.add(TemperaShapes.drawPolygonOutline(hole, ctx.palette.ink, width, alpha),
                TemperaBlocks.BlockOptions.of().delay(delay).span(0.5f));
    }

    /** 一个以 (cx, cy) 为中心、沿 {@code angle} 长 {@code length}、宽 {@code width} 的矩形。 */
    public static float[] axisRect(float cx, float cy, float length, float width, float angle) {
        return TemperaCurves.rotatePolygon(
                TemperaHatch.rectPolygon(cx - length / 2f, cy - width / 2f, length, width),
                cx, cy, angle);
    }

    /**
     * 流动角往它自己的竖直轴上拉回来一半。
     *
     * <p>通道必须平行于行进方向，否则交接不再读作一条连续走廊——但通道也很长，所以原始
     * 倾角（最大约 14°）在 720px 的画面上会把两端横向推走约 90px，足以让开口滑到字底下。
     * 取一半倾角保住方向、把横移减半；一次交接滑动里剩下的失配只有几个像素，看不出来。
     */
    public static float channelAxis(TemperaCompositionContext ctx) {
        float axis = (float) ((Math.sin(ctx.flowAngle) >= 0 ? 1 : -1) * Math.PI / 2);
        return axis + (ctx.flowAngle - axis) * 0.5f;
    }

    /**
     * 垂直于通道轴的单位向量，永远指进画框的 +x 半区。
     *
     * <p>流动以竖直为主，但有些镜头朝上、有些朝下，于是原始垂线会在镜头之间翻边。布局区域
     * 是固定数据，所以建立在原始垂线上的通道偏移有时会落到字底下。把符号归一化之后，
     * 「偏移 +n」在每个镜头里都意味着「往右」。
     */
    public static Pt acrossFlow(float angle) {
        float across = angle + (float) (Math.PI / 2);
        float sign = Math.cos(across) >= 0 ? 1f : -1f;
        return new Pt((float) (Math.cos(across) * sign), (float) (Math.sin(across) * sign));
    }

    /** 一条从画框中心横向偏移 {@code offset} 的通道的中心。 */
    public static Pt acrossPoint(TemperaCompositionContext ctx, float offset) {
        Pt across = acrossFlow(channelAxis(ctx));
        return new Pt(ctx.width / 2f + across.x * offset, ctx.height / 2f + across.y * offset);
    }

    /** 从画框中心沿轴走 {@code distance}、横向偏 {@code offset} 的点。 */
    public static Pt flowPoint(TemperaCompositionContext ctx, float distance, float offset) {
        Pt base = acrossPoint(ctx, offset);
        float angle = channelAxis(ctx);
        return new Pt((float) (base.x + Math.cos(angle) * distance),
                (float) (base.y + Math.sin(angle) * distance));
    }

    /** 无论流动角是多少，一个形状要跑多远才能在两端都离开画框。 */
    public static float flowSpan(TemperaCompositionContext ctx) {
        return (float) (Math.hypot(ctx.width, ctx.height) * 1.6);
    }
}
