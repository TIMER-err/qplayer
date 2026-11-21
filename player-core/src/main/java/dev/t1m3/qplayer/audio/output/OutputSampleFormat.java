package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.BitDepth;

/**
 * 采样格式（我们自己的，不把平台类型泄漏到外层）。
 *
 * <p>对应 Sonalis 的 {@code sonalis_output::OutputSampleFormat}。上游是 Rust enum，
 * 这里是一一对应的 Java enum，变体名保持 {@code UPPER_SNAKE}。
 *
 * <h3>{@link #I24} 是 HiFi 的主力规格</h3>
 *
 * 「4 字节存储、24 位有效」——CD 之上最普遍的一档，也是多数 USB DAC 与外置解码器的
 * 原生工作位深。上游专门写明了这一点，且在 A1 缺陷里指出「字段实现了但没有调用方
 * 接得上」等于没有，所以整条 I24 路径（{@code withBitDepth} → 打分 → 开流 → 打包）
 * 都必须接通并有测试。
 *
 * <h3>为什么这里没有 F64</h3>
 *
 * 上游的 {@code from_cpal} 对 {@code cpal::SampleFormat::F64} 返回错误
 * （测试 {@code unsupported_sample_format_is_reported_not_panicked}），
 * 所以 F64 属于「设备会报、我们不开」的那一类，被放在 {@link DeviceSampleFormat} 里。
 * 把它塞进本枚举会让那条上游测试失去意义（映射成功 = 能开流 = 可以 dispatch 到写入路径）。
 */
public enum OutputSampleFormat {

    /** 32 位浮点，桌面上最常见，内部格式直通无转换。 */
    F32,
    /** 16 位有符号整数（CD 规格）。 */
    I16,
    /** 16 位无符号整数。 */
    U16,
    /** 24 位有符号整数，4 字节存储、24 位有效（HiFi 主力规格）。 */
    I24,
    /** 32 位有符号整数。 */
    I32;

    /**
     * 每个采样的存储字节数。
     *
     * <p>注意 {@link #I24} 是 <strong>4</strong> 字节：上游明确要求「4 字节存储、24 位有效」。
     * Java Sound 那边的 24 位 PCM 惯例是 3 字节紧凑打包（{@code frameSize == 3 × channels}），
     * 那是平台自己的规矩，由 desktop-host 的写入路径按线路实际情况处理，
     * 不改这里的语义。
     */
    public int bytes() {
        switch (this) {
            case I16:
            case U16:
                return 2;
            case I24:
                // 4 字节存储、24 位有效
                return 4;
            default:
                // F32 / I32
                return 4;
        }
    }

    /** 是否与内部 f32 格式一致（直通、不做转换）。 */
    public boolean isNative() {
        return this == F32;
    }

    /** 有效位数。 */
    public BitDepth bitDepth() {
        switch (this) {
            case I16:
            case U16:
                return BitDepth.INT16;
            case I24:
                return BitDepth.INT24;
            case I32:
                return BitDepth.INT32;
            default:
                return BitDepth.FLOAT32;
        }
    }

    /**
     * 是否<strong>精确</strong>对应某个位深。
     *
     * <p>上游特意加了「同宽度不算匹配」这一层：I32 与 F32 都是 32 位宽，
     * 但绝不能互相匹配——整数与浮点是两种不同的表示，混起来就是位完美判定失效。
     * 唯一的例外是 I16 与 U16 都算 Int16（同一精度、只是偏移不同）。
     */
    public boolean matchesBitDepth(BitDepth d) {
        if (d == null) {
            return false;
        }
        if (bitDepth().bits() != d.bits()) {
            return false;
        }
        switch (this) {
            case F32:
                return d == BitDepth.FLOAT32;
            case I32:
                return d == BitDepth.INT32;
            case I24:
                return d == BitDepth.INT24;
            case I16:
            case U16:
                return d == BitDepth.INT16;
            default:
                return false;
        }
    }

    /**
     * 设备报的格式我们能不能开流。
     *
     * <p>对应上游在 {@code capabilities_of} / {@code choose_config} 里的
     * {@code OutputSampleFormat::from_cpal(r.sample_format()).is_ok()} 分支：
     * <strong>不能开就跳过，不报错</strong>。
     */
    public static boolean isSupported(DeviceSampleFormat f) {
        switch (f) {
            case F32:
            case I16:
            case U16:
            case I24:
            case I32:
                return true;
            default:
                // F64 / U8 / DSD_* / OTHER：设备声明的格式暂不支持
                return false;
        }
    }

