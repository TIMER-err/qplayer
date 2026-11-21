package dev.t1m3.qplayer.audio.dsp;

import dev.t1m3.qplayer.audio.core.AudioBuf;

import java.nio.FloatBuffer;

/**
 * 采样率转换：流式多相窗函数 sinc 重采样器。
 *
 * <p>多相窗函数 sinc 插值。为什么不用线性插值：线性插值在高频段的衰减与混叠都在
 * 可听范围内，48k 播放 44.1k 素材时会明显发闷，而 96k 素材降到 48k 更是直接混叠。
 *
 * <p>实现要点：
 *
 * <ul>
 *   <li>核表按相位预计算（{@link #OVERSAMPLE} 个相位 × {@link #TAPS} 个抽头），
 *       运行时只做乘加，不调用 {@code sin()}</li>
 *   <li>每个相位的系数<strong>归一化到和为 1</strong>，这保证直流增益严格为 1，
 *       也就保证了「全 1 信号进、全 1 信号出」——这条是可测的</li>
 *   <li>降采样时收窄截止频率（{@code cutoff = out/in * 0.95}）以压住混叠</li>
 *   <li>{@link #push} / {@link #pull} 解耦：输入输出块长可以任意，转换比不必是整数</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis-dsp/src/resample.rs}。
 *
 * <p>与上游的差异（约定见 {@code docs/audio-port.md}）：
 *
 * <ul>
 *   <li>上游 {@code history: Vec<f32>} 在这里是私有的裸 {@code float[]}。它是热循环内部的
 *       扁平缓冲，不走 {@link AudioBuf}（套上 {@code setFrames} 的游标语义只会更容易写错），
 *       属于约定里「热循环可用裸数组、但要在注释里说明」的例外</li>
 *   <li>上游用 {@code #[must_use]} 在编译期挡住「忽略 {@code push} 返回值」，Java 没有
 *       这个能力，只能把要求写死在 javadoc 里，并额外提供 {@link #pushAll} 便捷入口</li>
 *   <li>上游的 {@code debug_assert_eq!} 只在 debug 构建生效；Java 没有 debug/release 之分，
 *       这里<strong>总是</strong>检查（更严，不会更松）</li>
 *   <li>上游的 {@code const} 对子模块（测试）可见，这里用包内可见（默认访问级别）达到
 *       同样效果，不让它们进入公开 API</li>
 *   <li><strong>多了 {@link #drain}（流末尾收尾），上游没有对应物。</strong> 上游
 *       {@code resample.rs} 只有 {@code push} / {@code pull}，没有任何 drain / flush：
 *       于是流末尾那 {@link #TAPS_PER_SIDE} 帧「未来输入」永远凑不齐，最后 16 帧
 *       被静默丢掉（24000 帧的曲子只产出 23984 帧）。这是<strong>与上游的已记录偏差</strong>，
 *       原因、影响与修法见 {@link #drain} 的 javadoc 与 {@code docs/audio-port.md}
 *       的「与上游的已知差异」表</li>
 * </ul>
 */
public final class Resampler {

    /** 每侧抽头数。16 对应 32 抽头核，阻带衰减足够日常使用。 */
    static final int TAPS_PER_SIDE = 16;

    /** 总抽头数。 */
    static final int TAPS = TAPS_PER_SIDE * 2;

    /** 相位细分数。越大越接近连续插值，内存为 {@code OVERSAMPLE * TAPS * 4} 字节。 */
    static final int OVERSAMPLE = 256;

    /**
     * 历史缓冲的帧容量。
     *
     * <p><strong>这个值必须大于「单次 {@link #push} 可能给到的最大块长」</strong>，否则超出的
     * 部分会被 {@code push} 静默丢弃，输出时长凭空变短。实测：4096 的容量下喂 4410 帧，
     * 只产出 4441 帧而不是应有的 4800 —— 丢的不是尾音，是中间整段。
     *
     * <p>常见无损格式的包长：FLAC 4096、ALAC 4096、WAV 块可能更大。取 8192 让一整包必有
     * 位置，并给 {@code pull} 到 {@link #reclaim} 之间留出周转余量。
     */
    static final int HISTORY_FRAMES = 8192;

