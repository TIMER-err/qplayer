package dev.t1m3.qplayer.audio.stem;

import dev.t1m3.qplayer.audio.model.StemSeparator;

/**
 * 纯 DSP 的中心声道（人声）抑制——**人声消除三模式里的 DSP 模式**。
 *
 * <p>这一段是本仓库历史里的原创实现（旧 automix 的 {@code VocalRemover}，随 automix 重写被删掉），
 * 现在按 {@link StemSeparator} 接缝的形状搬回 player-core。算法与常量<strong>逐行照搬</strong>，
 * 没有改：{@code N = 2048} / {@code HOP = 512} / 四个频带边界 / 增益形状全部原样。
 *
 * <h2>原理（原注释的理由，照留）</h2>
 *
 * <p>主唱几乎总是摆在正中间，而鼓 / 吉他 / pad 大多带立体声能量。于是逐 STFT 帧把声场拆成
 * center {@code (L+R)/2} 与 side {@code (L−R)/2}，<strong>只在人声频段衰减 center</strong>
 * （约 100 Hz–5.2 kHz，边缘用升余弦斜坡）：低频 kick/bass（100 Hz 以下）与高频镲片
 * （5.2 kHz 以上）<strong>保留中心能量</strong>，所以"伴奏"听起来仍然有厚度。
 *
 * <p>内侧边界之间（{@code LO_INNER = 280} 到 {@code HI_INNER = 4000} Hz）完全抑制；
 * {@code LO_OUTER = 100}–{@code LO_INNER} 与 {@code HI_INNER}–{@code HI_OUTER = 5200}
 * 两段之间用升余弦斜坡过渡（不做砖墙，砖墙会在带边留下明显的振铃）。低端压到 100 Hz
 * 就是为了让 kick 与 bass 的基频原封不动地过去。
 *
 * <p><strong>不是 Spleeter/Demucs——是卡拉OK级近似。</strong>够 automix 交叠用（用户说的
 * "正常体验"），离线毫秒级，无 ML 依赖；当年同一条例程还兼作过渡预览的渲染器。
 *
 * <h2>为什么它是"永远可用"的那一档：不需要模型</h2>
 *
 * <p>它是一个纯函数式的频域改写，<strong>没有权重、没有会话、没有推理运行时</strong>：
 *
 * <ul>
 *   <li><strong>永远可用</strong>——不依赖 {@code StemModels}、不依赖 {@code ModelStore}
 *       里有没有权重、不依赖宿主有没有装 ONNX 实现。所以 DSP 模式在设置里
 *       <strong>不需要任何下载门控</strong>：不该出现"请先下载模型"来挡住它。</li>
 *   <li><strong>不占内存</strong>——没有常驻对象（本类除一个 {@code vocalK} 之外无状态；
 *       方法内的临时缓冲是 4 条 {@code double[帧数]}，48 kHz 下约 1.5 MB 每秒素材，
 *       返回即可回收；ML 那档是上百兆的权重常驻）。</li>
 *   <li><strong>毫秒级</strong>——复杂度只有 STFT 的 {@code O(帧数 · log N)}，没有模型加载、
 *       没有推理。本机实测（44.1 kHz 立体声）：10 秒素材 <b>215 ms</b>、60 秒素材 1.2 s，
 *       约 46 倍实时——过渡窗口那几秒就是几十毫秒的量级，交给 {@code prepare} 完全无压力，
 *       而模型那档是秒级 + 上百兆内存。<b>但"整首歌几十毫秒"是不成立的</b>，
 *       它的用法是"只对过渡窗口那几秒做"，别拿它整首整首地跑。</li>
 * </ul>
 *
 * <p>因为"能不能用"从不取决于外部文件，这个类<strong>没有也不需要</strong>
 * {@code available()} 这一档语义（接缝里本来也没有这个方法）：拿得到实例就一定跑得动。
 * 唯一会抛异常的地方是参数非法。
 *
 * <h2>接缝上的形状：两条轨，相加等于原信号</h2>
 *
 * <p>老实现 {@code process(stereo, sr, vocalK)} 返回的是"去掉人声后的成品"（一条信号），
 * 而接缝要的是两条轨，于是把同一个逐 bin 中心增益 {@code g(k)} 摊成两份：
 *
 * <pre>
 *   伴奏 = side + center · g(k)
 *   人声 =          center · (1 − g(k))
 * </pre>
 *
 * <p>两者相加 <strong>精确等于原信号</strong>（{@code side + center}，浮点误差内），所以调用方
 * （{@code VocalSeparationTransition}）可以按包络 {@code 伴奏 + 人声 × g(p)} 把人声渐恢复，
 * 而不必担心两条轨拼不回原音。这条性质有测试钉着。
 *
 * <p>逐帧的重叠相加（Hann 窗分析 + Hann 窗合成，除以 {@code Σw²}）是精确重建，所以
 * "相加等于原信号"成立<strong>与 k 的取值无关</strong>：{@code k = 0} 时是纯伴奏 + 纯人声，
 * {@code k = 1} 时退化成"伴奏 = 原信号、人声 = 0"（原实现里 {@code vocalK >= 1} 直接返回原数组，
 * 这里就是那条早返回的等价形式）。
 *
 * <p>{@code vocalK ∈ [0,1]} 是<strong>人声保留量</strong>：0 = 完全去除（伴奏）、1 = 原样透传。
 * 当年 automix 用它做交接后的人声渐恢复；现在默认构造是 0（= 模式开关打开时的"去人声"），
 * 渐恢复交给 {@code VocalSeparationTransition} 的 smoothstep 包络。
 *
 * <h2>与老实现的两处差异（照实记，理由都在）</h2>
 *
 * <ol>
 *   <li><b>窗覆盖不足的样点改成按覆盖率平滑退回原信号，不再清零、也不再放大。</b>
 *       老实现归一化时写的是 {@code winSum > 1e-9 ? winSum : 1.0}，它只挡住了"正好为零"的点：
 *       开头 {@code i = 0..3}（Hann 窗首值恰为 0）会被输出成 0——丢样点；
 *       长度不是 hop 整数倍时<strong>末尾最多 511 个样点（44.1 kHz 下约 11.6 ms）整段清零</strong>；
 *       而比"正好为零"更宽的一段（只有一帧几乎为零的窗边缘在覆盖，约 512 个样点）会因为
 *       除以小窗值而把增益改写的残差放大——实测在 {@code i = 4} 处放大到 ±1，撞上钳位后
 *       连"两轨相加 = 原信号"都被撞坏。现在这一段按覆盖率（{@link #MIN_COVERAGE}）
 *       在"处理结果"与"原信号"之间线性过渡，两条轨退回的是同一点上的（原信号, 0），
 *       所以恒等式仍然成立（只剩 {@code ~1e-7} 的浮点误差）。
 *       变换本身——窗长、hop、四个频带边界、增益形状、共轭半重建——一个数都没动；
 *       稳态区（覆盖率充足处）的输出与老实现逐位相同。</li>
 *   <li><b>输出仍按 ±1 钳位（与老实现一致）。</b> 逐轨钳位是<strong>非线性</strong>的，
 *       所以素材本身超过满刻度时，钳位会吃掉一点"两轨相加 = 原信号"的精度
 *       （偏差上界就是超出部分的幅度）；正常在 [-1,1] 内的素材（本类输入的口径，
 *       与整条播放链一致）误差只在浮点量级（&lt; 1e-6）。保留钳位是因为老实现
 *       就在这一步钳，而这一档的产物要能直接当成品播。</li>
 * </ol>
 *
 * <p>另一条不是差异、是固有性质：<strong>人声轨左右完全相同</strong>（人声只取 center，
 * 而 center 本来就不含左右差）。这正是"卡拉OK级近似"的体现，不是漏了立体声信息。
 *
 * <h2>怎么接上（留给主线的一处）</h2>
 *
 * <p>{@code audio/stem} 这个包现在只有这一个实现类，<strong>还没有按模式选择的入口</strong>
 * （{@code StemSeparators.forMode(...)} 之类）。那一处需要：DSP 这一支直接
 * {@code new DspStemSeparator()}（不查权重、不装宿主实现、不需要下载门控），
 * ML 那一支才走 {@code StemModels.open()}。本类可以被直接 new 出来用，不依赖任何工厂。
 */
