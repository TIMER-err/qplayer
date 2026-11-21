package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link Automix} 的窗口读取：**长度要盖满窗口，时间轴必须与原曲一致**。
 *
 * <p>这两条是同一个缺陷的两面（2026-10-02，用户报「过渡里刚听过的内容又听了一遍」）：
 * {@code resampleMono} 里那份手抄的 push/pull 循环**不看 {@code pushAll} 的返回值**，
 * 历史满时没喂进去的帧被静默丢掉——产物既短（盖不满窗口）又被时间压缩。
 *
 * <p>只对**需要重采样**的素材成立：44.1 kHz 源 + 48 kHz 输出是最常见的一种，
 * 而这条路径以前所有用例用的都是同采样率素材（走 {@code srcRate == outRate} 快路径），
 * 所以一直没被测到。
 */
public class AutomixTest {

    private static final int SRC_RATE = 44_100;
    private static final int OUT_RATE = 48_000;

    /**
     * 44.1 kHz → 48 kHz、20 秒的素材：输出必须**正好**是窗口长度，而且每个脉冲都待在自己那一秒。
     *
     * <p>素材用「每秒一个脉冲」的点击串：丢帧会改变脉冲间距与个数，时间压缩会让第 k 个脉冲
     * 提前跑到 k·outRate 之前——两种毛病都是这个缺陷的实际形状，而且都不需要听。
     *
     * <p>实测（改之前）：20 秒只写出 886923 帧（要 960000，缺 7.6%），脉冲位置逐步提前，
     * 到第 18 秒已经偏了约 1.4 秒。
     */
    @Test
    public void resampleKeepsTheTimelineAndCoversTheWholeWindow() {
        final int secs = 20;
        float[] src = clicks(SRC_RATE, secs);
        int want = OUT_RATE * secs;

        float[] out = Automix.resampleMono(src, SRC_RATE, OUT_RATE, want);

        assertEquals("输出必须盖满整个窗口（少了就是窗口尾部没有素材可用）", want, out.length);
        for (int k = 1; k <= secs - 2; k++) {
            int at = peakNear(out, k * OUT_RATE - OUT_RATE / 4, k * OUT_RATE + OUT_RATE / 4);
            assertTrue("第 " + k + " 秒的脉冲跑到了第 " + at + " 帧（应 ≈ " + k * OUT_RATE
                            + "，偏 " + (at - k * OUT_RATE) * 1000L / OUT_RATE + " ms）"
                            + "——素材被时间压缩了，窗口里会快放，交接时听着往回退",
                    Math.abs(at - (long) k * OUT_RATE) < OUT_RATE / 100L);
        }
    }

    /** 同采样率时原样返回（快路径不能因为这次修改而多过一遍核）。 */
    @Test
    public void resamplingAtTheSameRateIsAPlainCopy() {
        float[] src = clicks(OUT_RATE, 2);
        float[] out = Automix.resampleMono(src, OUT_RATE, OUT_RATE, OUT_RATE * 3);
        assertEquals("素材比窗口短时如实短着返回", src.length, out.length);
        for (int i = 0; i < src.length; i++) {
            if (src[i] != out[i]) {
                throw new AssertionError("同采样率必须逐位相同，第 " + i + " 帧不同");
            }
        }
    }

    /** 每秒一个脉冲（指数衰减），采样率 {@code rate}、长度 {@code secs} 秒。 */
    private static float[] clicks(int rate, int secs) {
        float[] s = new float[rate * secs];
        for (int k = 0; k < secs; k++) {
            int at = k * rate;
            int len = Math.min(200, s.length - at);
            for (int i = 0; i < len; i++) {
                s[at + i] = (float) Math.exp(-i / 25.0);
            }
        }
        return s;
    }

    private static int peakNear(float[] x, int from, int to) {
        int at = Math.max(0, from);
        float best = -1.0f;
        for (int i = Math.max(0, from); i < Math.min(x.length, to); i++) {
            float a = Math.abs(x[i]);
            if (a > best) {
                best = a;
                at = i;
            }
        }
        return at;
    }
}