    /**
     * 2π。等价于上游的 {@code core::f64::consts::TAU}：它按定义就是 {@code 2.0 * PI}，
     * 乘 2 在二进制浮点里是精确的，所以这里直接算出来与上游那个常量逐位相同。
     */
    private static final double TAU = 2.0 * Math.PI;

    /** 声道数。 */
    private final int channels;

    /** 每前进一个输出帧，输入位置前进多少帧。 */
    private final double step;

    /** 当前输出点对应的输入位置（相对 {@code history} 起点，单位：帧）。 */
    private double pos;

    /** 平面历史输入：{@code channels * HISTORY_FRAMES}。私有裸数组，见类注释。 */
    private final float[] history;

    /** {@code history} 中的有效帧数。 */
    private int histLen;

    /** 核表：{@code OVERSAMPLE * TAPS}。 */
    private final float[] kernel;

    private final int inRate;
    private final int outRate;

    /**
     * 构造 {@code inRate} → {@code outRate} 的重采样器。对应上游的 {@code Resampler::new}。
     *
     * @param inRate   输入采样率，必须大于 0
     * @param outRate  输出采样率，必须大于 0
     * @param channels 声道数，必须大于 0
     */
    public Resampler(int inRate, int outRate, int channels) {
        // 上游用 assert!（前置条件）：对应 IllegalArgumentException
        if (channels <= 0) {
            throw new IllegalArgumentException("声道数必须大于 0");
        }
        if (inRate <= 0 || outRate <= 0) {
            throw new IllegalArgumentException("采样率必须大于 0");
        }

        this.channels = channels;
        this.step = (double) inRate / (double) outRate;
        double cutoff;
        if (outRate < inRate) {
            // 降采样：收窄截止频率防止混叠
            cutoff = ((double) outRate / (double) inRate) * 0.95;
        } else {
            // 升采样：截止保持在奈奎斯特，避免高频提前衰减
            cutoff = 1.0;
        }
        this.kernel = buildKernel(cutoff);

        this.pos = 0.0;
        this.history = new float[channels * HISTORY_FRAMES];
        this.histLen = 0;
        this.inRate = inRate;
        this.outRate = outRate;
    }

    /**
     * 清空内部状态，准备接收一段<strong>全新</strong>的音频。
     *
     * <p>定位之后必须调用：历史缓冲里装的是跳转<strong>之前</strong>的声音，不清掉就会在
     * 新位置上先混进一小段旧内容 —— 听起来像「跳过去又弹回来」。
     *
     * <p>只清状态、不动核表 —— 核只跟采样率有关，让它重算是浪费。
     */
    public void reset() {
        this.histLen = 0;
        this.pos = 0.0;
    }

    /** 输入采样率。 */
    public int inRate() {
        return inRate;
    }

    /** 输出采样率。 */
    public int outRate() {
        return outRate;
    }

    /** 是否直通（两端采样率相同）。 */
    public boolean isPassthrough() {
        return inRate == outRate;
    }

    /** 折算需要的输入帧数：要产出 {@code outFrames} 帧，至少需要多少输入帧。 */
    public int neededInputFrames(int outFrames) {
        return (int) Math.ceil((double) outFrames * step) + TAPS;
    }

    /**
     * 喂入一块平面输入，返回<strong>实际接受</strong>的帧数。
     *
     * <p><strong>部分接收语义</strong>：历史缓冲满时，返回值会小于 {@code input.frames()}。
     * <strong>返回值必须检查</strong>（上游用 {@code #[must_use]} 在编译期强制，Java 做不到），
     * 并用 {@link #pushFrom} 把剩余部分补上。忽略返回值 = 静默丢帧，表现在输出时长凭空变短。
     */
    public int push(AudioBuf input) {
        return pushFrom(input, 0);
    }

