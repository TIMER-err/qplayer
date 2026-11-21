package dev.t1m3.qplayer.audio.engine;

import dev.t1m3.qplayer.audio.mix.Deck;

/**
 * 引擎里用到的帧 / 毫秒 / 秒换算，以及那几个「参数关系」常量。
 *
 * <p>为什么要有这么一个类：{@code PlayerController} 那一层说毫秒（{@code AudioBackend} 的
 * 所有时间量都是毫秒），而混音层说帧与秒。换算散落在回调、生产者线程、状态发布三处时，
 * 迟早会出现「一处按 1000 除、一处按 48000 除」这种对不上的错——而它的表现是进度条
 * 慢慢漂移，最难查。所以只留一份换算。
 *
 * <p>刻意<strong>不可实例化</strong>：这里全是纯静态换算，没有状态。
 */
public final class EngineFrames {

    /**
     * 单次 {@code Director.pump} 的产出上限（帧）。
     *
     * <p>它<strong>必须</strong>等于 {@link Deck#RENDER_FRAMES}：编排器给两个 deck 的
     * 缓冲就是按这个值分配的，给引擎自己的块缓冲配小了，{@code Deck.render} 会
     * <strong>直接报错</strong>而不是悄悄截断（那正是「静默丢帧」的入口）。
     */
    public static final int BLOCK_FRAMES = Deck.RENDER_FRAMES;

    /**
     * 环形缓冲的目标时长（毫秒）。
     *
     * <p>上游取 200 ms。太小会在负载波动时频繁欠载，太大则让暂停/跳转的响应变钝——
     * 而且暂停之后「环里剩下的还在响」的那一小段也变长（见 docs/audio-engine.md §2.6）。
     */
    public static final int DEFAULT_RING_MS = 200;

    private EngineFrames() {
        // 工具类，不给实例
    }

    /** 按目标时长算环形缓冲帧数（下限 1；采样率非法时按 48 kHz 估）。 */
    public static int ringFramesFor(int sampleRate, int wantedMs) {
        int rate = sampleRate > 0 ? sampleRate : 48_000;
        int ms = wantedMs > 0 ? wantedMs : DEFAULT_RING_MS;
        // 先乘后除，且用 long 中转：rate × ms 在 192 kHz × 200 ms 下是 3.84e7，int 装得下，
        // 但留 long 是为了让「有人把 wantedMs 传成几秒」时也不溢出。
        long frames = ((long) rate * (long) ms) / 1000L;
        return frames < 1L ? 1 : (int) Math.min(frames, Integer.MAX_VALUE);
    }

    /** 帧数 → 毫秒（向下取整；rate 非法时返回 0）。 */
    public static long msOfFrames(long frames, int sampleRate) {
        if (sampleRate <= 0 || frames <= 0L) {
            return 0L;
        }
        return frames * 1000L / (long) sampleRate;
    }

    /** 毫秒 → 帧数（向下取整；负数按 0）。 */
    public static long framesOfMs(long ms, int sampleRate) {
        if (sampleRate <= 0 || ms <= 0L) {
            return 0L;
        }
        return ms * (long) sampleRate / 1000L;
    }

    /** 帧数 → 秒（{@code Deck.seek} 要的是秒）。 */
    public static double secsOfFrames(long frames, int sampleRate) {
        if (sampleRate <= 0 || frames <= 0L) {
            return 0.0;
        }
        return (double) frames / (double) sampleRate;
    }

    /**
     * 一轮「一块」能装多少帧。
     *
     * <p>设备一次要的采样数可能比一块大：{@code OutputFiller} 会按暂存长度分批
     * {@code pop}，所以这里只用于「换算成帧」这一件事，不参与切块。
     */
    public static int framesOfSamples(int samples, int channels) {
        if (channels <= 0) {
            return 0;
        }
        return samples / channels;
    }
}
