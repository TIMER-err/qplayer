package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.dsp.Fft;

import java.util.Arrays;
import java.util.Locale;

/**
 * 拍点与节奏分析 —— AI 模式（folia 移植）的地基。
 *
 * <h3>它回答什么</h3>
 *
 * <p>要「在拍子上换歌」，得先知道<strong>拍子在哪里</strong>。本模块从音频里估出三件事：
 *
 * <ol>
 *   <li><strong>起始包络</strong>（onset envelope）：每一帧「新声音出现的强度」</li>
 *   <li><strong>速度</strong>（BPM）与<strong>周期</strong></li>
 *   <li><strong>相位</strong>：第一拍落在哪里</li>
 * </ol>
 *
 * <p>有了这三样，过渡引擎就能算出「下一拍在哪个采样点」，把切点钉在拍上。
 *
 * <h3>算法</h3>
 *
 * <p><strong>起始检测用频谱通量</strong>：对每一帧算幅度谱，把「比上一帧变强的那些频点」
 * 的增量加起来。这样做的原因是它天然忽略稳态成分——持续的长笛不会触发，
 * 鼓槌落下的瞬间会。半波整流（只取正增量）是关键，取绝对值的话
 * 声音<strong>消失</strong>也会被算成事件。
 *
 * <p><strong>速度估计用自相关 + 先验</strong>：起始包络的自相关会在拍周期及其整数倍上
 * 出现峰（自相关对周期天然是多值的）。所以不能只取最大峰，得乘一个
 * <strong>速度先验</strong>——人对 100–140 BPM 附近的节奏最敏感，这也是大多数音乐的
 * 实际区间。没有这个先验，同一段音乐很容易被估成一半或两倍速度。
 *
 * <p><strong>相位用折叠求和</strong>：把包络按周期折叠，取累加和最大的偏移量。比取
 * 「第一个峰」稳健得多——第一个峰可能是个弱起的装饰音。
 *
 * <h3>纯算法、无模型（这是刻意的，不是还没做）</h3>
 *
 * <p>拍点检测<strong>不依赖任何 ONNX 模型</strong>：频谱通量 + 自相关 + 相位折叠全是闭式
 * 计算，没有权重文件、没有推理运行时、没有下载步骤。所以本模块是<strong>零下载、
 * 零体积</strong>的——判调性（{@link Harmonic}）同样如此。上游刻意留在这个形态上：
 * 一个能在离线阶段跑几十毫秒、又不需要用户额外下几百 MB 的方案，比一个
 * 强一点点但会拖垮安装包与首次启动的神经网络方案更符合这个 App 的取舍。
 * 移植时不要「顺手」把它换成模型推理。
 *
 * <h3>洁净室说明</h3>
 *
 * <p>依公开的信号处理常规方法独立编写（频谱通量、自相关周期估计、折叠相位），
 * <strong>未参考任何 AGPL / LGPL 项目的源码</strong>。
 *
 * <h3>这不是实时模块</h3>
 *
 * <p>全程会分配内存。调用时机是<strong>过渡开始之前</strong>（离线分析出场曲尾部与进场曲
 * 首部），不能放进音频回调。
 *
 * <h3>估不出来就不表态</h3>
 *
 * <p>{@link #estimateTempo} / {@link #analyze} 返回 {@code null}（上游 {@code None}）表示
 * 「素材没有可辨的节拍」——静音、纯直流、自由速度的古典乐、纯噪声、素材太短。
 * 拿到了 {@code null}，调用方就该退化成固定窗口。关键在于
 * <strong>不要猜</strong>：猜一个偏小的 BPM，会让本该对拍的过渡退化成一次短淡化，
 * 而用户完全看不出原因（听感上只是「这次接得一般」，日志里也没有任何异常）。
 * 宁可不表态，也不要编一个 BPM 出来——照着一个假结论去切点，比不切更糟。
 *
 * <h3>与上游的类型差异（按 docs/audio-port.md 记录）</h3>
 *
 * <ul>
 *   <li>上游 {@code &[f32]} / {@code Vec<f32>} 按约定一般映射成 {@link java.nio.FloatBuffer}，
 *       这里全用裸 {@code float[]}：本模块拿到的都是「一段连续的<strong>单声道</strong> PCM」
 *       或整段的包络/频谱缓冲（不是从 {@code AudioBuf} 取出来的声道切片），且访问模式是
 *       逐样本的热循环。多声道素材由 {@link #foldToMono} 先折叠成一条 {@code float[]}
 *       再进来，与上游一致。副产物是这里<strong>完全不碰</strong>
 *       {@code FloatBuffer.get(int, float[], int, int)} 这类<strong>带下标</strong>的绝对批量
 *       读写（Java 13+，Android 上会挂），批量拷贝一律走 {@link System#arraycopy}
 *       （对应上游的 {@code copy_from_slice}；上游那里长度不等会 panic，这里两侧
 *       长度都是同一个常量 {@code bins}，构造上即相等，不需要额外校验）。</li>
 *   <li>{@code Onsets} / {@code Tempo} → {@link Analysis.Onsets} / {@link Analysis.Tempo}
 *       两个 {@code public static final class}，字段与上游<strong>同名同序</strong>（Java 用
 *       小驼峰：{@code period_frames} → {@code periodFrames}），全 {@code final}。
 *       上游 {@code Tempo} 是 {@code derive(PartialEq)}，这里补了
 *       {@code equals}/{@code hashCode}（字段全比），见 {@link Analysis.Tempo#equals}。</li>
 *   <li>{@code Option<Tempo>} → {@code null}（见上），语义核心，不是省事。</li>
 *   <li>{@code rate: u32} → {@code int}。上游类型上不可能出现负采样率，这里也<strong>不</strong>
 *       额外校验负数——加校验就与上游行为分叉了；只有 {@code 0} 是上游显式判过的。</li>
 *   <li>{@code u64}（采样位置）→ {@code long}，{@code usize}（计数/下标）→ {@code int}。
 *       上游 {@code frame: u64} 在类型上不可能为负，Java 的 {@code long} 可以，
 *       但本类的算路对负数同样成立（{@code beat < 0} 的分支原样保留）。</li>
 *   <li>上游 {@code Tempo::beat_* } 里的 {@code beat.round() as u64}：Rust 的
 *       {@code f32::round} 是「半数<strong>远离零</strong>」，Java 没有返回 {@code float} 的对应
 *       函数（{@code Math.round(float)} 是 {@code floor(x+0.5)} 且返回 {@code int}），
 *       所以这里写了 {@link #roundF32} 手工对齐，见它的注释。</li>
 *   <li>{@code mod tests} 是子模块，在 Rust 里能看见父模块的私有项。Java 的对应可见性是
 *       <strong>包私有</strong>，所以 {@link #tempoPrior} / {@link #beatPhase} 以及测试用到的
 *       {@code FRAME} / {@code HOP} / {@code ENV_CENTER_OFFSET} 不加 {@code private}，
 *       只给同包的 {@code AnalysisTest} 对齐上游测试用，不作为对外 API。</li>
 *   <li>所有 {@code Math.*}（{@code sin}/{@code cos}/{@code sqrt}/{@code log}/{@code exp}/
 *       {@code ceil}/{@code floor}）的结果<strong>立即</strong> {@code (float)} 转换：Rust 那边是
 *       {@code f32::ln} / {@code f32::exp} 等。相位折叠与自相关的每个中间量都是 f32
 *       ——{@code ac[best_lag] / mean / 8.0} 与 {@code confidence < 0.15f} 是硬比较，
 *       让中间量走 double 会在阈值边界上给出与上游不同的结论（「有没有拍点」这种
 *       二值结论一旦分叉，对拍行为就整体错位）。{@code (float) Math.ceil((double) v)}
 *       这类写法是<strong>等价</strong>的：v 是 f32，宽化成 double 无损，ceil 的结果是整数，
 *       在 f32 能精确表示时两者逐位相同（超出 2²⁴ 时 f32 本身已是整数，也相同），
 *       这样做只是绕开 Java 没有 {@code float} 版 {@code ceil}/{@code floor} 的限制。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::analysis}。
 */
public final class Analysis {

    /** 分析帧长（样本）。 */
    static final int FRAME = 2048;

    /** 分析帧移（样本）。四分之一重叠。 */
    static final int HOP = 512;

    /** 速度搜索范围（BPM）。 */
    public static final float MIN_BPM = 60.0f;

    /** 速度搜索范围（BPM）。 */
    public static final float MAX_BPM = 200.0f;

    /**
     * 速度先验的中心（BPM）。
     *
     * <p>人对这个区间附近的节拍最敏感，绝大多数流行乐也落在这里。
     */
    private static final float PRIOR_CENTER_BPM = 120.0f;

    /**
     * 先验的宽容度（以倍数计）。
     *
     * <p>取自然对数后按这个尺度做高斯衰减：{@code exp(-(ln(bpm/center))² / (2σ²))}。
     * σ 越大先验越弱。0.5 对应「±65% 以内都还算合理」。
     */
    private static final float PRIOR_SIGMA = 0.5f;

    /** 结论的最低置信度。低于它就不表态。 */
    private static final float MIN_CONFIDENCE = 0.15f;

    /**
     * <b>硬算路</b>（{@link #tempoFromOnsetEnvelope}）结论的最低置信度。
     *
     * <p>与 {@link #MIN_CONFIDENCE} <b>不是同一把尺子</b>，数值相同纯属巧合：那条是上游
     * {@code estimate_tempo} 的「主峰 / 搜索区间平均 |自相关| / 8」，分母随延迟格数变；
     * 这条是归一化自相关 {@code |ac[结论周期]| / 包络总能量}。两把尺子的实测对照见
     * {@link #tempoFromOnsetEnvelope} 里的表。
     */
    private static final float MIN_AC_RATIO = 0.15f;