    /**
     * 从 {@code input} 的第 {@code offset} 帧开始喂入，返回实际接受的帧数。
     *
     * <p>这是处理部分接收的唯一正确姿势：历史满了就先 {@link #pull} 腾空间，再用
     * {@code offset} 把没放下的部分补喂进来。
     *
     * <p><strong>返回值必须检查</strong>，并且必须用返回值推进 {@code offset}。
     */
    public int pushFrom(AudioBuf input, int offset) {
        // 上游是 debug_assert_eq!：只在 debug 构建生效。Java 没有这个区分，这里总是检查。
        if (input.channels() != channels) {
            throw new IllegalArgumentException(
                    "声道数不匹配：重采样器 " + channels + "，输入 " + input.channels());
        }
        // saturating_sub：offset 越过末尾时得到 0，而不是负数
        int available = Math.max(input.frames() - offset, 0);
        if (available == 0) {
            return 0;
        }
        reclaim();
        int room = HISTORY_FRAMES - histLen;
        int take = Math.min(available, room);
        if (take == 0) {
            return 0;
        }
        int start = histLen;
        for (int c = 0; c < channels; c++) {
            // 对应上游的 &input.channel(c)[offset..offset + take]：
            // 读视图长度 = 有效帧数，所以 offset + take <= frames 一定成立
            FloatBuffer src = input.channel(c);
            src.position(offset);
            src.get(history, c * HISTORY_FRAMES + start, take);
        }
        histLen += take;
        return take;
    }

    /**
     * 便捷入口：把 {@code input} 从头一直喂到喂不进去为止，返回实际接收的帧数。
     *
     * <p>上游没有这个具名函数，但它就是上游 {@code push} / {@code push_from} 文档里指定的
     * 唯一正确用法（「历史满了就先 pull 腾空间，再用 offset 把没放下的部分补喂进来」），
     * 也是上游测试里 {@code convert} 辅助函数的那套循环。Java 拿不到 {@code #[must_use]}，
     * 与其指望调用方手写这个循环，不如把循环本身收进来。
     *
     * <p><strong>返回值同样必须检查</strong>：等于 {@code input.frames()} 才算全部收下；
     * 小于它表示历史已满且期间没有 {@link #pull}，剩下的帧还在调用方手里，需要 pull 之后
     * 再用 {@link #pushFrom} 补喂（本方法不会自己去 pull —— pull 需要目的地缓冲，
     * 贸然拉干会把数据丢掉）。
     */
    public int pushAll(AudioBuf input) {
        int offset = 0;
        while (offset < input.frames()) {
            int accepted = pushFrom(input, offset);
            if (accepted == 0) {
                // 历史满了、腾不出地方：把已接收的帧数如实报回去，绝不假称全收下
                break;
            }
            offset += accepted;
        }
        return offset;
    }

    /**
     * 尽量产出，最多写满 {@code out}。返回写出的帧数。
     *
     * <p>返回 0 不代表结束，只代表历史里还不够算出下一个输出点，需要继续 {@code push}。
     * <strong>流真的结束了要用 {@link #drain}</strong>——{@code pull} 永远不会自己吐出
     * 末尾那 {@link #TAPS_PER_SIDE} 帧（它算每个输出点都要看到「未来输入」，而流末尾
     * 没有未来了），只有 {@code drain} 才会把那段按静音补齐后交付。
     */
    public int pull(AudioBuf out) {
        return pullInternal(out, false);
    }

