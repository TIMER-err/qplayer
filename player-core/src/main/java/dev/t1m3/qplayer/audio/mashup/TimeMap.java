package dev.t1m3.qplayer.audio.mashup;

/**
 * <b>输出时间轴 → 源帧位置</b>的分段线性映射（2026-11-21，mashup 第一阶段）。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>现有过渡的变速只有<b>窗口级</b>曲线（{@code TempoRamp} 按 {@code windowSecs}
 * 设计，实测只覆盖 1~3 s）。mashup 要在整首歌（2~5 分钟）上把 A 的拍点<b>每一拍</b>
 * 都对到 B 的拍点上，那就需要一条<b>全程</b>的映射，而且这条映射必须能被验证：
 * 锚点处精确命中、段内速度有界、速度变化率有界（否则听感是"忽快忽慢"）。
 *
 * <h2>为什么是"分段线性"而不是"每段一个速度"</h2>
 *
 * <p>两者在数学上等价（段内速度就是斜率），但<b>存锚点</b>而不是存速度有个关键好处：
 * 锚点是"第 k 拍对到第 k 拍"这件事本身，{@link #srcAt(long)} 在锚点处<b>精确</b>
 * 返回 {@code srcAnchor[i]}（整数运算，没有累积误差）；而"存速度再积分"的方式一旦
 * 有浮点误差，就会在 3 分钟里累积成几十毫秒的漂移——那正是要防的东西。
 *
 * <p>与 {@code TempoBridge} 的"搜索位置锚定目标时钟、不接着上一块算"是同一条纪律。
 *
 * <h2>不变式（构造时强制，逐条有测试）</h2>
 *
 * <ol>
 *   <li>锚点至少两个，且 {@code outAnchor} 与 {@code srcAnchor} 都<b>严格递增</b>；
 *   <li>段内速度 = {@code Δsrc / Δout}，只读不算——不在这里做钳位，
 *       "允不允许 ±8%"是规划层（{@code MashupPlanner}）的事，这里只如实报告；
 *   <li>锚点处 {@code srcAt(outAnchor[i]) == srcAnchor[i]} 精确成立。
 * </ol>
 *
 * <p>区间之外（{@code out < outAnchor[0]} 或 {@code out ≥ outAnchor[last]}）按
 * <b>首段/末段的速度外推</b>：这样速度曲线在边界上连续，不会在开头或结尾突然"停住"。
 * 调用方要不要用外推区间由它自己决定（渲染器只读 {@code [0, outFrames)} 内的位置）。
 */
public final class TimeMap {

    /** 输出帧锚点（严格递增）。 */
    private final long[] out;

    /** 源帧锚点（严格递增）。 */
    private final long[] src;

    /** 段速度：{@code speed[i]} 属于 {@code [i, i+1)}；长度 = 锚点数 − 1。 */
    private final double[] speed;

    private TimeMap(long[] out, long[] src) {
        this.out = out;
        this.src = src;
        this.speed = new double[out.length - 1];
        for (int i = 0; i < speed.length; i++) {
            speed[i] = (double) (src[i + 1] - src[i]) / (double) (out[i + 1] - out[i]);
        }
    }

    /**
     * 按两组锚点建一条映射。
     *
     * @param outAnchor 输出帧锚点（严格递增，至少两个）
     * @param srcAnchor 源帧锚点（严格递增，与 {@code outAnchor} 等长）
     * @throws IllegalArgumentException 锚点不足两个、长度不等、或任一序列不严格递增
     */
    public static TimeMap of(long[] outAnchor, long[] srcAnchor) {
        if (outAnchor == null || srcAnchor == null) {
            throw new IllegalArgumentException("锚点不能为空");
        }
        if (outAnchor.length != srcAnchor.length) {
            throw new IllegalArgumentException(
                    "两组锚点必须等长：" + outAnchor.length + " vs " + srcAnchor.length);
        }
        if (outAnchor.length < 2) {
            throw new IllegalArgumentException("至少需要两个锚点才能定义速度（现在是 "
                    + outAnchor.length + " 个）");
        }
        long[] out = outAnchor.clone();
        long[] src = srcAnchor.clone();
        requireStrictlyIncreasing(out, "输出帧锚点");
        requireStrictlyIncreasing(src, "源帧锚点");
        return new TimeMap(out, src);
    }

    /**
     * 恒速 1:1 的映射：输出第 {@code i} 帧对应源第 {@code i} 帧。
     *
     * @param frames 长度（≥1；{@code 0} 长度没有"时间轴"可言，直接拒绝而不是造一个退化对象）
     */
    public static TimeMap identity(long frames) {
        if (frames < 1) {
            throw new IllegalArgumentException("恒等映射的帧数必须 ≥ 1：" + frames);
        }
        return new TimeMap(new long[]{0L, frames}, new long[]{0L, frames});
    }

