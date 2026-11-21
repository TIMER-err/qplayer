package dev.t1m3.qplayer.audio.model;

/**
 * log-mel 前处理参数（上游 {@code sonalis-model/src/beat.rs} 的 {@code MelParams}）。
 *
 * <h2>⚠️ 前处理参数必须对着上游核对</h2>
 *
 * <p>下面 {@link #defaults()} 的默认值是一套<b>通用</b>的 log-mel 配置
 * （22.05 kHz / n_fft 2048 / hop 441 / 128 梅尔带），<b>不是从某个具体模型里
 * 抄出来的</b>。特征和训练时不一致的话，推理<b>不会报错</b>——它会安静地吐出
 * 一段看似正常的激活值，而拍点全错。
 *
 * <p>这正是清单里 {@code input} 字段要如实写清的原因。真实模型到位后，
 * 第一件事是拿人工标注的音乐核对参数，而不是直接接进播放链路。
 *
 * <h2>与上游的类型差异（按 docs/audio-port.md 记录）</h2>
 *
 * <ul>
 *   <li>上游字段是 {@code pub}，且调用点/测试普遍用 {@code MelParams { n_fft: 1024,
 *       ..MelParams::default() }} 这种<b>字面量更新</b>。Java 没有对应语法，这里保留
 *       <b>公开可变字段</b> + {@link #defaults()} 工厂：只改关心的那一项，
 *       其余沿用默认值（与 {@code MixTuning} 同一处理）。
 *       构造好的 {@link MelFrontend} / 模型会复制一份（上游是按值传参），
 *       所以之后改原对象不会影响已经建好的前端。</li>
 *   <li>上游 {@code validate()} 返回 {@code Result<(), String>}；Java 侧按约定抛
 *       {@link IllegalArgumentException}，消息与上游逐字相同（测试按同样的片段断言）。</li>
 *   <li>{@code usize}/{@code u32} → {@code int}，{@code f32} → {@code float}
 *       （字面量带 {@code f}）。</li>
 *   <li>{@code #[derive(Clone, PartialEq)]} → 复制构造 + {@link #equals}/
 *       {@link #hashCode}（字段全比）。上游测试
 *       {@code beats_with_a_reused_frontend_matches_rebuilding_it_every_call} 里有
 *       {@code assert_eq!(model.mel_params(), &p)}，Java 的对应物就是
 *       {@code assertEquals(p, model.melParams())}，所以 {@code equals} 是<b>必需的</b>，
 *       不是顺手加的。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_model::beat::MelParams}。
 */
public final class MelParams {

    /** 目标采样率。输入不是这个采样率时<b>由调用方先重采样</b>。 */
    public int sampleRate;

    /** FFT 窗口长度，必须是 2 的幂。 */
    public int nFft;

    /** 帧移。 */
    public int hop;

    /** 梅尔带数。 */
    public int mels;

    /** 最低频率。 */
    public float fmin;

    /** 最高频率。超过奈奎斯特会被夹住。 */
    public float fmax;

    /** 对数压缩系数：{@code ln(1 + k * x)}。取 0 表示不做压缩。 */
    public float logK;

    /**
     * 全参构造（字段顺序与上游 {@code struct MelParams} 的声明顺序一致）。
     *
     * <p>日常调用点请用 {@link #defaults()} 再改个别字段——七个位置参数是
     * 最容易看错顺序的那种签名。
     */
    public MelParams(int sampleRate, int nFft, int hop, int mels,
                     float fmin, float fmax, float logK) {
        this.sampleRate = sampleRate;
        this.nFft = nFft;
        this.hop = hop;
        this.mels = mels;
        this.fmin = fmin;
        this.fmax = fmax;
        this.logK = logK;
    }

    /** 复制构造（上游 {@code #[derive(Clone)]}）。 */
    public MelParams(MelParams other) {
        this(other.sampleRate, other.nFft, other.hop, other.mels,
                other.fmin, other.fmax, other.logK);
    }

    /**
     * 默认参数：Beat This! 一类的拍点模型常见的规格。
     *
     * <p>Java 没有 {@code Default} trait，对应物是这个静态工厂（与
     * {@code MixTuning.defaults()} 同一处理）。上游可以把它当零值随便共享，
     * 这里<b>每次返回新对象</b>——字段可变，共享一份会被谁改一下都不知道。
     *
     * <p><b>它不是 Beat This! 真正要的参数</b>：{@code n_fft} 这里给的是 2048，
     * 而模型训练时用的是 1024。真要跑模型请用 {@link #beatThis()}。
     */
    public static MelParams defaults() {
        return new MelParams(22_050, 2048, 441, 128, 30.0f, 11_025.0f, 1000.0f);
    }

