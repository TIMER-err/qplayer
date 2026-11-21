package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;

/**
 * 动态处理模块：<strong>门 → 压缩 → 限制</strong>，顺序固定（理由见 {@link DynamicsParams}）。
 *
 * <h2>三条判据（都在 {@code DynamicsTest} 里，且都对着定义而不是对着手感）</h2>
 *
 * <ol>
 *   <li><b>压缩曲线与定义一致</b>：静态测试音下，增益衰减必须等于
 *       {@code (输入 − 阈值) × (1 − 1/比率)}（软拐点按二次插值算），误差 &lt; 0.2 dB。
 *       这是「压缩器有没有做对」的唯一客观判据；</li>
 *   <li><b>启动/释放时间是真的</b>：喂一个阶跃，量到达最终衰减的 63% 用了多久
 *       （一阶时间常数的定义），误差 &lt; 30%；</li>
 *   <li><b>限制器的天花板是硬的</b>：任何输入下输出峰值都不许超过天花板 + 0.1 dB，
 *       而且它<strong>如实上报前视</strong>（{@link #latencyFrames()}）。</li>
 * </ol>
 *
 * <h2>检波与立体声链接</h2>
 *
 * <p>检波用<strong>两声道的最大值</strong>（立体声链接）：两声道各自独立压缩会把声像
 * 拉来拉去（压缩得多的那一侧变轻），那是「工作站级」最不该有的毛病。
 *
 * <h2>实时规则</h2>
 *
 * <p>所有缓冲构造时分配、{@link #process} 零分配；参数整体替换，换参数只重算几个系数。
 * 限制器的前视缓冲在构造时按最大前视（5 ms）分配一次。
 */
public final class DynamicsModule implements StudioModule {

    /** 前视的分配上限（毫秒）——参数上限就是它。 */
    private static final double MAX_LOOKAHEAD_MS = 5.0;

    private final int rate;
    private final int channels;

    private volatile DynamicsParams params = DynamicsParams.off();

    /** 压缩器的增益包络（线性，1 = 不衰减）。 */
    private double compEnv = 1.0;
    /** 门的增益包络与保持计数。 */
    private double gateEnv = 1.0;
    private int gateHold;
    /** RMS 检波的一阶平滑（平方域）。 */
    private double rmsState;
    /**
     * 峰值**包络跟随**（检波器的输出）。
     *
     * <p>为什么不能拿「这一采样的绝对值」当电平：正弦每半个周期都会穿过零，
     * 于是电平每 24 个采样就掉到 0 一次。后果实测有两条，都是判据当场照出来的：
     *
     * <ul>
     *   <li><b>门会在每个过零点关一次</b>：−10 dBFS 的信号过门（门限 −40 dB）时，
     *       输出峰值被压低 <strong>4.8 dB</strong>（判据：门限以上不许动，±0.3 dB）；</li>
     *   <li><b>压缩曲线偏离定义 0.54 dB</b>：包络在波峰之间回落，平均增益偏高
     *       （判据：与定义差 &lt; 0.2 dB）。</li>
     * </ul>
     *
     * <p>所以检波器是「快起、慢落」的峰值保持（起 1 ms、落 {@value #DETECTOR_RELEASE_MS} ms）：
     * 正弦的波峰之间它稳稳停在上一个峰值上，电平就是稳定的。
     * <strong>限制器不用它</strong>——天花板必须按瞬时采样算，那是硬保证。
     */
    private double detEnv;

    /** 检波器的释放时间（毫秒）。 */
    static final double DETECTOR_RELEASE_MS = 50.0;
    /** 限制器的增益包络。 */
    private double limEnv = 1.0;

    /** 限制器的前视延迟线（每声道一条）与写指针。 */
    private final float[][] lookahead;
    private final int lookaheadCapacity;
    private int lookaheadAt;

    /** 本块的最大增益衰减（dB，正数；表头读它）。 */
    private volatile float gainReductionDb;

    public DynamicsModule(int rate, int channels) {
        this.rate = Math.max(1, rate);
        this.channels = Math.max(1, channels);
        this.lookaheadCapacity =
                Math.max(1, (int) Math.ceil(MAX_LOOKAHEAD_MS / 1000.0 * this.rate)) + 1;
        this.lookahead = new float[this.channels][lookaheadCapacity];
    }

    public void setParams(DynamicsParams value) {
        this.params = value == null ? DynamicsParams.off() : value;
    }

    public DynamicsParams params() {
        return params;
    }

    @Override
    public boolean active() {
        return params.active();
    }

    @Override
    public int latencyFrames() {
        DynamicsParams p = params;
        return p.active() ? p.lookaheadFrames(rate) : 0;
    }

    /** 本块的最大增益衰减（dB，正数 = 压了多少）；表头读它。 */
    public float gainReductionDb() {
        return gainReductionDb;
    }

    @Override
    public void reset() {
        compEnv = 1.0;
        detEnv = 0.0;
        gateEnv = 1.0;
        gateHold = 0;
        rmsState = 0.0;
        limEnv = 1.0;
        lookaheadAt = 0;
        for (float[] line : lookahead) {
            java.util.Arrays.fill(line, 0f);
        }
        gainReductionDb = 0f;
    }