    /**
     * 起始包络的「时间归属」修正（包络帧）。
     *
     * <p>频谱通量在<strong>新能量第一次进入某帧的窗</strong>时最大（它拿本帧减上一帧），
     * 而那一刻位于该帧起点之后 {@code FRAME − HOP} 到 {@code FRAME} 之间。取区间中点，
     * 就是要把它前移的量。
     *
     * <p>不做这个修正会带来约 3.5 个包络帧（≈37 ms）的<strong>系统性偏早</strong>。
     * 对拍点来说是致命的：切点会稳定地偏早一个固定量，听感上「总差一点」，
     * 而且换首曲子偏的量还不一样——排查起来极其费劲。
     *
     * <p>预计算成常量：{@code (2048 − 256) / 512 = 3.5}。
     *
     * <p>包私有（上游是模块私有）而非 {@code private}：{@code AnalysisTest} 里有一套
     * 可执行的证据在直接钉这个常量与其方向，见
     * {@code envelopeCenterOffsetIsThreeAndAHalfFrames} 与
     * {@code withoutTheOffsetThePhaseIsSystematicallyEarly}。
     */
    static final float ENV_CENTER_OFFSET = ((float) FRAME - (float) HOP * 0.5f) / (float) HOP;

    private Analysis() {
    }

    /**
     * 起始包络。
     *
     * <p>字段与上游 {@code struct Onsets} 一一对应、顺序一致：{@code envelope} / {@code hop}
     * / {@code frame}。上游 {@code #[derive(Debug, Clone)]}：这里字段全 {@code final}，
     * 不需要复制构造；{@code envelope} <strong>不做防御性拷贝</strong>——上游的
     * {@code Vec<f32>} 也是公开可变的（{@code onset_envelope} 里就在往里 push），
     * 保持一致，调用方知道这是自己的缓冲。
     */
    public static final class Onsets {

        /** 每帧一个值。索引 {@code i} 对应原始样本位置 {@code i * hop}。 */
        public final float[] envelope;

        /** 帧移（样本）。 */
        public final int hop;

        /** 帧长（样本）。 */
        public final int frame;

        public Onsets(float[] envelope, int hop, int frame) {
            this.envelope = envelope;
            this.hop = hop;
            this.frame = frame;
        }

        /** 帧数。 */
        public int len() {
            return envelope.length;
        }

        /** 是否为空。 */
        public boolean isEmpty() {
            return envelope.length == 0;
        }

        /** 转成「帧/秒」。 */
        public float frameRate(int sampleRate) {
            return (float) sampleRate / (float) hop;
        }

        /**
         * 只打印形状，不打印整条包络。
         *
         * <p>上游 {@code #[derive(Debug)]} 会把几千个浮点数刷进日志（一段 8 秒素材就是
         * 700 多个值），与本仓库 {@code AudioBuf}/{@code Fft} 的处理同理，这里只留能
         * 定位问题的信息。
         */
        @Override
        public String toString() {
            return "Onsets { envelope: [" + envelope.length + " 个包络值], hop: " + hop
                    + ", frame: " + frame + " }";
        }
    }

    /**
     * 节奏结论。
     *
     * <p>字段与上游 {@code struct Tempo} 一一对应、顺序一致：
     * {@code bpm} / {@code period_frames} / {@code phase_frames} / {@code confidence}。
     * 上游是 {@code #[derive(Debug, Clone, Copy, PartialEq)]} 的不可变值类型，
     * 这里做成不可变（字段全 {@code final}），{@code Copy} 天然满足。
     */
    public static final class Tempo {

        /** 每分钟拍数。 */
        public final float bpm;

        /** 拍周期（样本）。 */
        public final float periodFrames;

        /** 第一拍的位置（样本，{@code 0..periodFrames}）。 */
        public final float phaseFrames;

        /** 置信度（0..1）。自相关主峰相对其邻域的能量占比。 */
        public final float confidence;

        public Tempo(float bpm, float periodFrames, float phaseFrames, float confidence) {
            this.bpm = bpm;
            this.periodFrames = periodFrames;
            this.phaseFrames = phaseFrames;
            this.confidence = confidence;
        }

        /**
         * 给定采样位置，返回<strong>不小于它的下一拍</strong>。
         *
         * <p>{@code k = ceil((frame − phase) / period)}：先算「相位栅格上第几拍」，
         * 再乘回周期。{@code ceil} 而不是 {@code floor} 就是「不小于」的语义。
         */
        public long nextBeatAfter(long frame) {
            if (periodFrames <= 1.0f) {
                return frame;
            }
            float f = (float) frame;
            float k = ceilF32((f - phaseFrames) / periodFrames);
            float beat = phaseFrames + k * periodFrames;
            if (beat < 0.0f) {
                return frame;
            }
            return (long) roundF32(beat);
        }

        /**
         * 给定采样位置，返回<strong>不大于它的上一拍</strong>。
         */
        public long prevBeatBefore(long frame) {
            if (periodFrames <= 1.0f) {
                return frame;
            }
            float f = (float) frame;
            float k = floorF32((f - phaseFrames) / periodFrames);
            float beat = phaseFrames + k * periodFrames;
            if (beat < 0.0f) {
                return frame;
            }
            return (long) roundF32(beat);
        }

        /**
         * 从 {@code frame} 起第 {@code n} 拍的位置。
         *
         * <p>{@code n} 可以为负（往前数），于是 {@code t} 可能落成负数——
         * 那种情况下按上游返回 0（采样位置没有负数），而不是回绕。
         */
        public long beatOffsetBy(long frame, long n) {
            long base = nextBeatAfter(frame);
            float t = (float) base + (float) n * periodFrames;
            if (t < 0.0f) {
                return 0L;
            }
            return (long) roundF32(t);
        }

        /**
         * 一行可读说明。
         *
         * <p>上游是 {@code format!("{:.1} BPM（周期 {:.0} 帧，首拍 {:.0}，置信度 {:.2}）", ...)}。
         *
         * <p>用 {@link Locale#ROOT}：默认区域会把小数点写成逗号，日志解析会崩
         * （与 {@link Harmonic.Match#describe} 同一处理）。另外 Rust 的 {@code {:.1}}
         * 是 half-to-even，Java 的 {@code %.1f} 是 HALF_UP，两者在 {@code x.05} 这种
         * 边界上可能差 1 个末位——这一行是给人看的，不参与任何判定。
         */
        public String describe() {
            return String.format(Locale.ROOT, "%.1f BPM（周期 %.0f 帧，首拍 %.0f，置信度 %.2f）",
                    bpm, periodFrames, phaseFrames, confidence);
        }

        /**
         * 镜像上游 {@code #[derive(Debug)]} 的输出（字段名保持上游的下划线写法，
         * 方便与上游日志对照）。
         *
         * <p>注意 <strong>{@code describe()} 才是给人看的那一行</strong>：上游 {@code Tempo}
         * 并没有 {@code impl fmt::Display}，这里也不让 {@code toString()} 去冒充它，
         * 免得两处日志格式互相污染。
         */
        @Override
        public String toString() {
            return "Tempo { bpm: " + bpm
                    + ", period_frames: " + periodFrames
                    + ", phase_frames: " + phaseFrames
                    + ", confidence: " + confidence + " }";
        }

        /**
         * 对应上游 derive 的 {@code PartialEq}（四个字段全比）。
         *
         * <p>上游对 {@code f32} 用 {@code ==}（于是 {@code NaN != NaN}），这里用
         * {@link Float#compare}：保证 {@code equals} 的自反性。结论里的浮点值只在有限值上
         * 产生（NaN 的包络会在 {@code zero <= 1e-12} 那关就被拦掉），两种写法在正常
         * 路径上完全一致。
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Tempo)) {
                return false;
            }
            Tempo t = (Tempo) o;
            return Float.compare(bpm, t.bpm) == 0
                    && Float.compare(periodFrames, t.periodFrames) == 0
                    && Float.compare(phaseFrames, t.phaseFrames) == 0
                    && Float.compare(confidence, t.confidence) == 0;
        }

        @Override
        public int hashCode() {
            int h = Float.floatToIntBits(bpm);
            h = 31 * h + Float.floatToIntBits(periodFrames);
            h = 31 * h + Float.floatToIntBits(phaseFrames);
            h = 31 * h + Float.floatToIntBits(confidence);
            return h;
        }
    }

    /**
     * 算起始包络（频谱通量）。
     *
     * <p>传进来的应当是<strong>单声道</strong>信号；多声道请调用方先折叠（{@link #foldToMono}）。
     *
     * <p>素材太短（不足两帧）或采样率为 0 时返回一个<strong>空</strong>包络
     * （{@code hop}/{@code frame} 仍是约定的值），调用方据此知道「没得分析」。
     */
    public static Onsets onsetEnvelope(float[] pcm, int sampleRate) {
        if (pcm.length < FRAME * 2 || sampleRate == 0) {
            return new Onsets(new float[0], HOP, FRAME);
        }

        Fft fft = new Fft(FRAME);
        int bins = FRAME / 2 + 1;

        float[] window = new float[FRAME];
        Fft.hann(window);

        float[] re = new float[FRAME];
        float[] im = new float[FRAME];
        float[] mag = new float[bins];
        float[] prev = new float[bins];
        boolean firstFrame = true;

        int frames = (pcm.length - FRAME) / HOP + 1;
        // 上游是 `out.envelope.reserve(frames)` 再逐帧 push —— 每次迭代恰好 push 一个值，
        // 所以最终长度就是 frames，这里直接按帧数分配、按下标写。
        float[] envelope = new float[frames];

        for (int f = 0; f < frames; f++) {
            int start = f * HOP;
            for (int i = 0; i < FRAME; i++) {
                re[i] = pcm[start + i] * window[i];
                im[i] = 0.0f;
            }
            fft.forward(re, im);
            fft.magnitude(re, im, mag);

            if (firstFrame) {
                System.arraycopy(mag, 0, prev, 0, bins);
                firstFrame = false;
                envelope[f] = 0.0f;
                continue;
            }

            // 半波整流：只累加「变强」的部分。
            // 取绝对值会把声音的**消失**也当成事件，那样每个音符的尾巴都会触发一次。
            float flux = 0.0f;
            for (int k = 0; k < bins; k++) {
                float d = mag[k] - prev[k];
                if (d > 0.0f) {
                    flux += d;
                }
            }
            // 除以频点数，让不同 FFT 长度下的量级可比
            envelope[f] = flux / (float) bins;

            System.arraycopy(mag, 0, prev, 0, bins);
        }

        Onsets out = new Onsets(envelope, HOP, FRAME);
        // 去均值：自相关最怕直流分量，它会让所有延迟上都叠一个大常数
        removeMean(out.envelope);
        return out;
    }

