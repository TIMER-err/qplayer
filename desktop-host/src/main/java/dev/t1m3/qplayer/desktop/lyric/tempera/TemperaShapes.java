package dev.t1m3.qplayer.desktop.lyric.tempera;

import java.util.ArrayList;
import java.util.List;

/**
 * 网点语汇的图形工厂，1:1 移植自 folia-major {@code tempera/temperaShapes.ts}。
 *
 * <p>每个工厂都返回一个「已完成、静态」的节点：播放期间只写 transform 与 alpha，
 * 从不再动几何。
 */
public final class TemperaShapes {
    private TemperaShapes() {
    }

    /** 锚点概念在 Skija 里由 {@link TemperaDraw.Graphic} 的 position/pivot 表达。 */
    private static TemperaDraw.Graphic node() {
        return new TemperaDraw.Graphic();
    }

    /** 与 folia 的 {@code array.map(d => ({x, y, radius}))} 等价的小工具。 */
    public static List<TemperaCurves.Disc> discs(float[] coords) {
        List<TemperaCurves.Disc> out = new ArrayList<>();
        for (int index = 0; index + 2 < coords.length; index += 3) {
            out.add(new TemperaCurves.Disc(coords[index], coords[index + 1], coords[index + 2]));
        }
        return out;
    }

    public static List<TemperaCurves.Disc> disc(float x, float y, float radius) {
        List<TemperaCurves.Disc> out = new ArrayList<>();
        out.add(new TemperaCurves.Disc(x, y, radius));
        return out;
    }

    public static List<TemperaHatch.Line> line(float x1, float y1, float x2, float y2) {
        List<TemperaHatch.Line> out = new ArrayList<>();
        out.add(new TemperaHatch.Line(x1, y1, x2, y2));
        return out;
    }

    // ------------------------------------------------------------------

    public static TemperaDraw.Graphic drawPolygonFill(float[] polygon, String color) {
        return drawPolygonFill(polygon, color, 1f, null);
    }

    public static TemperaDraw.Graphic drawPolygonFill(float[] polygon, String color, float alpha) {
        return drawPolygonFill(polygon, color, alpha, null);
    }

    public static TemperaDraw.Graphic drawPolygonFill(float[] polygon, String color, float alpha,
                                                      TemperaDraw.Gradient gradient) {
        if (polygon == null || polygon.length < 6) return node();
        return node().add(new TemperaDraw.Fill(polygon, null, color, alpha, gradient));
    }

    /**
     * 一个被挖了孔的填充多边形。孔是<b>真正透明</b>的：Pixi 画布跑在
     * {@code backgroundAlpha: 0} 上，所以透出来的是壳体里活的背景层。
     *
     * <p>Skija 侧用 EVEN_ODD 填充规则达成同样的效果——孔无论绕序如何都会被减掉。
     * 和 Pixi 一样，孔必须落在同一个节点上；孔的描边要用独立节点。
     */
    public static TemperaDraw.Graphic drawPolygonFillWithHoles(float[] polygon, List<float[]> holes,
                                                               String color, float alpha,
                                                               TemperaDraw.Gradient gradient) {
        if (polygon == null || polygon.length < 6) return node();
        return node().add(new TemperaDraw.Fill(polygon, holes, color, alpha, gradient));
    }

    public static TemperaDraw.Graphic drawPolygonOutline(float[] polygon, String color,
                                                         float width, float alpha) {
        if (polygon == null || polygon.length < 6) return node();
        return node().add(new TemperaDraw.Stroke(polygon, color, alpha, width));
    }

    /**
     * 用平行笔画填一个凸多边形。节点以形状中心为 pivot，于是调用方可以用水平缩放把它
     * 「张开」，而不用再碰几何。
     */
    public static TemperaDraw.Graphic drawHatchFill(float[] polygon, TemperaHatch.HatchSpec spec,
                                                    String color, float alpha) {
        if (polygon == null || polygon.length < 6) return node();
        List<TemperaHatch.Line> lines = TemperaHatch.buildHatchLines(polygon, spec);
        TemperaDraw.Graphic graphic = node();
        if (!lines.isEmpty()) graphic.add(new TemperaDraw.Segments(lines, color, alpha, spec.width));
        float[] center = TemperaDraw.boundsCenter(polygon);
        graphic.setPivot(center[0], center[1]).setPosition(center[0], center[1]);
        return graphic;
    }

    public static TemperaDraw.Graphic drawHatchFill(float[] polygon, TemperaHatch.HatchSpec spec,
                                                    String color) {
        return drawHatchFill(polygon, spec, color, 1f);
    }

    public static TemperaDraw.Graphic drawDiscs(List<TemperaCurves.Disc> discs, String color) {
        return drawDiscs(discs, color, 1f, null);
    }

    public static TemperaDraw.Graphic drawDiscs(List<TemperaCurves.Disc> discs, String color,
                                                float alpha) {
        return drawDiscs(discs, color, alpha, null);
    }

