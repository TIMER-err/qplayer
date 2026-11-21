package dev.t1m3.qplayer.audio.dsp;

/**
 * 基 2 快速傅里叶变换。
 *
 * <h3>为什么自己写</h3>
 *
 * <p>自动混音要用到频谱通量（拍点检测）、频带切分与滤波扫频。引一个 FFT 库
 * 意味着再背一棵依赖树（上游那台机器的依赖下载已知不稳）；而一个迭代式基 2
 * 复 FFT 只有几十行，且<strong>可验证</strong>：冲激的频谱必须平坦、单频正弦必须落在
 * 唯一一个 bin 上、Parseval 能量必须守恒。三条测试就能把它钉住。
 *
 * <h3>定位：自动混音（automix）的分析底座</h3>
 *
 * <p>{@code sonalis-mix} 的拍点检测（频谱通量 + 自相关 + 相位折叠）与判调性都建立在
 * 它上面：{@code analysis.rs} 每帧做 {@link #forward} 后取 {@link #magnitude} 得单边幅度谱，
 * 帧间做差得到起始包络，再对包络做 {@link #autocorrelate} 估周期。{@link #hann}
 * 还被 {@code pitch.rs}（颗粒变调）复用，所以公开 API 完整保留、命名与上游一一对应，
 * 不要改名或"顺手"合并。
 *
 * <h3>性能取向</h3>
 *
 * <p>变换的旋转因子与位反转表在构造时算好，{@link #forward} 里只做寄存器级运算，
 * <strong>不分配、不查表越界</strong>。音频线程之外的离线分析用它没有实时性压力，
 * 但仍按实时安全的标准写，免得日后被搬进回调。
 *
 * <h3>与上游的类型差异（按 docs/audio-port.md 记录）</h3>
 *
 * <ul>
 *   <li>上游的 {@code &[f32]} / {@code &mut [f32]} 按约定一般映射成
 *       {@link java.nio.FloatBuffer}，这里用裸 {@code float[]}：变换是逐样本访问的热循环
 *       （上游注释里就写着"只做寄存器级运算"），且位反转置换要按下标原地交换，
 *       {@code FloatBuffer} 的视图语义在这里只会多一层绕路。长度约定与上游一致：
 *       {@code re} / {@code im} 的长度必须<strong>恰好等于</strong> {@link #len()}。
 *       上游两处调用点（{@code analysis.rs} 的 {@code onset_envelope}、{@code pitch.rs}
 *       的 {@code PitchShifter::new}）都是对整段本地 {@code Vec<f32>} 调 {@code hann}，
 *       没有子切片调用，所以不需要 offset/length 版本。</li>
 *   <li>上游 {@code #[derive(Clone)]}：这里<strong>不做</strong>复制构造——本类构造完就不可变
 *       （三张表算好后再不改动），多线程共享同一个实例是安全的，复制只会白烧内存。</li>
 *   <li>所有 {@code Math.*}（{@code sin}/{@code cos}/{@code sqrt}）的结果<strong>立即</strong>
 *       {@code (float)} 转换：Rust 那边是 {@code f32::sin} 等，走 double 再转会让频谱结果
 *       出现可见偏差（旋转因子差 1 ulp 会一路传到 bin 上）。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_dsp::fft}。
 */
public final class Fft {

    /**
     * {@code core::f32::consts::PI} 的 f32 镜像。
     *
     * <p>{@link Math#PI} 是 double，直接参与运算会把整条相位链拉到 double 上算，
     * 与上游的 f32 结果就不逐位对照了。
     */
    private static final float PI = (float) Math.PI;

    private final int n;

    /** 位反转置换表。 */
    private final int[] rev;

    /** 旋转因子实部，长度 {@code n / 2}。 */
    private final float[] cos;

    /** 旋转因子虚部（已含负号，正向变换用），长度 {@code n / 2}。 */
    private final float[] sin;

