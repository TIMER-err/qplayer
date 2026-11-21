package dev.t1m3.qplayer.desktop.audio.decode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.TrackSpec;

import dev.t1m3.qplayer.desktop.audio.SeekableByteSource;

/**
 * WAV/RIFF 适配器：载荷本身就是 PCM，所以这是一层很薄的读取 + 字节偏移定位。
 *
 * <p>与旧 {@code WavPcmSource} 的两个关键差别：
 *
 * <ol>
 *   <li>输出是 f32（新解码层的通用货币），不再把 24 位「砍掉最低字节」折成 16 位——
 *       那种折法是有损的，位完美路径会被它毁掉。整数样本按 {@code v / 2^(bits-1)}
 *       归一化，全是 2 的幂，f32 精确。</li>
 *   <li>支持 {@code WAVE_FORMAT_EXTENSIBLE}（0xFFFE）与 32 位浮点：现代 24 位 WAV
 *       大多写成 EXTENSIBLE，旧实现会把它们判成不支持。</li>
 * </ol>
 *
 * <p>RIFF 里 PCM 按字节寻址，所以 {@link #seekToFrame} 能落到<em>精确</em>的请求位置
 * （不像 MP3/FLAC 只能停在帧边界），这正是上游说的「实际落点要如实回报」里最理想的那种情况。
 */
final class WavCodecAdapter implements CodecAdapter {

    /** PCM 没有包边界，按固定块读；块大小不影响正确性（上层循环会把缓冲填满）。 */
    private static final int BLOCK_FRAMES = 4096;

    private static final int FORMAT_PCM = 1;
    private static final int FORMAT_FLOAT = 3;
    private static final int FORMAT_EXTENSIBLE = 0xFFFE;

    private final SeekableByteSource src;
    private final TrackSpec spec;
    private final int channels;
    private final int bits;
    /** 1 = 整数 PCM，3 = IEEE float（EXTENSIBLE 已经被解包成这两个之一）。 */
    private final int formatTag;
    private final long dataStart;
    private final long dataLength;
    private final int frameBytes;

    private final float[] scratch;
    /** data 区内的字节偏移（源的位置始终是 {@code dataStart + cursor}）。 */
    private long cursor;
    private boolean eos;
    private byte[] in = new byte[0];

    WavCodecAdapter(SeekableByteSource src) {
        this.src = src;

        byte[] head = readAt(0, 12);
        if (head.length < 12
                || !"RIFF".equals(ascii(head, 0, 4))
                || !"WAVE".equals(ascii(head, 8, 4))) {
            throw AudioException.unsupportedFormat("不是 WAVE 文件（缺少 RIFF/WAVE 标记）");
        }

        int tag = -1;
        int ch = 0;
        int rate = 0;
        int sampleBits = 0;
        long dStart = -1;
        long dLen = 0;

        long fileSize = src.size();
        long off = 12;
        // 块序列：<id(4)><size(4)><body>，body 按字对齐（因此奇数长度要 +1）。
        while (fileSize < 0 || off + 8 <= fileSize) {
            byte[] chunk = readAt(off, 8);
            if (chunk.length < 8) {
                break;
            }
            String id = ascii(chunk, 0, 4);
            long size = u32(chunk, 4);
            long body = off + 8;
            if ("fmt ".equals(id)) {
                byte[] f = readAt(body, (int) Math.min(size, 40L));
                if (f.length >= 16) {
                    tag = u16(f, 0);
                    ch = u16(f, 2);
                    rate = (int) u32(f, 4);
                    sampleBits = u16(f, 14);
                    if (tag == FORMAT_EXTENSIBLE && f.length >= 26) {
                        // EXTENSIBLE 的前 16 字节与普通 fmt 一致，真正的格式标签藏在
                        // SubFormat GUID 的头两个字节里（GUID 剩下的部分是固定常量）。
                        int sub = u16(f, 24);
                        if (sub == FORMAT_PCM || sub == FORMAT_FLOAT) {
                            tag = sub;
                        }
                    }
                }
            } else if ("data".equals(id)) {
                dStart = body;
                dLen = size;
                break; // 音频数据从这里开始，后面的块（cue/LIST…）与解码无关
            }
            off = body + size + (size & 1L);
        }

        if (dStart < 0 || rate <= 0 || ch <= 0 || sampleBits <= 0 || tag < 0) {
            throw AudioException.unsupportedFormat("WAVE 结构不完整（缺 fmt 或 data 块）");
        }
        if (tag == FORMAT_PCM) {
            if (sampleBits != 8 && sampleBits != 16 && sampleBits != 24 && sampleBits != 32) {
                throw AudioException.unsupportedFormat("不支持的 WAVE 整数位深：" + sampleBits);
            }
        } else if (tag == FORMAT_FLOAT) {
            if (sampleBits != 32) {
                throw AudioException.unsupportedFormat("不支持的 WAVE 浮点位深：" + sampleBits);
            }
        } else {
            throw AudioException.unsupportedFormat(
                    "不支持的 WAVE 格式标签：" + tag + "（只做 PCM 与 IEEE float）");
        }
        if (ch > 8) {
            // 上游 symphonia 支持任意声道数；这里给到 8（jflac 的上限），再多就不是
            // 播放列表里的东西了。超限宁可报不支持，也不要吐出下游接不住的布局。
            throw AudioException.unsupportedFormat("WAVE 声道数过多：" + ch);
        }

        long exact = fileSize > 0 ? fileSize - dStart : -1;
        if (dLen <= 0 || dLen == 0xFFFFFFFFL) {
            // 流式写入的 WAV 常把 data 长度写成 0 或 0xFFFFFFFF：能用文件长度兜底就用。
            dLen = exact > 0 ? exact : 0;
        } else if (exact > 0 && dLen > exact) {
            dLen = exact;
        }

        this.channels = ch;
        this.bits = sampleBits;
        this.formatTag = tag;
        this.dataStart = dStart;
        this.dataLength = dLen;
        this.frameBytes = ch * (sampleBits / 8);
        this.scratch = new float[BLOCK_FRAMES * ch];

        String codec = codecName(tag, sampleBits);
        long totalFrames = dLen / frameBytes;
        this.spec = new TrackSpec(codec, rate, ChannelLayout.of(ch),
                Integer.valueOf(sampleBits), Long.valueOf(totalFrames),
                CodecNames.isLossless(codec));

        seekToFrame(0);
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
        long remainBytes = dataLength - cursor;
        long roomFrames = remainBytes / frameBytes;
        if (roomFrames <= 0) {
            // data 尾部不足一帧的残字节：不是完整样本，丢掉并收尾。
            eos = true;
            return -1;
        }
        int wantFrames = (int) Math.min(BLOCK_FRAMES, roomFrames);
        int wantBytes = wantFrames * frameBytes;
        if (in.length < wantBytes) {
            in = new byte[wantBytes];
        }
        int got = 0;
        boolean eof = false;
        int zeroReads = 0;
        while (got < wantBytes) {
            int n;
            try {
                n = src.read(in, got, wantBytes - got);
            } catch (IOException e) {
                throw AudioException.io("WAV 数据块读取失败", e);
            }
            if (n < 0) {
                eof = true;
                break;
            }
            if (n == 0) {
                // 网络源在等待下载时可能给不出来；给不出太多次就当流断了，
                // 免得音频线程在这里空转。
                if (++zeroReads > 64) {
                    eof = true;
                    break;
                }
                continue;
            }
            zeroReads = 0;
            got += n;
        }
        int frames = got / frameBytes;
        if (frames <= 0) {
            eos = true;
            return -1;
        }
        cursor += (long) frames * frameBytes;
        if (eof) {
            // 只有真的读到 -1 才算结束：网络源**短读不等于 EOF**（数据还在下载），
            // 把短读当结尾会把网络 WAV 拦腰截断。这一块仍要交付，下一次调用返回 -1。
            eos = true;
        }
        convert(frames);
        return frames;
    }

