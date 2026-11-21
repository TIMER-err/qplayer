package dev.t1m3.qplayer.android.audio.output;

import android.media.AudioTrack;
import android.os.Process;

import dev.t1m3.qplayer.android.audio.AndroidAudioFormats;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.RingBuffer;
import dev.t1m3.qplayer.audio.output.OutputConfig;
import dev.t1m3.qplayer.audio.output.OutputFiller;
import dev.t1m3.qplayer.audio.output.OutputSampleFormat;
import dev.t1m3.qplayer.audio.output.OutputStreamControl;
import dev.t1m3.qplayer.util.Logger;

import java.util.Arrays;

/**
 * 安卓端的已开流输出：一条 {@code AudioTrack(MODE_STREAM)} 加一条专用的写线程。
 *
 * <p>对应上游 {@code build_stream::<T>} 建立的那条 cpal 流与它内部的
 * {@code fill(data, consumer, scratch)} 回调，也是桌面端 {@code JavaSoundOutputStream}
 * 的同一位置。
 *
 * <h3>这条流永不中断（ADR-003 §2）</h3>
 *
 * <p>{@link #play()} 之后写线程一直跑，换曲、过渡、跳转都<strong>不</strong>停流也不重启：
 * 数据只是从环形缓冲里换了一批。真正让 gapless 无缝的是解码层的编码器延迟补偿
 * 加上这条不中断的流，<strong>不是淡化</strong>。
 *
 * <h3>回调语境里的三条禁令</h3>
 *
 * <p>写线程扮演的是「音频回调」，所以严格遵守上游的约定：
 * <strong>不分配内存、不加锁、不打印日志</strong>（也不调系统调用）。
 * 暂存缓冲与目标缓冲都在开流时一次性分配好，之后永不增长；循环里唯一的操作是
 * {@code consumer.pop(...)}（无锁读环）与采样格式转换（纯算术）。
 * 唯一允许的「系统调用」是 {@code AudioTrack.write(..., WRITE_BLOCKING)} 本身——
 * 它就是这个回调存在的意义（阻塞写入让系统按自己的节奏给我们回压）。
 *
 * <h3>欠载</h3>
 *
 * <p>与上游一致：读空了<strong>吐静音而不是少写采样</strong>。少写会让设备的播放位置与
 * 我们的写入进度逐渐错位，听感上表现为越来越明显的爆音。
 * 实现在 {@link OutputFiller}（纯逻辑，player-core 里有测试锁着），这里只负责
 * 「把整块填满 → 阻塞写出去」。
 *
 * <h3>24 位的存储约定</h3>
 *
 * <p>上游的 {@code I24} 是「4 字节存储、24 位有效」，而 AudioTrack 的
 * {@code ENCODING_PCM_24BIT_PACKED} 是<strong>3 字节紧凑打包</strong>。
 * 与桌面端 {@code JavaSoundOutputStream} 对 javax.sound 的 3 字节 24 位 PCM 一样，
 * 这里按线路实际帧长重新打包（用 {@link AndroidAudioFormats#pack24Packed}），
 * 不改 {@code OutputSampleFormat} 的语义。
 */
final class AndroidOutputStream implements OutputStreamControl {

    /** 单次写入设备的帧数（设备缓冲会按这个粒度被填满）。 */
    private static final int BLOCK_FRAMES = 1_024;

    private final AudioTrack track;
    private final OutputSampleFormat format;
    private final RingBuffer.Consumer consumer;
    private final int channels;
    /** 线路上每个采样的字节数（I24 时是 3，与 {@link OutputSampleFormat#bytes()} 不同，见类注释）。 */
    private final int wireBytes;
    private final float[] scratch = new float[OutputFiller.SCRATCH_SAMPLES];
    private final byte[] buffer;

    private volatile boolean running;
    private volatile boolean writeFailed;
    /** 是否已经成功写出去过一块（只由写线程读写；见 {@link #pump()} 里的那一条诊断）。 */
    private boolean firstWriteDone;
    /** 这条配置写不进去时回报给设备（让它下次别再挑这一档）；可为 {@code null}。 */
    private final java.util.function.Consumer<String> onConfigRejected;
    private Thread writer;

