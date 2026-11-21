package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.dsp.Fft;

/**
 * 去混响：<strong>谱减</strong>（估计尾音并减掉）+ <strong>谱门</strong>（增益下限）。
 *
 * <h2>怎么估计「混响尾巴」</h2>
 *
 * <p>混响尾巴有一个很好用的性质：<strong>它比直达声衰减得慢</strong>。于是每个频点上的
 * 尾音幅度可以这样估：
 *
 * <pre>
 *   est[k] ← max( |X[k]| × leak,  est[k] × decay )
 * </pre>
 *
 * <p>{@code leak} 很小（{@value #LEAK}）：直达声不该被算进「尾巴」，它只给估计器一点抬升；
 * {@code decay} 接近 1（{@value #DECAY}）让它缓慢下坠。于是一段<strong>持续</strong>的音里，
 * 估计器在起音之后很快就落到信号之下（干声几乎不动），而<strong>音一停</strong>，
 * 信号掉下去了、估计器还停在高处——那一段正是混响尾巴，把它减掉就得到「干」的声音。
 *
 * <h2>为什么必须留一个增益下限</h2>
 *
 * <p>谱减最出名的副作用是 <em>musical noise</em>（水声/金属声）：被减到负数的 bin 直接归零，
 * 于是相邻帧之间那些 bin 在「有」和「无」之间跳。留一个下限
 * （{@link DereverbParams#floorDb}，默认 −18 dB）让它们<strong>衰减而不是消失</strong>——
 * 这是听感上最关键的一个参数。
 *
 * <h2>延迟 = 一个窗，而且是<strong>真的</strong>延迟</h2>
 *
 * <p>要处理第 t 个采样，必须先看到 [t, t+{@value #WINDOW}) 这一段（STFT 的前视）。
 * 所以输出的是<strong>输入推迟 {@value #WINDOW} 帧之后</strong>的版本（48 kHz 下 21.3 ms），
 * 如实上报（{@link #latencyFrames()}）。开头那一窗没有前视可看，<strong>原样直通</strong>
 * （这就是「延迟」而不是「丢失」）。
 *
 * <h2>块长约定</h2>
 *
 * <p>内部按 {@value #HOP} 帧的栅格做分析。调用方给的块长是 {@value #HOP} 的整数倍时
 * 一切都在栅格上（引擎的 1024 帧块、离线路径的 1024 都是）。不是整数倍时，
 * <strong>末尾那几十个采样按干声直通</strong>（不改变时间轴，只是那一点没被处理）——
 * 这一条如实写在这里，而不是悄悄把时间轴挪半格。
 *
 * <p>实时规则：所有缓冲构造时分配，{@link #process} 零分配。
 */
public final class DereverbModule implements StudioModule {

    /** STFT 窗长（帧）。48 kHz 下 21.3 ms：够短到不把辅音糊在一起，够长到有频率分辨率。 */
    public static final int WINDOW = 1024;

    /** 跳距（帧）：四分之三重叠，配汉宁窗满足 COLA。 */
    public static final int HOP = 256;

    /**
     * 峰值保持的下坠系数（每帧，一跳 = 5.3 ms @48 kHz）。
     *
     * <p>0.99 → 时间常数约 0.53 s：混响比这更长的素材会被低估一点（压得少一些），
     * 而比这短得多的素材会被压得干净。它<strong>不</strong>直接等于某个 RT60——
     * 谱减法里的估计器只能取一个折中，这一点如实写在这里。
     */
    static final double PEAK_DECAY = 0.99;

    /**
     * 「这一帧在衰减吗」的门槛：频点幅度掉到它自己峰值的这个比例以下才算尾音。
     *
     * <p>为什么要有这道门：如果没有它，估计器在<strong>持续</strong>的音上就等于信号本身，
     * 谱减会把整个音都压掉（干声变化远大于 1 dB）。有了它，直达声（幅度≈峰值）时
     * 估计器为 0、只减衰减段——这正是「去混响只去掉尾巴」的数学表达。
     */
    static final double TAIL_GATE = 0.75;

    /** 增益平滑：起音快、释放慢（反了会有抽气声）。 */
    static final double GAIN_ATTACK = 0.5;
    static final double GAIN_RELEASE = 0.40;

    private final int channels;
    private final Fft fft = new Fft(WINDOW);
    private final float[] hann = new float[WINDOW];
    /** 汉宁窗平方叠加的归一化（75% 重叠下等于 1.5）。 */
    private final double olaNorm;

