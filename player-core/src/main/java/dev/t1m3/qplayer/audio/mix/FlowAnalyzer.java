package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.dsp.Fft;

import java.util.Arrays;
import java.util.Map;

/**
 * 把一首曲目<strong>量成</strong> {@link FlowFeatures}。
 *
 * <h2>它读什么、怎么读</h2>
 *
 * <p>三段窗口，各自跑一遍 {@link Analysis#analyze}（频谱通量 + 自相关 + 速度先验，
 * 纯算法、无模型、零下载）：
 *
 * <table>
 *   <tr><th>窗口</th><th>长度</th><th>位置</th><th>给出什么</th></tr>
 *   <tr><td>整段</td><td>{@value #WHOLE_SECS} 秒</td><td>开头</td>
 *       <td>整段速度与响度；<strong>贴合分</strong>用这一对</td></tr>
 *   <tr><td>开头</td><td>{@value #EDGE_SECS} 秒</td><td>开头</td>
 *       <td>接进来时的速度与响度</td></tr>
 *   <tr><td>结尾</td><td>{@value #EDGE_SECS} 秒</td><td>末尾</td>
 *       <td>交出去时的速度与响度</td></tr>
 * </table>
 *
 * <p>「首尾各 15 秒」这件事是 LiveSort 公开说明里写明的（它专门分析首尾片段，
 * 「提升切换判断准确度」），也是本模块存在的理由：<strong>排歌关心的是接缝</strong>，
 * 而接缝只发生在开头和结尾。
 *
 * <p>整段窗读到 30 秒之后，开头窗是它的<strong>前缀</strong>（前 15 秒），
 * 不再单独开一次文件——每次 {@code Decoder.open} 都是一次容器探测（网络源还有一次
 * 请求往返），队列里几十首歌乘上这个成本很可观。
 *
 * <h2>只读两遍，不是三遍</h2>
 *
 * <p>结尾窗必须单独读（要 seek 到末尾），所以每首歌是<strong>两次</strong>
 * {@code Decoder.open}：一次读开头 30 秒，一次读结尾 15 秒。整段与开头窗共用第一次。
 *
 * <h2>亮度用真正的频谱质心</h2>
 *
 * <p>{@link #toneOf} 走 {@link Fft}：分帧加 Hann 窗，取幅度谱，按
 * {@code Σ f·|X(f)| / Σ |X(f)|} 求质心（Hz）。这一项在 LiveSort 的公开说明里
 * 叫「亮度 / 频谱质心、反映声音的明亮程度」——这里按那个<strong>定义</strong>实现，
 * 而不是照抄它源码里那个（名为质心、实为相邻样本差与绝对值之比）的近似量。
 * 界面上它只是一个辅助权重（0.2），取哪个近似都听得出来差别不大，
 * 但名字与算法对得上是可维护性的底线。
 *
 * <h2>洁净室说明</h2>
 *
 * <p>见 {@link FlowFeatures} 的类注释。
 */
public final class FlowAnalyzer {

    /** 整段分析窗（秒）。与 {@link Automix#ANALYSIS_SECS} 取同一个量级，理由相同。 */
    public static final double WHOLE_SECS = 30.0;

    /** 首尾分析窗（秒）。LiveSort 公开说明给出的「首尾 15 秒」。 */
    public static final double EDGE_SECS = 15.0;

    /** 质心分析的帧长（样本）。2048 在 44.1 kHz 下约 46 ms，频率分辨率约 21.5 Hz。 */
    static final int TONE_FRAME = 2048;

    /**
     * 质心分析的帧移（样本）。
     *
     * <p>取 {@code 2 × TONE_FRAME}，也就是<strong>不重叠</strong>。质心是在 30 秒上求
     * 平均的统计量，重叠只会让同样的信息多算几遍：44.1 kHz 下 30 秒本来就有约 320 帧，
     * 足以让均值稳下来，而重叠 4 倍会把这一项从几毫秒推到二十几毫秒——
     * 一整个歌单上就是几秒，换不来任何结论上的差别。
     */
    static final int TONE_HOP = TONE_FRAME * 2;

