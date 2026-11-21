package dev.t1m3.qplayer.desktop.audio.decode;

import java.io.IOException;

import org.jflac.ChannelData;
import org.jflac.FLACDecoder;
import org.jflac.frame.Frame;
import org.jflac.metadata.StreamInfo;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.TrackSpec;

import dev.t1m3.qplayer.desktop.audio.SeekableByteSource;
import dev.t1m3.qplayer.desktop.audio.SeekableRandomFileInputStream;

/**
 * FLAC 适配器，底座是 jflac（纯 Java，无本地依赖）。
 *
 * <p>两个要点：
 *
 * <ol>
 *   <li><strong>不走 jflac 的交错字节输出</strong>：{@code decodeFrame(frame, ByteData)}
 *       吐的是按流的位深打包的字节，旧实现为了塞进 16 位 OpenAL 缓冲把 24 位砍成
 *       16 位。新层要 f32，所以直接读 {@code decoder.getChannelData()[c].getOutput()}
 *       的 int 样本，按 {@code v / 2^(bits-1)} 归一化——24/32 位都是无损转换。</li>
 *   <li><strong>jflac 的 seek 需要 {@code RandomFileInputStream}</strong>，这里用
 *       {@link SeekableRandomFileInputStream} 把读写接到字节源上，于是网络 FLAC
 *       能边下边解，而不是先把整个文件等完。它的 seek 做的是「帧边界二分 + 回退」，
 *       落点是<em>包含</em>目标位置那一帧的帧首，通常略早于请求位置——如实返回即可。</li>
 * </ol>
 *
 * <p>坏包：jflac 的 {@code readNextFrame()} 内部已经对 {@code FrameDecodeException}
 * 做了跳过（只在 CRC 校验失败时把该帧清零而不是丢弃），所以这层不再自己兜一遍；
 * 与上游「DecodeError 就跳到下一包」的效果一致。
 */
final class FlacCodecAdapter implements CodecAdapter {

    /** 一帧最多 65535 个采样（FLAC 的 blockSize 上限是 16 位），给到 64K 足够。 */
    private static final int MAX_FRAME_SAMPLES = 65536;

    private final SeekableByteSource src;
    private final SeekableRandomFileInputStream rfis;
    private final FLACDecoder decoder;
    private final TrackSpec spec;
    private final int channels;
    private final long totalSamples;
    private final float[] scratch;
    private final float scale;

    private boolean eos;

    FlacCodecAdapter(SeekableByteSource src) {
        this.src = src;
        try {
            this.rfis = new SeekableRandomFileInputStream(src);
        } catch (IOException e) {
            throw AudioException.io("FLAC 字节层初始化失败", e);
        }
        this.decoder = new FLACDecoder(rfis);
        try {
            decoder.readMetadata();
        } catch (IOException e) {
            // 上游把 probe 阶段的任何失败都归到 UnsupportedFormat；这里保持一致，
            // 但把原因串在消息后面，免得只剩一句「打不开」。
            throw AudioException.unsupportedFormat("FLAC 元数据读取失败：" + e.getMessage());
        }
        StreamInfo info = decoder.getStreamInfo();
        if (info == null) {
            throw AudioException.unsupportedFormat("FLAC 缺少 STREAMINFO（不是 FLAC 文件？）");
        }
        int ch = info.getChannels();
        int rate = info.getSampleRate();
        int bps = info.getBitsPerSample();
        if (ch < 1 || ch > 8) {
            throw AudioException.unsupportedFormat("FLAC 声道数不支持：" + ch);
        }
        if (bps < 4 || bps > 32) {
            throw AudioException.unsupportedFormat("FLAC 位深不支持：" + bps);
        }
        if (rate <= 0) {
            throw AudioException.unsupportedFormat("FLAC 采样率非法：" + rate);
        }
        this.channels = ch;
        this.totalSamples = info.getTotalSamples();
        this.scratch = new float[MAX_FRAME_SAMPLES * ch];
        this.scale = 1f / (float) (1L << (bps - 1));
        this.spec = new TrackSpec("flac", rate, ChannelLayout.of(ch),
                Integer.valueOf(bps),
                totalSamples > 0 ? Long.valueOf(totalSamples) : null,
                CodecNames.isLossless("flac"));
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
        while (true) {
            Frame frame;
            try {
                frame = decoder.readNextFrame();
            } catch (IOException e) {
                throw AudioException.io("FLAC 帧读取失败", e);
            }
            if (frame == null) {
                eos = true;
                return -1;
            }
            int frames = frame.header.blockSize;
            if (frames <= 0) {
                continue; // 空帧：跳过，绝不能当成流结束（上游同样是 continue）
            }
            ChannelData[] data = decoder.getChannelData();
            if (data == null || data.length < channels) {
                continue;
            }
            boolean complete = true;
            for (int c = 0; c < channels; c++) {
                if (data[c] == null) {
                    complete = false;
                    break;
                }
            }
            if (!complete) {
                continue;
            }
            for (int c = 0; c < channels; c++) {
                int[] samples = data[c].getOutput();
                for (int i = 0; i < frames; i++) {
                    scratch[i * channels + c] = samples[i] * scale;
                }
            }
            return frames;
        }
    }

    @Override
    public long seekToFrame(long targetFrame) {
        if (totalSamples <= 0) {
            // STREAMINFO 没写总长度（流式输入常见）：jflac 的 seek 会直接抛异常，
            // 这里如实退回起点（返回 0），而不是假装跳成功了。
            eos = false;
            return 0L;
        }
        long target = targetFrame < 0 ? 0L : Math.min(targetFrame, totalSamples - 1);
        long landed;
        try {
            landed = decoder.seek(target);
        } catch (IOException e) {
            throw AudioException.io("FLAC 定位失败", e);
        } catch (RuntimeException e) {
            // jflac 对越界目标抛 IllegalArgumentException；定位失败在上游也是 Decode 类错误。
            throw AudioException.decode("FLAC 定位失败：" + e.getMessage());
        }
        eos = false;
        return landed;
    }

    @Override
    public void close() {
        try {
            rfis.close();
        } catch (IOException ignored) {
            // 关闭失败没有补救动作，字节源下面还会再关一次。
        }
        src.close();
    }
}
