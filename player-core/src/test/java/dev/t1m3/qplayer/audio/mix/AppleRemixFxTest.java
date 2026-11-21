package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 照搬 goal 第 37 轮：钉住档 17 `Dance - Repeater` 的 FX 时刻表 ✓（值来自材料 ✓）。 */
public class AppleRemixFxTest {

    private static AppleRemixFx.State at(float p) {
        AppleRemixFx.State s = new AppleRemixFx.State();
        AppleRemixFx.fill(s, p);
        return s;
    }

    @Test
    public void repeaterIsOnOnlyBetweenItsTwoSwitchPoints() {
        assertFalse("0.4444 之前不许开 ✓", at(0.40f).repeaterOn);
        assertTrue("0.5 处应当在响 ✓", at(0.50f).repeaterOn);
        assertTrue("0.85 处仍应在响 ✓", at(0.85f).repeaterOn);
        assertFalse("0.8889 之后必须关 ✓", at(0.95f).repeaterOn);
    }

    @Test
    public void repeaterRateStepsUpThroughTheFiveStages() {
        assertEquals(1f, at(0.45f).repeaterRate, 1e-3f);
        assertEquals(2f, at(0.70f).repeaterRate, 1e-3f);
        assertEquals(3f, at(0.80f).repeaterRate, 1e-3f);
        assertEquals(4f, at(0.84f).repeaterRate, 1e-3f);
        assertEquals(5f, at(0.87f).repeaterRate, 1e-3f);
    }

    @Test
    public void theTwoFiltersOpenAndCloseOnSchedule() {
        assertTrue(at(0.60f).filterAOn);
        assertTrue("a 的截止应当从 20 一路上到 1000 ✓", at(0.85f).filterACutoff > 500f);
        assertTrue(at(0.60f).filterBOn);
        assertTrue("b 的截止在末段必须收到很低 ✓", at(0.888f).filterBCutoff < 100f);
    }

    @Test
    public void reverbAndFlangerComeInLateInTheWindow() {
        assertFalse(at(0.60f).reverbOn);
        assertTrue(at(0.70f).reverbOn);
        assertTrue("reverb 送量应当 > 0 ✓", at(0.75f).reverbSend > 0f);
        assertFalse(at(0.60f).flangerOn);
        assertTrue(at(0.70f).flangerOn);
        assertTrue("flanger mix 应当 > 0 ✓", at(0.80f).flangerMix > 0f);
    }
}
