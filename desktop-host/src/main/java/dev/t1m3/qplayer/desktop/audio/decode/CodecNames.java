package dev.t1m3.qplayer.desktop.audio.decode;

/**
 * 编码名相关的小工具。
 *
 * <p>对应上游 {@code sonalis-decode/src/lib.rs} 里那个模块级自由函数
 * {@code is_lossless}（按约定文档，模块级自由函数映射成同类上的 {@code public static}）。
 */
public final class CodecNames {

    private CodecNames() {
    }

    /**
     * 判断编码名是否为无损。
     *
     * <p><strong>只看编码名，绝不用位深佐证。</strong>上游注释写明：symphonia 对部分容器里的
     * AAC 会报告解码位深 {@code Some(16)}——曾经的写法 {@code is_lossless(codec) || bits >= 16}
     * 会因此把 MP3/AAC 标成无损，一路传到 {@code is_hires()} 显示成 Hi-Res 标签。
     * 这条判断是唯一判据，改动前先想清楚要背的锅。
     *
     * <p>用字符串而不是枚举，是为了将来接 FFmpeg 时能直接复用同一套判断（上游原话）。
     *
     * <p>与上游的差异：上游那张表没有 {@code pcm_u8}，而桌面端的 RIFF 读取器会报出
     * 8 位无符号 PCM（symphonia 里它叫 {@code pcm_u8}，可见上游那张表只是漏了这一项）。
     * 加它不会把任何有损格式拉进来——{@code pcm_u8} 是货真价实的无损 PCM。
     */
    public static boolean isLossless(String codec) {
        if (codec == null) {
            return false;
        }
        return "flac".equals(codec)
                || "alac".equals(codec)
                || "wavpack".equals(codec)
                || "pcm".equals(codec)
                || "pcm_s16le".equals(codec)
                || "pcm_s24le".equals(codec)
                || "pcm_s32le".equals(codec)
                || "pcm_f32le".equals(codec)
                || "pcm_u8".equals(codec);
    }
}
