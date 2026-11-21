package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;

/**
 * 无缝拼接：不做任何混合，只决定拼接点。
 *
 * <p><strong>这是唯一与位完美兼容的换曲方式</strong>——因为它不碰采样值。
 *
 * <p>真正让它「无缝」的是解码层的编码器延迟补偿（Xing/LAME 标签、AAC priming）
 * 加上一条永不中断的输出流，<strong>不是淡化</strong>。把 gapless 和 crossfade
 * 混为一谈是常见误解，这个类型的全部存在意义就是把它们分开。
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::transition::Gapless}（上游是 unit struct，
 * 这里是单例）。
 */
public final class Gapless implements Transition {

    /** 唯一实例。 */
    public static final Gapless INSTANCE = new Gapless();

    private Gapless() {
    }

    /** 窗口为空：不重叠，所以「过渡」退化成一次瞬间交接。 */
    @Override
    public long windowFrames(int sampleRate) {
        return 0;
    }

    @Override
    public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
        // 窗口为空时 progress 只会在两个端点取值：
        //   0.0 → 出场曲  1.0 → 进场曲
        // 中间值（理论上不该出现）按「已过半即交接」处理。
        if (progress < 0.5f) {
            return Mixer.gainInto(out, from, 1f);
        }
        return Mixer.gainInto(out, to, 1f);
    }

    @Override
    public String name() {
        return "无缝拼接";
    }

    @Override
    public boolean rewritesSamples() {
        return false;
    }
}
