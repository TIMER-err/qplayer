package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;

/**
 * 立体声处理：把 {@link StereoParams} 那几项落实到采样上。
 *
 * <h2>实现顺序与理由</h2>
 *
 * <ol>
 *   <li><b>M/S 变换</b>：{@code M = (L+R)/2, S = (L−R)/2}（带 0.5 系数的那一套，
 *       往返逐位相同）；</li>
 *   <li><b>宽度</b>：{@code S *= width}；</li>
 *   <li><b>低音单声道</b>：把 S 分成低/高两段（二阶巴特沃斯 LP，高频 = S − 低通输出，
 *       两者相加逐位还原 S），低频那一段<strong>归零</strong>；</li>
 *   <li><b>侧向延迟</b>：S 过一个延迟线（Haas）；</li>
 *   <li><b>声道互换</b>：M/S 域里就是 {@code S = −S}；</li>
 *   <li><b>交叉馈送</b>：对侧信号低通 + 衰减 + 一点点延迟后加过来（耳机用）。</li>
 * </ol>
 *
 * <p>中性参数时<strong>一个采样都不碰</strong>（{@link #active()} 为 false，{@code process}
 * 直接返回）——位完美承诺的一部分。
 *
 * <p>实时规则：延迟线与滤波器构造时分配，{@link #process} 零分配。
 */
public final class StereoModule implements StudioModule {

    /** 侧向延迟的最大分配（毫秒）——与 {@link StereoParams} 的上限一致。 */
    private static final double MAX_SIDE_DELAY_MS = 2.0;

    /** 交叉馈送的固定延迟（毫秒）：约 0.3 ms，模拟头宽带来的时间差。 */
    private static final double CROSSFEED_DELAY_MS = 0.3;

    private final int rate;
    private final int channels;

    private volatile StereoParams params = StereoParams.neutral();

    /**
     * 低音单声道用的<strong>Linkwitz-Riley 4 阶</strong>高通（侧信号，两级级联）。
     *
     * <p>为什么不是「低通 + 相减得到高通」（我第一版就是这么写的）：二阶巴特沃斯低通在
     * 分频点以下<strong>相位已经落后很多</strong>（120 Hz 分频、50 Hz 处落后约 45°），
     * 于是 {@code s − LP(s)} 的幅值不是 {@code 1 − |LP|}——实测 50 Hz 只压了
     * <strong>4.3 dB</strong>（判据要求 20 dB 以上），而分频点附近还冒出 +1.76 dB 的峰。
     * LR4 是一对相位匹配的滤波器（{@code (Butterworth)²}），低频抑制 24 dB/oct、
     * 通带平坦，这才是分频该有的样子。
     */
    private final StudioBiquad sideHighA;
    private final StudioBiquad sideHighB;
    /** 侧向延迟线。 */
    private final float[] sideDelay;
    /** 交叉馈送：对侧信号的低通（约 700 Hz，模拟头部遮挡）。 */
    private final StudioBiquad feedLow;
    private final StudioBiquad feedLow2;
    /**
     * 交叉馈送的两条延迟线：{@code feedDelayLeft} 存「左声道低通之后、准备送进右声道」的那一份，
     * {@code feedDelayRight} 反过来。
     *
     * <p><strong>两条线不能合成一条</strong>：交叉馈送要延迟的是<em>对侧</em>的内容，
     * 而一条单声道延迟线同一时刻只能存一路。合成一条时写进去的是本侧、取出来的还是本侧——
     * 那就成了「把自己的信号延迟后加回自己」（一个梳状滤波器），对侧一个采样都收不到。
     * 这个缺口与越界那个是一起被发现的：越界修好之后，
     * {@code StereoAndSaturationTest.crossfeedLeaksTheLeftChannelIntoTheRightAfterTheDelay}
     * 量出「右声道串音比 0.0000」——**交叉馈送从来没有真的交叉过**。
     */
    private final float[] feedDelayLeft;
    private final float[] feedDelayRight;
    /**
     * 两条延迟线<strong>各有各的写指针</strong>。
     *
     * <p>它们长度不同（侧向延迟 2 ms ≈ 97 帧、交叉馈送 0.3 ms ≈ 16 帧 @48k），
     * 共用一个指针时只能按其中一条的长度回绕——另一条的写下标就会越界。
     * 2026-09-26 收到的「生产者线程异常：ArrayIndexOutOfBoundsException: Index 16
     * out of bounds for length 16」就是它：只开交叉馈送、写的是 {@code feedDelay}
     * （长 16），却按 {@code sideDelay.length}（97）回绕，第 16 个采样当场越界、
     * 会话结束。**这个 bug 从交叉馈送写出来那天就在**，因为当时没有一条判据开过
     * {@code crossfeed > 0}（只有宽度 / 低音单声道 / 互换）。
     */
    private int sideDelayAt;
    private int feedDelayAt;

    public StereoModule(int rate, int channels) {
        this.rate = Math.max(1, rate);
        this.channels = Math.max(channels, 1);
        this.sideHighA = StudioBiquad.highPass(this.rate, 200.0, 0.7071);
        this.sideHighB = StudioBiquad.highPass(this.rate, 200.0, 0.7071);
        this.sideDelay = new float[Math.max(1,
                (int) Math.ceil(MAX_SIDE_DELAY_MS / 1000.0 * this.rate)) + 1];
        this.feedLow = StudioBiquad.lowPass(this.rate, 700.0, 0.7071);
        this.feedLow2 = StudioBiquad.lowPass(this.rate, 700.0, 0.7071);
        int feedFrames = Math.max(1,
                (int) Math.ceil(CROSSFEED_DELAY_MS / 1000.0 * this.rate)) + 1;
        this.feedDelayLeft = new float[feedFrames];
        this.feedDelayRight = new float[feedFrames];
    }

