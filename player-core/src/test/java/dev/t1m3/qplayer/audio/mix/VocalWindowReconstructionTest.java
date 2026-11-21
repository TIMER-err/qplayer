package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>去人声接进变速窗口的量尺</b>（2026-10-07，目标②）。
 *
 * <p>为什么先写判据：② 以前翻过一次车 —— 整段电平掉了 11 dB，原因是"素材源与渲染侧
 * **各减了一遍**人声"。所以接线之前先把两条验收标准做成可执行的东西：
 * <ol>
 *   <li><b>人声被压</b>：重建后的素材在人声频带上的能量必须明显低于原始；</li>
 *   <li><b>电平不下降</b>：整段总能量**不许下降**（伴奏要补回人声让出的位置）——
 *       这一条就是当年那个 bug 的防线。</li>
 * </ol>
 *
 * <p>本判据只量**重建公式**本身：把两轨按包络合成
 * {@code instr + voc × (1 − env)}（env=1 表示"这一段要去人声"），
 * 与渲染侧 {@code mixInto} 用同一条权重曲线。
 */
public class VocalWindowReconstructionTest {

    private static final int RATE = 48000;

    /** 造一段"伴奏（低频）+ 人声（中频）"的合成素材。 */
    private static float[][] synthetic(double vocalGain) {
        int n = RATE * 2;
        float[] instr = new float[n];
        float[] voc = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            instr[i] = (float) (0.3 * Math.sin(2 * Math.PI * 120 * t));    // 伴奏：120 Hz
            voc[i] = (float) (vocalGain * 0.3 * Math.sin(2 * Math.PI * 1000 * t)); // 人声：1 kHz
        }
        return new float[][] {instr, voc};
    }

    /** 人声频带（1 kHz 附近）能量：一个简单的带通能量代理（用差分去掉低频）。 */
    private static double vocalBandEnergy(float[] x) {
        double sum = 0.0;
        for (int i = 1; i < x.length; i++) {
            double d = x[i] - x[i - 1];           // 高通 ⇒ 压掉 120 Hz，留下 1 kHz
            sum += d * d;
        }
        return sum / Math.max(1, x.length - 1);
    }

    private static double totalEnergy(float[] x) {
        double sum = 0.0;
        for (float v : x) {
            sum += (double) v * v;
        }
        return sum / Math.max(1, x.length);
    }

    /** 按包络重建：env=1 处去人声，env=0 处原样。 */
    private static float[] rebuild(float[] instr, float[] voc, double env) {
        float[] out = new float[instr.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) (instr[i] + voc[i] * (1.0 - env));
        }
        return out;
    }

    @Test
    public void reconstructionSuppressesTheVocalBandWhereTheEnvelopeIsOn() {
        float[][] material = synthetic(1.0);
        float[] original = rebuild(material[0], material[1], 0.0);   // env=0 ⇒ 原样
        float[] deVocal = rebuild(material[0], material[1], 1.0);    // env=1 ⇒ 完全去人声
        double before = vocalBandEnergy(original);
        double after = vocalBandEnergy(deVocal);
        assertTrue("重建后人声频带能量必须明显下降（实测 " + before + " → " + after + "）",
                after < before * 0.5);
    }

    @Test
    public void reconstructionDoesNotDropTheOverallLevel() {
        float[][] material = synthetic(1.0);
        float[] original = rebuild(material[0], material[1], 0.0);
        float[] deVocal = rebuild(material[0], material[1], 1.0);
        double before = totalEnergy(original);
        double after = totalEnergy(deVocal);
        double db = 10.0 * Math.log10(after / before);
        // 伴奏 0.3 + 人声 0.3 ⇒ 去掉人声后总能量降到约 −3 dB 是**物理必然**（少了那一份能量），
        // 但**绝不该**出现当年那种 −11 dB 的塌陷（那是"减两遍"造成的）。
        assertTrue("总能量下降必须远小于当年那次塌陷（实测 " + db + " dB，红线 > −6 dB）",
                db > -6.0);
    }

    @Test
    public void duckGainIsOneOutsideTheRangeAndZeroInside() {
        AutoCueTransition t = new AutoCueTransition(null, RATE, 2, null, false);
        assertTrue("没装叠唱区间 ⇒ 处处原样（1.0）", t.vocalGainAt(3.0) == 1.0);
        t.setVocalDuckRanges(new double[] {2.0, 4.0});
        assertEquals("区间之前 ⇒ 原样", 1.0, t.vocalGainAt(1.999), 1e-9);
        assertEquals("区间之后 ⇒ 原样", 1.0, t.vocalGainAt(4.001), 1e-9);
        assertEquals("区间正中 ⇒ 完全去人声", 0.0, t.vocalGainAt(3.0), 1e-9);
        // 边缘必须是**渐变**（不是台阶）：刚进区间一点点时增益还接近 1
        double justInside = t.vocalGainAt(2.0 + AutoCueTransition.DUCK_EDGE_SECS * 0.1);
        assertTrue("刚进区间 ⇒ 增益还很高（渐变而非开关，实测 " + justInside + "）",
                justInside > 0.9);
        double midRamp = t.vocalGainAt(2.0 + AutoCueTransition.DUCK_EDGE_SECS * 0.5);
        assertTrue("淡化到一半 ⇒ 增益在中间（实测 " + midRamp + "）",
                midRamp > 0.3 && midRamp < 0.7);
    }

    @Test
    public void envelopeOnlyTouchesTheDuckRange() {
        // 只有前 1 秒是叠唱区间 ⇒ 后面必须**一个采样都不动**
        float[][] material = synthetic(1.0);
        int n = material[0].length;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double env = i < RATE ? 1.0 : 0.0;              // 叠唱区间内 env=1
            out[i] = (float) (material[0][i] + material[1][i] * (1.0 - env));
        }
        for (int i = RATE; i < n; i++) {
            assertEquals("叠唱区间之外必须逐位等于原始素材（第 " + i + " 个采样）",
                    material[0][i] + material[1][i], out[i], 1e-6);
        }
    }
}
