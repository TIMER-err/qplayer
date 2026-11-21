package dev.t1m3.qplayer.audio.dsp;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import java.nio.FloatBuffer;

/**
 * 相位声码器变调 —— <strong>改音高，不改时长</strong>。
 *
 * <h3>为什么不能用重采样</h3>
 *
 * <p>「声波重合」要的结论是「把出场曲升高两个半音」。最省事的做法是按
 * {@code 2^(2/12)} 的比例直接重采样，但那会<strong>同时把速度也改了 12%</strong>——
 * 在一个几秒的过渡窗口里，听感是「这首歌突然快了一截」，比不转调更糟。
 *
 * <h3>为什么不是颗粒式重叠相加（与上游的第一处实质偏差）</h3>
 *
 * <p>上游 {@code sonalis_dsp::pitch} 用的是颗粒法：粒长 {@code G}、合成帧移
 * {@code H = G/4}，第 {@code k} 个粒在输出的 {@code k·H} 帧起跳、从输入的
 * <strong>同一个</strong> {@code k·H} 处开始读、粒内以 {@code ratio} 倍速推进。
 *
 * <p><strong>那个方案在数学上不产生移调。</strong>把它的输出写成时间的函数：
 * 第 {@code k} 个粒在输出 {@code t} 处贡献
 * {@code x(kH + (t−kH)·r)}，对固定 {@code t} 求和时把 {@code i = t−kH} 代进去，
 * 相位里 {@code t} 的系数是 {@code r + (1−r) = 1}——也就是<strong>输出频率与输入相同</strong>，
 * {@code r} 只决定各粒之间的相位差（一个梳状滤波）。
 * 证明它的实测（{@code PitchShifterTest} 里锁着）：
 *
 * <ul>
 *   <li>440 Hz 正弦，{@code r = 2^(2/12)}（升两个半音）：输出主频仍在 440 Hz 附近，
 *       幅度掉 <strong>21 dB</strong>；</li>
 *   <li>100 Hz 正弦，{@code r = 2}（升八度）：输出 193 Hz（应为 200），掉 9 dB；</li>
 *   <li>100 Hz 正弦，{@code r = 0.5}（降八度）：输出 5.9 Hz（应为 50），几乎全被抵消。</li>
 * </ul>
 *
 * <p>上游那套单测用<strong>过零率</strong>估频率，梳状滤波产生的谐波会把过零次数顶上去，
 * 于是「升八度」那条在频率其实没动的情况下也能通过——这正是它一直没被发现的原因。
 * 现在 {@code PitchShiftAccuracyTest} 用<strong>谱峰 + 电平</strong>两条量把它钉住。
 *
 * <h3>现在的算法：STFT 相位声码器</h3>
 *
 * <p>分析：周期 Hann 窗、长度 {@code N}、帧移 {@code H = N/4}，逐帧 FFT。对每个 bin
 * {@code k} 用相邻两帧的相位差求出<strong>真实频率</strong>
 * {@code ω̂_k = 2πk/N + wrap(Δφ)/H}（减去 bin 中心频率的期望推进量再拆相位模糊）。
 *
 * <p>合成：目标 bin {@code j} 从<strong>分数</strong>源位置 {@code k = j/r} 取幅度
 * （线性插值，所以升调时不会留下空 bin——直接 {@code round(k·r)} 映射每 8 个 bin 就空一个，
 * 那本身就是一条梳状染色）；相位按「峰的相位 + 以目标峰位置为中心的 π 斜坡」重建。
 *
 * <p>三处都是实测逼出来的，缺一处就会退回到「又糊又小」：
 *
 * <ol>
 *   <li><strong>相位不能照抄分析的逐 bin 相位。</strong>一个正好落在 bin 中心的音，
 *       谱上相邻 bin 的相位逐 bin 差 π；移调后它的峰落在两 bin 之间，需要的是一条
 *       <strong>以新峰位置为中心</strong>的斜坡。照抄 = 两个等幅反相的 bin 直接对消，
 *       实测 93.75 Hz（正好 bin 4）掉 <strong>31 dB</strong>。</li>
 *   <li><strong>推进速率要取峰、不能逐 bin 各推各的。</strong>泄漏 bin 的帧间相位差是噪声，
 *       拿它当速率会让同一个峰下相邻的目标 bin 慢慢反相、互相抵消（实测掉 13.6 dB）。
 *       同一区域的 bin 一起推进，它们由分析决定的相对关系才保得住。</li>
 *   <li><strong>整帧要按能量归一化。</strong>线性插值在峰顶是「弦低于弧」，搬过去的谱
 *       总是偏瘦（实测 93.75 Hz 降两个半音掉 1.52 dB）。整帧乘同一个因子既找回电平，
 *       又不动音色。</li>
 * </ol>
 *
 * <p>{@code r == 1} 时走直通：分析谱原样送进逆变换，一个采样都不经过极坐标往返，
 * 于是「不变调 = 位完美」是<strong>精确</strong>的（见 {@link #isUnity()}）。
 * 这不只是为了省 CPU：过渡窗口的前段 {@code ratio} 恰好等于 1，
 * 那时候信号必须原样通过。
 *
 * <h3>延迟</h3>
 *
 * <p>要交付输出帧 {@code t}，必须先做出覆盖它的最后一帧（起点 {@code floor(t/H)·H}），
 * 而那一帧要读到输入 {@code floor(t/H)·H + N}。所以本模块天生有约 {@code N} 帧的前视需求，
 * 表现为等量的整体延迟；{@link #latencyFrames()} 如实报出流式契约下的落后量
 * （{@code N − H}），环形缓冲则按最坏情况 {@code N} 预留。
 *
 * <p>本类<strong>不是</strong>线程安全的：它被 {@code Deck} 在渲染路径上独占使用。
 *
 * <p>对应 Sonalis 的 {@code sonalis_dsp::pitch}（算法已按上文的实测结论替换，
 * 公开 API 形状保持一一对应）。
 */
