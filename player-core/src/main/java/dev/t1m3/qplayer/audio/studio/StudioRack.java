package dev.t1m3.qplayer.audio.studio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条处理链（一个机架）的参数（不可变）。
 *
 * <p>P1 里这一条链是：<b>前级增益 → 极性 → 均衡 → 平衡</b>，外加一个跟着音量走的
 * <b>等响度补偿</b>。顺序不是随手定的：极性必须在均衡之前（否则全通/架式段的状态会先被
 * 反相喂一遍，听起来没差别、但 A/B 对比时相位图会对不上），平衡放在最后
 * （它是纯增益，放最后不会影响任何滤波器的状态）。
 *
 * <p>不可变 + 整体替换：泵线程只读，命令线程建新的（见 {@link EqBand} 的说明）。
 */
public final class StudioRack {

    /** 一条链最多几段（再多界面也摆不下，而且每段都要算力）。 */
    public static final int MAX_BANDS = 16;

    /** 空机架（什么都不做，直通）。 */
    public static final StudioRack EMPTY = new StudioRack(false, 0.0, 0.0, false,
            false, 0.0, Collections.<EqBand>emptyList(), PitchParams.off(),
            DynamicsParams.off(), StereoParams.neutral(), SaturationParams.off());

    /** 整条链的开关（关掉 = 直通，一个采样都不碰）。 */
    public final boolean enabled;

    /** 前级增益（dB）。 */
    public final double preampDb;

    /** 平衡：−1 = 全左，0 = 居中，+1 = 全右。 */
    public final double balance;

    /** 左右反相（修某些录音的绝对极性；单听不容易发现，做减法对比时有用）。 */
    public final boolean polarityInvert;

    /** 等响度补偿：音量小的时候按等响曲线补一点低频与高频。 */
    public final boolean loudness;

    /** 补偿强度（0..1，1 = 最强）。 */
    public final double loudnessAmount;

    /** 均衡段（不可变列表）。 */
    public final List<EqBand> bands;

    /**
     * 音高（半音/音分/干湿）。
     *
     * <p>它在链上的位置是<strong>均衡之后、平衡之前</strong>（见 {@code RackProcessor}）：
     * 均衡先塑形，再整体移调；平衡是纯增益，放最后不影响任何滤波器的状态。
     *
     * <p>它是这条链上<strong>唯一带延迟</strong>的模块（一个 CHUNK = 21.3 ms，见
     * {@code PitchModule}），所以 {@link #latencyFrames()} 只由它决定。
     */
    public final PitchParams pitch;

    /** 动态（门 / 压缩 / 限制）。 */
    public final DynamicsParams dynamics;

    /** 立体声工具（宽度 / 低音单声道 / 侧向延迟 / 互换 / 交叉馈送）。 */
    public final StereoParams stereo;

    /** 染色（饱和 + 过采样）。 */
    public final SaturationParams saturation;

    private StudioRack(boolean enabled, double preampDb, double balance, boolean polarityInvert,
                       boolean loudness, double loudnessAmount, List<EqBand> bands,
                       PitchParams pitch, DynamicsParams dynamics, StereoParams stereo,
                       SaturationParams saturation) {
        this.enabled = enabled;
        this.preampDb = clamp(preampDb, -24.0, 24.0);
        this.balance = clamp(balance, -1.0, 1.0);
        this.polarityInvert = polarityInvert;
        this.loudness = loudness;
        this.loudnessAmount = clamp(loudnessAmount, 0.0, 1.0);
        List<EqBand> copy = new ArrayList<EqBand>();
        for (EqBand b : bands == null ? Collections.<EqBand>emptyList() : bands) {
            if (b != null && copy.size() < MAX_BANDS) {
                copy.add(b);
            }
        }
        this.bands = Collections.unmodifiableList(copy);
        this.pitch = pitch == null ? PitchParams.off() : pitch;
        this.dynamics = dynamics == null ? DynamicsParams.off() : dynamics;
        this.stereo = stereo == null ? StereoParams.neutral() : stereo;
        this.saturation = saturation == null ? SaturationParams.off() : saturation;
    }

