package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 去混响的判据（docs/audio-studio.md §8 那张表的可执行版本）。
 *
 * <h2>素材怎么造（这是这一条判据的全部难点）</h2>
 *
 * <p>拿真实录音没法判定「尾巴该掉多少」——你不知道干声本来是什么样。所以这里自己造：
 *
 * <ol>
 *   <li><strong>干声</strong>：一段带包络的音（0.4 s 有声 + 0.6 s 静音，重复几次），
 *       模拟「唱一句、停一下」；</li>
 *   <li><strong>冲激响应</strong>：指数衰减的噪声（RT60 ≈ 0.5 s）——它就是「混响」的解析定义；</li>
 *   <li><strong>湿声</strong> = 干声与冲激响应的卷积。于是「尾巴」是<strong>算得出来的</strong>：
 *       有声段结束后的那 0.6 s 里，干声一个采样都没有，剩下的全是混响。</li>
 * </ol>
 *
 * <p>判据三条，与文档里写的一致：
 *
 * <ul>
 *   <li><strong>尾巴 ≥ 8 dB 下降</strong>：静音段（干声为零的那一段）的能量必须掉下去；</li>
 *   <li><strong>干声变化 &lt; 1 dB</strong>：有声段（直达声为主）的能量几乎不许变——
 *       这一条是最容易被「减太狠」破坏的；</li>
 *   <li><strong>干湿 0 / 关掉逐位直通</strong>，以及<strong>延迟 = 一个窗且如实上报</strong>。</li>
 * </ul>
 */
public class DereverbTest {

    private static final int RATE = 48_000;

    /** 冲激响应的长度上限（秒）——见 {@link #impulseResponse}。 */
    private static final double IR_SECS = 0.2;

    /** 干声：每 1 秒里 0.4 s 有声（带淡入淡出）、0.6 s 静音；重复 {@code seconds} 次。 */
    private static float[] drySignal(int seconds) {
        int total = seconds * RATE;
        float[] out = new float[total];
        for (int i = 0; i < total; i++) {
            int inCycle = i % RATE;
            if (inCycle >= (int) (0.4 * RATE)) {
                continue;
            }
            double env = Math.min(1.0, Math.min(inCycle / (0.02 * RATE),
                    (0.4 * RATE - inCycle) / (0.02 * RATE)));
            // 「人声」用两个谐波叠出来：基频 + 一点二次谐波，比纯正弦更像嗓子
            out[i] = (float) (0.35 * env * (Math.sin(2 * Math.PI * 196.0 * i / RATE)
                    + 0.3 * Math.sin(2 * Math.PI * 392.0 * i / RATE)));
        }
        return out;
    }