    /**
     * 流末尾收尾：<strong>输入已确认结束</strong>时，把剩下的输出点全部拉出来。
     *
     * <p><strong>为什么需要这一步（上游没有）：</strong> {@link #pull} 每算一个输出点都要
     * 用到它<strong>前后各 {@link #TAPS_PER_SIDE} 帧</strong>输入，而流末尾永远凑不齐后面
     * 那 16 帧「未来输入」。于是 {@code pull} 会停在那儿不再产出，一段 24000 帧的音频只能
     * 给出 23984 帧——最后 16 帧被<strong>静默丢掉</strong>，表现是「进度永远差最后 16 帧」
     * （曲子 500 ms、位置卡在 499 ms）与「gapless 接缝整体早 0.33 ms」。
     * 上游 {@code sonalis-dsp/src/resample.rs} 里没有 drain / flush，上游
     * {@code sonalis-mix/src/deck.rs} 也只有 {@code drained} 标志、没有流末尾收尾，
     * 所以<strong>上游有同一个缺陷</strong>——它的测试里没有任何一条把「产出总帧数」与
     * {@code spec.total_frames} 对齐，因此一直没被发现（详见 {@code docs/audio-port.md}
     * 的「与上游的已知差异」表）。
     *
     * <p>{@link AudioBuf} 里原本就有「越界抽头不参与乘加」的处理，所以这里的语义可以写成
     * 一句话：<strong>输出点的存在性判据从「两侧抽头都落在历史里」放宽成「输出点落在真实
     * 输入范围内」，缺失的「未来输入」按静音（0）补齐。</strong>
     *
     * <p>与 {@link #pull} 的差别只有一处，且<strong>不改变任何一个本来就算得出来的输出
     * 点</strong>：能算的点用同一张核、同一段历史、同样的 0 填充参与乘加，两边逐位相同。
     * {@code drain} 多出来的只是 {@code pull} 因为看不到未来而放弃的那几个点。
     *
     * <p><strong>只在「输入确认结束」之后调用。</strong> 提前调用等于凭空截断一段还没喂完的
     * 音频——补上的静音会盖掉本该到来的真实采样。重复调用安全：第一次收尾后
     * {@code pos ≥ histLen}，第二次必然返回 0。
     *
     * <p><strong>同采样率下尾帧逐位精确，不需要任何容差。</strong> {@code step == 1.0}
     * 且 {@code pos} 自 0 起整步前进，所以每个输出点都落在相位 0 上，而相位 0 的核精确
     * 等于 delta 函数（{@link #buildKernel} 的归一化折算成 {@code 1.0f}，见
     * {@code ResamplerTest.phaseZeroKernelIsExactDelta}）。于是
     * {@code 输出 = history[i0] × 1.0f + Σ(旁瓣 × 相邻样本)}，而旁瓣的量级在 1e-17，
     * 与样本相加后必然舍入回原值——所以补零不会污染任何一个真实采样。
     * 这一点由 {@code ResamplerTest.drainFlushesTheTailSampleExactlyAtSameRate}
     * 用零容差断言钉住。
     *
     * <p><strong>跨采样率时末尾按零填充插值，这是有意的。</strong> 拿不到的输入不能凭空造；
     * 按静音补齐等价于「最后一个采样之后接着静音」，在幅度上是一段约
     * {@link #TAPS_PER_SIDE} 帧（48 kHz 下约 0.33 ms）的淡出，比把波形在末尾截成一个
     * 硬台阶（那会带来宽频爆音）更接近真实信号。libsamplerate / SoX / ffmpeg 的
     * {@code swr} 在处理流末尾时都是这个做法。
     *
     * @param out 输出缓冲，容量与声道数须与 {@link #pull} 的要求一致
     * @return 写入 {@code out} 的帧数；0 表示已经没有真实输入范围内的输出点了
     */
    public int drain(AudioBuf out) {
        return pullInternal(out, true);
    }