    public static StudioRack of(boolean enabled, double preampDb, double balance,
                                boolean polarityInvert, boolean loudness, double loudnessAmount,
                                List<EqBand> bands) {
        return new StudioRack(enabled, preampDb, balance, polarityInvert, loudness,
                loudnessAmount, bands, PitchParams.off(), DynamicsParams.off(), StereoParams.neutral(), SaturationParams.off());
    }

    /** 同 {@link #of}，再带一套音高参数。 */
    public static StudioRack of(boolean enabled, double preampDb, double balance,
                                boolean polarityInvert, boolean loudness, double loudnessAmount,
                                List<EqBand> bands, PitchParams pitchValue) {
        return new StudioRack(enabled, preampDb, balance, polarityInvert, loudness,
                loudnessAmount, bands, pitchValue, DynamicsParams.off(), StereoParams.neutral(),
                SaturationParams.off());
    }

    /**
     * 全参版工厂：一条链的所有参数（EQ 段 / 音高 / 动态 / 立体声 / 染色）。
     *
     * <p>JSON 读写与「自定义 JSON」那条路走它——那两处本来就要把每一项都写出来，
     * 少一个参数的工厂会在编译期拦住（这正是它存在的意义）。
     */
    public static StudioRack full(boolean enabled, double preampDb, double balance,
                                  boolean polarityInvert, boolean loudness, double loudnessAmount,
                                  List<EqBand> bands, PitchParams pitchValue,
                                  DynamicsParams dynamicsValue, StereoParams stereoValue,
                                  SaturationParams saturationValue) {
        return new StudioRack(enabled, preampDb, balance, polarityInvert, loudness,
                loudnessAmount, bands, pitchValue, dynamicsValue, stereoValue, saturationValue);
    }

    /** 常用：开着的链 + 几段 EQ。 */
    public static StudioRack eq(List<EqBand> bands) {
        return new StudioRack(true, 0.0, 0.0, false, false, 0.0, bands, PitchParams.off(), DynamicsParams.off(), StereoParams.neutral(), SaturationParams.off());
    }

    public StudioRack withEnabled(boolean value) {
        return new StudioRack(value, preampDb, balance, polarityInvert, loudness,
                loudnessAmount, bands, pitch, dynamics, stereo, saturation);
    }

    public StudioRack withPreamp(double db) {
        return new StudioRack(enabled, db, balance, polarityInvert, loudness, loudnessAmount,
                bands, pitch, dynamics, stereo, saturation);
    }

    public StudioRack withBalance(double value) {
        return new StudioRack(enabled, preampDb, value, polarityInvert, loudness,
                loudnessAmount, bands, pitch, dynamics, stereo, saturation);
    }

    public StudioRack withPolarityInvert(boolean value) {
        return new StudioRack(enabled, preampDb, balance, value, loudness, loudnessAmount,
                bands, pitch, dynamics, stereo, saturation);
    }

    public StudioRack withLoudness(boolean on, double amount) {
        return new StudioRack(enabled, preampDb, balance, polarityInvert, on, amount, bands,
                pitch, dynamics, stereo, saturation);
    }

    /** 换掉整份段表。 */
    public StudioRack withBands(List<EqBand> value) {
        return new StudioRack(enabled, preampDb, balance, polarityInvert, loudness,
                loudnessAmount, value, pitch, dynamics, stereo, saturation);
    }

    /** 换动态参数。 */
    public StudioRack withDynamics(DynamicsParams value) {
        return new StudioRack(enabled, preampDb, balance, polarityInvert, loudness,
                loudnessAmount, bands, pitch, value, stereo, saturation);
    }

    /** 换立体声参数。 */
    public StudioRack withStereo(StereoParams value) {
        return new StudioRack(enabled, preampDb, balance, polarityInvert, loudness,
                loudnessAmount, bands, pitch, dynamics, value, saturation);
    }

    /** 换染色参数。 */
    public StudioRack withSaturation(SaturationParams value) {
        return new StudioRack(enabled, preampDb, balance, polarityInvert, loudness,
                loudnessAmount, bands, pitch, dynamics, stereo, value);
    }

