package dev.t1m3.qplayer.audio.mix;

/**
 * <b>对拍与精度收口</b>：Ellis 动态规划找拍 + 全局稳健回归拟合（专业 DJ 软件那条路子的后半段）。
 *
 * <h3>为什么自相关峰值法不够（2026-10-01 实测）</h3>
 *
 * <p>换上白化谱通量前端之后，真歌上冒出一类<strong>极度系统</strong>的错：skydive 变速到
 * 150 / 165 / 175 BPM 时被报成 100.00 / 110.01 / 116.65——精确等于真值的 <b>×2/3</b>；
 * 尾段 128 被报成 170.63（<b>×4/3</b>）。这两个比例就是「三个八分音符」（附点型）与
 * 「三个十六分音符」，电子乐里到处都是。
 *
 * <p>它们不是前端的锅，也不是「哪个频带没加权」：<b>×2/3 的周期同样规整</b>，
 * 自相关只问「多久重复一次」，不问「这次重复落在起音上吗」。1.5 拍、0.75 拍这些
 * 谐波周期在自相关上一样强，甚至更强（附点型往往比正拍更抢耳）。
 *
 * <p>解法是把「落脚点必须踩在起音上」写进目标函数——这正是 Ellis (2007) 的 DP：
 * 找一条路径，使得<strong>沿途落脚点的起音强度之和</strong>最大、同时相邻间隔尽量规整。
 * 「每 1.5 拍一个落脚点」的路径会漏掉 1/3 的起音，目标函数天然更低。
 *
 * <h3>四步</h3>
 *
 * <ol>
 *   <li><b>候选周期</b>：自相关的局部极大（按值排序取前几个），每个再配上有理变体
 *       {{@value #VARIANT_FAST_1}、{@value #VARIANT_FAST_2}、{@value #VARIANT_FAST_3}} 与
 *       {{@value #VARIANT_SLOW_1}、{@value #VARIANT_SLOW_2}、{@value #VARIANT_SLOW_3}}
 *       ——「×2/3」那类错就是靠 {1.5} 这个变体找回来的；</li>
 *   <li><b>DP 对拍</b>：{@code C[t] = onset[t] + max_τ (C[τ] + α·logGauss(t − τ))}，
 *       τ 落在 {@code [t − 1.5p, t − 0.5p]} 里（Ellis 的转移约束），回溯出整条路径；</li>
 *   <li><b>打分选周期</b>：{@code 落点平均起音 × 共振先验}（先验同
 *       {@code Analysis.PRIOR_CENTER_BPM}/{@code PRIOR_SIGMA}：120 BPM 处最高，对数域高斯）。
 *       「平均」这个字是关键：慢了会漏掉若干拍（平均掉下来），快了会有落脚点落空
 *       （平均也掉下来），两个方向一起被压住；先验则负责在「真拍的子集」与「真拍本身」
 *       这种同样干净的候选之间表态；</li>
 *   <li><b>精度收口</b>：DP 的落脚点先在 ±1/4 拍内做局部峰值细化（不改归属），再用
 *       <b>稳健最小二乘</b>（MAD 定 σ、迭代剔除 &gt;2.5σ 的外点）拟合
 *       {@code t_i = phase + i·period}。精度由整曲拍数决定（4 分钟 ≈ 512 拍），
 *       而 <b>残差的标准差（ms）就是置信度</b>——有物理量纲、与 hop 和素材长度无关，
 *       与模型路 {@code tempoFromBeats} 那把「拍点偏离栅格多少」的尺子在此合流。</li>
 * </ol>
 *
 * <h3>单位</h3>
 *
 * <p>返回的 {@link Analysis.Tempo} 里 {@code periodFrames}/{@code phaseFrames} 是
 * <b>采样</b>单位（与 {@code tempoFromOnsetEnvelope} 的既有约定一致：{@code AutoCue}
 * 拿它除以采样率得秒数——那个参数名叫 {@code beatRate} 是历史笔误）。
 */
public final class BeatTracker {

    /** 速度搜索范围（BPM），与 {@code Analysis.ENERGY_MIN_BPM/MAX_BPM} 保持一致。 */
    static final float MIN_BPM = 70.0f;
    static final float MAX_BPM = 180.0f;