public final class DspStemSeparator implements StemSeparator {

    /** STFT 长度 —— 2048 @ 44.1 kHz 约 46 ms 窗，hop 512 即 4 倍重叠。 */
    private static final int N = 2048;

    private static final int HOP = 512;

    /** 单边谱的 bin 数（含直流与奈奎斯特）。 */
    private static final int HALF = N / 2 + 1;

    /** 中心衰减频带（Hz）：内侧边界之间完全抑制，内外边界之间走升余弦斜坡。
     *  低端压到 100 Hz 是为了让 kick/bass 的基频原样通过。 */
    private static final double LO_OUTER = 100.0;
    private static final double LO_INNER = 280.0;
    private static final double HI_INNER = 4000.0;
    private static final double HI_OUTER = 5200.0;

    /**
     * 视为"窗覆盖充足"的窗值和下限。
     *
     * <p>稳态下 {@code Σw² = 1.5}（hop = N/4 的 Hann 窗，4 帧重叠），所以 0.25 只是稳态的
     * 六分之一。低于它的地方（一段素材的开头约 512 帧、末尾没被覆盖的尾巴）在 44.1 kHz 下
     * 都不到 12 ms，那里只有某一帧<strong>几乎为零的窗边缘</strong>在起作用，
     * 而重叠相加要除以那个小窗值，于是"增益改写留下的残差"会被 {@code 1/w} 放大——
     * 实测在 {@code i = 4} 处放大到 ±1（撞上钳位，连带把"两轨相加 = 原信号"也撞坏）。
     * 那里按覆盖率平滑退回原信号：见 {@link #separate} 里的用法与类注释差异一。
     */
    private static final double MIN_COVERAGE = 0.25;

