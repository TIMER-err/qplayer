package dev.t1m3.qplayer.desktop.lyric.tempera;

import java.util.ArrayList;
import java.util.List;

/**
 * 圆滑语汇的纯几何生成器，1:1 移植自 folia-major {@code tempera/temperaCurves.ts}。
 *
 * <p>与 {@link TemperaHatch} 同一套约定：不用 Pixi、不用随机，只输出扁平多边形。
 * <b>凸性是硬约束</b>：{@code buildHatchLines} 靠半平面裁剪，只有 {@code ellipsePolygon}
 * 与 {@code roundedRectPolygon} 是凸的、可以交给网点填充；其余（花瓣、尖角、缺口、波边、
 * 缎带）都是凹多边形，只能填充与描边。
 */
public final class TemperaCurves {
    private TemperaCurves() {
    }

    private static final double TAU = Math.PI * 2;

    /** 构图空间里的一个圆。{@code drawDiscs}/{@code drawRings} 收这种数组。 */
    public static final class Disc {
        public final float x;
        public final float y;
        public final float radius;

        public Disc(float x, float y, float radius) {
            this.x = x;
            this.y = y;
            this.radius = radius;
        }
    }

    /** 凸。 */
    public static float[] ellipsePolygon(float cx, float cy, float rx, float ry, int segments) {
        int count = Math.max(8, segments);
        float[] points = new float[count * 2];
        for (int index = 0; index < count; index++) {
            double angle = (index / (double) count) * TAU;
            points[index * 2] = (float) (cx + Math.cos(angle) * rx);
            points[index * 2 + 1] = (float) (cy + Math.sin(angle) * ry);
        }
        return points;
    }

    /** 凸。贴纸板：四角为四分之一圆弧的矩形。 */
    public static float[] roundedRectPolygon(float x, float y, float width, float height,
                                             float radius, int cornerSegments) {
        float limit = Math.max(0f, Math.min(radius, Math.min(width, height) / 2f));
        int steps = Math.max(1, cornerSegments);
        float[][] corners = {
                {x + width - limit, y + limit, (float) (-Math.PI / 2)},
                {x + width - limit, y + height - limit, 0f},
                {x + limit, y + height - limit, (float) (Math.PI / 2)},
                {x + limit, y + limit, (float) Math.PI},
        };
        float[] points = new float[corners.length * (steps + 1) * 2];
        int cursor = 0;
        for (float[] corner : corners) {
            for (int step = 0; step <= steps; step++) {
                double angle = corner[2] + (step / (double) steps) * (Math.PI / 2);
                points[cursor++] = (float) (corner[0] + Math.cos(angle) * limit);
                points[cursor++] = (float) (corner[1] + Math.sin(angle) * limit);
            }
        }
        return points;
    }

    /** 绕枢轴旋转多边形。凸性保持，所以可网点填充的形状仍然可填。 */
    public static float[] rotatePolygon(float[] polygon, float cx, float cy, float angle) {
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float[] points = new float[polygon.length];
        for (int index = 0; index < polygon.length; index += 2) {
            float dx = polygon[index] - cx;
            float dy = polygon[index + 1] - cy;
            points[index] = cx + dx * cos - dy * sin;
            points[index + 1] = cy + dx * sin + dy * cos;
        }
        return points;
    }

    /**
     * 凹。带 {@code lobes} 个正弦凸起的轮廓，归一化后仍然正好落在 rx/ry 里。
     * 振幅小读作云或蕾丝徽章，大读作玫瑰花饰。
     */
    public static float[] lobedPolygon(float cx, float cy, float rx, float ry,
                                       float lobes, float amplitude, int segments) {
        int count = Math.max(24, segments);
        float petals = Math.max(1, Math.round(lobes));
        float swell = Math.max(0f, amplitude);
        float[] points = new float[count * 2];
        for (int index = 0; index < count; index++) {
            double angle = (index / (double) count) * TAU;
            float scale = (float) ((1 + Math.cos(angle * petals) * swell) / (1 + swell));
            points[index * 2] = (float) (cx + Math.cos(angle) * rx * scale);
            points[index * 2 + 1] = (float) (cy + Math.sin(angle) * ry * scale);
        }
        return points;
    }

