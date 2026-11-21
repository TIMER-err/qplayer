package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.BitDepth;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 开流诉求的对齐测试：对应 Sonalis {@code crates/sonalis-output/src/lib.rs} 的 {@code mod tests}。
 *
 * <p>上游 2 条 → 本地 2 条对齐，方法名一一对应（另有 2 条「额外补充」）：
 * <pre>
 * request_constructor_defaults_to_auto        → requestConstructorDefaultsToAuto
 * a_bit_depth_request_can_actually_be_made    → aBitDepthRequestCanActuallyBeMade
 * </pre>
 *
 * <p>上游差异记录：{@code a_bit_depth_request_can_actually_be_made} 在上游源码里被写了
 * <strong>两个 {@code #[test]} 属性</strong>（一个在文档注释前面），是明显的笔误；
 * 语义不受影响，这里按一条测试收。
 */
public class OutputRequestTest {

    /** 对应上游 {@code request_constructor_defaults_to_auto}。 */
    @Test
    public void requestConstructorDefaultsToAuto() {
        OutputRequest r = OutputRequest.atRate(96_000, 9_600);
        assertEquals(96_000, r.sampleRate());
        assertNull("不点名时应当交给设备选（优先 F32）", r.bitDepth());
        assertNull("不点名时不筛选声道", r.channels());
        assertNull("不指定时缓冲交给设备决定", r.fixedBufferFrames());
        assertEquals(9_600, r.ringFrames());
    }

    /**
     * 对应上游 {@code a_bit_depth_request_can_actually_be_made}。
     *
     * <p>这条守的是 A1 那个「字段实现了但没有任何调用方」的缺陷：
     * 光有 {@code bitDepth} 字段和 I24 格式，接不上就等于没有。
     */
    @Test
    public void aBitDepthRequestCanActuallyBeMade() {
        OutputRequest plain = OutputRequest.atRate(48_000, 9_600);
        assertNull("不点名时应当交给设备选（优先 F32）", plain.bitDepth());

        OutputRequest req = OutputRequest.atRate(48_000, 9_600).withBitDepth(BitDepth.INT24);
        assertEquals(BitDepth.INT24, req.bitDepth());
        assertEquals("只该动位深，不能碰到别的字段", 48_000, req.sampleRate());
        assertEquals(9_600, req.ringFrames());
        assertNull(req.channels());
        assertNull(req.fixedBufferFrames());

        // 请求 I24 时，I24 的打分必须压过 F32 的默认优势，否则这个请求等于没提
        assertTrue("点名 I24 时必须真的优先选 I24",
                OutputChooser.scoreFormat(OutputSampleFormat.I24, req.bitDepth())
                        > OutputChooser.scoreFormat(OutputSampleFormat.F32, req.bitDepth()));
        // 而没点名时 F32 仍是最优（内部就是 f32，直通不转换）
        assertTrue("不点名时 F32 应最优",
                OutputChooser.scoreFormat(OutputSampleFormat.F32, plain.bitDepth())
                        > OutputChooser.scoreFormat(OutputSampleFormat.I24, plain.bitDepth()));
    }

    // ------------------------------------------------------------------
    // 以下为移植时补充的测试（上游没有对应条目）
    // ------------------------------------------------------------------

    /**
     * {@code sample_rate} 必填（上游设计决定 ①）在 Java 里需要显式的前置条件：
     * Rust 的 {@code u32} 表达不了 0 与负数，Java 的 {@code int} 可以。
     */
    @Test
    public void sampleRateIsRequiredAndMustBePositive() {
        AudioException zero = assertThrows(AudioException.class, () -> OutputRequest.atRate(0, 4_800));
        assertEquals(AudioException.Kind.CONFIG, zero.kind());
        assertThrows(AudioException.class, () -> OutputRequest.atRate(-44_100, 4_800));
        assertThrows(AudioException.class, () -> OutputRequest.atRate(44_100, 0));
        // 合法值不报错
        assertNotNull(OutputRequest.atRate(1, 1));
    }

    /** {@code withBitDepth} 是「复制修改」，不改原对象（Rust 那边也是 move/复制语义）。 */
    @Test
    public void buildersAreImmutable() {
        OutputRequest base = OutputRequest.atRate(44_100, 4_800);
        OutputRequest withDepth = base.withBitDepth(BitDepth.INT24);
        OutputRequest withChannels = base.withChannels(Integer.valueOf(2));

        assertNull("原对象不能被改动", base.bitDepth());
        assertNull(base.channels());
        assertEquals(BitDepth.INT24, withDepth.bitDepth());
        assertNull("withBitDepth 不该碰声道", withDepth.channels());
        assertEquals(Integer.valueOf(2), withChannels.channels());
        assertNull("withChannels 不该碰位深", withChannels.bitDepth());
        assertEquals(44_100, withChannels.sampleRate());
        assertEquals(4_800, withChannels.ringFrames());
    }
}