    /**
     * 冲激响应：指数衰减的白噪声（RT60 ≈ {@code rt60} 秒）。
     *
     * <p>截断到 {@value #IR_SECS} 秒：卷积是 O(n·ir)，测试里 3 秒素材 × 0.2 秒 IR 就已经
     * 是 7 亿次乘加（约 1 秒）。尾音判据看的是「有声段结束后 0.6 秒」，IR 比它短就没问题。
     */
    private static float[] impulseResponse(double rt60, long seed) {
        int n = Math.min((int) (rt60 * RATE * 1.5), (int) (IR_SECS * RATE));
        java.util.Random rng = new java.util.Random(seed);
        float[] ir = new float[n];
        double decay = Math.log(1000.0) / (rt60 * RATE);     // −60 dB 的时间常数
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double v = (rng.nextDouble() * 2 - 1) * Math.exp(-decay * i);
            ir[i] = (float) v;
            sum += v * v;
        }
        // 归一化到单位能量：湿声的整体响度与干声可比，判据才有意义
        double norm = 1.0 / Math.sqrt(Math.max(sum, 1e-12));
        for (int i = 0; i < n; i++) {
            ir[i] *= norm;
        }
        // 直达声：给 IR 一个单位冲激头，湿声里才有「原来的干声」
        ir[0] += 1.0f;
        return ir;
    }

    /** 卷积（直接实现，离线测试够用）。 */
    private static float[] convolve(float[] dry, float[] ir) {
        float[] out = new float[dry.length];
        for (int i = 0; i < dry.length; i++) {
            double sum = 0;
            int last = Math.min(i, ir.length - 1);
            for (int k = 0; k <= last; k++) {
                sum += (double) dry[i - k] * ir[k];
            }
            out[i] = (float) sum;
        }
        return out;
    }

    /** 跑一遍模块（块长 = 1024，落在 HOP 栅格上）。 */
    private static float[] process(DereverbParams params, float[] in) {
        DereverbModule module = new DereverbModule(RATE, 1);
        module.setParams(params);
        float[] out = new float[in.length];
        int block = 1024;
        AudioBuf buf = new AudioBuf(ChannelLayout.MONO, block);
        for (int at = 0; at + block <= in.length; at += block) {
            buf.setFrames(block);
            java.nio.FloatBuffer w = buf.channelWriter(0);
            for (int i = 0; i < block; i++) {
                w.put(i, in[at + i]);
            }
            module.process(buf);
            System.arraycopy(buf.backingArray(), buf.channelBase(0), out, at, block);
        }
        return out;
    }

    /** 某一段的 RMS（dB）。 */
    private static double rmsDb(float[] v, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += (double) v[i] * v[i];
        }
        return 10.0 * Math.log10(Math.max(sum / Math.max(1, to - from), 1e-20));
    }

    // ==================================================================

    /**
     * <b>尾巴掉 ≥ 8 dB、干声变化 &lt; 1 dB。</b>
     *
     * <p>量的是第 2 个周期之后（第 1 个周期留给「开局一窗直通」与估计器的暂态）：
     * 有声段取 [0.02, 0.38) s，静音段取 [0.45, 0.95) s（末尾 50 ms 避开下一个起音）。
     */
    @Test
    public void theTailDropsWhileTheDryPartStays() {
        int seconds = 4;
        float[] dry = drySignal(seconds);
        float[] wet = convolve(dry, impulseResponse(0.5, 11));
        DereverbParams params = DereverbParams.of(0.85, -18.0, 1.0);
        float[] out = process(params, wet);

        double dryIn = 0;
        double dryOut = 0;
        double tailIn = 0;
        double tailOut = 0;
        int dryN = 0;
        int tailN = 0;
        // **按上报的延迟对齐再比**：模块的输出比输入晚一窗（1024 帧），不补偿的话
        // 「尾巴段」的窗口里会混进音尾本身（那一段比尾巴响 20 dB 以上），
        // 量出来的「下降」其实是那个边沿——第一版就是这么错的（无论怎么调参数都卡在 7.29 dB）。
        int shift = DereverbModule.WINDOW;
        for (int cycle = 2; cycle < seconds; cycle++) {
            int base = cycle * RATE;
            int from = base + (int) (0.02 * RATE);
            int to = base + (int) (0.38 * RATE);
            dryIn += Math.pow(10, rmsDb(wet, from, to) / 10);
            dryOut += Math.pow(10, rmsDb(out, from + shift, to + shift) / 10);
            dryN++;
            int tf = base + (int) (0.41 * RATE);
            int tt = base + (int) (0.60 * RATE);
            tailIn += Math.pow(10, rmsDb(wet, tf, tt) / 10);
            tailOut += Math.pow(10, rmsDb(out, tf + shift, tt + shift) / 10);
            tailN++;
        }
        double dryDelta = 10 * Math.log10(dryOut / dryIn);
        double tailDrop = 10 * Math.log10(tailIn / tailOut);
        System.out.printf("### 去混响：干声段变化 %+.2f dB，尾巴下降 %.2f dB（尾巴 %d 段平均）%n",
                Double.valueOf(dryDelta), Double.valueOf(tailDrop), Integer.valueOf(tailN));

        assertTrue("尾巴必须明显下降（实测 " + tailDrop + " dB）", tailDrop >= 8.0);
        assertTrue("干声不许被改掉超过 1 dB（实测 " + dryDelta + " dB）", Math.abs(dryDelta) < 1.0);
        assertTrue("上面两条必须同时成立：掉 " + tailDrop + " dB / 干声变 " + dryDelta + " dB",
                tailDrop >= 8.0 && Math.abs(dryDelta) < 1.0);
    }

    /**
     * <b>强度真的是「去多少」</b>：强度 0.3 时尾巴掉得明显比 0.9 少，
     * 而干声在两种情况下都基本不动。
     */
    @Test
    public void amountControlsHowMuchTailIsRemoved() {
        float[] dry = drySignal(3);
        float[] wet = convolve(dry, impulseResponse(0.5, 12));
        double low = tailDrop(DereverbParams.of(0.2, -18.0, 1.0), wet);
        double high = tailDrop(DereverbParams.of(0.9, -18.0, 1.0), wet);
        System.out.printf("### 强度 0.2 → 尾巴掉 %.2f dB，强度 0.9 → 掉 %.2f dB%n",
                Double.valueOf(low), Double.valueOf(high));
        assertTrue("强度越大掉得越多（" + low + " vs " + high + "）", high > low + 2.0);
    }

    private static double tailDrop(DereverbParams params, float[] wet) {
        float[] out = process(params, wet);
        int shift = DereverbModule.WINDOW;              // 与上面同一条：按延迟对齐
        double in = 0;
        double o = 0;
        for (int cycle = 2; cycle < 3; cycle++) {
            int base = cycle * RATE;
            in += Math.pow(10, rmsDb(wet, base + (int) (0.41 * RATE),
                    base + (int) (0.60 * RATE)) / 10);
            o += Math.pow(10, rmsDb(out, base + (int) (0.41 * RATE) + shift,
                    base + (int) (0.60 * RATE) + shift) / 10);
        }
        return 10 * Math.log10(in / o);
    }

    /**
     * <b>关掉 / 干湿 0 时逐位直通，而且不算「在工作」。</b>
     */
    @Test
    public void offIsBitExactPassthrough() {
        DereverbParams[] offs = {
                DereverbParams.off(),
                DereverbParams.of(0.0, -18.0, 1.0),
                DereverbParams.of(0.6, -18.0, 0.0),
        };
        float[] in = drySignal(1);
        for (DereverbParams p : offs) {
            DereverbModule module = new DereverbModule(RATE, 1);
            module.setParams(p);
            assertFalse("这些参数都不该算在工作：" + p, module.active());
            assertEquals("关着时延迟如实报 0", 0, module.latencyFrames());
            AudioBuf buf = new AudioBuf(ChannelLayout.MONO, 1024);
            buf.setFrames(1024);
            java.nio.FloatBuffer w = buf.channelWriter(0);
            for (int i = 0; i < 1024; i++) {
                w.put(i, in[i]);
            }
            module.process(buf);
            for (int i = 0; i < 1024; i++) {
                assertEquals("关掉时必须逐位直通（第 " + i + " 个）",
                        Float.floatToIntBits(in[i]),
                        Float.floatToIntBits(buf.backingArray()[buf.channelBase(0) + i]));
            }
        }
    }

    /**
     * <b>延迟 = 一个窗，而且是量出来的。</b>
     *
     * <p>喂一个冲激（第 0 个采样是 1，其余是 0），找出输出里第一个非零采样：
     * 它必须落在 {@link DereverbModule#WINDOW} 上——与 {@code latencyFrames()} 报的数一致。
     * 这一条同时钉住「开局那一窗是处理过的静音，而不是把输入提前放出去」。
     */
    @Test
    public void theReportedLatencyIsTheMeasuredOne() {
        float[] in = new float[8192];
        in[0] = 1.0f;
        DereverbModule module = new DereverbModule(RATE, 1);
        module.setParams(DereverbParams.of(0.5, -18.0, 1.0));
        assertEquals("上报延迟 = 一个窗", DereverbModule.WINDOW, module.latencyFrames());
        float[] out = process(module, in);
        int first = -1;
        for (int i = 0; i < out.length; i++) {
            if (Math.abs(out[i]) > 1e-6f) {
                first = i;
                break;
            }
        }
        System.out.printf("### 去混响延迟：上报 %d 帧，冲激响应第一个非零在 %d 帧%n",
                Integer.valueOf(DereverbModule.WINDOW), Integer.valueOf(first));
        assertEquals("冲激必须恰好出现在上报的延迟处", DereverbModule.WINDOW, first);
    }

    private static float[] process(DereverbModule module, float[] in) {
        float[] out = new float[in.length];
        int block = 1024;
        AudioBuf buf = new AudioBuf(ChannelLayout.MONO, block);
        for (int at = 0; at + block <= in.length; at += block) {
            buf.setFrames(block);
            java.nio.FloatBuffer w = buf.channelWriter(0);
            for (int i = 0; i < block; i++) {
                w.put(i, in[at + i]);
            }
            module.process(buf);
            System.arraycopy(buf.backingArray(), buf.channelBase(0), out, at, block);
        }
        return out;
    }

    /**
     * <b>关掉的时候不许让时间轴跳</b>：收尾那一窗要按干声补出去。
     *
     * <p>判据：喂一段连续的正弦，在开了之后又关掉，输出必须<strong>处处连着</strong>——
     * 相邻采样之差的峰值不能被一次「跳 21 ms」拉爆（那一下的正弦值会差很远）。
     */
    @Test
    public void turningItOffDoesNotJumpTheTimeline() {
        int total = RATE * 2;
        float[] in = new float[total];
        for (int i = 0; i < total; i++) {
            in[i] = (float) (0.4 * Math.sin(2 * Math.PI * 200.0 * i / RATE));
        }
        DereverbModule module = new DereverbModule(RATE, 1);
        DereverbParams on = DereverbParams.of(0.7, -18.0, 1.0);
        module.setParams(on);
        float[] out = new float[total];
        int block = 1024;
        AudioBuf buf = new AudioBuf(ChannelLayout.MONO, block);
        for (int at = 0; at + block <= total; at += block) {
            if (at == RATE / 2) {
                module.setParams(DereverbParams.off());     // 中途关掉
                assertFalse("关掉之后 params 说关着", module.params().active());
            }
            buf.setFrames(block);
            java.nio.FloatBuffer w = buf.channelWriter(0);
            for (int i = 0; i < block; i++) {
                w.put(i, in[at + i]);
            }
            module.process(buf);
            System.arraycopy(buf.backingArray(), buf.channelBase(0), out, at, block);
        }
        // 200 Hz 正弦在 48 kHz 下相邻采样最大差 = 2π·200/48000·0.4 ≈ 0.0105
        //
        // **末尾一窗不算**：输出比输入晚一窗，流的最后那一窗（1024 帧）注定交付不出来
        // （没有更多输入可看了），于是那一段是调用方缓冲里的原始输入——边界上必然有一个台阶。
        // 这是带前视的模块的固有性质（音高那一台也一样），如实写在这里：
        // 曲末/换曲时由引擎 reset()，不靠「末尾自动对齐」。
        int limit = total - DereverbModule.WINDOW - DereverbModule.HOP;
        float maxStep = 0;
        int atMax = 0;
        for (int i = 1; i < limit; i++) {
            float step = Math.abs(out[i] - out[i - 1]);
            if (step > maxStep) {
                maxStep = step;
                atMax = i;
            }
        }
        float tailStep = 0;
        for (int i = limit; i < total; i++) {
            tailStep = Math.max(tailStep, Math.abs(out[i] - out[i - 1]));
        }
        System.out.printf("### 中途关掉：正文相邻采样最大跳变 %.5f @ %d（正弦本身约 0.0105），"
                        + "末尾未交付那一窗 %.5f（固有性质，见注释）%n",
                Float.valueOf(maxStep), Integer.valueOf(atMax), Float.valueOf(tailStep));
        assertTrue("关掉时不许把时间轴跳掉一窗（最大跳变 " + maxStep + "）", maxStep < 0.02f);
    }
}
