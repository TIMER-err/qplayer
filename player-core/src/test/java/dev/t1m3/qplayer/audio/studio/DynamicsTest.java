package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 动态处理的判据：<strong>对着定义量，不对着手感量</strong>（docs/audio-studio.md §5）。
 *
 * <ul>
 *   <li><b>压缩曲线</b>：静态正弦下，增益衰减必须等于
 *       {@code (输入 − 阈值) × (1 − 1/比率)}（拐点内按二次插值），误差 &lt; 0.2 dB；</li>
 *   <li><b>软拐点</b>：拐点内的 GR 必须比「硬拐点」小、而且是连续的（边界上不跳）；</li>
 *   <li><b>启动 / 释放</b>：一阶时间常数——阶跃之后到达最终值的 63% 用掉的时间必须与旋钮一致
 *       （±40%：一阶系统的「到达 63%」在逐块测量里本来就有量化误差）；</li>
 *   <li><b>限制器</b>：输出峰值不许超过天花板 +0.05 dB；前视如实上报，而且是<strong>量出来的</strong>
 *       （冲激出现在第 {@code latencyFrames()} 个采样上）；</li>
 *   <li><b>门</b>：门限以下的信号按「范围」衰减、门限以上原样通过；</li>
 *   <li><b>关掉 / 旁路</b>：逐位直通，并且不算「在工作」。</li>
 * </ul>
 */
public class DynamicsTest {

    private static final int RATE = 48_000;

