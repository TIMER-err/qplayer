package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>「这一段本来就没有人声」的判据</b>（路线图 W3 的后半，2026-10-02）。
 *
 * <p>为什么要它：没有东西可去的时候就不该去人声——**省下一次分离**（DSP 档几百毫秒、
 * AI 档秒级），也不让素材白白多过一遍分离器（那会改音色）。
 * 判据走既有的诚实降级那条路：返回一句原因，日志写清，输出与「没套这一层」逐位相同。
 *
 * <p>判据本身是**相对量**（人声轨平均幅度 vs 伴奏轨），不是绝对阈值——素材响度差很多个
 * 量级，绝对阈值会把安静那首一律判成「没有人声」。
 */
public class VocalSeparationSkipTest {

    private static final float[] QUIET = filled(1000, 1e-4f);

    /** 人声轨可以忽略（不到伴奏的 5%）→ 跳过。 */
    @Test
    public void aNegligibleVocalTrackMeansNothingToRemove() {
        assertTrue("人声轨只有伴奏的 1%，应当判成「本来没有人声」",
                VocalSeparationTransition.vocalAbsent(filled(1000, 0.001f), filled(1000, 0.001f),
                        filled(1000, 0.2f), filled(1000, 0.2f)));
    }

    /** 人声轨有实质能量 → 必须照旧去人声。 */
    @Test
    public void aRealVocalTrackIsNotSkipped() {
        assertFalse("人声轨与伴奏同量级时绝不能跳过",
                VocalSeparationTransition.vocalAbsent(filled(1000, 0.2f), filled(1000, 0.2f),
                        filled(1000, 0.2f), filled(1000, 0.2f)));
    }

    /** 读不到轨（分离失败/被清理）→ **照旧去人声**：不许拿「读不到」当「没有」。 */
    @Test
    public void missingTracksAreNeverTreatedAsSilence() {
        assertFalse("人声轨读不到时不该跳过",
                VocalSeparationTransition.vocalAbsent(null, null,
                        filled(1000, 0.2f), filled(1000, 0.2f)));
        assertFalse("伴奏轨读不到时不该跳过",
                VocalSeparationTransition.vocalAbsent(QUIET, QUIET, null, filled(1000, 0.2f)));
        assertFalse("两边都读不到时不该跳过",
                VocalSeparationTransition.vocalAbsent(null, null, null, null));
    }

    /** 两边都是静音：伴奏也是 0 → 不跳过（同样是「读不出来什么」，交给原来的路径处理）。 */
    @Test
    public void totalSilenceIsNotAJudgementCall() {
        assertFalse("两边都是 0 时不该自己下结论",
                VocalSeparationTransition.vocalAbsent(filled(1000, 0.0f), filled(1000, 0.0f),
                        filled(1000, 0.0f), filled(1000, 0.0f)));
    }

    private static float[] filled(int n, float value) {
        float[] a = new float[n];
        java.util.Arrays.fill(a, value);
        return a;
    }
}