    public void setParams(StereoParams value) {
        StereoParams next = value == null ? StereoParams.neutral() : value;
        if (next.bassMonoHz > 0.0) {
            // 换系数保留状态（拖滑杆不咔哒）：与 RackProcessor 改 EQ 时同一个做法
            sideHighA.updateFrom(StudioBiquad.highPass(rate, next.bassMonoHz, 0.7071));
            sideHighB.updateFrom(StudioBiquad.highPass(rate, next.bassMonoHz, 0.7071));
        }
        this.params = next;
    }

    public StereoParams params() {
        return params;
    }

    @Override
    public boolean active() {
        return params.active();
    }

    @Override
    public int latencyFrames() {
        return params.active() ? params.sideDelayFrames(rate) : 0;
    }

    @Override
    public void reset() {
        sideHighA.reset();
        sideHighB.reset();
        feedLow.reset();
        feedLow2.reset();
        java.util.Arrays.fill(sideDelay, 0f);
        java.util.Arrays.fill(feedDelayLeft, 0f);
        java.util.Arrays.fill(feedDelayRight, 0f);
        sideDelayAt = 0;
        feedDelayAt = 0;
    }

    @Override
    public void process(AudioBuf buf) {
        StereoParams p = params;
        if (!p.active() || buf.channels() < 2) {
            return;                            // 直通（单声道素材上没有立体声可调）
        }
        int frames = buf.frames();
        if (frames <= 0) {
            return;
        }
        float[] backing = buf.backingArray();
        int baseL = buf.channelBase(0);
        int baseR = buf.channelBase(1);
        int sideDelayFrames = p.sideDelayFrames(rate);
        int feedDelayFrames = (int) Math.round(CROSSFEED_DELAY_MS / 1000.0 * rate);

        for (int i = 0; i < frames; i++) {
            float l = backing[baseL + i];
            float r = backing[baseR + i];
            // ⓪ 声道互换**在 M/S 之前**做：直接对调是逐位精确的，而放到 M/S 域里
            // （把侧信号取反）在浮点上会差 1 ULP——判据用的是逐位比较，当场就红了。
            // 「数学上精确、浮点上不是」这种差别在判据面前一律当错处理，改代码比改判据对。
            if (p.channelSwap) {
                float t = l;
                l = r;
                r = t;
            }
            // ① M/S（0.5 系数：往返逐位相同）。
            //    只在真的需要 M/S 域里做事时才走这一趟：宽度为 1、不做低音单声道、
            //    不做侧向延迟时，M/S 往返在浮点上会差 1 ULP，而判据是**逐位**比较的。
            boolean needMs = Math.abs(p.width - 1.0) > 1e-9 || p.bassMonoHz > 0.0
                    || sideDelayFrames > 0;
            double m = needMs ? (l + r) * 0.5 : 0.0;
            double s = needMs ? (l - r) * 0.5 : 0.0;
            // ② 宽度
            s *= p.width;
            // ③ 低音单声道：侧信号过 LR4 高通（两级级联的巴特沃斯高通）
            if (p.bassMonoHz > 0.0) {
                s = sideHighB.process(sideHighA.process(s));
            }
            // ④ 侧向延迟（Haas）
            if (sideDelayFrames > 0) {
                int readAt = ((sideDelayAt - sideDelayFrames) % sideDelay.length
                        + sideDelay.length) % sideDelay.length;
                double delayed = sideDelay[readAt];
                sideDelay[sideDelayAt] = (float) s;
                sideDelayAt = (sideDelayAt + 1) % sideDelay.length;
                s = delayed;
            }
            double outL = needMs ? m + s : l;
            double outR = needMs ? m - s : r;
            // ⑥ 交叉馈送（耳机）：对侧信号低通 + 衰减 + 一点点延迟后加过来
            if (p.crossfeed > 1e-9) {
                // 左声道那一份低通之后延迟，加到**右**声道；右边那一份加到**左**（交叉）。
                // 电平律与 StereoParams 的文档一致：交叉量 0.5 时对侧正好 −6 dB。
                double fromLeft = feedLow.process(outL);
                double fromRight = feedLow2.process(outR);
                int readAt = ((feedDelayAt - feedDelayFrames) % feedDelayLeft.length
                        + feedDelayLeft.length) % feedDelayLeft.length;
                double leftDelayed = feedDelayLeft[readAt];
                double rightDelayed = feedDelayRight[readAt];
                feedDelayLeft[feedDelayAt] = (float) fromLeft;
                feedDelayRight[feedDelayAt] = (float) fromRight;
                feedDelayAt = (feedDelayAt + 1) % feedDelayLeft.length;
                double keep = 1.0 - 0.5 * p.crossfeed;
                outL = outL * keep + rightDelayed * p.crossfeed;
                outR = outR * keep + leftDelayed * p.crossfeed;
            }
            backing[baseL + i] = (float) outL;
            backing[baseR + i] = (float) outR;
        }
    }

    @Override
    public String toString() {
        return "StereoModule{" + params + "}";
    }
}