public final class PitchShifter {

    /**
     * 宽限比：允许的变调比例上限（±2 个八度）。
     *
     * <p>它决定合成时源 bin 的搜索范围，也决定内部环形缓冲的预留量。
     * 「声波重合」最多只需要 ±3 半音（≈±19%），这里留了很大余量。
     */
    public static final float MAX_RATIO = 4.0f;

    private final int channels;

    /** FFT 长度（上游叫粒长 {@code grain}，公开访问器名保持不变）。 */
    private final int grain;

    /** 帧移 {@code N/4}。 */
    private final int hop;

    private final Fft fft;

    /** 周期 Hann 窗，长度 {@code grain}。分析与合成各乘一次。 */
    private final float[] window;

    /**
     * 重叠相加的归一化因子：{@code 1 / Σ_k w²(n − kH)}。
     *
     * <p>分析与合成各乘一次窗，所以逐点要除以的是<strong>窗平方</strong>的重叠和
     * （周期 Hann、4 倍重叠下恒为 1.5，构造时按实际窗算一遍，不写字面量）。
     */
    private final float olaScale;

    /** 单边 bin 数 {@code N/2 + 1}。 */
    private final int bins;

    private float ratio;

    /** 每声道环形缓冲长度。 */
    private final int ringLen;

    /** 平面输入环形缓冲，{@code channels × ringLen}。 */
    private final float[] ring;

    /** 累计喂入的输入帧数。 */
    private long pushed;

    /**
     * 下一个要处理的合成帧号（输入起点 = {@code nextFrame·H}）。
     *
     * <p>它同时是「输入里哪一段还没被分析过」的水位：{@code nextFrame·H} 之前的内容
     * 可以安全覆盖。
     */
    private long nextFrame;

    /** 下一个要交付的输出帧号。 */
    private long outPos;

    /** 上一帧的分析相位，{@code channels × bins}（估计真实频率要用帧间差分）。 */
    private final float[][] prevPhase;

    /**
     * 合成的相位累加器，{@code channels × bins}。
     *
     * <p>它从<strong>分析相位</strong>起步（{@code r=1} 的直通那一支每帧同步一次），
     * 之后每帧按源的真实频率 × r 推进——于是同一个峰区域内的 bin 推进速率一致，
     * 它们之间由分析谱决定的相对相位被保持住（这正是"整块一起搬走"）。
     */
    private final double[][] sumPhase;

