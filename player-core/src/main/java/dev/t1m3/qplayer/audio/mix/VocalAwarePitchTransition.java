package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.dsp.PitchShifter;
import dev.t1m3.qplayer.audio.model.StemSeparator;
import dev.t1m3.qplayer.audio.stem.StemSeparators;
import dev.t1m3.qplayer.util.Logger;

/**
 * 声波重合的<strong>人声不变调</strong>包装：变调只加在伴奏上，人声原样交叉。
 *
 * <h2>它修的是什么</h2>
 *
 * <p>声波重合靠<b>移调</b>把两首歌凑到同一个调上。而变调是 <b>deck 对整个混音</b>施加的
 * （{@code Deck.setPitch} → {@code PitchShifter}）——于是<strong>人声跟着一起移</strong>：
 * 用户听到的是「人声变调了」，那不是过渡，那是跑调。
 *
 * <h2>怎么修的</h2>
 *
 * <ol>
 *   <li>{@link #pitchAt} <strong>恒返回零</strong>：deck 一点都不要变调（编排器据此不挂变调器）；</li>
 *   <li>{@link #prepare} 里（<b>准备线程</b>，绝不在渲染路径上）读两首歌在窗口内的素材，
 *       各自过一次人声分离（AI 优先、没有就 DSP），拿到四条轨：A 伴奏 / A 人声 / B 伴奏 / B 人声；</li>
 *   <li>变调只加在<strong>出场曲的伴奏</strong>上（{@link PitchShifter}，离线一次算完）；
 *       进场曲<strong>整条都不动</strong>——理由见 {@link #prepare} 里那段（它没有和声收益，
 *       却会在交接处留一个弹回原调的跳变，用户 2026-09-30 报的「加速了」就是它）；</li>
 *   <li>{@link #render} 按等功率交叉把四条轨混起来——人声那两条<strong>不带任何变调</strong>。</li>
 * </ol>
 *
 * <p>失败的路径都如实降级：读不到素材、分离不出来、变调器建不起来 → 这一次就退回
 * <strong>被包住的那个接法</strong>（{@link #inner}），并在 {@code plan.note} 里写明原因——
 * 绝不「悄悄当成功」。
 *
 * <h2>代价</h2>
 *
 * <p>每个过渡窗口多一次分离（AI 实测 16 秒素材约 9 秒，DSP 是毫秒级）——所以它必须在
 * {@code prepare} 里做，且这一步的长度直接决定 {@code prepareMarginSecs}。
 *
 * <p>剩下唯一一处「不干净」的地方在出场曲自己身上：A 的伴奏被移了、A 的人声没移，
 * 所以窗口内 A 的伴奏与它自己的人声差 {@code shift} 个半音。这是用户明确选的取舍
 * （「A 和 B 人声音调都不变」优先）。进场曲没有这个问题——它一个采样都不动。
 */
public final class VocalAwarePitchTransition implements Transition {

    /** 一次过渡里最多给分离/变调多少时间（毫秒）。超了就退回被包住的接法。 */
    private static final long BUDGET_MS = 120_000L;   // 工作五：60 s → 120 s

    private final Transition inner;
    private final int rate;
    private final int channels;
    private final int vocalMode;
    /**
     * 这一档的 {@code vocalMode} 是不是引擎自己补的 DSP。
     *
     * <p>用户没开人声分离（{@code OFF}）时，声波重合仍然需要分轨才能「只移伴奏」，
     * 而 DSP 是毫秒级、不依赖任何外部文件的那一档，所以由引擎补上。
     * <b>必须如实标注</b>（{@code plan.note} 与日志里写清楚），
     * 否则就成了「悄悄换了一种算法」——那正是这一档的注释一直在防的事。
     */
    private final boolean autoSeparated;

    // ---- 只在 prepare 线程写、渲染线程读（prepare 完成后渲染线程才可能被调用） ----
    private volatile float[][] aInst;
    private volatile float[][] aVoc;
    private volatile float[][] bInst;
    private volatile float[][] bVoc;
    private volatile float semitones;
    private volatile int covered;
    private volatile String note;

    public VocalAwarePitchTransition(Transition inner, int rate, int channels, int vocalMode) {
        this(inner, rate, channels, vocalMode, false);
    }