    /**
     * 构造一个长度为 {@code n} 的变换器。{@code n} 必须是 2 的幂且不为零。
     *
     * <p>上游这里 {@code panic!}：非 2 的幂或为 0 是<strong>编程错误</strong>，不是运行时输入
     * 问题，静默取整会让频谱莫名其妙地错。Java 侧对应 {@link IllegalArgumentException}，
     * 消息保持上游文本（含"2 的幂"，便于按上游 {@code should_panic(expected = "2 的幂")} 核对）。
     *
     * @param n 变换长度，必须是 2 的幂
     * @throws IllegalArgumentException {@code n} 非 2 的幂或小于等于 0
     */
    public Fft(int n) {
        if (n <= 0 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("FFT 长度必须是 2 的幂，收到 " + n);
        }

        // 上游是 n.trailing_zeros()（usize → u32），Java 对应 Integer.numberOfTrailingZeros。
        int bits = Integer.numberOfTrailingZeros(n);
        int[] rev = new int[n];
        for (int i = 0; i < n; i++) {
            // 上游用 u32 存中间量；i 非负，Java 的 >> 与 u32 的逻辑右移等价。
            int x = i;
            int v = 0;
            for (int b = 0; b < bits; b++) {
                v = (v << 1) | (x & 1);
                x >>= 1;
            }
            rev[i] = v;
        }

        int half = n / 2;
        float[] cos = new float[half];
        float[] sin = new float[half];
        for (int k = 0; k < half; k++) {
            // 全程 f32：2.0f * PI（两个 f32 量的乘）* k / n，与上游的
            // `2.0 * PI * k as f32 / n as f32` 结合顺序、类型一致。
            float theta = 2.0f * PI * k / n;
            cos[k] = (float) Math.cos(theta);
            // 负号直接写进表里——正向变换就不用每次取负。
            sin[k] = -(float) Math.sin(theta);
        }

        this.n = n;
        this.rev = rev;
        this.cos = cos;
        this.sin = sin;
    }

    /** 变换长度。 */
    public int len() {
        return n;
    }

    /**
     * 长度恒不为零，仅为满足上游 clippy 的成对约定（{@code len}/{@code is_empty} 要成对出现）。
     *
     * <p>留着它还有实用价值：调用方可以无条件地写 {@code if (fft.isEmpty()) ...} 而不用查表长度。
     */
    public boolean isEmpty() {
        return false;
    }

    /**
     * 正向变换（原地，分离实虚部）。
     *
     * <p>{@code re} 与 {@code im} 长度都必须等于 {@link #len()}。
     */
    public void forward(float[] re, float[] im) {
        transform(re, im, false);
    }

    /**
     * 逆向变换（原地）。结果未归一化，需调用方除以 {@code n}。
     *
     * <p>（这里已经按上游做了：变换完统一乘 {@code 1/n}。）
     */
    public void inverse(float[] re, float[] im) {
        transform(re, im, true);
        float inv = 1.0f / n;
        for (int i = 0; i < re.length; i++) {
            re[i] *= inv;
        }
        for (int i = 0; i < im.length; i++) {
            im[i] *= inv;
        }
    }

    /**
     * 变换内核。{@code conjugate} 为真表示逆向（用共轭旋转因子）。
     *
     * <p>上游的 {@code assert_eq!(re.len(), self.n)} 是前置条件，Java 对应
     * {@link IllegalArgumentException}——静默按较短的那个算会得到垃圾频谱。
     */
    private void transform(float[] re, float[] im, boolean conjugate) {
        if (re.length != n) {
            throw new IllegalArgumentException("实部长度必须等于变换长度：" + re.length + " != " + n);
        }
        if (im.length != n) {
            throw new IllegalArgumentException("虚部长度必须等于变换长度：" + im.length + " != " + n);
        }

        // 位反转置换
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                float tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                float ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }

