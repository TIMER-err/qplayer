package dev.t1m3.qplayer.audio.core;

import java.nio.FloatBuffer;

/**
 * 平面 f32 音频缓冲。
 *
 * <p>为什么是平面（planar）而不是交错（interleaved）：重采样、均衡、声道映射、
 * 交叉馈送全都按声道独立处理，平面布局让每一步都不用先解交错。交错只发生在
 * 最后一步——送进音频后端之前。
 *
 * <p>缓冲区一旦创建就不再重新分配。{@link #setFrames} 只改变「有效长度」这个游标，
 * 这是热路径不分配内存的前提。
 *
 * <p>对应 Sonalis 的 {@code sonalis_core::buf::AudioBuf}。取声道数据用
 * {@link #channel(int)} / {@link #channelWriter(int)}（{@link FloatBuffer} 视图，
 * 即 Rust 那边的 {@code &[f32]} / {@code &mut [f32]} 切片）；确实需要裸数组的热循环
 * 可以用 {@link #backingArray()} + {@link #channelBase(int)}，但必须自己守住长度。
 */
public final class AudioBuf {

    private final float[] data;
    private final int channels;
    private final int capacityFrames;
    private int frames;

    /**
     * 按布局与帧容量创建。
     *
     * @param capacityFrames 帧容量（不是采样数；采样数是它的声道数倍）
     */
    public AudioBuf(ChannelLayout layout, int capacityFrames) {
        int ch = layout.count();
        if (ch <= 0) {
            throw new IllegalArgumentException("声道数必须大于 0");
        }
        if (capacityFrames < 0) {
            throw new IllegalArgumentException("帧容量不能为负：" + capacityFrames);
        }
        if ((long) ch * capacityFrames > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("声道数 × 帧容量超出数组上限："
                    + ch + " × " + capacityFrames);
        }
        this.channels = ch;
        this.capacityFrames = capacityFrames;
        this.data = new float[ch * capacityFrames];
        this.frames = 0;
    }

    /** 声道数。 */
    public int channels() {
        return channels;
    }

    /** 帧容量。 */
    public int capacityFrames() {
        return capacityFrames;
    }

    /** 当前有效帧数。 */
    public int frames() {
        return frames;
    }

    /** 设置有效帧数，超过容量会被夹住。 */
    public void setFrames(int n) {
        this.frames = n < 0 ? 0 : Math.min(n, capacityFrames);
    }

    /** 清空游标，保留已分配内存。 */
    public void clear() {
        this.frames = 0;
    }

    /** 当前是否没有有效数据。 */
    public boolean isEmpty() {
        return frames == 0;
    }

    /**
     * 第 {@code ch} 声道的有效数据（只读视图，长度 = {@link #frames()}）。
     *
     * <p>写入请用 {@link #channelWriter(int)}：那个视图的长度是整块容量。
     */
    public FloatBuffer channel(int ch) {
        checkChannel(ch);
        int base = ch * capacityFrames;
        return FloatBuffer.wrap(data, base, frames).slice();
    }

    /**
     * 第 {@code ch} 声道的整块内存（长度 = {@link #capacityFrames()}），用于写入。
     *
     * <p>写完记得 {@link #setFrames} 告诉缓冲实际产出了多少帧。
     */
    public FloatBuffer channelWriter(int ch) {
        checkChannel(ch);
        int base = ch * capacityFrames;
        return FloatBuffer.wrap(data, base, capacityFrames).slice();
    }

    /** 裸数组，仅供确实需要避开视图开销的热循环；长度 = 声道数 × 帧容量。 */
    public float[] backingArray() {
        return data;
    }

    /** 第 {@code ch} 声道在 {@link #backingArray()} 里的起始下标。 */
    public int channelBase(int ch) {
        checkChannel(ch);
        return ch * capacityFrames;
    }

    /** 全部置零。 */
    public void zero() {
        java.util.Arrays.fill(data, 0f);
    }

    /**
     * 交错输出到 {@code out}，返回写入的<strong>采样</strong>数。
     *
     * <p>{@code out} 必须至少能放下 {@code frames × channels} 个采样，否则只写能放下的部分。
     */
    public int interleaveInto(float[] out) {
        int ch = channels;
        int n = Math.min(frames, out.length / ch);
        for (int i = 0; i < n; i++) {
            int row = i * ch;
            for (int c = 0; c < ch; c++) {
                out[row + c] = data[c * capacityFrames + i];
            }
        }
        return n * ch;
    }

    /** 峰值绝对值。用于限幅判断与电平显示。 */
    public float peak() {
        float peak = 0f;
        for (int c = 0; c < channels; c++) {
            int base = c * capacityFrames;
            for (int i = 0; i < frames; i++) {
                float a = Math.abs(data[base + i]);
                if (a > peak) {
                    peak = a;
                }
            }
        }
        return peak;
    }

    /** 是否有采样超出 [-1, 1]。 */
    public boolean isClipping() {
        return peak() > 1f;
    }

    /**
     * 把 {@code other} 的有效帧追加到本缓冲之后。
     *
     * <p>声道数必须一致，否则返回 0 且不做任何事。返回实际追加的帧数；放不下时
     * 截断（不分配、不报错）。
     */
    public int append(AudioBuf other) {
        if (other == null || channels != other.channels) {
            return 0;
        }
        int room = capacityFrames - frames;
        int n = Math.min(other.frames, room);
        if (n == 0) {
            return 0;
        }
        int start = frames;
        int srcStride = other.capacityFrames;
        for (int c = 0; c < channels; c++) {
            System.arraycopy(other.data, c * srcStride, data, c * capacityFrames + start, n);
        }
        frames += n;
        return n;
    }

    private void checkChannel(int ch) {
        if (ch < 0 || ch >= channels) {
            throw new IndexOutOfBoundsException("声道下标越界：" + ch + "（共 " + channels + " 声道）");
        }
    }

    /** 只报有用的状态：Rust 那边 derive(Debug) 会把几万个采样刷进日志，这里不这么干。 */
    @Override
    public String toString() {
        return "AudioBuf{" + channels + "ch, frames=" + frames + "/" + capacityFrames
                + ", peak=" + peak() + '}';
    }
}
