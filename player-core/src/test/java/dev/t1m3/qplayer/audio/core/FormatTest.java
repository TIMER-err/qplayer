package dev.t1m3.qplayer.audio.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 位深 / 家族 / 格式指纹 / 路径判定的对齐测试。
 *
 * <p>逐条对应 Sonalis {@code sonalis-core/src/format.rs} 的 {@code mod tests}。
 */
public class FormatTest {

    private static SourceFormat src(int rate, BitDepth depth) {
        return new SourceFormat(rate, 2, depth, true, "flac");
    }

    @Test
    public void bitDepthBitsAndInteger() {
        assertEquals(16, BitDepth.INT16.bits());
        assertEquals(24, BitDepth.INT24.bits());
        assertEquals(32, BitDepth.FLOAT32.bits());
        assertTrue(BitDepth.INT24.isInteger());
        assertFalse(BitDepth.FLOAT32.isInteger());
        assertEquals("32 bit float", BitDepth.FLOAT32.toString());
    }

    /**
     * 这条是 HiFi 路径设计的基石：f32 的 24 位尾数够覆盖 CD 与 Hi-Res 主力规格，
     * 但覆盖不了 32 位整数源。
     */
    @Test
    public void f32ExactnessBoundary() {
        assertTrue(BitDepth.INT16.exactInF32());
        assertTrue(BitDepth.INT24.exactInF32());
        assertFalse("32 位整数无法精确进 f32", BitDepth.INT32.exactInF32());
        assertFalse(BitDepth.FLOAT64.exactInF32());
    }

    @Test
    public void bitDepthFromReportedBits() {
        assertEquals(BitDepth.INT16, BitDepth.fromBits(16));
        assertEquals("20 位按 24 位容器算", BitDepth.INT24, BitDepth.fromBits(20));
        assertEquals(BitDepth.INT24, BitDepth.fromBits(24));
        assertEquals(BitDepth.INT32, BitDepth.fromBits(32));
        assertNull(BitDepth.fromBits(0));
    }

    @Test
    public void rateFamilies() {
        assertEquals(RateFamily.BASE_44, RateFamily.of(44_100));
        assertEquals(RateFamily.BASE_44, RateFamily.of(88_200));
        assertEquals(RateFamily.BASE_44, RateFamily.of(176_400));
        // DSD64 落在 44.1 家族，这是正确的
        assertEquals(RateFamily.BASE_44, RateFamily.of(2_822_400));
        assertEquals(RateFamily.BASE_48, RateFamily.of(48_000));
        assertEquals(RateFamily.BASE_48, RateFamily.of(96_000));
        assertEquals(RateFamily.BASE_48, RateFamily.of(192_000));
    }

    @Test
    public void directWhenDeviceMatchesExactly() {
        PathVerdict v = PathVerdict.classify(
                src(96_000, BitDepth.INT24),
                new int[] {44_100, 48_000, 96_000},
                new BitDepth[] {BitDepth.INT16, BitDepth.INT24});
        assertEquals(new PathVerdict.Direct(96_000, BitDepth.INT24), v);
        assertTrue(v.isBitPerfect());
        assertTrue(v.summary().contains("直通"));
    }

    @Test
    public void resamplesWithinFamilyWhenPossible() {
        // 源 44.1k，设备只有 88.2k 与 48k —— 应选同家族的 88.2k
        PathVerdict v = PathVerdict.classify(
                src(44_100, BitDepth.INT16),
                new int[] {48_000, 88_200},
                new BitDepth[] {BitDepth.INT16});
        assertEquals(new PathVerdict.Resampled(44_100, 88_200, true), v);
        assertFalse(v.isBitPerfect());
    }

    @Test
    public void crossFamilyResampleIsFlagged() {
        PathVerdict v = PathVerdict.classify(
                src(44_100, BitDepth.INT16),
                new int[] {48_000, 96_000},
                new BitDepth[] {BitDepth.INT16});
        assertTrue("应为重采样判定，实际 " + v, v instanceof PathVerdict.Resampled);
        PathVerdict.Resampled r = (PathVerdict.Resampled) v;
        assertEquals("同家族没有合适档位时，应取不低于源的最低档，不无谓升到 96k",
                48_000, r.to);
        assertFalse("44.1 → 48 是跨家族，必须如实标注", r.sameFamily);
    }

    /** 设备全部档位都低于源采样率时，只能降采样——取最高档把损失压到最小。 */
    @Test
    public void fallsBackToHighestWhenDeviceIsSlower() {
        PathVerdict v = PathVerdict.classify(
                src(192_000, BitDepth.INT16),
                new int[] {44_100, 48_000, 96_000},
                new BitDepth[] {BitDepth.INT16});
        assertTrue("应为重采样判定，实际 " + v, v instanceof PathVerdict.Resampled);
        assertEquals("全部低于源时取最高，损失最小", 96_000, ((PathVerdict.Resampled) v).to);
    }