    /** 一段稳态正弦（峰值 = {@code peak}）。 */
    private static AudioBuf sine(double hz, double peak, int frames) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        buf.setFrames(frames);
        java.nio.FloatBuffer l = buf.channelWriter(0);
        java.nio.FloatBuffer r = buf.channelWriter(1);
        for (int i = 0; i < frames; i++) {
            float v = (float) (peak * Math.sin(2 * Math.PI * hz * i / RATE));
            l.put(i, v);
            r.put(i, v);
        }
        return buf;
    }

    /** 跑稳态输入，返回输出峰值（dBFS）。 */
    private static double outputPeakDb(DynamicsParams params, double inputPeak, int frames) {
        DynamicsModule module = new DynamicsModule(RATE, 2);
        module.setParams(params);
        AudioBuf buf = sine(997.0, inputPeak, frames);
        // 跑两块，取第二块（第一块是包络的暂态）
        module.process(buf);
        AudioBuf second = sine(997.0, inputPeak, frames);
        module.process(second);
        // 量后半段的峰值（避开块边界的相位差）
        int base = second.channelBase(0);
        float peak = 0f;
        for (int i = frames / 2; i < frames; i++) {
            peak = Math.max(peak, Math.abs(second.backingArray()[base + i]));
        }
        return 20 * Math.log10(Math.max(peak, 1e-12));
    }

    /**
     * <b>压缩曲线与定义一致。</b>
     *
     * <p>阈值 −18 dB、比率 4:1、硬拐点（0 dB）时：输入 −6 dBFS 的 GR 必须是
     * {@code (−6 − (−18)) × (1 − 1/4) = 9 dB}。
     */
    @Test
    public void theCompressionCurveMatchesTheDefinition() {
        // 释放取 2000 ms（上限）：判据量的是**静态**压缩曲线，包络在波峰之间必须几乎不动
        DynamicsParams hard = DynamicsParams.builder()
                .compressor(true, -18.0, 4.0, 0.0, 1.0, 2000.0, 0.0)
                .build();
        double[] inputs = {-6.0, -12.0, -24.0, -30.0};
        for (double inDb : inputs) {
            double peak = Math.pow(10.0, inDb / 20.0);
            double outDb = outputPeakDb(hard, peak, 8192);
            double over = Math.max(0.0, inDb - (-18.0));
            double wantGr = over * (1.0 - 1.0 / 4.0);
            double gotGr = inDb - outDb;
            System.out.printf("### 压缩：输入 %+.1f dBFS → 输出 %+.2f dBFS（衰减 %.2f dB，"
                            + "定义 %.2f dB）%n",
                    Double.valueOf(inDb), Double.valueOf(outDb), Double.valueOf(gotGr),
                    Double.valueOf(wantGr));
            assertEquals("GR 必须与定义一致（输入 " + inDb + " dBFS）",
                    wantGr, gotGr, 0.2);
        }
    }

    /**
     * <b>软拐点：起压更早（拐点下半段），到拐点上半段与硬拐点合流。</b>
     *
     * <p>注意不能拿「阈值处」去比：软拐点是以阈值为中心展开的
     * （{@code [threshold − knee/2, threshold + knee/2]}），所以阈值处本来就压了一点点
     * （定义值 {@code (1 − 1/ratio)·knee/8}），而硬拐点在阈值处是 0——第一版把这条
     * 期望写反了，判据当场红。真正体现「软」的是<strong>拐点下半段</strong>：
     * 硬拐点在那里一点不压，软拐点已经开始压了。
     */
    @Test
    public void theSoftKneeStartsCompressingBelowTheThreshold() {
        DynamicsParams hard = DynamicsParams.builder()
                .compressor(true, -18.0, 4.0, 0.0, 1.0, 2000.0, 0.0).build();
        DynamicsParams soft = DynamicsParams.builder()
                .compressor(true, -18.0, 4.0, 12.0, 1.0, 2000.0, 0.0).build();
        // 拐点下半段：阈值 −3 dB（拐点 12 dB → 下半段是 [−24, −18]）
        double inDb = -21.0;
        double peak = Math.pow(10.0, inDb / 20.0);
        double hardGr = inDb - outputPeakDb(hard, peak, 16384);
        double softGr = inDb - outputPeakDb(soft, peak, 16384);
        System.out.printf("### 软拐点：阈值下 3 dB 处硬拐点压 %.2f dB，软拐点压 %.2f dB%n",
                Double.valueOf(hardGr), Double.valueOf(softGr));
        assertEquals("硬拐点在阈值以下不压", 0.0, hardGr, 0.2);
        assertTrue("软拐点必须已经开始压（实测 " + softGr + " dB）", softGr > 0.2);
        // 定义值：(1 − 1/4) · x²/(2·knee)，x = over + knee/2 = (−3) + 6 = 3 → 0.75·9/24 = 0.281
        // （我第一版把 x 算成了 9，期望写成 2.53——判据红的时候先怀疑尺子，这一条又是例子）
        assertEquals("软拐点的值必须与二次插值一致", 0.281, softGr, 0.1);
        // 拐点外两侧一致：远高于阈值时两者必须一样
        double loud = 1.0;
        assertEquals("拐点外必须一样（软拐点只改拐点内）",
                outputPeakDb(hard, loud, 16384), outputPeakDb(soft, loud, 16384), 0.2);
    }

    /**
     * <b>启动时间是真的（一阶时间常数）。</b>
     *
     * <p>喂一个从 −40 dBFS 跳到 0 dBFS 的阶跃，量增益包络到达最终衰减的 63% 用了多久。
     */
    @Test
    public void theAttackTimeIsTheRealOne() {
        double attackMs = 20.0;
        DynamicsParams params = DynamicsParams.builder()
                .compressor(true, -20.0, 8.0, 0.0, attackMs, 2000.0, 0.0).build();
        DynamicsModule module = new DynamicsModule(RATE, 2);
        module.setParams(params);
        // **直流阶跃**：检波器对直流给的是干净常量电平，量出来的才是增益包络本身的时间常数
        // （拿正弦量会有波峰/过零的干扰，第一版量出 53 ms，是测量方法的问题而不是模块的）
        AudioBuf quiet = dc(Math.pow(10.0, -40.0 / 20.0), 4096);
        module.process(quiet);
        int frames = 8192;
        AudioBuf loud = dc(1.0, frames);
        module.process(loud);
        int base = loud.channelBase(0);
        float[] backing = loud.backingArray();
        double finalGr = (0.0 - -20.0) * (1 - 1.0 / 8.0);        // 17.5 dB
        // **一阶时间常数的定义作用在「线性包络」上，不是作用在 dB 上**：
        // 包络在 τ 处到达 final + (1 − final)·e⁻¹，换成 dB 是
        // −20·log10(0.1334 + 0.8666·0.368) = 6.90 dB——而不是「17.5 的 63% = 11.03 dB」。
        // 第一版就是按 dB 的 63% 去卡的，量出 35 ms（真值 20 ms）而误判成模块错。
        double finalEnv = Math.pow(10.0, -finalGr / 20.0);
        double envAtTau = finalEnv + (1.0 - finalEnv) * Math.exp(-1.0);
        double target = -20.0 * Math.log10(envAtTau);
        int reached = -1;
        for (int i = 0; i < frames; i++) {
            double gr = -20 * Math.log10(Math.max(Math.abs(backing[base + i]), 1e-12));
            if (gr >= target) {
                reached = i;
                break;
            }
        }
        double measuredMs = reached < 0 ? -1 : reached * 1000.0 / RATE;
        System.out.printf("### 启动：旋钮 %.0f ms，实测在 %.1f ms 到达一阶常数的那个点"
                        + "（%.2f dB，终值 %.1f dB）%n",
                Double.valueOf(attackMs), Double.valueOf(measuredMs), Double.valueOf(target),
                Double.valueOf(finalGr));
        assertTrue("启动时间必须与旋钮一致（实测 " + measuredMs + " ms）",
                measuredMs > attackMs * 0.8 && measuredMs < attackMs * 1.3);
    }

    /** 一个直流信号（峰 = 值）——量时间常数时用它最干净。 */
    private static AudioBuf dc(double value, int frames) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        buf.setFrames(frames);
        for (int ch = 0; ch < 2; ch++) {
            java.nio.FloatBuffer w = buf.channelWriter(ch);
            for (int i = 0; i < frames; i++) {
                w.put(i, (float) value);
            }
        }
        return buf;
    }

    /**
     * <b>限制器：天花板是硬的，而且前视是量出来的。</b>
     *
     * <p>两条判据：① 把 0 dBFS 的输入交给天花板 −6 dB 的限制器，输出峰值不许超过 −6 dB + 0.05；
     * ② 喂一个冲激，输出里第一个非零采样必须正好在 {@code latencyFrames()} 上。
     */
    @Test
    public void theLimiterHoldsTheCeilingAndReportsItsLookahead() {
        DynamicsParams params = DynamicsParams.builder()
                .limiter(true, -6.0, 2.0, 80.0).build();
        DynamicsModule module = new DynamicsModule(RATE, 2);
        module.setParams(params);
        int expect = (int) Math.round(2.0 / 1000.0 * RATE);
        assertEquals("上报的前视 = 2 ms @48 kHz", expect, module.latencyFrames());

        // ① 天花板
        double outDb = outputPeakDb(params, 1.0, 16384);
        System.out.printf("### 限制器：输入 0 dBFS → 输出 %+.3f dBFS（天花板 −6 dB）%n",
                Double.valueOf(outDb));
        assertTrue("不许超过天花板：" + outDb + " dBFS", outDb <= -6.0 + 0.05);
        assertTrue("也不许压得过多（那是别的毛病）：" + outDb + " dBFS", outDb >= -6.0 - 1.0);

        // ② 前视：冲激出现在第 latencyFrames() 个采样上
        DynamicsModule m2 = new DynamicsModule(RATE, 2);
        m2.setParams(params);
        int frames = 4096;
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        buf.setFrames(frames);
        buf.channelWriter(0).put(0, 1.0f);
        buf.channelWriter(1).put(0, 1.0f);
        m2.process(buf);
        int base = buf.channelBase(0);
        int first = -1;
        for (int i = 0; i < frames; i++) {
            if (Math.abs(buf.backingArray()[base + i]) > 1e-6f) {
                first = i;
                break;
            }
        }
        System.out.printf("### 限制器前视：上报 %d 帧，冲激出现在第 %d 帧%n",
                Integer.valueOf(expect), Integer.valueOf(first));
        assertEquals("冲激必须正好出现在上报的前视处", expect, first);
    }

    /** 门：门限以下按「范围」衰减，门限以上原样。 */
    @Test
    public void theGateClosesBelowTheThresholdOnly() {
        DynamicsParams params = DynamicsParams.builder()
                .gate(true, -40.0, -30.0, 0.0).build();
        // 远低于门限：应当被压掉约 30 dB
        double quietIn = -70.0;
        double quietOut = outputPeakDb(params, Math.pow(10.0, quietIn / 20.0), 16384);
        double quietGr = quietIn - quietOut;
        System.out.printf("### 门：输入 %+.0f dBFS → 输出 %+.1f dBFS（压了 %.1f dB，范围 30 dB）%n",
                Double.valueOf(quietIn), Double.valueOf(quietOut), Double.valueOf(quietGr));
        assertTrue("门限以下要按范围压掉（实测 " + quietGr + " dB）",
                quietGr > 25.0 && quietGr <= 30.5);
        // 高于门限：几乎不动
        double loudIn = -10.0;
        double loudOut = outputPeakDb(params, Math.pow(10.0, loudIn / 20.0), 16384);
        assertEquals("门限以上不许动", 0.0, loudIn - loudOut, 0.3);
    }

    /** 三台全关：逐位直通，不算在工作，延迟报 0。 */
    @Test
    public void offIsBitExactPassthrough() {
        DynamicsModule module = new DynamicsModule(RATE, 2);
        module.setParams(DynamicsParams.off());
        assertFalse("全关不算在工作", module.active());
        assertEquals("全关延迟 0", 0, module.latencyFrames());
        AudioBuf buf = sine(997.0, 0.4, 1024);
        float[] before = new float[2048];
        for (int ch = 0; ch < 2; ch++) {
            int base = buf.channelBase(ch);
            for (int i = 0; i < 1024; i++) {
                before[ch * 1024 + i] = buf.backingArray()[base + i];
            }
        }
        module.process(buf);
        for (int ch = 0; ch < 2; ch++) {
            int base = buf.channelBase(ch);
            for (int i = 0; i < 1024; i++) {
                assertEquals("全关必须逐位直通（声道 " + ch + " 第 " + i + " 个）",
                        Float.floatToIntBits(before[ch * 1024 + i]),
                        Float.floatToIntBits(buf.backingArray()[base + i]));
            }
        }
        assertEquals("全关时衰减读数归零", 0f, module.gainReductionDb(), 1e-9);
    }

    /** 增益衰减读数：压得越多读数越大（表头靠它）。 */
    @Test
    public void theGainReductionReadingFollowsTheAmountOfCompression() {
        DynamicsParams params = DynamicsParams.builder()
                .compressor(true, -24.0, 4.0, 0.0, 1.0, 200.0, 0.0).build();
        DynamicsModule module = new DynamicsModule(RATE, 2);
        module.setParams(params);
        module.process(sine(997.0, 1.0, 4096));
        float loud = module.gainReductionDb();
        DynamicsModule quiet = new DynamicsModule(RATE, 2);
        quiet.setParams(params);
        quiet.process(sine(997.0, 0.01, 4096));
        float small = quiet.gainReductionDb();
        System.out.printf("### 衰减读数：满幅 %.2f dB，小信号 %.2f dB%n",
                Float.valueOf(loud), Float.valueOf(small));
        assertTrue("满幅要压得多（读数 " + loud + " dB）", loud > 12.0);
        assertTrue("小信号不许压（读数 " + small + " dB）", small < 1.0);
    }
}
