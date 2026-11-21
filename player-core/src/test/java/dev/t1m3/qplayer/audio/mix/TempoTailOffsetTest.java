package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * **尾部直通的源位置必须靠"搜索"而不是"套公式"**（2026-10-09 ✓）。
 *
 * <p>上一轮用文档公式 `src(frames) = frames·(s0+s1)/2` 直接取源，判据测出相关 **−0.997**（反相 ✗）
 * ⇒ 结尾的样本被 OLA 换过相位，公式位置并不等于真实对应位置 ✗。
 *
 * <p>本判据做两件事 ✓：
 * ① 在 ±60 ms 内搜索与"输出尾部 40 ms"最相关的源偏移 ✓（这是生产代码要用的方法 ✓）
 * ② 验证确实存在高相关偏移（> 0.99）✓ —— 存在才说明"可以拿某一段源样本去直通"✓
 */
public class TempoTailOffsetTest {
    private static final int RATE = 48000;
    private static final int WINDOW = RATE;
    private static final int GRAIN = (int) Math.round(0.040 * RATE);

    private static float[][] sine(int frames, double hz) {
        float[] ch = new float[frames];
        for (int i = 0; i < frames; i++) {
            ch[i] = (float) (0.5 * Math.sin(2.0 * Math.PI * hz * i / RATE));
        }
        return new float[][] {ch, ch.clone()};
    }

    private static double corr(float[] a, int aFrom, float[] b, int bFrom, int n) {
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
        for (int i = 0; i < n; i++) {
            double x = a[aFrom + i], y = b[bFrom + i];
            sa += x; sb += y; saa += x * x; sbb += y * y; sab += x * y;
        }
        double va = saa - sa * sa / n, vb = sbb - sb * sb / n;
        if (va <= 1e-12 || vb <= 1e-12) {
            return 0;
        }
        return (sab - sa * sb / n) / Math.sqrt(va * vb);
    }

    @Test
    public void someSourceOffsetMatchesTheStretchTailClosely() {
        float[][] source = sine(WINDOW + RATE, 220.0);
        float[][] out = TempoBridge.stretchConverge(source, RATE, WINDOW, 1.05, 1.0, 0.5);
        assertTrue("变速必须产出整窗长度", out != null && out[0].length >= WINDOW);
        int outFrom = WINDOW - GRAIN;
        double srcEnd = WINDOW * (1.05 + 1.0) * 0.5;
        int base = (int) Math.round(srcEnd) - GRAIN;
        double bestR = -2;
        int bestOff = 0;
        int span = (int) Math.round(0.060 * RATE);
        for (int off = -span; off <= span; off++) {
            int sf = base + off;
            if (sf < 0 || sf + GRAIN >= source[0].length) {
                continue;
            }
            double rr = corr(out[0], outFrom, source[0], sf, GRAIN);
            if (rr > bestR) {
                bestR = rr;
                bestOff = off;
            }
        }
        System.out.println("[TAIL] 公式偏移相关=" + corr(out[0], outFrom, source[0], base, GRAIN)
                + " 最佳偏移=" + bestOff + " 帧(" + String.format(java.util.Locale.ROOT, "%.2f", bestOff * 1000.0 / RATE)
                + " ms) 相关=" + bestR);
        assertTrue("必须存在高相关的源偏移（>0.99），否则尾部无法直通；实际最佳 " + bestR, bestR > 0.99);
    }
}