    /**
     * 凹。一边平、另一边挂一排相切的半圆凸起——荷叶边。
     * 凸起半径是凸起宽度的一半，所以只靠数量就能定蕾丝的粗细。
     */
    public static float[] scallopBandPolygon(float x0, float x1, float flatY, float baseY,
                                             int bumps, int segmentsPerBump) {
        int count = Math.max(1, bumps);
        int steps = Math.max(3, segmentsPerBump);
        float span = (x1 - x0) / count;
        float radius = Math.abs(span) / 2f;
        float direction = baseY >= flatY ? 1f : -1f;
        List<Float> points = new ArrayList<>();
        points.add(x0);
        points.add(flatY);
        points.add(x1);
        points.add(flatY);
        // 从右向左走，让凸起在一条绕序里接到平边上。
        for (int bump = count - 1; bump >= 0; bump--) {
            float cx = x0 + span * (bump + 0.5f);
            for (int step = 0; step <= steps; step++) {
                double angle = (step / (double) steps) * Math.PI;
                points.add((float) (cx + Math.cos(angle) * (span / 2f)));
                points.add((float) (baseY + direction * Math.sin(angle) * radius));
            }
        }
        return toArray(points);
    }

    /** 凹。经典参数心形，采样后归一化进 rx/ry 盒子里。 */
    public static float[] heartPolygon(float cx, float cy, float rx, float ry, int segments) {
        int count = Math.max(16, segments);
        float[] raw = new float[count * 2];
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int index = 0; index < count; index++) {
            double t = (index / (double) count) * TAU;
            float x = (float) (Math.pow(Math.sin(t), 3) * 16);
            // 取负：曲线本为数学坐标系（y 向上）写的，构图画在屏幕坐标系（y 向下）。
            float y = (float) -(13 * Math.cos(t) - 5 * Math.cos(2 * t)
                    - 2 * Math.cos(3 * t) - Math.cos(4 * t));
            raw[index * 2] = x;
            raw[index * 2 + 1] = y;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        float halfWidth = Math.max(1e-6f, (maxX - minX) / 2f);
        float halfHeight = Math.max(1e-6f, (maxY - minY) / 2f);
        float midX = (minX + maxX) / 2f;
        float midY = (minY + maxY) / 2f;
        float[] points = new float[raw.length];
        for (int index = 0; index < raw.length; index += 2) {
            points[index] = cx + ((raw[index] - midX) / halfWidth) * rx;
            points[index + 1] = cy + ((raw[index + 1] - midY) / halfHeight) * ry;
        }
        return points;
    }

    /**
     * 凹。环的一段，从 startAngle 到 endAngle。一圈这样的扇形留下的辐条与轮毂，
     * 正是一条环从板上被冲掉而中间没掉下来的做法。
     */
    public static float[] annularSectorPolygon(float cx, float cy, float innerRadius,
                                               float outerRadius, float startAngle,
                                               float endAngle, int segments) {
        int steps = Math.max(2, segments);
        float[] points = new float[(steps + 1) * 4];
        int cursor = 0;
        for (int step = 0; step <= steps; step++) {
            double angle = startAngle + ((endAngle - startAngle) * step) / steps;
            points[cursor++] = (float) (cx + Math.cos(angle) * outerRadius);
            points[cursor++] = (float) (cy + Math.sin(angle) * outerRadius);
        }
        for (int step = steps; step >= 0; step--) {
            double angle = startAngle + ((endAngle - startAngle) * step) / steps;
            points[cursor++] = (float) (cx + Math.cos(angle) * innerRadius);
            points[cursor++] = (float) (cy + Math.sin(angle) * innerRadius);
        }
        return points;
    }

    /** 凹。四个尖角配很细的腰是 VN 的闪光；尖角更多就读作星形。 */
    public static float[] starPolygon(float cx, float cy, float outerRadius, float innerRadius,
                                      int points, float rotation) {
        int tips = Math.max(3, points);
        float[] polygon = new float[tips * 4];
        int cursor = 0;
        for (int index = 0; index < tips * 2; index++) {
            float radius = index % 2 == 0 ? outerRadius : innerRadius;
            double angle = rotation - Math.PI / 2 + (index / (double) (tips * 2)) * TAU;
            polygon[cursor++] = (float) (cx + Math.cos(angle) * radius);
            polygon[cursor++] = (float) (cy + Math.sin(angle) * radius);
        }
        return polygon;
    }

