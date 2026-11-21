package dev.t1m3.qplayer.audio.mix;

import java.util.Arrays;
import java.util.Locale;

/**
 * 和声匹配 —— 「声波重合」的计算核心。
 *
 * <h3>它回答什么问题</h3>
 *
 * <p>两首歌接连播放，如果调性不搭，接缝会「撞」。这个模块回答一个问题：
 * <strong>把出场曲整体升高或降低几个半音，能不能让它与进场曲更和谐？</strong>
 *
 * <p>答案是「能」时，过渡引擎按这个量做变调（不变速），接缝就顺了。
 *
 * <h3>算法轮廓</h3>
 *
 * <ol>
 *   <li>取出场曲的<strong>尾段</strong>、进场曲的<strong>首段</strong>（各 {@link #WINDOW_SECS} 秒）</li>
 *   <li>分帧，每帧算 12 维 chroma（十二平均律上的能量分布）</li>
 *   <li>每帧同时算<strong>和声清晰度</strong>与<strong>响度</strong>，按两者加权平均
 *       —— 鼓点、噪声、静音这些「没有调性」的片段权重被自然压低</li>
 *   <li>对出场侧做 −3..+3 半音的旋转，与进场侧比余弦相似度</li>
 *   <li>转调有代价（每半音扣一点分），所以只有<strong>明显更好</strong>才转</li>
 * </ol>
 *
 * <h3>洁净室说明</h3>
 *
 * <p>本实现依据公开的算法思路独立编写：只用「12 维 chroma + 有界半音旋转 +
 * 加权与阈值判定」这套方法本身。<strong>未参考任何 LGPL / AGPL 项目源码的行文、
 * 命名、结构或测试数据。</strong>诸如下列常量取自算法规格（算法描述里给出的
 * 参数），而非代码抄录。
 *
 * <h3>一个刻意的设计：算不出来就不表态</h3>
 *
 * <p>{@link #match} 返回 {@code null}。有效帧不够、或素材根本没有调性时
 * 返回 {@code null} —— <strong>宁可不给建议，也不要拿噪声编一个结论出来</strong>。
 * 调用方拿到 {@code null} 就该老老实实做等功率淡化，而不是照着一个假结论去变调。
 *
 * <h3>与上游的类型差异（按 docs/audio-port.md 记录）</h3>
 *
 * <ul>
 *   <li>上游 {@code &[f32]} 按约定一般映射成 {@link java.nio.FloatBuffer}，这里用裸
 *       {@code float[]}：整个模块是逐样本访问的热循环（Goertzel 递推要跑
 *       {@code 帧数 × 频点数 × FRAME_LEN} 次），上游的切片在这里只需要「一段连续的
 *       PCM」，用 {@code FloatBuffer} 反而要把每次子切片都包一层视图。长度约定与上游一致。</li>
 *   <li>{@code Option<Match>} / {@code Option<[f32; 12]>} → {@code null}（见上）。
 *       这是本模块的语义核心，不是省事。</li>
 *   <li>{@code rate: u32} → {@code int}。上游类型上不可能出现负采样率，这里也<strong>不</strong>
 *       额外校验负数——加校验就与上游行为分叉了；只有 {@code 0} 是上游显式判过的
 *       （{@code rate == 0 || pcm.len() < FRAME_LEN} → {@code None}）。</li>
 *   <li>上游 {@code fn tail_window} / {@code head_window} 返回<strong>借用切片</strong>（零拷贝）。
 *       Java 没有借用切片，这里返回数组：窗口不足时原样返回入参（与上游返回整段一致），
 *       需要截断时返回副本。两个函数都是纯读，副本语义等价。</li>
 *   <li>上游的 {@code private fn frame_chroma} / {@code clarity_of} 在 Rust 里对子模块
 *       {@code mod tests} 是可见的（子模块能访问父模块的私有项）。Java 的对应可见性是
 *       <strong>包私有</strong>，所以这两个方法不加 {@code private}，仅供同包的
 *       {@code HarmonicTest} 对齐上游测试使用，不作为对外 API。</li>
 *   <li>所有 {@code Math.*}（{@code pow}/{@code cos}/{@code sqrt}）的结果<strong>立即</strong>
 *       {@code (float)} 转换：Rust 那边是 {@code f32::powf} / {@code f32::cos} / {@code f32::sqrt}，
 *       让中间量走 double 会把 Goertzel 的递推与 chroma 归一化整条链拉到 double 上算，
 *       清晰度、相似度都会在阈值边界上给出与上游不同的结论（0.28 / 0.72 / 0.10 三个门槛
 *       都是硬比较）。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::harmonic}。
 */
public final class Harmonic {

    /** 音级数（十二平均律）。 */
    public static final int PITCH_CLASSES = 12;