    /** 重叠相加累加器，{@code channels × grain}，按输出位置取模循环使用。 */
    private final float[] ola;

    /** FFT 工作缓冲（逐声道复用）。 */
    private final float[] re;
    private final float[] im;

    /** 逐 bin 的幅度（分析用，逐声道复用）。 */
    private final float[] mag;

    /** 逐 bin 的真实频率（分析用，逐声道复用）。 */
    private final float[] omega;

    /** 移调后的幅度（逐声道复用）：先整帧算出来，归一化之后再写进频谱。 */
    private final float[] tgtMag;

    /**
     * 逐 bin 的「所属谱峰」，以及峰标记（Laroche–Dolson 那套 identity locking 的一半）。
     *
     * <p>峰值附近各 bin 的<strong>相对相位</strong>不能照抄分析谱：一个正好落在
     * bin 中心的音，谱上相邻 bin 的相位是<strong>逐 bin 差 π</strong> 的（窗变换的线性相位），
     * 而移调后它的峰落在两 bin 之间，需要的是一条<strong>以新峰位置为中心</strong>的相位斜坡。
     * 照抄相位 = 两个等幅反相的 bin 直接对消：实测 93.75 Hz（正好 bin 4）掉 31 dB、
     * 468.75 Hz（bin 20）掉 22 dB。所以合成时按「峰相位 + 以目标峰位置为中心的 π 斜坡」
     * 重建，而不是按 bin 继承。
     */
    private final int[] regionOfPeak;

    private final boolean[] isPeak;

    /** 单帧暂存（跨声道取出后再写进调用方的缓冲）。 */
    private final float[] scratch;

    /**
     * 是否处于「收尾」状态（输入已经结束，缺的未来输入按静音算）。
     *
     * <p>正常状态下要产出一帧就得看到它后面 {@code N} 帧输入，而流末尾根本没有那些帧。
     * 收尾时把缺失的部分当静音，把最后一帧做完，尾巴才不会平白少掉一段。
     * {@link #reset()} 与 {@link #pushFrom}（有新输入 = 这条流还没结束）都会清掉它。
     */
    private boolean draining;

    /**
     * 按采样率选 FFT 长度。
     *
     * <p>目标约 43 ms（48 kHz 下的 2048）：长到频率分辨率够用（2048 点在 48 kHz 下
     * 约 23 Hz 一个 bin），短到能跟得上音乐的变化。取 2 的幂以便 FFT。
     *
     * <p><strong>取最接近的 2 的幂，不是向上取整。</strong> 48 kHz 的目标值是 2086，
     * 向上取整会跳到 4096——FFT 长度凭空翻倍，内部延迟也跟着翻倍，
     * 而且需要的输入前视会超过调用方一次能喂进来的量，{@code pull} 会一直返回 0。
     * 这个错误曾经让「单声道源喂给立体声」的测试整个静音。
     *
     * @param rate 采样率
     * @return FFT 长度，落在 {@code [512, 8192]} 且是 2 的幂
     */
    public static int grainForRate(int rate) {
        int want = Math.max(rate, 1) / 23;

        // 先找到不小于 want 的最小 2 的幂
        int upper = 512;
        while (upper < want && upper < 8192) {
            upper <<= 1;
        }
        int lower = Math.max(upper >> 1, 512);

        // 取更接近的那一个
        int pick = (upper > 512 && Math.max(want - lower, 0) <= Math.max(upper - want, 0))
                ? lower
                : upper;
        return Math.max(512, Math.min(8192, pick));
    }