    /**
     * 凹。中线沿半个正弦下垂、两端切平的带子；可以跑出画面两侧却仍读作一条弯缎带。
     */
    public static float[] arcRibbonPolygon(float x0, float x1, float y, float thickness,
                                           float sag, int steps) {
        int count = Math.max(4, steps);
        float half = thickness / 2f;
        float[] top = new float[(count + 1) * 2];
        float[] bottom = new float[(count + 1) * 2];
        for (int index = 0; index <= count; index++) {
            float progress = index / (float) count;
            float x = x0 + (x1 - x0) * progress;
            float centre = (float) (y + Math.sin(progress * Math.PI) * sag);
            top[index * 2] = x;
            top[index * 2 + 1] = centre - half;
            bottom[index * 2] = x;
            bottom[index * 2 + 1] = centre + half;
        }
        float[] points = new float[top.length + bottom.length];
        System.arraycopy(top, 0, points, 0, top.length);
        int cursor = top.length;
        for (int index = bottom.length - 2; index >= 0; index -= 2) {
            points[cursor++] = bottom[index];
            points[cursor++] = bottom[index + 1];
        }
        return points;
    }

    /**
     * 抖动点阵上的一片圆盘场。晶格保证覆盖均匀，逐格抖动才让它不像一张点阵网格。
     */
    public static List<Disc> buildDiscField(int seed, int salt, float width, float height,
                                            int count, float radius) {
        List<Disc> discs = new ArrayList<>();
        int total = Math.max(0, count);
        if (total == 0 || width <= 0 || height <= 0) return discs;
        int columns = Math.max(1, Math.round((float) Math.sqrt(total * (width / height))));
        int rows = Math.max(1, (int) Math.ceil(total / (double) columns));
        float cellWidth = width / columns;
        float cellHeight = height / rows;
        for (int index = 0; index < total; index++) {
            int column = index % columns;
            int row = index / columns;
            discs.add(new Disc(
                    cellWidth * (column + 0.15f + (float) TemperaRandom.hash01(seed, index, salt) * 0.7f),
                    cellHeight * (row + 0.15f + (float) TemperaRandom.hash01(seed, index, salt + 3) * 0.7f),
                    radius * (0.55f + (float) TemperaRandom.hash01(seed, index, salt + 7) * 0.75f)));
        }
        return discs;
    }

    private static float[] toArray(List<Float> values) {
        float[] out = new float[values.size()];
        for (int index = 0; index < out.length; index++) out[index] = values.get(index);
        return out;
    }

    // ------------------------------------------------------------------
    // TS 默认参数的重载（folia 里这些参数带默认值：ellipse=32、roundedRect=6、
    // lobed=96、scallop=10、heart=64、annularSector=10、star.rotation=0、arcRibbon=28）
    // ------------------------------------------------------------------

    public static float[] ellipsePolygon(float cx, float cy, float rx, float ry) {
        return ellipsePolygon(cx, cy, rx, ry, 32);
    }

    public static float[] roundedRectPolygon(float x, float y, float width, float height,
                                             float radius) {
        return roundedRectPolygon(x, y, width, height, radius, 6);
    }

    public static float[] lobedPolygon(float cx, float cy, float rx, float ry,
                                       float lobes, float amplitude) {
        return lobedPolygon(cx, cy, rx, ry, lobes, amplitude, 96);
    }

    public static float[] scallopBandPolygon(float x0, float x1, float flatY, float baseY,
                                             int bumps) {
        return scallopBandPolygon(x0, x1, flatY, baseY, bumps, 10);
    }

    public static float[] heartPolygon(float cx, float cy, float rx, float ry) {
        return heartPolygon(cx, cy, rx, ry, 64);
    }

    public static float[] annularSectorPolygon(float cx, float cy, float innerRadius,
                                               float outerRadius, float startAngle,
                                               float endAngle) {
        return annularSectorPolygon(cx, cy, innerRadius, outerRadius, startAngle, endAngle, 10);
    }

    public static float[] starPolygon(float cx, float cy, float outerRadius, float innerRadius,
                                      int points) {
        return starPolygon(cx, cy, outerRadius, innerRadius, points, 0f);
    }

    public static float[] arcRibbonPolygon(float x0, float x1, float y, float thickness,
                                           float sag) {
        return arcRibbonPolygon(x0, x1, y, thickness, sag, 28);
    }
}