    @Override
    public long seekToFrame(long targetFrame) {
        long total = dataLength / frameBytes;
        long target = targetFrame < 0 ? 0 : Math.min(targetFrame, total);
        long byteOff = target * frameBytes;
        try {
            src.seek(dataStart + byteOff);
        } catch (IOException e) {
            throw AudioException.io("WAV 定位失败", e);
        }
        cursor = byteOff;
        eos = false;
        return target;
    }

    @Override
    public void close() {
        src.close();
    }

    /** 交织字节 → 交错 f32，按 {@code v / 2^(bits-1)} 归一化。 */
    private void convert(int frames) {
        int perSample = bits / 8;
        int samples = frames * channels;
        if (formatTag == FORMAT_FLOAT) {
            for (int i = 0; i < samples; i++) {
                int s = i * 4;
                int raw = (in[s] & 0xff) | ((in[s + 1] & 0xff) << 8)
                        | ((in[s + 2] & 0xff) << 16) | ((in[s + 3] & 0xff) << 24);
                scratch[i] = Float.intBitsToFloat(raw);
            }
            return;
        }
        for (int i = 0; i < samples; i++) {
            int s = i * perSample;
            switch (bits) {
                case 8:
                    // 8 位 WAV 是无符号的，中点 128。
                    scratch[i] = ((in[s] & 0xff) - 128) * (1f / 128f);
                    break;
                case 16:
                    scratch[i] = (short) ((in[s] & 0xff) | (in[s + 1] << 8)) * (1f / 32768f);
                    break;
                case 24: {
                    int v = (in[s] & 0xff) | ((in[s + 1] & 0xff) << 8) | ((in[s + 2] & 0xff) << 16);
                    v = (v << 8) >> 8; // 符号扩展到 32 位
                    scratch[i] = v * (1f / 8388608f);
                    break;
                }
                default: {
                    int v = (in[s] & 0xff) | ((in[s + 1] & 0xff) << 8)
                            | ((in[s + 2] & 0xff) << 16) | (in[s + 3] << 24);
                    scratch[i] = v * (1f / 2147483648f);
                    break;
                }
            }
        }
    }

    private static String codecName(int formatTag, int bits) {
        if (formatTag == FORMAT_FLOAT) {
            return "pcm_f32le";
        }
        if (bits == 8) {
            return "pcm_u8";
        }
        if (bits == 16) {
            return "pcm_s16le";
        }
        if (bits == 24) {
            return "pcm_s24le";
        }
        return "pcm_s32le";
    }

    private byte[] readAt(long pos, int len) {
        try {
            src.seek(pos);
        } catch (IOException e) {
            throw AudioException.io("WAV 头部定位失败", e);
        }
        byte[] b = new byte[len];
        int got = 0;
        while (got < len) {
            int n;
            try {
                n = src.read(b, got, len - got);
            } catch (IOException e) {
                throw AudioException.io("WAV 头部读取失败", e);
            }
            if (n < 0) {
                break;
            }
            got += n;
        }
        if (got < len) {
            byte[] trimmed = new byte[got];
            System.arraycopy(b, 0, trimmed, 0, got);
            return trimmed;
        }
        return b;
    }

    private static String ascii(byte[] b, int at, int len) {
        return new String(b, at, len, StandardCharsets.US_ASCII);
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8)
                | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }
}
