package dev.t1m3.qplayer.lyric.tempera;

import java.util.ArrayList;
import java.util.List;

/**
 * 网点图形语言的纯种子生成器，1:1 移植自 folia-major {@code tempera/temperaHatch.ts}。
 *
 * <p>斜线网点填充、手绘感涂鸦折线、重复装饰行，全部只依赖种子，不使用随机数。
 */
public final class TemperaHatch {
    private TemperaHatch() {
    }

    private static final double TAU = Math.PI * 2;
    private static final int MAX_HATCH_LINES = 320;

    /** 斜线网点的角度/间距/线宽三元组。 */
    public static final class HatchSpec {
        /** 弧度；形状内部斜线的方向。 */
        public final float angle;
        /** 两条平行笔画之间的距离（像素）。 */
        public final float spacing;
        /** 笔画宽度（像素）。 */
        public final float width;

        public HatchSpec(float angle, float spacing, float width) {
            this.angle = angle;
            this.spacing = spacing;
            this.width = width;
        }

        public HatchSpec withAngle(float newAngle) {
            return new HatchSpec(newAngle, spacing, width);
        }

        public HatchSpec withSpacing(float newSpacing) {
            return new HatchSpec(angle, newSpacing, width);
        }
    }

    /** 一条线段。 */
    public static final class Line {
        public final float x1;
        public final float y1;
        public final float x2;
        public final float y2;

        public Line(float x1, float y1, float x2, float y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }
    }

    /** 一个带尺寸与旋转的装饰标记。 */
    public static final class DecorMark {
        public final float x;
        public final float y;
        public final float size;
        public final float rotation;

        public DecorMark(float x, float y, float size, float rotation) {
            this.x = x;
            this.y = y;
            this.size = size;
            this.rotation = rotation;
        }
    }

    /**
     * 从种子挑出斜线角度/间距/线宽。密度档位故意做得很粗，这样同一个形状读起来是
     * 一个调子，而不是摩尔纹。
     */
    public static HatchSpec buildHatchSpec(int seed, int salt) {
        return buildHatchSpec(seed, salt, 1f);
    }

    public static HatchSpec buildHatchSpec(int seed, int salt, float scale) {
        int angleIndex = (int) Math.floor(TemperaRandom.hash01(seed, 1, salt) * 4);
        float[] angles = {(float) (-Math.PI / 4), (float) (Math.PI / 4),
                (float) (-Math.PI / 3), (float) (Math.PI / 6)};
        float angle = angleIndex >= 0 && angleIndex < angles.length ? angles[angleIndex] : (float) (Math.PI / 4);
        int density = (int) Math.floor(TemperaRandom.hash01(seed, 2, salt) * 3);
        float[] spacings = {9f, 13f, 18f};
        float spacing = (density >= 0 && density < spacings.length ? spacings[density] : 13f) * scale;
        float width = Math.max(0.8f, spacing * (0.16f + (float) TemperaRandom.hash01(seed, 3, salt) * 0.14f));
        return new HatchSpec(angle, spacing, width);
    }

    public static float[] rectPolygon(float x, float y, float width, float height) {
        return new float[]{x, y, x + width, y, x + width, y + height, x, y + height};
    }

    /** 圆的凸多边形近似，让网点裁剪器也能填圆形窗口。 */
    public static float[] circlePolygon(float cx, float cy, float radius) {
        return circlePolygon(cx, cy, radius, 28);
    }

    public static float[] circlePolygon(float cx, float cy, float radius, int segments) {
        int count = Math.max(6, Math.round(segments));
        float[] points = new float[count * 2];
        for (int index = 0; index < count; index++) {
            double angle = (index / (double) count) * TAU;
            points[index * 2] = (float) (cx + Math.cos(angle) * radius);
            points[index * 2 + 1] = (float) (cy + Math.sin(angle) * radius);
        }
        return points;
    }

    public static float[] diamondPolygon(float cx, float cy, float rx, float ry) {
        return new float[]{cx, cy - ry, cx + rx, cy, cx, cy + ry, cx - rx, cy};
    }