    /**
     * 换音高。
     *
     * <p>允许在整条链「关着」（{@code enabled == false}）时设置：{@code enabled} 是
     * <strong>整条链的总闸</strong>，与「哪一项在工作」是两件事——界面上的音高滑杆不该
     * 因为链总闸关着就反着改回去。
     */
    public StudioRack withPitch(PitchParams value) {
        return new StudioRack(enabled, preampDb, balance, polarityInvert, loudness,
                loudnessAmount, bands, value, dynamics, stereo, saturation);
    }

    /** 改其中一段（下标越界时原样返回——界面上的行可能比段表长）。 */
    public StudioRack withBand(int index, EqBand band) {
        if (index < 0 || index >= bands.size() || band == null) {
            return this;
        }
        List<EqBand> next = new ArrayList<EqBand>(bands);
        next.set(index, band);
        return withBands(next);
    }

    /**
     * 这条链会不会碰采样。
     *
     * <p>判据要<strong>严</strong>：只要有一项可能改动采样就得报 true（位完美标记靠它）。
     * 增益为 0 dB、平衡居中、没有段、没开响度补偿、没反相 → false（= 直通）。
     */
    public boolean active() {
        if (!enabled) {
            return false;
        }
        if (Math.abs(preampDb) > 1e-6 || Math.abs(balance) > 1e-6 || polarityInvert) {
            return true;
        }
        if (loudness && loudnessAmount > 1e-6) {
            return true;
        }
        // 音高：参数「开着但正好 0 半音 0 音分」或全干时它不改采样，不算在工作
        if (pitch.active()) {
            return true;
        }
        if (dynamics.active() || stereo.active() || saturation.active()) {
            return true;
        }
        // 下标循环：`active()` 每块都会被问一次，for-each 的迭代器是实打实的每块分配
        for (int i = 0; i < bands.size(); i++) {
            if (!bands.get(i).isTransparent()) {
                return true;
            }
        }
        return false;
    }

    /** 有几个段是真正在起作用的（界面与日志用）。 */
    public int activeBands() {
        int n = 0;
        for (int i = 0; i < bands.size(); i++) {
            if (!bands.get(i).isTransparent()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 这条链引入的延迟（采样）。
     *
     * <p>链上只有音高那一台带前视（一个 CHUNK = 21.3 ms @48 kHz，见 {@code PitchModule}）；
     * 均衡是双二阶（无延迟）、增益是纯乘加（无延迟）。所以这里就是它的值。
     *
     * <p><b>为什么必须如实报</b>：这台机架如果是「出场曲」那一台，移调期间整条 A 就比
     * B 晚 21.3 ms——融合期里两首歌错开一个前视。引擎把延迟记进输出那本账之后
     * （见 docs/audio-studio.md §4），这段错位至少是<strong>已知、可测</strong>的，
     * 而不是「听起来有点糊」。<b>不报</b>才是真正的问题。
     */
    public int latencyFrames() {
        return latencyFrames(48_000);
    }

    /**
     * 这条链引入的延迟（采样 @ 给定采样率）。
     *
     * <p>三笔一起报：音高（一个 CHUNK = 1024 帧，与采样率无关）、限制器的前视
     * （{@code DynamicsModule} 按采样率算）、立体声的侧向延迟（按采样率算）。
     * <b>漏报任何一笔</b>，引擎那本延迟账就会让两首曲子错开，而那种错位听起来
     * 只是「有点糊」——正是最该避免的失败方式。
     */
    public int latencyFrames(int rate) {
        int frames = 0;
        if (pitch.active()) {
            frames += PitchModule.LATENCY_FRAMES;
        }
        frames += stereo.latencyFrames(rate);
        if (dynamics.active() && dynamics.limiterOn) {
            frames += dynamics.lookaheadFrames(rate);
        }
        return frames;
    }

    private static double clamp(double v, double min, double max) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public String toString() {
        return "StudioRack{" + (enabled ? "开" : "关") + ", 前级 " + preampDb + "dB, 平衡 "
                + balance + ", " + activeBands() + " 段, 响度补偿 " + (loudness ? "开" : "关")
                + ", 音高 " + pitch + ", 动态 " + dynamics + ", 立体声 " + stereo
                + ", 染色 " + saturation + "}";
    }
}