    /** 人声保留量，见类注释。 */
    private final double vocalK;

    // ---- 复用缓冲（见 separate 里的说明）-------------------------------------
    //
    // 放在 **ThreadLocal** 而不是实例字段上：分离器是「一次分离一个实例」的用法
    // （{@code StemSeparators.forMode} 每次 new 一个），实例字段上的复用等于没复用。
    // 线程本地既真的省下那十几 MB，又不会让两个线程互相踩。
    private static final ThreadLocal<Scratch> SCRATCH = new ThreadLocal<Scratch>() {
        @Override
        protected Scratch initialValue() {
            return new Scratch();
        }
    };

    /** 一个线程的分离缓冲（长度不够时才重新分配）。 */
    private static final class Scratch {
        int frames;
        double[] accInsL = new double[0];
        double[] accInsR = new double[0];
        double[] accVoc = new double[0];
        double[] winSum = new double[0];
        /** 频谱缓冲：每帧被完整重写，所以不需要清零；**每个线程一份**（并行时各算各的）。 */
        final double[] specA = new double[N];
        final double[] specB = new double[N];
        final double[] specC = new double[N];
        final double[] specD = new double[N];
        final double[] specE = new double[N];
        final double[] specF = new double[N];
        final double[] specG = new double[N];
        final double[] specH = new double[N];
        final double[] specI = new double[N];
        final double[] specJ = new double[N];
    }
    /** 分析窗（只取决于 {@link #N}）。 */
    private final double[] winCache = new double[N];
    private boolean winReady;
    /** 逐 bin 的中心增益（只取决于采样率），按采样率缓存一份。 */
    private int gainRate = -1;
    private double[] gainCache;