    /**
     * 用边半平面求交，把一条无限直线裁到凸多边形里；直线完全错过形状时返回 null。
     */
    private static Line clipToConvex(float[] polygon, float ax, float ay,
                                     float dx, float dy, float span) {
        int count = polygon.length / 2;
        float cx = 0f;
        float cy = 0f;
        for (int index = 0; index < count; index++) {
            cx += polygon[index * 2];
            cy += polygon[index * 2 + 1];
        }
        cx /= count;
        cy /= count;

        float tMin = -span;
        float tMax = span;
        for (int index = 0; index < count; index++) {
            float x0 = polygon[index * 2];
            float y0 = polygon[index * 2 + 1];
            float x1 = polygon[((index + 1) % count) * 2];
            float y1 = polygon[((index + 1) % count) * 2 + 1];
            float nx = y1 - y0;
            float ny = -(x1 - x0);
            // 用质心把法线掰向外侧。
            if (nx * (cx - x0) + ny * (cy - y0) > 0) {
                nx = -nx;
                ny = -ny;
            }
            float denominator = nx * dx + ny * dy;
            float numerator = nx * (ax - x0) + ny * (ay - y0);
            if (Math.abs(denominator) < 1e-9f) {
                if (numerator > 0) return null;
                continue;
            }
            float t = -numerator / denominator;
            if (denominator > 0) tMax = Math.min(tMax, t);
            else tMin = Math.max(tMin, t);
            if (tMin > tMax) return null;
        }
        if (tMax - tMin < 0.5f) return null;
        return new Line(ax + dx * tMin, ay + dy * tMin, ax + dx * tMax, ay + dy * tMax);
    }

    /**
     * 用平行笔画填一个凸多边形；{@code coverage}（0..1）会裁掉两端，
     * 于是填充可以在镜头入场动画里从形状中心「张开」。
     */
    public static List<Line> buildHatchLines(float[] polygon, HatchSpec spec) {
        return buildHatchLines(polygon, spec, 1f);
    }

    public static List<Line> buildHatchLines(float[] polygon, HatchSpec spec, float coverage) {
        List<Line> lines = new ArrayList<>();
        if (polygon.length < 6 || spec.spacing <= 0) return lines;
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int index = 0; index < polygon.length; index += 2) {
            minX = Math.min(minX, polygon[index]);
            maxX = Math.max(maxX, polygon[index]);
            minY = Math.min(minY, polygon[index + 1]);
            maxY = Math.max(maxY, polygon[index + 1]);
        }
        float centerX = (minX + maxX) / 2f;
        float centerY = (minY + maxY) / 2f;
        float diagonal = (float) Math.hypot(maxX - minX, maxY - minY);
        if (diagonal <= 0) return lines;

