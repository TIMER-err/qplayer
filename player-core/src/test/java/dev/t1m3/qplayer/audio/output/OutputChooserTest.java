package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.BitDepth;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 能力挑选的对齐测试：对应 Sonalis {@code crates/sonalis-output/src/lib.rs} 的 {@code mod tests}。
 *
 * <p>上游 1 条 → 本地 1 条对齐（另有 7 条「额外补充」）：
 * <pre>
 * format_scoring_prefers_requested_then_f32    → formatScoringPrefersRequestedThenF32
 * </pre>
 *
 * <p>上游的 {@code choose_config} 没有单测（它要吃 {@code &cpal::Device}，必须有真声卡）。
 * 移植时把挑选逻辑改成只吃能力矩阵的纯函数，于是「选不到就报错，不静默降级」
 * （上游设计决定 ②）终于可以被测到——补的 7 条就是干这个的。
 */
public class OutputChooserTest {

    /** 一块「什么都能开」的假设备的矩阵：F32/I24/I16 都覆盖 44.1k~192k。 */
    private static DeviceCapabilities versatile() {
        return new DeviceCapabilities(Arrays.asList(
                new DeviceCapability(OutputSampleFormat.F32, 2, 44_100, 192_000),
                new DeviceCapability(OutputSampleFormat.I24, 2, 44_100, 192_000),
                new DeviceCapability(OutputSampleFormat.I16, 2, 44_100, 96_000)), null);
    }

    /** 对应上游 {@code format_scoring_prefers_requested_then_f32}。 */
    @Test
    public void formatScoringPrefersRequestedThenF32() {
        OutputSampleFormat[] noWish = {
                OutputSampleFormat.F32,
                OutputSampleFormat.I32,
                OutputSampleFormat.I24,
                OutputSampleFormat.I16,
        };
        int[] scores = new int[noWish.length];
        for (int i = 0; i < noWish.length; i++) {
            scores[i] = OutputChooser.scoreFormat(noWish[i], null);
        }
        // 上游：assert_eq!(scores, vec![90, 80, 70, 60], "没指定位深时 F32 应最优（内部格式直通）")
        assertEquals(90, scores[0]);
        assertEquals(80, scores[1]);
        assertEquals(70, scores[2]);
        assertEquals(60, scores[3]);
        assertEquals("U16 是最低分", 50, OutputChooser.scoreFormat(OutputSampleFormat.U16, null));

        // 指定 24 位时，I24 应当反超 F32
        assertTrue(OutputChooser.scoreFormat(OutputSampleFormat.I24, BitDepth.INT24)
                > OutputChooser.scoreFormat(OutputSampleFormat.F32, BitDepth.INT24));
    }

    // ------------------------------------------------------------------
    // 以下为移植时补充的测试（上游没有对应条目）
    // ------------------------------------------------------------------

    /** 不点名位深时选 F32：内部就是 f32，直通不转换。 */
    @Test
    public void choosesF32WhenNoBitDepthRequested() {
        OutputChooser.Chosen chosen = OutputChooser.choose(versatile(), OutputRequest.atRate(44_100, 9_600));
        assertEquals(OutputSampleFormat.F32, chosen.sampleFormat);
        assertEquals(2, chosen.channels);
        assertEquals("实际采样率就是请求的采样率（跟随源采样率是位完美的前提）",
                44_100, chosen.sampleRate);
    }

    /** 点名 I24 时必须真的选 I24——这条是 A1 的端到端验证。 */
    @Test
    public void bitDepthRequestIsHonoredEndToEnd() {
        OutputRequest req = OutputRequest.atRate(96_000, 9_600).withBitDepth(BitDepth.INT24);
        OutputChooser.Chosen chosen = OutputChooser.choose(versatile(), req);
        assertEquals("点名 24 bit 就该开 24 bit，而不是按 F32 直通", OutputSampleFormat.I24, chosen.sampleFormat);
        assertEquals(96_000, chosen.sampleRate);
        assertEquals(BitDepth.INT24, chosen.toConfig(9_600).bitDepth());
    }

