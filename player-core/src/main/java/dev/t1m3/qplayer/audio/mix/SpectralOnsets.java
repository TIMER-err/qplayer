package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.dsp.Fft;

/**
 * <b>起始（起音）包络的专业实现</b>：对数频率谱通量 + 谱白化 + 最大值滤波。
 *
 * <h3>为什么要换掉「逐格 RMS 正向差分」</h3>
 *
 * <p>原实现（Melodify 那套，见 {@link Analysis#tempoFromRmsFlux}）把信号压成每跳一个 RMS，
 * 再取正向一阶差分。它对<strong>稳态成分毫无免疫力</strong>，2026-10-01 实测到的四类毛病
 * 全部由此而来：
 *
 * <ul>
 *   <li><b>驻波素材报假速度</b>：恒幅正弦/乐音在逐格 RMS 上会留下「正弦相位相对跳的拍频
 *       抖动」，那是<strong>真周期</strong>——归一化自相关能到 0.82（真歌只有 0.21），
 *       任何基于该包络的统计量都分不开；</li>
 *   <li><b>跳一细就崩</b>：跳 512（10.7 ms）对，跳 256/128（5.3/2.7 ms）时 skydive 报
 *       100.5 / 82.8 BPM，<b>速度本身就错</b>——差分抓的是格内抖动；</li>
 *   <li><b>真歌的包络太稠密</b>：宽频底噪、混响尾巴全进包络，skydive 整曲的「栅格落点
 *       增益」只有 0.62σ，栅格挑不出峰；</li>
 *   <li>同一首歌不同片段读数不一致（skydive 头 127.79 / 尾 128.05，差 0.2%）。</li>
 * </ul>
 *
 * <p>频域里这些问题都有一个统一的对策：<b>逐带自适应白化</b>——稳态成分在每个频带上
 * 都是常数，除以该带自己的历史峰值之后自动归零；真正的起音会超出历史峰值，被留下。
 *
 * <h3>四步（每一步都能单独说清为什么）</h3>
 *
 * <ol>
 *   <li><b>STFT</b>：帧 {@value #FRAME} / 跳 {@value #HOP} / 周期 Hann（与
 *       {@code Harmonic}、{@code Analysis} 同一约定，@48 kHz 即 42.7 ms 窗、10.7 ms 跳）。
 *       跳足够细才有 20 ms 级的相位精度；</li>
 *   <li><b>对数频率分带</b>：每八度 {@value #BANDS_PER_OCTAVE} 带（≈ 半个半音），
 *       三角权重。线性频率分带在低频太粗（底鼓与贝斯分不开）、高频太细（同一个镲占几十个
 *       bin，通量被摊薄）；对数分带让「每个八度里的分辨率一致」；</li>
 *   <li><b>对数压缩 + 最大值滤波（SuperFlux，Böck 2013）</b>：{@code log(1 + γ·v)} 把
 *       动态范围压下来，再在频率方向取 ±{@value #MAXFILTER_BANDS} 带的最大值——
 *       颤音/揉弦会让能量在相邻带之间来回窜，取最大值之后这种「搬家」不再产生通量，
 *       只有真正新增的能量才算起音；</li>
 *   <li><b>逐带自适应白化 + 通量（Stark &amp; Plumbley 2008）</b>：每带维护一个
 *       缓慢衰减的历史峰值 {@code mu}，白化值 {@code w = s / max(mu, α)}，
 *       通量 {@code max(0, w[t] − w[t−1])}。稳态 → {@code w} 恒定 → 通量 0；
 *       起音 → {@code w} 冲高 → 通量正。最后各带相加成一条包络。</li>
 * </ol>
 *
 * <h3>相位归属</h3>
 *
 * <p>第 {@code t} 帧与第 {@code t−1} 帧的窗只差<strong>最后 {@value #HOP} 个采样</strong>
 * （窗 {@code [tH, tH+F)} 对 {@code [(t−1)H, (t−1)H+F)}），所以这一帧的通量测的是
 * 「落在该帧尾部那 {@value #HOP} 个采样里」的新能量，其中点在
 * {@code tH + F − H/2} —— 换算成跳就是 {@code t + (F − H/2)/H} = <b>t + {@value #ATTRIBUTION_BINS}</b>。
 * 这个常数错一点，整条栅格就整体偏一段（20 ms 就够听出来），所以它是算出来的、不是试出来的。
 *
 * <h3>性能</h3>
 *
 * <p>每帧一次 {@value #FRAME} 点 FFT（约 2×10⁴ 次浮点运算）。48 kHz 下 220 秒的曲子约
 * 2×10⁴ 帧、单线程 ~0.4 s；自动混音的准备阶段只读首尾 12 s（约 1.1×10³ 帧、~25 ms），
 * 整曲扫描那条路才是大头（与现有 {@code AutoCue.scan} 的 1.1 s 同量级）。
 * 需要时按 {@code Harmonic} 那套按帧并行即可（这里先保持单线程、可复现）。
 */
