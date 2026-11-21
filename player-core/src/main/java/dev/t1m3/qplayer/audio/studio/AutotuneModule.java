package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;

/**
 * 修跑调（自动音准）：检测 → 量化到音阶 → 用移调器把偏差拉回去。
 *
 * <h2>它怎么工作（每一拍 10 ms 走一遍）</h2>
 *
 * <ol>
 *   <li>把输入折成单声道喂给 {@link PitchDetector}（40 ms 窗 / 10 ms 跳，60–1200 Hz）；</li>
 *   <li>有可信音高（clarity ≥ {@value #MIN_CLARITY}）时算「离最近的音差多少音分」；</li>
 *   <li>修正量 = 强度 × (平滑后的音分偏差)。<strong>平滑</strong>是
 *       {@link AutotuneParams#preserveVibrato 保留颤音}的落点：平滑之后剩下的就是
 *       「整体偏高/偏低」，颤音（交流成分）没被碰；</li>
 *   <li>再对修正量做一次一阶低通（时间常数 = {@link AutotuneParams#retuneMs 回正速度}），
 *       这就是「电音 ↔ 像人」那个旋钮；</li>
 *   <li>把修正量（音分 → 半音）交给 {@link StreamingPitchShift} 逐块施加。</li>
 * </ol>
 *
 * <h2>没有可信音高时不修（这一条比修错更重要）</h2>
 *
 * <p>气声、齿音、呼吸声没有基频，伴奏里的和弦更是没有<strong>单一</strong>基频。检测器
 * 给出的置信度不够时，这里<strong>保持上一次的修正量不动</strong>（而不是归零、也不是瞎修）：
 * 归零会在每个辅音上产生一次抖动，瞎修会把整首歌推走。判据见
 * {@code AutotuneTest#noiseIsNotTouched}。
 *
 * <h2>只作用在人声轨</h2>
 *
 * <p>这一条是<strong>接线上的约束</strong>而不是参数：模块只装在「喂进来的是人声」的那条
 * 链上（见 {@code RackProcessor} 的 vocalChain 约定与 docs/audio-studio.md §8）。理由写在
 * {@link AutotuneParams} 的类注释里：复音素材上的基频检测没有意义。
 *
 * <h2>延迟</h2>
 *
 * <p>检测窗 40 ms + 移调壳的一个 CHUNK（21.3 ms）。<strong>报出来的只有移调那一段</strong>
 * （{@link #latencyFrames()}）：检测窗是「看过去」而不是「延迟输出」，
 * 把它算进延迟账会让引擎把 A/B 错开 40 ms。
 */
public final class AutotuneModule implements StudioModule {

    /** 置信度门槛：低于它就不修（见类注释）。 */
    public static final double MIN_CLARITY = 0.60;

    private final PitchDetector detector;
    private final StreamingPitchShift shifter;
    private final int channels;

    private volatile AutotuneParams params = AutotuneParams.off();

    /**
     * 平滑后的音高（MIDI 音号，含小数）。
     *
     * <p><strong>目标音必须由它决定，不能由瞬时音高决定</strong>——这是一个实测出来的
     * 设计要点：颤音让瞬时音高在半个半音里来回穿，而「量化到最近的音」这个函数在
     * 两个音的中点上是<strong>跳变</strong>的。于是瞬时偏差在跳变两侧符号相反、平均下来
     * 接近零，修正量就被自己抵消掉了：实测「唱高 60 音分、带 ±20 音分颤音」的人声
     * 只被修了 6.9 音分（判据要求 40 音分全部修掉）。
     *
     * <p>先平滑（约 120 ms）再量化，目标音就稳定了；颤音随后作为「相对这个固定目标的偏差」
     * 被保留下来。这也是真实修音器的做法。
     */
    private double smoothedMidi;

    /** 目标音（Hz）——由 {@link #smoothedMidi} 量化得到，每跳更新一次。 */
    private double targetHz;

    /**
     * 平滑器是不是已经用第一个可信音高初始化过了。
     *
     * <p>不初始化的话，第一跳的 {@code smoothedMidi} 从 0 出发（= MIDI 音号 0，约 8 Hz），
     * 量化出来的目标是那个低得离谱的音，修正量一上来就是 −839 音分——实测输出会先被
     * 拽到 280 Hz 附近再慢慢爬回来，整段前 0.5 秒都是废的（判据当场红）。
     */
    private boolean pitchPrimed;

    /** 平滑后的「离目标的音分偏差」（保留颤音那一层）——「保留颤音」就是靠它。 */
    private double smoothedCents;
    /** 当前施加的修正量（音分，已经过回正速度那一级低通）。 */
    private double appliedCents;
    /** 上一次检测到的音高（Hz），只用于诊断/判据。 */
    private double lastHz;
    /** 已经修了多少帧（有声帧计数，诊断用）。 */
    private long voicedFrames;

    public AutotuneModule(int rate, int channels) {
        this.channels = Math.max(channels, 1);
        this.detector = new PitchDetector(rate);
        this.shifter = new StreamingPitchShift(rate, this.channels);
    }

