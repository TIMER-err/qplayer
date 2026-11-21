package dev.t1m3.qplayer.lyric.skia;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 歌词动态背景「网格形变」（{@code STYLE_NETEASE_DYNAMIC}）的控制点网格：<strong>纯逻辑</strong>，
 * 不引用 Skia，所以可以在 JVM 单测里钉住。
 *
 * <p>这一层对应 {@code Documents/ui/ui/} 原型里的两个文件：
 *
 * <ul>
 *   <li>{@code background_mesh_data.js} —— 9×9 控制点网格（规则网格 + 5 个形变变体），
 *       从网易云动态背景 {@code 0x7A9CBB58} 那张 16 项控制点表里解出来的；数据本身是<strong>事实</strong>，
 *       原样搬进资源文件（见 {@code /shaders/fluid/mesh_warp_grids.txt}）；</li>
 *   <li>{@code dynamic_background.js} 的顶点着色器与 {@code buildMeshGeometry}/{@code sampleControlGrid}
 *       —— 把控制网格按 Catmull-Rom 双三次插值采样成细网格，再在两个变体之间按 {@code morph} 混合。
 * </ul>
 *
 * <h3>为什么形变在几何上做，而不是在片元着色器里做</h3>
 *
 * <p>原型的顶点着色器算的是<strong>屏幕顶点位置</strong>，而纹理坐标（{@code aRegular}）保持规则——
 * 也就是说「画面被网格推着走，但采样的内容不变」。Skia 的 {@code drawVertices} 正好是同一套语义
 * （positions 走形变、texCoords 走规则 uv，片元着色器按插值后的 texCoords 取值），
 * 而且本仓库的 AMLL 网格背景已经在用这条路，所以不需要在 SkSL 里放 162 个 uniform。
 *
 * <h3>每帧只做混合</h3>
 *
 * <p>双三次采样只在封面/变体变化时算一次（{@link Base}），之后每帧只做
 * 「两个变体按 morph 混合 + 强度 + 膨胀」这几次乘加——4225 个顶点，手机上也扛得住。
 */
final class MeshWarp {

    /**
     * 封面纹理边长。
     *
     * <p>比其它样式的 32px 大得多：这一档的画面是三层<strong>放大</strong>的封面
     * （最外层放到屏宽的 1/2.8 再裁一个方块），32px 放大后只剩色块，原图的结构（专辑封面上的形状）
     * 会在模糊之后彻底糊掉。原型直接拿全分辨率封面当纹理，这里取 192 作为「够看且够轻」的折中。
     */
    static final int ART = 192;

    /** 形变强度/膨胀的默认值，取自原型的 {@code C.backgroundMeshStrength} / {@code ...Expansion}。 */
    static final float STRENGTH = 1.0f;
    static final float EXPANSION = 1.06f;

    private static final String RESOURCE = "/shaders/fluid/mesh_warp_grids.txt";
    private static volatile Grids cached;

    private MeshWarp() {
    }

    /** 控制点网格：规则网格 + 若干形变变体（每个都是 {@code size×size×2} 的 x,y 序列）。 */
    static final class Grids {
        final int size;
        final int subdivisions;
        final float[] regular;
        final float[][] variants;

        Grids(int size, int subdivisions, float[] regular, float[][] variants) {
            this.size = size;
            this.subdivisions = subdivisions;
            this.regular = regular;
            this.variants = variants;
        }

        /** 每个控制格细分成的段数（原型 {@code (size - 1) << subdivisionShift}）。 */
        int segments() {
            return (size - 1) << subdivisions;
        }
    }

    /** 读控制点资源（进程内缓存一次）。资源缺失/损坏时抛 {@link IllegalStateException}。 */
    static Grids grids() {
        Grids local = cached;
        if (local != null) {
            return local;
        }
        synchronized (MeshWarp.class) {
            if (cached == null) {
                cached = load();
            }
            return cached;
        }
    }