    /**
     * <b>实测出来的 Beat This! 前端参数</b>（本仓库新增，上游没有对应物）。
     *
     * <p>上游 {@link #defaults()} 的注释把话说得很清楚：「默认值是一套通用的 log-mel
     * 配置，不是从某个具体模型里抄出来的……真实模型到位后，第一件事是拿人工标注的
     * 音乐核对参数」。现在模型到位了（本机 {@code D:\DevTools\sonalis\modelcheck}
     * 有全量与 small 的 {@code beat_this.onnx}，清单里的 {@code mel_spectrogram.onnx}
     * 就是它的官方前端），这一条就是那次核对的结论。
     *
     * <h3>怎么测出来的</h3>
     *
     * <p>素材是合成节拍（12 秒、22050 Hz 单声道、每拍一个衰减脉冲 + 极轻底噪），
     * 用 {@code beat_this_small.onnx} 跑真实推理，再看 {@code Peaks.pickPeaks} +
     * {@code Analysis.tempoFromBeats} 给出的 BPM。测过的组合与结果：
     *
     * <table>
     *   <tr><th>n_fft</th><th>log_k</th><th>120 BPM</th><th>100 BPM</th><th>140 BPM</th></tr>
     *   <tr><td>1024</td><td>1000</td><td><b>120.00</b></td><td><b>100.00</b></td>
     *       <td><b>142.86</b>（与官方前端一致）</td></tr>
     *   <tr><td>2048</td><td>1000</td><td>120.00</td><td>100.00</td>
     *       <td>136.36（只挑出 11 个峰，中间整段丢失）</td></tr>
     *   <tr><td>1024 / 2048</td><td>0（线性）</td><td colspan="3">
     *       <b>一个峰都挑不出来</b>——模型要的就是对数压缩后的输入</td></tr>
     *   <tr><td>1024 / 2048</td><td>1</td><td colspan="3">同上，一个峰都没有</td></tr>
     * </table>
     *
     * <p>另外，拿官方前端 {@code mel_spectrogram.onnx} 作为对照：
     * 它对静音输出<b>恒为 0</b>、幅度每涨 10 倍输出涨 {@code ln 10}，
     * 也就是压缩形式确实是 {@code ln(1 + k·x)}（与上游的 {@code log_k} 同形）。
     * 在 12 秒节拍素材上，官方前端给出的拍点是 0.000/0.500/1.000…，
     * 本参数给出 0.023/0.503/1.003…——同样的栅格，
     * 时间差是「窗口中心」约定带来的（差 512 个样本 ≈ 23 ms，见下）。
     *
     * <h3>上面那张表是在<b>权威那份权重</b>上复核过的</h3>
     *
     * <p>上表的第一次测量用的是 Sonalis 从 {@code ashudesai/songbird-models} 取的那个
     * 导出。项目后来的权威来源改成 <b>folia 的 {@code shared/modelManifest.json}</b>
     * （{@code mosynthkey/beat_this_cpp} 的导出，83077778 字节 /
     * {@code c5c1466e…}），那是一个<b>不同的文件</b>（输入名都不一样，见下），
     * 所以同一张表在权威那份上重跑了一遍，结论一致：
     *
     * <ul>
     *   <li>{@code n_fft = 1024, log_k = 1000} → 120.00 / 99.89 / 140.10 BPM，
     *       与官方前端（120.05 / 100.00 / 139.70）同档；</li>
     *   <li>{@code log_k = 0} 或 {@code 1} → <b>零个峰</b>；</li>
     *   <li>{@code n_fft = 2048} → <b>零个峰</b>（在 Sonalis 那份上还只是给出错误答案，
     *       在权威这份上直接一个峰都挑不出来）。</li>
     * </ul>
     *
     * <p>两份导出在细节上确实不同，除了输入名（{@code input_spectrogram} vs
     * {@code spectrogram}）与 batch 维（{@code 1} vs {@code -1}），
     * 同一段素材上的峰数也不完全一样。所以「换了一份导出」这件事必须靠实测确认，
     * 不能靠「同一个模型的导出应该一样」这种推断。
     *
     * <h3>⚠️ 与官方前端仍有一处<b>未消除</b>的差异（如实记录）</h3>
     *
     * <p>拿宽带素材逐带对比，本实现的线性 mel 比 {@code mel_spectrogram.onnx}
     * 小大约 <b>9~13 倍</b>（对数域上差 −2.2 到 −2.6，低半段 −2.18、高半段 −2.59）。
     * 主因是 {@code Fft.magnitude} 的归一化与那个参考实现不同（前者除以 {@code n}
     * 并给非直流 bin 乘 2，后者按 torchaudio 的口径），而 {@code Fft} 是硬算路共用的、
     * 有上游测试锁着的，<b>不能为了这一处改它</b>。
     *
     * <p>把 {@code log_k} 调到 11000 左右可以把这条偏差在对数域上盖掉，
     * 但那只是一种遮掩：偏差本身是频带相关的（高低半段差 0.4），
     * 而且会把一个「拍出来的数」塞进一个「上游猜的数」里。
     * 所以这里<b>不动 log_k</b>，把实测值记在这里——
     * 合成节拍上这个偏差不影响拍点位置（上面那张表就是证据），
     * <b>真实音乐上还没验过</b>，那需要人工标注的曲子。
     *
     * <h3>窗口约定带来的时间偏移</h3>
     *
     * <p>上游的帧约定是「窗口右端对齐样本末尾」({@code (len − n_fft)/hop + 1})，
     * 而参考实现是 torch 的 {@code center=True}（两端各补 {@code n_fft/2} 个零）。
     * 两者的帧栅格相差 {@code n_fft/2 = 512} 个样本（≡ 71 mod 441，约 3.2 ms）——
     * {@link MelFrontend#frameToSecs} 把窗口中心加回去了，所以<b>时间戳是对的</b>，
     * 差的只是「拍点落在哪一个 20 ms 格子上」这半个格子的量化。
     */
    public static MelParams beatThis() {
        return new MelParams(22_050, 1024, 441, 128, 30.0f, 11_025.0f, 1000.0f);
    }

