package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 均衡处理器：把一组 {@link EqBand} 编译成滤波器组，然后逐块就地处理。
 *
 * <h2>实时规则</h2>
 *
 * <p>{@link #setBands} 会分配（改参数时发生，每秒最多几次，可以接受）；
 * {@link #process} <strong>一个对象都不分配</strong>——这是泵线程的硬要求
 * （环冲只有约 200 ms，一次 GC 停顿就是一次爆音）。
 *
 * <h2>声道与中/侧</h2>
 *
 * <p>{@code STEREO} 段每声道各一份滤波器状态；{@code LEFT}/{@code RIGHT} 只有那一路。
 * {@code MID}/{@code SIDE} 段逐样本做一次 M/S 变换、只过滤中或侧、再变回来：
 * 修人声用 MID，扩声场用 SIDE——这是工作站 EQ 的常规用法，也是「只把左右加起来过滤」
 * 做不到的（后者会把两侧强行抹成一样）。
 *
 * <h2>为什么系数只在这里算一次</h2>
 *
 * <p>滤波器系数是频率/Q/增益的函数，与音频无关。参数没变就不该重算
 * （每次重算要 pow/sin/cos，一块 1024 帧要几千次）；换了参数则用
 * {@code update*} 系列<strong>保留内部状态</strong>地换系数，所以拖动滑杆时听不到咔哒。
 */
public final class EqProcessor {

    /** 巴特沃斯级联的 Q（12/24/48 dB/oct 各一组）。 */
    private static final double[] Q1 = {0.7071067811865476};
    private static final double[] Q2 = {0.541196100146197, 1.306562964876377};
    private static final double[] Q4 = {0.509795579104159, 0.601344886935045,
            0.899976223136416, 2.562915447741505};

    private final int rate;
    private final int channels;

    /** 当前编译结果（整体替换；泵线程读它的时候不加锁）。 */
    private volatile Compiled compiled = Compiled.EMPTY;

    public EqProcessor(int rate, int channels) {
        this.rate = Math.max(rate, 1);
        this.channels = Math.max(channels, 1);
    }

    /** 采样率（换设备时重建）。 */
    public int rate() {
        return rate;
    }

    /**
     * 换一组段。
     *
     * <p>能沿用旧状态的滤波器会沿用（按「下标 + 类型 + 声道」配对）：拖动频率/增益时
     * 状态连续，不会有咔哒；类型或声道换了就重新起振（那种情况下旧状态本来就是错的）。
     */
    public void setBands(List<EqBand> bands) {
        Compiled old = compiled;
        // 用数组而不是 List：逐块遍历时 for-each 会分配迭代器（实测每块 148 字节，
        // 200 块就是 30 KB——判据当场照出来的），下标循环一个对象都不分配。
        List<Plan> built = new ArrayList<Plan>();
        boolean any = false;
        int index = 0;
        for (EqBand band : bands == null ? Collections.<EqBand>emptyList() : bands) {
            if (index >= StudioRack.MAX_BANDS) {
                break;
            }
            Plan previous = index < old.plans.length ? old.plans[index] : null;
            Plan plan = Plan.compile(rate, channels, band, previous);
            built.add(plan);
            any = any || plan.active;
            index++;
        }
        compiled = new Compiled(built.toArray(new Plan[built.size()]), any);
    }

    /** 现在有没有段在动采样。 */
    public boolean active() {
        return compiled.any;
    }

    /** 正在工作的段数（日志用）。 */
    public int activeBands() {
        int n = 0;
        Plan[] plans = compiled.plans;
        for (int i = 0; i < plans.length; i++) {
            if (plans[i].active) {
                n++;
            }
        }
        return n;
    }

    /** 清空所有滤波器的状态（跳转/换曲后调用，免得漏出上一段的余振）。 */
    public void reset() {
        Plan[] plans = compiled.plans;
        for (int i = 0; i < plans.length; i++) {
            plans[i].reset();
        }
    }

    /** 就地处理一块。空闲时直接返回（一个采样都不碰）。 */
    public void process(AudioBuf buf) {
        Compiled c = compiled;
        if (!c.any) {
            return;
        }
        int frames = buf.frames();
        if (frames <= 0) {
            return;
        }
        float[] backing = buf.backingArray();
        int baseL = buf.channelBase(0);
        int baseR = buf.channelBase(buf.channels() > 1 ? 1 : 0);
        boolean stereo = buf.channels() > 1;
        Plan[] plans = c.plans;
        for (int pi = 0; pi < plans.length; pi++) {
            Plan p = plans[pi];
            if (!p.active) {
                continue;
            }
            if (p.target == ChannelTarget.MID || p.target == ChannelTarget.SIDE) {
                if (!stereo) {
                    // 单声道素材上「中」就是它自己，「侧」恒为零——如实跳过，不做无意义的运算
                    if (p.target == ChannelTarget.SIDE) {
                        continue;
                    }
                    for (int i = 0; i < frames; i++) {
                        backing[baseL + i] = (float) p.filter(0, 0, backing[baseL + i]);
                    }
                    continue;
                }
                boolean mid = p.target == ChannelTarget.MID;
                for (int i = 0; i < frames; i++) {
                    double l = backing[baseL + i];
                    double r = backing[baseR + i];
                    double m = l + r;
                    double side = l - r;
                    if (mid) {
                        m = p.filter(0, 0, m);
                    } else {
                        side = p.filter(0, 0, side);
                    }
                    backing[baseL + i] = (float) ((m + side) * 0.5);
                    backing[baseR + i] = (float) ((m - side) * 0.5);
                }
                continue;
            }
            int first = p.target == ChannelTarget.RIGHT ? 1 : 0;
            int last = (p.target == ChannelTarget.STEREO && stereo) ? 2 : first + 1;
            if (stereo) {
                for (int ch = first; ch < last; ch++) {
                    int base = buf.channelBase(ch);
                    for (int i = 0; i < frames; i++) {
                        backing[base + i] = (float) p.filter(ch, 0, backing[base + i]);
                    }
                }
            } else if (first == 0) {
                // 单声道素材：左右两路本来就是同一份，只处理一次
                for (int i = 0; i < frames; i++) {
                    backing[baseL + i] = (float) p.filter(0, 0, backing[baseL + i]);
                }
            }
        }
    }

    /** 一块的编译结果。 */
    private static final class Compiled {
        static final Compiled EMPTY = new Compiled(new Plan[0], false);

        final Plan[] plans;
        final boolean any;

        Compiled(Plan[] plans, boolean any) {
            this.plans = plans;
            this.any = any;
        }
    }

    /** 一个段编译出来的滤波器组：{@code filters[级][声道]}。 */
    private static final class Plan {

        final BandType type;
        final ChannelTarget target;
        final int stages;
        final boolean active;
        final StudioBiquad[][] filters;

        private Plan(BandType type, ChannelTarget target, int stages, boolean active,
                     StudioBiquad[][] filters) {
            this.type = type;
            this.target = target;
            this.stages = stages;
            this.active = active;
            this.filters = filters;
        }

        static Plan compile(int rate, int channels, EqBand band, Plan previous) {
            ChannelTarget target = band.channel;
            // 几路滤波器 = 这一段的处理会用到的**最大物理声道下标 + 1**。
            // 界面上的 RIGHT 段是「只过滤右声道」，而 `process()` 传进来的是**物理声道号**
            // （右 = 1），所以它同样需要两路（左路那一格不会被用到）。
            // 这里原来只给 STEREO 开两路，于是「把一段设在右声道」会在第一个采样就
            // `filters[s][1]` 越界（长度 1）——2026-09-26 用户报的
            // 「生产者线程异常：ArrayIndexOutOfBoundsException: Index 1 out of bounds
            // for length 1」就是它（界面上「声道」那一格点到「右」就必然发生）。
            boolean usesRight = target == ChannelTarget.STEREO || target == ChannelTarget.RIGHT;
            int voices = usesRight && channels > 1 ? 2 : 1;
            int stages = band.stages();
            boolean active = !band.isTransparent();
            StudioBiquad[][] filters = new StudioBiquad[stages][voices];
            double[] qs = qsFor(band, stages);
            for (int s = 0; s < stages; s++) {
                for (int v = 0; v < voices; v++) {
                    StudioBiquad reuse = reuseOf(previous, band, s, v);
                    StudioBiquad f = design(rate, band, qs[s]);
                    if (reuse != null) {
                        reuse.updateFrom(f);
                        f = reuse;
                    }
                    filters[s][v] = f;
                }
            }
            return new Plan(band.type, target, stages, active, filters);
        }

        /** 能沿用旧状态就沿用（下标/类型/声道都对得上、级数也一样）。 */
        private static StudioBiquad reuseOf(Plan previous, EqBand band, int stage, int voice) {
            if (previous == null || previous.type != band.type || previous.target != band.channel
                    || previous.stages != band.stages()) {
                return null;
            }
            if (stage >= previous.filters.length || voice >= previous.filters[stage].length) {
                return null;
            }
            return previous.filters[stage][voice];
        }

        /** 一块级联滤波：{@code voice} 是这一声道的滤波器下标。 */
        double filter(int voice, int stage, double x) {
            double y = x;
            for (int s = 0; s < stages; s++) {
                y = filters[s][voice].process(y);
            }
            return y;
        }

        void reset() {
            for (StudioBiquad[] row : filters) {
                for (StudioBiquad f : row) {
                    f.reset();
                }
            }
        }

        /**
         * 每一级用的 Q。
         *
         * <p><strong>只有高/低通用巴特沃斯级联的固定 Q</strong>（斜率就是靠它凑出来的：
         * 12/24/48 dB/oct = 1/2/4 级级联）。其余类型必须用<strong>用户设的那个 Q</strong>——
         * 我第一版在这里一律取级联值，于是所有段的 Q 都被悄悄改成 0.707：一个 Q=6 的陷波
         * 变成了 Q=0.707 的宽带凹陷（700 Hz 处实测 −6.8 dB，而解析式是 −0.22 dB）。
         * 这正是「判据必须用独立的尺子」的理由——拿 {@link EqResponse} 当尺子的话，
         * 它和处理器共用这段代码，会一起错、一起对。
         */
        private static double[] qsFor(EqBand band, int stages) {
            if (!band.type.usesSlope()) {
                return new double[]{band.q};
            }
            if (stages >= 4) {
                return Q4;
            }
            return stages == 2 ? Q2 : Q1;
        }

        private static StudioBiquad design(int rate, EqBand band, double q) {
            float f = (float) band.freqHz;
            float qq = (float) q;
            float g = (float) band.gainDb;
            switch (band.type) {
                case LOW_SHELF:
                    return StudioBiquad.lowShelf(rate, f, qq, g);
                case HIGH_SHELF:
                    return StudioBiquad.highShelf(rate, f, qq, g);
                case HIGH_PASS:
                    return StudioBiquad.highPass(rate, f, qq);
                case LOW_PASS:
                    return StudioBiquad.lowPass(rate, f, qq);
                case BAND_PASS:
                    return StudioBiquad.bandPass(rate, f, qq);
                case NOTCH:
                    return StudioBiquad.notch(rate, f, qq);
                case ALL_PASS:
                    return StudioBiquad.allPass(rate, f, qq);
                case PEAK:
                default:
                    return StudioBiquad.peaking(rate, f, qq, g);
            }
        }
    }
}
