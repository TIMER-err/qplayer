package dev.t1m3.qplayer.desktop.audio.output;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.RingBuffer;
import dev.t1m3.qplayer.audio.output.OutputConfig;
import dev.t1m3.qplayer.audio.output.OutputFiller;
import dev.t1m3.qplayer.audio.output.OutputSampleFormat;
import dev.t1m3.qplayer.audio.output.OutputStreamControl;
import dev.t1m3.qplayer.audio.output.SampleConversion;
import dev.t1m3.qplayer.util.Logger;

import javax.sound.sampled.SourceDataLine;

import java.util.Arrays;

/**
 * 桌面端的已开流输出：一条 {@code SourceDataLine} 加一条专用的写线程。
 *
 * <p>对应上游 {@code build_stream::<T>} 建立的那条 cpal 流与它内部的
 * {@code fill(data, consumer, scratch)} 回调。
 *
 * <h3>为什么需要自己的写线程</h3>
 *
 * cpal 是<strong>推模型</strong>：声卡按周期回调我们，我们在回调里填充。
 * {@code javax.sound.sampled} 是<strong>拉模型</strong>：没有回调 API，
 * 只能由我们调 {@code SourceDataLine.write(...)} 把数据塞进去（缓冲满了它会阻塞）。
 * 所以这里起一条专用线程模拟回调节奏，并且严格遵守上游对回调的三条禁令——
 * 线程里<strong>不分配内存、不加锁、不打印日志</strong>：暂存缓冲与目标缓冲都在开流时
 * 一次性分配好，之后永不增长；唯一的操作是 {@code popOrSilence}（无锁读环）与
 * 采样格式转换（纯算术）。
 *
 * <h3>欠载</h3>
 *
 * 与上游一致：读空了<strong>吐静音而不是少写采样</strong>。少写会让设备的播放位置与
 * 我们的写入进度逐渐错位，听感上表现为越来越明显的爆音。实现在
 * {@link OutputFiller}（纯逻辑，在 player-core 有测试锁着）。
 *
 * <h3>24 位的存储约定</h3>
 *
 * 上游的 {@code I24} 是「4 字节存储、24 位有效」，而 javax.sound 的 24 位 PCM 惯例是
 * <strong>3 字节紧凑打包</strong>（{@code frameSize == 3 × channels}）。这里按线路
 * 实际帧长决定怎么打包：3 字节就丢掉那个符号扩展字节，4 字节就用 player-core 的写法。
 */
final class JavaSoundOutputStream implements OutputStreamControl {

    /** 单次写入设备的帧数（设备缓冲会按这个粒度被填满）。 */
    private static final int BLOCK_FRAMES = 1_024;

    private final SourceDataLine line;
    private final OutputSampleFormat format;
    private final RingBuffer.Consumer consumer;
    /** 线路上每个采样的字节数（可能与 {@link OutputSampleFormat#bytes()} 不同，见类注释）。 */
    private final int wireBytes;
    private final float[] scratch = new float[OutputFiller.SCRATCH_SAMPLES];
    private final byte[] buffer;

    private volatile boolean running;
    private Thread writer;

    JavaSoundOutputStream(SourceDataLine line, OutputConfig config, RingBuffer.Consumer consumer) {
        this.line = line;
        this.format = config.sampleFormat();
        this.consumer = consumer;

        int frameSize = line.getFormat().getFrameSize();
        int channels = line.getFormat().getChannels();
        if (frameSize <= 0 || channels <= 0) {
            throw AudioException.output("设备报告的帧长不可用：" + line.getFormat());
        }
        this.wireBytes = frameSize / channels;
        if (wireBytes != format.bytes() && !(format == OutputSampleFormat.I24 && wireBytes == 3)) {
            throw AudioException.output("无法把 " + format + " 打包成 " + wireBytes + " 字节/采样");
        }
        this.buffer = new byte[wireBytes * channels * BLOCK_FRAMES];
    }

    @Override
    public void play() {
        try {
            line.start();
        } catch (RuntimeException e) {
            // 上游对 stream.play() 的失败直接报错，不吞——开不响的流比没有流更误导人
            throw AudioException.output("启动音频流失败：" + e);
        }
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                pump();
            }
        }, "qplayer-audio-output");
        t.setDaemon(true);
        writer = t;
        t.start();
    }

    /** 写循环。异常只记一次日志就退出，绝不在音频线程里抛。 */
    private void pump() {
        try {
            while (running) {
                fillOnce();
                int offset = 0;
                while (offset < buffer.length && running) {
                    int n = line.write(buffer, offset, buffer.length - offset);
                    if (n <= 0) {
                        break;
                    }
                    offset += n;
                }
            }
        } catch (Throwable t) {
            if (running) {
                // 上游在 cpal 的错误回调里 eprintln：回调线程里不能 panic，也不该频繁打印
                Logger.error("[qplayer-output] 音频流错误: " + t);
            }
        } finally {
            running = false;
        }
    }

    /** 填满整块设备缓冲。读空了补静音，绝不「少写几个采样」。 */
    private void fillOnce() {
        if (wireBytes == format.bytes()) {
            OutputFiller.fill(consumer, scratch, format, buffer);
            return;
        }
        // format == I24 且线路是 3 字节紧凑打包（构造时已校验）
        int total = buffer.length / 3;
        int offset = 0;
        while (offset < total) {
            int n = Math.min(total - offset, scratch.length);
            int got = consumer.pop(scratch, 0, n);
            if (got < n) {
                Arrays.fill(scratch, got, n, 0f);
            }
            for (int i = 0; i < n; i++) {
                int x = SampleConversion.f32ToI24(scratch[i]);
                int p = (offset + i) * 3;
                buffer[p] = (byte) x;
                buffer[p + 1] = (byte) (x >> 8);
                buffer[p + 2] = (byte) (x >> 16);
            }
            offset += n;
        }
    }

    @Override
    public boolean isRunning() {
        return running && line.isRunning();
    }

    @Override
    public void close() {
        running = false;
        Thread t = writer;
        writer = null;
        try {
            // stop + flush 会把阻塞在 write 里的写线程放出来
            line.stop();
            line.flush();
        } catch (RuntimeException ignored) {
            // 已经关了 / 驱动抽风：继续往下关
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            line.close();
        } catch (RuntimeException ignored) {
            // 重复关闭是允许的
        }
    }

    @Override
    public String toString() {
        return "JavaSoundOutputStream{" + line.getFormat() + ", running=" + isRunning() + '}';
    }
}
