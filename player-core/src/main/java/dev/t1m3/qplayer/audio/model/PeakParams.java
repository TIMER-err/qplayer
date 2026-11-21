package dev.t1m3.qplayer.audio.model;

/**
 * 峰值拾取参数（上游 {@code sonalis-model/src/beat.rs} 的 {@code PeakParams}）。
 *
 * <h2>与上游的类型差异（按 docs/audio-port.md 记录）</h2>
 *
 * <ul>
 *   <li>上游字段是 {@code pub}，{@code Default} 给出默认值；这里同样保留公开可变字段
 *       + {@link #defaults()} 工厂，理由见 {@link MelParams}（Java 没有
 *       {@code ..Default::default()} 字面量更新，也没有 {@code Default} trait）。</li>
 *   <li>{@code usize} → {@code int}，{@code f32} → {@code float}。上游字段类型上
 *       不可能为负；Java 的 {@code int} 可以，但 {@link Peaks#pickPeaks} 的算路
 *       对负数同样成立（{@code preMax}/{@code postMax} 取负会让局部极大的窗口收窄，
 *       {@code minGapFrames} 取负等价于不做间隔检查），<b>不额外校验</b>——
 *       加校验就与上游行为分叉了。</li>
 *   <li>{@code #[derive(Clone, PartialEq)]} → 复制构造 + {@link #equals}/{@link #hashCode}。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_model::beat::PeakParams}。
 */
public final class PeakParams {

    /** 只保留高于 {@code 阈值 = 最大值 × this} 的峰。 */
    public float thresholdRatio;

    /** 两个峰之间至少隔多少帧。 */
    public int minGapFrames;

    /** 判定局部极大时向前看的帧数。 */
    public int preMax;

    /** 向后看的帧数。 */
    public int postMax;

    /** 从激活值里减掉的常数（模型常有非零基线）。 */
    public float offset;

    /** 全参构造（字段顺序与上游 {@code struct PeakParams} 的声明顺序一致）。 */
    public PeakParams(float thresholdRatio, int minGapFrames, int preMax, int postMax, float offset) {
        this.thresholdRatio = thresholdRatio;
        this.minGapFrames = minGapFrames;
        this.preMax = preMax;
        this.postMax = postMax;
        this.offset = offset;
    }

    /** 复制构造（上游 {@code #[derive(Clone)]}）。 */
    public PeakParams(PeakParams other) {
        this(other.thresholdRatio, other.minGapFrames, other.preMax, other.postMax, other.offset);
    }

    /**
     * 默认参数：与 Beat This! 论文里的后处理同量级——±3 帧的局部极大、
     * 减 0.5 倍最大值的阈值。
     *
     * <p><b>数值要对上游核对</b>，理由同 {@link MelParams#defaults()}：
     * 后处理参数与训练时的约定不一致，不会报错，只会让拍点悄悄变差。
     */
    public static PeakParams defaults() {
        return new PeakParams(0.5f, 3, 3, 3, 0.0f);
    }

    /** 对应上游 derive 的 {@code PartialEq}（五个字段全比）。 */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PeakParams)) {
            return false;
        }
        PeakParams p = (PeakParams) o;
        return Float.compare(thresholdRatio, p.thresholdRatio) == 0
                && minGapFrames == p.minGapFrames
                && preMax == p.preMax
                && postMax == p.postMax
                && Float.compare(offset, p.offset) == 0;
    }

    @Override
    public int hashCode() {
        int h = Float.floatToIntBits(thresholdRatio);
        h = 31 * h + minGapFrames;
        h = 31 * h + preMax;
        h = 31 * h + postMax;
        h = 31 * h + Float.floatToIntBits(offset);
        return h;
    }

    /** 镜像上游 {@code #[derive(Debug)]}（字段名保持上游的下划线写法）。 */
    @Override
    public String toString() {
        return "PeakParams { threshold_ratio: " + thresholdRatio
                + ", min_gap_frames: " + minGapFrames
                + ", pre_max: " + preMax
                + ", post_max: " + postMax
                + ", offset: " + offset + " }";
    }
}
