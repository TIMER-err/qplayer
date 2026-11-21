package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 测试用的 16 bit PCM WAV：<b>写盘</b>与「最小解码器替身」。
 *
 * <p>本包原先有 <b>5 份</b>各写各的私有替身（{@code DirectorTest} / {@code DeckTest} /
 * {@code AiMixTest} / {@code VocalSeparationTransitionTest} / {@code VocalSeparationDspEndToEndTest}），
 * 各自只服务一个类。这里抽出第 1 份共享版，给新判据用；老的那几份暂时不动
 * （它们各自带探针记账之类的东西，一次全换是另一件事）。
 *
 * <p>产品路径上打不开音源会抛 {@link AudioException}，这里对不存在的文件给同样的行为——
 * 「打不开要跳过」这类判据靠的就是这一点。
 */
final class TestWavFile {

    private TestWavFile() {
    }

    /** 装一个只认 16 bit PCM WAV 的解码器（测试进程全局，用完要还原）。 */
    static Decoder.Opener opener() {
        return new Decoder.Opener() {
            @Override
            public Decoder open(String source, Map<String, String> headers) {
                return WavDecoder.open(source);
            }
        };
    }

    /**
     * 写一个 16 bit PCM 双声道 WAV。
     *
     * <p>{@code left}/{@code right} 长度必须一样；采样值按「向零截断 × 32767」量化，
     * 与产品里那条链的写法一致，免得判据量到的幅度差其实来自量化方式。
     */
    static Path write(Path path, float[] left, float[] right, int rate) throws IOException {
        if (left.length != right.length) {
            throw new IllegalArgumentException("左右声道帧数不同");
        }
        int frames = left.length;
        int dataLen = frames * 2 * 2;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(36 + dataLen);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) 2);
        buf.putInt(rate);
        buf.putInt(rate * 2 * 2);
        buf.putShort((short) 4);
        buf.putShort((short) 16);
        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(dataLen);
        for (int i = 0; i < frames; i++) {
            buf.putShort(quantize(left[i]));
            buf.putShort(quantize(right[i]));
        }
        Files.write(path, buf.array());
        return path;
    }

    private static short quantize(float s) {
        return (short) (Math.max(-1.0f, Math.min(1.0f, s)) * 32767.0f);
    }

    /** 只认 16 bit PCM WAV 的最小解码器替身（测试用，不是产品代码）。 */
    private static final class WavDecoder implements Decoder {

        private final byte[] file;
        private final int dataOff;
        private final int dataLen;
        private final int rate;
        private final int channels;
        private final int bytesPerSample;
        private final int frames;
        private int pos;

        private WavDecoder(byte[] file, int dataOff, int dataLen, int rate, int channels,
                           int bytesPerSample) {
            this.file = file;
            this.dataOff = dataOff;
            this.dataLen = dataLen;
            this.rate = rate;
            this.channels = channels;
            this.bytesPerSample = bytesPerSample;
            this.frames = dataLen / (channels * bytesPerSample);
        }

        static WavDecoder open(String source) {
            Path path;
            try {
                path = Paths.get(source);
            } catch (InvalidPathException e) {
                throw AudioException.config("打不开音源：" + source);
            }
            if (!Files.isReadable(path)) {
                throw AudioException.config("音源不存在或不可读：" + source);
            }
            byte[] all;
            try {
                all = Files.readAllBytes(path);
            } catch (IOException e) {
                throw AudioException.io("读不出音源：" + source, e);
            }
            if (all.length < 44 || !tag(all, 0, "RIFF") || !tag(all, 8, "WAVE")) {
                throw AudioException.unsupportedFormat("测试替身只认 RIFF/WAVE：" + source);
            }
            int rate = 0;
            int channels = 0;
            int bits = 0;
            int dataOff = -1;
            int dataLen = 0;
            int p = 12;
            while (p + 8 <= all.length) {
                int size = le32(all, p + 4);
                int body = p + 8;
                if (tag(all, p, "fmt ")) {
                    channels = le16(all, body + 2);
                    rate = le32(all, body + 4);
                    bits = le16(all, body + 14);
                } else if (tag(all, p, "data")) {
                    dataOff = body;
                    dataLen = Math.min(size, all.length - body);
                }
                p = body + size + (size & 1);
            }
            if (dataOff < 0 || channels <= 0 || bits != 16) {
                throw AudioException.unsupportedFormat("测试替身只认 16 bit PCM：" + source);
            }
            return new WavDecoder(all, dataOff, dataLen, rate, channels, bits / 8);
        }

        private static boolean tag(byte[] b, int off, String s) {
            for (int i = 0; i < s.length(); i++) {
                if (b[off + i] != (byte) s.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        private static int le16(byte[] b, int off) {
            return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
        }

        private static int le32(byte[] b, int off) {
            return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                    | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
        }

        private float sample(int frame, int ch) {
            int c = Math.min(ch, channels - 1);
            int off = dataOff + (frame * channels + c) * bytesPerSample;
            short raw = (short) ((file[off] & 0xFF) | (file[off + 1] << 8));
            return (float) raw / 32768.0f;
        }

        @Override
        public TrackSpec spec() {
            return new TrackSpec("wav", rate, ChannelLayout.of(channels),
                    Integer.valueOf(16), Long.valueOf(frames), true);
        }

        @Override
        public long framesDecoded() {
            return pos;
        }

        @Override
        public boolean isDrained() {
            return pos >= frames;
        }

        @Override
        public double seek(double secs) {
            long want = (long) (secs * (double) rate);
            pos = (int) Math.max(0L, Math.min(want, frames));
            return (double) pos / (double) rate;
        }

        @Override
        public int decodeInto(AudioBuf out) {
            int n = Math.min(out.capacityFrames(), frames - pos);
            if (n <= 0) {
                out.setFrames(0);
                return 0;
            }
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < out.channels(); c++) {
                    out.channelWriter(c).put(i, sample(pos + i, c));
                }
            }
            out.setFrames(n);
            pos += n;
            return n;
        }

        @Override
        public void close() {
            // 数据已经在内存里，没有句柄要放。
        }
    }
}