    private static void removeMean(float[] x) {
        if (x.length == 0) {
            return;
        }
        float sum = 0.0f;
        for (int i = 0; i < x.length; i++) {
            sum += x[i];
        }
        float m = sum / (float) x.length;
        for (int i = 0; i < x.length; i++) {
            x[i] -= m;
        }
    }

    /**
     * 从起始包络估速度与相位。
     *
     * <p>返回 {@code null} 表示<strong>素材没有可辨的节拍</strong>（静音、自由速度的古典乐、
     * 纯噪声）。宁可不表态，也不要编一个 BPM 出来——照着一个假结论去切点，比不切更糟。
     */
    public static Tempo estimateTempo(Onsets onsets, int sampleRate) {
        float[] env = onsets.envelope;
        if (sampleRate == 0 || env.length < 64) {
            return null;
        }

        float fr = onsets.frameRate(sampleRate);
        if (fr <= 0.0f) {
            return null;
        }

        // 允许的延迟范围（以帧为单位）。
        // 上游是 `as usize`：值恒为正（fr > 0），截断与 floor 等价；lag_max 还要被
        // 半个包络长度夹住——一个周期长过半个素材是估不出来的。
        int lagMin = Math.max((int) floorF32(fr * 60.0f / MAX_BPM), 2);
        int lagMax = Math.min((int) ceilF32(fr * 60.0f / MIN_BPM), env.length / 2);
        if (lagMax <= lagMin + 1) {
            return null;
        }

        // 取到 2 倍最长延迟，这样「最佳延迟的一半处」也能被看到，
        // 用于识别倍速错误
        float[] ac = new float[lagMax + 1];
        Fft.autocorrelate(env, lagMax, ac);

        // 注意：上游的 autocorrelate 只写 out 的前 min(out.len(), max_lag) 个位置，
        // 所以 ac[lagMax] 保持初始的 0（上游同样如此，不是移植差异）。
        float zero = Math.abs(ac.length > 0 ? ac[0] : 0.0f);
        if (zero <= 1e-12f) {
            return null;
        }

        // 乘速度先验后取最大。
        // 起始值是 f32 的最小值（≈ -3.4e38），不是 Java 的 Float.MIN_VALUE
        // （那是「最小正数」，用它当代数下界是经典坑）。
        int bestLag = 0;
        float bestScore = -Float.MAX_VALUE;

        for (int lag = lagMin; lag <= lagMax; lag++) {
            float bpm = fr * 60.0f / (float) lag;
            float score = ac[lag] * tempoPrior(bpm);
            if (score > bestScore) {
                bestScore = score;
                bestLag = lag;
            }
        }
        if (bestLag == 0) {
            return null;
        }

        // 抛物线插值：整数延迟的分辨率不够。93.75 帧/秒下相邻整数延迟
        // 对应约 2.5 BPM 的跳变，听感上足够明显（尤其是要对拍的时候）。
        float periodEnv = refinePeak(ac, bestLag);

        // 置信度：主峰相对整个搜索区间平均值的倍数。
        // 一个「真正的拍」在自相关上是一个尖峰；噪声则是一片差不多高的丘陵，
        // 平均值与峰值接近，置信度就低。
        float sum = 0.0f;
        for (int l = lagMin; l <= lagMax; l++) {
            sum += Math.abs(ac[l]);
        }
        // 上游是 `.sum::<f32>().max(1e-12)`：Rust 的 f32::max 遇 NaN 返回**另一个**
        // 操作数，Java 的 Math.max 会把 NaN 透出去。这里按 f32::max 的语义手写，
        // 免得一个 NaN 置信度顺着 `confidence < MIN_CONFIDENCE`（NaN < x 为假）
        // 一路混成「可信结论」返回给调用方。
        float mean = (sum > 1e-12f ? sum : 1e-12f) / (float) (lagMax - lagMin + 1);
        float denom = mean > 1e-12f ? mean : 1e-12f;
        float confidence = clamp(Math.abs(ac[bestLag]) / denom / 8.0f, 0.0f, 1.0f);

        if (confidence < MIN_CONFIDENCE) {
            return null;
        }

        float phaseEnv = beatPhase(env, periodEnv);
        float hop = (float) onsets.hop;
        float periodFrames = periodEnv * hop;
        if (periodFrames < 1.0f) {
            return null;
        }

        return new Tempo(
                fr * 60.0f / periodEnv,
                periodFrames,
                // 上游的 `%` 是 fmod：结果符号跟被除数，Java 的浮点 `%` 语义相同
                // （这里不要换成 Math.floorMod —— 那是整数取模）。
                (phaseEnv * hop) % periodFrames,
                confidence);
    }

    /**
     * 速度先验：以 120 BPM 为中心的、在对数尺度上的高斯权重。
     *
     * <p>{@code !(bpm > 0.0)} 而不是 {@code bpm <= 0.0}：NaN 也走这一支（取反比较为真），
     * 与上游逐位一致。对数尺度而不是线性：BPM 的偏差感是比例性的（60→70 与
     * 180→210 是同一种「快了」）。
     *
     * <p>包私有而非 {@code private}：上游的私有 fn 对子模块 {@code mod tests} 可见，
     * Java 的对应可见性就是包私有（只给同包的 {@code AnalysisTest} 用）。
     */
    static float tempoPrior(float bpm) {
        if (!(bpm > 0.0f)) {
            return 0.0f;
        }
        float r = (float) Math.log((double) (bpm / PRIOR_CENTER_BPM));
        return (float) Math.exp((double) (-(r * r) / (2.0f * PRIOR_SIGMA * PRIOR_SIGMA)));
    }

    /**
     * 抛物线插值细化峰值位置。
     *
     * <p>三点 {@code (lag-1, lag, lag+1)} 拟合抛物线，顶点偏移
     * {@code delta = 0.5(a − c) / (a − 2b + c)}。两个兜底：分母接近 0（三点共线，
     * 顶点在无穷远）、{@code |delta| > 0.5}（峰不在这个整数位置附近，硬掰会掰到
     * 隔壁去）——两种情况都原样返回整数延迟。
     */
    private static float refinePeak(float[] ac, int lag) {
        if (lag == 0 || lag + 1 >= ac.length) {
            return (float) lag;
        }
        float a = ac[lag - 1];
        float b = ac[lag];
        float c = ac[lag + 1];
        float denom = a - 2.0f * b + c;
        if (Math.abs(denom) < 1e-12f) {
            return (float) lag;
        }
        float delta = 0.5f * (a - c) / denom;
        // 插值偏移超过半个延迟说明峰不在这个位置附近，别硬掰
        if (Math.abs(delta) > 0.5f) {
            return (float) lag;
        }
        return (float) lag + delta;
    }

    /**
     * 折叠求相位：把包络按周期折叠，取累加和最大的偏移。
     *
     * <p>返回值<strong>已经包含</strong> {@link #ENV_CENTER_OFFSET} 的修正，单位是包络帧。
     *
     * <p>用 1/4 帧的细分搜索而不是整数帧：一个包络帧对应 {@code HOP} 个采样，
     * 整数分辨率下相位误差最大到 512 个采样（≈10 ms），对拍点来说太粗。
     * 折叠求和本身很便宜，细分 4 倍只多花几十微秒。
     *
     * <p>包私有而非 {@code private}：理由同 {@link #tempoPrior}。
     */
    static float beatPhase(float[] env, float period) {
        if (period <= 1.0f) {
            // 周期小于一帧时折叠没有意义：此时唯一诚实的答案就是「时间归属修正量」本身
            return ENV_CENTER_OFFSET;
        }
        float sub = 4.0f;
        int steps = (int) ceilF32(period * sub);
        int loops = Math.max(steps, 1);
        float bestOff = 0.0f;
        float best = -Float.MAX_VALUE;

        for (int s = 0; s < loops; s++) {
            float off = (float) s / sub;
            float sum = 0.0f;
            float t = off;
            while (t < (float) env.length) {
                sum += sampleLinear(env, t);
                t += period;
            }
            if (sum > best) {
                best = sum;
                bestOff = off;
            }
        }
        return bestOff + ENV_CENTER_OFFSET;
    }