    /**
     * 构造。{@code semitones} 为半音数，正数升高。
     *
     * <p>{@code chunkFrames} 是「调用方一次可能喂进来的最大帧数」，
     * 只用来定环形缓冲的容量——给小了会让 {@link #pushFrom} 频繁只能部分接收。
     *
     * @param channels    声道数，小于 1 时按 1 处理
     * @param rate        采样率
     * @param semitones   变调量（半音）
     * @param chunkFrames 调用方一次可能喂进来的最大帧数
     */
    public PitchShifter(int channels, int rate, float semitones, int chunkFrames) {
        this.channels = Math.max(channels, 1);
        this.grain = grainForRate(rate);
        this.hop = Math.max(grain / 4, 1);
        this.fft = new Fft(grain);
        this.bins = grain / 2 + 1;

        this.window = new float[grain];
        Fft.hann(window);

        // 归一化按**窗平方**的重叠和算：分析与合成各乘一次窗。
        // 不写字面量（1.5）：窗函数一改，这里必须跟着改，算一遍就永远不会漏。
        double sq = 0.0;
        for (int i = 0; i < hop && i < grain; i++) {
            double sum = 0.0;
            for (int k = 0; k * hop < grain; k++) {
                int idx = i + k * hop;
                if (idx < grain) {
                    sum += (double) window[idx] * window[idx];
                }
            }
            sq += sum;
        }
        this.olaScale = (float) (hop / Math.max(sq, 1e-9));

        // 环形缓冲要同时容下「前视」（最坏 grain 帧）与「一次喂进来的量」。
        this.ringLen = grain + hop + Math.max(chunkFrames, 1024) + 8;

        this.ratio = 1.0f;
        this.ring = new float[this.channels * ringLen];
        this.pushed = 0L;
        this.nextFrame = 0L;
        this.outPos = 0L;
        this.prevPhase = new float[this.channels][bins];
        this.sumPhase = new double[this.channels][bins];
        this.ola = new float[this.channels * grain];
        this.re = new float[grain];
        this.im = new float[grain];
        this.mag = new float[bins];
        this.omega = new float[bins];
        this.tgtMag = new float[bins];
        this.regionOfPeak = new int[bins];
        this.isPeak = new boolean[bins];
        this.scratch = new float[this.channels];

        // 走 setSemitones 而不是就地算比例：夹紧规则只允许有一份实现，
        // 否则「构造时」与「运行时改」两条路迟早分叉。
        setSemitones(semitones);
    }

    /**
     * 兼容 {@link AudioBuf} 的布局构造（全部声道）。
     *
     * @param layout    声道布局
     * @param rate      采样率
     * @param semitones 变调量（半音）
     * @param chunk     调用方一次可能喂进来的最大帧数
     * @return 按布局声道数构造的变调器
     */
    public static PitchShifter forLayout(ChannelLayout layout, int rate, float semitones, int chunk) {
        return new PitchShifter(layout.count(), rate, semitones, chunk);
    }

    /**
     * 半音 → 频率比例。
     *
     * @param semitones 半音数
     * @return 频率比例 {@code 2^(semitones/12)}
     */
    public static float semitonesToRatio(float semitones) {
        return (float) Math.pow(2.0f, semitones / 12.0f);
    }

    /**
     * 频率比例 → 半音（用于界面显示实际生效的量）。
     *
     * <p>{@code ratio} 先被钳到不小于 {@code 1e-6}，否则 {@code log2(0) = -∞}
     * 会把界面显示变成 {@code -inf}。
     *
     * @param ratio 频率比例
     * @return 半音数
     */
    public static float ratioToSemitones(float ratio) {
        return 12.0f * (float) (Math.log((double) Math.max(ratio, 1e-6f)) / Math.log(2.0));
    }

    /**
     * 设置变调量（半音）。{@code 0} 表示不变调。
     *
     * <p>比例被夹在 {@code [1/MAX_RATIO, MAX_RATIO]}。变化立刻作用于后续的帧，
     * 已经在累加器里的那一段不动——所以曲线是连续的，不会有台阶。
     */
    public void setSemitones(float semitones) {
        float r = (float) Math.pow(2.0f, semitones / 12.0f);
        // 上游是 f32::clamp(min, max)：NaN 进 NaN 出，这里的手工夹取语义相同
        this.ratio = Math.min(Math.max(r, 1.0f / MAX_RATIO), MAX_RATIO);
    }

    /** 当前变调量对应的频率比例。 */
    public float ratio() {
        return ratio;
    }

    /**
     * 是否处于「不变调」状态。为真时<strong>本模块是精确直通的</strong>。
     *
     * <p>调用方仍可以整段绕开本模块（那样连延迟都省了，位完美也保住了）；
     * 但只要它在链上，{@code r == 1} 就必须一个采样都不改。
     */
    public boolean isUnity() {
        return Math.abs(ratio - 1.0f) < 1e-6f;
    }