    /**
     * 位深请求选不到时<strong>不报错</strong>，退回最接近的格式并如实汇报
     * （上游：位深不值得像采样率那样直接失败）。
     */
    @Test
    public void unfulfillableBitDepthFallsBackWithoutFailing() {
        DeviceCapabilities only16 = new DeviceCapabilities(Arrays.asList(
                new DeviceCapability(OutputSampleFormat.I16, 2, 44_100, 48_000)), null);
        OutputRequest req = OutputRequest.atRate(44_100, 9_600).withBitDepth(BitDepth.INT24);
        OutputChooser.Chosen chosen = OutputChooser.choose(only16, req);
        assertEquals("选不到 24 bit 就用设备有的 16 bit", OutputSampleFormat.I16, chosen.sampleFormat);
        assertEquals(BitDepth.INT16, chosen.toConfig(9_600).bitDepth());
    }

    /** 采样率选不到就报错，不静默降级（上游设计决定 ②）。 */
    @Test
    public void unsupportedRateIsReportedNotSilentlyDowngraded() {
        AudioException e = assertThrows(AudioException.class,
                () -> OutputChooser.choose(versatile(), OutputRequest.atRate(384_000, 9_600)));
        assertEquals(AudioException.Kind.OUTPUT, e.kind());
        // 上游消息：设备不支持 {format_rate(rate)} 的输出，请改用默认配置或降低请求
        assertTrue(e.getMessage(), e.getMessage().contains("384 kHz"));
        assertTrue(e.getMessage(), e.getMessage().contains("请改用默认配置或降低请求"));
    }

    /** 空矩阵 → 上游原文「设备没有声明任何受支持的输出配置」。 */
    @Test
    public void emptyMatrixIsReported() {
        AudioException e = assertThrows(AudioException.class,
                () -> OutputChooser.choose(DeviceCapabilities.empty(), OutputRequest.atRate(44_100, 4_800)));
        assertEquals(AudioException.Kind.OUTPUT, e.kind());
        assertTrue(e.getMessage(), e.getMessage().contains("没有声明任何受支持的输出配置"));
    }

    /** 声道数能满足就筛选；满足不了就放开——宁可换声道数也别直接失败。 */
    @Test
    public void channelFilterRelaxesWhenUnavailable() {
        DeviceCapabilities caps = new DeviceCapabilities(Arrays.asList(
                new DeviceCapability(OutputSampleFormat.F32, 2, 44_100, 192_000),
                new DeviceCapability(OutputSampleFormat.I24, 1, 44_100, 192_000)), null);

        // 要 2 声道：只有 F32 档位满足，即使 I24 打分更高也不能选
        OutputChooser.Chosen two = OutputChooser.choose(caps,
                OutputRequest.atRate(44_100, 4_800).withChannels(Integer.valueOf(2)));
        assertEquals(2, two.channels);
        assertEquals(OutputSampleFormat.F32, two.sampleFormat);

        // 要 8 声道：没有档位满足 → 放开筛选，回到全池打分（F32 胜出）而不是失败
        OutputChooser.Chosen relaxed = OutputChooser.choose(caps,
                OutputRequest.atRate(44_100, 4_800).withChannels(Integer.valueOf(8)));
        assertEquals(OutputSampleFormat.F32, relaxed.sampleFormat);
        assertEquals(2, relaxed.channels);
    }

    /** 「设备默认配置」：优先 2 声道 + 48 kHz + F32 直通。 */
    @Test
    public void defaultConfigPrefersStereo48kF32() {
        OutputChooser.Chosen chosen = OutputChooser.chooseDefault(versatile());
        assertEquals(2, chosen.channels);
        assertEquals(48_000, chosen.sampleRate);
        assertEquals(OutputSampleFormat.F32, chosen.sampleFormat);

        // 只有 44.1k 的设备退到 44.1k
        DeviceCapabilities cdOnly = new DeviceCapabilities(Arrays.asList(
                new DeviceCapability(OutputSampleFormat.I16, 2, 44_100, 44_100)), null);
        assertEquals(44_100, OutputChooser.chooseDefault(cdOnly).sampleRate);

        // 连 44.1k/48k 都没有：用矩阵里打分最高的档位，取它区间的最低档位表采样率
        DeviceCapabilities odd = new DeviceCapabilities(Arrays.asList(
                new DeviceCapability(OutputSampleFormat.I16, 1, 8_000, 32_000),
                new DeviceCapability(OutputSampleFormat.I24, 4, 16_000, 32_000)), null);
        OutputChooser.Chosen oddChosen = OutputChooser.chooseDefault(odd);
        assertEquals(OutputSampleFormat.I24, oddChosen.sampleFormat);
        assertEquals(4, oddChosen.channels);
        assertEquals(16_000, oddChosen.sampleRate);
    }
}