    /**
     * 栅格落点的强度：{@code t} 前后各半格之内的最大值（也就是 {@code floor(t)} 与
     * {@code ceil(t)} 两格取大）。
     *
     * <p>为什么不直接用线性插值：包络**只有一格的分辨率**，而真实周期经常落在半格上
     * （150 BPM、48 kHz、跳 512 → 37.5 格）。这时一次击打的能量被摊进相邻两格，
     * 插值出来只有半高，于是「隔两拍」的整数格周期（75 格）在归一化得分上反而赢——
     * 实测：照搬 Melodify 的自相关选择 + 插值打分会把 150 BPM 报成 <b>75.00</b>；
     * 换成「±半格取大」之后 150 → 150.00，而真正的 75 BPM 素材仍然报 75.00
     * （它的半格落点落在两拍中间，那里没有起音）。
     */
    private static float peakAround(float[] onset, float t) {
        int lo = (int) floorF32(t);
        int hi = lo + 1;
        float a = (lo >= 0 && lo < onset.length) ? onset[lo] : 0.0f;
        float b = (hi >= 0 && hi < onset.length) ? onset[hi] : 0.0f;
        return Math.max(a, b);
    }

    /**
     * 线性插值采样。
     *
     * <p>越界一律给 0 而不是抛异常（上游用 {@code get(i).copied().unwrap_or(0.0)}）：
     * 折叠求和里最后一步经常会越过包络末端，那种「越界」是算法的正常部分。
     */
    private static float sampleLinear(float[] x, float pos) {
        if (x.length == 0 || pos < 0.0f) {
            return 0.0f;
        }
        int i = (int) floorF32(pos);
        if (i + 1 >= x.length) {
            return (i >= 0 && i < x.length) ? x[i] : 0.0f;
        }
        float frac = pos - (float) i;
        return x[i] + (x[i + 1] - x[i]) * frac;
    }

    /**
     * 一步到位：从单声道 PCM 得到节奏结论。
     */
    public static Tempo analyze(float[] pcm, int sampleRate) {
        // **起音用能量差分（Melodify 那套），不走频谱通量**——试过，撤了（2026-10-01）。
        //
        // 其他 AI 建议把起音换成 STFT 频谱通量（帧 2048/跳 512/Hann）。在合成点击轨上
        // 它确实没问题（`AnalysisTest` 那 20 条全绿），但在**真感素材**上它把倍速消歧带塌了：
        //
        //   * 「无底鼓」变体（军鼓在 2/4 + 踩镲八分）140 BPM → **70.01**（折成半速）；
        //   * 「底鼓每拍」的慢素材 70 BPM → **140.05**（选到了八分那一层）。
        //
        // 根因：频谱通量对踩镲这类**高频弱瞬态**过敏感，于是「八分 / 两拍」那一层的峰
        // 常常高于拍那一层，而倍速消歧判的是「哪套栅格解释的**能量**更多」——最响的那一路
        // （军鼓/底鼓）会把结论拉走。能量差分对低频瞬态更钝，反而与那条判据同向。
        // 判据就是 `AnalysisTempoRealisticTest` 那两条（当时当场红）。
        //
        // 速度估计（自相关 + 倍速消歧 + DP 对拍）见 {@link #tempoFromOnsetEnvelope}。
        int hops = pcm.length / ENERGY_HOP;
        if (sampleRate <= 0 || hops < ENERGY_HOP_MIN_HOPS) {
            return null;
        }
        float[] rms = new float[hops];
        for (int h = 0; h < hops; h++) {
            int base = h * ENERGY_HOP;
            double sum = 0.0;
            for (int i = 0; i < ENERGY_HOP; i++) {
                double v = pcm[base + i];
                sum += v * v;
            }
            rms[h] = (float) Math.sqrt(sum / ENERGY_HOP);
        }
        return tempoFromRmsFlux(rms, ENERGY_HOP, sampleRate);
    }

    // ==================================================================
    // 硬算速度：Melodify 的 BeatAnalyzer（照搬）
    // ==================================================================

    /** 能量包络的跳（样本）。Melodify {@code BeatAnalyzer.HOP}，也是本仓库原来的 {@link #HOP}。 */
    static final int ENERGY_HOP = 512;

    /** 包络至少要有这么多格才给结论（Melodify 的 {@code hops < 16}）。 */
    static final int ENERGY_HOP_MIN_HOPS = 16;

    /**
     * 允许的速度范围（BPM）。Melodify {@code BeatAnalyzer.MIN_BPM}/{@code MAX_BPM}。
     *
     * <p>注意它与旧的 {@link #MIN_BPM}/{@link #MAX_BPM}（60–200）不是一套：Melodify 只搜
     * 70–180，超出这个范围的速度会被自相关吸到它的倍速/半速上去（200 BPM 的素材报 100）。
     * <b>这是「照搬」的一部分</b>，不是笔误——照搬的判据在 {@code AnalysisTest} 里。
     */
    static final float ENERGY_MIN_BPM = 70.0f;
    static final float ENERGY_MAX_BPM = 180.0f;

    /**
     * 快速度偏置：{@code ac *= 1 + 0.0005 * (maxLag - lag)}（Melodify 原式）。
     *
     * <p>为什么需要它：自相关天然随延迟变长而衰减，不补一点权重的话「慢的那一半」
     * 总是赢——一段 128 BPM 的素材会被报成 64（半速），而半速的拍栅格挪点位时
     * 每两拍才对齐一次。
     */
    static final double FAST_TEMPO_BIAS_PER_LAG = 0.0005;

    /** 相位拟合的偏移细分（Melodify 是逐格；这里细到 1/4 格，见 {@link #tempoFromRmsFlux}）。 */
    static final int PHASE_SUBSTEPS = 4;

    /** 周期细分的分母（1/{@value} 格一步）与搜索半径（±{@value #PERIOD_SPAN_SUBSTEPS} 步 = ±半格）。 */
    static final int PERIOD_SUBSTEPS = 32;
    static final int PERIOD_SPAN_SUBSTEPS = 16;

    /**
     * 倍速消歧的第二条判据：半周期处的**带容差自相关**达到当前的这个倍数就折。
     *
     * <p>实测（合成点击轨，48 kHz，20 秒）：150 BPM 素材上比值≈1.0（折 ✔），
     * 75 BPM 素材上比值≈0.3（不折 ✔）。它单独用会在「底鼓每两拍一下」的素材上判不出来
     * （比值不到 0.6），所以与覆盖率判据**并联**用（见 {@link #tempoFromRmsFlux} 的 (b) 段）。
     */
    static final double OCTAVE_RATIO = 0.6;

    /** 覆盖率判据的门限（见 {@link #coverageOf} 与 (b) 段）。 */
    static final double OCTAVE_COVERAGE_MARGIN = 1.1;

    /** 最多往下折几档（p/2、p/4、p/8），且不许折出 70–180 的范围。 */
    static final int OCTAVE_FOLD_DEPTH = 3;

    /**
     * 某套栅格**解释了多少起音**（0..1）：去均值后的起始包络上，落在栅格点
     * ±1/4 周期之内的正能量占全部正能量的比例（相位取该周期下脉冲串最好的那个）。
     *
     * <p>随机的栅格也能拿到约 0.5（±1/4 周期占了一半时间轴），所以判据看的是**相对**关系：
     * 折半要求覆盖率明显更高（{@link #OCTAVE_COVERAGE_MARGIN}），相等不折
     * （慢歌在 p 与 p/2 上的覆盖率都是 1.0，相等就折会把 75 报成 150）。
     */
    static double coverageOf(float[] onset, int hops, float period) {
        if (period <= 1.0f) {
            return 0.0;
        }
        float phase = phaseOf(onset, hops, period);
        float tol = period * 0.25f;
        double total = 0.0;
        double covered = 0.0;
        for (int i = 0; i < hops; i++) {
            float v = onset[i];
            if (!(v > 0.0f)) {
                continue;
            }
            total += v;
            float rel = (float) i - phase;
            float mod = rel - period * (float) Math.floor((double) rel / (double) period);
            if (Math.min(mod, period - mod) <= tol) {
                covered += v;
            }
        }
        return total > 0.0 ? covered / total : 0.0;
    }

        /** 给定周期时脉冲串落点总强度最大的相位（Melodify 第 3 步正文；覆盖率判据也用它）。 */
    static float phaseOf(float[] onset, int hops, float period) {
        double bestSum = Double.NEGATIVE_INFINITY;
        float bestOff = 0.0f;
        int steps = Math.max(1, Math.round(period * PHASE_SUBSTEPS));
        for (int st = 0; st < steps; st++) {
            float off = (float) st / (float) PHASE_SUBSTEPS;
            double sum = 0.0;
            float t = off;
            while (t < (float) hops) {
                sum += sampleLinear(onset, t);
                t += period;
            }
            if (sum > bestSum) {
                bestSum = sum;
                bestOff = off;
            }
        }
        return bestOff;
    }

    /**
     * 带容差的自相关：{@code Σ onset[h] · peakAround(h − p)}（不归一）。
     *
     * <p>只给倍速消歧用——那里的两个候选（p 与 p/2）必须可比，而包络的半格分辨率
     * 会让 p/2 的逐点相乘只有半高（见 {@link #peakAround}）。相位拟合不用它。
     */
    private static double windowedAutocorrelation(float[] onset, int hops, float period) {
        int lag = (int) Math.ceil(period);
        if (lag <= 0 || lag >= hops) {
            return 0.0;
        }
        double ac = 0.0;
        for (int h = lag; h < hops; h++) {
            ac += (double) onset[h] * (double) peakAround(onset, (float) h - period);
        }
        return ac;
    }