    /**
     * 参与分析的时间窗长度（秒）。
     *
     * <p>太短则样本不足、结论不稳；太长则会把歌曲中段的转调也混进来，
     * 而我们要的是<strong>接缝处</strong>的调性。
     */
    public static final double WINDOW_SECS = 8.0;

    /**
     * 单帧长度（样本）。
     *
     * <p>上游是模块私有常量，Java 里取包私有：上游测试模块直接用到了它
     * （{@code &tone[..FRAME_LEN]}），这里要让对齐测试也能拿到。
     */
    static final int FRAME_LEN = 2048;

    /** 帧移（样本）。四分之一重叠，兼顾时间分辨率与计算量。 */
    private static final int HOP = 512;

    /** 十二平均律的基准频率（A2）。 */
    private static final float BASE_HZ = 110.0f;

    /**
     * 参与累加的八度数。
     *
     * <p>取 5 个（110 Hz 起，向上五个八度）覆盖绝大多数乐器的基频与低次谐波，
     * 又不至于把高频噪声也当成音高证据。
     */
    private static final int OCTAVES = 5;

    /**
     * 帧被采纳所需的最低和声清晰度。
     *
     * <p>纯噪声的 12 个分量接近均匀分布，清晰度约在 0.1 上下；
     * 有明确音高的段落会明显高于此值。这个阈值把前者挡在门外。
     *
     * <p>上游 {@code tuning.rs} 会读这个常量（{@code harmony_clarity_min}），所以是公开的。
     */
    public static final float CLARITY_MIN = 0.28f;

    /** 帧被采纳所需的最低响度（RMS）。太轻的段落信噪比差，不参与。 */
    private static final float LOUDNESS_MIN = 0.005f;

    /** 至少要有这么多帧被采纳，才给出结论。 */
    private static final int MIN_FRAMES = 8;

    /**
     * 每转一个半音扣的分数。
     *
     * <p>转调不是免费的（变速变调会引入处理痕迹），所以要给一点「惰性」，
     * 避免为了千分之几的收益来回折腾。
     */
    private static final float SHIFT_PENALTY_PER_SEMITONE = 0.015f;

    /**
     * 转调后至少要达到的相似度。
     *
     * <p>上游 {@code tuning.rs} 会读这个常量（{@code harmony_match_min_score}）。
     */
    public static final float MATCH_MIN_SCORE = 0.72f;

    /**
     * 转调相对不转调至少要提升多少，才值得动。
     *
     * <p>上游 {@code tuning.rs} 会读这个常量（{@code harmony_min_improvement}）。
     */
    public static final float MIN_IMPROVEMENT = 0.10f;

    /**
     * 允许搜索的半音范围（±）。
     *
     * <p>上游 {@code tuning.rs} 会读这个常量（{@code harmony_max_shift}）。
     */
    public static final int MAX_SHIFT = 3;

    /**
     * {@code core::f32::consts::PI} 的 f32 镜像。
     *
     * <p>{@link Math#PI} 是 double，直接参与运算会把相位链拉到 double 上算，
     * 与上游的 f32 结果就不逐位对照了。
     */
    private static final float PI = (float) Math.PI;

    private Harmonic() {
    }

    /**
     * 匹配结论。
     *
     * <p>字段与上游 {@code struct Match} <strong>一一对应、顺序一致</strong>：
     * {@code semitones} / {@code plain_score} / {@code best_score} / {@code should_shift}。
     * 上游是 {@code #[derive(Debug, Clone, Copy, PartialEq)]} 的不可变值类型，
     * 这里做成不可变（字段全 {@code final}），{@code Copy} 天然满足，不需要复制构造。
     */
    public static final class Match {

        /** 建议的转调量（半音）。{@code 0} 表示不转。 */
        public final int semitones;

        /** 采纳方案的相似度（−1..1，越大越像）。 */
        public final float plainScore;

        /** 不转调时的相似度，供对比与日志。 */
        public final float bestScore;

        /** 是否建议转调。 */
        public final boolean shouldShift;

        public Match(int semitones, float plainScore, float bestScore, boolean shouldShift) {
            this.semitones = semitones;
            this.plainScore = plainScore;
            this.bestScore = bestScore;
            this.shouldShift = shouldShift;
        }

        /**
         * 给界面/日志用的一行说明。
         *
         * <p>上游是 {@code format!("转调 {:+.0} 半音（相似度 {:.3} ← {:.3}）", semitones, best_score, plain_score)}
         * 与 {@code format!("不转调（相似度 {:.3}）", plain_score)}。
         *
         * <p>两处刻意的处理：
         *
         * <ul>
         *   <li>{@code {:+.0}} 作用在 {@code i32} 上就是「带符号的整数」，这里手工拼符号，
         *       不过一遍浮点格式化（{@code %+.0f} 的进位规则是 half-up，Rust 是
         *       half-to-even，只有整数语义才完全一致）。</li>
         *   <li>{@code Locale.ROOT}：默认区域会把小数点写成逗号，日志解析会崩。</li>
         * </ul>
         */
        public String describe() {
            if (shouldShift) {
                String signed = (semitones >= 0 ? "+" : "-") + Math.abs(semitones);
                return String.format(Locale.ROOT, "转调 %s 半音（相似度 %.3f ← %.3f）",
                        signed, bestScore, plainScore);
            }
            return String.format(Locale.ROOT, "不转调（相似度 %.3f）", plainScore);
        }

