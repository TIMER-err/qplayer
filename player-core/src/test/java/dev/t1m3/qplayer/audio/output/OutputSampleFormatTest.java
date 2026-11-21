package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.BitDepth;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 采样格式的对齐测试：对应 Sonalis {@code crates/sonalis-output/src/lib.rs}
 * 的 {@code mod tests}。
 *
 * <p>上游 5 条 → 本地 5 条对齐，方法名一一对应（另有 3 条「额外补充」，见文件末尾）：
 * <pre>
 * sample_format_byte_widths                          → sampleFormatByteWidths
 * sample_format_maps_to_bit_depth                     → sampleFormatMapsToBitDepth
 * bit_depth_matching_is_exact_not_just_same_width     → bitDepthMatchingIsExactNotJustSameWidth
 * unsupported_sample_format_is_reported_not_panicked  → unsupportedSampleFormatIsReportedNotPanicked
 * dsd_formats_are_not_mapped_as_pcm                   → dsdFormatsAreNotMappedAsPcm
 * </pre>
 *
 * <p>上游差异记录：{@code bit_depth_matching_is_exact_not_just_same_width} 在上游源码里
 * <strong>漏了 {@code #[test]} 属性</strong>（它只是那个模块里的一个私有函数，从来不会被执行），
 * 但它守的性质是真实且重要的，这里按「本该是测试」的样子收进来。
 */
public class OutputSampleFormatTest {

    /** 对应上游 {@code sample_format_byte_widths}。 */
    @Test
    public void sampleFormatByteWidths() {
        assertEquals(2, OutputSampleFormat.I16.bytes());
        assertEquals(2, OutputSampleFormat.U16.bytes());
        assertEquals("24 位按 4 字节存储", 4, OutputSampleFormat.I24.bytes());
        assertEquals(4, OutputSampleFormat.F32.bytes());
        assertEquals(4, OutputSampleFormat.I32.bytes());
        assertTrue(OutputSampleFormat.F32.isNative());
        assertFalse(OutputSampleFormat.I24.isNative());
    }

    /** 对应上游 {@code sample_format_maps_to_bit_depth}。 */
    @Test
    public void sampleFormatMapsToBitDepth() {
        assertEquals(BitDepth.INT16, OutputSampleFormat.I16.bitDepth());
        assertEquals(BitDepth.INT24, OutputSampleFormat.I24.bitDepth());
        assertEquals(BitDepth.INT32, OutputSampleFormat.I32.bitDepth());
        assertEquals(BitDepth.FLOAT32, OutputSampleFormat.F32.bitDepth());
    }

    /** 对应上游 {@code bit_depth_matching_is_exact_not_just_same_width}。 */
    @Test
    public void bitDepthMatchingIsExactNotJustSameWidth() {
        // I32 与 F32 都是 32 位宽，但绝不该互相匹配
        assertFalse("整数 32 位不能匹配浮点 32 位", OutputSampleFormat.I32.matchesBitDepth(BitDepth.FLOAT32));
        assertFalse("浮点 32 位不能匹配整数 32 位", OutputSampleFormat.F32.matchesBitDepth(BitDepth.INT32));
        // 宽度相同这一点本身就说明「按 bits() 判」是不够的
        assertEquals(OutputSampleFormat.I32.bitDepth().bits(), OutputSampleFormat.F32.bitDepth().bits());

        assertTrue(OutputSampleFormat.I24.matchesBitDepth(BitDepth.INT24));
        assertTrue(OutputSampleFormat.I16.matchesBitDepth(BitDepth.INT16));
        // U16 与 I16 精度相同，都是 Int16（只是偏移不同）
        assertTrue(OutputSampleFormat.U16.matchesBitDepth(BitDepth.INT16));
        assertFalse(OutputSampleFormat.I16.matchesBitDepth(BitDepth.INT24));
        // null（= 不点名）永远不匹配任何格式
        assertFalse(OutputSampleFormat.I16.matchesBitDepth(null));
    }

    /** 对应上游 {@code unsupported_sample_format_is_reported_not_panicked}。 */
    @Test
    public void unsupportedSampleFormatIsReportedNotPanicked() {
        assertFalse(OutputSampleFormat.isSupported(DeviceSampleFormat.F64));
        AudioException e = assertThrows(AudioException.class,
                () -> OutputSampleFormat.fromDevice(DeviceSampleFormat.F64));
        assertEquals(AudioException.Kind.OUTPUT, e.kind());
        // 上游原文消息：设备要求的采样格式暂不支持：…
        assertTrue(e.getMessage(), e.getMessage().contains("暂不支持"));
    }

    /**
     * 对应上游 {@code dsd_formats_are_not_mapped_as_pcm}。
     *
     * <p>DSD 格式在 cpal 里是一等公民，但我们特意不把它映射成 PCM 格式，
     * 免得被当成普通采样格式误用。
     */
    @Test
    public void dsdFormatsAreNotMappedAsPcm() {
        DeviceSampleFormat[] dsd = {
                DeviceSampleFormat.DSD_U8,
                DeviceSampleFormat.DSD_U16,
                DeviceSampleFormat.DSD_U32,
        };
        for (int i = 0; i < dsd.length; i++) {
            final DeviceSampleFormat f = dsd[i];
            assertFalse(f + " 不该被当成 PCM 格式", OutputSampleFormat.isSupported(f));
            AudioException e = assertThrows("DSD 不该被当成 PCM 格式：" + f, AudioException.class,
                    () -> OutputSampleFormat.fromDevice(f));
            assertEquals(AudioException.Kind.OUTPUT, e.kind());
        }
    }

    // ------------------------------------------------------------------
    // 以下为移植时补充的测试（上游没有对应条目）
    // ------------------------------------------------------------------

    /** 5 种支持的设备格式都要能映射回来（上游只测了「不支持的报错」这一半）。 */
    @Test
    public void supportedDeviceFormatsMapBack() {
        assertEquals(OutputSampleFormat.F32, OutputSampleFormat.fromDevice(DeviceSampleFormat.F32));
        assertEquals(OutputSampleFormat.I16, OutputSampleFormat.fromDevice(DeviceSampleFormat.I16));
        assertEquals(OutputSampleFormat.U16, OutputSampleFormat.fromDevice(DeviceSampleFormat.U16));
        assertEquals(OutputSampleFormat.I24, OutputSampleFormat.fromDevice(DeviceSampleFormat.I24));
        assertEquals(OutputSampleFormat.I32, OutputSampleFormat.fromDevice(DeviceSampleFormat.I32));
        // javax.sound 的 8 位无符号与 μ-law/A-law 同样不开
        assertFalse(OutputSampleFormat.isSupported(DeviceSampleFormat.U8));
        assertFalse(OutputSampleFormat.isSupported(DeviceSampleFormat.OTHER));
    }

    /**
     * 字节打包：Java 这边写的是字节，而 cpal 那边写的是 {@code SizedSample}，
     * 所以「打包」这一步在上游没有对应测试，必须自己补上。
     */
    @Test
    public void samplePackingIsLittleEndian() {
        byte[] b = new byte[4];

        OutputSampleFormat.I16.writeSample(1.0f, b, 0);
        assertEquals(0xFF, b[0] & 0xFF);
        assertEquals(0x7F, b[1] & 0xFF);

        OutputSampleFormat.I24.writeSample(1.0f, b, 0);
        // 8388607 = 0x7FFFFF，第 4 字节是高 8 位的符号扩展（0x00）
        assertEquals(0xFF, b[0] & 0xFF);
        assertEquals(0xFF, b[1] & 0xFF);
        assertEquals(0x7F, b[2] & 0xFF);
        assertEquals("24 位按 4 字节存储时高字节必须是符号扩展", 0x00, b[3] & 0xFF);

        OutputSampleFormat.I24.writeSample(-1.0f, b, 0);
        // -8388607 = 0xFF800001（32 位补码）
        assertEquals(0x01, b[0] & 0xFF);
        assertEquals(0x00, b[1] & 0xFF);
        assertEquals(0x80, b[2] & 0xFF);
        assertEquals(0xFF, b[3] & 0xFF);

        OutputSampleFormat.F32.writeSample(1.0f, b, 0);
        assertEquals(0x00, b[0] & 0xFF);
        assertEquals(0x00, b[1] & 0xFF);
        assertEquals(0x80, b[2] & 0xFF);
        assertEquals(0x3F, b[3] & 0xFF);
    }

    /** 打包与解包往返：容差就是该格式的量化步长。 */
    @Test
    public void packingRoundTrips() {
        byte[] b = new byte[4];
        float[] values = {0.0f, 0.25f, 0.5f, -0.25f, -1.0f, 1.0f};

        OutputSampleFormat[] formats = {
                OutputSampleFormat.I16,
                OutputSampleFormat.I24,
                OutputSampleFormat.I32,
                OutputSampleFormat.F32,
        };
        for (int k = 0; k < formats.length; k++) {
            OutputSampleFormat f = formats[k];
            for (int i = 0; i < values.length; i++) {
                f.writeSample(values[i], b, 0);
                float back = f.readSample(b, 0);
                // F32 完全精确；整数格式的容差是 1 个量化步长（1/最大幅值）
                float tol = f == OutputSampleFormat.F32 ? 0f : 2.0f / 32_767.0f;
                assertEquals(f + " 往返 " + values[i], values[i], back, tol);
            }
        }
    }

    /** 无符号 16 位的打包：中点必须是 0x8000。 */
    @Test
    public void unsignedPackingUsesMidpointForSilence() {
        byte[] b = new byte[2];
        OutputSampleFormat.U16.writeSample(0.0f, b, 0);
        assertEquals(0x00, b[0] & 0xFF);
        assertEquals(0x80, b[1] & 0xFF);
        OutputSampleFormat.U16.writeSample(-1.0f, b, 0);
        assertEquals(0x00, b[0] & 0xFF);
        assertEquals(0x00, b[1] & 0xFF);
        OutputSampleFormat.U16.writeSample(1.0f, b, 0);
        assertEquals(0xFF, b[0] & 0xFF);
        assertEquals(0xFF, b[1] & 0xFF);
    }
}
