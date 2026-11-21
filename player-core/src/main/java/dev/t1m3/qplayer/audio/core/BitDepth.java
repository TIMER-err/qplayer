package dev.t1m3.qplayer.audio.core;

/**
 * 位深。
 *
 * <p>对应 Sonalis 的 {@code sonalis_core::format::BitDepth}。
 */
public enum BitDepth {

    /** 16 位整数（CD 标准）。 */
    INT16,
    /** 24 位整数（Hi-Res 主力规格）。 */
    INT24,
    /** 32 位整数。 */
    INT32,
    /** 32 位浮点。 */
    FLOAT32,
    /** 64 位浮点。 */
    FLOAT64;

    /** 有效位数。用于比较精度高低。 */
    public int bits() {
        switch (this) {
            case INT16: return 16;
            case INT24: return 24;
            case INT32: return 32;
            case FLOAT32: return 32;
            default: return 64;
        }
    }

    /** 是否为整数表示。 */
    public boolean isInteger() {
        return this == INT16 || this == INT24 || this == INT32;
    }

    /**
     * f32 能否<strong>精确</strong>表示该位深的整数样本。
     *
     * <p>{@link #INT16} / {@link #INT24} 可以，{@link #INT32} 不行——f32 的尾数只有
     * 24 位，第 25 位以下会丢。这意味着 32 位整数源走 f32 中转就不再位完美，
     * 必须走整数直通路径。
     */
    public boolean exactInF32() {
        return this == INT16 || this == INT24;
    }

    /**
     * 从解码器报告的 {@code bitsPerSample} 推断。
     *
     * <p>窗口是放宽的：报 20 或 18 位的文件实际按 24 位容器存，过早判定成 16 位
     * 会让位完美判定误判为「需要降位」。返回 {@code null} 表示无法识别。
     */
    public static BitDepth fromBits(int bits) {
        if (bits >= 1 && bits <= 16) return INT16;
        if (bits >= 17 && bits <= 24) return INT24;
        if (bits >= 25 && bits <= 64) return INT32;
        return null;
    }

    /** 简短标签，用于界面。 */
    public String label() {
        switch (this) {
            case INT16: return "16 bit";
            case INT24: return "24 bit";
            case INT32: return "32 bit";
            case FLOAT32: return "32 bit float";
            default: return "64 bit float";
        }
    }

    @Override
    public String toString() {
        return label();
    }
}