        /**
         * 镜像上游 {@code #[derive(Debug)]} 的输出（字段名保持上游的下划线写法，
         * 方便与上游日志对照）。
         *
         * <p>注意 <strong>{@code describe()} 才是给人看的那一行</strong>：
         * 上游 {@code Match} 并没有 {@code impl fmt::Display}，这里也不让
         * {@code toString()} 去冒充它，免得两处日志格式互相污染。
         */
        @Override
        public String toString() {
            return "Match { semitones: " + semitones
                    + ", plain_score: " + plainScore
                    + ", best_score: " + bestScore
                    + ", should_shift: " + shouldShift + " }";
        }

        /**
         * 对应上游 derive 的 {@code PartialEq}（四个字段全比）。
         *
         * <p>上游对 {@code f32} 用 {@code ==}（于是 {@code NaN != NaN}），这里用
         * {@link Float#compare}：保证 {@code equals} 的自反性。相似度只在有限值上产生
         * （含 NaN 的 chroma 会在清晰度那关就被丢掉），两种写法在正常路径上完全一致。
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Match)) {
                return false;
            }
            Match m = (Match) o;
            return semitones == m.semitones
                    && Float.compare(plainScore, m.plainScore) == 0
                    && Float.compare(bestScore, m.bestScore) == 0
                    && shouldShift == m.shouldShift;
        }

        @Override
        public int hashCode() {
            int h = semitones;
            h = 31 * h + Float.floatToIntBits(plainScore);
            h = 31 * h + Float.floatToIntBits(bestScore);
            h = 31 * h + (shouldShift ? 1 : 0);
            return h;
        }
    }

    /**
     * 取尾部的分析窗。
     *
     * <p>上游返回 {@code &pcm[pcm.len() - want..]}；长度不足时返回整段。Java 没有切片，
     * 这里长度不足时原样返回入参（与上游「返回整段」语义一致），需要截断时给副本。
     */
    public static float[] tailWindow(float[] pcm, int rate) {
        int want = (int) ((double) rate * WINDOW_SECS);
        if (pcm.length <= want) {
            return pcm;
        }
        return Arrays.copyOfRange(pcm, pcm.length - want, pcm.length);
    }

    /** 取头部的分析窗。对应上游 {@code &pcm[..pcm.len().min(want)]}。 */
    public static float[] headWindow(float[] pcm, int rate) {
        int want = (int) ((double) rate * WINDOW_SECS);
        return Arrays.copyOfRange(pcm, 0, Math.min(pcm.length, want));
    }

    /**
     * 给出场曲的尾段与进场曲的首段做匹配。
     *
     * <p>传进来的应当是<strong>整段音频</strong>，窗口由本函数自己截——这样调用方不必
     * 关心窗口长度这个内部约定。
     *
     * <h3>两侧各要一个采样率，不能共用一个</h3>
     *
     * <p>采样率在这里有两个用途：截 8 秒的分析窗、以及 Goertzel 的频率坐标
     * （{@code k = n·f/rate}）。<strong>共用一个是错的</strong>——一个 44.1 kHz 的 MP3 接一个
     * 48 kHz 的 FLAC 是队列里最平常不过的事，拿 44.1k 去分析 48k 那段，
     * 每个音级都会整体偏移 8.8%，转调建议随之全错：要么该转的说不用转，
     * 要么转一个完全不相干的半音数。而这里恰恰是整个「声波重合」的立论基础。
     *
     * @return 匹配结论；素材没有可用调性信息时返回 {@code null}（对应上游的 {@code None}），
     *         调用方应当退回等功率淡化
     */
    public static Match match(float[] outro, int outroRate, float[] intro, int introRate) {
        float[] a = weightedChroma(tailWindow(outro, outroRate), outroRate);
        if (a == null) {
            return null;
        }
        float[] b = weightedChroma(headWindow(intro, introRate), introRate);
        if (b == null) {
            return null;
        }

        float plain = cosine(a, b);

        // 搜索最优旋转。比较时用「扣过分」的值，返回时用原始相似度——
        // 两笔账要分开，否则界面上的数字会莫名其妙地小于阈值。
        int bestShift = 0;
        float bestRaw = plain;
        float bestPenalized = plain;

        for (int shift = -MAX_SHIFT; shift <= MAX_SHIFT; shift++) {
            if (shift == 0) {
                continue;
            }
            float raw = cosine(rotate(a, shift), b);
            // 上游：raw - SHIFT_PENALTY_PER_SEMITONE * shift.abs() as f32（`as` 比 `*` 紧）
            float penalized = raw - SHIFT_PENALTY_PER_SEMITONE * (float) Math.abs(shift);
            if (penalized > bestPenalized) {
                bestPenalized = penalized;
                bestRaw = raw;
                bestShift = shift;
            }
        }

        // 两条都满足才转调：
        //   1. 绝对值够高 —— 低于阈值说明根本不像，转也白转
        //   2. 比不转明显更好 —— 提升太小就不值得动
        boolean shouldShift = bestShift != 0
                && bestRaw >= MATCH_MIN_SCORE
                && (bestRaw - plain) >= MIN_IMPROVEMENT;

        if (shouldShift) {
            return new Match(bestShift, plain, bestRaw, true);
        }
        return new Match(0, plain, plain, false);
    }