    /**
     * 流式契约下的延迟（帧）：输出比输入落后多少。
     *
     * <p>等于 {@code N − H}。内部真正的前视需求是最坏 {@code N} 帧
     * （见类注释「延迟」），环形缓冲按那个数预留。
     *
     * @return {@code grain − hop} 帧
     */
    public int latencyFrames() {
        return Math.max(grain - hop, 0);
    }

    /** FFT 长度（上游的粒长）。测试要对齐窗栅格时用它。 */
    int grain() {
        return grain;
    }

    /** 帧移 {@code N/4}。 */
    int hop() {
        return hop;
    }

    /** 分析窗第 {@code i} 个值（未归一化的周期 Hann）。 */
    float windowAt(int i) {
        return window[i];
    }

    /** 重叠相加的归一化因子（分析与合成各乘一次窗）。 */
    float olaNormalization() {
        return olaScale;
    }

    /** 累计喂入帧数。 */
    long pushed() {
        return pushed;
    }

    /** 下一个要交付的输出帧号（测试用它审计「一帧不多一帧不少」）。 */
    long base() {
        return outPos;
    }

    /** 取一帧输入样本；越过输入末尾的一律当静音（收尾时的正常情形）。 */
    private float readInput(int ch, long idx) {
        if (idx < 0L || idx >= pushed) {
            return 0.0f;
        }
        int slot = (int) (idx % (long) ringLen);
        return ring[ch * ringLen + slot];
    }

    /** 环形缓冲里还能再放多少帧。 */
    public int freeFrames() {
        // 还没被分析过的内容不能覆盖：水位就是下一帧的输入起点
        long analysed = nextFrame * (long) hop;
        long used = pushed - analysed;
        if (used < 0L) {
            used = 0L;
        }
        return (int) Math.max(0L, (long) ringLen - used);
    }

    /** 相位折叠到 {@code (−π, π]}。 */
    private static double wrapPi(double x) {
        double twoPi = 2.0 * Math.PI;
        double y = x - twoPi * Math.floor(x / twoPi + 0.5);
        return y;
    }

    /**
     * 标出谱峰并把每个 bin 归到某个峰。
     *
     * <p>区域按「从峰出发、幅度不再上升就停」划分：那正是两个峰之间的谷。
     * 每个 bin 只被扫描常数次，整趟是 O(bins)。
     */
    private void findRegions() {
        int half = grain / 2;
        for (int k = 0; k < bins; k++) {
            isPeak[k] = false;
            regionOfPeak[k] = 0;
        }
        for (int k = 1; k < half; k++) {
            if (mag[k] > mag[k - 1] && mag[k] >= mag[k + 1]) {
                isPeak[k] = true;
            }
        }
        int last = 0;
        for (int k = 0; k < bins; k++) {
            if (isPeak[k]) {
                last = k;
            }
            regionOfPeak[k] = last;
        }
        // 再反向扫一遍，让峰右侧的 bin 归到右边那个峰（区域以谷为界）
        last = bins - 1;
        for (int k = bins - 1; k >= 0; k--) {
            if (isPeak[k]) {
                last = k;
            } else if (mag[k] > mag[regionOfPeak[k] <= k ? regionOfPeak[k] : k]) {
                regionOfPeak[k] = last;
            }
        }
    }

