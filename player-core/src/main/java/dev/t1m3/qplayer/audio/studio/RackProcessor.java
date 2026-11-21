package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.dsp.Biquad;

/**
 * 一台机架的实时处理：<b>增益（前级 × 平衡 × 极性）→ 均衡 → 音高 → 染色 → 动态 →
 * 立体声 → 等响度补偿</b>。
 *
 * <p>音高那一台是链上**唯一带延迟**的模块（一个 CHUNK = 21.3 ms，见 {@link PitchModule}），
 * 所以 {@link #latencyFrames()} 直接报它。它必须在均衡**之后**、平衡**之前**：均衡先塑形、
 * 再整体移调（移调会挪动共振峰，先 EQ 才是用户按下的那个意思）；平衡是纯增益，
 * 放在任何位置都等价，放最后不影响任何滤波器的状态。
 *
 * <h2>增益为什么合成一次乘加</h2>
 *
 * <p>前级、极性、平衡三者都是「对本块乘一个常数」——常数增益与任何线性时不变滤波器
 * <strong>可交换</strong>，所以合并成一次逐声道的乘加在数学上与分开做完全等价，
 * 只是省了两次遍历。文档里写的顺序是给人看的（界面上的信号流），实现上按这里来。
 *
 * <h2>参数改动怎么做到不咔哒</h2>
 *
 * <ul>
 *   <li>增益：块内线性斜坡（从上一块的值走到新目标），一块约 20 ms，听不出来；</li>
 *   <li>滤波器系数：换系数时<strong>保留内部状态</strong>（{@link Biquad#updateFrom}）；</li>
 *   <li>等响度补偿的两级架式只在音量变化超过 0.1 dB 时才重算系数（音量一秒能来几十次）。</li>
 * </ul>
 */
public final class RackProcessor {

    private final int rate;
    private final int channels;
    private final EqProcessor eq;
    /**
     * 音高。**每台机架一个实例**：三台机架各自可能移调到不同的音高（自动混音里
     * 「出场曲降半音、进场曲不动」是很常见的用法），共用一台就等于三台互相打架。
     */
    private final PitchModule pitch;

    /** 染色（饱和 + 过采样）。 */
    private final SaturationModule saturation;

    /** 动态（门 / 压缩 / 限制）。 */
    private final DynamicsModule dynamics;

    /** 立体声工具。 */
    private final StereoModule stereo;

    /** 当前参数（整体替换，泵线程只读）。 */
    private volatile StudioRack rack = StudioRack.EMPTY;

    /** 逐声道目标增益（前级 × 平衡 × 极性），与上一块的值。 */
    private final float[] gainTarget;
    private final float[] gainCurrent;

    /** 等响度补偿的两级架式（低架 + 高架），系数随音量走。 */
    private final Biquad loudLow;
    private final Biquad loudHigh;
    private double loudLowDb;
    private double loudHighDb;

    private volatile float volume = 1.0f;

    public RackProcessor(int rate, int channels) {
        this.rate = Math.max(rate, 1);
        this.channels = Math.max(channels, 1);
        this.eq = new EqProcessor(this.rate, this.channels);
        this.pitch = new PitchModule(this.rate, this.channels);
        this.saturation = new SaturationModule(this.rate, this.channels);
        this.dynamics = new DynamicsModule(this.rate, this.channels);
        this.stereo = new StereoModule(this.rate, this.channels);
        this.gainTarget = new float[this.channels];
        this.gainCurrent = new float[this.channels];
        java.util.Arrays.fill(this.gainCurrent, 1.0f);
        this.loudLow = Biquad.lowShelf(this.rate, 120f, 0.7071f, 0f);
        this.loudHigh = Biquad.highShelf(this.rate, 6000f, 0.7071f, 0f);
    }

    /** 换参数（允许分配；参数每秒最多变几次）。 */
    public void setRack(StudioRack value) {
        StudioRack next = value == null ? StudioRack.EMPTY : value;
        this.rack = next;
        eq.setBands(next.bands);
        pitch.setParams(next.pitch);
        saturation.setParams(next.saturation);
        dynamics.setParams(next.dynamics);
        stereo.setParams(next.stereo);
        recomputeGains(next);
        updateLoudness(next, volume);
    }

    /** 当前音量（线性 0..1）：等响度补偿要跟着它走。 */
    public void setVolume(float value) {
        float v = Math.max(0f, Math.min(1f, value));
        if (Math.abs(v - volume) < 1e-4f) {
            return;
        }
        volume = v;
        updateLoudness(rack, v);
    }

    /** 采样率（换设备时重建）。 */
    public int rate() {
        return rate;
    }

    /** 这台机架现在会不会碰采样。 */
    public boolean active() {
        // **两个都要问**：`rack.active()` 回答「参数是不是在工作」，而音高模块在关掉之后的
        // 那一个 CHUNK 里还在把前视放出去（见 PitchModule 的 draining）——那期间它确实
        // 还在写采样，报「已经旁路」就是撒谎（位完美标记会提前亮起来）。
        return rack.active() || pitch.active() || saturation.active() || dynamics.active()
                || stereo.active();
    }

    /**
     * 这台机架引入的延迟（采样）：链上只有音高那一台带前视。
     *
     * <p>见 {@link StudioRack#latencyFrames()}：它是「如实上报」那条纪律的落点。
     */
    public int latencyFrames() {
        return rack.latencyFrames(rate);
    }