    /**
     * {@link #pull} 与 {@link #drain} 共用的实现。
     *
     * <p>两者唯一的差别是循环的退出判据：正常播放要等「未来输入」到位，收尾时不再等。
     * 乘加、相位、核表全都共用同一份代码——这是「{@code drain} 不会改变任何本来算得出来
     * 的输出点」这条保证的实现方式（写成两份循环迟早会漂移）。
     *
     * @param endOfStream 输入是否已经确认结束（{@code true} = 尾抽头按静音参与）
     */
    private int pullInternal(AudioBuf out, boolean endOfStream) {
        // 上游是 debug_assert_eq!；Java 侧总是检查（更严）
        if (out.channels() != channels) {
            throw new IllegalArgumentException(
                    "声道数不匹配：重采样器 " + channels + "，输出 " + out.channels());
        }
        int capacity = out.capacityFrames();
        int produced = 0;

        // 上游在热循环里每次取 out.channel_mut(c)；Java 取一次视图要包一层 FloatBuffer，
        // 每样本取一次会持续分配。这里只是把「创建视图」提到循环外，语义完全一样。
        FloatBuffer[] writers = new FloatBuffer[channels];
        for (int c = 0; c < channels; c++) {
            writers[c] = out.channelWriter(c);
        }

        while (produced < capacity) {
            // 历史为空时 hist_len - 1 会下溢（usize 减法）。
            //
            // 这曾经是个**真实 panic**：编排器里新开的 deck 第一次 render
            // 就是 pull，而那时历史里一个采样都还没有。
            // 底层的 pub API 不该因为调用顺序而崩，所以在这里挡住。
            if (histLen == 0) {
                break;
            }

            // 需要 index [i0 - TAPS_PER_SIDE + 1, i0 + TAPS_PER_SIDE]
            double i0 = Math.floor(pos);
            if (endOfStream) {
                // 收尾（drain）：只要求输出点落在真实输入范围内。
                // i0 >= histLen 时后面已经没有真实的输出点了。
                // 注意 i0 + TAPS_PER_SIDE 会越过 histLen —— 那些抽头由下面那个
                // idx 边界检查按 0 参与乘加，这就是「未来输入补零」。
                if (i0 >= (double) histLen) {
                    break;
                }
            } else {
                double highest = i0 + (double) TAPS_PER_SIDE;
                if (highest > (double) (histLen - 1)) {
                    break; // 历史不够，等更多输入
                }
            }

            double frac = pos - i0;
            int phase = (int) Math.min(Math.round(frac * (double) OVERSAMPLE), (long) (OVERSAMPLE - 1));
            // 上游取的是 &kernel[phase*TAPS..(phase+1)*TAPS]；这里按下标直接取，
            // 与切片逐元素等价，且不在热循环里复制数组
            int kernelRow = phase * TAPS;

            // pos 恒为非负（初值 0，只按正的 step 前进，reclaim 只会把它减到 frac + TAPS_PER_SIDE），
            // 所以这里的 (int) 转换与上游的 isize 转换一致，不会截断出错
            int base = (int) i0 - TAPS_PER_SIDE + 1;
            for (int c = 0; c < channels; c++) {
                int row = c * HISTORY_FRAMES;
                float acc = 0.0f;
                for (int k = 0; k < TAPS; k++) {
                    float coeff = kernel[kernelRow + k];
                    int idx = base + k;
                    if (idx >= 0 && idx < histLen) {
                        acc += history[row + idx] * coeff;
                    }
                }
                writers[c].put(produced, acc);
            }

            produced += 1;
            pos += step;
        }

        out.setFrames(produced);
        return produced;
    }

    /** 丢掉已经被消费掉的历史，把 {@code pos} 挪回起点。 */
    private void reclaim() {
        int consumed = (int) Math.floor(pos);
        // 至少保留 TAPS 帧给下一个输出点做插值
        int keepFrom = Math.max(consumed - TAPS_PER_SIDE, 0);
        if (keepFrom == 0) {
            return;
        }
        int keep = Math.max(histLen - keepFrom, 0);
        if (keep == 0) {
            histLen = 0;
            pos -= (double) keepFrom;
            return;
        }
        for (int c = 0; c < channels; c++) {
            int row = c * HISTORY_FRAMES;
            // 对应上游的 copy_within；arraycopy 与它一样正确处理重叠区间
            System.arraycopy(history, row + keepFrom, history, row, histLen - keepFrom);
        }
        histLen = keep;
        pos -= (double) keepFrom;
    }