    /**
     * 设备格式 → 我们的格式。
     *
     * <p>对应上游 {@code OutputSampleFormat::from_cpal}（返回 {@code Result}）：
     * 这里抛 {@link AudioException}（{@code Kind.OUTPUT}），
     * 消息也保留上游原文「设备要求的采样格式暂不支持：…」。
     *
     * @throws AudioException 设备格式不在我们的可开流集合里
     */
    public static OutputSampleFormat fromDevice(DeviceSampleFormat f) {
        if (f == null) {
            throw AudioException.output("设备没有报告采样格式");
        }
        switch (f) {
            case F32:
                return F32;
            case I16:
                return I16;
            case U16:
                return U16;
            case I24:
                return I24;
            case I32:
                return I32;
            default:
                throw AudioException.output("设备要求的采样格式暂不支持：" + f);
        }
    }

    /**
     * 把一个 f32 采样按本格式写进字节流（<strong>小端</strong>）。
     *
     * <p>写入前一律经 {@link SampleConversion#clampUnit(float)} 夹紧，理由见该类注释。
     * 小端是 Windows/Linux 桌面 Java Sound 的 {@code AudioFormat} 默认约定，
     * 大端档位在能力枚举阶段就被跳过了。
     *
     * @param v      f32 采样，超出 [-1, 1] 会被夹住
     * @param dst    目标字节数组
     * @param offset 写入位置（按 {@link #bytes()} 递增）
     */
    public void writeSample(float v, byte[] dst, int offset) {
        switch (this) {
            case F32: {
                int bits = Float.floatToIntBits(v);
                dst[offset] = (byte) bits;
                dst[offset + 1] = (byte) (bits >> 8);
                dst[offset + 2] = (byte) (bits >> 16);
                dst[offset + 3] = (byte) (bits >> 24);
                return;
            }
            case I16: {
                short s = SampleConversion.f32ToI16(v);
                dst[offset] = (byte) s;
                dst[offset + 1] = (byte) (s >> 8);
                return;
            }
            case U16: {
                int u = SampleConversion.f32ToU16(v);
                dst[offset] = (byte) u;
                dst[offset + 1] = (byte) (u >> 8);
                return;
            }
            case I24: {
                // 4 字节存储、只有低 24 位有效：高字节是符号扩展，不是垃圾
                int x = SampleConversion.f32ToI24(v);
                dst[offset] = (byte) x;
                dst[offset + 1] = (byte) (x >> 8);
                dst[offset + 2] = (byte) (x >> 16);
                dst[offset + 3] = (byte) (x >> 24);
                return;
            }
            default: {
                int x = SampleConversion.f32ToI32(v);
                dst[offset] = (byte) x;
                dst[offset + 1] = (byte) (x >> 8);
                dst[offset + 2] = (byte) (x >> 16);
                dst[offset + 3] = (byte) (x >> 24);
            }
        }
    }

    /**
     * {@link #writeSample} 的逆运算，供测试与调试核对打包是否正确。
     *
     * <p>上游没有这个函数，是移植时补的：Java 这边写的是字节而不是 cpal 的
     * {@code SizedSample}，需要一条往返路径把「打包」也纳入测试。
     */
    public float readSample(byte[] src, int offset) {
        switch (this) {
            case F32: {
                int bits = (src[offset] & 0xFF)
                        | ((src[offset + 1] & 0xFF) << 8)
                        | ((src[offset + 2] & 0xFF) << 16)
                        | ((src[offset + 3] & 0xFF) << 24);
                return Float.intBitsToFloat(bits);
            }
            case I16: {
                short s = (short) ((src[offset] & 0xFF) | (src[offset + 1] << 8));
                return s / 32767.0f;
            }
            case U16: {
                int u = (src[offset] & 0xFF) | ((src[offset + 1] & 0xFF) << 8);
                return (u / 65535.0f) * 2.0f - 1.0f;
            }
            case I24: {
                // 取低 24 位并做符号扩展（高字节里的符号位必须带进来）
                int x = (src[offset] & 0xFF)
                        | ((src[offset + 1] & 0xFF) << 8)
                        | ((src[offset + 2] & 0xFF) << 16);
                if ((x & 0x800000) != 0) {
                    x |= 0xFF000000;
                }
                return x / 8388607.0f;
            }
            default: {
                int x = (src[offset] & 0xFF)
                        | ((src[offset + 1] & 0xFF) << 8)
                        | ((src[offset + 2] & 0xFF) << 16)
                        | (src[offset + 3] << 24);
                return (float) (x / 2147483520.0);
            }
        }
    }

    @Override
    public String toString() {
        switch (this) {
            case F32: return "F32";
            case I16: return "I16";
            case U16: return "U16";
            case I24: return "I24";
            default: return "I32";
        }
    }
}
