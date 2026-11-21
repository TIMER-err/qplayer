package dev.t1m3.qplayer.android.audio;

import dev.t1m3.qplayer.audio.output.OutputSampleFormat;
import dev.t1m3.qplayer.audio.output.SampleConversion;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 安卓平台的 PCM 编码常量表与转换（<strong>纯逻辑：这个文件不引用 {@code android.*} 里的任何类型</strong>）。
 *
 * <p>为什么自己写一份常量，而不是直接引 {@code android.media.AudioFormat}：
 *
 * <ol>
 *   <li>这些数值本身就是稳定的平台 ABI（PCM_16BIT=2 从 API 1 起没变过），写在这里便于
 *       和 {@link #SDK_PCM_24_32} 这类<strong>版本门槛</strong>放在一起看——「哪个编码从哪个
 *       API 起可用」是这次移植最容易踩的坑；</li>
 *   <li>不依赖 Android 运行时，所以这一层能在 JVM 单测里直接跑（{@code AndroidAudioFormatsTest}）。
 *       解码层与输出层都靠它做格式映射，映射错了是两个宿主一起错，必须有测试钉着。</li>
 * </ol>
 *
 * <p>对应的平台常量（数值逐一核对过）：
 *
 * <pre>
 * android.media.AudioFormat.ENCODING_PCM_16BIT        = 2   （API 1）
 * android.media.AudioFormat.ENCODING_PCM_FLOAT        = 4   （API 21）
 * android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED = 21  （API 31）
 * android.media.AudioFormat.ENCODING_PCM_32BIT        = 22  （API 31）
 * android.media.AudioFormat.CHANNEL_OUT_MONO          = 0x4
 * android.media.AudioFormat.CHANNEL_OUT_STEREO        = 0xc
 * </pre>
 *
 * <p>输出格式的取向与 player-core 的约定一致：<strong>优先向解码器要 float PCM</strong>
 * （内部就是交错 f32，直通无转换），拿不到才退到 16 位整数——16 位整数 → f32 是
 * <strong>精确</strong>的（只改指数，见 ADR-003 §3.2），所以退化的代价只是动态范围，
 * 不是精度损失。
 */
public final class AndroidAudioFormats {

    /** {@code AudioFormat.ENCODING_INVALID}：MediaCodec 没报编码时的取值。 */
    public static final int ENCODING_INVALID = 0;

    /**
     * {@code AudioFormat.ENCODING_PCM_8BIT}（API 1+，<strong>无符号</strong>：静音是 128）。
     *
     * <p>只出现在<strong>解码侧</strong>：8 位 WAV（{@code WAVE_FORMAT_PCM} + 8bit）就是这一档，
     * media3 的 {@code WavExtractor} 会照着报（{@code Util.getPcmEncoding(8)}）。
     * AudioTrack 没有 8 位输出，所以 {@link #isPlayable} 对它恒为 false——
     * 与 {@code U16} 那条「上游档位不是安卓档位」是同一类区分。
     */
    public static final int ENCODING_PCM_8BIT = 3;

    /** {@code AudioFormat.ENCODING_PCM_16BIT}（API 1+）。 */
    public static final int ENCODING_PCM_16BIT = 2;

    /** {@code AudioFormat.ENCODING_PCM_FLOAT}（API 21+，minSdk 26 恒可用）。 */
    public static final int ENCODING_PCM_FLOAT = 4;

    /**
     * {@code AudioFormat.ENCODING_PCM_24BIT_PACKED}（<strong>API 31+</strong>）。
     *
     * <p>3 字节紧凑打包，与 AudioTrack 一致；注意这与 {@link OutputSampleFormat#I24}
     * 的「4 字节存储、24 位有效」不是同一种打包（后者是上游/cpal 的语义），
     * 所以输出层要专门处理这个差值（见 {@code AndroidOutputStream}）。
     */
    public static final int ENCODING_PCM_24BIT_PACKED = 21;

    /** {@code AudioFormat.ENCODING_PCM_32BIT}（<strong>API 31+</strong>）。 */
    public static final int ENCODING_PCM_32BIT = 22;

    /** {@code AudioFormat.CHANNEL_OUT_MONO}。 */
    public static final int CHANNEL_OUT_MONO = 0x4;

    /** {@code AudioFormat.CHANNEL_OUT_STEREO}。 */
    public static final int CHANNEL_OUT_STEREO = 0xc;

    /** 24 位紧凑打包 / 32 位整数编码要求的最低 API（Android 12）。 */
    public static final int SDK_PCM_24_32 = 31;

    /** {@code AudioTrack.isDirectPlaybackSupported(...)} 要求的最低 API（Android 10）。 */
    public static final int SDK_DIRECT_PLAYBACK = 29;

    private AndroidAudioFormats() {
    }

    /** 每个采样的存储字节数；未知编码返回 0。 */
    public static int bytesPerSample(int encoding) {
        switch (encoding) {
            case ENCODING_PCM_8BIT:
                return 1;
            case ENCODING_PCM_16BIT:
                return 2;
            case ENCODING_PCM_FLOAT:
            case ENCODING_PCM_32BIT:
                return 4;
            case ENCODING_PCM_24BIT_PACKED:
                return 3;
            default:
                return 0;
        }
    }

    /** 一帧（全部声道）的字节数；编码未知或声道数非法时返回 0。 */
    public static int frameBytes(int encoding, int channels) {
        int bytes = bytesPerSample(encoding);
        if (bytes <= 0 || channels < 1) {
            return 0;
        }
        return bytes * channels;
    }

    /**
     * <strong>输入侧</strong>能不能解这段 PCM。
     *
     * <p>五种编码（含 8 位无符号）的转换都是纯算术，不依赖任何平台 API：即使某台 API 26 的设备
     * 意外报了 24 位紧凑打包（正常不会，因为我们向解码器要的是 float），
     * 我们也能正确解出来——这属于加分项，不需要版本门槛。
     * 版本门槛只对<strong>输出侧</strong>成立（AudioTrack 的编码表），见 {@link #isPlayable}。
     */
    public static boolean isDecodable(int encoding) {
        return bytesPerSample(encoding) > 0;
    }

    /**
     * <strong>输出侧</strong>（AudioTrack）能不能用这个编码开流。
     *
     * <p>24 位紧凑与 32 位整数是 {@link #SDK_PCM_24_32} 起才有的编码
     * （{@code ENCODING_PCM_24BIT_PACKED} / {@code ENCODING_PCM_32BIT} 是 API 31 新增），
     * 低版本上传进去只会得到一个 «编码不支持» 的开流失败或静音，所以必须在这里守住。
     *
     * @param sdkInt {@code Build.VERSION.SDK_INT}
     */
    public static boolean isPlayable(int encoding, int sdkInt) {
        switch (encoding) {
            case ENCODING_PCM_16BIT:
                return true;
            case ENCODING_PCM_FLOAT:
                // API 21+，而 minSdk 是 26，所以恒真；仍然写成判据而不是常量 true，
                // 是因为这条判断的语义是「平台版本够不够」，不是「我们支不支持」
                return sdkInt >= 21;
            case ENCODING_PCM_24BIT_PACKED:
            case ENCODING_PCM_32BIT:
                return sdkInt >= SDK_PCM_24_32;
            default:
                return false;
        }
    }

    /**
     * 平台编码 → 我们的采样格式；不支持的返回 {@code null}（调用方跳过而不是报错，
     * 与上游 {@code from_cpal} 的用法一致）。
     *
     * @param sdkInt {@code Build.VERSION.SDK_INT}
     */
    public static OutputSampleFormat toOutputSampleFormat(int encoding, int sdkInt) {
        if (!isPlayable(encoding, sdkInt)) {
            return null;
        }
        switch (encoding) {
            case ENCODING_PCM_16BIT:
                return OutputSampleFormat.I16;
            case ENCODING_PCM_FLOAT:
                return OutputSampleFormat.F32;
            case ENCODING_PCM_24BIT_PACKED:
                return OutputSampleFormat.I24;
            case ENCODING_PCM_32BIT:
                return OutputSampleFormat.I32;
            default:
                return null;
        }
    }

    /**
     * 我们的采样格式 → AudioTrack 的编码；不支持时返回 {@link #ENCODING_INVALID}。
     *
     * <p>注意两处<strong>刻意的不支持</strong>：
     *
     * <ul>
     *   <li>{@link OutputSampleFormat#U16}：AudioTrack 没有无符号 16 位 PCM
     *       （那是 cpal 的档位，不是安卓的）</li>
     *   <li>{@code I24} / {@code I32} 在 {@link #SDK_PCM_24_32} 以下：如实报不支持，
     *       不退化成 16 位——输出层不许静默降级</li>
     * </ul>
     *
     * @param sdkInt {@code Build.VERSION.SDK_INT}
     */
    public static int fromOutputSampleFormat(OutputSampleFormat fmt, int sdkInt) {
        if (fmt == null) {
            return ENCODING_INVALID;
        }
        switch (fmt) {
            case I16:
                return ENCODING_PCM_16BIT;
            case F32:
                return sdkInt >= 21 ? ENCODING_PCM_FLOAT : ENCODING_INVALID;
            case I24:
                return sdkInt >= SDK_PCM_24_32 ? ENCODING_PCM_24BIT_PACKED : ENCODING_INVALID;
            case I32:
                return sdkInt >= SDK_PCM_24_32 ? ENCODING_PCM_32BIT : ENCODING_INVALID;
            default:
                // U16：AudioTrack 没有这一档
                return ENCODING_INVALID;
        }
    }

    /**
     * 声道数 → {@code CHANNEL_OUT_*}；不支持时返回 {@code -1}。
     *
     * <p>只开单声道与立体声：本播放器的引擎链路就是立体声（混音/过渡都按 2 声道算），
     * 环绕声需要引擎侧先接通声道映射。这里<strong>不虚报能力</strong>——
     * 探到 5.1 却放不出 5.1 比探不到更糟。
     */
    public static int channelMask(int channels) {
        switch (channels) {
            case 1:
                return CHANNEL_OUT_MONO;
            case 2:
                return CHANNEL_OUT_STEREO;
            default:
                return -1;
        }
    }

    /**
     * 缓冲字节数向上取整到<strong>整帧</strong>。
     *
     * <p>理由：{@code AudioTrack} 的缓冲长度是「帧」的整数倍才合法，而
     * {@code getMinBufferSize} 与「固定缓冲帧数」两条来源都可能给出半帧长度
     * （24 位紧凑是 3 字节/采样，最容易撞上）。半帧的缓冲在某些设备上直接判参数非法，
     * 症状是「开流失败」，而根因只是差那几个字节。
     */
    public static int roundUpToFrame(int bytes, int frameBytes) {
        if (frameBytes <= 0 || bytes <= 0) {
            return bytes;
        }
        return ((bytes + frameBytes - 1) / frameBytes) * frameBytes;
    }

    /**
     * 把一段 PCM（来自 {@code MediaCodec} 的输出缓冲）转成<strong>交错 f32</strong>。
     *
     * <p>写完 {@code dst} 的前 {@code frames × channels} 个采样，并把 {@code src} 的
     * position 推进到消费完的位置。调用方（解码器）负责把这些采样暂存起来，
     * 再按平面布局交付给 {@code AudioBuf}。
     *
     * <h3>字节序：不设一次 order 就会全部读反</h3>
     *
     * <p>{@code MediaCodec.getOutputBuffer(...)} 给回来的直接缓冲默认是
     * <strong>BIG_ENDIAN</strong>（JNI 造直接缓冲的默认值），而安卓的所有 ABI 都是小端、
     * PCM 也是小端。Google 自己的 MediaCodec float 示例同样先
     * {@code order(ByteOrder.nativeOrder())} 再取 sample——这一步漏掉的表现是
     * 「声音变成噪音」，而且只在 16 位以上才明显。这里显式写死 LITTLE_ENDIAN
     * （安卓支持的 ABI 全是小端），不依赖 {@code nativeOrder()} 的运行时取值。
     *
     * @param src        源 PCM，position 处开始
     * @param dst        目标交错 f32
     * @param encoding   平台编码（{@link #isDecodable} 为真的那几种）
     * @param channels   声道数
     * @param maxFrames  最多转换多少帧
     * @return 实际转换的帧数
     */
    public static int readPcm(ByteBuffer src, float[] dst, int encoding, int channels, int maxFrames) {
        return readPcm(src, dst, 0, encoding, channels, maxFrames);
    }

    /**
     * 同 {@link #readPcm(ByteBuffer, float[], int, int, int)}，但从 {@code dstOffset} 个采样处开始写。
     *
     * <p>为什么需要它：一次解码输出里可能<strong>接续着上一包留下的半个采样</strong>
     * （见 {@code PcmFramer}），于是同一次读取要分两段写进同一个目标缓冲。
     * 没有下标的版本会从 {@code dst[0]} 重写，把前一段悄悄盖掉——
     * 表现是「少了一帧、那一帧是旧数据」，听起来是杂音。
     *
     * @param dstOffset 目标缓冲里的起始<strong>采样</strong>下标（不是帧）
     */
    public static int readPcm(ByteBuffer src, float[] dst, int dstOffset, int encoding,
                              int channels, int maxFrames) {
        int frameBytes = frameBytes(encoding, channels);
        if (frameBytes <= 0) {
            throw new IllegalArgumentException("不认识的 PCM 编码：" + encoding);
        }
        if (maxFrames <= 0) {
            return 0;
        }
        src.order(ByteOrder.LITTLE_ENDIAN);
        int frames = Math.min(maxFrames, src.remaining() / frameBytes);
        int samples = frames * channels;
        for (int i = 0; i < samples; i++) {
            dst[dstOffset + i] = readSample(src, encoding);
        }
        return frames;
    }

    /** 单个采样：平台编码 → f32。 */
    private static float readSample(ByteBuffer src, int encoding) {
        switch (encoding) {
            case ENCODING_PCM_8BIT: {
                // 8 位 WAV 是**无符号**：128 是静音，0 → −1.0、255 → +127/128。
                // 除以 128（而不是 127）与 16 位那条理由一致：只改指数、映射精确
                int x = src.get() & 0xFF;
                return (x - 128) / 128.0f;
            }
            case ENCODING_PCM_16BIT: {
                short s = src.getShort();
                // 除以 2^15 = 32768 而不是 32767：只有 32768 能让 -32768 精确映射到 -1.0，
                // 而且除以 2 的幂只改指数，f32 与 int16 之间是**精确**的（ADR-003 §3.2）
                return s / 32768.0f;
            }
            case ENCODING_PCM_FLOAT:
                // 内部格式直通：位模式原样搬进 f32，不做任何加工
                return Float.intBitsToFloat(src.getInt());
            case ENCODING_PCM_24BIT_PACKED: {
                int b0 = src.get() & 0xFF;
                int b1 = src.get() & 0xFF;
                int b2 = src.get() & 0xFF;
                int x = b0 | (b1 << 8) | (b2 << 16);
                if ((x & 0x800000) != 0) {
                    x |= 0xFF000000; // 符号扩展（3 字节没有符号位，必须自己补）
                }
                return x / 8388608.0f; // 2^23
            }
            case ENCODING_PCM_32BIT: {
                int x = src.getInt();
                return x / 2147483648.0f; // 2^31
            }
            default:
                throw new IllegalArgumentException("不认识的 PCM 编码：" + encoding);
        }
    }

    /**
     * 把交错 f32 打包成 AudioTrack 的 <strong>24 位紧凑</strong>格式（3 字节/采样，小端）。
     *
     * <p>为什么需要单独一个函数：{@link OutputSampleFormat#writeSample} 对 I24 写的是
     * <strong>4 字节</strong>（上游语义「4 字节存储、24 位有效」），而 AudioTrack 的
     * {@link #ENCODING_PCM_24BIT_PACKED} 是 3 字节紧凑。桌面端 {@code JavaSoundOutputStream}
     * 对 javax.sound 的 3 字节 24 位 PCM 也做了同样的事——两个宿主在各自平台的
     * 「线路打包」上分叉，打包规则不改 {@code OutputSampleFormat} 的语义。
     *
     * <p>取整/限幅走 {@link SampleConversion#f32ToI24}：与上游逐样本一致
     * （包括 .5 远离零的取整差异），不自己另写一套。
     *
     * @param src       交错 f32
     * @param samples   要打包的采样数（不是帧数）
     * @param dst       目标字节数组
     * @param dstOffset 起始字节位置
     */
    public static void pack24Packed(float[] src, int samples, byte[] dst, int dstOffset) {
        int p = dstOffset;
        for (int i = 0; i < samples; i++) {
            int x = SampleConversion.f32ToI24(src[i]);
            dst[p] = (byte) x;
            dst[p + 1] = (byte) (x >> 8);
            dst[p + 2] = (byte) (x >> 16);
            p += 3;
        }
    }
}
