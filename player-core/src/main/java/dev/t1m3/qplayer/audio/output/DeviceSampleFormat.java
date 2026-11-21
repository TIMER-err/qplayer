package dev.t1m3.qplayer.audio.output;

/**
 * 设备侧可能声明的采样格式。
 *
 * <p>这一层刻意与 {@link OutputSampleFormat} 分开：<strong>设备会报一堆我们开不了的格式，
 * 那是常态</strong>（上游：{@code // 我们不支持的格式直接跳过，不报错}）。把「设备报了什么」
 * 与「我们能开什么」混成一个枚举，就再也表达不了「探测到了但不支持」这件事。
 *
 * <p>与上游 {@code cpal::SampleFormat} 的对应关系（上游只有枚举列出的一小部分能开流）：
 *
 * <ul>
 *   <li>{@link #F32} / {@link #I16} / {@link #U16} / {@link #I24} / {@link #I32}
 *       —— 上游支持的 5 种，可开流</li>
 *   <li>{@link #F64} —— cpal 0.18 会报，但上游<strong>刻意不映射</strong>：
 *       {@code from_cpal} 对它返回错误，测试 {@code unsupported_sample_format_is_reported_not_panicked}
 *       就是拿它当例子。移植后保持同一语义</li>
 *   <li>{@link #DSD_U8} / {@link #DSD_U16} / {@link #DSD_U32} —— cpal 的 DSD 原生格式。
 *       <strong>不映射成 PCM</strong>（测试 {@code dsd_formats_are_not_mapped_as_pcm} 锁着），
 *       只用于 DSD 支持度上报。理由见 {@link DsdSupport}</li>
 *   <li>{@link #U8} —— javax.sound 的 {@code PCM_UNSIGNED} 8 位。上游的 U16 是 cpal 的
 *       无符号 16 位格式，而 javax.sound 的 {@code PCM_UNSIGNED} 实际只用于 8 位，
 *       所以两个平台的无符号档位不是同一个东西，这里分开列</li>
 *   <li>{@link #OTHER} —— 其它（javax.sound 的 μ-law / A-law 等）。</li>
 * </ul>
 */
public enum DeviceSampleFormat {

    /** 32 位浮点（内部格式直通）。 */
    F32,
    /** 16 位有符号整数（CD 规格）。 */
    I16,
    /** 16 位无符号整数（cpal 的无符号档位）。 */
    U16,
    /** 24 位有符号整数，4 字节存储、24 位有效。<strong>HiFi 主力规格</strong>。 */
    I24,
    /** 32 位有符号整数。 */
    I32,
    /** 64 位浮点。设备会报，我们刻意不开。 */
    F64,
    /** 8 位无符号整数（javax.sound 的 {@code PCM_UNSIGNED}）。我们不做 8 bit。 */
    U8,
    /** DSD 原生格式（8 位容器）。 */
    DSD_U8,
    /** DSD 原生格式（16 位容器）。 */
    DSD_U16,
    /** DSD 原生格式（32 位容器）。 */
    DSD_U32,
    /** 其它我们不认识的格式。 */
    OTHER;

    /** 是否为 DSD 原生格式。 */
    public boolean isDsd() {
        return this == DSD_U8 || this == DSD_U16 || this == DSD_U32;
    }

    @Override
    public String toString() {
        switch (this) {
            case F32: return "F32";
            case I16: return "I16";
            case U16: return "U16";
            case I24: return "I24";
            case I32: return "I32";
            case F64: return "F64";
            case U8: return "U8";
            case DSD_U8: return "DsdU8";
            case DSD_U16: return "DsdU16";
            case DSD_U32: return "DsdU32";
            default: return "Other";
        }
    }
}
