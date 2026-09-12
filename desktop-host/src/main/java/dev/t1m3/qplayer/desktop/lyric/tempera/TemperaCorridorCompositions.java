package dev.t1m3.qplayer.desktop.lyric.tempera;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 走廊族构图，1:1 移植自 folia-major
 * {@code tempera/compositions/temperaCorridorCompositions.ts}。
 *
 * <p>开口是<b>顺着镜头行进方向</b>切的。这一族是专门为交接（hand-off）打造的：相邻镜头沿着流动
 * 向量彼此滑过，而一条平行于该向量的走廊在移动时看起来仍是同一条走廊，于是两镜之间的接缝不再
 * 读作一次剪辑。背景透过移动的开口始终连续可见，而不是每个切点都出现又消失。
 *
 * <p>这里所有的东西两端都锚在画框之外——一条看得到尽头的走廊是一个「形状」，而形状会出卖剪辑。
 * 字永远骑在开口旁边或横跨开口的一条实边上；{@link TemperaCutout} 解释了它为什么绝不能直接坐在
 * 开口上。
 */
public final class TemperaCorridorCompositions {

    private TemperaCorridorCompositions() {
    }

    /** 一条通道：沿通道轴方向、横跨 {@code offset} 的一条矩形开口。 */
    private static float[] channel(TemperaCompositionContext ctx, float offset, float width) {
        TemperaCutout.Pt centre = TemperaCutout.acrossPoint(ctx, offset);
        return TemperaCutout.axisRect(centre.x, centre.y, TemperaCutout.flowSpan(ctx), width,
                TemperaCutout.channelAxis(ctx));
    }

    /** 沿通道全长、紧贴唇边外侧的两根栏杆。 */
    private static void addRails(TemperaCompositionContext ctx, float offset, float width, float alpha) {
        float angle = TemperaCutout.channelAxis(ctx);
        float half = TemperaCutout.flowSpan(ctx) / 2f;
        int[] sides = {-1, 1};
        for (int index = 0; index < sides.length; index++) {
            int side = sides[index];
            // 两侧对称偏移，保证通道左右各有一根栏杆
            TemperaCutout.Pt start = TemperaCutout.flowPoint(ctx, -half, offset + side * width);
            TemperaCutout.Pt end = TemperaCutout.flowPoint(ctx, half, offset + side * width);
            List<TemperaHatch.Line> lines = new ArrayList<>();
            lines.add(new TemperaHatch.Line(start.x, start.y, end.x, end.y));
            ctx.add(TemperaShapes.drawLines(lines, ctx.palette.tone4, 1.4f, alpha),
                    TemperaBlocks.BlockOptions.of()
                            .delay(0.18f + index * 0.03f)
                            .span(0.5f)
                            .enterDX((float) (Math.cos(angle) * ctx.width * 0.08))
                            .enterDY((float) (Math.sin(angle) * ctx.height * 0.08)));
        }
    }