    /**
     * 一段单声道 PCM + 它的采样率。
     *
     * <p>直接复用 {@link Automix.Mono}（本包已有的类型，字段就是这三样），
     * 而不是再造一个同形状的值类。
     */
    public interface Reader {

        /**
         * 读一段。
         *
         * @param source  本机路径 / {@code content://} / http(s) 直链
         * @param headers 提供商要求的请求头；可为 {@code null}
         * @param tail    {@code true} 读末尾 {@code secs} 秒，{@code false} 读开头
         * @param secs    要读的秒数
         * @throws AudioException 打不开、解码失败，或尾部读取时时长未知
         */
        Automix.Mono read(String source, Map<String, String> headers, boolean tail, double secs);
    }

    /**
     * 默认读法：走宿主的 {@code Decoder}。
     *
     * <p>与 {@link Automix#readMono} 是同一套窗口策略（尾部读取在时长未知时如实失败，
     * 而不是静默从开头读），只是把请求头一起传下去。
     */
    public static final Reader DECODER = new Reader() {
        @Override
        public Automix.Mono read(String source, Map<String, String> headers, boolean tail, double secs) {
            return Automix.readMono(source, headers, tail, secs);
        }
    };

    private final Reader reader;

    /** 走宿主解码器。 */
    public FlowAnalyzer() {
        this(DECODER);
    }

    /**
     * 用指定的读法。
     *
     * <p>给测试用：{@code FlowAnalyzerTest} 直接喂合成 PCM，不必真去解码一个文件，
     * 于是「窗口怎么切、首尾特征怎么取」这件事有了可执行的证据，
     * 而不是只能靠听一整个队列来间接判断。
     */
    public FlowAnalyzer(Reader reader) {
        if (reader == null) {
            throw new IllegalArgumentException("reader 不能为 null");
        }
        this.reader = reader;
    }

    /**
     * 量一首歌。
     *
     * @param source  音源句柄（本机路径 / {@code content://} / http(s) 直链）
     * @param headers 提供商要求的请求头；可为 {@code null}
     * @throws AudioException 读不到任何可用素材。调用方应当把这一首<strong>如实记为
     *                        「没分析出来」</strong>，而不是给它编一份特征
     *                        （见 {@link FlowPlanner#plan} 对 {@code null} 槽位的约定）
     */
    public FlowFeatures analyze(String source, Map<String, String> headers) {
        Automix.Mono main = reader.read(source, headers, false, WHOLE_SECS);
        int rate = Math.max(main.rate, 1);
        if (main.pcm.length < Analysis.FRAME * 2) {
            // 连两帧 FFT 都凑不齐（约 93 ms）：这不是「没有节拍」，是「没有素材」。
            // 两部合流成一条：往下走每一步都会拿到 null / 0，报出来的却是一个
            // 「特征全都是 0」的正常结果，调用方根本分不出它和一首真静音的歌。
            throw AudioException.unsupportedFormat(
                    "素材太短，不足以分析（" + main.pcm.length + " 个采样）");
        }

        // 开头窗是整段窗的前缀（见类注释）。
        int headSamples = (int) Math.min((long) main.pcm.length, (long) ((double) rate * EDGE_SECS));
        float[] head = Arrays.copyOfRange(main.pcm, 0, headSamples);

        Analysis.Tempo wholeTempo = Analysis.analyze(main.pcm, rate);
        Analysis.Tempo headTempo = Analysis.analyze(head, rate);

        // 结尾窗：单独一次读。读不到（时长未知、seek 失败）<b>不阻断这一首</b>——
        // 开头的特征仍然成立，只是「它交出去的时候什么样」我们不知道。
        // 那种情况下 tailTempo = 0（测不出速度）、tailLevel = NaN（这段不存在），
        // 让衔接代价如实把「出去的这一侧」记成未知，而不是编一个响度出来。
        float tailTempoBpm = 0.0f;
        float tailLevel = Float.NaN;
        try {
            Automix.Mono tail = reader.read(source, headers, true, EDGE_SECS);
            if (tail.pcm.length >= Analysis.FRAME * 2) {
                Analysis.Tempo t = Analysis.analyze(tail.pcm, Math.max(tail.rate, 1));
                tailTempoBpm = t == null ? 0.0f : t.bpm;
                tailLevel = rms(tail.pcm);
            }
        } catch (AudioException ignored) {
            // 尾部读不到：上面两个「不知道」就是诚实的答案，不需要额外记一笔
            // （这一首本身仍然是「分析出来了」的，不该被算进失败名单）。
        }

        double duration = main.durationSecs == null
                ? (double) main.pcm.length / (double) rate
                : main.durationSecs.doubleValue();

        return new FlowFeatures(
                wholeTempo == null ? 0.0f : wholeTempo.bpm,
                headTempo == null ? 0.0f : headTempo.bpm,
                tailTempoBpm,
                rms(main.pcm),
                rms(head),
                tailLevel,
                toneOf(main.pcm, rate),
                duration);
    }

