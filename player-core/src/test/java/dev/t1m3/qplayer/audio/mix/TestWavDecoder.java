package dev.t1m3.qplayer.audio.mix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;

/** 测试共用的 16 bit PCM WAV 解码器（只给真实歌曲对拍用）。 */
final class TestWavDecoder implements Decoder {


    private final byte[] file;
    private final int dataOff;
    private final int dataLen;
    private final int rate;
    private final int channels;
    private final int bytesPerSample;
    private final int frames;
    private int pos;

    TestWavDecoder(byte[] file, int dataOff, int dataLen, int rate, int channels,
                       int bytesPerSample) {
        this.file = file;
        this.dataOff = dataOff;
        this.dataLen = dataLen;
        this.rate = rate;
        this.channels = channels;
        this.bytesPerSample = bytesPerSample;
        this.frames = dataLen / (channels * bytesPerSample);
    }

    static TestWavDecoder open(String source) {
        Path path;
        try {
            path = Paths.get(source);
        } catch (InvalidPathException e) {
            throw AudioException.config("打不开音源：" + source);
        }
        if (!Files.isReadable(path)) {
            // 对应上游 `Decoder::open` 对不存在的文件返回的那条 Err。
            throw AudioException.config("音源不存在或不可读：" + source);
        }
        byte[] all;
        try {
            all = Files.readAllBytes(path);
        } catch (IOException e) {
            throw AudioException.io("读不出音源：" + source, e);
        }
        if (all.length < 44
                || !tag(all, 0, "RIFF")
                || !tag(all, 8, "WAVE")) {
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
        return new TestWavDecoder(all, dataOff, dataLen, rate, channels, bits / 8);
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
        short raw = (short) (((file[off] & 0xFF)) | (file[off + 1] << 8));
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
