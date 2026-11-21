package dev.t1m3.qplayer.audio.mix;

/**
 * <b>Apple 的窗口/速率规则</b>（2026-10-09 ✓；逐字来自他的参考实现 ✓）。
 *
 * <p>出处：{@code automix-c/reference-python/alignment.py} 的
 * {@code unstructured_rate_endpoints} ✓：
 * <pre>
 * M   = alias * incomingBarCount / outgoingBarCount      // alias ∈ {0.5, 1, 2}（原生枚举 0/1/2）
 * Li' = Li / M                                           // 进场源长度折算到出场时间基
 * A 终点速率 = Lo / Li'                                   // 两侧长度都为正时；否则 1.0
 * B 起点速率：把 (Li'/Lo) 在**虚拟线性斜坡** [incomingEnd - Li', incomingEnd] 上采样
 *             —— 起点落在斜坡起点 ⇒ Li'/Lo；落在终点 ⇒ 1.0；落在中间 ⇒ 线性插值
 * 长度非正 / 不可用 ⇒ **unity（1.0）回退**（"不做变速"）
 * </pre>
 *
 * <p>他的注释特别写道：*"It is <b>not</b> always the outgoing reciprocal"* ✓ —— 所以我们原来
 * 那个 {@code endB = 1/endA} 只是**近似** ✗；本类按他的采样规则精确实现 ✓。
 *
 * <p><b>单位说明</b> ✓：{@code barsA/barsB} 是两侧在这一次过渡里**各跨了几个小节** ✓（由引擎选 ✓
 * —— 他的文档：*"The caller owns beat/bar selection"* ✓）；`Lo/Li/incomingStart/incomingEnd`
 * 都是**秒** ✓（源时间轴上的长度与位置 ✓）。本类是纯函数 ✓、零分配 ✓。
 */
public final class AppleWindowRule {

    /** 原生只允许这三档 alias ✓（索引 0/1/2 ⇒ 0.5 / 1 / 2 ✓）。 */
    public static final double[] ALIAS_FACTORS = {0.5, 1.0, 2.0};

    /** 结果 ✓。 */
    public static final class Result {
        public final double m;
        public final double scaledIncomingLen;
        public final double outgoingEndRate;
        public final double incomingStartRate;
        public final double virtualRampStart;
        public final double virtualRampEnd;

        Result(double m, double scaledIncomingLen, double outgoingEndRate,
               double incomingStartRate, double virtualRampStart, double virtualRampEnd) {
            this.m = m;
            this.scaledIncomingLen = scaledIncomingLen;
            this.outgoingEndRate = outgoingEndRate;
            this.incomingStartRate = incomingStartRate;
            this.virtualRampStart = virtualRampStart;
            this.virtualRampEnd = virtualRampEnd;
        }
    }

    private AppleWindowRule() {
    }

    /**
     * <b>由 BPM 取一小节的秒数</b>（4/4 拍 ✓ —— 我们的 `AutoCue` 网格也是按这个口径 ✓）。
     */
    public static double barSecs(double bpm) {
        return bpm > 0.0 ? 4.0 * 60.0 / bpm : 0.0;
    }

    /**
     * <b>把窗口取成"整小节"</b>（用户 2026-10-09：「把窗口改成按小节算」✓，并说「他的窗口绝对不短」✓）。
     *
     * <p>取法：两侧各取 {@code targetBars} 个小节 ✓，窗口 = **两者的小者** ✓（保证两边都装得下 ✓），
     * 并对齐到**出场侧**的整小节 ✓。{@code targetBars <= 0} ⇒ 不启用（返回现在的窗口 ✓，可回退 ✓）。
     */
    public static double barAlignedWindow(double windowSecsNow, double bpmA, double bpmB,
                                          int targetBars) {
        if (targetBars <= 0) {
            return windowSecsNow;
        }
        double barA = barSecs(bpmA);
        double barB = barSecs(bpmB);
        if (!(barA > 0.0) || !(barB > 0.0)) {
            return windowSecsNow;                 // 取不到 BPM ⇒ 不动 ✓（他的 unity 回退同一精神 ✓）
        }
        double lenA = targetBars * barA;
        double lenB = targetBars * barB;
        double len = Math.min(lenA, lenB);
        return Math.max(barA, Math.round(len / barA) * barA);   // 对齐到出场侧整小节 ✓
    }

    /** 由"整小节窗口"取两侧各自的**小节数**（用于 {@link #compute} ✓）。 */
    public static int barsFor(double windowSecs, double bpm) {
        double bar = barSecs(bpm);
        if (!(bar > 0.0)) {
            return 0;
        }
        return Math.max(1, (int) Math.round(windowSecs / bar));
    }

    /**
     * 按他的公式算一次 ✓。
     *
     * @param outgoingLen 出场源长度（秒 ✓，= Lo ✓）
     * @param incomingLen 进场源长度（秒 ✓，= Li ✓）
     * @param outgoingBars 出场侧跨的小节数 ✓（> 0 ✓）
     * @param incomingBars 进场侧跨的小节数 ✓（> 0 ✓）
     * @param aliasIndex 0/1/2 ⇒ 0.5/1/2 ✓
     * @param incomingStart 进场素材的**实际起点**（源时间轴 ✓，秒 ✓）
     * @param incomingEnd 进场素材的**终点**（源时间轴 ✓）—— 虚拟斜坡以它收尾 ✓
     */
    public static Result compute(double outgoingLen, double incomingLen,
                                 double outgoingBars, double incomingBars,
                                 int aliasIndex, double incomingStart, double incomingEnd) {
        if (!(outgoingBars > 0.0) || !(incomingBars > 0.0)
                || aliasIndex < 0 || aliasIndex >= ALIAS_FACTORS.length) {
            throw new IllegalArgumentException("bars must be positive and aliasIndex 0..2");
        }
        double alias = ALIAS_FACTORS[aliasIndex];
        double m = alias * incomingBars / outgoingBars;
        double scaled = incomingLen / m;
        boolean positive = outgoingLen > 0.0 && scaled > 0.0;
        double outEnd = positive ? outgoingLen / scaled : 1.0;
        double provisional = positive ? scaled / outgoingLen : 1.0;
        double virtualStart = incomingEnd - scaled;
        double virtualEnd = incomingEnd;
        double inStart;
        if (incomingStart >= virtualEnd) {
            inStart = 1.0;                       // 原生：起点不在斜坡内 ⇒ 1.0 ✓（value 先查末点 ✓）
        } else if (incomingStart <= virtualStart) {
            inStart = provisional;
        } else {
            double progress = (incomingStart - virtualStart) / (virtualEnd - virtualStart);
            inStart = provisional + progress * (1.0 - provisional);
        }
        return new Result(m, scaled, outEnd, inStart, virtualStart, virtualEnd);
    }
}