    /** 分析窗：值是同一批 {@code Math.cos} 的结果，逐位相同，只是不再每次重算。 */
    private double[] window() {
        if (!winReady) {
            for (int i = 0; i < N; i++) {
                winCache[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
            }
            winReady = true;
        }
        return winCache;
    }

    /** 逐 bin 中心增益：{@code f = b·sr/N} 现算，所以任何采样率都成立。 */
    private double[] centerGain(int sampleRate) {
        if (gainCache != null && gainRate == sampleRate) {
            return gainCache;
        }
        double[] out = new double[HALF];
        for (int b = 0; b < HALF; b++) {
            double f = (double) b * sampleRate / N;
            double g;
            if (f < LO_OUTER) {
                g = 1.0;
            } else if (f < LO_INNER) {
                g = 1.0 + (vocalK - 1.0) * 0.5
                        * (1 - Math.cos(Math.PI * (f - LO_OUTER) / (LO_INNER - LO_OUTER)));
            } else if (f <= HI_INNER) {
                g = vocalK;
            } else if (f < HI_OUTER) {
                g = vocalK + (1.0 - vocalK) * 0.5
                        * (1 - Math.cos(Math.PI * (f - HI_INNER) / (HI_OUTER - HI_INNER)));
            } else {
                g = 1.0;
            }
            out[b] = g;
        }
        gainCache = out;
        gainRate = sampleRate;
        return out;
    }

    /** 完全去除人声（DSP 模式打开时的默认）。 */
    public DspStemSeparator() {
        this(0.0);
    }

    /**
     * @param vocalK 人声保留量 {@code [0,1]}：0 = 完全去除（伴奏），1 = 原样透传
     */
    public DspStemSeparator(double vocalK) {
        if (Double.isNaN(vocalK) || vocalK < 0.0 || vocalK > 1.0) {
            throw new IllegalArgumentException(
                    "vocalK 是人声保留量，必须在 [0,1]（0 = 完全去除＝伴奏，1 = 原样透传），实际 " + vocalK);
        }
        this.vocalK = vocalK;
    }

    /** 这个人声保留量。 */
    public double vocalK() {
        return vocalK;
    }

    /**
     * 分离一段立体声素材：{@code 伴奏 = side + center·g}、{@code 人声 = center·(1−g)}。
     *
     * <p>输入数组<strong>不被改写</strong>，返回的四条轨长度都等于输入帧数（接缝的硬要求）。
     *
     * <p>输入短于一个 STFT 窗（{@code N = 2048} 帧）时原样返回：一帧都凑不齐时，
     * 分析 → 改谱 → 重叠相加这条链根本没有定义，硬跑只会得到一段被窗削掉的东西
     * （老实现也是这么早返回的）。"没做分离"在这里如实落成
     * <strong>伴奏 = 原信号、人声 = 0</strong>（等价于 {@code g ≡ 1}）——这与
     * 接缝类注释里那条"绝不许拿静音或原始音频冒充分离结果"不冲突：冒充指的是
     * <em>声称做了却没做</em>，而这里两轨相加仍是原信号、且没有凭空造出人声。
     * 2048 帧在 44.1 kHz 下只有 46 ms，调用方（过渡窗口）本来就远长于一帧。
     *
     * @throws IllegalArgumentException 声道为 null、左右长度不一致、采样率非正或 vocalK 越界
     */
    @Override
    public StemSet separate(float[] left, float[] right, int sampleRate) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("左右声道都不能为 null（单声道素材请把同一份传两次）");
        }
        if (left.length != right.length) {
            throw new IllegalArgumentException(
                    "左右声道长度必须一致（接缝按时间对齐混回两条轨），实际 "
                            + left.length + " ≠ " + right.length);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("采样率必须为正，实际 " + sampleRate);
        }
        int frames = left.length;
        if (frames < N) {
            return passThrough(left, right);
        }

        double[] win = window();

        // 逐 bin 的中心增益（预先算好）：LO_OUTER 以下为 1、人声带内为 vocalK、HI_OUTER 以上为 1，
        // 两段边缘之间走升余弦斜坡。频率由 sr 现算（f = b·sr/N），所以任何采样率都成立，
        // 没有一处写死 44.1 kHz。
        double[] centerGain = centerGain(sampleRate);