    /**
     * <b>硬算速度：Melodify 的 {@code BeatAnalyzer}</b>（逐行照搬那三步）。
     *
     * <h3>为什么换掉原来那套</h3>
     *
     * <p>用户 2026-09-30：「硬算 bpm 你直接照搬 Melodify 的，你现在这个太不准了如果不开 ai」。
     * 原来那套是「FFT 频谱通量包络 → FFT 自相关 → <b>以 120 BPM 为中心的对数高斯先验</b>
     * （σ=0.5）→ 抛物线细化」。那个先验是主要问题：它对 120 附近的延迟给了 1.0 的权重，
     * 180 只剩 0.72、90 只剩 0.87——一段真正的 90 或 160 BPM 素材经常被它拉到
     * 倍速/半速去（而拍栅格错半速＝每两拍才对一次）。
     *
     * <p>Melodify 的做法没有先验，代之以<b>朝快速度的线性偏置</b>（见
     * {@link #FAST_TEMPO_BIAS_PER_LAG}），并且在 70–180 这个更窄的区间里搜。
     *
     * <h3>三步（照搬，连常数都照抄）</h3>
     *
     * <ol>
     *   <li><b>起始强度包络</b>：每 {@value #ENERGY_HOP} 个采样算一格 RMS，取<b>正向</b>
     *       一阶差分（半波整流）作为「这一格有起音」的证据；总能量太小就当没有结论。
     *       再去均值——自相关量的是周期性，不是直流分量；</li>
     *   <li><b>自相关</b>：延迟范围 = 70–180 BPM 对应的格数，乘快速度偏置后取最大；</li>
     *   <li><b>相位</b>：在最佳周期上滑动一串脉冲，取「落在起音上的总强度」最大的那个偏移。</li>
     * </ol>
     *
     * <h3>与 Melodify 的两处刻意差异（都必须有话）</h3>
     *
     * <ol>
     *   <li><b>保留「不表态」这一档</b>：Melodify 只在太短/近乎静音时返回 {@code null}，
     *       而本仓库的纪律是「算不出来就别猜」（{@code AutoCue.Envelope.hasBeatGrid()} 会拿
     *       置信度决定要不要用这个拍栅格去挪点位）。所以这里按 Melodify 自己的两个量算一个
     *       置信度（主峰 ÷ 搜索区间平均 |自相关| ÷ 8，与原实现同一把尺子），
     *       低于 {@link #MIN_CONFIDENCE} 时返回 {@code null}——
     *       <b>白噪声也能被自相关测出一个周期</b>，照着一个假栅格挪点位比不对齐更糟；</li>
     *   <li><b>不产小节相位</b>：Melodify 还会再拟合一个四拍的小节相位（Apple 的 AutoMix 按小节
     *       交接）。本仓库的点位对齐只到拍，所以那一项没搬——搬来也没人读。</li>
     * </ol>
     *
     * @param rms        每 {@code hop} 个采样的 RMS（<b>未去均值</b>；本函数自己去）
     * @param hop        包络的跳（样本）。调用方必须给真值——相位就是靠它换算成采样位置的
     * @param sampleRate 源文件采样率（决定 {@code periodFrames}/{@code phaseFrames} 的换算）
     * @return 节奏结论；素材太短、近乎静音、或置信度不够时返回 {@code null}
     */
    public static Tempo tempoFromRmsFlux(float[] rms, int hop, int sampleRate) {
        if (rms == null || sampleRate <= 0 || hop <= 0 || rms.length < ENERGY_HOP_MIN_HOPS) {
            return null;
        }
        int hops = rms.length;

        // ── 1. 起始强度包络：每格 RMS 的正向差分 ──────────────────────────
        float[] onset = new float[hops];
        float prevE = 0.0f;
        double total = 0.0;
        for (int h = 0; h < hops; h++) {
            float e = rms[h];
            float flux = e - prevE;
            onset[h] = flux > 0.0f ? flux : 0.0f;
            prevE = e;
            total += onset[h];
        }
        if (total < 1e-4) {
            return null;                  // 近乎静音：没有可辨的起音
        }
        float mean = (float) (total / (double) hops);
        for (int h = 0; h < hops; h++) {
            onset[h] -= mean;
        }
        // 能量差分那条路：起音落在那一格之内，整格算进去（见 attributionBins 的说明）。
        return tempoFromOnsetEnvelope(onset, hop, sampleRate, 1.0f);
    }

    /**
     * <b>从起始包络估速度</b>（自相关 + comb 倍频加权 + 倍速消歧 + 相位联合细化）——
     * 生产上两条路都走这里：
     *
     * <ul>
     *   <li>{@link #analyze}：PCM → <b>频谱通量</b>包络（STFT 2048/512/Hann）；</li>
     *   <li>{@link #tempoFromRmsFlux}：扫描期的逐格 RMS → 能量差分包络。</li>
     * </ul>
     *
     * @param onset      <b>已去均值</b>的起始包络（正的部分才算起音）
     * @param hop        包络的跳（样本）
     * @param sampleRate 源采样率
     */
    public static Tempo tempoFromOnsetEnvelope(float[] onset, int hop, int sampleRate) {
        return tempoFromOnsetEnvelope(onset, hop, sampleRate, 0.0f);
    }

    /**
     * 同上，外加「这一格的时间归属」偏移（单位：格）。
     *
     * <p>两条路用的包络，起音在格内的位置不同：能量差分那条路起音落在**那一格之内**
     * （归属偏移 1 格，保证相位落在起音之后）；频谱通量那条路的包络按 STFT 帧算，
     * 帧的**中心**才是它的时间归属（偏移 = {@code (frame − hop/2) / hop}，即旧路径里的
     * {@code ENV_CENTER_OFFSET = 3.5}）。
     */
    public static Tempo tempoFromOnsetEnvelope(float[] onset, int hop, int sampleRate,
                                               float attributionBins) {
        if (onset == null || sampleRate <= 0 || hop <= 0
                || onset.length < ENERGY_HOP_MIN_HOPS) {
            return null;
        }
        int hops = onset.length;
        float envRate = (float) sampleRate / (float) hop;
        int minLag = Math.max(1, Math.round(envRate * 60.0f / ENERGY_MAX_BPM));
        int maxLag = Math.min(hops - 1, Math.round(envRate * 60.0f / ENERGY_MIN_BPM));
        if (maxLag <= minLag) {
            return null;
        }


        // ── 2. 自相关（延迟域直接算，Melodify 就是两层循环）────────────────
        //
        // 顺手把「不带偏置的自相关」留在 acRaw 里：置信度必须用它。
        // 偏置会随延迟线性抬高远处的峰，拿带偏置的值去算置信度，等于把
        // 「慢速的假峰」也算成可信。
        float[] acRaw = new float[maxLag + 1];
        int bestLag = minLag;
        double bestAc = Double.NEGATIVE_INFINITY;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double ac = 0.0;
            for (int h = lag; h < hops; h++) {
                ac += (double) onset[h] * (double) onset[h - lag];
            }
            acRaw[lag] = (float) ac;
            // **comb 倍频加权试过、撤了**（2026-10-01）：其他 AI 建议的
            //
            //     score(lag) = ac[lag] + 0.5·ac[lag/2] + 0.5·ac[2·lag]
            //
            // 在合成素材上实测是**反向**的：它给「两倍周期处有峰」的短延迟加分，
            // 于是 70 BPM 的素材被报成 **140.05**（判据 `slowTemposAreNotFoldedUp` 当场红）。
            // 原因：真正的拍周期 T 在 2T 处当然也有峰，而这一项奖励的是 lag=T
            // （因为 ac[2·lag] = ac[2T] 很强），等于系统性地偏向**快一倍**。
            // 倍频那件事由下面的「倍速消歧」管——那一步是**按覆盖率/自相关相对大小**判的，
            // 不预设方向，两种错法（半速、倍速）都能纠。
            double biased = ac * (1.0 + FAST_TEMPO_BIAS_PER_LAG * (double) (maxLag - lag));
            if (biased > bestAc) {
                bestAc = biased;
                bestLag = lag;
            }
        }

        // ── 2b/3. 亚格点周期 + 相位：**联合细化**（Melodify 没有这一步，是本仓库补的）──
        //
        // 照搬的那套只给整数延迟，而整数延迟的速度分辨率很差：48 kHz、跳 512 时，
        // 120 BPM（24000 采样 = 46.875 格）只能落在 46 格（122.3 BPM）或 47 格
        // （119.7 BPM）上——0.3% 的周期误差，在 10 秒的交叠窗口里就是 30 ms 的漂移
        // （实测：`AutoCueBeatTest#scanFindsTheClickGrid` 要求拍点落在真实栅格 40 ms 内，
        // 只按整数延迟直接红）。
        //
        // 细化用的目标函数就是 Melodify 第 3 步那个「脉冲串落点总强度」，
        // 只是把周期也一起搜：在 AC 选中的延迟附近 ±{@value #PERIOD_SPAN_SUBSTEPS}/
        // {@value #PERIOD_SUBSTEPS} 格里找总和最大的 (周期, 相位) 组合。
        // ── 3. 亚格点周期 + 倍速消歧 + 相位 ──────────────────────────────
        //
        // (a) 亚格点细化：规格那套只给整数延迟，而整数延迟的分辨率很差——48 kHz、
        //     跳 512 时 120 BPM（24000 采样 = 46.875 格）只能落在 46（122.3 BPM）
        //     或 47（119.7 BPM）上，0.3% 的周期误差在 10 秒窗口里就是 30 ms 漂移。
        //     沿用本仓库原有的抛物线插值（{@link #refinePeak}），只在选出来的延迟附近细化。
        float periodEnv = refinePeak(acRaw, bestLag);
        float bestOff = 0.0f;

