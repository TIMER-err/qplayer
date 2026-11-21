package dev.t1m3.qplayer.audio.output;

/**
 * f32 → 目标采样类型的转换与限幅。
 *
 * <p>对应 Sonalis {@code crates/sonalis-output/src/lib.rs} 里的模块级自由函数
 * {@code f32_to_i16} / {@code f32_to_u16} / {@code f32_to_i32} / {@code f32_to_i24}
 * （Rust 的自由函数在 Java 里落到 {@code public static} 上，见移植约定）。
 *
 * <h3>为什么自己写，而不用现成的转换（上游第 ③ 条设计决定）</h3>
 *
 * 上游刻意<strong>不用</strong> cpal 的 {@code FromSample}：它的溢出行为不在测试覆盖内，
 * 而输出端溢出会直接变成可听的反相爆音（1.5 变 −0.5 那种）。这里同样全部先
 * {@link #clampUnit(float)} 夹到 [-1, 1]，再乘系数取整，行为完全可控且有测试锁着。
 * Java 侧同理：{@code AudioFormat} 那条路上任何 JDK 自带的转换都不可控，
 * 一律不用。
 *
 * <h3>与上游的取整差异（必须显式抹平，否则逐样本不一致）</h3>
 *
 * Rust 的 {@code f32::round()} 是「四舍五入，恰好 .5 时<strong>远离零</strong>」；
 * Java 的 {@link Math#round(float)} 是 {@code floor(x + 0.5)}，对负数 .5 会往
 * <strong>零</strong>的方向走——{@code Math.round(-16383.5f)} 得 -16383，而 Rust 得 -16384。
 * 所以这里自己实现 {@link #roundHalfAwayFromZero(float)}，保证与上游逐样本一致。
 * 乘出来的中间量本身仍是 f32（与上游同精度），只有 {@code ±0.5} 与取整在 double 里做，
 * 这对量级 {@code <= 2^31} 的值是精确的，不会引入新的舍入。
 *
 * <h3>NaN 与正负零</h3>
 *
 * 上游的 {@code f32::clamp} 对 NaN 原样返回 NaN，随后 Rust 的浮点→整数转换是
 * <em>饱和转换</em>，NaN 转出 0。这里把 NaN 直接当 0（静音）处理：有符号格式出 0，
 * 无符号格式出中点 32768，两者都是静音。理由与上游「欠载吐静音」一致——NaN 是上游
 * 链路出问题时的症状，送到设备上的应该是静音，而不是一个随 NaN 位模式变化的爆音。
 * 正负零（{@code 0.0f} 与 {@code -0.0f}）都出 0，不存在 -0 这种整数表示。
 */
public final class SampleConversion {

    private SampleConversion() {
    }

    /**
     * 夹到 [-1, 1]；NaN 视为 0。
     *
     * <p>对应上游的 {@code v.clamp(-1.0, 1.0)}：夹紧是这里唯一的防线，
     * 越过它的采样会绕回成反相信号。
     */
    public static float clampUnit(float v) {
        // NaN != NaN：先拦下来，避免它一路飘到整数转换里
        if (v != v) {
            return 0f;
        }
        if (v < -1f) {
            return -1f;
        }
        if (v > 1f) {
            return 1f;
        }
        return v;
    }

    /** Rust {@code f32::round} 语义：四舍五入，.5 远离零。 */
    static float roundHalfAwayFromZero(float v) {
        return v < 0f ? (float) Math.ceil(v - 0.5) : (float) Math.floor(v + 0.5);
    }

    /**
     * f32 → i16。
     *
     * <p>先夹到 [-1, 1] 再缩放：不夹的话溢出会绕回成反相信号。
     * 上限用 32767 而不是 32768，因为 32768 溢出 i16。
     */
    public static short f32ToI16(float v) {
        float clamped = clampUnit(v);
        return (short) ((int) roundHalfAwayFromZero(clamped * 32767.0f));
    }

    /**
     * f32 → u16（无符号偏移格式）。
     *
     * <p>Java 没有无符号类型，这里用 {@code int} 承载 0..65535 的结果，
     * 与上游 {@code u16} 的取值域一一对应。
     */
    public static int f32ToU16(float v) {
        float clamped = clampUnit(v);
        return (int) roundHalfAwayFromZero((clamped * 0.5f + 0.5f) * 65535.0f);
    }

    /**
     * f32 → i32。
     *
     * <p>系数 2147483520 = 2^31 − 128，是 f32 能精确表示的最大「接近 i32::MAX」的值：
     * 直接乘 2147483647 在 f32 里根本表示不出来，会先被舍入到 2147483648 再溢出。
     */
    public static int f32ToI32(float v) {
        float clamped = clampUnit(v);
        return (int) roundHalfAwayFromZero(clamped * 2147483520.0f);
    }

    /**
     * f32 → 24 位整数（返回 32 位容器，只有低 24 位有效）。
     *
     * <p>上限取 2^23 − 1 = 8388607，再乘下去就会溢出 24 位有效范围——
     * 而 24 位是 HiFi 主力规格，这里出一点偏差都是可听的。
     */
    public static int f32ToI24(float v) {
        float clamped = clampUnit(v);
        return (int) roundHalfAwayFromZero(clamped * 8388607.0f);
    }
}
