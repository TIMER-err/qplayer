package dev.t1m3.qplayer.audio.output;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 采样转换与限幅的对齐测试：对应 Sonalis {@code crates/sonalis-output/src/lib.rs}
 * 的 {@code mod tests}。
 *
 * <p>上游 3 条 → 本地 3 条对齐，方法名一一对应（另有 5 条「额外补充」，见文件末尾）：
 * <pre>
 * conversion_clamps_instead_of_wrapping   → conversionClampsInsteadOfWrapping
 * conversion_endpoints                    → conversionEndpoints
 * i24_stays_within_24_significant_bits    → i24StaysWithin24SignificantBits
 * </pre>
 *
 * <p>容差说明：上游全部是 {@code assert_eq!}（精确相等），这里也用精确相等——
 * 转换函数只做乘法、取整与夹紧，没有超越函数，不存在 1 ulp 级的实现差异。
 * 唯一需要留神的是取整语义（见 {@link SampleConversion#roundHalfAwayFromZero(float)}），
 * 所以额外补了一条把 Rust 的「.5 远离零」钉住的测试。
 */
public class SampleConversionTest {

    /** 对应上游 {@code conversion_clamps_instead_of_wrapping}。 */
    @Test
    public void conversionClampsInsteadOfWrapping() {
        // 1.5 如果直接乘会绕成负数，听感是爆音
        assertTrue("1.5 夹紧后必须是正数", SampleConversion.f32ToI16(1.5f) > 0);
        assertTrue("-1.5 夹紧后必须是负数", SampleConversion.f32ToI16(-1.5f) < 0);
        assertEquals("1.5 → i16 上限", Short.MAX_VALUE, SampleConversion.f32ToI16(1.5f));
        assertEquals("-1.5 → i16 下限 + 1（-32768 留给精确的 -1.0）",
                Short.MIN_VALUE + 1, SampleConversion.f32ToI16(-1.5f));
        assertEquals("1.5 → 24 位上限", 8_388_607, SampleConversion.f32ToI24(1.5f));
        assertEquals("-1.5 → 24 位下限", -8_388_607, SampleConversion.f32ToI24(-1.5f));
    }

    /** 对应上游 {@code conversion_endpoints}。 */
    @Test
    public void conversionEndpoints() {
        assertEquals(0, SampleConversion.f32ToI16(0.0f));
        assertEquals(Short.MAX_VALUE, SampleConversion.f32ToI16(1.0f));

        assertEquals(0, SampleConversion.f32ToU16(-1.0f));
        assertEquals(32_768, SampleConversion.f32ToU16(0.0f));
        assertEquals(65_535, SampleConversion.f32ToU16(1.0f));

        assertEquals(0, SampleConversion.f32ToI32(0.0f));
        assertEquals(0, SampleConversion.f32ToI24(0.0f));
        assertEquals(8_388_607, SampleConversion.f32ToI24(1.0f));
    }

    /**
     * 对应上游 {@code i24_stays_within_24_significant_bits}。
     *
     * <p>24 位转换必须真的落在 24 位有效范围内，否则高 8 位会被后端截掉成噪声。
     */
    @Test
    public void i24StaysWithin24SignificantBits() {
        float[] values = {-1.0f, -0.5f, 0.0f, 0.5f, 1.0f};
        for (float v : values) {
            int x = SampleConversion.f32ToI24(v);
            assertTrue(v + " 转出的 " + x + " 超出 24 位范围", Math.abs(x) <= 8_388_607);
        }
    }

    // ------------------------------------------------------------------
    // 以下为移植时补充的测试（上游没有对应条目），守的是 Java 与 Rust 的差异处
    // ------------------------------------------------------------------

    /**
     * Rust 的 {@code f32::round()} 是「.5 远离零」，Java 的 {@code Math.round()} 是
     * {@code floor(x + 0.5)}。这条用 -0.5 把差异钉死：Rust 得 -16384，
     * 若误用 {@code Math.round} 会得 -16383，逐样本不一致且没人会发现。
     */
    @Test
    public void roundingMatchesRustHalfAwayFromZero() {
        assertEquals(-16_384, SampleConversion.f32ToI16(-0.5f));
        assertEquals(16_384, SampleConversion.f32ToI16(0.5f));
        // 24 位同理：8388607 * -0.5 = -4194303.5 → 远离零 → -4194304
        assertEquals(-4_194_304, SampleConversion.f32ToI24(-0.5f));
        assertEquals(-1.0f, SampleConversion.roundHalfAwayFromZero(-0.5f), 0f);
        assertEquals(-1f, SampleConversion.roundHalfAwayFromZero(-1.4f), 0f);
    }

    /**
     * NaN 当静音：上游的 {@code clamp} 原样放过 NaN，靠 Rust 的饱和转换落到 0；
     * 这里显式当 0 处理。有符号格式出 0，无符号格式出中点（同样是静音）。
     */
    @Test
    public void nanBecomesSilence() {
        assertEquals(0f, SampleConversion.clampUnit(Float.NaN), 0f);
        assertEquals(0, SampleConversion.f32ToI16(Float.NaN));
        assertEquals(0, SampleConversion.f32ToI24(Float.NaN));
        assertEquals(0, SampleConversion.f32ToI32(Float.NaN));
        assertEquals(32_768, SampleConversion.f32ToU16(Float.NaN));
    }

    /** 正负零都出 0（整数里没有 -0）。 */
    @Test
    public void negativeZeroIsZero() {
        assertEquals(0f, SampleConversion.clampUnit(-0.0f), 0f);
        assertEquals(0, SampleConversion.f32ToI16(-0.0f));
        assertEquals(0, SampleConversion.f32ToI24(-0.0f));
        // 无符号格式的中点是 32768，-0.0 与 +0.0 都是静音 → 都是中点
        assertEquals(32_768, SampleConversion.f32ToU16(-0.0f));
        // -0.0 < 0 在 Java 里是 false，取整分支不能因此走错
        assertEquals(0f, SampleConversion.roundHalfAwayFromZero(-0.0f), 0f);
    }

    /**
     * 远超 [-1, 1] 的输入同样只夹紧、不绕回。
     *
     * <p>补这条是因为 Java 的 float→int 转换本身是<strong>饱和</strong>的（NaN→0、
     * 超范围→MAX/MIN），而 Rust {@code as} 转换也是饱和的——两边的「兜底」虽然一样，
     * 但真正的防线必须是显式的 clamp，不能靠转换兜底（上游的原话）。
     */
    @Test
    public void farOutOfRangeValuesStillClamp() {
        assertEquals(Short.MAX_VALUE, SampleConversion.f32ToI16(3.0f));
        assertEquals(Short.MIN_VALUE + 1, SampleConversion.f32ToI16(-3.0f));
        assertEquals(8_388_607, SampleConversion.f32ToI24(2.0f));
        assertEquals(-8_388_607, SampleConversion.f32ToI24(-2.0f));
        assertEquals(0, SampleConversion.f32ToU16(-2.0f));
        assertEquals(65_535, SampleConversion.f32ToU16(2.0f));
        assertEquals(1f, SampleConversion.clampUnit(Float.POSITIVE_INFINITY), 0f);
        assertEquals(-1f, SampleConversion.clampUnit(Float.NEGATIVE_INFINITY), 0f);
    }

    /** i32 的上限是 f32 能精确表示的最大「接近 INT_MAX」的值（2^31 − 128）。 */
    @Test
    public void i32EndpointsAreF32Exact() {
        assertEquals(2_147_483_520, SampleConversion.f32ToI32(1.0f));
        assertEquals(-2_147_483_520, SampleConversion.f32ToI32(-1.0f));
    }
}
