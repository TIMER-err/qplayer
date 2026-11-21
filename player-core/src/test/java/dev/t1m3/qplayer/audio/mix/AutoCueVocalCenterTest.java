package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>中置显著度：给代理 VAD 去掉一类假阳性</b>（P6 的后半，「用 DSP 分出来的人声轨做活动检测」）。
 *
 * <p>代理 VAD（{@code AutoCue.voiceActive}）只有两条判据——「够响」与「在动」，于是
 * <b>中频上同样按音节起伏的吉他扫弦、琶音、加倍录的合成器会被误判成人声</b>；代码里一直
 * 如实写着这一条，也正因如此「叠唱 ≤0.5 s 的硬约束」当年没上（软惩罚权重要压到 10%）。
 *
 * <p>人声几乎总是录在<b>中间</b>的，而被摆开/加倍录/宽混响的那几类不是——中置显著度正好
 * 把这两类分开，而且是纯 DSP（{@code mid = (L+R)/2}、{@code side = (L−R)/2}），不需要模型。
 *
 * <p>判据先钉数学（居中 1.0 / 摆开 ≈0.5 / 纯侧 0.0），再钉「摆开的乐器不算人声」，
 * 最后钉「没有中置数据时与加这个字段之前逐位相同」。
 */
public class AutoCueVocalCenterTest {

    private static final int HOPS = 80 * 50;

    /** 中频按音节起伏（2 Hz 方波状）、够响、低频高频都很小的包络；{@code center} 可给可不给。 */
    private static AutoCue.Envelope singing(float level, Float centerValue) {
        float[] rms = new float[HOPS];
        float[][] band = new float[HOPS][];
        float[] center = centerValue == null ? null : new float[HOPS];
        Arrays.fill(rms, level);
        for (int i = 0; i < HOPS; i++) {
            float mid = (i / 5) % 2 == 0 ? level : level * 0.35f;
            band[i] = new float[] {0.05f, mid, 0.05f};
            if (center != null) {
                center[i] = centerValue.floatValue();
            }
        }
        return new AutoCue.Envelope(rms, new float[HOPS], 80.0, null, 0, band, null, 0,
                -1f, -1f, center);
    }

    // ==================================================================
    // ① 数学
    // ==================================================================

    /** 居中 1.0 / 硬摆到一边 ≈0.5 / 纯侧 0.0 / 静音按中性。 */
    @Test
    public void theSalienceMathIsWhatTheDocSays() {
        assertEquals("居中（L=R）", 1.0f, AutoCue.centerSalience(1.0, 0.0), 1e-6f);
        assertEquals("硬摆到一边（L=x, R=0）：中置与侧向各一半", 0.5f,
                AutoCue.centerSalience(0.25, 0.25), 1e-6f);
        assertEquals("纯侧（L=−R）", 0.0f, AutoCue.centerSalience(0.0, 1.0), 1e-6f);
        assertEquals("静音：没有「摆开」可言，按中性（不否决）", 1.0f,
                AutoCue.centerSalience(0.0, 0.0), 1e-6f);
        System.out.println("[center] 门槛 " + AutoCue.CENTER_SALIENCE_MIN
                + "：居中 1.0 ✔ / 摆开 0.5 ✗ / 纯侧 0.0 ✗");
    }

    // ==================================================================
    // ② 假阳性：摆开的乐器不算人声
    // ==================================================================

    /** 中频又响又在动，但整轨被摆到两边（0.50）→ <b>不算人声</b>。 */
    @Test
    public void aPannedInstrumentIsNotAVoice() {
        AutoCue.Envelope panned = singing(0.5f, Float.valueOf(0.50f));
        assertFalse("被摆开的中频起伏（吉他扫弦/琶音那种）不该判成人声",
                AutoCue.voiceActive(panned, 100));

        AutoCue.Envelope centered = singing(0.5f, Float.valueOf(0.95f));
        assertTrue("同素材居中时就该判成人声——上面那条不是靠「什么都不做」过的",
                AutoCue.voiceActive(centered, 100));
        System.out.println("[center] 同一段中频起伏：摆开 ✗ / 居中 ✔");
    }

    /** 门槛附近：0.5（摆开）✗、0.62（刚好到门槛）✔、0.65 ✔。 */
    @Test
    public void theThresholdSitsJustAboveHardPanned() {
        assertFalse("0.50（硬摆开）", AutoCue.voiceActive(singing(0.5f, Float.valueOf(0.50f)), 100));
        assertTrue("0.62（正好门槛）",
                AutoCue.voiceActive(singing(0.5f, Float.valueOf(0.62f)), 100));
        assertTrue("0.65（居中的真人声带一点立体声混响）",
                AutoCue.voiceActive(singing(0.5f, Float.valueOf(0.65f)), 100));
    }

    // ==================================================================
    // ③ 没有数据 = 与从前逐位相同
    // ==================================================================

    /** 单声道素材（{@code center == null}）→ VAD 的行为与加这个字段之前完全一样。 */
    @Test
    public void withoutCenterDataNothingChanges() {
        assertTrue("没有中置数据时不否决（向后兼容）",
                AutoCue.voiceActive(singing(0.5f, null), 100));
        assertEquals("没有中置数据时叠唱秒数与从前一致", 5.0,
                AutoCue.vocalOverlapSecs(singing(0.5f, null), singing(0.5f, null), 0, 0, 250), 0.05);
    }

    // ==================================================================
    // ④ 端到端：叠唱只算「两边都居中在唱」的那一段
    // ==================================================================

    /**
     * 出场曲全程居中在唱，进场曲前 5 秒是<b>被摆开的扫弦</b>、之后才居中在唱 →
     * 叠唱必须只算后面那一段（0 秒），而不是把前 5 秒也算进去。
     */
    @Test
    public void theOverlapOnlyCountsCenteredSinging() {
        AutoCue.Envelope outro = singing(0.5f, Float.valueOf(0.95f));

        AutoCue.Envelope pannedIntro = singing(0.5f, Float.valueOf(0.50f));
        double withPanned = AutoCue.vocalOverlapSecs(outro, pannedIntro, 0, 0, 250);
        assertEquals("被摆开的那 5 秒不该算叠唱", 0.0, withPanned, 0.05);

        AutoCue.Envelope centeredIntro = singing(0.5f, Float.valueOf(0.95f));
        double withVoice = AutoCue.vocalOverlapSecs(outro, centeredIntro, 0, 0, 250);
        assertTrue("真正两边都在唱时叠唱必须是满的（" + withVoice + " s）", withVoice > 4.5);
        System.out.println("[center] 叠唱秒数：对面是被摆开的扫弦 " + withPanned
                + " s，对面是居中的人声 " + withVoice + " s");
    }
}