    /**
     * @param autoSeparated 见 {@link #autoSeparated}：{@code vocalMode} 是不是引擎
     *                      在用户没开分离时自己补的 DSP。
     */
    public VocalAwarePitchTransition(Transition inner, int rate, int channels, int vocalMode,
                                     boolean autoSeparated) {
        if (inner == null) {
            throw new IllegalArgumentException("被包住的接法不能为 null");
        }
        this.inner = inner;
        this.rate = Math.max(rate, 1);
        this.channels = Math.max(channels, 1);
        this.vocalMode = vocalMode;
        this.autoSeparated = autoSeparated;
    }

    /** 这一档的分离器是引擎自动补的 DSP 吗（诊断与判据要看）。 */
    public boolean autoSeparated() {
        return autoSeparated;
    }

    /** 被包住的接法（诊断用）。 */
    public Transition inner() {
        return inner;
    }

    /** 用户选的那一档人声处理（{@code StemSeparators.DSP} / {@code AI}）——测试与诊断要看。 */
    public int vocalMode() {
        return vocalMode;
    }

    /** 这一次准备留下的说明（{@code null} = 还没准备过）。 */
    public String preparedNote() {
        return note;
    }

    @Override
    public long windowFrames(int sampleRate) {
        return inner.windowFrames(sampleRate);
    }

    @Override
    public String name() {
        // 与 {@link VocalSeparationTransition} 同一口径：**把自己的修饰挂在里面那层的名字后面**。
        //
        // 原先写死成「声波重合（人声不变调）」是错的：这一层在 **AI 自动档**里是「只移伴奏」
        // 的包装，里面那层由 AI 方案比较决定（可能是交叉淡化、低音交接、拍点剪切……）。
        // 写死会让日志把接法报错——2026-10-01 实测就撞上：
        // 「AI 方案比较：… 声波重合 不参与（调性本来就合）→ 选 现有接法」
        // 紧接着「进入过渡 · 接法「声波重合（人声不变调）」」，两句自相矛盾。
        // 用户读日志就是为了知道「这次到底跑了哪一套」，报错比不报还糟。
        return inner.name() + "（人声不变调）";
    }

    @Override
    public boolean rewritesSamples() {
        return true;
    }

    /**
     * 变调量恒为零：<strong>deck 一点都不许移调</strong>。
     *
     * <p>这正是这一层存在的意义——变调改由本类只对伴奏施加。
     */
    @Override
    public PitchPlan pitchAt(float progress) {
        return PitchPlan.ZERO;
    }

    /** 变调深度上限（半音）：超过就不移调，避免相位声码器把瞬态糊成"朦胧噪声"。 */
    private static final float MAX_PITCH_SEMITONES = 2.0f;

    @Override
    public TransitionPlan prepare(String outro, String intro, int sampleRate) {
        return prepare(outro, intro, sampleRate, null, null);
    }

