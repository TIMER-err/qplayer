package dev.t1m3.qplayer.lyric.skia;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link MeshWarp} 的测试：控制点数据、双三次采样、形变网格与 morph 曲线。
 *
 * <p>这些性质都是「画面不对时最可能坏掉的地方」：数据文件格式、采样越界夹取、
 * 顶点数/索引数、以及 morph 的取值范围。它们在 JVM 上就能钉住（不需要 Skia）。
 */
public class MeshWarpTest {

    @Test
    public void gridsResourceLoadsWithExpectedShape() {
        MeshWarp.Grids grids = MeshWarp.grids();
        assertEquals("原型 background_mesh_data.js 的 gridSize", 9, grids.size);
        assertEquals("原型 subdivisionShift", 3, grids.subdivisions);
        assertEquals(9 * 9 * 2, grids.regular.length);
        assertEquals("原型有 5 个形变变体", 5, grids.variants.length);
        for (float[] variant : grids.variants) {
            assertEquals(9 * 9 * 2, variant.length);
        }
        // (size - 1) << subdivisions = 8 * 8 = 64 段 → 65×65 个顶点
        assertEquals(64, grids.segments());
    }

    @Test
    public void regularGridCornersAndMonotonicity() {
        MeshWarp.Grids grids = MeshWarp.grids();
        // 规则网格的四个角必须精确（数据里就是 0 / 1），否则整块画面会整体偏移
        assertEquals(0f, sampleRegular(grids, 0f, 0f, 0), 0f);
        assertEquals(0f, sampleRegular(grids, 0f, 0f, 1), 0f);
        assertEquals(1f, sampleRegular(grids, 1f, 1f, 0), 1e-4f);
        assertEquals(1f, sampleRegular(grids, 1f, 1f, 1), 1e-4f);
        // 单调：u 增加时 x 不能回头
        float previous = -1f;
        for (int i = 0; i <= 16; i++) {
            float x = sampleRegular(grids, i / 16f, 0.5f, 0);
            assertTrue("规则网格 x 应单调不减：i=" + i + " x=" + x, x >= previous - 1e-4f);
            previous = x;
        }
        // 数据是从 JS 里 2 位小数取出来的（0.13 而不是 0.125），所以中点只有约 1e-2 精度
        assertEquals(0.5f, sampleRegular(grids, 0.5f, 0.5f, 0), 0.02f);
        assertEquals(0.5f, sampleRegular(grids, 0.5f, 0.5f, 1), 0.02f);
    }

    @Test
    public void baseMeshHasPrototypeVertexAndIndexCounts() {
        MeshWarp.Grids grids = MeshWarp.grids();
        MeshWarp.Base base = new MeshWarp.Base(grids, 0);
        assertEquals(65 * 65, base.vertexCount());
        assertEquals(65 * 65 * 2, base.uvs.length);
        // 每个格子两个三角形、每个三角形 3 个索引
        assertEquals(64 * 64 * 6, base.indices.length);
        // 索引必须都指得进顶点数组（越界在 Skia 里是崩，不是报错）
        for (short index : base.indices) {
            assertTrue("索引越界：" + index, index >= 0 && index < base.vertexCount());
        }
    }

    @Test
    public void textureCoordinatesAreTheRegularUvGrid() {
        MeshWarp.Base base = new MeshWarp.Base(MeshWarp.grids(), 0);
        // 原型里顶点属性 aRegular 就是规则 uv，片元着色器直接拿它当 uv 用
        assertEquals(0f, base.uvs[0], 1e-6f);
        assertEquals(0f, base.uvs[1], 1e-6f);
        int last = base.uvs.length - 2;
        assertEquals(1f, base.uvs[last], 1e-6f);
        assertEquals(1f, base.uvs[last + 1], 1e-6f);
        // 第二行第一个顶点 = 第一格之后 → u=0, v=1/64
        assertEquals(0f, base.uvs[130], 1e-6f);
        assertEquals(1f / 64f, base.uvs[131], 1e-6f);
    }