    public static TemperaDraw.Graphic drawDiscs(List<TemperaCurves.Disc> discs, String color,
                                                float alpha, TemperaDraw.Gradient gradient) {
        TemperaDraw.Graphic graphic = node();
        if (discs.isEmpty()) return graphic;
        graphic.add(new TemperaDraw.Discs(discs, color, alpha, 0f, false, gradient));
        return pivotOnSelf(graphic, discsCenter(discs));
    }

    public static TemperaDraw.Graphic drawRings(List<TemperaCurves.Disc> discs, String color,
                                                float width, float alpha) {
        TemperaDraw.Graphic graphic = node();
        if (discs.isEmpty()) return graphic;
        graphic.add(new TemperaDraw.Discs(discs, color, alpha, width, true, null));
        return pivotOnSelf(graphic, discsCenter(discs));
    }

    public static TemperaDraw.Graphic drawLines(List<TemperaHatch.Line> lines, String color,
                                                float width, float alpha) {
        TemperaDraw.Graphic graphic = node();
        if (lines.isEmpty()) return graphic;
        return graphic.add(new TemperaDraw.Segments(lines, color, alpha, width));
    }

    /** 折线：一条或多条。folia 版本只画一条，这里保留同语义并支持批量。 */
    public static TemperaDraw.Graphic drawPolyline(float[] points, String color,
                                                   float width, float alpha) {
        TemperaDraw.Graphic graphic = node();
        if (points == null || points.length < 4) return graphic;
        List<float[]> polylines = new ArrayList<>();
        polylines.add(points);
        return graphic.add(new TemperaDraw.Polylines(polylines, color, alpha, width));
    }

    public static TemperaDraw.Graphic drawPolylines(List<float[]> polylines, String color,
                                                    float width, float alpha) {
        TemperaDraw.Graphic graphic = node();
        if (polylines == null || polylines.isEmpty()) return graphic;
        return graphic.add(new TemperaDraw.Polylines(polylines, color, alpha, width));
    }

    /** 同心 45° 旋转框，描边粗细交替，最外圈最粗。 */
    public static TemperaDraw.Graphic drawConcentricDiamonds(float cx, float cy, float rx, float ry,
                                                             int rings, String color, float alpha) {
        TemperaDraw.Graphic graphic = node();
        int count = Math.max(1, rings);
        for (int ring = 0; ring < count; ring++) {
            float shrink = 1f - ring * 0.22f;
            float[] diamond = {
                    cx, cy - ry * shrink,
                    cx + rx * shrink, cy,
                    cx, cy + ry * shrink,
                    cx - rx * shrink, cy,
            };
            graphic.add(new TemperaDraw.Stroke(diamond, color, alpha, ring % 2 == 0 ? 3.5f : 1.4f));
        }
        return graphic.setPivot(cx, cy).setPosition(cx, cy);
    }

    /** 一列十字标记，每个由两条互相垂直的 45° 短线段组成。 */
    public static TemperaDraw.Graphic drawCrossMarks(List<TemperaHatch.DecorMark> marks,
                                                     String color, float width, float alpha) {
        TemperaDraw.Graphic graphic = node();
        if (marks.isEmpty()) return graphic;
        List<TemperaHatch.Line> lines = new ArrayList<>(marks.size() * 2);
        for (TemperaHatch.DecorMark mark : marks) {
            float cos = (float) (Math.cos(mark.rotation + Math.PI / 4) * mark.size);
            float sin = (float) (Math.sin(mark.rotation + Math.PI / 4) * mark.size);
            lines.add(new TemperaHatch.Line(mark.x - cos, mark.y - sin, mark.x + cos, mark.y + sin));
            lines.add(new TemperaHatch.Line(mark.x - sin, mark.y + cos, mark.x + sin, mark.y - cos));
        }
        return graphic.add(new TemperaDraw.Segments(lines, color, alpha, width));
    }

    /** 一列实心方点。 */
    public static TemperaDraw.Graphic drawSquareMarks(List<TemperaHatch.DecorMark> marks,
                                                      String color, float alpha) {
        TemperaDraw.Graphic graphic = node();
        if (marks.isEmpty()) return graphic;
        List<float[]> rects = new ArrayList<>(marks.size());
        for (TemperaHatch.DecorMark mark : marks) {
            rects.add(new float[]{
                    mark.x - mark.size / 2f,
                    mark.y - mark.size / 2f,
                    mark.size,
                    mark.size,
            });
        }
        return graphic.add(new TemperaDraw.Boxes(rects, color, alpha));
    }

    // ------------------------------------------------------------------

    private static TemperaDraw.Graphic pivotOnSelf(TemperaDraw.Graphic graphic, float[] center) {
        return graphic.setPivot(center[0], center[1]).setPosition(center[0], center[1]);
    }

    private static float[] discsCenter(List<TemperaCurves.Disc> discs) {
        if (discs.isEmpty()) return new float[]{0f, 0f};
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (TemperaCurves.Disc disc : discs) {
            minX = Math.min(minX, disc.x - disc.radius);
            maxX = Math.max(maxX, disc.x + disc.radius);
            minY = Math.min(minY, disc.y - disc.radius);
            maxY = Math.max(maxY, disc.y + disc.radius);
        }
        return new float[]{(minX + maxX) / 2f, (minY + maxY) / 2f};
    }
}