public final class SpectralOnsets {

    /** 帧长（采样）。与 {@link Analysis#FRAME}、{@code Harmonic.FRAME_LEN} 同值。 */
    public static final int FRAME = 2048;

    /** 帧移（采样）。@48 kHz 即 10.67 ms。 */
    public static final int HOP = 512;

    /** 包络的时间归属修正（帧）：见类注释的推导，{@code (FRAME − HOP/2) / HOP}。 */
    public static final float ATTRIBUTION_BINS = (FRAME - HOP / 2.0f) / HOP;

    /** 色度的音高类数（12 平均律）。 */
    public static final int PITCH_CLASSES = 12;

    /** 色度只取这个频率区间（Hz）：太低是隆隆声、太高是镲的嘶声，都不带和声信息。 */
    static final float CHROMA_MIN_HZ = 55.0f;
    static final float CHROMA_MAX_HZ = 2000.0f;

    /** 对数频率分带的密度（每八度带数）。24 = 半个半音。 */
    static final int BANDS_PER_OCTAVE = 24;

    /** 分带的频率下界（Hz）。30 Hz 以下没有可听的拍信息，还会被直流/隆隆声污染。 */
    static final float F_MIN = 30.0f;

    /** 分带的上界（相对 Nyquist 的比例）。再往上基本只有镲的嘶声与编码噪声。 */
    static final float F_MAX_NYQUIST_RATIO = 0.45f;

    /** 对数压缩的 γ：{@code log(1 + γ·v)}。选它的依据是「本仓库的幅度量级」——
     *  音乐帧幅度约 10⁻⁴~10⁻¹，γ=1000 让这段跨度落到 {@code log1p} 的 0.01~4.6，
     *  既有压缩又不过饱和。绝对尺度不影响结果：白化那一步已经把它约掉了。 */
    static final float GAMMA = 1000.0f;

    /** 最大值滤波的半径（带）。1 带 = 1/24 八度 ≈ 6%。 */
    static final int MAXFILTER_BANDS = 1;

    /** 历史峰值的半衰期（秒）：0.4 s 足够跟随段落变化，又不会把「每拍都在响的底鼓」抹平。 */
    static final double PEAK_HALF_LIFE_SECS = 0.4;

    /** 白化的分母下限（对数域）。低于它的带视为「一直很安静」，不放大它的噪声。 */
    static final double WHITEN_FLOOR = 0.08;

    /**
     * 频带权重：<b>全 1（不加权）</b>。
     *
     * <p>2026-10-01 试过「200 Hz 以下全权、以上按 200/f 衰减」（想把「拍由底鼓定义」
     * 写进前端），<b>实测更差</b>：白化之后各带已经可比，再压低高频等于把军鼓/踩镲的
     * 起音也压掉，skydive 的基准读数从 128.0 掉到 <b>170.4</b>（3:4 谐波），
     * 变速自洽的表整体变差。撤掉。
     *
     * <p>这条记录很重要：<b>新前端在真歌上的八度问题不是「哪个频带没加权」造成的</b>，
     * 而是「用自相关峰值选周期」这件事本身——1.5 拍、0.75 拍这些谐波周期同样规整，
     * 峰值法分不出来。解法在后面的对拍那一步（DP 沿路最大化起音强度，漏掉 1/3 起音的
     * 那条路径分数天然更低），不是在频带上调权重。
     */
    static final double FLAT_BAND_WEIGHT = 1.0;

    private SpectralOnsets() {
    }

