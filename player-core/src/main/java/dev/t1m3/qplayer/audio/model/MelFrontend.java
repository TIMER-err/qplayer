package dev.t1m3.qplayer.audio.model;

import dev.t1m3.qplayer.audio.dsp.Fft;

/**
 * log-mel 前端（上游 {@code sonalis-model/src/beat.rs} 的 {@code MelFrontend}）。
 *
 * <p>构造一次，反复用。
 *
 * <h2>为什么用稀疏三角滤波器</h2>
 *
 * <p>每条带不再是一整行 128 × 上千个 bin 的稠密权重：稠密矩阵里九成九是零，
 * 乘一遍纯属浪费。所以过滤器建成「每条带一组 {@code (bin, 权重)}」的稀疏表
 * （上游就是这么做的，这里按两条平行数组表达 {@code Vec<Vec<(usize, f32)>>}）。
 *
 * <h2>帧数与「窗口右端对齐样本末尾」这条约定</h2>
 *
 * <p>{@code frames = (len − n_fft) / hop + 1}：窗口从样本起点开始、每次移动一个
 * hop，最后一帧的<b>右端</b>正好落在样本末尾。帧数不足一帧时返回空——
 * <b>不补零硬凑</b>：凑出来的一帧会被下游当成真实的音频看待。
 *
 * <h2>借用/复用（上游的 {@code RefCell} 在 Java 里不需要）</h2>
 *
 * <p>上游把这个前端放进 {@code RefCell}，因为它要在 {@code &self} 的推理会话旁边
 * 提供 {@code &mut} 的 {@code compute}。Java 没有借用检查，本类自己的暂存缓冲
 * （{@code re}/{@code im}/{@code mag}）是私有字段，直接写即可。
 * 但上游那条<b>性质</b>要保住并且有测试钉着（{@code aReusedFrontendMatchesAFreshOne}）：
 * <b>复用同一个前端</b>与<b>每次新建一个前端</b>算出来的特征必须逐位相同——
 * 复用多出来的变数只有「暂存缓冲区里留着上一条输入的残渣」，所以测试会中间
 * 故意夹一段别的音频。
 *
 * <h2>与上游的类型差异（按 docs/audio-port.md 记录）</h2>
 *
 * <ul>
 *   <li>{@code compute} 返回 {@code (Vec<f32>, usize)}：Java 没有元组，按约定用
 *       不可变值类 {@link Features} 代替（字段与元组位置一一对应）。</li>
 *   <li>{@code &[f32]} → 裸 {@code float[]}：这是「一段连续的单声道 PCM」，
 *       不是从 {@code AudioBuf} 取出来的声道切片，且访问模式是逐样本热循环
 *       （与 {@code Analysis} 同一处理）。</li>
 *   <li>{@code hz_to_mel} / {@code mel_to_hz} 在上游是模块私有 fn，但 {@code mod tests}
 *       是子模块、看得见父模块的私有项。Java 的对应可见性是<b>包私有</b>，
 *       所以这两个不加 {@code private}——只给同包的测试用，不作为对外 API
 *       （与 {@code Analysis.tempoPrior} 的处理相同）。</li>
 *   <li>所有 {@code Math.*} 的结果<b>立即</b> {@code (float)} 转换：Rust 那边是
 *       {@code f32::log10} / {@code f32::powf} / {@code f32::ln}。走 double 再转会让
 *       梅尔带边界与特征值出现可见偏差，而特征正是模型的输入。</li>
 *   <li>{@code f32::ln} → {@code (float) Math.log(double)}：见
 *       {@link #compute(float[])} 里那条注释。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_model::beat::MelFrontend}。
 */
public final class MelFrontend {

    /** 前处理参数（构造时<b>复制</b>一份，上游是按值传参）。 */
    private final MelParams params;

    private final Fft fft;

    /** 周期性 Hann 窗，长度 {@code n_fft}。 */
    private final float[] window;

    /**
     * 稀疏三角滤波器：每条带一组 {@code (bin, 权重)}。
     *
     * <p>两条平行数组下标一一对应，长度都等于梅尔带数。用两条平行数组而不是
     * 一个 {@code (int, float)} 记录类：这是离线分析的热循环，避免每个抽头
     * 多一层对象解引用，语义与上游的 {@code Vec<(usize, f32)>} 完全一致。
     */
    private final int[][] filterBins;

    private final float[][] filterWeights;

    private final float[] re;

    private final float[] im;

    private final float[] mag;

    /**
     * 构造。
     *
     * <p>参数不自洽时抛 {@link IllegalArgumentException}（上游返回
     * {@code Err(String)}，调用方把它归到 {@code InferError::Shape}）。
     *
     * @param params 前处理参数，本构造器会复制一份
     */
    public MelFrontend(MelParams params) {
        params.validate();
        this.params = new MelParams(params);

        int n = params.nFft;
        int bins = n / 2 + 1;

        float[] win = new float[n];
        Fft.hann(win);

        // 梅尔等间距取 mels + 2 个点，相邻三点构成一个三角形
        float melLo = hzToMel(params.fmin);
        float melHi = hzToMel(params.fmax);
        float[] points = new float[params.mels + 2];
        for (int i = 0; i < points.length; i++) {
            float m = melLo + (melHi - melLo) * (float) i / (float) (params.mels + 1);
            points[i] = melToHz(m);
        }

        float binHz = (float) params.sampleRate / (float) n;
        int[][] binsOut = new int[params.mels][];
        float[][] weightsOut = new float[params.mels][];
        for (int b = 0; b < params.mels; b++) {
            float lo = points[b];
            float mid = points[b + 1];
            float hi = points[b + 2];
            // 上游是逐条 push 的 Vec；Java 的数组长度固定，所以先按上限开、
            // 再截到实际个数。判据（w > 1e-6）与遍历顺序都与上游一致，
            // 结果逐元素相同。
            int[] kb = new int[bins];
            float[] kw = new float[bins];
            int count = 0;
            for (int k = 0; k < bins; k++) {
                float w = triWeight((float) k * binHz, lo, mid, hi);
                if (w > 1e-6f) {
                    kb[count] = k;
                    kw[count] = w;
                    count++;
                }
            }
            binsOut[b] = java.util.Arrays.copyOf(kb, count);
            weightsOut[b] = java.util.Arrays.copyOf(kw, count);
        }

        this.fft = new Fft(n);
        this.re = new float[n];
        this.im = new float[n];
        this.mag = new float[bins];
        this.window = win;
        this.filterBins = binsOut;
        this.filterWeights = weightsOut;
    }