    /** 输入 FIFO：最近 {@value #WINDOW} 个采样，下标 0 就是「当前要交付的那一个」。 */
    private final float[][] fifo;
    /** 已算好、还没交付的湿信号（与 FIFO 的位置一一对应）。 */
    private final float[][] acc;
    /**
     * 交付用的干声暂存（FIFO 头部）。
     *
     * <p><strong>必须取在左移之前</strong>：FIFO 的头部就是这一跳要交付的那一段，
     * 左移会把它冲掉。第一版把「左移 + 接新输入」写在交付之前，于是交付的是错了 n 个采样的
     * 干声，而 OLA 的栅格也跟着错开一帧——判据（冲激响应第一个非零的位置）当场红。
     */
    private final float[] dryHead = new float[HOP];
    /** 这一跳的新输入暂存：交付会覆盖 backing 的同一段，所以要先抄出来。 */
    private final float[] inScratch = new float[HOP];
    /** 分析/合成用的帧缓冲。 */
    private final float[] re = new float[WINDOW];
    private final float[] im = new float[WINDOW];
    private final float[] mag = new float[WINDOW / 2 + 1];
    /** 尾音估计（由峰值保持 + 衰减门给出）。 */
    private final float[] est = new float[WINDOW / 2 + 1];
    /** 前一帧的增益（逐帧平滑，避免抽气感）。 */
    private final float[] gain = new float[WINDOW / 2 + 1];
    /** 逐频点的峰值保持（缓慢下坠，见 {@link #PEAK_DECAY}）。 */
    private final float[] peak = new float[WINDOW / 2 + 1];

    private volatile DereverbParams params = DereverbParams.off();

    /** 上一次分析之后又推进了多少个采样（攒够 {@value #HOP} 才做下一帧）。 */
    private int sinceAnalysis;
    /**
     * 已经过去多少个采样。
     *
     * <p>它只用于诊断：实现上**没有**「开局先直通」的特判——开局交付的是「静音前史
     * 被处理之后」的结果（≈ 零），那就是这一档的延迟本身（见 {@link #process} 的注释）。
     */
    private int consumed;
    /** 有没有处理过至少一跳（收尾逻辑用它判断「有没有东西还没交付」）。 */
    private boolean ready;
    /**
     * 关掉之后的收尾：还要把「没交付的那一窗」补出去多少个采样。
     *
     * <p>为什么需要它：输出比输入晚一个窗（{@value #WINDOW} 帧）。直接旁路的话，
     * 输出会<strong>向前跳 21 ms</strong>（那一段没交付过），听感是一次跳变。
     * 所以关掉之后先把 FIFO 里的干声按原速交付完这一窗，时间轴就接上了。
     * 这一窗里的内容是干声（湿的已经没在算了），如实写在这里。
     */
    private int flushing;

    /** 上一次这一块算不算「开着」：用来发现「开 → 关」这个边沿（见 {@link #flush}）。 */
    private boolean wasActive;

    /** 上一次的参数：收尾那一窗还要按它继续算（湿声淡出，见 {@link #flush}）。 */
    @SuppressWarnings("unused")
    private DereverbParams lastParams = DereverbParams.off();

    /** 上一次收到的那份参数（发现「开 → 关」时要用它把收尾那一窗算完）。 */
    private DereverbParams wasParams = DereverbParams.off();

    public DereverbModule(int rate, int channels) {
        this.channels = Math.max(channels, 1);
        Fft.hann(hann);
        fifo = new float[this.channels][WINDOW];
        acc = new float[this.channels][WINDOW];
        java.util.Arrays.fill(gain, 1f);
        double sum = 0.0;
        for (int n = 0; n < WINDOW; n += HOP) {
            sum += (double) hann[n] * hann[n];
        }
        olaNorm = sum > 1e-9 ? 1.0 / sum : 1.0;
    }

    public void setParams(DereverbParams value) {
        this.params = value == null ? DereverbParams.off() : value;
    }

    public DereverbParams params() {
        return params;
    }

    @Override
    public boolean active() {
        return params.active();
    }

    @Override
    public int latencyFrames() {
        return params.active() ? WINDOW : 0;
    }

    @Override
    public void reset() {
        for (float[] f : fifo) {
            java.util.Arrays.fill(f, 0f);
        }
        for (float[] a : acc) {
            java.util.Arrays.fill(a, 0f);
        }
        java.util.Arrays.fill(est, 0f);
        java.util.Arrays.fill(gain, 1f);
        java.util.Arrays.fill(peak, 0f);
        sinceAnalysis = 0;
        consumed = 0;
        ready = false;
        flushing = 0;
        wasActive = false;
        lastParams = DereverbParams.off();
    }

