package dev.t1m3.qplayer.desktop.lyric.tempera;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.PathFillMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * 凝彩的绘制层，对应 folia-major 里的 Pixi {@code Graphics} 节点模型。
 *
 * <p>folia 的构图只负责产出<b>静态、完备</b>的 Graphics 节点；播放期间只写
 * transform 与 alpha，从不改几何。这里保持同一契约：
 * {@link Graphic} 持有几何（若干 {@link Shape}）以及 position / pivot / scale /
 * rotation / alpha，播放层（{@link TemperaBlocks}）每帧只改这些字段，
 * {@link #paint(Canvas)} 再把 Pixi 的
 * {@code world = parentWorld · translate(pos) · rotate · scale · translate(-pivot)}
 * 用 Skija 的 canvas 变换原样重放。
 */
public final class TemperaDraw {
    private TemperaDraw() {
    }

    /** 四色线性渐变外加它跑的方向，由 gradient 色彩模式提供。 */
    public static final class Gradient {
        public final String[] colors;
        public final float angle;

        public Gradient(String[] colors, float angle) {
            this.colors = colors;
            this.angle = angle;
        }
    }

    /** 一个绘制指令。{@code alpha} 是父链累积下来的不透明度。 */
    public interface Shape {
        void close();
        void paint(Canvas canvas, float alpha);
    }

    // ------------------------------------------------------------------
    // 绘制指令实现
    // ------------------------------------------------------------------

    /** 多边形填充，可带真正透明的挖孔（构图板被冲穿的地方透出壳体实时背景）。 */
    public static final class Fill implements Shape {
        private final Path path;
        private final Paint paint;
        private final boolean gradient;

        public Fill(float[] polygon, List<float[]> holes, String color, float alpha,
                    Gradient gradientSpec) {
            boolean hasHoles = holes != null && !holes.isEmpty();
            PathBuilder builder = hasHoles
                    ? new PathBuilder(PathFillMode.EVEN_ODD) : new PathBuilder();
            builder.addPolygon(polygon, true);
            if (hasHoles) {
                for (float[] hole : holes) {
                    if (hole.length >= 6) builder.addPolygon(hole, true);
                }
            }
            path = builder.detach();
            builder.close();
            paint = new Paint().setAntiAlias(true).setMode(PaintMode.FILL);
            boolean useGradient = gradientSpec != null && gradientSpec.colors != null
                    && gradientSpec.colors.length > 1;
            if (useGradient) {
                Shader shader = buildGradient(polygon, gradientSpec, color);
                if (shader != null) {
                    paint.setShader(shader);
                    shader.close();
                }
                else useGradient = false;
            }
            if (!useGradient) paint.setColor(TemperaColor.withAlpha(color, 1f));
            this.gradient = useGradient;
            paint.setAlphaf(alpha);
        }

        @Override
        public void close() {
            paint.close();
            path.close();
        }

        @Override
        public void paint(Canvas canvas, float alpha) {
            paint.setAlphaf(alpha);
            canvas.drawPath(path, paint);
        }

        public boolean isGradient() {
            return gradient;
        }
    }

    /** 多边形描边。 */
    public static final class Stroke implements Shape {
        private final Path path;
        private final Paint paint;

        public Stroke(float[] polygon, String color, float alpha, float width) {
            PathBuilder builder = new PathBuilder();
            builder.addPolygon(polygon, true);
            path = builder.detach();
            builder.close();
            paint = new Paint()
                    .setAntiAlias(true)
                    .setMode(PaintMode.STROKE)
                    .setStrokeWidth(Math.max(0.05f, width))
                    .setStrokeCap(PaintStrokeCap.BUTT)
                    .setColor(TemperaColor.withAlpha(color, 1f))
                    .setAlphaf(alpha);
        }

        @Override
        public void close() {
            paint.close();
            path.close();
        }

        @Override
        public void paint(Canvas canvas, float alpha) {
            paint.setAlphaf(alpha);
            canvas.drawPath(path, paint);
        }
    }

    /** 一组线段（网点填充、装饰行、交叉线共用）。 */
    public static final class Segments implements Shape {
        private final float[] coords;
        private final Paint paint;

        public Segments(List<TemperaHatch.Line> lines, String color, float alpha, float width) {
            coords = new float[lines.size() * 4];
            int cursor = 0;
            for (TemperaHatch.Line line : lines) {
                coords[cursor++] = line.x1;
                coords[cursor++] = line.y1;
                coords[cursor++] = line.x2;
                coords[cursor++] = line.y2;
            }
            paint = new Paint()
                    .setAntiAlias(true)
                    .setMode(PaintMode.STROKE)
                    .setStrokeWidth(Math.max(0.05f, width))
                    .setColor(TemperaColor.withAlpha(color, 1f))
                    .setAlphaf(alpha);
        }

        @Override
        public void close() {
            paint.close();
        }

        @Override
        public void paint(Canvas canvas, float alpha) {
            if (coords.length < 4) return;
            paint.setAlphaf(alpha);
            canvas.drawLines(coords, paint);
        }
    }

    /** 一组折线（涂鸦、波边、缎带轮廓）。 */
    public static final class Polylines implements Shape {
        private final List<Path> paths = new ArrayList<>();
        private final Paint paint;

        public Polylines(List<float[]> polylines, String color, float alpha, float width) {
            for (float[] points : polylines) {
                if (points.length < 4) continue;
                PathBuilder builder = new PathBuilder();
                builder.moveTo(points[0], points[1]);
                for (int index = 2; index + 1 < points.length; index += 2) {
                    builder.lineTo(points[index], points[index + 1]);
                }
                paths.add(builder.detach());
                builder.close();
            }
            paint = new Paint()
                    .setAntiAlias(true)
                    .setMode(PaintMode.STROKE)
                    .setStrokeWidth(Math.max(0.05f, width))
                    .setColor(TemperaColor.withAlpha(color, 1f))
                    .setAlphaf(alpha);
        }

        @Override
        public void close() {
            paint.close();
            paths.forEach(Path::close);
            paths.clear();
        }

        @Override
        public void paint(Canvas canvas, float alpha) {
            if (paths.isEmpty()) return;
            paint.setAlphaf(alpha);
            for (Path path : paths) canvas.drawPath(path, paint);
        }
    }

    /** 一组填充矩形（方形装饰标记）。 */
    public static final class Boxes implements Shape {
        private final float[] rects;
        private final Paint paint;

        public Boxes(List<float[]> rects, String color, float alpha) {
            this.rects = new float[rects.size() * 4];
            int cursor = 0;
            for (float[] rect : rects) {
                this.rects[cursor++] = rect[0];
                this.rects[cursor++] = rect[1];
                this.rects[cursor++] = rect[2];
                this.rects[cursor++] = rect[3];
            }
            paint = new Paint()
                    .setAntiAlias(true)
                    .setMode(PaintMode.FILL)
                    .setColor(TemperaColor.withAlpha(color, 1f))
                    .setAlphaf(alpha);
        }

        @Override
        public void close() {
            paint.close();
        }

        @Override
        public void paint(Canvas canvas, float alpha) {
            if (rects.length < 4) return;
            paint.setAlphaf(alpha);
            for (int index = 0; index + 3 < rects.length; index += 4) {
                canvas.drawRect(Rect.makeLTRB(rects[index], rects[index + 1],
                        rects[index] + rects[index + 2], rects[index + 1] + rects[index + 3]), paint);
            }
        }
    }

    /**
     * 一整个圆盘场当作一个节点：气泡簇必须作为一个整体入场与呼吸，每个气泡一个
     * Graphics 会把十几条记录塞进色块动画器而不是一条。以场中心为 pivot，
     * 所以 {@code drift} 读起来是呼吸而不是平移。
     */
    public static final class Discs implements Shape {
        private final Path path;
        private final Paint paint;
        private final float[] bounds;
        private final boolean gradient;

        public Discs(List<TemperaCurves.Disc> discs, String color, float alpha,
                     float strokeWidth, boolean stroked, Gradient gradientSpec) {
            PathBuilder builder = new PathBuilder();
            for (TemperaCurves.Disc disc : discs) {
                if (disc.radius <= 0) continue;
                builder.addCircle(disc.x, disc.y, Math.max(0.5f, disc.radius));
            }
            path = builder.detach();
            builder.close();
            bounds = discsBounds(discs);
            paint = new Paint().setAntiAlias(true);
            if (stroked) {
                paint.setMode(PaintMode.STROKE).setStrokeWidth(Math.max(0.05f, strokeWidth));
            } else {
                paint.setMode(PaintMode.FILL);
            }
            boolean useGradient = !stroked && gradientSpec != null && gradientSpec.colors != null
                    && gradientSpec.colors.length > 1;
            if (useGradient) {
                Shader shader = buildGradientRect(bounds, gradientSpec, color);
                if (shader != null) {
                    paint.setShader(shader);
                    shader.close();
                }
                else useGradient = false;
            }
            if (!useGradient) paint.setColor(TemperaColor.withAlpha(color, 1f));
            this.gradient = useGradient;
            paint.setAlphaf(alpha);
        }

        @Override
        public void close() {
            paint.close();
            path.close();
        }

        @Override
        public void paint(Canvas canvas, float alpha) {
            paint.setAlphaf(alpha);
            canvas.drawPath(path, paint);
        }

        public boolean isGradient() {
            return gradient;
        }
    }

    // ------------------------------------------------------------------
    // 几何辅助
    // ------------------------------------------------------------------

    /** 多边形包围盒中心；{@code drawHatchFill} 用它把节点绕自身摊开。 */
    public static float[] boundsCenter(float[] polygon) {
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int index = 0; index + 1 < polygon.length; index += 2) {
            minX = Math.min(minX, polygon[index]);
            maxX = Math.max(maxX, polygon[index]);
            minY = Math.min(minY, polygon[index + 1]);
            maxY = Math.max(maxY, polygon[index + 1]);
        }
        return new float[]{(minX + maxX) / 2f, (minY + maxY) / 2f};
    }

    public static float[] polygonBounds(float[] polygon) {
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int index = 0; index + 1 < polygon.length; index += 2) {
            minX = Math.min(minX, polygon[index]);
            maxX = Math.max(maxX, polygon[index]);
            minY = Math.min(minY, polygon[index + 1]);
            maxY = Math.max(maxY, polygon[index + 1]);
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    private static float[] discsBounds(List<TemperaCurves.Disc> discs) {
        if (discs.isEmpty()) return new float[]{0, 0, 0, 0};
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
        return new float[]{minX, minY, maxX, maxY};
    }

    private static Shader buildGradient(float[] polygon, Gradient gradient, String tone) {
        return buildGradientRect(polygonBounds(polygon), gradient, tone);
    }

    /**
     * gradient 模式下形状用整条四色坡道而不是单一调子填充。每个 stop 都被往构图要求的
     * 色调拉近一半，于是坡道带着封面的色相，而形状仍然保有它在构图里需要的明度。
     *
     * <p>Pixi 侧用的是 {@code textureSpace: 'local'}，即 0..1 坐标是相对形状自身包围盒的，
     * 所以这里把 start/end 映射到包围盒矩形上。
     */
    private static Shader buildGradientRect(float[] bounds, Gradient gradient, String tone) {
        if (gradient.colors.length < 2) return null;
        float width = bounds[2] - bounds[0];
        float height = bounds[3] - bounds[1];
        if (width <= 0 || height <= 0) return null;
        int count = gradient.colors.length;
        int[] colors = new int[count];
        float[] positions = new float[count];
        for (int index = 0; index < count; index++) {
            positions[index] = count > 1 ? index / (float) (count - 1) : 0f;
            colors[index] = TemperaColor.mixColors(gradient.colors[index], tone, 0.5f, 1f);
        }
        float half = 0.5f;
        float dx = (float) Math.cos(gradient.angle) * half;
        float dy = (float) Math.sin(gradient.angle) * half;
        float x0 = bounds[0] + (half - dx) * width;
        float y0 = bounds[1] + (half - dy) * height;
        float x1 = bounds[0] + (half + dx) * width;
        float y1 = bounds[1] + (half + dy) * height;
        if (x0 == x1 && y0 == y1) return null;
        return Shader.makeLinearGradient(x0, y0, x1, y1, colors, positions);
    }

    // ------------------------------------------------------------------
    // 节点
    // ------------------------------------------------------------------

    /**
     * 一个可变换的绘制节点。空几何的实例同时充当 Pixi 里的 {@code Container}（分组），
     * 供海报类构图把倾斜子组挂在上面。
     */
    public static final class Graphic {
        public float x;
        public float y;
        public float pivotX;
        public float pivotY;
        public float scaleX = 1f;
        public float scaleY = 1f;
        /** 弧度。 */
        public float rotation;
        public float alpha = 1f;
        public boolean visible = true;
        public Graphic parent;

        private final List<Shape> shapes = new ArrayList<>();
        private final List<Graphic> children = new ArrayList<>();

        public void close() {
            shapes.forEach(Shape::close);
            shapes.clear();
            children.forEach(Graphic::close);
            children.clear();
            parent = null;
        }

        public Graphic add(Shape shape) {
            if (shape != null) shapes.add(shape);
            return this;
        }

        /**
         * 挂一个子节点。子节点保留在自己的局部坐标里，父链的变换在绘制时施加——对应
         * Pixi 里 {@code container.addChild(child)} 的语义。
         */
        public Graphic addGraphic(Graphic child) {
            if (child != null && child != this) {
                child.parent = this;
                if (!children.contains(child)) children.add(child);
            }
            return this;
        }

        public Graphic setPosition(float newX, float newY) {
            this.x = newX;
            this.y = newY;
            return this;
        }

        public Graphic setPivot(float newPivotX, float newPivotY) {
            this.pivotX = newPivotX;
            this.pivotY = newPivotY;
            return this;
        }

        public Graphic setScale(float newScaleX, float newScaleY) {
            this.scaleX = newScaleX;
            this.scaleY = newScaleY;
            return this;
        }

        public boolean hasShapes() {
            return !shapes.isEmpty();
        }

        private float chainAlpha() {
            float inherited = parent == null ? 1f : parent.chainAlpha();
            return inherited * alpha;
        }

        private void applyChain(Canvas canvas) {
            if (parent != null) parent.applyChain(canvas);
            canvas.translate(x, y);
            if (rotation != 0f) {
                // Skija 的 rotate 收的是角度。
                canvas.rotate((float) Math.toDegrees(rotation));
            }
            if (scaleX != 1f || scaleY != 1f) canvas.scale(scaleX, scaleY);
            canvas.translate(-pivotX, -pivotY);
        }

        /**
         * 把自身（含父链）连同整棵子树画到画布上；不改画布状态。
         *
         * <p>每个节点都从当前画布状态独立重走一遍自己的父链，所以递归进子树时父节点不能
         * 替子节点预先施加变换——否则每层都会被叠两次。
         */
        public void paint(Canvas canvas) {
            if (!visible) return;
            float alpha = chainAlpha();
            if (alpha > 0.002f && !shapes.isEmpty()) {
                int save = canvas.save();
                applyChain(canvas);
                for (Shape shape : shapes) shape.paint(canvas, alpha);
                canvas.restoreToCount(save);
            }
            for (Graphic child : children) child.paint(canvas);
        }
    }
}