    /**
     * 均方根响度。
     *
     * <p>与 {@link Harmonic} 里那个帧级 {@code rms} 不是一回事：那个只用于「这一帧轻到
     * 不值得采信」的阈值判定（帧长 2048），这里要的是<strong>一整段</strong>的响度。
     * 名字一样、粒度不同，所以各写一份，不互相调用。
     */
    static float rms(float[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return 0.0f;
        }
        double sum = 0.0;
        for (int i = 0; i < pcm.length; i++) {
            double x = (double) pcm[i];
            sum += x * x;
        }
        return (float) Math.sqrt(sum / (double) pcm.length);
    }

    /**
     * 频谱质心（Hz）：幅度谱的重心。
     *
     * <p>分帧、加 Hann 窗、取幅度谱（{@link Fft#magnitude}），把
     * {@code Σ f·|X(f)| / Σ |X(f)|} 在各帧上求平均。帧移见 {@link #TONE_HOP}。
     *
     * <p>素材太短给 {@code 0}：{@code 0 Hz} 在 {@link FlowPlanner#fitScores} 里
     * 拿 0 分——「不知道」而不是「很暗」，理由是亮度权重只有 0.2，
     * 而把它当成「极暗」会稳定地把这类曲目推向曲线低点。给 0 的影响更小、
     * 更不容易被误读成一个结论。
     */
    static float toneOf(float[] pcm, int rate) {
        if (pcm == null || pcm.length < TONE_FRAME || rate <= 0) {
            return 0.0f;
        }
        Fft fft = new Fft(TONE_FRAME);
        int bins = TONE_FRAME / 2 + 1;
        float[] window = new float[TONE_FRAME];
        Fft.hann(window);
        float[] re = new float[TONE_FRAME];
        float[] im = new float[TONE_FRAME];
        float[] mag = new float[bins];

        double weighted = 0.0;
        double total = 0.0;
        int frames = 0;
        for (int start = 0; start + TONE_FRAME <= pcm.length; start += TONE_HOP) {
            for (int i = 0; i < TONE_FRAME; i++) {
                re[i] = pcm[start + i] * window[i];
                im[i] = 0.0f;
            }
            fft.forward(re, im);
            fft.magnitude(re, im, mag);
            double frameWeighted = 0.0;
            double frameTotal = 0.0;
            for (int k = 0; k < bins; k++) {
                double m = (double) mag[k];
                frameWeighted += ((double) k * (double) rate / (double) TONE_FRAME) * m;
                frameTotal += m;
            }
            // 整帧无能量（纯静音的那一段）不参与平均：算进去只会把质心往 0 Hz 拉，
            // 而「安静」不该被读成「音色很暗」。
            if (frameTotal > 0.0) {
                weighted += frameWeighted;
                total += frameTotal;
                frames++;
            }
        }
        if (frames == 0 || total <= 0.0) {
            return 0.0f;
        }
        return (float) (weighted / total);
    }
}