    // 一侧一条宽走廊，两条边缘各有一道栏杆。
    private static void flowChannel(TemperaCompositionContext ctx) {
        float width = ctx.width;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, ctx.height);
        float[] slot = channel(ctx, width * 0.26f, unit * 0.3f);
        TemperaCutout.addCutField(ctx, palette.tone3, List.of(slot));
        TemperaCutout.addHoleLip(ctx, slot, 2.6f, 0.85f, 0.08f);
        addRails(ctx, width * 0.26f, unit * 0.19f, 0.55f);
    }

    // 两条走廊，字立在两道走廊之间的实边上。
    private static void twinChannel(TemperaCompositionContext ctx) {
        float width = ctx.width;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, ctx.height);
        float[] slotA = channel(ctx, -width * 0.34f, unit * 0.16f);
        float[] slotB = channel(ctx, width * 0.34f, unit * 0.16f);
        List<float[]> slots = new ArrayList<>();
        slots.add(slotA);
        slots.add(slotB);
        TemperaCutout.addCutField(ctx, palette.tone4, slots);
        for (int index = 0; index < slots.size(); index++) {
            TemperaCutout.addHoleLip(ctx, slots.get(index), 2.4f, 0.8f, 0.1f + index * 0.04f);
        }
    }

    // 芦苇：外三分之一里许多发丝般的走廊，中间留出一条干净的列。
    private static void reedRun(TemperaCompositionContext ctx) {
        float width = ctx.width;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, ctx.height);
        List<float[]> slots = new ArrayList<>();
        int[] sides = {-1, 1};
        for (int s = 0; s < sides.length; s++) {
            int side = sides[s];
            for (int index = 0; index < 4; index++) {
                slots.add(channel(ctx, side * width * (0.22f + index * 0.08f), unit * 0.035f));
            }
        }
        TemperaCutout.addCutField(ctx, palette.tone2, slots, 0.9f);
        addRails(ctx, 0f, width * 0.17f, 0.4f);
    }

    // 一条收着口的走廊——本族里唯一开口不是平移不变的，于是它读起来像走廊抵达了某处。
    private static void taperChannel(TemperaCompositionContext ctx) {
        float width = ctx.width;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, ctx.height);
        float half = TemperaCutout.flowSpan(ctx) / 2f;
        float wide = unit * 0.24f;
        float narrow = unit * 0.06f;
        float offset = width * 0.24f;
        TemperaCutout.Pt c0 = TemperaCutout.flowPoint(ctx, -half, offset - wide);
        TemperaCutout.Pt c1 = TemperaCutout.flowPoint(ctx, -half, offset + wide);
        TemperaCutout.Pt c2 = TemperaCutout.flowPoint(ctx, half, offset + narrow);
        TemperaCutout.Pt c3 = TemperaCutout.flowPoint(ctx, half, offset - narrow);
        float[] slot = {c0.x, c0.y, c1.x, c1.y, c2.x, c2.y, c3.x, c3.y};
        TemperaCutout.addCutField(ctx, palette.tone3, List.of(slot));
        TemperaCutout.addHoleLip(ctx, slot, 2.6f, 0.85f, 0.08f);
    }

    // 沿行进线钻出的一串端口，用一根发丝线串联起来。
    private static void chainPorts(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, height);
        float offset = width * 0.28f;
        float spacing = height * 0.26f;
        float[] steps = {-1.5f, -0.5f, 0.5f, 1.5f};
        List<float[]> holes = new ArrayList<>();
        List<TemperaCurves.Disc> discs = new ArrayList<>();
        for (int i = 0; i < steps.length; i++) {
            TemperaCutout.Pt port = TemperaCutout.flowPoint(ctx, steps[i] * spacing, offset);
            holes.add(TemperaHatch.circlePolygon(port.x, port.y, unit * 0.11f, 40));
            discs.addAll(TemperaShapes.disc(port.x, port.y, unit * 0.11f));
        }
        TemperaCutout.addCutField(ctx, palette.tone4, holes);
        ctx.add(TemperaShapes.drawRings(discs, palette.ink, 2.4f, 0.85f),
                TemperaBlocks.BlockOptions.of().delay(0.1f).span(0.55f));
        TemperaCutout.Pt head = TemperaCutout.flowPoint(ctx, -spacing * 2.4f, offset);
        TemperaCutout.Pt tail = TemperaCutout.flowPoint(ctx, spacing * 2.4f, offset);
        List<TemperaHatch.Line> line = new ArrayList<>();
        line.add(new TemperaHatch.Line(head.x, head.y, tail.x, tail.y));
        ctx.add(TemperaShapes.drawLines(line, palette.tone2, 1.6f, 0.6f),
                TemperaBlocks.BlockOptions.of().delay(0.18f).span(0.5f));
    }

    // 一条被切成虚线的走廊，虚线之间留下实衔接。
    private static void dashChannel(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, height);
        float offset = width * 0.24f;
        float step = height * 0.32f;
        float angle = TemperaCutout.channelAxis(ctx);
        float[] steps = {-1.5f, -0.5f, 0.5f, 1.5f};
        List<float[]> slots = new ArrayList<>();
        for (int i = 0; i < steps.length; i++) {
            TemperaCutout.Pt centre = TemperaCutout.flowPoint(ctx, steps[i] * step, offset);
            slots.add(TemperaCutout.axisRect(centre.x, centre.y, step * 0.72f, unit * 0.22f, angle));
        }
        TemperaCutout.addCutField(ctx, palette.tone3, slots);
        for (int index = 0; index < slots.size(); index++) {
            TemperaCutout.addHoleLip(ctx, slots.get(index), 2.2f, 0.8f, 0.08f + index * 0.03f);
        }
    }

    // 车厢窗：圆角开口以固定节距依次掠过。
    private static void windowRun(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, height);
        float offset = width * 0.27f;
        // 节距必须让窗加唇边都过得去，否则这一行会读成一整条链状的格子。
        float step = height * 0.36f;
        float angle = TemperaCutout.channelAxis(ctx);
        float[] steps = {-1.5f, -0.5f, 0.5f, 1.5f};
        List<float[]> slots = new ArrayList<>();
        for (int i = 0; i < steps.length; i++) {
            TemperaCutout.Pt centre = TemperaCutout.flowPoint(ctx, steps[i] * step, offset);
            float size = unit * 0.2f;
            slots.add(TemperaCurves.rotatePolygon(
                    TemperaCurves.roundedRectPolygon(centre.x - size, centre.y - size * 0.62f,
                            size * 2f, size * 1.24f, size * 0.3f),
                    centre.x, centre.y, angle + (float) (Math.PI / 2)));
        }
        TemperaCutout.addCutField(ctx, palette.tone4, slots);
        for (int index = 0; index < slots.size(); index++) {
            TemperaCutout.addHoleLip(ctx, slots.get(index), 2.4f, 0.85f, 0.08f + index * 0.03f);
        }
    }

    // 本族最宽的走廊，被一条实带横穿。字骑在带上，于是镜头读作跨在背景之上的一座桥，
    // 而不是一块挖了洞的板。
    private static void bridgeSpan(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, height);
        float[] slot = channel(ctx, 0f, unit * 0.62f);
        TemperaCutout.addCutField(ctx, palette.tone4, List.of(slot));
        TemperaCutout.addHoleLip(ctx, slot, 2.6f, 0.8f, 0.08f);
        // 这条带特意比字需要的更厚：它顺着通道倾斜，而一条斜带在自身的端头会先削掉高度，
        // 字还没地方安全落脚。
        float[] band = TemperaCutout.axisRect(width / 2f, height / 2f, TemperaCutout.flowSpan(ctx),
                height * 0.46f, TemperaCutout.channelAxis(ctx) + (float) (Math.PI / 2));
        ctx.add(TemperaShapes.drawPolygonFill(band, palette.tone3, 0.95f, ctx.gradient),
                TemperaBlocks.BlockOptions.of().delay(0.12f).span(0.6f).enterDX(-width * 0.16f));
        ctx.add(TemperaShapes.drawPolygonOutline(band, palette.ink, 2f, 0.7f),
                TemperaBlocks.BlockOptions.of().delay(0.2f).span(0.5f).enterDX(width * 0.1f));
    }

    // 两条走廊，各自在对方畅通处被打断：衔接交替，于是这对读作编织而非两条平行槽。
    private static void braidChannel(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, height);
        float angle = TemperaCutout.channelAxis(ctx);
        float pitch = height * 0.42f;
        List<float[]> slots = new ArrayList<>();
        int[] sides = {-1, 1};
        for (int s = 0; s < sides.length; s++) {
            int side = sides[s];
            // 两侧错开半个节距就是全部诀窍：一条走廊被截断的地方，另一条正畅通无阻。
            for (int index = -1; index <= 1; index++) {
                TemperaCutout.Pt centre = TemperaCutout.flowPoint(ctx,
                        index * pitch + side * pitch * 0.5f, side * width * 0.32f);
                slots.add(TemperaCutout.axisRect(centre.x, centre.y, pitch * 0.78f, unit * 0.16f, angle));
            }
        }
        TemperaCutout.addCutField(ctx, palette.tone3, slots);
        for (int index = 0; index < slots.size(); index++) {
            TemperaCutout.addHoleLip(ctx, slots.get(index), 2.2f, 0.8f, 0.08f + index * 0.025f);
        }
    }

    // 一条走廊，两条栏杆上各刻满梯级——把走廊当成一把度量尺。
    private static void portLadder(TemperaCompositionContext ctx) {
        float width = ctx.width;
        float height = ctx.height;
        TemperaPalette.Palette palette = ctx.palette;
        float unit = Math.min(width, height);
        float offset = width * 0.3f;
        float[] slot = channel(ctx, offset, unit * 0.14f);
        TemperaCutout.addCutField(ctx, palette.tone2, List.of(slot));
        TemperaCutout.addHoleLip(ctx, slot, 2.2f, 0.8f, 0.08f);
        List<TemperaHatch.Line> rungs = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            float distance = (index - 4) * height * 0.13f;
            boolean longRung = index % 3 == 0;
            TemperaCutout.Pt inner = TemperaCutout.flowPoint(ctx, distance, offset - unit * 0.09f);
            TemperaCutout.Pt outer = TemperaCutout.flowPoint(ctx, distance,
                    offset - unit * (longRung ? 0.22f : 0.15f));
            rungs.add(new TemperaHatch.Line(inner.x, inner.y, outer.x, outer.y));
        }
        ctx.add(TemperaShapes.drawLines(rungs, palette.tone4, 2f, 0.7f),
                TemperaBlocks.BlockOptions.of().delay(0.14f).span(0.6f));
    }

    /** 注册本文件里的全部构图。 */
    public static void register(Map<String, TemperaCompositions.Drawer> into) {
        into.put("flow-channel", TemperaCorridorCompositions::flowChannel);
        into.put("twin-channel", TemperaCorridorCompositions::twinChannel);
        into.put("reed-run", TemperaCorridorCompositions::reedRun);
        into.put("taper-channel", TemperaCorridorCompositions::taperChannel);
        into.put("chain-ports", TemperaCorridorCompositions::chainPorts);
        into.put("dash-channel", TemperaCorridorCompositions::dashChannel);
        into.put("window-run", TemperaCorridorCompositions::windowRun);
        into.put("bridge-span", TemperaCorridorCompositions::bridgeSpan);
        into.put("braid-channel", TemperaCorridorCompositions::braidChannel);
        into.put("port-ladder", TemperaCorridorCompositions::portLadder);
    }
}