    /**
     * 参数是否自洽。不自洽时抛 {@link IllegalArgumentException}。
     *
     * <p>上游返回 {@code Err(String)}；消息文本原样保留（含「2 的幂」「奈奎斯特」
     * 这些片段），便于按上游 {@code should_panic} 那套断言核对。
     */
    public void validate() {
        if (sampleRate == 0) {
            throw new IllegalArgumentException("采样率不能是 0");
        }
        if (!isPowerOfTwo(nFft) || nFft < 64) {
            throw new IllegalArgumentException("n_fft 必须是 ≥64 的 2 的幂，实际 " + nFft);
        }
        if (hop == 0 || hop > nFft) {
            throw new IllegalArgumentException("hop 应在 1..=" + nFft + " 之间，实际 " + hop);
        }
        if (mels == 0 || mels > 512) {
            throw new IllegalArgumentException("mels 应在 1..=512 之间，实际 " + mels);
        }
        if (!(fmin >= 0.0f && fmax > fmin)) {
            throw new IllegalArgumentException("频率范围不合法：" + fmin + " .. " + fmax);
        }
        if (fmax > (float) sampleRate / 2.0f) {
            throw new IllegalArgumentException(
                    "fmax " + fmax + " 超过奈奎斯特 " + ((float) sampleRate / 2.0f));
        }
    }

    /** 每帧产出多少个数。 */
    public int featureLen() {
        return mels;
    }

    /**
     * {@code usize::is_power_of_two()} 的镜像。
     *
     * <p>{@code 0.is_power_of_two()} 在 Rust 里是 {@code false}，所以 {@code 0} 取反后
     * 也走「不是 2 的幂」那一支——Java 的位运算写法必须显式排掉 0，否则
     * {@code (0 & -1) == 0} 会把 0 判成 2 的幂。
     */
    private static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    /** 对应上游 derive 的 {@code PartialEq}（七个字段全比）。 */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MelParams)) {
            return false;
        }
        MelParams p = (MelParams) o;
        return sampleRate == p.sampleRate
                && nFft == p.nFft
                && hop == p.hop
                && mels == p.mels
                && Float.compare(fmin, p.fmin) == 0
                && Float.compare(fmax, p.fmax) == 0
                && Float.compare(logK, p.logK) == 0;
    }

    @Override
    public int hashCode() {
        int h = sampleRate;
        h = 31 * h + nFft;
        h = 31 * h + hop;
        h = 31 * h + mels;
        h = 31 * h + Float.floatToIntBits(fmin);
        h = 31 * h + Float.floatToIntBits(fmax);
        h = 31 * h + Float.floatToIntBits(logK);
        return h;
    }

    /** 镜像上游 {@code #[derive(Debug)]}（字段名保持上游的下划线写法）。 */
    @Override
    public String toString() {
        return "MelParams { sample_rate: " + sampleRate
                + ", n_fft: " + nFft
                + ", hop: " + hop
                + ", mels: " + mels
                + ", fmin: " + fmin
                + ", fmax: " + fmax
                + ", log_k: " + logK + " }";
    }
}