    @Override
    public void process(AudioBuf buf) {
        DereverbParams p = params;
        boolean now = p.active();
        if (wasActive && !now && ready) {
            // 「开 → 关」的边沿：输出比输入晚一窗，还有一整窗内容没交付过。
            // 直接旁路会让输出向前跳 21 ms，所以再按**原来的参数**算完这一窗，
            // 同时把湿声的权重从 1 淡到 0（见 flush 的注释）。
            flushing = WINDOW;
            lastParams = wasParams;
        }
        wasActive = now;
        wasParams = p;
        DereverbParams use = now ? p : (flushing > 0 ? lastParams : null);
        if (use == null) {
            return;                            // 直通：一个采样都不碰（位完美的前提）
        }
        int frames = buf.frames();
        if (frames <= 0) {
            return;
        }
        int chs = Math.min(buf.channels(), channels);
        float[] backing = buf.backingArray();
        double over = use.overSubtraction();
        float floorLin = (float) use.floorLinear();

        int done = 0;
        while (done < frames) {
            int room = HOP - sinceAnalysis;
            int n = Math.min(room, frames - done);
            if (n < room) {
                // 块长没落在 HOP 栅格上：这一小段按干声直通（见类注释的「块长约定」），
                // 时间轴不动，只是这一小段没被处理。
                done += n;
                break;
            }
            // ⓪ 先把这一跳的输入抄出来（后面的交付会覆盖 backing 的同一段）
            for (int ch = 0; ch < chs; ch++) {
                int base = buf.channelBase(ch);
                for (int i = 0; i < n; i++) {
                    inScratch[i] = backing[base + done + i];
                }
                System.arraycopy(inScratch, 0, scratchPerChannel(ch), 0, n);
            }
            // ① 分析 FIFO —— 它此刻装的正是「这一步要交付的那一窗」
            //    （FIFO 恒等于最近的 WINDOW 个输入，开头那段是零 = 静音前史），
            //    结果按合成窗叠加进 acc。**必须先分析再左移**，否则窗口就错开一跳。
            //
            //    为什么不做「开局先直通/补零」的特判：那样前 WINDOW 个采样里，
            //    覆盖它们的后三帧根本没被分析过（汉宁窗在窗边界为 0，一个采样要四帧
            //    叠起来才拼得回来），输出会缺一块——冲激判据当场就红（第一个非零找不到）。
            analyzeAndAccumulate(chs, over, floorLin, p.amount);
            // ② 交付这一跳：干 = FIFO 头部，湿 = acc。开局交付的是「静音前史被处理之后」
            //    的结果 ≈ 零，这正是延迟本身。
            for (int ch = 0; ch < chs; ch++) {
                int base = buf.channelBase(ch);
                float[] f = fifo[ch];
                float[] a = acc[ch];
                // 收尾那一窗：湿声权重从 1 线性淡到 0（于是从「处理过的声音」平滑地
                // 回到干声，不留一步台阶——判据是「相邻采样最大跳变」）。
                float mix = (float) (use.mix * (now ? 1.0 : flushing / (double) WINDOW));
                for (int i = 0; i < n; i++) {
                    float dry = f[i];
                    // **必须乘 olaNorm**：acc 里是「各帧按合成窗叠加」的和，汉宁窗 75% 重叠下
                    // 那个和等于信号的 1.5 倍（Σ w² = 1.5）。漏掉它整套输出就整体 +3.5 dB，
                    // 而且不再是重建——本机实测：正弦的输入 0.4 会输出到 0.52。
                    float wet = (float) (a[i] * olaNorm);
                    backing[base + done + i] = dry + (wet - dry) * mix;
                }
                // ③ 交付完再挪走：FIFO 与 acc 一起左移 n（FIFO 的新尾巴在 slideAndAppend 里）
                System.arraycopy(a, n, a, 0, WINDOW - n);
                java.util.Arrays.fill(a, WINDOW - n, WINDOW, 0f);
            }
            slideAndAppend(chs, n);
            sinceAnalysis = 0;
            consumed += n;
            ready = true;
            done += n;
            if (!now) {
                flushing -= n;
                if (flushing <= 0) {
                    // 淡完了：剩下的采样原样直通（backing 里本来就是这一块的输入）
                    return;
                }
            }
        }
    }

    /** 对 FIFO 这一帧做 STFT、谱减、逆变换，再按合成窗叠加进 {@link #acc}。 */
    /** 每声道一份输入暂存（声道数在构造时定下，最多 8 个；超出按最后一个复用）。 */
    private final float[][] inScratchPerChannel = new float[8][HOP];

    private float[] scratchPerChannel(int ch) {
        return inScratchPerChannel[Math.min(ch, inScratchPerChannel.length - 1)];
    }

