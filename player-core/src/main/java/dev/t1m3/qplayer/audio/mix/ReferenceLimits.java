package dev.t1m3.qplayer.audio.mix;

/**
 * <b>参考包的 limits</b>（plan_transition 的默认值 + 可覆盖项，逐字段对齐）。
 *
 * <p>默认值与参考包一致：可以重叠、可以变速、最大复杂度 3、不保留专辑无缝、
 * 当前出场时间 0、准备余量 1 s、**只收原生的默认 16 个风格**。
 */
public final class ReferenceLimits {

    public boolean supportsOverlap = true;
    public boolean supportsSpeedRamp = true;
    public int maxComplexity = 3;
    public boolean preserveAlbumGapless = false;
    public double currentOutgoingTime = 0.0;
    public double setupMargin = 1.0;
    public boolean nativeDefaultStylesOnly = true;
}