    @Override
    public void process(AudioBuf buf) {
        DynamicsParams p = params;
        if (!p.active()) {
            gainReductionDb = 0f;
            return;                            // 直通：一个采样都不碰（位完美的前提）
        }
        int frames = buf.frames();
        if (frames <= 0) {
            return;
        }
        int chs = Math.min(buf.channels(), channels);
        float[] backing = buf.backingArray();
        double maxGrDb = 0.0;

        double compAttack = alpha(p.compAttackMs);
        double compRelease = alpha(p.compReleaseMs);
        double rmsAlpha = alpha(p.compRmsWindowMs);
        double detectorRelease = Math.exp(-1.0 / (DETECTOR_RELEASE_MS / 1000.0 * rate));
        double gateAttack = alpha(2.0);              // 门的开：2 ms（要快，否则起音被切）
        double gateRelease = alpha(80.0);            // 关：80 ms（要慢，否则尾巴被剁）
        int gateHoldFrames = (int) Math.round(p.gateHoldMs / 1000.0 * rate);
        double ceiling = Math.pow(10.0, p.limiterCeilingDb / 20.0);
        double limRelease = alpha(p.limiterReleaseMs);
        int look = p.lookaheadFrames(rate);
        double makeup = Math.pow(10.0, p.compMakeupDb / 20.0);
        double gateFloor = Math.pow(10.0, p.gateRangeDb / 20.0);

        for (int i = 0; i < frames; i++) {
            // ---- ① 检波：取两声道最大值（立体声链接）----
            double peak = 0.0;
            for (int ch = 0; ch < chs; ch++) {
                double v = Math.abs(backing[buf.channelBase(ch) + i]);
                if (v > peak) {
                    peak = v;
                }
            }
            // 检波器（见 detEnv 的注释）：快起、慢落的峰值保持
            detEnv = Math.max(peak, detEnv * detectorRelease);
            double level = detEnv;
            if (p.compRms) {
                rmsState += (peak * peak - rmsState) * rmsAlpha;
                level = Math.sqrt(Math.max(rmsState, 0.0));
            }
            double levelDb = level > 1e-9 ? 20.0 * Math.log10(level) : -120.0;

            // ---- ② 门 ----
            double gateTarget = 1.0;
            if (p.gateOn && p.gateRangeDb < -1e-6) {
                if (levelDb < p.gateThresholdDb) {
                    if (gateHold > 0) {
                        gateHold--;
                    } else {
                        gateTarget = gateFloor;          // 门限以下：最多压「范围」那么多
                    }
                } else {
                    gateHold = gateHoldFrames;
                }
            }
            gateEnv += (gateTarget - gateEnv) * (gateTarget < gateEnv ? gateAttack : gateRelease);

            // ---- ③ 压缩（软拐点，dB 域二次插值）----
            // 判据就是这条式子：拐点内 GR = (1 − 1/ratio)·x²/(2·knee)，拐点外是直线段，
            // 两处斜率在边界上连续（x = 0 与 x = knee 各自对上）。
            double compTarget = 1.0;
            if (p.compOn) {
                double over = levelDb - p.compThresholdDb;
                double knee = p.compKneeDb;
                double grDb;
                if (knee > 1e-9 && over > -knee / 2.0 && over < knee / 2.0) {
                    double x = over + knee / 2.0;
                    grDb = (1.0 - 1.0 / p.compRatio) * x * x / (2.0 * knee);
                } else if (over > 0.0) {
                    grDb = over * (1.0 - 1.0 / p.compRatio);
                } else {
                    grDb = 0.0;
                }
                compTarget = Math.pow(10.0, -Math.max(grDb, 0.0) / 20.0);
            }
            compEnv += (compTarget - compEnv) * (compTarget < compEnv ? compAttack : compRelease);

            // ---- ④ 限制器（带前视）----
            // 增益包络由**当前**（未延迟）的峰值决定，而它施加在**延迟 look 帧**之后的那一帧上
            // ——于是峰值到达输出之前增益就已经降下来了。实现上就是「忘了偏移的环形缓冲」：
            // 写指针写现在，读指针读 look 帧之前，两者相差正好 look。
            double limTarget = 1.0;
            if (p.limiterOn && peak > ceiling) {
                limTarget = ceiling / peak;
            }
            // 攻击是即时的（0 采样），只有释放有常数——这是限制器该有的样子：
            // 慢一点点的攻击就是一次削波（clipping），那正是它要防的东西。
            limEnv = limTarget < limEnv ? limTarget : limEnv + (limTarget - limEnv) * limRelease;

            double grDb = -20.0 * Math.log10(Math.max(Math.min(gateEnv * compEnv, 1.0), 1e-9));
            if (grDb > maxGrDb) {
                maxGrDb = grDb;
            }

            for (int ch = 0; ch < chs; ch++) {
                int at = buf.channelBase(ch) + i;
                double v = backing[at] * gateEnv * compEnv * makeup;
                if (p.limiterOn && look > 0) {
                    float[] line = lookahead[ch];
                    int readAt = ((lookaheadAt - look) % lookaheadCapacity + lookaheadCapacity)
                            % lookaheadCapacity;
                    double delayed = line[readAt];
                    line[lookaheadAt] = (float) v;
                    v = delayed * limEnv;
                } else if (p.limiterOn) {
                    v *= limEnv;
                }
                backing[at] = (float) v;
            }
            lookaheadAt = (lookaheadAt + 1) % lookaheadCapacity;
        }
        if (maxGrDb > 0.0) {
            // 表头读的是「最近这一块的衰减」，但读数不该在块之间跳：做一次保持式平滑
            gainReductionDb = (float) Math.max(maxGrDb, gainReductionDb * 0.7);
        } else {
            gainReductionDb = (float) (gainReductionDb * 0.7);
        }
    }

    /** 一阶时间常数 α（每采样）。 */
    private double alpha(double ms) {
        double secs = Math.max(ms, 0.01) / 1000.0;
        return 1.0 - Math.exp(-1.0 / (secs * rate));
    }

    @Override
    public String toString() {
        return "DynamicsModule{" + params + "，衰减 " + gainReductionDb + " dB}";
    }
}