        // (b) 倍速消歧（**Melodify 没有这一步**，见 docs §8.1）。
        //
        // 自相关挑的是**最强的周期性**，不是「人听到的拍」。实测（合成真感素材：
        // 底鼓每两拍一下、军鼓在 2/4、踩镲八分、贝斯切分）：150 BPM 被报成 75.00——
        // 底鼓那个周期性最强，而拍其实由踩镲与军鼓定义。
        //
        // 判据用「落点**平均**强度」（{@link #combMean}，含快速度偏置）：
        //
        //   * 半拍感觉（该折）：150 的栅格每一下都落在军鼓/底鼓上，75 的只落在底鼓上，
        //     两者均值相当 → 里面的快速度偏置把它压向 150 ✔；
        //   * 慢歌 / 慢节拍轨（不该折）：150 的栅格有一半落点落在两拍之间的**静音**上
        //     （去均值之后是负值）→ 均值明显更低 ✔ 不折。
        //
        // 为什么不用「求和」或「覆盖率」：求和让慢周期白占便宜（落点少、摊到的负值少）；
        // 覆盖率（±1/4 周期内算命中）的容差随周期变宽，慢的那套又能白占便宜——
        // 实测覆盖率判据会把切分贝斯的 150 报成 75。也不再用「带容差自相关 + 阈值」：
        // 阈值 0.6 在半拍感觉的素材上判不出来（比值不到 0.6），150 照样报 75。
        float chosenPeriod = periodEnv;
        for (int k = 1; k <= OCTAVE_FOLD_DEPTH; k++) {
            float faster = chosenPeriod * 0.5f;
            if (faster <= 1.0f || 60.0f * envRate / faster > ENERGY_MAX_BPM) {
                break;
            }
            // **两条判据，任一条成立就折**（两条各自对付一类素材，实测互补）：
            //
            //   ① 覆盖率：更快的栅格解释的起音明显更多（≥{@link #OCTAVE_COVERAGE_MARGIN} 倍）。
            //      对付「底鼓每两拍一下、拍由军鼓/踩镲定义」的素材；
            //   ② 带容差自相关：半周期处的自相关仍够强（≥{@link #OCTAVE_RATIO} 倍）。
            //      对付「贝斯切分」那类——快栅格与慢栅格的覆盖率咬得很近，只有自相关分得开。
            //
            // 慢素材两边都不会触发：半周期栅格有一半落点落在静音上（覆盖率不涨、
            // 自相关也弱）——实测 75 BPM 的慢歌与 75 BPM 的节拍轨都不折。
            boolean byCoverage = coverageOf(onset, hops, faster)
                    >= coverageOf(onset, hops, chosenPeriod) * OCTAVE_COVERAGE_MARGIN;
            boolean byAutocorrelation = windowedAutocorrelation(onset, hops, faster)
                    >= windowedAutocorrelation(onset, hops, chosenPeriod) * OCTAVE_RATIO;
            if (!byCoverage && !byAutocorrelation) {
                break;
            }
            chosenPeriod = faster;
        }
        periodEnv = refinePeak(acRaw, Math.max(2, Math.round(chosenPeriod)));

        // (c0) **节拍跟踪：Ellis 风格动态规划**（2026-10-01，其他 AI 的建议 + 用户
        //      「对拍还不行」）。给定周期之后，不再只拟合一个固定相位，而是找一条
        //      「局部起音强 + 间隔规整」的最优路径；路径上的拍点中位数反推周期、
        //      第一个拍点反推相位。接口不变（{@code Tempo} 仍是周期 + 相位），
        //      所以下游的对拍、自动点位、Flow 排序一行都不用动。
        //
        //      DP 失败（素材太短、起音太少）就退回下面那个脉冲串拟合——两条都留着，
        //      判据里两条路都被覆盖。
        //
        // (c) 周期 + 相位的联合细化，以及 Melodify 第 3 步那个「脉冲串落点总强度」——
        //     三件事一次做完：在 (b) 定下来的周期附近 ±1 格按 1/32 格扫，每个周期再按
        //     1/4 格扫相位，取落点总强度最大的那一对。
        //
        //     为什么不能只靠抛物线（{@link #refinePeak}）：自相关的峰在半格周期上是**扁**的
        //     （击打能量摊在相邻两格上），抛物线顶点因此不稳——实测周期会差 0.2%，
        //     20 秒里漂 40 ms，相位误差从 7 ms 涨到 70 ms。脉冲串的目标函数在真周期处
        //     才是尖的。
        //
        //     落点采样用**线性插值**，不用 (b) 那个「±半格取大」：容差是为了让两个候选
        //     可比，用在相位上就等于允许落点在半格里随便落。
        double bestPhaseSum = Double.NEGATIVE_INFINITY;
        float basePeriod = periodEnv;
        for (int pi = -PERIOD_SPAN_SUBSTEPS; pi <= PERIOD_SPAN_SUBSTEPS; pi++) {
            float period = basePeriod + (float) pi / (float) PERIOD_SUBSTEPS;
            if (period <= 1.0f || period >= (float) hops) {
                continue;
            }
            int steps = Math.max(1, Math.round(period * PHASE_SUBSTEPS));
            for (int st = 0; st < steps; st++) {
                float off = (float) st / (float) PHASE_SUBSTEPS;
                double sum = 0.0;
                float t = off;
                while (t < (float) hops) {
                    sum += sampleLinear(onset, t);
                    t += period;
                }
                if (sum > bestPhaseSum) {
                    bestPhaseSum = sum;
                    periodEnv = period;
                    bestOff = off;
                }
            }
        }
        // ── 4. 置信度：归一化自相关（{@code |ac[结论周期]| / 包络总能量}）────────────
        //
        // 「有没有拍」的判据换过四把尺子，这是第五把，也是第一把在生产的**两档 hop**
        // 上都成立的一把（实测 2026-10-01，48 kHz，包络 = 逐格 RMS 正向差分后去均值）：
        //
        //     素材                 旧尺子（峰/均/8）   新尺子（|ac|/能量）
        //     点击轨 120 BPM            1.000              0.842
        //     点击轨 128 BPM            1.000              0.941
        //     白噪声（跳 512）           0.450              0.041
        //     白噪声（跳 0.02 s）        0.532              0.039
        //     纯正弦 440 Hz             0.217              0.120
        //     skydive 头 12 s           0.779              0.208   ← PCM 路（跳 512）
        //     skydive 尾 12 s           0.255              0.226
        //     skydive 整曲扫描           0.439              0.259   ← 扫描路（跳 0.02 s）
        //     春めく 头/尾/整曲扫描    1.000/1.000/0.884  0.617/0.772/0.704
        //
        // 旧尺子的毛病是**分母随延迟格数变**：同一首 skydive，PCM 路 0.779、扫描路 0.439
        // ——而 {@code AutoCue} 的拍栅格门槛当时是 0.5，于是扫描路上「BPM 明明算对了 128.0，
        // 却判定没有节拍」，出场点拿不到拍栅格（用户报的「对拍还不行」有一半是这个）。
        // 新尺子的分子分母都是同一个包络上的求和，生产两档 hop 落在同一个区间
        // （0.208~0.259），两档的噪声都是 0.04，中间隔着 5 倍，门限 {@link #MIN_AC_RATIO}
        // 取两边的几何中点偏保守那一侧。
        //
        // **已知边界，别拿「hop 无关」去宣传**：这套尺子只在**跳 ≳10 ms** 的包络上成立。
        // 跳细到 256/128（约 5.3/2.7 ms）时，能量差分抓到的是**格内抖动**而不是拍，同一段
        // skydive 头 12 秒：跳 512 报 127.8 BPM / 0.208，跳 256 报 100.5 / 0.090，
        // 跳 128 报 82.8 / 0.052——**速度本身就错了**，不是尺子的问题。Melodify 把
        // {@code HOP} 钉在 512 就是这个原因，生产的两条路（512 与 0.02 s）都在安全区。
        //
        // **同一天试过、已撤掉的三把补充闸**（都实测过，都不分，别再捡回来）：
        //
        //   * 「起音尖度」= 最强起音 ÷ 平均起音：点击轨 1.1、真歌 5.4~7.8、纯正弦 66、
        //     白噪声 26~60——看着分得挺开，但**判据素材「恒定乐音底噪 + 每拍瞬态」在
        //     PCM 路是 28~38**（同样的素材在扫描路才 4.3~5.1），把这条真节拍轨和纯音
        //     混成了一类，门限放哪边都错。
        //   * 「栅格落点增益」= 落点平均比包络平均强多少个标准差：真歌 skydive 扫描路
        //     只有 0.62，反而低于纯音的 0.84——真歌的包络本来就稠密，落点并不比平均强多少。
        //   * 「起音/电平」= 平均起音 ÷ 平均电平：skydive 扫描 0.042、春めく 0.075，
        //     而判据那两条素材是 0.004~0.029——**纯音 0.028 与真节拍轨 0.026 直接重叠**。
        //
        // 三条都失败在同一个地方：恒定乐音底噪上叠拍点的素材，与纯音在**包络统计上就是
        // 同一类**（逐格 RMS 里那点相位抖动占主导）。要真把它们分开需要真正的瞬态检测
        // （谱通量 + 逐带白化），那是另一件事，不在「换一把置信度尺子」的范围里。
        // 再上一版（2026-10-01 早些时候）同一把尺子量出「白噪声 0.42 > 真歌 0.255」，
        // 方向是反的，据此撤回过。**那批数字作废**：今天用两种彼此独立的归一化重算
        // （自己写逐格求和、以及 {@link dev.t1m3.qplayer.audio.dsp.Fft#autocorrelate} 的输出）
        // 在同一批素材上一致（白噪声 0.041 / 0.042、skydive 头 0.208 / 0.216、
        // 扫描路 skydive 0.259 / 0.260），上表就是当前口径。
        //
        // 分子取的是**最终报出去的周期**上的自相关，不是自相关选峰时那个延迟：
        // 倍速消歧（上面那段）之后两者可能差一倍，而尺子要量的是「我们报出去的这个周期
        // 有多像真的」。周期比 {@code minLag} 小是常态（128 BPM 折成 256 就会掉到搜索
        // 区间外），所以这里另算一遍，不复用 {@code acRaw}。
        int chosenLag = Math.max(1, Math.min(hops - 1, Math.round(periodEnv)));
        double acAtChosen = 0.0;
        for (int h = chosenLag; h < hops; h++) {
            acAtChosen += (double) onset[h] * (double) onset[h - chosenLag];
        }
        double zeroLag = 0.0;
        for (int h = 0; h < hops; h++) {
            zeroLag += (double) onset[h] * (double) onset[h];
        }
        float confidence = zeroLag > 1e-12
                ? clamp((float) (Math.abs(acAtChosen) / zeroLag), 0.0f, 1.0f)
                : 0.0f;
        if (confidence < MIN_AC_RATIO) {
            return null;
        }
        float hopF = (float) hop;
        float periodFrames = periodEnv * hopF;
        if (periodFrames < 1.0f) {
            return null;
        }
        float phaseFrames = ((bestOff + attributionBins) * hopF) % periodFrames;
        return new Tempo(60.0f * envRate / periodEnv, periodFrames, phaseFrames, confidence);
    }

