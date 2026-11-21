package dev.t1m3.qplayer.audio.mix;

/**
 * <b>candidate_paths / recovered_logic 的"区域索引算术"端口</b>。
 *
 * <p>参考包用 <b>beatEvents 索引</b>（整数拍序号）而不是秒来表达候选区间，
 * 这样"第几小节到第几小节"就是整数运算，不引入浮点误差。三组规则：
 * <ul>
 *   <li>{@code normalized_incoming_beats}：把进场拍数换算到出场拍速（alias 半/一/两倍）；</li>
 *   <li>{@code scale_incoming_region}：以进场区<b>末端</b>为锚，长度取"完整小节"再对齐到
 *       4 拍（alias ≤ 1）或 2 拍（alias = 2）的量子；</li>
 *   <li>{@code shift_region_pair}：两侧按小节数整体平移（出场 4·bars，进场 4·bars/alias）。</li>
 * </ul>
 *
 * <p>另有两条响度窗（{@code loudness_windows} / {@code incoming_loudness_windows}），
 * 它们是各路径"看哪一段响度"的定义，窗可以**超出**候选区（参考包就是这么写的）。
 */
public final class ReferenceRegions {

    private ReferenceRegions() {
    }

    /** 参考包的 BeatRegion：拍索引区间。 */
    public static final class BeatRegion {
        public final int start;
        public final int end;

        public BeatRegion(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public int beatCount() {
            return end - start;
        }

        @Override
        public String toString() {
            return "[" + start + ',' + end + ')';
        }
    }

    /** 一对区间（shift_region_pair 的返回）。 */
    public static final class Pair {
        public final BeatRegion outgoing;
        public final BeatRegion incoming;

        public Pair(BeatRegion outgoing, BeatRegion incoming) {
            this.outgoing = outgoing;
            this.incoming = incoming;
        }
    }

    /** 两个浮点区间（响度窗）。 */
    public static final class Windows {
        public final double aStart;
        public final double aEnd;
        public final double bStart;
        public final double bEnd;

        public Windows(double aStart, double aEnd, double bStart, double bEnd) {
            this.aStart = aStart;
            this.aEnd = aEnd;
            this.bStart = bStart;
            this.bEnd = bEnd;
        }
    }

    /** {@code normalized_incoming_beats}：alias 0.5 折半、1 不变、2 加倍（整除）。 */
    public static int normalizedIncomingBeats(int beatCount, double alias) {
        if (alias != 0.5 && alias != 1.0 && alias != 2.0) {
            throw new IllegalArgumentException("A recovered binary tempo alias is required");
        }
        if (beatCount < 0) {
            throw new IllegalArgumentException("A nonnegative integer beat count is required");
        }
        if (alias == 0.5) {
            return beatCount / 2;
        }
        return alias == 1.0 ? beatCount : beatCount * 2;
    }

    /**
     * {@code scale_incoming_region}：以进场区末端为锚，长度 = min(完整小节, 换算后的出场拍数)，
     * 再对齐到 4 拍（alias ≤ 1）或 2 拍（alias = 2）。
     *
     * @return {@code null} 表示越界（参考包用 None 表达）
     */
    public static BeatRegion scaleIncomingRegion(BeatRegion incoming, int outgoingBeatCount,
                                                 double alias, Integer beatEventCount) {
        if (alias != 0.5 && alias != 1.0 && alias != 2.0) {
            throw new IllegalArgumentException("A recovered binary tempo alias is required");
        }
        if (outgoingBeatCount < 0 || incoming.end < 0) {
            throw new IllegalArgumentException("Nonnegative endpoint and beat count required");
        }
        int target = alias == 0.5 ? outgoingBeatCount * 2
                : (alias == 1.0 ? outgoingBeatCount : outgoingBeatCount / 2);
        int quantum = alias == 2.0 ? 2 : 4;
        int limit = incoming.end / 4 * 4;
        int length = Math.min(limit, target) / quantum * quantum;
        BeatRegion result = new BeatRegion(incoming.end - length, incoming.end);
        if (beatEventCount != null && result.end >= beatEventCount.intValue()) {
            return null;
        }
        return result;
    }