    /** 锚点数（≥2）。 */
    public int anchors() {
        return out.length;
    }

    /** 第 {@code i} 个输出帧锚点。 */
    public long outAnchor(int i) {
        return out[i];
    }

    /** 第 {@code i} 个源帧锚点。 */
    public long srcAnchor(int i) {
        return src[i];
    }

    /** 第 {@code segment} 段（{@code segment ∈ [0, anchors-1)}）的速度。 */
    public double speed(int segment) {
        if (segment < 0 || segment >= speed.length) {
            throw new IndexOutOfBoundsException("段号越界：" + segment
                    + "（共 " + speed.length + " 段）");
        }
        return speed[segment];
    }

    /** 全部段速度的最小值。 */
    public double minSpeed() {
        double m = Double.POSITIVE_INFINITY;
        for (double s : speed) {
            m = Math.min(m, s);
        }
        return m;
    }

    /** 全部段速度的最大值。 */
    public double maxSpeed() {
        double m = Double.NEGATIVE_INFINITY;
        for (double s : speed) {
            m = Math.max(m, s);
        }
        return m;
    }

    /**
     * 第一段速度越界的段号；全部在 {@code [min, max]} 内时返回 {@code -1}。
     *
     * <p>给规划层当判据用（整曲 ±8%），<b>不在这里自动钳位</b>：钳位会悄悄破坏
     * "锚点精确命中"，那正是这条映射唯一不能妥协的东西。
     */
    public int firstSpeedOutside(double min, double max) {
        for (int i = 0; i < speed.length; i++) {
            if (speed[i] < min || speed[i] > max) {
                return i;
            }
        }
        return -1;
    }

    /** 第一个输出帧锚点（通常 0）。 */
    public long outStart() {
        return out[0];
    }

    /** 最后一个输出帧锚点。 */
    public long outEnd() {
        return out[out.length - 1];
    }

    /** 第一个源帧锚点。 */
    public long srcStart() {
        return src[0];
    }

    /** 最后一个源帧锚点。 */
    public long srcEnd() {
        return src[src.length - 1];
    }

    /**
     * {@code outFrame} 对应的源位置（帧，可为小数）。
     *
     * <p><b>锚点处精确</b>：{@code outFrame == outAnchor[i]} 时返回整数
     * {@code srcAnchor[i]}（插值公式的 {@code u = 0} 分支使它逐位精确）。
     * 调用方要取整采样请自己 {@code Math.floor}——这里返回小数是为了让渲染器
     * 能用同一套算术算"块尾位置"而不引入额外舍入。
     */
    public double srcAt(long outFrame) {
        int i = segmentOf(outFrame);
        if (outFrame <= out[0]) {
            return src[0] + (outFrame - out[0]) * speed[0];
        }
        if (outFrame >= out[out.length - 1]) {
            return src[src.length - 1]
                    + (outFrame - out[out.length - 1]) * speed[speed.length - 1];
        }
        // i 是所在段的下标：out[i] <= outFrame < out[i+1]
        return src[i] + (double) (outFrame - out[i]) * speed[i];
    }

    /** {@code outFrame} 处的速度（段内常数）。 */
    public double speedAt(long outFrame) {
        return speed[segmentOf(outFrame)];
    }

    /**
     * {@code outFrame} 落在哪一段（返回段下标 {@code 0..anchors-2}）。
     *
     * <p>区间左端（{@code outFrame <= out[0]}）算首段、右端（{@code ≥ outEnd}）算末段——
     * 与外推方向一致，所以 {@link #srcAt(long)} 与 {@link #speedAt(long)} 在边界上
     * 不会互相矛盾。二分查找：锚点数通常几百个，用不着更快。
     */
    private int segmentOf(long outFrame) {
        int last = out.length - 2;
        if (outFrame <= out[0]) {
            return 0;
        }
        if (outFrame >= out[out.length - 1]) {
            return last;
        }
        int lo = 0;
        int hi = out.length - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (out[mid] <= outFrame) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return Math.min(lo, last);
    }

    private static void requireStrictlyIncreasing(long[] a, String what) {
        for (int i = 1; i < a.length; i++) {
            if (a[i] <= a[i - 1]) {
                throw new IllegalArgumentException(what + "必须严格递增：第 " + i
                        + " 个是 " + a[i] + "，前一个是 " + a[i - 1]);
            }
        }
    }

    @Override
    public String toString() {
        return "TimeMap{" + out.length + " 锚点, "
                + String.format(java.util.Locale.ROOT, "速度 %.4f~%.4f",
                minSpeed(), maxSpeed())
                + ", 输出 " + outStart() + ".." + outEnd()
                + ", 源 " + srcStart() + ".." + srcEnd() + "}";
    }
}