    /**
     * 算出一条<b>白化对数频率谱通量</b>包络（已去均值，可直接喂
     * {@link Analysis#tempoFromOnsetEnvelope}）。
     *
     * @param pcm  单声道 PCM
     * @param rate 采样率
     * @return 每跳一个值的起音包络；样本太短（不足 2 帧）时返回 {@code null}
     */
    public static float[] envelope(float[] pcm, int rate) {
        if (pcm == null || rate <= 0 || pcm.length < FRAME) {
            return null;
        }
        int frames = 1 + (pcm.length - FRAME) / HOP;
        if (frames < 2) {
            return null;
        }
        BandMap map = BandMap.of(rate, FRAME);
        if (map == null || map.count == 0) {
            return null;
        }
        int bands = map.count;
        Fft fft = new Fft(FRAME);
        float[] window = new float[FRAME];
        Fft.hann(window);
        float[] curMax = new float[bands];     // 最大值滤波之后
        float[] prevWhite = new float[bands];
        float[] white = new float[bands];
        float[] mu = new float[bands];
        float[] onset = new float[frames];
        double decay = Math.pow(0.5, (double) HOP / (PEAK_HALF_LIFE_SECS * rate));
        boolean first = true;

        // ---- 第 1 段：逐帧的频谱部分**并行**（2026-10-03，性能）------------------
        //
        // 与流式版（{@link Stream#processBatch}）同一条理由：窗→FFT→幅度谱→分带→log1p
        // →带内最大值滤波**只取决于这一帧自己**（`maxFilter` 是在频带之间做的），
        // 所以可以分线程算，每个数还是同一条算术；跨帧递推留在第 2 段严格按帧序做。
        int threads = Math.max(1, Math.min(ONSET_THREADS_MAX,
                Runtime.getRuntime().availableProcessors() - 1));
        // **一批一批地做**（2026-10-03）：第 1 段并行算一批帧的带值，第 2 段把这一批按帧序
        // 折掉，缓冲复用。原来是先把**整段**每帧的带值都存下来（12 秒素材上是 1034 帧 ×
        // 201 个带 × 4 字节 ≈ 830 KB，一次过渡两侧就是 1.7 MB 白占着），而且每帧再分配一个
        // 小数组；现在常驻的只有一批。
        final int batch = 32;
        float[][] batchCurMax = new float[batch][];
        for (int from = 0; from < frames; from += batch) {
            final int batchFrom = from;
            final int batchTo = Math.min(frames, from + batch);
            for (int i = 0; i < batchCurMax.length; i++) {
                if (i >= batchTo - batchFrom) {
                    break;
                }
                if (batchCurMax[i] == null) {
                    batchCurMax[i] = new float[bands];
                }
            }
            int count = batchTo - batchFrom;
            if (threads > 1 && count >= ONSET_MIN_FRAMES_TO_SPLIT) {
                int per = (count + threads - 1) / threads;
                java.util.List<java.util.concurrent.Future<?>> tasks =
                        new java.util.ArrayList<java.util.concurrent.Future<?>>(threads);
                for (int t = 0; t < threads; t++) {
                    final int partFrom = batchFrom + t * per;
                    final int partTo = Math.min(batchTo, partFrom + per);
                    if (partFrom >= partTo) {
                        break;
                    }
                    tasks.add(ONSET_POOL.submit(new Runnable() {
                        @Override
                        public void run() {
                            for (int f = partFrom; f < partTo; f++) {
                                frameBandsInto(pcm, f, fft, window, map, bands,
                                        batchCurMax[f - batchFrom]);
                            }
                        }
                    }));
                }
                for (java.util.concurrent.Future<?> task : tasks) {
                    try {
                        task.get();
                    } catch (InterruptedException e) {
                        // **不许把半成品当成品**（2026-10-04 审计发现）：这里原来只是
                        // 恢复中断标志就继续往下走，于是没跑到的分块在输出里全是 0，
                        // 而调用方拿到的是一个"看起来算完了"的包络/图。
                        // 任务自己抛异常会往上冒（下面那一支），被中断却静默——
                        // 两条路的待遇必须一致。调用方已经写好了如实的回退
                        // （AiMix / AutoCueTransition 都会退回固定点位并把原因写进 note）。
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "起音分析被中断，拒绝交出半张包络");
                    } catch (java.util.concurrent.ExecutionException e) {
                        throw new IllegalStateException(e.getCause());
                    }
                    }
            } else {
                for (int f = batchFrom; f < batchTo; f++) {
                    frameBandsInto(pcm, f, fft, window, map, bands,
                            batchCurMax[f - batchFrom]);
                }
            }
            // ---- 第 2 段：跨帧递推**严格按帧序**（一批做完再下一批，顺序不变）----
            for (int f = batchFrom; f < batchTo; f++) {
            curMax = batchCurMax[f - batchFrom];
            if (!first) {
                float acc = 0.0f;
                for (int b = 0; b < bands; b++) {
                    // 白化用**上一帧为止**的历史峰值：本帧的起音不参与压低自己。
                    double denom = Math.max(mu[b], WHITEN_FLOOR);
                    white[b] = (float) (curMax[b] / denom);
                    double flux = white[b] - prevWhite[b];
                    if (flux > 0.0) {
                        acc += flux;
                    }
                    mu[b] = (float) Math.max(mu[b] * decay, curMax[b]);
                }
                onset[f] = acc;
            } else {
                for (int b = 0; b < bands; b++) {
                    white[b] = 1.0f;
                    mu[b] = Math.max(mu[b], curMax[b]);
                }
                first = false;
            }
            System.arraycopy(white, 0, prevWhite, 0, bands);
            }
        }
        if (frames < 3) {
            return null;
        }
        // 去均值：后端的自相关量的是周期性，不是直流分量（与 RMS 那条路一致）。
        double total = 0.0;
        for (float v : onset) {
            total += v;
        }
        float mean = (float) (total / frames);
        for (int i = 0; i < frames; i++) {
            onset[i] -= mean;
        }
        return onset;
    }

    /**
     * <b>流式版本</b>：一次推一个采样，边推边算，最后 {@link #values()} 取包络。
     *
     * <p>为什么需要它：整曲扫描（{@link AutoCue#scan}）的硬约定是<strong>常数内存</strong>
     * ——一首 4 分钟的歌按 48 kHz 存下来是 45 MB，不能为了算拍点把整段 PCM 留在内存里。
     * 整段版 {@link #envelope} 要整段 PCM，所以扫描那条路用这个类。
     *
     * <p>帧长/跳按<strong>时间</strong>换算（{@link #FRAME}/{@link #HOP} 是 48 kHz 下的参考值），
     * 所以扫描把素材降采样到工作采样率之后，这里的 42.7 ms／10.7 ms 仍然成立。
     * 判据 {@code SpectralOnsetsTest} 钉着「流式与整段逐点一致」。
     */
    public static final class Stream {

        private final BandMap map;
        private final Fft fft;
        private final float[] window;
        private final float[] re;
        private final float[] im;
        private final float[] mag;
        private final float[] cur;
        private final float[] curMax;
        private final float[] prevWhite;
        private final float[] white;
        private final float[] mu;
        private final float[] ring;
        /** 环形缓冲的写指针——也就是**最老样本**的位置（每推一个采样就前进一格）。 */
        private int ringAt;
        private final int frame;
        private final int hop;
        private final double decay;
        /**
         * 每帧的起始包络值。
         *
         * <p>用可增长的 {@code float[]} 而不是 {@code ArrayList<Float>}（2026-10-03，性能）：
         * 整曲扫描是每帧一个元素，一首 5 分钟的歌上就是一万多个装箱对象；值本身没变。
         */
        private float[] valuesBuf = new float[4096];
        private int valueCount;
        /** 一批多少帧（32 帧 ≈ 65 ms 音频；批太小并行没意义，太大白占内存）。 */
        private static final int BATCH_FRAMES = 32;
        /** 待处理批次：每帧的采样（不含窗）、每帧的带内最大值、每帧的色度与能量。 */
        private final float[][] batchSamples = new float[BATCH_FRAMES][];
        private final float[][] batchCurMax = new float[BATCH_FRAMES][];
        private final float[] batchChroma = new float[BATCH_FRAMES * PITCH_CLASSES];
        private final float[] batchLevel = new float[BATCH_FRAMES];
        private int batchCount;
        /** 每个 bin 属于哪个音高类；-1 = 不参与（频率在区间外）。构造里按 {@code mag} 的长度建。 */
        private final int[] binClass;
        /** 每帧的色度（12 个半音类，已按总和归一），扁平追加。 */
        private float[] chroma = new float[1024 * PITCH_CLASSES];
        private int chromaFrames;
        /** 每帧的谱能量（幅度和）：给分段用，判「边界右边是不是掉进衰减/静音」。 */
        private float[] level = new float[1024];
        private long n;
        private boolean first = true;

        /** @param rate 送进来的采样率（扫描那条路送的是工作采样率） */
        public Stream(int rate) {
            int r = Math.max(1, rate);
            // 帧长按**时间**折算（FRAME 是 48 kHz 下的参考值），再就近取 2 的幂——
            // Fft 只支持 2 的幂（构造时会拒），而 44.1 kHz 上折算出来是 1882。
            // 44.1 kHz → 2048（46.4 ms，与 48 kHz 的 42.7 ms 同一量级）、24 kHz → 1024。
            // 跳保持 4:1 重叠（hop = frame/4），与整段版一致。
            double want = (double) FRAME * r / 48000.0;
            int levels = (int) Math.round(Math.log(Math.max(2.0, want)) / Math.log(2.0));
            this.frame = Math.max(256, 1 << levels);
            this.hop = Math.max(1, frame / (FRAME / HOP));
            this.map = BandMap.of(r, frame);
            if (map == null) {
                throw new IllegalArgumentException("采样率 " + r + " 上分不出对数频带");
            }
            this.fft = new Fft(frame);
            this.window = new float[frame];
            Fft.hann(window);
            this.re = new float[frame];
            this.im = new float[frame];
            this.mag = new float[frame / 2 + 1];
            this.cur = new float[map.count];
            this.curMax = new float[map.count];
            this.prevWhite = new float[map.count];
            this.white = new float[map.count];
            this.mu = new float[map.count];
            this.ring = new float[frame];
            this.decay = Math.pow(0.5, (double) hop / (PEAK_HALF_LIFE_SECS * r));
            // 色度的 bin → 音高类映射：按频率算，只做一次。
            this.binClass = new int[mag.length];
            for (int k = 1; k < binClass.length; k++) {
                double hz = (double) k * r / frame;
                if (hz < CHROMA_MIN_HZ || hz > CHROMA_MAX_HZ) {
                    binClass[k] = -1;
                    continue;
                }
                double semis = 12.0 * Math.log(hz / 440.0) / Math.log(2.0);
                int pc = (int) Math.round(semis) % PITCH_CLASSES;
                binClass[k] = pc < 0 ? pc + PITCH_CLASSES : pc;
            }
        }

        /**
         * 推一个采样。
         *
         * <p>用<b>环形缓冲</b>：原来是「每来一个采样就把整窗往前挪一格」（`arraycopy` 1023 个
         * 元素）——190 秒素材上那是约 <b>47 亿次</b>元素搬移，实测这一段占前端 595 ms 的大头。
         * 现在只在**出帧时**按时间顺序拷一次（每帧 1024 个元素，共约 1800 万次）。
         */
        public void push(float sample) {
            ring[ringAt] = sample;
            ringAt++;
            if (ringAt == frame) {
                ringAt = 0;
            }
            n++;
            if (n >= frame && (n - frame) % hop == 0) {
                emit();
            }
        }

        private void emit() {
            // 环形缓冲里最老样本在 ringAt：先把这一帧**按时间顺序**取出来排进批次。
            // 真正的计算（窗、FFT、分带、色度）与折成（白化/通量/衰减）在
            // processBatch 里分成两段——见那里的说明。
            int slot = batchCount;
            float[] samples = batchSamples[slot];
            if (samples == null) {
                samples = new float[frame];
                batchSamples[slot] = samples;
            }
            int oldest = ringAt;
            int tail = frame - oldest;
            System.arraycopy(ring, oldest, samples, 0, tail);
            System.arraycopy(ring, 0, samples, tail, oldest);
            batchCount++;
            if (batchCount == BATCH_FRAMES) {
                processBatch();
            }
        }

        /**
         * <b>一批帧分两段做</b>（2026-10-03，性能）：
         *
         * <ol>
         *   <li><b>逐帧的频谱部分并行</b>——窗、FFT、幅度谱、分带、log1p、带内最大值滤波。
         *       这一段**只取决于这一帧自己**（{@code maxFilter} 是在带之间做的，不跨帧），
         *       所以分给几条线程算，结果与串行逐帧算<b>逐位相同</b>。</li>
         *   <li><b>折成串行**严格按帧序**</b>——白化均值 {@code mu}、上一帧的 {@code white}、
         *       衰减与包络取值都是跨帧递推，浮点加法不满足结合律，改顺序就等于改结果。</li>
         * </ol>
         *
         * <p>为什么按「批」而不是整段：整段要先把整首歌的采样留在内存里（190 秒 ≈ 33 MB），
         * 而这个类的立身之本就是流式（边解边算，内存只留一个环）。批次 32 帧 ≈ 2.6 MB 里的一小截。
         */
        private void processBatch() {
            int n = batchCount;
            if (n == 0) {
                return;
            }
            int threads = Math.max(1, Math.min(ONSET_THREADS_MAX,
                    Runtime.getRuntime().availableProcessors() - 1));
            if (threads > 1 && n >= ONSET_MIN_FRAMES_TO_SPLIT) {
                int per = (n + threads - 1) / threads;
                java.util.List<java.util.concurrent.Future<?>> tasks =
                        new java.util.ArrayList<java.util.concurrent.Future<?>>(threads);
                for (int t = 0; t < threads; t++) {
                    final int from = t * per;
                    final int to = Math.min(n, from + per);
                    if (from >= to) {
                        break;
                    }
                    tasks.add(ONSET_POOL.submit(new Runnable() {
                        @Override
                        public void run() {
                            for (int i = from; i < to; i++) {
                                analyzeFrame(i);
                            }
                        }
                    }));
                }
                for (java.util.concurrent.Future<?> task : tasks) {
                    try {
                        task.get();
                    } catch (InterruptedException e) {
                        // **不许把半成品当成品**（2026-10-04 审计发现）：这里原来只是
                        // 恢复中断标志就继续往下走，于是没跑到的分块在输出里全是 0，
                        // 而调用方拿到的是一个"看起来算完了"的包络/图。
                        // 任务自己抛异常会往上冒（下面那一支），被中断却静默——
                        // 两条路的待遇必须一致。调用方已经写好了如实的回退
                        // （AiMix / AutoCueTransition 都会退回固定点位并把原因写进 note）。
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "起音分析被中断，拒绝交出半张包络");
                    } catch (java.util.concurrent.ExecutionException e) {
                        throw new IllegalStateException(e.getCause());
                    }
                    }
            } else {
                for (int i = 0; i < n; i++) {
                    analyzeFrame(i);
                }
            }
            for (int i = 0; i < n; i++) {
                fold(i);
            }
            batchCount = 0;
        }

        /** 第 {@code i} 帧的频谱部分（与串行版逐行等价，只是不再写在实例状态上）。 */
        private void analyzeFrame(int i) {
            FrameScratch sc = FRAME_SCRATCH.get();
            sc.ensure(frame, map.count, mag.length);
            float[] samples = batchSamples[i];
            float[] re = sc.re;
            float[] im = sc.im;
            float[] mag = sc.mag;
            for (int k = 0; k < frame; k++) {
                re[k] = samples[k] * window[k];
                im[k] = 0.0f;
            }
            fft.forward(re, im);
            fft.magnitude(re, im, mag);
            // 色度与谱能量（原来在 tapChroma 里做，那段现在只剩「按帧序追加」）
            int base = i * PITCH_CLASSES;
            for (int c = 0; c < PITCH_CLASSES; c++) {
                batchChroma[base + c] = 0.0f;
            }
            double sum = 0.0;
            for (int k = 1; k < binClass.length; k++) {
                int pc = binClass[k];
                if (pc < 0) {
                    continue;
                }
                batchChroma[base + pc] += mag[k];
                sum += mag[k];
            }
            if (sum > 1e-9) {
                float inv = (float) (1.0 / sum);
                for (int c = 0; c < PITCH_CLASSES; c++) {
                    batchChroma[base + c] *= inv;
                }
            }
            batchLevel[i] = (float) sum;
            map.apply(mag, sc.cur);
            for (int b = 0; b < map.count; b++) {
                sc.cur[b] = (float) Math.log1p(GAMMA * sc.cur[b]);
            }
            maxFilter(sc.cur, sc.curMax);
            float[] slotMax = batchCurMax[i];
            if (slotMax == null) {
                slotMax = new float[map.count];
                batchCurMax[i] = slotMax;
            }
            System.arraycopy(sc.curMax, 0, slotMax, 0, map.count);
        }

        /** 第 {@code i} 帧的折成部分：**严格按帧序**调用，跨帧递推都在这里。 */
        private void fold(int i) {
            float[] curMax = batchCurMax[i];
            if (!first) {
                float acc = 0.0f;
                for (int b = 0; b < map.count; b++) {
                    double denom = Math.max(mu[b], WHITEN_FLOOR);
                    white[b] = (float) (curMax[b] / denom);
                    double flux = white[b] - prevWhite[b];
                    if (flux > 0.0) {
                        acc += flux;
                    }
                    mu[b] = (float) Math.max(mu[b] * decay, curMax[b]);
                }
                appendValue(acc);
            } else {
                for (int b = 0; b < map.count; b++) {
                    white[b] = 1.0f;
                    mu[b] = Math.max(mu[b], curMax[b]);
                }
                first = false;
                appendValue(0.0f);
            }
            System.arraycopy(white, 0, prevWhite, 0, map.count);
            // 色度/谱能量按帧序追加（数值与原来逐位相同，只是搬到了这一侧）
            if (chromaFrames * PITCH_CLASSES + PITCH_CLASSES > chroma.length) {
                float[] grown = new float[chroma.length * 2];
                System.arraycopy(chroma, 0, grown, 0, chroma.length);
                chroma = grown;
            }
            if (chromaFrames + 1 > level.length) {
                float[] grown = new float[level.length * 2];
                System.arraycopy(level, 0, grown, 0, level.length);
                level = grown;
            }
            System.arraycopy(batchChroma, i * PITCH_CLASSES, chroma,
                    chromaFrames * PITCH_CLASSES, PITCH_CLASSES);
            level[chromaFrames] = batchLevel[i];
            chromaFrames++;
        }

        /**
         * 把还没处理的帧折完（{@link #values()} / {@link #chromaValues()} /
         * {@link #levelValues()} / {@link #chromaFrameCount()} 都会先调它）。
         *
         * <p><b>调用之后这条流仍然可以继续 push</b>（2026-10-03，评审要求写清）：
         * flush 只影响「已经在批次里、还没折」的那些帧，不重置任何跨帧状态
         * （{@code mu} / {@code prevWhite} / {@code first} / 帧计数都不动），
         * 所以「取一次 → 继续喂 → 再取」与「一直喂到底再取」得到的序列完全一样。
         * 多次调用是幂等的（没有待折帧时是空操作）。
         */
        public void flush() {
            if (batchCount > 0) {
                processBatch();
            }
        }

        /** 已经抽出的色度帧数（与 {@link #values()} 的帧数一致）。 */
        public int chromaFrameCount() {
            flush();
            return chromaFrames;
        }

        /** 每帧的谱能量（与 {@link #chromaValues()} 同一条时间轴）。 */
        public float[] levelValues() {
            flush();
            float[] out = new float[chromaFrames];
            System.arraycopy(level, 0, out, 0, out.length);
            return out;
        }

        /** 取色度（每帧 12 个值，扁平存放；长度 = 帧数 × 12）。 */
        public float[] chromaValues() {
            flush();
            float[] out = new float[chromaFrames * PITCH_CLASSES];
            System.arraycopy(chroma, 0, out, 0, out.length);
            return out;
        }

        /** 这条流的跳（采样）。调用方要用它去调 {@code BeatTracker}。 */
        public int hop() {
            return hop;
        }

        /** 取包络（已去均值，与 {@link #envelope} 的口径一致）。推完最后一个采样再取。 */
        public float[] values() {
            flush();
            int size = valueCount;
            float[] out = new float[size];
            // 均值仍然用 double 累加（与原来 `double total` 那条路逐位相同——
            // 换成 float 累加会在长素材上给出不一样的均值，那是改结果，不是优化）
            double total = 0.0;
            for (int i = 0; i < size; i++) {
                out[i] = valuesBuf[i];
                total += out[i];
            }
            float mean = size == 0 ? 0f : (float) (total / size);
            for (int i = 0; i < size; i++) {
                out[i] -= mean;
            }
            return out;
        }

        /** 追加一个包络值（容量不够就翻倍）。 */
        private void appendValue(float v) {
            if (valueCount == valuesBuf.length) {
                valuesBuf = java.util.Arrays.copyOf(valuesBuf, valuesBuf.length * 2);
            }
            valuesBuf[valueCount++] = v;
        }
    }

    /** 频率方向的滑动最大值（半径 {@link #MAXFILTER_BANDS} 带）。 */
    /** 逐帧频谱部分的线程上限。 */
    private static final int ONSET_THREADS_MAX = 6;

    /** 一批里少于这么多帧就不切（起线程比算它还贵）。 */
    private static final int ONSET_MIN_FRAMES_TO_SPLIT = 4;

    /** 服务逐帧频谱部分的线程池（daemon、固定条数）。 */
    private static final java.util.concurrent.ExecutorService ONSET_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(
                    Math.max(1, Math.min(ONSET_THREADS_MAX,
                            Runtime.getRuntime().availableProcessors() - 1)),
                    new java.util.concurrent.ThreadFactory() {
                        private final java.util.concurrent.atomic.AtomicInteger n =
                                new java.util.concurrent.atomic.AtomicInteger();
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread th = new Thread(r, "qplayer-onset-" + n.getAndIncrement());
                            th.setDaemon(true);
                            return th;
                        }
                    });

    /**
     * 一帧的「对数带值 → 带内最大值滤波」结果（第 1 段，可并行）。
     *
     * <p>与串行版逐行等价：同样的窗、同样的 FFT、同样的分带与 log1p、同样的 maxFilter。
     * 每个线程用自己的 scratch（{@link FrameScratch}），互不干扰。
     */
    private static void frameBandsInto(float[] pcm, int f, Fft fft, float[] window,
                                       BandMap map, int bands, float[] out) {
        FrameScratch sc = FRAME_SCRATCH.get();
        sc.ensure(FRAME, bands, FRAME / 2 + 1);
        float[] re = sc.re;
        float[] im = sc.im;
        float[] mag = sc.mag;
        float[] cur = sc.cur;
        System.arraycopy(pcm, f * HOP, re, 0, FRAME);
        for (int i = 0; i < FRAME; i++) {
            re[i] *= window[i];
            im[i] = 0.0f;
        }
        fft.forward(re, im);
        fft.magnitude(re, im, mag);
        map.apply(mag, cur);
        for (int b = 0; b < bands; b++) {
            cur[b] = (float) Math.log1p(GAMMA * cur[b]);
        }
        maxFilter(cur, out);
    }

    /** 逐帧频谱部分的工作缓冲（每个线程一份，避免每次分配）。 */
    static final class FrameScratch {
        float[] re = new float[0];
        float[] im = new float[0];
        float[] mag = new float[0];
        float[] cur = new float[0];
        float[] curMax = new float[0];
        private int frame;
        private int bands;
        private int magLen;

        /**
         * 保证三块缓冲都够用。
         *
         * <p><b>三个维度都要比</b>：帧长与频带数并不是一一对应的——不同的采样率
         * （44.1k / 24k / 48k）算出的分带数不同，而帧长可能一样。只比帧长就会拿到
         * 一条<b>更长的</b>旧缓冲，`maxFilter` 按 {@code in.length} 走，于是写出界
         * （2026-10-03 被 {@code SpectralOnsetsTest} 当场抓住：它在 44.1k 上跑，
         * 而这条线程之前可能服务过别的采样率）。
         */
        void ensure(int frame, int bands, int magLen) {
            if (this.frame == frame && this.bands == bands && this.magLen == magLen) {
                return;
            }
            this.frame = frame;
            this.bands = bands;
            this.magLen = magLen;
            re = new float[frame];
            im = new float[frame];
            mag = new float[magLen];
            cur = new float[bands];
            curMax = new float[bands];
        }
    }

    private static final ThreadLocal<FrameScratch> FRAME_SCRATCH =
            new ThreadLocal<FrameScratch>() {
                @Override
                protected FrameScratch initialValue() {
                    return new FrameScratch();
                }
            };

    private static void maxFilter(float[] in, float[] out) {
        int n = in.length;
        for (int b = 0; b < n; b++) {
            float best = in[b];
            for (int d = 1; d <= MAXFILTER_BANDS; d++) {
                if (b - d >= 0 && in[b - d] > best) {
                    best = in[b - d];
                }
                if (b + d < n && in[b + d] > best) {
                    best = in[b + d];
                }
            }
            out[b] = best;
        }
    }

    /**
     * 对数频率分带映射（按采样率与帧长预计算，之后只读）。
     *
     * <p>带中心按几何级数排：{@code f_b = F_MIN · 2^(b / BANDS_PER_OCTAVE)}；每带用<b>三角</b>
     * 权重覆盖 {@code [f_{b−1}, f_{b+1}]}（即相邻带各覆盖一半），这样带与带之间没有缝，
     * 也不会因为某个 bin 落在两带边界上而丢掉能量。
     */
    static final class BandMap {

        final int count;

        /** 每带覆盖的 bin 区间与权重（扁平存储）。 */
        private final int[] binStart;
        private final int[] binEnd;
        private final float[] weights;

        private BandMap(int count, int[] binStart, int[] binEnd, float[] weights) {
            this.count = count;
            this.binStart = binStart;
            this.binEnd = binEnd;
            this.weights = weights;
        }

        static BandMap of(int rate, int frame) {
            if (rate <= 0 || frame <= 0) {
                return null;
            }
            double nyquist = rate / 2.0;
            double fMax = Math.min(nyquist * F_MAX_NYQUIST_RATIO, 0.98 * nyquist);
            double binHz = rate / (double) frame;
            int bands = 0;
            for (double f = F_MIN; f < fMax; f *= Math.pow(2.0, 1.0 / BANDS_PER_OCTAVE)) {
                bands++;
            }
            if (bands <= 0) {
                return null;
            }
            int[] start = new int[bands];
            int[] end = new int[bands];
            float[] weights = new float[bands];
            double ratio = Math.pow(2.0, 1.0 / BANDS_PER_OCTAVE);
            for (int b = 0; b < bands; b++) {
                double centre = F_MIN * Math.pow(ratio, b);
                double lower = centre / ratio;
                double upper = centre * ratio;
                int k0 = Math.max(1, (int) Math.ceil(lower / binHz));
                int k1 = Math.min(frame / 2, (int) Math.floor(upper / binHz));
                if (k1 < k0) {
                    k0 = k1 = Math.max(1, Math.min(frame / 2, (int) Math.round(centre / binHz)));
                }
                start[b] = k0;
                end[b] = k1;
                // 带内的权重和（三角权重在带边界处为 0，用 1 兜底免得整带权重为 0）
                double sum = 0.0;
                for (int k = k0; k <= k1; k++) {
                    sum += 1.0;
                }
                weights[b] = (float) (1.0 / Math.max(1.0, sum));
            }
            return new BandMap(bands, start, end, weights);
        }

        /** 把一帧幅度谱归到各带上（带内取平均，所以带值不受带宽影响）。 */
        void apply(float[] mag, float[] out) {
            for (int b = 0; b < count; b++) {
                double sum = 0.0;
                for (int k = binStart[b]; k <= binEnd[b]; k++) {
                    sum += mag[k];
                }
                out[b] = (float) (sum * weights[b]);
            }
        }
    }
}
