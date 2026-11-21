package dev.t1m3.qplayer.audio.mashup;

/**
 * <b>一整段平面 f32 素材</b>：mashup 各阶段之间传递音频的最小单位。
 *
 * <p>为什么不用 {@code AudioBuf}：那是<b>块</b>缓冲（容量固定、{@code frames} 是可移动游标，
 * 热路径靠"不重新分配"换来性能）；mashup 的处理对象是<b>整段素材</b>（一首歌的人声轨），
 * 两者的生命周期和访问方式（随机读某一段 vs 顺序消费一块）完全不同，套在同一个类型上
 * 会让"这块还能不能用"变成需要猜的问题。
 *
 * <p>约定与仓库其它地方一致：<b>平面</b>（每声道一个数组）、<b>f32</b>、长度以<b>帧</b>计。
 * 声道数组的长度必须一致——长度差会让后续每一处对齐都错位，所以构造时直接拒绝。
 */
public final class PcmTrack {

    private final float[][] channels;
    private final int frames;

    private PcmTrack(float[][] channels, int frames) {
        this.channels = channels;
        this.frames = frames;
    }

    /** 单声道素材（{@code channels = 1}）。 */
    public static PcmTrack mono(float[] pcm) {
        if (pcm == null) {
            throw new IllegalArgumentException("单声道素材不能为空");
        }
        return new PcmTrack(new float[][]{pcm}, pcm.length);
    }

    /** 立体声素材。 */
    public static PcmTrack stereo(float[] left, float[] right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("立体声素材的左右声道都不能为空");
        }
        if (left.length != right.length) {
            throw new IllegalArgumentException(
                    "左右声道长度必须一致：" + left.length + " vs " + right.length);
        }
        return new PcmTrack(new float[][]{left, right}, left.length);
    }

    /**
     * 任意声道数（{@code 1..8}）。
     *
     * <p><b>不复制数组</b>：调用方交出所有权（这与 {@code AudioBuf} 的"引用而不是拷贝"
     * 一致）。要保留副本请自己 {@code clone()}。
     */
    public static PcmTrack of(float[][] channels) {
        if (channels == null || channels.length == 0) {
            throw new IllegalArgumentException("素材至少要有一个声道");
        }
        if (channels.length > 8) {
            throw new IllegalArgumentException("声道数过多：" + channels.length);
        }
        int n = -1;
        for (int c = 0; c < channels.length; c++) {
            float[] ch = channels[c];
            if (ch == null) {
                throw new IllegalArgumentException("第 " + c + " 个声道为空");
            }
            if (n < 0) {
                n = ch.length;
            } else if (ch.length != n) {
                throw new IllegalArgumentException("第 " + c + " 个声道长度 " + ch.length
                        + " 与第 0 个声道长度 " + n + " 不一致");
            }
        }
        return new PcmTrack(channels.clone(), n);
    }

    /** 声道数（1..8）。 */
    public int channels() {
        return channels.length;
    }

    /** 帧数。 */
    public int frames() {
        return frames;
    }

    /** 第 {@code c} 个声道的样本数组（只读约定，见 {@link #of(float[][])}）。 */
    public float[] channel(int c) {
        return channels[c];
    }

    /** 取 {@code frame} 处第 {@code c} 声道的样本；越界返回 0（"算不出来就静音"，不抛）。 */
    public float sample(int c, long frame) {
        if (frame < 0 || frame >= frames) {
            return 0f;
        }
        return channels[c][(int) frame];
    }

    @Override
    public String toString() {
        return "PcmTrack{" + channels.length + "ch, " + frames + " 帧}";
    }
}