    /**
     * 对一段音频做加权平均，得到 12 维 chroma 向量（已归一化）。
     *
     * <p>返回 {@code null} 表示「这段素材没有可用的调性信息」，而不是「出错了」。
     */
    public static float[] weightedChroma(float[] pcm, int rate) {
        PrepareStage.mark("判调性（逐帧 chroma）");
        if (rate == 0 || pcm.length < FRAME_LEN) {
            return null;
        }

        float[] acc = new float[PITCH_CLASSES];
        float weightSum = 0.0f;
        int used = 0;

        // ---- 逐帧的 chroma/clarity/rms：**可以并行**（2026-09-30 压缩预处理时间）----
        //
        // 每一帧只读 pcm 的一段、写自己那份结果，帧与帧之间没有依赖——所以这里按帧切块
        // 分给几个线程算，各自用自己那块帧缓冲（原来那个共享缓冲会变成数据竞争）。
        //
        // **累加仍然严格按帧序串行做**（见下面那个循环）：浮点加法不满足结合律，
        // 跨帧并行累加会改变结果，而这一层的结果要用来判调性、挑移调量——
        // 「快」不能拿结论去换。所以并行只覆盖「算」，不覆盖「加」：
        // 数值路径与逐帧串行实现逐位相同。
        int frames = 1 + (pcm.length - FRAME_LEN) / HOP;
        float[][] chromaOfFrame = new float[frames][];
        float[] clarityOfFrame = new float[frames];
        float[] loudnessOfFrame = new float[frames];
        computeFrames(pcm, rate, frames, chromaOfFrame, clarityOfFrame, loudnessOfFrame);

        for (int f = 0; f < frames; f++) {
            float[] chroma = chromaOfFrame[f];
            if (chroma == null) {
                continue;
            }
            float clarity = clarityOfFrame[f];
            float loudness = loudnessOfFrame[f];

            // 两道过滤：没有调性的（清晰度低）、太轻的（信噪比差）
            if (clarity >= CLARITY_MIN && loudness > LOUDNESS_MIN) {
                float w = clarity * loudness;
                for (int i = 0; i < PITCH_CLASSES; i++) {
                    acc[i] += chroma[i] * w;
                }
                weightSum += w;
                used++;
            }
        }

        if (used < MIN_FRAMES || weightSum <= 0.0f) {
            return null;
        }

        normalize(acc);
        return acc;
    }

    /**
     * 把 chroma 旋转 {@code semitones} 个半音。
     *
     * <p>方向约定：{@code +1} 表示把某段素材<strong>升高</strong>一个半音，即每个音级的能量
     * 在音级轴上向前移一格。保持这个约定一致即可——重要的是两侧用同一套映射。
     *
     * <p>上游用 {@code rem_euclid}（结果恒非负），Java 的等价物是 {@link Math#floorMod}。
     *
     * @param chroma 长度必须是 {@link #PITCH_CLASSES}（上游类型是定长数组）
     */
    public static float[] rotate(float[] chroma, int semitones) {
        float[] out = new float[PITCH_CLASSES];
        for (int i = 0; i < PITCH_CLASSES; i++) {
            int j = Math.floorMod(i + semitones, PITCH_CLASSES);
            out[j] = chroma[i];
        }
        return out;
    }

