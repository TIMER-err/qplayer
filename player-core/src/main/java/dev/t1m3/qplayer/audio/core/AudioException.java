package dev.t1m3.qplayer.audio.core;

/**
 * 统一音频错误。
 *
 * <p>刻意保持扁平：调用方通常只做两件事——打印给人看，或者换一条路径重试。
 * 细分错误类型在这个项目里没有收益。
 *
 * <p>对应 Sonalis 的 {@code sonalis_core::error::SonalisError}（那边是 enum + Result，
 * 这边是异常）。
 */
public class AudioException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误类别。 */
    public enum Kind {
        /** 文件或流的读写失败。 */
        IO,
        /** 解码失败：容器损坏、码流非法等。 */
        DECODE,
        /** 能识别容器但不会解这个编码。 */
        UNSUPPORTED_FORMAT,
        /** 音频设备相关失败。 */
        OUTPUT,
        /** 参数不合法（采样率、声道数、缓冲区长度等）。 */
        CONFIG
    }

    private final Kind kind;

    public AudioException(Kind kind, String message) {
        super(prefix(kind) + message);
        this.kind = kind;
    }

    public AudioException(Kind kind, String message, Throwable cause) {
        super(prefix(kind) + message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** I/O 失败：最常被上层「换一条路径重试」的那一类。 */
    public static AudioException io(String message, Throwable cause) {
        return new AudioException(Kind.IO, message, cause);
    }

    public static AudioException decode(String message) {
        return new AudioException(Kind.DECODE, message);
    }

    public static AudioException unsupportedFormat(String message) {
        return new AudioException(Kind.UNSUPPORTED_FORMAT, message);
    }

    public static AudioException output(String message) {
        return new AudioException(Kind.OUTPUT, message);
    }

    public static AudioException config(String message) {
        return new AudioException(Kind.CONFIG, message);
    }

    private static String prefix(Kind kind) {
        switch (kind) {
            case IO: return "io: ";
            case DECODE: return "decode: ";
            case UNSUPPORTED_FORMAT: return "unsupported format: ";
            case OUTPUT: return "output: ";
            default: return "config: ";
        }
    }
}