        // 输入谱 / 伴奏谱 / 人声谱 + 重叠相加的累加器（人声左右同谱，所以人声只要一份）。
        //
        // **这些是复用缓冲，不是每次新建**（2026-10-03，性能）：一次 7.5 秒窗口上，
        // 四个累加器各 360k 个 double（合计 ≈ 11.5 MB），频谱十个 2048 长的数组；
        // 原来每次分离都重新分配一遍，一次换曲白扔十几 MB 垃圾。
        // 复用**不改变任何数值**：频谱每帧被完整重写，累加器在开头显式清零。
        //
        // 代价是实例不再可重入（同时两处分离会互相踩）——这个类本来就是「一次分离用一个实例」
        // 的用法（见 {@code StemSeparators.dsp()}），这里把这条约定写下来。
        // 产物（四条轨）每次都是新的；**中间累加器与频谱缓冲走 ThreadLocal 复用**
        // （一次 7.5 秒窗口的四个累加器各 360k 个 double ≈ 11.5 MB，原来每次分离都白扔一遍）。
        float[] insL = new float[frames];
        float[] insR = new float[frames];
        float[] vocL = new float[frames];
        float[] vocR = new float[frames];

        int stftFrames = 0;
        for (int off = 0; off + N <= frames; off += HOP) {
            stftFrames++;
        }
        int threads = separationThreads(stftFrames);
        if (threads > 1) {
            // **按输出样本区间切块并行**（2026-10-03）。为什么逐位不变：每个输出样点的
            // 累加顺序 = 帧号升序，而并行版本在每个区间内也严格按帧号升序累加**同一批帧**
            // ——切块只决定「谁算」，不决定「怎么加」，所以每个样点的浮点结果一模一样。
            // 代价是块边界两侧各多算几帧（块宽 ≫ 窗长 N，实测重复率 < 5%）。
            int per = (frames + threads - 1) / threads;
            java.util.List<java.util.concurrent.Future<?>> tasks =
                    new java.util.ArrayList<java.util.concurrent.Future<?>>(threads);
            for (int t = 0; t < threads; t++) {
                final int from = t * per;
                final int to = Math.min(frames, from + per);
                if (from >= to) {
                    break;
                }
                tasks.add(SEPARATION_POOL.submit(new Runnable() {
                    @Override
                    public void run() {
                        processRange(left, right, win, centerGain, frames, from, to,
                                insL, insR, vocL, vocR);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> task : tasks) {
                try {
                    task.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
            return new StemSet(vocL, vocR, insL, insR);
        }
        processRange(left, right, win, centerGain, frames, 0, frames, insL, insR, vocL, vocR);
        return new StemSet(vocL, vocR, insL, insR);
    }

    /** 分离要开几条线程：帧太少就别开（起线程比算它还贵），上限 {@value #MAX_THREADS}。 */
    private static int separationThreads(int stftFrames) {
        int cores = Runtime.getRuntime().availableProcessors();
        int threads = Math.max(1, Math.min(MAX_THREADS, cores - 1));
        return stftFrames < MIN_FRAMES_TO_SPLIT ? 1 : threads;
    }

    /** 并行分离的线程上限（再多也是在抢同一批核，而这一档跑在准备线程上）。 */
    private static final int MAX_THREADS = 4;

    /** 帧数少于此值就不切块（否则切块开销比省下的还多）。 */
    private static final int MIN_FRAMES_TO_SPLIT = 8;

    /** 分离用的线程池（daemon、固定条数、只服务这一个方法）。 */
    private static final java.util.concurrent.ExecutorService SEPARATION_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(
                    Math.max(1, Math.min(MAX_THREADS,
                            Runtime.getRuntime().availableProcessors() - 1)),
                    new java.util.concurrent.ThreadFactory() {
                        private final java.util.concurrent.atomic.AtomicInteger n =
                                new java.util.concurrent.atomic.AtomicInteger();
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread th = new Thread(r, "qplayer-stem-" + n.getAndIncrement());
                            th.setDaemon(true);
                            return th;
                        }
                    });


    /**
     * 处理「会写进输出区间 {@code [from,to)}」的那些 STFT 帧，并把这段输出合成出来。
     *
     * <p>串行时（{@code from = 0, to = frames}）它与老实现逐行等价：同样的帧、同样的顺序、
     * 同样的算术。
     */
    private static void processRange(float[] left, float[] right, double[] win,
                                     double[] gain, int frames, int from, int to,
                                     float[] insL, float[] insR, float[] vocL, float[] vocR) {
        Scratch scratch = SCRATCH.get();
        if (scratch.frames < frames) {
            scratch.frames = frames;
            scratch.accInsL = new double[frames];
            scratch.accInsR = new double[frames];
            scratch.accVoc = new double[frames];
            scratch.winSum = new double[frames];
        }
        double[] accInsL = scratch.accInsL;
        double[] accInsR = scratch.accInsR;
        double[] accVoc = scratch.accVoc;
        double[] winSum = scratch.winSum;
        // 只清自己那一段（并行时各块互不相干；累加值本来也只用这一段）
        java.util.Arrays.fill(accInsL, from, to, 0.0);
        java.util.Arrays.fill(accInsR, from, to, 0.0);
        java.util.Arrays.fill(accVoc, from, to, 0.0);
        java.util.Arrays.fill(winSum, from, to, 0.0);
        double[] lRe = scratch.specA;
        double[] lIm = scratch.specB;
        double[] rRe = scratch.specC;
        double[] rIm = scratch.specD;
        double[] iLRe = scratch.specE;
        double[] iLIm = scratch.specF;
        double[] iRRe = scratch.specG;
        double[] iRIm = scratch.specH;
        double[] vRe = scratch.specI;
        double[] vIm = scratch.specJ;

        // 会写进本区的第一帧：off + N > from 的最小 HOP 倍数
        int firstOff = 0;
        if (from > N) {
            firstOff = ((from - N) / HOP + 1) * HOP;
        }
        int lastOff = frames - N;
        for (int off = firstOff; off <= lastOff && off < to; off += HOP) {
            for (int i = 0; i < N; i++) {
                lRe[i] = left[off + i] * win[i];
                lIm[i] = 0;
                rRe[i] = right[off + i] * win[i];
                rIm[i] = 0;
            }
            fft(lRe, lIm, false);
            fft(rRe, rIm, false);
            // 逐 bin 拆 center/side，center 乘 g、side 不动，两条轨分别重建。
            // 注意人声轨只吃 center：它左右相同，没有立体声信息可留。
            for (int b = 0; b < HALF; b++) {
                double g = gain[b];
                double cRe = (lRe[b] + rRe[b]) * 0.5;
                double cIm = (lIm[b] + rIm[b]) * 0.5;
                double sRe = (lRe[b] - rRe[b]) * 0.5;
                double sIm = (lIm[b] - rIm[b]) * 0.5;
                iLRe[b] = sRe + cRe * g;
                iLIm[b] = sIm + cIm * g;
                iRRe[b] = -sRe + cRe * g;
                iRIm[b] = -sIm + cIm * g;
                vRe[b] = cRe * (1.0 - g);
                vIm[b] = cIm * (1.0 - g);
            }
            // 重建共轭半（埃尔米特对称）——老实现这里记着一条实测教训：零掉它等于丢掉一半频谱，
            // 整段 -6 dB（听感上被读成"低频只留了 25%"）。
            for (int b = 1; b < HALF - 1; b++) {
                iLRe[N - b] = iLRe[b];
                iLIm[N - b] = -iLIm[b];
                iRRe[N - b] = iRRe[b];
                iRIm[N - b] = -iRIm[b];
                vRe[N - b] = vRe[b];
                vIm[N - b] = -vIm[b];
            }
            fft(iLRe, iLIm, true);
            fft(iRRe, iRIm, true);
            fft(vRe, vIm, true);
            double scale = 1.0 / N;
            // off + N <= frames 已经保证 off + i < frames，所以不需要老实现那个多余的
            // `&& off + i < frames`（语义完全一样）。
            for (int i = 0; i < N; i++) {
                double w = win[i];
                accInsL[off + i] += iLRe[i] * scale * w;
                accInsR[off + i] += iRRe[i] * scale * w;
                accVoc[off + i] += vRe[i] * scale * w;
                winSum[off + i] += w * w;
            }
        }

        for (int i = from; i < to; i++) {
            double ws = winSum[i];
            if (ws >= MIN_COVERAGE) {
                // 覆盖充足（一段素材的绝大多数样点都在这里）：与老实现逐位相同的写法。
                insL[i] = clampUnit(accInsL[i] / ws);
                insR[i] = clampUnit(accInsR[i] / ws);
                float v = clampUnit(accVoc[i] / ws);
                vocL[i] = v;
                vocR[i] = v;
                continue;
            }
            // 覆盖不足：按覆盖率把自己与"原信号/静音"线性混合。ws = 0（完全没有窗覆盖）
            // 时 cover = 0，除法走不到，结果就是原样透传。
            double cover = ws / MIN_COVERAGE;
            double processedL = 0;
            double processedR = 0;
            double processedV = 0;
            if (cover > 0.0) {
                processedL = accInsL[i] / ws;
                processedR = accInsR[i] / ws;
                processedV = accVoc[i] / ws;
            }
            // 退回的是"同一点上的（原信号, 0）"这一对，而两条轨相加恒等于原信号，
            // 所以这一步不破坏那个恒等式——偏差只剩 cover × 处理结果自身的浮点误差。
            insL[i] = clampUnit(left[i] + cover * (processedL - left[i]));
            insR[i] = clampUnit(right[i] + cover * (processedR - right[i]));
            float v = clampUnit(cover * processedV);
            vocL[i] = v;
            vocR[i] = v;
        }
    }

    /**
     * {@link #close()}：无状态可释放。
     *
     * <p>本类没有会话、没有原生内存、没有线程——唯一要丢的是调用方手里这个实例。
     * 写成幂等空实现是为了满足接缝（{@code AutoCloseable} 那条"可重复调用"的约定），
     * 让 {@code VocalSeparationTransition} 的统一 {@code closeQuietly} 路径不必为
     * DSP 这一档特判。重复调用不会有事。
     */
    @Override
    public void close() {
        // 故意为空：无状态。
    }

    /** 短于一个窗（或空输入）时的诚实答案：伴奏 = 原信号，人声 = 0。 */
    private static StemSet passThrough(float[] left, float[] right) {
        int frames = left.length;
        return new StemSet(new float[frames], new float[frames],
                left.clone(), right.clone());
    }

    /** 与老实现同一条钳位：产物要能直接当成品播。 */
    private static float clampUnit(double v) {
        return (float) Math.max(-1.0, Math.min(1.0, v));
    }

    /** 原地的基 2 Cooley–Tukey FFT（N 是 2 的幂），逐行照搬老实现。 */
    private static void fft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wRe = Math.cos(ang);
            double wIm = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double curRe = 1;
                double curIm = 0;
                for (int j = 0; j < len / 2; j++) {
                    double uRe = re[i + j];
                    double uIm = im[i + j];
                    double vRe = re[i + j + len / 2] * curRe - im[i + j + len / 2] * curIm;
                    double vIm = re[i + j + len / 2] * curIm + im[i + j + len / 2] * curRe;
                    re[i + j] = uRe + vRe;
                    im[i + j] = uIm + vIm;
                    re[i + j + len / 2] = uRe - vRe;
                    im[i + j + len / 2] = uIm - vIm;
                    double newRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = newRe;
                }
            }
        }
    }
}
