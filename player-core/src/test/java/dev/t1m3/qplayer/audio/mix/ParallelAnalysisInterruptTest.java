package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>「任务没跑完」不许当成品交出去——判据（2026-10-04，审计发现）</b>。
 *
 * <h3>发现的形状</h3>
 *
 * <p>并行分析那几处的收尾写法是：
 *
 * <pre>
 *   for (Future&lt;?&gt; task : tasks) {
 *       try {
 *           task.get();
 *       } catch (InterruptedException e) {
 *           Thread.currentThread().interrupt();     // ← 恢复标志
 *       } catch (ExecutionException e) {
 *           throw new IllegalStateException(e.getCause());   // ← 任务失败会往上抛
 *       }
 *   }
 * </pre>
 *
 * <p><b>两条路的待遇不一样</b>：任务自己抛异常会往上报，而"**调用线程被中断**"只是把标志
 * 恢复一下就继续往下走。于是那些输出数组里**没跑到的分块全是 0**，而调用方拿到的是一个
 * "看起来算完了"的结果：
 *
 * <ul>
 *   <li>{@link Harmonic#match} 拿到半张 chroma → 相似度、最优移调全是从半张图里算出来的，
 *       而且**一个字都不报**。后果很具体：一次过渡会按一个错的和声匹配去做移调；</li>
 *   <li>{@link SpectralOnsets#envelope} 同理 → 起音包络半张为 0 → 拍点/段落点跟着歪。</li>
 * </ul>
 *
 * <p>可达性不是理论上的：准备线程被打断在这条链上**是会发生的**——用户在准备期间切歌、
 * 退出、或者连续触发下一次准备（{@code AiMix} 与 {@code VocalAwarePitchTransition} 都为
 * 这件事各写过一处如实的回退，说明这条路上真被打断过）。
 *
 * <h3>为什么这条判据是这么写的</h3>
 *
 * <p>做法：**先把当前线程置为中断状态**，再调分析。此时 {@code Future.get()} 会立刻抛
 * {@code InterruptedException}，于是"每个分块都没等到"这件事被精确地复现出来——
 * 修复前它静默返回半成品（判据红），修复后它如实失败（判据绿）。
 *
 * <p>要求的行为与这个包一贯的纪律一致：**算不出来就别猜**，宁可让调用方走它已经写好的
 * 回退（{@code AiMix} / {@code AutoCueTransition} 都有"退回固定点位/不变调"的路径，
 * 并且会把原因写进 note），也不能交一份假的。
 */
public class ParallelAnalysisInterruptTest {

    /** 够长到会走并行分块（帧数要超过 {@code CHROMA_MIN_FRAMES_TO_SPLIT}）。 */
    private static final int SAMPLES = 48_000;

    /** 一段有音高的素材（440 Hz + 五度），否则 chroma 会是空的、判据测不到东西。 */
    private static float[] tone(int n, double f0) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / 48_000.0;
            out[i] = (float) (0.4 * Math.sin(2.0 * Math.PI * f0 * t)
                    + 0.3 * Math.sin(2.0 * Math.PI * f0 * 1.5 * t));
        }
        return out;
    }

    @Test
    public void anInterruptedHarmonicAnalysisMustFailLoudly() {
        float[] a = tone(SAMPLES, 220.0);
        float[] b = tone(SAMPLES, 330.0);

        boolean interrupted = Thread.currentThread().isInterrupted();
        assertTrue("这条判据要求测试线程一开始没被中断", !interrupted);
        Thread.currentThread().interrupt();
        try {
            Harmonic.Match match = Harmonic.match(a, 48_000, b, 48_000);
            // 修复前会走到这里。注意它返回 null 是**侥幸**：这次"每个分块都没等到"，
            // 于是 chroma 整张是 0、清晰度不过阈 → null。中断只要落在中途，
            // 剩下几张分块就是"有值但错"的，那时返回的就是一个像模像样的 Match。
            fail("线程被中断时，并行分析**必须**如实报错（不能靠运气：全没跑到是 null、"
                    + "跑到一半就是一个错的结论）；现在返回的是 " + match);
        } catch (RuntimeException expected) {
            // 期望：把"被中断"这件事如实报出来（调用方据此走它自己的回退）
            assertNotNull("异常必须说清是被中断", expected.getMessage());
            assertTrue("异常消息应当提到中断：" + expected.getMessage(),
                    expected.getMessage().contains("中断"));
        } finally {
            Thread.interrupted();          // 清掉标志，别污染后面的测试
        }
    }

    @Test
    public void anInterruptedOnsetAnalysisMustFailLoudly() {
        float[] pcm = tone(SAMPLES, 440.0);
        float[] stereo = new float[SAMPLES * 2];
        for (int i = 0; i < SAMPLES; i++) {
            stereo[2 * i] = pcm[i];
            stereo[2 * i + 1] = pcm[i];
        }

        assertTrue("这条判据要求测试线程一开始没被中断",
                !Thread.currentThread().isInterrupted());
        Thread.currentThread().interrupt();
        try {
            SpectralOnsets.envelope(stereo, 48_000);
            fail("线程被中断时，起音包络也必须如实失败（半张包络会让拍点/段落点悄悄歪掉）");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getMessage());
            assertTrue("异常消息应当提到中断：" + expected.getMessage(),
                    expected.getMessage().contains("中断"));
        } finally {
            Thread.interrupted();
        }
    }
}
