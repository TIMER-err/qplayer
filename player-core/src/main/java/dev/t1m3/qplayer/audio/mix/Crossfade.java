package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;

/**
 * 等功率交叉淡化。
 *
 * <p>用 {@code cos/sin} 而不是线性（原因见 {@link Mixer#equalPowerGains}）。
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::transition::Crossfade}。
 */
public final class Crossfade implements Transition {

    /** 无参构造用的默认窗口：6 秒是 DJ 混音的常见默认值。 */
    public static final float DEFAULT_SECONDS = 6f;

    /** 窗口时长（秒）。 */
    public final float seconds;

    /** 以默认窗口（6 秒）构造：足够长到听不出接缝，又不至于让两首歌「打架」太久。 */
    public Crossfade() {
        this(DEFAULT_SECONDS);
    }

    /** 以窗口时长（秒）构造。负值会被夹到 0（夹在 {@link #secondsClamped()} 里做）。 */
    public Crossfade(float seconds) {
        this.seconds = seconds;
    }

    /** 实际生效的窗口时长。 */
    public float secondsClamped() {
        return Math.max(seconds, 0f);
    }

    @Override
    public long windowFrames(int sampleRate) {
        return Math.round(secondsClamped() * (float) sampleRate);
    }

    @Override
    public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
        Mixer.Gains g = Mixer.equalPowerGains(progress);
        return Mixer.mixTwo(out, from, g.from, to, g.to);
    }

    @Override
    public String name() {
        return "交叉淡化";
    }

    @Override
    public boolean rewritesSamples() {
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Crossfade)) return false;
        return Float.compare(((Crossfade) o).seconds, seconds) == 0;
    }

    @Override
    public int hashCode() {
        return Float.floatToIntBits(seconds);
    }

    @Override
    public String toString() {
        return "Crossfade{seconds=" + seconds + '}';
    }
}