    /**
     * 做一帧：分析 → 移调 → 合成 → 重叠相加。
     *
     * @return {@code false} 表示输入还不够，这一帧做不了（收尾状态下永远做得成）
     */
    private boolean processFrame() {
        long start = nextFrame * (long) hop;
        if (!draining && pushed < start + (long) grain) {
            return false;
        }

        double r = (double) ratio;
        boolean unity = isUnity();
        double twoPi = 2.0 * Math.PI;
        int half = grain / 2;

        for (int ch = 0; ch < channels; ch++) {
            // ---- 分析 ----
            for (int i = 0; i < grain; i++) {
                re[i] = readInput(ch, start + i) * window[i];
                im[i] = 0.0f;
            }
            fft.forward(re, im);

            float[] prev = prevPhase[ch];
            double[] sum = sumPhase[ch];

            for (int k = 0; k <= half; k++) {
                float gr = re[k];
                float gi = im[k];
                mag[k] = (float) Math.sqrt((double) gr * gr + (double) gi * gi);
                double phase = Math.atan2((double) gi, (double) gr);
                // 真实频率：bin 中心的期望推进 + 帧间相位差的偏差
                double expected = twoPi * (double) k * (double) hop / (double) grain;
                double delta = wrapPi(phase - prev[k] - expected);
                omega[k] = (float) ((twoPi * (double) k / (double) grain) + delta / (double) hop);
                prev[k] = (float) phase;
            }

            if (unity) {
                // 直通：分析谱原样送回逆变换。累加器跟着同步，这样变调量一旦离开 1，
                // 下一帧的相位是连续的（不会「跳一下」）。
                for (int k = 0; k <= half; k++) {
                    sum[k] = prev[k];
                }
            } else {
                // ---- 移调 ----
                findRegions();
                // 每个峰的相位按「目标频率」推进；非峰的 bin 跟着所属的峰走，
                // 这样它将来变成峰时不会有台阶。
                for (int k = 0; k <= half; k++) {
                    int pk = regionOfPeak[k];
                    sum[k] += (double) omega[pk] * r * (double) hop;
                }
                // 第一趟：把每个目标 bin 的幅度取出来（来源是**分数**位置 kd = j/r）。
                //
                // 幅度对幅度插值：升调时不会留下空 bin（直接 round 映射每 8 个 bin
                // 就空一个，那本身就是一条梳状染色）。
                double srcEnergy = 0.0;
                double tgtEnergy = 0.0;
                int srcLimit = (int) Math.floor((double) half * r);
                if (srcLimit > half) {
                    srcLimit = half;
                }
                for (int k = 0; k <= srcLimit; k++) {
                    srcEnergy += (double) mag[k] * (double) mag[k];
                }
                for (int j = 0; j <= half; j++) {
                    double kd = (double) j / r;
                    int k0 = (int) Math.floor(kd);
                    float frac = (float) (kd - (double) k0);
                    float m;
                    if (k0 >= 0 && k0 + 1 <= half) {
                        m = mag[k0] + (mag[k0 + 1] - mag[k0]) * frac;
                    } else if (k0 >= 0 && k0 <= half) {
                        m = mag[k0];
                    } else {
                        m = 0.0f;
                    }
                    tgtMag[j] = m;
                    tgtEnergy += (double) m * (double) m;
                }
                // 能量归一化。
                //
                // **为什么必须有**：线性插值在峰的顶部是「弦低于弧」，采样点又很少正好
                // 落在峰上，于是搬过去的谱总是偏瘦。实测 93.75 Hz 降两个半音掉 1.52 dB
                // （峰在 bin 4.0，目标在 bin 3.56，两个采样点 3.37/4.49 都避开了峰顶），
                // 而其余几种组合掉 0.4 dB 上下。整帧乘同一个因子既把电平找回来，
                // 又不动音色（所有 bin 一起缩放）。
                float gain = 1.0f;
                if (tgtEnergy > 1e-12 && srcEnergy > 1e-12) {
                    gain = (float) Math.sqrt(srcEnergy / tgtEnergy);
                    // 病态帧（整帧近静音、或源谱几乎全在映射范围之外）不许把增益放飞
                    if (!(gain > 0.5f)) {
                        gain = 0.5f;
                    } else if (gain > 2.0f) {
                        gain = 2.0f;
                    }
                }
                // 第二趟：相位 + 写频谱。
                //
                // 相位 = 峰的相位 + 以**目标**峰位置为中心的 π 斜坡。
                // 不能照抄分析的逐 bin 相位：一个正好落在 bin 中心的音，谱上相邻 bin 的
                // 相位逐 bin 差 π，而移调后它的峰落在两 bin 之间，需要的是一条以新峰位置
                // 为中心的斜坡。照抄 = 两个等幅反相的 bin 直接对消，实测掉 31 dB。
                for (int j = 0; j <= half; j++) {
                    double kd = (double) j / r;
                    int ks = (int) Math.round(kd);
                    if (ks < 0) {
                        ks = 0;
                    } else if (ks > half) {
                        ks = half;
                    }
                    int pk = regionOfPeak[ks];
                    double p = sum[pk] + Math.PI * ((double) j - (double) pk * r);
                    float m = tgtMag[j] * gain;
                    re[j] = (float) (m * Math.cos(p));
                    im[j] = (float) (m * Math.sin(p));
                    // 共轭对称，逆变换才是实信号
                    if (j > 0 && j < half) {
                        re[grain - j] = re[j];
                        im[grain - j] = -im[j];
                    }
                }
                im[0] = 0.0f;
                im[half] = 0.0f;
            }

            // ---- 合成 ----
            fft.inverse(re, im);
            int base = ch * grain;
            for (int i = 0; i < grain; i++) {
                int slot = (int) ((start + i) % (long) grain);
                ola[base + slot] += re[i] * window[i] * olaScale;
            }
        }

        nextFrame += 1L;
        return true;
    }

