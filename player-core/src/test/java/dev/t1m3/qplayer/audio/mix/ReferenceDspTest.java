package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * {@link ReferenceDsp} 的判据：参数表条数、默认值规则、vocoder 归一化。
 *
 * <p>参考包 {@code default_value} 的规则很特别：{@code out_gain} 与"非四字符 id"一律返回
 * fallback（渲染器显式置 1.0），其余返回原生默认值 —— 这条错了会让所有路径多出一份增益。
 */
public class ReferenceDspTest {

    @Test
    public void 参数表有69条() {
        assertEquals(69, ReferenceDsp.descriptors().size());
    }

    @Test
    public void 默认值规则跟参考包一致() {
        // Ga1g = player_gain，四字符但不是 out_gain ⇒ 原生默认值
        assertEquals(Double.valueOf(1.0), ReferenceDsp.defaultValue("player_gain", Double.valueOf(7.0)));
        // out_gain ⇒ fallback
        ReferenceDsp.Descriptor outGain = null;
        for (ReferenceDsp.Descriptor d : ReferenceDsp.descriptors()) {
            if ("out_gain".equals(d.id)) {
                outGain = d;
            }
        }
        assertNotNull("表里应当有 out_gain", outGain);
        assertEquals(Double.valueOf(0.25), ReferenceDsp.defaultValue(outGain.styleParameterId, Double.valueOf(0.25)));
        // 非四字符 id（ts_rate）⇒ 也是 fallback
        assertNull(ReferenceDsp.defaultValue("ts_rate", null));
    }

    @Test
    public void 参数区间夹取生效() {
        // remixfx_filter_a_cutoff 的区间在表里（1000 附近），越界要夹住
        ReferenceDsp.Descriptor d = ReferenceDsp.descriptor("remixfx_filter_a_cutoff");
        assertEquals(d.rangeHigh, ReferenceDsp.clampToRange("remixfx_filter_a_cutoff",
                d.rangeHigh + 5000.0), 1e-12);
        assertEquals(d.rangeLow, ReferenceDsp.clampToRange("remixfx_filter_a_cutoff",
                d.rangeLow - 5000.0), 1e-12);
        // 区间内的值原样返回
        double mid = (d.rangeLow + d.rangeHigh) / 2.0;
        assertEquals(mid, ReferenceDsp.clampToRange("remixfx_filter_a_cutoff", mid), 1e-12);
    }

    @Test
    public void vocoder状态夹取跟参考包一致() {
        ReferenceDsp.VocoderState s = ReferenceDsp.vocoderState(0.001, 3000.0, 1.0, true, true);
        assertEquals(0.03125, s.rate, 1e-15);
        assertEquals(2400.0, s.pitchCents, 1e-15);
        assertEquals(Math.pow(2.0, 2.0), s.pitchRatio, 1e-15);
        assertEquals(3.0, s.smoothness, 1e-15);
        assertEquals(true, s.transientPreservation);
        ReferenceDsp.VocoderState t = ReferenceDsp.vocoderState(100.0, 0.0, 100.0, false, true);
        assertEquals(32.0, t.rate, 1e-15);
        assertEquals(32.0, t.smoothness, 1e-15);
        assertEquals(false, t.transientPreservation);
    }

