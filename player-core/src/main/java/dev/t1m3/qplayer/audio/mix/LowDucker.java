package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.dsp.SplitLowHigh;

/**
 * <b>低频让位</b>（P5）：融合期把两侧低频的<b>总量</b>压到「不超过其中较响的一首」。
 *
 * <h3>为什么低频要单独管</h3>
 *
 * <p>「两首歌皱在一起很糊」里最硬的一半是低频。两首歌的底鼓/贝斯叠起来是同相相加——
 * 实测两路各 0.4 的 60 Hz 在等功率淡化中点（各 0.707）合成 <b>≈ 0.566</b>，也就是单首
 * （0.283）的 <b>整整两倍（+6 dB）</b>；反相时又会互相抵消出「抖」。两种都不像音乐。
 *
 * <p>低频交接那一档（{@code BASS_SWAP}）用<b>固定进度</b>把低频交出去，那是一种接法；
 * 这一层要的是<b>所有</b>融合档位都自动做到「低频不叠」。
 *
 * <h3>做法</h3>
 *
 * <p>只对<b>低频段</b>动手：中高频原样相加（那是「两首歌同时在」的自然听感），低频则乘一个
 * 两侧<b>共有</b>的增益
 *
 * <pre>
 *   g = clamp( max(envA, envB) / (envA + envB), MIN_GAIN, 1 )
 * </pre>
 *
 * <p>两侧一样响时 {@code g = 0.5}（各让一半，加起来正好是一首的量）；只有一侧有低频时
 * {@code g = 1}（一个采样都不碰）；两边都没低频也是 1。上限 {@value #MIN_GAIN} 就是
 * 「最多 −6 dB」，是个有界的效果，不会把低音压瘪。
 *
 * <h3>为什么包络取得慢</h3>
 *
 * <p>{@value #ENVELOPE_TAU_SECS} s 的包络比一拍长得多，所以它跟的是<b>低音的档位</b>而不是
 * 每一次鼓点——跟着鼓点压会泵，那是压缩器的听感，比糊更明显。增益本身再过一道
 * {@value #GAIN_TAU_SECS} s 的一阶跟随，所以<b>没有台阶</b>。
 *
 * <h3>状态纪律</h3>
 *
 * <p>和 {@link LevelMatcher} 同一条：它是有状态的，必须在<b>混音那一层</b>逐帧驱动
 * （{@link Mixer#mixTwoMatched}），也就是「交付多少帧、状态走多少帧」。先算一整块增益数组
 * 再去混的话，素材缓冲里没交付的余量会被算两遍，状态累积跑偏。
 *
 * <p>包络只跟<b>第 0 声道</b>：低频在绝大多数混音里是居中的，用一个声道就够，
 * 也省掉「每个声道各自算一遍增益」的不一致。
 */
public final class LowDucker {

    /** 低频包络的时间常数（秒）。慢：跟的是低音档位，不是每一次鼓点。 */
    static final float ENVELOPE_TAU_SECS = 0.5f;

    /** 增益跟随的时间常数（秒）。慢到没有台阶。 */
    static final float GAIN_TAU_SECS = 0.2f;

    /** 增益下限（两侧一样响时的取值，也就是最多 −6 dB）。 */
    static final float MIN_GAIN = 0.5f;

    /** 低于这个功率就当没有低频（−100 dB）。 */
    private static final float SILENT_POWER = 1e-10f;

    private final SplitLowHigh[] fromSplit;
    private final SplitLowHigh[] toSplit;
    private final float[] scratch = new float[2];

    private final float envelopeAlpha;
    private final float gainAlpha;

    private float envA;
    private float envB;
    private float gain = 1.0f;
    private float lastGain = 1.0f;

    public LowDucker(int rate, int channels, float crossoverHz) {
        int ch = Math.max(channels, 1);
        this.fromSplit = new SplitLowHigh[ch];
        this.toSplit = new SplitLowHigh[ch];
        for (int c = 0; c < ch; c++) {
            this.fromSplit[c] = new SplitLowHigh(Math.max(rate, 1), crossoverHz);
            this.toSplit[c] = new SplitLowHigh(Math.max(rate, 1), crossoverHz);
        }
        this.envelopeAlpha = alphaFor(ENVELOPE_TAU_SECS, rate);
        this.gainAlpha = alphaFor(GAIN_TAU_SECS, rate);
    }

    private static float alphaFor(float tauSecs, int rate) {
        if (rate <= 0) {
            return 1.0f;
        }
        double a = 1.0 - Math.exp(-1.0 / ((double) tauSecs * rate));
        return (float) Math.max(0.0, Math.min(1.0, a));
    }

    /** 回到起始状态（每次过渡开始时调用）。 */
    public void reset() {
        for (int c = 0; c < fromSplit.length; c++) {
            fromSplit[c].reset();
        }
        for (int c = 0; c < toSplit.length; c++) {
            toSplit[c].reset();
        }
        envA = 0.0f;
        envB = 0.0f;
        gain = 1.0f;
        lastGain = 1.0f;
    }

    /**
     * 某一路、某个声道这一帧的<b>低频样点</b>（滤波器按帧推进一次）。
     *
     * <p>调用方拿 {@code hi = x − lo} 就得到高频段——两段相加等于原信号
     * （{@link SplitLowHigh} 的契约）。
     */
    public float lowOf(boolean from, int channel, float x) {
        SplitLowHigh[] splits = from ? fromSplit : toSplit;
        if (channel < 0 || channel >= splits.length) {
            return 0.0f;
        }
        return splits[channel].processInto(x, scratch)[0];
    }

    /** 把这一帧的低频喂进包络（只在第 0 声道上调一次）。 */
    public void track(float lowA, float lowB) {
        envA += (lowA * lowA - envA) * envelopeAlpha;
        envB += (lowB * lowB - envB) * envelopeAlpha;
    }

    /** 用当前包络算这一帧两侧共有的低频增益。 */
    public float gainForFrame(float strength) {
        float target = 1.0f;
        float sum = envA + envB;
        if (sum > SILENT_POWER) {
            float louder = Math.max(envA, envB);
            target = louder / sum;
            if (target < MIN_GAIN) {
                target = MIN_GAIN;
            } else if (target > 1.0f) {
                target = 1.0f;
            }
        }
        gain += (target - gain) * gainAlpha;

        float s = strength;
        if (s <= 0.0f) {
            lastGain = 1.0f;
            return lastGain;
        }
        if (s > 1.0f) {
            s = 1.0f;
        }
        lastGain = 1.0f + (gain - 1.0f) * s;
        return lastGain;
    }

    /**
     * 逐帧低频增益用的暂存（**复用**，理由同 {@link LevelMatcher#buffer(int)}）。
     *
     * <p>增益是两侧共有的，但混音循环是「按声道」的：第 0 声道算一次、写进这个数组，
     * 其余声道读它——所以包络也只在第 0 声道上推进一次。
     */
    public float[] buffer(int frames) {
        if (gainBuffer == null || gainBuffer.length < frames) {
            gainBuffer = new float[Math.max(frames, 1024)];
        }
        return gainBuffer;
    }

    private float[] gainBuffer;

    /** 上一帧交付的低频增益（判据与日志用）。 */
    public float lastGain() {
        return lastGain;
    }
}