    private static Grids load() {
        try (InputStream in = MeshWarp.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("resource not found: " + RESOURCE);
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            int size = -1;
            int subdivisions = -1;
            float[] regular = null;
            List<float[]> variants = new ArrayList<float[]>();
            float[] pending = null;
            int written = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                if (line.startsWith("grid")) {
                    size = Integer.parseInt(line.substring(4).trim());
                } else if (line.startsWith("subdivisions")) {
                    subdivisions = Integer.parseInt(line.substring("subdivisions".length()).trim());
                } else if ("regular".equals(line)) {
                    pending = new float[size * size * 2];
                    written = 0;
                    regular = pending;
                } else if (line.startsWith("variant")) {
                    pending = new float[size * size * 2];
                    written = 0;
                    variants.add(pending);
                } else if ("end".equals(line)) {
                    break;
                } else if (pending != null) {
                    int space = line.indexOf(' ');
                    if (space > 0 && written + 1 < pending.length) {
                        pending[written] = Float.parseFloat(line.substring(0, space));
                        pending[written + 1] = Float.parseFloat(line.substring(space + 1).trim());
                        written += 2;
                        if (written == pending.length) {
                            pending = null;
                        }
                    }
                }
            }
            if (size < 2 || subdivisions < 0 || regular == null || variants.isEmpty()) {
                throw new IOException("incomplete mesh data (grid=" + size
                        + ", subdivisions=" + subdivisions + ", regular=" + (regular != null)
                        + ", variants=" + variants.size() + ")");
            }
            return new Grids(size, subdivisions, regular,
                    variants.toArray(new float[variants.size()][]));
        } catch (IOException e) {
            throw new IllegalStateException("读取网格形变控制点失败：" + e, e);
        }
    }

    /**
     * 为一首曲目挑一个形变变体。
     *
     * <p>原型是每次构造随机挑一个；这里改成<strong>由曲目种子决定</strong>：
     * 同一首歌每次打开都是同一张形变（暂停/重开歌词页不会突然换一个形状），
     * 不同曲目之间照样是随机的。
     */
    static int variantFor(long seed, int variantCount) {
        if (variantCount <= 1) {
            return 0;
        }
        return new Random(seed).nextInt(variantCount);
    }

    /**
     * 形变插值 0..1（原型 {@code meshMorphValue}）。
     *
     * <p>相位 → 正弦 → 反余弦 → smoothstep：正弦本身在两端变化慢、中间快，
     * 直接拿它当插值会让形变在中途「窜」一下；反余弦把它掰回匀速的 0..1，
     * 再用 smoothstep 让两端停得稳一点。常数（周期系数 0.0015、倍率 1）来自原型。
     *
     * @param phase 相位（原型 {@code phase += 0.13 * dtMs}）
     */
    static float morphAt(double phase) {
        double wave = Math.sin(phase * 0.0015 * Math.PI);
        double clamped = wave < -1.0 ? -1.0 : (wave > 1.0 ? 1.0 : wave);
        double normalized = Math.acos(clamped) / Math.PI;
        double smooth = normalized * normalized * (3.0 - 2.0 * normalized);
        return (float) smooth;
    }

    /**
     * 一个封面/变体对应的预计算网格。
     *
     * <p>{@link #meshA}/{@link #meshB} 是<strong>归一化</strong>（0..1，y 向下）的两个变体位置，
     * {@link #uvs} 是规则纹理坐标（片元着色器直接当 uv 用）。
     * {@link #positions(float, float, float, float, float)} 每帧把设备坐标写进复用缓冲。
     */
    static final class Base {
        final int side;
        final float[] meshA;
        final float[] meshB;
        final float[] uvs;
        final short[] indices;
        private final float[] positions;

        Base(Grids grids, int variant) {
            int segments = grids.segments();
            this.side = segments + 1;
            int count = side * side;
            this.meshA = new float[count * 2];
            this.meshB = new float[count * 2];
            this.uvs = new float[count * 2];
            this.positions = new float[count * 2];
            float[] variantPoints = grids.variants[Math.max(0, variant) % grids.variants.length];

            int index = 0;
            for (int y = 0; y <= segments; y++) {
                float v = (float) y / segments;
                for (int x = 0; x <= segments; x++) {
                    float u = (float) x / segments;
                    sampleControlGrid(grids.regular, grids.size, u, v, meshA, index);
                    sampleControlGrid(variantPoints, grids.size, u, v, meshB, index);
                    // 纹理坐标 = 规则 uv：原型里顶点属性 aRegular 原样进片元着色器
                    uvs[index] = u;
                    uvs[index + 1] = v;
                    index += 2;
                }
            }

            this.indices = new short[segments * segments * 6];
            int offset = 0;
            for (int y = 0; y < segments; y++) {
                for (int x = 0; x < segments; x++) {
                    int topLeft = y * side + x;
                    int topRight = topLeft + 1;
                    int bottomLeft = topLeft + side;
                    int bottomRight = bottomLeft + 1;
                    indices[offset++] = (short) topLeft;
                    indices[offset++] = (short) bottomLeft;
                    indices[offset++] = (short) topRight;
                    indices[offset++] = (short) topRight;
                    indices[offset++] = (short) bottomLeft;
                    indices[offset++] = (short) bottomRight;
                }
            }
        }

        /**
         * 把形变后的设备坐标写进复用缓冲并返回它（原型顶点着色器的逐行对应）。
         *
         * <pre>
         * deformation = mix(meshA, meshB, morph)
         * warped      = mix(regularUV, deformation, strength)
         * warped      = (warped - 0.5) * expansion + 0.5
         * </pre>
         *
         * <p><strong>返回的是内部复用缓冲</strong>（每帧 4225 个顶点，不每帧分配）：
         * 调用方必须立刻把它交给 {@code drawVertices}，<strong>不能同时留下两份结果</strong>——
         * 第二次调用会把第一份覆盖掉（单测里踩过一次）。
         */
        float[] positions(float morph, float strength, float expansion, float w, float h) {
            for (int i = 0; i < positions.length; i += 2) {
                float u = uvs[i];
                float v = uvs[i + 1];
                float dx = meshA[i] + (meshB[i] - meshA[i]) * morph;
                float dy = meshA[i + 1] + (meshB[i + 1] - meshA[i + 1]) * morph;
                float x = u + (dx - u) * strength;
                float y = v + (dy - v) * strength;
                positions[i] = ((x - 0.5f) * expansion + 0.5f) * w;
                positions[i + 1] = ((y - 0.5f) * expansion + 0.5f) * h;
            }
            return positions;
        }

        /** 顶点数（诊断/测试用）。 */
        int vertexCount() {
            return side * side;
        }
    }

    // ---- 控制网格采样：与原型 sampleControlGrid / controlPoint / catmullRom 逐行对应 ----

    private static void sampleControlGrid(float[] points, int size, float u, float v,
                                          float[] out, int outIndex) {
        float gridX = u * (size - 1);
        float gridY = v * (size - 1);
        int x = (int) Math.floor(gridX);
        int y = (int) Math.floor(gridY);
        float amountX = gridX - x;
        float amountY = gridY - y;
        float[] rowsX = new float[4];
        float[] rowsY = new float[4];
        for (int row = -1; row <= 2; row++) {
            float p0x = control(points, size, x - 1, y + row, 0);
            float p0y = control(points, size, x - 1, y + row, 1);
            float p1x = control(points, size, x, y + row, 0);
            float p1y = control(points, size, x, y + row, 1);
            float p2x = control(points, size, x + 1, y + row, 0);
            float p2y = control(points, size, x + 1, y + row, 1);
            float p3x = control(points, size, x + 2, y + row, 0);
            float p3y = control(points, size, x + 2, y + row, 1);
            rowsX[row + 1] = catmullRom(p0x, p1x, p2x, p3x, amountX);
            rowsY[row + 1] = catmullRom(p0y, p1y, p2y, p3y, amountX);
        }
        out[outIndex] = catmullRom(rowsX[0], rowsX[1], rowsX[2], rowsX[3], amountY);
        out[outIndex + 1] = catmullRom(rowsY[0], rowsY[1], rowsY[2], rowsY[3], amountY);
    }

    /** 越界控制点按边缘夹取（原型 {@code clamp}），所以边缘外不会外推。 */
    private static float control(float[] points, int size, int column, int row, int component) {
        int cx = column < 0 ? 0 : (column > size - 1 ? size - 1 : column);
        int cy = row < 0 ? 0 : (row > size - 1 ? size - 1 : row);
        return points[(cy * size + cx) * 2 + component];
    }

    private static float catmullRom(float p0, float p1, float p2, float p3, float amount) {
        float amount2 = amount * amount;
        float amount3 = amount2 * amount;
        return 0.5f * (2f * p1
                + (-p0 + p2) * amount
                + (2f * p0 - 5f * p1 + 4f * p2 - p3) * amount2
                + (-p0 + 3f * p1 - 3f * p2 + p3) * amount3);
    }
}