    /** 转移项的标准差（以周期为单位）：偏离 1 个 σ 罚 α/2。 */
    static final double PREFERRED_DEVIATION = 0.12;

    /**
     * 转移项权重 α。包络先被归一化到 95 分位 = 1，所以这个数是尺度无关的：
     * 取 {@value} 时「偏 10%」的代价约等于一个中等起音的强度——既允许踩到更强的起音，
     * 又不至于让路径乱跳。
     */
    static final double TIGHTNESS = 5.0;

    /** 共振先验的中心与宽度（对数域），与 {@code Analysis.PRIOR_*} 同参便于对照。 */
    static final double RESONANCE_CENTER_BPM = 120.0;
    static final double RESONANCE_SIGMA = 0.5;

    /** 候选周期里配的有理变体（倍率）。1.5 这一档是「×2/3 谐波错」的解药。 */
    static final double VARIANT_FAST_1 = 0.5;
    static final double VARIANT_FAST_2 = 2.0 / 3.0;
    static final double VARIANT_FAST_3 = 0.75;
    static final double VARIANT_SLOW_1 = 4.0 / 3.0;
    static final double VARIANT_SLOW_2 = 1.5;
    static final double VARIANT_SLOW_3 = 2.0;

    /** 自相关取前几个局部极大当种子。 */
    static final int ACF_SEEDS = 4;

    /** 候选周期上限（每个种子最多 7 个变体，去重后）。 */
    static final int MAX_CANDIDATES = 24;

    /**
     * **试过、撤了**：收敛之后再对 (周期, 相位) 做一次一维精搜（±0.4% 周期 × 周期/16 相位，
     * 目标函数 = 落点处 ±p/8 内局部峰强度之和），想顺手修掉「短窗口锁在略偏周期上」。
     * 实测反而更差：合成素材从 128.00 变 128.26（+0.20%），skydive 三个 30 s 片段的离散度
     * 从 0.1682% 变 0.4125%，春めく 0.0226% → 0.4834%。原因是「峰强度之和」这个目标
     * 对 0.02% 量级的周期差分辨力不够（几百个落点的和几乎不变，噪声决定胜负）。
     * 短窗口那件事要另想办法（多窗联合拟合），不是加一层精搜能解决的。
     */
    /** 对拍-回归迭代的最大趟数。 */
    static final int MAX_PASSES = 5;

    /** 相邻两趟的周期变化小于这个比例就认为收敛（0.01%：比验收线 0.02% 还紧一倍）。 */
    static final double PERIOD_CONVERGED_FRACTION = 1.0e-4;

    /** 至少要这么多拍才敢拟合。 */
    static final int MIN_BEATS = 8;

    /**
     * 每个落脚点的「空落脚」代价（包络已归一化到 95 分位 = 1）。
     *
     * <p>取 {@value}：落脚点至少要踩到 0.25 个归一化起音强度才不亏。它让「把周期取成
     * 一半（两倍速）」那种候选出局——多出来的落脚点落在起音之间，本来是 0 分，现在记负分。
     */
    static final double EMPTY_COST = 0.25;

    /**
     * 局部细化时在左右各搜几格（包络格，48 kHz 跳 512 时一格 10.7 ms）。
     *
     * <p><b>必须很小</b>：DP 已经把「哪一拍落在哪」定好了，细化只负责把落点精确定位到
     * 峰顶（亚格点）。2026-10-01 实测把这里写成 ±{@code p/4}（慢速素材上 ±15 格 ≈ ±160 ms）
     * 的后果：细化会把落脚点**拉到相邻的另一个起音上**，回归出来的周期于是被拉向那个
     * 偏差方向——春めく 变速到 93.75 BPM 时报 95.11（+1.45%，恰好是「周期少一格」的量），
     * skydive 尾段同型。改回 ±{@value} 格之后这些偏差消失。
     */
    static final int REFINE_FRAMES = 3;

    /** 有效拍的门槛（相对归一化包络）：落点局部峰低于它就不算踩到拍。 */
    static final double VALID_BEAT_PEAK = 0.10;

    /** 有效拍占比低于它就不表态（栅格解释不了这么多拍）。 */
    static final double MIN_VALID_RATIO = 0.35;

