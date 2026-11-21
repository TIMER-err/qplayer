package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;

/**
 * 立体声工具（P6）的参数：<strong>宽度 → 低音单声道 → 侧向延迟 → 声道互换 → 耳机交叉馈送</strong>。
 *
 * <h2>每一项解决什么</h2>
 *
 * <ul>
 *   <li><b>宽度</b>（M/S 比例 0..2）：0 = 完全单声道，1 = 原样，2 = 侧信号翻倍。
 *       老录音太窄、或者 DJ 混音里两首歌宽度不一时用它对齐；</li>
 *   <li><b>低音单声道</b>（分频点 20–500 Hz）：把侧信号里的低频拿掉。
 *       理由不是听感而是<strong>物理</strong>：低频的波长比人头间距长得多，
 *       两侧的低频本来就几乎同相，而黑胶/俱乐部的低音单声道化能避免跳针与相位抵消。
 *       播放器上它的用处是「耳机里低频不再左右晃」；</li>
 *   <li><b>侧向延迟</b>（0..2 ms）：把侧信号延后一点点 = Haas 效应，声场变宽，
 *       但<strong>会与单声道兼容性打架</strong>——所以它默认关，而且如实在文档里写清；</li>
 *   <li><b>声道互换</b>：修某些录音左右接反了（听不出但看着别扭）；</li>
 *   <li><b>交叉馈送</b>（耳机用，0..1）：把对侧声道衰减、低通、延迟一点点送过来，
 *       模拟「头挡住的那一部分」。耳机里听老录音（人声全在一边）时它最有用，
 *       与已有的空间音频渲染器<strong>并存但独立</strong>——那个是整套双耳渲染，
 *       这个是给普通立体声加一点串音。</li>
 * </ul>
 *
 * <h2>判据（{@code StereoModuleTest}）</h2>
 *
 * <ul>
 *   <li>全参数中性（宽度 1、不低音单声道、不延迟、不互换、不交叉馈送）→ <strong>逐位直通</strong>；</li>
 *   <li>宽度 0 → 左右<strong>逐位相同</strong>；宽度 2 → 侧信号幅度加倍（精确）；</li>
 *   <li>低音单声道：50 Hz 的侧信号被压 ≥ 20 dB，而 1 kHz 的侧信号变化 &lt; 0.5 dB；</li>
 *   <li>交叉馈送 0.5：对侧电平精确为 −6 dB（不含滤波器的那一份），同侧几乎不动。</li>
 * </ul>
 */
public final class StereoParams {

    public final boolean enabled;
    /** 宽度（0 = 单声道，1 = 原样，2 = 侧 ×2）。 */
    public final double width;
    /** 低音单声道：分频点（Hz）；0 = 关。 */
    public final double bassMonoHz;
    /** 侧向延迟（毫秒，0..2）；0 = 关。 */
    public final double sideDelayMs;
    /** 左右互换。 */
    public final boolean channelSwap;
    /** 交叉馈送量（0..1）；0 = 关。 */
    public final double crossfeed;

    public StereoParams(boolean enabled, double width, double bassMonoHz, double sideDelayMs,
                        boolean channelSwap, double crossfeed) {
        this.enabled = enabled;
        this.width = clamp(width, 0.0, 2.0);
        this.bassMonoHz = clamp(bassMonoHz, 0.0, 500.0);
        this.sideDelayMs = clamp(sideDelayMs, 0.0, 2.0);
        this.channelSwap = channelSwap;
        this.crossfeed = clamp(crossfeed, 0.0, 1.0);
    }

    /** 全中性（= 直通）。 */
    public static StereoParams neutral() {
        return new StereoParams(false, 1.0, 0.0, 0.0, false, 0.0);
    }

    /** 这一套参数会不会改采样。 */
    public boolean active() {
        if (!enabled) {
            return false;
        }
        return Math.abs(width - 1.0) > 1e-9 || bassMonoHz > 0.0 || sideDelayMs > 1e-9
                || channelSwap || crossfeed > 1e-9;
    }

    /** 侧向延迟的采样数（@ 给定采样率）。 */
    public int sideDelayFrames(int rate) {
        return (int) Math.round(sideDelayMs / 1000.0 * Math.max(1, rate));
    }

    /**
     * 这一套参数引入的延迟（采样 @ 给定采样率）：只有侧向延迟会引入。
     *
     * <p>交叉馈送的那点延迟（0.3 ms）<strong>不算</strong>在这里：它是「串音」的一部分
     * （对侧信号延迟一点点送过来），不是整条链的延迟——把它算进去会让引擎把两首曲子
     * 白白错开 0.3 ms。
     */
    public int latencyFrames(int rate) {
        return active() ? sideDelayFrames(rate) : 0;
    }

    private static double clamp(double v, double min, double max) {
        if (Double.isNaN(v)) {
            return min;
        }
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "StereoParams{%s 宽度 %.2f、低音单声道 %.0f Hz、侧向延迟 %.2f ms、互换 %s、"
                        + "交叉馈送 %.2f}",
                enabled ? "开" : "关", Double.valueOf(width), Double.valueOf(bassMonoHz),
                Double.valueOf(sideDelayMs), channelSwap ? "是" : "否",
                Double.valueOf(crossfeed));
    }
}
