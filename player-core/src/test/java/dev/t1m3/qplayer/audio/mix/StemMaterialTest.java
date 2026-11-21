package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>分轨重建</b>（2026-10-07，目标②）—— 用户实测"突然静音"之后立的反面判据。
 *
 * <p>三条性质：
 * <ol>
 *   <li>增益处处 1 ⇒ 与未分离素材**逐位相同**（所以"没开分离"时零开销、零变化）；</li>
 *   <li>增益 0 ⇒ 人声频带被压，但**伴奏必须还在**（绝不能整段静音 —— 那就是用户听到的 bug）；</li>
 *   <li>没有分轨结果（{@code vocals == null}）⇒ 任何增益都**原样返回**（不猜）。</li>
 * </ol>
 */
public class StemMaterialTest {

    private static final int RATE = 48000;

    private static StemMaterial material() {
        int n = RATE;
        float[] instr = new float[n];
        float[] voc = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            instr[i] = (float) (0.3 * Math.sin(2 * Math.PI * 120 * t));      // 伴奏
            voc[i] = (float) (0.3 * Math.sin(2 * Math.PI * 1000 * t));       // 人声
        }
        return new StemMaterial(new float[][] {instr.clone(), instr.clone()}, voc);
    }

    private static double hfEnergy(float[] x) {
        double s = 0.0;
        for (int i = 1; i < x.length; i++) {
            double d = x[i] - x[i - 1];
            s += d * d;
        }
        return s / Math.max(1, x.length - 1);
    }

    private static double totalEnergy(float[] x) {
        double s = 0.0;
        for (float v : x) {
            s += (double) v * v;
        }
        return s / Math.max(1, x.length);
    }

    @Test
    public void unityGainIsBitIdenticalToTheOriginal() {
        StemMaterial m = material();
        float[][] same = m.mixWith(StemMaterial.rampGain(m.frames(), 0, 0, 0f));
        for (int c = 0; c < m.instrumental.length; c++) {
            for (int i = 0; i < m.frames(); i++) {
                assertEquals("处处增益 1 ⇒ 逐位相同（第 " + c + " 声道第 " + i + " 帧）",
                        m.instrumental[c][i] + m.vocals[i], same[c][i], 1e-6);
            }
        }
    }

    @Test
    public void zeroGainRemovesVocalsButKeepsTheAccompaniment() {
        StemMaterial m = material();
        float[] zero = StemMaterial.rampGain(m.frames(), 0, m.frames(), 0f);
        float[][] deVocal = m.mixWith(zero);
        float[] original = m.mixWith(null)[0];
        assertTrue("人声频带必须被压下去",
                hfEnergy(deVocal[0]) < hfEnergy(original) * 0.5);
        // **关键：伴奏必须还在** —— 用户听到的"突然静音"就是这里错了
        assertTrue("伴奏必须还在（总能量不许归零，实测 " + totalEnergy(deVocal[0]) + "）",
                totalEnergy(deVocal[0]) > 0.01);
        double db = 10.0 * Math.log10(totalEnergy(deVocal[0]) / totalEnergy(original));
        assertTrue("总能量降幅应当接近「少掉人声那一份」（实测 " + db + " dB，红线 > −6 dB）",
                db > -6.0);
    }

    @Test
    public void withoutStemsEverythingIsReturnedUntouched() {
        int n = 1000;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = (float) Math.sin(i * 0.01);
        }
        StemMaterial m = StemMaterial.withoutStems(new float[][] {x.clone(), x.clone()});
        assertTrue("没有分轨 ⇒ hasStems 为假", !m.hasStems());
        float[] zero = StemMaterial.rampGain(n, 0, n, 0f);
        float[][] out = m.mixWith(zero);
        for (int i = 0; i < n; i++) {
            assertEquals("没有分轨 ⇒ 增益再狠也原样返回（不猜）", x[i], out[0][i], 1e-9);
        }
    }
}