        float dx = (float) Math.cos(spec.angle);
        float dy = (float) Math.sin(spec.angle);
        // 垂直于笔画方向步进，扫过整个形状对角线。
        float px = -dy;
        float py = dx;
        // 扫描从形状中心往两边走，所以半个对角线就够了。
        int steps = Math.min(MAX_HATCH_LINES / 2,
                (int) Math.ceil(diagonal / (spec.spacing * 2)) + 2);
        float clampedCoverage = Math.min(1f, Math.max(0f, coverage));
        for (int step = -steps; step <= steps; step++) {
            float offset = step * spec.spacing;
            Line clipped = clipToConvex(polygon,
                    centerX + px * offset, centerY + py * offset, dx, dy, diagonal);
            if (clipped == null) continue;
            if (clampedCoverage >= 1f) {
                lines.add(clipped);
                continue;
            }
            float midX = (clipped.x1 + clipped.x2) / 2f;
            float midY = (clipped.y1 + clipped.y2) / 2f;
            lines.add(new Line(
                    midX + (clipped.x1 - midX) * clampedCoverage,
                    midY + (clipped.y1 - midY) * clampedCoverage,
                    midX + (clipped.x2 - midX) * clampedCoverage,
                    midY + (clipped.y2 - midY) * clampedCoverage));
        }
        return lines;
    }

    /**
     * 不用贴图做手绘感：一条带抖动的螺旋折线，读起来像随手绕出来的圈。
     */
    public static float[] buildScribblePath(int seed, int salt, float cx, float cy,
                                            float radius, float turns) {
        int perTurn = 13;
        int total = Math.max(perTurn, Math.round(perTurn * Math.max(1f, turns)));
        float[] points = new float[(total + 1) * 2];
        int cursor = 0;
        for (int index = 0; index <= total; index++) {
            float progress = index / (float) total;
            double angle = progress * TAU * Math.max(1f, turns);
            float jitterR = (float) ((TemperaRandom.hash01(seed, index, salt) - 0.5) * radius * 0.26);
            float jitterA = (float) ((TemperaRandom.hash01(seed, index, salt + 7) - 0.5) * 0.22);
            float currentRadius = radius * (0.52f + 0.48f * progress) + jitterR;
            points[cursor++] = (float) (cx + Math.cos(angle + jitterA) * currentRadius);
            points[cursor++] = (float) (cy + Math.sin(angle + jitterA) * currentRadius * 0.82f);
        }
        return points;
    }

    /** 海报构图底边用的起伏水平边。 */
    public static float[] buildWavyPath(int seed, int salt, float x0, float x1, float y,
                                        float amplitude) {
        return buildWavyPath(seed, salt, x0, x1, y, amplitude, 24);
    }

    public static float[] buildWavyPath(int seed, int salt, float x0, float x1, float y,
                                        float amplitude, int steps) {
        int safeSteps = Math.max(2, steps);
        float[] points = new float[(safeSteps + 1) * 2];
        int cursor = 0;
        for (int index = 0; index <= safeSteps; index++) {
            float progress = index / (float) safeSteps;
            double wave = Math.sin(progress * TAU * 1.5 + TemperaRandom.hash01(seed, 0, salt) * TAU);
            float jitter = (float) ((TemperaRandom.hash01(seed, index, salt) - 0.5) * amplitude * 0.5);
            points[cursor++] = x0 + (x1 - x0) * progress;
            points[cursor++] = (float) (y + wave * amplitude + jitter);
        }
        return points;
    }

    /** 沿某个方向等距排开的标记，每个带种子抖动，让这一行永远不像印上去的。 */
    private static List<DecorMark> buildMarkRow(int seed, int salt, float x, float y,
                                                int count, float spacing, float size, float angle) {
        List<DecorMark> marks = new ArrayList<>();
        int total = Math.max(0, count);
        float dx = (float) Math.cos(angle);
        float dy = (float) Math.sin(angle);
        for (int index = 0; index < total; index++) {
            float jitter = (float) ((TemperaRandom.hash01(seed, index, salt) - 0.5) * spacing * 0.16);
            float distance = index * spacing + jitter;
            marks.add(new DecorMark(
                    x + dx * distance,
                    y + dy * distance,
                    (float) (size * (0.82 + TemperaRandom.hash01(seed, index, salt + 3) * 0.36)),
                    (float) ((TemperaRandom.hash01(seed, index, salt + 11) - 0.5) * 0.3)));
        }
        return marks;
    }

    public static List<DecorMark> buildCrossRow(int seed, int salt, float x, float y,
                                                int count, float spacing, float size) {
        return buildMarkRow(seed, salt, x, y, count, spacing, size, 0f);
    }

    public static List<DecorMark> buildCrossRow(int seed, int salt, float x, float y,
                                                int count, float spacing, float size, float angle) {
        return buildMarkRow(seed, salt, x, y, count, spacing, size, angle);
    }

    public static List<DecorMark> buildCrossRow(int seed, int salt, float x, float y,
                                                int count, float spacing) {
        return buildMarkRow(seed, salt, x, y, count, spacing, 9f, 0f);
    }

    public static List<DecorMark> buildDotRow(int seed, int salt, float x, float y,
                                              int count, float spacing, float size) {
        return buildMarkRow(seed, salt, x, y, count, spacing, size, (float) (Math.PI / 2));
    }

    public static List<DecorMark> buildDotRow(int seed, int salt, float x, float y,
                                              int count, float spacing, float size, float angle) {
        return buildMarkRow(seed, salt, x, y, count, spacing, size, angle);
    }

    public static List<DecorMark> buildDotRow(int seed, int salt, float x, float y,
                                              int count, float spacing) {
        return buildMarkRow(seed, salt, x, y, count, spacing, 4f, (float) (Math.PI / 2));
    }

    /** 整场画面背后那层淡淡的纸纹网点用的等距点阵。 */
    public static List<DecorMark> buildDotGrid(float width, float height, float spacing) {
        return buildDotGrid(width, height, spacing, 1.6f);
    }

    public static List<DecorMark> buildDotGrid(float width, float height, float spacing, float size) {
        List<DecorMark> marks = new ArrayList<>();
        if (spacing <= 0 || width <= 0 || height <= 0) return marks;
        int columns = Math.min(200, (int) Math.ceil(width / spacing) + 1);
        int rows = Math.min(200, (int) Math.ceil(height / spacing) + 1);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                // 隔行错开半格，让点阵读作半调网点而不是方格纸。
                marks.add(new DecorMark(
                        column * spacing + (row % 2 == 0 ? 0f : spacing / 2f),
                        row * spacing,
                        size,
                        0f));
            }
        }
        return marks;
    }

    /** 一至三条跑出视口边缘的浅斜线，用来把构图拎起来。 */
    public static List<Line> buildCrossingLines(int seed, int salt, float width, float height,
                                                int count) {
        int total = Math.min(3, Math.max(0, count));
        List<Line> lines = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            float anchorY = (float) (height * (0.18 + TemperaRandom.hash01(seed, index, salt) * 0.64));
            float sign = TemperaRandom.hash01(seed, index, salt + 5) > 0.5 ? 1f : -1f;
            float angle = (float) (sign * (0.07 + TemperaRandom.hash01(seed, index, salt + 9) * 0.11));
            // 远伸出两侧边缘：图层会跟着构图一起滑动，停在画框里的线会明显脱开。
            float reach = width * 0.9f;
            lines.add(new Line(
                    -width * 0.3f,
                    (float) (anchorY - Math.tan(angle) * reach),
                    width * 1.3f,
                    (float) (anchorY + Math.tan(angle) * reach)));
        }
        return lines;
    }
}