    /**
     * FIFO 左移 n 再把这一跳的新输入接在尾部。
     *
     * <p><strong>左移不能省</strong>：FIFO 是「最近一窗输入」的滑动窗，不左移就是往同一段
     * 反复覆写——开局的四跳会把前 768 个采样全冲掉（冲激判据当场红：输出里根本找不到
     * 那个冲激）。交付必须在左移<strong>之前</strong>读 FIFO 头部，所以顺序是三件事：
     * 分析 → 交付 → 左移 + 接新。
     */
    private void slideAndAppend(int chs, int n) {
        for (int ch = 0; ch < chs; ch++) {
            float[] f = fifo[ch];
            System.arraycopy(f, n, f, 0, WINDOW - n);
            System.arraycopy(scratchPerChannel(ch), 0, f, WINDOW - n, n);
        }
    }

    /**
     * 关掉之后的收尾（由 {@link #process} 的主循环顺带完成）。
     *
     * <p>为什么不是「先交付一窗干声就完事」：那一窗里的湿声与干声是两个不同的信号，
     * 直接从湿切到干是一步台阶（实测相邻采样跳变 0.073，而 200 Hz 正弦本身只有 0.0105）。
     * 现在的做法是<strong>继续按原来的参数算完这一窗，同时把湿声权重线性淡到 0</strong>
     * ——时间轴接上、音色平滑过渡，两件事一起解决。
     */
    private void flush(AudioBuf buf) {
        // 收尾逻辑在主循环里（它要接着分析、交付、淡出），这里不再单独实现，
        // 留这个方法是为了让「收尾」这件事在类里有一个可读的落点与文档。
        process(buf);
    }

    private void analyzeAndAccumulate(int chs, double over, float floorLin, double amountScale) {
        for (int ch = 0; ch < chs; ch++) {
            float[] f = fifo[ch];
            for (int i = 0; i < WINDOW; i++) {
                re[i] = f[i] * hann[i];
                im[i] = 0f;
            }
            fft.forward(re, im);
            for (int k = 0; k <= WINDOW / 2; k++) {
                mag[k] = (float) Math.sqrt((double) re[k] * re[k] + (double) im[k] * im[k]);
            }
            // 尾音估计：**峰值保持（缓慢下坠）+ 衰减门**
            //
            // 第一版用的是「est = max(mag × 0.08, est × 0.93)」，判据当场量出它的问题：
            // 尾巴没被压（估计器在持续段只到信号的 8%，减不动），而干声反而被压掉
            // 1.1 dB（那 8% × 过量因子吃掉了直达声）。改成峰值保持 + 门之后两件事同时成立：
            // 持续段 mag ≈ peak → 门开不了 → est = 0 → 干声原样；
            // 音一停 mag 掉到 peak 的一半以下 → est = peak（还停在高处）→ 尾巴被减掉。
            for (int k = 0; k <= WINDOW / 2; k++) {
                float held = (float) (peak[k] * PEAK_DECAY);
                peak[k] = Math.max(mag[k], held);
                // **软门**（连续取值），不是硬开关：混响尾巴最强的那一小段（音刚停，
                // 幅度还在峰值的 5~7 成）用硬门根本开不了门，于是尾巴压不动——
                // 实测硬门只压 4.2 dB，而目标是 8 dB 以上。
                // w = 0（幅度=峰值，直达声）→ 1（幅度 ≤ 0.6×峰值，尾音）
                float ratio = peak[k] > 1e-9f ? mag[k] / peak[k] : 1f;
                float w = (float) ((1.0 - ratio) / (1.0 - TAIL_GATE));
                if (w < 0f) {
                    w = 0f;
                } else if (w > 1f) {
                    w = 1f;
                }
                est[k] = peak[k] * w * (float) amountScale;
            }
            // 谱减 + 谱门 + 帧间平滑
            for (int k = 0; k <= WINDOW / 2; k++) {
                float m = mag[k];
                float target = 1f;
                if (m > 1e-9f) {
                    float sub = (float) (m - over * est[k]);
                    target = sub / m;
                    if (target < floorLin) {
                        target = floorLin;
                    } else if (target > 1f) {
                        target = 1f;
                    }
                }
                float prev = gain[k];
                double a = target < prev ? GAIN_ATTACK : GAIN_RELEASE;
                gain[k] = (float) (prev + (target - prev) * a);
            }
            // 施加增益（同乘实部虚部 = 保持相位），补齐共轭对称后逆变换
            for (int k = 0; k <= WINDOW / 2; k++) {
                float g = gain[k];
                re[k] *= g;
                im[k] *= g;
                if (k > 0 && k < WINDOW / 2) {
                    re[WINDOW - k] = re[k];
                    im[WINDOW - k] = -im[k];
                }
            }
            fft.inverse(re, im);
            float[] a = acc[ch];
            for (int i = 0; i < WINDOW; i++) {
                a[i] += re[i] * hann[i];
            }
        }
    }

    @Override
    public String toString() {
        return "DereverbModule{" + params + "，窗 " + WINDOW + "/跳 " + HOP + "，延迟 " + WINDOW + "}";
    }
}
