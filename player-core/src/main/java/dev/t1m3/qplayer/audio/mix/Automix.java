package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.dsp.Resampler;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.audio.dsp.PitchShifter;
import dev.t1m3.qplayer.util.Logger;

import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * 自动混音 —— 三个模式的实现（本类 = 上游 {@code automix.rs} 的模块级部分）。
 *
 * <h2>三个模式</h2>
 *
 * <table>
 *   <tr><th>模式</th><th>是什么</th><th>本模块的类型</th></tr>
 *   <tr><td><b>交叉淡化</b></td><td>最基本的等功率淡化</td><td>{@link Crossfade}（已有）</td></tr>
 *   <tr><td><b>声波重合</b></td><td>先判调性，再把出场曲的尾段转调到与进场曲相合</td><td>{@link HarmonicBlend}</td></tr>
 *   <tr><td><b>AI 模式</b></td><td>拍点对齐 + 五种接法</td><td>{@link AiMix}</td></tr>
 * </table>
 *
 * <h2>三个模式共用一套骨架</h2>
 *
 * <p>它们都实现同一个 {@link Transition} 接口，<b>引擎主体一行都不用改</b>——
 * 这是「过渡抽象为接口」那条设计决定的兑现。
 *
 * <p>真正让它们成为「自动」而不是「定时」的，是 {@link Transition#prepare}：
 * 编排器会在过渡开始<b>之前</b>把两首歌的句柄交给它，让它离线分析
 * （读尾部、读首部、算拍点、判调性），然后回一个 {@link TransitionPlan}。
 *
 * <h2>拍点对齐是怎么算的</h2>
 *
 * <p>目标：<b>过渡开始的那一刻，出场曲踩在拍上、进场曲也踩在拍上。</b>
 *
 * <p>出场曲的结尾是固定的（文件末尾），所以能调的只有「从哪里开始过渡」。
 * 做法是把窗口长度<b>延长到下一个拍点</b>：
 *
 * <pre>
 * 理想起点 = 文件末尾 − 理想窗口
 * 实际起点 = 不超过理想起点的最后一个拍
 * 实际窗口 = 文件末尾 − 实际起点        （因此 ≥ 理想窗口，最多多一拍）
 * </pre>
 *
 * <p>进场曲那一侧靠 {@link TransitionPlan#skipSecs} 解决：跳过它从文件开头到
 * 第一拍之间的那段，让它的第一拍正好落在过渡起点上。
 *
 * <h2>分析失败时怎么办</h2>
 *
 * <p>拍点估不出来（自由速度、纯噪声、太短）时<b>不做对齐</b>，退回固定窗口的
 * 等功率淡化。理由和 {@link Analysis} 模块里那条一致：<b>算不出来就别猜。</b>
 * 猜错的代价是切点落在奇怪的位置，比不做过渡更糟。
 *
 * <h2>洁净室说明</h2>
 *
 * <p>依据公开的算法思路独立实现（频谱通量拍点检测、自相关周期估计、
 * 频带分频的 bass swap、等功率淡化）。<b>未参考任何 AGPL / LGPL 项目的源码、
 * 命名、结构或测试数据。</b>四种接法的名字（beatCut / bassSwap / tailRide /
 * plainBlend）沿用规格给出的称呼，便于与设计文档对照。
 *
 * <h2>与上游的类型差异（按 docs/audio-port.md 记录）</h2>
 *
 * <ul>
 *   <li>Rust 的<b>模块级自由函数</b>（{@code fallback_window} / {@code fallback_window_with} /
 *       {@code limit_window} / {@code limit_window_with} / {@code intro_pitch_glide} /
 *       {@code new_pitch_shifter}）在 Java 里没有落点，按约定挂到一个<b>不可实例化</b>
 *       的类上（私有构造 + 全 {@code static}）。{@link Analysis} / {@link Harmonic} /
 *       {@link Chooser} 是同一处理。</li>
 *   <li>{@code read_mono} 在上游是模块私有 fn、返回元组
 *       {@code Result<(Vec<f32>, u32, Option<f64>)>}：Java 里返回一个不可变值类
 *       {@link Mono}（{@code Option<f64>} → 可为 {@code null} 的 {@code Double}），
 *       失败时抛 {@link AudioException}（对应 {@code SonalisError::Config}）。</li>
 *   <li>{@code &Path} → {@code String}（本地路径或 http(s) URL）：QPlayer 的音源可以是
 *       网络直链且需要请求头，{@link Transition#prepare}、{@link Decoder#open(String)}
 *       用同一种句柄。</li>
 *   <li>{@code together_angle} / {@code smoothstep} 在上游是模块私有 fn，但
 *       {@code mod tests} 是子模块、看得见父模块的私有项。Java 的对应可见性是
 *       <b>包私有</b>，所以这两个不加 {@code private}——只给同包的两个对齐测试用，
 *       不作为对外 API（与 {@link Analysis} 里 {@code tempoPrior} / {@code beatPhase}
 *       的处理相同）。</li>
 *   <li>所有 {@code Math.*} 的结果<b>立即</b> {@code (float)} 转换（Rust 那边是
 *       {@code f32::sqrt} / {@code f32::cos} / {@code f32::powf}）；{@code f32::clamp} /
 *       {@code f32::min} / {@code f32::max} 在 Java 里没有等价物（{@code Math.min/max}
 *       会把 NaN 透出去，Rust 的 {@code f32::min/max} 返回<b>另一个</b>操作数），
 *       所以本类里 {@link #clampF32} 之外的取小/取大一律走 {@link #minF32} /
 *       {@link #maxF32} 手工对齐。</li>
 *   <li>上游 {@code new_pitch_shifter} 里有一句 {@code #[cfg(test)] probe::record_pitch_shifter()}：
 *       那是「变调器必须在准备线程上造、不能在生产者线程上造」那条回归的探针
 *       （见 {@link PitchShifter} 的分配成本）。Java 没有「只在测试编译期存在」的
 *       机制，这里不移植该探针；接 {@code director} 移植时应当按同样的理由补一处
 *       <b>包私有</b>的探针钩子，否则那条回归无据可依。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::automix}。
 */
public final class Automix {

    /**
     * 读文件用于分析时，单次解码缓冲的容量。
     *
     * <p>与 {@code Deck} 取同一个理由：<b>必须覆盖任何单包</b>，否则
     * {@link Decoder#decodeInto} 会报错。
     */
    public static final int ANALYSIS_DECODE_FRAMES = 65_536;

    /**
     * 分析窗口长度（秒）。
     *
     * <p>12 秒在 60 BPM 下仍有两拍以上，够自相关看出周期；再长就是在浪费启动时间。
     */
    public static final double ANALYSIS_SECS = 12.0;

    /**
     * 出场侧变调滑入的终点（进度比例）。
     *
     * <p>前 25% 从零滑到全量：出场曲在窗口开始时是满音量，突然变调会听出「跳」。
     *
     * <p>上游是模块私有 const（不是 {@link MixTuning} 的旋钮——它是曲线的<b>形状</b>，
     * 不是听感量）。这里公开，因为 {@link HarmonicBlend#pitchAt} 的契约就是这条曲线。
     */
    public static final float OUTRO_PITCH_GLIDE_END = 0.25f;

    /**
     * 进场侧变调滑出的起点（进度比例）。
     *
     * <p>最后 30% 从全量滑回零：它在窗口结束时是满音量，必须已经回到原调。
     *
     * <p>理由同 {@link #OUTRO_PITCH_GLIDE_END}：上游模块私有，这里公开。
     */
    public static final float INTRO_PITCH_GLIDE_START = 0.70f;

    /** {@code core::f32::consts::FRAC_PI_2} 的 f32 镜像（{@link Math#PI} 是 double）。 */
    private static final float FRAC_PI_2 = (float) (Math.PI / 2.0);

    /** {@code core::f32::consts::FRAC_PI_4} 的 f32 镜像。 */
    private static final float FRAC_PI_4 = (float) (Math.PI / 4.0);

    private Automix() {
    }

    /**
     * 估不出拍点（或分析失败）时用的固定窗口（帧）。
     *
     * <p><b>永远不小于 1 帧。</b>窗口为 0 等价于硬接，而调用方是按「用户选了
     * 自动混音」来显示界面的——静默退化成硬接，用户完全看不出原因。
     */
    public static long fallbackWindow(MixStyle style, int rate) {
        return fallbackWindowWith(style, rate, 1.0f);
    }

    /**
     * 同上，但按 {@link MixTuning#windowScale} 缩放。
     *
     * <p>缩放要同时作用于「按拍算的理想窗口」和「估不出拍点时的退路」：
     * 否则用户把过渡调短之后，只有那些估出拍点的曲子变短了，
     * 估不出的反而更长——听起来像随机生效。
     *
     * <p>{@code f32::round} 是「半数远离零」，Java 的 {@code Math.round(float)} 是
     * {@code floor(x+0.5)}（返回 {@code int}）。两者只在负值上分叉，而这里的乘积
     * 恒为正（{@code fallback_secs > 0}、{@code scale > 0}、{@code rate >= 1}），
     * 所以直接用 {@code Math.round} 即可，不需要再写一遍
     * {@link Analysis} 里那种手工 {@code roundF32}。NaN 也一致：{@code Math.round(NaN)}
     * 给 0，Rust 的 {@code NaN as u64} 也给 0，之后都被 {@code max(1)} 兜住。
     */
    public static long fallbackWindowWith(MixStyle style, int rate, float scale) {
        float s = scale > 0.0f ? scale : 1.0f;
        float frames = style.fallbackSecs() * s * (float) Math.max(rate, 1);
        return Math.max((long) Math.round(frames), 1L);
    }

    /**
     * 把过渡窗口夹在「出场曲长度的一半」以内。
     *
     * <p>抽成独立函数是为了<b>可测</b>。上界这条规则最初是漏的，后果在端到端测试里
     * 才露出来：表现模式按 32 拍算窗口，120 BPM 下是 16 秒；而它落在一首
     * <b>只有 8 秒</b>的曲子上时，整首歌都被当成了过渡窗口——进场曲在淡入的
     * 过程中就播到了自己的结尾，于是「两首歌加起来只播出一首的长度」。
     *
     * <p>「不超过一半」是个保守的界：过渡本来就该是几秒的事，不该吃掉曲子主体。
     */
    public static long limitWindow(long idealFrames, double songSecs, int rate) {
        return limitWindowWith(idealFrames, songSecs, rate, MixTuning.MAX_WINDOW_FRACTION);
    }

    /**
     * 同上，但上界由调参给出。
     *
     * <p>上界默认取 {@link MixTuning#MAX_WINDOW_FRACTION}——<b>不另抄一份字面量</b>：
     * 上游把听感常数集中进 {@code tuning.rs} 的整个立论就是「默认值必须仍然只有这一份」。
     */
    public static long limitWindowWith(long idealFrames, double songSecs, int rate, double fraction) {
        long ideal = Math.max(idealFrames, 1L);
        double f = fraction > 0.0 ? fraction : MixTuning.MAX_WINDOW_FRACTION;
        // 上游是 `!(song_secs > 0.0)`：NaN 也走这一支（取反比较为真），保持一致。
        if (!(songSecs > 0.0) || rate == 0) {
            // **拿不到时长就不夹。** 猜一个上界比不夹更危险：猜小了会让本该
            // 对拍的过渡退化成短淡化，而用户完全看不出原因。
            return ideal;
        }
        // f64::round 与 Math.round(double) 同样只在负值上分叉，这里的乘积恒为正。
        long cap = Math.round(songSecs * f * (double) rate);
        return Math.min(ideal, Math.max(cap, 1L));
    }

    /**
     * 进场侧变调的滑出曲线：前 70% 保持全量，最后 30% 滑回零。
     *
     * <p>当前没有接法用它（声波重合转的是出场曲）。留着是因为「转进场曲」的接法
     * 迟早要有，而那条曲线与 {@link HarmonicBlend#pitchAt} 是成对的：
     * 进场曲在窗口结束时<b>还要接着播</b>，所以它必须滑回原调。
     */
    public static float introPitchGlide(float progress) {
        return 1.0f - smoothstep(INTRO_PITCH_GLIDE_START, 1.0f, progress);
    }

    /**
     * 从 {@code startSecs} 起读一段<strong>立体声</strong>素材，重采样到输出规格。
     *
     * <p>与 {@link #readMono} 的区别是<strong>不折成单声道</strong>——这不是口味问题：
     * 去人声（{@code StemSeparators}）按「中间/两侧」分解，把素材折成单声道再去人声
     * 等于把整段都当人声减掉（历史上生成式那一档「交给模型的必须是伴奏」，
     * 所以它要的正是立体声。返回 {@code {左, 右}}，单声道素材左右同一份
     * （{@code StemSeparator.separate} 的约定）。
     *
     * <h3><strong>已知边界（2026-10-06 实测）：这条路与 deck「自然播」不在同一根轴上</strong></h3>
     *
     * <p>本方法是<strong>定位读</strong>（{@code dec.seek(startSecs)}），而过渡窗口开始前
     * 用户听到的是 deck <strong>从 0 自然播</strong>到那个位置的声音——两条读法在同一个
     * 时刻拿到的样本实测差 <strong>2304 帧（48.000 ms @48k）</strong>，而
     * {@code Deck.seek(startSecs)} 与本方法<strong>逐位相同</strong>（偏移 0）。也就是
     * 「对不上的只有自然播那条」，于是窗口起点是一次听得见的内容跳变。
     *
     * <p><b>根因不在本类、也不在 {@link Deck}</b>，两条 seek 读法走的是同一条
     * {@code Decoder.seek}；换成无损 WAV 素材时偏移是 0，所以差异全部来自解码器层
     * （桌面端 {@code Mp3CodecAdapter} / {@code Mp3XingTag}）：
     *
     * <ol>
     *   <li>jlayer 会把 <strong>Info/Xing 帧</strong>当成一帧真解出 1152 个采样，
     *       而 LAME 标签里的延迟是<strong>从第一个音频帧算起</strong>的，
     *       所以「头部丢 {@code delay + 528 + 1} = 1105 采样」（沙盒里那句日志的 1105
     *       就是这个式子）只对了<em>音频帧</em>那根轴——在 jlayer 的轴上正确的裁剪量是
     *       {@code 1105 + 1152 = 2257}。自然播因此整条<strong>早 24 ms</strong>；</li>
     *   <li>定位之后解码器状态（jlayer 的比特池记账）没有预热，落地还会再偏 0~2 帧
     *       ——这是「有些素材偏 1 帧、有些偏 2 帧」的来源。</li>
     * </ol>
     *
     * <p>判据：{@code WindowReadAlignmentProbe}（量这个偏移；<b>现在就是红的</b>，
     * 修在解码器那一层之后应当转绿）。<strong>不要在调用方这里加补偿</strong>：
     * 补偿量是解码器的内部状态，靠猜只会把跳变换个方向。
     */
    public static float[][] readStereoWindow(String source, java.util.Map<String, String> headers,
                                             double startSecs, int windowFrames, int outRate) {
        int srcRate;
        boolean mono;
        float[] left;
        float[] right;
        Decoder dec = headers == null || headers.isEmpty()
                ? Decoder.open(source) : Decoder.open(source, headers);
        try {
            TrackSpec spec = dec.spec();
            srcRate = Math.max(spec.sampleRate, 1);
            mono = spec.layout.count() < 2;
            // 换算成源采样率下要多少帧，再多要一点点给重采样器收尾
            long wantSrc = Math.min(
                    (long) Math.ceil((double) windowFrames * (double) srcRate / (double) Math.max(outRate, 1))
                            + (long) RESAMPLE_MARGIN_FRAMES,
                    (long) Integer.MAX_VALUE - 8);
            if (startSecs > 1e-9) {
                dec.seek(startSecs);
            }

            int cap = Math.min(Automix.ANALYSIS_DECODE_FRAMES,
                    (int) Math.min(wantSrc + 4096L, Integer.MAX_VALUE - 8));
            AudioBuf buf = new AudioBuf(spec.layout, Math.max(cap, 1));
            left = new float[(int) wantSrc];
            right = new float[(int) wantSrc];
            int len = 0;
            while ((long) len < wantSrc) {
                int n = dec.decodeInto(buf);
                if (n == 0) {
                    break;
                }
                if ((long) len + n > left.length) {
                    long grown = Math.min(Math.max((long) left.length * 2L, (long) len + n),
                            (long) Integer.MAX_VALUE - 8);
                    left = Arrays.copyOf(left, (int) grown);
                    right = Arrays.copyOf(right, (int) grown);
                }
                // 每次 decodeInto 之后重新取视图：视图长度是调用那一刻的 frames
                // （Automix.readMono 里踩过同一个坑）。
                FloatBuffer l = buf.channel(0);
                FloatBuffer r = buf.channel(mono ? 0 : Math.min(1, buf.channels() - 1));
                for (int i = 0; i < n; i++) {
                    left[len + i] = l.get(i);
                    right[len + i] = r.get(i);
                }
                len += n;
            }
            if (len < left.length) {
                left = Arrays.copyOf(left, len);
                right = Arrays.copyOf(right, len);
            }
        } finally {
            dec.close();
        }
        left = resampleMono(left, srcRate, outRate, windowFrames);
        // 单声道素材按 StemSeparator.separate 的约定「左右传同一份」
        right = mono ? left : resampleMono(right, srcRate, outRate, windowFrames);
        return new float[][]{left, right};
    }


    static float[] resampleMono(float[] src, int srcRate, int outRate, int wantFrames) {
        // **一份实现，不再抄第二遍**（2026-10-02 修）：`BeatModels.resampleMono` 那条循环
        // 早就把重采样器的契约写清楚了——「pushAll 会一路喂到喂不进去为止；历史满时先拉干
        // 腾地方再补喂」，而且它的注释里明写「块长要小于 Resampler.HISTORY_FRAMES(8192)，
        // 否则 push 会静默丢数据」。
        //
        // 这里原先自己抄了一份，抄丢了最关键的那一条：`rs.pushAll(in); fed += k;` ——
        // **不看返回值**，把没收下的帧当成喂过了。那些帧于是被静默丢掉，产物**既短又压缩**。
        // 实测（606382 帧 44.1k → 要 659868 帧 @48k，正是用户那次 13.7 s 窗口的形状）：
        // 只写出 611201 帧 = 覆盖 92.6%，内容被时间压缩 8.66%（= 48000/44100）——
        // 「素材下标 t 处放的是原曲 1.0866·t 处的内容」。
        //
        // 听感后果正是用户报的两句话：
        //   · 「融合部分加速了但本体时间没加速」——窗口里进场曲快放 8.66%，而 deck 的钟没变；
        //   · 「回归到 B 的时候出现听感进度回跳 / 刚听过的内容又听了一遍」——交接时 deck 用
        //     正确速度接上，内容往回退「窗口 × 8.66%」（13.7 s 窗口 ≈ 1.2 s）。
        // 只对**需要重采样**的素材成立（44.1 kHz 源 + 48 kHz 输出就是最常见的一种），
        // 同采样率时走 `srcRate == outRate` 的快路径，所以以前那些 48 kHz 素材的用例都测不到。
        //
        // 判据：{@code AutomixTest#resampleKeepsTheTimelineAndCoversTheWholeWindow}。
        if (srcRate == outRate) {
            return Arrays.copyOf(src, Math.min(src.length, wantFrames));
        }
        float[] resampled = dev.t1m3.qplayer.audio.model.BeatModels.resampleMono(
                src, srcRate, outRate);
        // 调用方要的是「窗口那么长的一段」：多出来的截掉，真的不够就如实短着返回
        // （`covered` 就是它，短了会在窗口尾部保持未分离，而不是凭空补静音）。
        return Arrays.copyOf(resampled, Math.min(resampled.length, Math.max(wantFrames, 0)));
    }

    /** 把重采样器吐出来的那一段按上限搬进数组（越界就少搬，绝不截断源）。 */
    private static int copyOut(int produced, float[] dst, int at, int limit, AudioBuf src) {
        int n = Math.min(Math.min(produced, src.frames()), limit - at);
        if (n <= 0) {
            return 0;
        }
        FloatBuffer r = src.channel(0);
        for (int i = 0; i < n; i++) {
            dst[at + i] = r.get(i);
        }
        return n;
    }

    /**
     * 构造一个变调器，参数按输出侧规格。
     *
     * <p>放在这里而不是 {@code automix} 之外，是因为调用方（{@code Deck}）只该知道
     * 「挂一个变调器」，不该知道粒长怎么选。
     *
     * <p><b>调用线程有硬要求</b>：造它要分配环形缓冲，而它曾经落在开始过渡的那一瞬间、
     * 在生产者线程上。过渡实例的<b>生命周期约定</b>因此是：每个接法实例只服务一次过渡，
     * 准备时另起线程造新实例跑 {@link Transition#prepare}，过渡真正开始时才把它换上来
     * （渲染路径上不加锁）。见 {@link AiMix} 类注释里那条展开。
     */
    public static PitchShifter newPitchShifter(int channels, int rate, float semitones, int chunk) {
        return new PitchShifter(channels, rate, semitones, chunk);
    }

    /**
     * 把进度映射成等功率曲线的角度，中段留出一段<b>平台</b>。
     *
     * <h3>为什么需要它：形状，而不是长度</h3>
     *
     * <p>「淡化」和「混音」的差别不在过渡多长，而在曲线什么形状。
     * 把过渡从 4 拍拉到 16 拍，听感仍然是「一次淡出」——因为它确实就是。
     * 让听感变成「两首歌真的叠在一起」的，是中段那段<b>不动</b>：
     * 两曲各停在 {@code cos(π/4) = sin(π/4) ≈ 0.707}（也就是等响）持续一会儿，
     * 低频就在这一段换手。
     *
     * <h3>平台不消耗任何余量，这是数学保证的</h3>
     *
     * <p>平台正落在 45° 那一点上，而 {@code cos²θ + sin²θ = 1} 对<b>任何</b> θ 成立。
     * 所以无论 {@code together} 取多少，两路功率和都逐点等于 1——
     * 平台既不会让中段塌下去，也不会让中段顶上去。
     *
     * <p>{@code together = 0} 退化成 {@code θ = p·π/2}，也就是原来那条纯等功率淡化曲线。
     *
     * <p>包私有而非 {@code private}：上游的私有 fn 对子模块 {@code mod tests} 可见，
     * Java 的对应可见性就是包私有（只给同包的 {@code AiMixTest} 用）。
     */
    static float togetherAngle(float p, float together) {
        float t = clampF32(together, 0.0f, 0.9f);
        float x = clampF32(p, 0.0f, 1.0f);
        // 两段斜坡合起来占剩下的部分，各占一半
        float arc = (1.0f - t) * 0.5f;
        if (arc <= 0.0f) {
            return x * FRAC_PI_2;
        }
        if (x < arc) {
            return (x / arc) * FRAC_PI_4;
        }
        if (x < arc + t) {
            return FRAC_PI_4;
        }
        return FRAC_PI_4 + ((x - arc - t) / arc) * FRAC_PI_4;
    }

    /**
     * 平滑阶跃（3 次 Hermite）。两端一阶导为零，所以不会有折角。
     *
     * <p>包私有而非 {@code private}：理由同 {@link #togetherAngle}。
     */
    static float smoothstep(float edge0, float edge1, float x) {
        if (edge1 <= edge0) {
            return x < edge0 ? 0.0f : 1.0f;
        }
        float t = clampF32((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    /** 重采样时多要的帧数：给多相核的收尾留一点余量。 */
    private static final int RESAMPLE_MARGIN_FRAMES = 128;

    /** 读盘/重采样时每次搬多少帧。 */
    private static final int DECODE_CHUNK_FRAMES = 4096;

    /**
     * 读出文件的尾部或首部，并折叠成单声道。
     *
     * <ul>
     *   <li>{@code tail == true}：从文件末尾往前 {@code secs} 秒开始读</li>
     *   <li>{@code tail == false}：从文件开头读 {@code secs} 秒</li>
     * </ul>
     *
     * <p>返回（音频, 该文件的采样率, 时长秒）。<b>采样率要一起返回</b>：拍点位置是「帧数」，
     * 换算成秒必须用源文件的采样率，用错会让对齐整体偏移。时长一起带出来，
     * 让调用方不必为拿时长再打开一次文件（每次打开都是一次容器探测）。
     *
     * <h3>时长未知时尾部读取必须报错</h3>
     *
     * <p>没有总长（无 Xing/VBRI 头的 CBR MP3、流式输入）就<b>找不到尾部</b>。
     * 曾经这里 {@code unwrap_or(0.0)} 之后 {@code start = 0}——静默退化成<b>从开头读</b>，
     * 然后把开头 12 秒当成尾部 12 秒去算 BPM、做拍点对齐，全链路无人报错。
     * 现在如实失败：调用方退回固定窗口，note 里写明原因。
     *
     * <p>包私有而非 {@code public}：上游是模块私有 fn，只有 {@link AiMix} 与
     * {@link HarmonicBlend} 用它；Java 的对应可见性就是包私有。
     *
     * @throws AudioException 打不开、解码失败，或尾部读取时时长未知
     */
    static Mono readMono(String source, boolean tail, double secs) {
        return readMono(source, null, tail, secs);
    }

    /**
     * 同 {@link #readMono(String, boolean, double)}，但带上提供商要求的请求头。
     *
     * <h3>本仓库新增，上游没有对应物</h3>
     *
     * <p>上游的音源都是本地路径，{@code read_mono} 里没有请求头这一层。
     * QPlayer 的队列里可以混着网易云/插件的直链，而那种直链<strong>确实</strong>需要
     * 请求头（与播放走的是同一份 {@code Track.streamHeaders}）；只认裸 source
     * 的话，这些曲目会以一种「打不开」的形式分析失败，而不是被当成「需要请求头」。
     *
     * <p>加的是一个<strong>重载</strong>而不是改原签名：{@link AiMix} /
     * {@link HarmonicBlend} 那条移植路径的调用点一行都不用动，与上游仍可逐行对照。
     *
     * @param headers 请求头；{@code null} / 空表示不带
     */
    static Mono readMono(String source, java.util.Map<String, String> headers, boolean tail, double secs) {
        boolean noHeaders = headers == null || headers.isEmpty();
        Decoder dec = noHeaders
                ? Decoder.open(source)
                : Decoder.open(source, headers);
        try {
            TrackSpec spec = dec.spec();
            int rate = Math.max(spec.sampleRate, 1);
            double wantSecs = secs > 0.0 ? secs : 0.0;
            // 上游 `(rate as f64 * secs.max(0.0)) as usize`；Java 的数组下标是 int，
            // 这里夹到 int 上限（一首 3 小时的 192 kHz 素材才够得着这个界）。
            long want = Math.min((long) ((double) rate * wantSecs), (long) Integer.MAX_VALUE - 8);

            if (tail) {
                Double dur = spec.durationSecs();
                if (dur == null) {
                    throw AudioException.config(
                            "文件时长未知（无总长头部的编码或流式输入），无法定位尾部 "
                                    + fileStem(source));
                }
                double start = dur.doubleValue() - secs;
                if (start < 0.0) {
                    start = 0.0;
                }
                if (start > 0.0) {
                    dec.seek(start);
                }
            }

            // 上游 `Vec::with_capacity(want.min(1 << 22) + 4096)`：先按提示分配，
            // 之后按需增长。Java 的数组没有 push，这里手工维护 len 与增长
            // （每次恰好拷贝已解出的部分，数值路径与上游逐元素一致）。
            long hint = Math.min(want, 1L << 22) + 4096L;
            float[] pcm = new float[(int) Math.min(hint, (long) Integer.MAX_VALUE - 8)];
            int len = 0;
            AudioBuf buf = new AudioBuf(spec.layout, ANALYSIS_DECODE_FRAMES);

            while ((long) len < want) {
                int n = dec.decodeInto(buf);
                if (n == 0) {
                    break;
                }
                if ((long) len + n > pcm.length) {
                    long grown = Math.max((long) pcm.length * 2L, (long) len + n);
                    pcm = Arrays.copyOf(pcm, (int) Math.min(grown, (long) Integer.MAX_VALUE - 8));
                }
                int chs = Math.max(buf.channels(), 1);
                // **每个块取一次裸数组与各声道起点**（2026-10-03，性能）：原来在逐**采样**的
                // 内层写 `buf.channel(c).get(i)`——`AudioBuf.channel` 每次都现包一个
                // `FloatBuffer`（wrap + slice 两个对象），一次 12 秒读取就是上百万个。
                // 原来的注释担心「在循环外取会拿到长度 0 的视图」是对的，但那说的是
                // **decodeInto 之前**；这里在 decodeInto 之后、采样循环之外取，长度已经对了。
                // 读出来的 float 逐位相同（`StreamingSoundPlayer` 那侧也是同一套约定）。
                float[] backing = buf.backingArray();
                int[] chanBase = new int[chs];
                for (int c = 0; c < chs; c++) {
                    chanBase[c] = buf.channelBase(c);
                }
                for (int i = 0; i < n; i++) {
                    float s = 0.0f;
                    for (int c = 0; c < chs; c++) {
                        s += backing[chanBase[c] + i];
                    }
                    pcm[len + i] = s / (float) chs;
                }
                len += n;
            }

            int kept = (int) Math.min((long) len, want);
            float[] out = kept == pcm.length ? pcm : Arrays.copyOf(pcm, kept);
            return new Mono(out, rate, spec.durationSecs());
        } finally {
            dec.close();
        }
    }

    /**
     * {@code read_mono} 的返回值。
     *
     * <p>对应上游那个元组 {@code (Vec<f32>, u32, Option<f64>)}：Java 没有元组，
     * 按约定用一个不可变值类代替（字段与元组位置一一对应）。
     */
    public static final class Mono {

        /** 折叠成单声道的 PCM。 */
        public final float[] pcm;

        /** 源文件的采样率（已保证 ≥ 1）。 */
        public final int rate;

        /** 时长（秒）。总帧数未知时为 {@code null}（上游是 {@code None}）。 */
        public final Double durationSecs;

        Mono(float[] pcm, int rate, Double durationSecs) {
            this.pcm = pcm;
            this.rate = rate;
            this.durationSecs = durationSecs;
        }

        /**
         * 只打印形状，不打印整段 PCM：上游的 {@code Debug} 会把几万个采样刷进日志
         * （与 {@link AudioBuf} / {@code Analysis.Onsets} 同一处理）。
         */
        @Override
        public String toString() {
            return "Mono { pcm: [" + pcm.length + " 个采样], rate: " + rate
                    + ", duration_secs: " + durationSecs + " }";
        }
    }

    /**
     * {@code f32::clamp} 的镜像：NaN 原样穿过去（Java 没有对应方法，行为手工对齐）。
     *
     * <p>上游对 {@code f32} 用的是内建的 {@code clamp}，所以本类与 {@link AiMix}
     * 各写一份本地实现（两处都是同一个语义，不共享是为了不引入跨类的耦合）。
     */
    static float clampF32(float v, float lo, float hi) {
        if (v < lo) {
            return lo;
        }
        if (v > hi) {
            return hi;
        }
        return v;
    }

    /**
     * {@code f32::min} 的镜像。
     *
     * <p>Java 的 {@link Math#min(float, float)} 遇到 NaN 会把 NaN 透出去，
     * Rust 的 {@code f32::min} 正相反：一个操作数是 NaN 时返回<b>另一个</b>
     * （两个都是 NaN 才返回 NaN）。上游在 {@code beatCut} 的块内步进里用
     * {@code (p + i * per_frame).min(1.0)} 把进度钉在 1.0，NaN 一走两边的产物不同；
     * 正常素材上两者给出同一个值。
     */
    static float minF32(float a, float b) {
        if (Float.isNaN(a)) {
            return b;
        }
        if (Float.isNaN(b)) {
            return a;
        }
        return Math.min(a, b);
    }

    /** {@code f32::max} 的镜像，理由同 {@link #minF32}。 */
    static float maxF32(float a, float b) {
        if (Float.isNaN(a)) {
            return b;
        }
        if (Float.isNaN(b)) {
            return a;
        }
        return Math.max(a, b);
    }

    /**
     * 从音源句柄里取「文件名主干」，只用于错误文案。
     *
     * <p>上游是 {@code path.file_stem()}（{@code Option<OsString>}，取不到就打 {@code None}）。
     * QPlayer 的句柄可以是本机路径，也可以是带查询串的 http(s) 直链，
     * 所以这里只做「最后一个分隔符之后、去掉最后一个扩展名」——
     * 与 {@code file_stem} 在本地路径上一致；网络直链上多出来的查询串比整条 URL
     * 更适合进日志（错误文案会被写进 {@code plan.note} 给用户看）。
     */
    private static String fileStem(String source) {
        if (source == null) {
            return "null";
        }
        int slash = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
        String name = slash >= 0 ? source.substring(slash + 1) : source;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