    /**
     * 残差换算成置信度时的「归零点」：残差达到拍长的这个比例时置信度 0。
     *
     * <p><b>用比例而不是毫秒</b>，原因有二：
     *
     * <ol>
     *   <li><b>量纲正确</b>：栅格好不好用，取决于「拍点偏离了多少个拍」，而不是多少毫秒——
     *       同样 50 ms，在 175 BPM（一拍 343 ms）上是 15%（不能用），在 94 BPM（一拍 640 ms）
     *       上只有 8%（能用）。2026-10-01 就是被绝对门槛卡住的：春めく 三个慢速档的残差
     *       59.1 / 52.8 / 48.7 ms 全部超过 45 ms 的绝对线，于是「不表态」，
     *       而它们其实是 9.2% / 8.3% / 7.6% 拍——完全可用。</li>
     *   <li><b>与模型路合流</b>：{@code Analysis.tempoFromBeats} 那把尺子是
     *       {@code 1 − 拍点间隔的平均绝对偏差 / 周期}，同样是「偏差 ÷ 周期」。
     *       两条路用同一个形式的尺子，{@code AutoCue.MIN_BEAT_CONFIDENCE} 那个门槛
     *       才对两边都说得通（原先一个量毫秒、一个量比例，门槛只能迁就其中一边）。</li>
     * </ol>
     */
    static final double RESIDUAL_FULL_FRACTION = 0.25;

    /** 残差超过拍长的这个比例就不表态（栅格不可信）。 */
    static final double MAX_RESIDUAL_FRACTION = 0.10;

    /**
     * 上一次 {@link #track} 为什么没给出结论（有结论时是 {@code null}）。
     *
     * <p>用户 2026-10-01 抱怨过「日志里看不到卡在哪」。这条诊断就是为那句话加的：
     * 它带上「有效拍 / 总拍 / 门槛」「残差 ms」「周期范围」这类**具体数字**，
     * 让「这一首歌没测出 BPM」变成一个能直接读的原因，而不是一个沉默的 {@code null}。
     * 单线程分析流程里读它是安全的（分析在准备线程上串行进行）。
     */
    private static volatile String lastReject;

    /** 上一次没给出结论的原因；有结论时为 {@code null}。 */
    public static String lastRejectReason() {
        return lastReject;
    }

    private static Analysis.Tempo no(String why) {
        lastReject = why;
        return null;
    }

    private BeatTracker() {
    }

    /**
     * 在一条（已去均值的）起音包络上对拍并拟合出栅格。
     *
     * @param onset      起音包络（每 {@code hop} 个采样一格）
     * @param hop        包络的跳（采样）
     * @param sampleRate 源采样率
     * @return 节奏结论（周期/相位以采样为单位）；置信度不够或素材太短时 {@code null}
     */
    public static Analysis.Tempo track(float[] onset, int hop, int sampleRate) {
        if (onset == null || hop <= 0 || sampleRate <= 0 || onset.length < 32) {
            return no("起音包络太短或参数非法");
        }
        int n = onset.length;
        double envRate = (double) sampleRate / (double) hop;
        int minLag = Math.max(1, (int) Math.round(envRate * 60.0 / MAX_BPM));
        int maxLag = Math.min(n - 2, (int) Math.round(envRate * 60.0 / MIN_BPM));
        if (maxLag <= minLag + 1) {
            return no("素材太短，70~180 BPM 的延迟范围都放不下（" + n + " 格）");
        }
        double scale = percentile95(onset);
        if (scale <= 1e-12) {
            return no("起音包络全是零（这段素材没有起音）");
        }
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = (float) (onset[i] / scale);
        }

        double energy = 0.0;
        for (int i = 0; i < n; i++) {
            energy += (double) x[i] * x[i];
        }
        if (energy <= 1e-12) {
            return no("起音包络能量为零");
        }