    @Test
    public void 逐条默认值与参考包一致() {
        assertEquals("player_gain", "Ga1g", ReferenceDsp.descriptor("player_gain").id);
        assertEquals("player_gain", Double.valueOf(1.0), ReferenceDsp.defaultValue("player_gain", null));
        assertEquals("remixfx_beats_per_minute", "RXxt", ReferenceDsp.descriptor("remixfx_beats_per_minute").id);
        assertEquals("remixfx_beats_per_minute", Double.valueOf(120.0), ReferenceDsp.defaultValue("remixfx_beats_per_minute", null));
        assertEquals("remixfx_filter_a_on", "RXae", ReferenceDsp.descriptor("remixfx_filter_a_on").id);
        assertEquals("remixfx_filter_a_on", Double.valueOf(0.0), ReferenceDsp.defaultValue("remixfx_filter_a_on", null));
        assertEquals("remixfx_filter_a_cutoff", "RXaf", ReferenceDsp.descriptor("remixfx_filter_a_cutoff").id);
        assertEquals("remixfx_filter_a_cutoff", Double.valueOf(1000.0), ReferenceDsp.defaultValue("remixfx_filter_a_cutoff", null));
        assertEquals("remixfx_filter_a_resonance", "RXar", ReferenceDsp.descriptor("remixfx_filter_a_resonance").id);
        assertEquals("remixfx_filter_a_resonance", Double.valueOf(0.0), ReferenceDsp.defaultValue("remixfx_filter_a_resonance", null));
        assertEquals("remixfx_filter_a_type", "RXat", ReferenceDsp.descriptor("remixfx_filter_a_type").id);
        assertEquals("remixfx_filter_a_type", Double.valueOf(0.0), ReferenceDsp.defaultValue("remixfx_filter_a_type", null));
        assertEquals("remixfx_filter_b_on", "RXbe", ReferenceDsp.descriptor("remixfx_filter_b_on").id);
        assertEquals("remixfx_filter_b_on", Double.valueOf(0.0), ReferenceDsp.defaultValue("remixfx_filter_b_on", null));
        assertEquals("remixfx_filter_b_cutoff", "RXbf", ReferenceDsp.descriptor("remixfx_filter_b_cutoff").id);
        assertEquals("remixfx_filter_b_cutoff", Double.valueOf(1000.0), ReferenceDsp.defaultValue("remixfx_filter_b_cutoff", null));
        assertEquals("remixfx_filter_b_resonance", "RXbr", ReferenceDsp.descriptor("remixfx_filter_b_resonance").id);
        assertEquals("remixfx_filter_b_resonance", Double.valueOf(0.0), ReferenceDsp.defaultValue("remixfx_filter_b_resonance", null));
        assertEquals("remixfx_filter_b_type", "RXbt", ReferenceDsp.descriptor("remixfx_filter_b_type").id);
        assertEquals("remixfx_filter_b_type", Double.valueOf(0.0), ReferenceDsp.defaultValue("remixfx_filter_b_type", null));
        assertEquals("remixfx_repeater_on", "RXpe", ReferenceDsp.descriptor("remixfx_repeater_on").id);
        assertEquals("remixfx_repeater_on", Double.valueOf(0.0), ReferenceDsp.defaultValue("remixfx_repeater_on", null));
        assertEquals("remixfx_repeater_rate", "RXpr", ReferenceDsp.descriptor("remixfx_repeater_rate").id);
        assertEquals("remixfx_repeater_rate", Double.valueOf(1.0), ReferenceDsp.defaultValue("remixfx_repeater_rate", null));
        assertEquals("remixfx_repeater_mix", "RXpm", ReferenceDsp.descriptor("remixfx_repeater_mix").id);
        assertEquals("remixfx_repeater_mix", Double.valueOf(1.0), ReferenceDsp.defaultValue("remixfx_repeater_mix", null));
        assertEquals("remixfx_reverb_on", "RXve", ReferenceDsp.descriptor("remixfx_reverb_on").id);
        assertEquals("remixfx_reverb_on", Double.valueOf(0.0), ReferenceDsp.defaultValue("remixfx_reverb_on", null));
        assertEquals("remixfx_reverb_time", "RXvt", ReferenceDsp.descriptor("remixfx_reverb_time").id);
        assertEquals("remixfx_reverb_time", Double.valueOf(1.0), ReferenceDsp.defaultValue("remixfx_reverb_time", null));
        assertEquals("remixfx_reverb_send", "RXvs", ReferenceDsp.descriptor("remixfx_reverb_send").id);
        assertEquals("remixfx_reverb_send", Double.valueOf(0.0), ReferenceDsp.defaultValue("remixfx_reverb_send", null));
        assertEquals("remixfx_reverb_dry", "RXvd", ReferenceDsp.descriptor("remixfx_reverb_dry").id);
        assertEquals("remixfx_reverb_dry", Double.valueOf(1.0), ReferenceDsp.defaultValue("remixfx_reverb_dry", null));
        assertEquals("remixfx_reverb_wet", "RXvw", ReferenceDsp.descriptor("remixfx_reverb_wet").id);
        assertEquals("remixfx_reverb_wet", Double.valueOf(1.0), ReferenceDsp.defaultValue("remixfx_reverb_wet", null));
        assertEquals("remixfx_delay_on", "RXde", ReferenceDsp.descriptor("remixfx_delay_on").id);
        assertEquals("remixfx_delay_on", Double.valueOf(0.0), ReferenceDsp.defaultValue("remixfx_delay_on", null));
        assertEquals("remixfx_delay_rate", "RXdr", ReferenceDsp.descriptor("remixfx_delay_rate").id);
        assertEquals("remixfx_delay_rate", Double.valueOf(10.0), ReferenceDsp.defaultValue("remixfx_delay_rate", null));
    }
}
