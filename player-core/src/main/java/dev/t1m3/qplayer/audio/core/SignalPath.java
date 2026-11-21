package dev.t1m3.qplayer.audio.core;

/**
 * 信号路径。
 *
 * <p>这是 HiFi 的总开关：一旦选定，整条链路的处理规则随之改变。
 *
 * <p>对应 Sonalis 的 {@code sonalis_core::format::SignalPath}。默认值
 * {@link #PROCESSED} 与上游一致。
 */
public enum SignalPath {

    /** 处理路径：允许重采样与音量/效果处理，追求最大兼容性。 */
    PROCESSED,

    /**
     * 位完美路径：<strong>旁路重采样与一切电平处理</strong>，设备跟随源采样率。
     *
     * <p>注意这条路径的约束是「什么都不做」，不是「做得更好」。任何增益、均衡、
     * 淡入淡出都会破坏位完美——包括交叉淡化。但位完美是<strong>逐段</strong>的：
     * 只有切歌过渡的那几秒让步，歌曲主体照常直通（见 ADR-003 §2.2）。
     */
    BIT_PERFECT;

    /** 是否位完美。 */
    public boolean isBitPerfect() {
        return this == BIT_PERFECT;
    }

    /** 这条路径下允不允许做 DSP。 */
    public boolean allowsDsp() {
        return this == PROCESSED;
    }
}