    /**
     * <b>硬算路的专业前端</b>：白化对数频率谱通量（{@link SpectralOnsets}）+ 现有后端。
     *
     * <p>与 {@link #tempoFromRmsFlux} 共用后面三步（自相关 + 倍速消歧 + (周期, 相位)
     * 联合细化），换掉的只有「起始包络怎么来」这一步——2026-10-01 的所有实测毛病
     * （驻波素材报假速度、跳一细就崩、真歌包络过密、同曲不同片段结论不一致）都出在
     * 那一步上。两者的对比数据在 {@code docs/bpm-precision.md} 与判据
     * {@code BpmPrecisionTest} 里逐项列着，**新的必须逐项不差于旧的**。
     *
     * <p>相位归属用 {@link SpectralOnsets#ATTRIBUTION_BINS}（谱通量测的是「本帧尾部那
     * 一跳里新进来的能量」，与 RMS 那条路「起音落在这一格之内」的归属不同）。
     *
     * @param pcm  单声道 PCM
     * @param rate 采样率
     * @return 节奏结论；素材太短、太安静或置信度不够时返回 {@code null}
     */
    public static Tempo tempoFromSpectralFlux(float[] pcm, int rate) {
        float[] onset = SpectralOnsets.envelope(pcm, rate);
        if (onset == null) {
            return null;
        }
        return tempoFromOnsetEnvelope(onset, SpectralOnsets.HOP, rate,
                SpectralOnsets.ATTRIBUTION_BINS);
    }

    /**
     * <b>专业路子</b>：白化谱通量前端 + {@link BeatTracker 动态规划对拍 + 全局回归拟合}。
     *
     * <p>与 {@link #tempoFromSpectralFlux} 的差别只在后半段：那条沿用自相关选周期
     * （在真歌上会出 ×2/3、×4/3 的附点谐波错，见 {@code docs/bpm-precision.md} §2.5），
     * 这条把「落脚点必须踩在起音上」写进目标函数，并用整曲拍点做回归把周期精度收上来。
     */
    public static Tempo tempoFromSpectralFluxTracked(float[] pcm, int rate) {
        float[] onset = SpectralOnsets.envelope(pcm, rate);
        if (onset == null) {
            return null;
        }
        return BeatTracker.track(onset, SpectralOnsets.HOP, rate);
    }

    /**
     * 从<b>模型给出的拍点</b>（秒）推出节奏结论。
     *
     * <h3>本仓库新增，上游没有对应物</h3>
     *
     * <p>上游 {@code sonalis-model/src/beat.rs} 的 {@code BeatModel::beats} 只返回
     * 拍点所在的<b>秒数</b>（{@code Vec<f32>}），到 {@code Tempo} 的换算是空白的——
     * 它那边模型路线还没接进 automix（{@code TransitionSpec::AiStyle} 的
     * {@code use_model} 至今只是一句注释）。这一步是 QPlayer 补上的，
     * 因此<b>没有上游常量可逐行对照</b>，判定依据全部写在这里。
     *
     * <h3>做法</h3>
     *
     * <ol>
     *   <li><b>先用相邻间隔的中位数</b>定一个稳健的周期初值。不取平均：模型的拍点
     *       偶尔会漏一拍或多一拍，平均值会被单个异常间隔拉偏，中位数不会。</li>
     *   <li><b>再用最小二乘把周期细化到「帧以下」</b>（见 {@link #refineGrid}）。
     *       拍点的时间戳是<b>帧号</b>换算来的，而帧栅格只有 20 ms 一格
     *       （hop 441 @ 22050）——直接拿两个相邻拍点相减，周期就只能落在 20 ms 的
     *       整数倍上。128 BPM 的真实周期是 0.46875 s，会被量化成 0.46 s，
     *       也就是 <b>130.43 BPM</b>（实测，见下面的表）。而把整条拍点序列
     *       对「第几拍」做一次直线拟合，量化误差会相互抵消，周期能回到
     *       0.4688 s 附近。相位同理：取拟合出来的截距，比「第一个拍点」
     *       少半个格子的量化误差。</li>
     *   <li><b>相位</b>取拟合出来的第一个拍点位置对周期取模。与硬算路一样，
     *       相位是「拍点在<b>被分析的这段 PCM</b> 里的位置」——{@code AiMix} 的对齐
     *       正是按这个约定算的（{@code phase / fileRate}）。</li>
     *   <li><b>置信度 = 1 − 间隔的中位绝对偏差 / 周期</b>，夹到 0..1。
     *       <b>语义与硬算路的 {@code confidence}（自相关主峰相对邻域的能量占比）
     *       不同</b>——两者都落在 0..1，但值不可直接比较。目前没有任何判定读它
     *       （{@code Chooser} 只看速度差与调性），它是给人看的。</li>
     * </ol>
     *
     * <h3>实测（本机，合成节拍 12 秒 @48 kHz，beat_this_small）</h3>
     *
     * <table>
     *   <tr><th>真实 BPM</th><th>硬算</th><th>模型（只取中位数）</th><th>模型（加最小二乘）</th></tr>
     *   <tr><td>100</td><td>100.13</td><td>100.00</td><td>100.00</td></tr>
     *   <tr><td>120</td><td>119.89</td><td>120.00</td><td>120.00</td></tr>
     *   <tr><td>128</td><td>127.95</td><td><b>130.43</b></td><td>≈128.0</td></tr>
     *   <tr><td>140</td><td>140.20</td><td>142.86</td><td>≈140.0</td></tr>
     * </table>
     *
     * <p>100/120 这两种「拍周期恰好是帧移整数倍」的素材上，中位数本来就落在格点上，
     * 细化不会改变结论；128/140 这种落不到格点上的，差异就是上面那 2~3 BPM。
     *
     * <h3>刻意不做的事</h3>
     *
     * <p><b>不做半速/倍速折叠。</b> 硬算路靠「速度先验 + 自相关」隐式地偏向
     * 100–140 BPM，而模型给出的是它自己判断的拍（tactus）；如果它判成 60 而人听成
     * 120，那多半是「哪一层是拍」的听感问题，不是数值问题。这里造一个
     * 「BPM 落在 60..200 之外就乘 2 或除 2」的启发式，就是<b>顺手优化</b>——
     * 它会掩盖模型的真实输出，而且改不对时听感上只是「窗口长度怪怪的」，
     * 几乎查不出来。所以：<b>模型说什么就是什么</b>，超出范围也照报。
     *
     * @param beats      拍点（秒），升序；由 {@code BeatDetector.beats} 给出
     * @param sampleRate {@link Tempo#periodFrames}/{@link Tempo#phaseFrames} 要用的
     *                   采样率。<b>必须是源文件的采样率</b>（也就是渲染侧那个），
     *                   不是模型前端的 22050——拍点是秒，秒与采样率无关，
     *                   这里只决定「一秒算多少帧」
     * @return 节奏结论；拍点少于两个、间隔全为 0、或周期小于 1 毫秒时返回
     *         {@code null}（上游 {@code None}）——「算不出来就不表态」
     */
    public static Tempo tempoFromBeats(float[] beats, int sampleRate) {
        if (sampleRate <= 0 || beats == null || beats.length < 2) {
            return null;
        }

        // 相邻间隔。非正/非有限的间隔直接丢掉，不能参与中位数：
        // 模型输出的时间戳偶尔会有重复值（同一帧被挑出两次）。
        float[] intervals = new float[beats.length - 1];
        int n = 0;
        for (int i = 1; i < beats.length; i++) {
            float d = beats[i] - beats[i - 1];
            if (d > 1e-6f && !Float.isNaN(d) && !Float.isInfinite(d)) {
                intervals[n] = d;
                n++;
            }
        }
        if (n == 0) {
            return null;
        }

        float[] sorted = Arrays.copyOf(intervals, n);
        Arrays.sort(sorted);
        float periodSecs = median(sorted);
        if (!(periodSecs > 1e-3f)) {
            return null;
        }

        float[] dev = new float[n];
        for (int i = 0; i < n; i++) {
            dev[i] = Math.abs(intervals[i] - periodSecs);
        }
        Arrays.sort(dev);
        float mad = median(dev);
        float confidence = clamp(1.0f - mad / periodSecs, 0.0f, 1.0f);

        // 用整条序列把周期/相位细化到帧以下。拟不出来（拍点太少、异常点太多）
        // 就沿用中位数——那仍然是一个可用的结论，只是带一格量化误差。
        float period = periodSecs;
        float firstBeatSecs = beats[0];
        float[] fit = refineGrid(beats, periodSecs);
        if (fit != null) {
            period = fit[0];
            firstBeatSecs = fit[1];
        }

        float rate = (float) sampleRate;
        float periodFrames = period * rate;
        if (periodFrames < 1.0f) {
            return null;
        }
        // 上游 estimateTempo 的 `%` 是 fmod：结果符号跟被除数，Java 的浮点 `%` 语义相同。
        // 拍点时间戳非负，所以这里得到的相位落在 [0, periodFrames)。
        float phaseFrames = (firstBeatSecs * rate) % periodFrames;

        return new Tempo(60.0f / period, periodFrames, phaseFrames, confidence);
    }