        // 上游的 `while size <= self.n` 里 size 是 usize（64 位）。这里用 long 保持同一宽度：
        // Java 的 int 在 size 加倍到 2^31 时会回绕成负数，`size <= n` 会永远成立（死循环，
        // n = 2^30 时可达）。循环体内的下标仍用 int —— size <= n 且 n 受数组长度限制，放得下。
        for (long size = 2; size <= n; size <<= 1) {
            int half = (int) (size / 2);
            int step = n / (int) size;
            for (int i = 0; i < n; i += (int) size) {
                int k = 0;
                for (int j = i; j < i + half; j++) {
                    int t = k * step;
                    float wr = cos[t];
                    // 逆向变换等价于用共轭旋转因子
                    float wi = conjugate ? -sin[t] : sin[t];

                    float xr = re[j + half] * wr - im[j + half] * wi;
                    float xi = re[j + half] * wi + im[j + half] * wr;

                    re[j + half] = re[j] - xr;
                    im[j + half] = im[j] - xi;
                    re[j] += xr;
                    im[j] += xi;
                    k += 1;
                }
            }
        }
    }

    /**
     * 单边<strong>幅度</strong>谱，写入 {@code out}（长度应为 {@code n / 2 + 1}）。
     *
     * <p>已经做了两件事，所以读出来的数就是「该频率上的时域幅度」：
     *
     * <ol>
     *   <li>直流与奈奎斯特之外的分量乘 2，补偿被丢弃的负频率</li>
     *   <li>整体除以 {@code n}。<strong>这一步不能省</strong>——不除的话，幅度为 1 的正弦
     *       会在谱上读出 {@code n}，各次调用之间也没法比大小，而拍点检测正是靠
     *       帧间幅度差做判据的</li>
     * </ol>
     *
     * <p>{@code out} 比 {@code n / 2 + 1} 长时，多出来的尾部会被<strong>清零</strong>
     * （上游 {@code skip(take)} 那一段）；{@code re} / {@code im} 至少要有 {@code take}
     * 个 bin，不够时上游按下标越界 panic，这里抛 {@link ArrayIndexOutOfBoundsException}。
     *
     * <p>注意：{@code re} / {@code im} 长度不等于 {@code n} 时上游<strong>不检查</strong>（只有
     * {@code transform} 查），所以这里也不查——保持同样的行为。
     */
    public void magnitude(float[] re, float[] im, float[] out) {
        int half = n / 2;
        int take = Math.min(out.length, half + 1);
        float inv = 1.0f / n;
        for (int k = 0; k < take; k++) {
            float m = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) * inv;
            out[k] = (k == 0 || k == half) ? m : m * 2.0f;
        }
        for (int k = take; k < out.length; k++) {
            out[k] = 0.0f;
        }
    }

    /**
     * 周期性 Hann 窗，长度 {@code n}，写入 {@code out}。
     *
     * <p>用周期性（{@code 2πk/n}）而不是对称性（{@code 2πk/(n-1)}）：重叠相加时前者才满足
     * 常数和条件，后者会留下周期性的幅度起伏。
     *
     * <p>被 {@code pitch.rs}（颗粒变调）复用——那边 {@code grain} 长度调用后再乘重叠归一化
     * 因子，依赖的正是这里的周期性。
     */
    public static void hann(float[] out) {
        int n = out.length;
        if (n == 0) {
            return;
        }
        if (n == 1) {
            out[0] = 1.0f;
            return;
        }
        for (int k = 0; k < n; k++) {
            float t = 2.0f * PI * k / n;
            // 0.5 - 0.5cos 升余弦：两端为 0，中点为 1
            out[k] = 0.5f - 0.5f * (float) Math.cos(t);
        }
    }

    /**
     * 自相关（无偏化，直接按位移求和）。
     *
     * <p>{@code max_lag} 之后的部分不计算。用于从起始包络里估周期。
     *
     * <p>几个与上游一致、但容易在移植时改错的点：
     *
     * <ul>
     *   <li>每个 lag 除以<strong>重叠长度</strong> {@code n - lag}，否则长延迟天然吃亏</li>
     *   <li>{@code lag >= n} 时写 0 后跳过（此时没有重叠样本可乘）</li>
     *   <li>{@code out} 超过 {@code min(out.length, max_lag)} 的尾部<strong>原样不动</strong>——
     *       这与 {@link #magnitude} 的清零行为不同，是上游刻意如此（调用方复用缓冲时
     *       要自己知道哪些位置是本次写过的）</li>
     *   <li>上游 {@code max_lag} 是 {@code usize}，不可能为负；Java 传负数时循环不执行、
     *       什么都不写（不抛异常），不做额外校验以保持与上游同样的"无前置检查"</li>
     * </ul>
     *
     * @param x       输入信号
     * @param maxLag  最大延迟（采样数）
     * @param out     输出，长度决定最多写多少个 lag
     */
    public static void autocorrelate(float[] x, int maxLag, float[] out) {
        int n = x.length;
        int take = Math.min(out.length, maxLag);
        for (int lag = 0; lag < take; lag++) {
            if (lag >= n) {
                out[lag] = 0.0f;
                continue;
            }
            float s = 0.0f;
            for (int i = 0; i < n - lag; i++) {
                s += x[i] * x[i + lag];
            }
            // 除以重叠长度，否则长延迟天然吃亏
            out[lag] = s / (n - lag);
        }
    }

    /**
     * 只打印形状，不打印三张表。
     *
     * <p>上游 {@code #[derive(Debug)]} 会把 {@code rev}/{@code cos}/{@code sin} 整表刷出来
     * （n 大时就是几万个数），与本仓库 {@code AudioBuf} 的处理同理，这里只留能定位问题的信息。
     */
    @Override
    public String toString() {
        return "Fft{n=" + n + ", half=" + cos.length + "}";
    }
}