    /**
     * 余弦相似度。两个向量都已归一化时它退化成点积，但这里不假设归一化。
     *
     * <p>三项（点积、两个模）的累加顺序与上游的迭代器一致，全程 f32；
     * 只有开方走 {@link Math#sqrt}（double，正确舍入）后立即转回 float。
     */
    private static float cosine(float[] a, float[] b) {
        float dot = 0.0f;
        for (int i = 0; i < PITCH_CLASSES; i++) {
            dot += a[i] * b[i];
        }
        float na = 0.0f;
        for (int i = 0; i < PITCH_CLASSES; i++) {
            na += a[i] * a[i];
        }
        na = (float) Math.sqrt((double) na);
        float nb = 0.0f;
        for (int i = 0; i < PITCH_CLASSES; i++) {
            nb += b[i] * b[i];
        }
        nb = (float) Math.sqrt((double) nb);
        if (na <= 0.0f || nb <= 0.0f) {
            return 0.0f;
        }
        return clamp(dot / (na * nb), -1.0f, 1.0f);
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

    private static void normalize(float[] v) {
        float n = 0.0f;
        for (int i = 0; i < PITCH_CLASSES; i++) {
            n += v[i] * v[i];
        }
        n = (float) Math.sqrt((double) n);
        if (n > 0.0f) {
            for (int i = 0; i < PITCH_CLASSES; i++) {
                v[i] /= n;
            }
        }
    }

    /**
     * 一帧的 12 维 chroma。
     *
     * <p>用 Goertzel 而不是通用 FFT：我们只需要特定音级上的能量，
     * 没必要为此引一个 FFT 库、也没必要算出一整条频谱再丢掉大半。
     * 96 个频点的计算量远小于一次 2048 点 FFT。
     *
     * <p>包私有而非 {@code private}：上游的私有 fn 对子模块 {@code mod tests} 可见，
     * Java 的对应可见性就是包私有（只给同包的 {@code HarmonicTest} 用）。
     */
    static float[] frameChroma(float[] frame, int rate) {
        ChromaPlan plan = planFor(rate, frame.length);
        float[] powers = new float[plan.distinct];
        return frameChromaAt(frame, 0, plan, powers);
    }

    /**
     * 频点参数表：{@code (八度, 音级) → 频点} 这一层<b>只取决于采样率与帧长</b>。
     *
     * <p>为什么要它（2026-10-03，性能）：原来的写法在<b>每一帧</b>里重算 60 次
     * {@code Math.pow} + {@code Math.cos} + {@code Math.floor}，而这些量与帧无关。
     * 更要紧的是：Goertzel 会把频点四舍五入到最近的整数 bin（{@code k}），
     * 低八度里 12 个半音常常落在<b>同一个 k</b> 上（实测 48 kHz 下 60 个频点只有 50 个
     * 不同 k，最多一个 k 被 3 个音级共用）——同一个 k 的递推<b>逐位相同</b>，
     * 所以算一次、把同一个 float 值加到那几个音级上，结果一个位都不变。
     *
     * <p>{@code coeff} 与 {@link #goertzel} 里那两行逐字相同（同一个 {@code k}、同一个
     * {@code n}）：Goertzel 递推里 coeff 差 1 ulp 会被 2048 次迭代放大。
     */
    static final class ChromaPlan {
        /** 帧长（{@code goertzel} 里的 {@code n}）。 */
        final int frameLen;
        /** 每个条目（按「八度升序、音级升序」的原始顺序）加到哪个音级。 */
        final int[] pitchClass;
        /** 每个条目用到哪个去重槽。 */
        final int[] slot;
        /** 去重后的系数表：{@code coeff[slot] = 2·cos(2πk/n)}（f32）。 */
        final float[] coeff;
        /** 不同 {@code k} 的个数。 */
        final int distinct;

        ChromaPlan(int frameLen, int[] pitchClass, int[] slot, float[] coeff, int distinct) {
            this.frameLen = frameLen;
            this.pitchClass = pitchClass;
            this.slot = slot;
            this.coeff = coeff;
            this.distinct = distinct;
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<Long, ChromaPlan> PLANS =
            new java.util.concurrent.ConcurrentHashMap<Long, ChromaPlan>();

    /** {@code (采样率, 帧长) → 参数表}；同一对参数永远得到同一张表，且表是不可变的。 */
    static ChromaPlan planFor(int rate, int frameLen) {
        long key = ((long) rate << 32) | (frameLen & 0xffffffffL);
        ChromaPlan cached = PLANS.get(Long.valueOf(key));
        if (cached != null) {
            return cached;
        }
        int cap = PITCH_CLASSES * OCTAVES;
        int[] pcOf = new int[cap];
        int[] slotOf = new int[cap];
        int[] kOfSlot = new int[cap];
        int[] slotTmp = new int[cap];
        int[] pcTmp = new int[cap];
        int[] kTmp = new int[cap];
        int n = 0;
        int distinct = 0;
        float nf = (float) frameLen;
        float ceiling = (float) rate * 0.45f; // 留出余量，避开奈奎斯特附近的混叠
        float base = BASE_HZ;
        for (int oct = 0; oct < OCTAVES; oct++) {
            if (base >= ceiling) {
                break;
            }
            for (int pc = 0; pc < PITCH_CLASSES; pc++) {
                // 上游：(2.0f32).powf(pc as f32 / 12.0)——先算 f32 的指数再取幂
                float f = base * (float) Math.pow(2.0, (double) ((float) pc / 12.0f));
                if (f >= ceiling) {
                    continue;
                }
                // 与 goertzel 里那两行逐字相同
                int ki = (int) ((float) Math.floor(0.5f + nf * f / (float) rate));
                int slot = -1;
                for (int i = 0; i < distinct; i++) {
                    if (kOfSlot[i] == ki) {
                        slot = i;
                        break;
                    }
                }
                if (slot < 0) {
                    slot = distinct;
                    kOfSlot[distinct] = ki;
                    distinct++;
                }
                pcOf[n] = pc;
                slotOf[n] = slot;
                slotTmp[n] = slot;
                pcTmp[n] = pc;
                kTmp[n] = ki;
                n++;
            }
            base *= 2.0f;
        }
        float[] coeff = new float[distinct];
        for (int i = 0; i < distinct; i++) {
            float w = 2.0f * PI * (float) kOfSlot[i] / nf;
            coeff[i] = 2.0f * (float) Math.cos((double) w);
        }
        ChromaPlan plan = new ChromaPlan(frameLen, java.util.Arrays.copyOf(pcOf, n),
                java.util.Arrays.copyOf(slotOf, n), coeff, distinct);
        // 兜底上限（2026-10-03，评审指出）：键只有「采样率 × 帧长」的组合，正常就是
        // 44.1k/48k × 2048 这么几条；但将来帧长要是跟着采样率变（例如每档都不同），
        // 这张表会随着**见过的档位**一直长。清空最坏只是重算一次表（几微秒），
        // 换来的是「这张表不会无限长」这条性质。
        if (PLANS.size() > 64) {
            PLANS.clear();
        }
        PLANS.put(Long.valueOf(key), plan);
        return plan;
    }

    /**
     * 一帧的 12 维 chroma：按参数表逐个<b>去重后的频点</b>跑 Goertzel，
     * 再按原始顺序把功率加到音级上。
     *
     * <p>{@code powers} 是调用方的临时数组（长度 = {@code plan.distinct}）——
     * 由调用方按线程各持有，避免每帧分配。
     */
    /**
     * <b>Goertzel 递推的交错推进</b>：把 4 个频点放进同一趟采样循环里。
     *
     * <p>为什么要这样写（2026-10-03，第二次优化）：Goertzel 的递推
     * {@code s0 = x + coeff*s1 - s2} 是<b>一条串行依赖链</b>——每个采样都要等上一拍的
     * {@code s1}，所以单跑一个频点时 CPU 大部分时间在等浮点延迟，吞吐上不去。
     * 频点之间<b>互不依赖</b>，于是把 4 条链交错在同一次循环里，四条链各自还是原来的
     * 顺序、原来的表达式，**逐位相同**，但流水线被填满了；顺带每采样只读一次 {@code x}
     * （原来 50 个频点各读一遍 2048 个采样）。
     *
     * <p>链的条数取 4：再多收益递减（寄存器压力、指令缓存），而且余数那几条要单独收尾。
     */
    private static void goertzelBatch(float[] pcm, int offset, int len, float[] coeffs,
                                      int from, int count, float[] powers) {
        if (count <= 0 || len <= 0) {
            return;
        }
        // 四条链一组
        int s = from;
        int end = from + count;
        for (; s + 4 <= end; s += 4) {
            float c0 = coeffs[s];
            float c1 = coeffs[s + 1];
            float c2 = coeffs[s + 2];
            float c3 = coeffs[s + 3];
            float a1 = 0.0f;
            float a2 = 0.0f;
            float b1 = 0.0f;
            float b2 = 0.0f;
            float d1 = 0.0f;
            float d2 = 0.0f;
            float e1 = 0.0f;
            float e2 = 0.0f;
            for (int i = 0; i < len; i++) {
                float x = pcm[offset + i];
                float a0 = x + c0 * a1 - a2;
                a2 = a1;
                a1 = a0;
                float b0 = x + c1 * b1 - b2;
                b2 = b1;
                b1 = b0;
                float d0 = x + c2 * d1 - d2;
                d2 = d1;
                d1 = d0;
                float e0 = x + c3 * e1 - e2;
                e2 = e1;
                e1 = e0;
            }
            powers[s] = goertzelPower(a1, a2, c0);
            powers[s + 1] = goertzelPower(b1, b2, c1);
            powers[s + 2] = goertzelPower(d1, d2, c2);
            powers[s + 3] = goertzelPower(e1, e2, c3);
        }
        for (; s < end; s++) {
            powers[s] = goertzelCoeff(pcm, offset, len, coeffs[s]);
        }
    }

    /** 递推收尾那一步的功率（与 {@link #goertzelCoeffRaw} 里逐字相同）。 */
    private static float goertzelPower(float s1, float s2, float coeff) {
        float power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        return (power > 0.0f) ? power : 0.0f;
    }

    private static float[] frameChromaAt(float[] pcm, int offset, ChromaPlan plan,
                                         float[] powers) {
        goertzelBatch(pcm, offset, plan.frameLen, plan.coeff, 0, plan.distinct, powers);
        float[] chroma = new float[PITCH_CLASSES];
        for (int e = 0; e < plan.pitchClass.length; e++) {
            // 加法的顺序与原来逐字相同（八度升序、音级升序）；同一个 k 的功率是同一个
            // float 值，所以既省了递推、又一位不差。
            chroma[plan.pitchClass[e]] += powers[plan.slot[e]];
        }
        return chroma;
    }

    private static float goertzel(float[] samples, int rate, float freq) {
        float n = (float) samples.length;
        if (n <= 0.0f) {
            return 0.0f;
        }
        // 取最近的整数频点，这是 Goertzel 的标准做法
        float k = (float) Math.floor(0.5f + n * freq / (float) rate);
        float w = 2.0f * PI * k / n;
        float coeff = 2.0f * (float) Math.cos((double) w);
        return goertzelCoeffRaw(samples, 0, samples.length, coeff);
    }

    /**
     * 递推本体：{@code coeff} 已知时的那 2048 次迭代（{@link #goertzel} 与参数表路径共用）。
     *
     * <p>拆出来的原因只有一个：{@code coeff} 只取决于 {@code (k, n)}，而参数表已经把
     * 「哪些音级共用一个 k」算清了——这里必须与原来那条路径<b>逐字相同</b>，
     * 否则同一次分析会因为走了哪条路而分叉。
     */
    private static float goertzelCoeff(float[] pcm, int offset, int len, float coeff) {
        return goertzelCoeffRaw(pcm, offset, len, coeff);
    }

    private static float goertzelCoeffRaw(float[] samples, int offset, int len, float coeff) {
        if (len <= 0) {
            return 0.0f;
        }
        float s1 = 0.0f;
        float s2 = 0.0f;
        for (int i = 0; i < len; i++) {
            float x = samples[offset + i];
            float s0 = x + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        // 功率。上游是 `.max(0.0)`，而 Rust 的 f32::max 遇到 NaN 返回另一个操作数
        // （Java 的 Math.max 返回 NaN），所以这里写成比较：NaN > 0 为假 → 0.0，
        // 与上游逐位一致（素材里真有 NaN 时也不会两边分叉）。
        float power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        return (power > 0.0f) ? power : 0.0f;
    }

    /**
     * 逐帧算 chroma/clarity/rms，并按需分给几个线程（结果与串行逐帧算逐位相同）。
     *
     * <p>为什么要并行：{@link #frameChroma} 是整条调性分析里唯一的重活——每帧
     * 12 个音级 × 最多 5 个八度的 Goertzel（每个频点都要把 2048 个采样过一遍），
     * 8 秒素材上是 90M 次乘加，本机实测约 0.5 秒，接近一次 automix 预处理里最贵的一项。
     * 它与「累加」是两件事，而只有累加对顺序敏感（见 {@link #weightedChroma} 的注释）。
     *
     * <p>线程数按核数取（最多 {@value #CHROMA_THREADS_MAX} 个），块按帧号连续切分——
     * 切法只取决于帧数，与线程调度无关，所以任何一次运行的结果都一样。
     */
    private static void computeFrames(float[] pcm, int rate, int frames,
                                      float[][] chromaOut, float[] clarityOut,
                                      float[] loudnessOut) {
        ChromaPlan plan = planFor(rate, FRAME_LEN);
        int cores = Runtime.getRuntime().availableProcessors();
        int threads = Math.max(1, Math.min(CHROMA_THREADS_MAX, cores - 1));
        if (threads <= 1 || frames < CHROMA_MIN_FRAMES_TO_SPLIT) {
            float[] powers = new float[plan.distinct];
            float[] frame = new float[FRAME_LEN];
            for (int f = 0; f < frames; f++) {
                computeFrame(pcm, f, plan, powers, frame, chromaOut, clarityOut, loudnessOut);
            }
            return;
        }
        // **线程池复用**（2026-10-03）：原来每次分析都新建 8 个线程，一次过渡要建两轮
        // （出场侧 + 进场侧），光起停就是几毫秒；池里的线程是 daemon，闲着不占 CPU。
        int chunk = (frames + threads - 1) / threads;
        java.util.List<java.util.concurrent.Future<?>> tasks =
                new java.util.ArrayList<java.util.concurrent.Future<?>>(threads);
        for (int t = 0; t < threads; t++) {
            final int from = t * chunk;
            final int to = Math.min(frames, from + chunk);
            if (from >= to) {
                break;
            }
            tasks.add(CHROMA_POOL.submit(new Runnable() {
                @Override
                public void run() {
                    // 每个任务自己的临时数组：帧与帧之间没有共享写入
                    float[] powers = new float[plan.distinct];
                    float[] frame = new float[FRAME_LEN];
                    for (int f = from; f < to; f++) {
                        computeFrame(pcm, f, plan, powers, frame, chromaOut, clarityOut,
                                loudnessOut);
                    }
                }
            }));
        }
        for (java.util.concurrent.Future<?> task : tasks) {
            try {
                task.get();
            } catch (InterruptedException e) {
                // **不许把半成品当成品**（2026-10-04 审计发现）：原来只恢复标志就继续往下走，
                // 于是没跑到的分块在 chroma/clarity/loudness 里全是 0，而调用方拿到的是一个
                // "看起来算完了"的结论（相似度、最优移调全从半张图里算出来）。全都没跑时
                // 侥幸会退化成 null，但中断只要落在中途，剩下几张就是"有值但错"的——
                // 那种情况最坏。任务本身抛异常时会往上冒（下面那一支），两条路待遇必须一致。
                Thread.currentThread().interrupt();
                throw new IllegalStateException("调性分析被中断，拒绝交出半张 chroma");
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        }
    }

    /** 并行算帧的上限（再多也是在抢同一批核）。 */
    private static final int CHROMA_THREADS_MAX = 8;

    /** 帧太少就别开线程了（起线程比算它还贵）。 */
    private static final int CHROMA_MIN_FRAMES_TO_SPLIT = 16;

    /**
     * 逐帧的线程池：只服务 {@link #computeFrames}，线程是 daemon、数量固定。
     *
     * <p>与「每次新建线程」相比，唯一的变化是线程被复用；切块方式（按帧号连续切、
     * 只取决于帧数）与结果都没变——每一帧仍然是<b>同一个纯函数</b>。
     */
    private static final java.util.concurrent.ExecutorService CHROMA_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(
                    Math.max(1, Math.min(CHROMA_THREADS_MAX,
                            Runtime.getRuntime().availableProcessors() - 1)),
                    new java.util.concurrent.ThreadFactory() {
                        private final java.util.concurrent.atomic.AtomicInteger n =
                                new java.util.concurrent.atomic.AtomicInteger();
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread th = new Thread(r, "qplayer-chroma-" + n.getAndIncrement());
                            th.setDaemon(true);
                            return th;
                        }
                    });

    /**
     * 第 {@code f} 帧的三样读数（与原来的串行循环逐行等价）。
     *
     * <p>{@code frame} 是调用方持有的整帧缓冲：原来每帧都新建一个 2048 长的数组再
     * {@code arraycopy}，现在把窗口直接盖到这块缓冲上——窗口内参与计算的采样值与
     * 逐字相同，只是不再为每一帧分配 8 KB。
     */
    private static void computeFrame(float[] pcm, int f, ChromaPlan plan, float[] powers,
                                     float[] frame, float[][] chromaOut, float[] clarityOut,
                                     float[] loudnessOut) {
        int off = f * HOP;
        System.arraycopy(pcm, off, frame, 0, FRAME_LEN);
        float[] chroma = frameChromaAt(frame, 0, plan, powers);
        chromaOut[f] = chroma;
        clarityOut[f] = clarityOf(chroma);
        loudnessOut[f] = rms(frame);
    }


    /**
     * 和声清晰度：最大分量占总能量的比例。
     *
     * <p>均匀分布（噪声）约 1/12 ≈ 0.083；有明确调性时显著更高。
     *
     * <p>包私有而非 {@code private}：理由同 {@link #frameChroma}。
     */
    static float clarityOf(float[] chroma) {
        float sum = 0.0f;
        for (int i = 0; i < PITCH_CLASSES; i++) {
            sum += chroma[i];
        }
        // 上游是 `if !(sum > 0.0)`：NaN 也走这一支（NaN 的取反比较为真）
        if (!(sum > 0.0f)) {
            return 0.0f;
        }
        // 上游 fold(f32::MIN, f32::max)：起始值是 f32 的最小值（≈ -3.4e38），
        // 不是 Java 的 Float.MIN_VALUE（那是「最小正数」，用它当代数下界是经典坑）。
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < PITCH_CLASSES; i++) {
            if (chroma[i] > max) {
                max = chroma[i];
            }
        }
        return clamp(max / sum, 0.0f, 1.0f);
    }

    static float rms(float[] frame) {
        if (frame.length == 0) {
            return 0.0f;
        }
        float sum = 0.0f;
        for (int i = 0; i < frame.length; i++) {
            float x = frame[i];
            sum += x * x;
        }
        return (float) Math.sqrt((double) (sum / (float) frame.length));
    }
}