    /**
     * 把整条拍点序列对「第几拍」做一次直线拟合，得到<b>帧以下分辨率</b>的周期与首个拍点。
     *
     * <h3>为什么需要</h3>
     *
     * <p>拍点时间戳是 {@code frameToSecs(帧号)}，也就是落在一个 20 ms 的栅格上。
     * 相邻两个拍点一相减，周期就只能取 20 ms 的整数倍：128 BPM 的 0.46875 s
     * 会被量化成 0.46 s（= 130.43 BPM）、140 BPM 的 0.42857 s 变成 0.42 s
     * （= 142.86 BPM）。而真实拍点在这条栅格上是「尽量靠近」的——误差在
     * 各拍之间正负交替、整体不累积，所以对「拍序号 → 时间」做一次最小二乘，
     * 斜率能把量化误差平掉，得到 0.4688 s 这种非栅格值。
     *
     * <h3>为什么要稳健化</h3>
     *
     * <p>模型漏一拍时那个拍点的「拍序号」会算错（相邻间隔翻倍），它在线性拟合里
     * 是一个巨大的异常点。做法与中位数同一个思路：先用中位数周期给每个拍点定一个
     * 整数序号，拟合一次，把残差超过 <b>四分之一周期</b>的点剔掉，再拟一次；
     * 两轮之后还有异常点就<b>整个放弃</b>，退回中位数结论——不冒险。
     *
     * @return {@code [周期秒, 第一个拍点的秒数]}；拟不出来时返回 {@code null}
     */
    private static float[] refineGrid(float[] beats, float periodGuess) {
        int m = beats.length;
        if (m < 4) {
            return null;
        }
        int[] k = new int[m];
        boolean[] keep = new boolean[m];
        for (int i = 0; i < m; i++) {
            // 「第几拍」由中位数周期给出；Math.round(float) 返回 int，
            // 这里的比值上界是「素材时长 / 一拍」，远在 int 范围内。
            k[i] = Math.round((beats[i] - beats[0]) / periodGuess);
            keep[i] = true;
        }
        // 跨不到两个周期就没有「斜率」可言，拟合会退化成噪声
        if (k[m - 1] - k[0] < 2) {
            return null;
        }

        for (int pass = 0; pass < 2; pass++) {
            // 累加量走 double：k 能到几万，k² × 点数在 int 里会溢出
            double sk = 0.0;
            double st = 0.0;
            double skk = 0.0;
            double skt = 0.0;
            int cnt = 0;
            for (int i = 0; i < m; i++) {
                if (!keep[i]) {
                    continue;
                }
                double ki = (double) k[i];
                double ti = (double) beats[i];
                sk += ki;
                st += ti;
                skk += ki * ki;
                skt += ki * ti;
                cnt++;
            }
            if (cnt < 4) {
                return null;
            }
            double denom = (double) cnt * skk - sk * sk;
            if (Math.abs(denom) < 1e-9) {
                return null;
            }
            double slope = ((double) cnt * skt - sk * st) / denom;
            double intercept = (st - slope * sk) / (double) cnt;
            // 斜率非正、或与初值差出半个周期以上：说明拟合被异常点带偏了
            if (!(slope > 0.0)
                    || slope < (double) periodGuess * 0.5
                    || slope > (double) periodGuess * 1.5) {
                return null;
            }

            boolean dropped = false;
            double tol = (double) periodGuess * 0.25;
            for (int i = 0; i < m; i++) {
                if (!keep[i]) {
                    continue;
                }
                double residual = Math.abs((double) beats[i] - (intercept + slope * (double) k[i]));
                if (residual > tol) {
                    keep[i] = false;
                    dropped = true;
                }
            }
            if (!dropped) {
                return new float[]{(float) slope, (float) intercept};
            }
        }
        // 两轮之后还有异常点：不冒险，退回中位数结论
        return null;
    }

    /**
     * 中位数。
     *
     * <p>入参必须是<b>已排序</b>的数组（调用点先 {@link Arrays#sort} 再调它）。
     * 偶数个元素时取中间两个的平均——这是「中位数」的常规定义，不是分位数插值。
     */
    private static float median(float[] sorted) {
        int n = sorted.length;
        if (n == 0) {
            return 0.0f;
        }
        int mid = n / 2;
        if ((n & 1) == 1) {
            return sorted[mid];
        }
        return (sorted[mid - 1] + sorted[mid]) * 0.5f;
    }

    /**
     * 把多声道折叠成单声道（分析用）。
     *
     * <p>分析不需要立体声信息，折叠掉还能让后面的自相关少算一遍。
     *
     * <p>上游 {@code &[&[f32]]} → {@code float[][]}（每个元素是一条声道）。单声道直接
     * 返回一份拷贝（上游 {@code first.to_vec()}）——注意上游注释里的意思：这条路径
     * <strong>不是</strong>「除以 1」，而是原样复制，避免多一次浮点除法。
     * 各声道长度不一致时按<strong>第一条</strong>的长度算，多余的样本丢弃（上游
     * {@code take} 的语义）。
     */
    public static float[] foldToMono(float[][] channels) {
        if (channels.length == 0) {
            return new float[0];
        }
        float[] first = channels[0];
        int n = first.length;
        if (channels.length == 1) {
            return Arrays.copyOf(first, n);
        }
        float[] out = new float[n];
        for (int c = 0; c < channels.length; c++) {
            float[] ch = channels[c];
            int take = Math.min(ch.length, n);
            for (int i = 0; i < take; i++) {
                out[i] += ch[i];
            }
        }
        float inv = 1.0f / (float) channels.length;
        for (int i = 0; i < n; i++) {
            out[i] *= inv;
        }
        return out;
    }

    /**
     * 镜像 Rust {@code f32::round}：最近整数，<strong>半数远离零</strong>。
     *
     * <p>Java 没有返回 {@code float} 的对应函数：{@code Math.round(float)} 返回 {@code int}
     * 且语义是 {@code floor(x+0.5)}（所有半数都往上走，负数上就与 Rust 不一致），
     * {@code Math.rint} 是半数取偶。这里利用「f32 宽化成 double 是精确的」这一点：
     * 在 double 里做 {@code floor(x+0.5)} / {@code ceil(x-0.5)} 得到的整数，就是那个
     * f32 精确值的最接近整数（半数远离零），再转回 float 无损。
     *
     * <p>NaN/无穷的传播也与上游一致：{@code NaN → NaN}、{@code ±∞ → ±∞}，之后
     * {@code (long)} 转换在 Java 里得到 0 / {@code Long.MAX_VALUE}，与 Rust 的
     * {@code as u64}（NaN→0，饱和到 {@code u64::MAX}）在采样位置这个量级上不可能分叉。
     */
    private static float roundF32(float v) {
        double d = (double) v;
        if (d < 0.0) {
            return (float) Math.ceil(d - 0.5);
        }
        return (float) Math.floor(d + 0.5);
    }

    /**
     * {@code f32::ceil} 的镜像（Java 只有 double 版）。
     *
     * <p>见类注释末尾：f32 宽化成 double 无损，ceil 的结果是整数，两边逐位相同。
     */
    private static float ceilF32(float v) {
        return (float) Math.ceil((double) v);
    }

    /** {@code f32::floor} 的镜像，理由同 {@link #ceilF32}。 */
    private static float floorF32(float v) {
        return (float) Math.floor((double) v);
    }

    /** 上游 {@code f32::clamp}：NaN 原样穿过去（Java 没有对应方法，行为手工对齐）。 */
    private static float clamp(float v, float lo, float hi) {
        if (v < lo) {
            return lo;
        }
        if (v > hi) {
            return hi;
        }
        return v;
    }
}
