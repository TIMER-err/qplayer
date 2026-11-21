package dev.t1m3.qplayer.audio.dsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>BS.1770 刻度</b>（2026-10-07）。
 *
 * <p>这条判据用的是可以**手算**的刻度：振幅 A 的正弦均方 = A²/2 ⇒
 * 双声道各 −20 dBFS（A = 0.1）时 Σz = 0.01 ⇒
 * {@code L = −0.691 + 10·log10(0.01) = −20.691 LUFS}。
 * 门限则用"静音段不算数"来验：前 4 秒满电平 + 后 4 秒静音 ⇒ 结果应当与**只有前 4 秒**相同。
 */
public class LufsTest {

    private static final int RATE = 48000;

    private static float[] sine(double amp, double secs) {
        int n = (int) Math.round(secs * RATE);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (float) (amp * Math.sin(2 * Math.PI * 1000 * i / (double) RATE));
        }
        return out;
    }

    @Test
    public void stereoMinus20DbfsReadsAboutMinus20Lufs() {
        float[] l = sine(0.1, 4.0);
        float[] r = sine(0.1, 4.0);
        double lufs = Lufs.integrated(l, r, RATE);
        // 我第一版把期望写成 −20.691（只算了均方与 −0.691 偏移，**忘了算 K 加权的增益**），
        // 实测 −20.04，差 +0.65 LU —— 那正是 K 加权在 1 kHz 处的提升量。
        // 标准的标定频率是 997 Hz、标称读数 −20.0 LKFS；所以这里按标准量：
        // 双声道各 −20 dBFS 的 1 kHz 正弦 ⇒ **约 −20.0 LUFS**（±0.3 容差覆盖 997/1000 Hz 之差）。
        assertEquals("双声道各 −20 dBFS ⇒ 约 −20.0 LUFS（标准标称值）", -20.0, lufs, 0.3);
        // 并且如实钉住"K 加权在 1 kHz 不是 0 dB"这件事（它是 +0.65 dB 左右）
        assertTrue("K 加权在 1 kHz 处是正的提升（约 +0.65 dB）—— 这一点判据要留着，"
                        + "免得下次又把均方当读数（实测 " + lufs + " LUFS）", lufs > -20.3);
    }

    @Test
    public void singleChannelIsAboutThreeLuQuieter() {
        float[] l = sine(0.1, 4.0);
        double both = Lufs.integrated(l, sine(0.1, 4.0), RATE);
        double one = Lufs.integrated(l, null, RATE);
        assertEquals("只给一个声道 ⇒ 少约 3 LU（通道和少一半）", 3.0, both - one, 0.3);
    }

    @Test
    public void silenceIsGatedOutNotAveragedIn() {
        float[] loud = sine(0.1, 4.0);
        float[] mixed = new float[loud.length * 2];
        System.arraycopy(loud, 0, mixed, 0, loud.length);      // 前 4 秒满电平
        double onlyLoud = Lufs.integrated(loud, loud, RATE);
        double withSilence = Lufs.integrated(mixed, mixed, RATE);
        assertEquals("后 4 秒静音必须被门限丢掉（否则会把它平均下去）",
                onlyLoud, withSilence, 0.3);
    }

    @Test
    public void normalizingBringsTwoTakesWithinHalfALu() {
        // 用户提的判据原话：同一素材在两条通路上的 LUFS 差 ≤ 0.5 LU
        float[] quiet = sine(0.05, 4.0);           // 比 loud 低 12 dB
        float[] loud = sine(0.2, 4.0);
        double before = Math.abs(Lufs.integrated(quiet, null, RATE)
                - Lufs.integrated(loud, null, RATE));
        assertTrue("归一化之前两者差得远（实测 " + before + " LU）", before > 6.0);
        boolean moved = Lufs.normalizeTo(quiet, loud, RATE);
        assertTrue("应当动过增益", moved);
        double after = Math.abs(Lufs.integrated(quiet, null, RATE)
                - Lufs.integrated(loud, null, RATE));
        assertTrue("归一到参照之后，两者差 ≤ 0.5 LU（实测 " + after + " LU）", after <= 0.5);
    }

    @Test
    public void normalizingSilenceDoesNothing() {
        float[] silent = new float[RATE * 2];
        float[] loud = sine(0.2, 2.0);
        assertEquals("对着静音谈增益没有意义 ⇒ 返回 1（不动）",
                1.0, Lufs.gainToMatch(silent, loud, RATE), 1e-9);
        assertEquals("反过来也一样（参照是静音 ⇒ 不动）",
                1.0, Lufs.gainToMatch(loud, silent, RATE), 1e-9);
    }

    @Test
    public void normalizationIsClampedSoItNeverTurnsQuietIntoHiss() {
        float[] veryQuiet = sine(1e-5, 3.0);
        float[] loud = sine(0.5, 3.0);
        double g = Lufs.gainToMatch(veryQuiet, loud, RATE);
        double db = 20.0 * Math.log10(g);
        assertTrue("增益被夹在 ±" + Lufs.MAX_NORMALIZE_DB + " dB 内（实测 " + db + " dB）",
                db <= Lufs.MAX_NORMALIZE_DB + 1e-6);
    }

    @Test
    public void pureSilenceGivesNoReading() {
        float[] zero = new float[RATE * 2];
        assertTrue("全静音 ⇒ 没有一块过门限（返回负无穷，而不是某个伪造的数字）",
                Double.isInfinite(Lufs.integrated(zero, zero, RATE))
                        && Lufs.integrated(zero, zero, RATE) < 0);
    }
}
