package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;

/**
 * 音高模块：把音频整体移调（半音 + 音分），干湿可调。
 *
 * <p>流式那一层（分块推进、干湿对齐、延迟上报、关掉时的交叉淡化收尾）全在
 * {@link StreamingPitchShift} 里，这里只负责「参数 → 比例」这一件事。
 *
 * <h2>延迟</h2>
 *
 * <p>一个 CHUNK（48 kHz 下 1024 帧 = 21.3 ms），<strong>实测值</strong>而不是抄来的：
 * 噪声互相关峰在位移 1024 处（{@code PitchModuleTest#theReportedLatencyIsTheMeasuredOne}）。
 * 声码器本身不延迟信号（lag = 0），这笔延迟是这一层为了消除 OLA 交付节奏抖动刻意加的。
 *
 * <p>实时规则：缓冲一律构造时分配，{@link #process} <strong>零分配</strong>
 * （实测 0 字节/块）；换参数只做一次引用替换，<strong>不清</strong>声码器状态。
 */
public final class PitchModule implements StudioModule {

    /** 本模块引入的延迟（帧）——与 {@link StreamingPitchShift#LATENCY_FRAMES} 是同一个数。 */
    public static final int LATENCY_FRAMES = StreamingPitchShift.LATENCY_FRAMES;

    private final StreamingPitchShift shifter;

    private volatile PitchParams params = PitchParams.off();

    public PitchModule(int rate, int channels) {
        this.shifter = new StreamingPitchShift(rate, channels);
    }

    /** 换参数（允许分配；调用线程与音频线程之间只有这一次引用替换）。 */
    public void setParams(PitchParams value) {
        this.params = value == null ? PitchParams.off() : value;
    }

    public PitchParams params() {
        return params;
    }

    @Override
    public boolean active() {
        return params.active() || shifter.active();
    }

    @Override
    public int latencyFrames() {
        return active() ? LATENCY_FRAMES : 0;
    }

    @Override
    public void reset() {
        shifter.reset();
    }

    @Override
    public void process(AudioBuf buf) {
        PitchParams p = params;
        // 参数关掉时**不立刻停**：壳里还压着一个 CHUNK 的前视，直接返回会让那一段凭空
        // 消失（听感是关掉那一刻一个小断口）。所以先把它淡完，再真正停下。
        if (!p.active()) {
            if (shifter.active()) {
                shifter.flushHold(buf);
            }
            return;                            // 直通：一个采样都不碰（位完美的前提）
        }
        shifter.setSemitones(p.totalSemitones());
        shifter.process(buf, (float) p.mix);
    }

    @Override
    public String toString() {
        return "PitchModule{" + params + (shifter.active() ? "，收尾中" : "") + "}";
    }
}