    AndroidOutputStream(AudioTrack track, OutputConfig config, RingBuffer.Consumer consumer,
                        java.util.function.Consumer<String> onConfigRejected) {
        this.track = track;
        this.format = config.sampleFormat();
        this.consumer = consumer;
        this.channels = config.channels();
        this.onConfigRejected = onConfigRejected;

        int sampleBytes = format.bytes();
        this.wireBytes = format == OutputSampleFormat.I24 ? 3 : sampleBytes;
        if (wireBytes != sampleBytes && !(format == OutputSampleFormat.I24 && wireBytes == 3)) {
            throw AudioException.output("无法把 " + format + " 打包成 " + wireBytes + " 字节/采样");
        }
        this.buffer = new byte[wireBytes * channels * BLOCK_FRAMES];
        if (this.buffer.length <= 0) {
            // 空缓冲会让写循环「每次都没有东西可写」：线程活着、一个字节都不写、也不报错，
            // 上游看到的是「环形缓冲一直满 → 输出停滞超过 2000 毫秒」。宁可当场喊出来。
            throw AudioException.output("输出缓冲长度为 0（" + format + " / " + channels
                    + " 声道 / wire=" + wireBytes + " 字节每采样）");
        }
    }

    /**
     * 启动写线程。
     *
     * <p>上游对 {@code stream.play()} 的失败直接报错，不吞——一个开不响的流比没有流更误导人。
     */
    @Override
    public void play() {
        try {
            track.play();
        } catch (RuntimeException e) {
            throw AudioException.output("启动音频流失败：" + e);
        }
        // 真机上「完全不出声 + 环形缓冲一直满」这类故障，最要紧的是分清两种情形：
        // ①流压根没进入播放状态（playState 不是 PLAYING）；②流在跑但设备就是不消费。
        // 所以这一刻把 AudioTrack 的真实读数记下来——没有这一行，两种情形从日志上
        // 完全一样（都只会看到引擎那句「输出停滞」）。
        int state = -1;
        int playState = -1;
        int rate = 0;
        int channelCount = 0;
        int bufferFrames = -1;
        int performanceMode = -1;
        try {
            state = track.getState();
            playState = track.getPlayState();
            rate = track.getSampleRate();
            channelCount = track.getChannelCount();
            bufferFrames = track.getBufferSizeInFrames();
            performanceMode = track.getPerformanceMode();
        } catch (RuntimeException ignored) {
            // 个别 ROM 上这些读数会抛；诊断读不出来不该影响能不能出声
        }
        Logger.info("[qplayer-output] 流已启动：state={} playState={} {} Hz / {} ch / {} 设备缓冲 {} 帧 perf={}",
                Integer.valueOf(state), Integer.valueOf(playState), Integer.valueOf(rate),
                Integer.valueOf(channelCount), format, Integer.valueOf(bufferFrames),
                Integer.valueOf(performanceMode));
        if (playState != AudioTrack.PLAYSTATE_PLAYING) {
            // 报错而不是继续等：一条进不了播放状态的流，唯一的结局是上游等满 2 秒
            // 然后当成「设备断开」，而真正的病因（playState）会被那句笼统的话盖掉。
            try {
                track.release();
            } catch (RuntimeException ignored) {
            }
            throw AudioException.output("AudioTrack 没能进入播放状态（playState=" + playState
                    + "，state=" + state + "）");
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
        // 写线程就是「音频回调线程」：给它音频级优先级，否则屏幕/GC 一忙就欠载。
        // 与引擎生产者线程（audio-engine §2 要求 THREAD_PRIORITY_URGENT_AUDIO）同一条约定。
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (RuntimeException ignored) {
            // 设置优先级失败不影响正确性，继续
        }
        try {
            while (running) {
                fillOnce();
                int offset = 0;
                while (offset < buffer.length && running) {
                    int n = track.write(buffer, offset, buffer.length - offset,
                            AudioTrack.WRITE_BLOCKING);
                    if (n < 0) {
                        reportWriteError(n);
                        running = false;
                        break;
                    }
                    if (n == 0) {
                        // 不该发生（阻塞写要么写完要么报错）：绝不自旋
                        break;
                    }
                    offset += n;
                    if (!firstWriteDone) {
                        firstWriteDone = true;
                        // **只打一次**，而且是「写出去第一块之后」才打：这一行把
                        // 「写线程活着、设备在收」与「写线程卡在 write 里、设备一个字节都不收」
                        // 分开——真机排查时这是唯一能区分的证据。打完就再也不进这个分支，
                        // 所以音频回调那条「不打印、不分配」的约定在稳定态下没有被破坏。
                        Logger.info("[qplayer-output] 首次写入设备成功（{} 字节）", Integer.valueOf(n));
                    }
                }
            }
        } catch (Throwable t) {
            if (running) {
                // 上游在 cpal 的错误回调里 eprintln：回调线程里不能 panic，也不该频繁打印
                Logger.error("[qplayer-output] 音频流错误: {}", t.toString());
            }
        } finally {
            running = false;
        }
    }

    /**
     * 写失败的诊断（只报一次）。
     *
     * <p>最常见的两个：{@code ERROR_DEAD_OBJECT}（音频服务重启/被系统杀掉，
     * 这条路只能重建流，我们这里如实停掉而不是假装还在放）与
     * {@code ERROR_INVALID_OPERATION}（状态被外力改了）。
     */
    private void reportWriteError(int code) {
        if (writeFailed) {
            return;
        }
        writeFailed = true;
        String why;
        if (code == AudioTrack.ERROR_DEAD_OBJECT) {
            why = "音频服务已断开（ERROR_DEAD_OBJECT）";
        } else if (code == AudioTrack.ERROR_BAD_VALUE) {
            why = "参数非法（ERROR_BAD_VALUE）";
        } else if (code == AudioTrack.ERROR_INVALID_OPERATION) {
            why = "状态非法（ERROR_INVALID_OPERATION）";
        } else {
            why = "未知错误码 " + code;
        }
        Logger.error("[qplayer-output] AudioTrack 写入失败：{}", why);
        java.util.function.Consumer<String> cb = onConfigRejected;
        if (cb != null) {
            try {
                cb.accept(why);
            } catch (RuntimeException e) {
                // 回报设备失败不该盖掉真正的写入失败（它只影响下一次挑哪一档）
                Logger.warn("[qplayer-output] 回报「这档写不进去」时出错：{}", String.valueOf(e));
            }
        }
    }

    /** 填满整块设备缓冲。读空了补静音，绝不「少写几个采样」。 */
    private void fillOnce() {
        if (wireBytes == format.bytes()) {
            // F32 / I16 / I32：线路打包与 player-core 的写法一致，直接用回调体
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
            AndroidAudioFormats.pack24Packed(scratch, n, buffer, offset * 3);
            offset += n;
        }
    }

    @Override
    public boolean isRunning() {
        if (!running) {
            return false;
        }
        try {
            return track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 停止并释放底层资源。可重复调用。
     *
     * <p>{@code pause()} + {@code flush()} 会把阻塞在 {@code write} 里的写线程放出来——
     * 不这么做，{@code close()} 会卡在 join 上（桌面端是同一条理由，那边用
     * {@code line.stop()/flush()}）。
     */
    @Override
    public void close() {
        running = false;
        Thread t = writer;
        writer = null;
        try {
            track.pause();
            track.flush();
        } catch (RuntimeException ignored) {
            // 已经停了 / 驱动抽风：继续往下关
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            track.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            track.release();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public String toString() {
        return "AndroidOutputStream{" + format + " " + channels + "ch, wire=" + wireBytes
                + " 字节/采样, running=" + isRunning() + '}';
    }
}
