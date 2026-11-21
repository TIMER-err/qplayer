package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.dsp.PitchShifter;

/**
 * 「按块流式移调」的那一层壳：把 {@link PitchShifter}（相位声码器）包成一个能直接吃
 * {@link AudioBuf} 的实时模块内核。
 *
 * <h2>为什么把它单独抽出来</h2>
 *
 * <p>这段壳里有三处是<strong>踩过坑才写对</strong>的，而用到它的地方不止一处
 * （{@link PitchModule} 是「用户拧一个旋钮」，{@link AutotuneModule} 是「每 10 ms
 * 按检测结果改一次比例」）：
 *
 * <ol>
 *   <li><strong>延迟是零</strong>：声码器移的是音高、时间轴原样保留，它对外报的
 *       {@code grain − hop}（1536 帧）是内部环的记账，<em>不是</em>信号延迟
 *       ——量出来的干湿对齐 lag 是 0（实测：宽带噪声互相关峰在第 0 帧）。
 *       照那个数去延迟干声，得到的是梳状滤波而不是移调。</li>
 *   <li><strong>喂之前先排空</strong>：声码器的内部环按块设计，只喂不取会让环满、
 *       {@code pushFrom} 推不动、输入被丢掉 → 输出音高漂（实测偏 3.4 音分）。</li>
 *   <li><strong>整个输出延后一个 CHUNK</strong>：这样需要吐出的湿信号早就到齐了，
 *       干湿仍在同一位置相加，只有整块输出被推迟——而那笔延迟
 *       <strong>如实上报</strong>（{@link #latencyFrames()}）。</li>
 * </ol>
 *
 * <p>参数（比例）可以随时改：改一下 {@code shifter.setSemitones}，滤波器的相位历史
 * <strong>不清</strong>（拖滑杆、跑调修正都靠这个才不会咔哒）。
 *
 * <p>实时规则：缓冲一律构造时分配，{@link #process} <strong>零分配</strong>。
 * （{@code PitchShifter} 内部原本每次 {@code pull}/{@code pushFrom} 都会 new 一组
 * {@code FloatBuffer} 视图——实测 1440 字节/块，已改成裸数组下标。）
 */
public final class StreamingPitchShift {

    /** 每次推给声码器的帧数（与构造时告诉它的 chunkFrames 一致）。 */
    public static final int CHUNK = 1024;

    /** 本壳引入的延迟（帧）= 一个 {@link #CHUNK}：48 kHz 下 21.3 ms。 */
    public static final int LATENCY_FRAMES = CHUNK;

    private final int channels;
    private final PitchShifter shifter;
    /** 声码器输出的取数缓冲（pull 的目标）。 */
    private final AudioBuf wet;
    /** 湿信号的搬运缓冲：一次 pull 多出来的帧留到下一小块用。 */
    private final float[][] carry;
    private int carryCount;
    /** 输出保持缓冲：混好的这一批先存这里，吐出去的是上一轮那一批。 */
    private final float[][] holdBuf;
    /** 保持缓冲的写指针（跨小块连续旋转——每小块重置就变成「只有最后一块被延迟」）。 */
    private int holdAt;

    private double appliedSemitones;

    /**
     * 「正在把最后那一批前视放出去」：参数关掉之后还要跑一个 CHUNK 才算真的停下
     * （直接返回会让那 21 ms 凭空消失 = 一个断口）。
     */
    private boolean draining;

    /**
     * 这次收尾已经放出去多少帧（<strong>跨块累加</strong>）。
     *
     * <p>必须是字段：收尾要放满一个 {@link #CHUNK}，而调用方给的块可能比它小。
     * 用局部变量的话每一块都会「从头开始淡」，于是永远淡不到底、也永远退不回旁路
     * （判据用 512 帧的块当场照出来过）。
     */
    private int flushed;

    public StreamingPitchShift(int rate, int channels) {
        int r = Math.max(rate, 1);
        this.channels = Math.max(channels, 1);
        int shifterChannels = this.channels > 1 ? 2 : 1;
        this.shifter = new PitchShifter(shifterChannels, r, 0f, CHUNK);
        this.wet = new AudioBuf(ChannelLayout.of(shifterChannels), CHUNK);
        this.carry = new float[this.channels][CHUNK * 2];
        this.holdBuf = new float[this.channels][CHUNK];
    }

    /** 当前移调量（半音）。 */
    public double semitones() {
        return appliedSemitones;
    }

    /** 改移调量（半音）。**不清**声码器的状态：连续改（滑杆、跑调修正）不会咔哒。 */
    public void setSemitones(double semitones) {
        if (semitones != appliedSemitones) {
            appliedSemitones = semitones;
            shifter.setSemitones((float) semitones);
        }
    }

    /** 现在会不会改动采样（收尾期间也算）。 */
    public boolean active() {
        return draining;
    }

    /** 一个 CHUNK 的前视，如实上报（见类注释）。 */
    public int latencyFrames() {
        return CHUNK;
    }

    public void reset() {
        shifter.reset();
        carryCount = 0;
        for (float[] h : holdBuf) {
            java.util.Arrays.fill(h, 0f);
        }
        holdAt = 0;
        appliedSemitones = 0;
        draining = false;
        flushed = 0;
    }

