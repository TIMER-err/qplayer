package dev.t1m3.qplayer.desktop.audio.decode;

import dev.t1m3.qplayer.audio.decode.TrackSpec;

/**
 * 单个格式的解码适配器：上游 {@code sonalis_decode::Decoder} 里
 * 「{@code format.next_packet()} + {@code decoder.decode(&packet)} +
 * {@code scratch.copy_interleaved_ref(decoded)}」这三步的宿主实现。
 *
 * <p>上游底座是 symphonia：一个库同时提供容器读取器与解码器，所以那三步写在
 * {@code Decoder} 本体里。QPlayer 的桌面端是一堆互不相干的库（jflac / jlayer /
 * stb_vorbis / 自家 RIFF 读取），每个格式取包与解码的方式都不同——把它们收进
 * 这一个接口，{@link DesktopDecoder} 就能只保留上游真正有价值的那部分：
 * <strong>暂存（pending）交付语义、seek 作废暂存、坏包跳过、drained 状态</strong>。
 *
 * <p>约定（与上游的循环一一对应，实现在 {@link DesktopDecoder#fillPending()}）：
 *
 * <ul>
 *   <li>{@link #decodeNext()} 返回 <strong>-1</strong> ↔ 上游 {@code next_packet()}
 *       报 {@code UnexpectedEof}：流结束</li>
 *   <li>返回 <strong>0</strong> ↔ 上游 {@code Err(DecodeError) => continue}：
 *       这一包没有产出（空包、坏包被跳过、或整包都被 gapless 裁剪掉了），
 *       调用方应当继续取下一包，<strong>绝不能当成流结束</strong></li>
 *   <li>抛 {@link dev.t1m3.qplayer.audio.core.AudioException} ↔ 上游其它
 *       {@code Err(e) => return Err(...)}：真正致命，整首歌停掉</li>
 * </ul>
 *
 * <p>本接口与实现都是包内可见的：外部只该看到 player-core 的
 * {@link dev.t1m3.qplayer.audio.decode.Decoder}。
 *
 * <p>所有权约定：适配器接管传入的字节源，只在自己的 {@link #close()} 里关闭它；
 * <strong>构造失败时不要关</strong>——那时对象还没交出去，由
 * {@link DesktopDecoder#open} 负责收尾（上游那版 {@code PcmSource.open} 就是这么分工的）。
 */
interface CodecAdapter extends AutoCloseable {

    /** 曲目事实。 */
    TrackSpec spec();

    /**
     * 上一包解出的<strong>交错</strong> f32 暂存，长度至少
     * {@code spec().layout.count() × decodeNext() 的返回值}。
     *
     * <p>为什么是交错：上游的 {@code SampleBuffer::copy_interleaved_ref} 交出来的就是交错，
     * pending 也就按交错存。平面化只在最后交付那一步做（见
     * {@link DesktopDecoder#decodeInto}）。
     */
    float[] scratch();

    /** 解码下一包进 {@link #scratch()}。返回帧数；-1 = 流结束；0 = 这一包没有产出。 */
    int decodeNext();

    /**
     * 跳到第 {@code targetFrame} 帧，返回<strong>实际</strong>落到的帧。
     *
     * <p>上游对应 {@code format.seek(SeekMode::Accurate, SeekTo::Time{..})} +
     * {@code seeked.actual_ts}：容器往往只能停在关键帧/包边界上，落点常常略早于
     * 请求位置，必须如实回报，否则界面进度条会在松手后自己跳回去。
     */
    long seekToFrame(long targetFrame);

    @Override
    void close();
}