    /**
     * 三角形权重。
     *
     * <p>逐个分支照抄上游的 {@code if f >= lo && f <= mid { .. } else if f > mid && f <= hi
     * { .. } else { 0.0 }}——包括「{@code mid == lo} 时给 0 而不是除零」这两处兜底：
     * 梅尔点在极端参数下可能重合，那时直接除会得到 {@code NaN}，
     * 而 {@code NaN} 会顺着特征一路传进模型（不报错，只是拍点全错）。
     */
    private static float triWeight(float f, float lo, float mid, float hi) {
        if (f >= lo && f <= mid) {
            return mid > lo ? (f - lo) / (mid - lo) : 0.0f;
        }
        if (f > mid && f <= hi) {
            return hi > mid ? (hi - f) / (hi - mid) : 0.0f;
        }
        return 0.0f;
    }

    /** 把一个频率转成梅尔刻度。包私有而非 {@code private}：上游的私有 fn 对子模块测试可见。 */
    static float hzToMel(float hz) {
        return 2595.0f * (float) Math.log10((double) (1.0f + hz / 700.0f));
    }

    /** 梅尔刻度转回频率。包私有，理由同 {@link #hzToMel}。 */
    static float melToHz(float mel) {
        return 700.0f * ((float) Math.pow(10.0, (double) (mel / 2595.0f)) - 1.0f);
    }

    /** 参数（上游 {@code params()} 借出 {@code &MelParams}；这里给出内部那一份的引用）。 */
    public MelParams params() {
        return params;
    }

    /**
     * {@code compute} 的返回值：行优先的 {@code 帧数 × mels} 特征 + 帧数。
     *
     * <p>对应上游的元组 {@code (Vec<f32>, usize)}。
     */
    public static final class Features {

        /** 行优先特征，长度 = {@link #frames} × {@code mels}。 */
        public final float[] values;

        /** 帧数。 */
        public final int frames;

        Features(float[] values, int frames) {
            this.values = values;
            this.frames = frames;
        }

        /** 只打印形状，不打印几万个特征值（与 {@code Analysis.Onsets} 同一处理）。 */
        @Override
        public String toString() {
            return "Features { values: [" + values.length + " 个数], frames: " + frames + " }";
        }
    }

    /**
     * 处理一段单声道 PCM，返回 {@code 帧数 × mels} 的行优先特征。
     *
     * <p>帧数不足一帧时返回空——<b>不补零硬凑</b>：凑出来的一帧会被下游
     * 当成真实的音频看待。
     */
    public Features compute(float[] pcm) {
        int n = params.nFft;
        int hop = params.hop;
        if (pcm.length < n) {
            return new Features(new float[0], 0);
        }
        // 帧数：窗口右端对齐样本末尾，所以是 (len - n)/hop + 1
        int frames = (pcm.length - n) / hop + 1;
        int mels = params.mels;
        float[] out = new float[frames * mels];

        for (int f = 0; f < frames; f++) {
            int start = f * hop;
            for (int i = 0; i < n; i++) {
                re[i] = pcm[start + i] * window[i];
                im[i] = 0.0f;
            }
            fft.forward(re, im);
            fft.magnitude(re, im, mag);

            int base = f * mels;
            for (int m = 0; m < mels; m++) {
                int[] kb = filterBins[m];
                float[] kw = filterWeights[m];
                float acc = 0.0f;
                for (int t = 0; t < kb.length; t++) {
                    acc += mag[kb[t]] * kw[t];
                }
                // 上游是 `(1.0 + log_k * acc).ln()`，即 f32::ln。Java 只有 Math.log(double)：
                // 先按 f32 算 1 + k*acc（与上游同一个 f32 中间量），再宽化到 double 取 ln，
                // 最后转回 f32。差异只可能来自 ln 本身的实现（f32::ln 是 libm 的 logf，
                // 与 Math.log 在 1 ulp 量级），而特征后面要经过模型，这点差异不改变结论。
                out[base + m] = params.logK > 0.0f
                        ? (float) Math.log((double) (1.0f + params.logK * acc))
                        : acc;
            }
        }

        return new Features(out, frames);
    }

    /** 帧索引换算成秒（取窗口中心）。 */
    public float frameToSecs(int frame) {
        return (float) (frame * params.hop + params.nFft / 2) / (float) params.sampleRate;
    }

    /** 只打印形状，不打印两张滤波器表（与 {@code Fft} 同一处理）。 */
    @Override
    public String toString() {
        return "MelFrontend { params: " + params + " }";
    }
}