    /**
     * 就地处理一块（干湿混合由调用方给）。
     *
     * @param buf 就地处理的缓冲
     * @param mix 干湿：1 = 全湿（移调声），0 = 全干
     */
    public void process(AudioBuf buf, float mix) {
        int frames = buf.frames();
        if (frames <= 0) {
            return;
        }
        if (flushed > 0) {
            // 上一次收尾还没走完就又被打开（21 ms 内连按两下）：hold 里有一半已经被读掉
            // 并清零，接着往下混会把那段空洞放出去。当作重新开始（清 hold 与 OLA 状态），
            // 代价是紧接着的 21 ms 是空的。**只有小于一个 CHUNK 的块长才可能走到这里。**
            for (float[] h : holdBuf) {
                java.util.Arrays.fill(h, 0f);
            }
            holdAt = 0;
            shifter.reset();
            flushed = 0;
        }
        draining = true;
        int chs = Math.min(buf.channels(), channels);
        int done = 0;
        while (done < frames) {
            int n = Math.min(CHUNK, frames - done);
            int pushed = 0;
            while (pushed < n && shifter.freeFrames() > 0) {
                int k = shifter.pushFrom(buf, done + pushed);
                if (k <= 0) {
                    break;
                }
                pushed += k;
            }
            // 喂之前先排空（见类注释第 ② 条）
            drain();
            mixBack(buf, done, n, mix, chs);
            done += n;
        }
    }

    /** 把声码器里已经能取的湿信号全部搬进 carry（carry 满了就停）。 */
    private void drain() {
        int guard = 0;
        while (carry[0].length - carryCount >= CHUNK && guard++ < 64) {
            int got = Math.max(shifter.pull(wet, 0), 0);
            if (got <= 0) {
                return;
            }
            int room = carry[0].length - carryCount;
            int take = Math.min(got, room);
            for (int ch = 0; ch < channels; ch++) {
                int from = wet.channelBase(Math.min(ch, wet.channels() - 1));
                System.arraycopy(wet.backingArray(), from, carry[ch], carryCount, take);
            }
            carryCount += take;
        }
    }

    /** 把第 {@code from} 帧起的 {@code n} 帧混好写回（干 = 延迟线，湿 = carry）。 */
    private void mixBack(AudioBuf buf, int from, int n, float mixValue, int chs) {
        float[] backing = buf.backingArray();
        int usable = Math.min(n, carryCount);
        int at = holdAt;
        for (int i = 0; i < n; i++) {
            for (int ch = 0; ch < chs; ch++) {
                int base = buf.channelBase(ch);
                float[] wetCarry = carry[ch];
                float[] hold = holdBuf[ch];
                float dry = backing[base + from + i];
                float wetV = i < usable ? wetCarry[i] : 0f;
                float mixed = dry * (1.0f - mixValue) + wetV * mixValue;
                backing[base + from + i] = hold[at];   // 吐一个 CHUNK 之前的
                hold[at] = mixed;
            }
            at = at + 1 == holdBuf[0].length ? 0 : at + 1;
        }
        holdAt = at;
        if (usable > 0) {
            int left = carryCount - usable;
            for (int ch = 0; ch < chs; ch++) {
                if (left > 0) {
                    System.arraycopy(carry[ch], usable, carry[ch], 0, left);
                }
            }
            carryCount = left;
        }
    }

    /**
     * 收尾：把 {@link #holdBuf} 里最后那一批前视放出去，并<strong>交叉淡化到本块的干声</strong>。
     *
     * <p>模块的输出比输入晚一个 CHUNK，所以关掉的那一刻还压着 21 ms 已经处理好的声音：
     *
     * <ul>
     *   <li><strong>直接返回</strong>（一个采样都不碰）→ 输出内容向前跳 21 ms，接缝上是一次台阶；</li>
     *   <li><strong>原样倒出来</strong> → 那 21 ms 是「延迟过的」内容，倒完之后下一块又是本块位置上的
     *       干声，等于把同一段时间放了两遍再跳一次；</li>
     *   <li><strong>交叉淡化</strong>（这里做的）：把那 21 ms 处理声淡到干声上。绝对对齐做不到——
     *       那 21 ms 的内容差<strong>就是</strong>模块的延迟，去掉延迟必然要跨过它。</li>
     * </ul>
     *
     * <p>判据：收尾块的<strong>最后一个采样必须等于本块自己的干声</strong>。
     */
    void flushHold(AudioBuf buf) {
        int frames = buf.frames();
        if (frames <= 0) {
            return;
        }
        int chs = Math.min(buf.channels(), channels);
        float[] backing = buf.backingArray();
        int at = holdAt;
        float inv = 1f / (float) CHUNK;
        for (int i = 0; i < frames && flushed < CHUNK; i++) {
            float w = (flushed + 1) * inv;
            if (w > 1f) {
                w = 1f;
            }
            for (int ch = 0; ch < chs; ch++) {
                int base = buf.channelBase(ch);
                float tail = holdBuf[ch][at];
                holdBuf[ch][at] = 0f;
                backing[base + i] = tail * (1f - w) + backing[base + i] * w;
            }
            at = at + 1 == holdBuf[0].length ? 0 : at + 1;
            flushed++;
        }
        holdAt = at;
        if (flushed < CHUNK) {
            return;                  // 还没淡完：下一块继续（flushed 跨块累加）
        }
        draining = false;
        flushed = 0;
    }
}