        // ── ① 自相关 → 种子周期 ─────────────────────────────────────────
        double[] ac = new double[maxLag + 1];
        for (int lag = minLag; lag <= maxLag; lag++) {
            double sum = 0.0;
            for (int i = lag; i < n; i++) {
                sum += (double) x[i] * x[i - lag];
            }
            ac[lag] = sum / energy;
        }
        java.util.TreeMap<Double, Boolean> seeds = new java.util.TreeMap<Double, Boolean>();
        for (int pick = 0; pick < ACF_SEEDS; pick++) {
            int best = -1;
            for (int lag = minLag + 1; lag < maxLag; lag++) {
                if (ac[lag] >= ac[lag - 1] && ac[lag] >= ac[lag + 1] && ac[lag] > 0.0) {
                    boolean taken = false;
                    for (Double s : seeds.keySet()) {
                        if (Math.abs(s.doubleValue() - lag) < 2.0) {
                            taken = true;
                            break;
                        }
                    }
                    if (!taken && (best < 0 || ac[lag] > ac[best])) {
                        best = lag;
                    }
                }
            }
            if (best < 0) {
                break;
            }
            seeds.put(Double.valueOf(best), Boolean.TRUE);
        }
        if (seeds.isEmpty()) {
            return no("自相关在 70~180 BPM 里没有任何正的局部极大");
        }

        // ── ② 候选周期（种子 × 有理变体）─────────────────────────────────
        java.util.TreeSet<Double> candidates = new java.util.TreeSet<Double>();
        double[] variants = {1.0, VARIANT_FAST_1, VARIANT_FAST_2, VARIANT_FAST_3,
                VARIANT_SLOW_1, VARIANT_SLOW_2, VARIANT_SLOW_3};
        for (Double seed : seeds.keySet()) {
            for (double v : variants) {
                double p = seed.doubleValue() * v;
                if (p >= minLag - 0.5 && p <= maxLag + 0.5) {
                    candidates.add(Double.valueOf(p));
                }
            }
            if (candidates.size() >= MAX_CANDIDATES) {
                break;
            }
        }
        if (candidates.isEmpty()) {
            return no("由自相关种子推不出落在 70~180 BPM 里的候选周期");
        }

        // ── ③ 每个候选对拍 + 打分 ───────────────────────────────────────
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestPeriod = 0.0;
        int[] bestPath = null;
        for (Double cand : candidates) {
            int[] path = dp(x, cand.doubleValue(), minLag, maxLag);
            if (path == null || path.length < MIN_BEATS) {
                continue;
            }
            // 打分用**总和减去空落脚惩罚**，不用平均。
            //
            // 用平均是个陷阱（2026-10-01 实测踩到）：把周期取成真值的两倍时，落脚点恰好是
            // 真拍的**子集**，每一个都踩在强起音上——平均值反而比真周期更高，于是合成素材
            // 在高速端被判成半速（150 → 75.00、165 → 82.50、175 → 87.50，全是精确的一半）。
            // 总和则是「这条栅格解释了多少起音」，子集只有一半的项，天然吃亏；
            // 而取成一半周期（两倍速）时多出来的落脚点落在起音之间，贡献 0，
            // 再由 {@link #EMPTY_COST} 记成负分。方向两边都被压住。
            double sum = 0.0;
            for (int idx : path) {
                sum += Math.max(0.0, x[idx]);
            }
            double bpm = 60.0 * envRate / cand.doubleValue();
            double score = (sum - EMPTY_COST * path.length) * resonancePrior(bpm);
            if (score > bestScore) {
                bestScore = score;
                bestPeriod = cand.doubleValue();
                bestPath = path;
            }
        }
        if (bestPath == null) {
            return no("所有候选周期都没能对出 " + MIN_BEATS + " 拍以上的路径");
        }