    @Test
    public void zeroStrengthIgnoresTheDeformationEntirely() {
        MeshWarp.Base base = new MeshWarp.Base(MeshWarp.grids(), 2);
        float[] positions = base.positions(0.7f, 0f, 1f, 200f, 100f);
        // strength = 0 时 warped = 规则 uv → 位置就是 uv × 尺寸（与原型 mix(aRegular, deformation, 0) 一致）
        for (int i = 0; i < base.uvs.length; i += 2) {
            assertEquals(base.uvs[i] * 200f, positions[i], 1e-3f);
            assertEquals(base.uvs[i + 1] * 100f, positions[i + 1], 1e-3f);
        }
    }

    @Test
    public void fullStrengthMatchesTheSampledVariant() {
        MeshWarp.Base base = new MeshWarp.Base(MeshWarp.grids(), 3);
        float[] positions = base.positions(0f, 1f, 1f, 100f, 100f);
        // morph = 0、strength = 1、expansion = 1 ⇒ 位置就是 meshA（变体 A 的双三次采样结果）
        for (int i = 0; i < base.meshA.length; i += 2) {
            assertEquals(base.meshA[i] * 100f, positions[i], 1e-3f);
            assertEquals(base.meshA[i + 1] * 100f, positions[i + 1], 1e-3f);
        }
    }

    @Test
    public void expansionPushesGeometryOutwards() {
        MeshWarp.Base base = new MeshWarp.Base(MeshWarp.grids(), 0);
        // positions() 返回的是**内部复用缓冲**：要比两次结果就必须自己拷一份出来
        float[] noExpansion = base.positions(0f, 0f, 1f, 100f, 100f).clone();
        float[] expanded = base.positions(0f, 0f, MeshWarp.EXPANSION, 100f, 100f);
        // 膨胀 1.06：(0.5 ± d) → 0.5 ± 1.06d ⇒ 左上角更小、右下角更大
        assertTrue(expanded[0] < noExpansion[0]);
        int last = noExpansion.length - 2;
        assertTrue(expanded[last] > noExpansion[last]);
        assertEquals(-0.03f * 100f, expanded[0], 0.05f);
    }

    @Test
    public void morphCurveStaysInRangeAndHitsItsEnds() {
        // phase = 0 → sin(0) = 0 → acos(0)/π = 0.5 → smoothstep(0.5) = 0.5
        assertEquals(0.5f, MeshWarp.morphAt(0.0), 1e-5f);
        for (double phase = 0.0; phase < 12_000.0; phase += 7.0) {
            float morph = MeshWarp.morphAt(phase);
            assertTrue("morph 必须落在 0..1：" + morph, morph >= 0f && morph <= 1f);
        }
        // 相位推进时 morph 真的会变（不是常数）
        assertNotEquals(MeshWarp.morphAt(0.0), MeshWarp.morphAt(600.0), 1e-3f);
    }

    @Test
    public void variantChoiceIsStablePerSeedAndSpread() {
        assertEquals(MeshWarp.variantFor(12345L, 5), MeshWarp.variantFor(12345L, 5));
        assertEquals(0, MeshWarp.variantFor(1L, 1));
        Set<Integer> seen = new HashSet<Integer>();
        for (long seed = 0; seed < 40; seed++) {
            int variant = MeshWarp.variantFor(seed, 5);
            assertTrue("变体越界：" + variant, variant >= 0 && variant < 5);
            seen.add(Integer.valueOf(variant));
        }
        assertTrue("40 个种子应该覆盖到多个变体，实际 " + seen, seen.size() >= 3);
    }

    private static float sampleRegular(MeshWarp.Grids grids, float u, float v, int component) {
        // 直接用 Base 的采样结果：Base 的 meshA 就是规则网格的双三次采样
        MeshWarp.Base base = new MeshWarp.Base(grids, 0);
        int x = Math.round(u * (base.side - 1));
        int y = Math.round(v * (base.side - 1));
        return base.meshA[(y * base.side + x) * 2 + component];
    }
}