    @Override
    public TransitionPlan prepare(String outro, String intro, int sampleRate,
                                  java.util.Map<String, String> outroHeaders,
                                  java.util.Map<String, String> introHeaders) {
        TransitionPlan plan = inner.prepare(outro, intro, sampleRate, outroHeaders, introHeaders);
        long window = inner.windowFrames(sampleRate);
        // PitchPlan 的两个字段是「出场侧 / 进场侧」各自的变调量（声波重合只用出场侧那个）
        float shift = plan.pitch == null ? 0.0f : plan.pitch.outro;
        if (window <= 0 || Math.abs(shift) < 0.01f) {
            note = "声波重合：没有窗口或没有变调量，这一层不做事";
            return plan;
        }
        // **变调深度上限 2 半音**（2026-11-16 用户实测：「像有一层朦胧乱码音效滑过来」）：
        // 整条伴奏移 3 个半音时相位声码器的瞬态/气声会糊成一层"毛毛的噪声"✗。
        // 超过 2 半音就不移调（两边各按自己的调做交叉淡化）——宁可调性差一点，也不要糊。
        if (Math.abs(shift) > MAX_PITCH_SEMITONES) {
            note = "声波重合：这次要移 " + String.format(java.util.Locale.ROOT, "%+.2f", Float.valueOf(shift))
                    + " 半音（超过上限 " + String.format(java.util.Locale.ROOT, "%.1f", Float.valueOf(MAX_PITCH_SEMITONES))
                    + "），移这么深会糊 → 本次不移调（两边各按自己的调）";
            Logger.info("[qplayer-engine] {}", note);
            return withoutPitch(plan, note);
        }
        // **拿不到分轨就不移调**：宁可这次不转调，也不许把整段混音（含人声）移调——
        // 用户的要求是「A 和 B 人声音调都不变」，人声的保护优先于调性收益。
        if (!StemSeparators.available(vocalMode)) {
            note = "声波重合：拿不到分轨（" + StemSeparators.unavailableReason(vocalMode)
                    + "）→ 本次不移调（人声不动）";
            Logger.warn("[qplayer-engine] {}", note);
            return withoutPitch(plan, note);
        }
        long t0 = System.currentTimeMillis();
        try {
            // **出场曲那一段的起点 = 总长 − 被丢掉的尾巴 − 窗口**。
            //
            // 原先这里直接把 `plan.outroTailSecs` 当偏移用，而它是「被丢掉的尾巴长度」：
            // 固定点位时它恒为 0，于是**读到的是出场曲的开头**——融合期里听到的是这首歌的
            // 前奏，而不是它的尾段（用户原话：「现在取A会取开头这是啥阴间」）。
            // 同族的 {@code GenerativeBridge.cutSecs} 一直是按那个减法算的。
            double fromSecs = windowStartSecs(outro, outroHeaders, plan, window, sampleRate);
            if (fromSecs < 0.0) {
                note = "声波重合：读不出出场曲总长，算不出它尾段从哪开始 → 本次不移调（人声不动）";
                Logger.warn("[qplayer-engine] {}", note);
                return withoutPitch(plan, note);
            }
            float[][] from = Automix.readStereoWindow(outro, outroHeaders,
                    fromSecs, (int) window, sampleRate);
            float[][] to = Automix.readStereoWindow(intro, introHeaders,
                    Math.max(0.0, plan.skipSecs), (int) window, sampleRate);
            if (from == null || to == null) {
                note = "声波重合：读不到窗口素材 → 本次不移调（人声不动）";
                Logger.warn("[qplayer-engine] {}", note);
                return withoutPitch(plan, note);
            }
            // **两侧一起做**（2026-09-30，用户要求压缩预处理时间）：读素材与分轨对 A、B
            // 互不相干，串着做等于把两段墙钟时间加起来。
            //
            // 结果**逐位不变**：同一批调用、同一份输入，只是并行——分离器各自 new 一个实例
            // （{@code StemSeparators.forMode} 每次都给新的），没有共享可变状态；
            // 两条轨的数组分别写进不同的字段，谁先完成都不影响另一半。
            //
            // **只有 DSP 那一档并行**：它每次几百 KB、毫秒级；AI 那一档要加载模型会话，
            // 两个会话并发在手机上就是 OOM——宁可慢一点，也不能为了省几秒把进程搞崩。
            StemSeparator.StemSet setA;
            StemSeparator.StemSet setB;
            if (vocalMode == StemSeparators.DSP) {
                final float[][] fromRef = from;
                final float[][] toRef = to;
                final StemSeparator.StemSet[] slotA = new StemSeparator.StemSet[1];
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        slotA[0] = separate(fromRef);
                    }
                }, "qplayer-stem-a");
                worker.setDaemon(true);
                worker.start();
                setB = separate(toRef);
                try {
                    worker.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                setA = slotA[0];
            } else {
                setA = separate(from);
                setB = separate(to);
            }
            if (setA == null || setB == null) {
                note = "声波重合：去人声失败（" + StemSeparators.label(vocalMode) + "）"
                        + " → 本次不移调（人声不动）";
                Logger.warn("[qplayer-engine] {}", note);
                return withoutPitch(plan, note);
            }
            // **四条轨补齐到同一长度**：`covered` 取四者的最小长度，
            // 差一帧就会让融合期的**最后一块**掉回内层（那是一次听得见的换声）。
            int longest = Math.max(lengthOf(stereo(setA.instrumentalLeft, setA.instrumentalRight)),
                    Math.max(lengthOf(stereo(setB.instrumentalLeft, setB.instrumentalRight)),
                            Math.max(lengthOf(stereo(setA.vocalsLeft, setA.vocalsRight)),
                                    lengthOf(stereo(setB.vocalsLeft, setB.vocalsRight)))));
            aInst = padTo(stereo(setA.instrumentalLeft, setA.instrumentalRight), longest);
            aVoc = padTo(stereo(setA.vocalsLeft, setA.vocalsRight), longest);
            bInst = padTo(stereo(setB.instrumentalLeft, setB.instrumentalRight), longest);
            bVoc = padTo(stereo(setB.vocalsLeft, setB.vocalsRight), longest);
            semitones = shift;