        // ── ④ 细化 → 回归 → **用拟合出来的周期再对拍一次** ──────────────────
        //
        // 第二趟是必须的：候选周期来自自相关的**整数**格，慢速素材上真周期常常不是整数
        // （44.1 kHz、跳 512 时 93.75 BPM 的真周期是 55.13 格），最近的整数差 1.2%——
        // 第一趟的路径会沿这个偏移的栅格走，回归虽然能纠一点，但纠不干净。
        // 用第一趟拟合出来的（非整数）周期重跑对拍，路径就能一直踩在真起音上。
        // 实测（2026-10-01）：春めく 降速档 95.10 → 93.75（真值）。
        Fit fit = null;
        double trackPeriod = bestPeriod;
        // 迭代到收敛：候选周期是自相关的整数格，慢速素材上真周期常常不是整数
        // （44.1 kHz、跳 512 时 93.75 BPM 的真周期是 55.13 格），最近的整数差 1.2%，
        // 一趟纠不干净。每趟用上一趟拟合出来的（非整数）周期重新对拍，
        // 周期变化小于 {@link #PERIOD_CONVERGED_FRACTION} 就收工；最多 {@link #MAX_PASSES} 趟。
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            int[] path = pass == 0 ? bestPath : dp(x, trackPeriod, minLag, maxLag);
            if (path == null || path.length < MIN_BEATS) {
                if (pass == 0) {
                    return no("所有候选周期都没能对出 " + MIN_BEATS + " 拍以上的路径");
                }
                break;
            }
            int span = REFINE_FRAMES;
            double[] times = new double[path.length];
            double[] numbers = new double[path.length];
            for (int i = 0; i < path.length; i++) {
                times[i] = refinePeak(x, path[i], span);
                if (i == 0) {
                    numbers[i] = 0.0;
                } else {
                    // 拍号按**相邻间隔 ÷ 当前周期**累加，而不是数组下标：DP 路径会在弱拍处
                    // 断开重新起步（见 dp 里的守卫），断点两侧的数组下标不是连续拍号。
                    numbers[i] = numbers[i - 1] + Math.max(1.0,
                            Math.round((times[i] - times[i - 1]) / trackPeriod));
                }
            }
            Fit got = robustFit(numbers, times, envRate);
            if (got == null || got.count < MIN_BEATS) {
                if (pass == 0) {
                    return no("稳健回归没能拟合出栅格（有效拍不足）");
                }
                break;
            }
            double previous = trackPeriod;
            fit = got;
            trackPeriod = got.period;
            if (Math.abs(got.period - previous) / got.period < PERIOD_CONVERGED_FRACTION) {
                break;
            }
        }
        if (fit == null) {
            return no("两趟对拍都没能拟合出栅格");
        }

        // ── ⑤ 残差：栅格对**起音峰**的贴合（不是对 DP 路径的贴合）──────────────
        //
        // 周期用 DP 路径的回归结果：起音峰本身只有包络一格（48 kHz 跳 512 时 10.7 ms）
        // 的定位精度，拿它当归一化基准去**拟合周期**反而更差——试过「用起音峰再拟合一次」，
        // skydive 三个 30 s 片段从 0.0401% 变成 0.1066%，撤了。
        //
        // 但残差**必须**量在起音峰上：DP 路径自己会在起音弱的地方抖半拍，拿路径当基准，
        // 「明明对上了」的素材会被 gate 掉（实测 skydive 与春めく 各有好几个档位全不表态）。
        int residSpan = Math.max(1, (int) Math.round(fit.period / 4.0));
        int first = (int) Math.ceil((0.0 - fit.phase) / fit.period);
        int last = (int) Math.floor((n - 1.0 - fit.phase) / fit.period);
        int total = Math.max(0, last - first + 1);
        double[] offsets = new double[total];
        int valid = 0;
        for (int k = first; k <= last; k++) {
            double want = fit.phase + fit.period * k;
            int centre = (int) Math.round(want);
            if (centre < 0 || centre >= n) {
                continue;
            }
            int lo = Math.max(0, centre - residSpan);
            int hi = Math.min(n - 1, centre + residSpan);
            int peak = lo;
            for (int i = lo; i <= hi; i++) {
                if (x[i] > x[peak]) {
                    peak = i;
                }
            }
            if (x[peak] < VALID_BEAT_PEAK) {
                continue;
            }
            offsets[valid++] = refinePeak(x, peak, 1) - want;
        }
        if (valid < MIN_BEATS || total <= 0 || valid < MIN_VALID_RATIO * total) {
            return no(String.format(java.util.Locale.ROOT,
                    "有效拍不够：%d/%d 落在起音上（门槛 %.0f%% 且至少 %d 拍）",
                    Integer.valueOf(valid), Integer.valueOf(total),
                    Double.valueOf(MIN_VALID_RATIO * 100.0), Integer.valueOf(MIN_BEATS)));
        }
        double[] absOff = new double[valid];
        for (int i = 0; i < valid; i++) {
            absOff[i] = Math.abs(offsets[i]);
        }
        java.util.Arrays.sort(absOff);
        double medianOffset = absOff[valid / 2];
        double[] dev = new double[valid];
        for (int i = 0; i < valid; i++) {
            dev[i] = Math.abs(Math.abs(offsets[i]) - medianOffset);
        }
        java.util.Arrays.sort(dev);
        double residualMs = 1.4826 * dev[valid / 2] / envRate * 1000.0;
        double residualFraction = residualMs / (fit.period / envRate * 1000.0);
        if (residualFraction > MAX_RESIDUAL_FRACTION) {
            return no(String.format(java.util.Locale.ROOT,
                    "拍点偏离栅格太多：残差 %.1f ms = 拍长的 %.1f%% > %.0f%%",
                    Double.valueOf(residualMs), Double.valueOf(residualFraction * 100.0),
                    Double.valueOf(MAX_RESIDUAL_FRACTION * 100.0)));
        }
        double bpm = 60.0 * envRate / fit.period;
        if (bpm < MIN_BPM * 0.95 || bpm > MAX_BPM * 1.05) {
            return no(String.format(java.util.Locale.ROOT,
                    "拟合出来的速度 %.2f BPM 落在 70~180 之外", Double.valueOf(bpm)));
        }
        double confidence = Math.max(0.0,
                Math.min(1.0, 1.0 - residualFraction / RESIDUAL_FULL_FRACTION));
        lastReject = null;
        return new Analysis.Tempo((float) bpm, (float) (fit.period * hop),
                (float) ((fit.phase % fit.period) * hop), (float) confidence);
    }

    /**
     * Ellis 动态规划：给定周期，找一条「起音强 + 间隔规整」的路径。
     *
     * <p>回溯得到的是<b>拍点下标</b>（升序）。路径从「累计得分最大」的末尾往前走。
     */
    private static int[] dp(float[] x, double period, int minLag, int maxLag) {
        int n = x.length;
        int back = Math.max(1, (int) Math.round(period * 0.5));
        int fwd = Math.max(back + 1, (int) Math.round(period * 1.5));
        double[] score = new double[n];
        int[] from = new int[n];
        // 转移项**按间隔预计算成表**（2026-10-01 压缩预处理时间）：
        // 它只依赖 `t − τ`（周期在一次 DP 里是常数），而原来对每一对 (t, τ) 都算一遍
        // `Math.pow(Math.log(d))`——实测 190 s 的素材上一次 DP 就是上百万次超越函数，
        // BeatTracker 整段 261 ms 里绝大部分花在这里。表长只有一块多（~66 项），
        // 值与原式**逐位相同**（同一个 d、同一个表达式，只是算的次数从百万次变成几十次）。
        double[] penaltyByGap = new double[fwd + 1];
        for (int gap = 0; gap <= fwd; gap++) {
            double d = (double) gap / period;
            penaltyByGap[gap] = TIGHTNESS * (-0.5
                    * Math.pow(Math.log(Math.max(d, 1e-6)) / PREFERRED_DEVIATION, 2.0));
        }
        for (int t = 0; t < n; t++) {
            score[t] = Math.max(0.0, x[t]);
            from[t] = -1;
            int lo = Math.max(0, t - fwd);
            int hi = t - back;
            double bestPrev = Double.NEGATIVE_INFINITY;
            int bestIdx = -1;
            for (int tau = lo; tau <= hi; tau++) {
                double cand = score[tau] + penaltyByGap[t - tau];
                if (cand > bestPrev) {
                    bestPrev = cand;
                    bestIdx = tau;
                }
            }
            // 只在「前驱得分 > 0」时接上：包络去均值之后一半的点是负的，
            // 无条件接会让路径穿过负分区、把根本不是拍的点也串进来——2026-10-01 实测：
            // 去掉这个守卫之后 skydive 在 r=0.7813 上从 100.00 变成 104.48、
            // 尾段 30 s 从 128.05 变成 96.10、春めく 变成 172.46，全都更差。守卫留着。
            //
            // 但**守卫会让路径断成几段**（弱拍处链断、路径重新起步），所以后面那条回归
            // 绝不能拿数组下标当拍号——见 `beatNumbers` 那几行。
            if (bestIdx >= 0 && bestPrev > 0.0) {
                score[t] += bestPrev;
                from[t] = bestIdx;
            }
        }
        // 末尾：只看最后一个周期内得分最高的点，避免「末尾突然冒一下」把整条路径带偏
        int tailFrom = Math.max(0, n - Math.max(1, (int) Math.round(period)));
        int end = -1;
        for (int t = tailFrom; t < n; t++) {
            if (end < 0 || score[t] > score[end]) {
                end = t;
            }
        }
        if (end < 0) {
            return null;
        }
        int count = 0;
        for (int t = end; t >= 0; t = from[t]) {
            count++;
            if (count > n) {
                return null;
            }
        }
        int[] path = new int[count];
        int i = count - 1;
        for (int t = end; t >= 0; t = from[t]) {
            path[i--] = t;
        }
        return path;
    }

    /** 共振先验：120 BPM 处最高，对数域高斯（与 {@code Analysis.PRIOR_*} 同参）。 */
    private static double resonancePrior(double bpm) {
        double ratio = Math.log(Math.max(bpm, 1e-6) / RESONANCE_CENTER_BPM);
        return Math.exp(-0.5 * Math.pow(ratio / RESONANCE_SIGMA, 2.0));
    }

    /** 在 {@code [center − span, center + span]} 内做抛物线插值取峰（亚格点定位）。 */
    private static double refinePeak(float[] x, int center, int span) {
        int lo = Math.max(0, center - span);
        int hi = Math.min(x.length - 1, center + span);
        int best = lo;
        for (int i = lo; i <= hi; i++) {
            if (x[i] > x[best]) {
                best = i;
            }
        }
        if (best <= 0 || best >= x.length - 1) {
            return best;
        }
        double a = x[best - 1];
        double b = x[best];
        double c = x[best + 1];
        double denom = a - 2.0 * b + c;
        if (Math.abs(denom) < 1e-12) {
            return best;
        }
        double delta = 0.5 * (a - c) / denom;
        return best + Math.max(-0.5, Math.min(0.5, delta));
    }

    /** 拟合结果：周期（包络格）、相位（包络格）、残差标准差（ms）、参与拟合的拍数。 */
    private static final class Fit {
        final double period;
        final double phase;
        final double residualMs;
        final int count;

        Fit(double period, double phase, double residualMs, int count) {
            this.period = period;
            this.phase = phase;
            this.residualMs = residualMs;
            this.count = count;
        }
    }

    /**
     * 稳健最小二乘：{@code t_i = phase + i·period}。
     *
     * <p>用 MAD 定 σ（对少数离谱的落脚点不敏感），迭代剔除 &gt;2.5σ 的外点后再拟合一次。
     * 残差标准差换算成毫秒返回——它就是置信度的物理来源。
     */
    private static Fit robustFit(double[] numbers, double[] times, double envRate) {
        int n = times.length;
        if (n < MIN_BEATS) {
            return null;
        }
        boolean[] keep = new boolean[n];
        java.util.Arrays.fill(keep, true);
        double period = 0.0;
        double phase = 0.0;
        double madMs = 0.0;
        int kept = n;
        for (int iter = 0; iter < 6; iter++) {
            double sx = 0.0;
            double sy = 0.0;
            double sxx = 0.0;
            double sxy = 0.0;
            double si = 0.0;
            int k = 0;
            for (int i = 0; i < n; i++) {
                if (!keep[i]) {
                    continue;
                }
                double xi = numbers[i];
                double yi = times[i] - times[0];
                sx += xi;
                sy += yi;
                sxx += xi * xi;
                sxy += xi * yi;
                si += 1.0;
                k++;
            }
            if (k < MIN_BEATS) {
                return null;
            }
            double denom = si * sxx - sx * sx;
            if (Math.abs(denom) < 1e-9) {
                return null;
            }
            period = (si * sxy - sx * sy) / denom;
            phase = times[0] + (sy - period * sx) / si;
            // phase 是「拍号 0 的位置」：调用方只把它当相位用（mod 周期），差一个常数拍
            // 不影响栅格。
            if (!(period > 0.0)) {
                return null;
            }
            double[] residuals = new double[k];
            int j = 0;
            for (int i = 0; i < n; i++) {
                if (keep[i]) {
                    residuals[j++] = times[i] - (phase + period * numbers[i]);
                }
            }
            double[] sorted = residuals.clone();
            java.util.Arrays.sort(sorted);
            double median = sorted[sorted.length / 2];
            double[] abs = new double[sorted.length];
            for (int i = 0; i < sorted.length; i++) {
                abs[i] = Math.abs(sorted[i] - median);
            }
            java.util.Arrays.sort(abs);
            double mad = abs[abs.length / 2];
            double sigma = 1.4826 * mad;
            madMs = sigma / envRate * 1000.0;
            if (sigma <= 1e-9) {
                break;
            }
            int dropped = 0;
            for (int i = 0; i < n; i++) {
                if (!keep[i]) {
                    continue;
                }
                double r = Math.abs(times[i] - (phase + period * numbers[i]) - median);
                if (r > 2.5 * sigma) {
                    keep[i] = false;
                    dropped++;
                }
            }
            kept = k - dropped;
            if (dropped == 0 || kept < MIN_BEATS) {
                break;
            }
        }
        return new Fit(period, phase % period, madMs, kept);
    }

    /** 一条拟合出来的直线：{@code pos ≈ intercept + slope · k}。 */
    private static final class Line {
        final double slope;
        final double intercept;
        final double residualMs;

        Line(double slope, double intercept, double residualMs) {
            this.slope = slope;
            this.intercept = intercept;
            this.residualMs = residualMs;
        }
    }

    /**
     * 对 {@code (k, pos)} 做稳健直线拟合（MAD 定 σ，迭代剔除 &gt;2.5σ 的外点）。
     *
     * <p>斜率就是周期（包络格），截距是相位；{@code residualMs} 是残差标准差换算成毫秒
     * ——它是置信度的物理来源（「拍点偏离栅格多少毫秒」），与 hop、与素材长度都无关。
     */
    private static Line robustLine(double[] k, double[] pos, int count, double envRate) {
        boolean[] keep = new boolean[count];
        java.util.Arrays.fill(keep, true);
        double slope = 0.0;
        double intercept = 0.0;
        double residualMs = Double.NaN;
        int kept = count;
        for (int iter = 0; iter < 6; iter++) {
            double sx = 0.0;
            double sy = 0.0;
            double sxx = 0.0;
            double sxy = 0.0;
            double m = 0.0;
            for (int i = 0; i < count; i++) {
                if (!keep[i]) {
                    continue;
                }
                sx += k[i];
                sy += pos[i];
                sxx += k[i] * k[i];
                sxy += k[i] * pos[i];
                m += 1.0;
            }
            if (m < MIN_BEATS) {
                return null;
            }
            double denom = m * sxx - sx * sx;
            if (Math.abs(denom) < 1e-9) {
                return null;
            }
            slope = (m * sxy - sx * sy) / denom;
            intercept = (sy - slope * sx) / m;
            if (!(slope > 0.0)) {
                return null;
            }
            double[] residuals = new double[(int) m];
            int j = 0;
            for (int i = 0; i < count; i++) {
                if (keep[i]) {
                    residuals[j++] = pos[i] - (intercept + slope * k[i]);
                }
            }
            java.util.Arrays.sort(residuals);
            double median = residuals[residuals.length / 2];
            double[] abs = new double[residuals.length];
            for (int i = 0; i < residuals.length; i++) {
                abs[i] = Math.abs(residuals[i] - median);
            }
            java.util.Arrays.sort(abs);
            double sigma = 1.4826 * abs[abs.length / 2];
            residualMs = sigma / envRate * 1000.0;
            if (sigma <= 1e-9) {
                break;
            }
            int dropped = 0;
            for (int i = 0; i < count; i++) {
                if (!keep[i]) {
                    continue;
                }
                if (Math.abs(pos[i] - (intercept + slope * k[i]) - median) > 2.5 * sigma) {
                    keep[i] = false;
                    dropped++;
                }
            }
            kept = (int) m - dropped;
            if (dropped == 0 || kept < MIN_BEATS) {
                break;
            }
        }
        return kept < MIN_BEATS ? null : new Line(slope, intercept, residualMs);
    }

    /** 包络的 95 分位（用来归一化，去掉整体电平的影响）。 */
    private static double percentile95(float[] x) {
        float[] copy = x.clone();
        java.util.Arrays.sort(copy);
        int idx = Math.min(copy.length - 1, (int) Math.floor(copy.length * 0.95));
        double v = copy[idx];
        if (v < 0.0) {
            // 全负（理论上不会，包络已去均值）时退回绝对值最大
            v = 0.0;
            for (float f : copy) {
                v = Math.max(v, Math.abs(f));
            }
        }
        return v;
    }
}