    /** 本块的最大增益衰减（dB，正数）——表头读它。 */
    public float gainReductionDb() {
        return dynamics.gainReductionDb();
    }

    /** 这台机架有几段在工作（日志与界面用）。 */
    public int activeBands() {
        StudioRack r = rack;
        return r.active() ? r.activeBands() : 0;
    }

    /** 清状态（跳转、换曲、设备重开）。 */
    public void reset() {
        eq.reset();
        pitch.reset();
        saturation.reset();
        dynamics.reset();
        stereo.reset();
        loudLow.reset();
        loudHigh.reset();
        java.util.Arrays.fill(gainCurrent, 1.0f);
    }

    /** 就地处理一块。空闲时一个采样都不碰。 */
    public void process(AudioBuf buf) {
        StudioRack r = rack;
        if (!r.active()) {
            // 直通：**注意不要把增益状态拉到 1** —— 关掉再打开时从当前值续着走，
            // 否则「关」的那一瞬间目标就变了，再打开会从 1 突跳回目标值（一次咔哒）。
            //
            // 音高那一台单独叫一次：它的 `active()` 还包含「正在把最后一批前视放出去」
            // 那一小段（关掉参数的瞬间 holdBuf 里还压着 21 ms），那一段必须走完，
            // 否则就是一个听得到的断口。参数一直关着时它立刻返回，一个采样都不碰。
            pitch.process(buf);
            saturation.process(buf);
            dynamics.process(buf);
            stereo.process(buf);
            return;
        }
        int frames = buf.frames();
        if (frames <= 0) {
            return;
        }
        float[] backing = buf.backingArray();
        int chs = Math.min(buf.channels(), channels);
        // ① 前级 × 平衡 × 极性：块内线性斜坡
        for (int ch = 0; ch < chs; ch++) {
            float from = gainCurrent[ch];
            float to = gainTarget[Math.min(ch, gainTarget.length - 1)];
            int base = buf.channelBase(ch);
            float step = (to - from) / (float) frames;
            float g = from;
            for (int i = 0; i < frames; i++) {
                g += step;
                backing[base + i] *= g;
            }
            gainCurrent[ch] = to;
        }
        // ② 均衡
        eq.process(buf);
        // ③ 音高（半音/音分/干湿；关掉时立刻返回，见 PitchModule）
        pitch.process(buf);
        // ④ 染色（饱和 + 过采样）：非线性的东西放在动态之前，让压缩器去收它产生的峰值
        saturation.process(buf);
        // ⑤ 动态（门 → 压缩 → 限制）
        dynamics.process(buf);
        // ⑥ 立体声（宽度 / 低音单声道 / 侧向延迟 / 互换 / 交叉馈送）
        stereo.process(buf);
        // ⑦ 等响度补偿（两级架式；没开时系数是 0 dB，等于直通）
        if (r.loudness && r.loudnessAmount > 1e-6) {
            for (int ch = 0; ch < chs; ch++) {
                int base = buf.channelBase(ch);
                for (int i = 0; i < frames; i++) {
                    float v = backing[base + i];
                    v = loudLow.process(v);
                    backing[base + i] = loudHigh.process(v);
                }
            }
        }
    }

    /** 逐声道目标增益：前级（dB）× 平衡 × 极性。 */
    private void recomputeGains(StudioRack r) {
        float preamp = (float) Math.pow(10.0, r.preampDb / 20.0);
        float polarity = r.polarityInvert ? -1f : 1f;
        for (int ch = 0; ch < channels; ch++) {
            float balance = 1f;
            if (ch == 0 && r.balance > 0) {
                balance = (float) (1.0 - r.balance);
            } else if (ch > 0 && r.balance < 0) {
                balance = (float) (1.0 + r.balance);
            }
            gainTarget[ch] = preamp * balance * polarity;
        }
    }

    /**
     * 等响度补偿：音量越小，人耳对低频与高频越迟钝，于是按等响曲线<strong>补回来</strong>。
     *
     * <p>这里用的是一条工程近似：把「衰减了多少 dB」当自变量，
     * 低频最多补 {@code 0.6×}、高频最多补 {@code 0.3×}（各自再乘强度），
     * 上限 12 / 6 dB。它不是 ISO 226 的逐点复现——那需要一张曲线表与一套插值，
     * 而这里要的只是「小音量下别听着又薄又闷」。想更准请改这一处，界面与实现都不用动。
     */
    private void updateLoudness(StudioRack r, float volume) {
        double wantLow = 0.0;
        double wantHigh = 0.0;
        if (r.loudness && r.loudnessAmount > 1e-6 && volume < 1.0f) {
            double cutDb = -20.0 * Math.log10(Math.max(volume, 1e-4));
            wantLow = Math.min(12.0, cutDb * 0.6) * r.loudnessAmount;
            wantHigh = Math.min(6.0, cutDb * 0.3) * r.loudnessAmount;
        }
        // 只在变化超过 0.1 dB 时重算系数（音量每秒可能变几十次）
        if (Math.abs(wantLow - loudLowDb) > 0.1 || Math.abs(wantHigh - loudHighDb) > 0.1) {
            loudLow.updateLowShelf(rate, 120f, 0.7071f, (float) wantLow);
            loudHigh.updateHighShelf(rate, 6000f, 0.7071f, (float) wantHigh);
            loudLowDb = wantLow;
            loudHighDb = wantHigh;
        }
    }
}
