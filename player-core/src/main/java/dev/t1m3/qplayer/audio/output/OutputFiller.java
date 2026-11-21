package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.RingBuffer;

import java.util.Arrays;

/**
 * 音频回调的填充逻辑：把环形缓冲里的交错 f32 采样转换并写满设备给的缓冲。
 *
 * <p>对应 Sonalis 的 {@code fill::<T>(data, consumer, scratch)}。上游是 cpal 回调里
 * 泛型化的一次 dispatch；Java 这边设备 I/O 归宿主，但<strong>回调体本身是纯逻辑</strong>，
 * 所以留在 player-core：给定环 + 暂存 + 目标格式，结果完全确定，可以脱离声卡被测到。
 *
 * <h3>音频回调里不做的事（上游原文）</h3>
 *
 * 不分配内存、不加锁、不打印日志、不调用系统调用。唯一允许的操作是
 * {@code popOrSilence}（无锁读写）与采样格式转换（纯算术）。
 * 回调内的暂存缓冲在开流之前一次性分配好，之后永不增长——所以
 * {@code scratch} 是<strong>传进来的</strong>，不是这里 new 的。
 *
 * <h3>欠载时吐静音而不是少写采样</h3>
 *
 * 少写会让设备的播放位置与我们的写入进度逐渐错位，听感上表现为越来越明显的爆音；
 * 补静音则保持时钟恒定。
 *
 * <h3>为什么必须显式写满</h3>
 *
 * 上游原文：{@code data} 是设备给的缓冲，里面可能残留上一轮的内容或者未初始化的垃圾，
 * <strong>必须显式写满</strong>，不能「读空了就跳出循环」。
 */
public final class OutputFiller {

    private OutputFiller() {
    }

    /**
     * 把 {@code out} 整段填满。
     *
     * @param consumer 环形缓冲消费端（音频线程独占）
     * @param scratch  回调暂存缓冲，长度即单次 {@code pop} 的上限；开流前分配好，之后不变
     * @param format   目标采样格式
     * @param out      设备缓冲（<strong>字节</strong>）。长度必须是 {@code format.bytes()} 的整数倍
     */
    public static void fill(RingBuffer.Consumer consumer, float[] scratch,
                            OutputSampleFormat format, byte[] out) {
        int bytesPerSample = format.bytes();
        if (bytesPerSample <= 0) {
            throw AudioException.config("采样格式没有字节宽度：" + format);
        }
        int total = out.length / bytesPerSample;
        int offset = 0;
        while (offset < total) {
            int n = Math.min(total - offset, scratch.length);
            int got = consumer.pop(scratch, 0, n);
            if (got < n) {
                // 欠载：补静音，绝不「少写几个采样」
                Arrays.fill(scratch, got, n, 0f);
            }
            for (int i = 0; i < n; i++) {
                format.writeSample(scratch[i], out, (offset + i) * bytesPerSample);
            }
            offset += n;
        }
    }

    /**
     * 单次回调需要的暂存采样上限。
     *
     * <p>上游的 {@code SCRATCH_SAMPLES = 16_384}：任何设备都不会一次要求比这更多。
     */
    public static final int SCRATCH_SAMPLES = 16_384;
}