    @Test
    public void keepsRateAndReportsDepthChange() {
        // 采样率能保住，位深只有 16 —— 应判为改位深且不是升级
        PathVerdict v = PathVerdict.classify(
                src(48_000, BitDepth.INT24),
                new int[] {48_000},
                new BitDepth[] {BitDepth.INT16});
        assertEquals(new PathVerdict.Requantized(BitDepth.INT24, BitDepth.INT16, false), v);
    }

    @Test
    public void depthUpconversionIsMarkedLossless() {
        PathVerdict v = PathVerdict.classify(
                src(48_000, BitDepth.INT16),
                new int[] {48_000},
                new BitDepth[] {BitDepth.INT24, BitDepth.INT32});
        assertTrue("应为改位深判定，实际 " + v, v instanceof PathVerdict.Requantized);
        PathVerdict.Requantized r = (PathVerdict.Requantized) v;
        assertEquals("取不低于源的最低档，不无谓升到 32", BitDepth.INT24, r.to);
        assertTrue(r.upconverted);
        assertTrue(v.summary().contains("无损失"));
    }

    /**
     * <strong>「不直通」不等于「有损失」。</strong>
     *
     * <p>这两件事混为一谈会让 24 bit 的 FLAC 在只声明 F32 的设备上被报成「丢信息」，
     * 而 16 bit 与 24 bit 经 f32 中转是<strong>精确</strong>的（f32 尾数正好 24 位），
     * 只有 32 bit 整数会丢掉低 8 位。
     */
    @Test
    public void losingInformationIsNotTheSameAsNotBeingDirect() {
        // 24 bit 源 → 只声明 F32 的设备：不是 Direct，但一个比特都没丢
        PathVerdict up = PathVerdict.classify(src(48_000, BitDepth.INT24),
                new int[] {48_000}, new BitDepth[] {BitDepth.FLOAT32});
        assertFalse("位深对不上，不能说直通", up.isBitPerfect());
        assertFalse("24 bit 经 f32 是精确的，不该报成有损失：" + up.summary(),
                up.losesInformation());

        // 32 bit 整数源 → F32：低 8 位真的没了
        PathVerdict down = PathVerdict.classify(src(48_000, BitDepth.INT32),
                new int[] {48_000}, new BitDepth[] {BitDepth.FLOAT32});
        assertTrue("32 bit 整数经 f32 会丢低 8 位，必须报成有损失：" + down.summary(),
                down.losesInformation());

        // 重采样是真损失
        PathVerdict rs = PathVerdict.classify(src(44_100, BitDepth.INT16),
                new int[] {48_000}, new BitDepth[] {BitDepth.INT16});
        assertTrue(rs.losesInformation());

        // 直通当然没有损失
        PathVerdict ok = PathVerdict.classify(src(48_000, BitDepth.INT16),
                new int[] {48_000}, new BitDepth[] {BitDepth.INT16});
        assertTrue(ok.isBitPerfect() && !ok.losesInformation());
    }

    @Test
    public void emptyDeviceListsDoNotPanic() {
        PathVerdict v = PathVerdict.classify(src(44_100, BitDepth.INT16), new int[0], new BitDepth[0]);
        assertTrue("空能力表应退化为重采样判定，实际 " + v, v instanceof PathVerdict.Resampled);
    }

    @Test
    public void hiresFlag() {
        assertTrue(src(96_000, BitDepth.INT24).isHires());
        assertTrue(src(44_100, BitDepth.INT24).isHires());
        assertFalse(src(44_100, BitDepth.INT16).isHires());
        // 有损格式即使参数高也不算 Hi-Res
        SourceFormat mp3 = new SourceFormat(48_000, 2, BitDepth.INT16, false, "mp3");
        assertFalse(mp3.isHires());
    }

    @Test
    public void rateDisplay() {
        assertEquals("44.1 kHz", PathVerdict.formatRate(44_100));
        assertEquals("48 kHz", PathVerdict.formatRate(48_000));
        assertEquals("192 kHz", PathVerdict.formatRate(192_000));
    }

    @Test
    public void bitPerfectPathForbidsDsp() {
        assertTrue(SignalPath.BIT_PERFECT.isBitPerfect());
        assertFalse(SignalPath.BIT_PERFECT.allowsDsp());
        assertTrue(SignalPath.PROCESSED.allowsDsp());
    }
}
