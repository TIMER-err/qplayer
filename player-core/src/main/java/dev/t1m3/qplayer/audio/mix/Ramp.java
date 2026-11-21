package dev.t1m3.qplayer.audio.mix;

/**
 * 一条参数曲线（对照 Apple 参考实现 {@code include/am_timing.h} 的 {@code am_ramp} ✓）。
 *
 * <pre>
 * typedef struct {char parameter[96];double start,end,first,last;unsigned curve;} am_ramp;
 * </pre>
 *
 * <p>语义与参考一致：在 {@code [start, end]}（**秒** ✓）区间内，参数从 {@code first}
 * 线性/曲线过渡到 {@code last}；区间外取最近的端点值 ✓。
 *
 * <p>为什么要它：我们的 EQ / 增益 / duck 现在是"按 progress 现算"✗，
 * 而参考是"编译成时间线上的一条条 ramp ✓ 运行期只查表"✓。
 * 结构上的差别就是"什么时候用 EQ"能不能被声明清楚 ✗ vs ✓。
 */
public final class Ramp {

    /** 曲线形状（对应参考的 {@code curve} 字段 ✓）。 */
    public enum Curve {
        /** 线性 ✓ */
        LINEAR,
        /** 余弦缓入缓出（两端斜率为 0 ✓）。 */
        COSINE
    }

    public final String parameter;
    public final double start;
    public final double end;
    public final double first;
    public final double last;
    public final Curve curve;

    public Ramp(String parameter, double startSecs, double endSecs,
                double first, double last, Curve curve) {
        this.parameter = parameter == null ? "" : parameter;
        this.start = startSecs;
        this.end = Math.max(startSecs, endSecs);
        this.first = first;
        this.last = last;
        this.curve = curve == null ? Curve.LINEAR : curve;
    }

    /** 在时刻 {@code secs} 的取值 ✓（区间外取端点 ✓）。 */
    public double valueAt(double secs) {
        if (end <= start) {
            // 零长度：区间是"瞬间"的 ✓ ⇒ 到点即取终点值 ✓（判据钉着这条 ✓）
            return secs >= end ? last : first;
        }
        if (secs <= start) {
            return first;
        }
        if (secs >= end) {
            return last;
        }
        final double t = (secs - start) / (end - start);
        final double w = curve == Curve.COSINE ? 0.5 - 0.5 * Math.cos(Math.PI * t) : t;
        return first + (last - first) * w;
    }
}