    /**
     * 上游 {@code derive(Debug)}。这里照 {@link AudioBuf} 的先例，只报有用状态：
     * 核表有 8192 个浮点，刷进日志没有任何意义。
     */
    @Override
    public String toString() {
        return "Resampler{" + inRate + "→" + outRate + ", " + channels + "ch"
                + ", passthrough=" + isPassthrough()
                + ", histLen=" + histLen + ", pos=" + pos + '}';
    }

    /**
     * 构建归一化的多相窗函数 sinc 核表。对应上游模块内的 {@code build_kernel}
     * （上游是模块私有自由函数，按约定映射到本类的 {@code public static}）。
     */
    public static float[] buildKernel(double cutoff) {
        float[] kernel = new float[OVERSAMPLE * TAPS];

        for (int phase = 0; phase < OVERSAMPLE; phase++) {
            double frac = (double) phase / (double) OVERSAMPLE;
            double sum = 0.0;
            int row = phase * TAPS;

            for (int k = 0; k < TAPS; k++) {
                // 该抽头对应的输入样点相对输出点的距离
                double d = ((double) k - (double) TAPS_PER_SIDE + 1.0) - frac;
                double v = cutoff * sinc(cutoff * d) * blackmanHarris(d);
                kernel[row + k] = (float) v;
                sum += v;
            }

            // 归一化：保证直流增益严格为 1。
            if (Math.abs(sum) > 1e-12) {
                float inv = (float) (1.0 / sum);
                for (int k = 0; k < TAPS; k++) {
                    kernel[row + k] *= inv;
                }
            }
        }

        return kernel;
    }

    /** 归一化 sinc。对应上游的 {@code sinc}。 */
    public static double sinc(double x) {
        if (Math.abs(x) < 1e-12) {
            return 1.0;
        }
        double px = Math.PI * x;
        return Math.sin(px) / px;
    }

    /**
     * Blackman-Harris 窗，定义域收在抽头范围内。对应上游的 {@code blackman_harris}。
     *
     * <p>{@code d} 是抽头到插值点的距离（单位：帧），归一化到 {@code r = |d| / TAPS_PER_SIDE}，
     * 取值 {@code [0, 1)}。窗在<strong>中心（r=0）取 1</strong>、在<strong>边缘（r→1）趋于 0</strong>。
     *
     * <p>注意标准 4 项 Blackman-Harris 的 {@code a1} 与 {@code a3} 项<strong>带负号</strong>。
     * 这两处符号写反会让整个窗倒置——中心几乎没有权重、边缘权重最大。
     * 由于核随后会做归一化，这种错误<strong>不会</strong>被直流测试发现（增益被强行拉回 1），
     * 只会让频响变坏、并让位完美在最低有效位上失效。
     * 所以 {@code windowShapeIsNotInverted} 那条测试是必需的，不是多余的。
     */
    public static double blackmanHarris(double d) {
        double r = Math.abs(d) / (double) TAPS_PER_SIDE;
        if (r >= 1.0) {
            return 0.0;
        }
        double a0 = 0.35875;
        double a1 = 0.48829;
        double a2 = 0.14128;
        double a3 = 0.01168;
        // 把 r ∈ [-1, 1] 映射到 u ∈ [0, 1]：中心 r=0 对应 u=0.5。
        // 于是 t = 2πu 就是标准式里的 2πn/(N-1)。
        double u = (r + 1.0) * 0.5;
        double t = TAU * u;
        return a0 - a1 * Math.cos(t) + a2 * Math.cos(2.0 * t) - a3 * Math.cos(3.0 * t);
    }
}
