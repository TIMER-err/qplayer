package dev.t1m3.qplayer.desktop.audio.decode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.desktop.audio.SeekableByteSource;

import static org.lwjgl.stb.STBVorbis.stb_vorbis_close;
import static org.lwjgl.stb.STBVorbis.stb_vorbis_get_info;
import static org.lwjgl.stb.STBVorbis.stb_vorbis_get_samples_float_interleaved;
import static org.lwjgl.stb.STBVorbis.stb_vorbis_open_memory;
import static org.lwjgl.stb.STBVorbis.stb_vorbis_seek;
import static org.lwjgl.stb.STBVorbis.stb_vorbis_stream_length_in_samples;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Ogg/Vorbis 适配器，底座是 LWJGL 的 stb_vorbis（与旧 {@code OggPcmSource} 同一底座）。
 *
 * <h2>为什么整个载荷进内存</h2>
 *
 * <p>stb_vorbis 只有「从一整块内存打开」这一个入口，没有增量喂数据的接口，所以压缩载荷
 * 必须整体读进本地内存（{@code readAll()}）。对网络源意味着<strong>要等下载完</strong>才能开始
 * 出声——这是底座能力决定的已知代价，上游 symphonia 是流式的，这一点我们做不到，
 * 报告里照实记了。定位则是 O(1) 级别的 {@code stb_vorbis_seek}，不用重解。
 *
 * <h2>无缝播放</h2>
 *
 * <p>Vorbis 没有 MP3 那种需要补偿的编码器延迟：流的有效区间由最后一页的 granulepos 决定，
 * stb_vorbis 自己就按它收尾（{@code stb_vorbis_stream_length_in_samples} 报的正是这个长度），
 * 所以这里不需要额外裁剪——上游「gapless 默认开启」在 Vorbis 上是底座天然满足的。
 *
 * <p>采样率/声道数上限：stb_vorbis 的交错输出按约定只支持 1/2 声道，多声道文件报不支持，
 * 而不是吐出一个下游接不住的布局。
 */
final class OggCodecAdapter implements CodecAdapter {

    /** 每次取一块：Vorbis 的包通常几百到几千采样，块大小不影响正确性。 */
    private static final int BLOCK_FRAMES = 4096;

    private final SeekableByteSource src;
    private final ByteBuffer data;
    private final long handle;
    private final float[] scratch;
    private final TrackSpec spec;
    private final int channels;
    private final int totalSamples;

    private boolean eos;
    private boolean closed;

    OggCodecAdapter(SeekableByteSource src) {
        this.src = src;
        byte[] all;
        try {
            all = src.readAll();
        } catch (IOException e) {
            throw AudioException.io("Ogg 载荷读取失败", e);
        }
        if (all.length == 0) {
            // memAlloc(0) 在 LWJGL 里是不合法的，而且空文件本来也没什么可解。
            throw AudioException.unsupportedFormat("Ogg 载荷为空");
        }
        this.data = MemoryUtil.memAlloc(all.length);
        data.put(all).flip();

        long opened;
        int rate;
        int ch;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            opened = stb_vorbis_open_memory(data, error, null);
            if (opened == NULL) {
                int code = error.get(0);
                MemoryUtil.memFree(data);
                throw AudioException.unsupportedFormat(
                        "Ogg/Vorbis 打开失败（stb_vorbis 错误码 " + code + "）");
            }
            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            stb_vorbis_get_info(opened, info);
            rate = info.sample_rate();
            ch = info.channels();
        }
        if (ch < 1 || ch > 2 || rate <= 0) {
            stb_vorbis_close(opened);
            MemoryUtil.memFree(data);
            throw AudioException.unsupportedFormat("不支持的 Ogg/Vorbis 参数：" + rate + " Hz，" + ch + "ch");
        }
        this.handle = opened;
        this.channels = ch;
        this.totalSamples = stb_vorbis_stream_length_in_samples(handle);
        this.scratch = new float[BLOCK_FRAMES * ch];
        this.spec = new TrackSpec("vorbis", rate, ChannelLayout.of(ch), null,
                totalSamples > 0 ? Long.valueOf(totalSamples) : null,
                CodecNames.isLossless("vorbis"));
    }

    @Override
    public TrackSpec spec() {
        return spec;
    }

    @Override
    public float[] scratch() {
        return scratch;
    }

    @Override
    public int decodeNext() {
        if (eos) {
            return -1;
        }
        // 返回的是「每声道采样数」；<= 0 表示解码到头或损坏到无法继续。
        int frames = stb_vorbis_get_samples_float_interleaved(handle, channels, scratch);
        if (frames <= 0) {
            eos = true;
            return -1;
        }
        return frames;
    }

    @Override
    public long seekToFrame(long targetFrame) {
        long target = targetFrame < 0 ? 0L : targetFrame;
        if (totalSamples > 0 && target > totalSamples - 1L) {
            target = totalSamples - 1L;
        }
        eos = false;
        // stb_vorbis_seek 是精确到采样的定位（内部解码到目标页再把多余的丢掉），
        // 所以落点就是请求位置——不像关键帧容器那样只能停在包边界。
        if (!stb_vorbis_seek(handle, (int) target)) {
            // 定位失败（例如流太长溢出 int）就如实回报 0，不装作跳成功了。
            return 0L;
        }
        return target;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        stb_vorbis_close(handle);
        MemoryUtil.memFree(data);
        src.close();
    }
}