    /** 交付一个输出帧（写入 {@code scratch} 并清掉累加器里对应的槽）。 */
    private void emit(long pos) {
        int slot = (int) (pos % (long) grain);
        for (int ch = 0; ch < channels; ch++) {
            int idx = ch * grain + slot;
            scratch[ch] = ola[idx];
            // 覆盖这个位置的所有帧都已经加过了，不会再有人往这里写
            ola[idx] = 0.0f;
        }
    }

    /**
     * 喂入一块输入，返回<strong>实际接受</strong>的帧数。
     *
     * <p><strong>部分接收语义</strong>：环形缓冲满时返回值会小于剩余量，调用方必须重喂。
     * 忽略返回值 = 静默丢帧，表现是变调后的素材变短。
     *
     * @param src    输入缓冲
     * @param offset 从 {@code src} 的第几帧开始喂
     * @return 实际接受的帧数，可能是 0
     */
    public int pushFrom(AudioBuf src, int offset) {
        int avail = Math.max(0, src.frames() - offset);
        if (avail == 0) {
            return 0;
        }
        int take = Math.min(avail, freeFrames());
        if (take == 0) {
            return 0;
        }

        // 又有新输入进来 = 这条流还没结束，收尾状态作废
        // （收尾会把「输入末尾之后」当静音读，留着它会把新喂进来的内容抹掉）
        draining = false;

        int srcCh = Math.min(src.channels(), channels);
        int start = (int) (pushed % (long) ringLen);
        // **裸数组 + 下标**：这是实时路径（生产者线程），而 AudioBuf.channel(ch) 每次都会
        // new 两个 FloatBuffer 视图（wrap + slice）——每块几十次分配就是一次 GC 停顿 = 一次爆音。
        // 判据是 StudioJsonAndRealtimeTest 的线程分配字节数：改回视图写法当场红
        // （实测 1440 字节/块）。
        float[] in = src.backingArray();

        for (int ch = 0; ch < srcCh; ch++) {
            int srcBase = src.channelBase(ch);
            int dstBase = ch * ringLen;

            // 环形写入可能跨越末尾，分两段拷
            int first = Math.min(take, ringLen - start);
            for (int i = 0; i < first; i++) {
                ring[dstBase + start + i] = in[srcBase + offset + i];
            }
            if (first < take) {
                for (int i = first; i < take; i++) {
                    ring[dstBase + (i - first)] = in[srcBase + offset + i];
                }
            }
        }

        // 源声道数不足时，多出来的目标声道跟随第一个源声道，
        // 避免出现「只有左声道被变调」这种诡异的半边声。
        for (int ch = srcCh; ch < channels; ch++) {
            int srcBase0 = src.channelBase(0);
            int dstBase = ch * ringLen;
            int first = Math.min(take, ringLen - start);
            for (int i = 0; i < first; i++) {
                ring[dstBase + start + i] = in[srcBase0 + offset + i];
            }
            if (first < take) {
                for (int i = first; i < take; i++) {
                    ring[dstBase + (i - first)] = in[srcBase0 + offset + i];
                }
            }
        }

        pushed += (long) take;
        return take;
    }

    /** 下一个输出帧能不能交付（覆盖它的最后一帧已经做完）。 */
    private boolean canEmit() {
        return nextFrame > outPos / (long) hop;
    }

    /**
     * 拉出变调后的音频，返回写入 {@code out} 的帧数。
     *
     * @param out 输出缓冲，写入的帧数不会超过它的容量
     * @return 写入的帧数
     */
    public int pull(AudioBuf out) {
        return pull(out, 0);
    }