            PrepareStage.mark("离线移调（出场曲伴奏）");
        // **只移出场曲的伴奏，而且只移这一条**：一次离线算完（时长不变，起止对齐由
            // PitchShifter 保证）。
            //
            // ---- 为什么进场曲的伴奏一点都不许动（用户 2026-09-30 实测报的 bug）----
            //
            // 用户报：「（声波重合时）加速了」。查下来是这一行原来把 **B 的伴奏也移了同一个
            // 量**（`bInst = shifted(bInst, shift)`）。这个处理**一点收益都没有、全是代价**：
            //
            //   1. **和声上白移**：`Harmonic.match` 算出来的 `shift` 语义就是「把**出场曲**
            //      转到进场曲的调上」（`HarmonicBlend` 的计划是 `PitchPlan(shift, 0)`）。
            //      把 B 也移同一个量，A、B 之间的音程**一点没变**——「凑到同一个调」压根没完成
            //      （这段取舍在 `HarmonicBlend` 的类注释里就写着，docs 的 §12.1① 也点过名）；
            //   2. **听得见的代价**：窗口里 B 的伴奏被移了、B 的人声（`bVoc`）没移 → B 自己
            //      的伴奏与人声差 `shift` 个半音；而窗口一结束 deck 按**原调**继续放 B，
            //      于是那一下又「弹回原调」。用户听到的就是这个跳变/整段不对劲。
            //
            // 只移 A 之后两边都更好：A 的伴奏**真的**被转到 B 的调上（和声意图完成），
            // 而 B 整首歌（伴奏 + 人声）在窗口里**逐样本就是它自己**——交接处没有任何跳变，
            // 因为 deck 接下来放的正是同一份东西。
            synchronized (shifterLock) {
                aInst = shifted(aInst, shift);
            }
            covered = Math.min(lengthOf(aInst), Math.min(lengthOf(bInst),
                    Math.min(lengthOf(aVoc), lengthOf(bVoc))));
            long ms = System.currentTimeMillis() - t0;
            note = "声波重合：只对出场曲的伴奏移调 " + String.format(java.util.Locale.ROOT, "%+.2f", Float.valueOf(shift))
                    + " 半音（进场曲原样），两条人声都原样（" + StemSeparators.label(vocalMode)
                    + (autoSeparated ? "，人声分离没开 → 引擎自动用 DSP" : "")
                    + "，" + ms + " ms）";
            Logger.debug("[qplayer-engine] {}", note);
        } catch (RuntimeException e) {
            clear();
            note = "声波重合：准备变调素材失败（" + e + "）→ 本次不移调（人声不动）";
            Logger.warn("[qplayer-engine] {}", note);
        }
        double elapsed = (System.currentTimeMillis() - t0) / 1000.0;
        if (elapsed * 1000.0 > BUDGET_MS) {
            clear();
            note = "声波重合：准备变调素材超预算（" + elapsed + " s）→ 本次不移调（人声不动）";
        }
        // 交给编排器的计划**不带变调**：变调由本类自己做（只加在伴奏上），
        // 而且只在素材真的准备好时才做（aInst 等非空）——render 里那条
        // 「材料不全就交回内层」的分支因此永远不会把整段混音移调。
        if (aInst == null || bInst == null) {
            return withoutPitch(plan, note);
        }
        return new TransitionPlan(plan.skipSecs, PitchPlan.ZERO, plan.introStartAt, note,
                plan.outroTailSecs);
    }

    @Override
    public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
        float[][] ai = aInst;
        float[][] av = aVoc;
        float[][] bi = bInst;
        float[][] bv = bVoc;
        if (ai == null || bi == null || av == null || bv == null) {
            return inner.render(out, from, to, progress);
        }
        int capacity = Math.min(out.capacityFrames(), covered - cursor);
        if (capacity <= 0) {
            return inner.render(out, from, to, progress);
        }
        int n = capacity;
        long window = Math.max(inner.windowFrames(rate), 1L);
        float perFrame = 1.0f / (float) window;
        int chs = Math.min(out.channels(), channels);
        for (int ch = 0; ch < chs; ch++) {
            java.nio.FloatBuffer dst = out.channelWriter(ch);
            float[] a = ch < ai.length ? ai[ch] : ai[0];
            float[] avc = ch < av.length ? av[ch] : av[0];
            float[] b = ch < bi.length ? bi[ch] : bi[0];
            float[] bvc = ch < bv.length ? bv[ch] : bv[0];
            for (int i = 0; i < n; i++) {
                int idx = cursor + i;
                float p = Automix.minF32(progress + (float) i * perFrame, 1.0f);
                float g = Automix.smoothstep(0.0f, 1.0f, p);
                double outGain = Math.cos(g * Math.PI / 2);
                double inGain = Math.sin(g * Math.PI / 2);
                // 四条轨**同一条等功率曲线**：伴奏已移调、人声原样（见类注释第 27 行
                // 「按等功率交叉把四条轨混起来」与第 41 行「进场曲一个采样都不动」）。
                //
                // **进场曲的人声曾经多乘一个 g（进度）**（2026-10-03 修）：
                // 于是 B 的人声在整个窗口里比 B 的伴奏额外弱 g 倍——窗口中点处是
                // sin(π/4)·smoothstep(0.5) = 0.354，而它应当是 sin(π/4) = 0.707（−6 dB），
                // 听感上是「新歌的人声慢慢鼓出来」。那不是设计，是公式错：
                //   · 类注释第 10 行写的是「人声原样交叉」，note 里对用户说的是「两条人声都原样」；
                //   · 「交叉期两首歌的人声不叠着唱」这件事**有自己的地方**——
                //     {@code VocalSeparationTransition} 的连续权重让位曲线（{@code vocalDuckCurve}，
                //     峰值/位置都算出来的），而 AI 自动档里那一层正好套在本层外面；
                //     这里再乘一个 g 等于**让两次、还让得没有形状**。
                // 判据：{@code VocalAwarePitchTransitionTest}
                // #theIncomingVocalFollowsTheSameEqualPowerCrossfadeAsTheOutgoingOne。
                dst.put(i, (float) (a[idx] * outGain + b[idx] * inGain
                        + avc[idx] * outGain + bvc[idx] * inGain));
            }
        }
        out.setFrames(n);
        cursor += n;
        return n;
    }

    // ------------------------------------------------------------------

    /** 已经渲染到窗口内的第几帧（渲染线程独占）。 */
    private int cursor;
    private final Object shifterLock = new Object();

    private void clear() {
        aInst = null;
        aVoc = null;
        bInst = null;
        bVoc = null;
        covered = 0;
        cursor = 0;
    }

    /**
     * 出场曲那一段在文件里的起点（秒）；算不出来返回负数。
     *
     * <p>语义来自 {@link TransitionPlan#outroTailSecs}：那是「被丢掉的尾巴长度」，
     * 编排器会把过渡触发点提前到「还剩 {@code 窗口 + 尾巴}」的时候，所以窗口覆盖的是
     * {@code [总长 − 尾巴 − 窗口, 总长 − 尾巴]} 这一段。
     * 同一个减法在 {@code GenerativeBridge.cutSecs} 里也有一份（那边算的是切点）。
     */
    private static double windowStartSecs(String outro, java.util.Map<String, String> headers,
                                          TransitionPlan plan, long window, int sampleRate) {
        try {
            double windowSecs = (double) window / (double) Math.max(sampleRate, 1);
            Automix.Mono tail = Automix.readMono(outro, headers, true, windowSecs);
            if (tail.durationSecs > 0.0) {
                return Math.max(0.0, tail.durationSecs - plan.outroTailSecs - windowSecs);
            }
        } catch (RuntimeException e) {
            Logger.warn("[qplayer-engine] 声波重合：读不出出场曲总长（{}）", e.toString());
        }
        return -1.0;
    }

    /**
     * 内层计划去掉移调意图、换上本层说明的副本。
     *
     * <p>「不移调」必须同时体现在 <b>plan 与 pitchAt 两处</b>：编排器是按
     * {@code plan.pitch} 决定要不要提前挂变调器的（挂上就不再位完美，而且会占掉
     * 那台 deck 的几十毫秒前视），而每块的量由 {@code pitchAt} 给。
     * 只改一处就会留下「挂了个空转变调器」或者更糟的「整段混音被移调」。
     */
    private static TransitionPlan withoutPitch(TransitionPlan plan, String note) {
        return new TransitionPlan(plan.skipSecs, PitchPlan.ZERO, plan.introStartAt, note,
                plan.outroTailSecs);
    }

    /**
     * 实测「输出相对输入被推后了多少帧」（正 = 推后）。
     *
     * <p>做法是降采样互相关，两级搜索（先 ±{@code search} 步长 8，再在邻域内细到 1 帧）。
     * 不为省事写常数：粒长随采样率取最接近的 2 的幂（见 {@code PitchShifter.grainForRate}），
     * 而且真实偏移与 {@code latencyFrames()} 并不是一个数——写成常数就会在某个采样率上错。
     */
    private static int measuredLag(AudioBuf in, AudioBuf out, int total, int written, int guess) {
        int usable = Math.min(total, written);
        int search = 4096;
        if (usable < 4 * search) {
            return guess;      // 素材太短，量不准就用前视那个估计
        }
        float[] a = flatten(in, 0);
        float[] b = flatten(out, 0);
        int best = guess;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int lag = -search; lag <= search; lag += 8) {
            double score = correlation(a, b, lag);
            if (score > bestScore) {
                bestScore = score;
                best = lag;
            }
        }
        for (int lag = best - 8; lag <= best + 8; lag++) {
            double score = correlation(a, b, lag);
            if (score > bestScore) {
                bestScore = score;
                best = lag;
            }
        }
        return best;
    }

    /** 单声道视图（相关性只看一条就够：两侧一起移同一个量）。 */
    private static float[] flatten(AudioBuf buf, int channel) {
        int frames = buf.frames();
        float[] out = new float[frames];
        java.nio.FloatBuffer r = buf.channel(Math.min(channel, buf.channels() - 1));
        for (int i = 0; i < frames; i++) {
            out[i] = r.get(i);
        }
        return out;
    }

    /** 降采样互相关（每 64 帧取一个样本；lag 仍是整帧）。 */
    private static double correlation(float[] a, float[] b, int lag) {
        double num = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i += 64) {
            int j = i + lag;
            if (j < 0 || j >= b.length) {
                continue;
            }
            num += (double) a[i] * b[j];
            na += (double) a[i] * a[i];
            nb += (double) b[j] * b[j];
        }
        double den = Math.sqrt(na * nb);
        return den > 0.0 ? num / den : Double.NEGATIVE_INFINITY;
    }

    /** 把一条立体声补零到指定长度（已经够长就原样返回）。 */
    private static float[][] padTo(float[][] pcm, int frames) {
        if (pcm.length == 0 || pcm[0].length >= frames) {
            return pcm;
        }
        float[][] out = new float[pcm.length][frames];
        for (int ch = 0; ch < pcm.length; ch++) {
            System.arraycopy(pcm[ch], 0, out[ch], 0, pcm[ch].length);
        }
        return out;
    }

    private static int lengthOf(float[][] pcm) {
        return pcm == null || pcm.length == 0 ? 0 : pcm[0].length;
    }

    private static float[][] stereo(float[] left, float[] right) {
        return new float[][]{left, right};
    }

    /** 去人声（AI 优先、没有就 DSP）；失败返回 {@code null}（由调用方如实降级）。 */
    private StemSeparator.StemSet separate(float[][] pcm) {
        StemSeparator sep = null;
        try {
            // 这一档在手机上可能就是十几秒（AI 那一档要加载模型会话）——记上，
            // 免得「没赶上触发点」时看不出是卡在分轨上。
            PrepareStage.mark("去人声 / 分轨（" + StemSeparators.label(vocalMode) + "）");
            sep = StemSeparators.forMode(vocalMode);
            return sep.separate(pcm[0], pcm[1], rate);
        } catch (RuntimeException e) {
            Logger.warn("[qplayer-engine] 声波重合：分离失败（{}）", e.toString());
            return null;
        } finally {
            if (sep != null) {
                try {
                    sep.close();
                } catch (RuntimeException ignored) {
                    // 关不掉不影响结果
                }
            }
        }
    }

    /**
     * 离线移调一条立体声（时长不变）。
     *
     * <h3>必须按「喂一点、拉一点」的流式协议来（这里踩过一个坑）</h3>
     *
     * <p>{@link PitchShifter#pushFrom} 是<strong>部分接收</strong>语义：环形缓冲装不下就返回 0。
     * 原先这里先把整段（窗口几万帧）一次喂完、再 {@code drain} 一次取结果——
     * 而环形缓冲只装得下大約四分之一秒，于是循环一 break，<strong>移调后的伴奏只有开头
     * 约 11025 帧，其余全是静音</strong>：融合期从 0.25 秒起伴奏整个消失，只剩人声。
     * 实测（44.1 kHz、1 秒窗口、真 DSP）：
     * {@code bInst} 头 11025 帧 RMS 0.122，后三段全是 0.000。
     *
     * <p>正确用法与 {@link Deck} 的渲染环一致：能塞就塞、塞不动就拉，拉完再塞，
     * 直到喂完全部输入；输入喂完之后改用 {@link PitchShifter#drain}（它按静音补齐
     * 缺失的未来输入，可续做）。目标是自己要的那 {@code n} 帧，一帧不丢。
     */
    /**
     * 离线移调一条立体声（时长不变、**与原素材对齐**）。
     *
     * <h3>为什么要对齐：一个真听得到的 bug</h3>
     *
     * <p>用户报「Cruel Summer → Symphony 几乎每次都炸，别的歌没事」——杂音 + 卡顿。
     * 根因是变调器**有延迟**：移调后的伴奏会比<b>同一首歌</b>的人声晚/早一截混出去，
     * 一首歌被拆成两半错开叠回来，听感就是梳状滤波（发闷、毛刺、糊），
     * 鼓点这类强瞬态素材最明显（那两首正好都是）。
     *
     * <p>延迟量**不能写常数**：`PitchShifter.latencyFrames()`（grain − hop）是
     * <b>流式契约下要多喂多少输入</b>，与"波形被推后了多少帧"不是同一个数——
     * 实测按它补会过头约 14 ms。也不能拿素材自己做互相关：移调会改变波形，
     * 脉冲类素材的相关峰是假的（实测过：两个脉冲素材量出 −3602 帧这种离谱值）。
     *
     * <p>所以：**用一段合成探针实测这个变调器配置的偏移**（见 {@link #shifterLag}），
     * 之后所有素材都按它对齐。
     */
    private float[][] shifted(float[][] pcm, float semitones) {
        int lag = Math.max(0, shifterLag(semitones));
        return shiftRaw(pcm, semitones, lag, lag);
    }

    /**
     * 两次调用之间的偏移缓存（同一个 wrapper 只服务一次过渡，量一次就够）。
     * 元素是 {@code {semitonesBits, lag}}。
     */
    private volatile long shifterLagKey = Long.MIN_VALUE;
    private volatile int shifterLagFrames;

    /** 这个变调量下的实际偏移（帧，正 = 输出被推后）。用探针实测，见 {@link #shifted}。 */
    private int shifterLag(float semitones) {
        long key = (long) Float.floatToIntBits(semitones);
        if (shifterLagKey == key) {
            return shifterLagFrames;
        }
        int measured = measureShifterLag(semitones);
        shifterLagFrames = measured;
        shifterLagKey = key;
        Logger.debug("[qplayer-engine] 声波重合：变调器偏移实测 {} 帧（{} ms，{} 半音）",
                Integer.valueOf(measured),
                String.format(java.util.Locale.ROOT, "%.2f",
                        Double.valueOf(measured * 1000.0 / Math.max(1, rate))),
                Float.valueOf(semitones));
        return measured;
    }

    /**
     * 拿一段合成探针（0.1 秒处一个脉冲）过一遍变调器，量出输出里那个脉冲跑到哪儿了。
     *
     * <p>用**能量阈值 + 重心**，不用互相关：移调会把脉冲摊开、波形也变了，
     * 但"能量出现在第几帧"是稳的。
     */
    private int measureShifterLag(float semitones) {
        int probeFrames = (int) Math.max(1L, Math.round(0.5 * rate));
        int pulseAt = (int) Math.round(0.1 * rate);
        float[][] probe = new float[2][probeFrames];
        for (int i = -4; i <= 4; i++) {
            int at = pulseAt + i;
            if (at >= 0 && at < probeFrames) {
                probe[0][at] = 0.6f * (1.0f - Math.abs(i) / 6.0f);
            }
        }
        float[][] shiftedProbe = shiftRaw(probe, semitones, 0, 0);
        long best = -1L;
        float peak = 0.0f;
        for (int i = 0; i < shiftedProbe[0].length; i++) {
            float v = Math.abs(shiftedProbe[0][i]);
            if (v > peak) {
                peak = v;
                best = i;
            }
        }
        if (best < 0L || peak <= 1e-6f) {
            return 0;
        }
        // 重心：脉冲被颗粒法摊开，取 ±40ms 内的能量重心比取峰值稳
        int half = (int) Math.round(0.04 * rate);
        int from = (int) Math.max(0L, best - half);
        int to = (int) Math.min(shiftedProbe[0].length, best + half);
        double num = 0.0;
        double den = 0.0;
        for (int i = from; i < to; i++) {
            double e = (double) shiftedProbe[0][i] * shiftedProbe[0][i];
            num += e * i;
            den += e;
        }
        double centroid = den > 0.0 ? num / den : best;
        return (int) Math.round(centroid - pulseAt);
    }

    /**
     * 真的过一遍变调器：喂 {@code frames + extraLead} 帧（多出来的补静音），
     * 再从输出的第 {@code skip} 帧开始取 {@code frames} 帧。
     *
     * <p>按 {@code pushFrom} 的**部分接收**语义来：能塞就塞、塞不动就拉、喂完再 drain。
     * 忽略这一点会让移调后的伴奏只产出开头一小段（那是上一个 bug）。
     */
    private float[][] shiftRaw(float[][] pcm, float semitones, int extraLead, int skip) {
        PitchShifter ps = new PitchShifter(2, rate, semitones, 4096);
        try {
            int n = pcm[0].length;
            int total = n + Math.max(0, extraLead);
            AudioBuf in = new AudioBuf(dev.t1m3.qplayer.audio.core.ChannelLayout.STEREO, total);
            for (int ch = 0; ch < 2; ch++) {
                java.nio.FloatBuffer w = in.channelWriter(ch);
                for (int i = 0; i < n; i++) {
                    w.put(i, pcm.length > ch ? pcm[ch][i] : pcm[0][i]);
                }
                for (int i = n; i < total; i++) {
                    w.put(i, 0.0f);
                }
            }
            in.setFrames(total);
            AudioBuf out = new AudioBuf(dev.t1m3.qplayer.audio.core.ChannelLayout.STEREO, total);
            int fed = 0;
            int written = 0;
            while (written < total) {
                int before = fed;
                while (fed < total) {
                    int took = ps.pushFrom(in, fed);
                    if (took <= 0) {
                        break;
                    }
                    fed += took;
                }
                int got = fed >= total ? ps.drain(out, written) : ps.pull(out, written);
                written += got;
                if (got <= 0 && fed == before) {
                    break;
                }
            }
            float[][] result = new float[2][n];
            float[] backing = out.backingArray();
            for (int ch = 0; ch < 2; ch++) {
                int base = out.channelBase(ch);
                for (int i = 0; i < n; i++) {
                    int src = i + skip;
                    result[ch][i] = src >= 0 && src < written ? backing[base + src] : 0.0f;
                }
            }
            if (written < total) {
                Logger.warn("[qplayer-engine] 移调只产出 {} / {} 帧", Integer.valueOf(written),
                        Integer.valueOf(total));
            }
            return result;
        } catch (RuntimeException e) {
            Logger.warn("[qplayer-engine] 移调失败（{}），这一条轨保持原样", e.toString());
            return pcm;
        }
    }

    /** 把一条立体声补零到指定长度（已经够长就原样返回）。 */
}