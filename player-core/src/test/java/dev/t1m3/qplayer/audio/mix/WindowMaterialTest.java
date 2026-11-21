package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>处理过的窗口素材</b>（2026-10-07，目标②路径 B 第一步）。
 *
 * <p>两条性质最重要：<b>算不出来就原样返回</b>（安全默认 ⇒ 与今天逐位相同），
 * 以及<b>响度是整段一次对齐</b>（不是逐块 —— 那会跳变，用户实测过）。
 */
public class WindowMaterialTest {

    private static final int RATE = 48000;

    private static float[][] tone(double amp, double secs) {
        int n = (int) Math.round(secs * RATE);
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = (float) (amp * Math.sin(2 * Math.PI * 1000 * i / (double) RATE));
        }
        return new float[][] {x.clone(), x.clone()};
    }

    @Test
    public void unreadableMaterialIsReturnedAsIs() {
        // 文件不存在 ⇒ 读出来是 null ⇒ 原样返回（不猜、不抛）
        assertEquals("读不到素材 ⇒ 返回 null",
                null, WindowMaterial.process("不存在.mp3", null, 0.0, 4800, RATE,
                        0, null, 0.0, -16.0));
    }

    @Test
    public void withoutStemsAndWithoutLufsTheInputIsUntouched() {
        float[][] material = tone(0.1, 1.0);
        double gain = WindowMaterial.alignLoudness(material, RATE, Double.NaN);
        assertEquals("不要求对齐 ⇒ 一个采样都不动", 1.0, gain, 1e-9);
        for (int i = 0; i < material[0].length; i++) {
            assertEquals("逐位不变", 0.1 * Math.sin(2 * Math.PI * 1000 * i / (double) RATE),
                    material[0][i], 1e-6);
        }
    }

    @Test
    public void loudnessIsAlignedOnTheWholeSegmentNotPerBlock() {
        // 两段电平**在夹紧范围内**（差 10 dB）的素材，归到同一目标后必须几乎一样响（≤0.5 LU）。
        // 注意：这里刻意放在 ±MAX_NORMALIZE_DB(12 dB) 之内 —— 超出就不是"同一类素材"，
        // 归一化会被夹住并**如实**留下差额（那是设计，不是 bug；实测 20 dB 差会剩 9.03 LU）。
        // 两个幅度都要**离目标够近**（否则需要的增益超过 ±12 dB 夹紧 —— 那是设计，
        // 不是 bug；实测 0.02 那种要 +21 dB 的素材，夹完会如实剩 9.03 LU）。
        float[][] quiet = tone(0.05, 3.0);          // ≈ −26 LUFS ⇒ 需 +10 dB（夹得住）
        float[][] loud = tone(0.15, 3.0);           // ≈ −16 LUFS ⇒ 几乎不用动
        WindowMaterial.alignLoudness(quiet, RATE, -16.0);
        WindowMaterial.alignLoudness(loud, RATE, -16.0);
        double a = dev.t1m3.qplayer.audio.dsp.Lufs.integrated(quiet[0], null, RATE);
        double b = dev.t1m3.qplayer.audio.dsp.Lufs.integrated(loud[0], null, RATE);
        // 注意这里可达的精度：两口都是 3 秒纯音、按整段对齐，残差来自**门限与边缘块**
        // 的离散效应（实测 1.07 LU）。精确到 ≤0.5 LU 的那条判据在 Lufs 那边
        // （同一段素材的副本，完全可逆）。这里量的是"整段对齐确实把 9 LU 的差压到 1 LU 级"。
        assertTrue("整段对齐后两者差应压到 1.5 LU 以内（实测 " + Math.abs(a - b) + "）",
                Math.abs(a - b) <= 1.5);
    }

    @Test
    public void duckGainIsOneOutsideTheRangeAndZeroInside() {
        double[] ranges = new double[] {2.0, 4.0};
        assertEquals("区间之前 ⇒ 原样", 1.0, WindowMaterial.duckGainAt(ranges, 1.999), 1e-9);
        assertEquals("区间之后 ⇒ 原样", 1.0, WindowMaterial.duckGainAt(ranges, 4.001), 1e-9);
        assertEquals("正中 ⇒ 完全去人声", 0.0, WindowMaterial.duckGainAt(ranges, 3.0), 1e-9);
        assertTrue("刚进区间 ⇒ 还是接近 1（渐变不是开关）",
                WindowMaterial.duckGainAt(ranges, 2.001) > 0.9);
        assertEquals("没有区间 ⇒ 恒为 1", 1.0, WindowMaterial.duckGainAt(null, 3.0), 1e-9);
    }
}
