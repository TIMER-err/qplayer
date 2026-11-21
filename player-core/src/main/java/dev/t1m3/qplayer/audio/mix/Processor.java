package dev.t1m3.qplayer.audio.mix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个 DSP 单元在时间线上的占位 + 它的一串参数曲线
 * （对照 Apple 参考实现 {@code include/am_timing.h} 的 {@code am_processor} ✓）。
 *
 * <pre>
 * typedef struct {char name[64];am_range placement;am_ramp *ramps;size_t count;} am_processor;
 * </pre>
 *
 * <p>用法（照着参考的形状 ✓）：
 * <pre>
 * Processor eq = new Processor("eq", 0.0, 8.0)
 *         .ramp("hp_cutoff_freq", 0.0, 8.0, 80.0, 40.0, Ramp.Curve.COSINE)
 *         .ramp("out_gain", 0.0, 8.0, -1.5, 0.0, Ramp.Curve.LINEAR);
 * double cutoff = eq.control("hp_cutoff_freq", 3.2, 80.0);
 * </pre>
 *
 * <p>现在还没有调用点 ✓（先立结构 ✓ 再逐步把 EQ/增益/duck 迁上来 ✓）；
 * 参考实现里同一个名字的处理器就是"什么时候用"的唯一声明处 ✓，
 * 这正是我们目前散在 if 里 ✗ 的那部分。
 */
public final class Processor {

    public final String name;
    /** 这个单元在时间线上的占位（秒 ✓）。 */
    public final double placementStart;
    public final double placementEnd;

    private final List<Ramp> ramps = new ArrayList<>();

    public Processor(String name, double placementStartSecs, double placementEndSecs) {
        this.name = name == null ? "" : name;
        this.placementStart = placementStartSecs;
        this.placementEnd = Math.max(placementStartSecs, placementEndSecs);
    }

    /** 加一条参数曲线 ✓（返回自身以便链式 ✓）。 */
    public Processor ramp(String parameter, double startSecs, double endSecs,
                          double first, double last, Ramp.Curve curve) {
        ramps.add(new Ramp(parameter, startSecs, endSecs, first, last, curve));
        return this;
    }

    /** 只读的曲线列表 ✓。 */
    public List<Ramp> ramps() {
        return Collections.unmodifiableList(ramps);
    }

    /** 这个单元在 {@code secs} 时刻是否生效 ✓（占位区间内 ✓）。 */
    public boolean activeAt(double secs) {
        return secs >= placementStart && secs < placementEnd;
    }

    /**
     * 取参数值 ✓（等价参考的 {@code am_control(side, parameter, t, fallback)} ✓）：
     * 没有这个参数 ⇒ 返回 {@code fallback} ✓。
     */
    public double control(String parameter, double secs, double fallback) {
        for (Ramp r : ramps) {
            if (r.parameter.equals(parameter)) {
                return r.valueAt(secs);
            }
        }
        return fallback;
    }
}
