package dev.t1m3.qplayer.audio.output;

/**
 * 设备对 DSD 原生输出的支持情况。
 *
 * <p>对应 Sonalis 的 {@code sonalis_output::DsdSupport}。
 *
 * <h3>为什么 DSD 不映射成 PCM 格式</h3>
 *
 * 上游的 {@code cpal::SampleFormat} 里 DSD 是一等公民（{@code DsdU8/DsdU16/DsdU32}），
 * 但 {@code OutputSampleFormat} 刻意不接收它们，测试
 * {@code dsd_formats_are_not_mapped_as_pcm} 锁着这一点。理由是：把 DSD 当成
 * 「另一种采样格式」混进 PCM 的开流/转换路径，等于允许有人对它调用
 * {@code f32_to_i16} 之类的函数——那是纯粹的噪声。
 *
 * <p>{@link #AVAILABLE} <strong>只说明底座能以 DSD 开流</strong>：上游还没有 DSD 解码器
 * （Symphonia 不解 DSF/DFF），所以这项仅用于界面标注，播放路径尚未接通。
 * 移植到 Java 后情况更差一层：{@code javax.sound.sampled} 根本没有 DSD 编码，
 * 所以桌面端这条路径恒为 {@link #NONE}（诚实标注，不是实现偷懒）。
 */
public enum DsdSupport {

    /** 设备未声明任何 DSD 格式。 */
    NONE,

    /**
     * 设备声明了 DSD 格式（{@code DsdU8} / {@code DsdU16} / {@code DsdU32}）。
     *
     * <p>只代表底座能以 DSD 开流。目前<strong>没有 DSD 解码器</strong>，
     * 所以这项仅用于界面标注。
     */
    AVAILABLE;

    /**
     * 从设备声明的格式清单推导 DSD 支持度。
     *
     * <p>上游直接看 cpal 报的 range 列表；这里把它抽成纯函数，
     * 好处是「有 DSD 档位 → AVAILABLE」这条规则可以脱离真实声卡被测到。
     */
    public static DsdSupport of(java.util.List<DeviceSampleFormat> declaredFormats) {
        if (declaredFormats == null) {
            return NONE;
        }
        for (DeviceSampleFormat f : declaredFormats) {
            if (f != null && f.isDsd()) {
                return AVAILABLE;
            }
        }
        return NONE;
    }
}