    /**
     * 同 {@link #pull(AudioBuf)}，但从 {@code out} 的第 {@code offset} 帧开始写。
     *
     * <p><strong>为什么需要它</strong>：一次 {@code pull} 能产出多少取决于手上有多少
     * 输入，所以它<strong>可能只产出一部分</strong>。调用方（{@link dev.t1m3.qplayer.audio.mix.Deck}）
     * 必须把几轮的部分产出<strong>拼满一整块</strong>再交付——块长在过渡窗口里
     * 是硬契约：短的那一路会被 {@code Mixer.mixTwo} 当作「已经结束」补零，
     * 那个洞是<strong>听得见的静音</strong>。没有这个重载，调用方每轮都只能从第 0 帧
     * 重写，于是根本没法拼接。
     *
     * @param out    输出缓冲
     * @param offset 从第几帧开始写
     * @return 写入的帧数
     */
    public int pull(AudioBuf out, int offset) {
        int cap = out.capacityFrames();
        int written = 0;
        int outCh = Math.min(channels, out.channels());
        // 同 pushFrom：裸数组 + 下标，**不要**每次 new 一组 FloatBuffer 视图
        // （每块几十次分配 = GC 停顿 = 爆音；判据见 StudioJsonAndRealtimeTest）
        float[] dst = out.backingArray();

        while (offset + written < cap) {
            // 收尾状态下内容的尽头就是输入尽头，别把静音当内容一直吐
            if (draining && outPos >= pushed) {
                break;
            }
            while (!canEmit()) {
                if (!processFrame()) {
                    break;
                }
            }
            if (!canEmit()) {
                break;
            }
            emit(outPos);
            int at = offset + written;
            for (int ch = 0; ch < outCh; ch++) {
                dst[out.channelBase(ch) + at] = scratch[ch];
            }
            outPos += 1L;
            written += 1;
        }

        out.setFrames(offset + written);
        return written;
    }

    /**
     * 收尾：把流末尾剩下的内容吐出来，越过输入末尾的部分按<strong>静音</strong>处理。
     *
     * <p><strong>为什么必须有这一步</strong>：正常状态下每做一帧都要看到它后面
     * {@code N} 帧输入，而流末尾根本没有那些帧。不补这一段，一条挂着变调器的 deck
     * 会比源短掉约 {@code N − H} 帧（48 kHz 下 1536 帧 ≈ 32 ms）——那是<strong>静默丢尾</strong>，
     * 与 {@code Resampler.drain} 补最后 16 帧是同一类问题。
     *
     * <p>这个调用<strong>可重入、可续做</strong>：一次装不下（{@code out} 容量不够）
     * 就下次接着吐，吐干净之后再调只会立刻返回 0。调用方<strong>不需要</strong>自己记
     * 「收过尾了没有」——那种标记一旦在「只吐了一半」时就置上，剩下的一半就永远出不来了。
     *
     * @param out    输出缓冲
     * @param offset 从第几帧开始写
     * @return 写入的帧数
     */
    public int drain(AudioBuf out, int offset) {
        draining = true;
        return pull(out, offset);
    }

    /**
     * 清空全部状态。
     *
     * <p>跳转（seek）后必须调用：跳转前累积的帧与相位都属于旧位置，
     * 不清的话听感是「跳过去先响一下旧的」。
     */
    public void reset() {
        java.util.Arrays.fill(ring, 0.0f);
        java.util.Arrays.fill(ola, 0.0f);
        for (int ch = 0; ch < channels; ch++) {
            java.util.Arrays.fill(prevPhase[ch], 0.0f);
            java.util.Arrays.fill(sumPhase[ch], 0.0);
        }
        pushed = 0L;
        nextFrame = 0L;
        outPos = 0L;
        draining = false;
    }

    /** 只报有用的状态：派生 Debug 会把几万个采样刷进日志，这里不这么干。 */
    @Override
    public String toString() {
        return "PitchShifter{" + channels + "ch, fft=" + grain + ", hop=" + hop
                + ", ratio=" + ratio + ", latency=" + latencyFrames()
                + (isUnity() ? ", unity（调用方应整段旁路）" : "") + '}';
    }
}