    public void setParams(AutotuneParams value) {
        this.params = value == null ? AutotuneParams.off() : value;
    }

    public AutotuneParams params() {
        return params;
    }

    /** 最近一次检测到的音高（Hz，0 = 不可信）；诊断与判据用。 */
    public double detectedHz() {
        return lastHz;
    }

    /** 修过的有声帧数（诊断用）。 */
    public long voicedFrames() {
        return voicedFrames;
    }

    @Override
    public boolean active() {
        return params.active() || shifter.active();
    }

    @Override
    public int latencyFrames() {
        return active() ? StreamingPitchShift.LATENCY_FRAMES : 0;
    }

    @Override
    public void reset() {
        detector.reset();
        shifter.reset();
        smoothedMidi = 0.0;
        targetHz = 0.0;
        pitchPrimed = false;
        smoothedCents = 0.0;
        appliedCents = 0.0;
        lastHz = 0.0;
        voicedFrames = 0L;
    }

    @Override
    public void process(AudioBuf buf) {
        AutotuneParams p = params;
        if (!p.active()) {
            if (shifter.active()) {
                shifter.flushHold(buf);
            }
            return;
        }
        int frames = buf.frames();
        if (frames <= 0) {
            return;
        }
        updateCorrection(buf, frames, p);
        // 修正量换算成移调量：音分 → 半音。**不取整**——取整就是「只修半音」，
        // 而修音准的全部意义就在那几十音分里。
        shifter.setSemitones(appliedCents / 100.0);
        shifter.process(buf, (float) p.mix);
    }

    /** 逐采样跑检测，每跳更新一次修正量（**在移调之前**，所以这一块的偏差用得上）。 */
    private void updateCorrection(AudioBuf buf, int frames, AutotuneParams p) {
        int chs = Math.min(buf.channels(), channels);
        float[] backing = buf.backingArray();
        // 目标音用的平滑：约 120 ms。它必须比颤音的周期长一点，才能把颤音抹平、
        // 让「最近的音」不再来回跳（见 smoothedMidi 的注释）。
        double pitchAlpha = 1.0 - Math.exp(-PitchDetector.HOP_SECS / 0.120);
        // 「保留颤音」的平滑时间常数：100 ms（比颤音的周期 200–400 ms 短，
        // 所以颤音作为交流成分留在检测里、不进入修正量）
        double smoothAlpha = 1.0 - Math.exp(-PitchDetector.HOP_SECS / 0.100);
        // 「回正速度」那一级：0 ms 时要立刻到位（alpha = 1）
        double retuneSecs = p.retuneMs / 1000.0;
        double retuneAlpha = retuneSecs <= 1e-9
                ? 1.0 : 1.0 - Math.exp(-PitchDetector.HOP_SECS / retuneSecs);
        for (int i = 0; i < frames; i++) {
            float mono = 0f;
            for (int ch = 0; ch < chs; ch++) {
                mono += backing[buf.channelBase(ch) + i];
            }
            mono /= chs;
            if (!detector.push(mono)) {
                continue;
            }
            double hz = detector.hz();
            lastHz = hz;
            if (hz <= 0.0 || detector.clarity() < MIN_CLARITY) {
                // 不可信：**保持上一次的修正量**（见类注释），并把平滑值往回带一点点，
                // 免得一段长噪声之后带着一个过期的偏差不放
                smoothedCents *= (1.0 - smoothAlpha * 0.5);
                continue;
            }
            // ① 先平滑音高，再由**平滑后的音高**定目标音（见 smoothedMidi 的注释）
            double midi = ScaleQuantizer.midiOf(hz, p.a4);
            if (!pitchPrimed) {
                // 第一个可信音高直接作为起点（从 0 起步会让第一跳的修正量飞到 −839 音分）
                smoothedMidi = midi;
                smoothedCents = 0.0;
                pitchPrimed = true;
            } else {
                smoothedMidi += (midi - smoothedMidi) * pitchAlpha;
            }
            double smoothedHz = ScaleQuantizer.hzOf(smoothedMidi, p.a4);
            targetHz = ScaleQuantizer.targetHz(smoothedHz, p.scale, p.a4, p.tonic);
            if (!(targetHz > 0.0)) {
                continue;
            }
            // ② 相对**这个固定目标**的瞬时偏差（颤音在这里作为交流成分留着）
            double cents = 1200.0 * Math.log(hz / targetHz) / Math.log(2.0);
            smoothedCents += (cents - smoothedCents) * smoothAlpha;
            // 保留颤音 = 用「平滑后的偏差」；不保留 = 用瞬时偏差（颤音也一起拉平）
            double basis = p.preserveVibrato ? smoothedCents : cents;
            double want = -p.strength * basis;
            appliedCents += (want - appliedCents) * retuneAlpha;
            voicedFrames++;
        }
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "AutotuneModule{%s，最近音高 %.1f Hz，修正 %+.1f 音分%s}",
                params, Double.valueOf(lastHz), Double.valueOf(appliedCents),
                shifter.active() ? "，收尾中" : "");
    }
}
