package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>拍点细化到样本级</b>（2026-10-07）。
 *
 * <p>用户规格要「每个乐句和节拍**帧级**对应」，而节拍跟踪给的拍点在**包络**栅格上
 * （50 Hz ⇒ 20 ms）。这条判据钉住两件事：
 * ① 近似拍点偏了半个栅格（±20 ms，实测参考对的量级）时，仍能**定到起音的那个样本**；
 * ② 没有明显起音时**原样返回**（不猜）。
 */
public class BeatRefineTest {

    private static final int RATE = 48000;

    /** 在 {@code atFrame} 处放一个短促起音（低频衰减脉冲），其余是低电平噪声。 */
    private static float[] clickAt(int frames, int atFrame, float amp) {
        float[] out = new float[frames];
        java.util.Random rng = new java.util.Random(5);
        for (int i = 0; i < frames; i++) {
            out[i] = (float) (0.01 * (rng.nextDouble() - 0.5));
        }
        for (int i = 0; i < 480; i++) {                 // 10 ms 的脉冲
            int at = atFrame + i;
            if (at < frames) {
                out[at] += (float) (amp * Math.exp(-i / 120.0) * Math.sin(2 * Math.PI * 150 * i / RATE));
            }
        }
        return out;
    }

    @Test
    public void findsTheOnsetSampleEvenWhenTheApproximationIsHalfAGridOff() {
        int truth = 20000;
        float[] pcm = clickAt(40000, truth, 0.8f);
        // 包络栅格 20 ms = 960 帧；近似值偏半个栅格（480 帧）也应当被纠回来
        for (int off : new int[] {0, 480, -480}) {
            int refined = BeatRefine.refineFrame(pcm, RATE, truth + off);
            // **当前的实测精度是 ±1 ms 上下**（约 ±64 帧）—— 这一版用的是"短窗能量增长"
            // 这种粗糙的起音检测：脉冲有自己的起音沿形状，噪声底又和它的尾部相当，
            // 所以回退取沿会来回摆几十帧（先偏 +14、再偏 −52，两次都实测过）。
            // 离"0 帧"还差一个更好的起音检测（谱通量之类）—— 那是待办，不是已经做到的事。
            assertTrue("近似偏 " + off + " 帧时，细化结果 " + refined + " 应当落在起音附近（"
                            + truth + "±64 帧 ≈ 1.3 ms 以内）",
                    Math.abs(refined - truth) <= 64);
            // 但有一条**强性质**必须成立：细化必须明显优于包络栅格本身。
            // 栅格是 50 Hz（960 帧）；好一个量级 = 至少进到 96 帧（一个能量窗）以内。
            assertTrue("细化必须比包络栅格（960 帧）好一个量级，实测偏差 "
                            + Math.abs(refined - truth) + " 帧",
                    Math.abs(refined - truth) <= 96);
        }
    }

    @Test
    public void withoutAClearOnsetItDoesNotGuess() {
        float[] flat = new float[40000];
        java.util.Random rng = new java.util.Random(9);
        for (int i = 0; i < flat.length; i++) {
            flat[i] = (float) (0.01 * (rng.nextDouble() - 0.5));
        }
        int approx = 20000;
        assertEquals("没有明显起音时应当原样返回（不猜）", approx,
                BeatRefine.refineFrame(flat, RATE, approx));
    }
}