    /** {@code shift_region_pair}：出场 +4·bars，进场 +4·bars/alias（整数除法）。 */
    public static Pair shiftRegionPair(BeatRegion outgoing, BeatRegion incoming,
                                       int outgoingBars, int incomingBars, double alias,
                                       Integer outgoingEventCount, Integer incomingEventCount) {
        if (alias != 0.5 && alias != 1.0 && alias != 2.0) {
            throw new IllegalArgumentException("A recovered binary tempo alias is required");
        }
        int outOffset = outgoingBars * 4;
        int inOffset = (int) (incomingBars * 4 / alias);
        BeatRegion out = new BeatRegion(outgoing.start + outOffset, outgoing.end + outOffset);
        BeatRegion inc = new BeatRegion(incoming.start + inOffset, incoming.end + inOffset);
        if (Math.min(out.start, inc.start) < 0) {
            return null;
        }
        if (outgoingEventCount != null && out.end >= outgoingEventCount.intValue()) {
            return null;
        }
        if (incomingEventCount != null && inc.end >= incomingEventCount.intValue()) {
            return null;
        }
        return new Pair(out, inc);
    }

    /** {@code shift_bars_for}：各路径/风格的小节平移量（平移后的口径）。 */
    public static int[] shiftBarsFor(String path, int styleId) {
        if ("dance-shifted".equals(path) && styleId == 17) {
            return new int[] {2, 2};
        }
        if ("filtered-shifted".equals(path) && styleId == 12) {
            return new int[] {0, 4};
        }
        if ("hiphop-shifted".equals(path) && styleId == 15) {
            return new int[] {1, 1};
        }
        if ("pop-scaled-shifted".equals(path)) {
            if (styleId == 16) {
                return new int[] {0, 2};
            }
            if (styleId == 20) {
                return new int[] {2, 1};
            }
        }
        return new int[] {0, 0};
    }

    /**
     * {@code loudness_windows}：路径各自的出场侧响度窗。
     *
     * <p>dance-shifted = (0, 8/9, 8/9)；hiphop-shifted = (−0.25, 1, 1)；
     * pop-scaled-shifted = (−0.25·incoming_bars, 1, 1)；其余 (0, 1, 1)。
     * 窗可以超出区间（参考包注释明写 "windows may extend outward"）。
     */
    public static Windows loudnessWindows(double start, double end, String path, int styleId) {
        double a;
        double b;
        double c;
        if ("dance-shifted".equals(path)) {
            a = 0.0;
            b = 8.0 / 9.0;
            c = 8.0 / 9.0;
        } else if ("hiphop-shifted".equals(path)) {
            a = -0.25;
            b = 1.0;
            c = 1.0;
        } else if ("pop-scaled-shifted".equals(path)) {
            int incomingBars = shiftBarsFor(path, styleId)[1];
            a = -0.25 * incomingBars;
            b = 1.0;
            c = 1.0;
        } else {
            a = 0.0;
            b = 1.0;
            c = 1.0;
        }
        double length = end - start;
        double split = start + (a + b) * length;
        return new Windows(start + a * length, split, split, start + (a + b + c) * length);
    }

    /** {@code incoming_loudness_windows}：进场侧窗（dance-shifted 用 8/9，其余 1）。 */
    public static Windows incomingLoudnessWindows(double start, double end, String path) {
        double fraction = "dance-shifted".equals(path) ? 8.0 / 9.0 : 1.0;
        double duration = end - start;
        double split = start + fraction * duration;
        return new Windows(start, split, split, start + 2.0 * fraction * duration);
    }

    /**
     * {@code aligned_vocal_overlap_incompatible}：对齐后逐小节取 min，最大值非 0 即冲突。
     *
     * <p>入参是对齐好的等级（0..4，或 null/5 表示未知）；任一侧为 null 视为**不兼容**。
     */
    public static boolean alignedVocalOverlapIncompatible(Integer[] outgoing, Integer[] incoming) {
        if (outgoing == null || incoming == null) {
            return true;
        }
        int best = -1;
        int n = Math.min(outgoing.length, incoming.length);
        for (int i = 0; i < n; i++) {
            Integer a = outgoing[i];
            Integer b = incoming[i];
            if (a == null || b == null || a.intValue() == 5 || b.intValue() == 5) {
                continue;
            }
            best = Math.max(best, Math.min(a.intValue(), b.intValue()));
        }
        return best > 0;
    }

    /** {@code outgoing_loudness_significant}：地图可用而均值 > −15 dB；地图缺失一律 True。 */
    public static boolean outgoingLoudnessSignificant(Double regionMean, boolean mapAvailable) {
        if (!mapAvailable) {
            return true;
        }
        return regionMean != null && regionMean.doubleValue() > -15.0;
    }
}
